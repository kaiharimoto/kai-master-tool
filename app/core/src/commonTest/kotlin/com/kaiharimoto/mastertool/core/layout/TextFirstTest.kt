package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFirstTest {
    /** Text whose height is a line count times a line height of 1.5 × the size. */
    private fun lines(n: Int): (Int) -> Int = { size -> (n * size * 1.5).toInt() }

    @Test fun shortTextLeavesTheArtItsNaturalSize() {
        val fit = TextFirst.fit(room = 1000, fixed = 100, artMin = 140, artMax = 400, sizes = TextFirst.steps(14, 11), textHeight = lines(4))
        assertEquals(TextFirst.Fit(400, 14, true), fit)
    }

    @Test fun longerTextShrinksTheArtBeforeTheText() {
        // 30 lines at 14 = 630; spare = 1000 − 100 − 630 = 270, between the least and the natural.
        assertEquals(TextFirst.Fit(270, 14, true), TextFirst.fit(1000, 100, 140, 400, TextFirst.steps(14, 11), lines(30)))
    }

    @Test fun theTextStepsDownOnlyOnceTheArtIsAtItsLeast() {
        // 36 lines: at 14 → 756, spare 144 ≥ 140, stays at 14.
        assertEquals(TextFirst.Fit(144, 14, true), TextFirst.fit(1000, 100, 140, 400, TextFirst.steps(14, 11), lines(36)))
        // 38 lines: at 14 → 798, spare 102 < 140; at 13 → 741, spare 159.
        assertEquals(TextFirst.Fit(159, 13, true), TextFirst.fit(1000, 100, 140, 400, TextFirst.steps(14, 11), lines(38)))
    }

    @Test fun pastTheFloorTheColumnScrolls() {
        val fit = TextFirst.fit(600, 100, 140, 400, TextFirst.steps(14, 11), lines(60))
        assertEquals(140, fit.art)
        assertEquals(11, fit.size)
        assertFalse(fit.whole)
    }

    @Test fun noLimitIsTheNaturalLayout() {
        assertEquals(TextFirst.Fit(400, 14, true), TextFirst.fit(null, 100, 140, 400, listOf(14, 13), lines(500)))
    }

    @Test fun aNarrowColumnCapsTheLeastArtAtItsNatural() {
        // A column narrower than the least picture: the least is the natural.
        val fit = TextFirst.fit(1000, 100, 140, 120, TextFirst.steps(14, 11), lines(4))
        assertEquals(120, fit.art)
        assertTrue(fit.whole)
    }

    @Test fun stepsRunFromTheNormalSizeToTheFloor() {
        assertEquals(listOf(15, 14, 13, 12, 11), TextFirst.steps(15, 11))
        assertEquals(listOf(11), TextFirst.steps(11, 11))
        assertEquals(listOf(10), TextFirst.steps(10, 11))
    }

    @Test fun theSameCardInTheSameColumnLaysOutTheSame() {
        val a = TextFirst.fit(812, 133, 140, 397, TextFirst.steps(14, 11), lines(27))
        val b = TextFirst.fit(812, 133, 140, 397, TextFirst.steps(14, 11), lines(27))
        assertEquals(a, b)
    }
}
