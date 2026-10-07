package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.model.CardId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A best-of-three at a Lounge room's table (`docs/LOUNGE.md`, round two), through the real host: kai keeps the room to
 * legal decks and an illegal one is refused; game one is lost by a concession, both players side (an unfair side
 * refused), the loser chooses to go second, and game two deals the sided decks with no dice; a second win takes the
 * match. Each game is recorded as the Lounge's and kept as a replay.
 */
class LoungeMatchFlowTest {
    private val dir = Files.createTempDirectory("lounge-match").toFile()
    private val kept = CopyOnWriteArrayList<DuelGame>()
    private val recorded = CopyOnWriteArrayList<DuelResult>()
    private val catalog = DuelCatalog { code ->
        when (code) {
            3001 -> DuelCardInfo("Fusion", CardKind.EXTRA_MONSTER)
            else -> DuelCardInfo("Card $code", CardKind.MONSTER)
        }
    }
    private val host = LoungeHost(
        dir,
        catalog = { catalog },
        keep = { _, g -> kept += g },
        record = { recorded += it },
        legality = { LoungeLegality("TCG") { d -> if (CardId(666) in d.main) listOf("Forbidden Card is Forbidden") else emptyList() } },
    )

    private inner class Member {
        val heard = LinkedBlockingQueue<LoungeWire>()
        val session: LoungeHost.Session = main { host.open(out = { heard.add(it) }) }
        fun say(w: LoungeWire) = main { session.hear(w) }

        inline fun <reified T : LoungeWire> next(where: (T) -> Boolean = { true }): T {
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                val w = heard.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (w is T && where(w)) return w
            }
            fail("no ${T::class.simpleName} came")
        }

        fun table(where: (DuelState) -> Boolean): DuelState {
            val until = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < until) {
                val u = ((heard.poll(200, TimeUnit.MILLISECONDS) as? LoungeWire.Table)?.wire as? Wire.Update) ?: continue
                val s = DuelMirror.state(u.view)
                if (where(s)) return s
            }
            fail("the table never came to that")
        }
    }

    private fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun deck(card: Int, side: List<Int> = emptyList(), extra: List<Int> = emptyList()) =
        "#main\n" + List(40) { card }.joinToString("\n") + "\n#extra\n" + extra.joinToString("\n") + "\n!side\n" + side.joinToString("\n") + "\n"

    private fun eventually(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!main(what)) {
            if (System.currentTimeMillis() > until) fail("it never came to be")
            Thread.sleep(50)
        }
    }

    @Test
    fun aBestOfThreeIsPlayedSidedAndWon() {
        val kai = Member()
        main { host.hostJoin(kai.session, "kai") }
        val ash = Member().also { it.say(LoungeWire.Hi(nick = "Ash")); it.next<LoungeWire.Welcome>() }
        val mira = Member().also { it.say(LoungeWire.Hi(nick = "Mira")); it.next<LoungeWire.Welcome>() }
        ash.say(LoungeWire.Create("Den"))
        val room = main { host.lounge.rooms.single().id }
        mira.say(LoungeWire.Enter(room))
        // The room's maker plays the best of three; kai keeps it to legal decks.
        ash.say(LoungeWire.RoomSet(room, bestOf = 3))
        kai.say(LoungeWire.RoomSet(room, legalOnly = true))
        assertEquals(3, main { host.lounge.room(room)!!.bestOf })
        ash.say(LoungeWire.Sit(0))
        mira.say(LoungeWire.Sit(1))
        ash.say(LoungeWire.DeckSave(null, "Ash's", deck(1001, side = listOf(1002, 1002, 1003), extra = listOf(3001))))
        val ashDeck = ash.next<LoungeWire.Deck>().id
        // A deck is marked by kai's rules where it is listed; one that breaks them is refused here.
        mira.say(LoungeWire.DeckSave(null, "Banned", deck(666)))
        val list = mira.next<LoungeWire.DeckList>()
        val banned = mira.next<LoungeWire.Deck>().id
        assertEquals("TCG", list.rules)
        assertFalse(list.decks.single { it.id == banned }.legal)
        mira.say(LoungeWire.Ready(banned))
        assertTrue("Forbidden" in mira.next<LoungeWire.Refused>().reason)
        mira.say(LoungeWire.Check(deck(666)))
        assertEquals(listOf("Forbidden Card is Forbidden"), mira.next<LoungeWire.Checked>().issues)
        mira.say(LoungeWire.DeckSave(null, "Mira's", deck(2002, side = listOf(2003))))
        val miraDeck = mira.next<LoungeWire.Deck>().id
        ash.say(LoungeWire.Ready(ashDeck))
        mira.say(LoungeWire.Ready(miraDeck))

        // Game one opens with the dice, and Ash concedes it.
        ash.table { it.opening != null }
        var seq = 0
        var tiedAt = 0
        var winner: Int? = null
        while (winner == null) {
            ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.OpeningRoll(0)))))
            mira.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.OpeningRoll(1)))))
            val o = ash.table { s -> s.opening?.let { it.winner != null || (it.tied && it.round > tiedAt) } == true }.opening!!
            winner = o.winner
            tiedAt = o.round
        }
        (if (winner == 0) ash else mira).say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.GoFirst(winner, first = true)))))
        ash.table { it.opening?.first != null }
        ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.Concede(0)))))

        // Both side; Ash lost, so Ash chooses.
        val ashSides = ash.next<LoungeWire.Siding>()
        val miraSides = mira.next<LoungeWire.Siding>()
        assertEquals(2, ashSides.game)
        assertTrue(ashSides.choose)
        assertFalse(miraSides.choose)
        eventually { host.lounge.room(room)!!.siding && !host.lounge.room(room)!!.playing }
        assertEquals(listOf(0, 1), main { host.lounge.room(room)!!.match!!.wins })
        // A card from nowhere is refused; so is the Fusion in the Main Deck.
        mira.say(LoungeWire.Side(miraSides.main + 9999, miraSides.extra, miraSides.side))
        assertTrue("come in" in mira.next<LoungeWire.Refused>().reason)
        ash.say(LoungeWire.Side(ashSides.main.drop(1) + 3001, listOf(1001), ashSides.side))
        assertTrue("Extra Deck card" in ash.next<LoungeWire.Refused>().reason)
        // Ash sides a 1002 in for a 1001, and chooses to go second; Mira keeps her deck.
        ash.say(LoungeWire.Side(ashSides.main.drop(1) + 1002, ashSides.extra, listOf(1001, 1002, 1003), first = false))
        mira.say(LoungeWire.Side(miraSides.main, miraSides.extra, miraSides.side))

        // Game two deals at once: no dice, Mira first.
        val two = ash.table { it.opening == null && it.turn >= 1 }
        assertEquals(1, two.active)
        assertEquals(1, recorded.size)
        assertEquals(DuelResult.LOUNGE, recorded.single().kind)
        // Ash concedes again: Mira wins the match, two games to none.
        ash.say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.Concede(0)))))
        eventually { host.lounge.room(room)!!.match?.over == true }
        assertEquals(listOf(0, 2), main { host.lounge.room(room)!!.match!!.wins })
        assertEquals(2, recorded.size)
        // Game two was dealt the decks as sided, the chooser's choice first, and no dice.
        ash.say(LoungeWire.End)
        eventually { kept.size == 2 }
        val game2 = kept.last()
        assertEquals(1, game2.header.first)
        assertFalse(game2.header.openingRoll)
        assertTrue(1002 in game2.header.seats[0].main)
        assertNotNull(kept.first())
    }
}
