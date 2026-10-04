package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.model.Contrast
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Target
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.sim.SimJudge
import com.kaiharimoto.mastertool.core.shootout.sim.SyntheticDeck
import com.kaiharimoto.mastertool.core.shootout.sim.TrustStudy
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Teaching Ai, proved in simulation before any model judges a real hand (stage 3, S.md §6½): a person and an Ai judge of
 * known accuracy ([SimJudge]) answer the same synthetic matchup, kept as the app keeps them ([TrustStudy]).
 *
 * - **The gate** opens for a good judge and stays shut for a poor one; **audits** close a kind when a judge that earned
 *   it gets worse.
 * - **The weighting** (Dawid–Skene: each kind of answer its own judge, its noise and lean measured where it overlaps the
 *   person's blind answers) keeps a biased judge from moving the ratings, where pooling its answers as the person's would.
 *
 * Deterministic: the same seeds give the same numbers every run. Each threshold is the measured number less a margin,
 * written beside it.
 */
class ShootoutTrustSimulationTest {

    /** A steady person: noise about half the stage-1 judge's, few slips, no fatigue. */
    private fun steady(world: SyntheticDeck, seed: Long) =
        SimJudge(world, seed, contextNoise = 0.2, handNoise = 0.2, slips = 0.01, fatigue = 0.0, lean = 0.0)

    /** A good Ai: steadier than the person. */
    private fun good(world: SyntheticDeck, seed: Long) =
        SimJudge(world, seed, contextNoise = 0.1, handNoise = 0.1, slips = 0.0, fatigue = 0.0, lean = 0.0, judge = 1)

    /** A poor Ai: three times the person's noise and a share of slips. */
    private fun poor(world: SyntheticDeck, seed: Long) =
        SimJudge(world, seed, contextNoise = 0.6, handNoise = 0.6, slips = 0.08, fatigue = 0.0, lean = 0.0, judge = 1)

    @Test
    fun theGateOpensForAGoodJudgeAndStaysShutForAPoorOne() {
        val opened = ArrayList<Int>()
        val shut = ArrayList<Int>()
        for (seed in 1L..3L) {
            val world = SyntheticDeck.matchup(seed, judges = 3)
            val goodStudy = TrustStudy(world, steady(world, seed * 11), good(world, seed * 13), seed)
            goodStudy.apprentice(400)
            val g = goodStudy.trust()
            opened += g.open.size
            val poorStudy = TrustStudy(world, steady(world, seed * 11), poor(world, seed * 17), seed)
            poorStudy.apprentice(400)
            val p = poorStudy.trust()
            shut += p.open.size
            println("[trust] seed $seed good: open ${g.open.map { it.kind.key }}; " + g.kinds.joinToString { "${it.kind.key} ${fmt(it.share)} (${fmt(it.sureRange.lower)}–${fmt(it.sureRange.upper)}) n=${it.surePairs.toInt()}" } + " ece ${fmt(g.calibration.ece)}")
            println("[trust] seed $seed poor: open ${p.open.map { it.kind.key }}; " + p.kinds.joinToString { "${it.kind.key} ${fmt(it.share)} (${fmt(it.sureRange.lower)}–${fmt(it.sureRange.upper)}) n=${it.surePairs.toInt()}" } + " ece ${fmt(p.calibration.ece)}")
            assertTrue(p.open.isEmpty(), "a poor judge earned ${p.open.map { it.kind.key }}")
            // Ai's stated certainty is scored too: the poor judge is as sure as the good one, and agrees far less.
            assertTrue(p.calibration.brier > 2 * g.calibration.brier, "poor Brier ${p.calibration.brier}, good ${g.calibration.brier}")
        }
        assertTrue(opened.sum() >= 6, "a good judge earned only $opened kinds over three matchups")
        assertTrue(shut.sum() == 0)
    }

    @Test
    fun auditsCloseAKindWhenAJudgeThatEarnedItGetsWorse() {
        val world = SyntheticDeck.matchup(4, judges = 3)
        val study = TrustStudy(world, steady(world, 41), good(world, 43), 4)
        study.apprentice(400)
        val before = study.trust().open.map { it.kind.key }
        assertTrue(before.isNotEmpty(), "nothing opened")
        // A while alone, audited: a good judge keeps what it earned.
        study.solo(150)
        val kept = study.trust()
        println("[trust] solo 150: ${study.solos} solo, ${study.audits} audits, open ${kept.open.map { it.kind.key }}, closed ${kept.kinds.filter { it.closedAt != null }.map { it.kind.key }}")
        assertTrue(study.solos > 0 && study.audits > 0)
        assertTrue(kept.open.size >= before.size - 1, "a good judge lost kinds to its audits: $before → ${kept.open.map { it.kind.key }}")
        // Then it gets worse — a new model, say, as noisy as the poor judge and a band gloomier: the audits close what it had.
        study.ai = SimJudge(world, 47, contextNoise = 0.6, handNoise = 0.6, slips = 0.08, fatigue = 0.0, lean = 0.0, offset = -1.5, judge = 1)
        val solosBefore = study.solos
        study.solo(400)
        val after = study.trust()
        val closed = after.kinds.filter { it.closedAt != null }.map { it.kind.key }
        println("[trust] worse judge: ${study.solos - solosBefore} more solo hands, open ${after.open.map { it.kind.key }}, closed $closed; audits " + after.kinds.joinToString { "${it.kind.key} ${it.misses}/${it.audits}" } + " all ${after.audits.size} missed ${after.audits.count { !it.agrees }}; closed at solo hand ${study.closed} (worse from $solosBefore)")
        assertTrue(after.open.size < kept.open.size, "the audits closed nothing: ${after.open.map { it.kind.key }}")
        assertTrue(closed.isNotEmpty())
    }

    @Test
    fun theWeightingKeepsABiasedJudgeFromMovingTheRatings() {
        var dsWin = 0.0
        var naiveWin = 0.0
        var dsCard = 0.0
        var naiveCard = 0.0
        val runs = 3
        for (seed in 1L..runs) {
            val world = SyntheticDeck.matchup(seed + 10, judges = 3)
            // Optimistic by a band, and blind to card 9 (a brick it rates as a starter).
            val biased = SimJudge(world, seed * 19, contextNoise = 0.2, handNoise = 0.2, slips = 0.01, fatigue = 0.0, lean = 0.0, offset = 1.0, favour = mapOf(9 to 1.5), judge = 1)
            val study = TrustStudy(world, SimJudge(world, seed * 23), biased, seed)
            study.apprentice(200)
            val person = study.model.filter { it.judge == 0 }
            val withAi = study.model.toList()
            val naive = study.model.map { if (it is Rated && it.judge == 1) it.copy(judge = 0) else it }
            val reporter = Reporter(world.spec, world.decks, 300, seed)
            fun read(trials: List<Trial>) = reporter.contrasts(Fitter.fit(world.spec, trials).theta)
            val base = read(person)
            val ds = read(withAi)
            val nv = read(naive)
            fun win(c: List<Contrast>) = c.filter { it.target is Target.WinRate }.map { it.value }.average()
            fun card(c: List<Contrast>) = c.filter { (it.target as? Target.Card)?.card == 9 }.map { it.value }.average()
            dsWin += abs(win(ds) - win(base)) / runs
            naiveWin += abs(win(nv) - win(base)) / runs
            dsCard += abs(card(ds) - card(base)) / runs
            naiveCard += abs(card(nv) - card(base)) / runs
        }
        println("[trust] biased judge moved the win rate ${fmt(dsWin)} points weighted, ${fmt(naiveWin)} pooled; card 9 ${fmt(dsCard)} weighted, ${fmt(naiveCard)} pooled")
        assertTrue(naiveWin >= 5.0, "pooling a judge a band optimistic should skew the win rate: $naiveWin")
        assertTrue(dsWin <= naiveWin / 3, "the lean was not corrected: $dsWin against $naiveWin")
        assertTrue(dsCard <= naiveCard / 2, "the blind spot moved the card: $dsCard against $naiveCard")
    }

    @Test
    fun aGoodJudgesAnswersAddToWhatThePersonsAlreadyKnow() {
        var alone = 0.0
        var helped = 0.0
        val runs = 3
        for (seed in 1L..runs) {
            val world = SyntheticDeck.matchup(seed + 20, judges = 3)
            val study = TrustStudy(world, SimJudge(world, seed * 29), good(world, seed * 31), seed)
            study.apprentice(120)
            // Ai alone on 240 more hands, every one of them (no gate: this is the weighting's own test).
            val more = TrustStudy(world, SimJudge(world, seed * 37), good(world, seed * 41), seed + 100)
            more.apprentice(240)
            val reporter = Reporter(world.spec, world.decks, 300, seed)
            val truth = reporter.contrasts(world.truth)
            fun error(trials: List<Trial>): Double {
                val est = reporter.contrasts(Fitter.fit(world.spec, trials).theta)
                val cards = est.indices.filter { est[it].target is Target.Card }
                return sqrt(cards.sumOf { (est[it].value - truth[it].value).let { e -> e * e } } / cards.size)
            }
            alone += error(study.model.filter { it.judge == 0 }) / runs
            helped += error(study.model.filter { it.judge == 0 } + more.model.filter { it.judge == 1 }) / runs
        }
        println("[trust] card error: the person's 120 answers ${fmt(alone)} points; with a good Ai's 240 more ${fmt(helped)}")
        assertTrue(helped < alone, "a good judge's answers did not help: $helped against $alone")
    }

    private fun fmt(x: Double?) = if (x == null) "–" else ((x * 1000).toInt() / 1000.0).toString()
}
