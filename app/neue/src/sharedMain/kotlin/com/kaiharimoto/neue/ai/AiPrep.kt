package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prep.Countdown
import com.kaiharimoto.mastertool.core.prep.Drill
import com.kaiharimoto.mastertool.core.prep.EventCheck
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.Policy
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import kotlinx.serialization.json.JsonObject

/**
 * Tournament prep for Ai (1.0.50): the Prep page's state, its event, its test games, its
 * numbers and its drills, on the same [com.kaiharimoto.neue.prep.Prep] the page reads, so a
 * game Ai logs is on the page at once. The `tournament-prep` skill is how they are used.
 */
internal class AiPrep(private val h: NeueHolders) {
    private val prep get() = h.prep
    private val webs get() = h.webs
    private val state get() = h.builder

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "prep_state" -> prepState(ToolArgs.string(i, "event_id"))
        "set_event" -> setEvent(i)
        "log_game" -> logGame(i)
        "matchup_matrix" -> matrix(ToolArgs.string(i, "deck_id"))
        "expected_winrate" -> expected(ToolArgs.string(i, "event_id"))
        "drill" -> drill(i)
        else -> null
    }

    private fun event(id: String?): PrepEvent? = if (id == null) prep.active else prep.doc.event(id)

    private suspend fun deck(id: String?): StoredDeck? = id?.let { webs.stored(it) }

    private fun day(e: PrepEvent) = buildString {
        append("${e.name} (id ${e.id}): ${e.date}")
        IsoDate.daysBetween(prep.today(), e.date)?.let { append(", ${IsoDate.words(it)}") }
        append(", Tier ${e.tier}")
        if (e.attendance > 0) append(", ${e.attendance} players")
        append(", decklist ${e.decklist.lowercase()}")
        e.deadline?.let { append(" due $it") }
    }

    private suspend fun prepState(eventId: String?): MetaAnswer {
        val doc = prep.doc
        if (doc.events.isEmpty()) return ok("No events yet. Make one with set_event.", "Prep: no events")
        val e = event(eventId) ?: return fail("No event $eventId.")
        val web = webs.library.byId(e.webId)
        val mine = deck(e.deckId)
        val text = buildString {
            appendLine("Events:")
            doc.events.forEach { appendLine("- ${day(it)}${if (it.id == e.id) " ← being prepared for" else ""}") }
            appendLine()
            appendLine("Field: ${web?.let { "${it.name} (id ${it.id})" } ?: "no web linked"}")
            appendLine("Deck: ${mine?.let { "${it.entry.name} (id ${it.entry.id})" } ?: "none chosen"}")
            if (e.checkIn != null) appendLine("Check-in: ${e.checkIn}")
            if (e.notes.isNotBlank()) appendLine("Notes: ${e.notes}")
            appendLine()
            appendLine("Countdown:")
            Countdown.milestones(e, prep.today()).forEach { m -> appendLine("- ${m.label}: ${m.date} (${m.days?.let(IsoDate::words) ?: "?"})") }
            if (e.attendance > 0) {
                val swiss = Policy.swiss(e.tier, e.attendance)
                appendLine()
                appendLine("Policy: ${swiss.rounds} rounds of Swiss${if (swiss.twoDays) " (${swiss.day1} + ${swiss.day2})" else ""}${if (swiss.topCut > 0) ", Top ${swiss.topCut}" else ""}. ${Policy.cutRecord(swiss, e.attendance)}.")
            }
            if (mine != null) {
                val d = webs.deckOf(mine, state)
                appendLine()
                appendLine("Ready to register:")
                EventCheck.check(d, DeckValidator.validate(d, state.index::byId, state.format), webs.sidingOf(mine, state), e.tier).forEach { item ->
                    appendLine("- ${if (!item.ok) "✕" else if (item.warning) "·" else "✓"} ${item.title}${if (item.detail.isNotBlank()) ": " + item.detail.replace("\n", "; ") else ""}")
                }
            }
            val games = doc.games.filter { it.round == null && (mine == null || it.deckId == mine.entry.id) }
            appendLine()
            appendLine("Practice: ${games.size} games, ${games.count { it.result == TestGame.WIN }}-${games.count { it.result == TestGame.LOSS }}-${games.count { it.result == TestGame.DRAW }}.")
            val rounds = doc.games.filter { it.eventId == e.id && it.round != null }
            if (rounds.isNotEmpty()) appendLine("At the event: " + rounds.sortedBy { it.round }.joinToString(", ") { "R${it.round} ${it.result} vs ${it.opponentName}" })
            if (doc.drills.isNotEmpty()) appendLine("Drills: ${doc.drills.size} plans drilled, ${doc.drills.values.count { it.box >= Drill.TOP_BOX }} known.")
        }
        return ok(text.trim(), "Read the Prep page")
    }

    private suspend fun setEvent(i: JsonObject): MetaAnswer {
        val id = ToolArgs.string(i, "event_id")
        val base = if (id != null) prep.doc.event(id) ?: return fail("No event $id.") else PrepEvent(prep.newId("ev"), "New event", IsoDate.of((IsoDate.epochDay(prep.today()) ?: 0) + 14))
        val date = ToolArgs.string(i, "date")
        if (date != null && IsoDate.epochDay(date) == null) return fail("The date must be yyyy-mm-dd, not “$date”.")
        val webId = ToolArgs.string(i, "web_id")
        if (webId != null && webs.library.byId(webId) == null) return fail("No web $webId.")
        val deckId = ToolArgs.string(i, "deck_id")
        if (deckId != null && deck(deckId) == null) return fail("No deck $deckId.")
        val e = base.copy(
            name = ToolArgs.string(i, "name") ?: base.name,
            date = date ?: base.date,
            tier = ToolArgs.int(i, "tier")?.coerceIn(1, 4) ?: base.tier,
            attendance = ToolArgs.int(i, "players") ?: base.attendance,
            webId = webId ?: base.webId,
            deckId = deckId ?: base.deckId ?: webId?.let { w -> webs.library.byId(w)?.entries?.firstOrNull { it.mine }?.deckId },
            decklist = ToolArgs.string(i, "decklist")?.uppercase()?.takeIf { it in setOf(PrepEvent.DECKLIST_PAPER, PrepEvent.DECKLIST_NEURON, PrepEvent.DECKLIST_ONLINE) } ?: base.decklist,
            deadline = ToolArgs.string(i, "deadline") ?: base.deadline,
            checkIn = ToolArgs.string(i, "check_in") ?: base.checkIn,
            notes = ToolArgs.string(i, "notes") ?: base.notes,
        )
        prep.putEvent(e)
        h.neue.go(Page.PREP)
        return ok("Saved: ${day(e)}.", if (id == null) "Made the event ${e.name}" else "Changed the event ${e.name}")
    }

    private suspend fun logGame(i: JsonObject): MetaAnswer {
        val deckId = ToolArgs.string(i, "deck_id") ?: prep.active?.deckId
        val against = ToolArgs.string(i, "against")!!.trim()
        // A web deck by id, or by its name in the event's web; else the name as given.
        val web = webs.library.byId(prep.active?.webId)
        val byId = deck(against)?.takeIf { web == null || web.has(it.entry.id) }
        val byName = if (byId == null && web != null) web.deckIds.firstNotNullOfOrNull { id -> deck(id)?.takeIf { it.entry.name.equals(against, ignoreCase = true) } } else null
        val foe = byId ?: byName
        val result = when (ToolArgs.string(i, "result")) {
            "win" -> TestGame.WIN
            "loss" -> TestGame.LOSS
            else -> TestGame.DRAW
        }
        val g = TestGame(
            id = prep.newId("g"),
            at = System.currentTimeMillis(),
            deckId = deckId,
            opponent = foe?.entry?.id ?: against,
            opponentName = foe?.entry?.name ?: against,
            turn = if (ToolArgs.string(i, "turn") == "second") TestGame.SECOND else TestGame.FIRST,
            game = ToolArgs.int(i, "game")?.coerceIn(1, 3) ?: 1,
            result = result,
            reason = ToolArgs.string(i, "reason")?.uppercase(),
            minutes = ToolArgs.int(i, "minutes"),
            note = ToolArgs.string(i, "note").orEmpty(),
        )
        prep.log(g)
        val n = prep.doc.games.count { it.opponent == g.opponent && it.deckId == deckId && it.round == null }
        return ok(
            "Logged game ${g.game} against ${g.opponentName}, going ${if (g.turn == TestGame.FIRST) "first" else "second"}: ${g.result}. $n games against them so far.",
            "Logged ${g.result} vs ${g.opponentName}",
        )
    }

    private suspend fun matrix(deckId: String?): MetaAnswer {
        val id = deckId ?: prep.active?.deckId
        val games = prep.doc.games.filter { it.round == null && (id == null || it.deckId == id) }
        if (games.isEmpty()) return ok("No games logged${id?.let { " with deck $it" } ?: ""} yet.", "Matchup table: empty")
        val rows = TestStats.matrix(games)
        val risk = TestStats.timeRisk(rows).toSet()
        fun r(x: TestStats.Rate) = if (x.games == 0) "--" else "${(x.pct * 100).toInt()}% (${x.wins}/${x.games})"
        val text = buildString {
            appendLine("| Against | First | Second | Game 1 | Games 2–3 | All | Minutes |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|")
            rows.forEach { row ->
                appendLine("| ${row.name}${if (row.opponent in risk) " (time risk)" else ""} | ${r(row.first)} | ${r(row.second)} | ${r(row.preSide)} | ${r(row.postSide)} | ${r(row.all)} | ${row.avgMinutes?.toInt() ?: "--"} |")
            }
            if (risk.isNotEmpty()) appendLine("\nTime risk: three games of these run past ${Policy.ROUND_MINUTES} minutes; an unfinished match is a loss for both.")
        }
        return ok(text.trim(), "Read the matchup table")
    }

    private suspend fun expected(eventId: String?): MetaAnswer {
        val e = event(eventId) ?: return fail("No event to expect a result at. Make one with set_event.")
        val web = webs.library.byId(e.webId) ?: return fail("${e.name} has no web of the field linked: set_event with web_id.")
        val shares = web.entries.filter { it.deckId != e.deckId }.mapNotNull { en -> en.share?.let { en.deckId to it } }.toMap()
        if (shares.isEmpty()) return fail("The web ${web.name} has no shares: give its decks their share of the field (set_web_entry).")
        val games = prep.doc.games.filter { it.round == null && (e.deckId == null || it.deckId == e.deckId) }
        val rows = TestStats.matrix(games)
        val total = TestStats.expected(rows, shares)
        val text = buildString {
            appendLine("Expected match win at ${e.name}: ${(total * 1000).toInt() / 10.0}%.")
            appendLine("Per opponent (share · games · best-of-three win):")
            shares.entries.sortedByDescending { it.value }.forEach { (id, share) ->
                val row = rows.firstOrNull { it.opponent == id }
                val name = row?.name ?: deck(id)?.entry?.name ?: id
                val one = TestStats.expected(rows, mapOf(id to 1))
                appendLine("- $name: $share% · ${row?.all?.games ?: 0} games · ${(one * 1000).toInt() / 10.0}%")
            }
            appendLine("Few games are pulled toward 50%: log more against the big shares to firm these up.")
            append(
                "The rate is only as good as the web's shares: if they were taken from ygopro_field_snapshot they are shares of " +
                    "top cuts, which over-represent strong decks — say so, or ask the person what the event's field looks like.",
            )
        }
        return ok(text, "Expected match win ${(total * 100).toInt()}%")
    }

    private suspend fun drill(i: JsonObject): MetaAnswer {
        val e = prep.active
        val mine = deck(e?.deckId) ?: return fail("Choose the event's deck first (set_event with deck_id).")
        val siding = webs.sidingOf(mine, state)
        val plans = siding.matchups.flatMap { m -> Turn.entries.map { t -> Triple(Drill.key(m.id, t.name), m, t) } }.filter { it.second.plan(it.third).sided }
        if (plans.isEmpty()) return fail("${mine.entry.name} has no siding plans to drill yet.")
        return when (ToolArgs.string(i, "action")) {
            "next" -> {
                val key = Drill.next(plans.map { it.first }, prep.doc.drills, System.currentTimeMillis())!!
                val (_, m, t) = plans.first { it.first == key }
                val p = m.plan(t)
                ok(
                    "Drill $key: against ${m.name}, ${t.title.lowercase()}. The plan moves ${p.out.size} out and ${p.into.size} in. " +
                        "Ask the person what they side out and in, without showing the plan, then call drill with action answer, this key and their words.",
                    "Drill: ${m.name}, ${t.title.lowercase()}",
                )
            }
            else -> {
                val key = ToolArgs.string(i, "key") ?: return fail("answer needs the key from next.")
                val (_, m, t) = plans.firstOrNull { it.first == key } ?: return fail("No drill $key.")
                val p = m.plan(t)
                val deck = webs.deckOf(mine, state)
                fun ids(words: List<String>): List<Int> = words.flatMap { w ->
                    when (val r = CardWords.resolve(w, state.index)) {
                        is Resolved.Found -> List(r.count.coerceIn(1, 3)) { r.card.id.value }
                        is Resolved.Unknown -> emptyList()
                    }
                }
                val score = Drill.score(p.out.map { it.value }, p.into.map { it.value }, ids(ToolArgs.strings(i, "out")), ids(ToolArgs.strings(i, "in")))
                prep.drilled(key, score)
                fun names(list: List<CardId>) = SidingMath.counted(list).joinToString(", ") { (id, n) -> "$n× ${state.index.byId(id)?.name ?: id.value}" }.ifEmpty { "nothing" }
                ok(
                    buildString {
                        appendLine(if (score.perfect) "Exactly the plan." else "${(score.fraction * 100).toInt()}% right: ${score.outRight} out and ${score.inRight} in right; ${score.outMissed} out missed, ${score.outWrong} out wrong, ${score.inMissed} in missed, ${score.inWrong} in wrong.")
                        appendLine("The plan against ${m.name}, ${t.title.lowercase()}: out ${names(p.out)}; in ${names(p.into)}.")
                        if (p.note.isNotBlank()) appendLine("Why: ${p.note}")
                        if (SidingMath.stale(deck, p).isNotEmpty()) append("The plan names cards the deck no longer holds: fix it on Siding.")
                    }.trim(),
                    if (score.perfect) "Drill: perfect" else "Drill: ${(score.fraction * 100).toInt()}%",
                )
            }
        }
    }
}
