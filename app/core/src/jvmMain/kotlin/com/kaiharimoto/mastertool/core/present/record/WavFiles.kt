package com.kaiharimoto.mastertool.core.present.record

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A take's sound written as a WAV (1.1.13; shared by the desk and Android since I.9): 16-bit PCM behind a header whose
 * lengths go in when it is closed. A take cut short by a crash still reads ([Wav.read] counts to the file's end).
 */
class WavWriter(file: File, private val rate: Int, private val channels: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(rate, channels, 0))
    }
    private var bytes = 0L

    @Synchronized
    fun write(data: ByteArray, length: Int) {
        out.write(data, 0, length)
        bytes += length
    }

    /** [frames] of silence, for a microphone that is not there (the synthetic one). */
    @Synchronized
    fun silence(frames: Long) {
        val chunk = ByteArray(4096)
        var left = frames * 2 * channels
        while (left > 0) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            out.write(chunk, 0, n)
            bytes += n
            left -= n
        }
    }

    @Synchronized
    override fun close() {
        runCatching {
            out.seek(0)
            out.write(Wav.header(rate, channels, bytes))
        }
        runCatching { out.close() }
    }
}

/**
 * A take's WAV read in order, in the samples each video frame takes ([RenderPlan.samplesThrough]): the desk's render and
 * Android's (I.9) read the sound the same way.
 */
class WavReader private constructor(private val file: RandomAccessFile, val rate: Int, val channels: Int, private val end: Long) : Closeable {
    private var given = 0L

    /** The samples up to [through] (per channel) not handed out yet, interleaved; silence past the file's end. */
    fun take(through: Long): ShortArray {
        val want = (through - given).coerceAtLeast(0)
        given += want
        val out = ShortArray((want * channels).toInt())
        val bytes = ByteArray(out.size * 2)
        val left = (end - file.filePointer).coerceAtLeast(0)
        val n = minOf(bytes.size.toLong(), left).toInt()
        if (n > 0) file.readFully(bytes, 0, n)
        ByteBuffer.wrap(bytes, 0, n - n % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out, 0, (n - n % 2) / 2)
        return out
    }

    override fun close() {
        runCatching { file.close() }
    }

    companion object {
        /** [f] opened for reading, or null when it is not a 16-bit PCM WAV. */
        fun open(f: File): WavReader? {
            val raf = RandomAccessFile(f, "r")
            return try {
                val head = ByteArray(minOf(4096L, raf.length()).toInt())
                raf.readFully(head)
                val info = Wav.read(head, raf.length()) ?: run { raf.close(); return null }
                raf.seek(info.dataStart.toLong())
                WavReader(raf, info.rate, info.channels, info.dataStart + info.dataBytes)
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        }
    }
}
