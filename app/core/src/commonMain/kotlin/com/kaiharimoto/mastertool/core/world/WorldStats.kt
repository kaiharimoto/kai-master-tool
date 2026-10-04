package com.kaiharimoto.mastertool.core.world

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The statistics a world's scripts lean on, written once here and tested, so a number Ai shows is the same number in
 * JavaScript, in Python's helper and in the app: summaries, a histogram, Wilson's interval for a rate, binomial and
 * normal tails, and Pearson's chi-square with its p-value (the regularised incomplete gamma).
 */
object WorldStats {
    fun mean(xs: List<Double>): Double = if (xs.isEmpty()) Double.NaN else xs.sum() / xs.size

    /** The sample variance (n − 1). */
    fun variance(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return xs.sumOf { (it - m) * (it - m) } / (xs.size - 1)
    }

    fun sd(xs: List<Double>): Double = sqrt(variance(xs))

    /** The [q] quantile, 0–1, interpolated between the order statistics (type 7, R's default). */
    fun quantile(xs: List<Double>, q: Double): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        val h = (s.size - 1) * q.coerceIn(0.0, 1.0)
        val lo = floor(h).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (h - lo) * (s[hi] - s[lo])
    }

    fun median(xs: List<Double>): Double = quantile(xs, 0.5)

    /** [bins] equal bins from the least value to the greatest: their left edges and counts, the last bin closed. */
    fun histogram(xs: List<Double>, bins: Int): Pair<List<Double>, List<Int>> {
        val n = bins.coerceIn(1, 200)
        if (xs.isEmpty()) return List(n) { it.toDouble() } to List(n) { 0 }
        val lo = xs.min()
        val hi = xs.max()
        val width = if (hi > lo) (hi - lo) / n else 1.0
        val counts = IntArray(n)
        xs.forEach { x -> counts[((x - lo) / width).toInt().coerceIn(0, n - 1)]++ }
        return List(n) { lo + it * width } to counts.toList()
    }

    /** Wilson's score interval for [k] of [n], at [z] (1.96 for 95 %). */
    fun wilson(k: Int, n: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n <= 0) return 0.0 to 1.0
        val p = k.toDouble() / n
        val z2 = z * z
        val centre = (p + z2 / (2 * n)) / (1 + z2 / n)
        val half = z * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n)
        return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
    }

    fun correlation(xs: List<Double>, ys: List<Double>): Double {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return Double.NaN
        val mx = mean(xs.take(n))
        val my = mean(ys.take(n))
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx
            val dy = ys[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        return if (sxx == 0.0 || syy == 0.0) Double.NaN else sxy / sqrt(sxx * syy)
    }

    /** P(X = k) for X ~ Binomial(n, p). */
    fun binomPmf(n: Int, k: Int, p: Double): Double {
        if (k < 0 || k > n) return 0.0
        if (p <= 0.0) return if (k == 0) 1.0 else 0.0
        if (p >= 1.0) return if (k == n) 1.0 else 0.0
        return exp(lnChoose(n, k) + k * ln(p) + (n - k) * ln(1 - p))
    }

    /** P(X ≤ k) for X ~ Binomial(n, p). */
    fun binomCdf(n: Int, k: Int, p: Double): Double = (0..minOf(k, n)).sumOf { binomPmf(n, it, p) }.coerceIn(0.0, 1.0)

    /** The standard normal's P(Z ≤ z), by Abramowitz–Stegun 7.1.26 (error under 1.5 × 10⁻⁷). */
    fun normalCdf(z: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(z) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            exp(-z * z / 2.0)
        return if (z >= 0) 0.5 * (1.0 + y) else 0.5 * (1.0 - y)
    }

    data class ChiSquare(val statistic: Double, val df: Int, val p: Double)

    /** Pearson's goodness of fit of [observed] to [expected] counts (expected scaled to the same total). */
    fun chiSquare(observed: List<Double>, expected: List<Double>): ChiSquare {
        require(observed.size == expected.size && observed.size >= 2) { "chi-square needs two or more matching counts" }
        val total = observed.sum()
        val etotal = expected.sum()
        require(etotal > 0) { "expected counts must not all be zero" }
        val scaled = expected.map { it * total / etotal }
        val stat = observed.indices.sumOf { i -> if (scaled[i] > 0) (observed[i] - scaled[i]).let { it * it } / scaled[i] else 0.0 }
        val df = observed.size - 1
        return ChiSquare(stat, df, 1.0 - gammaP(df / 2.0, stat / 2.0))
    }

    /** ln C(n, k). */
    fun lnChoose(n: Int, k: Int): Double = lnGamma(n + 1.0) - lnGamma(k + 1.0) - lnGamma(n - k + 1.0)

    /** ln Γ(x), Lanczos (g = 7, n = 9). */
    fun lnGamma(x: Double): Double {
        if (x < 0.5) return ln(kotlin.math.PI / kotlin.math.sin(kotlin.math.PI * x)) - lnGamma(1 - x)
        val g = 7.0
        val c = doubleArrayOf(
            0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059,
            12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
        )
        val xx = x - 1
        var a = c[0]
        val t = xx + g + 0.5
        for (i in 1 until 9) a += c[i] / (xx + i)
        return 0.5 * ln(2 * kotlin.math.PI) + (xx + 0.5) * ln(t) - t + ln(a)
    }

    /** The regularised lower incomplete gamma P(a, x): a series below a + 1, a continued fraction above. */
    fun gammaP(a: Double, x: Double): Double {
        if (x <= 0.0) return 0.0
        return if (x < a + 1) {
            var sum = 1.0 / a
            var term = sum
            var n = a
            var i = 0
            while (i++ < 500 && abs(term) >= abs(sum) * 1e-15) {
                n += 1
                term *= x / n
                sum += term
            }
            (sum * exp(-x + a * ln(x) - lnGamma(a))).coerceIn(0.0, 1.0)
        } else {
            1.0 - gammaQcf(a, x)
        }
    }

    private fun gammaQcf(a: Double, x: Double): Double {
        val tiny = 1e-300
        var b = x + 1 - a
        var c = 1 / tiny
        var d = 1 / b
        var h = d
        for (i in 1..500) {
            val an = -i * (i - a)
            b += 2
            d = an * d + b
            if (abs(d) < tiny) d = tiny
            c = b + an / c
            if (abs(c) < tiny) c = tiny
            d = 1 / d
            val del = d * c
            h *= del
            if (abs(del - 1) < 1e-15) break
        }
        return (exp(-x + a * ln(x) - lnGamma(a)) * h).coerceIn(0.0, 1.0)
    }
}
