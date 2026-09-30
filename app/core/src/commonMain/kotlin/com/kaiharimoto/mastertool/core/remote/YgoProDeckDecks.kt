package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Which game a tournament list is for: the TCG, the OCG, or Genesys (a points format, a different game). */
enum class DeckFormat { TCG, OCG, GENESYS }

/**
 * One tournament decklist from YGOPRODeck's curated meta decks: what was played,
 * where, how it placed, and when (YGOPRODeck says "3 days ago", read into [daysAgo]).
 */
data class TournamentDeck(
    val number: Int,
    val name: String,
    val event: String,
    val placement: String,
    val players: Int?,
    val pilot: String?,
    val format: DeckFormat,
    val daysAgo: Int,
    val tier: Int,
    val deck: Deck,
    val url: String,
    /** The event's date as YGOPRODeck writes it, when read off the deck's own page. */
    val date: String? = null,
) {
    /** How much a result says: a win at a big event more than a top 8 at a small one. */
    val weight: Double get() = TournamentDecks.placementWeight(placement) * TournamentDecks.sizeWeight(players)
}

/**
 * YGOPRODeck's tournament decks (`/api/decks/getDecks.php?tournament=tier-N`), the
 * source of Ai's meta skills — and, since 1.0.59, its players ([PlayerPages]). The endpoint is the site's own, undocumented: read
 * leniently, asked politely (one request a second, a User-Agent that names the
 * app), kept an hour, and a failure is said as one — never papered over.
 */
class YgoProDeckDecks(
    private val http: HttpClient,
    private val userAgent: String = "NeueMasterTool",
    private val clock: () -> Long,
    private val site: String = "https://ygoprodeck.com",
    private val base: String = "$site/api/decks/getDecks.php",
) {
    private val pace = Mutex()
    private var last = 0L
    private val texts = HashMap<String, Pair<Long, String>>()
    private val landed = HashMap<String, String>()

    /** One page (about twenty decks) of tier [tier]'s results, newest first; [page] 0 is the newest. */
    suspend fun page(tier: Int, page: Int): Result<List<TournamentDeck>> = runCatching {
        TournamentDecks.parse(fetch("$base?tournament=tier-$tier&offset=${page * PAGE}"), tier)
    }

    /** One address, paced and cached like every other; a refusal is an error in words. */
    private suspend fun fetch(url: String): String = fetched(url).first

    /** The page at [url], and the address it came from in the end (the site redirects). */
    private suspend fun fetched(url: String): Pair<String, String> {
        texts[url]?.takeIf { clock() - it.first < CACHE_MS }?.let { return it.second to (landed[url] ?: url) }
        val (body, final) = pace.withLock {
            val wait = MIN_INTERVAL_MS - (clock() - last)
            if (wait > 0) delay(wait)
            last = clock()
            val response = http.get(url) { header("User-Agent", userAgent) }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) error("YGOPRODeck answered ${response.status.value}")
            text to response.call.request.url.toString()
        }
        texts[url] = clock() to body
        landed[url] = final
        if (final != url) texts[final] = clock() to body
        return body to final
    }

    /**
     * Players with results whose name has [query] (a part of a name is enough), as the site's player
     * search finds them. When only one player matches, the site sends the browser straight to that
     * player's page (a 303) instead of a list of one — so a page that is a player's is that player
     * (1.0.60: "kaihuang zhang" found nobody, because the list of rows was looked for on the player's page).
     */
    suspend fun players(query: String): Result<List<TournamentPlayer>> = runCatching {
        val (body, final) = fetched("$site/tournaments/player-search/?search=" + query.trim().split(Regex("\\s+")).joinToString("+") { PlayerPages.encodeWord(it) })
        PlayerPages.search(body).ifEmpty {
            val career = PlayerPages.career(body)?.takeIf { it.results.isNotEmpty() } ?: return@ifEmpty emptyList()
            val path = final.substringAfter(site, "").takeIf { it.startsWith("/tournaments/by-player/") } ?: PlayerPages.playerPath(career.name)
            listOf(TournamentPlayer(career.name, career.country, career.results.first().date, path))
        }
    }

    /** A player's results, newest first, from their page ([path] as the search gave it, or made from the name). */
    suspend fun career(nameOrPath: String): Result<PlayerCareer?> = runCatching {
        val path = if (nameOrPath.startsWith("/tournaments/by-player/")) nameOrPath else PlayerPages.playerPath(nameOrPath)
        PlayerPages.career(fetch(site + path))?.takeIf { it.results.isNotEmpty() }
    }

    /** One published list by its number, from the deck's own page: any deck, not only the recent pages'. */
    suspend fun deck(number: Int): Result<TournamentDeck?> = runCatching {
        PlayerPages.deckPage(fetch("$site/deck/$number"), number)
    }

    /**
     * Recent results at tier [minTier] and above, back [days] days, in [format]; up to
     * [maxPages] pages a tier. The decks, and what could not be read, in words.
     */
    suspend fun recent(minTier: Int, days: Int, format: DeckFormat?, maxPages: Int = 4): Pair<List<TournamentDeck>, List<String>> {
        val out = mutableListOf<TournamentDeck>()
        val problems = mutableListOf<String>()
        for (tier in minTier.coerceIn(1, 4)..4) {
            for (p in 0 until maxPages) {
                val got = page(tier, p).getOrElse {
                    problems += "Tier $tier, page ${p + 1}: ${it.message ?: it::class.simpleName}"
                    break
                }
                val fresh = got.filter { it.daysAgo <= days }
                out += fresh.filter { format == null || it.format == format }
                // Newest first: a page with nothing recent means there is nothing newer after it.
                if (got.isEmpty() || fresh.size < got.size) break
            }
        }
        return out.distinctBy { it.number } to problems
    }

    companion object {
        const val PAGE = 20
        const val MIN_INTERVAL_MS = 1000L
        const val CACHE_MS = 60 * 60 * 1000L
    }
}

/** Reading YGOPRODeck's answer, apart from the network, so it is tested on a captured one. */
object TournamentDecks {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, tier: Int): List<TournamentDeck> {
        val root = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return root.mapNotNull { (it as? JsonObject)?.let { o -> read(o, tier) } }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

    private fun ids(raw: String?): List<CardId> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()?.let(::CardId) }
    }

    private fun read(o: JsonObject, tier: Int): TournamentDeck? {
        val number = o.int("deckNum") ?: return null
        val main = ids(o.str("main_deck"))
        if (main.isEmpty()) return null
        val format = o.str("format").orEmpty().let {
            when {
                it.contains("genesys", ignoreCase = true) -> DeckFormat.GENESYS
                it.contains("ocg", ignoreCase = true) -> DeckFormat.OCG
                else -> DeckFormat.TCG
            }
        }
        return TournamentDeck(
            number = number,
            name = o.str("deck_name")?.trim() ?: "Deck $number",
            event = o.str("tournamentName")?.trim() ?: o.str("deck_excerpt")?.substringAfter("/")?.trim() ?: "an event",
            placement = o.str("tournamentPlacement") ?: "Top",
            players = o.int("tournamentPlayerCount"),
            pilot = o.str("tournamentPlayerName")?.trim(),
            format = format,
            daysAgo = daysAgo(o.str("submit_date")),
            tier = tier,
            deck = Deck(main, ids(o.str("extra_deck")), ids(o.str("side_deck"))),
            url = "https://ygoprodeck.com/deck/" + (o.str("pretty_url") ?: number.toString()),
        )
    }

    /** "3 days ago", "1 week ago", "8 hours ago", "2 months ago" as whole days; unknown is old. */
    fun daysAgo(text: String?): Int {
        val t = text?.lowercase()?.trim() ?: return 999
        if ("today" in t || "hour" in t || "minute" in t || "second" in t || "just now" in t) return 0
        if ("yesterday" in t) return 1
        val n = Regex("(\\d+)").find(t)?.value?.toIntOrNull() ?: if (t.startsWith("a ") || t.startsWith("an ")) 1 else return 999
        return when {
            "day" in t -> n
            "week" in t -> n * 7
            "month" in t -> n * 30
            "year" in t -> n * 365
            else -> 999
        }
    }

    /** How much a placement says, from a win down. */
    fun placementWeight(placement: String): Double {
        val p = placement.lowercase()
        return when {
            "winner" in p || "1st" in p || p == "first" -> 1.0
            "runner" in p || "2nd" in p || "finalist" in p -> 0.85
            "top 4" in p || "3rd" in p || "4th" in p -> 0.7
            "top 8" in p -> 0.55
            "top 16" in p -> 0.45
            "top 32" in p -> 0.38
            else -> 0.3
        }
    }

    /** How much an event's size says: 64 players is 1, a 16-player local half, a 1000-player YCS about 1.7. */
    fun sizeWeight(players: Int?): Double {
        val n = (players ?: 32).coerceAtLeast(4)
        return (kotlin.math.ln(n.toDouble()) / kotlin.math.ln(64.0)).coerceIn(0.4, 1.8)
    }
}
