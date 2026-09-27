package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckFitterTest {

    private val aspect = 59f / 86f
    private val spacing = 6f
    private val chrome = 40f
    private val paneGap = 8f

    private fun plan(
        width: Float = 780f,
        height: Float = 720f,
        main: Int = 40,
        extra: Int = 15,
        side: Int = 15,
        mainCollapsed: Boolean = false,
        mainSpacing: Float = spacing,
    ) = DeckFitter.plan(
        requests = listOf(
            SectionFitRequest(main, 10, baselineCount = 40, spacing = mainSpacing, collapsed = mainCollapsed, chromeHeight = chrome),
            SectionFitRequest(extra, 15, baselineCount = 15, spacing = spacing, chromeHeight = chrome),
            SectionFitRequest(side, 15, baselineCount = 15, spacing = spacing, chromeHeight = chrome),
        ),
        availableWidth = width,
        availableHeight = height,
        aspectRatio = aspect,
        paneGap = paneGap,
    )

    @Test
    fun theWholeDeckFitsTheColumnItWasGiven() {
        val fit = plan()

        assertTrue(fit.fits)
        assertTrue(fit.totalHeight <= 720f)
        // Four rows of ten, one row of fifteen twice — the shape of a decklist.
        assertEquals(listOf(4, 1, 1), fit.sections.map { it.rows })
    }

    @Test
    fun everySectionIsDrawnAtTheSameWidth() {
        // The gap that started this: scaling the cards left a ten-wide row and a
        // fifteen-wide row at different widths, because the spacing between them
        // does not scale and there are more gaps in the wider row.
        val fit = plan(height = 400f)

        fit.sections.forEach { section ->
            val width = section.columns * section.cardWidth + spacing * (section.columns - 1)
            assertEquals(fit.contentWidth, width, 0.01f)
        }
    }

    @Test
    fun aMainDeckCardStaysLargerThanAnExtraDeckCard() {
        val (main, extra, side) = plan().sections

        assertTrue(main.cardWidth > extra.cardWidth)
        assertEquals(extra.cardWidth, side.cardWidth, 0.01f)
    }

    @Test
    fun leftoverWidthIsLeftForTheCallerToCentre() {
        // Height-bound: the stack is narrower than the space, and what is left
        // is negative space around all three panes rather than a margin inside
        // the main deck.
        val tight = plan(height = 420f)
        assertTrue(tight.contentWidth < 780f)
        assertTrue(tight.fits)

        // Room to spare: the stack spends the whole width.
        val roomy = plan(height = 4000f)
        assertEquals(780f, roomy.contentWidth, 0.01f)
    }

    @Test
    fun theSolvedWidthSpendsExactlyTheHeightAvailable() {
        val fit = plan(height = 500f)

        assertTrue(abs(fit.totalHeight - 500f) < 0.5f)
    }

    @Test
    fun openingTheGuttersKeepsTheWidthsAligned() {
        // The breakdown lens opens the main deck's spacing. Its cards get a
        // little smaller; the stack stays one width.
        val fit = plan(mainSpacing = 12f)

        val main = fit.sections[0]
        assertEquals(fit.contentWidth, 10 * main.cardWidth + 12f * 9, 0.01f)
        val side = fit.sections[2]
        assertEquals(fit.contentWidth, 15 * side.cardWidth + spacing * 14, 0.01f)
    }

    @Test
    fun sizeIsHeldSteadyUntilTheDeckOutgrowsItsBaseline() {
        // A deck being built must not resize on every card added.
        assertEquals(plan(main = 40).contentWidth, plan(main = 0).contentWidth)
        assertEquals(plan(main = 40).contentWidth, plan(main = 39).contentWidth)

        // Forty-one needs a fifth row, and only now does everything zoom out.
        val past = plan(main = 41)
        assertEquals(5, past.sections[0].rows)
        assertTrue(past.contentWidth < plan(main = 40).contentWidth)
    }

    @Test
    fun aCollapsedSectionGivesItsSpaceToTheOthers() {
        val collapsed = plan(height = 400f, mainCollapsed = true)

        assertEquals(0, collapsed.sections[0].rows)
        assertEquals(chrome, collapsed.sections[0].paneHeight, 0.01f)
        assertTrue(collapsed.contentWidth > plan(height = 400f).contentWidth)
    }

    @Test
    fun paneHeightsAddUpToWhatWasReported() {
        val fit = plan(height = 500f)
        val sum = fit.sections.sumOf { it.paneHeight.toDouble() }.toFloat() + paneGap * 2

        assertTrue(abs(sum - fit.totalHeight) < 0.01f)
    }

    @Test
    fun aSpaceNothingFitsInSaysSo() {
        // The column then scrolls rather than drawing cards nobody can read.
        val fit = plan(height = 120f)

        assertEquals(780f * DeckFitter.MIN_WIDTH_FRACTION, fit.contentWidth, 0.01f)
        assertFalse(fit.fits)
    }

    @Test
    fun zeroWidthIsSurvivedRatherThanDividedBy() {
        val fit = plan(width = 0f)

        assertFalse(fit.fits)
        assertTrue(fit.sections.all { it.cardWidth == 0f })
    }

    @Test
    fun widthAndHeightSpentOnPiecesComeOffTheCardsAndStillFit() {
        val plain = SectionFitRequest(count = 40, columns = 10, baselineCount = 40, spacing = 0f)
        val gapped = plain.copy(extraWidth = 30f, extraHeight = 20f)
        val a = DeckFitter.plan(listOf(plain), 1000f, 600f, 0.686f)
        val b = DeckFitter.plan(listOf(gapped), 1000f, 600f, 0.686f)
        val s = b.sections.single()
        assertTrue(s.cardWidth < a.sections.single().cardWidth)
        // The row is the cards and the gaps, exactly; the height is spent.
        assertEquals(b.contentWidth, s.cardWidth * 10 + 30f, 0.01f)
        assertEquals(s.rows * s.cardHeight + 20f, s.gridHeight, 0.01f)
        assertTrue(b.fits)
        assertTrue(b.totalHeight <= 600.5f)
    }
}
