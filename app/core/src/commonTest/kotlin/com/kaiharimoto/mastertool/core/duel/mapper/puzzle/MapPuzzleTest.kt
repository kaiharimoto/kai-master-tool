package com.kaiharimoto.mastertool.core.duel.mapper.puzzle

import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A puzzle file read (Phase M, M4): one that names no seat of the table is not a puzzle, and nothing about it throws. */
class MapPuzzleTest {
    private fun puzzle(): MapPuzzle {
        val (g, _) = FxRef.game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), Side(), turn = 2)
        return MapPuzzles.of(g, DuelBrief.FULL, BoardGoal.TheirLp(0), "p1", "Test", "a test", at = 1L)!!
    }

    @Test
    fun aSeatTheTableDoesNotHaveIsNoPuzzleAndThePositionNeverThrows() {
        val p = puzzle()
        val text = MapPuzzles.encode(p)
        // The puzzle's own seat, written before what it knows (the record's moves name seats too).
        val own = "\"seat\":0,\"knows\""
        assertTrue(own in text, text)
        fun seat(n: Int) = text.replace(own, "\"seat\":$n,\"knows\"")
        // As written, and the other seat: both read.
        assertEquals(0, assertNotNull(MapPuzzles.decode(text)).seat)
        assertEquals(1, assertNotNull(MapPuzzles.decode(seat(1))).seat)
        // A file synced or edited with a seat past the table's (the red team, 2026-10: the Puzzles tab closed the app on it).
        assertNull(MapPuzzles.decode(seat(2)))
        assertNull(MapPuzzles.decode(seat(-1)))
        // One made in memory with such a seat reads as a position that could not be read, never an index out of bounds.
        assertEquals("The position could not be read.", MapPuzzles.position(p.copy(seat = 2)))
        assertEquals("The position could not be read.", MapPuzzles.position(p.copy(seat = -1)))
        assertTrue(MapPuzzles.position(p).startsWith("Turn 2 · "), MapPuzzles.position(p))
    }
}
