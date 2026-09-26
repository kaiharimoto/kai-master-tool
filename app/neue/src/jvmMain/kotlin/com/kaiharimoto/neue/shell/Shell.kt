package com.kaiharimoto.neue.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.Mark
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuIcon
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuShell
import com.kaiharimoto.neue.theme.MuType

/** What the title bar reports on the right: a status in micro caps, with a square that breathes while it is true. */
data class ShellStatus(val text: String, val running: Boolean)

/**
 * The 40px title bar (§4): wordmark left; the update pill and the search
 * trigger right; system status last. The window keeps its native frame, so
 * this is the app's own first row rather than a replacement for it.
 */
@Composable
fun TitleBar(
    neue: NeueState,
    status: ShellStatus,
    update: String?,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Mu.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(MuShell.top)
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { neue.go(Page.DECKS) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mark(20.dp, ink = c.ink, paper = c.paper)
            MuText("NEUE MASTER TOOL", style = MuType.wordmark(LocalMuFonts.current))
        }
        Box(Modifier.weight(1f))
        if (update != null) {
            Tip("A newer build is ready. Click to read what changed and install it") {
                Pill(onUpdate) {
                    Breathe(color = Mu.colors.ink)
                    Micro("Update · ", color = Mu.colors.ink)
                    Mono(update, color = Mu.colors.ink)
                }
            }
        }
        SearchTrigger { neue.paletteOpen = true }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Breathe(running = status.running)
            Micro(status.text, color = c.ink70)
        }
    }
}

@Composable
private fun Pill(onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Inverted(hovered) {
        Row(
            Modifier
                .height(28.dp)
                .background(Mu.colors.paper)
                .border(1.dp, c.ink)
                .hoverable(source)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun SearchTrigger(onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val tone = animatedColor(if (hovered) c.ink else c.ink45)
    Row(
        Modifier
            .height(28.dp)
            .border(1.dp, animatedColor(if (hovered) c.ink else c.ink25))
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MuIcon(Icons.Search, tone, Modifier.width(14.dp).height(14.dp))
        Micro("Search", color = tone)
        Kbd(DeskShortcuts.chordFor(DeskAction.PALETTE)?.let(DeskShortcuts::kbd) ?: "Ctrl K")
    }
}

/**
 * The index rail (§4): 232px, numbered rows 56 high with a hairline between
 * them, the active one inverted, a `→` that slides in on hover. Settings and
 * the theme sit below the rule at the bottom.
 */
@Composable
fun Rail(neue: NeueState, version: String, counts: Map<Page, String>, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Column(
        modifier
            .width(MuShell.rail)
            .fillMaxHeight()
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) },
    ) {
        Page.entries.filter { it.numeral != null }.forEach { page ->
            RailRow(page, neue.page == page, counts[page]) { neue.go(page) }
            HRule()
        }
        Box(Modifier.weight(1f))
        HRule(color = c.ink)
        RailRow(Page.SETTINGS, neue.page == Page.SETTINGS, null) { neue.go(Page.SETTINGS) }
        HRule(color = c.ink)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicroLink(if (neue.prefs.theme == NeueTheme.PAPER) "Ink" else "Paper", neue::toggleTheme)
            MicroLink("Keys", { neue.helpOpen = true })
            Box(Modifier.weight(1f))
            Mono("v$version")
        }
    }
}

@Composable
private fun RailRow(page: Page, active: Boolean, count: String?, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val arrow by animateFloatAsState(if (hovered || active) 1f else 0f, tween(MuMotion.FAST, easing = MuMotion.ease), label = "arrow")
    val shift by animateDpAsState(if (hovered || active) 0.dp else (-4).dp, tween(MuMotion.FAST, easing = MuMotion.ease), label = "shift")
    val chord = when (page) {
        Page.DECKS -> DeskAction.GO_DECKS
        Page.BUILDER -> DeskAction.GO_BUILDER
        Page.ODDS -> DeskAction.GO_ODDS
        Page.STATS -> DeskAction.GO_STATS
        Page.SETTINGS -> DeskAction.GO_SETTINGS
    }.let { DeskShortcuts.chordFor(it)?.let(DeskShortcuts::kbd) }
    Inverted(active) {
        val inner = Mu.colors
        Tip(page.title, kbd = chord) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(MuShell.railRow)
                    .background(animatedColor(if (active) inner.paper else if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null, onClick = onClick)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (page.numeral != null) Numeral(page.numeral, color = if (active) inner.ink.copy(alpha = 0.6f) else c.ink45)
                MuText(page.title, Modifier.weight(1f), MuType.h2(LocalMuFonts.current), color = inner.ink, maxLines = 1)
                if (count != null) Mono(count, color = if (active) inner.ink.copy(alpha = 0.6f) else c.ink45)
                MuText(
                    "→",
                    Modifier.offset(x = shift).alpha(arrow),
                    MuType.h2(LocalMuFonts.current),
                    color = inner.ink,
                )
            }
        }
    }
}
