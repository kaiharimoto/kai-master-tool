package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelRules
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A table played move by move through the engine, each move committed to a duel as it would be at the table: what the
 * chain, step and rulings tests drive. Every move is checked as it goes — physics, one tag an action, the commit's table
 * the engine's, and the log's fold the engine's state.
 */
class FxPlays(val game0: DuelGame, t0: FxTable) {
    var t: FxTable = t0
        private set
    var game: DuelGame = game0
        private set
    /** Every decision put, in order. */
    val asked = mutableListOf<Decision>()

    constructor(pair: Pair<DuelGame, FxTable>) : this(pair.first, pair.second)

    /** A chooser that records, and answers by [answer] (the first legal answer, yes, the given order). */
    fun chooser(answer: (Decision) -> List<Int>? = { null }): Chooser = Chooser { d ->
        asked += d
        answer(d) ?: Chooser.FIRST.choose(d)
    }

    /** [move] by [seat]: done, checked and committed. */
    fun go(seat: Int, move: FxMove, answer: (Decision) -> List<Int>? = { null }): FxPlay.Done {
        val p = FxEngine.play(t, seat, move, chooser(answer))
        val d = p as? FxPlay.Done ?: fail("$move by $seat: $p")
        val (s, problem) = DuelRules.applyAll(t.state, d.actions)
        assertNotNull(s, problem)
        assertEquals(d.state, s)
        assertEquals(d.actions.size, d.tags.size)
        val c = game.act(d.actions, seat, fx = d.tags)
        assertEquals(null, c.problem)
        game = c.game
        assertEquals(d.state, game.state, "the commit's table is the engine's")
        assertEquals(d.fx, FxFold.fold(game.header, game.played, t.book, t.facts, game.state), "the log folds to the engine's state")
        t = FxTable(d.state, d.fx, t.book, t.facts, t.seed)
        return d
    }

    fun activate(seat: Int, uid: Int, effect: String = "e1", answer: (Decision) -> List<Int>? = { null }) = go(seat, FxMove.Activate(uid, effect), answer)
    fun pass(seat: Int) = go(seat, FxMove.Pass)
    fun resolve(seat: Int = t.state.active) = go(seat, FxMove.Resolve)

    /** Both seats pass from [first]: the newest link resolves. */
    fun passBoth(first: Int) {
        pass(first)
        pass(1 - first)
    }

    fun refused(seat: Int, move: FxMove): String = assertIs<FxPlay.Refused>(FxEngine.play(t, seat, move, Chooser.FIRST), "$move").why

    /** The notes the engine wrote, in order. */
    fun notes(): List<String> = game.played.mapNotNull { (it.action as? DuelAction.Note)?.text }

    fun uid(code: Int, seat: Int = 0): Int = FxRef.uid(t, code, seat)
    fun uids(code: Int, seat: Int = 0): List<Int> = FxRef.uids(t, code, seat)
}
