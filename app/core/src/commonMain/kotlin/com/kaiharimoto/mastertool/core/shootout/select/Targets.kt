package com.kaiharimoto.mastertool.core.shootout.select

import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Sparse
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Target

/**
 * What the picker is trying to learn at one moment (Phase S §3): every reported number of the [allowed] strata at
 * the current fit, each weighted by how often it is met in real opening hands, ready to score candidate trials.
 *
 * A trial is scored by the linearised reduction in those numbers' variances. With Σ the Laplace covariance, x the
 * trial's features, I its expected Fisher information and gⱼ a number's gradient, one more answer leaves Var(rⱼ)
 * smaller by I (gⱼᵀΣx)² / (1 + I xᵀΣx). Σgⱼ is worked out once here, so a candidate costs only its own features.
 */
internal class Targets(private val fit: Fit, reporter: Reporter, settings: PickerSettings, allowed: List<Stratum>) {

    private val weights: DoubleArray
    private val sigmaG: Array<DoubleArray>

    init {
        val contrasts = allowed.flatMap { reporter.contrasts(fit.theta, it) }
        val shares = allowed.associateWith { reporter.drawShare(it) }
        val drawnShares = allowed.associateWith { reporter.drawnShare(it) }
        weights = DoubleArray(contrasts.size) { i ->
            when (val t = contrasts[i].target) {
                is Target.Card -> shares.getValue(t.stratum)[t.card]
                is Target.Drawn -> settings.drawnWeight * drawnShares.getValue(t.stratum)[t.card]
                is Target.Pair -> settings.pairWeight * reporter.bothShare(t.stratum, t.pair)
                is Target.WinRate -> settings.winRateWeight
                // The next copy is read, never chased: the picker's targets are the deck's own numbers.
                is Target.Next, is Target.Variant -> 0.0
            }
        }
        sigmaG = Array(contrasts.size) { i ->
            if (weights[i] > 0) fit.covariance.times(contrasts[i].gradient) else DoubleArray(0)
        }
    }

    /** The weighted reduction in the numbers' variances one trial with [x] and [information] would bring. */
    fun score(x: Sparse, information: Double): Double? {
        if (information <= 0.0) return null
        val v = fit.covariance.sparseQuadratic(x.index, x.value)
        var sum = 0.0
        for (j in weights.indices) {
            val w = weights[j]
            if (w <= 0.0) continue
            val u = sigmaG[j]
            var dot = 0.0
            for (a in x.index.indices) dot += u[x.index[a]] * x.value[a]
            sum += w * dot * dot
        }
        return information * sum / (1.0 + information * v)
    }
}
