package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelFolds
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.Line
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.net.Windows

/**
 * Who is looking at a room's table: a player at [Seat] sees what that seat sees; a watcher sees everything, each
 * hiding on their own screen what they choose (kai: "everything, but they can choose what they want to see or
 * hide"), unless the room keeps watchers to the [publicOnly] table, what a stranger across a real table sees.
 */
sealed class Viewer {
    data class Seat(val seat: Int) : Viewer()
    data class Watcher(val publicOnly: Boolean) : Viewer()

    /** The viewer as [DuelView.of] takes it: a seat; null for everything; [PUBLIC] for neither hand, nothing face-down. */
    val sight: Int?
        get() = when (this) {
            is Seat -> seat
            is Watcher -> if (publicOnly) PUBLIC else null
        }

    companion object {
        /** No seat at all: [com.kaiharimoto.mastertool.core.duel.DuelSight] shows such a viewer only what is face-up. */
        const val PUBLIC = -1
    }
}

/**
 * One room's duel on kai's computer (the Lounge): the game, each seat's response windows, a take-back asked for.
 * Pure, like [DuelHost], which it is built on — every intent is resolved against what its seat was shown and made
 * on the real table, and each viewer is sent the table as they may see it.
 */
data class RoomTable(
    val game: DuelGame,
    val windows: Map<Int, String> = mapOf(0 to Windows.ACTIVATIONS, 1 to Windows.ACTIVATIONS),
    /** The seat that asked to take back its last move, waiting on the other's answer. */
    val takeBackFrom: Int? = null,
) {
    val secret: Long get() = game.header.seed

    /** The result of a seat's intent: the table after it, or the same table and why not. */
    data class Made(val table: RoomTable, val refused: String? = null)

    /** [seat]'s intent made on the table, if it can be: its refs resolved against its own view first. */
    fun intent(seat: Int, w: Wire.Intent, at: Long, by: Provenance): Made {
        val (resolved, why) = DuelHost.resolve(game.state, seat, secret, w.actions)
        if (resolved == null) return Made(this, why ?: "No")
        val r = DuelHost.act(game, seat, resolved, windows, w.force, at, by = by.copy(net = true))
        if (!r.ok) return Made(this, r.problem ?: "No")
        // A move made after asking to take one back: the ask is over.
        val asked = takeBackFrom.takeUnless { it == seat && resolved.any { a -> !a.social } }
        return Made(copy(game = r.game, takeBackFrom = asked))
    }

    fun setWindows(seat: Int, setting: String): RoomTable =
        if (setting in Windows.ALL) copy(windows = windows + (seat to setting)) else this

    /** [seat] asks the other player to let it take back its last move. */
    fun askTakeBack(seat: Int): RoomTable = copy(takeBackFrom = seat)

    /** The other player's answer to a take-back: yes undoes the asker's last move when it is still theirs. */
    fun answerTakeBack(seat: Int, yes: Boolean): Made {
        val asker = takeBackFrom ?: return Made(this, "No one asked to take a move back")
        if (asker == seat) return Made(this, "The other player answers")
        if (!yes) return Made(copy(takeBackFrom = null), "They would rather you did not take it back")
        val last = game.entries.getOrNull(game.cursor - 1)
        if (last == null || last.seat != asker || !game.canUndo) return Made(copy(takeBackFrom = null), "The last move is not theirs to take back")
        return Made(copy(game = game.undo(), takeBackFrom = null))
    }

    /** The log from [from] as [viewer] reads it. */
    fun lines(viewer: Viewer, from: Int, catalog: DuelCatalog, folds: DuelFolds<*>? = null): List<Line> =
        DuelHost.lines(game, from, viewer.sight, catalog, folds)

    /** Everything [viewer] is sent after a change: the table as they see it, the new lines, whose answer is awaited. */
    fun update(viewer: Viewer, from: Int, catalog: DuelCatalog, folds: DuelFolds<*>? = null): Wire.Update =
        Wire.Update(
            cursor = game.cursor,
            view = DuelView.of(game.state, viewer.sight, secret),
            lines = lines(viewer, minOf(from, game.cursor), catalog, folds),
            waitingFor = game.state.window?.responder,
            takeBackFrom = takeBackFrom,
        )

    companion object {
        fun start(header: DuelHeader, at: Long): RoomTable = RoomTable(DuelGame.start(header, at))
    }
}
