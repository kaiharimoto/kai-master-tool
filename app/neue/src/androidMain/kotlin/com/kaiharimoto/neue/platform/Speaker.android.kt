package com.kaiharimoto.neue.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Android's speaker: an [AudioTrack] streamed from a thread of its own, as a game's sound effects are. */
actual object Speaker {
    actual fun play(pcm: ShortArray, rate: Int, from: Int): Playing? {
        if (from >= pcm.size) return null
        val track = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return null
        }
        val playing = object : Playing {
            @Volatile var stopped = false
            override fun stop() {
                stopped = true
                runCatching { track.pause(); track.flush() }
            }
        }
        Thread({
            try {
                track.play()
                var i = from
                while (i < pcm.size && !playing.stopped) {
                    val n = minOf(2048, pcm.size - i)
                    val wrote = track.write(pcm, i, n)
                    if (wrote <= 0) break
                    i += wrote
                }
            } catch (_: Exception) {
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }, "neue-speaker").apply { isDaemon = true }.start()
        return playing
    }

    actual fun stream(rate: Int, fill: (ShortArray) -> Unit): Playing? {
        val track = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate / 20 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            return null
        }
        val playing = object : Playing {
            @Volatile var stopped = false
            override fun stop() {
                stopped = true
                runCatching { track.pause(); track.flush() }
            }
        }
        Thread({
            try {
                track.play()
                val chunk = ShortArray(256)
                while (!playing.stopped) {
                    fill(chunk)
                    if (track.write(chunk, 0, chunk.size) <= 0) break
                }
            } catch (_: Exception) {
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
            }
        }, "neue-speaker-stream").apply { isDaemon = true }.start()
        return playing
    }
}
