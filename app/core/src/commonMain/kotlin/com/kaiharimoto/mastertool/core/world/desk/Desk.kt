package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** How a window stands (§2.3). Minimised is apart ([DeskWindow.minimised]), so a window comes back as it went. */
@Serializable
enum class WindowMode {
    @SerialName("normal") NORMAL,
    @SerialName("maximised") MAXIMISED,
    @SerialName("snapped") SNAPPED,
}

/** Where a window snaps (§2.3, [SnapZones]). [TOP] is maximised: it never stays a snap, the reducer turns it into [WindowMode.MAXIMISED]. */
@Serializable
enum class Snap {
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
    @SerialName("top") TOP,
    @SerialName("top-left") TOP_LEFT,
    @SerialName("top-right") TOP_RIGHT,
    @SerialName("bottom-left") BOTTOM_LEFT,
    @SerialName("bottom-right") BOTTOM_RIGHT,
}

/** The edge or corner a resize drags (§2.3: a 6 dp band round the edge, 12 dp corners). */
enum class Edge(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false) {
    LEFT(left = true), TOP(top = true), RIGHT(right = true), BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true), TOP_RIGHT(top = true, right = true),
    BOTTOM_LEFT(left = true, bottom = true), BOTTOM_RIGHT(right = true, bottom = true),
}

/**
 * One open window: one an app, so [app] ([AppRef.key]) is its id too. [frame] is always its *normal* place — a snapped
 * or maximised window draws elsewhere and comes back here, which is how "snapped windows remember the frame they came
 * from" holds without a second field. [by] and [turn] say who opened it and in which of Ai's turns; [touched] that the
 * person pressed in it; [kept] that the person asked it to stay (`DeskTidy` never closes it); [used] when it was last
 * in front, for the least-recently-used rule.
 */
@Serializable
data class DeskWindow(
    val app: String,
    val mode: WindowMode = WindowMode.NORMAL,
    val snap: Snap? = null,
    val minimised: Boolean = false,
    val frame: Frame = Frame(),
    val by: String = WorldEvent.YOU,
    val turn: Int = 0,
    val touched: Boolean = false,
    val kept: Boolean = false,
    val used: Long = 0L,
) {
    val ref: AppRef? get() = AppRef.parse(app)

    /** Where it draws on [area] now, whatever its mode. */
    fun rect(area: DeskArea): DeskRect = when (mode) {
        WindowMode.MAXIMISED -> area.full
        WindowMode.SNAPPED -> SnapZones.frame(snap ?: Snap.LEFT, area.full)
        WindowMode.NORMAL -> area.rect(frame).inside(area.full, DeskPlacer.MIN)
    }
}

/** An icon's place on the desktop's grid (§2.1): column and row of 88 × 80 dp tiles, kept per world. */
@Serializable
data class IconCell(val app: String, val col: Int, val row: Int)

/** What Ai did in the turn under way, for [DeskTidy]: a page opened, apps made or changed, the last window it worked in. */
data class TurnFacts(val pages: Boolean = false, val apps: Set<String> = emptySet(), val last: String? = null)

/**
 * A world's desktop (`docs/world/DESKTOP.md` §2, `<data>/world/<id>/desk.json` through [DeskCodec]): its windows from
 * the bottom up, the one in [front] (or none), the Browser's [tabs], the icons' cells, and Ai's turns. A pure value;
 * every change is [reduce] over a [DeskOp], so the page, the keys, Ai and the tests all move it the same way.
 *
 * What only lasts a turn — whether Ai is working, where it is, what the person closed, [facts] — is never stored.
 */
@Serializable
data class Desk(
    val windows: List<DeskWindow> = emptyList(),
    val front: String? = null,
    val tabs: BrowserTabs = BrowserTabs(),
    val icons: List<IconCell> = emptyList(),
    /** Ai's turns in this world, counted: the one under way, or the last. */
    val turn: Int = 0,
    /** How many windows have opened, for the next cascade slot. */
    val cascade: Int = 0,
    @Transient val working: Boolean = false,
    /** The window Ai is working in now, or null. */
    @Transient val ai: String? = null,
    /** The windows the person closed during this turn: Ai's arrivals there are [FocusDecision.MARK] only (§6.1). */
    @Transient val closedThisTurn: Set<String> = emptySet(),
    @Transient val facts: TurnFacts = TurnFacts(),
) {
    fun window(app: String): DeskWindow? = windows.firstOrNull { it.app == app }
    fun isOpen(app: String): Boolean = window(app) != null

    /** The windows drawn, bottom to top: every open one that is not minimised. */
    val visible: List<DeskWindow> get() = windows.filterNot { it.minimised }

    /** Every open window, most recently in front first: the switcher's order (`Ctrl \``). */
    val recent: List<DeskWindow> get() = windows.sortedByDescending { it.used }

    /** Whether Ai is in [app] and it is covered or minimised: then the avatar stands on its taskbar cell (§5.2). */
    fun hidden(app: String): Boolean {
        val w = window(app) ?: return true
        return w.minimised || (front != app && visible.lastOrNull()?.app != app)
    }

    fun reduce(op: DeskOp, area: DeskArea): Desk = step(op, area).desk

    /** [op] applied, and the windows it closed to make room (§2.3: "says so in a notice"). */
    fun step(op: DeskOp, area: DeskArea): DeskStep = DeskReducer.step(this, op, area)

    companion object {
        /** At most this many windows; a 13th closes the least recently used that may go (§2.3). */
        const val MAX_WINDOWS = 12

        /** At most this many of Ai's apps open at once (§8.6). */
        const val MAX_APP_WINDOWS = 8

        fun reduce(desk: Desk, op: DeskOp, area: DeskArea): Desk = desk.reduce(op, area)
    }
}

/** A step of the desk: the next desk, and the windows closed to make room for one that opened. */
data class DeskStep(val desk: Desk, val evicted: List<String> = emptyList())

/** Everything that moves the desk (§2.3, §6). Times are the caller's clock in ms; positions are dp on the [DeskArea]. */
sealed interface DeskOp {
    /**
     * Opens [app], or brings it forward (restoring it if minimised). [by] is who asked: `you` marks it touched, `ai` marks
     * it Ai's in the turn under way. [behind] opens or restores it just under the window in front (a [FocusDecision.BEHIND]
     * arrival). [size] is a made app's manifest size in dp.
     */
    data class Open(val app: AppRef, val at: Long, val by: String = WorldEvent.YOU, val behind: Boolean = false, val size: DeskSize? = null) : DeskOp

    /** A press anywhere in [app]'s window: it comes to the front. [person] false is Ai's own focus (never touches it). */
    data class Focus(val app: String, val at: Long, val person: Boolean = true) : DeskOp

    /** The desktop pressed: nothing in front. */
    data object Unfocus : DeskOp

    /** The title bar pressed to drag: a snapped or maximised window comes back to its normal size under the pointer. */
    data class DragStart(val app: String, val pointer: DeskPoint, val at: Long) : DeskOp

    /** The title bar dragged by [dx], [dy]: the window follows the hand, no easing. */
    data class Drag(val app: String, val dx: Double, val dy: Double) : DeskOp

    /** Let go at [pointer]: within a snap zone it snaps (§2.3), else it stays where it was dragged. */
    data class DragEnd(val app: String, val pointer: DeskPoint) : DeskOp

    /** An edge or corner dragged: at least [DeskPlacer.MIN]. */
    data class Resize(val app: String, val edge: Edge, val dx: Double, val dy: Double) : DeskOp

    /** `□` or a double-click on the title bar: maximised, or back. */
    data class ToggleMaximise(val app: String, val at: Long) : DeskOp

    /** `–`: to its taskbar cell. */
    data class Minimise(val app: String) : DeskOp

    /** `Alt Shift` and an arrow (§9.1): ↑ maximise or restore, ← and → snap, ↓ restore, then minimise. */
    data class SnapKey(val app: String, val direction: Direction, val at: Long) : DeskOp

    /** `✕`. [person] closing a window during Ai's turn is remembered: Ai's next arrival there only marks it. */
    data class Close(val app: String, val person: Boolean = true) : DeskOp

    /** Keep (the title bar's menu): never put away. */
    data class Keep(val app: String, val kept: Boolean) : DeskOp

    /** `Ctrl \``, `Ctrl Shift \``: the next or previous window in the switcher's order comes forward. */
    data class Cycle(val forward: Boolean, val at: Long) : DeskOp

    /** Show desktop: every window minimised, nothing in front. */
    data object MinimiseAll : DeskOp

    /** Put windows away: every window closed but the kept ones. */
    data object PutAway : DeskOp

    /** An icon dragged to another cell (snapped to the grid); one already there takes the icon's old cell. */
    data class MoveIcon(val app: String, val col: Int, val row: Int) : DeskOp

    /** Ai starts a turn in this world. */
    data class TurnStart(val at: Long) : DeskOp

    /** Ai arrives in [app], as [FocusPolicy] decided. */
    data class Arrive(val app: AppRef, val decision: FocusDecision, val at: Long, val size: DeskSize? = null) : DeskOp

    /** Ai opened or changed a page this turn ([BrowserTabs] holds the tab): the Browser is where the answer is. */
    data object AiShowed : DeskOp

    /** Ai made or changed the app [slug] this turn: its window is where the answer is. */
    data class AiMadeApp(val slug: String) : DeskOp

    /** Ai's turn ends: [DeskTidy] puts away what Ai opened and the person never touched, keeping the answer. */
    data class TurnEnd(val at: Long) : DeskOp

    /** The Browser's tabs, changed (tabs are their own reducer: [BrowserTabs]). */
    data class Tabs(val tabs: BrowserTabs) : DeskOp

    enum class Direction { UP, LEFT, RIGHT, DOWN }
}
