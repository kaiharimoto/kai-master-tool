package com.kaiharimoto.mastertool.core.ai.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deck profile on YouTube, watched by Gemini (1.0.62). */
class VideoTest {
    @Test
    fun everyShapeOfYouTubeAddressGivesTheVideo() {
        listOf(
            "https://www.youtube.com/watch?v=fE-RsenvS5I",
            "https://youtube.com/watch?feature=shared&v=fE-RsenvS5I&t=42",
            "https://m.youtube.com/watch?v=fE-RsenvS5I",
            "https://youtu.be/fE-RsenvS5I?si=abc",
            "https://www.youtube.com/shorts/fE-RsenvS5I",
            "https://www.youtube.com/embed/fE-RsenvS5I",
            "https://www.youtube.com/live/fE-RsenvS5I?si=x",
            "watch this: youtu.be/fE-RsenvS5I",
        ).forEach { assertEquals("fE-RsenvS5I", YouTube.id(it), it) }
        assertNull(YouTube.id("https://ygoprodeck.com/deck/labrynth-711878"))
        assertEquals("https://www.youtube.com/watch?v=fE-RsenvS5I", YouTube.watch("fE-RsenvS5I"))
        assertEquals("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DfE-RsenvS5I", YouTube.oembed("fE-RsenvS5I"))
    }

    @Test
    fun oembedGivesTheTitleAndChannel() {
        // As YouTube answered it (September 2026), trimmed.
        val said = """{"title":"Yu-Gi-Oh! Las Vegas Regional 5th Place Labrynth Deck Profile! (Kaihuang Zhang)","author_name":"Cyberhorn92","type":"video"}"""
        assertEquals("Yu-Gi-Oh! Las Vegas Regional 5th Place Labrynth Deck Profile! (Kaihuang Zhang)" to "Cyberhorn92", YouTube.titleOf(said))
        assertNull(YouTube.titleOf("Not Found"))
    }

    @Test
    fun geminiIsGivenTheVideoThenTheBrief() {
        val brief = GeminiVideo.brief("Labrynth Deck Profile", "the side deck")
        assertTrue("DECKLIST:" in brief && "SIDING:" in brief && "\"Labrynth Deck Profile\"" in brief)
        assertTrue(brief.endsWith("They especially want to know: the side deck"))
        val body = Json.parseToJsonElement(GeminiVideo.request("https://www.youtube.com/watch?v=fE-RsenvS5I", brief)).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("https://www.youtube.com/watch?v=fE-RsenvS5I", parts[0].jsonObject["file_data"]!!.jsonObject["file_uri"]!!.jsonPrimitive.content)
        assertEquals(brief, parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminisAnswerAndItsRefusalsAreRead() {
        val ok = """{"candidates":[{"content":{"parts":[{"text":"DECKLIST:\n3 x Arianna the Labrynth Servant"},{"text":"\nPLAN: grind"}]},"finishReason":"STOP"}]}"""
        assertEquals("DECKLIST:\n3 x Arianna the Labrynth Servant\nPLAN: grind", GeminiVideo.read(200, ok).getOrThrow())
        // As Google answers a bad key (September 2026).
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertTrue(GeminiVideo.read(400, badKey).exceptionOrNull()!!.message!!.startsWith("The Gemini key was refused"))
        assertTrue("limit" in GeminiVideo.read(429, """{"error":{"code":429,"message":"Resource exhausted"}}""").exceptionOrNull()!!.message!!)
        assertTrue("said nothing (SAFETY)" in GeminiVideo.read(200, """{"candidates":[{"finishReason":"SAFETY"}]}""").exceptionOrNull()!!.message!!)
    }

    @Test
    fun theNewestFlashWatches() {
        val list = """{"models":[
            {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3-flash-preview","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3-flash","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3-flash-lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3-flash-image","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
        ]}"""
        assertEquals("gemini-3-flash", GeminiVideo.pick(list))
        assertEquals("gemini-2.5-pro", GeminiVideo.pick("""{"models":[{"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]}]}"""))
        assertEquals(GeminiVideo.FALLBACK_MODEL, GeminiVideo.pick("nonsense"))
    }
}
