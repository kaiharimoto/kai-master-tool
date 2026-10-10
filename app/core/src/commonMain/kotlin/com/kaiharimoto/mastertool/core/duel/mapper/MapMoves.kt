package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey

/**
 * What the mapper's searches try from a table, shared by the map ([MapSearch]) and the stress tests (`StressSearch`), so the
 * two never disagree on which moves are one move or how a turn is ended.
 */
internal object MapMoves {
    /** The ways to Set before the End Phase ([setChoices]): each choice's Set cards and the table they make; [cut]: past [MapSearch.SET_CHOICES]. */
    class SetChoices(val ways: List<Pair<List<Int>, FxTable>>, val cut: Boolean)

    /**
     * [seat]'s moves on [t], one a card and kind (copies are one), in a fixed order: resolve, pass, procedures, Normal Summons,
     * activations, Sets, and the End Phase — offered from every open table ([open]) and from no other.
     */
    fun distinct(t: FxTable, seat: Int, open: Boolean, ordered: Boolean = false): List<FxMove> {
        val key = TableKey(t, ordered)
        val out = LinkedHashMap<String, FxMove>()
        FxEngine.moves(t, seat).forEach { m ->
            val k = when (m) {
                is FxMove.Activate -> "4a" + key.token(m.uid) + ":" + m.effect
                is FxMove.NormalSummon -> (if (m.set) "5n" else "3n") + key.token(m.uid)
                is FxMove.Procedure -> "2p" + key.token(m.uid) + ":" + m.proc
                is FxMove.Phase -> if (m.to == DuelPhase.END) "6f" else return@forEach
                FxMove.Pass -> "1x"
                FxMove.Resolve -> "0r"
            }
            if (m is FxMove.Phase && !open) return@forEach
            out.getOrPut(k) { m }
        }
        return out.entries.sortedBy { it.key }.map { it.value }
    }

    /**
     * The ways to Set before the End Phase from [t]: every Trap and Quick-Play Spell the engine knows in [seat]'s hand, Set into
     * the free Spell & Trap Zones (by card, then uid). With more of them than zones, one way for each distinct choice of cards,
     * at most [MapSearch.SET_CHOICES]. With none, [t] as it is.
     */
    fun setChoices(t: FxTable, kit: GoldfishKit, seat: Int = 0): SetChoices {
        val free = t.state.freeZones(seat, ZoneKind.SPELL)
        val settable = t.state.seats[seat].hand.filter { u ->
            val c = t.card(u) ?: return@filter false
            !kit.inertUid(u, t) && (c.type == CardType.TRAP || (c.type == CardType.SPELL && c.isSpellSub("Quick-Play")))
        }.sortedWith(compareBy({ kit.canonical(t.code(it) ?: 0) }, { it }))
        if (settable.isEmpty() || free.isEmpty()) return SetChoices(listOf(emptyList<Int>() to t), false)
        var cut = false
        val picks = if (settable.size <= free.size) listOf(settable) else {
            val byCard = settable.groupBy { kit.canonical(t.code(it) ?: 0) }.values.toList()
            val out = ArrayList<List<Int>>()
            fun walk(i: Int, left: Int, acc: List<Int>) {
                if (out.size > MapSearch.SET_CHOICES) return
                if (left == 0) { out += acc; return }
                if (i == byCard.size) return
                for (k in minOf(left, byCard[i].size) downTo 0) walk(i + 1, left - k, acc + byCard[i].take(k))
            }
            walk(0, free.size, emptyList())
            if (out.size > MapSearch.SET_CHOICES) cut = true
            out.take(MapSearch.SET_CHOICES)
        }
        val ways = picks.map { pick ->
            val moves = pick.mapIndexed { i, u -> DuelAction.Move(u, free[i], CardPosition.FACE_DOWN_DEF, "set") }
            val (state, _) = DuelRules.applyAll(t.state, moves, seat)
            if (state == null) emptyList<Int>() to t
            else pick to FxTable(state, t.fx.copy(setCards = t.fx.setCards + pick), t.book, t.facts, t.seed)
        }.distinctBy { it.first }
        return SetChoices(ways, cut)
    }
}
