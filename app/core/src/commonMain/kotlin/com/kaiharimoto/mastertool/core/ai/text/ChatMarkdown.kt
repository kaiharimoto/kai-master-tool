package com.kaiharimoto.mastertool.core.ai.text

/**
 * The markdown Ai writes, read into blocks the chat draws with the app's own type —
 * enough of it to read well (headings, lists, emphasis, code, rules, quotes) and no
 * more. Card names Ai wraps in double brackets, `[[Ash Blossom & Joyous Spring]]`,
 * become [Inline.Card]: a chip the person can hover or open.
 *
 * Tolerant by design: a reply is drawn while it streams, so an unclosed `**` or
 * code fence is text until it closes.
 */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class Bullets(val items: List<List<Inline>>) : Block
    data class Numbered(val items: List<List<Inline>>, val start: Int = 1) : Block
    data class Code(val text: String, val lang: String = "") : Block
    data class Quote(val inlines: List<Inline>) : Block
    data class Table(
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        /** Per column, from the rule line's colons: left unless it says otherwise. */
        val align: List<Align> = emptyList(),
    ) : Block
    data object Rule : Block

    /** A ```chart block that read as a chart ([ChatChart]); one that did not is [Code]. */
    data class Chart(val chart: ChatChart.Chart) : Block

    /** A ```cards block: card names, each with its copies, drawn as art (1.0.46). */
    data class Cards(val lines: List<CardLine>) : Block

    /** A block still being written (a chart whose fence is not closed yet): a quiet line, not raw JSON. */
    data class Pending(val what: String) : Block
}

enum class Align { LEFT, CENTER, RIGHT }

/** One line of a ```cards block: `3 Ash Blossom & Joyous Spring`, `Ash Blossom x2`, or a bare name. */
data class CardLine(val count: Int, val name: String)

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val text: String) : Inline
    data class Italic(val text: String) : Inline
    data class Code(val text: String) : Inline
    data class Card(val name: String) : Inline
}

object ChatMarkdown {
    /**
     * [streaming]: the reply is still arriving, so what is half-written at its end is
     * held back rather than drawn raw (1.0.46) — an unclosed `**`, `` ` `` or `[[`, a table
     * whose rule line has not come yet, a chart whose fence is still open.
     */
    fun parse(text: String, streaming: Boolean = false): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = (if (streaming) settled(text) else text).replace("\r\n", "\n").lines()
        var i = 0
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inline(paragraph.joinToString(" ")))
                paragraph.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim().lowercase()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    val closed = i < lines.size
                    val body = code.joinToString("\n")
                    blocks += when {
                        streaming && !closed && lang in DRAWN -> Block.Pending(if (lang == "chart") "Drawing a chart" else "Laying out cards")
                        lang == "chart" -> ChatChart.parse(body).fold({ Block.Chart(it) }, { Block.Code(body, lang) })
                        lang == "cards" -> cardLines(body).takeIf { it.isNotEmpty() }?.let { Block.Cards(it) } ?: Block.Code(body, lang)
                        else -> Block.Code(body, lang)
                    }
                }
                t.isEmpty() -> flush()
                t.matches(Regex("#{1,6} .*")) -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, inline(t.drop(level).trim()))
                }
                t == "---" || t == "***" || t == "___" -> {
                    flush()
                    blocks += Block.Rule
                }
                t.startsWith("> ") || t == ">" -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quoted += lines[i++].trim().removePrefix(">").trim()
                    blocks += Block.Quote(inline(quoted.joinToString(" ")))
                    continue
                }
                bullet(t) != null -> {
                    flush()
                    val items = mutableListOf<List<Inline>>()
                    while (i < lines.size) {
                        val b = bullet(lines[i].trim()) ?: break
                        var item = b
                        i++
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() && bullet(lines[i].trim()) == null) item += " " + lines[i++].trim()
                        items += inline(item)
                    }
                    blocks += Block.Bullets(items)
                    continue
                }
                numbered(t) != null -> {
                    flush()
                    val start = numbered(t)!!.first
                    val items = mutableListOf<List<Inline>>()
                    while (i < lines.size) {
                        val n = numbered(lines[i].trim()) ?: break
                        var item = n.second
                        i++
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() && numbered(lines[i].trim()) == null) item += " " + lines[i++].trim()
                        items += inline(item)
                    }
                    blocks += Block.Numbered(items, start)
                    continue
                }
                '|' in t && i + 1 < lines.size && isRule(lines[i + 1].trim()) -> {
                    flush()
                    val header = cells(t)
                    val align = split(lines[i + 1].trim()).map { c ->
                        val x = c.trim()
                        when {
                            x.startsWith(":") && x.endsWith(":") -> Align.CENTER
                            x.endsWith(":") -> Align.RIGHT
                            else -> Align.LEFT
                        }
                    }
                    i += 2
                    val rows = mutableListOf<List<List<Inline>>>()
                    while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) rows += cells(lines[i++].trim())
                    blocks += Block.Table(header, rows, align)
                    continue
                }
                else -> paragraph += t
            }
            i++
        }
        flush()
        return blocks
    }

    private val DRAWN = setOf("chart", "cards")

    /** `| --- | :---: | ---: |`, with or without the outer pipes. */
    private fun isRule(t: String): Boolean =
        '-' in t && t.matches(Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$"))

    private fun cells(row: String): List<List<Inline>> = split(row).map { inline(it.trim()) }

    /**
     * A row's cells: split on `|`, but not on one inside `code` or written `\|`, so a
     * cell can show a pipe. The outer pipes are optional.
     */
    private fun split(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.drop(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var code = false
        var i = 0
        while (i < r.length) {
            val c = r[i]
            when {
                c == '\\' && i + 1 < r.length && r[i + 1] == '|' -> { cell.append('|'); i++ }
                c == '`' -> { code = !code; cell.append(c) }
                c == '|' && !code -> { out += cell.toString(); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    /** A ```cards block's lines: `3 Name`, `3x Name`, `Name x2`, `[[Name]]`, or a bare name. */
    fun cardLines(body: String): List<CardLine> = body.lines().mapNotNull { raw ->
        val t = raw.trim().removePrefix("- ").removePrefix("* ").trim()
        if (t.isEmpty()) return@mapNotNull null
        val lead = Regex("^(\\d{1,2})\\s*[x×]?\\s+(.+)$").find(t)
        val tail = Regex("^(.+?)\\s*[x×]\\s*(\\d{1,2})$").find(t)
        val (count, name) = when {
            lead != null -> lead.groupValues[1].toInt() to lead.groupValues[2]
            tail != null -> tail.groupValues[2].toInt() to tail.groupValues[1]
            else -> 1 to t
        }
        val clean = name.trim().removePrefix("[[").removeSuffix("]]").trim()
        if (clean.isEmpty()) null else CardLine(count.coerceIn(1, 3), clean)
    }

    /**
     * The part of a reply still arriving that is safe to draw: the last line's unclosed
     * `[[`, `**` or `` ` `` held back, and a table that has not got its rule line yet —
     * they come out whole a moment later instead of flickering raw.
     */
    fun settled(text: String): String {
        val lines = text.replace("\r\n", "\n").split("\n").toMutableList()
        // A table's lines at the end, until its rule line is in, are held back whole.
        var end = lines.size
        while (end > 0 && lines[end - 1].trim().startsWith("|")) end--
        val tail = lines.subList(end, lines.size)
        val fence = lines.take(end).count { it.trim().startsWith("```") } % 2 == 1
        if (!fence && tail.isNotEmpty() && tail.none { isRule(it.trim()) }) {
            while (lines.size > end) lines.removeAt(lines.size - 1)
        }
        if (fence || lines.isEmpty()) return lines.joinToString("\n")
        var last = lines.removeAt(lines.size - 1)
        fun cut(open: String, close: String) {
            val at = last.lastIndexOf(open)
            if (at >= 0 && last.indexOf(close, at + open.length) < 0) last = last.substring(0, at)
        }
        cut("[[", "]]")
        if (Regex("\\*\\*").findAll(last).count() % 2 == 1) last = last.substring(0, last.lastIndexOf("**"))
        if (last.count { it == '`' } % 2 == 1) last = last.substring(0, last.lastIndexOf('`'))
        lines += last
        return lines.joinToString("\n")
    }

    private fun bullet(t: String): String? =
        if ((t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")) && t.length > 2) t.drop(2).trim() else null

    private fun numbered(t: String): Pair<Int, String>? {
        val m = Regex("^(\\d{1,3})[.)] (.*)").find(t) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2]
    }

    /** Inline marks: `[[card]]`, `**bold**`, `*italic*`/`_italic_`, `` `code` ``. */
    fun inline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        fun push(i: Inline) {
            if (plain.isNotEmpty()) {
                out += Inline.Text(plain.toString())
                plain.clear()
            }
            out += i
        }
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val mark = when {
                rest.startsWith("[[") -> close(text, i + 2, "]]")?.let { end -> Inline.Card(text.substring(i + 2, end).trim()) to end + 2 }
                rest.startsWith("**") -> close(text, i + 2, "**")?.let { end -> Inline.Bold(text.substring(i + 2, end)) to end + 2 }
                rest.startsWith("`") -> close(text, i + 1, "`")?.let { end -> Inline.Code(text.substring(i + 1, end)) to end + 1 }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 1 && !rest[1].isWhitespace() &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) ->
                    close(text, i + 1, rest.substring(0, 1))?.takeIf { end -> end > i + 1 && !text[end - 1].isWhitespace() }
                        ?.let { end -> Inline.Italic(text.substring(i + 1, end)) to end + 1 }
                else -> null
            }
            if (mark != null && (mark.first !is Inline.Card || (mark.first as Inline.Card).name.isNotEmpty())) {
                push(mark.first)
                i = mark.second
            } else {
                plain.append(text[i])
                i++
            }
        }
        if (plain.isNotEmpty()) out += Inline.Text(plain.toString())
        return out
    }

    private fun close(text: String, from: Int, marker: String): Int? =
        text.indexOf(marker, from).takeIf { it >= from }

    /** The card names a reply mentions, in order, once each. */
    fun cards(text: String): List<String> =
        Regex("\\[\\[([^\\]]+)]]").findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.distinct().toList()

    /** A reply as plain words: marks gone, card brackets gone (for a toast or a search). */
    fun plain(text: String): String =
        text.replace(Regex("\\[\\[([^\\]]+)]]"), "$1").replace("**", "").replace("`", "")
}
