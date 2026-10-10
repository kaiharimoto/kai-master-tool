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
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.neue.ai.playbook
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.memory.MemoryBudget
import com.kaiharimoto.mastertool.core.duel.ai.DuelPosition
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.TurnStart
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboBook
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelGuide
import com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.effects.ComboWriteButton
import com.kaiharimoto.neue.ai.guideForPrompt
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

/** Asks Ai to play its seat's turn now, in the duel's log (1.0.80; before, the side panel). */
internal fun askAiToPlay(h: NeueHolders) {
    if (!h.neue.prefs.ai.enabled) { h.neue.note = Note("Ai is off. Turn it on in Settings › Ai."); return }
    val g = h.duel.game ?: return
    // Marked as asked only once the cue went (1.0.85): a refused one is asked again when Ai is free.
    if (cueAi(h, Cue.YOUR_MOVE)) h.duel.aiAskedTurn = g.state.turn
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
    DuelResults.ending(s) ?: return
    // The person's seat: the one Ai does not play, else the bottom.
    val aiSat = h.neue.prefs.ai.enabled && (d.aiPlays || duels.aiSession != null)
    val me = if (aiSat) 1 - d.aiSeat else duels.bottom
    val mine = g.header.seats.getOrNull(me) ?: return
    val theirs = g.header.seats.getOrNull(1 - me) ?: return
    val deckId = mine.deckId ?: return
    val foeName = theirs.deckName
    // First or second from who really had turn 1 — the dice's winner's choice when the roll decided it (Phase C; before,
    // seat 0 was always logged as going first).
    val game = DuelResults.practice(
        g, me, h.prep.newId("g"), System.currentTimeMillis(),
        opponent = theirs.deckId ?: foeName, opponentName = foeName, deckId = deckId, note = "From the Duel page, turn ${s.turn}",
        // The deck as dealt, by card (Phase G: record now), and who sat across: Ai, else the person playing both seats.
        deckPrint = mine.main.takeIf { it.isNotEmpty() }?.let { Ledger.fingerprint(Deck(main = it.map(::CardId), extra = mine.extra.map(::CardId)), h.builder.index::byId) },
        source = if (aiSat) TestGame.SOURCE_AI else TestGame.SOURCE_SELF,
    )?.copy(opening = DuelResults.openingHand(g, me).takeIf { it.isNotEmpty() }) ?: return
    duels.loggedDuel = g.header.id
    h.prep.log(game)
    val word = when (game.result) {
        TestGame.WIN -> "win"
        TestGame.LOSS -> "loss"
        else -> "draw"
    }
    h.neue.note = Note("Logged to Prep: a $word against $foeName", action = "Undo") { h.prep.removeGame(game.id) }
}

/**
 * How the duel table is set now, for every move's provenance (Phase C, `Duels.context`): the seat Ai holds and its
 * knowledge while Ai sits at a table of this device's (on, playing its seat's turns or in the duel's conversation, or
 * acting), and the person's eyes at a hot-seat. A networked table has no Ai seat: both seats are people's.
 */
internal fun duelContext(h: NeueHolders): Provenance {
    val d = h.neue.prefs.duel
    val duels = h.duel
    val net = duels.role != null
    val sits = !net && h.neue.prefs.ai.enabled && (d.aiPlays || duels.aiSession != null || duels.aiActing)
    val solo = duels.game?.state?.solo == true
    return Provenance(
        aiSeat = if (sits) (if (solo) 0 else d.aiSeat) else null,
        aiKnows = if (sits) d.aiKnowledge else null,
        eyes = if (net) null else d.knowledge,
    )
}

/**
 * Ai's guide at the table (Phase C stage 2): the guide and the combos of the deck its seat plays, within `DuelGuide`'s
 * budget — and the other seat's deck's, only when Ai reads the table with full knowledge. Its key names the decks, so a
 * conversation is given it once ([AiState.sendDuel]); the block is made only when it is needed. Null when Ai's seat
 * plays no saved deck.
 */
internal fun duelGuide(h: NeueHolders): Pair<String, () -> String>? {
    val g = h.duel.game ?: return null
    if (h.duel.role != null) return null
    val d = h.neue.prefs.duel
    val seat = if (g.state.solo) 0 else d.aiSeat
    val mine = g.header.seats.getOrNull(seat)?.takeIf { it.deckId != null } ?: return null
    val theirs = g.header.seats.getOrNull(1 - seat)?.takeIf { !g.state.solo && d.aiKnowledge == DuelBrief.FULL && it.deckId != null && it.deckId != mine.deckId }
    val key = mine.deckId + (theirs?.let { "+full:${it.deckId}" } ?: "")
    return key to {
        listOfNotNull(mine, theirs).map { s ->
            val id = s.deckId!!
            DuelGuide.block(s.deckName, tableGuide(h.ai.guideForPrompt(id)), h.duel.combosNow(id).combos, theirs = s !== mine)
        }.filter { it.isNotBlank() }.joinToString("\n\n")
    }
}

/**
 * The guide as the table reads it (mastery, 1.1.43): its most useful entries within the table's room — the game plan,
 * the lines and the card roles before the sources — never just its first ones; the rest named for `playbook_search`.
 */
internal fun tableGuide(guide: String): String {
    val entries = DuelGuide.entries(guide)
    if (entries.isEmpty()) return guide
    return MemoryBudget.pick(entries, DuelGuide.GUIDE_BUDGET, kind = MemoryKind.GUIDE, scope = "guide").lines()
}

/** Whether Ai sits at this table: on, and not a networked table (there the log is the other player's). */
internal fun aiAtTable(h: NeueHolders): Boolean = h.neue.prefs.ai.enabled && h.duel.role == null && h.duel.game != null

/**
 * Hands Ai a cue (1.0.80): what the person did on the table since Ai last read — in the log's words as
 * Ai's seat may see them — its seat and knowledge, and what the cue asks. The person's moves never start
 * Ai on their own; only a cue or a message does, so a small move never costs a turn of the model.
 */
internal fun cueAi(h: NeueHolders, cue: Cue, words: String = ""): Boolean {
    val duels = h.duel
    val g = duels.game ?: return false
    // A cue given while Ai answers waits for it (1.0.85; before, it was dropped with a note).
    if (h.ai.running) {
        duels.queuedCue = cue.name to words
        h.neue.note = Note("${h.ai.name} will read that when it finishes.")
        return false
    }
    val said = words.ifBlank { cue.shown }.ifBlank { "(the table)" }
    val id = h.ai.sendDuel(said, cueContext(h, cue.ask, said), duels.aiSession, fresh = duels.aiFresh, guide = duelGuide(h)) ?: return false
    duels.aiFresh = false
    duels.aiSession = id
    duels.aiRead = g.cursor
    duels.aiResponding = false
    return true
}

/**
 * What every cue carries (1.0.85): Ai's seat and knowledge, what happened since it last read, **the table as
 * its seat sees it** (so it need not spend a round on duel_state), and its watches — then the ask.
 */
private fun cueContext(h: NeueHolders, ask: String, said: String): List<String> {
    val duels = h.duel
    val g = duels.game ?: return listOf(ask)
    val d = h.neue.prefs.duel
    val s = g.state
    val seat = if (s.solo) 0 else d.aiSeat
    val viewer = DuelBrief.viewer(d.aiKnowledge, seat)
    // Moves taken back since Ai last read leave its mark past the log's end (1.0.85: it was told "nothing new").
    val takenBack = (duels.aiRead ?: 0) > g.cursor
    val from = (duels.aiRead ?: g.floor).coerceIn(0, g.cursor)
    // Every move but Ai's own, by who made it (Phase C stage 2): a person's move on Ai's cards is logged as Ai's seat, and
    // filtering by seat dropped it.
    val lines = DuelBrief.since(g, from, seat, viewer, duels.catalog, duels.folds(g))
        // The cue's own words reach Ai as the message; not twice.
        .filterNot { it.chat && it.text.endsWith(said) }
        .map { "${it.i}. ${it.text}" }
    val knows = when (d.aiKnowledge) {
        DuelBrief.FULL -> "full knowledge"
        DuelBrief.AUTO -> "auto knowledge (duel_peek only if a hidden card would change your play)"
        else -> "your seat's knowledge only"
    }
    return buildList {
        add("At the duel table: you are ${DuelWords.seatLabel(s, seat)}, with $knows. Turn ${s.turn}, ${DuelWords.seatLabel(s, s.active)} to play, ${s.phase.label} Phase.")
        // Turns that start themselves (1.0.86): the table has drawn for Ai's seat, so it must not draw again.
        if (d.autoDraw) {
            add(TurnStart.FOR_AI)
            // Said only as it is (1.0.86, the red team): on Ai's turn with its opening not yet made, the next step is named.
            if (s.active == seat) TurnStart.next(g)?.let { step ->
                add("Your turn's draw is not made yet; the table makes it — wait for it, then move on through the phases yourself.")
            }
        }
        if (takenBack || duels.aiTookBack) add("Moves were taken back since you last read: the table below is how it stands now.")
        duels.aiTookBack = false
        if (lines.isEmpty()) add("Nothing new on the table since you last read.")
        else {
            add("What happened on the table since you last read (entries $from–${g.cursor - 1}), as your seat saw it:")
            addAll(lines.takeLast(60))
        }
        add("The table now, as your seat sees it (duel_state only if you need it again):")
        add(DuelBrief.describe(s, viewer, duels.catalog, g.header.seed, seat, duels.tally(viewer), duels.rulings))
        // The playbook's entries for this position (mastery, 1.1.43): what Ai learned about the hand and board in front of it.
        g.header.seats.getOrNull(seat)?.deckId?.let { id -> h.ai.playbook(id) }?.takeIf { it.entries.isNotEmpty() }?.let { book ->
            DuelGuide.playbook(book, DuelPosition.of(s, seat, viewer, duels.catalog)).takeIf { it.isNotBlank() }?.let(::add)
        }
        val watches = duels.liveWatches()
        if (d.aiTriggers) {
            add(
                if (watches.isEmpty()) "Your watches: none. Leave one with duel_watch for each response your hand or set cards hold."
                else "Your watches (private):\n" + watches.joinToString("\n") { "- " + DuelTriggers.describe(it) },
            )
        }
        add(ask)
    }
}

/**
 * Wakes Ai on its watches (1.0.85): what fired, the table, and a short ask — respond or let it pass. The
 * person's moves wait while it decides; a phase change it watched is held until it has.
 */
internal fun cueTriggered(h: NeueHolders) {
    val duels = h.duel
    val g = duels.game ?: return
    val hits = duels.fired
    if (hits.isEmpty() || h.ai.running) return
    duels.fired = emptyList()
    val held = duels.held
    val what = hits.joinToString("\n") { hit ->
        "- watch #${hit.watch.id} (${hit.watch.on.joinToString(", ")}${hit.watch.note.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}) fired on: ${hit.happening.words}"
    }
    val ask = buildString {
        append("Your watches fired:\n$what\n")
        if (held != null) append("They are about to leave the ${g.state.phase.label} Phase and wait on you; when you finish, the phase moves on. ")
        else append("They wait on you before their next move. ")
        append(
            "Respond now only if it is worth it: your chain link or move with duel_act, your seat only. If you let it pass, write nothing " +
                "at all — no words — and end. Decide quickly. Then keep your watches true to your hand with duel_watch (clear the ones you spent).",
        )
    }
    val said = "(trigger) " + hits.joinToString("; ") { it.happening.kind.words }
    val id = h.ai.sendDuel(said, cueContext(h, ask, said), duels.aiSession, fresh = duels.aiFresh, guide = duelGuide(h))
    if (id == null) {
        // No connection, or it would not start: the person is never left waiting.
        duels.dontWait()
        return
    }
    duels.aiFresh = false
    duels.aiSession = id
    duels.aiRead = g.cursor
    duels.aiAnswering = true
}

/** A cue kept while Ai answered, given now it is free. */
internal fun cueQueued(h: NeueHolders) {
    val (name, words) = h.duel.queuedCue ?: return
    h.duel.queuedCue = null
    val cue = Cue.entries.firstOrNull { it.name == name } ?: return
    cueAi(h, cue, words)
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
    fun update(f: (DuelPrefs) -> DuelPrefs) = neue.update { it.copy(duel = f(it.duel)) }

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
                Small("Strength", color = c.ink70)
                Segmented(
                    d.aiStrength, listOf(DuelPrefs.FAST, DuelPrefs.STRONG, DuelPrefs.MAX),
                    { when (it) { DuelPrefs.FAST -> "Fast"; DuelPrefs.MAX -> "Max"; else -> "Strong" } },
                    { v -> update { it.copy(aiStrength = v) } }, small = true,
                )
            }
            Help(
                when (d.aiStrength) {
                    DuelPrefs.FAST -> "Answers quickly, thinking lightly: for casual play."
                    DuelPrefs.MAX -> "Thinks as hard as the model can and reads more before each move: slowest, costs the most."
                    else -> "Thinks hard and checks its playbook before moving: turns take longer, and it plays its best."
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuSwitch(d.aiTriggers, { v -> update { it.copy(aiTriggers = v) } })
                    Small("Responds by itself", color = c.ink)
                }
                Help(
                    if (d.aiTriggers) "${h.ai.name} leaves watches for what its hand could answer — a Summon, an activation, leaving a phase — and the table wakes it on those alone; your move waits for its answer."
                    else "${h.ai.name} reads the table only when you cue it.",
                )
                // Behind Thinking, as in the log: what Ai waits for tells what it holds (1.0.85).
                val live = DuelTriggers.alive(duels.watches, g?.state?.turn ?: 0)
                if (d.aiTriggers && d.aiThinking && live.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Small("Watching for ${com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.kindsWords(live).lowercase()}", Modifier.weight(1f), color = c.ink70)
                        MuButton("Clear", { duels.unwatch(null) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                    }
                }
            }
            MuButton("Play this turn, ${h.ai.name}", { duels.combosOpen = false; askAiToPlay(h) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            HRule()
        }
        // The table draws for each turn (1.0.86; the draw alone from 1.0.93): a table setting, here beside Ai because it decides
        // who draws for Ai.
        FieldLabel("Turns")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuSwitch(d.autoDraw, { v -> update { it.copy(autoDraw = v) } })
            Small("Draw for each turn's player", color = c.ink)
        }
        Help(
            if (d.autoDraw) {
                "After End Turn the table draws one card for the next player — never on the first turn — and the turn waits in the " +
                    "Draw Phase: move on (N) through the Standby Phase to Main Phase 1 yourself." +
                    if (neue.prefs.ai.enabled) " A response ${h.ai.name} is watching for in the Draw Phase pauses the draw until ${h.ai.name} has answered." else ""
            } else {
                "Each turn starts in the Draw Phase with nothing drawn: draw (D) and move on (N) yourself."
            },
        )
        HRule()
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
                    // Phase D step 2: the person's go for the cards this line uses.
                    ComboWriteButton(h, combo, deckId) { duels.combosOpen = false }
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
                        // From the start of this turn (the last End Turn), past its draw and phases (1.0.86), the bottom seat's own moves.
                        val turnFrom = g.entries.subList(0, g.cursor).indexOfLast { it.action == DuelAction.EndTurn }.let { if (it < 0) g.floor else it + 1 }
                        val from = TurnStart.afterOpening(g.entries, turnFrom, g.cursor)
                        val start = g.stateAt(from)
                        val span = g.entries.subList(from, g.cursor).filter { it.seat == seat }
                        val steps = ComboRecorder.steps(start, span, duels.catalog, seat, g.header.seed)
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
