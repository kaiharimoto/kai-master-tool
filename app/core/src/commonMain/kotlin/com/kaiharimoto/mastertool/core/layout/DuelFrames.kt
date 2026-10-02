package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

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
 * the pile laid out open above the near hand, if any.
 */
object DuelFrames {
    const val Z_MATERIAL = 1f
    const val Z_FIELD = 2f
    const val Z_PILE = 2f
    const val Z_HAND = 4f
    const val Z_STRIP = 8f

    fun of(s: DuelState, l: DuelLayout, viewers: Set<Int>, strip: Pair<Int, PileKind>? = null): List<CardFrame> {
        val out = ArrayList<CardFrame>(s.cards.size)
        fun look(uid: Int): CardLook {
            val c = s.cards.getValue(uid)
            val where = s.placeOf(uid)
            return when {
                viewers.none { DuelSight.sees(s, uid, it) } -> CardLook.BACK
                where is Place.Zone && !c.faceUp -> CardLook.SET
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
                out += CardFrame(m, slot.left - d, slot.top - d, slot.width, slot.height, 0f, CardLook.FACE, z - 0.01f * (k + 1), shown && k < 3)
            }
        }

        // The field.
        val seats = if (l.twoSided) listOf(0, 1) else listOf(l.bottom)
        s.emz.forEachIndexed { i, uid ->
            uid ?: return@forEachIndexed
            val slot = l.zone(Place.Zone(0, ZoneKind.EMZ, i)) ?: return@forEachIndexed
            place(uid, slot, Z_FIELD, rotation = rotationOf(s, uid))
        }
        for (seat in seats) {
            val st = s.seats[seat]
            st.monsters.forEachIndexed { i, uid -> uid ?: return@forEachIndexed; l.zone(Place.Zone(seat, ZoneKind.MONSTER, i))?.let { place(uid, it, Z_FIELD, rotation = rotationOf(s, uid)) } }
            st.spells.forEachIndexed { i, uid -> uid ?: return@forEachIndexed; l.zone(Place.Zone(seat, ZoneKind.SPELL, i))?.let { place(uid, it, Z_FIELD) } }
            st.field?.let { uid -> l.zone(Place.Zone(seat, ZoneKind.FIELD, 0))?.let { place(uid, it, Z_FIELD) } }
            // Piles: every card at its pile, the top one shown.
            for (kind in listOf(PileKind.DECK, PileKind.EXTRA, PileKind.GY, PileKind.BANISHED)) {
                if (strip == seat to kind) continue
                val slot = l.pile(seat, kind) ?: continue
                st.pile(kind).forEachIndexed { i, uid -> place(uid, slot, Z_PILE - i * 0.0001f, shown = i == 0) }
            }
            // The hand, fanned across its band.
            val band = l.pile(seat, PileKind.HAND)
            if (band != null) {
                fan(st.hand.size, band, if (seat == l.bottom) l.card else band.height / DuelLayouter.CARD_RATIO)
                    .forEachIndexed { i, slot -> place(st.hand[i], slot, Z_HAND + i * 0.001f) }
            } else {
                // A folded hand: its cards wait at the seat's bar, out of sight.
                val bar = l.bars[seat]
                if (bar != null) st.hand.forEach { uid -> place(uid, Slot(bar.left, bar.top, l.card * 0.3f, l.card * 0.3f * DuelLayouter.CARD_RATIO), Z_HAND, shown = false) }
            }
        }
        // The open pile, over the near side of the table.
        if (strip != null) {
            val band = stripBand(l)
            val cards = s.seats[strip.first].pile(strip.second)
            // The owner looking through their own deck sees it; everyone else sees what they could anyway.
            fan(cards.size, band, l.card).forEachIndexed { i, slot ->
                val uid = cards[i]
                val own = s.cards[uid]?.owner in viewers
                val lookAs = when {
                    strip.second == PileKind.DECK && own -> CardLook.FACE
                    strip.second == PileKind.EXTRA && own -> CardLook.FACE
                    strip.second == PileKind.BANISHED && own -> if (s.cards[uid]?.faceUp == true) CardLook.FACE else CardLook.SET
                    else -> null
                }
                place(uid, slot, Z_STRIP + i * 0.001f, inStrip = true, lookAs = lookAs)
            }
        }
        return out
    }

    /** The band an open pile is laid out in: over the near side's rows, above its seat bar. */
    fun stripBand(l: DuelLayout): Slot {
        val bar = l.bars.getValue(l.bottom)
        val h = l.cardHeight
        return Slot(bar.left, bar.top - l.gap - h, bar.width, h)
    }

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
