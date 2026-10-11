package com.kaiharimoto.neue.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.effects.Chooser
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxPlay
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.match.CueKind
import com.kaiharimoto.mastertool.core.duel.match.SeatDoor
import com.kaiharimoto.mastertool.core.duel.search.Deciders
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import com.kaiharimoto.mastertool.core.duel.search.Seen
import com.kaiharimoto.mastertool.core.duel.search.TableCue
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.effects.goldfishKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search in Ai's seat at kai's own table (Phase E, E.3b; `docs/phases/E.md` §4.4): when [DuelPrefs.aiPlayer] is Search and
 * Ai takes its seat's turns, every change to the table asks whether anything waits on Ai's seat ([TableCue]) — its turn,
 * a chain it has priority on, a window, an ask — and the app's own search makes the move, off the frame thread, through a
 * door over the duel in play ([TableDoor]). Never at a networked table, during an Ai vs Ai match or in a replay.
 */
class SearchSeat internal constructor(private val h: NeueHolders) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** Where a cue made no move (the duel, its cursor, the cue): not cued again until the table moves. */
    private var still: Triple<String, Int, CueKind>? = null

    /** The search is thinking on Ai's seat. */
    var thinking by mutableStateOf(false)
        private set

    /** The deck Ai named as the other seat's (E.5b, `duel_opponent`), for the duel it was named in. */
    class Opponent(val duel: String, val deckId: String, val name: String, val main: List<Int>)

    var opponent: Opponent? = null

    /**
     * What a search for [seat] guesses the other seat's hidden cards from (E.5b): their list where the seat may see everything,
     * else the deck Ai named for this duel, else the field's hand traps as last read.
     */
    fun prior(g: DuelGame, seat: Int, knows: String): Prior {
        val other = g.header.seats.getOrNull(1 - seat)?.main.orEmpty()
        if (knows == DuelBrief.FULL && other.isNotEmpty()) return Prior.Decklist(other)
        opponent?.takeIf { it.duel == g.header.id && it.main.isNotEmpty() }?.let { return Prior.Decklist(it.main) }
        return searchPrior(h)
    }

    private val prefs get() = h.neue.prefs.duel

    /** Whether Search plays Ai's seat at this table now; [force]: asked to play this turn, its turns not taken by itself. */
    fun on(force: Boolean = false): Boolean {
        val d = h.duel
        val g = d.game ?: return false
        return prefs.aiPlayer == DuelPrefs.SEARCH && (prefs.aiPlays || force) && aiAtTable(h) && !g.state.solo &&
            d.replay == null && !d.spectating && d.role == null
    }

    /**
     * The table changed: when something waits on Ai's seat and the search is not already thinking, it makes its move. [force]:
     * the person's Play this turn, with Ai's turns not taken by itself.
     */
    fun poke(force: Boolean = false) {
        if (!on(force) || job?.isActive == true) return
        val d = h.duel
        val g = d.game ?: return
        // The opening roll is thrown and chosen for Ai's seat by the table's own hand.
        if (d.opening || g.state.beforeTurnOne || d.shortcutPart.open) return
        val kit = h.goldfishKit()
        val seat = prefs.aiSeat
        val t = FxTable(g.state, FxFold.fold(g.header, g.played, kit.book, kit.facts, g.state), kit.book, kit.facts, g.header.seed).current()
        val kind = TableCue.of(t, seat) ?: return
        val at = Triple(g.header.id, g.cursor, kind)
        if (at == still && !force) return
        val knows = if (prefs.aiKnowledge == DuelBrief.FULL) DuelBrief.FULL else DuelBrief.SELF
        // Its guess at the person's hand: their list where it may see everything, else the deck Ai named, else the field's.
        val prior = prior(g, seat, knows)
        // The version in use (E.5b): the newest that passed the ladder's gate.
        h.ladder.versionsNow()
        val player = SearchPlayer({ prior }, SearchPlayer.Strength.of(prefs.aiStrength), version = h.ladder.inUse)
        job = scope.launch {
            thinking = true
            try {
                withContext(Dispatchers.Default) { player.cue(TableDoor(h, seat, kind, kit, knows, player.engine)) }
            } finally {
                // Nothing made: the same table is not cued again until it moves (the person's move, or undo).
                still = if (d.game?.cursor == g.cursor && d.game?.header?.id == g.header.id) at else null
                thinking = false
            }
        }
    }

    /** Stops the search where it stands (the person's Stop, Esc, a new duel). */
    fun stop() {
        job?.cancel()
        job = null
        thinking = false
    }
}

/**
 * The door over kai's own duel (E.3b): what the search is handed at that table. Its engine moves are made on the table's
 * engine state and committed with their tags as one group of Ai's, the search's version stamped on them; a choice the move
 * puts to the person is asked in the Shortcut window, the move made again from the start with each answer (the window's
 * own way of asking). Its typed moves are played out as Ai's lines are, at Ai's pace, held to what a player may do.
 */
internal class TableDoor(
    private val h: NeueHolders,
    override val seat: Int,
    override val kind: CueKind,
    override val kit: GoldfishKit,
    private val knows: String,
    /** The search's version, stamped on its moves. */
    private val engine: String,
) : SeatDoor {
    private val duels get() = h.duel
    private val pace: Long get() = h.neue.prefs.duel.aiPace.toLong()

    override val waitsOnOther: Boolean get() = duels.game?.state?.window?.opener == seat

    override fun seen(): Seen = Seen.of(duels.game ?: error("no duel"), seat, kit, knows)

    override suspend fun engine(move: FxMove, choose: SeatDoor.Choose): String? = withContext(Dispatchers.Main) { make(move, choose) }

    /** [move] made on the table, asking the person each choice of theirs it reaches; null when made, else why not. */
    private suspend fun make(move: FxMove, choose: SeatDoor.Choose): String? {
        val given = ArrayList<List<Int>>()
        while (true) {
            val g = duels.game ?: return "There is no duel on the table."
            val s = g.state
            val t = FxTable(s, FxFold.fold(g.header, g.played, kit.book, kit.facts, s), kit.book, kit.facts, g.header.seed).current()
            choose.rewind()
            val ask = Asker(s, choose, given)
            val p = withContext(Dispatchers.Default) { runCatching { FxEngine.play(t, seat, move, ask) }.getOrNull() }
            val q = ask.question
            if (q != null) {
                // The person's own choice inside Search's move: the window asks it, and the move is made again with it.
                given += duels.shortcutPart.askFor(q, 1 - seat) ?: Deciders.first(q)
                continue
            }
            if (p !is FxPlay.Done) return (p as? FxPlay.Refused)?.why ?: "It was not made."
            if (!asSearch { duels.act(p.actions, seat, fx = p.tags) }) return duels.problem ?: "The table refused it."
            delay(pace)
            return null
        }
    }

    override suspend fun ops(vararg ops: String): Pair<String, Boolean> = withContext(Dispatchers.Main) {
        // At kai's table the engine keeps the chain's priority: a pass is the engine's.
        if (ops.singleOrNull() == "pass") {
            val why = engine(FxMove.Pass, SeatDoor.Choose { d, _ -> Deciders.first(d) })
            return@withContext (why ?: "passed") to (why != null)
        }
        val viewer = DuelBrief.viewer(knows, seat)
        val report = asSearch { duels.playOut(ops.toList(), seat, pace, viewer, guard = true) }
        report.text to !report.played
    }

    override suspend fun settle(): Boolean = withContext(Dispatchers.Main) {
        val g = duels.game ?: return@withContext false
        val o = DuelBattle.owed(g, kit.catalog)?.takeIf { it.attack.seat == seat } ?: return@withContext false
        val line = com.kaiharimoto.mastertool.core.duel.text.Spotlight.battleLine(g.state, o, seat, g.header.seed) ?: return@withContext false
        val viewer = DuelBrief.viewer(knows, seat)
        asSearch { duels.playOut(line.split(';').map { it.trim() }, seat, pace, viewer, guard = true) }.played
    }

    /** [block] as Ai's move, the search's version stamped on it. */
    private inline fun <T> asSearch(block: () -> T): T {
        val wasAi = duels.aiActing
        val wasEngine = duels.aiEngine
        duels.aiActing = true
        duels.aiEngine = engine
        return try { block() } finally {
            duels.aiActing = wasAi
            duels.aiEngine = wasEngine
        }
    }

    /**
     * The chooser of one run of a move: the search's own choices by [choose] (told the codes of the offered cards it may see),
     * the person's from [given] in order, and the first the person has not given yet stops the run ([question]).
     */
    private inner class Asker(private val s: DuelState, private val choose: SeatDoor.Choose, private val given: List<List<Int>>) : Chooser {
        var question: Decision? = null
            private set
        private var k = 0

        override fun choose(d: Decision): List<Int> {
            if (question != null) return Chooser.CANCEL
            if (Deciders.of(d, s, seat) == seat) return choose.choose(d, codes(d))
            return given.getOrNull(k++) ?: run { question = d; Chooser.CANCEL }
        }

        private fun codes(d: Decision): List<Int?> = (d as? Decision.Cards)?.among?.map { u ->
            val c = s.cards[u] ?: return@map null
            if (c.token || DuelSight.sees(s, u, seat) || (d.hidden && c.owner == seat)) c.code else null
        }.orEmpty()
    }
}
