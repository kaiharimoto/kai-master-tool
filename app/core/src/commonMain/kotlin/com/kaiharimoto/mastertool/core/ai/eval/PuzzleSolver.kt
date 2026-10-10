package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.duel.DuelState

/**
 * The duel puzzles solved by search (Phase E, E.2; `docs/phases/E.md` §4.2): every op the puzzle's referee admits, tried in
 * the menu's order, deeper by one move at a time up to the puzzle's budget, so the first line found is a shortest one. A
 * position already searched as deep is not searched again (a table of positions, keyed by the table and what the referee
 * has seen this turn).
 *
 * A search that ran out of [MOST_POSITIONS] says so ([Solved.complete] false); one that covered everything and found no
 * line is a proof that none exists within the budget — what makes a puzzle's tempting line ([Puzzle.wrong]) a dead end.
 */
object PuzzleSolver {
    /** The line found ([line], null for none), whether the search covered everything, and the positions it visited. */
    class Solved(val line: List<String>?, val complete: Boolean, val positions: Int)

    /** Positions a solve may visit: far more than any of the 17 needs. */
    const val MOST_POSITIONS = 200_000

    private class Stop : RuntimeException()

    /** [puzzle] from its start, or from [from] (a position reached on its table) with the moves it has left. */
    fun solve(puzzle: Puzzle, from: PuzzleTable.Position? = null, most: Int = MOST_POSITIONS): Solved {
        val table = PuzzleTable(puzzle)
        val start = from ?: table.position()
        val left = (puzzle.budget - start.turn.moves).coerceAtLeast(0)
        // How deep each position was searched without a line: a position met again with no more depth left is skipped.
        val failed = HashMap<Pair<DuelState, PuzzleTurn>, Int>()
        // Each position's moves worked out once: deepening meets the same positions again on every pass.
        val menus = HashMap<Pair<DuelState, PuzzleTurn>, List<Pair<String, PuzzleTable.Position>>>()
        var visited = 0
        val path = ArrayList<String>()
        fun dfs(at: PuzzleTable.Position, depth: Int): Boolean {
            if (puzzle.goal.met(at.game.state, PuzzleCards.catalog).first) return true
            if (depth == 0) return false
            val key = at.game.state to at.turn.copy(moves = 0)
            if ((failed[key] ?: -1) >= depth) return false
            if (++visited > most) throw Stop()
            for ((op, next) in menus.getOrPut(at.game.state to at.turn) { table.moves(at) }) {
                path += op
                if (dfs(next, depth - 1)) return true
                path.removeAt(path.size - 1)
            }
            failed[key] = depth
            return false
        }
        return try {
            for (d in 0..left) {
                path.clear()
                if (dfs(start, d)) return Solved(path.toList(), complete = true, positions = visited)
            }
            Solved(null, complete = true, positions = visited)
        } catch (_: Stop) {
            Solved(null, complete = false, positions = visited)
        }
    }
}
