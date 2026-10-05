package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The World on a phone (`docs/world/DESKTOP.md` §2.5): no windows. One app fills the page; above Neue's tabs a 48 dp
 * dock — Apps (the launcher, full screen), Switch with the count of open apps, and the status (a run's seconds and Stop,
 * or the notices' count). A swipe up on the dock opens the launcher, a sideways swipe steps through the open apps. The
 * switcher lists them in words, never live pictures. Neue's `PhoneBar` carries the app's icon and name, and its face is
 * the avatar's home (§5.6). Measured to 360 dp: the dock is three cells, the launcher four columns.
 */

/** The phone's dock. */
private val DOCK = 48.dp

/** The app on screen, if any: the one in front, else none (the launcher stands in). */
internal fun phoneApp(h: NeueHolders): AppRef? {
    val d = h.world.desk.desk
    return d.front?.let { f -> d.window(f)?.takeIf { !it.minimised }?.ref }
}

@Composable
internal fun WorldPhone(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    val density = LocalDensity.current.density
    val app = phoneApp(h)
    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .onGloballyPositioned {
                desk.origin = it.positionInWindow()
                desk.density = density
                // Home is the face in Neue's bar, just over the page's right end (§5.6).
                val w = it.size.width / density
                desk.avatar.report(null, Anchor.HOME, DeskRect(w - 78.0, -38.0, 28.0, 28.0))
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    desk.phoneSwitcher -> PhoneSwitcher(h)
                    desk.launcherOpen || app == null -> LauncherContent(h, phone = true) { desk.launcherOpen = false }
                    else -> key(app.key) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .onAnyPress(app.key) { desk.touched() }
                                .deskTarget(h, app.key, Anchor.BODY),
                        ) {
                            // The app's title is Neue's bar; its top strip stands for it to the avatar.
                            Box(Modifier.fillMaxWidth().height(32.dp).deskTarget(h, app.key, Anchor.TITLE))
                            DeskApps.Content(h, app, Modifier.fillMaxSize(), phone = true)
                        }
                    }
                }
            }
            Dock(h)
        }
        desk.notices.toast?.let { t ->
            PhoneToast(h, t)
        }
        if (desk.trayOpen) Tray(h, DOCK)
        DeskDialogs(h)
        DeskAvatarLayer(h, desk.avatar, DeskAvatarState.PHONE_SIZE.dp)
    }
}

/** The bar's title on a phone (§2.5): the app's icon and name where `08 World` stood, or the world's on the launcher. */
@Composable
fun RowScope.WorldPhoneTitle(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    val app = phoneApp(h)?.takeIf { !desk.launcherOpen && !desk.phoneSwitcher }
    if (app != null) {
        AppIcon(app, 20.dp, desk.apps, color = c.ink)
        MuText(desk.title(app.key), style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 1)
    } else {
        Mono("08", color = c.ink45)
        MuText(if (desk.phoneSwitcher) "Open apps" else h.world.open?.title ?: "World", style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 1)
    }
}

/** Whether the bar's face is home now: not while the avatar is down in the app on screen (§5.6). */
fun worldFaceHome(h: NeueHolders): Boolean {
    val a = h.world.desk.avatar
    return a.asleep || !h.neue.prefs.world.avatar || a.at == null || a.at != phoneApp(h)?.key
}

@Composable
private fun Dock(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    val open = desk.desk.windows.size
    val app = phoneApp(h)
    val aiElsewhere = desk.desk.working && desk.avatar.at != null && desk.avatar.at != app?.key && !desk.avatar.asleep
    Row(
        Modifier
            .fillMaxWidth()
            .height(DOCK)
            .background(c.paper)
            .drawBehind { drawLine(c.ink12, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
            .dockSwipes(h),
    ) {
        DockCell(caption = "Apps", active = desk.launcherOpen, onClick = {
            desk.touched()
            desk.phoneSwitcher = false
            desk.launcherOpen = !desk.launcherOpen
        }) { color ->
            IconView(WorldIcons.LAUNCHER, 16.dp, color = color)
            Micro("Apps", color = color)
        }
        DockCell(
            caption = "Switch",
            active = desk.phoneSwitcher,
            modifier = Modifier.onGloballyPositioned { coords ->
                // Ai working in another app sits here (§5.6): every open app's cell is this one.
                desk.desk.windows.forEach { w -> desk.report(w.app, Anchor.CELL, coords) }
            },
            onClick = {
                desk.touched()
                desk.launcherOpen = false
                val at = desk.avatar.at
                if (aiElsewhere && at != null) AppRef.parse(at)?.let(desk::open) else desk.phoneSwitcher = !desk.phoneSwitcher
            },
        ) { color ->
            if (aiElsewhere) {
                Micro("Ai · ${desk.title(desk.avatar.at.orEmpty())}", color = color)
            } else {
                Micro("Switch", color = color)
                Mono("$open", color = color)
            }
        }
        val running = h.world.running
        DockCell(caption = if (running != null) "Stop" else "Notices", onClick = {
            if (running != null) h.world.stop() else desk.trayOpen = !desk.trayOpen
        }) { color ->
            if (running != null) {
                Breathe(color = color)
                Micro("Stop", color = color)
            } else if (desk.notices.unread > 0) {
                IconView(WorldIcons.NOTICES, 16.dp, color = color)
                Mono("${desk.notices.unread}", color = color)
            }
        }
    }
}

/** The dock's swipes (§9.3): up opens the launcher; sideways steps to the previous or next open app. */
private fun Modifier.dockSwipes(h: NeueHolders): Modifier = pointerInput(h) {
    var total = Offset.Zero
    detectDragGestures(
        onDragStart = { total = Offset.Zero },
        onDragEnd = {
            val desk = h.world.desk
            val far = 48.dp.toPx()
            when {
                -total.y > far && abs(total.y) > abs(total.x) -> {
                    desk.phoneSwitcher = false
                    desk.launcherOpen = true
                }
                abs(total.x) > far -> {
                    val order = desk.desk.windows.map { it.app }
                    if (order.isNotEmpty()) {
                        val now = order.indexOf(desk.desk.front).coerceAtLeast(0)
                        val next = order[((now + if (total.x < 0) 1 else -1) % order.size + order.size) % order.size]
                        AppRef.parse(next)?.let(desk::open)
                        desk.launcherOpen = false
                        desk.phoneSwitcher = false
                    }
                }
            }
        },
    ) { change, amount ->
        change.consume()
        total += amount
    }
}

@Composable
private fun RowScope.DockCell(
    caption: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val ink = if (active) c.paper else c.ink70
    Row(
        modifier
            .weight(1f)
            .fillMaxHeight()
            .background(animatedColor(if (active) c.ink else if (hot) c.ink06 else Color.Transparent))
            .drawBehind { drawLine(c.ink12, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) }
            .hoverable(source)
            .cursorPointer(caption = caption)
            .muClickable(interactionSource = source, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) { content(ink) }
}

/** Every open app as a row in words, most recent first; a tap shows it, a swipe left or its ✕ closes it (§2.5). */
@Composable
private fun PhoneSwitcher(h: NeueHolders) {
    val desk = h.world.desk
    val c = Mu.colors
    val rows = desk.desk.recent
    if (rows.isEmpty()) {
        Help("Nothing is open. Apps opens one.", Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.app }) { w ->
                val ref = w.ref
                if (ref != null) SwitchRow(h, ref)
            }
            item { Small("Swipe a row left to close it. Words, not live pictures: nothing is drawn that is not seen.", Modifier.padding(16.dp), color = c.ink45) }
        }
    }
}

@Composable
private fun SwitchRow(h: NeueHolders, ref: AppRef) {
    val desk = h.world.desk
    val c = Mu.colors
    var dx by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .offset { IntOffset(dx.coerceAtMost(0f).roundToInt(), 0) }
            .background(c.paper)
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .pointerInput(ref) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dx < -96.dp.toPx()) desk.close(ref.key)
                        dx = 0f
                    },
                    onDragCancel = { dx = 0f },
                ) { change, amount ->
                    change.consume()
                    dx += amount
                }
            }
            .cursorPointer(caption = "Show")
            .muClickable {
                desk.phoneSwitcher = false
                desk.open(ref)
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(ref, 32.dp, desk.apps, color = c.ink)
        Column(Modifier.weight(1f)) {
            MuText(desk.title(ref.key), style = MuType.row(LocalMuFonts.current).copy(fontSize = 14.sp), color = c.ink, maxLines = 1)
            Mono(DeskApps.summary(h, ref), color = c.ink45)
        }
        Box(
            Modifier.size(40.dp).cursorPointer(label = "Close").muClickable { desk.close(ref.key) },
            contentAlignment = Alignment.Center,
        ) { MuText("✕", color = c.ink45) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PhoneToast(h: NeueHolders, n: com.kaiharimoto.mastertool.core.world.desk.Notice) {
    val desk = h.world.desk
    androidx.compose.runtime.LaunchedEffect(n.id) {
        kotlinx.coroutines.delay(com.kaiharimoto.mastertool.core.world.desk.WorldNotices.TOAST_MS)
        desk.noticesNow { it.tick(WorldDeskState.now()) }
    }
    com.kaiharimoto.neue.theme.Inverted {
        val c = Mu.colors
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = DOCK + 12.dp)
                .fillMaxWidth()
                .background(c.paper)
                .muClickable { actOn(h, n) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Micro(n.title, color = c.ink70)
                if (n.text.isNotBlank()) Small(n.text, color = c.ink, maxLines = 2)
            }
            Micro(n.action, color = c.ink)
        }
    }
}
