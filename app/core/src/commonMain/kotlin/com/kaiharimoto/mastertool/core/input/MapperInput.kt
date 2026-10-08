package com.kaiharimoto.mastertool.core.input

/**
 * The mouse and the finger on Gameplay Mapper (Phase M step M1, `docs/phases/M.md` §6), as data — a table pair beside
 * [ShootoutMouse] and [ShootoutTouch]: a board is read and replayed, never moved. The help dialog renders both, and a test
 * holds every mouse action to a finger's form.
 *
 * The keys are [DeskShortcuts]' `MAPPER` rows: ↑ and ↓ walk the boards, Enter plays the cheapest line, R maps dealt hands.
 */
enum class MapperTarget(val heading: String) {
    BOARD("A board in the library"),
    LINE("A line to a board"),
    STARTER("A starter in the table"),
    WEIGHT("A trait's weight"),
    SHARE("A share of hands"),
    CARD("A card on a board"),
}

enum class MapperAction {
    /** The board, or the starter, in the inspector. */
    INSPECT,

    /** The line played again on the Duel page. */
    REPLAY,

    /** The weight set: more is better, less is better, or nothing. */
    WEIGH,

    /** A filter from it: at least this much. */
    FILTER,

    /** The starter's boards listed in the library. */
    SHOW_BOARDS,

    /** Only the boards with the card, or only the ones without it. */
    CARD_RULE,
}

data class MapperBinding(
    val target: MapperTarget,
    /** The words of the gesture, as the help dialog prints them. */
    val gesture: String,
    val action: MapperAction,
    val description: String,
)

object MapperMouse {
    val all: List<MapperBinding> = listOf(
        MapperBinding(MapperTarget.BOARD, "Click", MapperAction.INSPECT, "Read it in the inspector: its cards, every trait, its lines"),
        MapperBinding(MapperTarget.BOARD, "Double-click", MapperAction.REPLAY, "Play its cheapest line on the Duel page"),
        MapperBinding(MapperTarget.LINE, "Click Play", MapperAction.REPLAY, "Play the line on the Duel page, step by step"),
        MapperBinding(MapperTarget.STARTER, "Click", MapperAction.INSPECT, "Read it: its boards, its odds, what it makes with a partner"),
        MapperBinding(MapperTarget.STARTER, "Double-click", MapperAction.SHOW_BOARDS, "Its boards in the library"),
        MapperBinding(MapperTarget.WEIGHT, "Drag", MapperAction.WEIGH, "More is better to the right, less to the left"),
        MapperBinding(MapperTarget.SHARE, "Click", MapperAction.FILTER, "Boards with at least this much"),
        MapperBinding(MapperTarget.CARD, "Right-click", MapperAction.CARD_RULE, "Only boards with it, or only boards without it"),
    )
}

object MapperTouch {
    val all: List<MapperBinding> = listOf(
        MapperBinding(MapperTarget.BOARD, "Tap", MapperAction.INSPECT, "Read it in the inspector: its cards, every trait, its lines"),
        MapperBinding(MapperTarget.BOARD, "Double-tap", MapperAction.REPLAY, "Play its cheapest line on the Duel page"),
        MapperBinding(MapperTarget.LINE, "Tap Play", MapperAction.REPLAY, "Play the line on the Duel page, step by step"),
        MapperBinding(MapperTarget.STARTER, "Tap", MapperAction.INSPECT, "Read it: its boards, its odds, what it makes with a partner"),
        MapperBinding(MapperTarget.STARTER, "Double-tap", MapperAction.SHOW_BOARDS, "Its boards in the library"),
        MapperBinding(MapperTarget.WEIGHT, "Drag", MapperAction.WEIGH, "More is better to the right, less to the left"),
        MapperBinding(MapperTarget.SHARE, "Tap", MapperAction.FILTER, "Boards with at least this much"),
        MapperBinding(MapperTarget.CARD, "Hold", MapperAction.CARD_RULE, "Only boards with it, or only boards without it"),
    )
}
