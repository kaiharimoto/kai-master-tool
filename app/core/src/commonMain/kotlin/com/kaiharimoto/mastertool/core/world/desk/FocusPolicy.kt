package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldPrefs

/** What Ai's arrival in a window does to it (§6.1). */
enum class FocusDecision {
    /** It comes to the front. */
    RAISE,

    /** It opens or restores just under the window in front, and a notice says where Ai is. */
    BEHIND,

    /** Nothing moves forward: the avatar and the taskbar cell say where Ai is. */
    MARK,
}

/** Where Ai is arriving: the window, and whether the person closed it during this turn. */
data class FocusArrival(val app: String, val closedThisTurn: Boolean = false)

/**
 * What the person is doing as Ai arrives: typing in any field ([typing], the page's `textFocus.any`), when they last
 * pressed, typed or dragged ([lastInput], ms; 0 never), whether a menu, the launcher or a dialog is open ([overlay]),
 * and the clock ([now]).
 */
data class PersonState(val typing: Boolean = false, val lastInput: Long = 0L, val overlay: Boolean = false, val now: Long = 0L) {
    /** The person's hands were busy within [FocusPolicy.QUIET_MS]. */
    val busy: Boolean get() = typing || overlay || (lastInput > 0L && now - lastInput < FocusPolicy.QUIET_MS)
}

/**
 * Never steal focus (§6.1, kai: "we humans struggle to focus on many things at the same time"). Judged afresh at every
 * arrival — a deferred raise is never made later on its own — and the same rule decides whether Neue's page comes
 * forward for Ai (`comeForward`).
 */
object FocusPolicy {
    /** How long after the person's last press, key or drag Ai stays behind them. */
    const val QUIET_MS = 4_000L

    fun decide(arrival: FocusArrival, person: PersonState, prefs: WorldPrefs): FocusDecision =
        decide(arrival, person, prefs.follow)

    fun decide(arrival: FocusArrival, person: PersonState, follow: Boolean): FocusDecision = when {
        !follow || arrival.closedThisTurn -> FocusDecision.MARK
        person.busy -> FocusDecision.BEHIND
        else -> FocusDecision.RAISE
    }
}
