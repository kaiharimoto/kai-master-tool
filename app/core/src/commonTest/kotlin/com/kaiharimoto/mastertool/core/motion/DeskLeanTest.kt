package com.kaiharimoto.mastertool.core.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskLeanTest {

    @Test
    fun aPointerInTheMiddleOfACardLiftsItFlat() {
        val pose = DeskLean.toward(0f, 0f)
        assertEquals(0f, pose.rotationX, 1e-4f)
        assertEquals(0f, pose.rotationY, 1e-4f)
        assertEquals(DeskLean.HOVER_LIFT, pose.lift, 1e-4f)
    }

    @Test
    fun theEdgeNearestThePointerComesUp() {
        // Pointer to the right of the card's centre: right edge forward.
        assertTrue(DeskLean.toward(0.4f, 0f).rotationY > 0f)
        assertTrue(DeskLean.toward(-0.4f, 0f).rotationY < 0f)
        // Pointer above (screen y grows down): top edge forward.
        assertTrue(DeskLean.toward(0f, -0.4f).rotationX > 0f)
        // A neighbour on the slope leans the same way the card under it would.
        assertTrue(DeskLean.toward(1.2f, 0f).rotationY > 0f)
    }

    @Test
    fun itIsSymmetric() {
        for (d in listOf(0.2f, 0.5f, 0.9f, 1.7f)) {
            val a = DeskLean.toward(d, 0.3f)
            val b = DeskLean.toward(-d, 0.3f)
            assertEquals(a.rotationY, -b.rotationY, 1e-4f)
            assertEquals(a.rotationX, b.rotationX, 1e-4f)
            assertEquals(a.lift, b.lift, 1e-5f)
        }
    }

    @Test
    fun itIsBoundedAndFallsAwayWithDistance() {
        var last = Float.MAX_VALUE
        for (i in 0..60) {
            val r = 0.5f + i * 0.1f
            val pose = DeskLean.toward(r, 0f)
            assertTrue(abs(pose.rotationY) <= DeskLean.MAX_DEGREES + 1e-4f)
            assertTrue(abs(pose.rotationY) <= last + 1e-4f, "not falling at $r")
            last = abs(pose.rotationY)
        }
        assertEquals(LeanPose.REST, DeskLean.toward(10f, 0f))
        assertEquals(LeanPose.REST, DeskLean.toward(0.3f, 0.3f, presence = 0f))
    }

    @Test
    fun aLeanTooSmallToSeeIsFlat() {
        // Far out on the bump's side the turn is under a twentieth of a degree: drawn flat, exactly 0 (never -0),
        // so the card keeps off the perspective path and a still pointer redraws nothing (1.0.92).
        val far = (0..400).map { DeskLean.toward(0.5f + it * 0.01f, 0.3f) }
        far.forEach { p ->
            assertTrue(p.rotationY == 0f || abs(p.rotationY) >= DeskLean.FLAT_DEGREES * 0.5f, "a sliver of a turn: $p")
            if (p.rotationX == 0f) assertEquals(0f.toBits(), p.rotationX.toBits())
            if (p.rotationY == 0f) assertEquals(0f.toBits(), p.rotationY.toBits())
        }
        // The lift over a card's centre, where the lean itself is nothing, stays.
        assertEquals(DeskLean.HOVER_LIFT, DeskLean.toward(0f, 0f).lift, 1e-6f)
        // Where it was visible it is what it was.
        val near = DeskLean.toward(0.8f, 0.2f)
        assertTrue(abs(near.rotationY) > 1f)
    }

    @Test
    fun itIsContinuousAcrossTheCardsEdge() {
        val inside = DeskLean.toward(0.4999f, 0f)
        val outside = DeskLean.toward(0.5001f, 0f)
        assertEquals(inside.rotationY, outside.rotationY, 0.01f)
        assertEquals(inside.lift, outside.lift, 0.001f)
    }

    @Test
    fun aCarriedCardLeansBackAgainstItsMotionAndSaturates() {
        val moving = DeskLean.carried(0.3f, 0f)
        assertTrue(moving.rotationY > 0f)
        assertTrue(abs(DeskLean.carried(50f, -50f).rotationY) < DeskLean.CARRY_DEGREES)
        assertEquals(0f, DeskLean.carried(0f, 0f).rotationY, 1e-5f)
        assertEquals(DeskLean.CARRY_LIFT, DeskLean.carried(0f, 0f).lift, 1e-5f)
    }

    @Test
    fun approachDoesNotDependOnTheFrameRate() {
        var at60 = 0f
        repeat(60) { at60 = DeskLean.approach(at60, 100f, 1f / 60f, 0.05f) }
        var at144 = 0f
        repeat(144) { at144 = DeskLean.approach(at144, 100f, 1f / 144f, 0.05f) }
        assertEquals(at60, at144, 1e-3f)
        assertEquals(50f, DeskLean.approach(0f, 100f, 0.05f, 0.05f), 1e-3f)
    }

    @Test
    fun theFieldSettlesSoItsLoopCanStop() {
        val field = LeanField()
        field.aim(100f, 50f)
        // A bump that was absent appears where the pointer is, rather than sliding in.
        assertEquals(100f, field.x)
        field.aim(200f, 50f)
        repeat(120) { field.step(1f / 60f) }
        assertTrue(field.settled)
        assertEquals(200f, field.x)
        assertEquals(1f, field.presence)
        field.release()
        assertTrue(!field.settled)
        repeat(120) { field.step(1f / 60f) }
        assertTrue(field.settled)
        assertEquals(0f, field.presence)
    }

    @Test
    fun lightFollowsTheLean() {
        val (lx, ly) = DeskLean.toward(0.5f, -0.5f).light(DeskLean.MAX_DEGREES)
        assertTrue(lx > 0f && ly < 0f)
    }
}
