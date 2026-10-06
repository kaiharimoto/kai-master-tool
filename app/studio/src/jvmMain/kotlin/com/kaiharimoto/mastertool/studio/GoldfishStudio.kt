package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardPlace
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishBrowse
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishSetup
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandEnd
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandPick
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.NeedKind
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Needed
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TargetDraft
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.EffectsTab
import com.kaiharimoto.neue.effects.GoldfishRuns
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.effects.openGoldfishHand
import com.kaiharimoto.neue.platform.Platform
import java.io.File

/**
 * The goldfish in the Effects app, photographed (Phase D step 4, agent (c)): on the library `--effects=…` seeds, our own
 * scripts for a few of the builder deck's cards — a monster that searches a Spell when Normal Summoned, a Spell that Special
 * Summons a monster from the Deck, a Trap that destroys (an interruption once Set), the warned card's mill — and two end
 * boards, the person's and Ai's.
 *
 * - `--effects=goldfish`: the end boards and a 20,000-hand run under way (progress, Stop);
 * - `--effects=goldfish-result`: a run seen through, an earlier one kept; `--goldfish-pick=line|reached|no-line|inert` lists
 *   those hands;
 * - `--effects=goldfish-target`: the person's end board in the editor, the field's picker open;
 * - `--effects=goldfish-replay`: a reached hand opened on the Duel page as a replay, not yet kept.
 *
 * `--goldfish-hands=N` and `--goldfish-seed=N` set the run.
 */
internal suspend fun studioGoldfish(h: NeueHolders, mode: String, map: Map<String, String>, clock: FrameClock, clean: Card?, warned: Card?, effectful: List<Card>) {
    if (h.builder.deckId == null) {
        h.builder.save()
        clock.run(20)
    }
    val deckId = h.builder.deckId ?: run {
        println("[neue-studio] goldfish: the deck could not be saved")
        return
    }
    val dir = File(Platform.dataDir, FxPaths.FOLDER).apply { mkdirs() }
    // The searcher: a monster Normal Summoned without a Tribute (Level 4 or lower), the clean card when it is one.
    val clean = (listOfNotNull(clean) + effectful).firstOrNull { c ->
        c.type.contains("Monster") && (c.level ?: 0) in 1..4 && c != warned && listOf("Fusion", "Synchro", "XYZ", "Link").none { c.type.contains(it) }
    }
    val spell = effectful.firstOrNull { it.type.contains("Spell") && (it.race == "Normal" || it.race == "Quick-Play") }
    val trap = effectful.firstOrNull { it.type.contains("Trap") && it.race == "Normal" }
    // Our own scripts, for the picture: nothing here is a card's real text.
    clean?.let { c ->
        File(dir, FxPaths.js(c.id.value)).writeText(
            "fx.card(${c.id.value}, {\n  effects: [\n    fx.trigger('e1', { label: 'Search', on: fx.on.normalSummoned(), opt: fx.opt.byName(),\n" +
                "      does: [ fx.add({ from: 'your deck', where: ${trap?.let { "fx.name(${it.id.value})" } ?: "fx.spell()"} }) ] }),\n  ],\n})\n",
        )
    }
    spell?.let { c ->
        File(dir, FxPaths.js(c.id.value)).writeText(
            "fx.card(${c.id.value}, {\n  effects: [\n    fx.activation('e1', { label: 'Call', opt: fx.opt.byName(),\n" +
                "      does: [ fx.specialSummon({ from: 'your deck', where: fx.monster() }) ] }),\n  ],\n})\n",
        )
    }
    trap?.let { c ->
        File(dir, FxPaths.js(c.id.value)).writeText(
            "fx.card(${c.id.value}, {\n  effects: [\n    fx.activation('e1', { label: 'Wall', from: 'spell_zone', targets: [ fx.pick({ from: 'their monsters', bind: 't' }) ],\n" +
                "      does: [ fx.destroy({ ref: 't' }) ] }),\n  ],\n})\n",
        )
    }
    // Each picture starts from no goldfish file: the studio's data folder outlives a run.
    File(dir, "goldfish").deleteRecursively()
    h.effects.reloadNow()
    clock.run(10)
    listOfNotNull(clean, warned, spell, trap).forEach { c -> println("[neue-studio] goldfish: ${c.name} (level ${c.level}) is ${h.effects.status(c.id.value)} ${h.effects.entry(c.id.value)?.open?.map { it.message }}") }

    val index = h.builder.index
    val name = { code: Int -> index.byId(CardId(code))?.name ?: "#$code" }
    val now = System.currentTimeMillis()
    val two = TargetDraft(name = "Two monsters on the field")
        .add(BoardPlace.FIELD, Needed.Kind(NeedKind.MONSTER))
        .let { it.count(BoardPlace.FIELD, Needed.Kind(NeedKind.MONSTER), 2) }
        .board("t-two", deckId, EndBoard.PERSON, now, name)
    val ai = clean?.let { c ->
        TargetDraft(name = "${c.name} + an interruption")
            .add(BoardPlace.FIELD, Needed.Card(c.id.value))
            .copy(interruptions = 1)
            .board("t-ai", deckId, EndBoard.AI, now - 60_000, name)
    }
    // The person's: the searcher on the field and the card it found Set beside it.
    val set = clean?.let { c ->
        TargetDraft(name = "${c.name} + a set card")
            .add(BoardPlace.FIELD, Needed.Card(c.id.value))
            .copy(set = 1)
            .board("t-set", deckId, EndBoard.PERSON, now + 1, name)
    } ?: two
    h.effects.putTarget(deckId, two)
    ai?.let { h.effects.putTarget(deckId, it) }
    if (set !== two) h.effects.putTarget(deckId, set)
    val runs = h.effects.goldfishRuns
    runs.tab = EffectsTab.GOLDFISH
    runs.targetId = set.id
    runs.seedText = map["goldfish-seed"] ?: "7"

    // The Effects app in Ai World, maximised.
    studioWorld(h, map)
    h.neue.page = Page.WORLD
    clock.run(60)
    h.world.desk.open(BuiltInApp.EFFECTS.ref)
    clock.run(10)
    if (map["effects-window"] != "normal") h.world.desk.apply(DeskOp.ToggleMaximise(BuiltInApp.EFFECTS.id, System.currentTimeMillis()))
    clock.run(30)

    val hands = map["goldfish-hands"]?.toIntOrNull() ?: 500
    when (mode) {
        "goldfish" -> {
            runs.start(GoldfishSetup(h.goldfishDeck(), set, true, 20_000, runs.seed), h.goldfishKit())
            repeat(25) {
                Thread.sleep(120)
                clock.run(2)
            }
            println("[neue-studio] goldfish: running, ${runs.progress?.let(GoldfishBrowse::progressWords)}")
            clock.run(10)
        }
        "goldfish-target" -> {
            runs.editing = GoldfishRuns.Editing(set.id, TargetDraft.of(set), EndBoard.PERSON, picking = BoardPlace.FIELD)
            clock.run(60)
        }
        "goldfish-result", "goldfish-replay" -> {
            // An earlier run, kept, for the list under the result.
            runs.start(GoldfishSetup(h.goldfishDeck(), set, false, 200, 3), h.goldfishKit())?.join()
            runs.keep()
            clock.run(10)
            runs.start(GoldfishSetup(h.goldfishDeck(), set, true, hands, runs.seed), h.goldfishKit())?.join()
            clock.run(30)
            val r = runs.shown?.result
            println("[neue-studio] goldfish: ${r?.reached} of ${r?.hands} reached, ${r?.lines?.size} lines, ${r?.ms} ms; ${r?.unknown?.size} inert")
            if (mode == "goldfish-result") {
                when (map["goldfish-pick"]) {
                    "line" -> runs.pick(HandPick.Line(0))
                    "reached" -> runs.pick(HandPick.End(HandEnd.REACHED))
                    "no-line" -> runs.pick(HandPick.End(HandEnd.NO_LINE))
                    "inert" -> runs.pick(HandPick.HeldUnknown)
                }
                clock.run(60)
            } else {
                val k = r?.let { GoldfishBrowse.hands(it, HandPick.End(HandEnd.REACHED)).firstOrNull()?.index }
                if (k != null) h.openGoldfishHand(k)?.join()
                clock.run(30)
                // A step into its line, so the table shows the line under way.
                repeat((map["goldfish-steps"] ?: "1").toInt()) { h.duel.step(ReplayUnit.GROUP, 1) }
                clock.run(90)
                println("[neue-studio] goldfish: replay ${h.duel.replay?.record?.name} at ${h.duel.replay?.at} of ${h.duel.replay?.record?.entries?.size}")
            }
        }
    }
}
