package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import kotlin.math.abs

/**
 * The keyboard's focus on the duel table (1.0.87, Command mode: kai, "I can win with just typing too and
 * not a mouse"): a logical grid over the table as [DuelLayout] draws it, so the arrows walk it the way the
 * eye reads it and a test can walk it without a pixel.
 *
 * Rows, top to bottom, from the [viewer]'s seat (the one drawn at the bottom):
 *
 * ```
 *   their hand                                  (when drawn; as many cells as cards)
 *   [odk] [os5][os4][os3][os2][os1] [oex]
 *   [ogy] [om5][om4][om3][om2][om1] [ofz]
 *   [oban]      [e?]  chain  [e?]       [ban]
 *   [fz]  [m1] [m2] [m3] [m4] [m5]   [gy]
 *   [ex]  [s1] [s2] [s3] [s4] [s5]   [dk]
 *   your hand                                   (as many cells as cards)
 * ```
 *
 * Each cell has a column, 0 at the grid's left edge and 6 at its right, a hand card's where the fan
 * puts it, so ↑ and ↓ land on what stands above or below. An empty zone is a cell like any other —
 * a card is placed onto it — and nothing wraps: at an edge the focus stays. One-sided (a solo table,
 * or the near side alone) is the near half, the Extra Monster Zones' row on top.
 *
 * The coordinates ([label]) are the table notation's (1.0.87): `h1…` the hand left to right, `m1–m5`,
 * `s1–s5`, `fz`, the piles `gy`, `ban`, `ex`, `dk`, an open pile's cards `gy1…` from the top; the other
 * seat's with an `o` in front; the Extra Monster Zones `e1`/`e2` by their absolute index, nobody's.
 */
object DuelFocus {

    /** Somewhere the focus can stand. */
    sealed interface Slot {
        /** A zone, empty or not. The Extra Monster Zones are keyed by seat 0, as [DuelLayout] keys them. */
        data class Zone(val place: Place.Zone) : Slot

        /** A pile shut, standing for its top card: GY, Banished, Extra Deck or Deck (never the hand). */
        data class Pile(val seat: Int, val kind: PileKind) : Slot

        /** The [index]-th card of a hand, left to right. */
        data class HandCard(val seat: Int, val index: Int) : Slot

        /** The [index]-th card (0 the top) of a pile laid open over the field. */
        data class PileCard(val seat: Int, val kind: PileKind, val index: Int) : Slot
    }

    enum class Dir { UP, DOWN, LEFT, RIGHT }

    /**
     * The table's shape as drawn: both sides or one, whether the far hand has a band ([DuelLayout.farHandFolded]),
     * the far side's scale, and how many cards an open pile lays in a row ([DuelFrames.StripGrid.perRow]).
     */
    data class Shape(
        val twoSided: Boolean = true,
        val farHand: Boolean = true,
        val farScale: Float = 1f,
        val stripPerRow: Int = 1,
    ) {
        companion object {
            fun of(l: DuelLayout, stripPerRow: Int = 1) = Shape(l.twoSided, !l.farHandFolded, l.farScale, stripPerRow.coerceAtLeast(1))
        }
    }

    /**
     * Whose eyes the table is drawn through, and the duel's secret that keys its veils. A hand none of the
     * [viewers] may see is shown — drawn, walked and numbered `oh1…` — in [DuelView]'s order for it, sorted
     * by veil, never in its true order: which card came in last is not theirs to see (the 1.0.85 rule,
     * kept by the focus in 1.0.87). Empty [viewers] see everything.
     */
    data class Eyes(val viewers: Set<Int> = emptySet(), val secret: Long = 0L) {
        /** The uids of [seat]'s hand, left to right as these eyes are shown it. */
        fun hand(s: DuelState, seat: Int): List<Int> {
            val h = s.seats[seat].hand
            if (viewers.isEmpty() || seat in viewers) return h
            return h.sortedBy { u -> if (viewers.any { DuelSight.sees(s, u, it) }) u else DuelView.veil(secret, u, s.epoch[u] ?: 0) }
        }

        companion object {
            val ALL = Eyes()
        }
    }

    /** One cell of the grid: a [slot] in [row] (0 the top row drawn), at [column] (0..6, the zones' columns). */
    data class Cell(val slot: Slot, val row: Int, val column: Float)

    /** A zone's slot, the Extra Monster Zones keyed by seat 0. */
    fun zone(seat: Int, kind: ZoneKind, index: Int = 0): Slot.Zone =
        Slot.Zone(Place.Zone(if (kind == ZoneKind.EMZ) 0 else seat, kind, index))

    /** Every row of the grid, top to bottom, its cells left to right; a hand with no cards has no row. */
    fun rows(s: DuelState, viewer: Int, shape: Shape): List<List<Cell>> {
        val near = viewer
        val far = 1 - viewer
        val two = shape.twoSided && !s.solo
        val raw = mutableListOf<List<Pair<Slot, Float>>>()
        if (two) {
            if (shape.farHand) raw += hand(s, far, FAR_HAND * shape.farScale, shape.farScale)
            raw += listOf(Slot.Pile(far, PileKind.DECK) to 0f) +
                (0 until DuelState.ZONES).map { i -> zone(far, ZoneKind.SPELL, i) to (5 - i).toFloat() } +
                listOf(Slot.Pile(far, PileKind.EXTRA) to 6f)
            raw += listOf(Slot.Pile(far, PileKind.GY) to 0f) +
                (0 until DuelState.ZONES).map { i -> zone(far, ZoneKind.MONSTER, i) to (5 - i).toFloat() } +
                listOf(zone(far, ZoneKind.FIELD) to 6f)
        }
        // The shared row: the Extra Monster Zones either side of the chain well, the Banished piles at its ends.
        // The left Extra Monster Zone as drawn is index 0 for seat 0 at the bottom, index 1 for seat 1.
        val emzLeft = if (viewer == 0) 0 else 1
        raw += listOfNotNull(
            if (two) Slot.Pile(far, PileKind.BANISHED) to 0f else null,
            zone(0, ZoneKind.EMZ, emzLeft) to 2f,
            zone(0, ZoneKind.EMZ, 1 - emzLeft) to 4f,
            Slot.Pile(near, PileKind.BANISHED) to 6f,
        )
        raw += listOf(zone(near, ZoneKind.FIELD) to 0f) +
            (0 until DuelState.ZONES).map { i -> zone(near, ZoneKind.MONSTER, i) to (1 + i).toFloat() } +
            listOf(Slot.Pile(near, PileKind.GY) to 6f)
        raw += listOf(Slot.Pile(near, PileKind.EXTRA) to 0f) +
            (0 until DuelState.ZONES).map { i -> zone(near, ZoneKind.SPELL, i) to (1 + i).toFloat() } +
            listOf(Slot.Pile(near, PileKind.DECK) to 6f)
        raw += hand(s, near, 1f, 1f)
        return raw.filter { it.isNotEmpty() }.mapIndexed { r, row -> row.sortedBy { it.second }.map { (slot, col) -> Cell(slot, r, col) } }
    }

    /** Every cell, row by row: what the coordinate labels are written on. */
    fun cells(s: DuelState, viewer: Int, shape: Shape): List<Cell> = rows(s, viewer, shape).flatten()

    /**
     * A hand's cards at their columns, as [DuelFrames.fan] lays them: side by side when they fit, else
     * overlapping evenly, centred on the middle column. [width] is a card's width in columns' pitch, and
     * [span] the band's width as a share of the grid's.
     */
    private fun hand(s: DuelState, seat: Int, width: Float, span: Float): List<Pair<Slot, Float>> {
        val n = s.seats[seat].hand.size
        if (n == 0) return emptyList()
        val w = CARD_IN_PITCH * width
        val band = GRID_IN_PITCH * span
        val natural = n * w + (n - 1) * w * 0.06f
        val step = if (n == 1 || natural <= band) w * 1.06f else (band - w) / (n - 1)
        return List(n) { i -> Slot.HandCard(seat, i) to 3f + (i - (n - 1) / 2f) * step }
    }

    /** Where the focus starts: the viewer's first card in hand, else their first Monster Zone. */
    fun home(s: DuelState, viewer: Int): Slot =
        if (s.seats[viewer].hand.isNotEmpty()) Slot.HandCard(viewer, 0) else zone(viewer, ZoneKind.MONSTER, 0)

    /**
     * The slot one step from [from] in [dir], or [from] itself at an edge. Left and right go along the row;
     * up and down to the next row, onto the cell whose column is nearest (a tie goes to the one nearer the
     * middle). With no focus yet, [home]. A card in an open pile steps through the pile's rows instead.
     */
    fun step(from: Slot?, dir: Dir, s: DuelState, viewer: Int, shape: Shape): Slot {
        if (from == null) return home(s, viewer)
        if (from is Slot.PileCard) return stepStrip(from, dir, s, shape.stripPerRow)
        val rows = rows(s, viewer, shape)
        val at = settle(from, s, viewer, shape)
        val cell = rows.flatten().firstOrNull { it.slot == at } ?: return home(s, viewer)
        val row = rows[cell.row]
        return when (dir) {
            Dir.LEFT -> row.getOrNull(row.indexOf(cell) - 1)?.slot ?: cell.slot
            Dir.RIGHT -> row.getOrNull(row.indexOf(cell) + 1)?.slot ?: cell.slot
            Dir.UP, Dir.DOWN -> {
                val next = rows.getOrNull(cell.row + if (dir == Dir.UP) -1 else 1) ?: return cell.slot
                nearest(next, cell.column).slot
            }
        }
    }

    private fun nearest(row: List<Cell>, column: Float): Cell =
        row.minWith(compareBy<Cell>({ abs(it.column - column) }, { abs(it.column - 3f) }))

    /** The first cell of [from]'s row (an open pile's: of its row in the pile). */
    fun rowStart(from: Slot?, s: DuelState, viewer: Int, shape: Shape): Slot = rowEnd(from, s, viewer, shape, first = true)

    /** The last cell of [from]'s row. */
    fun rowEnd(from: Slot?, s: DuelState, viewer: Int, shape: Shape): Slot = rowEnd(from, s, viewer, shape, first = false)

    private fun rowEnd(from: Slot?, s: DuelState, viewer: Int, shape: Shape, first: Boolean): Slot {
        if (from == null) return home(s, viewer)
        if (from is Slot.PileCard) {
            val n = s.seats[from.seat].pile(from.kind).size
            if (n == 0) return from
            val per = shape.stripPerRow.coerceAtLeast(1)
            val start = (from.index.coerceIn(0, n - 1) / per) * per
            return from.copy(index = if (first) start else minOf(start + per, n) - 1)
        }
        val at = settle(from, s, viewer, shape)
        val rows = rows(s, viewer, shape)
        val cell = rows.flatten().firstOrNull { it.slot == at } ?: return home(s, viewer)
        val row = rows[cell.row]
        return (if (first) row.first() else row.last()).slot
    }

    /** Through an open pile's rows of [perRow]: along a row, or a row up or down (onto its last card when shorter). */
    private fun stepStrip(from: Slot.PileCard, dir: Dir, s: DuelState, perRow: Int): Slot {
        val n = s.seats[from.seat].pile(from.kind).size
        if (n == 0) return from.copy(index = 0)
        val per = perRow.coerceAtLeast(1)
        val i = from.index.coerceIn(0, n - 1)
        val col = i % per
        val rows = (n + per - 1) / per
        val row = i / per
        val to = when (dir) {
            Dir.LEFT -> if (col > 0) i - 1 else i
            Dir.RIGHT -> if (col < per - 1 && i + 1 < n) i + 1 else i
            Dir.UP -> if (row > 0) i - per else i
            Dir.DOWN -> if (row < rows - 1) minOf(i + per, n - 1) else i
        }
        return from.copy(index = to)
    }

    /**
     * [slot] made good on the table as it is now: a hand card past the hand's end is its last card, a hand
     * gone empty or a far-side place on a one-sided table is [home], an open pile's card is clamped to it.
     */
    fun settle(slot: Slot, s: DuelState, viewer: Int, shape: Shape): Slot = when (slot) {
        is Slot.HandCard -> {
            val n = s.seats[slot.seat].hand.size
            val drawn = slot.seat == viewer || (shape.twoSided && !s.solo && shape.farHand)
            if (n == 0 || !drawn) home(s, viewer) else slot.copy(index = slot.index.coerceIn(0, n - 1))
        }
        is Slot.PileCard -> {
            val n = s.seats[slot.seat].pile(slot.kind).size
            slot.copy(index = slot.index.coerceIn(0, (n - 1).coerceAtLeast(0)))
        }
        else -> if (cells(s, viewer, shape).any { it.slot == slot }) slot else home(s, viewer)
    }

    /**
     * The card at [slot]: a zone's, a pile's top card, a hand's or an open pile's; null for an empty place.
     * A hand's [Slot.HandCard.index] counts in the order [eyes] are shown it ([Eyes.hand]).
     */
    fun uidAt(s: DuelState, slot: Slot, eyes: Eyes = Eyes.ALL): Int? = when (slot) {
        is Slot.Zone -> s.at(slot.place)
        is Slot.Pile -> s.seats[slot.seat].pile(slot.kind).firstOrNull()
        is Slot.HandCard -> eyes.hand(s, slot.seat).getOrNull(slot.index)
        is Slot.PileCard -> s.seats[slot.seat].pile(slot.kind).getOrNull(slot.index)
    }

    /**
     * Where [uid] stands now, so the focus goes with a card it was on: a zone, a hand card, the card in
     * [strip] (the pile open), a shut pile, or — a material — the card it is under. Null once it has left.
     */
    fun slotOf(s: DuelState, uid: Int, strip: Pair<Int, PileKind>? = null, eyes: Eyes = Eyes.ALL): Slot? = when (val p = s.placeOf(uid)) {
        is Place.Zone -> zone(p.seat, p.kind, p.index)
        is Place.Pile -> when {
            p.kind == PileKind.HAND -> Slot.HandCard(p.seat, eyes.hand(s, p.seat).indexOf(uid).coerceAtLeast(0))
            strip == p.seat to p.kind -> Slot.PileCard(p.seat, p.kind, p.at ?: 0)
            else -> Slot.Pile(p.seat, p.kind)
        }
        is Place.Under -> slotOf(s, p.host, strip, eyes)
        else -> null
    }

    /**
     * The focus after the table changed: on the card it was on ([uid]), wherever that card went; else the
     * same place, made good ([settle]).
     */
    fun follow(focus: Slot?, uid: Int?, s: DuelState, viewer: Int, shape: Shape, strip: Pair<Int, PileKind>? = null, eyes: Eyes = Eyes.ALL): Slot? {
        focus ?: return null
        val moved = uid?.let { slotOf(s, it, strip, eyes) }
        // A card gone into a pile not drawn (a far hand folded away) leaves the focus where it was.
        if (moved != null && (moved is Slot.PileCard || settle(moved, s, viewer, shape) == moved)) return moved
        return settle(focus, s, viewer, shape)
    }

    /** The coordinate of [slot] from [viewer]'s seat: `m3`, `os2`, `e1`, `h4`, `ogy`, `gy3`. */
    fun label(slot: Slot, viewer: Int): String {
        fun o(seat: Int) = if (seat == viewer) "" else "o"
        return when (slot) {
            is Slot.Zone -> {
                val z = slot.place
                when (z.kind) {
                    ZoneKind.EMZ -> "e${z.index + 1}"
                    ZoneKind.MONSTER -> "${o(z.seat)}m${z.index + 1}"
                    ZoneKind.SPELL -> "${o(z.seat)}s${z.index + 1}"
                    ZoneKind.FIELD -> "${o(z.seat)}fz"
                }
            }
            is Slot.Pile -> o(slot.seat) + pileWord(slot.kind)
            is Slot.HandCard -> "${o(slot.seat)}h${slot.index + 1}"
            is Slot.PileCard -> "${o(slot.seat)}${pileWord(slot.kind)}${slot.index + 1}"
        }
    }

    /** A pile's short name in the notation. */
    fun pileWord(kind: PileKind): String = when (kind) {
        PileKind.GY -> "gy"
        PileKind.BANISHED -> "ban"
        PileKind.EXTRA -> "ex"
        PileKind.DECK -> "dk"
        PileKind.HAND -> "h"
    }

    /** A card's width in the zones' pitch (card and lane): the lane is about a tenth of a card. */
    private const val CARD_IN_PITCH = 1f / 1.1f
    /** The grid's width (seven cards, six lanes) in the zones' pitch. */
    private const val GRID_IN_PITCH = 7.6f / 1.1f
    /** The far hand's cards, as a share of a near card (`DuelLayouter`'s far hand band). */
    private const val FAR_HAND = 0.62f
}
