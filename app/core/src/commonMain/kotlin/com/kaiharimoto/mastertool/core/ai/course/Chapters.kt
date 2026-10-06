package com.kaiharimoto.mastertool.core.ai.course

/**
 * A guide's chapters read off its contents page without asking a model: the links that stay inside the guide — the
 * start's own path, deeper — in the order the page lists them, each once. A contents page that does not look like that
 * (fewer than [MIN] such links) is left to Ai, which reads the page and names them itself.
 */
object Chapters {
    const val MIN = 2

    /** The chapters [links] (text and address, in page order) point to, under [start]. */
    fun fromLinks(links: List<Pair<String, String>>, start: String): List<Chapter> {
        val base = normal(start)
        val host = BrowseGuard.host(start).removePrefix("www.")
        val seen = HashSet<String>()
        val found = links.mapNotNull { (text, href) ->
            val url = absolute(href, start) ?: return@mapNotNull null
            val n = normal(url)
            val title = text.lines().joinToString(" ") { it.trim() }.trim()
            when {
                BrowseGuard.host(url).removePrefix("www.") != host -> null
                n == base || !n.startsWith("$base/") -> null
                title.isBlank() || title.length > 160 -> null
                !seen.add(n) -> null
                else -> title to url.substringBefore('#')
            }
        }
        if (found.size < MIN) return emptyList()
        return found.mapIndexed { i, (title, url) -> Chapter(n = i + 1, title = title, url = url) }
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
