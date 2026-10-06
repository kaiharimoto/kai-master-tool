package com.kaiharimoto.mastertool.core.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PetSoundsTest {
    private val all = PetSounds().render()

    @Test
    fun everySoundIsMadeInEveryTakeAndBroughtToTheCeiling() {
        assertEquals(PetSound.entries.toSet(), all.keys)
        for ((sound, takes) in all) {
            assertEquals(PetSounds.VARIANTS, takes.size)
            for (x in takes) {
                assertTrue(x.size > 1000, "$sound is too short: ${x.size}")
                assertTrue(x.all { it.isFinite() }, "$sound has a bad sample")
                val peak = x.maxOf { abs(it) }
                assertTrue(peak in .5f..(PetSounds.CEILING.toFloat() + .01f), "$sound peaks at $peak")
            }
        }
    }

    @Test
    fun aNyaIsShortAndAPurrPulses() {
        val nya = all.getValue(PetSound.NYA)[1]
        assertEquals(PetSounds.NYA_LENGTH, nya.size / PetSounds.RATE.toDouble(), .01)
        // the purr: loudness in 10 ms windows rises and falls many times a second
        val purr = all.getValue(PetSound.PURR)[1]
        val w = PetSounds.RATE / 100
        val rms = (0 until purr.size / w).map { k -> sqrt((k * w until (k + 1) * w).sumOf { (purr[it] * purr[it]).toDouble() } / w) }
        val mid = rms.subList(20, rms.size - 30)
        var peaks = 0
        for (i in 1 until mid.size - 1) if (mid[i] > mid[i - 1] && mid[i] >= mid[i + 1] && mid[i] > mid.average()) peaks++
        assertTrue(peaks in 15..60, "purr pulses: $peaks")
    }

    @Test
    fun theTakesDiffer() {
        val takes = all.getValue(PetSound.MEW)
        assertTrue(takes[0].size != takes[2].size || (0 until 2000).any { takes[0][it] != takes[2][it] })
    }

    @Test
    fun theMixerSumsAndLetsGo() {
        val mix = PetMix()
        val out = ShortArray(512)
        mix.fill(out)
        assertTrue(out.all { it.toInt() == 0 }, "silence when nothing plays")
        val beep = FloatArray(1000) { .9f }
        repeat(20) { mix.play(beep) }
        assertEquals(PetMix.MAX, mix.playing)
        mix.fill(out)
        assertTrue(out.all { it in 1..32767 }, "loud but never past the top")
        mix.fill(out)
        mix.fill(out)
        assertEquals(0, mix.playing)
    }
}
