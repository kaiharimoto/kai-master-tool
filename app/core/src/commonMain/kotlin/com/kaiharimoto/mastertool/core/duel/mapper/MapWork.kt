package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Goldfish
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
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

/*
 * Many maps at once (M.md §2.4–§2.5): the starter table's and a run's deals mapped on several workers, each map's ends kept
 * without their tables, and the answers put back in the deals' order, so how many threads ran never changes the library.
 */

/** One end of a map as the library keeps it: its board without the table, so many maps can be held at once. */
class MapEnd(val key: String, val cards: BoardCards, val traits: BoardTraits, val line: MapLine, val at: Int)

/** A map made for [deal]: its ends without tables, whether it was complete, and the engine moves it cost. */
class MapDone(val deal: MapDeal, val ends: List<MapEnd>, val complete: Boolean, val moves: Int)

/** How a deck is mapped: what the search is told about it, read once from the deck and its scripts. */
class MapPlan(
    val main: List<Int>,
    val extra: List<Int>,
    val kit: GoldfishKit,
    val budget: Int = MapSearch.DEFAULT_BUDGET,
    val depth: Int = MapSearch.DEFAULT_DEPTH,
    val prior: MovePrior = MovePrior.NONE,
) {
    /** A script reads the Deck's order: tables that differ only in it are told apart. */
    val ordered: Boolean = !MapperDeck.orderFree(main, extra, kit)

    /** A Link Monster in the deck: zones are told apart. */
    val zones: Boolean = MapperDeck.zonesMatter(main, extra, kit)

    /** [deal] mapped on this thread; [cancelled] stops it between engine moves, and the map says it is incomplete. */
    fun map(deal: MapDeal, cancelled: () -> Boolean = { false }): MapDone {
        val table = deal.table(main, extra, kit)
        val m = MapSearch(kit, budget, depth, ordered = ordered, zonesMatter = zones, prior = prior, cancelled = cancelled)
            .map(table, deal.fodderUids(table))
        return MapDone(deal, m.ends.map { MapEnd(it.key, it.cards, it.traits, MapLine.of(deal, it), it.at) }, m.complete, m.moves)
    }
}

object MapWork {
    /** The workers a run uses: one per core, less one, at least one (the goldfish's rule). */
    fun workers(): Int = Goldfish.workers()

    /**
     * Every deal of [deals] mapped by [plan] on [workers] workers, the answers in [deals]' order. [done] is told after each
     * map (from the workers' threads), with how many are done. [stop] (the person's Stop) ends the run between engine moves
     * and keeps what was mapped: a deal never started is null, one cut short is incomplete. Cancelling the job throws, as
     * any coroutine does.
     */
    suspend fun all(
        plan: MapPlan,
        deals: List<MapDeal>,
        workers: Int = workers(),
        stop: () -> Boolean = { false },
        done: (Int) -> Unit = {},
    ): List<MapDone?> {
        val out = arrayOfNulls<MapDone>(deals.size)
        val queue = Channel<Int>(Channel.UNLIMITED)
        deals.indices.forEach { queue.trySend(it) }
        queue.close()
        val lock = Mutex()
        var finished = 0
        coroutineScope {
            val scope = coroutineContext
            val halt = { !scope.isActive || stop() }
            (0 until workers.coerceIn(1, 64)).map {
                launch(Dispatchers.Default) {
                    for (i in queue) {
                        coroutineContext.ensureActive()
                        if (halt()) continue
                        out[i] = plan.map(deals[i], halt)
                        lock.withLock { done(++finished) }
                    }
                }
            }.joinAll()
        }
        return out.toList()
    }

    /** [all] on this thread, one deal after another: what the tests hold the parallel run to. */
    fun here(plan: MapPlan, deals: List<MapDeal>, cancelled: () -> Boolean = { false }, done: (Int) -> Unit = {}): List<MapDone?> {
        val out = ArrayList<MapDone?>(deals.size)
        for ((i, d) in deals.withIndex()) {
            if (cancelled()) { out += null; continue }
            out += plan.map(d, cancelled)
            done(i + 1)
        }
        return out
    }
}
