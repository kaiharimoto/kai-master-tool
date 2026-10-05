package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.input.CropCaption
import com.kaiharimoto.mastertool.core.motion.DeskLean

/**
 * How Ai's avatar moves on the desktop (§5.3, `AvatarPathTest`): pure arithmetic over a clock the caller advances, so
 * the drawing (`DeskAvatar.kt`) only reads [position] in `Modifier.offset { }` and the studio photographs a hop mid-air.
 *
 * - **A hop**: a quadratic arc from where it is to the target, its middle lifted by [lift], over [duration], progress
 *   along it eased by Master UI's one easing (0.2, 0, 0, 1). No spring.
 * - **Following**: at the caret or a printing line the target itself moves; the avatar approaches it with a
 *   frame-rate-independent half-life of [FOLLOW_HALF_LIFE_MS] (`DeskLean.approach`).
 * - **Never a jump**: a new target mid-hop re-plans from where the avatar is, the new arc leaving along the old one's
 *   tangent, so position and direction are continuous.
 * - **Skip ahead**: the hop under way finishes in [SKIP_MS] — still a glide.
 * - **Settled** wants no frames ([wantsFrames]).
 * - **Reduced motion** ([reduced]): no hop; the avatar fades out and in at the target over [SKIP_MS] ([alpha]).
 */
class AvatarPath(start: DeskPoint, var reduced: Boolean = false) {
    private var from = start
    private var ctrl = start
    private var to = start
    private var startMs = 0L
    private var durationMs = 0L
    private var hopping = false
    private var followTarget: DeskPoint? = null
    private var lastStep = Long.MIN_VALUE
    private var lastDirection = DeskPoint.ZERO

    /** Where the avatar is, as of the last [step]. */
    var position: DeskPoint = start
        private set

    /** Where it is heading: the hop's end, or the point it follows. */
    val target: DeskPoint get() = followTarget ?: to

    /** When the last hop ended (ms), for the landing's squash; null while one is under way. */
    var landedAt: Long? = null
        private set

    /** Hops to [point], from where it is now: a re-plan if a hop is under way. */
    fun hopTo(point: DeskPoint, now: Long, durationOverride: Long? = null) {
        val here = step(now)
        followTarget = null
        val d = here.distanceTo(point)
        val heading = if (hopping) direction(now) else DeskPoint.ZERO
        from = here
        to = point
        ctrl = if (heading.length > 1e-9) {
            // Leave along the old tangent: the new arc's first direction is the old arc's last.
            here + heading * (d * 0.5)
        } else {
            val mid = (here + point) * 0.5
            DeskPoint(mid.x, mid.y - 2 * lift(d))
        }
        startMs = now
        durationMs = durationOverride ?: duration(d)
        hopping = d > SETTLED
        landedAt = if (hopping) null else now
        if (!hopping) position = point
    }

    /** Rides a point that moves (the caret, a printing line): call each frame with where it is now. */
    fun follow(point: DeskPoint, now: Long) {
        if (hopping && now < startMs + durationMs) {
            // Still arriving: the hop's end moves with the point, no re-plan needed for a small move.
            to = point
            return
        }
        hopping = false
        followTarget = point
        step(now)
    }

    /** Skip ahead: the hop under way ends within [SKIP_MS], along the way it was going. */
    fun skip(now: Long) {
        if (!hopping) return
        val end = to
        if (now + SKIP_MS >= startMs + durationMs) return
        hopTo(end, now, SKIP_MS)
    }

    /** Advances to [now] and returns [position]. */
    fun step(now: Long): DeskPoint {
        val dt = if (lastStep == Long.MIN_VALUE) 0L else (now - lastStep).coerceAtLeast(0L)
        lastStep = maxOf(lastStep, now)
        if (hopping) {
            val e = eased(now)
            val next = if (reduced) (if (progress(now) < 0.5) from else to) else at(e)
            if (next.distanceTo(position) > 1e-9) lastDirection = next - position
            position = next
            if (now >= startMs + durationMs) {
                hopping = false
                position = to
                landedAt = startMs + durationMs
            }
        } else {
            val f = followTarget
            if (f != null && dt > 0) {
                val s = dt / 1000f
                val h = FOLLOW_HALF_LIFE_MS / 1000f
                val x = DeskLean.approach(position.x.toFloat(), f.x.toFloat(), s, h).toDouble()
                val y = DeskLean.approach(position.y.toFloat(), f.y.toFloat(), s, h).toDouble()
                val next = if (DeskPoint(x, y).distanceTo(f) < SETTLED) f else DeskPoint(x, y)
                if (next.distanceTo(position) > 1e-9) lastDirection = next - position
                position = next
            }
        }
        return position
    }

    /** True while a hop or a follow is unsettled: the only time the travel loop runs (§5.4). */
    fun wantsFrames(now: Long): Boolean =
        (hopping && now < startMs + durationMs + FRAME_SLACK) || (followTarget?.let { it.distanceTo(position) >= SETTLED } ?: false)

    /** The hop's progress in time, 0 to 1 (1 when settled). */
    fun progress(now: Long): Double =
        if (!hopping || durationMs <= 0) 1.0 else ((now - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)

    /** The opacity to draw at: 1, except mid-fade under reduced motion. */
    fun alpha(now: Long): Float {
        if (!reduced || !hopping) return 1f
        val p = progress(now)
        return (kotlin.math.abs(p - 0.5) * 2).toFloat().coerceIn(0f, 1f)
    }

    /** The head's lean into its travel, degrees (at most [MAX_LEAN]), 0 at rest: the rig leans about the chin. */
    fun lean(now: Long): Float {
        if (!hopping || reduced) return 0f
        val d = direction(now)
        val p = eased(now).toDouble()
        return (MAX_LEAN * d.x * 4 * p * (1 - p)).toFloat()
    }

    /** The unit direction of travel now (zero at rest). */
    fun direction(now: Long): DeskPoint {
        if (!hopping) return if (lastDirection.length > 1e-9) lastDirection * (1 / lastDirection.length) else DeskPoint.ZERO
        val e = eased(now).toDouble()
        val v = (ctrl - from) * (2 * (1 - e)) + (to - ctrl) * (2 * e)
        val l = v.length
        return if (l < 1e-9) DeskPoint.ZERO else v * (1 / l)
    }

    private fun eased(now: Long): Float = CropCaption.ease(progress(now).toFloat())

    /** The arc at eased progress [e]. */
    private fun at(e: Float): DeskPoint {
        val t = e.toDouble()
        val u = 1 - t
        return from * (u * u) + ctrl * (2 * u * t) + to * (t * t)
    }

    companion object {
        const val FOLLOW_HALF_LIFE_MS = 60f
        const val SKIP_MS = 120L
        const val MAX_LEAN = 12.0
        const val MIN_MS = 280L
        const val MAX_MS = 680L
        const val MIN_LIFT = 12.0
        const val MAX_LIFT = 64.0

        /** Closer than this is there (dp). */
        const val SETTLED = 0.25

        /** One more frame after a hop's end, so its last position is drawn. */
        private const val FRAME_SLACK = 17L

        /** A hop's time for [distance] dp: `clamp(240 + 0.45 · distance, 280, 680)` ms. */
        fun duration(distance: Double): Long = (240 + 0.45 * distance).coerceIn(MIN_MS.toDouble(), MAX_MS.toDouble()).toLong()

        /** How high the arc's middle is lifted: `clamp(0.18 · distance, 12, 64)` dp. */
        fun lift(distance: Double): Double = (0.18 * distance).coerceIn(MIN_LIFT, MAX_LIFT)
    }
}
