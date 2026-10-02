package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelViewTest {

    @Test
    fun noPasscodeOfAHiddenCardReachesTheOtherSeat() {
        var g = DuelGame.start(header())
        val hand = g.state.seats[0].hand
        g = g.act(DuelAction.Move(hand[0], Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK, "set"), 0).game
        val rival = DuelView.of(g.state, 1, secret = g.header.seed)
        val mine = DuelView.of(g.state, 0, secret = g.header.seed)
        assertTrue(rival.seats[0].hand.all { it.code == null && it.ref < 0 })
        assertNull(rival.seats[0].spells[1]!!.code)
        assertEquals(g.state.cards.getValue(hand[0]).code, mine.seats[0].spells[1]!!.code)
        // Nothing in the rival's whole view names a hidden card of seat 0.
        val hidden = (g.state.seats[0].hand + g.state.seats[0].deck + hand[0]).toSet()
        val text = DuelCodec.json.encodeToString(DuelView.serializer(), rival)
        hidden.forEach { uid -> assertTrue("\"ref\":$uid," !in text, "uid $uid leaked") }
        assertEquals(35, rival.seats[0].deck)
    }

    @Test
    fun aVeilHoldsInTheHandAndChangesWithAShuffle() {
        var g = DuelGame.start(header())
        val before = DuelView.of(g.state, 1, g.header.seed).seats[0].hand.map { it.ref }
        g = g.act(DuelAction.Lp(0, delta = -100), 0).game
        val still = DuelView.of(g.state, 1, g.header.seed).seats[0].hand.map { it.ref }
        assertEquals(before, still)
        g = g.act(DuelAction.Shuffle(0, PileKind.HAND), 0).game
        val after = DuelView.of(g.state, 1, g.header.seed).seats[0].hand.map { it.ref }.toSet()
        assertTrue(before.toSet().intersect(after).isEmpty())
    }

    @Test
    fun theFullViewSeesEverything() {
        val g = DuelGame.start(header())
        val all = DuelView.of(g.state, null)
        assertTrue(all.seats.all { s -> s.hand.all { it.code != null } })
        assertNotEquals(DuelView.veil(1, 5, 0), DuelView.veil(1, 5, 1))
        assertTrue(DuelView.veil(1, 5, 0) < 0)
    }
}
