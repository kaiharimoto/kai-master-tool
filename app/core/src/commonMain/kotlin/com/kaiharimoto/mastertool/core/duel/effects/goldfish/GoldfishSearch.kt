package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.FxTable

/** One move of a line: [seat] made [move], its choices answered with [answers] in the order they were asked. */
data class LineStep(
    val seat: Int,
    val move: FxMove,
    val answers: List<List<Int>>,
    /** The canonical passcode of the card the move is about (its activation, its summon); null for a pass or a phase. */
    val card: Int? = null,
    /** Cards played as inert that this move's actions moved (canonical passcodes). */
    val touched: Set<Int> = emptySet(),
)

/**
 * The goldfish's search (D.md §5.4): depth first over [FxEngine.moves], seat 0 alone at a one-player table, stopping at the
 * first line whose end board meets the [target] — "can it", never "which line is best".
 *
 * - **A transposition table**, keyed by the table read without its uids ([TableKey]): a table reached again no deeper is
 *   not searched again.
 * - **Choices collapsed** where they cannot matter ([Options]); each move's choices are tried as a tape — the engine run
 *   again with one more answer changed each time — so every combination is one engine move.
 * - **Ordering**: the End Phase first when the board meets the target already; then summons by procedure, Normal Summons,
 *   activations, and Sets; within a move, the choices that meet more of the target's conditions, then those that leave more
 *   on the field and in the hand. The End Phase only when nothing else is legal, when the target is met, or when a script in
 *   play has an End Phase trigger; the other phases are never entered (no Battle Phase in a goldfish).
 * - **Bounds**: at most [depth] engine moves in the line, at most [budget] engine moves spent on the hand.
 *
 * The end board is read after the End Phase, its own triggers resolved; Spells and Traps the engine knows may be Set from the
 * hand then (a rule, not an effect: [EndSets]). A card played as inert is never activated (it has no script in the book),
 * never summoned, material or a Tribute ([Options]), and a line that moves it says so ([LineStep.touched]).
 */
class GoldfishSearch(
    private val target: EndBoard,
    private val kit: GoldfishKit,
    private val budget: Int = DEFAULT_BUDGET,
    private val depth: Int = DEFAULT_DEPTH,
    /** The scripts read the Deck's order: it is kept in the key. */
    private val ordered: Boolean = false,
    /** A rule reads zones (Link arrows): every zone is its own option. */
    private val zonesMatter: Boolean = false,
    /** A script in play has an End Phase trigger: the End Phase is tried from every open table. */
    private val endTriggers: Boolean = false,
    private val cancelled: () -> Boolean = { false },
    /** Only finish what stands: passes, resolutions, waiting triggers and the End Phase (a recorded line's end). */
    private val finishOnly: Boolean = false,
) {
    /** What the search came to: how the hand ended, its line and the sets made at its end, and the engine moves spent. */
    data class Found(
        val end: HandEnd,
        val line: List<LineStep> = emptyList(),
        /** uids Set from the hand at the turn's end (Spells and Traps), in the order Set. */
        val sets: List<Int> = emptyList(),
        val moves: Int = 0,
        /** The end board, when reached. */
        val board: FxTable? = null,
    )

    private class Stop : RuntimeException()

    private var spent = 0
    private var exhausted = false
    private val options = Options(zonesMatter)
    private val seen = HashMap<TableKey.Key, Int>()
    private val path = ArrayList<LineStep>()
    private var found: Found? = null

    /** Searches from [t] (seat 0's Main Phase 1). */
    fun search(t: FxTable): Found {
        try {
            if (dfs(t, 0)) return found!!
        } catch (_: Stop) {
            exhausted = true
        }
        val end = if (exhausted || options.truncated) HandEnd.UNDECIDED else HandEnd.NO_LINE
        return Found(end, moves = spent)
    }

    private fun dfs(t: FxTable, d: Int): Boolean {
        if (cancelled()) throw Stop()
        val key = TableKey(t, ordered).key()
        val before = seen[key]
        if (before != null && before <= d) return false
        seen[key] = d
        val open = FxRules.open(t)
        if (open && t.state.phase == DuelPhase.END) {
            val sets = EndSets.best(t, target, kit) ?: return false
            found = Found(HandEnd.REACHED, path.toList(), sets.first, spent, sets.second)
            return true
        }
        if (d >= depth) return false
        val seat = FxEngine.next(t)
        for (m in ordered(t, seat, open)) {
            for (leaf in leaves(t, seat, m)) {
                path += leaf.step
                if (dfs(leaf.table, d + 1)) return true
                path.removeAt(path.size - 1)
            }
        }
        return false
    }

    private class Leaf(val table: FxTable, val step: LineStep, val score: Int)

    /** [seat]'s moves on [t], one a card and kind (copies are one), in the search's order. */
    private fun ordered(t: FxTable, seat: Int, open: Boolean): List<FxMove> {
        val key = TableKey(t, ordered)
        val all = FxEngine.moves(t, seat)
        val pending = t.fx.pending.map { it.uid to it.effect }.toSet()
        val distinct = LinkedHashMap<String, FxMove>()
        all.forEach { m ->
            val k = when (m) {
                is FxMove.Activate -> "a" + key.token(m.uid) + ":" + m.effect
                is FxMove.NormalSummon -> "n" + key.token(m.uid) + ":" + m.set
                is FxMove.Procedure -> "p" + key.token(m.uid) + ":" + m.proc
                is FxMove.Phase -> "f" + m.to.ordinal
                FxMove.Pass -> "x"
                FxMove.Resolve -> "r"
            }
            if (finishOnly) {
                val ok = m is FxMove.Pass || m is FxMove.Resolve || (m is FxMove.Phase && m.to == DuelPhase.END) ||
                    (m is FxMove.Activate && (m.uid to m.effect) in pending)
                if (!ok) return@forEach
            }
            if (m is FxMove.Phase && m.to != DuelPhase.END) return@forEach
            distinct.getOrPut(k) { m }
        }
        val sorted = distinct.entries.sortedWith(compareBy({ rank(it.value) }, { it.key })).map { it.value }
        val end = sorted.filter { it is FxMove.Phase }
        val rest = sorted.filter { it !is FxMove.Phase }
        if (!open || end.isEmpty()) return rest
        val metNow = EndSets.best(t, target, kit) != null
        return when {
            metNow -> end + rest
            rest.isEmpty() || endTriggers || finishOnly -> rest + end
            else -> rest
        }
    }

    private fun rank(m: FxMove): Int = when (m) {
        FxMove.Resolve -> 0
        FxMove.Pass -> 1
        is FxMove.Procedure -> 2
        is FxMove.NormalSummon -> if (m.set) 5 else 3
        is FxMove.Activate -> 4
        is FxMove.Phase -> 6
    }

    /**
     * Every way [m] can be made on [t]: the engine run on a tape of answers, each run changing one more answer, the tables
     * it leads to (one a table), best first. Charged to the budget a run at a time.
     */
    private fun leaves(t: FxTable, seat: Int, m: FxMove): List<Leaf> {
        val out = ArrayList<Leaf>()
        val keys = HashSet<TableKey.Key>()
        val stack = ArrayDeque<List<List<Int>>>()
        stack.addLast(emptyList())
        while (stack.isNotEmpty()) {
            if (spent >= budget) throw Stop()
            if (cancelled()) throw Stop()
            val prefix = stack.removeLast()
            val tape = Tape(t, prefix)
            val p = FxEngine.play(t, seat, m, tape)
            spent++
            // Every answer past the prefix was the first of its options: each other option is a run of its own.
            // A run refused for an inert card's use: answers past the one that made it cannot mend it.
            for (j in minOf(tape.taken.size - 1, tape.cut) downTo prefix.size) {
                val alts = tape.alts[j]
                for (a in alts.size - 1 downTo 1) stack.addLast(tape.taken.subList(0, j) + listOf(alts[a]))
            }
            if (p !is FxPlay.Done || tape.violated) continue
            val next = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
            if (!keys.add(TableKey(next, ordered).key())) continue
            val touched = p.actions.mapNotNull { a -> (a as? DuelAction.Move)?.uid?.takeIf { kit.inertUid(it, t) } }.mapNotNull { t.code(it) }.toSet()
            val card = when (m) {
                is FxMove.Activate -> t.code(m.uid)
                is FxMove.NormalSummon -> t.code(m.uid)
                is FxMove.Procedure -> t.code(m.uid)
                else -> null
            }
            val score = BoardCheck.met(next, 0, target) * 10_000 + (BoardCheck.field(next, 0).size + next.state.seats[0].hand.size) * 10
            out += Leaf(next, LineStep(seat, m, tape.taken.toList(), card, touched), score)
        }
        // Stable: equal scores keep the tape's order.
        return out.withIndex().sortedWith(compareBy({ -it.value.score }, { it.index })).map { it.value }
    }

    /** Answers from [prefix], then the first option of each decision past it, noting every decision's options. */
    private inner class Tape(private val t: FxTable, private val prefix: List<List<Int>>) : Chooser {
        val taken = ArrayList<List<Int>>()
        val alts = ArrayList<List<List<Int>>>()
        var violated = false

        /** The last decision whose other answers may still mend a refused run. */
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
        /** D.md §5.4: at most 60 engine moves in a turn's line. */
        const val DEFAULT_DEPTH = 60

        /** D.md §5.4: at most 20,000 engine moves spent on one hand. */
        const val DEFAULT_BUDGET = 20_000
    }
}

/**
 * The end board with the Spells and Traps Set that help it (a rule, not an effect): only cards the engine knows — a card
 * played as inert is never Set (§5.5) — into the free Spell & Trap Zones, the fewest that meet the target.
 */
internal object EndSets {
    /** The uids Set and the board they make when the target is met (none Set when it is met already); null when it is not. */
    fun best(t: FxTable, target: EndBoard, kit: GoldfishKit): Pair<List<Int>, FxTable>? {
        if (BoardCheck.meets(t, 0, target)) return emptyList<Int>() to t
        if (!target.all.any(::setsHelp)) return null
        val free = t.state.freeZones(0, ZoneKind.SPELL)
        if (free.isEmpty()) return null
        val key = TableKey(t, false)
        val settable = t.state.seats[0].hand.filter { u ->
            val c = t.card(u) ?: return@filter false
            (c.type == CardType.TRAP || (c.type == CardType.SPELL && !c.isSpellSub("Field"))) && !kit.inertUid(u, t)
        }.groupBy { key.token(it) }.entries.sortedBy { it.key }.map { it.value }
        if (settable.isEmpty()) return null
        var tried = 0
        for (n in 1..minOf(free.size, settable.sumOf { it.size })) {
            val picks = ArrayList<List<Int>>()
            pick(settable, n, 0, ArrayList(), picks)
            for (p in picks) {
                if (++tried > MOST) return null
                val moves = p.mapIndexed { i, u -> DuelAction.Move(u, free[i], CardPosition.FACE_DOWN_DEF, "set") }
                val (state, _) = DuelRules.applyAll(t.state, moves, 0)
                state ?: continue
                val next = FxTable(state, t.fx.copy(setCards = t.fx.setCards + p), t.book, t.facts, t.seed)
                if (BoardCheck.meets(next, 0, target)) return p to next
            }
        }
        return null
    }

    private fun setsHelp(c: BoardCond): Boolean = when (c) {
        is BoardCond.SetCards, is BoardCond.Interruptions -> true
        is BoardCond.AnyOf -> c.any.any(::setsHelp)
        else -> false
    }

    private fun pick(groups: List<List<Int>>, left: Int, from: Int, acc: ArrayList<Int>, out: ArrayList<List<Int>>) {
        if (out.size > MOST) return
        if (left == 0) { out += acc.toList(); return }
        for (g in from until groups.size) {
            for (k in minOf(left, groups[g].size) downTo 1) {
                val n = acc.size
                acc.addAll(groups[g].subList(0, k))
                pick(groups, left - k, g + 1, acc, out)
                while (acc.size > n) acc.removeAt(acc.size - 1)
            }
        }
    }

    private const val MOST = 64
}
