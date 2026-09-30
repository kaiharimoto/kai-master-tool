package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.pages.SidingBoardMarks
import kotlin.test.Test
import kotlin.test.assertEquals

/** The siding board marks the first copies of each card a turn moves, in the deck's order. */
class SidingBoardTest {
    private fun ids(vararg v: Int) = v.map(::CardId)

    @Test
    fun theFirstCopiesOfEachMovedCardAreMarked() {
        val deck = ids(1, 1, 1, 2, 3, 3, 2)
        val marks = SidingBoardMarks.of(deck, ids(1, 1, 3, 2, 2))
        assertEquals(listOf(true, true, false, true, true, false, true), marks)
    }

    @Test
    fun nothingMovedMarksNothingAndAskingAgainGivesTheSameAnswer() {
        val deck = ids(5, 5, 6)
        assertEquals(listOf(false, false, false), SidingBoardMarks.of(deck, emptyList()))
        val moved = ids(6)
        assertEquals(SidingBoardMarks.of(deck, moved), SidingBoardMarks.of(deck, moved))
    }
}
