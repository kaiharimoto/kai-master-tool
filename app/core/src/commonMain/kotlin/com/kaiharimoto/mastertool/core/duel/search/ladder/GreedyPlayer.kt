package com.kaiharimoto.mastertool.core.duel.search.ladder

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.match.CueKind
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.DoorPlayer
import com.kaiharimoto.mastertool.core.duel.match.SeatDoor
import com.kaiharimoto.mastertool.core.duel.search.Deciders
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.duel.search.Worlds
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation

/**
 * The ladder's anchor (Phase E, E.4; `docs/phases/E.md` §4.5): the obvious line and nothing else. Each turn it Normal
 * Summons its strongest monster, then each monster, strongest first, attacks the weakest face-up monster of theirs it
 * destroys without being destroyed — or directly when they control none — and it ends the turn. It activates no effect,
 * answers nothing and passes every window: the puzzles' battle-only greedy player, given a summon so a duel from an empty
 * table is a duel. Every rating on the ladder is read against it, at 1,000.
 */
class GreedyPlayer : DoorPlayer {
    override val engine: String = VERSION

    override suspend fun cue(door: SeatDoor): CueResult {
        when (door.kind) {
            CueKind.CHOOSE -> door.ops("go first")
            CueKind.ANSWER -> door.ops("accept")
            // Its own link the other passed on: the engine's pass resolves it.
            CueKind.RESOLVE -> door.engine(FxMove.Pass) { d, _ -> Deciders.first(d) }
            CueKind.PLAY -> play(door)
            else -> door.ops("pass")
        }
        return CueResult()
    }

    /** Its turn from where it stands; it returns whenever the other seat is asked, and the next cue picks up after it. */
    private suspend fun play(door: SeatDoor) {
        val seat = door.seat
        // An attack the other seat was asked about and passed on is fought first.
        door.settle()
        var s = state(door) ?: return
        if (s.active != seat) return
        if (s.phase == DuelPhase.DRAW || s.phase == DuelPhase.STANDBY) {
            if (door.ops("m1").second) return
            s = state(door) ?: return
        }
        if (s.phase == DuelPhase.MAIN1) {
            val t = table(door) ?: return
            // Its strongest monster, by printed ATK; the first in the hand among equals.
            val summon = FxEngine.moves(t, seat).filterIsInstance<FxMove.NormalSummon>().filter { !it.set }
                .maxWithOrNull(compareBy<FxMove.NormalSummon> { atk(t.state, it.uid, door) }.thenByDescending { it.uid })
            if (summon != null) {
                door.engine(summon) { d, _ -> Deciders.first(d) }
                if (asked(door)) return
                s = state(door) ?: return
            }
            // The first turn has no Battle Phase.
            if (s.turn > 1 && s.seats[1 - seat].lp > 0) {
                if (door.ops("bp").second) return end(door)
                if (asked(door)) return
            }
        }
        s = state(door) ?: return
        if (s.phase == DuelPhase.BATTLE) {
            while (true) {
                val now = state(door) ?: return
                val (a, target) = attack(now, seat, door) ?: break
                val from = DuelNotation.coordOf(now, a, seat) ?: break
                val to = target?.let { DuelNotation.coordOf(now, it, seat) ?: return end(door) } ?: "direct"
                if (door.ops("a $from $to").second) break
                if (asked(door)) return
                door.settle()
            }
        }
        end(door)
    }

    private suspend fun end(door: SeatDoor) {
        val s = state(door) ?: return
        if (s.active == door.seat && s.chain.isEmpty()) door.ops("end")
    }

    /** The other seat is asked, or a chain stands: nothing more until it is answered. */
    private fun asked(door: SeatDoor): Boolean = door.waitsOnOther || door.seen().view.chain.isNotEmpty()

    /** The next attack: its strongest monster that has not attacked, and the weakest it beats (null: directly). */
    private fun attack(s: DuelState, seat: Int, door: SeatDoor): Pair<Int, Int?>? {
        val catalog = door.kit.catalog
        fun atk(u: Int) = s.cards[u]?.let { DuelBattle.atk(it, catalog) } ?: 0
        fun def(u: Int) = s.cards[u]?.let { DuelBattle.def(it, catalog) } ?: 0
        val attacked = s.attacks.filter { it.seat == seat }.map { it.attacker }.toSet()
        val theirs = monsters(s, 1 - seat)
        val mine = monsters(s, seat).filter { u -> u !in attacked && s.cards[u]?.let { it.faceUp && !it.defense } == true }
            .sortedWith(compareByDescending<Int> { atk(it) }.thenBy { it })
        for (a in mine) {
            if (theirs.isEmpty()) return a to null
            val beaten = theirs.filter { u -> s.cards[u]?.let { c -> c.faceUp && (if (c.defense) atk(a) > def(u) else atk(a) > atk(u)) } == true }
            val pick = beaten.minWithOrNull(compareBy<Int> { u -> if (s.cards.getValue(u).defense) def(u) else atk(u) }.thenBy { it })
            if (pick != null) return a to pick
        }
        return null
    }

    private fun monsters(s: DuelState, seat: Int): List<Int> =
        s.seats[seat].monsters.filterNotNull() + s.emz.filterNotNull().filter { s.cards[it]?.controller == seat }

    private fun atk(s: DuelState, uid: Int, door: SeatDoor): Int = s.cards[uid]?.let { DuelBattle.atk(it, door.kit.catalog) } ?: 0

    /** The table as its seat sees it: one world dealt with nothing guessed, so its own cards are its own and theirs blanks. */
    private fun table(door: SeatDoor): FxTable? = runCatching {
        val seen = door.seen()
        val w = Worlds.deal(seen, Prior.Blank, 1, door.kit).first()
        FxTable(w.game.state, seen.fx, door.kit.book, door.kit.facts, seen.seed).current()
    }.getOrNull()

    private fun state(door: SeatDoor): DuelState? = table(door)?.state

    companion object {
        /** The greedy player's name on its moves and its games. */
        const val VERSION = "greedy"
    }
}
