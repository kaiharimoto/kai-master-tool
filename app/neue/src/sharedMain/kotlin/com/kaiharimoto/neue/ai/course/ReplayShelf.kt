package com.kaiharimoto.neue.ai.course

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.course.CoursePaths
import com.kaiharimoto.mastertool.core.ai.course.DbReplay
import com.kaiharimoto.mastertool.core.ai.course.DbReplays
import com.kaiharimoto.mastertool.core.ai.course.ReplayLibrary
import com.kaiharimoto.mastertool.core.ai.course.ReplayReading
import com.kaiharimoto.mastertool.core.duel.replay.DbConvert
import com.kaiharimoto.neue.ai.AiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The replay library (1.1.48, kai: "have the replays saved and build a replay library interface"): every DuelingBook replay
 * kept on this computer — the courses' and those the person adds by address — read from what was kept, never from
 * DuelingBook again. Adding one opens it once in the study's browser, as a person would, and keeps what the page receives.
 */
class ReplayShelf(private val ai: AiState) {
    /** The library is open over the app. */
    var open by mutableStateOf(false)

    /** A replay being added: its address, while the browser reads it. */
    var adding by mutableStateOf<String?>(null)
        private set

    /** What went wrong adding one, or what was added, for the person. */
    var said by mutableStateOf<String?>(null)

    /** Bumped on every change, so what the library shows is read again. */
    var version by mutableIntStateOf(0)
        private set

    private val files get() = ai.files
    private val parsed = ConcurrentHashMap<String, DbReplay>()

    fun added(): List<ReplayLibrary.Added> = ReplayLibrary.readIndex(files.read(ReplayLibrary.index()))

    fun entries(): List<ReplayLibrary.Entry> = ReplayLibrary.entries(ai.courses.courses(), added())

    /** [entries] matching [query]: players, course, note, or a card played. */
    fun search(entries: List<ReplayLibrary.Entry>, query: String): List<ReplayLibrary.Entry> =
        ReplayLibrary.search(entries, query) { e -> replay(e)?.let(ReplayReading::cards).orEmpty() }

    /** [e] as DuelingBook sent it, parsed (once each, kept while the app runs). */
    fun replay(e: ReplayLibrary.Entry): DbReplay? = parsed[e.id] ?: raw(e)?.let(DbReplays::parse)?.also { parsed[e.id] = it }

    private fun raw(e: ReplayLibrary.Entry): String? =
        if (e.added) files.read(ReplayLibrary.raw(e.id)) else files.read(CoursePaths.replayRaw(e.course, e.n))

    /**
     * [e]'s games as our own duel logs (1.1.48, [DbConvert]), to play on the Duel page; [codeOf] is the card pool's
     * passcode for a name. Null when what was kept is not a replay.
     */
    fun games(e: ReplayLibrary.Entry, codeOf: (String) -> Int?): DbConvert.Result? =
        raw(e)?.let { DbConvert.convert(it, e.label, codeOf) }

    /** [e] in words, as the study reads it. */
    fun words(e: ReplayLibrary.Entry): String? =
        (if (e.added) files.read(ReplayLibrary.text(e.id)) else files.read(CoursePaths.replayText(e.course, e.n)))
            ?: replay(e)?.let { DbReplays.render(it, "Replay ${e.id} (${e.url})") }

    /** The study's notes on [e], when a course noted it. */
    fun notes(e: ReplayLibrary.Entry): String? = if (e.added) null else files.read(CoursePaths.replayNotes(e.course, e.n))?.takeIf { it.isNotBlank() }

    /**
     * The replay at [url] added to the library: opened once in the study's browser (DuelingBook's own check may want a tick
     * in that window), what the page receives kept and read. Not while a study holds the browser.
     */
    fun add(url: String, note: String = "") {
        if (adding != null) return
        val id = DbReplays.id(url.trim()) ?: return run { said = "That is not a DuelingBook replay's address." }
        entries().firstOrNull { it.id == id }?.let { return run { said = "Already in the library: ${it.label}." } }
        adding = url.trim()
        said = "Opening the replay: if DuelingBook asks to check the browser, tick its box in the browser window."
        ai.scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { ai.courses.receiveReplay(url.trim()) }
                val replay = DbReplays.parse(raw) ?: error("DuelingBook sent something that is not a replay.")
                withContext(Dispatchers.IO) {
                    files.write(ReplayLibrary.raw(id), raw)
                    files.write(ReplayLibrary.text(id), DbReplays.render(replay, "Replay $id (${DbReplays.normal(url) ?: url})"))
                    files.write(ReplayLibrary.index(), ReplayLibrary.writeIndex(ReplayLibrary.add(added(), url.trim(), replay, System.currentTimeMillis(), note.trim())))
                }
                parsed[id] = replay
                said = "Added: ${replay.players.joinToString(" vs ")}, ${replay.games.size} game${if (replay.games.size == 1) "" else "s"}."
                version++
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                said = t.message ?: "The replay could not be read."
            } finally {
                adding = null
            }
        }
    }

    /** An added replay taken out of the library; a course's stays with its course. */
    fun remove(e: ReplayLibrary.Entry) {
        if (!e.added) return
        files.write(ReplayLibrary.index(), ReplayLibrary.writeIndex(ReplayLibrary.remove(added(), e.id)))
        files.file(ReplayLibrary.raw(e.id)).delete()
        files.file(ReplayLibrary.text(e.id)).delete()
        parsed.remove(e.id)
        version++
    }
}
