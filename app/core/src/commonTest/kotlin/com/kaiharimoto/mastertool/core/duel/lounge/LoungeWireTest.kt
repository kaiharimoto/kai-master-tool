package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.net.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoungeWireTest {

    @Test
    fun everyMessageSurvivesTheSocket() {
        val t = RoomTable.start(header(), 0L)
        val lounge = Lounge(
            members = listOf(Member("kai", "kai", room = "r1", host = true), Member("ash", "Ash", online = false)),
            rooms = listOf(Room("r1", "Locals", seats = listOf(Seat(member = "kai", deck = "d1", deckName = "Labrynth", ready = true), Seat(ai = true)), ai = true)),
        )
        val messages = listOf(
            LoungeWire.Hi(nick = "Ash", token = "tok"),
            LoungeWire.Create("Locals"),
            LoungeWire.Enter("r1"),
            LoungeWire.Enter(null),
            LoungeWire.Sit(1),
            LoungeWire.Stand,
            LoungeWire.Ready("d1"),
            LoungeWire.Swap(),
            LoungeWire.Swap(yes = true),
            LoungeWire.AiSeat(1, on = false),
            LoungeWire.RoomSet("r1", ai = true),
            LoungeWire.Close("r1"),
            LoungeWire.Kick("ash"),
            LoungeWire.Say("gl hf"),
            LoungeWire.Decks,
            LoungeWire.DeckGet("d1"),
            LoungeWire.DeckSave(null, "Labrynth", "#main\n101\n"),
            LoungeWire.DeckDelete("d1"),
            LoungeWire.Table(Wire.Intent(3, listOf(DuelAction.Move(5, Place.Pile(1, PileKind.GY))))),
            LoungeWire.Table(t.update(Viewer.Watcher(false), 0, catalog)),
            LoungeWire.Bye,
            LoungeWire.Welcome("ash", "tok"),
            LoungeWire.State(lounge),
            LoungeWire.Seated("r1", null, publicOnly = true),
            LoungeWire.Refused("No"),
            LoungeWire.Rejected("Full"),
            LoungeWire.Said("ash", "Ash", "gg", room = "r1"),
            LoungeWire.DeckList(listOf(DeckInfo("d1", "Labrynth", 40, 15, 15))),
            LoungeWire.Deck("d1", "Labrynth", "#main\n"),
        )
        messages.forEach { m -> assertEquals(m, LoungeCodec.decode(LoungeCodec.encode(m)), "$m") }
    }

    @Test
    fun nonsenseAndFloodsAreDropped() {
        assertNull(LoungeCodec.decode("{\"t\":\"nonsense\"}"))
        assertNull(LoungeCodec.decode("not json"))
        assertNull(LoungeCodec.decode("{\"t\":\"say\",\"text\":\"" + "x".repeat(LoungeCodec.MAX_IN) + "\"}"))
    }

    @Test
    fun aTokenNeverTravelsInTheLoungesState() {
        val text = LoungeCodec.encode(LoungeWire.State(Lounge(members = listOf(Member("ash", "Ash")))))
        assertTrue("token" !in text, text)
    }
}
