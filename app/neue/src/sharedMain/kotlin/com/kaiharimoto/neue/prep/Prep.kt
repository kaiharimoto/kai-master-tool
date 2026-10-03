package com.kaiharimoto.neue.prep

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.prep.Drill
import com.kaiharimoto.mastertool.core.prep.DrillStat
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.PrepDoc
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.PrepProfile
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.ui.AppDependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tournament prep for the app's lifetime (1.0.50, `NEUE.md` §4l): the one [PrepDoc] —
 * events, the test games log, the drills, the decklist's details — read once and
 * written whole on every change, one write at a time, the last kept. The Prep page and
 * Ai's prep tools read and change it here, so a game Ai logs is on the page at once.
 */
class Prep(private val deps: AppDependencies, private val scope: CoroutineScope) {
    var doc by mutableStateOf(PrepDoc.EMPTY)
        private set

    var loaded by mutableStateOf(false)
        private set

    private val lock = Mutex()

    /** The page's tab, kept for the app's lifetime (and set by the studio's `--prep-tab`). */
    var tab by mutableStateOf(PrepTab.PLAN)

    fun load() {
        scope.launch {
            doc = deps.preferencesRepository.loadPrep()
            loaded = true
        }
    }

    fun commit(next: PrepDoc) {
        doc = next
        scope.launch { lock.withLock { deps.preferencesRepository.savePrep(next) } }
    }

    val active: PrepEvent? get() = doc.activeEvent ?: doc.events.firstOrNull()

    fun newId(prefix: String): String = "$prefix-${deps.now().toString(36)}-${(0..0xffff).random().toString(36)}"

    fun today(): String = IsoDate.of(Math.floorDiv(deps.now(), 86_400_000L))

    fun putEvent(event: PrepEvent, activate: Boolean = true) =
        commit(doc.put(event).let { if (activate) it.copy(active = event.id) else it })

    fun select(id: String) = commit(doc.copy(active = id))

    fun removeEvent(id: String) = commit(doc.removeEvent(id))

    fun log(game: TestGame) = commit(doc.record(game))

    fun removeGame(id: String) = commit(doc.removeGame(id))

    fun profile(p: PrepProfile) = commit(doc.copy(profile = p))

    fun check(event: PrepEvent, item: String, on: Boolean) =
        putEvent(event.copy(checked = if (on) (event.checked + item).distinct() else event.checked - item), activate = false)

    /** A drill answered: its Leitner box moves ([Drill.update]). */
    fun drilled(key: String, score: Drill.Score) {
        val stat = doc.drills[key] ?: DrillStat()
        commit(doc.copy(drills = doc.drills + (key to Drill.update(stat, score, deps.now()))))
    }
}
