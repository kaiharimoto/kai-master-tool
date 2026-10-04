package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelTally
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlinx.serialization.json.JsonObject

/** One seat of a match: who plays it (a connection's label and model) and the deck it sits down with. */
data class MatchSeat(
    /** The seat's name at the table, which both seats read. */
    val name: String,
    val deckId: String?,
    val deckName: String,
    val main: List<Int>,
    val extra: List<Int> = emptyList(),
    /** The connection playing it, by the person's label for it, and the model. */
    val connection: String = "",
    val model: String = "",
)

/**
 * The table of an Ai vs Ai match (`docs/phases/C.md` §6): one [DuelGame], two seats, and the tools each seat's session
 * plays it with — `duel_state`, `duel_moves`, `duel_act`, the very specs Ai plays kai with, and `card_info` for a card's
 * printed text — each answered **for that seat alone**: the brief through its own [com.kaiharimoto.mastertool.core.duel.DuelView]
 * (knowledge "self", no peeks), the menu of its own moves, its moves planned as `duel_act` plans a line at kai's table,
 * held to [com.kaiharimoto.mastertool.core.duel.DuelReach] as the network's guest is, their words through `Secrets` on the
 * table they are made on, and committed through [DuelHost.act] so a move opens the other seat's response window as at a
 * networked table. Every move carries its seat's provenance (Ai at that seat, knowledge self, the fingerprint of its view).
 *
 * Nothing here hands one seat anything of the other's but the table: no session's reasoning, its replies or its guide.
 */
class MatchTable(
    game: DuelGame,
    val catalog: DuelCatalog,
    val rules: MatchRules,
    /** A card's printed text by its name (public: a card database, never the table). */
    private val cardText: (String) -> String? = { null },
    private val now: () -> Long = { 0L },
) {
    var game: DuelGame = game
        private set
    val state: DuelState get() = game.state

    /** The seat being cued, and the table moves it has made in this cue. */
    private var cueSeat: Int? = null
    private var cueMoves = 0

    /** Called after every move on the table — the person watches it live — and given the pace to keep. */
    var onMove: suspend (DuelGame) -> Unit = {}

    /** Ai's moves at [seat]: knowledge self, the view's fingerprint sealed on commit. */
    fun by(seat: Int): Provenance = Provenance(Provenance.AI, aiSeat = seat, aiKnows = DuelBrief.SELF)

    /** The table's own move for [seat] (a die, a draw, a pass made for it), with [note] written first when there is one. */
    suspend fun table(seat: Int?, actions: List<DuelAction>, note: String? = null): Boolean {
        // The note is no seat's, so both seats' next cues carry it.
        note?.let { say(it) }
        if (actions.isEmpty()) return true
        val r = game.act(actions, seat, now(), by = Provenance(Provenance.TABLE))
        if (!r.ok) return false
        game = r.game
        onMove(game)
        return true
    }

    /** The referee's words in the log, for both seats and the person watching. */
    suspend fun say(text: String) {
        val r = game.act(listOf(DuelAction.Note(text)), null, now(), by = Provenance(Provenance.TABLE))
        if (r.ok) {
            game = r.game
            onMove(game)
        }
    }

    /** The seat's newest chain link resolved by the table for it (a negated link, or a seat that did not resolve). */
    suspend fun resolveFor(seat: Int, note: String? = null): Boolean = table(seat, DuelVerbs.resolve(state, catalog), note)

    /** A cue for [seat] begins: its moves are counted from here. */
    fun beginCue(seat: Int) {
        cueSeat = seat
        cueMoves = 0
    }

    fun endCue() {
        cueSeat = null
    }

    /** The table moves (talk left out) [seat]'s session made since entry [from]. */
    fun movesSince(from: Int, seat: Int): Int =
        game.entries.subList(from.coerceAtMost(game.cursor), game.cursor).count { e -> e.by?.byAi == true && e.by.aiSeat == seat && !DuelGame.isTalk(e.action) }

    // ---- the tools, for one seat ----------------------------------------------------------------------------------

    /** [seat]'s tool runner for a cue: the four tools, nothing else. */
    fun runner(seat: Int): ToolRunner = ToolRunner { call ->
        val (text, error) = tool(seat, call.name, call.input)
        Part.ToolResult(call.id, call.name, text, isError = error)
    }

    suspend fun tool(seat: Int, name: String, input: JsonObject): Pair<String, Boolean> = when (name.removePrefix("mcp__neue__")) {
        "duel_state" -> brief(seat) to false
        "duel_moves" -> moves(seat, input)
        "duel_act" -> act(seat, input)
        "card_info" -> cards(seat, ToolArgs.strings(input, "cards")) to false
        else -> "Only duel_state, duel_moves, duel_act and card_info are played at this table." to true
    }

    /** The table as [seat] sees it, and nothing more: its own view, knowledge self, this turn's moves as it saw them. */
    fun brief(seat: Int): String {
        val g = game
        val head = "Ai vs Ai — you play ${DuelWords.seatLabel(g.state, seat)} with “${g.header.seats.getOrNull(seat)?.deckName.orEmpty().ifBlank { "your deck" }}”. " +
            "Turn ${g.state.turn} of at most ${rules.turnCap}."
        return head + "\n" + DuelBrief.describe(
            g.state, seat, catalog, g.header.seed, seat,
            tally = DuelTally.of(g, catalog, seat),
            history = DuelBrief.turnLines(g, seat, catalog),
        )
    }

    private fun moves(seat: Int, input: JsonObject): Pair<String, Boolean> {
        val g = game
        val s = g.state
        val only = ToolArgs.string(input, "card")?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            when (val l = DuelCommand.lookup(q, s, seat, catalog, DuelCommand.Want.TARGET, secret = g.header.seed)) {
                is DuelCommand.Lookup.One -> l.uid
                is DuelCommand.Lookup.Many -> return "“$q” could be ${l.names.joinToString(" or ")}: give its coordinate." to true
                is DuelCommand.Lookup.None -> return l.why to true
            }
        }
        val menu = DuelMoves.menu(s, seat, catalog, g.header.seed, only)
        if (menu.isEmpty()) return (if (only != null) "No move for that card now." else "No moves now.") to false
        val cap = (ToolArgs.int(input, "limit") ?: DuelMoves.CAP).coerceIn(10, 600)
        return ("Your moves as ${DuelWords.seatLabel(s, seat)} — each `op` exactly as duel_act takes it; add a zone to put a card elsewhere (s h2 m4):\n" +
            DuelMoves.words(menu, cap)) to false
    }

    /** A card's printed text: by name, or by a coordinate or #uid the seat can see. Public text, never the table's secrets. */
    private fun cards(seat: Int, asked: List<String>): String {
        if (asked.isEmpty()) return "Name the cards to read."
        val s = state
        return asked.take(8).joinToString("\n\n") { q ->
            val name = (DuelCommand.lookup(q, s, seat, catalog, DuelCommand.Want.ANY, everywhere = true, secret = game.header.seed) as? DuelCommand.Lookup.One)
                ?.let { catalog.nameOf(s.cards.getValue(it.uid)) }
                ?: q.trim().removePrefix("[[").removeSuffix("]]")
            cardText(name)?.let { "$name\n$it" } ?: "No card named “$q”."
        }
    }

    /**
     * [seat]'s moves, each op planned on the table as `duel_act` plans a line at kai's table (the duel's secret, so `oh2`
     * is the card the brief shows there), its words kept from naming the seat's hidden cards, held to `DuelReach`, then
     * committed a step at a time through [DuelHost.act] — which opens the other seat's response window as it would at a
     * networked table, and refuses a move of the seat whose window waits on the other.
     */
    private suspend fun act(seat: Int, input: JsonObject): Pair<String, Boolean> {
        if (ToolArgs.string(input, "at")?.isNotBlank() == true) return "There is no going back at this table: play the moves now." to true
        val ops = ToolArgs.strings(input, "ops").flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (ops.isEmpty()) return "No ops to play." to true
        if (cueSeat != seat) return "It is not your cue: wait to be cued." to true
        val out = mutableListOf<String>()
        var refused = false
        loop@ for (op in ops) {
            if (DuelResults.ending(state) != null) { out += "✗ $op: the duel is over."; refused = true; break }
            val s = state
            val lower = op.lowercase().trim().trimEnd('.', '!')
            // A pass where one is asked for: a window waiting on this seat, or the other seat's chain link standing.
            if (lower in PASS && (s.window?.responder == seat || (s.chain.isNotEmpty() && s.chain.last().seat != seat && s.window == null))) {
                val r = game.act(listOf(DuelAction.Answer(seat, respond = false)), seat, now(), by = by(seat))
                if (!r.ok) { out += "✗ $op: ${r.problem}"; refused = true; break }
                game = r.game
                onMove(game)
                out += "✓ $op → you passed. Stop here: you will be cued when it is your move."
                break
            }
            if (s.window?.opener == seat) { out += "✗ $op: ${WAIT}"; refused = true; break }
            if (cueMoves >= rules.cueMoves) {
                out += "✗ $op: this cue's ${rules.cueMoves} moves are spent. Stop here; you will be cued again."
                refused = true
                break
            }
            val planned = ComboRunner.plan(s, seat, listOf(op), catalog, game.header.seed)
            if (!planned.ok) { out += "✗ $op: ${planned.problem.orEmpty().substringAfter("): ", planned.problem.orEmpty())}"; refused = true; break }
            val plan = ComboRunner.redacted(s, seat, planned, catalog)
            ComboRunner.reach(s, seat, plan)?.let { why ->
                out += "✗ $op: ${why.substringAfter("): ", why)}: a player does not do that to a card they cannot see."
                refused = true
                break@loop
            }
            for ((text, actions) in plan.steps) {
                refusal(state, seat, actions)?.let { why -> out += "✗ $text: $why"; refused = true; break@loop }
                val before = game.cursor
                val r = DuelHost.act(game, seat, actions, windows = mapOf(0 to rules.windows, 1 to rules.windows), at = now(), by = by(seat))
                if (!r.ok) { out += "✗ $text: ${r.problem}"; refused = true; break@loop }
                game = r.game
                onMove(game)
                val lines = DuelHost.lines(game, before, seat, catalog).map { it.text }
                out += "✓ $text → ${lines.joinToString("; ").ifBlank { "no change on the table" }}"
                if (actions.any { !it.social }) cueMoves++
                if (state.window?.opener == seat) {
                    out += "A response window is open for ${DuelWords.seatName(state, 1 - seat)}. Stop here: you will be cued again once they respond or pass."
                    break@loop
                }
                if (DuelResults.ending(state) != null) break@loop
            }
        }
        return (out.joinToString("\n") + "\n\nThe table now:\n" + brief(seat)) to refused
    }

    /** What this table refuses beyond the physics: the phases and the end of a turn are the turn player's, the dice the table's. */
    private fun refusal(s: DuelState, seat: Int, actions: List<DuelAction>): String? {
        var active = s.active
        for (a in actions) {
            when (a) {
                is DuelAction.Phase, DuelAction.EndTurn -> {
                    if (active != seat) return "It is not your turn: the turn player moves the phases."
                    if (a == DuelAction.EndTurn) active = 1 - active
                }
                is DuelAction.OpeningRoll -> return "The table throws the opening roll's dice for both seats."
                is DuelAction.Concede -> if (a.seat != seat) return "Only a player concedes for themselves."
                is DuelAction.Answer -> if (s.window != null && s.window.responder != seat) return "That window waits on the other player."
                else -> Unit
            }
        }
        return null
    }

    companion object {
        /** What a seat says to pass priority or a response window. */
        val PASS = setOf("pass", "no response", "no", "i pass", "pass priority")

        const val WAIT = "a response window is open for your opponent: wait for their answer. Stop here; you will be cued again."

        /** The header a match's table is dealt from: both seats' decks, the opening roll, [seed]. */
        fun header(id: String, seed: Long, seats: List<MatchSeat>, created: Long): DuelHeader = DuelHeader(
            id = id,
            seed = seed,
            seats = seats.map { SeatSetup(it.name, it.main, it.extra, it.deckId, it.deckName) },
            created = created,
            openingRoll = true,
        )
    }
}
