package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.LearnTools

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
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One seat's player in a match: given a cue's words and its seat's tools, it plays until it stops. */
interface MatchPlayer {
    suspend fun cue(text: String, tools: ToolRunner): CueResult

    /** What the cue under way has spent so far: a cue cut off by its time still counts what it read and wrote. */
    val spentInCue: Usage get() = Usage()
}

/**
 * What a cue cost, in tokens read and written, and why it failed if it did; [usage] is the same split by kind (new, cached
 * and written), so the watcher's counter can price each seat at its own model's rates (`Prices`).
 */
data class CueResult(val tokens: Long = 0, val failed: String? = null, val usage: Usage = Usage())

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

    /** Where the conversation sent now begins in [session]'s turns: a new page once the last grew long ([pageAt]). */
    private var page = 0

    override var spentInCue: Usage = Usage()
        private set

    /**
     * What it is saying, as it says it: the text of the turn being written so far, from the first word (the Lounge's
     * room conversation shows it live, L5 round three). Null for a seat, which only moves.
     */
    var onText: ((String) -> Unit)? = null

    override suspend fun cue(text: String, tools: ToolRunner): CueResult {
        // Append-only (the red team, 2026-10): the history sent is never edited — no old cue cut, no old result shortened —
        // or a model that binds its thinking to the conversation (Opus 5.5, Fable 5.1) refuses every later cue, and the
        // prompt cache is lost each time. A conversation grown long starts a new page instead, the cue's table its all.
        val fresh = page > 0 || session.turns.isNotEmpty()
        session = session.copy(turns = session.turns + ChatTurn.user(text, at = now()), updatedAt = now())
        if (fresh && Compaction.estimate(system, session.turns.drop(page), specs) > pageAt()) {
            page = session.turns.size - 1
            session = session.copy(turns = session.turns.dropLast(1) + ChatTurn.user(NEW_PAGE + "\n\n" + text, at = now()))
        }
        var spent = Usage()
        spentInCue = spent
        val saying = StringBuilder()
        var failed: String? = null
        var done = false
        var timedOut = false
        try {
            val request = TurnRequest(system, session.turns.drop(page), specs, model, effort)
            AgentLoop(backend, tools, maxSteps = steps, now = now, budget = budget).run(request).collect { e ->
                when (e) {
                    is AgentEvent.Appended -> {
                        session = session.copy(turns = session.turns + e.turn)
                        saying.clear()
                    }
                    is AgentEvent.Text -> onText?.let { say ->
                        saying.append(e.delta)
                        say(saying.toString())
                    }
                    is AgentEvent.Round -> {
                        spent += e.usage
                        spentInCue = spent
                    }
                    is AgentEvent.Failed -> failed = e.message
                    is AgentEvent.Done -> done = true
                    else -> Unit
                }
            }
            if (!done && failed == null) failed = "the model stopped without an answer"
        } catch (t: TimeoutCancellationException) {
            timedOut = true
            throw t
        } finally {
            // Stopped mid-call: every tool call gets its result, so the kept conversation can be read and carried on —
            // said truly: the cue's time ran out, or the person stopped the match.
            val last = session.turns.lastOrNull()
            val open = last?.takeIf { it.role == Role.ASSISTANT }?.toolUses.orEmpty()
            if (open.isNotEmpty()) {
                val stopped = open.map {
                    if (timedOut) Part.ToolResult(it.id, it.name, "Not run: this cue's time ran out first. Moves made before it stand.", isError = true)
                    else AgentLoop.unanswered(it, null, running = false, stopped = true)
                }
                session = session.copy(turns = session.turns + ChatTurn(Role.USER, stopped, now()))
            }
            session = session.copy(usage = session.usage + spent, updatedAt = now())
            withContext(NonCancellable) { keep(session) }
        }
        return CueResult(spent.read + spent.output, failed, spent)
    }

    /** How long a page of the conversation grows, in tokens, before the next cue starts a new one: well inside the window. */
    private fun pageAt(): Int = if (budget > 0) minOf((budget * PAGE_SHARE).toInt(), PAGE_TOKENS) else PAGE_TOKENS

    /** The conversation as sent for the next cue: the current page, never an edited copy. */
    fun sent(): List<ChatTurn> = session.turns.drop(page)

    companion object {
        /** A page's most: past it, a new page. */
        const val PAGE_TOKENS = 120_000
        const val PAGE_SHARE = 0.45

        const val NEW_PAGE = "(A new page of your record: what came before is kept, but not sent again. The table below is all you need; your plan, if you had one, say again to yourself in a line.)"
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
    /** Each seat's tokens so far by kind, after each cue: what the counter prices, seat by seat. */
    private val used: (List<Usage>) -> Unit = {},
) {
    init {
        table.onRefused = { seat, line -> status("${DuelWords.seatName(table.state, seat)}: ${line.take(160)}") }
    }

    var memo = MatchMemo()
        private set

    /** Each seat's tokens so far, by kind. */
    var usage: List<Usage> = List(players.size) { Usage() }
        private set

    private val rules get() = table.rules

    suspend fun run(): MatchEnd {
        var nudge: List<String?> = listOf(null, null)
        while (true) {
            val g = table.game
            when (val n = MatchReferee.next(g, memo, rules)) {
                is MatchReferee.Next.Over -> return end(n.winner to n.how, null)
                is MatchReferee.Next.Limit -> {
                    val ending = MatchReferee.limit(table.state)
                    table.say(MatchReferee.limitWords(table.state, n.why))
                    return end(ending, n.why)
                }
                is MatchReferee.Next.Table -> if (!table.table(n.seat, n.actions, n.note)) {
                    // The table cannot make its own move: nothing more can happen; said, and ended as a draw.
                    val why = "the table could not go on (${n.actions.joinToString { it::class.simpleName.orEmpty() }})"
                    table.say(MatchReferee.limitWords(table.state, why))
                    return end(MatchReferee.limit(table.state), why)
                }
                is MatchReferee.Next.Cue -> {
                    val seat = n.seat
                    // A negated link resolves doing nothing: the table resolves it without a cue.
                    if (n.kind == CueKind.RESOLVE && g.state.chain.lastOrNull()?.negated == true) {
                        resolved(seat, "Chain Link ${g.state.chain.size} is negated: the table resolves it.")
                        continue
                    }
                    val from = g.cursor
                    // The cue begins before its words are written: the brief it carries knows what the seat is asked.
                    table.beginCue(seat, n.kind)
                    val text = MatchPrompt.cue(table, seat, n.kind, memo.cues + 1, memo.read[seat], nudge[seat])
                    nudge = nudge.mapIndexed { i, x -> if (i == seat) null else x }
                    status("${DuelWords.seatName(g.state, seat)} is ${words(n.kind)}")
                    val r = try {
                        withTimeoutOrNull(rules.cueMillis) { players[seat].cue(text, table.runner(seat)) }
                            // What it spent before its time ran out still counts, against the budget and in its bill.
                            ?: players[seat].spentInCue.let { u -> CueResult(u.read + u.output, "no answer in ${rules.cueMillis / 1000} s", u) }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        players[seat].spentInCue.let { u -> CueResult(u.read + u.output, t.message ?: t::class.simpleName.orEmpty(), u) }
                    } finally {
                        table.endCue()
                    }
                    if (r.failed != null) table.say("${DuelWords.seatName(table.state, seat)}'s session failed this cue: ${r.failed.take(160)}")
                    val after = MatchReferee.after(g, table.game, MatchReferee.Cued(seat, n.kind, table.movesSince(from, seat), r.failed, r.tokens), memo, rules)
                    memo = after.memo
                    usage = usage.mapIndexed { i, u -> if (i == seat) u + r.usage else u }
                    spent(memo.tokens)
                    used(usage)
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
        val said = limit?.let { MatchReferee.limitWords(g.state, it) } ?: forfeit
        val r = DuelResults.aiVsAi(g, now(), g.header.id, engines, end = ending, said = said)
        status("Over")
        val words = r?.let { words(it, g) } ?: "The match ended."
        // The result is the log's last line, so it is read there after the bar is closed (a limit has said its own).
        if (limit == null) table.say(words)
        return MatchEnd(r, words)
    }

    private fun words(r: DuelResult, g: DuelGame): String {
        // A limit says why once: "A draw by limit in turn 7: the token budget … is spent."
        if (r.how == DuelResult.LIMIT) {
            val why = r.said?.substringAfter(": ", "")?.takeIf { it.isNotBlank() }
            val lead = r.winner?.let { "${DuelWords.seatName(g.state, it)} won on life points at the limit in turn ${r.turns}" }
            return (lead ?: "A draw by limit in turn ${r.turns}") + (why?.let { ": $it" } ?: ".")
        }
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
        val TOOLS: Set<String> = setOf("duel_state", "duel_moves", "duel_act", "card_info") + LearnTools.reading - "playbook_gaps"

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

        /**
         * What [cost] may come to in dollars, each seat at its own list [prices] (`Prices.of`), the tokens it can spend
         * shared evenly between the seats and split as `Prices.assumed` says; null when a seat's model has no price.
         */
        fun dollars(cost: Cost, prices: List<Prices.Price?>): Double? =
            Prices.total(prices.map { it to Prices.assumed(cost.tokens / prices.size.coerceAtLeast(1)) })

        /** What the seats have spent so far in dollars, each [usage] at its own [prices]; null when a seat has none. */
        fun dollars(usage: List<Usage>, prices: List<Prices.Price?>): Double? =
            Prices.total(prices.mapIndexed { i, p -> p to (usage.getOrNull(i) ?: Usage()) })

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
