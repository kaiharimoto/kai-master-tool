package com.kaiharimoto.mastertool.core.motion

import com.kaiharimoto.mastertool.core.layout.DockMetrics
import com.kaiharimoto.mastertool.core.layout.PoolDock
import com.kaiharimoto.mastertool.core.layout.PoolStop
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceTiltTest {

    private val g = 9.81f

    private fun rad(degrees: Float) = degrees * kotlin.math.PI.toFloat() / 180f

    /** Gravity for a phone held upright at [pitch] degrees back from vertical, its right edge dipped [roll] degrees. */
    private fun held(pitch: Float, roll: Float): Triple<Float, Float, Float> {
        val p = rad(pitch)
        val r = rad(roll)
        return Triple(-g * sin(r), g * cos(p) * cos(r), g * sin(p) * cos(r))
    }

    private fun TiltFilter.hold(pitch: Float, roll: Float, seconds: Float, turns: Int = 0): Tilt {
        var last = Tilt.LEVEL
        val (x, y, z) = held(pitch, roll)
        repeat((seconds * 60).toInt()) { last = feed(x, y, z, turns, 1f / 60f) }
        return last
    }

    @Test
    fun heldStillTheLightSettlesInTheMiddle() {
        val f = TiltFilter()
        val t = f.hold(pitch = 40f, roll = 0f, seconds = 10f)
        assertEquals(0f, t.x, 0.02f)
        assertEquals(0f, t.y, 0.02f)
    }

    @Test
    fun aTurnMovesTheLightAndThenItSettles() {
        val f = TiltFilter()
        f.hold(pitch = 40f, roll = 0f, seconds = 5f)
        // Dip the right edge by half the reach: the light goes right at once.
        val turned = f.hold(pitch = 40f, roll = 11f, seconds = 0.3f)
        assertTrue(turned.x > 0.3f, "the light did not follow the turn: $turned")
        assertEquals(0f, turned.y, 0.1f)
        // Held there, it drifts back to the middle.
        val later = f.hold(pitch = 40f, roll = 11f, seconds = 15f)
        assertEquals(0f, later.x, 0.03f)
    }

    @Test
    fun aHardTurnStopsAtTheEdge() {
        val f = TiltFilter()
        f.hold(pitch = 30f, roll = 0f, seconds = 5f)
        val t = f.hold(pitch = 30f, roll = 70f, seconds = 0.3f)
        assertTrue(t.x in 0.9f..1f, "$t")
    }

    @Test
    fun standingItMoreUprightMovesTheLightDown() {
        val f = TiltFilter()
        f.hold(pitch = 30f, roll = 0f, seconds = 5f)
        val t = f.hold(pitch = 10f, roll = 0f, seconds = 0.3f)
        assertTrue(t.y > 0.3f, "$t")
        assertEquals(0f, t.x, 0.1f)
    }

    @Test
    fun theScreensRightIsTheScreensWhicheverWayItIsTurned() {
        // Lying down, the display turned a quarter (the device's top to the left): the
        // screen's right is the device's −y and its up the device's +x. The same dip of the
        // screen's right edge still moves the light right.
        val f = TiltFilter()
        val back = rad(40f)
        repeat(300) { f.feed(g * cos(back), 0f, g * sin(back), 1, 1f / 60f) }
        val dip = rad(11f)
        var t = Tilt.LEVEL
        repeat(18) { t = f.feed(g * cos(back) * cos(dip), g * sin(dip), g * sin(back) * cos(dip), 1, 1f / 60f) }
        assertTrue(t.x > 0.3f, "$t")
        assertEquals(0f, t.y, 0.1f)
    }

    @Test
    fun aPhonesDeckKeepsItsHeightAndThePoolHasTheRest() {
        val m = DockMetrics(windowHeight = 700f, chrome = 140f, minDeck = 300f, deck = 330f)
        assertEquals(370f, PoolDock.height(PoolStop.HALF, m), 0.01f)
        assertEquals(330f, PoolDock.deckHeight(PoolStop.HALF, m), 0.01f)
        // A deck taller than the window leaves the dock its furniture.
        val tall = m.copy(deck = 690f, minDeck = 690f)
        assertEquals(140f, PoolDock.height(PoolStop.HALF, tall), 0.01f)
        // Without a deck height, HALF is half, as it was.
        assertEquals(350f, PoolDock.height(PoolStop.HALF, m.copy(deck = null, minDeck = 200f)), 0.01f)
    }
}
