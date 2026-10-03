package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Command mode's Spotlight (1.0.87), a part of [Duels]: the box's state and the lines made from it, per seat. [Duels]
 * forwards every member under its own name, so the page reaches it as `duels.spotlight`.
 */
internal class DuelSpotlightState(private val d: Duels) {
    /** The Spotlight (kai's direction C): open with its line, or shut (null). The page draws it over the table. */
    var spotlight by mutableStateOf<com.kaiharimoto.mastertool.core.duel.text.Spotlight.State?>(null)

    /**
     * The lines made from the Spotlight per seat, oldest first, the last fifty each kept (`<data>/duel/lines.txt`, a
     * line `seat<TAB>line`): ↑ on an empty box. Per seat, because a line names cards — "set mirror force s2" typed at
     * one seat of a hot-seat must never be recalled at the other (the red team).
     */
    var lineHistories by mutableStateOf<Map<Int, List<String>>>(emptyMap())

    /** The lines made at the seat at the bottom of the table. */
    val lineHistory: List<String> get() = lineHistories[d.bottom].orEmpty()

    /** Whether the Spotlight's own field has the keyboard (it reports it), so keys typed before it does are the box's. */
    var spotlightTyping by mutableStateOf(false)

    /** Bumped to take the keyboard to the Spotlight's field. */
    var spotlightFocus by mutableStateOf(0)

    /** What the Spotlight's chosen row touches and where it goes, set by the box for the table's dim (`SpotlightDim`). */
    var spotlightMarks by mutableStateOf<SpotMarks?>(null)

    /** The listening bars' levels, set only by the studio (it has no microphone); null: the microphone's own. */
    var spotlightLevels by mutableStateOf<List<Float>?>(null)

    /**
     * What opened the box and when: a letter key opens it holding that letter, and the same keystroke's typed
     * character can reach the field a moment later — the field drops it once (`SpotlightBox`).
     */
    var spotlightSeed: Triple<String, Char, Long>? = null

    /**
     * Opens the Spotlight on [text] (the letter that opened it, or a line), in [mode] — listening when M is held. An
     * open box keeps its line when it only changes mode. Nothing opens over a replay, or with no duel.
     */
    fun openSpotlight(
        text: String,
        mode: com.kaiharimoto.mastertool.core.duel.text.Spotlight.Mode,
        /** The character the opening keystroke may still type into the field (the letter, or `/`). */
        swallow: Char?,
    ) {
        if (d.shown == null || d.replayer.replay != null) return
        val was = spotlight
        spotlight = if (was != null && text.isEmpty()) was.copy(mode = mode, answer = null, problem = null)
        else com.kaiharimoto.mastertool.core.duel.text.Spotlight.State(text).copy(mode = mode)
        spotlightSeed = swallow?.let { Triple(text, it, Duels.now()) }
        spotlightFocus++
    }

    /** [text] typed at the open box before its field took the keyboard: added to its line. */
    fun typeIntoSpotlight(text: String) {
        val st = spotlight ?: return
        val line = st.text + text
        spotlight = st.typed(line, line.length)
        spotlightFocus++
    }

    fun closeSpotlight() {
        spotlight = null
        spotlightSeed = null
        spotlightTyping = false
    }

    /** [line] made at the bottom seat: kept in its history, newest last. */
    fun rememberLine(line: String) {
        val next = com.kaiharimoto.mastertool.core.duel.text.Spotlight.remember(lineHistory, line)
        if (next == lineHistory) return
        lineHistories = lineHistories + (d.bottom to next)
        writeLines()
    }

    private val linesLock = Mutex()

    /** The histories written out one at a time, each write the newest (a quick second line never loses to the first). */
    fun writeLines() {
        d.scope.launch {
            linesLock.withLock {
                val text = lineHistories.entries.sortedBy { it.key }.flatMap { (seat, lines) -> lines.map { "$seat\t$it" } }.joinToString("\n")
                withContext(Dispatchers.IO) { runCatching { File(d.dir, Duels.LINES).also { it.parentFile?.mkdirs() }.writeText(text) } }
            }
        }
    }
}
