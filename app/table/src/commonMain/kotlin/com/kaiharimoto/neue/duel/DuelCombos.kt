package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.ai.ComboBook
import com.kaiharimoto.mastertool.core.duel.ai.ComboCodec
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/**
 * Combos and moves played out a step at a time (1.0.76), a part of [Duels] (H.3, moved unchanged): a deck's combos read
 * and kept, and a batch of steps checked whole and then played one at a time — a combo, or Ai's turn. [Duels] forwards
 * every member here under its own name.
 */
class DuelCombos(private val d: Duels) {
    /** Whether moves are being played out a step at a time (a combo, Ai's turn); Esc stops them. */
    var playing by mutableStateOf(false)
    var stopRequested = false
    var combosOpen by mutableStateOf(false)
    /** The last turn Ai was asked to play, so a turn is asked for once. */
    var aiAskedTurn = -1

    /** The deck a seat is playing, for its combos: the duel's own, else none. */
    fun deckOf(seat: Int): String? = d.shown?.header?.seats?.getOrNull(seat)?.deckId

    suspend fun combos(deckId: String): ComboBook =
        d.store.read(ComboCodec.path(deckId))?.let(ComboCodec::decode) ?: ComboBook()

    /**
     * A deck's combos read where the caller is (Phase C stage 2): for a cue's guide block, built as the cue is sent, as
     * the deck's guide is read. A few kilobytes at most.
     */
    fun combosNow(deckId: String): ComboBook =
        runCatching { d.store.readNow(ComboCodec.path(deckId))?.let(ComboCodec::decode) }.getOrNull() ?: ComboBook()

    suspend fun saveCombos(deckId: String, book: ComboBook) {
        d.io.withLock { d.store.write(ComboCodec.path(deckId), ComboCodec.encode(book)) }
    }

    /**
     * [steps] played on the table for [seat], checked whole first (nothing moves if a step cannot be
     * done), then one step at a time [paceMs] apart, each its own step of undo. Returns what happened in
     * words: the steps played, and where it stopped if the person stopped it or the table changed under it.
     */
    suspend fun playOut(steps: List<String>, seat: Int, paceMs: Long, viewer: Int? = seat, guard: Boolean = false): Duels.PlayReport {
        val g = d.game ?: return Duels.PlayReport("There is no duel on the table.", 0, false)
        // Played on the live table only: never into an open replay (1.0.85).
        if (d.replayer.replay != null) return Duels.PlayReport("A replay is open on the table; close it first.", 0, false)
        // The duel's own secret, so `oh2` is the card the brief shows there; Ai's words in any step kept from naming its
        // hidden cards (Phase C stage 2: after a `;` and in a combo's steps they reached the record as typed).
        val plan = ComboRunner.plan(g.state, seat, steps, d.catalog, g.header.seed).let { if (guard && it.ok) ComboRunner.redacted(g.state, seat, it, d.catalog) else it }
        if (!plan.ok) return Duels.PlayReport("Nothing was played. ${plan.problem}", 0, false)
        // Ai's lines do only what a player may to cards it cannot see, as the network's guest's do (Phase C, DuelReach).
        if (guard) ComboRunner.reach(g.state, seat, plan)?.let { why ->
            return Duels.PlayReport("Nothing was played. $why: a player does not do that to a card they cannot see.", 0, false)
        }
        playing = true
        stopRequested = false
        // Ai's play-out marks its own moves only, never the person's made between its paced steps (1.0.85).
        val byAi = d.aiWatch.aiActing
        d.aiWatch.aiActing = false
        var done = 0
        // What each step really did, in the log's words as [viewer] reads them (1.0.79, Ai: "report each op's
        // real result … 'Played all N steps' showed up while the board hadn't changed").
        val said = mutableListOf<String>()
        fun report(head: String) = Duels.PlayReport((listOf(head) + said).joinToString("\n"), done, done > 0)
        try {
            for ((text, actions) in plan.steps) {
                if (stopRequested) return report("Stopped by the person after $done of ${plan.steps.size} steps:")
                val before = d.game?.cursor ?: 0
                d.aiWatch.aiActing = byAi
                val acted = try { d.act(actions, seat) } finally { d.aiWatch.aiActing = false }
                if (!acted) return report("Played $done of ${plan.steps.size}; “$text” no longer fits the table: ${d.problem ?: "refused"}. What was played:")
                done++
                val now = d.game
                val lines = if (d.network.role == Duels.NetRole.GUEST || now == null) listOf("sent to the host")
                else DuelHost.lines(now, before, viewer, d.catalog, d.folds(now)).map { it.text }
                said += "$done. $text → ${lines.joinToString("; ").ifBlank { "no change on the table" }}"
                if (paceMs > 0 && done < plan.steps.size) delay(paceMs)
            }
        } finally {
            playing = false
            d.aiWatch.aiActing = byAi
        }
        return report("Played all ${plan.steps.size} steps:")
    }
}
