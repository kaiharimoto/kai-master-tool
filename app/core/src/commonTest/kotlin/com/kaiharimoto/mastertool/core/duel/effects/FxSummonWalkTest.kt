package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Random walks over the engine's summons, procedures and phases (agent (a)'s part of `FxPhysicsTest` and
 * `FxDeterminismTest`, D.md §10): every action list it makes is accepted by `DuelRules`, commits through `DuelGame.act`
 * with its tags, and the same table and answers always give the same log.
 */
class FxSummonWalkTest {
    private fun start() = FxRef.table(
        you = Side(
            hand = listOf(FxRef.LAMP, FxRef.LAMP, FxRef.SCOUT, FxRef.PAWN, FxRef.COLOSSUS, FxRef.WARDEN, FxRef.SPRITE),
            field = listOf(Slot(FxRef.TINKER, 4)),
            extra = listOf(FxRef.BRIDGE, FxRef.ARCH, FxRef.SPIDER, FxRef.PALADIN, FxRef.REGENT, FxRef.SPIDER),
            deck = listOf(FxRef.PAWN, FxRef.PAWN),
        ),
    )

    /** Answers at random from [seed]: sometimes outside the rules, which the engine must refuse or cancel, never commit. */
    private fun chooser(r: Random) = Chooser { d ->
        when (d) {
            is Decision.Cards -> d.among.indices.shuffled(r).take(r.nextInt(d.min, d.max.coerceAtMost(d.among.size) + 1))
            is Decision.Zone -> listOf(r.nextInt(d.among.size))
            is Decision.Order -> d.triggers.indices.shuffled(r)
            is Decision.YesNo -> listOf(r.nextInt(2))
            is Decision.Option -> listOf(r.nextInt(d.among.size))
            is Decision.Declare -> listOf(r.nextInt(d.among.size))
        }
    }

    private fun walk(seed: Long): List<DuelEntry> {
        val r = Random(seed)
        var t = start()
        var game = DuelGame(DuelHeader(), emptyList(), 0, t.state, 0)
        val choose = chooser(r)
        repeat(40) {
            val moves = FxEngine.moves(t, 0)
            if (moves.isEmpty()) return game.entries
            when (val p = FxEngine.play(t, 0, moves[r.nextInt(moves.size)], choose)) {
                is FxPlay.Done -> {
                    val (s, problem) = DuelRules.applyAll(t.state, p.actions)
                    assertNotNull(s, problem)
                    assertEquals(p.state, s)
                    val c = game.act(p.actions, 0, fx = p.tags)
                    assertTrue(c.ok, c.problem)
                    game = c.game
                    t = t.copy(state = p.state, fx = p.fx)
                    assertEquals(game.state, t.state)
                }
                is FxPlay.Refused, FxPlay.Cancelled -> {}
            }
        }
        return game.entries
    }

    @Test
    fun everyWalkIsPhysicsAndTheSameAnswersGiveTheSameLog() {
        var summons = 0
        for (seed in 1L..40L) {
            val a = walk(seed)
            assertEquals(a, walk(seed), "seed $seed")
            assertTrue(a.all { it.fx != null }, "every engine entry is tagged")
            summons += a.count { it.fx?.part == FxTag.PROC }
        }
        assertTrue(summons > 0, "the walks reached the procedures")
    }
}
