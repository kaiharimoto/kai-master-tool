package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.LearnTools
import com.kaiharimoto.mastertool.core.ai.playbook.Playbook
import com.kaiharimoto.mastertool.core.duel.ai.DuelGuide
import com.kaiharimoto.mastertool.core.duel.ai.DuelPosition

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
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.board.DuelPhase
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
    /**
     * What a seat knows of its own deck (mastery, 1.1.42): `playbook_search`, `playbook_read`, `course_search`,
     * `course_open` answered for [seat]'s own deck alone — never the other seat's — or null when it keeps none.
     */
    private val knowledge: suspend (seat: Int, tool: String, input: JsonObject) -> String? = { _, _, _ -> null },
    /** [seat]'s own deck's playbook, for the entries a cue shows for its position; null when it has none. */
    private val playbook: (seat: Int) -> Playbook? = { null },
    /**
     * Each seat's own response windows (`Windows`), when they differ from [MatchRules.windows]: at a Lounge room a person
     * keeps their own setting, as at any networked table (`docs/LOUNGE.md`).
     */
    private val seatWindows: ((Int) -> String)? = null,
) {
    var game: DuelGame = game
        private set
    val state: DuelState get() = game.state

    /** The seat being cued, what it is cued for, and the table moves, talk and chance it has made in this cue. */
    private var cueSeat: Int? = null
    private var cueKind: CueKind? = null
    private var cueMoves = 0
    private var cueTalk = 0
    private var cueRolls = 0

    /** Called after every move on the table — the person watches it live — and given the pace to keep. */
    var onMove: suspend (DuelGame) -> Unit = {}

    /**
     * A seat's move the table refused, for the person watching (the red team: a seat stuck on its syntax was invisible until
     * the table ended its turn). Never written in the log: a refusal may name the seat's own hidden cards.
     */
    var onRefused: (seat: Int, line: String) -> Unit = { _, _ -> }

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

    /**
     * The table as it stands now, taken up before a seat's tool call: at a Lounge room a person may have moved while Ai
     * thought (`docs/LOUNGE.md`), and Ai plays on from there.
     */
    fun adopt(g: DuelGame) {
        game = g
    }

    /** A cue for [seat] begins: its moves are counted from here. */
    fun beginCue(seat: Int, kind: CueKind? = null) {
        cueSeat = seat
        cueKind = kind
        cueMoves = 0
        cueTalk = 0
        cueRolls = 0
    }

    fun endCue() {
        cueSeat = null
        cueKind = null
    }

    /** What the match's law allows [seat] now ([MatchLaw]), or every move when the table is not strict. */
    private fun lawful(s: DuelState, seat: Int, actions: List<DuelAction>): String? =
        if (rules.strict) MatchLaw.refusal(s, seat, cueKind, actions) else null

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
        in LearnTools.reading -> knowledge(seat, name.removePrefix("mcp__neue__"), input)?.let { it to false }
            ?: ("Nothing is kept on your deck for that." to true)
        else -> "Only duel_state, duel_moves, duel_act, card_info and your deck's playbook and course are used at this table." to true
    }

    /** The playbook's entries for [seat]'s position as it sees it, for its cue; "" when it keeps none. */
    fun forPosition(seat: Int): String {
        val book = playbook(seat)?.takeIf { it.entries.isNotEmpty() } ?: return ""
        return DuelGuide.playbook(book, DuelPosition.of(state, seat, seat, catalog))
    }

    /** The table as [seat] sees it, and nothing more: its own view, knowledge self, this turn's moves as it saw them. */
    fun brief(seat: Int): String {
        val g = game
        val head = "Ai vs Ai — you play ${DuelWords.seatLabel(g.state, seat)} with “${g.header.seats.getOrNull(seat)?.deckName.orEmpty().ifBlank { "your deck" }}”. " +
            "Turn ${g.state.turn} of at most ${rules.turnCap}."
        val body = DuelBrief.describe(
            g.state, seat, catalog, g.header.seed, seat,
            tally = DuelTally.of(g, catalog, seat),
            history = DuelBrief.turnLines(g, seat, catalog),
        )
        // Cued to resolve its own link, the seat has priority: the brief's general line would name the other.
        val top = g.state.chain.lastOrNull()
        val own = cueSeat == seat && cueKind == CueKind.RESOLVE && top?.seat == seat
        return head + "\n" + if (!own) body else body.lines().joinToString("\n") { line ->
            if (line.startsWith("Priority:")) "Priority: yours — ${DuelWords.seatName(g.state, 1 - seat)} passed on Chain Link ${g.state.chain.size}: chain to it, or resolve it" else line
        }
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
        val menu = DuelMoves.menu(s, seat, catalog, g.header.seed, only, allow = { lawful(s, seat, it) == null })
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
        val ops = ToolArgs.strings(input, "ops").flatMap(::split).map { it.trim() }.filter { it.isNotEmpty() }
        if (ops.isEmpty()) return "No ops to play." to true
        if (cueSeat != seat) return "It is not your cue: wait to be cued." to true
        val out = mutableListOf<String>()
        var refused = false
        // The card this call put on the chain: its targets and its words may still follow it before the other answers.
        var activated: Int? = null
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
            // "pass" with nothing to pass on is not the end of a turn (the command line reads it so): say so.
            if (lower in PASS && s.proposal == null) { out += "✗ $op: there is nothing to pass on now. To end your turn, `end`."; refused = true; break }
            if (s.window?.opener == seat) {
                // An activation is whole with its targets and which effect it is (the red team: the other decided on Ash
                // or Called by knowing neither): those, said in the same call, join it before the other is asked.
                val joined = activated?.let { attach(s, seat, op, it) }
                if (joined != null) { out += joined; continue@loop }
                out += "✗ $op: ${WAIT}"
                refused = true
                break
            }
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
                lawful(state, seat, actions)?.let { why -> out += "✗ $text: $why."; refused = true; break@loop }
                if (rules.strict && actions.any(MatchLaw::talk) && cueTalk >= MatchLaw.TALK_PER_CUE) {
                    out += "✗ $text: you have said enough this cue (${MatchLaw.TALK_PER_CUE} lines)."
                    refused = true
                    break@loop
                }
                if (rules.strict && actions.any(MatchLaw::chance) && cueRolls >= MatchLaw.ROLLS_PER_CUE) {
                    out += "✗ $text: an effect rolls once or twice, never until it suits (${MatchLaw.ROLLS_PER_CUE} a resolution)."
                    refused = true
                    break@loop
                }
                // `end` passes through the End Phase where the other may answer (Evenly Matched, a trap flipped at the end).
                val ending = rules.windows == Windows.FULL && actions == listOf(DuelAction.EndTurn) && state.phase != DuelPhase.END
                val made = if (ending) listOf(DuelAction.Phase(DuelPhase.END)) else actions
                // An effect being resolved is not answered half-way: what it summons or moves opens no window.
                val resolving = MatchLaw.resolving(state, seat, cueKind)
                fun windows(of: Int) = if (resolving) Windows.OFF else seatWindows?.invoke(of) ?: rules.windows
                val before = game.cursor
                val r = DuelHost.act(game, seat, made, windows = mapOf(0 to windows(0), 1 to windows(1)), at = now(), by = by(seat))
                if (!r.ok) { out += "✗ $text: ${r.problem}"; refused = true; break@loop }
                game = r.game
                onMove(game)
                val lines = DuelHost.lines(game, before, seat, catalog).map { it.text }
                out += "✓ $text → ${lines.joinToString("; ").ifBlank { "no change on the table" }}"
                if (actions.any { !it.social }) cueMoves++
                cueTalk += actions.count(MatchLaw::talk)
                cueRolls += actions.count(MatchLaw::chance)
                if (ending) {
                    out += "The End Phase: ${DuelWords.seatName(state, 1 - seat)} may answer. When you are cued again, `end` ends your turn."
                    break@loop
                }
                // The turn is over: nothing more is played in it (moves after `end` ran in the other's Draw Phase).
                if (made.any { it == DuelAction.EndTurn }) break@loop
                if (state.window?.opener == seat) {
                    activated = actions.filterIsInstance<DuelAction.ChainAdd>().lastOrNull()?.uid
                    if (activated != null) {
                        out += "A response window is open for ${DuelWords.seatName(state, 1 - seat)}. In this same call you may still name its targets " +
                            "(`t om2 with <card>`) and say which effect (`say …`); nothing else until they answer — then stop."
                        continue@loop
                    }
                    out += "A response window is open for ${DuelWords.seatName(state, 1 - seat)}. Stop here: you will be cued again once they respond or pass."
                    break@loop
                }
                if (DuelResults.ending(state) != null) break@loop
            }
        }
        out.firstOrNull { it.startsWith("✗") }?.let { onRefused(seat, it) }
        return (out.joinToString("\n") + "\n\nThe table now:\n" + brief(seat)) to refused
    }

    /**
     * [op] joined to the activation of [card] still waiting on the other seat's answer, when it is only that card's targets
     * or words: made at once, the window kept open; null when [op] is anything else (it waits, as every move does).
     */
    private suspend fun attach(s: DuelState, seat: Int, op: String, card: Int): String? {
        val planned = ComboRunner.plan(s, seat, listOf(op), catalog, game.header.seed)
        if (!planned.ok || planned.steps.isEmpty()) return null
        val plan = ComboRunner.redacted(s, seat, planned, catalog)
        val actions = plan.steps.flatMap { it.second }
        val joins = actions.all { a ->
            (a is DuelAction.Target && a.seat == seat && a.from == card) || a is DuelAction.Chat || a is DuelAction.Note
        }
        if (!joins || ComboRunner.reach(s, seat, plan) != null || lawful(s, seat, actions) != null) return null
        if (actions.any(MatchLaw::talk) && cueTalk >= MatchLaw.TALK_PER_CUE) return null
        val before = game.cursor
        val r = DuelHost.act(game, seat, actions, windows = mapOf(0 to Windows.OFF, 1 to Windows.OFF), force = true, at = now(), by = by(seat))
        if (!r.ok) return "✗ $op: ${r.problem}"
        // The window stays the one the activation opened.
        game = r.game.copy(state = r.game.state.copy(window = s.window))
        onMove(game)
        cueTalk += actions.count(MatchLaw::talk)
        return "✓ $op → " + DuelHost.lines(game, before, seat, catalog).joinToString("; ") { it.text }.ifBlank { "joined to its activation" }
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
        /**
         * One op string into ops on `;` — but a line of words (`say`, `note`, `lock`…) keeps its `;` (the red team: `say a;
         * m2` moved the phase): words are split off only where a new op could begin, never inside what a seat says.
         */
        fun split(line: String): List<String> {
            val head = line.trim().substringBefore(' ').lowercase()
            return if (head in WORDS) listOf(line) else line.split(';')
        }

        /** The ops whose rest is a seat's own words. */
        val WORDS = setOf("say", "chat", "note", "lock", "ruling", "rule")

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
