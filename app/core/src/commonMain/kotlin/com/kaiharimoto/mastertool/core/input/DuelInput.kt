package com.kaiharimoto.mastertool.core.input

/**
 * The mouse and the finger on the duel table, as data — a third table pair beside [DeskMouse] and
 * [PresentMouse], because a card on a duel table has verbs a deck's card does not (summon, set,
 * activate, target) and places a deck has no word for (a zone, a pile, the chain). The help dialog
 * renders both tables, and a test holds every mouse action to a finger's form.
 *
 * The grammar, against DuelingBook's menu on every card:
 * - **Drag anything anywhere.** What the card will do is drawn where it will land before you let go.
 * - **Right-click does the obvious thing** for that card where it is: a monster in the hand is
 *   summoned, a spell activated, a trap set, a set card activated, the deck drawn from, the opponent's
 *   card pointed at. A finger's double-tap is the same.
 * - **Click, or hold, for everything else** (1.0.78): every verb for the card stands beside it on
 *   the table, each with its key; the inspector reads the card.
 * - An open pile closes on a press outside it, or when a card is carried out of it.
 * - The keyboard has a key per verb over the card under the pointer (`DeskShortcuts`, [DeskScope.DUEL]),
 *   and the command line takes what a player says across a table.
 */
enum class DuelTarget(val heading: String) {
    MY_CARD("Your card, in the hand or on the field"),
    THEIR_CARD("Their card"),
    PILE("A deck, graveyard, banished pile or Extra Deck"),
    STRIP_CARD("A card in an open pile"),
    CHAIN("The chain"),
    SEAT("A player's name and life points"),
    /** Their monster, or their life points, while an attack waits for what it attacks (1.0.86). */
    ATTACKING("While attacking"),
    PHASE("The phases"),
    TABLE("The table around them"),
    /** The microphone beside the command line (1.0.87): the M key for a hand on the mouse or the glass. */
    MIC("The microphone beside the command line"),
    /** The two dice in front of the field before turn 1 (1.0.87, the opening roll). */
    DICE("Your dice, before turn 1"),
    /** The die and the coin beside the Extra Deck, or where they landed (1.0.96). */
    CHANCE("Your die and coin, by your Extra Deck"),
}

enum class DuelInputAction {
    INSPECT,
    DEFAULT_VERB,
    VERBS,
    MOVE,
    ADD_TO_SELECTION,
    PING,
    OPEN_PILE,
    RESOLVE,
    CLEAR_CHAIN,
    LP_PAD,
    GO_PHASE,
    MARQUEE,
    COMMAND,
    CLEAR_SELECTION,
    /** Declare the attack waiting: on their monster, or directly on their life points or hand (1.0.86). */
    ATTACK,
    /** Put the waiting attack away. */
    CANCEL_ATTACK,
    /** Listen while held, and send what was said when let go (1.0.87, as holding M). */
    SPEAK,
    /** Throw the opening roll's dice onto the field (1.0.87). */
    THROW,
}

data class DuelBinding(
    val target: DuelTarget,
    /** The words of the gesture, as the help dialog prints them. */
    val gesture: String,
    val action: DuelInputAction,
    val description: String,
)

object DuelMouse {
    const val CLICK = "Click"
    const val RIGHT = "Right-click"
    const val DRAG = "Drag"
    const val HOLD = "Hold"
    const val SHIFT_CLICK = "Shift click"
    /** ⌘ click on a Mac (1.0.90). */
    const val CTRL_CLICK = "Ctrl click"
    const val ALT_CLICK = "Alt click"
    const val DOUBLE = "Double-click"

    val all: List<DuelBinding> = listOf(
        DuelBinding(DuelTarget.MY_CARD, CLICK, DuelInputAction.INSPECT, "Select it: what it can do stands beside it, and the inspector reads it"),
        DuelBinding(DuelTarget.MY_CARD, RIGHT, DuelInputAction.DEFAULT_VERB, "The default action: summon, activate, set, flip summon; in the Battle Phase, attack with it"),
        DuelBinding(DuelTarget.MY_CARD, DOUBLE, DuelInputAction.DEFAULT_VERB, "The default action, as a right-click"),
        DuelBinding(DuelTarget.MY_CARD, DRAG, DuelInputAction.MOVE, "Put it anywhere; onto a monster attaches it — in the Battle Phase onto theirs attacks it, onto their hand or life points attacks directly; Alt sets it, Shift puts it under a pile"),
        DuelBinding(DuelTarget.MY_CARD, HOLD, DuelInputAction.VERBS, "Every verb for it, beside it"),
        DuelBinding(DuelTarget.MY_CARD, CTRL_CLICK, DuelInputAction.ADD_TO_SELECTION, "Put it in the selection, or take it out: each card gets its number, and one key, verb or drag then moves them all, as one undo"),
        DuelBinding(DuelTarget.MY_CARD, SHIFT_CLICK, DuelInputAction.ADD_TO_SELECTION, "Select a run: every card from the last one picked to this one, in one hand, pile or row"),
        DuelBinding(DuelTarget.MY_CARD, ALT_CLICK, DuelInputAction.PING, "Point at it for the other player"),
        DuelBinding(DuelTarget.THEIR_CARD, CLICK, DuelInputAction.INSPECT, "Select it and read it; Target stands beside it"),
        DuelBinding(DuelTarget.THEIR_CARD, RIGHT, DuelInputAction.DEFAULT_VERB, "Target it: an arrow both players see"),
        DuelBinding(DuelTarget.THEIR_CARD, DRAG, DuelInputAction.MOVE, "Take control of it, or send it somewhere"),
        DuelBinding(DuelTarget.THEIR_CARD, HOLD, DuelInputAction.VERBS, "Every verb for it"),
        DuelBinding(DuelTarget.THEIR_CARD, ALT_CLICK, DuelInputAction.PING, "Point at it"),
        DuelBinding(DuelTarget.THEIR_CARD, CTRL_CLICK, DuelInputAction.ADD_TO_SELECTION, "Put it in the selection with others: target each, or send them all away at once"),
        DuelBinding(DuelTarget.PILE, CLICK, DuelInputAction.OPEN_PILE, "Open it over the field, in rows; a press outside closes it"),
        DuelBinding(DuelTarget.PILE, RIGHT, DuelInputAction.DEFAULT_VERB, "The deck draws a card; any other pile opens"),
        DuelBinding(DuelTarget.PILE, DRAG, DuelInputAction.MOVE, "Take its top card"),
        DuelBinding(DuelTarget.PILE, ALT_CLICK, DuelInputAction.PING, "Point at it"),
        DuelBinding(DuelTarget.STRIP_CARD, CLICK, DuelInputAction.INSPECT, "Read it"),
        DuelBinding(DuelTarget.STRIP_CARD, RIGHT, DuelInputAction.DEFAULT_VERB, "From the deck to the hand; from your GY, activate; from theirs, target it"),
        DuelBinding(DuelTarget.STRIP_CARD, DRAG, DuelInputAction.MOVE, "Take it out of the pile: the pile steps aside and closes"),
        DuelBinding(DuelTarget.STRIP_CARD, HOLD, DuelInputAction.VERBS, "Every verb for it"),
        DuelBinding(DuelTarget.STRIP_CARD, CTRL_CLICK, DuelInputAction.ADD_TO_SELECTION, "Select it with others — open the GY, pick, open the banished pile, pick: the selection keeps them all"),
        DuelBinding(DuelTarget.STRIP_CARD, SHIFT_CLICK, DuelInputAction.ADD_TO_SELECTION, "Select a run of the pile, from the last one picked"),
        DuelBinding(DuelTarget.CHAIN, CLICK, DuelInputAction.RESOLVE, "Resolve the newest link"),
        DuelBinding(DuelTarget.CHAIN, RIGHT, DuelInputAction.CLEAR_CHAIN, "Clear the chain"),
        DuelBinding(DuelTarget.SEAT, CLICK, DuelInputAction.LP_PAD, "Change life points"),
        DuelBinding(DuelTarget.PHASE, CLICK, DuelInputAction.GO_PHASE, "Go to that phase, or end the turn; where the column is short, Next phase, and the phase's name lists them all"),
        DuelBinding(DuelTarget.TABLE, CLICK, DuelInputAction.CLEAR_SELECTION, "Select nothing"),
        DuelBinding(DuelTarget.TABLE, DRAG, DuelInputAction.MARQUEE, "Select every card the box touches"),
        DuelBinding(DuelTarget.TABLE, RIGHT, DuelInputAction.COMMAND, "The command line"),
        DuelBinding(DuelTarget.ATTACKING, CLICK, DuelInputAction.ATTACK, "Their monster: attack it; their life points or hand: attack directly"),
        DuelBinding(DuelTarget.ATTACKING, RIGHT, DuelInputAction.CANCEL_ATTACK, "Stop attacking; so do Esc and any other verb"),
        DuelBinding(DuelTarget.MIC, HOLD, DuelInputAction.SPEAK, "Speak a command while held; let go to see it, then Enter makes it — as holding M"),
        DuelBinding(DuelTarget.DICE, DRAG, DuelInputAction.THROW, "Pick both up and throw them onto your field: let go while moving and they fly as fast as your hand"),
        DuelBinding(DuelTarget.DICE, CLICK, DuelInputAction.THROW, "Toss them onto the field with a fling of their own"),
        DuelBinding(DuelTarget.CHANCE, DRAG, DuelInputAction.THROW, "Pick it up and throw it: the die rolls, the coin flips, as fast as your hand let go"),
        DuelBinding(DuelTarget.CHANCE, CLICK, DuelInputAction.THROW, "Roll the die or flip the coin onto the field; one lying out is thrown again from where it lies"),
    )

    fun resolve(target: DuelTarget, gesture: String): DuelInputAction? =
        all.firstOrNull { it.target == target && it.gesture == gesture }?.action
}

object DuelTouch {
    const val TAP = "Tap"
    const val DOUBLE = "Double-tap"
    const val DRAG = "Drag"
    const val HOLD = "Press and hold"
    /** After a press and hold on a card (1.0.90): select mode, until the selection is empty or let go. */
    const val SEVERAL = "Tap, after a press and hold"

    val all: List<DuelBinding> = listOf(
        DuelBinding(DuelTarget.MY_CARD, TAP, DuelInputAction.INSPECT, "Select it: what it can do stands beside it, and the inspector reads it"),
        DuelBinding(DuelTarget.MY_CARD, DOUBLE, DuelInputAction.DEFAULT_VERB, "The default action: summon, activate, set, flip summon; in the Battle Phase, attack with it"),
        DuelBinding(DuelTarget.MY_CARD, DRAG, DuelInputAction.MOVE, "Put it anywhere; onto a monster attaches it — in the Battle Phase onto theirs attacks it, onto their hand or life points attacks directly"),
        DuelBinding(DuelTarget.MY_CARD, HOLD, DuelInputAction.VERBS, "Every verb for it, beside it, Point among them — and select mode: each tap after it adds a card"),
        DuelBinding(DuelTarget.MY_CARD, SEVERAL, DuelInputAction.ADD_TO_SELECTION, "Put it in the selection, or take it out; the bar over your hand then moves them all"),
        DuelBinding(DuelTarget.MY_CARD, "Hold, then Point", DuelInputAction.PING, "Point at it for the other player"),
        DuelBinding(DuelTarget.THEIR_CARD, TAP, DuelInputAction.INSPECT, "Read it in the inspector"),
        DuelBinding(DuelTarget.THEIR_CARD, DOUBLE, DuelInputAction.DEFAULT_VERB, "Target it"),
        DuelBinding(DuelTarget.THEIR_CARD, DRAG, DuelInputAction.MOVE, "Take control of it, or send it somewhere"),
        DuelBinding(DuelTarget.THEIR_CARD, HOLD, DuelInputAction.VERBS, "Every verb for it"),
        DuelBinding(DuelTarget.THEIR_CARD, "Hold, then Point", DuelInputAction.PING, "Point at it"),
        DuelBinding(DuelTarget.THEIR_CARD, SEVERAL, DuelInputAction.ADD_TO_SELECTION, "Put it in the selection with others"),
        DuelBinding(DuelTarget.PILE, TAP, DuelInputAction.OPEN_PILE, "Open it over the field; a tap outside closes it"),
        DuelBinding(DuelTarget.PILE, DOUBLE, DuelInputAction.DEFAULT_VERB, "The deck draws a card; any other pile opens"),
        DuelBinding(DuelTarget.PILE, DRAG, DuelInputAction.MOVE, "Take its top card"),
        DuelBinding(DuelTarget.PILE, HOLD, DuelInputAction.PING, "Point at it"),
        DuelBinding(DuelTarget.STRIP_CARD, TAP, DuelInputAction.INSPECT, "Read it"),
        DuelBinding(DuelTarget.STRIP_CARD, DOUBLE, DuelInputAction.DEFAULT_VERB, "From the deck to the hand; from your GY, activate; from theirs, target it"),
        DuelBinding(DuelTarget.STRIP_CARD, DRAG, DuelInputAction.MOVE, "Take it out of the pile: the pile steps aside and closes"),
        DuelBinding(DuelTarget.STRIP_CARD, HOLD, DuelInputAction.VERBS, "Every verb for it"),
        DuelBinding(DuelTarget.STRIP_CARD, SEVERAL, DuelInputAction.ADD_TO_SELECTION, "Select it with others, across piles"),
        DuelBinding(DuelTarget.CHAIN, TAP, DuelInputAction.RESOLVE, "Resolve the newest link"),
        DuelBinding(DuelTarget.CHAIN, HOLD, DuelInputAction.CLEAR_CHAIN, "Clear the chain"),
        DuelBinding(DuelTarget.SEAT, TAP, DuelInputAction.LP_PAD, "Change life points"),
        DuelBinding(DuelTarget.PHASE, TAP, DuelInputAction.GO_PHASE, "Go to that phase, or end the turn; where the column is short, Next phase, and the phase's name lists them all"),
        DuelBinding(DuelTarget.TABLE, TAP, DuelInputAction.CLEAR_SELECTION, "Select nothing"),
        DuelBinding(DuelTarget.TABLE, DRAG, DuelInputAction.MARQUEE, "Select every card the box touches"),
        DuelBinding(DuelTarget.TABLE, HOLD, DuelInputAction.COMMAND, "The command line"),
        DuelBinding(DuelTarget.ATTACKING, TAP, DuelInputAction.ATTACK, "Their monster: attack it; their life points or hand: attack directly"),
        DuelBinding(DuelTarget.ATTACKING, "Tap Cancel, or Back", DuelInputAction.CANCEL_ATTACK, "Stop attacking; so does any other verb"),
        DuelBinding(DuelTarget.MIC, HOLD, DuelInputAction.SPEAK, "Speak a command while held; lift to see it, then confirm it"),
        DuelBinding(DuelTarget.DICE, DRAG, DuelInputAction.THROW, "Pick both up and throw them onto your field: lift while moving and they fly as fast as your finger"),
        DuelBinding(DuelTarget.DICE, TAP, DuelInputAction.THROW, "Toss them onto the field with a fling of their own"),
        DuelBinding(DuelTarget.CHANCE, DRAG, DuelInputAction.THROW, "Pick it up and throw it: the die rolls, the coin flips, as fast as your finger let go"),
        DuelBinding(DuelTarget.CHANCE, TAP, DuelInputAction.THROW, "Roll the die or flip the coin onto the field; one lying out is thrown again from where it lies"),
    )

    fun resolve(target: DuelTarget, gesture: String): DuelInputAction? =
        all.firstOrNull { it.target == target && it.gesture == gesture }?.action
}
