package com.kaiharimoto.neue.duel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboBook
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch

/** What Ai is asked when it is to play a turn: its seat, its knowledge, and where to stop. */
internal fun turnPrompt(h: NeueHolders): String {
    val g = h.duel.game ?: return ""
    val d = h.neue.prefs.duel
    val seat = if (g.state.solo) 0 else d.aiSeat
    val name = DuelWords.seatName(g.state, seat)
    val knows = when (d.aiKnowledge) {
        DuelBrief.FULL -> "full knowledge (you may read everything)"
        DuelBrief.AUTO -> "auto knowledge (your seat's eyes; duel_peek only if a hidden card would change your play, and say why)"
        DuelBrief.OPPONENT -> "the other seat's knowledge"
        else -> "your seat's knowledge only"
    }
    return "You are playing $name (seat $seat) in the duel on the Duel page, with $knows. Read the duel-table skill if you have not, " +
        "read the table with duel_state, then play $name's turn with duel_act — draw for the turn if it is the Draw Phase, play your " +
        "cards as their text says, stop and tell me where I could respond to something that matters, and end the turn when you are done."
}

/** Asks Ai to play its seat's turn now, in the duel's log (1.0.80; before, the side panel). */
internal fun askAiToPlay(h: NeueHolders) {
    if (!h.neue.prefs.ai.enabled) { h.neue.note = Note("Ai is off. Turn it on in Settings › Ai."); return }
    val g = h.duel.game ?: return
    h.duel.aiAskedTurn = g.state.turn
    cueAi(h, Cue.YOUR_MOVE)
}

/**
 * The words the person can hand Ai at the table without typing (1.0.80, kai: "there should be assigned
 * buttons where I make some actions and then I can have it pick up based on what I did"), each with what
 * it asks of Ai. [shown] is what the log shows the person said.
 */
internal enum class Cue(val shown: String, val ask: String) {
    SAY("", "They said this to you at the table. Answer briefly; move only if they ask you to."),
    YOUR_MOVE("Your move", "Your move: respond to what they did, or play your turn with duel_act. Stop where they could respond."),
    CATCH_UP("Catch up", "Read what they did. If a move of theirs is unclear, or could have been an activation you would answer, ask with ask_user. Move nothing yet."),
    NO_RESPONSE("No response", "They do not respond to your last move. Go on."),
    DONE("Done responding", "They responded on the table: their moves are above. Go on from there, resolving the chain as it now stands."),
    PASS("Over to you", "They activated something and pass priority to you: respond with a chain link, or say you do not and let them resolve it."),
}

/**
 * A duel that has ended — a concession, or life points at 0 — logged to Prep once as a practice game
 * against the other deck (1.0.80, Ai: "save this game automatically with log_game"), when both seats
 * played known decks and the person has not turned it off. A note says so, with Undo.
 */
internal fun logFinishedDuel(h: NeueHolders) {
    val duels = h.duel
    val g = duels.game ?: return
    val s = g.state
    val d = h.neue.prefs.duel
    if (s.solo || !d.logGames || duels.role != null || duels.replay != null || duels.loggedDuel == g.header.id) return
    val loser = s.conceded ?: s.seats.indexOfFirst { it.lp <= 0 }.takeIf { it >= 0 } ?: return
    // The person's seat: the one Ai does not play, else the bottom.
    val me = if (h.neue.prefs.ai.enabled && (d.aiPlays || duels.aiSession != null)) 1 - d.aiSeat else duels.bottom
    val mine = g.header.seats.getOrNull(me) ?: return
    val theirs = g.header.seats.getOrNull(1 - me) ?: return
    val deckId = mine.deckId ?: return
    val foeName = theirs.deckName ?: return
    duels.loggedDuel = g.header.id
    val game = com.kaiharimoto.mastertool.core.prep.TestGame(
        id = h.prep.newId("g"),
        at = System.currentTimeMillis(),
        deckId = deckId,
        opponent = theirs.deckId ?: foeName,
        opponentName = foeName,
        // Seat 0 takes the first turn.
        turn = if (me == 0) com.kaiharimoto.mastertool.core.prep.TestGame.FIRST else com.kaiharimoto.mastertool.core.prep.TestGame.SECOND,
        result = if (loser == me) com.kaiharimoto.mastertool.core.prep.TestGame.LOSS else com.kaiharimoto.mastertool.core.prep.TestGame.WIN,
        note = "From the Duel page, turn ${s.turn}",
    )
    h.prep.log(game)
    h.neue.note = Note("Logged to Prep: a ${if (loser == me) "loss" else "win"} against $foeName", action = "Undo") { h.prep.removeGame(game.id) }
}

/** Whether Ai sits at this table: on, and not a networked table (there the log is the other player's). */
internal fun aiAtTable(h: NeueHolders): Boolean = h.neue.prefs.ai.enabled && h.duel.role == null && h.duel.game != null

/**
 * Hands Ai a cue (1.0.80): what the person did on the table since Ai last read — in the log's words as
 * Ai's seat may see them — its seat and knowledge, and what the cue asks. The person's moves never start
 * Ai on their own; only a cue or a message does, so a small move never costs a turn of the model.
 */
internal fun cueAi(h: NeueHolders, cue: Cue, words: String = "") {
    val duels = h.duel
    val g = duels.game ?: return
    if (h.ai.running) { h.neue.note = Note("${h.ai.name} is still answering."); return }
    val d = h.neue.prefs.duel
    val s = g.state
    val seat = if (s.solo) 0 else d.aiSeat
    val viewer = DuelBrief.viewer(d.aiKnowledge, seat)
    val from = (duels.aiRead ?: g.floor).coerceIn(0, g.cursor)
    val lines = com.kaiharimoto.mastertool.core.duel.net.DuelHost.lines(g, from, viewer, duels.catalog)
        .filter { it.seat != seat }
        .map { "${it.i}. ${it.text}" }
    val knows = when (d.aiKnowledge) {
        DuelBrief.FULL -> "full knowledge"
        DuelBrief.AUTO -> "auto knowledge (duel_peek only if a hidden card would change your play)"
        else -> "your seat's knowledge only"
    }
    val context = buildList {
        add("At the duel table: you are ${DuelWords.seatLabel(s, seat)}, with $knows. Turn ${s.turn}, ${DuelWords.seatLabel(s, s.active)} to play, ${s.phase.label} Phase.")
        if (lines.isEmpty()) add("Nothing new on the table since you last read.")
        else {
            add("What happened on the table since you last read (entries $from–${g.cursor - 1}), as your seat saw it:")
            addAll(lines.takeLast(60))
        }
        add(cue.ask)
    }
    val said = words.ifBlank { cue.shown }
    val id = h.ai.sendDuel(said, context, duels.aiSession, fresh = duels.aiFresh) ?: return
    duels.aiFresh = false
    duels.aiSession = id
    duels.aiRead = g.cursor
    duels.aiResponding = false
}

/**
 * Ai and combos on the Duel page (1.0.76): which seat Ai plays, what it may know (its own seat's eyes,
 * the other's, everything, or *auto* — its own, peeking when it judges it must, every peek in the log),
 * how fast its moves play out, whether it takes its turns by itself; and the deck's combos — kept with
 * the deck, recorded from this turn, run on the table step by step. Combos need no Ai.
 */
@Composable
internal fun DuelAiDialog(h: NeueHolders) {
    val c = Mu.colors
    val duels = h.duel
    val neue = h.neue
    val d = neue.prefs.duel
    val g = duels.game
    val scope = rememberCoroutineScope()
    val seat = if (g?.state?.solo == true) 0 else duels.bottom
    val deckId = duels.deckOf(seat) ?: if (g == null) h.builder.deckId else null
    var book by remember(deckId) { mutableStateOf(ComboBook()) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(deckId) { if (deckId != null) book = duels.combos(deckId) }
    fun update(f: (com.kaiharimoto.mastertool.core.duel.DuelPrefs) -> com.kaiharimoto.mastertool.core.duel.DuelPrefs) = neue.update { it.copy(duel = f(it.duel)) }

    MuDialog("Ai and combos", { duels.combosOpen = false }, width = 600.dp) {
        if (neue.prefs.ai.enabled && g != null) {
            FieldLabel("${h.ai.name} at the table")
            if (!g.state.solo) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small("Plays", color = c.ink70)
                    Segmented(d.aiSeat, listOf(0, 1), { DuelWords.seatName(g.state, it) }, { v -> update { it.copy(aiSeat = v) } }, small = true)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("Knows", color = c.ink70)
                Segmented(
                    d.aiKnowledge, listOf(DuelBrief.SELF, DuelBrief.AUTO, DuelBrief.FULL),
                    { when (it) { DuelBrief.SELF -> "Its seat's eyes"; DuelBrief.AUTO -> "Auto"; else -> "Everything" } },
                    { v -> update { it.copy(aiKnowledge = v) } }, small = true,
                )
            }
            Help(
                when (d.aiKnowledge) {
                    DuelBrief.SELF -> "Only what that player could see: the honest opponent."
                    DuelBrief.AUTO -> "Its own seat's eyes, and a peek when it judges a hidden card would change its play — every peek written in the log with its reason."
                    else -> "Every card, the decks' order aside: for testing a line, not for a fair duel."
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("Pace", color = c.ink70)
                Segmented(d.aiPace, listOf(0, 250, 450, 900), { when (it) { 0 -> "At once"; 250 -> "Quick"; 450 -> "Watchable"; else -> "Slow" } }, { v -> update { it.copy(aiPace = v) } }, small = true)
            }
            if (!g.state.solo) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuSwitch(d.aiPlays, { v -> update { it.copy(aiPlays = v) } })
                    Small("Takes its seat's turns by itself", color = c.ink)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuSwitch(d.aiBothSeats, { v -> update { it.copy(aiBothSeats = v) } })
                    Small("May move your cards too", color = c.ink)
                }
                Help(if (d.aiBothSeats) "${h.ai.name} may move either seat's cards, when you ask it to." else "${h.ai.name} moves only its own seat's cards; on your turn it asks you to move the phase on.")
            }
            MuButton("Play this turn, ${h.ai.name}", { duels.combosOpen = false; askAiToPlay(h) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            HRule()
        }
        FieldLabel("Combos")
        if (deckId == null) {
            Help("Combos are kept with a deck from the library: start a duel with a saved deck to keep one.")
        } else {
            if (book.combos.isEmpty()) Help("None kept for this deck yet. Play a line, then record it here — it plays again against any shuffle.")
            book.combos.forEach { combo ->
                val missing = g?.let { ComboRunner.missing(it.state, seat, combo, duels.catalog) }.orEmpty()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        RowText(combo.name, color = c.ink)
                        Small(
                            "${combo.steps.size} steps · needs ${combo.needs.joinToString().ifBlank { "nothing" }}" +
                                if (missing.isNotEmpty()) " · not in hand: ${missing.joinToString()}" else "",
                            color = c.ink45, maxLines = 2,
                        )
                    }
                    MuButton("Run", {
                        duels.combosOpen = false
                        scope.launch { neue.note = Note("${combo.name}: ${duels.playOut(combo.steps, seat, d.aiPace.toLong().coerceAtLeast(250)).text.lineSequence().first()}") }
                    }, size = BtnSize.SM, enabled = g != null && missing.isEmpty() && !duels.playing, reason = if (missing.isNotEmpty()) "Not in hand: ${missing.joinToString()}" else null)
                    MuButton("Delete", {
                        scope.launch {
                            val next = book.copy(combos = book.combos - combo)
                            duels.saveCombos(deckId, next)
                            book = next
                        }
                    }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
                HRule()
            }
            if (g != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuInput(name, { name = it }, Modifier.weight(1f), placeholder = "Name the line played this turn", dense = true)
                    MuButton("Record this turn", {
                        // From the start of this turn (the last End Turn), the bottom seat's own moves.
                        val from = g.entries.subList(0, g.cursor).indexOfLast { it.action == DuelAction.EndTurn }.let { if (it < 0) g.floor else it + 1 }
                        val start = g.stateAt(from)
                        val span = g.entries.subList(from, g.cursor).filter { it.seat == seat }
                        val steps = ComboRecorder.steps(start, span, duels.catalog)
                        if (steps.isEmpty()) { neue.note = Note("Nothing played this turn to record."); return@MuButton }
                        val combo = Combo("c${System.currentTimeMillis()}", name.ifBlank { "Line ${book.combos.size + 1}" }, deckId, ComboRecorder.needs(start, seat, span, duels.catalog), steps, created = System.currentTimeMillis())
                        scope.launch {
                            val next = book.copy(combos = book.combos + combo)
                            duels.saveCombos(deckId, next)
                            book = next
                            name = ""
                            neue.note = Note("Kept “${combo.name}”: ${steps.size} steps")
                        }
                    }, size = BtnSize.SM)
                }
            }
        }
    }
}
