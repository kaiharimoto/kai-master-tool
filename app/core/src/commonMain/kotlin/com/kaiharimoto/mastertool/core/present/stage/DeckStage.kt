package com.kaiharimoto.mastertool.core.present.stage

import com.kaiharimoto.mastertool.core.layout.BandLayout
import com.kaiharimoto.mastertool.core.layout.GroupBands
import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.GroupRows
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.DeckSnapshot
import com.kaiharimoto.mastertool.core.present.Presentation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One card on the deck's stage: which copy ([key] `M:1234#0` — section, passcode, copy),
 * where, how bright, how much it stands out, and the group outline it draws.
 */
data class StageCard(
    val key: String,
    val id: Int,
    val box: Box,
    val alpha: Float = 1f,
    /** 0 at rest, 1 lifted and lit: the cards being talked about. */
    val emphasis: Float = 0f,
    /** The group this copy belongs to, for its outline's colour. */
    val group: String? = null,
    /** Which sides face out of its piece — left, top, right, bottom — as bits 1, 2, 4, 8. */
    val outer: Int = 0,
    /** A copies badge (`×3`) where one card stands for every copy. */
    val badge: Int = 0,
)

/** A group's name written over its piece. */
data class StageLabel(val group: String, val text: String, val color: Int, val box: Box, val alpha: Float = 1f)

/** The note beside the deck: what this step is about, in words. */
data class StageNote(val title: String, val text: String, val box: Box)

/** Everything a deck slide draws on its stage at rest. */
data class StageFrame(
    val cards: List<StageCard> = emptyList(),
    val labels: List<StageLabel> = emptyList(),
    val note: StageNote? = null,
    /** Group id → its colour index, for outlines. */
    val colors: Map<String, Int> = emptyMap(),
) {
    private val index: Map<String, StageCard> by lazy { cards.associateBy { it.key } }

    fun card(key: String): StageCard? = index[key]

    companion object {
        val EMPTY = StageFrame()
    }
}

/**
 * The three ways a deck is told (kai, 1.0.70), as pure layout: given the deck, the deck
 * slides in order and which one is showing, where every copy stands. The presenter only
 * glides between consecutive frames ([StageTween]), so nothing here knows about time.
 *
 * - **Spotlight**: the whole deck, as the builder lays it; the cards talked about lit and
 *   lifted, those talked about before staying bright, the rest dimmed.
 * - **Slides**: what is talked about, large — one card, a few, a group as its piece — and
 *   the whole deck only on demand ([overview]).
 * - **Build-up**: only the cards talked about so far, laid out as a deck that fills the
 *   stage, so the cards shrink as the deck grows, until the last step is the whole deck.
 */
object DeckStage {
    /** A card's width over its height. */
    const val CARD_ASPECT = 59f / 86f

    /** Room between a group's pieces, as a share of a card's width. */
    const val GAP_SHARE = 0.16f

    /** Room for a group's name over its piece, as a share of a card's width. */
    const val LABEL_SHARE = 0.2f

    /** The note lane's share of the stage, beside or under the deck. */
    const val SIDE_LANE = 0.3f
    const val BOTTOM_LANE = 0.24f

    /** The most a single card may take of the stage's height. */
    const val ONE_CARD = 0.92f

    /** A copy's key. */
    fun key(section: String, id: Int, copy: Int): String = "$section:$id#$copy"

    /** The passcode in a [key]. */
    fun idOf(key: String): Int? = key.substringAfter(':').substringBefore('#').toIntOrNull()

    /** A copy of the deck: its key, passcode and section. */
    data class Copy(val key: String, val id: Int, val section: String)

    /** Every copy in [deck], keyed, in the deck's order. */
    fun copies(deck: DeckSnapshot): List<Copy> = buildList {
        for (section in listOf(DeckSnapshot.SECTION_MAIN, DeckSnapshot.SECTION_EXTRA, DeckSnapshot.SECTION_SIDE)) {
            val seen = HashMap<Int, Int>()
            deck.section(section).forEach { id ->
                val n = seen[id] ?: 0
                seen[id] = n + 1
                add(Copy(key(section, id, n), id, section))
            }
        }
    }

    /** The copies [focus] names in [deck]: whole groups, every copy of its cards, and single copies. */
    fun focused(deck: DeckSnapshot, focus: DeckFocus): Set<String> {
        val all = copies(deck)
        if (focus.all) return all.filter { inSections(it.section, focus.sections) }.map { it.key }.toSet()
        val groups = focus.groups.toSet()
        val cards = focus.cards.toSet()
        return all.filter { c ->
            c.key in focus.copies || c.id in cards || (deck.groupOf(c.id)?.let { it in groups } == true)
        }.map { it.key }.toSet()
    }

    private fun inSections(section: String, sections: List<String>) = sections.isEmpty() || section in sections

    /**
     * The frame for the deck slide [step] of [steps] (each slide's focus, in order) told in
     * [style] on [stage]. [overview] shows the whole deck whatever the style, the current
     * focus marked — what D brings back at any time. [dim] is the theme's brightness for
     * cards not talked about.
     */
    fun frame(
        deck: DeckSnapshot,
        steps: List<DeckFocus>,
        style: String,
        step: Int,
        stage: Box,
        overview: Boolean = false,
        dim: Float = 0.28f,
    ): StageFrame {
        if (deck.isEmpty) return StageFrame.EMPTY
        val focus = steps.getOrNull(step) ?: DeckFocus(all = true)
        val lit = focused(deck, focus)
        val colors = deck.groups.associate { it.id to it.color }
        val noteText = focus.note.trim()
        val title = focus.title.trim()
        val wantsNote = !overview && focus.notePlace != DeckFocus.NOTE_NONE && (noteText.isNotEmpty() || title.isNotEmpty())

        return when {
            overview || style == Presentation.STYLE_SPOTLIGHT || focus.all -> {
                val reveal = if (style == Presentation.STYLE_BUILD_UP && overview) revealed(deck, steps, step) else null
                val (area, note) = split(stage, wantsNote, focus.notePlace, deckAspect(deck, null), title, noteText)
                val placed = whole(deck, area, sections = focus.sections.takeIf { focus.all })
                val anyLit = !focus.all && lit.isNotEmpty()
                // Spotlight keeps what it has revealed (kai, 1.0.71): the cards of earlier steps stay
                // bright, and only the step's own focus is lifted and lit.
                val keep = style == Presentation.STYLE_SPOTLIGHT && !focus.all
                val seen = if (keep) revealed(deck, steps, step) else emptySet()
                val seenGroups = if (keep) steps.take(step + 1).filter { !it.all }.flatMap { it.groups }.toSet() else emptySet()
                placed.copy(
                    cards = placed.cards.map { c ->
                        val on = c.key in lit
                        val shown = reveal == null || c.key in reveal
                        c.copy(
                            alpha = when {
                                !shown -> dim * 0.5f
                                !anyLit || on || c.key in seen -> 1f
                                else -> dim
                            },
                            emphasis = if (anyLit && on) 1f else 0f,
                        )
                    },
                    labels = placed.labels.map { l ->
                        l.copy(alpha = if (!anyLit || focus.groups.contains(l.group) || l.group in seenGroups) 1f else dim)
                    },
                    note = note,
                    colors = colors,
                )
            }
            style == Presentation.STYLE_BUILD_UP -> {
                val reveal = revealed(deck, steps, step)
                val (area, note) = split(stage, wantsNote, focus.notePlace, deckAspect(deck, reveal), title, noteText)
                val placed = whole(deck, area, only = reveal)
                placed.copy(
                    cards = placed.cards.map { c -> c.copy(emphasis = if (c.key in lit) 1f else 0f) },
                    note = note,
                    colors = colors,
                )
            }
            else -> {
                // Slides: one copy of each card talked about, large, with its count.
                val all = copies(deck)
                val chosen = all.filter { it.key in lit }
                val firsts = chosen.groupBy { it.section to it.id }.values.map { it.first() to it.size }
                val (area, note) = split(stage, wantsNote, focus.notePlace, slidesAspect(firsts.size), title, noteText)
                val cards = large(deck, firsts, area)
                StageFrame(cards = cards, labels = emptyList(), note = note, colors = colors)
            }
        }
    }

    /** The copies Build-up has shown by step [step]: every focus up to it but a whole-deck one. */
    fun revealed(deck: DeckSnapshot, steps: List<DeckFocus>, step: Int): Set<String> {
        val out = LinkedHashSet<String>()
        // A whole-deck step shows everything but reveals nothing for the steps after it.
        for (i in 0..step.coerceAtMost(steps.lastIndex)) if (!steps[i].all) out += focused(deck, steps[i])
        return out
    }

    // ---- the note lane ----------------------------------------------------------

    private fun split(
        stage: Box,
        wantsNote: Boolean,
        place: String,
        aspect: Float,
        title: String,
        note: String,
    ): Pair<Box, StageNote?> {
        if (!wantsNote) return stage to null
        val gap = 36f
        val side = Box(stage.x, stage.y, stage.w * (1 - SIDE_LANE) - gap, stage.h) to
            Box(stage.x + stage.w * (1 - SIDE_LANE), stage.y, stage.w * SIDE_LANE, stage.h)
        val bottom = Box(stage.x, stage.y, stage.w, stage.h * (1 - BOTTOM_LANE) - gap) to
            Box(stage.x, stage.y + stage.h * (1 - BOTTOM_LANE), stage.w, stage.h * BOTTOM_LANE)
        val chosen = when (place) {
            DeckFocus.NOTE_SIDE -> side
            DeckFocus.NOTE_BOTTOM -> bottom
            else -> if (side.first.fitted(aspect).area >= bottom.first.fitted(aspect).area) side else bottom
        }
        return chosen.first to StageNote(title, note, chosen.second)
    }

    // ---- the whole deck ---------------------------------------------------------

    /** The shape the whole deck (or [only] its copies) takes, roughly, width over height. */
    private fun deckAspect(deck: DeckSnapshot, only: Set<String>?): Float {
        val cs = copies(deck).filter { only == null || it.key in only }
        val main = cs.count { it.section == DeckSnapshot.SECTION_MAIN }
        val others = cs.size - main
        val cols = when {
            main <= 0 -> min(max(others, 1), 15)
            else -> max(5, min(15, ceil(kotlin.math.sqrt(main * 2.2)).toInt()))
        }
        val rows = ceil(main / cols.toDouble()).toFloat() + ceil(others / 15.0).toFloat() * cols / 15f
        return cols * CARD_ASPECT / max(rows, 1f)
    }

    private fun slidesAspect(n: Int): Float = when {
        n <= 1 -> CARD_ASPECT
        n <= 3 -> n * CARD_ASPECT
        else -> 2.2f
    }

    /**
     * The whole deck — or [only] those copies of it — on [area]: the Main Deck in the
     * snapshot's arrangement with its groups as pieces, the Extra and Side Decks in rows under
     * it at its width or in a column beside it at its card size — whichever draws larger —
     * everything scaled to fill [area].
     */
    fun whole(deck: DeckSnapshot, area: Box, only: Set<String>? = null, sections: List<String>? = null): StageFrame {
        val all = copies(deck).filter { (only == null || it.key in only) && (sections.isNullOrEmpty() || it.section in sections) }
        if (all.isEmpty() || area.w <= 0f || area.h <= 0f) return StageFrame.EMPTY
        val main = all.filter { it.section == DeckSnapshot.SECTION_MAIN }
        val extra = all.filter { it.section == DeckSnapshot.SECTION_EXTRA }
        val side = all.filter { it.section == DeckSnapshot.SECTION_SIDE }
        val order = deck.ordered().map { it.id }
        val keys = main.map { deck.groupOf(it.id) }
        val grouped = keys.any { it != null }
        val hasOthers = extra.isNotEmpty() || side.isNotEmpty()

        // A trial card width of 1 sizes the gaps; the real width scales everything.
        val pieces: PieceLayout? = if (main.isEmpty()) null else mainPieces(deck, main, keys, order, area, extra.size, side.size)
        val cols = pieces?.columns ?: 0
        val mainRows = pieces?.rowCount ?: 0
        val spanX = pieces?.spanX ?: 0
        val spanY = pieces?.spanY ?: 0
        val sectionGapShare = 0.35f
        val labelShare = if (grouped) LABEL_SHARE else 0f
        val mainAcross = if (main.isEmpty()) 0f else cols + spanX * GAP_SHARE
        val mainDown = if (main.isEmpty()) 0f else mainRows / CARD_ASPECT + spanY * (GAP_SHARE + labelShare) + labelShare
        fun rowsDown(n: Int, perRow: Int, w: Float): Float = if (n == 0) 0f else ceil(n / perRow.toDouble()).toFloat() * w / CARD_ASPECT

        // Below: the Extra and Side Decks in rows under the Main Deck, fifteen across its width, as the builder draws them.
        val belowCols = if (main.isEmpty()) min(15, max(extra.size, side.size)).coerceAtLeast(1) else 15
        val belowW = if (main.isEmpty()) 1f else mainAcross / belowCols
        val belowAcross = if (main.isEmpty()) belowCols.toFloat() else mainAcross
        val belowDown = mainDown +
            (if (extra.isNotEmpty()) (if (main.isEmpty()) 0f else sectionGapShare) + rowsDown(extra.size, belowCols, belowW) else 0f) +
            (if (side.isNotEmpty()) (if (main.isEmpty() && extra.isEmpty()) 0f else sectionGapShare) + rowsDown(side.size, belowCols, belowW) else 0f)
        val belowCw = min(area.w / belowAcross, area.h / belowDown.coerceAtLeast(0.01f))

        // Beside: a column of their own at the Main Deck's card size — what a wide stage has room for.
        var besideCols = 0
        var besideCw = 0f
        if (main.isNotEmpty() && hasOthers) {
            for (k in 2..8) {
                val down = maxOf(
                    mainDown,
                    rowsDown(extra.size, k, 1f) + rowsDown(side.size, k, 1f) + (if (extra.isNotEmpty() && side.isNotEmpty()) sectionGapShare else 0f),
                )
                val across = mainAcross + sectionGapShare + k
                val w = min(area.w / across, area.h / down)
                if (w > besideCw) {
                    besideCw = w
                    besideCols = k
                }
            }
        }
        val beside = besideCw > belowCw * 1.02f
        val cw = if (beside) besideCw else belowCw
        val gap = cw * GAP_SHARE
        val label = cw * labelShare
        val ch = cw / CARD_ASPECT
        val totalW = (if (beside) mainAcross + sectionGapShare + besideCols else belowAcross) * cw
        val totalH = (if (beside) {
            maxOf(mainDown, rowsDown(extra.size, besideCols, 1f) + rowsDown(side.size, besideCols, 1f) + (if (extra.isNotEmpty() && side.isNotEmpty()) sectionGapShare else 0f))
        } else {
            belowDown
        }) * cw
        val left = area.x + (area.w - totalW) / 2f
        val top0 = area.y + (area.h - totalH) / 2f
        var top = top0

        val cards = ArrayList<StageCard>(all.size)
        val labels = ArrayList<StageLabel>()
        if (pieces != null) {
            val y0 = top + label
            main.forEachIndexed { p, c ->
                val x = left + pieces.col(p) * cw + pieces.shiftX[p] * gap
                val y = y0 + pieces.row(p) * ch + pieces.shiftY[p] * (gap + label)
                val sides = pieces.outerSides(p)
                var outer = 0
                if (sides[0]) outer = outer or 1
                if (sides[1]) outer = outer or 2
                if (sides[2]) outer = outer or 4
                if (sides[3]) outer = outer or 8
                cards += StageCard(c.key, c.id, Box(x, y, cw, ch), group = keys[p], outer = if (keys[p] != null) outer else 0)
            }
            if (grouped) {
                for (g in deck.ordered()) {
                    val edge = pieces.labelEdge(keys, g.id, need = 1.5f) ?: continue
                    val first = cards[edge.first].box
                    labels += StageLabel(g.id, g.name, g.color, Box(first.x, first.y - label, cw * edge.cells, label))
                }
            }
            top += label + mainRows * ch + spanY * (gap + label)
        }
        var otherTop = if (beside) top0 else top
        val otherLeft = if (beside) left + (mainAcross + sectionGapShare) * cw else left
        val perRow = if (beside) besideCols else belowCols
        val w = if (beside) cw else belowW * cw
        val h = w / CARD_ASPECT
        var firstBlock = true
        fun rows(list: List<Copy>) {
            if (list.isEmpty()) return
            if (!(beside && firstBlock) && !(main.isEmpty() && firstBlock)) otherTop += cw * sectionGapShare
            firstBlock = false
            list.forEachIndexed { i, c ->
                cards += StageCard(c.key, c.id, Box(otherLeft + (i % perRow) * w, otherTop + (i / perRow) * h, w, h), group = deck.groupOf(c.id))
            }
            otherTop += ceil(list.size / perRow.toDouble()).toFloat() * h
        }
        rows(extra)
        rows(side)
        if (!hasOthers && pieces == null) return StageFrame.EMPTY
        return StageFrame(cards = cards, labels = labels)
    }

    private fun mainPieces(
        deck: DeckSnapshot,
        main: List<Copy>,
        keys: List<String?>,
        order: List<String>,
        area: Box,
        extra: Int,
        side: Int,
    ): PieceLayout {
        val ids = main.map { it.id }
        val grouped = keys.any { it != null }
        val otherRows = (if (extra > 0) ceil(extra / 15.0).toInt() else 0) + (if (side > 0) ceil(side / 15.0).toInt() else 0)
        val pane = area.w to area.h
        val aspect = 1f / CARD_ASPECT
        val gapGuess = area.w / 12f * GAP_SHARE
        if (grouped && deck.arrangement != "AS_IS") {
            val band: BandLayout? = if (deck.arrangement == "SEPARATE") {
                GroupRows.layout(ids, keys, order, pane, otherRows, aspect, gapY = gapGuess, setOrder = deck.fitted)
            } else {
                GroupBands.layout(ids, keys, order, pane, otherRows, aspect, gapX = gapGuess, gapY = gapGuess, setOrder = deck.fitted)
            }
            if (band != null) return band.pieces()
        }
        // As is, or no groups: the deck's own order, the column count that draws the largest cards.
        val n = ids.size
        val best = (min(5, n)..min(15, n).coerceAtLeast(min(5, n))).maxByOrNull { c ->
            val layout = GroupPieces.of(keys, c)
            val across = c + layout.spanX * GAP_SHARE
            val others = otherRows * (across / 15f) / CARD_ASPECT
            val down = layout.rowCount / CARD_ASPECT + layout.spanY * GAP_SHARE + others
            min(area.w / across, area.h / down)
        } ?: 10
        return GroupPieces.of(keys, best)
    }

    // ---- large: the Slides style ------------------------------------------------

    private fun large(deck: DeckSnapshot, cards: List<Pair<Copy, Int>>, area: Box): List<StageCard> {
        if (cards.isEmpty()) return emptyList()
        if (cards.size == 1) {
            val h = area.h * ONE_CARD
            val w = min(h * CARD_ASPECT, area.w * 0.9f)
            val hh = w / CARD_ASPECT
            val (c, n) = cards[0]
            return listOf(
                StageCard(c.key, c.id, Box(area.cx - w / 2f, area.cy - hh / 2f, w, hh), emphasis = 0f, group = deck.groupOf(c.id), badge = if (n > 1) n else 0),
            )
        }
        val n = cards.size
        val gapShare = 0.08f
        var bestCols = 1
        var bestW = 0f
        for (cols in 1..n) {
            val rows = ceil(n / cols.toDouble()).toInt()
            val w = min(area.w / (cols + (cols - 1) * gapShare), area.h / (rows / CARD_ASPECT + (rows - 1) * gapShare))
            if (w > bestW) {
                bestW = w
                bestCols = cols
            }
        }
        val cw = min(bestW, area.h * ONE_CARD * CARD_ASPECT)
        val ch = cw / CARD_ASPECT
        val gap = cw * gapShare
        val rows = ceil(n / bestCols.toDouble()).toInt()
        val totalH = rows * ch + (rows - 1) * gap
        val top = area.y + (area.h - totalH) / 2f
        return cards.mapIndexed { i, (c, count) ->
            val r = i / bestCols
            val inRow = if (r == rows - 1) n - r * bestCols else bestCols
            val rowW = inRow * cw + (inRow - 1) * gap
            val k = i % bestCols
            val left = area.x + (area.w - rowW) / 2f
            StageCard(c.key, c.id, Box(left + k * (cw + gap), top + r * (ch + gap), cw, ch), group = deck.groupOf(c.id), badge = if (count > 1) count else 0)
        }
    }
}
