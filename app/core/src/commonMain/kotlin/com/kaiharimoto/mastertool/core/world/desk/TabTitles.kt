package com.kaiharimoto.mastertool.core.world.desk

/**
 * A tab's title as its strip has room for it (`docs/world/READABILITY.md` §4, `TabTitlesTest`; kai: "the tabs names are
 * all truncated so it's hard to tell which tab is which"): the words that tell this page from its neighbours, never the
 * first letters of a long title. "What each card is worth to Starters ≥ 1" beside "When Starters ≥ 1 fails" reads
 * "Card worth Starters…" and "Starters ≥ 1 fails", not "What …" and "When…".
 *
 * The rule, in order, stopping as soon as it fits [short]'s `max`:
 * 1. the title cleaned — the quotes round it and its spare spaces gone;
 * 2. the little words dropped ([STOP]) and the dashes and dots that join a title's parts;
 * 3. the words most of its neighbours share dropped too (a deck's name every page of a study carries);
 * 4. cut at the last whole word that fits, with `…` — and a single word too long is cut inside it.
 *
 * Never shorter than [FLOOR] characters where the title has them: a strip too narrow for that takes a wider tab, a second
 * line or a list (the readability rules), never a stub. The full title is always the tab's tip and the overview's.
 */
object TabTitles {
    /** The fewest characters a cut title keeps (§4 of the readability rules). */
    const val FLOOR = 12

    /** Words that carry no difference between two titles. */
    val STOP = setOf(
        "a", "an", "the", "of", "to", "in", "on", "for", "and", "or", "by", "with", "from", "at", "as", "into",
        "what", "which", "each", "every", "is", "are", "was", "how", "its", "it", "this", "that",
    )

    private val JOINS = setOf("—", "–", "-", "·", ":", "|", "/")
    private val QUOTES = charArrayOf('"', '“', '”', '\'', '‘', '’')
    private val SPACES = Regex("""\s+""")

    /** [title] without the quotes round it or inside it as wrapping, and with one space between words. */
    fun clean(title: String): String =
        title.replace(SPACES, " ").trim().trim(*QUOTES).replace("“", "").replace("”", "").replace("\"", "").trim()

    /**
     * [title] in at most [max] characters (never under [FLOOR]), its distinctive words first; [siblings] are the other
     * tabs' titles, for the words they share.
     */
    fun short(title: String, max: Int, siblings: List<String> = emptyList()): String {
        val room = max.coerceAtLeast(FLOOR)
        val full = clean(title)
        if (full.length <= room) return full
        val words = full.split(' ').filter { it.isNotEmpty() }
        val meaningful = words.filter { it.lowercase() !in STOP && it !in JOINS }.ifEmpty { words }
        val shared = sharedWords(siblings.map(::clean).filter { it != full })
        val distinct = meaningful.filter { it.lowercase() !in shared }.ifEmpty { meaningful }
        val joined = capital(distinct.joinToString(" "))
        if (joined.length <= room) return joined
        return cut(joined, room)
    }

    /** Words that stand in more than half of [titles] (and in at least two): what a group of pages has in common. */
    private fun sharedWords(titles: List<String>): Set<String> {
        if (titles.size < 2) return emptySet()
        val counts = HashMap<String, Int>()
        titles.forEach { t -> t.lowercase().split(' ').filter { it.isNotEmpty() }.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val need = maxOf(2, titles.size / 2 + 1)
        return counts.filterValues { it >= need }.keys
    }

    /** [text] cut to [room] characters at a word's end where one falls late enough, with `…`. */
    private fun cut(text: String, room: Int): String {
        val limit = room - 1
        val end = text.lastIndexOf(' ', limit).takeIf { it >= FLOOR - 1 } ?: limit
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun capital(s: String) = s.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
}
