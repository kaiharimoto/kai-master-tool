package com.kaiharimoto.mastertool.core.ai.chessy.gifts

import com.kaiharimoto.mastertool.core.ai.chessy.toys.PetRoom
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyEvent
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyHit
import com.kaiharimoto.mastertool.core.duel.dice.V3
import kotlin.random.Random

/**
 * The gifts in her room: the box she is making or has made ([box]), its lid flying off and the opened box fading
 * ([lid], [opened]), the gifts out to play with ([out], at most [MAX_OUT]) and the chest they go back into ([chest]).
 * Stepped with the toys; what happens comes out as [ToyEvent]s ([ToyHit.STORED] when one goes into the chest).
 */
class GiftPlay {
    val out = ArrayList<GiftBody>()
    var box: GiftBody? = null
        private set
    var lid: GiftBody? = null
        private set
    var opened: GiftBody? = null
        private set
    val chest = Chest()

    /** Whether the box is still glitching into being (not yet to be opened). */
    val making: Boolean get() = (box?.age ?: MAKE) < MAKE

    /** How far the box has come into being, 0 to 1. */
    fun made(): Float = ((box?.age ?: MAKE) / MAKE).coerceIn(0f, 1f)

    val moving: Boolean get() = box != null || lid != null || opened != null || chest.moving || out.any { it.moving }

    /** She makes a box at [x], landing on the floor in front of her. */
    fun make(x: Float, room: PetRoom) {
        val b = GiftBody(null, GiftMeshes.box, BOX_SIZE * room.unit, GiftBody.BOX_REST)
        b.place(x, room.floor - BOX_SIZE * room.unit * 1.4f)
        box = b
    }

    /** The box opened on [item]: its lid flies, the box fades, and the gift jumps out. */
    fun open(item: GiftItem, room: PetRoom, random: Random, events: MutableList<ToyEvent>): GiftBody? {
        val b = box ?: return null
        if (making) return null
        box = null
        val u = room.unit
        lid = GiftBody(null, GiftModel(listOf(GiftMeshes.boxLid)), b.size, b.q).apply {
            place(b.x, b.y)
            vx = (if (random.nextBoolean()) 1f else -1f) * (200f + random.nextFloat() * 200f) * u
            vy = -1300f * u
            w = V3(random.nextDouble(-6.0, 6.0), random.nextDouble(-4.0, 4.0), random.nextDouble(-8.0, 8.0))
        }
        opened = GiftBody(null, GiftModel(listOf(GiftMeshes.boxBody)), b.size, b.q).apply { place(b.x, b.y); q = b.q }
        val g = spawn(item, room)
        g.place(b.x, b.y - b.size * .3f)
        g.vy = -1150f * u
        g.vx = (room.middle - b.x).coerceIn(-1f, 1f) * 120f * u
        g.w = V3(0.0, random.nextDouble(-3.0, 3.0), 0.0)
        add(g, room, events)
        return g
    }

    /** A gift out of the chest's drawer, tossed into the room. */
    fun takeOut(item: GiftItem, room: PetRoom, random: Random, events: MutableList<ToyEvent>): GiftBody {
        val g = spawn(item, room)
        val u = room.unit
        g.place(chest.x, room.floor - chest.h * 1.2f)
        g.vx = -(380f + random.nextFloat() * 240f) * u
        g.vy = -900f * u
        g.w = V3(random.nextDouble(-2.0, 2.0), random.nextDouble(-2.0, 2.0), random.nextDouble(-3.0, 3.0))
        chest.took()
        add(g, room, events)
        return g
    }

    private fun spawn(item: GiftItem, room: PetRoom) = GiftBody(item, GiftMeshes.of(item.kind), GiftMeshes.size(item.kind) * room.unit, GiftBody.restOf(item.kind))

    private fun add(g: GiftBody, room: PetRoom, events: MutableList<ToyEvent>) {
        out += g
        // more than the room holds: the one out longest goes back into the chest
        while (out.size > MAX_OUT) {
            val old = out.filter { !it.held }.maxByOrNull { it.age } ?: break
            out.remove(old)
            chest.took()
            events += ToyEvent(null, ToyHit.STORED, old.x, old.y)
        }
    }

    /** Let go of [g] thrown at ([vx], [vy]): over the chest it goes in (true), else it flies. */
    fun release(g: GiftBody, vx: Float, vy: Float, room: PetRoom, random: Random, events: MutableList<ToyEvent>): Boolean {
        if (chest.over(g.x, g.y, room)) {
            g.held = false
            out.remove(g)
            chest.took()
            events += ToyEvent(null, ToyHit.STORED, g.x, g.y)
            return true
        }
        g.release(vx, vy, room, random)
        return false
    }

    /** The gift (or the box) under a press at ([px], [py]), the one drawn last first. */
    fun at(px: Float, py: Float): GiftBody? = out.lastOrNull { it.hit(px, py) } ?: box?.takeIf { it.hit(px, py) }

    fun step(dt: Float, room: PetRoom, random: Random, events: MutableList<ToyEvent>) {
        box?.step(dt, room, random, events)
        opened?.let { it.step(dt, room, random, events); if (it.age > FADE) opened = null }
        lid?.let { it.step(dt, room, random, events); if (it.age > LID_LIFE) lid = null }
        for (g in out) g.step(dt, room, random, events)
        chest.step(dt, out.any { it.held && chest.over(it.x, it.y, room) })
    }

    companion object {
        const val MAX_OUT = 3

        /** Seconds the box takes to glitch into being; the opened box fades over [FADE]; the lid is gone after [LID_LIFE]. */
        const val MAKE = 1.4f
        const val FADE = .9f
        const val LID_LIFE = 2.2f

        /** The box's size in room units. */
        const val BOX_SIZE = 120f
    }
}
