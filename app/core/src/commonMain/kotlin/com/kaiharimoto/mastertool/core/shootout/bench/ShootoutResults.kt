package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.RealWorld
import com.kaiharimoto.mastertool.core.shootout.select.StopRule
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.JudgedPair
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import kotlin.math.abs
import kotlin.math.ceil

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
    /**
     * Going second, its numbers as the turn's draw (2026-10, kai): read only off the hands where it was drawn, so it never
     * muddies [cells], which are the opening five's. [CardCell.drawShare] is the chance it is the draw.
     */
    val drawn: Map<Stratum, CardCell> = emptyMap(),
)

/** A pair whose 95 % range excludes zero: the extra win chance from holding both, beyond the two cards' own. */
class PairResult(val a: Int, val b: Int, val stratum: Stratum, val estimate: Estimate, val trials: Int)

/** A card the hands have called (S.md §5: "a verdict only where the range supports one"): its 80 % range excludes zero. */
class Call(val card: Int, val stratum: Stratum, val estimate: Estimate) {
    val gains: Boolean get() = estimate.value > 0
}

/** Which trials a number stands on: every number opens its trials (S.md §5). */
sealed interface Behind {
    val stratum: Stratum?

    /** The trials of [stratum] showing a hand that holds [card] in its opening five. */
    data class Card(val card: Int, override val stratum: Stratum) : Behind

    /** The trials of [stratum] showing a hand whose turn's draw is [card]. */
    data class Drawn(val card: Int, override val stratum: Stratum) : Behind

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
    /**
     * How often the person would give one of their hands the same answer twice, as the fit reads their noise: the chance
     * of two equal answers, averaged over the hands they judged (design review, 1.1.6: "noise 0.24" in words). Null
     * before there are enough hands to say.
     */
    val steadiness: Double? = null,
    /**
     * The strata [settled] and [handsToSettle] read (the red team, 2026-10): the pinned one while a session is pinned, so
     * the results agree with the session's own line instead of waiting on a column the person chose not to train.
     */
    val inPlay: List<Stratum> = strata,
) {
    /**
     * What the hands have called so far, the clearest first: every card and stratum whose 80 % range lies wholly on one
     * side of zero ("Arias: worth about +20 points going first"). Empty is "too early to call".
     */
    fun calls(): List<Call> = cards.flatMap { row ->
        row.cells.filter { (_, c) -> c.estimate.range80.let { it.start > 0 || it.endInclusive < 0 } }
            .map { (s, c) -> Call(row.card, s, c.estimate) }
    }.sortedByDescending { abs(it.estimate.value) }

    /**
     * About how many more of the person's hands until the stop rule is met, read from the ranges as they stand: a range
     * narrows as one over the square root of the hands, so the card at the rule's share needs its width over the rule's
     * squared, times the hands so far. A rough reading, said as "about"; 0 once it is met, null with nothing to read.
     */
    fun handsToSettle(): Int? {
        if (settled.enough) return 0
        if (fitted == 0 || cards.isEmpty()) return null
        val widths = cards.mapNotNull { row -> row.cells.filterKeys { it in inPlay }.values.maxOfOrNull { it.estimate.halfWidth95 } }.sorted()
        if (widths.isEmpty()) return null
        val at = widths[(ceil(ShootoutRun.STOP.share * widths.size).toInt() - 1).coerceIn(0, widths.lastIndex)]
        if (at <= settled.halfWidth) return 0
        val ratio = at / settled.halfWidth
        return ceil(fitted * (ratio * ratio - 1)).toInt()
    }

    companion object {
        fun read(run: ShootoutRun): ShootoutResults {
            val bench = run.bench
            val spec = bench.spec
            val trials = run.log.trials
            val canon = bench::canonical
            val model = run.modelTrials()
            val ratings = run.reporter.ratings(run.fit, model)
            val byCard = ratings.cards.groupBy { it.card }
            val byDrawn = ratings.drawn.groupBy { it.card }
            val main = bench.spec.strata.first()
            val cards = bench.own.indices.mapNotNull { i ->
                val passcode = bench.own[i]
                val cells = LinkedHashMap<Stratum, CardCell>()
                byCard[i].orEmpty().forEach { r ->
                    if (r.drawShare <= 0) return@forEach
                    val n = trials.count { it.stratum == r.stratum.name && opens(it, passcode, canon) }
                    cells[r.stratum] = CardCell(r.estimate, r.drawShare, n)
                }
                val drawn = LinkedHashMap<Stratum, CardCell>()
                byDrawn[i].orEmpty().forEach { r ->
                    if (r.drawShare <= 0) return@forEach
                    val n = trials.count { it.stratum == r.stratum.name && draws(it, passcode, canon) }
                    drawn[r.stratum] = CardCell(r.estimate, r.drawShare, n)
                }
                if (cells.isEmpty()) null
                else CardResult(passcode, bench.roleNames[spec.roles[i]], bench.decks.own(main)[i], cells, drawn)
            }.sortedWith(compareBy<CardResult>({ bench.roleNames.indexOf(it.role) }, { -it.copies }))
            val pairs = ratings.shownPairs.map { p ->
                val a = bench.own[p.pair.a]
                val b = bench.own[p.pair.b]
                PairResult(a, b, p.stratum, p.estimate, trials.count { it.stratum == p.stratum.name && holdsBoth(it, a, b, canon) })
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
                fitted = run.trialsRead,
                olderPlans = trials.count(bench::underOlderPlan),
                settled = ShootoutRun.STOP.read(ratings, run.strataInPlay()),
                noise = run.noise,
                steadiness = steadiness(run),
                inPlay = run.strataInPlay(),
            )
        }

        /**
         * Whether a hand [t] shows holds [card] in its opening five: a hand naming its turn's draw ([StoredTrial.sixth])
         * holds that copy as the draw; one kept before it counts every copy. [canon] reads a kept passcode as the card's
         * canonical one, so a trial kept before the pool knew an alternate artwork still counts (the red team, 2026-10).
         */
        fun opens(t: StoredTrial, card: Int, canon: (Int) -> Int = { it }): Boolean =
            if (t.kind == StoredTrial.COMPARE) opens(t.left, t.leftSixth, card, canon) || opens(t.right, t.rightSixth, card, canon)
            else opens(t.hand, t.sixth, card, canon)

        private fun opens(hand: List<Int>, sixth: Int?, card: Int, canon: (Int) -> Int): Boolean =
            hand.count { canon(it) == card } - (if (sixth != null && canon(sixth) == card) 1 else 0) > 0

        /** Whether a hand [t] shows has [card] as its turn's draw. */
        fun draws(t: StoredTrial, card: Int, canon: (Int) -> Int = { it }): Boolean {
            fun d(x: Int?) = x != null && canon(x) == card
            return if (t.kind == StoredTrial.COMPARE) d(t.leftSixth) || d(t.rightSixth) else d(t.sixth)
        }

        /** Whether a hand [t] shows holds both [a] and [b]. */
        fun holdsBoth(t: StoredTrial, a: Int, b: Int, canon: (Int) -> Int = { it }): Boolean =
            t.hands.any { h -> h.any { canon(it) == a } && h.any { canon(it) == b } }

        /** The person's newest hands read for [steadiness]: enough to say, cheap to read. */
        private const val STEADY_HANDS = 400

        /** The chance of the same answer twice, by the fit, averaged over the person's blind ratings; null under ten. */
        private fun steadiness(run: ShootoutRun): Double? {
            val rated = run.log.trials.filter { it.blind && it.kind == StoredTrial.RATE }.takeLast(STEADY_HANDS)
            val same = rated.mapNotNull { t ->
                (run.bench.proposal(t) as? Proposal.Rate)?.let { p -> run.predict(p).chances.sumOf { it * it } }
            }
            return if (same.size < 10) null else same.average()
        }

        /**
         * The kept trials [behind] a number, newest first. [kindOf] names a trial's kind of hand, for [Behind.Kind]: the
         * pairs of that kind — each of the person's trials Ai answered, and Ai's answer beside it.
         */
        fun trialsBehind(
            trials: List<StoredTrial>, behind: Behind, kindOf: (StoredTrial) -> String? = { null }, canon: (Int) -> Int = { it },
        ): List<StoredTrial> {
            if (behind is Behind.Kind) {
                val pairs = JudgedPair.all(trials, kindOf).filter { p ->
                    p.kind == behind.key && (if (behind.audits) p.person.mode == TeachModes.AUDIT else p.counts)
                }
                return pairs.flatMap { listOfNotNull(it.person, it.aiTrial) }.asReversed()
            }
            return trials.filter { t ->
                when (behind) {
                    is Behind.Card -> t.stratum == behind.stratum.name && opens(t, behind.card, canon)
                    is Behind.Drawn -> t.stratum == behind.stratum.name && draws(t, behind.card, canon)
                    is Behind.Pair -> t.stratum == behind.stratum.name && holdsBoth(t, behind.a, behind.b, canon)
                    is Behind.WinRate -> t.stratum == behind.stratum.name
                    Behind.All -> true
                    Behind.Ai -> t.judge == StoredTrial.AI
                    is Behind.Kind -> false
                }
            }.asReversed()
        }
    }
}
