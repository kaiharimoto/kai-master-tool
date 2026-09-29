package com.kaiharimoto.mastertool.core.prefs

import kotlinx.serialization.Serializable

/**
 * Master UI names its two themes for the two fills: Paper (light, the default)
 * and Ink (the exact inversion). There is no "system": the family's rule is
 * that the person chooses.
 */
@Serializable
enum class NeueTheme { PAPER, INK }

/**
 * A list of cards kept for consideration (kai, 1.0.19: "a custom list of cards in
 * the database for consideration"): a name and passcodes, in the order they were
 * put on it. Not a deck — no copies, no sections, no limits.
 */
@Serializable
data class CardList(val id: String, val name: String, val ids: List<Int> = emptyList())

/** The arithmetic of [CardList]s, kept here so the menus, the keys and the pop-out agree. */
object CardLists {
    /** [list] with [id] on it, at the end, or taken off if it was there. */
    fun toggle(list: CardList, id: Int): CardList =
        if (id in list.ids) list.copy(ids = list.ids - id) else list.copy(ids = list.ids + id)

    fun add(list: CardList, id: Int): CardList = if (id in list.ids) list else list.copy(ids = list.ids + id)

    /** A new list's id, unused by [lists]. */
    fun newId(lists: List<CardList>): String {
        var n = lists.size + 1
        while (lists.any { it.id == "list-$n" }) n++
        return "list-$n"
    }

    /** A new list's name: "Considering", then "List 2", "List 3"… — never one already taken. */
    fun newName(lists: List<CardList>): String {
        if (lists.none { it.name == "Considering" }) return "Considering"
        var n = 2
        while (lists.any { it.name == "List $n" }) n++
        return "List $n"
    }

    /** [lists] with [changed] in the place of the list with its id. */
    fun replace(lists: List<CardList>, changed: CardList): List<CardList> = lists.map { if (it.id == changed.id) changed else it }
}

/** Where the window was, so it opens there again. */
@Serializable
data class WindowBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val maximised: Boolean = false,
)

/**
 * The settings of Neue Master Tool, the desktop builder.
 *
 * A second document beside [UiPreferences] rather than more fields in it: that
 * one is the tablet's, and its "Reset layout" and layout migrations are written
 * against the tablet's panes. The desktop shares the parts that are about the
 * *deck* (format, effect search) through [UiPreferences] and keeps everything
 * about the *window* here. Same rules: a field with a default, never a schema
 * migration, and [sanitised] on the way in and out.
 */
@Serializable
data class NeuePreferences(
    val theme: NeueTheme = NeueTheme.PAPER,
    /** The whole interface, as a multiple of the operating system's own scale. */
    val scale: Float = 1f,
    val poolVisible: Boolean = true,
    val inspectorVisible: Boolean = true,
    /** Widths in dp at a scale of one. */
    val poolWidth: Float = DEFAULT_POOL_WIDTH,
    val inspectorWidth: Float = DEFAULT_INSPECTOR_WIDTH,
    /** Cards per row in the pool; zero sizes them to the pane. */
    val poolColumns: Int = 0,
    val filtersOpen: Boolean = false,
    /** The foil on card faces. A style name so that a style added later is a string, not a migration. */
    val foil: String = DEFAULT_FOIL,
    /**
     * How a card's name is drawn: stamped in the foil (the default, kai's pick),
     * foil over an ink outline, or as printed. A name, like [foil], so a style
     * added later is a string rather than a migration.
     */
    val foilNames: String = DEFAULT_FOIL_NAMES,
    val sound: Boolean = false,
    /**
     * The index rail stays out. Off by default: the rail folds away until the
     * pointer reaches the window's left edge, which is the deck's space back.
     */
    val railPinned: Boolean = false,
    /**
     * Download every card's full-size picture in the background and draw from
     * those once they are here. The small renders YGOPRODeck serves for a grid
     * are 268 pixels wide, and a card drawn larger than that is blurred text.
     */
    val hdArt: Boolean = true,
    /**
     * The lens's keys in a row over the main deck, 1.0.9 only. Read by nothing
     * since: kept so a document that carries it still reads.
     */
    val lensKeys: Boolean = true,
    /**
     * The Groups panel down the right of the deck — each group with its count
     * and opening rate. Closed by default, because the width it takes comes off
     * every card.
     */
    val groupsPanel: Boolean = false,
    /** The inspector's sections the user has folded shut, by name ("art", "details", "deck"). */
    val inspectorFolded: List<String> = emptyList(),
    /**
     * Contrast of everything that is not full ink: "standard" or "high". High
     * darkens the grey text, the field borders and the hairlines, in both themes.
     */
    val contrast: String = CONTRAST_STANDARD,
    val window: WindowBounds? = null,
    /**
     * The deck the builder opens with, by its id (1.0.14). Null, or a deck since
     * deleted, and it opens the deck saved most recently (`StartingDeck`).
     */
    val defaultDeckId: String? = null,
    /** The cards each deck is shown by in the library, by deck id: up to three passcodes, oldest choice first (`DeckCovers`). */
    val covers: Map<String, List<Int>> = emptyMap(),
    /** The artwork chosen for a card, by the card's own passcode: another of its passcodes (`CardArt`). */
    val arts: Map<Int, Int> = emptyMap(),
    /**
     * The pool's Side switch: right-click, double-click and Enter add to the side
     * deck, and Shift adds to the main (`DeskMouse.forPool`).
     */
    val poolToSide: Boolean = false,
    /** 1.0.15's one switch for both; read by nothing since 1.0.17, kept so a document that carries it still reads. */
    val extraSideVisible: Boolean = true,
    /** The extra deck, and the side deck, under the main deck: a switch each (1.0.17). */
    val extraVisible: Boolean = true,
    val sideVisible: Boolean = true,
    /** The palette the groups are coloured from, by name (1.0.17, `GroupMarkers`). */
    val groupPalette: String = DEFAULT_PALETTE,
    /** How large the deck is drawn, as a share of the size that fills its column (the wheel, 1.0.17). */
    val deckZoom: Float = 1f,
    /** How wide the gaps between groups are, as a multiple of the standard gap (Shift and the wheel). */
    val groupGap: Float = 1f,
    /** The Groups column's slides turn by themselves (1.0.18). */
    val slidesAutoplay: Boolean = true,
    /** Save the deck by itself a moment after every change (1.0.18, beside Save). */
    @kotlinx.serialization.SerialName("autoSaveOn")
    val autoSave: Boolean = true,
    /**
     * The shape of the deck's picture (`Ctrl Shift S`, 1.0.23): [SHOT_PICTURE], the
     * cards as the builder draws them, or [SHOT_LIST], a decklist of art, counts and names.
     */
    val shotStyle: String = SHOT_PICTURE,
    /** The tablet's first-run note has been shown (touch swarm, rec 20): a field with a default, no migration. */
    val touchIntroSeen: Boolean = false,
    /** Lists of cards kept for consideration (1.0.19), in the order they were made. */
    val cardLists: List<CardList> = emptyList(),
    /** The list the pool is showing instead of the whole database, by id; null is the database. */
    val poolList: String? = null,
    /** The list a card goes onto with `L` and the menus: the last one shown or edited. */
    val activeList: String? = null,
    /**
     * Zen comes by itself after idle seconds in immersive mode (1.0.16: a switch on
     * the bar). Off, it comes only when asked for with Z.
     */
    val autoZen: Boolean = true,
    /**
     * Each group's name on its piece in zen (kai, 1.0.24: "let the user toggle the
     * labels for the groups"), the corner's Labels switch. On unless turned off.
     */
    val zenLabels: Boolean = true,
    /**
     * Text size apart from Interface scale (touch swarm, rec 26): one of
     * [TEXT_SCALES], multiplying the type alone — pane widths, card fits and targets
     * are untouched. Null is the platform's own choice ([textScaleOn]), so a tablet
     * reads a size up without a stored seed; a field with a default, no migration.
     */
    val textScale: Float? = null,
    /**
     * Which way the screen may turn (the phone, v1.3.5): a `ScreenOrientation` key —
     * `portrait`, `landscape` or `auto`. Null is the device's own default, upright on
     * a phone and lying down on a tablet. A field with a default, no migration.
     */
    val orientation: String? = null,
    /**
     * Where the phone's pool dock rests (v1.3.5): a `PoolStop` name. Its own field
     * rather than a pane width, because the tall builder keeps its own settings
     * (`docs/classic/DEVICES.md` §6).
     */
    val phoneDockStop: String = DEFAULT_DOCK_STOP,
    /**
     * The foil follows the phone's tilt (kai, v1.3.6): the light on every card moves as
     * the phone is turned, read off gravity (`TiltFilter`). On unless turned off; only
     * where there is a sensor. A field with a default, no migration.
     */
    val foilTilt: Boolean = true,
) {
    /**
     * The text size in force: the chosen one, else a size up on a tablet held at arm's
     * length — and not on a phone, held close, where every letter is a letter off a card.
     */
    fun textScaleOn(touch: Boolean, phone: Boolean = false): Float = textScale ?: if (touch && !phone) TABLET_TEXT_SCALE else 1f

    fun sanitised(): NeuePreferences = copy(
        scale = if (scale.isFinite()) scale.coerceIn(SCALES.first(), SCALES.last()) else 1f,
        poolWidth = if (poolWidth.isFinite()) poolWidth.coerceIn(MIN_POOL_WIDTH, MAX_POOL_WIDTH) else DEFAULT_POOL_WIDTH,
        inspectorWidth = if (inspectorWidth.isFinite()) {
            inspectorWidth.coerceIn(MIN_INSPECTOR_WIDTH, MAX_INSPECTOR_WIDTH)
        } else {
            DEFAULT_INSPECTOR_WIDTH
        },
        poolColumns = if (poolColumns <= 0) 0 else poolColumns.coerceIn(MIN_POOL_COLUMNS, MAX_POOL_COLUMNS),
        foil = foil.ifBlank { DEFAULT_FOIL },
        foilNames = foilNames.ifBlank { DEFAULT_FOIL_NAMES },
        contrast = if (contrast == CONTRAST_HIGH) CONTRAST_HIGH else CONTRAST_STANDARD,
        groupPalette = groupPalette.ifBlank { DEFAULT_PALETTE },
        shotStyle = if (shotStyle == SHOT_LIST) SHOT_LIST else SHOT_PICTURE,
        deckZoom = if (deckZoom.isFinite()) deckZoom.coerceIn(MIN_ZOOM, 1f) else 1f,
        groupGap = if (groupGap.isFinite()) groupGap.coerceIn(MIN_GAP, MAX_GAP) else 1f,
        covers = covers
            .mapValues { (_, cards) -> cards.distinct().takeLast(COVERS) }
            .filterValues { it.isNotEmpty() },
        arts = arts.filter { (card, art) -> card != art },
        orientation = orientation?.takeIf { it in ORIENTATIONS },
        phoneDockStop = phoneDockStop.takeIf { it in DOCK_STOPS } ?: DEFAULT_DOCK_STOP,
        textScale = textScale?.takeIf { it.isFinite() }?.let { t -> TEXT_SCALES.minBy { kotlin.math.abs(it - t) } },
        window = window?.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() &&
                it.width >= MIN_WINDOW_WIDTH && it.height >= MIN_WINDOW_HEIGHT
        },
    )

    /** One step larger on [SCALES], or unchanged at the top. */
    fun zoomedIn(): NeuePreferences = copy(scale = SCALES.firstOrNull { it > scale + 0.001f } ?: SCALES.last())

    /** One step smaller on [SCALES], or unchanged at the bottom. */
    fun zoomedOut(): NeuePreferences = copy(scale = SCALES.lastOrNull { it < scale - 0.001f } ?: SCALES.first())

    companion object {
        const val KEY = "neue.ui"

        /** The interface scales Ctrl = and Ctrl - step through. A large display wants the top of this. */
        val SCALES = listOf(0.875f, 1f, 1.125f, 1.25f, 1.5f, 1.75f, 2f)

        const val DEFAULT_POOL_WIDTH = 440f
        const val MIN_POOL_WIDTH = 280f
        const val MAX_POOL_WIDTH = 960f
        const val DEFAULT_INSPECTOR_WIDTH = 400f
        const val MIN_INSPECTOR_WIDTH = 320f
        const val MAX_INSPECTOR_WIDTH = 640f
        const val MIN_POOL_COLUMNS = 2
        const val MAX_POOL_COLUMNS = 12
        const val MIN_WINDOW_WIDTH = 1024f
        const val MIN_WINDOW_HEIGHT = 680f
        const val DEFAULT_FOIL = "holo"
        const val DEFAULT_FOIL_NAMES = "foil"
        const val CONTRAST_STANDARD = "standard"
        const val CONTRAST_HIGH = "high"
        const val COVERS = 3
        const val DEFAULT_PALETTE = "prism"
        const val SHOT_PICTURE = "picture"
        const val SHOT_LIST = "list"
        const val MIN_ZOOM = 0.4f
        const val MIN_GAP = 0.4f
        const val MAX_GAP = 3f

        /** Text size's steps (rec 26): 100, 115 and 130%. */
        val TEXT_SCALES = listOf(1f, 1.15f, 1.3f)
        const val TABLET_TEXT_SCALE = 1.15f
        val ORIENTATIONS = setOf("portrait", "landscape", "auto")
        val DOCK_STOPS = setOf("PEEK", "HALF", "FULL")
        const val DEFAULT_DOCK_STOP = "HALF"

        val DEFAULT = NeuePreferences()
    }
}
