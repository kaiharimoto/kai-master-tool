package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.ai.vision.NameMatch
import kotlin.math.max

/**
 * How a card name sounds (1.0.87): a small Metaphone-like key, so what a transcriber heard — "zoos", "droll and
 * lockbird", "ash blossom" — finds the card it sounds like when its spelling does not: Zeus, Droll & Lock Bird, Ash
 * Blossom & Joyous Spring. Only ever run over the names in reach, after the spelling found nothing.
 *
 * The key keeps a word's first vowel (as `A`) and its consonants by sound — `c` as `K` or `S`, `ph` as `F`, `gh`
 * silent, `z` as `S`, `x` as `KS`, doubled letters once — and writes a lone number as its word, so "1 for 1" sounds
 * like One for One. "and", "&", "the" and "of" say nothing about a card and are left out.
 */
object Phonetic {

    private val QUIET = setOf("and", "the", "of", "n")
    private val DIGITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    /** The words of [text], each as its key, the quiet ones left out. */
    fun wordKeys(text: String): List<String> =
        NameMatch.normal(text.replace("&", " and ")).split(' ')
            .filter { it.isNotEmpty() && it !in QUIET }
            .map { w -> w.toIntOrNull()?.let { DIGITS.getOrNull(it) } ?: w }
            .map(::word)
            .filter { it.isNotEmpty() }

    /** The key of the whole of [text]: its words' keys run together. */
    fun key(text: String): String = wordKeys(text).joinToString("")

    /** One word's key. */
    fun word(raw: String): String {
        var w = raw.lowercase().filter { it.isLetterOrDigit() }
        if (w.isEmpty()) return ""
        if (w.any { it.isDigit() }) return w.uppercase()
        // Silent first letters.
        when {
            w.startsWith("kn") || w.startsWith("gn") || w.startsWith("pn") || w.startsWith("wr") || w.startsWith("ae") -> w = w.drop(1)
            w.startsWith("wh") -> w = "w" + w.drop(2)
            w.startsWith("x") -> w = "s" + w.drop(1)
        }
        // Doubled letters once (but "cc" can be two sounds, as in "accent": kept).
        w = buildString { w.forEachIndexed { i, c -> if (i == 0 || c != w[i - 1] || c == 'c') append(c) } }
        val out = StringBuilder()
        fun at(i: Int) = w.getOrNull(i)
        fun vowel(c: Char?) = c != null && c in VOWELS
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val next = at(i + 1)
            when (c) {
                'a', 'e', 'i', 'o', 'u' -> if (i == 0) out.append('A')
                'b' -> if (!(i == w.length - 1 && at(i - 1) == 'm')) out.append('B')
                'c' -> when {
                    next == 'i' && at(i + 2) == 'a' -> out.append('X')
                    next == 'h' -> { out.append(if (at(i - 1) == 's') 'K' else 'X'); i++ }
                    next == 'i' || next == 'e' || next == 'y' -> if (at(i - 1) != 's') out.append('S')
                    next == 'k' -> { out.append('K'); i++ }
                    else -> out.append('K')
                }
                'd' -> if (next == 'g' && (at(i + 2) == 'e' || at(i + 2) == 'i' || at(i + 2) == 'y')) { out.append('J'); i++ } else out.append('T')
                'g' -> when {
                    next == 'h' && !vowel(at(i + 2)) -> i++
                    next == 'n' && (i + 2 == w.length || (at(i + 2) == 'e' && at(i + 3) == 'd')) -> Unit
                    next == 'i' || next == 'e' || next == 'y' -> out.append('J')
                    else -> out.append('K')
                }
                'h' -> if (vowel(next) && at(i - 1) !in setOf('c', 's', 'p', 't', 'g')) out.append('H')
                'k' -> out.append('K')
                'p' -> if (next == 'h') { out.append('F'); i++ } else out.append('P')
                'q' -> out.append('K')
                's' -> when {
                    next == 'h' -> { out.append('X'); i++ }
                    next == 'i' && (at(i + 2) == 'o' || at(i + 2) == 'a') -> out.append('X')
                    else -> out.append('S')
                }
                't' -> when {
                    next == 'i' && (at(i + 2) == 'o' || at(i + 2) == 'a') -> out.append('X')
                    next == 'h' -> { out.append('0'); i++ }
                    next == 'c' && at(i + 2) == 'h' -> Unit
                    else -> out.append('T')
                }
                'v' -> out.append('F')
                'w', 'y' -> if (vowel(next)) out.append(c.uppercaseChar())
                'x' -> out.append("KS")
                'z' -> out.append('S')
                else -> out.append(c.uppercaseChar())
            }
            i++
        }
        return out.toString()
    }

    /**
     * How surely [heard] sounds like [name], 0 to 1: the whole name 1; its first words 0.92; any run of its words
     * 0.88 (Zeus out of "Divine Arsenal AA-ZEUS - Sky Thunder"); otherwise the keys' edit distance, scaled down so
     * only a near miss is ever sure.
     */
    fun score(heard: String, name: String): Double {
        val h = wordKeys(heard)
        val n = wordKeys(name)
        if (h.isEmpty() || n.isEmpty()) return 0.0
        val hk = h.joinToString("")
        val nk = n.joinToString("")
        if (hk == nk) return 1.0
        if (hk.length >= 2) {
            // A run of the name's words, sounded together.
            for (from in n.indices) {
                val run = StringBuilder()
                for (to in from until n.size) {
                    run.append(n[to])
                    if (run.length > hk.length) break
                    if (run.toString() == hk) return if (from == 0) 0.92 else 0.88
                }
            }
        }
        val whole = 1.0 - NameMatch.distance(hk, nk).toDouble() / max(hk.length, nk.length)
        // Near a run of the name's words ("blossum joy" near Blossom Joyous): a choice to offer, never sure.
        var part = 0.0
        for (from in n.indices) {
            val run = StringBuilder()
            for (to in from until n.size) {
                run.append(n[to])
                val r = run.toString()
                part = max(part, 1.0 - NameMatch.distance(hk, r).toDouble() / max(hk.length, r.length))
                if (r.length > hk.length * 2) break
            }
        }
        return max(whole * 0.9, part * 0.85)
    }
}
