package com.kaiharimoto.neue.cursor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.CropCaption
import com.kaiharimoto.mastertool.core.input.CursorBox
import com.kaiharimoto.mastertool.core.input.CursorBusy
import com.kaiharimoto.mastertool.core.input.CursorCaption
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.CursorTarget
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/**
 * The Master UI family cursor, **Crop caption**, in Compose — the same pointer
 * every other Master app draws with `kit/cursor/cursor.js` (Master-UI
 * `kit/guides/CURSOR.md` is the spec). Four 2px trim marks around a 2px point,
 * in difference mode; over something clickable they open out and frame it, and
 * a micro-caps caption in the slug under the frame says what a click will do.
 *
 * The web kit reads intent off the DOM. Compose has no DOM, so a target says
 * what it is with [cursor] — the Compose form of the kit's native semantics and
 * `data-cursor*` hooks — and Compose's own hit testing decides which targets
 * the pointer is over, so a dialog or a drawer occludes what is under it
 * exactly as it does for a click. The arithmetic is `core/input/CropCaption.kt`,
 * with the reference script's numbers, and tested there.
 *
 * One per window, installed by the shell: [CursorLayer] draws it last, and the
 * shell hides the system pointer with [BLANK] while it runs.
 */
class FamilyCursor {
    /** Where the pointer is, in window pixels, or null while it is out of the window. */
    var position by mutableStateOf<Offset?>(null)

    /** A mouse button is held. */
    var pressed by mutableStateOf(false)

    /** A long job: busy overrides every other state while it is set. */
    var busy by mutableStateOf<CursorBusy?>(null)

    internal val hovered = mutableStateListOf<CursorHook>()

    /** A drag or a text target held under the button: the state stays until it is let go. */
    internal var locked by mutableStateOf<CursorHook?>(null)

    /** `setCursorBusy(…)`. */
    fun setBusy(label: String? = null, pct: Float? = null) {
        busy = CursorBusy(label, pct)
    }

    fun clearBusy() {
        busy = null
    }

    /** The target under the pointer now: the locked one, else the innermost hovered. */
    internal fun under(): CursorHook? {
        locked?.let { return it }
        val at = position ?: return null
        return CropCaption.innermost(hovered.toList(), at.x, at.y) { it.box() }
    }

    /** The state and its target, as the layer and the shell read them. */
    internal fun resolve(): Pair<CursorMode, CursorHook?> {
        val hook = under()
        return CropCaption.modeOf(hook?.target(), busy) to hook
    }

    /** The state and the caption it would show, for the studio's log. */
    fun debugResolve(): Pair<CursorMode, String?> {
        val (mode, hook) = resolve()
        return mode to CropCaption.caption(mode, hook?.target(), busy)?.let { "“${it.text}${if (it.value.isNotEmpty()) " " + it.value else ""}”" }
    }

    /** Whether the family cursor has stepped aside for the system pointer. */
    val native: Boolean
        get() = resolve().first == CursorMode.NATIVE

    /** The window's pointer watcher reports here. */
    fun moved(at: Offset?, buttonsDown: Boolean) {
        position = at
        if (buttonsDown && !pressed) {
            pressed = true
            val (mode, hook) = resolve()
            if (mode == CursorMode.DRAG || mode == CursorMode.TEXT) locked = hook
        } else if (!buttonsDown && pressed) {
            pressed = false
            locked = null
        }
    }

    companion object {
        /**
         * The system pointer, hidden: a one-pixel transparent image. Where the
         * toolkit cannot make one (headless, as the studio runs), the normal
         * arrow — as the kit keeps it when its script never loads.
         */
        val BLANK: PointerIcon by lazy {
            runCatching {
                val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "master-ui-cursor"))
            }.getOrDefault(PointerIcon.Default)
        }
    }
}

/** A target the cursor knows about: what it is, and where it is. */
internal class CursorHook {
    var spec by mutableStateOf(CursorTarget(CursorMode.DEFAULT, CursorBox(0f, 0f, 0f, 0f)))
    var bounds by mutableStateOf(Rect.Zero)

    fun box(): CursorBox = CursorBox(bounds.left, bounds.top, bounds.width, bounds.height)

    fun target(): CursorTarget = spec.copy(bounds = box())
}

val LocalCursor = staticCompositionLocalOf<FamilyCursor?> { null }

/**
 * What the pointer means over this element — the kit's `data-cursor` hooks.
 *
 * - [mode]: `data-cursor`. A disabled control is [CursorMode.NO].
 * - [caption]: `data-cursor-caption`, a verb: `Open`, `Select`, `Create →`.
 * - [label]: `aria-label`, captioned only when [showsWords] is false (an icon button).
 * - [reason]: `data-cursor-reason`, why a disabled control is disabled.
 * - [value]: `data-cursor-value`, a drag's live value as people read it: `440 px`.
 * - [fontSize], [singleLine], [focused]: what a text field's caret needs.
 * - [slider]: a horizontal range.
 */
fun Modifier.cursor(
    mode: CursorMode,
    caption: String? = null,
    label: String? = null,
    showsWords: Boolean = false,
    reason: String? = null,
    value: String? = null,
    fontSize: TextUnit = 14.sp,
    singleLine: Boolean = true,
    focused: Boolean = false,
    slider: Boolean = false,
): Modifier = composed {
    val cursor = LocalCursor.current ?: return@composed Modifier
    val density = LocalDensity.current
    val hook = remember { CursorHook() }
    val px = with(density) { if (fontSize.isSp) fontSize.toPx() else 14.sp.toPx() }
    hook.spec = CursorTarget(
        mode = mode,
        bounds = hook.box(),
        caption = caption,
        label = label,
        showsWords = showsWords,
        reason = reason,
        value = value,
        fontSize = px,
        singleLine = singleLine,
        focused = focused,
        slider = slider,
    )
    DisposableEffect(hook) {
        onDispose {
            cursor.hovered.remove(hook)
            if (cursor.locked === hook) cursor.locked = null
        }
    }
    Modifier
        .onGloballyPositioned { hook.bounds = it.boundsInWindow() }
        .pointerInput(hook) {
            // Hover, as Compose's hit testing sees it: what occludes a click occludes this.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Exit -> cursor.hovered.remove(hook)
                        else -> if (hook !in cursor.hovered) cursor.hovered.add(hook)
                    }
                }
            }
        }
}

/** A clickable thing: framed, captioned with [caption] (or [label] when it shows no words), `✕` and [reason] when not [enabled]. */
fun Modifier.cursorPointer(
    caption: String? = null,
    label: String? = null,
    showsWords: Boolean = false,
    enabled: Boolean = true,
    reason: String? = null,
): Modifier = cursor(
    mode = if (enabled) CursorMode.POINTER else CursorMode.NO,
    caption = caption,
    label = label,
    showsWords = showsWords,
    reason = reason,
)

/**
 * Draws the cursor: last in the window, above everything in it, taking no
 * input. Motion is the spec's: the frame snaps to a new target over 180 ms on
 * the family's easing, the caption fades and drops 4 px over 120 ms, busy ticks
 * one mark at a time and breathes; following the pointer has no easing at all.
 */
@Composable
fun CursorLayer(cursor: FamilyCursor, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val fonts = LocalMuFonts.current
    val measurer = rememberTextMeasurer()
    val motion = remember { CursorMotion() }

    // A frame clock only while something is moving on its own: a snap, a caption
    // fading, or busy. Following the pointer needs none — every move redraws.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(cursor) {
        snapshotFlow { motion.animating || cursor.busy != null }.collect { running ->
            while (running && (motion.animating || cursor.busy != null)) {
                withFrameNanos { now = it / 1_000_000 }
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        now // read, so the clock redraws the layer
        val at = cursor.position ?: return@Canvas
        val (mode, hook) = cursor.resolve()
        if (mode == CursorMode.NATIVE) return@Canvas
        // The spec's numbers are CSS pixels, which are dp here: everything is worked in dp and drawn at × density.
        val target = hook?.target()?.let { t ->
            val b = t.bounds
            t.copy(bounds = CursorBox(b.x / density, b.y / density, b.w / density, b.h / density), fontSize = t.fontSize / density)
        }
        val clock = System.nanoTime() / 1_000_000
        val (live, wide) = CropCaption.geometry(mode, target, at.x / density, at.y / density, size.width / density, size.height / density)
        val key = "${mode.name}|${System.identityHashCode(hook)}"
        motion.ticking = mode == CursorMode.BUSY
        val box = motion.frame(key, live, clock)
        val arm = motion.arm(CropCaption.arm(mode), clock)
        val s = density

        // The marks and the point, in difference mode: they read on paper, on ink and on pictures.
        val lit = if (mode == CursorMode.BUSY) CropCaption.litMark(clock) else -1
        val markAlpha = if (mode == CursorMode.NO) 0.3f else 1f
        val filled = cursor.pressed && mode != CursorMode.NO
        corners(box, arm, s).forEachIndexed { i, (corner, dir) ->
            val alpha = markAlpha * if (lit >= 0 && lit != i) 0.25f else 1f
            mark(corner, dir, arm * s, 2f * s, filled, Color.White.copy(alpha = alpha))
        }
        if (mode == CursorMode.NO) {
            val cross = measurer.measure("✕", TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.Bold, fontSize = 9.sp))
            drawText(
                cross,
                color = Color.White,
                topLeft = Offset((box.x + box.w / 2f) * s - cross.size.width / 2f, (box.y + box.h / 2f) * s - cross.size.height / 2f),
                blendMode = BlendMode.Difference,
            )
        } else {
            drawRect(Color.White, Offset(at.x - s, at.y - s), Size(2f * s, 2f * s), blendMode = BlendMode.Difference)
        }

        // The caption, in the slug under the frame.
        val words = CropCaption.caption(mode, target, cursor.busy)
        val shown = motion.caption(words, clock)
        val cap = motion.lastCaption ?: return@Canvas
        if (shown <= 0.001f) return@Canvas
        val text = buildAnnotatedString {
            if (cap.text.isNotEmpty()) append(cap.text.uppercase())
            if (cap.value.isNotEmpty()) {
                if (cap.text.isNotEmpty()) append(" ")
                withStyle(SpanStyle(fontFamily = fonts.mono, fontWeight = FontWeight.Normal, letterSpacing = 0.em)) { append(cap.value) }
            }
        }
        val layout = measurer.measure(
            text,
            TextStyle(fontFamily = fonts.sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.08.em),
        )
        val busyNow = mode == CursorMode.BUSY
        // The breathing square and its 6 px gap, when busy.
        val square = if (busyNow) 12f else 0f
        val capW = 7f * 2 + square + layout.size.width / s
        val capH = CropCaption.CAPTION_H
        val (cx, cy) = CropCaption.captionAt(box, wide, at.x / s, capW, capH, size.width / s, size.height / s)
        val drop = (1f - shown) * -4f
        val (bg, fg, edge) = when {
            mode == CursorMode.NO -> Triple(c.paper, c.ink70, c.ink25)
            cursor.pressed && mode == CursorMode.POINTER -> Triple(c.paper, c.ink, c.ink)
            else -> Triple(c.ink, c.paper, c.ink)
        }
        val left = cx * s
        val top = (cy + drop) * s
        val w = capW * s
        val h = capH * s
        // The double stroke of §17.3: an ink block with a 1px paper outline outside it.
        drawRect(c.paper.copy(alpha = shown), Offset(left - s, top - s), Size(w + 2 * s, h + 2 * s))
        drawRect(bg.copy(alpha = bg.alpha * shown), Offset(left, top), Size(w, h))
        drawRect(edge.copy(alpha = edge.alpha * shown), Offset(left + s / 2f, top + s / 2f), Size(w - s, h - s), style = Stroke(s))
        var x = left + 7f * s
        if (busyNow) {
            val breathe = CropCaption.breathe(clock)
            drawRect(fg.copy(alpha = fg.alpha * shown * breathe), Offset(x, top + h / 2f - 3f * s), Size(6f * s, 6f * s))
            x += 12f * s
        }
        drawText(layout, color = fg.copy(alpha = fg.alpha * shown), topLeft = Offset(x, top + (h - layout.size.height) / 2f))
    }
}

/** The corners of [box] in pixels, each with the direction its arms point (inward). */
private fun corners(box: CursorBox, arm: Float, s: Float): List<Pair<Offset, Pair<Float, Float>>> {
    val l = box.x * s
    val t = box.y * s
    val r = box.right * s
    val b = box.bottom * s
    return listOf(
        Offset(l, t) to (1f to 1f),
        Offset(r, t) to (-1f to 1f),
        Offset(r, b) to (-1f to -1f),
        Offset(l, b) to (1f to -1f),
    )
}

/** One trim mark: an L of [weight] with arms of [arm], or the whole square when [filled] (pressed). */
private fun DrawScope.mark(corner: Offset, dir: Pair<Float, Float>, arm: Float, weight: Float, filled: Boolean, color: Color) {
    val (dx, dy) = dir
    val x0 = if (dx > 0) corner.x else corner.x - arm
    val y0 = if (dy > 0) corner.y else corner.y - arm
    if (filled) {
        drawRect(color, Offset(x0, y0), Size(arm, arm), blendMode = BlendMode.Difference)
        return
    }
    // The horizontal arm along the edge, and the vertical one without its shared corner.
    val hy = if (dy > 0) corner.y else corner.y - weight
    drawRect(color, Offset(x0, hy), Size(arm, weight), blendMode = BlendMode.Difference)
    val vx = if (dx > 0) corner.x else corner.x - weight
    val vy = if (dy > 0) corner.y + weight else corner.y - arm
    drawRect(color, Offset(vx, vy), Size(weight, arm - weight), blendMode = BlendMode.Difference)
}

/** The cursor's own transitions, advanced by the clock the layer is drawn at. */
private class CursorMotion {
    private var key: String? = null
    private var drawn: CursorBox? = null
    private var from: CursorBox? = null
    private var snapAt = 0L
    private var armFrom = 6f
    private var armTo = 6f
    private var armAt = 0L
    private var captionOn = false
    private var captionAt = 0L
    private var captionFrom = 0f
    var lastCaption: CursorCaption? = null
        private set

    var animating by mutableStateOf(false)
        private set

    /** Busy is drawn (globally or over a busy region): the marks tick and the square breathes. */
    var ticking = false

    fun frame(newKey: String, live: CursorBox, now: Long): CursorBox {
        val last = drawn
        if (newKey != key) {
            key = newKey
            from = last
            snapAt = now
        }
        val f = from
        val t = ((now - snapAt).toFloat() / CropCaption.SNAP_MS).coerceIn(0f, 1f)
        val box = if (f == null || t >= 1f) live else f.lerp(live, CropCaption.ease(t))
        drawn = box
        settle(now)
        return box
    }

    fun arm(target: Float, now: Long): Float {
        if (target != armTo) {
            armFrom = current(now)
            armTo = target
            armAt = now
        }
        return current(now)
    }

    private fun current(now: Long): Float {
        val t = ((now - armAt).toFloat() / CropCaption.SNAP_MS).coerceIn(0f, 1f)
        return armFrom + (armTo - armFrom) * CropCaption.ease(t)
    }

    /** How far the caption is shown, 0..1; the last words stay while it fades out. */
    fun caption(words: CursorCaption?, now: Long): Float {
        val on = words != null
        if (on && words != lastCaption && captionOn) {
            // New words on a caption already up: shown at once, no second fade.
            lastCaption = words
        }
        if (on != captionOn) {
            captionFrom = shownAt(now)
            captionOn = on
            captionAt = now
            if (on) lastCaption = words
        }
        settle(now)
        return shownAt(now)
    }

    private fun shownAt(now: Long): Float {
        val t = ((now - captionAt).toFloat() / CropCaption.FADE_MS).coerceIn(0f, 1f)
        val to = if (captionOn) 1f else 0f
        return captionFrom + (to - captionFrom) * CropCaption.ease(t)
    }

    private fun settle(now: Long) {
        val moving = ticking || now - snapAt < CropCaption.SNAP_MS + 20 || now - armAt < CropCaption.SNAP_MS + 20 || now - captionAt < CropCaption.FADE_MS + 20
        if (moving != animating) animating = moving
    }
}
