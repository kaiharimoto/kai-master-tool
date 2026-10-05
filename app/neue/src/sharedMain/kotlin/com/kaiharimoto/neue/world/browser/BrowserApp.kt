package com.kaiharimoto.neue.world.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.neue.world.desk.deskTarget
import com.kaiharimoto.mastertool.core.world.desk.Tab
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.world.desk.IconView

/*
 * The World's Browser (`docs/world/DESKTOP.md` §4): every board a page, one per tab, as Chrome has them — tabs, back and
 * forward, an address line for `world://` addresses, Keep, and the page itself, drawn at the window's size by the boards'
 * own painters. On a phone the tabs fold into a count beside the address.
 */

/**
 * The Browser's window body. [tabsHere] draws the tab strip on top; the desktop may carry it in the window's title bar
 * instead ([BrowserTabStrip]) and pass false.
 */
@Composable
fun BrowserApp(h: NeueHolders, modifier: Modifier = Modifier, tabsHere: Boolean = true) {
    val browser = h.world.browser
    val phone = LocalPhone.current
    var listing by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        if (tabsHere && !phone) BrowserTabStrip(h)
        BrowserToolbar(h, phone, onTabs = { listing = !listing })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val tab = browser.current
            when {
                listing && phone -> TabList(h) { listing = false }
                tab == null -> HomePage(h, null)
                else -> key(tab.id) { PageAt(h, tab.parsed) }
            }
        }
    }
}

/** The tabs (§4): square cells 32 dp tall, 96–220 dp wide, the selected one open to the toolbar below; `+` for a new tab. */
@Composable
fun BrowserTabStrip(h: NeueHolders, modifier: Modifier = Modifier) {
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
        val n = tabs.tabs.size.coerceAtLeast(1)
        val w = ((maxWidth - TAB_H) / n).coerceIn(96.dp, 220.dp)
        Row(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tabs.tabs.forEach { t -> key(t.id) { TabCell(h, t, t.id == tabs.selected, w) } }
            Tip("A new tab", kbd = "Ctrl T") {
                IconButton(Icons.Plus, { browser.open() }, size = TAB_H, label = "New tab")
            }
        }
    }
}

private val TAB_H = 32.dp

@Composable
private fun TabCell(h: NeueHolders, t: Tab, selected: Boolean, width: androidx.compose.ui.unit.Dp) {
    val browser = h.world.browser
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val title = pageTitle(h, t.parsed)
    Row(
        Modifier
            .width(width)
            .fillMaxHeight()
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
                    drawLine(c.ink12, Offset(size.width - px / 2, 6.dp.toPx()), Offset(size.width - px / 2, size.height - 6.dp.toPx()), px)
                }
            }
            .hoverable(source)
            .cursorPointer(caption = if (selected) null else "Show")
            .onPointer(PointerEventType.Press) { e -> if (e.buttons.isTertiaryPressed) browser.close(t.id) }
            .muClickable(interactionSource = source) { browser.select(t.id) }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconView(pageGlyph(h, t.parsed), 16.dp, color = if (selected) c.ink else c.ink45)
        Small(title, Modifier.weight(1f), color = if (selected) c.ink else c.ink45, maxLines = 1)
        // Ai changed it while it was not selected: a 6 dp ink square, until it is.
        if (t.mark) Box(Modifier.size(6.dp).background(c.ink))
        // ✕ on the tab in view and the one under the pointer (always, to a finger); the others give its room to the title.
        if (hot || selected || LocalPhone.current) IconButton(Icons.X, { browser.close(t.id) }, size = 24.dp, label = "Close tab")
    }
}

/** The toolbar (§4): ← → ↻, the address, Keep, and ⋯. On a phone the tabs are a count here. */
@Composable
private fun BrowserToolbar(h: NeueHolders, phone: Boolean, onTabs: () -> Unit) {
    val browser = h.world.browser
    val c = Mu.colors
    val tab = browser.current
    var typed by remember(tab?.id, tab?.address) { mutableStateOf(tab?.address ?: WorldAddress.HOME) }
    var focused by remember { mutableStateOf(false) }
    var moreAt by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(browser.addressTick) { if (browser.addressTick > 0) runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tip("Back", kbd = "Alt ←") { IconButton(Icons.ChevronLeft, { browser.back() }, enabled = tab?.back?.isNotEmpty() == true, label = "Back", reason = "Nowhere to go back to") }
        Tip("Forward", kbd = "Alt →") { IconButton(Icons.ChevronRight, { browser.forward() }, enabled = tab?.forward?.isNotEmpty() == true, label = "Forward", reason = "Nowhere to go forward to") }
        if (!phone) Tip("Show it again") { IconButton(Icons.Refresh, { h.world.reload() }, label = "Reload") }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            MuInput(
                if (focused) typed else (tab?.address ?: WorldAddress.HOME),
                { typed = it },
                Modifier.fillMaxWidth(),
                placeholder = "world://home",
                mono = true,
                dense = true,
                focusRequester = focus,
                onFocusChange = { f ->
                    focused = f
                    if (f) typed = tab?.address ?: WorldAddress.HOME
                },
                onSubmit = { browser.go(typed.trim().ifEmpty { WorldAddress.HOME }) },
            )
        }
        if (phone) {
            // The tabs fold into a count beside the address (§4).
            Box(
                Modifier
                    .size(32.dp)
                    .border(1.dp, c.ink)
                    .cursorPointer(caption = "Tabs")
                    .muClickable(onClick = onTabs),
                contentAlignment = Alignment.Center,
            ) { Mono(browser.tabs.tabs.size.toString(), color = c.ink) }
        }
        if (tab != null) {
            Tip(if (tab.kept) "Kept: never put away" else "Keep this tab: it is never put away at the end of Ai's turn") {
                WordToggle("Keep", tab.kept) { browser.keep(tab.id, !tab.kept) }
            }
        }
        Box(Modifier.onGloballyPositioned { moreAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
            IconButton(Icons.More, {
                h.neue.menu = MenuSpec(moreAt, moreMenu(h, tab))
            }, label = "More")
        }
    }
}

private fun moreMenu(h: NeueHolders, tab: Tab?): List<MenuEntry> {
    val browser = h.world.browser
    val address = tab?.parsed
    val board = (address as? WorldAddress.Board)?.let { h.world.open?.board(it.id) }
    return listOf(
        MenuEntry("Copy address", enabled = tab != null, reason = "No page open") {
            tab?.let { Platform.copy(it.address); h.neue.note = Note("Copied") }
        },
        MenuEntry("Open the source file", enabled = board?.source != null, reason = "No file made this page") {
            board?.source?.let { browser.go(WorldAddress.File(it).format()) }
        },
        MenuEntry("Show the run", enabled = board != null && runOf(h, board.id) != null, reason = "No run made this page") {
            board?.let { b -> runOf(h, b.id)?.let { browser.go(WorldAddress.Run(it.t).format()) } }
        },
        MenuEntry("Open in a new tab", enabled = tab != null, reason = "No page open") { tab?.let { browser.open(it.address) } },
        MenuEntry("Home", hint = "Every page of this world") { browser.go(WorldAddress.HOME) },
        MenuEntry("Take the page down", separatorBefore = true, enabled = board != null, reason = "Only a board's page can be taken down") {
            board?.let { b -> h.neue.confirmTakeDown(h, b.id, b.title) }
        },
    )
}

/** The tabs as a list, on a phone (§4): a tap shows one, ✕ closes it. */
@Composable
private fun TabList(h: NeueHolders, onDone: () -> Unit) {
    val browser = h.world.browser
    val c = Mu.colors
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        items(browser.tabs.tabs.size) { i ->
            val t = browser.tabs.tabs[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(if (t.id == browser.tabs.selected) c.ink06 else c.paper)
                    .cursorPointer(caption = "Show")
                    .muClickable { browser.select(t.id); onDone() }
                    .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconView(pageGlyph(h, t.parsed), 20.dp)
                Column(Modifier.weight(1f)) {
                    Small(pageTitle(h, t.parsed), color = c.ink, maxLines = 1)
                    Mono(t.address, color = c.ink45)
                }
                if (t.mark) Box(Modifier.size(6.dp).background(c.ink))
                IconButton(Icons.X, { browser.close(t.id) }, size = 40.dp, label = "Close tab")
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                com.kaiharimoto.neue.kit.MuButton("New tab", { browser.open(); onDone() }, icon = Icons.Plus)
            }
        }
    }
}

/** "Take the page down" asks first: a board is Ai's work, and the person's call to remove. */
internal fun com.kaiharimoto.neue.NeueState.confirmTakeDown(h: NeueHolders, id: String, title: String) {
    menu = MenuSpec(
        menu?.at ?: Offset.Zero,
        listOf(
            MenuEntry("Take down “${title.ifBlank { id }}”", hint = "Its page goes; its tabs say so") {
                h.world.takeDown(id)
                note = Note("Took the page down")
            },
            MenuEntry("Keep it"),
        ),
    )
}

/** Who opened [t]: the person, or Ai in its turn. */
internal fun Tab.byWords(): String = if (by == WorldEvent.AI) "Ai" else "you"
