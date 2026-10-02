package com.kaiharimoto.mastertool.core.present

import kotlin.random.Random

/** Fresh ids for presentations, slides, elements and builds: short, random, stable once given. */
object PresentIds {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun next(prefix: String, random: Random = Random.Default): String =
        prefix + (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}

/**
 * The layouts a new slide can start from, as Google Slides offers them: placeholders
 * anchored to the stage (the room the webcam leaves), so a slide made from one re-flows
 * when the camera moves. Each is ordinary elements — once made, everything is editable.
 */
object SlideLayouts {
    const val BLANK = "BLANK"
    const val TITLE = "TITLE"
    const val TITLE_BODY = "TITLE_BODY"
    const val TWO_COLUMN = "TWO_COLUMN"
    const val SECTION = "SECTION"
    const val BIG_NUMBER = "BIG_NUMBER"
    const val CARD_FOCUS = "CARD_FOCUS"
    const val CARDS_ROW = "CARDS_ROW"
    const val IMAGE_FULL = "IMAGE_FULL"
    const val QUOTE = "QUOTE"
    const val CAMERA_BIG = "CAMERA_BIG"
    const val END_CARD = "END_CARD"
    const val DECK = "DECK"

    val all = listOf(
        TITLE, TITLE_BODY, TWO_COLUMN, SECTION, BIG_NUMBER, CARD_FOCUS, CARDS_ROW,
        IMAGE_FULL, QUOTE, CAMERA_BIG, END_CARD, DECK, BLANK,
    )

    fun name(layout: String): String = when (layout) {
        TITLE -> "Title"
        TITLE_BODY -> "Title and body"
        TWO_COLUMN -> "Two columns"
        SECTION -> "Section header"
        BIG_NUMBER -> "Big number"
        CARD_FOCUS -> "Card focus"
        CARDS_ROW -> "Row of cards"
        IMAGE_FULL -> "Full picture"
        QUOTE -> "Quote"
        CAMERA_BIG -> "Big camera"
        END_CARD -> "End card"
        DECK -> "Deck"
        else -> "Blank"
    }

    /** Every run of this text set at [size]: a layout's headline, larger than the role's. */
    private fun Element.big(size: Float): Element = copy(paras = paras.map { p -> p.copy(runs = p.runs.map { it.copy(style = it.style.copy(size = size)) }) })

    /** A new slide of [layout]: its placeholders, and for [DECK] the whole deck as its focus. */
    fun slide(layout: String, random: Random = Random.Default): Slide {
        val id = PresentIds.next("s", random)
        fun el(type: String, x: Float, y: Float, w: Float, h: Float, block: Element.() -> Element = { this }): Element =
            Element(PresentIds.next("e", random), type, x, y, w, h, anchor = Element.ANCHOR_STAGE).block()
        fun text(role: String, words: String, align: String = Para.ALIGN_LEFT, v: String = Element.V_TOP) =
            { e: Element -> e.copy(paras = listOf(Para.of(words, align = align)), role = role, vAlign = v) }
        val elements: List<Element> = when (layout) {
            TITLE -> listOf(
                el(Element.TEXT, 0.04f, 0.22f, 0.92f, 0.34f, text(Element.ROLE_TITLE, "Deck profile", Para.ALIGN_CENTER, Element.V_BOTTOM)).big(132f),
                el(Element.TEXT, 0.1f, 0.6f, 0.8f, 0.12f, text(Element.ROLE_SUBTITLE, "Subtitle", Para.ALIGN_CENTER)),
            )
            TITLE_BODY -> listOf(
                el(Element.TEXT, 0f, 0f, 1f, 0.16f, text(Element.ROLE_TITLE, "Title", v = Element.V_MIDDLE)),
                el(Element.TEXT, 0f, 0.2f, 1f, 0.78f, text(Element.ROLE_BODY, "Body")),
            )
            TWO_COLUMN -> listOf(
                el(Element.TEXT, 0f, 0f, 1f, 0.16f, text(Element.ROLE_TITLE, "Title", v = Element.V_MIDDLE)),
                el(Element.TEXT, 0f, 0.2f, 0.48f, 0.78f, text(Element.ROLE_BODY, "Left")),
                el(Element.TEXT, 0.52f, 0.2f, 0.48f, 0.78f, text(Element.ROLE_BODY, "Right")),
            )
            SECTION -> listOf(
                el(Element.SHAPE, 0f, 0.44f, 0.012f, 0.2f) { copy(fill = Fill.solid("@accent")) },
                el(Element.TEXT, 0.04f, 0.3f, 0.9f, 0.26f, text(Element.ROLE_TITLE, "Section", v = Element.V_BOTTOM)).big(112f),
                el(Element.TEXT, 0.04f, 0.57f, 0.9f, 0.1f, text(Element.ROLE_SUBTITLE, "What comes next")),
            )
            BIG_NUMBER -> listOf(
                el(Element.STAT, 0.1f, 0.18f, 0.8f, 0.5f) { copy(stat = Stat("87%", "to open a starter", "going first")) },
                el(Element.TEXT, 0.1f, 0.74f, 0.8f, 0.12f, text(Element.ROLE_CAPTION, "Why it matters", Para.ALIGN_CENTER)),
            )
            CARD_FOCUS -> listOf(
                el(Element.CARD, 0f, 0.02f, 0.34f, 0.96f),
                el(Element.TEXT, 0.4f, 0.04f, 0.6f, 0.18f, text(Element.ROLE_TITLE, "Card name", v = Element.V_MIDDLE)),
                el(Element.TEXT, 0.4f, 0.26f, 0.6f, 0.7f, text(Element.ROLE_BODY, "What it does for the deck")),
            )
            CARDS_ROW -> listOf(
                el(Element.TEXT, 0f, 0f, 1f, 0.16f, text(Element.ROLE_TITLE, "Title", v = Element.V_MIDDLE)),
                el(Element.CARDS, 0f, 0.22f, 1f, 0.6f) { copy(cardLabels = true) },
                el(Element.TEXT, 0f, 0.86f, 1f, 0.12f, text(Element.ROLE_CAPTION, "Caption", Para.ALIGN_CENTER)),
            )
            IMAGE_FULL -> listOf(
                Element(PresentIds.next("e", random), Element.IMAGE, 0f, 0f, Presentation.WIDTH, Presentation.HEIGHT),
                el(Element.TEXT, 0f, 0.84f, 1f, 0.14f, text(Element.ROLE_SUBTITLE, "Caption")),
            )
            QUOTE -> listOf(
                el(Element.TEXT, 0.08f, 0.2f, 0.84f, 0.46f, text(Element.ROLE_TITLE, "“A line worth remembering.”", Para.ALIGN_CENTER, Element.V_MIDDLE)),
                el(Element.TEXT, 0.2f, 0.7f, 0.6f, 0.1f, text(Element.ROLE_CAPTION, "— Who said it", Para.ALIGN_CENTER)),
            )
            CAMERA_BIG -> listOf(
                Element(PresentIds.next("e", random), Element.CAMERA, 96f, 140f, 1100f, 800f),
                Element(PresentIds.next("e", random), Element.TEXT, 1260f, 200f, 560f, 200f, paras = listOf(Para.of("Hi, I'm…")), role = Element.ROLE_TITLE),
                Element(PresentIds.next("e", random), Element.TEXT, 1260f, 430f, 560f, 400f, paras = listOf(Para.of("Today's deck")), role = Element.ROLE_BODY),
            )
            END_CARD -> listOf(
                el(Element.TEXT, 0.05f, 0.06f, 0.9f, 0.2f, text(Element.ROLE_TITLE, "Thanks for watching", Para.ALIGN_CENTER, Element.V_MIDDLE)).big(104f),
                el(Element.SHAPE, 0.06f, 0.34f, 0.4f, 0.5f) {
                    copy(shape = Element.SHAPE_ROUNDED, corner = 24f, fill = Fill.solid("@surface"), stroke = Stroke("@line", 3f, Stroke.DASH_DASHED),
                        paras = listOf(Para.of("Next video", align = Para.ALIGN_CENTER)), vAlign = Element.V_MIDDLE, role = Element.ROLE_CAPTION)
                },
                el(Element.SHAPE, 0.54f, 0.34f, 0.4f, 0.5f) {
                    copy(shape = Element.SHAPE_ROUNDED, corner = 24f, fill = Fill.solid("@surface"), stroke = Stroke("@line", 3f, Stroke.DASH_DASHED),
                        paras = listOf(Para.of("Subscribe", align = Para.ALIGN_CENTER)), vAlign = Element.V_MIDDLE, role = Element.ROLE_CAPTION)
                },
            )
            else -> emptyList()
        }
        return Slide(
            id = id,
            layout = layout,
            elements = elements,
            deck = if (layout == DECK) DeckFocus(all = true, title = "The deck") else null,
            title = if (layout == DECK) "The deck" else "",
        )
    }
}

/** Easing curves: cubic Béziers, never springs (Master UI's law). */
object Ease {
    const val LINEAR = "LINEAR"
    const val OUT = "OUT"
    const val IN = "IN"
    const val IN_OUT = "IN_OUT"

    /** [t] in 0..1 eased by [curve]. */
    fun apply(curve: String, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (curve) {
            LINEAR -> x
            IN -> x * x * x
            IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
            else -> 1f - (1f - x).let { it * it * it }
        }
    }
}
