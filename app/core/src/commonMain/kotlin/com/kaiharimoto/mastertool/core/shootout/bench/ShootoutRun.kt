package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Picker
import com.kaiharimoto.mastertool.core.shootout.select.PickerSettings
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import kotlin.math.exp

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
class ShootoutRun(val bench: Bench, log: ShootoutLog, pinned: Stratum? = null, seed: Long = 7L) {

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
        log.trials.forEach { t -> bench.trial(t)?.let { model += it } }
        fit = Fitter.fit(bench.spec, model)
    }

    /** How many trials the model reads (the person's blind answers in the strata it holds). */
    val fitted: Int get() = model.size

    /** The next trial to show. */
    fun next(): Proposal = picker.next(model, fit)

    /** [trial] kept and fitted. */
    fun record(trial: StoredTrial) {
        log = log.plus(trial)
        bench.trial(trial)?.let {
            model += it
            fit = Fitter.fit(bench.spec, model, fit.theta)
        }
    }

    /** The person's answer to a rating, kept and fitted; the kept trial. */
    fun answer(p: Proposal.Rate, answer: Answer, id: String, at: Long, ms: Long? = null, session: String? = null): StoredTrial =
        bench.rated(p, answer, id, at, ms, session).also(::record)

    /** The person's choice in a comparison, kept and fitted; the kept trial. */
    fun prefer(p: Proposal.Compare, leftPreferred: Boolean, id: String, at: Long, ms: Long? = null, session: String? = null): StoredTrial =
        bench.compared(p, leftPreferred, id, at, ms, session).also(::record)

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
