package com.kaiharimoto.mastertool.core.shootout.sim

import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.CardPair
import com.kaiharimoto.mastertool.core.shootout.model.DeckList
import com.kaiharimoto.mastertool.core.shootout.model.Decks
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Pool
import com.kaiharimoto.mastertool.core.shootout.model.Priors
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A made-up matchup whose true card and pair values are known (Phase S §4): the ground the method is proved on
 * before any person judges a hand.
 *
 * The deck is shaped like a real one: 40 cards, 24 different (six three-ofs, four two-ofs, fourteen one-ofs) in four
 * roles (starters, extenders, hand traps, bricks); the opponent's is 40 cards, 17 different. The true values are
 * drawn from the model's own priors around role averages the model is not told, so a calibrated model's ranges
 * should hold the truth as often as they claim. Three of the twelve named pairs are real (two combos, one
 * redundancy); the other nine are nothing, to see that nothing is invented.
 */
class SyntheticDeck(
    val spec: ModelSpec,
    val decks: Decks,
    /** The true parameters, in the model's own layout. */
    val truth: DoubleArray,
    /** The pairs that really carry an effect, by index into [ModelSpec.pairs]. */
    val realPairs: Set<Int>,
) {
    companion object {
        /** Copies of each of the 24 cards: six three-ofs, four two-ofs, fourteen one-ofs. */
        val COPIES = intArrayOf(3, 3, 3, 3, 3, 3, 2, 2, 2, 2) + IntArray(14) { 1 }

        /** 0 starter, 1 extender, 2 hand trap, 3 brick. */
        val ROLES = intArrayOf(0, 0, 0, 2, 2, 1, 0, 2, 1, 3, 0, 0, 2, 2, 2, 1, 1, 1, 1, 1, 3, 3, 3, 3)

        /** The roles' true averages, which the model must find for itself. */
        val ROLE_MEANS = doubleArrayOf(0.9, 0.3, 0.4, -0.5)

        /** The opponent's 17 cards: ten three-ofs, three two-ofs, four one-ofs. */
        val OPPONENT_COPIES = IntArray(10) { 3 } + intArrayOf(2, 2, 2) + IntArray(4) { 1 }

        /** Twelve named pairs; the first three are real. */
        val PAIRS = listOf(
            CardPair(0, 5), CardPair(1, 8), CardPair(0, 1),
            CardPair(2, 5), CardPair(3, 4), CardPair(0, 3), CardPair(1, 2), CardPair(2, 9),
            CardPair(4, 6), CardPair(5, 7), CardPair(1, 3), CardPair(6, 8),
        )

        /** The real pairs' true extra log-odds: two combos and a redundancy. */
        val PAIR_EFFECTS = doubleArrayOf(0.9, 0.7, -0.6)

        /**
         * A matchup in [strata] (game one first and second by default) with truth drawn from [seed]. The strata's
         * starting points are set so the average real hand wins about [winRate] of the time.
         */
        fun matchup(
            seed: Long,
            priors: Priors = Priors(),
            strata: List<Stratum> = listOf(Stratum.G1_FIRST, Stratum.G1_SECOND),
            winRate: Double = 0.55,
            lapse: Double = 0.0,
        ): SyntheticDeck {
            val spec = ModelSpec(
                cards = COPIES.size,
                strata = strata,
                roles = ROLES,
                opponentCards = OPPONENT_COPIES.size,
                pairs = PAIRS,
                priors = priors,
                lapse = lapse,
            )
            val decks = Decks.matchup(DeckList(COPIES), DeckList(OPPONENT_COPIES))
            val random = Random(seed)
            val l = spec.layout
            val t = DoubleArray(l.size)
            for (r in ROLE_MEANS.indices) t[l.role(r)] = ROLE_MEANS[r]
            for (c in COPIES.indices) {
                t[l.card(c)] = ROLE_MEANS[ROLES[c]] + priors.cardSd * gaussian(random)
                for (s in 0 until l.deviationsPerCard) t[l.deviation(c, s)] = priors.deviationSd * gaussian(random)
            }
            for (i in PAIR_EFFECTS.indices) t[l.pair(i)] = PAIR_EFFECTS[i]
            t[l.opponentMean] = 0.25
            for (o in OPPONENT_COPIES.indices) t[l.opponent(o)] = 0.25 + 0.4 * gaussian(random)
            for (j in 0 until spec.judges) {
                t[l.precision(j)] = ln(1 / 0.6)
                t[l.comparePrecision(j)] = ln(1 / (0.6 * sqrt(2.0)))
            }
            // Each stratum's starting point puts its average real hand at the chosen win rate.
            val value = HandValue(spec)
            for (s in strata.indices) {
                val pool = Pool.deal(decks, strata[s], 2000, seed * 13 + s)
                val mean = (0 until pool.size).sumOf { value.of(t, s, pool.hands[it], pool.opponents[it]) } / pool.size
                t[l.intercept(s)] = Logistic.logit(winRate) - mean
            }
            return SyntheticDeck(spec, decks, t, PAIR_EFFECTS.indices.toSet())
        }

        /** A standard normal draw (Box–Muller), so the truth depends on nothing but the seed. */
        fun gaussian(random: Random): Double {
            val u = random.nextDouble().coerceAtLeast(1e-300)
            val v = random.nextDouble()
            return sqrt(-2 * ln(u)) * cos(2 * PI * v)
        }
    }
}
