package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.Recall
import com.kaiharimoto.mastertool.core.ai.report.GuideDoc

/**
 * Reading a memory file a part at a time (1.1.9): once a file is larger than its room in the prompt ([MemoryBudget]),
 * this is how Ai reaches the rest — `memory_read` with a query, a label or a range, and `recall` with scope memory
 * across every file. Each answer fits one tool result ([PAGE] characters, under `Compaction.RESULT_CAP`), numbers
 * every entry by its place in the file, and says where to read on, so nothing is ever cut out of the middle unseen.
 */
object MemoryQuery {
    /** What one read returns at most, in characters: under a tool result's cap, so it arrives whole. */
    const val PAGE = 12_000

    /** Words too common to say what an entry is about. */
    private val STOP = setOf(
        "the", "and", "for", "you", "your", "with", "that", "this", "what", "how", "are", "was", "but", "not", "can", "its",
        "it's", "from", "have", "has", "they", "them", "when", "why", "who", "which", "would", "should", "could", "about",
        "into", "out", "all", "any", "one", "our", "his", "her", "their", "there", "then", "than", "also", "just", "some",
        "more", "most", "does", "did", "will", "may", "use", "using", "want", "need", "please", "tell", "me", "my", "is",
        "to", "of", "in", "on", "or", "an", "be", "do", "if", "so", "at", "by", "as", "we", "us", "it", "no", "yes",
    )

    /** The words of [query] that can say what an entry is about. */
    fun terms(query: String): List<String> = Recall.words(query).filter { it !in STOP }

    /** Where [word] stands in [lower] as a word of its own; -1 when it does not. */
    fun wordAt(lower: String, word: String): Int = Recall.wordAt(lower, word)

    /**
     * The label [entry] begins with ("Lines: …"), as the guide sorts it — its section when the guide knows the label,
     * else the label as written; null when it has none.
     */
    fun labelOf(entry: String): String? {
        val (section, _) = GuideDoc.split(entry)
        if (section != "Notes") return section
        val clean = entry.trim().removePrefix("**")
        val colon = clean.indexOf(':')
        if (colon !in 1..24) return null
        val label = clean.substring(0, colon).removeSuffix("**").trim()
        return label.takeIf { it.isNotEmpty() && !it.startsWith("[") && !it.contains("http") }
    }

    /** Whether [entry] is under [label]: the guide's section (an alias resolves) or the label as written. */
    fun hasLabel(entry: String, label: String): Boolean {
        val asked = label.trim().removeSuffix(":")
        if (asked.isEmpty()) return false
        val section = GuideDoc.split("$asked: x").first.takeIf { it != "Notes" } ?: asked
        val own = labelOf(entry) ?: if (section.equals("Notes", ignoreCase = true)) "Notes" else return false
        return own.equals(section, ignoreCase = true) || own.equals(asked, ignoreCase = true)
    }

    /** An entry matched by a query: its place in the file (1-based) and how many of the query's words it holds. */
    data class Match(val number: Int, val entry: String, val found: Int, val score: Double)

    /** [entries] holding [query]'s words, the most first — at least half of them, or the one word asked. */
    fun matches(entries: List<String>, query: String): List<Match> {
        val words = terms(query).ifEmpty { Recall.words(query) }
        if (words.isEmpty()) return emptyList()
        val need = (words.size + 1) / 2
        return entries.mapIndexedNotNull { i, e ->
            val lower = e.lowercase()
            val found = words.count { wordAt(lower, it) >= 0 }
            if (found == 0 || found < need) null else Match(i + 1, e, found, found + (i + 1.0) / (entries.size + 1))
        }.sortedByDescending { it.score }
    }

    /**
     * What `memory_read` answers for a file: [title] and its size; then, by [query] (best first), by [label] or by
     * range ([from], 1-based, [count] entries) — every entry numbered by its place in the file, up to [page]
     * characters, and where to read on. With none of them, the file from its first entry.
     */
    fun read(
        doc: MemoryDoc,
        title: String,
        query: String? = null,
        label: String? = null,
        from: Int? = null,
        count: Int? = null,
        page: Int = PAGE,
    ): String {
        val entries = doc.entries
        val head = "$title — ${entries.size} ${if (entries.size == 1) "entry" else "entries"}, ${MemoryBudget.grouped(doc.used)} characters."
        if (entries.isEmpty()) return "$head\n(empty)"
        val start = (from ?: 1).coerceAtLeast(1)
        val want = count?.coerceAtLeast(1) ?: Int.MAX_VALUE
        return buildString {
            appendLine(head)
            when {
                !query.isNullOrBlank() -> {
                    val all = matches(entries, query).let { m -> if (label.isNullOrBlank()) m else m.filter { hasLabel(it.entry, label) } }
                    if (all.isEmpty()) {
                        append("No entry holds “${query.trim()}”" + (label?.takeIf { it.isNotBlank() }?.let { " under $it" } ?: "") + ". Try other words, a label, or a range.")
                        return@buildString
                    }
                    val shown = fit(all.filter { it.number >= start }.take(want).map { it.number to it.entry }, page - length)
                    appendLine("Entries holding “${query.trim()}”: ${all.size}, the best first.")
                    shown.forEach { (n, e) -> appendLine("[$n] $e") }
                    val more = all.count { it.number >= start } - shown.size
                    if (more > 0) append("($more more match: narrow the query, add a label, or read a range.)")
                }
                !label.isNullOrBlank() -> {
                    val under = entries.withIndex().filter { hasLabel(it.value, label) }.map { it.index + 1 to it.value }
                    if (under.isEmpty()) {
                        val known = entries.mapNotNull(::labelOf).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                            .take(12).joinToString(", ") { "${it.key} ${it.value}" }
                        append("No entry is under “${label.trim()}”." + (if (known.isNotEmpty()) " Labels here: $known." else ""))
                        return@buildString
                    }
                    val after = under.filter { it.first >= start }
                    val shown = fit(after.take(want), page - length)
                    appendLine("Entries under “${label.trim()}”: ${under.size}" + (shown.firstOrNull()?.let { ", from [${it.first}]" } ?: "") + ".")
                    shown.forEach { (n, e) -> appendLine("[$n] $e") }
                    val more = after.size - shown.size
                    if (more > 0) append("($more more under it: read on with label “${label.trim()}” from ${after[shown.size].first}.)")
                }
                else -> {
                    if (start > entries.size) {
                        append("There is no entry $start: the file holds ${entries.size}.")
                        return@buildString
                    }
                    val range = (start..minOf(entries.size, if (want == Int.MAX_VALUE) entries.size else start + want - 1)).map { it to entries[it - 1] }
                    val shown = fit(range, page - length)
                    appendLine("Entries ${shown.first().first}–${shown.last().first}:")
                    shown.forEach { (n, e) -> appendLine("[$n] $e") }
                    val next = shown.last().first + 1
                    if (next <= entries.size) append("(${entries.size - next + 1} more: read on from $next.)")
                }
            }
        }.trimEnd()
    }

    /** As many of [items] as fit [room] characters, at least one (cut, when one alone is longer). */
    private fun fit(items: List<Pair<Int, String>>, room: Int): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var used = 0
        for ((n, e) in items) {
            val c = e.length + n.toString().length + 4
            if (out.isNotEmpty() && used + c > room - 120) break
            out += n to if (out.isEmpty() && c > room - 120) e.take((room - 200).coerceAtLeast(400)) + " […]" else e
            used += c
        }
        return out
    }

    /** One entry found by [search]: the file it is in, its place there (1-based), and the words around the find. */
    data class Hit(val path: String, val number: Int, val excerpt: String, val found: Int)

    /**
     * [query] across every memory file in [files] (path to its text): `recall` with scope memory. The entries holding
     * the most of its words first, then the newest, [limit] of them.
     */
    fun search(files: Map<String, String>, query: String, limit: Int = 12): List<Hit> {
        val hits = files.flatMap { (path, text) ->
            val entries = AiMemory.parse(text).entries
            matches(entries, query).map { m -> Triple(path, m, entries.size) }
        }
        val words = terms(query).ifEmpty { Recall.words(query) }
        return hits.sortedWith(compareByDescending<Triple<String, Match, Int>> { it.second.found }.thenByDescending { it.second.score })
            .take(limit)
            .map { (path, m, _) ->
                val lower = m.entry.lowercase()
                val at = words.map { wordAt(lower, it) }.filter { it >= 0 }.minOrNull() ?: 0
                val excerpt = if (m.entry.length <= 400) m.entry else {
                    val a = (at - 120).coerceAtLeast(0)
                    (if (a > 0) "…" else "") + m.entry.substring(a, (a + 400).coerceAtMost(m.entry.length)) + if (a + 400 < m.entry.length) "…" else ""
                }
                Hit(path, m.number, excerpt, m.found)
            }
    }
}
