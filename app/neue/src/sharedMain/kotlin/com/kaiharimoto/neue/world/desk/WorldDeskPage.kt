package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskArea
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.mastertool.core.world.desk.WorldHome
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.askAi
import com.kaiharimoto.neue.world.newWorld
import com.kaiharimoto.neue.world.system.openAddress
import kotlin.math.roundToInt

/*
 * Ai World as a desktop (1.1.x, `docs/world/DESKTOP.md` §1–§2): the page below Neue's bar is paper, edge to edge, the
 * taskbar along its foot. Icons stand in a column at the left — the seven built-ins, and Ai's apps in a column of their
 * own under *Made by Ai* — and with no window open, the plate: the desktop's wallpaper, in the kit's empty-state voice,
 * gone under the first window. Every window is one the person opened or one Ai is working in.
 *
 * Hierarchy, loudest first: the window in front; Ai (the only thing that moves by itself); the windows behind; the
 * taskbar; the desktop. A phone has no windows: one app at a time (`WorldPhone.kt`).
 */

/** An icon's tile on the desktop's grid (§2.1). */
internal val TILE_W = 88.dp
internal val TILE_H = 80.dp
private val GRID_GAP = 8.dp
private val INSET = 16.dp

/** The heading over Ai's apps, *Made by Ai*: the column's tiles start under it. */
private val MADE_HEAD = 28.dp

@Composable
fun WorldDeskPage(h: NeueHolders) {
    if (LocalPhone.current) {
        WorldPhone(h)
    } else {
        WorldDesk(h)
    }
}

@Composable
private fun WorldDesk(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    val finger = LocalTouchFirst.current
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .onGloballyPositioned {
                desk.origin = it.positionInWindow()
                desk.density = density
                desk.avatar.bounds = com.kaiharimoto.mastertool.core.world.desk.DeskRect(0.0, 0.0, (it.size.width / density).toDouble(), (it.size.height / density).toDouble())
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Desktop(h, maxWidth, maxHeight, finger)
            }
            Taskbar(h, finger)
        }
        DeskOverlays(h, finger)
        DeskAvatarLayer(h, desk.avatar, DeskAvatarState.SIZE.dp)
    }
}

/** How many icon columns the desktop holds: the built-ins, then Ai's apps, a column more each time one is full. */
private fun columns(apps: Int, rows: Int): Int = 1 + if (apps == 0) 0 else (apps + rows - 1) / rows.coerceAtLeast(1)

@Composable
private fun Desktop(h: NeueHolders, width: Dp, height: Dp, finger: Boolean) {
    val world = h.world
    val desk = world.desk
    val d = desk.desk
    val apps = desk.apps
    val rows = ((height - INSET * 2 - MADE_HEAD) / TILE_H).toInt().coerceAtLeast(1)
    val cols = columns(apps.size, rows)
    val iconColumns = INSET + TILE_W * cols + GRID_GAP * (cols - 1)
    val area = DeskArea(DeskRect(0.0, 0.0, width.value.toDouble(), height.value.toDouble()), iconColumns.value.toDouble())
    // The reducer reads the desk's measure; composition passes its own down.
    desk.area = area
    var snap by remember { mutableStateOf<DeskRect?>(null) }
    val at = remember { arrayOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { at[0] = it.positionInWindow() }) {
        // The desktop itself, behind everything on it: a press puts nothing in front; a right-click is its menu.
        Box(
            Modifier
                .fillMaxSize()
                .cursor(CursorMode.DEFAULT)
                .onContextMenu { local -> h.neue.menu = MenuSpec(at[0] + local, desktopMenu(h)) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Main)
                            if (e.type == PointerEventType.Press && (e.buttons.isPrimaryPressed || e.changes.any { it.type == androidx.compose.ui.input.pointer.PointerType.Touch })) {
                                desk.touched()
                                desk.selectedIcon = null
                                if (desk.desk.front != null) desk.apply(DeskOp.Unfocus)
                                desk.launcherOpen = false
                                desk.trayOpen = false
                                h.focus?.clearFocus()
                            }
                        }
                    }
                },
        )
        DesktopIcons(h, apps, rows, finger)
        if (d.visible.isEmpty()) Plate(h, area)
        val receding = d.working && h.neue.prefs.world.follow && h.neue.prefs.world.recede && !desk.recedeBroken && d.ai != null && d.ai == d.front
        d.visible.forEach { w ->
            key(w.app) {
                WindowFrame(
                    h,
                    w,
                    w.rect(area),
                    front = d.front == w.app,
                    receding = receding && d.front != w.app,
                    finger = finger,
                    onSnap = { snap = it },
                )
            }
        }
        // Where a window held at an edge will land: a dashed 1 dp ink outline, the Spotlight's dash (§2.3).
        snap?.let { r ->
            val c = Mu.colors
            Canvas(Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp)) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                drawRect(c.ink, Offset(0.5f, 0.5f), Size(size.width - 1f, size.height - 1f), style = Stroke(width = 1.dp.toPx(), pathEffect = dash))
            }
        }
    }
}

private fun desktopMenu(h: NeueHolders): List<MenuEntry> {
    val desk = h.world.desk
    val any = desk.desk.windows.isNotEmpty()
    return listOf(
        MenuEntry("Show desktop", hint = "Minimise every window", enabled = desk.desk.visible.isNotEmpty(), reason = "Nothing is open") { desk.apply(DeskOp.MinimiseAll) },
        MenuEntry("Put windows away", hint = "All but the kept", enabled = any, reason = "Nothing is open") { desk.apply(DeskOp.PutAway) },
        MenuEntry("Tidy icons", enabled = desk.desk.icons.isNotEmpty(), reason = "They are in order") { tidyIcons(h) },
        MenuEntry("New world", separatorBefore = true, hint = "Alt N") { newWorld(h) },
        MenuEntry("World settings", hint = "Python, typing, the avatar") { h.neue.go(Page.SETTINGS) },
    )
}

/** Every icon back to its own cell, in order (§2.1, the desktop's Tidy icons). */
private fun tidyIcons(h: NeueHolders) {
    val desk = h.world.desk
    val moved = desk.desk.icons.map { it.app }.toSet()
    defaultCells(desk.apps, Int.MAX_VALUE).filter { it.key in moved }.forEach { (app, cell) -> desk.apply(DeskOp.MoveIcon(app, cell.first, cell.second)) }
}

/** Each icon's own cell, before the person moves any: the built-ins down column 0, Ai's apps from column 1. */
internal fun defaultCells(apps: List<AppManifest>, rows: Int): Map<String, Pair<Int, Int>> = buildMap {
    BuiltInApp.entries.forEachIndexed { i, b -> put(b.id, 0 to i) }
    apps.forEachIndexed { i, a -> put(AppRef.Made(a.slug).key, (1 + i / rows) to (i % rows)) }
}

/** Where an icon's cell stands on the desktop, in dp. */
private fun cellAt(col: Int, row: Int): Pair<Dp, Dp> =
    (INSET + (TILE_W + GRID_GAP) * col) to (INSET + (if (col >= 1) MADE_HEAD else 0.dp) + TILE_H * row)

/** The cell under a point on the desktop, in dp. */
private fun cellUnder(x: Float, y: Float): Pair<Int, Int> {
    val col = ((x - INSET.value) / (TILE_W + GRID_GAP).value).roundToInt().coerceAtLeast(0)
    val top = INSET.value + if (col >= 1) MADE_HEAD.value else 0f
    val row = ((y - top) / TILE_H.value).roundToInt().coerceAtLeast(0)
    return col to row
}

/** The icons, in their cells (§2.1). The person's moves are kept per world (`desk.json`'s icons). */
@Composable
private fun DesktopIcons(h: NeueHolders, apps: List<AppManifest>, rows: Int, finger: Boolean) {
    val desk = h.world.desk
    val c = Mu.colors
    val cells = remember(apps, rows, desk.desk.icons) {
        val own = desk.desk.icons.associate { it.app to (it.col to it.row) }
        defaultCells(apps, rows).mapValues { (app, cell) -> own[app] ?: cell }
    }
    if (apps.isNotEmpty()) {
        val (x, _) = cellAt(1, 0)
        Box(
            Modifier
                .offset(x, INSET)
                .width(TILE_W)
                .height(MADE_HEAD - 4.dp)
                .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
            contentAlignment = Alignment.BottomCenter,
        ) { Micro("Made by Ai", Modifier.padding(bottom = 6.dp), color = c.ink45, size = 10.sp) }
    }
    cells.forEach { (app, cell) ->
        val ref = AppRef.parse(app)
        if (ref != null) {
            key(app) {
                val made = (ref as? AppRef.Made)?.let { m -> apps.firstOrNull { it.slug == m.slug } }
                val (x, y) = cellAt(cell.first, cell.second)
                DeskIcon(h, ref, made, x, y, finger)
            }
        }
    }
}

@Composable
private fun DeskIcon(h: NeueHolders, ref: AppRef, made: AppManifest?, x: Dp, y: Dp, finger: Boolean) {
    val desk = h.world.desk
    val key = ref.key
    val selected = desk.selectedIcon == key
    val fresh = made != null && desk.isNew(made)
    val drag = remember { floatArrayOf(0f, 0f) }
    val moved = remember { mutableIntStateOf(0) }
    val at = remember { arrayOf(Offset.Zero) }
    val mover = remember(key) {
        object : DeskDrag {
            override fun start(local: Offset) {
                desk.touched()
                desk.selectedIcon = key
            }

            override fun move(delta: Offset, local: Offset) {
                drag[0] += delta.x / desk.density
                drag[1] += delta.y / desk.density
                moved.intValue++
            }

            override fun end(local: Offset) {
                val (col, row) = cellUnder(x.value + drag[0], y.value + drag[1])
                drag[0] = 0f
                drag[1] = 0f
                moved.intValue++
                desk.apply(DeskOp.MoveIcon(key, col, row))
            }

            override fun cancel() {
                drag[0] = 0f
                drag[1] = 0f
                moved.intValue++
            }
        }
    }
    Inverted(selected) {
        val c = Mu.colors
        Column(
            Modifier
                .offset {
                    moved.intValue
                    IntOffset(((x.value + drag[0]) * density).roundToInt(), ((y.value + drag[1]) * density).roundToInt())
                }
                .size(TILE_W, TILE_H)
                .background(if (selected) c.paper else Color.Transparent)
                .onGloballyPositioned { at[0] = it.positionInWindow() }
                .deskTarget(h, key, Anchor.ICON)
                .cursorPointer(caption = if (finger || selected) "Open" else "Select", holdOnPress = true)
                .onContextMenu { local -> desk.selectedIcon = key; h.neue.menu = MenuSpec(at[0] + local, iconMenu(h, ref)) }
                .deskPointer(
                    key,
                    // A tap opens at once, a launcher's habit; a click picks it out (§9.3).
                    onTap = { byFinger -> if (byFinger) desk.open(ref) else { desk.touched(); desk.selectedIcon = key } },
                    onDouble = { desk.open(ref) },
                    drag = mover,
                    fingerDrags = false,
                )
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // An app of Ai's the person has not opened: its tile inverted, and a NEW tag under its name (§2.1, §7.3).
            if (fresh && !selected) {
                Box(Modifier.background(c.ink)) { AppIcon(ref, 32.dp, desk.apps, color = c.paper) }
            } else {
                AppIcon(ref, 32.dp, desk.apps, color = c.ink)
            }
            MuText(
                desk.title(key),
                Modifier.widthIn(max = TILE_W - 8.dp),
                style = MuType.small(LocalMuFonts.current).copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = c.ink,
                maxLines = 2,
                align = TextAlign.Center,
            )
            if (fresh) {
                Box(Modifier.background(c.ink).padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Micro("New", color = c.paper, size = 9.sp)
                }
            }
        }
    }
}

private fun iconMenu(h: NeueHolders, ref: AppRef): List<MenuEntry> {
    val desk = h.world.desk
    val key = ref.key
    val pinned = key in desk.pinned()
    return buildList {
        add(MenuEntry("Open") { desk.open(ref) })
        add(MenuEntry(if (pinned) "Unpin" else "Pin", hint = "To the taskbar") { pin(h, key, !pinned) })
        if (ref is AppRef.Made) {
            add(MenuEntry("Show code", hint = "apps/${ref.slug}/main.js", separatorBefore = true) { desk.open(BuiltInApp.FILES.ref) })
        }
    }
}

// ---- The plate --------------------------------------------------------------------------------------------------

/** The desktop's wallpaper with no window open (§2.1): what the place is for, or what is in it. */
@Composable
private fun Plate(h: NeueHolders, area: DeskArea) {
    val world = h.world
    val w = world.open
    val c = Mu.colors
    val f = LocalMuFonts.current
    val work = area.work
    val x = (work.x + ((work.w - PLATE_W) * 0.35).coerceAtLeast(32.0)).dp
    val y = (area.full.h * 0.24).coerceIn(32.0, 220.0).dp
    val ai = h.neue.prefs.ai.enabled
    val fresh = w == null || (w.boards.isEmpty() && world.files.size <= 1 && world.desk.apps.isEmpty())
    Column(
        Modifier.offset(x, y).widthIn(max = PLATE_W.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (fresh) {
            MuText(if (w == null) "No world yet." else "A computer for ${h.ai.name}'s experiments.", style = MuType.h1(f), color = c.ink)
            Body(
                if (w == null) "A world is ${h.ai.name}'s own small computer: it writes code there, runs it, and shows you what it finds — every app it opens, as it opens it."
                else "Ask ${h.ai.name} a question it can answer by running something. You will see it open each app it uses.",
                color = c.ink70,
            )
            if (w == null) MuButton("New world", { newWorld(h) }, variant = BtnVariant.PRIMARY, icon = Icons.Plus)
            if (ai) {
                AskLine(h)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SUGGESTIONS.forEach { q -> TextLink("$q →") { askAi(h, q) } }
                }
            }
        } else {
            MuText(w.title, style = MuType.h2(f), color = c.ink, maxLines = 2)
            Mono(WorldHome.summary(w.boards.size, world.files.size, world.desk.apps.size), color = c.ink45, size = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextLink("Open the browser") { openAddress(h, WorldAddress.Home()) }
                if (ai) TextLink("What ${h.ai.name} did last") { world.desk.open(BuiltInApp.THOUGHTS.ref) }
            }
            if (ai) AskLine(h)
        }
    }
}

private const val PLATE_W = 520.0

private val SUGGESTIONS = listOf("Study my deck's openings", "Build me a hand-odds calculator", "Map how my cards search each other")

/** The ask line: Enter asks, as a World conversation (§2.1). */
@Composable
internal fun AskLine(h: NeueHolders, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AiMark(16.dp, name = h.ai.name)
        MuInput(
            text,
            { text = it },
            Modifier.weight(1f),
            placeholder = "Ask ${h.ai.name} to try something here",
            onSubmit = { askAi(h, text); text = "" },
        )
        MuButton("Ask", { askAi(h, text); text = "" }, size = BtnSize.SM, variant = BtnVariant.SUBTLE, enabled = text.isNotBlank(), reason = "Type a question first")
    }
}

/** An underlined line that does something: the plate's suggestions and links. */
@Composable
internal fun TextLink(text: String, onClick: () -> Unit) {
    val c = Mu.colors
    MuText(
        text,
        Modifier.cursorPointer(showsWords = true).deskPointer(text, onTap = { onClick() }),
        style = MuType.body(LocalMuFonts.current).copy(textDecoration = TextDecoration.Underline),
        color = c.ink,
        maxLines = 1,
    )
}
