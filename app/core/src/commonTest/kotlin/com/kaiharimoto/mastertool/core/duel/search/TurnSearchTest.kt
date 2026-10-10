package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A whole turn by search (Phase E, E.2) on the reference cards: the engine's Main Phase, the battle planned, the turn's end. */
class TurnSearchTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }))

    /** [you] against [them] on seat 0's turn [turn], their life points [theirLp]: the duel and its engine table. */
    private fun game(you: Side, them: Side = Side(), turn: Int = 2, theirLp: Int = 8000): Pair<DuelGame, FxTable> {
        val (g0, _) = FxRef.game(you, them, turn = turn)
        val g = if (theirLp == 8000) g0 else g0.act(listOf(DuelAction.Lp(1, delta = theirLp - 8000)), null).game.let { it.copy(floor = it.cursor) }
        return g to FxTable(g.state, FxFold.fold(g.header, g.played, FxRef.book, FxRef.facts, g.state), FxRef.book, FxRef.facts, g.header.seed)
    }

    private fun attacks(r: TurnResult) = r.steps.filterIsInstance<TurnStep.Attack>()

    @Test
    fun aSummonAndTwoDirectAttacksWinTheGame() {
        // Scout (1600) on the field and Knight (2100, no Tribute) in hand against 3000 and an empty field: 3700 is lethal.
        val (_, t) = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), theirLp = 3000)
        val r = TurnSearch(kit).search(t, 0)
        assertTrue(r.complete)
        assertEquals(TurnValue.WIN, r.value)
        assertTrue(r.steps.any { it is TurnStep.Move && it.move is FxMove.NormalSummon }, "Knight is summoned first: ${r.steps}")
        assertEquals(2, attacks(r).size)
        assertTrue(attacks(r).all { it.step.attack.target == null })
        assertTrue(r.parts!!.won)
    }

    @Test
    fun theFirstTurnHasNoBattle() {
        val (_, t) = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), turn = 1, theirLp = 3000)
        val r = TurnSearch(kit).search(t, 0)
        assertTrue(r.complete)
        assertTrue(attacks(r).isEmpty())
        assertTrue(r.steps.none { it is TurnStep.Move && it.move == FxMove.Phase(DuelPhase.BATTLE) })
        assertEquals(DuelPhase.END, r.end?.state?.phase)
    }

    @Test
    fun aWeakerMonsterIsAttackedAndTheLineReplaysToItsTable() {
        // Knight (2100) against Pawn (1500 ATK): destroying it is worth more than leaving it, and deals 600.
        val (g, t) = game(Side(field = listOf(Slot(FxRef.KNIGHT, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0))))
        val r = TurnSearch(kit).search(t, 0)
        assertTrue(r.complete)
        assertEquals(1, attacks(r).size, attacks(r).joinToString { "${it.step.attack} ${it.step.actions}" })
        val a = attacks(r).single().step.attack
        assertEquals(FxRef.uid(t, FxRef.PAWN, 1), a.target)
        assertEquals(0, r.parts!!.theirBodies)
        assertEquals(600, r.parts!!.dealt)
        val played = TurnLine.play(g, t, 0, r.steps)
        assertEquals(null, played.problem)
        assertEquals(r.end!!.state.seats.map { it.lp }, played.game.state.seats.map { it.lp })
        assertEquals(r.end!!.state.seats.map { it.monsters }, played.game.state.seats.map { it.monsters })
        assertEquals(TurnValue.V0.of(r.end!!, 0), TurnValue.V0.of(played.table, 0))
    }

    @Test
    fun anAttackIntoALossIsLeftAlone() {
        // Pawn (1500, a Normal Monster) against Knight (2100 ATK): attacking loses Pawn and 600; the turn ends without it.
        // (Scout would attack: in the GY its Quick Effect is an interruption, worth the trade by version 0's weights.)
        val (_, t) = game(Side(field = listOf(Slot(FxRef.PAWN, 0))), them = Side(field = listOf(Slot(FxRef.KNIGHT, 0))))
        val r = TurnSearch(kit).search(t, 0)
        assertTrue(attacks(r).isEmpty(), "${r.steps} " + attacks(r).joinToString { "${it.step.attack} ${it.step.actions}" } + " value ${r.value}")
        assertEquals(0, r.parts!!.taken)
    }

    @Test
    fun aTurnIsSolvedOnTheTableAsTheSeatSeesIt() {
        // Their Set Snare is an interruption with a written effect: the seat cannot see it, so the solve never counts it.
        val snare = Slot(FxRef.SNARE, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)
        val (g, _) = game(Side(hand = listOf(FxRef.KNIGHT), field = listOf(Slot(FxRef.SCOUT, 0))), them = Side(hand = listOf(FxRef.WARD), field = listOf(snare)), theirLp = 3000)
        val seen = TurnSolve.solve(g, 0, kit)!!
        assertEquals(null, seen.problem)
        assertEquals(TurnValue.WIN, seen.result.value)
        assertEquals(0, seen.result.parts!!.theirInterruptions)
        // The fork holds no card the seat could not see: their hand and Set card are unknown.
        val theirs = seen.fork.state.cards.values.filter { it.owner == 1 }
        assertTrue(theirs.none { it.code == FxRef.WARD || it.code == FxRef.SNARE }, "${theirs.map { it.code }}")
        // The line was made on the fork: it opens as a replay, ending as the search did.
        assertTrue(seen.game.state.seats[1].lp <= 0)
        assertTrue(seen.game.played.size > seen.fork.played.size)
        // Read back as a replay is, it folds from the fork's own table (the header's start) to the same end.
        val back = DuelGame.of(DuelCodec.decode(DuelCodec.encode(seen.game.record()))!!)
        assertEquals(seen.game.state, back.state)
        assertEquals(seen.fork.state, back.stateAt(0))
        // A dealt duel's header keeps no table: it writes as it did.
        assertTrue("\"start\"" !in DuelCodec.encode(g.record()))
        // Not the seat's turn, or not a Main Phase: nothing to solve.
        assertEquals(null, TurnSolve.solve(g, 1, kit))
        assertTrue(TurnSolve.refusal(g, 1)!!.contains("not that seat's turn"))
    }
}
