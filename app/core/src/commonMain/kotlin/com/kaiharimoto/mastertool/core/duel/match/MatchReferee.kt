package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.TurnStart
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelWords

/**
 * How an Ai vs Ai match is bounded (`docs/phases/C.md` §6): per cue, per turn and for the whole match, so two sessions can
 * never run up a bill nobody saw coming nor stall the table for ever.
 */
data class MatchRules(
    /** A turn past this ends the match as a draw by limit. */
    val turnCap: Int = 12,
    /** Table moves (ops) a seat may make in one cue; the rest wait for its next. */
    val cueMoves: Int = 12,
    /** Rounds of tools in one cue (the agent loop's cap). */
    val cueSteps: Int = 10,
    /** How long one cue may take before it counts as a failure. */
    val cueMillis: Long = 240_000,
    /** Cues a turn player stops of its own accord in one turn, its turn not ended, before the table ends the turn. */
    val playCues: Int = 5,
    /** Cues in a row in which the turn player made no move before the table ends its turn. */
    val stalls: Int = 2,
    /** A seat's failed cues in a row (an error, a timeout) before it forfeits. */
    val failures: Int = 3,
    /** When a move opens a response window for the other seat (`Windows`): activations, summons too, or always. */
    val windows: String = Windows.ACTIVATIONS,
    /** Cues in the whole match, both seats. */
    val maxCues: Int = 300,
    /** Tokens read and written in the whole match, both seats; past it the match ends as a draw by limit. */
    val tokenCap: Long = 500_000,
    /** Milliseconds between moves on the table, so the person watching can follow; 0 in tests. */
    val paceMs: Long = 350,
)

/** What a cue asks of a seat. */
enum class CueKind {
    /** The opening roll's winner chooses to go first or second. */
    CHOOSE,

    /** The turn player plays its turn. */
    PLAY,

    /** A response window is open on the other seat's move: respond, or pass. */
    RESPOND,

    /** The other seat's chain link stands: chain to it, or pass so it resolves. */
    CHAIN,

    /** Both have passed: the seat resolves its newest link. */
    RESOLVE,

    /** The other seat asks to move the phase on: the turn player accepts or declines. */
    ANSWER,
}

/** What the referee remembers between cues: passes on a chain, the turn's cues and stalls, each seat's failures, the spend. */
data class MatchMemo(
    /** The chain's length when the seat with priority passed on it: from there it resolves, newest first. */
    val passedOn: Int? = null,
    /** The turn the counters below are for. */
    val turn: Int = 0,
    /** The turn player's cues this turn that it ended by itself, its turn not ended. */
    val playCues: Int = 0,
    /** The turn player's cues in a row this turn with no move. */
    val stalls: Int = 0,
    /** Each seat's failed cues in a row. */
    val failures: List<Int> = listOf(0, 0),
    /** Cues given, both seats. */
    val cues: Int = 0,
    /** Tokens spent, both seats. */
    val tokens: Long = 0,
    /** The log's length at each seat's last cue: its next cue says what happened since. */
    val read: List<Int?> = listOf(null, null),
)

/**
 * The referee of an Ai vs Ai match (`docs/phases/C.md` §6), pure: whose move it is — the opening roll's throws and choice,
 * a response window's responder, the seat that may chain or must resolve, a phase asked for, the turn player — what the
 * table does by itself (the dice, a turn's draw, a pass for a seat that made no move, the end of a turn that stalled, a
 * forfeit), and when the match is over. It never reads a hidden card and never judges card text: the table is manual, the
 * referee only keeps the turn.
 */
object MatchReferee {
    /** What comes next. */
    sealed interface Next {
        /** The duel has ended on the table: life points at 0, a concession. */
        data class Over(val winner: Int?, val how: String) : Next

        /** The match ends as a draw by limit, for [why]. */
        data class Limit(val why: String) : Next

        /** The table moves by itself for [seat] (null: no seat's): a die thrown, a turn's draw, a seat that decked out. */
        data class Table(val seat: Int?, val actions: List<DuelAction>, val note: String? = null) : Next

        /** [seat] is cued for [kind]. */
        data class Cue(val seat: Int, val kind: CueKind) : Next
    }

    fun next(g: DuelGame, memo: MatchMemo, rules: MatchRules): Next {
        val s = g.state
        DuelResults.ending(s)?.let { (winner, how) -> return Next.Over(winner, how) }
        if (s.turn > rules.turnCap) return Next.Limit("turn ${s.turn} reached: the match is capped at ${rules.turnCap} turns")
        if (memo.tokens >= rules.tokenCap) return Next.Limit("the token budget of ${rules.tokenCap} is spent")
        if (memo.cues >= rules.maxCues) return Next.Limit("${rules.maxCues} cues given, the most a match takes")
        s.opening?.takeIf { !it.decided }?.let { o ->
            val winner = o.winner
            if (winner != null) return Next.Cue(winner, CueKind.CHOOSE)
            val seat = (0..1).first { o.waitsOn(it) }
            return Next.Table(seat, listOf(DuelAction.OpeningRoll(seat)))
        }
        s.window?.let { return Next.Cue(it.responder, CueKind.RESPOND) }
        s.proposal?.let { return Next.Cue(s.active, CueKind.ANSWER) }
        s.chain.lastOrNull()?.let { top ->
            return if (memo.passedOn == s.chain.size) Next.Cue(top.seat, CueKind.RESOLVE) else Next.Cue(1 - top.seat, CueKind.CHAIN)
        }
        // A turn's draw, made by the table as at kai's (TurnStart); a Deck that cannot give it loses the duel.
        TurnStart.next(g)?.let { return Next.Table(s.active, listOf(it)) }
        if (s.phase == DuelPhase.DRAW && s.turn > 1 && !TurnStart.drewThisTurn(g) && s.seats[s.active].deck.isEmpty()) {
            return Next.Table(s.active, listOf(DuelAction.Concede(s.active)), "${DuelWords.seatName(s, s.active)} cannot draw for its turn: it loses the duel (decked out).")
        }
        return Next.Cue(s.active, CueKind.PLAY)
    }

    /** What a cue came to: the moves the seat made in it, whether it failed (an error, a timeout) and why. */
    data class Cued(val seat: Int, val kind: CueKind, val moves: Int, val failed: String? = null, val tokens: Long = 0)

    /** The memo after a cue, and what the table does for the seat because of how it ended. */
    data class After(val memo: MatchMemo, val table: List<Next.Table>)

    /**
     * After [cued] on the table [before] → [after]: a seat that failed too often forfeits; a pass is made for a seat that
     * neither moved nor passed where a pass was asked for; a chain both passed on resolves; the turn player that stalled
     * or kept stopping has its turn ended for it; the choice of who goes first falls back to going first.
     */
    fun after(before: DuelGame, after: DuelGame, cued: Cued, memo: MatchMemo, rules: MatchRules): After {
        val s = after.state
        val seat = cued.seat
        val name = DuelWords.seatName(s, seat)
        var m = memo.copy(
            cues = memo.cues + 1,
            tokens = memo.tokens + cued.tokens,
            failures = memo.failures.mapIndexed { i, n -> if (i == seat) (if (cued.failed != null) n + 1 else 0) else n },
            read = memo.read.mapIndexed { i, r -> if (i == seat) after.cursor else r },
        )
        if (s.turn != m.turn) m = m.copy(turn = s.turn, playCues = 0, stalls = 0)
        if (cued.failed != null && m.failures[seat] >= rules.failures && DuelResults.ending(s) == null) {
            return After(
                m,
                listOf(Next.Table(seat, listOf(DuelAction.Concede(seat)), "$name's session failed ${m.failures[seat]} times in a row (${cued.failed}): it forfeits.")),
            )
        }
        if (DuelResults.ending(s) != null) return After(m, emptyList())
        val out = mutableListOf<Next.Table>()
        fun passed(chain: Int) {
            m = m.copy(passedOn = chain)
        }
        when (cued.kind) {
            CueKind.CHOOSE -> if (s.opening?.decided == false) {
                out += Next.Table(seat, listOf(DuelAction.GoFirst(seat, true)), "$name made no choice: it goes first, the default.")
            }
            CueKind.RESPOND -> {
                val w = s.window
                if (w != null && w.responder == seat) {
                    out += Next.Table(seat, listOf(DuelAction.Answer(seat, respond = false)), "$name passed.")
                }
                // A pass on a window over the other seat's chain link is a pass on the chain.
                val top = s.chain.lastOrNull()
                if (top != null && top.seat != seat && s.chain.size == before.state.chain.size) passed(s.chain.size)
                else if (s.chain.size != before.state.chain.size) m = m.copy(passedOn = null)
            }
            CueKind.CHAIN -> {
                when {
                    s.window != null -> m = m.copy(passedOn = null)
                    s.chain.size == before.state.chain.size && s.chain.isNotEmpty() -> passed(s.chain.size)
                    else -> m = m.copy(passedOn = null)
                }
            }
            CueKind.RESOLVE -> {
                val was = before.state.chain.size
                when {
                    s.chain.size > was -> m = m.copy(passedOn = null)
                    s.chain.size == was && s.chain.isNotEmpty() -> out += Next.Table(seat, emptyList(), RESOLVE_FOR)
                    // Resolved: the rest of the chain resolves too, newest first.
                    else -> m = m.copy(passedOn = s.chain.size.takeIf { it > 0 })
                }
            }
            CueKind.ANSWER -> if (s.proposal != null) out += Next.Table(s.active, listOf(DuelAction.Decline(s.active)), "$name did not answer: declined.")
            CueKind.PLAY -> {
                val interrupted = s.window != null || s.chain.isNotEmpty() || s.proposal != null
                if (s.turn == before.state.turn && s.active == seat && !interrupted) {
                    m = m.copy(playCues = m.playCues + 1, stalls = if (cued.moves == 0) m.stalls + 1 else 0)
                    val why = when {
                        m.stalls >= rules.stalls -> "$name made no move for ${m.stalls} cues"
                        m.playCues >= rules.playCues -> "$name stopped ${m.playCues} times without ending its turn"
                        else -> null
                    }
                    if (why != null) out += Next.Table(seat, listOf(DuelAction.EndTurn), "$why: the table ends its turn.")
                }
            }
        }
        if (s.chain.isEmpty()) m = m.copy(passedOn = null)
        return After(m, out)
    }

    /** The note on a [Next.Table] with no actions: the table resolves the seat's newest link (its actions need the catalog). */
    const val RESOLVE_FOR = "resolve"

    /** The ending of a match the table ended, as the record writes it: a draw by limit. */
    fun limit(): Pair<Int?, String> = null to DuelResult.LIMIT
}
