package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.Lock
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Once per turn (D.md §2.2 `Opt`): counted at activation, by name across copies and printings, per copy, per Duel. */
class FxOptTest {
    private val t = FxRef.table(
        you = Side(field = listOf(Slot(FxRef.SCOUT, 0), Slot(FxRef.SCOUT_ALT, 1), Slot(FxRef.TINKER, 2), Slot(FxRef.TINKER, 3))),
        them = Side(field = listOf(Slot(FxRef.SCOUT, 0))),
    )
    private val scouts = FxRef.uids(t, FxRef.SCOUT)
    private val tinkers = FxRef.uids(t, FxRef.TINKER)

    @Test
    fun byNameCountsEveryCopyAndPrintingForEachPlayer() {
        val opt = Opt.ByName()
        assertNull(FxRules.optRefusal(t, 0, scouts[0], "e1", opt))
        val used = t.copy(fx = FxRules.use(t, 0, scouts[0], "e1", opt))
        assertEquals(FxRules.OPT_USED, FxRules.optRefusal(used, 0, scouts[0], "e1", opt))
        assertEquals(FxRules.OPT_USED, FxRules.optRefusal(used, 0, scouts[1], "e1", opt), "the alternate artwork is the same name")
        assertNull(FxRules.optRefusal(used, 0, scouts[0], "e2", opt), "another effect of the card")
        assertNull(FxRules.optRefusal(used, 1, FxRef.uid(t, FxRef.SCOUT, 1), "e1", opt), "the other player's own use")
        // Twice a turn.
        val twice = Opt.ByName(times = 2)
        val once = t.copy(fx = FxRules.use(t, 0, scouts[0], "e1", twice))
        assertNull(FxRules.optRefusal(once, 0, scouts[1], "e1", twice))
        assertEquals(FxRules.OPT_USED, FxRules.optRefusal(once.copy(fx = FxRules.use(once, 0, scouts[1], "e1", twice)), 0, scouts[0], "e1", twice))
        // The turn's end forgets it.
        assertNull(FxRules.optRefusal(used.copy(fx = used.fx.forTurn(3)), 0, scouts[0], "e1", opt))
    }

    @Test
    fun aGroupSharesOneUseBetweenEffects() {
        val group = Opt.ByName(group = Opt.CARD)
        val used = t.copy(fx = FxRules.use(t, 0, scouts[0], "e1", group))
        assertEquals(FxRules.OPT_USED, FxRules.optRefusal(used, 0, scouts[1], "e2", group), "\"only 1 X effect per turn\"")
        assertEquals("name:${FxRef.SCOUT}:card", FxRules.optKey(FxRef.SCOUT, "e2", group, scouts[0], 0))
    }

    @Test
    fun perCopyIsThisInstanceWhileItStays() {
        val used = t.copy(fx = FxRules.use(t, 0, tinkers[0], "e1", Opt.PerCopy))
        assertEquals(FxRules.OPT_USED, FxRules.optRefusal(used, 0, tinkers[0], "e1", Opt.PerCopy))
        assertNull(FxRules.optRefusal(used, 0, tinkers[1], "e1", Opt.PerCopy), "the other copy")
        // It left and came back: a new instance.
        val back = used.copy(fx = used.fx.moved(tinkers[0], Place.Pile(0, PileKind.HAND)).moved(tinkers[0], Place.Zone(0, ZoneKind.MONSTER, 2)))
        assertNull(FxRules.optRefusal(back, 0, tinkers[0], "e1", Opt.PerCopy))
    }

    @Test
    fun perDuelOutlastsTheTurnAndAnUnreadRuleIsNeverUnlimited() {
        val used = t.copy(fx = FxRules.use(t, 0, scouts[0], "e1", Opt.PerDuel))
        val later = used.copy(fx = used.fx.forTurn(9))
        assertEquals(FxRules.OPD_USED, FxRules.optRefusal(later, 0, scouts[1], "e1", Opt.PerDuel))
        assertEquals(FxRules.OPT_UNREAD, FxRules.optRefusal(t, 0, scouts[0], "e1", Opt.Unknown()))
        assertNull(FxRules.optRefusal(t, 0, scouts[0], "e1", null), "no rule")
        assertEquals(t.fx, FxRules.use(t, 0, scouts[0], "e1", null))
        // What lasts the Duel stays through the turn's end; the rest goes.
        val fx = FxState(
            turn = 2,
            restrictions = listOf(InForce(Restriction(Ban.ACTIVATE), 0, 1, 2), InForce(Restriction(Ban.ACTIVATE, until = Lock.UNTIL_DUEL), 0, 1, 2)),
            levels = listOf(LevelChange(1, 0, to = 1), LevelChange(1, 0, to = 2, until = Lock.UNTIL_DUEL)),
            normals = mapOf(0 to 1), sent = setOf(1), proper = setOf(5), lives = mapOf(5 to 2),
        ).forTurn(3)
        assertEquals(listOf(Lock.UNTIL_DUEL), fx.restrictions.map { it.restriction.until })
        assertEquals(listOf(2), fx.levels.map { it.to })
        assertEquals(0, fx.normalsUsed(0))
        assertEquals(emptySet(), fx.sent)
        assertEquals(setOf(5), fx.proper)
        assertEquals(2, fx.life(5))
    }
}
