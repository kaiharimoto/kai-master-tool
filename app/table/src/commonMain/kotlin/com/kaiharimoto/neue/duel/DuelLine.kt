package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.text.DuelAnswer
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand

/**
 * The command line on the table (1.0.87), a part of [Duels] (H.3, moved unchanged): a line typed into the Spotlight, the
 * log's `/` lines or the LP pad, run for the seat at the bottom — moves, several joined with `;`, a Shortcut, a house
 * ruling, a question answered to this seat alone, or the chrome's own words.
 */
class DuelLine(private val d: Duels) {
    /**
     * A line run for the seat at the bottom (1.0.87: the Spotlight's, the log's `/` lines, the LP pad's), saying what it
     * came to. [quiet]: the Spotlight shows the refusal or the answer itself, so nothing is said at the window's foot,
     * and the line goes into [Duels.lineHistory] when it does something.
     */
    fun runLine(text: String, quiet: Boolean = false): Duels.Ran {
        val g = d.shown ?: return Duels.Ran.Refused("No duel")
        /** The table's own refusal ([Duels.act] says it once), taken back into the box when it is the box's. */
        fun refused(): Duels.Ran {
            val why = d.problem ?: "The table refused that"
            if (quiet) d.problem = null
            return Duels.Ran.Refused(why)
        }
        val ran: Duels.Ran = when (val p = DuelCommand.parse(text, g.state, d.bottom, d.catalog, g.header.seed)) {
            is DuelCommand.Parsed.Problem -> { if (!quiet) d.problem = p.text; Duels.Ran.Refused(p.text) }
            is DuelCommand.Parsed.Actions -> if (d.act(p.actions, d.bottom)) Duels.Ran.Moved else refused()
            // Moves joined with ";" (1.0.87): each its own step, in order, stopping at the first the table refuses.
            is DuelCommand.Parsed.Many -> many(p, quiet, ::refused)
            // Shortcut (Phase D §5½): the window asks what the line's own answers leave out; with nothing written, the line says so.
            is DuelCommand.Parsed.Shortcut -> if (d.shortcutPart.line(p.ask) || d.shortcutPart.open) Duels.Ran.Moved else refused()
            is DuelCommand.Parsed.Ruling -> {
                val r = d.houseRulings.keepRuling(p.code, p.card, p.text)
                d.act(DuelAction.Note("House ruling: ${r.card?.let { "$it — " } ?: ""}${r.text}", d.bottom), d.bottom)
                Duels.Ran.Chrome
            }
            // A question is answered to this seat alone: never a Chat or a Note, which the log keeps for both seats.
            is DuelCommand.Parsed.Query -> {
                val said = DuelAnswer.answer(p, g.state, d.bottom, d.catalog, g.header.seed)
                d.answer = said
                if (!quiet) d.problem = said
                if (p.uid != null) d.inspected = p.uid
                Duels.Ran.Answered(said)
            }
            is DuelCommand.Parsed.Ui -> {
                var why: String? = null
                when (p.kind) {
                    DuelCommand.UiKind.OPEN -> {
                        val seat = p.seat
                        val pile = p.pile
                        if (seat != null && pile != null && d.strip != seat to pile) d.openPile(seat, pile)
                    }
                    DuelCommand.UiKind.CLOSE -> d.closeStrip()
                    DuelCommand.UiKind.READ -> d.inspected = p.uid
                    DuelCommand.UiKind.CUE -> if (d.cueAi?.invoke(p) != true) {
                        // No Ai at the table (1.0.87, the red team): "pass" and "no response" with a chain open pass
                        // priority across a hot-seat, as the response window's own No response does.
                        val passes = p.cue == AiCue.PASS || p.cue == AiCue.NO_RESPONSE
                        if (passes && !g.state.solo && g.state.chain.isNotEmpty()) {
                            if (!d.act(DuelAction.Answer(d.bottom, respond = false), d.bottom)) why = d.problem ?: "The table refused that"
                        } else why = "No Ai sits at this table."
                    }
                    DuelCommand.UiKind.SWAP -> d.swap()
                    DuelCommand.UiKind.UNDO -> d.undo()
                    DuelCommand.UiKind.REDO -> d.redo()
                }
                if (why != null) {
                    if (quiet) d.problem = null else d.problem = why
                    Duels.Ran.Refused(why)
                } else Duels.Ran.Chrome
            }
        }
        if (quiet && ran.ok) d.spot.rememberLine(text)
        return ran
    }

    /**
     * A `;` line, made a step at a time (1.0.87). Into the past with Insert here, the steps go in together, once (the red
     * team: only the first went back). Stopped partway — Ai's watch fired on a step, a phase change held for it, or the
     * table refused one — the steps made stay made and [Duels.Ran.Partial] carries the rest, never the whole line, so Enter
     * again does not make the first step twice.
     */
    private fun many(p: DuelCommand.Parsed.Many, quiet: Boolean, refused: () -> Duels.Ran): Duels.Ran {
        val flat = p.parts.flatMap { it.actions }
        d.insertAfter?.let { at ->
            if (d.network.role == null && !d.playing && flat.any { !it.social }) {
                d.insertAfter = null
                return if (d.replayer.insertPast(at, flat, d.bottom)) Duels.Ran.Moved else refused()
            }
        }
        p.parts.forEachIndexed { k, part ->
            val rest = p.lines.drop(k).joinToString("; ")
            if (k > 0 && d.aiWatch.waitingOnAi) return Duels.Ran.Partial(k, p.parts.size, rest, waiting = true, why = null)
            if (!d.act(part.actions, d.bottom)) {
                if (k == 0) return refused()
                val why = d.problem
                if (quiet) d.problem = null
                return Duels.Ran.Partial(k, p.parts.size, rest, waiting = d.aiWatch.waitingOnAi, why = why)
            }
            // A phase change held for Ai holds the steps after it too.
            if (d.aiWatch.held != null && k < p.parts.size - 1) return Duels.Ran.Partial(k + 1, p.parts.size, p.lines.drop(k + 1).joinToString("; "), waiting = true, why = null)
        }
        return Duels.Ran.Moved
    }
}
