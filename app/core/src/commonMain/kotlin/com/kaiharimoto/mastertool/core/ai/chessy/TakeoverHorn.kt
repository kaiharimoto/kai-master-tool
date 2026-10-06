package com.kaiharimoto.mastertool.core.ai.chessy

/**
 * The takeover's alarm horn, as kai tuned it (2026-10-06, on the storyboard's "Tune the horn": "with this its perfect").
 * One pitch, like a semi truck's air horn: reeds of saws and a square pulse driven through a tanh, shaped by three
 * resonances and a low-pass, the pitch scooping up into the note and sagging at its end, a valve's flutter in the
 * level, air through it and a short dark echo. Phase 6 synthesizes it from these numbers (`TakeoverLayer`, `Synth`);
 * the storyboard's `SFX.horn` is the reference implementation. Never change one without kai: `TakeoverHornTest` pins them.
 */
object TakeoverHorn {
    const val VOLUME = .07f
    const val PITCH_HZ = 55f
    /** The pitch a blast starts at, as a share of [PITCH_HZ], rising to it in [SCOOP_S]. */
    const val SCOOP = .8f
    const val SCOOP_S = .055f
    /** The pitch a blast falls to at its end, as a share. */
    const val SAG = .88f
    const val BLAST_S = .75f
    const val GAP_S = .32f
    /** In the chaos, blast and gap both times this. */
    const val CHAOS = .82f
    const val ATTACK_S = .035f
    const val RELEASE_S = .25f
    /** The tanh's drive: `tanh(x · DRIVE) / tanh(DRIVE)`. */
    const val DRIVE = 4f
    /** The three saw reeds detuned at −0.6, +0.5 and +1 times this, in cents. */
    const val SPREAD_CENTS = 24f
    /** The square reed's level beside the saws' 1. */
    const val PULSE = .65f
    /** A sine an octave down: none. */
    const val SUB = 0f
    /** Band-passed noise at 1.6 kHz into the drive. */
    const val AIR = .18f
    const val BRIGHT_HZ = 8300f
    const val LOW_HZ = 160f
    const val LOW_DB = 15f
    const val MID_HZ = 1180f
    const val MID_DB = 1.5f
    const val HIGH_HZ = 2500f
    const val HIGH_DB = 7f
    const val FLUTTER_HZ = 23f
    const val FLUTTER_DEPTH = .14f
    /** The echo's send: a 0.9 s decaying noise impulse, low-passed at 2.2 kHz. */
    const val ECHO = .46f

    /** When each blast starts, from the alarm's start to the snap: steady, then quicker in the chaos. */
    fun blasts(from: Float = 2f, chaosAt: Float = 6f, until: Float = 8.8f): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var t = from
        while (t < until) {
            val k = if (t < chaosAt) 1f else CHAOS
            val blast = BLAST_S * k
            out += t to minOf(blast, until + .1f - t)
            t += maxOf(.1f, blast + GAP_S * k)
        }
        return out
    }
}
