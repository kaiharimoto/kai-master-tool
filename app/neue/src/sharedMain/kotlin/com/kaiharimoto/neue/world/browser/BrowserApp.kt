package com.kaiharimoto.neue.world.browser

import androidx.compose.foundation.layout.heightIn
import com.kaiharimoto.neue.world.type.Body
import com.kaiharimoto.neue.world.CardChip
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
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.world.type.Small
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
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (tabsHere && !phone) {
                BrowserTabStrip(
                    h,
                    onPeek = { id -> browser.peek = id },
                    onOverview = { browser.peek = null; browser.overview = !browser.overview },
                )
            }
            BrowserToolbar(h, phone, onTabs = { listing = !listing })
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val tab = browser.current
                when {
                    listing && phone -> TabList(h) { listing = false }
                    browser.overview && !phone -> TabOverview(h) { browser.overview = false }
                    tab == null -> HomePage(h, null)
                    else -> key(tab.id) { PageAt(h, tab.parsed) }
                }
            }
        }
        val t = browser.peek?.let { browser.tabs.tab(it) }
        if (t != null && !browser.overview && t.id != browser.tabs.selected) {
            TabPeek(h, t, browser.tabX[t.id] ?: 0f) {
                browser.peek = null
                browser.select(t.id)
            }
        }
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
                    .heightIn(min = 64.dp)
                    .background(if (t.id == browser.tabs.selected) c.ink06 else c.paper)
                    .cursorPointer(caption = "Show")
                    .muClickable { browser.select(t.id); onDone() }
                    .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val face = tabFace(h, t)
                if (i < 9) Mono("${i + 1}", Modifier.width(12.dp), color = c.ink45, data = true)
                IconView(face.glyph, 20.dp)
                face.card?.let { CardChip(h, it, 22.dp) }
                Column(Modifier.weight(1f)) {
                    Body(face.title, color = c.ink, maxLines = 2)
                    Small(face.source, color = c.ink70, maxLines = 1)
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
