package com.kaiharimoto.mastertool.core.duel.dice

import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.layout.Slot

/**
 * Where the opening roll's dice stand on the drawn table (1.0.87), and how they are seen.
 *
 * Each seat's [DiceSim] arena is laid over that seat's own field — its two rows of zones and its piles — at one
 * scale (a die's edge in dp), centred; the far seat's turned half round, as across a real table, so a throw is the
 * same throw drawn on either side. Before a throw the dice rest on the seat's Spell & Trap row, in front of its hand
 * (1.0.95). The table runs [DiceSim.INNER] past the field's far edge, over the middle row, before a wall.
 *
 * The camera ([project]) looks down on the table from above the person's own side: the picture plane is the table,
 * so anything lying on it is drawn exactly where the 2-D table is (a die at rest sits on its zone), and anything
 * above it is drawn larger and leaning away from the eye — a die in the air reads as nearer, and its sides toward
 * the person show, as they would sitting at the table.
 */
class DiceStage(private val layout: DuelLayout) {

    /** One seat's arena on the table: its corner, the scale (dp to a die's edge), and whether it is turned round. */
    data class Arena(val seat: Int, val left: Float, val top: Float, val scale: Float, val turned: Boolean, val rest: List<V3>)

    /** A point of the table drawn: where on screen, and how much larger than on the table it is drawn there. */
    data class Seen(val x: Float, val y: Float, val k: Float)

    /** The eye, in table dp: over the person's side, beyond the bottom edge, well above the table. */
    val eye: V3 = V3(layout.field.centerX.toDouble(), (layout.height * 1.3f).toDouble(), (layout.height * 1.6f).toDouble())

    private val arenas: Map<Int, Arena> = (0..1).mapNotNull { seat -> arenaFor(seat)?.let { seat to it } }.toMap()

    fun arena(seat: Int): Arena? = arenas[seat]

    private fun arenaFor(seat: Int): Arena? {
        val slots = layout.spots.entries.filter { (spot, _) ->
            when (spot) {
                is DuelSpot.Zone -> spot.zone.seat == seat && spot.zone.kind != ZoneKind.EMZ
                is DuelSpot.Pile -> spot.seat == seat && spot.kind != PileKind.BANISHED
                else -> false
            }
        }.map { it.value }
        if (slots.isEmpty()) return null
        val box = Slot(slots.minOf { it.left }, slots.minOf { it.top }, 0f, 0f).let { s ->
            Slot(s.left, s.top, slots.maxOf { it.right } - s.left, slots.maxOf { it.bottom } - s.top)
        }
        val scale = minOf(box.width / DiceSim.ARENA_W.toFloat(), box.height / DiceSim.ARENA_D.toFloat())
        val left = box.left + (box.width - scale * DiceSim.ARENA_W.toFloat()) / 2f
        val top = box.top + (box.height - scale * DiceSim.ARENA_D.toFloat()) / 2f
        val turned = layout.twoSided && seat != layout.bottom
        val base = Arena(seat, left, top, scale, turned, emptyList())
        // On the Spell & Trap row, in front of the hand, in its middle (kai, 1.0.95: "when the duel starts have the dice in
        // front of the hand where the spell and trap zone are"); 1.0.88–1.0.94 kept them at the far end of the hand.
        val middle = layout.zone(Place.Zone(seat, ZoneKind.SPELL, 2))
        val at = if (middle != null) toArena(base, middle.centerX, middle.centerY) else V3(DiceSim.ARENA_W / 2, DiceSim.ARENA_D * 0.75, 0.0)
        val rest = listOf(V3(at.x - 0.8, at.y, 0.5), V3(at.x + 0.8, at.y, 0.5))
        return base.copy(rest = rest)
    }

    /** A point of [seat]'s arena on the table, in dp (z: its height in dp). */
    fun toTable(seat: Int, p: V3): V3 {
        val a = arenas[seat] ?: return p
        return toTable(a, p)
    }

    private fun toTable(a: Arena, p: V3): V3 {
        val s = a.scale.toDouble()
        val x = if (a.turned) DiceSim.ARENA_W - p.x else p.x
        val y = if (a.turned) DiceSim.ARENA_D - p.y else p.y
        return V3(a.left + x * s, a.top + y * s, p.z * s)
    }

    /** A direction in [seat]'s arena, on the table (turned with it). */
    fun dirToTable(seat: Int, d: V3): V3 = if (arenas[seat]?.turned == true) V3(-d.x, -d.y, d.z) else d

    private fun toArena(a: Arena, x: Float, y: Float): V3 {
        val s = a.scale.toDouble()
        val ax = (x - a.left) / s
        val ay = (y - a.top) / s
        return if (a.turned) V3(DiceSim.ARENA_W - ax, DiceSim.ARENA_D - ay, 0.0) else V3(ax, ay, 0.0)
    }

    /** Where on [seat]'s arena, at [height] (die edges), a die is that is drawn under the pointer at ([x], [y]). */
    fun under(seat: Int, x: Float, y: Float, height: Double): V3 {
        val a = arenas[seat] ?: return V3()
        val z = height * a.scale
        // Unproject: on the picture plane at that height, the table point is pulled toward the eye.
        val f = (eye.z - z) / eye.z
        val tx = eye.x + (x - eye.x) * f
        val ty = eye.y + (y - eye.y) * f
        return toArena(a, tx.toFloat(), ty.toFloat()).copy(z = height)
    }

    /** A pointer's velocity on screen (dp a second), as a velocity in [seat]'s arena (die edges a second). */
    fun velocity(seat: Int, vx: Float, vy: Float, height: Double): V3 {
        val a = arenas[seat] ?: return V3()
        val f = (eye.z - height * a.scale) / eye.z / a.scale
        val v = V3(vx * f, vy * f, 0.0)
        return if (a.turned) V3(-v.x, -v.y, 0.0) else v
    }

    /** Where a table point (dp, z its height) is drawn. */
    fun project(p: V3): Seen {
        val k = eye.z / (eye.z - p.z).coerceAtLeast(1.0)
        return Seen((eye.x + (p.x - eye.x) * k).toFloat(), (eye.y + (p.y - eye.y) * k).toFloat(), k.toFloat())
    }

    /** Whether a face at table point [at] with outward normal [n] (table frame) faces the eye. */
    fun faces(at: V3, n: V3): Boolean = (n dot (eye - at)) > 0
}
