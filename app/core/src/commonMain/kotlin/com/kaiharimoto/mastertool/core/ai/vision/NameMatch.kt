package com.kaiharimoto.mastertool.core.ai.vision

import kotlin.math.max
import kotlin.math.min

/**
 * A card name as read off a picture, matched to the card it means (1.0.55). A decklist read
 * from a screenshot has names cut short ("Snake-Eye Ash"), split across lines, misread
 * ("Ash Blossom & Joyous Spnng") or in capitals; this scores each known name against it by
 * the edit distance of their normalised forms, with a prefix counting as nearly whole.
 */
object NameMatch {
    data class Scored(val name: String, val score: Double)

    /** Lower case, punctuation as spaces, runs of space as one. */
    fun normal(s: String): String = buildString {
        var space = false
        s.lowercase().forEach { c ->
            if (c.isLetterOrDigit()) {
                append(c)
                space = false
            } else if (!space && isNotEmpty()) {
                append(' ')
                space = true
            }
        }
    }.trim()

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /** How well [read] names [name], 0 to 1. */
    fun score(read: String, name: String): Double {
        val r = normal(read)
        val n = normal(name)
        if (r.isEmpty() || n.isEmpty()) return 0.0
        if (r == n) return 1.0
        // Cut short on the screen: the start of the name, whole words.
        if (r.length >= 6 && n.startsWith(r)) return 0.9 * (0.6 + 0.4 * r.length / n.length)
        val whole = 1.0 - distance(r, n).toDouble() / max(r.length, n.length)
        val head = if (r.length < n.length) 1.0 - distance(r, n.take(r.length)).toDouble() / r.length else 0.0
        return max(whole, head * 0.85 * (0.6 + 0.4 * r.length / n.length))
    }

    /** The best of [names] for [read], most likely first, at most [limit]. */
    fun best(read: String, names: Sequence<String>, limit: Int = 3): List<Scored> {
        val r = normal(read)
        if (r.isEmpty()) return emptyList()
        val first = r.first()
        return names
            // A name far longer or shorter, or sharing no first letter with any word, is no match; skip the arithmetic.
            .filter { n -> val nn = normal(n); nn.isNotEmpty() && (nn.first() == first || nn.split(' ').any { it.startsWith(first) }) && nn.length <= r.length * 4 + 8 }
            .map { Scored(it, score(read, it)) }
            .filter { it.score > 0.35 }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    /** A match this sure is taken without asking. */
    const val SURE = 0.86
}
