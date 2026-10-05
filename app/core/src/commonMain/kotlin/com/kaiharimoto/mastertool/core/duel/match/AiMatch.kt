package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.AgentEvent
import com.kaiharimoto.mastertool.core.ai.AgentLoop
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Compaction
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One seat's player in a match: given a cue's words and its seat's tools, it plays until it stops. */
interface MatchPlayer {
    suspend fun cue(text: String, tools: ToolRunner): CueResult
}

/** What a cue cost, in tokens read and written, and why it failed if it did. */
data class CueResult(val tokens: Long = 0, val failed: String? = null)

/**
 * One seat's session (`docs/phases/C.md` §6): a headless agent run of its own — its own backend and model, its own
 * history, its own [AiSession] kept so the person can read its side afterwards — fed only its cues and its own tools'
 * results. Its replies and reasoning stay in its session: nothing it says reaches the other seat but through the table.
 */
class AgentPlayer(
    private val backend: ModelBackend,
    private val system: String,
    /** The tool specs it is offered: duel_state, duel_moves, duel_act, card_info. */
    private val specs: List<ToolSpec>,
    private val model: String,
    private val effort: String,
    private val steps: Int,
    /** The model's window in tokens, so the loop shortens old tool results before it overflows; 0: never. */
    private val budget: Int,
    private val now: () -> Long,
    session: AiSession,
    /** The session after each cue, to be kept. */
    private val keep: (AiSession) -> Unit = {},
) : MatchPlayer {
    var session: AiSession = session
        private set

    override suspend fun cue(text: String, tools: ToolRunner): CueResult {
        session = session.copy(turns = session.turns + ChatTurn.user(text, at = now()), updatedAt = now())
        var spent = Usage()
        var failed: String? = null
        var done = false
        try {
            val request = TurnRequest(system, sent(session.turns), specs, model, effort)
            AgentLoop(backend, tools, maxSteps = steps, now = now, budget = budget).run(request).collect { e ->
                when (e) {
                    is AgentEvent.Appended -> session = session.copy(turns = session.turns + e.turn)
                    is AgentEvent.Round -> spent += e.usage
                    is AgentEvent.Failed -> failed = e.message
                    is AgentEvent.Done -> done = true
                    else -> Unit
                }
            }
            if (!done && failed == null) failed = "the model stopped without an answer"
        } finally {
            // Stopped mid-call: every tool call gets its result, so the kept conversation can be read and carried on.
            val last = session.turns.lastOrNull()
            val open = last?.takeIf { it.role == Role.ASSISTANT }?.toolUses.orEmpty()
            if (open.isNotEmpty()) {
                val stopped = open.map { AgentLoop.unanswered(it, null, running = false, stopped = true) }
                session = session.copy(turns = session.turns + ChatTurn(Role.USER, stopped, now()))
            }
            session = session.copy(usage = session.usage + spent, updatedAt = now())
            withContext(NonCancellable) { keep(session) }
        }
        return CueResult(spent.read + spent.output, failed)
    }

    /**
     * What is sent: the whole conversation, but each cue older than the last [KEEP_CUES] cut to its first line (its table
     * was given again since) and old tool results shortened — so a long match costs each cue about the same.
     */
    private fun sent(turns: List<ChatTurn>): List<ChatTurn> {
        val cues = turns.indices.filter { i -> turns[i].role == Role.USER && !turns[i].isToolResults }
        val old = cues.dropLast(KEEP_CUES).toSet()
        val trimmed = turns.mapIndexed { i, t ->
            if (i !in old) t
            else t.copy(parts = t.parts.map { p -> if (p is Part.Text) Part.Text(p.text.lineSequence().first() + " (the table as it was then, given again since)") else p })
        }
        return Compaction.prune(trimmed, keep = KEEP_TURNS, max = 400)
    }

    companion object {
        const val KEEP_CUES = 2
        const val KEEP_TURNS = 12
    }
}

/** How a match ended: its record (null when the person stopped it before it ended), and in words. */
data class MatchEnd(val result: DuelResult?, val words: String, val stopped: Boolean = false)

/**
 * The referee's loop (`docs/phases/C.md` §6): asks [MatchReferee] whose move it is, makes the table's own moves, cues
 * the seat whose move it is with its brief and what changed since its last cue, waits for its moves within the cue's
 * bounds, and goes on until the duel ends on the table, a limit ends it as a draw, or the person stops it (the
 * coroutine cancelled: both runs end where they stand, their sessions kept).
 */
class AiMatch(
    val table: MatchTable,
    private val players: List<MatchPlayer>,
    private val engines: List<DuelResults.Engine>,
    private val now: () -> Long = { 0L },
    /** Who is moving now, in words, for the person watching. */
    private val status: (String) -> Unit = {},
    /** The tokens spent so far, both seats, after each cue: the watcher's counter against the budget. */
    private val spent: (Long) -> Unit = {},
) {
    var memo = MatchMemo()
        private set

    private val rules get() = table.rules

    suspend fun run(): MatchEnd {
        var nudge: List<String?> = listOf(null, null)
        while (true) {
            val g = table.game
            when (val n = MatchReferee.next(g, memo, rules)) {
                is MatchReferee.Next.Over -> return end(n.winner to n.how, null)
                is MatchReferee.Next.Limit -> {
                    table.say("A draw by limit: ${n.why}.")
                    return end(MatchReferee.limit(), n.why)
                }
                is MatchReferee.Next.Table -> if (!table.table(n.seat, n.actions, n.note)) {
                    // The table cannot make its own move: nothing more can happen; said, and ended as a draw.
                    val why = "the table could not go on (${n.actions.joinToString { it::class.simpleName.orEmpty() }})"
                    table.say("A draw by limit: $why.")
                    return end(MatchReferee.limit(), why)
                }
                is MatchReferee.Next.Cue -> {
                    val seat = n.seat
                    // A negated link resolves doing nothing: the table resolves it without a cue.
                    if (n.kind == CueKind.RESOLVE && g.state.chain.lastOrNull()?.negated == true) {
                        resolved(seat, "Chain Link ${g.state.chain.size} is negated: the table resolves it.")
                        continue
                    }
                    val from = g.cursor
                    val text = MatchPrompt.cue(table, seat, n.kind, memo.cues + 1, memo.read[seat], nudge[seat])
                    nudge = nudge.mapIndexed { i, x -> if (i == seat) null else x }
                    status("${DuelWords.seatName(g.state, seat)} is ${words(n.kind)}")
                    table.beginCue(seat)
                    val r = try {
                        withTimeoutOrNull(rules.cueMillis) { players[seat].cue(text, table.runner(seat)) }
                            ?: CueResult(failed = "no answer in ${rules.cueMillis / 1000} s")
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        CueResult(failed = t.message ?: t::class.simpleName.orEmpty())
                    } finally {
                        table.endCue()
                    }
                    if (r.failed != null) table.say("${DuelWords.seatName(table.state, seat)}'s session failed this cue: ${r.failed.take(160)}")
                    val after = MatchReferee.after(g, table.game, MatchReferee.Cued(seat, n.kind, table.movesSince(from, seat), r.failed, r.tokens), memo, rules)
                    memo = after.memo
                    spent(memo.tokens)
                    for (t in after.table) {
                        if (t.note == MatchReferee.RESOLVE_FOR) resolved(seat, "${DuelWords.seatName(table.state, seat)} did not resolve its link: the table resolves it.")
                        else table.table(t.seat, t.actions, t.note)
                    }
                    if (n.kind == CueKind.PLAY && memo.stalls > 0 && table.state.active == seat) {
                        nudge = nudge.mapIndexed { i, x -> if (i == seat) "You stopped last time without a move and without ending your turn: play on, or `end`." else x }
                    }
                }
            }
        }
    }

    /** The newest link resolved by the table for [seat]; the rest of the chain goes on resolving. */
    private suspend fun resolved(seat: Int, note: String) {
        table.resolveFor(seat, note)
        memo = memo.copy(passedOn = table.state.chain.size.takeIf { it > 0 })
    }

    private suspend fun end(ending: Pair<Int?, String>, limit: String?): MatchEnd {
        val g = table.game
        val forfeit = g.played.lastOrNull { it.action is DuelAction.Note && it.by?.by == Provenance.TABLE }
            ?.let { (it.action as DuelAction.Note).text }
            ?.takeIf { g.state.conceded != null && ("forfeits" in it || "decked out" in it) }
        val said = limit?.let { "A draw by limit: $it." } ?: forfeit
        val r = DuelResults.aiVsAi(g, now(), g.header.id, engines, end = ending, said = said)
        status("Over")
        val words = r?.let { words(it, g) } ?: "The match ended."
        // The result is the log's last line, so it is read there after the bar is closed (a limit has said its own).
        if (limit == null) table.say(words)
        return MatchEnd(r, words)
    }

    private fun words(r: DuelResult, g: DuelGame): String {
        // A limit says why once: "A draw by limit in turn 7: the token budget … is spent."
        if (r.how == DuelResult.LIMIT) return r.said?.removePrefix("A draw by limit: ")?.let { "A draw by limit in turn ${r.turns}: $it" } ?: "A draw by limit in turn ${r.turns}."
        val who = r.winner?.let { "${DuelWords.seatName(g.state, it)} won" } ?: "A draw"
        val how = when (r.how) {
            DuelResult.CONCEDE -> "by concession"
            DuelResult.LP -> "on life points"
            DuelResult.LIMIT -> "by limit"
            else -> ""
        }
        return listOfNotNull("$who $how in turn ${r.turns}.".replace("  ", " "), r.said).joinToString(" ")
    }

    companion object {
        fun words(kind: CueKind): String = when (kind) {
            CueKind.CHOOSE -> "choosing first or second"
            CueKind.PLAY -> "playing its turn"
            CueKind.RESPOND -> "deciding whether to respond"
            CueKind.CHAIN -> "deciding whether to chain"
            CueKind.RESOLVE -> "resolving its link"
            CueKind.ANSWER -> "answering an ask"
        }

        /** The tools each seat is offered, by name: the table's three and a card's printed text. */
        val TOOLS: Set<String> = setOf("duel_state", "duel_moves", "duel_act", "card_info")

        /** Roughly what one cue costs, read and written, for the estimate shown before a match. */
        const val TOKENS_PER_CUE = 18_000L

        /** Roughly how many cues a turn takes, both seats: the turn player's few and the other's answers. */
        const val CUES_PER_TURN = 4

        /**
         * What a match may spend, before it starts: [cues] the cues it would take to reach the turn cap (both seats),
         * [estimate] what those would cost, and [tokens] the most it can spend — never more than the cap, where it stops.
         */
        data class Cost(val cues: Int, val estimate: Long, val tokens: Long) {
            val capped: Boolean get() = estimate > tokens
        }

        fun cost(rules: MatchRules): Cost {
            val cues = minOf(rules.turnCap * CUES_PER_TURN + 2, rules.maxCues)
            val estimate = cues * TOKENS_PER_CUE
            return Cost(cues, estimate, minOf(estimate, rules.tokenCap))
        }

        /** The token budgets the start dialog offers, both seats. */
        val BUDGETS: List<Long> = listOf(250_000L, 500_000L, 1_000_000L, 2_000_000L)

        /**
         * The budget a match of [turnCap] turns starts with: the smallest offered that covers the estimate, so the first
         * match someone starts with the defaults can finish (12 turns: 1M) — the largest when none does.
         */
        fun budgetFor(turnCap: Int): Long {
            val estimate = cost(MatchRules(turnCap = turnCap, tokenCap = Long.MAX_VALUE)).estimate
            return BUDGETS.firstOrNull { it >= estimate } ?: BUDGETS.last()
        }

        /** About the turn in which a budget of [tokenCap] stops a match, at the estimate's pace. */
        fun stopsAt(tokenCap: Long): Int = ((tokenCap / TOKENS_PER_CUE - 2) / CUES_PER_TURN + 1).toInt().coerceAtLeast(1)
    }
}
