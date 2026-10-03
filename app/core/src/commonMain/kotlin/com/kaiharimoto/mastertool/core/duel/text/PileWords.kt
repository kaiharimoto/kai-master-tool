package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.QueryKind

/**
 * The piles' words, one table (the refactor's step 1): each pile's coordinate code, its names, its `o`-forms and its
 * prose, and the tables the line, the questions, the random line, the shuffle head and the log read are derived
 * from it. The tables never all agreed, and they still do not — each difference is a named variant below, so
 * nothing anyone could type reads differently than it did.
 *
 * `DuelPreview`'s pile names ("GY", "banished cards", "Extra Deck", "Deck", "hand") are deliberately not from here:
 * the preview names a pile bare, after "your", "their" or "the" ("Open their banished cards", "Shuffle the Deck"),
 * where the log's prose ("banishment", "the GY") would not read.
 */
internal object PileWords {

    /**
     * One pile's words.
     * - [code]: its table notation ([DuelNotation.Kind.short]): `h`, `gy`, `ban`, `ex`, `dk`.
     * - [names]: the words that name it besides the code.
     * - [phrases]: what a question may also say ("cards in hand", "grave yard").
     * - [theirs]: the random line's words for the other seat's pile (`oh`, `ogy`… and a few spelled out).
     * - [line]: what a move's "from" or "to" names it by — its own list (see [LINE]).
     * - [prose]: the log's words for it as a destination: "to the GY", "to banishment".
     */
    class Entry(
        val kind: PileKind,
        val code: String,
        val names: List<String>,
        val phrases: List<String>,
        val theirs: List<String>,
        val line: List<String>,
        val prose: String,
    ) {
        /**
         * The pile's bare word as a question: its code, but the hand's is `h` — a verb letter and the start of every
         * hand coordinate — so the hand's is its name. `ogy`, `oban`, `oex`, `odk` and `ohand` ask about theirs.
         */
        val bare: String get() = if (code.length > 1) code else names.first()
    }

    val ENTRIES: List<Entry> = listOf(
        Entry(PileKind.HAND, "h", listOf("hand"), listOf("cards in hand"), listOf("oh", "ohand"), listOf("hand"), "the hand"),
        Entry(PileKind.GY, "gy", listOf("grave", "graveyard"), listOf("grave yard"), listOf("ogy"), listOf("gy", "grave", "graveyard"), "the GY"),
        Entry(
            PileKind.BANISHED, "ban", listOf("banished", "banishment"), listOf("banished cards"), listOf("oban"),
            listOf("banish", "banished", "removed", "exile"), "banishment",
        ),
        Entry(PileKind.EXTRA, "ex", listOf("extra", "ed"), listOf("extra deck"), listOf("oex", "oed"), listOf("extra", "ed"), "the Extra Deck"),
        Entry(PileKind.DECK, "dk", listOf("deck"), emptyList(), listOf("odk", "odeck"), listOf("deck"), "the Deck"),
    )

    fun of(kind: PileKind): Entry = ENTRIES.first { it.kind == kind }

    /**
     * A pile after a move's "from" or "to": `ash from gy`, `ash to extra`. Its own list, not the names: no codes but
     * `gy` (which is a word too), "banishment" not among them, and the banished pile by its verbs (`banish`,
     * `removed`, `exile`) as well.
     */
    val LINE: Map<String, PileKind> = ENTRIES.flatMap { e -> e.line.map { it to e.kind } }.toMap()

    /** The random line's piles: word → (the other seat's, pile). The code and the names for yours; [Entry.theirs] for theirs. */
    val RANDOM: Map<String, Pair<Boolean, PileKind>> = ENTRIES.flatMap { e ->
        (listOf(e.code) + e.names).map { it to (false to e.kind) } + e.theirs.map { it to (true to e.kind) }
    }.toMap()

    /** A question's pile words, whole: the bare word, the names and the phrases (never the hand's letter `h`). */
    val QUERY: Map<String, QueryKind> = ENTRIES.flatMap { e -> (listOf(e.bare) + e.names + e.phrases).distinct().map { it to queryKind(e.kind) } }.toMap()

    /** The words a question takes with an `o` in front: each pile's bare word, and "field", which is no pile. */
    val QUERY_THEIRS: Set<String> = ENTRIES.map { it.bare }.toSet() + "field"

    /** `shuffle <pile>`: the piles a shuffle takes (the hand, the Extra Deck, the Deck), by their bare word and names. */
    val SHUFFLE: Map<String, PileKind> = listOf(PileKind.HAND, PileKind.EXTRA, PileKind.DECK).map(::of)
        .flatMap { e -> (listOf(e.bare) + e.names).map { it to e.kind } }.toMap()

    /** The log's prose for a pile: "the hand", "the Deck", "the Extra Deck", "the GY", "banishment". */
    fun prose(kind: PileKind): String = of(kind).prose

    /** The random line's destination: the prose, with the Deck's end ("the top of the Deck") and a face-down banishment. */
    fun randomProse(kind: PileKind, bottom: Boolean, down: Boolean): String = when (kind) {
        PileKind.DECK -> "the ${if (bottom) "bottom" else "top"} of ${prose(kind)}"
        PileKind.BANISHED -> if (down) "${prose(kind)}, face-down" else prose(kind)
        else -> prose(kind)
    }

    private fun queryKind(kind: PileKind): QueryKind = when (kind) {
        PileKind.HAND -> QueryKind.HAND
        PileKind.GY -> QueryKind.GY
        PileKind.BANISHED -> QueryKind.BANISHED
        PileKind.EXTRA -> QueryKind.EXTRA
        PileKind.DECK -> QueryKind.DECK
    }
}
