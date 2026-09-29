package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import kotlin.math.abs
import kotlin.math.exp

/**
 * The wheel's size for the deck in Neue's builder (`NeuePreferences.deckZoom`,
 * the share of the column the fitter is handed), and how the drawn deck glides
 * to it.
 *
 * Until 1.0.24 a notch took a flat 4 % off the share and the deck was re-fitted
 * at once, so the cards jumped a step a notch — fifteen notches end to end, and
 * a touchpad's stream of small deltas moved it in stutters. kai: "more sensitive
 * and more fluid/smooth feeling". Two changes:
 *
 * - **A notch is a ratio, not a step** ([wheel]): each one scales the share by
 *   `e^(−0.12)`, about 11 %, so the whole range is eight notches and every notch
 *   looks the same size whatever size the deck is. The delta is taken as it
 *   comes, so a touchpad's fractions move the deck in proportion, and a burst is
 *   capped at [MAX_NOTCHES] so a flung wheel cannot throw it end to end.
 * - **The drawn deck glides** ([approach]): the share the wheel sets is the
 *   target, and the deck is re-fitted every frame at a share that closes on it
 *   exponentially — [GLIDE_MS] is the time constant, so it is there, to the
 *   pixel, in about the family's 180 ms. An exponential approach is not a
 *   spring: it never overshoots and never bounces, and a notch that arrives
 *   mid-glide just moves the target, so a spun wheel is one continuous motion.
 */
object DeckZoom {

    /** The smallest share: the preference's floor. */
    const val MIN = NeuePreferences.MIN_ZOOM

    /** How much one notch scales the share, as a natural log: `e^(−0.12)` ≈ 0.887. */
    const val PER_NOTCH = 0.12f

    /** The most one scroll event may move, in notches: a flung wheel reports ten at once. */
    const val MAX_NOTCHES = 3f

    /** Growing past this share is the full size: the last notch up lands on the column's edge, not a hair short of it. */
    const val SNAP_FULL = 0.97f

    /** The glide's time constant: after four of them (about 180 ms) the deck is within 2 % of where it is going. */
    const val GLIDE_MS = 45f

    /** Closer than this to the target is the target: the glide ends, exactly. */
    const val SETTLED = 0.0015f

    /**
     * The share after a scroll of [delta] from [zoom]: positive (down, or toward
     * you) is smaller, negative larger. Proportional to the delta, so half a notch
     * is half the change in size.
     */
    fun wheel(zoom: Float, delta: Float): Float {
        val from = if (zoom.isFinite()) zoom.coerceIn(MIN, 1f) else 1f
        if (!delta.isFinite() || delta == 0f) return from
        val notches = delta.coerceIn(-MAX_NOTCHES, MAX_NOTCHES)
        val next = (from * exp(-notches * PER_NOTCH)).coerceIn(MIN, 1f)
        return if (notches < 0f && next >= SNAP_FULL) 1f else next
    }

    /**
     * The share to draw [dtMs] after [shown], closing on [target]. Never past it,
     * and exactly it once within [SETTLED].
     */
    fun approach(shown: Float, target: Float, dtMs: Float): Float {
        if (!shown.isFinite()) return target
        val dt = if (dtMs.isFinite()) dtMs.coerceAtLeast(0f) else 0f
        val next = target + (shown - target) * exp(-dt / GLIDE_MS)
        return if (abs(next - target) < SETTLED) target else next
    }

    /**
     * How far down the column's spare height the deck stands at [zoom], as a
     * fraction of it: at the full size a deck limited by its width sits at the
     * top, and a smaller one in the middle (1.0.17). Between the full size and
     * [SNAP_FULL] it goes smoothly from one to the other, so the first moment of
     * a glide does not drop the deck half its spare height in one frame.
     */
    fun centring(zoom: Float): Float {
        if (!zoom.isFinite()) return 0f
        return ((1f - zoom) / (1f - SNAP_FULL)).coerceIn(0f, 1f) * 0.5f
    }
}
