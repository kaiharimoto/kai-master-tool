package com.kaiharimoto.mastertool.core.ai.library

/**
 * A document drawn lazily (§10.3, `LibrarySectionsTest`): split at its headings, then into blocks of at most [BLOCK]
 * characters at paragraph breaks (else at line breaks, else cut), each keyed by its [Block.offset] in the document. The
 * reader is a lazy column of blocks, never one `Text`; the contents column lists the [Section]s.
 */
object LibrarySections {
    const val BLOCK = 4 * 1024

    /** A heading and the blocks under it; the text before the first heading is a section with [level] 0 and no title. */
    data class Section(val title: String, val level: Int, val offset: Int, val blocks: List<Block>)

    /** One block: where it starts in the document, and its text (at most [BLOCK] characters). */
    data class Block(val offset: Int, val text: String)

    fun split(text: String): List<Section> {
        val out = ArrayList<Section>()
        var title = ""
        var level = 0
        var start = 0
        var bodyStart = 0
        var i = 0
        fun close(end: Int) {
            val blocks = blocks(text, bodyStart, end)
            if (title.isNotEmpty() || blocks.isNotEmpty()) out += Section(title, level, start, blocks)
        }
        while (i <= text.length) {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val h = heading(text, i, lineEnd)
            if (h != null) {
                close(i)
                title = h.second
                level = h.first
                start = i
                bodyStart = minOf(text.length, lineEnd + 1)
            }
            if (lineEnd >= text.length) break
            i = lineEnd + 1
        }
        close(text.length)
        return out
    }

    /** A markdown heading on the line [from]..[to]: its level and its words, or null. */
    private fun heading(text: String, from: Int, to: Int): Pair<Int, String>? {
        var n = 0
        while (from + n < to && text[from + n] == '#') n++
        if (n == 0 || n > 6 || from + n >= to || text[from + n] != ' ') return null
        return n to text.substring(from + n + 1, to).trim().trimEnd('#').trim()
    }

    /** [text] from [from] to [to] in blocks of at most [BLOCK], broken at a blank line, else a line break, else cut. */
    fun blocks(text: String, from: Int, to: Int): List<Block> {
        val out = ArrayList<Block>()
        var s = from
        while (s < to) {
            // Skip blank lines between blocks: they are the breaks, not content.
            while (s < to && (text[s] == '\n' || text[s] == '\r')) s++
            if (s >= to) break
            var e = minOf(to, s + BLOCK)
            if (e < to) {
                val para = text.lastIndexOf("\n\n", e - 1).takeIf { it > s }
                val line = text.lastIndexOf('\n', e - 1).takeIf { it > s }
                e = when {
                    para != null -> para + 1
                    line != null -> line + 1
                    else -> e
                }
            }
            val chunk = text.substring(s, e).trimEnd('\n', '\r')
            if (chunk.isNotEmpty()) out += Block(s, chunk)
            s = e
        }
        return out
    }
}
