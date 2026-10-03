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
    fun speakingIsHeldNotPressed() {
        // Hold M to speak (1.0.87); typing in the command line, M is an m and Alt M is the key.
        val m = DeskShortcuts.resolveShortcut(KeyChord("m"), duelling)
        assertEquals(DeskAction.DUEL_VOICE, m?.action)
        assertTrue(m!!.hold)
        assertNull(DeskShortcuts.resolve(KeyChord("m"), chatting))
        assertEquals(DeskAction.DUEL_VOICE, DeskShortcuts.resolve(KeyChord("m", alt = true), chatting))
        assertEquals(DeskAction.DUEL_VOICE, DeskShortcuts.resolve(KeyChord("m", alt = true), duelling))
        // It is the person's own: alive with Ai off, and only on Duel.
        assertEquals(DeskAction.DUEL_VOICE, DeskShortcuts.resolve(KeyChord("m"), duelling.copy(ai = false)))
        assertNull(DeskShortcuts.resolve(KeyChord("m"), DeskContext()))
        assertNull(DeskShortcuts.resolve(KeyChord("m"), duelling.copy(overlayOpen = true)))
    }

    @Test
    fun heldRowsAreHeldActionsAndNeverRepeat() {
        val rows = DeskShortcuts.all.filter { it.hold }
        assertTrue(rows.isNotEmpty())
        rows.forEach { assertTrue(it.action in DeskAction.HELD && !it.repeatable, "${DeskShortcuts.kbd(it.chord)} (${it.action})") }
        // A held action is only ever bound as held: a plain press would start it and nothing would end it.
        DeskShortcuts.all.filter { it.action in DeskAction.HELD }.forEach { assertTrue(it.hold, "${DeskShortcuts.kbd(it.chord)} (${it.action})") }
        // Ai never presses one (run_action), and it is not one of Ai's own.
        assertTrue(DeskAction.HELD.none { it in DeskAction.AI })
        assertTrue("\"${DeskAction.DUEL_VOICE.name}\"" !in com.kaiharimoto.mastertool.core.ai.AiTools.runAction.schema.toString())
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
