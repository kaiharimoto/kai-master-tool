package com.kaiharimoto.mastertool.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase I, I.5: the modes' table closes what the `when` it replaced closed, mode by mode. */
class AiModesTest {

    @Test
    fun everyModeClosesWhatItClosedBefore() {
        val deckWork = AiTools.DECK_CHANGING + AiTools.SWITCHING_DECKS
        // The `when` of 1.1.93, written out: what each mode closed (a course's own tools aside, closed outside a course).
        val before = mapOf(
            AiSession.MODE_PRINCIPLES to AiTools.FIRST_PRINCIPLES_BARRED + deckWork,
            AiSession.MODE_TUNE to deckWork, AiSession.MODE_STUDY to deckWork, AiSession.MODE_REFACTOR to deckWork, AiSession.MODE_WRITE to deckWork,
            AiSession.MODE_PROFILE to AiTools.DECK_CHANGING,
            AiSession.MODE_RUBRIC to deckWork, AiSession.MODE_SHOOTOUT to deckWork,
            AiSession.MODE_EFFECTS to deckWork,
            AiSession.MODE_COURSE to deckWork,
            AiSession.MODE_CHAT to emptySet(), AiSession.MODE_PRESENT to emptySet(), AiSession.MODE_RESTYLE to emptySet(),
            AiSession.MODE_DUEL to emptySet(), AiSession.MODE_WORLD to emptySet(), AiSession.MODE_MATCH to emptySet(),
            "a newer build's mode" to emptySet(),
        )
        before.forEach { (mode, closed) ->
            val course = if (mode == AiSession.MODE_COURSE) emptySet() else CourseTools.names
            assertEquals(closed + course, AiTools.barredIn(mode), mode)
        }
        assertEquals(AiModes.ALL.map { it.id }.toSet(), before.keys - "a newer build's mode", "every mode in the table, once")
        assertEquals(AiModes.ALL.size, AiModes.ALL.map { it.id }.toSet().size)
    }
}
