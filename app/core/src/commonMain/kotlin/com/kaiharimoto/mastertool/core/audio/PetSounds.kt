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
    NYA, MEW, TRILL, PURR, NYAA, GIGGLE, MRRP, HMPH,

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
            PetSound.NYA -> nya(NYA, p * NYA.pitch)
            PetSound.MEW -> nya(MEW, p * MEW.pitch)
            PetSound.NYAA -> nya(NYAA, p * NYAA.pitch)
            PetSound.HMPH -> nya(HMPH, p * HMPH.pitch)
            PetSound.TRILL -> nya(TRILL, p * TRILL.pitch)
            PetSound.MRRP -> nya(MRRP, p * MRRP.pitch)
            PetSound.GIGGLE -> giggle(p)
            PetSound.PURR -> purr(1.7, p)
            PetSound.NOM -> nom(p)
            PetSound.SNAP -> snap(p * SNAP_PITCH)
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

    /**
     * A cat's "nya" (kai, 1.1.29: the meow "sounds too human … I want it to sound like a real cat, maybe not meow but a
     * nya instead since it's Japanese themed"; then "it sounds like the cat is in distress and not loving or
     * affectionate"). Built on what was measured of real meows: Nicastro (2004), 96 calls, a tonal voice averaging 609 Hz;
     * Schötz's Meowsic work, where the vowel's change is one resonance (F1) moving with the jaw, F2 and F3 high and still,
     * and friendly calls rise in pitch while stressed ones arch high and fall. So one jaw gesture opens F1 from [Nya.shut]
     * to [Nya.open] and lets it ease back ([Nya.close]); the pitch follows three points ([Nya.start], [Nya.peak],
     * [Nya.end]) with an optional lift at the end ([Nya.tail]); an onset may be rolled ([Nya.trill], the "mrr" cats put
     * before a greeting) or a closed-mouth hum ([Nya.hum]), and the end may close into an "n" ([Nya.nasal]). The throat
     * is a sum of harmonics falling away by [Nya.tilt], with a little breath, wander and flutter ([Nya.rough]), an
     * optional purr under it, and gentle fades. The tuner page holds the same function line for line, with every option
     * kai chose from as a set of these knobs.
     */
    private fun nya(k: Nya, p: Double): DoubleArray {
        val len = k.length
        val total = n(len)
        val out = DoubleArray(total)
        val f1 = Biquad(rate)
        val f2 = Biquad(rate)
        val f3 = Biquad(rate)
        val lips = Biquad(rate)
        val air = Biquad(rate).highpass(1800.0)
        val rumble = Biquad(rate).lowpass(180.0)
        val top = maxOf(k.start, k.peak, k.end) * (1 + k.tail) * p
        val count = maxOf(1, (rate * .45 / top).toInt())
        val phases = DoubleArray(count) { rng.nextDouble() }
        val vph = rng.nextDouble() * 6.28
        val b0 = min(len * .5, k.trill + k.hum)
        val b1 = maxOf(b0 + len * .3, len - k.nasal)
        var ph = 0.0
        var drift = 0.0
        for (i in 0 until total) {
            val t = i * dt
            val w = t / len
            val v = (t - b0) / (b1 - b0)
            // the mouth: shut (barely open in a trill) before the body, open and easing back through it, shut for an "n"
            var j = when {
                v < 0 -> if (t < k.trill) .12 else 0.0
                v > 1 -> 0.0
                else -> smooth(v / k.jawAt) * (1 - k.close * smooth((v - k.jawAt) / (1 - k.jawAt)))
            }
            val d = (v - .55) / .13
            val dip = k.hump * exp(-(d * d))
            j *= 1 - .65 * dip
            val nose = when {
                k.hum > 0 && t < b0 -> 1 - smooth((t - (b0 - .03)) / .03)
                k.nasal > 0 && t > b1 - .03 -> smooth((t - (b1 - .03)) / .03)
                else -> 0.0
            }
            drift = drift * .997 + noise() * .0015
            var f0 = if (w < k.peakAt) k.start + (k.peak - k.start) * smooth(w / k.peakAt)
            else k.peak + (k.end - k.peak) * smooth((w - k.peakAt) / (1 - k.peakAt))
            f0 *= p * (1 + k.tail * smooth((w - .72) / .28)) * (1 - .07 * dip) * (1 + drift * k.rough + k.vib * sin(2 * PI * 5.5 * t + vph))
            ph += f0 / rate
            var src = 0.0
            for (h in 1..count) {
                if (h * f0 >= rate * .45) break
                src += sin(2 * PI * (h * ph + phases[h - 1])) * h.toDouble().pow(-k.tilt)
            }
            src += air.next(noise()) * k.breath * (.4 + .6 * j)
            val r1 = ((k.shut + (k.open - k.shut) * j) * (1 - nose) + 300.0 * nose) * k.mouth
            val r2 = (2850.0 + 250.0 * j) * k.mouth
            val r3 = (4700.0 + 150.0 * j) * k.mouth
            var y = f1.bandpass(r1, r1 / 150.0).next(src) +
                (f2.bandpass(r2, r2 / 260.0).next(src) * .4 + f3.bandpass(r3, r3 / 380.0).next(src) * .15) * (1 - .8 * nose)
            // the lips: muffled while shut, open with the jaw, closed and quiet for an "m" or "n"
            y = lips.lowpass((1000.0 + 8000.0 * j) * (1 - nose) + 500.0 * nose).next(y) * (1 - .7 * nose)
            val roller = if (t < k.trill) .4 + .6 * sin(PI * k.roll * t).let { it * it } else 1.0
            val level = if (v < 0 || v > 1) .55 else .4 + .6 * maxOf(j, .5 * nose)
            val purr = .5 + .5 * sin(2 * PI * 26.0 * t)
            val purring = if (k.purr > 0) 1 - k.purr * .3 * purr else 1.0
            val a = level * roller * purring * smooth(t / k.attack) * min(1.0, smooth((len - t) / (len * k.release)) * 1.0001) *
                (1 + k.rough * .05 * sin(2 * PI * 37.0 * t))
            if (k.purr > 0) y += rumble.next(noise()) * k.purr * .6 * purr
            out[i] = y * a
        }
        return out
    }

    /**
     * The nya's knobs, the tuner page's names: pitch points in Hz, times in seconds, the rest shares. The defaults are
     * the tuner's first option, Sweet; [NYA] is the one the app plays.
     */
    data class Nya(
        val pitch: Double = 1.0, val length: Double = .42,
        val start: Double = 560.0, val peak: Double = 720.0, val end: Double = 700.0, val peakAt: Double = .6, val tail: Double = 0.0,
        val open: Double = 1200.0, val shut: Double = 650.0, val mouth: Double = 1.0, val jawAt: Double = .4, val close: Double = .4,
        val trill: Double = 0.0, val roll: Double = 26.0, val hum: Double = 0.0, val nasal: Double = 0.0, val hump: Double = 0.0,
        val tilt: Double = 1.5, val breath: Double = .05, val rough: Double = .15, val vib: Double = .01, val purr: Double = 0.0,
        val attack: Double = .05, val release: Double = .3,
    )

    private fun smooth(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** A giggle: four quick little nyas, each a step lower, in her own voice. */
    private fun giggle(p: Double): DoubleArray {
        val out = DoubleArray(n(.5))
        for ((k, step) in GIGGLE_STEPS.withIndex()) {
            val h = nya(GIGGLE, p * GIGGLE.pitch * step)
            val at = n(k * GIGGLE_SPACING)
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

    /** One take of [sound] at the loudness ceiling, as the takeover lays her voice into its soundtrack. */
    fun loud(sound: PetSound, take: Int = 0): DoubleArray {
        val x = make(sound, take)
        val peak = x.maxOf { abs(it) }.coerceAtLeast(1e-9)
        val k = (CEILING / peak).coerceAtMost(4.0)
        return DoubleArray(x.size) { x[it] * k }
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

        /** Her nya, as kai tuned it on the tuner page (from Mrrnya: a rolled "mrr", a hum, then a nya that rises). */
        val NYA = Nya(
            pitch = 1.37, length = .37, start = 400.0, peak = 645.0, end = 795.0, peakAt = .41, tail = .13,
            open = 1260.0, shut = 710.0, mouth = 1.3, jawAt = .36, close = .88, trill = .13, roll = 38.5, hum = .14,
            tilt = 1.65, breath = .09, rough = .15, vib = .008, purr = .04, attack = .02, release = .31,
        )

        // Her other sounds are the same voice (kai: "apply the voice for the other sounds too, but not the exact same
        // meow"): the nya's throat, mouth and pitch, each with a gesture of its own.

        /** Shy, surprised, an oops, a sad face: a small, short mew, the mouth barely opening. */
        val MEW = NYA.copy(length = .22, trill = 0.0, hum = .05, start = 520.0, peak = 640.0, end = 660.0, peakAt = .5, tail = .05, open = 1080.0, jawAt = .4, close = .5, attack = .012, release = .35)

        /** Delighted: rolled all the way through as it opens, rising. */
        val TRILL = NYA.copy(length = .36, trill = .3, hum = 0.0, roll = 27.0, start = 420.0, peak = 560.0, end = 700.0, peakAt = .75, tail = .08, open = 1080.0, jawAt = .45, close = .3)

        /** A leap, or finding something: a quick rolled chirp up. */
        val MRRP = NYA.copy(length = .2, trill = .08, hum = 0.0, roll = 30.0, start = 480.0, peak = 700.0, end = 760.0, peakAt = .8, tail = .05, open = 1050.0, jawAt = .5, close = .2, attack = .01, release = .3)

        /** Catnip: her nya drawn out and dreamy, a swell in the middle, a little vibrato and purr. */
        val NYAA = NYA.copy(length = .9, hump = 1.0, vib = .03, start = 400.0, peak = 660.0, end = 520.0, peakAt = .3, tail = 0.0, jawAt = .25, close = .6, breath = .12, purr = .1)

        /** Cross: a closed-mouth "mm-hm" that barely opens, falling. */
        val HMPH = NYA.copy(length = .32, trill = 0.0, hum = .2, start = 520.0, peak = 560.0, end = 420.0, peakAt = .3, tail = 0.0, open = 860.0, shut = 680.0, jawAt = .4, close = .6, breath = .14)

        /** One syllable of the giggle; [GIGGLE_STEPS] are their pitches, [GIGGLE_SPACING] seconds apart. */
        val GIGGLE = NYA.copy(length = .1, trill = 0.0, hum = 0.0, start = 600.0, peak = 700.0, end = 660.0, peakAt = .4, tail = 0.0, open = 1150.0, jawAt = .35, close = .5, attack = .008, release = .4)
        val GIGGLE_STEPS = listOf(1.1, 1.04, .98, .94)
        const val GIGGLE_SPACING = .12

        /** The nya's length, seconds. */
        val NYA_LENGTH get() = NYA.length

        /** kai's tuning of the snap's pitch (1.1.29). */
        const val SNAP_PITCH = .96

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
