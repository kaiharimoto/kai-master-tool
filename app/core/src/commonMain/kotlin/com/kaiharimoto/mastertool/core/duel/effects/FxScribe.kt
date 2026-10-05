package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelRandom
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.Outcome

/**
 * Why a use stopped part-way: [why] a rule refused it (in words), or, null, the chooser cancelled. Nothing it made is
 * committed either way.
 */
internal class FxStop(val why: String?) : RuntimeException(why ?: "cancelled") {
    companion object {
        fun cancel() = FxStop(null)
        fun refuse(why: String) = FxStop(why)
    }
}

/**
 * The engine at work on one move (agents (b) and (c) meet here): the table as it goes, the tagged actions made so far and
 * the events they were. Every action is applied by `DuelRules` as it is made, and its `FxState` is the fold of its tagged
 * entry ([FxFold.read]) — so what the engine knows is exactly what its log says. Chance is stamped as the log will stamp
 * it: a shuffle by the duel's dice for its roll, a token's uid and a lock's id as `DuelIds` would give them.
 */
internal class FxScribe(start: FxTable, private val chooser: Chooser, val by: Int?) {
    var t: FxTable = start
        private set
    val actions = ArrayList<DuelAction>()
    val tags = ArrayList<FxTag>()
    val events = ArrayList<FxEvent>()

    /** Makes [action], tagged [tag]: refused (with the table's words) when the table cannot hold it. */
    fun emit(action: DuelAction, tag: FxTag) {
        if (actions.size >= MOST_ACTIONS) throw FxStop.refuse("The engine stops at $MOST_ACTIONS actions in one move.")
        val a = stamp(action)
        val before = t.state
        val o = DuelRules.apply(before, a, by)
        if (o !is Outcome.Ok) throw FxStop.refuse("The table refused it: ${(o as Outcome.Refused).reason}")
        val r = FxFold.read(t.fx, before, DuelEntry(0, 0L, by, 0, a, fx = tag), o.state, t.book, t.facts)
        t = t.copy(state = o.state, fx = r.fx)
        actions += a
        tags += tag
        events += r.events
    }

    /** What [a] leaves to chance, or to a number, written in as the log will write it. */
    private fun stamp(a: DuelAction): DuelAction = when (a) {
        is DuelAction.Shuffle -> DuelRandom.stamp(a, DuelRandom.forRoll(t.seed, t.fx.rolls))
        is DuelAction.Token -> if (a.uid == null) a.copy(uid = t.state.nextUid) else a
        is DuelAction.Lock -> if (a.id == null) a.copy(id = maxOf(t.state.lastLock, t.state.locks.maxOfOrNull { it.id } ?: 0) + 1) else a
        else -> a
    }

    /**
     * [d] answered: without asking when it has one legal answer (D.md §2.5), else by the chooser; an answer out of
     * bounds cancels the whole use.
     */
    fun ask(d: Decision): List<Int> {
        single(d)?.let {
            chooser.told(d, it)
            return it
        }
        val answer = chooser.choose(d)
        if (!Chooser.legal(d, answer)) throw FxStop.cancel()
        return answer
    }

    /**
     * An open name declaration ([Decision.Declare.open]): any card of the pool by [Chooser.name] — accepted when [fits]
     * says it may be declared — or one of [Decision.Declare.among] by [ask]. Null: answered from the list, at [ask]'s index.
     */
    fun named(d: Decision.Declare, fits: (Int) -> Boolean): Int? {
        val code = chooser.name(d) ?: return null
        if (!fits(code)) throw FxStop.cancel()
        return code
    }

    /** The one legal answer to [d], or null when there is a choice to make. */
    private fun single(d: Decision): List<Int>? = when (d) {
        is Decision.Declare -> if (!d.open && d.among.size == 1) listOf(0) else null
        is Decision.Cards -> when {
            d.max <= 0 -> emptyList()
            d.min == d.max && d.among.size == d.min -> d.among.indices.toList()
            else -> null
        }
        is Decision.Zone -> if (d.among.size == 1) listOf(0) else null
        is Decision.Position -> if (d.among.size == 1) listOf(0) else null
        is Decision.Order -> if (d.triggers.size <= 1) d.triggers.indices.toList() else null
        is Decision.YesNo -> null
        is Decision.Option -> if (d.among.size == 1) listOf(0) else null
    }

    fun done(prefix: List<FxEvent> = emptyList()): FxPlay.Done = FxPlay.Done(actions.toList(), tags.toList(), t.state, t.fx, prefix + events)

    companion object {
        /** The most actions one move makes (D.md §7: a thousand a turn anywhere). */
        const val MOST_ACTIONS = 1000

        /** Runs [body] on a scribe over [t]: what it made, refused in words, or cancelled. */
        fun play(t: FxTable, chooser: Chooser, by: Int?, body: (FxScribe) -> Unit): FxPlay {
            val sc = FxScribe(t, chooser, by)
            return try {
                body(sc)
                sc.done()
            } catch (s: FxStop) {
                if (s.why == null) FxPlay.Cancelled else FxPlay.Refused(s.why)
            }
        }
    }
}

/**
 * The duel's catalog as the engine's facts tell it: what `DuelVerbs.resolve` and `DuelVerbs.negate` read (a card's
 * kind, its Spell or Trap kind), so the chain's own Spells and Traps go to the GY by the one list.
 */
fun FxFacts.catalog(): DuelCatalog = DuelCatalog { code ->
    get(code)?.let { c ->
        val kind = when {
            c.type == CardType.SPELL && c.isSpellSub("Field") -> CardKind.FIELD_SPELL
            c.type == CardType.SPELL -> CardKind.SPELL
            c.type == CardType.TRAP -> CardKind.TRAP
            CardFrame.TOKEN in c.frames -> CardKind.TOKEN
            c.extraDeck -> CardKind.EXTRA_MONSTER
            else -> CardKind.MONSTER
        }
        DuelCardInfo(c.name, kind, pendulum = c.pendulum, link = c.link != null, sub = c.sub, atk = c.atk, def = c.def)
    }
}
