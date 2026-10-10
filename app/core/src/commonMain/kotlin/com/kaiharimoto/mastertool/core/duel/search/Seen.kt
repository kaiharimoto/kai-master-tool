package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelView
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxState
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.sync.Sha256

/**
 * Everything a search for a seat is handed (Phase E, E.3; `docs/phases/E.md` §3 rule 2, "hidden is hidden"): the seat's
 * view now and its own decklist ([source], [DuelFork.Source]), the engine's state cut to the cards it can see ([fx]), and the
 * table as it saw it at each of the other seat's passes this turn ([passes]) — never the duel itself. A world is dealt from
 * this and nothing else, so two duels that differ only in what the seat cannot see are searched the same.
 *
 * [seed] is the view's own fingerprint, never the duel's seed (the duel's seed is its secret: it keys the veils and the
 * shuffles), so the same table deals the same worlds on any device and tells the search nothing it could not see.
 */
class Seen(val seat: Int, val source: DuelFork.Source, val fx: FxState, val passes: List<Moment>, val seed: Long) {
    /** The table as the seat saw it at one of the other seat's passes: its view and the engine's state, cut the same way. */
    class Moment(val view: DuelView, val fx: FxState)

    val view: DuelView get() = source.view

    companion object {
        /** What [seat] has seen of [game], read with [knows] (the seat's own eyes by default). */
        fun of(game: DuelGame, seat: Int, kit: GoldfishKit, knows: String = DuelBrief.SELF): Seen {
            val src = DuelFork.source(game, seat, knows)
            // The duel's seed is its secret: the fork's header keeps none of it.
            val source = src.copy(header = src.header.copy(seed = 0L, start = null))
            val viewer = DuelBrief.viewer(knows, seat)
            val fx = cut(FxFold.fold(game.header, game.played, kit.book, kit.facts, game.state), game.state, viewer)
            val them = 1 - seat
            val played = game.played
            val start = played.indexOfLast { it.action == DuelAction.EndTurn } + 1
            val passes = (start until played.size).mapNotNull { i ->
                val a = played[i].action as? DuelAction.Answer ?: return@mapNotNull null
                if (a.seat != them || a.respond) return@mapNotNull null
                val before = game.stateAt(i)
                Moment(DuelView.of(before, viewer, game.header.seed), cut(FxFold.fold(game.header, played.subList(0, i), kit.book, kit.facts, before), before, viewer))
            }
            return Seen(seat, source, fx, passes, fingerprint(source.view))
        }

        /**
         * [fx] with every card the [viewer] cannot see on [s] left out: the engine's state speaks of cards by uid, and a hidden
         * card's uid is the table's, never the view's — what was summoned, sent, Set or used, its instances, Levels and negations,
         * and the triggers waiting, kept only for cards the seat can see.
         */
        fun cut(fx: FxState, s: DuelState, viewer: Int?): FxState {
            if (viewer == null) return fx
            fun sees(u: Int) = s.cards[u] == null || DuelSight.sees(s, u, viewer)
            return fx.copy(
                uses = fx.uses.filter { sees(it.uid) },
                summoned = fx.summoned.filterKeys(::sees),
                sent = fx.sent.filterTo(HashSet(), ::sees),
                setCards = fx.setCards.filterTo(HashSet(), ::sees),
                deeds = fx.deeds.filter { sees(it.uid) },
                pending = fx.pending.filter { sees(it.uid) },
                levels = fx.levels.filter { sees(it.uid) },
                negated = fx.negated.filter { sees(it.uid) },
                proper = fx.proper.filterTo(HashSet(), ::sees),
                lives = fx.lives.filterKeys(::sees),
                links = fx.links.map { l -> l.copy(bound = l.bound.mapValues { (_, us) -> us.filter(::sees) }) },
            )
        }

        /** A view's fingerprint as a seed: the same table, the same worlds. */
        fun fingerprint(view: DuelView): Long {
            val hex = Sha256.hex(DuelCodec.json.encodeToString(DuelView.serializer(), view)).take(16)
            return hex.toULong(16).toLong()
        }
    }
}
