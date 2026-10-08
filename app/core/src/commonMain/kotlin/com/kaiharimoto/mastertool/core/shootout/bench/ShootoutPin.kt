package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Stratum

/**
 * A pin carried onto what the bench can deal now (the red team, 2026-10): a pin chosen for one target — "G1 second"
 * against an opponent — meets another deck, the deck alone, or a siding plan gone in a sync. It was dropped without a
 * word, and the session dealt both turns. Now the turn is what a pin keeps: the same stratum if it can be dealt, else the
 * same turn in the nearest game (the deck alone, or game 1 when sided hands are waiting), else no pin — and every change
 * is said.
 */
object ShootoutPin {

    /** Where a pin lands: [pin] the one kept (null for none), [said] the words for a change, null when nothing changed. */
    data class Carried(val pin: Stratum?, val said: String?)

    /** [pin] carried onto [bench]. */
    fun carry(pin: Stratum?, bench: Bench): Carried = carry(pin, bench.strata, bench.waiting, bench.opponentName)

    /** [pin] carried onto [strata] (what can be dealt), with [waiting] saying why a stratum cannot. */
    fun carry(pin: Stratum?, strata: List<Stratum>, waiting: Map<Stratum, String> = emptyMap(), opponent: String? = null): Carried {
        if (pin == null || pin in strata) return Carried(pin, null)
        val was = ShootoutWords.situation(pin, opponent)
        val why = waiting[pin]?.let { " ($it)" } ?: ""
        val near = strata.firstOrNull { it.goingFirst == pin.goingFirst && !it.sided }
            ?: strata.firstOrNull { it.goingFirst == pin.goingFirst }
        return if (near != null) {
            Carried(near, "“$was” cannot be dealt here$why, so the session deals “${ShootoutWords.situation(near, opponent)}” only.")
        } else {
            Carried(null, "“$was” cannot be dealt here$why, so the pin is off and the picker chooses.")
        }
    }
}
