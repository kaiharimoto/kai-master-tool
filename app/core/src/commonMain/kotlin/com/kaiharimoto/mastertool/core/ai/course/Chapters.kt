package com.kaiharimoto.mastertool.core.ai.course

/**
 * A guide's chapters read off its contents page without asking a model: the links that stay inside the guide — the
 * start's own path, deeper — in the order the page lists them, each once. A contents page that does not look like that
 * (fewer than [MIN] such links) is left to Ai, which reads the page and names them itself.
 */
object Chapters {
    const val MIN = 2

    /**
     * The chapters [links] (text and address, in page order) point to, under [start]. A link of many words — a card with
     * the chapter's title, its description and its length — is named by its first line. An address listed more than once
     * ("Continue", then the chapter's own name) is named, and placed, by the link that is not a navigation word; and a
     * guide's own pages that are not chapters (comments, reviews, checkout, settings…) are passed over.
     */
    fun fromLinks(links: List<Pair<String, String>>, start: String): List<Chapter> {
        val base = normal(start)
        val host = BrowseGuard.host(start).removePrefix("www.")
        data class Seen(val at: Int, val title: String, val url: String)
        val byPlace = LinkedHashMap<String, MutableList<Seen>>()
        links.forEachIndexed { i, (text, href) ->
            val url = absolute(href, start) ?: return@forEachIndexed
            val n = normal(url)
            val title = title(text)
            when {
                host.isBlank() || BrowseGuard.host(url).removePrefix("www.") != host -> Unit
                n == base || !n.startsWith("$base/") -> Unit
                n.substringAfterLast('/').lowercase() in NOT_CHAPTERS -> Unit
                title.isBlank() -> Unit
                else -> byPlace.getOrPut(n) { ArrayList() } += Seen(i, title, url.substringBefore('#'))
            }
        }
        val found = byPlace.values.map { seen -> seen.firstOrNull { !navigation(it.title) } ?: seen.first() }.sortedBy { it.at }
        if (found.size < MIN) return emptyList()
        return found.mapIndexed { i, s -> Chapter(n = i + 1, title = s.title, url = s.url) }
    }

    /** The longest a link's words may be and still be a chapter's title whole. */
    const val TITLE = 160

    /** A link's words as a chapter's title: whole when short, else its first line, cut at a word near [TITLE_CUT]. */
    fun title(text: String): String {
        val whole = text.lines().joinToString(" ") { it.trim() }.trim()
        if (whole.length <= TITLE) return whole
        val first = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (first.length <= TITLE_CUT) return first
        val space = first.lastIndexOf(' ', TITLE_CUT).takeIf { it > TITLE_CUT / 2 } ?: TITLE_CUT
        return first.take(space).trimEnd(' ', ',', ';', ':', '-', '—', '·') + "…"
    }

    const val TITLE_CUT = 120

    /** A guide's pages that are never chapters, by their last path segment. */
    private val NOT_CHAPTERS = setOf(
        "comments", "reviews", "discussion", "discussions", "community", "checkout", "purchase", "buy", "cart", "settings", "account", "logout", "login",
    )

    private val NAVIGATION = Regex(
        """^(?:continue|resume|start|begin|next|previous|prev|back|go\s+to|read\s+more|read|view|open|watch|up\s+next)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a link's [title] is a way to move through the guide ("Continue", "Next lesson →") rather than a chapter's name. */
    private fun navigation(title: String): Boolean {
        val t = title.trim().trimStart('←', '‹', '«', '<', ' ')
        return t.none { it.isLetterOrDigit() } || NAVIGATION.containsMatchIn(t)
    }

    /** [href] made whole against the page at [page]: absolute, root-relative or relative; null for anything else. */
    fun absolute(href: String, page: String): String? {
        val h = href.trim()
        return when {
            h.startsWith("https://", ignoreCase = true) || h.startsWith("http://", ignoreCase = true) -> h
            h.startsWith("//") -> "https:$h"
            h.startsWith("/") -> page.substringBefore("://") + "://" + page.substringAfter("://").substringBefore('/') + h
            h.isEmpty() || h.startsWith("#") || h.contains(':') -> null
            else -> page.substringBefore('?').substringBefore('#').substringBeforeLast('/') + "/" + h
        }
    }

    /** An address compared as a place: scheme, query and fragment aside, no trailing slash, lowercase host. */
    private fun normal(url: String): String {
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').lowercase().removePrefix("www.")
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/')
        return if (path.isEmpty()) host else "$host/$path"
    }
}

/** A chapter's kept text read a part at a time: the study's notes run reads it all, in pieces a model can hold. */
object CourseText {
    const val PART = 12_000

    /** [text] from character [from], at most [count], with where it stands and where the next part starts. */
    fun part(text: String, from: Int = 0, count: Int = PART): String {
        if (text.isEmpty()) return "(Nothing kept.)"
        val start = from.coerceIn(0, text.length)
        // Ends on a line break where one is near, so a part never splits a sentence it could keep whole.
        var end = (start + count.coerceIn(500, 40_000)).coerceAtMost(text.length)
        if (end < text.length) text.lastIndexOf('\n', end).takeIf { it > start + count / 2 }?.let { end = it + 1 }
        val head = "Characters $start–$end of ${text.length}."
        val tail = if (end < text.length) "\n\n(More: read again from $end.)" else "\n\n(The end.)"
        return head + "\n\n" + text.substring(start, end) + tail
    }

    /** The words in [text], for the course's record. */
    fun words(text: String): Int = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
}
