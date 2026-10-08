package com.kaiharimoto.neue.builder

import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPoint
import com.kaiharimoto.neue.ai.chessy.chessySpot
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.input.DeskWords
import com.kaiharimoto.mastertool.core.input.TwoFinger
import com.kaiharimoto.mastertool.core.haptics.DeskEvent
import com.kaiharimoto.mastertool.ui.deckbuilder.RevealRequest
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.releasesTypingOnFinger
import com.kaiharimoto.neue.kit.LocalTouchFirst
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Animatable
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
import com.kaiharimoto.mastertool.core.layout.DeckFitter
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Density
import com.kaiharimoto.mastertool.core.layout.DeckZoom
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
import androidx.compose.runtime.key
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import com.kaiharimoto.mastertool.core.model.CardId
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import com.kaiharimoto.mastertool.core.motion.ZenFloat
import com.kaiharimoto.mastertool.core.motion.ZenPhase
import com.kaiharimoto.mastertool.core.motion.ZenLabels
import com.kaiharimoto.mastertool.core.deck.LensKeying
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
import com.kaiharimoto.mastertool.core.layout.GroupArrangement
import com.kaiharimoto.mastertool.core.layout.BandLayout
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

/**
 * The tab a group's name is written on, rising from its piece's top edge (1.0.18). Fits
 * inside a gap. Measured off the tab's own type, so a larger text size never clips it
 * (touch swarm, rec 26); 17dp at the desk's text size.
 */
@Composable
private fun nameTab(): Dp = with(LocalDensity.current) { maxOf(17.dp, 10.sp.toDp() * 1.3f + 4.dp) }
private val LENS_ROW = 40.dp
private val LABEL_ROW = 24.dp
private val LABEL_GUTTER = 104.dp

/**
 * Paper kept under the last section (1.0.41, kai: "a bit of a safety zone so the side
 * deck doesn't get too close to the bottom edge for visual focus"). Not on a phone,
 * whose deck ends at the pool's dock.
 */
private val BOTTOM_SAFE = 28.dp
private val GRID_PAD = 6.dp

/**
 * Breathing room each side of the rule between two sections (1.0.41, kai: "the extra
 * deck and side deck are too close to each other and the main deck"), over the grid's
 * own [GRID_PAD]: on the desk the rows of two sections stand 33 dp apart, not 13.
 */
private fun air(phone: Boolean) = if (phone) 5.dp else 10.dp

/** Paper over a section's grid: its pad, and air when a section stands above it. */
private fun padTop(section: DeckSection, phone: Boolean) = GRID_PAD + if (section == DeckSection.MAIN) 0.dp else air(phone)

/** Paper under a section's grid, down to its rule. */
private fun padBottom(phone: Boolean) = GRID_PAD + air(phone)

/** All the height a section spends on other than its cards: its paper and its rule. */
private fun chromeOf(section: DeckSection, phone: Boolean) = padTop(section, phone) + padBottom(phone) + RULE
private val SIDE_PAD = 16.dp
private val RULE = 1.dp

/** The narrowest the main deck's row is drawn, whatever the deck's width: room for every button (1.0.38). */
private val ROW_LEAST = 720.dp

/** The most of a section's width its pieces' gaps may take before they give way (1.0.38). */
private const val GAP_SHARE = 0.25f

/**
 * How card [position] floats in zen (kai, 1.0.12: "float in groups … flutter
 * diagonally like scales"): with its block — its lens group, or its section
 * when there is no lens — drifting as one and fluttering corner to corner. A
 * card put down beside another joins that card's block (1.0.14, `ZenSnap`); one
 * set down on its own keeps floating with its own, so it never jumps.
 */
private fun zenFloat(zen: ZenLayer, section: DeckSection, position: Int, cells: PieceLayout, keyId: String?, roleKey: String?, seconds: Float): LeanPose {
    // With zen's pieces out, a card floats with its Roles group; otherwise with the lens's.
    val m = zen.membershipOf(ZenArrangement.key(section.ordinal, position), homeBlock(section, position, cells, if (zen.groups) roleKey else keyId))
    return ZenFloat.inBlock(m.group, m.col, m.row, seconds)
}

/** The block a card floats with in its own slot, and its cell there ([cells]: a deck in bands puts a card anywhere). */
private fun homeBlock(section: DeckSection, position: Int, cells: PieceLayout, keyId: String?): ZenMembership =
    ZenMembership(section.ordinal * 7_919 + (keyId?.hashCode() ?: 0), cells.col(position), cells.row(position))

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
    // The lens tabs are gone (1.0.42): a deck saved looking through one opens plain.
    LaunchedEffect(state.lens) { if (state.lens != Lens.DECK && state.lens != Lens.ROLES) state.useLens(Lens.DECK) }
    val panel = groupsOn(state)
    Row(modifier) {
        DeckBody(state, neue, drag, Modifier.weight(1f).fillMaxHeight().releasesTypingOnFinger().chessySpot(ChessyPoint.DECK))
        // On a phone the panel is a tab of the pool's (v1.3.5): beside the deck it would be the deck.
        if (panel && !neue.phone) GroupsPanel(state, neue, Modifier.zenQuiet().chessySpot(ChessyPoint.GROUPS))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeckBody(state: DeckBuilderState, neue: NeueState, drag: NeueDrag, modifier: Modifier) {
    val nameTab = nameTab()
    val density = LocalDensity.current
    val lensOn = state.lens != Lens.DECK || state.groupDraft != null
    val crack by animateFloatAsState(if (lensOn) 1f else 0f, tween(MuMotion.SLOW, easing = MuMotion.ease), label = "crack")
    val zen = LocalZen.current
    val motion = rememberDeckMotion(drag, zen)
    val origin = remember { floatArrayOf(0f, 0f) }
    val grids = remember { mutableMapOf<DeckSection, Rect>() }
    // The wheel's size glides (1.0.24, `DeckZoom`): the preference is where the deck is
    // going, and the deck is re-fitted every frame at a size closing on it. At rest, and
    // for anything but the wheel (a pinch, the stored size on opening), there is no glide.
    val glide = remember { ZoomGlide() }
    val zoomTarget = neue.prefs.deckZoom
    LaunchedEffect(zoomTarget) {
        if (glide.shown.isNaN()) return@LaunchedEffect
        while (true) {
            val now = withFrameNanos { it }
            // A notch mid-glide restarts this with the frame before it still in hand, so the
            // motion carries on rather than stopping a frame at each notch.
            val dt = if (glide.frame == 0L) 16f else ((now - glide.frame) / 1_000_000f).coerceAtMost(64f)
            glide.frame = now
            val next = DeckZoom.approach(glide.shown, zoomTarget, dt)
            if (next == zoomTarget) {
                glide.shown = Float.NaN
                glide.frame = 0L
                break
            }
            glide.shown = next
        }
    }

    BoxWithConstraints(
        modifier
            .onGloballyPositioned { val p = it.positionInWindow(); origin[0] = p.x; origin[1] = p.y }
            .onPointer(PointerEventType.Move) { e -> e.changes.firstOrNull()?.let { motion.hover = Offset(origin[0] + it.position.x, origin[1] + it.position.y) } }
            .onPointer(PointerEventType.Enter) { e -> e.changes.firstOrNull()?.let { motion.hover = Offset(origin[0] + it.position.x, origin[1] + it.position.y) } }
            .onPointer(PointerEventType.Exit) { motion.hover = null }
            // The wheel sizes the deck (kai, 1.0.17): down, the cards shrink toward the middle
            // with paper round them; up, back to the size that fills the column. A notch is a
            // ratio and a touchpad's fraction of one counts in proportion, and the deck glides
            // to the size (1.0.24, `DeckZoom`). With the groups on, Shift and the wheel open
            // and close the gaps between them. In deep zen the wheel is zen's (the gaps
            // there), so it is left alone here.
            .onPointer(PointerEventType.Scroll) { e ->
                if (neue.zen == ZenPhase.DEEP) return@onPointer
                val d = e.changes.firstOrNull()?.scrollDelta ?: return@onPointer
                val step = if (d.y != 0f) d.y else d.x
                if (step == 0f) return@onPointer
                if (e.keyboardModifiers.isShiftPressed && lensOn) {
                    neue.update(debounce = true) { it.copy(groupGap = it.groupGap - step * 0.15f) }
                } else {
                    val from = neue.prefs.deckZoom
                    val to = DeckZoom.wheel(from, step)
                    if (to != from) {
                        if (glide.shown.isNaN()) glide.shown = from
                        neue.update(debounce = true) { it.copy(deckZoom = to) }
                    }
                }
                e.changes.forEach { it.consume() }
            }
            // Two fingers are the wheel on a tablet (1.3.0), and say which over their first
            // 12dp (touch swarm, rec 21: `TwoFinger`): apart or together is the size, the cards
            // shrinking toward the middle and growing back; slid up or down with the groups on
            // is the gap between them, the desk's Shift-wheel; spread at full size hides the
            // pool and the inspector, and pinched at full size with them hidden brings them
            // back. The size is held while the fingers are down and written once, as they lift.
            // In deep zen the gaps between the groups open and close, as the wheel does there.
            // A card being carried is the gesture's, never the pinch's.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    if (!first.byFinger) return@awaitEachGesture
                    var from = 0f
                    var centre = Offset.Zero
                    var startZoom = 1f
                    var startGap = 1f
                    var kind = TwoFinger.Kind.NONE
                    var atLimit = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.filter { it.pressed }
                        if (fingers.isEmpty()) break
                        if (fingers.size < 2 || drag.held != null) continue
                        val apart = (fingers[0].position - fingers[1].position).getDistance()
                        val middle = (fingers[0].position + fingers[1].position) / 2f
                        val deep = neue.zen == ZenPhase.DEEP
                        if (from == 0f) {
                            from = apart
                            centre = middle
                            startZoom = if (deep) zen.gapScale else glide.shown.takeUnless { it.isNaN() } ?: neue.prefs.deckZoom
                            startGap = neue.prefs.groupGap
                            continue
                        }
                        val ratio = if (from > 0f) apart / from else 1f
                        if (deep) {
                            zen.groups = true
                            zen.gapScale = (startZoom * ratio).coerceIn(com.kaiharimoto.neue.ZEN_GAP_MIN, com.kaiharimoto.neue.ZEN_GAP_MAX)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        // Decided over the first 12dp; a zoom may still become a spread past full size.
                        if (kind == TwoFinger.Kind.NONE || kind == TwoFinger.Kind.ZOOM) {
                            val slid = (middle - centre) / this.density
                            val next = TwoFinger.classify(
                                startApart = from / this.density,
                                apart = apart / this.density,
                                centroidDx = slid.x,
                                centroidDy = slid.y,
                                groupsOn = lensOn,
                                zoomAtOne = startZoom >= 1f - 1e-3f,
                                panesHidden = !neue.prefs.poolVisible && !neue.prefs.inspectorVisible,
                            )
                            if (next != TwoFinger.Kind.NONE) kind = next
                        }
                        when (kind) {
                            TwoFinger.Kind.ZOOM -> {
                                val wanted = startZoom * ratio
                                // The fingers are the size: no glide behind them.
                                glide.shown = Float.NaN
                                neue.update(persist = false) { it.copy(deckZoom = wanted) }
                                // The size stops at its ends; the finger feels the stop once.
                                val stopped = neue.prefs.deckZoom != wanted
                                if (stopped && !atLimit && ratio < 1f) neue.actingBy(finger = true) { neue.felt(DeskEvent.ZOOM_LIMIT) }
                                atLimit = stopped
                            }
                            TwoFinger.Kind.GAP -> {
                                val dy = (middle.y - centre.y) / this.density
                                neue.update(persist = false) { it.copy(groupGap = startGap + dy / TwoFinger.GAP_STEP_DP * TwoFinger.GAP_STEP) }
                            }
                            // Undone while the fingers are down; the panes answer as they lift.
                            TwoFinger.Kind.HIDE_PANES, TwoFinger.Kind.SHOW_PANES ->
                                if (neue.prefs.deckZoom != startZoom) neue.update(persist = false) { it.copy(deckZoom = startZoom) }
                            TwoFinger.Kind.NONE -> Unit
                        }
                        event.changes.forEach { it.consume() }
                    }
                    when (kind) {
                        TwoFinger.Kind.HIDE_PANES -> neue.update { it.copy(poolVisible = false, inspectorVisible = false) }
                        TwoFinger.Kind.SHOW_PANES -> neue.update { it.copy(poolVisible = true, inspectorVisible = true) }
                        TwoFinger.Kind.ZOOM, TwoFinger.Kind.GAP -> neue.update { it }
                        TwoFinger.Kind.NONE -> Unit
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
        // A phone (v1.3.5): every section the same few cards across, each a finger wide,
        // and the deck fitted to the width and scrolled rather than shrunk to the height.
        val sidePad = if (neue.phone) 8.dp else SIDE_PAD
        // Upright, a phone's deck is the decklist's own 10×4 (kai, v1.3.6): the desk's rows, fitted
        // to the width; the dock takes what the deck does not need. Lying down, it scrolls.
        val phoneCols = if (neue.phone && !neue.posture.isTall) DeckFitter.phoneColumns((maxWidth - sidePad * 2).value) else null
        SideEffect { neue.phoneColumns = phoneCols }
        // The lens row stands still over the deck whatever the wheel does (kai, 1.0.18):
        // it is laid out once at the top, and the deck is fitted to what is below it.
        val bottomSafe = if (neue.phone) 0.dp else BOTTOM_SAFE
        val deckHeight = maxHeight - LENS_ROW - bottomSafe
        // The main deck in bands of group blocks (1.0.37, `GroupBands`) while the groups are out
        // Fitted or Separate. As the groups close the last bands are kept, so the cards go back
        // into their reading order on the bands' width before the deck is plain again.
        // The deck in pieces by the lens (GroupPieces): what width and height the gaps
        // between them take, declared to the fitter so the cards pay for them honestly.
        val gapPx = with(density) { PIECE_GAP.toPx() } * neue.prefs.groupGap
        // Fitted and Separate both stand a whole gap apart, the Shift-wheel's (1.0.40, kai:
        // "the current fitted doesn't have enough space between them").
        val bandGapX = gapPx
        val bandGapY = gapPx
        // The pane less what is drawn over and between the sections whatever the cards: each
        // section's padding and rule, the other sections' names, the name tabs over the top
        // band — or a tall deck is chosen for room it will not have (1.0.38).
        val chrome = sections.fold(0.dp) { sum, s -> sum + chromeOf(s, neue.phone) } + nameTab
        val liveBands = with(density) { mainBands(state, neue, phoneCols, (maxWidth - sidePad * 2).toPx(), (deckHeight - chrome).toPx(), bandGapX, bandGapY) }
        val keptBands = remember { arrayOfNulls<BandLayout>(1) }
        val bands = liveBands ?: keptBands[0]?.takeIf { !lensOn && crack > 0.01f && it.row.size == state.deck[DeckSection.MAIN].size }
        keptBands[0] = bands
        // A copy set being moved within its group (1.0.39): the bands as they will be if it is
        // let go, drawn at the width shown now; the deck is fitted to the bands at rest.
        val setPreview = (drag.preview as? NeueDrag.Sets)?.takeIf { it.section == DeckSection.MAIN && liveBands != null && drag.held != null }
        val shownBands = remember(setPreview?.sets, bands) { setPreview?.let { neue.bandCache.preview(it.sets) } } ?: bands
        val restMainPieces = remember(bands) { bands?.pieces() }
        fun cols(section: DeckSection) = if (section == DeckSection.MAIN && bands != null) bands.columns else phoneCols ?: columnsOf(section)
        // The extra and side decks are drawn the main deck's width; where their pieces' gaps
        // would take more than a quarter of it, the gaps give way rather than the cards
        // (kai, 1.0.38: a narrow deck left the side deck nothing but its gaps).
        val squeeze = remember { HashMap<DeckSection, Float>() }
        fun gapX(section: DeckSection) = if (section == DeckSection.MAIN && bands != null) bandGapX else gapPx * (squeeze[section] ?: 1f)
        fun gapY(section: DeckSection) = if (section == DeckSection.MAIN && bands != null) bandGapY else gapPx * (squeeze[section] ?: 1f)
        val zenGapPx = with(density) { PIECE_GAP.toPx() }
        // Zen's pieces are always the Roles groups (kai, 1.0.15): the ones the user draws.
        val rolePieces = sections.map { section ->
            val ids = state.deck[section]
            val roleKeying = remember(ids, state.groupsWithDraft, state.index) {
                DeckLenses.key(Lens.ROLES, ids, state.index::byId, state.groupsWithDraft, state.format)
            }
            val bandPieces = if (section == DeckSection.MAIN) shownBands else null
            roleKeying to remember(roleKeying.keyOfCell, phoneCols, bandPieces) { bandPieces?.pieces() ?: GroupPieces.of(roleKeying.keyOfCell, cols(section)) }
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
        val zoom = glide.shown.takeUnless { it.isNaN() } ?: neue.prefs.deckZoom
        // Turned off, the pieces close from where they were rather than vanishing: the
        // last ones are kept while the gaps run down, unless the section has changed size.
        val kept = remember { HashMap<DeckSection, PieceLayout>() }
        val pieces = sections.map { section ->
            val keys = state.keying(section).keyOfCell
            val bandPieces = if (section == DeckSection.MAIN) shownBands else null
            val live = remember(keys, cols(section), bandPieces) { bandPieces?.pieces() ?: GroupPieces.of(keys, cols(section)) }
            val previous = kept[section]
            val used = if (lensOn || bandPieces != null || previous == null || previous.piece.size != keys.size || crack < 0.01f) live else previous
            kept[section] = used
            used
        }
        fun fitAt(z: Float) = with(density) {
            val requests = sections.mapIndexed { i, section ->
                // Bands are a grid of their own: every cell of it, filled or not, is room.
                val cells = if (section == DeckSection.MAIN) bands?.let { it.columns * it.rows } else null
                val fitted = (if (section == DeckSection.MAIN) restMainPieces else null) ?: pieces[i]
                SectionFitRequest(
                    count = cells ?: state.deck[section].size,
                    columns = cols(section),
                    baselineCount = cells ?: if (section == DeckSection.MAIN) section.minSize else section.maxSize,
                    spacing = 0f,
                    chromeHeight = chromeOf(section, neue.phone).toPx(),
                    extraWidth = fitted.spanX * gapX(section) * crack,
                    extraHeight = fitted.spanY * gapY(section) * crack + (if (fitted.pieces > 1) nameTab.toPx() * crack else 0f),
                )
            }
            // The extra and side decks carry no names or counts (1.0.41, kai: "players know
            // intuitively that the extra deck and side deck are what they are"): their room is the cards'.
            val labelled = sections.map { false }
            if (phoneCols != null) {
                DeckLabels.stack(
                    availableWidth = (maxWidth - sidePad * 2).toPx() * z,
                    availableHeight = deckHeight.toPx(),
                    aspectRatio = CARD_RATIO,
                    rowHeight = LABEL_ROW.toPx(),
                    requests = requests,
                    labelled = labelled,
                )
            } else {
                DeckLabels.place(
                    availableWidth = (maxWidth - SIDE_PAD * 2).toPx() * z,
                    availableHeight = deckHeight.toPx() * z,
                    aspectRatio = CARD_RATIO,
                    gutter = LABEL_GUTTER.toPx(),
                    rowHeight = LABEL_ROW.toPx(),
                    requests = requests,
                    labelled = labelled,
                )
            }
        }
        squeeze.clear()
        val loose = fitAt(zoom)
        sections.forEachIndexed { i, section ->
            val spent = pieces[i].spanX * gapX(section) * crack
            val room = loose.fit.contentWidth * GAP_SHARE
            if (section != DeckSection.MAIN && spent > room) squeeze[section] = room / spent
        }
        val placed = if (squeeze.isEmpty()) loose else fitAt(zoom)
        // The row's inset is the deck's edge at the size that fills the column, so it
        // does not creep inward as the wheel shrinks the cards.
        val full = if (zoom < 0.999f) fitAt(1f) else placed
        val fullWidth = with(density) { full.fit.contentWidth.toDp() }
        // Never so far in that the row gives up its buttons: a fitted deck can be narrow.
        val rowInset = minOf(maxOf((maxWidth - fullWidth) / 2, sidePad), maxOf((maxWidth - ROW_LEAST) / 2, sidePad))
        val fit = placed.fit
        val contentWidth = with(density) { fit.contentWidth.toDp() }
        // Where the grids start, from the column's left edge: every section is the same width and centred.
        val gridLeft = (maxWidth - contentWidth) / 2
        motion.cardWidth = fit.sections.firstOrNull()?.cardWidth ?: 100f
        // The pool draws its cards the size of these, unless told otherwise: at the size
        // that fills the column, whatever the wheel has made of the deck — the fit at the
        // full size itself, so a glide does not stir the pool's columns as it goes.
        val fullCard = with(density) { (full.fit.sections.firstOrNull()?.cardWidth ?: 0f).toDp() }
        SideEffect { if (zen.deep == 0f && fullCard > 0.dp) neue.deckCardWidth = fullCard }
        val mainIds = state.deck[DeckSection.MAIN]
        val mainRefused = drag.hover?.let { it.section == DeckSection.MAIN && drag.held != null && !it.accepted } == true
        val mainOut = mainIds.size > DeckSection.MAIN.maxSize || mainIds.size < DeckSection.MAIN.minSize
        LensRow(state, neue, "${mainIds.size} · ${DeckSection.MAIN.minSize}–${DeckSection.MAIN.maxSize}", mainOut, mainRefused, inset = rowInset)
        Column(
            Modifier.padding(top = LENS_ROW, bottom = bottomSafe).fillMaxSize().let { if (!fit.fits) it.verticalScroll(rememberScrollState()) else it },
            // Immersive: past the page's fixed strip at the top (IMMERSIVE_TOP), whatever
            // height the deck does not need is shared above and below it, so the deck sits
            // in the middle of the screen (kai, 1.0.12: all of it above was too much).
            // A deck the wheel has made smaller stays in the middle too, with paper round it,
            // and one gliding away from the full size goes there smoothly (`DeckZoom.centring`).
            verticalArrangement = standing(if (neue.immersive) 0.5f else DeckZoom.centring(zoom)),
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
                    pieces = pieces[i],
                    gapPx = gapX(section),
                    gapYPx = gapY(section),
                    bandKeys = if (section == DeckSection.MAIN && bands != null) state.keying(section).keyOfCell else null,
                    bandSets = if (section == DeckSection.MAIN) bands?.let { b -> remember(b) { b.setOrder(state.deck[section].map { it.value }) } } else null,
                    cellOrder = (drag.preview as? NeueDrag.Cells)?.takeIf { it.section == section && drag.held != null }?.order,
                    roleKeying = rolePieces[i].first,
                    first = i == 0,
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

/**
 * How tall a phone's upright deck is drawn at [width] (v1.3.6): the decklist's 10×4 main
 * deck and its fifteen-wide extra and side decks at the full width, their names in rows
 * over them, the pieces' gaps while the groups are out, and the lens row — the same
 * sums [DeckBody] fits to, so the deck given this height fills its width exactly.
 */
@Composable
internal fun naturalDeckHeight(state: DeckBuilderState, neue: NeueState, width: Dp): Dp {
    val density = LocalDensity.current
    val nameTab = nameTab()
    val lensOn = state.lens != Lens.DECK || state.groupDraft != null
    val sections = buildList {
        add(DeckSection.MAIN)
        if (neue.prefs.extraVisible) add(DeckSection.EXTRA)
        if (neue.prefs.sideVisible) add(DeckSection.SIDE)
    }
    return with(density) {
        val gapPx = PIECE_GAP.toPx() * neue.prefs.groupGap
        // The same bands the deck is laid out in, asked with the same width (`mainBands`).
        val bandGapX = gapPx
        val bandGapY = gapPx
        val bands = mainBands(state, neue, null, (width - (if (neue.phone) 8.dp else SIDE_PAD) * 2).toPx(), 0f, bandGapX, bandGapY)
        val placed = DeckLabels.stack(
            availableWidth = (width - SIDE_PAD * 2).toPx(),
            availableHeight = Float.MAX_VALUE,
            aspectRatio = CARD_RATIO,
            rowHeight = LABEL_ROW.toPx(),
            requests = sections.map { section ->
                val banded = if (section == DeckSection.MAIN) bands else null
                val pieces = banded?.pieces() ?: if (lensOn) GroupPieces.of(state.keying(section).keyOfCell, columnsOf(section)) else null
                val gapX = if (banded == null) gapPx else bandGapX
                val gapY = if (banded == null) gapPx else bandGapY
                SectionFitRequest(
                    count = banded?.let { it.columns * it.rows } ?: state.deck[section].size,
                    columns = banded?.columns ?: columnsOf(section),
                    baselineCount = banded?.let { it.columns * it.rows } ?: if (section == DeckSection.MAIN) section.minSize else section.maxSize,
                    spacing = 0f,
                    chromeHeight = chromeOf(section, neue.phone).toPx(),
                    extraWidth = (pieces?.spanX ?: 0) * gapX,
                    extraHeight = (pieces?.spanY ?: 0) * gapY + (if ((pieces?.pieces ?: 0) > 1) nameTab.toPx() else 0f),
                )
            },
            labelled = sections.map { false },
        )
        placed.fit.totalHeight.toDp() + LENS_ROW + 2.dp
    }
}

/** The drawn size of the deck while the wheel's glide runs (`DeckZoom.approach`); NaN at rest. */
private class ZoomGlide {
    var shown by mutableFloatStateOf(Float.NaN)

    /** The last frame's time, so a glide restarted by a notch carries on at the same pace. Plain. */
    var frame = 0L
}

/** A column's children stacked, standing [fraction] of the way down its spare height: 0 the top, 0.5 the middle. */
private fun standing(fraction: Float): Arrangement.Vertical = when (fraction) {
    0f -> Arrangement.Top
    0.5f -> Arrangement.Center
    else -> object : Arrangement.Vertical {
        override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
            var y = ((totalSize - sizes.sum()).coerceAtLeast(0) * fraction).roundToInt()
            sizes.forEachIndexed { i, size ->
                outPositions[i] = y
                y += size
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
        // Its sets beside it (kai, 2026-10): other ways of grouping the deck, one in use. A
        // phone's row has no room for it: there it heads the Groups tab (`GroupsPanel`).
        if (!neue.phone) GroupSetButton(state, neue, nameWidth = if (tight) 72.dp else if (narrow) 120.dp else 180.dp)
        FoilToggle(neue)
        // The extra and the side deck, each on its own switch (kai, 1.0.17): the main deck
        // has whatever they give up. Short words when the row is tight.
        // A card a finger put into a hidden section flashes that section's box instead (rec 15).
        val ring by rememberRing(state)
        fun flashed(section: DeckSection, visible: Boolean) = visible != (!visible && ring?.section == section)
        Tip(if (neue.prefs.extraVisible) "Hide the extra deck" else "Show the extra deck") {
            BoxToggle(if (tight) "Ex" else "Extra", flashed(DeckSection.EXTRA, neue.prefs.extraVisible)) { neue.update { it.copy(extraVisible = !it.extraVisible) } }
        }
        Tip(if (neue.prefs.sideVisible) "Hide the side deck" else "Show the side deck") {
            BoxToggle(if (tight) "Si" else "Side", flashed(DeckSection.SIDE, neue.prefs.sideVisible)) { neue.update { it.copy(sideVisible = !it.sideVisible) } }
        }
        if (!narrow) Micro("Main deck", color = c.ink70)
        // At its tightest the row keeps the count only when it is something to act on.
        if (!tight || outOfRange) Mono(if (outOfRange) "✕ $count" else count, color = if (outOfRange) c.ink else c.ink70)
        Box(Modifier.weight(1f))
        if (refused) Micro("✕ Not allowed here", color = c.ink)
        // How the groups stand (1.0.42, kai: the lens tabs went unused — "repurpose that area
        // for groups instead"): As is, Fitted or Separate, where the tabs were. Chosen with the
        // groups off, it brings them out. On a phone, one button and the three in its menu.
        if (neue.phone) ArrangementMenu(state, neue) else ArrangementSwitch(state, neue)
    }
    }
}

/** The three ways the groups stand, as a switch: faint while the groups are off. */
@Composable
private fun ArrangementSwitch(state: DeckBuilderState, neue: NeueState) {
    Tip("As is keeps your order; Fitted fits the groups together as blocks; Separate gives each group its own rows", kbd = "Shift K") {
        Box(Modifier.alpha(if (groupsOn(state)) 1f else 0.45f)) {
            Segmented(neue.prefs.arrangement, GroupArrangement.entries, { com.kaiharimoto.neue.arrangementWords(it) }, { arrange(state, neue, it) }, small = true, compact = true)
        }
    }
}

/** The arrangement on a phone (v1.3.5's lens button, 1.0.42's use): the one in use, and the others a tap away. */
@Composable
private fun ArrangementMenu(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    var at by remember { mutableStateOf(Offset.Zero) }
    val now = neue.prefs.arrangement
    Row(
        Modifier
            .height(32.dp)
            .border(1.dp, if (groupsOn(state)) c.ink else c.ink25)
            .onGloballyPositioned { at = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }
            .cursorPointer(caption = "Arrange")
            .muClickable {
                neue.menu = MenuSpec(at, GroupArrangement.entries.map { a ->
                    MenuEntry(com.kaiharimoto.neue.arrangementWords(a), hint = if (a == now) "Now" else null) { arrange(state, neue, a) }
                })
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Micro(com.kaiharimoto.neue.arrangementWords(now), color = if (groupsOn(state)) c.ink else c.ink45)
        Micro("▾", color = c.ink70)
    }
}

/** [a] chosen: kept, and the groups out if they were not. */
private fun arrange(state: DeckBuilderState, neue: NeueState, a: GroupArrangement) {
    neue.update { it.copy(groupArrangement = a.name) }
    if (!groupsOn(state)) state.useLens(Lens.ROLES)
}

/**
 * Whether the groups are on (kai, 1.0.15): the Roles lens, or a group being drawn
 * up. It is one switch — the Groups button — for the pieces, their colour and the
 * panel that edits them; off, the deck is plain.
 */
internal fun groupsOn(state: DeckBuilderState): Boolean = state.lens == Lens.ROLES || state.groupDraft != null


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
    val hovered by source.collectIsHotAsState()
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
                .muClickable(interactionSource = source) {
                    neue.update { it.copy(foil = if (on) Foils.OFF else Foils.HOLO) }
                },
            contentAlignment = Alignment.Center,
        ) {
            FoilGlyph(on, feel, ink = c.ink, paper = c.paper, modifier = Modifier.size(12.dp, 17.dp))
        }
    }
}

/**
 * The card the builder was last asked to reveal, for [DeskTouch.REVEAL_MS] (touch
 * swarm, rec 15): the add or drop a finger made, which the finger itself covers.
 */
@Composable
private fun rememberRing(state: DeckBuilderState): androidx.compose.runtime.State<RevealRequest?> {
    val ring = remember { mutableStateOf<RevealRequest?>(null) }
    val request = state.revealRequest
    // One that was asked for before this deck was drawn (another page, a moment ago) is spent.
    val spent = remember { request }
    androidx.compose.runtime.LaunchedEffect(request) {
        if (request == null || request == spent) return@LaunchedEffect
        ring.value = request
        kotlinx.coroutines.delay(DeskTouch.REVEAL_MS)
        if (ring.value == request) ring.value = null
    }
    return ring
}

/** A boxed button that stays pressed: ink when on, a ruled box when off. */
@Composable
private fun BoxToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Box(
        Modifier
            .height(28.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .border(1.dp, c.ink)
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Micro(label, color = if (on) c.paper else c.ink)
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
    pieces: PieceLayout,
    gapPx: Float,
    gapYPx: Float,
    /** Laid out in bands: each position's group and the Fitted order, for a drag to move sets by (1.0.39). */
    bandKeys: List<String?>?,
    bandSets: List<Int>?,
    /** A card being moved through the cells: the positions in the order they are drawn (1.0.39). */
    cellOrder: List<Int>?,
    roleKeying: LensKeying,
    rolePieces: PieceLayout,
    /** The top section on show, with only the table above it: room for a whole name tab in zen. */
    first: Boolean,
    zenGapPx: Float,
    zenAbove: Float,
    crack: Float,
    motion: DeckMotion,
    onGrid: (Rect) -> Unit,
) {
    val nameTab = nameTab()
    val c = Mu.colors
    val density = LocalDensity.current
    val zen = LocalZen.current
    val ids = state.deck[section]
    val roleKeys = roleKeying.keyOfCell
    val hover = drag.hover?.takeIf { it.section == section && drag.held != null }
    val keying = state.keying(section)
    val ring by rememberRing(state)
    val aimed = section == DeckSection.SIDE && neue.touchFirst && neue.prefs.poolToSide
    // A section's taps are counted together (rec 11): a double-tap that drifts is still the first card's.
    val taps = rememberTapSurface(repeats = false, key = section)
    val labelRoom = if (pieces.pieces > 1) with(density) { nameTab.toPx() } else 0f
    val placer = PiecePlacer(fit.columns, fit.cardWidth, fit.cardHeight, gapPx, pieces, crack, rolePieces, zenGapPx, zenAbove, labelRoom, gapY = gapYPx)
    // Room for every group's name twice over: zen measures them on every frame it floats.
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val tabStyle = MuType.micro(LocalMuFonts.current).copy(fontSize = 10.sp)
    val restPlaces = List(ids.size) { placer.rest(it) }
    // Where each card is drawn: its place, or — while a card is moved through the cells —
    // the cell the preview gives it (1.0.39). The cells stay; the cards move through them.
    val drawnPlaces = cellOrder?.takeIf { it.size == ids.size }?.let { order ->
        val slotOf = IntArray(order.size).also { a -> order.forEachIndexed { q, p -> a[p] = q } }
        List(ids.size) { restPlaces[slotOf[it]] }
    } ?: restPlaces
    // The cards glide to the preview's places while a card of this section is carried, and
    // are simply there otherwise, so a re-fit or the groups opening never trails a frame.
    val gliding = drag.held?.from == section

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
            placed = drawnPlaces,
            ids = ids.map { it.value },
            bandKeys = bandKeys,
            bandSets = bandSets,
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
    // the sections below it: the pane is as high as its highest card. Worked out as the
    // pane is placed, and only told when it changes, so zen's fade never recomposes the pane.
    val paneLayer = remember(zen, section, ids.size) {
        derivedStateOf { if (zen.deep > 0f) (0 until ids.size).maxOfOrNull { zen.layerOf(ZenArrangement.key(section.ordinal, it)) } ?: 0f else 0f }
    }
    Column(
        Modifier
            .zIndexAsPlaced { paneLayer.value }
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                laid.pane = coords.boundsInWindow()
                publish()
            },
    ) {
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

        Box(Modifier.fillMaxWidth().padding(top = padTop(section, neue.phone), bottom = padBottom(neue.phone)), contentAlignment = Alignment.TopCenter) {
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
                                    membership = homeBlock(section, p, rolePieces, keying.keyAt(p)),
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
                            placed = drawnPlaces,
                            ids = ids.map { it.value },
                            bandKeys = bandKeys,
                            bandSets = bandSets,
                        )
                        publish()
                    }
                    // The side deck the pool adds to on a tablet is ringed, as its name was inverted (rec 17).
                    .let { if (hover?.accepted == true) it.border(1.dp, c.ink) else if (aimed) it.border(1.dp, c.ink45) else it },
            ) {
                // Zen: every card's shadow on the table, under all of them.
                Canvas(Modifier.matchParentSize()) {
                    val deep = zen.deep
                    if (deep <= 0f) return@Canvas
                    val pieceAmount = zen.groupsAmount
                    val drawn = ids.indices.map { position ->
                        val key = ZenArrangement.key(section.ordinal, position)
                        val o = zen.offsetOf(key)
                        val drift = zenFloat(zen, section, position, rolePieces, keying.keyAt(position), roleKeys.getOrNull(position), zen.time)
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
                            val r = rolePieces.row(position)
                            val col = rolePieces.col(position)
                            val neighbours = arrayOf(rolePieces.at(r, col - 1), rolePieces.at(r - 1, col), rolePieces.at(r, col + 1), rolePieces.at(r + 1, col))
                            for (s in 0..3) {
                                val n = neighbours[s] ?: continue
                                if (!sides[s] && zen.offsetOf(ZenArrangement.key(section.ordinal, n)) != mine) sides[s] = true
                            }
                            zenGlow(drawn[position], sides, GroupMarkers.hue(group.color), glow, zen.time, fit.cardWidth)
                        }
                    }
                    // Each group's name on its piece, as the builder writes it, when the corner's
                    // Labels switch is on (kai, 1.0.24): on the cards still in the piece, floating
                    // with them, in whatever gap is over them (`ZenLabels`).
                    val named = glow * zen.labelsAmount
                    if (named > 0.01f && roleKeys.any { it != null } && rolePieces.piece.size == ids.size) {
                        val frame = FRAME.toPx()
                        val labels = Labels(measurer, tabStyle, nameTab.toPx()) { id -> roleKeying.keyById(id)?.label.orEmpty() }
                        val gapNow = zenGapPx * zen.pieceAmount
                        val between = (padBottom(neue.phone) + RULE + padTop(section, neue.phone)).toPx()
                        val least = nameTab.toPx() * 0.55f
                        roleKeys.filterNotNull().distinct().forEach { id ->
                            val key = roleKeying.keyById(id) ?: return@forEach
                            drawGroupLabel(
                                key = id,
                                keys = roleKeys,
                                pieces = rolePieces,
                                at = { drawn[it].topLeft },
                                cardWidth = fit.cardWidth,
                                frame = frame,
                                color = GroupMarkers.paint(key.paint, c.ink),
                                labels = labels,
                                alpha = named,
                                edgeOf = { need -> ZenLabels.edge(rolePieces, roleKeys, id, need) { zen.isMoved(ZenArrangement.key(section.ordinal, it)) } },
                                tabOf = { edge -> ZenLabels.tab(rolePieces.row(edge.first), first, gapNow, between, labels.tab, least) },
                            )
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
                            // Cards moving through the cells cross the outlines, which stay with the cells: faint meanwhile.
                            alphaOf = { id -> crack * fade * (if (cellOrder != null) 0.3f else 1f) * if (state.isolatedKey != null && state.isolatedKey != id) 0.18f else 1f },
                            labels = Labels(measurer, tabStyle, nameTab.toPx()) { id -> keying.keyById(id)?.label.orEmpty() },
                        )
                    }
                }

                // Each card keyed by its passcode and which copy it is (1.0.92), so what a card
                // remembers (its press, its art, its glide) stays with it when the deck is edited.
                val copyOf = remember(ids) { copyNumbers(ids) }
                ids.forEachIndexed { position, id -> key(id, copyOf[position]) {
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
                    val drawnAt = drawnPlaces[position]
                    val glide = remember(section) { Animatable(drawnAt, Offset.VectorConverter) }
                    LaunchedEffect(drawnAt, gliding) {
                        if (gliding) glide.animateTo(drawnAt, tween(MuMotion.BASE, easing = MuMotion.ease)) else glide.snapTo(drawnAt)
                    }
                    Box(
                        Modifier
                            .offset {
                                val o = zen.offsetOf(zenKey)
                                // In zen the pieces may open or close; at rest this is exactly (l, t),
                                // or where the preview is taking it while a card is carried.
                                val d = zen.deep
                                val g = if (gliding) glide.value else null
                                val x = if (d > 0f) round(placer.x(position, d, zen.pieceAmount)).toInt() else g?.let { round(it.x).toInt() } ?: l
                                val y = if (d > 0f) round(placer.y(position, d, zen.pieceAmount)).toInt() else g?.let { round(it.y).toInt() } ?: t
                                IntOffset(x + o.x.roundToInt(), y + o.y.roundToInt())
                            }
                            .size(with(density) { (r - l).toDp() }, with(density) { (b - t).toDp() })
                            // A selected card over its neighbours, so its frame is seen whole (1.0.41).
                            // Read as the card is placed: the bump crossing a card, or zen's layers
                            // shifting, places the cards again and recomposes none of them.
                            .zIndexAsPlaced {
                                zen.layerOf(zenKey).takeIf { it > 0f }
                                    ?: if ((neue.selection as? Selection.InDeck)?.let { it.section == section && it.index == position } == true) 2f else if (position == onTop) 1f else 0f
                            }
                            // The carried card's own place is the slot it will land in: a faint ghost of it.
                            .alpha(if (held) 0.3f else if (covered) 0.3f else 1f),
                    ) {
                        if (card == null) {
                            Box(Modifier.fillMaxSize().background(c.ink06)) {
                                Hatch(Modifier.fillMaxSize(), color = c.ink25)
                                Box(Modifier.align(Alignment.Center).background(c.paper).padding(2.dp)) {
                                    Mono(id.value.toString(), color = c.ink, size = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp))
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
                                        drafting = state.groupDraft != null,
                                        taps = taps,
                                    ),
                                motion = {
                                    // Where the card is at rest: zen's lean works out from there where it has gone.
                                    val o = laid.restOrigin ?: laid.grid?.origin ?: Offset.Zero
                                    val moved = zen.offsetOf(zenKey)
                                    val lean = if (held) LeanPose.REST else motion.poseAt(Offset(o.x + left + moved.x + fit.cardWidth / 2f, o.y + top + moved.y + fit.cardHeight / 2f))
                                    val pressed = press.pose()
                                    val deep = zen.deep
                                    val drift = if (deep > 0f) zenFloat(zen, section, position, rolePieces, keyId, roleKeys.getOrNull(position), zen.time).times(deep) else LeanPose.REST
                                    val carried = if (zenKey in zen.carrying) HELD_LIFT * deep else 0f
                                    lean.copy(lift = lean.lift + pressed.lift + carried) + drift
                                },
                                format = state.format,
                                marks = state.marks,
                                section = section,
                                // No artwork chip on the card (kai, 1.0.89: "distracting and not necessary"): the
                                // inspector's arrows and A step the art.
                                artChip = false,
                                // In deep zen, the cards picked out to move together.
                                selected = selected && neue.zen != ZenPhase.DEEP || zen.isSelected(zenKey),
                                foil = neue.prefs.foil,
                                // The group's name is on its piece's tab now (1.0.18), not a mark on every card.
                                marker = null,
                            )
                            // Where a finger's add or drop landed (touch swarm, rec 15): a ring inside the
                            // card for a moment, then gone at once — nothing moves, nothing is re-fitted.
                            if (ring?.let { it.section == section && it.position == position } == true) {
                                Box(Modifier.fillMaxSize().border(2.dp, c.ink))
                            }
                        }
                    }
                } }

                // Where a card from elsewhere will land: a 2px ink bar in the gap, on the row the
                // pointer is in (1.0.39). A card of this section opens its own slot instead, and
                // bands place a card by its group, so they have no bar.
                if (hover?.accepted == true && drag.held?.from != section && bandKeys == null) {
                    val at = hover.index.coerceIn(0, ids.size)
                    val (by, after) = drag.mark ?: (at to false)
                    val mark = when {
                        ids.isEmpty() -> Offset.Zero
                        by in ids.indices -> restPlaces[by] + Offset(if (after) fit.cardWidth else 0f, 0f)
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
                            DeskWords.emptySection(
                                touch = LocalTouchFirst.current,
                                quickAddLandsHere = (section == DeckSection.SIDE) == neue.prefs.poolToSide,
                            ),
                        )
                    }
                }
            }
        }
        HRule(Modifier.zenQuiet(), color = c.ink)
    }
}

/**
 * `Modifier.zIndex`, with the index worked out as the element is placed (1.0.92): what it
 * reads places the element again when it changes, rather than recomposing what wears it.
 * Measured and placed exactly as `zIndex` does it — at the origin, at [z].
 */
private fun Modifier.zIndexAsPlaced(z: () -> Float): Modifier = this then ZIndexAsPlacedElement(z)

private data class ZIndexAsPlacedElement(val z: () -> Float) : ModifierNodeElement<ZIndexAsPlacedNode>() {
    override fun create() = ZIndexAsPlacedNode(z)

    override fun update(node: ZIndexAsPlacedNode) {
        node.z = z
    }
}

private class ZIndexAsPlacedNode(var z: () -> Float) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0, zIndex = z()) }
    }
}

/** Which copy of its card each position holds, 0 for the first: with the passcode, a card's key in its section. */
private fun copyNumbers(ids: List<CardId>): IntArray {
    val seen = HashMap<CardId, Int>()
    return IntArray(ids.size) { i ->
        val n = seen[ids[i]] ?: 0
        seen[ids[i]] = n + 1
        n
    }
}

/** Where a pane and its grid were last laid out, in window pixels. */
private class PaneLayout {
    var pane: Rect = Rect.Zero
    var grid: GridGeometry? = null

    /** The grid's origin measured at rest, outside zen's transform. */
    var restOrigin: Offset? = null
}

