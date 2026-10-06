package com.kaiharimoto.mastertool.core.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test

/**
 * The petting mode's sounds written as WAVs when asked (`NEUE_PET_WAV=<folder>`): one file a sound, its takes one after
 * another, and `all.wav` with every sound in a row, to be heard without the app. Skipped otherwise.
 */
class PetSoundsWavTest {
    @Test
    fun writesTheSoundsWhenAsked() {
        val dir = File(System.getenv("NEUE_PET_WAV") ?: return).apply { mkdirs() }
        val all = PetSounds().render()
        val gap = FloatArray(PetSounds.RATE / 3)
        val everything = ArrayList<FloatArray>()
        for ((sound, takes) in all) {
            val one = takes.flatMap { listOf(it, gap) }
            write(File(dir, "${sound.name.lowercase()}.wav"), one)
            everything += takes[1]
            everything += gap
        }
        write(File(dir, "all.wav"), everything)
    }

    private fun write(file: File, parts: List<FloatArray>) {
        val n = parts.sumOf { it.size }
        val data = ByteArray(n * 2)
        var k = 0
        for (p in parts) for (x in p) {
            val v = (x * .8f * 32767).toInt().coerceIn(-32768, 32767)
            data[k++] = (v and 0xFF).toByte(); data[k++] = (v shr 8 and 0xFF).toByte()
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            fun le32(v: Int) = d.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = d.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            d.writeBytes("RIFF"); le32(36 + data.size); d.writeBytes("WAVE")
            d.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(PetSounds.RATE); le32(PetSounds.RATE * 2); le16(2); le16(16)
            d.writeBytes("data"); le32(data.size); d.write(data)
        }
        file.writeBytes(out.toByteArray())
    }
}
