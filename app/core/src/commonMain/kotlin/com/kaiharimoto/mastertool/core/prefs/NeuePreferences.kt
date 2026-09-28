package com.kaiharimoto.mastertool.core.prefs

import kotlinx.serialization.Serializable

/**
 * Master UI names its two themes for the two fills: Paper (light, the default)
 * and Ink (the exact inversion). There is no "system": the family's rule is
 * that the person chooses.
 */
@Serializable
enum class NeueTheme { PAPER, INK }

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
    /**
     * Zen comes by itself after idle seconds in immersive mode (1.0.16: a switch on
     * the bar). Off, it comes only when asked for with Z.
     */
    val autoZen: Boolean = true,
) {
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
        deckZoom = if (deckZoom.isFinite()) deckZoom.coerceIn(MIN_ZOOM, 1f) else 1f,
        groupGap = if (groupGap.isFinite()) groupGap.coerceIn(MIN_GAP, MAX_GAP) else 1f,
        covers = covers
            .mapValues { (_, cards) -> cards.distinct().takeLast(COVERS) }
            .filterValues { it.isNotEmpty() },
        arts = arts.filter { (card, art) -> card != art },
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
        const val MIN_ZOOM = 0.4f
        const val MIN_GAP = 0.4f
        const val MAX_GAP = 3f

        val DEFAULT = NeuePreferences()
    }
}
