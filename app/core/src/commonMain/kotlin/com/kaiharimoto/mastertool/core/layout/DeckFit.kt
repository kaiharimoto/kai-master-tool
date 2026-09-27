package com.kaiharimoto.mastertool.core.layout

import kotlin.math.ceil

/**
 * One section, as it wants to be drawn: a row width the user chose, and the
 * capacity it should be sized for even before it holds that many cards.
 *
 * [chromeHeight] is everything in the pane that is not grid — its header, its
 * padding, the gap under the header. It is passed in rather than assumed
 * because only the UI knows how tall its own header is, and the whole point of
 * this fitter is that the number it returns leaves nothing overflowing.
 *
 * [spacing] is per section because the main deck opens its gutters in the
 * breakdown lens, and the sections still have to end up the same width.
 */
data class SectionFitRequest(
    val count: Int,
    val columns: Int,
    /** Size as though the section already held this many — see [DeckFitter]. */
    val baselineCount: Int,
    val spacing: Float,
    val collapsed: Boolean = false,
    val chromeHeight: Float = 0f,
    /**
     * Width the grid spends on something other than cards and even spacing —
     * the gaps between a deck's pieces (`GroupPieces`) — taken off the cards
     * across the row, and height likewise added under them.
     */
    val extraWidth: Float = 0f,
    val extraHeight: Float = 0f,
)

/** A section's grid, in pixels: what to draw and how much room it takes. */
data class SectionFit(
    val columns: Int,
    val rows: Int,
    val cardWidth: Float,
    val cardHeight: Float,
    val gridHeight: Float,
    /** Grid plus chrome: the height the whole pane should be given. */
    val paneHeight: Float,
)

/**
 * The three panes, sized so all of them are on screen at once.
 *
 * [contentWidth] is the width every section is drawn at — one number, not one
 * per section, which is what keeps the three grids the same width and stops
 * the main deck from developing margins its neighbours do not have. Anything
 * left over between this and the space available is negative space around the
 * whole stack, and belongs to the caller to centre.
 */
data class DeckFit(
    val contentWidth: Float,
    val sections: List<SectionFit>,
    val totalHeight: Float,
    /** False when even [DeckFitter.MIN_WIDTH_FRACTION] does not get it in. */
    val fits: Boolean,
)

/**
 * Sizes the whole deck column in one pass.
 *
 * [GridFitter] answers a different question — "how many columns make this
 * section fit on its own" — and answering it per pane is what put cards out of
 * bounds: three panes each sized against a height they were given by a divider
 * drag cannot agree on a total, and the main deck lost. Here the row widths are
 * fixed (ten for the main deck, fifteen for the extra and side, which is how
 * decklists are read), the row *counts* follow from the deck, and the one free
 * variable is how wide the whole stack is drawn.
 *
 * Solving for that width rather than for a scale factor is what makes the deck
 * fill its box. Every grid height is linear in the width, so the total is
 * `A·W + B` and the width that exactly spends the height available is one
 * division. Scaling the cards instead left each section a slightly different
 * width — the spacing between cards does not scale, and a fifteen-wide row has
 * fourteen of them against a ten-wide row's nine — so the main deck ended up
 * narrower than the side deck below it, which is the gap you could see.
 *
 * Sections keep their own column counts, so a main-deck card stays half again
 * the size of an extra-deck one — the hierarchy of the list, drawn rather than
 * labelled. The baseline is why an empty deck is not drawn as one enormous
 * card: the main deck is sized for forty from the start, so nothing reflows
 * until the deck actually outgrows it.
 */
object DeckFitter {

    /** Below this share of the space the cards are unreadable; it scrolls. */
    const val MIN_WIDTH_FRACTION = 0.3f

    fun plan(
        requests: List<SectionFitRequest>,
        availableWidth: Float,
        availableHeight: Float,
        /** Card width divided by card height. */
        aspectRatio: Float,
        /** Space between two panes — the dividers. */
        paneGap: Float = 0f,
    ): DeckFit {
        if (requests.isEmpty()) return DeckFit(availableWidth, emptyList(), 0f, fits = true)

        val chrome = requests.sumOf { it.chromeHeight.toDouble() }.toFloat() +
            paneGap * (requests.size - 1)

        if (availableWidth <= 0f || aspectRatio <= 0f) {
            return DeckFit(0f, requests.map { EMPTY_SECTION }, chrome, fits = false)
        }

        // Rows are a property of the deck, not of the space, so they are decided
        // before any width is.
        val rows = requests.map { request ->
            if (request.collapsed) {
                0
            } else {
                val columns = request.columns.coerceAtLeast(1)
                ceil(maxOf(request.count, request.baselineCount).toDouble() / columns).toInt()
            }
        }

        // height(W) = slope·W + offset, summed over the sections.
        var slope = 0.0
        var offset = 0.0
        requests.forEachIndexed { i, request ->
            if (rows[i] <= 0) return@forEachIndexed
            val columns = request.columns.coerceAtLeast(1)
            val perRow = rows[i] / (columns * aspectRatio.toDouble())
            slope += perRow
            offset += request.spacing * (rows[i] - 1) - perRow * (request.spacing * (columns - 1) + request.extraWidth) + request.extraHeight
        }

        val solved = when {
            slope <= 0.0 -> availableWidth
            else -> ((availableHeight - chrome - offset) / slope).toFloat()
        }
        val floor = availableWidth * MIN_WIDTH_FRACTION
        val contentWidth = solved.coerceIn(minOf(floor, availableWidth), availableWidth)

        val sections = requests.mapIndexed { i, request ->
            val columns = request.columns.coerceAtLeast(1)
            val cardWidth = ((contentWidth - request.spacing * (columns - 1) - request.extraWidth) / columns)
                .coerceAtLeast(0f)
            val cardHeight = cardWidth / aspectRatio
            val gridHeight = if (rows[i] <= 0) {
                0f
            } else {
                rows[i] * cardHeight + request.spacing * (rows[i] - 1) + request.extraHeight
            }
            SectionFit(
                columns = columns,
                rows = rows[i],
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                gridHeight = gridHeight,
                paneHeight = gridHeight + request.chromeHeight,
            )
        }

        // Summed from what the panes were actually given, so the total is the
        // one the caller can lay out to rather than one it has to trust.
        val total = chrome + sections.sumOf { it.gridHeight.toDouble() }.toFloat()
        // A hair of tolerance: these are float pixels, and a total that lands a
        // thousandth over the space is not a layout that failed.
        return DeckFit(contentWidth, sections, total, fits = total <= availableHeight + 0.5f)
    }

    /**
     * The other way of sizing the same three panes: width first, and scroll.
     *
     * [plan] solves for the width that makes the whole deck fit the height it
     * has. That is the right question on a tablet, where the answer is a card
     * you can read. It is the wrong question in a portrait window: a 360dp-wide
     * phone with a card pool docked along the bottom leaves the deck about a
     * third of its height, and solving for that hands back a twenty-dp card —
     * a deck rendered as lint.
     *
     * So in [Posture.TALL] the width is the input, the card size follows from
     * the row width alone, and the column scrolls. The deck stops being a
     * diagram you take in at a glance and becomes a list you move through,
     * which is what a phone can honestly offer.
     *
     * [fits] is still answered honestly against [availableHeight], because when
     * the deck *does* fit — a side deck on its own, a collapsed pane or two —
     * the caller should centre it rather than leave it pinned to the top of a
     * scroll container with nothing to scroll.
     */
    fun stack(
        requests: List<SectionFitRequest>,
        availableWidth: Float,
        availableHeight: Float,
        aspectRatio: Float,
        paneGap: Float = 0f,
    ): DeckFit {
        if (requests.isEmpty()) return DeckFit(availableWidth, emptyList(), 0f, fits = true)

        val chrome = requests.sumOf { it.chromeHeight.toDouble() }.toFloat() +
            paneGap * (requests.size - 1)

        if (availableWidth <= 0f || aspectRatio <= 0f) {
            return DeckFit(0f, requests.map { EMPTY_SECTION }, chrome, fits = false)
        }

        val sections = requests.map { request ->
            val columns = request.columns.coerceAtLeast(1)
            val rows = if (request.collapsed) {
                0
            } else {
                ceil(maxOf(request.count, request.baselineCount).toDouble() / columns).toInt()
            }
            val cardWidth = ((availableWidth - request.spacing * (columns - 1) - request.extraWidth) / columns)
                .coerceAtLeast(0f)
            val cardHeight = cardWidth / aspectRatio
            val gridHeight = if (rows <= 0) 0f else rows * cardHeight + request.spacing * (rows - 1) + request.extraHeight
            SectionFit(
                columns = columns,
                rows = rows,
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                gridHeight = gridHeight,
                paneHeight = gridHeight + request.chromeHeight,
            )
        }

        val total = chrome + sections.sumOf { it.gridHeight.toDouble() }.toFloat()
        return DeckFit(
            contentWidth = availableWidth,
            sections = sections,
            totalHeight = total,
            fits = total <= availableHeight + 0.5f,
        )
    }

    private val EMPTY_SECTION = SectionFit(
        columns = 1,
        rows = 0,
        cardWidth = 0f,
        cardHeight = 0f,
        gridHeight = 0f,
        paneHeight = 0f,
    )
}
