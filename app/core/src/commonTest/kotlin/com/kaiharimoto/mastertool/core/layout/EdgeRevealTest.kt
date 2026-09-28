package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdgeRevealTest {

    private fun next(
        current: Revealed,
        x: Float?,
        y: Float?,
        immersive: Boolean = true,
        holdTop: Boolean = false,
        suppress: Boolean = false,
    ) = EdgeReveal.next(current, x, y, height = 1000f, railWidth = 232f, topHeight = 140f, bottomHeight = 64f, immersive = immersive, holdTop = holdTop, suppress = suppress)

    @Test
    fun theRailComesOutAtTheLeftEdgeAndStaysWhileThePointerIsOnIt() {
        var r = next(Revealed.NONE, 400f, 500f)
        assertFalse(r.left)
        r = next(r, 3f, 500f)
        assertTrue(r.left)
        r = next(r, 200f, 500f)
        assertTrue(r.left, "on the rail")
        r = next(r, 250f, 500f)
        assertTrue(r.left, "inside the slack")
        r = next(r, 300f, 500f)
        assertFalse(r.left)
    }

    @Test
    fun theBarsOnlyFoldInImmersiveMode() {
        val r = next(Revealed.NONE, 500f, 2f, immersive = false)
        assertFalse(r.top)
        assertFalse(next(Revealed.NONE, 500f, 999f, immersive = false).bottom)
        assertTrue(next(Revealed.NONE, 500f, 2f).top)
        assertTrue(next(Revealed.NONE, 500f, 996f).bottom)
    }

    @Test
    fun theTopStaysOutUnderItsOwnHeightPlusSlack() {
        var r = next(Revealed.NONE, 500f, 1f)
        r = next(r, 500f, 150f)
        assertTrue(r.top)
        r = next(r, 500f, 170f)
        assertFalse(r.top)
    }

    @Test
    fun aNameBeingTypedHoldsTheTopOut() {
        var r = next(Revealed(top = true), 500f, 600f, holdTop = true)
        assertTrue(r.top)
        r = next(r, 500f, 600f)
        assertFalse(r.top)
        // Typing in a field somewhere else does not bring the bars out.
        assertFalse(next(Revealed.NONE, 500f, 600f, holdTop = true).top)
    }

    @Test
    fun aCarriedCardOpensNothingButClosesNothingEither() {
        assertEquals(Revealed.NONE, next(Revealed.NONE, 1f, 1f, suppress = true))
        assertTrue(next(Revealed(left = true), 100f, 500f, suppress = true).left)
    }

    @Test
    fun leavingTheWindowOpensNothing() {
        assertEquals(Revealed.NONE, next(Revealed.NONE, null, null))
        assertTrue(next(Revealed(bottom = true), null, null).bottom)
    }

    private fun tap(current: Revealed, x: Float, y: Float, immersive: Boolean = true) =
        EdgeReveal.onTap(current, x, y, topStrip = 32f, leftStrip = 32f, topHeight = 48f, railWidth = 56f, immersive = immersive)

    @Test
    fun aFingerTapsTheBarsOut() {
        assertEquals(Revealed(left = true), tap(Revealed.NONE, 10f, 300f))
        assertEquals(Revealed(top = true), tap(Revealed.NONE, 600f, 12f))
        // The top strip wins in the corner, where both meet.
        assertEquals(Revealed(top = true), tap(Revealed.NONE, 10f, 10f))
        // A tap inside a bar that is out keeps it; a tap on the page folds everything.
        assertEquals(Revealed(top = true), tap(Revealed(top = true), 900f, 40f))
        assertEquals(Revealed(left = true), tap(Revealed(left = true), 50f, 500f))
        assertEquals(Revealed.NONE, tap(Revealed(top = true, left = true), 600f, 400f))
        // Outside immersive nothing folds away, so a tap changes nothing.
        assertEquals(Revealed.NONE, tap(Revealed.NONE, 10f, 300f, immersive = false))
    }
}
