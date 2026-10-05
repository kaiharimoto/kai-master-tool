package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WorldInputTest {
    private val world = DeskContext(onBuilder = false, onWorld = true)
    private val typingInWorld = DeskContext(onBuilder = false, onWorld = true, textInputFocused = true)

    @Test
    fun everyMouseActionHasAFingersForm() {
        val mouse = WorldMouse.all.map { it.target to it.action }.toSet()
        val finger = WorldTouch.all.map { it.target to it.action }.toSet()
        // A tap opens an icon at once (a launcher's habit, §9.3): a finger never needs to pick one out first. And a window
        // has no edge band to a finger (§2.3: desk only): it resizes from the title bar, by □ and snapping.
        val exempt = setOf(WorldTarget.ICON to WorldAction.SELECT, WorldTarget.EDGE to WorldAction.RESIZE)
        assertTrue(WorldTouch.all.any { it.action == WorldAction.RESIZE }, "a finger can still size a window")
        val missing = mouse - finger - exempt
        assertTrue(missing.isEmpty(), "No finger's form for $missing")
    }

    @Test
    fun noGestureMeansTwoThings() {
        for (table in listOf(WorldMouse.all, WorldTouch.all)) {
            table.groupBy { it.target to it.gesture }.forEach { (key, rows) ->
                assertEquals(1, rows.size, "$key is bound ${rows.size} times")
            }
        }
    }

    @Test
    fun theSevenAppsAreAltAndTheirNumber() {
        val apps = listOf(
            DeskAction.WORLD_APP_FILES, DeskAction.WORLD_APP_EDITOR, DeskAction.WORLD_APP_TERMINAL, DeskAction.WORLD_APP_BROWSER,
            DeskAction.WORLD_APP_THOUGHTS, DeskAction.WORLD_APP_INSTRUMENTS, DeskAction.WORLD_APP_LIBRARY,
            // Phase D step 2: the Effects app.
            DeskAction.WORLD_APP_EFFECTS,
        )
        apps.forEachIndexed { i, a -> assertEquals(a, DeskShortcuts.resolve(KeyChord("${i + 1}", alt = true), world), "Alt ${i + 1}") }
        // And while typing in the Editor, too.
        assertEquals(DeskAction.WORLD_APP_TERMINAL, DeskShortcuts.resolve(KeyChord("3", alt = true), typingInWorld))
        assertEquals(DeskAction.WORLD_LAUNCHER, DeskShortcuts.resolve(KeyChord("0", alt = true), world))
        assertEquals(DeskAction.WORLD_NEXT_WINDOW, DeskShortcuts.resolve(KeyChord("backquote", ctrl = true), world))
        assertEquals(DeskAction.WORLD_PREVIOUS_WINDOW, DeskShortcuts.resolve(KeyChord("backquote", ctrl = true, shift = true), world))
        assertEquals(DeskAction.WORLD_CLOSE, DeskShortcuts.resolve(KeyChord("w", ctrl = true), world))
        assertEquals(DeskAction.WORLD_SKIP, DeskShortcuts.resolve(KeyChord("f", shift = true), world))
        assertEquals(DeskAction.WORLD_TAB_BACK, DeskShortcuts.resolve(KeyChord("left", alt = true), world))
        assertEquals("Ctrl `", DeskShortcuts.kbd(KeyChord("backquote", ctrl = true)))
    }

    @Test
    fun theWorldsKeysMeanOneThingEach() {
        for (context in listOf(world, typingInWorld)) {
            DeskShortcuts.live(context).groupBy { it.chord }.forEach { (chord, rows) ->
                val actions = rows.map { it.action }.toSet()
                if (actions.size > 1) fail("${DeskShortcuts.kbd(chord)} means $actions in $context")
            }
        }
    }

    @Test
    fun theWorldsKeysStayInTheWorld() {
        val builder = DeskContext()
        assertEquals(DeskAction.WEB_PREVIOUS, DeskShortcuts.resolve(KeyChord("left", alt = true), builder))
        assertEquals(null, DeskShortcuts.resolve(KeyChord("w", ctrl = true), builder))
        assertEquals(DeskAction.SLIDE_NEW, DeskShortcuts.resolve(KeyChord("m", ctrl = true), DeskContext(onBuilder = false, onPresent = true)))
    }

    @Test
    fun ctrlDigitsAreTheBrowsersTabsWhileItIsInFront() {
        val browser = DeskContext(onBuilder = false, onWorld = true, browserInFront = true)
        val typingAnAddress = browser.copy(textInputFocused = true)
        for (n in 1..9) {
            val chord = KeyChord("$n", ctrl = true)
            assertEquals(DeskAction.valueOf("WORLD_TAB_$n"), DeskShortcuts.resolve(chord, browser), "Ctrl $n in the Browser")
            assertEquals(DeskAction.valueOf("WORLD_TAB_$n"), DeskShortcuts.resolve(chord, typingAnAddress))
        }
        // Any other window in front, or the desktop: the pages keep their keys.
        assertEquals(DeskAction.GO_BUILDER, DeskShortcuts.resolve(KeyChord("1", ctrl = true), world))
        assertEquals(DeskAction.GO_SHOOTOUT, DeskShortcuts.resolve(KeyChord("9", ctrl = true), world))
        // Never two meanings at once.
        for (context in listOf(browser, typingAnAddress, world, typingInWorld)) {
            DeskShortcuts.live(context).groupBy { it.chord }.forEach { (chord, rows) ->
                if (rows.map { it.action }.toSet().size > 1) fail("${DeskShortcuts.kbd(chord)} means ${rows.map { it.action }} in $context")
            }
        }
    }
}
