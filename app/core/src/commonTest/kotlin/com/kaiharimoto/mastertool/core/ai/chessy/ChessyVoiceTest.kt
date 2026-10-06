package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.prompt.PromptBuilder
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChessyVoiceTest {
    @Test
    fun theVoiceFollowsWhoIsTheAssistantAndCatMode() {
        assertEquals(ChessyVoice.AI, ChessyVoice.key(AiPrefs.PERSONA_AI, catMode = false))
        // cat mode is Chessy's: on with Ai as the assistant, Ai is still Ai
        assertEquals(ChessyVoice.AI, ChessyVoice.key(AiPrefs.PERSONA_AI, catMode = true))
        assertEquals(ChessyVoice.CHESSY, ChessyVoice.key(AiPrefs.PERSONA_CHESSY, catMode = false))
        assertEquals(ChessyVoice.CHESSY_CAT, ChessyVoice.key(AiPrefs.PERSONA_CHESSY, catMode = true))
    }

    @Test
    fun herSectionIsCuteEvilAndCatModeAddsTheFullVoice() {
        assertEquals("", ChessyVoice.section(ChessyVoice.AI))
        val plain = ChessyVoice.section(ChessyVoice.CHESSY)
        val cat = ChessyVoice.section(ChessyVoice.CHESSY_CAT)
        assertTrue("You are Chessy" in plain && "cute evil" in plain)
        assertFalse("Cat mode is on" in plain)
        assertTrue(cat.startsWith(plain) && "Cat mode is on" in cat && "nya" in cat)
        // what a person acts on is never voiced, in either
        for (s in listOf(plain, cat)) assertTrue("Card names" in s || "card names" in s)
    }

    @Test
    fun bothPromptsCarryHerSectionAndAisCarriesNone() {
        val base = PromptBuilder.Setup("Chessy", "I am the soul.", "", "", "", "desktop")
        for (mode in listOf(AiSession.MODE_CHAT, AiSession.MODE_DUEL)) {
            val ai = PromptBuilder.system(base.copy(name = "Ai", mode = mode))
            val chessy = PromptBuilder.system(base.copy(mode = mode, voice = ChessyVoice.section(ChessyVoice.CHESSY_CAT)))
            assertFalse("You are Chessy" in ai, mode)
            assertTrue("You are Chessy" in chessy && "Cat mode is on" in chessy, mode)
            // her section stands right after the soul, before the job
            assertTrue(chessy.indexOf("I am the soul.") < chessy.indexOf("You are Chessy"), mode)
        }
    }

    @Test
    fun aChangeMidConversationIsSaidInTheNextMessage() {
        assertTrue("Kiri again" in ChessyVoice.switched(ChessyVoice.AI, aiName = "Kiri"))
        val on = ChessyVoice.switched(ChessyVoice.CHESSY)
        assertTrue("you are Chessy" in on && "Cat mode is off" in on)
        assertTrue("Cat mode is on" in ChessyVoice.switched(ChessyVoice.CHESSY_CAT))
    }

    @Test
    fun aConversationSavedBeforeHerVoiceWasAis() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(AiSession.serializer(), """{"id":"old","system":"You are Ai.","mode":"chat"}""")
        assertNull(old.voiceShown)
        val back = json.decodeFromString(AiSession.serializer(), json.encodeToString(AiSession.serializer(), old.copy(voiceShown = ChessyVoice.CHESSY_CAT)))
        assertEquals(ChessyVoice.CHESSY_CAT, back.voiceShown)
    }
}
