package com.kaiharimoto.mastertool.core.duel.mapper.puzzle

import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.search.TurnLine
import com.kaiharimoto.mastertool.core.duel.search.TurnSolve

/**
 * A puzzle solved (Phase M, M4; `docs/phases/M.md` §5): the whole turn searched by `TurnSearch` from the position, on a fork of
 * it as the solving seat sees it ([MapPuzzle.knows]), valued by the goal ([GoalValue]) — and **against the other seat**: every
 * card of theirs the solver sees answers at the worst moment for it, at its priority and at every open table after a move (the
 * engine's house ruling for Quick Effects, as the stress tests play it). So "solved" is a line that meets the goal whatever
 * they do with what is seen, and the line kept shows their worst answer in it.
 *
 * Three answers, never a fourth: [Verdict.SOLVED], a line; [Verdict.NONE], every line searched and none meets it — a proof,
 * within what a turn's search plays ([ASSUMES]); [Verdict.UNKNOWN], none found before the budget ran out, which proves
 * nothing. The line opens as an unsaved replay of the fork, so the position itself never changes.
 */
object PuzzleSolve {
    enum class Verdict(val words: String) {
        SOLVED("Solved"),
        NONE("No solution"),
        UNKNOWN("Not found"),
    }

    /** A solve: the puzzle, the turn search's answer on its fork, and the verdict. */
    class Solved(val puzzle: MapPuzzle, val turn: TurnSolve.Solved, val verdict: Verdict) {
        val met: Boolean get() = verdict == Verdict.SOLVED
    }

    /** Engine moves a puzzle is given: four turns' worth, since a puzzle is asked once and answered for good. */
    const val BUDGET = 200_000

    /** What every answer assumes, said beside it. */
    const val ASSUMES = "Their cards you can see answer after each of your summons and effects, at the worst moment for you. " +
        "Battle goes by the printed ATK and DEF, with no answer inside it; Main Phase 2 only Sets cards; changes of battle " +
        "position and Flip Summons are not among the moves yet."

    /** Said too when the solver could not see all of their cards. */
    const val HIDDEN = "Their cards you could not see when it was saved play nothing."

    /** Why [p] cannot be solved, or null. */
    fun refusal(p: MapPuzzle): String? {
        val g = p.game() ?: return "The position could not be read: it may have been written by a newer version."
        if (g.state.active != p.seat) return "The position is not the solving seat's turn."
        return MapPuzzles.refusal(g)
    }

    /** [p]'s position as the solver sees it, a table of its own to open as a replay; null when it cannot be read. */
    fun position(p: MapPuzzle): DuelGame? = p.game()?.let { TurnSolve.fork(it, p.seat, p.knows) }

    /** [p] solved within [budget] engine moves; null when it cannot be ([refusal]). */
    fun solve(p: MapPuzzle, kit: GoldfishKit, budget: Int = BUDGET, by: Provenance? = null, cancelled: () -> Boolean = { false }): Solved? {
        if (refusal(p) != null) return null
        val g = p.game() ?: return null
        val s = TurnSolve.solve(g, p.seat, kit, p.knows, budget, by, GoalValue(p.goal), answers = true, cancelled = cancelled) ?: return null
        val verdict = when {
            GoalValue.met(s.result.value) -> Verdict.SOLVED
            s.result.complete -> Verdict.NONE
            else -> Verdict.UNKNOWN
        }
        return Solved(p, s, verdict)
    }

    /** The answer kept on the puzzle: the line in words ([name] a passcode's), what the goal read, and the effects' print. */
    fun answer(s: Solved, kit: GoldfishKit, name: (Int) -> String, at: Long): PuzzleAnswer {
        val r = s.turn.result
        val cards = s.turn.fork.state.cards
        fun uidName(u: Int): String = cards[u]?.code?.takeIf { it != 0 }?.let(name) ?: "a card"
        return PuzzleAnswer(
            verdict = s.verdict.name,
            line = if (s.met) TurnLine.words(r.steps, s.puzzle.seat, name, ::uidName, theirs = true) else emptyList(),
            read = r.end?.let { s.puzzle.goal.read(it, s.puzzle.seat) }.orEmpty(),
            moves = r.moves,
            positions = r.positions,
            complete = r.complete,
            effects = kit.trust.library(MapPuzzles.cards(s.puzzle)),
            at = at,
        )
    }

    /** Whether [a] was found with other written effects than [kit] holds now: solve again. */
    fun stale(p: MapPuzzle, a: PuzzleAnswer, kit: GoldfishKit): Boolean = a.effects != kit.trust.library(MapPuzzles.cards(p))

    /** The verdict of [a] in a sentence. */
    fun words(a: PuzzleAnswer): String {
        val v = Verdict.entries.firstOrNull { it.name == a.verdict } ?: return "Not solved yet."
        return when (v) {
            Verdict.SOLVED -> "Solved in ${a.line.size} step${if (a.line.size == 1) "" else "s"}" + (if (a.read.isNotEmpty()) ": ${a.read}." else ".")
            Verdict.NONE -> "No line reaches it: every line was searched (${a.moves} moves), so none exists within what the search plays."
            Verdict.UNKNOWN -> "No line was found before the budget ran out (${a.moves} moves): that proves nothing either way."
        }
    }

    /** What [p]'s answer assumes, in words. */
    fun assumes(p: MapPuzzle): String = if (p.knows == DuelBrief.FULL) ASSUMES else "$ASSUMES $HIDDEN"
}
