package com.kaiharimoto.mastertool.core.input

/**
 * The hints the builder writes into its own empty places, in the words of the
 * hand in front of it (touch swarm, rec 20).
 *
 * They were written for a mouse — "point at a card", "right-click", "hold the
 * button" — and were then fixed one sentence at a time, in three files, for a
 * finger. Here, each is a function of [touch], and `DeskWordsTest` refuses a
 * finger's sentence that speaks of clicking, pointing, hovering or a key, and
 * holds every gesture word to one that `DeskTouch` has.
 */
object DeskWords {

    /** The first-run note on a tablet: the three things a finger does to a card. */
    const val TOUCH_INTRO = "Tap reads a card. Double-tap adds it, or takes it out. Press and hold opens it large."

    /** What the help says of the rest: pinch, zen, and how to learn a button's name. */
    val TOUCH_FOOTER = "Pinch in to see the whole deck smaller; pinch out to fit it again. " +
        "In deep zen a pinch breaks the deck into its groups and sets their gaps. Hold any button to read its name."

    /** The empty inspector. */
    fun inspectorEmpty(touch: Boolean): String =
        if (touch) {
            "Tap a card to read it here. Press and hold one to open it large."
        } else {
            "Point at a card to read it. Click one to keep it here. Hold the button down on one to open it large."
        }

    /**
     * An empty extra or side deck: how a card gets there. [quickAddLandsHere] is whether the
     * pool's quick add lands here — the side deck with the pool's Side
     * switch on, or the main and extra decks with it off.
     */
    fun emptySection(touch: Boolean, quickAddLandsHere: Boolean): String = when {
        touch && !quickAddLandsHere -> "Press and hold a card in the pool for the side deck, or drag it here"
        touch -> "Double-tap a card in the pool, or drag it here"
        !quickAddLandsHere -> "Shift right-click a card in the pool, or drag it here"
        else -> "Right-click a card in the pool, or drag it here"
    }

    /** The Groups panel's own line: how a group is looked at alone, and where the rest is. */
    fun groupsHelp(touch: Boolean): String =
        if (touch) {
            "Tap a colour square to see that group alone. Press and hold a group for the rest."
        } else {
            "Click a colour square to see that group alone. Right-click a group for the rest."
        }

    /** Every sentence written for a finger, for the test. */
    val touchSentences: List<String>
        get() = listOf(
            TOUCH_INTRO,
            TOUCH_FOOTER,
            inspectorEmpty(true),
            emptySection(true, true),
            emptySection(true, false),
            groupsHelp(true),
        )
}
