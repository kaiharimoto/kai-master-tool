package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind

/** Somewhere a card or a control sits on the duel table. */
sealed interface DuelSpot {
    /** A zone; the Extra Monster Zones are keyed by seat 0 (they are shared). */
    data class Zone(val zone: Place.Zone) : DuelSpot
    data class Pile(val seat: Int, val kind: PileKind) : DuelSpot
    data class Hand(val seat: Int) : DuelSpot
    data object Chain : DuelSpot
}

/**
 * Where everything on the duel table sits, for a window of [width] × [height] (dp). The single free
 * variable is the card's width; everything else is a fraction of it or a fixed band, so the board
 * reads the same at any size — never crammed, never spread thin.
 *
 * ```
 *               (their hand — small, backs)
 *   [Deck] [S5][S4][S3][S2][S1] [Extra]   their name, LP
 *   [GY]   [M5][M4][M3][M2][M1] [Field]   turn
 *   [Ban]       [EMZ][chain][EMZ]     [Ban]    phases
 *   [Field][M1][M2][M3][M4][M5]  [GY]          …
 *   [Extra][S1][S2][S3][S4][S5]  [Deck]   your LP, name
 *               — your hand, full size —
 * ```
 *
 * (1.0.78, kai: the seat bars cost two rows of height for what the piles already say.) Names, life
 * points and the turn stand in the score column beside the field, with the phases between them.
 *
 * The far side is the near side turned round, as across a real table; the one-player table is the
 * near side alone, with the Extra Monster Zones and the chain well above it where they always are.
 * The rules that keep it coherent:
 *
 * - The card is `min(by width, by height, CAP)`. Past the cap a bigger window grows the rails and the
 *   margins, never the space between zones.
 * - The lane between zones is a tenth of a card, 4 to 14 dp.
 * - Spare width goes to the inspector (left), then the log (right), then the margins. A window too
 *   narrow for both folds the log into the inspector; too narrow for that, both become drawers.
 * - A short window shrinks the far side to three quarters before the near side shrinks below a
 *   readable card, and then folds the far hand away (a count by their name).
 */
data class DuelLayout(
    val width: Float,
    val height: Float,
    val card: Float,
    val cardHeight: Float,
    val gap: Float,
    /** The far side's cards are drawn at this fraction of [card]. */
    val farScale: Float,
    val spots: Map<DuelSpot, Slot>,
    /** Each seat's name and life points, at its own end of the score column. */
    val score: Map<Int, Slot>,
    /** The turn, under the far seat's score. */
    val turn: Slot,
    /** The six phases and End turn, in the score column between the two seats. */
    val phases: Slot,
    /** The grid of zones (both sides), without hands and bars. */
    val field: Slot,
    val inspector: Slot?,
    val log: Slot?,
    /** The log is a tab of the inspector, the window being too narrow for both. */
    val logInInspector: Boolean,
    /** Neither rail fits; both are drawers over the table. */
    val drawers: Boolean,
    /** The far hand is folded into a count on its seat bar. */
    val farHandFolded: Boolean,
    val bottom: Int,
    val twoSided: Boolean,
    val fits: Boolean,
) {
    operator fun get(spot: DuelSpot): Slot? = spots[spot]

    fun zone(z: Place.Zone): Slot? = spots[DuelSpot.Zone(if (z.kind == ZoneKind.EMZ) z.copy(seat = 0) else z)]

    fun pile(seat: Int, kind: PileKind): Slot? = if (kind == PileKind.HAND) spots[DuelSpot.Hand(seat)] else spots[DuelSpot.Pile(seat, kind)]

    /** The card size a seat's cards are drawn at. */
    fun cardFor(seat: Int): Float = if (twoSided && seat != bottom) card * farScale else card

    /** The spot under a point — the slot itself first, then the half-lane around it, so no drop falls between. */
    fun spotAt(x: Float, y: Float): DuelSpot? {
        spots.entries.firstOrNull { it.value.contains(x, y) }?.let { return it.key }
        return spots.entries.firstOrNull { it.value.inflated(gap / 2f).contains(x, y) }?.key
    }

    /** The seat drawn at the bottom, full size, and the one across from it. */
    val near: Int get() = bottom
    val far: Int get() = 1 - bottom
}

object DuelLayouter {
    const val CARD_RATIO = 86f / 59f
    const val MIN_CARD = 34f
    const val CAP_DESK = 132f
    const val CAP_TABLET = 112f
    const val CAP_PHONE = 96f
    const val INSPECTOR_MIN = 260f
    const val INSPECTOR_MAX = 380f
    const val LOG_MIN = 260f
    const val LOG_MAX = 360f
    const val FAR_SHRUNK = 0.75f

    /** The far hand's band, as a fraction of a card's height: their cards are held, not laid out. */
    private const val FAR_HAND = 0.62f

    fun gapFor(card: Float) = (card * 0.10f).coerceIn(4f, 14f)
    /** The score column's width: room for "8000" and a name. */
    fun scoreFor(card: Float) = (card * 0.55f).coerceIn(52f, 76f)
    /** A seat's block in the score column: its name over its life points. */
    fun scoreHeightFor(card: Float) = (card * CARD_RATIO * 0.38f).coerceIn(54f, 72f)
    fun turnHeightFor(card: Float) = (card * CARD_RATIO * 0.12f).coerceIn(18f, 24f)
    fun capFor(form: FormFactor) = when (form) {
        FormFactor.DESK -> CAP_DESK
        FormFactor.TABLET -> CAP_TABLET
        FormFactor.PHONE -> CAP_PHONE
    }

    fun solve(
        width: Float,
        height: Float,
        twoSided: Boolean,
        form: FormFactor = FormFactor.DESK,
        bottom: Int = 0,
        wantRails: Boolean = true,
    ): DuelLayout {
        val margin = if (form == FormFactor.PHONE) 8f else 12f
        val cap = capFor(form)

        // The card the height allows, for a far side at [scale] with or without its hand.
        fun tall(c: Float, scale: Float, farHand: Boolean): Float {
            val g = gapFor(c)
            val ch = c * CARD_RATIO
            var t = margin * 2 + ch + g + (2 * ch + g)
            if (twoSided) {
                t += g + ch + g + scale * (2 * ch + g)
                if (farHand) t += g + FAR_HAND * ch * scale
            } else {
                t += g + ch
            }
            return t
        }
        fun byHeight(scale: Float, farHand: Boolean): Float = largest(cap) { tall(it, scale, farHand) <= height }

        var farScale = 1f
        var farHand = twoSided
        var c = byHeight(1f, farHand)
        if (twoSided && c < MIN_CARD) {
            farScale = FAR_SHRUNK
            c = byHeight(farScale, true)
            if (c < MIN_CARD) {
                farHand = false
                c = byHeight(farScale, false)
            }
        }
        fun grid(card: Float) = 7 * card + 6 * gapFor(card) + gapFor(card) + scoreFor(card)

        // Rails: both, the inspector alone (the log a tab of it), or drawers.
        var inspectorW = 0f
        var logW = 0f
        var logInInspector = false
        var drawers = !wantRails
        val room = width - margin * 2
        if (wantRails) {
            when {
                grid(c) + INSPECTOR_MIN + LOG_MIN + margin * 2 <= room -> {
                    var spare = room - grid(c) - INSPECTOR_MIN - LOG_MIN - margin * 2
                    inspectorW = INSPECTOR_MIN + minOf(spare, INSPECTOR_MAX - INSPECTOR_MIN)
                    spare -= inspectorW - INSPECTOR_MIN
                    logW = LOG_MIN + minOf(spare, LOG_MAX - LOG_MIN)
                }
                grid(c) + INSPECTOR_MIN + margin <= room -> {
                    inspectorW = INSPECTOR_MIN + minOf(room - grid(c) - INSPECTOR_MIN - margin, INSPECTOR_MAX - INSPECTOR_MIN)
                    logInInspector = true
                }
                else -> drawers = true
            }
        }
        // Too narrow for the grid even with no rails: the width decides the card.
        val tableRoom = room - inspectorW - logW - (if (inspectorW > 0) margin else 0f) - (if (logW > 0) margin else 0f)
        if (grid(c) > tableRoom) c = largest(c) { grid(it) <= tableRoom }

        val g = gapFor(c)
        val ch = c * CARD_RATIO
        val ph = scoreFor(c)
        val gridW = 7 * c + 6 * g
        val blockW = gridW + g + ph
        val tableLeft = margin + (if (inspectorW > 0) inspectorW + margin else 0f)
        val gridLeft = tableLeft + (tableRoom - blockW) / 2f
        fun col(i: Int) = gridLeft + i * (c + g)

        // Rows, top to bottom; the block is centred in the height.
        val totalH = tall(c, farScale, farHand) - margin * 2
        // Centred in the height; on a phone, down by the thumbs, the room left above the far side.
        val slack = ((height - margin * 2) - totalH).coerceAtLeast(0f)
        var y = margin + if (form == FormFactor.PHONE) slack else slack / 2f
        val spots = LinkedHashMap<DuelSpot, Slot>()
        val score = HashMap<Int, Slot>()
        val near = bottom
        val far = 1 - bottom
        val fieldTop: Float

        fun slot(colIndex: Int, top: Float) = Slot(col(colIndex), top, c, ch)
        // A far-side cell: mirrored column, scaled about the column's centre.
        fun farSlot(colIndex: Int, top: Float): Slot {
            val m = 6 - colIndex
            val w = c * farScale
            val cx = col(m) + c / 2f
            return Slot(cx - w / 2f, top, w, w * CARD_RATIO)
        }

        if (twoSided) {
            val fc = c * farScale
            val fch = fc * CARD_RATIO
            val fg = g * farScale
            if (farHand) {
                val handW = gridW * farScale
                spots[DuelSpot.Hand(far)] = Slot(gridLeft + (gridW - handW) / 2f, y, handW, FAR_HAND * ch * farScale)
                y += FAR_HAND * ch * farScale + g
            }
            fieldTop = y
            // Their Spell & Trap row, then their monsters, mirrored.
            spots[DuelSpot.Pile(far, PileKind.EXTRA)] = farSlot(0, y)
            (0 until 5).forEach { i -> spots[DuelSpot.Zone(Place.Zone(far, ZoneKind.SPELL, i))] = farSlot(1 + i, y) }
            spots[DuelSpot.Pile(far, PileKind.DECK)] = farSlot(6, y)
            y += fch + fg
            spots[DuelSpot.Zone(Place.Zone(far, ZoneKind.FIELD, 0))] = farSlot(0, y)
            (0 until 5).forEach { i -> spots[DuelSpot.Zone(Place.Zone(far, ZoneKind.MONSTER, i))] = farSlot(1 + i, y) }
            spots[DuelSpot.Pile(far, PileKind.GY)] = farSlot(6, y)
            y += fch + g
            // The shared row.
            spots[DuelSpot.Pile(far, PileKind.BANISHED)] = slot(0, y).let { s -> val w = c * farScale; Slot(s.centerX - w / 2f, s.top + (ch - w * CARD_RATIO) / 2f, w, w * CARD_RATIO) }
        } else {
            fieldTop = y
        }
        val emzLeft = if (bottom == 0) 0 else 1
        spots[DuelSpot.Zone(Place.Zone(0, ZoneKind.EMZ, emzLeft))] = slot(2, y)
        spots[DuelSpot.Zone(Place.Zone(0, ZoneKind.EMZ, 1 - emzLeft))] = slot(4, y)
        spots[DuelSpot.Chain] = slot(3, y)
        spots[DuelSpot.Pile(near, PileKind.BANISHED)] = slot(6, y)
        y += ch + g
        spots[DuelSpot.Zone(Place.Zone(near, ZoneKind.FIELD, 0))] = slot(0, y)
        (0 until 5).forEach { i -> spots[DuelSpot.Zone(Place.Zone(near, ZoneKind.MONSTER, i))] = slot(1 + i, y) }
        spots[DuelSpot.Pile(near, PileKind.GY)] = slot(6, y)
        y += ch + g
        spots[DuelSpot.Pile(near, PileKind.EXTRA)] = slot(0, y)
        (0 until 5).forEach { i -> spots[DuelSpot.Zone(Place.Zone(near, ZoneKind.SPELL, i))] = slot(1 + i, y) }
        spots[DuelSpot.Pile(near, PileKind.DECK)] = slot(6, y)
        y += ch
        val fieldBottom = y
        y += g
        spots[DuelSpot.Hand(near)] = Slot(gridLeft, y, gridW, ch)

        // The score column: their score at the top, the turn, the phases, your score at the bottom.
        val colLeft = gridLeft + gridW + g
        val sh = scoreHeightFor(c)
        val th = turnHeightFor(c)
        var top = fieldTop
        if (twoSided) {
            score[far] = Slot(colLeft, top, ph, sh)
            top += sh + g
        }
        val turn = Slot(colLeft, top, ph, th)
        top += th + g / 2f
        score[near] = Slot(colLeft, fieldBottom - sh, ph, sh)
        val phases = Slot(colLeft, top, ph, (fieldBottom - sh - g - top).coerceAtLeast(0f))
        val railTop = margin
        val railH = height - margin * 2
        val inspector = if (inspectorW > 0) Slot(margin, railTop, inspectorW, railH) else null
        val log = if (logW > 0) Slot(width - margin - logW, railTop, logW, railH) else null
        return DuelLayout(
            width = width,
            height = height,
            card = c,
            cardHeight = ch,
            gap = g,
            farScale = farScale,
            spots = spots,
            score = score,
            turn = turn,
            phases = phases,
            field = Slot(gridLeft, fieldTop, gridW, fieldBottom - fieldTop),
            inspector = inspector,
            log = log,
            logInInspector = logInInspector,
            drawers = drawers,
            farHandFolded = twoSided && !farHand,
            bottom = bottom,
            twoSided = twoSided,
            fits = c >= MIN_CARD,
        )
    }

    /** The largest card width up to [cap] for which [fits] holds, to a tenth of a dp. */
    private fun largest(cap: Float, fits: (Float) -> Boolean): Float {
        if (fits(cap)) return cap
        var lo = 8f
        var hi = cap
        if (!fits(lo)) return lo
        repeat(40) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo
    }
}
