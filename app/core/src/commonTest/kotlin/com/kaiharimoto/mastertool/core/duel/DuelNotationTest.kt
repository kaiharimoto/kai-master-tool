package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.uid
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation.Coord
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelNotationTest {
    private val s = battle()

    @Test
    fun coordinatesParseExactlyAndCaseFree() {
        assertEquals(Coord(true, Kind.HAND, 1), DuelNotation.parse("h2"))
        assertEquals(Coord(true, Kind.MONSTER, 2), DuelNotation.parse("M3"))
        assertEquals(Coord(false, Kind.SPELL, 1), DuelNotation.parse("os2"))
        assertEquals(Coord(true, Kind.EMZ, 1), DuelNotation.parse("e2"))
        assertEquals(Coord(false, Kind.FIELD, 0), DuelNotation.parse("ofz"))
        assertEquals(Coord(true, Kind.GY, null), DuelNotation.parse("gy"))
        assertEquals(Coord(false, Kind.GY, 1), DuelNotation.parse("ogy2"))
        assertEquals(Coord(false, Kind.BANISHED, null), DuelNotation.parse("oban"))
        assertEquals(Coord(true, Kind.EXTRA, 0), DuelNotation.parse("ex1"))
        assertEquals(Coord(false, Kind.DECK, null), DuelNotation.parse("odk"))
        // Not coordinates: zones past 5, the 0th, an EMZ 3, a prefixed EMZ, words.
        listOf("m6", "s0", "h0", "e3", "oe1", "ash", "gyx", "m", "exodia", "banisher", "el").forEach { assertNull(DuelNotation.parse(it), it) }
    }

    @Test
    fun everyCardOnTheTableRoundTripsThroughItsCoordinate() {
        listOf(0, 1).forEach { viewer ->
            s.cards.keys.forEach { uid ->
                val c = DuelNotation.coordOf(s, uid, viewer) ?: return@forEach
                assertEquals(uid, DuelNotation.at(s, c, viewer), "$c for seat $viewer")
            }
        }
        // Seat 0's view of the table.
        assertEquals("m1", DuelNotation.coordOf(s, uid(0, 5), 0))
        assertEquals("om1", DuelNotation.coordOf(s, uid(0, 5), 1))
        assertEquals("s1", DuelNotation.coordOf(s, uid(0, 3), 0))
        assertEquals("e1", DuelNotation.coordOf(s, uid(0, 40), 0))
        assertEquals("e1", DuelNotation.coordOf(s, uid(0, 40), 1))
        assertEquals("h1", DuelNotation.coordOf(s, uid(0, 0), 0))
        assertEquals("gy1", DuelNotation.coordOf(s, uid(0, 8), 0))
        assertEquals("ogy1", DuelNotation.coordOf(s, uid(1, 7), 0))
        // A material has no place of its own; a Deck's hidden card has none the viewer may know.
        assertNull(DuelNotation.coordOf(s, uid(0, 7), 0))
        assertNull(DuelNotation.coordOf(s, uid(0, 12), 0))
    }

    @Test
    fun theirHandIsCountedInTheOrderTheViewerIsShown() {
        val hand = s.seats[1].hand
        val shown = DuelNotation.handOrder(s, 1, 0, secret = 99L)
        assertEquals(hand.toSet(), shown.toSet())
        assertEquals(DuelView.of(s, 0, 99L).seats[1].hand.map { it.ref }, shown.map { DuelView.veil(99L, it, s.epoch[it] ?: 0) })
        assertEquals(shown[2], DuelNotation.at(s, "oh3", 0, 99L))
        // Their own hand, to themselves, in its real order.
        assertEquals(hand, DuelNotation.handOrder(s, 1, 1, 99L))
        assertEquals(hand[0], DuelNotation.at(s, "h1", 1, 99L))
    }

    @Test
    fun emptyZonesHaveCoordinatesToo() {
        assertEquals("m3", DuelNotation.slotCoord(Place.Zone(0, ZoneKind.MONSTER, 2), 0))
        assertEquals("os5", DuelNotation.slotCoord(Place.Zone(1, ZoneKind.SPELL, 4), 0))
        assertEquals("fz", DuelNotation.slotCoord(Place.Zone(1, ZoneKind.FIELD, 0), 1))
        assertEquals("e2", DuelNotation.slotCoord(Place.Zone(1, ZoneKind.EMZ, 1), 0))
        assertEquals("ogy", DuelNotation.slotCoord(Place.Pile(1, PileKind.GY), 0))
        assertEquals("dk", DuelNotation.slotCoord(Place.Pile(0, PileKind.DECK), 0))
        assertNull(DuelNotation.slotCoord(Place.Pile(0, PileKind.HAND), 0))
        assertNull(DuelNotation.at(s, "m3", 0))
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 2), DuelNotation.toPlace(DuelNotation.parse("m3")!!, 0))
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 2), DuelNotation.toPlace(DuelNotation.parse("om3")!!, 1))
    }

    @Test
    fun coordinatesAreSaidAloud() {
        assertEquals("your monster zone three", DuelNotation.spoken(DuelNotation.parse("m3")!!))
        assertEquals("their graveyard", DuelNotation.spoken(DuelNotation.parse("ogy")!!))
        assertEquals("the second card in your hand", DuelNotation.spoken(DuelNotation.parse("h2")!!))
        assertEquals("their spell and trap zone two", DuelNotation.spoken(DuelNotation.parse("os2")!!))
        assertEquals("extra monster zone one", DuelNotation.spoken(DuelNotation.parse("e1")!!))
        assertEquals("M3", DuelNotation.label(DuelNotation.parse("m3")!!))
        assertEquals("their S2", DuelNotation.label(DuelNotation.parse("os2")!!))
        assertEquals("om1", DuelNotation.parse("OM1").toString())
        assertNotEquals(DuelNotation.parse("m1"), DuelNotation.parse("om1"))
        assertTrue(DuelNotation.zones(true).size == 11)
    }
}
