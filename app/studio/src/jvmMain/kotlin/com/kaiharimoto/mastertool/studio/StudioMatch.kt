package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.match.CueResult
import com.kaiharimoto.mastertool.core.duel.match.MatchPlayer
import com.kaiharimoto.mastertool.core.duel.match.MatchRules
import com.kaiharimoto.mastertool.core.duel.match.MatchSeat
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.duel.MatchChoice
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Ai vs Ai photographed (`docs/phases/C.md` §6, `--duel-match=dialog|live|over`): the start dialog with two connections
 * set up; a match being watched — two scripted players through the real referee and table, the second seat mid-turn,
 * thinking; or one played to its end. No model is called: the players are scripts, the rest is the app's own.
 */
internal suspend fun studioMatch(h: NeueHolders, how: String, clock: FrameClock) {
    val b = h.builder
    val index = b.index
    h.duel.catalog = DuelCatalog { code -> index.byId(CardId(code))?.let(DuelCardInfo::of) }
    val opus = AiConnection("studio-a", "anthropic", "Anthropic", "claude-opus-5-5")
    val gpt = AiConnection("studio-o", "openai", "OpenAI", "gpt-5")
    h.neue.update { it.copy(ai = it.ai.copy(connections = listOf(opus, gpt), active = opus.id)) }
    h.neue.page = Page.DUEL
    clock.run(20)
    if (how == "dialog") {
        h.duel.matches.dialogOpen = true
        clock.run(60)
        return
    }
    val main = b.deck.main.map { it.value }
    val extra = b.deck.extra.map { it.value }
    val seats = listOf(
        MatchSeat("claude-opus-5-5", b.deckId, b.deckName, main, extra, "Anthropic", "claude-opus-5-5"),
        MatchSeat("gpt-5", b.deckId, b.deckName, main, extra, "OpenAI", "gpt-5"),
    )
    val hold = how == "live"
    val players = listOf(Script(hold = false), Script(hold = hold))
    h.duel.matches.start(MatchChoice(seats, listOf(opus, gpt), 7, MatchRules(turnCap = 12, paceMs = 0)), players, h.duel.catalog, emptyList())
    var k = 0
    while (k++ < 400) {
        clock.run(5)
        val m = h.duel.matches
        if (!m.running) break
        if (hold && players[1].holding) break
    }
    clock.run(60)
    val g = h.duel.matches.live
    println("[neue-studio] ai vs ai: ${if (h.duel.matches.running) "running" else h.duel.matches.ended}; turn ${g?.state?.turn}, entries ${g?.cursor}")
}

/** A scripted seat: goes first, summons what the menu offers, attacks when it can, and wins on turn 3; or holds, thinking. */
private class Script(private val hold: Boolean) : MatchPlayer {
    var holding = false
    private var n = 0

    override suspend fun cue(text: String, tools: ToolRunner): CueResult {
        val head = text.lineSequence().first()
        val turn = Regex("turn (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        suspend fun call(name: String, ops: List<String> = emptyList()): String {
            val input = if (ops.isEmpty()) JsonObject(emptyMap()) else buildJsonObject { putJsonArray("ops") { ops.forEach { add(it) } } }
            return tools.run(Part.ToolUse("s${n++}", name, input)).content
        }
        when {
            "· choose]" in head -> call("duel_act", listOf("go first"))
            "· resolve]" in head -> call("duel_act", listOf("resolve"))
            "· play]" in head && turn >= 3 -> call("duel_act", listOf("m1", "lp opp -8000"))
            "· play]" in head -> {
                call("duel_act", listOf("m1"))
                val summon = Regex("`(s h\\d)`").find(call("duel_moves"))?.groupValues?.get(1)
                if (summon != null) call("duel_act", listOf(summon))
                if (hold) {
                    holding = true
                    awaitCancellation()
                }
                call("duel_act", listOf("end"))
            }
        }
        return CueResult(tokens = 0)
    }
}
