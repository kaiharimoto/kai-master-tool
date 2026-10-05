package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.Lock
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Restrictions bind (D.md §2.4): left by effects, or applied by a face-up card's continuous effect while it stays face-up. */
class FxRestrictionTest {
    private val t = FxRef.table(
        you = Side(hand = listOf(FxRef.LAMP, FxRef.PAWN, FxRef.WARDEN), field = listOf(Slot(FxRef.SCOUT, 0), Slot(FxRef.TINKER, 1)), extra = listOf(FxRef.BRIDGE, FxRef.PALADIN)),
    )
    private val bridge = FxRef.uid(t, FxRef.BRIDGE)
    private val paladin = FxRef.uid(t, FxRef.PALADIN)

    private fun with(vararg r: InForce) = t.copy(fx = t.fx.copy(restrictions = r.toList()))

    @Test
    fun aLockOnTheExtraDeckBindsAllButWhatItExcepts() {
        val locked = with(InForce(Restriction(Ban.SPECIAL_SUMMON_FROM_EXTRA, except = Filter.Frame(CardFrame.SYNCHRO)), 0, FxRef.uid(t, FxRef.SCOUT), 2))
        assertTrue(FxRules.specialRefusal(locked, 0, bridge, ProcKind.LINK)!!.startsWith("A restriction is in force"))
        assertNull(FxRules.specialRefusal(locked, 0, paladin, ProcKind.SYNCHRO), "a Synchro Monster is excepted")
        assertTrue(FxProcs.options(locked, 0, bridge).isEmpty(), "no procedure is offered under it")
        assertFalse(FxEngine.moves(locked, 0).any { it is FxMove.Procedure && it.uid == bridge })
        assertTrue(FxEngine.moves(t, 0).any { it is FxMove.Procedure && it.uid == bridge }, "without it, the Link Summon is there")
        // It binds its own seat only.
        val theirs = with(InForce(Restriction(Ban.SPECIAL_SUMMON_FROM_EXTRA), 1, 0, 2))
        assertNull(FxRules.specialRefusal(theirs, 0, bridge, ProcKind.LINK))
        // A lock on the Extra Deck leaves the hand free.
        val lamp = FxRef.uid(t, FxRef.LAMP)
        assertNull(FxRules.restricted(locked, 0, Ban.SPECIAL_SUMMON, lamp))
    }

    @Test
    fun noSpecialSummonsBindsTheExtraDeckTooAndNormalSummonsHaveTheirOwn() {
        val none = with(InForce(Restriction(Ban.SPECIAL_SUMMON), 0, 0, 2))
        assertNotNull(FxRules.restricted(none, 0, Ban.SPECIAL_SUMMON_FROM_EXTRA, bridge))
        assertNotNull(FxRules.specialRefusal(none, 0, FxRef.uid(t, FxRef.WARDEN), ProcKind.INHERENT))
        assertNull(FxRules.normalSummonRefusal(none, 0, FxRef.uid(t, FxRef.PAWN)), "a Normal Summon is no Special Summon")
        val noNormal = with(InForce(Restriction(Ban.NORMAL_SUMMON, except = Filter.NameHas("Example")), 0, 0, 2))
        assertNull(FxRules.normalSummonRefusal(noNormal, 0, FxRef.uid(t, FxRef.PAWN)), "Example Pawn is excepted")
        val strict = with(InForce(Restriction(Ban.NORMAL_SUMMON), 0, 0, 2))
        assertNotNull(FxRules.normalSummonRefusal(strict, 0, FxRef.uid(t, FxRef.PAWN)))
        assertFalse(FxEngine.moves(strict, 0).any { it is FxMove.NormalSummon })
    }

    @Test
    fun aContinuousCardBindsWhileItIsFaceUp() {
        // Their Edict: you cannot Special Summon, except Example monsters.
        val board = FxRef.table(
            you = Side(hand = listOf(FxRef.SPRITE, FxRef.LAMP), extra = listOf(FxRef.BRIDGE)),
            them = Side(field = listOf(Slot(FxRef.EDICT, 0, kind = ZoneKind.SPELL))),
        )
        val forced = board.copy(state = board.state)
        assertEquals(listOf(0), FxRules.inForce(forced).map { it.seat }, "it binds the other seat")
        val lamp = FxRef.uid(board, FxRef.LAMP)
        val sprite = FxRef.uid(board, FxRef.SPRITE)
        assertNull(FxRules.restricted(board, 0, Ban.SPECIAL_SUMMON, lamp), "an Example card is excepted")
        assertNotNull(FxRules.restricted(board, 0, Ban.SPECIAL_SUMMON, sprite))
        assertNull(FxRules.restricted(board, 1, Ban.SPECIAL_SUMMON, sprite), "not its own controller")
        // Face-down, it binds nothing.
        val set = FxRef.table(you = Side(hand = listOf(FxRef.SPRITE)), them = Side(field = listOf(Slot(FxRef.EDICT, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL))))
        assertTrue(FxRules.inForce(set).isEmpty())
    }

    @Test
    fun aTurnsLockGoesWithTheTurn() {
        val r = with(InForce(Restriction(Ban.SPECIAL_SUMMON), 0, 0, 2), InForce(Restriction(Ban.ACTIVATE, until = Lock.UNTIL_DUEL), 0, 0, 2))
        val next = r.fx.forTurn(3)
        assertEquals(listOf(Ban.ACTIVATE), next.restrictions.map { it.restriction.ban })
        // A table read on a later turn reads the later turn's state.
        val later = r.copy(state = r.state.copy(turn = 3))
        assertNull(FxRules.restricted(later.current(), 0, Ban.SPECIAL_SUMMON, bridge))
        assertEquals(listOf(0), FxRules.seatsOf(Rel.YOU, 0))
        assertEquals(listOf(1), FxRules.seatsOf(Rel.THEM, 0))
        assertEquals(listOf(0, 1), FxRules.seatsOf(Rel.ANY, 1))
    }
}
