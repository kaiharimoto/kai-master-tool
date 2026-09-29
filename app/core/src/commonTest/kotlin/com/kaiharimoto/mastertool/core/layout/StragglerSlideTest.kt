package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** kai's 1.0.33 screenshot: a deck past 40 leaves a group's last cards alone in the last row. */
class StragglerSlideTest {

    private fun keys(vararg runs: Pair<String?, Int>): List<String?> = runs.flatMap { (k, n) -> List(n) { k } }

    @Test
    fun theStragglersStandUnderTheirGroupAndJoinIt() {
        // 19 starters, 14 non-engine, 9 bricks, ten to a row: two bricks in the last row,
        // under the non-engine cards as read, while the other bricks are at columns 3–9 above.
        val keys = keys("S" to 19, "N" to 14, "B" to 9)
        val layout = GroupPieces.of(keys, 10)
        assertEquals(listOf(3, 4), listOf(layout.col(40), layout.col(41)))
        // They touch the brick above them, and are one piece with it.
        assertEquals(layout.piece[33], layout.piece[40])
        assertEquals(layout.piece[33], layout.piece[41])
        // Nothing else moved.
        (0 until 40).forEach { assertEquals(it % 10, layout.col(it)) }
    }

    @Test
    fun aRowThatGainsNothingStaysAsRead() {
        // The last row's group is already under itself.
        val keys = keys("A" to 13, "B" to 4)
        val layout = GroupPieces.of(keys, 10)
        (0 until keys.size).forEach { assertEquals(it % 10, layout.col(it)) }
        // A group nowhere above it has nowhere to go either.
        val alone = GroupPieces.of(keys("A" to 10, "B" to 3), 10)
        assertEquals(listOf(0, 1, 2), (10 until 13).map { alone.col(it) })
    }

    @Test
    fun eachRunFindsItsOwnGroupInOrderLeavingAGapBetween() {
        // Above: N at 0–2, B at 3–9. The last row reads one N, then two B: the N stays at 0,
        // the bricks move under the bricks, and the row keeps its order.
        val keys = keys("N" to 3, "B" to 7, "N" to 1, "B" to 2)
        val layout = GroupPieces.of(keys, 10)
        assertEquals(listOf(0, 3, 4), (10 until 13).map { layout.col(it) })
        assertEquals(layout.piece[0], layout.piece[10])
        assertEquals(layout.piece[3], layout.piece[11])
        assertNotEquals(layout.piece[10], layout.piece[11])
    }

    @Test
    fun theOutlineFollowsTheCellsNotTheIndex() {
        val keys = keys("S" to 19, "N" to 14, "B" to 9)
        val layout = GroupPieces.of(keys, 10)
        // Position 40 at column 3: its left is an empty cell, its top a brick of its own piece.
        val sides = layout.outerSides(40)
        assertEquals(true, sides[0])
        assertEquals(false, sides[1])
        assertEquals(false, sides[2])
        assertEquals(true, sides[3])
    }
}
