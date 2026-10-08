package com.kaiharimoto.mastertool.core.siding

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How large the siding board's cards can be so the whole deck to side from is on screen at
 * once (1.0.51, kai: "have the main deck all fit in the screen without needing to scroll…
 * with the remaining space horizontally put the side deck next to the main deck").
 *
 * The Main Deck is ten to a row, as the builder and a decklist have it, with the Extra Deck
 * (when it is shown) under it at the same ten; the Side Deck stands beside them in as many
 * columns as it needs to fill the same height. One card size serves both, the largest for
 * which the ten plus the side's columns fit the width and the rows fit the height.
 * Units are whatever the caller measures in (dp); [ratio] is a card's width over its height.
 */
object BoardFit {
    const val MAIN_COLUMNS = 10

    data class Fit(
        /** A card's width. */
        val card: Float,
        /** Rows of the Main Deck, then of the Extra Deck when shown. */
        val mainRows: Int,
        val extraRows: Int,
        /** The Side Deck's columns beside them (at least one, for its label, when it is empty). */
        val sideColumns: Int,
    ) {
        val rows: Int get() = mainRows + extraRows
    }

    fun fit(
        width: Float,
        height: Float,
        main: Int,
        extra: Int,
        side: Int,
        gap: Float,
        /** Between the Main and Side columns, and above the Extra Deck. */
        sectionGap: Float,
        /** A section's heading, with its space below. */
        header: Float,
        ratio: Float,
        maxCard: Float,
    ): Fit = fit(
        width, height, MAIN_COLUMNS, max(1, rowsOf(main, MAIN_COLUMNS)), extra, side,
        gap = gap, sectionGap = sectionGap, header = header, ratio = ratio, maxCard = maxCard,
    )

    /**
     * The same for a Main Deck laid out in [columns] by [mainRows] cells (2026-10: the builder's
     * arrangements on the board, `SidingLayout`), [spanX] and [spanY] gaps of [pieceGap] between
     * its pieces across and down, and [tab] over it for the groups' names. The Extra Deck stands
     * under it at the same [columns].
     */
    fun fit(
        width: Float,
        height: Float,
        columns: Int,
        mainRows: Int,
        extra: Int,
        side: Int,
        gap: Float,
        sectionGap: Float,
        header: Float,
        ratio: Float,
        maxCard: Float,
        spanX: Int = 0,
        spanY: Int = 0,
        pieceGap: Float = 0f,
        tab: Float = 0f,
    ): Fit {
        val across = max(1, columns)
        val rowsMain = max(1, mainRows)
        val extraRows = rowsOf(extra, across)
        val rows = rowsMain + extraRows
        val sideColumns = max(1, rowsOf(side, rows))
        val all = across + sideColumns
        val byWidth = (width - gap * (all - 2) - sectionGap - spanX * pieceGap) / all
        val headers = header * (if (extraRows > 0) 2 else 1) + (if (extraRows > 0) sectionGap else 0f) + tab
        val byHeight = (height - headers - gap * (rows - 1) - spanY * pieceGap) / rows * ratio
        return Fit(max(0f, min(min(byWidth, byHeight), maxCard)), rowsMain, extraRows, sideColumns)
    }

    internal fun rowsOf(count: Int, perRow: Int): Int = if (count <= 0) 0 else ceil(count / perRow.toDouble()).toInt()
}
