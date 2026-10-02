package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.PairCode
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.net.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelNetTest {
    private val secret = 77L

    @Test
    fun aCodeCarriesTheTableAndSurvivesBeingReadAloud() {
        listOf(
            PairCode.Table("192.168.1.42", 47321, 513),
            PairCode.Table("10.0.0.2", 1, 65535),
            PairCode.Table("255.255.255.255", 65535, 0),
            PairCode.Table("0.0.0.0", 9, 7),
        ).forEach { t ->
            val code = PairCode.encode(t)!!
            assertEquals(15, code.length, code)
            assertEquals(t, PairCode.decode(code))
            assertEquals(t, PairCode.decode(code.lowercase().replace("-", " ").replace('1', 'l').replace('0', 'o')))
            assertEquals(t, PairCode.fromQr(PairCode.qr(code)))
        }
        assertNull(PairCode.decode("not a code"))
        assertNull(PairCode.encode(PairCode.Table("fe80::1", 1, 1)))
    }

    @Test
    fun messagesSurviveTheWire() {
        val g = DuelGame.start(header())
        val messages = listOf(
            Wire.Hello(name = "Rival", main = listOf(1, 2), secret = 5),
            Wire.Intent(3, listOf(DuelAction.Move(5, Place.Zone(1, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal"), DuelAction.Draw(1))),
            DuelHost.update(g, 1, 0, secret, catalog),
            Wire.Refused(3, "No"),
            Wire.TakeBack(ask = false, yes = true),
            Wire.Bye,
        )
        messages.forEach { m -> assertEquals(m, WireCodec.decode(WireCodec.encode(m))) }
        assertNull(WireCodec.decode("{\"t\":\"nonsense\"}"))
    }

    @Test
    fun theGuestNeverHoldsWhatItCannotSee() {
        val g = DuelGame.start(header())
        val update = DuelHost.update(g, 1, 0, secret, catalog)
        val mirror = DuelMirror.state(update.view)
        // Seat 0's hand and deck arrive as backs with no passcode.
        mirror.seats[0].hand.forEach { assertEquals(0, mirror.cards.getValue(it).code) }
        mirror.seats[0].deck.forEach { assertEquals(0, mirror.cards.getValue(it).code) }
        assertTrue(mirror.seats[1].hand.all { mirror.cards.getValue(it).code != 0 })
        // The guest's log of the deal names its own draw and not the host's.
        assertTrue(update.lines.any { it.text.startsWith("Rival draws") }, update.lines.joinToString { it.text })
        val hostOnly = g.state.seats[0].hand.mapNotNull { catalog.info(g.state.cards.getValue(it).code)?.name }.filter { it != "Filler" }
        assertTrue(update.lines.none { l -> hostOnly.any { it in l.text } }, "$hostOnly in ${update.lines.joinToString { it.text }}")
    }

    @Test
    fun theHostTurnsRefsBackIntoCardsAndRefusesWhatWasNeverShown() {
        var g = DuelGame.start(header())
        val hostHand = g.state.seats[0].hand
        // Seat 0 sets a card; the guest destroys it by its veil.
        g = g.act(DuelAction.Move(hostHand[0], Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"), 0).game
        val view = DuelHost.update(g, 1, 0, secret, catalog).view
        val veil = view.seats[0].spells[0]!!.ref
        assertTrue(veil < 0)
        val (resolved, why) = DuelHost.resolve(g.state, 1, secret, listOf(DuelAction.Move(veil, Place.Pile(1, PileKind.GY))))
        assertNull(why)
        assertEquals(hostHand[0], (resolved!!.single() as DuelAction.Move).uid)
        // A uid of a card in the host's hand is refused: the guest was never shown it.
        val (_, refused) = DuelHost.resolve(g.state, 1, secret, listOf(DuelAction.Move(hostHand[1], Place.Pile(0, PileKind.GY))))
        assertNotNull(refused)
        // The guest's own deck by place: its top card to the GY.
        val (mill, _) = DuelHost.resolve(g.state, 1, secret, listOf(DuelAction.Move(DuelMirror.deckRef(1, 0), Place.Pile(1, PileKind.GY))))
        assertEquals(g.state.seats[1].deck[0], (mill!!.single() as DuelAction.Move).uid)
        // What a guest says is always its own seat's.
        val (said, _) = DuelHost.resolve(g.state, 1, secret, listOf(DuelAction.Chat(0, "I am the host")))
        assertEquals(1, (said!!.single() as DuelAction.Chat).seat)
    }

    @Test
    fun aResponseWindowHoldsTheOpenerUntilTheOtherPlayerAnswers() {
        var g = DuelGame.start(header())
        val windows = mapOf(0 to Windows.ACTIVATIONS, 1 to Windows.ACTIVATIONS)
        val card = g.state.seats[0].hand[0]
        g = DuelHost.act(g, 0, listOf(DuelAction.ChainAdd(0, card)), windows).game
        assertEquals(1, g.state.window?.responder)
        // The opener's next move waits…
        val held = DuelHost.act(g, 0, listOf(DuelAction.Draw(0)), windows)
        assertFalse(held.ok)
        // …talk does not…
        assertTrue(DuelHost.act(g, 0, listOf(DuelAction.Chat(0, "ok?")), windows).ok)
        // …Esc's force does, and so does the other player passing.
        assertTrue(DuelHost.act(g, 0, listOf(DuelAction.Draw(0)), windows, force = true).ok)
        g = DuelHost.act(g, 1, listOf(DuelAction.Answer(1, respond = false)), windows).game
        assertNull(g.state.window)
        assertTrue(DuelHost.act(g, 0, listOf(DuelAction.Draw(0)), windows).ok)
        // A summon opens nothing for a player who asked only for activations; everything for "always".
        val summon = listOf(DuelAction.Move(1, Place.Zone(0, ZoneKind.MONSTER, 0), how = "normal"))
        assertFalse(Windows.opens(summon, Windows.ACTIVATIONS))
        assertTrue(Windows.opens(summon, Windows.SUMMONS))
        assertFalse(Windows.opens(listOf(DuelAction.Chat(0, "hi")), Windows.ALWAYS))
    }
}
