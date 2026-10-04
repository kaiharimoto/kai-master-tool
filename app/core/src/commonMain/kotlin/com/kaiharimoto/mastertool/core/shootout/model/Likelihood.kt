package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import kotlin.math.exp
import kotlin.math.ln

/**
 * How likely one answer is, and how that changes with the few numbers it reads (Phase S §2). Each function fills
 * the first and second derivatives over its own handful of local variables; [Posterior] spreads them over the
 * parameters. Exact derivatives, not differences, because Newton's method and the Laplace ranges are only as good
 * as the curvature.
 */
internal object Likelihood {

    /**
     * A five-point answer: the judge reads the hand's value [eta] with logistic noise of precision e^[logPrecision]
     * and answers the band it falls in. [answer] is 0 (clear loss) to 4 (clear win); [lower] and [upper] are the
     * band's cut-offs (∓∞ at the ends, which have none).
     *
     * Local variables, in order: the value, the lower cut-off, the upper cut-off, the log precision.
     * Returns ln P(answer), or −∞ when the cut-offs are out of order.
     */
    fun ordinal(
        eta: Double, lower: Double, upper: Double, logPrecision: Double,
        d: DoubleArray, h: DoubleArray,
    ): Double {
        d.fill(0.0); h.fill(0.0)
        val lam = exp(logPrecision)
        val hasUpper = upper != Double.POSITIVE_INFINITY
        val hasLower = lower != Double.NEGATIVE_INFINITY
        if (hasUpper && hasLower && upper <= lower) return Double.NEGATIVE_INFINITY
        val a = if (hasUpper) lam * (upper - eta) else Double.POSITIVE_INFINITY
        val b = if (hasLower) lam * (lower - eta) else Double.NEGATIVE_INFINITY

        // The band's probability, from whichever tail keeps its digits.
        val logL = when {
            !hasUpper -> Logistic.logOf(-b)
            !hasLower -> Logistic.logOf(a)
            b >= 0 -> ln((Logistic.of(-b) - Logistic.of(-a)).coerceAtLeast(MIN_P))
            else -> ln((Logistic.of(a) - Logistic.of(b)).coerceAtLeast(MIN_P))
        }
        val l = exp(logL)

        // ∂a and ∂b over (value, lower, upper, log precision), and their second derivatives.
        val fa = if (hasUpper) Logistic.slope(a) else 0.0
        val fb = if (hasLower) Logistic.slope(b) else 0.0
        val fpa = if (hasUpper) fa * (Logistic.of(-a) - Logistic.of(a)) else 0.0
        val fpb = if (hasLower) fb * (Logistic.of(-b) - Logistic.of(b)) else 0.0
        val da = doubleArrayOf(-lam, 0.0, lam, if (hasUpper) a else 0.0)
        val db = doubleArrayOf(-lam, lam, 0.0, if (hasLower) b else 0.0)
        for (v in 0 until 4) d[v] = (fa * da[v] - fb * db[v]) / l
        for (v in 0 until 4) for (w in 0 until 4) {
            val daa = second(v, w, lam, if (hasUpper) a else 0.0, upperSide = true)
            val dbb = second(v, w, lam, if (hasLower) b else 0.0, upperSide = false)
            h[v * 4 + w] = (fpa * da[v] * da[w] + fa * daa - fpb * db[v] * db[w] - fb * dbb) / l - d[v] * d[w]
        }
        return logL
    }

    /** ∂²(λ(cut − η)) over two local variables: only the log precision bends it. */
    private fun second(v: Int, w: Int, lam: Double, x: Double, upperSide: Boolean): Double {
        val cut = if (upperSide) 2 else 1
        return when {
            v == 3 && w == 3 -> x
            (v == 0 && w == 3) || (v == 3 && w == 0) -> -lam
            (v == cut && w == 3) || (v == 3 && w == cut) -> lam
            else -> 0.0
        }
    }

    /**
     * A comparison: the left hand is preferred with chance logistic(e^[logPrecision] × [difference]), the
     * difference being left's value less right's. Local variables: the difference, the log precision.
     */
    fun compared(difference: Double, leftPreferred: Boolean, logPrecision: Double, d: DoubleArray, h: DoubleArray): Double {
        val lam = exp(logPrecision)
        val y = if (leftPreferred) 1.0 else -1.0
        val z = lam * difference
        val lz = y * Logistic.of(-y * z)
        val lzz = -Logistic.slope(z)
        val zv = doubleArrayOf(lam, z)
        val zvw = doubleArrayOf(0.0, lam, lam, z)
        for (v in 0 until 2) d[v] = lz * zv[v]
        for (v in 0 until 2) for (w in 0 until 2) h[v * 2 + w] = lzz * zv[v] * zv[w] + lz * zvw[v * 2 + w]
        return Logistic.logOf(y * z)
    }

    /**
     * An answer that may also be a slip: P' = (1 − [lapse]) P + [lapse] × [chance], where [chance] is a random
     * key's (a fifth, or a half for a comparison). Rewrites the [m] local derivatives [d] and [h] of ln P into
     * those of ln P' and returns ln P'.
     */
    fun withLapse(logL: Double, chance: Double, lapse: Double, d: DoubleArray, h: DoubleArray, m: Int): Double {
        if (lapse == 0.0 || logL == Double.NEGATIVE_INFINITY) return logL
        val l = exp(logL)
        val mixed = (1 - lapse) * l + lapse * chance
        val r = (1 - lapse) * l / mixed
        for (v in 0 until m) for (w in 0 until m) {
            val dd = d[v] * d[w]
            h[v * m + w] = r * (h[v * m + w] + dd) - r * r * dd
        }
        for (v in 0 until m) d[v] *= r
        return ln(mixed)
    }

    /** The smallest band probability kept, so an answer the model thinks impossible costs a lot, not infinity. */
    private const val MIN_P = 1e-300

    /**
     * The chance of each of the five answers for a hand of value [eta]: what the model expects, for skipping a
     * hand already known and for the real-world rate's check.
     */
    fun answerChances(eta: Double, cuts: DoubleArray, logPrecision: Double): DoubleArray {
        val lam = exp(logPrecision)
        val out = DoubleArray(5)
        var below = 0.0
        for (k in 0 until 4) {
            val at = Logistic.of(lam * (cuts[k] - eta))
            out[k] = (at - below).coerceAtLeast(0.0)
            below = maxOf(below, at)
        }
        out[4] = (1.0 - below).coerceAtLeast(0.0)
        return out
    }

    /** Fisher's information about a hand's value in one five-point answer: how much one such trial teaches. */
    fun ordinalInformation(eta: Double, cuts: DoubleArray, logPrecision: Double): Double {
        val lam = exp(logPrecision)
        var info = 0.0
        var prevF = 0.0
        var prevSlope = 0.0
        for (k in 0..4) {
            val f = if (k < 4) Logistic.of(lam * (cuts[k] - eta)) else 1.0
            val slope = if (k < 4) lam * Logistic.slope(lam * (cuts[k] - eta)) else 0.0
            val p = f - prevF
            val dp = -(slope - prevSlope)
            if (p > 1e-12) info += dp * dp / p
            prevF = f; prevSlope = slope
        }
        return info
    }

    /** Fisher's information about a difference of values in one comparison. */
    fun compareInformation(difference: Double, logPrecision: Double): Double {
        val lam = exp(logPrecision)
        return lam * lam * Logistic.slope(lam * difference)
    }
}
