package com.kaiharimoto.mastertool.core.ai.voice

import com.kaiharimoto.mastertool.core.ai.avatar.AiSignals
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MoodTracker
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Talking to Ai (1.0.57): sound as numbers, when speech ends, what primes the transcriber, what is said aloud. */
class VoiceTest {
    @Test
    fun pcmIsReadAsSamplesAndLoudness() {
        val bytes = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // +16384, −16384
        val s = Pcm.samples(bytes)
        assertEquals(0.5f, s[0], 1e-4f)
        assertEquals(-0.5f, s[1], 1e-4f)
        assertEquals(0.5f, Pcm.rms(s), 1e-4f)
        val tone = FloatArray(1600) { (0.3 * sin(2 * PI * 440 * it / Pcm.RATE)).toFloat() }
        assertEquals(0.212f, Pcm.rms(tone), 0.01f)
    }

    private fun run(gate: SpeechGate, levels: List<Pair<Float, Int>>): SpeechGate.State {
        var t = 0.0
        levels.forEach { (level, slices) ->
            repeat(slices) {
                t += 0.1
                gate.feed(level, t)
            }
        }
        return gate.state
    }

    @Test
    fun speechEndsAfterAPause() {
        // Half a second of room, two seconds of speech, then a second and a half of quiet.
        assertEquals(SpeechGate.State.DONE_SPOKEN, run(SpeechGate(), listOf(0.005f to 5, 0.12f to 20, 0.006f to 15)))
        // A pause shorter than the tail is still one turn.
        assertEquals(SpeechGate.State.SPEAKING, run(SpeechGate(), listOf(0.005f to 5, 0.12f to 10, 0.006f to 6, 0.1f to 3)))
    }

    @Test
    fun silenceAndRamblingEndToo() {
        assertEquals(SpeechGate.State.DONE_NOTHING, run(SpeechGate(), listOf(0.004f to 90)))
        assertEquals(SpeechGate.State.DONE_TOO_LONG, run(SpeechGate(most = 5.0), listOf(0.005f to 3, 0.2f to 60)))
        // A noisy room raises the floor: its hum is not speech.
        assertEquals(SpeechGate.State.DONE_NOTHING, run(SpeechGate(), listOf(0.03f to 90)))
    }

    @Test
    fun theTranscriberIsPrimedWithTheDecksCards() {
        val p = Hints.prompt(listOf("Snake-Eye Ash", "Snake-Eye Ash", "Fiendsmith Engraver"))
        assertTrue(p.startsWith("Snake-Eye Ash, Fiendsmith Engraver"))
        assertTrue("Extra Deck" in p)
        assertTrue(Hints.prompt(List(400) { "Card number $it" }).length <= 700)
        assertTrue(Hints.isNothing(" [BLANK_AUDIO] "))
        assertTrue(Hints.isNothing("(music)"))
        assertFalse(Hints.isNothing("Add three Ash Blossom"))
    }

    @Test
    fun onlyTheWordsAreSaidAloud() {
        val said = Spoken.of("**Side in** [[Nibiru, the Primal Being]] going second.\n\n```compare\nOut:\n1 Droll & Lock Bird\nIn:\n1 Nibiru, the Primal Being\n```")
        assertEquals("Side in Nibiru, the Primal Being going second. I've put it on screen.", said)
        val list = Spoken.of("- first\n- second\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n```chart\n{}\n```")
        assertTrue(list.startsWith("first. second."))
        assertTrue(list.endsWith("The rest is on screen."))
        assertTrue(Spoken.of("word ".repeat(600)).length <= 950)
    }

    @Test
    fun theFaceListensAndSpeaks() {
        val mood = MoodTracker()
        assertEquals(Expression.LISTENING, mood.at(AiSignals(hearing = true), 1.0, 1.0))
        assertEquals(Expression.SPEAKING, mood.at(AiSignals(aloud = true), 2.0, 2.0))
    }

    @Test
    fun modelsAndPreferencesStayInBounds() {
        assertEquals(VoiceModel.BASE_EN, VoiceModel.of("nonsense"))
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin", VoiceModel.TINY_EN.url)
        VoiceModel.entries.forEach { assertEquals(64, it.sha256.length, it.id) }
        val p = AiPrefs(voiceModel = "huge", speakReplies = "always", speechRate = 9f).sanitised()
        assertEquals("base.en", p.voiceModel)
        assertEquals(AiPrefs.SPEAK_IN_TALK, p.speakReplies)
        assertEquals(2f, p.speechRate)
    }
}
