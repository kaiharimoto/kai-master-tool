package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.voice.Pcm
import com.kaiharimoto.neue.platform.Voice
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desk's speech-to-text end to end (1.0.57): whisper-jni's natives load and write out a real
 * recording with a real model. Both are large, so this runs only where they are given —
 * `NEUE_WHISPER_MODEL` (a ggml model) and `NEUE_WHISPER_WAV` (16 kHz mono) — and passes quietly
 * elsewhere, CI included.
 */
class VoiceProbeTest {
    @Test
    fun whisperWritesOutARecording() {
        val model = System.getenv("NEUE_WHISPER_MODEL")?.let(::File)?.takeIf { it.isFile } ?: return
        val wav = System.getenv("NEUE_WHISPER_WAV")?.let(::File)?.takeIf { it.isFile } ?: return
        val bytes = AudioSystem.getAudioInputStream(wav).use { it.readAllBytes() }
        val text = Voice.transcribe(model, anyLanguage = false, samples = Pcm.samples(bytes), hints = "Ash Blossom & Joyous Spring")
        println("whisper heard: $text")
        assertTrue("country" in text.lowercase(), text)
    }
}
