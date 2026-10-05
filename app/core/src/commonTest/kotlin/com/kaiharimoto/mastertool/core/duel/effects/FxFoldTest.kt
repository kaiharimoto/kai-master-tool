package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Shortcuts
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `FxState` is a fold over the log (D.md §2.1, §4.2, §10 `FxFoldTest`): a tagged log folds to exactly the engine's own state
 * — after every move of every walk, and after the log has been written to a file and read back — and a log made by hand is
 * inferred: its Normal Summons, its summons by `how`, its links, marked [FxState.inferred].
 */
class FxFoldTest {
    private fun fold(g: DuelGame): FxState = FxFold.fold(g.header, g.played, FxRef.book, FxRef.facts, g.state)

    @Test
    fun aTaggedLogFoldsToTheEnginesOwnState() {
        var checked = 0
        for (seed in 1L..40L) {
            FxWalker.walk(seed) { step ->
                val p = step.play as? FxPlay.Done ?: return@walk
                assertEquals(p.fx, fold(step.game), "seed $seed after ${step.move}")
                checked++
            }
        }
        assertTrue(checked > 300, "folded after $checked moves")
    }

    @Test
    fun theFoldSurvivesTheFile() {
        val g = FxWalker.walk(11)
        val back = DuelGame.of(DuelCodec.decode(DuelCodec.encode(g.record()))!!)
        assertEquals(g.entries.map { it.fx }, back.entries.map { it.fx }, "tags and memos read back")
        assertEquals(fold(g), fold(back))
    }

    @Test
    fun refoldingKeepsTheWrittenLinksForResolveByShortcut() {
        // A link the engine made, committed and the log folded again (as `Shortcuts.of` does): its effect, targets and lives stay.
        val (g, t) = FxRef.game(
            Side(field = listOf(Slot(FxRef.SNARE, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL))),
            them = Side(field = listOf(Slot(FxRef.PAWN, 0))),
        )
        val snare = FxRef.uid(t, FxRef.SNARE)
        val pawn = FxRef.uid(t, FxRef.PAWN, 1)
        val p = assertIs<FxPlay.Done>(FxEngine.play(t, 0, FxMove.Activate(snare, "e1"), Chooser.FIRST))
        val game = g.act(p.actions, 0, fx = p.tags).game
        val link = fold(game).links.single()
        assertEquals(FxLink(1, 0, snare, FxRef.SNARE, "e1", 2, mapOf(Pick.TARGETS to listOf(pawn), FxSteps.targetKey(0) to listOf(pawn), "t" to listOf(pawn)),
            script = FxRef.book.hash(FxRef.SNARE), lives = mapOf(pawn to t.fx.life(pawn))), link)
        assertEquals(p.fx, fold(game))
        val sc = Shortcuts.of(game, FxRef.book, FxRef.facts)
        assertTrue(sc.written(game.state, 1))
        val r = sc.resolve(game.state, 1, DuelCatalog.NONE, Chooser.FIRST)
        assertTrue(r.ok, r.problem)
        assertTrue(r.actions.any { it is DuelAction.Move && it.uid == pawn && it.to == Place.Pile(1, PileKind.BANISHED) })
    }

    @Test
    fun aLogMadeByHandIsInferred() {
        val (g, t) = FxRef.game(Side(hand = listOf(FxRef.SCOUT, FxRef.LAMP), deck = listOf(FxRef.PAWN, FxRef.ECHO)), them = Side(hand = listOf(FxRef.PAWN)))
        assertFalse(t.fx.inferred, "the table's own layout infers nothing")
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val lamp = FxRef.uid(t, FxRef.LAMP)
        // Scout Normal Summoned by hand: its Normal Summon counted, its trigger waiting.
        var game = g.act(DuelAction.Move(scout, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"), 0).game
        var fx = fold(game)
        assertTrue(fx.inferred)
        assertEquals(1, fx.normalsUsed(0))
        assertEquals(ProcKind.NORMAL, fx.summoned[scout])
        assertEquals(listOf(scout to "e1"), fx.pending.map { it.uid to it.effect })
        // The trigger put on the chain by hand: it is that effect, its once-per-turn use counted.
        game = game.act(DuelAction.ChainAdd(0, scout), 0).game
        fx = fold(game)
        assertEquals("e1", fx.links.single().effect)
        assertTrue(fx.pending.isEmpty())
        assertEquals(1, fx.uses.size)
        // Resolved by hand, then Lamp Special Summoned by hand: a summon of its own kind.
        game = game.act(DuelAction.ChainResolve, 0).game
        game = game.act(DuelAction.Move(lamp, Place.Zone(0, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_ATK, "special"), 0).game
        fx = fold(game)
        assertTrue(fx.links.isEmpty())
        assertEquals(ProcKind.SPECIAL, fx.summoned[lamp])
        // A link whose effect cannot be told (a card with no script) is kept as unknown, never guessed.
        val theirs = FxRef.uid(t, FxRef.PAWN, 1)
        game = game.act(DuelAction.ChainAdd(1, theirs), 1).game
        assertEquals("", fold(game).links.single().effect)
    }

    @Test
    fun aHandMadeMoveLetsAWaitingTriggerGo() {
        val (g, t) = FxRef.game(Side(hand = listOf(FxRef.SCOUT, FxRef.PAWN), deck = listOf(FxRef.LAMP)))
        val scout = FxRef.uid(t, FxRef.SCOUT)
        var game = g.act(DuelAction.Move(scout, Place.Zone(0, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "normal"), 0).game
        assertEquals(1, fold(game).pending.size)
        game = game.act(DuelAction.Phase(DuelPhase.BATTLE), 0).game
        assertTrue(fold(game).pending.isEmpty(), "the player moved on: the trigger went unused")
    }
}
