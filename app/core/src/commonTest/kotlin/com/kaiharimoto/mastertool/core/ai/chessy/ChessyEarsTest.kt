package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.test.Test
import kotlin.test.assertTrue

class ChessyEarsTest {
    private fun xs(p: FloatArray) = p.filterIndexed { i, _ -> i % 2 == 0 }
    private fun ys(p: FloatArray) = p.filterIndexed { i, _ -> i % 2 == 1 }

    @Test
    fun twoEarsInOneBox() {
        for (ear in listOf(ChessyEars.LEFT, ChessyEars.RIGHT)) {
            assertTrue(ear.size % 2 == 0 && ear.size / 2 in 8..120, "an ear is a closed outline of a few dozen points")
            assertTrue(xs(ear).all { it in 0f..1f } && ys(ear).all { it in 0f..ChessyEars.HEIGHT }, "inside the box")
        }
        // the left ear on the left, the right on the right, a gap between
        assertTrue(xs(ChessyEars.LEFT).max() < xs(ChessyEars.RIGHT).min())
        assertTrue(ChessyEars.HEIGHT in .3f..1f)
    }
}
