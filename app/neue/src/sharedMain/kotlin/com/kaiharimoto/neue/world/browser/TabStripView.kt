package com.kaiharimoto.neue.world.browser

import androidx.compose.ui.layout.layout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.Icon
import com.kaiharimoto.mastertool.core.world.desk.PageLead
import com.kaiharimoto.mastertool.core.world.desk.Tab
import com.kaiharimoto.mastertool.core.world.desk.TabStrip
import com.kaiharimoto.mastertool.core.world.desk.TabTitles
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.world.CardChip
import com.kaiharimoto.neue.world.desk.IconView
import com.kaiharimoto.neue.world.desk.deskTarget
import com.kaiharimoto.neue.world.type.Body
import com.kaiharimoto.neue.world.type.Help
import com.kaiharimoto.neue.world.type.Micro
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.world.type.Small
import com.kaiharimoto.neue.world.type.WorldType
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/*
 * Tabs anyone can tell apart at a glance (kai: "the tabs names are all truncated so it's hard to tell which tab is which,
 * so we need to also integrate a visually intuitive solution to tell which tab is which"; READABILITY.md §4):
 *
 * - each tab its number (`Ctrl 1`–`Ctrl 9`), its kind's glyph, the art of the card it is about where it is about one
 *   (`PageLead`), and a title of the words that tell it from its neighbours (`TabTitles`), never under the floor —
 *   tabs never narrower than `TabStrip.MIN_W`; past the room, whole tabs and a count;
 * - Ai's mark, a 6 dp square on the glyph's corner, never taking the title's room;
 * - a group's pages side by side, a darker rule where another run's begin;
 * - a pointer resting on a tab (a held finger) shows the page itself, small, with its whole title and where it came from;
 * - the count opens every tab as pictures, searched, closed and reordered there.
 */

/** A page drawn as a picture of itself: no avatar targets reported, no hand reaching inside. */
internal val LocalPagePreview = staticCompositionLocalOf { false }

private val CLOCK = SimpleDateFormat("HH:mm")

/** What the strip, the preview and the overview say of a tab. */
internal data class TabFace(val title: String, val glyph: Icon, val card: String?, val source: String)

@Composable
internal fun tabFace(h: NeueHolders, t: Tab): TabFace {
    val w = h.world.open
    val a = t.parsed
    val board = (a as? WorldAddress.Board)?.let { w?.board(it.id) }
    return remember(t.address, board) {
        TabFace(pageTitle(h, a), pageGlyph(h, a), board?.let(PageLead::card), pageSource(h, a))
    }
}

/** Where a page came from, in a line: `from openings.js · 17:35`. */
internal fun pageSource(h: NeueHolders, a: WorldAddress): String = when (a) {
    is WorldAddress.Board -> h.world.open?.board(a.id)?.let { b ->
        val run = runOf(h, b.id)
        val at = run?.t ?: b.updated.takeIf { it > 0 }
        listOfNotNull(b.source?.let { "from $it" }, at?.let { (if (run != null) "ran " else "pinned ") + CLOCK.format(Date(it)) }).joinToString(" · ").ifEmpty { "a page of this world" }
    } ?: "taken down"
    is WorldAddress.Home -> "every page of this world"
    is WorldAddress.File -> a.path
    is WorldAddress.Run -> "a run · " + CLOCK.format(Date(a.t))
    is WorldAddress.Instrument -> "an instrument"
    is WorldAddress.App -> "an app"
    is WorldAddress.Unknown -> a.raw
}

/** The tab strip's height: room for the body tier and a card's art. */
internal val TAB_H = 36.dp

/** About how wide a character of the body tier is, to give [TabTitles.short] its room. */
private const val CHAR_DP = 7.1f

/** The tabs (§4): whole tabs as wide as the room allows and never narrower than a readable title, `+`, and the count. */
@Composable
fun BrowserTabStrip(h: NeueHolders, modifier: Modifier = Modifier, onPeek: (String?) -> Unit = {}, onOverview: () -> Unit = {}) {
    val browser = h.world.browser
    val c = Mu.colors
    val tabs = browser.tabs
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(TAB_H)
            .deskTarget(h, BuiltInApp.BROWSER.id, Anchor.TABS)
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
    ) {
        val fit = TabStrip.fit(maxWidth.value.toDouble(), tabs.tabs.size)
        // The first tab shown: kept while the selected one is in view, moved the least that brings it in. Plain, not state:
        // where the window stood is only ever read to decide where it stands next.
        val first = remember { intArrayOf(0) }
        val selected = tabs.tabs.indexOfFirst { it.id == tabs.selected }
        val start = TabStrip.follow(first[0], selected, fit.shown, tabs.tabs.size)
        first[0] = start
        val shown = tabs.tabs.subList(start, (start + fit.shown).coerceAtMost(tabs.tabs.size))
        val titles = tabs.tabs.map { pageTitle(h, it.parsed) }
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            shown.forEachIndexed { k, t ->
                val i = start + k
                key(t.id) { TabCell(h, t, i, t.id == tabs.selected, fit.width.dp, tabs.startsGroup(i), titles, onPeek) }
            }
            Tip("A new tab", kbd = "Ctrl T") {
                IconButton(Icons.Plus, { browser.open() }, size = TabStrip.NEW_W.dp, label = "New tab")
            }
            Box(Modifier.weight(1f))
            AllTabs(tabs.tabs.size, fit.hidden, onOverview)
        }
    }
}

/** The count of every tab, and how many the strip leaves out: opens them all as pictures. */
@Composable
private fun AllTabs(count: Int, hidden: Int, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Tip(if (hidden > 0) "$hidden more — every tab, as pictures" else "Every tab, as pictures") {
        Row(
            Modifier
                .width(TabStrip.LIST_W.dp)
                .fillMaxHeight()
                .background(animatedColor(if (hot) c.ink06 else c.paper))
                .hoverable(source)
                .cursorPointer(caption = "All tabs")
                .muClickable(interactionSource = source, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            Box(Modifier.border(1.dp, c.ink).padding(horizontal = 4.dp)) { Mono(if (hidden > 0) "+$hidden" else "$count", color = c.ink) }
            Mono("▾", color = c.ink, data = true)
        }
    }
}

@Composable
private fun TabCell(h: NeueHolders, t: Tab, index: Int, selected: Boolean, width: Dp, groupStart: Boolean, titles: List<String>, onPeek: (String?) -> Unit) {
    val browser = h.world.browser
    val c = Mu.colors
    val phone = LocalPhone.current
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val face = tabFace(h, t)
    // A pointer resting on a tab shows its page, small, after a moment; leaving it puts the picture away.
    LaunchedEffect(hot) {
        if (hot) {
            kotlinx.coroutines.delay(PEEK_AFTER_MS)
            onPeek(t.id)
        } else if (browser.peek == t.id) {
            onPeek(null)
        }
    }
    val closes = hot || selected || phone
    val room = width.value - 12f - (if (index < 9) 16f else 0f) - 22f - (if (face.card != null) 19f else 0f) - (if (closes) 24f else 0f)
    val short = remember(face.title, titles, room) { TabTitles.short(face.title, (room / CHAR_DP).toInt(), titles - face.title) }
    Tip(face.title + " · " + face.source) {
        Row(
            Modifier
                .width(width)
                .fillMaxHeight()
                .onGloballyPositioned { browser.tabX[t.id] = it.boundsInParent().left }
                .deskTarget(h, BuiltInApp.BROWSER.id, Anchor.TAB, t.id)
                .drawBehind {
                    val px = 1.dp.toPx()
                    if (selected) {
                        // Open to the toolbar below: an ink edge on three sides and paper over the strip's rule.
                        drawRect(c.paper, Offset(0f, 0f), size)
                        drawLine(c.ink, Offset(px / 2, 0f), Offset(px / 2, size.height), px)
                        drawLine(c.ink, Offset(0f, px / 2), Offset(size.width, px / 2), px)
                        drawLine(c.ink, Offset(size.width - px / 2, 0f), Offset(size.width - px / 2, size.height), px)
                    } else {
                        drawLine(c.ink12, Offset(size.width - px / 2, 7.dp.toPx()), Offset(size.width - px / 2, size.height - 7.dp.toPx()), px)
                    }
                    // Another run's pages begin here: a rule of ink the full height, quieter than the selected tab's edge.
                    if (groupStart && !selected) drawLine(c.ink45, Offset(px / 2, 4.dp.toPx()), Offset(px / 2, size.height - 4.dp.toPx()), 2 * px)
                }
                .hoverable(source)
                .cursorPointer(caption = if (selected) null else "Show")
                .onPointer(PointerEventType.Press) { e -> if (e.buttons.isTertiaryPressed) browser.close(t.id) }
                .onContextMenu { onPeek(t.id) }
                .muClickable(interactionSource = source) { onPeek(null); browser.select(t.id) }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (index < 9) Mono("${index + 1}", Modifier.width(10.dp), color = c.ink45, data = true)
            Box {
                IconView(face.glyph, 16.dp, color = if (selected) c.ink else c.ink70)
                // Ai changed it while it was not selected: a 6 dp square on the glyph's corner, paper round it, never on the title.
                if (t.mark) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(3.dp, (-3).dp)
                            .size(8.dp)
                            .background(c.paper)
                            .padding(1.dp)
                            .background(c.ink),
                    )
                }
            }
            face.card?.let { CardChip(h, it, 13.dp) }
            MuText(short, Modifier.weight(1f), style = WorldType.body(LocalMuFonts.current, phone), color = if (selected) c.ink else c.ink70, maxLines = 1)
            // ✕ on the tab in view and the one under the pointer (always, to a finger); the others give its room to the title.
            if (closes) IconButton(Icons.X, { browser.close(t.id) }, size = 24.dp, label = "Close tab")
        }
    }
}

private const val PEEK_AFTER_MS = 450L

/** The preview of a tab's page under the strip: the page, small, its whole title and where it came from. */
@Composable
internal fun TabPeek(h: NeueHolders, t: Tab, x: Float, onShow: () -> Unit) {
    val c = Mu.colors
    val face = tabFace(h, t)
    Column(
        Modifier
            // Under its tab, held inside the window: a tab at the right end opens its picture leftwards.
            .layout { m, c ->
                val p = m.measure(Constraints())
                val px = x.roundToInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0))
                layout(p.width, p.height) { p.place(px, TAB_H.roundToPx() + 2) }
            }
            .width(PEEK_W)
            .background(c.paper)
            .border(1.dp, c.ink)
            .cursorPointer(caption = "Show")
            .muClickable(onClick = onShow),
    ) {
        PagePreview(h, t.parsed, PEEK_W, PEEK_W * 0.6f, scale = 0.4f)
        Column(Modifier.fillMaxWidth().drawBehind { drawLine(c.ink12, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                face.card?.let { CardChip(h, it, 18.dp) }
                Body(face.title, Modifier.weight(1f), color = c.ink, maxLines = 3)
            }
            Small(face.source, color = c.ink70, maxLines = 1)
        }
    }
}

private val PEEK_W = 300.dp

/**
 * A page as a picture: [PageAt] laid out at the size a window would give it and drawn at a fraction, through one layer,
 * so it is drawn again only when the page changes (never a frame loop). Nothing inside it reports to the avatar or answers
 * a hand.
 */
@Composable
internal fun PagePreview(h: NeueHolders, a: WorldAddress, width: Dp, height: Dp, scale: Float = 0.32f) {
    val c = Mu.colors
    Box(Modifier.size(width, height).clipToBounds().background(c.paper)) {
        CompositionLocalProvider(LocalPagePreview provides true) {
            Layout(
                content = {
                    Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) { PageAt(h, a) }
                },
            ) { measurables, _ ->
                val w = (width.toPx() / scale).roundToInt()
                val ht = (height.toPx() / scale).roundToInt()
                val p = measurables.first().measure(Constraints.fixed(w, ht))
                layout(width.roundToPx(), height.roundToPx()) { p.place(0, 0) }
            }
        }
        // A sheet over the picture: the page's own buttons never hear a hand here.
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

/**
 * Every tab as a picture (the strip's count): its page small, its number, glyph and card, its whole title and where it
 * came from; grouped by what made them, a quiet label over each group. Typing searches the titles and addresses; ✕
 * closes a tab; a picture dragged onto another takes its place.
 */
@Composable
internal fun TabOverview(h: NeueHolders, onDone: () -> Unit) {
    val browser = h.world.browser
    val c = Mu.colors
    val tabs = browser.tabs.tabs
    var query by remember { mutableStateOf("") }
    val faces = tabs.map { tabFace(h, it) }
    val shown = tabs.indices.filter { i ->
        val q = query.trim().lowercase()
        q.isEmpty() || faces[i].title.lowercase().contains(q) || tabs[i].address.lowercase().contains(q) || faces[i].card?.lowercase()?.contains(q) == true
    }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Micro("${tabs.size} tabs", color = c.ink70)
            MuInput(query, { query = it }, Modifier.weight(1f), placeholder = "Find a tab by its title, its card or its address")
            IconButton(Icons.X, onDone, size = 32.dp, label = "Back to the page")
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val across = ((maxWidth - 32.dp) / (CARD_W + 16.dp)).toInt().coerceAtLeast(1)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (shown.isEmpty()) Help("No tab has “${query.trim()}” in its title, card or address.")
                // Groups in tab order: a run of tabs made by one thing is one group (none while searching).
                val groups = if (query.isBlank()) shown.fold(mutableListOf<MutableList<Int>>()) { acc, i ->
                    if (acc.isEmpty() || browser.tabs.startsGroup(i)) acc += mutableListOf(i) else acc.last() += i
                    acc
                } else listOf(shown)
                groups.forEach { g ->
                    val lead = tabs[g.first()]
                    if (query.isBlank() && lead.group != null) Micro(groupLabel(faces[g.first()].source, g.size), color = c.ink70)
                    g.chunked(across).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { i ->
                                key(tabs[i].id) {
                                    OverviewCard(h, tabs[i], i, faces[i], tabs[i].id == browser.tabs.selected, query.isBlank(), bounds, onDone)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A group's label: what made it, and how many pages. */
private fun groupLabel(source: String, n: Int): String = source.substringBefore(" · ran").substringBefore(" · pinned").ifBlank { "Pages" } + " · $n page" + if (n == 1) "" else "s"

private val CARD_W = 236.dp

@Composable
private fun OverviewCard(
    h: NeueHolders,
    t: Tab,
    index: Int,
    face: TabFace,
    selected: Boolean,
    reorders: Boolean,
    bounds: MutableMap<String, Rect>,
    onDone: () -> Unit,
) {
    val browser = h.world.browser
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    // Where the hand has carried the picture: state read only in the layout phase (`offset {}`), so a drag never recomposes.
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val at = remember { arrayOf(Rect.Zero) }
    Column(
        Modifier
            .width(CARD_W)
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .onGloballyPositioned { if (!dragging) { at[0] = it.windowBounds(); bounds[t.id] = at[0] } }
            .border(if (selected) 2.dp else 1.dp, if (selected || hot) c.ink else c.ink25)
            .background(c.paper)
            .hoverable(source)
            .cursorPointer(caption = if (reorders) "Show, or drag to move" else "Show")
            .let { m ->
                if (!reorders) m else m.pointerInput(t.id) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            val centre = at[0].center + drag
                            val to = bounds.entries.minByOrNull { (_, r) -> (r.center - centre).getDistance() }?.key
                            drag = Offset.Zero
                            dragging = false
                            val target = browser.tabs.tabs.indexOfFirst { it.id == to }
                            if (to != null && to != t.id && target >= 0) browser.move(t.id, target)
                        },
                        onDragCancel = { drag = Offset.Zero; dragging = false },
                    ) { change, d ->
                        change.consume()
                        drag += d
                    }
                }
            }
            .muClickable(interactionSource = source) { browser.select(t.id); onDone() },
    ) {
        PagePreview(h, t.parsed, CARD_W, CARD_W * 0.58f, scale = 0.26f)
        Column(
            Modifier.fillMaxWidth().drawBehind { drawLine(c.ink12, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (index < 9) Mono("${index + 1}", color = c.ink45, data = true)
                Box {
                    IconView(face.glyph, 16.dp, color = c.ink)
                    if (t.mark) Box(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).size(8.dp).background(c.paper).padding(1.dp).background(c.ink))
                }
                face.card?.let { CardChip(h, it, 14.dp) }
                Box(Modifier.weight(1f))
                if (t.kept) Micro("Kept", color = c.ink70)
                IconButton(Icons.X, { browser.close(t.id) }, size = 24.dp, label = "Close tab")
            }
            Body(face.title, color = c.ink, maxLines = 2)
            Small(face.source, color = c.ink70, maxLines = 1)
        }
    }
}

/** This node's bounds in the window, for the overview's drop: the nearest picture's centre takes the dragged tab's place. */
private fun androidx.compose.ui.layout.LayoutCoordinates.windowBounds(): Rect {
    val p = localToWindow(Offset.Zero)
    return Rect(p, Size(size.width.toFloat(), size.height.toFloat()))
}
