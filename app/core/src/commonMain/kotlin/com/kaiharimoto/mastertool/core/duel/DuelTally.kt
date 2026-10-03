package com.kaiharimoto.mastertool.core.duel

/**
 * What this turn has counted so far (1.0.79, Ai's feedback: "a tracker for running counts and locks —
 * Summons this turn would have warned me about Nibiru at 5"): each seat's Normal and Special Summons,
 * the effects it activated by card, and the locks written down. Read off the log, never kept: undo and a
 * replay's scrubbing are right by construction.
 */
data class Tally(
    val turn: Int,
    /** Per seat: Normal Summons (and Sets) of a monster from the hand. */
    val normal: List<Int>,
    /** Per seat: every other Summon to the field, tokens included. */
    val special: List<Int>,
    /** Per seat: card name → how many times it went on the chain this turn. */
    val activations: List<Map<String, Int>>,
    val locks: List<Lock>,
) {
    fun summons(seat: Int): Int = normal[seat] + special[seat]

    /** One line per seat that did anything, and the locks: "Kai: 1 Normal, 4 Special (5 Summons) · Destrier ×2". */
    fun words(s: DuelState): List<String> = buildList {
        val seats = if (s.solo) listOf(0) else listOf(0, 1)
        seats.forEach { i ->
            if (summons(i) == 0 && activations[i].isEmpty()) return@forEach
            val parts = buildList {
                if (summons(i) > 0) add("${normal[i]} Normal, ${special[i]} Special (${summons(i)} Summon${if (summons(i) == 1) "" else "s"})")
                if (activations[i].isNotEmpty()) add(activations[i].entries.joinToString(", ") { (name, n) -> if (n > 1) "$name ×$n" else name })
            }
            add("${com.kaiharimoto.mastertool.core.duel.text.DuelWords.seatName(s, i)}: ${parts.joinToString(" · ")}")
        }
        locks.forEach { l -> add("Lock ${l.id} (${com.kaiharimoto.mastertool.core.duel.text.DuelWords.seatName(s, l.seat)}): ${l.text} — ${com.kaiharimoto.mastertool.core.duel.text.DuelWords.untilWords(l.until)}") }
    }
}

object DuelTally {
    /**
     * The tally as [viewer] may read it (1.0.85): an activation is named only when the viewer could see the
     * card as it went on the chain — a hand card linked, or a Set card chained face-down, is "a card".
     */
    fun of(game: DuelGame, catalog: DuelCatalog, viewer: Int? = null): Tally {
        val s = game.state
        val played = game.played
        // This turn began after the last End Turn.
        val start = played.indexOfLast { it.action is DuelAction.EndTurn } + 1
        val normal = IntArray(2)
        val special = IntArray(2)
        val acts = listOf(LinkedHashMap<String, Int>(), LinkedHashMap<String, Int>())
        var state = game.stateAt(start)
        for (e in played.subList(start, played.size)) {
            when (val a = e.action) {
                is DuelAction.Move -> {
                    val to = a.to
                    val from = state.placeOf(a.uid)
                    val card = state.cards[a.uid]
                    if (card != null && to is Place.Zone && (to.kind == ZoneKind.MONSTER || to.kind == ZoneKind.EMZ) && from !is Place.Zone && from != null) {
                        val seat = to.seat.coerceIn(0, 1)
                        val fromHand = from is Place.Pile && from.kind == PileKind.HAND
                        if (fromHand && (a.how == "normal" || a.how == "tribute" || a.how == "set" || (a.how == null && DuelVerbs.kindOf(card, catalog) == CardKind.MONSTER))) normal[seat]++
                        else if (DuelVerbs.kindOf(card, catalog).let { it == CardKind.MONSTER || it == CardKind.EXTRA_MONSTER }) special[seat]++
                    }
                }
                is DuelAction.Token -> special[a.to.seat.coerceIn(0, 1)]++
                is DuelAction.ChainAdd -> {
                    val seen = a.uid == null || viewer == null || DuelSight.sees(state, a.uid, viewer) ||
                        ((DuelRules.apply(state, e.action, e.seat) as? Outcome.Ok)?.state?.let { DuelSight.sees(it, a.uid, viewer) } == true)
                    val name = if (!seen) "a card" else a.uid?.let { state.cards[it] }?.let { catalog.nameOf(it) } ?: a.note.ifBlank { null }
                    if (name != null) acts[a.seat.coerceIn(0, 1)].let { m -> m[name] = (m[name] ?: 0) + 1 }
                }
                else -> Unit
            }
            state = (DuelRules.apply(state, e.action, e.seat) as? Outcome.Ok)?.state ?: state
        }
        return Tally(s.turn, normal.toList(), special.toList(), acts, s.locks)
    }
}
