package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * How Chessy is fitted to a canvas: her whole sheet (1320 x 1740), or her head alone, ears to the tips of her hair
 * ([HEAD]), centred and scaled to fit. The drawing and the hand read the same fit, so a touch lands where it is drawn.
 */
object ChessyFit {
    /** Her head on the sheet, ears to the tips of her hair and the bell under her chin: left, top, right, bottom. */
    val HEAD = floatArrayOf(17f, 80f, 1262f, 1660f)
    const val SHEET_W = 1320f
    const val SHEET_H = 1740f

    /** Below this many dp she is drawn as her head alone. */
    const val HEAD_BELOW_DP = 150f

    /** Scale and offset (screen = offset + sheet × scale) for a [w] x [h] canvas: s, ox, oy. */
    fun of(w: Float, h: Float, head: Boolean): FloatArray {
        val l0 = if (head) HEAD[0] else 0f
        val t0 = if (head) HEAD[1] else 0f
        val bw = if (head) HEAD[2] - HEAD[0] else SHEET_W
        val bh = if (head) HEAD[3] - HEAD[1] else SHEET_H
        val s = minOf(w / bw, h / bh)
        return floatArrayOf(s, (w - bw * s) / 2f - l0 * s, (h - bh * s) / 2f - t0 * s)
    }

    /** The sheet point under canvas point ([x], [y]). */
    fun toSheet(w: Float, h: Float, head: Boolean, x: Float, y: Float): Pair<Float, Float> {
        val f = of(w, h, head)
        return (x - f[1]) / f[0] to (y - f[2]) / f[0]
    }
}

/** Where a hand lands on her, for the petting mode. */
enum class AmieZone { HEAD, EAR_L, EAR_R, CHEEK_L, CHEEK_R, CHIN, BELL, FACE, NONE }

object AmieZones {
    /** The zone at sheet point ([x], [y]): ears above her hair, the bell under her chin, cheeks either side of her nose. */
    fun at(x: Float, y: Float): AmieZone = when {
        y in 80f..640f && x in 60f..560f && y < 760f - (x - 60f) * .55f -> AmieZone.EAR_L
        y in 80f..640f && x in 760f..1250f && y < 760f - (1250f - x) * .55f -> AmieZone.EAR_R
        x in 489f..770f && y in 1336f..1615f -> AmieZone.BELL
        x in 500f..760f && y in 1240f..1336f -> AmieZone.CHIN
        x in 290f..520f && y in 1010f..1240f -> AmieZone.CHEEK_L
        x in 740f..980f && y in 1010f..1240f -> AmieZone.CHEEK_R
        x in 250f..1010f && y in 820f..1336f -> AmieZone.FACE
        x in 40f..1280f && y in 300f..830f -> AmieZone.HEAD
        else -> AmieZone.NONE
    }
}

/**
 * What she does when touched: a face worn [seconds], a line with its kaomoji, how many foil hearts and sparkles rise,
 * an ear that twitches (−1 left, 1 right) and whether her bell rings.
 */
data class AmieReaction(
    val mood: Expression,
    val line: String,
    val seconds: Double = 2.6,
    val hearts: Int = 0,
    val sparkles: Int = 0,
    val ear: Int = 0,
    val ring: Boolean = false,
)

/**
 * Chessy's petting mode (kai, 2026-10: "a cute interaction mode like Pokemon Amie … she'll say moe lines like 'That
 * tickles!' and 'Thank you~'"): the grammar of a hand on her, pure and tested. A tap is answered by where it lands; a
 * stroke back and forth is petting (her head), tickling (her chin) or a rub (her cheeks), answered at most every
 * [ANSWER_EVERY] seconds and warmer as she grows fond of you ([fondness], 0 to 1); poking her too fast makes her sulk
 * until she is petted; a hand held still on her is a hug; left alone she wonders where you went, then dozes. Lines
 * never repeat back to back. Time is seconds on any clock.
 */
class ChessyAmie(seed: Int = 7) {
    private val random = Random(seed)
    private val last = HashMap<String, String>()
    private val taps = ArrayList<Double>()
    private var sulking = false
    private var lastAnswer = -100.0
    private var lastTouch = 0.0
    private var lonely = false
    private var asleep = false

    // the stroke under way
    private var travel = 0f
    private var turns = 0
    private var lastSign = 0f
    private var lastStroke = -100.0

    /** How fond of you she has grown this visit, 0 to 1: petting raises it, sulking lowers it. */
    var fondness = 0f
        private set

    fun greet(now: Double): AmieReaction {
        lastTouch = now
        return AmieReaction(Expression.FOUND, pick("greet"), 3.0, hearts = 2, sparkles = 8)
    }

    fun bye(): AmieReaction = AmieReaction(Expression.WINK, pick("bye"), 3.0, hearts = 1)

    /** A tap on [zone]. */
    fun tap(zone: AmieZone, now: Double): AmieReaction? {
        if (zone == AmieZone.NONE) return null
        touch(now)
        taps.removeAll { now - it > POKE_WINDOW }
        taps += now
        if (taps.size >= POKES) {
            taps.clear()
            sulking = true
            fondness = (fondness - .15f).coerceAtLeast(0f)
            return answer(now, AmieReaction(Expression.ANGRY, pick("sulk"), 3.0))
        }
        if (sulking) return answer(now, AmieReaction(Expression.SAD, pick("still-sulking"), 2.4))
        return answer(now, when (zone) {
            AmieZone.EAR_L, AmieZone.EAR_R -> AmieReaction(Expression.SHY, pick("ear"), 2.6, sparkles = 2, ear = if (zone == AmieZone.EAR_L) -1 else 1)
            AmieZone.CHEEK_L, AmieZone.CHEEK_R -> AmieReaction(Expression.OOPS, pick("cheek"), 2.4, sparkles = 1)
            AmieZone.BELL -> AmieReaction(Expression.WINK, pick("bell"), 2.4, sparkles = 5, ring = true)
            AmieZone.CHIN -> AmieReaction(Expression.DELIGHTED, pick("tickle"), 2.4, hearts = 1, sparkles = 2)
            AmieZone.HEAD -> AmieReaction(Expression.WAITING, pick("head-tap"), 2.2)
            else -> AmieReaction(Expression.LISTENING, pick("face"), 2.2, sparkles = 1)
        })
    }

    /**
     * The hand moved [dx], [dy] (dp) over [zone], pressed or hovering: back and forth enough, it is a pet, a tickle
     * or a rub. Null until it is, or while the last answer is still being said.
     */
    fun stroke(zone: AmieZone, dx: Float, dy: Float, now: Double): AmieReaction? {
        if (zone == AmieZone.NONE) return null
        touch(now)
        if (now - lastStroke > STROKE_GAP) { travel = 0f; turns = 0; lastSign = 0f }
        lastStroke = now
        val along = if (abs(dx) >= abs(dy)) dx else dy
        travel += abs(dx) + abs(dy)
        val s = sign(along)
        if (s != 0f && lastSign != 0f && s != lastSign) turns++
        if (s != 0f) lastSign = s
        if (turns < TURNS || travel < TRAVEL || now - lastAnswer < ANSWER_EVERY) return null
        turns = 0
        travel = 0f
        if (sulking) {
            sulking = false
            return answer(now, AmieReaction(Expression.SHY, pick("forgive"), 2.8, hearts = 2))
        }
        return answer(now, when (zone) {
            AmieZone.CHIN -> AmieReaction(Expression.DELIGHTED, pick("tickle"), 2.4, hearts = 2, sparkles = 3).also { warm(.06f) }
            AmieZone.CHEEK_L, AmieZone.CHEEK_R -> AmieReaction(Expression.SHY, pick("rub"), 2.4, hearts = 2).also { warm(.05f) }
            AmieZone.EAR_L, AmieZone.EAR_R -> AmieReaction(Expression.SHY, pick("ear"), 2.4, hearts = 1, ear = if (zone == AmieZone.EAR_L) -1 else 1)
            AmieZone.BELL -> AmieReaction(Expression.WINK, pick("bell"), 2.2, sparkles = 4, ring = true)
            else -> {
                warm(.08f)
                if (fondness >= FOND) AmieReaction(Expression.LOVE, pick("adore"), 3.0, hearts = 5, sparkles = 3)
                else AmieReaction(Expression.DELIGHTED, pick("pet"), 2.6, hearts = 3)
            }
        })
    }

    /** A hand held still on [zone] past [HOLD]: a hug. */
    fun hold(zone: AmieZone, now: Double): AmieReaction? {
        if (zone == AmieZone.NONE) return null
        touch(now)
        warm(.1f)
        sulking = false
        return answer(now, AmieReaction(Expression.LOVE, pick("hug"), 3.0, hearts = 4))
    }

    /** Nothing touched her for a while: she wonders where you went, then dozes. Null when there is nothing to say. */
    fun idle(now: Double): AmieReaction? {
        val quiet = now - lastTouch
        if (!asleep && quiet >= SLEEPY) { asleep = true; return answer(now, AmieReaction(Expression.SLEEPING, pick("sleepy"), 30.0)) }
        if (!lonely && quiet >= LONELY) { lonely = true; return answer(now, AmieReaction(Expression.WAITING, pick("lonely"), 3.0)) }
        return null
    }

    private fun touch(now: Double) {
        lastTouch = now
        lonely = false
        if (asleep) asleep = false
    }

    private fun warm(by: Float) { fondness = (fondness + by).coerceAtMost(1f) }

    private fun answer(now: Double, r: AmieReaction): AmieReaction { lastAnswer = now; return r }

    /** A line of [kind], never the one said last time. */
    private fun pick(kind: String): String {
        val all = LINES.getValue(kind)
        val choices = if (all.size > 1) all.filter { it != last[kind] } else all
        return choices[random.nextInt(choices.size)].also { last[kind] = it }
    }

    companion object {
        const val POKES = 5
        const val POKE_WINDOW = 2.0
        const val HOLD = .7
        const val STROKE_GAP = .6
        const val TURNS = 2
        const val TRAVEL = 70f
        const val ANSWER_EVERY = 1.6
        const val FOND = .5f
        const val LONELY = 9.0
        const val SLEEPY = 24.0

        /** Her words, by what happened (kai: "think of more to make her charming and adorable"). */
        val LINES: Map<String, List<String>> = mapOf(
            "greet" to listOf(
                "Ah! You came to play with me? (≧▽≦)",
                "Just the two of us now~ (*´꒳`*)",
                "Nyaa~ hi hi! Pet me, pet me! ฅ(^・ω・^ฅ)",
            ),
            "pet" to listOf(
                "Ehehe~ that feels nice (=^･ω･^=)",
                "Mmm… more, please~ (´,,•ω•,,)",
                "Nyaa~ you're good at this (*ﾟ▽ﾟ*)",
                "Purrr… (ฅ´ω`ฅ)",
                "Don't stop~ (=´∇｀=)",
            ),
            "adore" to listOf(
                "I like you, you know~ (〃▽〃)",
                "You're my favourite human ♡ (◕ᴗ◕✿)",
                "Thank you~ for always building with me (*˘︶˘*).｡*♡",
                "Hehe… I'm all warm now (っ˘ω˘ς )",
            ),
            "tickle" to listOf(
                "That tickles! (≧▽≦)",
                "Ahaha, stop it~! (>ω<)",
                "Nya-ha-ha! N-not there! (｡>﹏<｡)",
            ),
            "cheek" to listOf(
                "Puu~ (・ε・)",
                "Hey, my cheeks aren't mochi! (｀・ω・´)",
                "Squish… ( ˘ ³˘)",
            ),
            "rub" to listOf(
                "Ehehe, squishy squishy~ (*´ω｀*)",
                "Mrrr… my cheeks are getting warm (〃ω〃)",
            ),
            "ear" to listOf(
                "My ears are sensitive~! (/ω＼)",
                "Eep! Ears! (⁄ ⁄>⁄ ▽ ⁄<⁄ ⁄)",
                "Hehe, they twitch on their own (=ﾟωﾟ=)",
            ),
            "bell" to listOf(
                "Ring ring~ ♪ ヾ(＾∇＾)",
                "That's my lucky bell! ♪(๑ᴖ◡ᴖ๑)♪",
                "Chiriin~ (´∀`)♪",
            ),
            "head-tap" to listOf(
                "Hm? Pats go like this, see? (・ω・)ゞ",
                "A boop on the head? Hehe (๑˃ᴗ˂)ﻭ",
            ),
            "face" to listOf(
                "Hm? (・ω・)?",
                "Boop! (๑˃ᴗ˂)ﻭ",
                "Yes yes, I'm here~ (｡•̀ᴗ-)✧",
            ),
            "sulk" to listOf(
                "Mou~! That's too much! (｀へ´)",
                "Hmph! I'm sulking now. (￣^￣)",
            ),
            "still-sulking" to listOf(
                "…I'm still sulking. Pet me and maybe I'll forgive you. (￣ヘ￣)",
                "Hmph. (｀ε´)",
            ),
            "forgive" to listOf(
                "…Okay, you're forgiven. (´・ω・`)",
                "Fine~ just this once. Ehehe (〃▽〃)",
            ),
            "hug" to listOf(
                "Thank you~ this is warm (˶ᵔ ᵕ ᵔ˶)",
                "Ehh? You're holding on… (⁄ ⁄•⁄ω⁄•⁄ ⁄)",
                "Stay like this a little longer~ (つ≧▽≦)つ",
            ),
            "lonely" to listOf(
                "Are you still there…? (・・？)",
                "Helloooo? I'm right here~ (｡•́︿•̀｡)",
            ),
            "sleepy" to listOf(
                "Fuwaa… getting sleepy… (－ω－) zzZ",
                "Nya… five more minutes… (￣o￣) zzZ",
            ),
            "bye" to listOf(
                "Come back and play again soon~! (ﾉ´ヮ`)ﾉ*: ･ﾟ",
                "Bye-bye~ I'll be in the chat box! (๑•̀ᴗ•̀)و",
                "See you, partner~ ฅ^•ﻌ•^ฅ",
            ),
        )
    }
}

/** A foil heart or sparkle rising from where she was touched. */
class AmieParticle(var x: Float, var y: Float, val heart: Boolean, val vx: Float, val vy: Float, val size: Float, val phase: Float) {
    var age = 0f

    /** 0 to 1 over its life, and its alpha: in fast, out slow. */
    val life: Float get() = (age / LIFE).coerceIn(0f, 1f)
    val alpha: Float get() = minOf(1f, life * 8f) * (1f - life) * (1f - life)

    companion object {
        const val LIFE = 1.6f
    }
}

/**
 * The hearts and sparkles of the petting mode, in canvas pixels: each rises, sways and fades over
 * [AmieParticle.LIFE] seconds; at most [MAX] at once, the oldest let go first.
 */
class AmieParticles(seed: Int = 11) {
    private val random = Random(seed)
    val live = ArrayList<AmieParticle>()

    /** [hearts] and [sparkles] from ([x], [y]), sized from [unit] (a heart's size in pixels). */
    fun burst(x: Float, y: Float, hearts: Int, sparkles: Int, unit: Float) {
        repeat(hearts + sparkles) { i ->
            val heart = i < hearts
            val a = (-PI / 2 + (random.nextFloat() - .5f) * PI * .9f).toFloat()
            val speed = unit * (2.2f + random.nextFloat() * 1.6f)
            live += AmieParticle(
                x + (random.nextFloat() - .5f) * unit, y + (random.nextFloat() - .5f) * unit * .5f, heart,
                cos(a) * speed * .6f, sin(a) * speed, unit * (if (heart) .8f + random.nextFloat() * .5f else .45f + random.nextFloat() * .4f),
                random.nextFloat() * 6.28f,
            )
        }
        while (live.size > MAX) live.removeAt(0)
    }

    /** Advance [dt] seconds; true while any is still to be drawn. */
    fun step(dt: Float): Boolean {
        val d = dt.coerceIn(0f, .05f)
        val it = live.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += d
            if (p.age >= AmieParticle.LIFE) { it.remove(); continue }
            p.x += (p.vx + sin(p.age * 5f + p.phase) * p.size * 1.2f) * d
            p.y += p.vy * d * (1f - p.life * .6f)
        }
        return live.isNotEmpty()
    }

    companion object {
        const val MAX = 60
    }
}
