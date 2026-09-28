package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class DeskShortcutsTest {

    private val builder = DeskContext()
    private val typingName = DeskContext(textInputFocused = true)
    private val searching = DeskContext(textInputFocused = true, searchFocused = true)
    private val covered = DeskContext(overlayOpen = true)
    private val decksPage = DeskContext(onBuilder = false)

    @Test
    fun everyActionIsBound() {
        val bound = DeskShortcuts.all.map { it.action }.toSet()
        val missing = DeskAction.entries.filterNot { it in bound }
        assertTrue(missing.isEmpty(), "Unbound desk actions: $missing")
    }

    @Test
    fun noChordMeansTwoThingsAtOnce() {
        // Two rows sharing a chord are only a bug if some context makes both
        // live, because then the first one silently wins.
        for (context in listOf(builder, typingName, searching, covered, decksPage)) {
            DeskShortcuts.live(context).groupBy { it.chord }.forEach { (chord, rows) ->
                val actions = rows.map { it.action }.toSet()
                if (actions.size > 1) fail("${DeskShortcuts.kbd(chord)} means $actions in $context")
            }
        }
    }

    @Test
    fun thePaletteAndEscapeWorkOverEverything() {
        for (context in listOf(builder, typingName, searching, covered, decksPage)) {
            assertEquals(DeskAction.PALETTE, DeskShortcuts.resolve(KeyChord("k", ctrl = true), context))
            assertEquals(DeskAction.DISMISS, DeskShortcuts.resolve(KeyChord("escape"), context))
        }
    }

    @Test
    fun pagesAreReachableWhileTyping() {
        assertEquals(DeskAction.GO_ODDS, DeskShortcuts.resolve(KeyChord("3", ctrl = true), typingName))
        assertEquals(DeskAction.GO_SETTINGS, DeskShortcuts.resolve(KeyChord("comma", ctrl = true), searching))
    }

    @Test
    fun anOverlayDeadensThePageKeys() {
        assertNull(DeskShortcuts.resolve(KeyChord("2", ctrl = true), covered))
        assertNull(DeskShortcuts.resolve(KeyChord("b"), covered))
        assertNull(DeskShortcuts.resolve(KeyChord("enter"), covered))
    }

    @Test
    fun unmodifiedLettersAreDeadWhileTyping() {
        assertNull(DeskShortcuts.resolve(KeyChord("b"), typingName))
        assertNull(DeskShortcuts.resolve(KeyChord("i"), searching))
        assertNull(DeskShortcuts.resolve(KeyChord("delete"), searching))
        assertEquals(DeskAction.NEXT_LENS, DeskShortcuts.resolve(KeyChord("b"), builder))
    }

    @Test
    fun thePoolKeysLiveInTheSearchFieldButNotTheDeckName() {
        assertEquals(DeskAction.POOL_NEXT, DeskShortcuts.resolve(KeyChord("down"), searching))
        assertEquals(DeskAction.POOL_ADD, DeskShortcuts.resolve(KeyChord("enter"), searching))
        assertEquals(DeskAction.POOL_ADD_TO_SIDE, DeskShortcuts.resolve(KeyChord("enter", shift = true), searching))
        // Pressing Enter to confirm a deck name must not add a card.
        assertNull(DeskShortcuts.resolve(KeyChord("enter"), typingName))
        // With nothing focused, the arrows still walk the results.
        assertEquals(DeskAction.POOL_PREVIOUS, DeskShortcuts.resolve(KeyChord("up"), builder))
    }

    @Test
    fun builderKeysStayOnTheBuilder() {
        assertNull(DeskShortcuts.resolve(KeyChord("z", ctrl = true), decksPage))
        assertNull(DeskShortcuts.resolve(KeyChord("enter"), decksPage))
        assertEquals(DeskAction.SAVE, DeskShortcuts.resolve(KeyChord("s", ctrl = true), decksPage))
    }

    @Test
    fun kbdIsSpacedAndNeverAPlusOrACommandGlyph() {
        assertEquals("Ctrl K", DeskShortcuts.kbd(KeyChord("k", ctrl = true)))
        assertEquals("Shift Enter", DeskShortcuts.kbd(KeyChord("enter", shift = true)))
        assertEquals("Ctrl Shift Z", DeskShortcuts.kbd(KeyChord("z", ctrl = true, shift = true)))
        assertEquals("Esc", DeskShortcuts.kbd(KeyChord("escape")))
        for (row in DeskShortcuts.all) {
            val text = DeskShortcuts.kbd(row.chord)
            assertTrue('+' !in text && '⌘' !in text, text)
        }
    }

    @Test
    fun descriptionsAreSentenceCaseWithoutExclamationMarks() {
        for (row in DeskShortcuts.all) {
            assertTrue(row.description.first().isUpperCase(), row.description)
            assertTrue(!row.description.endsWith("!") && !row.description.endsWith("."), row.description)
        }
    }

    @Test
    fun typingNeverRunsALetterOrDeletesACard() {
        // A keyboard cover on a tablet types into a field while the table listens
        // (touch swarm, rec 6): nothing it types may act on the deck.
        for (context in listOf(typingName, searching)) {
            DeskShortcuts.live(context).forEach { row ->
                val chord = row.chord
                val bare = !chord.ctrl && !chord.alt
                assertTrue(!(bare && chord.key.length == 1 && chord.key[0].isLetterOrDigit()), "${row.action} fires on a typed ${chord.key}")
                assertTrue(!(bare && chord.key in setOf("backspace", "delete", "space", "slash")), "${row.action} fires on ${chord.key} while typing")
            }
        }
    }
}
