package com.kaiharimoto.mastertool.core.motion

import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How far a phone is tipped, for the foil (kai, v1.3.6: "have the foil react to
 * the gyroscopic data"): the light on a card follows the hand, the way a real
 * foil catches the room's light as the card is turned.
 *
 * It reads gravity — Android's `TYPE_GRAVITY`, or the accelerometer, in the
 * device's own axes — because gravity says how the screen is tipped with no
 * drift to correct, which a gyroscope's integrated rate does not. The answer is
 * relative to a **rest** that follows the hand over [restSeconds]: however the
 * phone is being held, held still the light settles in the middle of the card,
 * and it is the *turn* that moves it. That is what a foil does under a lamp, and
 * it is what makes it work lying in bed as well as sitting up.
 *
 * [x] is across the screen, positive when its right edge dips; [y] is down it,
 * positive when the screen is stood more upright; both −1..1 at [maxDegrees]
 * from rest, in the screen's axes whichever way the display is turned.
 */
data class Tilt(val x: Float, val y: Float) {
    companion object {
        val LEVEL = Tilt(0f, 0f)
    }
}

class TiltFilter(
    /** A turn this far from rest is the light at the card's edge. */
    val maxDegrees: Float = 22f,
    /** How long the rest takes to catch up with how the phone is held. */
    val restSeconds: Float = 2.5f,
    /** The light's own lag behind the hand: short, so it feels attached, never jittery. */
    val smoothSeconds: Float = 0.06f,
) {
    private var rest: Vec2? = null
    private var shown = Vec2.Zero

    /**
     * One sample: gravity [gx], [gy], [gz] in the device's axes (x to the right of its
     * natural orientation, y up it, z out of the screen), [quarterTurns] the display's
     * rotation from natural (Android's `Surface.ROTATION_*`, 0..3), [dtSeconds] since
     * the last sample.
     */
    fun feed(gx: Float, gy: Float, gz: Float, quarterTurns: Int, dtSeconds: Float): Tilt {
        // Into the screen's axes as it is drawn, so "right" is the right of what is shown.
        val (sx, sy) = when (((quarterTurns % 4) + 4) % 4) {
            1 -> -gy to gx
            2 -> -gx to -gy
            3 -> gy to -gx
            else -> gx to gy
        }
        // The screen's two tips, in degrees: about its vertical axis and about its horizontal one.
        val roll = degrees(atan2(-sx, sqrt(sy * sy + gz * gz)))
        val pitch = degrees(atan2(sy, sqrt(sx * sx + gz * gz)))
        val now = Vec2(roll, pitch)
        val dt = dtSeconds.coerceIn(0f, 0.5f)
        val held = rest ?: now
        rest = held + (now - held) * follow(dt, restSeconds)
        val target = (now - rest!!) / maxDegrees
        val clamped = Vec2(target.x.coerceIn(-1f, 1f), target.y.coerceIn(-1f, 1f))
        shown += (clamped - shown) * follow(dt, smoothSeconds)
        return Tilt(shown.x, shown.y)
    }

    /** The phone put down, or the screen turned: the rest is wherever it is next. */
    fun reset() {
        rest = null
        shown = Vec2.Zero
    }

    private fun follow(dt: Float, seconds: Float): Float =
        if (seconds <= 0f) 1f else (1f - exp(-dt / seconds)).coerceIn(0f, 1f)

    private fun degrees(radians: Float): Float = radians * 57.29578f
}
