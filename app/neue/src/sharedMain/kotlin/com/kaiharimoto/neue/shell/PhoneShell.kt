package com.kaiharimoto.neue.shell

import androidx.compose.foundation.border

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.builder.DeckNameField
import com.kaiharimoto.neue.builder.Standing
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuIcon
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuShell
import com.kaiharimoto.neue.theme.MuType

/**
 * The phone's bar (v1.3.5): one 48dp line with nothing hidden off its end. The
 * desk's bar holds about 1400dp of tools; on a 412dp phone it ran off the edge,
 * and the Update pill with it — the app could not update itself. So this is a
 * different bar, not a squeezed copy: the page's name (or, in the builder, the
 * deck's, editable where it stands, with its standing), undo and redo, the
 * Update chip whenever there is one, and the overflow — which holds every tool
 * the desk's bar has.
 */
@Composable
fun PhoneBar(
    neue: NeueState,
    state: DeckBuilderState,
    update: String?,
    onUpdate: () -> Unit,
    menu: (Offset) -> List<MenuEntry>,
    modifier: Modifier = Modifier,
    working: Boolean = false,
    /** Ai's marquee while it is on (1.0.52), told whether the Update chip is taking room from it. */
    ai: (@Composable (narrow: Boolean) -> Unit)? = null,
) {
    val c = Mu.colors
    var moreAt by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier
            .fillMaxWidth()
            .height(MuShell.top)
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (neue.page == Page.BUILDER) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                DeckNameField(state, Modifier.fillMaxWidth(), small = true)
                Standing(state, neue)
            }
            IconButton(Icons.Undo, state::undo, enabled = state.canUndo, size = 40.dp, label = "Undo", reason = "Nothing to undo")
            IconButton(Icons.Redo, state::redo, enabled = state.canRedo, size = 40.dp, label = "Redo", reason = "Nothing to redo")
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                neue.page.numeral?.let { Mono(it.toString().padStart(2, '0'), color = c.ink45) }
                MuText(neue.page.title, style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 1)
            }
        }
        if (working) Breathe(running = true)
        if (update != null) {
            // Never in the overflow: an update is what a phone that cannot fit the bar missed.
            Tip("A newer build is ready: read what changed and install it") {
                Row(
                    Modifier
                        .height(32.dp)
                        .background(c.ink)
                        .cursorPointer(caption = "Update")
                        .muClickable(onClick = onUpdate)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Micro("Update", color = c.paper) }
            }
        }
        // Never in the overflow either: shorter while the Update chip is out.
        if (ai != null) ai(update != null)
        Box(Modifier.onGloballyPositioned { moreAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
            Tip("Everything else: import, export, save, search, rotate, updates") {
                IconButton(Icons.More, { neue.menu = MenuSpec(moreAt, menu(moreAt)) }, size = 40.dp, label = "More")
            }
        }
    }
}

/**
 * The pages a phone's tab bar holds, in the rail's order, Settings last. Shootout (1.1.2) is a thumb's page — a
 * trial is a glance and a swipe — so it has a tab; Present, Duel and Ai World are in the ⋯ menu.
 */
private val TABS = listOf(Page.BUILDER, Page.DECKS, Page.SIDING, Page.FORMAT, Page.PREP, Page.SHOOTOUT, Page.SETTINGS)

/**
 * The phone's index (v1.3.5): the rail's five pages as tabs along the bottom,
 * under the thumbs — numeral over word, the page you are on inverted. Lying
 * down, the same five stand as a strip down the left ([vertical]), since there
 * a row along the bottom is a row off every card's height.
 */
@Composable
fun TabBar(neue: NeueState, modifier: Modifier = Modifier, vertical: Boolean = false, onSearch: () -> Unit = {}) {
    val c = Mu.colors
    if (vertical) {
        Column(
            modifier
                .width(MuShell.strip)
                .fillMaxHeight()
                .background(c.paper)
                .drawBehind { drawLine(c.ink, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) },
        ) {
            TABS.forEach { page ->
                Tab(page, neue.page == page, Modifier.fillMaxWidth().height(48.dp), compact = true) { neue.go(page) }
                HRule()
            }
            HRule(color = c.ink)
            Box(
                Modifier.fillMaxWidth().height(48.dp).cursorPointer(caption = "Search").muClickable(onClick = onSearch),
                contentAlignment = Alignment.Center,
            ) { MuIcon(Icons.Search, c.ink, Modifier.size(18.dp)) }
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(PHONE_TABS)
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) },
    ) {
        TABS.forEach { page ->
            Tab(page, neue.page == page, Modifier.weight(1f).fillMaxHeight()) { neue.go(page) }
        }
    }
}

/** How tall the phone's tab bar is: a thumb's target, and the rail's row. */
val PHONE_TABS = 56.dp

@Composable
private fun Tab(page: Page, active: Boolean, modifier: Modifier, compact: Boolean = false, onClick: () -> Unit) {
    Inverted(active) {
        val c = Mu.colors
        Column(
            modifier
                .background(if (active) c.paper else Color.Transparent)
                .cursorPointer(caption = page.title)
                .muClickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (page.numeral != null) {
                Mono(page.numeral.toString().padStart(2, '0'), color = if (active) c.ink else c.ink45)
            } else {
                MuIcon(Icons.Settings, if (active) c.ink else c.ink45, Modifier.size(14.dp))
            }
            // Seven tabs across 360 dp: the words a size smaller, so "Settings" and "Shootout" fit their tab.
            if (!compact) Micro(page.title, Modifier.padding(top = 2.dp), color = c.ink, size = 9.sp)
        }
    }
}
