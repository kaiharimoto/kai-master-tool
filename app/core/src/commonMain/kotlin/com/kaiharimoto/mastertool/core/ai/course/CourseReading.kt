package com.kaiharimoto.mastertool.core.ai.course

/**
 * Reading a course closely (kai, 2026-10: "the notes need to be thorough in order to attain mastery"): a chapter is read
 * in its sections, each numbered (§1, §2 …), and the notes say which section each thing came from — so the app can tell
 * which sections the notes have not covered yet and send the study back to them ([Sections.uncovered]). What a chapter
 * names is found against the card pool ([CardMentions]), so its notes are written against the real cards' text. And the
 * whole course — its chapters, its replays in words, the notes on both — can be searched at once ([CourseSearch]).
 */
object Sections {
    data class Section(val n: Int, val title: String, val text: String) {
        val words: Int get() = CourseText.words(text)
    }

    /** A section under this many words is not one the notes must cite (a caption, a lone heading). */
    const val MIN_WORDS = 40

    /** A chapter without headings is cut into parts about this long, each a section. */
    const val PART = 2_400

    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val CITE = Regex("""§\s*(\d{1,3})""")

    /** [text]'s sections: at its markdown headings, else in parts of about [PART] characters at line breaks. */
    fun of(text: String): List<Section> {
        val lines = text.lines()
        val heads = lines.indices.filter { HEADING.matches(lines[it]) }
        val raw = if (heads.size >= 2) {
            val out = ArrayList<Pair<String, String>>()
            if (heads.first() > 0) out += "Opening" to lines.subList(0, heads.first()).joinToString("\n")
            heads.forEachIndexed { k, at ->
                val end = heads.getOrNull(k + 1) ?: lines.size
                val title = HEADING.matchEntire(lines[at])!!.groupValues[2].trim()
                out += title to lines.subList(at + 1, end).joinToString("\n")
            }
            out
        } else {
            val out = ArrayList<Pair<String, String>>()
            val buf = StringBuilder()
            for (line in lines) {
                buf.appendLine(line)
                if (buf.length >= PART && line.isBlank()) {
                    out += "Part ${out.size + 1}" to buf.toString()
                    buf.setLength(0)
                }
            }
            if (buf.isNotBlank()) out += "Part ${out.size + 1}" to buf.toString()
            out
        }
        return raw.filter { it.second.isNotBlank() || it.first.isNotBlank() }.mapIndexed { i, (t, body) -> Section(i + 1, t, body.trim()) }
    }

    /** [text] with each section headed "§N Title", as the study reads it: what its notes cite. */
    fun numbered(text: String): String {
        val parts = of(text)
        if (parts.isEmpty()) return text
        return parts.joinToString("\n\n") { s -> "## §${s.n} ${s.title}\n\n${s.text}" }
    }

    /** Sections [first] to [last] of [text] alone, headed as [numbered] heads them: one part of a study ([StudyChunks]). */
    fun only(text: String, first: Int, last: Int): String =
        of(text).filter { it.n in first..last }.joinToString("\n\n") { s -> "## §${s.n} ${s.title}\n\n${s.text}" }

    /**
     * The sections [notes] cite ("§3", "§ 12") of the chapter or replay [unit] ("ch. 7", "replay 3"; null for any). A
     * citation in brackets that names another unit — "(ch. 7 §3)" in a replay's notes — is that unit's, never this one's
     * (1.1.47: a replay's notes citing the chapter that links to it counted as covering the replay's own sections).
     */
    fun cited(notes: String, unit: String? = null): Set<Int> {
        val self = unit?.let(::unitOf)
        if (self == null) return CITE.findAll(notes).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        val out = HashSet<Int>()
        var last = 0
        // Each bracket on its own: it counts unless it names a unit that is not this one; what is outside counts.
        BRACKET.findAll(notes).forEach { b ->
            out += numbers(notes.substring(last, b.range.first))
            val named = UNIT.findAll(b.value).map { it.groupValues[1].lowercase().first() to it.groupValues[2].toIntOrNull() }.toList()
            if (named.isEmpty() || self in named) out += numbers(b.value)
            last = b.range.last + 1
        }
        out += numbers(notes.substring(last))
        return out
    }

    private fun numbers(t: String): List<Int> = CITE.findAll(t).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

    /** "ch. 7" → ('c', 7), "replay 3" → ('r', 3). */
    private fun unitOf(u: String): Pair<Char, Int?>? = UNIT.find(u)?.let { it.groupValues[1].lowercase().first() to it.groupValues[2].toIntOrNull() }

    private val BRACKET = Regex("""[(\[][^)\]\n]{0,200}[)\]]""")
    private val UNIT = Regex("""\b(ch(?:apter)?|replay)\.?\s*(\d{1,3})""", RegexOption.IGNORE_CASE)

    /** The sections of [text] worth citing that [notes] do not cite yet. */
    fun uncovered(text: String, notes: String, unit: String? = null): List<Section> {
        val cited = cited(notes, unit)
        return of(text).filter { it.words >= MIN_WORDS && it.n !in cited }
    }

    /** How much of [text] [notes] cover: sections cited of the sections worth citing. */
    fun coverage(text: String, notes: String, unit: String? = null): Pair<Int, Int> {
        val worth = of(text).filter { it.words >= MIN_WORDS }
        val cited = cited(notes, unit)
        return worth.count { it.n in cited } to worth.size
    }
}

/**
 * The cards a text names, found against the names of every card: whole names only, case and punctuation aside, the
 * longest name at each place ("Branded Fusion", not "Fusion"). Fast enough for a long chapter against the whole pool:
 * names are looked up by their first word.
 */
object CardMentions {
    /** Every card of [names] that [text] names, in the order first named, each once. */
    fun find(text: String, names: Collection<String>): List<String> {
        val byFirst = HashMap<String, MutableList<Pair<List<String>, String>>>()
        names.forEach { name ->
            val words = tokens(name)
            if (words.isEmpty() || (words.size == 1 && words[0].length < 4)) return@forEach
            byFirst.getOrPut(words[0]) { ArrayList() } += words to name
        }
        byFirst.values.forEach { list -> list.sortByDescending { it.first.size } }
        val words = tokens(text)
        val found = LinkedHashSet<String>()
        var i = 0
        while (i < words.size) {
            val match = byFirst[words[i]]?.firstOrNull { (w, _) -> i + w.size <= words.size && (w.indices).all { k -> words[i + k] == w[k] } }
            if (match != null) {
                found += match.second
                i += match.first.size
            } else {
                i++
            }
        }
        return found.toList()
    }

    private fun tokens(s: String): List<String> =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotBlank() }
}

/**
 * The whole course searched at once: its chapters' kept text, its replays in words and the notes on both. A hit is a
 * line with a little around it and where it is, so a study (or a conversation, or a seat at the table) can go and read
 * it whole. Quoted words must stand together; other words may be anywhere in the line.
 */
object CourseSearch {
    /** A document of the course: [ref] is how it is cited and read ("ch. 4", "replay 12", "ch. 4 notes"). */
    data class Doc(val ref: String, val title: String, val text: String)

    data class Hit(val ref: String, val title: String, val line: Int, val excerpt: String, val score: Int)

    fun search(docs: List<Doc>, query: String, limit: Int = 20): List<Hit> {
        val phrases = Regex("\"([^\"]+)\"").findAll(query).map { norm(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val words = norm(query.replace(Regex("\"[^\"]*\""), " ")).split(' ').filter { it.length >= 2 && it !in STOP }.distinct()
        if (phrases.isEmpty() && words.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (d in docs) {
            val lines = d.text.lines()
            lines.forEachIndexed { i, line ->
                val n = norm(line)
                if (n.isBlank()) return@forEachIndexed
                if (phrases.any { it !in n }) return@forEachIndexed
                val got = words.count { it in n }
                if (phrases.isEmpty() && got < minOf(words.size, 2).coerceAtLeast(1)) return@forEachIndexed
                val score = got * 2 + phrases.size * 4 + if (d.ref.contains("notes")) 1 else 0
                val excerpt = (maxOf(0, i - 1)..minOf(lines.size - 1, i + 1)).joinToString(" ") { lines[it].trim() }.take(EXCERPT)
                hits += Hit(d.ref, d.title, i + 1, excerpt, score)
            }
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.ref }.thenBy { it.line }).take(limit.coerceIn(1, 60))
    }

    const val EXCERPT = 360

    private val STOP = setOf("the", "and", "of", "to", "in", "on", "for", "with", "is", "it", "a", "an", "or", "at", "by", "you", "your")

    private fun norm(s: String): String = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * The replays a study never reads (kai, 2026-10: about a fifth held out): its exam — how often Ai would make the author's
 * play in a duel it never studied — so the score says what it learned, not what it remembers. Chosen by the replay's id,
 * so the same replay is always in or out, and a replay already studied is never put in the exam.
 */
object ReplayExam {
    /** One in [EVERY] replays is held out. */
    const val EVERY = 5

    fun held(url: String): Boolean {
        val id = DbReplays.id(url) ?: url
        var h = 0x811c9dc5.toInt()
        id.forEach { c -> h = (h xor c.code) * 0x01000193 }
        return (h.toLong() and 0xffffffffL) % EVERY == 0L
    }
}
