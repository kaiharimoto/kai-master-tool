package com.kaiharimoto.neue.world.desk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.DeskPoint
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.mastertool.core.world.desk.DeskWindow
import com.kaiharimoto.mastertool.core.world.desk.Edge
import com.kaiharimoto.mastertool.core.world.desk.SnapZones
import com.kaiharimoto.mastertool.core.world.desk.WindowMode
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.desk.DeskApps.Tools
import kotlin.math.roundToInt

/*
 * A window's chrome (`docs/world/DESKTOP.md` §2.3): the title bar (icon, name, `· context`, the app's own tools,
 * `– □ ✕`), the body, a 1 dp edge, and for the window in front a 3 dp paper keep-out outside the edge, so no window
 * behind touches its border. In front the title bar is ink with paper words (inversion is Master UI's emphasis); behind,
 * paper with ink-45 words. No shadow. While Ai works in the window in front with Follow on, the others recede: their
 * content at 45 % (§6.2).
 *
 * Everything a window does goes through core's reducer: a drag moves it under the hand with no easing, and let go at an
 * edge or a corner it snaps (a dashed outline shows where while it is held there); a 6 dp band and 12 dp corners resize
 * it (not to a finger, which sizes by `□` and snapping); `□` or a double-click maximises.
 */

/** How tall a title bar is: 32 dp, 40 to a finger. */
internal fun titleHeight(finger: Boolean): Dp = if (finger) 40.dp else 32.dp

/** The keep-out round the window in front. */
private val KEEP_OUT = 3.dp

/** The resize band, and its corners. */
private val BAND = 6.dp
private val CORNER = 12.dp

@Composable
internal fun WindowFrame(
    h: NeueHolders,
    w: DeskWindow,
    rect: DeskRect,
    front: Boolean,
    receding: Boolean,
    finger: Boolean,
    onSnap: (DeskRect?) -> Unit,
) {
    val desk = h.world.desk
    val ref = w.ref
    val c = Mu.colors
    // The hand's drag, in dp, read only where the window is placed: nothing recomposes as it follows.
    val drag = remember { floatArrayOf(0f, 0f) }
    val moved = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(MuMotion.FAST, easing = MuMotion.ease), label = "open")
    val recede by animateFloatAsState(if (receding) 0.45f else 1f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "recede")
    DisposableEffect(w.app) { onDispose { desk.avatar.forget(w.app) } }
    if (ref != null) {
        Box(
            Modifier
                .offset {
                    moved.intValue
                    IntOffset(((rect.x + drag[0]) * density).roundToInt(), ((rect.y + drag[1]) * density).roundToInt())
                }
                .size(rect.w.dp, rect.h.dp)
                .graphicsLayer { alpha = appear }
                .drawBehind {
                    if (front) {
                        val k = KEEP_OUT.toPx()
                        drawRect(c.paper, Offset(-k, -k), Size(size.width + 2 * k, size.height + 2 * k))
                    }
                }
                .background(c.paper)
                .border(1.dp, if (front) c.ink else c.ink25)
                .onAnyPress(w.app) { desk.focus(w.app) },
        ) {
            Column(Modifier.fillMaxSize()) {
                TitleBar(h, w, ref, front, finger, drag, moved, onSnap)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .graphicsLayer { alpha = recede }
                        .deskTarget(h, w.app, Anchor.BODY),
                ) {
                    DeskApps.Content(h, ref, Modifier.fillMaxSize(), phone = false)
                }
            }
            if (!finger && w.mode == WindowMode.NORMAL) ResizeBands(h, w.app)
        }
    }
}

@Composable
private fun TitleBar(
    h: NeueHolders,
    w: DeskWindow,
    ref: AppRef,
    front: Boolean,
    finger: Boolean,
    drag: FloatArray,
    moved: androidx.compose.runtime.MutableIntState,
    onSnap: (DeskRect?) -> Unit,
) {
    val desk = h.world.desk
    val app = w.app
    val at = remember { arrayOf(Offset.Zero) }
    val made = ref is AppRef.Made
    val mover = remember(app) {
        object : DeskDrag {
            private var last = Offset.Zero

            private fun pointer(local: Offset, d: Float): DeskPoint {
                val r = desk.desk.window(app)?.rect(desk.area) ?: return DeskPoint.ZERO
                return DeskPoint(r.x + drag[0] + local.x / d, r.y + drag[1] + local.y / d)
            }

            override fun start(local: Offset) {
                desk.touched()
                drag[0] = 0f
                drag[1] = 0f
                desk.apply(DeskOp.DragStart(app, pointer(local, desk.density), WorldDeskState.now()))
                last = local
            }

            override fun move(delta: Offset, local: Offset) {
                drag[0] += delta.x / desk.density
                drag[1] += delta.y / desk.density
                moved.intValue++
                last = local
                val p = pointer(local, desk.density)
                onSnap(SnapZones.zone(p, desk.area.full)?.let { SnapZones.frame(it, desk.area.full) })
            }

            override fun end(local: Offset) {
                val p = pointer(local, desk.density)
                desk.apply(DeskOp.Drag(app, drag[0].toDouble(), drag[1].toDouble()))
                drag[0] = 0f
                drag[1] = 0f
                moved.intValue++
                desk.apply(DeskOp.DragEnd(app, p))
                onSnap(null)
            }

            override fun cancel() {
                drag[0] = 0f
                drag[1] = 0f
                moved.intValue++
                onSnap(null)
            }
        }
    }
    Inverted(front) {
        val c = Mu.colors
        val words = if (front) c.ink else c.ink45
        Row(
            Modifier
                .fillMaxWidth()
                .height(titleHeight(finger))
                .background(c.paper)
                .drawBehind { if (!front) drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .onGloballyPositioned { at[0] = it.positionInWindow() }
                .deskTarget(h, app, Anchor.TITLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .cursor(CursorMode.DRAG, caption = "Move", holdOnPress = true)
                    .onContextMenu { local -> h.neue.menu = MenuSpec(at[0] + local, titleMenu(h, w)) }
                    .deskPointer(
                        app,
                        onDouble = { desk.touched(); desk.apply(DeskOp.ToggleMaximise(app, WorldDeskState.now())) },
                        drag = mover,
                    )
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(ref, 16.dp, desk.apps, color = words)
                Micro(desk.title(app), color = words)
                if (made) Micro("by Ai", color = if (front) c.ink70 else c.ink45, size = 10.sp)
                DeskApps.context(h, ref)?.let { ctx ->
                    Mono("· $ctx", Modifier.weight(1f, fill = false).widthIn(max = 360.dp), color = if (front) c.ink70 else c.ink45)
                }
                // With the avatar off, the window Ai works in shows its still mark (§5.5).
                if (!h.neue.prefs.world.avatar && desk.desk.ai == app) AiMark(14.dp, name = h.ai.name)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Tools(h, ref) }
            Glyph("–", "Minimise", titleHeight(finger)) { desk.touched(); desk.apply(DeskOp.Minimise(app)) }
            Glyph(if (w.mode == WindowMode.MAXIMISED) "❐" else "□", if (w.mode == WindowMode.MAXIMISED) "Restore" else "Maximise", titleHeight(finger)) {
                desk.touched()
                desk.apply(DeskOp.ToggleMaximise(app, WorldDeskState.now()))
            }
            Glyph("✕", "Close", titleHeight(finger)) { desk.close(app) }
        }
    }
}

/** One of `– □ ✕`: a text glyph in a square cell as tall as the bar. */
@Composable
private fun Glyph(text: String, label: String, size: Dp, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Tip(label) {
        Box(
            Modifier
                .size(size)
                .background(animatedColor(if (hot) c.ink12 else Color.Transparent))
                .hoverable(source)
                .cursorPointer(label = label)
                .muClickable(interactionSource = source, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            MuText(text, style = MuType.small(LocalMuFonts.current).copy(fontSize = 13.sp), color = c.ink)
        }
    }
}

/** The title bar's menu (§9.2): Minimise, Maximise, Snap, Keep, Close. */
private fun titleMenu(h: NeueHolders, w: DeskWindow): List<MenuEntry> {
    val desk = h.world.desk
    val app = w.app
    val now = WorldDeskState.now()
    return listOf(
        MenuEntry("Minimise") { desk.apply(DeskOp.Minimise(app)) },
        MenuEntry(if (w.mode == WindowMode.MAXIMISED) "Restore" else "Maximise") { desk.apply(DeskOp.ToggleMaximise(app, now)) },
        MenuEntry("Snap left", hint = "Alt Shift ←") { desk.apply(DeskOp.SnapKey(app, DeskOp.Direction.LEFT, now)) },
        MenuEntry("Snap right", hint = "Alt Shift →") { desk.apply(DeskOp.SnapKey(app, DeskOp.Direction.RIGHT, now)) },
        MenuEntry(if (w.kept) "Don't keep" else "Keep", hint = if (w.kept) "Ai may put it away" else "Never put away", separatorBefore = true) {
            desk.apply(DeskOp.Keep(app, !w.kept))
        },
        MenuEntry(if (w.by == WorldEvent.AI && !w.touched) "Close (Ai opened it)" else "Close", separatorBefore = true) { desk.close(app) },
    )
}

/** The edges and corners that resize a window (§2.3: a 6 dp band, 12 dp corners). */
@Composable
private fun BoxScope.ResizeBands(h: NeueHolders, app: String) {
    // The top edge is the title bar's: a 3 dp band there, so the bar's drag keeps the rest.
    Handle(h, app, Edge.LEFT, Modifier.align(Alignment.CenterStart).width(BAND).fillMaxHeight().padding(vertical = CORNER))
    Handle(h, app, Edge.RIGHT, Modifier.align(Alignment.CenterEnd).width(BAND).fillMaxHeight().padding(vertical = CORNER))
    Handle(h, app, Edge.BOTTOM, Modifier.align(Alignment.BottomCenter).height(BAND).fillMaxWidth().padding(horizontal = CORNER))
    Handle(h, app, Edge.TOP, Modifier.align(Alignment.TopCenter).height(3.dp).fillMaxWidth().padding(horizontal = CORNER))
    Handle(h, app, Edge.TOP_LEFT, Modifier.align(Alignment.TopStart).size(CORNER, 3.dp))
    Handle(h, app, Edge.TOP_RIGHT, Modifier.align(Alignment.TopEnd).size(CORNER, 3.dp))
    Handle(h, app, Edge.BOTTOM_LEFT, Modifier.align(Alignment.BottomStart).size(CORNER))
    Handle(h, app, Edge.BOTTOM_RIGHT, Modifier.align(Alignment.BottomEnd).size(CORNER))
}

@Composable
private fun Handle(h: NeueHolders, app: String, edge: Edge, modifier: Modifier) {
    val desk = h.world.desk
    val band = remember(app, edge) {
        object : DeskDrag {
            override fun start(local: Offset) = desk.touched()
            override fun move(delta: Offset, local: Offset) {
                desk.apply(DeskOp.Resize(app, edge, (delta.x / desk.density).toDouble(), (delta.y / desk.density).toDouble()))
            }
            override fun end(local: Offset) = Unit
        }
    }
    Box(modifier.cursor(CursorMode.DRAG, caption = "Resize", holdOnPress = true).deskPointer(edge, drag = band, fingerDrags = false))
}
