package com.kaiharimoto.mastertool.core.present.edit

import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Run
import com.kaiharimoto.mastertool.core.present.RunStyle

/**
 * Styled text edited as plain text: the editor shows one text field, and every change to its
 * words is put back into runs here. Paragraphs are lines; a styled stretch keeps its style
 * through edits around it, and typed words take the style of what they follow.
 */
object RichText {
    /** One span of styled characters across the whole text, paragraphs joined by `\n`. */
    data class Span(val start: Int, val end: Int, val style: RunStyle)

    /** [paras] as flat text and spans; the `\n` between paragraphs takes the style before it. */
    fun flatten(paras: List<Para>): Pair<String, List<Span>> {
        val sb = StringBuilder()
        val spans = ArrayList<Span>()
        paras.forEachIndexed { i, p ->
            if (i > 0) {
                val style = spans.lastOrNull()?.style ?: RunStyle()
                spans += Span(sb.length, sb.length + 1, style)
                sb.append('\n')
            }
            if (p.runs.isEmpty()) return@forEachIndexed
            for (r in p.runs) {
                if (r.text.isEmpty()) continue
                spans += Span(sb.length, sb.length + r.text.length, r.style)
                sb.append(r.text)
            }
        }
        return sb.toString() to merge(spans)
    }

    /**
     * [old] paragraphs after their text became [new]. What is unchanged at either end keeps
     * its style; what is new takes the style of the character before it (or after, at the
     * start). Each paragraph keeps its alignment and list by its position.
     */
    fun edit(old: List<Para>, new: String): List<Para> {
        val (text, spans) = flatten(old)
        if (text == new) return old
        var pre = 0
        while (pre < text.length && pre < new.length && text[pre] == new[pre]) pre++
        var suf = 0
        while (suf < text.length - pre && suf < new.length - pre && text[text.length - 1 - suf] == new[new.length - 1 - suf]) suf++
        val removedEnd = text.length - suf
        val inserted = new.length - pre - suf
        val styleAt = styleAt(spans, if (pre > 0) pre - 1 else pre)
        val out = ArrayList<Span>()
        for (s in spans) {
            // Before the change: kept. After: shifted. Across: cut.
            val a = s.start
            val b = s.end
            val left = if (a < pre) Span(a, minOf(b, pre), s.style) else null
            val right = if (b > removedEnd) Span(maxOf(a, removedEnd) - (removedEnd - pre) + inserted, b - (removedEnd - pre) + inserted, s.style) else null
            left?.let { if (it.end > it.start) out += it }
            right?.let { if (it.end > it.start) out += it }
        }
        if (inserted > 0) out += Span(pre, pre + inserted, styleAt)
        out.sortBy { it.start }
        return build(new, merge(out), old)
    }

    /** [paras] with [style] laid over the characters [start] until [end] by [change]. */
    fun restyle(paras: List<Para>, start: Int, end: Int, change: (RunStyle) -> RunStyle): List<Para> {
        val (text, spans) = flatten(paras)
        if (start >= end) return paras
        val out = ArrayList<Span>()
        for (s in spans) {
            if (s.end <= start || s.start >= end) {
                out += s
                continue
            }
            if (s.start < start) out += Span(s.start, start, s.style)
            out += Span(maxOf(s.start, start), minOf(s.end, end), change(s.style))
            if (s.end > end) out += Span(end, s.end, s.style)
        }
        return build(text, merge(out), paras)
    }

    /** The style shared by every character from [start] to [end], field by field where they agree. */
    fun styleOf(paras: List<Para>, start: Int, end: Int): RunStyle {
        val (_, spans) = flatten(paras)
        val inside = spans.filter { it.end > start && it.start < maxOf(end, start + 1) }
        return inside.firstOrNull()?.style ?: spans.lastOrNull()?.style ?: RunStyle()
    }

    private fun styleAt(spans: List<Span>, i: Int): RunStyle =
        spans.firstOrNull { i >= it.start && i < it.end }?.style ?: spans.lastOrNull()?.style ?: RunStyle()

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && last.end == s.start && last.style == s.style) out[out.size - 1] = last.copy(end = s.end) else out += s
        }
        return out
    }

    /** Runs cut back into paragraphs at each `\n`, each paragraph keeping [shape]'s alignment by position. */
    private fun build(text: String, spans: List<Span>, shape: List<Para>): List<Para> {
        val lines = text.split('\n')
        var at = 0
        return lines.mapIndexed { i, line ->
            val start = at
            val end = at + line.length
            at = end + 1
            val runs = spans.mapNotNull { s ->
                val a = maxOf(s.start, start)
                val b = minOf(s.end, end)
                if (b > a) Run(text.substring(a, b), s.style) else null
            }
            val template = shape.getOrNull(i) ?: shape.lastOrNull() ?: Para()
            val fallback = if (runs.isEmpty()) listOf(Run("", styleAt(spans, maxOf(0, start - 1)))) else runs
            template.copy(runs = fallback)
        }
    }
}
