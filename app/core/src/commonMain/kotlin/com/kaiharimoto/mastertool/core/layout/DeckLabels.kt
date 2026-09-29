package com.kaiharimoto.mastertool.core.layout

/** Where a deck section's name and count are written: beside its cards, or over them. */
enum class LabelPlace {
    /** In a gutter to the left of the grid: free when the deck is limited by its height. */
    GUTTER,

    /** In a thin row over the grid: free when the deck is limited by its width. */
    ROWS,
}

/** A fitted deck, and where its section labels went to get it. */
data class LabeledFit(val place: LabelPlace, val fit: DeckFit)

/**
 * kai's brief for 1.0.10: the section names go "next to the empty space in the
 * deck sections themselves". A deck fitted to a column is limited by one of its
 * two sides and has room to spare along the other — height-limited, there is
 * paper beside the grids; width-limited, there is paper under them. So the deck
 * is fitted both ways, the labels in a [gutter] each side or in a [rowHeight]
 * row over each section they name, and whichever draws the larger card wins.
 * The labels then cost the cards nothing, or as little as they can.
 *
 * On a tie the gutter wins: it is the arrangement with fewer rows.
 */
object DeckLabels {
    fun place(
        availableWidth: Float,
        availableHeight: Float,
        aspectRatio: Float,
        gutter: Float,
        rowHeight: Float,
        /** The sections as they would be asked for with no labels at all. */
        requests: List<SectionFitRequest>,
        /** Which sections carry a label of their own (the main deck's is on its lens row). */
        labelled: List<Boolean>,
    ): LabeledFit {
        val beside = DeckFitter.plan(requests, (availableWidth - gutter * 2f).coerceAtLeast(1f), availableHeight, aspectRatio)
        val over = DeckFitter.plan(
            requests.mapIndexed { i, r -> if (labelled.getOrElse(i) { false }) r.copy(chromeHeight = r.chromeHeight + rowHeight) else r },
            availableWidth,
            availableHeight,
            aspectRatio,
        )
        val a = beside.sections.firstOrNull()?.cardWidth ?: 0f
        val b = over.sections.firstOrNull()?.cardWidth ?: 0f
        return if (a >= b - 0.01f) LabeledFit(LabelPlace.GUTTER, beside) else LabeledFit(LabelPlace.ROWS, over)
    }

    /**
     * A phone's deck (v1.3.5): fitted width first by [DeckFitter.stack], and scrolling.
     * There is no paper beside a deck the width of the screen, so every labelled section
     * carries its name in a [rowHeight] row over it.
     */
    fun stack(
        availableWidth: Float,
        availableHeight: Float,
        aspectRatio: Float,
        rowHeight: Float,
        requests: List<SectionFitRequest>,
        labelled: List<Boolean>,
    ): LabeledFit = LabeledFit(
        LabelPlace.ROWS,
        DeckFitter.stack(
            requests.mapIndexed { i, r -> if (labelled.getOrElse(i) { false }) r.copy(chromeHeight = r.chromeHeight + rowHeight) else r },
            availableWidth,
            availableHeight,
            aspectRatio,
        ),
    )
}
