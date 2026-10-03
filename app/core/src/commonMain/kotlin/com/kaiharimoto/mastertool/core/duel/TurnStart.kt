package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase

/**
 * Turns that start themselves (1.0.86, `DuelPrefs.autoDraw`): End Turn leaves the next player in the Draw Phase with
 * nothing drawn, so every turn began D, N, N. With it on, the table makes the incoming player's opening itself — the
 * draw (one card, never on the first turn), the Standby Phase, Main Phase 1 — a step at a time, each step a move like
 * any other, so Ai's watches see the draw and each phase entered and left.
 *
 * [next] is the step to make now, read off the table and the log alone, so a draw already made (by hand, or before a
 * pause for Ai) is never made twice; null when the turn is under way, or cannot go on by itself — a chain or an ask
 * open, the duel over, or an empty deck (the player sees it and decides).
 */
object TurnStart {

    /** What every cue tells Ai while turns start themselves, so it never draws twice for its own seat. */
    const val FOR_AI: String =
        "Turns start themselves at this table: after End Turn the table draws for the player whose turn begins (never on turn 1) " +
            "and moves through the Standby Phase to Main Phase 1. Your turn starts in Main Phase 1 with your draw done — never " +
            "`draw` or move the phase to start it."

    fun next(g: DuelGame): DuelAction? = next(g.state, drewThisTurn(g))

    fun next(s: DuelState, drawn: Boolean): DuelAction? {
        // Turn 1 waits for the opening roll's choice (1.0.87).
        if (s.beforeTurnOne) return null
        if (s.chain.isNotEmpty() || s.proposal != null || s.window != null) return null
        if (s.conceded != null || s.seats.any { it.lp <= 0 }) return null
        return when (s.phase) {
            DuelPhase.DRAW -> when {
                drawn || s.turn <= 1 -> DuelAction.Phase(DuelPhase.STANDBY)
                s.seats[s.active].deck.isEmpty() -> null
                else -> DuelAction.Draw(s.active, 1)
            }
            DuelPhase.STANDBY -> DuelAction.Phase(DuelPhase.MAIN1)
            else -> null
        }
    }

    /**
     * Where a turn's play begins in [entries], from [from] (just after its End Turn): past its opening — the draw and the
     * moves to the Standby Phase and Main Phase 1, made by the table or by hand — so a turn recorded as a combo starts in
     * Main Phase 1 and never draws again when it is run.
     */
    fun afterOpening(entries: List<DuelEntry>, from: Int, to: Int = entries.size): Int {
        var k = from
        while (k < to) {
            val a = entries[k].action
            val opening = a is DuelAction.Draw || (a is DuelAction.Phase && (a.phase == DuelPhase.STANDBY || a.phase == DuelPhase.MAIN1))
            if (!opening && !DuelGame.isTalk(a)) break
            k++
        }
        return k
    }

    /** Whether the turn player has drawn since the turn began: after the last End Turn, or the deal on turn 1. */
    fun drewThisTurn(g: DuelGame): Boolean {
        val active = g.state.active
        for (k in g.cursor - 1 downTo g.floor) {
            val a = g.entries[k].action
            if (a == DuelAction.EndTurn) return false
            if (a is DuelAction.Draw && a.seat == active) return true
        }
        return false
    }
}
