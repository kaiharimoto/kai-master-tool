package com.kaiharimoto.mastertool.core.start

import com.kaiharimoto.mastertool.core.update.AppVersion
import kotlinx.serialization.Serializable

/**
 * The setup offered as the app opens (1.0.69, kai: "for new users, and older versions who are updating
 * to new versions, have features that would be needed for the initial setup be offered on startup").
 *
 * A new person is walked through everything; someone updating sees only the steps that arrived after the
 * version they last opened, and never one already done (sync set up, Ai connected, the art here) or one
 * they skipped. Each step says when it arrived on each track — the desktop's `1.0.x` and the APK's
 * `1.3.x` count separately.
 */
enum class StartStep(
    val id: String,
    /** The desktop release it arrived in. */
    val desk: String,
    /** The APK release it arrived in. */
    val apk: String,
    /** Offered to a new person only: someone updating has already made these choices. */
    val newOnly: Boolean = false,
) {
    LOOK("look", "1.0.0", "1.3.0", newOnly = true),
    DECKS("decks", "1.0.0", "1.3.0", newOnly = true),
    SYNC("sync", "1.0.68", "1.3.45"),
    AI("ai", "1.0.43", "1.3.20"),
    ART("art", "1.0.10", "1.3.0"),
    ;

    companion object {
        fun of(id: String): StartStep? = entries.firstOrNull { it.id == id }
    }
}

/** What is set up on this device already, so a step that is done is never offered. */
data class StartState(
    val hasDecks: Boolean,
    val syncOn: Boolean,
    val aiEnabled: Boolean,
    val aiConnected: Boolean,
    /** Every card's picture is here, or the person turned the download off. */
    val artSettled: Boolean,
)

/** This device's own record of the setup (a field of `NeuePreferences`, never synced). */
@Serializable
data class StartPrefs(
    /** The version that last opened here; empty before 1.0.69 (or on a new install). */
    val seen: String = "",
    /** Steps finished or skipped: never offered again. */
    val done: List<String> = emptyList(),
)

object StartSteps {
    /**
     * The steps to offer now. [current] is this build's version on its track ([android] says which);
     * [prefs] what this device has seen. A new install — nothing seen, no decks — gets everything that
     * is not done; an update gets what arrived after [StartPrefs.seen] (everything, when it is empty:
     * the version before 1.0.69 is not known, and each step is asked about once). Opening the same
     * version again offers nothing.
     */
    fun pending(current: String, prefs: StartPrefs, state: StartState, android: Boolean): List<StartStep> {
        val now = AppVersion.parse(current)
        val seen = AppVersion.parse(prefs.seen)
        if (prefs.seen.isNotBlank() && seen >= now) return emptyList()
        val isNew = prefs.seen.isBlank() && !state.hasDecks
        return StartStep.entries.filter { step ->
            val arrived = AppVersion.parse(if (android) step.apk else step.desk)
            step.id !in prefs.done &&
                (isNew || (!step.newOnly && (prefs.seen.isBlank() || arrived > seen) && arrived <= now)) &&
                !settled(step, state)
        }
    }

    fun isNew(prefs: StartPrefs, state: StartState): Boolean = prefs.seen.isBlank() && !state.hasDecks

    private fun settled(step: StartStep, s: StartState): Boolean = when (step) {
        StartStep.LOOK -> false
        StartStep.DECKS -> s.hasDecks
        StartStep.SYNC -> s.syncOn
        StartStep.AI -> s.aiConnected || !s.aiEnabled
        StartStep.ART -> s.artSettled
    }
}
