package com.kaiharimoto.mastertool.core.duel.record

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A finished duel as a result (Phase C, `docs/phases/C.md` §2): who sat where and who they were, the decks, who went first
 * and how that was decided, who won and how, how long it ran, and how the table was set — Ai's seat, its knowledge, its
 * peeks, the person's eyes, a networked table or a hot-seat. Read off the duel's log and its [Provenance], never typed.
 *
 * Kept one file a duel in `<data>/duel/records/<id>.json` ([DuelResultCodec.path]), so two devices never write the same
 * file: synced and backed up as replays are (everything under `duel/` but the duel in play). Every field has a default
 * and unknown keys are ignored, so an older build reads a newer record and the other way round.
 */
@Serializable
data class DuelResult(
    /** The record's own id: the duel's, or for a what-if the duel's and where it branched. */
    val id: String = "",
    /** The duel's header id. */
    val duel: String = "",
    /** When it ended and when it began (ms). */
    val ended: Long = 0L,
    val created: Long = 0L,
    val seats: List<ResultSeat> = emptyList(),
    /** The seat that had turn 1, and how that was decided: [ROLL] (the dice's winner chose) or [SET] (the table's first seat). */
    val first: Int = 0,
    val firstBy: String = SET,
    /** Each round of the opening roll, both seats' sums ([0, 0] for a seat that did not throw). */
    val rolls: List<List<Int>> = emptyList(),
    /** The seat that won the roll and chose, what it chose, and who made the choice (`Provenance.by`). */
    val rollWinner: Int? = null,
    val choseFirst: Boolean? = null,
    val chosenBy: String? = null,
    /** The seat that won; null when neither did (both at 0 together). */
    val winner: Int? = null,
    /** How it ended: [CONCEDE], [LP] or [DRAW]. */
    val how: String = LP,
    /** The turn it ended in. */
    val turns: Int = 0,
    /** Ai at the table, when it played a seat. */
    val ai: AiPlay? = null,
    /** A networked table (the host's record), or a hot-seat. */
    val net: Boolean = false,
    /** The person's eyes at a hot-seat (`DuelPrefs.knowledge`): [DuelPrefs.KNOW_ALL] saw both hands, [DuelPrefs.KNOW_SEAT] their own. */
    val eyes: String? = null,
    /** Played on from a replay ("what if"): not a game of its own, left out of the summary unless asked. */
    val whatIf: Boolean = false,
    val version: Int = VERSION,
) {
    /** The seats played by people (this device's person or the network's guest), and Ai's. */
    fun player(seat: Int): String = seats.getOrNull(seat)?.player ?: UNKNOWN

    companion object {
        const val VERSION = 1
        const val ROLL = "roll"
        const val SET = "set"
        const val CONCEDE = "concede"
        const val LP = "lp"
        const val DRAW = "draw"
        const val UNKNOWN = "unknown"
    }
}

@Serializable
data class ResultSeat(
    val name: String = "",
    val deckId: String? = null,
    val deckName: String = "",
    /** Who played it: `Provenance.PERSON`, `AI` or `GUEST` — whoever made most of its moves — or [DuelResult.UNKNOWN]. */
    val player: String = DuelResult.UNKNOWN,
    /** Its moves (talk left out), by who made them. */
    val moves: Map<String, Int> = emptyMap(),
)

@Serializable
data class AiPlay(
    val seat: Int = 1,
    /** Its knowledge setting across its moves (`DuelBrief`): one word, or several joined by "+" when it changed. */
    val knows: String = DuelBrief.SELF,
    /** Peeks it took (knowledge auto), each written in the log. */
    val peeks: Int = 0,
    /** Its own seat's moves. */
    val moves: Int = 0,
    /** Moves it made for the other seat (allowed only by "Ai may move both seats"). */
    val forOther: Int = 0,
    /** Moves the person made for Ai's seat. */
    val helped: Int = 0,
) {
    /** Each side played only its own seat: the result measures Ai, not a shared hand. */
    val clean: Boolean get() = forOther == 0 && helped == 0
}

/** The record file's shape and where it lives. */
object DuelResultCodec {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = false; prettyPrint = true }

    const val FOLDER = "records"

    fun encode(r: DuelResult): String = json.encodeToString(DuelResult.serializer(), r)

    /** Null when the text is not a record. */
    fun decode(text: String): DuelResult? = runCatching { json.decodeFromString(DuelResult.serializer(), text) }.getOrNull()?.takeIf { it.id.isNotBlank() }

    /** The file a record lives in, under `<data>/duel/`. */
    fun path(id: String): String = "$FOLDER/${id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "duel" }}.json"
}

object DuelResults {
    /** How the duel on [s] ended — the winner (null for neither) and how — or null while it goes on (or on one player's table). */
    fun ending(s: DuelState): Pair<Int?, String>? {
        if (s.solo) return null
        s.conceded?.let { return (1 - it) to DuelResult.CONCEDE }
        val down = s.seats.indices.filter { s.seats[it].lp <= 0 }
        return when (down.size) {
            0 -> null
            1 -> (1 - down.single()) to DuelResult.LP
            else -> null to DuelResult.DRAW
        }
    }

    /** The seat that had turn 1: the opening roll's choice, else the table's first seat. */
    fun firstSeat(header: DuelHeader, s: DuelState): Int = s.opening?.first ?: header.first

    /**
     * The duel as a result, or null while it goes on. [id] names the record (a what-if's carries where it branched);
     * [ended] is when.
     */
    fun of(game: DuelGame, ended: Long, id: String = game.header.id, whatIf: Boolean = false): DuelResult? {
        val s = game.state
        val (winner, how) = ending(s) ?: return null
        val played = game.played
        val moves = played.filter { it.seat != null && !DuelGame.isTalk(it.action) }
        // Each seat's moves by who made them (a move with no provenance — an older build's — by no one).
        fun bySeat(seat: Int) = moves.filter { it.seat == seat }.groupingBy { it.by?.by ?: DuelResult.UNKNOWN }.eachCount()
        val counts = (0..1).map(::bySeat)
        val players = counts.map { c ->
            c.filterKeys { it == Provenance.PERSON || it == Provenance.AI || it == Provenance.GUEST }.maxByOrNull { it.value }?.key ?: DuelResult.UNKNOWN
        }
        val seats = (0..1).map { i ->
            val h = game.header.seats.getOrNull(i)
            ResultSeat(h?.name.orEmpty(), h?.deckId, h?.deckName.orEmpty(), players[i], counts[i])
        }
        val aiSeat = players.indexOf(Provenance.AI).takeIf { it >= 0 }
        val ai = aiSeat?.let { seat ->
            val own = moves.filter { it.seat == seat && it.by?.byAi == true }
            AiPlay(
                seat = seat,
                knows = own.mapNotNull { it.by?.aiKnows }.distinct().joinToString("+").ifBlank { DuelBrief.SELF },
                peeks = played.count { it.by?.peek == true },
                moves = own.size,
                forOther = moves.count { it.seat != seat && it.by?.byAi == true },
                helped = moves.count { it.seat == seat && it.by?.by == Provenance.PERSON },
            )
        }
        val opening = s.opening
        val choice = played.lastOrNull { it.action is DuelAction.GoFirst }
        return DuelResult(
            id = id,
            duel = game.header.id,
            ended = ended,
            created = game.header.created,
            seats = seats,
            first = firstSeat(game.header, s),
            firstBy = if (opening?.decided == true) DuelResult.ROLL else DuelResult.SET,
            rolls = rounds(played),
            rollWinner = opening?.winner,
            choseFirst = (choice?.action as? DuelAction.GoFirst)?.first,
            chosenBy = choice?.by?.by,
            winner = winner,
            how = how,
            turns = s.turn,
            ai = ai,
            net = played.any { it.by?.net == true },
            eyes = played.lastOrNull { it.by?.by == Provenance.PERSON && it.by.eyes != null }?.by?.eyes,
            whatIf = whatIf,
        )
    }

    /** The opening roll's rounds as both seats' sums: a seat's second throw in a round (after a tie) starts the next. */
    fun rounds(played: List<DuelEntry>): List<List<Int>> {
        val out = mutableListOf<MutableList<Int?>>()
        played.forEach { e ->
            val a = e.action as? DuelAction.OpeningRoll ?: return@forEach
            if (a.seat !in 0..1) return@forEach
            val round = out.lastOrNull()?.takeIf { it[a.seat] == null } ?: mutableListOf<Int?>(null, null).also { out += it }
            round[a.seat] = a.values.sum()
        }
        return out.map { r -> r.map { it ?: 0 } }
    }

    /**
     * The game as Prep logs it, for the person at [me] (1.0.80's `logFinishedDuel`, Phase C): going first or second from
     * who really had turn 1 — the dice's winner's choice when the opening roll decided it (the red team: it was always
     * "seat 0 goes first") — and the result. Null while the duel goes on.
     */
    fun practice(game: DuelGame, me: Int, id: String, at: Long, opponent: String, opponentName: String, deckId: String?, note: String): TestGame? {
        val (winner, _) = ending(game.state) ?: return null
        return TestGame(
            id = id,
            at = at,
            deckId = deckId,
            opponent = opponent,
            opponentName = opponentName,
            turn = if (firstSeat(game.header, game.state) == me) TestGame.FIRST else TestGame.SECOND,
            result = when (winner) {
                null -> TestGame.DRAW
                me -> TestGame.WIN
                else -> TestGame.LOSS
            },
            note = note,
        )
    }

    // ---- the summary: "Ai won N of M against kai, with these settings" -----------------------------------------------

    /** How a table was set, as the summary groups results. */
    data class Settings(
        /** Ai's knowledge across the duel (`DuelBrief`; "self+full" when it changed). */
        val knows: String,
        /** The person's eyes: [DuelPrefs.KNOW_ALL] (they saw Ai's hand), [DuelPrefs.KNOW_SEAT], or null (networked or unknown). */
        val eyes: String?,
        val net: Boolean,
        /** Ai took at least one peek in the duel (auto knowledge). */
        val peeked: Boolean,
        /** Each side played only its own seat. */
        val clean: Boolean,
        /** The dice decided who went first. */
        val rolled: Boolean,
    )

    /** One person against Ai at one setting: how many Ai won, lost and drew. */
    data class Score(
        val person: String,
        val settings: Settings,
        val won: Int,
        val lost: Int,
        val drawn: Int,
        val peeks: Int,
        val wentFirst: Int,
    ) {
        val played: Int get() = won + lost + drawn
    }

    /**
     * Ai's results against people, grouped by the person (their seat's name) and by how the table was set — only duels
     * where one seat was Ai's and the other a person's (here or the network's guest). [person] keeps one person's
     * (matched without case); what-ifs only when [whatIfs]. Newest settings first is not promised: by person, then most
     * played.
     */
    fun aiAgainst(results: List<DuelResult>, person: String? = null, whatIfs: Boolean = false): List<Score> {
        val games = results.mapNotNull { r ->
            if (r.whatIf && !whatIfs) return@mapNotNull null
            val ai = r.ai ?: return@mapNotNull null
            val other = 1 - ai.seat
            val p = r.player(other)
            if (p != Provenance.PERSON && p != Provenance.GUEST) return@mapNotNull null
            val name = r.seats.getOrNull(other)?.name.orEmpty().ifBlank { "Player ${other + 1}" }
            if (person != null && !name.equals(person.trim(), ignoreCase = true)) return@mapNotNull null
            Triple(name, Settings(ai.knows, if (r.net) null else r.eyes, r.net, ai.peeks > 0, ai.clean, r.firstBy == DuelResult.ROLL), r)
        }
        return games.groupBy { it.first to it.second }.map { (key, rs) ->
            val ai = { r: DuelResult -> r.ai!!.seat }
            Score(
                person = key.first,
                settings = key.second,
                won = rs.count { it.third.winner == ai(it.third) },
                lost = rs.count { it.third.winner != null && it.third.winner != ai(it.third) },
                drawn = rs.count { it.third.winner == null },
                peeks = rs.sumOf { it.third.ai!!.peeks },
                wentFirst = rs.count { it.third.first == ai(it.third) },
            )
        }.sortedWith(compareBy<Score> { it.person.lowercase() }.thenByDescending { it.played })
    }

    /** "Ai won 3 of 5 against kai (1 drawn), with these settings: …" — [aiName] is what the person calls Ai. */
    fun words(score: Score, aiName: String = "Ai"): String {
        val s = score.settings
        val head = "$aiName won ${score.won} of ${score.played} against ${score.person}" +
            (if (score.drawn > 0) " (${score.drawn} drawn)" else "")
        val knows = s.knows.split('+').joinToString(" then ") { knowsWords(it) }
        val parts = listOfNotNull(
            "$aiName's knowledge $knows",
            if (s.knows.split('+').contains(DuelBrief.AUTO)) "${score.peeks} peek${if (score.peeks == 1) "" else "s"}, each in the log" else null,
            when {
                s.net -> "over the network, each seeing only their own hand"
                s.eyes == DuelPrefs.KNOW_ALL -> "${score.person} seeing both hands"
                s.eyes == DuelPrefs.KNOW_SEAT -> "${score.person} seeing only their own hand"
                else -> null
            },
            if (s.rolled) "the dice deciding who went first ($aiName first in ${score.wentFirst})" else "$aiName first in ${score.wentFirst}",
            if (!s.clean) "a seat moved by the other side at times" else null,
        )
        return "$head, with these settings: ${parts.joinToString("; ")}."
    }

    /** Every person and setting in words, one line each; [none] when there is nothing to count. */
    fun summary(results: List<DuelResult>, person: String? = null, aiName: String = "Ai", whatIfs: Boolean = false): String {
        val scores = aiAgainst(results, person, whatIfs)
        if (scores.isEmpty()) return if (person != null) "No finished duels between $aiName and $person yet." else "No finished duels against $aiName yet."
        return scores.joinToString("\n") { words(it, aiName) }
    }

    private fun knowsWords(k: String) = when (k) {
        DuelBrief.SELF -> "its own seat's eyes"
        DuelBrief.AUTO -> "auto (its own eyes, and peeks it logs)"
        DuelBrief.FULL -> "full (every card)"
        DuelBrief.OPPONENT -> "the other seat's eyes"
        else -> k
    }
}
