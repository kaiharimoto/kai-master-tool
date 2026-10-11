package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.duel.match.series.SeriesReading
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesWords
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesCoverage
import com.kaiharimoto.mastertool.core.duel.match.series.OpponentRun
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesRun
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesGame
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesMatch
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.compare.CompareAsk
import com.kaiharimoto.mastertool.core.duel.mapper.compare.RatioSearch
import com.kaiharimoto.mastertool.core.duel.mapper.compare.DeckChange
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Variants
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSet
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView
import com.kaiharimoto.neue.mapper.MapperShow
import com.kaiharimoto.neue.mapper.MapperTab
import com.kaiharimoto.neue.mapper.fieldHold
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
 * - `--mapper-compare=result`: Compare with… open on a comparison (Phase G); `--mapper-without=true`: each card without it;
 * - `--mapper-tab=stress` (Phase E, E.1): the Stress tab, the suite our own Ash- and Veiler-like scripts, tested in memory;
 *   `--mapper-stress-select=none` with no starter's line open, `--mapper-stress-empty=true` the tab before any set.
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
    // The stress picture needs a search Ash can answer: with no Spell to fetch, the searcher fetches the Trap.
    val fetched = spell ?: trap.takeIf { map["mapper-tab"] == "stress" }
    write(searcher) { id ->
        "fx.card($id, {\n  effects: [\n    fx.trigger('e1', { label: 'Search', on: fx.on.normalSummoned(), opt: fx.opt.byName(),\n" +
            "      does: [ fx.add({ from: 'your deck', where: ${fetched?.let { "fx.name(${it.id.value})" } ?: "fx.spell()"} }) ] }),\n  ],\n})\n"
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
    // The stress tests' answers (`--mapper-tab=stress`): our own scripts for two of the pool's hand traps, for the picture.
    val stressing = map["mapper-tab"] == "stress"
    val ash = if (stressing) index.search("Ash Blossom & Joyous Spring", limit = 1).cards.firstOrNull() else null
    val veiler = if (stressing) index.search("Effect Veiler", limit = 1).cards.firstOrNull() else null
    write(ash) { id ->
        "fx.card($id, {\n  effects: [\n    fx.quick('e1', { label: 'Answer', from: 'hand', opt: fx.opt.byName(), respond: fx.respond({ seat: 'them', includes: ['search'] }),\n" +
            "      cost: [ fx.discard({ ref: 'self' }) ], does: [ fx.negate({ what: 'effect' }) ] }),\n  ],\n})\n"
    }
    write(veiler) { id ->
        "fx.card($id, {\n  effects: [\n    fx.quick('e1', { label: 'Veil', from: 'hand', condition: fx.cond.theirTurn(), cost: [ fx.discard({ ref: 'self' }) ],\n" +
            "      targets: [ fx.pick({ from: 'their monsters', where: fx.faceUp(), bind: 't' }) ], does: [ fx.negateEffects({ ref: 't' }) ] }),\n  ],\n})\n"
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
    // G2.1: a comparison by simulated matches, made up for the picture (the series themselves are SeriesPlayTest's), shown in
    // "Compare with…" set to simulated matches.
    if (map["mapper-series"] == "result") {
        fun match(seed: Long, opener: Int, winner: Int?) = SeriesMatch(seed, opener, listOf(SeriesGame(opener, winner, "lp", 6), SeriesGame(1 - opener, winner, "lp", 7)), winner)
        fun matches(wins: List<Int?>) = wins.mapIndexed { i, w -> match(i / 2L, i % 2, w) }
        val run = SeriesRun(
            "studio-series", h.builder.deckId.orEmpty(), "Cut 1 Ash Blossom, add 1 Infinite Impermanence", h.builder.deckName, "${h.builder.deckName} with Impermanence",
            "", "", 8, "search-v0", "Fast",
            listOf(
                OpponentRun("o1", "Snake-Eye Fire King", 45.0, matches(listOf(0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1, 0, 1, 1, 1)), matches(listOf(0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1))),
                OpponentRun("o2", "Yubel", 35.0, matches(listOf(0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0)), matches(listOf(0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0))),
                OpponentRun("o3", "Tenpai Dragon", 20.0, matches(listOf(1, 1, 0, 1, 1, 0, 1, 1)), matches(listOf(1, 1, 0, 1, 0, 0, 1, 1))),
            ),
            listOf(SeriesCoverage("d", h.builder.deckName, 31, 40), SeriesCoverage("o1", "Snake-Eye Fire King", 12, 40), SeriesCoverage("o2", "Yubel", 22, 40), SeriesCoverage("o3", "Tenpai Dragon", 9, 40)),
            1L, false,
        )
        h.series.put(run)
        h.series.showing = run.id
        m.comparing = true
        println("[neue-studio] mapper: series ${SeriesWords.headline(SeriesReading.of(run))}")
    }
    // G2.4: the ratios searched, with the field's Ash (made up for the picture: 61 % of lists) in the other seat's hand.
    when (map["mapper-ratios"]) {
        "setup" -> {
            ash?.let { m.demoSuite(listOf(StressSet(listOf(h.goldfishKit().canonical(it.id.value)), why = "played by 61 % of the field", share = 0.61))) }
            m.searchingRatios = true
        }
        "result" -> {
            val kit = h.goldfishKit()
            val deck = h.goldfishDeck()
            ash?.let { m.demoSuite(listOf(StressSet(listOf(kit.canonical(it.id.value)), why = "played by 61 % of the field", share = 0.61))) }
            val held = fieldHold(h)?.takeIf { it.sets.isNotEmpty() }
            val swaps = RatioSearch.swaps(deck, kit, most = 4)
            val t0 = System.nanoTime()
            m.demoRatios(deck, kit, swaps, CompareAsk.interruptions(1), held, hands = 60, budget = 6_000)
            println("[neue-studio] mapper: ratios ${swaps.size} swaps, held ${held?.chance} in ${(System.nanoTime() - t0) / 1_000_000} ms: ${m.ratios?.result?.calls}")
        }
    }
    if (map["mapper-without"] == "true") {
        m.demoWithout(h.goldfishDeck(), h.goldfishKit(), CompareAsk.ANY, hands = 60, budget = 6_000)
        println("[neue-studio] mapper: without ${m.without?.rows?.size} cards")
    }
    if (stressing) {
        val kit = h.goldfishKit()
        val sets = listOfNotNull(
            ash?.let { StressSet(listOf(kit.canonical(it.id.value)), why = "played by 61 % of the field", share = 0.61) },
            if (ash != null && veiler != null) StressSet(listOf(kit.canonical(ash.id.value), kit.canonical(veiler.id.value)).sorted(), by = StressSet.AI, why = "the field's two most played answers together") else null,
        )
        val t0 = System.nanoTime()
        m.demoStress(h.goldfishDeck(), kit, sets, budget = 6_000)
        println("[neue-studio] mapper: stressed ${sets.size} sets in ${(System.nanoTime() - t0) / 1_000_000} ms: ${m.stressed.values.map { r -> "${r.tested} boards, ${r.hands.size} starters, ${r.missing}" }}")
        m.stressed.values.firstOrNull()?.hands?.forEach { hd ->
            val row = m.starterRows().firstOrNull { it.cards == hd.starter }
            println("[neue-studio] stress: ${hd.starter.map { index.byId(CardId(it))?.name }} g=${hd.result.guarantee} of ${hd.result.unopposed} complete=${hd.result.complete} moves=${hd.result.moves} " +
                "row: complete=${row?.complete} ends=${row?.ends?.size} inLibrary=${row?.ends?.count { it in m.side.library.byKey }} best=${row?.ends?.mapNotNull { m.side.library.byKey[it]?.traits?.interruptions }?.maxOrNull()} line=${hd.result.line.map { it.move }}")
        }
        m.tab = MapperTab.STRESS
        m.stressStarter = when (map["mapper-stress-select"]) {
            "none" -> null
            else -> m.stressed.values.firstOrNull()?.hands?.let { hs -> (hs.firstOrNull { it.result.chokes.isNotEmpty() } ?: hs.firstOrNull())?.starter }
        }
        if (map["mapper-stress-empty"] == "true") m.demoStress(h.goldfishDeck(), kit, emptyList(), budget = 0)
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
