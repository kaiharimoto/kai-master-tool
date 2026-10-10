package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishReduce
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapPlan
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperRun
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import kotlin.time.TimeSource

/*
 * "Is B better than A?" on the same hands (Phase G, G2, `docs/phases/G.md` §G.2): the Mapper run over two versions of a deck,
 * hand k of each dealt from the same keys, compared hand by hand. It stops as soon as the answer is known — the interval left
 * zero, or it lies within a point — and says which hands changed, so each can be opened and looked at.
 *
 * A comparison is only as fair as the scripts it plays with (G4): a card the engine does not know is played as inert, so
 * cutting a written card for an unwritten one always looks like a loss. [CoverageGuard] refuses those, and names a swap the
 * goldfish cannot value (a hand trap with no opponent) as a question for a stress test or Shootout instead.
 */

/** What a hand is asked: whether it reaches a board that [passes], named for the person. */
class CompareAsk(val name: String, val passes: (BoardTraits) -> Boolean) {
    companion object {
        /** At least [n] interruptions: board depth (mockup D). */
        fun interruptions(n: Int): CompareAsk = CompareAsk(if (n == 1) "at least 1 interruption" else "at least $n interruptions") { it.interruptions >= n }

        /** A preset's filters (its weights rank boards, and say nothing about whether one passes). */
        fun of(preset: BoardPreset): CompareAsk = CompareAsk(preset.name.ifBlank { "the ask" }) { t -> preset.filters.all { it.passes(t) } }

        /** Any board with something on it: a body or a card set. */
        val ANY: CompareAsk = CompareAsk("any board") { it.bodies + it.set + it.interruptions > 0 }
    }
}

/**
 * A change to a deck that saves nothing (G2): [copies] of [out] cut, [copies] of [into] added, or both — a swap. Canonical
 * passcodes. A change that cuts a card the deck does not hold that many of is refused by [Variants.apply]. In a swap within
 * one section each new copy takes the place of a cut one in the deal ([GoldfishDeck.keyAs]), so a hand differs from the
 * deck's own only where it held that copy.
 */
data class DeckChange(val out: Int? = null, val into: Int? = null, val copies: Int = 1) {
    init { require(out != null || into != null) { "a change cuts a card, adds one, or both" } }
}

object Variants {
    /**
     * [deck] with [changes] made, each to the Main Deck or the Extra Deck by where its card belongs ([isExtra]); null when a
     * change cuts more copies than the deck holds. The variant keeps [deck]'s id and name, and has no print of its own.
     */
    fun apply(deck: GoldfishDeck, changes: List<DeckChange>, isExtra: (Int) -> Boolean): GoldfishDeck? {
        val main = deck.main.toMutableList()
        val keys = (deck.keyAs?.takeIf { it.size == deck.main.size } ?: deck.main).toMutableList()
        val extra = deck.extra.toMutableList()
        for (c in changes) {
            val out = c.out
            val into = c.into
            if (out != null && into != null && !isExtra(out) && !isExtra(into)) {
                // A swap in the Main Deck: each new copy dealt in a cut copy's place.
                repeat(c.copies) {
                    val i = main.lastIndexOf(out)
                    if (i < 0) return null
                    main[i] = into
                }
                continue
            }
            if (out != null) {
                if (isExtra(out)) repeat(c.copies) { if (!extra.remove(out)) return null }
                else repeat(c.copies) {
                    val i = main.lastIndexOf(out)
                    if (i < 0) return null
                    main.removeAt(i)
                    keys.removeAt(i)
                }
            }
            if (into != null) repeat(c.copies) {
                if (isExtra(into)) extra += into else { main += into; keys += into }
            }
        }
        return GoldfishDeck(main, extra, id = deck.id, name = deck.name, keyAs = keys.takeIf { it != main })
    }

    /** What [b] has that [a] has not ([Diff.added]) and the reverse ([Diff.removed]), each by card and copies, Main and Extra Deck together. */
    fun diff(a: GoldfishDeck, b: GoldfishDeck, canonical: (Int) -> Int = { it }): Diff {
        val ca = (a.main + a.extra).map(canonical).groupingBy { it }.eachCount()
        val cb = (b.main + b.extra).map(canonical).groupingBy { it }.eachCount()
        val removed = ca.mapNotNull { (k, n) -> (n - (cb[k] ?: 0)).takeIf { it > 0 }?.let { k to it } }.toMap()
        val added = cb.mapNotNull { (k, n) -> (n - (ca[k] ?: 0)).takeIf { it > 0 }?.let { k to it } }.toMap()
        return Diff(removed, added)
    }

    data class Diff(val removed: Map<Int, Int>, val added: Map<Int, Int>) {
        val cards: Set<Int> get() = removed.keys + added.keys
        val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()
    }
}

/**
 * The coverage guard (G4): what a comparison of [a] and [b] can and cannot say, before it runs.
 * - A card that differs and is played as inert, but is not a blank (something in play could pick it, so it would matter),
 *   is [unwritten]: the comparison is refused until its effect is written — Write these.
 * - Engine cut for non-engine, or the reverse, is a [question]: the goldfish cannot value a hand trap with no opponent.
 */
object CoverageGuard {
    data class Check(
        /** Differing cards that are inert but not blanks: the comparison would be biased against them. */
        val unwritten: List<Int>,
        /** What the comparison cannot value, in words. */
        val questions: List<String>,
        val diff: Variants.Diff,
    ) {
        /** Whether the comparison may run. */
        val ok: Boolean get() = unwritten.isEmpty() && !diff.isEmpty
    }

    fun check(a: GoldfishDeck, b: GoldfishDeck, kit: GoldfishKit): Check {
        val diff = Variants.diff(a, b, kit::canonical)
        val reduceA = GoldfishReduce(kit, GoldfishDeck(a.main.map(kit::canonical), a.extra.map(kit::canonical)), EndBoard("compare", "compare", ""))
        val reduceB = GoldfishReduce(kit, GoldfishDeck(b.main.map(kit::canonical), b.extra.map(kit::canonical)), EndBoard("compare", "compare", ""))
        fun unwritten(c: Int, r: GoldfishReduce) = c != GoldfishKit.BLANK && kit.inert(c) && !r.blank(c)
        val bad = (diff.removed.keys.filter { unwritten(it, reduceA) } + diff.added.keys.filter { unwritten(it, reduceB) }).distinct().sorted()
        val engineA = StarterTable.engine(a.main, kit).toSet()
        val engineB = StarterTable.engine(b.main, kit).toSet()
        val outEngine = diff.removed.keys.filter { it in engineA }
        val inEngine = diff.added.keys.filter { it in engineB }
        val outOther = diff.removed.keys.filter { it !in engineA && it != GoldfishKit.BLANK }
        val inOther = diff.added.keys.filter { it !in engineB && it != GoldfishKit.BLANK }
        val questions = buildList {
            if (outEngine.isNotEmpty() && inEngine.isEmpty() && inOther.isNotEmpty()) {
                add("The goldfish cannot value ${inOther.joinToString(", ") { kit.name(it) }} with no opponent: what ${if (inOther.size == 1) "it is" else "they are"} worth in that slot is a stress test or a Shootout question.")
            }
            if (inEngine.isNotEmpty() && outEngine.isEmpty() && outOther.isNotEmpty()) {
                add("The goldfish cannot value ${outOther.joinToString(", ") { kit.name(it) }} with no opponent: what is lost by cutting ${if (outOther.size == 1) "it" else "them"} is a stress test or a Shootout question.")
            }
        }
        return Check(bad, questions, diff)
    }
}

/** A hand whose answer changed between the versions: hand [k] of the run, as each version dealt it, and its answers. */
data class ChangedHand(val k: Int, val a: HandAnswer, val b: HandAnswer, val handA: List<Int>, val handB: List<Int>)

/** How far a comparison has got: [hands] paired so far, out of at most [most], and the counts so far. */
data class CompareProgress(val hands: Int, val most: Int, val paired: Paired)

/**
 * What a comparison asks: [a] against [b], going [first], hand k dealt from [seed] by keyed dealing; [batch] hands at a time,
 * stopping once [Paired.settled] or at [most] hands; each map bounded by [budget] and [depth]. The discordant hands left
 * undecided are searched again at [recheck] times the budget.
 */
data class CompareSetup(
    val a: GoldfishDeck,
    val b: GoldfishDeck,
    val ask: CompareAsk,
    val first: Boolean = true,
    val seed: Long = 1L,
    val batch: Int = 200,
    val most: Int = 4_000,
    val budget: Int = MapSearch.DEFAULT_BUDGET,
    val depth: Int = MapSearch.DEFAULT_DEPTH,
    val recheck: Int = 5,
    val sequential: Boolean = true,
)

/** A comparison's answer: the pairs, the hands that changed, whether it settled or was stopped, and what it cost. */
data class Comparison(
    val paired: Paired,
    val changed: List<ChangedHand>,
    val settled: Boolean,
    val stopped: Boolean,
    /** Maps searched for each version, and those a cache answered. */
    val mappedA: Int,
    val mappedB: Int,
    val cachedA: Int,
    val cachedB: Int,
    val moves: Long,
    val ms: Long,
    val rechecked: Int = 0,
    val guard: CoverageGuard.Check? = null,
    /** Every kind of board each version reached, for "boards lost" ([Ablation]). */
    val kindsA: Set<BoardTraits> = emptySet(),
    val kindsB: Set<BoardTraits> = emptySet(),
) {
    /** Kinds of board A reached that no hand of B did. */
    val kindsLost: Set<BoardTraits> get() = kindsA - kindsB
}

object VersionCompare {
    /**
     * Compares [setup]'s two versions with [kit] on [workers] workers, the maps kept in [cache] (one comparison's misses are
     * the next one's hits, and a version the page already ran costs nothing). [stop] ends it between batches and between
     * engine moves; [progress] is told after each batch. Refused (an empty comparison carrying the [CoverageGuard.Check])
     * when the guard says the comparison would be biased.
     */
    suspend fun run(
        setup: CompareSetup,
        kit: GoldfishKit,
        cache: MapCache = MapCache(),
        workers: Int = MapWork.workers(),
        stop: () -> Boolean = { false },
        progress: (CompareProgress) -> Unit = {},
    ): Comparison = go(setup, kit, cache, stop, progress) { plan, deals, halt ->
        if (workers <= 1) MapWork.here(plan, deals, halt) else MapWork.all(plan, deals, workers, halt)
    }

    private suspend fun go(
        setup: CompareSetup,
        kit: GoldfishKit,
        cache: MapCache,
        stop: () -> Boolean,
        progress: (CompareProgress) -> Unit,
        mapAll: suspend (MapPlan, List<MapDeal>, () -> Boolean) -> List<com.kaiharimoto.mastertool.core.duel.mapper.MapDone?>,
    ): Comparison {
        val started = TimeSource.Monotonic.markNow()
        val guard = CoverageGuard.check(setup.a, setup.b, kit)
        if (!guard.ok) return Comparison(Paired(), emptyList(), settled = false, stopped = false, 0, 0, 0, 0, 0L, 0L, guard = guard)
        val sideA = Side(setup.a, setup, kit, cache)
        val sideB = Side(setup.b, setup, kit, cache)
        var paired = Paired()
        val changed = ArrayList<ChangedHand>()
        var start = 0
        var stopped = false
        var rechecked = 0
        val most = setup.most.coerceIn(1, Mapper.MOST_HANDS)
        while (start < most) {
            if (stop()) { stopped = true; break }
            val n = minOf(setup.batch.coerceAtLeast(1), most - start)
            val a = sideA.batch(start, n, stop, mapAll)
            val b = sideB.batch(start, n, stop, mapAll)
            if (a == null || b == null) { stopped = true; break }
            // The discordant hands left undecided, searched again with more room: an answer either way settles the pair.
            val again = (0 until n).filter { i -> a.answers[i] != b.answers[i] && (a.answers[i] == HandAnswer.UNDECIDED || b.answers[i] == HandAnswer.UNDECIDED) }
            if (again.isNotEmpty() && setup.recheck > 1) {
                rechecked += again.size
                sideA.recheck(a, again.filter { a.answers[it] == HandAnswer.UNDECIDED }, stop, mapAll)
                sideB.recheck(b, again.filter { b.answers[it] == HandAnswer.UNDECIDED }, stop, mapAll)
            }
            paired += Paired.of(a.answers, b.answers)
            for (i in 0 until n) {
                if ((a.answers[i] == HandAnswer.YES) != (b.answers[i] == HandAnswer.YES)) {
                    changed += ChangedHand(start + i, a.answers[i], b.answers[i], a.hands[i], b.hands[i])
                }
            }
            start += n
            progress(CompareProgress(start, most, paired))
            if (setup.sequential && paired.settled()) break
        }
        return Comparison(
            paired = paired,
            changed = changed,
            settled = paired.settled(),
            stopped = stopped,
            mappedA = sideA.mapped,
            mappedB = sideB.mapped,
            cachedA = sideA.cached,
            cachedB = sideB.cached,
            moves = sideA.moves + sideB.moves,
            ms = started.elapsedNow().inWholeMilliseconds,
            rechecked = rechecked,
            guard = guard,
            kindsA = sideA.kinds,
            kindsB = sideB.kinds,
        )
    }

    /** One batch of one version: each hand's answer and its cards, and the part each hand was. */
    private class Batch(val answers: MutableList<HandAnswer>, val hands: List<List<Int>>, val parts: IntArray, val deals: List<MapDeal>)

    /** One version as the comparison maps it: its plan's constants, the cache's key for it, and what it cost. */
    private class Side(val deck: GoldfishDeck, val setup: CompareSetup, val kit: GoldfishKit, val cache: MapCache) {
        val main = deck.main.map(kit::canonical)
        val extra = deck.extra.map(kit::canonical)
        private val keyAs = deck.keyAs?.map(kit::canonical)
        private val maps = MapPlan(main, extra, kit, setup.budget, setup.depth)
        private val reduce = GoldfishReduce(kit, GoldfishDeck(main, extra), EndBoard("mapper", "mapper", ""))
        /** What a map of this version depends on beyond the hand: the cards anything could pick, the Extra Deck, the scripts. */
        private val deckKey = MapCache.deckKey(main.filterNot(reduce::blank), extra, Mapper.scripts(GoldfishDeck(main, extra), kit), setup.first)
        var mapped = 0
        var cached = 0
        var moves = 0L
        val kinds = HashSet<BoardTraits>()

        suspend fun batch(
            start: Int,
            n: Int,
            stop: () -> Boolean,
            mapAll: suspend (MapPlan, List<MapDeal>, () -> Boolean) -> List<com.kaiharimoto.mastertool.core.duel.mapper.MapDone?>,
        ): Batch? {
            val plan = Mapper.Plan(MapperSetup(deck, setup.first, n, setup.seed, setup.budget, setup.depth, GoldfishHands.DEAL, start = start), kit)
            val keys = plan.deals.map { d -> MapCache.handKey(reduce.reduce(d.hand + d.fodder), d.first, if (maps.ordered) d.seed else null, setup.budget, setup.depth) }
            val results = arrayOfNulls<MapCache.Entry>(plan.deals.size)
            val misses = ArrayList<Int>()
            for (i in plan.deals.indices) {
                val hit = cache[deckKey, keys[i]]
                if (hit != null) { results[i] = hit; cached++ } else misses += i
            }
            if (misses.isNotEmpty()) {
                val done = mapAll(maps, misses.map { plan.deals[it] }, stop)
                for ((j, i) in misses.withIndex()) {
                    val d = done[j] ?: return null
                    val e = MapCache.Entry(d.ends.map { it.traits.copy(through = emptyMap()) }.distinct(), d.complete, d.moves)
                    // A map cut short by Stop says nothing it could not finish: it is not kept.
                    if (d.complete || !stop()) cache.put(deckKey, keys[i], e)
                    results[i] = e
                    mapped++
                    moves += d.moves
                }
            }
            val answers = MutableList(n) { k -> answer(results[plan.dealt[k]]!!) }
            results.forEach { r -> r?.traits?.let { kinds += it } }
            val hands = (0 until n).map { k -> GoldfishHands.hand(main, setup.seed, start + k, setup.first, keyAs = keyAs) }
            return Batch(answers, hands, plan.dealt, plan.deals)
        }

        /** The hands [which] of [b] (each undecided here) mapped again with [CompareSetup.recheck] times the budget. */
        suspend fun recheck(
            b: Batch,
            which: List<Int>,
            stop: () -> Boolean,
            mapAll: suspend (MapPlan, List<MapDeal>, () -> Boolean) -> List<com.kaiharimoto.mastertool.core.duel.mapper.MapDone?>,
        ) {
            if (which.isEmpty()) return
            val budget = setup.budget * setup.recheck
            val wider = MapPlan(main, extra, kit, budget, setup.depth)
            val parts = which.map { b.parts[it] }.distinct()
            val keys = parts.map { p -> MapCache.handKey(reduce.reduce(b.deals[p].hand + b.deals[p].fodder), setup.first, if (wider.ordered) b.deals[p].seed else null, budget, setup.depth) }
            val results = HashMap<Int, MapCache.Entry>()
            val misses = ArrayList<Int>()
            parts.forEachIndexed { j, p -> cache[deckKey, keys[j]]?.let { results[p] = it; cached++ } ?: misses.add(j) }
            if (misses.isNotEmpty()) {
                val done = mapAll(wider, misses.map { b.deals[parts[it]] }, stop)
                misses.forEachIndexed { m, j ->
                    val d = done[m] ?: return@forEachIndexed
                    val e = MapCache.Entry(d.ends.map { it.traits.copy(through = emptyMap()) }.distinct(), d.complete, d.moves)
                    if (d.complete || !stop()) cache.put(deckKey, keys[j], e)
                    results[parts[j]] = e
                    mapped++
                    moves += d.moves
                }
            }
            which.forEach { i -> results[b.parts[i]]?.let { e -> b.answers[i] = answer(e); kinds += e.traits } }
        }

        private fun answer(e: MapCache.Entry): HandAnswer = when {
            e.traits.any(setup.ask.passes) -> HandAnswer.YES
            !e.complete -> HandAnswer.UNDECIDED
            else -> HandAnswer.NO
        }
    }

    /** Each hand's answer to [ask] in a kept [run], in deal order; a hand the run never reached is null. */
    fun answers(run: MapperRun, ask: CompareAsk): List<HandAnswer?> {
        val ok = BooleanArray(run.traits.size) { ask.passes(run.traits[it]) }
        return run.dealt.map { p ->
            if (p < 0) null else {
                val part = run.parts[p]
                when {
                    part.ends.any { ok[it] } -> HandAnswer.YES
                    !part.complete -> HandAnswer.UNDECIDED
                    else -> HandAnswer.NO
                }
            }
        }
    }
}

/**
 * Maps kept by what they depend on (G6's `MapCache`, device-only and in memory): a version's [deckKey] — the cards anything in
 * play could pick, the Extra Deck, the scripts, the side — and a hand's [handKey] — the cards dealt that are not blanks (its
 * engine part and the fodder a cost could take), the deck's order when a script reads it, the budget and depth. A blank changing in the deck changes neither, so swapping one blank for another
 * maps nothing, and comparing it comes out exactly even. Bounded: past [capacity] entries the oldest go.
 */
class MapCache(private val capacity: Int = 200_000) {
    /** A map as a comparison reads it: every kind of board it reached, whether it was complete, and its cost. */
    class Entry(val traits: List<BoardTraits>, val complete: Boolean, val moves: Int)

    private val entries = LinkedHashMap<String, Entry>()

    operator fun get(deck: String, hand: String): Entry? = entries["$deck#$hand"]

    fun put(deck: String, hand: String, e: Entry) {
        entries["$deck#$hand"] = e
        if (entries.size > capacity) {
            val oldest = entries.keys.iterator()
            repeat(entries.size - capacity) { oldest.next(); oldest.remove() }
        }
    }

    val size: Int get() = entries.size

    fun clear() = entries.clear()

    companion object {
        fun deckKey(pickable: List<Int>, extra: List<Int>, scripts: String, first: Boolean): String =
            "${pickable.sorted().joinToString(",")}|${extra.sorted().joinToString(",")}|$scripts|${if (first) 1 else 2}"

        fun handKey(engine: List<Int>, first: Boolean, seed: Long?, budget: Int, depth: Int): String =
            "${engine.sorted().joinToString(",")}|${if (first) 1 else 2}|${seed ?: "-"}|$budget|$depth"
    }
}
