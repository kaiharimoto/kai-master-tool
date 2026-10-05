package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The game's rules the engine holds (D.md §2.4): Normal Summons, Tributes, timing, speeds and the phases. */
class FxRulesTest {
    private fun apply(t: FxTable, vararg a: DuelAction): FxTable {
        val (s, problem) = DuelRules.applyAll(t.state, a.toList())
        return t.copy(state = s ?: error(problem!!))
    }

    private val t = FxRef.table(
        you = Side(hand = listOf(FxRef.SCOUT, FxRef.COLOSSUS, FxRef.KNIGHT, FxRef.SPRITE, FxRef.PAWN, FxRef.FLASH), field = listOf(Slot(FxRef.TINKER, 0), Slot(FxRef.LAMP, 1))),
    )

    @Test
    fun tributesByLevelUnlessTheScriptSays() {
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 2, 2, 2), (1..9).map { FxRules.tributes(it) })
        assertEquals(0, FxRules.tributes(6, SummonRule(tributes = 0)))
        assertEquals(3, FxRules.tributes(10, SummonRule(tributes = 3)))
        assertNull(FxRules.tributeRefusal(7, null, 2))
        assertEquals("A Level 7 monster needs 2 Tributes.", FxRules.tributeRefusal(7, null, 1))
        assertEquals("A Level 4 monster needs no Tribute.", FxRules.tributeRefusal(4, null, 1))
        assertNull(FxRules.tributeRefusal(6, SummonRule(tributes = 0), 0))
    }

    @Test
    fun oneNormalSummonATurnInYourMainPhaseWithNothingPending() {
        val scout = FxRef.uid(t, FxRef.SCOUT)
        assertNull(FxRules.normalSummonRefusal(t, 0, scout))
        assertEquals(FxRules.NOT_TURN, FxRules.normalSummonRefusal(t, 1, scout))
        assertEquals(FxRules.NOT_MAIN, FxRules.normalSummonRefusal(apply(t, DuelAction.Phase(DuelPhase.BATTLE)), 0, scout))
        assertEquals(FxRules.NOT_OPEN, FxRules.normalSummonRefusal(apply(t, DuelAction.ChainAdd(0, FxRef.uid(t, FxRef.TINKER))), 0, scout))
        val used = t.copy(fx = t.fx.copy(normals = mapOf(0 to 1)))
        assertEquals(FxRules.USED, FxRules.normalSummonRefusal(used, 0, scout))
        // "Normal Summon 1 more" an Example monster.
        val granted = used.copy(fx = used.fx.copy(grants = listOf(NormalGrant(0, FxRef.uid(t, FxRef.TINKER), Filter.NameHas("Example")))))
        assertNull(FxRules.normalSummonRefusal(granted, 0, scout))
        assertEquals(0, FxRules.normalSlot(granted, 0, scout))
        assertEquals(FxRules.USED, FxRules.normalSummonRefusal(granted, 0, FxRef.uid(t, FxRef.SPRITE)), "the grant is for Example monsters")
        assertEquals(FxRules.OWN, FxRules.normalSlot(t, 0, scout))
        val spent = granted.copy(fx = granted.fx.copy(grants = granted.fx.grants.map { it.copy(used = true) }))
        assertEquals(FxRules.USED, FxRules.normalSummonRefusal(spent, 0, scout))
        // Not from anywhere but the hand.
        assertTrue(FxRules.normalSummonRefusal(t, 0, FxRef.uid(t, FxRef.TINKER))!!.contains("hand"))
    }

    @Test
    fun whatIsNeverNormalSummoned() {
        val f = FxRef.facts
        assertEquals(FxRules.NOT_MAIN_DECK_MONSTER, FxRules.normalKindRefusal(f[FxRef.BRIDGE], null))
        assertEquals(FxRules.NOT_MAIN_DECK_MONSTER, FxRules.normalKindRefusal(f[FxRef.FLASH], null))
        assertEquals(FxRules.NOT_MAIN_DECK_MONSTER, FxRules.normalKindRefusal(null, null))
        assertEquals(FxRules.RITUAL_NORMAL, FxRules.normalKindRefusal(f[FxRef.ORACLE], null))
        assertEquals(FxRules.CANNOT_NORMAL, FxRules.normalKindRefusal(f[FxRef.WARDEN], FxRef.book.script(FxRef.WARDEN)!!.summon))
        assertNull(FxRules.normalKindRefusal(f[FxRef.PAWN], null))
    }

    @Test
    fun theEngineNormalSummonsWithTributesAsOrdinaryMoves() {
        val moves = FxEngine.moves(t, 0)
        val colossus = FxRef.uid(t, FxRef.COLOSSUS)
        assertTrue(FxMove.NormalSummon(colossus) in moves && FxMove.NormalSummon(colossus, set = true) in moves)
        assertFalse(moves.any { it is FxMove.NormalSummon && it.uid == FxRef.uid(t, FxRef.FLASH) }, "a Spell is not summoned")
        val tinker = FxRef.uid(t, FxRef.TINKER)
        val lamp = FxRef.uid(t, FxRef.LAMP)
        // Both monsters it controls are the two Tributes it needs: one legal answer, never asked (D.md §2.5).
        val chooser = FxRef.Answers({ d -> assertIs<Decision.Zone>(d); listOf(1) })
        val p = assertIs<FxPlay.Done>(FxEngine.play(t, 0, FxMove.NormalSummon(colossus), chooser))
        assertEquals(
            listOf(
                DuelAction.Move(tinker, Place.Pile(0, PileKind.GY), how = "tribute"),
                DuelAction.Move(lamp, Place.Pile(0, PileKind.GY), how = "tribute"),
                DuelAction.Move(colossus, Place.Zone(0, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_ATK, "normal"),
            ),
            p.actions,
        )
        assertTrue(p.tags.all { it.uid == colossus && it.effect == FxTag.RULE && it.part == FxTag.RULE && it.script == FxCodec.hash(FxRef.book.script(FxRef.COLOSSUS)!!) })
        assertEquals(1, p.fx.normalsUsed(0))
        assertEquals(ProcKind.TRIBUTE, p.fx.summoned[colossus])
        assertEquals(setOf(tinker, lamp), p.fx.sent)
        assertEquals(listOf(Event.SENT_TO_GY, Event.LEFT_FIELD, Event.SENT_TO_GY, Event.LEFT_FIELD, Event.SUMMONED, Event.NORMAL_SUMMONED), p.events.map { it.event })
        assertEquals(Cause.TRIBUTE, p.events.first().cause)
        assertEquals(colossus, p.state.seats[0].monsters[1])
        // The turn's summon is spent.
        val after = t.copy(state = p.state, fx = p.fx)
        assertFalse(FxEngine.moves(after, 0).any { it is FxMove.NormalSummon })
        assertEquals(FxRules.USED, (FxEngine.play(after, 0, FxMove.NormalSummon(FxRef.uid(t, FxRef.SCOUT)), Chooser.FIRST) as FxPlay.Refused).why)
    }

    @Test
    fun aSetIsNoSummonAndAnswersAreChecked() {
        val knight = FxRef.uid(t, FxRef.KNIGHT)
        val p = assertIs<FxPlay.Done>(FxEngine.play(t, 0, FxMove.NormalSummon(knight, set = true), FxRef.Answers({ listOf(0) })))
        assertEquals(DuelAction.Move(knight, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_DOWN_DEF, "set"), p.actions.single(), "Level 6, set with no Tribute by its rule")
        assertTrue(p.events.isEmpty(), "a Set is not a summon")
        assertTrue(knight in p.fx.setCards && knight !in p.fx.summoned)
        // A cancel commits nothing; an answer out of bounds is a cancel.
        val colossus = FxRef.uid(t, FxRef.COLOSSUS)
        assertEquals(FxPlay.Cancelled, FxEngine.play(t, 0, FxMove.NormalSummon(colossus), FxRef.Answers({ Chooser.CANCEL })))
        assertEquals(FxPlay.Cancelled, FxEngine.play(t, 0, FxMove.NormalSummon(colossus), FxRef.Answers({ listOf(9) })), "a zone out of bounds")
        // A card the engine does not know is summoned by hand.
        val blank = t.copy(book = ScriptBook.EMPTY)
        assertFalse(FxEngine.moves(blank, 0).any { it is FxMove.NormalSummon && it.uid == FxRef.uid(t, FxRef.SCOUT) })
        assertTrue(FxEngine.moves(blank, 0).any { it is FxMove.NormalSummon && it.uid == FxRef.uid(t, FxRef.PAWN) }, "a Normal Monster needs no script")
        assertIs<FxPlay.Refused>(FxEngine.play(blank, 0, FxMove.NormalSummon(FxRef.uid(t, FxRef.SCOUT)), Chooser.FIRST))
    }

    @Test
    fun thePhasesOnlyGoForward() {
        assertEquals("The phases only go forward: it is the Main 1 Phase.", FxRules.phaseRefusal(t.state, DuelPhase.STANDBY))
        assertNull(FxRules.phaseRefusal(t.state, DuelPhase.END), "skipping is forward")
        assertEquals(listOf(DuelPhase.BATTLE, DuelPhase.MAIN2, DuelPhase.END), FxRules.phases(t, 0))
        assertEquals(emptyList(), FxRules.phases(t, 1), "the turn player's")
        val first = FxRef.table(Side(hand = listOf(FxRef.PAWN)), turn = 1)
        assertEquals(FxRules.NO_BATTLE_FIRST, FxRules.phaseRefusal(first.state, DuelPhase.BATTLE))
        assertEquals(listOf(DuelPhase.MAIN2, DuelPhase.END), FxRules.phases(first, 0))
        assertTrue(FxRules.phaseRefusal(apply(t, DuelAction.ChainAdd(0, FxRef.uid(t, FxRef.TINKER))).state, DuelPhase.END)!!.contains("chain"))
        val p = assertIs<FxPlay.Done>(FxEngine.play(t, 0, FxMove.Phase(DuelPhase.MAIN2), Chooser.FIRST))
        assertEquals(listOf(DuelAction.Phase(DuelPhase.MAIN2)), p.actions)
        assertEquals(DuelPhase.MAIN2, p.state.phase)
        assertIs<FxPlay.Refused>(FxEngine.play(t, 0, FxMove.Phase(DuelPhase.DRAW), Chooser.FIRST))
    }

    @Test
    fun spellSpeedsAndTiming() {
        val b = FxRef.book
        val f = FxRef.facts
        fun speed(code: Int, id: String = "e1") = FxRules.speed(b.effect(code, id)!!, f[code])
        assertEquals(2, speed(FxRef.FLASH), "Quick-Play")
        assertEquals(3, speed(FxRef.DENIAL), "Counter Trap")
        assertEquals(2, speed(FxRef.SNARE), "Normal Trap")
        assertEquals(1, speed(FxRef.FUSION), "Normal Spell")
        assertEquals(1, speed(FxRef.SCOUT), "a trigger")
        assertEquals(2, speed(FxRef.SCOUT, "e2"), "a quick effect")
        assertEquals(1, speed(FxRef.LAMP), "an ignition")
        assertEquals(0, speed(FxRef.EDICT, "e2"), "continuous: never activated")

        val board = FxRef.table(Side(hand = listOf(FxRef.FLASH), field = listOf(Slot(FxRef.SNARE, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL), Slot(FxRef.FLASH, 1, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL))))
        val snare = FxRef.uid(board, FxRef.SNARE)
        val setFlash = FxRef.uids(board, FxRef.FLASH).first { board.state.placeOf(it) is Place.Zone }
        val handFlash = FxRef.uids(board, FxRef.FLASH).first { it != setFlash }
        val snareE = b.effect(FxRef.SNARE, "e1")!!
        assertNull(FxRules.setTurnRefusal(board, snare, snareE), "set on an earlier turn")
        val justSet = board.copy(fx = board.fx.copy(setCards = setOf(snare, setFlash)))
        assertEquals(FxRules.SET_TURN, FxRules.setTurnRefusal(justSet, snare, snareE))
        assertEquals(FxRules.SET_TURN, FxRules.setTurnRefusal(justSet, setFlash, b.effect(FxRef.FLASH, "e1")!!))
        assertNull(FxRules.setTurnRefusal(justSet, snare, snareE.copy(sameTurn = true)), "its flag allows it")
        assertNull(FxRules.quickPlayRefusal(board, 0, handFlash))
        assertEquals(FxRules.QUICK_PLAY_HAND, FxRules.quickPlayRefusal(board.copy(state = board.state.copy(active = 1)), 0, handFlash))
        assertNull(FxRules.quickPlayRefusal(board.copy(state = board.state.copy(active = 1)), 0, setFlash), "set, it answers on their turn")
        assertEquals(3, FxRules.spellZones(board, 0, handFlash).size)
    }
}
