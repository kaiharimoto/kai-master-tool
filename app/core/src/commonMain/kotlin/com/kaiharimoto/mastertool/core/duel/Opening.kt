package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlinx.serialization.Serializable

/**
 * The opening roll (1.0.87; kai: "at the start of the duel … a dice roll simulation between the two players using
 * two dice … after determining a winner (higher), the player who wins chooses to go first or second").
 *
 * Before turn 1 each seat throws two dice ([DuelAction.OpeningRoll], its values stamped on commit as every die is);
 * the higher sum wins, a tie throws again, and the winner chooses ([DuelAction.GoFirst]), which sets who has turn 1.
 * Until then the turn does not start — no phase moves, no End Turn, and turns that start themselves wait
 * ([TurnStart]). Public: both seats see every throw, so [DuelView] carries it whole.
 *
 * A duel with no opening roll ([DuelState.opening] null — every duel before 1.0.87, a one-player table, the setting
 * off) begins as it always did, the header's [DuelHeader.first] going first.
 */
@Serializable
data class Opening(
    /** Each seat's dice this round, empty until it throws. */
    val dice: List<List<Int>> = listOf(emptyList(), emptyList()),
    /** Each seat's throw this round, so every screen plays the same physics. */
    val throws: List<DiceThrow?> = listOf(null, null),
    /** Which round of throwing: a tie starts the next. */
    val round: Int = 1,
    /** The last round tied: both throws stand until the next one is made. */
    val tied: Boolean = false,
    /** The seat that threw higher, once both have thrown and the sums differ. */
    val winner: Int? = null,
    /** The seat that goes first, once the winner has chosen: the opening is over. */
    val first: Int? = null,
) {
    /** The winner has chosen: the duel is under way. */
    val decided: Boolean get() = first != null

    /** [seat]'s dice this round add up to this (0 before it throws). */
    fun sum(seat: Int): Int = dice.getOrNull(seat).orEmpty().sum()

    fun thrown(seat: Int): Boolean = dice.getOrNull(seat).orEmpty().isNotEmpty()

    /** Whether [seat] throws next: no winner yet, and either a tie stands or it has not thrown this round. */
    fun waitsOn(seat: Int): Boolean = !decided && winner == null && (tied || !thrown(seat))

    companion object {
        /** [s] with [a] made: the seat's dice down, and the round settled when both have thrown. */
        fun roll(s: DuelState, a: DuelAction.OpeningRoll): Outcome {
            val o = s.opening ?: return Outcome.Refused("This duel has no opening roll")
            if (a.seat !in 0..1) return Outcome.Refused("No such seat")
            if (o.decided || o.winner != null) return Outcome.Refused("The roll is over")
            // Unstamped (a preview): the dice are not known yet.
            val values = a.values.takeIf { v -> v.size == 2 && v.all { it in 1..6 } } ?: if (a.values.isEmpty()) listOf(0, 0)
                else return Outcome.Refused("Two dice, 1 to 6 each")
            // A tie stands until either seat throws again: then a new round, both to throw.
            val start = if (o.tied) o.copy(dice = listOf(emptyList(), emptyList()), throws = listOf(null, null), round = o.round + 1, tied = false) else o
            if (start.thrown(a.seat)) return Outcome.Refused("${DuelWords.seatName(s, a.seat)} has thrown: the other seat throws now")
            val next = start.copy(
                dice = start.dice.mapIndexed { i, d -> if (i == a.seat) values else d },
                throws = start.throws.mapIndexed { i, t -> if (i == a.seat) a.toss else t },
            )
            if (!next.thrown(0) || !next.thrown(1)) return Outcome.Ok(s.copy(opening = next))
            val (a0, a1) = next.sum(0) to next.sum(1)
            val settled = when {
                a0 == a1 -> next.copy(tied = true)
                else -> next.copy(winner = if (a0 > a1) 0 else 1)
            }
            return Outcome.Ok(s.copy(opening = settled))
        }

        /** The winner's choice made: who goes first has turn 1. */
        fun choose(s: DuelState, a: DuelAction.GoFirst): Outcome {
            val o = s.opening ?: return Outcome.Refused("This duel has no opening roll")
            if (o.decided) return Outcome.Refused("Who goes first is decided")
            val w = o.winner ?: return Outcome.Refused("Roll first: the higher roll chooses")
            if (a.seat != w) return Outcome.Refused("${DuelWords.seatName(s, w)} won the roll and chooses")
            val first = if (a.first) w else 1 - w
            return Outcome.Ok(s.copy(opening = o.copy(first = first), active = first))
        }

        /** Why [a] waits for the opening roll, or null when it need not: the turn's flow waits for who goes first. */
        fun waits(s: DuelState, a: DuelAction): String? {
            val o = s.opening ?: return null
            if (o.decided) return null
            return when (a) {
                is DuelAction.Phase, DuelAction.EndTurn, is DuelAction.Propose, is DuelAction.Attack ->
                    if (o.winner == null) "Roll for who goes first: drag your dice onto the field, or type roll"
                    else "${DuelWords.seatName(s, o.winner)} won the roll: go first or second?"
                else -> null
            }
        }
    }
}
