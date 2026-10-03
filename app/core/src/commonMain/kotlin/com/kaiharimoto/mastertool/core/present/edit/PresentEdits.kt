package com.kaiharimoto.mastertool.core.present.edit

import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.DeckSnapshot
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.SlideLayouts
import com.kaiharimoto.mastertool.core.present.SnapGroup
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random

/**
 * Every change to a presentation as a pure function, shared by the editor's clicks and Ai's
 * tools so the two can never disagree. Nothing here knows about undo: the caller records the
 * value before ([EditHistory]).
 */
object PresentEdits {

    /** The deck as it stands, for a presentation to keep. */
    fun snapshot(
        deck: Deck,
        groups: DeckGroups,
        name: String,
        deckId: String?,
        arrangement: String,
        arts: Map<Int, Int>,
        palette: Int,
        now: Long,
    ): DeckSnapshot = DeckSnapshot(
        deckId = deckId,
        name = name,
        main = deck.main.map { it.value },
        extra = deck.extra.map { it.value },
        side = deck.side.map { it.value },
        groups = groups.ordered().map { SnapGroup(it.id, it.name, it.color, it.order) },
        assignments = groups.assignments.mapKeys { it.key.value },
        fitted = groups.fitted.map { it.value },
        arrangement = arrangement,
        arts = arts,
        palette = palette,
        takenAt = now,
    )

    /**
     * A new deck profile: a title, the whole deck, one deck slide per group (then the Extra
     * and Side Decks), and an end card — the skeleton a creator, or Ai, fills in.
     */
    fun newProfile(
        id: String,
        name: String,
        deck: DeckSnapshot?,
        style: String,
        theme: String,
        webcam: WebcamZone,
        creator: String,
        now: Long,
        random: Random = Random.Default,
    ): Presentation {
        val title = SlideLayouts.slide(SlideLayouts.TITLE, random).let { s ->
            s.copy(
                title = "Title",
                elements = s.elements.mapIndexed { i, e ->
                    // The layout's own style (the headline's size) kept; only the words change.
                    val style = e.paras.firstOrNull()?.runs?.firstOrNull()?.style ?: RunStyle()
                    when (i) {
                        0 -> e.copy(paras = listOf(Para.of(deck?.name?.ifBlank { null } ?: name, style, Para.ALIGN_CENTER)))
                        1 -> e.copy(paras = listOf(Para.of(if (creator.isBlank()) "Deck profile" else "Deck profile · $creator", style, Para.ALIGN_CENTER)))
                        else -> e
                    }
                },
            )
        }
        val p = Presentation(
            id = id, name = name, createdAt = now, updatedAt = now, style = style, deck = deck,
            theme = theme, webcam = webcam, creator = creator,
            slides = listOf(title),
        )
        val withDeck = if (deck != null && !deck.isEmpty) stepsFromGroups(p, random) else p
        return withDeck.copy(slides = withDeck.slides + SlideLayouts.slide(SlideLayouts.END_CARD, random).copy(title = "End card"))
    }

    /**
     * The deck slides made from the deck's groups: the whole deck first, then each group in
     * order, the cards in no group, the Extra Deck and the Side Deck — replacing the deck
     * slides there were, kept where the first one stood.
     */
    fun stepsFromGroups(p: Presentation, random: Random = Random.Default): Presentation {
        val deck = p.deck ?: return p
        val steps = ArrayList<Slide>()
        fun deckSlide(title: String, focus: DeckFocus) = Slide(
            id = PresentIds.next("s", random), title = title, layout = SlideLayouts.DECK, deck = focus.copy(title = title),
        )
        // Build-up ends on the whole deck; the other styles open with it.
        val buildUp = p.style == Presentation.STYLE_BUILD_UP
        if (!buildUp) steps += deckSlide("The deck", DeckFocus(all = true))
        val inMain = deck.main.toSet()
        for (g in deck.ordered()) {
            val cards = deck.assignments.filterValues { it == g.id }.keys
            if (cards.none { it in inMain || it in deck.extra || it in deck.side }) continue
            steps += deckSlide(g.name, DeckFocus(groups = listOf(g.id)))
        }
        val loose = deck.main.distinct().filter { deck.groupOf(it) == null }
        if (loose.isNotEmpty() && deck.groups.isNotEmpty()) steps += deckSlide("The rest", DeckFocus(cards = loose))
        if (deck.groups.isEmpty()) {
            if (deck.main.isNotEmpty()) steps += deckSlide("Main Deck", DeckFocus(all = true, sections = listOf(DeckSnapshot.SECTION_MAIN)))
        }
        val extraLoose = deck.extra.distinct().filter { deck.groupOf(it) == null || deck.groups.isEmpty() }
        if (extraLoose.isNotEmpty()) steps += deckSlide("Extra Deck", DeckFocus(cards = extraLoose))
        val sideLoose = deck.side.distinct().filter { deck.groupOf(it) == null || deck.groups.isEmpty() }
        if (sideLoose.isNotEmpty()) steps += deckSlide("Side Deck", DeckFocus(cards = sideLoose))
        if (buildUp) steps += deckSlide("The full deck", DeckFocus(all = true))
        val firstDeck = p.slides.indexOfFirst { it.deck != null }
        val kept = p.slides.filter { it.deck == null }
        val at = if (firstDeck >= 0) p.slides.take(firstDeck).count { it.deck == null } else kept.size.coerceAtMost(1)
        return p.copy(slides = kept.take(at) + steps + kept.drop(at))
    }

    // ---- slides ------------------------------------------------------------------

    fun addSlide(p: Presentation, slide: Slide, after: Int? = null): Presentation {
        val at = ((after ?: p.slides.lastIndex) + 1).coerceIn(0, p.slides.size)
        return p.copy(slides = p.slides.take(at) + slide + p.slides.drop(at))
    }

    fun updateSlide(p: Presentation, id: String, change: (Slide) -> Slide): Presentation =
        p.copy(slides = p.slides.map { if (it.id == id) change(it) else it })

    fun removeSlides(p: Presentation, ids: Set<String>): Presentation = p.copy(slides = p.slides.filterNot { it.id in ids })

    /** Copies of [ids] each straight after its original, with fresh ids throughout. */
    fun duplicateSlides(p: Presentation, ids: Set<String>, random: Random = Random.Default): Presentation =
        p.copy(slides = p.slides.flatMap { s -> if (s.id in ids) listOf(s, fresh(s, random)) else listOf(s) })

    /** [slide] with new ids for itself, its elements and their builds. */
    fun fresh(slide: Slide, random: Random = Random.Default): Slide {
        val groups = HashMap<String, String>()
        return slide.copy(
            id = PresentIds.next("s", random),
            elements = slide.elements.map { freshElement(it, groups, random) },
        )
    }

    fun freshElement(e: Element, groups: MutableMap<String, String> = HashMap(), random: Random = Random.Default): Element = e.copy(
        id = PresentIds.next("e", random),
        group = e.group?.let { g -> groups.getOrPut(g) { PresentIds.next("g", random) } },
        animations = e.animations.map { it.copy(id = PresentIds.next("a", random)) },
    )

    /** The slides [ids] moved together so the first of them stands at [to]. */
    fun moveSlides(p: Presentation, ids: List<String>, to: Int): Presentation {
        val moving = p.slides.filter { it.id in ids }
        if (moving.isEmpty()) return p
        val rest = p.slides.filterNot { it.id in ids }
        val before = p.slides.take(to.coerceIn(0, p.slides.size)).count { it.id !in ids }
        return p.copy(slides = rest.take(before) + moving + rest.drop(before))
    }

    // ---- elements ----------------------------------------------------------------

    fun addElements(p: Presentation, slideId: String, elements: List<Element>): Presentation =
        updateSlide(p, slideId) { s -> s.copy(elements = s.elements + elements) }

    /**
     * [ids] on slide [slideId] changed by [change]. An element a module made is marked edited,
     * so refreshing the module leaves the person's change alone.
     */
    fun updateElements(p: Presentation, slideId: String, ids: Set<String>, change: (Element) -> Element): Presentation =
        updateSlide(p, slideId) { s ->
            s.copy(elements = s.elements.map { e ->
                if (e.id in ids) change(e).let { if (s.module != null) it.copy(edited = true) else it } else e
            })
        }

    fun removeElements(p: Presentation, slideId: String, ids: Set<String>): Presentation =
        updateSlide(p, slideId) { s -> s.copy(elements = s.elements.filterNot { it.id in ids }) }

    /** Copies of [ids], nudged [offset] so they show, added on top; with their new ids. */
    fun duplicateElements(p: Presentation, slideId: String, ids: Set<String>, offset: Float = 24f, random: Random = Random.Default): Pair<Presentation, List<String>> {
        val slide = p.slide(slideId) ?: return p to emptyList()
        val groups = HashMap<String, String>()
        val copies = slide.elements.filter { it.id in ids }.map { e ->
            freshElement(e, groups, random).let {
                if (it.anchor == Element.ANCHOR_STAGE) it.copy(x = it.x + 0.02f, y = it.y + 0.02f) else it.copy(x = it.x + offset, y = it.y + offset)
            }
        }
        return addElements(p, slideId, copies) to copies.map { it.id }
    }

    const val FORWARD = "FORWARD"
    const val BACKWARD = "BACKWARD"
    const val FRONT = "FRONT"
    const val BACK = "BACK"

    /** [ids] moved up or down the drawing order (the element list, last on top). */
    fun order(p: Presentation, slideId: String, ids: Set<String>, how: String): Presentation = updateSlide(p, slideId) { s ->
        val list = s.elements.toMutableList()
        when (how) {
            FRONT -> s.copy(elements = list.filterNot { it.id in ids } + list.filter { it.id in ids })
            BACK -> s.copy(elements = list.filter { it.id in ids } + list.filterNot { it.id in ids })
            FORWARD -> {
                for (i in list.indices.reversed()) {
                    if (list[i].id in ids && i + 1 < list.size && list[i + 1].id !in ids) {
                        val t = list[i]; list[i] = list[i + 1]; list[i + 1] = t
                    }
                }
                s.copy(elements = list)
            }
            BACKWARD -> {
                for (i in list.indices) {
                    if (list[i].id in ids && i > 0 && list[i - 1].id !in ids) {
                        val t = list[i]; list[i] = list[i - 1]; list[i - 1] = t
                    }
                }
                s.copy(elements = list)
            }
            else -> s
        }
    }

    /** [ids] made one group; ungrouping clears it. */
    fun group(p: Presentation, slideId: String, ids: Set<String>, random: Random = Random.Default): Presentation {
        if (ids.size < 2) return p
        val g = PresentIds.next("g", random)
        return updateElements(p, slideId, ids) { it.copy(group = g) }
    }

    fun ungroup(p: Presentation, slideId: String, ids: Set<String>): Presentation =
        updateElements(p, slideId, ids) { it.copy(group = null) }

    /** [ids] widened to every element sharing a group with one of them: what a click selects. */
    fun withGroups(slide: Slide, ids: Set<String>): Set<String> {
        val groups = slide.elements.filter { it.id in ids }.mapNotNull { it.group }.toSet()
        if (groups.isEmpty()) return ids
        return ids + slide.elements.filter { it.group in groups }.map { it.id }
    }
}

/**
 * Copy and paste of elements and slides as text, `NMTSLIDE1:` then JSON, so it travels by
 * the ordinary clipboard between presentations, windows and devices.
 */
object SlideClip {
    const val ELEMENTS = "NMTELEM1:"
    const val SLIDES = "NMTSLIDE1:"

    fun elements(list: List<Element>): String = ELEMENTS + PresentCodec.json.encodeToString(ListSerializer(Element.serializer()), list)

    fun slides(list: List<Slide>): String = SLIDES + PresentCodec.json.encodeToString(ListSerializer(Slide.serializer()), list)

    fun readElements(text: String?): List<Element>? {
        if (text == null || !text.startsWith(ELEMENTS)) return null
        return try {
            PresentCodec.json.decodeFromString(ListSerializer(Element.serializer()), text.removePrefix(ELEMENTS)).map(Geometry::sane)
        } catch (e: Exception) {
            null
        }
    }

    fun readSlides(text: String?): List<Slide>? {
        if (text == null || !text.startsWith(SLIDES)) return null
        return try {
            PresentCodec.json.decodeFromString(ListSerializer(Slide.serializer()), text.removePrefix(SLIDES)).map { sl -> sl.copy(elements = sl.elements.map(Geometry::sane)) }
        } catch (e: Exception) {
            null
        }
    }
}
