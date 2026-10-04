package com.kaiharimoto.mastertool.core.ai.rules

import com.kaiharimoto.mastertool.core.ai.web.HtmlText
import com.kaiharimoto.mastertool.core.ai.web.SearchResults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Yugipedia's MediaWiki API, for rulings and archetype pages: the addresses to ask
 * and the JSON they answer with, read. The fetching is the caller's (it owns the
 * HTTP client and the user agent Yugipedia asks bots to send).
 *
 * Rulings live on their own pages, `Card Rulings:<card name>`; an archetype's
 * "Playing style" is one section of its page, fetched alone by its index from
 * [sectionsUrl] so Ai reads a few kilobytes, not the whole article. Everything uses
 * `formatversion=2`, whose JSON puts the wikitext in a plain string; the older shape,
 * `{"wikitext":{"*":…}}`, is read too.
 *
 * The text is CC BY-SA 4.0: anything that quotes it carries [ATTRIBUTION].
 */
object Yugipedia {
    const val API = "https://yugipedia.com/api.php"
    const val ATTRIBUTION = "Source: Yugipedia (yugipedia.com), CC BY-SA 4.0."

    fun rulingsUrl(cardName: String): String = parseUrl("Card Rulings:$cardName")

    fun parseUrl(page: String, section: Int? = null): String =
        "$API?action=parse&page=${title(page)}&prop=wikitext" +
            (if (section != null) "&section=$section" else "") +
            "&format=json&formatversion=2&redirects=1"

    fun sectionsUrl(page: String): String = "$API?action=parse&page=${title(page)}&prop=sections&format=json&formatversion=2&redirects=1"

    /** The wiki's own search: titles and snippets for [query], for when a web search is not to be had. */
    fun searchUrl(query: String, limit: Int = 8): String =
        "$API?action=query&list=search&srsearch=${title(query).replace("_", "%20")}&srlimit=$limit&format=json&formatversion=2"

    /** A search answer's hits: title, page URL and the snippet as plain text. */
    fun searchHits(json: String): List<SearchResults.Hit> {
        val hits = (root(json)?.get("query") as? JsonObject)?.get("search") as? JsonArray ?: return emptyList()
        return hits.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val t = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val snippet = (o["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SearchResults.Hit(
                t,
                "https://yugipedia.com/wiki/${title(t)}",
                HtmlText.decode(snippet.replace(Regex("<[^>]+>"), "")),
            )
        }
    }

    /**
     * A page name as it goes in the URL: spaces as underscores, as MediaWiki writes
     * them, and anything that would end or bend the query (`&`, `#`, `?`, `%`, `+`,
     * `=`) or is not ASCII percent-encoded as UTF-8.
     */
    fun title(page: String): String {
        val out = StringBuilder()
        for (b in page.trim().replace(' ', '_').encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in SAFE)) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return out.toString()
    }

    private const val SAFE = "-_.~:/()!,'*;@"
    private const val HEX = "0123456789ABCDEF"

    /**
     * Whether an answer is the API's error, not a page: a page that does not exist comes back
     * 200 with `{"error":{"code":"missingtitle",…}}`, and must never be kept as if it were the
     * page — it may be written tomorrow. Anything that is not JSON is not a page either.
     */
    fun isError(json: String): Boolean = root(json)?.let { "error" in it } ?: true

    /** The wikitext in a `parse` answer, or null for an error (a missing page) or anything else. */
    fun wikitextOf(json: String): String? {
        val parse = root(json)?.takeIf { "error" !in it }?.get("parse") as? JsonObject ?: return null
        return when (val w = parse["wikitext"]) {
            is JsonPrimitive -> w.contentOrNull
            is JsonObject -> (w["*"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }

    /** Whether [json] is a `prop=sections` answer at all (its list may be empty): what the cache keeps (1.0.99). */
    fun hasSections(json: String): Boolean =
        (root(json)?.takeIf { "error" !in it }?.get("parse") as? JsonObject)?.get("sections") is JsonArray

    /**
     * The sections of a `prop=sections` answer whose headings hold any of [wanted]
     * (ignoring case), as their index — the number [parseUrl] takes — and their
     * heading, in page order. With nothing wanted, every section.
     */
    fun sectionIndex(json: String, wanted: List<String>): List<Pair<Int, String>> {
        val parse = root(json)?.takeIf { "error" !in it }?.get("parse") as? JsonObject ?: return emptyList()
        val sections = parse["sections"] as? JsonArray ?: return emptyList()
        return sections.mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            // A transcluded section's index is "T-1": not one this page can fetch.
            val index = (s["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            val raw = (s["line"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val line = HtmlText.decode(raw.replace(Regex("<[^>]*>"), "")).trim()
            if (wanted.isEmpty() || wanted.any { line.contains(it.trim(), ignoreCase = true) }) index to line else null
        }
    }

    private fun root(json: String): JsonObject? = try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (e: Exception) {
        null
    }
}
