package com.kaiharimoto.mastertool.core.input

/**
 * The mouse and the finger on Ai World's desktop (`docs/world/DESKTOP.md` §9), as data — a table pair beside
 * [ShootoutMouse] and [ShootoutTouch]. The help dialog renders both, and `WorldInputTest` holds every mouse action to a
 * finger's form. The keys are [DeskShortcuts]' `WORLD` rows.
 */
enum class WorldTarget(val heading: String) {
    ICON("A desktop icon"),
    CELL("A taskbar cell"),
    TITLE("A title bar"),
    EDGE("A window's edge"),
    TAB("A tab"),
    DESKTOP("The desktop"),
    AVATAR("Ai on the desktop"),
    LINK("A link in a page or Thoughts"),
    DOCK("The phone's dock"),
    SWITCHER("A row of the phone's switcher"),
}

enum class WorldAction {
    /** An icon picked out (inverted). */
    SELECT,

    /** An app opened, or brought forward; on the window in front, minimised (a taskbar cell). */
    OPEN,

    /** The thing's menu: Open, Pin, Rename, Show code, Delete; Minimise, Maximise, Snap, Keep, Close; the desktop's. */
    MENU,

    /** An icon to another cell of the grid; a window moved, and snapped at an edge. */
    MOVE,

    /** A window's size. */
    RESIZE,

    /** Maximised, or back. */
    MAXIMISE,

    /** Closed: a window from its cell, a tab. */
    CLOSE,

    /** Tabs put in another order. */
    REORDER,

    /** Nothing in front. */
    UNFOCUS,

    /** Thoughts at this moment. */
    THOUGHTS,

    /** Petted (`AvatarPlay`). */
    PET,

    /** Picked up; let go, it hops back to its work. */
    PICK_UP,

    /** Opened here. */
    FOLLOW_LINK,

    /** Opened in a new tab. */
    NEW_TAB,

    /** The launcher (the phone's dock). */
    LAUNCHER,

    /** The previous or next app (the phone's dock). */
    STEP,
}

data class WorldBinding(
    val target: WorldTarget,
    /** The words of the gesture, as the help dialog prints them. */
    val gesture: String,
    val action: WorldAction,
    val description: String,
)

object WorldMouse {
    val all: List<WorldBinding> = listOf(
        WorldBinding(WorldTarget.ICON, "Click", WorldAction.SELECT, "Pick it out"),
        WorldBinding(WorldTarget.ICON, "Double-click", WorldAction.OPEN, "Open it"),
        WorldBinding(WorldTarget.ICON, "Right-click", WorldAction.MENU, "Open, Pin, Rename, Show code, Delete"),
        WorldBinding(WorldTarget.ICON, "Drag", WorldAction.MOVE, "Move it to another cell"),
        WorldBinding(WorldTarget.CELL, "Click", WorldAction.OPEN, "Open, bring forward, or minimise the one in front"),
        WorldBinding(WorldTarget.CELL, "Middle-click", WorldAction.CLOSE, "Close it"),
        WorldBinding(WorldTarget.CELL, "Right-click", WorldAction.MENU, "Pin, Close, Close Ai's windows"),
        WorldBinding(WorldTarget.TITLE, "Drag", WorldAction.MOVE, "Move it; let go at an edge or a corner to snap"),
        WorldBinding(WorldTarget.TITLE, "Double-click", WorldAction.MAXIMISE, "Maximise or restore"),
        WorldBinding(WorldTarget.TITLE, "Right-click", WorldAction.MENU, "Minimise, Maximise, Snap, Keep, Close"),
        WorldBinding(WorldTarget.EDGE, "Drag", WorldAction.RESIZE, "Resize"),
        WorldBinding(WorldTarget.TAB, "Click", WorldAction.SELECT, "Show its page"),
        WorldBinding(WorldTarget.TAB, "Middle-click", WorldAction.CLOSE, "Close it"),
        WorldBinding(WorldTarget.TAB, "Drag", WorldAction.REORDER, "Put it elsewhere in the strip"),
        WorldBinding(WorldTarget.DESKTOP, "Click", WorldAction.UNFOCUS, "Nothing in front"),
        WorldBinding(WorldTarget.DESKTOP, "Right-click", WorldAction.MENU, "Show desktop, Put windows away, Tidy icons, New world, World settings"),
        WorldBinding(WorldTarget.AVATAR, "Click", WorldAction.THOUGHTS, "Thoughts, at this moment"),
        WorldBinding(WorldTarget.AVATAR, "Hover", WorldAction.PET, "Pet it"),
        WorldBinding(WorldTarget.AVATAR, "Drag", WorldAction.PICK_UP, "Pick it up; let go and it hops back to its work"),
        WorldBinding(WorldTarget.LINK, "Click", WorldAction.FOLLOW_LINK, "Open it here"),
        WorldBinding(WorldTarget.LINK, "Middle-click", WorldAction.NEW_TAB, "Open it in a new tab"),
    )
}

object WorldTouch {
    val all: List<WorldBinding> = listOf(
        WorldBinding(WorldTarget.ICON, "Tap", WorldAction.OPEN, "Open it at once, as a launcher does"),
        WorldBinding(WorldTarget.ICON, "Press and hold", WorldAction.MENU, "Open, Pin, Rename, Show code, Delete"),
        WorldBinding(WorldTarget.ICON, "Hold, then drag", WorldAction.MOVE, "Move it to another cell"),
        WorldBinding(WorldTarget.CELL, "Tap", WorldAction.OPEN, "Open, bring forward, or minimise the one in front"),
        WorldBinding(WorldTarget.CELL, "Press and hold", WorldAction.MENU, "Pin, Close, Close Ai's windows"),
        WorldBinding(WorldTarget.CELL, "Press and hold, then Close", WorldAction.CLOSE, "Close it from its menu"),
        WorldBinding(WorldTarget.TITLE, "Drag", WorldAction.MOVE, "Move it; let go at an edge or a corner to snap"),
        WorldBinding(WorldTarget.TITLE, "Double-tap", WorldAction.MAXIMISE, "Maximise or restore"),
        WorldBinding(WorldTarget.TITLE, "Press and hold", WorldAction.MENU, "Minimise, Maximise, Snap, Keep, Close"),
        WorldBinding(WorldTarget.TITLE, "Tap □, or snap to an edge", WorldAction.RESIZE, "A finger sizes a window by maximising and snapping"),
        WorldBinding(WorldTarget.TAB, "Tap", WorldAction.SELECT, "Show its page"),
        WorldBinding(WorldTarget.TAB, "Tap ✕", WorldAction.CLOSE, "Close it (always shown to a finger)"),
        WorldBinding(WorldTarget.TAB, "Hold, then drag", WorldAction.REORDER, "Put it elsewhere in the strip"),
        WorldBinding(WorldTarget.DESKTOP, "Tap", WorldAction.UNFOCUS, "Nothing in front"),
        WorldBinding(WorldTarget.DESKTOP, "Press and hold", WorldAction.MENU, "Show desktop, Put windows away, Tidy icons, New world, World settings"),
        WorldBinding(WorldTarget.AVATAR, "Tap", WorldAction.THOUGHTS, "Thoughts, at this moment"),
        WorldBinding(WorldTarget.AVATAR, "Stroke", WorldAction.PET, "Pet it"),
        WorldBinding(WorldTarget.AVATAR, "Press and hold", WorldAction.PICK_UP, "Pick it up; let go and it hops back to its work"),
        WorldBinding(WorldTarget.LINK, "Tap", WorldAction.FOLLOW_LINK, "Open it here"),
        WorldBinding(WorldTarget.LINK, "Press and hold", WorldAction.NEW_TAB, "Open it in a new tab"),
        WorldBinding(WorldTarget.DOCK, "Swipe up", WorldAction.LAUNCHER, "The launcher, full screen"),
        WorldBinding(WorldTarget.DOCK, "Swipe sideways", WorldAction.STEP, "The previous or next app"),
        WorldBinding(WorldTarget.SWITCHER, "Tap", WorldAction.OPEN, "Show the app"),
        WorldBinding(WorldTarget.SWITCHER, "Swipe left", WorldAction.CLOSE, "Close it"),
    )
}
