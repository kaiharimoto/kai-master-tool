package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.StopReason
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.net.NetReplay
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.TestHolders
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.duel.OfflineNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The duel table held to what each seat may know and do (the red team, R1, R11 and R8): `duel_search` never searches from
 * the person's seat unless the person gave Ai full knowledge; the fact-check's correction at the table is a word to the
 * player — offered only the tools that look and talk, and refused a move if it asks for one anyway; and a networked duel
 * still going is neither kept as a replay nor reviewed.
 */
class TableRedTeamTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun NeueHolders.tool(name: String, input: JsonObject = JsonObject(emptyMap())): Part.ToolResult =
        ai.host.run(Part.ToolUse("t-" + UUID.randomUUID(), name, input))

    private fun ids(vararg pairs: Pair<Int, Int>): List<CardId> = pairs.flatMap { (code, n) -> List(n) { CardId(code) } }

    /** A two-seat duel dealt: the person at seat 0 with [person]'s deck, Ai at seat 1 with Raigeki and Impermanence. */
    private suspend fun duel(h: NeueHolders, knowledge: String) {
        val person = Deck(ids(ASH to 3, NIBIRU to 3, POT to 3), emptyList(), emptyList())
        val ai = Deck(ids(RAIGEKI to 3, IMPERMANENCE to 3), emptyList(), emptyList())
        h.deps.deckRepository.save("person-deck", "Person's deck", person)
        h.deps.deckRepository.save("ai-deck", "Ai's deck", ai)
        h.neue.update { it.copy(duel = it.duel.copy(aiSeat = 1, aiKnowledge = knowledge, openingRoll = false)) }
        val set = h.tool("duel_setup", buildJsonObject { put("deck_id", "person-deck"); put("opponent_deck_id", "ai-deck") })
        assertFalse(set.isError, set.content)
        assertEquals(5, h.duel.game!!.state.seats[0].hand.size, "five drawn")
    }

    @Test
    fun aiNeverSearchesFromThePersonsSeatWithoutFullKnowledge() = runBlocking {
        val h = TestHolders.make(scope)
        duel(h, DuelBrief.SELF)
        val s = h.duel.game!!.state
        val hand = s.seats[0].hand.mapNotNull { u -> s.cards[u]?.code }.mapNotNull { h.builder.index.byId(CardId(it))?.name }.distinct()
        assertTrue(hand.isNotEmpty())
        val theirs = h.tool("duel_search", buildJsonObject { put("seat", 0); put("strength", "fast") })
        assertTrue(theirs.isError, theirs.content)
        assertTrue("your own seat only" in theirs.content, theirs.content)
        hand.forEach { name -> assertFalse(name in theirs.content, "named $name from the person's hand: ${theirs.content}") }
        // Its own seat is never refused for being another's.
        val own = h.tool("duel_search", buildJsonObject { put("seat", 1); put("strength", "fast") })
        assertFalse("your own seat only" in own.content, own.content)
    }

    @Test
    fun aCorrectionAtTheTableMovesNothing() = runBlocking {
        val h = TestHolders.make(scope)
        duel(h, DuelBrief.SELF)
        val conn = AiConnection("c-correct", "anthropic", model = "scripted")
        h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(conn), active = conn.id, factCheck = true)) }
        val offered = Collections.synchronizedList(ArrayList<Set<String>>())
        val scripted = object : ModelBackend {
            override val runsOwnLoop = false
            override fun turn(request: TurnRequest) = flow {
                if (request.system.startsWith("You check")) {
                    val claims = """{"claims": [{"claim": "Raigeki attacks for 2000", "verdict": "wrong", "correction": "Raigeki is a Spell", "source": "card_info"}]}"""
                    emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant(claims), usage = Usage(input = 100, output = 10)))
                    return@flow
                }
                offered += request.tools.map { it.name }.toSet()
                val answered = request.history.lastOrNull()?.parts?.any { it is Part.ToolResult } == true
                if (answered) {
                    emit(BackendEvent.Finished(StopReason.END, ChatTurn.assistant("**Correction:** Raigeki is a Spell; it never attacks."), usage = Usage(input = 100, output = 10)))
                } else {
                    // The correction asks to move anyway: a card to the GY.
                    val call = Part.ToolUse("a1", "duel_act", buildJsonObject { putJsonArray("ops") { add(JsonPrimitive("lp opp =0")) } })
                    emit(BackendEvent.Finished(StopReason.TOOL_USE, ChatTurn(Role.ASSISTANT, listOf(call)), usage = Usage(input = 100, output = 10)))
                }
            }
        }
        h.ai.backend = "${conn.id}:${conn.model}:${conn.baseUrl}:${conn.program}" to scripted
        val before = h.duel.game
        val answer = "You should attack with Raigeki now: it is better to attack before they respond, and the chain cannot negate it. " +
            "Summon first, then attack for lethal."
        h.ai.session = AiSession(
            "s-duel-${UUID.randomUUID()}", mode = AiSession.MODE_DUEL, connection = conn.id,
            turns = listOf(ChatTurn.user("Should I attack?"), ChatTurn.assistant(answer)),
        )
        h.ai.duelAsked = true
        h.ai.checkLastAnswer()
        withTimeout(20_000) {
            while (h.ai.session?.turns?.none { t -> t.role == Role.ASSISTANT && "Correction:" in t.text } != false || h.ai.running || h.ai.checking) delay(20)
        }
        val turns = h.ai.session!!.turns
        assertTrue(offered.isNotEmpty(), "the correction ran")
        offered.forEach { names ->
            assertTrue(names.isNotEmpty() && names.all { it in AiTools.DUEL_WORDS }, "offered $names")
            listOf("duel_act", "duel_peek", "duel_watch", "duel_combo").forEach { assertFalse(it in names, "offered $it") }
            assertTrue("duel_state" in names, "$names")
        }
        val refused = turns.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().firstOrNull { it.id == "a1" }
        assertNotNull(refused)
        assertTrue(refused.isError && "moves, peeks and watches nothing" in refused.content, refused.content)
        assertEquals(before, h.duel.game, "the table is unchanged")
    }

    @Test
    fun aNetworkedDuelGoingOnIsNeitherKeptNorReviewed() = runBlocking {
        val h = TestHolders.make(scope)
        duel(h, DuelBrief.SELF)
        val g = h.duel.game!!
        val record = g.record("live", saved = 0L)
        // On one screen it may be kept.
        assertNull(h.duel.keepRefusal)
        // Hosting it over the network: the host's record holds the guest's hand and choices.
        h.duel.network = OfflineNet().also { it.role = Duels.NetRole.HOST }
        assertEquals(NetReplay.REFUSED, h.duel.keepRefusal)
        h.duel.problem = null
        h.duel.saveReplay("mid-duel")
        assertEquals(NetReplay.REFUSED, h.duel.problem)
        h.reviews.start("r-live", record)
        assertEquals(NetReplay.REFUSED, h.reviews.problem)
        assertNull(h.reviews.running, "no review started")
        // Once it has ended, both are open again.
        h.duel.game = g.copy(state = g.state.copy(conceded = 1))
        assertNull(h.duel.keepRefusal)
        assertNull(h.duel.reviewRefusal(record))
        h.duel.network = OfflineNet()
    }

    private companion object {
        const val ASH = 14558127
        const val NIBIRU = 27204311
        const val POT = 55144522
        const val RAIGEKI = 12580477
        const val IMPERMANENCE = 10045474
    }
}
