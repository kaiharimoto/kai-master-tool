package com.kaiharimoto.mastertool.core.shootout.sim

import com.kaiharimoto.mastertool.core.shootout.math.Normal
import com.kaiharimoto.mastertool.core.shootout.model.Contrast
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Target
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Picker
import com.kaiharimoto.mastertool.core.shootout.select.PickerSettings
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.RealWorld
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One simulated session (Phase S §4): a picker, a [SimJudge] and the fit after every answer, with the error
 * against the truth read at checkpoints. The same harness runs the adaptive picker and the plain-shuffle baseline,
 * so the two differ only in how hands are chosen.
 */
object Study {

    /** How far the reported numbers were from the truth after [trials] answers. */
    data class Checkpoint(
        val trials: Int,
        /** Root mean square error of the cards' reported worth, in points, weighted by how often each is drawn. */
        val cardError: Double,
        /** The share of cards (by stratum) whose 80 % range held the truth. */
        val covered80: Double,
        /** The share of cards whose 95 % range held the truth. */
        val covered95: Double,
        /** The judge's fitted noise on the five-point scale (1 / precision), in log-odds. */
        val noise: Double,
    )

    /** A finished session: its checkpoints, its trials, its last fit and every reported number against the truth. */
    class Run(
        val checkpoints: List<Checkpoint>,
        val trials: List<Trial>,
        val fit: Fit,
        val estimated: List<Contrast>,
        val truth: List<Contrast>,
        val reporter: Reporter,
        val proposals: List<Proposal>,
    ) {
        /** The first checkpoint at which the card error reached [target], or null if it never did. */
        fun trialsTo(target: Double): Int? = checkpoints.firstOrNull { it.cardError <= target }?.trials

        /** Each pair's call at the end: whether it is real, and whether its 95 % range excluded zero (it was shown). */
        fun pairCalls(realPairs: Set<Int>): List<PairCall> = estimated.indices.mapNotNull { i ->
            val t = estimated[i].target as? Target.Pair ?: return@mapNotNull null
            val e = estimated[i]
            PairCall(t.pair in realPairs, abs(e.value) > Normal.Z95 * e.sd(fit))
        }

        /**
         * Each stratum's win rate at the end, in points: the truth (over real hands), the model's estimate and its
         * standard deviation, the naive average of the answers to the hands shown, and the plain hands' check
         * ([RealWorld]'s correction, which should sit near zero when the model is right).
         */
        fun winRates(): List<WinRateCall> {
            val checks = RealWorld.check(fit.spec, fit, reporter, trials)
            return estimated.indices.mapNotNull { i ->
                val t = estimated[i].target as? Target.WinRate ?: return@mapNotNull null
                val shown = trials.filterIsInstance<Rated>().filter { it.stratum == t.stratum && it.judge == 0 }
                WinRateCall(
                    truth = truth[i].value,
                    model = estimated[i].value,
                    sd = estimated[i].sd(fit),
                    naive = 100 * shown.map { it.answer.score }.average(),
                    residual = checks.first { it.stratum == t.stratum }.residual,
                )
            }
        }
    }

    /** One pair's call against the truth. */
    data class PairCall(val real: Boolean, val shown: Boolean)

    /** One stratum's win rate against the truth, in points. */
    data class WinRateCall(val truth: Double, val model: Double, val sd: Double, val naive: Double, val residual: Double)

    /** The plain-shuffle baseline: every trial a random opening hand, as the legacy shootout dealt them. */
    val PLAIN = PickerSettings(plainShare = 1.0, repeatShare = 0.0, compareShare = 0.0, compareCandidates = 0)

    /**
     * Runs [trials] answers on [world] with [settings], checking every [checkEvery]. [seed] fixes the judge, the
     * picker and the reporting pool, so a run is the same every time.
     */
    fun run(
        world: SyntheticDeck,
        settings: PickerSettings,
        trials: Int,
        seed: Long,
        checkEvery: Int = 20,
        judge: SimJudge = SimJudge(world, seed * 101 + 3),
        poolSize: Int = 300,
    ): Run {
        val spec = world.spec
        val reporter = Reporter(spec, world.decks, poolSize, seed * 7 + 1)
        val picker = Picker(spec, world.decks, settings, seed * 17 + 5)
        val truth = reporter.contrasts(world.truth)
        val shares = spec.strata.associateWith { reporter.drawShare(it) }
        val log = ArrayList<Trial>(trials)
        val proposals = ArrayList<Proposal>(trials)
        val checkpoints = ArrayList<Checkpoint>()
        var fit = Fitter.fit(spec, log)
        for (t in 0 until trials) {
            val proposal = picker.next(log, fit)
            proposals += proposal
            log += judge.answer(proposal, t)
            fit = Fitter.fit(spec, log, fit.theta)
            if ((t + 1) % checkEvery == 0 || t + 1 == trials) {
                checkpoints += checkpoint(t + 1, fit, reporter.contrasts(fit.theta), truth, shares)
            }
        }
        return Run(checkpoints, log, fit, reporter.contrasts(fit.theta), truth, reporter, proposals)
    }

    private fun checkpoint(
        n: Int,
        fit: Fit,
        estimated: List<Contrast>,
        truth: List<Contrast>,
        shares: Map<Stratum, DoubleArray>,
    ): Checkpoint {
        var se = 0.0; var w = 0.0; var in80 = 0; var in95 = 0; var cards = 0
        for (i in estimated.indices) {
            val target = estimated[i].target as? Target.Card ?: continue
            val share = shares.getValue(target.stratum)[target.card]
            if (share <= 0) continue
            val err = estimated[i].value - truth[i].value
            se += share * err * err
            w += share
            val sd = estimated[i].sd(fit)
            if (abs(err) <= Normal.Z80 * sd) in80++
            if (abs(err) <= Normal.Z95 * sd) in95++
            cards++
        }
        val noise = exp(-fit.theta[fit.spec.layout.precision(0)])
        return Checkpoint(n, sqrt(se / w), in80.toDouble() / cards, in95.toDouble() / cards, noise)
    }
}
