package com.kaiharimoto.mastertool.core.ai.text

/**
 * How a table Ai drew fits the chat (1.0.65, kai: "sometimes Ai draws a table and it doesn't fit
 * properly in the chat window"). Its columns were guessed from their characters and scrolled
 * sideways out of sight; now each column's real widths are measured and the table is laid out to
 * the room there is: as it is when it fits, its wide columns wrapped when that is enough, and
 * stacked a row at a time when even its longest words will not sit side by side. Never scrolled.
 */
sealed interface TableLayout {
    /** The columns side by side, each this wide; a cell narrower than its text wraps. */
    data class Widths(val widths: List<Float>) : TableLayout

    /** One row at a time: its first cell a title, the others "Header: value" beneath it. */
    data object Stacked : TableLayout
}

object TableFit {
    /**
     * [natural] is each column's width on one line, [minimum] its longest word (both with the
     * cell's padding); [available] the room, [gap] what the rules between columns take.
     */
    fun fit(natural: List<Float>, minimum: List<Float>, available: Float, gap: Float = 0f): TableLayout {
        if (natural.isEmpty()) return TableLayout.Widths(emptyList())
        val mins = natural.indices.map { minOf(minimum.getOrElse(it) { natural[it] }, natural[it]) }
        val room = available - gap * (natural.size - 1)
        if (natural.sum() <= room) return TableLayout.Widths(natural)
        if (mins.sum() > room) return TableLayout.Stacked
        // Only what is above each column's minimum gives, in proportion to how much there is.
        val spare = room - mins.sum()
        val over = natural.indices.map { natural[it] - mins[it] }
        val total = over.sum()
        return TableLayout.Widths(natural.indices.map { mins[it] + if (total > 0f) over[it] / total * spare else 0f })
    }
}
