package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.ai.FxTools
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asking (Phase D step 2, D.md §3.1, §7): Ai writes a card's effect only when the person asked for it, and the ask is the
 * person's click. A write without a go is refused; `fx_request` offers and adds nothing; a go from each place adds its cards;
 * the cards a stopped session never reached stay asked, not started.
 */
class FxAsksTest {
    private val herald = 900_000_201
    private val squire = 900_000_203
    private val knight = 900_000_206

    private fun request(id: String, from: String, vararg cards: Int) = FxRequest(id, at = 10, from = from, what = "a test", deck = "d1", cards = cards.toList())

    @Test
    fun aWriteWithoutAGoIsRefused() {
        val empty = FxAsked()
        // Ai's write to a card nobody asked for: refused in the words the seam promises.
        val why = assertNotNull(FxAsks.gate(empty, herald, WorldEvent.AI))
        assertTrue(FxAsks.REFUSED in why, why)
        // A helper serves asked cards only: refused while nothing is asked.
        assertNotNull(FxAsks.gate(empty, null, WorldEvent.AI))
        // The person's own saves need no list.
        assertNull(FxAsks.gate(empty, herald, WorldEvent.YOU))
        // Once asked, Ai may write it — and repair it as often as its checks need — and a helper beside it.
        val asked = assertNotNull(FxAsks.go(empty, request("r1", FxFrom.VIEWER, herald), FxReviews.PERSON))
        assertNull(FxAsks.gate(asked, herald, WorldEvent.AI))
        assertNull(FxAsks.gate(FxAsks.spent(asked, herald, Usage(input = 10), null, null), herald, WorldEvent.AI))
        assertNull(FxAsks.gate(asked, null, WorldEvent.AI))
        // Another card is still refused.
        assertNotNull(FxAsks.gate(asked, squire, WorldEvent.AI))
    }

    @Test
    fun onlyThePersonsClickAdds() {
        // Ai cannot put a card on the list, whatever it sends.
        assertNull(FxAsks.go(FxAsked(), request("r1", FxFrom.CHAT, herald), FxReviews.AI))
        assertNull(FxAsks.go(FxAsked(), request("r1", FxFrom.CHAT, herald), WorldEvent.AI))
        // A go with no cards adds nothing.
        assertNull(FxAsks.go(FxAsked(), request("r1", FxFrom.PANE), FxReviews.PERSON))
    }

    @Test
    fun fxRequestOffersAndAddsNothing() {
        // The tool exists, offered and answered (AiToolsTest holds the rest), and only offers.
        assertTrue(FxTools.request in FxTools.all)
        assertTrue("writes nothing" in FxTools.request.description)
        val doc = FxAsked()
        val status = mapOf(herald to FxStatus.MISSING, squire to FxStatus.NONE, knight to FxStatus.UNTESTED)
        val offer = FxOffers.of("r1", "the engine", "d1", listOf(herald, squire, knight), { it }, { status[it] ?: FxStatus.MISSING }, FxCost.perCard(doc, null), null)
        assertEquals(listOf(herald), offer.write)
        assertEquals(listOf(knight), offer.reused, "a written card is reused at no cost")
        assertEquals(listOf(squire), offer.nothing)
        // The offer is in the tool's result, for the chat's card; nothing reached the list.
        val content = FxOffers.words(offer) { "#$it" } + "\n" + FxOffers.embed(offer)
        assertEquals(offer, FxOffers.read(content))
        assertTrue(doc.asks.isEmpty())
        assertNotNull(FxAsks.gate(doc, herald, WorldEvent.AI))
        // The person's Write is the go: what the card offered to write and repair, and nothing it reused.
        val go = FxOffers.request(offer, at = 99)
        assertEquals(FxFrom.CHAT, go.from)
        assertEquals(listOf(herald), go.cards)
        val asked = assertNotNull(FxAsks.go(doc, go, FxReviews.PERSON))
        assertTrue(FxAsks.asked(asked, herald))
        assertTrue(!FxAsks.asked(asked, knight))
    }

    @Test
    fun aGoFromEachPlaceAddsItsCards() {
        var doc = FxAsked()
        listOf(FxFrom.VIEWER to herald, FxFrom.INSPECTOR to squire, FxFrom.DUEL to knight).forEachIndexed { i, (from, card) ->
            doc = assertNotNull(FxAsks.go(doc, request("r$i", from, card), FxReviews.PERSON))
            assertEquals(from, doc.of(card)?.from)
        }
        doc = assertNotNull(FxAsks.go(doc, request("pane", FxFrom.PANE, herald, 900_000_300), FxReviews.PERSON))
        doc = assertNotNull(FxAsks.go(doc, request("combo", FxFrom.COMBO, 900_000_301, 900_000_302), FxReviews.PERSON))
        assertEquals(setOf(herald, squire, knight, 900_000_300, 900_000_301, 900_000_302), doc.asks.map { it.card }.toSet())
        // Asked again, a card takes the newer request; there is one row a card.
        assertEquals("pane", doc.of(herald)?.request)
        assertEquals(1, doc.asks.count { it.card == herald })
        assertEquals(listOf("r0", "r1", "r2", "pane", "combo"), doc.requests.map { it.id })
        // Each place says where in words.
        listOf(FxFrom.VIEWER, FxFrom.INSPECTOR, FxFrom.DUEL, FxFrom.PANE, FxFrom.COMBO, FxFrom.CHAT).forEach { assertTrue(FxFrom.words(it) != it, it) }
    }

    @Test
    fun aStoppedSessionLeavesItsCardsAskedNotStarted() {
        var doc = assertNotNull(FxAsks.go(FxAsked(), request("r1", FxFrom.PANE, herald, squire, knight), FxReviews.PERSON))
        doc = FxAsks.started(doc, herald)
        doc = FxAsks.spent(doc, herald, Usage(input = 20_000, output = 4_000), Prices.of("anthropic", "claude-sonnet-5-5"), "anthropic/claude-sonnet-5-5")
        doc = FxAsks.stopped(doc, "r1")
        assertEquals(FxAsks.WRITTEN, doc.of(herald)?.state)
        assertEquals(FxAsks.NOT_STARTED, doc.of(squire)?.state)
        assertEquals(FxAsks.NOT_STARTED, doc.of(knight)?.state)
        // Still on the list: a later go starts them, and Ai may write them.
        assertNull(FxAsks.gate(doc, squire, WorldEvent.AI))
        val again = assertNotNull(FxAsks.go(doc, request("r2", FxFrom.PANE, squire, herald), FxReviews.PERSON))
        assertEquals(FxAsks.ASKED, again.of(squire)?.state)
        assertEquals(FxAsks.WRITTEN, again.of(herald)?.state, "a written card stays written, its cost kept")
        assertEquals(24_000, again.of(herald)?.tokens)
    }

    @Test
    fun theListRoundTripsAndReadsForgivingly() {
        val doc = assertNotNull(FxAsks.go(FxAsked(), request("r1", FxFrom.VIEWER, herald), FxReviews.PERSON))
        assertEquals(doc, FxAsks.decode(FxAsks.encode(doc)))
        assertEquals(FxAsked(), FxAsks.decode(null))
        assertEquals(FxAsked(), FxAsks.decode("not json"))
    }
}
