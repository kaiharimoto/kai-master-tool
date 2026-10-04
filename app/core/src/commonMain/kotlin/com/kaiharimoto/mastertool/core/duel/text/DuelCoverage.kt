package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.duel.DuelVerb

/**
 * Everything a mouse does on the duel's table, and the words that do it (1.0.87, Command mode: "I can win with just
 * typing too and not a mouse"). One row per gesture — every [DuelVerb], every kind of drop ([intent], the keys of
 * [com.kaiharimoto.mastertool.core.duel.DuelDrop]'s answers), the chain well, the LP pad, the piles, the phase
 * controls, the proposal bar, Ai's cues, the seat swap — with a typed form that does it.
 *
 * `DuelCoverageTest` parses every row on a table set up for it and fails when a verb or a drop has no row: a new
 * gesture is not done until it can be typed. The help's Command mode section can render this table.
 */
object DuelCoverage {

    /**
     * [gesture]: what the mouse does; [typed]: the words; [verb] / [intent]: what it covers; [needs]: what the table
     * must hold for the words to apply — [NEEDS_CHAIN], [NEEDS_PROPOSAL], or nothing (a two-seat table in the turn
     * player's Battle Phase, as the test sets it up).
     */
    data class Row(val gesture: String, val typed: String, val verb: DuelVerb? = null, val intent: String? = null, val needs: String = "")

    const val NEEDS_CHAIN = "chain"
    const val NEEDS_PROPOSAL = "proposal"
    /** Before turn 1, the opening roll to throw (1.0.87). */
    const val NEEDS_OPENING = "opening"
    /** Before turn 1, the roll won by the seat typing: go first or second (1.0.87). */
    const val NEEDS_CHOICE = "choice"

    /** The drop intents, by [com.kaiharimoto.mastertool.core.duel.DropSpot] kind then what it does there. */
    val INTENTS = listOf(
        "zone.place", "zone.set", "zone.move", "zone.attack", "zone.attach",
        "pile.gy", "pile.banish", "pile.banish-down", "pile.deck-top", "pile.deck-bottom", "pile.extra", "pile.hand",
        "hand.to", "hand.reorder", "hand.draw", "hand.direct", "chain", "score",
    )

    val ROWS: List<Row> = listOf(
        // ---- the verbs: a right-click's menu, the verb strip, a key on the card ----
        Row("Space / double-click: the default action", "do h1", DuelVerb.DEFAULT),
        Row("A: activate", "a s1", DuelVerb.ACTIVATE),
        Row("S: summon", "s h1 m3", DuelVerb.SUMMON),
        Row("Shift S: Special Summon", "ss gy1 m4", DuelVerb.SPECIAL),
        Row("E: set", "e h3 s3", DuelVerb.SET),
        Row("P: change position", "p m1", DuelVerb.POSITION),
        Row("F: flip", "f m1", DuelVerb.FLIP),
        Row("G: send to the GY", "g om1", DuelVerb.GRAVE),
        Row("B: banish", "b gy1", DuelVerb.BANISH),
        Row("Shift B: banish face-down", "bd h1", DuelVerb.BANISH_DOWN),
        Row("H: to the hand", "h gy1", DuelVerb.HAND),
        Row("K: to the top of the Deck", "k h1", DuelVerb.DECK_TOP),
        Row("Shift K: to the bottom of the Deck", "kb h1", DuelVerb.DECK_BOTTOM),
        Row("Alt K: shuffled into the Deck", "ks h1", DuelVerb.DECK_SHUFFLE),
        Row("X: to the Extra Deck", "x e1", DuelVerb.EXTRA),
        Row("O, then a click on the host: attach", "o h1 m1", DuelVerb.ATTACH),
        Row("A material's Detach", "detach m1", DuelVerb.DETACH),
        Row("R: reveal", "r h1", DuelVerb.REVEAL),
        Row("C: a counter", "c m1", DuelVerb.COUNTER_UP),
        Row("Shift C: a counter off", "cd m1", DuelVerb.COUNTER_DOWN),
        Row("T: target", "t om1", DuelVerb.TARGET),
        Row("Place (as a Continuous Spell)", "place h3 in s4", DuelVerb.PLACE),
        Row("Drag from zone to zone", "move m1 to m5", DuelVerb.MOVE),
        Row("Shift A, then a click on their monster: attack", "a m1 om1", DuelVerb.ATTACK),
        // ---- drops: where a carried card is let go ----
        Row("Drop on an empty Monster Zone", "h1 to m3", intent = "zone.place"),
        Row("Alt-drop on an empty zone: set", "e h1 m3", intent = "zone.set"),
        Row("Drop a card on the field into another zone", "m m1 m4", intent = "zone.move"),
        Row("Battle Phase: drop on their monster", "m1 attacks om1", intent = "zone.attack"),
        Row("Drop on a monster: attach", "attach h1 to m1", intent = "zone.attach"),
        Row("Drop on the GY", "h1 to gy", intent = "pile.gy"),
        Row("Drop on the banished pile", "h1 to ban", intent = "pile.banish"),
        Row("Alt-drop on the banished pile", "bfd h1", intent = "pile.banish-down"),
        Row("Drop on the Deck", "h1 to dk", intent = "pile.deck-top"),
        Row("Shift-drop on the Deck: its bottom", "h1 to deck bottom", intent = "pile.deck-bottom"),
        Row("Drop on the Extra Deck", "e1 to ex", intent = "pile.extra"),
        Row("Drop on the hand's pile", "gy1 to hand", intent = "pile.hand"),
        Row("Drop into the hand", "om1 to hand", intent = "hand.to"),
        Row("Drag within the hand", "move h2 to h1", intent = "hand.reorder"),
        Row("Drag the Deck's top card to the hand: draw", "draw", intent = "hand.draw"),
        Row("Battle Phase: drop on their hand", "m1 attacks directly", intent = "hand.direct"),
        Row("Drop on the chain well: activate", "chain h4", intent = "chain"),
        Row("Battle Phase: drop on their life points", "a m1 direct", intent = "score"),
        // ---- the rest of the table ----
        Row("A press on the chain well: resolve", "resolve", needs = NEEDS_CHAIN),
        Row("A right-click on the chain well: clear", "clear chain", needs = NEEDS_CHAIN),
        Row("Resolve, keeping the card on the field", "resolve keep", needs = NEEDS_CHAIN),
        // ---- the chain by keys (1.0.90) ----
        Row("Shift Q: resolve the whole chain", "resolve all", needs = NEEDS_CHAIN),
        Row("Enter on a link in the chain well: Negate", "negate 1", needs = NEEDS_CHAIN),
        Row("Y with no Ai at the table: No response", "pass", needs = NEEDS_CHAIN),
        // ---- several cards, one move (1.0.90) ----
        Row("Ctrl-click several, then G", "g h1 h2"),
        Row("Ctrl-click across the GY and the hand, then B", "b gy1 h3"),
        Row("Several onto the Deck in the order chosen, top first", "k h1 h2"),
        Row("Several to the bottom of the Deck, in order", "kb h1 h2"),
        Row("Several onto the Deck in a random order", "random h1 h2 kb"),
        Row("Several shuffled into the Deck", "ks h1 h2"),
        Row("Ctrl-click their cards, then T: an arrow to each", "t om1 os1"),
        Row("An effect where it stands: a link", "link m1"),
        Row("The LP pad", "lp o -1000"),
        Row("Your life points", "lp -500"),
        Row("Open a pile", "open ogy"),
        Row("Close the open pile", "close"),
        Row("The phase column", "m2"),
        Row("Next phase", "next"),
        Row("End the turn", "end"),
        Row("Accept their ask", "accept", needs = NEEDS_PROPOSAL),
        Row("Decline their ask", "decline", needs = NEEDS_PROPOSAL),
        Row("Ai: No response", "no response"),
        Row("Ai: Over to you", "over to you"),
        Row("Ai: Your move", "your move"),
        Row("Ai: Done responding", "done"),
        Row("Ai: Don't wait", "don't wait"),
        Row("Ai: Catch up", "catch up"),
        Row("Ai: Respond", "respond"),
        Row("Sit at the other seat", "swap"),
        Row("Undo", "undo"),
        Row("Redo", "redo"),
        Row("Counters by the handful", "counter m1 +2"),
        Row("An arrow from a card", "target om1 with s1"),
        Row("Read a card in the inspector", "read om1"),
        Row("A card's words", "?m1"),
        Row("Draw a card", "d"),
        Row("Shuffle", "shuffle"),
        Row("Click or throw your coin, by your Extra Deck", "coin"),
        Row("Click or throw your die, by your Extra Deck", "dice"),
        Row("A token", "token m5"),
        Row("Thinking", "think"),
        Row("Say", "say ok?"),
        Row("Several moves", "s h1 m3; t om1"),
        // ---- the opening roll (1.0.87) ----
        Row("Drag your dice and throw them onto the field", "roll", needs = NEEDS_OPENING),
        Row("The winner's Go first", "go first", needs = NEEDS_CHOICE),
        Row("The winner's Go second", "second", needs = NEEDS_CHOICE),
    )

    fun forVerb(verb: DuelVerb): Row? = ROWS.firstOrNull { it.verb == verb }

    fun forIntent(intent: String): Row? = ROWS.firstOrNull { it.intent == intent }
}
