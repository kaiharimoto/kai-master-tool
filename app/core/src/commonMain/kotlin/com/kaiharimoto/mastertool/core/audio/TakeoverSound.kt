package com.kaiharimoto.mastertool.core.audio

import com.kaiharimoto.mastertool.core.ai.chessy.Takeover
import com.kaiharimoto.mastertool.core.ai.chessy.Takeover.Sound
import kotlin.math.roundToInt

/**
 * The takeover's whole soundtrack, rendered once before it plays (a second or so of work, off the frame thread): every
 * cue of [Takeover.CUES] made by [Synth] (her voice by [PetSounds], kai's nya) and laid at its time, the horn's echo run through the room over the whole
 * alarm, and the mix at the storyboard's master level. Playing it is a matter of streaming samples from wherever the
 * cinematic is, so a skip is a seek and the sound off is a stop.
 */
object TakeoverSound {
    /** The storyboard's master gain. */
    const val MASTER = .32

    const val RATE = 32000

    /** Her voice in the mix, against its ceiling: over the alarm, as she is the one talking. */
    const val VOICE = 1.1

    fun render(rate: Int = RATE, seed: Int = 7): ShortArray {
        val s = Synth(rate, seed)
        val pets = PetSounds(rate, seed)
        var take = 0
        val total = s.samples(Takeover.END.toDouble() + 2)
        val mix = DoubleArray(total)
        val echo = DoubleArray(total)
        fun lay(at: Float, x: DoubleArray, into: DoubleArray = mix) {
            val start = (at * rate).roundToInt()
            for (i in x.indices) if (start + i in into.indices) into[start + i] += x[i]
        }
        for (cue in Takeover.CUES) {
            when (cue.sound) {
                Sound.HORN -> {
                    val send = DoubleArray(s.samples(cue.len + 1.0))
                    lay(cue.at, s.horn(cue.len.toDouble(), send))
                    lay(cue.at, send, echo)
                }
                Sound.TICK -> lay(cue.at, s.tick())
                Sound.STATIC -> lay(cue.at, s.static())
                Sound.CHIRP -> lay(cue.at, s.chirp())
                Sound.BUZZ -> lay(cue.at, s.buzz())
                Sound.CRUSH -> lay(cue.at, s.crush())
                Sound.POWERDOWN -> lay(cue.at, s.powerdown())
                Sound.NYA -> lay(cue.at, s.nya())
                Sound.POPUP -> lay(cue.at, s.popup())
                Sound.VOICE -> cue.voice?.let { v -> lay(cue.at, pets.loud(v, take++ % PetSounds.VARIANTS).also { x -> for (i in x.indices) x[i] *= VOICE }) }
                Sound.KEY -> lay(cue.at, s.key())
                Sound.RESTORE -> lay(cue.at, s.restore())
                Sound.POWERUP -> lay(cue.at, s.powerup())
            }
        }
        val room = s.room(echo)
        for (i in mix.indices) mix[i] += room[i]
        return Synth.pcm16(mix, MASTER)
    }

    /** The sample to start from for a moment of the cinematic. */
    fun sampleAt(t: Float, rate: Int = RATE): Int = (t * rate).roundToInt().coerceAtLeast(0)
}
