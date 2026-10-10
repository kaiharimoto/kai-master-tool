package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import kotlinx.serialization.Serializable

/**
 * What a turn's end is worth to the seat that played it (Phase E, E.2; `docs/phases/E.md` §4.2): a won game above anything,
 * else a weighted sum of what the table measures. **The weights are a player's, not a ranking of boards** (the Mapper's boards
 * are still ranked only by the person's weights): version 0's are written down here, rated on the ladder, and replaced only
 * by weights tuned on won games (E.5).
 */
@Serializable
data class ValueWeights(
    val version: Int = 0,
    /** Each interruption the board holds for their turn, and each that negates, again. */
    val interruptions: Double = 3.0,
    val negates: Double = 1.0,
    /** Each face-up monster, each Set card, each card kept in hand, each hand trap kept. */
    val bodies: Double = 1.0,
    val set: Double = 0.5,
    val hand: Double = 0.5,
    val handTraps: Double = 1.0,
    /** Each 1000 of their life points taken, and of yours paid or lost. */
    val damage: Double = 1.0,
    val taken: Double = 0.5,
    /** Each monster they still control, and each interruption their face-up board still holds. */
    val theirBodies: Double = 1.5,
    val theirInterruptions: Double = 3.0,
)

/** A table at a turn's end, valued for [seat] by [weights]. */
class TurnValue(val weights: ValueWeights = ValueWeights()) {
    /** What the value was made of, for words: the seat's board, theirs, and the life points. */
    class Parts(val mine: BoardTraits, val theirBodies: Int, val theirInterruptions: Int, val dealt: Int, val taken: Int, val won: Boolean, val lost: Boolean)

    fun parts(t: FxTable, seat: Int, fodder: Set<Int> = emptySet()): Parts {
        val s = t.state
        val them = 1 - seat
        val theirs = BoardTraits.of(t, them)
        return Parts(
            mine = BoardTraits.of(t, seat, fodder),
            theirBodies = BattlePlan.monsters(s, them).size,
            theirInterruptions = theirs.interruptions,
            dealt = (DuelState.START_LP - s.seats[them].lp).coerceAtLeast(0),
            taken = (DuelState.START_LP - s.seats[seat].lp).coerceAtLeast(0),
            won = s.seats[them].lp <= 0,
            lost = s.seats[seat].lp <= 0,
        )
    }

    fun of(t: FxTable, seat: Int, fodder: Set<Int> = emptySet()): Double = of(parts(t, seat, fodder))

    fun of(p: Parts): Double {
        if (p.won) return WIN
        if (p.lost) return LOSS
        val w = weights
        return w.interruptions * p.mine.interruptions + w.negates * p.mine.negates + w.bodies * p.mine.bodies + w.set * p.mine.set +
            w.hand * p.mine.hand + w.handTraps * p.mine.handInterruptions + w.damage * p.dealt / 1000.0 - w.taken * p.taken / 1000.0 -
            w.theirBodies * p.theirBodies - w.theirInterruptions * p.theirInterruptions
    }

    companion object {
        /** A won game: above any table. */
        const val WIN = 1_000_000.0
        const val LOSS = -1_000_000.0

        /** Version 0: the weights above, as written. */
        val V0 = TurnValue()

        /** The parts in words: "a win" or "2 interruptions, 3 bodies, 1200 dealt; they keep 1 monster". */
        fun words(p: Parts): String {
            if (p.won) return "a win: their life points reach 0"
            if (p.lost) return "your life points reach 0"
            val mine = listOfNotNull(
                "${p.mine.interruptions} interruption${if (p.mine.interruptions == 1) "" else "s"}",
                "${p.mine.bodies} ${if (p.mine.bodies == 1) "body" else "bodies"}",
                p.mine.set.takeIf { it > 0 }?.let { "$it set" },
                "${p.mine.hand} kept in hand",
                p.dealt.takeIf { it > 0 }?.let { "$it damage dealt" },
                p.taken.takeIf { it > 0 }?.let { "$it of your life points spent" },
            ).joinToString(", ")
            val theirs = listOfNotNull(
                p.theirBodies.takeIf { it > 0 }?.let { "$it monster${if (it == 1) "" else "s"}" },
                p.theirInterruptions.takeIf { it > 0 }?.let { "$it interruption${if (it == 1) "" else "s"} face-up" },
            )
            return mine + if (theirs.isEmpty()) "; their field is empty" else "; they keep ${theirs.joinToString(" and ")}"
        }
    }
}
