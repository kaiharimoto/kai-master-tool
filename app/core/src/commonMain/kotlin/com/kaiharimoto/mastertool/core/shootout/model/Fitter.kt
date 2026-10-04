package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Cholesky
import com.kaiharimoto.mastertool.core.shootout.math.Matrix
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The ratings read from the trials so far (Phase S §2): the most likely parameters given the priors and the
 * judges' fitted noise ([theta]), and the Laplace approximation's [covariance] around them (the inverse of the
 * negative curvature there). The noise parameters themselves carry no range here (their rows are zero).
 */
class Fit(
    val spec: ModelSpec,
    val theta: DoubleArray,
    val covariance: Matrix,
    val logDensity: Double,
    val iterations: Int,
    val converged: Boolean,
    /** How many trials it was read from. */
    val trials: Int,
    /**
     * The standard deviation of each noise parameter (a log precision) from the curvature of its own fit, 0
     * elsewhere: how sure the fit is of each judge's noise, which the picker uses to expect a trial's teaching.
     */
    val noiseSd: DoubleArray = DoubleArray(theta.size),
)

/**
 * Newton's method to the mode, then Laplace (Phase S §2), with the judges' noise fitted beside it.
 *
 * About a hundred parameters fit in milliseconds, so the model is refitted after every answer, starting from the
 * last fit, which is usually two or three steps away.
 *
 * **The noise is not fitted at the joint mode.** Early in a session there are fewer answers than parameters, and the
 * joint mode explains every answer exactly and calls the judge noiseless; the ranges then shrink to nothing and the
 * picker trusts each answer far too much (the simulation found the fitted noise at a tenth of the truth after 80
 * trials, and the 80 % ranges holding the truth half the time). Instead the two are alternated, as in
 * variational EM: the values by Newton with the noise held; then each judge's noise by maximising its answers'
 * likelihood *averaged over what the fit still does not know about each hand's value* (plus its prior). That
 * average is what charges an over-confident fit for its own uncertainty.
 */
object Fitter {

    /**
     * Fits [spec] to [trials], from [start] (the last fit's mode) or the prior's middle. [noiseRounds] bounds how
     * many times the noise and the values are alternated; one or two suffice from a warm start.
     */
    fun fit(
        spec: ModelSpec,
        trials: List<Trial>,
        start: DoubleArray? = null,
        maxIterations: Int = 60,
        noiseRounds: Int = 4,
    ): Fit {
        val posterior = Posterior(spec, trials)
        val l = spec.layout
        val held = IntArray(2 * spec.judges) { if (it % 2 == 0) l.precision(it / 2) else l.comparePrecision(it / 2) }
        val first = start?.copyOf()?.takeIf { it.size == l.size && posterior.logDensity(it).isFinite() } ?: posterior.start
        var mode = newton(posterior, first, held, maxIterations)
        var covariance = laplace(mode.eval.negHessian, held)
        var iterations = mode.iterations
        val noiseSd = DoubleArray(l.size)
        for (i in held) noiseSd[i] = noisePrior(spec, i).second
        var rounds = 0
        while (rounds++ < noiseRounds) {
            val theta = mode.theta.copyOf()
            var moved = 0.0
            for (i in held) {
                if (posterior.count(i) == 0) continue
                val (mean, sd) = noisePrior(spec, i)
                val expected = posterior.expectedLogLikelihood(theta, covariance, i)
                val objective = { s: Double -> expected(s) - 0.5 * ((s - mean) / sd).let { it * it } }
                val best = maximise(mean - 4 * sd, mean + 4 * sd, objective)
                val bend = (objective(best + 0.01) - 2 * objective(best) + objective(best - 0.01)) / 1e-4
                noiseSd[i] = if (bend < 0) minOf(sd, 1 / sqrt(-bend)) else sd
                moved = maxOf(moved, abs(best - theta[i]))
                theta[i] = best
            }
            // A noise that moved less than this is not worth refitting the values for; the fit keeps the last mode.
            if (moved < 0.01) break
            mode = newton(posterior, theta, held, maxIterations)
            covariance = laplace(mode.eval.negHessian, held)
            iterations += mode.iterations
        }
        return Fit(spec, mode.theta, covariance, mode.eval.logDensity, iterations, mode.converged, trials.size, noiseSd)
    }

    private class Mode(val theta: DoubleArray, val eval: Posterior.Eval, val iterations: Int, val converged: Boolean)

    /**
     * Newton's method with the [held] parameters fixed. A step that does not raise the log posterior is halved
     * until it does, which keeps the cut-offs in order; a curvature that is not quite positive gets a diagonal
     * jitter, turning the step toward plain gradient ascent rather than away from the mode.
     */
    private fun newton(posterior: Posterior, start: DoubleArray, held: IntArray, maxIterations: Int): Mode {
        var theta = start
        var eval = posterior.evaluate(theta)
        var iterations = 0
        var converged = false
        while (iterations < maxIterations) {
            iterations++
            hold(eval, held)
            val step = Cholesky.robust(eval.negHessian).solve(eval.gradient)
            var t = 1.0
            var next = DoubleArray(theta.size) { theta[it] + step[it] }
            var lp = posterior.logDensity(next)
            while (!(lp >= eval.logDensity - 1e-10) && t > 1e-6) {
                t *= 0.5
                next = DoubleArray(theta.size) { theta[it] + t * step[it] }
                lp = posterior.logDensity(next)
            }
            if (!(lp >= eval.logDensity - 1e-10)) break
            val moved = step.maxOf { abs(it) } * t
            theta = next
            eval = posterior.evaluate(theta)
            if (moved < 1e-7 || (moved < 1e-4 && eval.gradient.indices.all { it in held || abs(eval.gradient[it]) < 1e-6 })) {
                converged = true
                break
            }
        }
        hold(eval, held)
        return Mode(theta, eval, iterations, converged)
    }

    /** Takes the [held] parameters out of a step: no gradient, and a unit curvature with no ties to the rest. */
    private fun hold(eval: Posterior.Eval, held: IntArray) {
        val m = eval.negHessian
        for (i in held) {
            eval.gradient[i] = 0.0
            for (k in 0 until m.n) { m[i, k] = 0.0; m[k, i] = 0.0 }
            m[i, i] = 1.0
        }
    }

    /** The Laplace covariance, with the held parameters' rows zeroed: they are fitted, not ranged. */
    private fun laplace(negHessian: Matrix, held: IntArray): Matrix {
        val cov = Cholesky.robust(negHessian).inverse()
        for (i in held) cov[i, i] = 0.0
        return cov
    }

    /** The prior middle and spread of the noise parameter at [index]. */
    private fun noisePrior(spec: ModelSpec, index: Int): Pair<Double, Double> {
        val p = spec.priors
        val isCompare = (0 until spec.judges).any { spec.layout.comparePrecision(it) == index }
        return if (isCompare) p.comparePrecisionLog to p.comparePrecisionLogSd else p.precisionLog to p.precisionLogSd
    }

    /** The maximum of a function that rises then falls on [lo, hi], by golden-section search to a thousandth. */
    private fun maximise(lo: Double, hi: Double, f: (Double) -> Double): Double {
        val g = 0.6180339887498949
        var a = lo; var b = hi
        var c = b - g * (b - a); var d = a + g * (b - a)
        var fc = f(c); var fd = f(d)
        while (b - a > 1e-3) {
            if (fc >= fd) { b = d; d = c; fd = fc; c = b - g * (b - a); fc = f(c) }
            else { a = c; c = d; fc = fd; d = a + g * (b - a); fd = f(d) }
        }
        return (a + b) / 2
    }
}
