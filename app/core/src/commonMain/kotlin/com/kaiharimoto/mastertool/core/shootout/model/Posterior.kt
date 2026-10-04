package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Matrix
import kotlin.math.sqrt

/**
 * The log posterior of [ModelSpec]'s parameters given [trials]: the priors plus every answer's log likelihood
 * (Phase S §2). Its gradient and curvature are what Newton's method climbs and the Laplace ranges read.
 *
 * Each trial's features are worked out once here; they do not depend on the parameters.
 */
class Posterior(val spec: ModelSpec, trials: List<Trial>) {

    private val layout = spec.layout
    private val prior = Prior(spec)
    private val value = HandValue(spec)
    private val prepared: List<Prepared> = trials.map(::prepare)

    /** The prior's own starting point, for a fit with nothing better. */
    val start: DoubleArray get() = prior.start.copyOf()

    /** One evaluation: the log posterior, its gradient, and its negative curvature (−Hessian). */
    class Eval(val logDensity: Double, val gradient: DoubleArray, val negHessian: Matrix)

    /** log p(θ | trials), up to a constant; −∞ where the parameters are impossible (cut-offs out of order). */
    fun logDensity(theta: DoubleArray): Double {
        if (!cutsInOrder(theta)) return Double.NEGATIVE_INFINITY
        var lp = prior.logDensity(theta)
        val d = DoubleArray(4)
        val h = DoubleArray(16)
        for (t in prepared) {
            lp += t.logLikelihood(theta, d, h)
            if (lp == Double.NEGATIVE_INFINITY) return lp
        }
        return lp
    }

    /** The log posterior with its gradient and negative curvature at [theta]. */
    fun evaluate(theta: DoubleArray): Eval {
        val n = layout.size
        val gradient = DoubleArray(n)
        val neg = prior.precision.copy()
        prior.addGradient(theta, gradient)
        var lp = if (cutsInOrder(theta)) prior.logDensity(theta) else Double.NEGATIVE_INFINITY
        val d = DoubleArray(4)
        val h = DoubleArray(16)
        for (t in prepared) {
            lp += t.logLikelihood(theta, d, h)
            val vars = t.locals
            val m = vars.size
            for (v in 0 until m) {
                val dv = d[v]
                val (iv, cv) = vars[v]
                for (a in iv.indices) gradient[iv[a]] += dv * cv[a]
                for (w in 0 until m) {
                    val hvw = h[v * m + w]
                    if (hvw == 0.0) continue
                    val (iw, cw) = vars[w]
                    for (a in iv.indices) {
                        val row = iv[a]
                        val ca = hvw * cv[a]
                        for (b in iw.indices) neg.add(row, iw[b], -ca * cw[b])
                    }
                }
            }
        }
        return Eval(lp, gradient, neg)
    }

    /** Every judge's cut-offs must rise, or the five bands are not bands (the reference's are fixed in order). */
    private fun cutsInOrder(theta: DoubleArray): Boolean {
        for (j in 1 until spec.judges) {
            for (k in 1 until 4) if (cut(theta, j, k) <= cut(theta, j, k - 1)) return false
        }
        return true
    }

    private fun cut(theta: DoubleArray, judge: Int, k: Int): Double =
        ModelSpec.NOMINAL_CUTS[k] + if (judge == 0) 0.0 else theta[layout.cut(judge, k)]

    /** A judge's cut-off as a local variable: a parameter, or nothing for the reference judge's fixed ones. */
    private fun cutLocal(judge: Int, k: Int): Local = if (judge == 0) none else one(layout.cut(judge, k))

    /** A local variable: the parameters it is, and how much of each. */
    private class Local(val index: IntArray, val coef: DoubleArray) {
        operator fun component1() = index
        operator fun component2() = coef
    }

    /**
     * One trial, ready: the local variables it reads, its features [x] (the value, or the two values' difference),
     * and which precision it is read with. [at] is its log likelihood with the value and the precision given
     * explicitly, which is how the noise is fitted on the expected likelihood ([expectedLogLikelihood]).
     */
    private abstract class Prepared(val locals: Array<Local>, val x: Sparse, val precisionIndex: Int) {
        abstract fun at(theta: DoubleArray, eta: Double, logPrecision: Double, d: DoubleArray, h: DoubleArray): Double

        fun logLikelihood(theta: DoubleArray, d: DoubleArray, h: DoubleArray): Double =
            at(theta, x.dot(theta), theta[precisionIndex], d, h)
    }

    /**
     * The answers read with precision index [precisionIndex] (one judge's five-point answers, or their
     * comparisons), as a function of the log precision: their log likelihood averaged over what the fit still does not
     * know about each value: the value ~ N(its fitted value, xᵀ Σ x), by five-point Gauss–Hermite quadrature.
     *
     * Fitting the noise to this, rather than at the joint mode, is what stops a young session from explaining
     * every answer exactly with a hundred parameters and declaring the judge noiseless (S.md "Simulation results").
     */
    fun expectedLogLikelihood(theta: DoubleArray, covariance: Matrix, precisionIndex: Int): (Double) -> Double {
        val mine = prepared.filter { it.precisionIndex == precisionIndex }
        val means = DoubleArray(mine.size) { mine[it].x.dot(theta) }
        val sds = DoubleArray(mine.size) { sqrt(covariance.sparseQuadratic(mine[it].x.index, mine[it].x.value).coerceAtLeast(0.0)) }
        val d = DoubleArray(4)
        val h = DoubleArray(16)
        return { logPrecision ->
            var sum = 0.0
            for (i in mine.indices) for (q in HERMITE_NODES.indices) {
                sum += HERMITE_WEIGHTS[q] * mine[i].at(theta, means[i] + sds[i] * HERMITE_NODES[q], logPrecision, d, h)
            }
            sum
        }
    }

    /** How many trials are read with precision index [precisionIndex]. */
    fun count(precisionIndex: Int): Int = prepared.count { it.precisionIndex == precisionIndex }

    private companion object {
        /** A local variable that is one parameter. */
        fun one(i: Int) = Local(intArrayOf(i), doubleArrayOf(1.0))

        /** A local variable a trial does not have (the lowest band has no lower cut-off). */
        val none = Local(IntArray(0), DoubleArray(0))

        /** Five-point Gauss–Hermite quadrature for a standard normal: E f(Z) ≈ Σ wᵢ f(zᵢ). */
        val HERMITE_NODES = doubleArrayOf(-2.8569700138728056, -1.3556261799742657, 0.0, 1.3556261799742657, 2.8569700138728056)
        val HERMITE_WEIGHTS = doubleArrayOf(0.011257411327720691, 0.2220759220056126, 0.5333333333333333, 0.2220759220056126, 0.011257411327720691)
    }

    private fun prepare(trial: Trial): Prepared {
        val s = spec.stratumIndex(trial.stratum)
        require(s >= 0) { "trial in ${trial.stratum}, which this model does not hold" }
        require(trial.judge in 0 until spec.judges) { "judge ${trial.judge} is not one of this model's" }
        val j = trial.judge
        return when (trial) {
            is Rated -> {
                val x = value.features(s, trial.hand, trial.opponent)
                val k = trial.answer.ordinal
                val locals = arrayOf(
                    Local(x.index, x.value),
                    if (k > 0) cutLocal(j, k - 1) else none,
                    if (k < 4) cutLocal(j, k) else none,
                    one(layout.precision(j)),
                )
                object : Prepared(locals, x, layout.precision(j)) {
                    override fun at(theta: DoubleArray, eta: Double, logPrecision: Double, d: DoubleArray, h: DoubleArray): Double {
                        val lower = if (k > 0) cut(theta, j, k - 1) else Double.NEGATIVE_INFINITY
                        val upper = if (k < 4) cut(theta, j, k) else Double.POSITIVE_INFINITY
                        val ll = Likelihood.ordinal(eta, lower, upper, logPrecision, d, h)
                        return Likelihood.withLapse(ll, 0.2, spec.lapse, d, h, 4)
                    }
                }
            }
            is Compared -> {
                val x = value.features(s, trial.left, trial.opponent).minus(value.features(s, trial.right, trial.opponent))
                val locals = arrayOf(Local(x.index, x.value), one(layout.comparePrecision(j)))
                object : Prepared(locals, x, layout.comparePrecision(j)) {
                    override fun at(theta: DoubleArray, eta: Double, logPrecision: Double, d: DoubleArray, h: DoubleArray): Double =
                        Likelihood.withLapse(Likelihood.compared(eta, trial.leftPreferred, logPrecision, d, h), 0.5, spec.lapse, d, h, 2)
                }
            }
        }
    }
}
