package com.kaiharimoto.mastertool.core.duel.mapper.puzzle

import com.kaiharimoto.mastertool.core.ai.eval.PuzzleCards
import com.kaiharimoto.mastertool.core.ai.eval.Puzzles
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.search.TurnValue
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Puzzles of the person's own (Phase M, M4): a position from a duel's log, a goal on the boards, solved against their cards. */
class PuzzleSolveTest {
    private val refKit = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }))

    /** The duel puzzle set's cards as the goldfish reads them: Normal Monsters, and the five Spells' scripts. */
    private val setKit: GoldfishKit by lazy {
        val cards = PuzzleCards.info.map { (code, i) ->
            if (i.kind == CardKind.MONSTER) {
                Card(CardId(code), i.name, "Normal Monster", "normal", race = i.race, attribute = Attribute.fromApi(i.attribute), atk = i.atk, def = i.def, level = i.level)
            } else {
                Card(CardId(code), i.name, "Spell Card", "spell", race = "Normal")
            }
        }
        GoldfishKit.of(GoldfishFixtures.trust(PuzzleCards.scripts), cards)
    }

    private fun game(you: Side, them: Side = Side(), turn: Int = 2, theirLp: Int = 8000): DuelGame {
        val (g0, _) = FxRef.game(you, them, turn = turn)
        return if (theirLp == 8000) g0 else g0.act(listOf(DuelAction.Lp(1, delta = theirLp - 8000)), null).game.let { it.copy(floor = it.cursor) }
    }

    private fun puzzle(g: DuelGame, goal: BoardGoal, knows: String = DuelBrief.FULL) =
        MapPuzzles.of(g, knows, goal, "p1", "Test", "a test", at = 1L)!!

    @Test
    fun theSetsPuzzlesLoadAsPositionsAndTheGoalSearchSolvesThem() {
        val solved = Puzzles.all.map { p ->
            val pos = MapPuzzles.decode(MapPuzzles.encode(Puzzles.position(p)))
            assertNotNull(pos, "${p.id} reads back")
            assertNull(PuzzleSolve.refusal(pos), "${p.id}: ${PuzzleSolve.refusal(pos)}")
            val s = PuzzleSolve.solve(pos, setKit)!!
            if (s.met) {
                // The line, made on the fork as a replay, ends on a table that meets the goal.
                val end = s.turn.game
                val t = FxTable(end.state, FxFold.fold(end.header, end.played, setKit.book, setKit.facts, end.state), setKit.book, setKit.facts, end.header.seed)
                assertTrue(pos.goal.met(t, pos.seat), "${p.id}: the line played out meets ${pos.goal}: ${s.turn.problem}")
            }
            p.id to s.verdict
        }
        // Fifteen of seventeen. The two left need a change of battle position (p10) and a Flip Summon (p11), which are not
        // among the engine's moves yet: each is proved to have no line within what the search plays, as ASSUMES says.
        val unsolved = solved.filter { it.second != PuzzleSolve.Verdict.SOLVED }
        assertEquals(listOf("p10" to PuzzleSolve.Verdict.NONE, "p11" to PuzzleSolve.Verdict.NONE), unsolved)
        assertTrue("Flip Summons" in PuzzleSolve.ASSUMES)
    }

    @Test
    fun theirSetCardStopsALineOnlyWhenTheSolverCanSeeIt() {
        // Scout (1600) on the field, Knight (2100) in hand, against 3000 and their Set Snare (banish a monster of yours).
        val snare = Slot(FxRef.SNARE, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)
        val g = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), them = Side(field = listOf(snare)), theirLp = 3000)
        val lethal = BoardGoal.TheirLp(0)
        // Seen, Snare answers at the worst moment: no line deals 3000 through it, and every line was searched — a proof.
        val seen = PuzzleSolve.solve(puzzle(g, lethal, DuelBrief.FULL), refKit)!!
        assertEquals(PuzzleSolve.Verdict.NONE, seen.verdict, "${seen.turn.result.steps}")
        assertTrue(seen.turn.result.complete)
        // Unseen, it is a blank that plays nothing: summon and attack twice.
        val blind = PuzzleSolve.solve(puzzle(g, lethal, DuelBrief.SELF), refKit)!!
        assertEquals(PuzzleSolve.Verdict.SOLVED, blind.verdict)
        val a = PuzzleSolve.answer(blind, refKit, { FxRef.facts[it]?.name ?: "#$it" }, at = 2L)
        assertEquals("SOLVED", a.verdict)
        assertTrue(a.line.any { "Knight" in it }, "${a.line}")
        assertTrue(PuzzleSolve.words(a).startsWith("Solved"), PuzzleSolve.words(a))
        assertTrue(PuzzleSolve.HIDDEN in PuzzleSolve.assumes(blind.puzzle))
        // A goal it can reach through Snare: their field clear is already so, kept to the turn's end.
        val clear = PuzzleSolve.solve(puzzle(g, BoardGoal.TheirFieldClear), refKit)!!
        assertEquals(PuzzleSolve.Verdict.SOLVED, clear.verdict)
    }

    @Test
    fun theLineShowsTheirAnswerAndTheEndMeetsTheGoal() {
        // Knight in hand, Scout (1600) on the field, against Pawn (1500 ATK) and their Set Snare: summoned, Knight is banished
        // at their worst moment, and Scout still clears Pawn. Snare's use is their worst answer, so the line shows it.
        val snare = Slot(FxRef.SNARE, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)
        val g = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 1))), them = Side(field = listOf(Slot(FxRef.PAWN, 0), snare)))
        val s = PuzzleSolve.solve(puzzle(g, BoardGoal.TheirFieldClear), refKit)!!
        assertEquals(PuzzleSolve.Verdict.SOLVED, s.verdict, "${s.turn.result.steps}")
        val a = PuzzleSolve.answer(s, refKit, { FxRef.facts[it]?.name ?: "#$it" }, at = 2L)
        assertTrue(a.line.any { it.startsWith("They: ") }, "their answer stands in the line: ${a.line}")
        assertEquals("their field clear", a.read)
    }

    @Test
    fun aPositionIsTheLogPlayedAndReadsBack() {
        val g = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), theirLp = 3000)
        // An undone move is not the position.
        val moved = g.act(listOf(DuelAction.Lp(1, delta = -100)), 0).game
        val undone = moved.undo()
        val p = MapPuzzles.of(undone, DuelBrief.FULL, BoardGoal.All(listOf(BoardGoal.TheirLp(0), BoardGoal.YourLp(8000))), "a/b c", " ", "The duel in play", 5L)!!
        assertEquals(undone.played.size, p.record.entries.size)
        assertEquals("Turn 2", p.title)
        assertEquals(0, p.seat)
        assertEquals("puzzles/a-b-c.json", MapPuzzles.path(p.id))
        val back = MapPuzzles.decode(MapPuzzles.encode(p))!!
        assertEquals(p, back)
        assertEquals(undone.state, back.game()!!.state)
        assertTrue(MapPuzzles.position(back).startsWith("Turn 2 · "), MapPuzzles.position(back))
        // A goal this build does not know: the file is not a puzzle it can read, and nothing guesses.
        val newer = MapPuzzles.encode(p).replace("\"their-lp\"", "\"their-hand-empty\"")
        assertNull(MapPuzzles.decode(newer))
    }

    @Test
    fun aPuzzleBeginsInAMainPhaseOfADuelStillGoing() {
        val g = game(Side(field = listOf(Slot(FxRef.KNIGHT, 0))))
        val battle = g.act(listOf(DuelAction.Phase(DuelPhase.BATTLE)), 0).game
        assertTrue(MapPuzzles.refusal(battle)!!.contains("Main Phase"), MapPuzzles.refusal(battle))
        assertNull(MapPuzzles.of(battle, DuelBrief.FULL, BoardGoal.TheirLp(0), "x", "", "", 0L))
        val over = g.act(listOf(DuelAction.Lp(1, set = 0)), null).game
        assertTrue(MapPuzzles.refusal(over)!!.contains("over"))
    }

    @Test
    fun anAnswerFoundWithOtherEffectsIsStale() {
        val g = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), theirLp = 3000)
        val p = puzzle(g, BoardGoal.TheirLp(0))
        val s = PuzzleSolve.solve(p, refKit)!!
        val a = PuzzleSolve.answer(s, refKit, { "#$it" }, 1L)
        assertTrue(!PuzzleSolve.stale(p, a, refKit))
        val fewer = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.filter { it != FxRef.KNIGHT }.mapNotNull { FxRef.book.script(it) }))
        assertTrue(PuzzleSolve.stale(p, a, fewer))
    }

    @Test
    fun eachGoalReadsTheTable() {
        val g = game(Side(field = listOf(Slot(FxRef.KNIGHT, 0), Slot(FxRef.SCOUT, 1))), them = Side(field = listOf(Slot(FxRef.PAWN, 0))), theirLp = 3000)
        val t = FxTable(g.state, FxFold.fold(g.header, g.played, FxRef.book, FxRef.facts, g.state), FxRef.book, FxRef.facts, g.header.seed)
        assertTrue(BoardGoal.TheirLp(3000).met(t, 0))
        assertTrue(!BoardGoal.TheirLp(0).met(t, 0))
        assertTrue(BoardGoal.YourLp(8000).met(t, 0))
        assertTrue(!BoardGoal.TheirFieldClear.met(t, 0))
        assertTrue(BoardGoal.TheirMonsters(1).met(t, 0))
        assertTrue(BoardGoal.Bodies(2).met(t, 0))
        assertTrue(BoardGoal.Control(FxRef.KNIGHT).met(t, 0))
        assertTrue(!BoardGoal.Control(FxRef.PAWN).met(t, 0))
        assertTrue(BoardGoal.TheirAnswers(0).met(t, 0))
        assertEquals("they keep 1 monster", BoardGoal.TheirFieldClear.read(t, 0))
        assertEquals("control Example Knight face-up", BoardGoal.Control(FxRef.KNIGHT).words { FxRef.facts[it]?.name ?: "#$it" })
        // Losing the duel never meets a goal.
        val lost = t.copy(state = t.state.copy(seats = t.state.seats.mapIndexed { i, s -> if (i == 0) s.copy(lp = 0) else s }))
        assertEquals(TurnValue.LOSS, GoalValue(BoardGoal.TheirLp(3000)).of(lost, 0, emptySet()))
        assertTrue(GoalValue.met(GoalValue(BoardGoal.TheirLp(3000)).of(t, 0, emptySet())))
        assertTrue(!GoalValue.met(GoalValue(BoardGoal.TheirLp(0)).of(t, 0, emptySet())))
    }
}
