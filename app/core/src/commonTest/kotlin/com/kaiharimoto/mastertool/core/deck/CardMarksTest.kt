package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.CardIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bar's `TCG | OCG | Genesys` and the card's corner (the 1.1.2 design review, findings 3, 5 and 8, kai's choices):
 * which choice the stored values show, a card's Genesys points, and the inverted ✕ on a card that fails.
 */
class CardMarksTest {
    private val maxx = TestCards.maxxC.copy(formats = listOf("TCG", "OCG"), tcgDate = "2011-02-08", genesysPoints = 50)
    private val talker = TestCards.accesscode.copy(formats = listOf("TCG", "OCG"), genesysPoints = 0)
    private val reborn = TestCards.monster(id = 83764718, name = "Monster Reborn", tcg = BanStatus.LIMITED).copy(formats = listOf("TCG", "OCG"), tcgDate = "2002-03-08")
    private val ash = TestCards.ashBlossom.copy(formats = listOf("TCG", "OCG"), tcgDate = "2017-11-17", genesysPoints = null, alternateIds = listOf(CardId(14558127), CardId(14558128)))
    private val filler = (1..40).map { TestCards.monster(id = 2000 + it, name = "Filler $it").copy(formats = listOf("TCG", "OCG"), tcgDate = "2002-03-08", genesysPoints = 1) }
    private val index = CardIndex.build(listOf(maxx, talker, reborn, ash) + filler)
    private fun deck(ids: List<CardId> = emptyList()) = Deck(main = ids + filler.take(40 - ids.size).map { it.id })

    @Test
    fun theBarShowsGenesysWhateverRegionIsStoredBesideIt() {
        assertEquals(PlayChoice.TCG, PlayChoice.of(Format.TCG, genesys = false))
        assertEquals(PlayChoice.OCG, PlayChoice.of(Format.OCG, genesys = false))
        assertEquals(PlayChoice.GENESYS, PlayChoice.of(Format.TCG, genesys = true))
        // 1.1.1 could store Genesys beside OCG: the bar says Genesys, and the region settles to TCG.
        assertEquals(PlayChoice.GENESYS, PlayChoice.of(Format.OCG, genesys = true))
        assertEquals(Format.TCG, PlayChoice.settledFormat(Format.OCG, genesys = true))
        assertNull(PlayChoice.settledFormat(Format.TCG, genesys = true))
        assertNull(PlayChoice.settledFormat(Format.OCG, genesys = false))
    }

    @Test
    fun genesysIsStoredAsTcgSoOlderBuildsReadARegion() {
        // What each choice writes: the region older builds read, and the Genesys switch.
        assertEquals(Format.TCG to true, PlayChoice.GENESYS.format to PlayChoice.GENESYS.genesys)
        assertEquals(Format.TCG to false, PlayChoice.TCG.format to PlayChoice.TCG.genesys)
        assertEquals(Format.OCG to false, PlayChoice.OCG.format to PlayChoice.OCG.genesys)
        assertEquals(listOf("TCG", "OCG", "Genesys"), PlayChoice.entries.map { it.label })
        assertEquals(PlayChoice.TCG, PlayChoice.GENESYS.next())
        assertEquals(PlayChoice.GENESYS, PlayChoice.parse(" genesys "))
        assertNull(PlayChoice.parse("speed"))
    }

    @Test
    fun genesysShowsPointsAndNoLimitMarks() {
        val marks = CardMarks(DeckRules(genesysCap = 100))
        assertEquals(CornerMark.Points(50), marks.of(maxx, limitMarks = true))
        // Worth nothing, or no points known: nothing in the corner.
        assertNull(marks.of(talker))
        assertNull(marks.of(ash))
        // Genesys has no list: a Limited card wears no 1 even with Limit marks on.
        assertNull(marks.of(reborn, limitMarks = true))
    }

    @Test
    fun theListsMarksAreAsBefore() {
        val today = CardMarks()
        assertEquals(CornerMark.Limit(0), today.of(maxx))
        assertNull(today.of(reborn))
        assertEquals(CornerMark.Limit(1), today.of(reborn, limitMarks = true))
        // A dated list's status, not the pool's.
        val april2005 = CardMarks(DeckRules(asOf = "2005-04-01", limits = BanSource { if (it.name == "Monster Reborn") BanStatus.FORBIDDEN else BanStatus.UNLIMITED }))
        assertEquals(CornerMark.Limit(0), april2005.of(reborn))
        assertNull(april2005.of(maxx))
    }

    @Test
    fun aCardThatFailsWearsTheCrossInTheDeckOnly() {
        // 1 Apr 2005: Maxx "C" is not out until 2011, and Monster Reborn is Forbidden on that day's list.
        val rules = DeckRules(asOf = "2005-04-01", limits = BanSource { if (it.name == "Monster Reborn") BanStatus.FORBIDDEN else BanStatus.UNLIMITED }, listName = "April 2005 Lists (TCG)")
        val d = deck(listOf(maxx.id, reborn.id))
        val failures = CardFailures.of(rules, d, index::byId, "2026-10-04")
        val marks = CardMarks(rules, failures)
        val onMaxx = marks.of(maxx, DeckSection.MAIN)
        assertTrue(onMaxx is CornerMark.Fails && "until 8 Feb 2011" in onMaxx.why, onMaxx.toString())
        val onReborn = marks.of(reborn, DeckSection.MAIN)
        assertTrue(onReborn is CornerMark.Fails && "Monster Reborn is Forbidden" in onReborn.why, onReborn.toString())
        // Out of the deck (the pool, a search) the same card wears the list's mark, never the cross.
        assertEquals(CornerMark.Limit(0), marks.of(reborn))
        // The fillers pass.
        assertEquals(null, marks.of(filler.first(), DeckSection.MAIN))
        assertEquals(setOf(maxx.id, reborn.id), failures.cards)
    }

    @Test
    fun failuresAreCountedByCard() {
        // Two Ash and two alternate-art Ash: four copies of one Limited card, and every printing fails.
        val d = deck(listOf(ash.id, ash.id, CardId(14558128), CardId(14558128)))
        val failures = CardFailures.of(DeckRules(), d, index::byId, "2026-10-04")
        val shown = index.byId(CardId(14558128))!!
        val why = CardMarks(DeckRules(), failures).of(shown, DeckSection.MAIN)
        assertTrue(why is CornerMark.Fails && "limited to 1" in why.why && "deck has 4" in why.why, why.toString())
    }

    @Test
    fun genesysBarsALinkWhereverItStandsAndTheCapMarksNoOne() {
        val d = deck(List(3) { maxx.id }).copy(extra = listOf(talker.id))
        val rules = DeckRules(genesysCap = 100)
        val marks = CardMarks(rules, CardFailures.of(rules, d, index::byId, "2026-10-04"))
        val onTalker = marks.of(talker, DeckSection.EXTRA)
        assertTrue(onTalker is CornerMark.Fails && "Link monster" in onTalker.why, onTalker.toString())
        // 187 points over a cap of 100 is the deck's problem, not one card's: Maxx keeps its points.
        assertEquals(CornerMark.Points(50), marks.of(maxx, DeckSection.MAIN))
    }

    @Test
    fun aCardInASectionItMayNotStandInFailsThereOnly() {
        val d = deck().copy(side = listOf(talker.id), main = deck().main.dropLast(1) + talker.id)
        val marks = CardMarks(DeckRules(), CardFailures.of(DeckRules(), d, index::byId, "2026-10-04"))
        val inMain = marks.of(talker, DeckSection.MAIN)
        assertTrue(inMain is CornerMark.Fails && "cannot be in the Main Deck" in inMain.why, inMain.toString())
        assertNull(marks.of(talker, DeckSection.SIDE))
    }
}
