package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Matrix

/**
 * The priors of [ModelSpec.priors] as one Gaussian over the whole parameter vector: log p(θ) = −½ θᵀPθ + bᵀθ.
 *
 * Every prior is a statement "this combination of parameters is near that number, give or take so much": a card
 * near its role's average is (card − role) ≈ 0; a pair held at zero is pair ≈ 0. Writing them all that way makes
 * the hierarchy (role → card → stratum) one matrix, and its curvature is simply −P.
 */
class Prior(spec: ModelSpec) {

    private val n = spec.layout.size

    /** P: how sharply the priors hold each combination. */
    val precision = Matrix(n)

    /** b: where they hold it. */
    val shift = DoubleArray(n)

    /** A sensible place to start the fit: each parameter at its prior's middle. */
    val start = DoubleArray(n)

    init {
        val l = spec.layout
        val p = spec.priors
        for (r in 0 until spec.roleCount) {
            val mean = p.roleMeans.getOrElse(r) { 0.0 }
            hold(l.role(r), mean, p.roleSd)
            start[l.role(r)] = mean
        }
        for (c in 0 until spec.cards) {
            holdDifference(l.card(c), l.role(spec.roles[c]), p.cardSd)
            start[l.card(c)] = start[l.role(spec.roles[c])]
            for (s in 0 until l.deviationsPerCard) hold(l.deviation(c, s), 0.0, p.deviationSd)
            if (l.drawnPerCard > 0) {
                // Held near its role's average as the draw, its own number: nothing learned of the five moves it.
                holdDifference(l.drawn(c), l.drawnRole(spec.roles[c]), p.cardSd)
                start[l.drawn(c)] = p.roleMeans.getOrElse(spec.roles[c]) { 0.0 }
            }
        }
        if (l.drawnPerCard > 0) for (r in 0 until spec.roleCount) {
            val mean = p.roleMeans.getOrElse(r) { 0.0 }
            hold(l.drawnRole(r), mean, p.roleSd)
            start[l.drawnRole(r)] = mean
        }
        for (i in spec.pairs.indices) hold(l.pair(i), 0.0, p.pairSd)
        if (spec.opponentCards > 0) {
            hold(l.opponentMean, 0.0, p.roleSd)
            for (o in 0 until spec.opponentCards) holdDifference(l.opponent(o), l.opponentMean, p.opponentSd)
        }
        for (s in spec.strata.indices) hold(l.intercept(s), 0.0, p.interceptSd)
        for (j in 0 until spec.judges) {
            if (j > 0) for (k in 0 until 4) hold(l.cut(j, k), 0.0, p.cutSd)
            if (j > 0) for (c in 0 until spec.cards) hold(l.judgeCard(j, c), 0.0, p.judgeCardSd)
            hold(l.precision(j), p.precisionMean(j), p.precisionLogSd)
            hold(l.comparePrecision(j), p.comparePrecisionMean(j), p.comparePrecisionLogSd)
            start[l.precision(j)] = p.precisionMean(j)
            start[l.comparePrecision(j)] = p.comparePrecisionMean(j)
        }
    }

    /** log p(θ), up to a constant. */
    fun logDensity(theta: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until n) s += shift[i] * theta[i]
        return s - 0.5 * precision.quadratic(theta)
    }

    /** Adds the prior's gradient (b − Pθ) into [gradient]. */
    fun addGradient(theta: DoubleArray, gradient: DoubleArray) {
        val pt = precision.times(theta)
        for (i in 0 until n) gradient[i] += shift[i] - pt[i]
    }

    /** θᵢ ≈ mean ± sd. */
    private fun hold(i: Int, mean: Double, sd: Double) {
        val w = 1.0 / (sd * sd)
        precision.add(i, i, w)
        shift[i] += w * mean
    }

    /** θᵢ − θⱼ ≈ 0 ± sd. */
    private fun holdDifference(i: Int, j: Int, sd: Double) {
        val w = 1.0 / (sd * sd)
        precision.add(i, i, w)
        precision.add(j, j, w)
        precision.add(i, j, -w)
        precision.add(j, i, -w)
    }
}
