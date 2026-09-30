package com.kaiharimoto.mastertool.core.ai.web

/**
 * Web search results read out of a search engine's plain HTML page, so Ai can search
 * without an API key. DuckDuckGo's HTML endpoint (`https://html.duckduckgo.com/html/?q=…`)
 * is the one read here: no script needed, one `result__a` link and one
 * `result__snippet` per result.
 *
 * Its links go through a redirect, `//duckduckgo.com/l/?uddg=<the real URL>&rut=…`,
 * and the real URL is what Ai should see and fetch, so `uddg` is decoded. Ads carry
 * `result--ad` (and click through `y.js`) and are skipped.
 *
 * DuckDuckGo answers traffic it takes for a bot with a challenge page (HTTP 202, "Select
 * all squares containing a duck") instead of results; [blocked] tells that apart from a
 * search that found nothing, so the caller can say so rather than report no results.
 */
object SearchResults {
    data class Hit(val title: String, val url: String, val snippet: String)

    fun duckDuckGo(html: String, limit: Int = 8): List<Hit> {
        val anchors = anchorsWithClass(html, "result__a")
        val hits = mutableListOf<Hit>()
        val seen = mutableSetOf<String>()
        for ((n, anchor) in anchors.withIndex()) {
            if (hits.size >= limit) break
            val before = html.substring(if (n == 0) 0 else anchors[n - 1].end, anchor.start)
            val href = attribute(anchor.tag, "href") ?: continue
            if ("result--ad" in before || "/y.js?" in href || "ad_domain=" in href) continue
            val url = resolve(href) ?: continue
            val title = inline(anchor.inner)
            if (title.isEmpty() || !seen.add(url)) continue
            val segmentEnd = anchors.getOrNull(n + 1)?.start ?: html.length
            val snippet = snippet(html.substring(anchor.end, segmentEnd))
            hits += Hit(title, url, snippet)
        }
        return hits
    }

    /** True when the page is DuckDuckGo's bot challenge rather than results. */
    fun blocked(html: String): Boolean = "anomaly-modal" in html || "anomaly.js" in html

    /**
     * The address a result link stands for: `uddg` decoded out of a DuckDuckGo
     * redirect, a scheme added to a protocol-relative link. Null for anything that
     * is not a web address (a relative link back into the search page).
     */
    fun resolve(href: String): String? {
        val h = HtmlText.decode(href).trim()
        val query = h.substringAfter('?', "")
        if ("duckduckgo.com/l/" in h || h.startsWith("/l/")) {
            val uddg = query.split('&').firstOrNull { it.startsWith("uddg=") }?.removePrefix("uddg=")
            if (uddg != null) return percentDecode(uddg)
        }
        return when {
            h.startsWith("//") -> "https:$h"
            h.startsWith("http://") || h.startsWith("https://") -> h
            else -> null
        }
    }

    /**
     * `%XX` escapes decoded as UTF-8 bytes. `+` is left alone: in a URL carried
     * inside another URL a literal plus arrives as `%2B`, and a space as `%20`. A
     * broken escape is kept as written.
     */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hi = if (c == '%' && i + 2 < s.length) hex(s[i + 1]) else -1
            val lo = if (hi >= 0) hex(s[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                bytes += ((hi shl 4) or lo).toByte()
                i += 3
            } else {
                for (b in c.toString().encodeToByteArray()) bytes += b
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private class Anchor(val start: Int, val end: Int, val tag: String, val inner: String)

    /** Every `<a …>…</a>` whose class list holds [cls], in page order. */
    private fun anchorsWithClass(html: String, cls: String): List<Anchor> {
        val out = mutableListOf<Anchor>()
        var i = 0
        while (true) {
            val a = html.indexOf("<a", i)
            if (a < 0) break
            val gt = html.indexOf('>', a)
            if (gt < 0) break
            val tag = html.substring(a, gt + 1)
            val after = html.getOrNull(a + 2)
            if (after != null && after.isWhitespace() && hasClass(tag, cls)) {
                val close = html.indexOf("</a>", gt).let { if (it < 0) html.length else it }
                out += Anchor(a, (close + 4).coerceAtMost(html.length), tag, html.substring(gt + 1, close))
                i = close
            } else {
                i = gt + 1
            }
        }
        return out
    }

    /** The first element in [segment] with the `result__snippet` class, as text. */
    private fun snippet(segment: String): String {
        var i = 0
        while (true) {
            val lt = segment.indexOf('<', i)
            if (lt < 0) return ""
            val gt = segment.indexOf('>', lt)
            if (gt < 0) return ""
            val tag = segment.substring(lt, gt + 1)
            if (hasClass(tag, "result__snippet")) {
                val name = tag.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase()
                val close = segment.indexOf("</$name", gt).let { if (it < 0) segment.length else it }
                return inline(segment.substring(gt + 1, close))
            }
            i = gt + 1
        }
    }

    private fun hasClass(tag: String, cls: String): Boolean =
        attribute(tag, "class")?.split(' ', '\t', '\n')?.contains(cls) == true

    private fun attribute(tag: String, name: String): String? {
        val m = Regex("\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
    }

    /** Inline markup (the `<b>` round matched words) dropped, entities decoded, one line. */
    private fun inline(html: String): String =
        HtmlText.decode(html.replace(Regex("<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()
}
