package com.kaiharimoto.mastertool.core.ai.playbook

/**
 * Finding what the playbook knows: by words (a question in chat, a study checking what it has), and by the cards in
 * play — at the table the entries that fit the hand and the board come first, a line the hand can start above all, so
 * what Ai reads before it moves is about the position it is in, never the playbook's first pages.
 */
object PlaybookSearch {
    data class Hit(val play: Play, val score: Double)

    /** Entries for [query], of [kind] when given, touching [cards] when given; best first. */
    fun search(book: Playbook, query: String, kind: Play.Kind? = null, cards: Collection<String> = emptyList(), limit: Int = 20): List<Hit> =
        page(book, query, kind, cards, limit = limit).hits

    /** One page of a search: [hits], from the [from]th of [total] that match. */
    data class Page(val hits: List<Hit>, val from: Int, val total: Int)

    /**
     * Entries for [query], of [kind], touching [cards], learned from [source] (a source's ref begins with it: "ch. 4",
     * "replay 12"), each when given; best first, [limit] of them from the [from]th — so a playbook of any size can be
     * read whole, a page at a time (1.1.47: consolidating a course saw only its first hundred decisions).
     */
    fun page(book: Playbook, query: String, kind: Play.Kind? = null, cards: Collection<String> = emptyList(), source: String? = null, from: Int = 0, limit: Int = 20): Page {
        val words = terms(query)
        val wanted = cards.map(::norm).filter { it.isNotBlank() }.toSet()
        val origin = source?.let(::norm)?.takeIf { it.isNotBlank() }
        val all = book.entries.asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { wanted.isEmpty() || it.allCards.any { c -> norm(c) in wanted } }
            .filter { origin == null || it.sources.any { s -> learnedFrom(s.ref, origin) } }
            .map { p -> Hit(p, score(p, words) + if (wanted.isNotEmpty()) p.allCards.count { norm(it) in wanted } * 2.0 else 0.0) }
            .filter { words.isEmpty() || it.score > 0 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.play.sources.size }.thenBy { it.play.id })
            .toList()
        val start = from.coerceIn(0, all.size)
        return Page(all.drop(start).take(limit.coerceIn(1, 100)), start, all.size)
    }

    /** Whether a source's [ref] is [origin] (normalised) or within it: "ch. 4 §2" is in "ch 4", "ch. 41" is not. */
    fun learnedFrom(ref: String, origin: String): Boolean {
        val r = norm(ref)
        return r == origin || r.startsWith("$origin ")
    }

    /** What the cards in play are: the seat's hand, its field and piles it can use, and the other seat's known cards. */
    data class Position(
        val hand: Collection<String> = emptyList(),
        val mine: Collection<String> = emptyList(),
        val theirs: Collection<String> = emptyList(),
        /** Whether the seat went first this game, when known. */
        val first: Boolean? = null,
    )

    /**
     * The entries for [at], most useful first, within [budget] characters, written as [render] does, compactly; and how
     * many more there are. A line the hand can start comes first; then lines it nearly can, the decisions and card roles
     * the cards in play touch, the matchup against what the other seat has shown, and principles.
     */
    fun relevant(book: Playbook, at: Position, budget: Int): Pair<String, Int> {
        if (book.entries.isEmpty()) return "" to 0
        val hand = at.hand.map(::norm).toSet()
        val mine = at.mine.map(::norm).toSet() + hand
        val theirs = at.theirs.map(::norm).toSet()
        fun fit(p: Play): Double {
            val cards = p.allCards.map(::norm).toSet()
            val going = when {
                at.first == null || p.going == Play.Going.EITHER -> 0.0
                (p.going == Play.Going.FIRST) == at.first -> 2.0
                else -> -4.0
            }
            val evidence = minOf(p.sources.size, 5) * 0.2
            val base = when (p.kind) {
                Play.Kind.LINE -> {
                    val needs = p.needs.map(::norm)
                    // The hand starts a line; what is elsewhere (the GY, the Extra Deck, the field) may carry it on, and counts for
                    // less (1.1.47: a card in the GY made a line look startable).
                    when {
                        needs.isNotEmpty() && needs.all { it in hand } -> 20.0
                        needs.isNotEmpty() && needs.all { it in mine } && needs.any { it in hand } -> 10.0
                        needs.any { it in hand } -> 6.0 + needs.count { it in hand }
                        needs.any { it in mine } -> 2.0 + needs.count { it in mine } * 0.5
                        else -> 0.5
                    }
                }
                Play.Kind.DECISION -> cards.count { it in mine } * 3.0 + cards.count { it in theirs } * 3.0 + 0.5
                Play.Kind.CARD -> cards.count { it in mine } * 4.0 + cards.count { it in theirs } * 2.0
                Play.Kind.MATCHUP -> cards.count { it in theirs } * 4.0 + if (theirs.any { t -> norm(p.against).contains(t) }) 6.0 else 0.0
                Play.Kind.RULING -> cards.count { it in mine || it in theirs } * 3.0
                Play.Kind.PRINCIPLE -> 2.0
            }
            return base + going + evidence
        }
        val ranked = book.entries.map { it to fit(it) }.filter { it.second > 0.6 }.sortedByDescending { it.second }
        val out = StringBuilder()
        var shown = 0
        for ((p, _) in ranked) {
            val text = render(p, compact = true)
            if (out.isNotEmpty() && out.length + text.length + 1 > budget) continue
            out.append(text).append('\n')
            shown++
        }
        return out.toString().trimEnd() to (book.entries.size - shown)
    }

    /** One entry in words: whole for playbook_read, [compact] at the table (the body cut, sources counted). */
    fun render(p: Play, compact: Boolean = false): String = buildString {
        val going = if (p.going == Play.Going.EITHER) "" else " (going ${p.going.word})"
        append("[${p.id}] ${p.kind.word.uppercase()} · ${p.title}$going — ${p.confidence.word}, ${p.sources.size} source${if (p.sources.size == 1) "" else "s"}\n")
        if (p.against.isNotBlank()) append("Against: ${p.against}\n")
        if (p.needs.isNotEmpty()) append("Needs: ${p.needs.joinToString()}\n")
        if (p.situation.isNotBlank()) append("When: ${p.situation}\n")
        if (p.choice.isNotBlank()) append("Do: ${p.choice}\n")
        if (p.steps.isNotEmpty()) {
            p.steps.forEachIndexed { i, s ->
                append("  ${i + 1}. ${s.card}: ${s.action}")
                if (s.result.isNotBlank()) append(" → ${s.result}")
                append('\n')
            }
        }
        if (p.endBoard.isNotBlank()) append("Ends on: ${p.endBoard}\n")
        if (p.through.isNotEmpty()) append("Plays through: ${p.through.joinToString("; ")}\n")
        if (p.weakTo.isNotEmpty()) append("Weak to: ${p.weakTo.joinToString("; ")}\n")
        if (p.why.isNotBlank()) append("Why: ${p.why}\n")
        if (p.body.isNotBlank()) append(if (compact) cut(p.body, COMPACT_BODY) else p.body).append('\n')
        if (!compact && p.cards.isNotEmpty()) append("Cards: ${p.cards.joinToString()}\n")
        if (!compact && p.sources.isNotEmpty()) append("Sources: ${p.sources.joinToString("; ") { s -> s.ref + if (s.note.isNotBlank()) " (${s.note})" else "" }}\n")
    }.trimEnd()

    /** One entry in a line, for lists. */
    fun line(p: Play): String {
        val going = if (p.going == Play.Going.EITHER) "" else ", going ${p.going.word}"
        val gist = (p.choice.ifBlank { p.endBoard }.ifBlank { p.body }).replace('\n', ' ')
        return "[${p.id}] ${p.kind.word} · ${p.title}$going (${p.sources.size} src): ${cut(gist, 140)}"
    }

    /** The words of an entry, weighted: its title and cards count most. */
    private fun score(p: Play, words: List<String>): Double {
        if (words.isEmpty()) return 1.0
        val title = norm(p.title)
        val cards = p.allCards.joinToString(" ") { norm(it) }
        val rest = norm(listOf(p.body, p.situation, p.choice, p.why, p.endBoard, p.against, p.through.joinToString(" "), p.weakTo.joinToString(" "),
            p.steps.joinToString(" ") { it.action + " " + it.result }).joinToString(" "))
        return words.sumOf { w -> (if (w in title) 3.0 else 0.0) + (if (w in cards) 3.0 else 0.0) + (if (w in rest) 1.0 else 0.0) }
    }

    private fun terms(q: String): List<String> = norm(q).split(' ').filter { it.length >= 2 && it !in STOP }.distinct()

    private val STOP = setOf("the", "a", "an", "of", "to", "in", "on", "and", "or", "for", "with", "is", "it", "my", "your", "how", "what", "when", "do", "i")

    fun norm(s: String): String = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotBlank() }.joinToString(" ")

    private fun cut(t: String, cap: Int): String = if (t.length <= cap) t else t.take(cap - 1).trimEnd() + "…"

    const val COMPACT_BODY = 500
}
