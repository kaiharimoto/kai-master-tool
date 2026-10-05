package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.Format

/**
 * Where a copy limit comes from (Phase B §3): the pool's own status for today's list ([current]), or a dated list
 * from the banlist history (`core/cards`, `LimitationList.match`). [DeckValidator] takes one, so "is this deck legal
 * on the March list" is the same check as "is it legal now".
 */
fun interface BanSource {
    fun statusOf(card: Card): BanStatus

    /** The list's name for the words ("April 2025 Lists (TCG)"); null for the pool's own. */
    val label: String? get() = null

    companion object {
        /** The pool's status in [format]: the list in force when the pool was last fetched. */
        fun current(format: Format): BanSource = BanSource { it.banStatus(format) }

        /** No list at all: three of anything (Genesys, 1.1.1). */
        val NONE: BanSource = BanSource { BanStatus.UNLIMITED }
    }
}
