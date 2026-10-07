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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.effects.ScriptBook
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelFolds
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.DuelTriggers
import com.kaiharimoto.mastertool.core.duel.ai.Secrets
import com.kaiharimoto.mastertool.core.duel.net.Line
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

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
fun DuelLogRail(h: TableHost, duels: Duels, game: DuelGame, viewer: Int?, modifier: Modifier = Modifier, head: @Composable () -> Unit = {}) {
    val c = Mu.colors
    val ai = h.ai
    val refused = duels.refused()
    val remote = duels.remoteLines
    val guest = duels.role == Duels.NetRole.GUEST
    // Watching Ai vs Ai (the design review, finding 1): no box to type in, no cues for Ai at the person's table — the
    // log is read, not written.
    val watching = duels.spectating
    val seated = ai?.atTable() == true && !watching
    val thinking = h.duelPrefs.aiThinking
    val talk = ai?.talk()?.takeIf { seated }
    // A Shortcut's line names its effect by its short name (Phase D §5½): the written effects' book, when the table has one.
    val book = duels.shortcuts()?.book ?: ScriptBook.EMPTY
    val folds = remember(game.header, viewer, duels.catalog, book) { logFolds(game, viewer, duels.catalog, book) }
    val rolling = duels.diceRolling
    val inAir = duels.chanceRolling
    // Which lines are the person's own reads the bottom seat: a seat swapped colours them again (1.0.92; it was stale).
    val bottom = duels.bottom
    val table = remember(game.header, game.entries, game.cursor, viewer, refused, remote, guest, rolling, inAir, bottom) {
        if (guest) remoteLog(remote, duels.mySeat) else logLines(game, folds, bottom, refused, rolling, inAir)
    }
    // Ai's hidden cards never named to the person in what it says (1.0.81): the original behind Thinking. The names are
    // read once a table, and the patterns made once a list of names, each line redacted once and kept (1.0.92) — the same
    // words as before, no longer read again on every move.
    val aiSeat = if (game.state.solo) 0 else h.duelPrefs.aiSeat
    val secret = remember(game.state, aiSeat, duels.catalog) { Secrets.names(game.state, 1 - aiSeat, aiSeat, duels.catalog) }
    val redactor = remember(secret) { Secrets.Redactor(secret) }
    val own = ai?.ownWords == true
    val said = remember(talk?.turns, thinking, redactor, own) { talk?.let { aiLines(it, thinking, redactor::redact, own) }.orEmpty() }
    // Ai's words among the table's lines, by when each was said.
    // A Lounge room's own talk (docs/LOUNGE.md) stands among them too.
    val room = h.roomChat
    val lines = remember(table, said, room) {
        val talk = said + room.map { LogLine.Said("${it.who}: ${it.text}", it.at) }
        if (talk.isEmpty()) table else (table + talk).sortedBy { it.at }
    }
    // Each line its own key, so the list keeps what is on screen as lines come in (1.0.92).
    val keys = remember(lines) { lineKeys(lines) }
    val live = talk != null && ai?.running == true
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, live, (ai?.streaming?.length ?: 0) / 120) {
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
                        .muClickable { h.updateDuelPrefs { it.copy(aiThinking = !thinking) } }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) { Micro("Thinking", color = if (thinking) c.paper else c.ink70) }
            }
            if (talk != null && !live && !own) {
                Box(
                    Modifier.border(1.dp, c.ink25).background(c.paper)
                        .cursorPointer(caption = "Start a new conversation with ${ai?.name}")
                        .muClickable { duels.newTopic() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) { Micro("New topic", color = c.ink70) }
            }
            head()
        }
        HRule()
        TurnTally(duels, game, viewer)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            // A match over: its result is the log's last line, in ink at weight 500, kept after the bar is closed.
            val result = if (watching && !duels.match.running) lines.lastIndex else -1
            itemsIndexed(lines, key = { i, _ -> keys[i] }) { i, line -> LogRow(h, duels, line, opened, strong = i == result) }
            if (ai != null && live && thinking && ai.reasoning.isNotBlank()) item { Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ai.Reasoning(ai.reasoning, live = true, opened) } }
            if (ai != null && live && thinking) items(ai.activity) { (text, isError) -> Box(Modifier.padding(horizontal = 12.dp)) { ai.Activity(text, isError) } }
            if (ai != null && live && ai.streaming.isNotEmpty()) item { Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { ai.Reply(redactor.once(ai.streaming).text, live = true) } }
        }
        HRule()
        if (duels.logPick.isNotEmpty() && !guest && !watching) PickBar(h, duels, game)
        if (seated && !guest && duels.replay == null) ai?.Cues(game, talk != null)
        if (!watching) MuInput(
            duels.chat,
            { duels.chat = it },
            Modifier.fillMaxWidth().padding(8.dp),
            placeholder = when {
                seated -> "Say something to ${ai?.name} · / for a command · Enter"
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
private fun submit(h: TableHost, duels: Duels, text: String, seated: Boolean) {
    val t = text.trim()
    if (t.isEmpty()) return
    when {
        t.startsWith("/") -> when (val r = duels.runLine(t.drop(1))) {
            // A `;` line stopped partway keeps only its rest (1.0.87, the red team: Enter again made the first step twice).
            is Duels.Ran.Partial -> { duels.chat = "/" + r.rest; h.note(r.words) }
            else -> if (r.ok) duels.chat = ""
        }
        // A Lounge room's conversation (docs/LOUNGE.md) carries the words itself: to Ai alone.
        seated && h.ai?.ownWords == true -> {
            h.ai?.say(t)
            duels.chat = ""
        }
        // A Lounge room's watcher has no seat to chat from: the words go to the room.
        duels.network.watching && h.roomSay(t) -> duels.chat = ""
        seated -> {
            // Said while Ai answers: in the log now, read by Ai when it finishes (1.0.85).
            duels.say(t)
            h.ai?.say(t)
        }
        else -> duels.say(t)
    }
}

@Composable
private fun LogRow(h: TableHost, duels: Duels, line: LogLine, opened: MutableMap<String, Boolean>, strong: Boolean = false) {
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
            Box(Modifier.border(1.dp, c.ink).padding(horizontal = 6.dp, vertical = 3.dp)) {
                if (strong) MuText(line.text, style = MuType.small(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium), color = c.ink)
                else Small(line.text, color = c.ink)
            }
        }
        is LogLine.AiSays -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { h.ai?.Reply(line.text, live = false) }
        is LogLine.AiThought -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { h.ai?.Reasoning(line.text, live = false, opened) }
        is LogLine.AiDid -> Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { h.ai?.Activity(line.text, line.isError) }
    }
}

/**
 * One line picked: the next move goes in after it (a phase gone by). Two: the span between them kept as
 * one of the deck's combos (1.0.80, Ai: "record the double-e4 line … so we can replay it from any shuffle").
 */
@Composable
private fun PickBar(h: TableHost, duels: Duels, game: DuelGame) {
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
private fun saveSpan(h: TableHost, duels: Duels, game: DuelGame, from: Int, to: Int) {
    val seat = if (game.state.solo) 0 else duels.bottom
    val deckId = duels.deckOf(seat) ?: run { h.note("Combos are kept with a library deck: start the duel with a saved deck."); return }
    val start = game.stateAt(from)
    val span = game.entries.subList(from, (to + 1).coerceAtMost(game.cursor)).filter { it.seat == seat || it.seat == null }
    val steps = ComboRecorder.steps(start, span, duels.catalog, seat, game.header.seed)
    if (steps.isEmpty()) { h.note("Nothing in those lines to keep."); return }
    val needs = ComboRecorder.needs(start, seat, span, duels.catalog)
    duels.saveSpan(deckId, "Line from turn ${start.turn}", needs, steps) { n -> h.note("Kept “$n”: ${steps.size} steps, in Combos") }
    duels.logPick = emptyList()
}

sealed interface LogLine {
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
private fun aiLines(session: AiSession, thinking: Boolean, redact: (String) -> Secrets.Redacted, ownWords: Boolean = false): List<LogLine> = buildList {
    session.turns.forEach { turn ->
        val at = turn.at
        when {
            turn.role == Role.USER && turn.isToolResults -> if (thinking) turn.toolResults.forEach { add(LogLine.AiDid(it.summary.ifBlank { it.name }, it.isError, at)) }
            // The person's words are in the log already, as they said them — unless the conversation carries them (the Lounge).
            turn.role == Role.USER -> if (ownWords) turn.text.takeIf { it.isNotBlank() }?.let { add(LogLine.Said(it, at)) }
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

/**
 * A key for each of [lines], never two alike (1.0.92): what kind of line, when, which entry and its words — and, should
 * two lines still match, how many like it came before.
 */
private fun lineKeys(lines: List<LogLine>): List<String> {
    val seen = HashMap<String, Int>()
    return lines.map { line ->
        val text = when (line) {
            is LogLine.Turn -> line.text
            is LogLine.Done -> line.text
            is LogLine.Noted -> line.text
            is LogLine.Said -> line.text
            is LogLine.AiSays -> line.text
            is LogLine.AiThought -> line.text
            is LogLine.AiDid -> line.text
        }
        val base = "${line::class.simpleName}:${line.at}:${(line as? LogLine.Done)?.i ?: -1}:${text.hashCode()}"
        val n = seen[base] ?: 0
        seen[base] = n + 1
        if (n == 0) base else "$base#$n"
    }
}

/** The guest's log: the lines the host sent it, a rule at each new turn. */
private fun remoteLog(lines: List<Line>, me: Int): List<LogLine> {
    val out = ArrayList<LogLine>()
    var turn = 0
    lines.forEach { l ->
        if (l.turn != turn) { turn = l.turn; out += LogLine.Turn("Turn $turn", l.at) }
        out += if (l.chat) LogLine.Said(l.text, l.at) else LogLine.Done(l.text, l.seat == me, at = l.at)
    }
    return out
}

/** One entry of the log in words, as [DuelFolds] keeps it: what it says, and whether the table took it. */
class LogRead(val text: String, val applied: Boolean)

/**
 * The log's words, read once an entry and kept (1.0.86): a move reads one entry, an undo, a redo or a
 * replay's tick reads none. Before, every change of the cursor folded and worded the whole duel again.
 */
fun logFolds(
    game: DuelGame, viewer: Int?, catalog: DuelCatalog,
    book: ScriptBook = ScriptBook.EMPTY,
): DuelFolds<LogRead> =
    DuelFolds(game.header) { e, before, after, applied ->
        val text = if (e.action == DuelAction.EndTurn) "Turn ${after.turn} · ${DuelWords.seatName(after, after.active)}"
        else DuelWords.say(before, after, e, viewer, catalog, book)
        LogRead(text, applied)
    }

private fun logLines(
    game: DuelGame,
    folds: DuelFolds<LogRead>,
    bottom: Int,
    refused: Set<Int>,
    rolling: Set<Int> = emptySet(),
    inAir: Set<Pair<Int, Boolean>> = emptySet(),
): List<LogLine> {
    // A throw still in the air is a throw, not its numbers: the log never tells what the dice will read (1.0.87).
    val lastRoll = (0..1).associateWith { seat -> game.entries.subList(0, game.cursor).indexOfLast { (it.action as? DuelAction.OpeningRoll)?.seat == seat } }
    // The table's die and coin too (1.0.96): the last of each seat's, while it is in the air.
    val played = game.entries.subList(0, game.cursor)
    val airborne = inAir.mapNotNull { (seat, coin) ->
        played.indexOfLast { e -> val a = e.action; if (coin) a is DuelAction.Coin && a.seat == seat else a is DuelAction.Dice && a.seat == seat }.takeIf { it >= 0 }
    }.toSet()
    val reads = folds.sync(game.entries).results(game.cursor)
    val out = ArrayList<LogLine>(reads.size - game.floor.coerceAtMost(reads.size) + 8)
    for (i in game.floor until reads.size) {
        val e = game.entries[i]
        val r = reads[i]
        if (i == game.floor) out += LogLine.Turn("Turn 1", e.at)
        val inAir = (e.action as? DuelAction.OpeningRoll)?.let { it.seat in rolling && lastRoll[it.seat] == i } == true
        if (inAir) {
            out += LogLine.Done("${DuelWords.seatName(game.state, (e.action as DuelAction.OpeningRoll).seat)} throws the dice…", e.seat == bottom, i = i, at = e.at)
            continue
        }
        if (i in airborne) {
            val a = e.action
            val words = if (a is DuelAction.Coin) "${DuelWords.seatName(game.state, a.seat)} tosses a coin…" else "${DuelWords.seatName(game.state, (a as DuelAction.Dice).seat)} rolls a die…"
            out += LogLine.Done(words, e.seat == bottom, i = i, at = e.at)
            continue
        }
        out += when (e.action) {
            is DuelAction.Chat -> LogLine.Said(r.text, e.at)
            DuelAction.EndTurn -> LogLine.Turn(r.text, e.at)
            is DuelAction.Note -> LogLine.Noted(r.text, e.at)
            is DuelAction.Thinking, is DuelAction.Ping -> LogLine.Done(r.text, false, at = e.at)
            else -> LogLine.Done(r.text, e.seat == bottom, struck = !r.applied || e.i in refused, i = i, at = e.at)
        }
    }
    if (game.floor >= game.cursor) out += LogLine.Turn("Turn 1")
    return out
}
