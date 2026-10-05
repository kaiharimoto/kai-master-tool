package com.kaiharimoto.mastertool.core.present.edit

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Run
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.stage.Box
import kotlin.math.min

/**
 * The editor's own edits (the editor's audit, track B), pure and tested: what Undo restyle puts back,
 * the creator's name on the slides, where something inserted lands, how a deck is retold in another
 * style, and a number typed into a field.
 */
object EditorEdits {

    // ---- Undo restyle: the look, and nothing else (B3) ---------------------------------

    /**
     * [now] with the look of [before] put back: the theme and its overrides, each slide's background, and
     * each element's fills, borders, shadows, rounding, chart colours and the faces and colours of its
     * words — matched by id. Everything else is [now]'s: slides added after the restyle, words typed,
     * notes, order and deletions all stay. Before this, Undo restyle put back the whole slide list, and
     * ten minutes of notes written after a restyle went with it.
     */
    fun restoreLook(now: Presentation, before: Presentation): Presentation {
        val oldSlides = before.slides.associateBy { it.id }
        return now.copy(
            theme = before.theme,
            themeOverride = before.themeOverride,
            slides = now.slides.map { s ->
                val o = oldSlides[s.id]
                if (o == null) {
                    s
                } else {
                    val oldElements = o.elements.associateBy { it.id }
                    s.copy(background = o.background, elements = s.elements.map { e -> oldElements[e.id]?.let { lookOf(it, e) } ?: e })
                }
            },
        )
    }

    private fun lookOf(from: Element, onto: Element): Element = onto.copy(
        fill = from.fill,
        stroke = from.stroke,
        shadow = from.shadow,
        corner = from.corner,
        paras = onto.paras.mapIndexed { i, p ->
            val old = from.paras.getOrNull(i) ?: from.paras.lastOrNull()
            if (old == null) p else p.copy(runs = p.runs.mapIndexed { j, r -> lookOf(old.runs.getOrNull(j) ?: old.runs.lastOrNull(), r) })
        },
        chart = onto.chart?.let { c ->
            val old = from.chart ?: return@let c
            c.copy(series = c.series.mapIndexed { k, se -> se.copy(color = old.series.getOrNull(k)?.color) })
        },
    )

    private fun lookOf(from: Run?, onto: Run): Run {
        if (from == null) return onto
        val f = from.style
        return onto.copy(style = onto.style.copy(font = f.font, color = f.color, highlight = f.highlight, weight = f.weight, tracking = f.tracking, caps = f.caps))
    }

    // ---- the creator's name on the slides (B4) -----------------------------------------

    /** The title slide's line under the deck's name. */
    fun creatorLine(creator: String): String = if (creator.isBlank()) "Deck profile" else "Deck profile · ${creator.trim()}"

    /**
     * [p] with [name] as its creator, written where the old name was: on the title and end slides, the
     * line the profile was made with ([creatorLine]) and the old name wherever it stands as a word. Typed
     * a letter at a time, the line follows each letter. Words the person wrote themselves are left alone.
     */
    fun setCreator(p: Presentation, name: String): Presentation {
        val old = p.creator
        if (old == name) return p
        val oldLine = creatorLine(old)
        val newLine = creatorLine(name)
        val slides = p.slides.map { s ->
            if (s.layout != SlideLayouts.TITLE && s.layout != SlideLayouts.END_CARD) {
                s
            } else {
                s.copy(elements = s.elements.map { e ->
                    if (e.paras.isEmpty()) e else e.copy(paras = e.paras.map { pa -> pa.copy(runs = pa.runs.map { r -> r.copy(text = renamed(r.text, old, name, oldLine, newLine)) }) })
                })
            }
        }
        return p.copy(creator = name, slides = slides)
    }

    private fun renamed(text: String, old: String, name: String, oldLine: String, newLine: String): String = when {
        text == oldLine -> newLine
        old.trim().length >= 2 && name.isNotBlank() -> replaceWord(text, old.trim(), name.trim())
        else -> text
    }

    /** Every whole-word [word] in [text] replaced by [by]: "kai" in "— kai" but never "an" in "Thanks". */
    internal fun replaceWord(text: String, word: String, by: String): String {
        if (word.isEmpty()) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val at = text.indexOf(word, i)
            if (at < 0) break
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + word.length)
            val whole = (before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())
            out.append(text, i, at)
            out.append(if (whole) by else word)
            i = at + word.length
        }
        out.append(text, i.coerceAtMost(text.length), text.length)
        return out.toString()
    }

    // ---- where something inserted lands (I5) ---------------------------------------------

    /**
     * A box [w] × [h] in canvas units placed on [stage] — the room the camera leaves — centred, and
     * shrunk (never grown) to [fill] of it: an inserted element never lands under the camera.
     */
    fun placeIn(stage: Box, w: Float, h: Float, fill: Float = 0.9f): Box {
        val k = min(1f, min(stage.w * fill / w.coerceAtLeast(1f), stage.h * fill / h.coerceAtLeast(1f)))
        val bw = w * k
        val bh = h * k
        return Box(stage.cx - bw / 2f, stage.cy - bh / 2f, bw, bh)
    }

    // ---- a deck retold in another style (I9) ----------------------------------------------

    /**
     * [p] told in [style], its whole-deck step moved with it: Build-up ends on the whole deck, Spotlight
     * and Slides open with it (what [PresentEdits.stepsFromGroups] makes). Other slides and every deck
     * slide's notes and builds are untouched.
     */
    fun retell(p: Presentation, style: String): Presentation {
        if (style == p.style) return p
        val told = p.copy(style = style)
        val deck = p.slides.indices.filter { p.slides[it].deck != null }
        if (deck.size < 2) return told
        fun whole(i: Int) = p.slides[i].deck?.let { it.all && it.sections.isEmpty() } == true
        val first = deck.first()
        val last = deck.last()
        return when {
            style == Presentation.STYLE_BUILD_UP && whole(first) && !whole(last) ->
                PresentEdits.moveSlides(told, listOf(p.slides[first].id), last + 1)
            style != Presentation.STYLE_BUILD_UP && whole(last) && !whole(first) ->
                PresentEdits.moveSlides(told, listOf(p.slides[last].id), first)
            else -> told
        }
    }

    /** How many deck slides "Slides from groups" would replace: the number its confirmation names. */
    fun deckSlides(p: Presentation): Int = p.slides.count { it.deck != null }

    /** Whether any of them holds work of the person's own: notes, a note on the slide, builds or elements. */
    fun deckSlidesHoldWork(p: Presentation): Boolean = p.slides.any { s ->
        s.deck != null && (s.notes.isNotBlank() || s.deck.note.isNotBlank() || s.elements.isNotEmpty())
    }

    // ---- a number typed (B1) ---------------------------------------------------------------

    /**
     * The number in [text], held within [min]..[max], or null when it is not a number yet. Read only
     * when the field is let go or Enter is pressed: read on every key, "500" became 12 at its first
     * digit, "120" at its second and 1200 at its third.
     */
    fun number(text: String, min: Float = -Float.MAX_VALUE, max: Float = Float.MAX_VALUE): Float? {
        val v = text.trim().replace(',', '.').toFloatOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return v.coerceIn(min, max)
    }
}

/**
 * Slides picked in the sorter (the editor's audit, M3): a click opens one, Ctrl (⌘) adds or takes one
 * out, Shift picks the run from the slide in view. [current] is the slide in view; [picked] the others
 * picked with it — so copy, duplicate, delete and a drag act on [all].
 */
data class SlidePicks(val current: String, val picked: Set<String> = emptySet()) {
    /** Every picked slide, the one in view included, in the show's [order]. */
    fun all(order: List<String>): List<String> = order.filter { it == current || it in picked }

    /** After a click on [id]: with [range] (Shift) the run from [current]; with [toggle] (Ctrl) one in or out. */
    fun click(order: List<String>, id: String, range: Boolean, toggle: Boolean): SlidePicks = when {
        range -> {
            val a = order.indexOf(current)
            val b = order.indexOf(id)
            if (a < 0 || b < 0) SlidePicks(id) else SlidePicks(id, order.subList(minOf(a, b), maxOf(a, b) + 1).toSet() - id)
        }
        toggle -> {
            val set = (picked + current).let { if (id in it) it - id else it + id }
            when {
                set.isEmpty() -> SlidePicks(id)
                id in set -> SlidePicks(id, set - id)
                else -> {
                    val next = order.firstOrNull { it in set } ?: id
                    SlidePicks(next, set - next)
                }
            }
        }
        else -> SlidePicks(id)
    }
}
