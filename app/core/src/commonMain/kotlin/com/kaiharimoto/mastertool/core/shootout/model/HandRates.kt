package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import kotlin.math.sqrt

/**
 * Win rates over chosen sets of real hands (Phase S, S4.2; `docs/phases/S.md` §5): a stratum's hands sorted into opening
 * patterns, or hands dealt from a deck a siding plan leaves — read with the same model the same way [Reporter] reads a
 * stratum's win rate, over hands dealt as a real shuffle would.
 *
 * Each rate's range counts two doubts: the fit's, by the delta method like every other number, and the pool's own — the
 * hands are a sample, and a pattern holding a twentieth of them stands on a twentieth of the pool.
 */
class HandRates(val spec: ModelSpec) {

    private val value = HandValue(spec)

    /**
     * One rate in points of win chance, over [hands] of the pool's hands: [sum] and [sumSq] are the hands' win chances
     * (in points) and their squares, [gradient] the sum of each hand's gradient (in points), not yet divided.
     */
    class Rate(val hands: Int, val sum: Double, val sumSq: Double, val gradient: DoubleArray) {
        val value: Double get() = if (hands == 0) 0.0 else sum / hands

        /** The pool's own doubt of [value], as a variance: the hands' spread over their number. */
        val sampling: Double
            get() = if (hands < 2) 0.0 else ((sumSq - sum * sum / hands) / (hands - 1)).coerceAtLeast(0.0) / hands

        /** The mean gradient: what [value] moves by with each parameter. */
        fun meanGradient(): DoubleArray = DoubleArray(gradient.size) { if (hands == 0) 0.0 else gradient[it] / hands }

        /** [value] with its range under [fit], the pool's doubt counted. */
        fun estimate(fit: Fit): Estimate =
            Estimate(value, sqrt((fit.covariance.quadratic(meanGradient()) + sampling).coerceAtLeast(0.0)))

        /** The hands here but not in [part] (a subset of these): what is left of the pool. */
        fun minus(part: Rate): Rate =
            Rate(hands - part.hands, sum - part.sum, sumSq - part.sumSq, DoubleArray(gradient.size) { gradient[it] - part.gradient[it] })
    }

    /**
     * The hands of [pool] read in [stratum] under [theta], each counted in the rate of every pattern [classify] names for it,
     * and in [ALL]: one pass, every pattern's rate together.
     */
    fun sorted(theta: DoubleArray, stratum: Stratum, pool: Pool, classify: (Hand, Hand?) -> Collection<String>): Map<String, Rate> {
        val s = spec.stratumIndex(stratum)
        val n = spec.layout.size
        val counts = HashMap<String, IntArray>()
        val sums = HashMap<String, DoubleArray>()
        val grads = HashMap<String, DoubleArray>()
        fun add(key: String, w: Double, slope: Double, hand: Hand, opp: Hand?) {
            counts.getOrPut(key) { IntArray(1) }[0]++
            val sum = sums.getOrPut(key) { DoubleArray(2) }
            sum[0] += w
            sum[1] += w * w
            value.addFeatures(grads.getOrPut(key) { DoubleArray(n) }, slope, s, hand, opp)
        }
        for (i in 0 until pool.size) {
            val hand = pool.hands[i]
            val opp = pool.opponents[i]
            val eta = value.of(theta, s, hand, opp)
            val w = POINTS * Logistic.of(eta)
            val slope = POINTS * Logistic.slope(eta)
            add(ALL, w, slope, hand, opp)
            for (key in classify(hand, opp)) if (key != ALL) add(key, w, slope, hand, opp)
        }
        return counts.keys.associateWith { k ->
            val sum = sums.getValue(k)
            Rate(counts.getValue(k)[0], sum[0], sum[1], grads.getValue(k))
        }
    }

    /** The win rate over every hand of [pool] in [stratum] under [theta]. */
    fun all(theta: DoubleArray, stratum: Stratum, pool: Pool): Rate = sorted(theta, stratum, pool) { _, _ -> emptyList() }.getValue(ALL)

    companion object {
        /** The key every hand is counted under: the pool's whole rate. */
        const val ALL = ""

        private const val POINTS = 100.0

        /**
         * A difference of two rates read under one fit, [a] less [b], with its range: the fit's doubt by the two gradients'
         * difference (what both share cancels), and the pools' doubts added — read apart, or [nested] when [b]'s hands hold
         * [a]'s (a pattern against its stratum).
         */
        fun difference(a: HandRates.Rate, b: HandRates.Rate, fit: Fit, nested: Boolean = false): Estimate {
            val ga = a.meanGradient()
            val gb = b.meanGradient()
            val g = DoubleArray(ga.size) { ga[it] - gb[it] }
            val sampling = if (!nested) a.sampling + b.sampling else {
                // b = share·a + (1 − share)·rest, so a − b = (1 − share)(a − rest), the two read apart.
                val share = if (b.hands == 0) 0.0 else a.hands.toDouble() / b.hands
                val rest = b.minus(a)
                (1 - share) * (1 - share) * (a.sampling + rest.sampling)
            }
            return Estimate(a.value - b.value, sqrt((fit.covariance.quadratic(g) + sampling).coerceAtLeast(0.0)))
        }
    }
}
