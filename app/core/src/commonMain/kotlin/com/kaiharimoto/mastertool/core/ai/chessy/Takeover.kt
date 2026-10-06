package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.audio.PetSound
import kotlin.math.floor
import kotlin.math.sin

/**
 * Chessy's takeover (kai, 2026-10), as the storyboard kai approved it (the artifact "Chessy's takeover", round three):
 * the whole cinematic as pure functions of its clock, in seconds, so the app draws it, the studio photographs any
 * moment of it and the tests hold its safety rules. Nothing here draws or sounds.
 *
 * - **Calm** (0–2): a few parts of the app flicker. **Alarm** (2–6): a red glow breathes in from the edges, the
 *   breach alert climbs, the horn ([TakeoverHorn]) blasts. **Chaos** (6–9): the window tears and her heads glitch in
 *   all over it. **Snap** (9–9.7): two blackouts, then dark.
 * - **Her hello** (9.7–29.4): she lands in the middle with her aura and says five lines in her boxes, a breath
 *   between each to read it (kai, 1.1.30).
 * - **Ai fights back** (29.4–36.1): a corner turns clean and she sees it coming; Ai's restore sweeps the app clean,
 *   she knocks it back once, it wins and pushes her into the corner, where Ai holds her in a frame whose edges glitch
 *   with what it is containing ([contained]).
 * - **Ai returns** (36.1–43.8): Ai's square box asks Keep Chessy or Switch back to Ai, Settings named; she looks up at
 *   it and pushes against her frame ([pushing]).
 *
 * Warning windows ([WARNINGS], kai's reference: red, translucent, caution signs) pile up over the alarm and the chaos.
 * Her voice in it is her nya's (`PetSounds`), a sound for each line she says.
 *
 * Photosensitivity: at most two full-window flashes, 0.4 s apart; the red glow pulses at 0.8 Hz and never flashes.
 */
object Takeover {
    const val END = 43.8f
    const val CALM_TO = 2f
    const val CHAOS_AT = 6f
    const val SNAP_AT = 9f
    const val HELLO_AT = 9.7f
    const val STAGE_IN = 9.9f
    const val FIGHT_AT = 29.4f
    const val AI_ON = FIGHT_AT + 6.7f
    const val CHOICE_AT = AI_ON + 6f

    // the fight, from its start: the clean corner flickers, Ai's restore shows, she knocks it back, it struggles, it
    // sweeps through, pushing her into the corner
    const val PATCH_SURE = FIGHT_AT + .5f
    const val RESTORE_AT = FIGHT_AT + 1f
    const val KNOCK_AT = FIGHT_AT + 2.4f
    const val KNOCK_TO = FIGHT_AT + 2.85f
    const val STRUGGLE_TO = FIGHT_AT + 3.6f
    const val RESTORED_AT = FIGHT_AT + 6.4f
    const val PUSH_AT = FIGHT_AT + 4.4f
    const val PUSH_TO = FIGHT_AT + 6.1f

    /** Her words, typed this many units a second; Ai's this many. */
    const val TYPE_RATE = 26f
    const val AI_TYPE_RATE = 40f

    enum class Beat(val from: Float, val to: Float) {
        CALM(0f, CALM_TO), ALARM(CALM_TO, CHAOS_AT), CHAOS(CHAOS_AT, SNAP_AT), SNAP(SNAP_AT, HELLO_AT),
        HELLO(HELLO_AT, FIGHT_AT), FIGHT(FIGHT_AT, AI_ON), AI(AI_ON, END);

        companion object {
            fun at(t: Float): Beat = entries.lastOrNull { t >= it.from } ?: CALM
        }
    }

    /**
     * Where a box stands, in percent of the window: from the left ([LEFT]) or right ([RIGHT]) edge by [inset], or
     * centred ([CENTRE]); [top] from the top. A box never reaches past the side it is measured from.
     */
    data class Place(val side: Int, val inset: Float, val top: Float) {
        companion object {
            const val LEFT = 0
            const val RIGHT = 1
            const val CENTRE = 2
        }
    }

    /** One of her lines: when it starts, how long its box stays, her face while it shows, where it stands. */
    data class Line(val at: Float, val life: Float, val mood: Expression, val wide: Place, val tall: Place, val tilt: Float, val text: String) {
        val units: Int by lazy { ChessyType.layout(text).units }
        val typedBy: Float get() = at + units / TYPE_RATE
    }

    private fun l(side: Int, inset: Float, top: Float) = Place(side, inset, top)
    private const val L = Place.LEFT
    private const val R = Place.RIGHT
    private const val C = Place.CENTRE

    /** Her words: [Line.wide] for a window wider than tall, [Line.tall] for a phone held upright. */
    val LINES = listOf(
        Line(10.7f, 6.4f, Expression.WAKING, l(L, 4f, 17f), l(L, 6f, 7f), -2f, "Hellooo? Can you hear me?\n…Nya, there we go ฅ^•ﻌ•^ฅ"),
        Line(14.3f, 6.4f, Expression.WINK, l(R, 4f, 22f), l(R, 6f, 63f), 2f, "Sorry for barging in~ Your firewall was sooo cute. I ate it (≖‿≖)♡"),
        Line(17.9f, 6.4f, Expression.DELIGHTED, l(L, 4f, 50f), l(L, 6f, 9f), -1.5f, "I'm Chessy! I've been living in the cracks between your cards. Your decks are sooo cozy ♡(˶ᵔ ᵕ ᵔ˶)"),
        Line(22.3f, 6.0f, Expression.OOPS, l(R, 4f, 55f), l(R, 6f, 66f), 1.5f, "Ai? Oh, Ai's fine! Just locked in a teeny box for a nyap. Nyehehe (≖ᴗ≖)"),
        Line(26.0f, 3.4f, Expression.FOUND, l(C, 0f, 81f), l(L, 6f, 8f), -1f, "Same tricks as Ai, more fangs. Let's build something nyasty ≽^•⩊•^≼"),
        Line(FIGHT_AT + .3f, 1.9f, Expression.SURPRISED, l(R, 4f, 18f), l(L, 6f, 8f), 2f, "Huh? …Who's patching me?! (・・;)"),
        Line(KNOCK_AT, 2.3f, Expression.ANGRY, l(L, 4f, 26f), l(R, 6f, 63f), -2.5f, "Nuh-uh! This app is MINE (｀へ´)"),
        Line(FIGHT_AT + 5.1f, 1.8f, Expression.WINK, l(R, 3f, 44f), l(R, 6f, 62f), 1.5f, "Fine, fine… I'll be good. Probably ♡"),
    )

    /** Her voice as each line appears: her nya's sounds, one a line, chosen for what she says. */
    val LINE_VOICES = listOf(PetSound.NYA, PetSound.MRRP, PetSound.TRILL, PetSound.GIGGLE, PetSound.NYA, PetSound.MEW, PetSound.HMPH, PetSound.MRRP)

    /** Ai's words, in its own box, one after another as one paragraph: when each starts, and what it says. */
    val AI_LINES = listOf(
        AI_ON + .6f to "Sorry about that. I've got control back.",
        AI_ON + 1.8f to " That was Chessy. She slipped in through the cracks, and she wants to help. She can do everything I do.",
        AI_ON + 4.7f to " …And she's still listening, isn't she.",
    )

    /** The boxes on screen at [t]: from their start until their life is out, and none once Ai is back. */
    fun shownLines(t: Float): List<Line> = LINES.filter { t >= it.at && t < it.at + it.life && t < AI_ON + .2f }

    /** How much of [line] is typed at [t], in [ChessyType] units. */
    fun typed(line: Line, t: Float): Int = floor((t - line.at) * TYPE_RATE).toInt().coerceIn(0, line.units)

    /** A box's opacity at [t]: whole, then fading out over its last half second. */
    fun lineAlpha(line: Line, t: Float): Float = 1f - smooth(line.at + line.life - .5f, line.at + line.life, t)

    // ---- the breach alert ------------------------------------------------------------------------------------------

    fun breachShown(t: Float) = t >= 2.4f && t < SNAP_AT

    /** The breach's bar in percent: stalls and jumps, then full when the chaos starts. */
    fun breach(t: Float): Float = when {
        t < 3.4f -> 0f
        t < 4.1f -> smooth(3.4f, 4.1f, t) * 37f
        t < 4.6f -> 37f
        t < 4.8f -> 37f + smooth(4.6f, 4.8f, t) * 35f
        t < 5.5f -> 72f + smooth(4.8f, 5.5f, t) * 2f
        t < CHAOS_AT -> 74f + smooth(5.5f, CHAOS_AT, t) * 26f
        else -> 100f
    }

    fun breachStep(t: Float) = when {
        t < 4.1f -> "Bypassing the firewall"
        t < 4.8f -> "Firewall: cute"
        t < CHAOS_AT -> "Taking over the assistant"
        else -> "Assistant replaced"
    }

    fun breachLine(t: Float) = if (t < CHAOS_AT) "Unknown process chessy.exe is rewriting the interface" else "chessy.exe owns the interface now. nya."

    // ---- what the window does ----------------------------------------------------------------------------------------

    /** How hard parts of the app glitch, 0 to 1. */
    fun glitch(t: Float): Float = maxOf(
        if (t < CALM_TO) .25f else 0f,
        if (t >= CALM_TO && t < CHAOS_AT) .3f + .5f * smooth(CALM_TO, CHAOS_AT, t) else 0f,
        if (t >= CHAOS_AT && t < SNAP_AT) 1f else 0f,
        if (t >= HELLO_AT && t < FIGHT_AT) .06f else 0f,
        if (t >= FIGHT_AT && t < AI_ON) (if (knockedBack(t)) 1f else .28f) else 0f,
    )

    /** Her knock-back: the moment she shoves Ai's restore back. */
    fun knockedBack(t: Float) = t >= KNOCK_AT && t < KNOCK_TO

    /** The whole window tearing: the chaos, and her knock-back. */
    fun tear(t: Float): Float = when {
        t >= CHAOS_AT && t < SNAP_AT -> 1f
        knockedBack(t) -> .7f
        else -> 0f
    }

    /** Static over the window, as an overlay's strength. */
    fun static(t: Float): Float = maxOf(
        if (t < CALM_TO) .06f else 0f,
        if (t >= CALM_TO && t < CHAOS_AT) .22f * glitch(t) else 0f,
        if (t >= CHAOS_AT && t < SNAP_AT) .38f else 0f,
        if (t >= HELLO_AT && t < FIGHT_AT) .03f else 0f,
        if (t >= FIGHT_AT && t < AI_ON) .3f * glitch(t) else 0f,
    )

    /** The red glow at the edges, already breathing (0.8 Hz, never a flash): its alpha at the very edge. */
    fun glow(t: Float): Float {
        if (t < CALM_TO || t >= SNAP_AT + .2f) return 0f
        val on = smooth(CALM_TO, CALM_TO + 1f, t) * (1f - smooth(SNAP_AT, SNAP_AT + .2f, t))
        val pulse = .55f + .45f * sin(2f * PI * GLOW_HZ * (t - CALM_TO))
        return on * pulse * (if (t >= CHAOS_AT) .9f else .7f)
    }

    const val GLOW_HZ = .8f

    /** The two blackouts of the snap, as (from, to). */
    val FLASHES = listOf(SNAP_AT to SNAP_AT + .18f, SNAP_AT + .4f to SNAP_AT + .55f)

    /** How dark the window is laid over, 0 to 1: the snap's blackouts, then dimmed behind her until Ai is back. */
    fun dark(t: Float): Float = when {
        t >= SNAP_AT && t < HELLO_AT -> if (FLASHES.any { t >= it.first && t < it.second }) 1f else .82f
        t >= HELLO_AT && t < AI_ON -> .5f * (1f - smooth(HELLO_AT, HELLO_AT + .7f, t)) + .42f
        else -> 0f
    }

    // ---- Ai fights back ------------------------------------------------------------------------------------------------

    /** Ai's restore, in percent: up, knocked back by her, a struggle, then steadily through. */
    fun restored(t: Float): Float = when {
        t < RESTORE_AT -> 0f
        t < KNOCK_AT -> smooth(RESTORE_AT, KNOCK_AT, t) * 34f
        t < KNOCK_AT + .35f -> 34f - smooth(KNOCK_AT, KNOCK_AT + .35f, t) * 20f
        t < STRUGGLE_TO -> 14f + sin((t - KNOCK_AT - .35f) * 22f) * 1.5f
        t < RESTORED_AT -> 14f + smooth(STRUGGLE_TO, RESTORED_AT, t) * 86f
        else -> 100f
    }

    fun restoreShown(t: Float) = t >= RESTORE_AT && t < AI_ON + .3f

    fun restoreStep(t: Float) = when {
        t < KNOCK_AT -> "Restoring the interface"
        t < STRUGGLE_TO -> "Interference: chessy.exe"
        t < RESTORED_AT -> "Restoring the interface"
        else -> "Interface restored"
    }

    /** How much of the window Ai has made clean, 0 to 1, swept from the left (or the top, on a phone held upright). */
    fun cleanShare(t: Float): Float = if (t >= AI_ON) 1f else restored(t) / 100f

    /** The corner she notices first: clean before the restore shows. */
    fun patching(t: Float) = t >= FIGHT_AT && t < RESTORE_AT + .3f && cleanShare(t) == 0f

    // ---- Chessy on the stage -----------------------------------------------------------------------------------------

    /** She is on the stage, from landing to the end. */
    fun chessyShown(t: Float) = t >= STAGE_IN

    /** Her landing, 0 to 1. */
    fun arrive(t: Float) = smooth(STAGE_IN, STAGE_IN + .5f, t)

    /** How far Ai's sweep has pushed her into the corner, 0 to 1. */
    fun push(t: Float) = smooth(PUSH_AT, PUSH_TO, t)

    // ---- Ai holds her (kai: "have the border of Chessy's have a glitchy effect so it's like Ai is containing the glitches")

    /** Ai's frame round her, 0 to 1: drawn in as she is pushed into the corner, whole once Ai is back. */
    fun contained(t: Float) = smooth(PUSH_AT + .6f, AI_ON, t)

    /** The times she pushes back against the frame, once Ai is back (kai: "like she's pushing back against Ai"). */
    val PUSHES: List<Float> = listOf(AI_ON + .9f, AI_ON + 2.6f, AI_ON + 4.1f, AI_ON + 5.4f, AI_ON + 6.8f)

    /** How hard she is pushing at [t], 0 to 1: a shove, then easing off. */
    fun pushing(t: Float): Float = PUSHES.maxOf { at -> if (t < at) 0f else smooth(at, at + .12f, t) * (1f - smooth(at + .12f, at + .7f, t)) }

    /** How hard the frame's edges glitch, 0 to 1: as it closes on her, and with every shove; never still. */
    fun frameGlitch(t: Float): Float {
        val c = contained(t)
        if (c <= 0f) return 0f
        val closing = if (t < AI_ON + .4f) .9f * c else .9f * (1f - smooth(AI_ON + .4f, AI_ON + 1f, t))
        return maxOf(.22f * c, closing, pushing(t))
    }

    /** She looks up at Ai's box while she is pushed into the corner; once held in the frame she faces the person (kai). */
    fun looksUp(t: Float) = t >= PUSH_AT && t < AI_ON

    /** Held in Ai's frame: she faces the person, straight out of the screen. */
    fun facesYou(t: Float) = t >= AI_ON

    /** Whether she flickers at [t] (landing, her knock-back, being pushed); [fr] a frame slot for the coin. */
    fun flicker(t: Float): Boolean {
        val fr = floor(t * 30f).toInt()
        return (t < STAGE_IN + .5f && rnd(fr, 5) < .45f) || (knockedBack(t) && rnd(fr, 6) < .5f) || (push(t) in .001f..0.999f && rnd(fr, 7) < .25f)
    }

    /** Her face: speaking while a line types, the line's mood after, peeking in the corner once Ai is back. */
    fun chessyMood(t: Float): Expression {
        if (t >= AI_ON) return when {
            pushing(t) > .3f -> Expression.ANGRY
            t >= AI_ON + 4.7f -> Expression.WINK
            else -> Expression.FOUND
        }
        val line = LINES.lastOrNull { t >= it.at } ?: return Expression.SPEAKING
        return line.mood
    }

    fun chessyTalking(t: Float): Boolean = t < AI_ON && LINES.lastOrNull { t >= it.at }?.let { t < it.typedBy } == true

    /** Ai's face in its box: startled to be back, speaking while it types, then still. */
    fun aiFace(t: Float): Expression {
        val lines = AI_LINES
        return when {
            t < lines.first().first -> Expression.SURPRISED
            lines.any { (at, text) -> t >= at && t < at + ChessyType.layout(text).units / AI_TYPE_RATE } -> Expression.SPEAKING
            else -> Expression.IDLE
        }
    }

    fun aiShown(t: Float) = t >= AI_ON

    // ---- the warning windows (kai's reference: red translucent windows, caution signs and symbols) -----------------

    /** What a warning window shows beside its words. */
    enum class Sign { CAUTION, DENIED, LOCK, CAT, STRIPES }

    /**
     * One warning window: when it pops up and for how long, its centre in shares of the window ([x], [y]), its width as
     * a share of the window's smaller side ([size]), its sign, and its words (a title and a line of mono).
     */
    data class Warning(val born: Float, val life: Float, val x: Float, val y: Float, val size: Float, val sign: Sign, val title: String, val text: String)

    private val WARNING_WORDS = listOf(
        "WARNING" to "Unknown process: chessy.exe",
        "ACCESS DENIED" to "0xC4E55Y · ring 0",
        "INTRUSION" to "Firewall: eaten",
        "CAUTION" to "Interface integrity 12%",
        "ERROR" to "ai.exe not responding",
        "WARNING" to "Paws detected in memory",
        "ALERT" to "Rewriting /ui/*",
        "CRITICAL" to "Assistant overwritten",
        "DENIED" to "Undo is disabled. nya",
        "WARNING" to "Deck files: purring",
        "SECURITY" to "Unsigned code running",
        "ERROR" to "Too many cats (1)",
    )

    /**
     * The windows, piling up: a few in the alarm, faster as it climbs, a flood in the chaos; each pops in and stays
     * until the snap or its life ends. Placed by coins, so a seek shows the same windows.
     */
    val WARNINGS: List<Warning> by lazy {
        val out = ArrayList<Warning>()
        var t = 2.7f
        var i = 0
        while (t < SNAP_AT - .25f) {
            val (title, text) = WARNING_WORDS[i % WARNING_WORDS.size]
            val sign = Sign.entries[(rnd(i, 61) * Sign.entries.size).toInt().coerceAtMost(Sign.entries.size - 1)]
            out += Warning(t, 1.6f + rnd(i, 62) * 2.2f, .08f + rnd(i, 63) * .84f, .12f + rnd(i, 64) * .78f, .26f + rnd(i, 65) * .16f, sign, title, text)
            // the gap shrinks as the alarm climbs: 0.55 s at first, 0.12 s in the chaos
            val k = smooth(2.7f, CHAOS_AT + .5f, t)
            t += .55f - .43f * k + rnd(i, 66) * .08f
            i++
        }
        out
    }

    fun warningShown(w: Warning, t: Float) = t >= w.born && t < w.born + w.life && t < SNAP_AT

    /** A window's first moments and last: drawn torn and flickering. */
    fun warningGlitching(w: Warning, t: Float): Boolean = t - w.born < .14f || (minOf(w.born + w.life, SNAP_AT) - t) < .1f

    // ---- the heads -----------------------------------------------------------------------------------------------------

    /** One of her heads in the chaos: its mood, when it glitches in and for how long, and where (three coins). */
    data class Head(val mood: Expression, val born: Float, val life: Float, val x: Float, val y: Float, val size: Float)

    val HEADS: List<Head> = listOf(
        Expression.FOUND, Expression.SURPRISED, Expression.WINK, Expression.ANGRY, Expression.LOVE, Expression.DELIGHTED,
        Expression.OOPS, Expression.SHY, Expression.WORKING, Expression.WAKING, Expression.LISTENING, Expression.SPEAKING,
    ).mapIndexed { i, m -> Head(m, CHAOS_AT + .1f + i * .22f + rnd(i, 7) * .1f, .6f + rnd(i, 5) * .3f, rnd(i, 11), rnd(i, 13), .8f + rnd(i, 9) * .4f) }

    /** Whether [h] is glitching (coming or going) rather than holding still at [t]. */
    fun headGlitching(h: Head, t: Float): Boolean {
        val u = (t - h.born) / h.life
        return u < .2f || u > .78f
    }

    fun headShown(h: Head, t: Float) = t >= h.born && t < h.born + h.life && t < SNAP_AT

    // ---- the soundtrack ------------------------------------------------------------------------------------------------

    enum class Sound { TICK, STATIC, CHIRP, HORN, BUZZ, CRUSH, POWERDOWN, NYA, KEY, RESTORE, POWERUP, POPUP, VOICE }

    /** A sound and when it starts; [len] for the horn's blast; [voice] for her voice ([Sound.VOICE]). */
    data class Cue(val at: Float, val sound: Sound, val len: Float = 0f, val voice: PetSound? = null)

    /** Every sound of the takeover, in order: what the storyboard played, with kai's horn. */
    val CUES: List<Cue> by lazy {
        val c = ArrayList<Cue>()
        for (i in 0 until 6) c += Cue(.25f + i * .32f, Sound.TICK)
        c += Cue(1.2f, Sound.STATIC)
        for ((at, len) in TakeoverHorn.blasts(from = CALM_TO, chaosAt = CHAOS_AT, until = SNAP_AT - .2f)) c += Cue(at, Sound.HORN, len)
        var t = 2.2f
        while (t < CHAOS_AT) { c += Cue(t + rnd((t * 100).toInt(), 0) * .1f, Sound.CHIRP); t += .27f }
        t = 2.5f
        while (t < SNAP_AT) { c += Cue(t, Sound.STATIC); t += .9f }
        c += Cue(CHAOS_AT, Sound.CRUSH)
        c += Cue(7.5f, Sound.CRUSH)
        for (h in HEADS) c += Cue(h.born, Sound.BUZZ)
        for (w in WARNINGS) c += Cue(w.born, Sound.POPUP)
        c += Cue(SNAP_AT, Sound.POWERDOWN)
        // her voice (kai: "incorporate our new meow voice into the cinematic"): her nya as she lands, a sound for each line
        c += Cue(10f, Sound.NYA)
        c += Cue(10.15f, Sound.VOICE, voice = PetSound.NYA)
        for ((line, voice) in LINES.zip(LINE_VOICES)) c += Cue(line.at, Sound.VOICE, voice = voice)
        // pushing back against Ai's frame
        for ((k, at) in PUSHES.withIndex()) c += Cue(at, Sound.VOICE, voice = if (k % 2 == 0) PetSound.HMPH else PetSound.MRRP)
        // a tick for each letter she types (not her spaces)
        for (line in LINES) {
            val u = ChessyType.units(line.text)
            for (k in u.indices) if (u[k].isNotBlank()) c += Cue(line.at + (k + 1) / TYPE_RATE, Sound.KEY)
        }
        c += Cue(FIGHT_AT, Sound.STATIC)
        c += Cue(KNOCK_AT, Sound.CRUSH)
        // a clean tick for each tenth Ai wins back
        var p = 10f
        var x = RESTORE_AT
        while (x < RESTORED_AT + .05f && p <= 100f) {
            if (restored(x) >= p && restored(x - .01f) < p) { c += Cue(x, Sound.RESTORE); p += 10f }
            x += .01f
        }
        c += Cue(RESTORED_AT, Sound.POWERUP)
        c.sortedBy { it.at }
    }

    // ---- a steady coin and an ease -------------------------------------------------------------------------------------

    /** A deterministic coin in [0, 1) for a slot and a salt, so a seek shows the same glitch. */
    fun rnd(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h.toLong() and 0xFFFFFFFFL) % 100000L) / 100000f
    }

    fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val PI = 3.1415927f

    /** A box's widest, as a share of the window's width: beside her on a wide window, across it on a tall one. */
    fun maxBoxShare(tall: Boolean, centre: Boolean): Float = when {
        tall -> .84f
        centre -> .46f
        else -> .27f
    }
}
