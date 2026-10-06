package com.kaiharimoto.mastertool.core.audio

import com.kaiharimoto.mastertool.core.ai.chessy.Takeover
import com.kaiharimoto.mastertool.core.ai.chessy.TakeoverHorn
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthTest {
    @Test
    fun theHornIsOnePitchAt55AndNeverClips() {
        val s = Synth(32000, seed = 3)
        for (k in 0 until 3) {
            val blast = s.horn(TakeoverHorn.BLAST_S.toDouble())
            assertTrue(blast.none { it.isNaN() })
            val peak = Synth.peak(blast)
            assertTrue(peak in .02..1.0, "peak $peak")
            // the pitch, by autocorrelation over the steady middle
            val from = (s.rate * .2).toInt()
            val seg = blast.copyOfRange(from, from + 4096)
            var best = 0.0
            var lag = 0
            for (l in s.rate / 400..s.rate / 30) {
                var c = 0.0
                for (i in 0 until seg.size - l) c += seg[i] * seg[i + l]
                if (c > best) { best = c; lag = l }
            }
            val hz = s.rate.toDouble() / lag
            assertTrue(abs(hz - TakeoverHorn.PITCH_HZ) < 3, "blast $k at $hz Hz")
        }
    }

    @Test
    fun everySoundIsQuietAtItsEndAndBounded() {
        val s = Synth(32000, seed = 1)
        val sounds = listOf(s.static(), s.tick(), s.chirp(), s.buzz(), s.crush(), s.powerdown(), s.nya(), s.huh(), s.key(), s.restore(), s.powerup())
        for ((i, x) in sounds.withIndex()) {
            assertTrue(x.none { it.isNaN() }, "sound $i")
            assertTrue(Synth.peak(x) <= 1.0, "sound $i peaks ${Synth.peak(x)}")
            assertTrue(abs(x.last()) < .01, "sound $i ends quiet")
        }
    }

    @Test
    fun theSoundtrackIsTheCinematicsLengthAndAlwaysInRange() {
        val pcm = TakeoverSound.render(rate = 16000)
        assertTrue(pcm.size >= (Takeover.END * 16000).toInt())
        // some sound during the alarm, silence well after the end
        val alarm = pcm.copyOfRange(16000 * 3, 16000 * 4)
        assertTrue(alarm.any { abs(it.toInt()) > 500 })
        val clipped = pcm.count { it == Short.MAX_VALUE || it == Short.MIN_VALUE }
        assertTrue(clipped < pcm.size / 1000, "clipped $clipped")
        assertEquals(16000 * 2, TakeoverSound.sampleAt(2f, 16000))
    }
}
