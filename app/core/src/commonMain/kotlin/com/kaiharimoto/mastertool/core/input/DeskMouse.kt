package com.kaiharimoto.mastertool.core.input

/**
 * The mouse of Neue Master Tool, as data — the same contract as [DeskShortcuts]:
 * one table, one pure resolve, and the help dialog renders the table, so it
 * cannot describe a gesture that does nothing.
 *
 * kai's brief, as it settled after 1.0.3: **right-click adds, to the main
 * deck**, wherever the card is — a pool card goes in, a deck card gets another
 * copy. (1.0.3 read "right-clicking it will remove it" as the deck's
 * right-click; kai meant right-click to add, everywhere.) So taking a copy out
 * is Shift + right-click on a deck card — Shift is "the other way" throughout,
 * as Shift Enter and Shift right-click in the pool send a card to the side —
 * and a hold is the menu on every card, which is the same answer in both
 * places rather than two.
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

    /**
     * One more copy, into the main deck: beside it, for a card already there; at
     * the end of the main deck for one in the side; beside it in the extra deck
     * for a card the rules keep there.
     */
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
        MouseBinding(MouseTarget.DECK, MouseGesture.RIGHT_CLICK, MouseAction.ADD_COPY, "Add another copy to the deck"),
        MouseBinding(MouseTarget.DECK, MouseGesture.SHIFT_RIGHT_CLICK, MouseAction.REMOVE, "Remove this copy"),
        MouseBinding(MouseTarget.DECK, MouseGesture.HOLD, MouseAction.MENU, "Everything else"),
        MouseBinding(MouseTarget.DECK, MouseGesture.DRAG, MouseAction.PICK_UP, "Move it, or drop it on the pool to remove it"),
    )

    fun resolve(target: MouseTarget, gesture: MouseGesture): MouseAction? =
        all.firstOrNull { it.target == target && it.gesture == gesture }?.action

    /** The gesture that does [action] on [target], for a hint beside a menu row. */
    fun gestureFor(target: MouseTarget, action: MouseAction): MouseGesture? =
        all.firstOrNull { it.target == target && it.action == action }?.gesture
}
