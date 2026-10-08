package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The board library (M.md §2.6, kai: "it would find optimized endboards from a library that it found during runs"): every
 * distinct end board any run has found for a deck, with what it measures and the lines that reach it. Runs only add to it;
 * the person's filters and weights choose from it ([BoardQuery]) and never change it. Kept per deck in
 * `<data>/effects/mapper/<deck>/library.json`, synced, backed up, deleted with the deck.
 */

/** One board of the library. */
@Serializable
data class BoardEntry(
    val key: String,
    val cards: BoardCards,
    val traits: BoardTraits,
    /** The cheapest lines found to it, cheapest first, at most [BoardLibrary.LINES]. */
    val lines: List<MapLine> = emptyList(),
    /** Every starting hand found to reach it (sorted passcodes), smallest first: its starters. */
    val starters: List<List<Int>> = emptyList(),
    /** When it was first found (ms), and by which run. */
    val found: Long = 0L,
    val run: Int = 0,
    /** The deck changed and no run since has reached it again: kept, marked, never deleted. */
    val stale: Boolean = false,
)

/**
 * A deck's library. [deck] is the deck's fingerprint and [library] the trusted scripts' (`FxTrust.library`) when last run:
 * either moving marks every board [BoardEntry.stale] until a run reaches it again.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BoardLibrary(
    val version: Int = 1,
    val deckId: String = "",
    val deck: String = "",
    val library: String = "",
    val first: Boolean = true,
    val boards: List<BoardEntry> = emptyList(),
    val runs: Int = 0,
    /**
     * The [BoardKey.VERSION] its boards are keyed by: always written, so a file keyed by this version still says so when the
     * version has moved on and the default with it.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val keys: Int = BoardKey.VERSION,
) {
    val byKey: Map<String, BoardEntry> by lazy { boards.associateBy { it.key } }

    /** The boards still reachable (not [BoardEntry.stale]). */
    val live: List<BoardEntry> get() = boards.filterNot { it.stale }

    /**
     * [mapped] (from [deal]) added: a new board joins, a known one gains the line if it is cheaper and the starter if it is
     * new, and is no longer stale. Its lines are stamped with [deck]; a known board's lines found on another deck are dropped,
     * since their uids may not play again. Boards come out in key order, so two devices merging the same runs agree. A deal
     * on the other side of the turn ([first]) is refused: going first and going second are two libraries.
     */
    fun add(deal: MapDeal, mapped: MapSearch.Mapped, run: Int, at: Long = 0L): BoardLibrary {
        require(deal.first == first) { "a deal going ${if (deal.first) "first" else "second"} added to the library going ${if (first) "first" else "second"}" }
        val out = LinkedHashMap(byKey)
        mapped.ends.forEach { e ->
            val line = MapLine.of(deal, e, deck)
            val starter = deal.hand.sorted()
            val old = out[e.key]
            out[e.key] = if (old == null) {
                BoardEntry(e.key, e.cards, e.traits, listOf(line), listOf(starter), at, run)
            } else {
                old.copy(
                    // A board measured again keeps what the stress tests found; the counted traits are the table's.
                    traits = e.traits.copy(through = old.traits.through + e.traits.through),
                    lines = (old.lines.filter { it.deck == deck } + line).distinct().sortedWith(LINE_ORDER).take(LINES),
                    starters = (old.starters + listOf(starter)).distinct().sortedWith(STARTERS),
                    stale = false,
                )
            }
        }
        return copy(boards = out.values.sortedBy { it.key }, runs = maxOf(runs, run))
    }

    /**
     * The library after the deck or the scripts changed: every board stale until a run reaches it again, and its stress
     * results forgotten (they were measured with the old scripts on both sides).
     */
    fun rebased(deck: String, library: String): BoardLibrary =
        if (deck == this.deck && library == this.library) this
        else copy(deck = deck, library = library, boards = boards.map { it.copy(stale = true, traits = it.traits.copy(through = emptyMap())) })

    /**
     * Every board's lines played again on the deck as it is ([main], [extra]): a line that still ends on its board is kept and
     * stamped with [deck], one that does not is dropped, and a board none of whose lines plays again is stale. What a rebase
     * marked stale and still plays is live again without a run.
     */
    fun revalidated(main: List<Int>, extra: List<Int>, kit: GoldfishKit): BoardLibrary = copy(
        boards = boards.map { e ->
            val kept = e.lines.filter { MapReplay.of(it, main, extra, kit).key == e.key }.map { it.copy(deck = deck) }
            e.copy(lines = kept, stale = kept.isEmpty())
        },
    )

    /** Records a stress result: [key]'s board plays through interruption set [suite] keeping [interruptions]. */
    fun through(key: String, suite: String, interruptions: Int): BoardLibrary =
        copy(boards = boards.map { if (it.key == key) it.copy(traits = it.traits.copy(through = it.traits.through + (suite to interruptions))) else it })

    fun encode(): String = JSON.encodeToString(serializer(), this)

    /** This library keyed by the current [BoardKey.VERSION]. */
    fun rekeyed(): BoardLibrary {
        if (keys == BoardKey.VERSION) return this
        val out = LinkedHashMap<String, BoardEntry>()
        boards.forEach { e ->
            val k = BoardKey.of(e.cards, e.traits)
            val old = out[k]
            out[k] = if (old == null) e.copy(key = k) else old.copy(
                lines = (old.lines + e.lines).distinct().sortedWith(LINE_ORDER).take(LINES),
                starters = (old.starters + e.starters).distinct().sortedWith(STARTERS),
                stale = old.stale && e.stale,
                found = minOf(old.found, e.found),
            )
        }
        return copy(boards = out.values.sortedBy { it.key }, keys = BoardKey.VERSION)
    }

    companion object {
        /** Lines kept a board. */
        const val LINES = 3

        private val STARTERS = compareBy<List<Int>>({ it.size }, { it.joinToString(",") })

        /** Cheaper first, then by the line's text: the same lines kept whatever order the runs were merged in. */
        private val LINE_ORDER = compareBy<MapLine>({ it.cost }, { it.text })

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /**
         * Read forgivingly: a newer build's fields ignored. An unreadable file is null, never an empty library: the caller
         * must not write over what it could not read. A library keyed by another [BoardKey.VERSION] is keyed again from its
         * stored cards and traits, two boards that now share a key merged.
         */
        fun decode(text: String): BoardLibrary? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()?.rekeyed()
    }
}
