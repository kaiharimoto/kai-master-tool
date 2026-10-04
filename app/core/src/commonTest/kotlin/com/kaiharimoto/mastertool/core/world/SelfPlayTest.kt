package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Self-play tables in Ai World (Phase C stage 3, `docs/phases/C.md` §6): a seed (never seed 1 for every table — the lead),
 * who goes first, a fork of the duel in play through the seat Ai would hold, moves for both seats as Ai's, and a table that
 * ends kept as a result of its own kind.
 */
class SelfPlayTest {
    private val cards = (1..12).map { Card(CardId(it), "Card $it", "Normal Monster", "normal", "", atk = 1000 + it * 100, def = 1000, level = 4) }

    private val branded = DeckEntry("b", "Branded", Deck(main = (1..6).flatMap { id -> List(5) { CardId(id) } } + List(10) { CardId(7) }), 0, 0)
    private val snake = DeckEntry("s", "Snake-Eye", Deck(main = (8..12).flatMap { id -> List(8) { CardId(id) } }), 0, 0)

    private fun host(live: DuelFork.Source? = null) = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override fun search(query: String, limit: Int) = emptyList<Card>()
        override fun deck(id: String?) = when (id) { null, "b" -> branded; "s" -> snake; else -> null }
        override fun decks() = listOf(branded, snake)
        override fun now(): Long = 1_000L
        override fun liveDuel(): DuelFork.Source? = live
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is Int -> JsonPrimitive(v)
            is Long -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    @Test
    fun aTableTakesASeedAndWhoGoesFirstAndIsNeverSeedOneByDefault() {
        val api = WorldApi(host())
        val a = api.call("duelNew", args("a" to "b", "b" to "s")).jsonObject
        val b = api.call("duelNew", args("a" to "b", "b" to "s")).jsonObject
        // The lead (verified first: `seed ?: 1L`): every table with no seed dealt the same hands. Now each is fresh, and says which.
        assertNotEquals(a["seed"]!!.jsonPrimitive.long, b["seed"]!!.jsonPrimitive.long)
        assertNotEquals(api.call("duelState", args("h" to 0)), api.call("duelState", args("h" to 1)), "two fresh seeds, two deals")
        // The same seed deals the same hands; who goes first is the script's.
        val c = api.call("duelNew", args("a" to "b", "b" to "s", "seed" to 7, "first" to 1)).jsonObject
        val d = api.call("duelNew", args("a" to "b", "b" to "s", "seed" to 7)).jsonObject
        assertEquals(7L, c["seed"]!!.jsonPrimitive.long)
        assertEquals(1, c["first"]!!.jsonPrimitive.int)
        assertEquals(1, c["active"]!!.jsonPrimitive.int)
        assertEquals(0, d["active"]!!.jsonPrimitive.int)
        val hands = { h: Int -> api.call("duelState", args("h" to h)).jsonObject["seats"].toString() }
        assertEquals(hands(2), hands(3))
    }

    @Test
    fun bothSeatsAreAisAndATableThatEndsIsASelfPlayResult() {
        val api = WorldApi(host())
        api.call("duelNew", args("a" to "b", "b" to "s", "seed" to 11))
        // Each seat moved by the script, through the line Ai plays kai with; the menu's lines are what `do` takes.
        val menu = api.call("duelMoves", args("h" to 0, "seat" to 0)) as JsonArray
        val summon = menu.map { it.jsonObject["line"]!!.jsonPrimitive.content }.first { it.startsWith("s h") }
        assertTrue(api.call("duelDo", args("h" to 0, "line" to "m1", "seat" to 0)).toString().contains("\"ok\":true"))
        assertTrue(api.call("duelDo", args("h" to 0, "line" to summon, "seat" to 0)).toString().contains("\"ok\":true"))
        assertTrue(api.call("duelDo", args("h" to 0, "line" to "end", "seat" to 0)).toString().contains("\"ok\":true"))
        assertTrue(api.call("duelDo", args("h" to 0, "line" to "draw", "seat" to 1)).toString().contains("\"ok\":true"))
        assertTrue(api.finished.isEmpty())
        val end = api.call("duelDo", args("h" to 0, "line" to "concede", "seat" to 1)).jsonObject
        assertEquals(0, end["ended"]!!.jsonObject["winner"]!!.jsonPrimitive.int)
        val r = api.finished.single()
        assertEquals(DuelResult.SELF_PLAY, r.kind)
        assertEquals(11L, r.seed)
        assertEquals(null, r.ai, "no seat is a person's: there is no Ai-against-someone to count")
        assertEquals(listOf("ai", "ai"), r.seats.map { it.player }, "provenance ai on both seats")
        assertTrue(r.seats.all { s -> s.moves.keys == setOf("ai") }, r.seats.toString())
        assertEquals(listOf("Branded", "Snake-Eye"), r.seats.map { it.deckName })
        // Counted apart: "Ai won N of M" reads for self-play too, never as a game against kai.
        assertTrue(DuelResults.aiAgainst(api.finished).isEmpty())
        assertEquals("Ai against itself: Branded won 1 of 1 against Snake-Eye; going first won 1.", DuelResults.summary(api.finished))
        assertEquals(0, api.call("duelResult", args("h" to 0)).jsonObject["winner"]!!.jsonPrimitive.int)
        // A finished table takes no more moves, and is kept once.
        assertTrue(api.call("duelDo", args("h" to 0, "line" to "draw", "seat" to 0)).toString().contains("the duel is over"))
        assertEquals(1, api.finished.size)
        // A question is still no move.
        api.call("duelNew", args("a" to "b", "b" to "s", "seed" to 12))
        assertTrue("asks the table something" in api.call("duelDo", args("h" to 1, "line" to "?hand", "seat" to 0)).toString())
    }

    /** A live duel: Kai (seat 0) and Ai (seat 1) dealt five each; Ai sets a card; Kai summons one and sets one. */
    private fun live(): DuelGame {
        val header = DuelHeader(
            id = "live-1", seed = 99,
            seats = listOf(
                SeatSetup("Kai", branded.deck.main.map { it.value }, deckId = "b", deckName = "Branded"),
                SeatSetup("Ai", snake.deck.main.map { it.value }, deckId = "s", deckName = "Snake-Eye"),
            ),
        )
        var g = DuelGame.start(header)
        val kai = g.state.seats[0].hand
        val ai = g.state.seats[1].hand
        g = g.act(DuelAction.Move(kai[0], Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_UP_ATK, "normal"), 0).game
        g = g.act(DuelAction.Move(kai[1], Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK, "set"), 0).game
        g = g.act(DuelAction.Move(kai[2], Place.Pile(0, PileKind.GY), how = "send"), 0).game
        g = g.act(DuelAction.EndTurn, 0).game
        g = g.act(DuelAction.Move(ai[0], Place.Zone(1, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"), 1).game
        return g
    }

    @Test
    fun aForkHoldsWhatAisSeatSeesAndNothingElse() {
        val g = live()
        val before = g
        val src = DuelFork.source(g, seat = 1, knows = DuelBrief.SELF)
        assertTrue(src.header.seats[0].main.isEmpty(), "the other seat's decklist is not Ai's to know")
        val fork = DuelFork.table(src, seed = 5, id = "f1")
        val s = fork.state
        val live = g.state
        // Kai's hand, Deck and set card: unknown cards, by count and place — never a passcode.
        assertEquals(live.seats[0].hand.size, s.seats[0].hand.size)
        assertTrue(s.seats[0].hand.all { s.cards.getValue(it).code == DuelFork.UNKNOWN })
        assertEquals(live.seats[0].deck.size, s.seats[0].deck.size)
        assertTrue(s.seats[0].deck.all { s.cards.getValue(it).code == DuelFork.UNKNOWN })
        val setCard = s.seats[0].spells[1]!!
        assertEquals(DuelFork.UNKNOWN, s.cards.getValue(setCard).code)
        // What Ai's seat sees, as it is: Kai's face-up monster and GY, its own hand and set card.
        assertEquals(live.cards.getValue(live.seats[0].monsters[2]!!).code, s.cards.getValue(s.seats[0].monsters[2]!!).code)
        assertEquals(live.seats[0].gy.map { live.cards.getValue(it).code }, s.seats[0].gy.map { s.cards.getValue(it).code })
        assertEquals(live.seats[1].hand.map { live.cards.getValue(it).code }, s.seats[1].hand.map { s.cards.getValue(it).code })
        assertEquals(live.cards.getValue(live.seats[1].spells[0]!!).code, s.cards.getValue(s.seats[1].spells[0]!!).code)
        // Its own Deck: its list less what it sees — the same cards, in an order of the fork's own.
        val mine = { st: DuelState -> st.seats[1].deck.map { st.cards.getValue(it).code }.sorted() }
        assertEquals(mine(live), mine(s))
        // No passcode of Kai's hidden cards anywhere in the fork that Ai's seat could not see.
        val seen = live.cards.values.filter { DuelSight.sees(live, it.uid, 1) }.map { it.code }.toSet()
        assertTrue(s.cards.values.filter { it.owner == 0 }.all { it.code == DuelFork.UNKNOWN || it.code in seen })
        assertEquals(live.turn, s.turn)
        assertEquals(live.active, s.active)
        assertEquals(live.seats.map { it.lp }, s.seats.map { it.lp })
        // The live duel is read, never changed.
        assertEquals(before, g)
        // With full knowledge the fork is the table, its Decks shuffled.
        val full = DuelFork.table(DuelFork.source(g, 1, DuelBrief.FULL), 5, "f2").state
        assertEquals(live.seats[0].hand.map { live.cards.getValue(it).code }, full.seats[0].hand.map { full.cards.getValue(it).code })
    }

    @Test
    fun aForkIsATableOfItsOwnAndItsResultSaysWhereItCameFrom() {
        val src = DuelFork.source(live(), 1, DuelBrief.SELF)
        val api = WorldApi(host(src))
        val t = api.call("duelNew", args("fork" to true, "seed" to 3)).jsonObject
        assertEquals("live-1", t["forkOf"]!!.jsonPrimitive.content)
        assertEquals(1, t["active"]!!.jsonPrimitive.int, "the turn as it stands")
        assertTrue("\"ok\":true" in api.call("duelDo", args("h" to 0, "line" to "concede", "seat" to 0)).toString())
        val r = api.finished.single()
        assertEquals("live-1", r.forkOf)
        assertEquals(DuelResult.SELF_PLAY, r.kind)
        // No duel in play: a fork is refused in words.
        val none = WorldApi(host())
        val e = runCatching { none.call("duelNew", args("fork" to true)) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue("no duel in play" in e.message.orEmpty(), e.message)
        assertFalse(none.finished.isNotEmpty())
    }
}
