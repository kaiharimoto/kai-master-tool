package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRecord
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.coach.Decisions
import com.kaiharimoto.mastertool.core.duel.coach.Judged
import com.kaiharimoto.mastertool.core.duel.coach.MisplayRate
import com.kaiharimoto.mastertool.core.duel.coach.Review
import com.kaiharimoto.mastertool.core.duel.coach.ReviewBook
import com.kaiharimoto.mastertool.core.duel.coach.ReviewCodec
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import com.kaiharimoto.mastertool.core.duel.search.SearchWords
import com.kaiharimoto.mastertool.core.duel.search.TurnLine
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Reviews of replays (Phase F, F.1; `docs/phases/F.md`): a replay's every decision weighed by the search in use, from each
 * seat's own view, off the frame thread a decision at a time, kept as `<data>/duel/coach/<replay>.json` (synced and backed up
 * with the duel folder) — what the replay bar marks, and what the monthly misplay chart counts.
 */
class Reviews internal constructor(private val h: NeueHolders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dir get() = File(File(Platform.dataDir, "duel"), Review.FOLDER)
    private var job: Job? = null
    private var loaded = false

    /** Every review kept, by replay id. */
    var books by mutableStateOf<Map<String, ReviewBook>>(emptyMap())
        private set

    /** The replay being reviewed, and its place: decisions judged and decisions to judge. */
    var running by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun load() {
        if (loaded) return
        loaded = true
        reload()
    }

    /** Read again (a review synced from another device). */
    fun reload() {
        scope.launch {
            val read = withContext(Dispatchers.IO) { read() }
            if (running == null) books = read
        }
    }

    /** Reviews [id]'s [record]: every decision, by the search in use at Fast, kept as it ends (a stopped review is kept as far as it went). */
    fun start(id: String, record: DuelRecord) {
        if (running != null) return
        load()
        problem = null
        running = id
        job = scope.launch {
            val kit = h.goldfishKit()
            h.ladder.versionsNow()
            val version = h.ladder.inUse
            val strength = SearchPlayer.Strength.FAST
            val prior = searchPrior(h)
            val catalog = h.duel.catalog
            val n = record.entries.size
            val floor = record.entries.indexOfFirst { it.seat != null }.let { if (it < 0) n else it }
            val game = DuelGame(record.header, record.entries, n, DuelGame.of(record).stateAt(n), floor)
            val decisions = withContext(Dispatchers.Default) { Decisions.of(game) }
            progress = 0 to decisions.size
            val judged = ArrayList<Judged>()
            var stopped = false
            try {
                for ((k, d) in decisions.withIndex()) {
                    val ctx = currentCoroutineContext()
                    judged += withContext(Dispatchers.Default) {
                        Review.judge(
                            game, d, kit, prior, version, strength,
                            words = { root, s, seat -> SearchWords.root(root, s, seat, catalog, kit, record.header.seed) },
                            lineWords = { r ->
                                val s = game.stateAt(d.at)
                                val line = r.choice?.root?.let { root -> r.lines.values.firstOrNull { it.root == root } }
                                fun name(code: Int) = h.builder.index.byId(CardId(code))?.name ?: "a card"
                                fun uidName(u: Int) = s.cards[u]?.takeIf { it.token || DuelSight.sees(s, u, d.seat) }?.code?.let(::name) ?: "a card"
                                line?.let { TurnLine.words(it.steps, d.seat, ::name, ::uidName).take(MAX_LINE) }.orEmpty()
                            },
                            cancelled = { !ctx.isActive },
                        )
                    }
                    progress = k + 1 to decisions.size
                }
            } catch (c: CancellationException) {
                stopped = true
            } catch (t: Throwable) {
                problem = "The review stopped: ${t.message ?: t::class.simpleName}"
                stopped = true
            } finally {
                val book = ReviewBook(
                    id, record.header.id, version.name, strength.label, System.currentTimeMillis(), record.header.created,
                    judged.toList(), complete = !stopped && judged.size == decisions.size, steps = n,
                )
                withContext(Dispatchers.IO + NonCancellable) { write(book) }
                books = books + (id to book)
                running = null
                progress = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** A review made elsewhere put beside the rest without being written (the studio's pictures). */
    fun put(b: ReviewBook) {
        loaded = true
        books = books + (b.replay to b)
    }

    /** Mistakes and blunders per 100 of the person's decisions, by month, over every review kept. */
    fun months(of: List<ReviewBook> = books.values.toList()): List<MisplayRate.Month> = MisplayRate.byMonth(of, monthOf = { ms ->
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { "%04d-%02d".format(it.year, it.monthValue) }
    })

    /** Every review, read from the folder when none was read yet (Ai's `duel_review`, which may come before the page). */
    suspend fun readAll(): Map<String, ReviewBook> {
        if (books.isNotEmpty()) return books
        return withContext(Dispatchers.IO) { read() }
    }

    private fun read(): Map<String, ReviewBook> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { f -> ReviewCodec.decode(f.readText()) }.associateBy { it.replay }

    private fun write(b: ReviewBook) {
        dir.mkdirs()
        val target = File(dir, "${b.replay}.json")
        val temp = File(dir, ".${target.name}.tmp")
        temp.writeText(ReviewCodec.encode(b))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private companion object {
        /** The better line, at most this many steps in words. */
        const val MAX_LINE = 8
    }
}
