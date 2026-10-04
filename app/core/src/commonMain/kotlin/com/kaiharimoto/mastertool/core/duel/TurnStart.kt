package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase

/**
 * Turns that draw for themselves (1.0.86, `DuelPrefs.autoDraw`): End Turn leaves the next player in the Draw Phase with
 * nothing drawn. With it on, the table makes the incoming player's draw itself (one card, never on the first turn), a
 * move like any other, so Ai's watches see it. The phases are the player's (kai, 1.0.93: "I don't like that it's
 * automatically skipping to standby phase and main phase, let the player manually do that"): the turn stays in the Draw
 * Phase, where the other seat may still answer the draw, until its player moves on (N) — 1.0.86–1.0.92 went on through
 * the Standby Phase to Main Phase 1 by themselves.
 *
 * [next] is the step to make now, read off the table and the log alone, so a draw already made (by hand, or before a
 * pause for Ai) is never made twice; null when the turn is under way, or cannot go on by itself — a chain or an ask
 * open, the duel over, or an empty deck (the player sees it and decides).
 */
object TurnStart {

    /** What every cue tells Ai while turns start themselves, so it never draws twice for its own seat. */
    const val FOR_AI: String =
        "The table draws for each turn's player at this table: after End Turn it draws one card for the player whose turn begins " +
            "(never on turn 1) and leaves the turn in the Draw Phase. On your turn your draw is done for you — never `draw` for it — " +
            "and you move on through the Standby Phase to Main Phase 1 yourself, as a player does."

    fun next(g: DuelGame): DuelAction? = next(g.state, drewThisTurn(g))

    fun next(s: DuelState, drawn: Boolean): DuelAction? {
        // Turn 1 waits for the opening roll's choice (1.0.87).
        if (s.beforeTurnOne) return null
        if (s.chain.isNotEmpty() || s.proposal != null || s.window != null) return null
        if (s.conceded != null || s.seats.any { it.lp <= 0 }) return null
        return when (s.phase) {
            // The draw alone: the phases from here are the player's (1.0.93).
            DuelPhase.DRAW -> when {
                drawn || s.turn <= 1 -> null
                s.seats[s.active].deck.isEmpty() -> null
                else -> DuelAction.Draw(s.active, 1)
            }
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
