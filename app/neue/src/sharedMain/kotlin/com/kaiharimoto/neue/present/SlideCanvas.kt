package com.kaiharimoto.neue.present

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskTouch
import com.kaiharimoto.mastertool.core.input.PresentAction
import com.kaiharimoto.mastertool.core.input.PresentGestures
import com.kaiharimoto.mastertool.core.input.PresentPress
import com.kaiharimoto.mastertool.core.input.PresentTarget
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.edit.Guide
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.RichText
import com.kaiharimoto.mastertool.core.present.edit.SlideZoom
import com.kaiharimoto.mastertool.core.present.edit.Snap
import com.kaiharimoto.mastertool.core.present.edit.Transform
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.Box as CanvasBox
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.releasesTyping
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.platform.droppedPicture
import com.kaiharimoto.neue.platform.mayBePicture
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.present.paint.editingText
import com.kaiharimoto.neue.present.paint.fitOf
import com.kaiharimoto.neue.present.paint.roleLook
import com.kaiharimoto.neue.present.paint.styledText
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** What a press on the canvas turned into. */
private sealed interface Drag {
    data class Move(val start: Map<String, CanvasBox>) : Drag
    data class Resize(val id: String, val handle: Transform.Handle, val start: CanvasBox, val rotation: Float) : Drag
    data class Rotate(val id: String, val start: CanvasBox) : Drag
    data object Marquee : Drag

    /** The camera on this slide carried, or one of its handles ([handle] null to carry it whole). */
    data class Camera(val start: CanvasBox, val handle: Transform.Handle?) : Drag
}

/** Where a press landed, before it became anything. */
private enum class Landed { HANDLE, ROUND_HANDLE, CAMERA, CAMERA_HANDLE, ELEMENT, CANVAS }

/**
 * The slide being made (1.0.70): the painter drawing it as it will be shown, the selection
 * over it with eight handles and a turn, smart guides while moving, a box to select by
 * dragging, double-click to edit words in place. Every press is described as a [PresentPress] and
 * acted on by what [PresentGestures] makes of it — the help dialog's tables, `PresentMouse` and
 * `PresentTouch`, are what this does (the editor's audit, M2–M5): Ctrl wheel and a pinch zoom, the
 * wheel and two fingers move a zoomed slide, a held finger opens the menus, a picture dropped on it
 * is added, and the camera is dragged where it should stand on this slide (B6).
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SlideCanvas(h: NeueHolders, p: Presentation, slide: Slide, ctx: SlideContext, show: CompiledShow, modifier: Modifier) {
    val present = h.present
    val c = Mu.colors
    val index = present.slideIndex
    val zone = show.zone(index)
    val stage = show.stage(index)
    val keys = remember(p.deck) { p.deck?.let { d -> DeckStage.copies(d).map { it.key } }.orEmpty() }
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    var marquee by remember { mutableStateOf<CanvasBox?>(null) }
    var windowAt by remember { mutableStateOf(Offset.Zero) }
    var dropOver by remember { mutableStateOf(false) }
    val latestSlide by rememberUpdatedState(slide)
    val latestStage by rememberUpdatedState(stage)
    val latestZone by rememberUpdatedState(zone)
    val latestShow by rememberUpdatedState(show)
    val scope = rememberCoroutineScope()
    // A picture dropped on the slide is added to it (M5), as the Item tab's help always said.
    val drop = remember(h) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropOver = false
                val file = droppedPicture(event)
                if (file != null) {
                    scope.launch { addPictureBytes(h, file.bytes, file.extension) }
                    return true
                }
                val link = com.kaiharimoto.neue.platform.droppedLink(event) ?: return false
                scope.launch { com.kaiharimoto.neue.platform.fetchPicture(link)?.let { addPictureBytes(h, it.bytes, it.extension) } }
                return true
            }

            override fun onEntered(event: DragAndDropEvent) { dropOver = true }

            override fun onExited(event: DragAndDropEvent) { dropOver = false }

            override fun onEnded(event: DragAndDropEvent) { dropOver = false }
        }
    }

    Column(modifier.background(c.ink06)) {
        CanvasHeader(h, slide, zone)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .dragAndDropTarget(shouldStartDragAndDrop = { mayBePicture(it) }, target = drop)
                .border(2.dp, animatedColor(if (dropOver) c.ink else androidx.compose.ui.graphics.Color.Transparent))
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            val wPx = constraints.maxWidth.toFloat()
            val hPx = constraints.maxHeight.toFloat()
            SideEffect {
                present.viewWidth = wPx
                present.viewHeight = hPx
            }
            val view = present.zoom
            val s = view.scale(wPx, hPx)
            val ox = view.originX(wPx, hPx)
            val oy = view.originY(wPx, hPx)
            Box(Modifier.fillMaxSize().onGloballyPositioned { windowAt = it.positionInWindow() }) {
                // The slide at its zoomed size, where the view puts it: SlideView fits it to the box it is given.
                Layout(
                    content = {
                        SlideView(
                            ctx, slide, zone, stage, Modifier.fillMaxSize(),
                            deck = if (slide.deck != null) ({ show.deckFrame(index) }) else null,
                            deckKeys = keys,
                            hidden = setOfNotNull(present.editingText),
                            editing = true,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { measurables, constraints ->
                    val w = (Presentation.WIDTH * s).roundToInt().coerceAtLeast(1)
                    val hh = (Presentation.HEIGHT * s).roundToInt().coerceAtLeast(1)
                    val placed = measurables.map { it.measure(Constraints.fixed(w, hh)) }
                    layout(constraints.maxWidth, constraints.maxHeight) { placed.forEach { it.place(ox.roundToInt(), oy.roundToInt()) } }
                }
                // The selection, the guides and the box being dragged.
                Canvas(Modifier.fillMaxSize()) {
                    translate(ox, oy) {
                        // The safe margin in paper under ink, so it reads on a dark slide and a light one (R5).
                        val safe = WebcamLayout.safe
                        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        drawRect(c.paper.copy(alpha = 0.5f), Offset(safe.x * s, safe.y * s), Size(safe.w * s, safe.h * s), style = Stroke(2.5f))
                        drawRect(c.ink.copy(alpha = 0.45f), Offset(safe.x * s, safe.y * s), Size(safe.w * s, safe.h * s), style = Stroke(1f, pathEffect = dash))
                        val selected = slide.elements.filter { it.id in present.selection }
                        for (e in selected) {
                            val b = Geometry.box(e, stage)
                            rotate(e.rotation, Offset(b.cx * s, b.cy * s)) {
                                drawRect(c.paper, Offset(b.x * s - 1f, b.y * s - 1f), Size(b.w * s + 2f, b.h * s + 2f), style = Stroke(3f))
                                drawRect(c.ink, Offset(b.x * s - 1f, b.y * s - 1f), Size(b.w * s + 2f, b.h * s + 2f), style = Stroke(1.5f))
                            }
                        }
                        val hs = 9f
                        fun handlesOf(b: CanvasBox, rotation: Float) {
                            for (handle in Transform.Handle.entries) {
                                val (hx, hy) = Transform.handlePoint(b, rotation, handle)
                                drawRect(c.paper, Offset(hx * s - hs / 2f, hy * s - hs / 2f), Size(hs, hs))
                                drawRect(c.ink, Offset(hx * s - hs / 2f, hy * s - hs / 2f), Size(hs, hs), style = Stroke(1.5f))
                            }
                        }
                        if (selected.size == 1 && !selected[0].locked && present.editingText == null) {
                            val e = selected[0]
                            val b = Geometry.box(e, stage)
                            handlesOf(b, e.rotation)
                            val (rx, ry) = rotateHandle(b, e.rotation, s)
                            val (tx, ty) = Transform.handlePoint(b, e.rotation, Transform.Handle.TOP)
                            drawLine(c.ink, Offset(tx * s, ty * s), Offset(rx * s, ry * s), 1.5f)
                            drawCircle(c.paper, 7f, Offset(rx * s, ry * s))
                            drawCircle(c.ink, 7f, Offset(rx * s, ry * s), style = Stroke(1.5f))
                        }
                        // The camera picked: framed and handled like an element, without the turn.
                        val z = zone
                        if (present.cameraPicked && z != null) {
                            drawRect(c.paper, Offset(z.x * s - 1f, z.y * s - 1f), Size(z.w * s + 2f, z.h * s + 2f), style = Stroke(3f))
                            drawRect(c.ink, Offset(z.x * s - 1f, z.y * s - 1f), Size(z.w * s + 2f, z.h * s + 2f), style = Stroke(1.5f, pathEffect = dash))
                            handlesOf(z, 0f)
                        }
                        for (g in guides) {
                            val a = if (g.vertical) Offset(g.at * s, g.from * s) else Offset(g.from * s, g.at * s)
                            val zz = if (g.vertical) Offset(g.at * s, g.to * s) else Offset(g.to * s, g.at * s)
                            drawLine(c.paper, a, zz, 3f)
                            drawLine(c.ink, a, zz, 1f)
                        }
                        marquee?.let { m ->
                            drawRect(c.ink.copy(alpha = 0.08f), Offset(m.x * s, m.y * s), Size(m.w * s, m.h * s))
                            drawRect(c.ink, Offset(m.x * s, m.y * s), Size(m.w * s, m.h * s), style = Stroke(1f))
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize()
                        .cursor(CursorMode.DEFAULT)
                        // A press on the slide lets go of a field being typed in (a number commits then, B1).
                        .releasesTyping()
                        .pointerInput(Unit) {
                            var lastClickAt = 0L
                            var lastClickId: String? = null
                            var gesture = 0
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val w = size.width.toFloat()
                                    val hgt = size.height.toFloat()
                                    // The wheel: Ctrl zooms round the pointer, plain moves a zoomed slide (Shift across).
                                    if (ev.type == PointerEventType.Scroll) {
                                        val ch = ev.changes.firstOrNull() ?: continue
                                        val mods = ev.keyboardModifiers
                                        val press = PresentPress(PresentTarget.CANVAS, wheel = true, ctrl = mods.isCtrlPressed || mods.isMetaPressed, shift = mods.isShiftPressed)
                                        when (PresentGestures.classify(press)) {
                                            PresentAction.ZOOM -> {
                                                val d = ch.scrollDelta.y
                                                if (d != 0f) present.zoom = present.zoom.zoomAround(if (d < 0f) SlideZoom.STEP else 1f / SlideZoom.STEP, ch.position.x, ch.position.y, w, hgt)
                                                ch.consume()
                                            }
                                            PresentAction.PAN -> if (present.zoom.zoom > 1f) {
                                                val d = ch.scrollDelta
                                                val (dx, dy) = if (press.shift) d.y to 0f else d.x to d.y
                                                present.zoom = present.zoom.panned(-dx * WHEEL_PAN, -dy * WHEEL_PAN, w, hgt)
                                                ch.consume()
                                            }
                                            else -> Unit
                                        }
                                        continue
                                    }
                                    if (ev.type != PointerEventType.Press) continue
                                    val down = ev.changes.firstOrNull() ?: continue
                                    gesture++
                                    present.sorterFocused = false
                                    val view = present.zoom
                                    val sNow = view.scale(w, hgt)
                                    fun toCanvas(o: Offset) = Offset(view.toCanvasX(o.x, w, hgt), view.toCanvasY(o.y, w, hgt))
                                    val at = toCanvas(down.position)
                                    val mods = ev.keyboardModifiers
                                    val finger = down.byFinger
                                    val ctrl = mods.isCtrlPressed || mods.isMetaPressed
                                    val shift = mods.isShiftPressed
                                    val alt = mods.isAltPressed
                                    val secondary = ev.buttons.isSecondaryPressed
                                    val sl = latestSlide
                                    val st = latestStage
                                    val camZone = latestZone
                                    val afterDuplicate = present.justDuplicated
                                    present.justDuplicated = false
                                    val selectedBefore = present.selection
                                    val reach = (if (finger) 22f else 10f) / sNow
                                    val hit = sl.elements.asReversed().firstOrNull { e -> Transform.hit(Geometry.box(e, st), e.rotation, at.x, at.y, slop = 6f / sNow) }
                                    val onCamera = camZone != null && camZone.contains(at.x, at.y)
                                    if (present.editingText != null && hit?.id != present.editingText) present.editingText = null

                                    // Where it landed: a handle of the one selected, the camera (drawn over everything), something, or the slide.
                                    val only = sl.elements.filter { it.id in present.selection }.singleOrNull()?.takeIf { !it.locked }
                                    var drag: Drag? = null
                                    var landed = Landed.CANVAS
                                    var corner = false
                                    if (only != null && present.editingText == null) {
                                        val b = Geometry.box(only, st)
                                        val (rx, ry) = rotateHandle(b, only.rotation, sNow)
                                        if (abs(at.x - rx) < reach && abs(at.y - ry) < reach) {
                                            drag = Drag.Rotate(only.id, b)
                                            landed = Landed.ROUND_HANDLE
                                        } else {
                                            Transform.Handle.entries.firstOrNull { hd ->
                                                val (hx, hy) = Transform.handlePoint(b, only.rotation, hd)
                                                abs(at.x - hx) < reach && abs(at.y - hy) < reach
                                            }?.let {
                                                drag = Drag.Resize(only.id, it, b, only.rotation)
                                                landed = Landed.HANDLE
                                                corner = it.hx != 0 && it.hy != 0
                                            }
                                        }
                                    }
                                    if (drag == null && present.cameraPicked && camZone != null) {
                                        Transform.Handle.entries.firstOrNull { hd ->
                                            val (hx, hy) = Transform.handlePoint(camZone, 0f, hd)
                                            abs(at.x - hx) < reach && abs(at.y - hy) < reach
                                        }?.let {
                                            drag = Drag.Camera(camZone, it)
                                            landed = Landed.CAMERA_HANDLE
                                            corner = it.hx != 0 && it.hy != 0
                                        }
                                    }
                                    if (drag == null) {
                                        landed = when {
                                            onCamera -> Landed.CAMERA
                                            hit != null -> Landed.ELEMENT
                                            else -> Landed.CANVAS
                                        }
                                    }
                                    val target = when (landed) {
                                        Landed.HANDLE, Landed.ROUND_HANDLE, Landed.CAMERA_HANDLE -> PresentTarget.HANDLE
                                        Landed.ELEMENT, Landed.CAMERA -> PresentTarget.ELEMENT
                                        Landed.CANVAS -> PresentTarget.CANVAS
                                    }
                                    val press = PresentPress(
                                        target, finger = finger, secondary = secondary, shift = shift, ctrl = ctrl, alt = alt,
                                        roundHandle = landed == Landed.ROUND_HANDLE, corner = corner,
                                        selectSeveral = present.selectSeveral, keepShape = present.keepShape, afterDuplicate = afterDuplicate,
                                    )

                                    // What a menu opens on: the thing pressed, selected first; or the slide.
                                    fun openMenu(atView: Offset) {
                                        when (landed) {
                                            Landed.CAMERA, Landed.CAMERA_HANDLE -> {
                                                present.selection = emptySet()
                                                present.cameraPicked = true
                                                h.neue.menu = MenuSpec(windowAt + atView, cameraMenu(h))
                                            }
                                            Landed.CANVAS -> {
                                                present.selection = emptySet()
                                                present.cameraPicked = false
                                                h.neue.menu = MenuSpec(windowAt + atView, canvasMenu(h))
                                            }
                                            else -> {
                                                if (hit != null && hit.id !in present.selection) present.selection = present.groupOf(hit.id)
                                                present.cameraPicked = false
                                                h.neue.menu = MenuSpec(windowAt + atView, elementMenu(h))
                                            }
                                        }
                                    }

                                    if (PresentGestures.classify(press) == PresentAction.MENU) {
                                        openMenu(down.position)
                                        down.consume()
                                        continue
                                    }

                                    // At the press: what is under it is selected, so a drag carries it.
                                    val adds = PresentGestures.classify(press.copy(target = PresentTarget.ELEMENT)) == PresentAction.ADD_TO_SELECTION
                                    when (landed) {
                                        Landed.CAMERA -> {
                                            present.selection = emptySet()
                                            present.cameraPicked = true
                                            drag = Drag.Camera(camZone!!, null)
                                        }
                                        Landed.ELEMENT -> {
                                            val group = present.groupOf(hit!!.id)
                                            present.cameraPicked = false
                                            if (adds) {
                                                present.selection = if (hit.id in present.selection) present.selection - group else present.selection + group
                                            } else if (hit.id !in present.selection) {
                                                present.selection = group
                                            }
                                            val moving = sl.elements.filter { it.id in present.selection && !it.locked }
                                            if (moving.isNotEmpty()) drag = Drag.Move(moving.associate { it.id to Geometry.box(it, st) })
                                            present.tab = if (present.tab == PropsTab.DECK || present.tab == PropsTab.THEME || present.tab == PropsTab.SLIDE) PropsTab.ELEMENT else present.tab
                                        }
                                        Landed.CANVAS -> {
                                            if (!adds) present.selection = emptySet()
                                            present.cameraPicked = false
                                            drag = Drag.Marquee
                                        }
                                        else -> Unit
                                    }
                                    down.consume()

                                    // Until it moves, lifts, a second finger lands, or a finger is held still.
                                    val slop = viewConfiguration.touchSlop
                                    val waited: Wait = if (finger) {
                                        withTimeoutOrNull(DeskTouch.holdMs(viewConfiguration.longPressTimeoutMillis)) { firstMove(down, slop) } ?: Wait.HELD
                                    } else {
                                        firstMove(down, slop)
                                    }
                                    when (waited) {
                                        Wait.HELD -> {
                                            if (PresentGestures.classify(press.copy(held = true)) == PresentAction.MENU) openMenu(down.position)
                                            drainUntilUp()
                                            continue
                                        }
                                        Wait.SECOND -> {
                                            // Two fingers: a pinch zooms and a drag moves the zoomed slide, together.
                                            twoFingers(present, press, w, hgt)
                                            continue
                                        }
                                        else -> Unit
                                    }
                                    val moved = waited == Wait.MOVED

                                    if (moved) {
                                        val action = PresentGestures.classify(press.copy(moved = true))
                                        var current = drag
                                        var dup = action == PresentAction.DUPLICATE_MOVE && !afterDuplicate
                                        val keepShape = action == PresentAction.RESIZE_KEEP_SHAPE
                                        while (true) {
                                            val e2 = awaitPointerEvent()
                                            val ch = e2.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!ch.pressed) break
                                            if (e2.changes.count { it.pressed } >= 2) break
                                            ch.consume()
                                            val now = toCanvas(ch.position)
                                            val dx = now.x - at.x
                                            val dy = now.y - at.y
                                            val pNow = present.open ?: break
                                            val sid = sl.id
                                            when (val d = current) {
                                                is Drag.Move -> {
                                                    var start = d.start
                                                    if (dup) {
                                                        dup = false
                                                        val (withCopies, ids) = PresentEdits.duplicateElements(pNow, sid, d.start.keys, offset = 0f)
                                                        present.commit(withCopies, "Copy", coalesce = "drag-$gesture")
                                                        val copies = withCopies.slide(sid)?.elements.orEmpty().filter { it.id in ids }
                                                        start = copies.associate { it.id to Geometry.box(it, st) }
                                                        present.selection = ids.toSet()
                                                        current = Drag.Move(start)
                                                    }
                                                    val union = CanvasBox.around(start.values.map { it.copy(x = it.x + dx, y = it.y + dy) }) ?: continue
                                                    val others = sl.elements.filter { it.id !in start.keys }.map { Geometry.box(it, st) }
                                                    val targets = listOfNotNull(CanvasBox.CANVAS, WebcamLayout.safe, st, camZone) + others
                                                    val snapped = if (e2.keyboardModifiers.isCtrlPressed) null else Snap.move(union, targets, 8f / sNow)
                                                    guides = snapped?.guides.orEmpty()
                                                    val fx = dx + (snapped?.dx ?: 0f)
                                                    val fy = dy + (snapped?.dy ?: 0f)
                                                    val base = present.open ?: pNow
                                                    val next = PresentEdits.updateElements(base, sid, start.keys) { e ->
                                                        val b = start.getValue(e.id)
                                                        Geometry.place(e, b.copy(x = b.x + fx, y = b.y + fy), st)
                                                    }
                                                    present.commit(next, "Move", coalesce = "drag-$gesture")
                                                }
                                                is Drag.Resize -> {
                                                    val keep = keepShape || e2.keyboardModifiers.isShiftPressed ||
                                                        (d.handle.hx != 0 && d.handle.hy != 0 && sl.element(d.id)?.type in setOf(Element.IMAGE, Element.CARD))
                                                    val b = Transform.resize(d.start, d.rotation, d.handle, dx, dy, keepAspect = keep, fromCentre = e2.keyboardModifiers.isAltPressed)
                                                    present.commit(PresentEdits.updateElements(pNow, sid, setOf(d.id)) { Geometry.place(it, b, st) }, "Resize", coalesce = "drag-$gesture")
                                                }
                                                is Drag.Rotate -> {
                                                    val deg = Transform.rotation(d.start, now.x, now.y, snap = e2.keyboardModifiers.isShiftPressed)
                                                    present.commit(PresentEdits.updateElements(pNow, sid, setOf(d.id)) { it.copy(rotation = deg) }, "Turn", coalesce = "drag-$gesture")
                                                }
                                                is Drag.Camera -> {
                                                    // The camera moved on this slide alone: its own box from here on (B6).
                                                    val b = if (d.handle == null) {
                                                        val moved1 = d.start.copy(x = d.start.x + dx, y = d.start.y + dy)
                                                        val others = sl.elements.map { Geometry.box(it, st) }
                                                        val snapped = if (e2.keyboardModifiers.isCtrlPressed) null else Snap.move(moved1, listOf(CanvasBox.CANVAS, WebcamLayout.safe) + others, 8f / sNow)
                                                        guides = snapped?.guides.orEmpty()
                                                        moved1.copy(x = moved1.x + (snapped?.dx ?: 0f), y = moved1.y + (snapped?.dy ?: 0f))
                                                    } else {
                                                        Transform.resize(d.start, 0f, d.handle, dx, dy, keepAspect = keepShape || e2.keyboardModifiers.isShiftPressed, fromCentre = e2.keyboardModifiers.isAltPressed)
                                                    }
                                                    present.commit(PresentEdits.updateSlide(pNow, sid) { SlideCamera.moved(it, b) }, "Move the camera", coalesce = "drag-$gesture")
                                                }
                                                Drag.Marquee -> {
                                                    val m = CanvasBox(min(at.x, now.x), min(at.y, now.y), abs(dx), abs(dy))
                                                    marquee = m
                                                    present.selection = (if (press.shift || press.ctrl) selectedBefore else emptySet()) +
                                                        sl.elements.filter { Transform.bounds(Geometry.box(it, st), it.rotation).intersects(m) }.map { it.id }
                                                }
                                                null -> Unit
                                            }
                                        }
                                    }
                                    guides = emptyList()
                                    marquee = null
                                    present.seal()
                                    if (!moved) {
                                        val t = System.currentTimeMillis()
                                        val double = hit != null && hit.id == lastClickId && t - lastClickAt < 380
                                        lastClickAt = t
                                        lastClickId = hit?.id
                                        val clicked = if (landed == Landed.CANVAS && sl.deck != null && selectedBefore.isEmpty() && !present.cameraPicked) {
                                            // A card of the deck slide, with nothing selected: talked about here, or not (I6).
                                            latestShow.deckFrame(present.slideIndex)?.cards?.firstOrNull { it.box.contains(at.x, at.y) }
                                        } else {
                                            null
                                        }
                                        val tap = if (clicked != null) press.copy(target = PresentTarget.STAGE_CARD) else press.copy(taps = if (double) 2 else 1)
                                        when (PresentGestures.classify(tap)) {
                                            PresentAction.EDIT_TEXT -> if (landed == Landed.ELEMENT && hit != null && (hit.type == Element.TEXT || hit.type == Element.SHAPE) && !hit.locked) {
                                                present.selection = setOf(hit.id)
                                                present.editingText = hit.id
                                            }
                                            PresentAction.FOCUS_CARD -> clicked?.let { card -> toggleFocusCard(h, sl, card.id) }
                                            // Selected at the press, so a drag could carry it.
                                            PresentAction.SELECT, PresentAction.ADD_TO_SELECTION, null -> Unit
                                            PresentAction.MOVE, PresentAction.DUPLICATE_MOVE, PresentAction.MENU, PresentAction.MARQUEE,
                                            PresentAction.ZOOM, PresentAction.PAN, PresentAction.RESIZE, PresentAction.RESIZE_KEEP_SHAPE,
                                            PresentAction.ROTATE, PresentAction.OPEN_SLIDE, PresentAction.REORDER, PresentAction.NEXT,
                                            PresentAction.PREVIOUS, PresentAction.LASER,
                                            -> Unit
                                        }
                                    }
                                }
                            }
                        },
                )
                present.editingText?.let { id -> slide.element(id) }?.let { e ->
                    TextEditLayer(h, slide, e, ctx, stage, s, ox, oy)
                }
            }
        }
    }
}

/** How far a notch of the wheel moves a zoomed slide, in pixels. */
private const val WHEEL_PAN = 48f

/** What became of a press before it was anything. */
private enum class Wait { MOVED, RELEASED, SECOND, HELD }

/** Waits for [down] to move past [slop], lift, or be joined by a second finger. */
private suspend fun AwaitPointerEventScope.firstMove(down: PointerInputChange, slop: Float): Wait {
    while (true) {
        val e = awaitPointerEvent()
        if (e.changes.count { it.pressed } >= 2) return Wait.SECOND
        val ch = e.changes.firstOrNull { it.id == down.id } ?: return Wait.RELEASED
        if (!ch.pressed) return Wait.RELEASED
        if ((ch.position - down.position).getDistance() >= slop) return Wait.MOVED
    }
}

/** Every event spent until the last pointer lifts: what a held finger's menu leaves behind. */
private suspend fun AwaitPointerEventScope.drainUntilUp() {
    do {
        val rest = awaitPointerEvent()
        rest.changes.forEach { it.consume() }
    } while (rest.changes.any { it.pressed })
}

/**
 * Two fingers on the slide (M2): spread or pinched they zoom round their middle, moved together they
 * carry the zoomed slide, until both lift.
 */
private suspend fun AwaitPointerEventScope.twoFingers(present: Presentations, press: PresentPress, w: Float, h: Float) {
    var last: Pair<Offset, Float>? = null
    var spread = false
    while (true) {
        val e = awaitPointerEvent()
        val down = e.changes.filter { it.pressed }
        if (down.isEmpty()) break
        e.changes.forEach { it.consume() }
        if (down.size < 2) {
            last = null
            continue
        }
        val a = down[0].position
        val b = down[1].position
        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        val dist = (a - b).getDistance().coerceAtLeast(1f)
        val before = last
        last = mid to dist
        if (before == null) continue
        val (pm, pd) = before
        if (abs(dist - pd) > 0.5f) spread = true
        val action = PresentGestures.classify(press.copy(target = PresentTarget.CANVAS, fingers = 2, spread = spread, moved = true))
        var view = present.zoom
        if (action == PresentAction.ZOOM) view = view.zoomAround(dist / pd, mid.x, mid.y, w, h)
        present.zoom = view.panned(mid.x - pm.x, mid.y - pm.y, w, h)
    }
}

/** Above the slide: what a click does here, a finger's two switches, and the zoom. */
@Composable
private fun CanvasHeader(h: NeueHolders, slide: Slide, zone: CanvasBox?) {
    val present = h.present
    val c = Mu.colors
    val touch = LocalTouchFirst.current
    val click = if (touch) "Tap" else "Click"
    val hint = when {
        present.cameraPicked -> "The camera on this slide: drag it or its corners. Delete hides it here; the Slide tab puts it back."
        present.editingText != null -> "Typing on the slide. Esc when done."
        slide.deck != null && present.selection.isEmpty() -> "$click a card to talk about it on this slide, or not. Drag across the slide to select."
        present.selection.isNotEmpty() -> if (touch) "Drag to move it; double-tap words to edit them; hold for the menu." else "Drag to move it; double-click words to edit them; right-click for the menu."
        zone != null -> "$click something to change it. The camera stands in its frame: ${click.lowercase()} it to move it on this slide."
        else -> "$click something to change it, or drag a box round several."
    }
    Row(
        Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Help(hint, Modifier.weight(1f), maxLines = 1)
        if (touch) {
            WordToggle("Select several", present.selectSeveral) { present.selectSeveral = !present.selectSeveral }
            WordToggle("Keep shape", present.keepShape) { present.keepShape = !present.keepShape }
        }
        IconButton(Icons.Minus, { present.zoomBy(1f / SlideZoom.STEP) }, size = 24.dp, enabled = present.zoom.zoom > SlideZoom.MIN, label = "Zoom out", reason = "The whole slide shows")
        Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.Center) { Mono("${present.zoom.percent}%", color = c.ink70) }
        IconButton(Icons.Plus, { present.zoomBy(SlideZoom.STEP) }, size = 24.dp, enabled = present.zoom.zoom < SlideZoom.MAX, label = "Zoom in", reason = "As close as it goes")
        if (!present.zoom.isFitted) MicroLink("Fit", { present.zoomBy(null) })
    }
}

/** Where the turning handle stands: above the top edge's middle, turned with the box. */
private fun rotateHandle(b: CanvasBox, rotation: Float, s: Float): Pair<Float, Float> {
    val r = rotation * kotlin.math.PI.toFloat() / 180f
    val lift = b.h / 2f + 30f / s
    return (b.cx + lift * kotlin.math.sin(r)) to (b.cy - lift * kotlin.math.cos(r))
}

/** Card [id] in or out of the deck slide's focus. */
internal fun toggleFocusCard(h: NeueHolders, slide: Slide, id: Int) {
    val present = h.present
    val p = present.open ?: return
    val focus = slide.deck ?: return
    val next = if (id in focus.cards) focus.copy(cards = focus.cards - id, all = false) else focus.copy(cards = focus.cards + id, all = false)
    present.commit(PresentEdits.updateSlide(p, slide.id) { it.copy(deck = next) }, "Focus")
}

/** The menu over something on the slide. */
internal fun elementMenu(h: NeueHolders): List<MenuEntry> {
    val present = h.present
    val sel = present.selection
    val slide = present.slide
    val one = slide?.elements?.filter { it.id in sel }?.singleOrNull()
    val locked = slide?.elements?.filter { it.id in sel }?.any { it.locked } == true
    return listOfNotNull(
        one?.takeIf { it.type == Element.TEXT || it.type == Element.SHAPE }?.let { e -> MenuEntry("Edit words") { present.editingText = e.id } },
        MenuEntry("Cut", hint = "Ctrl X") { cut(h) },
        MenuEntry("Copy", hint = "Ctrl C") { copy(h) },
        MenuEntry("Paste", hint = "Ctrl V", enabled = present.clipboard.isNotEmpty(), reason = "Nothing copied") { paste(h) },
        MenuEntry("Duplicate", hint = "Ctrl D; then drag the copy") { duplicate(h) },
        MenuEntry("Bring to front", separatorBefore = true) { reorder(h, PresentEdits.FRONT) },
        MenuEntry("Bring forward") { reorder(h, PresentEdits.FORWARD) },
        MenuEntry("Send backward") { reorder(h, PresentEdits.BACKWARD) },
        MenuEntry("Send to back") { reorder(h, PresentEdits.BACK) },
        MenuEntry("Group", separatorBefore = true, enabled = sel.size > 1, reason = "Select two or more") { group(h, true) },
        MenuEntry("Ungroup", enabled = slide?.elements?.any { it.id in sel && it.group != null } == true, reason = "Not grouped") { group(h, false) },
        MenuEntry(if (locked) "Unlock" else "Lock in place") { setLocked(h, !locked) },
        MenuEntry("Builds…", hint = "Animate") { present.tab = PropsTab.ANIMATE },
        MenuEntry("Delete", danger = true, separatorBefore = true) { deleteSelection(h) },
    )
}

/** The menu over the slide itself. */
internal fun canvasMenu(h: NeueHolders): List<MenuEntry> {
    val present = h.present
    return listOf(
        MenuEntry("Paste", hint = "Ctrl V", enabled = present.clipboard.isNotEmpty(), reason = "Nothing copied") { paste(h) },
        MenuEntry("Select everything", hint = "Ctrl A") { selectAll(h) },
        MenuEntry("Background…", separatorBefore = true) { present.tab = PropsTab.SLIDE },
        MenuEntry("Theme…") { present.tab = PropsTab.THEME },
        MenuEntry("New slide") { addSlide(h, SlideLayouts.TITLE_BODY) },
        MenuEntry("Fit the slide in the window", enabled = !present.zoom.isFitted, reason = "It fits") { present.zoomBy(null) },
    )
}

/** The menu over the camera: where it stands on this slide. */
internal fun cameraMenu(h: NeueHolders): List<MenuEntry> {
    val present = h.present
    fun set(camera: String, label: String) {
        val p = present.open ?: return
        val s = present.slide ?: return
        present.commit(PresentEdits.updateSlide(p, s.id) { it.copy(camera = camera, cameraBox = null) }, label)
    }
    return listOf(
        MenuEntry("Where it always is", hint = "the Theme tab's place") { set(Slide.CAMERA_DEFAULT, "Camera") },
        MenuEntry("Big on this slide") {
            val p = present.open ?: return@MenuEntry
            val s = present.slide ?: return@MenuEntry
            present.commit(PresentEdits.updateSlide(p, s.id) { SlideCamera.moved(it, SlideCamera.BIG) }, "Big camera")
        },
        MenuEntry("Hide it on this slide", danger = true, separatorBefore = true) { set(Slide.CAMERA_HIDDEN, "Hide the camera here"); present.cameraPicked = false },
    )
}

/**
 * Words edited where they stand: a text field over the element, styled run by run as the slide
 * draws it, every change put back into the runs ([RichText.edit]) and one step of Undo for a
 * stretch of typing.
 */
@Composable
private fun TextEditLayer(h: NeueHolders, slide: Slide, e: Element, ctx: SlideContext, stage: CanvasBox, s: Float, ox: Float, oy: Float) {
    val present = h.present
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val box = Geometry.box(e, stage)
    val pad = (if (e.type == Element.SHAPE) maxOf(e.padding, 24f) else e.padding) * s
    val plain = remember(e.paras) { RichText.flatten(e.paras).first }
    var field by remember(e.id) { mutableStateOf(TextFieldValue(plain, TextRange(0, plain.length))) }
    if (field.text != plain) field = field.copy(text = plain, selection = TextRange(field.selection.start.coerceAtMost(plain.length), field.selection.end.coerceAtMost(plain.length)))
    val w = (box.w * s - pad * 2).roundToInt()
    val hh = (box.h * s - pad * 2).roundToInt()
    val fit = remember(e.paras, e.role, w, hh, s) {
        fitOf(measurer, { f -> styledText(ctx, e.paras, e.role, s, f, density) }, w, hh, e.fit == Element.FIT_SHRINK).first
    }
    val look = roleLook(e.role, ctx.theme)
    val focus = remember { FocusRequester() }
    LaunchedEffect(e.id) { runCatching { focus.requestFocus() } }
    val align = when (e.paras.firstOrNull()?.align) {
        Para.ALIGN_CENTER -> TextAlign.Center
        Para.ALIGN_RIGHT -> TextAlign.End
        else -> TextAlign.Start
    }
    val transformation = remember(e.paras, fit, s, ctx) {
        VisualTransformation { text ->
            val styled = editingText(ctx, RichText.edit(e.paras, text.text), e.role, s, fit, density)
            TransformedText(if (styled.length == text.length) styled else androidx.compose.ui.text.AnnotatedString(text.text), OffsetMapping.Identity)
        }
    }
    Box(
        Modifier
            .offset { IntOffset((ox + box.x * s + pad).roundToInt(), (oy + box.y * s + pad).roundToInt()) }
            .size(with(density) { (box.w * s - pad * 2).toDp() }, with(density) { (box.h * s - pad * 2).toDp() })
            .graphicsLayer { rotationZ = e.rotation; transformOrigin = TransformOrigin.Center },
    ) {
        BasicTextField(
            value = field,
            onValueChange = { v ->
                val before = field.text
                field = v
                present.textSelection = v.selection
                if (v.text != before) {
                    val p = present.open ?: return@BasicTextField
                    val next = PresentEdits.updateElements(p, slide.id, setOf(e.id)) { el -> el.copy(paras = RichText.edit(el.paras, v.text)) }
                    present.commit(next, "Type", coalesce = "type-${e.id}")
                }
            },
            modifier = Modifier.fillMaxSize().focusRequester(focus).reportsTextFocus(),
            textStyle = TextStyle(
                color = ctx.color(look.color, "#000000"),
                fontSize = with(density) { (look.size * s * fit).toSp() },
                fontFamily = ctx.fonts.of(look.font),
                textAlign = align,
            ),
            cursorBrush = SolidColor(ctx.color("@accent", "#000000")),
            visualTransformation = transformation,
        )
    }
}
