package com.kaiharimoto.mastertool.core.duel.dice

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** One die as it leaves the hand: where, which way up, how fast and how it spins (world frame, the seat's arena). */
@Serializable
data class DieStart(
    val p: V3 = V3(),
    val q: Quat = Quat(),
    val v: V3 = V3(),
    val w: V3 = V3(),
)

/**
 * A throw of the opening roll's two dice (1.0.87): everything the simulation needs, in the seat's own arena
 * ([DiceSim.ARENA_W] by [DiceSim.ARENA_D], y toward the thrower), so it is the same throw drawn on either side of
 * the table. Written into the log with the roll and sent over the network, so every screen plays the same physics.
 *
 * A throw made by a hand comes from the drag ([fromDrag]); one typed, keyed or made by Ai is a [random] throw, made
 * from the same stamped randomness as the dice's values.
 */
@Serializable
data class DiceThrow(val dice: List<DieStart> = emptyList()) {

    /** Every number rounded, so the throw read back from the log is bit for bit the throw that was simulated. */
    fun rounded(): DiceThrow = DiceThrow(dice.map { DieStart(it.p.rounded(), it.q.rounded(), it.v.rounded(), it.w.rounded()) })

    /** Usable by [DiceSim]: two dice, every number finite. */
    val valid: Boolean
        get() = dice.size == 2 && dice.all { d ->
            listOf(d.p.x, d.p.y, d.p.z, d.q.w, d.q.x, d.q.y, d.q.z, d.v.x, d.v.y, d.v.z, d.w.x, d.w.y, d.w.z).all { it.isFinite() }
        }

    companion object {
        /** How high the dice are held over the table as they are carried and let go. */
        const val HELD = 1.6

        /** How far apart the two dice sit in the hand, centre to centre. */
        const val SPREAD = 1.3

        /** The fastest a fling throws, units a second: a hard flick still lands on the field. */
        const val MAX_SPEED = 42.0

        /**
         * A throw from the person's drag: the dice let go at [at] (the arena's x and y; z is [HELD]), turned [held]
         * (each die's orientation as carried), with the release's velocity [velocity] (units a second, x and y). The
         * spin is the roll a die takes from that velocity, plus a twist from [wobble] — how the drag curved — so two
         * throws of one speed tumble differently. Clamped into the arena.
         */
        fun fromDrag(at: V3, held: List<Quat>, velocity: V3, wobble: Double): DiceThrow {
            var v = V3(velocity.x, velocity.y, 0.0)
            val speed = v.length
            if (speed > MAX_SPEED) v = v * (MAX_SPEED / speed)
            val heading = if (v.length > 1e-6) v.normalized() else V3(0.0, -1.0, 0.0)
            val side = V3.UP cross heading
            val dice = (0..1).map { i ->
                val offset = side * (if (i == 0) -SPREAD / 2 else SPREAD / 2)
                val p = clampInto(V3(at.x, at.y, HELD) + offset)
                // Rolling forward (ω = up × v / r), more for the leading die, and a twist about the heading.
                val roll = (V3.UP cross v) * (1.6 + 0.25 * i)
                val twist = heading * (wobble * (if (i == 0) 1.0 else -0.8) + 4.0 * (if (i == 0) 1.0 else -1.0))
                val spin = side * (2.5 * (if (i == 0) 1.0 else -1.0))
                DieStart(p, held.getOrElse(i) { Quat.IDENTITY }, V3(v.x * (1.0 - 0.06 * i), v.y * (1.0 + 0.05 * i), 3.0 + 0.8 * i), roll + twist + spin)
            }
            // Let go against a wall, the clamp can bring them together: side by side along it instead.
            val gap = (dice[0].p - dice[1].p).length
            val parted = if (gap >= SPREAD * 0.95) dice else {
                val mid = (dice[0].p + dice[1].p) * 0.5
                val m = 0.9 + SPREAD / 2
                val cx = mid.x.coerceIn(m, DiceSim.ARENA_W - m)
                listOf(dice[0].copy(p = V3(cx - SPREAD / 2, mid.y, HELD)), dice[1].copy(p = V3(cx + SPREAD / 2, mid.y, HELD)))
            }
            return DiceThrow(parted).rounded()
        }

        /** A throw with no hand behind it (a key, a typed `roll`, Ai): from the near edge, toward the far one. */
        fun random(r: Random): DiceThrow {
            val x = DiceSim.ARENA_W / 2 + (r.nextDouble() - 0.5) * 6.0
            val y = DiceSim.ARENA_D - 1.2
            val v = V3((r.nextDouble() - 0.5) * 16.0, -(10.0 + r.nextDouble() * 7.0), 0.0)
            val held = List(2) { randomTurn(r) }
            return fromDrag(V3(x, y), held, v, (r.nextDouble() - 0.5) * 16.0)
        }

        /** A turn chosen evenly among all turns (Shoemake's). */
        fun randomTurn(r: Random): Quat {
            val u1 = r.nextDouble()
            val u2 = r.nextDouble() * 2 * PI
            val u3 = r.nextDouble() * 2 * PI
            val a = sqrt(1 - u1)
            val b = sqrt(u1)
            return Quat(a * sin(u2), a * cos(u2), b * sin(u3), b * cos(u3)).normalized()
        }

        private fun clampInto(p: V3): V3 {
            val m = 0.9
            return V3(p.x.coerceIn(m, DiceSim.ARENA_W - m), p.y.coerceIn(m, DiceSim.ARENA_D - m), p.z)
        }
    }
}
