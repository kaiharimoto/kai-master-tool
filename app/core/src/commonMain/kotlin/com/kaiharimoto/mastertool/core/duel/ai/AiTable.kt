package com.kaiharimoto.mastertool.core.duel.ai

/**
 * Where Ai may sit (Phase C, the red team's lead: "on a networked table, Ai's tools read and move the guest's seat" —
 * `DuelPrefs.aiSeat` is 1 by default, and the guest sits at 1). A networked table is two people's: the host at seat 0,
 * the guest at seat 1, each seeing only their own hand. Ai holds neither seat there, so the tools that read or move the
 * table are refused; a deck's combos, house rulings and the duel records stay open, since they touch no seat.
 */
object AiTable {
    /** The tools that read or move the table in play. */
    val TABLE_TOOLS: Set<String> = setOf("duel_state", "duel_act", "duel_peek", "duel_log", "duel_watch", "duel_setup")

    /** `duel_combo`'s actions that read the log or move the table. */
    val COMBO_ON_TABLE: Set<String> = setOf("record", "run")

    /** Why [tool] (with its [action]) is refused at a networked table, or null when it is allowed, or the table is not one. */
    fun refusal(tool: String, action: String?, networked: Boolean): String? {
        if (!networked) return null
        val touches = tool in TABLE_TOOLS || (tool == "duel_combo" && action in COMBO_ON_TABLE)
        return if (!touches) null
        else "This is a networked table: both seats are people's, and the guest's seat is the guest's. You neither read nor move " +
            "it. Help after the duel (duel_records, the replay), or play at a table of your own."
    }
}
