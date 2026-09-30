package com.kaiharimoto.mastertool.core.ai.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A table laid out to the chat's room (1.0.65): as it is, wrapped, or stacked — never cut off. */
class TableFitTest {
    @Test
    fun aTableThatFitsIsLeftAsItIs() {
        assertEquals(TableLayout.Widths(listOf(120f, 80f)), TableFit.fit(listOf(120f, 80f), listOf(60f, 40f), available = 361f))
    }

    @Test
    fun wideColumnsWrapAndNarrowOnesKeepTheirWidth() {
        // "Card | Why": a short name column and a long reason, in a 361 dp panel.
        val fit = assertIs<TableLayout.Widths>(TableFit.fit(listOf(150f, 520f), listOf(90f, 80f), available = 361f))
        assertEquals(361f, fit.widths.sum(), 0.5f)
        assertTrue(fit.widths[0] >= 90f && fit.widths[1] >= 80f, "never below a column's longest word")
        assertTrue(fit.widths[1] > fit.widths[0], "the column with more to give gives more room back")
        // A column already at its minimum does not shrink.
        val kept = assertIs<TableLayout.Widths>(TableFit.fit(listOf(50f, 600f), listOf(50f, 90f), available = 300f))
        assertEquals(50f, kept.widths[0], 0.01f)
    }

    @Test
    fun whatCannotSitSideBySideIsStacked() {
        assertEquals(TableLayout.Stacked, TableFit.fit(List(6) { 120f }, List(6) { 70f }, available = 328f))
        // Rules between columns count too.
        assertEquals(TableLayout.Stacked, TableFit.fit(listOf(100f, 100f), listOf(100f, 100f), available = 200f, gap = 1f))
    }

    @Test
    fun oddShapesAreSafe() {
        assertEquals(TableLayout.Widths(emptyList()), TableFit.fit(emptyList(), emptyList(), 300f))
        assertEquals(TableLayout.Widths(listOf(200f)), TableFit.fit(listOf(200f), emptyList(), 300f))
        // A minimum wider than the natural width is read as the natural one.
        val odd = assertIs<TableLayout.Widths>(TableFit.fit(listOf(100f, 400f), listOf(150f, 50f), 300f))
        assertEquals(300f, odd.widths.sum(), 0.5f)
    }
}
