package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.ai.voice.CommandClip
import com.kaiharimoto.mastertool.core.ai.voice.Pcm
import com.kaiharimoto.neue.platform.Voice
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The duel's spoken commands through the desk's Whisper (1.0.87): each recording written out the old way
 * (the whole 30 s window) and the command way (`CommandTuning`), timed, with what was heard. Like
 * [VoiceProbeTest] it needs `NEUE_WHISPER_MODEL` (a ggml model) and `NEUE_WHISPER_WAV` — here a 16 kHz mono
 * recording or a folder of them — and passes quietly where they are not given, CI included.
 *
 * Measured in 1.0.87's making, 4 cores, espeak-ng speaking eight commands: see `docs/NEUE.md` §4p.
 */
class VoiceCommandProbeTest {
    @Test
    fun commandsAreWrittenOutFasterAndAsWell() {
        val model = System.getenv("NEUE_WHISPER_MODEL")?.let(::File)?.takeIf { it.isFile } ?: return
        val given = System.getenv("NEUE_WHISPER_WAV")?.let(::File)?.takeIf { it.exists() } ?: return
        val wavs = if (given.isDirectory) given.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name } else listOf(given)
        val hints = "Ash Blossom & Joyous Spring, Called by the Grave, Maxx \"C\", summon, set, activate, attack, monster zone, spell zone, battle phase, end turn, graveyard, no response"
        fun samples(f: File) = Pcm.samples(AudioSystem.getAudioInputStream(f).use { it.readAllBytes() })
        // The model loaded, and whisper.cpp's buffers made, before anything is timed: what prewarm does.
        val loadStart = System.nanoTime()
        Voice.transcribe(model, anyLanguage = false, samples = FloatArray(Pcm.RATE / 2), hints = "", command = true)
        println("whisper: loaded and warmed in ${(System.nanoTime() - loadStart) / 1_000_000} ms")
        var wholeMs = 0L
        var commandMs = 0L
        wavs.forEach { wav ->
            val s = samples(wav)
            val t0 = System.nanoTime()
            val whole = Voice.transcribe(model, anyLanguage = false, samples = s, hints = hints, command = false)
            val t1 = System.nanoTime()
            // The command way, as a key let go hands it over: the clicks cut off, the loop folded.
            val command = CommandClip.unrepeat(Voice.transcribe(model, anyLanguage = false, samples = CommandClip.trim(s), hints = hints, command = true))
            val t2 = System.nanoTime()
            wholeMs += (t1 - t0) / 1_000_000
            commandMs += (t2 - t1) / 1_000_000
            println(
                "whisper ${wav.name} (%.1f s): whole window %d ms \"%s\" · command %d ms \"%s\"".format(
                    s.size.toDouble() / Pcm.RATE, (t1 - t0) / 1_000_000, whole, (t2 - t1) / 1_000_000, command,
                ),
            )
            assertTrue(command.isNotBlank(), "${wav.name} heard nothing in command mode")
        }
        println("whisper: ${wavs.size} clips, whole window $wholeMs ms, command $commandMs ms")
    }
}
