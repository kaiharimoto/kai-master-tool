package com.kaiharimoto.mastertool.core.haptics

/**
 * What the builder's hand events feel like on a tablet (touch swarm, rec 13),
 * in the classic app's one vocabulary ([Haptic]).
 *
 * Only for what the hand did, and only when it worked: a tap that only selects,
 * an add the deck refused and a drop that went nowhere are felt as nothing, so
 * "no buzz" always means "not in the deck". Played through the platform's own
 * haptic feedback, which honours the system's touch-feedback switch and needs
 * no permission.
 */
enum class DeskEvent {
    HOLD_OPENED,
    PICKED_UP,
    SLOT_CHANGED,
    DROPPED,
    DROPPED_ON_CARD,
    DROP_REFUSED,
    ADDED,
    ADD_REFUSED,
    REMOVED,
    ART_STEPPED,
    ZOOM_LIMIT,
    SNAPPED,
    SELECTED,
}

object DeskFeel {
    fun of(event: DeskEvent): Haptic? = when (event) {
        DeskEvent.HOLD_OPENED -> Haptic.PEEK
        DeskEvent.PICKED_UP -> Haptic.LIFT
        DeskEvent.SLOT_CHANGED -> Haptic.DETENT
        DeskEvent.DROPPED -> HapticScore.landing(ontoCard = false)
        DeskEvent.DROPPED_ON_CARD -> HapticScore.landing(ontoCard = true)
        DeskEvent.ADDED -> Haptic.DEAL
        DeskEvent.REMOVED -> Haptic.SLIDE
        DeskEvent.ART_STEPPED -> Haptic.FLIP
        DeskEvent.ZOOM_LIMIT -> Haptic.DETENT
        DeskEvent.SNAPPED -> Haptic.STACK
        DeskEvent.SELECTED, DeskEvent.DROP_REFUSED, DeskEvent.ADD_REFUSED -> null
    }
}
