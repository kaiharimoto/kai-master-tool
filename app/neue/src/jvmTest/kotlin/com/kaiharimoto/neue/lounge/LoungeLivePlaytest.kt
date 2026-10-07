package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeTalk
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.duel.match.AgentPlayer
import com.kaiharimoto.mastertool.core.duel.match.AiMatch
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchPrompt
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.Windows
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.neue.ai.AnthropicBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ai at a Lounge table with a real model (`docs/LOUNGE.md`, round three) — only with `NEUE_LIVE_LOUNGE=1` and an
 * `ANTHROPIC_API_KEY` (model `NEUE_LIVE_MODEL`, else Sonnet), since it spends real tokens; otherwise it does nothing.
 * A scripted person sits across from Ai, Ai plays its first turn and hands the table back, and the room asks Ai two
 * things, one for everyone and one privately. It checks the table moved on and every answer came, and prints the
 * transcript, for tuning `MatchPrompt.system(against = …)` and `LoungeTalk.system` on kai's machine.
 */
class LoungeLivePlaytest {
    @Test
    fun aRealModelPlaysASeatAndAnswersTheRoom() {
        if (System.getenv("NEUE_LIVE_LOUNGE") != "1") return
        val players = LiveLoungeAi.fromEnv() ?: return
        val pool = LoungeBrowserHarness.POOL
        val catalog = DuelCatalog { code -> pool.firstOrNull { it.id.value == code }?.let(DuelCardInfo::of) }
        val host = LoungeHost(Files.createTempDirectory("lounge-live").toFile(), catalog = { catalog }, ai = { players })
        fun <T> main(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
        val heard = LinkedBlockingQueue<LoungeWire>()
        val kai = main { host.open(out = {}) }
        main { host.hostJoin(kai, "kai") }
        val ash = main { host.open(out = { heard.add(it) }) }
        fun say(w: LoungeWire) = main { ash.hear(w) }
        fun table(minutes: Long, where: (DuelState) -> Boolean): DuelState {
            val until = System.currentTimeMillis() + minutes * 60_000
            while (System.currentTimeMillis() < until) {
                val u = ((heard.poll(500, TimeUnit.MILLISECONDS) as? LoungeWire.Table)?.wire as? Wire.Update) ?: continue
                val s = DuelMirror.state(u.view)
                if (where(s)) return s
            }
            fail("the table never came to that")
        }
        say(LoungeWire.Hi(nick = "Ash"))
        main { kai.hear(LoungeWire.Create("Live")) }
        val room = main { host.lounge.rooms.single().id }
        main { kai.hear(LoungeWire.RoomSet(room, ai = true)) }
        // Ash brings a deck, and sits Ai across with the same cards.
        say(LoungeWire.Enter(room))
        say(LoungeWire.DeckSave(null, "Ash's", LoungeBrowserHarness.DECK))
        val ashDeck = generateSequence { heard.poll(5, TimeUnit.SECONDS) }.filterIsInstance<LoungeWire.Deck>().first().id
        say(LoungeWire.AiSeat(1, deck = ashDeck))
        say(LoungeWire.Sit(0))
        say(LoungeWire.Ready(ashDeck))
        // The opening: Ash throws, Ai throws and chooses (or Ash lets Ai go first).
        var seq = 0
        var s = table(2) { it.opening != null }
        while (s.opening?.decided != true) {
            if (s.opening?.dice?.get(0).isNullOrEmpty() || s.opening?.tied == true) say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.OpeningRoll(0)))))
            if (s.opening?.winner == 0) say(LoungeWire.Table(Wire.Intent(++seq, listOf(DuelAction.GoFirst(0, first = false)))))
            s = table(3) { it.opening?.decided == true || it.opening?.winner != null || it.opening?.tied == true }
        }
        // Ai's turn, played and handed back.
        val back = table(10) { it.turn >= 2 && it.active == 0 }
        println("[live] Ai handed back the table at turn ${back.turn}")
        // The room asks; then Ash privately.
        say(LoungeWire.AskAi("What did Ai just do, and what can I see on its side?"))
        say(LoungeWire.AskAi("From my hand, what is my best play?", private = true))
        val until = System.currentTimeMillis() + 5 * 60_000
        var talk: LoungeWire.Talk? = null
        while (System.currentTimeMillis() < until) {
            val w = heard.poll(500, TimeUnit.MILLISECONDS) as? LoungeWire.Talk ?: continue
            talk = w
            if (w.entries.count { it.ai } >= 2 && !w.thinking) break
        }
        val entries = talk?.entries.orEmpty()
        entries.forEach { println("[live] ${it.who}${if (it.to != null) " (private)" else ""}: ${it.text}") }
        assertTrue(entries.count { it.ai && !it.text.startsWith("(No answer") } >= 2, "both questions answered")
        println("[live] tokens spent: ${players.spentTotal}")
        assertTrue(players.spentTotal in 1..2_000_000, "a sane spend: ${players.spentTotal}")
    }
}

/** Ai's players on a real model, for the live playtest and the harness's `LOUNGE_AI=real`: no memory, no kai. */
internal class LiveLoungeAi(private val key: String, private val model: String) : LoungeAiPlayers {
    override val name = "Ai"
    override val rules = MatchRules(turnCap = Int.MAX_VALUE, maxCues = Int.MAX_VALUE, tokenCap = Long.MAX_VALUE, windows = Windows.ACTIVATIONS, paceMs = 300)
    var spentTotal = 0L
        private set

    private fun backend() = AnthropicBackend(key, System.getenv("ANTHROPIC_BASE_URL"))

    override fun cardText(name: String): String? = LoungeBrowserHarness.POOL.firstOrNull { it.name == name }?.let { "${it.type}\n${it.description}" }
    override fun unavailable(): String? = null

    override fun player(seat: Int, seatName: String, deckName: String, against: String?, library: String?, strength: String): MatchPlayer {
        val system = MatchPrompt.system(name, seat, seatName, deckName, rules, guide = "", against = against)
        val specs = MatchPrompt.tools(AiTools.offered(Int.MAX_VALUE).filter { it.name in AiMatch.TOOLS })
        return AgentPlayer(backend(), system, specs, model, DuelPrefs.effort(strength, emptyList(), ""), DuelPrefs.steps(strength, rules.cueSteps),
            200_000, System::currentTimeMillis, session(system))
    }

    override fun talker(roomName: String, seatName: String?, strength: String): LoungeTalker {
        val system = LoungeTalk.system(name, seatName)
        val specs = MatchPrompt.tools(AiTools.offered(Int.MAX_VALUE).filter { it.name in LoungeTalk.TOOLS })
        val agent = AgentPlayer(backend(), system, specs, model, "", 4, 200_000, System::currentTimeMillis, session(system))
        return object : LoungeTalker {
            override suspend fun ask(cue: String, tools: ToolRunner, saying: (String) -> Unit): Pair<String?, CueResult> {
                val before = agent.session.turns.size
                agent.onText = saying
                val result = try { agent.cue(cue, tools) } finally { agent.onText = null }
                return agent.session.turns.drop(before).lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }?.text to result
            }
        }
    }

    private fun session(system: String): AiSession {
        val now = System.currentTimeMillis()
        return AiSession(id = UUID.randomUUID().toString(), title = "Live Lounge", createdAt = now, updatedAt = now, system = system, mode = AiSession.MODE_MATCH)
    }

    override fun spent(tokens: Long) { spentTotal += tokens }
    override fun release(player: MatchPlayer) = Unit
    override fun release(talker: LoungeTalker) = Unit

    companion object {
        /** A real model when `ANTHROPIC_API_KEY` is set, else null: the model is `NEUE_LIVE_MODEL`, else Sonnet. */
        fun fromEnv(): LiveLoungeAi? {
            val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() } ?: return null
            return LiveLoungeAi(key, System.getenv("NEUE_LIVE_MODEL") ?: "claude-sonnet-5-5")
        }
    }
}
