package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.world.toggleFollow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/*
 * The taskbar (`docs/world/DESKTOP.md` §2.2): 44 dp on the desk (48 to a finger), paper under a hairline. Left to right:
 * the launcher, the pinned apps and every open one, a space, the run under way with Stop, the notices' tray, the clock,
 * and Ai's cell — the avatar's home, its line of status, and Follow and Skip while it works.
 */

/** How tall the taskbar is. */
internal fun taskbarHeight(finger: Boolean): Dp = if (finger) 48.dp else 44.dp

internal fun kbd(action: DeskAction): String? = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/** The `Alt` key of a built-in app, for its tips. */
private fun altKey(ref: AppRef): DeskAction? = when ((ref as? AppRef.BuiltIn)?.kind) {
    BuiltInApp.FILES -> DeskAction.WORLD_APP_FILES
    BuiltInApp.EDITOR -> DeskAction.WORLD_APP_EDITOR
    BuiltInApp.TERMINAL -> DeskAction.WORLD_APP_TERMINAL
    BuiltInApp.BROWSER -> DeskAction.WORLD_APP_BROWSER
    BuiltInApp.THOUGHTS -> DeskAction.WORLD_APP_THOUGHTS
    BuiltInApp.INSTRUMENTS -> DeskAction.WORLD_APP_INSTRUMENTS
    BuiltInApp.LIBRARY -> DeskAction.WORLD_APP_LIBRARY
    null -> null
}

@Composable
internal fun Taskbar(h: NeueHolders, finger: Boolean) {
    val desk = h.world.desk
    val c = Mu.colors
    val d = desk.desk
    val tall = taskbarHeight(finger)
    // The pinned apps, then every open app that is not pinned, in the order opened (§2.2).
    val pins = desk.pinned()
    val open = d.windows.map { it.app }
    val cells = remember(pins, desk.opening, open) {
        (pins + desk.opening.filter { it in open } + open.filter { it !in desk.opening }).distinct()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(tall)
            .background(c.paper)
            .drawBehind { drawLine(c.ink12, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The launcher.
        Tip("Apps and worlds", kbd = kbd(DeskAction.WORLD_LAUNCHER), above = true) {
            Cell(tall, inverted = desk.launcherOpen, caption = "Apps", onClick = { desk.touched(); desk.launcherOpen = !desk.launcherOpen }) { color ->
                IconView(WorldIcons.LAUNCHER, 20.dp, color = color)
            }
        }
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp).width(1.dp).fillMaxHeight().background(c.ink12))
        cells.forEach { key ->
            val ref = AppRef.parse(key)
            if (ref != null) androidx.compose.runtime.key(key) { AppCell(h, ref, tall) }
        }
        Spacer(Modifier.weight(1f))
        h.world.running?.let { RunStatus(h, it) }
        if (desk.notices.tray.isNotEmpty()) NoticesPart(h)
        Part { Clock(h) }
        if (h.neue.prefs.ai.enabled) AiCell(h)
    }
}

/** A square cell of the taskbar: [inverted] in front, a bar under its icon when open (ink-45 when minimised). */
@Composable
private fun Cell(
    size: Dp,
    inverted: Boolean = false,
    bar: Color? = null,
    caption: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Inverted(inverted) {
        val c = Mu.colors
        Box(
            modifier
                .size(size)
                .background(animatedColor(if (inverted) c.paper else if (hot) c.ink06 else Color.Transparent))
                .hoverable(source)
                .cursorPointer(label = caption)
                .muClickable(interactionSource = source, onClick = onClick)
                .drawBehind {
                    val b = if (inverted && bar != null) c.ink else bar
                    if (b != null) {
                        val w = 12.dp.toPx()
                        drawRect(b, Offset((this.size.width - w) / 2f, this.size.height - 6.dp.toPx()), androidx.compose.ui.geometry.Size(w, 2.dp.toPx()))
                    }
                },
            contentAlignment = Alignment.Center,
        ) { content(if (inverted) c.ink else c.ink70) }
    }
}

@Composable
private fun AppCell(h: NeueHolders, ref: AppRef, size: Dp) {
    val desk = h.world.desk
    val c = Mu.colors
    val d = desk.desk
    val key = ref.key
    val w = d.window(key)
    val front = d.front == key && w != null && !w.minimised
    val bar = when {
        w == null -> null
        w.minimised -> c.ink45
        else -> c.ink
    }
    val at = remember { arrayOf(Offset.Zero) }
    val context = DeskApps.context(h, ref)
    val tip = desk.title(key) + (context?.let { " · $it" }.orEmpty())
    Tip(tip, kbd = altKey(ref)?.let(::kbd), above = true) {
        Cell(
            size,
            inverted = front,
            bar = bar,
            caption = if (front) "Minimise" else "Open",
            modifier = Modifier
                .onGloballyPositioned { at[0] = it.positionInWindow() }
                .deskTarget(h, key, Anchor.CELL)
                .onContextMenu { local -> h.neue.menu = MenuSpec(at[0] + local, cellMenu(h, ref)) }
                .deskPointer(key, onMiddle = { desk.close(key) }),
            onClick = { desk.toggle(ref) },
        ) { color -> AppIcon(ref, 20.dp, desk.apps, color = color) }
    }
}

private fun cellMenu(h: NeueHolders, ref: AppRef): List<MenuEntry> {
    val desk = h.world.desk
    val key = ref.key
    val pinned = key in desk.pinned()
    val aiOpened = desk.desk.windows.filter { it.by == WorldEvent.AI && !it.kept }
    return listOf(
        MenuEntry(if (desk.desk.isOpen(key)) "Bring forward" else "Open") { desk.open(ref) },
        MenuEntry(if (pinned) "Unpin" else "Pin", hint = "To the taskbar") { pin(h, key, !pinned) },
        MenuEntry("Close", enabled = desk.desk.isOpen(key), reason = "Not open", separatorBefore = true) { desk.close(key) },
        MenuEntry("Close Ai's windows", enabled = aiOpened.isNotEmpty(), reason = "Ai has none open") { aiOpened.forEach { desk.close(it.app) } },
    )
}

/** Pins [key] to this device's taskbar, or unpins it (`WorldPrefs.pinned`). */
internal fun pin(h: NeueHolders, key: String, on: Boolean) {
    h.neue.update { p ->
        val now = p.world.pinned
        p.copy(world = p.world.copy(pinned = if (on) (now + key).distinct() else now - key))
    }
}

/** A part of the taskbar's right end: a hairline before it. */
@Composable
private fun RowScope.Part(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Mu.colors
    Row(
        modifier
            .fillMaxHeight()
            .drawBehind { drawLine(c.ink12, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx()) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** What runs, and for how long, with Stop (§2.2). A click on the name opens the Terminal. */
@Composable
private fun RowScope.RunStatus(h: NeueHolders, label: String) {
    val world = h.world
    val c = Mu.colors
    var since by remember(label) { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember(label) { mutableLongStateOf(since) }
    LaunchedEffect(label) {
        while (true) {
            delay(100)
            now = System.currentTimeMillis()
        }
    }
    Part {
        Breathe()
        Box(
            Modifier
                .cursorPointer(caption = "Terminal")
                .muClickable { world.desk.open(BuiltInApp.TERMINAL.ref) },
        ) { Mono("${label.substringAfterLast('/')} ${(now - since) / 1000}.${((now - since) % 1000) / 100} s", Modifier.widthIn(max = 220.dp), color = c.ink) }
        Tip("Stop the run", kbd = kbd(DeskAction.WORLD_STOP), above = true) {
            WordButton("Stop") { world.stop() }
        }
    }
}

/** A boxed word, as the mockup's Stop and Skip. */
@Composable
internal fun WordButton(label: String, on: Boolean = false, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val inv = on || hot
    Box(
        Modifier
            .height(24.dp)
            .background(animatedColor(if (inv) c.ink else Color.Transparent))
            .border(1.dp, if (on) c.ink else c.ink25)
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Micro(label, color = if (inv) c.paper else c.ink70) }
}

@Composable
private fun RowScope.NoticesPart(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    val unread = desk.notices.unread
    Part(
        Modifier
            .cursorPointer(caption = "Notices")
            .muClickable {
                desk.trayOpen = !desk.trayOpen
                if (desk.trayOpen) desk.noticesNow { it.readAll() }
            },
    ) {
        IconView(WorldIcons.NOTICES, 18.dp, color = if (unread > 0) c.ink else c.ink45)
        if (unread > 0) Mono("$unread", color = c.ink)
    }
}

private val CLOCK = SimpleDateFormat("HH:mm")

/** The time, and while Ai works, the turn's own: `07:48 · 0:42`. */
@Composable
private fun Clock(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val since = desk.turnSince
    LaunchedEffect(since) {
        while (true) {
            // To the next minute, or the next second while a turn's time is shown.
            val t = System.currentTimeMillis()
            delay(if (since > 0) 1_000 - t % 1_000 else 60_000 - t % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val turn = if (since > 0) ((now - since).coerceAtLeast(0) / 1000).let { s -> " · ${s / 60}:${(s % 60).toString().padStart(2, '0')}" } else ""
    Mono(CLOCK.format(Date(now)) + turn, color = c.ink45)
}

/** Ai's cell (§2.2): its home, its line, and while it works Follow and Skip. */
@Composable
private fun RowScope.AiCell(h: NeueHolders) {
    val desk = h.world.desk
    val avatar = desk.avatar
    val c = Mu.colors
    val at = remember { arrayOf(Offset.Zero) }
    val working = desk.desk.working
    val follow = h.neue.prefs.world.follow
    val shown = h.neue.prefs.world.avatar
    Part(
        Modifier
            .widthIn(min = 236.dp)
            .onGloballyPositioned { at[0] = it.positionInWindow() }
            .cursorPointer(caption = "Thoughts")
            .onContextMenu { local -> h.neue.menu = MenuSpec(at[0] + local, aiMenu(h)) }
            .muClickable { desk.open(BuiltInApp.THOUGHTS.ref) },
    ) {
        Box(Modifier.size(28.dp).deskTarget(h, null, Anchor.HOME), contentAlignment = Alignment.Center) {
            // Home: the still mark asleep (no frame loop), or — the avatar off — the live face working at home.
            when {
                avatar.asleep -> AiMark(28.dp, name = h.ai.name)
                !shown -> com.kaiharimoto.neue.ai.avatar.AiAvatar(h.ai.face, 28.dp, pointer = { h.cursor.position }, name = h.ai.name)
                else -> Unit
            }
        }
        Small(avatar.status, Modifier.weight(1f, fill = false), color = c.ink70, maxLines = 1)
        if (working) {
            Spacer(Modifier.weight(1f))
            Tip(if (follow) "Ai's window comes forward as it arrives" else "Nothing is raised for Ai; its cell carries it", kbd = kbd(DeskAction.WORLD_FOLLOW), above = true) {
                WordButton("Follow", on = follow) { toggleFollow(h) }
            }
            Tip("Skip ahead: the typing and the hop finish at once", kbd = kbd(DeskAction.WORLD_SKIP), above = true) {
                WordButton("Skip") { h.world.skip() }
            }
        }
    }
}

private fun aiMenu(h: NeueHolders): List<MenuEntry> {
    val world = h.world
    val follow = h.neue.prefs.world.follow
    val shown = h.neue.prefs.world.avatar
    return listOf(
        MenuEntry("Thoughts", hint = "What it did and said") { world.desk.open(BuiltInApp.THOUGHTS.ref) },
        MenuEntry(if (follow) "Stop following" else "Follow", hint = "F") { toggleFollow(h) },
        MenuEntry("Skip ahead", hint = "Shift F", enabled = world.desk.desk.working, reason = "Ai is not working") { world.skip() },
        MenuEntry("Stop Ai", enabled = h.ai.running, reason = "Ai is not working") {
            world.stop()
            h.ai.stop()
        },
        MenuEntry(if (shown) "Keep Ai at home" else "Show Ai on the desktop", separatorBefore = true) {
            h.neue.update { it.copy(world = it.world.copy(avatar = !shown)) }
        },
    )
}
