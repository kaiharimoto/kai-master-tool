package com.kaiharimoto.mastertool.core.duel.replay

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelState
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

    /** Where a move put into the past takes its dice from: far from any roll the duel makes in play. */
    private const val PAST_ROLLS = 1_000_000

    /**
     * What [actions] put in after [at] entries of [played] leave to chance, stamped from the duel's own dice for that place
     * in the log (Phase C, the red team: "`duel_act at` … is fishing for a better hand"): the roll is keyed to how many
     * rolls came before that place, never to the log's length, so taking the move back and putting it in again, after a
     * line of chat or none, comes out the same. 1.0.80–1.1.1 keyed it to the log's length.
     */
    fun stamp(header: DuelHeader, played: List<DuelEntry>, at: Int, actions: List<DuelAction>): List<DuelAction> {
        var roll = played.take(at.coerceIn(0, played.size)).count { DuelRandom.rolls(it.action) }
        return actions.map { a -> if (DuelRandom.rolls(a)) DuelRandom.stamp(a, DuelRandom.forRoll(header.seed, PAST_ROLLS + roll++)) else a }
    }

    /**
     * Why putting [added] (made by [seat]) in after [at] entries of [played] would re-deal what chance gave later — a later
     * draw taking other cards from the top of the Deck, a later random pick other cards — or null when every one comes out
     * the same. A move in a phase gone by may change the table after it (a later move it makes impossible is struck
     * through), but never what was drawn since: that would be fishing for a better hand. Null too when [added] does not fit
     * the table at [at] — the caller says that itself.
     */
    fun redeals(header: DuelHeader, played: List<DuelEntry>, at: Int, added: List<DuelAction>, seat: Int?): String? {
        val k = at.coerceIn(0, played.size)
        var old = DuelSetup.fold(header, played.take(k)).first
        var new = DuelRules.applyAll(old, added, seat).first ?: return null
        for (e in played.drop(k)) {
            val a = e.action
            val o = (DuelRules.apply(old, a, e.seat) as? Outcome.Ok)?.state
            val n = (DuelRules.apply(new, a, e.seat) as? Outcome.Ok)?.state
            val was = o?.let { dealt(old, it, a) }
            if (was != null && was != n?.let { dealt(new, it, a) }) {
                val what = if (a is DuelAction.Draw) "drawn" else "picked at random"
                return "That would change the cards $what in turn ${old.turn} — a move in a phase gone by never re-deals what was dealt since"
            }
            if (o != null) old = o
            if (n != null) new = n
        }
        return null
    }

    /** The cards [a] dealt by chance going from [before] to [after]: a draw's, a random pick's; null for anything else. */
    private fun dealt(before: DuelState, after: DuelState, a: DuelAction): List<Int>? = when (a) {
        is DuelAction.Draw -> before.seats.getOrNull(a.seat)?.deck?.take(a.n)
        is DuelAction.Pick -> (0..1).flatMap { seat ->
            val was = before.seats[seat].pile(a.to.kind).toSet()
            after.seats[seat].pile(a.to.kind).filter { it !in was }
        }
        else -> null
    }
}
