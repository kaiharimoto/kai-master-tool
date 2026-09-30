package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupRowsTest {

    // kai's Angelechy Labrynth: groups of 10, 9, 9, 4 and 8.
    private val sets = listOf(
        "Combo" to listOf(1 to 3, 2 to 3, 3 to 3, 4 to 1),
        "Angelechy" to listOf(5 to 3, 6 to 3, 7 to 3),
        "Non-engine" to listOf(8 to 3, 9 to 3, 10 to 3),
        "Breakers" to listOf(11 to 2, 12 to 2),
        null to listOf(13 to 2, 14 to 1, 15 to 1, 16 to 2, 17 to 1, 18 to 1),
    )
    private val ids = sets.flatMap { (_, s) -> s.flatMap { (card, n) -> List(n) { card } } }
    private val keys = sets.flatMap { (key, s) -> s.flatMap { (_, n) -> List(n) { key } } }
    private val order = sets.mapNotNull { it.first }

    @Test
    fun everyGroupStartsItsOwnRowAndNoRowHoldsTwo() {
        val l = GroupRows.layout(ids, keys, order, 1400f to 900f, gapY = 28f)!!
        // One block a group, one band each, in the groups' order, the cards in no group last.
        assertEquals(listOf("Combo", "Angelechy", "Non-engine", "Breakers", null), l.blocks.map { it.key })
        assertEquals(l.blocks.indices.toList(), l.blocks.map { it.band })
        assertTrue(l.blocks.all { it.col == 0 && it.stack == 0 })
        // No row holds cards of two groups.
        ids.indices.groupBy { l.row[it] }.values.forEach { row -> assertEquals(1, row.map { keys[it] }.distinct().size) }
        // Each group's cards are in its own block, read row by row with the copies together.
        l.blocks.forEachIndexed { b, block ->
            val mine = ids.indices.filter { l.block[it] == b }.sortedWith(compareBy({ l.row[it] }, { l.col[it] }))
            assertTrue(mine.all { keys[it] == block.key })
            assertEquals(sets.first { it.first == block.key }.second.flatMap { (card, n) -> List(n) { card } }, mine.map { ids[it] })
            // Flush left and packed: cell k of the block is row k / width, column k % width.
            mine.forEachIndexed { k, p -> assertEquals(block.row + k / l.columns to k % l.columns, l.row[p] to l.col[p]) }
        }
        // Every card has a cell of its own.
        assertEquals(ids.size, ids.indices.map { l.row[it] to l.col[it] }.toSet().size)
    }

    @Test
    fun theWidthFollowsThePaneAndTheFittedOrderArrangesAGroup() {
        val wide = GroupRows.layout(ids, keys, order, 2400f to 700f)!!
        val tall = GroupRows.layout(ids, keys, order, 700f to 1600f)!!
        assertTrue(wide.columns > tall.columns, "${wide.columns} vs ${tall.columns}")
        val l = GroupRows.layout(ids, keys, order, 1400f to 900f, setOrder = listOf(3, 1, 2, 4))!!
        assertEquals(listOf(3, 1, 2, 4), l.setOrder(ids).take(4))
        assertNull(GroupRows.layout(emptyList(), emptyList(), emptyList(), 1400f to 900f))
    }

    @Test
    fun asPiecesEachGroupIsOnePieceAGapUnderTheOneAbove() {
        val pieces = GroupRows.layout(ids, keys, order, 1400f to 900f)!!.pieces()
        assertEquals(5, pieces.pieces)
        assertEquals(0, pieces.spanX)
        assertEquals(4, pieces.spanY)
    }
}
