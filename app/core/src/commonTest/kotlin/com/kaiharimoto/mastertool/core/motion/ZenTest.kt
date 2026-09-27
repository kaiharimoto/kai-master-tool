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
    fun theStagePutsTheDeckInTheMiddleWithRoomRoundIt() {
        val stage = ZenStage.of(deckLeft = 400f, deckTop = 100f, deckWidth = 1000f, deckHeight = 800f, windowWidth = 1920f, windowHeight = 1080f)
        val (cx, cy) = stage.apply(900f, 500f, 900f, 500f, 1f)
        assertEquals(960f, cx, 0.5f)
        assertEquals(540f, cy, 0.5f)
        // The deck takes no more than its share of the width, or of the height.
        assertTrue(1000f * stage.scale <= 1920f * 0.72f + 0.5f)
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

    @Test
    fun anArrangementIsOffsetsThatAddUpAndReset() {
        val a = ZenArrangement()
        assertTrue(a.isEmpty)
        val k = ZenArrangement.key(section = 0, index = 7)
        a.move(k, 10f, -4f)
        a.move(k, 5f, 4f)
        assertEquals(15f to 0f, a.offsetOf(k))
        assertEquals(0f to 0f, a.offsetOf(ZenArrangement.key(1, 7)))
        val before = a.version
        a.reset()
        assertTrue(a.isEmpty && a.version > before)
        assertEquals(0f to 0f, a.offsetOf(k))
        assertEquals(0, a.layerOf(k))
        // A card put down lands on top of what it is put on.
        val first = ZenArrangement.key(0, 1)
        val second = ZenArrangement.key(0, 2)
        a.move(second, 1f, 1f)
        a.move(first, 1f, 1f)
        assertTrue(a.layerOf(first) > a.layerOf(second) && a.layerOf(second) > 0)
        // Keys do not collide between sections of a legal deck.
        assertNotEquals(ZenArrangement.key(0, 59), ZenArrangement.key(1, 0))
    }

    @Test
    fun aHigherCardCastsAFurtherSofterFainterShadow() {
        val low = ZenShadow.of(ZenShadow.REST_LIFT)
        val high = ZenShadow.of(0.12f)
        assertTrue(high.dy > low.dy && high.dx > low.dx)
        assertTrue(high.blur > low.blur)
        assertTrue(high.alpha < low.alpha)
        // Down and to the right: the light is up and to the left.
        assertTrue(low.dx > 0f && low.dy > 0f)
    }

    @Test
    fun theResetCornerIsTheBottomRightOnly() {
        assertTrue(ZenCorner.reaches(1900f, 1070f, 1920f, 1080f))
        assertTrue(!ZenCorner.reaches(1900f, 500f, 1920f, 1080f))
        assertTrue(!ZenCorner.reaches(100f, 1070f, 1920f, 1080f))
    }

    @Test
    fun aBlockFloatsAsOneAndItsScalesRunDiagonally() {
        for (step in 0 until 200) {
            val t = step * 0.137f
            // Every card of a block drifts the same way, so the block keeps its shape.
            val a = ZenFloat.inBlock(4, 0, 0, t)
            val b = ZenFloat.inBlock(4, 5, 2, t)
            assertEquals(a.dx, b.dx, 1e-6f)
            assertEquals(a.dy, b.dy, 1e-6f)
            assertEquals(0f, a.spin)
            // Cards on one diagonal flutter together; the next diagonal a step behind.
            assertEquals(ZenFloat.scales(3, 1, t), ZenFloat.scales(2, 2, t))
            val f = ZenFloat.scales(0, 0, t)
            assertTrue(abs(f.rotationX) <= ZenFloat.SCALE_LEAN + 1e-4f)
            // Leaning about the diagonal: the two axes move against each other.
            assertEquals(f.rotationX, -f.rotationY, 1e-6f)
        }
        assertNotEquals(ZenFloat.scales(0, 0, 1f), ZenFloat.scales(1, 0, 1f))
        // Two blocks do not drift in step.
        assertNotEquals(ZenFloat.group(1, 2f), ZenFloat.group(2, 2f))
    }

    @Test
    fun aCardPutDownHasLeftItsBlock() {
        val a = ZenArrangement()
        val k = ZenArrangement.key(0, 3)
        assertTrue(!a.isMoved(k))
        a.move(k, 4f, 0f)
        assertTrue(a.isMoved(k))
        a.reset()
        assertTrue(!a.isMoved(k))
    }
}
