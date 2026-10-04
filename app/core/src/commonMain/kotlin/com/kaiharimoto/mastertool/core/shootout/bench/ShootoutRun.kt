package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.Likelihood
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Picker
import com.kaiharimoto.mastertool.core.shootout.select.PickerSettings
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.Prediction
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A Shootout under way (Phase S §3): the kept trials, the fit read from them, the picker choosing the next trial with
 * the simulation's tuned settings, and every answer added to the log and fitted in turn.
 *
 * Plain Kotlin with no clock and no thread of its own: the page runs [next], [answer] and [results] off the frame
 * thread (each is milliseconds, but a frame is never made to wait), and keeps the log on disk.
 *
 * [pinned] holds a session to one stratum ("let the picker choose" is null); a stratum the model cannot deal is
 * ignored rather than refused.
 */
class ShootoutRun(
    val bench: Bench,
    log: ShootoutLog,
    pinned: Stratum? = null,
    seed: Long = 7L,
    /** Whether Ai's answers are fitted (stage 3); false reads the ratings as they would be without them. */
    val withAi: Boolean = true,
) {

    /** The log, with every answer of this run added. */
    var log: ShootoutLog = log
        private set

    private val model = ArrayList<Trial>()

    /** The fit read from every trial the model reads. */
    var fit: Fit
        private set

    val pinned: Stratum? = pinned?.takeIf { it in bench.spec.strata }

    private val picker = Picker(bench.spec, bench.decks, PickerSettings(pinned = this.pinned), seed)

    init {
        log.trials.forEach { t -> model += bench.observations(t, withAi) }
        fit = Fitter.fit(bench.spec, model)
    }

    /**
     * How many answers the model reads: the person's blind ones, and — each as its own judge, weighed by its measured
     * noise and lean (stage 3) — Ai's and the person's after seeing Ai's.
     */
    val fitted: Int get() = model.size

    /** The next trial to show: chosen for what the fit (Ai's answers in it) still does not know, among the person's hands. */
    fun next(): Proposal = picker.next(model.filter { it.judge == Bench.PERSON }, fit)

    /** [trial] kept and fitted. */
    fun record(trial: StoredTrial) {
        log = log.plus(trial)
        val read = bench.observations(trial, withAi)
        if (read.isNotEmpty()) {
            model += read
            fit = Fitter.fit(bench.spec, model, fit.theta)
        }
    }

    /** Several kept at once, fitted once (Ai's exam on a calibration set). */
    fun recordAll(trials: List<StoredTrial>) {
        if (trials.isEmpty()) return
        log = log.copy(trials = log.trials + trials)
        val read = trials.flatMap { bench.observations(it, withAi) }
        if (read.isNotEmpty()) {
            model += read
            fit = Fitter.fit(bench.spec, model, fit.theta)
        }
    }

    /** The log with [change] made to what is not a trial (notes, the trust settings): nothing to fit. */
    fun amend(change: (ShootoutLog) -> ShootoutLog) {
        log = change(log).copy(trials = log.trials)
    }

    /** The person's answer to a rating, kept and fitted; the kept trial. */
    fun answer(
        p: Proposal.Rate, answer: Answer, id: String, at: Long, ms: Long? = null, session: String? = null,
        sawAi: Boolean = false, mode: String? = null, draws: SeenDraws = SeenDraws.NONE,
    ): StoredTrial = bench.rated(p, answer, id, at, ms, session, sawAi, mode, draws).also(::record)

    /** The person's choice in a comparison, kept and fitted; the kept trial. */
    fun prefer(
        p: Proposal.Compare, leftPreferred: Boolean, id: String, at: Long, ms: Long? = null, session: String? = null,
        sawAi: Boolean = false, mode: String? = null, draws: SeenDraws = SeenDraws.NONE,
    ): StoredTrial = bench.compared(p, leftPreferred, id, at, ms, session, sawAi, mode, draws).also(::record)

    /** Each judge's noise on the five-point scale, in log-odds (1 / precision), by [Bench.PERSON], [Bench.AI], [Bench.SEEN]. */
    fun noiseOf(judge: Int): Double = exp(-fit.theta[bench.spec.layout.precision(judge)])

    /**
     * How much one answer of [judge]'s weighs beside one of the person's blind answers: the information an answer carries
     * grows with the judge's precision squared, so a judge twice as noisy counts a quarter. Measured where they overlap.
     */
    fun weightOf(judge: Int): Double {
        val l = bench.spec.layout
        val d = fit.theta[l.precision(judge)] - fit.theta[l.precision(Bench.PERSON)]
        return exp(2 * d)
    }

    /**
     * How [judge] leans against the person's bands, in points of win chance at an even hand: positive is more
     * optimistic (it calls a hand better than the person would). The mean shift of its four cut-offs, read at 50 %.
     */
    fun leanOf(judge: Int): Double {
        if (judge == Bench.PERSON) return 0.0
        val l = bench.spec.layout
        val shift = (0 until 4).sumOf { fit.theta[l.cut(judge, it)] } / 4
        return 100 * (0.5 - Logistic.of(shift))
    }

    /** How many answers of each judge the model reads. */
    fun answersOf(judge: Int): Int = model.count { it.judge == judge }

    /** The reader of the numbers: the same hands every time, so the progress line and the results agree. */
    internal val reporter: Reporter by lazy { Reporter(bench.spec, bench.decks, REPORT_POOL, REPORT_SEED) }

    /** What is settled so far: the session's progress line. */
    fun settled(): StopRule.Settled = STOP.read(reporter.ratings(fit, model), strataInPlay())

    /** Every number with its range, from the whole log. */
    fun results(): ShootoutResults = ShootoutResults.read(this)

    /** The strata the session deals in. */
    fun strataInPlay(): List<Stratum> = pinned?.let { listOf(it) } ?: bench.spec.strata

    /** The person's measured noise on the five-point scale, in log-odds (1 / precision): the first number to watch. */
    val noise: Double get() = exp(-fit.theta[bench.spec.layout.precision(0)])

    internal fun modelTrials(): List<Trial> = model

    /**
     * The model's own prediction for a hand (stage 3, S.md §6½ "The model's own prediction"): handed to Ai as one input
     * among the examples and the rubric, read on the person's own scale.
     */
    fun predict(p: Proposal): Prediction {
        val spec = bench.spec
        val l = spec.layout
        val s = spec.stratumIndex(p.stratum)
        val value = HandValue(spec)
        val theta = fit.theta
        return when (p) {
            is Proposal.Rate -> {
                val x = value.features(s, p.hand, p.opponent)
                val eta = x.dot(theta)
                val chances = Likelihood.answerChances(eta, l.cuts(theta, Bench.PERSON), theta[l.precision(Bench.PERSON)])
                val sd = sqrt(fit.covariance.sparseQuadratic(x.index, x.value).coerceAtLeast(0.0))
                Prediction(Logistic.of(eta), sd, Answer.entries[chances.indices.maxBy { chances[it] }], chances.toList(), null)
            }
            is Proposal.Compare -> {
                val x = value.features(s, p.left, p.opponent).minus(value.features(s, p.right, p.opponent))
                val diff = x.dot(theta)
                val sd = sqrt(fit.covariance.sparseQuadratic(x.index, x.value).coerceAtLeast(0.0))
                Prediction(Logistic.of(exp(theta[l.comparePrecision(Bench.PERSON)]) * diff), sd, null, emptyList(), diff >= 0)
            }
        }
    }

    companion object {
        /**
         * The stop rule at the simulation's tuning (S.md §4½): 21 of 24 cards known within ±5 points (95 %). The
         * session shows it as progress, never as a promise; at a judge's real noise it takes several sessions.
         */
        val STOP = StopRule(halfWidth = 5.0, share = 0.875)

        /** About ten minutes is a session (S.md §3): then stopping is suggested, every answer kept. */
        const val SESSION_MS = 10 * 60_000L

        /** Hands the results are averaged over: the reports' own size, seeded so a report reads the same twice. */
        const val REPORT_POOL = 400
        const val REPORT_SEED = 1L
    }
}
