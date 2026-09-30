package com.kaiharimoto.mastertool.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ai's notification out of sight (1.0.61). */
class WorkNoticeTest {
    @Test
    fun whileItWorksItSaysWhatItIsDoing() {
        assertEquals("Ai is working" to "Reading Kaihuang Zhang's results", WorkNotice.working("Ai", "Reading Kaihuang Zhang's results"))
        assertTrue(WorkNotice.working("Ai", " ").second.startsWith("Answering"))
    }

    @Test
    fun whenItIsDoneItSaysHowItBegan() {
        assertEquals("Ai answered" to "Side in two Nibiru going second.", WorkNotice.answered("Ai", "Side in two Nibiru going second.", null))
        assertEquals("Ai stopped", WorkNotice.answered("Ai", "x", "Could not reach it").first)
        assertEquals("Ai is done", WorkNotice.answered("Ai", "", null).first)
        assertTrue(WorkNotice.answered("Ai", "w".repeat(500), null).second.length <= 160)
    }
}
