package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.Notice
import com.kaiharimoto.mastertool.core.world.desk.NoticeKind
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.mastertool.core.world.desk.WorldNotices
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.RequestFocusOnce
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.newWorld
import com.kaiharimoto.neue.world.system.openAddress
import com.kaiharimoto.neue.world.system.openFile
import com.kaiharimoto.neue.world.system.openInEditor
import kotlinx.coroutines.delay

/*
 * What stands over the desktop (`docs/world/DESKTOP.md` §2.4, §6.3, §9.1): the launcher (`⊞`, `Alt 0`) — search, the
 * apps, the worlds, Ask Ai —; the strip of open windows while `Ctrl \`` is held; one toast at a time over the tray's
 * corner, and the tray; the desk's own dialogs. Esc closes each, never a window.
 */

@Composable
internal fun BoxScope.DeskOverlays(h: NeueHolders, finger: Boolean) {
    val desk = h.world.desk
    val c = Mu.colors
    val bottom = taskbarHeight(finger)
    if (desk.launcherOpen) {
        // The overlay at 90 % paper behind the panel; a press there closes it.
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = bottom)
                .background(c.paper.copy(alpha = 0.9f))
                .muClickable { desk.launcherOpen = false },
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = bottom + 8.dp)
                .width(560.dp)
                .heightIn(max = 640.dp)
                .background(c.paper)
                .border(1.dp, c.ink)
                .muClickable { },
        ) {
            LauncherContent(h, phone = false) { desk.launcherOpen = false }
        }
    }
    desk.switching?.let { s -> SwitchStrip(h, s) }
    if (desk.trayOpen) Tray(h, bottom)
    desk.notices.toast?.let { t -> Toast(h, t, bottom) }
    DeskDialogs(h)
}

/** One thing the launcher's search found. */
private data class Found(val kind: String, val title: String, val open: () -> Unit)

/** The launcher's panel, on the desk and full screen on a phone (§2.4, §2.5). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LauncherContent(h: NeueHolders, phone: Boolean, close: () -> Unit) {
    val world = h.world
    val desk = world.desk
    val c = Mu.colors
    var q by remember { mutableStateOf("") }
    var everyWorld by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (!phone) RequestFocusOnce(focus)
    fun go(f: () -> Unit) {
        close()
        f()
    }
    val found = remember(q, world.files, world.open, desk.apps, world.list) {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) emptyList() else buildList {
            BuiltInApp.entries.filter { needle in it.title.lowercase() }.forEach { b -> add(Found("App", b.title) { desk.open(b.ref) }) }
            desk.apps.filter { needle in it.title.lowercase() }.forEach { a -> add(Found("App", a.title) { desk.open(a.ref) }) }
            world.list.filter { needle in it.title.lowercase() }.forEach { w -> add(Found("World", w.title) { world.saveEditor(); world.openWorld(w.id) }) }
            world.open?.boards.orEmpty().filter { needle in it.title.lowercase() }.forEach { b -> add(Found("Page", b.title) { openAddress(h, WorldAddress.Board(b.id)) }) }
            world.files.filter { needle in it.lowercase() }.forEach { f -> add(Found("File", f) { openFile(h, f) }) }
        }.take(40)
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MuInput(
            q,
            { q = it },
            Modifier.fillMaxWidth(),
            placeholder = "Find an app, a world, a page or a file",
            focusRequester = focus,
            onSubmit = { found.firstOrNull()?.let { go(it.open) } },
        )
        if (q.isNotBlank()) {
            if (found.isEmpty()) Help("Nothing here is called that.", color = c.ink45)
            found.forEach { f -> FoundRow(f) { go(f.open) } }
        } else {
            // The open windows first: the way between them when `Ctrl \`` is out of reach (§9.1).
            val open = desk.desk.recent
            if (open.isNotEmpty() && !phone) {
                Section("Open")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    open.forEach { w -> AppRef.parse(w.app)?.let { ref -> AppTile(h, ref, 72.dp, compact = true) { go { desk.open(ref) } } } }
                }
            }
            Section("Apps")
            val width = if (phone) 72.dp else 96.dp
            FlowRow(horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 4) {
                BuiltInApp.entries.forEach { b -> AppTile(h, b.ref, width) { go { desk.open(b.ref) } } }
            }
            if (desk.apps.isNotEmpty()) {
                Section("Made by Ai")
                desk.apps.forEach { a -> MadeRow(h, a.ref, a.title, a.description) { go { desk.open(a.ref) } } }
            }
            Section("Worlds")
            (if (everyWorld) world.list else world.list.take(5)).forEach { w ->
                WorldRow(w.title, if (w.id == world.open?.id) "open" else "${w.boards.size} pages", w.id == world.open?.id) {
                    go { world.saveEditor(); world.openWorld(w.id) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (world.list.size > 5 && !everyWorld) MicroLink("All worlds…", { everyWorld = true }, color = c.ink)
                MicroLink("New world", { go { newWorld(h) } }, color = c.ink)
            }
            if (h.neue.prefs.ai.enabled) {
                Section("Ask Ai")
                AskLine(h)
            }
            MicroLink("World settings: Python, typing, the avatar", { go { h.neue.go(Page.SETTINGS) } }, color = c.ink45)
        }
    }
}

@Composable
private fun Section(title: String) {
    val c = Mu.colors
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(bottom = 6.dp),
    ) { Micro(title, color = c.ink45, size = 10.sp) }
}

/** An app as a tile: its icon over its name (the launcher's grid, the switcher's strip). */
@Composable
internal fun AppTile(h: NeueHolders, ref: AppRef, width: Dp, compact: Boolean = false, chosen: Boolean = false, onClick: () -> Unit) {
    val desk = h.world.desk
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Inverted(chosen) {
        val c = Mu.colors
        Column(
            Modifier
                .width(width)
                .background(animatedColor(if (chosen) c.paper else if (hot) c.ink06 else Color.Transparent))
                .hoverable(source)
                .cursorPointer(caption = "Open")
                .muClickable(interactionSource = source, onClick = onClick)
                .padding(vertical = if (compact) 6.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon(ref, if (compact) 24.dp else 32.dp, desk.apps, color = c.ink)
            MuText(desk.title(ref.key), Modifier.padding(horizontal = 2.dp), style = MuType.small(LocalMuFonts.current).copy(fontSize = 11.sp), color = c.ink, maxLines = 1, align = TextAlign.Center)
        }
    }
}

@Composable
private fun MadeRow(h: NeueHolders, ref: AppRef, title: String, description: String, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot) c.ink06 else Color.Transparent))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(ref, 32.dp, h.world.desk.apps, color = c.ink)
        Column(Modifier.weight(1f)) {
            MuText(title, style = MuType.row(LocalMuFonts.current), color = c.ink, maxLines = 1)
            if (description.isNotBlank()) Small(description, color = c.ink45, maxLines = 1)
        }
    }
}

@Composable
private fun WorldRow(title: String, hint: String, open: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot) c.ink06 else Color.Transparent))
            .hoverable(source)
            .cursorPointer(caption = if (open) null else "Open")
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MuText(title, Modifier.weight(1f), style = MuType.row(LocalMuFonts.current), color = c.ink, maxLines = 1)
        Mono(hint, color = c.ink45)
    }
}

@Composable
private fun FoundRow(f: Found, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot) c.ink06 else Color.Transparent))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Micro(f.kind, Modifier.width(48.dp), color = c.ink45, size = 10.sp)
        MuText(f.title, Modifier.weight(1f), style = MuType.row(LocalMuFonts.current), color = c.ink, maxLines = 1)
    }
}

// ---- The switcher's strip ---------------------------------------------------------------------------------------

@Composable
private fun BoxScope.SwitchStrip(h: NeueHolders, s: Switching) {
    var visible by remember(s.since) { mutableStateOf(false) }
    LaunchedEffect(s.since) {
        delay(WorldDeskState.STRIP_AFTER_MS)
        visible = true
    }
    if (visible) {
        val c = Mu.colors
        Row(
            Modifier.align(Alignment.Center).background(c.paper).border(1.dp, c.ink).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            s.order.forEachIndexed { i, app ->
                AppRef.parse(app)?.let { ref ->
                    AppTile(h, ref, 96.dp, chosen = i == s.at) {
                        h.world.desk.switching = s.copy(at = i)
                        h.world.desk.commitSwitch()
                    }
                }
            }
        }
    }
}

// ---- Notices ----------------------------------------------------------------------------------------------------

/** What a notice's one action does (§6.3). */
internal fun actOn(h: NeueHolders, n: Notice) {
    val desk = h.world.desk
    desk.noticesNow { it.acted(n.id) }
    desk.trayOpen = false
    when (n.kind) {
        NoticeKind.RUN_FINISHED, NoticeKind.RUN_FAILED -> desk.open(BuiltInApp.TERMINAL.ref)
        NoticeKind.WAITING -> desk.open(BuiltInApp.THOUGHTS.ref)
        NoticeKind.AI_IS_IN -> n.address?.let(AppRef::parse)?.let(desk::open)
        NoticeKind.APP_FAILED, NoticeKind.APP_MADE, NoticeKind.NEW_PAGES -> n.address?.let { openAddress(h, WorldAddress.parse(it)) }
        NoticeKind.WINDOW_CLOSED -> Unit
    }
}

/** One toast at a time over the tray's corner, for 5 s, held while the pointer is on it (§6.3). */
@Composable
private fun BoxScope.Toast(h: NeueHolders, n: Notice, bottom: Dp) {
    val desk = h.world.desk
    val notices = desk.notices
    LaunchedEffect(n.id, notices.held, notices.toastSince) {
        if (!notices.held) {
            delay((notices.toastSince + WorldNotices.TOAST_MS - WorldDeskState.now()).coerceAtLeast(0L))
            desk.noticesNow { it.tick(WorldDeskState.now()) }
        }
    }
    Inverted {
        val c = Mu.colors
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = bottom + 12.dp)
                .widthIn(max = 420.dp)
                .background(c.paper)
                .onPointer(PointerEventType.Enter) { desk.noticesNow { it.hold(true, WorldDeskState.now()) } }
                .onPointer(PointerEventType.Exit) { desk.noticesNow { it.hold(false, WorldDeskState.now()) } }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Micro(n.title, color = c.ink70)
            if (n.text.isNotBlank()) Small(n.text, Modifier.weight(1f, fill = false), color = c.ink, maxLines = 2)
            if (n.kind != NoticeKind.WINDOW_CLOSED) {
                MuButton(n.action, { actOn(h, n) }, size = com.kaiharimoto.neue.kit.BtnSize.SM, variant = BtnVariant.SECONDARY)
            }
        }
    }
}

/** The tray: the last 50 notices, newest first, each with its action, and Clear (§6.3). */
@Composable
internal fun BoxScope.Tray(h: NeueHolders, bottom: Dp) {
    val desk = h.world.desk
    val c = Mu.colors
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 8.dp, bottom = bottom + 8.dp)
            .padding(start = 8.dp)
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .background(c.paper)
            .border(1.dp, c.ink)
            .muClickable { },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("Notices", color = c.ink45)
            Spacer(Modifier.weight(1f))
            MicroLink("Clear", { desk.noticesNow { it.clear() }; desk.trayOpen = false }, color = c.ink)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (desk.notices.tray.isEmpty()) Help("Nothing new.", Modifier.padding(14.dp))
            desk.notices.tray.forEach { n ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(c.ink12, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        MuText(n.title, style = MuType.row(LocalMuFonts.current), color = c.ink, maxLines = 1)
                        if (n.text.isNotBlank()) Small(n.text, color = c.ink70, maxLines = 2)
                    }
                    if (n.kind != NoticeKind.WINDOW_CLOSED) MicroLink(n.action, { actOn(h, n) }, color = c.ink)
                }
            }
        }
    }
}

// ---- The desk's dialogs -----------------------------------------------------------------------------------------

@Composable
internal fun DeskDialogs(h: NeueHolders) {
    val world = h.world
    val desk = world.desk
    when (val d = desk.dialog) {
        null -> Unit
        DeskDialog.NewFile -> NameDialog(desk, "New file", "A path in this world: notes.md, lib/hands.js", "", "Make") { name ->
            world.launch {
                world.write(name, "", WorldEvent.YOU).onSuccess { openFile(h, name.trim()) }
            }
        }
        is DeskDialog.Rename -> NameDialog(desk, "Rename ${d.path.substringAfterLast('/')}", "Its new path in this world", d.path, "Rename") { name ->
            world.launch { world.rename(d.path, name) }
        }
        is DeskDialog.Delete -> MuDialog(
            "Delete ${d.path.substringAfterLast('/')}?",
            onDismiss = { desk.dialog = null },
            description = "${d.path} goes from this world. Its runs and pages stay.",
            footer = {
                MuButton("Cancel", { desk.dialog = null }, variant = BtnVariant.SUBTLE)
                MuButton("Delete", {
                    desk.dialog = null
                    world.launch { world.delete(d.path, WorldEvent.YOU) }
                })
            },
        ) {}
    }
}

@Composable
private fun NameDialog(desk: WorldDeskState, title: String, hint: String, start: String, action: String, done: (String) -> Unit) {
    var name by remember(title) { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    RequestFocusOnce(focus)
    fun submit() {
        if (name.isBlank()) return
        desk.dialog = null
        done(name.trim())
    }
    MuDialog(
        title,
        onDismiss = { desk.dialog = null },
        description = hint,
        footer = {
            MuButton("Cancel", { desk.dialog = null }, variant = BtnVariant.SUBTLE)
            MuButton(action, { submit() }, variant = BtnVariant.PRIMARY, enabled = name.isNotBlank(), reason = "Give it a name")
        },
    ) {
        MuInput(name, { name = it }, Modifier.fillMaxWidth(), mono = true, focusRequester = focus, onSubmit = { submit() })
    }
}

