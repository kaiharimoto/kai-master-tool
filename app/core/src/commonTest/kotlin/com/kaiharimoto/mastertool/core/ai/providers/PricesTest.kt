package com.kaiharimoto.mastertool.core.ai.providers

import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cost in money (the design review, finding 4): list prices from a dated table, never guessed for a model it lacks. */
class PricesTest {
    private fun near(want: Double, got: Double?) = assertTrue(got != null && abs(want - got) < 1e-9, "want $want, got $got")

    @Test
    fun anthropicModelsArePricedAsTheSkillStatesThem() {
        val opus = assertNotNull(Prices.of("anthropic", "claude-opus-5-5"))
        assertEquals(4.0, opus.input)
        assertEquals(20.0, opus.output)
        assertEquals(0.20, opus.cacheRead)
        assertEquals(5.0, opus.cacheWrite, "1.25× input, the stated 5-minute write")
        assertEquals(Prices.Price(2.0, 10.0, 0.20, 2.5), Prices.of("anthropic", "claude-sonnet-5-5"))
        assertEquals(1.0, Prices.of("anthropic", "claude-haiku-4-5")?.input)
        // A dated snapshot is its model, a context tag and a routing prefix are dropped.
        assertEquals(Prices.of("anthropic", "claude-haiku-4-5"), Prices.of("anthropic", "claude-haiku-4-5-20251001"))
        assertEquals(opus, Prices.of("anthropic", "claude-opus-5-5[1m]"))
        assertEquals(opus, Prices.of("anthropic", "Claude-Opus-5-5"))
    }

    @Test
    fun onlyTheProvidersOwnApiIsPricedAndNothingIsGuessed() {
        // The same model through another host bills its own rates.
        assertNull(Prices.of("openrouter", "anthropic/claude-opus-5-5"))
        assertNull(Prices.of("claude-code", "claude-opus-5-5"))
        assertNull(Prices.of("ollama", "qwen3"))
        // An id the table lacks is unknown — never the nearest name.
        assertNull(Prices.of("openai", "gpt-5-pro"))
        assertNull(Prices.of("openai", "gpt-4o-2024-05-13"), "OpenAI prices that snapshot apart from gpt-4o")
        assertNull(Prices.of("anthropic", "claude-opus-9"))
        assertNull(Prices.of("anthropic", ""))
        assertEquals(Prices.Price(1.25, 10.0, 0.125, 1.25), Prices.of("openai", "gpt-5"))
        assertEquals(0.25, Prices.of("openai", "gpt-5-mini")?.input)
        assertEquals(Prices.Price(1.25, 10.0, 0.125, 1.25), Prices.of("gemini", "models/gemini-2.5-pro"))
    }

    @Test
    fun usageIsPricedKindByKind() {
        val opus = Prices.of("anthropic", "claude-opus-5-5")!!
        // 1M new in ($4), 100k out ($2), 2M cached reads ($0.40), 100k cache writes ($0.50).
        near(6.9, Prices.cost(opus, Usage(input = 1_000_000, output = 100_000, cacheRead = 2_000_000, cacheWrite = 100_000)))
        near(0.0, Prices.cost(opus, Usage()))
    }

    @Test
    fun anEstimateSaysTheSplitItAssumes() {
        val u = Prices.assumed(1_000_000)
        assertEquals(900_000, u.input)
        assertEquals(100_000, u.output)
        assertEquals(0, u.cacheRead, "an estimate prices every read token uncached")
        // 900k × $4 + 100k × $20 = $3.60 + $2.00.
        near(5.6, Prices.estimate(Prices.of("anthropic", "claude-opus-5-5")!!, 1_000_000))
    }

    @Test
    fun aSumIsSaidAsPeopleReadItWithItsDate() {
        assertEquals("$1.80", Prices.dollars(1.8))
        assertEquals("$0.04", Prices.dollars(0.04))
        assertEquals("< $0.01", Prices.dollars(0.003))
        assertEquals("$0", Prices.dollars(0.0))
        assertEquals("$12.35", Prices.dollars(12.346))
        assertEquals("$100", Prices.dollars(99.999))
        assertEquals("$1,250", Prices.dollars(1249.6))
        assertEquals("≈ $1.80 at list prices, ${Prices.AS_OF}", Prices.words(1.8))
        assertEquals("see your provider's pricing", Prices.words(null))
    }

    @Test
    fun aTotalLeavesNoSeatOut() {
        val opus = Prices.of("anthropic", "claude-opus-5-5")
        val gpt = Prices.of("openai", "gpt-5")
        near(4.0 + 1.25, Prices.total(listOf(opus to Usage(input = 1_000_000), gpt to Usage(input = 1_000_000))))
        assertNull(Prices.total(listOf(opus to Usage(input = 1), null to Usage(input = 1))), "one seat unknown: no sum that reads as the whole")
        assertNull(Prices.total(emptyList()))
    }

    @Test
    fun aMatchIsPricedSeatBySeat() {
        val opus = Prices.of("anthropic", "claude-opus-5-5")
        val gpt = Prices.of("openai", "gpt-5")
        val cost = AiMatch.cost(MatchRules(turnCap = 12, tokenCap = 1_000_000))
        // 900k tokens: 450k a seat, 405k read and 45k written each.
        val want = (405_000 * 4.0 + 45_000 * 20.0 + 405_000 * 1.25 + 45_000 * 10.0) / 1_000_000
        near(want, AiMatch.dollars(cost, listOf(opus, gpt)))
        assertNull(AiMatch.dollars(cost, listOf(opus, null)))
        near(4.0 + 10.0, AiMatch.dollars(listOf(Usage(input = 1_000_000), Usage(output = 1_000_000)), listOf(opus, gpt)))
    }
}
