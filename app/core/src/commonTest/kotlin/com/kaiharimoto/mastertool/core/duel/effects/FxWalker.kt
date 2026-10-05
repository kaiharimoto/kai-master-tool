package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.random.Random

/**
 * Seeded random walks over the reference scripts, both seats (agents (b) and (c)): the engine's moves made at random with
 * a chooser answering at random — now and then out of bounds, which must cancel and commit nothing — each play committed
 * through `DuelGame.act` with its tags. [walk]'s observer sees every step; what `FxPhysicsTest`, `FxFoldTest`, `FxDeterminismTest` and
 * `FxBenchTest` hold of it.
 */
object FxWalker {
    /** A full table: every kind of effect the reference scripts hold, for both seats. */
    fun start(seed: Long = 7L): Pair<DuelGame, FxTable> = FxRef.game(
        you = Side(
            hand = listOf(
                FxRef.SCOUT, FxRef.LAMP, FxRef.FLASH, FxRef.CALL, FxRef.RALLY, FxRef.SEEDS, FxRef.FUSION, FxRef.RITE, FxRef.ORACLE,
                FxRef.ECHO, FxRef.OFFERING, FxRef.OATH, FxRef.CROSSROADS, FxRef.MILL, FxRef.SIEVE, FxRef.PURGE, FxRef.BEACON, FxRef.WARDEN,
            ),
            field = listOf(Slot(FxRef.TINKER, 0), Slot(FxRef.MANDATE, 1), Slot(FxRef.SCOUT_ALT, 2), Slot(FxRef.SNARE, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)),
            gy = listOf(FxRef.ECHO, FxRef.SCOUT),
            deck = listOf(
                FxRef.SCOUT, FxRef.ECHO, FxRef.MANDATE, FxRef.EMBER, FxRef.PAWN, FxRef.LAMP, FxRef.ECHO, FxRef.TINKER, FxRef.KNIGHT,
                FxRef.PAWN, FxRef.SPRITE, FxRef.EMBER, FxRef.BEACON, FxRef.MANDATE, FxRef.SCOUT,
            ),
            extra = listOf(FxRef.BRIDGE, FxRef.ARCH, FxRef.SPIDER, FxRef.PALADIN, FxRef.REGENT, FxRef.CHIMERA, FxRef.LORD),
        ),
        them = Side(
            hand = listOf(FxRef.WARD, FxRef.FLASH, FxRef.ECHO),
            field = listOf(
                Slot(FxRef.PAWN, 0), Slot(FxRef.EMBER, 1), Slot(FxRef.DENIAL, 0, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL),
                Slot(FxRef.SNARE, 1, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL), Slot(FxRef.EDICT, 2, kind = ZoneKind.SPELL),
            ),
            deck = listOf(FxRef.PAWN, FxRef.MANDATE, FxRef.ECHO, FxRef.PAWN, FxRef.WARD),
        ),
        seed = seed,
    )

    /** Answers at random from [r]: one time in [cancelOneIn], out of bounds. */
    fun chooser(r: Random, cancelOneIn: Int = 25) = Chooser { d ->
        if (cancelOneIn > 0 && r.nextInt(cancelOneIn) == 0) return@Chooser Chooser.CANCEL
        when (d) {
            is Decision.Cards -> d.among.indices.shuffled(r).take(r.nextInt(d.min, d.max.coerceAtMost(d.among.size) + 1))
            is Decision.Zone -> listOf(r.nextInt(d.among.size))
            is Decision.Position -> listOf(r.nextInt(d.among.size))
            is Decision.Order -> d.triggers.indices.shuffled(r)
            is Decision.YesNo -> listOf(r.nextInt(2))
            is Decision.Option -> listOf(r.nextInt(d.among.size))
            is Decision.Declare -> listOf(r.nextInt(d.among.size))
        }
    }

    /** One step of a walk: the table [before], the move, what it came to, and the game after committing it. */
    class Step(val before: FxTable, val seat: Int, val move: FxMove, val play: FxPlay, val game: DuelGame)

    /**
     * A walk of at most [steps] moves from [seed]: the next seat's moves ([FxEngine.next]), one at random. Phases are
     * offered rarely, so a walk spends its moves on the effects. Returns the game at the end.
     */
    fun walk(seed: Long, steps: Int = 60, see: (Step) -> Unit = {}): DuelGame {
        val r = Random(seed)
        var (game, t) = start(seed)
        val choose = chooser(r)
        repeat(steps) {
            val seat = FxEngine.next(t)
            val all = FxEngine.moves(t, seat)
            if (all.isEmpty()) return game
            val effects = all.filter { it !is FxMove.Phase }
            val moves = if (effects.isNotEmpty() && r.nextInt(8) != 0) effects else all
            val move = moves[r.nextInt(moves.size)]
            val p = FxEngine.play(t, seat, move, choose)
            if (p is FxPlay.Done) {
                val c = game.act(p.actions, seat, fx = p.tags)
                check(c.ok) { "seed $seed: the commit refused ${p.actions}: ${c.problem}" }
                game = c.game
                see(Step(t, seat, move, p, game))
                t = FxTable(p.state, p.fx, t.book, t.facts, t.seed)
                if (p.state.phase == DuelPhase.END) return game
            } else see(Step(t, seat, move, p, game))
        }
        return game
    }
}
