package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.input.DeskAction

/**
 * The duel's verb letters, one list (the refactor's step 1): a letter typed before a coordinate is a verb (`s h2 m3`,
 * `g om3`, `kb gy1`), and the same letter is that verb's key (`DeskShortcuts`, DUEL scope) — the two-letter verbs are
 * the Shift and Alt keys spelled out (`bd` is Shift B, `kb` Shift K, `ks` Alt K, `cd` Shift C).
 *
 * Read from here: [DuelCommand]'s letter words and the set of letters, [DuelComplete]'s key letters and the two-letter
 * set, [CommandHelp.letters], and in the window the verb keys both ways (`DuelWiring.VERBS`, `DuelRails.VERB_KEYS`,
 * through [KEYS]).
 */
object DuelLetters {

    /**
     * One letter: the [verb] it types, the [key] that runs that verb ([DeskAction]; null when the letter is typed only),
     * and [hint], completion's own words for the letter as a key — given only for the keys' single letters.
     */
    data class Letter(val letter: String, val verb: DuelVerb, val key: DeskAction?, val hint: String? = null) {
        /** `bd`, `kb`, `ks`, `cd`: a Shift or Alt key spelled out. */
        val twoLetter: Boolean get() = letter.length == 2
    }

    /**
     * Every letter, in the order the line has always listed them. `m` is MOVE typed and nothing as a key: the key M
     * is held to speak (`DUEL_VOICE`), so `m` has no key here and is no key letter for completion.
     */
    val ROWS: List<Letter> = listOf(
        Letter("a", DuelVerb.ACTIVATE, DeskAction.DUEL_ACTIVATE, "Activate"),
        Letter("s", DuelVerb.SUMMON, DeskAction.DUEL_SUMMON, "Summon"),
        Letter("e", DuelVerb.SET, DeskAction.DUEL_SET, "Set"),
        Letter("p", DuelVerb.POSITION, DeskAction.DUEL_POSITION, "Position"),
        Letter("f", DuelVerb.FLIP, DeskAction.DUEL_FLIP, "Flip"),
        Letter("g", DuelVerb.GRAVE, DeskAction.DUEL_GRAVE, "Send to the GY"),
        Letter("b", DuelVerb.BANISH, DeskAction.DUEL_BANISH, "Banish"),
        Letter("h", DuelVerb.HAND, DeskAction.DUEL_HAND, "To the hand"),
        Letter("k", DuelVerb.DECK_TOP, DeskAction.DUEL_DECK_TOP, "To the top of the Deck"),
        Letter("x", DuelVerb.EXTRA, DeskAction.DUEL_EXTRA, "To the Extra Deck"),
        Letter("o", DuelVerb.ATTACH, DeskAction.DUEL_ATTACH, "Attach"),
        Letter("r", DuelVerb.REVEAL, DeskAction.DUEL_REVEAL, "Reveal"),
        Letter("c", DuelVerb.COUNTER_UP, DeskAction.DUEL_COUNTER_UP, "A counter"),
        Letter("t", DuelVerb.TARGET, DeskAction.DUEL_TARGET, "Target"),
        // Shortcut (Phase D §5½): `u h2`, `u h2 e2`, `u h2 search`; the word is `shortcut`, never `use`, which stays Activate.
        Letter("u", DuelVerb.SHORTCUT, DeskAction.DUEL_SHORTCUT, "Shortcut"),
        Letter("m", DuelVerb.MOVE, null),
        Letter("bd", DuelVerb.BANISH_DOWN, DeskAction.DUEL_BANISH_DOWN),
        Letter("kb", DuelVerb.DECK_BOTTOM, DeskAction.DUEL_DECK_BOTTOM),
        Letter("ks", DuelVerb.DECK_SHUFFLE, DeskAction.DUEL_DECK_SHUFFLE),
        Letter("cd", DuelVerb.COUNTER_DOWN, DeskAction.DUEL_COUNTER_DOWN),
    )

    /** The letters as the line's verb words: letter → verb. */
    val WORDS: Map<String, DuelVerb> = ROWS.associate { it.letter to it.verb }

    /** The one- and two-letter verbs: verbs only before a coordinate. */
    val LETTERS: Set<String> = ROWS.map { it.letter }.toSet()

    /** The two-letter verbs. */
    val TWO_LETTER: Set<String> = ROWS.filter { it.twoLetter }.map { it.letter }.toSet()

    /**
     * Completion's key letters, with its own words: the single letters that are a key — not `m` (typed only), not the
     * two-letter verbs (a Shift or Alt key is no letter to complete).
     */
    val KEY_HINTS: List<Pair<String, String>> = ROWS.filter { !it.twoLetter && it.key != null }.mapNotNull { r -> r.hint?.let { r.letter to it } }

    /** The verbs that have a key and no letter: Shift S (Special Summon; `ss` is its word) and Shift A (an attack is `a m3 om1`). */
    private val KEY_ONLY: Map<DuelVerb, DeskAction> = mapOf(
        DuelVerb.SPECIAL to DeskAction.DUEL_SPECIAL,
        DuelVerb.ATTACK to DeskAction.DUEL_ATTACK,
    )

    /**
     * Every verb's key: the letters' keys and the key-only verbs. The window runs a verb key through its inverse
     * (with Space, DEFAULT, beside it), and the verb strip labels a verb by it. DEFAULT is no verb here: Space is the
     * window's alone.
     */
    val KEYS: Map<DuelVerb, DeskAction> = ROWS.mapNotNull { r -> r.key?.let { r.verb to it } }.toMap() + KEY_ONLY
}
