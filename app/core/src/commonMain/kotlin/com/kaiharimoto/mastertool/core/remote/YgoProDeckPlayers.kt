package com.kaiharimoto.mastertool.core.remote

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck

/** A player YGOPRODeck has tournament results for, as its player search lists them. */
data class TournamentPlayer(val name: String, val country: String?, val lastSeen: String, val path: String)

/**
 * One of a player's results: when, how it placed, where, what they played, and the list's
 * number when YGOPRODeck has published it (many tops are recorded without one).
 */
data class PlayerResult(
    val date: String,
    val placement: String,
    val event: String,
    val archetypes: List<String>,
    val deckNumber: Int?,
    val url: String,
)

/** A player's page: who, from where, the site's tally of their tops, and every result, newest first. */
data class PlayerCareer(val name: String, val country: String?, val tally: List<String>, val results: List<PlayerResult>)

/**
 * Reading YGOPRODeck's tournament pages by player (1.0.59, kai: "it fails to find the topping
 * list by player name"). The deck API has no player filter — it filters by deck name, card and
 * author only — so a player is found the way the site finds one: its player search
 * (`/tournaments/player-search/?search=`), then the player's page (`/tournaments/by-player/Name`)
 * with each result and the deck it links to, then the deck's own page (`/deck/<number>`), whose
 * list is in the page's script. Pages are HTML, read leniently; here apart from the network, so
 * they are tested on captured ones.
 *
 * Leniently, but never by guessing "nobody" (red team): a page that says there is nothing — the
 * search's "No results.", the site's Not Found page that a missing player or deck redirects to —
 * is an answer; a real page with none of what the app reads on it is [LayoutChanged], an error,
 * or a site redesign would read as "no such player" for ever.
 */
object PlayerPages {
    /** A YGOPRODeck page the app reached but could no longer read: the site has changed how it is laid out. */
    class LayoutChanged(page: String) : IllegalStateException(
        "YGOPRODeck's $page page has changed its layout, so the app could not read what is on it. " +
            "That is not the same as finding nothing; the app needs an update to read the new page.",
    )

    private val row = Regex("""<a\s+class="tournament_table_row[^"]*"[^>]*?href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val anyRow = Regex("""class="tournament_table_row""")
    private val heading = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)

    /** The player search's own words for no match: "Tournament players matching '…':" and then "No results." */
    fun noMatches(html: String): Boolean = Regex("""players matching\b.*?</p>\s*No results""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html)

    /**
     * The site's page for an address with nothing behind it: a player or deck that does not exist
     * redirects to `/not-found/`, whose heading is "Not Found". [final] is where the request landed.
     */
    fun notFound(html: String, final: String = ""): Boolean =
        "/not-found" in final || heading.find(html)?.groupValues?.get(1)?.let(::text).equals("Not Found", ignoreCase = true)

    /** [career], told apart from a page the app can no longer read: null only when the site says there is no such player. */
    fun readCareer(html: String, final: String = ""): PlayerCareer? {
        if (notFound(html, final)) return null
        val career = career(html) ?: throw LayoutChanged("player")
        if (career.results.isEmpty() && (anyRow.containsMatchIn(html) || "tournament_table_header" !in html)) throw LayoutChanged("player")
        return career
    }

    /** [deckPage], told apart from a page the app can no longer read: null only when the site has no such deck. */
    fun readDeck(html: String, final: String, number: Int): TournamentDeck? {
        if (notFound(html, final)) return null
        return deckPage(html, number) ?: if ("var maindeckjs" !in html) throw LayoutChanged("deck") else null
    }
    private val cell = Regex("""<span class="as-tablecell"[^>]*role="gridcell"[^>]*>""")
    private val flag = Regex("""<span class="country-flag[^"]*"\s+title="([^"]*)"""")
    private val badge = Regex("""<span class="badge[^"]*">(?:<img[^>]*>)?([^<]*)</span>""")
    private val alsoTitle = Regex("""<img class="archetype-tournament-img"[^>]*title="([^"]*)"""")
    private val tags = Regex("<[^>]+>")
    private val space = Regex("\\s+")
    private val deckNumber = Regex("""/deck/(?:[^/?#]*-)?(\d+)/?$""")

    /** Plain words out of a bit of HTML: tags gone, entities decoded, runs of space one. */
    fun text(html: String): String = entities(html.replace(tags, " ")).replace(space, " ").trim()

    /** The entities these pages use, and any numeric one. */
    fun entities(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> named[e] ?: m.value
            }
        }
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”", "hellip" to "…",
    )

    private fun codePoint(c: Int): String = when {
        c < 0x10000 -> c.toChar().toString()
        else -> (c - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
    }

    /** The cells of one table row, as words. */
    private fun cells(body: String): List<String> {
        val starts = cell.findAll(body).map { it.range.first to it.range.last + 1 }.toList()
        return starts.mapIndexed { i, (_, from) -> body.substring(from, starts.getOrNull(i + 1)?.first ?: body.length) }
    }

    /** The player search's answer: each player once per nationality, as the site lists them. */
    fun search(html: String): List<TournamentPlayer> = row.findAll(html).mapNotNull { m ->
        val path = m.groupValues[1]
        if ("/by-player/" !in path) return@mapNotNull null
        val cs = cells(m.groupValues[2])
        val country = flag.find(m.groupValues[2])?.groupValues?.get(1)?.takeUnless { it.contains("unknown", ignoreCase = true) }
        val name = cs.getOrNull(0)?.let(::text)?.let(::dropFlag) ?: return@mapNotNull null
        TournamentPlayer(name, country, cs.getOrNull(1)?.let(::text).orEmpty(), path)
    }.toList()

    /** The flag is an emoji before the name; the name is what follows it. */
    private fun dropFlag(s: String): String = s.dropWhile { !it.isLetterOrDigit() }.trim()

    /**
     * A player's page, read leniently. Null when it has no heading at all; a missing player is
     * the site's Not Found page, which [readCareer] tells apart from a page it can no longer read.
     */
    fun career(html: String): PlayerCareer? {
        val title = heading.find(html)?.groupValues?.get(1)?.let(::text) ?: return null
        val name = title.removeSuffix("Tournament Results").trim().removeSuffix("'s").removeSuffix("’s").trim()
        val country = html.indexOf("Nationality:").takeIf { it >= 0 }?.let { flag.find(html, it) }?.groupValues?.get(1)
            ?.takeUnless { it.contains("unknown", ignoreCase = true) }
        val tally = Regex("""<abbr[^>]*>(.*?)</abbr>\s*events:</b>\s*([^<]*)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { "${text(it.groupValues[1])} events: ${text(it.groupValues[2])}" }.toList()
        val results = row.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1]
            val body = m.groupValues[2]
            val cs = cells(body).map(::text)
            if (cs.size < 3) return@mapNotNull null
            val archetypes = (badge.findAll(body).map { text(it.groupValues[1]) } + alsoTitle.findAll(body).map { entities(it.groupValues[1]) })
                .filter { it.isNotBlank() }.distinct().toList()
            val number = deckNumber.find(href)?.groupValues?.get(1)?.toIntOrNull()
            PlayerResult(cs[0], cs[1], cs[2], archetypes, number, absolute(href))
        }.toList()
        return PlayerCareer(name, country, tally, results)
    }

    /** A deck's page: its list is in the page's script, its event in the description. Null when there is no list. */
    fun deckPage(html: String, number: Int): TournamentDeck? {
        fun js(name: String) = Regex("""var $name\s*=\s*'([^']*)'""").find(html)?.groupValues?.get(1)
        fun ids(raw: String?) = raw?.let { Regex("\\d+").findAll(it).map { m -> CardId(m.value.toInt()) }.toList() }.orEmpty()
        val main = ids(js("maindeckjs"))
        if (main.isEmpty()) return null
        val name = Regex("""var deckname\s*=\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)
            ?.let { unescapeJs(it) }?.let(::entities)?.trim()?.takeIf { it.isNotBlank() } ?: "Deck $number"
        fun para(label: String) = Regex("""<p>$label:\s*(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let(::text)
        val tournament = para("Tournament")
        val event = tournament?.substringBefore(" – ")?.trim()
        val date = tournament?.substringAfter(" – ", "")?.trim()?.takeIf { it.isNotBlank() }
        val category = para("Category").orEmpty()
        val url = Regex("""<meta property="og:url" content="([^"]*)"""").find(html)?.groupValues?.get(1) ?: "https://ygoprodeck.com/deck/$number"
        return TournamentDeck(
            number = number,
            name = name,
            event = event ?: "an event",
            placement = para("Placement") ?: "Top",
            players = null,
            pilot = para("Creator"),
            format = when {
                category.contains("genesys", ignoreCase = true) -> DeckFormat.GENESYS
                category.contains("ocg", ignoreCase = true) -> DeckFormat.OCG
                else -> DeckFormat.TCG
            },
            daysAgo = 999,
            tier = 0,
            deck = Deck(main, ids(js("extradeckjs")), ids(js("sidedeckjs"))),
            url = url,
            date = date,
            day = TournamentDecks.eventDay(date),
        )
    }

    private fun unescapeJs(s: String): String = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(s) { m ->
        val e = m.groupValues[1]
        if (e.length == 5) e.drop(1).toInt(16).toChar().toString() else when (e) {
            "n" -> "\n"
            "t" -> "\t"
            else -> e
        }
    }

    fun absolute(path: String): String = if (path.startsWith("http")) path else "https://ygoprodeck.com$path"

    /** The player's page, as the site writes it: spaces as `+`, everything else escaped. */
    fun playerPath(name: String): String = "/tournaments/by-player/" + name.trim().split(space).joinToString("+") { encodeWord(it) }

    fun encodeWord(s: String): String = buildString {
        s.encodeToByteArray().forEach { b ->
            val c = b.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-_.~") append(c.toChar()) else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }

    /** A deck's number from a YGOPRODeck deck address (`/deck/ignister-maliss-735623`) or the number itself. */
    fun numberOf(s: String): Int? = s.trim().toIntOrNull() ?: deckNumber.find(s.trim().substringBefore('?').trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull()

    /** Names compared as a person types them: case, accents and spacing ignored. */
    fun fold(s: String): String = buildString {
        s.lowercase().forEach { c -> append(accents[c] ?: c) }
    }.replace(space, " ").trim()

    /** Whether [query] names [name]: every word of it is in the name. */
    fun names(query: String, name: String?): Boolean {
        if (name == null) return false
        val n = fold(name)
        return fold(query).split(' ').filter { it.isNotBlank() }.all { it in n }
    }

    private val accents: Map<Char, Char> = buildMap {
        fun put(from: String, to: Char) = from.forEach { put(it, to) }
        put("àáâãäåāăą", 'a'); put("çćĉċč", 'c'); put("ďđ", 'd'); put("èéêëēĕėęěẹ", 'e'); put("ĝğġģ", 'g')
        put("ìíîïĩīĭįı", 'i'); put("ñńņňŉ", 'n'); put("òóôõöøōŏőơọồ", 'o'); put("ùúûüũūŭůűųưụ", 'u')
        put("ýÿŷỳ", 'y'); put("śŝşšș", 's'); put("ţťțŧ", 't'); put("źżž", 'z'); put("ĥħ", 'h'); put("ĵ", 'j'); put("ķ", 'k')
        put("ĺļľŀł", 'l'); put("ŕŗř", 'r'); put("ŵ", 'w'); put("ạảấầẩẫậắằẳẵặ", 'a'); put("ẻẽếềểễệ", 'e'); put("ỉị", 'i'); put("ỏốổỗộớờởỡợ", 'o')
        put("ủứừửữự", 'u'); put("ỵỷỹ", 'y')
    }
}
