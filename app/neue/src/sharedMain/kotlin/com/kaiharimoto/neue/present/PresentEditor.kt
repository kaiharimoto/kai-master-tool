package com.kaiharimoto.neue.present

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.text.MicroCaps
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.PresentAction
import com.kaiharimoto.mastertool.core.input.PresentGestures
import com.kaiharimoto.mastertool.core.input.PresentPress
import com.kaiharimoto.mastertool.core.input.PresentTarget
import com.kaiharimoto.mastertool.core.layout.ToolFold
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Fill
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Placeholders
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.Stroke
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.SlidePicks
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.LocalKeepCase
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.pages.NotesField
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The editor (1.0.70): one bar of tools, the slides down the left, the slide being made in
 * the middle with its notes under it, and what is selected — the slide, an element, its
 * builds, the deck's step, the theme — on the right. Google Slides is the benchmark (kai);
 * Master UI is the look of everything that is not the slide.
 */
@Composable
internal fun PresentEditor(h: NeueHolders, p: Presentation) {
    val present = h.present
    val ctx = rememberSlideContext(h, p)
    val show = remember(p) { CompiledShow(p) }
    val slide = present.slide ?: run {
        // A presentation with no slides gets one.
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorBar(h, p)
            MuButton("Add a slide", { addSlide(h, SlideLayouts.TITLE_BODY) }, variant = BtnVariant.PRIMARY, icon = Icons.Plus)
        }
        return
    }
    val c = Mu.colors
    val phone = LocalPhone.current
    Column(Modifier.fillMaxSize()) {
        EditorBar(h, p)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SlideSorter(h, p, ctx, show, Modifier.width(if (phone) 132.dp else 196.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SlideCanvas(h, p, slide, ctx, show, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
                NotesPane(h, p, slide, Modifier.fillMaxWidth().height(if (phone) 72.dp else 112.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
            PropsPanel(h, p, slide, ctx, Modifier.width(if (phone) 260.dp else 336.dp).fillMaxHeight())
        }
    }
    CardPicker(h)
    ModuleDialog(h)
    BuildWithAiDialog(h)
    RestyleDialog(h)
    RestyleWatcher(h)
    ExportOverlay(h)
    FromGroupsDialog(h, p)
}

// ---- the bar -----------------------------------------------------------------------------

/**
 * One tool of the bar. [rank] 0 never folds; the higher, the sooner it goes into ⋯ (`ToolFold`).
 * [act] runs it, given where in the window a menu it opens should stand.
 */
private class BarTool(
    val label: String,
    val rank: Int,
    val icon: ImageVector? = null,
    /** It opens a menu: drawn with its arrow, listed with an ellipsis when folded. */
    val opens: Boolean = false,
    val tip: String? = null,
    val kbd: String? = null,
    val act: (Offset) -> Unit,
)

/**
 * The editor's bar (the editor's audit, B11): it folds at every width. What does not fit goes into ⋯,
 * the least used first; Back, the name, Export and Present never fold, and Present is a split button —
 * its face presents from this slide, its arrow opens the rest. Before, sixteen controls stood in one row
 * that never wrapped: Present shrank to a bare arrow at 1280 px, and on a phone Module, Style, Export and
 * Present were off the edge of the screen.
 */
@Composable
private fun EditorBar(h: NeueHolders, p: Presentation) {
    val present = h.present
    val neue = h.neue
    val phone = LocalPhone.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val below = with(density) { 36.dp.toPx() }
    fun kbd(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)

    val tools = listOf(
        BarTool("Undo", 2, Icons.Undo, tip = "Undo", kbd = kbd(DeskAction.UNDO)) { present.undo() },
        BarTool("Redo", 2, Icons.Redo, tip = "Redo", kbd = kbd(DeskAction.REDO)) { present.redo() },
        BarTool("Slide", 1, Icons.Plus, opens = true, tip = "A new slide, from a layout") { at ->
            neue.menu = MenuSpec(at, SlideLayouts.all.map { l -> MenuEntry(SlideLayouts.name(l)) { addSlide(h, l) } })
        },
        BarTool("Text", 2) { insertText(h) },
        BarTool("Shape", 3, opens = true) { at ->
            neue.menu = MenuSpec(at, Element.SHAPES.map { k -> MenuEntry(Element.shapeName(k)) { insertShape(h, k) } })
        },
        BarTool("Picture", 3, Icons.Image) { scope.launch { insertPicture(h) } },
        BarTool("Card", 3) { present.pickingCards = PickTarget.NEW },
        BarTool("Insert", 2, opens = true, tip = "Cards in a row, the deck, the camera, a number, a table, a chart, the deck's code") { at ->
            neue.menu = MenuSpec(at, insertMenu(h, p))
        },
        BarTool("Module", 3, Icons.Plus, opens = true, tip = "Slides made from the app's data: siding, matchups, odds…") { at ->
            neue.menu = MenuSpec(at, Modules.all.map { t -> MenuEntry(Modules.name(t)) { present.addingModule = t } })
        },
        BarTool("Style", 2, opens = true, tip = "The look of every slide") { at -> neue.menu = MenuSpec(at, styleMenu(h, p)) },
    ) + if (neue.prefs.ai.enabled) {
        listOf(BarTool("Build with ${h.ai.name}", 4, tip = "${h.ai.name} fills these slides in from the deck, and checks each one") { present.briefing = true })
    } else {
        emptyList()
    }

    // Each tool's width as it will be drawn: its words measured in the bar's own type.
    val measurer = rememberTextMeasurer()
    val fonts = LocalMuFonts.current
    val keep = LocalKeepCase.current
    fun words(s: String): Float = with(density) { measurer.measure(MicroCaps.of(s, keep), MuType.micro(fonts, 11.sp)).size.width.toDp().value }
    val arrowWidth = remember(fonts) { words("→") }
    fun widthOf(t: BarTool): Float = if (t.icon != null && (t.label == "Undo" || t.label == "Redo")) {
        28f
    } else {
        24f + words(t.label) + (if (t.icon != null) 22f else 0f) + (if (t.opens) 8f + arrowWidth else 0f)
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
        val nameWidth = if (phone || maxWidth < 1100.dp) 140f else 220f
        val gap = 4f
        // Back, the name and the space after it; ⋯, Export, Present and its arrow.
        val fixed = 24f + 28f + gap + nameWidth + 8f + gap + 28f + gap + presentWidth(::words) + gap
        val fit = ToolFold.fit(tools.map { ToolFold.Tool(widthOf(it), it.rank) }, maxWidth.value - fixed, gap = gap, more = 28f + gap)
        var moreAt by remember { mutableStateOf(Offset.Zero) }
        fun openMore() {
            neue.menu = MenuSpec(
                Offset(moreAt.x, moreAt.y + below),
                fit.folded.map { i ->
                    val t = tools[i]
                    MenuEntry(if (t.opens) "${t.label}…" else t.label, hint = t.kbd, enabled = toolEnabled(h, t)) { t.act(Offset(moreAt.x, moreAt.y + below)) }
                },
            )
        }
        androidx.compose.runtime.LaunchedEffect(present.moreTools, fit.overflows, moreAt) {
            if (present.moreTools && fit.overflows && moreAt != Offset.Zero) {
                present.moreTools = false
                openMore()
            }
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
        ) {
            Tip("All presentations") { IconButton(Icons.ChevronLeft, { present.close() }, label = "All presentations") }
            MuInput(p.name, { present.rename(it) }, Modifier.width(nameWidth.dp), dense = true)
            Box(Modifier.width(8.dp))
            tools.forEachIndexed { i, t -> if (i in fit.shown) BarButton(h, t, below) }
            Box(Modifier.weight(1f))
            if (fit.overflows) {
                Box(Modifier.onGloballyPositioned { moreAt = it.positionInWindow() }) {
                    Tip("More tools") { IconButton(Icons.More, ::openMore, label = "More tools") }
                }
            }
            ExportButton(h, p, below)
            PresentButton(h, below, ::kbd)
        }
    }
}

/** Present's width: its face and its arrow. */
private fun presentWidth(words: (String) -> Float): Float = 24f + words("Present") + 32f

private fun toolEnabled(h: NeueHolders, t: BarTool): Boolean = when (t.label) {
    "Undo" -> h.present.canUndo
    "Redo" -> h.present.canRedo
    else -> true
}

@Composable
private fun BarButton(h: NeueHolders, t: BarTool, below: Float) {
    var at by remember { mutableStateOf(Offset.Zero) }
    val enabled = toolEnabled(h, t)
    Box(Modifier.onGloballyPositioned { at = it.positionInWindow() }) {
        val button: @Composable () -> Unit = {
            if (t.label == "Undo" || t.label == "Redo") {
                IconButton(t.icon!!, { t.act(Offset(at.x, at.y + below)) }, enabled = enabled, label = t.label, reason = if (t.label == "Undo") "Nothing to undo" else "Nothing to redo")
            } else {
                MuButton(t.label, { t.act(Offset(at.x, at.y + below)) }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = t.icon, arrow = t.opens)
            }
        }
        if (t.tip != null) Tip(t.tip, kbd = t.kbd) { button() } else button()
    }
}

@Composable
private fun ExportButton(h: NeueHolders, p: Presentation, below: Float) {
    val present = h.present
    var exportAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Box(Modifier.onGloballyPositioned { exportAt = it.positionInWindow() }) {
        Tip("Export: a PDF, pictures or a thumbnail") {
            IconButton(Icons.Export, {
                val shown = p.slides.indices.filter { !p.slides[it].hidden }
                h.neue.menu = MenuSpec(
                    Offset(exportAt.x - with(density) { 180.dp.toPx() }, exportAt.y + below),
                    listOf(
                        MenuEntry("PDF of every slide", hint = "${shown.size} pages") { present.exporting = ExportJob(ExportJob.PDF, shown) },
                        MenuEntry("This slide as a picture", hint = "1920 × 1080") { present.exporting = ExportJob(ExportJob.PNG, listOf(present.slideIndex)) },
                        MenuEntry("Every slide as pictures", hint = "a zip") { present.exporting = ExportJob(ExportJob.PNG, shown) },
                        MenuEntry("YouTube thumbnail of this slide", hint = "1280 × 720", separatorBefore = true) { present.exporting = ExportJob(ExportJob.THUMBNAIL, listOf(present.slideIndex)) },
                    ),
                )
            }, label = "Export")
        }
    }
}

/** Present as a split button (the audit's newcomer item 3): the face presents from this slide, the arrow opens the rest. */
@Composable
private fun PresentButton(h: NeueHolders, below: Float, kbd: (DeskAction) -> String?) {
    val present = h.present
    var arrowAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tip("Present from this slide", kbd = kbd(DeskAction.PRESENT_FROM_HERE)) {
            MuButton("Present", { present.present(present.slideIndex) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
        }
        Box(Modifier.onGloballyPositioned { arrowAt = it.positionInWindow() }) {
            Tip("From the start, rehearsing, the presenter view") {
                IconButton(Icons.ArrowDown, {
                    h.neue.menu = MenuSpec(
                        Offset(arrowAt.x - with(density) { 200.dp.toPx() }, arrowAt.y + below),
                        listOf(
                            MenuEntry("From the start", hint = kbd(DeskAction.PRESENT_START)) { present.present(0) },
                            MenuEntry("From this slide", hint = kbd(DeskAction.PRESENT_FROM_HERE)) { present.present(present.slideIndex) },
                            MenuEntry("Rehearse timings", hint = "Keeps each slide's time", separatorBefore = true) { present.present(0, rehearse = true) },
                            MenuEntry(
                                if (present.audience) "Presenter view: on" else "Presenter view: off",
                                hint = if (present.screens > 1) "Slides on the other screen, notes on this one" else "Needs a second screen",
                                enabled = present.screens > 1,
                                reason = "Connect a second screen",
                            ) { present.audience = !present.audience },
                        ),
                    )
                }, size = 32.dp, variant = BtnVariant.SECONDARY, label = "Ways to present")
            }
        }
    }
}

/** Style ▾: the looks, Master UI first (kai, 1.0.72), then Restyle with Ai and its undo — one door to the look. */
private fun styleMenu(h: NeueHolders, p: Presentation): List<MenuEntry> {
    val present = h.present
    return Themes.all.map { t ->
        MenuEntry(
            t.name,
            hint = when {
                t.id == p.theme -> "In use"
                t.id == Themes.MASTER -> "Default"
                else -> null
            },
        ) { present.applyTheme(t.id) }
    } + listOfNotNull(
        present.restyleBefore?.takeIf { it.id == p.id }?.let { MenuEntry("Undo restyle", hint = "the look only; your work since stays", separatorBefore = true) { present.undoRestyle() } },
        if (h.neue.prefs.ai.enabled) MenuEntry("Restyle with ${h.ai.name}…", hint = "describe a look", separatorBefore = present.restyleBefore?.id != p.id) { present.restyling = true } else null,
        MenuEntry("Colours, faces and the camera…", separatorBefore = true) { present.tab = PropsTab.THEME },
    )
}

private fun insertMenu(h: NeueHolders, p: Presentation): List<MenuEntry> {
    val present = h.present
    return listOf(
        MenuEntry("Cards in a row") { present.pickingCards = PickTarget.NEW_ROW },
        MenuEntry("The deck", hint = "a picture of it", enabled = p.deck != null, reason = "This presentation has no deck") { insertElement(h, deckElement()) },
        // The camera is one zone: this moves it on this slide (B6), never draws a second frame.
        MenuEntry("Camera", hint = if (p.webcam.enabled) "move it on this slide" else "turns the webcam on") { pickCamera(h) },
        MenuEntry("Big number", separatorBefore = true) { insertSized(h, Element(newElementId(), Element.STAT, 0f, 0f, 800f, 420f, stat = Placeholders.stat)) },
        MenuEntry("Table") { insertSized(h, Element(newElementId(), Element.TABLE, 0f, 0f, 1200f, 420f, table = Placeholders.table)) },
        MenuEntry("Chart") { insertSized(h, Element(newElementId(), Element.CHART, 0f, 0f, 1200f, 600f, chart = Placeholders.chart)) },
        MenuEntry("Deck code (QR)", hint = "for viewers to scan", enabled = p.deck != null, reason = "This presentation has no deck") {
            val d = p.deck ?: return@MenuEntry
            val code = YdkeCodec.encode(Deck(d.main.map(::cid), d.extra.map(::cid), d.side.map(::cid)))
            insertSized(h, Element(newElementId(), Element.QR, 0f, 0f, 400f, 400f, qr = code))
        },
    )
}

/**
 * The camera on the slide in view, picked to be dragged where it should stand here: the webcam
 * turned on first when it is off (one step of Undo), and moved off the hidden state.
 */
internal fun pickCamera(h: NeueHolders) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    var next = p
    if (!p.webcam.enabled) next = next.copy(webcam = next.webcam.copy(enabled = true))
    if (slide.camera == Slide.CAMERA_HIDDEN) next = PresentEdits.updateSlide(next, slide.id) { it.copy(camera = Slide.CAMERA_DEFAULT) }
    if (next != p) present.commit(next, "Camera")
    present.selection = emptySet()
    present.cameraPicked = true
    present.tab = PropsTab.ELEMENT
    h.neue.note = Note("Drag the camera where it should stand on this slide")
}

private fun cid(i: Int) = CardId(i)

internal fun newElementId(): String = PresentIds.next("e")

private fun deckElement(): Element = Element(newElementId(), Element.DECK, 0f, 0f, 1f, 1f, anchor = Element.ANCHOR_STAGE, focus = DeckFocus(all = true))

/** [e] added to the slide in view, on top, selected. */
internal fun insertElement(h: NeueHolders, e: Element, edit: Boolean = false) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    present.commit(PresentEdits.addElements(p, slide.id, listOf(e)), "Add ${Element.typeName(e.type).lowercase()}")
    present.selection = setOf(e.id)
    present.cameraPicked = false
    present.tab = PropsTab.ELEMENT
    if (edit) present.editingText = e.id
}

/** [e] added at its size, placed on the stage beside the camera (I5). */
internal fun insertSized(h: NeueHolders, e: Element, edit: Boolean = false) {
    val b = insertBox(h, e.w, e.h)
    insertElement(h, e.copy(x = b.x, y = b.y, w = b.w, h = b.h, anchor = Element.ANCHOR_CANVAS), edit)
}

private fun insertText(h: NeueHolders) = insertSized(
    h,
    Element(newElementId(), Element.TEXT, 0f, 0f, 800f, 160f, paras = listOf(Para.of("Text")), role = Element.ROLE_BODY),
    edit = true,
)

private fun insertShape(h: NeueHolders, kind: String) {
    val line = kind == Element.SHAPE_LINE || kind == Element.SHAPE_ARROW_LINE
    insertSized(
        h,
        Element(
            newElementId(), Element.SHAPE, 0f, 0f, 400f, if (line) 20f else 300f,
            shape = kind, corner = if (kind == Element.SHAPE_ROUNDED) 32f else 0f,
            fill = if (line) null else Fill.solid("@accent"),
            stroke = if (line) Stroke("@line", 6f) else null,
        ),
    )
}

/** A picture from a file, kept in the presentation's media, sized to its own shape. */
internal suspend fun insertPicture(h: NeueHolders) {
    val picked = Platform.pick("Add a picture", PICTURE_EXTENSIONS) ?: return
    addPictureBytes(h, picked.bytes, picked.extension)
}

internal suspend fun addPictureBytes(h: NeueHolders, bytes: ByteArray, extension: String) {
    val image = decodePicture(bytes)
    if (image == null) {
        h.neue.note = Note("That picture would not open")
        return
    }
    val name = h.present.putMedia(bytes, extension)
    val aspect = image.width.toFloat() / image.height.coerceAtLeast(1)
    val w = min(900f, 600f * aspect)
    val hh = w / aspect
    insertSized(h, Element(newElementId(), Element.IMAGE, 0f, 0f, w, hh, media = name))
}

internal fun addSlide(h: NeueHolders, layout: String) {
    val present = h.present
    val p = present.open ?: return
    var s = SlideLayouts.slide(layout)
    // A deck slide with no deck to show is a blank slide.
    if (layout == SlideLayouts.DECK && p.deck == null) s = SlideLayouts.slide(SlideLayouts.BLANK)
    present.commit(PresentEdits.addSlide(p, s, present.slideIndex), "New slide")
    present.slideId = s.id
    present.selection = emptySet()
    present.slidesPicked = emptySet()
    if (layout == SlideLayouts.CAMERA_BIG && !p.webcam.enabled) h.neue.note = Note("The webcam is off: turn it on in the Theme tab to give it this room")
}

/** "Slides from groups", asked first when it would replace work: every deck slide goes (I9). */
@Composable
private fun FromGroupsDialog(h: NeueHolders, p: Presentation) {
    val present = h.present
    if (!present.confirmFromGroups) return
    val n = com.kaiharimoto.mastertool.core.present.edit.EditorEdits.deckSlides(p)
    MuDialog(
        "Make the deck slides again?",
        { present.confirmFromGroups = false },
        description = "This replaces the $n deck slides with one for each group, as a new profile has. Their notes, builds and anything added to them go with them; Undo brings them back.",
        footer = {
            MuButton("Keep them", { present.confirmFromGroups = false }, variant = BtnVariant.GHOST)
            MuButton("Make them again", {
                present.confirmFromGroups = false
                present.open?.let { o -> present.commit(PresentEdits.stepsFromGroups(o), "Deck slides from groups") }
            }, variant = BtnVariant.PRIMARY)
        },
    ) {}
}

// ---- the sorter --------------------------------------------------------------------------

/** The keys held at the last press on a row: a click reads them, since a click itself carries none. */
private class PressMods {
    var mods: PointerKeyboardModifiers? = null
    var finger = false
}

@Composable
private fun SlideSorter(h: NeueHolders, p: Presentation, ctx: SlideContext, show: CompiledShow, modifier: Modifier) {
    val present = h.present
    val c = Mu.colors
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }
    var listHeight by remember { mutableStateOf(1f) }
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.toPx() }
    val picked = present.pickedSlides.toSet()

    /** A drag of row [s] by [by] rows: the picked slides when it is one of them, else it alone. */
    fun drop(s: Slide) {
        val by = (dragY / (rowHeight + gapPx)).roundToInt()
        val o = present.open ?: return
        val from = o.indexOf(s.id)
        if (by != 0) {
            val ids = if (s.id in picked && picked.size > 1) o.slides.map { it.id }.filter { it in picked } else listOf(s.id)
            present.commit(PresentEdits.moveSlides(o, ids, (from + by + if (by > 0) 1 else 0).coerceIn(0, o.slides.size)), if (ids.size == 1) "Move slide" else "Move ${ids.size} slides")
        }
        dragging = null
        dragY = 0f
    }

    Box(modifier.onGloballyPositioned { listHeight = it.size.height.toFloat() }) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(p.slides, key = { _, s -> s.id }) { i, s ->
                val current = s.id == present.slide?.id
                val isPicked = s.id in picked && picked.size > 1
                val source = remember { MutableInteractionSource() }
                val held = remember { PressMods() }
                SectionHeading(h, p, s)
                Row(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { rowHeight = it.size.height.toFloat() }
                        .graphicsLayer { if (dragging == s.id || dragging != null && isPicked && dragging in picked) { translationY = dragY; alpha = 0.85f } }
                        .hoverable(source)
                        .cursorPointer(label = "Slide ${i + 1}")
                        .onContextMenu { at ->
                            present.sorterFocused = true
                            h.neue.menu = MenuSpec(at, slideMenu(h, p, s))
                        }
                        // What the press held (Shift, Ctrl, a finger), read by the click.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    if (e.type == PointerEventType.Press) {
                                        held.mods = e.keyboardModifiers
                                        held.finger = e.changes.any { it.byFinger }
                                    }
                                }
                            }
                        }
                        // A mouse drags a row anywhere; a finger only by its number, so it can scroll the list (R2).
                        .pointerInput(s.id, p.slides.size) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.byFinger) return@awaitEachGesture
                                rowDrag(down.id, viewConfiguration.touchSlop, { dragging = s.id; dragY = 0f }, { dy ->
                                    dragY += dy + edgeScroll(list, s.id, down.position.y, dragY, rowHeight, listHeight)
                                }) { drop(s) }
                            }
                        }
                        .muClickable(interactionSource = source) {
                            val mods = held.mods
                            val press = PresentPress(
                                PresentTarget.SORTER, finger = held.finger,
                                shift = mods?.isShiftPressed == true, ctrl = mods?.let { it.isCtrlPressed || it.isMetaPressed } == true,
                            )
                            if (PresentGestures.classify(press) != PresentAction.OPEN_SLIDE) return@muClickable
                            openSlide(h, p, s, range = press.shift, toggle = press.ctrl || press.finger && present.selectSeveral)
                        },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The number is the row's grip: a finger drags it from here.
                    Box(
                        Modifier.width(18.dp)
                            .cursorPointer(caption = "Drag to move")
                            .pointerInput(s.id, p.slides.size) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!down.byFinger) return@awaitEachGesture
                                    down.consume()
                                    rowDrag(down.id, viewConfiguration.touchSlop, { dragging = s.id; dragY = 0f }, { dy ->
                                        dragY += dy + edgeScroll(list, s.id, down.position.y, dragY, rowHeight, listHeight)
                                    }) { drop(s) }
                                }
                            },
                    ) {
                        Mono("${i + 1}".padStart(2, '0'), color = if (current) c.ink else c.ink45)
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(16f / 9f)
                            .border(if (current || isPicked) 2.dp else 1.dp, if (current || isPicked) c.ink else c.ink25)
                            .graphicsLayer { alpha = if (s.hidden) 0.4f else 1f },
                    ) {
                        SlideView(ctx, s, show.zone(i), show.stage(i), Modifier.fillMaxSize(), deck = if (s.deck != null) ({ show.deckFrame(i) }) else null, deckKeys = sorterKeys(p, show, i))
                        if (s.durationMs != null) Mono("%d:%02d".format(s.durationMs!! / 60000, s.durationMs!! / 1000 % 60), Modifier.align(Alignment.BottomEnd).background(c.paper).padding(horizontal = 3.dp), color = c.ink70)
                        if (isPicked) {
                            val nth = p.slides.map { it.id }.filter { it in picked }.indexOf(s.id) + 1
                            Box(Modifier.align(Alignment.TopEnd).background(c.ink).padding(horizontal = 4.dp)) { Mono("$nth/${picked.size}", color = c.paper) }
                        }
                    }
                }
            }
            item {
                MuButton("Slide", { addSlide(h, SlideLayouts.TITLE_BODY) }, Modifier.fillMaxWidth(), size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
            }
        }
    }
}

/** A press on a slide in the list: it opens, or with Shift a run and with Ctrl one more is picked (M3). */
private fun openSlide(h: NeueHolders, p: Presentation, s: Slide, range: Boolean, toggle: Boolean) {
    val present = h.present
    val current = present.slide?.id ?: s.id
    val next = SlidePicks(current, present.slidesPicked).click(p.slides.map { it.id }, s.id, range, toggle)
    present.slideId = next.current
    present.slidesPicked = next.picked
    present.selection = emptySet()
    present.editingText = null
    present.sorterFocused = true
}

/**
 * A row carried by the pointer [id] once it moves past [slop]: [start], each move's [by] (pixels down),
 * then [end] as it is let go. Moves are spent so the list does not scroll under the carry.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.rowDrag(
    id: androidx.compose.ui.input.pointer.PointerId,
    slop: Float,
    start: () -> Unit,
    by: (Float) -> Unit,
    end: () -> Unit,
) {
    var total = 0f
    var carrying = false
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.id == id } ?: break
        if (!ch.pressed) break
        val dy = ch.position.y - ch.previousPosition.y
        total += dy
        if (!carrying && abs(total) > slop) {
            carrying = true
            start()
            by(total)
            ch.consume()
            continue
        }
        if (carrying) {
            by(dy)
            ch.consume()
        }
    }
    if (carrying) end()
}

/**
 * The list scrolled when a carried row nears its top or bottom edge (R2), and by how much — added to the
 * carry, so the row stays under the pointer while the list moves under it.
 */
private fun edgeScroll(list: androidx.compose.foundation.lazy.LazyListState, key: String, inRow: Float, dragY: Float, rowHeight: Float, listHeight: Float): Float {
    val itemTop = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: return 0f
    // Where the pointer is in the list's own window.
    val y = itemTop + dragY + inRow
    val edge = rowHeight * 0.6f
    val step = when {
        y < edge -> -rowHeight * 0.15f
        y > listHeight - edge -> rowHeight * 0.15f
        else -> 0f
    }
    if (step == 0f) return 0f
    return list.dispatchRawDelta(step)
}

/** A slide's section heading, named where it stands (M6): a click renames it in place. */
@Composable
private fun SectionHeading(h: NeueHolders, p: Presentation, s: Slide) {
    val present = h.present
    val c = Mu.colors
    val name = s.section ?: return
    if (present.namingSection == s.id) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        // Let go only after it had the focus: the field reports "not focused" once as it first appears.
        var had by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        MuInput(
            name,
            { v -> present.open?.let { o -> present.commit(PresentEdits.updateSlide(o, s.id) { it.copy(section = v) }, "Name the section", coalesce = "section-${s.id}") } },
            Modifier.fillMaxWidth().padding(top = 4.dp),
            dense = true,
            placeholder = "Section name",
            focusRequester = focus,
            onFocusChange = { focused ->
                if (focused) had = true
                else if (had && present.namingSection == s.id) present.namingSection = null
            },
            onSubmit = { present.namingSection = null; present.seal() },
        )
    } else {
        Box(
            Modifier.padding(top = 6.dp, bottom = 2.dp)
                .cursorPointer(caption = "Rename")
                .muClickable { present.namingSection = s.id },
        ) {
            Micro(name.ifBlank { "Untitled section" }, color = c.ink70)
        }
    }
}

/** The copies a thumbnail of deck slide [i] draws: only those its frame shows, so a picture costs no more than it holds. */
private fun sorterKeys(p: Presentation, show: CompiledShow, i: Int): List<String> =
    if (p.slides.getOrNull(i)?.deck == null) emptyList() else show.deckFrame(i)?.cards?.map { it.key }.orEmpty()

private fun slideMenu(h: NeueHolders, p: Presentation, s: Slide): List<MenuEntry> {
    val present = h.present
    // The menu acts on every picked slide when it opened on one of them, else on this one alone.
    val group = present.pickedSlides.takeIf { s.id in it && it.size > 1 } ?: listOf(s.id)
    val many = group.size > 1
    val n = group.size
    return listOf(
        MenuEntry("New slide after") { present.slideId = s.id; addSlide(h, SlideLayouts.TITLE_BODY) },
        MenuEntry(if (many) "Duplicate $n slides" else "Duplicate") {
            present.commit(PresentEdits.duplicateSlides(p, group.toSet()), if (many) "Duplicate $n slides" else "Duplicate slide")
            present.slidesPicked = emptySet()
        },
        MenuEntry(if (many) "Copy $n slides" else "Copy", hint = "Ctrl C") {
            if (!many) present.slideId = s.id
            present.selection = emptySet()
            copy(h)
        },
        MenuEntry(if (s.hidden) "Show in the presentation" else "Skip when presenting") {
            val hide = !s.hidden
            present.commit(p.copy(slides = p.slides.map { if (it.id in group) it.copy(hidden = hide) else it }), if (hide) "Hide slide" else "Show slide")
        },
        if (s.section == null) {
            MenuEntry("Start a section here", hint = "and name it") {
                present.commit(PresentEdits.updateSlide(p, s.id) { it.copy(section = "New section") }, "Section")
                present.namingSection = s.id
            }
        } else {
            MenuEntry("Rename the section") { present.namingSection = s.id }
        },
        if (s.section != null) MenuEntry("End the section here") { present.commit(PresentEdits.updateSlide(p, s.id) { it.copy(section = null) }, "Section") } else null,
        MenuEntry("Present from here", separatorBefore = true) { present.present(p.indexOf(s.id)) },
        MenuEntry(if (many) "Delete $n slides" else "Delete slide", danger = true, separatorBefore = true, enabled = group.size < p.slides.size, reason = "A presentation keeps one slide") {
            if (!many) {
                present.slideId = s.id
                present.slidesPicked = emptySet()
            }
            deleteSlides(h)
        },
    ).filterNotNull()
}

// ---- the notes ---------------------------------------------------------------------------

@Composable
private fun NotesPane(h: NeueHolders, p: Presentation, slide: Slide, modifier: Modifier) {
    val present = h.present
    val c = Mu.colors
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro(if (LocalPhone.current) "Speaker notes" else "Speaker notes · only you see them", Modifier.weight(1f), color = c.ink45)
            ReadyLine(h, p)
        }
        NotesField(slide.notes, { text ->
            val o = present.open ?: return@NotesField
            present.commit(PresentEdits.updateSlide(o, slide.id) { it.copy(notes = text) }, "Notes", coalesce = "notes-${slide.id}")
        }, "What to say on this slide. Shown while you present (S) and in the presenter view, never on the slide.")
    }
}

/**
 * What comes next, in a line (the audit's newcomer item 2): how many slides have speaker notes, and
 * whether the show was rehearsed. Each part jumps to what it names.
 */
@Composable
private fun ReadyLine(h: NeueHolders, p: Presentation) {
    val present = h.present
    val shown = p.slides.filter { !it.hidden }
    if (shown.isEmpty()) return
    val noted = shown.count { it.notes.isNotBlank() }
    val rehearsed = shown.any { it.durationMs != null }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MicroLink("Notes on $noted of ${shown.size}", {
            shown.firstOrNull { it.notes.isBlank() }?.let { present.slideId = it.id; present.selection = emptySet() }
        })
        MicroLink(if (rehearsed) "Rehearsed" else "Not rehearsed", { present.present(0, rehearse = true) })
    }
}
