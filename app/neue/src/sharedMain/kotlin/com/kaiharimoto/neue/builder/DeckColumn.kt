package com.kaiharimoto.neue.builder

import com.kaiharimoto.neue.kit.LocalTouchFirst
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import com.kaiharimoto.mastertool.core.layout.DeckLabels
import com.kaiharimoto.mastertool.core.layout.LabelPlace
import com.kaiharimoto.mastertool.core.motion.ZenArrangement
import com.kaiharimoto.mastertool.core.motion.ZenHome
import com.kaiharimoto.mastertool.core.motion.ZenMembership
import com.kaiharimoto.neue.zen.ZenLayer
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.zen.zenShadow
import kotlin.math.round
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.kaiharimoto.neue.kit.onPointer
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.kaiharimoto.mastertool.core.motion.ZenFloat
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.neue.zen.LocalZen
import com.kaiharimoto.neue.zen.zenQuiet
import com.kaiharimoto.mastertool.core.input.MouseTarget
import com.kaiharimoto.mastertool.core.motion.LeanPose
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.MuType
import androidx.compose.ui.text.rememberTextMeasurer
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.deck.DeckLenses
import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.neue.zen.zenGlow
import com.kaiharimoto.mastertool.core.layout.SectionFit
import com.kaiharimoto.mastertool.core.layout.SectionFitRequest
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Selection
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.Marker
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.FoilGlyph
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion

/**
 * Flush: kai asked for no gap between cards. While a lens is on, the deck breaks
 * into pieces by its groups (`GroupPieces`, kai's 1.0.15 picture): flush inside
 * a piece, [PIECE_GAP] between two, each piece outlined [FRAME] wide in its
 * group's colour in the gap round it ([drawPieces]).
 */
private val PIECE_GAP = 28.dp
private val FRAME = 5.dp

/** The tab a group's name is written on, rising from its piece's top edge (1.0.18). Fits inside a gap. */
private val NAME_TAB = 17.dp
private val LENS_ROW = 40.dp
private val LABEL_ROW = 24.dp
private val LABEL_GUTTER = 104.dp
private val GRID_PAD = 6.dp
private val SIDE_PAD = 16.dp
private val RULE = 1.dp

/**
 * How card [position] floats in zen (kai, 1.0.12: "float in groups … flutter
 * diagonally like scales"): with its block — its lens group, or its section
 * when there is no lens — drifting as one and fluttering corner to corner. A
 * card put down beside another joins that card's block (1.0.14, `ZenSnap`); one
 * set down on its own keeps floating with its own, so it never jumps.
 */
private fun zenFloat(zen: ZenLayer, section: DeckSection, position: Int, columns: Int, keyId: String?, roleKey: String?, seconds: Float): LeanPose {
    // With zen's pieces out, a card floats with its Roles group; otherwise with the lens's.
    val m = zen.membershipOf(ZenArrangement.key(section.ordinal, position), homeBlock(section, position, columns, if (zen.groups) roleKey else keyId))
    return ZenFloat.inBlock(m.group, m.col, m.row, seconds)
}

/** The block a card floats with in its own slot, and its cell there. */
private fun homeBlock(section: DeckSection, position: Int, columns: Int, keyId: String?): ZenMembership =
    ZenMembership(section.ordinal * 7_919 + (keyId?.hashCode() ?: 0), position % columns, position / columns)

/** How much higher a card being carried in zen floats than its neighbours, in card widths. */
private const val HELD_LIFT = 0.1f

/** Row widths: the tablet's, because a decklist is quoted in tens and fifteens whatever the screen. */
internal fun columnsOf(section: DeckSection) = if (section == DeckSection.MAIN) 10 else 15

/**
 * The deck: main, extra and side, all on screen at once and never scrolled.
 *
 * Sized by the tablet's own `DeckFitter.plan` — row widths in, one card size
 * out, every section the same width — because "layout is solved, not
 * negotiated" is as true of a 27-inch monitor as of a tablet. Sections are
 * divided by rules, not gaps (law 6).
 *
 * Every pixel of chrome is a pixel off every card, so there is as little as
 * reads. The main deck has one row: the **Groups** button in its corner, its
 * name and count, and the lens. The extra and side decks have none of their
 * own: their names go in whatever the deck has to spare (`DeckLabels`) — beside
 * the cards when the deck is limited by its height, over them when it is limited
 * by its width. The lens's keys stand down the right in [GroupsPanel] when asked for.
 */
@Composable
fun DeckColumn(state: DeckBuilderState, neue: NeueState, drag: NeueDrag, modifier: Modifier = Modifier) {
    val panel = groupsOn(state)
    Row(modifier) {
        DeckBody(state, neue, drag, Modifier.weight(1f).fillMaxHeight())
        if (panel) GroupsPanel(state, neue, Modifier.zenQuiet())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeckBody(state: DeckBuilderState, neue: NeueState, drag: NeueDrag, modifier: Modifier) {
    val density = LocalDensity.current
    val lensOn = state.lens != Lens.DECK || state.groupDraft != null
    val crack by animateFloatAsState(if (lensOn) 1f else 0f, tween(MuMotion.SLOW, easing = MuMotion.ease), label = "crack")
    val zen = LocalZen.current
    val motion = rememberDeckMotion(drag, zen)
    val origin = remember { floatArrayOf(0f, 0f) }
    val grids = remember { mutableMapOf<DeckSection, Rect>() }

    BoxWithConstraints(
        modifier
            .onGloballyPositioned { val p = it.positionInWindow(); origin[0] = p.x; origin[1] = p.y }
            .onPointer(PointerEventType.Move) { e -> e.changes.firstOrNull()?.let { motion.hover = Offset(origin[0] + it.position.x, origin[1] + it.position.y) } }
            .onPointer(PointerEventType.Enter) { e -> e.changes.firstOrNull()?.let { motion.hover = Offset(origin[0] + it.position.x, origin[1] + it.position.y) } }
            .onPointer(PointerEventType.Exit) { motion.hover = null }
            // The wheel sizes the deck (kai, 1.0.17): down, the cards shrink toward the middle
            // with paper round them; up, back to the size that fills the column. With the
            // groups on, Shift and the wheel open and close the gaps between them. In deep zen
            // the wheel is zen's (the gaps there), so it is left alone here.
            .onPointer(PointerEventType.Scroll) { e ->
                if (neue.zen == ZenPhase.DEEP) return@onPointer
                val d = e.changes.firstOrNull()?.scrollDelta ?: return@onPointer
                val step = if (d.y != 0f) d.y else d.x
                if (step == 0f) return@onPointer
                if (e.keyboardModifiers.isShiftPressed && lensOn) {
                    neue.update(debounce = true) { it.copy(groupGap = it.groupGap - step * 0.15f) }
                } else {
                    neue.update(debounce = true) { it.copy(deckZoom = it.deckZoom - step * 0.04f) }
                }
                e.changes.forEach { it.consume() }
            }
            // Two fingers are the wheel on a tablet (1.3.0): pinched, the cards shrink toward
            // the middle and grow back; in deep zen the gaps between the groups open and close,
            // as the wheel does there. A card under one of the fingers lets the gesture go.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    if (!first.byFinger) return@awaitEachGesture
                    var from = 0f
                    var start = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.filter { it.pressed }
                        if (fingers.isEmpty()) break
                        if (fingers.size < 2) continue
                        val apart = (fingers[0].position - fingers[1].position).getDistance()
                        val deep = neue.zen == ZenPhase.DEEP
                        if (from == 0f) {
                            from = apart
                            start = if (deep) zen.gapScale else neue.prefs.deckZoom
                        } else if (from > 0f) {
                            val ratio = apart / from
                            if (deep) {
                                zen.groups = true
                                zen.gapScale = (start * ratio).coerceIn(com.kaiharimoto.neue.ZEN_GAP_MIN, com.kaiharimoto.neue.ZEN_GAP_MAX)
                            } else {
                                neue.update(debounce = true) { it.copy(deckZoom = start * ratio) }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            // Zen: the deck comes to the middle of the window and grows into it, about
            // its own centre — a transform, never a re-fit, so nothing jumps on the way
            // there or back.
            .graphicsLayer {
                val a = zen.deep
                if (a > 0f && zen.deck.width > 0f) {
                    val stage = zen.stage
                    val scale = 1f + (stage.scale - 1f) * a
                    val pivot = zen.pivot
                    val cx = pivot.x - origin[0]
                    val cy = pivot.y - origin[1]
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = cx * (1f - scale) + stage.dx * a
                    translationY = cy * (1f - scale) + stage.dy * a
                }
            },
    ) {
        // The extra and the side deck each on a switch of its own (1.0.17).
        val sections = buildList {
            add(DeckSection.MAIN)
            if (neue.prefs.extraVisible) add(DeckSection.EXTRA)
            if (neue.prefs.sideVisible) add(DeckSection.SIDE)
        }
        // The deck in pieces by the lens (GroupPieces): what width and height the gaps
        // between them take, declared to the fitter so the cards pay for them honestly.
        val gapPx = with(density) { PIECE_GAP.toPx() } * neue.prefs.groupGap
        val zenGapPx = with(density) { PIECE_GAP.toPx() }
        // Zen's pieces are always the Roles groups (kai, 1.0.15): the ones the user draws.
        val rolePieces = sections.map { section ->
            val ids = state.deck[section]
            val roleKeys = remember(ids, state.groupsWithDraft, state.index) {
                DeckLenses.key(Lens.ROLES, ids, state.index::byId, state.groupsWithDraft, state.format).keyOfCell
            }
            roleKeys to remember(roleKeys) { GroupPieces.of(roleKeys, columnsOf(section)) }
        }
        // How far down each section's zen pieces start, and how much the whole deck grows
        // with them fully apart at one gap: zen fits that grown deck to the window.
        val zenAbove = rolePieces.runningFold(0f) { acc, (_, layout) -> acc + layout.spanY * zenGapPx }
        SideEffect {
            zen.pieceGrowth = androidx.compose.ui.geometry.Size(
                rolePieces.maxOfOrNull { it.second.spanX * zenGapPx } ?: 0f,
                zenAbove.last(),
            )
        }
        val zoom = neue.prefs.deckZoom
        // Turned off, the pieces close from where they were rather than vanishing: the
        // last ones are kept while the gaps run down, unless the section has changed size.
        val kept = remember { HashMap<DeckSection, PieceLayout>() }
        val pieces = sections.map { section ->
            val keys = state.keying(section).keyOfCell
            val live = remember(keys, columnsOf(section)) { GroupPieces.of(keys, columnsOf(section)) }
            val previous = kept[section]
            val used = if (lensOn || previous == null || previous.piece.size != keys.size) live else previous
            kept[section] = used
            used
        }
        // The lens row stands still over the deck whatever the wheel does (kai, 1.0.18):
        // it is laid out once at the top, and the deck is fitted to what is below it.
        val deckHeight = maxHeight - LENS_ROW
        fun fitAt(z: Float) = with(density) {
            DeckLabels.place(
                availableWidth = (maxWidth - SIDE_PAD * 2).toPx() * z,
                availableHeight = deckHeight.toPx() * z,
                aspectRatio = CARD_RATIO,
                gutter = LABEL_GUTTER.toPx(),
                rowHeight = LABEL_ROW.toPx(),
                requests = sections.mapIndexed { i, section ->
                    SectionFitRequest(
                        count = state.deck[section].size,
                        columns = columnsOf(section),
                        baselineCount = if (section == DeckSection.MAIN) section.minSize else section.maxSize,
                        spacing = 0f,
                        chromeHeight = (GRID_PAD * 2 + RULE).toPx(),
                        extraWidth = pieces[i].spanX * gapPx * crack,
                        extraHeight = pieces[i].spanY * gapPx * crack + (if (pieces[i].pieces > 1) NAME_TAB.toPx() * crack else 0f),
                    )
                },
                labelled = sections.map { it != DeckSection.MAIN },
            )
        }
        val placed = fitAt(zoom)
        // The row's inset is the deck's edge at the size that fills the column, so it
        // does not creep inward as the wheel shrinks the cards.
        val fullWidth = with(density) { (if (zoom < 0.999f) fitAt(1f) else placed).fit.contentWidth.toDp() }
        val rowInset = maxOf((maxWidth - fullWidth) / 2, SIDE_PAD)
        val fit = placed.fit
        val contentWidth = with(density) { fit.contentWidth.toDp() }
        // Where the grids start, from the column's left edge: every section is the same width and centred.
        val gridLeft = (maxWidth - contentWidth) / 2
        motion.cardWidth = fit.sections.firstOrNull()?.cardWidth ?: 100f
        // The pool draws its cards the size of these, unless told otherwise.
        val mainWidth = with(density) { (fit.sections.firstOrNull()?.cardWidth ?: 0f).toDp() }
        // At the size that fills the column, whatever the wheel has made of the deck.
        SideEffect { if (zen.deep == 0f && mainWidth > 0.dp) neue.deckCardWidth = mainWidth / zoom }
        val mainIds = state.deck[DeckSection.MAIN]
        val mainRefused = drag.hover?.let { it.section == DeckSection.MAIN && drag.held != null && !it.accepted } == true
        val mainOut = mainIds.size > DeckSection.MAIN.maxSize || mainIds.size < DeckSection.MAIN.minSize
        LensRow(state, neue, "${mainIds.size} · ${DeckSection.MAIN.minSize}–${DeckSection.MAIN.maxSize}", mainOut, mainRefused, inset = rowInset)
        Column(
            Modifier.padding(top = LENS_ROW).fillMaxSize().let { if (!fit.fits) it.verticalScroll(rememberScrollState()) else it },
            // Immersive: past the page's fixed strip at the top (IMMERSIVE_TOP), whatever
            // height the deck does not need is shared above and below it, so the deck sits
            // in the middle of the screen (kai, 1.0.12: all of it above was too much).
            // A deck the wheel has made smaller stays in the middle too, with paper round it.
            verticalArrangement = if (neue.immersive || zoom < 0.999f) Arrangement.Center else Arrangement.Top,
        ) {
            sections.forEachIndexed { i, section ->
                DeckSectionPane(
                    state = state,
                    neue = neue,
                    drag = drag,
                    section = section,
                    fit = fit.sections[i],
                    contentWidth = contentWidth,
                    gridLeft = gridLeft,
                    labels = placed.place,
                    pieces = pieces[i],
                    gapPx = gapPx,
                    roleKeys = rolePieces[i].first,
                    rolePieces = rolePieces[i].second,
                    zenGapPx = zenGapPx,
                    zenAbove = zenAbove[i],
                    crack = crack,
                    motion = motion,
                    onGrid = { rect ->
                        // Measured at rest only: in zen the grids are inside the transform, and a
                        // deck measured there would chase its own reflection.
                        if (zen.deep == 0f) {
                            grids[section] = rect
                            // Only the sections on show: a hidden one keeps no place in the stone.
                            zen.deck = grids.filterKeys { it in sections }.values.filter { it.width > 0f && it.height > 0f }.reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) } ?: Rect.Zero
                        }
                    },
                )
            }
        }
    }
}

/** The main deck's one row: the Groups button in the deck's corner, its name and count, and the lens. */
@Composable
private fun LensRow(state: DeckBuilderState, neue: NeueState, count: String, outOfRange: Boolean, refused: Boolean, inset: Dp) {
    val c = Mu.colors
    BoxWithConstraints(Modifier.zenQuiet().fillMaxWidth().height(LENS_ROW).padding(start = inset, end = inset)) {
    // A narrow deck (the rail pinned, the pool, the inspector and the groups all out)
    // gives up the section's name first, then the tabs' long words, and never a tab.
    val narrow = maxWidth < 860.dp
    val tight = maxWidth < 720.dp
    Row(
        Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tight) 8.dp else 12.dp),
    ) {
        val open = groupsOn(state)
        Tip(if (open) "Hide the groups: the plain deck" else "Your groups: the deck in pieces, and each group to edit beside it", kbd = "K") {
            BoxToggle("Groups", open) {
                if (open) {
                    state.cancelGroupDraft()
                    state.useLens(Lens.DECK)
                } else {
                    state.useLens(Lens.ROLES)
                }
            }
        }
        FoilToggle(neue)
        // The extra and the side deck, each on its own switch (kai, 1.0.17): the main deck
        // has whatever they give up. Short words when the row is tight.
        Tip(if (neue.prefs.extraVisible) "Hide the extra deck" else "Show the extra deck") {
            BoxToggle(if (tight) "Ex" else "Extra", neue.prefs.extraVisible) { neue.update { it.copy(extraVisible = !it.extraVisible) } }
        }
        Tip(if (neue.prefs.sideVisible) "Hide the side deck" else "Show the side deck") {
            BoxToggle(if (tight) "Si" else "Side", neue.prefs.sideVisible) { neue.update { it.copy(sideVisible = !it.sideVisible) } }
        }
        if (!narrow) Micro("Main deck", color = c.ink70)
        // At its tightest the row keeps the count only when it is something to act on.
        if (!tight || outOfRange) Mono(if (outOfRange) "✕ $count" else count, color = if (outOfRange) c.ink else c.ink70)
        Box(Modifier.weight(1f))
        if (refused) Micro("✕ Not allowed here", color = c.ink)
        // The other ways to see the deck in pieces. The Roles lens is the Groups button's.
        Segmented(state.lens, LENS_TABS, { if (tight) shortName(it) else it.displayName }, state::useLens, small = true)
    }
    }
}

/** A tab's name when the row is tight. */
private fun shortName(lens: Lens): String = when (lens) {
    Lens.ARCHETYPE -> "Arch."
    Lens.LEGALITY -> "Legal"
    else -> lens.displayName
}

/**
 * Whether the groups are on (kai, 1.0.15): the Roles lens, or a group being drawn
 * up. It is one switch — the Groups button — for the pieces, their colour and the
 * panel that edits them; off, the deck is plain.
 */
internal fun groupsOn(state: DeckBuilderState): Boolean = state.lens == Lens.ROLES || state.groupDraft != null

/** The lens tabs over the main deck: every way to see the deck in pieces but the user's own groups. */
internal val LENS_TABS: List<Lens> = Lens.entries - Lens.ROLES

/**
 * The foil on every card face, on or off: a boxed button the size of Groups
 * beside it, carrying a small card in the foil itself (`FoilGlyph`), whose light
 * follows the pointer over the button as a card's does.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FoilToggle(neue: NeueState) {
    val c = Mu.colors
    val on = neue.prefs.foil != Foils.OFF
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var feel by remember { mutableStateOf<Offset?>(null) }
    Tip(if (on) "Foil off: plain card faces" else "Foil on: the holographic border on every card") {
        Box(
            Modifier
                .size(28.dp)
                .background(animatedColor(if (hovered) c.ink06 else Color.Transparent))
                .border(1.dp, c.ink)
                .hoverable(source)
                .onPointer(PointerEventType.Move) { e ->
                    val p = e.changes.first().position
                    feel = Offset(p.x / size.width * 2f - 1f, p.y / size.height * 2f - 1f)
                }
                .onPointer(PointerEventType.Exit) { feel = null }
                .cursorPointer(caption = if (on) "Foil off" else "Foil on")
                .clickable(interactionSource = source, indication = null) {
                    neue.update { it.copy(foil = if (on) Foils.OFF else Foils.HOLO) }
                },
            contentAlignment = Alignment.Center,
        ) {
            FoilGlyph(on, feel, ink = c.ink, paper = c.paper, modifier = Modifier.size(12.dp, 17.dp))
        }
    }
}

/** A boxed button that stays pressed: ink when on, a ruled box when off. */
@Composable
private fun BoxToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .height(28.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .border(1.dp, c.ink)
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Micro(label, color = if (on) c.paper else c.ink)
    }
}

/** A section's name and count, for the extra and side decks, wherever `DeckLabels` put it. */
@Composable
private fun SectionLabel(section: DeckSection, count: String, outOfRange: Boolean, refused: Boolean, modifier: Modifier, stacked: Boolean) {
    val c = Mu.colors
    val parts: @Composable () -> Unit = {
        Micro("${section.displayName} deck", color = c.ink70)
        Mono(if (outOfRange) "✕ $count" else count, color = if (outOfRange) c.ink else c.ink70)
        if (refused) Micro("✕ Not allowed", color = c.ink)
    }
    if (stacked) {
        Column(modifier.zenQuiet(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { parts() }
    } else {
        Row(modifier.zenQuiet(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { parts() }
    }
}

@Composable
private fun DeckSectionPane(
    state: DeckBuilderState,
    neue: NeueState,
    drag: NeueDrag,
    section: DeckSection,
    fit: SectionFit,
    contentWidth: Dp,
    gridLeft: Dp,
    labels: LabelPlace,
    pieces: PieceLayout,
    gapPx: Float,
    roleKeys: List<String?>,
    rolePieces: PieceLayout,
    zenGapPx: Float,
    zenAbove: Float,
    crack: Float,
    motion: DeckMotion,
    onGrid: (Rect) -> Unit,
) {
    val c = Mu.colors
    val density = LocalDensity.current
    val zen = LocalZen.current
    val ids = state.deck[section]
    val hover = drag.hover?.takeIf { it.section == section && drag.held != null }
    val count = ids.size
    val rangeText = if (section == DeckSection.MAIN) "${section.minSize}–${section.maxSize}" else "0–${section.maxSize}"
    val outOfRange = count > section.maxSize || count < section.minSize
    val refused = hover != null && !hover.accepted
    val keying = state.keying(section)
    val labelRoom = if (pieces.pieces > 1) with(density) { NAME_TAB.toPx() } else 0f
    val placer = PiecePlacer(fit.columns, fit.cardWidth, fit.cardHeight, gapPx, pieces, crack, rolePieces, zenGapPx, zenAbove, labelRoom)
    val measurer = rememberTextMeasurer()
    val tabStyle = MuType.micro(LocalMuFonts.current).copy(fontSize = 10.sp)
    val restPlaces = List(ids.size) { placer.rest(it) }

    // The whole pane accepts a drop, label included: aiming at a label is
    // aiming at the section. The grid's own geometry is measured separately,
    // and the two are joined whenever either moves.
    val laid = remember(section) { PaneLayout() }
    fun publish() {
        val grid = laid.grid ?: return
        drag.register(section, grid.copy(bounds = laid.pane))
    }

    // A card added without the grid moving fires no layout callback, and a stale
    // count would put the drop marker one card short.
    SideEffect {
        laid.grid = laid.grid?.copy(
            count = ids.size,
            columns = fit.columns,
            cardWidth = fit.cardWidth,
            cardHeight = fit.cardHeight,
            spacing = 0f,
            placed = restPlaces,
        )
        publish()
        // Where each card goes in zen, relative to where it rests, for picking and snapping there.
        ids.indices.forEach { p ->
            val key = ZenArrangement.key(section.ordinal, p)
            zen.pieceBase[key] = placer.zenBase(p)
            zen.pieceShift[key] = placer.zenPieces(p)
        }
    }

    // A section hidden (1.0.15) takes no drops and has no slots in zen.
    DisposableEffect(section) {
        onDispose {
            drag.unregister(section)
            zen.homes.keys.removeAll { it / 1_000 == section.ordinal }
        }
    }

    // In zen a card carried out of this section, or put down beyond it, is drawn over
    // the sections below it: the pane is as high as its highest card.
    val paneLayer = if (zen.deep > 0f) ids.indices.maxOfOrNull { zen.layerOf(ZenArrangement.key(section.ordinal, it)) } ?: 0f else 0f
    Column(
        Modifier
            .zIndex(paneLayer)
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                laid.pane = coords.boundsInWindow()
                publish()
            },
    ) {
        if (section != DeckSection.MAIN && labels == LabelPlace.ROWS) {
            SectionLabel(
                section, "$count · $rangeText", outOfRange, refused,
                Modifier.fillMaxWidth().height(LABEL_ROW).padding(start = maxOf(gridLeft, SIDE_PAD)),
                stacked = false,
            )
        }

        val cardH = with(density) { fit.cardHeight.toDp() }
        val gridHeight = with(density) { fit.gridHeight.toDp() }
        // The card the bump is over is drawn above its neighbours, so its lift is never
        // tucked under the next card along. Recomposes when the pointer crosses a card, not every frame.
        val onTop by remember(section, placer.cardWidth, ids.size, restPlaces) {
            derivedStateOf {
                val p = motion.point() ?: return@derivedStateOf -1
                val o = laid.grid?.origin ?: return@derivedStateOf -1
                placer.at(p - o, ids.size)
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = GRID_PAD), contentAlignment = Alignment.TopCenter) {
            if (section != DeckSection.MAIN && labels == LabelPlace.GUTTER) {
                SectionLabel(
                    section, "$count · $rangeText", outOfRange, refused,
                    Modifier.align(Alignment.TopStart).width((gridLeft - 16.dp).coerceAtLeast(0.dp)),
                    stacked = true,
                )
            }
            Box(
                Modifier
                    .size(contentWidth, gridHeight)
                    .onGloballyPositioned { coords ->
                        val at = coords.positionInWindow()
                        if (zen.deep == 0f) {
                            laid.restOrigin = at
                            // Every card's slot at rest, for a card let go in zen to snap back to or beside.
                            val keysHere = zen.homes.keys.filter { it / 1_000 == section.ordinal }
                            keysHere.forEach { zen.homes.remove(it) }
                            ids.indices.forEach { p ->
                                zen.homes[ZenArrangement.key(section.ordinal, p)] = ZenHome(
                                    x = at.x + restPlaces[p].x,
                                    y = at.y + restPlaces[p].y,
                                    width = fit.cardWidth,
                                    height = fit.cardHeight,
                                    membership = homeBlock(section, p, fit.columns, keying.keyAt(p)),
                                )
                            }
                        }
                        // Only the cards: an empty side deck is not part of the stone.
                        if (ids.isNotEmpty()) onGrid(Rect(at.x, at.y, at.x + coords.size.width, at.y + fit.gridHeight)) else onGrid(Rect.Zero)
                        laid.grid = GridGeometry(
                            bounds = coords.boundsInWindow(),
                            origin = coords.positionInWindow(),
                            columns = fit.columns,
                            cardWidth = fit.cardWidth,
                            cardHeight = fit.cardHeight,
                            spacing = 0f,
                            count = ids.size,
                            placed = restPlaces,
                        )
                        publish()
                    }
                    .let { if (hover?.accepted == true) it.border(1.dp, c.ink) else it },
            ) {
                // Zen: every card's shadow on the table, under all of them.
                Canvas(Modifier.matchParentSize()) {
                    val deep = zen.deep
                    if (deep <= 0f) return@Canvas
                    val pieceAmount = zen.groupsAmount
                    val drawn = ids.indices.map { position ->
                        val key = ZenArrangement.key(section.ordinal, position)
                        val o = zen.offsetOf(key)
                        val drift = zenFloat(zen, section, position, fit.columns, keying.keyAt(position), roleKeys.getOrNull(position), zen.time)
                        val x = placer.x(position, deep, zen.pieceAmount) + o.x + drift.dx * fit.cardWidth * deep
                        val y = placer.y(position, deep, zen.pieceAmount) + o.y + drift.dy * fit.cardWidth * deep
                        val lift = drift.lift + if (key in zen.carrying) HELD_LIFT else 0f
                        zenShadow(Rect(x, y, x + fit.cardWidth, y + fit.cardHeight), lift, deep, c.ink)
                        Rect(x, y, x + fit.cardWidth, y + fit.cardHeight)
                    }
                    // Zen's pieces, when they are asked for: each Roles group's outline glows,
                    // faintly and prismatically, in its own colour (kai, 1.0.15).
                    val glow = pieceAmount * deep
                    if (glow > 0.01f) {
                        val roleKeying = state.groupsWithDraft
                        ids.indices.forEach { position ->
                            val keyId = roleKeys.getOrNull(position) ?: return@forEach
                            val group = roleKeying.byId(keyId) ?: return@forEach
                            val key = ZenArrangement.key(section.ordinal, position)
                            val mine = zen.offsetOf(key)
                            // A side is on the outline where the piece ends — or where the card
                            // beside it in the piece has been carried away.
                            val sides = rolePieces.outerSides(position)
                            val neighbours = intArrayOf(position - 1, position - fit.columns, position + 1, position + fit.columns)
                            for (s in 0..3) {
                                if (!sides[s] && zen.offsetOf(ZenArrangement.key(section.ordinal, neighbours[s])) != mine) sides[s] = true
                            }
                            zenGlow(drawn[position], sides, GroupMarkers.hue(group.color), glow, zen.time, fit.cardWidth)
                        }
                    }
                }
                if (!keying.isEmpty && crack > 0.01f) {
                    Canvas(Modifier.matchParentSize()) {
                        // The builder's outlines give way to zen's glow.
                        val fade = (1f - zen.deep).coerceIn(0f, 1f)
                        drawPieces(
                            keys = keying.keyOfCell,
                            pieces = pieces,
                            at = { restPlaces[it] },
                            cardWidth = fit.cardWidth,
                            cardHeight = fit.cardHeight,
                            frame = FRAME.toPx(),
                            colorOf = { id -> keying.keyById(id)?.let { GroupMarkers.paint(it.paint, c.ink) } ?: c.ink },
                            alphaOf = { id -> crack * fade * if (state.isolatedKey != null && state.isolatedKey != id) 0.18f else 1f },
                            labels = Labels(measurer, tabStyle, NAME_TAB.toPx()) { id -> keying.keyById(id)?.label.orEmpty() },
                        )
                    }
                }

                ids.forEachIndexed { position, id ->
                    val card = state.index.byId(id)
                    val keyId = keying.keyAt(position)
                    val key = keying.keyById(keyId)
                    val held = drag.held?.let { it.from == section && it.index == position } == true
                    val covered = state.isolatedKey != null && state.isolatedKey != keyId
                    val left = restPlaces[position].x
                    val top = restPlaces[position].y
                    // Both edges snapped to the pixel, not the corner and the size apart:
                    // truncating one and rounding the other opened a one-pixel seam
                    // between flush cards wherever the pitch was fractional.
                    val l = round(left).toInt()
                    val t = round(top).toInt()
                    val r = round(left + fit.cardWidth).toInt()
                    val b = round(top + fit.cardHeight).toInt()
                    val zenKey = ZenArrangement.key(section.ordinal, position)
                    Box(
                        Modifier
                            .offset {
                                val o = zen.offsetOf(zenKey)
                                // In zen the pieces may open or close; at rest this is exactly (l, t).
                                val d = zen.deep
                                val x = if (d > 0f) round(placer.x(position, d, zen.pieceAmount)).toInt() else l
                                val y = if (d > 0f) round(placer.y(position, d, zen.pieceAmount)).toInt() else t
                                IntOffset(x + o.x.roundToInt(), y + o.y.roundToInt())
                            }
                            .size(with(density) { (r - l).toDp() }, with(density) { (b - t).toDp() })
                            .zIndex(zen.layerOf(zenKey).takeIf { it > 0f } ?: if (position == onTop) 1f else 0f)
                            .alpha(if (held) 0.4f else if (covered) 0.3f else 1f),
                    ) {
                        if (card == null) {
                            Box(Modifier.fillMaxSize().background(c.ink06)) {
                                Hatch(Modifier.fillMaxSize(), color = c.ink25)
                                Box(Modifier.align(Alignment.Center).background(c.paper).padding(2.dp)) {
                                    Mono(id.value.toString(), color = c.ink, size = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp))
                                }
                            }
                        } else {
                            val selected = (neue.selection as? Selection.InDeck)?.let { it.section == section && it.index == position } == true
                            val press = rememberPress()
                            NeueCard(
                                card = card,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .cardPointer(
                                        card = card,
                                        neue = neue,
                                        drag = drag,
                                        from = section,
                                        index = position,
                                        target = MouseTarget.DECK,
                                        press = press,
                                        onAction = { action, at -> CardActions.onDeck(action, at, card, section, position, state, neue) },
                                        zenKey = zenKey,
                                    ),
                                motion = {
                                    // Where the card is at rest: zen's lean works out from there where it has gone.
                                    val o = laid.restOrigin ?: laid.grid?.origin ?: Offset.Zero
                                    val moved = zen.offsetOf(zenKey)
                                    val lean = if (held) LeanPose.REST else motion.poseAt(Offset(o.x + left + moved.x + fit.cardWidth / 2f, o.y + top + moved.y + fit.cardHeight / 2f))
                                    val pressed = press.pose()
                                    val deep = zen.deep
                                    val drift = if (deep > 0f) zenFloat(zen, section, position, fit.columns, keyId, roleKeys.getOrNull(position), zen.time).times(deep) else LeanPose.REST
                                    val carried = if (zenKey in zen.carrying) HELD_LIFT * deep else 0f
                                    lean.copy(lift = lean.lift + pressed.lift + carried) + drift
                                },
                                format = state.format,
                                // The artwork chip on hover — not in deep zen, where a press carries the card.
                                artChip = neue.zen != ZenPhase.DEEP,
                                // In deep zen, the cards picked out to move together.
                                selected = selected && neue.zen != ZenPhase.DEEP || zen.isSelected(zenKey),
                                foil = neue.prefs.foil,
                                // The group's name is on its piece's tab now (1.0.18), not a mark on every card.
                                marker = null,
                            )
                        }
                    }
                }

                // Where a drop will land: a 2px ink bar in the gap, never a shifted grid.
                if (hover?.accepted == true) {
                    val at = hover.index.coerceIn(0, ids.size)
                    val mark = when {
                        ids.isEmpty() -> Offset.Zero
                        at < ids.size -> restPlaces[at]
                        else -> restPlaces[ids.size - 1] + Offset(fit.cardWidth, 0f)
                    }
                    Box(
                        Modifier
                            .offset { IntOffset((mark.x - 1.dp.toPx()).roundToInt(), mark.y.roundToInt()) }
                            .size(2.dp, cardH)
                            .background(c.ink),
                    )
                }

                if (ids.isEmpty()) {
                    Box(Modifier.fillMaxSize().zenQuiet(), contentAlignment = Alignment.Center) {
                        Help(
                            when {
                                LocalTouchFirst.current && (section == DeckSection.SIDE) != neue.prefs.poolToSide -> "Press and hold a card in the pool for the side deck, or drag it here"
                                LocalTouchFirst.current -> "Double-tap a card in the pool, or drag it here"
                                (section == DeckSection.SIDE) != neue.prefs.poolToSide -> "Shift right-click a card in the pool, or drag it here"
                                else -> "Right-click a card in the pool, or drag it here"
                            },
                        )
                    }
                }
            }
        }
        com.kaiharimoto.neue.kit.HRule(Modifier.zenQuiet(), color = c.ink)
    }
}

/** Where a pane and its grid were last laid out, in window pixels. */
private class PaneLayout {
    var pane: Rect = Rect.Zero
    var grid: GridGeometry? = null

    /** The grid's origin measured at rest, outside zen's transform. */
    var restOrigin: Offset? = null
}

