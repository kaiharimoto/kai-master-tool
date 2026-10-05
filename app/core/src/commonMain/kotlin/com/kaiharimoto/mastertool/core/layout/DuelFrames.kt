package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import kotlin.math.abs

/** How a card is drawn: its face, its face dimmed and marked as set (its controller's own set card), or its back. */
enum class CardLook { FACE, SET, BACK }

/**
 * Where one card is drawn this frame: the box of the card upright ([x], [y] its top-left, [w] × [h]),
 * turned [rotation] degrees about its centre (90 for Defense Position), at depth [z]. A card inside a
 * pile is still placed — at the pile, [shown] false — so that leaving the pile it glides out of it
 * rather than appearing from nowhere.
 */
data class CardFrame(
    val uid: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotation: Float,
    val look: CardLook,
    val z: Float,
    val shown: Boolean = true,
    /** In an open pile, drawn over the table. */
    val inStrip: Boolean = false,
) {
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f

    /** Whether a point is on the card as drawn, turned or not. */
    fun contains(px: Float, py: Float): Boolean {
        val turned = rotation % 180f != 0f
        val hw = (if (turned) h else w) / 2f
        val hh = (if (turned) w else h) / 2f
        return px in centerX - hw..centerX + hw && py in centerY - hh..centerY + hh
    }
}

/**
 * Every card's frame for a table, a layout and the seats whose eyes it is drawn through — pure, so the
 * page, a replay scrubbed backwards and the studio all draw the same table from the same state. Both
 * seats is the hot-seat tester's view: both hands face-up, and still never a deck's order. [strip] is
 * the pile laid out open over the field, if any ([stripRow] the first row of it in view, when it is
 * too long to show whole). [facing] turns the far seat's cards round to face their owner, as across a
 * real table (1.0.78); the cards in an open pile and in the hand still read the right way up.
 */
object DuelFrames {
    const val Z_MATERIAL = 1f
    const val Z_FIELD = 2f
    const val Z_PILE = 2f
    /** Every card of a pile but its top: hidden, never drawn, under the top card. */
    const val Z_PILE_HIDDEN = Z_PILE - 0.0001f
    /**
     * The chain well's words (1.1.9, kai: "the chain link box text is showing over some windows that its not supposed
     * to"): the table's own layer — over the field's cards, a Defense Position card's overhang among them — and under the
     * hands, an open pile ([Z_STRIP]) and every window over the table. At 20 it stood above an open pile's rows.
     */
    const val Z_CHAIN = 3f
    const val Z_HAND = 4f
    const val Z_STRIP = 8f

    fun of(
        s: DuelState,
        l: DuelLayout,
        viewers: Set<Int>,
        strip: Pair<Int, PileKind>? = null,
        facing: Boolean = false,
        stripRow: Int = 0,
        /** The duel's secret (its seed): a hand the [viewers] cannot see is drawn in its veils' order (1.0.87). */
        secret: Long = 0L,
        /** The seat the notation counts from (1.0.87): every other seat's hand drawn in its `oh1…` order ([DuelFocus.Eyes.viewer]). */
        viewer: Int? = null,
        /** The near hand's card the pointer or the keys are on (1.0.94): it rises whole and the hand riffles round it. */
        riffle: Int? = null,
    ): List<CardFrame> {
        val out = ArrayList<CardFrame>(s.cards.size)
        fun look(uid: Int): CardLook {
            val c = s.cards.getValue(uid)
            val where = s.placeOf(uid)
            return when {
                viewers.none { DuelSight.sees(s, uid, it) } -> CardLook.BACK
                where is Place.Zone && !c.faceUp -> CardLook.SET
                // The Deck is a pile of backs, even with a known card put on top of it (kai: "it places face up").
                where is Place.Pile && where.kind == PileKind.DECK && !c.faceUp -> CardLook.BACK
                // A face-down Extra Deck is a pile of backs on the table; its owner reads it opened.
                where is Place.Pile && where.kind == PileKind.EXTRA && !c.faceUp -> CardLook.BACK
                where is Place.Pile && where.kind == PileKind.BANISHED && !c.faceUp -> CardLook.SET
                else -> CardLook.FACE
            }
        }
        fun place(uid: Int, slot: Slot, z: Float, shown: Boolean = true, rotation: Float = 0f, inStrip: Boolean = false, lookAs: CardLook? = null) {
            out += CardFrame(uid, slot.left, slot.top, slot.width, slot.height, rotation, lookAs ?: look(uid), z, shown, inStrip)
            // Materials peek out from under their card, the first three of them.
            s.cards[uid]?.under?.forEachIndexed { k, m ->
                val d = slot.width * 0.07f * (k + 1)
                out += CardFrame(m, slot.left - d, slot.top - d, slot.width, slot.height, rotation - rotation % 180f, CardLook.FACE, z - 0.01f * (k + 1), shown && k < 3)
            }
        }

        // The field.
        val seats = if (l.twoSided) listOf(0, 1) else listOf(l.bottom)
        // Turned to face its controller: the far side's cards, when the table is set to.
        fun turn(seat: Int) = if (facing && l.twoSided && seat != l.bottom) 180f else 0f
        s.emz.forEachIndexed { i, uid ->
            uid ?: return@forEachIndexed
            val slot = l.zone(Place.Zone(0, ZoneKind.EMZ, i)) ?: return@forEachIndexed
            place(uid, slot, Z_FIELD, rotation = rotationOf(s, uid) + turn(s.cards.getValue(uid).controller))
        }
        for (seat in seats) {
            val st = s.seats[seat]
            val r = turn(seat)
            st.monsters.forEachIndexed { i, uid -> uid ?: return@forEachIndexed; l.zone(Place.Zone(seat, ZoneKind.MONSTER, i))?.let { place(uid, it, Z_FIELD, rotation = rotationOf(s, uid) + r) } }
            st.spells.forEachIndexed { i, uid -> uid ?: return@forEachIndexed; l.zone(Place.Zone(seat, ZoneKind.SPELL, i))?.let { place(uid, it, Z_FIELD, rotation = r) } }
            st.field?.let { uid -> l.zone(Place.Zone(seat, ZoneKind.FIELD, 0))?.let { place(uid, it, Z_FIELD, rotation = r) } }
            // Piles: every card at its pile, the top one shown.
            for (kind in listOf(PileKind.DECK, PileKind.EXTRA, PileKind.GY, PileKind.BANISHED)) {
                if (strip == seat to kind) continue
                val slot = l.pile(seat, kind) ?: continue
                // The top card shown at [Z_PILE]; the rest are never drawn, so they share one depth under it (1.0.92) —
                // a depth by their index changed on every draw and shuffle, and every card of the pile drew itself again.
                st.pile(kind).forEachIndexed { i, uid -> place(uid, slot, if (i == 0) Z_PILE else Z_PILE_HIDDEN, shown = i == 0, rotation = r) }
            }
            // The hand, fanned across its band.
            val band = l.pile(seat, PileKind.HAND)
            if (band != null) {
                // Another seat's hidden hand in no order of its own, as DuelView sends it (1.0.87, the focus's `oh1…`).
                val shown = DuelFocus.Eyes(viewers, secret, viewer).hand(s, seat)
                // Both hands held (1.0.94; the far one 1.0.95): bigger cards, a little over one another, riffling round the
                // one in hand — the far hand turned round, its cards dropping toward the field from the window's top edge.
                val near = seat == l.bottom
                val at = riffle?.let { shown.indexOf(it) }?.takeIf { it >= 0 }
                held(shown.size, band, if (near) l.handCard else l.farHandCard, at, fromTop = !near)
                    .forEachIndexed { i, (slot, z) -> place(shown[i], slot, z, rotation = r) }
            } else {
                // A folded hand: its cards wait by the seat's score, out of sight.
                val at = l.score[seat] ?: l.turn
                st.hand.forEach { uid -> place(uid, Slot(at.left, at.top, l.card * 0.3f, l.card * 0.3f * DuelLayouter.CARD_RATIO), Z_HAND, shown = false) }
            }
        }
        // The open pile, over the field.
        if (strip != null) {
            val cards = s.seats[strip.first].pile(strip.second)
            val grid = stripGrid(cards.size, l)
            val first = stripRow.coerceIn(0, (grid.rows - grid.visibleRows).coerceAtLeast(0)) * grid.perRow
            val inView = first until first + grid.visibleRows * grid.perRow
            // The owner looking through their own deck sees it; everyone else sees what they could anyway.
            grid.cells.forEachIndexed { i, cell ->
                val uid = cards[i]
                val shown = i in inView
                // Rows scrolled out of view wait at the band's top or bottom edge, hidden.
                val slot = if (shown) cell.copy(top = cell.top - (first / grid.perRow) * grid.rowStep)
                else cell.copy(top = if (i < first) grid.area.top else grid.area.bottom - cell.height)
                val own = s.cards[uid]?.owner in viewers
                val lookAs = when {
                    strip.second == PileKind.DECK && own -> CardLook.FACE
                    strip.second == PileKind.EXTRA && own -> CardLook.FACE
                    strip.second == PileKind.BANISHED && own -> if (s.cards[uid]?.faceUp == true) CardLook.FACE else CardLook.SET
                    else -> null
                }
                place(uid, slot, Z_STRIP + i * 0.001f, shown = shown, inStrip = true, lookAs = lookAs)
            }
        }
        return out
    }

    /**
     * An open pile laid out in rows over the field (1.0.78, kai: "at least 80 % of the card showing …
     * multiple rows … but not too big"). [cells] are in pile order, top of the pile first, the rows
     * standing on the field's bottom edge and growing upward; [area] is what the rows cover (the
     * strip's ground), at most the room from the top of the table to the field's bottom.
     */
    data class StripGrid(
        val card: Float,
        val perRow: Int,
        val rows: Int,
        /** Rows that fit; when fewer than [rows], the strip scrolls a row at a time. */
        val visibleRows: Int,
        val rowStep: Float,
        val area: Slot,
        val cells: List<Slot>,
    ) {
        val scrolls: Boolean get() = visibleRows < rows
    }

    /** The smallest a card in an open pile gets before the pile scrolls instead. */
    const val STRIP_MIN = 44f
    /** How much of each card in an open pile shows, at least: the step to the next is this much of a card. */
    const val STRIP_SHOWN = 0.8f
    /** The strip's head: the pile's name and its buttons. */
    const val STRIP_HEAD = 24f

    fun stripGrid(n: Int, l: DuelLayout): StripGrid {
        val width = l.field.width
        val bottom = l.field.bottom
        // Room above: the whole table to its top margin, less the strip's head and its frame.
        val room = bottom - STRIP_HEAD - l.gap * 2 - 8f
        val count = n.coerceAtLeast(1)
        fun plan(w: Float): StripGrid {
            val h = w * DuelLayouter.CARD_RATIO
            val rowGap = (w * 0.08f).coerceAtLeast(3f)
            val capacity = ((width - w) / (w * STRIP_SHOWN)).toInt() + 1
            val rows = (count + capacity - 1) / capacity
            val perRow = (count + rows - 1) / rows
            val natural = w * 1.06f
            val step = if (perRow <= 1) natural else minOf(natural, (width - w) / (perRow - 1))
            val rowStep = h + rowGap
            val visible = ((room + rowGap) / rowStep).toInt().coerceIn(1, rows)
            val areaH = visible * rowStep - rowGap
            val area = Slot(l.field.left, bottom - areaH, width, areaH)
            val cells = List(n) { i ->
                val row = i / perRow
                val inRow = if (row == rows - 1) n - row * perRow else perRow
                val used = w + step * (inRow - 1)
                val left = l.field.left + (width - used) / 2f + (i % perRow) * step
                Slot(left, area.top + row * rowStep, w, h)
            }
            return StripGrid(w, perRow, rows, visible, rowStep, area, cells)
        }
        var w = minOf(l.card, maxOf(56f, l.card * STRIP_SHOWN))
        while (true) {
            val g = plan(w)
            if (!g.scrolls || w <= STRIP_MIN) return g
            w = maxOf(STRIP_MIN, w - 2f)
        }
    }

    /** What an open pile of [n] cards covers: the ground under its rows (its head stands above it). */
    fun stripBand(l: DuelLayout, n: Int): Slot = stripGrid(n, l).area

    /**
     * [n] cards of width [w] across [band]: side by side with a small gap when they fit, overlapping
     * evenly when they do not, centred either way.
     */
    fun fan(n: Int, band: Slot, w: Float): List<Slot> {
        if (n <= 0) return emptyList()
        val h = w * DuelLayouter.CARD_RATIO
        val gap = w * 0.06f
        val natural = n * w + (n - 1) * gap
        val step = if (natural <= band.width || n == 1) w + gap else (band.width - w) / (n - 1)
        val used = if (n == 1) w else w + step * (n - 1)
        val left = band.left + (band.width - used) / 2f
        val top = band.top + (band.height - h) / 2f
        return List(n) { i -> Slot(left + i * step, top, w, h) }
    }

    /**
     * The near hand as it is held (1.0.94, kai: "slightly overlapping each other and have them riffle through them as the
     * player holds their cursor over the cards or with their keyboard"): [n] cards of width [w] across [band], each over the
     * one before by [OVERLAP] of a card (more when the band is short of room), in its band's top where the window's edge cuts
     * a fifth off ([fromTop]: the far hand, cut at the top and dropping down). The card [at] — under the pointer or the
     * keys — rises by that fifth, whole, in front of the rest; its
     * neighbours rise a little after it and the hand parts round it, so moving along the hand riffles through it.
     */
    fun held(n: Int, band: Slot, w: Float, at: Int? = null, fromTop: Boolean = false): List<Pair<Slot, Float>> {
        if (n <= 0) return emptyList()
        val h = w * DuelLayouter.CARD_RATIO
        val natural = w * (1f - OVERLAP)
        val step = if (n == 1) 0f else minOf(natural, (band.width - w) / (n - 1)).coerceAtLeast(w * 0.12f)
        val used = w + step * (n - 1)
        val left = band.left + (band.width - used) / 2f
        val cover = (w - step).coerceAtLeast(0f)
        // Toward the field: up from the bottom edge, or down from the top edge for the far hand.
        val rise = h * DuelLayouter.HAND_CUT * (if (fromTop) -1f else 1f)
        return List(n) { i ->
            var x = left + i * step
            var y = band.top
            var z = Z_HAND + i * 0.001f
            if (at != null) {
                val d = i - at
                when {
                    d == 0 -> { y -= rise; z = Z_HAND + 0.5f }
                    // The neighbours part to frame it and lift a little, as fingers riffling a hand do.
                    else -> {
                        x += (if (d < 0) -1f else 1f) * cover * PART / abs(d).coerceAtMost(3)
                        if (abs(d) == 1) y -= rise * 0.3f
                    }
                }
            }
            Slot(x, y, w, h) to z
        }
    }

    /** How much of a held card the next one covers, and how far the hand parts round the card in hand. */
    const val OVERLAP = 0.22f
    const val PART = 0.6f

    /** The hand position a card dropped at [x] would take: before the card whose middle is right of it. */
    fun handIndex(frames: List<CardFrame>, hand: List<Int>, x: Float): Int {
        val mids = hand.map { uid -> frames.firstOrNull { it.uid == uid }?.centerX ?: Float.MAX_VALUE }
        val i = mids.indexOfFirst { x < it }
        return if (i < 0) hand.size else i
    }

    /** The topmost shown card under a point. */
    fun hit(frames: List<CardFrame>, x: Float, y: Float): CardFrame? =
        frames.filter { it.shown && it.contains(x, y) }.maxByOrNull { it.z }

    private fun rotationOf(s: DuelState, uid: Int): Float = if (s.cards.getValue(uid).defense) 90f else 0f
}
