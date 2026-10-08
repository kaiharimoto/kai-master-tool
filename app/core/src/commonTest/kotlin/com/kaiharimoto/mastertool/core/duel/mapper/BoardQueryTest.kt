package com.kaiharimoto.mastertool.core.duel.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Filters, weights and the Pareto front (M.md §2.7), on boards made by hand. */
class BoardQueryTest {
    private fun board(key: String, traits: BoardTraits, monsters: List<Int> = emptyList(), starter: List<Int> = listOf(1), moves: Int = 5) =
        BoardEntry(
            key, BoardCards(monsters = monsters), traits,
            lines = listOf(MapLine(MapDeal(starter), List(moves) { MapStep(kind = "x") })),
            starters = listOf(starter),
        )

    private val wide = board("a", BoardTraits(interruptions = 3, negates = 1, removal = 2, bodies = 3, hand = 0), monsters = listOf(10))
    private val negates = board("b", BoardTraits(interruptions = 2, negates = 2, bodies = 2, hand = 1, through = mapOf("ash" to 2)), monsters = listOf(11))
    private val weak = board("c", BoardTraits(interruptions = 1, negates = 1, bodies = 1, hand = 0))
    private val all = listOf(wide, negates, weak)

    @Test
    fun weightsRankAndEachPartIsScaledToTheMostAnyBoardHas() {
        val r = BoardQuery.rank(all, BoardPreset(weights = mapOf("interruptions" to 1.0, "negates" to 1.0)))
        // a: 3/3 + 1/2 = 1.5; b: 2/3 + 2/2 ≈ 1.67 — b leads.
        assertEquals(listOf("b", "a", "c"), r.map { it.entry.key })
        assertEquals(1.0, r.first().parts.getValue("negates"), 1e-9)
        assertEquals(2.0 / 3.0, r.first().parts.getValue("interruptions"), 1e-9)
    }

    @Test
    fun theFrontKeepsTheTradeOffsAndDropsTheBoardBeatenOnEverything() {
        val front = BoardQuery.pareto(all, mapOf("interruptions" to 1.0, "negates" to 1.0))
        assertEquals(setOf("a", "b"), front)
        val r = BoardQuery.rank(all, BoardPreset(weights = mapOf("interruptions" to 1.0, "negates" to 1.0)))
        assertEquals(setOf("a", "b"), r.filter { it.front }.map { it.entry.key }.toSet())
    }

    @Test
    fun aNegativeWeightPrefersLess() {
        val front = BoardQuery.pareto(all, mapOf("hand" to -1.0))
        assertEquals(setOf("a", "c"), front)
    }

    @Test
    fun aTraitNotMeasuredFailsItsFilterAndAddsNothing() {
        val preset = BoardPreset(filters = listOf(BoardFilter("through:ash", min = 1.0)))
        assertEquals(listOf("b"), BoardQuery.rank(all, preset).map { it.entry.key })
        val r = BoardQuery.rank(all, BoardPreset(weights = mapOf("through:ash" to 1.0)))
        assertEquals("b", r.first().entry.key)
        assertEquals(listOf("through:ash"), r.single { it.entry.key == "a" }.unmeasured)
    }

    @Test
    fun cardsRequiredOrRefusedAreReadOffTheBoardAndItsStarters() {
        assertEquals(listOf("a"), BoardQuery.rank(all, BoardPreset(uses = listOf(10))).map { it.entry.key })
        assertTrue(BoardQuery.rank(all, BoardPreset(avoids = listOf(1))).isEmpty(), "every board starts from card 1")
    }

    @Test
    fun staleBoardsStayOutUnlessAskedFor() {
        val stale = wide.copy(stale = true)
        assertEquals(listOf("b", "c"), BoardQuery.rank(listOf(stale, negates, weak), BoardPreset.DEFAULT).map { it.entry.key })
        assertEquals(3, BoardQuery.rank(listOf(stale, negates, weak), BoardPreset.DEFAULT.copy(stale = true)).size)
    }

    @Test
    fun tiesGoToTheCheaperLine() {
        val cheap = board("z", wide.traits, starter = listOf(1), moves = 2)
        val dear = board("y", wide.traits, starter = listOf(1, 2), moves = 2)
        assertEquals(listOf("z", "y"), BoardQuery.rank(listOf(dear, cheap), BoardPreset.DEFAULT).map { it.entry.key })
    }
}
