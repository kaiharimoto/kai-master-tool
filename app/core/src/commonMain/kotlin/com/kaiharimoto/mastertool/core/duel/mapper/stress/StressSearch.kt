package com.kaiharimoto.mastertool.core.duel.mapper.stress

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Options
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey
import com.kaiharimoto.mastertool.core.duel.mapper.BoardCards
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapLine
import com.kaiharimoto.mastertool.core.duel.mapper.MapMoves

/**
 * One move of a stress test's line: [seat] made [move] with [answers]; [card] is the card it is about (canonical), and
 * [choke] marks the other seat spending an interruption.
 */
data class StressStep(val seat: Int, val move: FxMove, val answers: List<List<Int>>, val card: Int? = null, val effect: String = "") {
    val choke: Boolean get() = seat == StressSearch.THEM && move is FxMove.Activate
}

/**
 * What a stress test found for one hand against one set of interruptions (M.md §3.3):
 *
 * - [guarantee]: the most of [trait] seat 0 can guarantee whatever the other seat does with what it holds — exact when
 *   [complete], else a proven lower bound;
 * - [line]: the line that guarantees it, against the other seat's best answers — its choke points ([chokes]) are where they
 *   spend an interruption, and the moves after each its fallback;
 * - [end]: the board the line ends on;
 * - [unopposed] (given) the most with nothing in their hand, and [ifTheyPass] what this line's choices reach when they never
 *   use their cards: the [cost] of playing around them is the difference.
 */
data class StressResult(
    val trait: String,
    val guarantee: Int,
    val complete: Boolean,
    val line: List<StressStep>,
    val end: BoardCards?,
    val endTraits: BoardTraits?,
    val unopposed: Int?,
    val ifTheyPass: Int?,
    val moves: Int,
    val positions: Int,
) {
    val chokes: List<Int> get() = line.indices.filter { line[it].choke }

    /** What playing around the interruptions costs when they are not there: the unopposed best less [ifTheyPass]. */
    val cost: Int? get() = if (unopposed != null && ifTheyPass != null) (unopposed - ifTheyPass).coerceAtLeast(0) else null
}

/** A board's line stressed ([StressSearch.through]): what it keeps, the step their worst answer comes before, and that answer. */
data class Through(val keeps: Int, val complete: Boolean, val at: Int? = null, val answer: StressStep? = null, val replayed: Boolean = true)

/**
 * The stress tests' search (E.md §4.1, M.md §3.2): one turn between two players. Seat 0 plays its hand to maximise one
 * measured [trait] of its end board; seat 1, holding interruptions, answers to minimise it.
 *
 * - **A proof, a threshold at a time.** "Can seat 0 keep at least g?" is an AND-OR search: an OR over seat 0's moves, an AND
 *   over the other seat's answers. The guarantee is the greatest g proved, from 1 upward; a test cut short (the depth bound,
 *   a cycle, the budget) is unknown, so the guarantee is then a proven lower bound and the result says it is incomplete.
 * - **Where the other seat may answer**: whenever it has priority on a chain ([FxEngine.next]), and — the worst case, the
 *   engine's own house ruling for Quick Effects — at every open table after a move, until it lets that moment go.
 * - **A choice is made by whoever the engine says makes it**: inside a move, seat 0's answers are an OR and the other seat's
 *   (`Decision.by`, or its own effect's) an AND. The move is played again once per answer, as the mapper's tape plays it.
 * - **Ends as the mapper ends**: the End Phase is offered from every open table, after Setting the Traps and Quick-Plays in
 *   hand ([MapMoves.setChoices]), and the board is measured once the End Phase is open.
 * - A transposition table per threshold, keyed by the table ([TableKey]: both hands, the chain, priority and passes) and
 *   whether the other seat's moment is open. Deterministic: moves are tried in [MapMoves.distinct]'s fixed order.
 */
class StressSearch(
    private val kit: GoldfishKit,
    val trait: String = DEFAULT_TRAIT,
    private val budget: Int = DEFAULT_BUDGET,
    private val depth: Int = DEFAULT_DEPTH,
    private val cancelled: () -> Boolean = { false },
) {
    private enum class Proof { YES, NO, UNKNOWN }

    private class Stop : RuntimeException()

    private data class Key(val a: Long, val b: Long, val window: Boolean)

    private var spent = 0
    private var limit = budget
    private val options = Options(false)
    private val memo = HashMap<Int, HashMap<Key, Boolean>>()
    private val onPath = HashSet<Key>()
    private var setsCut = false
    private var fodder: Set<Int> = emptySet()

    /** [passBest]'s memo for the line being drawn: what each table reaches if they let every moment go. */
    private val hopes = HashMap<Key, Int?>()

    /** Engine moves spent so far, over every test this search has run. */
    val spentMoves: Int get() = spent

    /**
     * The stress test of [t] (a [StressDeal]'s table, at seat 0's Main Phase): the guarantee, its line and what it costs.
     * [fodder] is left out of every board; [ceiling] is the most with nothing in their hand, when known (the map's): no test
     * goes past it, since the other seat can always let a moment go.
     */
    fun test(t: FxTable, fodder: Set<Int> = emptySet(), ceiling: Int? = null): StressResult {
        this.fodder = fodder
        hopes.clear()
        var g = 0
        var complete = true
        var refuted = false
        try {
            while (ceiling == null || g < ceiling) {
                when (prove(t, g + 1, true, 0)) {
                    Proof.YES -> g++
                    Proof.NO -> { refuted = true; break }
                    Proof.UNKNOWN -> { complete = false; break }
                }
            }
        } catch (_: Stop) {
            complete = false
        }
        if (options.truncated || setsCut) complete = false
        // The line is drawn on what the tests proved; it may spend a fifth more than the budget, and stops short past it.
        limit = budget + budget / 5
        val line = ArrayList<StressStep>()
        var end: FxTable? = null
        var ifTheyPass: Int? = null
        try {
            end = walk(t, g, if (refuted) g + 1 else null, line, theyPass = false)
            ifTheyPass = passBest(t, g, 0, hopes)
        } catch (_: Stop) {
            complete = false
        }
        return StressResult(
            trait, g, complete, line,
            end?.let { BoardCards.of(it, YOU, fodder) }, end?.let { BoardTraits.of(it, YOU, fodder) },
            ceiling, ifTheyPass, spent, memo.values.sumOf { it.size },
        )
    }

    /**
     * What [line] (a kept line of the mapper, played on its own deal) keeps when the other seat answers at the worst point
     * of it (E.md §4.1, `BoardTraits.through`): seat 0 follows the line until it is answered, then plays on freely. The
     * least of the board's own measure and, at every moment the other seat could answer, what seat 0 can still guarantee
     * after that answer. [replayed] false: the line did not play on this table, and nothing is said.
     */
    fun through(t: FxTable, line: MapLine, fodder: Set<Int> = emptySet()): Through {
        this.fodder = fodder
        var cur = t
        var window = true
        var keeps = Int.MAX_VALUE
        var at: Int? = null
        var answer: StressStep? = null
        var complete = true
        var setDone = line.sets.isEmpty()
        /** Every answer the other seat has at [cur] now, each tested against the least found so far. */
        fun answers(step: Int, moves: List<FxMove>) {
            for (m in moves) {
                if (m !is FxMove.Activate) continue
                val cap = if (keeps == Int.MAX_VALUE) null else keeps
                val v = valueAfter(cur, m, cap) ?: run { complete = false; null } ?: continue
                if (v.first < keeps) {
                    keeps = v.first
                    at = step
                    answer = StressStep(THEM, m, emptyList(), cur.code(m.uid), m.effect)
                }
                if (!v.second) complete = false
            }
        }
        try {
            for ((i, step) in line.steps.withIndex()) {
                val move = step.move() ?: return Through(0, false, replayed = false)
                // The other seat's moments before this move: a chain's priority, then an open table's.
                while (true) {
                    val open = FxRules.open(cur)
                    if (window && open && cur.state.phase != DuelPhase.END) {
                        answers(i, theirQuick(cur))
                        window = false
                        continue
                    }
                    if (!open && FxEngine.next(cur) == THEM) {
                        answers(i, FxEngine.moves(cur, THEM))
                        cur = play(cur, THEM, FxMove.Pass, emptyList()) ?: return Through(0, false, replayed = false)
                        continue
                    }
                    break
                }
                if (!setDone && (move as? FxMove.Phase)?.to == DuelPhase.END) {
                    setDone = true
                    cur = sets(cur, line.sets) ?: return Through(0, false, replayed = false)
                }
                cur = play(cur, step.seat, move, step.answers) ?: return Through(0, false, replayed = false)
                window = true
            }
            // Triggers the End Phase set off, answered as any chain is.
            while (!FxRules.open(cur)) {
                val seat = FxEngine.next(cur)
                if (seat == THEM) {
                    answers(line.steps.size, FxEngine.moves(cur, THEM))
                    cur = play(cur, THEM, FxMove.Pass, emptyList()) ?: break
                } else {
                    val m = FxEngine.moves(cur, YOU).firstOrNull { it == FxMove.Pass || it == FxMove.Resolve } ?: break
                    cur = play(cur, YOU, m, emptyList()) ?: break
                }
            }
        } catch (_: Stop) {
            complete = false
        }
        val own = value(cur)
        if (own < keeps) {
            keeps = own
            at = null
            answer = null
        }
        return Through(keeps, complete && !options.truncated, at, answer)
    }

    // ---- the proof ------------------------------------------------------------------------------------------------------

    /** Whether seat 0 can keep at least [g] from [t]; [window]: the other seat's moment at an open table is still to come. */
    private fun prove(t: FxTable, g: Int, window: Boolean, d: Int): Proof {
        if (g <= 0) return Proof.YES
        if (cancelled()) throw Stop()
        val open = FxRules.open(t)
        if (open && t.state.phase == DuelPhase.END) return if (value(t) >= g) Proof.YES else Proof.NO
        val theirs = if (window && open) theirQuick(t) else emptyList()
        val tk = TableKey(t, false).key()
        val key = Key(tk.a, tk.b, theirs.isNotEmpty())
        memo[g]?.get(key)?.let { return if (it) Proof.YES else Proof.NO }
        if (d >= depth || key in onPath) return Proof.UNKNOWN
        onPath += key
        val r = try {
            when {
                theirs.isNotEmpty() -> and(theirs.asSequence().map { m -> { move(t, THEM, m, g, d) } } + sequenceOf({ prove(t, g, false, d) }))
                FxEngine.next(t) == YOU -> or(yours(t, open).asSequence().map { (m, from) -> { move(from, YOU, m, g, d) } })
                else -> {
                    val moves = MapMoves.distinct(t, THEM, false).filter { it is FxMove.Activate || it == FxMove.Pass || it == FxMove.Resolve }
                    // Their answers first: a refutation is likelier there, and an AND stops at the first.
                    and(moves.sortedBy { if (it is FxMove.Activate) 0 else 1 }.asSequence().map { m -> { move(t, THEM, m, g, d) } })
                }
            } ?: Proof.NO
        } finally {
            onPath -= key
        }
        if (r != Proof.UNKNOWN) memo.getOrPut(g) { HashMap() }[key] = r == Proof.YES
        return r
    }

    /** Seat 0's moves on [t], each with the table it is made from (the End Phase once per way of Setting before it). */
    private fun yours(t: FxTable, open: Boolean): List<Pair<FxMove, FxTable>> = MapMoves.distinct(t, YOU, open).flatMap { m ->
        if ((m as? FxMove.Phase)?.to == DuelPhase.END) {
            val c = MapMoves.setChoices(t, kit, YOU)
            if (c.cut) setsCut = true
            c.ways.map { m to it.second }
        } else listOf(m to t)
    }

    /** The other seat's Quick Effects it could use at an open table now: one a card and effect, never an inert card's. */
    private fun theirQuick(t: FxTable): List<FxMove> =
        MapMoves.distinct(t, THEM, false).filter { it is FxMove.Activate && !kit.inertUid(it.uid, t) }

    /** [m] by [mover] on [t]: every way it can be made, each answer chosen by whoever makes it. Null: it cannot be made. */
    private fun move(t: FxTable, mover: Int, m: FxMove, g: Int, d: Int): Proof? = node(t, mover, m, run(t, mover, m, emptyList()), 0, g, d)

    /** The move's answers from decision [j] of [r]'s path on, as a tree: an OR at seat 0's decisions, an AND at the other's. */
    private fun node(t: FxTable, mover: Int, m: FxMove, r: Run, j: Int, g: Int, d: Int): Proof? {
        if (j > r.cut) return null
        if (j == r.taken.size) return r.next?.let { prove(it, g, true, d + 1) }
        val children = r.alts[j].asSequence().map { o ->
            {
                if (o == r.taken[j]) {
                    if (j == r.cut) null else node(t, mover, m, r, j + 1, g, d)
                } else node(t, mover, m, run(t, mover, m, r.taken.subList(0, j) + listOf(o)), j + 1, g, d)
            }
        }
        return if (r.makers[j] == YOU) or(children) else and(children)
    }

    private fun or(children: Sequence<() -> Proof?>): Proof? {
        var any = false
        var unknown = false
        for (c in children) {
            when (c()) {
                Proof.YES -> return Proof.YES
                Proof.UNKNOWN -> { unknown = true; any = true }
                Proof.NO -> any = true
                null -> {}
            }
        }
        return if (!any) null else if (unknown) Proof.UNKNOWN else Proof.NO
    }

    private fun and(children: Sequence<() -> Proof?>): Proof? {
        var any = false
        var unknown = false
        for (c in children) {
            when (c()) {
                Proof.NO -> return Proof.NO
                Proof.UNKNOWN -> { unknown = true; any = true }
                Proof.YES -> any = true
                null -> {}
            }
        }
        return if (!any) null else if (unknown) Proof.UNKNOWN else Proof.YES
    }

    // ---- the line ---------------------------------------------------------------------------------------------------------

    /**
     * The line from [t]: seat 0's moves and answers keep at least [g]; the other seat answers so seat 0 keeps less than
     * [refute] where it can, and lets the moment go where it cannot ([theyPass]: always). The board it ends on.
     */
    private fun walk(t: FxTable, g: Int, refute: Int?, out: MutableList<StressStep>, theyPass: Boolean): FxTable? {
        var cur = t
        var window = true
        var d = 0
        while (true) {
            if (d > depth) return null
            val open = FxRules.open(cur)
            if (open && cur.state.phase == DuelPhase.END) return cur
            val theirs = if (window && open) theirQuick(cur) else emptyList()
            if (theirs.isNotEmpty()) {
                val m = if (theyPass || refute == null) null else theirs.firstOrNull { move(cur, THEM, it, refute, d) == Proof.NO }
                if (m == null) {
                    window = false
                    continue
                }
                cur = step(cur, THEM, m, g, refute, d, out) ?: return null
            } else if (FxEngine.next(cur) == YOU) {
                // Of the moves that keep [g], the one that reaches the most if they let it be: the line a player tries, not
                // the first that happens to keep the guarantee (any move keeps a guarantee of nothing).
                val pick = yours(cur, open).filter { (m, from) -> move(from, YOU, m, g, d) == Proof.YES }
                    .maxByOrNull { (m, from) -> moveBest(from, YOU, m, run(from, YOU, m, emptyList()), 0, g, d, hopes) ?: Int.MIN_VALUE }
                    ?: yours(cur, open).firstOrNull { (m, from) -> move(from, YOU, m, 0, d) != null }
                    ?: return null
                cur = step(pick.second, YOU, pick.first, g, refute, d, out) ?: return null
            } else {
                val moves = MapMoves.distinct(cur, THEM, false)
                val m = (if (theyPass || refute == null) null else moves.firstOrNull { it is FxMove.Activate && move(cur, THEM, it, refute, d) == Proof.NO })
                    ?: moves.firstOrNull { it == FxMove.Pass || it == FxMove.Resolve } ?: return null
                cur = step(cur, THEM, m, g, refute, d, out) ?: return null
            }
            window = true
            d++
        }
    }

    /** [m] by [mover] made on [t] with the answers [walk] chooses; the step kept in [out]. */
    private fun step(t: FxTable, mover: Int, m: FxMove, g: Int, refute: Int?, d: Int, out: MutableList<StressStep>): FxTable? {
        var r = run(t, mover, m, emptyList())
        var j = 0
        while (j < r.taken.size) {
            if (j > r.cut) return null
            val maker = r.makers[j]
            val cands = r.alts[j].mapNotNull { o -> if (o == r.taken[j] && j != r.cut) r else if (o == r.taken[j]) null else run(t, mover, m, r.taken.subList(0, j) + listOf(o)) }
            val chosen = (
                if (maker == YOU) cands.filter { c -> node(t, mover, m, c, j + 1, g, d) == Proof.YES }
                    .maxByOrNull { c -> moveBest(t, mover, m, c, j + 1, g, d, hopes) ?: Int.MIN_VALUE }
                else cands.firstOrNull { c -> refute == null || node(t, mover, m, c, j + 1, refute, d) == Proof.NO }
                ) ?: r.alts[j].firstNotNullOfOrNull { o ->
                val cand = if (o == r.taken[j] && j != r.cut) r else if (o == r.taken[j]) null else run(t, mover, m, r.taken.subList(0, j) + listOf(o))
                cand?.takeIf { node(t, mover, m, it, j + 1, 0, d) != null }
            } ?: return null
            r = chosen
            j++
        }
        val next = r.next ?: return null
        out += StressStep(mover, m, r.taken.toList(), cardOf(t, m), (m as? FxMove.Activate)?.effect.orEmpty())
        return next
    }

    /**
     * The most seat 0 reaches from [t] when the other seat never uses its cards, choosing only moves and answers that keep
     * the guarantee [g]: what playing around them gives back when they were not there. The other seat's own answers inside a
     * move stay the worst for seat 0. Null when no such line ends the turn.
     */
    private fun passBest(t: FxTable, g: Int, d: Int, memo: HashMap<Key, Int?>): Int? {
        if (cancelled()) throw Stop()
        val open = FxRules.open(t)
        if (open && t.state.phase == DuelPhase.END) return value(t)
        if (d > depth) return null
        val tk = TableKey(t, false).key()
        val key = Key(tk.a, tk.b, false)
        if (key in memo) return memo[key]
        memo[key] = null
        val r = if (FxEngine.next(t) == YOU) {
            yours(t, open).mapNotNull { (m, from) -> if (move(from, YOU, m, g, d) == Proof.YES) moveBest(from, YOU, m, run(from, YOU, m, emptyList()), 0, g, d, memo) else null }.maxOrNull()
        } else {
            val m = MapMoves.distinct(t, THEM, false).firstOrNull { it == FxMove.Pass || it == FxMove.Resolve }
            m?.let { moveBest(t, THEM, it, run(t, THEM, it, emptyList()), 0, g, d, memo) }
        }
        memo[key] = r
        return r
    }

    /** [passBest] through one move's answers: seat 0's that keep [g], the best of them; the other seat's, the worst. */
    private fun moveBest(t: FxTable, mover: Int, m: FxMove, r: Run, j: Int, g: Int, d: Int, memo: HashMap<Key, Int?>): Int? {
        if (j > r.cut) return null
        if (j == r.taken.size) return r.next?.let { passBest(it, g, d + 1, memo) }
        val values = r.alts[j].mapNotNull { o ->
            val c = if (o == r.taken[j]) (if (j == r.cut) null else r) else run(t, mover, m, r.taken.subList(0, j) + listOf(o))
            if (c == null) null
            else if (r.makers[j] == YOU && node(t, mover, m, c, j + 1, g, d) != Proof.YES) null
            else moveBest(t, mover, m, c, j + 1, g, d, memo)
        }
        return if (r.makers[j] == YOU) values.maxOrNull() else values.minOrNull()
    }

    // ---- through ----------------------------------------------------------------------------------------------------------

    /**
     * What seat 0 can still guarantee after the other seat makes [m] on [t] (its own answers inside it the worst for seat 0):
     * the greatest g proved, tested from [cap] downward — only whether it is below [cap] matters to the least. With its
     * completeness; null when [m] cannot be made.
     */
    private fun valueAfter(t: FxTable, m: FxMove, cap: Int?): Pair<Int, Boolean>? {
        val top = cap ?: run { value(t) + MAX_GAIN }
        var g = top
        var complete = true
        while (g > 0) {
            when (move(t, THEM, m, g, 0)) {
                Proof.YES -> return g to complete
                Proof.UNKNOWN -> { complete = false; g-- }
                Proof.NO -> g--
                null -> return null
            }
        }
        return 0 to complete
    }

    // ---- the engine ---------------------------------------------------------------------------------------------------------

    /** One play of a move along [prefix]: what was answered, every decision's options and maker, and the table it made. */
    private class Run(
        val taken: List<List<Int>>,
        val alts: List<List<List<Int>>>,
        val makers: List<Int>,
        val cut: Int,
        val next: FxTable?,
    )

    private fun run(t: FxTable, mover: Int, m: FxMove, prefix: List<List<Int>>): Run {
        if (spent >= limit || cancelled()) throw Stop()
        spent++
        val tape = Tape(t, mover, prefix)
        val p = FxEngine.play(t, mover, m, tape)
        val next = if (p is FxPlay.Done && !tape.violated) FxTable(p.state, p.fx, t.book, t.facts, t.seed) else null
        return Run(tape.taken, tape.alts, tape.makers, tape.cut, next)
    }

    /** Plays [m] with [answers] as given (a kept line, a pass); null when it is not made. */
    private fun play(t: FxTable, seat: Int, m: FxMove, answers: List<List<Int>>): FxTable? {
        if (spent >= limit || cancelled()) throw Stop()
        spent++
        var i = 0
        val p = FxEngine.play(t, seat, m) { answers.getOrNull(i++) ?: Chooser.CANCEL }
        return (p as? FxPlay.Done)?.let { FxTable(it.state, it.fx, t.book, t.facts, t.seed) }
    }

    /** [uids] Set face-down before the End Phase, as the line's own end Set them. */
    private fun sets(t: FxTable, uids: List<Int>): FxTable? {
        var s = t.state
        for (u in uids) {
            val zone = s.freeZones(YOU, ZoneKind.SPELL).firstOrNull() ?: return null
            s = com.kaiharimoto.mastertool.core.duel.DuelRules.applyAll(s, listOf(DuelAction.Move(u, zone, CardPosition.FACE_DOWN_DEF, "set")), YOU).first ?: return null
        }
        return FxTable(s, t.fx.copy(setCards = t.fx.setCards + uids), t.book, t.facts, t.seed)
    }

    /** The tape: answers from [prefix], then each decision's first option; every decision's options and who made it. */
    private inner class Tape(private val t: FxTable, private val mover: Int, private val prefix: List<List<Int>>) : Chooser {
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

        /** Who answers [d]: its `by`, else the user of the effect asking (its card's controller), else the mover. */
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
            return source?.let { s -> t.state.cards[s.uid]?.controller } ?: mover
        }
    }

    private fun cardOf(t: FxTable, m: FxMove): Int? = when (m) {
        is FxMove.Activate -> t.code(m.uid)
        is FxMove.NormalSummon -> t.code(m.uid)
        is FxMove.Procedure -> t.code(m.uid)
        else -> null
    }

    /** The measure of seat 0's board on [t]. */
    private fun value(t: FxTable): Int = BoardTraits.of(t, YOU, fodder)[trait]?.toInt() ?: 0

    companion object {
        const val YOU = 0
        const val THEM = 1

        /** What is guaranteed unless asked otherwise: the board's interruptions (M.md §3.2). */
        const val DEFAULT_TRAIT = "interruptions"

        /** A line of seat 0's 60 moves, with the other seat's passes between them. */
        const val DEFAULT_DEPTH = 160

        /** Engine moves for one stress test (M.md §2.2's map budget). */
        const val DEFAULT_BUDGET = 100_000

        /** How far past a table's own measure an answer's aftermath is tested when nothing caps it. */
        private const val MAX_GAIN = 8
    }
}
