package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.match.series.OpponentRun
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesCodec
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesCoverage
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesDeck
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesOpponent
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesRun
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesRunner
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simulated series (Phase G part two, G2.1; `docs/phases/G2.md`): two versions of the builder's deck played in best-of-three
 * matches against the field of its web, by the search in use at Fast, off the frame thread a few matches at a time, each
 * kept as it ends in `<data>/duel/series/<id>.json` (synced and backed up with the duel folder) — so a run stopped goes on
 * where it stood. The person presses Start; Ai only offers.
 */
class Series internal constructor(private val h: NeueHolders) {
    private val scope = Background.scope("simulated series", Dispatchers.Main)
    private val dir get() = File(File(Platform.dataDir, "duel"), SeriesCodec.FOLDER)
    private var job: Job? = null
    private var loaded = false

    @Volatile
    private var stopping = false

    /** Every comparison kept, by id. */
    var runs by mutableStateOf<Map<String, SeriesRun>>(emptyMap())
        private set

    /** The comparison playing now. */
    var running by mutableStateOf<String?>(null)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    /** The comparison "Compare with…" opens on when it next opens (the studio's pictures); read once. */
    var showing by mutableStateOf<String?>(null)

    /** A run made elsewhere put beside the rest without being written (the studio's pictures). */
    fun put(r: SeriesRun) {
        loaded = true
        runs = runs + (r.id to r)
    }

    fun load() {
        if (loaded) return
        loaded = true
        reload()
    }

    /** Read again (a run synced from another device). */
    fun reload() {
        scope.launch {
            val read = withContext(Dispatchers.IO) { read() }
            if (running == null) runs = read
        }
    }

    /** Every run, read from the folder when none was read yet (Ai's `match_results`). */
    suspend fun readAll(): Map<String, SeriesRun> {
        if (runs.isNotEmpty()) return runs
        return withContext(Dispatchers.IO) { read() }
    }

    private fun read(): Map<String, SeriesRun> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { f -> SeriesCodec.decode(f.readText()) }.associateBy { it.id }

    /** The field a comparison plays: the other decks of the web the builder's deck is in, with their shares and both sides' plans. */
    class Field(val web: String?, val opponents: List<SeriesOpponent>, val coverage: List<SeriesCoverage>)

    /**
     * The field for deck [me] ([meName]) in versions [a] and [b]: the other decks of its web, their shares, their plans against
     * it, and each version's plans against them — [a]'s from its siding, [b]'s from [bPlans] when given (a suggested side deck,
     * G2.2), else from deck [bDeckId]'s siding when it is another deck, else [a]'s.
     */
    suspend fun field(
        me: String,
        meName: String,
        a: SeriesDeck,
        b: SeriesDeck,
        bDeckId: String?,
        kit: GoldfishKit,
        bPlans: Map<String, Pair<SidePlan, SidePlan>>? = null,
    ): Field {
        val state = h.builder
        val webs = h.webs
        val web = webs.webOf(me)
        val mineStored = webs.stored(me)
        val mySiding = mineStored?.let { webs.sidingOf(it, state) }
        val bSiding = bDeckId?.takeIf { it != me }?.let { webs.stored(it) }?.let { webs.sidingOf(it, state) }
        val entries = web?.entries.orEmpty().filter { !it.mine && it.deckId != me && it.deckId != bDeckId }
        val opponents = entries.mapNotNull { e ->
            val stored = webs.stored(e.deckId) ?: return@mapNotNull null
            val deck = webs.deckOf(stored, state)
            val theirs = webs.sidingOf(stored, state).against(me, meName)
            val mine = mySiding?.against(e.deckId, stored.entry.name)
            val bs = (bSiding ?: mySiding)?.against(e.deckId, stored.entry.name)
            val them = SeriesDeck(e.deckId, stored.entry.name, deck, theirs?.plan(Turn.FIRST) ?: SidePlan(), theirs?.plan(Turn.SECOND) ?: SidePlan())
            SeriesOpponent(
                them, (e.share ?: 1).toDouble().coerceAtLeast(0.0),
                listOf(
                    (mine?.plan(Turn.FIRST) ?: SidePlan()) to (mine?.plan(Turn.SECOND) ?: SidePlan()),
                    bPlans?.get(e.deckId) ?: ((bs?.plan(Turn.FIRST) ?: SidePlan()) to (bs?.plan(Turn.SECOND) ?: SidePlan())),
                ),
            )
        }
        val coverage = listOf(SeriesCoverage.of(a.id, a.name, a.deck, kit), SeriesCoverage.of(b.id, b.name, b.deck, kit)) +
            opponents.map { SeriesCoverage.of(it.deck.id, it.deck.name, it.deck.deck, kit) }
        return Field(web?.name, opponents, coverage)
    }

    /** Why [field] cannot be played for [a] and [b], or null: no opponent, or your versions the engine plays too little of. */
    fun refusal(field: Field): String? = when {
        field.opponents.isEmpty() -> "Put the deck in a web with the decks you expect to face (Format), and it plays them."
        field.coverage.take(2).any { it.share < SeriesCoverage.FLOOR } ->
            "The engine plays less than half of ${field.coverage.take(2).first { it.share < SeriesCoverage.FLOOR }.name}: write its cards' effects first."
        else -> null
    }

    /** Starts the comparison of [a] against [b] (versions of deck [deckId]) over [field] in [pairs] seed pairs; null when one is already playing. */
    fun start(deckId: String, a: SeriesDeck, b: SeriesDeck, label: String, field: Field, pairs: Int, kit: GoldfishKit): String? {
        if (running != null) return null
        load()
        problem = null
        stopping = false
        val version = h.ladder.inUse
        val index = h.builder.index
        val run = SeriesRun(
            "s${System.currentTimeMillis()}", deckId, label, a.name, b.name,
            Ledger.fingerprint(a.deck, index::byId), Ledger.fingerprint(b.deck, index::byId), pairs, version.name, SearchPlayer.Strength.FAST.label,
            field.opponents.map { OpponentRun(it.deck.id, it.deck.name, it.share) }, field.coverage, System.currentTimeMillis(),
        )
        running = run.id
        runs = runs + (run.id to run)
        play(run, a, b, field, kit)
        return run.id
    }

    /** Plays what [run] owes, a few matches at a time, each kept as it ends; Stop leaves what is kept. */
    private fun play(run: SeriesRun, a: SeriesDeck, b: SeriesDeck, field: Field, kit: GoldfishKit) {
        val index = h.builder.index
        val version = h.ladder.inUse
        val isExtra: (CardId) -> Boolean = { index.byId(it)?.isExtraDeck == true }
        val workers = MapWork.workers().coerceAtMost(MAX_WORKERS)
        job = scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    SeriesRunner.run(
                        run, listOf(a, b), field.opponents, kit,
                        // Each seat guesses the other's hidden cards from the list it faces, as in testing.
                        players = { _, against ->
                            val prior = Prior.Decklist(against.main.map { it.value })
                            SearchPlayer({ prior }, SearchPlayer.Strength.FAST, cancelled = { stopping }, version = version)
                        },
                        isExtra = isExtra, workers = workers, stop = { stopping },
                    ) { kept ->
                        withContext(Dispatchers.IO) { write(kept) }
                        withContext(Dispatchers.Main) { runs = runs + (kept.id to kept) }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                problem = "The series stopped: ${t.message ?: t::class.simpleName}"
            } finally {
                withContext(NonCancellable) { running = null }
            }
        }
    }

    /** Stops the run where it stands: the matches kept stay, read as far as they go. */
    fun stop() {
        stopping = true
    }

    fun delete(id: String) {
        if (running == id) return
        runs = runs - id
        scope.launch(Dispatchers.IO) { File(dir, "$id.json").delete() }
    }

    private fun write(r: SeriesRun) {
        dir.mkdirs()
        val target = File(dir, "${r.id}.json")
        val temp = File(dir, ".${target.name}.tmp")
        temp.writeText(SeriesCodec.encode(r))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private companion object {
        /** At most this many matches at once: the search is heavy, and the window must stay smooth. */
        const val MAX_WORKERS = 4
    }
}
