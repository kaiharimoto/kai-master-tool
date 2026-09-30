package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.ai.meta.DeckAnalysis
import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import com.kaiharimoto.mastertool.core.remote.TournamentDecks
import com.kaiharimoto.mastertool.core.remote.YgoProDeckDecks
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetaTest {
    /** YGOPRODeck's answer, as captured, trimmed to what the app reads. */
    private val captured = """
        [{"username":"milliezone","deck_name":"Branded Light and Darkness Ritual","deck_excerpt":"Branded Light and Darkness Ritual  / Foggia WCQ Regional Top 4",
          "main_deck":"[\"70405001\",\"44001993\",\"98684220\",\"98684220\"]","extra_deck":"[\"76636978\"]","side_deck":"[\"102380\",\"102380\"]",
          "deckNum":735416,"pretty_url":"branded-light-and-darkness-ritual-735416","format":"Tournament Meta Decks","submit_date":"3 days ago",
          "tournamentPlayerName":"Francesco","tournamentName":"Foggia WCQ Regional","tournamentPlayerCount":80,"tournamentPlacement":"Top 4"},
         {"deck_name":"Materiactor Phantom Knights","main_deck":"[\"1\",\"2\"]","extra_deck":"[]","side_deck":null,"deckNum":"735415",
          "format":"Tournament Meta Decks (Genesys)","submit_date":"1 week ago","tournamentName":"Greenville Genesys Regional","tournamentPlacement":"Top 8"},
         {"deck_name":"Broken","main_deck":"not a list","deckNum":1}]
    """

    @Test
    fun theCapturedAnswerIsRead() {
        val decks = TournamentDecks.parse(captured, tier = 2)
        assertEquals(2, decks.size, "a list that cannot be read is left out")
        val first = decks[0]
        assertEquals(735416, first.number)
        assertEquals(4, first.deck.main.size)
        assertEquals(listOf(CardId(102380), CardId(102380)), first.deck.side)
        assertEquals(DeckFormat.TCG, first.format)
        assertEquals(3, first.daysAgo)
        assertEquals(80, first.players)
        assertTrue(first.url.endsWith("735416"))
        assertEquals(DeckFormat.GENESYS, decks[1].format)
        assertEquals(7, decks[1].daysAgo)
    }

    @Test
    fun relativeDatesAndWeights() {
        assertEquals(0, TournamentDecks.daysAgo("8 hours ago"))
        assertEquals(14, TournamentDecks.daysAgo("2 weeks ago"))
        assertEquals(30, TournamentDecks.daysAgo("a month ago"))
        assertEquals(999, TournamentDecks.daysAgo(null))
        assertTrue(TournamentDecks.placementWeight("Winner") > TournamentDecks.placementWeight("Top 8"))
        assertTrue(TournamentDecks.sizeWeight(1000) > TournamentDecks.sizeWeight(16))
    }

    @Test
    fun theSourceIsAskedPolitelyAndItsFailureSaid() = runTest {
        var asked = 0
        val ok = YgoProDeckDecks(HttpClientFactory.create(MockEngine { asked++; respond(captured, HttpStatusCode.OK) }), clock = { 0L })
        val (decks, problems) = ok.recent(minTier = 4, days = 30, format = DeckFormat.TCG, maxPages = 1)
        assertEquals(listOf(735416), decks.map { it.number })
        assertTrue(problems.isEmpty())
        ok.page(4, 0)
        assertEquals(1, asked, "an hour's cache")
        val down = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }), clock = { 0L })
        val (none, why) = down.recent(minTier = 4, days = 30, format = null, maxPages = 1)
        assertTrue(none.isEmpty())
        assertTrue(why.single().contains("503"))
    }

    private fun td(number: Int, name: String, main: List<Int>, placement: String = "Top 8", players: Int = 64) = TournamentDeck(
        number, name, "Event $number", placement, players, null, DeckFormat.TCG, 3, 2, Deck(main.map(::CardId), emptyList(), emptyList()), "",
    )

    @Test
    fun theFieldGroupsListsByWhatTheyPlayNotWhatTheyAreCalled() {
        val staples = listOf(900, 901, 902)
        val snake = listOf(1, 2, 3, 4, 5, 6)
        val tenpai = listOf(20, 21, 22, 23, 24, 25)
        val decks = listOf(
            td(1, "Snake-Eye", snake + staples, "Winner", 300),
            td(2, "Snake-Eye Fire King", snake.take(5) + 7 + staples, "Top 8"),
            td(3, "Snake-Eye", snake + staples, "Top 4"),
            td(4, "Tenpai Dragon", tenpai + staples, "Runner-Up"),
            td(5, "Tenpai", tenpai.drop(1) + 26 + staples, "Top 8"),
            td(6, "Rogue", listOf(40, 41, 42, 43), "Top 32", 20),
        )
        assertTrue(FieldBuilder.staples(decks).containsAll(staples.map(::CardId)))
        val field = FieldBuilder.build(decks)
        assertEquals(3, field.size)
        assertEquals("Snake-Eye", field[0].name, "named by the words its lists share")
        assertEquals(3, field[0].decks.size)
        assertEquals(1, field[0].representative.number, "the list most like the rest, the best result breaking ties")
        assertEquals("Tenpai", field[1].name)
        assertTrue(abs(field.sumOf { it.share } - 100) <= 1)
        assertTrue(field[0].share > field[1].share)
        assertTrue(CardId(1) in field[0].core)
    }

    @Test
    fun aDeckInNumbers() {
        assertEquals(1.0 - (35.0 * 34 * 33 * 32 * 31) / (40.0 * 39 * 38 * 37 * 36), DeckAnalysis.atLeastOne(5, 40, 5), 1e-9)
        assertEquals(0.0, DeckAnalysis.atLeastOne(0, 40, 5))
        val cards = TestCards.all.associateBy { it.id }
        val deck = Deck(List(3) { TestCards.ashBlossom.id } + List(37) { TestCards.nibiru.id }, emptyList(), emptyList())
        val text = DeckAnalysis.describe(deck, cards::get, Format.TCG)
        assertTrue("Main 40" in text)
        assertTrue("Not legal" in text, "three Ash at Limited is not legal: $text")
    }
}
