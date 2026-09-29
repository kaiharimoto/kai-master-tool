package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckReorderTest {

    // Ten across, 100 wide and 140 tall, as the plain main deck is drawn.
    private fun grid(count: Int, columns: Int = 10): List<ItemBox> = (0 until count).map { i ->
        val left = (i % columns) * 100f
        val top = (i / columns) * 140f
        ItemBox(i, left, top, left + 100f, top + 140f)
    }

    @Test
    fun aCardIsOverTheBoxItIsWellInsideAndNoneOnTheLineBetweenTwo() {
        val boxes = grid(20)
        assertEquals(3, DeckReorder.hit(boxes, 350f, 70f))
        assertEquals(13, DeckReorder.hit(boxes, 350f, 210f))
        // On the line between 3 and 4, and between the rows: nothing, so nothing moves.
        assertNull(DeckReorder.hit(boxes, 400f, 70f))
        assertNull(DeckReorder.hit(boxes, 350f, 140f))
    }

    @Test
    fun theEndIsBelowTheLastRowOrBesideItsLastCard() {
        val boxes = grid(15)
        assertTrue(DeckReorder.pastEnd(boxes, 700f, 200f), "beside the last card")
        assertTrue(DeckReorder.pastEnd(boxes, 100f, 300f), "below the last row")
        assertFalse(DeckReorder.pastEnd(boxes, 900f, 70f), "the first row's end is not the section's")
        assertFalse(DeckReorder.pastEnd(boxes, 300f, 200f))
    }

    @Test
    fun aCardTakesTheCellItIsOverAndTheRestCloseUp() {
        val order = (0 until 6).toList()
        assertEquals(listOf(0, 2, 3, 4, 1, 5), DeckReorder.moveCell(order, 1, 4))
        assertEquals(listOf(4, 0, 1, 2, 3, 5), DeckReorder.moveCell(order, 4, 0))
        assertEquals(listOf(0, 1, 2, 3, 5, 4), DeckReorder.moveCell(order, 4, 99))
        // Composes: the order it is moved through is the one drawn.
        val once = DeckReorder.moveCell(order, 1, 4)
        assertEquals(listOf(0, 2, 1, 3, 4, 5), DeckReorder.moveCell(once, 1, 2))
    }

    @Test
    fun aSetTakesTheOthersPlaceFromEitherSide() {
        val sets = listOf(10, 20, 30, 40)
        assertEquals(listOf(20, 30, 10, 40), DeckReorder.moveSet(sets, 10, 30), "forward: after it")
        assertEquals(listOf(10, 40, 20, 30), DeckReorder.moveSet(sets, 40, 20), "back: before it")
        assertEquals(listOf(20, 10, 30, 40), DeckReorder.moveSet(sets, 10, 20), "a neighbour: swapped")
        assertEquals(sets, DeckReorder.moveSet(sets, 10, 10))
        assertEquals(sets, DeckReorder.moveSet(sets, 10, 99))
        // Having taken 30's place, 10 is over itself: nothing more happens.
        val moved = DeckReorder.moveSet(sets, 10, 30)
        assertEquals(moved, DeckReorder.moveSet(moved, 10, 10))
    }

    @Test
    fun theBarAtTheEndOfARowStandsOnThatRow() {
        val boxes = grid(20)
        // The pointer at the right end of the first row: insertion 10, drawn after card 9.
        assertEquals(9 to true, GridDropResolver.anchor(boxes, 70f, 8f, 10))
        // The same insertion from the start of the second row stands before card 10.
        assertEquals(10 to false, GridDropResolver.anchor(boxes, 210f, 8f, 10))
        assertEquals(19 to true, GridDropResolver.anchor(boxes, 210f, 8f, 20))
        assertEquals(4 to false, GridDropResolver.anchor(boxes, 70f, 8f, 4))
    }

    @Test
    fun theFittedOrderArrangesAGroupsSetsAndTheDecksOrderDoesNot() {
        // One group, sets 1, 2, 3 in the deck's order; the Fitted order puts 3 first.
        val ids = listOf(1, 1, 1, 2, 2, 2, 3, 3, 3)
        val keys = List(9) { "g" }
        val plain = GroupBands.layout(ids, keys, listOf("g"), 1000f to 600f)!!
        assertEquals(listOf(1, 2, 3), plain.setOrder(ids))
        val fitted = GroupBands.layout(ids, keys, listOf("g"), 1000f to 600f, setOrder = listOf(3, 1, 2))!!
        assertEquals(listOf(3, 1, 2), fitted.setOrder(ids))
        // A card the Fitted order has never met follows the ones it has, as the deck reads.
        val added = GroupBands.layout(ids + listOf(4), keys + "g", listOf("g"), 1000f to 600f, setOrder = listOf(3, 1))!!
        assertEquals(listOf(3, 1, 2, 4), added.setOrder(ids + listOf(4)))
    }
}
