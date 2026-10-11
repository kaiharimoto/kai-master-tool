package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.model.CardPair
import com.kaiharimoto.mastertool.core.shootout.model.Priors
import com.kaiharimoto.mastertool.core.shootout.select.PickerSettings
import com.kaiharimoto.mastertool.core.shootout.sim.Study
import com.kaiharimoto.mastertool.core.shootout.sim.SyntheticDeck
import com.kaiharimoto.mastertool.core.shootout.store.PairPick
import com.kaiharimoto.mastertool.core.shootout.store.RoleLean
import com.kaiharimoto.mastertool.core.shootout.store.RolePick
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriors
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriorsCodec
import com.kaiharimoto.mastertool.core.shootout.teach.PriorsBrief
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase S, S4.3: Ai reads the deck before the first hand — each role's lean and the pairs fitted first, and for a deck with no
 * groups the roles themselves. Only where the ratings start: a wrong lean is answered away by the person's hands.
 */
class ShootoutPriorsTest {

    private val pool: Map<Int, Card> = buildMap {
        for (i in 1..40) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect", description = "Text of card $i."))
        for (i in 1..40) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
    }
    private val lookup: (CardId) -> Card? = { pool[it.value] }
    private val name: (Int) -> String = { pool[it]?.name ?: "#$it" }

    private val mine: Deck = Deck(main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId))
    private val theirs: Deck = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))

    private val groups = DeckGroups(
        groups = listOf(DeckGroup("g-start", "Starters", 3, 0), DeckGroup("g-brick", "Bricks", 2, 1)),
        assignments = mapOf(CardId(1001) to "g-start", CardId(1002) to "g-start", CardId(1009) to "g-brick"),
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun theBenchStartsEachRoleAtItsLeanAndFitsTheNamedPairsFirst() {
        val priors = ShootoutPriors(
            leans = listOf(RoleLean("Starters", 2, "they start the engine"), RoleLean("bricks", -2), RoleLean("Ungrouped", 1, on = false)),
            pairs = listOf(PairPick(1001, 1019, "searches it"), PairPick(1003, 1020, on = false)),
        )
        val bench = Bench.of(BenchInput(mine, lookup, groups, priors = priors))
        assertEquals(listOf("Starters", "Bricks", Bench.UNGROUPED), bench.roleNames)
        // Names read without case; a lean turned off starts level.
        assertEquals(listOf(0.8, -0.8, 0.0), bench.spec.priors.roleMeans)
        val at = bench.own.withIndex().associate { it.value to it.index }
        val named = CardPair(at.getValue(1001), at.getValue(1019))
        assertTrue(named in bench.spec.pairs, "a pair Ai named is fitted, though two one-ofs are rarely held together")
        assertFalse(CardPair(at.getValue(1003), at.getValue(1020)) in bench.spec.pairs, "a pair turned off is not")
        assertEquals(Bench.PAIRS, bench.spec.pairs.size)
        // Nothing read, every role level and the commonest pairs, as before.
        val plain = Bench.of(BenchInput(mine, lookup, groups))
        assertTrue(plain.spec.priors.roleMeans.isEmpty())
        assertFalse(named in plain.spec.pairs)
    }

    @Test
    fun aDeckWithNoGroupsTakesAisRolesAndOneWithGroupsKeepsItsOwn() {
        val priors = ShootoutPriors(
            roles = listOf(RolePick(1001, "Starters"), RolePick(1002, "Starters"), RolePick(1009, "Bricks"), RolePick(1003, "Extenders")),
            leans = listOf(RoleLean("Starters", 1)),
        )
        val bench = Bench.of(BenchInput(mine, lookup, priors = priors))
        assertTrue(bench.aiRoles)
        assertEquals(listOf("Starters", "Extenders", "Bricks", Bench.OTHER), bench.roleNames)
        assertEquals("Starters", bench.roleOf(1002))
        assertEquals(Bench.OTHER, bench.roleOf(1004), "a card Ai left out is Other")
        // The deck's own groups always win.
        val grouped = Bench.of(BenchInput(mine, lookup, groups, priors = priors))
        assertFalse(grouped.aiRoles)
        assertEquals(listOf("Starters", "Bricks", Bench.UNGROUPED), grouped.roleNames)
    }

    @Test
    fun aisAnswerIsReadAgainstTheDeck() {
        val bench = Bench.of(BenchInput(mine, lookup, groups, opponent = Opponent("o", "Yubel", theirs)))
        val text = PriorsBrief.text(bench, "Lab", name) { pool[it]?.description }
        assertTrue("Card 1 · Starters: Text of card 1." in text && "· Starters" in text && "Their deck (Yubel)" in text, text)
        assertFalse("give every card above a role" in text, "a deck with groups is only leaned")

        val good = PriorsBrief.read(
            json("""{"leans":[{"role":"starters","step":2,"why":"engine"},{"role":"Bricks","step":"-1","why":"dead"}],"pairs":[{"a":"Card 1","b":"1019","why":"searches it"}],"summary":"Combo off a starter."}"""),
            bench, name, "m", at = 5,
        )
        val p = assertIs<PriorsBrief.Read.Done>(good).priors
        assertEquals(listOf(RoleLean("Starters", 2, "engine"), RoleLean("Bricks", -1, "dead")), p.leans)
        assertEquals(listOf(PairPick(1001, 1019, "searches it")), p.pairs)
        assertEquals(bench.deckPrint, p.deckPrint)
        assertEquals("Combo off a starter.", p.summary)

        fun problem(input: String) = (PriorsBrief.read(json(input), bench, name, null, 0) as? PriorsBrief.Read.Problem)?.message
        assertNotNull(problem("""{"roles":[{"card":"Card 1","role":"A"}],"leans":[]}"""), "roles are refused when the deck has groups")
        assertNotNull(problem("""{"leans":[{"role":"Engine","step":1}]}"""), "a role the deck does not have")
        assertNotNull(problem("""{"leans":[{"role":"Starters","step":3}]}"""), "a step past 2")
        assertNotNull(problem("""{"leans":[],"pairs":[{"a":"Card 1","b":"Card 1"}]}"""), "a pair of one card")
        assertNotNull(problem("""{"leans":[],"pairs":[{"a":"Card 1","b":"Theirs 1"}]}"""), "a card that is not the deck's")

        // A deck with no groups must be given roles, two to eight of them.
        val plain = Bench.of(BenchInput(mine, lookup))
        assertTrue("give every card above a role" in PriorsBrief.text(plain, "Lab", name) { null })
        assertNotNull((PriorsBrief.read(json("""{"leans":[]}"""), plain, name, null, 0) as? PriorsBrief.Read.Problem))
        val roled = PriorsBrief.read(
            json("""{"roles":[{"card":"Card 1","role":"Starters"},{"card":"Card 9","role":"Bricks"}],"leans":[{"role":"starters","step":1}],"summary":"x"}"""),
            plain, name, null, 0,
        )
        assertEquals(listOf(RolePick(1001, "Starters"), RolePick(1009, "Bricks")), assertIs<PriorsBrief.Read.Done>(roled).priors.roles)
    }

    @Test
    fun theReadingIsKeptBesideTheTrialsAndNeverReadAsALog() {
        val p = ShootoutPriors(leans = listOf(RoleLean("Starters", 1, "x")), pairs = listOf(PairPick(1, 2)), model = "m", at = 9, summary = "s")
        val text = ShootoutPriorsCodec.encode(p)
        assertEquals(p, ShootoutPriorsCodec.decode(text))
        assertNull(ShootoutPriorsCodec.decode("not json"))
        assertEquals("shootout/deck/alone.priors.json", ShootoutPaths.priors("deck", null))
        assertTrue(ShootoutPaths.isLog("alone.json") && ShootoutPaths.isLog("opp.json"))
        assertFalse(ShootoutPaths.isLog("alone.priors.json") || ShootoutPaths.isLog("alone.rubric.md"))
        // An older build that reads every .json of the folder as a log finds no trials in it.
        assertTrue(ShootoutCodec.decode(text)?.trials.isNullOrEmpty())
    }

    /**
     * Where the ratings start, and only that (S.md §6 1): on the simulation's four made-up matchups (the adaptive picker, 160
     * hands), the cards' draw-weighted error in points. Measured — after 40 hands: none 7.57, a right reading 7.43, every role
     * leaned the wrong way 8.26; after 160: none 4.89, right 4.18, wrong 4.52. A wrong lean costs a little at first and is
     * answered away; a right one helps more as the hands go on, since the roles' averages are the hardest numbers to pin down.
     * Held with margins: right at 160 within 0.95× none, never worse early by more than 0.2; wrong at 160 within half a point
     * of none, and at 40 within 1.2.
     */
    @Test
    fun aWrongLeanWashesOutAndARightOneHelpsEarly() {
        val right = Priors(roleMeans = listOf(0.8, 0.4, 0.4, -0.4))
        val wrong = Priors(roleMeans = listOf(-0.8, -0.4, -0.4, 0.4))
        fun runs(priors: Priors) = (1L..4L).map { seed ->
            Study.run(SyntheticDeck.matchup(seed, priors = priors, winRate = 0.6), PickerSettings(), TRIALS, seed, checkEvery = 20)
        }
        fun at(rs: List<Study.Run>, n: Int) = rs.map { r -> r.checkpoints.first { it.trials == n }.cardError }.average()
        val none = runs(Priors())
        val good = runs(right)
        val bad = runs(wrong)
        val measured = "after 40: none ${at(none, 40)}, right ${at(good, 40)}, wrong ${at(bad, 40)}; after $TRIALS: none ${at(none, TRIALS)}, right ${at(good, TRIALS)}, wrong ${at(bad, TRIALS)}"
        assertTrue(at(good, TRIALS) <= 0.95 * at(none, TRIALS) && at(good, 40) <= at(none, 40) + 0.2, "a right lean helps: $measured")
        assertTrue(at(bad, TRIALS) <= at(none, TRIALS) + 0.5 && at(bad, 40) <= at(none, 40) + 1.2, "a wrong lean is answered away: $measured")
    }

    private companion object {
        const val TRIALS = 160
    }
}
