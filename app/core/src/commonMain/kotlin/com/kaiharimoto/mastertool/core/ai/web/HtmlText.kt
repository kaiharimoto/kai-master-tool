package com.kaiharimoto.mastertool.core.ai.web

/**
 * A web page as the text a person would read on it, for Ai to quote from. A model
 * given raw HTML spends most of its context on scripts, styles and class names;
 * given this it gets the words, with enough of the shape kept — headings as `#`
 * lines, list items as `- `, table cells split by ` | ` — to follow the page.
 *
 * Deliberately not a real HTML parser: a forgiving scan over tags that never
 * throws on broken markup, which is most of the web. Everything that is not text
 * (scripts, styles, `<noscript>`, inline SVG, comments, the head but for its title)
 * is dropped before the scan. A long page keeps its head and a tail of about 15 %
 * with a line saying how much was cut, since both ends of a page tend to matter
 * (the answer near the top, the date or the decklist near the bottom).
 */
object HtmlText {
    /** Elements whose content is never text. */
    private val DROPPED = listOf("script", "style", "noscript", "svg", "template", "iframe", "object", "title")

    /** In the scan's output: a line break, and a paragraph break (a blank line wanted). */
    private const val LINE_BREAK = '\n'
    private const val PARAGRAPH = '\u0001'

    /** Elements that start and end a line. */
    private val LINE = setOf(
        "div", "section", "article", "header", "footer", "nav", "aside", "main", "figure", "figcaption",
        "form", "fieldset", "address", "dt", "dd", "caption", "center", "details", "summary",
    )

    /** Elements that stand apart with a blank line either side. */
    private val BLOCK = setOf("p", "ul", "ol", "dl", "table", "blockquote", "pre", "hr", "h4", "h5", "h6")

    fun text(html: String, maxChars: Int = 12_000): String {
        val t = title(html)
        val body = render(clean(html))
        // The title leads unless the page already says it near the top.
        val full = if (t != null && t !in body.take(t.length + 300)) "$t\n\n$body".trim() else body
        return truncate(full, maxChars)
    }

    /** The page's `<title>`, decoded and on one line, or null when it has none. */
    fun title(html: String): String? {
        val lower = html.lowercase()
        val open = Regex("<title(\\s[^>]*)?>").find(lower) ?: return null
        val close = lower.indexOf("</title", open.range.last + 1).takeIf { it >= 0 } ?: return null
        return collapse(decode(html.substring(open.range.last + 1, close))).trim().ifEmpty { null }
    }

    /**
     * [text] no longer than [maxChars]: its head and a tail of about 15 %, joined by
     * a line saying how many characters were cut. Cuts fall on whitespace where one
     * is near, so a word is never split.
     */
    fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val tailWanted = maxChars * 15 / 100
        val budget = (maxChars - 48).coerceAtLeast(0)
        var headEnd = (budget - tailWanted).coerceAtLeast(0)
        var tailStart = text.length - tailWanted
        headEnd = snapBack(text, headEnd)
        tailStart = snapForward(text, tailStart)
        val head = text.substring(0, headEnd).trimEnd()
        val tail = text.substring(tailStart).trimStart()
        val cut = text.length - head.length - tail.length
        return "$head\n\n[… $cut characters cut …]\n\n$tail"
    }

    private fun snapBack(s: String, at: Int): Int {
        for (i in at downTo (at - 200).coerceAtLeast(1)) if (s[i - 1].isWhitespace()) return i
        return at
    }

    private fun snapForward(s: String, at: Int): Int {
        for (i in at until (at + 200).coerceAtMost(s.length)) if (s[i].isWhitespace()) return i
        return at
    }

    /** Comments, the head, and every element in [DROPPED] with its content, gone. */
    private fun clean(html: String): String {
        var s = removeBetween(html, "<!--", "-->")
        s = dropElement(s, "head")
        for (name in DROPPED) s = dropElement(s, name)
        return s
    }

    private fun removeBetween(s: String, open: String, close: String): String {
        val out = StringBuilder()
        var i = 0
        while (true) {
            val a = s.indexOf(open, i)
            if (a < 0) break
            out.append(s, i, a)
            val b = s.indexOf(close, a + open.length)
            if (b < 0) return out.toString()
            i = b + close.length
        }
        out.append(s, i, s.length)
        return out.toString()
    }

    /** Removes `<name …>…</name>` wherever it stands; an element never closed runs to the end. */
    private fun dropElement(s: String, name: String): String {
        val lower = s.lowercase()
        val out = StringBuilder()
        var i = 0
        while (true) {
            val a = openTag(lower, name, i)
            if (a < 0) break
            out.append(s, i, a)
            val close = lower.indexOf("</$name", a)
            if (close < 0) return out.toString()
            val end = lower.indexOf('>', close)
            i = if (end < 0) s.length else end + 1
        }
        out.append(s, i, s.length)
        return out.toString()
    }

    /** Where `<name` begins as a whole tag name (so `<header` is not `<head`), from [from]. */
    private fun openTag(lower: String, name: String, from: Int): Int {
        var i = from
        while (true) {
            val a = lower.indexOf("<$name", i)
            if (a < 0) return -1
            val after = lower.getOrNull(a + name.length + 1)
            if (after == null || after == '>' || after == '/' || after.isWhitespace()) return a
            i = a + 1
        }
    }

    /** The scan: text runs decoded and joined, tags turned into the line breaks and marks they stand for. */
    private fun render(html: String): String {
        val out = StringBuilder()
        var i = 0
        var cell = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                out.append(collapse(decode(html.substring(i))))
                break
            }
            if (lt > i) out.append(collapse(decode(html.substring(i, lt))))
            val next = html.getOrNull(lt + 1)
            if (next == null || !(next.isLetter() || next == '/' || next == '!' || next == '?')) {
                out.append('<')
                i = lt + 1
                continue
            }
            val gt = tagEnd(html, lt)
            val tag = html.substring(lt + 1, gt).trim()
            i = if (gt < html.length) gt + 1 else html.length
            if (tag.startsWith("!") || tag.startsWith("?")) continue
            val closing = tag.startsWith("/")
            val name = tag.removePrefix("/").takeWhile { it.isLetterOrDigit() }.lowercase()
            when {
                name == "br" -> out.append(LINE_BREAK)
                name == "li" -> {
                    out.append(LINE_BREAK)
                    if (!closing) out.append("- ")
                }
                name == "h1" || name == "h2" || name == "h3" -> {
                    out.append(PARAGRAPH)
                    if (!closing) out.append("#".repeat(name[1] - '0')).append(' ')
                }
                name == "tr" -> {
                    out.append(LINE_BREAK)
                    cell = 0
                }
                name == "td" || name == "th" -> if (!closing) {
                    if (cell > 0) out.append(" | ")
                    cell++
                }
                name in BLOCK -> out.append(PARAGRAPH)
                name in LINE -> out.append(LINE_BREAK)
            }
        }
        return tidy(out.toString())
    }

    /** The index of the `>` closing the tag opened at [lt], quotes respected; the end of [s] if none. */
    private fun tagEnd(s: String, lt: Int): Int {
        var quote: Char? = null
        var i = lt + 1
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return s.length
    }

    /** Source whitespace means one space in HTML; only the tags make lines. */
    private fun collapse(s: String): String {
        val out = StringBuilder(s.length)
        var space = false
        for (c in s) {
            if (c.isWhitespace()) {
                if (!space) out.append(' ')
                space = true
            } else {
                out.append(c)
                space = false
            }
        }
        return out.toString()
    }

    /**
     * The scan's output as lines: each trimmed and its spaces squeezed, empty ones
     * dropped, and a blank line only where a paragraph break asked for one — source
     * whitespace between two list items is not a paragraph.
     */
    private fun tidy(s: String): String {
        val out = StringBuilder()
        val line = StringBuilder()
        var pending = 0
        fun flush() {
            val text = line.toString().trim().replace(Regex(" {2,}"), " ")
            line.clear()
            // A cell mark left alone by an empty cell says nothing, nor does an empty item or heading.
            val clean = text.removePrefix("| ").removeSuffix(" |").trim()
            if (clean.isEmpty() || clean == "-" || clean.all { it == '#' }) return
            if (out.isNotEmpty()) out.append(if (pending >= 2) "\n\n" else "\n")
            out.append(clean)
            pending = 0
        }
        for (c in s) {
            when (c) {
                LINE_BREAK -> {
                    flush()
                    pending = maxOf(pending, 1)
                }
                PARAGRAPH -> {
                    flush()
                    pending = 2
                }
                else -> line.append(c)
            }
        }
        flush()
        return out.toString()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®", "trade" to "™",
        "bull" to "•", "middot" to "·", "times" to "×", "laquo" to "«", "raquo" to "»",
        "shy" to "", "zwj" to "", "zwnj" to "",
    )

    /**
     * Character references decoded: the common named ones and every numeric one,
     * decimal or hex. An unknown or broken reference is left as written.
     */
    fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                out.append(c)
                i++
                continue
            }
            val ref = s.substring(i + 1, semi)
            val decoded = if (ref.startsWith("#")) numeric(ref.drop(1)) else NAMED[ref] ?: NAMED[ref.lowercase()]
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun numeric(ref: String): String? {
        val code = if (ref.startsWith("x") || ref.startsWith("X")) ref.drop(1).toIntOrNull(16) else ref.toIntOrNull()
        if (code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
        if (code < 0x10000) return code.toChar().toString()
        val v = code - 0x10000
        return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
}
