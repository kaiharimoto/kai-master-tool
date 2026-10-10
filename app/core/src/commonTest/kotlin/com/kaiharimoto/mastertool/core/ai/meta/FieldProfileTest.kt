package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The field's interaction, your list against a strategy's, and honest shares (Phase G, G.5). */
class FieldProfileTest {
    private val ash = Card(
        id = CardId(1), name = "Ash", type = "Effect Monster", frameType = "effect", attribute = Attribute.FIRE,
        description = "When a card or effect is activated that includes this effect (Quick Effect): You can discard this card; negate that effect.",
    )
    private val veil = Card(
        id = CardId(2), name = "Veiler", type = "Effect Monster", frameType = "effect", attribute = Attribute.LIGHT,
        description = "During your opponent's Main Phase (Quick Effect): You can send this card from your hand to the GY; that monster's effects are negated.",
    )
    private val solemn = Card(
        id = CardId(3), name = "Solemn", type = "Trap Card", frameType = "trap",
        description = "When your opponent would Summon a monster: Pay 1500 LP; negate the Summon, and if you do, destroy that card.",
    )
    private val filler = (10..60).associate { CardId(it) to Card(id = CardId(it), name = "Filler $it", type = "Effect Monster", frameType = "effect", description = "Draw 1 card.", attribute = Attribute.DARK) }
    private val cards: (CardId) -> Card? = { id -> listOf(ash, veil, solemn).firstOrNull { it.id == id } ?: filler[id] }

    private fun td(number: Int, name: String, main: List<Int>, side: List<Int> = emptyList(), placement: String = "Top 8", players: Int = 64, event: String = "Event $number", ago: Int = 3) =
        TournamentDeck(number, name, event, placement, players, null, DeckFormat.TCG, ago, 2, Deck(main.map(::CardId), emptyList(), side.map(::CardId)), "")

    private val engineA = (10..36).toList()
    private val engineB = (37..60).toList()

    private fun close(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) < tol, "$a against $b")

    @Test
    fun aStrategysInteractionIsCountedPerListAndWeighted() {
        // Strategy A: 3 Ash, 2 Veiler and 2 Solemn in 40; B: none. Each list's chance is exact, then weighted.
        val aMain = engineA + List(3) { 1 } + List(2) { 2 } + List(2) { 3 } + listOf(10, 11, 12, 13, 14, 15)
        val bMain = engineB + (10..25).toList()
        val a = FieldCluster("A", 60, listOf(td(1, "A", aMain.take(40), side = listOf(2, 2, 3)), td(2, "A", aMain.take(40))), td(1, "A", aMain), emptyList())
        val b = FieldCluster("B", 40, listOf(td(3, "B", bMain.take(40), side = listOf(1, 1, 1))), td(3, "B", bMain), emptyList())
        val profile = FieldProfiles.of(listOf(a, b), cards) { 1.0 }
        val pa = profile.strategies.first { it.name == "A" }
        val k = aMain.take(40).count { it in 1..3 }
        close(FieldProfiles.atLeast(40, k, 5, 1), pa.opens.one5)
        close(FieldProfiles.atLeast(40, k, 6, 2), pa.opens.two6)
        assertEquals(listOf(CardId(1), CardId(2)), pa.handTraps.map { it.card })
        assertEquals(listOf(CardId(3)), pa.negates.map { it.card }, "a negate that is no hand trap")
        close(3.0, pa.handTraps.first().mean)
        // Half of A's lists side Veiler, twice.
        val sideVeil = pa.side.first { it.card == CardId(2) }
        close(0.5, sideVeil.share); close(2.0, sideVeil.mean)
        // The field: A's chance at 60 %, B's (none) at 40 %.
        close(0.6 * pa.opens.one5, profile.field.one5)
        assertTrue(FieldProfiles.atLeast(40, 3, 5, 1) in 0.33..0.34, "three outs in forty, five cards: about 33.8 %")
    }

    @Test
    fun yourListAgainstTheStrategysListsSaysWhatDiffers() {
        val core = (10..39).toList()
        val lists = listOf(
            td(1, "A", core + List(3) { 1 } + List(3) { 2 } + listOf(40, 41, 42, 43)),
            td(2, "A", core + List(3) { 1 } + List(2) { 2 } + listOf(40, 41, 42, 43, 44)),
            td(3, "A", core + List(3) { 1 } + List(3) { 2 } + listOf(40, 41, 42, 45)),
            td(4, "A", core + List(2) { 1 } + List(3) { 2 } + listOf(40, 41, 42, 43, 46)),
        )
        val mine = Deck((core + List(3) { 2 } + List(1) { 3 } + listOf(40, 41, 42, 43, 47, 48)).map(::CardId))
        val r = StrategyRatios.of("A", lists, mine, cards) { 1.0 }
        val ashRow = r.of(CardId(1))!!
        close(1.0, ashRow.share); assertEquals(3, ashRow.mode); assertEquals(0, ashRow.yours)
        assertTrue(ashRow in r.missing, "the field plays Ash and this list does not")
        val veilRow = r.of(CardId(2))!!
        assertEquals(3, veilRow.mode)
        assertTrue(veilRow !in r.counts, "the same count as most lists")
        assertTrue(r.techs.any { it.card == CardId(3) }, "a card no list plays is a tech")
        assertEquals("3 in 100% of 4 lists", StrategyRatios.line(ashRow, r.lists))
        // The consensus: every card most lists play, at its most common count, filled to forty.
        val consensus = r.consensus()
        assertTrue(consensus.main.size >= 40, "${consensus.main.size}")
        assertEquals(3, consensus.main.count { it == CardId(1) })
        assertTrue(consensus.main.groupingBy { it }.eachCount().values.all { it <= 3 })
        assertEquals(DeckSection.MAIN, ashRow.section)
    }

    @Test
    fun anEventsBudgetIsSharedSoADeepCutCountsAsMuchAsATopEight() {
        // Two events of the same size: one published 16 lists, the other 4. Each event's lists weigh the same in all.
        val deep = (1..16).map { td(it, "A", engineA, placement = if (it == 1) "Winner" else "Top 16", event = "Big", players = 128) }
        val small = (17..20).map { td(it, "B", engineB, placement = if (it == 17) "Winner" else "Top 4", event = "Other", players = 128) }
        val all = deep + small
        val budget = FieldShares.weigher(all, FieldShares.Weighting.BUDGET)
        close(deep.sumOf(budget), small.sumOf(budget), 1e-9)
        val old = FieldShares.weigher(all, FieldShares.Weighting.RESULTS)
        assertTrue(deep.sumOf(old) > 2 * small.sumOf(old), "the old weighting let the deep cut outweigh")
        // A list a half-life older weighs half.
        val young = td(30, "C", engineA, event = "Y", ago = 0)
        val aged = td(31, "C", engineA, event = "Z", ago = FieldShares.HALF_LIFE_DAYS)
        val w = FieldShares.weigher(listOf(young, aged), FieldShares.Weighting.BUDGET)
        close(0.5, w(aged) / w(young))
    }

    @Test
    fun aTrendIsTheSameStrategysPresenceInTwoWindows() {
        val older = (1..10).map { td(it, if (it <= 8) "A" else "B", if (it <= 8) engineA else engineB).copy(day = "2026-08-01") }
        val newer = (11..20).map { td(it, if (it <= 13) "A" else "B", if (it <= 13) engineA else engineB).copy(day = "2026-09-20") }
        val trend = FieldShares.trend(older, newer, cards, banlistDays = listOf("2026-07-01", "2026-09-01", "2026-10-01"))
        val a = trend.rows.first { it.name == "A" }
        assertEquals(8, a.before); assertEquals(3, a.after)
        close(-50.0, a.change, 1e-9)
        assertTrue(a.range.first < -50 && a.range.second > -50 && a.range.second < 0, "${a.range}")
        assertTrue(a.moved)
        assertEquals(listOf("2026-09-01"), trend.banlists, "only the list between the windows is marked")
    }
}
