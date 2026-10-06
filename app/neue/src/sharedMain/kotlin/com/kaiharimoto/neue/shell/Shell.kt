package com.kaiharimoto.neue.shell

import com.kaiharimoto.mastertool.core.ai.chessy.ChessyPoint
import com.kaiharimoto.neue.ai.chessy.chessySpot
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.cursor.cursorPointer
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.offline.WorkReadout
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.Mark
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuIcon
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.WordToggle
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
 * The window's one bar (§4, bent by kai for 1.0.10): the mark and the page you
 * are on on the left, then whatever the page puts in it — the builder puts the
 * deck's name, its standing, its tools and Save — then the update pill and
 * immersive mode. It was two bars, the app's and the page's, and the app's
 * carried a search trigger and a card count: search lives on the rail now,
 * beside Settings, and the count is the pool's, where it is read.
 *
 * While the app fetches something in the background — the card pool, the
 * full-size art — [work] stands before the update pill: what, how far, and a
 * thin bar, with the whole story in its tip; a click opens Settings, where the
 * same bars are, beside the controls.
 */
@Composable
fun TitleBar(
    neue: NeueState,
    update: String?,
    onUpdate: () -> Unit,
    onImmersive: () -> Unit = {},
    modifier: Modifier = Modifier,
    work: WorkReadout? = null,
    onWork: () -> Unit = {},
    /** Before the bar's own switches: the assistant's (1.0.43). */
    trailing: @Composable () -> Unit = {},
    /** Full screen; a page whose own row carries it (Duel, 1.0.78) leaves it out. Auto zen is in Settings (1.0.88). */
    switches: Boolean = true,
    content: @Composable RowScope.(narrow: Boolean) -> Unit = {},
) {
    val c = Mu.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val narrow = maxWidth < 1500.dp
        Row(
            Modifier
                .fillMaxWidth()
                .height(MuShell.top)
                .background(c.paper)
                .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .cursorPointer(caption = if (neue.railPinned) "Builder →" else if (neue.railHeld) "Fold the index" else "The index")
                    // The logo opens the index (kai, 1.0.89); with the index pinned out, it goes home to the builder.
                    .muClickable { if (neue.railPinned) neue.go(Page.BUILDER) else neue.railHeld = !neue.railHeld },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Mark(20.dp, ink = c.ink, paper = c.paper)
                // The wordmark gives way before anything that does something — and on the
                // builder, whose title is the deck's name, before that (1.0.41).
                if (!narrow && neue.page != Page.BUILDER) MuText("NEUE MASTER TOOL", style = MuType.wordmark(LocalMuFonts.current))
            }
            // Where you are, because the rail that says so is folded away.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Micro("/", color = c.ink25)
                neue.page.numeral?.let { Mono(it.toString().padStart(2, '0'), color = c.ink70) }
                Micro(neue.page.title, color = c.ink70)
                Micro("/", color = c.ink25)
            }
            content(narrow)
            if (work != null) {
                Tip(work.detail) { WorkChip(work, narrow, onWork) }
            }
            if (update != null) {
                Tip("A newer build is ready. Click to read what changed and install it") {
                    Pill(onUpdate) {
                        Breathe(color = Mu.colors.ink)
                        Micro("Update · ", color = Mu.colors.ink)
                        Mono(update, color = Mu.colors.ink)
                    }
                }
            }
            trailing()
            if (switches) {
            // Auto zen lives in Settings since 1.0.88 (kai); the bar keeps Full screen alone.
            Tip(if (neue.immersive) "Leave immersive mode" else "Immersive mode: full screen, bars out of the way", kbd = DeskShortcuts.chordFor(DeskAction.IMMERSIVE)?.let(DeskShortcuts::kbd)) {
                IconButton(if (neue.immersive) Icons.Minimize else Icons.Maximize, onImmersive, toggled = neue.immersive, size = 32.dp, label = if (neue.immersive) "Leave full screen" else "Full screen")
            }
            }
        }
    }
}

/**
 * Background work in the bar: `CARD ART 42%` over a 3px track. Narrow, the words
 * give way and the figure and the bar stay.
 */
@Composable
private fun WorkChip(work: WorkReadout, narrow: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier
            .hoverable(source)
            .cursorPointer(caption = "Settings →")
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Breathe(running = true)
            if (!narrow) Micro(work.label, color = if (hovered) c.ink else c.ink70)
            Mono(work.figure, color = c.ink)
        }
        Progress(work.fraction, Modifier.width(if (narrow) 72.dp else 112.dp))
    }
}

@Composable
private fun Pill(onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Inverted(hovered) {
        Row(
            Modifier
                .height(28.dp)
                .background(Mu.colors.paper)
                .border(1.dp, c.ink)
                .hoverable(source)
                .cursorPointer(showsWords = true)
                .muClickable(interactionSource = source, onClick = onClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@Composable
private fun SearchTrigger(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    val tone = animatedColor(if (hovered) c.ink else c.ink45)
    Row(
        modifier
            .height(32.dp)
            .border(1.dp, animatedColor(if (hovered) c.ink else c.ink25))
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MuIcon(Icons.Search, tone, Modifier.width(14.dp).height(14.dp))
        Micro("Search", Modifier.weight(1f), color = tone)
        Kbd(DeskShortcuts.chordFor(DeskAction.PALETTE)?.let(DeskShortcuts::kbd) ?: "Ctrl K")
    }
}

/**
 * The index rail (§4): 232px, numbered rows 56 high with a hairline between
 * them, the active one inverted, a `→` that slides in on hover. What is being
 * fetched, search and Settings sit below the rule at the bottom, and the theme
 * under them.
 */
@Composable
fun Rail(
    neue: NeueState,
    version: String,
    counts: Map<Page, String>,
    modifier: Modifier = Modifier,
    status: ShellStatus? = null,
    art: String? = null,
) {
    if (neue.touchFirst) {
        IndexStrip(neue, counts, modifier, status)
        return
    }
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
        // The art's line stood inside the pool's block, and so showed only while the
        // pool synced: it stands on its own now.
        if (status != null || art != null) {
            // What the app is fetching, when it is: the card pool, the full-size art.
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Breathe(running = status.running)
                        Micro(status.text, color = c.ink70)
                    }
                }
                if (art != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Breathe(running = true)
                        Micro(art, color = c.ink70)
                    }
                }
            }
        }
        HRule(color = c.ink)
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            SearchTrigger(Modifier.fillMaxWidth()) { neue.paletteOpen = true }
        }
        HRule()
        RailRow(Page.SETTINGS, neue.page == Page.SETTINGS, null) { neue.go(Page.SETTINGS) }
        HRule(color = c.ink)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Light and dark, as a light: the sun on paper, the moon on ink (kai, 1.0.15).
            val paper = neue.prefs.theme == NeueTheme.PAPER
            Tip(if (paper) "Switch to ink, the dark theme" else "Switch to paper, the light theme", kbd = DeskShortcuts.chordFor(DeskAction.TOGGLE_THEME)?.let(DeskShortcuts::kbd), above = true) {
                IconButton(if (paper) Icons.Sun else Icons.Moon, neue::toggleTheme, size = 24.dp, label = if (paper) "Ink" else "Paper")
            }
            MicroLink("Keys", { neue.helpOpen = true })
            // A tablet's index is always out: pinning means nothing there (touch swarm, rec 27).
            if (!neue.immersive && !neue.touchFirst) {
                Tip(if (neue.prefs.railPinned) "Fold the index away until the pointer reaches the left edge" else "Keep the index out", above = true) {
                    MicroLink(if (neue.prefs.railPinned) "Unpin" else "Pin", { neue.update { it.copy(railPinned = !it.railPinned) } })
                }
            }
            Box(Modifier.weight(1f))
            Mono("v$version")
        }
    }
}

@Composable
private fun RailRow(page: Page, active: Boolean, count: String?, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    val arrow by animateFloatAsState(if (hovered || active) 1f else 0f, tween(MuMotion.FAST, easing = MuMotion.ease), label = "arrow")
    val shift by animateDpAsState(if (hovered || active) 0.dp else (-4).dp, tween(MuMotion.FAST, easing = MuMotion.ease), label = "shift")
    val chord = when (page) {
        Page.DECKS -> DeskAction.GO_DECKS
        Page.BUILDER -> DeskAction.GO_BUILDER
        Page.SIDING -> DeskAction.GO_SIDING
        Page.FORMAT -> DeskAction.GO_FORMAT
        Page.PREP -> DeskAction.GO_PREP
        Page.PRESENT -> DeskAction.GO_PRESENT
        Page.DUEL -> DeskAction.GO_DUEL
        Page.WORLD -> DeskAction.GO_WORLD
        Page.SHOOTOUT -> DeskAction.GO_SHOOTOUT
        Page.SETTINGS -> DeskAction.GO_SETTINGS
    }.let { DeskShortcuts.chordFor(it)?.let(DeskShortcuts::kbd) }
    Inverted(active) {
        val inner = Mu.colors
        Tip(page.title, kbd = chord) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(MuShell.railRow)
                    .chessySpot(ChessyPoint.page(page.name.lowercase()))
                    .background(animatedColor(if (active) inner.paper else if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(showsWords = true)
                    .muClickable(interactionSource = source, onClick = onClick)
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

/**
 * The index on a tablet (touch swarm, rec 1): a strip of numerals, [MuShell.strip]
 * wide, in place of the 232dp rail — which on a 1280dp tablet took the deck's
 * width for four rows of words. Every row is still a 56dp square to tap, in the
 * same order as the rail's, so nothing moved but the words: the page's own name
 * is in the window's bar, and holding a numeral reads its name (rec 8).
 */
@Composable
private fun IndexStrip(neue: NeueState, counts: Map<Page, String>, modifier: Modifier, status: ShellStatus?) {
    val c = Mu.colors
    Column(
        modifier
            .width(MuShell.strip)
            .fillMaxHeight()
            .background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) },
    ) {
        Page.entries.filter { it.numeral != null }.forEach { page ->
            StripCell(page.title, neue.page == page, onClick = { neue.go(page) }) { active ->
                Mono(page.numeral!!.toString().padStart(2, '0'), color = if (active) Mu.colors.ink else c.ink70)
            }
            HRule()
        }
        Box(Modifier.weight(1f))
        if (status != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Breathe(running = status.running) }
        }
        HRule(color = c.ink)
        // The gestures, one tap away (touch swarm, rec 20): the desk's rail has its Keys link.
        StripCell("Gestures", neue.helpOpen, onClick = { neue.helpOpen = true }) { active ->
            Mono("?", color = if (active) Mu.colors.ink else c.ink70)
        }
        HRule()
        StripCell("Search", false, onClick = { neue.paletteOpen = true }) { MuIcon(Icons.Search, c.ink, Modifier.width(18.dp).height(18.dp)) }
        HRule()
        StripCell(Page.SETTINGS.title, neue.page == Page.SETTINGS, onClick = { neue.go(Page.SETTINGS) }) { active ->
            MuIcon(Icons.Settings, if (active) Mu.colors.ink else c.ink, Modifier.width(18.dp).height(18.dp))
        }
        HRule(color = c.ink)
        val paper = neue.prefs.theme == NeueTheme.PAPER
        StripCell(if (paper) "Ink, the dark theme" else "Paper, the light theme", false, onClick = neue::toggleTheme) {
            MuIcon(if (paper) Icons.Sun else Icons.Moon, c.ink, Modifier.width(18.dp).height(18.dp))
        }
    }
}

@Composable
private fun StripCell(name: String, active: Boolean, onClick: () -> Unit, content: @Composable (Boolean) -> Unit) {
    val c = Mu.colors
    Inverted(active) {
        Tip(name) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(MuShell.railRow)
                    .background(if (active) Mu.colors.paper else Color.Transparent)
                    .cursorPointer(caption = name)
                    .muClickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { content(active) }
        }
    }
}
