package com.kaiharimoto.mastertool.core.duel.coach

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.duel.search.Root
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import com.kaiharimoto.mastertool.core.duel.search.SearchVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The misplay finder (Phase F, F.1; F.md §1): decisions, what they were among the search's ways, the marks, the rate. */
class ReviewTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }))
    private val person = Provenance(Provenance.PERSON)

    private fun DuelGame.then(seat: Int?, vararg a: DuelAction, by: Provenance? = null): DuelGame =
        act(a.toList(), seat, by = by).also { check(it.ok) { it.problem.orEmpty() } }.game

    /** Seat 0's turn 2 in Main Phase 1: Example Knight (2100) face-up, their field empty and their Life Points at [lp]. */
    private fun lethal(lp: Int = 1000): DuelGame {
        val (g, _) = FxRef.game(Side(field = listOf(Slot(FxRef.KNIGHT, 0))), Side(deck = listOf(FxRef.PAWN)), turn = 2)
        return g.then(null, DuelAction.Lp(1, set = lp)).let { it.copy(floor = it.cursor) }
    }

    private fun review(g: DuelGame, d: Decision): Judged =
        Review.judge(g, d, kit, Prior.Blank, SearchVersions.V0, SearchPlayer.Strength.FAST, words = { root, _, _ -> words(root) })

    private fun words(r: Root): String = when (r) {
        Root.Battle -> "battle"
        is Root.End -> "end"
        is Root.Attack -> "attack"
        Root.LetGo -> "let go"
        is Root.Move -> (r.move as? FxMove.Phase)?.let { "phase ${it.to}" } ?: r.move::class.simpleName.orEmpty()
    }

    @Test
    fun aDecisionIsASeatsOwnGestureNeverTheTablesTalkOrAPeek() {
        val g = lethal()
            .then(0, DuelAction.Note("thinking", 0), by = person)
            .then(0, DuelAction.Phase(DuelPhase.BATTLE), by = person)
            .then(null, DuelAction.Lp(1, delta = -100))
            .then(0, DuelAction.Lp(0, delta = 0), by = Provenance(Provenance.PERSON, peek = true))
            .then(1, DuelAction.Answer(1, false), by = person)
        val ds = Decisions.of(g)
        assertEquals(2, ds.size, "$ds")
        assertEquals(listOf(DuelAction.Phase(DuelPhase.BATTLE)), ds[0].actions)
        assertEquals(0, ds[0].seat)
        assertEquals(Provenance.PERSON, ds[0].by?.by)
        assertEquals(1, ds[1].seat)
        assertTrue(ds.all { it.end > it.at })
    }

    @Test
    fun whatWasPlayedIsReadAsTheSearchsWays() {
        val g = lethal().then(0, DuelAction.EndTurn)
        val d = Decisions.of(g).single()
        val s = g.stateAt(d.at)
        assertTrue(DecisionRoots.matches(d, Root.End(emptyList()), s))
        assertTrue(!DecisionRoots.matches(d, Root.End(listOf(5)), s))
        assertTrue(!DecisionRoots.matches(d, Root.Battle, s))
        val b = lethal().then(0, DuelAction.Phase(DuelPhase.BATTLE))
        val db = Decisions.of(b).single()
        assertTrue(DecisionRoots.matches(db, Root.Battle, b.stateAt(db.at)))
        assertTrue(!DecisionRoots.matches(db, Root.End(emptyList()), b.stateAt(db.at)))
        val knight = lethal().state.cards.values.first { it.code == FxRef.KNIGHT }.uid
        val a = b.then(0, DuelAction.Attack(0, knight, null))
        val da = Decisions.of(a).last()
        assertTrue(DecisionRoots.matches(da, Root.Attack(knight, null), a.stateAt(da.at)))
        assertTrue(!DecisionRoots.matches(da, Root.Attack(knight + 1, null), a.stateAt(da.at)))
    }

    @Test
    fun endingTheTurnWithLethalOnBoardIsABlunderAndTheBetterWayIsTheBattle() {
        val g = lethal().then(0, DuelAction.EndTurn, by = person)
        val j = review(g, Decisions.of(g).single())
        assertTrue(j.judged, "${j.why}")
        assertEquals(Mark.BLUNDER.name, j.mark, "$j")
        assertTrue(j.drop >= 15.0, "$j")
        assertEquals("end", j.played)
        assertEquals("battle", j.best)
        assertEquals(Provenance.PERSON, j.by)
        // The same table, the Battle Phase taken: nothing given up.
        val b = lethal().then(0, DuelAction.Phase(DuelPhase.BATTLE), by = person)
        val fine = review(b, Decisions.of(b).single())
        assertTrue(fine.judged, "${fine.why}")
        assertNull(fine.mark, "$fine")
        assertEquals(0.0, fine.drop)
        // Deterministic (rule 3): the same review again.
        assertEquals(j, review(g, Decisions.of(g).single()))
    }

    @Test
    fun theOtherSeatOutsideItsMomentsIsNotJudgedAndSaysWhy() {
        val g = lethal().then(1, DuelAction.Lp(1, delta = -1), by = person)
        val j = review(g, Decisions.of(g).single())
        assertTrue(!j.judged)
        assertNotNull(j.why)
        assertNull(j.mark)
    }

    @Test
    fun theMarksAreLichesssThresholds() {
        assertNull(Mark.of(4.9))
        assertEquals(Mark.INACCURACY, Mark.of(5.0))
        assertEquals(Mark.MISTAKE, Mark.of(10.0))
        assertEquals(Mark.MISTAKE, Mark.of(14.9))
        assertEquals(Mark.BLUNDER, Mark.of(15.0))
        assertEquals(Mark.BLUNDER, Mark.of(80.0))
    }

    @Test
    fun winChanceIsTheFitAndAWonTurnIsCertain() {
        assertEquals(1.0, WinChance.of(1_000_000.0))
        assertEquals(0.0, WinChance.of(-1_000_000.0))
        assertTrue(WinChance.of(0.0) in 0.3..0.4)
        assertTrue(WinChance.of(5.0) > WinChance.of(0.0))
    }

    @Test
    fun theMonthlyRateCountsThePersonsJudgedDecisionsOnly() {
        fun j(mark: Mark?, by: String = Provenance.PERSON, why: String? = null) = Judged(0, 0, by, mark = mark?.name, why = why)
        val books = listOf(
            ReviewBook("a", played = 1, decisions = listOf(j(Mark.BLUNDER), j(Mark.MISTAKE), j(Mark.INACCURACY), j(null), j(Mark.BLUNDER, by = Provenance.AI))),
            ReviewBook("b", played = 2, decisions = listOf(j(null), j(null, why = "not a moment the search weighs"))),
            ReviewBook("c", played = 40, decisions = listOf(j(Mark.MISTAKE))),
        )
        val months = MisplayRate.byMonth(books) { if (it < 30) "2026-09" else "2026-10" }
        assertEquals(listOf("2026-09", "2026-10"), months.map { it.month })
        val sep = months[0]
        assertEquals(5, sep.decisions)
        assertEquals(2, sep.misplays)
        assertEquals(1, sep.inaccuracies)
        assertEquals(40.0, sep.rate)
        assertTrue(sep.range.first < 40.0 && sep.range.second > 40.0)
        assertEquals(100.0, months[1].rate)
        assertEquals("Sep 26", sep.label)
        val count = MarkCount.of(books[0])
        assertEquals(2, count.blunders)
        assertEquals("1 ?? · 1 ? · 1 ?! in 4 decisions", MarkCount.of(books[0], Provenance.PERSON).words)
    }

    @Test
    fun aReviewRoundTripsAndABrokenFileIsNoReview() {
        val b = ReviewBook(
            "r1", "d1", "search-v0", "Fast", 5L, 4L,
            listOf(Judged(3, 0, Provenance.PERSON, "end", "battle", listOf("Battle Phase", "Knight attacks directly"), 61.2, 9e5, Mark.BLUNDER.name, 1)),
            complete = false,
        )
        assertEquals(b, ReviewCodec.decode(ReviewCodec.encode(b)))
        assertNull(ReviewCodec.decode("{not json"))
        val words = ReviewWords.replay(b, "Lethal missed", all = false)
        assertTrue("?? blunder" in words && "better battle" in words && "stopped part way" in words, words)
    }
}
