package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.shootout.store.PairPick
import com.kaiharimoto.mastertool.core.shootout.store.RoleLean
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriors
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.shootout.Shootouts
import kotlinx.coroutines.runBlocking

/**
 * `--shootout=demo` (1.1.2): Shootout's page with answers already given, so it can be photographed without a person.
 *
 * - `--shootout-target=alone|matchup`: the builder's deck on its own, or in a field of two against a K9 list built from
 *   the lab deck's own legacy siding patterns (which supply the lab's plans, read only), the K9 list siding back.
 * - `--shootout-view=trial|results|setup`; `--shootout-answers=N` (60 by default).
 * - `--shootout-teach=…` (Phase S stage 3): the teaching screens, with a calibration set, apprentice and supervised
 *   answers, Ai's solo hands and audits, notes and a rubric made up beside the demo's answers; `early` keeps only a
 *   calibration set of 12 not yet examined (the steps early on, 1.1.8). Setup with few hands, the teaching steps still
 *   hidden: `--shootout-view=setup --shootout-answers=0` (or any number under 30).
 * - `--shootout-priors=demo` (S4.3): a reading of the deck written as Ai's would be, shown on the setup;
 *   `--shootout-writeup=true` opens the write-up into the guide over the results.
 * - `--shootout-scroll=N` (S4.2): the results scrolled N pixels, for a phone's lower sections.
 * - `--shootout-plans=true` (S4.2): with `--shootout-target=matchup`, the lab deck's own plans against K9 too, so the sided
 *   situations are dealt and the results compare the plans.
 * - `--shootout-why=marks|ask|fatigue|reasons` (stage 4, S4.1): reasons on the demo's answers, and the hand marked, the
 *   surprise question or the fatigue line on the trial; with `--shootout-view=results`, the reasons counted.
 */
internal suspend fun studioShootout(h: NeueHolders, map: Map<String, String>, clock: FrameClock) {
    h.webs.load()
    clock.run(20)
    if (h.builder.deckId == null) {
        h.builder.save()
        clock.run(20)
    }
    val built = h.builder.deckId ?: error("the builder's deck did not save")
    var me = built
    var opponent: String? = null
    if (map["shootout-target"] == "matchup") {
        val stored = runBlocking { h.deps.deckRepository.byId(built) } ?: error("no saved deck")
        val web = h.webs.create("Studio field")
        var copied: String? = null
        h.webs.addFromLibrary(web.id, stored) { copied = it }
        clock.run(30)
        val lab = copied ?: error("the deck did not join the web")
        // A K9 list from the cards the legacy pattern names, three, two and one of each.
        val playable = K9.filter { h.builder.index.byId(CardId(it))?.isExtraDeck != true }
        val k9 = playable.mapIndexed { i, id -> List(if (i < 8) 3 else if (i < 14) 2 else 1) { CardId(id) } }.flatten().take(40)
        val siding = DeckSiding(
            listOf(
                Matchup(
                    id = "m-lab", name = stored.entry.name, deckId = lab,
                    first = SidePlan(out = listOf(CardId(playable[9])), into = listOf(CardId(10045474))),
                    second = SidePlan(out = listOf(CardId(playable[0]), CardId(playable[0])), into = listOf(CardId(94145021), CardId(94145021))),
                ),
            ),
        )
        var made: String? = null
        val document = YdkDocument(
            Deck(main = k9, side = listOf(94145021, 94145021, 10045474).map(::CardId)),
            extended = SidingCodec.write(null, siding),
        )
        h.webs.add(web.id, "K9 Vanquish Soul", document) { made = it }
        clock.run(30)
        me = lab
        opponent = made ?: error("the opponent did not save")
        // What the hands say (S4.2): --shootout-plans=true gives the lab deck its own plans against K9, so the sided
        // situations are dealt and the plans compared.
        if (map["shootout-plans"] == "true") {
            val main = stored.entry.deck.main
            val many = main.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.toList()
            val side = stored.entry.deck.side.ifEmpty { List(3) { CardId(14558127) } }
            val mine = DeckSiding(
                listOf(
                    Matchup(
                        id = "m-k9", name = "K9 Vanquish Soul", deckId = opponent,
                        first = SidePlan(out = List(2) { many.last() }, into = side.take(2)),
                        second = SidePlan(out = List(2) { many[many.size / 2] }, into = side.takeLast(2)),
                    ),
                ),
            )
            h.webs.saveSiding(lab, mine, h.builder)
            clock.run(30)
        }
    }
    val view = when (map["shootout-view"]) {
        "results" -> Shootouts.View.RESULTS
        "setup" -> Shootouts.View.SETUP
        else -> Shootouts.View.TRIAL
    }
    // Teaching Ai (Phase S stage 3): --shootout-teach=supervised|judging|question|calibration|solo|exam|exam-running|trust|rubric|setup|early.
    h.shootout.teaching = map["shootout-teach"]
    // Why, in your words (S4.1): --shootout-why=marks|ask|fatigue|reasons.
    h.shootout.why = map["shootout-why"]
    // The results scrolled (S4.2): --shootout-scroll=N pixels, for what a phone shows below its first screen.
    h.shootout.studioScroll = map["shootout-scroll"]?.toIntOrNull()
    // Ai's reading of the deck (S4.3): --shootout-priors=demo writes one as Ai's would be, before the demo deals.
    if (map["shootout-priors"] == "demo") {
        val stored = runBlocking { h.deps.deckRepository.byId(me) } ?: error("no saved deck")
        val cards = stored.entry.deck.main.map { it.value }.distinct()
        val roles = h.builder.groups.ordered().map { it.name }
        val steps = listOf(2, -1, 1, 0, -2)
        val whys = listOf("one card starts the engine and searches the rest", "dead going first, live only into their turn", "answers a board once it is made", "neither, by the text alone", "does nothing on its own")
        h.shootout.deckReading.studioWrite(
            me, opponent,
            ShootoutPriors(
                leans = roles.mapIndexed { i, r -> RoleLean(r, steps[i % steps.size], whys[i % whys.size], on = i != 3) },
                pairs = listOf(PairPick(cards[0], cards[1], "one searches the other"), PairPick(cards[2], cards[4], "both want the same once-a-turn summon", on = false)),
                model = "studio", at = 1_760_000_000_000L,
                summary = "Sets up Labrynth's trap engine off one starter, and wins by turning their plays into its traps' targets.",
            ),
        )
    }
    h.shootout.demo(me, opponent, map["shootout-answers"]?.toIntOrNull() ?: 60, view)
    // The write-up into the guide (S4.3): --shootout-writeup=true opens it over the results.
    if (map["shootout-writeup"] == "true") h.shootout.deckReading.offerWriteUp()
    h.neue.page = Page.SHOOTOUT
    clock.run(160)
    // Draws by effects (1.1.5): --shootout-draws=MINE,THEIRS turns that many cards up off each deck.
    map["shootout-draws"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.let { (mine, theirs) ->
        repeat(mine) { h.shootout.drawMine() }
        repeat(theirs) { h.shootout.drawTheirs() }
        clock.run(30)
    }
    val log = h.shootout.log
    println("[neue-studio] shootout: ${log?.trials?.size} trials, strata ${h.shootout.bench?.strata}, waiting ${h.shootout.bench?.waiting?.keys}, settled ${h.shootout.settled ?: h.shootout.results?.settled}")
}

/** The K9 Vanquish Soul list's cards, from the lab deck's legacy siding pattern (`sourceDeckIds`). */
private val K9 = listOf(
    29302858, 93156774, 29280200, 9091064, 92895501, 91073013, 92248362, 28642461, 47960073, 55031170,
    91025875, 91800273, 42141493, 14558128, 94145021, 54562327, 35550352, 80181649, 53792930, 84211599,
    32807846, 87126721, 84192580, 27204311, 58921041, 24207889, 10045474, 60883493,
)
