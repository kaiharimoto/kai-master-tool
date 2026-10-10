package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.neue.NeueHolders
import kotlinx.coroutines.delay

/**
 * Solve this turn photographed (Phase E, E.2, `--duel-solve=dialog|replay`), on the duel `--duel=two` dealt: turn 2, the
 * second seat's Main Phase 1, so the Battle Phase is open and the near field empty. The real solver, off the frame thread as
 * the page runs it; `replay` opens its line as the unsaved replay and stands at its end.
 */
internal suspend fun studioSolve(h: NeueHolders, how: String, clock: FrameClock) {
    val d = h.duel
    d.act(DuelAction.EndTurn, 0)
    d.act(DuelAction.Phase(DuelPhase.MAIN1), 1)
    d.bottom = 1
    h.solver.start()
    var waited = 0
    while (h.solver.running && waited < 120_000) {
        delay(100)
        waited += 100
        clock.run(1)
    }
    val s = h.solver.solved
    println("[neue-studio] solve: ${h.solver.problem ?: "${s?.result?.steps?.size} steps, value ${s?.result?.value}, complete ${s?.result?.complete}, ${s?.result?.moves} moves"}")
    if (how == "replay") {
        h.solver.play()
        d.seek(Int.MAX_VALUE)
    }
    clock.run(60)
}
