package com.kaiharimoto.mastertool.core.duel.dice

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One die or one coin thrown during the duel (1.0.96, kai: "have a 3d dice and coin by the left side near the extra deck
 * for both players that the players can use in game for dice rolls and coin flips. clicking on one of them will bring
 * them to the field, just dragging them from the corner also works. the coin is thrown by dragging and throwing").
 *
 * Like the opening roll's [DiceThrow], everything the simulation needs, in the thrower's own arena; written into the log
 * with the [com.kaiharimoto.mastertool.core.duel.DuelAction.Dice] or [com.kaiharimoto.mastertool.core.duel.DuelAction.Coin]
 * and sent over the network, so every screen plays the same physics. The value is stamped first and the faces are
 * labelled to it where the physics leaves them, so a throw never decides what it reads.
 */
@Serializable
data class Toss(val start: DieStart = DieStart()) {

    fun rounded(): Toss = Toss(DieStart(start.p.rounded(), start.q.rounded(), start.v.rounded(), start.w.rounded()))

    val valid: Boolean
        get() = with(start) {
            listOf(p.x, p.y, p.z, q.w, q.x, q.y, q.z, v.x, v.y, v.z, w.x, w.y, w.z).all { it.isFinite() }
        }

    /** Thrown from the corner by the Extra Deck — a click, a typed `roll`, Ai — rather than let go by a hand. */
    val fromCorner: Boolean get() = start.p == CORNER

    companion object {
        /**
         * Where a throw with no hand behind it leaves from: over the Extra Deck's corner of the seat's arena, toward the
         * player and to their left, where the die and the coin are kept.
         */
        val CORNER = V3(1.2, DiceSim.ARENA_D - 1.2, DiceThrow.HELD)

        /** The fastest a fling throws, as for the opening dice. */
        const val MAX_SPEED = DiceThrow.MAX_SPEED

        /**
         * The die let go by a hand at [at] (the arena's x and y), turned [held], at the release's [velocity]: it rolls
         * forward from that velocity, twisted by how the drag curved ([wobble]).
         */
        fun die(at: V3, held: Quat, velocity: V3, wobble: Double): Toss {
            val v = capped(velocity)
            val heading = if (v.length > 1e-6) v.normalized() else V3(0.0, -1.0, 0.0)
            val side = V3.UP cross heading
            val roll = (V3.UP cross v) * 1.7
            val twist = heading * (wobble + 4.0)
            return Toss(DieStart(clampInto(V3(at.x, at.y, DiceThrow.HELD)), held, V3(v.x, v.y, 3.0), roll + twist + side * 2.5)).rounded()
        }

        /**
         * The coin let go by a hand (kai: "thrown by dragging and throwing"): it goes up as it leaves the hand, flipping end
         * over end about the line square to the throw — faster the harder it is thrown — with a little wobble from the drag.
         */
        fun coin(at: V3, held: Quat, velocity: V3, wobble: Double): Toss {
            val v = capped(velocity) * 0.7
            val speed = v.length
            val heading = if (speed > 1e-6) v.normalized() else V3(0.0, -1.0, 0.0)
            val side = V3.UP cross heading
            val flip = side * (22.0 + speed * 0.6)
            val twist = heading * (wobble * 0.5) + V3.UP * (wobble * 0.3)
            return Toss(DieStart(clampInto(V3(at.x, at.y, DiceThrow.HELD), DiceSim.COIN_R + 0.1), held, V3(v.x, v.y, 14.0 + speed * 0.2), flip + twist)).rounded()
        }

        /** The die thrown from [CORNER] toward the field's middle, from stamped randomness. */
        fun randomDie(r: Random): Toss {
            val v = V3(9.0 + r.nextDouble() * 7.0, -(5.0 + r.nextDouble() * 5.0), 0.0)
            return Toss(DieStart(CORNER, DiceThrow.randomTurn(r), V3(v.x, v.y, 3.0), (V3.UP cross v) * 1.7 + V3(0.0, 0.0, (r.nextDouble() - 0.5) * 12.0)))
                .rounded().let { it.copy(start = it.start.copy(p = CORNER)) }
        }

        /** The coin flipped from [CORNER] toward the field's middle, from stamped randomness. */
        fun randomCoin(r: Random): Toss {
            val v = V3(5.0 + r.nextDouble() * 4.0, -(3.0 + r.nextDouble() * 3.0), 0.0)
            val side = (V3.UP cross v).normalized()
            val flip = side * (24.0 + r.nextDouble() * 10.0) + V3.UP * ((r.nextDouble() - 0.5) * 4.0)
            return Toss(DieStart(CORNER, Quat.IDENTITY, V3(v.x, v.y, 15.0 + r.nextDouble() * 3.0), flip))
                .rounded().let { it.copy(start = it.start.copy(p = CORNER)) }
        }

        private fun capped(velocity: V3): V3 {
            var v = V3(velocity.x, velocity.y, 0.0)
            val speed = v.length
            if (speed > MAX_SPEED) v = v * (MAX_SPEED / speed)
            return v
        }

        private fun clampInto(p: V3, m: Double = 0.9): V3 =
            V3(p.x.coerceIn(m, DiceSim.ARENA_W - m), p.y.coerceIn(-DiceSim.INNER + m, DiceSim.ARENA_D - m), p.z)
    }
}

/** [Toss]es played out, kept for the last few: the table asks for the same run on every frame of it. */
object TossRuns {
    private const val KEEP = 6

    @kotlin.concurrent.Volatile
    private var kept: List<Pair<Pair<DiceSim.Shape, Toss>, DiceSim.Run>> = emptyList()

    fun of(shape: DiceSim.Shape, toss: Toss): DiceSim.Run {
        val key = shape to toss
        kept.firstOrNull { it.first == key }?.let { return it.second }
        val run = DiceSim.run(shape, toss.start)
        kept = (listOf(key to run) + kept.filter { it.first != key }).take(KEEP)
        return run
    }
}
