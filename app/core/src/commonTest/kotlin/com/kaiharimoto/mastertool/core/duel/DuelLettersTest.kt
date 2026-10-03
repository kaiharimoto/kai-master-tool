package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelComplete
import com.kaiharimoto.mastertool.core.duel.text.DuelLetters
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskScope
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.KeyChord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The verb letters are one list ([DuelLetters]): what nothing held together before is held here. */
class DuelLettersTest {

    /** The chord a letter is: a single letter bare; `bd`, `kb`, `cd` Shift on the first letter, `ks` Alt. */
    private fun chordOf(letter: String): KeyChord = when {
        letter.length == 1 -> KeyChord(letter)
        letter == "ks" -> KeyChord("k", alt = true)
        else -> KeyChord(letter.take(1), shift = true)
    }

    @Test
    fun everyLettersKeyIsBoundToThatLetterInTheDuel() {
        DuelLetters.ROWS.forEach { r ->
            val key = r.key ?: return@forEach
            val bound = DeskShortcuts.all.filter { it.action == key }
            assertTrue(bound.isNotEmpty(), "${r.letter}: $key is bound to nothing")
            assertTrue(bound.all { it.scope == DeskScope.DUEL }, "${r.letter}: $key outside the duel")
            assertEquals(chordOf(r.letter), DeskShortcuts.chordFor(key), r.letter)
        }
    }

    @Test
    fun mIsTypedOnlyBecauseTheKeyMSpeaks() {
        val m = DuelLetters.ROWS.single { it.letter == "m" }
        assertEquals(DuelVerb.MOVE, m.verb)
        assertNull(m.key)
        assertEquals(DeskAction.DUEL_VOICE, DeskShortcuts.all.single { it.chord == KeyChord("m") && it.scope == DeskScope.DUEL }.action)
        assertTrue(DuelLetters.KEY_HINTS.none { it.first == "m" })
    }

    @Test
    fun theKeyOnlyVerbsAreBoundToo() {
        DuelLetters.KEYS.forEach { (verb, key) ->
            assertTrue(DeskShortcuts.all.any { it.action == key && it.scope == DeskScope.DUEL }, "$verb: $key")
        }
        // One key a verb, one verb a key: the window's map is this one's inverse.
        assertEquals(DuelLetters.KEYS.size, DuelLetters.KEYS.values.toSet().size)
        assertNull(DuelLetters.KEYS[DuelVerb.DEFAULT])
    }

    @Test
    fun theLettersAreVerbWordsAndCompletionsLettersAreLetters() {
        assertTrue(DuelCommand.VERB_WORDS.entries.containsAll(DuelLetters.WORDS.entries))
        assertEquals(DuelLetters.LETTERS, DuelCommand.LETTER_WORDS)
        DuelComplete.KEY_LETTERS.forEach { (l, _) -> assertTrue(l in DuelCommand.VERB_WORDS && l in DuelLetters.LETTERS, l) }
        // A hint is given exactly to the single letters that are a key.
        DuelLetters.ROWS.forEach { r -> assertEquals(!r.twoLetter && r.key != null, r.hint != null, r.letter) }
        assertEquals(setOf("bd", "kb", "ks", "cd"), DuelLetters.TWO_LETTER)
    }

    @Test
    fun theSpokenHeadsAreTheLines() {
        // "return" is a head to the ear and none to the line: kept as it was (a line "return …" is shown as the Line's
        // problem, not handed to Ai), and named here so that it stays the only one.
        assertEquals(setOf("return"), DuelSpeech.COMMAND_HEADS - DuelCommand.HEADS)
        assertTrue(DuelCommand.HEADS.containsAll(DuelCommand.VERB_WORDS.keys))
    }
}
