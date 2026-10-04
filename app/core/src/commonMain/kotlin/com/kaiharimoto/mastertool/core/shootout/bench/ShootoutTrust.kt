package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.math.Normal
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import com.kaiharimoto.mastertool.core.shootout.teach.TrustState
import kotlin.math.abs

/** One card's worth in one stratum with Ai's answers and without them (S.md §6½ "What Ai's answers moved"). */
class Moved(val card: Int, val stratum: Stratum, val with: Estimate, val without: Estimate) {
    /** How far Ai's answers moved it, in points. */
    val change: Double get() = with.value - without.value

    /** A change worth a look: past the card's own 80 % range without Ai, and at least [ShootoutTrust.FLAG_POINTS]. */
    val flagged: Boolean get() = abs(change) >= maxOf(ShootoutTrust.FLAG_POINTS, Normal.Z80 * without.sd)
}

/**
 * The trust panel per matchup (S.md §6½ "What the person sees"): the open kinds with their ranges and the audit record
 * ([state]); how much of the data is Ai's, raw and weighted by its measured accuracy; how Ai and the person after seeing
 * Ai lean and how much each counts; and what Ai's answers moved.
 */
class TrustReport(
    val state: TrustState,
    /** Ai's answers of all answers the model reads, and the same weighted by how much each counts. */
    val aiShare: Double,
    val aiWeighted: Double,
    /** One answer of Ai's against one of the person's blind ones, its lean in points, its noise in log-odds. */
    val aiWeight: Double,
    val aiLean: Double,
    val aiNoise: Double,
    /** The same for the person's answers after seeing Ai's; null with none. */
    val seenWeight: Double?,
    val seenLean: Double?,
    val moved: List<Moved>,
) {
    val flagged: List<Moved> get() = moved.filter { it.flagged }
}

object ShootoutTrust {
    /** The fewest points a change must be to be flagged. */
    const val FLAG_POINTS = 2.0

    fun read(run: ShootoutRun): TrustReport {
        val bench = run.bench
        val log = run.log
        val state = Trust.read(log.trials, log.trusted, bench.alone, bench.print) { bench.kindOf(it)?.key }
        val ai = run.answersOf(Bench.AI)
        val seen = run.answersOf(Bench.SEEN)
        val person = run.answersOf(Bench.PERSON)
        val all = (ai + seen + person).coerceAtLeast(1)
        val wAi = run.weightOf(Bench.AI)
        val wSeen = run.weightOf(Bench.SEEN)
        val weighted = ai * wAi / (person + ai * wAi + seen * wSeen).coerceAtLeast(1e-9)
        val moved = if (ai == 0) emptyList() else {
            val without = ShootoutRun(bench, log, withAi = false)
            val a = run.reporter.ratings(run.fit, run.modelTrials()).cards
            val b = without.reporter.ratings(without.fit, without.modelTrials()).cards.associateBy { it.card to it.stratum }
            a.mapNotNull { r ->
                val o = b[r.card to r.stratum] ?: return@mapNotNull null
                if (r.drawShare <= 0) return@mapNotNull null
                Moved(bench.own[r.card], r.stratum, r.estimate, o.estimate)
            }.sortedByDescending { abs(it.change) }
        }
        return TrustReport(
            state = state,
            aiShare = ai.toDouble() / all,
            aiWeighted = if (ai == 0) 0.0 else weighted,
            aiWeight = wAi,
            aiLean = run.leanOf(Bench.AI),
            aiNoise = run.noiseOf(Bench.AI),
            seenWeight = wSeen.takeIf { seen > 0 },
            seenLean = run.leanOf(Bench.SEEN).takeIf { seen > 0 },
            moved = moved,
        )
    }
}
