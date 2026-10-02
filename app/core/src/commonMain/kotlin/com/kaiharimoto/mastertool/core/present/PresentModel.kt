package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import kotlinx.serialization.Serializable

/**
 * A presentation (1.0.70, kai: "a slideshow presentation creator that's animated and
 * interactive" for deck profiles): slides on a 1920×1080 canvas, a deck to present, how
 * the deck is told ([style]), a theme and where the webcam stands.
 *
 * Kinds are strings with constants, never enums, because this is stored JSON a later
 * build adds to: a value an older build does not know must read as a default, not throw
 * the presentation away ([PresentCodec]).
 */
@Serializable
data class Presentation(
    val id: String,
    val name: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** How the deck slides are told: [STYLE_SPOTLIGHT], [STYLE_SLIDES] or [STYLE_BUILD_UP]. */
    val style: String = STYLE_SPOTLIGHT,
    /** The deck as it stood when the presentation was made ([DeckSnapshot]). */
    val deck: DeckSnapshot? = null,
    /** A theme id from [Themes], else [Themes.PAPER]. */
    val theme: String = Themes.PAPER,
    /** Colours, fonts and the dimming over the theme's own, by the person. */
    val themeOverride: ThemeOverride? = null,
    val webcam: WebcamZone = WebcamZone(),
    val slides: List<Slide> = emptyList(),
    /** Who is presenting, for title and outro slides. */
    val creator: String = "",
    val version: Int = 1,
) {
    fun slide(id: String?): Slide? = id?.let { wanted -> slides.firstOrNull { it.id == wanted } }

    fun indexOf(id: String?): Int = slides.indexOfFirst { it.id == id }

    /** The deck slides, in order: what Build-up reveals one by one. */
    val deckSlides: List<Slide> get() = slides.filter { it.deck != null && !it.hidden }

    companion object {
        const val STYLE_SPOTLIGHT = "SPOTLIGHT"
        const val STYLE_SLIDES = "SLIDES"
        const val STYLE_BUILD_UP = "BUILD_UP"
        val STYLES = listOf(STYLE_SPOTLIGHT, STYLE_SLIDES, STYLE_BUILD_UP)

        const val WIDTH = 1920f
        const val HEIGHT = 1080f

        fun styleName(style: String): String = when (style) {
            STYLE_SLIDES -> "Slides"
            STYLE_BUILD_UP -> "Build-up"
            else -> "Spotlight"
        }

        fun styleLine(style: String): String = when (style) {
            STYLE_SLIDES -> "Each card or group its own slide; the whole deck on demand"
            STYLE_BUILD_UP -> "Cards emerge as you talk and build into the deck"
            else -> "The whole deck, dimmed; what you talk about lights up"
        }
    }
}

/**
 * The deck being presented, kept whole inside the presentation: passcodes, groups and the
 * artworks chosen. A deck edited later, or deleted, never breaks the slides or a take.
 */
@Serializable
data class DeckSnapshot(
    val deckId: String? = null,
    val name: String = "",
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val side: List<Int> = emptyList(),
    val groups: List<SnapGroup> = emptyList(),
    /** Passcode → group id. */
    val assignments: Map<Int, String> = emptyMap(),
    /** The Fitted order (`DeckGroups.fitted`). */
    val fitted: List<Int> = emptyList(),
    /** `GroupArrangement` name: AS_IS, FITTED or SEPARATE. */
    val arrangement: String = "FITTED",
    /** Passcode → the artwork chosen for it (`CardArt`). */
    val arts: Map<Int, Int> = emptyMap(),
    /** Which group palette the colours read through (`GroupMarkers.palettes`). */
    val palette: Int = 0,
    val takenAt: Long = 0L,
) {
    val isEmpty: Boolean get() = main.isEmpty() && extra.isEmpty() && side.isEmpty()

    fun groupOf(id: Int): String? = assignments[id]?.takeIf { g -> groups.any { it.id == g } }

    fun group(id: String): SnapGroup? = groups.firstOrNull { it.id == id }

    fun ordered(): List<SnapGroup> = groups.sortedWith(compareBy({ it.order }, { it.id }))

    /** Every passcode in the deck, once. */
    val distinct: List<Int> get() = (main + extra + side).distinct()

    fun section(name: String): List<Int> = when (name) {
        SECTION_EXTRA -> extra
        SECTION_SIDE -> side
        else -> main
    }

    companion object {
        const val SECTION_MAIN = "M"
        const val SECTION_EXTRA = "E"
        const val SECTION_SIDE = "S"
    }
}

@Serializable
data class SnapGroup(val id: String, val name: String, val color: Int = 0, val order: Int = 0)

/**
 * What a deck slide talks about: groups and cards (every copy), or the whole deck, with
 * the note shown beside it. Copies are keys `M:1234#2` (section, passcode, copy) when one
 * copy alone is meant.
 */
@Serializable
data class DeckFocus(
    val groups: List<String> = emptyList(),
    val cards: List<Int> = emptyList(),
    val copies: List<String> = emptyList(),
    /** The whole deck: an overview slide. */
    val all: Boolean = false,
    /** Which sections the slide shows; empty is all three. */
    val sections: List<String> = emptyList(),
    val title: String = "",
    val note: String = "",
    /** Where the note stands: [NOTE_AUTO], [NOTE_SIDE], [NOTE_BOTTOM], [NOTE_NONE]. */
    val notePlace: String = NOTE_AUTO,
) {
    val isEmpty: Boolean get() = !all && groups.isEmpty() && cards.isEmpty() && copies.isEmpty()

    companion object {
        const val NOTE_AUTO = "AUTO"
        const val NOTE_SIDE = "SIDE"
        const val NOTE_BOTTOM = "BOTTOM"
        const val NOTE_NONE = "NONE"
    }
}

/**
 * One slide. A slide with [deck] is a deck slide: the deck stands on its stage, told by
 * the presentation's style, and [elements] draw over it. Every slide may carry
 * elements, speaker [notes], a [transition] and a time for rehearsals.
 */
@Serializable
data class Slide(
    val id: String,
    val title: String = "",
    val layout: String = SlideLayouts.BLANK,
    val background: Fill? = null,
    val elements: List<Element> = emptyList(),
    val deck: DeckFocus? = null,
    val transition: Transition = Transition(),
    val notes: String = "",
    /** Rehearsed or set time on this slide, ms. */
    val durationMs: Long? = null,
    /** The webcam on this slide: [CAMERA_DEFAULT], [CAMERA_HIDDEN], or a preset that moves it here. */
    val camera: String = CAMERA_DEFAULT,
    val hidden: Boolean = false,
    /** A module this slide was made by (phase 2), kept so it can be refreshed. */
    val module: ModuleRef? = null,
    /** The section heading in the sorter this slide begins, if any. */
    val section: String? = null,
) {
    fun element(id: String?): Element? = id?.let { wanted -> elements.firstOrNull { it.id == wanted } }

    val isDeck: Boolean get() = deck != null

    companion object {
        const val CAMERA_DEFAULT = "DEFAULT"
        const val CAMERA_HIDDEN = "HIDDEN"
    }
}

@Serializable
data class ModuleRef(val type: String, val params: Map<String, String> = emptyMap(), val generatedAt: Long = 0L)

/**
 * Anything on a slide. One flat shape for every kind rather than a sealed tree: an edit is
 * a copy, Ai's patches are JSON merged onto it, and a kind or field a later build adds
 * reads as defaults in an older one. [x], [y], [w], [h] are canvas units (1920×1080),
 * unless [anchor] is [ANCHOR_STAGE], when they are fractions of the slide's stage — the
 * room the webcam leaves — so the slide re-flows when the camera moves.
 */
@Serializable
data class Element(
    val id: String,
    /** [TEXT], [SHAPE], [IMAGE], [CARD], [CARDS], [DECK], [CAMERA], [TABLE], [CHART], [STAT], [QR]. */
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 400f,
    val h: Float = 200f,
    /** Degrees, clockwise. */
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val anchor: String = ANCHOR_CANVAS,
    val locked: Boolean = false,
    /** Elements grouped together share an id: selected and moved as one. */
    val group: String? = null,
    val name: String = "",
    // Text, and text inside a shape.
    val paras: List<Para> = emptyList(),
    /** The theme's style this text takes: [ROLE_TITLE], [ROLE_SUBTITLE], [ROLE_BODY], [ROLE_CAPTION], [ROLE_NONE]. */
    val role: String = ROLE_NONE,
    /** Shrink the text to its box ([FIT_SHRINK]) or leave it ([FIT_NONE]). */
    val fit: String = FIT_SHRINK,
    /** Vertical alignment of text in its box: [V_TOP], [V_MIDDLE], [V_BOTTOM]. */
    val vAlign: String = V_TOP,
    val padding: Float = 0f,
    // Shapes.
    val shape: String = SHAPE_RECT,
    /** A rounded rectangle's radius, in canvas units. */
    val corner: Float = 0f,
    val fill: Fill? = null,
    val stroke: Stroke? = null,
    val shadow: Shadow? = null,
    // Pictures.
    /** A picture in the presentation's media folder, by its file name. */
    val media: String? = null,
    /** Crop as fractions of the picture: left, top, right, bottom. */
    val crop: List<Float> = emptyList(),
    /** [FIT_COVER] or [FIT_CONTAIN]. */
    val imageFit: String = FIT_COVER,
    // Cards.
    val cards: List<Int> = emptyList(),
    /** How [cards] stand: [CARDS_ROW], [CARDS_FAN], [CARDS_GRID]. */
    val cardLayout: String = CARDS_ROW,
    /** Card names written under each card. */
    val cardLabels: Boolean = false,
    // A deck view on a freeform slide.
    val focus: DeckFocus? = null,
    // Tables, charts, stats and codes (phase 2 builds them; phase 1 reads and draws them).
    val table: List<List<String>> = emptyList(),
    val chart: Chart? = null,
    val stat: Stat? = null,
    val qr: String? = null,
    // Motion.
    val animations: List<Anim> = emptyList(),
    /** Elements sharing a key on two slides morph between them. */
    val morphKey: String? = null,
    /** Set once the person changes an element a module made, so a refresh leaves it. */
    val edited: Boolean = false,
    /** A link: another slide's id to jump to when clicked while presenting. */
    val link: String? = null,
) {
    val plainText: String get() = paras.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }

    companion object {
        const val TEXT = "TEXT"
        const val SHAPE = "SHAPE"
        const val IMAGE = "IMAGE"
        const val CARD = "CARD"
        const val CARDS = "CARDS"
        const val DECK = "DECK"
        const val CAMERA = "CAMERA"
        const val TABLE = "TABLE"
        const val CHART = "CHART"
        const val STAT = "STAT"
        const val QR = "QR"
        val TYPES = listOf(TEXT, SHAPE, IMAGE, CARD, CARDS, DECK, CAMERA, TABLE, CHART, STAT, QR)

        const val ANCHOR_CANVAS = "CANVAS"
        const val ANCHOR_STAGE = "STAGE"

        const val ROLE_TITLE = "TITLE"
        const val ROLE_SUBTITLE = "SUBTITLE"
        const val ROLE_BODY = "BODY"
        const val ROLE_CAPTION = "CAPTION"
        const val ROLE_NONE = "NONE"

        const val FIT_SHRINK = "SHRINK"
        const val FIT_NONE = "NONE"
        const val FIT_COVER = "COVER"
        const val FIT_CONTAIN = "CONTAIN"

        const val V_TOP = "TOP"
        const val V_MIDDLE = "MIDDLE"
        const val V_BOTTOM = "BOTTOM"

        const val SHAPE_RECT = "RECT"
        const val SHAPE_ROUNDED = "ROUNDED"
        const val SHAPE_ELLIPSE = "ELLIPSE"
        const val SHAPE_TRIANGLE = "TRIANGLE"
        const val SHAPE_DIAMOND = "DIAMOND"
        const val SHAPE_STAR = "STAR"
        const val SHAPE_CHEVRON = "CHEVRON"
        const val SHAPE_ARROW = "ARROW"
        const val SHAPE_LINE = "LINE"
        const val SHAPE_ARROW_LINE = "ARROW_LINE"
        const val SHAPE_CALLOUT = "CALLOUT"
        val SHAPES = listOf(
            SHAPE_RECT, SHAPE_ROUNDED, SHAPE_ELLIPSE, SHAPE_TRIANGLE, SHAPE_DIAMOND, SHAPE_STAR,
            SHAPE_CHEVRON, SHAPE_ARROW, SHAPE_CALLOUT, SHAPE_LINE, SHAPE_ARROW_LINE,
        )

        const val CARDS_ROW = "ROW"
        const val CARDS_FAN = "FAN"
        const val CARDS_GRID = "GRID"

        fun shapeName(shape: String): String = when (shape) {
            SHAPE_ROUNDED -> "Rounded"
            SHAPE_ELLIPSE -> "Oval"
            SHAPE_TRIANGLE -> "Triangle"
            SHAPE_DIAMOND -> "Diamond"
            SHAPE_STAR -> "Star"
            SHAPE_CHEVRON -> "Chevron"
            SHAPE_ARROW -> "Arrow"
            SHAPE_CALLOUT -> "Callout"
            SHAPE_LINE -> "Line"
            SHAPE_ARROW_LINE -> "Arrow line"
            else -> "Rectangle"
        }

        fun typeName(type: String): String = when (type) {
            TEXT -> "Text"
            SHAPE -> "Shape"
            IMAGE -> "Picture"
            CARD -> "Card"
            CARDS -> "Cards"
            DECK -> "Deck"
            CAMERA -> "Camera"
            TABLE -> "Table"
            CHART -> "Chart"
            STAT -> "Number"
            QR -> "Code"
            else -> "Element"
        }
    }
}

/** A paragraph: runs of styled text, aligned, maybe a list item. */
@Serializable
data class Para(
    val runs: List<Run> = emptyList(),
    /** [ALIGN_LEFT], [ALIGN_CENTER], [ALIGN_RIGHT]. */
    val align: String = ALIGN_LEFT,
    /** [LIST_NONE], [LIST_BULLET], [LIST_NUMBER]. */
    val list: String = LIST_NONE,
    val indent: Int = 0,
    /** Line height as a multiple of the size. */
    val lineHeight: Float = 1.2f,
    /** Room after the paragraph, in canvas units. */
    val after: Float = 0f,
) {
    val text: String get() = runs.joinToString("") { it.text }

    companion object {
        const val ALIGN_LEFT = "LEFT"
        const val ALIGN_CENTER = "CENTER"
        const val ALIGN_RIGHT = "RIGHT"
        const val LIST_NONE = "NONE"
        const val LIST_BULLET = "BULLET"
        const val LIST_NUMBER = "NUMBER"

        fun of(text: String, style: RunStyle = RunStyle(), align: String = ALIGN_LEFT): Para =
            Para(listOf(Run(text, style)), align)
    }
}

/** Text in one style. Null fields take the element's role from the theme. */
@Serializable
data class Run(val text: String, val style: RunStyle = RunStyle())

@Serializable
data class RunStyle(
    /** A font id from [SlideFonts], or null for the role's. */
    val font: String? = null,
    /** Canvas units. */
    val size: Float? = null,
    /** 400, 500 or 700. */
    val weight: Int? = null,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** A colour ([SlideColor]): `#RRGGBB`, `#AARRGGBB` or a theme token like `@accent`. */
    val color: String? = null,
    val highlight: String? = null,
    /** Extra room between letters, in ems. */
    val tracking: Float? = null,
    /** All capitals. */
    val caps: Boolean = false,
)

/** A fill: solid, or a gradient of two or more stops. */
@Serializable
data class Fill(
    /** [SOLID], [LINEAR], [RADIAL]. */
    val kind: String = SOLID,
    val color: String = "@surface",
    val stops: List<String> = emptyList(),
    /** A linear gradient's angle in degrees, 0 left to right, 90 top to bottom. */
    val angle: Float = 90f,
    /** A picture filling a slide's background. */
    val media: String? = null,
    /** Darken a background picture, 0..1, so words read over it. */
    val scrim: Float = 0f,
) {
    companion object {
        const val SOLID = "SOLID"
        const val LINEAR = "LINEAR"
        const val RADIAL = "RADIAL"

        fun solid(color: String) = Fill(SOLID, color)
    }
}

@Serializable
data class Stroke(
    val color: String = "@text",
    val width: Float = 4f,
    /** [DASH_SOLID], [DASH_DASHED], [DASH_DOTTED]. */
    val dash: String = DASH_SOLID,
) {
    companion object {
        const val DASH_SOLID = "SOLID"
        const val DASH_DASHED = "DASHED"
        const val DASH_DOTTED = "DOTTED"
    }
}

@Serializable
data class Shadow(
    val color: String = "#66000000",
    val blur: Float = 24f,
    val dx: Float = 0f,
    val dy: Float = 8f,
)

@Serializable
data class Chart(
    /** [BAR], [COLUMN], [PIE], [DONUT], [LINE]. */
    val kind: String = COLUMN,
    val labels: List<String> = emptyList(),
    val series: List<ChartSeries> = emptyList(),
    /** A value axis's top; null fits the data. */
    val max: Float? = null,
    /** Values are percentages. */
    val percent: Boolean = false,
) {
    companion object {
        const val BAR = "BAR"
        const val COLUMN = "COLUMN"
        const val PIE = "PIE"
        const val DONUT = "DONUT"
        const val LINE = "LINE"
    }
}

@Serializable
data class ChartSeries(val name: String = "", val values: List<Float> = emptyList(), val color: String? = null)

@Serializable
data class Stat(val value: String = "", val label: String = "", val sub: String = "")

/**
 * A build: an element entering, drawing the eye, or leaving, on a click or with or after
 * the one before ([Builds]).
 */
@Serializable
data class Anim(
    val id: String,
    /** [ENTRANCE], [EMPHASIS], [EXIT]. */
    val kind: String = ENTRANCE,
    /** [FADE], [RISE], [DROP], [ZOOM], [WIPE], [FLY_LEFT], [FLY_RIGHT], [TYPE], [PULSE], [GROW], [SPIN], [GLOW]. */
    val effect: String = FADE,
    /** [ON_CLICK], [WITH_PREVIOUS], [AFTER_PREVIOUS]. */
    val trigger: String = ON_CLICK,
    val durationMs: Int = 450,
    val delayMs: Int = 0,
    /** Where the build stands in its slide's order. */
    val order: Int = 0,
) {
    companion object {
        const val ENTRANCE = "ENTRANCE"
        const val EMPHASIS = "EMPHASIS"
        const val EXIT = "EXIT"

        const val FADE = "FADE"
        const val RISE = "RISE"
        const val DROP = "DROP"
        const val ZOOM = "ZOOM"
        const val WIPE = "WIPE"
        const val FLY_LEFT = "FLY_LEFT"
        const val FLY_RIGHT = "FLY_RIGHT"
        const val TYPE = "TYPE"
        const val PULSE = "PULSE"
        const val GROW = "GROW"
        const val SPIN = "SPIN"
        const val GLOW = "GLOW"

        val ENTRANCE_EFFECTS = listOf(FADE, RISE, DROP, ZOOM, WIPE, FLY_LEFT, FLY_RIGHT, TYPE)
        val EMPHASIS_EFFECTS = listOf(PULSE, GROW, SPIN, GLOW)
        val EXIT_EFFECTS = listOf(FADE, RISE, DROP, ZOOM, WIPE, FLY_LEFT, FLY_RIGHT)

        const val ON_CLICK = "ON_CLICK"
        const val WITH_PREVIOUS = "WITH_PREVIOUS"
        const val AFTER_PREVIOUS = "AFTER_PREVIOUS"

        fun effectName(effect: String): String = when (effect) {
            RISE -> "Rise"
            DROP -> "Drop"
            ZOOM -> "Zoom"
            WIPE -> "Wipe"
            FLY_LEFT -> "Fly from left"
            FLY_RIGHT -> "Fly from right"
            TYPE -> "Typewriter"
            PULSE -> "Pulse"
            GROW -> "Grow"
            SPIN -> "Spin"
            GLOW -> "Glow"
            else -> "Fade"
        }

        fun triggerName(trigger: String): String = when (trigger) {
            WITH_PREVIOUS -> "With previous"
            AFTER_PREVIOUS -> "After previous"
            else -> "On click"
        }
    }
}

/** How a slide arrives. Between two deck slides the deck itself moves ([DECK]). */
@Serializable
data class Transition(
    /** [NONE], [FADE], [PUSH], [COVER], [ZOOM], [MORPH]. */
    val kind: String = FADE,
    val durationMs: Int = 500,
    /** [LEFT], [RIGHT], [UP], [DOWN] for a push or cover. */
    val direction: String = LEFT,
) {
    companion object {
        const val NONE = "NONE"
        const val FADE = "FADE"
        const val PUSH = "PUSH"
        const val COVER = "COVER"
        const val ZOOM = "ZOOM"
        const val MORPH = "MORPH"
        val KINDS = listOf(NONE, FADE, PUSH, COVER, ZOOM, MORPH)

        const val LEFT = "LEFT"
        const val RIGHT = "RIGHT"
        const val UP = "UP"
        const val DOWN = "DOWN"

        fun kindName(kind: String): String = when (kind) {
            NONE -> "Cut"
            PUSH -> "Push"
            COVER -> "Cover"
            ZOOM -> "Zoom"
            MORPH -> "Morph"
            else -> "Fade"
        }
    }
}

/** The person's changes over a theme: any colour token, the fonts, how dim the deck goes. */
@Serializable
data class ThemeOverride(
    val colors: Map<String, String> = emptyMap(),
    val headingFont: String? = null,
    val bodyFont: String? = null,
    val dim: Float? = null,
)

/**
 * How a new presentation starts (a `NeuePreferences` field, synced): the style, theme and
 * camera the creator last chose, and their name for title slides.
 */
@Serializable
data class PresentPrefs(
    val style: String = Presentation.STYLE_SPOTLIGHT,
    val theme: String = Themes.ARENA,
    val webcam: Boolean = false,
    val webcamPreset: String = WebcamZone.BOTTOM_RIGHT,
    val creator: String = "",
    /** The presentation open last, by id. */
    val open: String? = null,
)
