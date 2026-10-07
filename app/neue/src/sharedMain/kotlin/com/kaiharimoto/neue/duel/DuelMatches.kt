package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.ModelNames
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ai.DuelGuide
import com.kaiharimoto.mastertool.core.duel.match.AgentPlayer
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.MatchEnd
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchPrompt
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.duel.match.MatchTable
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.ai.closeBackend
import com.kaiharimoto.neue.ai.guideForPrompt
import com.kaiharimoto.neue.ai.newBackend
import com.kaiharimoto.neue.ai.windowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** What the person chose in the Ai vs Ai dialog: each seat's deck and connection, the seed and the bounds. */
data class MatchChoice(
    val seats: List<MatchSeat>,
    val connections: List<AiConnection>,
    val seed: Long?,
    val rules: MatchRules,
)

/**
 * Ai vs Ai on the Duel page (`docs/phases/C.md` §6), a part of [Duels]: two Ai sessions, one a seat, refereed off the main
 * thread on a table of the match's own — never the duel in play, which waits untouched behind it. While a match is on
 * the table ([live]) the page shows it as a spectator sees it, both hands face-up — the person's view, never handed to
 * either session — and refuses every move of the person's; Stop ends both runs where they stand. A finished match is
 * a duel record of kind "ai-vs-ai" and a replay; each seat's conversation is kept with Ai's.
 */
class DuelMatches internal constructor(private val d: Duels) : LiveMatch {
    /** The match's table, shown in place of the duel in play from its start until it is closed. */
    override var live by mutableStateOf<DuelGame?>(null)
        private set
    /** The referee is running. */
    override var running by mutableStateOf(false)
        private set
    /** Who is moving now, in words. */
    var status by mutableStateOf<String?>(null)
        private set
    /** How the match ended, in words; null while it runs. */
    var ended by mutableStateOf<String?>(null)
        private set
    /** Each seat's player for the bar, as the table names it: the model said short ("Opus 5.5"), else the connection. */
    var engines by mutableStateOf<List<String>>(emptyList())
        private set
    /** Each seat's deck, by name. */
    var decks by mutableStateOf<List<String>>(emptyList())
        private set
    /** Tokens spent so far, both seats, and the budget they are spent against: the bar's counter while it runs. */
    var spent by mutableStateOf(0L)
        private set
    var budget by mutableStateOf(0L)
        private set
    /** Each seat's tokens so far by kind, and its model's list prices (null: not in the table), for the counter's "≈ $". */
    var usage by mutableStateOf<List<Usage>>(emptyList())
        private set
    var prices by mutableStateOf<List<Prices.Price?>>(emptyList())
        private set
    /** Each seat's model, said short, for the counter's tip. */
    var models by mutableStateOf<List<String>>(emptyList())
        private set

    /** What the match has spent so far in dollars at list prices; null when a seat's model has no price. */
    val dollars: Double? get() = AiMatch.dollars(usage, prices)
    /** Each seat's conversation, by id: kept with Ai's, to be read afterwards. */
    var sessions by mutableStateOf<List<String>>(emptyList())
        private set
    /** The start dialog is open. */
    var dialogOpen by mutableStateOf(false)

    private var job: Job? = null

    /**
     * Starts a match of [players] on a table dealt from [choice]: its record kept and its replay saved when it ends, [done]
     * told how. [backends] are closed when it is over. The caller has checked the seats can play ([problems]).
     */
    fun start(
        choice: MatchChoice,
        players: List<MatchPlayer>,
        catalog: DuelCatalog,
        sessionIds: List<String>,
        cardText: (String) -> String? = { null },
        backends: List<ModelBackend> = emptyList(),
        done: (MatchEnd) -> Unit = {},
    ) {
        if (running) return
        val seed = choice.seed ?: (System.nanoTime() and 0x7fffffff)
        val id = "avai${Duels.now()}"
        val header = MatchTable.header(id, seed, choice.seats, Duels.now())
        val table = MatchTable(DuelGame.start(header, Duels.now()), catalog, choice.rules, cardText, Duels::now)
        // Live, as the table moves: the person watches the match, at a pace they can follow.
        table.onMove = { g ->
            withContext(Dispatchers.Main) { live = g }
            if (choice.rules.paceMs > 0) delay(choice.rules.paceMs)
        }
        val engineList = choice.seats.map { DuelResults.Engine(it.connection, it.model) }
        live = table.game
        running = true
        ended = null
        status = "Dealing"
        engines = choice.seats.map { it.name }
        decks = choice.seats.map { it.deckName }
        spent = 0L
        budget = choice.rules.tokenCap
        usage = List(choice.seats.size) { Usage() }
        prices = choice.connections.map { Prices.of(it.provider, it.model) }
        models = choice.seats.map { ModelNames.short(it.model).ifBlank { it.name } }
        sessions = sessionIds
        d.closeReplay()
        d.strip = null
        d.clearSelection()
        val match = AiMatch(
            table, players, engineList, Duels::now,
            status = { words -> d.scope.launch { status = words } },
            spent = { tokens -> d.scope.launch { spent = tokens } },
            used = { u -> d.scope.launch { usage = u } },
        )
        job = d.scope.launch {
            var end: MatchEnd? = null
            try {
                end = withContext(Dispatchers.Default) { match.run() }
            } catch (c: CancellationException) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Default) { table.say("Stopped by the person: no result is kept.") }
                    end = MatchEnd(null, "Stopped by the person in turn ${table.state.turn}.", stopped = true)
                }
            } catch (t: Throwable) {
                end = MatchEnd(null, "The match stopped: ${t.message ?: t::class.simpleName}.", stopped = true)
            } finally {
                withContext(NonCancellable) {
                    val over = end ?: MatchEnd(null, "The match stopped.", stopped = true)
                    live = table.game
                    running = false
                    status = null
                    ended = over.words
                    over.result?.let { d.records.keep(it) }
                    val names = choice.seats.joinToString(" v ") { it.name }
                    d.replayer.keepReplay("Ai vs Ai: $names${if (over.stopped) " (stopped)" else ""}", table.game)
                    backends.forEach { runCatching { closeBackend(it) } }
                    job = null
                    done(over)
                }
            }
        }
    }

    /** The person's Stop: both runs end where they stand; the table stays to be read until [close]. */
    override fun stop() {
        job?.cancel()
    }

    /** Back to the duel in play: the match's table put away (its replay and record are kept). */
    override fun close() {
        if (running) return
        live = null
        ended = null
        engines = emptyList()
        decks = emptyList()
    }

    /** Waits for a running match to finish (tests). */
    suspend fun join() {
        job?.join()
    }

    companion object {
        /** What the person is told when they reach for a table Ai vs Ai is playing. */
        const val ON_THE_TABLE = LiveMatch.ON_THE_TABLE

        /**
         * Why [connection] cannot play a seat, or null when it can: an API connection the app can talk to itself. A plan's
         * command-line app runs its own loop and reaches the app's tools through its MCP server, which answers for the duel
         * in play — it cannot be held to one seat of a table of the match's own.
         */
        fun unusable(connection: AiConnection): String? {
            val p = Providers.byId(connection.provider) ?: return "Unknown provider ${connection.provider}."
            return if (p.wire == Wire.CLAUDE_CLI || p.wire == Wire.CODEX_CLI) {
                "${connection.label.ifBlank { p.label }} is a plan's command-line app: it runs its own loop and every tool through the app's own server, which cannot be held to one seat of a match's table. Choose an API connection."
            } else null
        }
    }
}

/** What is wrong with starting [choice] here, in words; empty when it can start. */
internal fun matchProblems(h: NeueHolders, choice: MatchChoice): List<String> = buildList {
    if (!h.neue.prefs.ai.enabled) add("Ai is off (Settings › Ai): there is no Ai to play.")
    if (h.duel.role != null) add("This is a networked table: Ai vs Ai is never played on one.")
    if (choice.connections.size != 2) add("Choose a connection for each seat.")
    choice.connections.distinctBy { it.id }.forEach { c -> DuelMatches.unusable(c)?.let(::add) }
    choice.seats.forEach { s -> if (s.main.isEmpty()) add("${s.deckName.ifBlank { "A seat's deck" }} has no Main Deck to draw from.") }
}

/**
 * Starts an Ai vs Ai match from the dialog: each seat a session of its own — its own backend, its own conversation (kept
 * with Ai's, mode "ai-vs-ai"), its own deck's guide and combos and never the other's — on the match's table. Null when
 * it started, else why not.
 */
internal fun startAiVsAi(h: NeueHolders, choice: MatchChoice): String? {
    matchProblems(h, choice).firstOrNull()?.let { return it }
    val duels = h.duel
    duels.useIndex(h.builder.index)
    val made = mutableListOf<ModelBackend>()
    val backends = choice.connections.map { c ->
        runCatching { h.ai.newBackend(c) }.getOrElse { e ->
            made.forEach(::closeBackend)
            return "Could not connect to ${c.label.ifBlank { c.provider }}: ${e.message}"
        }.also { made += it }
    }
    val now = System.currentTimeMillis()
    val specs = h.ai.tools.filter { it.name in AiMatch.TOOLS }
    val players = choice.seats.mapIndexed { seat, s ->
        val c = choice.connections[seat]
        // Its own deck's guide and combos, and never the other's: a player knows their own deck.
        val guide = s.deckId?.let { id -> DuelGuide.block(s.deckName, h.ai.guideForPrompt(id), duels.combosNow(id).combos) }.orEmpty()
        val system = MatchPrompt.system(h.ai.name, seat, s.name, s.deckName, choice.rules, guide)
        val provider = Providers.byId(c.provider)
        val effort = h.ai.prefs.effort.ifBlank { if (provider?.efforts?.contains("low") == true) "low" else provider?.defaultEffort.orEmpty() }
        val session = AiSession(
            id = UUID.randomUUID().toString(),
            title = "Ai vs Ai · ${s.name} · ${s.deckName}",
            createdAt = now,
            updatedAt = now,
            connection = c.id,
            system = system,
            mode = AiSession.MODE_MATCH,
            deckId = s.deckId,
            deckName = s.deckName,
        )
        AgentPlayer(
            backends[seat], system, specs, c.model, effort, choice.rules.cueSteps, h.ai.windowOf(c), System::currentTimeMillis, session,
            keep = { kept -> h.ai.scope.launch { h.ai.save(kept) } },
        )
    }
    val index = h.builder.index
    duels.matches.start(
        choice, players, duels.catalog, players.map { it.session.id },
        cardText = { name -> index.byName(name)?.let { c -> "${c.type}\n${c.description}" } },
        backends = backends,
        done = { end -> h.neue.note = Note("Ai vs Ai: ${end.words}") },
    )
    h.neue.go(Page.DUEL)
    return null
}

/** The desk's and Android's Ai vs Ai, behind the table's [Duels.match]. */
val Duels.matches: DuelMatches get() = match as DuelMatches
