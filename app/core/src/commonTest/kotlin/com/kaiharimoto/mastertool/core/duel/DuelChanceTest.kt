package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceStage
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.TossRuns
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

    // ---- 1.1.9: put back, and over the other seat's field --------------------------------------------------------

    private fun out(): DuelGame {
        var g = play().act(DuelAction.Dice(0), 0).game
        g = g.act(DuelAction.Coin(0), 0).game
        return g.act(DuelAction.Dice(1), 1).game
    }

    @Test
    fun stowPutsASeatsPiecesBackAndNoOneElses() {
        var g = out()
        g = g.act(DuelAction.Stow(0, coin = false), 0).game
        assertEquals(setOf(0 to true, 1 to false), g.state.chance.map { it.seat to it.coin }.toSet())
        g = g.act(DuelAction.Stow(0), 0).game
        assertEquals(listOf(1 to false), g.state.chance.map { it.seat to it.coin })
        // Nothing left of seat 0's: said, not silently done.
        val again = g.act(DuelAction.Stow(0), 0)
        assertTrue(!again.ok && again.problem!!.contains("already"), again.problem)
        assertTrue(!g.act(DuelAction.Stow(1, coin = true), 1).ok)
        // A step of the log: Undo brings the die back where it lay.
        assertEquals(2, g.undo().state.chance.size)
        assertEquals(out().state.chance, g.undo().undo().state.chance)
    }

    @Test
    fun stowIsHousekeepingNeverAMove() {
        // Social, as a lock is: it never ends a thinking mark, opens a response window or puts the other seat's back.
        val a = DuelAction.Stow(0)
        assertTrue(a.social)
        val before = out().act(DuelAction.Thinking(0, true), 0).game
        val g = before.act(a, 0).game
        assertTrue(0 in g.state.thinking)
        assertTrue(!com.kaiharimoto.mastertool.core.duel.net.Windows.opens(listOf(a), com.kaiharimoto.mastertool.core.duel.net.Windows.ALWAYS))
        assertEquals(1, g.state.chance.size)
        // It leaves nothing to chance: nothing stamped.
        assertTrue(!DuelRandom.rolls(a))
        assertEquals("Kai puts the die and the coin back", com.kaiharimoto.mastertool.core.duel.text.DuelWords.say(before.state, g.state, g.entries.last(), null, CommandFixtures.catalog))
    }

    @Test
    fun stowIsTypedSpokenAndKeyed() {
        val s = out().state
        fun typed(line: String) = com.kaiharimoto.mastertool.core.duel.text.DuelCommand.parse(line, s, 0, CommandFixtures.catalog)
        fun stowOf(line: String) = (typed(line) as com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed.Actions).actions.single()
        assertEquals(DuelAction.Stow(0), stowOf("stow"))
        assertEquals(DuelAction.Stow(0), stowOf("stow all"))
        assertEquals(DuelAction.Stow(0, coin = false), stowOf("stow die"))
        assertEquals(DuelAction.Stow(0, coin = false), stowOf("stow the dice"))
        assertEquals(DuelAction.Stow(0, coin = true), stowOf("stow coin"))
        assertTrue(typed("stow cards") is com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed.Problem)
        assertTrue("stow" in com.kaiharimoto.mastertool.core.duel.text.DuelCommand.HEADS)
        val speech = com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
        assertEquals("stow die", speech.normalize("Put the dice away."))
        assertEquals("stow coin", speech.normalize("put my coin back"))
        assertEquals("stow", speech.normalize("Put the die and coin back"))
        // Alt R, on Duel only: both of the person's pieces.
        val duelling = com.kaiharimoto.mastertool.core.input.DeskContext(onBuilder = false, onDuel = true)
        val altR = com.kaiharimoto.mastertool.core.input.KeyChord("r", alt = true)
        assertEquals(com.kaiharimoto.mastertool.core.input.DeskAction.DUEL_STOW, com.kaiharimoto.mastertool.core.input.DeskShortcuts.resolve(altR, duelling))
        assertNull(com.kaiharimoto.mastertool.core.input.DeskShortcuts.resolve(altR, duelling.copy(textInputFocused = true)))
        // The mouse and the finger both put one back, by a double press or a carry home.
        val stow = com.kaiharimoto.mastertool.core.input.DuelInputAction.STOW
        val chance = com.kaiharimoto.mastertool.core.input.DuelTarget.CHANCE
        assertEquals(stow, com.kaiharimoto.mastertool.core.input.DuelMouse.resolve(chance, com.kaiharimoto.mastertool.core.input.DuelMouse.DOUBLE))
        assertEquals(stow, com.kaiharimoto.mastertool.core.input.DuelTouch.resolve(chance, com.kaiharimoto.mastertool.core.input.DuelTouch.DOUBLE))
        assertEquals(stow, com.kaiharimoto.mastertool.core.input.DuelMouse.resolve(chance, com.kaiharimoto.mastertool.core.input.DuelMouse.DRAG_HOME))
        assertEquals(stow, com.kaiharimoto.mastertool.core.input.DuelTouch.resolve(chance, com.kaiharimoto.mastertool.core.input.DuelMouse.DRAG_HOME))
        assertTrue(com.kaiharimoto.mastertool.core.duel.text.DuelCoverage.ROWS.any { it.typed == "stow" })
    }

    @Test
    fun theGuestSeesThemPutBack() {
        val g = out().act(DuelAction.Stow(0, coin = true), 0).game
        val mirror = DuelMirror.state(DuelView.of(g.state, 1, g.header.seed))
        assertEquals(g.state.chance, mirror.chance)
    }

    @Test
    fun aHardThrowCrossesTheMiddleOntoTheirFieldAndNeverOffTheTable() {
        // Thrown firmly from the near edge straight at the other seat: past the middle row, onto their field (a flick at the
        // fastest meets their far edge and comes back, as off a real table's rim).
        val hard = Toss.die(V3(10.0, 7.0), Quat.IDENTITY, V3(0.0, -25.0), 0.0)
        assertEquals(DiceSim.ACROSS, hard.reach)
        val rest = TossRuns.of(DiceSim.Shape.DIE, hard).rest.single().p
        assertTrue(rest.y < -DiceSim.INNER, "stopped at $rest")
        // The same throw with the old wall stops at the middle row, as every throw before 1.1.9 did.
        val old = DiceSim.run(DiceSim.Shape.DIE, hard.start, DiceSim.INNER).rest.single().p
        assertTrue(old.y >= -DiceSim.INNER, "old wall passed: $old")
        // Seeded flings every way: each ends flat on the whole table, never past a wall.
        val r = Random(41)
        repeat(24) { i ->
            val at = V3(1.0 + r.nextDouble() * 18.0, -DiceSim.ACROSS + 1.0 + r.nextDouble() * 18.0)
            val v = V3((r.nextDouble() - 0.5) * 90.0, (r.nextDouble() - 0.5) * 90.0)
            val coin = i % 2 == 1
            val t = if (coin) Toss.coin(at, DiceThrow.randomTurn(r), v, r.nextDouble() * 8.0) else Toss.die(at, DiceThrow.randomTurn(r), v, r.nextDouble() * 16.0)
            val p = TossRuns.of(if (coin) DiceSim.Shape.COIN else DiceSim.Shape.DIE, t).rest.single().p
            assertTrue(p.x in 0.0..DiceSim.ARENA_W && p.y in -DiceSim.ACROSS..DiceSim.ARENA_D, "throw $i at $p")
        }
        // A wall from the wire is held to the table's: no further than ACROSS, no nearer than INNER.
        assertEquals(DiceSim.ACROSS, Toss(hard.start, 1e9).rounded().far)
        assertEquals(DiceSim.INNER, Toss(hard.start, -3.0).rounded().far)
        assertTrue(!Toss(hard.start, Double.NaN).valid)
    }

    @Test
    fun theHostAndTheGuestPlayTheSameFarThrow() {
        val hard = Toss.coin(V3(4.0, 6.5), Quat.IDENTITY, V3(6.0, -40.0), 3.0)
        val g = play().act(DuelAction.Coin(0, toss = hard), 0).game
        // The throw goes over the wire inside the action; read back, it is bit for bit the throw, and the same run.
        val wire = com.kaiharimoto.mastertool.core.duel.net.WireCodec.json
        val sent = wire.encodeToString(DuelAction.serializer(), g.entries.last().action)
        val got = wire.decodeFromString(DuelAction.serializer(), sent) as DuelAction.Coin
        assertEquals(hard, got.toss)
        assertEquals(DiceSim.run(DiceSim.Shape.COIN, hard.start, hard.far).frames, DiceSim.run(DiceSim.Shape.COIN, got.toss!!.start, got.toss!!.far).frames)
        val mirror = DuelMirror.state(DuelView.of(g.state, 1, g.header.seed))
        assertEquals(g.state.chance, mirror.chance)
    }

    @Test
    fun aDieOnTheirFieldIsDrawnOnTheTableOnEveryWindow() {
        val sizes = listOf(1920f to 1080f, 1280f to 800f, 1024f to 640f, 2560f to 1440f, 412f to 915f, 915f to 412f)
        for ((w, h) in sizes) for (twoSided in listOf(true, false)) {
            val form = if (minOf(w, h) < 600f) FormFactor.PHONE else FormFactor.DESK
            val l = DuelLayouter.solve(w, h, twoSided = twoSided, form = form)
            val stage = DiceStage(l)
            // Everything of the table: both fields and the shared row.
            val table = l.spots.filterKeys { it !is DuelSpot.Hand }.values
            val left = table.minOf { it.left } - l.gap
            val right = table.maxOf { it.right } + l.gap
            val top = table.minOf { it.top } - l.gap
            val bottom = table.maxOf { it.bottom } + l.gap
            for (seat in 0..1) {
                if (stage.arena(seat) == null) continue
                for (y in listOf(-DiceSim.ACROSS + 0.5, -DiceSim.INNER, -1.0, 4.0, DiceSim.ARENA_D - 0.5)) for (x in listOf(0.5, 10.0, DiceSim.ARENA_W - 0.5)) {
                    val p = V3(x, y, 0.5)
                    val drawn = stage.toTable(seat, stage.shown(seat, p, DiceSim.ACROSS))
                    assertTrue(drawn.x in left..right && drawn.y in top..bottom, "seat $seat $p drawn at $drawn, off the table at $w×$h${if (twoSided) "" else " one-sided"}")
                    val back = stage.unshown(seat, stage.shown(seat, p, DiceSim.ACROSS), DiceSim.ACROSS)
                    assertEquals(p.y, back.y, 1e-9)
                }
            }
            // Where both fields are drawn at one size, a die past the middle row is drawn where it lies.
            if (twoSided && l.farScale == 1f) {
                val p = V3(10.0, -DiceSim.ACROSS + 0.5, 0.5)
                assertEquals(p.y, stage.shown(0, p, DiceSim.ACROSS).y, 0.15)
            }
            // The fold only ever squeezes: a throw from before 1.1.9 (INNER) is drawn where it always was, on two sides.
            if (twoSided) for (seat in 0..1) assertEquals(-3.0, stage.shown(seat, V3(5.0, -3.0, 0.5), DiceSim.INNER).y, 1e-9)
        }
    }

    @Test
    fun eachHomeIsADropTargetOfItsOwn() {
        listOf(1920f to 1080f, 412f to 915f, 915f to 412f).forEach { (w, h) ->
            val form = if (minOf(w, h) < 600f) FormFactor.PHONE else FormFactor.DESK
            val stage = DiceStage(DuelLayouter.solve(w, h, twoSided = true, form = form))
            for (seat in 0..1) {
                val home = assertNotNull(stage.home(seat))
                assertTrue(home.over(false, home.die.first, home.die.second) && home.over(true, home.coin.first, home.coin.second))
                assertTrue(!home.over(true, home.die.first, home.die.second) && !home.over(false, home.coin.first, home.coin.second))
                val d = home.target(false)
                val c = home.target(true)
                assertTrue(d.right <= c.left || c.right <= d.left || d.bottom <= c.top || c.bottom <= d.top, "die $d and coin $c overlap at $w×$h")
            }
        }
    }
}
