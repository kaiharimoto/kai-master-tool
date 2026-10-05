package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.input.CropCaption
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarPathTest {
    @Test
    fun durationsAndLiftsAreInBounds() {
        assertEquals(280L, AvatarPath.duration(0.0))
        assertEquals(280L, AvatarPath.duration(80.0))
        assertEquals(240L + 180, AvatarPath.duration(400.0))
        assertEquals(680L, AvatarPath.duration(5_000.0))
        for (d in listOf(0.0, 10.0, 100.0, 500.0, 2_000.0, 10_000.0)) {
            assertTrue(AvatarPath.duration(d) in 280L..680L)
            assertTrue(AvatarPath.lift(d) in 12.0..64.0)
        }
        assertEquals(36.0, AvatarPath.lift(200.0), 1e-9)
    }

    @Test
    fun easedProgressIsMonotonic() {
        var last = -1f
        for (i in 0..1_000) {
            val e = CropCaption.ease(i / 1_000f)
            assertTrue(e >= last - 1e-6f, "the ease went back at $i")
            last = e
        }
        assertEquals(0f, CropCaption.ease(0f), 1e-6f)
        assertEquals(1f, CropCaption.ease(1f), 1e-4f)
    }

    @Test
    fun aHopArrivesOnTimeLiftedAndThenWantsNoFrames() {
        val p = AvatarPath(DeskPoint(0.0, 500.0))
        p.hopTo(DeskPoint(400.0, 500.0), now = 1_000)
        val ms = AvatarPath.duration(400.0)
        var highest = 500.0
        var t = 1_000L
        while (t <= 1_000 + ms) {
            highest = minOf(highest, p.step(t).y)
            assertTrue(p.wantsFrames(t))
            t += 16
        }
        p.step(1_000 + ms)
        assertEquals(DeskPoint(400.0, 500.0), p.position)
        // A hop, never a slide along the floor: the middle is lifted (up is less y).
        assertTrue(highest < 500.0 - AvatarPath.lift(400.0) * 0.8, "it rose only to $highest")
        assertFalse(p.wantsFrames(1_000 + ms + 100), "settled: no frames wanted")
        assertEquals(1_000 + ms, p.landedAt)
    }

    @Test
    fun aReplanKeepsPositionAndDirection() {
        val p = AvatarPath(DeskPoint(100.0, 400.0))
        p.hopTo(DeskPoint(700.0, 400.0), now = 0)
        p.step(150)
        val at = p.position
        val heading = p.direction(150)
        p.hopTo(DeskPoint(300.0, 100.0), now = 150)
        assertEquals(at, p.position, "no jump")
        val after = p.direction(150)
        assertTrue(abs(heading.x - after.x) < 1e-6 && abs(heading.y - after.y) < 1e-6, "it leaves along the old tangent: $heading then $after")
    }

    @Test
    fun randomReplansNeverJump() {
        // The largest step a frame may take: what a 680 ms hop covers in one 16 ms frame at the ease's steepest, along
        // the longest arc this desktop allows — a re-planned arc leaves along the old tangent, so it may bow out to three
        // times the distance it crosses, plus the lift. (Skip ahead is its own case: it finishes in 120 ms by design.)
        val w = 1920.0
        val h = 1080.0
        var steepest = 0.0
        for (i in 0 until 2_000) steepest = maxOf(steepest, ((CropCaption.ease((i + 1) / 2_000f) - CropCaption.ease(i / 2_000f)) * 2_000).toDouble())
        val longest = hypot(w, h) * 3 + 4 * AvatarPath.MAX_LIFT
        val bound = steepest * longest * 16.0 / AvatarPath.MAX_MS
        val rnd = Random(11)
        repeat(40) {
            val p = AvatarPath(DeskPoint(rnd.nextDouble() * w, rnd.nextDouble() * h))
            var t = 0L
            var last = p.step(0)
            p.hopTo(DeskPoint(rnd.nextDouble() * w, rnd.nextDouble() * h), 0)
            repeat(400) {
                t += 16
                if (rnd.nextInt(8) == 0) p.hopTo(DeskPoint(rnd.nextDouble() * w, rnd.nextDouble() * h), t)
                val now = p.step(t)
                val step = now.distanceTo(last)
                assertTrue(step <= bound, "a $step dp step in one frame (bound $bound)")
                last = now
            }
        }
    }

    @Test
    fun skipFinishesIn120Ms() {
        val p = AvatarPath(DeskPoint(0.0, 0.0))
        p.hopTo(DeskPoint(1_000.0, 0.0), now = 0)
        p.step(50)
        p.skip(50)
        p.step(50 + AvatarPath.SKIP_MS)
        assertEquals(DeskPoint(1_000.0, 0.0), p.position)
        assertFalse(p.wantsFrames(50 + AvatarPath.SKIP_MS + 20))
    }

    @Test
    fun followingApproachesAMovingPointIndependentOfTheFrameRate() {
        fun run(frame: Long): DeskPoint {
            val p = AvatarPath(DeskPoint(0.0, 0.0))
            var t = 0L
            p.step(0)
            while (t < 60) {
                t += frame
                p.follow(DeskPoint(100.0, 0.0), t)
            }
            return p.position
        }
        val at60 = run(16)
        val at144 = run(4)
        // After 60 ms (its half-life give or take a frame) it has closed about half the gap, at 60 Hz and 240 Hz alike.
        assertTrue(abs(at60.x - at144.x) < 6.0, "$at60 vs $at144")
        assertTrue(at60.x in 40.0..65.0, "$at60")
        val p = AvatarPath(DeskPoint(0.0, 0.0))
        p.step(0)
        var t = 0L
        repeat(200) { t += 16; p.follow(DeskPoint(100.0, 0.0), t) }
        assertFalse(p.wantsFrames(t), "settled at the point: no frames")
    }

    @Test
    fun reducedMotionFadesInPlaceOfAHop() {
        val p = AvatarPath(DeskPoint(0.0, 0.0), reduced = true)
        p.hopTo(DeskPoint(500.0, 0.0), now = 0)
        p.step(10)
        assertEquals(DeskPoint(0.0, 0.0), p.position, "no travel")
        assertTrue(p.alpha(10) < 1f)
        assertEquals(0f, p.lean(10))
        p.step(AvatarPath.duration(500.0))
        assertEquals(DeskPoint(500.0, 0.0), p.position)
    }

    @Test
    fun theHeadLeansIntoItsTravelAndNoFurther() {
        val p = AvatarPath(DeskPoint(0.0, 300.0))
        p.hopTo(DeskPoint(800.0, 300.0), now = 0)
        var most = 0f
        var t = 0L
        while (t < 700) {
            p.step(t)
            most = maxOf(most, abs(p.lean(t)))
            t += 8
        }
        assertTrue(most > 4f && most <= AvatarPath.MAX_LEAN.toFloat() + 1e-3f, "$most")
    }
}
