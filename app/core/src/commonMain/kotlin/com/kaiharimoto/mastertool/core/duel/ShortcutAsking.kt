package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.text.AnswerChooser
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAnswers
import com.kaiharimoto.mastertool.core.duel.text.ShortcutAsk
import com.kaiharimoto.mastertool.core.duel.text.ShortcutLine

/** One answer the person gave the Shortcut window: indexes into the decision's own list, or a card named from the pool. */
sealed interface Given {
    /** Indexes into the decision's list ([Chooser.choose]'s answer). */
    data class Pick(val answer: List<Int>) : Given

    /** An open name declaration answered with any card of the pool ([Chooser.name]): its passcode. */
    data class Name(val code: Int) : Given
}

/** What a run of a Shortcut with the answers given so far came to (D.md §5¾.1). */
sealed interface ShortcutStep {
    /**
     * The first decision the answers do not reach: the window shows it. [asked] are the decisions answered on the way, in
     * step with the answers given ([ShortcutAsking.given]); [which] marks the card's own "which Shortcut" ([Decision.Option]
     * put by `Shortcuts.use` before the engine runs).
     */
    data class Asking(val decision: Decision, val asked: List<Decision>, val which: Boolean) : ShortcutStep

    /** Every choice made: the moves to commit as one group. */
    data class Done(val result: ShortcutResult) : ShortcutStep

    /** Not legal: the rule that forbids it, in words. */
    data class Refused(val why: String) : ShortcutStep

    /** Cancelled with nothing asked: nothing to commit. */
    data object Cancelled : ShortcutStep
}

/**
 * The table's [Chooser] for a Shortcut, **asking by replay** (D.md §5¾.1): [Chooser.choose] is synchronous, so the window
 * never blocks the engine. The use runs with the answers given so far ([given]); the first decision they do not reach stops
 * the run ([question]), and the window shows it. An answer is added and the use runs again from the start: the engine is
 * deterministic, so every run reaches the same next question. Nothing is committed until a run ends done.
 *
 * Decisions with one legal answer are never put (the engine's rule), so they never take an answer of the list.
 */
class ReplayChooser(given: List<Given>) : Chooser {
    private val queue = ArrayDeque(given)

    /** The decisions answered from the list, in order. */
    val asked = mutableListOf<Decision>()

    /** The first decision the list did not reach; null when every decision was answered. */
    var question: Decision? = null
        private set

    override fun choose(d: Decision): List<Int> {
        if (question != null) return Chooser.CANCEL
        val next = queue.removeFirstOrNull()
        if (next == null) {
            question = d
            return Chooser.CANCEL
        }
        asked += d
        return when (next) {
            is Given.Pick -> next.answer
            // A name given where an index was asked: the window's answers fell out of step; nothing is made.
            is Given.Name -> Chooser.CANCEL
        }
    }

    override fun name(d: Decision.Declare): Int? {
        if (!d.open || question != null) return null
        val next = queue.firstOrNull() as? Given.Name ?: return null
        queue.removeFirst()
        asked += d
        return next.code
    }
}

/**
 * A Shortcut being asked for at the table (D.md §5¾): what was asked ([ask]: a card's use, or the chain resolved as
 * written), by [seat], and the answers the person has given ([given]). Pure: the window's holder keeps one of these and
 * runs it on the table as it stands; Esc is [back], which lands on the decision before, and with nothing given cancels the
 * whole use with nothing committed.
 *
 * Answers the line gave ([ShortcutAsk.Use.answers]: `pick=`, `zone=`…, Ai's or typed) are used first; the window asks only
 * what they leave out.
 */
data class ShortcutAsking(val ask: ShortcutAsk, val seat: Int, val given: List<Given> = emptyList()) {

    /** The use run on [s] with the answers so far: the next question, the moves to commit, or why not. */
    fun run(shortcuts: Shortcuts, s: DuelState, catalog: DuelCatalog, secret: Long = 0L): ShortcutStep {
        val replay = ReplayChooser(given)
        val typed = when (ask) {
            is ShortcutAsk.Use -> ask.answers
            is ShortcutAsk.Resolve -> ask.answers
        }
        val chooser: Chooser = if (typed.isEmpty) replay else Both(AnswerChooser(typed, s, seat, catalog, secret, replay, shortcuts.names), replay)
        val r = ShortcutLine.run(ask, shortcuts, s, seat, catalog, chooser)
        val q = replay.question
        return when {
            q != null -> ShortcutStep.Asking(q, replay.asked.toList(), which = isWhich(q, replay.asked))
            r.problem != null -> ShortcutStep.Refused(r.problem)
            r.cancelled -> ShortcutStep.Cancelled
            r.ok -> ShortcutStep.Done(r)
            else -> ShortcutStep.Refused("Nothing to do")
        }
    }

    /** The person's answer to the question standing. */
    fun answer(a: List<Int>): ShortcutAsking = copy(given = given + Given.Pick(a))

    /** An open name declaration answered with a card of the pool. */
    fun named(code: Int): ShortcutAsking = copy(given = given + Given.Name(code))

    /** Esc: the last answer taken back — the decision before is asked again — or null, the whole use cancelled. */
    fun back(): ShortcutAsking? = if (given.isEmpty()) null else copy(given = given.dropLast(1))

    /** The card using the effect, when it is a card's use. */
    val uid: Int? get() = (ask as? ShortcutAsk.Use)?.uid

    private fun isWhich(q: Decision, asked: List<Decision>): Boolean =
        q is Decision.Option && q.effect == null && asked.isEmpty() && ask is ShortcutAsk.Use && ask.effect == null

    /**
     * The cards placed so far in this use, read off the answers given: each summoned card's zone, and its position when it
     * was asked or fixed — drawn on the table dashed until the last one is placed (D.md §5¾.5).
     */
    fun placed(asked: List<Decision>): List<Placement> {
        val out = mutableListOf<Placement>()
        asked.forEachIndexed { i, d ->
            val a = (given.getOrNull(i) as? Given.Pick)?.answer?.singleOrNull() ?: return@forEachIndexed
            when (d) {
                is Decision.Zone -> {
                    val z = d.among.getOrNull(a) ?: return@forEachIndexed
                    out += Placement(d.card, z, d.positions.singleOrNull())
                }
                is Decision.Position -> {
                    val p = d.among.getOrNull(a) ?: return@forEachIndexed
                    val k = out.indexOfLast { it.card == d.card }
                    if (k >= 0) out[k] = out[k].copy(position = p)
                }
                else -> {}
            }
        }
        return out
    }

    /** A card placed in this use, not yet committed: its [zone], and its [position] once known. */
    data class Placement(val card: Int?, val zone: Place.Zone, val position: CardPosition?)

    /** The typed answers first, then the window's: names go to whichever has one. */
    private class Both(private val first: AnswerChooser, private val then: ReplayChooser) : Chooser {
        override fun choose(d: Decision): List<Int> = first.choose(d)
        override fun name(d: Decision.Declare): Int? = first.name(d) ?: then.name(d)
        override fun told(d: Decision, answer: List<Int>) = first.told(d, answer)
    }

    companion object {
        /** A card's Shortcut asked for: [effect] by id or short name, or null to ask which. */
        fun use(uid: Int, seat: Int, effect: String? = null, answers: ShortcutAnswers = ShortcutAnswers()) =
            ShortcutAsking(ShortcutAsk.Use(uid, effect, answers), seat)

        /** The chain resolved as written: its newest link, or with [all] every link. */
        fun resolve(seat: Int, all: Boolean) = ShortcutAsking(ShortcutAsk.Resolve(all), seat)
    }
}
