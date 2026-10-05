package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.TurnStart
import com.kaiharimoto.mastertool.core.duel.dice.DiceRuns
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.Toss
import com.kaiharimoto.mastertool.core.duel.dice.TossRuns
import com.kaiharimoto.neue.duel.dice.ChanceCarry
import com.kaiharimoto.neue.duel.dice.DiceCarry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * How a turn opens, a part of [Duels]: the turns that start themselves (1.0.86) and the opening roll's dice (1.0.87).
 * [Duels] forwards every member under its own name.
 */
internal class DuelOpening(private val d: Duels) {

    // ---- turns that start themselves (1.0.86) ----------------------------------------------------------

    /** `DuelPrefs.autoDraw`, set by the page: after End Turn the next player's draw is made here (the phases are theirs, 1.0.93). */
    var autoDraw = true
    /** The turn whose opening is being made; null when none is. */
    var autoTurn by mutableStateOf<Int?>(null)

    /** A turn's opening is still to be made (paused on Ai's answer): Ai is not asked to play until it is. */
    val opening: Boolean get() = autoTurn != null
    /** The opening's group in the log: its steps are one gesture, the incoming seat's, one step of undo. */
    var autoGroup: Int? = null
    /** A step of the opening is being committed. */
    var autoActing = false

    /** The turn just begun opens by itself: on the live table of this device only, never a networked one or a replay. */
    fun beginTurn() {
        autoTurn = null
        if (!autoDraw || d.network.role != null || d.replayer.replay != null) return
        autoTurn = d.game?.state?.turn ?: return
        autoGroup = null
        if (!d.aiWatch.releasing) resumeTurn()
    }

    /**
     * Makes the opening's next steps ([com.kaiharimoto.mastertool.core.duel.TurnStart]) through [Duels.act], so Ai's
     * watches see the draw and each phase entered and left. It pauses while Ai answers a watch that fired, or holds a
     * phase change, and goes on from where it stopped once Ai has answered (or the person did not wait) and the held
     * change is made; a held change taken back (Undo) ends it, as does any table it no longer fits.
     */
    fun resumeTurn() {
        val turn = autoTurn ?: return
        var left = 4
        while (left-- > 0) {
            val g = d.game
            if (g == null || !autoDraw || d.network.role != null || d.replayer.replay != null || g.state.turn != turn) { autoTurn = null; return }
            if (d.aiWatch.held != null || d.aiWatch.aiAnswering || d.aiWatch.fired.isNotEmpty()) return
            val step = TurnStart.next(g) ?: run { autoTurn = null; return }
            // The opening is the table's, never a move put back in a phase gone by.
            val pastAt = d.insertAfter
            d.insertAfter = null
            autoActing = true
            // Never Ai's move even when Ai's End Turn began it: the person's opening is watched (1.0.86, the red team).
            val wasAi = d.aiWatch.aiActing
            d.aiWatch.aiActing = false
            val ok = try { d.act(listOf(step), g.state.active) } finally { autoActing = false; d.aiWatch.aiActing = wasAi; d.insertAfter = pastAt }
            if (!ok) { autoTurn = null; return }
        }
    }

    // ---- the opening roll (1.0.87) ----------------------------------------------------------------------

    /** `DuelPrefs.openingRoll`, set by the page: a new two-seat duel this table hosts opens with the dice. */
    var openingRoll = true
    /** The person's two dice in the hand, carried across the table before they are thrown; null when none are. */
    var diceCarry by mutableStateOf<DiceCarry?>(null)
    /** The seats whose dice are still in the air: the log holds their numbers back until they land (1.0.87). */
    var diceRolling by mutableStateOf<Set<Int>>(emptySet())
    /** The seat Ai throws and chooses for, set by the page while Ai takes its seat's turns; null otherwise. */
    var aiOpeningSeat: Int? = null

    /**
     * Whether the person throws [seat]'s dice (or chooses for it): their own seat — the guest's or the host's at a
     * networked table — or, in a hot-seat where they play both seats, either; never the seat Ai throws for.
     */
    fun mayRoll(seat: Int, playsBoth: Boolean): Boolean = when {
        d.replayer.replay != null -> false
        // Ai vs Ai being watched: its die and coin are the players', never the watcher's (the design review, finding 1).
        d.matches.live != null -> false
        d.network.role != null -> seat == d.network.mySeat
        seat == aiOpeningSeat -> false
        else -> seat == d.bottom || playsBoth
    }

    /**
     * [seat] throws its two dice: [toss] the person's own throw, or null for a fling with no hand behind it (a key,
     * `roll`, Ai), made from the same stamped randomness as the values. The guest's throw goes to the host, who stamps.
     */
    fun throwDice(seat: Int, toss: DiceThrow? = null): Boolean {
        val ok = d.act(listOf(DuelAction.OpeningRoll(seat, toss = toss)), seat)
        // The throw played out now, off the thread that draws (1.0.92): the table finds its run made, the same run.
        if (ok) d.game?.state?.opening?.throws?.getOrNull(seat)?.let { t -> d.scope.launch(Dispatchers.Default) { DiceRuns.warm(t) } }
        return ok
    }

    // ---- the table's die and coin (1.0.96) -------------------------------------------------------------

    /** The die or the coin in the person's hand, carried across the table before it is thrown; null when neither is. */
    var chanceCarry by mutableStateOf<ChanceCarry?>(null)
    /** The die and coin still in the air, as (seat, coin): the log holds what they read until they land. */
    var chanceRolling by mutableStateOf<Set<Pair<Int, Boolean>>>(emptySet())

    /**
     * [seat] rolls its die, or flips its coin ([coin]): [toss] the person's own throw, or null for one from the corner by
     * the Extra Deck (a click, `roll`, Ai), stamped with the value. The guest's goes to the host, who stamps.
     */
    fun throwChance(seat: Int, coin: Boolean, toss: Toss? = null): Boolean {
        val ok = d.act(listOf(if (coin) DuelAction.Coin(seat, toss = toss) else DuelAction.Dice(seat, toss = toss)), seat)
        // Played out now, off the thread that draws, as the opening dice are (1.0.92).
        if (ok) d.game?.state?.chance?.firstOrNull { it.seat == seat && it.coin == coin }?.let { c ->
            d.scope.launch(Dispatchers.Default) { TossRuns.of(if (c.coin) DiceSim.Shape.COIN else DiceSim.Shape.DIE, c.toss) }
        }
        return ok
    }

    /**
     * [seat]'s die ([coin] false), coin (true) or both (null) back beside its Extra Deck (1.1.9): a carry onto its home, a
     * double-click on it lying out, Alt R, `stow`. One step of the log, so the guest's goes to the host and Undo takes it back.
     */
    fun stowChance(seat: Int, coin: Boolean? = null): Boolean = d.act(listOf(DuelAction.Stow(seat, coin)), seat)

    /** The roll's winner goes first, or second. */
    fun goFirst(seat: Int, first: Boolean): Boolean = d.act(listOf(DuelAction.GoFirst(seat, first)), seat)

    /**
     * Ai at its seat throws its own dice and, winning, chooses to go first (1.0.87): one step each time it is called,
     * so the page can let the person watch the dice between. True when it did something.
     */
    fun aiOpening(): Boolean {
        val seat = aiOpeningSeat ?: return false
        val g = d.game ?: return false
        val o = g.state.opening ?: return false
        if (o.decided || d.network.role != null || d.replayer.replay != null) return false
        val wasAi = d.aiWatch.aiActing
        d.aiWatch.aiActing = true
        try {
            return when {
                o.waitsOn(seat) -> throwDice(seat)
                o.winner == seat -> goFirst(seat, true)
                else -> false
            }
        } finally { d.aiWatch.aiActing = wasAi }
    }
}
