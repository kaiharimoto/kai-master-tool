package com.kaiharimoto.neue.duel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.search.TurnLine
import com.kaiharimoto.mastertool.core.duel.search.TurnSolve
import com.kaiharimoto.mastertool.core.duel.search.TurnValue
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Solve this turn (Phase E, E.2; `docs/phases/E.md` §4.2): the best whole turn the written effects allow for the seat whose
 * turn it is, searched off the frame thread on the table as that seat sees it ([TurnSolve]). Never at a table with another
 * player, never for Ai's seat (its hand is its own), never while an Ai vs Ai match is watched. The line opens as an unsaved
 * replay of the fork it was found on, so the duel in play is never touched.
 */
class TurnSolver(private val h: NeueHolders) {
    private val scope = Background.scope("Solve this turn", Dispatchers.Main)

    var open by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    var solved by mutableStateOf<TurnSolve.Solved?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** One a search: a search started again stops the one before it, whose thread the job's cancel cannot reach. */
    private class Flag {
        @Volatile
        var on = false
    }

    private var stopped = Flag()
    private var job: Job? = null

    /** The duel a solve reads: the one on screen (a replay at its step, or the duel in play). */
    private val game: DuelGame? get() = h.duel.shown

    /** Why the turn on screen cannot be solved now, or null. */
    fun refusal(): String? {
        val d = h.duel
        val g = game ?: return "No duel is on the table."
        if (d.role != null) return "A table with another player is theirs and yours: nothing is solved there."
        if (d.spectating) return "An Ai vs Ai match is on the table."
        val seat = g.state.active
        if (!g.state.solo && d.aiEngaged && h.neue.prefs.duel.aiSeat == seat && d.replay == null) {
            return "It is ${h.ai.name}'s turn, and its hand is its own."
        }
        return TurnSolve.refusal(g, seat)
    }

    /** Opens the dialog and starts the search, or says why it cannot. */
    fun start() {
        stopped.on = true
        job?.cancel()
        open = true
        solved = null
        problem = refusal()
        val g = game
        if (problem != null || g == null) return
        val seat = g.state.active
        val kit = h.goldfishKit()
        val flag = Flag().also { stopped = it }
        running = true
        job = scope.launch {
            val got = withContext(Dispatchers.Default) {
                runCatching { TurnSolve.solve(g, seat, kit, by = Provenance(Provenance.TABLE)) { flag.on } }
            }
            running = false
            got.onSuccess { s ->
                solved = s
                if (s == null) problem = TurnSolve.refusal(g, seat)
            }.onFailure { problem = "The search failed: ${it.message ?: it::class.simpleName}" }
        }
    }

    /** Stops the search: what it found so far stands. */
    fun stop() {
        stopped.on = true
    }

    fun close() {
        stopped.on = true
        job?.cancel()
        running = false
        open = false
        solved = null
        problem = null
    }

    /** The line, on the fork it was found on, as an unsaved replay. */
    fun play() {
        val s = solved ?: return
        h.duel.openGame("Solved: turn ${s.fork.state.turn}", s.game)
        close()
    }

    fun name(code: Int): String = h.builder.index.byId(CardId(code))?.name ?: "#$code"
}

/** What the search found: its value in words, the line a step a row, what it assumed, and the way to play it. */
@Composable
internal fun SolveTurnDialog(h: NeueHolders) {
    val c = Mu.colors
    val solver = h.solver
    val s = solver.solved
    MuDialog(
        "Solve this turn",
        { solver.close() },
        width = 560.dp,
        description = "The best whole turn the written effects allow, as the turn player sees the table.",
        footer = {
            if (solver.running) MuButton("Stop", { solver.stop() })
            else MuButton("Search again", { solver.start() }, enabled = solver.refusal() == null, reason = solver.refusal())
            MuButton("Close", { solver.close() }, variant = BtnVariant.GHOST)
            if (s != null && s.result.steps.isNotEmpty()) MuButton("Play it as a replay", { solver.play() }, variant = BtnVariant.PRIMARY)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val problem = solver.problem
            when {
                problem != null -> Small(problem, color = c.ink)
                solver.running || s == null -> Small("Searching every line the written effects allow…", color = c.ink)
                else -> SolvedLine(solver, s)
            }
        }
    }
}

@Composable
private fun SolvedLine(solver: TurnSolver, s: TurnSolve.Solved) {
    val c = Mu.colors
    val r = s.result
    val cards = s.fork.state.cards
    fun uidName(u: Int): String = cards[u]?.code?.let(solver::name) ?: "a card"
    val words = TurnLine.words(r.steps, s.seat, solver::name, ::uidName)
    Micro("It comes to", color = c.ink45)
    Small(r.parts?.let(TurnValue::words) ?: "nothing to measure", color = c.ink)
    HRule()
    Micro("The line", color = c.ink45)
    if (words.isEmpty()) {
        Small("Nothing is worth doing: end the turn as it stands.", color = c.ink)
    } else {
        words.forEachIndexed { i, w ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Numeral(i + 1, Modifier.width(20.dp))
                Small(w, color = c.ink)
            }
        }
    }
    s.problem?.let { Small("It could not be played out to the end: $it", color = c.ink) }
    HRule()
    Small(TurnSolve.ASSUMES, color = c.ink70)
    Mono(
        (if (r.complete) "Every line searched" else "The search was cut short: the best line found") +
            " · ${r.moves} moves · ${r.positions} tables",
    )
}
