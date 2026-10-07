package com.kaiharimoto.mastertool.core.duel.replay

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A DuelingBook replay, in the shape its page receives, played on our table (1.1.51). */
class DbConvertTest {
    private val codes = mapOf(
        "Dark Magician" to 46986414, "Mirror Force" to 44095762, "Effect Veiler" to 97268402,
        "Number 39: Utopia" to 84013237, "Pot of Greed" to 55144522, "Linkuriboh" to 41999284, "Kuriboh" to 40640057,
    )
    private val codeOf: (String) -> Int? = { codes[it] }

    // Alice's cards are 1000…1039 and her Extra Deck 1040…1054; Bob's 2000… and 2040…
    private val replay = """
        {"id":123,"format":"ar","player1":{"username":"Alice","start":1000,"main_total":40,"extra_total":15},
         "player2":{"username":"Bob","start":2000,"main_total":40,"extra_total":15},
         "plays":[
          {"play":"Pick first","username":"Alice"},
          {"play":"Start turn","username":"Alice","seconds":10},
          {"play":"Enter M1","username":"Alice"},
          {"play":"Normal Summon","username":"Alice","id":1003,"zone":"M-3","card":{"name":"Dark Magician"},"log":{"public_log":"Normal Summoned \"Dark Magician\""}},
          {"play":"Set ST","username":"Alice","id":1010,"zone":"S-2","card":{"name":"Mirror Force"}},
          {"play":"SS DEF from deck","username":"Alice","id":1020,"zone":"M-1","card":{"name":"Effect Veiler"}},
          {"play":"OL ATK","username":"Alice","start_id":1045,"end_id":1003,"card":{"name":"Number 39: Utopia"}},
          {"play":"Attach","username":"Alice","start_id":1020,"end_id":1045,"card":{"name":"Effect Veiler"}},
          {"play":"End turn","username":"Alice"},
          {"play":"Start turn","username":"Bob"},
          {"play":"Enter DP","username":"Bob"},
          {"play":"Draw card","username":"Bob","id":2007,"card":{"name":"Kuriboh"}},
          {"play":"Enter SP","username":"Bob"},
          {"play":"Enter M1","username":"Bob"},
          {"play":"Activate ST","username":"Bob","id":2001,"zone":"S-3","card":{"name":"Pot of Greed"}},
          {"play":"To GY","username":"Bob","id":2001,"log":{"private_log":"Sent \"Pot of Greed\" from field to GY"}},
          {"play":"SS ATK","username":"Bob","id":2041,"zone":"Left EMZ","card":{"name":"Linkuriboh"}},
          {"play":"Enter BP","username":"Bob"},
          {"play":"Attack","username":"Bob","attacking_id":2041,"attacked_id":1045},
          {"play":"Life points","username":"Alice","life":7500,"amount":-500},
          {"play":"Detach","username":"Alice","id":1003},
          {"play":"Duel message","username":"Bob","message":"gg"},
          {"play":"Banish from hand FD","username":"Bob","id":2007},
          {"play":"Summon token","username":"Bob","id":900001,"zone":"M-2","token":3},
          {"play":"Declare","username":"Bob","log":{"public_log":"Bob declared \"Dark Magician\""}},
          {"play":"Attack directly","username":"Bob","attacking_id":2041},
          {"play":"Admit defeat","username":"Alice","log":{"public_log":"Alice admitted defeat"}},
          {"play":"Begin next duel","username":"Bob","player1":{"username":"Alice","start":3000,"main_total":40,"extra_total":15},
           "player2":{"username":"Bob","start":4000,"main_total":40,"extra_total":15}},
          {"play":"Start turn","username":"Bob"},
          {"play":"Enter M1","username":"Bob"},
          {"play":"Normal Summon","username":"Bob","id":4002,"zone":"M-1","card":{"name":"Kuriboh"}}
         ]}
    """.trimIndent()

    private fun uid(seat: Int, k: Int) = 1 + seat * 1000 + k

    @Test
    fun everyPlayFitsTheTable() {
        val r = assertNotNull(DbConvert.convert(replay, codeOf = codeOf))
        assertEquals(listOf("Alice", "Bob"), r.players)
        assertEquals(2, r.games.size)
        r.games.forEach { g ->
            val (_, refused) = DuelSetup.fold(g.record.header, g.record.entries)
            assertTrue(refused.isEmpty(), "game ${g.n} refused $refused")
        }
    }

    @Test
    fun decksAreNumberedAsDuelingBookNumbersThem() {
        val g = DbConvert.convert(replay, codeOf = codeOf)!!.games[0]
        val h = g.record.header
        assertEquals("Alice", h.seats[0].name)
        assertEquals(40, h.seats[0].main.size)
        assertEquals(15, h.seats[0].extra.size)
        assertEquals(46986414, h.seats[0].main[3])
        assertEquals(84013237, h.seats[0].extra[5])
        assertEquals(41999284, h.seats[1].extra[1])
        // A card never shown has no face: never guessed.
        assertEquals(0, h.seats[0].main[39])
        assertEquals(0, h.first)
    }

    @Test
    fun theOpeningHandsAreWhatWasPlayedFromThem() {
        val g = DbConvert.convert(replay, codeOf = codeOf)!!.games[0]
        val dealt = g.record.entries.takeWhile { it.seat == null }.map { it.action as DuelAction.Move }
        val alice = dealt.filter { it.uid < 1000 }.map { it.uid }
        val bob = dealt.filter { it.uid > 1000 }.map { it.uid }
        assertEquals(5, alice.size)
        assertEquals(5, bob.size)
        // Summoned and Set from the hand: dealt. Summoned from the Deck, or drawn later: not.
        assertTrue(uid(0, 3) in alice && uid(0, 10) in alice)
        assertTrue(uid(0, 20) !in alice)
        assertTrue(uid(1, 1) in bob && uid(1, 7) !in bob)
        // The deal is behind undo: the game opens at it.
        assertEquals(10, DuelGame.of(g.record).floor)
    }

    @Test
    fun theTableEndsWhereDuelingBooksDid() {
        val g = DbConvert.convert(replay, codeOf = codeOf)!!.games[0]
        val s = DuelGame.of(g.record).state
        // Utopia over Dark Magician in Alice's third zone, Veiler beneath it; Dark Magician detached to the GY.
        val utopia = uid(0, 45)
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 2), s.placeOf(utopia))
        assertEquals(listOf(uid(0, 20)), s.cards.getValue(utopia).under)
        assertEquals(Place.Pile(0, PileKind.GY, 0), s.placeOf(uid(0, 3)))
        assertEquals(CardPosition.FACE_DOWN_ATK, s.cards.getValue(uid(0, 10)).pos)
        // Bob's "Left EMZ" is the right one as seat 0 sees it.
        assertEquals(uid(1, 41), s.emz[1])
        assertEquals(Place.Pile(1, PileKind.GY, 0), s.placeOf(uid(1, 1)))
        assertEquals(CardPosition.FACE_DOWN_DEF, s.cards.getValue(uid(1, 7)).pos)
        assertTrue(s.placeOf(uid(1, 7)) == Place.Pile(1, PileKind.BANISHED, 0))
        // The token in Bob's second zone, numbered apart.
        val token = assertNotNull(s.seats[1].monsters[1])
        assertTrue(s.cards.getValue(token).token)
        assertEquals(7500, s.seats[0].lp)
        assertEquals(2, s.turn)
        assertEquals(1, s.active)
        assertEquals(DuelPhase.BATTLE, s.phase)
        assertEquals(listOf(uid(1, 41)), s.attacks.map { it.attacker }.distinct())
        assertNull(s.attacks.last().target)
    }

    @Test
    fun talkAndWhatTheTableCannotDrawStayInWords() {
        val g = DbConvert.convert(replay, codeOf = codeOf)!!.games[0]
        val actions = g.record.entries.map { it.action }
        assertTrue(DuelAction.Chat(1, "gg") in actions)
        val notes = actions.filterIsInstance<DuelAction.Note>().map { it.text }
        assertTrue(notes.any { "declared" in it }, "$notes")
        assertTrue(notes.any { "admitted defeat" in it }, "$notes")
        assertEquals(2, g.notes)
        // Turns are what the replay steps by.
        assertTrue(DuelAction.EndTurn in actions)
        assertEquals(1, actions.count { it == DuelAction.EndTurn })
    }

    @Test
    fun theNextGameIsDealtAfresh() {
        val g = DbConvert.convert(replay, codeOf = codeOf)!!.games[1]
        assertEquals(1, g.record.header.first)
        val s = DuelGame.of(g.record).state
        assertEquals(uid(1, 2), s.seats[1].monsters[0])
        assertEquals(40640057, s.cards.getValue(uid(1, 2)).code)
        assertTrue("game 2" in g.record.name)
    }

    @Test
    fun aConvertedGameIsAnOrdinaryReplay() {
        val g = DbConvert.convert(replay, codeOf = codeOf)!!.games[0]
        val back = assertNotNull(DuelCodec.decode(DuelCodec.encode(g.record)))
        assertEquals(g.record.entries.size, back.entries.size)
        val turn = Replays.next(back.entries, DuelGame.of(back).floor, ReplayUnit.TURN)
        assertEquals(DuelAction.EndTurn, back.entries[turn - 1].action)
    }

    @Test
    fun notAReplayIsNothing() {
        assertNull(DbConvert.convert("""{"action":"Error","message":"Replay does not exist"}""", codeOf = codeOf))
        assertNull(DbConvert.convert("<html></html>", codeOf = codeOf))
    }

    @Test
    fun aDocumentWithoutDeckNumbersStillPlays() {
        val bare = """
            {"player1":{"username":"A"},"player2":{"username":"B"},"plays":[
              {"play":"Start turn","username":"A"},
              {"play":"Normal Summon","username":"A","id":77,"zone":"M-1","card":{"name":"Kuriboh"}},
              {"play":"To GY","username":"A","id":77}
            ]}
        """.trimIndent()
        val g = assertNotNull(DbConvert.convert(bare, codeOf = codeOf)).games.single()
        val (s, refused) = DuelSetup.fold(g.record.header, g.record.entries)
        assertTrue(refused.isEmpty())
        assertEquals(40640057, s.cards.getValue(s.seats[0].gy.single()).code)
    }
}
