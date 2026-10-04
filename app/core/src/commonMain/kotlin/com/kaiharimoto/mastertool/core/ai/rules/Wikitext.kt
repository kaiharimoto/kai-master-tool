package com.kaiharimoto.mastertool.core.ai.rules

import com.kaiharimoto.mastertool.core.ai.web.HtmlText

/**
 * MediaWiki wikitext as readable text, for rulings and archetype pages fetched from
 * Yugipedia. The API hands out wikitext rather than HTML because a ruling's question,
 * answer and citation are *named parameters* of a `{{Ruling}}` template there, and
 * would be lost in rendered HTML; the price is reading wikitext, which is what this is.
 *
 * Not a MediaWiki renderer: templates are not fetched or run. The few that carry the
 * page's words are expanded here — a ruling, a decklist, a combo, and link-like ones
 * such as `{{Card|X}}` that stand for their first argument — and every other template
 * (navigation boxes, infoboxes) is dropped. Links become their label, references and
 * comments go, bold and italic marks go, lists become `- ` and `1. ` lines.
 *
 * Yugipedia's text is CC BY-SA 4.0: whatever quotes it must say so
 * ([Yugipedia.ATTRIBUTION]).
 */
object Wikitext {
    /**
     * One ruling: its question when it has one, its answer, where it comes from ([cite]: a
     * `{{Ruling}}`'s question number in Konami's database, or a bullet's reference in words),
     * and the headings it stands under ([section], "OCG Rulings › Q&A Rulings") — which is
     * what says whether a ruling is the TCG's or the OCG's, and they can differ.
     */
    data class Ruling(val question: String?, val answer: String, val cite: String?, val section: String? = null) {
        /** Where it comes from, in words: a bare number is a question in Konami's OCG card database. */
        val source: String? get() = cite?.let { if (it.all(Char::isDigit)) "Konami OCG Card Database, Q&A #$it" else it }

        /** The ruling as one item of a list, for Ai: `- [OCG Rulings] Q: … A: … (source: …)`. */
        fun line(): String = buildString {
            append("- ")
            section?.let { append('[').append(it).append("] ") }
            if (question != null) append("Q: ").append(question).append("\n  A: ").append(answer) else append(answer)
            source?.let { append(" (source: ").append(it).append(')') }
        }
    }

    data class Section(val level: Int, val title: String, val body: String)

    /** Templates that stand for their first argument: a card's, an archetype's, a character's name. */
    private val LINKISH = setOf("card", "c", "arch", "archetype", "series", "char", "character", "nowrap", "ruby")

    /** Templates whose named parameters are the page's content (the rest are chrome, and dropped). */
    private val LISTS = setOf("decklist", "combo steps")

    /** Sections that hold no rulings, however their bullets look. */
    private val NOT_RULINGS = setOf("references", "see also", "external links", "navigation", "sources")

    fun plain(wikitext: String): String {
        var s = stripComments(wikitext)
        s = stripRefs(s)
        s = expandTemplates(s)
        s = links(s)
        s = EXTERNAL_LINK.replace(s) { it.groupValues[2] }
        s = BOLD_ITALIC.replace(s, "")
        s = BR.replace(s, "\n")
        s = HTML_TAG.replace(s, "")
        s = MAGIC_WORD.replace(s, "")
        s = HtmlText.decode(s)
        return lines(s)
    }

    /**
     * Every ruling on a page: the bullets of its ruling sections, as answers with no
     * question, and each `{{Ruling | Q = … | A = … | cite = … }}`. A bullet nested
     * under another (`**`) belongs to it and is added to its answer.
     */
    fun rulings(wikitext: String): List<Ruling> {
        val s = stripComments(wikitext)
        val out = mutableListOf<Ruling>()
        var section = ""
        // The headings over the line, by level: "OCG Rulings" over "Q&A Rulings".
        val headings = mutableMapOf<Int, String>()
        val refs = REF_NAMED.findAll(s).associate { it.groupValues[1].trim() to it.groupValues[2] }
        var lastWasBullet = false
        for (line in logicalLines(s)) {
            val t = line.trim()
            val heading = HEADING.matchEntire(t)
            val under = headings.entries.sortedBy { it.key }.joinToString(" › ") { it.value }.ifEmpty { null }
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    val title = plainInline(heading.groupValues[2])
                    headings.keys.retainAll { it < level }
                    headings[level] = title
                    section = title.lowercase()
                    lastWasBullet = false
                }
                t.startsWith("{{") -> {
                    lastWasBullet = false
                    val end = templateEnd(t, 0)
                    if (end < 0) continue
                    val template = template(t.substring(2, end - 2))
                    if (template.name != "ruling") continue
                    // Named or in order, as the template takes them: Q, A, cite (a `source` in words wins).
                    val q = (template.named("q", "question") ?: template.positional.getOrNull(0))?.let { plainInline(it) }?.ifEmpty { null }
                    val a = (template.named("a", "answer") ?: template.positional.getOrNull(1))?.let { plainInline(it) }.orEmpty()
                    val cite = (template.named("source") ?: template.named("cite") ?: template.positional.getOrNull(2))
                        ?.let { plainInline(it) }?.ifEmpty { null }
                    if (q != null || a.isNotEmpty()) out += Ruling(q, a, cite, under)
                }
                t.startsWith("*") && section !in NOT_RULINGS -> {
                    val depth = t.takeWhile { it == '*' }.length
                    val text = plainInline(t.drop(depth))
                    if (text.isEmpty()) continue
                    val last = out.lastOrNull()
                    if (depth > 1 && lastWasBullet && last != null) {
                        out[out.lastIndex] = last.copy(answer = last.answer + "\n- " + text)
                    } else {
                        out += Ruling(null, text, refCite(t, refs), under)
                    }
                    lastWasBullet = true
                }
                t.isNotEmpty() -> lastWasBullet = false
            }
        }
        return out
    }

    /**
     * A bullet's source: its first reference, in words — written on the line, or only named
     * there (`<ref name="No.12950"/>`) and written once elsewhere on the page.
     */
    private fun refCite(line: String, refs: Map<String, String>): String? {
        val ref = REF_ANY.find(line) ?: return null
        val name = REF_NAME.find(ref.groupValues[1])?.groupValues?.get(1)?.trim()
        val body = ref.groupValues[3].ifBlank { name?.let { refs[it] }.orEmpty() }
        return plainInline(body).ifEmpty { null }
    }

    /**
     * The page cut at its headings, each section's body read by [plain] and holding
     * only its own text (a subsection is a section of its own). Text before the first
     * heading, when there is any, is a section of level 0 with no title.
     */
    fun sections(wikitext: String): List<Section> {
        val out = mutableListOf<Section>()
        var level = 0
        var title = ""
        val body = StringBuilder()
        fun flush() {
            val text = plain(body.toString())
            if (level > 0 || text.isNotEmpty()) out += Section(level, title, text)
            body.clear()
        }
        for (line in logicalLines(stripComments(wikitext))) {
            val heading = HEADING.matchEntire(line.trim())
            if (heading != null) {
                flush()
                level = heading.groupValues[1].length
                title = plainInline(heading.groupValues[2])
            } else {
                body.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    // --- Pieces ------------------------------------------------------------------

    private val HEADING = Regex("(={1,6})\\s*(.+?)\\s*\\1")
    private val COMMENT = Regex("<!--[\\s\\S]*?(-->|$)")
    private val REF_SELF = Regex("<ref\\b[^>]*/>", RegexOption.IGNORE_CASE)
    private val REF_PAIR = Regex("<ref\\b[^>]*>[\\s\\S]*?</ref\\s*>", RegexOption.IGNORE_CASE)
    private val REF_ANY = Regex("<ref\\b([^>]*?)(/>|>([\\s\\S]*?)</ref\\s*>)", RegexOption.IGNORE_CASE)
    private val REF_NAME = Regex("name\\s*=\\s*\"?([^\"/>]+)\"?", RegexOption.IGNORE_CASE)
    private val REF_NAMED = Regex("<ref\\s+name\\s*=\\s*\"?([^\"/>]+?)\"?\\s*>([\\s\\S]*?)</ref\\s*>", RegexOption.IGNORE_CASE)
    private val REFERENCES = Regex("<references\\b[^>]*/>|<references\\b[^>]*>[\\s\\S]*?</references\\s*>", RegexOption.IGNORE_CASE)
    private val EXTERNAL_LINK = Regex("\\[((?:https?:)?//[^\\s\\]]+)(?:\\s+([^\\]]*))?]")
    private val BOLD_ITALIC = Regex("'{2,5}")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^<>]*)?/?>")
    private val MAGIC_WORD = Regex("__[A-Z]+__")

    private fun stripComments(s: String) = COMMENT.replace(s, "")

    private fun stripRefs(s: String) = REFERENCES.replace(REF_PAIR.replace(REF_SELF.replace(s, ""), ""), "")

    /** [plain] on one line: for a question, an answer, a title. */
    private fun plainInline(s: String) = plain(s).replace(Regex("\\s*\n\\s*"), " ").trim()

    /**
     * The text split into lines, except that a template running over several lines
     * stays in the line it began on — a `{{Ruling}}` is one logical line.
     */
    private fun logicalLines(s: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        var depth = 0
        while (i < s.length) {
            when {
                s.startsWith("{{", i) -> { depth++; i += 2 }
                s.startsWith("}}", i) && depth > 0 -> { depth--; i += 2 }
                s[i] == '\n' && depth == 0 -> {
                    out += s.substring(start, i)
                    start = i + 1
                    i++
                }
                else -> i++
            }
        }
        if (start < s.length) out += s.substring(start)
        return out
    }

    /** The index just past the `}}` closing the template opened at [from], or -1 when it never closes. */
    private fun templateEnd(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            when {
                s.startsWith("{{", i) -> { depth++; i += 2 }
                s.startsWith("}}", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) return i
                }
                else -> i++
            }
        }
        return -1
    }

    private class Template(val name: String, val positional: List<String>, val params: List<Pair<String, String>>) {
        fun named(vararg keys: String): String? = params.firstOrNull { (k, _) -> k in keys }?.second
    }

    /** A template's inside (between the braces) cut into its name and parameters at top-level pipes. */
    private fun template(inner: String): Template {
        val parts = mutableListOf<String>()
        var depthT = 0
        var depthL = 0
        var start = 0
        var i = 0
        while (i < inner.length) {
            when {
                inner.startsWith("{{", i) -> { depthT++; i += 2 }
                inner.startsWith("}}", i) -> { depthT--; i += 2 }
                inner.startsWith("[[", i) -> { depthL++; i += 2 }
                inner.startsWith("]]", i) -> { depthL--; i += 2 }
                inner[i] == '|' && depthT <= 0 && depthL <= 0 -> {
                    parts += inner.substring(start, i)
                    start = ++i
                }
                else -> i++
            }
        }
        parts += inner.substring(start)
        val name = parts.first().trim().replace('_', ' ').lowercase()
        val positional = mutableListOf<String>()
        val named = mutableListOf<Pair<String, String>>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            val key = if (eq > 0) p.substring(0, eq).trim() else ""
            if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }) {
                named += key.lowercase() to p.substring(eq + 1).trim()
            } else {
                positional += p.trim()
            }
        }
        return Template(name, positional, named)
    }

    /** Every template replaced by the text it stands for, or by nothing. */
    private fun expandTemplates(s: String): String {
        if ("{{" !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf("{{", i)
            if (open < 0) {
                out.append(s, i, s.length)
                break
            }
            out.append(s, i, open)
            val end = templateEnd(s, open)
            if (end < 0) {
                // Never closed: drop the rest rather than show raw template syntax.
                break
            }
            out.append(render(template(s.substring(open + 2, end - 2))))
            i = end
        }
        return out.toString()
    }

    private fun render(t: Template): String = when {
        t.name == "ruling" -> {
            val q = t.named("q", "question")?.let { plainInline(it) }.orEmpty()
            val a = t.named("a", "answer")?.let { plainInline(it) }.orEmpty()
            "\n" + (if (q.isNotEmpty()) "Q: $q\n" else "") + "A: $a\n"
        }
        t.name in LINKISH -> t.positional.firstOrNull()?.let { plainInline(it) }.orEmpty()
        t.name in LISTS -> buildString {
            append('\n')
            t.positional.firstOrNull()?.let { append(plainInline(it)).append('\n') }
            for ((key, value) in t.params) {
                val v = plain(value)
                if (v.isEmpty()) continue
                val label = key.replaceFirstChar { it.uppercase() }
                if ('\n' in v || v.startsWith("- ") || v.startsWith("1. ")) append("$label:\n$v\n") else append("$label: $v\n")
            }
        }
        else -> ""
    }

    /** `[[Page|label]]` to its label, `[[Page]]` to the page; files and categories removed. */
    private fun links(s: String): String {
        if ("[[" !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf("[[", i)
            if (open < 0) {
                out.append(s, i, s.length)
                break
            }
            out.append(s, i, open)
            // Matching brackets, since a file's caption can hold links of its own.
            var depth = 0
            var j = open
            var end = -1
            while (j < s.length) {
                when {
                    s.startsWith("[[", j) -> { depth++; j += 2 }
                    s.startsWith("]]", j) -> {
                        depth--
                        j += 2
                        if (depth == 0) {
                            end = j
                            break
                        }
                    }
                    else -> j++
                }
            }
            if (end < 0) {
                out.append(s, open, s.length)
                break
            }
            out.append(linkText(s.substring(open + 2, end - 2)))
            i = end
        }
        return out.toString()
    }

    private fun linkText(inner: String): String {
        val target = inner.substringBefore('|').trim()
        val ns = target.substringBefore(':', "").trim().lowercase()
        if (ns in setOf("file", "image", "category")) return ""
        if ('|' in inner) return links(inner.substringAfter('|')).trim()
        return target.removePrefix(":").substringBefore('#').ifEmpty { target.substringAfter('#') }
    }

    /** Headings as plain lines, lists as `- ` and `1. `, table markup gone, spaces squeezed, one blank line at most. */
    private fun lines(s: String): String {
        val out = mutableListOf<String>()
        val counters = IntArray(8)
        for (raw in s.split('\n')) {
            var t = raw.trim().replace(Regex("[ \\t\\u00A0]{2,}"), " ")
            val heading = HEADING.matchEntire(t)
            if (heading != null) {
                out += ""
                out += heading.groupValues[2]
                out += ""
                counters.fill(0)
                continue
            }
            if (t.startsWith("{|") || t.startsWith("|}") || t.startsWith("|-")) continue
            if (t.startsWith("|+") || (t.startsWith("|") && !t.startsWith("||")) || t.startsWith("!")) {
                t = t.drop(if (t.startsWith("|+")) 2 else 1).replace("||", " | ").replace("!!", " | ").trim()
            }
            val marks = t.takeWhile { it == '*' || it == '#' || it == ':' || it == ';' }
            if (marks.isNotEmpty()) {
                val text = t.drop(marks.length).trim()
                val depth = marks.length.coerceAtMost(counters.size)
                val indent = "  ".repeat(depth - 1)
                // A numbered item counts on at its depth; anything else at a depth restarts the count there.
                if (marks.last() == '#') counters[depth - 1]++ else counters[depth - 1] = 0
                for (d in depth until counters.size) counters[d] = 0
                t = when (marks.last()) {
                    '*' -> "$indent- $text"
                    '#' -> "$indent${counters[depth - 1]}. $text"
                    else -> "$indent$text"
                }
            } else if (t.isNotEmpty()) {
                counters.fill(0)
            }
            out += t
        }
        // One blank line at most, none at either end.
        val result = StringBuilder()
        var blank = false
        for (line in out) {
            if (line.isBlank()) {
                blank = result.isNotEmpty()
                continue
            }
            if (result.isNotEmpty()) result.append(if (blank) "\n\n" else "\n")
            result.append(line.trimEnd())
            blank = false
        }
        return result.toString()
    }
}
