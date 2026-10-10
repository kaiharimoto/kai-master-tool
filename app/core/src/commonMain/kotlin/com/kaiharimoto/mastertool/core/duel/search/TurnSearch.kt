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
 * A whole turn by search (Phase E, E.2; `docs/phases/E.md` §4.2): the seat's Main Phase 1 by the engine — every move of its
 * written effects, as the mapper tries them ([MapMoves]) — then, from any open table, either the End Phase or the Battle
 * Phase planned whole ([BattlePlan]) and the End Phase after it, valued at the turn's end by [value].
 *
 * - **The other seat holds nothing it uses** (E.2): at its priority it passes. E.3's player answers for it.
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
) {
    private class Stop : RuntimeException()

    private class Best(val value: Double, val steps: List<TurnStep>, val end: FxTable?)

    private var spent = 0
    private var cut = false
    private val options = Options(false)
    private val memo = HashMap<TableKey.Key, Best>()
    private val onPath = HashSet<TableKey.Key>()
    private var seat = 0
    private var fodder: Set<Int> = emptySet()

    /** The best turn for [seat] from [t] (its own Main Phase, the table open or a chain standing). */
    fun search(t: FxTable, seat: Int, fodder: Set<Int> = emptySet()): TurnResult {
        this.seat = seat
        this.fodder = fodder
        val best = try {
            dfs(t, 0)
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

    private fun dfs(t: FxTable, d: Int): Best? {
        if (cancelled()) throw Stop()
        val open = FxRules.open(t)
        val phase = t.state.phase
        if (open && phase == DuelPhase.END) return Best(value.of(t, seat, fodder), emptyList(), t)
        val key = TableKey(t, false).key()
        memo[key]?.let { return it }
        if (d >= depth || key in onPath) {
            cut = true
            return null
        }
        onPath += key
        val best = try {
            val mover = FxEngine.next(t)
            if (mover != seat) {
                // The other seat lets every moment go.
                val m = FxEngine.moves(t, mover).firstOrNull { it == FxMove.Pass || it == FxMove.Resolve } ?: return null
                val next = play(t, mover, m, emptyList()) ?: return null
                dfs(next, d + 1)?.let { Best(it.value, listOf(TurnStep.Move(mover, m, emptyList())) + it.steps, it.end) }
            } else {
                var best: Best? = null
                fun take(b: Best?) { if (b != null && (best == null || b.value > best!!.value)) best = b }
                val main2 = phase == DuelPhase.MAIN2
                for (m in MapMoves.distinct(t, seat, open)) {
                    val end = (m as? FxMove.Phase)?.to == DuelPhase.END
                    if (main2 && !end) continue
                    if (end) {
                        val ways = MapMoves.setChoices(t, kit, seat)
                        if (ways.cut) cut = true
                        for ((sets, table) in ways.ways) {
                            take(move(table, m, d)?.let { b -> if (sets.isEmpty()) b else Best(b.value, listOf<TurnStep>(TurnStep.Sets(sets)) + b.steps, b.end) })
                        }
                    } else take(move(t, m, d))
                }
                if (open && phase == DuelPhase.MAIN1 && FxRules.phaseRefusal(t.state, DuelPhase.BATTLE) == null) take(battle(t, d))
                best
            }
        } finally {
            onPath -= key
        }
        if (best != null) memo[key] = best
        return best
    }

    /** [m] by the seat on [t], every way it can be made: the seat's answers the best for it, the other seat's the worst. */
    private fun move(t: FxTable, m: FxMove, d: Int): Best? = node(t, m, run(t, m, emptyList()), 0, d)

    private fun node(t: FxTable, m: FxMove, r: Run, j: Int, d: Int): Best? {
        if (j > r.cut) return null
        if (j == r.taken.size) {
            val next = r.next ?: return null
            return dfs(next, d + 1)?.let { Best(it.value, listOf<TurnStep>(TurnStep.Move(seat, m, r.taken, cardOf(t, m))) + it.steps, it.end) }
        }
        val mine = r.makers[j] == seat
        var best: Best? = null
        for (o in r.alts[j]) {
            val c = if (o == r.taken[j]) (if (j == r.cut) null else r) else run(t, m, r.taken.subList(0, j) + listOf(o))
            val b = c?.let { node(t, m, it, j + 1, d) } ?: continue
            if (best == null || (if (mine) b.value > best.value else b.value < best.value)) best = b
        }
        return best
    }

    /** The Battle Phase from [t]: entered, planned whole by [BattlePlan], then Main Phase 2's Sets and the End Phase. */
    private fun battle(t: FxTable, d: Int): Best? {
        val enter = FxMove.Phase(DuelPhase.BATTLE)
        val b = play(t, seat, enter, emptyList()) ?: return null
        if (!FxRules.open(b)) return null
        // What each table the attacks can leave comes to by the turn's end, worked out once each.
        val after = HashMap<DuelState, Best?>()
        fun finish(f: FxTable): Best? = after.getOrPut(f.state) {
            val m2 = play(f, seat, FxMove.Phase(DuelPhase.MAIN2), emptyList()) ?: return@getOrPut null
            dfs(m2, d + 2)?.let { Best(it.value, listOf<TurnStep>(TurnStep.Move(seat, FxMove.Phase(DuelPhase.MAIN2), emptyList())) + it.steps, it.end) }
        }
        val plan = BattlePlan.best(b, { it.state }, { f, step -> folded(f, step.actions) }, seat, kit.catalog, { f -> finish(f)?.value ?: Double.NEGATIVE_INFINITY })
        val rest = finish(plan.end) ?: return null
        return Best(rest.value, listOf<TurnStep>(TurnStep.Move(seat, enter, emptyList())) + plan.steps.map { TurnStep.Attack(it) } + rest.steps, rest.end)
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

    private fun run(t: FxTable, m: FxMove, prefix: List<List<Int>>): Run {
        if (spent >= budget || cancelled()) throw Stop()
        spent++
        val tape = Tape(t, prefix)
        val p = FxEngine.play(t, seat, m, tape)
        val next = if (p is FxPlay.Done && !tape.violated) FxTable(p.state, p.fx, t.book, t.facts, t.seed) else null
        return Run(tape.taken, tape.alts, tape.makers, tape.cut, next)
    }

    private fun play(t: FxTable, mover: Int, m: FxMove, answers: List<List<Int>>): FxTable? {
        if (spent >= budget || cancelled()) throw Stop()
        spent++
        var i = 0
        val p = FxEngine.play(t, mover, m) { answers.getOrNull(i++) ?: Chooser.CANCEL }
        return (p as? FxPlay.Done)?.let { FxTable(it.state, it.fx, t.book, t.facts, t.seed) }
    }

    private inner class Tape(private val t: FxTable, private val prefix: List<List<Int>>) : Chooser {
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

        private fun maker(d: Decision): Int {
            val by = when (d) {
                is Decision.Cards -> d.by
                is Decision.Order -> d.by
                is Decision.YesNo -> d.by
                else -> null
            }
            if (by != null) return by
            val source = when (d) {
                is Decision.Cards -> d.effect
                is Decision.Zone -> d.effect
                is Decision.Position -> d.effect
                is Decision.YesNo -> d.effect
                is Decision.Option -> d.effect
                is Decision.Declare -> d.effect
                is Decision.Order -> null
            }
            return source?.let { s -> t.state.cards[s.uid]?.controller } ?: seat
        }
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
