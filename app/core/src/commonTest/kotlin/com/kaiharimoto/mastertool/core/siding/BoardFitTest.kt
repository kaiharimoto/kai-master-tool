package com.kaiharimoto.mastertool.core.siding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardFitTest {
    private val ratio = 59f / 86f

    private fun fit(width: Float, height: Float, main: Int = 40, extra: Int = 0, side: Int = 15) =
        BoardFit.fit(width, height, main, extra, side, gap = 4f, sectionGap = 16f, header = 28f, ratio = ratio, maxCard = 140f)

    /** The board's height for a fit, as the page lays it out. */
    private fun height(f: BoardFit.Fit): Float =
        28f * (if (f.extraRows > 0) 2 else 1) + (if (f.extraRows > 0) 16f else 0f) + f.rows * f.card / ratio + 4f * (f.rows - 1)

    private fun width(f: BoardFit.Fit): Float {
        val columns = BoardFit.MAIN_COLUMNS + f.sideColumns
        return columns * f.card + 4f * (columns - 2) + 16f
    }

    @Test
    fun aFortyCardDeckAndFifteenSideCardsFitBothWays() {
        val f = fit(1300f, 520f)
        assertEquals(4, f.mainRows)
        assertEquals(4, f.sideColumns, "fifteen side cards in the four rows of the main deck")
        assertTrue(height(f) <= 520.01f, "fits the height: ${height(f)}")
        assertTrue(width(f) <= 1300.01f, "fits the width: ${width(f)}")
    }

    @Test
    fun theShorterSideDecidesTheSize() {
        val tall = fit(900f, 2000f)
        assertTrue(width(tall) > 899f, "a tall space is filled across")
        val wide = fit(3000f, 400f)
        assertTrue(height(wide) > 399f, "a wide space is filled down")
        assertEquals(140f, fit(10_000f, 10_000f).card, "never larger than the largest card")
    }

    @Test
    fun theExtraDeckTakesRowsAndTheSideSpreadsOverThem() {
        val without = fit(1300f, 600f)
        val with = fit(1300f, 600f, extra = 15)
        assertEquals(2, with.extraRows)
        assertEquals(6, with.rows)
        assertEquals(3, with.sideColumns, "fifteen side cards over six rows")
        assertTrue(with.card < without.card)
        assertTrue(height(with) <= 600.01f)
    }

    @Test
    fun aSixtyCardDeckIsSixRowsAndAnEmptySideStillHasItsColumn() {
        val big = fit(1300f, 700f, main = 60)
        assertEquals(6, big.mainRows)
        assertTrue(height(big) <= 700.01f)
        assertEquals(1, fit(1300f, 600f, side = 0).sideColumns)
    }
}
