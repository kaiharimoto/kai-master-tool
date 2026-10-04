package com.kaiharimoto.mastertool.core.ai.text

/**
 * [ChatMarkdown.parse] for a reply that grows (1.0.92): while it streams only its end changes, so
 * the blocks before the last place a block begins afresh — a line after a blank one, outside any
 * code fence — are kept, and only what follows is read again. The answer is always the whole
 * parse's ([MarkdownMemoTest] holds every prefix of every sample to it); the kept blocks are the
 * same objects frame to frame, so the chat need not draw them again.
 *
 * Text that is not the last text grown at its end is read from the start; so is any with a `\r`.
 */
class MarkdownMemo {
    /** A prefix of the text, ending where a block begins afresh, and its blocks. */
    private var head = ""
    private var headBlocks: List<Block> = emptyList()

    /** Whether [head] leaves a ``` fence open by `settled`'s count (a line starting with one, wherever). */
    private var headFenced = false
    private var streamed = false

    fun parse(text: String, streaming: Boolean): List<Block> {
        if ('\r' in text) {
            reset()
            return ChatMarkdown.parse(text, streaming)
        }
        if (streaming != streamed || !text.startsWith(head)) {
            reset()
            streamed = streaming
        }
        val rest = text.substring(head.length)
        val marks = mutableListOf<IntArray>()
        val tail = ChatMarkdown.blocks(rest, streaming, marks)
        val all = if (headBlocks.isEmpty()) tail else headBlocks + tail
        if (marks.isNotEmpty()) advance(rest, tail, marks)
        return all
    }

    /** Moves [head] on to the last mark that keeps the fences' count even. */
    private fun advance(rest: String, tail: List<Block>, marks: List<IntArray>) {
        val lines = rest.split('\n')
        var fenced = headFenced
        var offset = 0
        var line = 0
        var best: IntArray? = null
        var bestOffset = 0
        var bestFenced = false
        for (m in marks) {
            while (line < m[0]) {
                if (lines[line].trim().startsWith("```")) fenced = !fenced
                offset += lines[line].length + 1
                line++
            }
            if (!fenced) {
                best = m
                bestOffset = offset
                bestFenced = fenced
            }
        }
        val at = best ?: return
        head += rest.substring(0, bestOffset.coerceAtMost(rest.length))
        headBlocks = headBlocks + tail.subList(0, at[1])
        headFenced = bestFenced
    }

    private fun reset() {
        head = ""
        headBlocks = emptyList()
        headFenced = false
    }
}
