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
    val sound: Boolean = false,
    val window: WindowBounds? = null,
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
        const val DEFAULT_FOIL = "classic"

        val DEFAULT = NeuePreferences()
    }
}
