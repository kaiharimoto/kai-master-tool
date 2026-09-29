package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs

/** One laid-out grid item, in whatever coordinate space the cursor is given in. */
data class ItemBox(
    val index: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Where a dragged card would be dropped.
 *
 * Pure geometry, deliberately kept out of the UI: this is the fiddliest part of
 * drag and drop and the part most likely to be quietly wrong, and none of it
 * needs a composition to decide. Working out an insertion point in a grid is
 * genuinely two-dimensional — find the row first, then the gap within it — which
 * is why a plain "nearest item" answer feels wrong the moment the last row is
 * ragged.
 *
 * [hysteresis] is what stops the insertion marker flickering when the cursor
 * hovers on a boundary: the answer only changes once the cursor has gone past
 * the boundary by that much, so a hand that is not quite still does not produce
 * a marker that cannot make up its mind.
 */
object GridDropResolver {

    /**
     * Returns an insertion index in `0..items.size`, meaning "before the item
     * currently at this index".
     */
    fun insertionIndex(
        items: List<ItemBox>,
        cursorX: Float,
        cursorY: Float,
        rowTolerance: Float,
        hysteresis: Float = 0f,
        previous: Int? = null,
    ): Int {
        if (items.isEmpty()) return 0

        val row = nearestRow(items, cursorY, rowTolerance)
        val raw = insertionWithinRow(row, cursorX)

        return damp(items, cursorX, raw, previous, hysteresis)
    }

    /**
     * Where the marker for insertion [index] stands, in the row [cursorY] is in: at the left
     * of the card at [index], or — when that card starts the next row — at the right of the
     * one before it (1.0.39: a card aimed at the end of a row showed its bar on the next).
     * The card to draw it by, and whether it is on that card's right.
     */
    fun anchor(items: List<ItemBox>, cursorY: Float, rowTolerance: Float, index: Int): Pair<Int, Boolean> {
        if (items.isEmpty()) return 0 to false
        val row = nearestRow(items, cursorY, rowTolerance).map { it.index }
        return when {
            index in row -> index to false
            index - 1 in row -> index - 1 to true
            index >= items.size -> items.size - 1 to true
            else -> index to false
        }
    }

    private fun nearestRow(
        items: List<ItemBox>,
        cursorY: Float,
        rowTolerance: Float,
    ): List<ItemBox> {
        val rows = mutableListOf<MutableList<ItemBox>>()

        items.sortedBy { it.centerY }.forEach { item ->
            val current = rows.lastOrNull()
            if (current != null && abs(item.centerY - current.first().centerY) <= rowTolerance) {
                current += item
            } else {
                rows += mutableListOf(item)
            }
        }

        // Above the first row or below the last one still resolves to a row, so a
        // drop into the padding around the grid lands somewhere sensible rather
        // than nowhere.
        return rows.minBy { row -> abs(cursorY - row.first().centerY) }
    }

    private fun insertionWithinRow(row: List<ItemBox>, cursorX: Float): Int {
        val ordered = row.sortedBy { it.centerX }
        val before = ordered.firstOrNull { cursorX <= it.centerX }
        return before?.index ?: (ordered.last().index + 1)
    }

    private fun damp(
        items: List<ItemBox>,
        cursorX: Float,
        raw: Int,
        previous: Int?,
        hysteresis: Float,
    ): Int {
        if (previous == null || previous == raw || hysteresis <= 0f) return raw
        // A jump of more than one gap is a deliberate move, not a twitch.
        if (abs(raw - previous) != 1) return raw

        // The boundary between "before item i" and "before item i+1" is the
        // centre of item i, so the gap being crossed is the lower of the two.
        val boundary = items.firstOrNull { it.index == minOf(raw, previous) } ?: return raw

        return if (raw > previous) {
            if (cursorX > boundary.centerX + hysteresis) raw else previous
        } else {
            if (cursorX < boundary.centerX - hysteresis) raw else previous
        }
    }
}
