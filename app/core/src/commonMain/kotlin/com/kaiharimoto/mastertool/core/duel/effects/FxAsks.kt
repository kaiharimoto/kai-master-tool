package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Asking (Phase D step 2, `docs/phases/D.md` §3.1, kai: "effects as code should be done by the Ai for cards the user wants
 * because otherwise the whole cardbase would need to be done"): **Ai writes a card's effect only when the person has asked
 * for it**, and the ask is always the person's click. The asked list (`<data>/effects/asked.json`, [FxAsked]) records each
 * card asked for — when, from where, the request it belongs to — and what writing it cost. Core enforces it: [FxAsks.gate]
 * refuses Ai's write to `lib/effects/<passcode>.js` for a card not on it, and [FxAsks.go] refuses an ask Ai makes.
 */

/** Where a go came from (D.md §3.1's table): kept on each ask, said in words on the Effects app's rows. */
object FxFrom {
    /** The card viewer (the card held large). */
    const val VIEWER = "viewer"

    /** The builder's inspector. */
    const val INSPECTOR = "inspector"

    /** The Duel page's inspector. */
    const val DUEL = "duel"

    /** The Effects app in Ai World: Write these. */
    const val PANE = "pane"

    /** A combo's row: Write this combo's cards. */
    const val COMBO = "combo"

    /** Ai's request card in the chat (`fx_request`): the person's Write. */
    const val CHAT = "chat"

    fun words(from: String): String = when (from) {
        VIEWER -> "the card viewer"
        INSPECTOR -> "the inspector"
        DUEL -> "the Duel page"
        PANE -> "the Effects app"
        COMBO -> "a combo"
        CHAT -> "a request in the chat"
        else -> from
    }
}

/**
 * One card on the asked list. [card] is its canonical passcode (every printing is the one script); [request] the go it came
 * with, [what] that go in words ("Example Herald's effect", "the combo “Line 1”"). [tokens] and [usd] add up what writing it
 * cost — every round between starting the card and each of its `fx_check`s, repairs too ([FxMeter]); [usd] is null when the
 * connection has no list price ([Prices.UNKNOWN]), and [model] names the connection it was measured on.
 */
@Serializable
data class FxAsk(
    val card: Int,
    val at: Long = 0L,
    val from: String = "",
    val request: String = "",
    val what: String = "",
    val deck: String? = null,
    val state: String = FxAsks.ASKED,
    val tokens: Long = 0L,
    val usd: Double? = null,
    val model: String? = null,
)

/** One go: the cards it asked for, and what was said they would cost before it ([estimateTokens], ≈ [estimateUsd]). */
@Serializable
data class FxRequest(
    val id: String,
    val at: Long = 0L,
    val from: String = "",
    val what: String = "",
    val deck: String? = null,
    val cards: List<Int> = emptyList(),
    val estimateTokens: Long = 0L,
    val estimateUsd: Double? = null,
)

/** The asked list as stored (`<data>/effects/asked.json`): versioned, read forgivingly, synced newer-wins and backed up. */
@Serializable
data class FxAsked(
    val version: Int = FxAsks.VERSION,
    val asks: List<FxAsk> = emptyList(),
    val requests: List<FxRequest> = emptyList(),
) {
    fun of(card: Int): FxAsk? = asks.firstOrNull { it.card == card }
}

object FxAsks {
    const val VERSION = 1

    /** Asked, not begun yet. */
    const val ASKED = "asked"

    /** Ai's session has begun this card (its first write or check). */
    const val STARTED = "started"

    /** Written and checked at least once ([fx_check] ran on it). */
    const val WRITTEN = "written"

    /** The session stopped before it reached this card: it stays asked, and a later go starts it. */
    const val NOT_STARTED = "not-started"

    /** The requests kept: the newest this many (each card's own ask is kept for ever). */
    const val MOST_REQUESTS = 200

    /** What Ai is told when it writes a card nobody asked for (D.md §3.1). */
    const val REFUSED = "not asked for; offer it with fx_request"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(doc: FxAsked): String = json.encodeToString(FxAsked.serializer(), doc)

    /** The asked list as stored; an unreadable or missing one reads as empty, a newer version's for what this build knows. */
    fun decode(text: String?): FxAsked = text?.let { runCatching { json.decodeFromString(FxAsked.serializer(), it) }.getOrNull() } ?: FxAsked()

    /** Whether [card] (canonical) is on the list. */
    fun asked(doc: FxAsked, card: Int): Boolean = doc.of(card) != null

    /**
     * Why [by] may not write [card]'s source now, or null when they may (the seam `Effects.gate`). The person's own saves need
     * no list: they are the one asking. Ai may write a card on the list, as often as its checks need; a helper ([card] null)
     * only while something is asked, since a helper is there for the cards that use it.
     */
    fun gate(doc: FxAsked, card: Int?, by: String): String? {
        if (by != WorldEvent.AI) return null
        if (card == null) return if (doc.asks.isEmpty()) "No card has been asked for yet: a helper serves asked cards. $REFUSED." else null
        if (asked(doc, card)) return null
        return "$card is $REFUSED. The person asks with a click (Write its effect, Write these, or Write on your request card); wait for it."
    }

    /**
     * [doc] with [request]'s cards asked for — **the person's click only**: null when [by] is not the person (Ai's
     * `fx_request` offers and never adds) or the request names no card. A card asked before keeps what it cost and takes the
     * new request; one written stays written.
     */
    fun go(doc: FxAsked, request: FxRequest, by: String): FxAsked? {
        if (by != FxReviews.PERSON) return null
        val cards = request.cards.distinct()
        if (cards.isEmpty()) return null
        val asks = doc.asks.toMutableList()
        cards.forEach { card ->
            val i = asks.indexOfFirst { it.card == card }
            val fresh = FxAsk(card, request.at, request.from, request.id, request.what, request.deck)
            if (i < 0) {
                asks += fresh
            } else {
                val old = asks[i]
                asks[i] = fresh.copy(state = if (old.state == WRITTEN) WRITTEN else ASKED, tokens = old.tokens, usd = old.usd, model = old.model)
            }
        }
        val requests = (doc.requests.filterNot { it.id == request.id } + request.copy(cards = cards)).takeLast(MOST_REQUESTS)
        return doc.copy(version = maxOf(doc.version, VERSION), asks = asks, requests = requests)
    }

    /** [card] begun (its first write or check in a session). */
    fun started(doc: FxAsked, card: Int): FxAsked = edit(doc, card) { if (it.state == WRITTEN) it else it.copy(state = STARTED) }

    /**
     * [card] checked: what the rounds since the last check cost ([usage], at [price]: null when the connection has none) is
     * added to it, and it is written. [model] names the connection ("anthropic/claude-opus-5-5").
     */
    fun spent(doc: FxAsked, card: Int, usage: Usage, price: Prices.Price?, model: String?): FxAsked = edit(doc, card) { a ->
        val tokens = usage.input + usage.output + usage.cacheRead + usage.cacheWrite
        val usd = FxCost.actual(usage, price)
        // A sum that leaves part out would read as the whole: once one round is unpriced, the card's money is unknown.
        val sum = if (a.tokens > 0 && a.usd == null) null else usd?.let { (a.usd ?: 0.0) + it }
        a.copy(state = WRITTEN, tokens = a.tokens + tokens, usd = sum, model = model ?: a.model)
    }

    /** The session for [request] stopped: its cards it never reached are kept on the list, marked not started (D.md §3.6). */
    fun stopped(doc: FxAsked, request: String): FxAsked =
        doc.copy(asks = doc.asks.map { if (it.request == request && it.state == ASKED) it.copy(state = NOT_STARTED) else it })

    private fun edit(doc: FxAsked, card: Int, f: (FxAsk) -> FxAsk): FxAsked =
        if (!asked(doc, card)) doc else doc.copy(asks = doc.asks.map { if (it.card == card) f(it) else it })

    fun stateWords(state: String): String = when (state) {
        ASKED -> "asked"
        STARTED -> "being written"
        WRITTEN -> "written"
        NOT_STARTED -> "asked, not started"
        else -> state
    }
}

/**
 * What writing costs, said before and after (D.md §3.1): before the go, the cards to write at the connection's own measured
 * figure a card — what cards written on it cost, from the asked list — or else an assumed [ASSUMED], said to be assumed,
 * priced by [Prices.estimate]; after, what each card's rounds really cost ([Prices.cost]). A connection with no list price
 * (a plan's command-line app, a model the table does not hold) is said in [Prices]' own words, never a sum made up.
 */
object FxCost {
    /** Tokens a card is assumed to take when nothing has been written on the connection yet. */
    const val ASSUMED = 30_000L

    /** How many tokens one card takes: [measured] from [from] cards written on this connection, else assumed. */
    data class PerCard(val tokens: Long, val measured: Boolean, val from: Int = 0)

    /** The connection's figure a card: the mean of the cards written on [model] (with what they cost), else [ASSUMED]. */
    fun perCard(doc: FxAsked, model: String?): PerCard {
        val done = doc.asks.filter { model != null && it.model == model && it.tokens > 0 && it.state == FxAsks.WRITTEN }
        if (done.isEmpty()) return PerCard(ASSUMED, measured = false)
        return PerCard((done.sumOf { it.tokens } / done.size).coerceAtLeast(1), measured = true, from = done.size)
    }

    /** An estimate before the go: [cards] to write at [per], ≈ [usd] (null: no price). */
    data class Estimate(val cards: Int, val per: PerCard, val usd: Double?) {
        val tokens: Long get() = cards * per.tokens
    }

    fun estimate(cards: Int, per: PerCard, price: Prices.Price?): Estimate =
        Estimate(cards, per, if (cards == 0) 0.0 else price?.let { Prices.estimate(it, cards * per.tokens) })

    /**
     * The estimate as the request card says it: "4 cards to write, about 30,000 tokens each (assumed), ≈ $0.90 at list
     * prices, Oct 2026"; with no price, the tokens and "see your provider's pricing".
     */
    fun words(e: Estimate): String {
        if (e.cards == 0) return "Nothing to write: every card asked for is already done."
        val each = "about ${tokens(e.per.tokens)} tokens ${if (e.cards == 1) "" else "each "}" +
            if (e.per.measured) "(measured on this connection over ${e.per.from} card${if (e.per.from == 1) "" else "s"})" else "(assumed: none written on this connection yet)"
        val n = "${e.cards} card${if (e.cards == 1) "" else "s"} to write, $each"
        return if (e.usd == null) "$n, ${tokens(e.tokens)} tokens in all — ${Prices.UNKNOWN}" else "$n, ${Prices.words(e.usd)}"
    }

    /** What [usage] cost in dollars: the provider's own figure when it says one (a CLI), else at [price]; null with neither. */
    fun actual(usage: Usage, price: Prices.Price?): Double? = usage.costUsd ?: price?.let { Prices.cost(it, usage) }

    /** What writing [ask] cost, for its row: "written for 31,000 tokens, ≈ $0.24"; null before anything was spent. */
    fun spentWords(ask: FxAsk): String? {
        if (ask.tokens <= 0) return null
        val money = ask.usd?.let { "≈ ${Prices.dollars(it)}" } ?: Prices.UNKNOWN
        return "written for ${tokens(ask.tokens)} tokens, $money"
    }

    /** "31,000". */
    fun tokens(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}

/**
 * The rounds between starting a card and its check (D.md §3.1, "after writing"): marked with what the session had spent at
 * the go, each [since] answers what was spent from the last mark and moves it on, so a card's repairs add up and no round is
 * counted twice.
 */
class FxMeter(private var mark: Usage) {
    fun since(now: Usage): Usage {
        val d = Usage(
            input = (now.input - mark.input).coerceAtLeast(0),
            output = (now.output - mark.output).coerceAtLeast(0),
            cacheRead = (now.cacheRead - mark.cacheRead).coerceAtLeast(0),
            cacheWrite = (now.cacheWrite - mark.cacheWrite).coerceAtLeast(0),
            costUsd = if (now.costUsd == null) null else (now.costUsd - (mark.costUsd ?: 0.0)).coerceAtLeast(0.0),
        )
        mark = now
        return d
    }
}

/**
 * What `fx_request` offers (D.md §3.6): the cards sorted into what is to write, to repair, already done (reused, at no
 * cost — every deck reads the one script) and nothing to write (a Normal Monster), and the estimate. **It adds nothing**:
 * the chat draws it as a request card, and only the person's Write puts [toWrite] on the asked list.
 */
@Serializable
data class FxOffer(
    val id: String,
    val what: String = "",
    val deck: String? = null,
    val write: List<Int> = emptyList(),
    val repair: List<Int> = emptyList(),
    val reused: List<Int> = emptyList(),
    val nothing: List<Int> = emptyList(),
    val perCard: Long = FxCost.ASSUMED,
    val measured: Boolean = false,
    val measuredFrom: Int = 0,
    val usd: Double? = null,
) {
    /** What the go adds to the asked list: the cards to write, then those to repair. */
    val toWrite: List<Int> get() = write + repair

    val estimate: FxCost.Estimate get() = FxCost.Estimate(toWrite.size, FxCost.PerCard(perCard, measured, measuredFrom), usd)
}

object FxOffers {
    /** The line that carries the offer in `fx_request`'s result, for the chat to draw the card from. */
    const val MARK = "Request card: "

    /** An offer is at most this many cards (a deck's distinct Main and Extra Deck cards fit). */
    const val MOST = 80

    /**
     * [cards] (any printings, in the request's order) sorted by [status]: a missing script is to write; a failing, warned or
     * broken one to repair; a written one (verified, or written and checked — unverified until step 3 — or written as far as
     * the vocabulary goes) reused at no cost; a Normal Monster nothing. Priced at [per] and [price].
     */
    fun of(
        id: String,
        what: String,
        deck: String?,
        cards: List<Int>,
        canonical: (Int) -> Int,
        status: (Int) -> FxStatus,
        per: FxCost.PerCard,
        price: Prices.Price?,
    ): FxOffer {
        val distinct = cards.map(canonical).distinct().take(MOST)
        val write = mutableListOf<Int>()
        val repair = mutableListOf<Int>()
        val reused = mutableListOf<Int>()
        val nothing = mutableListOf<Int>()
        distinct.forEach { c ->
            when (status(c)) {
                FxStatus.MISSING -> write += c
                FxStatus.FAILING, FxStatus.WARNED, FxStatus.BROKEN -> repair += c
                FxStatus.VERIFIED, FxStatus.UNTESTED, FxStatus.UNSUPPORTED -> reused += c
                FxStatus.NONE -> nothing += c
            }
        }
        val e = FxCost.estimate(write.size + repair.size, per, price)
        return FxOffer(id, what, deck, write, repair, reused, nothing, per.tokens, per.measured, per.from, e.usd)
    }

    /** The offer in words, for Ai to read and for the chat's line. */
    fun words(o: FxOffer, nameOf: (Int) -> String): String = buildString {
        append("Offered to the person: ${o.what.ifBlank { "cards to write" }}.")
        fun list(title: String, cards: List<Int>) {
            if (cards.isNotEmpty()) append("\n$title (${cards.size}): ").append(cards.joinToString { "${nameOf(it)} ($it)" })
        }
        list("To write", o.write)
        list("To repair", o.repair)
        list("Already done, reused at no cost", o.reused)
        list("Nothing to write (Normal Monsters)", o.nothing)
        append("\nCost: ").append(FxCost.words(o.estimate)).append('.')
        if (o.toWrite.isEmpty()) {
            append("\nNothing needs writing: tell the person so.")
        } else {
            append("\nThe person sees a request card with the cards, the cost, Write and Not now. Nothing is on the asked list until they press Write, ")
            append("which starts a session that writes them: do not write any of these cards yourself now, and do not ask again.")
        }
    }

    /** [o] as its line in the tool's result. */
    fun embed(o: FxOffer): String = MARK + FxAsks.json.encodeToString(FxOffer.serializer(), o)

    /** The offer a tool result carries, or null. */
    fun read(content: String): FxOffer? {
        val line = content.lineSequence().lastOrNull { it.startsWith(MARK) } ?: return null
        return runCatching { FxAsks.json.decodeFromString(FxOffer.serializer(), line.removePrefix(MARK)) }.getOrNull()
    }

    /** The go the person's Write makes of [o]: its cards to write and repair, priced as the card said. */
    fun request(o: FxOffer, at: Long): FxRequest =
        FxRequest(o.id, at, FxFrom.CHAT, o.what, o.deck, o.toWrite, o.estimate.tokens, o.usd)
}
