package com.kaiharimoto.neue.browser

import com.kaiharimoto.mastertool.core.ai.course.Transcript
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer

actual object VideoListening {
    actual fun missing(model: VoiceModel): String? =
        if (Voice.needsModel(model)) "To listen to a course's videos, download the voice model first (Settings › Voice)." else null

    actual suspend fun transcribe(webm: ByteArray, model: VoiceModel, rate: Double): List<Transcript.Line> = withContext(Dispatchers.Default) {
        val samples = VideoAudio.decode(webm)
        val file = File(Platform.dataDir, "voice/${model.file}")
        val piece = VideoAudio.RATE * PIECE_SECONDS
        val out = ArrayList<Transcript.Line>()
        var at = 0
        while (at < samples.size) {
            ensureActive()
            val chunk = samples.copyOfRange(at, (at + piece).coerceAtMost(samples.size))
            // Silence is skipped: Whisper invents words in it.
            if (VideoAudio.level(chunk) > SILENT) {
                val words = Voice.transcribe(file, model == VoiceModel.BASE, chunk, "A Yu-Gi-Oh! deck guide")
                // The recording ran at [rate]: its seconds are the video's seconds times the rate.
                if (words.isNotBlank()) out += Transcript.Line((at * 1000L / VideoAudio.RATE * rate).toLong(), words)
            }
            at += piece
        }
        out
    }

    private const val PIECE_SECONDS = 28
    private const val SILENT = 0.004
}

/** A recording's sound as 16 kHz mono samples, the way Whisper reads it (FFmpeg through JavaCV, already on the desk). */
object VideoAudio {
    const val RATE = 16_000

    fun decode(bytes: ByteArray): FloatArray {
        val out = ArrayList<FloatArray>()
        FFmpegFrameGrabber(ByteArrayInputStream(bytes), 0).use { g ->
            g.format = "webm"
            g.sampleRate = RATE
            g.audioChannels = 1
            g.sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            g.start()
            while (true) {
                val frame = g.grabSamples() ?: break
                val buf = frame.samples?.firstOrNull() ?: continue
                out += when (buf) {
                    is FloatBuffer -> FloatArray(buf.remaining()).also { buf.get(it) }
                    is ShortBuffer -> ShortArray(buf.remaining()).also { buf.get(it) }.let { s -> FloatArray(s.size) { s[it] / 32768f } }
                    else -> FloatArray(0)
                }
            }
            g.stop()
        }
        val all = FloatArray(out.sumOf { it.size })
        var i = 0
        out.forEach { it.copyInto(all, i); i += it.size }
        return all
    }

    /** How loud [samples] are: their root mean square. */
    fun level(samples: FloatArray): Double = if (samples.isEmpty()) 0.0 else kotlin.math.sqrt(samples.sumOf { it * it.toDouble() } / samples.size)
}
