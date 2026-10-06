package com.kaiharimoto.mastertool.core.audio

import com.kaiharimoto.mastertool.core.audio.Synth.Biquad
import com.kaiharimoto.mastertool.core.audio.Synth.Osc
import com.kaiharimoto.mastertool.core.audio.Synth.Wave
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Every sound of Chessy's petting mode (kai, 1.1.29: "sound effects for the pet mode for the objects and effects and also
 * have Chessy make cat sounds … chimes and cute sounds when she emotes or affection increases").
 */
enum class PetSound {
    // her
    MEOW, MEW, TRILL, PURR, NYAA, GIGGLE, MRRP, HMPH,

    // what she does
    NOM, SNAP, LAND,

    // the toys
    THUD, BELL, WIND, SCURRY, BOING, SWISH, RUSTLE,

    // the effects
    SPARKLE, CHIME, POP,
}

/**
 * Chessy's petting mode's sounds, made in code like the takeover's (no audio files): her cat sounds by a small formant
 * voice — a buzzing throat (a saw with a breath of noise) through three resonances that glide from one vowel to the next,
 * "m-i-a-o-u" — a purr of throat pulses twenty-odd a second, and the toys' and effects' sounds from oscillators, filters
 * and noise. Each sound comes in a few takes ([VARIANTS], pitches a little apart) so nothing repeats exactly. Pure and
 * deterministic for a seed; rendered once ([render]) and played by [PetMix].
 */
class PetSounds(val rate: Int = RATE, seed: Int = 21) {
    private val rng = Random(seed)
    private val synth = Synth(rate, seed)
    private val dt = 1.0 / rate

    private fun n(seconds: Double) = (seconds * rate).roundToInt().coerceAtLeast(1)
    private fun noise() = rng.nextDouble() * 2 - 1
    private fun env(t: Double, attack: Double, peak: Double, decay: Double) = synth.env(t, attack, peak, decay)
    private fun glide(t: Double, a: Double, b: Double, len: Double) = if (t >= len) b else a * (b / a).pow(t / len)

    /** Every take of every sound, as floats. */
    fun render(): Map<PetSound, List<FloatArray>> = PetSound.entries.associateWith { s -> List(VARIANTS) { k -> make(s, k).toFloats() } }

    fun make(sound: PetSound, take: Int = 0): DoubleArray {
        val p = 1.0 + (take - (VARIANTS - 1) / 2.0) * .06
        return when (sound) {
            PetSound.MEOW -> voice(.62, doubleArrayOf(640.0, 860.0, 720.0, 560.0).scaled(p), MEOW_VOWELS, vibrato = .018)
            PetSound.MEW -> voice(.24, doubleArrayOf(980.0, 1180.0, 1040.0).scaled(p), MEW_VOWELS, vibrato = .01, gain = .8)
            PetSound.NYAA -> voice(1.0, doubleArrayOf(700.0, 980.0, 900.0, 760.0, 620.0).scaled(p), NYAA_VOWELS, vibrato = .045, wobble = 6.5)
            PetSound.HMPH -> voice(.3, doubleArrayOf(420.0, 380.0, 300.0).scaled(p), HMPH_VOWELS, vibrato = .0, gain = .7)
            PetSound.TRILL -> voice(.34, doubleArrayOf(720.0, 1040.0, 980.0).scaled(p), TRILL_VOWELS, roll = 27.0, gain = .85)
            PetSound.MRRP -> voice(.2, doubleArrayOf(560.0, 840.0).scaled(p), TRILL_VOWELS, roll = 30.0, gain = .75)
            PetSound.GIGGLE -> giggle(p)
            PetSound.PURR -> purr(1.7, p)
            PetSound.NOM -> nom(p)
            PetSound.SNAP -> snap(p)
            PetSound.LAND -> land(p)
            PetSound.THUD -> thud(p)
            PetSound.BELL -> bell(p)
            PetSound.WIND -> wind(p)
            PetSound.SCURRY -> scurry(SCURRY_FOR)
            PetSound.BOING -> boing(p)
            PetSound.SWISH -> swish(p)
            PetSound.RUSTLE -> rustle()
            PetSound.SPARKLE -> sparkle(p)
            PetSound.CHIME -> chime(take)
            PetSound.POP -> pop(p)
        }
    }

    private fun DoubleArray.scaled(k: Double) = DoubleArray(size) { this[it] * k }

    /** Vowel targets along a sound: F1, F2, F3 at evenly spaced points. */
    private class Vowels(vararg val points: DoubleArray)

    /**
     * A cat's voice of [len] seconds: a throat at the pitch contour [f0] (evenly spaced points, glided), with vibrato
     * [vibrato] (a share of the pitch), through formants gliding through [vowels]; an "m" at its start (the resonances
     * closed, opening), [roll] Hz of trill if any, and [wobble] Hz of happy warble.
     */
    private fun voice(len: Double, f0: DoubleArray, vowels: Vowels, vibrato: Double = .015, roll: Double = 0.0, wobble: Double = 5.5, gain: Double = 1.0): DoubleArray {
        val total = n(len)
        val out = DoubleArray(total)
        val throat = Osc(Wave.SAW, rate)
        val f = Array(3) { Biquad(rate) }
        val open = Biquad(rate)
        val qs = doubleArrayOf(7.0, 11.0, 13.0)
        val amps = doubleArrayOf(1.0, .7, .35)
        val jitter = rng.nextDouble() * 6.28
        for (i in 0 until total) {
            val t = i * dt
            val u = t / len
            val pitch = along(f0, u) * (1 + vibrato * sin(2 * PI * wobble * t + jitter))
            val src = throat.next(pitch) * .8 + noise() * .12
            var y = 0.0
            for (k in 0..2) {
                val fk = along(DoubleArray(vowels.points.size) { vowels.points[it][k] }, u)
                y += f[k].bandpass(fk, qs[k]).next(src) * amps[k]
            }
            // the "m": the mouth opening over the first 70 ms
            y = open.lowpass(450.0 + 6000.0 * min(1.0, t / .07)).next(y)
            if (roll > 0) y *= .55 + .45 * sin(2 * PI * roll * t).let { it * it }
            val a = min(1.0, t / .03) * min(1.0, (len - t) / (len * .35))
            out[i] = y * a * gain * 1.6
        }
        return out
    }

    /** A value along evenly spaced [points] at [u], 0 to 1, smoothly. */
    private fun along(points: DoubleArray, u: Double): Double {
        if (points.size == 1) return points[0]
        val x = u.coerceIn(0.0, 1.0) * (points.size - 1)
        val i = min(points.size - 2, x.toInt())
        val f = x - i
        val s = f * f * (3 - 2 * f)
        return points[i] + (points[i + 1] - points[i]) * s
    }

    private fun giggle(p: Double): DoubleArray {
        val out = DoubleArray(n(.5))
        for ((k, pitch) in listOf(1150.0, 1040.0, 960.0).withIndex()) {
            val h = voice(.12, doubleArrayOf(pitch * p, pitch * p * 1.08), MEW_VOWELS, vibrato = .0, gain = .7)
            val at = n(k * .13)
            for (i in h.indices) if (at + i < out.size) out[at + i] += h[i]
        }
        return out
    }

    /** A purr: throat pulses about 26 a second through a low, breathy resonance, swelling as she breathes in and out. */
    private fun purr(len: Double, p: Double): DoubleArray {
        val total = n(len)
        val lp = Biquad(rate).lowpass(320.0 * p)
        val body = Biquad(rate).bandpass(140.0 * p, 2.0)
        val o = Osc(Wave.SINE, rate)
        return DoubleArray(total) { i ->
            val t = i * dt
            val pulse = sin(PI * 26.0 * p * t).let { abs(it).pow(3.0) }
            val breath = .65 + .35 * sin(2 * PI * t / .85)
            val y = (lp.next(noise()) * 2.2 + body.next(noise()) * 1.2 + o.next(52.0 * p) * .25) * pulse * breath
            y * min(1.0, t / .15) * min(1.0, (len - t) / .3) * .9
        }
    }

    /** A bite: two clicks of teeth and a soft thump, "nom". */
    private fun nom(p: Double): DoubleArray {
        val out = DoubleArray(n(.16))
        val hp = Biquad(rate).highpass(2200.0)
        val o = Osc(Wave.SINE, rate)
        for (i in out.indices) {
            val t = i * dt
            val click = (env(t, .0005, .9, .006) + env(t - .05, .0005, .6, .006)) * hp.next(noise())
            val thump = o.next(glide(t, 240.0 * p, 110.0 * p, .07)) * env(t, .002, .7, .09)
            out[i] = click + thump
        }
        return out
    }

    /** Jaws snapping on nothing: one sharp click and a breath of air. */
    private fun snap(p: Double): DoubleArray {
        val hp = Biquad(rate).highpass(1800.0 * p)
        val bp = Biquad(rate).bandpass(1200.0 * p, 1.0)
        return DoubleArray(n(.14)) { i -> val t = i * dt; hp.next(noise()) * env(t, .0005, .8, .008) + bp.next(noise()) * env(t, .01, .25, .1) }
    }

    /** Her landing: a soft pat on the floor. */
    private fun land(p: Double): DoubleArray {
        val lp = Biquad(rate).lowpass(380.0 * p)
        val o = Osc(Wave.SINE, rate)
        return DoubleArray(n(.16)) { i -> val t = i * dt; lp.next(noise()) * env(t, .002, 1.2, .06) + o.next(glide(t, 120.0 * p, 60.0, .1)) * env(t, .002, .6, .12) }
    }

    /** The yarn on the floor: a soft, woolly thud. */
    private fun thud(p: Double): DoubleArray {
        val lp = Biquad(rate).lowpass(520.0 * p)
        val o = Osc(Wave.SINE, rate)
        return DoubleArray(n(.18)) { i -> val t = i * dt; o.next(glide(t, 165.0 * p, 70.0, .12)) * env(t, .002, .8, .14) + lp.next(noise()) * env(t, .001, .8, .05) }
    }

    /** Her bell: a little jingle, three strikes of a small bell's partials with a rattle. */
    private fun bell(p: Double): DoubleArray {
        val out = DoubleArray(n(.9))
        val partials = doubleArrayOf(1.0, 2.32, 4.25, 6.63)
        val levels = doubleArrayOf(.5, .3, .18, .1)
        val hp = Biquad(rate).highpass(5000.0)
        for ((k, at) in listOf(0.0, .075, .16).withIndex()) {
            val base = 1760.0 * p * (1 + k * .012)
            val oscs = partials.map { Osc(Wave.SINE, rate) }
            val start = n(at)
            val strike = 1.0 - k * .22
            for (i in start until out.size) {
                val t = (i - start) * dt
                var y = 0.0
                for (j in partials.indices) y += oscs[j].next(base * partials[j]) * levels[j] * env(t, .001, 1.0, .55 / (1 + j * .6))
                out[i] += (y + hp.next(noise()) * env(t, .0005, .35, .01)) * strike
            }
        }
        return out
    }

    /** Winding the mouse: a ratchet's clicks, a little higher each time. */
    private fun wind(p: Double): DoubleArray {
        val out = DoubleArray(n(.5))
        val bp = Biquad(rate).bandpass(2800.0 * p, 6.0)
        for (k in 0 until 8) {
            val start = n(k * .055)
            val o = Osc(Wave.TRIANGLE, rate)
            for (i in start until min(out.size, start + n(.03))) {
                val t = (i - start) * dt
                out[i] += bp.next(noise()) * env(t, .0004, 1.4, .012) + o.next((1500.0 + k * 60) * p) * env(t, .0005, .2, .01)
            }
        }
        return out
    }

    /** The mouse running: quick little ticks of its feet over a faint whirr of clockwork, for [len] seconds. */
    private fun scurry(len: Double): DoubleArray {
        val total = n(len)
        val out = DoubleArray(total)
        val whirr = Osc(Wave.SQUARE, rate)
        val lp = Biquad(rate).lowpass(500.0)
        val bp = Biquad(rate).bandpass(3600.0, 4.0)
        var next = 0
        var tick = -1
        for (i in 0 until total) {
            val t = i * dt
            if (i >= next) { tick = i; next = i + n(.055 + rng.nextDouble() * .03) }
            val since = (i - tick) * dt
            val fade = min(1.0, t / .1) * min(1.0, (len - t) / .6)
            out[i] = (bp.next(noise()) * env(since, .0004, .9, .01) + lp.next(whirr.next(95.0)) * .08) * fade * .7
        }
        return out
    }

    /** The mouse bouncing off her: a spring's "boing". */
    private fun boing(p: Double): DoubleArray {
        val o = Osc(Wave.TRIANGLE, rate)
        return DoubleArray(n(.55)) { i ->
            val t = i * dt
            val f = glide(t, 220.0 * p, 440.0 * p, .25) * (1 + .22 * exp(-t * 5) * sin(2 * PI * 13 * t))
            o.next(f) * env(t, .004, .7, .5)
        }
    }

    /** The feather sweeping through the air. */
    private fun swish(p: Double): DoubleArray {
        val bp = Biquad(rate)
        return DoubleArray(n(.2)) { i -> val t = i * dt; bp.bandpass(glide(t, 700.0 * p, 2600.0 * p, .18), 1.3).next(noise()) * env(t, .05, .6, .14) }
    }

    /** The catnip pouch: a crinkle of dry leaves. */
    private fun rustle(): DoubleArray {
        val out = DoubleArray(n(.45))
        val hp = Biquad(rate).highpass(2500.0)
        repeat(34) {
            val start = n(rng.nextDouble() * .4)
            val g = .3 + rng.nextDouble() * .7
            for (i in start until min(out.size, start + n(.008))) out[i] += noise() * g * env((i - start) * dt, .0003, 1.0, .006)
        }
        return DoubleArray(out.size) { hp.next(out[it]) * .8 }
    }

    /** A twinkle for sparkles: three high pings, quick. */
    private fun sparkle(p: Double): DoubleArray {
        val out = DoubleArray(n(.5))
        for ((k, f) in listOf(2637.0, 3520.0, 4186.0).withIndex()) {
            val o = Osc(Wave.SINE, rate)
            val o2 = Osc(Wave.SINE, rate)
            val start = n(k * .05)
            for (i in start until out.size) {
                val t = (i - start) * dt
                out[i] += (o.next(f * p) + o2.next(f * p * 2.01) * .2) * env(t, .002, .28, .28)
            }
        }
        return out
    }

    /** Her fondness grown: four bells rising on a pentatonic scale, from a step that rises with [take]. */
    private fun chime(take: Int): DoubleArray {
        val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)
        val out = DoubleArray(n(1.3))
        for (k in 0 until 4) {
            val f = scale[(take + k).coerceIn(0, scale.size - 1)]
            val o = Osc(Wave.SINE, rate)
            val o2 = Osc(Wave.SINE, rate)
            val start = n(k * .085)
            for (i in start until out.size) {
                val t = (i - start) * dt
                out[i] += (o.next(f) + o2.next(f * 2.76) * .22) * env(t, .003, .3, .9)
            }
        }
        return out
    }

    /** An emote: a round little "bloop". */
    private fun pop(p: Double): DoubleArray {
        val o = Osc(Wave.SINE, rate)
        return DoubleArray(n(.12)) { i -> val t = i * dt; o.next(glide(t, 420.0 * p, 980.0 * p, .07)) * env(t, .003, .55, .09) }
    }

    private fun DoubleArray.toFloats(): FloatArray {
        // every sound brought to one loudness ceiling, so the mixer's gains mean the same for each
        val peak = maxOf { abs(it) }.coerceAtLeast(1e-9)
        val k = (CEILING / peak).coerceAtMost(4.0)
        return FloatArray(size) { (this[it] * k).toFloat() }
    }

    companion object {
        const val RATE = 32000
        const val VARIANTS = 3
        const val CEILING = .9
        const val SCURRY_FOR = 4.5

        private val MEOW_VOWELS = Vowels(doubleArrayOf(380.0, 2250.0, 3000.0), doubleArrayOf(850.0, 1650.0, 2800.0), doubleArrayOf(700.0, 1100.0, 2700.0), doubleArrayOf(420.0, 820.0, 2600.0))
        private val MEW_VOWELS = Vowels(doubleArrayOf(360.0, 2400.0, 3100.0), doubleArrayOf(700.0, 1800.0, 2900.0))
        private val NYAA_VOWELS = Vowels(doubleArrayOf(330.0, 2350.0, 3000.0), doubleArrayOf(850.0, 1700.0, 2800.0), doubleArrayOf(900.0, 1500.0, 2700.0), doubleArrayOf(800.0, 1350.0, 2700.0))
        private val HMPH_VOWELS = Vowels(doubleArrayOf(300.0, 1000.0, 2400.0), doubleArrayOf(450.0, 900.0, 2400.0))
        private val TRILL_VOWELS = Vowels(doubleArrayOf(420.0, 1300.0, 2600.0), doubleArrayOf(500.0, 1100.0, 2500.0), doubleArrayOf(380.0, 900.0, 2500.0))
    }
}

/**
 * The petting mode's mixer: sounds started at any moment ([play], each with its gain), summed into whatever the speaker
 * asks for next ([fill]), at most [MAX] at once (the oldest let go), softly limited so many at once never clip. Pure;
 * the caller keeps it to one thread at a time.
 */
class PetMix(private val master: Float = .55f) {
    private class Voice(val id: Int, val samples: FloatArray, val gain: Float, var at: Int)

    private val voices = ArrayList<Voice>()
    private var ids = 0

    val playing: Int get() = voices.size

    /** Starts [samples] at [gain]; the id [stop] takes, or 0 when nothing started. */
    fun play(samples: FloatArray, gain: Float = 1f): Int {
        if (gain <= 0f) return 0
        val id = ++ids
        voices += Voice(id, samples, gain, 0)
        while (voices.size > MAX) voices.removeAt(0)
        return id
    }

    /** Stops the sound [play] gave [id] to, if it still plays. */
    fun stop(id: Int) {
        voices.removeAll { it.id == id }
    }

    fun stopAll() = voices.clear()

    /** The next [out].size samples, 16-bit; silence when nothing plays. */
    fun fill(out: ShortArray) {
        for (i in out.indices) {
            var s = 0f
            for (v in voices) if (v.at + i < v.samples.size) s += v.samples[v.at + i] * v.gain
            val y = tanh((s * master).toDouble()).toFloat()
            out[i] = (y * 32000f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        for (v in voices) v.at += out.size
        voices.removeAll { it.at >= it.samples.size }
    }

    companion object {
        const val MAX = 14
    }
}
