package com.kaiharimoto.mastertool.core.ai.eval

/**
 * Trust's words for what a run got wrong (Settings › Ai › Trust): the question first, cut at a word, then what it
 * answered against what is right and how close it had to be, and the item's id last — never an id leading a line, nor a
 * prompt cut mid-word.
 */
object TrustWords {
    /** One missed item: the question's gist, the verdict in words, and the item's id. */
    data class Miss(val question: String, val verdict: String, val id: String)

    private val expected = Regex("""^(.+?) \(expected (.+?)\)$""")

    fun miss(item: EvalItem?, outcome: ItemOutcome, max: Int = 90): Miss =
        Miss(item?.prompt?.let { gist(it, max) }.orEmpty(), verdict(item?.grader, outcome.read), outcome.id)

    /**
     * The grader's reading in words: "74.5% (expected 74.2%)" is "answered 74.5%, right is 74.2% (to 0.1%)" — the
     * precision said, so a near miss reads as the miss it is; anything else as the grader wrote it.
     */
    fun verdict(grader: Grader?, read: String): String {
        val m = expected.matchEntire(read.trim()) ?: return read
        val (said, right) = m.destructured
        val within = (grader as? Grader.Percent)?.let { " (to ${precision(it.decimals)}%)" }.orEmpty()
        return "answered $said, right is $right$within"
    }

    /** 1 → "0.1", 0 → "1", 2 → "0.01". */
    fun precision(decimals: Int): String =
        if (decimals <= 0) "1" else "0." + "0".repeat(decimals - 1) + "1"

    /**
     * A question's first line, at most [max] characters, cut at a word with "…" — never inside a `[[Card]]`, which the
     * dialog draws as the card's name.
     */
    fun gist(prompt: String, max: Int = 90): String {
        val line = prompt.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.length <= max) return line
        var cut = line.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        // A card's name is never cut: back to before its brackets.
        val open = line.lastIndexOf("[[", cut)
        if (open >= 0 && line.indexOf("]]", open).let { it < 0 || it + 2 > cut }) cut = line.lastIndexOf(' ', open).takeIf { it > 0 } ?: open
        return line.substring(0, cut).trimEnd(' ', ',', ';', ':', '(', '—') + "…"
    }
}
