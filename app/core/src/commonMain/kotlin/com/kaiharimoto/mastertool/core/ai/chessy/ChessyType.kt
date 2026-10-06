package com.kaiharimoto.mastertool.core.ai.chessy

/**
 * How Chessy's words are typed into her box (kai, 2026-10: the emoticons "get cut off … to another line" and the text
 * "changes the text layout slightly as its being typed out"). Two rules, the takeover's and the petting mode's:
 * - **The whole line is laid out from the first letter**: [layout] gives the text to set and where each typed unit ends
 *   in it, so the box draws all of it and only colours what has been typed. Nothing reflows as it types.
 * - **An emoticon is one unit and one unbreakable piece**: it appears whole, never a half face, and it is glued to the
 *   word before it (no-break spaces, word joiners), so a line never breaks inside it or just before it.
 */
object ChessyType {
    /** A kaomoji: a bracketed face with something not ASCII inside, the paw and whisker faces, or a lone heart or star. */
    val KAOMOJI = Regex("♡?[(（][^()（）\\n]*[^\\u0000-\\u007F][^()（）\\n]*[)）][♡✧≡]?|ฅ\\S*ฅ|≽\\S*≼|[♡✧]")
    private const val NBSP = ' '
    private const val JOIN = '⁠'

    /** [text] set for display, and [ends]: where unit k (1-based) ends in it; ends[0] is 0. */
    class Laid(val text: String, val ends: IntArray) {
        val units: Int get() = ends.size - 1

        /** How much of [text] is typed after [n] units. */
        fun typedTo(n: Int): Int = ends[n.coerceIn(0, units)]
    }

    /** The units [text] is typed in: a character (a whole grapheme of one code point or a surrogate pair), or a kaomoji. */
    fun units(text: String): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (m in KAOMOJI.findAll(text)) {
            chars(text.substring(last, m.range.first), out)
            out += m.value
            last = m.range.last + 1
        }
        chars(text.substring(last), out)
        return out
    }

    private fun chars(s: String, out: MutableList<String>) {
        var i = 0
        while (i < s.length) {
            val step = if (s[i].isHighSurrogate() && i + 1 < s.length) 2 else 1
            out += s.substring(i, i + step)
            i += step
        }
    }

    fun isKaomoji(u: String): Boolean = u == "♡" || u == "✧" || (u.length > 1 && KAOMOJI.matches(u))

    /** [text] as it is set: each kaomoji held together and to the word before it. */
    fun layout(text: String): Laid {
        val u = units(text)
        // the first unit of the piece each kaomoji is glued into: the word before it and the space between
        val glueFrom = IntArray(u.size) { -1 }
        for (i in u.indices) if (isKaomoji(u[i])) {
            var j = i - 1
            if (j >= 0 && u[j] == " ") j--
            while (j >= 0 && u[j] != " " && u[j] != "\n" && !isKaomoji(u[j])) j--
            glueFrom[i] = j + 1
        }
        val glued = BooleanArray(u.size)
        for (i in u.indices) if (glueFrom[i] >= 0) for (x in glueFrom[i]..i) glued[x] = true
        val sb = StringBuilder()
        val ends = IntArray(u.size + 1)
        for (i in u.indices) {
            val unit = u[i]
            when {
                glued[i] && unit == " " -> sb.append(NBSP)
                isKaomoji(unit) -> {
                    // a joiner before it (after the word) and between its characters: no break anywhere inside
                    if (i > 0 && glued[i - 1]) sb.append(JOIN)
                    unit.forEachIndexed { k, ch -> if (k > 0 && !(ch.isLowSurrogate())) sb.append(JOIN); sb.append(if (ch == ' ') NBSP else ch) }
                }
                else -> sb.append(unit)
            }
            ends[i + 1] = sb.length
        }
        return Laid(sb.toString(), ends)
    }
}
