package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.search.SearchVersion
import com.kaiharimoto.mastertool.core.duel.search.SearchVersions
import com.kaiharimoto.mastertool.core.duel.search.ValueTuning
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderBook
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderCodec
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderJudge
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderMatch
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderReading
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderRoster
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The ladder (Phase E, E.4; `docs/phases/E.md` §4.5): its book, `<data>/duel/ladder.json`, and the search's versions,
 * `<data>/duel/search/value-<n>.json` (E.5b) — both synced and backed up with the duel folder. The games it owes are played
 * off the frame thread a game at a time, each kept as it ends, so a run stopped goes on where it stood; a candidate's verdict
 * is written into its file once all its deals are played, and the newest that passed is the search in use. A game is
 * counted effort, never time, so the ladder played on one device is the ladder of all.
 */
class Ladders(private val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val file get() = File(dir, LadderCodec.FILE)
    private val versionsDir get() = File(dir, SearchVersions.FOLDER)
    private var job: Job? = null
    private var loaded = false

    var book by mutableStateOf(LadderBook())
        private set

    /** Every search version: the built-in ones and the files. */
    var versions by mutableStateOf(SearchVersions.builtIn)
        private set

    /** The ratings and verdicts from [book]; null until it is first read. */
    var reading by mutableStateOf<LadderReading?>(null)
        private set

    /** A run's place: games played in it and the games it set out to play; null when none runs. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** The game being played, in words. */
    var playing by mutableStateOf<String?>(null)
        private set

    /** Whether the run tunes (makes the next step and plays it) rather than only playing what is owed. */
    var tuning by mutableStateOf(false)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    val running: Boolean get() = progress != null

    val roster: LadderRoster get() = LadderRoster(versions)

    /** The version that plays at the table and in Ai vs Ai: the newest that passed its gate. */
    val inUse: SearchVersion get() = SearchVersions.inUse(versions)

    /** Read once, the first time the ladder is shown, asked about or played with. */
    fun load() {
        if (loaded) return
        loaded = true
        reload()
    }

    /** The versions now, read from their files first if they never were: what a search seat plays (on the frame thread; a few small files). */
    fun versionsNow(): List<SearchVersion> {
        if (!loaded) {
            versions = readVersions()
            load()
        }
        return versions
    }

    /** Read again (another device's games or versions arrived through sync). */
    fun reload() {
        scope.launch {
            val (b, v) = withContext(Dispatchers.IO) { read() to readVersions() }
            if (running) return@launch
            book = b
            versions = v
            reading = withContext(Dispatchers.Default) { LadderReading.of(b, LadderRoster(v)) }
        }
    }

    /** The reading as it stands, read from the book first when it never was (Ai's `duel_ladder`). */
    suspend fun current(): LadderReading {
        reading?.let { return it }
        val (b, v) = withContext(Dispatchers.IO) { read() to readVersions() }
        val r = withContext(Dispatchers.Default) { LadderReading.of(b, LadderRoster(v)) }
        withContext(Dispatchers.Main) {
            if (reading == null && !running) {
                loaded = true
                book = b
                versions = v
                reading = r
            }
        }
        return r
    }

    /** Plays every game the ladder owes, one at a time, keeping each as it ends; a candidate is judged as its last deal ends. */
    fun run() = start(tune = false)

    /**
     * Tunes (E.5b, [ValueTuning]): plays what is owed, then makes the next step from the version in use and plays its deals
     * against it, the gate judging each, one step after another until every step has been tried or the person stops.
     */
    fun tune() = start(tune = true)

    /**
     * Keeps a version Ai proposed ([SearchVersions.PROPOSED], `value_propose`): a file of its own, judged against the version
     * in use once its deals are played. Never in use before it passes.
     */
    suspend fun propose(v: SearchVersion): SearchVersion {
        val all = versionsNow()
        val kept = v.copy(n = SearchVersions.next(all), parent = inUse.n, source = SearchVersions.PROPOSED, verdict = null, at = System.currentTimeMillis())
        withContext(Dispatchers.IO) { writeVersion(kept) }
        versions = SearchVersions.all(versions.filter { it.source != SearchVersions.BUILT_IN } + kept)
        reading = withContext(Dispatchers.Default) { LadderReading.of(book, roster) }
        return kept
    }

    private fun start(tune: Boolean) {
        if (running) return
        problem = null
        tuning = tune
        job = scope.launch {
            progress = 0 to 0
            try {
                var played = 0
                while (true) {
                    val owed = withContext(Dispatchers.Default) { LadderRun.owed(book, roster) }
                    if (owed.isEmpty()) {
                        if (!tune) break
                        // The next step from the version in use; none left: the sweep is done until one passes.
                        val step = ValueTuning.next(versions, System.currentTimeMillis()) ?: break
                        withContext(Dispatchers.IO) { writeVersion(step) }
                        versions = SearchVersions.all(versions.filter { it.source != SearchVersions.BUILT_IN } + step)
                        continue
                    }
                    progress = played to played + owed.size
                    for (m in owed) {
                        playing = words(m)
                        val r = roster
                        val g = withContext(Dispatchers.Default) { LadderRun.play(m, System::currentTimeMillis, r) }
                        book = book.with(g)
                        withContext(Dispatchers.IO) { write(book) }
                        judge()
                        reading = withContext(Dispatchers.Default) { LadderReading.of(book, roster) }
                        played++
                        progress = played to (progress?.second ?: played)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                problem = "The ladder stopped: ${t.message ?: t::class.simpleName}"
            } finally {
                progress = null
                playing = null
                tuning = false
            }
        }
    }

    /** Every candidate whose gate deals are all played, and no verdict yet: judged, and the verdict kept in its file. */
    private suspend fun judge() {
        val judged = withContext(Dispatchers.Default) { LadderJudge.judge(book, versions, System.currentTimeMillis()) }
        for (v in judged) {
            withContext(Dispatchers.IO) { writeVersion(v) }
            versions = SearchVersions.all(versions.filter { it.source != SearchVersions.BUILT_IN && it.n != v.n } + v)
        }
    }

    /** Stops a run where it stands: the games kept so far stay, and Play or Tune goes on from there. */
    fun stop() {
        job?.cancel()
        job = null
        progress = null
        playing = null
        tuning = false
    }

    private fun words(m: LadderMatch): String {
        val names = m.players.map { roster.of(it)?.name ?: it }
        return "Seed ${m.seed.n}: ${names[0]} against ${names[1]}"
    }

    private fun read(): LadderBook = runCatching { if (file.exists()) LadderCodec.decode(file.readText()) else LadderBook() }.getOrDefault(LadderBook())

    private fun readVersions(): List<SearchVersion> = SearchVersions.all(
        versionsDir.listFiles().orEmpty().filter { it.name.startsWith("value-") && it.name.endsWith(".json") }
            .mapNotNull { f -> runCatching { SearchVersions.decode(f.readText()) }.getOrNull() },
    )

    private fun write(b: LadderBook) = atomically(File(dir, LadderCodec.FILE), LadderCodec.encode(b))

    private fun writeVersion(v: SearchVersion) = atomically(File(versionsDir, SearchVersions.file(v.n)), SearchVersions.encode(v))

    private fun atomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}
