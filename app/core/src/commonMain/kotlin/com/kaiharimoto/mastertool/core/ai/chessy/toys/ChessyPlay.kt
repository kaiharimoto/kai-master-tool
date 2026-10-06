package com.kaiharimoto.mastertool.core.ai.chessy.toys

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What she is doing in her room. */
enum class PlayState {
    /** Sitting on the floor. */
    SIT,

    /** Hopping along the floor to [ChessyPlay.goal]: to a toy, or only to somewhere else. */
    WALK,

    /** Crouched with her eyes on the mouse, wiggling, about to pounce. */
    STALK,

    /** In the air, jaws open, coming down on the mouse. */
    POUNCE,

    /** A bite: crouch, strike, chomp, recover. */
    LUNGE,

    /** She caught the mouse and it bounced off her: little hops, giggling. */
    AMUSED,

    /** Catnip has her: she bounces about. */
    SILLY,

    /** Down in the catnip on the floor, rolling over in it and back. */
    ROLL,
}

/**
 * Chessy in her room as a body (kai, 1.1.29: "let her also move around in the space, and cast a shadow for physics … she
 * should also chase the mouse and try to bite it, and when she does once in a while, the mouse bounces which amuses her";
 * the yarn she bites to set rolling, the feather she bites at): where she sits across the floor ([x]), how far off it she
 * is ([hop]), her lean toward what she is after ([lean], degrees) and her squash and stretch ([sx], [sy]), all about her
 * base; [mouthOpen] while she bites. Pure: stepped by [PetToys.step] with the toys, events out as [ToyEvent]s with a null
 * kind when they are hers alone. A hand on her ([touched]) holds her still.
 */
class ChessyPlay {
    var x = 0f
        private set
    /** How far off the floor she is: a leap's height and her gait's bounce. */
    val hop: Float get() = air + bob
    var lean = 0f
        private set
    var sx = 1f
        private set
    var sy = 1f
        private set
    var mouthOpen = false
        private set

    /** How far she has rolled over, degrees (0 upright), and how far down she has thrown herself, pixels. */
    var spin = 0f
        private set
    var sink = 0f
        private set
    var state = PlayState.SIT
        private set

    /** What she is after, if anything: what she watches. */
    var target: ToyKind? = null
        private set

    /** Where she is walking to. */
    var goal = 0f
        private set

    /** How much catnip has her, 0 to 1: set from outside each step. */
    var silly = 0f

    private var air = 0f
    private var bob = 0f
    private var vx = 0f
    private var vy = 0f
    private var t = 0f
    private var gait = 0f
    private var placed = false
    private var held = 0f
    private var cool = 0f
    private var wanderIn = 4f
    private var spotted = 0f
    private var leanTo = 0f
    private var sxTo = 1f
    private var syTo = 1f
    private var dir = 1f
    private var bitten = false
    private var hopsLeft = 0
    private var rollFrom = 0f
    private var rollCool = 0f

    /** Whether anything of her is still moving: her loop may sleep when not. */
    val moving: Boolean
        get() = state != PlayState.SIT || abs(spin) > .2f || sink > .2f || air > 0f || bob > .05f || abs(lean) > .2f || abs(sx - 1f) > .004f || abs(sy - 1f) > .004f || abs(vx) > 1f

    /** A hand is on her: she holds still for a moment. */
    fun touched() {
        held = HOLD
        if (state != PlayState.SIT && state != PlayState.SILLY) settle()
    }

    fun step(dt: Float, room: PetRoom, toys: PetToys, random: Random, events: MutableList<ToyEvent>) {
        val u = room.unit
        val g = Yarn.GRAVITY * u
        val lo = room.left + room.halfW
        val hi = max(lo, room.right - room.halfW)
        if (!placed) { x = (room.left + room.right) / 2f; placed = true; wanderIn = 3f + random.nextFloat() * 4f }
        x = x.coerceIn(lo, hi)
        t += dt
        held = max(0f, held - dt)
        cool = max(0f, cool - dt)
        spotted = max(0f, spotted - dt)
        rollCool = max(0f, rollCool - dt)

        // the air: a hop is up, and falls
        if (air > 0f || vy > 0f) {
            vy -= g * dt
            air += vy * dt
            if (air <= 0f) {
                val hard = -vy
                air = 0f
                vy = 0f
                if (hard > 300f * u) {
                    // a landing squashes her, and is heard
                    sx = 1.12f; sy = .86f
                    events += ToyEvent(null, ToyHit.LAND, x, room.floor, (hard / (1400f * u)).coerceIn(.1f, 1f))
                }
                if (state == PlayState.POUNCE) land(room, toys, random, events)
            }
        }

        if (silly > 0f && state != PlayState.SILLY && state != PlayState.POUNCE && state != PlayState.ROLL) { state = PlayState.SILLY; t = 0f; target = null; mouthOpen = false }
        when (state) {
            PlayState.SIT -> {
                leanTo = 0f; sxTo = 1f; syTo = 1f
                bob *= exp(-dt * 12f)
                if (held <= 0f) choose(dt, room, toys, random, events)
            }
            PlayState.WALK -> walk(dt, room, toys, random, events)
            PlayState.STALK -> {
                // crouched, her rump wiggling, eyes on it; then she leaps where it will be
                val m = toys.mouse
                sxTo = 1.08f; syTo = .9f
                leanTo = dir * 4f + sin(t * 26f) * 3f
                vx = 0f
                if (!m.out || m.held) settle()
                else if (t > STALK_FOR) {
                    val flight = 2f * POUNCE_UP * u / g
                    val lands = (m.x + m.vx * flight).coerceIn(lo, hi)
                    vx = (lands - x) / flight
                    vy = POUNCE_UP * u
                    air = .01f
                    bob = 0f
                    mouthOpen = true
                    sx = .92f; sy = 1.12f
                    state = PlayState.POUNCE; t = 0f
                    events += ToyEvent(null, ToyHit.POUNCE, x, room.floor)
                }
            }
            PlayState.POUNCE -> {
                leanTo = sign(vx) * 14f
                sxTo = .94f; syTo = 1.08f
                x = (x + vx * dt).coerceIn(lo, hi)
            }
            PlayState.LUNGE -> lunge(dt, room, toys, random, events)
            PlayState.ROLL -> roll(dt, room, toys, random)
            PlayState.AMUSED -> {
                // giggling: a few little hops, a wiggle
                leanTo = sin(t * 13f) * 9f
                sxTo = 1f; syTo = 1f
                if (air == 0f && hopsLeft > 0 && t > .12f) { vy = 380f * u; air = .01f; hopsLeft--; t = 0f }
                if (hopsLeft == 0 && air == 0f && t > .5f) settle()
            }
            PlayState.SILLY -> {
                // catnip: she bounces about, swaying, now this way, now that
                mouthOpen = false
                leanTo = sin(t * 3.1f) * 14f
                if (air == 0f && random.nextFloat() < dt * 1.6f) {
                    vy = (260f + random.nextFloat() * 260f) * u
                    air = .01f
                    vx = (random.nextFloat() - .5f) * 260f * u
                }
                if (air == 0f) vx *= exp(-6f * dt)
                x = (x + vx * dt).coerceIn(lo, hi)
                if (silly <= 0f) settle()
            }
        }
        // out of a roll she comes upright the short way round, and up off the floor
        if (state != PlayState.ROLL) {
            val upright = if (spin > 180f) 360f else if (spin < -180f) -360f else 0f
            spin += (upright - spin) * (1f - exp(-dt * 9f))
            if (abs(spin - upright) < .2f) spin = 0f
            sink *= exp(-dt * 9f)
        }
        // her pose eases toward what the moment asks
        val k = 1f - exp(-dt * 14f)
        lean += (leanTo - lean) * k
        sx += (sxTo - sx) * (1f - exp(-dt * 10f))
        sy += (syTo - sy) * (1f - exp(-dt * 10f))
        room.herX = x
        room.herHop = hop
    }

    /** What to go after, if anything, else now and then somewhere else to sit. */
    private fun choose(dt: Float, room: PetRoom, toys: PetToys, random: Random, events: MutableList<ToyEvent>) {
        if (cool > 0f) return
        val u = room.unit
        val m = toys.mouse
        val y = toys.yarn
        val w = toys.wand
        when {
            m.out && !m.held && m.wound > 0f -> {
                if (spotted <= 0f) events += ToyEvent(ToyKind.MOUSE, ToyHit.NEAR, m.x, m.y)
                spotted = 8f
                go(ToyKind.MOUSE, m.x)
            }
            // catnip on the floor: she has to roll in it (kai: "Chessy should roll around in the catnip")
            rollCool <= 0f && (toys.catnip.patch(room)?.second ?: 0) >= ROLL_MIN -> go(ToyKind.CATNIP, toys.catnip.patch(room)!!.first)
            w.held && featherNear(room, w) -> go(ToyKind.FEATHER, w.endX)
            y.out && y.onFloor(room) && abs(y.vx) < 520f * u && abs(y.x - x) < room.reach * 7f -> go(ToyKind.YARN, y.x)
            else -> {
                wanderIn -= dt
                if (wanderIn <= 0f) {
                    wanderIn = 5f + random.nextFloat() * 7f
                    val lo = room.left + room.halfW
                    val hi = max(lo, room.right - room.halfW)
                    if (hi - lo > room.reach) {
                        target = null
                        goal = lo + random.nextFloat() * (hi - lo)
                        state = PlayState.WALK; t = 0f
                    }
                }
            }
        }
    }

    /** Whether the feather hangs where she could get at it: not far across. Held high, she jumps for it (kai). */
    private fun featherNear(room: PetRoom, w: FeatherWand): Boolean = abs(w.endX - x) < room.reach * 6f

    /** How high she can jump: [JUMP] of her own height, and never her head through the ceiling. */
    private fun jumpMax(room: PetRoom): Float =
        min(room.headRise * JUMP, room.floor - room.headRise - room.headR * .8f - room.top).coerceAtLeast(0f)

    private fun go(kind: ToyKind, at: Float) {
        target = kind
        goal = at
        state = PlayState.WALK
        t = 0f
    }

    private fun walk(dt: Float, room: PetRoom, toys: PetToys, random: Random, events: MutableList<ToyEvent>) {
        val u = room.unit
        val m = toys.mouse
        val y = toys.yarn
        val w = toys.wand
        // keep after a toy that moves; let it go when it is gone
        when (target) {
            ToyKind.MOUSE -> if (!m.out || m.held || m.wound <= 0f) return settle() else goal = m.x
            ToyKind.YARN -> if (!y.out || y.held || !y.onFloor(room)) return settle() else goal = y.x
            ToyKind.FEATHER -> if (!w.held || !featherNear(room, w)) return settle() else goal = w.endX
            ToyKind.CATNIP -> goal = toys.catnip.patch(room)?.first ?: return settle()
            else -> Unit
        }
        val lo = room.left + room.halfW
        val hi = max(lo, room.right - room.halfW)
        val to = goal.coerceIn(lo, hi)
        val dx = to - x
        dir = if (dx != 0f) sign(dx) else dir
        val near = abs(goal - x)
        // close enough: a bite, a pounce, or only sitting down where she meant to be
        when (target) {
            ToyKind.MOUSE -> if (near < room.reach * 2.6f && cool <= 0f) { state = PlayState.STALK; t = 0f; return }
            ToyKind.YARN, ToyKind.FEATHER -> if (near < room.reach && cool <= 0f) { startLunge(); return }
            ToyKind.CATNIP -> if (near < room.reach * .8f || abs(dx) < 2f * u) { startRoll(room, events); return }
            else -> if (abs(dx) < 4f * u) return settle()
        }
        // where she cannot go nearer (a wall), she bites from where she is
        if (abs(dx) < 2f * u && target != null && target != ToyKind.MOUSE && near < room.reach * 2.2f) { startLunge(); return }
        val speed = (if (target == ToyKind.MOUSE) 430f else if (target == null) 170f else 300f) * u
        val step = min(abs(dx), speed * dt) * dir
        x += step
        // a hopping gait: a little bounce a stride, a lean the way she goes
        gait += abs(step) / (26f * u)
        bob = if (air == 0f) abs(sin(gait * PI.toFloat())) * 9f * u else 0f
        leanTo = dir * 7f
        sxTo = 1f; syTo = 1f
        if (abs(dx) < 1f * u && target == null) settle()
    }

    private fun startRoll(room: PetRoom, events: MutableList<ToyEvent>) {
        state = PlayState.ROLL
        t = 0f
        bob = 0f
        rollFrom = x
        mouthOpen = false
        events += ToyEvent(ToyKind.CATNIP, ToyHit.ROLL, x, room.floor)
    }

    /**
     * Rolling in the catnip: down onto the floor, over the way she faces and back, squirming, kicking flakes up as she
     * goes; then up again.
     */
    private fun roll(dt: Float, room: PetRoom, toys: PetToys, random: Random) {
        val u = ((t / ROLL_FOR)).coerceIn(0f, 1f)
        val over = if (u < .5f) smooth(u / .5f) else 1f - smooth((u - .5f) / .5f)
        val before = x
        spin = dir * 360f * over + sin(t * 11f) * 7f * (1f - abs(2f * u - 1f))
        sink = room.headR * .3f * smooth(t / .2f) * (1f - smooth((t - ROLL_FOR + .25f) / .25f))
        val lo = room.left + room.halfW
        val hi = max(lo, room.right - room.halfW)
        // a ball's roll: as far across as her turn would carry her
        x = (rollFrom + dir * over * 2f * PI.toFloat() * room.headR * .32f).coerceIn(lo, hi)
        leanTo = 0f
        sxTo = 1.06f; syTo = .92f
        toys.catnip.flakes.kick(x, room.halfW * .7f, (x - before) / max(dt, 1e-4f), room, random)
        if (t >= ROLL_FOR) {
            spin = 0f
            rollCool = ROLL_AGAIN
            settle()
        }
    }

    private fun smooth(v: Float): Float { val c = v.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    private fun startLunge() {
        state = PlayState.LUNGE
        t = 0f
        bitten = false
        bob = 0f
    }

    /** A bite: crouch, strike with her jaws open, chomp on whatever is there, recover. */
    private fun lunge(dt: Float, room: PetRoom, toys: PetToys, random: Random, events: MutableList<ToyEvent>) {
        val u = room.unit
        val y = toys.yarn
        val w = toys.wand
        val at = when (target) { ToyKind.FEATHER -> w.endX; ToyKind.YARN -> y.x; else -> x }
        dir = if (at != x) sign(at - x) else dir
        when {
            t < CROUCH -> { sxTo = 1.1f; syTo = .86f; leanTo = -dir * 5f }
            t < CROUCH + STRIKE -> {
                sxTo = .95f; syTo = 1.08f; leanTo = dir * 20f
                mouthOpen = true
                // she lunges a little toward it, and jumps for a feather above her
                val lo = room.left + room.halfW
                val hi = max(lo, room.right - room.halfW)
                x = (x + (at - x).coerceIn(-room.reach, room.reach) * min(1f, dt * 9f)).coerceIn(lo, hi)
                // held high (kai: "when I hold the feather high up, have Chessy jump up to bite it"): she leaps for it,
                // as high as it hangs, up to the most she can jump, and bites at the top of the leap
                val sitting = room.floor - room.mouthRise
                if (target == ToyKind.FEATHER && air == 0f && vy == 0f && w.endY < sitting - room.reach * .3f) {
                    val up = min(sitting - w.endY, jumpMax(room))
                    if (up > 0f) {
                        vy = sqrt(2f * Yarn.GRAVITY * u * up)
                        air = .01f
                        events += ToyEvent(null, ToyHit.POUNCE, x, room.floor)
                    }
                }
            }
            // still rising toward the feather: jaws open, stretched up, the bite waits for the top of the leap
            !bitten && target == ToyKind.FEATHER && air > 0f && vy > 0f -> { sxTo = .9f; syTo = 1.14f; leanTo = dir * 10f; mouthOpen = true }
            !bitten -> {
                bitten = true
                mouthOpen = false
                sx = 1.08f; sy = .9f
                when (target) {
                    ToyKind.YARN -> if (y.out && !y.held && abs(y.x - x) < room.reach * 1.5f && y.y > room.floor - room.mouthRise - y.radius * 2f) {
                        val away = if (abs(y.x - x) < 2f * u) (if (random.nextBoolean()) 1f else -1f) else sign(y.x - x)
                        y.kick(away * (620f + random.nextFloat() * 420f) * u, -(420f + random.nextFloat() * 420f) * u, random)
                        events += ToyEvent(ToyKind.YARN, ToyHit.BIT, y.x, y.y)
                    } else events += ToyEvent(ToyKind.YARN, ToyHit.MISSED, x, room.mouthY)
                    ToyKind.FEATHER -> if (w.held && kotlin.math.hypot(w.endX - room.mouthX, w.endY - room.mouthY) < room.reach * 1.9f && random.nextFloat() < .65f) {
                        w.knock(sign(w.endX - x + .01f) * (700f + random.nextFloat() * 500f) * u, -(500f + random.nextFloat() * 400f) * u)
                        events += ToyEvent(ToyKind.FEATHER, ToyHit.BIT, w.endX, w.endY)
                    } else events += ToyEvent(ToyKind.FEATHER, ToyHit.MISSED, x, room.mouthY)
                    else -> Unit
                }
            }
            t < CROUCH + STRIKE + RECOVER -> { sxTo = 1f; syTo = 1f; leanTo = 0f }
            air > 0f -> { sxTo = 1f; syTo = 1f; leanTo = 0f }
            else -> { cool = if (target == ToyKind.FEATHER) .35f else .7f; settle() }
        }
    }

    /** Down from a pounce: on the mouse (now and then a catch, and it bounces off her) or beside it. */
    private fun land(room: PetRoom, toys: PetToys, random: Random, events: MutableList<ToyEvent>) {
        mouthOpen = false
        vx = 0f
        val m = toys.mouse
        val on = m.out && !m.held && abs(m.x - x) < room.reach * 1.1f && m.y > room.floor - m.height * 1.5f
        if (on && random.nextFloat() < CATCH) {
            m.bounce(room, random)
            events += ToyEvent(ToyKind.MOUSE, ToyHit.CAUGHT, m.x, m.y)
            state = PlayState.AMUSED; t = 0f; hopsLeft = 3
            cool = 1.5f
        } else {
            events += ToyEvent(ToyKind.MOUSE, ToyHit.MISSED, x, room.floor)
            cool = 1.1f
            settle()
        }
    }

    private fun settle() {
        state = PlayState.SIT
        target = null
        mouthOpen = false
        t = 0f
        if (air == 0f) vx = 0f
        bob = 0f
    }

    companion object {
        /** How long a hand on her holds her still, seconds. */
        const val HOLD = 1.4f
        const val STALK_FOR = .5f
        const val POUNCE_UP = 640f
        const val CROUCH = .14f
        const val STRIKE = .16f
        const val RECOVER = .3f

        /** The highest she jumps for the feather, in her own heights (to the top of her head). */
        const val JUMP = 1.5f

        /** Flakes lying together that she will roll in, how long a roll takes, and how soon she rolls again. */
        const val ROLL_MIN = 12
        const val ROLL_FOR = 2.6f
        const val ROLL_AGAIN = 5f

        /** How often a pounce on the mouse catches it. */
        const val CATCH = .45f
    }
}
