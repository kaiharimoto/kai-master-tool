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

    /** Two fingers down and up together, anywhere in the window: undo (touch swarm, rec 22). */
    TWO_FINGER_TAP("Two-finger tap"),

    /** Three: redo. */
    THREE_FINGER_TAP("Three-finger tap"),

    /** The S Pen's side button held while the pen taps: the right-click (touch swarm, rec 29). */
    PEN_BUTTON_TAP("Pen button tap"),
}

/** A gesture that belongs to the window rather than to a card. */
data class WindowTouch(val gesture: TouchGesture, val action: DeskAction, val description: String)

data class TouchBinding(
    val target: MouseTarget,
    val gesture: TouchGesture,
    val action: MouseAction,
    val description: String,
)

object DeskTouch {

    /** Two taps this close together, on the same card, are a double-tap. */
    const val DOUBLE_TAP_MS = 300L

    /**
     * How long a card must have been selected before a finger's art chip appears on
     * it (touch swarm, rec 7): past the double-tap window, so a double-tap's second
     * tap lands on the card and never on a chip that appeared under it at the first.
     */
    const val CHIP_DELAY_MS = DOUBLE_TAP_MS + 20

    /** How long a control's name stays after the finger that held it lifts (touch swarm, rec 8). */
    const val LABEL_LINGER_MS = 1500L

    /** The chip only on cards drawn at least this wide, in dp: below it, it covers what a finger aims at. */
    const val CHIP_MIN_CARD_DP = 48f

    val all: List<TouchBinding> = listOf(
        TouchBinding(MouseTarget.POOL, TouchGesture.TAP, MouseAction.SELECT, "Select it, and read it in the inspector"),
        TouchBinding(MouseTarget.POOL, TouchGesture.DOUBLE_TAP, MouseAction.ADD, "Add it to the deck"),
        TouchBinding(MouseTarget.POOL, TouchGesture.LONG_PRESS, MouseAction.VIEW, "Open it large, with everything else"),
        TouchBinding(MouseTarget.POOL, TouchGesture.DRAG, MouseAction.PICK_UP, "Drag it across onto the deck; up and down scrolls the pool"),
        TouchBinding(MouseTarget.POOL, TouchGesture.PEN_BUTTON_TAP, MouseAction.ADD, "With the pen, its button held: add it"),

        TouchBinding(MouseTarget.DECK, TouchGesture.TAP, MouseAction.SELECT, "Select it, and read it in the inspector"),
        TouchBinding(MouseTarget.DECK, TouchGesture.DOUBLE_TAP, MouseAction.REMOVE, "Remove this copy"),
        TouchBinding(MouseTarget.DECK, TouchGesture.LONG_PRESS, MouseAction.VIEW, "Open it large, with everything else"),
        TouchBinding(MouseTarget.DECK, TouchGesture.DRAG, MouseAction.PICK_UP, "Move it, or drop it on the pool to remove it"),
        TouchBinding(MouseTarget.DECK, TouchGesture.PEN_BUTTON_TAP, MouseAction.REMOVE, "With the pen, its button held: remove this copy"),
    )

    /** The window's own gestures: undo and redo, the help renders them (touch swarm, rec 22). */
    val window: List<WindowTouch> = listOf(
        WindowTouch(TouchGesture.TWO_FINGER_TAP, DeskAction.UNDO, "Undo"),
        WindowTouch(TouchGesture.THREE_FINGER_TAP, DeskAction.REDO, "Redo"),
    )

    /** How long a pressed control keeps its pressed look after a quick tap, so the tap is seen (rec 14). */
    const val PRESS_ECHO_MS = 100L

    /** How long a card a finger just added or dropped is ringed where it landed (rec 15). */
    const val REVEAL_MS = 700L

    /** The shortest hold a finger is asked for: the system's long-press delay, but never under this. */
    const val MIN_HOLD_MS = 400L

    /** A finger's hold, from the system's long-press delay (rec 11): the platform's feel, not the desk's 450. */
    fun holdMs(systemLongPressMs: Long): Long = maxOf(MIN_HOLD_MS, systemLongPressMs)

    /**
     * Whether a finger's press and lift were a tap (rec 23): lifted within the hold
     * and a little more, and not travelled past the slop. A longer press is a
     * resting thumb, and the chrome under it hears nothing — the same rule the
     * cards use, so chrome and cards agree on what a tap is.
     */
    fun isTap(downMs: Long, upMs: Long, travel: Float, slop: Float, holdMs: Long): Boolean =
        upMs - downMs <= holdMs + 150 && travel <= slop

    /**
     * What [gesture] on [target] does. While a group is being drawn up ([drafting])
     * the deck is being chosen from, not edited, and a tap there is a vote: a quick
     * second vote is two votes, never the double-tap's removal (touch swarm, rec 7).
     */
    fun resolve(target: MouseTarget, gesture: TouchGesture, drafting: Boolean = false): MouseAction? {
        val removes = gesture == TouchGesture.DOUBLE_TAP || gesture == TouchGesture.PEN_BUTTON_TAP
        if (drafting && target == MouseTarget.DECK && removes) return MouseAction.SELECT
        return all.firstOrNull { it.target == target && it.gesture == gesture }?.action
    }

    /**
     * Whether a drag that has crossed the slop by ([dx], [dy]) picks a card up on
     * [target]: always in the deck, and in the scrolling pool only when it runs
     * more across than along.
     */
    fun picksUp(target: MouseTarget, dx: Float, dy: Float, finger: Boolean = true): Boolean =
        target != MouseTarget.POOL || kotlin.math.abs(dx) >= (if (finger) PICK_UP_RATIO else 1f) * kotlin.math.abs(dy)

    /**
     * A finger picks a pool card up when it runs at least this much across for its
     * run along (rec 12): about a 55 degree cone, since a finger's drag is rarely
     * level, and a steeper one is the pool scrolling.
     */
    const val PICK_UP_RATIO = 0.7f
}
