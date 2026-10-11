package com.kaiharimoto.mastertool.core.duel.coach

import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.prep.Drill
import com.kaiharimoto.mastertool.core.prep.DrillStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drills from your own mistakes (Phase F, F.3): which decisions become drills, their choices, and the boxes they climb. */
class MisplayDrillsTest {
    private val ways = listOf(Way("Battle Phase", 0.0), Way("End the turn", 38.6), Way("Set a card", 3.1), Way("Normal Summon Pawn", 12.0))

    private fun j(at: Int, mark: Mark?, by: String = Provenance.PERSON, ways: List<Way> = this.ways, why: String? = null) = Judged(
        at, 0, by, played = "End the turn", best = "Battle Phase", line = listOf("Battle Phase", "Example Knight attacks directly"),
        drop = 38.6, mark = mark?.name, worlds = 8, why = why, turn = 3, phase = "Main 1", ways = ways,
    )

    @Test
    fun yourMistakesAndBlundersBecomeDrillsAndNothingElse() {
        val book = ReviewBook(
            "r1", played = 5,
            decisions = listOf(
                j(4, Mark.BLUNDER), j(9, Mark.MISTAKE), j(12, Mark.INACCURACY), j(15, null),
                j(18, Mark.BLUNDER, by = Provenance.AI), j(21, Mark.BLUNDER, why = "not a moment the search weighs"),
            ),
        )
        val items = MisplayDrills.items(listOf(book))
        assertEquals(listOf("misplay:r1:4", "misplay:r1:9"), items.map { it.key })
        val item = items.first()
        assertEquals(Mark.BLUNDER, item.mark)
        assertEquals(ways.map { it.words }.toSet(), item.choices.map { it.words }.toSet())
        // Right is within an inaccuracy of the best: the best and the Set, never the end of the turn or the summon.
        assertEquals(setOf("Battle Phase", "Set a card"), item.choices.filter { it.right }.map { it.words }.toSet())
        // The order is the drill's own, the same every time.
        assertEquals(item.choices, MisplayDrills.items(listOf(book)).first().choices)
        assertEquals(item.choices, MisplayDrills.order(item.key, item.choices.reversed()))
    }

    @Test
    fun aReviewBeforeF3DrillsThePlayedAgainstTheBest() {
        val old = Judged(7, 0, Provenance.PERSON, played = "End the turn", best = "Battle Phase", drop = 22.0, mark = Mark.BLUNDER.name)
        val item = MisplayDrills.items(listOf(ReviewBook("r2", decisions = listOf(old)))).single()
        assertEquals(setOf("End the turn" to false, "Battle Phase" to true), item.choices.map { it.words to it.right }.toSet())
        assertEquals("A position, in “Duel 2”: which way is better?", MisplayDrills.question(item, "Duel 2"))
    }

    @Test
    fun theQuestionNeverSaysWhatWasPlayedAndTheVerdictSaysAll() {
        val item = MisplayDrills.items(listOf(ReviewBook("r1", decisions = listOf(j(4, Mark.BLUNDER))))).single()
        val q = MisplayDrills.question(item, "Studio duel")
        assertEquals("Turn 3, Main 1, in “Studio duel”: which way is better?", q)
        assertFalse("End the turn" in q)
        val wrong = item.choices.first { it.words == "End the turn" }
        val v = MisplayDrills.verdict(item, wrong)
        assertTrue(v.startsWith("Not this one: End the turn gives up 38.6 points"), v)
        assertTrue("The best way here: Battle Phase" in v && "?? blunder" in v, v)
        assertTrue(MisplayDrills.verdict(item, item.choices.first { it.words == "Battle Phase" }).startsWith("Right"))
    }

    @Test
    fun aMistakeAnsweredRightClimbsABoxAndWrongGoesBackToTheFirst() {
        val key = MisplayDrills.key("r1", 4)
        var stat = DrillStat()
        stat = Drill.answered(stat, right = true, now = 1_000)
        assertEquals(1, stat.box)
        assertEquals(1, stat.correct)
        stat = Drill.answered(stat, right = true, now = 2_000)
        assertEquals(2, stat.box)
        stat = Drill.answered(stat, right = false, now = 3_000)
        assertEquals(0, stat.box)
        assertEquals(3, stat.seen)
        // The one wrong comes back first.
        assertEquals(key, Drill.next(listOf(MisplayDrills.key("r1", 9), key), mapOf(key to stat, MisplayDrills.key("r1", 9) to DrillStat(1, 1, 3_000, 1)), 3_500))
        assertTrue(MisplayDrills.isMisplay(key))
        assertFalse(MisplayDrills.isMisplay("m1:FIRST"))
        assertNull(MisplayDrills.items(emptyList()).firstOrNull())
    }
}
