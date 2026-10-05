package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.stage.Box
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * [Presentation] to and from its file, `<data>/present/<id>.json`. Reading forgives the way
 * `PrepCodec` does — unknown keys from a newer build skipped, nulls take defaults — and goes
 * further, because a presentation is hours of work: a slide or element that will not read
 * is dropped alone, never the whole file.
 */
object PresentCodec {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val pretty = Json(json) { prettyPrint = true }

    fun encode(p: Presentation): String = pretty.encodeToString(Presentation.serializer(), p)

    /** The presentation in [text], or null when it is not one at all. */
    fun decode(text: String?): Presentation? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(Presentation.serializer(), text)
        } catch (e: Exception) {
            salvage(text)
        }?.let(Geometry::sane)?.let(SlideCamera::fold)
    }

    /** Slide by slide and element by element, keeping whatever reads. */
    private fun salvage(text: String): Presentation? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val slides = (root["slides"] as? JsonArray).orEmpty().mapNotNull { slide(it) }
        val bare = JsonObject(root.filterKeys { it != "slides" })
        val base = try {
            json.decodeFromJsonElement(Presentation.serializer(), bare)
        } catch (e: Exception) {
            val id = (root["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            Presentation(id = id, name = (root["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "Presentation")
        }
        return base.copy(slides = slides)
    }

    private fun slide(e: JsonElement): Slide? {
        val obj = e as? JsonObject ?: return null
        return try {
            json.decodeFromJsonElement(Slide.serializer(), obj)
        } catch (x: Exception) {
            val elements = (obj["elements"] as? JsonArray).orEmpty().mapNotNull { el ->
                try {
                    json.decodeFromJsonElement(Element.serializer(), el)
                } catch (y: Exception) {
                    null
                }
            }
            val bare = JsonObject(obj.filterKeys { it != "elements" })
            try {
                json.decodeFromJsonElement(Slide.serializer(), bare).copy(elements = elements)
            } catch (y: Exception) {
                null
            }
        }
    }

    fun element(e: Element): JsonObject = json.encodeToJsonElement(Element.serializer(), e).jsonObject

    fun elementOf(o: JsonElement): Element? = try {
        json.decodeFromJsonElement(Element.serializer(), o)
    } catch (e: Exception) {
        null
    }

    /** [base] with the keys of [patch] put over it, objects merged key by key. */
    fun merge(base: JsonObject, patch: JsonObject): JsonObject = buildJsonObject {
        base.forEach { (k, v) -> if (k !in patch) put(k, v) }
        patch.forEach { (k, v) ->
            val b = base[k]
            put(k, if (b is JsonObject && v is JsonObject) merge(b, v) else v)
        }
    }

    /** A small JSON object, for tests and fixtures. */
    internal fun obj(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }
}

/** Where an element stands on the canvas, its stage-anchored fractions resolved. */
object Geometry {
    fun box(e: Element, stage: Box): Box = if (e.anchor == Element.ANCHOR_STAGE) {
        Box(stage.x + e.x * stage.w, stage.y + e.y * stage.h, e.w * stage.w, e.h * stage.h)
    } else {
        Box(e.x, e.y, e.w, e.h)
    }

    /** [e] moved to [box] on the canvas, kept in its own anchoring. */
    fun place(e: Element, box: Box, stage: Box): Element = if (e.anchor == Element.ANCHOR_STAGE && stage.w > 0f && stage.h > 0f) {
        e.copy(x = (box.x - stage.x) / stage.w, y = (box.y - stage.y) / stage.h, w = box.w / stage.w, h = box.h / stage.h)
    } else {
        e.copy(x = box.x, y = box.y, w = box.w, h = box.h)
    }

    /**
     * [e] with geometry that can be drawn (1.0.73). kai's 1.0.72 crash was a stage-anchored box 500 stages
     * wide: canvas units written over a placeholder's fractions. So: numbers that are not numbers take the
     * defaults; a stage-anchored box past four stages is canvas units and is anchored so; sizes are held
     * within four canvases (or stages) and positions near the slide, so an element stays findable.
     */
    fun sane(e: Element): Element {
        fun num(v: Float, d: Float) = if (v.isNaN() || v.isInfinite()) d else v
        var x = num(e.x, 0f)
        var y = num(e.y, 0f)
        var w = num(e.w, if (e.anchor == Element.ANCHOR_STAGE) 0.5f else 400f)
        var h = num(e.h, if (e.anchor == Element.ANCHOR_STAGE) 0.3f else 200f)
        val rotation = num(e.rotation, 0f)
        var anchor = e.anchor
        if (anchor == Element.ANCHOR_STAGE && (kotlin.math.abs(x) > 4f || kotlin.math.abs(y) > 4f || w > 4f || h > 4f)) anchor = Element.ANCHOR_CANVAS
        if (anchor == Element.ANCHOR_STAGE) {
            x = x.coerceIn(-4f, 4f)
            y = y.coerceIn(-4f, 4f)
            w = w.coerceIn(0f, 4f)
            h = h.coerceIn(0f, 4f)
        } else {
            x = x.coerceIn(-Presentation.WIDTH * 2f, Presentation.WIDTH * 3f)
            y = y.coerceIn(-Presentation.HEIGHT * 2f, Presentation.HEIGHT * 3f)
            w = w.coerceIn(0f, Presentation.WIDTH * 4f)
            h = h.coerceIn(0f, Presentation.HEIGHT * 4f)
        }
        return if (x == e.x && y == e.y && w == e.w && h == e.h && rotation == e.rotation && anchor == e.anchor) e
        else e.copy(x = x, y = y, w = w, h = h, rotation = rotation, anchor = anchor)
    }

    /** Every element of [p] made [sane]. */
    fun sane(p: Presentation): Presentation {
        val slides = p.slides.map { s -> s.elements.map(::sane).let { els -> if (els == s.elements) s else s.copy(elements = els) } }
        return if (slides == p.slides) p else p.copy(slides = slides)
    }

    /** [e] in canvas units, whatever it was anchored to: what an edit by hand turns it into. */
    fun toCanvas(e: Element, stage: Box): Element {
        if (e.anchor != Element.ANCHOR_STAGE) return e
        val b = box(e, stage)
        return e.copy(anchor = Element.ANCHOR_CANVAS, x = b.x, y = b.y, w = b.w, h = b.h)
    }
}
