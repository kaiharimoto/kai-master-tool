package com.kaiharimoto.neue.world

import com.kaiharimoto.neue.world.type.WorldType
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.KeyChord
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.world.type.Micro
import com.kaiharimoto.neue.world.type.MicroLink
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.desk.WorldDeskPage
import com.kaiharimoto.neue.world.desk.kbd
import com.kaiharimoto.neue.world.desk.phoneApp

/**
 * Ai World's page (`08`): since 1.1.x a small computer — a desktop with icons, a taskbar, windows that open one at a
 * time, and Ai moving about it (`docs/world/DESKTOP.md`, `neue/world/desk/`). 1.0.97's six panes and the boards canvas
 * are gone; this file keeps the page's entry, its words in Neue's bar, and its keys.
 */
@Composable
fun WorldPage(h: NeueHolders) {
    val world = h.world
    val neue = h.neue
    // The world open last is opened again next time.
    LaunchedEffect(world.open?.id) {
        val id = world.open?.id ?: return@LaunchedEffect
        if (neue.prefs.world.open != id) neue.update { it.copy(world = it.world.copy(open = id)) }
    }
    // The avatar walks, and Ai's arrival waits for it, only while the page is on screen (§5.3, §5.4).
    DisposableEffect(world) {
        world.desk.shown = { true }
        onDispose { world.desk.shown = { false } }
    }
    // Ai asked the person something, or waits on their yes (a confirmation): the avatar goes to Thoughts' composer, where
    // it is answered, and holds its waiting pose there (§5.2, §5.7).
    val asking = h.ai.question != null || h.ai.confirm != null
    LaunchedEffect(asking) { if (asking) world.desk.waiting() }
    WorldDeskPage(h)
}

/** A new world, about the builder's deck when one is open. */
internal fun newWorld(h: NeueHolders) {
    val b = h.builder
    val deck = b.deckId
    val title = if (deck != null) "${b.deckName} study" else "World ${h.world.list.size + 1}"
    h.world.make(title, deck?.let { World.SCOPE_DECK + it })
}

/**
 * Hands [question] to Ai as a fresh World conversation (`AiState.startWorld`), about the world open here when one is.
 * Thoughts is where it is answered on the World page (§6.5): the panel does not dock by itself here. With no connection
 * yet the panel opens on its setup, and the question waits in its box.
 */
internal fun askAi(h: NeueHolders, question: String) {
    val q = question.trim()
    if (q.isEmpty()) return
    val w = h.world.open
    val asked = if (w != null) "In Ai World, in the world “${w.title}”: $q" else "$q (in Ai World)"
    if (h.ai.prefs.connection == null) {
        h.ai.draft = asked
        h.ai.startWorld(asked)
        return
    }
    val wasOpen = h.ai.prefs.panelOpen
    h.ai.startWorld(asked)
    if (!wasOpen && h.neue.page == Page.WORLD) {
        h.ai.setOpen(false)
        h.world.desk.open(BuiltInApp.THOUGHTS.ref)
    }
}

internal fun toggleFollow(h: NeueHolders) {
    val on = !h.neue.prefs.world.follow
    h.neue.update { it.copy(world = it.world.copy(follow = on)) }
    h.neue.note = Note(if (on) "Following Ai: its window comes forward as it arrives" else "The desktop stays where you leave it")
}

/** The worlds, newest first, and New world: the bar's picker and the phone's ⋯. */
internal fun worldMenu(h: NeueHolders): List<MenuEntry> = buildList {
    val world = h.world
    if (world.list.isEmpty()) add(MenuEntry("No worlds yet"))
    world.list.forEach { other ->
        add(MenuEntry(other.title, hint = "${other.boards.size} pages", enabled = other.id != world.open?.id, reason = "Open now") {
            world.saveEditor()
            world.openWorld(other.id)
        })
    }
    add(MenuEntry("New world", hint = "Alt N", separatorBefore = true) { newWorld(h) })
}

/** What the world's scope says in the bar: `deck: Labrynth`. */
private fun scopeWords(h: NeueHolders, w: World): String? {
    val scope = w.scope ?: return null
    return when {
        scope.startsWith(World.SCOPE_DECK) -> "deck: " + (scope.removePrefix(World.SCOPE_DECK).takeIf { it == h.builder.deckId }?.let { h.builder.deckName } ?: "a deck")
        scope.startsWith(World.SCOPE_WEB) -> "web"
        else -> null
    }
}

/**
 * The World's words in Neue's bar (§2.1): the world's name as a picker — the worlds, newest first, and New world — the
 * way the builder keeps the deck's name there, and its scope. 1.0.97's head row is gone.
 */
@Composable
fun RowScope.WorldBarItems(h: NeueHolders, narrow: Boolean) {
    val world = h.world
    val c = Mu.colors
    val w = world.open
    val at = remember { arrayOf(Offset.Zero) }
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .onGloballyPositioned { at[0] = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }
            .hoverable(source)
            .cursorPointer(caption = "Switch")
            .muClickable(interactionSource = source) { h.neue.menu = MenuSpec(at[0], worldMenu(h)) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuText(w?.title ?: "No world", Modifier.widthIn(max = if (narrow) 240.dp else 420.dp), style = WorldType.body(LocalMuFonts.current, phone = true), color = c.ink, maxLines = 1)
        MuText("▼", style = WorldType.data(LocalMuFonts.current), color = animatedColor(if (hot) c.ink else c.ink70))
    }
    if (w != null) scopeWords(h, w)?.let { s ->
        Box(Modifier.border(1.dp, c.ink25).padding(horizontal = 6.dp, vertical = 1.dp)) { Mono(s, color = c.ink45) }
    }
    Box(Modifier.weight(1f))
    Tip("A new world: a folder of Ai's own", kbd = kbd(DeskAction.WORLD_NEW)) { MicroLink("New world", { newWorld(h) }, color = c.ink45) }
}

/** The phone's ⋯ on the World page (§2.5): the world picker, New world, Follow, Skip ahead, Close this app. */
fun worldPhoneMenu(h: NeueHolders, at: Offset): List<MenuEntry> {
    val desk = h.world.desk
    val app = phoneApp(h)
    return listOf(
        MenuEntry("Worlds…", hint = h.world.open?.title) { h.neue.menu = MenuSpec(at, worldMenu(h)) },
        MenuEntry("New world") { newWorld(h) },
        MenuEntry(if (h.neue.prefs.world.follow) "Stop following Ai" else "Follow Ai") { toggleFollow(h) },
        MenuEntry("Skip ahead", enabled = desk.desk.working, reason = "Ai is not working") { h.world.skip() },
        MenuEntry("Close this app", enabled = app != null, reason = "No app is open") { app?.let { desk.close(it.key) } },
    )
}

// ---- Keys ---------------------------------------------------------------------------------------------------------

/** Esc and Back on the World page (§9.1): the launcher, the strip, the tray, a toast, a dialog — never a window. */
internal fun dismissWorld(h: NeueHolders): Boolean {
    if (h.neue.page != Page.WORLD || h.neue.hasTop || h.overlays.isOpen) return false
    if (!h.worldStarted) return false
    val desk = h.world.desk
    when {
        desk.dialog != null -> desk.dialog = null
        desk.switching != null -> desk.switching = null
        desk.launcherOpen -> desk.launcherOpen = false
        desk.phoneSwitcher -> desk.phoneSwitcher = false
        desk.trayOpen -> desk.trayOpen = false
        desk.notices.toast != null -> desk.noticesNow { it.dismiss() }
        else -> return false
    }
    return true
}

/** The app an `Alt` number names. */
private fun appKey(action: DeskAction): BuiltInApp? = when (action) {
    DeskAction.WORLD_APP_FILES -> BuiltInApp.FILES
    DeskAction.WORLD_APP_EDITOR -> BuiltInApp.EDITOR
    DeskAction.WORLD_APP_TERMINAL -> BuiltInApp.TERMINAL
    DeskAction.WORLD_APP_BROWSER -> BuiltInApp.BROWSER
    DeskAction.WORLD_APP_THOUGHTS -> BuiltInApp.THOUGHTS
    DeskAction.WORLD_APP_INSTRUMENTS -> BuiltInApp.INSTRUMENTS
    DeskAction.WORLD_APP_LIBRARY -> BuiltInApp.LIBRARY
    DeskAction.WORLD_APP_EFFECTS -> BuiltInApp.EFFECTS
    else -> null
}

/** `Ctrl 1`–`Ctrl 9`'s tab: 1 to 9. */
internal fun tabNumber(action: DeskAction): Int = action.ordinal - DeskAction.WORLD_TAB_1.ordinal + 1

/** The World's keys (`DeskScope.WORLD`, §9.1), and the same actions from the palette and the menus. */
internal fun runWorld(h: NeueHolders, action: DeskAction, ctrlHeld: Boolean = false) {
    val world = h.world
    val desk = world.desk
    val now = com.kaiharimoto.neue.world.desk.WorldDeskState.now()
    val front = desk.desk.front
    desk.touched()
    appKey(action)?.let { app ->
        desk.toggle(app.ref)
        return
    }
    val inBrowser = front == BuiltInApp.BROWSER.id
    val tabs = desk.desk.tabs
    when (action) {
        DeskAction.WORLD_RUN -> world.runBlocked?.let { h.neue.note = Note(it) } ?: world.runEditor()
        DeskAction.WORLD_STOP -> world.stop()
        DeskAction.WORLD_FOLLOW -> toggleFollow(h)
        DeskAction.WORLD_SKIP -> world.skip()
        DeskAction.WORLD_NEW -> newWorld(h)
        DeskAction.WORLD_LAUNCHER -> {
            desk.phoneSwitcher = false
            desk.launcherOpen = !desk.launcherOpen
        }
        DeskAction.WORLD_NEXT_WINDOW -> desk.cycle(forward = true, held = ctrlHeld)
        DeskAction.WORLD_PREVIOUS_WINDOW -> desk.cycle(forward = false, held = ctrlHeld)
        DeskAction.WORLD_CLOSE -> when {
            inBrowser && tabs.selected != null -> desk.apply(DeskOp.Tabs(tabs.close(tabs.selected!!)))
            front != null -> desk.close(front)
        }
        DeskAction.WORLD_MINIMISE -> front?.let { desk.apply(DeskOp.Minimise(it)) }
        DeskAction.WORLD_SNAP_UP -> front?.let { desk.apply(DeskOp.SnapKey(it, DeskOp.Direction.UP, now)) }
        DeskAction.WORLD_SNAP_LEFT -> front?.let { desk.apply(DeskOp.SnapKey(it, DeskOp.Direction.LEFT, now)) }
        DeskAction.WORLD_SNAP_RIGHT -> front?.let { desk.apply(DeskOp.SnapKey(it, DeskOp.Direction.RIGHT, now)) }
        DeskAction.WORLD_SNAP_DOWN -> front?.let { desk.apply(DeskOp.SnapKey(it, DeskOp.Direction.DOWN, now)) }
        DeskAction.WORLD_TAB_NEW -> {
            desk.apply(DeskOp.Tabs(tabs.open(WorldAddress.HOME, now)))
            desk.open(BuiltInApp.BROWSER.ref)
        }
        DeskAction.WORLD_TAB_ADDRESS -> if (inBrowser) desk.addressAsked++
        DeskAction.WORLD_TAB_NEXT -> if (inBrowser) desk.apply(DeskOp.Tabs(tabs.step(1)))
        DeskAction.WORLD_TAB_PREVIOUS -> if (inBrowser) desk.apply(DeskOp.Tabs(tabs.step(-1)))
        DeskAction.WORLD_TAB_BACK -> if (inBrowser) tabs.selected?.let { desk.apply(DeskOp.Tabs(tabs.back(it))) }
        DeskAction.WORLD_TAB_FORWARD -> if (inBrowser) tabs.selected?.let { desk.apply(DeskOp.Tabs(tabs.forward(it))) }
        DeskAction.WORLD_TAB_1, DeskAction.WORLD_TAB_2, DeskAction.WORLD_TAB_3, DeskAction.WORLD_TAB_4, DeskAction.WORLD_TAB_5,
        DeskAction.WORLD_TAB_6, DeskAction.WORLD_TAB_7, DeskAction.WORLD_TAB_8, DeskAction.WORLD_TAB_9,
        -> {
            world.browser.jump(tabNumber(action))
            if (!inBrowser) desk.open(BuiltInApp.BROWSER.ref)
        }
        else -> Unit
    }
}

/**
 * The keys only the desktop itself hears (A's decision: never in `DeskShortcuts`): with nothing in front and no field
 * typing, the arrows walk the icons and Enter opens the one picked (§9.1). Returns whether the key was the desktop's.
 */
internal fun worldDeskKey(h: NeueHolders, chord: KeyChord, typing: Boolean): Boolean {
    if (h.neue.page != Page.WORLD || !h.worldStarted || typing || h.neue.phone) return false
    if (chord.ctrl || chord.alt || chord.shift) return false
    val desk = h.world.desk
    if (desk.desk.front != null || desk.launcherOpen || desk.dialog != null) return false
    val order = BuiltInApp.entries.map { it.id } + desk.apps.map { AppRef.Made(it.slug).key }
    val at = order.indexOf(desk.selectedIcon)
    when (chord.key) {
        "down", "right" -> desk.selectedIcon = order[if (at < 0) 0 else (at + 1).coerceAtMost(order.size - 1)]
        "up", "left" -> desk.selectedIcon = order[if (at < 0) 0 else (at - 1).coerceAtLeast(0)]
        "enter" -> desk.selectedIcon?.let(AppRef::parse)?.let(desk::open) ?: return false
        else -> return false
    }
    return true
}
