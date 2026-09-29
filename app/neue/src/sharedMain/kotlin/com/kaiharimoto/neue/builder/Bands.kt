package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.layout.BandLayout
import com.kaiharimoto.mastertool.core.layout.BandMemory
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.GroupBands
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import kotlin.math.roundToInt

/**
 * The main deck's bands of group blocks (1.0.37, `GroupBands`), when the groups are out
 * as Fitted or Separate: one layout the deck and the phone's measure of it both read, and
 * the memory that keeps an edit from reshuffling the deck (`BandMemory`), per deck.
 *
 * Plain state, not snapshot state: it is worked out from the deck, the keys and the pane,
 * which are the things that recompose.
 */
internal class BandCache {
    private var deck: String? = null
    private var inputs: Any? = null
    private var result: BandLayout? = null

    /** The bands last laid out, for the screenshot to draw what the builder shows. */
    val last: BandLayout? get() = result
    private var memory: BandMemory? = null
    private var room: Any? = null

    fun layout(deckId: String?, ids: List<Int>, keys: List<String?>, order: List<String>, pane: Pair<Float, Float>, otherRows: Int): BandLayout? {
        if (deckId != deck) {
            deck = deckId
            memory = null
            inputs = null
        }
        val key = listOf(ids, keys, order, pane, otherRows)
        if (key == inputs) return result
        inputs = key
        // The memory is for edits: a new pane (a window resized, a tablet turned, the extra
        // deck shown) is laid out afresh, or the first frame's size would hold for ever.
        val here = pane to otherRows
        if (here != room) memory = null
        room = here
        result = GroupBands.layout(ids, keys, order, pane, otherRows, memory = memory)
        result?.let { memory = it.memory }
        return result
    }
}

/** Whether the main deck is laid out in bands now: the groups out, Fitted or Separate, not being drawn up. */
internal fun bandsOn(state: DeckBuilderState, neue: NeueState, phoneColumns: Int?): Boolean =
    state.lens == Lens.ROLES && state.groupDraft == null && phoneColumns == null &&
        neue.prefs.arrangement != GroupArrangement.AS_IS

/**
 * The main deck's bands, or null to lay it out as it reads. [paneWidth] × [paneHeight] is
 * the room the deck has, in pixels; on a phone the deck is measured to its width, so
 * the pane is the width's own shape there. Quantised, so a resize does not lay the deck
 * out again every pixel.
 */
internal fun mainBands(state: DeckBuilderState, neue: NeueState, phoneColumns: Int?, paneWidth: Float, paneHeight: Float): BandLayout? {
    if (!bandsOn(state, neue, phoneColumns)) return null
    val keying = state.keying(DeckSection.MAIN)
    if (keying.keyOfCell.none { it != null }) return null
    val ids = state.deck[DeckSection.MAIN].map { it.value }
    val height = if (neue.phone) paneWidth * PHONE_PANE else paneHeight
    fun step(v: Float) = ((v / 40f).roundToInt() * 40).toFloat().coerceAtLeast(40f)
    val others = listOf(neue.prefs.extraVisible, neue.prefs.sideVisible).count { it }
    return neue.bandCache.layout(state.deckId, ids, keying.keyOfCell, keying.keyOrder, step(paneWidth) to step(height), others)
}

/** A phone's deck is measured to its width, and upright it leaves the dock half the screen (v1.3.6): the pane its bands are fitted to is this much of the width tall, the decklist's 10×4 shape. */
private const val PHONE_PANE = 0.6f
