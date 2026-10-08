package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.LineStep
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Options
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey

/**
 * The order a search tries moves in (M.md §4.1): the learned move prior, or the hand-written order. **It may only reorder.**
 * A complete map's end boards never depend on it (`MapSearchTest` holds it); an incomplete one says it is incomplete. An
 * order that is not the same moves (one dropped, one added, one twice) is not used: the search keeps its own and says so
 * ([MapSearch.Mapped.priorRefused]).
 */
fun interface MovePrior {
    /** [moves] (seat [seat]'s on [t]) in the order to try them: the same moves, every one of them. */
    fun order(t: FxTable, seat: Int, moves: List<FxMove>): List<FxMove>

    companion object {
        /** The hand-written order: procedures, Normal Summons, activations, Sets, the End Phase last. */
        val NONE = MovePrior { _, _, moves -> moves }
    }
}

/**
 * The mapper's search (M.md §2.2): every end board seat 0 can reach in one turn from a table, each with its best line —
 * the goldfish's search ([com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSearch]) without the early stop and
 * without a target.
 *
 * - **Every open table may end the turn**: the End Phase is tried from each, so every board on the way is an end board too.
 * - **A graph, not a tree**: a table reached again no deeper is merged ([TableKey], the goldfish's transposition table).
 * - **Ends are deduplicated** by [BoardKey]; each keeps its **best line**: the shortest, and among lines as short that the
 *   search met, the first in a fixed order of their text. The ends and their lines' lengths never depend on the order moves
 *   were tried in; which of two equally short lines is kept may (a merged table is not searched again from a second prefix).
 * - **Before the End Phase** the Traps and Quick-Play Spells the engine knows are Set from the hand into the free zones, as
 *   a player would (a rule, not an effect), and the End Phase is played from there, so its triggers see the Set cards as a
 *   replay does. With more of them than zones, every distinct choice by card is its own end; other Spells stay in hand.
 * - **Bounds**: [depth] engine moves a line, [budget] engine moves a map. A map that ran out of either, or met a decision
 *   with more answers than the search tries, is [Mapped.complete] false: "incomplete", never "these are all the boards".
 * - **[trace]** keeps the decision tables with the ends below each of their moves, in the order the search met them, at
 *   most [traceMost]: the training data's source ([TrainingExport]).
 */
class MapSearch(
    private val kit: GoldfishKit,
    private val budget: Int = DEFAULT_BUDGET,
    private val depth: Int = DEFAULT_DEPTH,
    private val ordered: Boolean = false,
    private val zonesMatter: Boolean = false,
    private val prior: MovePrior = MovePrior.NONE,
    private val trace: Boolean = false,
    private val traceMost: Int = TrainingExport.MOST_RECORDS,
    private val cancelled: () -> Boolean = { false },
) {
    /** An end board found: what it is, what it measures, the table, and its best line with the Sets made at its end. */
    class End(
        val key: String,
        val cards: BoardCards,
        val traits: BoardTraits,
        val board: FxTable,
        val line: List<LineStep>,
        val sets: List<Int>,
        /** The engine moves the search had spent when it first met this board: how fast an order finds it (the gate's measure). */
        val at: Int,
    )

    /**
     * A decision on the way (only when tracing): the table, the moves tried, and the ends below each (indexes into
     * [Mapped.ends], sorted).
     */
    class Node(val table: FxTable, val seat: Int, val step: Int, val moves: List<FxMove>, val reach: List<IntArray>)

    /** The map: its ends in key order, whether the search covered everything, the engine moves spent, the tables visited. */
    class Mapped(
        val ends: List<End>,
        val complete: Boolean,
        val moves: Int,
        val positions: Int,
        val nodes: List<Node> = emptyList(),
        /** Tables where the prior's order was not the same moves, and the search's own was used instead. */
        val priorRefused: Int = 0,
        /**
         * Whether every node's ends below each move are all of them: [complete], and no table was ever cut at the depth bound
         * (one cut and searched again from nearer the start leaves the map complete, but the tables above the cut counted
         * without it). What the training data needs ([TrainingExport]).
         */
        val reachExact: Boolean = complete,
    ) {
        /** Each end's key and the engine moves spent when it was first found: what the gate's front recall reads. */
        val found: Map<String, Int> get() = ends.associate { it.key to it.at }

        /** The keys of the ends no other end beats on every trait where more is plainly better: the map's front. */
        fun front(): Set<String> {
            val dims = BoardTraits.MORE_IS_BETTER + ends.flatMap { it.traits.through.keys }.distinct().sorted().map { BoardTraits.THROUGH + it }
            return Pareto.front(ends.map { e -> DoubleArray(dims.size) { e.traits[dims[it]] ?: Double.NEGATIVE_INFINITY } }).mapTo(LinkedHashSet()) { ends[it].key }
        }
    }

    private class Stop : RuntimeException()

    private var spent = 0
    private val options = Options(zonesMatter)
    private val seen = HashMap<TableKey.Key, Int>()
    private val reachOf = HashMap<TableKey.Key, IntArray>()
    private val path = ArrayList<LineStep>()
    private val ends = LinkedHashMap<String, End>()
    private val endIndex = HashMap<String, Int>()
    private val nodes = ArrayList<Node?>()
    private val slotOf = HashMap<TableKey.Key, Int>()
    /** Tables cut at the depth bound and not searched since from nearer the start: while any is left, the map is incomplete. */
    private val cutKeys = HashSet<TableKey.Key>()
    private var everCut = false
    private var setsCut = false
    private var refused = 0
    private var fodder: Set<Int> = emptySet()

    /** The Sets made before the End Phase on the way to the table being searched: what an end records. */
    private var sets: List<Int> = emptyList()

    /** Maps from [t] (seat 0's Main Phase 1). [fodder] is the deal's fodder ([MapDeal.fodderUids]), left out of every board. */
    fun map(t: FxTable, fodder: Set<Int> = emptySet()): Mapped {
        this.fodder = fodder
        var stopped = false
        try {
            dfs(t, 0)
        } catch (_: Stop) {
            stopped = true
        }
        val sorted = ends.values.sortedBy { it.key }
        val renumber = IntArray(endIndex.size).also { r -> sorted.forEachIndexed { i, e -> r[endIndex.getValue(e.key)] = i } }
        val kept = nodes.filterNotNull().map { n ->
            Node(n.table, n.seat, n.step, n.moves, n.reach.map { r -> IntArray(r.size) { renumber[r[it]] }.also { it.sort() } })
        }
        val complete = !stopped && cutKeys.isEmpty() && !setsCut && !options.truncated
        return Mapped(sorted, complete, spent, seen.size, kept, refused, reachExact = complete && !everCut)
    }

    /** The ends reachable from [t] (their indexes in discovery order, sorted), when tracing; else empty. */
    private fun dfs(t: FxTable, d: Int): IntArray {
        if (cancelled()) throw Stop()
        val key = TableKey(t, ordered).key()
        val before = seen[key]
        if (before != null && before <= d) return reachOf[key] ?: NONE
        seen[key] = d
        val open = FxRules.open(t)
        if (open && t.state.phase == DuelPhase.END) {
            val i = record(t)
            return if (trace) intArrayOf(i).also { reachOf[key] = it } else NONE
        }
        if (d >= depth) {
            cutKeys += key
            everCut = true
            return NONE
        }
        cutKeys -= key
        val seat = FxEngine.next(t)
        val mine = distinct(t, seat, open)
        val moves = prior.order(t, seat, mine).let { o ->
            if (o.size == mine.size && o.toSet() == mine.toSet()) o else mine.also { refused++ }
        }
        // Kept in the order the search meets the tables: the slot is taken before the moves below are searched. A table
        // searched again from nearer the start keeps its slot, its record made again from the fuller search.
        val slot = if (!trace || moves.size < 2) -1 else slotOf[key] ?: if (nodes.size < traceMost) nodes.size.also { nodes += null; slotOf[key] = it } else -1
        val reach = ArrayList<IntArray>(moves.size)
        val all = HashSet<Int>()
        for (m in moves) {
            val below = HashSet<Int>()
            val from = if ((m as? FxMove.Phase)?.to == DuelPhase.END) setChoices(t) else listOf(sets to t)
            val outer = sets
            for ((chosen, table) in from) {
                sets = chosen
                for (leaf in leaves(table, seat, m)) {
                    path += leaf.second
                    val r = dfs(leaf.first, d + 1)
                    if (trace) r.forEach { below += it }
                    path.removeAt(path.size - 1)
                }
            }
            sets = outer
            if (trace) {
                reach += below.toIntArray().also { it.sort() }
                all.addAll(below)
            }
        }
        if (!trace) return NONE
        val union = all.toIntArray().also { it.sort() }
        reachOf[key] = union
        if (slot >= 0) nodes[slot] = Node(t, seat, d, moves, reach)
        return union
    }

    /** Records the end board on [t] (the End Phase played, after the Sets before it), keeping the better line; its index. */
    private fun record(t: FxTable): Int {
        val cards = BoardCards.of(t, 0, fodder)
        val traits = BoardTraits.of(t, 0, fodder)
        val k = BoardKey.of(cards, traits)
        val old = ends[k]
        if (old == null) ends[k] = End(k, cards, traits, t, path.toList(), sets, spent)
        else if (better(path, sets, old)) ends[k] = End(k, cards, traits, t, path.toList(), sets, old.at)
        return endIndex.getOrPut(k) { endIndex.size }
    }

    private fun better(a: List<LineStep>, aSets: List<Int>, old: End): Boolean =
        a.size < old.line.size || (a.size == old.line.size && text(a, aSets) < text(old.line, old.sets))

    private fun text(line: List<LineStep>, sets: List<Int>): String = line.joinToString(";") { "${it.seat}${it.move}${it.answers}" } + "|" + sets

    /**
     * The ways to Set before the End Phase from [t]: every Trap and Quick-Play Spell the engine knows in the hand, Set into the
     * free Spell & Trap Zones (by card, then uid). With more of them than zones, one way for each distinct choice of cards, at
     * most [SET_CHOICES] (past it the map is incomplete). With none, [t] as it is.
     */
    private fun setChoices(t: FxTable): List<Pair<List<Int>, FxTable>> {
        val free = t.state.freeZones(0, ZoneKind.SPELL)
        val settable = t.state.seats[0].hand.filter { u ->
            val c = t.card(u) ?: return@filter false
            !kit.inertUid(u, t) && (c.type == CardType.TRAP || (c.type == CardType.SPELL && c.isSpellSub("Quick-Play")))
        }.sortedWith(compareBy({ kit.canonical(t.code(it) ?: 0) }, { it }))
        if (settable.isEmpty() || free.isEmpty()) return listOf(emptyList<Int>() to t)
        val picks = if (settable.size <= free.size) listOf(settable) else choices(settable, free.size, t)
        return picks.map { pick ->
            val moves = pick.mapIndexed { i, u -> DuelAction.Move(u, free[i], CardPosition.FACE_DOWN_DEF, "set") }
            val (state, _) = DuelRules.applyAll(t.state, moves, 0)
            if (state == null) emptyList<Int>() to t
            else pick to FxTable(state, t.fx.copy(setCards = t.fx.setCards + pick), t.book, t.facts, t.seed)
        }.distinctBy { it.first }
    }

    /** Every distinct choice of [n] of [settable] by card (the first copies of each), at most [SET_CHOICES]. */
    private fun choices(settable: List<Int>, n: Int, t: FxTable): List<List<Int>> {
        val byCard = settable.groupBy { kit.canonical(t.code(it) ?: 0) }.values.toList()
        val out = ArrayList<List<Int>>()
        fun walk(i: Int, left: Int, acc: List<Int>) {
            if (out.size > SET_CHOICES) return
            if (left == 0) { out += acc; return }
            if (i == byCard.size) return
            for (k in minOf(left, byCard[i].size) downTo 0) walk(i + 1, left - k, acc + byCard[i].take(k))
        }
        walk(0, n, emptyList())
        if (out.size > SET_CHOICES) setsCut = true
        return out.take(SET_CHOICES)
    }

    /** [seat]'s moves on [t], one a card and kind (copies are one), the End Phase offered from every open table. */
    private fun distinct(t: FxTable, seat: Int, open: Boolean): List<FxMove> {
        val key = TableKey(t, ordered)
        val out = LinkedHashMap<String, FxMove>()
        FxEngine.moves(t, seat).forEach { m ->
            val k = when (m) {
                is FxMove.Activate -> "4a" + key.token(m.uid) + ":" + m.effect
                is FxMove.NormalSummon -> (if (m.set) "5n" else "3n") + key.token(m.uid)
                is FxMove.Procedure -> "2p" + key.token(m.uid) + ":" + m.proc
                is FxMove.Phase -> if (m.to == DuelPhase.END) "6f" else return@forEach
                FxMove.Pass -> "1x"
                FxMove.Resolve -> "0r"
            }
            if (m is FxMove.Phase && !open) return@forEach
            out.getOrPut(k) { m }
        }
        return out.entries.sortedBy { it.key }.map { it.value }
    }

    /**
     * Every way [m] can be made on [t] (the goldfish's tape: the engine run again with one more answer changed each time),
     * one per table it leads to, in the tape's order. Charged to the budget a run at a time.
     */
    private fun leaves(t: FxTable, seat: Int, m: FxMove): List<Pair<FxTable, LineStep>> {
        val out = ArrayList<Pair<FxTable, LineStep>>()
        val keys = HashSet<TableKey.Key>()
        val stack = ArrayDeque<List<List<Int>>>()
        stack.addLast(emptyList())
        while (stack.isNotEmpty()) {
            if (spent >= budget || cancelled()) throw Stop()
            val prefix = stack.removeLast()
            val tape = Tape(t, prefix)
            val p = FxEngine.play(t, seat, m, tape)
            spent++
            for (j in minOf(tape.taken.size - 1, tape.cut) downTo prefix.size) {
                val alts = tape.alts[j]
                for (a in alts.size - 1 downTo 1) stack.addLast(tape.taken.subList(0, j) + listOf(alts[a]))
            }
            if (p !is FxPlay.Done || tape.violated) continue
            val next = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
            if (!keys.add(TableKey(next, ordered).key())) continue
            val card = when (m) {
                is FxMove.Activate -> t.code(m.uid)
                is FxMove.NormalSummon -> t.code(m.uid)
                is FxMove.Procedure -> t.code(m.uid)
                else -> null
            }
            val used = p.tags.filter { it.part == FxTag.ACTIVATE || it.part == FxTag.PROC }
                .mapNotNull { tag -> (next.code(tag.uid) ?: t.code(tag.uid))?.let(kit::canonical) }.toSet()
            out += next to LineStep(seat, m, tape.taken.toList(), card, effects = used)
        }
        return out
    }

    /** The goldfish's tape: answers from [prefix], then each decision's first option, noting every decision's options. */
    private inner class Tape(private val t: FxTable, private val prefix: List<List<Int>>) : Chooser {
        val taken = ArrayList<List<Int>>()
        val alts = ArrayList<List<List<Int>>>()
        var violated = false
        var cut = Int.MAX_VALUE
        private val key = TableKey(t, ordered)
        private val inert = { uid: Int -> kit.inertUid(uid, t) }

        private fun refuse(at: Int) {
            violated = true
            cut = minOf(cut, at)
        }

        override fun choose(d: Decision): List<Int> {
            val i = taken.size
            val opts = options.of(key, d, inert)
            if (opts.isEmpty()) {
                refuse(i - 1)
                return Chooser.CANCEL
            }
            val a = if (i < prefix.size) prefix[i] else opts[0]
            alts += opts
            taken += a
            if (options.inertUse(d, a, inert)) refuse(i)
            return a
        }

        override fun told(d: Decision, answer: List<Int>) {
            if (options.inertUse(d, answer, inert)) refuse(taken.size - 1)
        }
    }

    companion object {
        private val NONE = IntArray(0)

        /** Ways of Setting before the End Phase tried from one table; past it the map is incomplete. */
        const val SET_CHOICES = 16

        /** M.md §2.2: a line holds at most the goldfish's 60 engine moves. */
        const val DEFAULT_DEPTH = 60

        /** A map costs more than a first line: five goldfish budgets a hand. */
        const val DEFAULT_BUDGET = 100_000
    }
}
