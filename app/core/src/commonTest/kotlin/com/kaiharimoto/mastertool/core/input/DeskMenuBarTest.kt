package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeskMenuBarTest {

    private val items = DeskMenuBar.menus.flatMap { it.items }

    @Test
    fun everyItemIsAnActionTheKeyboardHasToo() {
        items.forEach { item ->
            assertTrue(DeskShortcuts.all.any { it.action == item.action }, "${item.label} is not in DeskShortcuts")
        }
        assertEquals(items.size, items.map { it.action }.toSet().size, "an action is in the menu bar twice")
    }

    @Test
    fun noAcceleratorCanEatTyping() {
        items.forEach { item ->
            val chord = DeskMenuBar.accelerated(item) ?: return@forEach
            assertTrue(chord.ctrl || chord.alt, "${item.label}: a bare key in a menu eats typing")
            assertTrue(
                DeskShortcuts.all.any { it.chord == chord && it.action == item.action && it.allowedInTextInput },
                "${item.label}: the menu would take a chord the table leaves to a text field",
            )
        }
        val chords = items.mapNotNull { DeskMenuBar.accelerated(it) }
        assertEquals(chords.size, chords.toSet().size, "two items share an accelerator")
    }

    @Test
    fun undoStaysWithTheTextFieldWhileTyping() {
        // Undo is the deck's only when you are not typing: the menu carries no
        // accelerator for it, so the deck name's own undo keeps its key.
        assertNull(DeskMenuBar.accelerated(items.first { it.action == DeskAction.UNDO }))
        assertEquals(KeyChord("s", ctrl = true), DeskMenuBar.accelerated(items.first { it.action == DeskAction.SAVE }))
    }

    @Test
    fun theMenuObeysTheTablesScopes() {
        assertTrue(DeskMenuBar.enabled(DeskAction.UNDO, DeskContext()))
        assertFalse(DeskMenuBar.enabled(DeskAction.UNDO, DeskContext(onBuilder = false)))
        assertFalse(DeskMenuBar.enabled(DeskAction.SAVE, DeskContext(overlayOpen = true)))
        assertTrue(DeskMenuBar.enabled(DeskAction.PALETTE, DeskContext(overlayOpen = true)))
    }

    @Test
    fun onePressArrivingTwiceRunsOnce() {
        val echo = ActionEcho()
        assertTrue(echo.admit(DeskAction.SAVE, 1_000))
        assertFalse(echo.admit(DeskAction.SAVE, 1_005), "the accelerator and the key handler heard one press")
        assertTrue(echo.admit(DeskAction.SAVE, 1_000 + ActionEcho.ECHO_MS + 10), "a second press")
        assertTrue(echo.admit(DeskAction.UNDO, 1_050))
        assertTrue(echo.admit(DeskAction.REDO, 1_051), "a different action is never an echo")
    }

    @Test
    fun chordsAreWrittenTheMacsWayOnAMac() {
        assertEquals("Ctrl K", DeskShortcuts.kbd(KeyChord("k", ctrl = true), mac = false))
        assertEquals("⌘K", DeskShortcuts.kbd(KeyChord("k", ctrl = true), mac = true))
        assertEquals("⇧⌘F", DeskShortcuts.kbd(KeyChord("f", ctrl = true, shift = true), mac = true))
        assertEquals("⌘,", DeskShortcuts.kbd(KeyChord("comma", ctrl = true), mac = true))
        assertEquals("⇧↩", DeskShortcuts.kbd(KeyChord("enter", shift = true), mac = true))
        assertEquals("Esc", DeskShortcuts.kbd(KeyChord("escape"), mac = true))
    }
}
