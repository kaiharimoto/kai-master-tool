package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.ShortcutLine

/**
 * A recorded line played through the engine (D.md §5.4, "this line"): a combo's steps, as the command line says them, each
 * made by the trusted effects — the **plan chooser**: for every step, the engine's moves and answers that leave the table as
 * the step itself would (its cards where the step puts them, by identity; a chain standing is left to resolve). It gets there
 * when its needs are in hand, every step is legal by the trusted scripts, and the board meets the target once what stands
 * has resolved and the End Phase is over.
 *
 * A line whose steps activate, summon, Set or use as material a card played as inert is **not computable** ([needsInert]):
 * never reported as 0 %; the search runs instead.
 */
class GoldfishPlan(
    private val combo: Combo,
    private val target: EndBoard,
    private val kit: GoldfishKit,
    private val budget: Int = GoldfishSearch.DEFAULT_BUDGET,
    private val depth: Int = GoldfishSearch.DEFAULT_DEPTH,
    private val ordered: Boolean = false,
    private val zonesMatter: Boolean = false,
    private val cancelled: () -> Boolean = { false },
) {
    private var spent = 0

    /** Plays the line from [t] (hand k's table): how it ended, and the engine's line when it got there. */
    fun play(t: FxTable): GoldfishSearch.Found {
        if (ComboRunner.missing(t.state, 0, combo, kit.catalog).isNotEmpty()) return GoldfishSearch.Found(HandEnd.NO_LINE)
        var cur = t
        val line = ArrayList<LineStep>()
        for (text in combo.steps) {
            val expected = expected(cur, text) ?: return GoldfishSearch.Found(HandEnd.NO_LINE, line, moves = spent)
            val found = match(cur, expected) ?: return GoldfishSearch.Found(if (spent >= budget) HandEnd.UNDECIDED else HandEnd.NO_LINE, line, moves = spent)
            line += found.first
            cur = found.second
        }
        // What stands resolves, the End Phase is entered and its triggers resolved; the board is read then.
        val finish = GoldfishSearch(
            target, kit, budget = (budget - spent).coerceAtLeast(1), depth = depth, ordered = ordered, zonesMatter = zonesMatter,
            cancelled = cancelled, finishOnly = true,
        ).search(cur)
        val moves = spent + finish.moves
        return when (finish.end) {
            HandEnd.REACHED -> finish.copy(line = line + finish.line, moves = moves)
            else -> GoldfishSearch.Found(finish.end, line, moves = moves)
        }
    }

    /** The table [text] leaves, made as the step says (by hand, or its Shortcut with the answers it gives); null when it cannot be. */
    private fun expected(t: FxTable, text: String): DuelState? {
        return when (val p = DuelCommand.parse(text, t.state, 0, kit.catalog, t.seed, anyCopy = true)) {
            is DuelCommand.Parsed.Actions -> DuelRules.applyAll(t.state, p.actions, 0).first
            is DuelCommand.Parsed.Many -> {
                var s: DuelState = t.state
                p.parts.forEach { part -> s = DuelRules.applyAll(s, part.actions, 0).first ?: return null }
                s
            }
            is DuelCommand.Parsed.Shortcut -> {
                val sc = Shortcuts(kit.book, kit.facts, t.fx, resolveAtOnce = true)
                val r = ShortcutLine.answered(p.ask, sc, t.state, 0, kit.catalog, t.seed, FIRST)
                if (!r.ok) null else r.state
            }
            else -> null
        }
    }

    /** The engine's moves (at most [MATCH_DEPTH]) that leave [t] as [expected], and the table after; null when none does. */
    private fun match(t: FxTable, expected: DuelState): Pair<List<LineStep>, FxTable>? {
        val want = signature(expected)
        val options = Options(zonesMatter)
        fun go(cur: FxTable, d: Int, acc: List<LineStep>): Pair<List<LineStep>, FxTable>? {
            if (d > 0 && signature(cur.state) == want) return acc to cur
            if (d >= MATCH_DEPTH) return null
            val seat = FxEngine.next(cur)
            for (m in FxEngine.moves(cur, seat)) {
                if (m is FxMove.Phase) continue
                val stack = ArrayDeque<List<List<Int>>>()
                stack.addLast(emptyList())
                while (stack.isNotEmpty()) {
                    if (spent >= budget || cancelled()) return null
                    val prefix = stack.removeLast()
                    val tape = PlanTape(cur, prefix, options)
                    val p = FxEngine.play(cur, seat, m, tape)
                    spent++
                    for (j in tape.taken.size - 1 downTo prefix.size) {
                        val alts = tape.alts[j]
                        for (a in alts.size - 1 downTo 1) stack.addLast(tape.taken.subList(0, j) + listOf(alts[a]))
                    }
                    if (p !is FxPlay.Done || tape.violated) continue
                    val next = FxTable(p.state, p.fx, cur.book, cur.facts, cur.seed)
                    val card = when (m) {
                        is FxMove.Activate -> cur.code(m.uid)
                        is FxMove.NormalSummon -> cur.code(m.uid)
                        is FxMove.Procedure -> cur.code(m.uid)
                        else -> null
                    }
                    val touched = p.actions.mapNotNull { a -> (a as? DuelAction.Move)?.uid?.takeIf { kit.inertUid(it, cur) } }.mapNotNull { cur.code(it) }.toSet()
                    go(next, d + 1, acc + LineStep(seat, m, tape.taken.toList(), card, touched))?.let { return it }
                }
            }
            return null
        }
        return go(t, 0, emptyList())
    }

    /** The table as a step leaves it, read by identity: each place's cards, faces apart, zones and the chain aside. */
    private fun signature(s: DuelState): String {
        val side = s.seats[0]
        fun codes(uids: List<Int>) = uids.mapNotNull { s.cards[it] }.map { "${kit.canonical(it.code)}${if (it.faceUp) "+" else "-"}" }.sorted()
        val monsters = side.monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == 0 }
        return listOf(
            codes(side.hand), codes(side.gy), codes(side.banished), codes(side.extra), codes(side.deck),
            codes(monsters), codes(side.spells.filterNotNull()), codes(listOfNotNull(side.field)),
            codes(monsters.flatMap { s.cards[it]?.under.orEmpty() }),
        ).joinToString("|")
    }

    private inner class PlanTape(private val t: FxTable, private val prefix: List<List<Int>>, private val options: Options) : Chooser {
        val taken = ArrayList<List<Int>>()
        val alts = ArrayList<List<List<Int>>>()
        var violated = false
        private val key = TableKey(t, ordered)
        private val inert = { uid: Int -> kit.inertUid(uid, t) }

        override fun choose(d: Decision): List<Int> {
            // Zones collapse as in the search: a step is matched by identity and place, never by its zone's number.
            val opts = options.of(key, d, inert)
            if (opts.isEmpty()) {
                violated = true
                return Chooser.CANCEL
            }
            val a = if (taken.size < prefix.size) prefix[taken.size] else opts[0]
            alts += opts
            taken += a
            if (options.inertUse(d, a, inert)) violated = true
            return a
        }

        override fun told(d: Decision, answer: List<Int>) {
            if (options.inertUse(d, answer, inert)) violated = true
        }
    }

    companion object {
        /** The most engine moves one step of a line may stand for: its activation, a pass, its resolution. */
        const val MATCH_DEPTH = 3

        /** A choice a step's words do not settle: the first answer. */
        private val FIRST = Chooser.FIRST

        /**
         * The cards played as inert that [combo]'s steps activate, summon, Set, attach or use as material or a Tribute, read
         * off the steps made by hand on [t] (a hand that holds the combo's needs): the line is not computable when any is.
         * Empty when the steps cannot be laid out on [t] at all (nothing to say about them).
         */
        fun needsInert(combo: Combo, t: FxTable, kit: GoldfishKit): List<Int> {
            val run = ComboRunner.plan(t.state, 0, combo.steps, kit.catalog, t.seed)
            var s = t.state
            val out = LinkedHashSet<Int>()
            run.steps.forEach { (_, actions) ->
                actions.forEach { a ->
                    val uid = when (a) {
                        is DuelAction.Move -> a.uid.takeIf {
                            a.to is Place.Zone || a.to is Place.Under || a.how == "material" || a.how == "tribute" ||
                                (s.placeOf(a.uid) is Place.Zone && a.to is Place.Pile && a.to.kind == PileKind.GY && a.how == "material")
                        }
                        is DuelAction.ChainAdd -> a.uid
                        else -> null
                    }
                    if (uid != null) s.cards[uid]?.let { c -> if (!c.token && kit.inert(c.code)) out += kit.canonical(c.code) }
                    s = DuelRules.applyAll(s, listOf(a), 0).first ?: s
                }
            }
            return out.toList()
        }
    }
}
