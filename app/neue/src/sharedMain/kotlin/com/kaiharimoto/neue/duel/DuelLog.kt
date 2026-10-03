package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelFolds
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/**
 * The duel's log and its chat — and, from 1.0.80, Ai's (kai: "it feels redundant to have basically two
 * chat boxes … what if I could operate and communicate with the AI using the log chat as the main one,
 * and be able to toggle the AI thinking"): the table's lines and what was said, newest at the bottom, a
 * rule at each turn, with what Ai says set in among them by time, and its thinking and its tool lines
 * behind the head's Thinking switch. The box talks to Ai at a table it sits at (a leading `/` is a
 * command), else to the other player. Under it, the buttons that cue Ai without typing — Your move,
 * Catch up, No response, Respond, Over to you — and any question Ai asks, with No response among its
 * answers. A line or two picked: insert the next move after it, or keep the span as a combo.
 */
@Composable
internal fun DuelLogRail(h: NeueHolders, duels: Duels, game: DuelGame, viewer: Int?, modifier: Modifier = Modifier, head: @Composable () -> Unit = {}) {
    val c = Mu.colors
    val ai = h.ai
    val refused = duels.refused()
    val remote = duels.remoteLines
    val guest = duels.role == Duels.NetRole.GUEST
    val seated = aiAtTable(h)
    val thinking = h.neue.prefs.duel.aiThinking
    val talk = ai.session?.takeIf { seated && it.mode == AiSession.MODE_DUEL && it.id == duels.aiSession }
    val folds = remember(game.header, viewer, duels.catalog) { logFolds(game, viewer, duels.catalog) }
    val table = remember(game.header, game.entries, game.cursor, viewer, refused, remote, guest) {
        if (guest) remoteLog(remote, duels.mySeat) else logLines(game, folds, duels, refused)
    }
    // Ai's hidden cards never named to the person in what it says (1.0.81): the original behind Thinking.
    val aiSeat = if (game.state.solo) 0 else h.neue.prefs.duel.aiSeat
    val redact: (String) -> com.kaiharimoto.mastertool.core.duel.ai.Secrets.Redacted = { text ->
        com.kaiharimoto.mastertool.core.duel.ai.Secrets.redact(text, game.state, 1 - aiSeat, aiSeat, duels.catalog)
    }
    val said = remember(talk?.turns, thinking, game.cursor, aiSeat) { talk?.let { aiLines(it, thinking, redact) }.orEmpty() }
    // Ai's words among the table's lines, by when each was said.
    val lines = remember(table, said) { if (said.isEmpty()) table else (table + said).sortedBy { it.at } }
    val live = talk != null && ai.running
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, live, ai.streaming.length / 120) {
        val info = list.layoutInfo
        val last = info.totalItemsCount - 1
        // Follows only a reader at the end (1.0.85): reading back, or picking a line, while Ai talks stays put.
        val seen = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last >= 0 && (seen < 0 || seen >= last - 3)) list.scrollToItem(last, 1_000_000)
    }
    val chatFocus = remember { FocusRequester() }
    // Only a request made after the box appeared takes the keyboard (1.0.85: an old one did, each time the box
    // came back on screen, and the next hotkeys typed into it).
    val askedBefore = remember { duels.chatFocus }
    LaunchedEffect(duels.chatFocus) { if (duels.chatFocus > askedBefore) runCatching { chatFocus.requestFocus() } }
    val opened = remember(duels.aiSession) { mutableStateMapOf<String, Boolean>() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("Log", color = c.ink70)
            Mono("${if (guest) remote.size else game.cursor - game.floor}", Modifier.weight(1f), color = c.ink45)
            if (seated) {
                Box(
                    Modifier.border(1.dp, if (thinking) c.ink else c.ink25).background(if (thinking) c.ink else c.paper)
                        .cursorPointer(caption = if (thinking) "Hide its thinking" else "Show its thinking")
                        .muClickable { h.neue.update { it.copy(duel = it.duel.copy(aiThinking = !thinking)) } }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) { Micro("Thinking", color = if (thinking) c.paper else c.ink70) }
            }
            if (talk != null && !live) {
                Box(
                    Modifier.border(1.dp, c.ink25).background(c.paper)
                        .cursorPointer(caption = "Start a new conversation with ${ai.name}")
                        .muClickable { duels.newTopic() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) { Micro("New topic", color = c.ink70) }
            }
            head()
        }
        HRule()
        TurnTally(duels, game, viewer)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(lines) { line -> LogRow(h, duels, line, opened) }
            if (live && thinking && ai.reasoning.isNotBlank()) item { Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { com.kaiharimoto.neue.ai.ReasoningView(ai, ai.reasoning, live = true, opened) } }
            if (live && thinking) items(ai.activity) { Box(Modifier.padding(horizontal = 12.dp)) { com.kaiharimoto.neue.ai.ActivityLine(it.summary.ifBlank { it.name }, it.isError) } }
            if (live && ai.streaming.isNotEmpty()) item { Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { com.kaiharimoto.neue.ai.ReplyView(ai, redact(ai.streaming).text, live = true) } }
        }
        HRule()
        if (duels.logPick.isNotEmpty() && !guest) PickBar(h, duels, game)
        if (seated && !guest && duels.replay == null) AiCues(h, duels, game, talk != null)
        MuInput(
            duels.chat,
            { duels.chat = it },
            Modifier.fillMaxWidth().padding(8.dp),
            placeholder = when {
                seated -> "Say something to ${ai.name} · / for a command · Enter"
                duels.role != null -> "Say something · / for a command · Enter"
                else -> "Say something · Enter"
            },
            dense = true,
            focusRequester = chatFocus,
            onSubmit = { submit(h, duels, duels.chat, seated) },
        )
    }
}

/** What was typed in the log's box: a command, words to Ai at its table, or words across the table. */
private fun submit(h: NeueHolders, duels: Duels, text: String, seated: Boolean) {
    val t = text.trim()
    if (t.isEmpty()) return
    when {
        t.startsWith("/") -> if (duels.run(t.drop(1))) duels.chat = ""
        seated -> {
            // Said while Ai answers: in the log now, read by Ai when it finishes (1.0.85).
            duels.say(t)
            cueAi(h, Cue.SAY, t)
        }
        else -> duels.say(t)
    }
}

@Composable
private fun LogRow(h: NeueHolders, duels: Duels, line: LogLine, opened: MutableMap<String, Boolean>) {
    val c = Mu.colors
    when (line) {
        is LogLine.Turn -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro(line.text, color = c.ink)
            Box(Modifier.weight(1f).padding(start = 8.dp)) { HRule() }
        }
        is LogLine.Said -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
            Box(Modifier.background(c.ink06).padding(horizontal = 6.dp, vertical = 3.dp)) { Small(line.text, color = c.ink) }
        }
        is LogLine.Done -> {
            val picked = line.i != null && line.i in duels.logPick
            val m = Modifier.fillMaxWidth().then(if (picked) Modifier.background(c.ink06) else Modifier)
                .then(if (line.i != null) Modifier.cursorPointer(caption = if (picked) "Unpick" else "Pick").muClickable { duels.pickLine(line.i) } else Modifier)
                .padding(horizontal = 12.dp, vertical = 2.dp)
            if (line.struck) Small("${line.text} — no longer fits", m, color = c.ink25)
            else Small(line.text, m, color = if (line.mine) c.ink else c.ink70)
        }
        is LogLine.Noted -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Box(Modifier.border(1.dp, c.ink).padding(horizontal = 6.dp, vertical = 3.dp)) { Small(line.text, color = c.ink) }
        }
        is LogLine.AiSays -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { com.kaiharimoto.neue.ai.ReplyView(h.ai, line.text) }
        is LogLine.AiThought -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { com.kaiharimoto.neue.ai.ReasoningView(h.ai, line.text, live = false, opened) }
        is LogLine.AiDid -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { com.kaiharimoto.neue.ai.ActivityLine(line.text, line.isError) }
    }
}

/**
 * The buttons that hand Ai a cue (1.0.80): what fits the table now. Ai's link on top of the chain: No
 * response or Respond. Responding: Done. The person's own link on top: Over to you. Otherwise: Your move,
 * Catch up. While Ai answers: what it is doing, and Stop. A question it asks stands here too.
 */
@Composable
private fun AiCues(h: NeueHolders, duels: Duels, game: DuelGame, talking: Boolean) {
    val c = Mu.colors
    val ai = h.ai
    val s = game.state
    val seat = if (s.solo) 0 else h.neue.prefs.duel.aiSeat
    val q = ai.question
    if (q != null && talking) {
        Box(Modifier.fillMaxWidth().padding(8.dp)) { com.kaiharimoto.neue.ai.QuestionCard(ai, q) }
        return
    }
    // What Ai's watches wait for, by kind, never by card — behind Thinking, as the rest of its plans are (1.0.85).
    val live = com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.alive(duels.watches, s.turn)
    if (h.neue.prefs.duel.aiThinking && h.neue.prefs.duel.aiTriggers && live.isNotEmpty() && !duels.aiAnswering) {
        Small(
            "${ai.name} is watching for ${com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers.kindsWords(live).lowercase()}",
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), color = c.ink45, maxLines = 2,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val top = s.chain.lastOrNull()
        when {
            // Woken by a watch (1.0.85): the person's moves wait on its answer, unless they go on.
            duels.aiAnswering || duels.held != null -> {
                com.kaiharimoto.neue.ai.avatar.AiMark(18.dp, name = ai.name)
                Small(
                    if (duels.held != null) "${ai.name} may respond before the phase moves on" else "${ai.name} may respond — your move waits",
                    Modifier.weight(1f), color = c.ink, maxLines = 1,
                )
                MuButton("Don't wait", { duels.dontWait() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            ai.running && talking -> {
                com.kaiharimoto.neue.ai.avatar.AiMark(18.dp, name = ai.name)
                Small(ai.working ?: ai.status ?: "${ai.name} is thinking", Modifier.weight(1f), color = c.ink70, maxLines = 1)
                MuButton("Stop", { ai.stop() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            duels.aiResponding -> {
                Small("Respond on the table, then:", Modifier.weight(1f), color = c.ink70, maxLines = 1)
                MuButton("Done", { duels.say(Cue.DONE.shown); cueAi(h, Cue.DONE) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            }
            top != null && top.seat == seat && !s.solo -> {
                MuButton("No response", { duels.say(Cue.NO_RESPONSE.shown); cueAi(h, Cue.NO_RESPONSE) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                MuButton("Respond", { duels.aiResponding = true }, size = BtnSize.SM)
                Box(Modifier.weight(1f))
            }
            top != null && top.seat != seat && !s.solo -> {
                MuButton("Over to you", { duels.say(Cue.PASS.shown); cueAi(h, Cue.PASS) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                Small("or keep acting: you hold priority", Modifier.weight(1f), color = c.ink45, maxLines = 1)
            }
            else -> {
                MuButton(Cue.YOUR_MOVE.shown, { duels.say(Cue.YOUR_MOVE.shown); askAiToPlay(h) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                MuButton(Cue.CATCH_UP.shown, { duels.say(Cue.CATCH_UP.shown); cueAi(h, Cue.CATCH_UP) }, size = BtnSize.SM)
                Box(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One line picked: the next move goes in after it (a phase gone by). Two: the span between them kept as
 * one of the deck's combos (1.0.80, Ai: "record the double-e4 line … so we can replay it from any shuffle").
 */
@Composable
private fun PickBar(h: NeueHolders, duels: Duels, game: DuelGame) {
    val c = Mu.colors
    val picks = duels.logPick.sorted()
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (duels.insertAfter != null) Small("Your next move goes in after line ${duels.insertAfter!! - 1}.", color = c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (picks.size == 1) {
                MuButton(if (duels.insertAfter == null) "Insert here" else "Insert at the end", {
                    duels.insertAfter = if (duels.insertAfter == null) picks[0] + 1 else null
                }, size = BtnSize.SM)
            }
            MuButton("Save as combo", { saveSpan(h, duels, game, picks.first(), picks.last()) }, size = BtnSize.SM)
            Box(Modifier.weight(1f))
            MuButton("Clear", { duels.logPick = emptyList(); duels.insertAfter = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
    }
}

/** Entries [from]..[to] (both picked) kept as a combo of the deck the bottom seat plays. */
private fun saveSpan(h: NeueHolders, duels: Duels, game: DuelGame, from: Int, to: Int) {
    val seat = if (game.state.solo) 0 else duels.bottom
    val deckId = duels.deckOf(seat) ?: run { h.neue.note = Note("Combos are kept with a library deck: start the duel with a saved deck."); return }
    val start = game.stateAt(from)
    val span = game.entries.subList(from, (to + 1).coerceAtMost(game.cursor)).filter { it.seat == seat || it.seat == null }
    val steps = com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder.steps(start, span, duels.catalog)
    if (steps.isEmpty()) { h.neue.note = Note("Nothing in those lines to keep."); return }
    val needs = com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder.needs(start, seat, span, duels.catalog)
    duels.saveSpan(deckId, "Line from turn ${start.turn}", needs, steps) { n -> h.neue.note = Note("Kept “$n”: ${steps.size} steps, in Combos") }
    duels.logPick = emptyList()
}

internal sealed interface LogLine {
    /** When it happened, to set Ai's words among the table's. */
    val at: Long
    data class Turn(val text: String, override val at: Long = 0L) : LogLine
    /** A move; [i] its entry, to pick it. */
    data class Done(val text: String, val mine: Boolean, val struck: Boolean = false, val i: Int? = null, override val at: Long = 0L) : LogLine
    data class Noted(val text: String, override val at: Long = 0L) : LogLine
    data class Said(val text: String, override val at: Long = 0L) : LogLine
    data class AiSays(val text: String, override val at: Long) : LogLine
    data class AiThought(val text: String, override val at: Long) : LogLine
    data class AiDid(val text: String, val isError: Boolean, override val at: Long) : LogLine
}

/**
 * Ai's side of the duel's conversation: what it said — its hidden cards put as "a card", the words as it
 * wrote them kept as a thought — and, with Thinking on, how it thought and what it did.
 */
private fun aiLines(session: AiSession, thinking: Boolean, redact: (String) -> com.kaiharimoto.mastertool.core.duel.ai.Secrets.Redacted): List<LogLine> = buildList {
    session.turns.forEach { turn ->
        val at = turn.at
        when {
            turn.role == Role.USER && turn.isToolResults -> if (thinking) turn.toolResults.forEach { add(LogLine.AiDid(it.summary.ifBlank { it.name }, it.isError, at)) }
            turn.role == Role.USER -> Unit // the person's words are in the log already, as they said them
            else -> {
                if (thinking) {
                    turn.parts.filterIsInstance<Part.Reasoning>().forEach { add(LogLine.AiThought(it.text, at)) }
                    turn.parts.filterIsInstance<Part.Activity>().forEach { add(LogLine.AiDid(it.summary.ifBlank { it.name }, it.isError, at)) }
                }
                turn.text.takeIf { it.isNotBlank() }?.let { text ->
                    val r = redact(text)
                    if (r.changed && thinking) add(LogLine.AiThought("Private — as written: $text", at))
                    add(LogLine.AiSays(r.text, at))
                }
            }
        }
    }
}

/** The guest's log: the lines the host sent it, a rule at each new turn. */
private fun remoteLog(lines: List<com.kaiharimoto.mastertool.core.duel.net.Line>, me: Int): List<LogLine> {
    val out = ArrayList<LogLine>()
    var turn = 0
    lines.forEach { l ->
        if (l.turn != turn) { turn = l.turn; out += LogLine.Turn("Turn $turn") }
        out += if (l.chat) LogLine.Said(l.text) else LogLine.Done(l.text, l.seat == me)
    }
    return out
}

/** One entry of the log in words, as [DuelFolds] keeps it: what it says, and whether the table took it. */
internal class LogRead(val text: String, val applied: Boolean)

/**
 * The log's words, read once an entry and kept (1.0.86): a move reads one entry, an undo, a redo or a
 * replay's tick reads none. Before, every change of the cursor folded and worded the whole duel again.
 */
internal fun logFolds(game: DuelGame, viewer: Int?, catalog: com.kaiharimoto.mastertool.core.duel.DuelCatalog): DuelFolds<LogRead> =
    DuelFolds(game.header) { e, before, after, applied ->
        val text = if (e.action == DuelAction.EndTurn) "Turn ${after.turn} · ${DuelWords.seatName(after, after.active)}"
        else DuelWords.say(before, after, e, viewer, catalog)
        LogRead(text, applied)
    }

private fun logLines(game: DuelGame, folds: DuelFolds<LogRead>, duels: Duels, refused: Set<Int>): List<LogLine> {
    val reads = folds.sync(game.entries).results(game.cursor)
    val out = ArrayList<LogLine>(reads.size - game.floor.coerceAtMost(reads.size) + 8)
    for (i in game.floor until reads.size) {
        val e = game.entries[i]
        val r = reads[i]
        if (i == game.floor) out += LogLine.Turn("Turn 1", e.at)
        out += when (e.action) {
            is DuelAction.Chat -> LogLine.Said(r.text, e.at)
            DuelAction.EndTurn -> LogLine.Turn(r.text, e.at)
            is DuelAction.Note -> LogLine.Noted(r.text, e.at)
            is DuelAction.Thinking, is DuelAction.Ping -> LogLine.Done(r.text, false, at = e.at)
            else -> LogLine.Done(r.text, e.seat == duels.bottom, struck = !r.applied || e.i in refused, i = i, at = e.at)
        }
    }
    if (game.floor >= game.cursor) out += LogLine.Turn("Turn 1")
    return out
}
