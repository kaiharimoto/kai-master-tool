package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.match.CueKind
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.DoorPlayer
import com.kaiharimoto.mastertool.core.duel.match.SeatDoor
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * A seat the app's own search plays (Phase E, E.3; `docs/phases/E.md` §4.4): no connection and no words. Each cue it is handed
 * its [SeatDoor], deals worlds from what its seat has seen and its [prior], and makes the way on with the least expected
 * regret ([Regret]) — on its turn deciding again after each move until the other seat is asked, as a player would be. A
 * cue to choose goes first; an ask to move on is accepted.
 *
 * Its [strength] is effort counted in engine moves and worlds, never time: the same table gives the same move on any device.
 */
class SearchPlayer(
    private val prior: (Seen) -> Prior,
    val strength: Strength = Strength.STRONG,
    private val value: TurnValue = TurnValue.V0,
    private val cancelled: () -> Boolean = { false },
    /** Which search plays ([VERSIONS]): 0 the other seat answering knowing everything, 1 (E.5a) its answers as chance. */
    val version: Int = IN_USE,
) : DoorPlayer {
    /** The search's effort a decision (E.md §4.4): engine moves, shared across the worlds dealt. */
    enum class Strength(val moves: Int, val worlds: Int, val label: String) {
        FAST(5_000, 8, "Fast"),
        STRONG(25_000, 16, "Strong"),
        MAX(150_000, 32, "Max"),
        ;

        companion object {
            /** The table's strength setting (`DuelPrefs.aiStrength`) as the search's effort. */
            fun of(setting: String): Strength = when (setting) {
                DuelPrefs.FAST -> FAST
                DuelPrefs.MAX -> MAX
                else -> STRONG
            }
        }
    }

    override val engine: String = name(version)

    /** The last decision made, for the person watching and the tests. */
    var last: Regret.Decision? = null
        private set

    override suspend fun cue(door: SeatDoor): CueResult {
        when (door.kind) {
            CueKind.CHOOSE -> door.ops("go first")
            CueKind.ANSWER -> door.ops("accept")
            // Its turn played on within the cue, a decision a move, until the other seat is asked or the turn is over: the
            // referee's cues are a model's breaths, and a turn that stops five times is ended for it.
            CueKind.PLAY -> {
                // An attack the other seat was asked about and passed on is fought first.
                door.settle()
                var made = 0
                while (made < MOVES_A_CUE) {
                    if (!decide(door, first = made == 0)) break
                    made++
                    val v = door.seen().view
                    if (door.waitsOnOther || v.chain.isNotEmpty() || v.active != door.seat || v.phase == DuelPhase.END) break
                }
            }
            else -> decide(door, first = true)
        }
        return CueResult()
    }

    /** One decision made through [door]; false when nothing was made. Only the cue's [first] may fall back on ending the turn. */
    private suspend fun decide(door: SeatDoor, first: Boolean): Boolean {
        // Stop reaches a search thinking on another thread: the match's coroutine cancelled is the search cancelled.
        val ctx = currentCoroutineContext()
        val stop = { cancelled() || !ctx.isActive }
        val seen = door.seen()
        val v = seen.view
        // A turn's plays begin in Main Phase 1: its Draw and Standby Phases are walked through first.
        if (door.kind == CueKind.PLAY && v.active == seen.seat && v.chain.isEmpty() && (v.phase == DuelPhase.DRAW || v.phase == DuelPhase.STANDBY)) {
            return !door.ops("m1").second
        }
        val d = Regret.decide(seen, prior(seen), door.kit, strength.worlds, strength.moves, value, stop, chance = version >= 1)
        last = d
        val c = d.choice
        if (c == null) {
            if (first) fallback(door, seen)
            return false
        }
        // The world the choice is made in again: the heaviest that valued it, the first of those.
        val k = d.lines.keys.maxWithOrNull(compareBy<Int> { k -> d.worlds[k].weight }.thenByDescending { it })
        val world = k?.let { d.worlds[it] }
        val table = world?.let { FxTable(it.game.state, seen.fx, door.kit.book, door.kit.facts, seen.seed).current() }
        val made = make(door, c.root, table, k?.let { d.lines[it] }, seen.seat)
        if (!made && first) fallback(door, seen)
        return made
    }

    /** [root] made on the table through the door; false when the table refused it. */
    private suspend fun make(door: SeatDoor, root: Root, world: FxTable?, line: TurnOption?, seat: Int): Boolean = when (root) {
        is Root.Move -> when (val m = root.move) {
            is FxMove.Phase -> !door.ops(phaseOp(m.to)).second
            // On its own link, passed on by the other seat: the engine's pass resolves it, its effect's moves and all. On theirs,
            // the table's pass: their link is theirs to resolve.
            FxMove.Pass -> if (door.kind == CueKind.RESOLVE) door.engine(FxMove.Pass) { d, _ -> Deciders.first(d) } == null else !door.ops("pass").second
            else -> {
                val step = line?.steps?.firstOrNull() as? TurnStep.Move
                val carry = if (world != null && step != null && step.move == m) Carry.of(world, seat, step) else world?.let { Carry.none(it) }
                door.engine(m, object : SeatDoor.Choose {
                    override fun choose(d: Decision, cards: List<Int?>): List<Int> = carry?.choose(d, cards) ?: Deciders.first(d)
                    override fun rewind() { carry?.rewind() }
                }) == null
            }
        }
        is Root.End -> {
            var ok = true
            for (u in root.sets) {
                val at = world?.state?.let { coord(it, u, seat) } ?: continue
                if (door.ops("e $at").second) ok = false
            }
            !door.ops("end").second && ok
        }
        Root.Battle -> !door.ops("bp").second
        is Root.Attack -> {
            val s = world?.state
            val a = s?.let { coord(it, root.attacker, seat) }
            val t = root.target?.let { u -> s?.let { coord(it, u, seat) } } ?: "direct"
            // Declared, then fought: the battle's damage and destruction are the attacker's own move, made once no
            // window waits on the other seat (else at its next cue).
            (a != null && !door.ops("a $a $t").second).also { ok -> if (ok && !door.waitsOnOther) door.settle() }
        }
        Root.LetGo -> !door.ops("pass").second
    }

    /** Nothing could be valued, or the table refused the choice: pass where a pass is asked, else end the turn. */
    private suspend fun fallback(door: SeatDoor, seen: Seen) {
        val turn = seen.view.active == seen.seat
        if (door.kind == CueKind.PLAY && turn) door.ops("end") else door.ops("pass")
    }

    /** [uid]'s coordinate as [seat] says it on a world's table: its own places are the table's, theirs by zone. */
    private fun coord(s: DuelState, uid: Int, seat: Int): String? = DuelNotation.coordOf(s, uid, seat)

    private fun phaseOp(to: DuelPhase): String = when (to) {
        DuelPhase.BATTLE -> "bp"
        DuelPhase.MAIN2 -> "m2"
        DuelPhase.END -> "end"
        else -> "next"
    }

    companion object {
        /**
         * The search in use, on its moves and its records: v0's value and worlds. A version replaces it only through the
         * ladder's gate (E.4): the one in use is [IN_USE], and [VERSION] is its name.
         */
        const val VERSION = "search-v0"
        const val IN_USE = 0

        /** Every version this build plays: v0 (E.3), v1 (E.5a, the other seat's answers as chance). */
        val VERSIONS = listOf(0, 1)

        /** A version's name: "search-v1". */
        fun name(version: Int): String = "search-v$version"

        /** The most moves one cue plays on: past it, the referee cues again. */
        const val MOVES_A_CUE = 40

        /** The cards a Main Deck is taken to hold when a card's copies are read as a chance per card in hand. */
        const val DECK = 40.0
    }
}
