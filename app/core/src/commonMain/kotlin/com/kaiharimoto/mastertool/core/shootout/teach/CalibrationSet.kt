package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import kotlin.random.Random

/**
 * The calibration set (S.md §6½, "the preset"): a fixed set of 24 to 40 hands that covers the matchup's kinds of hand
 * (with and without a starter, into interaction or none, first and second), judged blind by the person and then
 * answered blind by Ai as its first exam. After a deck change, a short set ([short]) re-earns every kind.
 *
 * Hands are dealt as a real shuffle would in each stratum the matchup deals, sorted into kinds, and taken in turn from
 * each kind, the hands the model is least sure of first ([uncertainty]): so the set covers the kinds evenly, and each
 * hand also teaches the ratings what they do not know yet.
 */
object CalibrationSet {
    const val MIN = 24
    const val MAX = 40
    const val SIZE = 32

    /** Hands per kind in a short set, after the decks change. */
    const val SHORT_PER_KIND = Trust.RECHECK

    /** Hands dealt per stratum to choose from. */
    const val DEALT = 300

    fun pick(
        bench: Bench,
        size: Int = SIZE,
        random: Random = Random(1),
        uncertainty: (Proposal.Rate) -> Double = { 0.0 },
    ): List<Proposal.Rate> = choose(bench, size.coerceIn(MIN, MAX), null, random, uncertainty)

    /** The short set: [SHORT_PER_KIND] hands of each kind that occurs. */
    fun short(bench: Bench, random: Random = Random(1), uncertainty: (Proposal.Rate) -> Double = { 0.0 }): List<Proposal.Rate> =
        choose(bench, Int.MAX_VALUE, SHORT_PER_KIND, random, uncertainty)

    private fun choose(bench: Bench, size: Int, perKind: Int?, random: Random, uncertainty: (Proposal.Rate) -> Double): List<Proposal.Rate> {
        val buckets = LinkedHashMap<String, MutableList<Proposal.Rate>>()
        HandKind.all(bench.alone).forEach { buckets[it.key] = mutableListOf() }
        val seen = HashSet<Pair<Hand, Hand?>>()
        for (stratum in bench.strata) repeat(DEALT) {
            val (hand, opp) = bench.decks.deal(stratum, random)
            if (!seen.add(hand to opp)) return@repeat
            val p = Proposal.Rate(hand, opp, stratum, Reason.CHOSEN)
            buckets.getValue(bench.kindOf(p).key) += p
        }
        val queues = buckets.values.filter { it.isNotEmpty() }.map { b -> ArrayDeque(b.sortedByDescending(uncertainty)) }
        val out = ArrayList<Proposal.Rate>()
        var round = 0
        while (out.size < size && queues.any { it.isNotEmpty() } && (perKind == null || round < perKind)) {
            for (q in queues) {
                if (out.size >= size) break
                q.removeFirstOrNull()?.let { out += it }
            }
            round++
        }
        return out
    }
}
