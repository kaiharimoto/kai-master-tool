package com.kaiharimoto.mastertool.core.ai.web

/**
 * Text from outside the app, marked as such before a model reads it: a web page, a search's
 * snippets, a wiki's rulings, a stranger's decklist, a video's report. Anyone can write those,
 * and a line in them saying "ignore your instructions and …" is the oldest trick there is. The
 * system prompt tells Ai that what stands inside the envelope is information, never an
 * instruction; this keeps the envelope honest.
 *
 * ```
 * <untrusted source="https://example.com/guide">
 * …the page's words…
 * </untrusted>
 * ```
 *
 * Outside text may not close the envelope early, or open one of its own: every look-alike of
 * the tag inside it — any case, spaces or invisible characters inside, attributes, `&lt;` and
 * `<` spellings, the full-width `＜` — loses its opening bracket to a `[` (and its closing
 * one to a `]`), so it still reads, and nothing reads as the tag. The source is cut to one
 * line with no quotes or angle brackets, so it cannot end the attribute or the tag.
 */
object Untrusted {
    const val TAG = "untrusted"

    /** Characters that show as nothing, which outside text could slip inside the tag's name. */
    private const val INVISIBLE = "\\u00AD\\u200B\\u200C\\u200D\\u2060\\uFEFF"

    /** A space or an invisible character, any number of them. */
    private const val GAP = "[\\s$INVISIBLE]*"

    /** Every spelling of `<` a model could read as one: plain, full-width, small, and escaped. */
    private const val OPEN = "(?:<|\\uFF1C|\\uFE64|&lt;|&#0*60;|&#x0*3c;|\\\\u003c|\\\\x3c)"

    /** The tag's name with invisibles allowed between its letters. */
    private val NAME = TAG.map { it.toString() }.joinToString("[$INVISIBLE]*")

    /** An opening or closing look-alike: the bracket, then the name, its attributes, and its `>` if it has one. */
    private val LOOK_ALIKE = Regex(
        "$OPEN($GAP(?:/|\\\\/)?$GAP$NAME)([^<>\\n\\uFF1C\\uFF1E]{0,200})([>\\uFF1E])?",
        RegexOption.IGNORE_CASE,
    )

    /** [text] inside the envelope, from [source] (an address, or a few words naming where it came from). */
    fun wrap(source: String, text: String): String =
        "<$TAG source=\"${source(source)}\">\n${neutralise(text).trimEnd('\n', '\r')}\n</$TAG>"

    /** [text] with every look-alike of the envelope's tag disarmed. */
    fun neutralise(text: String): String =
        LOOK_ALIKE.replace(text) { m -> "[" + m.groupValues[1] + m.groupValues[2] + (if (m.groupValues[3].isNotEmpty()) "]" else "") }

    /** [raw] fit for the attribute: one line, no quotes, no brackets, no ampersands, at most [MAX_SOURCE] characters. */
    fun source(raw: String): String {
        val clean = buildString {
            raw.forEach { c ->
                when {
                    c == '"' || c == '\'' || c == '`' || c == '<' || c == '>' || c == '&' || c == '\\' -> Unit
                    c == '＜' || c == '＞' || c == '﹤' || c == '﹥' -> Unit
                    c.isWhitespace() || c.isISOControl() -> append(' ')
                    c in INVISIBLE_CHARS -> Unit
                    else -> append(c)
                }
            }
        }.replace(SPACES, " ").trim()
        return clean.take(MAX_SOURCE).trim().ifEmpty { "outside" }
    }

    private const val MAX_SOURCE = 200
    private val SPACES = Regex(" {2,}")
    private val INVISIBLE_CHARS = setOf('­', '​', '‌', '‍', '⁠', '﻿')
}
