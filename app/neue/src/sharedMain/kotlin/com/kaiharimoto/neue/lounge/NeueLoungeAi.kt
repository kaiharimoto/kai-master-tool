package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.playbook.Playbook
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.ai.DuelGuide
import com.kaiharimoto.mastertool.core.duel.lounge.AiSpend
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeTalk
import com.kaiharimoto.mastertool.core.duel.match.AgentPlayer
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchPrompt
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.AiLearn
import com.kaiharimoto.neue.ai.closeBackend
import com.kaiharimoto.neue.ai.guideForPrompt
import com.kaiharimoto.neue.ai.newBackend
import com.kaiharimoto.neue.ai.playbook
import com.kaiharimoto.neue.ai.windowOf
import com.kaiharimoto.neue.duel.DuelMatches
import com.kaiharimoto.neue.duel.tableGuide
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Ai at the Lounge's tables on kai's own connection (`docs/LOUNGE.md`, L5). Each seat Ai sits in is an agent of its
 * own, as an Ai vs Ai seat is: a fresh session, its own backend, the four table tools answered for its seat alone, and
 * nothing of kai's conversations, memory or other tools. An API connection only (a plan's command-line app runs its own
 * loop, which cannot be held to one seat), and never past [com.kaiharimoto.mastertool.core.duel.lounge.LoungePrefs.aiDailyTokens]
 * a day.
 */
internal class NeueLoungeAi(private val h: NeueHolders, private val dir: File) : LoungeAiPlayers {
    override val name: String get() = h.ai.name

    // A people's table: no turn cap, no match budget (the day's is the cap), each person's own windows kept by the table.
    override val rules = MatchRules(turnCap = Int.MAX_VALUE, maxCues = Int.MAX_VALUE, tokenCap = Long.MAX_VALUE, windows = Windows.ACTIVATIONS, paceMs = 600)

    private val backends = HashMap<MatchPlayer, ModelBackend>()
    private val talkBackends = HashMap<LoungeTalker, ModelBackend>()
    private val spendFile get() = File(dir, "ai-spend.json")
    private var spend: AiSpend = runCatching { json.decodeFromString(AiSpend.serializer(), spendFile.readText()) }.getOrDefault(AiSpend())

    private fun today(): String = LocalDate.now().toString()

    override fun cardText(name: String): String? = h.builder.index.byName(name)?.let { c -> "${c.type}\n${c.description}" }

    override fun unavailable(): String? {
        if (!h.neue.prefs.ai.enabled) return "Ai is off on kai's computer"
        val c = h.ai.prefs.connection ?: return "kai has no Ai connection set up"
        if (DuelMatches.unusable(c) != null) return "kai's Ai runs on a plan's command-line app; the Lounge's tables need an API connection"
        val cap = h.lounge.prefs.aiDailyTokens
        if (cap <= 0) return "kai keeps Ai out of the Lounge"
        if (spend.spent(today(), cap)) return "today's Ai budget is spent (${cap / 1000}k tokens); it comes back tomorrow"
        return null
    }

    override fun player(seat: Int, seatName: String, deckName: String, against: String?, library: String?, strength: String): MatchPlayer {
        val c = h.ai.prefs.connection ?: error("No Ai connection")
        val backend = h.ai.newBackend(c)
        val specs = MatchPrompt.tools(h.ai.tools.filter { it.name in AiMatch.TOOLS })
        // kai's library deck: its guide and combos, as at kai's own table. A friend's deck: none of kai's applies to it.
        val guide = library?.let { id -> DuelGuide.block(deckName, tableGuide(h.ai.guideForPrompt(id)), h.duel.combosNow(id).combos) }.orEmpty()
        val system = MatchPrompt.system(name, seat, seatName, deckName, rules, guide = guide, against = against)
        val provider = Providers.byId(c.provider)
        val effort = DuelPrefs.effort(strength, provider?.efforts.orEmpty(), h.ai.prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() })
        val now = System.currentTimeMillis()
        val session = AiSession(
            id = UUID.randomUUID().toString(),
            title = "Lounge · $seatName · $deckName",
            createdAt = now,
            updatedAt = now,
            connection = c.id,
            system = system,
            mode = AiSession.MODE_MATCH,
            deckId = library,
            deckName = deckName,
        )
        // Its conversation is the table's, not kai's: never kept among kai's.
        val player = AgentPlayer(backend, system, specs, c.model, effort, DuelPrefs.steps(strength, rules.cueSteps), h.ai.windowOf(c), System::currentTimeMillis, session)
        backends[player] = backend
        return player
    }

    override suspend fun knowledge(library: String, tool: String, input: JsonObject): String? =
        AiLearn(h, h.ai).run(tool, JsonObject(input - "deck_id"), library, { emptyList() })?.content

    override fun playbook(library: String): Playbook? = h.ai.playbook(library)

    override fun talker(roomName: String, seatName: String?, strength: String): LoungeTalker {
        val c = h.ai.prefs.connection ?: error("No Ai connection")
        val backend = h.ai.newBackend(c)
        val specs = MatchPrompt.tools(h.ai.tools.filter { it.name in LoungeTalk.TOOLS })
        val system = LoungeTalk.system(name, seatName)
        val provider = Providers.byId(c.provider)
        val effort = DuelPrefs.effort(strength, provider?.efforts.orEmpty(), h.ai.prefs.effort.ifBlank { provider?.defaultEffort.orEmpty() })
        val now = System.currentTimeMillis()
        val session = AiSession(
            id = UUID.randomUUID().toString(),
            title = "Lounge · $roomName" + (seatName?.let { " · $it" } ?: ""),
            createdAt = now,
            updatedAt = now,
            connection = c.id,
            system = system,
            mode = AiSession.MODE_MATCH,
        )
        val agent = AgentPlayer(backend, system, specs, c.model, effort, TALK_STEPS, h.ai.windowOf(c), System::currentTimeMillis, session)
        val talker = object : LoungeTalker {
            override suspend fun ask(cue: String, tools: ToolRunner, saying: (String) -> Unit): Pair<String?, CueResult> {
                val before = agent.session.turns.size
                agent.onText = saying
                val result = try { agent.cue(cue, tools) } finally { agent.onText = null }
                // Its answer is the last thing it said to this question — never an earlier one's.
                val reply = agent.session.turns.drop(before).lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text
                return reply to result
            }
        }
        talkBackends[talker] = backend
        return talker
    }

    override fun release(talker: LoungeTalker) {
        talkBackends.remove(talker)?.let(::closeBackend)
    }

    override fun spent(tokens: Long) {
        spend = spend.add(today(), tokens)
        runCatching {
            dir.mkdirs()
            spendFile.writeText(json.encodeToString(AiSpend.serializer(), spend))
        }
    }

    override fun release(player: MatchPlayer) {
        backends.remove(player)?.let(::closeBackend)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        /** Rounds of tools for one answer in a room's conversation: a card or two read, the table looked at again. */
        const val TALK_STEPS = 4
    }
}
