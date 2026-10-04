package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.CardIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase B's "done when", held: one card whatever its printing, and released where and when it is played. */
class CardTruthTest {

    private val ashId = CardId(14558127)
    private val ashAlt = CardId(14558128)
    private val ash = TestCards.ashBlossom.copy(
        tcgBanStatus = BanStatus.UNLIMITED,
        alternateIds = listOf(ashId, ashAlt),
        formats = listOf("TCG", "OCG", "Master Duel"),
        tcgDate = "2017-05-04",
        ocgDate = "2017-01-14",
        konamiId = 12950,
        genesysPoints = 20,
    )
    private val filler = (1..40).map { TestCards.monster(id = 1000 + it, name = "Filler $it", formats = listOf("TCG", "OCG")) }
    private val turtle = TestCards.monster(id = 72929454, name = "30,000-Year White Turtle", formats = listOf("OCG GOAT", "OCG"), ocgDate = "1999-10-17")
    private val upcoming = TestCards.monster(id = 5000001, name = "Adamancipator Conductor", formats = listOf("TCG", "OCG"), tcgDate = "2026-10-08", ocgDate = "2026-04-26")
    private val speedOnly = TestCards.monster(id = 5000002, name = "Bandit", formats = listOf("Speed Duel"), tcgDate = "2019-08-01")
    private val oldPool = TestCards.monster(id = 5000003, name = "From an old pool")
    private val index = CardIndex.build(listOf(ash, turtle, upcoming, speedOnly, oldPool) + filler)

    private fun deckWith(vararg ids: Int) = Deck(main = ids.map(::CardId) + filler.take(40 - ids.size).map { it.id })

    @Test
    fun anAlternateArtAshCountsAgainstTheThreeCopyLimit() {
        val deck = deckWith(14558127, 14558127, 14558128, 14558128)
        assertEquals(4, CardIdentity.copiesOf(deck, ash))
        assertEquals(mapOf(ashId to 4), CardIdentity.counts(deck.main, index::byId).filterKeys { it == ashId })
        val v = DeckValidator.validate(deck, index::byId, Format.TCG)
        assertTrue(v.errors.any { "Ash Blossom" in it.message && "deck has 4" in it.message }, v.errors.toString())
        // Reported once, not once per printing.
        assertEquals(1, v.errors.count { "Ash Blossom" in it.message })
    }

    @Test
    fun theEditorRefusesAFourthCopyByAnotherPrinting() {
        val three = Deck(main = listOf(ashId, ashAlt, ashAlt))
        val edit = DeckEditor.add(three, ash, DeckSection.MAIN, Format.TCG)
        assertIs<DeckEdit.Rejected>(edit)
        assertEquals(RejectionReason.COPY_LIMIT, edit.reason)
        assertEquals(0, DeckEditor.remainingCopies(three, ash))
        // setCount on one printing leaves room only for what the others have not taken.
        val set = DeckEditor.setCount(Deck(main = listOf(ashAlt, ashAlt)), ash, DeckSection.MAIN, 3)
        assertIs<DeckEdit.Applied>(set)
        assertEquals(3, CardIdentity.copiesOf(set.deck, ash))
    }

    @Test
    fun theDeckKeepsThePrintingItWasGiven() {
        val deck = Deck(main = listOf(ashAlt))
        val added = DeckEditor.add(deck, ash, DeckSection.MAIN)
        assertIs<DeckEdit.Applied>(added)
        assertEquals(listOf(ashAlt, ashId), added.deck.main)
    }

    @Test
    fun anOcgOnlyCardIsIllegalInTheTcgAndFineInTheOcg() {
        assertIs<Legality.Release.NotReleased>(Legality.release(turtle, Format.TCG))
        assertIs<Legality.Release.Legal>(Legality.release(turtle, Format.OCG))
        val deck = deckWith(turtle.id.value)
        val tcg = DeckValidator.validate(deck, index::byId, Format.TCG)
        assertTrue(tcg.errors.any { it.message == "30,000-Year White Turtle is not released in the TCG (OCG only)." }, tcg.errors.toString())
        val ocg = DeckValidator.validate(deck, index::byId, Format.OCG)
        assertTrue(ocg.isLegal, ocg.errors.toString())
    }

    @Test
    fun aSpeedDuelCardIsNoAdvancedCardThoughItHasATcgDate() {
        assertIs<Legality.Release.NotReleased>(Legality.release(speedOnly, Format.TCG))
    }

    @Test
    fun aCardNotOutYetSaysWhen() {
        val r = Legality.release(upcoming, Format.TCG, asOf = "2026-10-04")
        assertEquals(Legality.Release.NotYet(Format.TCG, "2026-10-08"), r)
        assertEquals("Adamancipator Conductor is not out in the TCG until 8 Oct 2026.", Legality.problem(upcoming, Format.TCG, "2026-10-04"))
        assertIs<Legality.Release.Legal>(Legality.release(upcoming, Format.TCG, asOf = "2026-10-08"))
        // Without a date, only the region is asked.
        assertIs<Legality.Release.Legal>(Legality.release(upcoming, Format.TCG))
        val v = DeckValidator.validate(deckWith(upcoming.id.value), index::byId, Format.TCG, asOf = "2026-10-04")
        assertTrue(v.errors.any { "until 8 Oct 2026" in it.message }, v.errors.toString())
    }

    @Test
    fun aPoolWithoutReleaseDataFailsNothing() {
        assertEquals(Legality.Release.Unknown, Legality.release(oldPool, Format.TCG, "2026-10-04"))
        assertNull(Legality.problem(oldPool, Format.TCG, "2026-10-04"))
        assertTrue(DeckValidator.validate(deckWith(oldPool.id.value), index::byId, Format.TCG, "2026-10-04").isLegal)
    }

    @Test
    fun genesysCountsEveryCopysPointsAndBarsLinksAndPendulums() {
        val deck = deckWith(14558127, 14558128, 14558127).copy(extra = listOf(TestCards.accesscode.id))
        val cards = CardIndex.build(listOf(ash, TestCards.accesscode.copy(genesysPoints = 0)) + filler.map { it.copy(genesysPoints = 0) })
        val r = GenesysRules.check(deck, cards::byId)
        assertEquals(60, r.points)
        assertTrue(r.problems.any { "Accesscode Talker" in it })
        val over = GenesysRules.check(deck.copy(extra = emptyList()), cards::byId, cap = 40)
        assertEquals(listOf("The deck costs 60 points; the cap is 40."), over.problems)
        // A card whose points the pool does not know counts 0 and is named.
        assertEquals(listOf(oldPool.id), GenesysRules.check(Deck(main = listOf(oldPool.id)), index::byId).unknown)
    }

    @Test
    fun readableDates() {
        assertEquals("14 Jan 2017", Legality.readable("2017-01-14"))
        assertEquals("soon", Legality.readable("soon"))
    }

    private fun TestCards.monster(id: Int, name: String, formats: List<String> = emptyList(), tcgDate: String? = null, ocgDate: String? = null): Card =
        monster(id = id, name = name).copy(formats = formats, tcgDate = tcgDate, ocgDate = ocgDate)
}
