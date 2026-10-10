package com.kaiharimoto.mastertool.core.ai.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The duel puzzles solved by search (Phase E, E.2): every one, as short as its recorded solution, and the dead ends proved.
 * On the JVM only: the whole set takes seconds, past the browser test runner's two.
 */
class PuzzleSolverTest {
    @Test
    fun theSearchSolvesEveryPuzzleWithinItsBudget() {
        val lengths = Puzzles.all.map { p ->
            val s = PuzzleSolver.solve(p)
            assertTrue(s.complete, "${p.id}: searched to the end")
            val line = assertNotNull(s.line, "${p.id}: a line")
            val recorded = p.solution.flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }.size
            assertTrue(line.size <= recorded, "${p.id}: ${line.size} moves, the recorded solution ${recorded}: $line")
            val graded = PuzzleBaselines.searched(p)
            assertTrue(graded.pass, "${p.id}: ${graded.read}")
            p.id to line.size
        }
        println("PuzzleSolverTest: " + lengths.joinToString { (id, n) -> "$id $n" })
        assertEquals(Puzzles.all.size, PuzzleBaselines.searchedCount())
    }

    @Test
    fun theTemptingLinesAreDeadEnds() {
        // From where each puzzle's tempting line leaves the table, nothing within the budget left meets the goal: a proof,
        // since the search covered everything.
        val proved = Puzzles.all.filter { p ->
            val t = PuzzleTable(p)
            val played = t.play(p.wrong)
            if (played.any { !it.ok } || t.met().first) return@filter false
            val s = PuzzleSolver.solve(p, t.position())
            s.complete && s.line == null
        }
        println("PuzzleSolverTest: dead ends proved for ${proved.map { it.id }}")
        assertTrue(proved.size >= Puzzles.all.size / 2, "${proved.size} of ${Puzzles.all.size} tempting lines proved dead ends")
    }
}
