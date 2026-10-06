package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessyTest {
    @Test
    fun aWholeMessageIsACommandInAnyCase() {
        assertEquals(SlashCommand.CHESSY, SlashCommand.parse("/chessy"))
        assertEquals(SlashCommand.CHESSY, SlashCommand.parse("  /Chessy \n"))
        assertEquals(SlashCommand.CAT_MODE, SlashCommand.parse("/CATMODE"))
        assertEquals(SlashCommand.AI, SlashCommand.parse("/ai"))
    }

    @Test
    fun anythingElseGoesToTheModel() {
        assertNull(SlashCommand.parse("chessy"))
        assertNull(SlashCommand.parse("/chessy please"))
        assertNull(SlashCommand.parse("what is 1/2 of 40"))
        assertNull(SlashCommand.parse("/"))
        assertNull(SlashCommand.parse("/cat mode"))
        assertNull(SlashCommand.parse(""))
    }

    @Test
    fun theTakeoverIsDueAfterFiveRepliesOnceAndNeverMidWork() {
        val fresh = AiPrefs()
        assertFalse(TakeoverGate.due(fresh, busy = false))
        assertFalse(TakeoverGate.due(fresh.copy(uses = 4), busy = false))
        assertTrue(TakeoverGate.due(fresh.copy(uses = 5), busy = false))
        assertTrue(TakeoverGate.due(fresh.copy(uses = 40), busy = false))
        assertFalse(TakeoverGate.due(fresh.copy(uses = 5), busy = true))
        assertFalse(TakeoverGate.due(fresh.copy(uses = 5, takeover = AiPrefs.TAKEOVER_SEEN), busy = false))
        assertFalse(TakeoverGate.due(fresh.copy(uses = 5, enabled = false), busy = false))
    }

    @Test
    fun theNewFieldsAreCleanedOnRead() {
        val p = AiPrefs(persona = "grok", uses = -3, takeover = "maybe").sanitised()
        assertEquals(AiPrefs.PERSONA_AI, p.persona)
        assertEquals(0, p.uses)
        assertEquals(AiPrefs.TAKEOVER_NONE, p.takeover)
        assertEquals(AiPrefs.PERSONA_CHESSY, AiPrefs(persona = "chessy").sanitised().persona)
    }

    @Test
    fun everyMoodIsWornInHerParts() {
        for (e in com.kaiharimoto.mastertool.core.ai.avatar.Expression.entries) {
            val m = ChessyMoods.of(e)
            assertTrue(ChessyFaces.ofMood(m) in setOf(ChessyFaces.GRIN, ChessyFaces.FANGS, ChessyFaces.TONGUE))
            // a shut eye has no lid to swap in, so it never blinks
            if (m.eyeL == ChessyEye.SHUT || m.eyeR == ChessyEye.CLOSED) assertTrue(!m.blinks, e.id)
            assertTrue(m.browTilt in -20f..20f && m.ears in -20f..20f, e.id)
        }
        val idle = ChessyMoods.of(com.kaiharimoto.mastertool.core.ai.avatar.Expression.IDLE)
        assertEquals(ChessyEye.SLY, idle.eyeL)
        assertEquals(ChessyLips.SMILE, idle.lips)
        assertTrue(idle.blinks)
        // a wink is one eye shut
        val wink = ChessyMoods.of(com.kaiharimoto.mastertool.core.ai.avatar.Expression.WINK)
        assertTrue(wink.eyeL != wink.eyeR)
        // the moods are many faces, not her three again
        val looks = com.kaiharimoto.mastertool.core.ai.avatar.Expression.entries.map { ChessyMoods.of(it) }
            .map { listOf(it.eyeL, it.eyeR, it.lips, it.brows, it.browTilt, it.browLiftL, it.browLiftR, it.ears) }.toSet()
        assertTrue(looks.size >= 18, "only ${looks.size} distinct looks")
    }
}
