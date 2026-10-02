package com.kaiharimoto.neue.present

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.edit.Guide
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.RichText
import com.kaiharimoto.mastertool.core.present.edit.Snap
import com.kaiharimoto.mastertool.core.present.edit.Transform
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.Box as CanvasBox
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.present.paint.editingText
import com.kaiharimoto.neue.present.paint.fitOf
import com.kaiharimoto.neue.present.paint.roleLook
import com.kaiharimoto.neue.present.paint.styledText
import com.kaiharimoto.neue.theme.Mu
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** What a press on the canvas turned into. */
private sealed interface Drag {
    data class Move(val start: Map<String, CanvasBox>) : Drag
    data class Resize(val id: String, val handle: Transform.Handle, val start: CanvasBox, val rotation: Float) : Drag
    data class Rotate(val id: String, val start: CanvasBox) : Drag
    data object Marquee : Drag
}

/**
 * The slide being made (1.0.70): the painter drawing it as it will be shown, the selection
 * over it with eight handles and a turn, smart guides while moving, a box to select by
 * dragging, double-click to edit words in place — the gestures of `PresentMouse` and
 * `PresentTouch`, which the help dialog prints.
 */
@Composable
internal fun SlideCanvas(h: NeueHolders, p: Presentation, slide: Slide, ctx: SlideContext, show: CompiledShow, modifier: Modifier) {
    val present = h.present
    val c = Mu.colors
    val density = LocalDensity.current
    val index = present.slideIndex
    val zone = show.zone(index)
    val stage = show.stage(index)
    val keys = remember(p.deck) { p.deck?.let { d -> DeckStage.copies(d).map { it.key } }.orEmpty() }
    var guides by remember { mutableStateOf<List<Guide>>(emptyList()) }
    var marquee by remember { mutableStateOf<CanvasBox?>(null) }
    var windowAt by remember { mutableStateOf(Offset.Zero) }
    val latestSlide by rememberUpdatedState(slide)
    val latestStage by rememberUpdatedState(stage)
    val latestZone by rememberUpdatedState(zone)
    val latestShow by rememberUpdatedState(show)

    BoxWithConstraints(modifier.background(c.ink06).padding(20.dp)) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val s = min(wPx / Presentation.WIDTH, hPx / Presentation.HEIGHT).coerceAtLeast(0.0001f)
        val ox = (wPx - Presentation.WIDTH * s) / 2f
        val oy = (hPx - Presentation.HEIGHT * s) / 2f
        Box(Modifier.fillMaxSize().onGloballyPositioned { windowAt = it.positionInWindow() }) {
            SlideView(
                ctx, slide, zone, stage, Modifier.fillMaxSize(),
                deck = if (slide.deck != null) ({ show.deckFrame(index) }) else null,
                deckKeys = keys,
                hidden = setOfNotNull(present.editingText),
                editing = true,
            )
            // The selection, the guides and the box being dragged.
            Canvas(Modifier.fillMaxSize()) {
                translate(ox, oy) {
                    val safe = WebcamLayout.safe
                    drawRect(c.ink.copy(alpha = 0.12f), Offset(safe.x * s, safe.y * s), Size(safe.w * s, safe.h * s), style = Stroke(1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                    val selected = slide.elements.filter { it.id in present.selection }
                    for (e in selected) {
                        val b = Geometry.box(e, stage)
                        rotate(e.rotation, Offset(b.cx * s, b.cy * s)) {
                            drawRect(c.paper, Offset(b.x * s - 1f, b.y * s - 1f), Size(b.w * s + 2f, b.h * s + 2f), style = Stroke(3f))
                            drawRect(c.ink, Offset(b.x * s - 1f, b.y * s - 1f), Size(b.w * s + 2f, b.h * s + 2f), style = Stroke(1.5f))
                        }
                    }
                    if (selected.size == 1 && !selected[0].locked && present.editingText == null) {
                        val e = selected[0]
                        val b = Geometry.box(e, stage)
                        val hs = 9f
                        for (handle in Transform.Handle.entries) {
                            val (hx, hy) = Transform.handlePoint(b, e.rotation, handle)
                            drawRect(c.paper, Offset(hx * s - hs / 2f, hy * s - hs / 2f), Size(hs, hs))
                            drawRect(c.ink, Offset(hx * s - hs / 2f, hy * s - hs / 2f), Size(hs, hs), style = Stroke(1.5f))
                        }
                        val (rx, ry) = rotateHandle(b, e.rotation, s)
                        val (tx, ty) = Transform.handlePoint(b, e.rotation, Transform.Handle.TOP)
                        drawLine(c.ink, Offset(tx * s, ty * s), Offset(rx * s, ry * s), 1.5f)
                        drawCircle(c.paper, 7f, Offset(rx * s, ry * s))
                        drawCircle(c.ink, 7f, Offset(rx * s, ry * s), style = Stroke(1.5f))
                    }
                    for (g in guides) {
                        val a = if (g.vertical) Offset(g.at * s, g.from * s) else Offset(g.from * s, g.at * s)
                        val z = if (g.vertical) Offset(g.at * s, g.to * s) else Offset(g.to * s, g.at * s)
                        drawLine(c.paper, a, z, 3f)
                        drawLine(c.ink, a, z, 1f)
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
                    .pointerInput(Unit) {
                        var lastClickAt = 0L
                        var lastClickId: String? = null
                        var gesture = 0
                        awaitPointerEventScope {
                            while (true) {
                                var ev: PointerEvent
                                do { ev = awaitPointerEvent() } while (ev.type != PointerEventType.Press)
                                val down = ev.changes.firstOrNull() ?: continue
                                gesture++
                                val sNow = min(size.width / Presentation.WIDTH, size.height / Presentation.HEIGHT)
                                val oxNow = (size.width - Presentation.WIDTH * sNow) / 2f
                                val oyNow = (size.height - Presentation.HEIGHT * sNow) / 2f
                                fun toCanvas(o: Offset) = Offset((o.x - oxNow) / sNow, (o.y - oyNow) / sNow)
                                val at = toCanvas(down.position)
                                val mods = ev.keyboardModifiers
                                val shift = mods.isShiftPressed
                                val alt = mods.isAltPressed
                                val multi = shift || mods.isCtrlPressed || mods.isMetaPressed
                                val secondary = ev.buttons.isSecondaryPressed
                                val finger = down.type == PointerType.Touch
                                val sl = latestSlide
                                val st = latestStage
                                val hit = sl.elements.asReversed().firstOrNull { e -> Transform.hit(Geometry.box(e, st), e.rotation, at.x, at.y, slop = 6f / sNow) }
                                if (present.editingText != null && hit?.id != present.editingText) present.editingText = null

                                if (secondary) {
                                    if (hit != null && hit.id !in present.selection) present.selection = present.groupOf(hit.id)
                                    if (hit == null) present.selection = emptySet()
                                    val windowPos = windowAt + down.position
                                    h.neue.menu = MenuSpec(windowPos, if (hit != null) elementMenu(h) else canvasMenu(h))
                                    down.consume()
                                    continue
                                }

                                // A handle of the one selected element.
                                val only = sl.elements.filter { it.id in present.selection }.singleOrNull()?.takeIf { !it.locked }
                                var drag: Drag? = null
                                if (only != null && present.editingText == null) {
                                    val b = Geometry.box(only, st)
                                    val reach = (if (finger) 22f else 10f) / sNow
                                    val (rx, ry) = rotateHandle(b, only.rotation, sNow)
                                    if (abs(at.x - rx) < reach && abs(at.y - ry) < reach) {
                                        drag = Drag.Rotate(only.id, b)
                                    } else {
                                        Transform.Handle.entries.firstOrNull { hd ->
                                            val (hx, hy) = Transform.handlePoint(b, only.rotation, hd)
                                            abs(at.x - hx) < reach && abs(at.y - hy) < reach
                                        }?.let { drag = Drag.Resize(only.id, it, b, only.rotation) }
                                    }
                                }
                                if (drag == null) {
                                    if (hit != null) {
                                        val group = present.groupOf(hit.id)
                                        if (multi) {
                                            present.selection = if (hit.id in present.selection) present.selection - group else present.selection + group
                                        } else if (hit.id !in present.selection) {
                                            present.selection = group
                                        }
                                        val moving = sl.elements.filter { it.id in present.selection && !it.locked }
                                        if (moving.isNotEmpty()) drag = Drag.Move(moving.associate { it.id to Geometry.box(it, st) })
                                        present.tab = if (present.tab == PropsTab.DECK || present.tab == PropsTab.THEME || present.tab == PropsTab.SLIDE) PropsTab.ELEMENT else present.tab
                                    } else {
                                        if (!multi) present.selection = emptySet()
                                        drag = Drag.Marquee
                                    }
                                }
                                down.consume()

                                var moved = false
                                var dup = alt
                                var current = drag
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val e2 = awaitPointerEvent()
                                    val ch = e2.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    if (!moved && (ch.position - down.position).getDistance() < slop) continue
                                    moved = true
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
                                            val targets = listOfNotNull(CanvasBox.CANVAS, WebcamLayout.safe, st, latestZone) + others
                                            val snapped = if (mods.isCtrlPressed) null else Snap.move(union, targets, 8f / sNow)
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
                                            val keep = e2.keyboardModifiers.isShiftPressed ||
                                                (d.handle.hx != 0 && d.handle.hy != 0 && sl.element(d.id)?.type in setOf(Element.IMAGE, Element.CARD))
                                            val b = Transform.resize(d.start, d.rotation, d.handle, dx, dy, keepAspect = keep, fromCentre = e2.keyboardModifiers.isAltPressed)
                                            present.commit(PresentEdits.updateElements(pNow, sid, setOf(d.id)) { Geometry.place(it, b, st) }, "Resize", coalesce = "drag-$gesture")
                                        }
                                        is Drag.Rotate -> {
                                            val deg = Transform.rotation(d.start, now.x, now.y, snap = e2.keyboardModifiers.isShiftPressed)
                                            present.commit(PresentEdits.updateElements(pNow, sid, setOf(d.id)) { it.copy(rotation = deg) }, "Turn", coalesce = "drag-$gesture")
                                        }
                                        Drag.Marquee -> {
                                            val m = CanvasBox(min(at.x, now.x), min(at.y, now.y), abs(dx), abs(dy))
                                            marquee = m
                                            present.selection = sl.elements.filter { Transform.bounds(Geometry.box(it, st), it.rotation).intersects(m) }.map { it.id }.toSet()
                                        }
                                        null -> Unit
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
                                    if (double && (hit.type == Element.TEXT || hit.type == Element.SHAPE) && !hit.locked) {
                                        present.selection = setOf(hit.id)
                                        present.editingText = hit.id
                                    } else if (hit == null && present.tab == PropsTab.DECK && sl.deck != null) {
                                        // A card of the deck slide: talked about on this slide, or not.
                                        val f = latestShow.deckFrame(present.slideIndex)
                                        f?.cards?.firstOrNull { it.box.contains(at.x, at.y) }?.let { card -> toggleFocusCard(h, sl, card.id) }
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
        MenuEntry("Duplicate", hint = "Ctrl D") { duplicate(h) },
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
        MenuEntry("New slide") { addSlide(h, com.kaiharimoto.mastertool.core.present.SlideLayouts.TITLE_BODY) },
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
