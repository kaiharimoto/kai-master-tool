package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingCodec
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
 *   answers, Ai's solo hands and audits, notes and a rubric made up beside the demo's answers.
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
    }
    val view = when (map["shootout-view"]) {
        "results" -> Shootouts.View.RESULTS
        "setup" -> Shootouts.View.SETUP
        else -> Shootouts.View.TRIAL
    }
    // Teaching Ai (Phase S stage 3): --shootout-teach=supervised|judging|question|calibration|solo|exam|exam-running|trust|rubric|setup.
    h.shootout.teaching = map["shootout-teach"]
    h.shootout.demo(me, opponent, map["shootout-answers"]?.toIntOrNull() ?: 60, view)
    h.neue.page = Page.SHOOTOUT
    clock.run(160)
    val log = h.shootout.log
    println("[neue-studio] shootout: ${log?.trials?.size} trials, strata ${h.shootout.bench?.strata}, waiting ${h.shootout.bench?.waiting?.keys}, settled ${h.shootout.settled ?: h.shootout.results?.settled}")
}

/** The K9 Vanquish Soul list's cards, from the lab deck's legacy siding pattern (`sourceDeckIds`). */
private val K9 = listOf(
    29302858, 93156774, 29280200, 9091064, 92895501, 91073013, 92248362, 28642461, 47960073, 55031170,
    91025875, 91800273, 42141493, 14558128, 94145021, 54562327, 35550352, 80181649, 53792930, 84211599,
    32807846, 87126721, 84192580, 27204311, 58921041, 24207889, 10045474, 60883493,
)
