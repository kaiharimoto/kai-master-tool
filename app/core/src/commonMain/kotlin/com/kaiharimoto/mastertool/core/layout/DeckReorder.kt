package com.kaiharimoto.mastertool.core.layout

/**
 * Reordering by drag (1.0.39, kai: "dragging and dropping cards to adjust card order is
 * finnicky"). The deck opens a slot where the carried card will land and its old place
 * closes up, so what is drawn is what the drop does. The rule that keeps that steady
 * under the pointer is sortable's: **the carried card takes the place of the card it is
 * over** — after which it is the card under the pointer, so nothing changes again until
 * the pointer reaches another.
 *
 * Two shapes of it:
 *
 * - **Cells** (the deck As is, or with no groups): the grid's cells stay where they are
 *   and the cards move through them, one copy at a time. An order is the deck's positions
 *   in the order they will be drawn.
 * - **Sets** (Fitted and Separate): a copy set moves as one, within its group's block
 *   only (kai's choice), and what changes is the Fitted order (`DeckGroups.fitted`), never
 *   the deck's own. An order is card passcodes, each once.
 */
object DeckReorder {

    /**
     * The box [x], [y] is over, shrunk by [inset] of its size on every side, or null —
     * the inset is the hysteresis: a pointer on the line between two cards moves nothing.
     */
    fun hit(boxes: List<ItemBox>, x: Float, y: Float, inset: Float = 0.12f): Int? = boxes.firstOrNull { b ->
        val dx = (b.right - b.left) * inset
        val dy = (b.bottom - b.top) * inset
        x >= b.left + dx && x <= b.right - dx && y >= b.top + dy && y <= b.bottom - dy
    }?.index

    /**
     * Whether [x], [y] is past the last of [boxes] as they are read: below the last row, or
     * beside the last card in it — where a card goes to the end of the section.
     */
    fun pastEnd(boxes: List<ItemBox>, x: Float, y: Float): Boolean {
        val last = boxes.maxWithOrNull(compareBy({ it.top }, { it.left })) ?: return false
        return y > last.bottom || (y >= last.top && x > last.right)
    }

    /** [order] (positions, as drawn) with [held] moved into [slot]. */
    fun moveCell(order: List<Int>, held: Int, slot: Int): List<Int> {
        val rest = order - held
        return rest.toMutableList().apply { add(slot.coerceIn(0, rest.size), held) }
    }

    /**
     * [sets] (passcodes, each once) with [held] taking [target]'s place: after it when it
     * was before it, before it when it was after — the cards between close up.
     */
    fun moveSet(sets: List<Int>, held: Int, target: Int): List<Int> {
        val from = sets.indexOf(held)
        val to = sets.indexOf(target)
        if (from < 0 || to < 0 || from == to) return sets
        val rest = sets - held
        val at = rest.indexOf(target) + if (from < to) 1 else 0
        return rest.toMutableList().apply { add(at, held) }
    }
}
