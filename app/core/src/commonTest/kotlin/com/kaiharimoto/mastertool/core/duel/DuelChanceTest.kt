package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceStage
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.Toss
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.layout.FormFactor
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The table's own die and coin (1.0.96, kai: "have a 3d dice and coin by the left side near the extra deck for both
 * players … clicking on one of them will bring them to the field, just dragging them from the corner also works. the
 * coin is thrown by dragging and throwing").
 */
class DuelChanceTest {

    private fun play(): DuelGame = DuelGame.start(header())

    @Test
    fun aRollIsStampedWithAThrowFromTheCornerAndLiesOnTheTable() {
        val r = play().act(DuelAction.Dice(0), 0)
        assertTrue(r.ok, r.problem)
        val a = r.game.entries.last().action as DuelAction.Dice
        val toss = assertNotNull(a.toss)
        assertTrue(toss.fromCorner)
        assertEquals(listOf(Chance(0, coin = false, value = a.value, toss = toss)), r.game.state.chance)
    }

    @Test
    fun theValueIsTheOneTheRollAlwaysHad() {
        // The value is drawn first, the throw after it: what a roll reads is what it read before throws.
        val g = play()
        val n = g.played.count { DuelRandom.rolls(it.action) }
        val die = g.act(DuelAction.Dice(0), 0).game.entries.last().action as DuelAction.Dice
        assertEquals(DuelRandom.forRoll(g.header.seed, n).nextInt(1, 7), die.value)
        val coin = g.act(DuelAction.Coin(1), 1).game.entries.last().action as DuelAction.Coin
        assertEquals(DuelRandom.forRoll(g.header.seed, n).nextBoolean(), coin.heads)
    }

    @Test
    fun aHandsThrowIsKeptAndTheValueStillStamped() {
        val mine = Toss.coin(V3(10.0, 5.0), Quat.IDENTITY, V3(3.0, -12.0), 2.0)
        val g = play().act(DuelAction.Coin(0, toss = mine), 0).game
        val c = g.entries.last().action as DuelAction.Coin
        assertEquals(mine, c.toss)
        assertEquals(c.heads, g.state.chance.single().heads)
    }

    @Test
    fun theNextMovePutsThemBackButTalkDoesNot() {
        var g = play().act(DuelAction.Dice(0), 0).game
        g = g.act(DuelAction.Coin(0), 0).game
        g = g.act(DuelAction.Dice(1), 1).game
        assertEquals(3, g.state.chance.size)
        // A second roll of the same seat's die replaces its first.
        g = g.act(DuelAction.Dice(0), 0).game
        assertEquals(3, g.state.chance.size)
        g = g.act(DuelAction.Chat(1, "nice"), 1).game
        assertEquals(3, g.state.chance.size)
        g = g.act(DuelAction.Draw(0), 0).game
        assertEquals(emptyList(), g.state.chance)
        // Undo brings them back, as it brings back everything.
        assertEquals(3, g.undo().state.chance.size)
    }

    @Test
    fun aRollFromBeforeThrowsLiesNowhere() {
        val s = DuelRules.apply(play().state, DuelAction.Dice(0, 4), 0)
        assertEquals(emptyList(), (s as Outcome.Ok).state.chance)
    }

    @Test
    fun theGuestSeesThemLand() {
        val g = play().act(DuelAction.Coin(0), 0).game
        val mirror = DuelMirror.state(DuelView.of(g.state, 1, g.header.seed))
        assertEquals(g.state.chance, mirror.chance)
    }

    @Test
    fun theDieAndTheCoinComeToRestFlatOnTheTable() {
        val r = Random(5)
        repeat(12) { i ->
            val die = DiceSim.run(DiceSim.Shape.DIE, Toss.randomDie(r).start)
            val pose = die.rest.single()
            assertEquals(0.5, pose.p.z, 1e-9)
            assertTrue(pose.p.x in 0.0..DiceSim.ARENA_W && pose.p.y in -DiceSim.INNER..DiceSim.ARENA_D, "die $i at ${pose.p}")
            val coin = DiceSim.run(DiceSim.Shape.COIN, Toss.randomCoin(r).start)
            val c = coin.rest.single()
            assertEquals(DiceSim.COIN_H, c.p.z, 1e-9)
            assertEquals(1.0, abs(c.q.rotate(V3.UP).z), 1e-6)
            assertTrue(coin.up.single() in 0..1)
            assertTrue(c.p.x in 0.0..DiceSim.ARENA_W && c.p.y in -DiceSim.INNER..DiceSim.ARENA_D, "coin $i at ${c.p}")
        }
    }

    @Test
    fun aCoinFlipsInTheAir() {
        // Turned over at least once on the way: its face's normal points down at some frame.
        val r = Random(9)
        repeat(6) {
            val run = DiceSim.run(DiceSim.Shape.COIN, Toss.randomCoin(r).start)
            assertTrue(run.frames.any { f -> f.dice.single().q.rotate(V3.UP).z < -0.5 }, "a coin that never turned")
            assertTrue(run.duration < DiceSim.MAX_TIME + 1.0)
        }
    }

    @Test
    fun theSameThrowIsTheSameRun() {
        val t = Toss.coin(V3(8.0, 6.0), Quat.IDENTITY, V3(-4.0, -10.0), -3.0)
        assertEquals(DiceSim.run(DiceSim.Shape.COIN, t.start).frames, DiceSim.run(DiceSim.Shape.COIN, t.start).frames)
    }

    @Test
    fun eachSeatKeepsThemBesideItsExtraDeckOffTheZones() {
        val sizes = listOf(1920f to 1080f, 1280f to 800f, 2560f to 1440f, 412f to 915f, 915f to 412f)
        sizes.forEach { (w, h) ->
            val form = if (minOf(w, h) < 600f) FormFactor.PHONE else FormFactor.DESK
            val l = DuelLayouter.solve(w, h, twoSided = true, form = form)
            val stage = DiceStage(l)
            for (seat in 0..1) {
                val home = assertNotNull(stage.home(seat), "no home for $seat at $w×$h")
                val ed = assertNotNull(l.pile(seat, PileKind.EXTRA))
                listOf(home.die, home.coin).forEach { (x, y) ->
                    val r = home.coinRadius
                    assertTrue(x - r >= 0f && x + r <= l.width && y in 0f..l.height, "($x, $y) off the table at $w×$h")
                    // Never on a zone or a pile.
                    l.spots.filterKeys { it !is DuelSpot.Hand }.values.forEach { s -> assertTrue(!s.contains(x, y), "($x, $y) on $s at $w×$h") }
                    // Near its own Extra Deck.
                    assertTrue(abs(x - ed.centerX) <= ed.width * 3f && abs(y - ed.centerY) <= ed.height * 1.6f, "($x, $y) far from $ed at $w×$h")
                }
            }
        }
    }

    @Test
    fun noThrowStartsAtAHomeByChance() {
        assertNull(Toss.die(V3(5.0, 5.0), Quat.IDENTITY, V3(1.0, -1.0), 0.0).takeIf { it.fromCorner })
    }
}
