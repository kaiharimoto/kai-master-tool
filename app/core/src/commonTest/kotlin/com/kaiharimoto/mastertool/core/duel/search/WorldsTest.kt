package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The worlds a search plays in (Phase E, E.3; E.md §4.3) and the turn's search with the other seat answering from them. */
class WorldsTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }))

    /** [m] by [seat] made on [g] (its engine table [t]), every choice the first and every "you can" a yes: the duel and its table after. */
    private fun commit(g: DuelGame, t: FxTable, seat: Int, m: FxMove): Pair<DuelGame, FxTable> {
        val p = FxEngine.play(t, seat, m) { d -> if (d is Decision.YesNo) listOf(1) else listOf(0) }
        check(p is FxPlay.Done) { "$m: $p" }
        val r = g.act(p.actions, seat, fx = p.tags)
        check(r.ok) { r.problem.orEmpty() }
        return r.game to FxTable(p.state, p.fx, t.book, t.facts, t.seed)
    }

    private fun theirHand(w: World): List<Int> = w.game.state.seats[1].hand.map { w.game.state.cards.getValue(it).code }

    @Test
    fun twoDuelsThatDifferOnlyInWhatTheSeatCannotSeeDealTheSameWorlds() {
        // Hidden is hidden (rule 2): their hand is WARD and PAWN in one duel, VEIL and PAWN in the other.
        val (a, _) = FxRef.game(Side(hand = listOf(FxRef.SCOUT)), Side(hand = listOf(FxRef.WARD, FxRef.PAWN)))
        val (b, _) = FxRef.game(Side(hand = listOf(FxRef.SCOUT)), Side(hand = listOf(FxRef.VEIL, FxRef.PAWN)))
        val prior = Prior.Decklist(listOf(FxRef.WARD, FxRef.VEIL, FxRef.PAWN, FxRef.PAWN, FxRef.KNIGHT))
        val sa = Seen.of(a, 0, kit)
        val sb = Seen.of(b, 0, kit)
        assertEquals(sa.seed, sb.seed)
        assertEquals(sa.view, sb.view)
        val wa = Worlds.deal(sa, prior, 6, kit)
        val wb = Worlds.deal(sb, prior, 6, kit)
        assertEquals(wa.map { it.game.state }, wb.map { it.game.state })
        // The same table deals the same worlds again; and the worlds differ among themselves.
        assertEquals(wa.map { it.game.state }, Worlds.deal(sa, prior, 6, kit).map { it.game.state })
        assertTrue(wa.map(::theirHand).toSet().size > 1, "${wa.map(::theirHand)}")
        // The fork keeps nothing of the duel's seed.
        assertEquals(0L, sa.source.header.seed)
        // And the search decides the same in both (the red team's question: was it handed anything it could not see?).
        val da = Regret.decide(sa, prior, kit, k = 4, budget = 8_000)
        val db = Regret.decide(sb, prior, kit, k = 4, budget = 8_000)
        assertEquals(da.all.map { it.root to it.regret }, db.all.map { it.root to it.regret })
    }

    @Test
    fun aDecklistPriorFillsTheirHiddenCardsAndKeepsWhatTheyShowed() {
        val (g, _) = FxRef.game(Side(hand = listOf(FxRef.SCOUT)), Side(hand = listOf(FxRef.PAWN, FxRef.PAWN), gy = listOf(FxRef.EDICT)))
        val prior = Prior.Decklist(listOf(FxRef.WARD, FxRef.WARD, FxRef.VEIL, FxRef.EDICT))
        val worlds = Worlds.deal(g, 0, prior, 8, kit)
        for (w in worlds) {
            val hand = theirHand(w)
            assertEquals(2, hand.size)
            // The Edict they showed is out of the pool: their hand is two of Ward, Ward and Veil.
            assertTrue(hand.all { it == FxRef.WARD || it == FxRef.VEIL }, "$hand")
            assertEquals(1.0, w.weight)
        }
        // With nothing known, their hand stays blanks.
        assertTrue(Worlds.deal(g, 0, Prior.Blank, 2, kit).all { w -> theirHand(w).all { it == DuelFork.UNKNOWN } })
        // The field's interaction puts a card in a hand slot with its chance, blanks around it.
        val sure = Worlds.deal(g, 0, Prior.Interaction(mapOf(FxRef.WARD to 1.0)), 2, kit)
        assertTrue(sure.all { w -> theirHand(w) == listOf(FxRef.WARD, FxRef.WARD) })
    }

    @Test
    fun aWorldWhoseHandCouldHaveAnsweredAPassIsWeighedDown() {
        // Scout is Normal Summoned and its search goes on the chain; they pass. A world with Ward in their hand held it back.
        val (g0, t0) = FxRef.game(Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.SCOUT)), Side(hand = listOf(FxRef.PAWN)), turn = 2)
        var (g, t) = commit(g0, t0, 0, FxMove.NormalSummon(FxRef.uid(t0, FxRef.SCOUT)))
        // The trigger went on the chain as the summon was made ("you can": yes); their priority, and they pass.
        assertEquals(1, t.state.chain.size)
        assertEquals(1, FxEngine.next(t))
        commit(g, t, 1, FxMove.Pass).let { g = it.first; t = it.second }
        val seen = Seen.of(g, 0, kit)
        assertEquals(1, seen.passes.size)
        val worlds = Worlds.deal(seen, Prior.Decklist(listOf(FxRef.WARD, FxRef.PAWN)), 8, kit)
        assertTrue(worlds.any { theirHand(it) == listOf(FxRef.WARD) } && worlds.any { theirHand(it) == listOf(FxRef.PAWN) }, "${worlds.map(::theirHand)}")
        for (w in worlds) {
            if (theirHand(w) == listOf(FxRef.WARD)) assertEquals(Worlds.HOLD_BACK, w.weight) else assertEquals(1.0, w.weight)
        }
    }

    @Test
    fun withAnswersOnTheTurnIsSearchedAroundTheirWard() {
        // Scout's search is worth a card in hand; Ward in their hand negates it.
        val (g, _) = FxRef.game(Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.SCOUT)), Side(hand = listOf(FxRef.PAWN)), turn = 1)
        val seen = Seen.of(g, 0, kit)
        val world = Worlds.deal(seen, Prior.Decklist(listOf(FxRef.WARD)), 1, kit).single()
        assertEquals(listOf(FxRef.WARD), theirHand(world))
        val t = FxTable(world.game.state, seen.fx, kit.book, kit.facts, seen.seed).current()
        val passing = TurnSearch(kit).search(t, 0)
        val answered = TurnSearch(kit, answers = true).search(t, 0)
        assertTrue(answered.complete && passing.complete)
        assertTrue(answered.value < passing.value, "${answered.value} < ${passing.value}")
        // They Ward the search: their move is in the line.
        assertTrue(answered.steps.any { it is TurnStep.Move && it.seat == 1 && it.move is FxMove.Activate }, "${answered.steps}")
    }

    @Test
    fun aDecisionAcrossWorldsIsMadeTheSameWayEveryTime() {
        val (g, _) = FxRef.game(Side(hand = listOf(FxRef.SCOUT, FxRef.KNIGHT), deck = listOf(FxRef.PAWN)), Side(hand = listOf(FxRef.PAWN)), turn = 1)
        val seen = Seen.of(g, 0, kit)
        val prior = Prior.Decklist(listOf(FxRef.WARD, FxRef.PAWN, FxRef.PAWN))
        val a = Regret.decide(seen, prior, kit, k = 4, budget = 20_000)
        val b = Regret.decide(seen, prior, kit, k = 4, budget = 20_000)
        val choice = a.choice!!
        assertEquals(choice.root, b.choice?.root)
        assertEquals(a.all.map { it.root to it.regret }, b.all.map { it.root to it.regret })
        // The choice has the least regret, and is a way the seat can go on.
        assertTrue(a.all.all { it.regret >= choice.regret })
        assertTrue(a.lines.isNotEmpty())
    }
}
