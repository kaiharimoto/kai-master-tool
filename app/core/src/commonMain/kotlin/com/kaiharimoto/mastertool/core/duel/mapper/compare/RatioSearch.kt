package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import kotlin.math.roundToInt

/**
 * The ratios searched (Phase G part two, G2.4; `docs/phases/G2.md` §8): one more copy of one engine card for one fewer of
 * another, each such swap compared with the deck as it is on the same hands ([VersionCompare]) — with the field's
 * interruptions in the other seat's hand at their shares ([FieldHold]), so a ratio that bricks less through Ash and Droll is
 * worth what the field makes it — and called by Holm's step-down at 95 % over every swap compared, so a search of a dozen
 * swaps calls one by chance at most one time in twenty.
 *
 * Swaps are between engine cards only: the goldfish cannot value a hand trap of your own with no opponent to answer
 * ([CoverageGuard]), so cutting one for an engine card would always look like a gain. Every swap gets the same [HANDS],
 * fixed before the search runs; nothing is added after a verdict.
 */
object RatioSearch {
    /** Swaps a search asks by default: each costs as much as one comparison. */
    const val MOST = 12

    /** Hands each swap is compared on. */
    const val HANDS = 400

    /** The family-wise error over the swaps compared. */
    const val ALPHA = ShootoutResults.CALL_ALPHA

    /** One more copy of [add] for one fewer of [cut] (canonical passcodes). */
    data class Swap(val add: Int, val cut: Int) {
        val change: DeckChange get() = DeckChange(out = cut, into = add)
    }

    /**
     * The swaps worth asking in [deck], at most [most]: one more of an engine card it plays fewer than [allowed] copies of, for
     * one fewer of another engine card. The classic ratio questions first — the third copy of one against the second of
     * another — then by the deck's order.
     */
    fun swaps(deck: GoldfishDeck, kit: GoldfishKit, allowed: (Int) -> Int = { 3 }, most: Int = MOST): List<Swap> {
        val main = deck.main.map(kit::canonical)
        val engine = StarterTable.engine(main, kit)
        val copies = main.groupingBy { it }.eachCount()
        val adds = engine.filter { (copies[it] ?: 0) < allowed(it).coerceAtMost(3) }
        val cuts = engine.filter { (copies[it] ?: 0) >= 1 }
        return adds.flatMap { a -> cuts.filter { it != a }.map { c -> Swap(a, c) } }
            .sortedWith(compareByDescending<Swap> { (copies[it.cut] ?: 0) - (copies[it.add] ?: 0) }.thenBy { it.add }.thenBy { it.cut })
            .take(most)
    }

    /** One swap compared: its [comparison], or why it was not ([refused]). */
    data class Step(val swap: Swap, val comparison: Comparison?, val refused: String? = null) {
        /** Whether it ran on every hand it was given: only those are called. */
        val complete: Boolean get() = comparison != null && !comparison.stopped && comparison.guard?.ok != false && comparison.paired.hands > 0
    }

    enum class Call { BETTER, WORSE, ALIKE, UNSURE, NOT_RUN }

    /**
     * A search: each [steps]'s [calls], the [ask], the [hands] each swap was given, what the other seat held, and how many swaps
     * were [asked] — the family Holm calls over, a search stopped part way included.
     */
    data class Result(
        val steps: List<Step>,
        val calls: List<Call>,
        val ask: String,
        val hands: Int,
        val first: Boolean,
        val seed: Long,
        val held: FieldHold?,
        val stopped: Boolean,
        val asked: Int = steps.size,
    ) {
        /** The swaps called better, the clearest first. */
        val better: List<Step> get() = steps.indices.filter { calls[it] == Call.BETTER }.map { steps[it] }.sortedByDescending { it.comparison!!.paired.difference }
    }

    /**
     * [deck] against each of [swaps] on [hands] hands going [first] from [seed], asked [ask], the other seat holding [against]
     * (null: nothing). [done] is told after each swap, with how many are done. A swap the coverage guard refuses is said and
     * not run; [stop] ends the search between and inside swaps, and what was compared is kept.
     */
    suspend fun run(
        deck: GoldfishDeck,
        swaps: List<Swap>,
        ask: CompareAsk,
        kit: GoldfishKit,
        cache: MapCache = MapCache(),
        against: FieldHold? = null,
        first: Boolean = true,
        seed: Long = 1L,
        hands: Int = HANDS,
        budget: Int = MapSearch.DEFAULT_BUDGET,
        workers: Int = MapWork.workers(),
        stop: () -> Boolean = { false },
        done: (Step, Int, Int) -> Unit = { _, _, _ -> },
    ): Result {
        val base = GoldfishDeck(deck.main.map(kit::canonical), deck.extra.map(kit::canonical), id = deck.id, name = deck.name)
        val steps = ArrayList<Step>()
        var stopped = false
        for ((i, swap) in swaps.withIndex()) {
            if (stop()) { stopped = true; break }
            val b = Variants.apply(base, listOf(swap.change)) { false }
            val step = if (b == null) {
                Step(swap, null, "the deck does not hold a copy to cut")
            } else {
                val setup = CompareSetup(base, b, ask, first, seed, batch = hands, most = hands, budget = budget, recheck = 1, sequential = false, against = against)
                val c = VersionCompare.run(setup, kit, cache, workers, stop)
                Step(swap, c, c.guard?.takeIf { !it.ok }?.let { g -> if (g.unwritten.isNotEmpty()) "a card in it is not written" else "nothing differs" })
            }
            steps += step
            done(step, i + 1, swaps.size)
            if (step.comparison?.stopped == true) { stopped = true; break }
        }
        return Result(steps, calls(steps, swaps.size), ask.name, hands, first, seed, against, stopped, asked = swaps.size)
    }

    /**
     * Each step's call: Holm's step-down at [ALPHA] over the [asked] swaps — the exact McNemar p of every step that ran whole,
     * and p = 1 for a swap not compared whole (refused, cut short, or never reached after a Stop), so the family of swaps asked
     * is held to one false call in twenty however far the search went (the red team, R6: stopped after one promising swap, it
     * was called alone). A step it keeps is better or worse by the sign of its difference. The rest are alike when the
     * interval lies within a point, else unsure; a step that did not run whole is not called.
     */
    fun calls(steps: List<Step>, asked: Int = steps.size): List<Call> {
        val pValues = steps.map { s -> if (s.complete) s.comparison!!.paired.pValue else 1.0 } +
            List((asked - steps.size).coerceAtLeast(0)) { 1.0 }
        val kept = ShootoutResults.holm(pValues, ALPHA)
        return steps.indices.map { i ->
            if (!steps[i].complete) return@map Call.NOT_RUN
            val p = steps[i].comparison!!.paired
            val (lo, hi) = p.interval
            when {
                kept[i] && p.difference > 0 -> Call.BETTER
                kept[i] && p.difference < 0 -> Call.WORSE
                lo >= -PairedMath.CLOSE && hi <= PairedMath.CLOSE -> Call.ALIKE
                else -> Call.UNSURE
            }
        }
    }

    /** A swap in words: "+1 Pond Caller, −1 Pond Net". */
    fun swapWords(s: Swap, name: (Int) -> String): String = "+1 ${name(s.add)}, −1 ${name(s.cut)}"

    /** A step's numbers: "+3.1 points (0.4 to 5.8) · p 0.004". */
    fun numbers(step: Step): String {
        val p = step.comparison?.paired ?: return step.refused ?: "not compared"
        val (lo, hi) = p.interval
        return "${points(p.difference)} points (${points(lo)} to ${points(hi)}) · p ${pv(p.pValue)}"
    }

    /** What the other seat held, in a sentence. */
    fun heldWords(h: FieldHold?, name: (Int) -> String): String = when {
        h == null -> "The other seat held nothing: the goldfish alone."
        else -> buildString {
            append("The other seat held one of the field's interruptions on ${pct(h.chance)} of hands")
            if (h.sets.isNotEmpty()) append(" (").append(h.sets.joinToString(", ") { "${name(it.card)} ${pct(it.chance)}" }).append(")")
            append(".")
            if (h.unwritten.isNotEmpty()) {
                append(" Not written, so held as nothing: ").append(h.unwritten.joinToString(", ") { "${name(it.card)} (${pct(it.chance)} of hands)" }).append(".")
            }
        }
    }

    /** The search in words, as the page and Ai say it. */
    fun words(r: Result, name: (Int) -> String): String = buildString {
        val ran = r.steps.count { it.complete }
        append("${r.steps.size} swap${if (r.steps.size == 1) "" else "s"} of the engine's ratios against the deck as it is, ${r.hands} hands each going ${if (r.first) "first" else "second"}, asked ${r.ask}. ")
        append(heldWords(r.held, name))
        append(" Called by Holm's step-down at 95 % over the ${r.asked} asked, $ran compared whole (the rest count as no evidence).")
        r.steps.forEachIndexed { i, s ->
            append("\n- ").append(swapWords(s.swap, name)).append(": ").append(callWords(r.calls[i])).append(" — ").append(numbers(s))
        }
        if (r.better.isEmpty() && ran > 0) append("\nNo swap is called better: the ratios as they are hold up on these hands.")
        if (r.stopped) append("\nStopped before the end: what was compared is kept, and only it is called — among every swap asked.")
    }

    fun callWords(c: Call): String = when (c) {
        Call.BETTER -> "better"
        Call.WORSE -> "worse"
        Call.ALIKE -> "alike"
        Call.UNSURE -> "not sure"
        Call.NOT_RUN -> "not called"
    }

    private fun points(x: Double): String = ((x * 1000).roundToInt() / 10.0).let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }

    private fun pct(x: Double): String = "${(x * 100).roundToInt()} %"

    private fun pv(p: Double): String = when {
        p < 0.001 -> "< 0.001"
        else -> ((p * 1000).roundToInt() / 1000.0).toString()
    }
}
