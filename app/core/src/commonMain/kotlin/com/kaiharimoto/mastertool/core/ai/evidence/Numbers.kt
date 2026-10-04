package com.kaiharimoto.mastertool.core.ai.evidence

import kotlin.math.abs
import kotlin.math.pow

/**
 * The numbers a claim stakes itself on, and whether a check produced them (1.0.98, kai: "how can we have a reliable way
 * to check the work of an AI?"). Code, not a model, decides: a percentage, a probability, odds "1 in 4" or "3 out of 10"
 * in what Ai writes must appear in what a tool computed — at the precision Ai wrote it — or it is a number nobody
 * checked. Counts, Levels, ATK and turn numbers are not claims of this kind and are left alone.
 */
object Numbers {
    /** One number a claim makes, as a fraction of one (74.2 % and 0.742 are both 0.742), and how precisely it was written. */
    data class Claimed(val written: String, val value: Double, val decimals: Int)

    private val percent = Regex("""(?<![\w.])(\d{1,3}(?:\.\d+)?)\s?%""")
    private val odds = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\s+(?:in|out of)\s+(\d+(?:\.\d+)?)(?![\w.])""", RegexOption.IGNORE_CASE)
    private val decimal = Regex("""(?<![\w.%])(0\.\d+)(?![\w.%])""")
    private val any = Regex("""-?\d+(?:\.\d+)?(?:[eE]-?\d+)?""")

    /** The percentages, odds and probabilities in [text], in the order written. */
    fun claimed(text: String): List<Claimed> {
        val out = mutableListOf<Pair<Int, Claimed>>()
        percent.findAll(text).forEach { m ->
            val n = m.groupValues[1]
            out += m.range.first to Claimed(m.value, n.toDouble() / 100, decimalsOf(n) + 2)
        }
        odds.findAll(text).forEach { m ->
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDouble()
            // "1 in 4" is odds; "4 in 40" too. A denominator under the numerator is not ("5 in 3 copies" never is).
            if (b > 0 && a <= b) out += m.range.first to Claimed(m.value, a / b, 3)
        }
        decimal.findAll(text).forEach { m -> out += m.range.first to Claimed(m.value, m.value.toDouble(), decimalsOf(m.value)) }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** Every number in [source], read as a fraction too where it is a percentage. */
    fun values(source: String): List<Double> = buildList {
        any.findAll(source).forEach { m ->
            val v = m.value.toDoubleOrNull() ?: return@forEach
            add(v)
            // A percentage in the source ("74.2%") and a bare number that reads as one (74.2 → 0.742).
            if (v in 0.0..100.0) add(v / 100)
        }
        // A source that says "1 in 4" or "3/10" gives the fraction itself.
        odds.findAll(source).forEach { m ->
            val b = m.groupValues[2].toDouble()
            if (b > 0) add(m.groupValues[1].toDouble() / b)
        }
        Regex("""(\d+)\s*/\s*(\d+)""").findAll(source).forEach { m ->
            val b = m.groupValues[2].toDouble()
            if (b > 0) add(m.groupValues[1].toDouble() / b)
        }
    }

    /**
     * Whether [claim] is one of [sources]' numbers at the precision it was written: "74%" is 0.742, "74.2%" is 0.7418,
     * never 0.75; a source's number rounded the way the claim was matches, a different number never does.
     */
    fun found(claim: Claimed, sources: List<Double>): Boolean {
        val half = 0.5 * 10.0.pow(-claim.decimals) + 1e-9
        return sources.any { abs(it - claim.value) <= half }
    }

    /** The claims in [text] no source computed: what a check found missing. */
    fun unsourced(text: String, sources: List<String>): List<Claimed> {
        val claims = claimed(text)
        if (claims.isEmpty()) return emptyList()
        val vs = sources.flatMap(::values)
        return claims.filter { !found(it, vs) }
    }

    /** Whether [text] marks itself an estimate — Ai's judgment, not a computed number — in so many words. */
    fun isEstimate(text: String): Boolean {
        val low = text.lowercase()
        return "(estimate)" in low || "estimate:" in low || "my estimate" in low || "roughly estimated" in low
    }

    private fun decimalsOf(n: String): Int = n.substringAfter('.', "").length
}
