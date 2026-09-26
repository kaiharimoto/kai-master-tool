package com.kaiharimoto.mastertool.core.motion

import com.kaiharimoto.mastertool.core.layout.SandPaths
import com.kaiharimoto.mastertool.core.layout.SandTrack
import kotlin.math.abs
import kotlin.math.hypot
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
        assertTrue(1000f * stage.scale <= 1920f * 0.58f + 0.5f)
        assertTrue(800f * stage.scale <= 1080f * 0.86f + 0.5f)
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
    fun theBallNeverJumpsBetweenTracks() {
        for (seed in 0 until 3) {
            val run = SandPaths.startSeed(seed)
            var track = SandPaths.first(radius = 300f, spacing = 12f, seed = seed)
            repeat(24) { n ->
                val next = SandPaths.track(n + 1, 300f, 12f, track, seed = run)
                val (ex, ey) = track.at(1.0)
                val (sx, sy) = next.at(0.0)
                assertTrue(hypot(ex - sx, ey - sy) < 1e-3, "seed $seed track $n (${track.kind}) ends at ($ex,$ey), ${next.kind} starts at ($sx,$sy)")
                track = next
            }
        }
    }

    @Test
    fun everyProgramBeginsAtTheCentre() {
        for (seed in 0 until 6) {
            val (x, y) = SandPaths.first(300f, 12f, seed).at(0.0)
            assertTrue(hypot(x, y) < 1e-6, "seed $seed")
        }
    }

    @Test
    fun everyTrackStaysInTheDisk() {
        var track = SandPaths.first(300f, 12f, 1)
        repeat(12) { n ->
            for (i in 0..2000) {
                val (x, y) = track.at(i / 2000.0)
                assertTrue(hypot(x, y) <= 1.0 + 1e-9, "${track.kind} leaves the disk")
            }
            track = SandPaths.track(n + 1, 300f, 12f, track, SandPaths.startSeed(1))
        }
    }

    @Test
    fun theBallRollsAtOneSpeed() {
        for (track in listOf(SandTrack(SandTrack.Kind.SPIRAL_OUT, turns = 20f), SandTrack(SandTrack.Kind.ROSE, p = 7, q = 4))) {
            var s = 0.05
            val steps = mutableListOf<Double>()
            repeat(400) {
                val next = SandPaths.advance(track, s, 2f, 300f)
                val (x0, y0) = track.at(s)
                val (x1, y1) = track.at(next)
                steps += hypot(x1 - x0, y1 - y0) * 300.0
                s = next
            }
            steps.forEach { assertEquals(2.0, it, 0.2) }
        }
    }
}
