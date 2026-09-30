package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarTest {

    private fun signedArea(p: FloatArray): Float {
        var a = 0f
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return a / 2
    }

    @Test
    fun theHeadIsClosedAndTheEyesSitInsideItAtOneHeight() {
        val body = AvatarGeometry.body
        assertEquals(0, (body.size - 2) % 6)
        assertEquals(body[0], body[body.size - 2], .01f)
        assertEquals(body[1], body[body.size - 1], .01f)
        // Both eyes 81.6 px tall, their middles inside the head's box.
        assertEquals(AvatarGeometry.eyeL.ry, AvatarGeometry.eyeR.ry)
        for (e in listOf(AvatarGeometry.eyeL, AvatarGeometry.eyeR)) {
            val xs = body.filterIndexed { i, _ -> i % 2 == 0 }
            val ys = body.filterIndexed { i, _ -> i % 2 == 1 }
            assertTrue(e.cx in xs.min()..xs.max() && e.cy in ys.min()..ys.max())
        }
        AvatarGeometry.veins.forEach { assertEquals(0, (it.path.size - 2) % 6, it.name) }
    }

    @Test
    fun everyEyeIsSixtyFourPointsClockwiseFromItsRightmost() {
        val out = FloatArray(2 * EyeShape.POINTS)
        val placed = EyeShape.Placed()
        val pose = EyePose()
        EyeShape.target(pose, EyeBase(0f, 0f, 20f, 30f, 0f, 2f), 1f, out, placed)
        // Point 0 is the rightmost; y down, so a positive area is clockwise on screen.
        assertEquals(out.filterIndexed { i, _ -> i % 2 == 0 }.max(), out[0], .01f)
        assertTrue(signedArea(out) > 0)
        assertEquals(1f, placed.open, .001f)
        EyeShape.glyphs.forEach { (g, poly) ->
            assertEquals(2 * EyeShape.POINTS, poly.size, g.name)
            assertTrue(signedArea(poly) > 0, g.name)
            assertEquals(poly.filterIndexed { i, _ -> i % 2 == 0 }.max(), poly[0], .05f, g.name)
        }
    }

    @Test
    fun lidsShutTheEyeToALine() {
        val out = FloatArray(2 * EyeShape.POINTS)
        val placed = EyeShape.Placed()
        val pose = EyePose().apply { lidTop = .5f; lidBot = .5f }
        EyeShape.target(pose, AvatarGeometry.eyeL, 1f, out, placed)
        assertEquals(0f, placed.open, .001f)
    }

    @Test
    fun theFaceEasesWithoutOvershooting() {
        val from = Pose()
        val to = Pose()
        Expression.SURPRISED.pose(null, to)
        var last = from.l.ry
        repeat(60) {
            from.l.ease(to.l, 1 - kotlin.math.exp(-(1 / 60f) / AvatarRig.TAU_EYE))
            from.b.ease(to.b, .2f, .1f)
            assertTrue(from.l.ry >= last && from.l.ry <= to.l.ry + 1e-4f)
            assertTrue(from.b.sy <= to.b.sy + 1e-4f)
            last = from.l.ry
        }
        assertEquals(to.l.ry, from.l.ry, .01f)
    }

    @Test
    fun blinksComeEveryTwoAndAHalfToSixSeconds() {
        val rig = AvatarRig(seed = 3)
        val shut = mutableListOf<Float>()
        var was = false
        repeat(60 * 60) {
            val f = rig.step(1 / 60f)
            // A blink squashes the eye: its height collapses toward a line.
            val ys = f.eyeR.filterIndexed { i, _ -> i % 2 == 1 }
            val closed = ys.max() - ys.min() < 40f
            if (closed && !was) shut += rig.clock
            was = closed
        }
        assertTrue(shut.size in 8..30, "${shut.size} blinks in a minute")
        // Blinks start 2.5 to 6 s apart, or 0.28 s for the second of a double.
        shut.zipWithNext { a, b -> b - a }.forEach { gap -> assertTrue(gap < 6.3f && (gap > 2.3f || gap < .5f), "gap $gap") }
    }

    @Test
    fun reducedMotionIsTheStillPoseAndNeverMoves() {
        val rig = AvatarRig(seed = 1, still = true)
        rig.show(Expression.WINK)
        val a = rig.step(1 / 60f).eyeR.copyOf()
        repeat(100) { rig.step(1 / 60f) }
        assertTrue(a.contentEquals(rig.frame.eyeR))
        // The still wink is shut: its right eye is Done's arc, not an oval.
        val ys = a.filterIndexed { i, _ -> i % 2 == 1 }
        assertTrue(ys.max() - ys.min() < 2 * AvatarGeometry.eyeR.ry * .8f)
    }

    @Test
    fun theGlyphDrawsNoMarks() {
        val rig = AvatarRig(glyph = true)
        rig.show(Expression.LOVE)
        repeat(30) { rig.step(1 / 60f) }
        assertEquals(0, rig.frame.fx.size)
        assertEquals(0, rig.frame.top.size)
        val full = AvatarRig()
        full.show(Expression.LOVE)
        repeat(30) { full.step(1 / 60f) }
        assertTrue(full.frame.fx.size > 0)
    }

    @Test
    fun theEyesFollowThePointer() {
        val rig = AvatarRig(seed = 2)
        repeat(60) { rig.step(1 / 60f, 400f, 0f, 100f) }
        val right = rig.frame.eyeL.filterIndexed { i, _ -> i % 2 == 0 }.average()
        val left = AvatarRig(seed = 2).let { r -> repeat(60) { r.step(1 / 60f, -400f, 0f, 100f) }; r.frame.eyeL.filterIndexed { i, _ -> i % 2 == 0 }.average() }
        assertTrue(right - left > 8, "right $right, left $left")
    }

    @Test
    fun everyFaceHasAKaomojiAndStaysFinite() {
        assertEquals(20, Expression.entries.size)
        assertEquals(Expression.entries.size, Expression.entries.map { it.id }.toSet().size)
        Expression.entries.forEach { e ->
            assertTrue(e.kaomoji.startsWith("("), e.name)
            val rig = AvatarRig()
            rig.show(e)
            repeat(300) {
                val f = rig.step(1 / 30f)
                assertFalse(f.eyeL.any { it.isNaN() } || f.eyeR.any { it.isNaN() } || f.rot.isNaN(), e.name)
            }
        }
    }

    // ---- the mood table ------------------------------------------------------------------

    @Test
    fun theMoodTable() {
        val m = MoodTracker()
        val t = 100.0
        assertEquals(Expression.IDLE, m.at(AiSignals(), t, t))
        assertEquals(Expression.LISTENING, m.at(AiSignals(drafting = true), t, t))
        assertEquals(Expression.THINKING, m.at(AiSignals(running = true), t, t))
        assertEquals(Expression.WORKING, m.at(AiSignals(running = true, tool = "search_cards"), t, t))
        assertEquals(Expression.READING, m.at(AiSignals(running = true, tool = "mcp__neue__card_info"), t, t))
        assertEquals(Expression.SPEAKING, m.at(AiSignals(running = true, streaming = true), t, t))
        assertEquals(Expression.WAITING, m.at(AiSignals(running = true, waiting = true), t, t))
        assertEquals(Expression.READING, m.at(AiSignals(running = true, tuning = true, studying = true), t, t))
        assertEquals(Expression.LISTENING, m.at(AiSignals(tuning = true), t, t))
    }

    @Test
    fun momentsPassAndProblemsShowOnce() {
        val m = MoodTracker()
        m.done(10.0)
        assertEquals(Expression.DONE, m.at(AiSignals(), 11.0, 11.0))
        assertEquals(Expression.IDLE, m.at(AiSignals(), 13.0, 13.0))
        m.found(20.0)
        assertEquals(Expression.FOUND, m.at(AiSignals(running = true), 20.5, 20.0))
        assertEquals(Expression.THINKING, m.at(AiSignals(running = true), 21.5, 20.0))
        assertEquals(Expression.OOPS, m.at(AiSignals(problem = "Could not connect."), 30.0, 30.0))
        assertEquals(Expression.IDLE, m.at(AiSignals(problem = "Could not connect."), 35.0, 35.0))
        assertEquals(Expression.CRYING, m.at(AiSignals(problem = "Rate limit reached"), 36.0, 36.0))
        // A question beats a face Ai chose, which beats a moment.
        m.express(Expression.LOVE, 3.0, 40.0)
        m.thanked(40.0)
        assertEquals(Expression.LOVE, m.at(AiSignals(), 41.0, 41.0))
        assertEquals(Expression.WAITING, m.at(AiSignals(waiting = true), 41.0, 41.0))
    }

    @Test
    fun itSleepsAfterAMinuteAndWakesToTheNextInput() {
        val m = MoodTracker()
        assertEquals(Expression.IDLE, m.at(AiSignals(), 59.0, 0.0))
        assertEquals(Expression.SLEEPING, m.at(AiSignals(), 61.0, 0.0))
        assertEquals(Expression.SLEEPING, m.at(AiSignals(), 90.0, 0.0))
        assertEquals(Expression.WAKING, m.at(AiSignals(), 91.0, 90.5))
        assertEquals(Expression.WAKING, m.at(AiSignals(), 94.0, 90.5))
        assertEquals(Expression.IDLE, m.at(AiSignals(), 96.0, 90.5))
    }

    @Test
    fun thanksAndLimitsAreRead() {
        assertTrue(MoodTracker.isThanks("Thanks, that's great"))
        assertTrue(MoodTracker.isThanks("ありがとう"))
        assertFalse(MoodTracker.isThanks("Build me a thanksgiving deck"))
        assertTrue(MoodTracker.isLimit("You have hit your usage limit"))
        assertFalse(MoodTracker.isLimit("Could not connect."))
        assertTrue(MoodTracker.expressible.all { Expression.byId(it.id) == it })
    }

    @Test
    fun theBordersHoldTheirWidthsAtEverySize() {
        val (dark, white, outline) = AvatarRig.borders(.1f)
        assertEquals(2.2f, dark, .001f)
        assertEquals(4.4f, white, .001f)
        assertEquals(2.8f, outline, .001f)
        val big = AvatarRig.borders(10f)
        assertTrue(abs(big.first - 7f) < .001f && abs(big.second - 13f) < .001f && abs(big.third - 6f) < .001f)
    }
}
