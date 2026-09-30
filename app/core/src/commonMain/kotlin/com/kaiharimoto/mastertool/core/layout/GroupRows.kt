package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.math.min

/**
 * Separate (1.0.40, kai: "each group in its own line/row — separate does not follow the
 * rules for fitment"): every group starts a row of its own and fills rows as it reads —
 * its copy sets in the Fitted order, copies side by side, wrapping where the row ends —
 * with a gap under it. No block shapes, no four-row limit, no fitting one group beside
 * another: the groups read top to bottom like a decklist's headings.
 *
 * Handed on as a [BandLayout] — each group a band of one block, flush left — so the
 * builder's outlines, name tabs, drops, the drag's sets and zen read it as they read
 * the fitted bands.
 */
object GroupRows {
    private const val MIN_WIDTH = 5
    private const val MAX_WIDTH = 15
    private const val PLAIN_WIDTH = 10
    private const val OTHER_COLUMNS = 15f

    /**
     * The rows for positions [ids] keyed [keys], groups in [order], each group's sets in
     * [setOrder] where it names them. The width is the one that leaves the biggest card in
     * [pane] with [gapY] under every group but the last and [otherRows] fifteen-wide rows
     * below — the nearer ten on a tie. Null when there is nothing to lay out.
     */
    fun layout(
        ids: List<Int>,
        keys: List<String?>,
        order: List<String>,
        pane: Pair<Float, Float>,
        otherRows: Int = 0,
        cardAspect: Float = 86f / 59f,
        gapY: Float = 0f,
        setOrder: List<Int> = emptyList(),
        widths: IntRange? = null,
    ): BandLayout? {
        if (ids.isEmpty() || ids.size != keys.size) return null
        val groups = GroupBands.groupsOf(ids, keys, order, setOrder)
        val n = ids.size
        fun rows(w: Int) = groups.sumOf { (it.size + w - 1) / w }
        fun card(w: Int) = min(
            pane.first / w,
            (pane.second - (groups.size - 1) * gapY) / (rows(w) * cardAspect + otherRows * cardAspect * w / OTHER_COLUMNS),
        )
        val range = widths ?: (min(MIN_WIDTH, n)..min(MAX_WIDTH, n).coerceAtLeast(min(MIN_WIDTH, n)))
        val width = range.maxWithOrNull(compareBy<Int> { card(it) }.thenBy { -abs(it - PLAIN_WIDTH) }) ?: return null

        val row = IntArray(n)
        val col = IntArray(n)
        val blockOf = IntArray(n)
        val blocks = mutableListOf<BandBlock>()
        var top = 0
        groups.forEachIndexed { g, group ->
            // Each cell to its card's next position in the deck, so every copy is a position.
            val waiting = HashMap<Int, ArrayDeque<Int>>()
            group.positions.forEach { p -> waiting.getOrPut(ids[p]) { ArrayDeque() }.addLast(p) }
            var k = 0
            group.sets.forEach { (card, count) ->
                repeat(count) {
                    val p = waiting.getValue(card).removeFirst()
                    row[p] = top + k / width
                    col[p] = k % width
                    blockOf[p] = g
                    k++
                }
            }
            val height = (group.size + width - 1) / width
            blocks += BandBlock(group.key, top, 0, min(group.size, width), height, band = g, stack = 0, above = 0)
            top += height
        }
        return BandLayout(width, top, row.toList(), col.toList(), blockOf.toList(), blocks)
    }
}
