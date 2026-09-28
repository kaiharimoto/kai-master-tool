package com.kaiharimoto.mastertool.core.input

/**
 * The fingers of Neue Master Tool on a tablet (1.3.0), as data beside
 * [DeskMouse] — the same contract: one table, one pure resolve, and the help
 * dialog renders it on a touch screen.
 *
 * A finger has one button and no hover, so the mouse's grammar is re-spoken in
 * what a finger can say, keeping every meaning:
 *
 * - **A tap reads.** It selects the card, and the inspector — which follows the
 *   pointer on a desk — follows the selection.
 * - **A double-tap is the right-click**: the quick edit, in from the pool and out
 *   of the deck, exactly as a right-click is on the desk.
 * - **Press and hold opens the card large** with every action beside it, as a
 *   held left button does — which is also where the held right button's extra
 *   copy is, and the side deck.
 * - **A drag moves the card.** In the pool, which scrolls, only a drag *across* it
 *   picks a card up; up and down is the pool scrolling, as a finger expects.
 *
 * `DeskTouchTest` holds the table to the mouse's: nothing a mouse can do to a
 * card is out of a finger's reach.
 */
enum class TouchGesture(val label: String) {
    TAP("Tap"),
    DOUBLE_TAP("Double-tap"),
    LONG_PRESS("Press and hold"),
    DRAG("Drag"),
}

data class TouchBinding(
    val target: MouseTarget,
    val gesture: TouchGesture,
    val action: MouseAction,
    val description: String,
)

object DeskTouch {

    /** Two taps this close together, on the same card, are a double-tap. */
    const val DOUBLE_TAP_MS = 300L

    val all: List<TouchBinding> = listOf(
        TouchBinding(MouseTarget.POOL, TouchGesture.TAP, MouseAction.SELECT, "Select it, and read it in the inspector"),
        TouchBinding(MouseTarget.POOL, TouchGesture.DOUBLE_TAP, MouseAction.ADD, "Add it to the deck"),
        TouchBinding(MouseTarget.POOL, TouchGesture.LONG_PRESS, MouseAction.VIEW, "Open it large, with everything else"),
        TouchBinding(MouseTarget.POOL, TouchGesture.DRAG, MouseAction.PICK_UP, "Drag it across onto the deck; up and down scrolls the pool"),

        TouchBinding(MouseTarget.DECK, TouchGesture.TAP, MouseAction.SELECT, "Select it, and read it in the inspector"),
        TouchBinding(MouseTarget.DECK, TouchGesture.DOUBLE_TAP, MouseAction.REMOVE, "Remove this copy"),
        TouchBinding(MouseTarget.DECK, TouchGesture.LONG_PRESS, MouseAction.VIEW, "Open it large, with everything else"),
        TouchBinding(MouseTarget.DECK, TouchGesture.DRAG, MouseAction.PICK_UP, "Move it, or drop it on the pool to remove it"),
    )

    fun resolve(target: MouseTarget, gesture: TouchGesture): MouseAction? =
        all.firstOrNull { it.target == target && it.gesture == gesture }?.action

    /**
     * Whether a drag that has crossed the slop by ([dx], [dy]) picks a card up on
     * [target]: always in the deck, and in the scrolling pool only when it runs
     * more across than along.
     */
    fun picksUp(target: MouseTarget, dx: Float, dy: Float): Boolean =
        target != MouseTarget.POOL || kotlin.math.abs(dx) >= kotlin.math.abs(dy)
}
