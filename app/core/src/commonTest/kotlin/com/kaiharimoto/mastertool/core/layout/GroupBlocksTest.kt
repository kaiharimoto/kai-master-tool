package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupBlocksTest {

    @Test
    fun aGroupFillsItsOwnSeamsAndNoOthers() {
        // Two rows of four: A A B B / A A A B
        val keys = listOf("A", "A", "B", "B", "A", "A", "A", "B")
        val j = GroupBlocks.joins(keys, columns = 4)
        assertTrue(j[0].right && j[0].down && j[0].corner) // the A square
        assertFalse(j[1].right) // A beside B: paper
        assertTrue(j[1].down)
        assertFalse(j[1].corner) // the cell diagonal is A but right is B
        assertTrue(j[2].right)
        assertFalse(j[2].down) // B over A: paper
        assertTrue(j[3].down) // B over B
    }

    @Test
    fun rowsDoNotJoinAcrossTheirEnds() {
        // The last card of a row and the first of the next are not neighbours.
        val j = GroupBlocks.joins(listOf("A", "A", "A", "A"), columns = 2)
        assertFalse(j[1].right)
        assertFalse(j[1].corner)
    }

    @Test
    fun aCardInNoGroupJoinsNothing() {
        val j = GroupBlocks.joins(listOf(null, null, "A"), columns = 3)
        assertEquals(BlockJoin(false, false, false), j[0])
    }
}
