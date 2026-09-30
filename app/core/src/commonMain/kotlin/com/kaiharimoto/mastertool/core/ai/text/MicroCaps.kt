package com.kaiharimoto.mastertool.core.ai.text

/**
 * Micro caps that leave a name as it is written (1.0.45, kai: "when Ai is shown in the
 * UI be sure to have it not in all caps … it would be easy to conflate Ai the name with
 * AI meaning artificial intelligence"). The kit sets every label in capitals; the
 * assistant's name, and its possessive, keep their own spelling inside one — "ASK Ai",
 * "Ai'S" never, "Ai's NOTES". A name is matched whole and case for case, so "Ai" does
 * not keep the "ai" of "Maiden", nor "air".
 */
object MicroCaps {
    fun of(text: String, keep: Set<String>): String {
        val names = keep.filter { it.isNotBlank() }
        if (names.isEmpty()) return text.uppercase()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = names.firstOrNull { name -> text.startsWith(name, i) && boundary(text, i - 1) && boundary(text, i + name.length, allowPossessive = true) }
            if (hit == null) {
                out.append(text[i].uppercaseChar())
                i++
                continue
            }
            out.append(hit)
            i += hit.length
            // "Ai's" and "Ai’s": the possessive belongs to the name.
            if (i + 1 < text.length && (text[i] == '\'' || text[i] == '’') && text[i + 1] == 's' && boundary(text, i + 2)) {
                out.append(text[i]).append('s')
                i += 2
            }
        }
        return out.toString()
    }

    private fun boundary(text: String, at: Int, allowPossessive: Boolean = false): Boolean {
        if (at < 0 || at >= text.length) return true
        val ch = text[at]
        if (allowPossessive && (ch == '\'' || ch == '’')) return true
        return !ch.isLetterOrDigit()
    }
}
