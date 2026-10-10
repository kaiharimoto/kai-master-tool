package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishReduce
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainGate
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/*
 * Over many hands (M.md §2.5): `Goldfish.run`'s twin. Hands dealt from a seed as the goldfish deals them, each mapped, and
 * counted by what its boards measure — "how often does a real hand reach a board like this", with a range. The training
 * learns mostly from these hands.
 *
 * A dealt hand's boards are nearly all its own: the same field with other cards left in hand is another board, so sixty
 * hands of the bench deck made 9,326 boards and 157 kinds of board by what they measure. A run keeps every hand's kinds
 * (its [MapperRun.traits]), and the library keeps each field's best board ([BoardLibrary.admits]: what a player sees, judged
 * by what is plainly better), so the library stays wide and small.
 */

/** What a run asks: a deck, a seat order, how many hands from which seed, and the bounds of each map. */
data class MapperSetup(
    val deck: GoldfishDeck,
    val first: Boolean = true,
    val hands: Int = Mapper.DESK_HANDS,
    val seed: Long = 1L,
    val budget: Int = MapSearch.DEFAULT_BUDGET,
    val depth: Int = MapSearch.DEFAULT_DEPTH,
    /** How hands are dealt ([GoldfishHands.DEAL]). */
    val deal: Int = GoldfishHands.DEAL,
)

/** How far a run has got: [done] of [total] maps, the hands they stand for, in [ms]. */
data class MapperProgress(val done: Int, val total: Int, val hands: Int, val ms: Long)

/**
 * One distinct hand of a run, mapped once: its [cards] (the engine part, sorted canonical passcodes), the cards dealt beside
 * it that do nothing ([fodder], left out of every board), how many of the run's hands it stands for, the kinds of board it
 * reaches ([ends]: indexes into [MapperRun.traits]), whether its map was complete and what it cost.
 */
@Serializable
data class RunPart(
    val cards: List<Int>,
    val fodder: List<Int> = emptyList(),
    val hands: Int = 1,
    val ends: List<Int> = emptyList(),
    val complete: Boolean = true,
    val moves: Int = 0,
)

/**
 * A run's counts, kept beside the library (`run.json`, `run-2nd.json`): which boards each dealt hand reaches. [deck] and
 * [library] are the deck's fingerprint and the trusted scripts' when it ran: either moving makes it [stale] — its counts
 * describe a deck that is not the one open.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MapperRun(
    val version: Int = 1,
    val deckId: String = "",
    val deck: String = "",
    val library: String = "",
    val first: Boolean = true,
    val hands: Int = 0,
    val seed: Long = 1L,
    val budget: Int = 0,
    /** The [BoardKey.VERSION] the boards were counted by. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val keys: Int = BoardKey.VERSION,
    /** Every kind of board a hand reached: what it measures, without stress results, each once, in a fixed order. */
    val traits: List<BoardTraits> = emptyList(),
    /** The boards the run added to the library (each new field's best), by key, sorted. */
    val added: List<String> = emptyList(),
    /** The distinct hands mapped, in the order of the first hand dealt as each. */
    val parts: List<RunPart> = emptyList(),
    /** For hand k of the deal, the index of its part; −1 for a hand left out because the run stopped first. */
    val dealt: List<Int> = emptyList(),
    val moves: Long = 0L,
    val ms: Long = 0L,
    val at: Long = 0L,
    /** Stopped before every hand was mapped: the hands left out are not in [hands]. */
    val stopped: Boolean = false,
    /** How its hands were dealt ([GoldfishHands.DEAL]); a run kept before keyed dealing is deal 1. */
    val deal: Int = 1,
) {
    /** Whether the run describes another deck or other scripts than [deck] and [library]. */
    fun stale(deck: String, library: String): Boolean = deck != this.deck || library != this.library || keys != BoardKey.VERSION

    /** The run under the deck's print now ([to]) when it was made under an earlier print of it ([from]); see `BoardLibrary.adopted`. */
    fun adopted(from: Set<String>, to: String): MapperRun = if (deck in from && to.isNotEmpty()) copy(deck = to) else this

    /** How many of the run's hands reach at least one board that [passes]. */
    fun reaching(passes: (BoardTraits) -> Boolean): Int {
        val ok = BooleanArray(traits.size) { passes(traits[it]) }
        return parts.sumOf { p -> if (p.ends.any { ok[it] }) p.hands else 0 }
    }

    /** The share of hands reaching at least one board that [passes], and its 95 % range. */
    fun share(passes: (BoardTraits) -> Boolean): Share = Share(reaching(passes), hands)

    /** The share of hands reaching a board that passes [preset]'s filters (its cards are the library's to judge). */
    fun share(preset: BoardPreset): Share = share { t -> preset.filters.all { it.passes(t) } }

    /**
     * The share of hands reaching a board at least as good as [t] on every trait where more is plainly better
     * ([BoardTraits.MORE_IS_BETTER]): "how often does a hand make at least this much".
     */
    fun atLeast(t: BoardTraits): Share = share { o -> BoardTraits.MORE_IS_BETTER.all { h -> (o[h] ?: 0.0) >= (t[h] ?: 0.0) } }

    /** Hands whose map was incomplete: they may reach more than the run says, so every share is at least what it says. */
    val incomplete: Int get() = parts.filterNot { it.complete }.sumOf { it.hands }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Read forgivingly; an unreadable file is null. */
        fun decode(text: String): MapperRun? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** [hits] of [of] hands, with the 95 % Wilson range of the share. */
data class Share(val hits: Int, val of: Int) {
    val share: Double get() = if (of == 0) 0.0 else hits.toDouble() / of
    val range: Pair<Double, Double> get() = TrainGate.wilson(share, of)
}

/**
 * The mapper over many dealt hands. Hand k is the goldfish's ([GoldfishHands]: the duel's riffle keyed by the seed and k), so
 * the same seed deals the same hands everywhere.
 * - **The fodder**: the cards of a hand played as inert are dealt beside it and left out of its boards, as the starter
 *   table's fodder is, so a board reads the same whichever blanks a hand held.
 * - **Hands with one engine part share a map** (the goldfish's reduction: its blanks set aside), while no script reads the
 *   Deck's order. The map is made from the first hand dealt as that part, fixed before any worker starts, so the library
 *   is the same however many threads ran. When a script reads the order every hand is its own map, its deck dealt by the
 *   hand's own seed.
 * - **Parallel and stoppable**, on [MapWork]: Stop keeps what was mapped and says the run stopped.
 */
object Mapper {
    /** Hands a run deals on the desk by default: a map costs many goldfish searches. */
    const val DESK_HANDS = 1_000

    /** Hands on a phone by default. */
    const val PHONE_HANDS = 200

    /** The most hands one run deals. */
    const val MOST_HANDS = 20_000

    /** The trusted scripts' fingerprint for [deck] ([BoardLibrary.library], [MapperRun.library]): what moving makes a run stale. */
    fun scripts(deck: GoldfishDeck, kit: GoldfishKit): String = kit.trust.library(deck.main.map(kit::canonical) + deck.extra.map(kit::canonical))

    /** Kinds of board in a fixed order: by each head in [BoardTraits.HEADS] order. */
    private val TRAIT_ORDER: Comparator<BoardTraits> = Comparator { a, b ->
        for (h in BoardTraits.HEADS) {
            val c = (a[h] ?: 0.0).compareTo(b[h] ?: 0.0)
            if (c != 0) return@Comparator c
        }
        0
    }

    /** The run's deals, worked out before any map: which hand is which part, and each part's deal. */
    class Plan(setup: MapperSetup, kit: GoldfishKit) {
        val main: List<Int> = setup.deck.main.map(kit::canonical)
        val extra: List<Int> = setup.deck.extra.map(kit::canonical)
        val maps = MapPlan(main, extra, kit, setup.budget, setup.depth)
        val hands: Int = setup.hands.coerceIn(1, MOST_HANDS)
        private val reduce = GoldfishReduce(kit, GoldfishDeck(main, extra), EndBoard("mapper", "mapper", ""))

        /** For each hand, its part; for each part, its deal and how many hands it stands for. */
        val dealt: IntArray = IntArray(hands)
        val deals: List<MapDeal>
        val counts: IntArray

        init {
            val byKey = HashMap<List<Int>, Int>()
            val out = ArrayList<MapDeal>()
            val n = ArrayList<Int>()
            for (k in 0 until hands) {
                val hand = GoldfishHands.hand(main, setup.seed, k, setup.first, setup.deal)
                val key = if (maps.ordered) null else reduce.reduce(hand)
                val known = key?.let { byKey[it] }
                if (known != null) {
                    dealt[k] = known
                    n[known] = n[known] + 1
                    continue
                }
                val fodder = hand.filter(kit::inert).sorted()
                val engine = hand.filterNot(kit::inert).sorted()
                val seed = if (maps.ordered) GoldfishHands.handSeed(setup.seed, k) else setup.seed
                dealt[k] = out.size
                key?.let { byKey[it] = out.size }
                out += MapDeal(engine, setup.first, seed, fodder)
                n += 1
            }
            deals = out
            counts = n.toIntArray()
        }
    }

    /**
     * Runs [setup] with [kit] into [library] on [workers] workers: the run's counts and the library they grew. [stop] ends it
     * between engine moves, keeping what was mapped; [progress] is told from the workers' threads.
     */
    suspend fun run(
        setup: MapperSetup,
        kit: GoldfishKit,
        library: BoardLibrary,
        workers: Int = MapWork.workers(),
        stop: () -> Boolean = { false },
        progress: (MapperProgress) -> Unit = {},
        now: Long = 0L,
    ): Pair<MapperRun, BoardLibrary> {
        val started = TimeSource.Monotonic.markNow()
        val plan = Plan(setup, kit)
        val total = plan.deals.size
        val dones = MapWork.all(plan.maps, plan.deals, workers, stop) { n ->
            progress(MapperProgress(n, total, 0, started.elapsedNow().inWholeMilliseconds))
        }
        return fold(setup, kit, plan, dones, library, started.elapsedNow().inWholeMilliseconds, now)
    }

    /** [run] on this thread, every map in order: what the tests hold the parallel run to. */
    fun runHere(setup: MapperSetup, kit: GoldfishKit, library: BoardLibrary, now: Long = 0L): Pair<MapperRun, BoardLibrary> {
        val started = TimeSource.Monotonic.markNow()
        val plan = Plan(setup, kit)
        val dones = MapWork.here(plan.maps, plan.deals)
        return fold(setup, kit, plan, dones, library, started.elapsedNow().inWholeMilliseconds, now)
    }

    private fun fold(
        setup: MapperSetup,
        kit: GoldfishKit,
        plan: Plan,
        dones: List<MapDone?>,
        library: BoardLibrary,
        ms: Long,
        at: Long,
    ): Pair<MapperRun, BoardLibrary> {
        val scripts = kit.trust.library(plan.main + plan.extra)
        // A library from another version of the deck or its scripts is stale until this run reaches its boards again.
        var lib = library.rebased(setup.deck.fingerprint, scripts)
        if (lib.boards.isEmpty() && lib.first != setup.first) lib = lib.copy(first = setup.first)
        if (lib.deckId.isEmpty()) lib = lib.copy(deckId = setup.deck.id.orEmpty())
        val run = lib.runs + 1
        // Only the parts mapped count; a hand whose part was never started is left out of the run.
        val kept = dones.indices.filter { dones[it] != null }
        val traits = kept.flatMap { i -> dones[i]!!.ends.map { it.traits.copy(through = emptyMap()) } }.distinct().sortedWith(TRAIT_ORDER)
        val index = traits.withIndex().associate { it.value to it.index }
        val added = ArrayList<String>()
        val renumber = IntArray(dones.size) { -1 }
        val parts = kept.mapIndexed { j, i ->
            renumber[i] = j
            val d = dones[i]!!
            // The library takes each field's best board (BoardLibrary.admits): the boards a hand reaches are nearly all its own.
            val known = lib.byKey
            val taken = lib.admits(d.ends)
            if (taken.isNotEmpty()) {
                lib = lib.add(d.deal, taken, run, at)
                added += taken.filter { it.key !in known }.map { it.key }
            }
            RunPart(d.deal.hand, d.deal.fodder, plan.counts[i], d.ends.map { index.getValue(it.traits.copy(through = emptyMap())) }.distinct().sorted(), d.complete, d.moves)
        }
        val dealt = plan.dealt.map { renumber[it] }
        val result = MapperRun(
            deal = setup.deal,
            deckId = setup.deck.id.orEmpty(),
            deck = setup.deck.fingerprint,
            library = scripts,
            first = setup.first,
            hands = dealt.count { it >= 0 },
            seed = setup.seed,
            budget = setup.budget,
            traits = traits,
            added = added.distinct().sorted(),
            parts = parts,
            dealt = dealt,
            moves = parts.sumOf { it.moves.toLong() },
            ms = ms,
            at = at,
            stopped = kept.size < dones.size,
        )
        return result to lib
    }
}
