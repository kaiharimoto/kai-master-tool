package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.FxTrust

/**
 * What the app hands the goldfish instrument (`WorldHost.goldfish`): the library as the goldfish trusts it, each deck's kept
 * targets and results, and how many hands to deal by default on this device (2,000 on the desk, 500 on a phone).
 */
interface GoldfishHost {
    val trust: FxTrust

    /** [deckId]'s goldfish file (its targets, its kept results); empty when it has none. */
    fun doc(deckId: String): GoldfishDoc

    /** Hands a run deals when it names no number. */
    val defaultHands: Int get() = Goldfish.DESK_HANDS
}
