package com.kaiharimoto.mastertool.core.ai.providers

import com.kaiharimoto.mastertool.core.ai.Usage
import kotlin.math.roundToLong

/**
 * What a run costs in money (the design review of Ai vs Ai and Test scores, finding 4, kai's choice (b)): tokens priced
 * at a provider's **list** prices, from a small dated table, so "≈ $1.80 at list prices, Oct 2026" stands beside the
 * tokens it was worked out from. Only the provider's own API is priced — the same model through OpenRouter, a cloud
 * (Bedrock, Vertex) or any other host bills its own rates — and a model the table does not hold is never guessed: its
 * figure is tokens alone and "see your provider's pricing".
 *
 * **Sources**, each read on or before [AS_OF] and never from memory:
 * - Anthropic: the claude-api skill's Current Models table (cached 2026-09-25), first-party API rates. Cache reads are
 *   the stated figure where the skill states one (Fable 5.1 $0.25, Fable 5 $1, Opus 5.5 $0.20, Sonnet 5.5 $0.20), else
 *   0.1× input; cache writes 1.25× input (the skill's eval guide; Opus 5.5's stated 5-minute write, $5, is 1.25× $4).
 * - OpenAI: developers.openai.com/api/docs/pricing, the Standard tier's short-context columns (input, cached input,
 *   cache writes where listed, output), read 2026-10-05. A dated snapshot is not matched to its alias, because OpenAI
 *   prices some snapshots apart (gpt-4o-2024-05-13 is $5, gpt-4o $2.50); models with no cached-input price are left out.
 * - Google: ai.google.dev/gemini-api/docs/pricing, "Last updated 2026-10-01", the paid tier at prompts ≤ 200k tokens
 *   (a match's or a question's prompt is far under). The Gemini 3.x Flash prices shown are those "through December 31,
 *   2026": they double on 1 January 2027, which is what the date beside every figure is for.
 *
 * When a price changes, change the row and [AS_OF] together.
 */
object Prices {
    /** When the table was read: said beside every figure. */
    const val AS_OF = "Oct 2026"

    /** What the person is told when their model is not in the table. */
    const val UNKNOWN = "see your provider's pricing"

    /** One model's list prices in US dollars per million tokens. */
    data class Price(val input: Double, val output: Double, val cacheRead: Double = input * 0.1, val cacheWrite: Double = input * 1.25)

    /**
     * The share of an up-front estimate's tokens that are read rather than written: an agent's turns re-read the table,
     * the tools and the conversation and write a few lines, so nine in ten. Every read token is priced uncached — the
     * real bill is lower when the provider caches the prompt, which is why the figure is "≈".
     */
    const val ESTIMATE_READ_SHARE = 0.9

    private fun claude(input: Double, output: Double, cacheRead: Double = input * 0.1) = Price(input, output, cacheRead, input * 1.25)

    /** Anthropic's own API, by model id (the skill's table, cached 2026-09-25). */
    private val anthropic: Map<String, Price> = mapOf(
        "claude-fable-5-1" to claude(10.0, 50.0, cacheRead = 0.25),
        "claude-mythos-5-1" to claude(10.0, 50.0, cacheRead = 0.25),
        "claude-fable-5" to claude(10.0, 50.0, cacheRead = 1.0),
        "claude-mythos-5" to claude(10.0, 50.0, cacheRead = 1.0),
        "claude-opus-5-5" to claude(4.0, 20.0, cacheRead = 0.20),
        "claude-opus-5" to claude(5.0, 25.0),
        "claude-opus-4-8" to claude(5.0, 25.0),
        "claude-opus-4-7" to claude(5.0, 25.0),
        "claude-opus-4-6" to claude(5.0, 25.0),
        "claude-sonnet-5-5" to claude(2.0, 10.0, cacheRead = 0.20),
        "claude-sonnet-5" to claude(2.0, 10.0),
        "claude-sonnet-4-6" to claude(3.0, 15.0),
        "claude-haiku-4-5" to claude(1.0, 5.0),
    )

    /** OpenAI's own API, Standard tier, short context: input, cached input, output (cache writes where listed, else input). */
    private val openai: Map<String, Price> = mapOf(
        "gpt-6-astra" to Price(10.0, 50.0, 1.0, 12.5),
        "gpt-6.1-sol" to Price(2.0, 10.0, 0.1, 2.5),
        "gpt-6-sol" to Price(2.0, 10.0, 0.2, 2.5),
        "gpt-6-luna" to Price(0.1, 0.5, 0.01, 0.125),
        "gpt-5.6-sol" to Price(4.0, 20.0, 0.4, 5.0),
        "gpt-5.6-terra" to Price(2.0, 12.0, 0.2, 2.5),
        "gpt-5.6-luna" to Price(0.2, 1.2, 0.02, 0.25),
        "gpt-5.5" to Price(5.0, 30.0, 0.5, 5.0),
        "gpt-5.4" to Price(2.5, 15.0, 0.25, 2.5),
        "gpt-5.4-mini" to Price(0.75, 4.5, 0.075, 0.75),
        "gpt-5.4-nano" to Price(0.2, 1.25, 0.02, 0.2),
        "gpt-5.2" to Price(1.75, 14.0, 0.175, 1.75),
        "gpt-5.1" to Price(1.25, 10.0, 0.125, 1.25),
        "gpt-5" to Price(1.25, 10.0, 0.125, 1.25),
        "gpt-5-mini" to Price(0.25, 2.0, 0.025, 0.25),
        "gpt-5-nano" to Price(0.05, 0.4, 0.005, 0.05),
        "gpt-4.1" to Price(2.0, 8.0, 0.5, 2.0),
        "gpt-4.1-mini" to Price(0.4, 1.6, 0.1, 0.4),
        "gpt-4.1-nano" to Price(0.1, 0.4, 0.025, 0.1),
        "gpt-4o" to Price(2.5, 10.0, 1.25, 2.5),
        "gpt-4o-mini" to Price(0.15, 0.6, 0.075, 0.15),
        "o3" to Price(2.0, 8.0, 0.5, 2.0),
        "o4-mini" to Price(1.1, 4.4, 0.275, 1.1),
    )

    /** Google's Gemini API, paid tier, prompts ≤ 200k tokens: input, output, context caching. */
    private val gemini: Map<String, Price> = mapOf(
        "gemini-3.8-flash" to Price(0.75, 3.75, 0.075, 0.75),
        "gemini-3.7-flash" to Price(0.75, 3.75, 0.075, 0.75),
        "gemini-3.6-flash" to Price(0.75, 3.75, 0.075, 0.75),
        "gemini-3.1-pro-preview" to Price(2.0, 12.0, 0.2, 2.0),
        "gemini-2.5-pro" to Price(1.25, 10.0, 0.125, 1.25),
        "gemini-2.5-flash" to Price(0.30, 2.50, 0.03, 0.30),
    )

    private val claudeDate = Regex("""-20\d{6}$""")

    /**
     * [model]'s list prices on [provider]'s own API, or null when the table does not hold it. The id is read as
     * [ModelNames] and [com.kaiharimoto.mastertool.core.ai.ContextWindows] read it — lowercase, a routing prefix
     * ("models/") and a context tag ("[1m]") dropped — and then must be in the table exactly: "gpt-5" never prices
     * "gpt-5-pro". Only Anthropic's dated snapshots ("claude-haiku-4-5-20251001") are read as their alias, since
     * Anthropic prices a snapshot as its model.
     */
    fun of(provider: String, model: String): Price? {
        val id = model.trim().lowercase().substringAfterLast('/').replace("[1m]", "").removeSuffix("-latest")
        if (id.isEmpty()) return null
        return when (provider) {
            Providers.anthropic.id -> anthropic[claudeDate.replace(id, "")]
            Providers.openai.id -> openai[id]
            Providers.gemini.id -> gemini[id]
            else -> null
        }
    }

    /** What [usage] cost at [price], each kind of token at its own rate, in dollars. */
    fun cost(price: Price, usage: Usage): Double =
        (usage.input * price.input + usage.output * price.output + usage.cacheRead * price.cacheRead + usage.cacheWrite * price.cacheWrite) / 1_000_000.0

    /** Tokens split as an estimate assumes ([ESTIMATE_READ_SHARE] read, uncached; the rest written). */
    fun assumed(tokens: Long): Usage {
        val read = (tokens * ESTIMATE_READ_SHARE).roundToLong()
        return Usage(input = read, output = tokens - read)
    }

    /** An up-front estimate of [tokens] at [price], split as [assumed] says. */
    fun estimate(price: Price, tokens: Long): Double = cost(price, assumed(tokens))

    /**
     * A sum in dollars as people read it: "$1.80", "$24", "$0.04", and under a cent "< $0.01". Two decimals below $100,
     * whole dollars from there — an estimate claims no more.
     */
    fun dollars(usd: Double): String {
        val c = (usd * 100).roundToLong()
        return when {
            usd <= 0.0 -> "$0"
            c < 1 -> "< $0.01"
            c < 10_000 -> "$${c / 100}.${(c % 100).toString().padStart(2, '0')}"
            else -> "$" + usd.roundToLong().toString().reversed().chunked(3).joinToString(",").reversed()
        }
    }

    /** The figure with its date: "≈ $1.80 at list prices, Oct 2026"; null when there is nothing to price. */
    fun words(usd: Double?): String = if (usd == null) UNKNOWN else "≈ ${dollars(usd)} at list prices, $AS_OF"

    /**
     * What several seats or runs cost together, each at its own price: null when any of them has no price — a sum that
     * leaves one out would read as the whole.
     */
    fun total(parts: List<Pair<Price?, Usage>>): Double? =
        if (parts.isEmpty() || parts.any { it.first == null }) null else parts.sumOf { (p, u) -> cost(p!!, u) }
}
