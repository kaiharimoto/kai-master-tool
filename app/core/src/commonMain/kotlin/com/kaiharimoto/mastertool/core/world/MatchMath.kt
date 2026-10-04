package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.prep.TestStats
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How sure a matchup is (1.0.97, the instruments' red team): a handful of games is not a rate. Each rate is read as a
 * Beta posterior from a Beta(2, 2) prior — four games' worth at 50 %, the prior [TestStats.expected] already uses — so
 * the point the instrument shows is the one Prep shows, and the interval is what the games can actually say.
 */
object MatchMath {
    /** Games' worth of 50 % the rates are pulled toward. */
    const val PRIOR_GAMES = 4

    /** The rate shrunk toward 50 %: (w + 2) / (n + 4). */
    fun shrunk(r: TestStats.Rate): Double = (r.wins + PRIOR_GAMES / 2.0) / (r.games + PRIOR_GAMES)

    data class Interval(val point: Double, val low: Double, val high: Double)

    /**
     * Best of three from going first and going second: the point from the shrunk rates ([TestStats.matchWin]), and a
     * [level] credible interval integrated over both rates' Beta posteriors on a [grid] × [grid] lattice — exact to the
     * grid, deterministic, no seed.
     */
    fun bestOfThree(first: TestStats.Rate, second: TestStats.Rate, level: Double = 0.95, grid: Int = 100): Interval {
        val wf = betaWeights(first, grid)
        val ws = betaWeights(second, grid)
        val values = ArrayList<Pair<Double, Double>>(grid * grid)
        for (i in 0 until grid) for (j in 0 until grid) {
            val x = (i + 0.5) / grid
            val y = (j + 0.5) / grid
            values += TestStats.matchWin(x, y) to wf[i] * ws[j]
        }
        values.sortBy { it.first }
        val tail = (1 - level) / 2
        var acc = 0.0
        var low = values.first().first
        var high = values.last().first
        var lowSet = false
        for ((v, w) in values) {
            acc += w
            if (!lowSet && acc >= tail) { low = v; lowSet = true }
            if (acc >= 1 - tail) { high = v; break }
        }
        return Interval(TestStats.matchWin(shrunk(first), shrunk(second)), low, high)
    }

    /** A Beta(w + 2, l + 2) density on the grid's midpoints, summing to 1. */
    private fun betaWeights(r: TestStats.Rate, grid: Int): DoubleArray {
        val a = r.wins + PRIOR_GAMES / 2.0
        val b = (r.games - r.wins) + PRIOR_GAMES / 2.0
        val logs = DoubleArray(grid) { i -> val x = (i + 0.5) / grid; (a - 1) * ln(x) + (b - 1) * ln(1 - x) }
        val top = logs.max()
        val w = DoubleArray(grid) { exp(logs[it] - top) }
        val sum = w.sum()
        return DoubleArray(grid) { w[it] / sum }
    }

    /**
     * The match win to expect against a field, with a [level] interval: [TestStats.expected] for the point (Prep's
     * number), and the interval from [draws] seeded draws of every opponent's two rates from their posteriors.
     * Simulated, because the field's sum has no closed form; the seed is part of the answer.
     */
    fun field(rows: List<TestStats.Row>, shares: Map<String, Int>, draws: Int = 4000, seed: Long = 1, level: Double = 0.95): Interval {
        val point = TestStats.expected(rows, shares)
        val weighed = shares.filterValues { it > 0 }
        val total = weighed.values.sum().toDouble()
        if (total == 0.0) return Interval(point, point, point)
        val byKey = rows.associateBy { it.opponent }
        val random = Random(seed)
        val xs = DoubleArray(draws) {
            weighed.entries.sumOf { (k, share) ->
                val row = byKey[k]
                fun draw(rate: TestStats.Rate?) = beta(random, (rate?.wins ?: 0) + 2.0, (rate?.let { it.games - it.wins } ?: 0) + 2.0)
                val f = draw(row?.first)
                val s = draw(row?.second)
                // Game 1 and the sided games at their own rates, as the point is (Phase B): a split with games drawn from
                // its own posterior, one without played at the turn's pooled draw — so a log with no splits reads as before.
                fun split(rate: TestStats.Rate?, pooled: Double) = if ((rate?.games ?: 0) > 0) draw(rate) else pooled
                share / total * TestStats.matchWin(split(row?.preFirst, f), split(row?.preSecond, s), split(row?.postFirst, f), split(row?.postSecond, s))
            }
        }
        xs.sort()
        val tail = (1 - level) / 2
        return Interval(point, xs[(tail * (draws - 1)).toInt()], xs[((1 - tail) * (draws - 1)).toInt()])
    }

    /** A Beta(a, b) draw, a and b at least 1: two Gamma draws (Marsaglia and Tsang). */
    fun beta(random: Random, a: Double, b: Double): Double {
        val x = gamma(random, a)
        val y = gamma(random, b)
        return x / (x + y)
    }

    private fun gamma(random: Random, shape: Double): Double {
        val d = shape - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = normal(random)
                v = 1 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = random.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun normal(random: Random): Double {
        val u = 1 - random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * kotlin.math.PI * v)
    }
}
