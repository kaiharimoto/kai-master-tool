package com.kaiharimoto.mastertool.core.input

/**
 * The mouse of Neue Master Tool, as data — the same contract as [DeskShortcuts]:
 * one table, one pure resolve, and the help dialog renders the table, so it
 * cannot describe a gesture that does nothing.
 *
 * kai's brief, and how it was reconciled. The asks were: right-click
 * quick-adds; left-click drags and interacts; holding left opens the menu;
 * holding left *on a deck card* adds a copy; right-clicking a deck card removes
 * it. Two pairs of those collide, and the more specific one wins each time —
 * so a hold means the menu in the pool and "one more" in the deck, and a
 * right-click means "in" in the pool and "out" in the deck. That leaves the
 * deck's menu (move to side, groups, copy name) with no gesture, and it goes on
 * Shift + right-click: Shift already means "the other way" everywhere else
 * (Shift Enter, Shift + right-click in the pool both send a card to the side).
 */
enum class MouseTarget(val heading: String) {
    POOL("A card in the pool"),
    DECK("A card in the deck"),
}

enum class MouseGesture(val label: String) {
    HOVER("Hover"),
    CLICK("Click"),
    DOUBLE_CLICK("Double-click"),
    SHIFT_DOUBLE_CLICK("Shift double-click"),
    HOLD("Hold"),
    RIGHT_CLICK("Right-click"),
    SHIFT_RIGHT_CLICK("Shift right-click"),
    DRAG("Drag"),
}

enum class MouseAction {
    /** The inspector shows it. */
    INSPECT,

    /** Selected: the inspector keeps it and Delete acts on it. */
    SELECT,

    /** Into the main deck, or the extra deck for a card that lives there. */
    ADD,
    ADD_TO_SIDE,

    /** One more copy, into the section the card is already in. */
    ADD_COPY,

    /** This copy, out of the deck. */
    REMOVE,

    MENU,

    /** Lifted, and carried: the drop rules are the tablet's. */
    PICK_UP,
}

data class MouseBinding(
    val target: MouseTarget,
    val gesture: MouseGesture,
    val action: MouseAction,
    val description: String,
)

object DeskMouse {

    /** How long a primary press must stay still to be a hold rather than a click. */
    const val HOLD_MS = 450L

    /** Two presses this close together, on the same card, are a double-click. */
    const val DOUBLE_CLICK_MS = 350L

    val all: List<MouseBinding> = listOf(
        MouseBinding(MouseTarget.POOL, MouseGesture.HOVER, MouseAction.INSPECT, "Read it in the inspector"),
        MouseBinding(MouseTarget.POOL, MouseGesture.CLICK, MouseAction.SELECT, "Select it"),
        MouseBinding(MouseTarget.POOL, MouseGesture.RIGHT_CLICK, MouseAction.ADD, "Add it to the deck"),
        MouseBinding(MouseTarget.POOL, MouseGesture.SHIFT_RIGHT_CLICK, MouseAction.ADD_TO_SIDE, "Add it to the side deck"),
        MouseBinding(MouseTarget.POOL, MouseGesture.DOUBLE_CLICK, MouseAction.ADD, "Add it to the deck"),
        MouseBinding(MouseTarget.POOL, MouseGesture.SHIFT_DOUBLE_CLICK, MouseAction.ADD_TO_SIDE, "Add it to the side deck"),
        MouseBinding(MouseTarget.POOL, MouseGesture.HOLD, MouseAction.MENU, "Everything else"),
        MouseBinding(MouseTarget.POOL, MouseGesture.DRAG, MouseAction.PICK_UP, "Pick it up and place it"),

        MouseBinding(MouseTarget.DECK, MouseGesture.HOVER, MouseAction.INSPECT, "Read it in the inspector"),
        MouseBinding(MouseTarget.DECK, MouseGesture.CLICK, MouseAction.SELECT, "Select it"),
        MouseBinding(MouseTarget.DECK, MouseGesture.HOLD, MouseAction.ADD_COPY, "Add another copy"),
        MouseBinding(MouseTarget.DECK, MouseGesture.RIGHT_CLICK, MouseAction.REMOVE, "Remove this copy"),
        MouseBinding(MouseTarget.DECK, MouseGesture.SHIFT_RIGHT_CLICK, MouseAction.MENU, "Everything else"),
        MouseBinding(MouseTarget.DECK, MouseGesture.DRAG, MouseAction.PICK_UP, "Move it, or drop it on the pool to remove it"),
    )

    fun resolve(target: MouseTarget, gesture: MouseGesture): MouseAction? =
        all.firstOrNull { it.target == target && it.gesture == gesture }?.action

    /** The gesture that does [action] on [target], for a hint beside a menu row. */
    fun gestureFor(target: MouseTarget, action: MouseAction): MouseGesture? =
        all.firstOrNull { it.target == target && it.action == action }?.gesture
}
