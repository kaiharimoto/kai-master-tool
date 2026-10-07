package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomTableTest {
    private val guest = Provenance(by = Provenance.GUEST)

    private fun table() = RoomTable.start(header(), at = 0L)

    @Test
    fun eachSeatSeesItsOwnHandAndNotTheOthers() {
        val t = table()
        val seat0 = DuelMirror.state(t.update(Viewer.Seat(0), 0, catalog).view)
        assertTrue(seat0.seats[0].hand.all { seat0.cards.getValue(it).code != 0 })
        assertTrue(seat0.seats[1].hand.all { seat0.cards.getValue(it).code == 0 })
        val seat1 = DuelMirror.state(t.update(Viewer.Seat(1), 0, catalog).view)
        assertTrue(seat1.seats[0].hand.all { seat1.cards.getValue(it).code == 0 })
    }

    @Test
    fun aWatcherSeesEverythingUnlessTheRoomKeepsThemToThePublicTable() {
        var t = table()
        val hand = t.game.state.seats[0].hand
        // Seat 0 sets a card face-down.
        t = t.intent(0, Wire.Intent(1, listOf(DuelAction.Move(hand[0], Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"))), 1L, guest).table
        val all = t.update(Viewer.Watcher(publicOnly = false), 0, catalog).view
        assertTrue(all.seats.all { s -> s.hand.all { it.code != null } })
        assertNotNull(all.seats[0].spells[0]?.code)
        val public = t.update(Viewer.Watcher(publicOnly = true), 0, catalog)
        assertTrue(public.view.seats.all { s -> s.hand.all { it.code == null } })
        assertNull(public.view.seats[0].spells[0]?.code)
        // Nor does the public log name what seat 0 drew.
        val names = t.game.state.seats[0].hand.mapNotNull { catalog.info(t.game.state.cards.getValue(it).code)?.name }.filter { it != "Filler" }
        assertTrue(public.lines.none { l -> names.any { it in l.text } }, public.lines.joinToString { it.text })
    }

    @Test
    fun aSeatCannotNameACardItWasNeverShown() {
        val t = table()
        val theirs = t.game.state.seats[0].hand[0]
        // Seat 1 names seat 0's card by its real uid: refused, the table unchanged.
        val made = t.intent(1, Wire.Intent(1, listOf(DuelAction.Move(theirs, Place.Pile(1, PileKind.HAND)))), 1L, guest)
        assertNotNull(made.refused)
        assertEquals(t.game, made.table.game)
    }

    @Test
    fun aSeatMovesItsOwnCardsAndTheMoveIsMarkedAsNetworked() {
        val t = table()
        val mine = t.game.state.seats[1].hand[0]
        val made = t.intent(1, Wire.Intent(1, listOf(DuelAction.Move(mine, Place.Pile(1, PileKind.GY)))), 1L, guest)
        assertNull(made.refused)
        assertEquals(t.game.cursor + 1, made.table.game.cursor)
        assertTrue(made.table.game.entries.last().by?.net == true)
    }

    @Test
    fun aTakeBackIsAnsweredByTheOtherPlayerOnly() {
        var t = table()
        val mine = t.game.state.seats[1].hand[0]
        t = t.intent(1, Wire.Intent(1, listOf(DuelAction.Move(mine, Place.Pile(1, PileKind.GY)))), 1L, guest).table
        val before = t.game.cursor
        t = t.askTakeBack(1)
        assertEquals(1, t.update(Viewer.Seat(0), before, catalog).takeBackFrom)
        assertNotNull(t.answerTakeBack(1, true).refused)
        val no = t.answerTakeBack(0, false)
        assertEquals(before, no.table.game.cursor)
        assertNull(no.table.takeBackFrom)
        val yes = t.answerTakeBack(0, true)
        assertNull(yes.refused)
        assertEquals(before - 1, yes.table.game.cursor)
    }

    @Test
    fun windowsAreEachSeatsOwnAndOnlyKnownSettings() {
        val t = table().setWindows(1, "always").setWindows(0, "nonsense")
        assertEquals("always", t.windows[1])
        assertEquals("activations", t.windows[0])
    }
}
