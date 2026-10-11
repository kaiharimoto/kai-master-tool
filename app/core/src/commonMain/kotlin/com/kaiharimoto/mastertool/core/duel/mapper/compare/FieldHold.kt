package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.ai.meta.FieldProfiles
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSet
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSuite
import kotlin.math.roundToInt

/**
 * What the other seat holds in a comparison against the field (Phase G part two, G2.4; `docs/phases/G2.md` §8): on each hand
 * at most one of [sets] — the field's interruptions in the deck's stress suite — drawn as often as the field opens it, and
 * nothing the rest of the time. The draw is the comparison's seed and the hand's number alone, so both versions meet the
 * same interruption on the same hand (paired), and a run gives the same draws on every device.
 *
 * One interruption at a time: the suite's pairs are the Stress tab's. A card the engine cannot play is in [unwritten], with
 * the hands it would have been held on: on those it is played as nothing, and the words say so.
 */
data class FieldHold(val sets: List<Held>, val unwritten: List<Held> = emptyList()) {
    /** [card] (canonical) held on a share [chance] of hands: the field's share playing it times the chance it is opened. */
    data class Held(val card: Int, val chance: Double, val share: Double, val copies: Double) {
        val cards: List<Int> get() = listOf(card)
        val key: String get() = StressSuite.keyOf(cards)
    }

    private val scale: Double = sets.sumOf { it.chance }.let { if (it > 1.0) 1.0 / it else 1.0 }

    /** The share of hands on which the other seat holds one of [sets]. */
    val chance: Double get() = sets.sumOf { it.chance } * scale

    /** What the other seat holds on hand [k] of [seed]'s deal: one of [sets], or null for nothing. */
    fun on(seed: Long, k: Int): Held? {
        if (sets.isEmpty()) return null
        val u = uniform(seed, k)
        var at = 0.0
        for (s in sets) {
            at += s.chance * scale
            if (u < at) return s
        }
        return null
    }

    companion object {
        /** Copies of an interruption a list plays when the field's read does not say: the usual three. */
        const val COPIES = 3.0

        /** The other seat's Main Deck and opening hand, for the chance it opens a card. */
        const val DECK = 40
        const val OPENING = 5

        /**
         * The single-card sets of [suite] that came from the field (a [StressSet.share]), each held as often as the field opens
         * it: its share times the chance of at least one of its [copies] in five cards from forty. [written]: whether the
         * engine can play a card; one it cannot goes to [unwritten]. Null when the suite holds none of the field's cards.
         */
        fun of(suite: StressSuite, written: (Int) -> Boolean, copies: (Int) -> Double? = { null }): FieldHold? {
            val held = suite.sets.filter { it.share != null && it.cards.size == 1 }.map { s ->
                val card = s.cards.single()
                val n = copies(card) ?: COPIES
                Held(card, s.share!! * opens(n), s.share, n)
            }
            if (held.isEmpty()) return null
            return FieldHold(held.filter { written(it.card) }, held.filterNot { written(it.card) })
        }

        /** The chance of opening at least one of [copies] in five cards from forty. */
        fun opens(copies: Double): Double = FieldProfiles.atLeast(DECK, copies.roundToInt().coerceIn(0, 3), OPENING, 1)

        /** A number in [0, 1) from [seed] and [k] alone (SplitMix64): the same on every platform. */
        fun uniform(seed: Long, k: Int): Double {
            var z = seed * -0x61c8864680b583ebL + k.toLong() * 0x2545F4914F6CDD1DL + 0x1234567L
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return (z ushr 11).toDouble() / (1L shl 53).toDouble()
        }
    }
}
