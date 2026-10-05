package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Place

/**
 * What a card choice is for ([Decision.Cards.purpose]): what a window says and how it draws the picked cards' way —
 * worked out by the engine from the step, never written by a script.
 */
enum class Purpose { TARGET, COST, SUMMON, ADD, SEND, BANISH, DESTROY, RETURN, ATTACH, MATERIAL, TRIBUTE, DISCARD, REVEAL, OTHER }

/** The effect a decision is for: its card, the effect's id, and its short name ("Revive"), or "" for a rule's own choice. */
data class FxSource(val uid: Int, val effect: String, val label: String = "")

/**
 * Where a step takes the cards it picks ([Decision.Cards.to]): [dest] on [seat]'s side, and the positions the step allows
 * when it puts them on the field (empty elsewhere; one position when the step fixes it).
 */
data class Landing(val dest: Dest, val seat: Int, val positions: List<CardPosition> = emptyList())

/**
 * A choice the engine puts to a [Chooser] (D.md §2.5, §5½): **the engine never guesses.** Every answer is a list of indexes
 * into the decision's own list — [Cards.among], [Zone.among], [Position.among], [Order.triggers], [Option.among],
 * [Declare.among]; [YesNo] is `[1]` for yes and `[0]` for no. An answer outside the decision's bounds, or [Chooser.CANCEL],
 * cancels the whole use: nothing is committed ([FxPlay.Cancelled]). A decision with one legal answer is never put.
 *
 * Each carries what a generic window needs to draw it — what it is for, where its cards are and go, which effect asks,
 * which step of the Shortcut it is — so every picker is derived from the engine alone, and a script never says how it is
 * shown (kai: "so the Ai doesn't need to worry about that"). Every such field has a default, so a decision built by hand
 * (a test, the table's "which Shortcut") reads as it did.
 */
sealed interface Decision {
    /**
     * [min]–[max] of [among] (uids), for [why]: "Tribute", "Materials for X", "Target", "Add to your hand".
     *
     * [purpose]: what the cards are for. [to]: where the step takes them, and in which positions, when it moves them.
     * [from]: each candidate's place, in step with [among] (a seat and a zone, a pile, or beneath a card), so a window
     * groups them by place. [effect]: the effect that asks. [step]: "2 of 3" within the effect's steps (its costs, targets
     * and what it does), when known. [hidden]: some candidates lie in a pile the chooser may look through but the other
     * player does not see — its own Deck or Extra Deck, as a search shows them — as against public places.
     */
    data class Cards(
        val why: String,
        val among: List<Int>,
        val min: Int,
        val max: Int,
        val purpose: Purpose = Purpose.OTHER,
        val to: Landing? = null,
        val from: List<Place?> = emptyList(),
        val effect: FxSource? = null,
        val step: String? = null,
        val hidden: Boolean = false,
        /** The places the pick looked in ([Pick.from]: a seat and an area), so a window can show a place with nothing legal. */
        val looked: List<Spot> = emptyList(),
        /** The seat that chooses, when it is not the one using the effect ("your opponent chooses"); null: the user. */
        val by: Int? = null,
    ) : Decision

    /** Which of [among] (only the legal, free zones) [card] goes to, and the [positions] it may take there. */
    data class Zone(
        val among: List<Place.Zone>,
        val card: Int? = null,
        val positions: List<CardPosition> = emptyList(),
        val effect: FxSource? = null,
    ) : Decision

    /** Which of [among] [card] is summoned in: face-up Attack or Defense, or face-down Defense where it is Set. */
    data class Position(val card: Int, val among: List<CardPosition>, val effect: FxSource? = null) : Decision

    /**
     * The order a player puts their own simultaneous triggers on the chain: the answer is a permutation. [labels]: each
     * one's card and effect, never naming a card hidden from the seat that moves. [by]: the seat whose triggers they are.
     */
    data class Order(val triggers: List<Pending>, val labels: List<String> = emptyList(), val by: Int? = null) : Decision

    /** An optional trigger, a "you can …", chaining more. [by]: the seat whose choice it is (a trigger's controller). */
    data class YesNo(val why: String, val effect: FxSource? = null, val by: Int? = null) : Decision

    /** One of [among]: a [Op.Choose]'s options, and which of a card's Shortcuts to run. */
    data class Option(val among: List<String>, val effect: FxSource? = null) : Decision

    /**
     * A declaration ([Op.Declare]): one of [among] — card names, Types, Attributes or Levels. [open]: a card name, where
     * **any card that exists may be declared** (Yugipedia, "Declare", citing the OCG Perfect Rulebook): [among] is then
     * only the names on the table the seat can see, a start for the search, and the chooser may answer with any card of
     * the pool through [Chooser.name].
     */
    data class Declare(val kind: DeclareKind, val among: List<String>, val effect: FxSource? = null, val open: Boolean = false) : Decision
}

/**
 * Answers a [Decision]. The test's `RecordMatcher` (answers from the record), the goldfish's search (tries each answer),
 * a combo's `PlanChooser` (by the names its steps give) and the table's chooser for **Shortcut** (`DuelVerb.SHORTCUT`) (asks the person,
 * or reads Ai's choices from its op).
 */
fun interface Chooser {
    fun choose(d: Decision): List<Int>

    /**
     * An open name declaration ([Decision.Declare.open]) answered by any card of the pool: its passcode, or null to answer
     * from [Decision.Declare.among] by [choose] instead. The engine accepts a passcode the pool knows that matches what the
     * effect lets be declared, and cancels on any other.
     */
    fun name(d: Decision.Declare): Int? = null

    /**
     * [d] was settled without asking — it had one legal answer, [answer] (D.md §2.5) — so a chooser reading answers from a
     * line (`zone=`, `pos=`, `pick=`) can keep its answers in step with the decisions.
     */
    fun told(d: Decision, answer: List<Int>) {}

    companion object {
        /** The answer that cancels the whole use. */
        val CANCEL: List<Int> = listOf(-1)

        /** Answers every decision with its first legal answer: the first [Decision.Cards.min] cards, yes, the first zone, position or option. */
        val FIRST = Chooser { d ->
            when (d) {
                is Decision.Cards -> (0 until d.min.coerceAtMost(d.among.size)).toList()
                is Decision.Zone -> if (d.among.isEmpty()) CANCEL else listOf(0)
                is Decision.Position -> if (d.among.isEmpty()) CANCEL else listOf(0)
                is Decision.Order -> d.triggers.indices.toList()
                is Decision.YesNo -> listOf(1)
                is Decision.Option -> if (d.among.isEmpty()) CANCEL else listOf(0)
                is Decision.Declare -> if (d.among.isEmpty()) CANCEL else listOf(0)
            }
        }

        /** Whether [answer] is a legal one for [d]. */
        fun legal(d: Decision, answer: List<Int>): Boolean {
            if (answer.any { it < 0 } || answer.toSet().size != answer.size) return false
            return when (d) {
                is Decision.Cards -> answer.size in d.min..d.max && answer.all { it < d.among.size }
                is Decision.Zone -> answer.size == 1 && answer[0] < d.among.size
                is Decision.Position -> answer.size == 1 && answer[0] < d.among.size
                is Decision.Order -> answer.sorted() == d.triggers.indices.toList()
                is Decision.YesNo -> answer.size == 1 && answer[0] in 0..1
                is Decision.Option -> answer.size == 1 && answer[0] < d.among.size
                is Decision.Declare -> answer.size == 1 && answer[0] < d.among.size
            }
        }
    }
}

/** Something a seat may start now (D.md §2.5): listed by [FxEngine.moves], made by [FxEngine.play]. */
sealed interface FxMove {
    /** [uid]'s effect [effect] activated (or a [Kind.TRIGGER] put on the chain). Agent (b). */
    data class Activate(val uid: Int, val effect: String) : FxMove

    /** A Normal Summon, or a Set when [set], from the hand. Agent (a), `FxSummons`. */
    data class NormalSummon(val uid: Int, val set: Boolean = false) : FxMove

    /** [uid]'s summoning procedure [proc] (an index into its `SummonRule.procs`): Link, Synchro, Xyz, Inherent. Agent (a). */
    data class Procedure(val uid: Int, val proc: Int) : FxMove

    /** On to [to], forward only. Agent (a). */
    data class Phase(val to: DuelPhase) : FxMove

    /** The seat with priority passes: the other may respond, or, both having passed, the newest link resolves. Agent (b). */
    data object Pass : FxMove

    /** The newest link resolves. Agent (b). */
    data object Resolve : FxMove
}

/** What making an [FxMove] came to. */
sealed interface FxPlay {
    /**
     * The tagged [actions] to commit (one [tags] entry each, in step) and the table and engine state after them — the
     * actions already applied by `DuelRules`, so committing them through `DuelGame.act` cannot be refused. [events] are
     * what happened, for the triggers.
     */
    data class Done(
        val actions: List<DuelAction>,
        val tags: List<FxTag>,
        val state: DuelState,
        val fx: FxState,
        val events: List<FxEvent> = emptyList(),
    ) : FxPlay

    /** Not legal now: [why] names the rule that forbids it, in words. */
    data class Refused(val why: String) : FxPlay

    /** The chooser cancelled: nothing is committed. */
    data object Cancelled : FxPlay
}

/**
 * The engine's two doors (D.md §2.5): what a seat may start now, and a move made. It is a referee above the table — every
 * thing it does is ordinary `DuelAction`s, checked by `DuelRules` as physics — and deterministic.
 *
 * Its parts, each its own file so agents build in parallel:
 * - summons, procedures and phases: `FxSummons` (agent (a));
 * - activations, the chain, priority, resolution and triggers: `FxChain` (agent (b)), which runs each effect's costs,
 *   targets and steps through `FxSteps` (agent (c));
 * - the fold of a log: `FxFold` (agent (c)).
 */
object FxEngine {
    /** Everything [seat] may start now: activations (card, effect), Normal Summon or Set, procedures, phases, pass, resolve. */
    fun moves(t: FxTable, seat: Int): List<FxMove> {
        val now = t.current()
        return FxSummons.moves(now, seat) + FxChain.moves(now, seat)
    }

    /**
     * [move] made: the tagged actions to commit and the table after, or refused with the rule that forbids it.
     *
     * Summons, procedures and phases are `FxSummons`'; they start no chain, so the triggers they set off gather afterwards
     * and form a chain in the same move (`FxChain.after`, SEGOC). Activations, passes and resolutions are `FxChain`'s.
     * Either way the state returned is the fold of the tagged actions ([FxFold]), so committing them and folding the log
     * gives it back. A shuffle is stamped with the duel's dice ([FxTable.seed], [FxState.rolls]) as `DuelGame.act` will
     * stamp it, so the table returned is the one the commit makes.
     */
    fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay {
        val now = t.current()
        return when (move) {
            is FxMove.NormalSummon, is FxMove.Procedure, is FxMove.Phase -> when (val p = FxSummons.play(now, seat, move, chooser)) {
                is FxPlay.Done -> FxChain.after(now, seat, p, chooser)
                else -> p
            }
            is FxMove.Activate, FxMove.Pass, FxMove.Resolve -> FxChain.play(now, seat, move, chooser)
        }
    }

    /**
     * Why [seat] may not activate [uid]'s [effect] now, in words, or null when it may: what a Shortcut shows greyed with its
     * rule ("Once per turn: used.").
     */
    fun refusal(t: FxTable, seat: Int, uid: Int, effect: String): String? = FxChain.refusal(t, seat, uid, effect)

    /** Who moves next: the seat with priority while a chain stands, the turn player otherwise. */
    fun next(t: FxTable): Int = FxChain.next(t.current())
}
