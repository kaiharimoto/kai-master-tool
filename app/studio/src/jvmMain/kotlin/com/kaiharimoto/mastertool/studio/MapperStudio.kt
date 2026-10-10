package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareAsk
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Variants
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView
import com.kaiharimoto.neue.mapper.MapperShow
import com.kaiharimoto.neue.mapper.MapperTab
import com.kaiharimoto.neue.platform.Platform
import java.io.File

/**
 * Gameplay Mapper, photographed (Phase M step M1, `--page=mapper --mapper=demo`): our own scripts for a few of the builder
 * deck's cards — a monster that searches a Spell when Normal Summoned, the Spell that Special Summons a second monster from
 * the Deck, which adds a Trap when Special Summoned, the Trap (removal once Set), and a Link-2 with a quick negate — then the
 * starter table and dealt hands mapped into memory ([com.kaiharimoto.neue.mapper.Mappers.demo]; nothing is written).
 *
 * - `--mapper-show=overview|rows|cards|map` (or M1's `--mapper-look=gallery|table|map`): what the library shows; none, the
 *   density the library's length gives;
 * - `--mapper-order=asked|often|shortest`, `--mapper-tune=true` (the weights unfolded), `--mapper-select=none|N` (no board
 *   chosen, or the Nth in the order: the inspector only for a board chosen);
 * - `--mapper-moment=first|uncounted`: the page before any board, or with boards and no hands counted;
 * - `--mapper-tab=starters`: the starter table;
 * - `--mapper-hands=N` (60), `--mapper-weights=negates:2,hand:1`: the run and the weights on screen;
 * - `--mapper-compare=result`: Compare with… open on a comparison (Phase G); `--mapper-without=true`: each card without it.
 */
internal suspend fun studioMapper(h: NeueHolders, map: Map<String, String>, clock: FrameClock) {
    if (h.builder.deckId == null) {
        h.builder.save()
        clock.run(20)
    }
    val deck = h.builder.deck
    val index = h.builder.index
    val cards = (deck.main + deck.extra).mapNotNull { index.byId(it) }.distinctBy { it.id }
    fun plainMonster(c: Card) = c.type.contains("Monster") && !c.type.contains("Normal Monster") && (c.level ?: 0) in 1..4 &&
        listOf("Fusion", "Synchro", "XYZ", "Link", "Pendulum").none { c.type.contains(it) }
    val monsters = cards.filter(::plainMonster)
    val searcher = monsters.getOrNull(0)
    val extender = monsters.getOrNull(1)
    val spell = cards.firstOrNull { it.type.contains("Spell") && (it.race == "Normal" || it.race == "Quick-Play") }
    val trap = cards.firstOrNull { it.type.contains("Trap") && it.race == "Normal" }
    val link = cards.filter { it.type.contains("Link") && (it.linkValue ?: 0) in 1..2 }.maxByOrNull { it.linkValue ?: 0 }
    val dir = File(Platform.dataDir, FxPaths.FOLDER).apply { mkdirs() }
    // Our own scripts, for the picture: nothing here is a card's real text.
    fun write(c: Card?, js: (Int) -> String) = c?.let { File(dir, FxPaths.js(it.id.value)).writeText(js(it.id.value)) }
    write(searcher) { id ->
        "fx.card($id, {\n  effects: [\n    fx.trigger('e1', { label: 'Search', on: fx.on.normalSummoned(), opt: fx.opt.byName(),\n" +
            "      does: [ fx.add({ from: 'your deck', where: ${spell?.let { "fx.name(${it.id.value})" } ?: "fx.spell()"} }) ] }),\n  ],\n})\n"
    }
    write(spell) { id ->
        "fx.card($id, {\n  effects: [\n    fx.activation('e1', { label: 'Call', opt: fx.opt.byName(),\n" +
            "      does: [ fx.specialSummon({ from: 'your deck', where: ${extender?.let { "fx.name(${it.id.value})" } ?: "fx.monster()"} }) ] }),\n  ],\n})\n"
    }
    write(extender) { id ->
        "fx.card($id, {\n  effects: [\n    fx.trigger('e1', { label: 'Set up', on: fx.on.specialSummoned(), opt: fx.opt.byName(),\n" +
            "      does: [ fx.add({ from: 'your deck', where: ${trap?.let { "fx.name(${it.id.value})" } ?: "fx.trap()"} }) ] }),\n  ],\n})\n"
    }
    write(trap) { id ->
        "fx.card($id, {\n  effects: [\n    fx.activation('e1', { label: 'Wall', from: 'spell_zone', targets: [ fx.pick({ from: 'their monsters', bind: 't' }) ],\n" +
            "      does: [ fx.destroy({ ref: 't' }) ] }),\n  ],\n})\n"
    }
    write(link) { id ->
        val n = link?.linkValue ?: 2
        "fx.card($id, {\n  summon: fx.summon({ normal: false, procs: [ fx.proc.link($n, $n, fx.monster()) ] }),\n  effects: [\n" +
            "    fx.quick('e1', { label: 'Stop', from: 'monsters', opt: fx.opt.byName(), respond: fx.respond({ seat: 'them' }),\n" +
            "      does: [ fx.negate({ link: 'answered' }) ] }),\n  ],\n})\n"
    }
    h.effects.reloadNow()
    clock.run(10)
    listOfNotNull(searcher, extender, spell, trap, link).forEach { c ->
        println("[neue-studio] mapper: ${c.name} is ${h.effects.status(c.id.value)} ${h.effects.entry(c.id.value)?.open?.map { it.message }}")
    }

    val m = h.mapper
    m.chosenShow = when (map["mapper-show"] ?: map["mapper-look"]) {
        "overview" -> MapperShow.OVERVIEW
        "b", "table", "rows" -> MapperShow.ROWS
        "c", "map", "plot" -> MapperShow.MAP
        "a", "gallery", "cards" -> MapperShow.CARDS
        else -> null
    }
    m.order = when (map["mapper-order"]) {
        "often" -> MapperView.Order.OFTEN
        "shortest" -> MapperView.Order.SHORTEST
        else -> MapperView.Order.ASKED
    }
    m.tuning = map["mapper-tune"] == "true"
    val moment = map["mapper-moment"]
    if (moment == "first") {
        m.demo(h.goldfishDeck(), h.goldfishKit(), hands = 0, budget = 0, empty = true)
    } else {
        val t0 = System.nanoTime()
        m.demo(h.goldfishDeck(), h.goldfishKit(), hands = map["mapper-hands"]?.toIntOrNull() ?: 60, budget = 6_000, counted = moment != "uncounted")
        val lib = m.side.library
        println("[neue-studio] mapper: ${lib.boards.size} boards, ${m.side.starters?.rows?.size} starters, ${m.side.run?.hands} hands in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
    val weights = map["mapper-weights"]?.split(',')?.mapNotNull { p -> p.split(':').takeIf { it.size == 2 }?.let { (k, v) -> v.toDoubleOrNull()?.let { k to it } } }?.toMap()
        ?: mapOf("interruptions" to 1.0, "negates" to 1.0, "hand" to 0.5)
    m.query = BoardPreset(weights = weights)
    m.selected = when (val sel = map["mapper-select"]) {
        "none" -> null
        null -> m.ordered().firstOrNull()?.entry?.key
        else -> m.ordered().getOrNull((sel.toIntOrNull() ?: 1) - 1)?.entry?.key
    }
    // Phase G: a comparison on the same hands (one more copy of the searcher for a card it does not play), and each card
    // without it.
    if (map["mapper-compare"] == "result" && searcher != null) {
        val a = h.goldfishDeck()
        val kit = h.goldfishKit()
        val cut = (a.main.map(kit::canonical)).firstOrNull { kit.inert(it) && kit.canonical(it) != kit.canonical(searcher.id.value) }
        val b = Variants.apply(a, listOf(DeckChange(cut, kit.canonical(searcher.id.value))), { c -> index.byId(CardId(c))?.isExtraDeck == true })
        if (b != null) {
            m.demoCompare(a, b, "Cut 1 ${cut?.let { index.byId(CardId(it))?.name } ?: "card"}, add 1 ${searcher.name}", CompareAsk.ANY, kit, hands = 120, budget = 6_000)
            println("[neue-studio] mapper: compared ${m.compared?.result?.paired}")
        }
    }
    if (map["mapper-without"] == "true") {
        m.demoWithout(h.goldfishDeck(), h.goldfishKit(), CompareAsk.ANY, hands = 60, budget = 6_000)
        println("[neue-studio] mapper: without ${m.without?.rows?.size} cards")
    }
    if (map["mapper-tab"] == "starters") {
        m.tab = MapperTab.STARTERS
        m.starter = m.starterRows().firstOrNull { it.cards.size == 2 && it.together.isNotEmpty() }?.cards ?: m.starterRows().firstOrNull()?.cards
    }
    h.neue.page = Page.MAPPER
    // The cards' art decodes off the frame thread: frames and a moment for it to land.
    repeat(12) {
        clock.run(20)
        Thread.sleep(150)
    }
}
