package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.apps.AppKind

/** A point on the icons' 32-unit grid: whole units only (§7.1). */
data class IconPt(val x: Int, val y: Int)

/**
 * What an icon is drawn from (§7.1): lines, polylines, rectangles, arcs and filled squares on a 32-unit grid, painted by
 * one Compose `Canvas` painter (`neue/world/desk/IconPaint.kt`) — stroke [WorldIcons.STROKE] units, square caps, miter
 * joins; fills in ink only. A stroked shape's numbers are its centre line, as an SVG's are.
 */
sealed interface IconShape {
    /** A polyline (a line is two points); [closed] joins the last point to the first. */
    data class Path(val points: List<IconPt>, val closed: Boolean = false) : IconShape

    /** A rectangle, stroked or [filled]. */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int, val filled: Boolean = false) : IconShape

    /** A stroked arc about [cx], [cy] of radius [r]: from [start]° through [sweep]°, clockwise on screen (0° is +x). */
    data class Arc(val cx: Int, val cy: Int, val r: Int, val start: Int, val sweep: Int) : IconShape
}

/** One icon: its [name] (unique) and its shapes. */
data class Icon(val name: String, val shapes: List<IconShape>)

/**
 * An Ai app's tile (§7.3): a 2-unit ink frame on the 32 grid, [glyph] at half size in its top-left (4–20), [monogram]
 * in JetBrains Mono 700 at [WorldIcons.MONO_SIZE] units, right-aligned at 28 on a baseline at 28. A frame and letters
 * mean *made by Ai*: a built-in is never framed.
 */
data class Tile(val glyph: Icon, val monogram: String)

/**
 * Every icon the World draws (§7.2), number for number. Built-ins, the desktop's own, a page glyph per [BoardKind] and
 * `home`, and the fifteen glyphs Ai may choose for its apps. `WorldIconsTest` holds the grammar: inside the 3–29 field,
 * on the grid, nothing under 2 units, names unique, every board kind and app kind covered.
 */
object WorldIcons {
    const val GRID = 32
    const val STROKE = 2
    const val FIELD_MIN = 3
    const val FIELD_MAX = 29

    /** The tile's frame: a 2-unit stroke whose centre line is this square. */
    val TILE_FRAME = IconShape.Box(1, 1, 30, 30)

    /** Where a tile's glyph stands, and at what scale. */
    const val GLYPH_AT = 4
    const val GLYPH_SCALE = 0.5f

    /** The monogram: JetBrains Mono 700 at this many units, its right edge and baseline at [MONO_AT]. */
    const val MONO_SIZE = 10
    const val MONO_AT = 28

    private fun p(vararg xy: Int): IconShape.Path = IconShape.Path(xy.toList().chunked(2) { IconPt(it[0], it[1]) })
    private fun closed(vararg xy: Int): IconShape.Path = p(*xy).copy(closed = true)
    private fun box(x: Int, y: Int, w: Int, h: Int) = IconShape.Box(x, y, w, h)
    private fun fill(x: Int, y: Int, w: Int, h: Int) = IconShape.Box(x, y, w, h, filled = true)

    // ---- The built-in apps (§7.2) ------------------------------------------------------------------------------

    val FILES = Icon("files", listOf(closed(5, 7, 13, 7, 15, 9, 27, 9, 27, 25, 5, 25), p(5, 13, 27, 13)))
    val EDITOR = Icon(
        "editor",
        listOf(closed(7, 4, 20, 4, 25, 9, 25, 28, 7, 28), p(20, 4, 20, 9, 25, 9), p(11, 14, 21, 14), p(11, 18, 19, 18), p(11, 22, 15, 22), fill(17, 20, 2, 5)),
    )
    val TERMINAL = Icon("terminal", listOf(box(5, 7, 22, 18), p(9, 12, 13, 16, 9, 20), p(15, 21, 21, 21)))
    val BROWSER = Icon("browser", listOf(closed(4, 10, 28, 10, 28, 27, 4, 27), p(4, 10, 4, 5, 15, 5, 15, 10), p(15, 7, 23, 7, 23, 10), p(8, 15, 24, 15)))
    val THOUGHTS = Icon("thoughts", listOf(closed(5, 6, 27, 6, 27, 21, 13, 21, 8, 26, 8, 21, 5, 21), fill(10, 12, 2, 2), fill(15, 12, 2, 2), fill(20, 12, 2, 2)))
    val INSTRUMENTS = Icon(
        "instruments",
        listOf(IconShape.Arc(16, 22, 11, 180, 180), p(5, 22, 27, 22), p(16, 11, 16, 14), p(8, 14, 10, 16), p(24, 14, 22, 16), p(16, 22, 22, 15), fill(14, 20, 4, 4)),
    )
    val LIBRARY = Icon("library", listOf(box(5, 8, 5, 18), fill(12, 5, 5, 21), closed(19, 9, 23, 8, 28, 25, 24, 26), p(3, 28, 29, 28)))

    // ---- The desktop's own ---------------------------------------------------------------------------------------

    /** Four squares, the first filled. The three open ones' centre lines sit a unit in, so all four read 8 × 8. */
    val LAUNCHER = Icon("launcher", listOf(fill(6, 6, 8, 8), box(19, 7, 6, 6), box(7, 19, 6, 6), box(19, 19, 6, 6)))
    val NOTICES = Icon("notices", listOf(closed(5, 17, 8, 7, 24, 7, 27, 17, 27, 26, 5, 26), p(5, 17, 11, 17, 13, 20, 19, 20, 21, 17, 27, 17)))
    val NEW_WORLD = Icon("new-world", listOf(box(5, 5, 22, 22), p(16, 10, 16, 22), p(10, 16, 22, 16)))

    // ---- Pages: the Browser's tab glyphs, one per board kind ---------------------------------------------------

    val PAGE_MARKDOWN = Icon("page-markdown", listOf(p(7, 8, 25, 8), p(7, 13, 25, 13), p(7, 18, 19, 18), p(7, 23, 22, 23)))
    val PAGE_CHART = Icon("page-chart", listOf(fill(7, 17, 5, 9), fill(14, 9, 5, 17), fill(21, 13, 5, 13), p(4, 27, 28, 27)))
    val PAGE_GRAPH = Icon("page-graph", listOf(p(9, 21, 23, 9), p(9, 22, 23, 22), p(25, 11, 25, 20), fill(4, 19, 6, 6), fill(21, 5, 6, 6), fill(21, 19, 6, 6)))
    val PAGE_FLOW = Icon(
        "page-flow",
        listOf(box(11, 4, 10, 6), box(4, 22, 10, 6), box(18, 22, 10, 6), p(16, 10, 16, 16), p(9, 16, 23, 16), p(9, 16, 9, 22), p(23, 16, 23, 22)),
    )
    val PAGE_TABLE = Icon("page-table", listOf(box(5, 6, 22, 20), p(5, 12, 27, 12), p(5, 19, 27, 19), p(13, 6, 13, 26)))
    val PAGE_STAT = Icon("page-stat", listOf(box(6, 6, 5, 5), box(21, 21, 5, 5), p(8, 25, 24, 7)))

    /** Two cards, the front filled: the back's centre line 13 × 19 (the card's own 59 : 86), the front its outer size. */
    val PAGE_CARDS = Icon("page-cards", listOf(box(6, 5, 13, 19), fill(12, 8, 15, 21)))
    val PAGE_BOARD = Icon(
        "page-board",
        listOf(fill(4, 8, 3, 5), fill(9, 8, 3, 5), fill(14, 8, 3, 5), fill(19, 8, 3, 5), fill(24, 8, 3, 5), p(4, 16, 27, 16), box(5, 20, 4, 5), box(14, 20, 4, 5), box(23, 20, 4, 5)),
    )
    val PAGE_LINE = Icon("page-line", listOf(box(3, 13, 6, 6), box(23, 13, 6, 6), p(9, 16, 13, 16), p(19, 16, 23, 16), fill(12, 12, 8, 8)))
    val PAGE_IMAGE = Icon("page-image", listOf(box(5, 7, 22, 18), p(5, 25, 13, 15, 19, 21, 22, 18, 27, 23), fill(20, 10, 3, 3)))
    val PAGE_HOME = Icon("page-home", listOf(fill(4, 8, 7, 7), box(14, 9, 5, 5), box(23, 9, 5, 5), box(5, 19, 5, 5), box(14, 19, 5, 5), box(23, 19, 5, 5)))

    // ---- The glyphs Ai picks for its apps (§7.3) -----------------------------------------------------------------

    val GLYPH_DICE = Icon("glyph-dice", listOf(box(6, 6, 20, 20), fill(9, 9, 4, 4), fill(14, 14, 4, 4), fill(19, 19, 4, 4)))
    val GLYPH_HAND = Icon("glyph-hand", listOf(box(4, 11, 10, 15), box(10, 8, 10, 15), fill(16, 4, 12, 17)))
    val GLYPH_DECK = Icon("glyph-deck", listOf(fill(11, 4, 14, 19), p(8, 7, 8, 26, 22, 26), p(5, 10, 5, 29, 19, 29)))
    val GLYPH_TALLY = Icon("glyph-tally", listOf(p(8, 8, 8, 24), p(13, 8, 13, 24), p(18, 8, 18, 24), p(23, 8, 23, 24), p(5, 21, 26, 11)))
    val GLYPH_VERSUS = Icon("glyph-versus", listOf(box(5, 9, 9, 15), fill(18, 8, 10, 17), p(16, 5, 16, 27)))
    val GLYPH_TIMER = Icon("glyph-timer", listOf(IconShape.Arc(16, 18, 10, 0, 360), p(16, 18, 16, 12), p(16, 18, 21, 18), p(13, 4, 19, 4)))
    val GLYPH_CHECK = Icon("glyph-check", listOf(box(5, 5, 22, 22), p(10, 16, 14, 20, 22, 11)))
    val GLYPH_SEARCH = Icon("glyph-search", listOf(box(6, 6, 14, 14), p(20, 20, 26, 26)))
    val GLYPH_NOTE = Icon("glyph-note", listOf(box(7, 5, 18, 22), p(11, 11, 21, 11), p(11, 16, 21, 16), p(11, 21, 17, 21)))

    /** The fifteen glyphs by the word Ai names them with; six are the page glyphs they share an idea with. */
    val GLYPHS: Map<String, Icon> = linkedMapOf(
        "odds" to PAGE_STAT,
        "dice" to GLYPH_DICE,
        "hand" to GLYPH_HAND,
        "deck" to GLYPH_DECK,
        "line" to PAGE_LINE,
        "tally" to GLYPH_TALLY,
        "versus" to GLYPH_VERSUS,
        "web" to PAGE_GRAPH,
        "flow" to PAGE_FLOW,
        "table" to PAGE_TABLE,
        "bars" to PAGE_CHART,
        "timer" to GLYPH_TIMER,
        "check" to GLYPH_CHECK,
        "search" to GLYPH_SEARCH,
        "note" to GLYPH_NOTE,
    )

    /** Every distinct icon, for the test and the studio's icon sheet. */
    val ALL: List<Icon> = listOf(
        FILES, EDITOR, TERMINAL, BROWSER, THOUGHTS, INSTRUMENTS, LIBRARY,
        LAUNCHER, NOTICES, NEW_WORLD,
        PAGE_MARKDOWN, PAGE_CHART, PAGE_GRAPH, PAGE_FLOW, PAGE_TABLE, PAGE_STAT, PAGE_CARDS, PAGE_BOARD, PAGE_LINE, PAGE_IMAGE, PAGE_HOME,
        GLYPH_DICE, GLYPH_HAND, GLYPH_DECK, GLYPH_TALLY, GLYPH_VERSUS, GLYPH_TIMER, GLYPH_CHECK, GLYPH_SEARCH, GLYPH_NOTE,
    )

    fun builtIn(app: BuiltInApp): Icon = when (app) {
        BuiltInApp.FILES -> FILES
        BuiltInApp.EDITOR -> EDITOR
        BuiltInApp.TERMINAL -> TERMINAL
        BuiltInApp.BROWSER -> BROWSER
        BuiltInApp.THOUGHTS -> THOUGHTS
        BuiltInApp.INSTRUMENTS -> INSTRUMENTS
        BuiltInApp.LIBRARY -> LIBRARY
    }

    /** A page's tab glyph; a kind from a newer build (null) draws as markdown. */
    fun page(kind: BoardKind?): Icon = when (kind) {
        BoardKind.MARKDOWN, null -> PAGE_MARKDOWN
        BoardKind.CHART -> PAGE_CHART
        BoardKind.GRAPH -> PAGE_GRAPH
        BoardKind.FLOW -> PAGE_FLOW
        BoardKind.TABLE -> PAGE_TABLE
        BoardKind.STAT -> PAGE_STAT
        BoardKind.CARDS -> PAGE_CARDS
        BoardKind.BOARD -> PAGE_BOARD
        BoardKind.LINE -> PAGE_LINE
        BoardKind.IMAGE -> PAGE_IMAGE
    }

    /** The glyph an app of [kind] wears when it names none (§7.3). */
    fun defaultGlyph(kind: AppKind): String = when (kind) {
        AppKind.CALCULATOR -> "odds"
        AppKind.EXPLORER -> "line"
        AppKind.TRACKER -> "tally"
        AppKind.SIMULATOR -> "dice"
        AppKind.VIEWER -> "table"
        AppKind.DRILL -> "check"
        AppKind.PLANNER -> "flow"
        AppKind.NOTEBOOK -> "note"
    }

    /** One or two characters, `A–Z 0–9`: [given] cleaned, else the name's initials (*Hand odds* → `HO`). */
    fun monogram(name: String, given: String? = null): String {
        val clean = given.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(2)
        if (clean.isNotEmpty()) return clean
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val initials = words.take(2).map { it.first().uppercaseChar() }.joinToString("")
        return initials.ifEmpty { "AI" }
    }

    /** An app's tile from its manifest's choices: [glyph] if it is one of the fifteen, else its kind's. */
    fun tile(name: String, kind: AppKind, glyph: String?, monogram: String?): Tile =
        Tile(GLYPHS[glyph?.trim()?.lowercase()] ?: GLYPHS.getValue(defaultGlyph(kind)), monogram(name, monogram))
}
