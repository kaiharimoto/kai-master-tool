package com.kaiharimoto.mastertool.core.motion

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * How far into zen the builder is. kai's brief, for immersive mode: after three
 * seconds of nothing, the interface fades and the cards stay; after ten, the pool
 * and the card details go too, and the deck comes to the middle and floats over
 * its own shadows. **The cards are the garden** (1.0.10): in deep zen the pointer
 * picks them up and puts them down anywhere, and only a key brings the builder
 * back. A sand garden was drawn behind them for six releases and is gone.
 */
enum class ZenPhase {
    AWAKE,

    /** Three seconds idle: everything but the cards fades. */
    QUIET,

    /** Ten seconds idle: the pool and the inspector go, the deck floats in the middle, and the cards are free to arrange. */
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
 * [fillHeight] of its height, whichever is reached first — leaving room round it
 * to put cards down in.
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
            fillWidth: Float = 0.72f,
            fillHeight: Float = 0.8f,
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

    /** The most a card leans in the scales' flutter, in degrees. */
    const val SCALE_LEAN = 3.5f

    /** Seconds for the flutter to run once through a block. */
    const val SCALE_PERIOD = 5.5

    /** How far behind its neighbour, in radians, each diagonal of a block lifts. */
    const val SCALE_STEP = 0.55

    /**
     * A block of cards floating as one (1.0.12, kai: "float in groups until the
     * user breaks alignment"): every card of [group] shares one drift, so the
     * block keeps its shape. No spin — a block turned about each card's own
     * centre would come apart at its seams.
     */
    fun group(group: Int, seconds: Float): LeanPose {
        val a = phase(group, 11)
        val b = phase(group, 12)
        val c = phase(group, 13)
        val t = seconds.toDouble()
        fun wave(period: Double, ph: Double) = sin(2.0 * PI * t / period + ph).toFloat()
        return LeanPose(
            lift = 0.02f + 0.008f * wave(9.1, c),
            dx = DRIFT * (0.7f * wave(12.7, a) + 0.3f * wave(7.9, b)),
            dy = DRIFT * (0.7f * wave(10.9, b) + 0.3f * wave(6.3, c)),
        )
    }

    /**
     * The block's idle flutter, "diagonally, like scales": each card leans about
     * the diagonal and lifts a little, a moment after the card up and to the left
     * of it, so a slow wave runs through the block corner to corner. Cards on one
     * diagonal ([col] + [row]) move together.
     */
    fun scales(col: Int, row: Int, seconds: Float): LeanPose {
        val w = sin(2.0 * PI * seconds / SCALE_PERIOD - (col + row) * SCALE_STEP).toFloat()
        return LeanPose(
            rotationX = SCALE_LEAN * w,
            rotationY = -SCALE_LEAN * w,
            lift = 0.006f * (w + 1f),
        )
    }

    /** A card still in its block: the block's drift and the scales' flutter. */
    fun inBlock(group: Int, col: Int, row: Int, seconds: Float): LeanPose = group(group, seconds) + scales(col, row, seconds)

    /** A phase in [0, 2π) for card [index] and channel [k], from an integer hash. */
    private fun phase(index: Int, k: Int): Double {
        var h = index * 0x27D4EB2D + k * 0x165667B1
        h = h xor (h ushr 15)
        h *= 0x2C1B3C6D
        h = h xor (h ushr 12)
        return ((h ushr 1) % 10_000) / 10_000.0 * 2.0 * PI
    }
}

/**
 * Where the cards have been put in deep zen: an offset from its place in the
 * deck for each card that has been moved, in the deck's own pixels (inside the
 * zen transform, so a card follows the pointer at whatever scale the deck is
 * drawn). A card is named by a key the screen chooses — section and position.
 *
 * Only a picture: nothing here touches the deck's order. Leaving zen draws every
 * card home and coming back puts them where they were left, until [reset].
 */
class ZenArrangement {
    private val moved = HashMap<Int, Pair<Float, Float>>()
    private val order = HashMap<Int, Int>()
    private val joined = HashMap<Int, ZenMembership>()

    /**
     * Which block card [key] floats with and where in it: the one it was put
     * down beside, else [home] — its own block, which it keeps floating with even
     * when set down on its own, so picking a card up never makes it jump.
     */
    fun membershipOf(key: Int, home: ZenMembership): ZenMembership = joined[key] ?: home

    /**
     * Card [key] is let go: back into its own slot if it is near it, flush beside
     * another card if it is near one of that card's edges (and floating with that
     * card's block from then on), or left where it is ([ZenSnap]).
     */
    fun drop(key: Int, homes: Map<Int, ZenHome>): ZenSnap.Result {
        val result = ZenSnap.snap(key, this, homes)
        when (result) {
            ZenSnap.Result.Home -> {
                moved.remove(key)
                order.remove(key)
                joined.remove(key)
            }
            is ZenSnap.Result.Beside -> {
                moved[key] = result.dx to result.dy
                joined[key] = result.membership
            }
            ZenSnap.Result.Free -> joined.remove(key)
        }
        version++
        return result
    }

    /** Whether card [key] has been picked up and put down: it has left its block and floats on its own. */
    fun isMoved(key: Int): Boolean = key in moved

    /** How many times the arrangement has changed: a screen reads it to know to redraw. */
    var version: Int = 0
        private set

    val isEmpty: Boolean get() = moved.isEmpty()

    fun offsetOf(key: Int): Pair<Float, Float> = moved[key] ?: (0f to 0f)

    /**
     * Where card [key] lies in the pile: 0 for a card never moved, and higher for
     * one put down later — a card put down lands on top of what it is put on.
     */
    fun layerOf(key: Int): Int = order[key] ?: 0

    fun move(key: Int, dx: Float, dy: Float) {
        val (x, y) = offsetOf(key)
        moved[key] = (x + dx) to (y + dy)
        version++
        order[key] = version
    }

    fun reset() {
        if (moved.isEmpty()) return
        moved.clear()
        order.clear()
        joined.clear()
        version++
    }

    companion object {
        /** The key for card [index] of section [section] (its ordinal): unique while a section holds under a thousand. */
        fun key(section: Int, index: Int): Int = section * 1_000 + index
    }
}

/** Where card sits in the deck at rest (window pixels, before zen's transform), and the block it floats with there. */
data class ZenHome(val x: Float, val y: Float, val width: Float, val height: Float, val membership: ZenMembership)

/** A block to float with ([group], as `ZenFloat.group` takes it) and a card's cell in it, for the scales' flutter. */
data class ZenMembership(val group: Int, val col: Int, val row: Int)

/**
 * Where a card let go in deep zen settles (kai, 1.0.14: "allow me to snap it
 * back in and group cards together"). In order:
 *
 * 1. **Home**: within [RADIUS] of a card width of its own slot, it goes back in,
 *    flush, and floats with its block again.
 * 2. **Beside**: within [RADIUS] of the slot flush against another card's edge
 *    — left, right, above, below, the slot empty — it snaps there and joins that
 *    card's block, one cell over, so the two float and flutter as one.
 * 3. **Free**: anywhere else it stays where it was put.
 */
object ZenSnap {
    /** How near, as a fraction of the card's width, counts as reaching a slot. */
    const val RADIUS = 0.35f

    sealed interface Result {
        data object Home : Result
        data object Free : Result
        data class Beside(val target: Int, val dx: Float, val dy: Float, val membership: ZenMembership) : Result
    }

    fun snap(key: Int, arrangement: ZenArrangement, homes: Map<Int, ZenHome>): Result {
        val home = homes[key] ?: return Result.Free
        val (ox, oy) = arrangement.offsetOf(key)
        val reach = RADIUS * home.width
        if (kotlin.math.hypot(ox, oy) < reach) return Result.Home
        val px = home.x + ox
        val py = home.y + oy
        // Where every other card is drawn now.
        val drawn = homes.filterKeys { it != key }.mapValues { (k, h) ->
            val (dx, dy) = arrangement.offsetOf(k)
            (h.x + dx) to (h.y + dy)
        }
        var best: Result.Beside? = null
        var bestDistance = reach
        for ((other, spot) in drawn) {
            val target = homes.getValue(other)
            val (qx, qy) = spot
            val slots = listOf(
                Triple(qx + target.width, qy, 1 to 0),
                Triple(qx - home.width, qy, -1 to 0),
                Triple(qx, qy + target.height, 0 to 1),
                Triple(qx, qy - home.height, 0 to -1),
            )
            for ((sx, sy, step) in slots) {
                val d = kotlin.math.hypot(px - sx, py - sy)
                if (d >= bestDistance) continue
                // A slot another card already fills is not a slot.
                val taken = drawn.any { (k, at) ->
                    k != other && kotlin.math.abs(at.first - sx) < home.width / 2f && kotlin.math.abs(at.second - sy) < home.height / 2f
                }
                if (taken) continue
                val their = arrangement.membershipOf(other, target.membership)
                bestDistance = d
                best = Result.Beside(other, sx - home.x, sy - home.y, ZenMembership(their.group, their.col + step.first, their.row + step.second))
            }
        }
        return best ?: Result.Free
    }
}

/**
 * The shadow a floating card casts on the table under it, from a light up and
 * to the left: the higher the card, the further the shadow falls away down and
 * to the right, the softer its edge and the fainter it is. In card widths, so
 * it is the same shadow at every size a card is drawn.
 *
 * kai's exception to Master UI's "no shadows", for zen alone: a shadow is the
 * one thing that says a card is off the page rather than on it.
 */
data class ZenShadow(val dx: Float, val dy: Float, val blur: Float, val alpha: Float) {
    companion object {
        /** A card resting at the float's own height. */
        const val REST_LIFT = 0.02f

        fun of(lift: Float): ZenShadow {
            val h = (0.10f + lift * 3.2f).coerceIn(0.06f, 0.6f)
            return ZenShadow(
                dx = h * 0.35f,
                dy = h * 0.55f,
                blur = 0.04f + h * 0.35f,
                alpha = (0.34f - h * 0.28f).coerceIn(0.12f, 0.34f),
            )
        }
    }
}

/**
 * The bottom-right corner of the window in deep zen, where "put the cards back"
 * comes out: [WIDTH] by [HEIGHT] pixels, which a pointer reaches only on purpose.
 */
object ZenCorner {
    const val WIDTH = 240f
    const val HEIGHT = 140f

    fun reaches(x: Float, y: Float, windowWidth: Float, windowHeight: Float): Boolean =
        windowWidth > 0f && windowHeight > 0f && x >= windowWidth - WIDTH && y >= windowHeight - HEIGHT && x <= windowWidth && y <= windowHeight
}
