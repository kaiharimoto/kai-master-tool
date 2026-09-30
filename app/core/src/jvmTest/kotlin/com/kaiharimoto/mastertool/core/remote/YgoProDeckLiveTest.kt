package com.kaiharimoto.mastertool.core.remote

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The player pages read off the live site, not a capture (1.0.59): only when
 * `NEUE_LIVE_YGOPRODECK` names a player with published lists, so CI never leans on the network.
 * The site's pages are HTML and change without notice; this is how to see that they still read.
 */
class YgoProDeckLiveTest {
    @Test
    fun aPlayersListsAreFoundOnTheLiveSite() = runBlocking {
        val player = System.getenv("NEUE_LIVE_YGOPRODECK")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val source = YgoProDeckDecks(HttpClientFactory.create(), clock = System::currentTimeMillis)
        val found = source.players(player).getOrThrow()
        assertTrue(found.isNotEmpty(), "the search finds $player")
        val career = source.career(found.first().path).getOrThrow()!!
        println("${career.name} (${career.country}): ${career.tally} — ${career.results.size} results")
        career.results.take(5).forEach { println("  ${it.date} | ${it.placement} | ${it.event} | ${it.archetypes} | ${it.deckNumber}") }
        val number = career.results.firstNotNullOf { it.deckNumber }
        val deck = source.deck(number).getOrThrow()!!
        println("  #$number ${deck.name}: ${deck.deck.main.size}/${deck.deck.extra.size}/${deck.deck.side.size}, ${deck.pilot}, ${deck.event}, ${deck.date}")
        assertEquals(number, deck.number)
        assertTrue(deck.deck.main.size >= 40)
    }
}
