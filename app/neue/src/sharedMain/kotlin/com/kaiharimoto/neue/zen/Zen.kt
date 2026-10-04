package com.kaiharimoto.neue.zen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import com.kaiharimoto.mastertool.core.motion.ZenArrangement
import com.kaiharimoto.mastertool.core.motion.ZenHome
import com.kaiharimoto.mastertool.core.motion.ZenMembership
import com.kaiharimoto.mastertool.core.motion.ZenPick
import com.kaiharimoto.mastertool.core.motion.ZenStage

/**
 * Zen mode, as the builder sees it: two amounts and a clock.
 *
 * - [quiet] goes 0 → 1 three seconds after the last input: everything that is
 *   not a card fades (strips, rules, search, the inspector's text).
 * - [deep] goes 0 → 1 at ten seconds: the pool and the inspector go entirely,
 *   and the deck moves to the middle and floats over its own shadows. The
 *   cards are the garden: the pointer may pick any of them up and put it down
 *   anywhere ([arrangement]), and only a key brings the builder back.
 * - [time] runs while [deep] is above zero, for the float.
 *
 * Every reader reads them inside a `graphicsLayer` or a draw block, so the
 * whole of zen is redrawing and never recomposing — the same recipe as the
 * deck's lean.
 */
class ZenLayer {
    var quiet by mutableFloatStateOf(0f)
    var deep by mutableFloatStateOf(0f)
    var time by mutableFloatStateOf(0f)

    /**
     * Whether zen is deep: the phase, not the fade. From the moment it is, nothing
     * but the cards and the corner answers the pointer (1.0.24): the panes are
     * shielded and every piece of faded chrome is taken off the page ([zenQuiet]).
     * Written by the clockwork, which knows the phase.
     */
    var asleep by mutableStateOf(false)

    /** Where the cards have been put. Plain: [arranged] is the state a reader reads to see it change. */
    val arrangement = ZenArrangement()

    /** Bumped whenever [arrangement] changes, so layers that read it redraw. */
    var arranged by mutableIntStateOf(0)

    /** The cards being carried in zen, by their [ZenArrangement.key]: one, or the whole selection. */
    var carrying by mutableStateOf<Set<Int>>(emptySet())

    /**
     * The cards picked out in deep zen (1.0.14): by a box dragged over the table,
     * a Shift-click, or a double-click on a block. Pressing any of them carries
     * all of them. Cleared when zen ends.
     */
    var selection by mutableStateOf<Set<Int>>(emptySet())

    /** The box being dragged over the table, in window pixels, or null. */
    var marquee by mutableStateOf<Rect?>(null)

    /**
     * The deck in pieces by its Roles groups, in deep zen (kai, 1.0.15): asked for
     * from the corner, and on from the start when the builder had its groups on.
     */
    var groups by mutableStateOf(false)

    /** How far the pieces have opened, 0..1, following [groups]. Read in layers and draw blocks. */
    var groupsAmount by mutableFloatStateOf(0f)

    /**
     * How far the groups' names are drawn on their pieces, 0..1, following the
     * corner's Labels switch (`NeuePreferences.zenLabels`, 1.0.24). Read in draw blocks.
     */
    var labelsAmount by mutableFloatStateOf(1f)

    /**
     * How wide zen's gaps are, as a multiple of the standard gap: the wheel's, in
     * deep zen (kai, 1.0.17: "let the scroll wheel expand the gaps further and
     * tighten, card sizes adjust automatically").
     */
    var gapScale by mutableFloatStateOf(1f)

    /** How far apart the pieces are drawn: open or not, times how wide the gaps are. */
    val pieceAmount: Float get() = groupsAmount * gapScale

    /**
     * How much wider and taller the deck is with its pieces fully apart at one
     * standard gap, in the deck's pixels at rest. Plain: the deck writes it.
     */
    var pieceGrowth: Size = Size.Zero

    /**
     * How far each card is from its rest place once zen is deep, by its key: the
     * part that is always so, and the part the pieces add at full [groupsAmount].
     * In the deck's pixels. Plain: written by the deck, read when a card is let go.
     */
    val pieceBase = HashMap<Int, Offset>()
    val pieceShift = HashMap<Int, Offset>()

    /**
     * Every slot as it is in deep zen, with the pieces as open as they are now:
     * what a card is picked out and snapped against. The deck's pixels are the
     * window's at rest; the pieces move inside the zen transform, so they go in
     * before it, as the arrangement's own offsets do.
     */
    fun homesNow(): Map<Int, ZenHome> {
        val k = pieceAmount
        return homes.mapValues { (key, h) ->
            val base = pieceBase[key] ?: Offset.Zero
            val shift = pieceShift[key] ?: Offset.Zero
            h.copy(x = h.x + base.x + shift.x * k, y = h.y + base.y + shift.y * k)
        }
    }

    /**
     * How far home the arrangement is drawn from: 1 where it was left, 0 back in
     * the deck. "Put the cards back" runs it down before it clears the arrangement,
     * so the cards glide home rather than jump.
     */
    var gather by mutableFloatStateOf(1f)

    /** Whether the pointer is in the window's bottom-right corner in deep zen, where "put the cards back" is. */
    var corner by mutableStateOf(false)

    /** How wide the corner's row of buttons was when last out, in pixels: the corner is at least that wide. Plain. */
    var cornerRow: Float = 0f

    fun move(key: Int, dx: Float, dy: Float) {
        arrangement.move(key, dx, dy)
        arranged++
    }

    /** Every card's slot at rest, in window pixels, and the block it floats with there: what a drop snaps to. Plain. */
    val homes = HashMap<Int, ZenHome>()

    /** Card [key] is let go: home, beside another card, or where it is (`ZenSnap`). */
    fun drop(key: Int) {
        arrangement.drop(key, homesNow())
        arranged++
    }

    /** Every card of [keys] moved as one. */
    fun moveAll(keys: Collection<Int>, dx: Float, dy: Float) {
        arrangement.moveAll(keys, dx, dy)
        arranged++
    }

    /** The cards of [keys], carried by [anchor], let go together (`ZenSnap.snapAll`). */
    fun dropAll(keys: Collection<Int>, anchor: Int) {
        arrangement.dropAll(keys, anchor, homesNow())
        arranged++
    }

    /** The card drawn on top at a window point, or null for the table. */
    fun pickAt(point: Offset): Int? = ZenPick.at(point.x, point.y, homesNow(), arrangement, stage, pivot.x, pivot.y)

    /** Every card a box over the window touches. */
    fun within(box: Rect): Set<Int> = ZenPick.within(box.left, box.top, box.right, box.bottom, homesNow(), arrangement, stage, pivot.x, pivot.y)

    /** Card [key]'s whole block, as the garden has it now. */
    fun blockOf(key: Int): Set<Int> = arrangement.blockOf(key, homesNow())

    fun isSelected(key: Int): Boolean = key in selection

    /** Zen has ended, or the cards have gone home: nothing is picked out any more. */
    fun forget() {
        selection = emptySet()
        marquee = null
        carrying = emptySet()
    }

    /**
     * Zen begins (1.0.24, kai: "zen mode seems to remember card positions if they
     * were moved before exiting zen mode. Have it reset every time we enter"): every
     * card in its slot, nothing picked out. The last zen's cards went home as the
     * builder woke, drawn there by the fade; this forgets where they had been.
     */
    fun begin() {
        forget()
        if (!arrangement.isEmpty) {
            arrangement.reset()
            arranged++
        }
        gather = 1f
    }

    /** The block card [key] floats with, and its cell there. */
    fun membershipOf(key: Int, home: ZenMembership): ZenMembership = if (arranged < 0) home else arrangement.membershipOf(key, home)

    /** Whether card [key] has been moved out of its block. */
    fun isMoved(key: Int): Boolean = arranged >= 0 && arrangement.isMoved(key)

    /** How high card [key] sits among the cards in zen: the one carried, then those put down, latest on top. */
    fun layerOf(key: Int): Float {
        if (arranged < 0 || deep <= 0f) return 0f
        val layer = arrangement.layerOf(key)
        // Carried cards over everything, in the order they lie among themselves.
        if (key in carrying) return 1_000_000f + layer
        return if (layer == 0) 0f else 2f + layer
    }

    /** Where card [key] is drawn from its place in the deck, in the deck's pixels, now. */
    fun offsetOf(key: Int): Offset {
        // Read, so a layer or a layout that asks is told when the arrangement moves.
        if (arranged < 0) return Offset.Zero
        val (x, y) = arrangement.offsetOf(key)
        val k = deep * gather
        return Offset(x * k, y * k)
    }

    /** Where the pointer is in the window while immersive, for the deck to turn toward in zen. */
    var pointer by mutableStateOf<Offset?>(null)

    /** The window, in pixels, which the deck is centred in. Plain: only layers read it. */
    var window: Size = Size.Zero

    /** Where the deck's cards are, at rest, in window pixels; and where they go in zen. */
    var deck: Rect = Rect.Zero

    /**
     * The deck as zen fits it to the window: its rest rectangle grown by its pieces
     * — out to either side, since each section's pieces open about its middle, and
     * downward, since the sections stack — so the wider the gaps, the smaller the
     * cards, and the whole still sits in the middle of the window.
     */
    val stageRect: Rect
        get() {
            val k = pieceAmount
            val w = pieceGrowth.width * k
            val h = pieceGrowth.height * k
            return Rect(deck.left - w / 2f, deck.top, deck.right + w / 2f, deck.bottom + h)
        }

    /** The point zen's transform scales about: the middle of [stageRect]. */
    val pivot: Offset get() = stageRect.center

    val stage: ZenStage
        get() = stageRect.let { ZenStage.of(it.left, it.top, it.width, it.height, window.width, window.height) }

    /** The deck's rectangle once it has come to the middle. */
    val deckInZen: Rect
        get() {
            val s = stage
            val r = stageRect
            val w = r.width * s.scale
            val h = r.height * s.scale
            return Rect(window.width / 2f - w / 2f, window.height / 2f - h / 2f, window.width / 2f + w / 2f, window.height / 2f + h / 2f)
        }

    companion object {
        /** Zen that never happens: outside immersive mode, and in the studio. */
        val NONE = ZenLayer()
    }
}

val LocalZen = staticCompositionLocalOf { ZenLayer.NONE }

/**
 * Fades with the first stage of zen: chrome — anything that is not a card.
 *
 * Faded is not gone, and a fade alone left every faded control answering the
 * pointer in deep zen (kai, 1.0.24: "zen mode is accidentally trying to click on
 * objects not in zen mode … the tooltips are showing"). So once zen is deep and
 * the chrome has faded all the way, it is taken off the page: still measured, so
 * nothing moves, but not placed — neither drawn nor hit, so a hover, a tooltip or
 * a press goes to the card under the pointer instead. It comes back, still
 * transparent, the moment zen ends, and fades in from there.
 */
@Composable
fun Modifier.zenQuiet(): Modifier {
    val zen = LocalZen.current
    val gone = remember(zen) { derivedStateOf { zen.asleep && zen.quiet >= 0.99f } }
    return graphicsLayer { alpha = (1f - zen.quiet).coerceIn(0f, 1f) }.unplacedWhile(gone)
}

/** Measured as ever, placed only while [gone] is false: read in the placement, so a change re-places and never re-measures. */
private fun Modifier.unplacedWhile(gone: State<Boolean>): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (!gone.value) placeable.place(0, 0)
    }
}

/**
 * Fades with the second: the pool and the inspector, cards and all.
 *
 * Faded all the way, at an alpha of exactly zero, what is inside is not drawn
 * (1.0.92): it would add nothing to a pixel, and drawing it kept every card in the
 * pool redrawing unseen. It is still laid out and still placed, so nothing moves
 * and the pointer meets it as before (`ZenShield` is what keeps it from the pointer).
 */
@Composable
fun Modifier.zenDeep(): Modifier {
    val zen = LocalZen.current
    val unseen = remember(zen) { derivedStateOf { 1f - zen.deep <= 0f } }
    return graphicsLayer { alpha = (1f - zen.deep).coerceIn(0f, 1f) }.drawWithContent { if (!unseen.value) drawContent() }
}
