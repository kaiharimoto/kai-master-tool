package com.kaiharimoto.mastertool.core.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tablet's own tilt, and the four ways it could quietly ruin the stage.
 *
 * It could **idle** — a rotation vector is never exactly still, so a naive
 * version redraws sixty cards forever because a tablet is a hundredth of a
 * degree off level. It could **roll** the horizon, which is the one thing
 * `CameraEnvelope` refuses outright. It could **fight a gesture**, if it were
 * ever geared strongly enough to matter. And it could **arrive already pinned**,
 * because nobody holds a tablet flat and a sway measured against level would sit
 * at its limit from the first frame.
 *
 * Most of this file is those four.
 */
class HeadSwayTest {

    private val frame = 1f / 120f

    private fun settle(sway: HeadSway, frames: Int = 400) {
        repeat(frames) { if (!sway.step(frame)) return }
    }

    // ---- it starts from wherever the device is -------------------------------------------

    @Test
    fun theFirstReadingIsTheZeroRatherThanLevelBeing() {
        // Nobody holds a tablet flat. A sway measured against level would arrive
        // pinned to its limit on a device propped on a knee and stay there for
        // the whole session, which reads as the camera being wrong rather than
        // as the feature being on.
        val sway = HeadSway()
        assertTrue(!sway.referenced, "it should not claim a posture before it has seen one")

        sway.onTilt(HeadTilt(pitchDegrees = 38f, rollDegrees = -12f))
        assertTrue(sway.referenced)
        settle(sway)
        assertEquals(0f, sway.yawDegrees, "a propped tablet is not a camera move")
        assertEquals(0f, sway.pitchDegrees)
    }

    @Test
    fun pickingItUpAgainTakesTheNewPostureAsTheZero() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(30f, 30f))
        settle(sway)
        assertTrue(abs(sway.pitchDegrees) > 0f, "this test is measuring nothing")

        sway.restart()
        assertEquals(0f, sway.pitchDegrees)
        assertEquals(0f, sway.yawDegrees)
        assertTrue(!sway.referenced)

        sway.onTilt(HeadTilt(30f, 30f))
        settle(sway)
        assertEquals(0f, sway.pitchDegrees, "the new posture did not become the zero")
        assertEquals(0f, sway.yawDegrees)
    }

    // ---- nothing idles --------------------------------------------------------------------

    @Test
    fun aDeviceLyingOnATableCostsTheFrameLoopNothing() {
        // The claim `docs/classic/LOOP.md`'s "nothing idles" reduces to here. `step`
        // returning false is what stops the play stage writing its plane, and a
        // plane written every frame re-projects sixty cards.
        val sway = HeadSway()
        sway.onTilt(HeadTilt(11f, -4f))
        settle(sway)

        assertTrue(!sway.step(frame), "a settled sway asked for a redraw")
        repeat(20) {
            // Sensor noise, well inside the deadband.
            sway.onTilt(HeadTilt(11f + it * 0.01f, -4f - it * 0.01f))
            assertTrue(!sway.step(frame), "noise at rest asked for a redraw on frame $it")
        }
    }

    @Test
    fun theDeadbandIsWiderThanAHandRestingOnADesk() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        settle(sway)

        sway.onTilt(HeadTilt(sway.deadband * 0.9f, sway.deadband * 0.9f))
        settle(sway)
        assertEquals(0f, sway.pitchDegrees, "inside the deadband and it still moved")
        assertEquals(0f, sway.yawDegrees)

        sway.onTilt(HeadTilt(sway.deadband * 4f, 0f))
        settle(sway)
        assertTrue(sway.pitchDegrees > 0f, "outside the deadband and it did not move")
    }

    @Test
    fun itLeavesTheDeadbandWithoutAStep() {
        // Measured from the edge of the deadband rather than from zero. The
        // alternative jumps by whatever the deadband was worth the instant it is
        // crossed, which is the thing a deadband exists to prevent.
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        settle(sway)

        sway.onTilt(HeadTilt(sway.deadband + 0.01f, 0f))
        settle(sway)
        assertTrue(
            sway.pitchDegrees < 0.02f,
            "it jumped to ${sway.pitchDegrees} the moment it left the deadband",
        )
    }

    // ---- and it stays small, and never rolls ------------------------------------------------

    @Test
    fun itIsNeverWorthMoreThanTwoDegreesHoweverFarTheDeviceGoes() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        listOf(20f, 60f, 90f, 180f, -180f, 4000f).forEach { angle ->
            sway.onTilt(HeadTilt(angle, angle))
            settle(sway)
            assertTrue(
                abs(sway.pitchDegrees) <= sway.limit + 1e-5f,
                "pitch reached ${sway.pitchDegrees} at $angle",
            )
            assertTrue(
                abs(sway.yawDegrees) <= sway.limit + 1e-5f,
                "yaw reached ${sway.yawDegrees} at $angle",
            )
        }
    }

    @Test
    fun aWristsWorthOfTiltIsTheWholeUsefulRange() {
        // Twelve degrees is about what a wrist does without thinking about it,
        // and it should reach the limit — so the range a person actually uses is
        // the whole range, and everything past it is the same picture rather
        // than an increasingly wrong one.
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(12f, 0f))
        settle(sway)
        assertEquals(sway.limit, sway.pitchDegrees, 0.05f)
    }

    @Test
    fun itIsSymmetricAndSignedTheWayTheDeviceIs() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(8f, 8f))
        settle(sway)
        val positive = sway.pitchDegrees to sway.yawDegrees
        assertTrue(positive.first > 0f && positive.second > 0f)

        sway.onTilt(HeadTilt(-8f, -8f))
        settle(sway)
        assertEquals(-positive.first, sway.pitchDegrees, 1e-4f)
        assertEquals(-positive.second, sway.yawDegrees, 1e-4f)
    }

    /**
     * There is no roll, and there is no way to ask for one.
     *
     * `CameraEnvelope`'s note is that a horizon which tips is the most reliable
     * way to make somebody feel ill, and a device's roll is the axis most likely
     * to be mistaken for one. This is a structural claim rather than a clamp:
     * the type has two outputs and neither is a roll, so the check is that
     * rolling the device moves the camera *round the table* instead.
     */
    @Test
    fun rollingTheDeviceTurnsTheTableRatherThanTippingTheHorizon() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(pitchDegrees = 0f, rollDegrees = 10f))
        settle(sway)

        assertTrue(abs(sway.yawDegrees) > 0.5f, "a roll did nothing at all")
        assertEquals(0f, sway.pitchDegrees, "a roll leaked into the pitch")
    }

    // ---- the filter is per second ------------------------------------------------------------

    @Test
    fun itSettlesOverTheSameSecondsAtSixtyHertzAndAtOneHundredAndTwenty() {
        // The bug this codebase has already written down twice: a per-frame
        // factor means something different on a 120Hz tablet than on a 60Hz
        // desktop, and a camera that behaves differently on two devices is a
        // camera nobody can tune.
        fun after(seconds: Float, hz: Float): Float {
            val sway = HeadSway()
            sway.onTilt(HeadTilt(0f, 0f))
            sway.onTilt(HeadTilt(12f, 0f))
            val dt = 1f / hz
            repeat((seconds * hz).toInt()) { sway.step(dt) }
            return sway.pitchDegrees
        }
        listOf(0.05f, 0.1f, 0.25f).forEach { seconds ->
            assertEquals(
                after(seconds, 60f),
                after(seconds, 120f),
                0.02f,
                "the two rates disagree after ${seconds}s",
            )
        }
    }

    @Test
    fun itArrivesSmoothlyRatherThanInOneFrame() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(12f, 0f))

        sway.step(frame)
        val firstFrame = sway.pitchDegrees
        assertTrue(
            firstFrame > 0f && firstFrame < sway.limit * 0.25f,
            "one frame took it to $firstFrame of ${sway.limit}",
        )
        settle(sway)
        assertEquals(sway.limit, sway.pitchDegrees, 0.05f)
    }

    // ---- and nothing hostile gets through -----------------------------------------------------

    @Test
    fun aSensorThatProducesNonsenseIsIgnoredRatherThanBelieved() {
        // Dropped whole rather than clamped, and — the part that matters — never
        // allowed to become the reference. A NaN posture poisons every sample
        // after it, and the symptom is a camera that has quietly stopped
        // responding rather than anything that throws.
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

        bad.forEach { value ->
            val fresh = HeadSway()
            fresh.onTilt(HeadTilt(value, value))
            assertTrue(!fresh.referenced, "$value became the posture everything is measured from")

            fresh.onTilt(HeadTilt(5f, 5f))
            settle(fresh)
            assertEquals(0f, fresh.pitchDegrees, "and the real reading after it was not the zero")
        }

        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(12f, 0f))
        settle(sway)
        val before = sway.pitchDegrees
        bad.forEach { sway.onTilt(HeadTilt(it, it)) }
        settle(sway)
        assertEquals(before, sway.pitchDegrees, "nonsense moved a camera that was already aimed")
        assertTrue(sway.pitchDegrees.isFinite())
    }

    @Test
    fun aFrameOfNoTimeOrNonsenseTimeDoesNothing() {
        val sway = HeadSway()
        sway.onTilt(HeadTilt(0f, 0f))
        sway.onTilt(HeadTilt(12f, 0f))

        assertTrue(!sway.step(0f))
        assertTrue(!sway.step(-1f))
        assertTrue(!sway.step(Float.NaN))
        assertEquals(0f, sway.pitchDegrees, "a frame of no time moved it")
    }
}
