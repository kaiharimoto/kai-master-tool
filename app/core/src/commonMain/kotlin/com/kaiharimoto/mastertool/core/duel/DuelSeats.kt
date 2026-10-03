package com.kaiharimoto.mastertool.core.duel

/**
 * Whose cards the person at the table plays (1.0.86). An open pile laid over the field used to answer a
 * right-click with its owner's obvious verb whoever owned it, so the other player's GY offered
 * "Activate" — and activated it as them. Their pile is for pointing at and reading, unless the person
 * plays both seats.
 */
object DuelSeats {
    /**
     * The person plays both seats: a hot-seat (not a networked table, no Ai at the other seat) with both
     * hands face-up — the tester's table.
     */
    fun playsBoth(prefs: DuelPrefs, networked: Boolean, aiSeated: Boolean): Boolean =
        !networked && !aiSeated && prefs.knowledge == DuelPrefs.KNOW_ALL

    /** A card in [pileSeat]'s open pile answers with its verbs, not only Target. */
    fun stripPlays(s: DuelState, pileSeat: Int, bottom: Int, playsBoth: Boolean): Boolean =
        s.solo || pileSeat == bottom || playsBoth
}
