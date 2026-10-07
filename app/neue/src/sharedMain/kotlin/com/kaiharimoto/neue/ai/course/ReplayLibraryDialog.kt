package com.kaiharimoto.neue.ai.course

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.course.ReplayLibrary
import com.kaiharimoto.mastertool.core.ai.course.ReplayReading
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The replay library (1.1.51): every DuelingBook replay kept on this computer — the courses' and the person's own — found
 * by player, card or course, and read here turn by turn beside the study's notes, without opening DuelingBook. A replay
 * is added by its address. Ink only: the list on the left, the duel on the right; stacked on a phone.
 */
@Composable
fun ReplayLibraryDialog(ai: AiState, play: (ReplayLibrary.Entry, Int) -> Unit) {
    val shelf = ai.replays
    if (!shelf.open) return
    var query by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    // Read off the frame thread: the courses' records, and every replay parsed for a card search.
    val all by produceState(emptyList<ReplayLibrary.Entry>(), shelf.version) { value = withContext(Dispatchers.IO) { shelf.entries() } }
    val shown by produceState(all, all, query) { value = withContext(Dispatchers.IO) { shelf.search(all, query) } }
    MuDialog(
        title = "Replays",
        onDismiss = { shelf.open = false },
        width = 1180.dp,
        description = "Every DuelingBook replay kept on this computer: ${all.size}. Read here, never from DuelingBook again.",
        scrolls = false,
        footer = { MuButton("Close", { shelf.open = false }, variant = BtnVariant.GHOST) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MuInput(query, { query = it }, Modifier.weight(1f), placeholder = "Find a player, a card, a course")
            MuInput(address, { address = it }, Modifier.weight(1f), placeholder = "Add a replay: its DuelingBook address", onSubmit = { shelf.add(address); address = "" })
            MuButton(
                if (shelf.adding != null) "Adding…" else "Add", { shelf.add(address); address = "" }, size = BtnSize.SM,
                enabled = address.isNotBlank() && shelf.adding == null && !ai.courses.running,
                reason = if (ai.courses.running) "A course is being studied in the browser: pause it first" else "Paste a replay's address first",
            )
        }
        shelf.said?.let { Small(it, color = Mu.colors.ink) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            val pick = shown.firstOrNull { it.id == chosen } ?: all.firstOrNull { it.id == chosen }
            if (wide) {
                Row(Modifier.fillMaxWidth().heightIn(max = 600.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.9f)) { Shelf(shown, chosen) { chosen = it } }
                    Column(Modifier.weight(1.6f)) { Reader(ai, pick, play) }
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.heightIn(max = 280.dp)) { Shelf(shown, chosen) { chosen = it } }
                    Column(Modifier.weight(1f, fill = false)) { Reader(ai, pick, play) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Shelf(entries: List<ReplayLibrary.Entry>, chosen: String?, choose: (String) -> Unit) {
    val c = Mu.colors
    if (entries.isEmpty()) {
        Help("No replay yet. A course's replays come in as its study reads them; add any other by its address above.")
        return
    }
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(entries, key = { it.id }) { e ->
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier.fillMaxWidth()
                    .hoverable(source)
                    .cursorPointer(caption = "Read")
                    .muClickable(interactionSource = source) { choose(e.id) }
                    .drawBehind { if (e.id == chosen) drawLine(c.ink, Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx()) }
                    .padding(start = 10.dp),
            ) {
                Small(e.label, color = c.ink, maxLines = 1)
                Help(
                    listOfNotNull(
                        e.games.takeIf { it > 0 }?.let { "$it game${if (it == 1) "" else "s"}" },
                        if (e.added) "added" + (if (e.note.isNotBlank()) " · ${e.note}" else "") else "${e.courseLabel} · replay ${e.n}, ch. ${e.chapter} · ${e.state}",
                        if (e.heldOut) "held out for Ai's exam" else null,
                    ).joinToString(" · "),
                    color = c.ink45,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Reader(ai: AiState, e: ReplayLibrary.Entry?, play: (ReplayLibrary.Entry, Int) -> Unit) {
    val c = Mu.colors
    val shelf = ai.replays
    if (e == null) {
        Help("Choose a replay to read it: each game, turn by turn, with what the players said.")
        return
    }
    val reading by produceState<ReplayReading.Reading?>(null, e.id, shelf.version) { value = withContext(Dispatchers.IO) { shelf.replay(e)?.let { ReplayReading.of(it) } } }
    val notes by produceState<String?>(null, e.id, shelf.version) { value = withContext(Dispatchers.IO) { shelf.notes(e) } }
    var game by remember(e.id) { mutableIntStateOf(1) }
    var showNotes by remember(e.id) { mutableStateOf(false) }
    Micro(e.label, color = c.ink45)
    val r = reading
    if (r == null) {
        Help("Reading it…")
        return
    }
    ReplayReading.results(r).takeIf { it.isNotEmpty() }?.let { Small(it.joinToString(" · "), color = c.ink) }
    r.cards.forEach { (player, played) ->
        if (played.isNotEmpty()) Help("$player played most: " + played.take(6).joinToString { (card, n) -> if (n > 1) "$card ×$n" else card }, color = c.ink70)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        r.games.forEach { g -> MuButton("Game ${g.n}", { game = g.n; showNotes = false }, size = BtnSize.SM, toggled = !showNotes && game == g.n) }
        if (notes != null) MuButton("The study's notes", { showNotes = true }, size = BtnSize.SM, toggled = showNotes)
        // The game on the Duel page's table, as our own replay (1.1.51): its place among the games where cards moved.
        val played = r.games.filter { g -> g.turns.any { t -> t.lines.any { !it.chat } } }
        val k = played.indexOfFirst { it.n == game }
        MuButton(
            if (played.size > 1 && k >= 0) "Play game ${k + 1} on the table" else "Play on the table", { play(e, k + 1) },
            size = BtnSize.SM, enabled = !showNotes && k >= 0, reason = "Choose a game where cards were played",
        )
        MuButton("Open on DuelingBook", { Platform.browse(e.url) }, size = BtnSize.SM, variant = BtnVariant.GHOST, arrow = true)
        if (e.added) MuButton("Remove", { shelf.remove(e) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
    if (showNotes) {
        LazyColumn(Modifier.weight(1f, fill = false)) { item { SelectionContainer { Body(notes.orEmpty(), color = c.ink70) } } }
        return
    }
    val turns = r.games.firstOrNull { it.n == game }?.turns.orEmpty()
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(turns) { t ->
            Column(Modifier.fillMaxWidth()) {
                Micro(if (t.n == 0) "Before the first turn" else "Turn ${t.n} · ${t.player}", color = c.ink45)
                SelectionContainer {
                    Column {
                        t.lines.forEach { l ->
                            when {
                                l.chat -> Help("${l.who}: “${l.text}”", color = c.ink70)
                                l.phase -> Help(l.text, color = c.ink45)
                                else -> Body((if (l.who.isNotBlank() && l.who != t.player) "${l.who}: " else "") + l.text, color = c.ink)
                            }
                        }
                    }
                }
            }
        }
    }
}
