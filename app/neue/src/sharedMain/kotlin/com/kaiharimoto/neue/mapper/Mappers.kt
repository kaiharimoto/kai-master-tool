package com.kaiharimoto.neue.mapper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.BoardEntry
import com.kaiharimoto.mastertool.core.duel.mapper.BoardFilter
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.BoardQuery
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapReplay
import com.kaiharimoto.mastertool.core.duel.mapper.MapLine
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPaths
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPresets
import com.kaiharimoto.mastertool.core.duel.mapper.MapperReport
import com.kaiharimoto.mastertool.core.duel.mapper.MapperRun
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView
import com.kaiharimoto.mastertool.core.duel.mapper.StarterRun
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Ablation
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareAsk
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareProgress
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareSetup
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareWords
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Comparison
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CoverageGuard
import com.kaiharimoto.mastertool.core.duel.mapper.compare.MapCache
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Paired
import com.kaiharimoto.mastertool.core.duel.mapper.compare.VersionCompare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Gameplay Mapper's tabs (M.md §6): the first form has two. */
enum class MapperTab(val words: String) { LIBRARY("Library"), STARTERS("Starters") }

/**
 * What the library shows (the design run, M.md §6½): its boards at a density ([MapperView.Density]: the overview, rows of
 * numbers, or tiles), or the map of two traits. The densities are one list read three ways, so the gallery and the table of
 * M1's mockups are two settings of one view.
 */
enum class MapperShow(val words: String, val density: MapperView.Density?) {
    OVERVIEW("Overview", MapperView.Density.OVERVIEW),
    ROWS("Rows", MapperView.Density.ROWS),
    CARDS("Cards", MapperView.Density.CARDS),
    MAP("Map", null),
    ;

    companion object {
        fun of(d: MapperView.Density): MapperShow = entries.first { it.density == d }
    }
}

/**
 * One side of the open deck's mapper files (going first or second): its board library, the last run's counts and the starter
 * table, and the files there that this build could not read — never written over.
 */
data class MapperSide(
    val library: BoardLibrary = BoardLibrary(),
    val run: MapperRun? = null,
    val starters: StarterRun? = null,
    val unreadable: List<String> = emptyList(),
) {
    /** The side made on the deck as it is under an earlier print of it ([from]) moved to its print now ([to]): `BoardLibrary.adopted`. */
    fun adopted(from: Set<String>, to: String): MapperSide =
        copy(library = library.adopted(from, to), run = run?.adopted(from, to), starters = starters?.adopted(from, to))
}

/**
 * Gameplay Mapper (Phase M step M1, `docs/phases/M.md`): the open deck's files under `<data>/effects/mapper/<deck>/` —
 * libraries, runs, starter tables, presets — the query on screen, and the runs, off the frame thread with their progress
 * posted here and the person's Stop. For the app's lifetime (lazy in `NeueHolders`), so a run carries on while the page is
 * left. Files are written whole and atomically, one at a time; a file that could not be read is never written over.
 */
class Mappers(private val effectsDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val work = Mutex()

    var tab by mutableStateOf(MapperTab.LIBRARY)

    /** Going first (5 cards) or second (6): two libraries. */
    var first by mutableStateOf(true)

    /** The deck whose files are loaded, or null. */
    var deckId by mutableStateOf<String?>(null)
        private set

    /** Both sides' files of [deckId]. */
    var sides by mutableStateOf(mapOf<Boolean, MapperSide>())
        private set

    /** Whether [deckId]'s files have been read. */
    var loaded by mutableStateOf(false)
        private set

    var presets by mutableStateOf(MapperPresets())
        private set

    /** The filters and weights on screen: a preset's, or the person's own until saved. */
    var query by mutableStateOf(BoardPreset.DEFAULT)

    /** The board in the inspector, by key. */
    var selected by mutableStateOf<String?>(null)

    /** What the library shows, as the person chose it; null until they do, and the density follows the library's length. */
    var chosenShow by mutableStateOf<MapperShow?>(null)

    /** What the library shows: the person's choice, else the density [MapperView.autoDensity] gives this many boards. */
    fun show(boards: Int, phone: Boolean): MapperShow =
        chosenShow?.takeUnless { phone && it == MapperShow.ROWS } ?: MapperShow.of(MapperView.autoDensity(boards, phone))

    /** A step denser or looser from what is on screen (the map steps back into the list). */
    fun stepDensity(denser: Boolean, boards: Int, phone: Boolean) {
        val d = show(boards, phone).density ?: MapperView.Density.CARDS
        var next = if (denser) d.denser() else d.looser()
        if (phone && next == MapperView.Density.ROWS) next = if (denser) next.denser() else next.looser()
        chosenShow = MapperShow.of(next)
    }

    /** What the library is ordered by. */
    var order by mutableStateOf(MapperView.Order.ASKED)

    /** The weights, bounds and card rules beside the library: folded away until asked for. */
    var tuning by mutableStateOf(false)

    /** A run's settings (hands, seed) unfolded on the run line. */
    var runSettings by mutableStateOf(false)

    /** The two traits the map plots, across and up. */
    var plotX by mutableStateOf("bodies")
    var plotY by mutableStateOf("interruptions")

    /** Only the boards of one starter ([Only.keys]), from the starter table; null for the whole library. */
    var only by mutableStateOf<Only?>(null)

    /** A phone's inspector, open over the list. */
    var inspecting by mutableStateOf(false)

    data class Only(val words: String, val keys: Set<String>)

    /** The starter row chosen on the Starters tab (its cards). */
    var starter by mutableStateOf<List<Int>?>(null)

    /** Hands a run deals, as typed; null for the device's default. */
    var handsText by mutableStateOf<String?>(null)

    /** The seed, as typed. */
    var seedText by mutableStateOf("1")

    /** The run in progress, or null. */
    var running by mutableStateOf<Running?>(null)
        private set

    var progress by mutableStateOf<Progress?>(null)
        private set

    /** A line to the person about the last run or file: finished, stopped, failed, unreadable. */
    var said by mutableStateOf<String?>(null)

    /** Moves on whenever a file is written or read again. */
    var revision by mutableStateOf(0)
        private set

    /** The line being made into a replay, or null. */
    var opening by mutableStateOf<MapLine?>(null)
        private set

    @Volatile
    private var stopping = false
    private var job: Job? = null

    /** What is running: the starter table, dealt hands, or checking every line again; for [deckId] going [first]. */
    data class Running(val kind: Kind, val deckId: String, val first: Boolean, val total: Int)

    enum class Kind(val words: String) {
        STARTERS("Mapping the starters"),
        HANDS("Mapping dealt hands"),
        CHECK("Playing every line again"),
        COMPARE("Comparing two versions"),
        WITHOUT("Measuring each card without it"),
    }

    // ---------------------------------------------------------------- Phase G: two versions on the same hands

    /**
     * Maps kept for the app's lifetime by what they depend on (`MapCache`, device-only): a version compared once costs nothing
     * the next time, and the deck as it is is mapped once for every change put against it.
     */
    val cache = MapCache()

    /** "Compare with…" open. */
    var comparing by mutableStateOf(false)

    /** The comparison on screen: what was asked, how far it got, and its answer. */
    var compared by mutableStateOf<Compared?>(null)
        private set

    /** A comparison: [label] (the change in words), [ask], going [first], from [seed]; [b] is the variant, kept to open its hands. */
    data class Compared(
        val label: String,
        val ask: String,
        val first: Boolean,
        val seed: Long,
        val a: GoldfishDeck,
        val b: GoldfishDeck,
        val progress: CompareProgress? = null,
        val result: Comparison? = null,
    )

    /** Each engine card's worth without it, for the deck print, side and ask it was measured on. */
    var without by mutableStateOf<Without?>(null)
        private set

    data class Without(val print: String, val first: Boolean, val ask: String, val rows: Map<Int, Ablation.Row>, val hands: Int)

    /**
     * [a] against [b] going [first] on the same hands, asked [ask], off the frame thread; refused by the coverage guard before
     * anything is mapped when a differing card cannot be played. Null when it cannot start ([said] says why).
     */
    fun startCompare(
        a: GoldfishDeck,
        b: GoldfishDeck,
        label: String,
        ask: CompareAsk,
        kit: GoldfishKit,
        first: Boolean = this.first,
        most: Int = COMPARE_HANDS,
        seed: Long = this.seed,
    ): Job? {
        if (running != null) { said = "${running!!.kind.words} already."; return null }
        val setup = CompareSetup(a, b, ask, first, seed, batch = COMPARE_BATCH, most = most, budget = RUN_BUDGET)
        val guard = CoverageGuard.check(a, b, kit)
        compared = Compared(label, ask.name, first, seed, a, b, result = if (guard.ok) null else Comparison(Paired(), emptyList(), false, false, 0, 0, 0, 0, 0L, 0L, guard = guard))
        if (!guard.ok) return null
        return launchRun(Running(Kind.COMPARE, a.id ?: "", first, most)) { posted ->
            val t0 = System.nanoTime()
            val r = VersionCompare.run(setup, kit, cache, stop = { stopping }) { p ->
                posted(p.hands, p.most, t0)
                scope.launch { compared = compared?.copy(progress = p) }
            }
            withContext(Dispatchers.Main) { compared = compared?.copy(result = r) }
            CompareWords.all(r, ask.name).take(2).joinToString(" ")
        }
    }

    /** The comparison put away. */
    fun closeCompare() {
        if (running?.kind == Kind.COMPARE) stop()
        comparing = false
    }

    /**
     * Each engine card of [deck] measured without it ([copies]: one copy or all), going [first], asked [ask], on [hands] hands
     * each; the rows land as each card is done.
     */
    fun startWithout(deck: GoldfishDeck, kit: GoldfishKit, ask: CompareAsk, copies: Ablation.Copies = Ablation.Copies.ONE, first: Boolean = this.first, hands: Int = WITHOUT_HANDS): Job? {
        if (running != null) { said = "${running!!.kind.words} already."; return null }
        if (StarterTable.engine(deck.main, kit).isEmpty()) { said = "None of this deck's cards has a written effect the mapper trusts yet."; return null }
        val total = StarterTable.engine(deck.main, kit).size
        val seed = seed
        without = Without(deck.fingerprint, first, ask.name, emptyMap(), hands)
        return launchRun(Running(Kind.WITHOUT, deck.id ?: "", first, total)) { posted ->
            val t0 = System.nanoTime()
            val rows = Ablation.engine(deck, copies, ask, kit, cache, first, seed, hands, RUN_BUDGET, stop = { stopping }) { row, done, all ->
                posted(done, all, t0)
                scope.launch { without = without?.let { w -> w.copy(rows = w.rows + (row.card to row)) } }
            }
            "Measured ${rows.size} of $total cards without them, on ${GoldfishWords.count(hands)} hands each."
        }
    }

    data class Progress(val done: Int, val total: Int, val ms: Long)

    /** The side on screen. */
    val side: MapperSide get() = sides[first] ?: MapperSide()

    private var rankMemo: Triple<BoardLibrary, Pair<BoardPreset, Only?>, List<BoardQuery.Ranked>>? = null

    /** The side's library as the query ranks it, [only]'s boards when one starter is chosen. Remembered while nothing moves. */
    fun ranked(): List<BoardQuery.Ranked> {
        val lib = side.library
        val q = query to only
        rankMemo?.let { (l, k, r) -> if (l === lib && k == q) return r }
        val boards = only?.let { o -> lib.boards.filter { it.key in o.keys } } ?: lib.boards
        return BoardQuery.rank(boards, query).also { rankMemo = Triple(lib, q, it) }
    }

    /** The run's counts when they were made on this library's deck and scripts: else a share would be of other boards. */
    val counted: MapperRun? get() = side.run?.takeIf { it.deck == side.library.deck && it.library == side.library.library && it.hands > 0 }

    private var shareMemo: Pair<MapperRun, HashMap<BoardTraits, Double>>? = null

    /** [e]'s share of the counted hands that make at least as much, or null when no run counted this library. */
    fun shareOf(e: BoardEntry): Double? {
        val run = counted ?: return null
        val memo = shareMemo?.takeIf { it.first === run }?.second ?: HashMap<BoardTraits, Double>().also { shareMemo = run to it }
        return memo.getOrPut(e.traits) { run.atLeast(e.traits).share }
    }

    private var orderMemo: Triple<List<BoardQuery.Ranked>, Pair<MapperView.Order, MapperRun?>, List<BoardQuery.Ranked>>? = null

    /** [ranked] in the order on screen ([order]). Remembered while nothing moves. */
    fun ordered(): List<BoardQuery.Ranked> {
        val r = ranked()
        val k = order to counted
        orderMemo?.let { (l, kk, out) -> if (l === r && kk == k) return out }
        return MapperView.order(r, order) { shareOf(it) }.also { orderMemo = Triple(r, k, it) }
    }

    /** The starter table's rows, the ones reaching the most boards first. */
    fun starterRows(): List<StarterTable.Row> =
        side.starters?.rows.orEmpty().sortedWith(compareByDescending<StarterTable.Row> { it.ends.size }.thenByDescending { it.odds }.thenBy { it.cards.joinToString(",") })

    /** The board after [step] boards from the one selected (in the ranked list), or the first. */
    fun step(step: Int) {
        if (tab == MapperTab.STARTERS) {
            val rows = starterRows()
            if (rows.isEmpty()) return
            val at = rows.indexOfFirst { it.cards == starter }
            starter = rows[if (at < 0) 0 else (at + step).coerceIn(0, rows.lastIndex)].cards
            return
        }
        val r = ordered()
        if (r.isEmpty()) return
        val at = r.indexOfFirst { it.entry.key == selected }
        selected = r[if (at < 0) 0 else (at + step).coerceIn(0, r.lastIndex)].entry.key
    }

    /** A filter from [e]'s board: at least as much of every trait where more is plainly better, as a share counts it. */
    fun atLeast(e: BoardEntry) {
        val bounds = BoardTraits.MORE_IS_BETTER.mapNotNull { h -> e.traits[h]?.takeIf { it > 0 }?.let { BoardFilter(h, min = it) } }
        query = query.copy(id = "", name = "", filters = query.filters.filterNot { f -> bounds.any { it.head == f.head } } + bounds)
    }

    /** One of the questions the library answers ([MapperView.ASKS]): its weights on screen, the filters and cards kept. */
    fun ask(p: BoardPreset) {
        query = query.copy(id = "", name = "", weights = p.weights, by = BoardPreset.PERSON, why = "")
    }

    /** The query with the weight of [head] set ([w] 0: none). */
    fun weigh(head: String, w: Double) {
        query = query.copy(id = "", name = "", weights = if (w == 0.0) query.weights - head else query.weights + (head to w))
    }

    /** The query with at least [min] of [head] (0: no bound). */
    fun bound(head: String, min: Int) {
        val rest = query.filters.filterNot { it.head == head }
        query = query.copy(id = "", name = "", filters = if (min <= 0) rest else rest + BoardFilter(head, min = min.toDouble()))
    }

    /** Boards that use [card] ([use]), or that do not. A card on one list leaves the other. */
    fun card(card: Int, use: Boolean) {
        query = query.copy(
            id = "", name = "",
            uses = if (use) (query.uses - card + card) else query.uses - card,
            avoids = if (use) query.avoids - card else (query.avoids - card + card),
        )
    }

    val busy: Boolean get() = running != null

    /** [id]'s files read, unless they are the ones loaded ([force]: read again, after a sync or a restore). */
    fun open(id: String?, force: Boolean = false): Job? {
        if (id == null) {
            if (deckId != null && running == null) { deckId = null; sides = emptyMap(); loaded = false }
            return null
        }
        if (id == deckId && loaded && !force) return null
        if (id != deckId) {
            selected = null
            starter = null
            only = null
            loaded = false
        }
        deckId = id
        // Read again over what this device holds (a sync or a restore): a sync keeps the newer file, and runs only add, so
        // this device's boards are put together with the file's and written back.
        val mine = if (force && loaded) sides else null
        return scope.launch {
            val (s, p) = withContext(Dispatchers.IO) {
                work.withLock {
                    val read = read(id)
                    val merged = if (mine == null) read else read.mapValues { (first, side) ->
                        val held = mine[first]?.library
                        if (held == null || side.unreadable.isNotEmpty()) side else side.copy(library = held.merged(side.library))
                    }
                    merged.forEach { (first, side) ->
                        if (side.library != read.getValue(first).library) atomic(File(effectsDir, MapperPaths.library(id, first)), side.library.encode())
                    }
                    merged to readPresets(id)
                }
            }
            if (deckId != id) return@launch
            sides = s
            presets = p
            if (!loaded) query = p.byId(p.chosen) ?: BoardPreset.DEFAULT
            loaded = true
            revision++
        }
    }

    /** After a sync or a restore: the open deck's files read again (a run in progress keeps its own and writes them after). */
    fun reload() {
        if (running == null) open(deckId, force = true)
    }

    /** A deck deleted: its mapper folder goes with it, `train/` too. */
    fun forgetDeck(id: String) {
        scope.launch {
            work.withLock { withContext(Dispatchers.IO) { File(effectsDir, MapperPaths.deck(id)).deleteRecursively() } }
            if (deckId == id) {
                if (running?.deckId == id) stop()
                deckId = null
                sides = emptyMap()
                presets = MapperPresets()
                loaded = false
                selected = null
            }
        }
    }

    /** [id]'s files going first and second, read on this thread: for any deck (Ai's tools read decks not on screen). */
    fun read(id: String): Map<Boolean, MapperSide> = listOf(true, false).associateWith { first ->
        val bad = ArrayList<String>()
        fun <T> load(rel: String, decode: (String) -> T?): T? {
            val f = File(effectsDir, rel)
            if (!f.isFile) return null
            val text = runCatching { f.readText() }.getOrNull()
            val v = text?.let(decode)
            if (v == null) bad += f.name
            return v
        }
        MapperSide(
            library = load(MapperPaths.library(id, first), BoardLibrary::decode) ?: BoardLibrary(deckId = id, first = first),
            run = load(MapperPaths.run(id, first), MapperRun::decode),
            starters = load(MapperPaths.starters(id, first), StarterRun::decode),
            unreadable = bad,
        )
    }

    /** [id]'s presets, read on this thread. */
    fun readPresets(id: String): MapperPresets {
        val f = File(effectsDir, MapperPaths.presets(id))
        return f.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }?.let(MapperPresets::decode) ?: MapperPresets()
    }

    /** Why a run on [deck] cannot start, or null. */
    fun refusal(deck: GoldfishDeck, kit: GoldfishKit, first: Boolean): String? {
        val id = deck.id ?: return "Save the deck first: its boards are kept with it."
        if (running != null) return "${running!!.kind.words} already."
        if (id != deckId || !loaded) return "The deck's boards are still being read."
        sides[first]?.unreadable?.takeIf { it.isNotEmpty() }?.let {
            return "${it.joinToString()} could not be read by this version, so nothing is written over it. Update the app to map this deck."
        }
        if (StarterTable.engine(deck.main, kit).isEmpty()) return "None of this deck's cards has a written effect the mapper trusts yet: write some in the Effects app first."
        return null
    }

    /**
     * The starter table of [deck] mapped going [first] into its library, off the frame thread; the table and the library
     * written when it is done (or stopped: what was mapped is kept). Null when it cannot start ([said] says why).
     */
    fun startStarters(deck: GoldfishDeck, kit: GoldfishKit, first: Boolean = this.first, pairs: Boolean = true, now: Long = System.currentTimeMillis()): Job? {
        refusal(deck, kit, first)?.let { said = it; return null }
        val id = deck.id!!
        val total = StarterTable.starters(deck.main.map(kit::canonical), kit, pairs).size
        val seed = seed
        return launchRun(Running(Kind.STARTERS, id, first, total)) { posted ->
            val scripts = Mapper.scripts(deck, kit)
            val before = (sides[first] ?: MapperSide()).adopted(deck.also, deck.fingerprint).library.rebased(deck.fingerprint, scripts)
            val t0 = System.nanoTime()
            val r = StarterTable.runOn(
                deck.main, deck.extra, kit, before.copy(deckId = id, first = first), first, seed, pairs = pairs,
                at = now, stop = { stopping },
                progress = { done, all -> posted(done, all, t0) },
            )
            val ms = (System.nanoTime() - t0) / 1_000_000
            val table = StarterRun(
                deckId = id, deck = deck.fingerprint, library = scripts, first = first, seed = seed, budget = MapSearch.DEFAULT_BUDGET,
                pairs = pairs, rows = r.rows, moves = r.rows.sumOf { it.moves.toLong() }, ms = ms, at = now, stopped = r.rows.size < total,
            )
            write(id, first, library = r.library, starters = table)
            val words = "Mapped ${r.rows.size} of $total starters: ${GoldfishWords.count(r.library.boards.size)} boards in the library." +
                if (table.stopped) " Stopped before the end; what was mapped is kept." else ""
            words
        }
    }

    /**
     * [hands] hands dealt from [seed] mapped going [first], counted by kinds of board, the best of each kind added to the
     * library; written when done or stopped. Null when it cannot start ([said] says why).
     */
    fun startHands(
        deck: GoldfishDeck,
        kit: GoldfishKit,
        hands: Int,
        seed: Long = this.seed,
        first: Boolean = this.first,
        budget: Int = RUN_BUDGET,
        now: Long = System.currentTimeMillis(),
    ): Job? {
        refusal(deck, kit, first)?.let { said = it; return null }
        val id = deck.id!!
        val setup = MapperSetup(deck, first, hands.coerceIn(1, Mapper.MOST_HANDS), seed, budget)
        val plan = Mapper.Plan(setup, kit)
        return launchRun(Running(Kind.HANDS, id, first, plan.deals.size)) { posted ->
            // Made on the deck as it is under its earlier print: kept, not staled, by the upgrade to print 2.
            val before = (sides[first] ?: MapperSide()).adopted(deck.also, deck.fingerprint).library
            val t0 = System.nanoTime()
            val (run, lib) = Mapper.run(setup, kit, before, stop = { stopping }, progress = { p -> posted(p.done, p.total, t0) }, now = now)
            write(id, first, library = lib, run = run)
            MapperReport.run(run)
        }
    }

    /** Every line of the library played again on [deck] as it is: the boards that still land live again, the rest stale. */
    fun startCheck(deck: GoldfishDeck, kit: GoldfishKit, first: Boolean = this.first): Job? {
        val id = deck.id ?: run { said = "Save the deck first."; return null }
        if (running != null || id != deckId || !loaded) return null
        sides[first]?.unreadable?.takeIf { it.isNotEmpty() }?.let { said = "${it.joinToString()} could not be read; nothing is written over it."; return null }
        val lib = (sides[first] ?: MapperSide()).adopted(deck.also, deck.fingerprint).library
        if (lib.boards.isEmpty()) return null
        return launchRun(Running(Kind.CHECK, id, first, lib.boards.size)) { _ ->
            val scripts = Mapper.scripts(deck, kit)
            val main = deck.main.map(kit::canonical)
            val extra = deck.extra.map(kit::canonical)
            val next = lib.rebased(deck.fingerprint, scripts).revalidated(main, extra, kit)
            write(id, first, library = next)
            val live = next.boards.count { !it.stale }
            "Played every line again: ${GoldfishWords.count(live)} of ${GoldfishWords.count(next.boards.size)} boards still land on the deck as it is."
        }
    }

    private fun launchRun(r: Running, body: suspend (posted: (Int, Int, Long) -> Unit) -> String): Job {
        said = null
        stopping = false
        running = r
        progress = Progress(0, r.total, 0)
        val last = AtomicLong(0L)
        val posted: (Int, Int, Long) -> Unit = { done, total, t0 ->
            val t = System.nanoTime() / 1_000_000
            val was = last.get()
            if (done == total || (t - was >= POST_MS && last.compareAndSet(was, t))) {
                val ms = (System.nanoTime() - t0) / 1_000_000
                scope.launch { if (running === r && done >= (progress?.done ?: 0)) progress = Progress(done, total, ms) }
            }
        }
        val j = scope.launch {
            try {
                val words = withContext(Dispatchers.Default) { body(posted) }
                said = words
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = "The run failed: ${e.message ?: e::class.simpleName}"
            } finally {
                if (running === r) running = null
                job = null
            }
        }
        job = j
        return j
    }

    /** Stops the run between engine moves: what was mapped is kept and written. */
    fun stop() {
        if (running == null) return
        stopping = true
    }

    /** [library], [run] and [starters] of [id] going [first] written, then put on screen if [id] is still the deck loaded. */
    private suspend fun write(id: String, first: Boolean, library: BoardLibrary? = null, run: MapperRun? = null, starters: StarterRun? = null) {
        work.withLock {
            withContext(Dispatchers.IO) {
                library?.let { atomic(File(effectsDir, MapperPaths.library(id, first)), it.encode()) }
                run?.let { atomic(File(effectsDir, MapperPaths.run(id, first)), it.encode()) }
                starters?.let { atomic(File(effectsDir, MapperPaths.starters(id, first)), it.encode()) }
            }
        }
        withContext(Dispatchers.Main) {
            if (deckId == id) {
                val old = sides[first] ?: MapperSide()
                sides = sides + (first to old.copy(library = library ?: old.library, run = run ?: old.run, starters = starters ?: old.starters))
            }
            revision++
        }
    }

    /** The query on screen saved as a preset named [name] (a new one, or the one of that name replaced), and chosen. */
    fun savePreset(name: String): Job? {
        val id = deckId ?: return null
        val n = name.trim().ifEmpty { return null }
        val old = presets.presets.firstOrNull { it.name.equals(n, ignoreCase = true) }
        val p = query.copy(id = old?.id ?: "p${System.currentTimeMillis()}", name = n, by = BoardPreset.PERSON, why = "")
        return setPresets(id, presets.put(p).copy(chosen = p.id)).also { query = p }
    }

    /** A preset Ai suggests, kept as Ai's with its reason, and put on screen. */
    fun putAiPreset(p: BoardPreset): Job? {
        val id = deckId ?: return null
        val kept = p.copy(by = BoardPreset.AI, id = p.id.ifEmpty { "ai${System.currentTimeMillis()}" })
        query = kept
        return setPresets(id, presets.put(kept).copy(chosen = kept.id))
    }

    fun deletePreset(pid: String): Job? {
        val id = deckId ?: return null
        if (query.id == pid) query = BoardPreset.DEFAULT
        return setPresets(id, presets.remove(pid))
    }

    /** A preset put on screen, and remembered as the one last chosen. */
    fun choosePreset(pid: String) {
        val p = presets.byId(pid) ?: return
        query = p
        val id = deckId ?: return
        if (presets.chosen != pid) setPresets(id, presets.copy(chosen = pid))
    }

    private fun setPresets(id: String, next: MapperPresets): Job {
        presets = next
        return scope.launch {
            work.withLock { withContext(Dispatchers.IO) { atomic(File(effectsDir, MapperPaths.presets(id)), next.encode()) } }
        }
    }

    /** [text] written to [f] through a file beside it, so a reader never sees half a file. */
    private fun atomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    /** The seed typed, or 1. */
    val seed: Long get() = seedText.trim().toLongOrNull() ?: 1L

    /** The hands typed, or [default]. */
    fun hands(default: Int): Int = handsText?.trim()?.toIntOrNull()?.coerceIn(1, Mapper.MOST_HANDS) ?: default

    /** A new seed: the next run deals other hands. */
    fun reroll(now: Long = System.nanoTime()) {
        seedText = ((now xor (now ushr 29)) and 0x7fffffff).toString()
    }

    /**
     * [line] made again (off the frame thread) on [main] and [extra] and handed to [then] on the main thread; one line at a
     * time. A line that no longer plays says why ([MapReplay.Replay.problem]).
     */
    fun replay(line: MapLine, main: List<Int>, extra: List<Int>, kit: GoldfishKit, then: (MapReplay.Replay) -> Unit): Job? {
        if (opening != null) return null
        opening = line
        return scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { MapReplay.of(line, main.map(kit::canonical), extra.map(kit::canonical), kit) }
                then(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = "The line could not be played again: ${e.message ?: e::class.simpleName}"
            } finally {
                opening = null
            }
        }
    }

    /**
     * The studio's picture (`--mapper=demo`): [deck]'s starter table and [hands] dealt hands mapped here, synchronously, into
     * memory only — nothing is written ([counted] false: the run's counts left out; [empty]: no board yet, the page before any run). Never in the app.
     */
    fun demo(deck: GoldfishDeck, kit: GoldfishKit, hands: Int, budget: Int, counted: Boolean = true, empty: Boolean = false) {
        val id = deck.id ?: "demo"
        if (empty) {
            deckId = id
            sides = mapOf(true to MapperSide())
            loaded = true
            revision++
            return
        }
        val scripts = Mapper.scripts(deck, kit)
        val t = StarterTable.run(deck.main, deck.extra, kit, BoardLibrary(deckId = id, deck = deck.fingerprint, library = scripts), budget = budget)
        val (run, lib) = Mapper.runHere(MapperSetup(deck, true, hands, 1L, budget), kit, t.library)
        val table = StarterRun(deckId = id, deck = deck.fingerprint, library = scripts, rows = t.rows, moves = t.rows.sumOf { it.moves.toLong() }, budget = budget)
        deckId = id
        sides = mapOf(true to MapperSide(lib, run.takeIf { counted }, table))
        loaded = true
        revision++
    }

    /**
     * The studio's comparison (`--mapper-compare=result`): [a] against [b] compared here, synchronously, on [hands] hands at
     * [budget], and put on screen in the dialog. Never in the app.
     */
    fun demoCompare(a: GoldfishDeck, b: GoldfishDeck, label: String, ask: CompareAsk, kit: GoldfishKit, hands: Int, budget: Int) {
        val setup = CompareSetup(a, b, ask, first, 1L, batch = hands, most = hands, budget = budget, sequential = false)
        val r = kotlinx.coroutines.runBlocking { VersionCompare.run(setup, kit, cache, workers = 1) }
        compared = Compared(label, ask.name, first, 1L, a, b, result = r)
        comparing = true
    }

    /** The studio's "without it" (`--mapper-without=true`): every engine card measured here, synchronously. Never in the app. */
    fun demoWithout(deck: GoldfishDeck, kit: GoldfishKit, ask: CompareAsk, hands: Int, budget: Int) {
        val rows = kotlinx.coroutines.runBlocking { Ablation.engine(deck, Ablation.Copies.ONE, ask, kit, cache, first, 1L, hands, budget, workers = 1) }
        without = Without(deck.fingerprint, first, ask.name, rows.associateBy { it.card }, hands)
    }

    companion object {
        /** At most this often the progress is posted, in ms. */
        const val POST_MS = 120L

        /** Engine moves a dealt hand's map may spend: a fifth of the starter table's, since a run maps hundreds. */
        const val RUN_BUDGET = 20_000

        /** A comparison's most hands, a batch at a time: it stops sooner once it knows. */
        const val COMPARE_HANDS = 2_000
        const val COMPARE_BATCH = 100

        /** Hands each card is measured on without it. */
        const val WITHOUT_HANDS = 300

        /** Hands a run deals by default: the desk's and a phone's. */
        const val DESK_HANDS = 500
        const val PHONE_HANDS = 100

        /** The workers a run uses. */
        fun workers(): Int = MapWork.workers()
    }
}
