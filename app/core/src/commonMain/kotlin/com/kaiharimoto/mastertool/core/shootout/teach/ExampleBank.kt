package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import kotlin.math.abs

/**
 * A hand in its situation: what similarity compares ([hand], a comparison's left), and what tells the same hand shown again
 * ([same]). Cards are canonical passcodes, one per copy; [draw] is the hand's turn's draw when it names one, and a
 * comparison's right hand is [other], with its draw [otherDraw].
 */
data class Situation(
    val stratum: Stratum,
    val hand: List<Int>,
    val opponent: List<Int>?,
    val draw: Int? = null,
    val other: List<Int>? = null,
    val otherDraw: Int? = null,
) {
    /**
     * Whether [o] is this very hand shown again — the picker's repeats, a hand in twenty — not merely a like one: the same
     * stratum, the same copies in each hand (a comparison's two in either order), the same turn's draw where both name one
     * (a trial kept before the draw was names none), and the same opponent hand. The person's answer to such a trial is
     * their answer to this hand, so it is never an example for it ([ExampleBank]), a pair shown one is never held out
     * ([JudgedPair.heldOut]), and the ablation never holds out the second answer ([Repeats]) — the red team, 2026-10.
     */
    fun same(o: Situation): Boolean {
        if (stratum != o.stratum || opponent?.sorted() != o.opponent?.sorted()) return false
        val mine = other
        val theirs = o.other
        if (mine == null || theirs == null) return mine == null && theirs == null && alike(hand, draw, o.hand, o.draw)
        return (alike(hand, draw, o.hand, o.draw) && alike(mine, otherDraw, theirs, o.otherDraw)) ||
            (alike(hand, draw, theirs, o.otherDraw) && alike(mine, otherDraw, o.hand, o.draw))
    }

    /** What two situations that are [same] always share: the key their candidates are found by. */
    internal val key: List<Any?>
        get() = listOf(stratum, opponent?.sorted(), listOfNotNull(hand.sorted(), other?.sorted()).sortedBy { it.joinToString(",") })

    companion object {
        /** A kept trial's situation; null when its stratum is not one this build knows. */
        fun of(t: StoredTrial): Situation? {
            val stratum = Stratum.entries.firstOrNull { it.name == t.stratum } ?: return null
            return if (t.kind == StoredTrial.COMPARE) Situation(stratum, t.left, t.opponent, t.leftSixth, t.right, t.rightSixth)
            else Situation(stratum, t.hand, t.opponent, t.sixth)
        }

        private fun alike(a: List<Int>, aDraw: Int?, b: List<Int>, bDraw: Int?): Boolean =
            a.sorted() == b.sorted() && (aDraw == null || bDraw == null || aDraw == bDraw)
    }
}

/**
 * The person's answers to a hand they had answered before (the picker's repeats; S.md §3): a second answer to the same hand
 * ([Situation.same]) measures the person against themselves, never Ai afresh — the first answer may have been shown to Ai as
 * an example, written into the rubric or noted.
 */
object Repeats {
    /** The ids of the person's answers in [trials] whose hand they had answered before. */
    fun of(trials: List<StoredTrial>): Set<String> {
        val seen = HashMap<List<Any?>, MutableList<Pair<Situation, Long>>>()
        val out = HashSet<String>()
        for (t in trials.filter { it.judge == StoredTrial.PERSON && it.given != null }.sortedBy { it.at }) {
            val s = Situation.of(t) ?: continue
            val earlier = seen.getOrPut(s.key) { ArrayList() }
            if (earlier.any { (e, at) -> at < t.at && s.same(e) }) out += t.id
            earlier += s to t.at
        }
        return out
    }
}

/**
 * How alike two hands are for judging (S.md §6½ "The example bank": by cards, roles, turn and the opponent's
 * interaction), from 0 to 1, symmetric, 1 for the same hand in the same situation:
 * - **cards** (0.4): the weighted Jaccard of the two hands' copies — the share of copies they hold in common;
 * - **roles** (0.2): how alike their mix of roles is (starters, hand traps, bricks…), one less the distance between the shares;
 * - **turn** (0.2): the same stratum 1, the same turn in the other game 0.7, the other turn 0;
 * - **the opponent** (0.2): half how alike their counts of interaction are, half how alike their hands are; the deck
 *   alone has none, and two hands with no opponent are alike in that.
 */
class Similarity(private val roleOf: (Int) -> String?, private val interaction: Set<Int>) {

    fun of(a: Situation, b: Situation): Double =
        CARDS * jaccard(a.hand, b.hand) + ROLES * roles(a.hand, b.hand) + TURN * turn(a.stratum, b.stratum) + OPPONENT * opponent(a.opponent, b.opponent)

    private fun roles(a: List<Int>, b: List<Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return if (a.isEmpty() && b.isEmpty()) 1.0 else 0.0
        val ra = a.groupingBy { roleOf(it) ?: "" }.eachCount()
        val rb = b.groupingBy { roleOf(it) ?: "" }.eachCount()
        val distance = (ra.keys + rb.keys).sumOf { k -> abs((ra[k] ?: 0).toDouble() / a.size - (rb[k] ?: 0).toDouble() / b.size) }
        return 1.0 - distance / 2
    }

    private fun opponent(a: List<Int>?, b: List<Int>?): Double {
        if (a == null || b == null) return if (a == null && b == null) 1.0 else 0.0
        val ia = a.count { it in interaction }
        val ib = b.count { it in interaction }
        val alike = 1.0 - abs(ia - ib).toDouble() / maxOf(ia, ib, 1)
        return 0.5 * alike + 0.5 * jaccard(a, b)
    }

    companion object {
        const val CARDS = 0.4
        const val ROLES = 0.2
        const val TURN = 0.2
        const val OPPONENT = 0.2

        /** Σ min / Σ max over the copies: two hands sharing every copy are 1, sharing none 0. */
        fun jaccard(a: List<Int>, b: List<Int>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val ca = a.groupingBy { it }.eachCount()
            val cb = b.groupingBy { it }.eachCount()
            var min = 0
            var max = 0
            for (k in ca.keys + cb.keys) {
                val x = ca[k] ?: 0
                val y = cb[k] ?: 0
                min += minOf(x, y)
                max += maxOf(x, y)
            }
            return if (max == 0) 0.0 else min.toDouble() / max
        }

        fun turn(a: Stratum, b: Stratum): Double = when {
            a == b -> 1.0
            a.goingFirst == b.goingFirst && a.alone == b.alone -> 0.7
            else -> 0.0
        }
    }
}

/** One of the person's judged trials shown to Ai, how like the new hand it is, and the person's notes on it. */
class Example(val trial: StoredTrial, val similarity: Double, val notes: List<TrialNote>)

/**
 * The example bank (S.md §6½): every trial the person judged, and, for a new hand, the [k] most like it. A trial is an
 * example only once it is the person's, given blind (Ai's own answers never teach Ai, and neither do the person's after
 * seeing Ai's, which echo Ai back and flatter its agreement — the red team, 2026-10), and only if it was answered before [asOf] —
 * so the bank handed to Ai for a hand never holds that hand's own answer, nor anything the person said after it. Nor an
 * answer to the very same hand shown before ([Situation.same]): a repeat's first answer ranked first in the bank, handing
 * Ai the person's answer to the hand it was judging (the red team, 2026-10). That is what lets the confidence score be
 * measured honestly on what Ai never learned from.
 */
object ExampleBank {

    const val K = 6

    fun nearest(
        target: Situation,
        trials: List<StoredTrial>,
        similarity: Similarity,
        notes: List<TrialNote> = emptyList(),
        k: Int = K,
        asOf: Long = Long.MAX_VALUE,
        exclude: Set<String> = emptySet(),
    ): List<Example> {
        val byTrial = notes.filter { it.at < asOf }.groupBy { it.trial }
        return trials.asSequence()
            .filter { it.blind && it.id !in exclude && it.at < asOf }
            .filter { (it.kind == StoredTrial.RATE && it.answer != null) || (it.kind == StoredTrial.COMPARE && it.prefer != null) }
            .mapNotNull { t -> Situation.of(t)?.takeIf { !target.same(it) }?.let { Example(t, similarity.of(target, it), byTrial[t.id].orEmpty()) } }
            .sortedWith(compareByDescending<Example> { it.similarity }.thenByDescending { it.trial.at })
            .take(k)
            .toList()
    }
}
