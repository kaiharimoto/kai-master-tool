package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderBook
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderCodec
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderMatch
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderPlayers
import com.kaiharimoto.mastertool.core.duel.search.ladder.LadderReading
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
 * The ladder (Phase E, E.4; `docs/phases/E.md` §4.5): its book, `<data>/duel/ladder.json` (synced and backed up with the
 * duel folder), the games it still owes played off the frame thread a game at a time — each kept as it ends, so a run
 * stopped goes on where it stood — and the reading worked out from them. A game is counted effort, never time, so the
 * ladder played on one device is the ladder of all: a synced book is read, never played again.
 */
class Ladders(private val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val file get() = File(dir, LadderCodec.FILE)
    private var job: Job? = null
    private var loaded = false

    var book by mutableStateOf(LadderBook())
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

    var problem by mutableStateOf<String?>(null)
        private set

    val running: Boolean get() = progress != null

    /** Read once, the first time the ladder is shown or asked about. */
    fun load() {
        if (loaded) return
        loaded = true
        reload()
    }

    /** Read again (another device's games arrived through sync). */
    fun reload() {
        scope.launch {
            val b = withContext(Dispatchers.IO) { read() }
            if (running) return@launch
            book = b
            reading = withContext(Dispatchers.Default) { LadderReading.of(b) }
        }
    }

    /** The reading as it stands, read from the book first when it never was (Ai's `duel_ladder`). */
    suspend fun current(): LadderReading {
        reading?.let { return it }
        val b = withContext(Dispatchers.IO) { read() }
        val r = withContext(Dispatchers.Default) { LadderReading.of(b) }
        withContext(Dispatchers.Main) {
            if (reading == null && !running) {
                loaded = true
                book = b
                reading = r
            }
        }
        return r
    }

    /** Plays every game the ladder owes, one at a time, keeping each as it ends. */
    fun run() {
        if (running) return
        problem = null
        job = scope.launch {
            val owed = withContext(Dispatchers.Default) { LadderRun.owed(book) }
            progress = 0 to owed.size
            try {
                owed.forEachIndexed { i, m ->
                    playing = words(m)
                    val g = withContext(Dispatchers.Default) { LadderRun.play(m, System::currentTimeMillis) }
                    book = book.with(g)
                    withContext(Dispatchers.IO) { write(book) }
                    reading = withContext(Dispatchers.Default) { LadderReading.of(book) }
                    progress = i + 1 to owed.size
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                problem = "The ladder stopped: ${t.message ?: t::class.simpleName}"
            } finally {
                progress = null
                playing = null
            }
        }
    }

    /** Stops a run where it stands: the games kept so far stay, and Run goes on from there. */
    fun stop() {
        job?.cancel()
        job = null
        progress = null
        playing = null
    }

    private fun words(m: LadderMatch): String {
        val names = m.players.map { LadderPlayers.of(it)?.name ?: it }
        return "Seed ${m.seed.n}: ${names[0]} against ${names[1]}"
    }

    private fun read(): LadderBook = runCatching { if (file.exists()) LadderCodec.decode(file.readText()) else LadderBook() }.getOrDefault(LadderBook())

    private fun write(b: LadderBook) {
        dir.mkdirs()
        val temp = File(dir, ".${LadderCodec.FILE}.tmp")
        temp.writeText(LadderCodec.encode(b))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
