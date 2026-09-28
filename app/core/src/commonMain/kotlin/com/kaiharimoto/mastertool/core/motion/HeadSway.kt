package com.kaiharimoto.mastertool.core.motion

import kotlin.math.abs
import kotlin.math.exp

/**
 * How the device is being held, as the two angles that matter to a table.
 *
 * Not a full attitude. A tablet lying flat has a third angle — which way round
 * it is pointing — and it is deliberately absent: it changes when you turn your
 * chair, and nothing about the table should.
 *
 * @param pitchDegrees how far the far edge of the device is tipped away from
 *   you. Zero is however it was being held when the reference was taken.
 * @param rollDegrees how far the right edge is tipped away, likewise.
 */
data class HeadTilt(val pitchDegrees: Float, val rollDegrees: Float)

/**
 * The tablet's own tilt, moving the camera a degree or two. `docs/classic/AAA.md` #8:
 * *"the cheapest three-dimensional tell that exists on a handheld… one sensor
 * listener and a low-pass filter."*
 *
 * ## It moves the eye, and that is the whole point
 *
 * The obvious implementation is to slide the picture, and it is worthless. A
 * lens shift — `CameraPose.shiftX` — moves every pixel by the same amount by
 * construction, so near and far move together and there is **no parallax at
 * all**: it is a picture being dragged, not a room being looked into. What makes
 * a handheld screen read as a window is that things at different depths move by
 * different amounts, and the only way to get that is to move the camera.
 *
 * So this produces a small **yaw and pitch**, which is what `AAA.md` #8 asked
 * for, and it is applied on top of whatever the rig holds rather than written
 * into it. Two degrees is enough — at the seated seats the far edge of the board
 * and the near edge separate by several pixels, which is exactly the cue, and it
 * is far too little to fight a gesture.
 *
 * ## And it never produces roll
 *
 * Not by clamping it — by not having one. `CameraEnvelope`'s note is that a
 * horizon which tips is the single most reliable way to make somebody feel ill,
 * and a device's roll is the axis most likely to be mistaken for one. It is read
 * (it is half of [HeadTilt]) and it drives *yaw*, which is what leaning sideways
 * does to what you can see round a card.
 *
 * ## Nothing idles, and a latch is what makes that true
 *
 * The rule is that light moves when the hour moves and nothing moves because
 * time passed. A hand is not time passing — this is the same licence a card's
 * tilt under a finger has — but a hand resting on a desk is *noise*, and a stage
 * that redraws forever because a tablet is a fraction of a degree off level is
 * a screensaver with extra steps.
 *
 * So there are two gates. [deadband] throws away anything under it outright, and
 * [step] returns **false** once the filter has stopped moving, which is how the
 * frame loop knows not to write the plane. A device sitting on a table produces
 * no redraws at all, and `HeadSwayTest` holds it to that.
 *
 * ## The filter is per second, not per frame
 *
 * `exp(−λ·dt)`, like `CameraRig.glide`, so a 120Hz tablet and a 60Hz desktop
 * settle over the same *seconds*. A per-frame factor is the bug this codebase
 * has already written down twice.
 */
class HeadSway(
    /** Degrees of camera per degree of device. Well under one, on purpose. */
    val gain: Float = GAIN,
    /** The most it may ever contribute, in degrees, on either axis. */
    val limit: Float = LIMIT,
    /** Device degrees under which nothing happens at all. */
    val deadband: Float = DEADBAND,
    /** How fast the filter chases the target: `exp(−this · seconds)`. */
    val response: Float = RESPONSE,
) {

    /**
     * The posture the device was in when this started, and what everything is
     * measured against.
     *
     * Captured from the first sample rather than assumed to be flat, because
     * nobody holds a tablet flat: it is on a stand, or on a lap, or propped on a
     * knee, and a sway measured against level would arrive already pinned to its
     * limit and stay there. Null until the first finite sample.
     */
    private var reference: HeadTilt? = null

    /** Where the filter is heading. */
    private var wantedYaw = 0f
    private var wantedPitch = 0f

    /** And where it is, which is what a camera adds. */
    var yawDegrees: Float = 0f
        private set
    var pitchDegrees: Float = 0f
        private set

    /** True once the filter has caught up and there is nothing left to draw. */
    var settled: Boolean = true
        private set

    /** Whether a device has been seen at all. Until then this contributes nothing. */
    val referenced: Boolean get() = reference != null

    /**
     * Take the device's posture as the new zero.
     *
     * Called when the feature is switched on and when the stage is opened —
     * *not* on every sample, which would make the sway chase its own tail and
     * settle back to nothing however the tablet was moved.
     */
    fun restart() {
        reference = null
        wantedYaw = 0f
        wantedPitch = 0f
        yawDegrees = 0f
        pitchDegrees = 0f
        settled = true
    }

    /**
     * A reading from the device. Cheap, and safe to call at whatever rate the
     * sensor produces.
     *
     * Non-finite samples are dropped whole rather than clamped: a sensor that
     * has produced one NaN has not told you anything about where the device is,
     * and folding it in as a zero is inventing a posture. It must not become the
     * reference either, which is the reason for the check being here rather than
     * in [step].
     */
    fun onTilt(tilt: HeadTilt) {
        if (!tilt.pitchDegrees.isFinite() || !tilt.rollDegrees.isFinite()) return

        val zero = reference ?: tilt.also { reference = it }
        // Roll drives yaw and pitch drives pitch: leaning the tablet sideways is
        // leaning your head sideways, which is what changes what you can see
        // round the side of a card.
        wantedYaw = swing(tilt.rollDegrees - zero.rollDegrees)
        wantedPitch = swing(tilt.pitchDegrees - zero.pitchDegrees)
        if (wantedYaw != yawDegrees || wantedPitch != pitchDegrees) settled = false
    }

    /**
     * One frame of the filter. Returns whether anything moved.
     *
     * The return value is load-bearing and is the whole of "nothing idles": the
     * play stage's frame loop writes the plane only on frames something moved,
     * and a false here on a still device is what stops sixty cards being
     * re-projected forever because a tablet is a fraction of a degree off level.
     */
    fun step(dt: Float): Boolean {
        if (settled || dt <= 0f || !dt.isFinite()) return false

        val keep = exp(-response * dt)
        yawDegrees = wantedYaw + (yawDegrees - wantedYaw) * keep
        pitchDegrees = wantedPitch + (pitchDegrees - wantedPitch) * keep

        if (abs(yawDegrees - wantedYaw) < REST && abs(pitchDegrees - wantedPitch) < REST) {
            yawDegrees = wantedYaw
            pitchDegrees = wantedPitch
            settled = true
        }
        return true
    }

    /** A device angle, deadbanded, geared and held to [limit]. */
    private fun swing(degrees: Float): Float {
        val past = abs(degrees) - deadband
        if (past <= 0f) return 0f
        // Measured from the edge of the deadband rather than from zero, so the
        // first degree past it contributes a first degree rather than jumping
        // by whatever the deadband was worth.
        val geared = past * gain
        val held = if (geared > limit) limit else geared
        return if (degrees < 0f) -held else held
    }

    companion object {
        /**
         * Two degrees is all it may ever be worth.
         *
         * Enough to separate the near and far edges of the board by several
         * pixels at the seated seats, which is the depth cue; far too little to
         * argue with a finger, which is the constraint. `AAA.md` #8 says "a
         * degree or two" and this is the top of that.
         */
        const val LIMIT = 2f

        /**
         * Device degrees inside which nothing happens at all.
         *
         * Two, which is more than a hand resting on a desk and less than anybody
         * would call a movement. Without it the stage redraws forever, because a
         * rotation vector is never exactly still — and *that* is the reading of
         * "nothing idles" that matters here.
         */
        const val DEADBAND = 2f

        /**
         * The tilt a wrist gives without being asked, which is what [GAIN] is
         * solved against. Twelve degrees.
         */
        const val WRIST = 12f

        /**
         * Degrees of camera per degree of device, past the deadband.
         *
         * A fifth, and it is **solved from the other two rather than chosen**:
         * twelve degrees is about what a wrist does without thinking about it,
         * the first [DEADBAND] of that is thrown away, and the remaining ten
         * have to arrive at [LIMIT]. So the range a person actually uses is the
         * whole range and everything past it is the same picture, rather than an
         * increasingly wrong one.
         *
         * It read a sixth for exactly as long as it took to write the test —
         * which is a third of a degree short of the two this paragraph claimed,
         * and precisely the class of number this project has been caught by
         * before. `HeadSwayTest.aWristsWorthOfTiltIsTheWholeUsefulRange` is the
         * tripwire, so the three constants cannot drift apart again.
         *
         * Gearing it one-to-one would make putting the tablet down a camera move.
         *
         * Declared last of the three, because a `const` may only be built from
         * `const`s already in scope and Kotlin means that literally.
         */
        const val GAIN = LIMIT / (WRIST - DEADBAND)

        /**
         * How fast the filter follows: `exp(−this · seconds)`.
         *
         * Six, so it is most of the way there in a fifth of a second — quick
         * enough to feel attached to the device rather than dragged behind it,
         * slow enough that sensor noise inside the deadband's edge does not
         * become a shimmer.
         */
        const val RESPONSE = 6f

        /** Below this the filter has arrived and there is nothing left to draw. */
        private const val REST = 0.002f
    }
}
