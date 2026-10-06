package com.kaiharimoto.mastertool.core.audio

import com.kaiharimoto.mastertool.core.ai.chessy.TakeoverHorn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Sound made in code (kai, 2026-10: "synthesized in code", no audio files shipped): the takeover's sounds as the
 * storyboard's Web Audio graph made them, rendered offline into a buffer of samples. Oscillators with their edges
 * smoothed (polyBLEP), the RBJ biquads Web Audio's filters are, a wave shaper, envelopes that ramp and decay as
 * Web Audio's do, and a small reverb for the horn's echo. Pure, deterministic for a seed, tested on length and level.
 */
class Synth(val rate: Int = 32000, seed: Int = 7) {
    private val rng = Random(seed)
    private val dt = 1.0 / rate

    fun samples(seconds: Double) = (seconds * rate).roundToInt().coerceAtLeast(1)

    // ---- building blocks ---------------------------------------------------------------------------------------------

    /** A running oscillator: [next] gives a sample at [freq] Hz. */
    class Osc(private val type: Wave, private val rate: Int, phase: Double = 0.0) {
        private var p = phase
        fun next(freq: Double): Double {
            val inc = freq / rate
            val v = when (type) {
                Wave.SINE -> sin(2 * PI * p)
                Wave.TRIANGLE -> 1 - 4 * abs(p - .5)
                Wave.SAW -> (2 * p - 1) - blep(p, inc)
                Wave.SQUARE -> (if (p < .5) 1.0 else -1.0) + blep(p, inc) - blep((p + .5) % 1.0, inc)
            }
            p += inc
            if (p >= 1) p -= 1
            return v
        }

        private fun blep(t: Double, dt: Double): Double = when {
            dt <= 0 -> 0.0
            t < dt -> { val x = t / dt; x + x - x * x - 1 }
            t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
            else -> 0.0
        }
    }

    enum class Wave { SINE, TRIANGLE, SAW, SQUARE }

    /** A biquad as Web Audio's: lowpass, highpass, bandpass (constant peak) and peaking, from the RBJ cookbook. */
    class Biquad(private val rate: Int) {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun lowpass(f: Double, q: Double = .7071) = set(f, q) { w, al -> val c = cos(w); doubleArrayOf((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al) }
        fun highpass(f: Double, q: Double = .7071) = set(f, q) { w, al -> val c = cos(w); doubleArrayOf((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al) }
        fun bandpass(f: Double, q: Double) = set(f, q) { w, al -> val c = cos(w); doubleArrayOf(al, 0.0, -al, 1 + al, -2 * c, 1 - al) }
        fun peaking(f: Double, q: Double, db: Double) = set(f, q) { w, al ->
            val a = 10.0.pow(db / 40); val c = cos(w)
            doubleArrayOf(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }

        private inline fun set(f: Double, q: Double, k: (Double, Double) -> DoubleArray): Biquad {
            val w = 2 * PI * f.coerceIn(10.0, rate * .49) / rate
            val al = sin(w) / (2 * q)
            val c = k(w, al)
            b0 = c[0] / c[3]; b1 = c[1] / c[3]; b2 = c[2] / c[3]; a1 = c[4] / c[3]; a2 = c[5] / c[3]
            return this
        }

        fun next(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** Web Audio's envelope: a linear ramp to [peak] in [attack], then an exponential fall to 0.0001 over [decay]. */
    fun env(t: Double, attack: Double, peak: Double, decay: Double): Double {
        if (t < 0) return 0.0
        if (t < attack) return peak * t / attack
        val u = (t - attack) / decay
        return if (u >= 1) 0.0 else peak * exp(ln(.0001 / peak) * u)
    }

    /** An exponential glide from [a] to [b] over [len] seconds, held at [b] after. */
    private fun glide(t: Double, a: Double, b: Double, len: Double) = if (t >= len) b else a * (b / a).pow(t / len)

    private fun noise() = rng.nextDouble() * 2 - 1

    // ---- the takeover's sounds -----------------------------------------------------------------------------------------

    /** kai's air horn ([TakeoverHorn]), a blast of [len] seconds, before its echo; the echo's send comes back in [echo]. */
    fun horn(len: Double, echo: DoubleArray? = null): DoubleArray {
        val h = TakeoverHorn
        val f = h.PITCH_HZ.toDouble()
        val out = DoubleArray(samples(len + h.RELEASE_S + .02))
        val reeds = listOf(
            Triple(Osc(Wave.SAW, rate, rng.nextDouble()), -h.SPREAD_CENTS * .6, 1.0),
            Triple(Osc(Wave.SAW, rate, rng.nextDouble()), h.SPREAD_CENTS * .5, 1.0),
            Triple(Osc(Wave.SAW, rate, rng.nextDouble()), h.SPREAD_CENTS * 1.0, 1.0),
            Triple(Osc(Wave.SQUARE, rate, rng.nextDouble()), 0.0, h.PULSE.toDouble()),
        )
        val airBand = Biquad(rate).bandpass(1600.0, .7)
        val chain = listOf(
            Biquad(rate).peaking(h.LOW_HZ.toDouble(), 1.3, h.LOW_DB.toDouble()),
            Biquad(rate).peaking(h.MID_HZ.toDouble(), 1.6, h.MID_DB.toDouble()),
            Biquad(rate).peaking(h.HIGH_HZ.toDouble(), 2.0, h.HIGH_DB.toDouble()),
            Biquad(rate).lowpass(h.BRIGHT_HZ.toDouble()),
            Biquad(rate).highpass(50.0),
        )
        val drive = h.DRIVE.toDouble()
        val norm = tanh(drive)
        val vol = h.VOLUME.toDouble()
        val scoopS = h.SCOOP_S.toDouble()
        val sagFrom = maxOf(scoopS, len - .1)
        for (i in out.indices) {
            val t = i * dt
            val pitch = when {
                t < scoopS -> glide(t, f * h.SCOOP, f, scoopS)
                t < sagFrom -> f
                else -> glide(t - sagFrom, f, f * h.SAG, (len + .06) - sagFrom)
            }
            var x = 0.0
            for ((osc, cents, level) in reeds) x += level * osc.next(pitch * 2.0.pow(cents / 1200))
            x += h.AIR * airBand.next(noise())
            var y = tanh(x.coerceIn(-1.0, 1.0) * drive) / norm
            for (b in chain) y = b.next(y)
            y *= 1 + h.FLUTTER_DEPTH * sin(2 * PI * h.FLUTTER_HZ * t)
            val a = h.ATTACK_S.toDouble()
            val level = when {
                t < a -> vol * t / a
                t < maxOf(a, len - .05) -> vol
                else -> vol * exp(ln(.0005 / vol) * ((t - maxOf(a, len - .05)) / (h.RELEASE_S + .05)).coerceAtMost(1.0))
            }
            out[i] = y * level
            if (echo != null && i < echo.size) echo[i] += out[i] * h.ECHO
        }
        return out
    }

    /** Static: band-passed noise somewhere between 1.8 and 4.2 kHz. */
    fun static(len: Double = .25): DoubleArray {
        val bp = Biquad(rate).bandpass(1800.0 + rng.nextDouble() * 2400, .8)
        return DoubleArray(samples(len + .06)) { i -> bp.next(noise()) * env(i * dt, .01, .35, len) }
    }

    /** A tick: high-passed noise, a click. */
    fun tick(): DoubleArray {
        val hp = Biquad(rate).highpass(3000.0)
        return DoubleArray(samples(.05)) { i -> hp.next(noise()) * env(i * dt, .002, .5, .03) }
    }

    /** A data chirp: a square jumping a fifth up or down after 25 ms. */
    fun chirp(): DoubleArray {
        val o = Osc(Wave.SQUARE, rate)
        val f0 = 1200.0 + rng.nextDouble() * 1800
        val f1 = f0 * (if (rng.nextBoolean()) 1.5 else .75)
        return DoubleArray(samples(.08)) { i -> val t = i * dt; o.next(if (t < .025) f0 else f1) * env(t, .002, .05, .05) }
    }

    /** Distorted buzzing: a low saw and square crushed to a few levels, stuttering, its band sweeping up. */
    fun buzz(len: Double = .26, base: Double = 0.0, gain: Double = .3): DoubleArray {
        val f = if (base > 0) base else 55 + rng.nextDouble() * 70
        val to = f * (if (rng.nextBoolean()) 1.7 else .55)
        val saw = Osc(Wave.SAW, rate)
        val sq = Osc(Wave.SQUARE, rate)
        val gate = Osc(Wave.SQUARE, rate)
        val gateHz = 16 + rng.nextDouble() * 26
        val bpFrom = 300 + rng.nextDouble() * 500
        val bpTo = 1400 + rng.nextDouble() * 1400
        val bp = Biquad(rate)
        return DoubleArray(samples(len + .05)) { i ->
            val t = i * dt
            val u = (t / len).coerceAtMost(1.0)
            if (i % 32 == 0) bp.bandpass(bpFrom * (bpTo / bpFrom).pow(u), 1.1)
            val x = saw.next(f + (to - f) * u) + sq.next(f * 2.02)
            val crushed = (tanh(x.coerceIn(-1.0, 1.0) * 9) * 3).roundToInt() / 3.0
            bp.next(crushed) * (.5 + .5 * gate.next(gateHz)) * env(t, .004, gain, len)
        }
    }

    /** Her crush: two buzzes, deep and low, and static. */
    fun crush(): DoubleArray = mix(buzz(.7, 42.0, .45), buzz(.5, 90.0, .25), static(.4))

    /** The power going down: a saw falling from 420 Hz to 30 through a low-pass. */
    fun powerdown(): DoubleArray {
        val o = Osc(Wave.SAW, rate)
        val lp = Biquad(rate).lowpass(1400.0)
        return DoubleArray(samples(.8)) { i -> val t = i * dt; lp.next(o.next(glide(t, 420.0, 30.0, .7))) * env(t, .005, .25, .7) }
    }

    /** Her chime as she lands: three bells rising, with a little vibrato. */
    fun nya(): DoubleArray {
        val out = DoubleArray(samples(1.2))
        for ((freq, at) in listOf(1318.5 to 0.0, 1760.0 to .09, 2637.0 to .18)) {
            val o = Osc(Wave.SINE, rate)
            val start = (at * rate).roundToInt()
            for (i in start until out.size) {
                val t = (i - start) * dt
                out[i] += o.next(freq + 6 * sin(2 * PI * 6 * t)) * env(t, .005, .16, .9)
            }
        }
        return out
    }

    /** A warning window popping up: a bright square "bip" dropping a fourth, crushed, with a click of static. */
    fun popup(click: Boolean = true): DoubleArray {
        val o = Osc(Wave.SQUARE, rate)
        val f0 = 880.0 * (if (rng.nextBoolean()) 1.0 else 1.12)
        val tick = tick().also { if (!click) it.fill(0.0) }
        return DoubleArray(samples(.16)) { i ->
            val t = i * dt
            val x = o.next(if (t < .05) f0 else f0 * .75)
            (x * 3).roundToInt() / 3.0 * env(t, .002, .09, .12) + (if (i < tick.size) tick[i] * .4 else 0.0)
        }
    }

    /** "Huh?": a triangle rising like a question. */
    fun huh(): DoubleArray {
        val o = Osc(Wave.TRIANGLE, rate)
        return DoubleArray(samples(.3)) { i -> val t = i * dt; o.next(glide(t, 700.0, 1500.0, .18)) * env(t, .01, .14, .25) }
    }

    /** A key typed in her box. */
    fun key(): DoubleArray {
        val o = Osc(Wave.TRIANGLE, rate)
        val f = 1900 + rng.nextDouble() * 700
        return DoubleArray(samples(.05)) { i -> o.next(f) * env(i * dt, .001, .05, .03) }
    }

    /** Ai's restore: one quiet clean tick. */
    fun restore(): DoubleArray {
        val o = Osc(Wave.SINE, rate)
        return DoubleArray(samples(.16)) { i -> o.next(1046.5) * env(i * dt, .003, .07, .12) }
    }

    /** The power coming back: a rising sine and a major chord. */
    fun powerup(): DoubleArray {
        val out = DoubleArray(samples(1.95))
        val rise = Osc(Wave.SINE, rate)
        for (i in 0 until samples(.6)) { val t = i * dt; out[i] += rise.next(glide(t, 180.0, 660.0, .5)) * env(t, .02, .18, .5) }
        for ((k, freq) in listOf(523.25, 659.25, 783.99).withIndex()) {
            val o = Osc(Wave.TRIANGLE, rate)
            val start = ((.45 + k * .06) * rate).roundToInt()
            for (i in start until out.size) { val t = (i - start) * dt; out[i] += o.next(freq) * env(t, .02, .09, 1.2) }
        }
        return out
    }

    /** Sounds laid over one another from their start. */
    fun mix(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    /**
     * The horn's echo: a small reverb of four combs and two all-passes (a short dark room), low-passed at 2.2 kHz,
     * run over a whole send bus.
     */
    fun room(send: DoubleArray): DoubleArray {
        val combs = listOf(.0297, .0371, .0411, .0437).map { s -> DoubleArray((s * rate).roundToInt()) }
        val pos = IntArray(combs.size)
        val feedback = .78
        val aps = listOf(.005, .0017).map { s -> DoubleArray((s * rate).roundToInt()) }
        val apPos = IntArray(aps.size)
        val lp = Biquad(rate).lowpass(2200.0)
        val out = DoubleArray(send.size)
        for (i in send.indices) {
            var y = 0.0
            for ((k, buf) in combs.withIndex()) {
                val o = buf[pos[k]]
                buf[pos[k]] = send[i] + o * feedback
                pos[k] = (pos[k] + 1) % buf.size
                y += o
            }
            y /= combs.size
            for ((k, buf) in aps.withIndex()) {
                val o = buf[apPos[k]]
                val v = y + o * .5
                buf[apPos[k]] = v
                apPos[k] = (apPos[k] + 1) % buf.size
                y = o - v * .5
            }
            out[i] = lp.next(y)
        }
        return out
    }

    companion object {
        /** Samples as 16-bit, clipped. */
        fun pcm16(x: DoubleArray, gain: Double = 1.0): ShortArray =
            ShortArray(x.size) { i -> (x[i] * gain * 32767).roundToInt().coerceIn(-32768, 32767).toShort() }

        fun peak(x: DoubleArray): Double = x.maxOfOrNull { abs(it) } ?: 0.0

        fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
            var s = 0.0
            for (i in from until to) s += x[i] * x[i]
            return sqrt(s / maxOf(1, to - from))
        }
    }
}
