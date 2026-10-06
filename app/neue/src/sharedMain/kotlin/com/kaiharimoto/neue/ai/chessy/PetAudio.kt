package com.kaiharimoto.neue.ai.chessy

import com.kaiharimoto.mastertool.core.audio.PetMix
import com.kaiharimoto.mastertool.core.audio.PetSound
import com.kaiharimoto.mastertool.core.audio.PetSounds
import com.kaiharimoto.neue.platform.Playing
import com.kaiharimoto.neue.platform.Speaker
import kotlin.random.Random

/**
 * The petting mode's sound (kai, 1.1.29): its sounds rendered once, off the frame thread, the first time she comes out
 * ([PetSounds], kept for the app's life), and one stream to the speaker while she is out and sound is on, fed by a
 * [PetMix] the frame thread starts sounds in. Where there is no speaker nothing plays and nothing fails.
 */
internal class PetAudio {
    private val mix = PetMix()
    private var stream: Playing? = null
    private var on = false
    private val random = Random(System.nanoTime())

    /** Sound on ([enabled]) or off; off stops the stream and every sound. */
    fun start(enabled: Boolean) {
        on = enabled
        if (!enabled) return stop()
        load()
        if (stream == null) stream = Speaker.stream(PetSounds.RATE) { out -> synchronized(mix) { mix.fill(out) } }
    }

    fun stop() {
        stream?.stop()
        stream = null
        synchronized(mix) { mix.stopAll() }
    }

    /** Plays [sound] at [gain], a take of it chosen at random (or [take]); its id for [cut], or 0. */
    fun play(sound: PetSound, gain: Float = 1f, take: Int = -1): Int {
        if (!on) return 0
        val takes = sounds?.get(sound) ?: return 0
        val k = if (take in takes.indices) take else random.nextInt(takes.size)
        return synchronized(mix) { mix.play(takes[k], gain) }
    }

    /** Stops a sound [play] started. */
    fun cut(id: Int) {
        if (id != 0) synchronized(mix) { mix.stop(id) }
    }

    companion object {
        @Volatile private var sounds: Map<PetSound, List<FloatArray>>? = null
        @Volatile private var loading = false

        private fun load() {
            if (sounds != null || loading) return
            loading = true
            Thread({ sounds = PetSounds().render() }, "neue-pet-sounds").apply { isDaemon = true }.start()
        }
    }
}
