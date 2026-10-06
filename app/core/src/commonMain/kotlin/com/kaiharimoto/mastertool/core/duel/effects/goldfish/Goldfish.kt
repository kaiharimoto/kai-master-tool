package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxVocab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/** What a run asks (D.md §5.1): a deck, a target, a seat order, how many hands from which seed, and the bounds. */
data class GoldfishSetup(
    val deck: GoldfishDeck,
    val target: EndBoard,
    /** Going first (5 cards, no draw), or second (6, an empty field across the table). */
    val first: Boolean = true,
    val hands: Int = Goldfish.DESK_HANDS,
    val seed: Long = 1L,
    /** Engine moves a hand may spend. */
    val budget: Int = GoldfishSearch.DEFAULT_BUDGET,
    /** Engine moves a line may hold. */
    val depth: Int = GoldfishSearch.DEFAULT_DEPTH,
    /** "This line": a recorded combo played through the engine; null for the search. */
    val combo: Combo? = null,
    /** Hands with one engine part share their search (only while the scripts never read the Deck's order). */
    val reduce: Boolean = true,
)

/** How far a run has got: [done] of [total] hands, [reached] so far, in [ms]. */
data class GoldfishProgress(val done: Int, val total: Int, val reached: Int, val ms: Long) {
    /** Hands a second so far. */
    val handsPerSecond: Double get() = if (ms <= 0) 0.0 else done * 1000.0 / ms
}

/**
 * The goldfish simulator (Phase D step 4, `docs/phases/D.md` §5): of N hands dealt from a seed, in how many can the trusted
 * effects reach the target in one turn, and by which lines — or, given a combo, in how many does that line get there.
 *
 * - **Hands and seeds** ([GoldfishHands]): hand k is the duel's own riffle keyed by `DuelRandom.forRoll(seed, k)`; the same
 *   seed deals the same hands everywhere.
 * - **Reduced hands** ([GoldfishReduce]): a hand's blanks set aside, hands with one engine part searched once.
 * - **Parallel and cancellable** (§5.7): on `Dispatchers.Default`, one worker per core less one, hands taken by index and put
 *   together in index order, so how many threads ran never changes a count. Cancelling the run's job stops every worker
 *   within one engine move.
 * - **The trust rule** (§11): only the scripts [FxTrust][com.kaiharimoto.mastertool.core.duel.effects.FxTrust] lets it use;
 *   the result names the cards whose effects its lines used, those with open warnings, and how many were played by you.
 */
object Goldfish {
    /** Hands on the desk by default (§5.3). */
    const val DESK_HANDS = 2_000

    /** Hands on a phone by default. */
    const val PHONE_HANDS = 500

    /** The most hands one run deals. */
    const val MOST_HANDS = 20_000

    /** The workers a run uses: one per core, less one, at least one. */
    fun workers(): Int = (goldfishCores() - 1).coerceAtLeast(1)

    /**
     * Why [setup] cannot be run with [kit], or null when it can (§5.5): a target a newer build wrote, or one that names a card
     * played as inert in `Controls`, `InGy` or `Banished` — its procedure or a step of its own would be needed. The cards are
     * named, for **Write these** (the person's go). `Holds` may name one: holding a card needs no effect.
     */
    fun refusal(setup: GoldfishSetup, kit: GoldfishKit): NotComputable? {
        if (BoardCheck.unread(setup.target)) {
            return NotComputable("target", setup.target.name, why = "The target holds a condition a newer build wrote: it cannot be judged here.")
        }
        if (setup.target.all.isEmpty()) return NotComputable("target", setup.target.name, why = "The target has no conditions: name what the end board must hold.")
        val needs = setup.target.all.flatMap(::named).map(kit::canonical).distinct().filter(kit::inert)
        if (needs.isNotEmpty()) {
            return NotComputable(
                "target", setup.target.name, needs,
                "The target needs ${needs.joinToString { kit.name(it) }} on the board, which the goldfish plays as inert (no trusted effect): write ${if (needs.size == 1) "it" else "them"} first.",
            )
        }
        if (setup.deck.main.size < GoldfishHands.size(setup.first)) return NotComputable("target", setup.target.name, why = "The Main Deck holds fewer cards than a hand.")
        return null
    }

    /** The cards a condition names by passcode where it needs them on the board: in `Controls`, `InGy` or `Banished`. */
    private fun named(c: BoardCond): List<Int> = when (c) {
        is BoardCond.Controls -> names(c.where)
        is BoardCond.InGy -> names(c.where)
        is BoardCond.Banished -> names(c.where)
        is BoardCond.AnyOf -> if (c.any.all { named(it).isNotEmpty() }) c.any.flatMap(::named) else emptyList()
        else -> emptyList()
    }

    private fun names(f: Filter): List<Int> = when (f) {
        is Filter.Name -> listOf(f.card)
        is Filter.All -> f.all.flatMap(::names)
        is Filter.AnyOf -> if (f.any.all { names(it).isNotEmpty() }) f.any.flatMap(::names) else emptyList()
        else -> emptyList()
    }

    /**
     * Runs [setup] with [kit] on [workers] workers, telling [progress] as hands are done (from the workers' threads). Throws
     * [IllegalArgumentException] with the refusal's words when the run is not computable ([refusal]). [now] is the time
     * kept in the result (`at`).
     */
    suspend fun run(
        setup: GoldfishSetup,
        kit: GoldfishKit,
        workers: Int = workers(),
        progress: (GoldfishProgress) -> Unit = {},
        now: Long = 0L,
    ): GoldfishResult {
        refusal(setup, kit)?.let { throw IllegalArgumentException(it.why) }
        val started = TimeSource.Monotonic.markNow()
        val ctx = Context(setup, kit)
        val n = setup.hands.coerceIn(1, MOST_HANDS)
        val outs = arrayOfNulls<Hand>(n)
        val queue = Channel<Int>(Channel.UNLIMITED)
        for (k in 0 until n) queue.trySend(k)
        queue.close()
        val lock = Mutex()
        var done = 0
        var reached = 0
        coroutineScope {
            val scope = coroutineContext
            val stop = { !scope.isActive }
            (0 until workers.coerceIn(1, 64)).map {
                launch(Dispatchers.Default) {
                    for (k in queue) {
                        coroutineContext.ensureActive()
                        val h = ctx.hand(k, stop)
                        coroutineContext.ensureActive()
                        outs[k] = h
                        lock.withLock {
                            done++
                            if (h.end == HandEnd.REACHED) reached++
                            progress(GoldfishProgress(done, n, reached, started.elapsedNow().inWholeMilliseconds))
                        }
                    }
                }
            }.joinAll()
        }
        return ctx.result(outs.map { it!! }, started.elapsedNow().inWholeMilliseconds, now)
    }

    /** [run] on this thread alone, every hand in order: what the tests compare the parallel run against. */
    fun runHere(setup: GoldfishSetup, kit: GoldfishKit, now: Long = 0L): GoldfishResult {
        refusal(setup, kit)?.let { throw IllegalArgumentException(it.why) }
        val started = TimeSource.Monotonic.markNow()
        val ctx = Context(setup, kit)
        val n = setup.hands.coerceIn(1, MOST_HANDS)
        val outs = (0 until n).map { ctx.handNow(it) { false } }
        return ctx.result(outs, started.elapsedNow().inWholeMilliseconds, now)
    }

    /** [run] blocking its caller (an instrument's thread, never the frame's). */
    fun runBlocking(setup: GoldfishSetup, kit: GoldfishKit, workers: Int = workers(), now: Long = 0L): GoldfishResult =
        goldfishBlocking { run(setup, kit, workers, now = now) }

    /**
     * Hand [k] of [setup] searched (or its line played) on its own, nothing shared: its outcome and the line found — what a
     * replay is made from ([GoldfishReplay]), and what the memo is held to.
     */
    fun hand(setup: GoldfishSetup, kit: GoldfishKit, k: Int): GoldfishSearch.Found =
        Context(setup.copy(reduce = false), kit).searchHand(k) { false }

    /** One hand as the run keeps it: its outcome and what the search found. */
    internal class Hand(val outcome: HandOutcome, val found: GoldfishSearch.Found) {
        val end: HandEnd get() = outcome.end
    }

    /** One run's fixed parts: the reduction, the line's check, and the searches shared by hands with one engine part. */
    internal class Context(val setup: GoldfishSetup, val kit: GoldfishKit) {
        val main: List<Int> = setup.deck.main.map(kit::canonical)
        val extra: List<Int> = setup.deck.extra.map(kit::canonical)
        val reduce = GoldfishReduce(kit, setup.deck.copy(main = main, extra = extra), setup.target)
        private val shared = HashMap<List<Int>, GoldfishSearch.Found>()
        private val sharing = Mutex()

        /** The recorded line, when it is computable; else why not (and the search runs). */
        val notComputable: List<NotComputable>
        val combo: Combo?

        init {
            val c = setup.combo
            var nc = emptyList<NotComputable>()
            var use: Combo? = c
            if (c != null) {
                // Read on the first hand that holds its needs: what its steps do there by hand.
                val n = setup.hands.coerceIn(1, MOST_HANDS)
                val probe = (0 until minOf(n, 2_000)).firstNotNullOfOrNull { k ->
                    val game = GoldfishHands.game(main, extra, setup.seed, k, setup.first)
                    val t = GoldfishHands.table(game, kit)
                    t.takeIf { ComboRunner.missing(it.state, 0, c, kit.catalog).isEmpty() }
                }
                val needs = probe?.let { GoldfishPlan.needsInert(c, it, kit) }.orEmpty()
                if (needs.isNotEmpty()) {
                    nc = listOf(
                        NotComputable(
                            c.id, c.name, needs,
                            "“${c.name}” activates, summons or uses as material ${needs.joinToString { kit.name(it) }}, played as inert (no trusted effect): not computable — the search ran instead.",
                        ),
                    )
                    use = null
                }
            }
            notComputable = nc
            combo = use
        }

        /**
         * Hands with one engine part share a search — never for a recorded line, whose steps name cards (a blank among
         * them), nor when the scripts read the Deck's order.
         */
        val memo: Boolean = setup.reduce && reduce.orderFree && combo == null

        fun searchHand(k: Int, stop: () -> Boolean): GoldfishSearch.Found {
            val game = GoldfishHands.game(main, extra, setup.seed, k, setup.first)
            val t = GoldfishHands.table(game, kit)
            val c = combo
            return if (c != null) {
                GoldfishPlan(c, setup.target, kit, setup.budget, setup.depth, !reduce.orderFree, reduce.zonesMatter, stop).play(t)
            } else {
                GoldfishSearch(setup.target, kit, setup.budget, setup.depth, !reduce.orderFree, reduce.zonesMatter, reduce.endTriggers, stop).search(t)
            }
        }

        private fun outcome(k: Int, found: GoldfishSearch.Found): Hand {
            val hand = GoldfishHands.hand(main, setup.seed, k, setup.first)
            val touched = found.end == HandEnd.REACHED && found.line.any { it.touched.isNotEmpty() }
            return Hand(
                HandOutcome(k, hand, reduce.reduce(hand), found.end, moves = found.moves, heldUnknown = hand.any(kit::inert), touchedUnknown = touched),
                found,
            )
        }

        /** Hand [k], its search shared with every hand of its engine part (parallel). */
        suspend fun hand(k: Int, stop: () -> Boolean): Hand {
            if (!memo) return outcome(k, searchHand(k, stop))
            val key = reduce.reduce(GoldfishHands.hand(main, setup.seed, k, setup.first))
            sharing.withLock { shared[key] }?.let { return outcome(k, it) }
            val found = searchHand(k, stop)
            // A search cut short by the run's cancelling is never shared: its "undecided" was the cancel's, not the hand's.
            if (!stop()) sharing.withLock { shared.getOrPut(key) { found } }
            return outcome(k, found)
        }

        /** Hand [k], on this thread. */
        fun handNow(k: Int, stop: () -> Boolean): Hand {
            if (!memo) return outcome(k, searchHand(k, stop))
            val key = reduce.reduce(GoldfishHands.hand(main, setup.seed, k, setup.first))
            val found = shared.getOrPut(key) { searchHand(k, stop) }
            return outcome(k, found)
        }

        /** The hands, in index order, put together. */
        fun result(hands: List<Hand>, ms: Long, at: Long): GoldfishResult {
            val skeletons = LinkedHashMap<String, MutableList<Hand>>()
            hands.filter { it.end == HandEnd.REACHED }.forEach { h -> skeletons.getOrPut(skeleton(h.found)) { ArrayList() } += h }
            val lines = skeletons.entries.sortedWith(compareBy({ -it.value.size }, { it.key })).map { (sk, hs) ->
                LineCount(sk, hs.size, combo?.id, hs.flatMap { h -> h.found.line.flatMap { it.touched } }.distinct().sorted())
            }
            val index = lines.withIndex().associate { it.value.skeleton to it.index }
            val outcomes = hands.map { h -> if (h.end == HandEnd.REACHED) h.outcome.copy(line = index[skeleton(h.found)]) else h.outcome }
            val used = hands.filter { it.end == HandEnd.REACHED }.flatMap { h ->
                h.found.line.filter { it.move is FxMove.Activate || it.move is FxMove.Procedure }
                    .mapNotNull { it.card }
            }.filter { kit.book.has(it) }.distinct().sorted()
            val unknown = (main + extra).distinct().filter(kit::inert)
            return GoldfishResult(
                deck = setup.deck.fingerprint,
                library = kit.trust.library(main + extra),
                target = setup.target,
                first = setup.first,
                hands = hands.size,
                seed = setup.seed,
                budget = setup.budget,
                reached = hands.count { it.end == HandEnd.REACHED },
                noLine = hands.count { it.end == HandEnd.NO_LINE },
                undecided = hands.count { it.end == HandEnd.UNDECIDED },
                unknown = unknown,
                heldUnknown = outcomes.count { it.heldUnknown },
                touchedUnknown = outcomes.count { it.touchedUnknown },
                notComputable = notComputable,
                lines = lines,
                outcomes = outcomes,
                ms = ms,
                used = used,
                warned = used.filter { kit.trust.openWarnings(it).isNotEmpty() },
                playedByYou = used.count { kit.trust.playedByYou(it) },
                deckId = setup.deck.id,
                combo = combo?.id,
                ordered = !memo,
                depth = setup.depth,
                at = at,
                engine = FxVocab.ENGINE,
            )
        }

        /** A line's skeleton: its activations and summons in order, by card name, and the Sets at its end. */
        fun skeleton(found: GoldfishSearch.Found): String = GoldfishWords.skeleton(found, kit)
    }
}
