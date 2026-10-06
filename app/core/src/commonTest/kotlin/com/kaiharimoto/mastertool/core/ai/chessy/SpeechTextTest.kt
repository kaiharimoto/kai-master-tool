package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Her Flap follows the words she says (the rig red team). */
class SpeechTextTest {
    /** The mouth's open/shut over [ms], stepped 16 ms at a time, after feeding [text]. */
    private fun mouths(text: String, ms: Int = 4000): List<Float> {
        val s = SpeechText()
        s.feed(text)
        return List(ms / 16) { s.advance(16f); s.now.open }
    }

    @Test
    fun syllablesAreCountedAsSpoken() {
        assertEquals(1, SpeechText.syllablesOf("nya"))
        assertEquals(2, SpeechText.syllablesOf("Labrynth"))
        assertEquals(4, SpeechText.syllablesOf("Labyrinthian"))
        assertEquals(1, SpeechText.syllablesOf("make"))
        assertEquals(2, SpeechText.syllablesOf("table"))
        assertEquals(2, SpeechText.syllablesOf("2026"))
        assertEquals(2, SpeechText.syllablesOf("にゃあ"))
        assertEquals(6, SpeechText.syllablesOf("supercalifragilistic"))
    }

    @Test
    fun aFullStopIsALongerRestThanAComma() {
        // the longest rest between two spoken moments (not the silence after the last)
        fun longestRest(text: String): Int {
            var run = 0
            var best = 0
            var spoke = false
            for (o in mouths(text)) {
                if (o > 0f) { if (spoke) best = maxOf(best, run); spoke = true; run = 0 } else if (spoke) run += 16
            }
            return best
        }
        val comma = longestRest("well then, let us duel ")
        val stop = longestRest("well then. Let us duel ")
        assertTrue(stop > comma, "a full stop rests $stop ms, a comma $comma")
    }

    @Test
    fun codeTablesAndLinksAreNotSpoken() {
        val silent = "```kotlin\nval x = 1\nprintln(x)\n```\n| a | b |\n|---|---|\n`inline` https://example.com/x "
        assertTrue(mouths(silent).all { it == 0f }, "she mouthed code")
        val mixed = mouths("```\ncode here\n```\nhello there ")
        assertTrue(mixed.any { it > .38f }, "and then speaks again")
    }

    @Test
    fun theWordsArriveAsTheyStreamAndHalfAWordWaits() {
        val s = SpeechText()
        s.feed("hel")
        repeat(20) { s.advance(16f) }
        assertEquals(0f, s.now.open, "half a word is not spoken yet")
        s.feed("hello the")
        var spoke = false
        repeat(40) { s.advance(16f); spoke = spoke || s.now.open > 0f }
        assertTrue(spoke)
    }

    @Test
    fun sheNeverFallsFarBehindAFastStreamAndStopsWithIt() {
        val s = SpeechText()
        val text = StringBuilder()
        // a reply streamed at some three hundred characters a second, far faster than speech
        repeat(120) {
            text.append("the deck draws into its engine every turn, ")
            s.feed(text.toString())
            repeat(9) { s.advance(16f) }
            assertTrue(s.behind <= SpeechText.LAG + 1f, "fell ${s.behind} ms behind")
        }
        // the text stops: within the lag (played hurried) her mouth is still
        repeat((SpeechText.LAG / 16).toInt() + 10) { s.advance(16f) }
        assertEquals(0f, s.now.open)
    }

    @Test
    fun aNewReplyBeginsAfreshAndTheSameWordsKeepTheSameRhythm() {
        assertEquals(mouths("purr purr, nya! "), mouths("purr purr, nya! "))
        val s = SpeechText()
        s.feed("a long first reply that is spoken ")
        repeat(50) { s.advance(16f) }
        s.feed("")
        s.feed("new ")
        s.advance(16f)
        assertTrue(s.behind > 0f && s.behind < 400f, "the new reply starts at its start: ${s.behind}")
    }
}
