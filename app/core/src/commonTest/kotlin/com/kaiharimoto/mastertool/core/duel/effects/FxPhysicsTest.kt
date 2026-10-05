package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The engine is a referee above the table (D.md §2.1, §10 `FxPhysicsTest`): over seeded random walks of the reference
 * scripts, both seats, every action list the engine emits is accepted by `DuelRules.applyAll` from the table it was made
 * on, lands on the table the engine says, commits through `DuelGame.act` with one tag per action, and — its shuffles
 * stamped with the duel's own dice — leaves the committed table equal to the engine's. A cancel or a refusal makes nothing.
 */
class FxPhysicsTest {
    @Test
    fun everyActionListTheEngineEmitsIsPhysics() {
        var made = 0
        var activations = 0
        var resolutions = 0
        var refused = 0
        var cancelled = 0
        val kinds = HashSet<String>()
        for (seed in 1L..60L) {
            FxWalker.walk(seed) { step ->
                when (val p = step.play) {
                    is FxPlay.Done -> {
                        made++
                        val (s, problem) = DuelRules.applyAll(step.before.state, p.actions)
                        assertNotNull(s, "seed $seed, ${step.move}: $problem")
                        assertEquals(p.state, s, "seed $seed, ${step.move}: the engine's table is the actions' table")
                        assertEquals(p.actions.size, p.tags.size, "one tag an action")
                        assertEquals(step.game.state, p.state, "seed $seed, ${step.move}: committed, the same table")
                        if (step.move is FxMove.Activate) activations++
                        if (p.actions.any { it == DuelAction.ChainResolve }) resolutions++
                        p.actions.forEach { kinds += it::class.simpleName ?: "" }
                    }
                    is FxPlay.Refused -> refused++
                    FxPlay.Cancelled -> cancelled++
                }
            }
        }
        assertTrue(made > 500, "the walks played ($made)")
        assertTrue(activations > 100 && resolutions > 100, "they reached the chain: $activations activations, $resolutions resolutions")
        assertTrue(cancelled > 0, "out-of-bounds answers cancel")
        listOf("Move", "ChainAdd", "Answer", "Draw", "Lp", "Token", "Shuffle", "Note", "Lock").forEach {
            assertTrue(it in kinds, "the walks made a $it: $kinds")
        }
    }
}
