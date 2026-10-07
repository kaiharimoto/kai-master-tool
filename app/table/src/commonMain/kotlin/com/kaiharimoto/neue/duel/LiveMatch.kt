package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.DuelGame

/**
 * A game the table shows that is not the person's to play: Ai vs Ai (`docs/phases/C.md` §6), run by Neue's
 * `DuelMatches` on the desk and Android, where Ai's connections are. The table asks it only what it draws.
 */
interface LiveMatch {
    /** The match's table while it is shown, else null. */
    val live: DuelGame?
    val running: Boolean

    /** The person's Stop: the match ends where it stands; its table stays to be read until [close]. */
    fun stop()

    /** Back to the duel in play. */
    fun close()

    companion object {
        /** What the person is told when they reach for a table Ai vs Ai is playing. */
        const val ON_THE_TABLE = "Ai vs Ai is on the table. Stop it, or press Back to your duel."
    }
}

/** No match: a table where Ai vs Ai is never played (the browser's). */
object NoMatch : LiveMatch {
    override val live: DuelGame? get() = null
    override val running: Boolean get() = false
    override fun stop() = Unit
    override fun close() = Unit
}
