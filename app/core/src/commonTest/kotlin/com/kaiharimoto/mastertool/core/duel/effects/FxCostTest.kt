package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cost, said before and after (D.md §3.1): the estimate at the connection's measured figure a card, else an assumed
 * 30,000 said to be assumed, priced at list prices; what each card's rounds really cost; and a connection with no price said
 * in words, never a made-up sum.
 */
class FxCostTest {
    private val sonnet = "anthropic/claude-sonnet-5-5"
    private val price = Prices.of("anthropic", "claude-sonnet-5-5")

    @Test
    fun theEstimateBeforeTheGo() {
        val per = FxCost.perCard(FxAsked(), sonnet)
        assertEquals(FxCost.PerCard(30_000, measured = false), per)
        val e = FxCost.estimate(4, per, price)
        assertEquals(120_000, e.tokens)
        // 108,000 read at $2 and 12,000 written at $10 a million.
        assertEquals(0.336, assertNotNull(e.usd), 1e-9)
        assertEquals("4 cards to write, about 30,000 tokens each (assumed: none written on this connection yet), ≈ \$0.34 at list prices, ${Prices.AS_OF}", FxCost.words(e))
        assertEquals("Nothing to write: every card asked for is already done.", FxCost.words(FxCost.estimate(0, per, price)))
    }

    @Test
    fun theConnectionsOwnFigureOnceItHasWritten() {
        var doc = assertNotNull(FxAsks.go(FxAsked(), FxRequest("r1", cards = listOf(1, 2, 3)), FxReviews.PERSON))
        doc = FxAsks.spent(doc, 1, Usage(input = 18_000, output = 2_000), price, sonnet)
        doc = FxAsks.spent(doc, 2, Usage(input = 36_000, output = 4_000), price, sonnet)
        // Another connection's card is not this one's figure.
        doc = FxAsks.spent(doc, 3, Usage(input = 900_000), null, "openrouter/x")
        val per = FxCost.perCard(doc, sonnet)
        assertEquals(FxCost.PerCard(30_000, measured = true, from = 2), per)
        assertTrue("measured on this connection over 2 cards" in FxCost.words(FxCost.estimate(1, per, price)))
    }

    @Test
    fun anUnpricedConnectionIsSaidInWords() {
        // A plan's command-line app, or a model the table does not hold: tokens, and Prices' own words.
        assertNull(Prices.of("claude-code", "claude-opus-5-5"))
        val e = FxCost.estimate(2, FxCost.PerCard(30_000, false), null)
        assertNull(e.usd)
        assertEquals("2 cards to write, about 30,000 tokens each (assumed: none written on this connection yet), 60,000 tokens in all — ${Prices.UNKNOWN}", FxCost.words(e))
    }

    @Test
    fun whatACardReallyCost() {
        var doc = assertNotNull(FxAsks.go(FxAsked(), FxRequest("r1", cards = listOf(1, 2)), FxReviews.PERSON))
        // The rounds between starting the card and its check, by the meter: a repair's rounds add to the same card.
        val meter = FxMeter(Usage(input = 1_000))
        val first = meter.since(Usage(input = 15_000, output = 3_000))
        assertEquals(Usage(input = 14_000, output = 3_000), first)
        doc = FxAsks.spent(doc, 1, first, price, sonnet)
        val repair = meter.since(Usage(input = 20_000, output = 4_000))
        assertEquals(Usage(input = 5_000, output = 1_000), repair)
        doc = FxAsks.spent(doc, 1, repair, price, sonnet)
        val a = assertNotNull(doc.of(1))
        assertEquals(23_000, a.tokens)
        // 19,000 in at $2 and 4,000 out at $10 a million.
        assertEquals(0.078, assertNotNull(a.usd), 1e-9)
        assertEquals("written for 23,000 tokens, ≈ \$0.08", FxCost.spentWords(a))
        assertEquals(FxAsks.WRITTEN, a.state)
        // A CLI says its own figure; nothing else does with no price, and the row says so.
        assertEquals(0.5, FxCost.actual(Usage(input = 10, costUsd = 0.5), null))
        doc = FxAsks.spent(doc, 2, Usage(input = 31_000), null, "claude-code/opus")
        assertEquals("written for 31,000 tokens, ${Prices.UNKNOWN}", FxCost.spentWords(assertNotNull(doc.of(2))))
        // Once a round is unpriced the card's money is unknown: a sum that leaves part out would read as the whole.
        doc = FxAsks.spent(doc, 2, Usage(input = 1_000), price, sonnet)
        assertNull(doc.of(2)?.usd)
        assertNull(FxCost.spentWords(FxAsk(card = 9)), "nothing spent, nothing said")
    }
}
