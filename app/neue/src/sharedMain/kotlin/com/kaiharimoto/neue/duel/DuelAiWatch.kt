package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ai's response triggers (1.0.85), a part of [Duels]: the watches Ai left, the hits waiting for it, a phase change held
 * for its answer, and the marks of who is acting. [Duels.act] checks the person's moves against them; [Duels] forwards
 * every member under its own name. Not core's `DuelTriggers`, which is the arithmetic this reads.
 */
internal class DuelAiWatch(private val d: Duels) {
    /** Ai's watches: its private plan for what it would answer. Never in the log, the record or the network. */
    var watches by mutableStateOf<List<com.kaiharimoto.mastertool.core.duel.ai.Watch>>(emptyList())
    private var nextWatch = 1
    /** Watches that fired and wait for Ai: the page cues it with them as soon as it is free. */
    var fired by mutableStateOf<List<com.kaiharimoto.mastertool.core.duel.ai.Hit>>(emptyList())
    /** A phase change held while Ai decides whether to respond before it: a phase_leave watch fired. */
    var held by mutableStateOf<Duels.Held?>(null)
    /** Ai is answering a trigger: the person's moves wait for it, unless they say Don't wait. */
    var aiAnswering by mutableStateOf(false)
    /** The seat Ai watches as, set by the page while Ai sits at the table; null otherwise. */
    var watcher: Int? = null
    /** A cue given while Ai was still answering, kept for when it is free (1.0.85; before, it was dropped). */
    var queuedCue by mutableStateOf<Pair<String, String>?>(null)
    var releasing = false
    /** Ai is playing its moves out (duel_act, a combo it runs, a move into the past): never the person's moves (1.0.85). */
    var aiActing = false
    /** Stops Ai's answer, set by the page: Don't wait means Ai's late answer never lands (1.0.85). */
    var stopAi: (() -> Unit)? = null

    fun watch(w: com.kaiharimoto.mastertool.core.duel.ai.Watch): com.kaiharimoto.mastertool.core.duel.ai.Watch {
        val kept = w.copy(id = nextWatch++)
        watches = watches + kept
        return kept
    }

    fun unwatch(id: Int?): Int {
        val before = watches.size
        watches = if (id == null) emptyList() else watches.filter { it.id != id }
        return before - watches.size
    }

    fun nextWatchId(): Int = nextWatch

    /** Ai's watches as of now: a turn's watch is gone with its turn. */
    fun liveWatches(): List<com.kaiharimoto.mastertool.core.duel.ai.Watch> {
        val turn = d.game?.state?.turn ?: return emptyList()
        val alive = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.alive(watches, turn)
        if (alive.size != watches.size) watches = alive
        return alive
    }

    /** The seat Ai's watches were left for: they are forgotten only when Ai changes seats, not each time the page opens. */
    var watchSeat: Int? = null
    /** Moves were taken back under Ai's read mark (an undo that kept the talk after it): its next cue says so. */
    var aiTookBack = false

    /** Everything about Ai's answers let go: a new duel, a what-if, a replay, the network, another seat for Ai. */
    fun forgetTriggers(clearWatches: Boolean) {
        if (clearWatches) watches = emptyList()
        fired = emptyList()
        held = null
        aiAnswering = false
    }

    /** Whether the person's table moves wait on Ai now. */
    val waitingOnAi: Boolean get() = aiAnswering || held != null || fired.isNotEmpty()

    fun summonsThisTurn(): (Int) -> Int {
        val n by lazy { d.game?.let { com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.summonsThisTurn(it, d.catalog) } }
        return { seat -> n?.getOrNull(seat) ?: 0 }
    }

    fun fire(hits: List<com.kaiharimoto.mastertool.core.duel.ai.Hit>): List<com.kaiharimoto.mastertool.core.duel.ai.Watch> {
        if (hits.isEmpty()) return emptyList()
        val gone = hits.filter { it.watch.once }.map { it.watch }
        if (gone.isNotEmpty()) watches = watches.filter { w -> gone.none { it.id == w.id } }
        fired = fired + hits
        return gone
    }

    /**
     * The phase change held for Ai, made now: Ai has answered, or the person will not wait. Only onto the table it was
     * held against — if Ai responded (the log moved, a chain is open), the person moves the phase on again themselves
     * once the chain is done; on a replay, the past or the network it is let go (1.0.85, the red team).
     */
    fun releaseHeld() {
        val h = held ?: return
        held = null
        val g = d.game ?: return
        if (d.replayer.replay != null || d.network.role != null) return
        // Ai's words are not a response; a table move of its is.
        val moved = g.cursor < h.cursor || g.played.drop(h.cursor).any { !it.action.social }
        if (moved || g.state.chain.isNotEmpty()) {
            d.problem = "Ai responded — move the phase on again once the chain is done."
            // The opening stops here too: made again at once it would wake Ai a second time for the same step.
            d.opener.autoTurn = null
            return
        }
        releasing = true
        d.opener.autoActing = h.auto
        val wasAi = aiActing
        aiActing = false
        try { d.act(h.actions, h.seat) } finally { releasing = false; d.opener.autoActing = false; aiActing = wasAi }
        d.opener.resumeTurn()
    }

    /** The held phase change taken back (Undo): the once-watches it spent stand again. */
    fun dropHeld() {
        val h = held ?: return
        held = null
        if (h.spent.isNotEmpty()) watches = watches + h.spent.filter { w -> watches.none { it.id == w.id } }
    }

    /** The person goes on without Ai's answer: Ai is stopped, so a late answer never lands on a table moved on. */
    fun dontWait() {
        if (aiAnswering) stopAi?.invoke()
        aiAnswering = false
        fired = emptyList()
        releaseHeld()
        d.opener.resumeTurn()
    }
}
