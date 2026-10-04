package com.kaiharimoto.mastertool.core.ai.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A streaming reply read a piece at a time (1.0.92): always the whole parse's blocks, the settled ones kept. */
class MarkdownMemoTest {
    private val samples = listOf(
        "Hello there.\n\nA second paragraph with **bold**, *italic*, `code` and [[Ash Blossom & Joyous Spring]].\n\nThird.",
        "# Heading\n\nSome words\nthat wrap.\n\n- one\n- two\n  continued\n- three\n\n1. first\n2. second\n\n> quoted\n> more\n\n---\n\nEnd.",
        "Before the table.\n\n| Card | Count |\n| --- | ---: |\n| Ash | 3 |\n| Imperm | 2 |\n\nAfter the table.\n\n| a | b |\n|---|---|\n| 1 | 2 |",
        "Code:\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\nMore after the code.\n\n```chart\n{\"type\":\"bar\",\"labels\":[\"a\",\"b\"],\"series\":[{\"name\":\"s\",\"values\":[1,2]}]}\n```\n\nDone.",
        "- a bullet\n  ```\n\nA paragraph after a fence-looking continuation.\n\n```\ncode\n```\n\nTail **open",
        "```cards\n## Starters\n3 Snake-Eye Ash\n\n2 Snake-Eye Oak\n```\n\n```deck\nMain:\n3 Ash\n```\n\nThe end [[Nibi",
        "Line one\n\n\n\nLine two after blanks\n   \n  \nLine three after spaces\n\n|only a pipe line\n\nnot a table",
        "Windows lines\r\n\r\nare read whole\r\n\r\n- a\r\n- b",
        "",
    )

    @Test
    fun everyPrefixParsesAsTheWhole() {
        for (sample in samples) {
            val memo = MarkdownMemo()
            for (n in 0..sample.length) {
                val text = sample.substring(0, n)
                assertEquals(ChatMarkdown.parse(text, streaming = true), memo.parse(text, streaming = true), "streamed prefix $n of ${sample.take(30)}")
            }
            assertEquals(ChatMarkdown.parse(sample), memo.parse(sample, streaming = false), "settled ${sample.take(30)}")
        }
    }

    @Test
    fun inChunksTooAndAfterAJump() {
        val memo = MarkdownMemo()
        val all = samples.joinToString("\n\n")
        var n = 0
        while (n < all.length) {
            n = (n + 7).coerceAtMost(all.length)
            val text = all.substring(0, n)
            assertEquals(ChatMarkdown.parse(text, streaming = true), memo.parse(text, streaming = true))
        }
        // A text that is not the last one grown (a redaction, a new reply) is read from the start.
        val other = "Something else entirely.\n\n- with a list"
        assertEquals(ChatMarkdown.parse(other, streaming = true), memo.parse(other, streaming = true))
        val shorter = all.substring(0, all.length / 2)
        assertEquals(ChatMarkdown.parse(shorter, streaming = true), memo.parse(shorter, streaming = true))
    }

    @Test
    fun settledBlocksAreKeptAsTheSameObjects() {
        val memo = MarkdownMemo()
        val first = memo.parse("One paragraph.\n\nTwo para", streaming = true)
        val second = memo.parse("One paragraph.\n\nTwo paragraphs now", streaming = true)
        assertSame(first[0], second[0])
        assertTrue(second.size == 2)
    }

    @Test
    fun inlineReadsAsItAlwaysDid() {
        val texts = listOf(
            "plain", "**bold** and *it* and _it_ and `c`", "[[Card]] [[ ]] [[open", "a*b*c", "*not closed", "** spaced **",
            "_x_y_", "`a` `b", "*", "**", "[[", "x * y * z", "end*", "*a *b* c*", "", "mixed **[[Card]]** text_",
        )
        for (t in texts) assertEquals(oldInline(t), ChatMarkdown.inline(t), t)
    }

    /** The reading before 1.0.92, which copied the rest of the text at every character. */
    private fun oldInline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        fun push(i: Inline) {
            if (plain.isNotEmpty()) {
                out += Inline.Text(plain.toString())
                plain.clear()
            }
            out += i
        }
        fun close(from: Int, marker: String): Int? = text.indexOf(marker, from).takeIf { it >= from }
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            val mark = when {
                rest.startsWith("[[") -> close(i + 2, "]]")?.let { end -> Inline.Card(text.substring(i + 2, end).trim()) to end + 2 }
                rest.startsWith("**") -> close(i + 2, "**")?.let { end -> Inline.Bold(text.substring(i + 2, end)) to end + 2 }
                rest.startsWith("`") -> close(i + 1, "`")?.let { end -> Inline.Code(text.substring(i + 1, end)) to end + 1 }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 1 && !rest[1].isWhitespace() &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) ->
                    close(i + 1, rest.substring(0, 1))?.takeIf { end -> end > i + 1 && !text[end - 1].isWhitespace() }
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
}
