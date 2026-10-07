package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.neue.duel.FileDuelStore
import com.kaiharimoto.neue.duel.matches
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.ToolRunner
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.ModelNames
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.record.AiPlay
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResultCodec
import com.kaiharimoto.mastertool.core.duel.record.ResultSeat
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
import java.io.File

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
        // Named as the dialog names them: the model said short (the design review, finding 2).
        MatchSeat(ModelNames.short(opus.model), b.deckId, b.deckName, main, extra, "Anthropic", opus.model),
        MatchSeat(ModelNames.short(gpt.model), b.deckId, b.deckName, main, extra, "OpenAI", gpt.model),
    )
    val hold = how == "live"
    val players = listOf(Script(hold = false), Script(hold = hold))
    h.duel.matches.start(MatchChoice(seats, listOf(opus, gpt), 7, MatchRules(turnCap = 12, tokenCap = 1_000_000, paceMs = 0)), players, h.duel.catalog, listOf("studio-seat-0", "studio-seat-1"))
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

/**
 * Replays opened on a tally of finished duels (`--duel-records=demo`, the design review, finding 11): Ai against kai at two
 * settings and against a guest over the network, and Ai vs Ai by models and decks — records written as the app writes them
 * and read back through its own reader.
 */
internal suspend fun studioRecords(h: NeueHolders, clock: FrameClock) {
    h.neue.page = Page.DUEL
    clock.run(20)
    val folder = File((h.duel.store as FileDuelStore).dir, DuelResultCodec.FOLDER).apply { mkdirs() }
    var n = 0
    fun keep(r: DuelResult) {
        n++
        File((h.duel.store as FileDuelStore).dir, DuelResultCodec.path(r.id)).writeText(DuelResultCodec.encode(r))
    }
    fun table(person: String, winner: Int?, first: Int, knows: String = DuelBrief.SELF, peeks: Int = 0, eyes: String? = DuelPrefs.KNOW_SEAT, net: Boolean = false) =
        keep(
            DuelResult(
                id = "studio-d$n", duel = "studio-d$n", ended = 1_760_000_000_000L + n,
                seats = listOf(
                    ResultSeat(person, "lab", "lab", if (net) Provenance.GUEST else Provenance.PERSON),
                    ResultSeat("Ai", "k9", "K9 Vanquish Soul", Provenance.AI),
                ),
                first = first, firstBy = DuelResult.ROLL, winner = winner, turns = 7,
                ai = AiPlay(seat = 1, knows = knows, peeks = peeks, moves = 30), net = net, eyes = if (net) null else eyes,
            ),
        )
    // Against kai, each seeing only their own hand: Ai won 3 of 5, 1 drawn.
    table("kai", 1, 1); table("kai", 1, 0); table("kai", 0, 0); table("kai", null, 1); table("kai", 1, 0)
    // Against kai with Ai on auto knowledge, kai seeing both hands.
    table("kai", 1, 1, knows = DuelBrief.AUTO, peeks = 2, eyes = DuelPrefs.KNOW_ALL); table("kai", 0, 0, knows = DuelBrief.AUTO, peeks = 1, eyes = DuelPrefs.KNOW_ALL)
    // A guest over the network.
    table("Rin", 0, 1, net = true)
    fun match(winner: Int?, first: Int, a: Pair<String, String>, b: Pair<String, String>) = keep(
        DuelResult(
            id = "studio-m$n", duel = "studio-m$n", ended = 1_760_000_000_000L + n,
            seats = listOf(
                ResultSeat(ModelNames.short(a.first), null, a.second, Provenance.AI, connection = "Anthropic", model = a.first),
                ResultSeat(ModelNames.short(b.first), null, b.second, Provenance.AI, connection = "OpenAI", model = b.first),
            ),
            first = first, firstBy = DuelResult.ROLL, winner = winner, turns = 9, kind = DuelResult.AI_VS_AI,
            how = if (winner == null) DuelResult.LIMIT else DuelResult.LP,
        ),
    )
    val opusLab = "claude-opus-5-5" to "lab"
    val gptK9 = "gpt-5" to "K9 Vanquish Soul"
    match(0, 0, opusLab, gptK9); match(1, 1, gptK9, opusLab); match(1, 1, opusLab, gptK9); match(null, 0, opusLab, gptK9); match(0, 0, opusLab, gptK9)
    match(1, 1, opusLab, "claude-opus-5-5" to "Branded")
    h.duel.reloadResults()
    h.duel.libraryOpen = true
    clock.run(60)
    println("[neue-studio] records: $n written to $folder, ${h.duel.results.size} read")
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
            "· play]" in head && turn >= 3 -> call("duel_act", listOf("m1", "bp", "lp opp -8000"))
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
        // Split as a cue of a real match splits: most of it the cached table and tools, a few lines written.
        return CueResult(tokens = 16_500, usage = Usage(input = 3_000, output = 1_500, cacheRead = 12_000))
    }
}
