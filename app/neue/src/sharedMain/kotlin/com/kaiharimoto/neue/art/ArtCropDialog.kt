package com.kaiharimoto.neue.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.layout.ArtCrop
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.ArtWindow
import com.kaiharimoto.mastertool.core.layout.ContentBounds
import com.kaiharimoto.mastertool.core.layout.CropBox
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.PickedFile
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.platform.droppedPicture
import com.kaiharimoto.neue.platform.encodePng
import com.kaiharimoto.neue.platform.mayBePicture
import com.kaiharimoto.neue.platform.pastedPicture
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** YGOPRODeck's render, which every art window is measured against. */
private const val RENDER_WIDTH = 813f
private const val RENDER_HEIGHT = 1185f

/**
 * Your own art in a card (kai, 1.0.34: "let the user upload an image, drag a
 * file from an explorer, or just paste a copied image, then … crop the area
 * that they wish to be just the card art, and it will replace the card art").
 *
 * A picture arrives by any of the three; a box of the card's art window
 * ([ArtWindow], measured off the renders beside the foil's [ArtFrame]) is laid
 * over it, moved by a drag and resized by its corners, the wheel or a pinch;
 * and the card beside it shows the crop in place as it moves. **Replace the
 * art** draws the card's own original with the crop in its art box and keeps
 * that as one of the card's own pictures ([CustomArt]) — so the foil, the name
 * stamped in it, the viewer and the screenshot all read it as they read any
 * picture. **Whole card** keeps the picture as it is, as before.
 *
 * [base] is the printing whose frame and text are kept: the artwork chosen for
 * the card, when it is one the pool knows.
 */
/** Your own art being cropped into [card]: the picture it arrived with, when it was dropped on the card. */
data class ArtCropping(val card: Card, val picture: PickedFile? = null)

@Composable
fun ArtCropDialog(
    card: Card,
    base: Card,
    initial: PickedFile?,
    custom: CustomArt,
    library: ArtLibrary?,
    touch: Boolean,
    onChosen: (Int) -> Unit,
    onNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    val window = remember(base.frameType) { ArtFrame.of(base.frameType)?.let(ArtWindow::of) }
    var source by remember { mutableStateOf<PickedFile?>(null) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var box by remember { mutableStateOf<CropBox?>(null) }
    var face by remember { mutableStateOf<ImageBitmap?>(null) }
    var faceMissing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }

    // The card's own original, which the crop is drawn into.
    LaunchedEffect(base.id) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching { library?.ensure(base)?.readBytes()?.let(::decodePicture) }.getOrNull()
        }
        face = decoded
        faceMissing = decoded == null
    }

    fun take(picked: PickedFile) {
        scope.launch {
            val image = withContext(Dispatchers.Default) { decodePicture(picked.bytes) }
            if (image == null) {
                onNote("That is not a picture Neue can read.")
                return@launch
            }
            source = picked
            picture = image
            box = window?.let { ArtCrop.initial(image.width.toFloat(), image.height.toFloat(), it.aspect(RENDER_WIDTH, RENDER_HEIGHT)) }
        }
    }
    val takeNow by rememberUpdatedState(::take)
    LaunchedEffect(initial) { initial?.let(takeNow) }
    val choose: () -> Unit = {
        scope.launch { Platform.pick("Choose a picture for this card", PICTURE_EXTENSIONS)?.let(takeNow) }
    }
    val paste: () -> Unit = {
        scope.launch { pastedPicture()?.let(takeNow) ?: onNote("There is no picture on the clipboard.") }
    }
    val drop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val picked = droppedPicture(event)
                if (picked != null) {
                    takeNow(picked)
                    return true
                }
                // An image dragged out of a browser is its address: fetched, then taken (1.0.89).
                val link = com.kaiharimoto.neue.platform.droppedLink(event) ?: return false
                scope.launch {
                    com.kaiharimoto.neue.platform.fetchPicture(link)?.let(takeNow)
                        ?: onNote("That picture could not be fetched. Save it, then drop the file, or copy it and paste here.")
                }
                return true
            }

            override fun onEntered(event: DragAndDropEvent) {
                dropping = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dropping = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dropping = false
            }
        }
    }

    /** The crop where the art most likely is ([ArtCrop.auto]): margins trimmed off the pixels, a whole card's art box found. */
    fun autoAlign() {
        val image = picture ?: return
        val w = window ?: return
        // Off the frame thread, and only the rows [ContentBounds] looks at, each read once (1.0.92): the
        // same pixels it read from the whole picture's copy, without the copy — 64 MB for a 4096-pixel square.
        scope.launch {
            val bounds = withContext(Dispatchers.Default) {
                val rows = HashMap<Int, IntArray>()
                val step = (maxOf(image.width, image.height) / 600).coerceAtLeast(1)
                runCatching {
                    ContentBounds.of(image.width, image.height, step = step) { x, y ->
                        rows.getOrPut(y) { IntArray(image.width).also { image.readPixels(it, startX = 0, startY = y, width = image.width, height = 1) } }[x]
                    }
                }
            }.getOrElse { return@launch }
            // A picture changed while this was working is not this crop's.
            if (picture !== image) return@launch
            box = ArtCrop.auto(
                image.width.toFloat(), image.height.toFloat(), w.aspect(RENDER_WIDTH, RENDER_HEIGHT), w, RENDER_WIDTH / RENDER_HEIGHT, bounds,
            )
        }
    }

    fun replace() {
        val f = face ?: return
        val p = picture ?: return
        val b = box ?: return
        val w = window ?: return
        saving = true
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { runCatching { encodePng(bake(f, p, b, w)) }.getOrNull() }
            val choice = bytes?.let { custom.keep(card.id.value, PickedFile("art.png", it)) }
            saving = false
            if (choice == null) onNote("That picture could not be saved.") else onChosen(choice)
        }
    }

    fun whole() {
        val picked = source ?: return
        val choice = custom.keep(card.id.value, picked)
        if (choice == null) onNote("That picture could not be saved.") else onChosen(choice)
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shortcut = if (Platform.os == DesktopOs.MAC) "Cmd V" else "Ctrl V"

    MuDialog(
        title = "Your own art",
        onDismiss = onDismiss,
        width = 880.dp,
        description = if (window != null) {
            "For “${card.name}”. The part of the picture in the box goes in the card's art box; the rest of the card stays as printed."
        } else {
            "For “${card.name}”. This card has no art box, so your picture is used as the whole card."
        },
        footer = {
            MuButton("Cancel", onDismiss, variant = BtnVariant.GHOST)
            MuButton("Whole card", ::whole, enabled = source != null, reason = "Add a picture first")
            // Auto align (kai, 1.0.89): the art box of a whole card in the picture, or the art inside plain margins.
            if (window != null) MuButton("Auto align", ::autoAlign, variant = BtnVariant.GHOST, enabled = picture != null, reason = "Add a picture first")
            if (window != null) {
                MuButton(
                    if (saving) "Saving…" else "Replace art",
                    ::replace,
                    variant = BtnVariant.PRIMARY,
                    enabled = picture != null && face != null && !saving,
                    reason = when {
                        picture == null -> "Add a picture first"
                        face == null && faceMissing -> "The card's own picture could not be loaded"
                        else -> "The card's own picture is still loading"
                    },
                )
            }
        },
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)) {
                        paste()
                        true
                    } else {
                        false
                    }
                },
        ) {
            val wide = maxWidth >= 600.dp
            val editorHeight = if (wide) 440.dp else 300.dp
            val previewWidth = if (wide) 220.dp else 140.dp
            val editor: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(editorHeight)
                            .background(c.ink06)
                            .border(1.dp, if (dropping) c.ink else c.ink25)
                            .dragAndDropTarget(shouldStartDragAndDrop = { mayBePicture(it) }, target = drop),
                    ) {
                        val p = picture
                        val b = box
                        when {
                            p != null && b != null -> CropEditor(p, b, { box = it }, touch, Modifier.fillMaxSize())
                            p != null -> Canvas(Modifier.fillMaxSize()) { drawFitted(p) }
                            else -> Column(
                                Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Body(if (touch) "Choose a picture, or paste one you copied" else "Drop a picture here", color = c.ink)
                                Help(if (touch) "A photo, a screenshot, a picture from the web" else "from a folder or a browser, or paste one you copied — $shortcut")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MuButton("Choose a picture", choose)
                                    MuButton("Paste", paste, variant = BtnVariant.GHOST)
                                }
                            }
                        }
                    }
                    if (picture != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Help(
                                when {
                                    box == null -> "The picture is used as it is."
                                    touch -> "Drag to move the box; pinch or drag a corner to resize it."
                                    else -> "Drag to move the box; the wheel or a corner resizes it."
                                },
                                Modifier.weight(1f),
                            )
                            MicroLink("Another picture", choose)
                            MicroLink("Paste", paste)
                        }
                    }
                }
            }
            val preview: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(previewWidth).aspectRatio(CARD_RATIO).background(c.ink06)) {
                        val f = face
                        when {
                            f != null -> Canvas(Modifier.fillMaxSize()) {
                                val w = window
                                if (w != null) drawCardWithArt(f, picture, box, w) else picture?.let { drawFitted(it) }
                            }
                            faceMissing -> Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                Help("The card's own picture could not be loaded. Whole card still works.")
                            }
                            else -> Hatch(Modifier.fillMaxSize(), live = true, color = c.ink25)
                        }
                    }
                    Help("As it will look", Modifier.width(previewWidth))
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    editor(Modifier.weight(1f))
                    preview()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    editor(Modifier.fillMaxWidth())
                    preview()
                }
            }
        }
    }
}

/**
 * The picture with its crop box over it: the picture fitted whole, a veil of
 * paper over what is left out, and the box ruled in ink with a handle at each
 * corner. A drag inside moves the box, a drag at a corner resizes it, and the
 * wheel or a pinch resizes it about the pointer.
 */
@Composable
private fun CropEditor(picture: ImageBitmap, box: CropBox, onBox: (CropBox) -> Unit, touch: Boolean, modifier: Modifier) {
    val c = Mu.colors
    val current by rememberUpdatedState(box)
    val set by rememberUpdatedState(onBox)
    val iw = picture.width.toFloat()
    val ih = picture.height.toFloat()
    val density = LocalDensity.current
    val reach = with(density) { (if (touch) 24.dp else 12.dp).toPx() }
    val handle = with(density) { (if (touch) 14.dp else 9.dp).toPx() }
    // The view's size, for the gestures: a plain holder, since nothing is drawn from it.
    val view = remember { FloatArray(2) }
    fun fit() = ArtCrop.fit(iw, ih, view[0].coerceAtLeast(1f), view[1].coerceAtLeast(1f))
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { view[0] = it.width.toFloat(); view[1] = it.height.toFloat() }
            .cursor(CursorMode.DRAG, caption = "Move")
            .pointerInput(picture) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val f = fit()
                    val corner = ArtCrop.cornerAt(current, f, down.position.x, down.position.y, reach)
                    var last = down.position
                    var spread: Float? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val d = (a - b).getDistance()
                            val was = spread
                            if (was != null && was > 0f && d > 0f) {
                                val mid = (a + b) / 2f
                                val (ax, ay) = f.toImage(mid.x, mid.y)
                                // Fingers apart is closer in: the box shrinks as the picture grows.
                                set(ArtCrop.scaled(current, was / d, ax, ay, iw, ih))
                            }
                            spread = d
                            last = a
                        } else {
                            val p = pressed[0].position
                            if (spread == null) {
                                if (corner != null) {
                                    val (px, py) = f.toImage(p.x, p.y)
                                    set(ArtCrop.resized(current, corner, px, py, iw, ih))
                                } else {
                                    set(ArtCrop.moved(current, (p.x - last.x) / f.scale, (p.y - last.y) / f.scale, iw, ih))
                                }
                            }
                            last = p
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .onPointer(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointer
                val (ax, ay) = fit().toImage(change.position.x, change.position.y)
                set(ArtCrop.scaled(current, 1.1f.pow(change.scrollDelta.y), ax, ay, iw, ih))
                change.consume()
            },
    ) {
        val f = ArtCrop.fit(iw, ih, size.width, size.height)
        drawFitted(picture)
        val (x0, y0) = f.toView(box.x, box.y)
        val (x1, y1) = f.toView(box.right, box.bottom)
        val veil = c.paper.copy(alpha = 0.72f)
        drawRect(veil, Offset.Zero, Size(size.width, y0))
        drawRect(veil, Offset(0f, y1), Size(size.width, size.height - y1))
        drawRect(veil, Offset(0f, y0), Size(x0, y1 - y0))
        drawRect(veil, Offset(x1, y0), Size(size.width - x1, y1 - y0))
        val line = 2.dp.toPx()
        drawRect(c.ink, Offset(x0 - line / 2, y0 - line / 2), Size(x1 - x0 + line, y1 - y0 + line), style = Stroke(line))
        val inner = 1.dp.toPx()
        drawRect(c.paper, Offset(x0 + inner / 2 + line / 2, y0 + inner / 2 + line / 2), Size(x1 - x0 - inner - line, y1 - y0 - inner - line), style = Stroke(inner))
        listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1).forEach { (x, y) ->
            drawRect(c.ink, Offset(x - handle / 2, y - handle / 2), Size(handle, handle))
            drawRect(c.paper, Offset(x - handle / 2 + inner, y - handle / 2 + inner), Size(handle - 2 * inner, handle - 2 * inner))
        }
    }
}

/** [image] fitted whole into the draw area, centred. */
private fun DrawScope.drawFitted(image: ImageBitmap) {
    val f = ArtCrop.fit(image.width.toFloat(), image.height.toFloat(), size.width, size.height)
    drawImage(
        image,
        dstOffset = IntOffset(f.dx.roundToInt(), f.dy.roundToInt()),
        dstSize = IntSize((image.width * f.scale).roundToInt().coerceAtLeast(1), (image.height * f.scale).roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * The card's own render [face] filling the draw area, with [crop] of [art]
 * drawn into its art [window] — clipped to the window's outline, so a link
 * card's corner sockets stay printed over it. Used for the preview and, at
 * the render's own size, for the picture that is kept.
 */
internal fun DrawScope.drawCardWithArt(face: ImageBitmap, art: ImageBitmap?, crop: CropBox?, window: ArtWindow) {
    drawImage(
        face,
        dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.High,
    )
    if (art == null || crop == null) return
    val outline = window.outline(size.width, size.height)
    val path = Path().apply {
        moveTo(outline[0].first, outline[0].second)
        outline.drop(1).forEach { (x, y) -> lineTo(x, y) }
        close()
    }
    val sx = floor(crop.x).toInt().coerceIn(0, art.width - 1)
    val sy = floor(crop.y).toInt().coerceIn(0, art.height - 1)
    val sw = crop.width.roundToInt().coerceIn(1, art.width - sx)
    val sh = crop.height.roundToInt().coerceIn(1, art.height - sy)
    val left = floor(window.left * size.width).toInt()
    val top = floor(window.top * size.height).toInt()
    val right = ceil(window.right * size.width).toInt()
    val bottom = ceil(window.bottom * size.height).toInt()
    clipPath(path) {
        drawImage(
            art,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset(left, top),
            dstSize = IntSize((right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)),
            filterQuality = FilterQuality.High,
        )
    }
}

/** The card as it is kept: [face] at its own size with [crop] of [art] in its art box. */
internal fun bake(face: ImageBitmap, art: ImageBitmap, crop: CropBox, window: ArtWindow): ImageBitmap {
    val out = ImageBitmap(face.width, face.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(face.width.toFloat(), face.height.toFloat())) {
        drawCardWithArt(face, art, crop, window)
    }
    return out
}
