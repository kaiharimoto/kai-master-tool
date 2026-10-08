package com.kaiharimoto.mastertool.core.duel.mapper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The MAP-Elites archive (M.md §4.2): one elite a cell, the cheaper wins, and the frontier is the empty cells beside the filled. */
class EliteArchiveTest {
    private val axes = listOf(EliteAxis("interruptions", 2), EliteAxis("hand", 1))

    private fun board(key: String, interruptions: Int, hand: Int, starter: Int = 1, moves: Int = 4, stale: Boolean = false) = BoardEntry(
        key, BoardCards(), BoardTraits(interruptions = interruptions, hand = hand),
        lines = listOf(MapLine(MapDeal(List(starter) { 7 }), List(moves) { MapStep(kind = "x") })),
        starters = listOf(List(starter) { 7 }), stale = stale,
    )

    @Test
    fun aCellKeepsItsCheapestBoard() {
        val a = EliteArchive(axes)
        assertTrue(a.offer(board("dear", 1, 0, starter = 2)))
        assertTrue(a.offer(board("cheap", 1, 0, starter = 1)))
        assertFalse(a.offer(board("dearer", 1, 0, starter = 2, moves = 9)))
        assertEquals("cheap", a.filled.values.single().key)
    }

    @Test
    fun aBoardThatHoldsMoreBeatsACheaperOneInItsCell() {
        // Both land in the last interruptions bin; the dearer one keeps an Xyz's materials and holds a sixth interruption.
        val a = EliteArchive(axes)
        a.offer(board("spent", 5, 0, starter = 1))
        assertTrue(a.offer(board("kept", 6, 0, starter = 2)))
        assertFalse(a.offer(board("cheaper-but-less", 5, 0, starter = 1, moves = 1)))
        assertEquals("kept", a.filled.values.single().key)
    }

    @Test
    fun theLastBinHoldsEverythingAboveIt() {
        val a = EliteArchive(axes)
        a.offer(board("many", 7, 3))
        assertEquals(EliteArchive.Cell(listOf(2, 1)), a.filled.keys.single())
        assertEquals(6, a.size)
        assertEquals(1.0 / 6, a.coverage, 1e-12)
    }

    @Test
    fun staleOrUnmeasuredBoardsTakeNoCell() {
        val a = EliteArchive(listOf(EliteAxis("through:ash", 2)))
        assertFalse(a.offer(board("untested", 1, 0)))
        assertFalse(EliteArchive(axes).offer(board("old", 1, 0, stale = true)))
    }

    @Test
    fun theFrontierIsTheEmptyNeighboursCheapestFirst() {
        val a = EliteArchive(axes)
        a.offer(board("dear", 0, 0, starter = 2))
        a.offer(board("cheap", 2, 1, starter = 1))
        val f = a.frontier().map { it.first.bins }
        // Next to (2,1): (1,1) and (2,0); next to (0,0): (1,0) and (0,1). The cheap board's neighbours lead.
        assertEquals(listOf(listOf(1, 1), listOf(2, 0), listOf(1, 0), listOf(0, 1)), f)
        assertTrue(f.none { it == listOf(0, 0) || it == listOf(2, 1) })
    }

    @Test
    fun aCellNoBoardCouldFillIsNeitherCountedNorSought() {
        val a = EliteArchive(listOf(EliteAxis("interruptions", 2), EliteAxis("negates", 2)))
        // More negates than interruptions cannot be, below the last bin: (0,1), (0,2) and (1,2) are out.
        assertEquals(6, a.size)
        assertFalse(a.possible(EliteArchive.Cell(listOf(0, 1))))
        assertTrue(a.possible(EliteArchive.Cell(listOf(2, 2))))
        a.offer(board("none", 0, 0))
        assertEquals(listOf(listOf(1, 0)), a.frontier().map { it.first.bins })
    }
}
