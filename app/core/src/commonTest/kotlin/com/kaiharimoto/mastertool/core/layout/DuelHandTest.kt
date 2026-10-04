package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.duel.CommandFixtures
import com.kaiharimoto.mastertool.core.duel.PileKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** kai, 1.0.94: the near hand "bigger … slightly overlapping each other and … riffle through them" under the pointer or the keys. */
class DuelHandTest {
    private val band = Slot(100f, 500f, 800f, 150f)
    private val w = 100f

    @Test
    fun theCardsOverlapALittle() {
        val held = DuelFrames.held(5, band, w)
        val steps = held.zipWithNext { (a, _), (b, _) -> b.left - a.left }
        steps.forEach { assertEquals(w * (1f - DuelFrames.OVERLAP), it, 0.01f) }
        // Centred, in order, each in front of the one before.
        assertEquals(band.left + band.width / 2f, (held.first().first.left + held.last().first.right) / 2f, 0.01f)
        assertTrue(held.zipWithNext().all { (a, b) -> b.second > a.second })
        // Short of room they overlap more, and stay inside the band.
        val many = DuelFrames.held(14, band, w)
        assertTrue(many.first().first.left >= band.left - 0.01f && many.last().first.right <= band.right + 0.01f)
    }

    @Test
    fun theCardInHandRisesWholeAndTheHandPartsRoundIt() {
        val rest = DuelFrames.held(5, band, w)
        val riffled = DuelFrames.held(5, band, w, at = 2)
        val h = w * DuelLayouter.CARD_RATIO
        val (card, z) = riffled[2]
        // Up by the fifth the window's edge cuts off, so it shows whole; in front of every other card.
        assertEquals(rest[2].first.top - h * DuelLayouter.HAND_CUT, card.top, 0.01f)
        assertTrue(riffled.filterIndexed { i, _ -> i != 2 }.all { it.second < z })
        // Its neighbours part (left ones left, right ones right) and lift a little; the far ones less.
        assertTrue(riffled[1].first.left < rest[1].first.left && riffled[3].first.left > rest[3].first.left)
        assertTrue(riffled[1].first.top < rest[1].first.top && riffled[3].first.top < rest[3].first.top)
        assertTrue(abs(riffled[0].first.left - rest[0].first.left) < abs(riffled[1].first.left - rest[1].first.left))
        assertEquals(rest[0].first.top, riffled[0].first.top, 0.01f)
    }

    @Test
    fun theTableRifflesTheNearHandOnly() {
        val s = CommandFixtures.battle()
        val l = DuelLayouter.solve(1920f, 1032f, true)
        val mine = s.seats[0].hand
        val still = DuelFrames.of(s, l, setOf(0, 1))
        val riffled = DuelFrames.of(s, l, setOf(0, 1), riffle = mine[1])
        val card = riffled.first { it.uid == mine[1] }
        assertTrue(card.y < still.first { it.uid == mine[1] }.y)
        assertEquals(l.handCard, card.w, 0.01f)
        // Their hand is as it was, whatever the pointer is over.
        s.seats[1].hand.forEach { uid -> assertEquals(still.first { it.uid == uid }, DuelFrames.of(s, l, setOf(0, 1), riffle = uid).first { it.uid == uid }) }
        // The hand's band is the layout's.
        assertTrue(l.pile(0, PileKind.HAND)!!.contains(still.first { it.uid == mine[0] }.x + 1f, still.first { it.uid == mine[0] }.y + 1f))
    }
}
