package com.kaiharimoto.mastertool.core.present

/**
 * What an inserted number, table or chart holds until the person fills it in (the editor's audit, R7).
 * "87 %" and "Opponent A 60 %" read as real data, and a slide that went out unchanged misled its
 * viewers; these read as blanks to fill — dashes and words that say what goes there — so nothing on a
 * slide looks like a result nobody measured.
 */
object Placeholders {
    /** Stands where a number goes. */
    const val BLANK = "—"

    val stat: Stat = Stat(BLANK, "What this number counts", "Type it in the Item tab")

    val table: List<List<String>> = listOf(
        listOf("Matchup", "Going first", "Going second"),
        listOf("Their deck", BLANK, BLANK),
        listOf("Their deck", BLANK, BLANK),
    )

    /** An empty chart: three labelled places and no values, so it draws its axes and nothing that looks measured. */
    val chart: Chart = Chart(Chart.COLUMN, listOf("First", "Second", "Third"), listOf(ChartSeries("Your numbers", listOf(0f, 0f, 0f))), max = 100f, percent = true)

    /** Whether [e] still holds a placeholder of this object's: the editor flags it before it goes out. */
    fun untouched(e: Element): Boolean = when (e.type) {
        Element.STAT -> e.stat?.value == BLANK
        Element.TABLE -> e.table == table
        Element.CHART -> e.chart == chart
        else -> false
    }
}
