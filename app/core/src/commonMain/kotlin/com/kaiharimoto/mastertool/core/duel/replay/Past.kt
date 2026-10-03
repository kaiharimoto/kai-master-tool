package com.kaiharimoto.mastertool.core.duel.replay

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.Outcome

/**
 * Acting in a phase already gone by (1.0.80, Ai: "'In your End Phase I'll use Trap Trick' came after the
 * table had moved to turn 3 — let a player insert actions into a past phase"): where in the log a move
 * made "in turn 2's End Phase" belongs, so it can be put there with `Replays.insert` and everything after
 * it folds on top. A later entry it makes impossible is struck through, never refused.
 */
object Past {
    /** "t2 ep", "turn 2 end", "2 bp": a turn and a phase; null when it is not one. */
    fun parse(text: String): Pair<Int, DuelPhase>? {
        val w = text.lowercase().replace("turn", "t").replace(Regex("[^a-z0-9]+"), " ").trim().split(" ").filter { it.isNotEmpty() }
        val turn = w.firstNotNullOfOrNull { it.removePrefix("t").toIntOrNull() } ?: return null
        val phase = w.firstNotNullOfOrNull { phaseOf(it) } ?: DuelPhase.END
        return turn to phase
    }

    private fun phaseOf(w: String): DuelPhase? = when (w) {
        "dp", "draw" -> DuelPhase.DRAW
        "sp", "standby" -> DuelPhase.STANDBY
        "m1", "mp1", "main1", "main" -> DuelPhase.MAIN1
        "bp", "battle" -> DuelPhase.BATTLE
        "m2", "mp2", "main2" -> DuelPhase.MAIN2
        "ep", "end" -> DuelPhase.END
        else -> null
    }

    /**
     * The log position just after the last entry played in [turn]'s [phase] — or, when that phase was
     * never entered, after the last entry of the turn before it would have begun. Null when the turn has
     * not been reached yet (that is the present, not the past) or is the one in play at that phase.
     */
    fun indexOf(header: DuelHeader, played: List<DuelEntry>, turn: Int, phase: DuelPhase): Int? {
        var s = DuelSetup.initial(header)
        var at: Int? = null
        played.forEachIndexed { i, e ->
            val next = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
            // Where the table stood once this entry was played.
            if (next.turn == turn && next.phase.ordinal <= phase.ordinal && e.action !is DuelAction.EndTurn) at = i + 1
            // The deal: a turn's own Draw Phase begins there.
            if (next.turn == turn && at == null && i + 1 == played.size) at = i + 1
            s = next
        }
        // Still in that turn and phase or before it: that is now, not the past.
        if (s.turn < turn || (s.turn == turn && s.phase.ordinal <= phase.ordinal)) return null
        return at
    }
}
