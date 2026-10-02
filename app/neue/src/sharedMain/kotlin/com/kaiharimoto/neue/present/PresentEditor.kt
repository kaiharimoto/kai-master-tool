package com.kaiharimoto.neue.present

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.present.Chart
import com.kaiharimoto.mastertool.core.present.ChartSeries
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Fill
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.Stat
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.Box as CanvasBox
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.pages.NotesField
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch
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
            EditorBar(h, p, ctx)
            MuButton("Add a slide", { addSlide(h, SlideLayouts.TITLE_BODY) }, variant = BtnVariant.PRIMARY, icon = Icons.Plus)
        }
        return
    }
    val c = Mu.colors
    val phone = com.kaiharimoto.neue.kit.LocalPhone.current
    Column(Modifier.fillMaxSize()) {
        EditorBar(h, p, ctx)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            SlideSorter(h, p, ctx, show, Modifier.width(if (phone) 132.dp else 196.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SlideCanvas(h, p, slide, ctx, show, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
                NotesPane(h, slide, Modifier.fillMaxWidth().height(if (phone) 72.dp else 112.dp))
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
}

// ---- the bar -----------------------------------------------------------------------------

@Composable
private fun EditorBar(h: NeueHolders, p: Presentation, ctx: SlideContext) {
    val present = h.present
    val neue = h.neue
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    var insertAt by remember { mutableStateOf(Offset.Zero) }
    var shapeAt by remember { mutableStateOf(Offset.Zero) }
    var layoutAt by remember { mutableStateOf(Offset.Zero) }
    var presentAt by remember { mutableStateOf(Offset.Zero) }
    var moduleAt by remember { mutableStateOf(Offset.Zero) }
    var exportAt by remember { mutableStateOf(Offset.Zero) }
    var styleAt by remember { mutableStateOf(Offset.Zero) }
    fun kbd(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tip("All presentations") { IconButton(Icons.ChevronLeft, { present.close() }, label = "All presentations") }
        MuInput(p.name, { present.rename(it) }, Modifier.width(220.dp), dense = true)
        Box(Modifier.width(8.dp))
        Tip("Undo", kbd = kbd(DeskAction.UNDO)) { IconButton(Icons.Undo, { present.undo() }, enabled = present.canUndo, label = "Undo", reason = "Nothing to undo") }
        Tip("Redo", kbd = kbd(DeskAction.REDO)) { IconButton(Icons.Redo, { present.redo() }, enabled = present.canRedo, label = "Redo", reason = "Nothing to redo") }
        Box(Modifier.width(1.dp).height(24.dp).background(c.ink12))
        Box(Modifier.onGloballyPositioned { layoutAt = it.positionInWindow() }) {
            MuButton("Slide", {
                neue.menu = MenuSpec(Offset(layoutAt.x, layoutAt.y + 36f), SlideLayouts.all.map { l -> MenuEntry(SlideLayouts.name(l)) { addSlide(h, l) } })
            }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
        }
        MuButton("Text", { insertText(h) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        Box(Modifier.onGloballyPositioned { shapeAt = it.positionInWindow() }) {
            MuButton("Shape", {
                neue.menu = MenuSpec(Offset(shapeAt.x, shapeAt.y + 36f), Element.SHAPES.map { k -> MenuEntry(Element.shapeName(k)) { insertShape(h, k) } })
            }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
        MuButton("Picture", { scope.launch { insertPicture(h) } }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Image)
        MuButton("Card", { present.pickingCards = PickTarget.NEW }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        Box(Modifier.onGloballyPositioned { insertAt = it.positionInWindow() }) {
            MuButton("More", {
                neue.menu = MenuSpec(Offset(insertAt.x, insertAt.y + 36f), insertMenu(h, p))
            }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.More)
        }
        Box(Modifier.onGloballyPositioned { moduleAt = it.positionInWindow() }) {
            MuButton("Module", {
                neue.menu = MenuSpec(
                    Offset(moduleAt.x, moduleAt.y + 36f),
                    com.kaiharimoto.mastertool.core.present.modules.Modules.all.map { t ->
                        MenuEntry(com.kaiharimoto.mastertool.core.present.modules.Modules.name(t)) { present.addingModule = t }
                    },
                )
            }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
        }
        Box(Modifier.onGloballyPositioned { styleAt = it.positionInWindow() }) {
            MuButton("Style", {
                // Master UI first: the default look; the others are there when wanted (kai, 1.0.72).
                neue.menu = MenuSpec(
                    Offset(styleAt.x, styleAt.y + 36f),
                    com.kaiharimoto.mastertool.core.present.Themes.all.map { t ->
                        MenuEntry(
                            t.name,
                            hint = when {
                                t.id == p.theme -> "In use"
                                t.id == com.kaiharimoto.mastertool.core.present.Themes.MASTER -> "Default"
                                else -> null
                            },
                        ) { present.applyTheme(t.id) }
                    } + listOfNotNull(
                        present.restyleBefore?.takeIf { it.id == p.id }?.let { MenuEntry("Undo restyle", separatorBefore = true) { present.undoRestyle() } },
                        if (neue.prefs.ai.enabled) MenuEntry("Restyle with ${h.ai.name}…", separatorBefore = present.restyleBefore?.id != p.id) { present.restyling = true } else null,
                    ),
                )
            }, size = BtnSize.SM, variant = BtnVariant.GHOST, arrow = true)
        }
        Box(Modifier.weight(1f))
        if (neue.prefs.ai.enabled) {
            Tip("Describe a look and ${h.ai.name} restyles the slides; the words and cards stay") {
                MuButton("Restyle", { present.restyling = true }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            Tip("${h.ai.name} fills these slides in from the deck, and checks each one") {
                MuButton("Build with ${h.ai.name}", { present.briefing = true }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
        Box(Modifier.onGloballyPositioned { exportAt = it.positionInWindow() }) {
            Tip("Export") {
                IconButton(Icons.Export, {
                    val shown = p.slides.indices.filter { !p.slides[it].hidden }
                    neue.menu = MenuSpec(
                        Offset(exportAt.x - 160f, exportAt.y + 36f),
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
        Box(Modifier.onGloballyPositioned { presentAt = it.positionInWindow() }) {
            MuButton("Present", {
                neue.menu = MenuSpec(
                    Offset(presentAt.x - 120f, presentAt.y + 36f),
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
            }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, arrow = true)
        }
    }
}

private fun insertMenu(h: NeueHolders, p: Presentation): List<MenuEntry> = listOf(
    MenuEntry("Cards in a row") { h.present.pickingCards = PickTarget.NEW_ROW },
    MenuEntry("The deck", hint = "a picture of it", enabled = p.deck != null, reason = "This presentation has no deck") { insertElement(h, deckElement()) },
    MenuEntry("Camera", hint = "where the webcam stands on this slide") {
        insertElement(h, Element(newElementId(), Element.CAMERA, 1200f, 520f, 620f, 349f))
    },
    MenuEntry("Big number", separatorBefore = true) { insertElement(h, Element(newElementId(), Element.STAT, 560f, 300f, 800f, 420f, stat = Stat("87%", "to open a starter", "going first"))) },
    MenuEntry("Table") {
        insertElement(h, Element(newElementId(), Element.TABLE, 360f, 280f, 1200f, 420f, table = listOf(listOf("Matchup", "Going first", "Going second"), listOf("Opponent A", "60%", "45%"), listOf("Opponent B", "55%", "50%"))))
    },
    MenuEntry("Chart") {
        insertElement(h, Element(newElementId(), Element.CHART, 360f, 260f, 1200f, 600f, chart = Chart(Chart.COLUMN, listOf("A", "B", "C"), listOf(ChartSeries("Win rate", listOf(60f, 45f, 52f))), max = 100f, percent = true)))
    },
    MenuEntry("Deck code (QR)", hint = "for viewers to scan", enabled = p.deck != null, reason = "This presentation has no deck") {
        val d = p.deck ?: return@MenuEntry
        val code = YdkeCodec.encode(com.kaiharimoto.mastertool.core.model.Deck(d.main.map(::cid), d.extra.map(::cid), d.side.map(::cid)))
        insertElement(h, Element(newElementId(), Element.QR, 760f, 240f, 400f, 400f, qr = code))
    },
)

private fun cid(i: Int) = com.kaiharimoto.mastertool.core.model.CardId(i)

internal fun newElementId(): String = com.kaiharimoto.mastertool.core.present.PresentIds.next("e")

private fun deckElement(): Element = Element(newElementId(), Element.DECK, 0f, 0f, 1f, 1f, anchor = Element.ANCHOR_STAGE, focus = DeckFocus(all = true))

/** [e] added to the slide in view, on top, selected. */
internal fun insertElement(h: NeueHolders, e: Element, edit: Boolean = false) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    present.commit(PresentEdits.addElements(p, slide.id, listOf(e)), "Add ${Element.typeName(e.type).lowercase()}")
    present.selection = setOf(e.id)
    present.tab = PropsTab.ELEMENT
    if (edit) present.editingText = e.id
}

private fun insertText(h: NeueHolders) = insertElement(
    h,
    Element(newElementId(), Element.TEXT, 560f, 440f, 800f, 160f, paras = listOf(Para.of("Text")), role = Element.ROLE_BODY),
    edit = true,
)

private fun insertShape(h: NeueHolders, kind: String) {
    val line = kind == Element.SHAPE_LINE || kind == Element.SHAPE_ARROW_LINE
    insertElement(
        h,
        Element(
            newElementId(), Element.SHAPE, 760f, if (line) 530f else 390f, 400f, if (line) 20f else 300f,
            shape = kind, corner = if (kind == Element.SHAPE_ROUNDED) 32f else 0f,
            fill = if (line) null else Fill.solid("@accent"),
            stroke = if (line) com.kaiharimoto.mastertool.core.present.Stroke("@line", 6f) else null,
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
    insertElement(h, Element(newElementId(), Element.IMAGE, (Presentation.WIDTH - w) / 2f, (Presentation.HEIGHT - hh) / 2f, w, hh, media = name))
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
}

// ---- the sorter --------------------------------------------------------------------------

@Composable
private fun SlideSorter(h: NeueHolders, p: Presentation, ctx: SlideContext, show: CompiledShow, modifier: Modifier) {
    val present = h.present
    val c = Mu.colors
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(1f) }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(p.slides, key = { _, s -> s.id }) { i, s ->
                val current = s.id == present.slide?.id
                val picked = s.id in present.slidesPicked
                val source = remember { MutableInteractionSource() }
                s.section?.let { Micro(it, Modifier.padding(top = 6.dp, bottom = 2.dp), color = c.ink70) }
                Row(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { rowHeight = it.size.height.toFloat() }
                        .graphicsLayer { if (dragging == s.id) { translationY = dragY; alpha = 0.85f } }
                        .hoverable(source)
                        .cursorPointer(label = "Slide ${i + 1}")
                        .onContextMenu { at -> h.neue.menu = MenuSpec(at + Offset(0f, 0f), slideMenu(h, p, s)) }
                        .pointerInput(s.id, p.slides.size) {
                            detectDragGestures(
                                onDragStart = { dragging = s.id; dragY = 0f },
                                onDragEnd = {
                                    val by = (dragY / (rowHeight + 8f)).roundToInt()
                                    val from = p.indexOf(s.id)
                                    if (by != 0) present.commit(PresentEdits.moveSlides(p, listOf(s.id), (from + by + if (by > 0) 1 else 0).coerceIn(0, p.slides.size)), "Move slide")
                                    dragging = null
                                    dragY = 0f
                                },
                                onDragCancel = { dragging = null; dragY = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragY += amount.y
                            }
                        }
                        .muClickable(interactionSource = source) {
                            present.slideId = s.id
                            present.selection = emptySet()
                            present.editingText = null
                            present.slidesPicked = emptySet()
                        },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Mono("${i + 1}".padStart(2, '0'), Modifier.width(18.dp), color = if (current) c.ink else c.ink45)
                    Box(
                        Modifier.weight(1f).aspectRatio(16f / 9f)
                            .border(if (current) 2.dp else 1.dp, if (current || picked) c.ink else c.ink25)
                            .graphicsLayer { alpha = if (s.hidden) 0.4f else 1f },
                    ) {
                        SlideView(ctx, s, show.zone(i), show.stage(i), Modifier.fillMaxSize(), deck = if (s.deck != null) ({ show.deckFrame(i) }) else null, deckKeys = sorterKeys(p, show, i))
                        if (s.durationMs != null) Mono("%d:%02d".format(s.durationMs!! / 60000, s.durationMs!! / 1000 % 60), Modifier.align(Alignment.BottomEnd).background(c.paper).padding(horizontal = 3.dp), color = c.ink70)
                    }
                }
            }
            item {
                MuButton("Slide", { addSlide(h, SlideLayouts.TITLE_BODY) }, Modifier.fillMaxWidth(), size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
            }
        }
    }
}

/** The copies a thumbnail of deck slide [i] draws: only those its frame shows, so a picture costs no more than it holds. */
private fun sorterKeys(p: Presentation, show: CompiledShow, i: Int): List<String> =
    if (p.slides.getOrNull(i)?.deck == null) emptyList() else show.deckFrame(i)?.cards?.map { it.key }.orEmpty()

private fun slideMenu(h: NeueHolders, p: Presentation, s: Slide): List<MenuEntry> {
    val present = h.present
    return listOf(
        MenuEntry("New slide after") { present.slideId = s.id; addSlide(h, SlideLayouts.TITLE_BODY) },
        MenuEntry("Duplicate") { present.commit(PresentEdits.duplicateSlides(p, setOf(s.id)), "Duplicate slide") },
        MenuEntry(if (s.hidden) "Show in the presentation" else "Skip when presenting") { present.commit(PresentEdits.updateSlide(p, s.id) { it.copy(hidden = !it.hidden) }, "Hide slide") },
        MenuEntry(if (s.section == null) "Start a section here" else "End the section here") {
            present.commit(PresentEdits.updateSlide(p, s.id) { it.copy(section = if (it.section == null) "Section" else null) }, "Section")
        },
        MenuEntry("Present from here", separatorBefore = true) { present.present(p.indexOf(s.id)) },
        MenuEntry("Delete slide", danger = true, separatorBefore = true, enabled = p.slides.size > 1, reason = "A presentation keeps one slide") {
            present.commit(PresentEdits.removeSlides(p, setOf(s.id)), "Delete slide")
        },
    )
}

// ---- the notes ---------------------------------------------------------------------------

@Composable
private fun NotesPane(h: NeueHolders, slide: Slide, modifier: Modifier) {
    val present = h.present
    val c = Mu.colors
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Micro("Speaker notes", color = c.ink45)
        NotesField(slide.notes, { text ->
            val p = present.open ?: return@NotesField
            present.commit(PresentEdits.updateSlide(p, slide.id) { it.copy(notes = text) }, "Notes", coalesce = "notes-${slide.id}")
        }, "What to say on this slide. Shown while you present (S) and in the presenter view.")
    }
}
