package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.IsoDate
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
    /** The event's date as YGOPRODeck writes it ("September 27th 2026"), from the list's description or its own page. */
    val date: String? = null,
    /** The event's day, `yyyy-MM-dd`, read from [date] (1.1.1); null when it could not be read. */
    val day: String? = null,
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
    private val site: String = SITE,
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
            val career = PlayerPages.career(body)?.takeIf { it.results.isNotEmpty() }
            when {
                career != null -> {
                    val path = final.substringAfter(site, "").takeIf { it.startsWith("/tournaments/by-player/") } ?: PlayerPages.playerPath(career.name)
                    listOf(TournamentPlayer(career.name, career.country, career.results.first().date, path))
                }
                // The site's own "No results." — nobody by that name, which is an answer, not a failure.
                PlayerPages.noMatches(body) || PlayerPages.notFound(body, final) -> emptyList()
                else -> throw PlayerPages.LayoutChanged("player search")
            }
        }
    }

    /** A player's results, newest first, from their page ([path] as the search gave it, or made from the name). */
    suspend fun career(nameOrPath: String): Result<PlayerCareer?> = runCatching {
        val path = if (nameOrPath.startsWith("/tournaments/by-player/")) nameOrPath else PlayerPages.playerPath(nameOrPath)
        val (body, final) = fetched(site + path)
        PlayerPages.readCareer(body, final)?.takeIf { it.results.isNotEmpty() }
    }

    /** One published list by its number, from the deck's own page: any deck, not only the recent pages'. */
    suspend fun deck(number: Int): Result<TournamentDeck?> = runCatching {
        val (body, final) = fetched("$site/deck/$number")
        PlayerPages.readDeck(body, final, number)
    }

    /**
     * Results at tier [minTier] and above in [format] over [days] days: the last [days], or with [asOf] (`yyyy-MM-dd`)
     * the [days] up to and including that day (1.1.1: a past format read as it was). Up to [maxPages] pages a tier
     * inside the window. The decks, what could not be read, in words, and the tiers whose window held more lists than
     * [maxPages] pages — never cut short in silence.
     *
     * A list's age is its **event's** day when its description gives one ([TournamentDeck.day]) — the site's own "2
     * months ago" is a month wide, and is the fallback ([TournamentDeck.daysAgo]).
     *
     * **One window for all tiers** (Phase B §4): a busy tier fills its pages in days while a quiet
     * one reaches back the whole window, and read as they came, "the last 45 days" was a week or
     * two of regionals beside 45 days of YCS. So when a tier's reading stops at the page cap still
     * inside the window, every tier is cut to the same date ([RecentDecks.window]): the days before
     * the newest of the capped tiers' oldest dates, since lists of that day itself may be left unread.
     *
     * **Reading back** ([asOf]): the pages are newest first, so the page where the window ends is found by a search —
     * a gallop from a guess made from the first page's span, then halving — at most [MAX_PROBES] requests a tier and
     * never past page [MAX_PAGE], each paced like any other ([MIN_INTERVAL_MS]). A year back is some twenty requests a
     * tier, not three hundred pages read in turn. Lists newer than the window are skipped; [maxPages] counts from there.
     */
    suspend fun recent(minTier: Int, days: Int, format: DeckFormat?, maxPages: Int = 4, asOf: String? = null): RecentDecks {
        val todayDay = floorDiv(clock(), DAY_MS)
        val end = asOf?.let { IsoDate.epochDay(it) }?.let { (todayDay - it).toInt().coerceAtLeast(0) } ?: 0
        val start = end + days
        fun age(d: TournamentDeck): Int = RecentDecks.ageOf(d, todayDay)
        val out = mutableListOf<TournamentDeck>()
        val problems = mutableListOf<String>()
        val unread = mutableListOf<Int>()
        val reached = LinkedHashMap<Int, Int>()
        val ends = LinkedHashMap<Int, Int>()
        for (tier in minTier.coerceIn(1, 4)..4) {
            val first = if (end == 0) 0 else when (val s = startPage(tier, end, ::age)) {
                is Start.At -> s.page
                is Start.Ends -> { s.oldest?.let { ends[tier] = it }; continue }
                is Start.Failed -> { problems += s.why; continue }
            }
            // From the page before the one found, which may end with the window's first lists (the search read it, and
            // pages are kept an hour, so it costs no request).
            val from = (first - 1).coerceAtLeast(0)
            for (p in from until first + maxPages) {
                val got = page(tier, p).getOrElse {
                    problems += "Tier $tier, page ${p + 1}: ${Unreachable.of(base, it)}"
                    break
                }
                val inside = got.filter { age(it) in end..start }
                out += inside.filter { format == null || it.format == format }
                // Newest first: a page reaching past the window's start means nothing after it is inside. Past it is
                // the page's last list, by its event and by its posting both, or most of the page — one old event
                // posted late does not end the reading.
                val past = got.count { age(it) > start }
                val last = got.lastOrNull()
                if (last == null || past * 2 > got.size || (age(last) > start && last.daysAgo > start)) break
                // A last page still inside the window: older lists in it were left unread.
                if (p == first + maxPages - 1 && age(last) <= start && inside.isNotEmpty()) {
                    unread += tier
                    reached[tier] = inside.maxOf(::age)
                }
            }
        }
        val window = reached.values.minOrNull()?.let { (it - 1).coerceAtLeast(end) }
        val kept = out.distinctBy { it.number }.filter { window == null || age(it) <= window }
        return RecentDecks(
            kept, problems, unread, window, reached,
            end = end, asOf = asOf?.takeIf { end > 0 }, today = IsoDate.of(todayDay), ends = ends,
        )
    }

    /** Where a tier's reading begins: the page, or — when the tier's lists end before the window does — how old its oldest is. */
    private sealed interface Start {
        data class At(val page: Int) : Start
        data class Ends(val oldest: Int?) : Start
        data class Failed(val why: String) : Start
    }

    /**
     * The first page of [tier] holding a list [end] days old or older ([age]); every page before it holds only newer
     * ones. A gallop from a guess (how many days page 0 spans), then halving. A page past the last is empty.
     */
    private suspend fun startPage(tier: Int, end: Int, age: (TournamentDeck) -> Int): Start {
        var probes = 0
        val seen = HashMap<Int, List<TournamentDeck>>()
        var failure: Throwable? = null
        suspend fun at(p: Int): List<TournamentDeck>? {
            seen[p]?.let { return it }
            if (probes >= MAX_PROBES) return null
            probes++
            return page(tier, p).onFailure { failure = it }.getOrNull()?.also { seen[p] = it }
        }
        fun failed(p: Int) = Start.Failed(
            "Tier $tier: could not find where $end days ago begins in YGOPRODeck's lists " +
                (failure?.let { "(page ${p + 1}: ${Unreachable.of(base, it)})" } ?: "(still searching after $MAX_PROBES requests)"),
        )
        // Reached as the reading's end is: most of the page that old, or its last list by its event and its posting both
        // — an old event posted late, on a page of newer ones, must not start the reading weeks too soon.
        fun reaches(got: List<TournamentDeck>): Boolean {
            val last = got.lastOrNull() ?: return true
            return got.count { age(it) >= end } * 2 > got.size || (age(last) >= end && last.daysAgo >= end)
        }
        val zero = at(0) ?: return failed(0)
        if (zero.isEmpty()) return Start.Ends(null)
        if (reaches(zero)) return Start.At(0)
        var lo = 0
        var loAge = zero.maxOf(age)
        val span = (loAge - zero.minOf(age)).coerceAtLeast(1)
        var step = ((end - loAge) / span).coerceIn(1, MAX_PAGE)
        var hi: Int
        while (true) {
            val p = (lo + step).coerceAtMost(MAX_PAGE)
            val got = at(p) ?: return failed(p)
            if (reaches(got)) {
                hi = p
                break
            }
            lo = p
            loAge = got.maxOf(age)
            if (p == MAX_PAGE) return Start.Ends(loAge)
            step *= 2
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            val got = at(mid) ?: return failed(mid)
            if (reaches(got)) hi = mid else {
                lo = mid
                loAge = got.maxOf(age)
            }
        }
        // The first page to reach the window's end is past the last page: the tier's lists end before the window does.
        return if (seen[hi].isNullOrEmpty()) Start.Ends(loAge) else Start.At(hi)
    }

    companion object {
        const val SITE = "https://ygoprodeck.com"
        const val PAGE = 20
        const val MIN_INTERVAL_MS = 1000L
        const val CACHE_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Requests a tier may spend finding where a past window ends. */
        const val MAX_PROBES = 32

        /** The furthest page searched: fifty thousand lists back. */
        const val MAX_PAGE = 2500

        private fun floorDiv(a: Long, b: Long): Long = a / b - (if (a % b != 0L && (a xor b) < 0) 1 else 0)
    }
}

/**
 * What [YgoProDeckDecks.recent] read: the lists, each page that failed in words, and the tiers
 * whose last page read was still inside the window — older lists there may not have been read,
 * and an answer built on them must say so.
 */
data class RecentDecks(
    val decks: List<TournamentDeck>,
    val problems: List<String>,
    val unread: List<Int> = emptyList(),
    /**
     * The days every tier was cut to because a tier in [unread] stopped short (Phase B §4), or null when the whole
     * window asked for was read.
     */
    val window: Int? = null,
    /** How far back, in days before today, each tier in [unread] was read. */
    val reached: Map<Int, Int> = emptyMap(),
    /** How many days before today the window ends: 0 for the last days, more when read as of a past day (1.1.1). */
    val end: Int = 0,
    /** The day the window ends on, `yyyy-MM-dd`, when it is a past one; null for the last days. */
    val asOf: String? = null,
    /** The day the ages are counted from, `yyyy-MM-dd`. */
    val today: String? = null,
    /** Tiers whose lists on YGOPRODeck all came after the window, with how old the oldest is, in days. */
    val ends: Map<Int, Int> = emptyMap(),
) {
    /** The days the lists really cover: the window asked for, or the one every tier was cut to. */
    fun covers(asked: Int): Int = window?.let { it - end } ?: asked

    /** "last 12 days", or "45 days to 12 Mar 2025" — the window as an answer says it. */
    fun windowWords(asked: Int): String = covers(asked).let {
        if (asOf != null) {
            val to = Legality.readable(asOf)
            if (it == 0) "day of $to" else if (it == 1) "1 day to $to" else "$it days to $to"
        } else if (it == 0) "today" else if (it == 1) "last 1 day" else "last $it days"
    }

    /** The day [age] days before [today], `yyyy-MM-dd`; null when there is no today. */
    fun dayAt(age: Int): String? = IsoDate.epochDay(today)?.let { IsoDate.of(it - age) }

    /** The as-of day that reads the part of a window that was cut: the day before the part read begins; null when none was cut. */
    fun earlier(): String? = window?.let { dayAt(it + 1) }

    /**
     * Why the window is shorter than [asked], in words, then [next] (what reads further); empty when the whole window
     * was read.
     */
    fun cutWords(asked: Int, next: String): String {
        if (unread.isEmpty()) return ""
        val tiers = reached.entries.joinToString(", ") { (t, d) ->
            if (asOf != null) "tier $t reached back only to ${dayAt(d)?.let(Legality::readable) ?: "$d days ago"}"
            else "tier $t reached back only ${if (d == 0) "to today" else "$d days"}"
        }.ifEmpty { unread.joinToString(", ") { "tier $it" } }
        val wanted = if (asOf != null) "the $asked days to ${Legality.readable(asOf)}" else "the last $asked"
        return " Not every list in the window was read ($tiers before the pages ran out), so every tier is cut to the same " +
            "window — the ${windowWords(asked)}, not $wanted asked for — to compare like with like; $next."
    }

    /** The tiers whose lists on YGOPRODeck do not reach back to the window, in words; empty when every tier does. */
    fun endsWords(): String {
        if (ends.isEmpty()) return ""
        val tiers = ends.entries.joinToString(", ") { (t, d) -> "tier $t's to ${dayAt(d)?.let(Legality::readable) ?: "$d days ago"}" }
        return " YGOPRODeck's lists go back only so far ($tiers), so ${if (ends.size == 1) "that tier has" else "those tiers have"} nothing in the window."
    }

    companion object {
        /** How many days before [todayDay] (days since 1970) [deck]'s event was: its day when read, else the site's age. */
        fun ageOf(deck: TournamentDeck, todayDay: Long): Int =
            deck.day?.let { IsoDate.epochDay(it) }?.let { (todayDay - it).toInt().coerceAtLeast(0) } ?: deck.daysAgo
    }
}

/** Reading YGOPRODeck's answer, apart from the network, so it is tested on a captured one. */
object TournamentDecks {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A page of lists. The answer is a JSON list (empty past the last page); anything else — an
     * object, a page of HTML — is an error, never "no lists": read as none, it ended the search
     * as if there were nothing older, and the field came back empty.
     */
    fun parse(body: String, tier: Int): List<TournamentDeck> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: error(unknownShape(body))
        return root.mapNotNull { (it as? JsonObject)?.let { o -> read(o, tier) } }
    }

    /** An answer the app cannot read, in words, with the start of it to show what came instead. */
    fun unknownShape(body: String): String {
        val start = body.trim().replace(Regex("\\s+"), " ").take(200)
        return "YGOPRODeck answered in a shape the app doesn't know: ${start.ifEmpty { "(an empty answer)" }}"
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
        val date = eventDate(o.str("deck_description"))
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
            date = date,
            day = eventDay(date),
        )
    }

    /**
     * The event's date as the list's description writes it — `<p>Tournament: Mexico City WCQ Regional &ndash; September
     * 27th 2026</p>` gives "September 27th 2026" — or null when the description has none.
     */
    fun eventDate(description: String?): String? {
        val line = description?.let { TOURNAMENT.find(it)?.groupValues?.get(1) }?.let(PlayerPages::text) ?: return null
        return line.substringAfterLast(" – ", "").trim().takeIf { it.isNotBlank() && eventDay(it) != null }
    }

    /** "September 27th 2026", "Sept. 27, 2026" or "27 September 2026" as `2026-09-27`; null for anything else. */
    fun eventDay(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val (month, day, year) = MONTH_FIRST.find(text)?.destructured?.let { (m, d, y) -> Triple(m, d, y) }
            ?: DAY_FIRST.find(text)?.destructured?.let { (d, m, y) -> Triple(m, d, y) } ?: return null
        val m = MONTHS.indexOfFirst { month.lowercase().startsWith(it) } + 1
        val d = day.toInt()
        if (m == 0 || d !in 1..31) return null
        val iso = "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        // A day that does not exist (31 February) is not a day.
        return iso.takeIf { IsoDate.epochDay(it)?.let(IsoDate::of) == it }
    }

    private val TOURNAMENT = Regex("""<p>\s*Tournament:\s*(.*?)</p>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private const val MONTH = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?"
    private val MONTH_FIRST = Regex("""\b$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val DAY_FIRST = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+$MONTH,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

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
        // Whole words: "21st" is no win and "Top 48" no Top 4 (2026-10, the red team).
        fun has(word: String) = Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(p)
        return when {
            has("winner") || has("1st") || p == "first" -> 1.0
            has("runner") || p.contains("runner-up") || has("2nd") || has("finalist") -> 0.85
            has("top 4") || has("3rd") || has("4th") -> 0.7
            has("top 8") -> 0.55
            has("top 16") -> 0.45
            has("top 32") -> 0.38
            else -> 0.3
        }
    }

    /** How much an event's size says: 64 players is 1, a 16-player local half, a 1000-player YCS about 1.7. */
    fun sizeWeight(players: Int?): Double {
        val n = (players ?: 32).coerceAtLeast(4)
        return (kotlin.math.ln(n.toDouble()) / kotlin.math.ln(64.0)).coerceIn(0.4, 1.8)
    }
}
