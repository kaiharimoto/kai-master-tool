package com.kaiharimoto.mastertool.core.text

import kotlin.math.roundToInt

/**
 * Numbers and small words the way the family writes them, in one place so each feature stops writing its own
 * (the 1.1.2 design review, finding 12): `57%` with no space and no decimal, hints set in the right voice, and Ai
 * called by the name the person gave it.
 */
object Words {
    /** A share as the family writes one (kit §9): `57%`, `<1%`, `>99%`, `0%`, `100%`; `--` when there is none. */
    fun percent(p: Double): String = when {
        p.isNaN() -> "--"
        p <= 0.0 -> "0%"
        p >= 1.0 -> "100%"
        p < 0.01 -> "<1%"
        p > 0.99 -> ">99%"
        else -> "${(p * 100).roundToInt()}%"
    }

    /**
     * Whether a hint is prose — "100 unless the event sets another", "At the limit" — rather than data: a key
     * (`Ctrl Shift F`, `Enter · Shift Enter side`), a count, a date. Prose is set in the sans; data in mono (kit §3:
     * mono is for data, never prose). Two or more plain lowercase words make it prose: keys are capitalised, and a
     * pattern such as `yyyy-mm-dd` is one token.
     */
    fun isProse(hint: String): Boolean =
        hint.split(' ').count { word -> word.trim(',', '.', ';', ':', '…').let { it.length >= 2 && it.all { ch -> ch in 'a'..'z' || ch == '\'' } } } >= 2

    /**
     * [text] with the assistant called by [name] (finding 14): "How far Ai is trusted" reads "How far Kiri is trusted"
     * when it was renamed. The product noun "Ai World" keeps its word.
     */
    fun named(text: String, name: String): String {
        val n = name.trim()
        if (n.isEmpty() || n == AI) return text
        return AI_WORD.replace(text) { m ->
            val after = text.substring(m.range.last + 1)
            if (after.startsWith(" World")) m.value else n
        }
    }

    private const val AI = "Ai"
    private val AI_WORD = Regex("""\bAi\b""")
}
