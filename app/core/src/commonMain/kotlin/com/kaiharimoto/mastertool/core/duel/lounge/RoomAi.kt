package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.TurnStart
import com.kaiharimoto.mastertool.core.duel.match.CueKind
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelWords

/**
 * Ai at a Lounge room's table (`docs/LOUNGE.md`, L5): one seat Ai's against a person, or both seats Ai's for the room to
 * watch. Like the Ai vs Ai referee ([com.kaiharimoto.mastertool.core.duel.match.MatchReferee]) it says whose move it is and
 * what the table does by itself — but only ever for an Ai seat: a person's move is theirs to make, at their pace, and the
 * table waits. Passes on a chain are read off the log, so a person's "No response" counts as an Ai's does.
 */
object RoomAiTurn {
    sealed interface Next {
        /** A person's move, or nothing to do: the table waits. */
        data object Wait : Next

        /** The duel has ended on the table. */
        data class Over(val winner: Int?, val how: String) : Next

        /** The table moves for Ai's [seat] by itself: its dice, its turn's draw, a turn ended for it. */
        data class Table(val seat: Int, val actions: List<DuelAction>, val note: String? = null) : Next

        /** Ai's [seat] is cued for [kind]. */
        data class Cue(val seat: Int, val kind: CueKind) : Next
    }

    fun next(g: DuelGame, ai: Set<Int>, memo: RoomAiMemo, rules: MatchRules): Next {
        val s = g.state
        DuelResults.ending(s)?.let { (winner, how) -> return Next.Over(winner, how) }
        if (ai.isEmpty()) return Next.Wait
        s.opening?.takeIf { !it.decided }?.let { o ->
            val winner = o.winner
            if (winner != null) return if (winner in ai) Next.Cue(winner, CueKind.CHOOSE) else Next.Wait
            // Ai throws as soon as it may; a person throws their own.
            val seat = ai.sorted().firstOrNull { o.waitsOn(it) } ?: return Next.Wait
            return Next.Table(seat, listOf(DuelAction.OpeningRoll(seat)))
        }
        s.window?.let { return if (it.responder in ai) Next.Cue(it.responder, CueKind.RESPOND) else Next.Wait }
        s.proposal?.let { return if (s.active in ai) Next.Cue(s.active, CueKind.ANSWER) else Next.Wait }
        s.chain.lastOrNull()?.let { top ->
            val other = 1 - top.seat
            return if (passedOnTop(g)) {
                if (top.seat in ai) Next.Cue(top.seat, CueKind.RESOLVE) else Next.Wait
            } else {
                if (other in ai) Next.Cue(other, CueKind.CHAIN) else Next.Wait
            }
        }
        if (s.active !in ai) return Next.Wait
        // No Ai turn lasts for ever: past its cues the table ends it, at a moment nothing waits on anyone.
        if (memo.turn == s.turn && memo.turnCues >= rules.turnCues && s.phase != DuelPhase.DRAW) {
            return Next.Table(s.active, listOf(DuelAction.EndTurn), "${DuelWords.seatName(s, s.active)} had ${memo.turnCues} cues this turn: the table ends it.")
        }
        TurnStart.next(g)?.let { return Next.Table(s.active, listOf(it)) }
        if (s.phase == DuelPhase.DRAW && s.turn > 1 && !TurnStart.drewThisTurn(g) && s.seats[s.active].deck.isEmpty()) {
            return Next.Table(s.active, listOf(DuelAction.Concede(s.active)), "${DuelWords.seatName(s, s.active)} cannot draw for its turn: it loses the duel (decked out).")
        }
        return Next.Cue(s.active, CueKind.PLAY)
    }

    /**
     * Whether the newest chain link has been passed on by the player who did not make it — their "No response" logged
     * after it was added — or the chain is already resolving (a link resolved since), when the rest resolves newest first.
     */
    fun passedOnTop(g: DuelGame): Boolean {
        val top = g.state.chain.lastOrNull() ?: return false
        for (k in g.cursor - 1 downTo g.floor) {
            when (val a = g.entries[k].action) {
                is DuelAction.Answer -> if (a.seat == 1 - top.seat && !a.respond) return true
                is DuelAction.ChainAdd -> return false
                DuelAction.ChainResolve -> return true
                else -> Unit
            }
        }
        return false
    }

    /** What a cue came to: the table moves Ai made in it, whether it failed (an error, a timeout), and its tokens. */
    data class Cued(val seat: Int, val kind: CueKind, val moves: Int, val failed: String? = null, val tokens: Long = 0)

    /** The memo after a cue, and what the table does for Ai's seat because of how it went. */
    data class After(val memo: RoomAiMemo, val table: Next.Table? = null)

    /**
     * After [cued] on the table [before] → [after]: an Ai that failed too often concedes; a pass is made for an Ai that
     * neither moved nor passed where a pass was asked for; an Ai that stalled in its turn has the turn ended for it; the
     * choice of who goes first falls back to going first.
     */
    fun after(before: DuelGame, after: DuelGame, cued: Cued, memo: RoomAiMemo, rules: MatchRules): After {
        val s = after.state
        val seat = cued.seat
        val name = DuelWords.seatName(s, seat)
        var m = memo.copy(
            tokens = memo.tokens + cued.tokens,
            failures = memo.failures.mapIndexed { i, n -> if (i == seat) (if (cued.failed != null) n + 1 else 0) else n },
            read = memo.read.mapIndexed { i, r -> if (i == seat) after.cursor else r },
        )
        if (s.turn != m.turn) m = m.copy(turn = s.turn, playCues = 0, stalls = 0, turnCues = 0)
        if (cued.kind == CueKind.PLAY && s.turn == before.state.turn) m = m.copy(turnCues = m.turnCues + 1)
        if (DuelResults.ending(s) != null) return After(m)
        if (cued.failed != null && m.failures[seat] >= rules.failures) {
            return After(m, Next.Table(seat, listOf(DuelAction.Concede(seat)), "$name's Ai failed ${m.failures[seat]} times in a row (${cued.failed}): it concedes."))
        }
        val made: Next.Table? = when (cued.kind) {
            CueKind.CHOOSE -> if (s.opening?.decided == false) Next.Table(seat, listOf(DuelAction.GoFirst(seat, true)), "$name made no choice: it goes first, the default.") else null
            CueKind.RESPOND -> if (s.window?.responder == seat) Next.Table(seat, listOf(DuelAction.Answer(seat, respond = false)), "$name passed.") else null
            // Neither chained nor passed: a pass, so the person's link can resolve.
            CueKind.CHAIN -> if (s.chain.size == before.state.chain.size && s.chain.isNotEmpty() && s.window == null && !passedOnTop(after)) {
                Next.Table(seat, listOf(DuelAction.Answer(seat, respond = false)), "$name passed.")
            } else null
            CueKind.RESOLVE -> if (s.chain.size == before.state.chain.size && s.chain.isNotEmpty() && s.chain.last().seat == seat) {
                Next.Table(seat, listOf(DuelAction.ChainResolve), "$name did not resolve its link: the table resolves it.")
            } else null
            CueKind.ANSWER -> if (s.proposal != null) Next.Table(s.active, listOf(DuelAction.Decline(s.active)), "$name did not answer: declined.") else null
            CueKind.PLAY -> {
                val interrupted = s.window != null || s.chain.isNotEmpty() || s.proposal != null
                if (s.turn == before.state.turn && s.active == seat && !interrupted) {
                    m = m.copy(playCues = m.playCues + 1, stalls = if (cued.moves == 0) m.stalls + 1 else 0)
                    val why = when {
                        m.stalls >= rules.stalls -> "$name made no move for ${m.stalls} cues"
                        m.playCues >= rules.playCues -> "$name stopped ${m.playCues} times without ending its turn"
                        else -> null
                    }
                    why?.let { Next.Table(seat, listOf(DuelAction.EndTurn), "$it: the table ends its turn.") }
                } else null
            }
        }
        return After(m, made)
    }
}

/** What Ai's play at a room remembers between cues: the turn's cues and stalls, each seat's failures, the spend. */
data class RoomAiMemo(
    val turn: Int = 0,
    val playCues: Int = 0,
    val stalls: Int = 0,
    val turnCues: Int = 0,
    val failures: List<Int> = listOf(0, 0),
    /** Tokens read and written by Ai at this table, both seats. */
    val tokens: Long = 0,
    /** The log's length at each seat's last cue: its next cue says what happened since. */
    val read: List<Int?> = listOf(null, null),
)
