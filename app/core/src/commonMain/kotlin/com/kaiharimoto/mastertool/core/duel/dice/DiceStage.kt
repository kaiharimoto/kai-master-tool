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

    /**
     * Where [seat]'s own die and coin are kept between throws (1.0.96, kai: "by the left side near the extra deck for both
     * players"), in table dp: [die] and [coin] their centres, [size] a die's edge. Beside the Extra Deck on its outer side —
     * your left, their right past the score column — stacked, the coin nearer the middle; where the window leaves no room
     * there (a phone), beside the Extra Deck on the hand's side, in the band the hand leaves at that end.
     */
    data class Home(val seat: Int, val die: Pair<Float, Float>, val coin: Pair<Float, Float>, val size: Float) {
        /** The coin's radius on the table, dp. */
        val coinRadius: Float get() = (DiceSim.COIN_R * size).toFloat()

        /** Whether a press at ([x], [y]) is on the die (a little grown to take a finger). */
        fun onDie(x: Float, y: Float): Boolean = kotlin.math.abs(x - die.first) <= size * 0.75f && kotlin.math.abs(y - die.second) <= size * 0.75f

        fun onCoin(x: Float, y: Float): Boolean {
            val dx = x - coin.first
            val dy = y - coin.second
            val r = coinRadius + size * 0.2f
            return dx * dx + dy * dy <= r * r
        }

        /**
         * Where a carried die ([coin] false) or coin is let go to put it back (1.1.9): a box round its place, drawn as crop
         * marks while it is carried. The die's and the coin's never overlap, so a drop means one of them.
         */
        fun target(coin: Boolean): Slot {
            val (x, y) = if (coin) this.coin else die
            val half = if (coin) coinRadius + size * 0.2f else size * 0.8f
            return Slot(x - half, y - half, 2 * half, 2 * half)
        }

        /** Whether a die or coin carried to ([x], [y]) is let go onto its home. */
        fun over(coin: Boolean, x: Float, y: Float): Boolean = target(coin).contains(x, y)
    }

    private val homes: Map<Int, Home> = (0..1).mapNotNull { seat -> homeFor(seat)?.let { seat to it } }.toMap()

    fun home(seat: Int): Home? = homes[seat]

    private fun homeFor(seat: Int): Home? {
        val a = arenas[seat] ?: return null
        val ed = layout.pile(seat, PileKind.EXTRA) ?: return null
        val size = a.scale
        val coinW = (2 * DiceSim.COIN_R).toFloat() * size
        val pad = layout.gap.coerceAtLeast(6f)
        val far = a.turned
        // Up the column, toward the middle of the table: the coin there, the die toward the player.
        val toward = if (far) 1f else -1f
        val step = size * 0.95f
        if (!far) {
            val bound = layout.inspector?.takeIf { it.right <= ed.left }?.right ?: 0f
            if (ed.left - bound >= coinW + 2 * pad) {
                val x = ed.left - pad - coinW / 2f
                return Home(seat, x to ed.centerY - toward * step, x to ed.centerY + toward * step, size)
            }
        } else {
            val start = maxOf(ed.right, layout.phases.right, layout.score.values.maxOfOrNull { it.right } ?: 0f)
            val bound = layout.log?.takeIf { it.left >= start }?.left ?: layout.width
            if (bound - start >= coinW + 2 * pad) {
                val x = start + pad + coinW / 2f
                return Home(seat, x to ed.centerY - toward * step, x to ed.centerY + toward * step, size)
            }
        }
        // No room beside it: in the hand's band at the Extra Deck's end, side by side.
        val y = if (far) (ed.top - pad - coinW / 2f).coerceAtLeast(coinW / 2f) else (ed.bottom + pad + coinW / 2f).coerceAtMost(layout.height - coinW / 2f)
        val dx = size * 0.95f
        // Kept inside the window: on a phone the Extra Deck stands against its edge.
        val margin = coinW / 2f + pad
        val centre = ed.centerX.coerceIn(dx + margin, (layout.width - dx - margin).coerceAtLeast(dx + margin))
        return Home(seat, centre + dx * (if (far) -1f else 1f) to y, centre - dx * (if (far) -1f else 1f) to y, size)
    }

    /**
     * How deep the table is drawn past [seat]'s field, in its arena's die edges (1.1.9): to the other seat's far edge when
     * both sides are drawn, else to the shared row's — the room a throw's far side ([DiceSim.ACROSS], or [DiceSim.INNER] on a
     * solo table) is folded onto. About 12.4 on a window that draws both fields at one size, so a throw there is drawn
     * where it lies; less where the far side is drawn smaller, or not at all.
     */
    private val drawnAcross: Map<Int, Double> = arenas.mapValues { (seat, a) -> acrossFor(seat, a) }

    private fun acrossFor(seat: Int, a: Arena): Double {
        fun box(of: (DuelSpot) -> Boolean): List<Slot> = layout.spots.filterKeys(of).values.toList()
        val theirs = box { spot ->
            when (spot) {
                is DuelSpot.Zone -> spot.zone.seat != seat && spot.zone.kind != ZoneKind.EMZ
                is DuelSpot.Pile -> spot.seat != seat && spot.kind != PileKind.BANISHED
                else -> false
            }
        }.takeIf { layout.twoSided && it.isNotEmpty() }
        val beyond = theirs ?: box { it == DuelSpot.Chain || (it is DuelSpot.Zone && it.zone.kind == ZoneKind.EMZ) }
        if (beyond.isEmpty()) return DiceSim.INNER
        val far = beyond.flatMap { s -> listOf(s.top, s.bottom).map { y -> toArena(a, s.centerX, y).y } }.min()
        return (-far).coerceAtLeast(1.0)
    }

    /**
     * A table throw's point (its own arena, [reach] the wall it met) where this window draws it (1.1.9): on the thrower's
     * field as it is; past it, its depth folded onto the table drawn there ([drawnAcross]), so it never leaves the table.
     */
    fun shown(seat: Int, p: V3, reach: Double): V3 {
        if (p.y >= 0) return p
        return p.copy(y = p.y * fold(seat, reach))
    }

    /** [shown] undone: a point as drawn back into the throw's own arena. */
    fun unshown(seat: Int, p: V3, reach: Double): V3 {
        if (p.y >= 0) return p
        return p.copy(y = p.y / fold(seat, reach))
    }

    /** Only ever a squeeze: a throw that fits what is drawn (an old one at [DiceSim.INNER], say) is drawn where it lies. */
    private fun fold(seat: Int, reach: Double): Double {
        val drawn = drawnAcross[seat] ?: return 1.0
        return (drawn / reach.coerceAtLeast(1.0)).coerceAtMost(1.0)
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
