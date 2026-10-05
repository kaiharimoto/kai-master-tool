package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolFoldTest {
    // Back, name, Undo, Redo, Slide, Text, Shape, Picture, Card, More, Module, Style, Export, Present.
    private val bar = listOf(
        ToolFold.Tool(28f, 0), ToolFold.Tool(160f, 0), ToolFold.Tool(28f, 2), ToolFold.Tool(28f, 2),
        ToolFold.Tool(80f, 1), ToolFold.Tool(60f, 3), ToolFold.Tool(70f, 3), ToolFold.Tool(90f, 3), ToolFold.Tool(60f, 3),
        ToolFold.Tool(70f, 2), ToolFold.Tool(90f, 2), ToolFold.Tool(80f, 2), ToolFold.Tool(28f, 0), ToolFold.Tool(120f, 0),
    )
    private val pinned = bar.indices.filter { bar[it].rank == 0 }.toSet()

    private fun width(fit: ToolFold.Fit, gap: Float, more: Float): Float =
        fit.shown.sumOf { bar[it].width.toDouble() }.toFloat() + (if (fit.overflows) more else 0f) + gap * (fit.shown.size + (if (fit.overflows) 1 else 0) - 1)

    @Test
    fun aWideBarFoldsNothing() {
        val fit = ToolFold.fit(bar, 5000f, gap = 4f, more = 28f)
        assertFalse(fit.overflows)
        assertEquals(bar.indices.toSet(), fit.shown)
    }

    @Test
    fun everyWidthKeepsPresentAndExportAndFitsWhenItCan() {
        for (w in 300..1400 step 20) {
            val fit = ToolFold.fit(bar, w.toFloat(), gap = 4f, more = 28f)
            assertTrue(fit.shown.containsAll(pinned), "at $w a pinned tool folded")
            val minimum = pinned.sumOf { bar[it].width.toDouble() }.toFloat() + 28f + 4f * pinned.size
            if (w >= minimum) assertTrue(width(fit, 4f, 28f) <= w, "at $w the bar is ${width(fit, 4f, 28f)} wide")
            assertEquals(bar.indices.toSet(), fit.shown + fit.folded, "every tool is in the bar or the menu")
        }
    }

    @Test
    fun theLeastImportantFoldFirst() {
        val total = bar.sumOf { it.width.toDouble() }.toFloat() + 4f * (bar.size - 1)
        val fit = ToolFold.fit(bar, total - 30f, gap = 4f, more = 28f)
        assertTrue(fit.overflows)
        assertTrue(fit.folded.all { bar[it].rank == 3 }, "only the rank-3 tools go first: ${fit.folded}")
        // The menu lists them in the bar's order.
        assertEquals(fit.folded.sorted(), fit.folded)
    }
}
