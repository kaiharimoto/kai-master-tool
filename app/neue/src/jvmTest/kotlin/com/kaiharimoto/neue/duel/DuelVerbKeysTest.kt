package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.input.DeskAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The duel's verb keys as the window runs them (`DuelWiring.VERBS`) and as the verb strip labels them
 * (`DuelRails.VERB_KEYS`): the literals as they stood before both were read off one map, and the rule that holds them
 * together — one is the other's inverse, and only the window's has Space's DEFAULT.
 */
class DuelVerbKeysTest {

    /** `DuelWiring.VERBS`, as it was written out. */
    private val verbs = mapOf(
        DeskAction.DUEL_DEFAULT to DuelVerb.DEFAULT,
        DeskAction.DUEL_ACTIVATE to DuelVerb.ACTIVATE,
        DeskAction.DUEL_SUMMON to DuelVerb.SUMMON,
        DeskAction.DUEL_SPECIAL to DuelVerb.SPECIAL,
        DeskAction.DUEL_SET to DuelVerb.SET,
        DeskAction.DUEL_POSITION to DuelVerb.POSITION,
        DeskAction.DUEL_FLIP to DuelVerb.FLIP,
        DeskAction.DUEL_GRAVE to DuelVerb.GRAVE,
        DeskAction.DUEL_BANISH to DuelVerb.BANISH,
        DeskAction.DUEL_BANISH_DOWN to DuelVerb.BANISH_DOWN,
        DeskAction.DUEL_HAND to DuelVerb.HAND,
        DeskAction.DUEL_DECK_TOP to DuelVerb.DECK_TOP,
        DeskAction.DUEL_DECK_BOTTOM to DuelVerb.DECK_BOTTOM,
        DeskAction.DUEL_DECK_SHUFFLE to DuelVerb.DECK_SHUFFLE,
        DeskAction.DUEL_EXTRA to DuelVerb.EXTRA,
        DeskAction.DUEL_ATTACH to DuelVerb.ATTACH,
        DeskAction.DUEL_REVEAL to DuelVerb.REVEAL,
        DeskAction.DUEL_COUNTER_UP to DuelVerb.COUNTER_UP,
        DeskAction.DUEL_COUNTER_DOWN to DuelVerb.COUNTER_DOWN,
        DeskAction.DUEL_TARGET to DuelVerb.TARGET,
        DeskAction.DUEL_ATTACK to DuelVerb.ATTACK,
    )

    /** `DuelRails.VERB_KEYS`, as it was written out. */
    private val verbKeys = mapOf(
        DuelVerb.ACTIVATE to DeskAction.DUEL_ACTIVATE, DuelVerb.SUMMON to DeskAction.DUEL_SUMMON, DuelVerb.SPECIAL to DeskAction.DUEL_SPECIAL,
        DuelVerb.SET to DeskAction.DUEL_SET, DuelVerb.POSITION to DeskAction.DUEL_POSITION, DuelVerb.FLIP to DeskAction.DUEL_FLIP,
        DuelVerb.GRAVE to DeskAction.DUEL_GRAVE, DuelVerb.BANISH to DeskAction.DUEL_BANISH, DuelVerb.BANISH_DOWN to DeskAction.DUEL_BANISH_DOWN,
        DuelVerb.HAND to DeskAction.DUEL_HAND, DuelVerb.DECK_TOP to DeskAction.DUEL_DECK_TOP, DuelVerb.DECK_BOTTOM to DeskAction.DUEL_DECK_BOTTOM, DuelVerb.DECK_SHUFFLE to DeskAction.DUEL_DECK_SHUFFLE,
        DuelVerb.EXTRA to DeskAction.DUEL_EXTRA, DuelVerb.ATTACH to DeskAction.DUEL_ATTACH, DuelVerb.REVEAL to DeskAction.DUEL_REVEAL,
        DuelVerb.COUNTER_UP to DeskAction.DUEL_COUNTER_UP, DuelVerb.COUNTER_DOWN to DeskAction.DUEL_COUNTER_DOWN, DuelVerb.TARGET to DeskAction.DUEL_TARGET,
        DuelVerb.ATTACK to DeskAction.DUEL_ATTACK,
    )

    @Test
    fun theWindowsVerbKeysAreAsTheyWere() {
        assertEquals(verbs, VERBS)
    }

    @Test
    fun theStripsVerbKeysAreAsTheyWere() {
        assertEquals(verbKeys, VERB_KEYS)
    }

    @Test
    fun oneIsTheOthersInverseAndOnlyTheWindowHasDefault() {
        assertEquals(VERBS - DeskAction.DUEL_DEFAULT, VERB_KEYS.entries.associate { (v, a) -> a to v })
        assertEquals(DuelVerb.DEFAULT, VERBS[DeskAction.DUEL_DEFAULT])
        assertEquals(null, VERB_KEYS[DuelVerb.DEFAULT])
        assertEquals(VERB_KEYS.size, VERB_KEYS.values.toSet().size)
    }
}
