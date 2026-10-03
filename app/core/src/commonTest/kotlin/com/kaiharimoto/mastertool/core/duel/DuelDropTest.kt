package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.layout.CardLook
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuelDropTest {
    private val table = ok(DuelFixtures.bare(), DuelAction.Draw(0, 4))
    private val ash = uid(0, 0)
    private val droll = uid(0, 1)
    private val pot = uid(0, 2)
    private val zeus = uid(0, 40)
    private val m3 = Place.Zone(0, ZoneKind.MONSTER, 2)

    @Test
    fun whatTheHighlightSaysIsWhatTheDropDoes() {
        val summon = DuelDrop.intent(table, ash, DropSpot.Zone(m3), catalog)
        assertEquals("Summon to M3", summon.label)
        assertEquals(listOf(DuelAction.Move(ash, m3, CardPosition.FACE_UP_ATK, null)), summon.actions)
        val set = DuelDrop.intent(table, ash, DropSpot.Zone(m3), catalog, alt = true)
        assertEquals("Set in M3", set.label)
        assertEquals(CardPosition.FACE_DOWN_DEF, (set.actions.single() as DuelAction.Move).pos)
        val spell = DuelDrop.intent(table, pot, DropSpot.Zone(Place.Zone(0, ZoneKind.SPELL, 1)), catalog)
        assertEquals(listOf(DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_UP_ATK, "activate"), DuelAction.ChainAdd(0, pot)), spell.actions)
    }

    @Test
    fun aMonsterDroppedOnAMonsterGoesOnTopAndAnyOtherCardUnder() {
        // kai (1.0.87): "when I drag a monster atop another card, it should overlay on top of it instead of attaching itself".
        val s = ok(table, DuelAction.Move(zeus, m3))
        val i = DuelDrop.intent(s, ash, DropSpot.Zone(m3), catalog)
        assertTrue(i.label.startsWith("On top of"), i.label)
        assertEquals(listOf(DuelAction.Move(ash, m3, CardPosition.FACE_UP_ATK, "special", over = true)), i.actions)
        val after = DuelRules.applyAll(s, i.actions).first!!
        assertEquals(ash, after.at(m3))
        assertEquals(listOf(zeus), after.cards.getValue(ash).under)
        // A spell carried onto a monster still goes under it.
        val spell = DuelDrop.intent(s, pot, DropSpot.Zone(m3), catalog)
        assertTrue(spell.label.startsWith("Attach to"))
        assertEquals(listOf(DuelAction.Move(pot, Place.Under(zeus), how = "attach")), spell.actions)
    }

    @Test
    fun pilesTakeShiftAndAlt() {
        val bottom = DuelDrop.intent(table, ash, DropSpot.Pile(0, PileKind.DECK), catalog, shift = true)
        assertEquals(Place.Pile(0, PileKind.DECK, Place.BOTTOM), (bottom.actions.single() as DuelAction.Move).to)
        val down = DuelDrop.intent(table, ash, DropSpot.Pile(0, PileKind.BANISHED), catalog, alt = true)
        assertEquals("Banish face-down", down.label)
        // Every intent applies cleanly.
        listOf(bottom, down).forEach { assertNotNull(DuelRules.applyAll(table, it.actions).first) }
    }

    @Test
    fun reorderingTheHandCountsTheGapItLeaves() {
        val i = DuelDrop.intent(table, ash, DropSpot.Hand(0, 3), catalog)
        val s = DuelRules.applyAll(table, i.actions).first!!
        assertEquals(listOf(droll, pot, ash, uid(0, 3)), s.seats[0].hand)
        assertTrue(DuelDrop.intent(table, ash, DropSpot.Hand(0, 1), catalog).none)
    }

    @Test
    fun framesDrawTheHotSeatWithoutADecksOrder() {
        val l = DuelLayouter.solve(1920f, 1032f, true)
        val frames = DuelFrames.of(table, l, setOf(0, 1))
        assertTrue(frames.filter { it.uid in table.seats[0].hand }.all { it.look == CardLook.FACE })
        assertTrue(frames.filter { it.uid in table.seats[0].deck }.all { it.look == CardLook.BACK })
        val rivalEyes = DuelFrames.of(table, l, setOf(1))
        assertTrue(rivalEyes.filter { it.uid in table.seats[0].hand }.all { it.look == CardLook.BACK })
        // Only a pile's top card is shown, and the hit test finds the hand's card.
        assertEquals(1, frames.count { it.shown && it.uid in table.seats[0].deck })
        val first = frames.first { it.uid == ash }
        assertEquals(ash, DuelFrames.hit(frames, first.centerX, first.centerY)?.uid)
    }
}
