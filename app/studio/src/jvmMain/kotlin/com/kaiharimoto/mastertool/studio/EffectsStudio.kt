package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.Usage
import com.kaiharimoto.mastertool.core.ai.providers.Prices
import com.kaiharimoto.mastertool.core.duel.effects.FxFrom
import com.kaiharimoto.mastertool.core.duel.effects.FxOffers
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.FxRequest
import com.kaiharimoto.mastertool.core.duel.effects.FxReviews
import com.kaiharimoto.mastertool.core.duel.effects.FxStatus
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.effects.offerFor
import com.kaiharimoto.neue.platform.Platform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Effects as code, photographed (Phase D step 2, `docs/phases/D.md` §3.1, §3.5): `--effects=pane` opens the Effects app in
 * Ai World, maximised, on a library seeded from the builder's deck — a card written clean, one with a warning open, one that
 * does not check, each asked from somewhere with what it cost — and the open deck's choices with Write these; `--effects=request`
 * shows Ai's `fx_request` in the chat as the request card (cards, cost, Write and Not now); `--effects=viewer` opens the
 * first unwritten card large, with Write its effect. `--effects=goldfish…` is the goldfish tab (`GoldfishStudio.kt`).
 */
internal suspend fun studioEffects(h: NeueHolders, mode: String, map: Map<String, String>, clock: FrameClock) {
    h.neue.update {
        it.copy(ai = it.ai.copy(
            enabled = true,
            connections = listOf(AiConnection("anthropic-demo", "anthropic", "Anthropic", "claude-sonnet-5-5")),
            active = "anthropic-demo",
        ))
    }
    val deck = h.builder.deck
    val index = h.builder.index
    val cards = (deck.main + deck.extra).mapNotNull { index.byId(it) }.distinctBy { it.id }
    val effectful = cards.filter { it.description.isNotBlank() && !it.type.contains("Normal Monster") }
    val monsters = effectful.filter { it.type.contains("Monster") && !it.type.contains("Link") && !it.type.contains("XYZ") && !it.type.contains("Fusion") && !it.type.contains("Synchro") }
    val clean = monsters.getOrNull(0)
    val warned = monsters.getOrNull(1)
    val broken = monsters.getOrNull(2)
    val dir = File(Platform.dataDir, FxPaths.FOLDER).apply { mkdirs() }
    // Each picture starts from the same asked list: the studio's data folder outlives a run.
    File(dir, FxPaths.ASKED).delete()
    // Our own scripts, written for the picture: a search, the same with its once-per-turn left out, and one whose ref nothing binds.
    clean?.let { c ->
        File(dir, FxPaths.js(c.id.value)).writeText(
            "fx.card(${c.id.value}, {\n  effects: [\n    fx.ignition('e1', { label: 'Draw', from: 'monsters', opt: fx.opt.perCopy(), does: [ fx.draw(1) ] }),\n  ],\n})\n",
        )
    }
    warned?.let { c ->
        File(dir, FxPaths.js(c.id.value)).writeText(
            "fx.card(${c.id.value}, {\n  effects: [\n    fx.ignition('e1', { label: 'Mill', from: 'monsters', does: [ fx.send({ from: 'your deck', where: fx.monster() }) ] }),\n  ],\n})\n",
        )
    }
    broken?.let { c ->
        File(dir, FxPaths.js(c.id.value)).writeText(
            "fx.card(${c.id.value}, { effects: [ fx.ignition('e1', { from: 'monsters', does: [ fx.destroy({ ref: 'nobody' }) ] }) ] })\n",
        )
    }
    h.effects.reloadNow()
    // The asked list: each card asked from somewhere, and what writing it cost on the demo connection.
    val price = Prices.of("anthropic", "claude-sonnet-5-5")
    val model = "anthropic/claude-sonnet-5-5"
    val now = System.currentTimeMillis()
    listOfNotNull(clean to FxFrom.VIEWER, warned to FxFrom.PANE, broken to FxFrom.CHAT).forEachIndexed { i, (c, from) ->
        c ?: return@forEachIndexed
        val r = FxRequest("studio-$i", now - (3 - i) * 3_600_000L, from, "${c.name}'s effect", h.builder.deckId, listOf(c.id.value))
        h.effects.go(r, FxReviews.PERSON)
    }
    listOfNotNull(clean, warned, broken).forEachIndexed { i, c ->
        h.effects.begin("studio", FxRequest("studio-$i", cards = listOf(c.id.value)), Usage(), model, price)
        h.effects.checked(c.id.value, "studio", Usage(input = 24_000L + i * 7_000L, output = 3_500L + i * 600L))
    }
    h.effects.ended("studio")
    clock.run(20)
    println("[neue-studio] effects: ${h.effects.entries.size} in the library, ${h.effects.asked.asks.size} asked")
    when (mode) {
        "pane" -> {
            studioWorld(h, map)
            h.neue.page = Page.WORLD
            clock.run(60)
            val world = h.world
            world.desk.open(BuiltInApp.EFFECTS.ref)
            clock.run(10)
            if (map["effects-window"] != "normal") world.desk.apply(DeskOp.ToggleMaximise(BuiltInApp.EFFECTS.id, System.currentTimeMillis()))
            clock.run(90)
        }
        "request" -> {
            // Ai answered "write my engine's effects" with fx_request: the card, not yet answered.
            val asked = effectful.drop(3).take(5).map { it.id.value } + listOfNotNull(clean?.id?.value, warned?.id?.value)
            val offer = h.offerFor(asked, "${h.builder.deckName}'s engine", h.builder.deckId, "studio-offer")
            val content = FxOffers.words(offer) { c -> index.byId(CardId(c))?.name ?: c.toString() } + "\n" + FxOffers.embed(offer)
            val input = JsonObject(mapOf("deck_id" to JsonPrimitive("open"), "scope" to JsonPrimitive("engine"), "cards" to JsonArray(emptyList())))
            h.ai.preview(
                AiSession(
                    id = "studio-fx-request",
                    title = "Write my engine's effects",
                    connection = "anthropic-demo",
                    turns = listOf(
                        ChatTurn.user("Can you write the effects for my engine so the table can play them?"),
                        ChatTurn(Role.ASSISTANT, listOf(Part.Text("Here is what that would take."), Part.ToolUse("r1", "fx_request", input))),
                        ChatTurn(Role.USER, listOf(Part.ToolResult("r1", "fx_request", content, summary = "Offered ${offer.toWrite.size} cards to write"))),
                        ChatTurn.assistant("Five are new and two already written need a repair. Press **Write** when you are ready, and I'll write them one at a time in Ai World."),
                    ),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
            clock.run(60)
        }
        "viewer" -> {
            val card = effectful.firstOrNull { h.effects.status(it.id.value) == FxStatus.MISSING } ?: effectful.firstOrNull()
            card?.let { h.neue.viewing = Viewing(it, null, 0) }
            clock.run(60)
        }
        // --effects=goldfish|goldfish-result|goldfish-target|goldfish-replay (Phase D step 4, `GoldfishStudio.kt`).
        else -> if (mode.startsWith("goldfish")) studioGoldfish(h, mode, map, clock, clean, warned, effectful)
    }
}
