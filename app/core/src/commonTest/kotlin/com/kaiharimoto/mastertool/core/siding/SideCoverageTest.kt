package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.Drill
import com.kaiharimoto.mastertool.core.prep.DrillStat
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Side Deck read across the field's plans (Phase G, G.6). */
class SideCoverageTest {
    private fun ids(vararg n: Int) = n.map(::CardId)
    private fun close(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-9, "$a against $b")

    private val deck = Deck(
        main = (1..20).flatMap { listOf(CardId(it), CardId(it)) },
        side = ids(100, 100, 100, 101, 101, 101, 102, 102, 103, 103),
    )
    private val a = Matchup("a", "Yubel", "d-a",
        first = SidePlan(out = ids(1, 1, 2), into = ids(100, 100, 101)),
        second = SidePlan(out = ids(1), into = ids(102)))
    private val b = Matchup("b", "K9", "d-b",
        first = SidePlan(out = ids(1), into = ids(100)),
        second = SidePlan(out = ids(3)))
    private val opponents = listOf(
        SideCoverage.Opponent("Yubel", 50, a),
        SideCoverage.Opponent("K9", 30, b),
        SideCoverage.Opponent("Snake-Eye", 20, null),
    )
    private val c = SideCoverage.of(deck, opponents) { false }

    @Test
    fun eachSideCardMeetsTheShareOfTheFieldItsPlansBringItInAgainst() {
        val byCard = c.cards.associateBy { it.card.value }
        close(0.8, byCard.getValue(100).first)
        close(0.0, byCard.getValue(100).second)
        assertEquals(2, byCard.getValue(100).needed, "at most two in any one plan")
        assertEquals(1, byCard.getValue(100).dead)
        close(0.5, byCard.getValue(102).second)
        assertEquals(2, byCard.getValue(103).dead, "no plan brings it in")
        assertEquals(1 + 2 + 1 + 2, c.deadCopies)
        assertEquals("6 of 10 Side Deck copies come in against nothing", c.deadWords())
        assertEquals(listOf("Yubel", "K9"), byCard.getValue(100).against)
    }

    @Test
    fun cardsMovedAgainstMostOfTheFieldAreNamedAndUnplannedShareIsSaid() {
        assertEquals(listOf(CardId(1) to Turn.FIRST), c.outAgainstMost.map { it.card to it.turn }, "out against 80 % going first")
        assertEquals(listOf(CardId(100) to Turn.FIRST), c.inAgainstMost.map { it.card to it.turn })
        close(0.2, c.unplannedFirst)
        close(0.2, c.unplannedSecond)
        assertEquals(listOf("Snake-Eye"), c.unplanned)
    }

    @Test
    fun aPlanThatLeavesNoLegalDeckIsNamedByTheLoungesRule() {
        assertEquals(listOf("K9" to Turn.SECOND), c.illegal.map { it.opponent to it.turn })
        assertTrue("not card for card" in c.illegal.single().words)
        // The shared rule itself: an even plan that moves a Main Deck card for an Extra Deck one leaves 39 in the Main.
        val toExtra = SidePlan(out = ids(1), into = ids(200))
        val withExtraSide = deck.copy(side = deck.side + CardId(200))
        val problem = SidingMath.legalAfter(withExtraSide, toExtra) { it.value == 200 }
        assertTrue(problem != null && "at least 40" in problem, "$problem")
        // No shares at all: every matchup weighs alike, said so.
        val equal = SideCoverage.of(deck, opponents.map { it.copy(share = 0) }) { false }
        assertTrue(equal.equalWeights)
        close(2.0 / 3, equal.cards.first { it.card.value == 100 }.first)
        assertTrue("6 of 10" in SideCoverage.words(c) { "#${it.value}" })
    }

    @Test
    fun theGuidesFirstPageSaysTheDeadCopiesAndTheFieldWithNoPlan() {
        val lines = SideCoverage.guideLines(c) { "#${it.value}" }
        assertTrue(lines.first().startsWith("6 of 10 Side Deck copies come in against nothing: 1 #100, 2 #101, 1 #102, 2 #103"), lines.first())
        assertTrue(lines.any { it.startsWith("No plan against Snake-Eye: 20% of the field going first, 20% going second") }, lines.toString())
        assertTrue(lines.any { "Not a legal deck after siding: K9 going second" in it }, lines.toString())
    }

    @Test
    fun aDrillSaysWhenItIsDueAgain() {
        val hour = 3_600_000L
        val day = 24 * hour
        assertEquals("due now", Drill.dueWords(null, 5 * day))
        // Box 3 waits three days after its last try (the Leitner boxes the drills keep).
        val stat = DrillStat(seen = 3, correct = 2, lastAt = 10 * day, box = 3)
        assertEquals(13 * day, Drill.dueAt(stat))
        assertEquals("due in 3 days", Drill.dueWords(stat, 10 * day))
        assertEquals("due in 5 hours", Drill.dueWords(stat, 13 * day - 5 * hour))
        assertEquals("due now", Drill.dueWords(stat, 13 * day))
    }
}
