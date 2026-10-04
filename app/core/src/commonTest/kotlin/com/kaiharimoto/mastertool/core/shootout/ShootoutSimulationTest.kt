package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.model.Target
import com.kaiharimoto.mastertool.core.shootout.select.PickerSettings
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.sim.Study
import com.kaiharimoto.mastertool.core.shootout.sim.SyntheticDeck
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The method, proved on a made-up matchup before any person judges a hand (Phase S §4).
 *
 * Every test reads one seeded study, run once (about 18 s on a laptop's JVM): four matchups, each with the adaptive
 * picker for 200 trials and with plain shuffled hands for 300, answered by a simulated judge with noise, slips and
 * fatigue the model does not know about ([com.kaiharimoto.mastertool.core.shootout.sim.SimJudge]'s defaults). It
 * is deterministic: the same seeds give the same numbers on every run, so nothing here can flake.
 *
 * Each threshold is the measured number less a margin, written beside it, so a later change cannot quietly make the
 * method worse (S.md "Simulation results" has these numbers and the wider sweep behind them).
 */
class ShootoutSimulationTest {

    /**
     * The adaptive picker reaches the target error (draw-weighted RMS error of the cards' worth, 5 points) in far
     * fewer trials than plain shuffled hands. Measured: adaptive 130, 90, 60, 80 (mean 90); plain 100, 160, 210, 200
     * (mean 167.5): a ratio of 1.86. Held at 1.4. A plain run that never gets there counts as its full 300.
     */
    @Test
    fun recovery() {
        val adaptive = TheStudy.adaptive.map { it.trialsTo(TARGET) ?: ADAPTIVE_TRIALS * 2 }.average()
        val plain = TheStudy.plain.map { it.trialsTo(TARGET) ?: PLAIN_TRIALS }.average()
        assertTrue(plain / adaptive >= 1.4, "plain needs ${plain / adaptive}× the adaptive picker's trials ($plain vs $adaptive)")

        // And at the same count, the adaptive picker is further along. Measured: 3.76 against 4.51 points (0.83×).
        val atSame = TheStudy.plain.map { r -> r.checkpoints.first { it.trials == ADAPTIVE_TRIALS }.cardError }.average()
        val mine = TheStudy.adaptive.map { it.checkpoints.last().cardError }.average()
        assertTrue(mine <= 0.92 * atSame, "after $ADAPTIVE_TRIALS trials: adaptive $mine, plain $atSame points")
    }

    /**
     * The ranges hold the truth as often as they say. Measured over the eight runs' 384 card ratings: 80 % ranges
     * 79.2 %, 95 % ranges 94.8 %. Held at 72–88 % and 88–99 %: the ratings within a run share a fit, so the
     * pooled share wanders more than a binomial's ±2 points.
     */
    @Test
    fun calibration() {
        val all = TheStudy.adaptive + TheStudy.plain
        val in80 = all.map { it.checkpoints.last().covered80 }.average()
        val in95 = all.map { it.checkpoints.last().covered95 }.average()
        assertTrue(in80 in 0.72..0.88, "80 % ranges held the truth ${in80 * 100} % of the time")
        assertTrue(in95 in 0.88..0.99, "95 % ranges held the truth ${in95 * 100} % of the time")
    }

    /**
     * The real-world win rate is not skewed by choosing hands. The matchup wins 65 % of real hands; the picker shows
     * mostly close calls, so the plain average of its answers reads 4.8 points low (measured, every one of eight
     * strata low). The model's rate, averaged over hands by their real odds, is off by −0.1 points on average
     * (plain shuffles: −0.2), and the plain hands' check sits at −0.5. Held: the model within ±2 points on average
     * for both pickers, the check within ±2.5, and the naive average at least 2.5 points low, so the test also shows
     * why the correction is needed.
     */
    @Test
    fun noBias() {
        for (runs in listOf(TheStudy.adaptive, TheStudy.plain)) {
            val calls = runs.flatMap { it.winRates() }
            val bias = calls.map { it.model - it.truth }.average()
            assertTrue(abs(bias) <= 2.0, "the model's win rate is off by $bias points on average")
            val check = calls.map { it.residual }.average()
            assertTrue(abs(check) <= 2.5, "the plain hands' check is off by $check points on average")
        }
        val naive = TheStudy.adaptive.flatMap { it.winRates() }.map { it.naive - it.truth }.average()
        assertTrue(naive <= -2.5, "the naive average of the chosen hands' answers was only $naive points low")
    }

    /**
     * Pair effects are found when they exist and not invented when they do not. Of the 3 real pairs × 2 strata × 4
     * matchups, the adaptive picker's runs show 10 of 24 (plain shuffles 4); of the 72 null ones, 2 (plain 0). So
     * 2 of the 16 pairs shown were false: a false-discovery rate of 12.5 %, held at 25 %; real pairs found held at
     * a quarter, and never fewer than plain shuffles find.
     */
    @Test
    fun pairs() {
        val real = SyntheticDeck.PAIR_EFFECTS.indices.toSet()
        val adaptive = TheStudy.adaptive.flatMap { it.pairCalls(real) }
        val plain = TheStudy.plain.flatMap { it.pairCalls(real) }
        val found = adaptive.count { it.real && it.shown }
        assertTrue(found >= adaptive.count { it.real } / 4, "only $found real pairs found")
        assertTrue(found >= plain.count { it.real && it.shown }, "plain shuffles found more real pairs")
        val all = adaptive + plain
        val shown = all.count { it.shown }
        val false_ = all.count { !it.real && it.shown }
        assertTrue(false_ <= 0.25 * shown, "$false_ of $shown pairs shown were not real")
        assertTrue(false_ <= 0.05 * all.count { !it.real }, "$false_ null pairs shown")
    }

    /**
     * When the stop rule calls a card known within ±7 points, it is. Measured after 200 adaptive trials: 6, 10, 8
     * and 7 of 24 cards known within ±7 points (the 95 % range), and 77 of those 79 were within ±7 of the truth
     * (97 %). Held at 90 %.
     */
    @Test
    fun stopRuleKeepsItsWord() {
        val h = 7.0
        var known = 0
        var held = 0
        for ((i, run) in TheStudy.adaptive.withIndex()) {
            val ratings = run.reporter.ratings(run.fit, run.trials)
            val truth = run.truth.associateBy { it.target }
            for (r in ratings.cards) {
                if (r.estimate.halfWidth95 > h) continue
                known++
                if (abs(r.estimate.value - truth.getValue(Target.Card(r.card, r.stratum)).value) <= h) held++
            }
            val settled = StopRule(h).read(ratings, TheStudy.worlds[i].spec.strata)
            assertTrue(settled.known in 1 until settled.of, "a rule that settles nothing, or everything, tests nothing: $settled")
        }
        assertTrue(held >= 0.9 * known, "$held of $known cards called known within ±$h points were")
    }

    private companion object {
        const val ADAPTIVE_TRIALS = 200
        const val PLAIN_TRIALS = 300
        const val TARGET = 5.0
    }

    /** The study, run once for every test here. */
    private object TheStudy {
        val worlds = (1L..4L).map { SyntheticDeck.matchup(it, winRate = 0.65) }
        val adaptive = worlds.mapIndexed { i, w -> Study.run(w, PickerSettings(), ADAPTIVE_TRIALS, i + 1L, checkEvery = 10) }
        val plain = worlds.mapIndexed { i, w -> Study.run(w, Study.PLAIN, PLAIN_TRIALS, i + 1L, checkEvery = 10) }
    }
}
