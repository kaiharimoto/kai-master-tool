package com.kaiharimoto.mastertool.core.motion

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * How far into zen the builder is. kai's brief, for immersive mode: after three
 * seconds of nothing, the interface fades and the cards stay; after ten, the pool
 * and the card details go too, the deck comes to the middle and floats, and a
 * sand garden is raked around it. Any movement brings everything back.
 */
enum class ZenPhase {
    AWAKE,

    /** Three seconds idle: everything but the cards fades. */
    QUIET,

    /** Ten seconds idle: the pool and the inspector go, the deck floats in the middle, the garden is raked. */
    DEEP,
}

/** When each phase begins, from how long it has been since the last thing the person did. */
object ZenClock {
    const val QUIET_MS = 3_000L
    const val DEEP_MS = 10_000L

    fun phase(idleMs: Long): ZenPhase = when {
        idleMs >= DEEP_MS -> ZenPhase.DEEP
        idleMs >= QUIET_MS -> ZenPhase.QUIET
        else -> ZenPhase.AWAKE
    }

    /** How long until the phase next deepens, or null once it is as deep as it goes. */
    fun untilNext(idleMs: Long): Long? = when {
        idleMs < QUIET_MS -> QUIET_MS - idleMs.coerceAtLeast(0)
        idleMs < DEEP_MS -> DEEP_MS - idleMs
        else -> null
    }
}

/**
 * The transform that brings the deck front and centre: a uniform scale about
 * the deck's own centre and a move that puts that centre in the middle of the
 * window, sized so the deck fills [fillWidth] of the window's width or
 * [fillHeight] of its height, whichever is reached first — leaving the sides for
 * the garden.
 */
data class ZenStage(val scale: Float, val dx: Float, val dy: Float) {
    companion object {
        val NONE = ZenStage(1f, 0f, 0f)

        fun of(
            deckLeft: Float,
            deckTop: Float,
            deckWidth: Float,
            deckHeight: Float,
            windowWidth: Float,
            windowHeight: Float,
            fillWidth: Float = 0.58f,
            fillHeight: Float = 0.86f,
        ): ZenStage {
            if (deckWidth <= 0f || deckHeight <= 0f || windowWidth <= 0f || windowHeight <= 0f) return NONE
            val scale = min(windowWidth * fillWidth / deckWidth, windowHeight * fillHeight / deckHeight).coerceIn(0.6f, 2.2f)
            val cx = deckLeft + deckWidth / 2f
            val cy = deckTop + deckHeight / 2f
            return ZenStage(scale, windowWidth / 2f - cx, windowHeight / 2f - cy)
        }
    }

    /** Where a point of the deck ends up, [amount] of the way (0 at rest, 1 in zen), scaling about ([ox], [oy]). */
    fun apply(x: Float, y: Float, ox: Float, oy: Float, amount: Float): Pair<Float, Float> {
        val s = 1f + (scale - 1f) * amount
        return (ox + (x - ox) * s + dx * amount) to (oy + (y - oy) * s + dy * amount)
    }
}

/**
 * The ambient float of card [index] at [seconds]: each card drifts, turns and
 * breathes on its own slow clock, so the deck reads as things resting on air
 * rather than one sheet bobbing.
 *
 * Sums of sines with periods between about six and thirteen seconds, and phases
 * taken from the card's position by a fixed hash — so the same deck always floats
 * the same way, nothing is random, and no two neighbours move in step. The lean
 * is small enough to read as air, and large enough that the foil catches the
 * light as each card turns.
 */
object ZenFloat {
    /** The furthest a card drifts, in card widths. */
    const val DRIFT = 0.045f

    /** The most a card turns in its own plane, in degrees. */
    const val SPIN = 1.6f

    /** The most a card leans, in degrees. */
    const val LEAN = 7f

    fun pose(index: Int, seconds: Float): LeanPose {
        val a = phase(index, 1)
        val b = phase(index, 2)
        val c = phase(index, 3)
        val d = phase(index, 4)
        val t = seconds.toDouble()
        fun wave(period: Double, ph: Double) = sin(2.0 * PI * t / period + ph).toFloat()
        return LeanPose(
            rotationX = LEAN * 0.7f * wave(9.7, c) + LEAN * 0.3f * wave(5.9, a),
            rotationY = LEAN * 0.7f * wave(11.3, d) + LEAN * 0.3f * wave(6.7, b),
            lift = 0.02f + 0.012f * wave(8.3, a + b),
            dx = DRIFT * (0.65f * wave(12.1, a) + 0.35f * wave(7.3, d)),
            dy = DRIFT * (0.65f * wave(10.4, b) + 0.35f * wave(6.1, c)),
            spin = SPIN * wave(13.3, c + d),
        )
    }

    /** A phase in [0, 2π) for card [index] and channel [k], from an integer hash. */
    private fun phase(index: Int, k: Int): Double {
        var h = index * 0x27D4EB2D + k * 0x165667B1
        h = h xor (h ushr 15)
        h *= 0x2C1B3C6D
        h = h xor (h ushr 12)
        return ((h ushr 1) % 10_000) / 10_000.0 * 2.0 * PI
    }
}
