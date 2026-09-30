package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.model.CardId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A player's lists on YGOPRODeck (1.0.59): the deck API cannot filter by player, so the site's
 * own pages are read — its player search, the player's results, and a deck's page — each
 * captured from the live site (September 2026) and trimmed.
 */
class YgoProDeckPlayersTest {
    private val search = """<div id="tournament_table" style="width: 100%" class="as-table" role="grid"> <div id="tournament_table_header" class="as-tablerow" role="row"> <span class="as-tablecell" role="columnheader">Player</span> <span class="as-tablecell" role="columnheader">Last Appearance</span> </div> <a class="tournament_table_row as-tablerow even" role="row" href="/tournaments/by-player/Matthew+Cane" target="_blank"> <span class="as-tablecell" role="gridcell"> <span class="country-flag" title="United Kingdom" alt="United Kingdom">🇬🇧</span> Matthew Cane </span> <span class="as-tablecell" role="gridcell"><b>Sep 27, 2026</b></span> </a> <a class="tournament_table_row as-tablerow even" role="row" href="/tournaments/by-player/Matthew+Cane" target="_blank"> <span class="as-tablecell" role="gridcell"> <span class="country-flag unknown" title="Nationality unknown" alt="Nationality unknown">🇺🇳</span> Matthew Cane </span> <span class="as-tablecell" role="gridcell"><b>Jan 11, 2025</b></span> </a> </div> </div>"""

    private val player = """<h1 class="mt-5">Matthew Cane's Tournament Results</h1> <p>Tournament results and decklists from Matthew Cane's Yu-Gi-Oh! tournament career.</p> <div class="container-card mb-3"> <div class="d-flex flex-column flex-lg-row p-3" style="gap: 16px;"> <span> <b>Nationality:</b> <span class="country-flag" title="United Kingdom" alt="United Kingdom">🇬🇧</span> United Kingdom </span> <span> <b><abbr title="Tier 2 events are Regional Qualifiers">Tier 2</abbr> events:</b> 13 tops (Wins: 1) </span> </div> </div> <div id="tournament_table" style="width: 100%" class="as-table" role="grid"> <div id="tournament_table_header" class="as-tablerow" role="row"> <span class="as-tablecell" role="columnheader">Date</span> <span class="as-tablecell" role="columnheader">Placement</span> <span class="as-tablecell" role="columnheader">Tournament</span> <span class="as-tablecell" role="columnheader">Archetypes</span> <span class="as-tablecell" role="columnheader">Deck Price</span> </div> <a class="tournament_table_row as-tablerow even" role="row" href="/deck/ignister-maliss-735623" data-deckurl="/deck/ignister-maliss-735623" target="_blank"> <span class="as-tablecell" role="gridcell">Sep 27, 2026</span> <span class="as-tablecell" role="gridcell"><b>Top 4</b></span> <span class="as-tablecell" role="gridcell">Chelmsford WCQ Regional</span> <span class="as-tablecell" role="gridcell"> <div class="d-flex align-content-start flex-wrap arch-link"> <span class="badge badge-ygoprodeck"><img class="tournament-badge-img-crop" src="https://images.ygoprodeck.com/images/cards_cropped_200/21848500.jpg">Maliss</span><span><img class="archetype-tournament-img" src="https://images.ygoprodeck.com/images/cards_cropped/30118811.jpg" title="@Ignister" alt="@Ignister"></span> </div> </span> <span class="as-tablecell" role="gridcell"> $304.49 </span> </a> <a class="tournament_table_row as-tablerow even" role="row" href="/deck/ignister-maliss-735622" data-deckurl="/deck/ignister-maliss-735622" target="_blank"> <span class="as-tablecell" role="gridcell">Sep 26, 2026</span> <span class="as-tablecell" role="gridcell"><b>Runner-Up</b></span> <span class="as-tablecell" role="gridcell">Folkestone WCQ Regional</span> <span class="as-tablecell" role="gridcell"> <div class="d-flex align-content-start flex-wrap arch-link"> <span class="badge badge-ygoprodeck"><img class="tournament-badge-img-crop" src="https://images.ygoprodeck.com/images/cards_cropped_200/21848500.jpg">Maliss</span><span><img class="archetype-tournament-img" src="https://images.ygoprodeck.com/images/cards_cropped/30118811.jpg" title="@Ignister" alt="@Ignister"></span> </div> </span> <span class="as-tablecell" role="gridcell"> $304.49 </span> </a> <a class="tournament_table_row as-tablerow even" role="row" href="/tournament/chichester-wcq-regional-4971" target="_blank"> <span class="as-tablecell" role="gridcell">Sep 6, 2026</span> <span class="as-tablecell" role="gridcell"><b>Top 8</b></span> <span class="as-tablecell" role="gridcell">Chichester WCQ Regional</span> <span class="as-tablecell" role="gridcell"> <div class="d-flex align-content-start flex-wrap arch-link"> <span class="badge badge-ygoprodeck"><img class="tournament-badge-img-crop" src="https://images.ygoprodeck.com/images/cards_cropped_200/22912101.jpg">Blitzclique</span><span><img class="archetype-tournament-img" src="https://images.ygoprodeck.com/images/cards_cropped/34909328.jpg" title="Ryzeal" alt="Ryzeal"></span> </div> </span> <span class="as-tablecell" role="gridcell"> </span> </a> </div>"""

    private val deck = """<meta property="og:url" content="https://ygoprodeck.com/deck/ignister-maliss-735623"/>
<p>Category: Tournament Meta Decks (TCG)</p>
<p>Creator: Matthew Cane</p><p>Tournament: Chelmsford WCQ Regional &ndash; September 27th 2026</p><p>Placement: Top 4</p>
var maindeckjs = '["64865","32061192","69272449","96676583","96676583","96676583","20938824","20938824","20938824","3723262","30118811","30118811","30118811","18789533","42141493","42141493","42141493","14558128","14558128","14558128","59438930","59438930","94145021","94145021","33854624","6637331","60242223","68337209","68337209","93453053","1475311","1475311","25311006","73628505","75500286","10045474","10045474","40366667","40366667","40366667","94722358","57111661"]'
var extradeckjs = '["21848500","68059897","52698008","32995276","5043010","24842059","39138610","86066372","37458564","7594154","27519978","4993187","29301450","9763474","45112597"]'
var sidedeckjs = '["84192580","84192580","84192580","27204311","27204311","72656408","72656408","93453053","4227096","4227096","4227096","24224830","6325660","6325660","8264361"]'
var deckname = "@Ignister Maliss""""

    @Test
    fun theSearchListsEachPlayerWithWhereTheyAreFrom() {
        val found = PlayerPages.search(search)
        assertEquals(2, found.size)
        assertEquals("Matthew Cane", found[0].name)
        assertEquals("United Kingdom", found[0].country)
        assertEquals("Sep 27, 2026", found[0].lastSeen)
        assertEquals("/tournaments/by-player/Matthew+Cane", found[0].path)
        assertNull(found[1].country, "nationality unknown is no country")
        assertTrue(PlayerPages.search("<p>No players found</p>").isEmpty())
    }

    @Test
    fun aPlayersPageIsEveryResultAndTheListsItLinks() {
        val career = PlayerPages.career(player)!!
        assertEquals("Matthew Cane", career.name)
        assertEquals("United Kingdom", career.country)
        assertEquals(listOf("Tier 2 events: 13 tops (Wins: 1)"), career.tally)
        assertEquals(3, career.results.size)
        val first = career.results[0]
        assertEquals("Sep 27, 2026", first.date)
        assertEquals("Top 4", first.placement)
        assertEquals("Chelmsford WCQ Regional", first.event)
        assertEquals(listOf("Maliss", "@Ignister"), first.archetypes)
        assertEquals(735623, first.deckNumber)
        assertEquals("https://ygoprodeck.com/deck/ignister-maliss-735623", first.url)
        assertEquals("Runner-Up", career.results[1].placement)
        val noList = career.results[2]
        assertNull(noList.deckNumber, "a top recorded without a list")
        assertEquals(listOf("Blitzclique", "Ryzeal"), noList.archetypes)
    }

    @Test
    fun aDecksOwnPageIsItsList() {
        val d = PlayerPages.deckPage(deck, 735623)!!
        assertEquals("@Ignister Maliss", d.name)
        assertEquals("Matthew Cane", d.pilot)
        assertEquals("Chelmsford WCQ Regional", d.event)
        assertEquals("September 27th 2026", d.date)
        assertEquals("Top 4", d.placement)
        assertEquals(DeckFormat.TCG, d.format)
        assertEquals(42, d.deck.main.size)
        assertEquals(15, d.deck.extra.size)
        assertEquals(15, d.deck.side.size)
        assertEquals(CardId(64865), d.deck.main.first())
        assertEquals("https://ygoprodeck.com/deck/ignister-maliss-735623", d.url)
        assertNull(PlayerPages.deckPage("<html>not a deck</html>", 1))
    }

    @Test
    fun namesAndAddressesAreReadAsAPersonWritesThem() {
        assertEquals("/tournaments/by-player/Matthew+Cane", PlayerPages.playerPath(" Matthew  Cane "))
        assertEquals("/tournaments/by-player/Nguy%E1%BB%85n+Vi%E1%BB%87t", PlayerPages.playerPath("Nguyễn Việt"))
        assertEquals(735623, PlayerPages.numberOf("https://ygoprodeck.com/deck/ignister-maliss-735623"))
        assertEquals(735623, PlayerPages.numberOf("/deck/735623/"))
        assertEquals(12, PlayerPages.numberOf("12"))
        assertNull(PlayerPages.numberOf("/tournament/chichester-wcq-regional-4971"))
        assertTrue(PlayerPages.names("matthew cane", "Matthew Cane"))
        assertTrue(PlayerPages.names("cane", "Matthew Cane"))
        assertTrue(PlayerPages.names("nguyen viet", "Nguyễn Hoàng Việt"))
        assertTrue(!PlayerPages.names("alex cane", "Matthew Cane"))
        assertEquals("A & B – C", PlayerPages.text("<b>A &amp; B</b> &ndash;  C"))
    }

    @Test
    fun playersAreFetchedThroughTheSamePoliteReader() = runTest {
        val asked = mutableListOf<String>()
        val source = YgoProDeckDecks(
            HttpClientFactory.create(
                MockEngine { request ->
                    val url = request.url.toString()
                    asked += url
                    when {
                        "player-search" in url -> respond(search, HttpStatusCode.OK)
                        "by-player" in url -> respond(player, HttpStatusCode.OK)
                        "/deck/" in url -> respond(deck, HttpStatusCode.OK)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            ),
            clock = { 0L },
        )
        assertEquals(2, source.players("matthew cane").getOrThrow().size)
        assertEquals("https://ygoprodeck.com/tournaments/player-search/?search=matthew+cane", asked.last())
        assertEquals(3, source.career("Matthew Cane").getOrThrow()!!.results.size)
        assertEquals("https://ygoprodeck.com/tournaments/by-player/Matthew+Cane", asked.last())
        assertEquals(42, source.deck(735623).getOrThrow()!!.deck.main.size)
        assertEquals("https://ygoprodeck.com/deck/735623", asked.last())
        source.career("/tournaments/by-player/Matthew+Cane")
        assertEquals(3, asked.size, "the page is kept an hour")
    }
}
