package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import kotlinx.serialization.json.JsonObject

/**
 * Ai at the duel table (1.0.76): the table read as one seat sees it, moves played out on it at a pace
 * the person can follow, a peek written in the log when knowledge is *auto*, the log, a new duel, and a
 * deck's combos. Everything goes through the same `Duels` the page's clicks use, so what Ai does is on
 * the table, in the log and in the undo as if the person had done it.
 */
internal class AiDuel(private val h: NeueHolders) {
    private val duels get() = h.duel
    private val prefs get() = h.neue.prefs.duel

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? {
        if (!name.startsWith("duel_")) return null
        duels.useIndex(h.builder.index)
        return when (name) {
            "duel_state" -> state(i)
            "duel_act" -> act(i)
            "duel_peek" -> peek(i)
            "duel_log" -> log(i)
            "duel_setup" -> setup(i)
            "duel_combo" -> combo(i)
            "duel_ruling" -> ruling(i)
            else -> null
        }
    }

    /** The seat Ai acts as: the one asked for, else the page's; on one player's table, the only one. */
    private fun seat(i: JsonObject): Int {
        val g = duels.game
        if (g?.state?.solo == true) return 0
        return (ToolArgs.int(i, "seat") ?: prefs.aiSeat).coerceIn(0, 1)
    }

    private fun perspective(i: JsonObject): String =
        ToolArgs.string(i, "perspective")?.takeIf { it in DuelBrief.PERSPECTIVES } ?: prefs.aiKnowledge

    /** The seat Ai plays: the page's, or seat 0 on one player's table. */
    private fun aiSeat(): Int = if (duels.game?.state?.solo == true) 0 else prefs.aiSeat

    private fun state(i: JsonObject): MetaAnswer {
        val g = duels.game ?: return fail("There is no duel on the table. duel_setup starts one.")
        val seat = seat(i)
        val p = perspective(i)
        val text = DuelBrief.describe(g.state, DuelBrief.viewer(p, seat), duels.catalog, g.header.seed, seat, duels.tally(), duels.rulings) +
            if (p == DuelBrief.AUTO) "\n\nKnowledge: auto — duel_peek if a hidden card would change your play; every peek is logged." else ""
        return ok(text, "Read the duel table")
    }

    private suspend fun act(i: JsonObject): MetaAnswer {
        val ops = ToolArgs.strings(i, "ops").filter { it.isNotBlank() }
        if (ops.isEmpty()) return fail("No ops to play.")
        if (duels.game == null) return fail("There is no duel on the table. duel_setup starts one.")
        if (duels.replay != null) return fail("A replay is open on the table; the person must close it first.")
        val seat = seat(i)
        val mine = aiSeat()
        // Ai plays its own seat (1.0.79, its own feedback: "I acted as your seat … and the result came back from seat 0's view").
        if (seat != mine && !prefs.aiBothSeats) {
            val s = duels.game!!.state
            return fail("You play ${DuelWords.seatLabel(s, mine)}. ${DuelWords.seatLabel(s, seat)} is the person's: ask them to make that move, " +
                "or to let you move both seats (Ai · Combos › Ai may move both seats). To move the phase on their turn, play the phase op as your own seat: it asks them.")
        }
        h.neue.go(Page.DUEL)
        // A move in a phase gone by (1.0.80): put into the log where it belongs, everything after folded on top.
        ToolArgs.string(i, "at")?.takeIf { it.isNotBlank() }?.let { at -> return past(at, ops, seat) }
        val pace = (ToolArgs.int(i, "pace_ms") ?: prefs.aiPace).toLong().coerceIn(0, 5000)
        // What Ai is told comes through its knowledge setting, always as its own seat — never the acting seat's eyes.
        val viewer = DuelBrief.viewer(prefs.aiKnowledge, mine)
        val report = duels.playOut(ops, seat, pace, viewer)
        val g = duels.game!!
        val after = DuelBrief.describe(g.state, viewer, duels.catalog, g.header.seed, mine, duels.tally(), duels.rulings)
        return MetaAnswer("${report.text}\n\nThe table now:\n$after", if (!report.played) "Could not play that" else "Played ${report.done} of ${ops.size} moves", isError = !report.played)
    }

    private fun past(at: String, ops: List<String>, seat: Int): MetaAnswer {
        val g = duels.game!!
        val (turn, phase) = com.kaiharimoto.mastertool.core.duel.replay.Past.parse(at) ?: return fail("“$at” is not a turn and phase: say it like 't2 ep'.")
        val index = com.kaiharimoto.mastertool.core.duel.replay.Past.indexOf(g.header, g.played, turn, phase)
            ?: return fail("Turn $turn's ${phase.label} Phase is now or still to come: play the moves without 'at'.")
        val before = g.stateAt(index)
        val plan = ComboRunner.plan(before, seat, ops, duels.catalog)
        if (!plan.ok) return fail("Nothing was put in. ${plan.problem}")
        if (!duels.insertPast(index, plan.steps.flatMap { it.second }, seat)) return fail("Nothing was put in: ${duels.problem ?: "the table refused it"}.")
        val struck = duels.game!!.let { now -> com.kaiharimoto.mastertool.core.duel.DuelSetup.fold(now.header, now.played).second.size }
        val viewer = DuelBrief.viewer(prefs.aiKnowledge, aiSeat())
        val after = DuelBrief.describe(duels.game!!.state, viewer, duels.catalog, g.header.seed, aiSeat(), duels.tally(), duels.rulings)
        return ok(
            "Put ${ops.size} move(s) into turn $turn's ${phase.label} Phase: ${ops.joinToString("; ")}." +
                (if (struck > 0) " $struck later move(s) no longer fit and are struck through in the log." else "") + "\n\nThe table now:\n$after",
            "Played into turn $turn's ${phase.label} Phase",
        )
    }

    private fun peek(i: JsonObject): MetaAnswer {
        if (prefs.aiKnowledge != DuelBrief.AUTO) return fail("Knowledge is ${prefs.aiKnowledge}, not auto: a peek is not yours to take. Play with what your seat can see.")
        val g = duels.game ?: return fail("There is no duel on the table.")
        val seat = seat(i)
        val them = 1 - seat
        val reason = ToolArgs.string(i, "reason")?.trim().orEmpty().ifBlank { return fail("Say why you need to look.") }
        val count = (ToolArgs.int(i, "count") ?: 1).coerceIn(1, 10)
        val s = g.state
        fun names(uids: List<Int>) = uids.joinToString(", ") { "#$it ${duels.catalog.nameOf(s.cards.getValue(it))}" }.ifBlank { "nothing" }
        val (what, seen) = when (ToolArgs.string(i, "what")) {
            "their_hand" -> "${DuelWords.possessive(DuelWords.seatName(s, them))} hand" to names(s.seats[them].hand)
            "their_set" -> "${DuelWords.possessive(DuelWords.seatName(s, them))} set cards" to names(s.onField().filter { s.cards[it]?.controller == them && s.cards[it]?.faceUp == false })
            "their_deck_top" -> "the top $count of ${DuelWords.possessive(DuelWords.seatName(s, them))} deck" to names(s.seats[them].deck.take(count))
            "my_deck_top" -> "the top $count of its own deck" to names(s.seats[seat].deck.take(count))
            else -> return fail("Look at their_hand, their_set, their_deck_top or my_deck_top.")
        }
        duels.act(DuelAction.Note("${h.ai.name} looked at $what: $reason", seat), seat)
        return ok("You see $what: $seen. (Written in the log: “${h.ai.name} looked at $what: $reason”.)", "Peeked at $what")
    }

    private fun log(i: JsonObject): MetaAnswer {
        val g = duels.game ?: return fail("There is no duel on the table.")
        val seat = seat(i)
        val viewer = DuelBrief.viewer(perspective(i), seat)
        val count = (ToolArgs.int(i, "count") ?: 40).coerceIn(1, 400)
        var s = DuelSetup.initial(g.header)
        val lines = mutableListOf<String>()
        g.played.forEach { e ->
            val after = (DuelRules.apply(s, e.action, e.seat) as? Outcome.Ok)?.state ?: s
            lines += "${e.i}. ${DuelWords.say(s, after, e, viewer, duels.catalog)}"
            s = after
        }
        return ok(lines.takeLast(count).joinToString("\n").ifBlank { "Nothing has happened yet." }, "Read the duel's log")
    }

    private suspend fun setup(i: JsonObject): MetaAnswer {
        val b = h.builder
        suspend fun deck(id: String?): Triple<String?, String, com.kaiharimoto.mastertool.core.model.Deck>? =
            if (id == null) Triple(b.deckId, b.deckName, b.deck)
            else h.deps.deckRepository.byId(id)?.let { Triple(it.entry.id, it.entry.name, it.entry.deck) }
        val mine = deck(ToolArgs.string(i, "deck_id")) ?: return fail("No deck ${ToolArgs.string(i, "deck_id")}.")
        if (mine.third.main.isEmpty()) return fail("${mine.second} has no Main Deck to draw from.")
        val solo = ToolArgs.bool(i, "solo") == true
        val theirs = if (solo) null else deck(ToolArgs.string(i, "opponent_deck_id") ?: mine.first) ?: return fail("No deck ${ToolArgs.string(i, "opponent_deck_id")}.")
        // The seats by name: Ai's own at its seat, the person's at the other (1.0.79: never "You", which read as "You's turn").
        val names = com.kaiharimoto.neue.duel.seatNames(h, aiAtSeat = if (solo) null else prefs.aiSeat)
        duels.start(
            DuelHeader(
                id = "d${System.currentTimeMillis()}",
                seed = System.nanoTime(),
                seats = listOf(
                    SeatSetup(names[0], mine.third.main.map { it.value }, mine.third.extra.map { it.value }, mine.first, mine.second),
                    if (theirs == null) SeatSetup(names[1])
                    else SeatSetup(names[1], theirs.third.main.map { it.value }, theirs.third.extra.map { it.value }, theirs.first, theirs.second),
                ),
                solo = solo,
                created = System.currentTimeMillis(),
            ),
        )
        h.neue.go(Page.DUEL)
        return ok("A new duel: ${mine.second}${theirs?.let { " against ${it.second}" } ?: ", one player's table"}. Both shuffled, five drawn. duel_state to read it.", "Started a duel")
    }

    private suspend fun combo(i: JsonObject): MetaAnswer {
        val g = duels.game
        val seat = seat(i)
        val deckId = ToolArgs.string(i, "deck_id") ?: g?.header?.seats?.getOrNull(if (g.state.solo) 0 else seat)?.deckId ?: h.builder.deckId
            ?: return fail("Which deck? Give deck_id — the deck must be saved in the library.")
        val book = duels.combos(deckId)
        return when (ToolArgs.string(i, "action")) {
            "list" -> ok(
                if (book.combos.isEmpty()) "No combos kept for this deck yet."
                else book.combos.joinToString("\n") { c -> "- ${c.name} (id ${c.id}): needs ${c.needs.joinToString().ifBlank { "nothing" }}; ${c.steps.size} steps${if (c.notes.isNotBlank()) " — ${c.notes}" else ""}" },
                "Listed ${book.combos.size} combos",
            )
            "get" -> {
                val c = book.combos.firstOrNull { it.id == ToolArgs.string(i, "combo_id") } ?: return fail("No combo ${ToolArgs.string(i, "combo_id")}.")
                ok("${c.name}\nNeeds: ${c.needs.joinToString()}\nSteps:\n" + c.steps.mapIndexed { k, st -> "${k + 1}. $st" }.joinToString("\n") + "\n${c.notes}", "Read ${c.name}")
            }
            "save" -> {
                val steps = ToolArgs.strings(i, "steps").filter { it.isNotBlank() }
                if (steps.isEmpty()) return fail("A combo needs steps.")
                if (steps.any { Regex("#\\d+").containsMatchIn(it) }) return fail("Name cards in a combo's steps, not #uids: uids change with every shuffle.")
                val id = ToolArgs.string(i, "combo_id") ?: "c${System.currentTimeMillis()}"
                val c = Combo(id, ToolArgs.string(i, "name") ?: "Combo", deckId, ToolArgs.strings(i, "needs"), steps, ToolArgs.string(i, "notes").orEmpty(), System.currentTimeMillis())
                duels.saveCombos(deckId, book.copy(combos = book.combos.filterNot { it.id == id } + c))
                ok("Kept “${c.name}” (id $id) with the deck.", "Kept a combo")
            }
            "record" -> {
                g ?: return fail("There is no duel to record from.")
                val from = (ToolArgs.int(i, "from_entry") ?: g.floor).coerceIn(0, g.cursor)
                val to = (ToolArgs.int(i, "to_entry") ?: g.cursor).coerceIn(from, g.cursor)
                val start = g.stateAt(from)
                val span = g.entries.subList(from, to).filter { it.seat == seat || it.seat == null }
                val steps = ComboRecorder.steps(start, span, duels.catalog)
                if (steps.isEmpty()) return fail("Nothing in entries $from–$to to record.")
                val c = Combo("c${System.currentTimeMillis()}", ToolArgs.string(i, "name") ?: "Recorded line", deckId, ComboRecorder.needs(start, seat, span, duels.catalog), steps, ToolArgs.string(i, "notes").orEmpty(), System.currentTimeMillis())
                duels.saveCombos(deckId, book.copy(combos = book.combos + c))
                ok("Recorded “${c.name}” (id ${c.id}): needs ${c.needs.joinToString()}; ${steps.size} steps:\n" + steps.joinToString("\n"), "Recorded a combo")
            }
            "run" -> {
                g ?: return fail("There is no duel on the table.")
                val c = book.combos.firstOrNull { it.id == ToolArgs.string(i, "combo_id") } ?: return fail("No combo ${ToolArgs.string(i, "combo_id")}.")
                val missing = ComboRunner.missing(g.state, seat, c, duels.catalog)
                if (missing.isNotEmpty()) return fail("${c.name} needs ${missing.joinToString()} in hand, which the hand does not hold.")
                h.neue.go(Page.DUEL)
                val said = duels.playOut(c.steps, seat, (ToolArgs.int(i, "pace_ms") ?: prefs.aiPace).toLong())
                ok("${c.name}: ${said.text}", "Ran ${c.name}")
            }
            else -> fail("action is list, get, save, record or run.")
        }
    }

    private suspend fun ruling(i: JsonObject): MetaAnswer = when (ToolArgs.string(i, "action")) {
        "list" -> ok(
            duels.rulings.rulings.joinToString("\n") { r -> "- ${r.id}: ${r.card?.let { "$it: " } ?: ""}${r.text}" }.ifBlank { "No house rulings kept yet." },
            "Listed house rulings",
        )
        "save" -> {
            val text = ToolArgs.string(i, "text")?.trim().orEmpty().ifBlank { return fail("Say the ruling in text.") }
            val q = ToolArgs.string(i, "card")?.trim().orEmpty()
            val s = duels.game?.state
            val found = if (q.isBlank() || s == null) null
            else (com.kaiharimoto.mastertool.core.duel.text.DuelCommand.lookup(q, s, aiSeat(), duels.catalog, everywhere = true) as? com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Lookup.One)?.uid?.let { s.cards[it] }
            val r = duels.keepRuling(found?.code?.takeIf { it != 0 }, found?.let { duels.catalog.nameOf(it) } ?: q.ifBlank { null }, text)
            ok("Kept house ruling ${r.id}: ${r.card?.let { "$it: " } ?: ""}${r.text}", "Kept a house ruling")
        }
        "delete" -> {
            val id = ToolArgs.string(i, "id") ?: return fail("Which ruling? Give its id (list shows them).")
            if (duels.forgetRuling(id)) ok("Forgot house ruling $id.", "Forgot a house ruling") else fail("No house ruling $id.")
        }
        else -> fail("action is list, save or delete.")
    }
}
