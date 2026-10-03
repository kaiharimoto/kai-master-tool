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
    fun pushToTalkEndsOnTheKeyNotOnAPause() {
        // A long pause mid-command is not the end: only letting go of the key is (1.0.87).
        assertEquals(SpeechGate.State.SPEAKING, run(SpeechGate.pushToTalk(), listOf(0.005f to 5, 0.12f to 10, 0.006f to 40, 0.1f to 3)))
        // A key held with nothing said, or one whose release was lost, still lets go.
        assertEquals(SpeechGate.State.DONE_NOTHING, run(SpeechGate.pushToTalk(), listOf(0.004f to 90)))
        assertEquals(SpeechGate.State.DONE_TOO_LONG, run(SpeechGate.pushToTalk(), listOf(0.005f to 3, 0.2f to 250)))
    }

    @Test
    fun pushToTalkHearsSpeechFromTheFirstSlice() {
        // Speaking the moment the key goes down: no room is learnt from the voice, so it is speech, not "nothing" at 8 s.
        assertEquals(SpeechGate.State.SPEAKING, run(SpeechGate.pushToTalk(), listOf(0.12f to 85)))
        // The talk gate, learning the room from that same voice, would have heard nothing.
        assertEquals(SpeechGate.State.DONE_NOTHING, run(SpeechGate(), listOf(0.12f to 85)))
    }

    private fun tone(seconds: Double, amp: Float) = FloatArray((seconds * Pcm.RATE).toInt()) { (amp * sin(2 * PI * 220 * it / Pcm.RATE)).toFloat() }

    @Test
    fun theKeysClicksAndTooLittleVoiceNeverReachTheTranscriber() {
        // A click at each end: cut off, so a tap of the key alone is no voice at all.
        val click = FloatArray(Pcm.RATE / 2).also { s -> repeat(800) { s[it] = 0.5f; s[s.size - 1 - it] = 0.5f } }
        assertTrue(CommandClip.voiced(click) > 0.0)
        assertEquals(0.0, CommandClip.voiced(CommandClip.trim(click)))
        assertTrue(CommandClip.voiced(CommandClip.trim(click)) < CommandClip.VOICED_LEAST)
        // A spoken "yes" is enough.
        val yes = FloatArray(Pcm.RATE / 5) + tone(0.3, 0.2f) + FloatArray(Pcm.RATE / 5)
        assertEquals(0.3, CommandClip.voiced(CommandClip.trim(yes)), 0.03)
        // Too short to cut is kept whole.
        assertEquals(100, CommandClip.trim(FloatArray(100)).size)
    }

    @Test
    fun whatWhisperInventsForNothingIsRefused() {
        listOf("Thanks for watching!", " Thank you for watching. ", "Bye.", "Subtitles by the Amara.org community", "[BLANK_AUDIO]", "Um.").forEach {
            assertTrue(CommandClip.isNothing(it), it)
        }
        listOf("yes", "No response.", "end turn", "summon Ash Blossom to monster zone 3").forEach { assertFalse(CommandClip.isNothing(it), it) }
        assertTrue(Hints.isNothing("Thanks for watching."))
        // A card's name from its priming, out of almost no voice, is an echo; said at length, or a one-word hint, it is not.
        val hints = Hints.prompt(listOf("Ash Blossom & Joyous Spring", "Nibiru, the Primal Being", "yes"))
        assertTrue(CommandClip.echoes("Ash Blossom & Joyous Spring.", hints, voiced = 0.3))
        assertFalse(CommandClip.echoes("Ash Blossom & Joyous Spring.", hints, voiced = 1.1))
        assertFalse(CommandClip.echoes("yes", hints, voiced = 0.2))
        assertFalse(CommandClip.echoes("summon Ash Blossom & Joyous Spring", hints, voiced = 0.3))
    }

    @Test
    fun aLoopIsSaidOnce() {
        assertEquals("no response", CommandClip.unrepeat("no response, no response, no response, no response, no"))
        assertEquals("and turn", CommandClip.unrepeat("and turn, and turn,"))
        assertEquals("summon, Ash Blossom to monster zone 3", CommandClip.unrepeat("summon, Ash Blossom to monster zone 3, summon, Ash Blossom to monster zone 3, sum"))
        // Anything that is not a whole repeat is kept as written.
        assertEquals("attack their monster 1 with my monster 3", CommandClip.unrepeat(" attack their monster 1 with my monster 3 "))
        assertEquals("yes", CommandClip.unrepeat("yes"))
        assertEquals("set h4 to spell 2", CommandClip.unrepeat("set h4 to spell 2"))
    }

    @Test
    fun speakingBackIsOffUnlessAskedFor() {
        assertFalse(com.kaiharimoto.mastertool.core.duel.DuelPrefs().speak)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(com.kaiharimoto.mastertool.core.duel.DuelPrefs.serializer(), """{"aiSeat":0,"autoDraw":false}""")
        assertFalse(old.speak, "an older document reads with it off")
    }

    @Test
    fun aCommandIsReadForItsOwnLengthNotThirtySeconds() {
        // Two seconds: 100 frames and the margin, under the floor, so the floor — half Whisper's window.
        assertEquals(CommandTuning.FLOOR, CommandTuning.audioCtx(2.0))
        assertTrue(CommandTuning.FLOOR < CommandTuning.FULL)
        assertEquals(CommandTuning.FLOOR, CommandTuning.audioCtx(0.0))
        assertEquals(CommandTuning.FLOOR, CommandTuning.audioCtx(Double.NaN))
        // Eighteen seconds: 900 + 64.
        assertEquals(964, CommandTuning.audioCtx(18.0))
        // A fraction of a frame rounds up, never cutting the last of the clip.
        assertEquals(901 + CommandTuning.MARGIN, CommandTuning.audioCtx(18.01))
        // Never past Whisper's own window.
        assertEquals(CommandTuning.FULL, CommandTuning.audioCtx(29.0))
        assertEquals(CommandTuning.FULL, CommandTuning.audioCtx(120.0))
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
