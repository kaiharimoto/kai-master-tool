package com.kaiharimoto.mastertool.core.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZenTest {

    @Test
    fun thePhasesAreKaisThreeAndTenSeconds() {
        assertEquals(ZenPhase.AWAKE, ZenClock.phase(2_999))
        assertEquals(ZenPhase.QUIET, ZenClock.phase(3_000))
        assertEquals(ZenPhase.QUIET, ZenClock.phase(9_999))
        assertEquals(ZenPhase.DEEP, ZenClock.phase(10_000))
        assertEquals(3_000L, ZenClock.untilNext(0))
        assertEquals(6_000L, ZenClock.untilNext(4_000))
        assertNull(ZenClock.untilNext(12_000))
    }

    @Test
    fun theStagePutsTheDeckInTheMiddleAndLeavesTheSides() {
        val stage = ZenStage.of(deckLeft = 400f, deckTop = 100f, deckWidth = 1000f, deckHeight = 800f, windowWidth = 1920f, windowHeight = 1080f)
        val (cx, cy) = stage.apply(900f, 500f, 900f, 500f, 1f)
        assertEquals(960f, cx, 0.5f)
        assertEquals(540f, cy, 0.5f)
        // The deck takes no more than its share of the width, or of the height.
        assertTrue(1000f * stage.scale <= 1920f * 0.52f + 0.5f)
        assertTrue(800f * stage.scale <= 1080f * 0.8f + 0.5f)
        // At zero it is where it was.
        assertEquals(400f to 100f, stage.apply(400f, 100f, 900f, 500f, 0f))
    }

    @Test
    fun theFloatIsSmallDeterministicAndOutOfStep() {
        for (i in 0 until 60) for (step in 0 until 200) {
            val p = ZenFloat.pose(i, step * 0.137f)
            assertTrue(abs(p.dx) <= ZenFloat.DRIFT + 1e-4f && abs(p.dy) <= ZenFloat.DRIFT + 1e-4f)
            assertTrue(abs(p.spin) <= ZenFloat.SPIN + 1e-4f)
            assertTrue(abs(p.rotationX) <= ZenFloat.LEAN + 1e-3f && abs(p.rotationY) <= ZenFloat.LEAN + 1e-3f)
        }
        assertEquals(ZenFloat.pose(7, 3.3f), ZenFloat.pose(7, 3.3f))
        assertNotEquals(ZenFloat.pose(7, 3.3f), ZenFloat.pose(8, 3.3f))
        // Continuous: a frame later is barely anywhere else.
        val a = ZenFloat.pose(3, 5f)
        val b = ZenFloat.pose(3, 5f + 1f / 60f)
        assertTrue(abs(a.rotationX - b.rotationX) < 0.2f && abs(a.dx - b.dx) < 0.002f)
    }

    @Test
    fun theDreamyLeanIsWiderAndGentler() {
        // Two cards away the builder's bump has all but gone; zen's has not.
        assertTrue(abs(DeskLean.dreamy(2f, 0f).rotationY) > abs(DeskLean.toward(2f, 0f).rotationY))
        assertTrue(abs(DeskLean.dreamy(0.45f, 0f).rotationY) < abs(DeskLean.toward(0.45f, 0f).rotationY))
    }
}
