package com.kaiharimoto.neue.ai.course

import com.kaiharimoto.mastertool.core.ai.course.ReplayLibrary
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Game [n] of the kept DuelingBook replay [e] on the Duel page (1.1.51, kai: "the DuelingBook replays would need to be
 * converted to work with our player"): turned into our own log off the frame thread, its cards named by the pool, and
 * opened as a replay to step through — never written to the duel's replays unless the person keeps it.
 */
fun NeueHolders.playDbReplay(e: ReplayLibrary.Entry, n: Int) {
    val shelf = ai.replays
    val index = builder.index
    ai.scope.launch {
        try {
            val result = withContext(Dispatchers.Default) { shelf.games(e) { name -> index.byName(name)?.id?.value } }
            val g = result?.games?.getOrNull(n - 1) ?: result?.games?.firstOrNull()
            if (g == null) {
                shelf.said = "That replay holds no game to play on the table."
                return@launch
            }
            val game = withContext(Dispatchers.Default) { DuelGame.of(g.record) }
            duel.openGame(g.record.name, game)
            shelf.open = false
            neue.go(Page.DUEL)
            val left = listOfNotNull(
                g.notes.takeIf { it > 0 }?.let { "$it play${if (it == 1) "" else "s"} kept in words" },
                g.unseen.takeIf { it > 0 }?.let { "$it card${if (it == 1) "" else "s"} never shown" },
            )
            neue.note = Note("Playing ${g.record.name}: ${g.moves} moves" + if (left.isEmpty()) "" else " · " + left.joinToString(" · "))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            shelf.said = "That replay could not be played: ${t.message ?: "it did not read as a duel"}."
        }
    }
}
