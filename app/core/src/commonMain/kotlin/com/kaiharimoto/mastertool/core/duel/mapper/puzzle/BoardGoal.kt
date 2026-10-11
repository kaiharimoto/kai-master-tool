package com.kaiharimoto.mastertool.core.duel.mapper.puzzle

import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.search.BattlePlan
import com.kaiharimoto.mastertool.core.duel.search.TurnValue
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a puzzle asks of the turn's end (Phase M, M4; `docs/phases/M.md` §5): a condition on the boards and the life points,
 * checked on the table the search ends on — never a model's say-so. Counted the way the Mapper counts boards ([BoardTraits]),
 * so "they have no answer left" means what the library's interruptions mean.
 *
 * Stored by its kind's name ([SerialName]); a kind is only ever added, never renamed.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface BoardGoal {
    /** Whether [t], at the end of [seat]'s turn, meets it. */
    fun met(t: FxTable, seat: Int): Boolean

    /** What [t] holds of it, in words: "their LP 1200". */
    fun read(t: FxTable, seat: Int): String

    /** The goal in words, [name] naming a passcode: "leave them with no monster". */
    fun words(name: (Int) -> String): String

    /** Their life points at most [atMost] (0: the duel won this turn). */
    @Serializable
    @SerialName("their-lp")
    data class TheirLp(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val atMost: Int = 0) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = t.state.seats[1 - seat].lp <= atMost
        override fun read(t: FxTable, seat: Int) = "their LP ${t.state.seats[1 - seat].lp.coerceAtLeast(0)}"
        override fun words(name: (Int) -> String) = if (atMost <= 0) "reduce them to 0 LP this turn" else "leave them at $atMost LP or less"
    }

    /** Your own life points at least [atLeast] at the turn's end. */
    @Serializable
    @SerialName("your-lp")
    data class YourLp(val atLeast: Int) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = t.state.seats[seat].lp >= atLeast
        override fun read(t: FxTable, seat: Int) = "your LP ${t.state.seats[seat].lp.coerceAtLeast(0)}"
        override fun words(name: (Int) -> String) = "keep at least $atLeast LP yourself"
    }

    /** They control no monster. */
    @Serializable
    @SerialName("their-field-clear")
    data object TheirFieldClear : BoardGoal {
        override fun met(t: FxTable, seat: Int) = BattlePlan.monsters(t.state, 1 - seat).isEmpty()
        override fun read(t: FxTable, seat: Int) = BattlePlan.monsters(t.state, 1 - seat).size.let { if (it == 0) "their field clear" else "they keep $it monster${if (it == 1) "" else "s"}" }
        override fun words(name: (Int) -> String) = "leave them with no monster"
    }

    /** They control at most [atMost] monsters. */
    @Serializable
    @SerialName("their-monsters")
    data class TheirMonsters(val atMost: Int) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = BattlePlan.monsters(t.state, 1 - seat).size <= atMost
        override fun read(t: FxTable, seat: Int) = BattlePlan.monsters(t.state, 1 - seat).size.let { "they keep $it monster${if (it == 1) "" else "s"}" }
        override fun words(name: (Int) -> String) = "leave them at most $atMost monster${if (atMost == 1) "" else "s"}"
    }

    /**
     * What they could still answer with — the interruptions their field holds and their hand traps, counted as the Mapper
     * counts a board's — at most [atMost] ("they have no negates left" is 0).
     */
    @Serializable
    @SerialName("their-answers")
    data class TheirAnswers(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val atMost: Int = 0) : BoardGoal {
        private fun count(t: FxTable, seat: Int) = BoardTraits.of(t, 1 - seat).let { it.interruptions + it.handInterruptions }
        override fun met(t: FxTable, seat: Int) = count(t, seat) <= atMost
        override fun read(t: FxTable, seat: Int) = count(t, seat).let { "they hold $it answer${if (it == 1) "" else "s"}" }
        override fun words(name: (Int) -> String) =
            if (atMost <= 0) "leave them no answer on their field or in their hand" else "leave them at most $atMost answer${if (atMost == 1) "" else "s"}"
    }

    /** Your board holds at least [atLeast] interruptions for their turn. */
    @Serializable
    @SerialName("interruptions")
    data class Interruptions(val atLeast: Int) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = BoardTraits.of(t, seat).interruptions >= atLeast
        override fun read(t: FxTable, seat: Int) = BoardTraits.of(t, seat).interruptions.let { "$it interruption${if (it == 1) "" else "s"}" }
        override fun words(name: (Int) -> String) = "end on at least $atLeast interruption${if (atLeast == 1) "" else "s"}"
    }

    /** Of your board's interruptions, at least [atLeast] negate. */
    @Serializable
    @SerialName("negates")
    data class Negates(val atLeast: Int) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = BoardTraits.of(t, seat).negates >= atLeast
        override fun read(t: FxTable, seat: Int) = BoardTraits.of(t, seat).negates.let { "$it negate${if (it == 1) "" else "s"}" }
        override fun words(name: (Int) -> String) = "end on at least $atLeast negate${if (atLeast == 1) "" else "s"}"
    }

    /** At least [atLeast] face-up monsters of yours. */
    @Serializable
    @SerialName("bodies")
    data class Bodies(val atLeast: Int) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = BoardTraits.of(t, seat).bodies >= atLeast
        override fun read(t: FxTable, seat: Int) = BoardTraits.of(t, seat).bodies.let { "$it face-up monster${if (it == 1) "" else "s"}" }
        override fun words(name: (Int) -> String) = "end with at least $atLeast face-up monster${if (atLeast == 1) "" else "s"}"
    }

    /** You control [code] face-up (any of its artworks: counted by card). */
    @Serializable
    @SerialName("control")
    data class Control(val code: Int) : BoardGoal {
        private fun has(t: FxTable, seat: Int): Boolean {
            val want = t.book.canonical(code)
            return BattlePlan.monsters(t.state, seat).any { u -> t.state.cards[u]?.faceUp == true && t.code(u) == want }
        }
        override fun met(t: FxTable, seat: Int) = has(t, seat)
        override fun read(t: FxTable, seat: Int) = if (has(t, seat)) "you control it" else "you do not control it"
        override fun words(name: (Int) -> String) = "control ${name(code)} face-up"
    }

    /** Every one of [all]. */
    @Serializable
    @SerialName("all")
    data class All(val all: List<BoardGoal>) : BoardGoal {
        override fun met(t: FxTable, seat: Int) = all.all { it.met(t, seat) }
        override fun read(t: FxTable, seat: Int) = all.joinToString("; ") { it.read(t, seat) }
        override fun words(name: (Int) -> String) = all.joinToString(", and ") { it.words(name) }
    }
}

/**
 * A turn's end valued by a [goal] (M4): met is above anything ([MET], above even a won game, since a puzzle asks for its goal
 * and nothing else), the better board breaking a tie between two ends that meet it — so the other seat's worst answer is the
 * one that leaves least, and the line kept shows it; else [base]'s value, so a search that cannot meet it still ends on the
 * best turn it found. Losing the duel never meets a goal.
 */
class GoalValue(val goal: BoardGoal, private val base: TurnValue = TurnValue.V0) : TurnValue(base.weights) {
    override fun of(t: FxTable, seat: Int, fodder: Set<Int>): Double = when {
        t.state.seats[seat].lp <= 0 -> TurnValue.LOSS
        goal.met(t, seat) -> MET + base.of(t, seat, fodder).coerceIn(-TIE, TIE)
        else -> base.of(t, seat, fodder)
    }

    companion object {
        /** A goal met: above [TurnValue.WIN]. */
        const val MET = 4_000_000.0

        /** How far the board moves a met goal's value: never below [MET] less this, never near an unmet one. */
        const val TIE = 100_000.0

        /** Whether a search's [value] met the goal. */
        fun met(value: Double): Boolean = value >= MET - TIE
    }
}

/** The goals a puzzle is saved with, by name: the save dialog's choices and Ai's `mapper_solve` words, one list. */
object BoardGoals {
    /** A choice: its [key], its [words] ("N" stands for [count]'s number), and whether it takes a count. */
    class Kind(val key: String, val words: String, val counted: Boolean = false)

    val kinds: List<Kind> = listOf(
        Kind("lethal", "Reduce them to 0 LP"),
        Kind("clear", "Leave them with no monster"),
        Kind("no-answers", "Leave them no answer on their field or in their hand"),
        Kind("interruptions", "End on at least N interruptions", counted = true),
        Kind("negates", "End on at least N negates", counted = true),
        Kind("bodies", "End with at least N face-up monsters", counted = true),
    )

    /** The goal [key] names, with [count] for a counted one and [code] for "control"; null for a name it does not know. */
    fun of(key: String, count: Int = 1, code: Int? = null): BoardGoal? {
        val n = count.coerceIn(1, 9)
        return when (key.trim().lowercase()) {
            "lethal" -> BoardGoal.TheirLp(0)
            "clear" -> BoardGoal.TheirFieldClear
            "no-answers" -> BoardGoal.TheirAnswers(0)
            "interruptions" -> BoardGoal.Interruptions(n)
            "negates" -> BoardGoal.Negates(n)
            "bodies" -> BoardGoal.Bodies(n)
            "control" -> code?.let { BoardGoal.Control(it) }
            else -> null
        }
    }
}
