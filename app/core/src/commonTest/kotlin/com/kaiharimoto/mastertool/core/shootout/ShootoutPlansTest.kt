package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrint
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase S, S4.5: each siding plan its own deck — a sided hand dealt under an older plan is read as that plan's for the cards
 * it changed, and pooled for the rest (S.md §1½), so changing a plan never leaves its old hands speaking for the new one.
 */
class ShootoutPlansTest {

    private val pool: Map<Int, Card> = buildMap {
        for (i in 1..40) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect"))
        for (i in 1..40) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
    }
    private val lookup: (CardId) -> Card? = { pool[it.value] }
    private val mine: Deck = Deck(
        main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId),
        side = listOf(1031, 1031, 1031).map(::CardId),
    )
    private val theirs: Deck = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))
    private val opp = Opponent("opp", "Yubel", theirs)
    private val theirPlan = SidePlan(out = listOf(CardId(2001)), into = listOf(CardId(2040)))

    /** Plan A brings one copy of 1031 in; plan B, three. */
    private val planA = SidePlan(out = listOf(CardId(1009)), into = listOf(CardId(1031)))
    private val planB = SidePlan(out = List(3) { CardId(1009) }, into = List(3) { CardId(1031) })

    private fun bench(plan: SidePlan, trials: List<StoredTrial> = emptyList()) =
        Bench.of(BenchInput(mine, lookup, opponent = opp, mine = mapOf(Turn.FIRST to plan), theirs = mapOf(Turn.SECOND to theirPlan), trials = trials))

    /** A session pinned to sided going first, its judge reading 1031 as worth [worth] in log-odds a copy. */
    private fun answer(b: Bench, log: ShootoutLog, n: Int, worth: Double, seed: Int): ShootoutLog {
        val run = ShootoutRun(b, log, pinned = Stratum.SIDED_FIRST, seed = seed.toLong())
        val random = Random(seed)
        repeat(n) { i ->
            when (val p = run.next()) {
                is Proposal.Rate -> {
                    val ids = b.ids(p.hand)
                    val v = worth * ids.count { it == 1031 } + 0.4 * ids.count { it == 1001 } + 0.3 * (random.nextDouble() - 0.5)
                    val a = ShootoutWords.SCALE.minByOrNull { x -> abs(x.score - Logistic.of(v)) }!!
                    run.answer(p, a, "s$seed-$i", at = seed * 10_000L + i)
                }
                is Proposal.Compare -> {
                    val l = b.ids(p.left).count { it == 1031 }
                    val r = b.ids(p.right).count { it == 1031 }
                    run.prefer(p, if (worth > 0) l >= r else l <= r, "s$seed-$i", at = seed * 10_000L + i)
                }
            }
        }
        return run.log
    }

    @Test
    fun anOlderPlansHandsAreReadAsThatPlansForTheCardsItChanged() {
        assertEquals(mapOf(1009 to -3, 1031 to 3), PlanPrint.delta(PlanPrint.of(planB)))
        assertEquals(emptyMap(), PlanPrint.delta("="))
        val log = answer(bench(planA), ShootoutLog(deck = "me", opponent = "opp"), 60, worth = -1.5, seed = 1)
        val b = bench(planB, log.trials)
        val plan = b.spec.plans.single()
        assertEquals(Stratum.SIDED_FIRST, plan.stratum)
        val at = b.own.withIndex().associate { it.value to it.index }
        assertEquals(setOf(at.getValue(1009), at.getValue(1031)), plan.cards.toSet(), "the cards whose copies the plans deal differently")
        // A hand of the older plan reads as plan 0; one kept before plans were stamped reads as today's.
        val old = log.trials.first { it.stratum == "SIDED_FIRST" && it.kind == StoredTrial.RATE }
        assertEquals(0, (assertNotNull(b.trial(old)) as Rated).plan)
        assertEquals(-1, (assertNotNull(b.trial(old.copy(plans = null))) as Rated).plan)
        assertTrue(b.underOlderPlan(old))
        // No older plan, nothing changes: the model is the one it was.
        assertTrue(bench(planA, log.trials).spec.plans.isEmpty())
    }

    @Test
    fun aChangedPlansOldHandsNeverSpeakForTheNewOne() {
        // Under plan A the judge read 1031 as costing a copy; under plan B (three copies) as gaining. The same answers, read
        // with their plans (apart) and with the plans stripped (pooled, as before 1.1.93): B's rating of 1031 is B's.
        // Measured: apart +28.1 points a copy, pooled +22.0. Held: apart above zero, and at least 3 points over pooled.
        val a = answer(bench(planA), ShootoutLog(deck = "me", opponent = "opp"), 120, worth = -1.5, seed = 2)
        val both = answer(bench(planB, a.trials), a, 120, worth = 1.5, seed = 3)
        fun worth(trials: List<StoredTrial>): Double {
            val b = bench(planB, trials)
            val r = ShootoutRun(b, both.copy(trials = trials), seed = 3).results()
            return r.cards.first { it.card == 1031 }.cells.getValue(Stratum.SIDED_FIRST).estimate.value
        }
        val apart = worth(both.trials)
        val pooled = worth(both.trials.map { it.copy(plans = null) })
        assertTrue(apart > 0, "under plan B 1031 gains: $apart")
        assertTrue(apart > pooled + 3, "read apart ($apart) is not pulled toward plan A's hands as pooled ($pooled) is")
    }
}
