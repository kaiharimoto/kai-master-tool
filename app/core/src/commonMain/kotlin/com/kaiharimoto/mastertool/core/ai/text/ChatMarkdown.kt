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
    data class Code(val text: String) : Block
    data class Quote(val inlines: List<Inline>) : Block
    data class Table(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : Block
    data object Rule : Block
}

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val text: String) : Inline
    data class Italic(val text: String) : Inline
    data class Code(val text: String) : Inline
    data class Card(val name: String) : Inline
}

object ChatMarkdown {
    fun parse(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = text.replace("\r\n", "\n").lines()
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
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    blocks += Block.Code(code.joinToString("\n"))
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
                t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("\\|?\\s*:?-{2,}.*")) -> {
                    flush()
                    val header = cells(t)
                    i += 2
                    val rows = mutableListOf<List<List<Inline>>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) rows += cells(lines[i++].trim())
                    blocks += Block.Table(header, rows)
                    continue
                }
                else -> paragraph += t
            }
            i++
        }
        flush()
        return blocks
    }

    private fun cells(row: String): List<List<Inline>> =
        row.trim().trim('|').split('|').map { inline(it.trim()) }

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
