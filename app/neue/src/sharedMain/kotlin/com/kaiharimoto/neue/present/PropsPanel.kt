package com.kaiharimoto.neue.present

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.present.Anim
import com.kaiharimoto.mastertool.core.present.Chart
import com.kaiharimoto.mastertool.core.present.ChartSeries
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Fill
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.Shadow
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideFonts
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.Stat
import com.kaiharimoto.mastertool.core.present.Stroke
import com.kaiharimoto.mastertool.core.present.Theme
import com.kaiharimoto.mastertool.core.present.ThemeOverride
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.Transition
import com.kaiharimoto.mastertool.core.present.Placeholders
import com.kaiharimoto.mastertool.core.present.edit.Align
import com.kaiharimoto.mastertool.core.present.edit.EditorEdits
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSlider
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.MuTabs
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.pages.NotesField
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.present.paint.ColorField
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.ThemeSwatch
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The right of the editor (1.0.70): the slide (layout, background, transition, camera), the
 * selected element (where, how it looks, its words), its builds in order, the deck's step on a
 * deck slide (what it talks about and the note), and the theme with the webcam's zone.
 */
@Composable
internal fun PropsPanel(h: NeueHolders, p: Presentation, slide: Slide, ctx: SlideContext, modifier: Modifier) {
    val present = h.present
    val scroll = rememberScrollState()
    val tabs = PropsTab.entries
    Column(modifier) {
        // The tabs scroll sideways where the panel is narrow (B11: on a phone Theme was off its edge).
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            MuTabs(present.tab, tabs, { it.title }, { present.tab = it })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (present.tab) {
                    PropsTab.SLIDE -> SlideProps(h, p, slide, ctx)
                    PropsTab.ELEMENT -> ElementProps(h, p, slide, ctx)
                    PropsTab.ANIMATE -> AnimateProps(h, p, slide)
                    PropsTab.DECK -> DeckProps(h, p, slide, ctx)
                    PropsTab.THEME -> ThemeProps(h, p, ctx)
                }
            }
            ScrollbarFor(scroll)
        }
    }
}

private fun commitSlide(h: NeueHolders, slide: Slide, label: String, coalesce: String? = null, change: (Slide) -> Slide) {
    val p = h.present.open ?: return
    h.present.commit(PresentEdits.updateSlide(p, slide.id, change), label, coalesce)
}

private fun commitElements(h: NeueHolders, slide: Slide, ids: Set<String>, label: String, coalesce: String? = null, change: (Element) -> Element) {
    val p = h.present.open ?: return
    h.present.commit(PresentEdits.updateElements(p, slide.id, ids, change), label, coalesce)
}

// ---- the slide ---------------------------------------------------------------------------

@Composable
private fun SlideProps(h: NeueHolders, p: Presentation, slide: Slide, ctx: SlideContext) {
    val scope = rememberCoroutineScope()
    slide.module?.let { ref ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("Made by ${com.kaiharimoto.mastertool.core.present.modules.Modules.name(ref.type).lowercase()}", Modifier.weight(1f))
            MuButton("Refresh", {
                scope.launch {
                    val o = h.present.open ?: return@launch
                    val fresh = ModuleData.refresh(h, o, slide) ?: return@launch
                    h.present.commit(PresentEdits.updateSlide(o, slide.id) { fresh }, "Refresh ${com.kaiharimoto.mastertool.core.present.modules.Modules.name(ref.type).lowercase()}")
                }
            }, size = BtnSize.SM, icon = Icons.Refresh)
        }
        Help("Refresh makes it again from the newest data; what you changed by hand stays.")
    }
    FieldLabel("Title", hint = "for the list and the notes")
    MuInput(slide.title, { t -> commitSlide(h, slide, "Title", "title-${slide.id}") { it.copy(title = t) } }, Modifier.fillMaxWidth(), dense = true, placeholder = "Untitled")
    FieldLabel("Background")
    val kinds = listOf("THEME", Fill.SOLID, Fill.LINEAR, Fill.RADIAL, "PICTURE")
    val current = slide.background
    val kind = when {
        current == null -> "THEME"
        current.media != null -> "PICTURE"
        else -> current.kind
    }
    MuSelect(kind, kinds, { when (it) { "THEME" -> "The theme's"; Fill.SOLID -> "Colour"; Fill.LINEAR -> "Gradient"; Fill.RADIAL -> "Radial gradient"; else -> "Picture" } }, { k ->
        when (k) {
            "THEME" -> commitSlide(h, slide, "Background") { it.copy(background = null) }
            "PICTURE" -> scope.launch {
                val picked = Platform.pick("Background picture", PICTURE_EXTENSIONS) ?: return@launch
                val name = h.present.putMedia(picked.bytes, picked.extension)
                commitSlide(h, slide, "Background") { it.copy(background = Fill(Fill.SOLID, "@bg", media = name, scrim = 0.35f)) }
            }
            else -> commitSlide(h, slide, "Background") { it.copy(background = Fill(k, "@bg", stops = if (k == Fill.SOLID) emptyList() else listOf("@bg", "@accent"))) }
        }
    }, Modifier.fillMaxWidth(), small = true)
    val bg = slide.background
    if (bg != null && bg.media == null) {
        if (bg.kind == Fill.SOLID) {
            ColorField(bg.color, ctx.theme, { v -> commitSlide(h, slide, "Background") { it.copy(background = bg.copy(color = v ?: "@bg")) } }, Modifier.fillMaxWidth())
        } else {
            val stops = bg.stops.ifEmpty { listOf("@bg", "@accent") }
            stops.forEachIndexed { i, stop ->
                ColorField(stop, ctx.theme, { v -> commitSlide(h, slide, "Background") { it.copy(background = bg.copy(stops = stops.toMutableList().also { l -> l[i] = v ?: "@bg" })) } }, Modifier.fillMaxWidth(), label = "Stop ${i + 1}")
            }
            if (bg.kind == Fill.LINEAR) {
                Small("Angle ${bg.angle.roundToInt()}°")
                MuSlider(bg.angle, { a -> commitSlide(h, slide, "Background", "angle") { it.copy(background = bg.copy(angle = a)) } }, Modifier.fillMaxWidth(), 0f..360f, name = "Angle")
            }
        }
    }
    if (bg?.media != null) {
        Small("Darken it ${(bg.scrim * 100).roundToInt()}%")
        MuSlider(bg.scrim, { v -> commitSlide(h, slide, "Background", "scrim") { it.copy(background = bg.copy(scrim = v)) } }, Modifier.fillMaxWidth(), 0f..0.9f, name = "Darken")
    }
    FieldLabel("Transition", hint = "how this slide arrives")
    // Morph drew a fade, so it is offered as what it is (M7); a slide that has it shows Fade.
    val arrives = slide.transition.kind.let { if (it in Transition.OFFERED) it else Transition.FADE }
    MuSelect(arrives, Transition.OFFERED, Transition::kindName, { k -> commitSlide(h, slide, "Transition") { it.copy(transition = it.transition.copy(kind = k)) } }, Modifier.fillMaxWidth(), small = true)
    if (p.slides.getOrNull(p.indexOf(slide.id) - 1)?.deck != null && slide.deck != null) {
        Help("Between two deck slides the deck itself glides, whatever the transition.")
    }
    if (slide.transition.kind == Transition.PUSH || slide.transition.kind == Transition.COVER) {
        Segmented(slide.transition.direction, listOf(Transition.LEFT, Transition.RIGHT, Transition.UP, Transition.DOWN), { it.lowercase().replaceFirstChar(Char::uppercase) }, { d ->
            commitSlide(h, slide, "Transition") { it.copy(transition = it.transition.copy(direction = d)) }
        }, small = true)
    }
    Small("${slide.transition.durationMs} ms")
    MuSlider(slide.transition.durationMs.toFloat(), { v -> commitSlide(h, slide, "Transition", "dur") { it.copy(transition = it.transition.copy(durationMs = v.roundToInt())) } }, Modifier.fillMaxWidth(), 150f..1500f, name = "Length")
    MuButton("Use on every slide", { val p2 = h.present.open ?: return@MuButton; h.present.commit(p2.copy(slides = p2.slides.map { it.copy(transition = slide.transition) }), "Transition everywhere") }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    SlideCameraField(h, p, slide)
    FieldLabel("Section", hint = "a heading in the list")
    if (slide.section == null) {
        MuButton("Start a section here", {
            commitSlide(h, slide, "Section") { it.copy(section = "New section") }
            h.present.namingSection = slide.id
        }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MuInput(slide.section.orEmpty(), { v -> commitSlide(h, slide, "Name the section", "section-${slide.id}") { it.copy(section = v) } }, Modifier.weight(1f), dense = true, placeholder = "Section name")
            IconButton(Icons.X, { commitSlide(h, slide, "Section") { it.copy(section = null) } }, label = "End the section")
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuSwitch(slide.hidden, { v -> commitSlide(h, slide, "Hide slide") { it.copy(hidden = v) } })
        Small("Skip when presenting")
    }
    slide.durationMs?.let { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Small("Rehearsed: %d:%02d".format(d / 60000, d / 1000 % 60), Modifier.weight(1f))
            MuButton("Clear", { commitSlide(h, slide, "Timing") { it.copy(durationMs = null) } }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
    }
}

/** The camera's promise, said plainly until it can be kept (the audit's newcomer item 1). */
internal const val CAMERA_NOTE = "Your camera goes here when you record. Recording is coming; for now choose Green screen in the Theme tab and record with OBS."

/** Where the camera stands on this slide: the presentation's place, hidden, a corner, or a box of its own (B6). */
@Composable
private fun SlideCameraField(h: NeueHolders, p: Presentation, slide: Slide) {
    FieldLabel("Camera on this slide")
    if (!p.webcam.enabled) {
        Help("The webcam is off for this presentation.")
        MuButton("Turn the webcam on", { pickCamera(h) }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Camera)
        return
    }
    val options = listOf(Slide.CAMERA_DEFAULT, Slide.CAMERA_HIDDEN) + WebcamZone.PRESETS.filter { it != WebcamZone.CUSTOM } + Slide.CAMERA_CUSTOM
    MuSelect(slide.camera, options, ::cameraName, { v ->
        if (v == Slide.CAMERA_CUSTOM) {
            // Its own box starts where the camera stands now, to be dragged from there.
            val now = SlideCamera.zone(p, slide) ?: SlideCamera.BIG
            commitSlide(h, slide, "Camera") { SlideCamera.moved(it, now) }
            h.present.cameraPicked = true
        } else {
            commitSlide(h, slide, "Camera") { it.copy(camera = v, cameraBox = null) }
        }
    }, Modifier.fillMaxWidth(), small = true)
    Help("Click the camera on the slide to drag it where it should stand here; the rest of the slide makes room. $CAMERA_NOTE")
}

private fun cameraName(v: String): String = when (v) {
    Slide.CAMERA_DEFAULT -> "Where it always is"
    Slide.CAMERA_HIDDEN -> "Hidden"
    Slide.CAMERA_CUSTOM -> "A box of its own"
    else -> WebcamZone.presetName(v)
}

/** The camera picked on the slide: where it stands here, by numbers too. */
@Composable
private fun CameraProps(h: NeueHolders, p: Presentation, slide: Slide) {
    Micro("Camera", color = Mu.colors.ink70)
    val zone = SlideCamera.zone(p, slide)
    if (zone != null) key(slide.id) {
        FieldLabel("Where it stands here", hint = "canvas 1920 × 1080; Enter sets it")
        fun put(b: com.kaiharimoto.mastertool.core.present.stage.Box) = commitSlide(h, slide, "Move the camera") { SlideCamera.moved(it, b) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("X", zone.x, Modifier.weight(1f)) { put(zone.copy(x = it)) }
            NumberField("Y", zone.y, Modifier.weight(1f)) { put(zone.copy(y = it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("W", zone.w, Modifier.weight(1f), min = SlideCamera.MIN) { put(zone.copy(w = it)) }
            NumberField("H", zone.h, Modifier.weight(1f), min = SlideCamera.MIN) { put(zone.copy(h = it)) }
        }
    }
    SlideCameraField(h, p, slide)
    Help("Moving it here moves it on this slide only; the Theme tab sets where it stands on every slide, its shape and its border.")
}

// ---- the element -------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElementProps(h: NeueHolders, p: Presentation, slide: Slide, ctx: SlideContext) {
    val present = h.present
    val c = Mu.colors
    val show = remember(p) { CompiledShow(p) }
    val sel = slide.elements.filter { it.id in present.selection }
    if (present.cameraPicked && sel.isEmpty()) {
        CameraProps(h, p, slide)
        return
    }
    if (sel.isEmpty()) {
        // Nothing selected (the audit's newcomer item 7): what is on the slide, each a tap away.
        Help("Select something to change it: ${if (LocalTouchFirst.current) "tap it, or turn on Select several for more" else "click it, Shift-click for more, or drag a box round several"}. Or pick it here.")
        slide.elements.asReversed().forEach { el ->
            Row(
                Modifier.fillMaxWidth().border(1.dp, c.ink12).cursorPointer(label = "Select it").muClickable { present.selection = present.groupOf(el.id); present.cameraPicked = false }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Micro(Element.typeName(el.type), Modifier.width(64.dp), color = c.ink70)
                Small(el.plainText.ifBlank { el.stat?.value ?: el.cards.mapNotNull { ctx.cards(it)?.name }.firstOrNull() ?: "" }.take(40), Modifier.weight(1f), maxLines = 1)
                if (Placeholders.untouched(el)) Micro("Fill in", color = c.ink)
            }
        }
        if (p.webcam.enabled && show.zone(present.slideIndex) != null) {
            MuButton("The camera", { pickCamera(h) }, Modifier.fillMaxWidth(), size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Camera)
        }
        if (slide.elements.isEmpty()) Small("This slide has nothing on it yet: Text, Shape, Picture, Card and Insert in the bar add something.", color = c.ink45)
        return
    }
    val ids = sel.map { it.id }.toSet()
    val e = sel.first()
    val stage = show.stage(present.slideIndex)
    val box = Geometry.box(e, stage)
    Micro(if (sel.size == 1) Element.typeName(e.type) else "${sel.size} selected", color = c.ink70)
    if (sel.any(Placeholders::untouched)) Help("This still shows a placeholder: fill it in before the slide goes out.")

    if (sel.size == 1) key(e.id) {
        FieldLabel("Position and size", hint = "canvas 1920 × 1080; Enter sets it")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("X", box.x, Modifier.weight(1f)) { v -> commitElements(h, slide, ids, "Move") { Geometry.place(it, box.copy(x = v), stage) } }
            NumberField("Y", box.y, Modifier.weight(1f)) { v -> commitElements(h, slide, ids, "Move") { Geometry.place(it, box.copy(y = v), stage) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("W", box.w, Modifier.weight(1f), min = 12f) { v -> commitElements(h, slide, ids, "Resize") { Geometry.place(it, box.copy(w = v), stage) } }
            NumberField("H", box.h, Modifier.weight(1f), min = 12f) { v -> commitElements(h, slide, ids, "Resize") { Geometry.place(it, box.copy(h = v), stage) } }
            NumberField("°", e.rotation, Modifier.weight(1f)) { v -> commitElements(h, slide, ids, "Turn") { it.copy(rotation = ((v % 360f) + 360f) % 360f) } }
        }
    }
    FieldLabel("Arrange")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Align.LEFT to "Left", Align.CENTER to "Centre", Align.RIGHT to "Right", Align.TOP to "Top", Align.MIDDLE to "Middle", Align.BOTTOM to "Bottom").forEach { (edge, word) ->
            MuButton(word, {
                val boxes = sel.map { Geometry.box(it, stage) }
                val to = if (sel.size == 1) com.kaiharimoto.mastertool.core.present.stage.Box.CANVAS else null
                val aligned = Align.align(boxes, edge, to)
                val byId = sel.mapIndexed { i, el -> el.id to aligned[i] }.toMap()
                commitElements(h, slide, ids, "Align") { el -> byId[el.id]?.let { Geometry.place(el, it, stage) } ?: el }
            }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
        if (sel.size >= 3) {
            MuButton("Space across", { distribute(h, slide, sel, stage, true) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            MuButton("Space down", { distribute(h, slide, sel, stage, false) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MuButton("Front", { reorder(h, PresentEdits.FRONT) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        MuButton("Back", { reorder(h, PresentEdits.BACK) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        MuButton(if (sel.any { it.group != null }) "Ungroup" else "Group", { group(h, sel.none { it.group != null }) }, size = BtnSize.SM, variant = BtnVariant.GHOST, enabled = sel.size > 1 || sel.any { it.group != null }, reason = "Select two or more")
        MuButton(if (e.locked) "Unlock" else "Lock", { setLocked(h, !e.locked) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        MuButton("Delete", { deleteSelection(h) }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Trash)
    }
    Small("See-through ${((1f - e.opacity) * 100).roundToInt()}%")
    MuSlider(e.opacity, { v -> commitElements(h, slide, ids, "Opacity", "opacity") { it.copy(opacity = v) } }, Modifier.fillMaxWidth(), 0.05f..1f, name = "Opacity")

    when (e.type) {
        Element.TEXT, Element.SHAPE -> {
            if (e.type == Element.SHAPE) ShapeProps(h, slide, ids, e, ctx)
            if (e.type == Element.TEXT || e.paras.isNotEmpty()) TextProps(h, slide, ids, e, ctx)
            if (e.type == Element.SHAPE && e.paras.isEmpty() && e.shape != Element.SHAPE_LINE && e.shape != Element.SHAPE_ARROW_LINE) {
                MuButton("Add words", { commitElements(h, slide, ids, "Words") { it.copy(paras = listOf(Para.of("Text", align = Para.ALIGN_CENTER)), vAlign = Element.V_MIDDLE) }; present.editingText = e.id }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
        Element.IMAGE -> {
            val scope = rememberCoroutineScope()
            FieldLabel("Picture")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MuButton("Replace", { scope.launch {
                    val picked = Platform.pick("Replace the picture", PICTURE_EXTENSIONS) ?: return@launch
                    val name = present.putMedia(picked.bytes, picked.extension)
                    commitElements(h, slide, ids, "Picture") { it.copy(media = name) }
                } }, size = BtnSize.SM)
                Segmented(e.imageFit, listOf(Element.FIT_COVER, Element.FIT_CONTAIN), { if (it == Element.FIT_COVER) "Fill" else "Fit" }, { v -> commitElements(h, slide, ids, "Fit") { it.copy(imageFit = v) } }, small = true)
            }
            FieldLabel("Shape")
            MuSelect(e.shape, listOf(Element.SHAPE_RECT, Element.SHAPE_ROUNDED, Element.SHAPE_ELLIPSE, Element.SHAPE_DIAMOND, Element.SHAPE_STAR), Element::shapeName, { v -> commitElements(h, slide, ids, "Mask") { it.copy(shape = v, corner = if (v == Element.SHAPE_ROUNDED) 32f else it.corner) } }, Modifier.fillMaxWidth(), small = true)
            BorderAndShadow(h, slide, ids, e, ctx)
            Help("A picture can be pasted (Ctrl V) or dropped on the slide too: it comes in as a new picture.")
        }
        Element.CARD, Element.CARDS -> {
            FieldLabel(if (e.type == Element.CARD) "Card" else "Cards")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MuButton("Choose…", { present.pickingCards = PickTarget.REPLACE }, size = BtnSize.SM)
                if (e.type == Element.CARDS) Segmented(e.cardLayout, listOf(Element.CARDS_ROW, Element.CARDS_FAN, Element.CARDS_GRID), { it.lowercase().replaceFirstChar(Char::uppercase) }, { v -> commitElements(h, slide, ids, "Layout") { it.copy(cardLayout = v) } }, small = true)
            }
            if (e.type == Element.CARDS) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MuSwitch(e.cardLabels, { v -> commitElements(h, slide, ids, "Names") { it.copy(cardLabels = v) } })
                    Small("Names under the cards")
                }
            }
            Small(e.cards.mapNotNull { ctx.cards(it)?.name }.joinToString(", ").ifBlank { "No cards yet" }, color = c.ink45)
        }
        Element.STAT -> {
            val st = e.stat ?: Stat()
            FieldLabel("Number")
            MuInput(st.value, { v -> commitElements(h, slide, ids, "Number", "stat") { it.copy(stat = st.copy(value = v)) } }, Modifier.fillMaxWidth(), dense = true)
            FieldLabel("What it counts")
            MuInput(st.label, { v -> commitElements(h, slide, ids, "Number", "stat") { it.copy(stat = st.copy(label = v)) } }, Modifier.fillMaxWidth(), dense = true)
            MuInput(st.sub, { v -> commitElements(h, slide, ids, "Number", "stat") { it.copy(stat = st.copy(sub = v)) } }, Modifier.fillMaxWidth(), dense = true, placeholder = "A line under it")
        }
        Element.TABLE -> {
            FieldLabel("Rows", hint = "one a line, cells split by |")
            NotesField(e.table.joinToString("\n") { it.joinToString(" | ") }, { v ->
                commitElements(h, slide, ids, "Table", "table") { it.copy(table = v.lines().filter { l -> l.isNotBlank() }.map { l -> l.split("|").map(String::trim) }) }
            }, "Matchup | First | Second")
        }
        Element.CHART -> {
            val ch = e.chart ?: Chart()
            FieldLabel("Chart")
            MuSelect(ch.kind, listOf(Chart.COLUMN, Chart.BAR, Chart.LINE, Chart.PIE, Chart.DONUT), { it.lowercase().replaceFirstChar(Char::uppercase) }, { v -> commitElements(h, slide, ids, "Chart") { it.copy(chart = ch.copy(kind = v)) } }, Modifier.fillMaxWidth(), small = true)
            FieldLabel("Labels", hint = "split by commas")
            MuInput(ch.labels.joinToString(", "), { v -> commitElements(h, slide, ids, "Chart", "chart") { it.copy(chart = ch.copy(labels = v.split(",").map(String::trim).filter(String::isNotEmpty))) } }, Modifier.fillMaxWidth(), dense = true)
            FieldLabel("Values", hint = "one series a line: name: 1, 2, 3")
            NotesField(ch.series.joinToString("\n") { s -> "${s.name}: ${s.values.joinToString(", ") { v -> if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else v.toString() }}" }, { v ->
                val series = v.lines().filter { it.isNotBlank() }.map { line ->
                    val name = line.substringBefore(":", "").trim()
                    val nums = line.substringAfter(":").split(",").mapNotNull { it.trim().toFloatOrNull() }
                    ChartSeries(name, nums)
                }
                commitElements(h, slide, ids, "Chart", "chart") { it.copy(chart = ch.copy(series = series)) }
            }, "Win rate: 60, 45, 52")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MuSwitch(ch.percent, { v -> commitElements(h, slide, ids, "Chart") { it.copy(chart = ch.copy(percent = v, max = if (v) 100f else null)) } })
                Small("Percentages")
            }
        }
        Element.QR -> {
            FieldLabel("What the code holds")
            MuInput(e.qr.orEmpty(), { v -> commitElements(h, slide, ids, "Code", "qr") { it.copy(qr = v) } }, Modifier.fillMaxWidth(), dense = true, mono = true)
        }
        Element.DECK -> {
            FieldLabel("Shows")
            val f = e.focus ?: DeckFocus(all = true)
            Segmented(f.sections.firstOrNull() ?: "ALL", listOf("ALL", "M", "E", "S"), { when (it) { "M" -> "Main"; "E" -> "Extra"; "S" -> "Side"; else -> "All" } }, { v ->
                commitElements(h, slide, ids, "Deck") { it.copy(focus = f.copy(all = true, sections = if (v == "ALL") emptyList() else listOf(v))) }
            }, small = true)
        }
    }
    if (sel.size == 1) {
        FieldLabel("When clicked while presenting")
        val targets = listOf<String?>(null) + p.slides.map { it.id }
        MuSelect(e.link, targets, { id -> id?.let { "Go to slide ${p.indexOf(it) + 1}" } ?: "Nothing" }, { v -> commitElements(h, slide, ids, "Link") { it.copy(link = v) } }, Modifier.fillMaxWidth(), small = true)
    }
}

private fun distribute(h: NeueHolders, slide: Slide, sel: List<Element>, stage: com.kaiharimoto.mastertool.core.present.stage.Box, across: Boolean) {
    val boxes = sel.map { Geometry.box(it, stage) }
    val spread = Align.distribute(boxes, across)
    val byId = sel.mapIndexed { i, el -> el.id to spread[i] }.toMap()
    commitElements(h, slide, byId.keys, "Distribute") { el -> byId[el.id]?.let { Geometry.place(el, it, stage) } ?: el }
}

/**
 * A number typed (the editor's audit, B1): the words are the field's own while it has focus, and the
 * number is read once — on Enter, or as the field is let go — held within [min]..[max], one step of
 * Undo. Read on every key, typing 500 into W gave 1200: "5" was clamped to 12 at once, and the field
 * was rewritten under the typing.
 */
@Composable
internal fun NumberField(label: String, value: Float, modifier: Modifier, min: Float = -Float.MAX_VALUE, max: Float = Float.MAX_VALUE, onChange: (Float) -> Unit) {
    val shown = value.roundToInt().toString()
    var text by remember { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    // While it is not being typed in, it shows the value as it stands (a drag on the slide, an Undo).
    if (!focused && text != shown) text = shown
    fun commit() {
        val v = EditorEdits.number(text, min, max)
        if (v != null && v.roundToInt() != value.roundToInt()) onChange(v)
        text = (v ?: value).roundToInt().toString()
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Mono(label, color = Mu.colors.ink45)
        MuInput(
            text, { v -> text = v }, Modifier.weight(1f), dense = true, mono = true,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            onFocusChange = { f ->
                if (focused && !f) commit()
                focused = f
            },
            onSubmit = ::commit,
        )
    }
}

@Composable
private fun ShapeProps(h: NeueHolders, slide: Slide, ids: Set<String>, e: Element, ctx: SlideContext) {
    FieldLabel("Shape")
    MuSelect(e.shape, Element.SHAPES, Element::shapeName, { v -> commitElements(h, slide, ids, "Shape") { it.copy(shape = v) } }, Modifier.fillMaxWidth(), small = true)
    val line = e.shape == Element.SHAPE_LINE || e.shape == Element.SHAPE_ARROW_LINE
    if (!line) {
        FieldLabel("Fill")
        val fill = e.fill
        val fillKind = fill?.kind ?: "NONE"
        Segmented(fillKind, listOf("NONE", Fill.SOLID, Fill.LINEAR, Fill.RADIAL), { when (it) { "NONE" -> "None"; Fill.SOLID -> "Colour"; Fill.LINEAR -> "Gradient"; else -> "Radial" } }, { k ->
            commitElements(h, slide, ids, "Fill") { it.copy(fill = if (k == "NONE") null else Fill(k, fill?.color ?: "@accent", stops = if (k == Fill.SOLID) emptyList() else listOf("@accent", "@accent2"))) }
        }, small = true)
        if (fill != null) {
            if (fill.kind == Fill.SOLID) {
                ColorField(fill.color, ctx.theme, { v -> commitElements(h, slide, ids, "Fill") { it.copy(fill = fill.copy(color = v ?: "@accent")) } }, Modifier.fillMaxWidth())
            } else {
                val stops = fill.stops.ifEmpty { listOf("@accent", "@accent2") }
                stops.forEachIndexed { i, stop ->
                    ColorField(stop, ctx.theme, { v -> commitElements(h, slide, ids, "Fill") { it.copy(fill = fill.copy(stops = stops.toMutableList().also { l -> l[i] = v ?: "@accent" })) } }, Modifier.fillMaxWidth(), label = "Stop ${i + 1}")
                }
                if (fill.kind == Fill.LINEAR) MuSlider(fill.angle, { a -> commitElements(h, slide, ids, "Fill", "angle") { it.copy(fill = fill.copy(angle = a)) } }, Modifier.fillMaxWidth(), 0f..360f, name = "Angle")
            }
        }
        if (e.shape == Element.SHAPE_ROUNDED || e.shape == Element.SHAPE_CALLOUT) {
            Small("Rounding ${e.corner.roundToInt()}")
            MuSlider(e.corner, { v -> commitElements(h, slide, ids, "Rounding", "corner") { it.copy(corner = v) } }, Modifier.fillMaxWidth(), 0f..200f, name = "Rounding")
        }
    }
    BorderAndShadow(h, slide, ids, e, ctx, line)
}

@Composable
private fun BorderAndShadow(h: NeueHolders, slide: Slide, ids: Set<String>, e: Element, ctx: SlideContext, line: Boolean = false) {
    FieldLabel(if (line) "Line" else "Border")
    val st = e.stroke
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!line) MuSwitch(st != null, { v -> commitElements(h, slide, ids, "Border") { it.copy(stroke = if (v) Stroke("@line", 4f) else null) } })
        if (st != null || line) {
            val s = st ?: Stroke("@line", 6f)
            ColorField(s.color, ctx.theme, { v -> commitElements(h, slide, ids, "Border") { it.copy(stroke = s.copy(color = v ?: "@line")) } }, Modifier.weight(1f))
        }
    }
    if (st != null || line) {
        val s = st ?: Stroke("@line", 6f)
        Small("Width ${s.width.roundToInt()}")
        MuSlider(s.width, { v -> commitElements(h, slide, ids, "Border", "stroke") { it.copy(stroke = s.copy(width = v)) } }, Modifier.fillMaxWidth(), 1f..40f, name = "Width")
        Segmented(s.dash, listOf(Stroke.DASH_SOLID, Stroke.DASH_DASHED, Stroke.DASH_DOTTED), { it.lowercase().replaceFirstChar(Char::uppercase) }, { v -> commitElements(h, slide, ids, "Border") { it.copy(stroke = s.copy(dash = v)) } }, small = true)
    }
    if (!line) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MuSwitch(e.shadow != null, { v -> commitElements(h, slide, ids, "Shadow") { it.copy(shadow = if (v) Shadow() else null) } })
            Small("Shadow")
        }
        e.shadow?.let { sh ->
            Small("Softness ${sh.blur.roundToInt()}")
            MuSlider(sh.blur, { v -> commitElements(h, slide, ids, "Shadow", "blur") { it.copy(shadow = sh.copy(blur = v)) } }, Modifier.fillMaxWidth(), 0f..80f, name = "Softness")
            Small("Drop ${sh.dy.roundToInt()}")
            MuSlider(sh.dy, { v -> commitElements(h, slide, ids, "Shadow", "dy") { it.copy(shadow = sh.copy(dy = v)) } }, Modifier.fillMaxWidth(), -40f..60f, name = "Drop")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextProps(h: NeueHolders, slide: Slide, ids: Set<String>, e: Element, ctx: SlideContext) {
    FieldLabel("Words")
    Segmented(e.role, listOf(Element.ROLE_TITLE, Element.ROLE_SUBTITLE, Element.ROLE_BODY, Element.ROLE_CAPTION), { when (it) { Element.ROLE_TITLE -> "Title"; Element.ROLE_SUBTITLE -> "Sub"; Element.ROLE_CAPTION -> "Caption"; else -> "Body" } }, { v ->
        commitElements(h, slide, ids, "Text style") { it.copy(role = v) }
    }, small = true)
    val first = e.paras.firstOrNull()?.runs?.firstOrNull()?.style ?: RunStyle()
    fun all(change: (RunStyle) -> RunStyle) = commitElements(h, slide, ids, "Text") { el ->
        el.copy(paras = el.paras.map { pa -> pa.copy(runs = pa.runs.map { r -> r.copy(style = change(r.style)) }) })
    }
    MuSelect(first.font, listOf<String?>(null) + SlideFonts.all, { it?.let(SlideFonts::name) ?: "The theme's" }, { v -> all { it.copy(font = v) } }, Modifier.fillMaxWidth(), small = true)
    val look = com.kaiharimoto.neue.present.paint.roleLook(e.role, ctx.theme)
    val size = first.size ?: look.size
    Small("Size ${size.roundToInt()}")
    MuSlider(size, { v -> commitElements(h, slide, ids, "Size", "size") { el -> el.copy(paras = el.paras.map { pa -> pa.copy(runs = pa.runs.map { r -> r.copy(style = r.style.copy(size = v)) }) }) } }, Modifier.fillMaxWidth(), 14f..240f, name = "Size")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WordToggle("Bold", (first.weight ?: look.weight) >= 700) { bold(h) }
        WordToggle("Italic", first.italic) { italic(h) }
        WordToggle("Underline", first.underline) { underline(h) }
        WordToggle("Capitals", first.caps) { all { it.copy(caps = !first.caps) } }
    }
    ColorField(first.color ?: look.color, ctx.theme, { v -> all { it.copy(color = v) } }, Modifier.fillMaxWidth(), label = "Text colour")
    ColorField(first.highlight, ctx.theme, { v -> all { it.copy(highlight = v) } }, Modifier.fillMaxWidth(), none = true, label = "Highlight")
    val para = e.paras.firstOrNull() ?: Para()
    Segmented(para.align, listOf(Para.ALIGN_LEFT, Para.ALIGN_CENTER, Para.ALIGN_RIGHT), { when (it) { Para.ALIGN_CENTER -> "Centre"; Para.ALIGN_RIGHT -> "Right"; else -> "Left" } }, { v ->
        commitElements(h, slide, ids, "Align text") { el -> el.copy(paras = el.paras.map { it.copy(align = v) }) }
    }, small = true)
    Segmented(e.vAlign, listOf(Element.V_TOP, Element.V_MIDDLE, Element.V_BOTTOM), { it.lowercase().replaceFirstChar(Char::uppercase) }, { v -> commitElements(h, slide, ids, "Align text") { it.copy(vAlign = v) } }, small = true)
    Segmented(para.list, listOf(Para.LIST_NONE, Para.LIST_BULLET, Para.LIST_NUMBER), { when (it) { Para.LIST_BULLET -> "• List"; Para.LIST_NUMBER -> "1. List"; else -> "No list" } }, { v ->
        commitElements(h, slide, ids, "List") { el -> el.copy(paras = el.paras.map { it.copy(list = v) }) }
    }, small = true)
    Small("Line height ${"%.2f".format(para.lineHeight)}")
    MuSlider(para.lineHeight, { v -> commitElements(h, slide, ids, "Line height", "lh") { el -> el.copy(paras = el.paras.map { it.copy(lineHeight = v) }) } }, Modifier.fillMaxWidth(), 0.8f..2.2f, name = "Line height")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuSwitch(e.fit == Element.FIT_SHRINK, { v -> commitElements(h, slide, ids, "Fit") { it.copy(fit = if (v) Element.FIT_SHRINK else Element.FIT_NONE) } })
        Small("Shrink to fit the box")
    }
    if (e.type == Element.TEXT) {
        ColorField(e.fill?.color, ctx.theme, { v -> commitElements(h, slide, ids, "Box") { it.copy(fill = v?.let(Fill::solid)) } }, Modifier.fillMaxWidth(), none = true, label = "Box colour")
        Small("Padding ${e.padding.roundToInt()}")
        MuSlider(e.padding, { v -> commitElements(h, slide, ids, "Padding", "pad") { it.copy(padding = v) } }, Modifier.fillMaxWidth(), 0f..80f, name = "Padding")
    }
    Help("Double-click the words to edit them where they stand; select some and press Ctrl B, Ctrl U or Ctrl Alt I.")
}

// ---- builds ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimateProps(h: NeueHolders, p: Presentation, slide: Slide) {
    val present = h.present
    val c = Mu.colors
    val sel = slide.elements.filter { it.id in present.selection }
    val all = slide.elements.flatMap { e -> e.animations.map { e to it } }.sortedWith(compareBy({ it.second.order }, { it.second.id }))
    FieldLabel("Add to the selection")
    if (sel.isEmpty()) Help("Select something on the slide to make it come in, draw the eye, or go.")
    // Wrapped, so Go is never cut off by the panel's edge (B13).
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Anim.ENTRANCE to "Come in", Anim.EMPHASIS to "Draw the eye", Anim.EXIT to "Go").forEach { (kind, word) ->
            MuButton(word, {
                var order = (all.maxOfOrNull { it.second.order } ?: -1) + 1
                val effect = when (kind) {
                    Anim.EMPHASIS -> Anim.PULSE
                    else -> Anim.RISE
                }
                commitElements(h, slide, sel.map { it.id }.toSet(), "Add build") { e ->
                    e.copy(animations = e.animations + Anim(PresentIds.next("a"), kind, if (kind == Anim.EXIT) Anim.FADE else effect, if (order == (all.maxOfOrNull { it.second.order } ?: -1) + 1) Anim.ON_CLICK else Anim.WITH_PREVIOUS, order = order++))
                }
            }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
        }
    }
    FieldLabel("In order", hint = "${all.size}")
    if (all.isEmpty()) Help("Nothing on this slide moves yet. Builds run on clicks, with the one before, or after it.")
    all.forEachIndexed { i, (e, a) ->
        Column(Modifier.fillMaxWidth().border(1.dp, if (e.id in present.selection) c.ink else c.ink12).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("${i + 1}", Modifier.width(20.dp), color = c.ink45)
                Small(
                    "${Element.typeName(e.type)}${e.plainText.take(18).takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                    Modifier.weight(1f).cursorPointer(label = "Select it").muClickable { present.selection = setOf(e.id) },
                    maxLines = 1,
                )
                IconButton(Icons.ArrowUp, { moveBuild(h, slide, all, i, -1) }, size = 22.dp, enabled = i > 0, label = "Earlier", reason = "First already")
                IconButton(Icons.ArrowDown, { moveBuild(h, slide, all, i, 1) }, size = 22.dp, enabled = i < all.lastIndex, label = "Later", reason = "Last already")
                IconButton(Icons.X, { commitElements(h, slide, setOf(e.id), "Remove build") { el -> el.copy(animations = el.animations.filterNot { it.id == a.id }) } }, size = 22.dp, label = "Remove")
            }
            val effects = when (a.kind) {
                Anim.EMPHASIS -> Anim.EMPHASIS_EFFECTS
                Anim.EXIT -> Anim.EXIT_EFFECTS
                else -> Anim.ENTRANCE_EFFECTS
            }
            fun put(change: (Anim) -> Anim) = commitElements(h, slide, setOf(e.id), "Build", "build-${a.id}") { el -> el.copy(animations = el.animations.map { if (it.id == a.id) change(it) else it }) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MuSelect(a.effect, effects, Anim::effectName, { v -> put { it.copy(effect = v) } }, Modifier.weight(1f), small = true)
                MuSelect(a.trigger, listOf(Anim.ON_CLICK, Anim.WITH_PREVIOUS, Anim.AFTER_PREVIOUS), Anim::triggerName, { v -> put { it.copy(trigger = v) } }, Modifier.weight(1f), small = true)
            }
            Small("${a.durationMs} ms${if (a.delayMs > 0) ", after ${a.delayMs} ms" else ""}", color = c.ink45)
            MuSlider(a.durationMs.toFloat(), { v -> put { it.copy(durationMs = v.roundToInt()) } }, Modifier.fillMaxWidth(), 100f..3000f, name = "Length")
            MuSlider(a.delayMs.toFloat(), { v -> put { it.copy(delayMs = v.roundToInt()) } }, Modifier.fillMaxWidth(), 0f..3000f, name = "Delay")
        }
    }
    if (all.isNotEmpty()) MuButton("Play this slide", { present.present(present.slideIndex) }, size = BtnSize.SM, variant = BtnVariant.GHOST, arrow = true)
}

private fun moveBuild(h: NeueHolders, slide: Slide, all: List<Pair<Element, Anim>>, i: Int, by: Int) {
    val j = i + by
    if (j !in all.indices) return
    val order = all.map { it.second.id }.toMutableList()
    val t = order[i]; order[i] = order[j]; order[j] = t
    val rank = order.withIndex().associate { (k, id) -> id to k }
    val p = h.present.open ?: return
    h.present.commit(PresentEdits.updateSlide(p, slide.id) { s ->
        s.copy(elements = s.elements.map { e -> e.copy(animations = e.animations.map { a -> a.copy(order = rank[a.id] ?: a.order) }) })
    }, "Reorder builds")
}

// ---- the deck ----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckProps(h: NeueHolders, p: Presentation, slide: Slide, ctx: SlideContext) {
    val present = h.present
    val c = Mu.colors
    val deck = p.deck
    FieldLabel("How the deck is told")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Presentation.STYLES.forEach { st ->
            Row(
                Modifier.fillMaxWidth().border(if (st == p.style) 2.dp else 1.dp, if (st == p.style) c.ink else c.ink12)
                    .cursorPointer(label = Presentation.styleName(st))
                    // The whole-deck step moves with the style: Build-up ends on it, the others open with it (I9).
                    .muClickable { present.commit(EditorEdits.retell(p, st), "Style") }
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StyleDiagram(st, Modifier.width(64.dp).aspectRatio(16f / 9f))
                Column(Modifier.weight(1f)) {
                    Micro(Presentation.styleName(st), color = c.ink)
                    Small(Presentation.styleLine(st), color = c.ink70, maxLines = 2)
                }
            }
        }
    }
    if (deck == null) {
        Help("This presentation has no deck. Start a deck profile from the library to tell one.")
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // It replaces every deck slide: asked first when they hold work of the person's (I9).
        MuButton("Slides from groups", {
            if (EditorEdits.deckSlidesHoldWork(p)) present.confirmFromGroups = true
            else present.commit(PresentEdits.stepsFromGroups(p), "Deck slides from groups")
        }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        MuButton("Deck slide", { addSlide(h, SlideLayouts.DECK) }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus)
    }
    val focus = slide.deck
    if (focus == null) {
        Help("This is not a deck slide. Pick one in the list, or add one: its deck is told the way chosen above.")
        return
    }
    FieldLabel("This step")
    MuInput(focus.title, { t -> commitSlide(h, slide, "Step", "step-${slide.id}") { it.copy(title = t, deck = focus.copy(title = t)) } }, Modifier.fillMaxWidth(), dense = true, placeholder = "What this step is about")
    // Two kinds of note (the audit's newcomer item 5): this one is drawn on the slide; speaker notes are private.
    FieldLabel("On the slide", hint = "a note beside the deck, everyone sees it")
    NotesField(focus.note, { t -> commitSlide(h, slide, "Note", "note-${slide.id}") { it.copy(deck = focus.copy(note = t)) } }, "Why these cards, in a sentence or two. What only you should see goes in the speaker notes under the slide.")
    Segmented(focus.notePlace, listOf(DeckFocus.NOTE_AUTO, DeckFocus.NOTE_SIDE, DeckFocus.NOTE_BOTTOM, DeckFocus.NOTE_NONE), { when (it) { DeckFocus.NOTE_SIDE -> "Side"; DeckFocus.NOTE_BOTTOM -> "Under"; DeckFocus.NOTE_NONE -> "None"; else -> "Auto" } }, { v ->
        commitSlide(h, slide, "Note place") { it.copy(deck = focus.copy(notePlace = v)) }
    }, small = true)
    FieldLabel("Talks about")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuSwitch(focus.all, { v -> commitSlide(h, slide, "Focus") { it.copy(deck = focus.copy(all = v)) } })
        Small("The whole deck")
    }
    if (deck.groups.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            deck.ordered().forEach { g ->
                Tag(g.name, g.id in focus.groups, {
                    commitSlide(h, slide, "Focus") { it.copy(deck = focus.copy(all = false, groups = if (g.id in focus.groups) focus.groups - g.id else focus.groups + g.id)) }
                }, caption = "Talk about")
            }
        }
    }
    Help(if (LocalTouchFirst.current) "Or tap cards on the slide with nothing selected, or below." else "Or click cards on the slide with nothing selected, or below.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        deck.distinct.forEach { id ->
            val card = ctx.cards(id) ?: return@forEach
            val on = id in focus.cards || deck.groupOf(id)?.let { it in focus.groups } == true
            Box(
                Modifier.width(40.dp).aspectRatio(59f / 86f)
                    .border(if (on) 2.dp else 0.dp, if (on) c.ink else c.ink12)
                    .graphicsLayer { alpha = if (on || focus.all) 1f else 0.45f }
                    .cursorPointer(label = card.name)
                    .muClickable { toggleFocusCard(h, slide, id) },
            ) { NeueCard(card, Modifier.fillMaxSize(), format = ctx.format, foil = "off") }
        }
    }
    FieldLabel("The deck", hint = deck.name)
    Small("Kept as it was when the presentation was made, so a take never changes under you.", color = c.ink45)
    deck.deckId?.let { id ->
        val scope = rememberCoroutineScope()
        MuButton("Refresh from the saved deck", {
            scope.launch {
                val stored = h.deps.deckRepository.byId(id) ?: return@launch
                val groups = DeckGroupsCodec.read(stored.extended).groups
                val snap = PresentEdits.snapshot(stored.entry.deck, groups, stored.entry.name, id, deck.arrangement, deck.arts, deck.palette, System.currentTimeMillis())
                val o = present.open ?: return@launch
                present.commit(o.copy(deck = snap), "Refresh the deck")
            }
        }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Refresh)
    }
}

// ---- the theme and the camera ------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeProps(h: NeueHolders, p: Presentation, ctx: SlideContext) {
    val present = h.present
    val c = Mu.colors
    FieldLabel("Theme", hint = "Master UI is the default")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Themes.all.forEach { t -> ThemeSwatch(t, t.id == p.theme, { present.applyTheme(t.id) }, Modifier.width(84.dp)) }
    }
    if (h.neue.prefs.ai.enabled) {
        MuButton("Restyle with ${h.ai.name}…", { present.restyling = true }, Modifier.fillMaxWidth(), size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
    val o = p.themeOverride ?: ThemeOverride()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuSwitch(ctx.theme.flat, { v -> present.commit(p.copy(themeOverride = o.copy(flat = v)), "Flat") })
        Column {
            Small("Flat", color = c.ink)
            Small("Square corners, no shadows or glows", color = c.ink45)
        }
    }
    FieldLabel("Its colours", hint = "change any")
    Theme.TOKENS.forEach { token ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small(Theme.tokenName(token), Modifier.width(92.dp))
            ColorField(ctx.theme.color(token), Themes.of(p.theme), { v ->
                present.commit(p.copy(themeOverride = o.copy(colors = if (v == null) o.colors - token else o.colors + (token to v))), "Theme colour")
            }, Modifier.weight(1f))
        }
    }
    // Each face named for what it sets (I7).
    FieldLabel("Faces")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Small("Headings", Modifier.width(72.dp))
        MuSelect(ctx.theme.headingFont, SlideFonts.all, SlideFonts::name, { v -> present.commit(p.copy(themeOverride = o.copy(headingFont = v)), "Heading face") }, Modifier.weight(1f), small = true)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Small("Words", Modifier.width(72.dp))
        MuSelect(ctx.theme.bodyFont, SlideFonts.all, SlideFonts::name, { v -> present.commit(p.copy(themeOverride = o.copy(bodyFont = v)), "Body face") }, Modifier.weight(1f), small = true)
    }
    Small("Cards not talked about: ${(ctx.theme.dim * 100).roundToInt()}% bright")
    MuSlider(ctx.theme.dim, { v -> present.commit(p.copy(themeOverride = o.copy(dim = v)), "Dimming", "dim") }, Modifier.fillMaxWidth(), 0.05f..0.8f, name = "Dimming")

    FieldLabel("Webcam", hint = "the slides make room for it")
    val z = p.webcam
    fun put(next: WebcamZone) = present.commit(p.copy(webcam = next), "Camera", "camera")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuSwitch(z.enabled, { put(z.copy(enabled = it)) })
        Small(if (z.enabled) "On" else "Off")
    }
    if (z.enabled) Help("$CAMERA_NOTE A slide can move or hide it: its Slide tab, or drag it on the slide.")
    if (z.enabled) {
        MuSelect(z.preset, WebcamZone.PRESETS, WebcamZone::presetName, { v ->
            put(z.copy(preset = v, box = if (v == WebcamZone.CUSTOM) (WebcamLayout.zone(z) ?: z.box) else z.box))
        }, Modifier.fillMaxWidth(), small = true)
        Segmented(z.size, listOf(WebcamZone.SIZE_S, WebcamZone.SIZE_M, WebcamZone.SIZE_L), { when (it) { WebcamZone.SIZE_S -> "Small"; WebcamZone.SIZE_L -> "Large"; else -> "Medium" } }, { put(z.copy(size = it)) }, small = true)
        MuSelect(z.shape, WebcamZone.SHAPES, WebcamZone::shapeName, { put(z.copy(shape = it)) }, Modifier.fillMaxWidth(), small = true)
        if (z.preset == WebcamZone.CUSTOM) {
            val b = z.box ?: WebcamLayout.zone(z.copy(preset = WebcamZone.BOTTOM_RIGHT))!!
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("X", b.x, Modifier.weight(1f)) { put(z.copy(box = b.copy(x = it))) }
                NumberField("Y", b.y, Modifier.weight(1f)) { put(z.copy(box = b.copy(y = it))) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("W", b.w, Modifier.weight(1f), min = SlideCamera.MIN) { put(z.copy(box = b.copy(w = it))) }
                NumberField("H", b.h, Modifier.weight(1f), min = SlideCamera.MIN) { put(z.copy(box = b.copy(h = it))) }
            }
        }
        FieldLabel("Border")
        ColorField(z.border, ctx.theme, { put(z.copy(border = it)) }, Modifier.fillMaxWidth(), none = true)
        MuSlider(z.borderWidth, { put(z.copy(borderWidth = it)) }, Modifier.fillMaxWidth(), 0f..24f, name = "Border width")
        FieldLabel("Before the camera is live", hint = "in the editor and for your own recorder")
        Segmented(z.fill, listOf(WebcamZone.FILL_THEME, WebcamZone.FILL_CHROMA, WebcamZone.FILL_NONE), { when (it) { WebcamZone.FILL_CHROMA -> "Green screen"; WebcamZone.FILL_NONE -> "Clear"; else -> "Panel" } }, { put(z.copy(fill = it)) }, small = true)
        Help("Green screen fills the zone with keying green, so a recorder of your own (OBS) can put your camera there. Clear leaves the slide showing through.")
    }
    // Written onto the title and end slides where the old name stood (B4); words written by hand stay.
    FieldLabel("Your name", hint = "on the title slide")
    MuInput(p.creator, { v -> present.commit(EditorEdits.setCreator(p, v), "Name", "creator") }, Modifier.fillMaxWidth(), dense = true, placeholder = "Channel or handle")
    Help("Changing it rewrites \"Deck profile · ${p.creator.ifBlank { "your name" }}\" on the title slide, and your old name wherever it stands on the title and end slides.")
}
