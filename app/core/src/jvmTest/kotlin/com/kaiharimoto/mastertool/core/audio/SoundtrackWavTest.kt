package com.kaiharimoto.mastertool.core.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test

/**
 * The takeover's soundtrack written to a WAV when asked (`NEUE_TAKEOVER_WAV=<path>`), so the app's own synthesis can be
 * heard beside the storyboard's, without the app. Skipped otherwise.
 */
class SoundtrackWavTest {
    @Test
    fun writesTheSoundtrackWhenAsked() {
        val path = System.getenv("NEUE_TAKEOVER_WAV") ?: return
        val pcm = TakeoverSound.render()
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { data[i * 2] = (pcm[i].toInt() and 0xFF).toByte(); data[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte() }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            fun le32(v: Int) = d.writeInt(Integer.reverseBytes(v))
            fun le16(v: Int) = d.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            d.writeBytes("RIFF"); le32(36 + data.size); d.writeBytes("WAVE")
            d.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(TakeoverSound.RATE); le32(TakeoverSound.RATE * 2); le16(2); le16(16)
            d.writeBytes("data"); le32(data.size); d.write(data)
        }
        File(path).writeBytes(out.toByteArray())
    }
}
