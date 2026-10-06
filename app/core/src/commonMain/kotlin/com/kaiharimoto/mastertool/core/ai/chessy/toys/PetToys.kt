package com.kaiharimoto.mastertool.core.ai.chessy.toys

import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Chessy's toys in her petting mode (kai, 2026-10: "add more toys to play with chessy like cat toys and cat nip for
 * funny interactions. the styling of the toys and stuff should be in the same style and build quality as the coin and
 * dice in the duel simulator"): a yarn ball, a feather wand, a wind-up mouse and a pouch of catnip, each a body with
 * real physics in the room she sits in, drawn as the dice are (paper, ink, a step of shade for each way a surface turns
 * from the light). Pure and tested: the room is pixels, x right, y down, z out of the screen toward the person.
 */
enum class ToyKind(val title: String, val short: String, val verb: String, val hint: String) {
    YARN("Yarn ball", "Yarn", "Throw", "Drag and throw"),
    FEATHER("Feather wand", "Feather", "Wave", "Drag and wave"),
    MOUSE("Wind-up mouse", "Mouse", "Wind up", "Tap to wind"),
    CATNIP("Catnip", "Catnip", "Give", "Drop on her"),
}

/**
 * Where the toys play, in pixels: the walls and the floor, and Chessy on it. Her shape is the layout's ([headRise],
 * [headR], [mouthRise], [halfW], [reach]: from her size); where she is, her body's ([herX], [herHop], [ChessyPlay]).
 */
class PetRoom {
    var left = 0f
    var right = 1000f
    var top = 0f
    var floor = 800f

    /** How high the middle of her head is over the floor, and its radius. */
    var headRise = 400f
    var headR = 200f

    /** How high her mouth is over the floor. */
    var mouthRise = 240f

    /** Half her drawn width: how near a wall her middle may come. */
    var halfW = 300f

    /** How far across her bite reaches. */
    var reach = 120f

    /** Where she is: her middle across, and how far she is off the floor (a hop). */
    var herX = 500f
    var herHop = 0f

    val headX: Float get() = herX
    val headY: Float get() = floor - headRise - herHop
    val mouthX: Float get() = herX
    val mouthY: Float get() = floor - mouthRise - herHop

    /** Pixels in a dp: every speed and size below is in dp and scaled by it. */
    var unit = 1f

    /** Her middle across: where she sits. */
    val middle: Float get() = herX
}

/** What a toy did, or she did with it ([ToyEvent.kind] null: she alone), that she answers or that sounds. */
enum class ToyHit {
    /** It flew past her face, or she spotted it running. */
    NEAR,

    /** It hit the floor or a wall ([ToyEvent.strength] how hard): a sound. */
    BOUNCE,

    /** She bit it and sent it flying. */
    BIT,

    /** She caught the mouse: it bounced off her. */
    CAUGHT,

    /** Her bite or pounce missed. */
    MISSED,

    /** The feather swept through the air fast: a sound. */
    SWISH,

    /** She leapt. */
    POUNCE,

    /** She landed from a leap ([ToyEvent.strength] how hard). */
    LAND,
}

class ToyEvent(val kind: ToyKind?, val hit: ToyHit, val x: Float, val y: Float, val strength: Float = 1f)

/** The light, as the dice have it: from above the person's left shoulder (y down, so up is −y). */
val TOY_LIGHT: V3 = V3(-0.45, -0.35, 1.0).normalized()

/** A step of shade for a surface whose normal meets the light at [lit]: 0 none, then the faintest to the darkest. */
fun toyShade(lit: Double): Int = when {
    lit > 0.8 -> 0
    lit > 0.45 -> 1
    lit > 0.1 -> 2
    else -> 3
}

/**
 * A string of [n] links of [link] pixels, hung from its first point: Verlet, as a strand of yarn, a mouse's tail and
 * the feather's string are. Its first point is put where it hangs from each step; the floor holds the rest up.
 */
class Rope(val n: Int, var link: Float) {
    val x = FloatArray(n + 1)
    val y = FloatArray(n + 1)
    private val px = FloatArray(n + 1)
    private val py = FloatArray(n + 1)

    /** Laid out straight down from ([ax], [ay]), at rest. */
    fun hang(ax: Float, ay: Float) {
        for (i in 0..n) { x[i] = ax; y[i] = ay + i * link; px[i] = x[i]; py[i] = y[i] }
    }

    /** Its end kicked by ([vx], [vy]) pixels a second, as of a step of [dt]. */
    fun kick(vx: Float, vy: Float, dt: Float) {
        px[n] = x[n] - vx * dt
        py[n] = y[n] - vy * dt
    }

    /** How fast its end moves, pixels a second, over the last step of [dt]. */
    fun endSpeed(dt: Float): Float = if (dt <= 0f) 0f else hypot(x[n] - px[n], y[n] - py[n]) / dt

    fun step(dt: Float, ax: Float, ay: Float, gravity: Float, floor: Float, damping: Float = .985f) {
        val g = gravity * dt * dt
        for (i in 1..n) {
            val vx = (x[i] - px[i]) * damping
            val vy = (y[i] - py[i]) * damping
            px[i] = x[i]; py[i] = y[i]
            x[i] += vx
            y[i] += vy + g
        }
        px[0] = x[0]; py[0] = y[0]
        x[0] = ax; y[0] = ay
        repeat(6) {
            for (i in 0 until n) {
                val dx = x[i + 1] - x[i]
                val dy = y[i + 1] - y[i]
                val d = max(1e-4f, sqrt(dx * dx + dy * dy))
                val k = (d - link) / d
                if (i == 0) {
                    x[1] -= dx * k; y[1] -= dy * k
                } else {
                    x[i] += dx * k * .5f; y[i] += dy * k * .5f
                    x[i + 1] -= dx * k * .5f; y[i + 1] -= dy * k * .5f
                }
            }
            for (i in 1..n) if (y[i] > floor) { y[i] = floor; px[i] = x[i] - (x[i] - px[i]) * .6f }
        }
    }
}

/**
 * A ball of yarn [radius] pixels across its middle: thrown, it flies, spins, bounces off the walls and the floor and rolls
 * to a stop with its spin matched to its roll, in front of her (kai, 1.1.29: "the yarn ball rolls in front of her and she
 * can make it move on her own by biting on it"); its loose end trails. A bite ([kick]) sends it off again.
 */
class Yarn(var radius: Float) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var q: Quat = Quat(0.92, 0.2, 0.3, 0.1).normalized()
    var w: V3 = V3.ZERO
    var held = false
    var out = false
    val strand = Rope(7, radius * .42f)
    private var cool = FloatArray(ToyHit.entries.size)

    /** Whether it lies or rolls on the floor. */
    fun onFloor(room: PetRoom): Boolean = !held && y >= room.floor - radius - 1f && abs(vy) < 60f * room.unit

    /** Where its loose end leaves the ball, on its surface: the body point (0, 1, 0) turned by [q]. */
    fun anchor(): V3 = q.rotate(V3(0.3, 0.95, 0.1).normalized())

    fun place(px: Float, py: Float) {
        x = px; y = py; vx = 0f; vy = 0f; w = V3.ZERO
        val a = anchor()
        strand.hang(x + a.x.toFloat() * radius, y + a.y.toFloat() * radius)
    }

    val moving: Boolean get() = held || abs(vx) > 2f || abs(vy) > 2f || w.length > .05

    fun step(dt: Float, room: PetRoom, random: Random, events: MutableList<ToyEvent>) {
        if (!out) return
        val u = room.unit
        for (i in cool.indices) cool[i] = max(0f, cool[i] - dt)
        if (!held) {
            vy += GRAVITY * u * dt
            x += vx * dt
            y += vy * dt
            // the walls and the ceiling
            if (x < room.left + radius) { x = room.left + radius; if (vx < 0f) { bounced(-vx, u, events); vx = -vx * .7f } }
            if (x > room.right - radius) { x = room.right - radius; if (vx > 0f) { bounced(vx, u, events); vx = -vx * .7f } }
            if (y < room.top + radius) { y = room.top + radius; if (vy < 0f) vy = -vy * .6f }
            // the floor: a bounce that dies away, then a roll
            val onFloor = y >= room.floor - radius - .5f
            if (y > room.floor - radius) {
                y = room.floor - radius
                if (vy > 0f) { bounced(vy, u, events); vy = if (vy > 60f * u) -vy * .48f else 0f }
            }
            if (onFloor && abs(vy) < 1f) {
                vx *= exp(-ROLL_DRAG * dt)
                if (abs(vx) < 3f * u) vx = 0f
                // spin matched to the roll: ω.z = v.x / r; the other turns die away
                w = V3(w.x * exp(-6.0 * dt), w.y * exp(-6.0 * dt), (vx / radius).toDouble())
            } else {
                w = w * exp(-.3 * dt)
            }
            // flying close past her face
            val d = hypot(x - room.headX, y - room.headY)
            if (d < room.headR * 1.7f && hypot(vx, vy) > 700f * u) hit(ToyHit.NEAR, 2.5f, events)
        }
        q = q.integrate(w, dt.toDouble())
        val a = anchor()
        strand.link = radius * .42f
        strand.step(dt, x + a.x.toFloat() * radius, y + a.y.toFloat() * radius, GRAVITY * u, room.floor)
    }

    private fun bounced(speed: Float, u: Float, events: MutableList<ToyEvent>) {
        if (speed < 180f * u || cool[ToyHit.BOUNCE.ordinal] > 0f) return
        cool[ToyHit.BOUNCE.ordinal] = .07f
        events += ToyEvent(ToyKind.YARN, ToyHit.BOUNCE, x, y, (speed / (1800f * u)).coerceIn(.1f, 1f))
    }

    /** Bitten: off it goes at ([kvx], [kvy]) pixels a second, spinning. */
    fun kick(kvx: Float, kvy: Float, random: Random) {
        vx = kvx
        vy = kvy
        w = V3(random.nextDouble(-6.0, 6.0), random.nextDouble(-6.0, 6.0), (vx / radius).toDouble())
    }

    private fun hit(kind: ToyHit, cooldown: Float, events: MutableList<ToyEvent>) {
        if (cool[kind.ordinal] > 0f) return
        cool[kind.ordinal] = cooldown
        events += ToyEvent(ToyKind.YARN, kind, x, y)
    }

    /** Thrown from the hand at ([tvx], [tvy]) pixels a second, given a spin to match. */
    fun release(tvx: Float, tvy: Float, room: PetRoom, random: Random) {
        held = false
        val cap = THROW_CAP * room.unit
        val s = hypot(tvx, tvy)
        val k = if (s > cap) cap / s else 1f
        vx = tvx * k
        vy = tvy * k
        w = V3(vy / radius * .6 + random.nextDouble(-2.0, 2.0), -vx / radius * .4 + random.nextDouble(-2.0, 2.0), (vx / radius).toDouble())
    }

    companion object {
        const val GRAVITY = 2600f
        const val ROLL_DRAG = 1.1f
        const val THROW_CAP = 4200f
    }
}

/**
 * A clockwork mouse [length] pixels nose to tail: wound, it runs along the floor and turns at the walls, and she chases
 * it; caught, it bounces off her ([bounce]: it flips over and lands on its feet, still running); run down, it stops.
 */
class WindupMouse(var length: Float) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var facing = 1f
    var q: Quat = Quat.IDENTITY
    var w: V3 = V3.ZERO
    var held = false
    var out = false

    /** Seconds of running left in its spring. */
    var wound = 0f

    /** Its key's turn, radians: it turns while the spring runs. */
    var key = 0f

    /** Its gait: a little up-and-down as it runs, in pixels. */
    var hop = 0f
    val tail = Rope(6, length * .1f)
    private var clock = 0f

    val height: Float get() = length * .42f
    val moving: Boolean get() = held || wound > 0f || abs(vy) > 2f || abs(vx) > 2f || w.length > .05

    fun place(px: Float, py: Float) {
        x = px; y = py; vx = 0f; vy = 0f; w = V3.ZERO; q = upright()
        tail.hang(px - facing * length * .5f, py)
    }

    /** Standing on its feet, facing [facing]: turned half round its upright axis to face left. */
    fun upright(): Quat = if (facing > 0f) Quat.IDENTITY else Quat(0.0, 0.0, 1.0, 0.0)

    fun wind(seconds: Float = WIND) { wound = seconds }

    fun step(dt: Float, room: PetRoom, random: Random, events: MutableList<ToyEvent>) {
        if (!out) return
        val u = room.unit
        clock += dt
        val floorY = room.floor - height / 2f
        if (!held) {
            vy += Yarn.GRAVITY * u * dt
            y += vy * dt
            val onFloor = y >= floorY - .5f
            if (y > floorY) {
                y = floorY
                if (vy > 0f) {
                    if (vy > 250f * u) events += ToyEvent(ToyKind.MOUSE, ToyHit.BOUNCE, x, y, (vy / (1800f * u)).coerceIn(.1f, 1f))
                    vy = if (vy > 80f * u) -vy * .3f else 0f
                }
            }
            if (onFloor && abs(vy) < 1f) {
                // on its feet again, it settles upright; then it runs while it is wound
                q = Quat.nlerp(q, upright(), min(1.0, dt * 12.0))
                w = V3.ZERO
                if (wound > 0f) {
                    wound -= dt
                    vx = facing * RUN * u * (if (wound < .6f) wound / .6f else 1f)
                    key += dt * 9f
                    hop = abs(kotlin.math.sin(clock * 22f)) * length * .025f
                } else {
                    wound = 0f
                    vx *= exp(-8f * dt)
                    hop = 0f
                }
            } else {
                q = q.integrate(w, dt.toDouble())
                hop = 0f
            }
            x += vx * dt
            val half = length * .5f
            if (x < room.left + half) { x = room.left + half; facing = 1f; vx = abs(vx) }
            if (x > room.right - half) { x = room.right - half; facing = -1f; vx = -abs(vx) }
        } else {
            vx = 0f; vy = 0f; hop = 0f
        }
        val back = q.rotate(V3(-0.5, 0.05, 0.0))
        tail.link = length * .1f
        tail.step(dt, x + back.x.toFloat() * length, y + back.y.toFloat() * length + hop, Yarn.GRAVITY * u * .4f, room.floor)
    }

    /** Caught by her: it bounces up off her nose and flips, and lands on its feet still running. */
    fun bounce(room: PetRoom, random: Random) {
        val u = room.unit
        vy = -(950f + random.nextFloat() * 250f) * u
        vx = facing * (140f + random.nextFloat() * 120f) * u
        w = V3(0.0, 0.0, facing * (12.0 + random.nextDouble() * 4.0))
    }

    /** Let go from the hand, moving ([tvx], [tvy]) pixels a second: it falls, tumbling if it was thrown. */
    fun release(tvx: Float, tvy: Float, room: PetRoom) {
        held = false
        val cap = Yarn.THROW_CAP * .6f * room.unit
        val s = hypot(tvx, tvy)
        val k = if (s > cap) cap / s else 1f
        vx = tvx * k * .5f
        vy = tvy * k
        if (abs(tvx) > 40f * room.unit) facing = if (tvx > 0f) 1f else -1f
        w = if (s > 400f * room.unit) V3(0.0, 0.0, (tvx / length * .4).coerceIn(-14.0, 14.0)) else V3.ZERO
    }

    companion object {
        const val RUN = 230f
        const val WIND = 4.5f
    }
}

/**
 * A feather on a string at the end of a wand: in the hand, the wand's handle follows the pointer and the feather swings
 * after it, in front of her; she follows it and bites at it, and a bite that lands knocks it flying ([knock]).
 */
class FeatherWand(var stick: Float) {
    var hx = 0f
    var hy = 0f
    var held = false
    val string = Rope(9, stick * .07f)
    private var cool = 0f
    private var dtLast = 1f / 60f

    /** The wand's tip, where the string hangs from: up from the handle and leaning toward her. */
    fun tip(room: PetRoom): Pair<Float, Float> {
        val lean = (if (hx < room.middle) 1f else -1f) * .42f
        val n = sqrt(1f + lean * lean)
        return (hx + lean / n * stick) to (hy - 1f / n * stick)
    }

    fun take(x: Float, y: Float, room: PetRoom) {
        held = true
        hx = x; hy = y
        val (tx, ty) = tip(room)
        string.link = stick * .07f
        string.hang(tx, ty)
    }

    val endX: Float get() = string.x[string.n]
    val endY: Float get() = string.y[string.n]

    /** The feather's speed, pixels a second. */
    val speed: Float get() = string.endSpeed(dtLast)

    fun step(dt: Float, room: PetRoom, events: MutableList<ToyEvent>) {
        if (!held) return
        dtLast = max(dt, 1e-3f)
        cool = max(0f, cool - dt)
        val (tx, ty) = tip(room)
        string.step(dt, tx, ty, Yarn.GRAVITY * room.unit * .35f, room.floor, damping = .97f)
        if (speed > 1100f * room.unit && cool <= 0f) {
            cool = .28f
            events += ToyEvent(ToyKind.FEATHER, ToyHit.SWISH, endX, endY, (speed / (2600f * room.unit)).coerceIn(.2f, 1f))
        }
    }

    /** Bitten: the feather knocked at ([vx], [vy]) pixels a second. */
    fun knock(vx: Float, vy: Float) = string.kick(vx, vy, dtLast)
}

/** A pouch of catnip in the hand: let go over her, it is given. */
class Catnip(var size: Float) {
    var x = 0f
    var y = 0f
    var held = false

    /** Whether ([x], [y]) is over her: her head, or as near it as a sniff reaches. */
    fun over(room: PetRoom): Boolean = hypot(x - room.headX, y - room.headY) < room.headR * 1.15f
}

/**
 * Every toy in the room, stepped together. [focus] is what she watches: the toy that moved last, or null for the
 * person's hand.
 */
class PetToys(val room: PetRoom = PetRoom(), seed: Int = 3) {
    private val random = Random(seed)
    val yarn = Yarn(YARN_R)
    val mouse = WindupMouse(MOUSE_L)
    val wand = FeatherWand(WAND_L)
    val catnip = Catnip(NIP_S)

    /** Her, playing with them. */
    val her = ChessyPlay()
    private val events = ArrayList<ToyEvent>()
    private var lastMoving: ToyKind? = null

    /** Sizes from the room's unit (times [k]; a phone's are smaller): the yarn [YARN_R] dp across its middle, the mouse [MOUSE_L] long, and so on. */
    fun scale(k: Float = 1f) {
        val u = room.unit * k
        yarn.radius = YARN_R * u
        mouse.length = MOUSE_L * u
        wand.stick = WAND_L * u
        catnip.size = NIP_S * u
    }

    val moving: Boolean get() = yarn.out && yarn.moving || mouse.out && mouse.moving || wand.held || yarn.held || mouse.held || catnip.held || her.moving

    /** Advance [dt] seconds (cut into steps of at most 1/120 s); what happened, for her to answer. */
    fun step(dt: Float): List<ToyEvent> {
        events.clear()
        val d = dt.coerceIn(0f, .1f)
        val pieces = max(1, kotlin.math.ceil(d * 120f).toInt())
        val h = d / pieces
        repeat(pieces) {
            yarn.step(h, room, random, events)
            mouse.step(h, room, random, events)
            wand.step(h, room, events)
            her.step(h, room, this, random, events)
        }
        if (yarn.held || yarn.out && yarn.moving && hypot(yarn.vx, yarn.vy) > 60f * room.unit) lastMoving = ToyKind.YARN
        if (mouse.held || mouse.out && mouse.wound > 0f) lastMoving = ToyKind.MOUSE
        if (wand.held) lastMoving = ToyKind.FEATHER
        if (catnip.held) lastMoving = ToyKind.CATNIP
        if (lastMoving == ToyKind.YARN && !yarn.moving) lastMoving = null
        if (lastMoving == ToyKind.MOUSE && !mouse.moving) lastMoving = null
        if (lastMoving == ToyKind.FEATHER && !wand.held) lastMoving = null
        if (lastMoving == ToyKind.CATNIP && !catnip.held) lastMoving = null
        return events
    }

    /** What she is watching, in room pixels: the toy in play, or null for the person's hand. */
    fun focus(): Pair<Float, Float>? = when (her.target ?: lastMoving) {
        ToyKind.YARN -> yarn.x to yarn.y
        ToyKind.MOUSE -> mouse.x to mouse.y
        ToyKind.FEATHER -> wand.endX to wand.endY
        ToyKind.CATNIP -> catnip.x to catnip.y
        null -> null
    }

    fun random(): Random = random

    companion object {
        const val YARN_R = 44f
        const val MOUSE_L = 124f
        const val WAND_L = 230f
        const val NIP_S = 78f
    }
}
