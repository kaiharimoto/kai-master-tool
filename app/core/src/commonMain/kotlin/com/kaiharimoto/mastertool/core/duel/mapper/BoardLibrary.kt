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
    fun add(deal: MapDeal, mapped: MapSearch.Mapped, run: Int, at: Long = 0L): BoardLibrary =
        add(deal, mapped.ends.map { MapEnd(it.key, it.cards, it.traits, MapLine.of(deal, it), it.at) }, run, at)

    /** [ends] (a map of [deal], kept without its tables: [MapWork]) added, as [add] adds a map. */
    fun add(deal: MapDeal, ends: List<MapEnd>, run: Int, at: Long = 0L): BoardLibrary {
        require(deal.first == first) { "a deal going ${if (deal.first) "first" else "second"} added to the library going ${if (first) "first" else "second"}" }
        val out = LinkedHashMap(byKey)
        ends.forEach { e ->
            val line = e.line.copy(deck = deck)
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
     * The ends of one map the library takes (M.md §2.6): every board it holds already (it gains the line and the starter),
     * and each new board no other board of its field beats on what a field is judged by ([judged]) — among the library's
     * live boards (unless not [againstLibrary]) and the map's own; of equal boards, the cheapest line's. A dealt hand's
     * boards are nearly all distinct by what was left in hand and in the GY (the bench deck's starter table: 8,157 boards,
     * 637 fields), and a field is what a player sees.
     */
    fun admits(ends: List<MapEnd>, againstLibrary: Boolean = true): List<MapEnd> {
        val known = byKey
        val fronts = HashMap<BoardCards, MutableList<DoubleArray>>()
        if (againstLibrary) live.forEach { e -> fronts.getOrPut(field(e.cards)) { ArrayList() } += judged(e.cards, e.traits) }
        val fresh = ends.filter { it.key !in known }.distinctBy { it.key }.sortedWith(compareBy<MapEnd>({ it.line.cost }, { it.line.text }))
        val vectors = fresh.associate { it.key to judged(it.cards, it.traits) }
        val byField = fresh.groupBy { field(it.cards) }
        val out = ends.filter { it.key in known }.toMutableList()
        fresh.forEach { e ->
            val f = field(e.cards)
            val v = vectors.getValue(e.key)
            // Beaten by another board of this map: only the better joins, whichever was found first.
            if (byField.getValue(f).any { o -> o !== e && Pareto.dominates(vectors.getValue(o.key), v) }) return@forEach
            val front = fronts.getOrPut(f) { ArrayList() }
            if (front.any { it.contentEquals(v) || Pareto.dominates(it, v) }) return@forEach
            front += v
            out += e
        }
        return out
    }

    /**
     * The library made on the deck as it is under an earlier print of it ([from], `Ledger.fingerprintV1`) given the print it
     * has now ([to]): the deck and its lines' stamps moved, nothing else — so the upgrade to print 2 stales no board.
     */
    fun adopted(from: Set<String>, to: String): BoardLibrary {
        if (deck !in from || to.isEmpty()) return this
        return copy(deck = to, boards = boards.map { e -> e.copy(lines = e.lines.map { if (it.deck in from) it.copy(deck = to) else it }) })
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

    /**
     * This library and [other] together (the same deck's, from another device: a sync keeps the newer file, and runs only
     * add, so nothing is lost by putting both together). Every board of either; a board in both keeps the cheaper lines,
     * every starter and every stress result, and is stale only when stale in both. The deck and scripts are this library's:
     * a board only [other] has, found on another version of the deck or its scripts, comes in stale, its lines and stress
     * results from there left behind.
     */
    fun merged(other: BoardLibrary): BoardLibrary {
        if (other.first != first || other.boards.isEmpty()) return this
        val o = other.rekeyed()
        val same = o.deck == deck && o.library == library
        val out = LinkedHashMap(byKey)
        o.boards.forEach { e ->
            val theirs = if (same) e else e.copy(stale = true, lines = e.lines.filter { it.deck == deck }, traits = e.traits.copy(through = emptyMap()))
            val old = out[e.key]
            out[e.key] = if (old == null) theirs else old.copy(
                traits = old.traits.copy(through = theirs.traits.through + old.traits.through),
                lines = (old.lines + theirs.lines.filter { it.deck == deck }).distinct().sortedWith(LINE_ORDER).take(LINES),
                starters = (old.starters + theirs.starters).distinct().sortedWith(STARTERS),
                stale = old.stale && theirs.stale,
                found = minOf(old.found, theirs.found),
            )
        }
        return copy(boards = out.values.sortedBy { it.key }, runs = maxOf(runs, o.runs))
    }

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

        /** A board's field: what a player sees of it, with the hand, the GY, the banished cards and the LP left aside. */
        fun field(c: BoardCards): BoardCards = c.copy(hand = emptyList(), gy = emptyList(), banished = emptyList(), lp = 0)

        /**
         * What boards of one field are judged by, more being better: the traits where more is plainly better
         * ([BoardTraits.MORE_IS_BETTER]), the cards kept in hand, and the LP left.
         */
        fun judged(c: BoardCards, t: BoardTraits): DoubleArray =
            (BoardTraits.MORE_IS_BETTER.map { t[it] ?: 0.0 } + t.hand.toDouble() + c.lp.toDouble()).toDoubleArray()

        /**
         * Read forgivingly: a newer build's fields ignored. An unreadable file is null, never an empty library: the caller
         * must not write over what it could not read. A library keyed by another [BoardKey.VERSION] is keyed again from its
         * stored cards and traits, two boards that now share a key merged.
         */
        fun decode(text: String): BoardLibrary? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()?.rekeyed()
    }
}
