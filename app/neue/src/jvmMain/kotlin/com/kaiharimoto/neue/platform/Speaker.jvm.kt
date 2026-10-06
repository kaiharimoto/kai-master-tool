package com.kaiharimoto.neue.platform

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** The desk's speaker: Java Sound, a line written from a thread of its own in small chunks so a stop is quick. */
actual object Speaker {
    actual fun play(pcm: ShortArray, rate: Int, from: Int): Playing? {
        if (from >= pcm.size) return null
        val line: SourceDataLine = try {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            AudioSystem.getSourceDataLine(format).also { it.open(format, rate / 5 * 2); it.start() }
        } catch (e: Exception) {
            return null
        } catch (e: LinkageError) {
            return null
        }
        val playing = object : Playing {
            @Volatile var stopped = false
            override fun stop() {
                stopped = true
            }
        }
        Thread({
            try {
                val chunk = ByteArray(1024 * 2)
                var i = from
                while (i < pcm.size && !playing.stopped) {
                    val n = minOf(1024, pcm.size - i)
                    for (k in 0 until n) {
                        val v = pcm[i + k].toInt()
                        chunk[k * 2] = (v and 0xFF).toByte()
                        chunk[k * 2 + 1] = (v shr 8 and 0xFF).toByte()
                    }
                    line.write(chunk, 0, n * 2)
                    i += n
                }
                if (playing.stopped) line.flush() else line.drain()
            } catch (_: Exception) {
            } finally {
                runCatching { line.stop(); line.close() }
            }
        }, "neue-speaker").apply { isDaemon = true }.start()
        return playing
    }
}
