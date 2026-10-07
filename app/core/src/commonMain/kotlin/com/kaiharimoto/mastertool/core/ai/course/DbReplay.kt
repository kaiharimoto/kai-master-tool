package com.kaiharimoto.mastertool.core.ai.course

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A DuelingBook replay a course links to (kai, 2026-10: "the guide has 60+ DuelingBook replays … can the Ai parse
 * them and learn from them"). DuelingBook's replay page receives the whole duel as one JSON document — after its own
 * bot check, so the app never asks for it: the study's browser opens the page as the person would and the app reads
 * what the page received ([DbReplays.DATA]). This reads that document into games, turns and actions, in words.
 *
 * The document is DuelingBook's and unpublished, so it is read forgivingly: `player1`/`player2` and their `username`;
 * `plays`, each with its `play` (what happened: "Normal Summon", "Enter M1", "Duel message"…), its `username`, its
 * `seconds`, a `log` of `public_log`/`private_log` words, a `card` or `cards` with `name`s, a chat's `message`; and the
 * duel `logs`, read instead when the plays carry no words. Whatever else it holds is kept in the raw file, so a later
 * build reads it again without loading the page.
 */
data class DbReplay(
    val players: List<String>,
    val format: String = "",
    val games: List<Game>,
) {
    data class Game(val n: Int, val turns: List<Turn>, val first: String? = null, val loser: String? = null) {
        val actions: Int get() = turns.sumOf { it.actions.size }
    }

    /** A turn of [player]'s; turn 0 holds what came before the first (the coin, the choice to go first, opening talk). */
    data class Turn(val n: Int, val player: String, val actions: List<Action>)

    data class Action(
        val play: String,
        val player: String,
        val words: String,
        val cards: List<String> = emptyList(),
        val seconds: Double = -1.0,
        /** Something a player said, not something they did. */
        val chat: Boolean = false,
        val phase: String = "",
        /**
         * What the other player saw of it: the log's public words (a chat is public). [words] prefer the private ones, which
         * name cards only their owner saw; the exam shows the author the other player's moves in these alone.
         */
        val public: String = words,
    )

    val actions: Int get() = games.sumOf { it.actions }
    val talk: Int get() = games.sumOf { g -> g.turns.sumOf { t -> t.actions.count { it.chat } } }

    /** The other of the two players. */
    fun opponentOf(player: String): String? = players.firstOrNull { it != player }
}

object DbReplays {
    /** The site the replays are on; a replay page is `https://www.duelingbook.com/replay?id=…`. */
    const val HOST = "duelingbook.com"

    /** The part of the address the page asks its replay from: the response the app reads. */
    const val DATA = "view-replay"

    private val ID = Regex("""[?&]id=([0-9]+(?:-[0-9]+)?)""")
    private val IN_TEXT = Regex("""https?://(?:www\.)?duelingbook\.com/replay\?[^\s)"'<>\]]+""", RegexOption.IGNORE_CASE)

    /** Whether [url] is a DuelingBook replay page. */
    fun isReplay(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true) && !u.startsWith("http://", ignoreCase = true)) return false
        val host = BrowseGuard.host(u).removePrefix("www.")
        val path = u.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#').trimEnd('/')
        return host == HOST && path.equals("replay", ignoreCase = true) && ID.containsMatchIn(u)
    }

    /** The replay's id ("123-4567" or "4567"), or null. */
    fun id(url: String): String? = if (isReplay(url)) ID.find(url)?.groupValues?.get(1) else null

    /** [url] as the one address a replay is opened at: https, www, its id, and the game or match it names. */
    fun normal(url: String): String? {
        val id = id(url) ?: return null
        val keep = listOf("game", "match").mapNotNull { k ->
            Regex("""[?&]$k=([0-9]+)""").find(url)?.groupValues?.get(1)?.let { "&$k=$it" }
        }.joinToString("")
        return "https://www.duelingbook.com/replay?id=$id$keep"
    }

    /** Every replay [links] (words and address) and [text] point to, each once, in order. */
    fun found(links: List<Pair<String, String>>, text: String = ""): List<String> =
        (links.map { it.second } + IN_TEXT.findAll(text).map { it.value }).mapNotNull(::normal).distinct()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val CHAT = setOf("duel message", "message", "watcher message")
    private val NOISE = setOf(
        "add watcher", "remove watcher", "countdown", "swap cards", "thinking", "permission event", "good", "stop good", "rps",
        "call admin", "cancel call", "left duel", "rejoin duel", "resume game", "show deck",
    )
    private val NEW_GAME = setOf("begin next duel", "back to rps")
    private val LOSS = setOf("game loss", "match loss", "loss", "admit defeat", "quit duel")
    private val QUOTED = Regex(""""([^"\n]{2,80})"""")
    private val DRAW_PHASE = Regex("""\b(?:enter(?:ed)?\s+(?:the\s+)?(?:dp|draw\s+phase))\b""", RegexOption.IGNORE_CASE)

    /** What DuelingBook said instead of a replay ("Replay does not exist"), or null when [raw] is not its error. */
    fun error(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        return if (root.str("action").equals("Error", ignoreCase = true)) root.str("message").ifBlank { "DuelingBook said no." } else null
    }

    /** The replay [raw] holds, or null when it is not one (an error, a page, a list of replays). */
    fun parse(raw: String): DbReplay? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        if (root.str("action").equals("Error", ignoreCase = true)) return null
        val players = listOf("player1", "player2", "player3", "player4").mapNotNull { k -> (root[k] as? JsonObject)?.str("username")?.takeIf { it.isNotBlank() } }
        val plays = (root["plays"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        var actions = plays.mapNotNull(::action)
        // Plays that carry no words of their own: the duel's log is read instead.
        if (actions.none { it.words.isNotBlank() && !it.chat }) {
            val logged = logEntries(root["logs"]).mapNotNull(::action)
            if (logged.isNotEmpty()) actions = logged + actions.filter { it.chat }.let { chat -> if (logged.any { it.chat }) emptyList() else chat }
        }
        if (actions.isEmpty()) return null
        return DbReplay(players, root.str("format"), games(actions, players))
    }

    private fun action(p: JsonObject): DbReplay.Action? {
        val play = p.str("play").ifBlank { p.str("type") }.trim()
        val key = play.lowercase()
        if (key in NOISE) return null
        val who = p.str("username").ifBlank { p.str("player") }.ifBlank { p.str("user") }
        val chat = key in CHAT
        val log = p["log"]
        val words = if (chat) {
            p.str("message").ifBlank { p.str("text") }.ifBlank { p.str("msg") }
        } else {
            val inLog = (log as? JsonObject)?.let { it.str("private_log").ifBlank { it.str("public_log") } }
                ?: (log as? JsonPrimitive)?.contentOrNull.orEmpty()
            inLog.ifBlank { p.str("private_log").ifBlank { p.str("public_log") } }
        }.trim()
        val public = if (chat) words else {
            ((log as? JsonObject)?.str("public_log") ?: (log as? JsonPrimitive)?.contentOrNull).orEmpty().ifBlank { p.str("public_log") }.trim()
        }
        val cards = buildList {
            (p["card"] as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() }?.let(::add)
            (p["cards"] as? JsonArray)?.forEach { c -> (c as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() }?.let(::add) }
            if (!chat) QUOTED.findAll(words).forEach { add(it.groupValues[1]) }
        }.distinct()
        if (words.isBlank() && play.isBlank()) return null
        if (chat && words.isBlank()) return null
        val phase = Regex("""^enter\s+(\w+)""", RegexOption.IGNORE_CASE).find(play)?.groupValues?.get(1)?.uppercase().orEmpty()
        return DbReplay.Action(play, who, words, cards, (p["seconds"] as? JsonPrimitive)?.doubleOrNull ?: -1.0, chat, phase, public)
    }

    /** The duel log's entries, however deep the document keeps them: objects that carry a log's words. */
    private fun logEntries(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap(::logEntries)
        is JsonObject -> if (listOf("public_log", "private_log", "log", "message").any { e[it] != null }) listOf(e) else e.values.flatMap(::logEntries)
        else -> emptyList()
    }

    /**
     * The actions cut into games and turns. A turn begins at a player's Draw Phase, at the first thing done after a turn
     * ended, or — turn 1 has no draw — at the first phase entered; a new game at "Begin next duel" or "Back to RPS".
     */
    private fun games(actions: List<DbReplay.Action>, players: List<String>): List<DbReplay.Game> {
        val games = ArrayList<DbReplay.Game>()
        var turns = ArrayList<DbReplay.Turn>()
        var current = ArrayList<DbReplay.Action>()
        var player = ""
        var first: String? = null
        var loser: String? = null
        var turnNo = 0
        var ended = false
        var drew = false
        fun closeTurn() {
            if (current.isNotEmpty()) turns += DbReplay.Turn(turnNo, player, current)
            current = ArrayList()
        }
        fun open(who: String) {
            closeTurn()
            turnNo++
            player = who
            if (turnNo == 1) first = who
            ended = false
            drew = false
        }
        fun closeGame() {
            closeTurn()
            if (turns.isNotEmpty()) games += DbReplay.Game(games.size + 1, turns, first, loser)
            turns = ArrayList()
            turnNo = 0
            player = ""
            first = null
            loser = null
            ended = false
            drew = false
        }
        for (a in actions) {
            val key = a.play.lowercase()
            if (key in NEW_GAME) {
                closeGame()
                continue
            }
            val acts = !a.chat && a.player.isNotBlank()
            val draw = acts && (a.phase == "DP" || DRAW_PHASE.containsMatchIn(a.play) || DRAW_PHASE.containsMatchIn(a.words))
            when {
                !acts -> Unit
                ended -> open(a.player)
                draw && (turnNo == 0 || drew || a.player != player) -> open(a.player)
                turnNo == 0 && a.phase.isNotEmpty() -> open(a.player)
            }
            if (draw) drew = true
            current += a
            if (acts && (key == "end turn" || END_TURN.containsMatchIn(a.words))) ended = true
            if (key in LOSS && a.player.isNotBlank()) loser = a.player
        }
        closeGame()
        // A loser named by the log's own words, where no play said so.
        return games.map { g ->
            if (g.loser != null) g
            else g.copy(loser = g.turns.flatMap { it.actions }.lastOrNull { !it.chat && CONCEDED.containsMatchIn(it.words) }?.player?.takeIf { it in players })
        }
    }

    private val END_TURN = Regex("""\bended (?:their|his|her|the) turn\b""", RegexOption.IGNORE_CASE)
    private val CONCEDED = Regex("""\b(?:admitted defeat|surrendered|conceded)\b""", RegexOption.IGNORE_CASE)

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    // ---- in words -----------------------------------------------------------------------

    /** The replay as the study reads it: games, turns and what each player did and said, card names in quotes. */
    fun render(r: DbReplay, heading: String): String = buildString {
        appendLine("# $heading")
        appendLine()
        appendLine("Players: ${r.players.joinToString(" vs ").ifBlank { "unknown" }}" + if (r.format.isNotBlank()) " · format ${r.format}" else "")
        appendLine("${r.games.size} game${if (r.games.size == 1) "" else "s"}, ${r.actions} actions, ${r.talk} lines of talk.")
        for (g in r.games) {
            appendLine()
            appendLine("## Game ${g.n}" + (g.first?.let { " — $it went first" } ?: ""))
            for (t in g.turns) {
                appendLine()
                appendLine(if (t.n == 0) "### Before the first turn" else "### Turn ${t.n} — ${t.player.ifBlank { "?" }}")
                t.actions.forEach { appendLine(line(it)) }
            }
            g.loser?.let { l -> appendLine(); appendLine("Result: $l lost" + (r.opponentOf(l)?.let { "; $it won" } ?: "") + ".") }
        }
    }.trimEnd() + "\n"

    private fun line(a: DbReplay.Action): String {
        val who = a.player.ifBlank { "?" }
        return when {
            a.chat -> "- $who says: “${a.words.replace('\n', ' ')}”"
            a.words.isNotBlank() -> "- " + (if (a.words.startsWith(who)) "" else "$who: ") + a.words.replace('\n', ' ')
            a.phase.isNotEmpty() -> "- $who: enters ${a.phase}"
            else -> "- $who: ${a.play}" + if (a.cards.isNotEmpty()) " " + a.cards.joinToString { "\"$it\"" } else ""
        }
    }
}
