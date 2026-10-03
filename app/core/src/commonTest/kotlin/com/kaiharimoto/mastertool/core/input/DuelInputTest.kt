package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelInputTest {
    private val duelling = DeskContext(onBuilder = false, onDuel = true)
    private val chatting = DeskContext(onBuilder = false, onDuel = true, textInputFocused = true)

    @Test
    fun everyMouseActionHasAFingersForm() {
        val mouse = DuelMouse.all.map { it.target to it.action }.toSet()
        val finger = DuelTouch.all.map { it.target to it.action }.toSet()
        val missing = mouse - finger
        assertTrue(missing.isEmpty(), "No finger's form for $missing")
    }

    @Test
    fun noGestureMeansTwoThings() {
        for (table in listOf(DuelMouse.all, DuelTouch.all)) {
            table.groupBy { it.target to it.gesture }.forEach { (key, rows) ->
                assertTrue(rows.size == 1, "$key is bound ${rows.size} times")
            }
        }
    }

    @Test
    fun theObviousThingIsOneGestureAwayEverywhereACardIs() {
        listOf(DuelTarget.MY_CARD, DuelTarget.THEIR_CARD, DuelTarget.PILE, DuelTarget.STRIP_CARD).forEach { t ->
            assertEquals(DuelInputAction.DEFAULT_VERB, DuelMouse.resolve(t, DuelMouse.RIGHT), "$t")
            assertEquals(DuelInputAction.DEFAULT_VERB, DuelTouch.resolve(t, DuelTouch.DOUBLE), "$t")
            assertEquals(DuelInputAction.MOVE, DuelMouse.resolve(t, DuelMouse.DRAG), "$t")
        }
    }

    @Test
    fun theDuelsKeysLiveOnDuelAlone() {
        assertEquals(DeskAction.GO_DUEL, DeskShortcuts.resolve(KeyChord("7", ctrl = true), DeskContext()))
        assertEquals(DeskAction.DUEL_SUMMON, DeskShortcuts.resolve(KeyChord("s"), duelling))
        assertEquals(DeskAction.DUEL_DRAW, DeskShortcuts.resolve(KeyChord("d"), duelling))
        assertEquals(DeskAction.UNDO, DeskShortcuts.resolve(KeyChord("z", ctrl = true), duelling))
        assertEquals(DeskAction.DUEL_ZONE_S3, DeskShortcuts.resolve(KeyChord("3", shift = true), duelling))
        // The builder keeps its own letters; Duel's are dead off the page.
        assertEquals(DeskAction.TOGGLE_KEYS, DeskShortcuts.resolve(KeyChord("k"), DeskContext()))
        assertNull(DeskShortcuts.resolve(KeyChord("s"), DeskContext(onBuilder = false)))
        // Typing in the chat or the command line never moves a card.
        assertNull(DeskShortcuts.resolve(KeyChord("g"), chatting))
        assertNull(DeskShortcuts.resolve(KeyChord("space"), chatting))
    }

    @Test
    fun commandModeWalksTheTableByKeys() {
        // 1.0.87: the arrows walk the focus, Enter acts on it, Shift Enter picks up, I writes the coordinates.
        assertEquals(DeskAction.DUEL_FOCUS_UP, DeskShortcuts.resolve(KeyChord("up"), duelling))
        assertEquals(DeskAction.DUEL_FOCUS_LEFT, DeskShortcuts.resolve(KeyChord("left"), duelling))
        assertEquals(DeskAction.DUEL_FOCUS_ROW_END, DeskShortcuts.resolve(KeyChord("right", shift = true), duelling))
        assertEquals(DeskAction.DUEL_FOCUS_ACT, DeskShortcuts.resolve(KeyChord("enter"), duelling))
        assertEquals(DeskAction.DUEL_PICK, DeskShortcuts.resolve(KeyChord("enter", shift = true), duelling))
        assertEquals(DeskAction.DUEL_CHAT, DeskShortcuts.resolve(KeyChord("enter", ctrl = true), duelling))
        assertEquals(DeskAction.DUEL_COORDINATES, DeskShortcuts.resolve(KeyChord("i"), duelling))
        // A replay keeps its own arrows; the line and the chat keep theirs while typing.
        assertEquals(DeskAction.REPLAY_BACK, DeskShortcuts.resolve(KeyChord("left"), duelling.copy(replaying = true)))
        assertNull(DeskShortcuts.resolve(KeyChord("up"), chatting))
        assertNull(DeskShortcuts.resolve(KeyChord("enter"), chatting))
        assertNull(DeskShortcuts.resolve(KeyChord("i"), chatting))
        // M is the voice's hold key, not the focus's.
        assertTrue(DeskShortcuts.all.none { it.chord == KeyChord("m") && it.action.name.startsWith("DUEL_FOCUS") })
    }

    @Test
    fun noChordMeansTwoThingsWhileDuelling() {
        for (context in listOf(duelling, chatting, duelling.copy(overlayOpen = true))) {
            DeskShortcuts.live(context).groupBy { it.chord }.forEach { (chord, rows) ->
                val actions = rows.map { it.action }.toSet()
                assertTrue(actions.size == 1, "${DeskShortcuts.kbd(chord)} means $actions")
            }
        }
    }
}
