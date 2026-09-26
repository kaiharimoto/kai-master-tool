package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.zIndex
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
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.layout.BreakdownLayout
import com.kaiharimoto.mastertool.core.layout.BreakdownPlan
import com.kaiharimoto.mastertool.core.layout.CardPlacement
import com.kaiharimoto.mastertool.core.layout.CellEdges
import com.kaiharimoto.mastertool.core.layout.DeckFitter
import com.kaiharimoto.mastertool.core.layout.GridRegion
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
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Strip
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion

private val SPACING = 2.dp
private val CRACK = 4.dp
private val CELL_INSET = 1.dp
private val STRIP = 37.dp
private val MAIN_STRIP = 45.dp
private val LENS_STRIP = 45.dp
private val GRID_PAD = 12.dp
private val SIDE_PAD = 24.dp

/** Row widths: the tablet's, because a decklist is quoted in tens and fifteens whatever the screen. */
private fun columnsOf(section: DeckSection) = if (section == DeckSection.MAIN) 10 else 15

/**
 * The deck: main, extra and side, all on screen at once and never scrolled.
 *
 * Sized by the tablet's own `DeckFitter.plan` — row widths in, one card size
 * out, every section the same width — because "layout is solved, not
 * negotiated" is as true of a 27-inch monitor as of a tablet. On a large
 * display the fitter simply hands back bigger cards. Sections are divided by
 * rules, not gaps (law 6); the main deck carries the lens.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DeckColumn(state: DeckBuilderState, neue: NeueState, drag: NeueDrag, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val lensOn = state.lens != Lens.DECK || state.groupDraft != null
    val crack by animateFloatAsState(if (lensOn) 1f else 0f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "crack")
    val motion = rememberDeckMotion(drag)
    val origin = remember { floatArrayOf(0f, 0f) }

    BoxWithConstraints(
        modifier
            .onGloballyPositioned { val p = it.positionInWindow(); origin[0] = p.x; origin[1] = p.y }
            .onPointerEvent(PointerEventType.Move) { e -> e.changes.firstOrNull()?.let { motion.hover = Offset(origin[0] + it.position.x, origin[1] + it.position.y) } }
            .onPointerEvent(PointerEventType.Enter) { e -> e.changes.firstOrNull()?.let { motion.hover = Offset(origin[0] + it.position.x, origin[1] + it.position.y) } }
            .onPointerEvent(PointerEventType.Exit) { motion.hover = null },
    ) {
        val sections = DeckSection.entries
        val fit = with(density) {
            DeckFitter.plan(
                requests = sections.map { section ->
                    SectionFitRequest(
                        count = state.deck[section].size,
                        columns = columnsOf(section),
                        baselineCount = if (section == DeckSection.MAIN) section.minSize else section.maxSize,
                        spacing = SPACING.toPx(),
                        chromeHeight = (GRID_PAD * 2 + if (section == DeckSection.MAIN) MAIN_STRIP + LENS_STRIP else STRIP).toPx(),
                    )
                },
                availableWidth = (maxWidth - SIDE_PAD * 2).toPx(),
                availableHeight = maxHeight.toPx(),
                aspectRatio = CARD_RATIO,
            )
        }
        val contentWidth = with(density) { fit.contentWidth.toDp() }
        motion.cardWidth = fit.sections.firstOrNull()?.cardWidth ?: 100f
        Column(
            Modifier.fillMaxSize().let { if (!fit.fits) it.verticalScroll(rememberScrollState()) else it },
        ) {
            sections.forEachIndexed { i, section ->
                DeckSectionPane(
                    state = state,
                    neue = neue,
                    drag = drag,
                    section = section,
                    fit = fit.sections[i],
                    contentWidth = contentWidth,
                    crack = crack,
                    lensStrip = section == DeckSection.MAIN,
                    motion = motion,
                )
            }
        }
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
    crack: Float,
    lensStrip: Boolean,
    motion: DeckMotion,
) {
    val c = Mu.colors
    val density = LocalDensity.current
    val ids = state.deck[section]
    val hover = drag.hover?.takeIf { it.section == section && drag.held != null }
    val count = ids.size
    val rangeText = if (section == DeckSection.MAIN) "${section.minSize}–${section.maxSize}" else "0–${section.maxSize}"
    val outOfRange = count > section.maxSize || count < section.minSize

    // The whole pane accepts a drop, header included: aiming at a strip is
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
        )
        publish()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                laid.pane = coords.boundsInWindow()
                publish()
            },
    ) {
        Strip(
            label = "${section.displayName} deck",
            modifier = Modifier.height(if (lensStrip) MAIN_STRIP else STRIP),
        ) {
            if (hover != null && !hover.accepted) Micro("✕ Not allowed here", color = c.ink)
            if (lensStrip) {
                Segmented(state.lens, Lens.entries, { it.displayName }, state::useLens, small = true)
            }
            Mono(
                if (outOfRange) "✕ $count · $rangeText" else "$count · $rangeText",
                color = if (outOfRange) c.ink else c.ink45,
            )
        }
        if (lensStrip) LensStrip(state, neue, Modifier.height(LENS_STRIP))

        val keying = state.keying(section)
        val plan: BreakdownPlan? = if (crack > 0.01f && !keying.isEmpty) BreakdownLayout.plan(keying, fit.columns) else null
        val cardW = with(density) { fit.cardWidth.toDp() }
        val cardH = with(density) { fit.cardHeight.toDp() }
        val gridHeight = with(density) { fit.gridHeight.toDp() }
        val pitchX = fit.cardWidth + with(density) { SPACING.toPx() }
        val pitchY = fit.cardHeight + with(density) { SPACING.toPx() }
        // The card the bump is over is drawn above its neighbours, so its lift is never
        // tucked under the next card along. Recomposes when the pointer crosses a card, not every frame.
        val onTop by remember(section, fit.columns, ids.size) {
            derivedStateOf {
                val p = motion.point() ?: return@derivedStateOf -1
                val o = laid.grid?.origin ?: return@derivedStateOf -1
                val col = ((p.x - o.x) / pitchX).toInt()
                val row = ((p.y - o.y) / pitchY).toInt()
                if (p.x < o.x || p.y < o.y || col >= fit.columns) -1 else (row * fit.columns + col).takeIf { it < ids.size } ?: -1
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = GRID_PAD), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .size(contentWidth, gridHeight)
                    .onGloballyPositioned { coords ->
                        laid.grid = GridGeometry(
                            bounds = coords.boundsInWindow(),
                            origin = coords.positionInWindow(),
                            columns = fit.columns,
                            cardWidth = fit.cardWidth,
                            cardHeight = fit.cardHeight,
                            spacing = with(density) { SPACING.toPx() },
                            count = ids.size,
                        )
                        publish()
                    }
                    .let { if (hover?.accepted == true) it.border(1.dp, c.ink) else it },
            ) {
                if (plan != null) {
                    Canvas(Modifier.matchParentSize()) {
                        plan.pieces.forEach { piece ->
                            val key = keying.keyById(piece.keyId) ?: return@forEach
                            val muted = state.isolatedKey != null && state.isolatedKey != piece.keyId
                            drawRegion(
                                cells = piece.cells,
                                columns = fit.columns,
                                pitchX = fit.cardWidth + SPACING.toPx(),
                                pitchY = fit.cardHeight + SPACING.toPx(),
                                spacing = SPACING.toPx(),
                                inset = CELL_INSET.toPx(),
                                color = GroupMarkers.paint(key.paint, c.ink),
                                alpha = crack * if (muted) 0.18f else 1f,
                            )
                        }
                    }
                }

                ids.forEachIndexed { position, id ->
                    val x = (cardW + SPACING) * (position % fit.columns)
                    val y = (cardH + SPACING) * (position / fit.columns)
                    val card = state.index.byId(id)
                    val keyId = keying.keyAt(position)
                    val key = keying.keyById(keyId)
                    val place = CardPlacement.place(
                        cellWidth = fit.cardWidth,
                        cellHeight = fit.cardHeight,
                        edges = plan?.edgesAt(position) ?: NO_EDGES,
                        crack = with(density) { CRACK.toPx() } * crack,
                        aspectRatio = CARD_RATIO,
                    )
                    val held = drag.held?.let { it.from == section && it.index == position } == true
                    val covered = state.isolatedKey != null && state.isolatedKey != keyId
                    val left = x.value * density.density + place.left
                    val top = y.value * density.density + place.top
                    Box(
                        Modifier
                            .offset { IntOffset(left.toInt(), top.toInt()) }
                            .size(with(density) { place.width.toDp() }, with(density) { place.height.toDp() })
                            .zIndex(if (position == onTop) 1f else 0f)
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
                                    ),
                                motion = {
                                    val o = laid.grid?.origin ?: Offset.Zero
                                    val lean = if (held) LeanPose.REST else motion.poseAt(Offset(o.x + left + place.width / 2f, o.y + top + place.height / 2f))
                                    val pressed = press.pose()
                                    lean.copy(lift = lean.lift + pressed.lift)
                                },
                format = state.format,
                                selected = selected,
                                foil = neue.prefs.foil,
                                marker = key?.let { Marker(it.mark, GroupMarkers.paint(it.paint, c.ink)) },
                            )
                        }
                    }
                }

                // Where a drop will land: a 2px ink bar in the gap, never a shifted grid.
                if (hover?.accepted == true) {
                    val at = hover.index.coerceIn(0, ids.size)
                    val col = if (at == ids.size && at > 0) (at - 1) % fit.columns + 1 else at % fit.columns
                    val row = if (at == ids.size && at > 0) (at - 1) / fit.columns else at / fit.columns
                    Box(
                        Modifier
                            .offset(x = (cardW + SPACING) * col - SPACING - 1.dp, y = (cardH + SPACING) * row)
                            .size(2.dp, cardH)
                            .background(c.ink),
                    )
                }

                if (ids.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Help(
                            if (section == DeckSection.SIDE) "Shift right-click a card in the pool, or drag it here" else "Right-click a card in the pool, or drag it here",
                        )
                    }
                }
            }
        }
        com.kaiharimoto.neue.kit.HRule(color = c.ink)
    }
}

/** Where a pane and its grid were last laid out, in window pixels. */
private class PaneLayout {
    var pane: Rect = Rect.Zero
    var grid: GridGeometry? = null
}

private val NO_EDGES = CellEdges(start = false, top = false, end = false, bottom = false)
