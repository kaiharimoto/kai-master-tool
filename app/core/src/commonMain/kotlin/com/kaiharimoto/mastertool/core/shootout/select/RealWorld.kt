package com.kaiharimoto.mastertool.core.shootout.select

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.Likelihood
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import kotlin.math.sqrt

/**
 * The real-world rate, checked against reality (Phase S §3, "Keep it honest").
 *
 * The picker shows hands on purpose, mostly the close calls, so the plain average of the answers says more about
 * the picker than the deck. Two corrections:
 * - **The model's rate** ([Reporter]'s win rate): every hand dealt as a shuffle would, by its real odds, read
 *   through the fit. Unbiased if the model is right.
 * - **The check** (this): the model's expected answer over real hands, plus the mean of (answer − model's
 *   expectation) over the plain hands only. The plain hands are dealt by the real odds, so that correction is an
 *   honest sample whatever the picker did, and the sum is unbiased whether or not the model is right. When the
 *   correction is far from zero, the model is missing something real.
 *
 * Both are on the answers' own scale (each answer read as its band's middle, [Answer.score]), in points.
 */
object RealWorld {

    /** One stratum's check. [residual] is the correction, ± [residualSd]; [plainTrials] how many plain hands. */
    data class Check(
        val stratum: Stratum,
        val modelScore: Double,
        val residual: Double,
        val residualSd: Double,
        val plainTrials: Int,
    ) {
        /** The corrected rate, in points. */
        val corrected: Double get() = modelScore + residual
    }

    /** The check for every stratum of [reporter], from [trials] (only the plain ones correct it). */
    fun check(spec: ModelSpec, fit: Fit, reporter: Reporter, trials: List<Trial>): List<Check> {
        val value = HandValue(spec)
        val l = spec.layout
        val cuts = l.cuts(fit.theta, 0)
        val precision = fit.theta[l.precision(0)]
        fun expected(eta: Double): Double {
            val p = Likelihood.answerChances(eta, cuts, precision)
            var s = 0.0
            for (k in p.indices) s += ((1 - spec.lapse) * p[k] + spec.lapse / 5) * Answer.entries[k].score
            return 100 * s
        }
        return spec.strata.map { stratum ->
            val si = spec.stratumIndex(stratum)
            val pool = reporter.pools.getValue(stratum)
            var model = 0.0
            for (i in 0 until pool.size) model += expected(value.of(fit.theta, si, pool.hands[i], pool.opponents[i]))
            model /= pool.size
            val residuals = trials.filterIsInstance<Rated>()
                .filter { it.plain && it.stratum == stratum && it.judge == 0 }
                .map { 100 * it.answer.score - expected(value.of(fit.theta, si, it.hand, it.opponent)) }
            val n = residuals.size
            val mean = if (n == 0) 0.0 else residuals.average()
            val sd = if (n < 2) Double.NaN else sqrt(residuals.sumOf { (it - mean) * (it - mean) } / (n - 1) / n)
            Check(stratum, model, mean, sd, n)
        }
    }
}
