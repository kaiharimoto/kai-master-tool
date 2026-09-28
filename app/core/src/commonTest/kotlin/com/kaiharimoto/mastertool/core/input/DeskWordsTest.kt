package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertTrue

class DeskWordsTest {

    @Test
    fun aFingersSentenceNeverSpeaksMouse() {
        val mouse = Regex("(?i)\\b(click|right-click|ctrl|pointer|point at|hover|cursor)\\b|Press [A-Z] ")
        DeskWords.touchSentences.forEach { s ->
            assertTrue(!mouse.containsMatchIn(s), "a finger's sentence speaks mouse: \"$s\"")
        }
    }

    @Test
    fun theGesturesItNamesAreTheTablesOwn() {
        val words = TouchGesture.entries.map { it.label.lowercase() }
        listOf("tap", "double-tap", "press and hold").forEach { gesture ->
            assertTrue(words.any { it.startsWith(gesture) || it == gesture }, "\"$gesture\" is not a DeskTouch gesture")
        }
        assertTrue(DeskWords.TOUCH_INTRO.lowercase().contains("double-tap"))
        assertTrue(DeskWords.TOUCH_INTRO.lowercase().contains("press and hold"))
    }
}
