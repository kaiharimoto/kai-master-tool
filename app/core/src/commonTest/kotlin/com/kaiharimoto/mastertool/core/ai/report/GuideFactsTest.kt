package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.ai.report.guide.EngineLayout
import com.kaiharimoto.mastertool.core.ai.report.guide.GuideFacts
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reader's guide's numbers and the engine map's layout (1.0.67): worked out, so they can be checked. */
class GuideFactsTest {
    private val guide = ReaderGuideSample.labrynth
    private val facts = GuideFacts.of(guide)

    @Test
    fun theFortyCardsAreTheRolesAndTheirCopies() {
        assertEquals(40, facts.deckSize, "the Las Vegas list is forty cards")
        assertEquals(40, facts.cells.size)
        assertEquals(facts.roles.sumOf { it.count }, facts.cells.size)
        facts.roles.forEachIndexed { i, r -> assertEquals(r.count, facts.cells.count { it == i }, r.name) }
        assertEquals(GuideFacts.Fill.SOLID, facts.roles.first().fill)
    }

    @Test
    fun theOddsAreTheHypergeometric() {
        // Fourteen starters in forty: 1 − C(26,5)/C(40,5) = 0.9000.
        assertTrue(abs(facts.startFirst - 0.9000) < 0.0005, "${facts.startFirst}")
        assertTrue(facts.startSecond > facts.startFirst)
        assertTrue(abs(facts.brick - (1 - facts.startFirst)) < 1e-9)
        // The lesson's other number: nine monster starters alone, 74%.
        assertEquals("74%", GuideFacts.percent(GuideFacts.atLeastOne(9, 40, 5)))
        assertEquals("90%", GuideFacts.percent(facts.startFirst))
    }

    @Test
    fun sampleHandsAreDealtTheSameEveryTime() {
        assertEquals(facts.hands, GuideFacts.of(guide).hands)
        assertTrue(facts.hands.isNotEmpty())
        facts.hands.forEach { assertEquals(5, it.cards.size) }
        assertEquals(facts.hands.map { it.verdict }.distinct(), facts.hands.map { it.verdict }, "one hand of each kind")
    }

    @Test
    fun sidingIsCountedAndBalanced() {
        val maliss = facts.sides.first()
        assertEquals(listOf("Rescue-ACE Impulse" to 3, "Different Dimension Ground" to 1), maliss.ins)
        assertEquals(maliss.ins.sumOf { it.second }, maliss.outs.sumOf { it.second })
        assertEquals(3, GuideFacts.theirTurn(guide.lines.first()))
    }

    @Test
    fun theEngineMapRunsDownAndFindsItsHubs() {
        val map = EngineLayout.of(guide.connections)
        map.edges.forEach { e -> assertTrue(map.rowOf(e.from) < map.rowOf(e.to), "${e.from} → ${e.to} runs down the page") }
        assertEquals(map.rows.flatten().size, map.rows.flatten().distinct().size, "each card once")
        assertTrue("Lady Labrynth of the Silver Castle" in map.hubs, "every route passes through Lady")
        assertTrue(map.rows.first().contains("Arianna the Labrynth Servant"))
        // A cycle does not hang it, and still places every card.
        val loop = EngineLayout.of(listOf(ReaderGuide.Edge("A", "B"), ReaderGuide.Edge("B", "C"), ReaderGuide.Edge("C", "A")))
        assertEquals(setOf("A", "B", "C"), loop.rows.flatten().toSet())
    }
}
