package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An open pile (1.0.78): at least 80 % of every card showing, in rows, never bigger than the table's card. */
class DuelStripGridTest {
    private val windows = listOf(
        Triple(1920f, 984f, FormFactor.DESK),
        Triple(1366f, 672f, FormFactor.DESK),
        Triple(1280f, 752f, FormFactor.TABLET),
        Triple(390f, 760f, FormFactor.PHONE),
        Triple(844f, 340f, FormFactor.PHONE),
    )

    @Test
    fun everyCardShowsAtLeastFourFifths() {
        windows.forEach { (w, h, form) ->
            listOf(true, false).forEach { two ->
                val l = DuelLayouter.solve(w, h, two, form)
                listOf(1, 5, 15, 40, 60, 75).forEach { n ->
                    val g = DuelFrames.stripGrid(n, l)
                    assertEquals(n, g.cells.size)
                    assertTrue(g.card <= l.card + 0.01f, "$w×$h n=$n: ${g.card} > ${l.card}")
                    g.cells.zipWithNext().forEach { (a, b) ->
                        if (a.top == b.top) assertTrue(b.left - a.left >= g.card * DuelFrames.STRIP_SHOWN - 0.01f, "$w×$h n=$n: step ${b.left - a.left}")
                        else assertTrue(b.top - a.top >= a.height - 0.01f, "rows overlap")
                    }
                    g.cells.forEach { c ->
                        assertTrue(c.left >= l.field.left - 0.01f && c.right <= l.field.right + 0.01f, "$w×$h n=$n: $c outside the field's width")
                    }
                    assertTrue(g.area.top >= DuelFrames.STRIP_HEAD, "$w×$h n=$n: the head has no room")
                    assertEquals(l.field.bottom, g.area.bottom, 0.01f)
                }
            }
        }
    }

    @Test
    fun aWholeDeckFitsWithoutScrollingOnADesk() {
        val l = DuelLayouter.solve(1920f, 984f, true)
        val g = DuelFrames.stripGrid(60, l)
        assertFalse(g.scrolls, "rows ${g.rows}, visible ${g.visibleRows}")
        assertTrue(g.rows > 1)
        assertTrue(g.card >= 56f)
    }

    @Test
    fun aShortPileIsOneRowSideBySide() {
        val l = DuelLayouter.solve(1920f, 984f, true)
        val g = DuelFrames.stripGrid(5, l)
        assertEquals(1, g.rows)
        assertTrue(g.cells[1].left - g.cells[0].left > g.card)
    }

    @Test
    fun aLongOpenPileCoversTheChainWellAndItsWordsGoUnder() {
        // 1.1.9 (kai: "the chain link box text is showing over some windows"): the Deck laid open reaches the middle row,
        // so the chain well's words must sit in the table's layer — over the field's cards, under the pile's ground
        // (Z_STRIP − ½) and its cards, and under the hands.
        assertTrue(DuelFrames.Z_FIELD < DuelFrames.Z_CHAIN && DuelFrames.Z_CHAIN < DuelFrames.Z_HAND)
        assertTrue(DuelFrames.Z_CHAIN < DuelFrames.Z_STRIP - 0.5f)
        windows.forEach { (w, h, form) ->
            val l = DuelLayouter.solve(w, h, true, form)
            val chain = l[DuelSpot.Chain] ?: return@forEach
            val band = DuelFrames.stripBand(l, 40)
            assertTrue(band.top < chain.bottom && band.bottom > chain.top, "$w×$h: the open Deck misses the chain well")
        }
    }
}
