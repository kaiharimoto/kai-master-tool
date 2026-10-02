package com.kaiharimoto.mastertool.core.input

/**
 * The mouse and the finger on Present (1.0.70), as data — a second table pair beside
 * [DeskMouse] and [DeskTouch], because a slide's text box or shape is not a card: it has no
 * "open it large", and the desk's tables hold every target to that. The help dialog renders
 * both tables, and a test holds every mouse action to a finger's form.
 */
enum class PresentTarget(val heading: String) {
    ELEMENT("Something on a slide"),
    CANVAS("The slide around it"),
    HANDLE("A handle on the selection"),
    SORTER("A slide in the list"),
    STAGE_CARD("A card in a deck slide's panel"),
    PRESENTING("The slide while presenting"),
}

enum class PresentAction {
    SELECT,
    ADD_TO_SELECTION,
    MOVE,
    DUPLICATE_MOVE,
    EDIT_TEXT,
    MENU,
    MARQUEE,
    ZOOM,
    PAN,
    RESIZE,
    RESIZE_KEEP_SHAPE,
    ROTATE,
    OPEN_SLIDE,
    REORDER,
    FOCUS_CARD,
    NEXT,
    PREVIOUS,
    LASER,
}

data class PresentBinding(
    val target: PresentTarget,
    /** The words of the gesture, as the help dialog prints them. */
    val gesture: String,
    val action: PresentAction,
    val description: String,
)

object PresentMouse {
    val all: List<PresentBinding> = listOf(
        PresentBinding(PresentTarget.ELEMENT, "Click", PresentAction.SELECT, "Select it"),
        PresentBinding(PresentTarget.ELEMENT, "Shift click", PresentAction.ADD_TO_SELECTION, "Add it to the selection, or take it out"),
        PresentBinding(PresentTarget.ELEMENT, "Drag", PresentAction.MOVE, "Move it; guides catch edges and middles"),
        PresentBinding(PresentTarget.ELEMENT, "Alt drag", PresentAction.DUPLICATE_MOVE, "Move a copy"),
        PresentBinding(PresentTarget.ELEMENT, "Double-click", PresentAction.EDIT_TEXT, "Edit its words"),
        PresentBinding(PresentTarget.ELEMENT, "Right-click", PresentAction.MENU, "Cut, copy, order, group, delete"),
        PresentBinding(PresentTarget.CANVAS, "Drag", PresentAction.MARQUEE, "Select everything the box touches"),
        PresentBinding(PresentTarget.CANVAS, "Right-click", PresentAction.MENU, "Paste, new slide, background"),
        PresentBinding(PresentTarget.CANVAS, "Ctrl wheel", PresentAction.ZOOM, "Zoom the slide"),
        PresentBinding(PresentTarget.HANDLE, "Drag", PresentAction.RESIZE, "Size it; Alt sizes from the middle"),
        PresentBinding(PresentTarget.HANDLE, "Shift drag", PresentAction.RESIZE_KEEP_SHAPE, "Size it, keeping its shape"),
        PresentBinding(PresentTarget.HANDLE, "Drag the round handle", PresentAction.ROTATE, "Turn it; Shift snaps to 15°"),
        PresentBinding(PresentTarget.SORTER, "Click", PresentAction.OPEN_SLIDE, "Edit the slide; Shift or Ctrl picks several"),
        PresentBinding(PresentTarget.SORTER, "Drag", PresentAction.REORDER, "Move it in the show"),
        PresentBinding(PresentTarget.SORTER, "Right-click", PresentAction.MENU, "Duplicate, hide, delete, a new slide after"),
        PresentBinding(PresentTarget.STAGE_CARD, "Click", PresentAction.FOCUS_CARD, "Talk about it on this slide, or not"),
        PresentBinding(PresentTarget.PRESENTING, "Click", PresentAction.NEXT, "Next"),
        PresentBinding(PresentTarget.PRESENTING, "Right-click", PresentAction.PREVIOUS, "Back"),
        PresentBinding(PresentTarget.PRESENTING, "Move with the laser on", PresentAction.LASER, "Point"),
    )

    fun resolve(target: PresentTarget, gesture: String): PresentAction? =
        all.firstOrNull { it.target == target && it.gesture == gesture }?.action
}

object PresentTouch {
    val all: List<PresentBinding> = listOf(
        PresentBinding(PresentTarget.ELEMENT, "Tap", PresentAction.SELECT, "Select it"),
        PresentBinding(PresentTarget.ELEMENT, "Tap with Select several on", PresentAction.ADD_TO_SELECTION, "Add it to the selection, or take it out"),
        PresentBinding(PresentTarget.ELEMENT, "Drag", PresentAction.MOVE, "Move it; guides catch edges and middles"),
        PresentBinding(PresentTarget.ELEMENT, "Duplicate, then drag", PresentAction.DUPLICATE_MOVE, "Move a copy"),
        PresentBinding(PresentTarget.ELEMENT, "Double-tap", PresentAction.EDIT_TEXT, "Edit its words"),
        PresentBinding(PresentTarget.ELEMENT, "Press and hold", PresentAction.MENU, "Cut, copy, order, group, delete"),
        PresentBinding(PresentTarget.CANVAS, "Drag", PresentAction.MARQUEE, "Select everything the box touches"),
        PresentBinding(PresentTarget.CANVAS, "Press and hold", PresentAction.MENU, "Paste, new slide, background"),
        PresentBinding(PresentTarget.CANVAS, "Pinch", PresentAction.ZOOM, "Zoom the slide"),
        PresentBinding(PresentTarget.CANVAS, "Two-finger drag", PresentAction.PAN, "Move round a zoomed slide"),
        PresentBinding(PresentTarget.HANDLE, "Drag", PresentAction.RESIZE, "Size it"),
        PresentBinding(PresentTarget.HANDLE, "Drag a corner with Keep shape on", PresentAction.RESIZE_KEEP_SHAPE, "Size it, keeping its shape"),
        PresentBinding(PresentTarget.HANDLE, "Drag the round handle", PresentAction.ROTATE, "Turn it"),
        PresentBinding(PresentTarget.SORTER, "Tap", PresentAction.OPEN_SLIDE, "Edit the slide"),
        PresentBinding(PresentTarget.SORTER, "Drag", PresentAction.REORDER, "Move it in the show"),
        PresentBinding(PresentTarget.SORTER, "Press and hold", PresentAction.MENU, "Duplicate, hide, delete, a new slide after"),
        PresentBinding(PresentTarget.STAGE_CARD, "Tap", PresentAction.FOCUS_CARD, "Talk about it on this slide, or not"),
        PresentBinding(PresentTarget.PRESENTING, "Tap the right half", PresentAction.NEXT, "Next"),
        PresentBinding(PresentTarget.PRESENTING, "Tap the left half", PresentAction.PREVIOUS, "Back"),
        PresentBinding(PresentTarget.PRESENTING, "Press and hold", PresentAction.LASER, "Point while held"),
    )
}
