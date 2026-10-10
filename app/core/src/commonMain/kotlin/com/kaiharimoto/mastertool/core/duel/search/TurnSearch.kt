package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Options
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey
import com.kaiharimoto.mastertool.core.duel.mapper.MapMoves

/** One step of a turn's line (E.2): a move of the engine's, the Sets before the End Phase, or an attack. */
sealed interface TurnStep {
    /** [move] by [seat] with [answers]; [card] the card it is about (canonical). */
    data class Move(val seat: Int, val move: FxMove, val answers: List<List<Int>>, val card: Int? = null) : TurnStep

    /** The Traps and Quick-Plays [uids] Set before the End Phase. */
    data class Sets(val uids: List<Int>) : TurnStep

    /** An attack and what battle made of it. */
    data class Attack(val step: BattlePlan.Step) : TurnStep
}

/**
 * The best whole turn the search found (E.2): its [steps], its [value] by [TurnValue] and the [parts] it was made of, the table
 * it [end]s on, whether the search covered everything ([complete]), the engine moves spent and the tables visited.
 */
class TurnResult(
    val steps: List<TurnStep>,
    val value: Double,
    val end: FxTable?,
    val parts: TurnValue.Parts?,
    val complete: Boolean,
    val moves: Int,
    val positions: Int,
)

/**
 * One way on from a table (Phase E, E.3): what a decision is made between, and what a decision across worlds compares — a
 * move of the engine's, the End Phase after Setting [End.sets], the Battle Phase planned whole, or (for the other seat at an
 * open table) letting the moment go.
 */
sealed interface Root {
    data class Move(val move: FxMove) : Root
    data class End(val sets: List<Int>) : Root
    data object Battle : Root
    data object LetGo : Root

    /** In the Battle Phase: [attacker] declares an attack on [target] (null: directly). */
    data class Attack(val attacker: Int, val target: Int?) : Root
}

/** One way on and what it comes to for the turn player ([value]), with the line it was valued by ([steps], from this way on). */
class TurnOption(val root: Root, val value: Double, val steps: List<TurnStep>)

/**
 * A whole turn by search (Phase E, E.2; `docs/phases/E.md` §4.2): the seat's Main Phase 1 by the engine — every move of its
 * written effects, as the mapper tries them ([MapMoves]) — then, from any open table, either the End Phase or the Battle
 * Phase planned whole ([BattlePlan]) and the End Phase after it, valued at the turn's end by [value].
 *
 * - **The other seat**: with [answers] off (E.2) it holds nothing it uses, passing at its priority. With [answers] on (E.3,
 *   a world's hidden cards dealt) it answers with what it holds, the worst for the seat: at its priority, and at every open
 *   table after a move with its Quick Effects (the engine's house ruling, as the stress search plays it) until it lets that
 *   moment go.
 * - **A choice is made by whoever the engine says makes it**: inside a move the seat's own answers are the best for it, the
 *   other seat's (`Decision.by`, or its own effect's) the worst.
 * - **Main Phase 2 sets the backrow and ends the turn**: its moves are not searched yet, and a line that needs them is not
 *   found (said in the docs, E.md §4.2).
 * - A table reached again is not searched again (keyed by [TableKey]); a search cut by [depth] or [budget] is incomplete.
 */
class TurnSearch(
    private val kit: GoldfishKit,
    private val value: TurnValue = TurnValue.V0,
    private val budget: Int = DEFAULT_BUDGET,
    private val depth: Int = DEFAULT_DEPTH,
    private val cancelled: () -> Boolean = { false },
    /** The other seat answers with what it holds (E.3), or passes at every moment (E.2). */
    private val answers: Boolean = false,
    /**
     * A decision's search (E.3): past its [budget], an open table is valued as if the turn ended there — a lower bound, since
     * the seat may always end it — rather than the search stopping; each way on from the first table gets an equal share. A
     * chain standing is resolved past the budget, up to twice it.
     */
    private val soft: Boolean = false,
    /**
     * The other seat cannot see the seat's hand (E.5a, search v1): at each moment it holds a live answer it answers now with
     * chance 1 − [hold] — its worst answer for the seat — and lets the moment go with [hold], never knowing which of the
     * seat's moves matters. Null: it answers knowing everything, the worst for the seat (v0, and always for a seat
     * deciding its own answers).
     */
    private val hold: Double? = null,
) {
    private class Stop : RuntimeException()

    private class Best(val value: Double, val steps: List<TurnStep>, val end: FxTable?)

    /** A table, and whether the other seat's moment at it (an open table after a move) is still to come. */
    private data class Key(val table: TableKey.Key, val window: Boolean)

    private var spent = 0
    private var cut = false
    /** Where a soft search stops going deeper: the budget, or a way on's share of it. */
    private var limit = budget
    private val options = Options(false)
    private val memo = HashMap<Key, Best>()
    private val onPath = HashSet<Key>()
    private var seat = 0
    private var fodder: Set<Int> = emptySet()

    /** Engine moves spent so far, over every call. */
    val spentMoves: Int get() = spent

    /** Whether everything asked so far was searched to its end. */
    val complete: Boolean get() = !cut && !options.truncated

    /** The best turn for [seat] from [t] (its own Main Phase, the table open or a chain standing). */
    fun search(t: FxTable, seat: Int, fodder: Set<Int> = emptySet()): TurnResult {
        this.seat = seat
        this.fodder = fodder
        val best = try {
            dfs(t, 0, false)
        } catch (_: Stop) {
            cut = true
            null
        }
        val end = best?.end
        return TurnResult(
            best?.steps.orEmpty(), best?.value ?: Double.NEGATIVE_INFINITY, end, end?.let { value.parts(it, seat, fodder) },
            complete = !cut && !options.truncated, moves = spent, positions = memo.size,
        )
    }

    /**
     * Every way [decider] may go on from [t] in [seat]'s turn, each valued for [seat] (E.3): the seat's own moves, End Phases
     * and Battle Phase when it decides; the other seat's answers — and letting the moment go — when it does. A way the budget
     * did not reach is left out (unknown, never zero), and [complete] says so.
     */
    fun options(t: FxTable, seat: Int, decider: Int, fodder: Set<Int> = emptySet()): List<TurnOption> {
        this.seat = seat
        this.fodder = fodder
        val out = ArrayList<TurnOption>()
        try {
            val open = FxRules.open(t)
            val ways: List<Pair<Root, () -> Best?>> = when {
                decider == seat -> mine(t, 0, open)
                open -> theirQuick(t).map { m -> Root.Move(m) to { move(t, decider, m, 0) } } + (Root.LetGo to { dfs(t, 0, false) })
                else -> theirChain(t, decider).map { m -> Root.Move(m) to { move(t, decider, m, 0) } }
            }
            val share = if (soft) ((budget - spent) / ways.size.coerceAtLeast(1)).coerceAtLeast(1) else 0
            for ((root, way) in ways) {
                if (soft) limit = spent + share
                way()?.let { out += TurnOption(root, it.value, it.steps) }
            }
        } catch (_: Stop) {
            cut = true
        }
        limit = budget
        return out
    }

    private fun dfs(t: FxTable, d: Int, window: Boolean): Best? {
        if (cancelled()) throw Stop()
        val open = FxRules.open(t)
        val phase = t.state.phase
        if (open && phase == DuelPhase.END) return Best(value.of(t, seat, fodder), emptyList(), t)
        // Past a soft search's share: the table as if the turn ended here, never kept as the table's own value.
        if (soft && open && spent >= limit) {
            cut = true
            return Best(value.of(t, seat, fodder), emptyList(), t)
        }
        val theirs = if (answers && window && open) theirQuick(t) else emptyList()
        val key = Key(TableKey(t, false).key(), theirs.isNotEmpty())
        memo[key]?.let { return it }
        if (d >= depth || key in onPath) {
            cut = true
            return null
        }
        onPath += key
        val best = try {
            val mover = FxEngine.next(t)
            when {
                // Their moment at an open table: each Quick Effect they hold, or letting it go — the worst for the seat.
                theirs.isNotEmpty() -> answered(theirs.map { m -> move(t, 1 - seat, m, d) }, dfs(t, d, false))
                mover != seat && answers -> {
                    val ms = theirChain(t, mover)
                    answered(ms.filter { it is FxMove.Activate }.map { m -> move(t, mover, m, d) }, worst(ms.filter { it !is FxMove.Activate }.map { m -> move(t, mover, m, d) }))
                }
                mover != seat -> {
                    // The other seat lets every moment go.
                    val m = FxEngine.moves(t, mover).firstOrNull { it == FxMove.Pass || it == FxMove.Resolve } ?: return null
                    val next = play(t, mover, m, emptyList()) ?: return null
                    dfs(next, d + 1, true)?.let { Best(it.value, listOf(TurnStep.Move(mover, m, emptyList())) + it.steps, it.end) }
                }
                else -> {
                    var best: Best? = null
                    for ((_, b) in mine(t, d, open)) {
                        val got = b()
                        if (got != null && (best == null || got.value > best.value)) best = got
                    }
                    best
                }
            }
        } finally {
            onPath -= key
        }
        if (best != null) memo[key] = best
        return best
    }

    /** The seat's ways on from [t], each a way to value it: its moves, an End Phase per way of Setting, the Battle Phase. */
    private fun mine(t: FxTable, d: Int, open: Boolean): List<Pair<Root, () -> Best?>> = buildList {
        // In the Battle Phase (a decision made there, E.3): Main 2 first, so an attack that changes nothing is not made; then
        // each attack the rules allow, the rest planned whole after it.
        if (open && t.state.phase == DuelPhase.BATTLE) {
            add(Root.Move(FxMove.Phase(DuelPhase.MAIN2)) to { finish(t, d) })
            for (step in BattlePlan.attacks(t.state, seat, kit.catalog)) {
                add(Root.Attack(step.attack.attacker, step.attack.target) to {
                    folded(t, step.actions)?.let { f -> fought(f, d)?.let { b -> Best(b.value, listOf<TurnStep>(TurnStep.Attack(step)) + b.steps, b.end) } }
                })
            }
            return@buildList
        }
        val main2 = t.state.phase == DuelPhase.MAIN2
        for (m in MapMoves.distinct(t, seat, open)) {
            val end = (m as? FxMove.Phase)?.to == DuelPhase.END
            if (main2 && !end) continue
            if (end) {
                val ways = MapMoves.setChoices(t, kit, seat)
                if (ways.cut) cut = true
                for ((sets, table) in ways.ways) {
                    add(Root.End(sets) to { move(table, seat, m, d)?.let { b -> if (sets.isEmpty()) b else Best(b.value, listOf<TurnStep>(TurnStep.Sets(sets)) + b.steps, b.end) } })
                }
            } else add(Root.Move(m) to { move(t, seat, m, d) })
        }
        if (open && t.state.phase == DuelPhase.MAIN1 && FxRules.phaseRefusal(t.state, DuelPhase.BATTLE) == null) add(Root.Battle to { battle(t, d) })
    }

    /** The lowest of [bs] for the seat: the other seat's choice. */
    private fun worst(bs: List<Best?>): Best? = bs.filterNotNull().minByOrNull { it.value }

    /**
     * The other seat's moment: its [uses] (each answer it holds) or [letGo]. Knowing everything ([hold] null) it takes the
     * worst for the seat; not seeing the seat's hand (E.5a) it answers — the worst answer — with chance 1 − [hold], and lets
     * the moment go otherwise. The line kept is the likelier one.
     */
    private fun answered(uses: List<Best?>, letGo: Best?): Best? {
        val h = hold ?: return worst(uses + letGo)
        val use = worst(uses) ?: return letGo
        if (letGo == null) return use
        val line = if (h < 0.5) use else letGo
        return Best((1 - h) * use.value + h * letGo.value, line.steps, line.end)
    }

    /** The other seat's Quick Effects at an open table now: one a card and effect, never an inert card's. */
    private fun theirQuick(t: FxTable): List<FxMove> =
        MapMoves.distinct(t, 1 - seat, false).filter { it is FxMove.Activate && !kit.inertUid(it.uid, t) }

    /** [mover]'s moves at a chain's priority: answer it, pass, or resolve. */
    private fun theirChain(t: FxTable, mover: Int): List<FxMove> =
        MapMoves.distinct(t, mover, false).filter { (it is FxMove.Activate && !kit.inertUid(it.uid, t)) || it == FxMove.Pass || it == FxMove.Resolve }

    /** [m] by [mover] on [t], every way it can be made: the seat's answers the best for it, the other seat's the worst. */
    private fun move(t: FxTable, mover: Int, m: FxMove, d: Int): Best? = node(t, mover, m, run(t, mover, m, emptyList()), 0, d)

    private fun node(t: FxTable, mover: Int, m: FxMove, r: Run, j: Int, d: Int): Best? {
        if (j > r.cut) return null
        if (j == r.taken.size) {
            val next = r.next ?: return null
            return dfs(next, d + 1, true)?.let { Best(it.value, listOf<TurnStep>(TurnStep.Move(mover, m, r.taken, cardOf(t, m))) + it.steps, it.end) }
        }
        val mine = r.makers[j] == seat
        var best: Best? = null
        for (o in r.alts[j]) {
            val c = if (o == r.taken[j]) (if (j == r.cut) null else r) else run(t, mover, m, r.taken.subList(0, j) + listOf(o))
            val b = c?.let { node(t, mover, m, it, j + 1, d) } ?: continue
            if (best == null || (if (mine) b.value > best.value else b.value < best.value)) best = b
        }
        return best
    }

    /** The Battle Phase from [t]: entered, planned whole by [BattlePlan], then Main Phase 2's Sets and the End Phase. */
    private fun battle(t: FxTable, d: Int): Best? {
        val enter = FxMove.Phase(DuelPhase.BATTLE)
        val b = play(t, seat, enter, emptyList()) ?: return null
        if (!FxRules.open(b)) return null
        return fought(b, d)?.let { Best(it.value, listOf<TurnStep>(TurnStep.Move(seat, enter, emptyList())) + it.steps, it.end) }
    }

    /** The rest of a Battle Phase from [b] (in it), planned whole, then Main Phase 2's Sets and the End Phase. */
    private fun fought(b: FxTable, d: Int): Best? {
        // What each table the attacks can leave comes to by the turn's end, worked out once each.
        val after = HashMap<DuelState, Best?>()
        fun rest(f: FxTable): Best? = after.getOrPut(f.state) { finish(f, d) }
        val plan = BattlePlan.best(b, { it.state }, { f, step -> folded(f, step.actions) }, seat, kit.catalog, { f -> rest(f)?.value ?: Double.NEGATIVE_INFINITY })
        val rest = rest(plan.end) ?: return null
        return Best(rest.value, plan.steps.map { TurnStep.Attack(it) } + rest.steps, rest.end)
    }

    /** From the Battle Phase to Main Phase 2, then on to the turn's end. */
    private fun finish(f: FxTable, d: Int): Best? {
        val m2 = play(f, seat, FxMove.Phase(DuelPhase.MAIN2), emptyList()) ?: return null
        return dfs(m2, d + 2, true)?.let { Best(it.value, listOf<TurnStep>(TurnStep.Move(seat, FxMove.Phase(DuelPhase.MAIN2), emptyList())) + it.steps, it.end) }
    }

    /** [actions] made by hand on [t], the engine's state folded as the log will fold them; null when the table refuses one. */
    private fun folded(t: FxTable, actions: List<DuelAction>): FxTable? {
        var at = t
        for (a in actions) {
            val o = DuelRules.apply(at.state, a, seat) as? Outcome.Ok ?: return null
            val fx = FxFold.step(at.fx, at.state, DuelEntry(0, 0L, seat, 0, a), o.state, at.book, at.facts)
            at = at.copy(state = o.state, fx = fx)
        }
        return at
    }

    // ---- the engine ---------------------------------------------------------------------------------------------------------

    private class Run(val taken: List<List<Int>>, val alts: List<List<List<Int>>>, val makers: List<Int>, val cut: Int, val next: FxTable?)

    /** The most engine moves this search makes: its budget, or twice it for a soft search resolving what stands. */
    private val most: Int get() = if (soft) budget * 2 else budget

    private fun run(t: FxTable, mover: Int, m: FxMove, prefix: List<List<Int>>): Run {
        if (spent >= most || cancelled()) throw Stop()
        spent++
        val tape = Tape(t, prefix, mover)
        val p = FxEngine.play(t, mover, m, tape)
        val next = if (p is FxPlay.Done && !tape.violated) FxTable(p.state, p.fx, t.book, t.facts, t.seed) else null
        return Run(tape.taken, tape.alts, tape.makers, tape.cut, next)
    }

    private fun play(t: FxTable, mover: Int, m: FxMove, answers: List<List<Int>>): FxTable? {
        if (spent >= most || cancelled()) throw Stop()
        spent++
        var i = 0
        val p = FxEngine.play(t, mover, m) { answers.getOrNull(i++) ?: Chooser.CANCEL }
        return (p as? FxPlay.Done)?.let { FxTable(it.state, it.fx, t.book, t.facts, t.seed) }
    }

    private inner class Tape(private val t: FxTable, private val prefix: List<List<Int>>, private val mover: Int) : Chooser {
        val taken = ArrayList<List<Int>>()
        val alts = ArrayList<List<List<Int>>>()
        val makers = ArrayList<Int>()
        var violated = false
        var cut = Int.MAX_VALUE
        private val key = TableKey(t, false)
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
            makers += maker(d)
            if (options.inertUse(d, a, inert)) refuse(i)
            return a
        }

        override fun told(d: Decision, answer: List<Int>) {
            if (options.inertUse(d, answer, inert)) refuse(taken.size - 1)
        }

        private fun maker(d: Decision): Int = Deciders.of(d, t.state, mover)
    }

    private fun cardOf(t: FxTable, m: FxMove): Int? = when (m) {
        is FxMove.Activate -> t.code(m.uid)
        is FxMove.NormalSummon -> t.code(m.uid)
        is FxMove.Procedure -> t.code(m.uid)
        else -> null
    }

    companion object {
        /** Engine moves for one turn: about two seconds on the desk. */
        const val DEFAULT_BUDGET = 50_000

        /** A turn of 60 of the seat's moves, with the other seat's passes between them. */
        const val DEFAULT_DEPTH = 160
    }
}
