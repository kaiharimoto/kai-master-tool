package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeckLabelsTest {
    private val sections = listOf(
        SectionFitRequest(count = 40, columns = 10, baselineCount = 40, spacing = 0f, chromeHeight = 48f),
        SectionFitRequest(count = 15, columns = 15, baselineCount = 15, spacing = 0f, chromeHeight = 12f),
        SectionFitRequest(count = 15, columns = 15, baselineCount = 15, spacing = 0f, chromeHeight = 12f),
    )
    private val labelled = listOf(false, true, true)
    private val ratio = 59f / 86f

    private fun place(w: Float, h: Float) = DeckLabels.place(w, h, ratio, gutter = 96f, rowHeight = 24f, requests = sections, labelled = labelled)

    @Test
    fun aShortWideColumnPutsTheLabelsBesideTheCards() {
        // Height-limited: the gutter is paper that was going spare.
        val placed = place(2400f, 900f)
        assertEquals(LabelPlace.GUTTER, placed.place)
        val bare = DeckFitter.plan(sections, 2400f, 900f, ratio)
        assertEquals(bare.sections[0].cardWidth, placed.fit.sections[0].cardWidth, 0.01f)
    }

    @Test
    fun aTallNarrowColumnPutsThemOverTheCards() {
        // Width-limited: the rows are paper that was going spare.
        val placed = place(1000f, 1400f)
        assertEquals(LabelPlace.ROWS, placed.place)
        val bare = DeckFitter.plan(sections, 1000f, 1400f, ratio)
        assertEquals(bare.sections[0].cardWidth, placed.fit.sections[0].cardWidth, 0.01f)
    }

    @Test
    fun theChoiceIsNeverWorseThanEitherArrangement() {
        for (w in listOf(700f, 1000f, 1300f, 1700f, 2200f)) for (h in listOf(600f, 800f, 1000f, 1300f)) {
            val placed = place(w, h).fit.sections[0].cardWidth
            val beside = DeckFitter.plan(sections, w - 192f, h, ratio).sections[0].cardWidth
            val over = DeckFitter.plan(sections.mapIndexed { i, r -> if (labelled[i]) r.copy(chromeHeight = r.chromeHeight + 24f) else r }, w, h, ratio).sections[0].cardWidth
            assertTrue(placed >= maxOf(beside, over) - 0.01f, "at $w × $h")
        }
    }
}
