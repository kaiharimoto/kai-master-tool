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
import kotlin.test.assertTrue

/** What the builder checks against (1.1.1): a day and its list, or Genesys, in one place. */
class DeckRulesTest {
    private val maxx = TestCards.maxxC.copy(formats = listOf("TCG", "OCG"), tcgDate = "2011-02-08", genesysPoints = 50)
    private val talker = TestCards.accesscode.copy(formats = listOf("TCG", "OCG"), genesysPoints = 0)
    private val filler = (1..40).map { TestCards.monster(id = 2000 + it, name = "Filler $it").copy(formats = listOf("TCG", "OCG"), genesysPoints = 1) }
    private val index = CardIndex.build(listOf(maxx, talker) + filler)
    private fun deck(ids: List<CardId> = emptyList()) = Deck(main = ids + filler.take(40 - ids.size).map { it.id })

    @Test
    fun theWordsSayWhatIsChecked() {
        assertEquals("TCG", DeckRules().words())
        assertEquals("OCG on 1 May 2025, by the April 2025 Lists (OCG)", DeckRules(Format.OCG, "2025-05-01", listName = "April 2025 Lists (OCG)").words())
        assertEquals("Genesys, 100 points", DeckRules(genesysCap = 100).words())
    }

    @Test
    fun aDatedListHoldsTheCopiesNotThePools() {
        val d = deck(listOf(maxx.id))
        assertTrue(DeckRules().validate(d, index::byId, "2026-10-04").errors.any { "Maxx" in it.message && "Forbidden" in it.message })
        val then = DeckRules(asOf = "2012-01-01", limits = BanSource { BanStatus.UNLIMITED }, listName = "Old list").validate(d, index::byId, "2026-10-04")
        assertTrue(then.isLegal, then.errors.toString())
        // Before it was released, the same day's check says when it came out.
        val before = DeckRules(asOf = "2010-01-01", limits = BanSource { BanStatus.UNLIMITED }).validate(d, index::byId, "2026-10-04")
        assertTrue(before.errors.any { "until 8 Feb 2011" in it.message }, before.errors.toString())
    }

    @Test
    fun genesysHasNoListButCountsPointsAndBarsLinks() {
        val three = deck(List(3) { maxx.id }).copy(extra = listOf(talker.id))
        val r = DeckRules(genesysCap = 100).validate(three, index::byId, "2026-10-04")
        // No Forbidden & Limited list: three Maxx "C" are no copy problem.
        assertTrue(r.errors.none { "Forbidden" in it.message }, r.errors.toString())
        assertTrue(r.errors.any { it.message == "The deck costs 187 Genesys points; the cap is 100." }, r.errors.toString())
        assertTrue(r.errors.any { "Accesscode Talker is a Link monster" in it.message && it.cardId == talker.id && it.section == DeckSection.EXTRA }, r.errors.toString())
        assertEquals(187, DeckRules(genesysCap = 100).points(three, index::byId)?.points)
        assertTrue(DeckRules(genesysCap = 200).validate(three.copy(extra = emptyList()), index::byId, "2026-10-04").isLegal)
    }

    @Test
    fun theCostliestCardsAreNamedDearestFirst() {
        val three = deck(List(3) { maxx.id })
        val r = DeckRules(genesysCap = 100).points(three, index::byId)!!
        // Three Maxx "C" at 50 each lead; the 37 fillers at 1 each are one line apiece after it.
        assertEquals(GenesysRules.Cost(maxx.id, maxx.name, 3, 50), r.costs.first())
        assertEquals(150, r.costs.first().total)
        assertEquals(87, r.over)
        // Cards worth nothing are not costs.
        assertTrue(r.costs.none { it.id == talker.id })
    }

    @Test
    fun aNoteIsSaidBesideTheIssues() {
        val r = DeckRules(asOf = "2025-05-01", note = "The TCG lists could not be read.").validate(deck(), index::byId, "2026-10-04")
        assertTrue(r.isLegal)
        assertEquals(listOf("The TCG lists could not be read."), r.warnings.map { it.message })
    }
}
