package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.RealWorld
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.JudgedPair
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes

/** One card's number in one stratum: its worth per copy with its ranges, how often it is drawn, and the trials behind it. */
class CardCell(val estimate: Estimate, val drawShare: Double, val trials: Int)

/** One card's row: its numbers in each stratum the deck deals it in, side by side (S.md §5). */
class CardResult(
    /** The canonical passcode. */
    val card: Int,
    val role: String,
    /** Copies in the deck as built (the game-one or deck-alone deck). */
    val copies: Int,
    val cells: Map<Stratum, CardCell>,
)

/** A pair whose 95 % range excludes zero: the extra win chance from holding both, beyond the two cards' own. */
class PairResult(val a: Int, val b: Int, val stratum: Stratum, val estimate: Estimate, val trials: Int)

/** Which trials a number stands on: every number opens its trials (S.md §5). */
sealed interface Behind {
    val stratum: Stratum?

    /** The trials of [stratum] showing a hand that holds [card]. */
    data class Card(val card: Int, override val stratum: Stratum) : Behind

    /** The trials of [stratum] showing a hand that holds both. */
    data class Pair(val a: Int, val b: Int, override val stratum: Stratum) : Behind

    /** Every trial of [stratum] (its win rate). */
    data class WinRate(override val stratum: Stratum) : Behind

    /** Every trial kept. */
    data object All : Behind {
        override val stratum: Stratum? = null
    }

    /**
     * The hands of one kind both the person and Ai answered (stage 3, the trust panel's agreement): [key] is a
     * [com.kaiharimoto.mastertool.core.shootout.teach.HandKind.key]; [audits] lists only the audits.
     */
    data class Kind(val key: String, val audits: Boolean = false) : Behind {
        override val stratum: Stratum? = null
    }

    /** Ai's answers alone (stage 3): every trial Ai judged, solo or beside the person. */
    data object Ai : Behind {
        override val stratum: Stratum? = null
    }
}

/**
 * What the trials say (Phase S §5, a first cut): each card's worth per copy in each stratum with its 80 % and 95 %
 * ranges, its draw rate and the trials behind it; the pairs that earned a place; each stratum's real-world win rate;
 * how much is settled; and the person's measured noise.
 *
 * The deck alone and a matchup are separate results, never mixed: a [ShootoutRun] is one or the other.
 */
class ShootoutResults(
    /** The strata dealt, in their order. */
    val strata: List<Stratum>,
    /** The strata waiting for a siding plan, and why. */
    val waiting: Map<Stratum, String>,
    val cards: List<CardResult>,
    val pairs: List<PairResult>,
    /** Each stratum's win rate over real hands, by their real odds. */
    val winRates: Map<Stratum, Estimate>,
    /** The check against reality on the plain shuffled hands (S.md §3), per stratum. */
    val checks: Map<Stratum, RealWorld.Check>,
    /** Trials kept, per stratum, all judges. */
    val counts: Map<Stratum, Int>,
    /** Every trial in the log, and how many the model read. */
    val kept: Int,
    val fitted: Int,
    /** Sided trials dealt under plans other than today's: kept, labelled, pooled. */
    val olderPlans: Int,
    val settled: StopRule.Settled,
    /** The person's noise on the five-point scale, in log-odds. */
    val noise: Double,
) {
    companion object {
        fun read(run: ShootoutRun): ShootoutResults {
            val bench = run.bench
            val spec = bench.spec
            val trials = run.log.trials
            val model = run.modelTrials()
            val ratings = run.reporter.ratings(run.fit, model)
            val byCard = ratings.cards.groupBy { it.card }
            val main = bench.spec.strata.first()
            val cards = bench.own.indices.mapNotNull { i ->
                val passcode = bench.own[i]
                val cells = LinkedHashMap<Stratum, CardCell>()
                byCard[i].orEmpty().forEach { r ->
                    if (r.drawShare <= 0) return@forEach
                    val n = trials.count { it.blind && it.stratum == r.stratum.name && it.holds(passcode) }
                    cells[r.stratum] = CardCell(r.estimate, r.drawShare, n)
                }
                if (cells.isEmpty()) null
                else CardResult(passcode, bench.roleNames[spec.roles[i]], bench.decks.own(main)[i], cells)
            }.sortedWith(compareBy<CardResult>({ bench.roleNames.indexOf(it.role) }, { -it.copies }))
            val pairs = ratings.shownPairs.map { p ->
                val a = bench.own[p.pair.a]
                val b = bench.own[p.pair.b]
                PairResult(a, b, p.stratum, p.estimate, trials.count { it.blind && it.stratum == p.stratum.name && it.holdsBoth(a, b) })
            }
            val checks = RealWorld.check(spec, run.fit, run.reporter, model).associateBy { it.stratum }
            return ShootoutResults(
                strata = bench.strata,
                waiting = bench.waiting,
                cards = cards,
                pairs = pairs,
                winRates = ratings.winRates,
                checks = checks,
                counts = (bench.strata + bench.waiting.keys).associateWith { s -> trials.count { it.stratum == s.name } },
                kept = trials.size,
                fitted = model.size,
                olderPlans = trials.count(bench::underOlderPlan),
                settled = ShootoutRun.STOP.read(ratings, bench.strata),
                noise = run.noise,
            )
        }

        /**
         * The kept trials [behind] a number, newest first. [kindOf] names a trial's kind of hand, for [Behind.Kind]: the
         * pairs of that kind — each of the person's trials Ai answered, and Ai's answer beside it.
         */
        fun trialsBehind(trials: List<StoredTrial>, behind: Behind, kindOf: (StoredTrial) -> String? = { null }): List<StoredTrial> {
            if (behind is Behind.Kind) {
                val pairs = JudgedPair.all(trials, kindOf).filter { p ->
                    p.kind == behind.key && (if (behind.audits) p.person.mode == TeachModes.AUDIT else p.counts)
                }
                return pairs.flatMap { listOfNotNull(it.person, it.aiTrial) }.asReversed()
            }
            return trials.filter { t ->
                when (behind) {
                    is Behind.Card -> t.stratum == behind.stratum.name && t.holds(behind.card)
                    is Behind.Pair -> t.stratum == behind.stratum.name && t.holdsBoth(behind.a, behind.b)
                    is Behind.WinRate -> t.stratum == behind.stratum.name
                    Behind.All -> true
                    Behind.Ai -> t.judge == StoredTrial.AI
                    is Behind.Kind -> false
                }
            }.asReversed()
        }
    }
}
