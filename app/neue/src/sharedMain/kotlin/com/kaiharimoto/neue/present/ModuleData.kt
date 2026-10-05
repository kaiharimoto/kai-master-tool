package com.kaiharimoto.neue.present

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.deck.GroupStats
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.present.DeckSnapshot
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.modules.GroupOdds
import com.kaiharimoto.mastertool.core.present.modules.MatchRow
import com.kaiharimoto.mastertool.core.present.modules.ModuleInput
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.modules.RoundRow
import com.kaiharimoto.mastertool.core.present.modules.SideMatchup
import com.kaiharimoto.mastertool.core.present.modules.SideTurn
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.NeueHolders

/**
 * What the modules read from the rest of the app (1.0.71): the deck's siding plans, the practice
 * log and an event from Prep, the deck's numbers. The same gathering serves the module dialog,
 * a module's Refresh, and Ai's `add_module`.
 */
internal object ModuleData {
    fun deckOf(s: DeckSnapshot): Deck = Deck(s.main.map(::CardId), s.extra.map(::CardId), s.side.map(::CardId))

    fun groupsOf(s: DeckSnapshot): DeckGroups = DeckGroups(
        s.groups.map { DeckGroup(it.id, it.name, it.color, it.order) },
        s.assignments.mapKeys { CardId(it.key) },
    )

    /** Every matchup sided for the presentation's deck, by the saved deck's siding plans. */
    suspend fun matchups(h: NeueHolders, p: Presentation): List<SideMatchup> {
        val id = p.deck?.deckId ?: return emptyList()
        val stored = h.deps.deckRepository.byId(id) ?: return emptyList()
        return SidingCodec.read(stored.extended).matchups.map { m ->
            SideMatchup(
                m.name,
                SideTurn(m.first.out.map { it.value }, m.first.into.map { it.value }, m.first.note),
                SideTurn(m.second.out.map { it.value }, m.second.into.map { it.value }, m.second.note),
                m.note,
                m.covers.map { it.value },
            )
        }
    }

    /** The practice record of the presentation's deck, and the match win to expect against its event's field. */
    suspend fun practice(h: NeueHolders, p: Presentation): Pair<List<MatchRow>, Double?> {
        val doc = h.deps.preferencesRepository.loadPrep()
        val deckId = p.deck?.deckId
        val own = deckId?.let { h.deps.deckRepository.byId(it)?.entry?.name }
        val games = TestStats.mirrored(doc.games.filter { it.round == null && (deckId == null || it.deckId == deckId) }, deckId, listOfNotNull(own))
        val rows = TestStats.matrix(games, deckId)
        val event = doc.events.firstOrNull { it.deckId == deckId && it.webId != null } ?: doc.activeEvent
        val web = event?.webId?.let { h.webs.library.byId(it) }
        // Every deck of the field at its share, the person's own as the mirror (Phase B, TestStats.field).
        val shares = TestStats.field(web?.entries.orEmpty())
        val expected = if (rows.isEmpty()) null else if (shares.values.sum() > 0) TestStats.expected(rows, shares) else null
        return rows.map { r ->
            MatchRow(
                r.name, r.first.pct, r.first.games, r.second.pct, r.second.games, r.preSide.pct, r.preSide.games,
                r.postSide.pct, r.postSide.games, r.all.pct, r.all.games, shares[r.opponent],
            )
        } to expected
    }

    /** Prep's events, newest first. */
    suspend fun events(h: NeueHolders): List<PrepEvent> = h.deps.preferencesRepository.loadPrep().events.sortedByDescending { it.date }

    /** An event's rounds and record. */
    suspend fun tournament(h: NeueHolders, eventId: String): ModuleInput {
        val doc = h.deps.preferencesRepository.loadPrep()
        val e = doc.event(eventId) ?: return ModuleInput()
        val rounds = doc.games.filter { it.eventId == e.id && it.round != null }.sortedBy { it.round }
        val w = rounds.count { it.result == TestGame.WIN }
        val l = rounds.count { it.result == TestGame.LOSS }
        val d = rounds.count { it.result == TestGame.DRAW }
        return ModuleInput(
            title = e.name,
            subtitle = listOfNotNull(e.date, e.attendance.takeIf { it > 0 }?.let { "$it players" }, "Tier ${e.tier}").joinToString(" · "),
            rounds = rounds.map { g -> RoundRow(g.round ?: 0, g.opponentName, g.note.ifBlank { resultWord(g.result) }) },
            record = if (d > 0) "$w–$l–$d" else "$w–$l",
            params = mapOf("event" to e.id),
        )
    }

    private fun resultWord(r: String) = when (r) {
        TestGame.WIN -> "Won"
        TestGame.LOSS -> "Lost"
        else -> "Draw"
    }

    /** How often each group opens, from the presentation's deck. */
    fun odds(h: NeueHolders, p: Presentation): List<GroupOdds> {
        val s = p.deck ?: return emptyList()
        val report = GroupStats.of(deckOf(s), groupsOf(s), { id -> h.builder.index.byId(id) })
        return report.groups.map { g -> GroupOdds(g.name, g.opening, g.openingSecond, g.main + g.extra + g.side, g.color) }
    }

    fun code(p: Presentation): String = p.deck?.let { YdkeCodec.encode(deckOf(it)) }.orEmpty()

    /** The input a data module is made from now; a module picked by hand keeps the input it has. */
    suspend fun gather(h: NeueHolders, p: Presentation, type: String, base: ModuleInput = ModuleInput()): ModuleInput = when (type) {
        Modules.SIDING -> {
            val all = matchups(h, p)
            val wanted = base.params["matchups"]?.split('\n')?.filter { it.isNotBlank() }?.toSet()
            base.copy(matchups = if (wanted.isNullOrEmpty()) all else all.filter { it.name in wanted })
        }
        Modules.MATCHUPS -> practice(h, p).let { (rows, expected) -> base.copy(rows = rows, expected = expected) }
        Modules.TOURNAMENT -> base.params["event"]?.let { tournament(h, it).copy(placement = base.placement) } ?: base
        Modules.ODDS, Modules.RATIOS -> base.copy(odds = odds(h, p))
        Modules.GET_THE_DECK -> base.copy(code = code(p))
        Modules.DECKLIST -> base.copy(title = base.title.ifBlank { p.deck?.name.orEmpty() })
        else -> base
    }

    /**
     * Slide [slide], a module's, made again from what its data says now — or why it cannot be. A siding
     * slide follows its own matchup, never its title (B2, `Modules.refreshed`). Decklist and Get the deck
     * read the presentation's snapshot, which "Refresh from the saved deck" takes again first (I3).
     */
    suspend fun refreshed(h: NeueHolders, p: Presentation, slide: Slide): Modules.Refreshed {
        val ref = slide.module ?: return Modules.Refreshed.Gone("This slide was not made by a module.")
        val input = gather(h, p, ref.type, Modules.inputOf(ref))
        return Modules.refreshed(slide, Modules.generate(ref.type, input, System.currentTimeMillis()))
    }

    /** What Refresh does for a slide [type] made, said on the Slide tab. */
    fun refreshHelp(type: String): String = when (type) {
        Modules.DECKLIST, Modules.GET_THE_DECK, Modules.ODDS, Modules.RATIOS ->
            "Refresh makes it again from this presentation's copy of the deck; what you changed by hand stays. " +
                "If the saved deck changed, press Refresh from the saved deck on the Deck tab first."
        Modules.SIDING -> "Refresh makes it again from this matchup's newest siding plan; what you changed by hand stays."
        else -> "Refresh makes it again from the newest data; what you changed by hand stays."
    }

    /** [refreshed]'s slide, or null when it could not be made again. */
    suspend fun refresh(h: NeueHolders, p: Presentation, slide: Slide): Slide? =
        (refreshed(h, p, slide) as? Modules.Refreshed.Made)?.slide
}
