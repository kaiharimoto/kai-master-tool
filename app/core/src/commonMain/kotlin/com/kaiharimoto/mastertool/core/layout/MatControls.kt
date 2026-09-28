package com.kaiharimoto.mastertool.core.layout

/**
 * A thing on the table you can press that is not a card.
 *
 * There is exactly one kind, and adding a second should be an argument rather
 * than an edit. `docs/classic/DESIGN.md` §10 says the table has no affordances drawn on
 * it, because a table does not do that either, and that stance is why the guide
 * exists at all — so every control here is spent against it.
 *
 * Shuffling earns the exception on one point: it is the only thing in the game
 * you do to a *pile as a whole*, and every other way of reaching it is somewhere
 * else. There is a button in the bar and a key on a keyboard nobody holding a
 * tablet has, and both of them are a long way from the deck they are about —
 * whereas a real deck is shuffled by picking it up, and the place your hand
 * already is when you think about shuffling is exactly where the deck is.
 */
enum class MatControl(val pile: BoardSlot) {
    SHUFFLE_DECK(BoardSlot.Deck),
    SHUFFLE_EXTRA(BoardSlot.ExtraDeck),
}

/**
 * Where the table's own controls sit, and what a finger on the felt has landed on.
 *
 * Solved in core beside [BoardLayout] rather than positioned in a composable,
 * for the reason every other piece of geometry on this stage is: the hit test
 * and the drawing have to agree exactly, and the only way to guarantee that is
 * for both to read the same function.
 */
object MatControls {

    /** How wide a control is, as a share of a card's width. */
    private const val WIDTH = 0.70f

    /** And how tall. A chip lying on the felt, not a button standing on it. */
    private const val HEIGHT = 0.24f

    /** The air between the pile and the control belonging to it, in card widths. */
    private const val CLEARANCE = 0.10f

    /**
     * The footprint of [control], in mat pixels, or null if its pile has no slot.
     *
     * Directly under the pile it is about, because that is the whole of what
     * makes it legible without a label: an unlabelled mark under the deck is
     * about the deck. Both piles this can be about sit in the bottom row of the
     * field, so both controls land in the band between the board and the hand,
     * which is empty on this screen.
     */
    fun slotFor(control: MatControl, layout: BoardLayout): Slot? {
        val pile = layout[control.pile] ?: return null
        val width = layout.cardWidth * WIDTH
        val height = layout.cardWidth * HEIGHT
        return Slot(
            left = pile.centerX - width / 2f,
            top = pile.bottom + layout.cardWidth * CLEARANCE,
            width = width,
            height = height,
        )
    }

    /** Every control that has somewhere to be, for a renderer to walk. */
    fun all(layout: BoardLayout): List<Pair<MatControl, Slot>> =
        MatControl.entries.mapNotNull { control ->
            slotFor(control, layout)?.let { control to it }
        }

    /**
     * What is under ([x], [y]) in mat pixels, if anything.
     *
     * Asked *before* the cards, and that ordering is the one thing about this
     * that could go wrong: a control lives in the band below the board, which is
     * empty of zones but is somewhere a card can perfectly well be put down. A
     * card left on top of a control should be the thing your finger gets — so
     * the caller checks the cards first and only falls through to here.
     */
    fun at(layout: BoardLayout, x: Float, y: Float): MatControl? =
        all(layout).firstOrNull { (_, slot) -> slot.contains(x, y) }?.first
}
