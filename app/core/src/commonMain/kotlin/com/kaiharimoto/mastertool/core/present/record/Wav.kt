package com.kaiharimoto.mastertool.core.present.record

/**
 * A take's sound as a WAV file (1.1.13): 16-bit PCM, little-endian, written as it is heard behind a header whose
 * lengths are put in when recording stops. A take cut short by a crash has a header saying nothing was written:
 * [read] then counts the samples from the file's own length, so the sound is not lost.
 */
object Wav {
    const val HEADER = 44

    /** The 44-byte header of [dataBytes] of [channels]-channel, 16-bit sound at [rate]. */
    fun header(rate: Int, channels: Int, dataBytes: Long): ByteArray {
        val b = ByteArray(HEADER)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun int(at: Int, v: Long) { for (i in 0 until 4) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        fun short(at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
        val data = dataBytes.coerceIn(0L, 0xFFFF_FFFFL - 36)
        ascii(0, "RIFF"); int(4, 36 + data); ascii(8, "WAVE")
        ascii(12, "fmt "); int(16, 16); short(20, 1); short(22, channels)
        int(24, rate.toLong()); int(28, rate.toLong() * channels * 2); short(32, channels * 2); short(34, 16)
        ascii(36, "data"); int(40, data)
        return b
    }

    /** What a WAV header says: the rate, the channels, and where its samples begin and how many bytes there are. */
    data class Info(val rate: Int, val channels: Int, val dataStart: Int, val dataBytes: Long) {
        /** Samples per channel. */
        val frames: Long get() = dataBytes / (2L * channels.coerceAtLeast(1))
        val durationMs: Long get() = if (rate <= 0) 0L else frames * 1000L / rate
    }

    /**
     * The header of a 16-bit PCM WAV whose first bytes are [head] and whose whole length is [fileLength]; null when it
     * is not one. A data length of nothing, or past the file's end, is read as "to the end of the file".
     */
    fun read(head: ByteArray, fileLength: Long): Info? {
        if (head.size < HEADER) return null
        fun ascii(at: Int, n: Int) = (0 until n).map { head[at + it].toInt().toChar() }.joinToString("")
        fun int(at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((head[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun short(at: Int): Int = (head[at].toInt() and 0xFF) or ((head[at + 1].toInt() and 0xFF) shl 8)
        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE") return null
        // Walk the chunks: "fmt " then "data", with anything between skipped.
        var at = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (at + 8 <= head.size) {
            val id = ascii(at, 4)
            val size = int(at + 4)
            if (id == "fmt " && at + 24 <= head.size) {
                if (short(at + 8) != 1) return null
                channels = short(at + 10)
                rate = int(at + 12).toInt()
                bits = short(at + 22)
            } else if (id == "data") {
                if (bits != 16 || channels <= 0 || rate <= 0) return null
                val start = at + 8
                val left = (fileLength - start).coerceAtLeast(0L)
                val bytes = if (size <= 0L || size > left) left else size
                return Info(rate, channels, start, bytes - bytes % (2L * channels))
            }
            at += 8 + size.toInt().coerceAtLeast(0) + (size.toInt() and 1)
        }
        return null
    }
}
