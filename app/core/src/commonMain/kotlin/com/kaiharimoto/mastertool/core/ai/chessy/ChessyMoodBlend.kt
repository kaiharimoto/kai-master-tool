package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Chessy going from one mood to the next without a pop (the rig red team, `docs/chessy/RIG-REDTEAM.md`): her eyes and
 * mouth were swapped on one frame, her brows jumped up to twenty sheet pixels and her ears thirty degrees. Now the parts
 * of the mood she leaves cross-fade into the new one's over [FADE] ms (Cubism's expression fade-in is 0–0.1 s; this sits
 * just past it, as her parts are bigger patches), and her brows' tilt and lift and her ears' angle travel there on a
 * spring a little under critical damping — the ears overshoot a hair as they drop.
 *
 * [from] and [to] are the moods drawn; [fromAlpha] and [toAlpha] their parts' opacity (the new one rises through the
 * first half, the old one fades through the second, so neither ghosts through the middle and nothing jumps at either
 * end). [still] (reduced motion) changes at once.
 */
class ChessyMoodBlend(private val still: Boolean = false) {
    var showing: Expression? = null
        private set
    var from: ChessyMood = ChessyMoods.of(Expression.IDLE)
        private set
    var to: ChessyMood = from
        private set

    /** How far through the fade, 0 to 1. */
    var mix = 1f
        private set

    var browTilt = 0f
        private set
    var browLiftL = 0f
        private set
    var browLiftR = 0f
        private set
    var ears = 0f
        private set

    /** How open each eye is (her half-lids), eased like the brows. */
    var openL = 1f
        private set
    var openR = 1f
        private set
    private val v = FloatArray(6)

    /** The old mood's parts' opacity. */
    val fromAlpha: Float get() = (2f * (1f - mix)).coerceIn(0f, 1f)

    /** The new mood's parts' opacity. */
    val toAlpha: Float get() = (2f * mix).coerceIn(0f, 1f)

    /** Whether it is still on its way: drawn every frame meanwhile. */
    val busy: Boolean
        get() = mix < 1f || abs(browTilt - to.browTilt) > .05f || abs(browLiftL - to.browLiftL) > .05f ||
            abs(browLiftR - to.browLiftR) > .05f || abs(ears - to.ears) > .05f ||
            abs(openL - to.openL) > .005f || abs(openR - to.openR) > .005f || v.any { abs(it) > .5f }

    /** Wear [e] from now; the same mood again changes nothing. */
    fun show(e: Expression) {
        if (e == showing) return
        val first = showing == null
        showing = e
        val next = ChessyMoods.of(e)
        if (first || still) {
            from = next; to = next; mix = 1f
            browTilt = next.browTilt; browLiftL = next.browLiftL; browLiftR = next.browLiftR; ears = next.ears
            openL = next.openL; openR = next.openR
            v.fill(0f)
            return
        }
        // a change in the middle of a fade leaves from whichever of the two is showing more
        from = if (mix < .5f) from else to
        to = next
        mix = 0f
    }

    /** One step, [dt] seconds after the last. */
    fun step(dt: Float) {
        if (still) return
        val d = dt.coerceIn(0f, ChessyRig.MAX_STEP / 1000f)
        mix = (mix + d * 1000f / FADE).coerceAtMost(1f)
        if (mix >= 1f) from = to
        val pieces = max(1, ceil(d * 1000f / ChessyRig.PIECE).toInt())
        val h = d / pieces
        repeat(pieces) {
            browTilt = spring(0, browTilt, to.browTilt, h)
            browLiftL = spring(1, browLiftL, to.browLiftL, h)
            browLiftR = spring(2, browLiftR, to.browLiftR, h)
            ears = spring(3, ears, to.ears, h)
            openL = spring(4, openL, to.openL, h)
            openR = spring(5, openR, to.openR, h)
        }
    }

    private fun spring(i: Int, x: Float, goal: Float, h: Float): Float {
        v[i] += (W * W * (goal - x) - 2f * Z * W * v[i]) * h
        return x + v[i] * h
    }

    companion object {
        /** The parts' cross-fade, in ms. */
        const val FADE = 120f

        /** The brows' and ears' spring: natural frequency (rad/s) and damping ratio, settling in about a third of a second. */
        const val W = 18f
        const val Z = .7f
    }
}
