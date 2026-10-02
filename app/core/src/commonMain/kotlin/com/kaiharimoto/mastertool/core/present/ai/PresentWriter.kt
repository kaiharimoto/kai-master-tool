package com.kaiharimoto.mastertool.core.present.ai

import com.kaiharimoto.mastertool.core.present.Anim
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Fill
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Run
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideFonts
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.ThemeOverride
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.Transition
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * Ai's hands on a presentation (1.0.71, `present_edit`): a list of operations applied in order to
 * the presentation, each checked — every card named must be a card (by its printed name, through
 * [Context.card]), every slide and element referred to must exist — and the same edits the
 * editor's own clicks make ([PresentEdits]), so Ai and the person can never disagree on what an
 * edit means. One failing operation stops the rest; what came before it stays.
 */
object PresentWriter {
    class Context(
        /** A card by its name as printed (or a near miss), to its passcode; null when there is no such card. */
        val card: (String) -> Int?,
        val now: Long = 0L,
        val random: Random = Random.Default,
    )

    data class Result(val presentation: Presentation, val lines: List<String>, val ok: Boolean, val touched: String? = null)

    val ACTIONS = listOf(
        "set_props", "steps_from_groups", "set_steps", "add_slide", "edit_slide", "add_element", "update_element",
        "remove", "reorder", "duplicate_slide", "set_notes", "set_animation", "apply_theme",
    )

    fun apply(start: Presentation, ops: List<JsonObject>, ctx: Context): Result {
        var p = start
        val lines = ArrayList<String>()
        var touched: String? = null
        for ((n, op) in ops.withIndex()) {
            val action = op.str("action") ?: return Result(p, lines + "Operation ${n + 1} has no action.", false, touched)
            val step = try {
                one(p, action, op, ctx)
            } catch (e: Problem) {
                return Result(p, lines + "Operation ${n + 1} ($action): ${e.message}", false, touched)
            }
            p = step.first
            lines += "${n + 1}. ${step.second}"
            step.third?.let { touched = it }
        }
        return Result(p, lines, true, touched)
    }

    private class Problem(message: String) : Exception(message)

    private fun fail(message: String): Nothing = throw Problem(message)

    /** One operation: the presentation after it, a line saying what it did, and the slide it is about. */
    private fun one(p: Presentation, action: String, op: JsonObject, ctx: Context): Triple<Presentation, String, String?> = when (action) {
        "set_props" -> {
            var next = p
            op.str("name")?.let { next = next.copy(name = it) }
            op.str("style")?.let { s ->
                val style = styleOf(s) ?: fail("style must be spotlight, slides or build_up")
                next = next.copy(style = style)
            }
            op.str("theme")?.let { t -> next = next.copy(theme = themeOf(t)) }
            op.str("creator")?.let { next = next.copy(creator = it) }
            (op["webcam"] as? JsonObject)?.let { w -> next = next.copy(webcam = webcam(next.webcam, w)) }
            (op["colors"] as? JsonObject)?.let { colors ->
                val o = next.themeOverride ?: ThemeOverride()
                next = next.copy(themeOverride = o.copy(colors = o.colors + colors.mapValues { it.value.jsonPrimitive.content }))
            }
            op.str("heading_font")?.let { f -> next = next.copy(themeOverride = (next.themeOverride ?: ThemeOverride()).copy(headingFont = fontOf(f))) }
            op.str("body_font")?.let { f -> next = next.copy(themeOverride = (next.themeOverride ?: ThemeOverride()).copy(bodyFont = fontOf(f))) }
            op.bool("flat")?.let { f -> next = next.copy(themeOverride = (next.themeOverride ?: ThemeOverride()).copy(flat = f)) }
            Triple(next, "Set the presentation's ${op.keys.filter { it != "action" }.joinToString()}", null)
        }
        "apply_theme" -> {
            val t = themeOf(op.str("theme") ?: fail("give a theme"))
            Triple(p.copy(theme = t, themeOverride = null), "Theme: ${Themes.of(t).name}", null)
        }
        "steps_from_groups" -> {
            if (p.deck == null) fail("this presentation has no deck")
            val next = PresentEdits.stepsFromGroups(p, ctx.random)
            Triple(next, "Made ${next.slides.count { it.deck != null }} deck slides from the deck's groups", null)
        }
        "set_steps" -> {
            val deck = p.deck ?: fail("this presentation has no deck")
            val steps = (op["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: fail("give steps: [{title, cards, groups, note}]")
            val made = steps.map { s ->
                val focus = focusOf(s, p, ctx)
                Slide(PresentIds.next("s", ctx.random), title = focus.title, layout = SlideLayouts.DECK, deck = focus, notes = s.str("notes").orEmpty())
            }
            val first = p.slides.indexOfFirst { it.deck != null }
            val kept = p.slides.filter { it.deck == null }
            val at = if (first >= 0) p.slides.take(first).count { it.deck == null } else kept.size.coerceAtMost(1)
            if (deck.isEmpty) fail("the deck is empty")
            Triple(p.copy(slides = kept.take(at) + made + kept.drop(at)), "Set ${made.size} deck slides", made.firstOrNull()?.id)
        }
        "add_slide" -> {
            val layout = op.str("layout")?.uppercase()?.takeIf { it in SlideLayouts.all } ?: SlideLayouts.TITLE_BODY
            var s = SlideLayouts.slide(layout, ctx.random)
            if (layout == SlideLayouts.DECK && p.deck == null) fail("a deck slide needs the presentation's deck")
            op.str("title")?.let { s = s.copy(title = it) }
            (op["slots"] as? JsonObject)?.let { slots -> s = fillSlots(s, slots, ctx, p) }
            (op["elements"] as? JsonArray)?.forEach { e -> s = s.copy(elements = s.elements + elementOf(e as? JsonObject ?: fail("an element must be an object"), null, ctx)) }
            (op["deck"] as? JsonObject)?.let { d -> s = s.copy(deck = focusOf(d, p, ctx)) }
            op.str("notes")?.let { s = s.copy(notes = it) }
            (op["transition"])?.let { s = s.copy(transition = transitionOf(it, s.transition)) }
            if (s.title.isBlank()) s = s.copy(title = s.elements.firstOrNull { it.role == Element.ROLE_TITLE }?.plainText?.take(60).orEmpty())
            val after = op.str("after")?.let { slideIndex(p, it) } ?: (p.slides.lastIndex - if (p.slides.lastOrNull()?.layout == SlideLayouts.END_CARD) 1 else 0)
            Triple(PresentEdits.addSlide(p, s, after), "Added slide “${s.title.ifBlank { SlideLayouts.name(layout) }}” (id ${s.id})", s.id)
        }
        "edit_slide" -> {
            val s = slide(p, op)
            var t = s
            op.str("title")?.let { t = t.copy(title = it) }
            op.str("notes")?.let { t = t.copy(notes = it) }
            op.bool("hidden")?.let { t = t.copy(hidden = it) }
            op.str("camera")?.let { c -> t = t.copy(camera = cameraOf(c)) }
            op["background"]?.let { b -> t = t.copy(background = if (b is JsonNull) null else fillOf(b)) }
            op["transition"]?.let { t = t.copy(transition = transitionOf(it, t.transition)) }
            (op["deck"] as? JsonObject)?.let { d -> t = t.copy(deck = focusOf(d, p, ctx)) }
            (op["slots"] as? JsonObject)?.let { slots -> t = fillSlots(t, slots, ctx, p) }
            Triple(PresentEdits.updateSlide(p, s.id) { t }, "Changed slide ${p.indexOf(s.id) + 1}", s.id)
        }
        "set_notes" -> {
            val s = slide(p, op)
            val notes = op.str("notes") ?: fail("give notes")
            Triple(PresentEdits.updateSlide(p, s.id) { it.copy(notes = notes) }, "Speaker notes on slide ${p.indexOf(s.id) + 1}", s.id)
        }
        "add_element" -> {
            val s = slide(p, op)
            val e = elementOf(op["element"] as? JsonObject ?: fail("give element: {type, …}"), null, ctx)
            Triple(PresentEdits.addElements(p, s.id, listOf(e)), "Added ${Element.typeName(e.type).lowercase()} ${e.id} to slide ${p.indexOf(s.id) + 1}", s.id)
        }
        "update_element" -> {
            val s = slide(p, op)
            val id = op.str("element") ?: fail("give element: its id")
            val old = s.element(id) ?: fail("slide ${p.indexOf(s.id) + 1} has no element $id")
            val patch = op["patch"] as? JsonObject ?: fail("give patch: the fields to change")
            val next = elementOf(patch, old, ctx)
            Triple(PresentEdits.updateElements(p, s.id, setOf(id)) { next }, "Changed $id on slide ${p.indexOf(s.id) + 1}", s.id)
        }
        "remove" -> {
            val s = slide(p, op)
            val id = op.str("element")
            if (id != null) {
                if (s.element(id) == null) fail("slide ${p.indexOf(s.id) + 1} has no element $id")
                Triple(PresentEdits.removeElements(p, s.id, setOf(id)), "Removed $id", s.id)
            } else {
                if (p.slides.size <= 1) fail("a presentation keeps one slide")
                Triple(PresentEdits.removeSlides(p, setOf(s.id)), "Removed slide ${p.indexOf(s.id) + 1}", null)
            }
        }
        "reorder" -> {
            val s = slide(p, op)
            val to = op.int("to") ?: fail("give to: the slide number it moves to")
            Triple(PresentEdits.moveSlides(p, listOf(s.id), (to - 1).coerceIn(0, p.slides.size)), "Moved slide to $to", s.id)
        }
        "duplicate_slide" -> {
            val s = slide(p, op)
            val next = PresentEdits.duplicateSlides(p, setOf(s.id), ctx.random)
            val copy = next.slides[next.indexOf(s.id) + 1]
            Triple(next, "Duplicated slide ${p.indexOf(s.id) + 1} as ${copy.id}", copy.id)
        }
        "set_animation" -> {
            val s = slide(p, op)
            val id = op.str("element") ?: fail("give element: its id")
            val e = s.element(id) ?: fail("slide ${p.indexOf(s.id) + 1} has no element $id")
            if (op.bool("clear") == true) {
                Triple(PresentEdits.updateElements(p, s.id, setOf(id)) { it.copy(animations = emptyList()) }, "Cleared $id's builds", s.id)
            } else {
                val kind = when (op.str("kind")?.lowercase()) { "emphasis" -> Anim.EMPHASIS; "exit" -> Anim.EXIT; else -> Anim.ENTRANCE }
                val effect = op.str("effect")?.uppercase()?.takeIf { it in Anim.ENTRANCE_EFFECTS + Anim.EMPHASIS_EFFECTS } ?: if (kind == Anim.EMPHASIS) Anim.PULSE else Anim.RISE
                val trigger = when (op.str("trigger")?.lowercase()) { "with_previous" -> Anim.WITH_PREVIOUS; "after_previous" -> Anim.AFTER_PREVIOUS; else -> Anim.ON_CLICK }
                val order = (s.elements.flatMap { it.animations }.maxOfOrNull { it.order } ?: -1) + 1
                val a = Anim(PresentIds.next("a", ctx.random), kind, effect, trigger, op.int("duration_ms") ?: 450, op.int("delay_ms") ?: 0, order)
                Triple(PresentEdits.updateElements(p, s.id, setOf(id)) { it.copy(animations = it.animations + a) }, "Build on $id: ${Anim.effectName(effect)}, ${Anim.triggerName(trigger).lowercase()}", s.id)
            }
        }
        else -> fail("unknown action $action; the actions are ${ACTIONS.joinToString()}, add_module and refresh_module")
    }

    // ---- reading what Ai wrote -----------------------------------------------------

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

    fun styleOf(s: String): String? = when (s.lowercase().replace("-", "_").replace(" ", "_")) {
        "spotlight" -> Presentation.STYLE_SPOTLIGHT
        "slides" -> Presentation.STYLE_SLIDES
        "build_up", "buildup" -> Presentation.STYLE_BUILD_UP
        else -> null
    }

    private fun themeOf(t: String): String = Themes.named(t)?.id
        ?: fail("no theme $t; they are master, master-dark, ${Themes.all.drop(2).joinToString { it.id }}")

    private fun fontOf(f: String): String = SlideFonts.all.firstOrNull { it == f.lowercase() || SlideFonts.name(it).equals(f, ignoreCase = true) }
        ?: fail("no face $f; they are ${SlideFonts.all.joinToString { SlideFonts.name(it) }}")

    private fun cameraOf(c: String): String = when (c.uppercase()) {
        "DEFAULT", "" -> Slide.CAMERA_DEFAULT
        "HIDDEN", "OFF", "NONE" -> Slide.CAMERA_HIDDEN
        else -> c.uppercase().takeIf { it in WebcamZone.PRESETS } ?: fail("camera is default, hidden or a preset (${WebcamZone.PRESETS.joinToString()})")
    }

    /** [z] changed by Ai's `{enabled, preset, size, shape, fill, border}`; a failure says what is allowed. */
    fun webcamOf(z: WebcamZone, w: JsonObject): kotlin.Result<WebcamZone> = runCatching { webcam(z, w) }

    private fun webcam(z: WebcamZone, w: JsonObject): WebcamZone {
        var next = z
        w.bool("enabled")?.let { next = next.copy(enabled = it) }
        w.str("preset")?.let { s -> next = next.copy(preset = s.uppercase().takeIf { it in WebcamZone.PRESETS } ?: fail("preset is one of ${WebcamZone.PRESETS.joinToString()}")) }
        w.str("size")?.let { s -> next = next.copy(size = s.uppercase().take(1).takeIf { it in listOf("S", "M", "L") } ?: "M") }
        w.str("shape")?.let { s -> next = next.copy(shape = s.uppercase().takeIf { it in WebcamZone.SHAPES } ?: fail("shape is one of ${WebcamZone.SHAPES.joinToString()}")) }
        w.str("fill")?.let { s -> next = next.copy(fill = s.uppercase().takeIf { it in listOf(WebcamZone.FILL_NONE, WebcamZone.FILL_THEME, WebcamZone.FILL_CHROMA) } ?: WebcamZone.FILL_THEME) }
        w.str("border")?.let { next = next.copy(border = it) }
        return next
    }

    private fun slideIndex(p: Presentation, ref: String): Int {
        ref.toIntOrNull()?.let { n -> if (n in 1..p.slides.size) return n - 1 }
        val i = p.indexOf(ref)
        if (i < 0) fail("no slide $ref (give an id from present_state, or a number 1–${p.slides.size})")
        return i
    }

    private fun slide(p: Presentation, op: JsonObject): Slide = p.slides[slideIndex(p, op.str("slide") ?: op.int("slide")?.toString() ?: fail("give slide: its id or number"))]

    private fun cardsOf(v: JsonElement?, ctx: Context): List<Int> {
        val list = (v as? JsonArray) ?: return emptyList()
        val unknown = ArrayList<String>()
        val ids = list.mapNotNull { e ->
            val prim = e as? JsonPrimitive ?: return@mapNotNull null
            prim.intOrNull?.takeIf { !prim.isString } ?: ctx.card(prim.content) ?: run { unknown += prim.content; null }
        }
        if (unknown.isNotEmpty()) fail("not cards: ${unknown.joinToString()} (search_cards finds the printed names)")
        return ids
    }

    private fun focusOf(o: JsonObject, p: Presentation, ctx: Context): DeckFocus {
        val deck = p.deck ?: fail("this presentation has no deck")
        val groups = (o["groups"] as? JsonArray)?.map { g ->
            val name = g.jsonPrimitive.content
            deck.groups.firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) }?.id ?: fail("the deck has no group $name; its groups are ${deck.groups.joinToString { it.name }}")
        }.orEmpty()
        val cards = cardsOf(o["cards"], ctx)
        val missing = cards.filter { it !in deck.distinct }
        if (missing.isNotEmpty()) fail("not in the deck: card ${missing.joinToString()} — a deck slide talks about the deck's own cards")
        return DeckFocus(
            groups = groups,
            cards = cards,
            all = o.bool("all") ?: (groups.isEmpty() && cards.isEmpty()),
            title = o.str("title").orEmpty(),
            note = o.str("note").orEmpty(),
            notePlace = when (o.str("note_place")?.lowercase()) { "side" -> DeckFocus.NOTE_SIDE; "bottom", "under" -> DeckFocus.NOTE_BOTTOM; "none" -> DeckFocus.NOTE_NONE; else -> DeckFocus.NOTE_AUTO },
        )
    }

    private fun fillOf(v: JsonElement): Fill = when (v) {
        is JsonPrimitive -> Fill.solid(v.content)
        is JsonObject -> PresentCodec.json.decodeFromJsonElement(Fill.serializer(), v)
        else -> fail("a fill is a colour or {kind, color, stops, angle}")
    }

    private fun transitionOf(v: JsonElement, base: Transition): Transition = when (v) {
        is JsonPrimitive -> base.copy(kind = v.content.uppercase().takeIf { it in Transition.KINDS } ?: fail("a transition is one of ${Transition.KINDS.joinToString()}"))
        is JsonObject -> base.copy(
            kind = v.str("kind")?.uppercase()?.takeIf { it in Transition.KINDS } ?: base.kind,
            durationMs = v.int("duration_ms") ?: base.durationMs,
            direction = v.str("direction")?.uppercase() ?: base.direction,
        )
        else -> base
    }

    /**
     * A slide's placeholders filled by role: `title`, `subtitle`, `body` (the first body or
     * the left column), `right`, `caption`, and `cards` / `card` for a layout's card slots.
     */
    private fun fillSlots(s: Slide, slots: JsonObject, ctx: Context, p: Presentation): Slide {
        val els = s.elements.toMutableList()
        fun firstIndex(pred: (Element) -> Boolean) = els.indexOfFirst(pred)
        fun setText(i: Int, text: String) {
            if (i < 0) return
            val e = els[i]
            val style = e.paras.firstOrNull()?.runs?.firstOrNull()?.style ?: RunStyle()
            val align = e.paras.firstOrNull()?.align ?: Para.ALIGN_LEFT
            els[i] = e.copy(paras = parasOf(text, style, align))
        }
        slots.str("title")?.let { setText(firstIndex { it.role == Element.ROLE_TITLE }, it) }
        slots.str("subtitle")?.let { setText(firstIndex { it.role == Element.ROLE_SUBTITLE }, it) }
        slots.str("caption")?.let { setText(firstIndex { it.role == Element.ROLE_CAPTION }, it) }
        val bodies = els.indices.filter { els[it].role == Element.ROLE_BODY }
        (slots.str("body") ?: slots.str("left"))?.let { setText(bodies.getOrElse(0) { -1 }, it) }
        slots.str("right")?.let { setText(bodies.getOrElse(1) { -1 }, it) }
        slots["cards"]?.let { v -> val ids = cardsOf(v, ctx); firstIndex { it.type == Element.CARDS }.takeIf { it >= 0 }?.let { els[it] = els[it].copy(cards = ids) } }
        slots["card"]?.let { v ->
            val ids = cardsOf(if (v is JsonArray) v else JsonArray(listOf(v)), ctx)
            firstIndex { it.type == Element.CARD }.takeIf { it >= 0 }?.let { els[it] = els[it].copy(cards = ids.take(1)) }
        }
        (slots["number"] as? JsonObject)?.let { n ->
            firstIndex { it.type == Element.STAT }.takeIf { it >= 0 }?.let { i ->
                els[i] = els[i].copy(stat = com.kaiharimoto.mastertool.core.present.Stat(n.str("value").orEmpty(), n.str("label").orEmpty(), n.str("sub").orEmpty()))
            }
        }
        return s.copy(elements = els, title = slots.str("title")?.take(60) ?: s.title)
    }

    private fun parasOf(text: String, style: RunStyle, align: String): List<Para> =
        text.split('\n').map { line ->
            val bullet = line.trimStart().startsWith("- ") || line.trimStart().startsWith("• ")
            val words = if (bullet) line.trimStart().drop(2) else line
            Para(listOf(Run(words, style)), align, list = if (bullet) Para.LIST_BULLET else Para.LIST_NONE)
        }

    /**
     * An element from Ai's JSON — the stored fields as they are, plus a few that are easier to
     * write: `text` (lines, `- ` for a bullet), `cards` by name, `box` [x, y, w, h], `color`,
     * `size`, `bold`, `align`, and `fill` as a colour. Over [base] when changing one.
     */
    fun elementOf(o: JsonObject, base: Element?, ctx: Context): Element {
        val easy = setOf("text", "cards", "box", "color", "size", "bold", "align", "fill", "font", "italic")
        val raw = JsonObject(o.filterKeys { it !in easy })
        val merged = if (base != null) PresentCodec.merge(PresentCodec.element(base), raw) else buildJsonObject {
            put("id", JsonPrimitive(PresentIds.next("e", ctx.random)))
            raw.forEach { (k, v) -> put(k, v) }
        }
        var e = PresentCodec.elementOf(merged) ?: fail("that element does not read; its fields are the ones present_state shows")
        if (base == null && e.type.uppercase() !in Element.TYPES) fail("type is one of ${Element.TYPES.joinToString()}")
        e = e.copy(type = e.type.uppercase(), id = base?.id ?: e.id)
        (o["box"] as? JsonArray)?.let { b ->
            val v = b.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
            if (v.size == 4) e = e.copy(x = v[0], y = v[1], w = v[2], h = v[3])
        }
        o.str("text")?.let { t ->
            val style = e.paras.firstOrNull()?.runs?.firstOrNull()?.style ?: RunStyle()
            e = e.copy(paras = parasOf(t, style, e.paras.firstOrNull()?.align ?: Para.ALIGN_LEFT))
        }
        o["cards"]?.let { e = e.copy(cards = cardsOf(it, ctx)) }
        fun restyle(change: (RunStyle) -> RunStyle) { e = e.copy(paras = e.paras.map { p -> p.copy(runs = p.runs.map { r -> r.copy(style = change(r.style)) }) }) }
        o.str("color")?.let { c -> restyle { it.copy(color = c) } }
        o.float("size")?.let { s -> restyle { it.copy(size = s) } }
        o.bool("bold")?.let { b -> restyle { it.copy(weight = if (b) 700 else 400) } }
        o.bool("italic")?.let { b -> restyle { it.copy(italic = b) } }
        o.str("font")?.let { f -> val id = fontOf(f); restyle { it.copy(font = id) } }
        o.str("align")?.let { a ->
            val align = when (a.lowercase()) { "center", "centre" -> Para.ALIGN_CENTER; "right" -> Para.ALIGN_RIGHT; else -> Para.ALIGN_LEFT }
            e = e.copy(paras = e.paras.map { it.copy(align = align) })
        }
        o["fill"]?.let { f -> e = e.copy(fill = if (f is JsonNull) null else fillOf(f)) }
        if (e.anchor == Element.ANCHOR_CANVAS && e.x <= 1.01f && e.y <= 1.01f && e.w <= 1.01f && e.h <= 1.01f && (o["box"] != null || o["w"] != null)) {
            // Fractions given without saying so: of the stage, as the layouts are.
            e = e.copy(anchor = Element.ANCHOR_STAGE)
        }
        if (e.type == Element.CARD && e.cards.size > 1) e = e.copy(type = Element.CARDS)
        return e
    }
}
