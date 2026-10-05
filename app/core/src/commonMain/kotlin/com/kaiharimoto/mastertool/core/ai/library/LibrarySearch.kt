package com.kaiharimoto.mastertool.core.ai.library

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A Library search (§10.3) as typed: words matched whole, the last one as a prefix (the word still being typed), and a
 * `"quoted run of words"` matched contiguous — normalised as `EffectMatching` reads card text: lowercase, any run of
 * punctuation one word break.
 */
class LibraryQuery private constructor(val terms: List<Term>) {
    /** A term: lowercase words joined by single spaces; [prefix] lets its last word be the start of a longer one. */
    data class Term(val text: String, val prefix: Boolean)

    /** The longest term, in characters: how far a window must overlap the last so no match is cut in two. */
    val longest: Int get() = terms.maxOf { it.text.length }

    companion object {
        const val MIN_LENGTH = 2

        /** [q] as a query, or null when it has nothing to look for. */
        fun parse(q: String): LibraryQuery? {
            val terms = ArrayList<Term>()
            var rest = q
            Regex("\"([^\"]*)\"").findAll(q).forEach { m ->
                norm(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { terms += Term(it, prefix = false) }
            }
            rest = rest.replace(Regex("\"[^\"]*\"?"), " ")
            val words = rest.split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase() }.filter { it.isNotEmpty() }
            val trailing = !q.trimEnd().endsWith("\"") && q.isNotEmpty() && q.last().isLetterOrDigit()
            words.forEachIndexed { i, w -> terms += Term(w, prefix = trailing && i == words.lastIndex) }
            if (terms.isEmpty() || terms.sumOf { it.text.length } < MIN_LENGTH) return null
            return LibraryQuery(terms.distinct())
        }

        private fun norm(s: String): String = s.split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase() }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/** One hit: the document, where its line starts, the line as shown (cut round the match), and each term's match in it. */
data class LibraryHit(val doc: LibraryDoc, val offset: Long, val line: String, val ranges: List<IntRange>)

/**
 * Searching the Library by walking, not by an index (§10.3, `LibrarySearchTest`, `LibraryScaleTest`) — the lesson of
 * `EffectMatching`: an index of every word costs more than it saves. Each document is read in [WINDOW]-character windows,
 * line by line (a line longer than a window is searched in pieces overlapping by the query's longest term), a hit being a
 * line that holds every term. Nothing is copied but the lines that hit. Cancellable between windows: the next keystroke
 * cancels the search under way. At most [limit] hits (500, then *More*).
 */
object LibrarySearch {
    const val WINDOW = 64 * 1024
    const val MAX_HITS = 500

    /** A hit's line as shown: at most this many characters round its first match. */
    const val SHOWN = 240

    /** Searches [docs] in order, calling [onHit] as each hit is found; the number of hits. Cancelled with its coroutine. */
    suspend fun search(files: LibraryFiles, docs: List<LibraryDoc>, query: LibraryQuery, limit: Int = MAX_HITS, onHit: (LibraryHit) -> Unit): Int {
        val ctx = currentCoroutineContext()
        return walk(files, docs, query, limit, { ctx.ensureActive(); false }, onHit)
    }

    /** The same, blocking: [cancelled] is asked between windows. */
    fun walk(files: LibraryFiles, docs: List<LibraryDoc>, query: LibraryQuery, limit: Int = MAX_HITS, cancelled: () -> Boolean = { false }, onHit: (LibraryHit) -> Unit): Int {
        var hits = 0
        val buf = CharArray(WINDOW)
        for (doc in docs) {
            if (hits >= limit || cancelled()) break
            val r = files.reader(doc.path) ?: continue
            try {
                hits += walkOne(doc, r, buf, query, limit - hits, cancelled, onHit)
            } finally {
                r.close()
            }
        }
        return hits
    }

    private fun walkOne(doc: LibraryDoc, r: LibraryReader, buf: CharArray, q: LibraryQuery, limit: Int, cancelled: () -> Boolean, onHit: (LibraryHit) -> Unit): Int {
        var hits = 0
        val carry = StringBuilder()
        var carryAt = 0L
        var at = 0L
        val overlap = q.longest * 2 + 16
        var lines = 0
        fun test(text: CharSequence, from: Int, to: Int, offset: Long, reportBefore: Int): Boolean {
            // Asked every few thousand lines and after every hit too, not only between windows: a window of short lines
            // that all hit would otherwise run on past the keystroke that cancelled it.
            if ((++lines and 0xFFF) == 0 && cancelled()) return false
            val ranges = matchLine(text, from, to, q) ?: return true
            if (ranges.first().first - from >= reportBefore) return true
            onHit(hit(doc, offset, text.subSequence(from, to).toString(), ranges.map { (it.first - from)..(it.last - from) }))
            hits++
            return hits < limit && !cancelled()
        }
        while (true) {
            if (cancelled()) return hits
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            val view = Chars(buf)
            var s = 0
            while (s < n) {
                var e = s
                while (e < n && buf[e] != '\n') e++
                if (e < n) {
                    // A whole line: the carried start of it, then this.
                    if (carry.isEmpty()) {
                        if (!test(view, s, e, at + s, Int.MAX_VALUE)) return hits
                    } else {
                        carry.appendRange(buf, s, e)
                        if (!test(carry, 0, carry.length, carryAt, Int.MAX_VALUE)) return hits
                        carry.clear()
                    }
                    s = e + 1
                } else {
                    if (carry.isEmpty()) carryAt = at + s
                    carry.appendRange(buf, s, n)
                    if (carry.length > WINDOW) {
                        // A line longer than a window: search what is held, keep its tail so a match across the cut is found once.
                        val keep = minOf(overlap, carry.length)
                        if (!test(carry, 0, carry.length, carryAt, carry.length - keep)) return hits
                        carryAt += carry.length - keep
                        carry.deleteRange(0, carry.length - keep)
                    }
                    s = n
                }
            }
            at += n
        }
        if (carry.isNotEmpty()) test(carry, 0, carry.length, carryAt, Int.MAX_VALUE)
        return hits
    }

    private fun hit(doc: LibraryDoc, offset: Long, line: String, ranges: List<IntRange>): LibraryHit {
        if (line.length <= SHOWN) return LibraryHit(doc, offset, line, ranges)
        val first = ranges.minOf { it.first }
        val start = (first - SHOWN / 3).coerceIn(0, line.length - SHOWN)
        val cut = line.substring(start, start + SHOWN)
        val shifted = ranges.map { (it.first - start)..(it.last - start) }.filter { it.first >= 0 && it.last < cut.length }
        return LibraryHit(doc, offset, cut, shifted)
    }

    /** Each term's first match in [text] between [from] and [to], or null when a term is not there. */
    fun matchLine(text: CharSequence, from: Int, to: Int, q: LibraryQuery): List<IntRange>? {
        val out = ArrayList<IntRange>(q.terms.size)
        for (t in q.terms) out += find(text, from, to, t.text, t.prefix) ?: return null
        return out
    }

    /** [needle] (normalised words) as a run of whole words in [text] between [from] and [to]: where, or null. */
    fun find(text: CharSequence, from: Int, to: Int, needle: String, prefix: Boolean): IntRange? {
        if (needle.isEmpty()) return null
        val first = needle[0]
        var s = from
        while (s < to) {
            val c = text[s]
            if (!c.isLetterOrDigit() || (s > from && text[s - 1].isLetterOrDigit())) {
                s++
                continue
            }
            if (c.lowercaseChar() == first) {
                val end = matchAt(text, s, to, needle, prefix)
                if (end >= 0) return s until end
            }
            while (s < to && text[s].isLetterOrDigit()) s++
        }
        return null
    }

    /** The end of [needle] matched at [from], or -1. */
    private fun matchAt(text: CharSequence, from: Int, to: Int, needle: String, prefix: Boolean): Int {
        var t = from
        var n = 0
        while (n < needle.length) {
            val wanted = needle[n]
            if (wanted == ' ') {
                if (t >= to || text[t].isLetterOrDigit()) return -1
                while (t < to && !text[t].isLetterOrDigit()) t++
                if (t >= to) return -1
                n++
            } else {
                if (t >= to) return -1
                val ch = text[t]
                if (!ch.isLetterOrDigit() || ch.lowercaseChar() != wanted) return -1
                t++
                n++
            }
        }
        if (!prefix && t < to && text[t].isLetterOrDigit()) return -1
        return t
    }

    /** A window of the buffer as text, without a copy. */
    private class Chars(private val a: CharArray) : CharSequence {
        override val length: Int get() = a.size
        override fun get(index: Int): Char = a[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = a.concatToString(startIndex, endIndex)
    }
}
