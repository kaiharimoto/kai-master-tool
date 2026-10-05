package com.kaiharimoto.neue.world.apps

import com.kaiharimoto.neue.world.type.Body
import com.kaiharimoto.neue.world.type.readingMeasure
import com.kaiharimoto.neue.world.type.WorldType
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.text.ChatFollow
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.neue.world.desk.deskTarget
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.Composer
import com.kaiharimoto.neue.ai.ReasoningView
import com.kaiharimoto.neue.ai.ReplyView
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.world.type.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.world.type.Micro
import com.kaiharimoto.neue.world.type.MicroLink
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.world.type.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.serialization.json.JsonObject

/*
 * Thoughts (`docs/world/DESKTOP.md` §3, §6.5): 1.0.97's Thoughts and Activity as one stream — the person's asks, Ai's
 * reasoning (open while it streams, folded once filed), its words, and its actions as compact rows, each a link to what it
 * made. All · Words · Actions. The composer at its foot is where the person talks to Ai on the World page.
 */

/** What the stream shows. */
enum class ThoughtsFilter(val label: String) { ALL("All"), WORDS("Words"), ACTIONS("Actions") }

/** One row of the stream. */
internal sealed interface Thought {
    data class Asked(val text: String) : Thought
    data class Reasoned(val text: String) : Thought
    data class Said(val text: String) : Thought

    /** An action: its glyph, its words, and the address or app it made, when it made one. */
    data class Did(val glyph: String, val words: String, val isError: Boolean, val goes: Goes?) : Thought
}

/** Where an action's row goes on a click. */
internal sealed interface Goes {
    data class Page(val address: String) : Goes
    data class App(val ref: AppRef) : Goes
    data class File(val path: String) : Goes
}

/** The stream of [turns], tool calls matched to their results so each action says what it made. */
internal fun thoughts(turns: List<ChatTurn>, filter: ThoughtsFilter = ThoughtsFilter.ALL): List<Thought> {
    val calls = HashMap<String, Part.ToolUse>()
    turns.forEach { t -> t.parts.filterIsInstance<Part.ToolUse>().forEach { calls[it.id] = it } }
    val all = buildList {
        turns.forEach { turn ->
            when {
                turn.role == Role.USER && turn.isToolResults -> turn.toolResults.forEach { r -> add(action(r, calls[r.id]?.input)) }
                turn.role == Role.USER -> if (turn.text.isNotBlank()) add(Thought.Asked(turn.text))
                else -> {
                    turn.parts.filterIsInstance<Part.Reasoning>().forEach { add(Thought.Reasoned(it.text)) }
                    turn.text.takeIf { it.isNotBlank() }?.let { add(Thought.Said(it)) }
                }
            }
        }
    }
    return when (filter) {
        ThoughtsFilter.ALL -> all
        ThoughtsFilter.WORDS -> all.filter { it is Thought.Asked || it is Thought.Said }
        ThoughtsFilter.ACTIONS -> all.filterIsInstance<Thought.Did>()
    }
}

/** A tool's result as a compact row: ✎ wrote, ▶ ran, ◧ pinned, ⊞ made — and where it goes. */
internal fun action(r: Part.ToolResult, input: JsonObject?): Thought.Did {
    val words = r.summary.ifBlank { r.name.replace('_', ' ') }
    fun s(k: String) = input?.let { ToolArgs.string(it, k) }
    val (glyph, goes) = when (r.name) {
        "world_write" -> "✎" to s("path")?.let { Goes.File(it) }
        "world_read" -> "◔" to s("path")?.let { Goes.File(it) }
        "world_run" -> "▶" to (s("path")?.let { Goes.File(it) } ?: Goes.App(BuiltInApp.TERMINAL.ref))
        "world_tool" -> "▶" to Goes.App(BuiltInApp.TERMINAL.ref)
        "world_show" -> "◧" to s("id")?.let { Goes.Page(WorldAddress.Board(it).format()) }
        "world_app" -> "⊞" to s("slug")?.let { Goes.App(AppRef.Made(it)) }
        "world_open" -> "↗" to s("address")?.let { Goes.Page(it) }
        "world_new", "world_state" -> "○" to null
        else -> "→" to null
    }
    return Thought.Did(glyph, words, r.isError, goes)
}

/** Thoughts' window body. */
@Composable
fun ThoughtsApp(h: NeueHolders, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val ai = h.ai
    val phone = LocalPhone.current
    if (!h.neue.prefs.ai.enabled) {
        Box(modifier.fillMaxSize()) { Help("Ai is off. Turn it on in Settings to watch it think here.", Modifier.padding(16.dp)) }
        return
    }
    val session = ai.session?.takeIf { it.mode == AiSession.MODE_WORLD }
    var filter by remember { mutableStateOf(ThoughtsFilter.ALL) }
    val rows = remember(session?.turns, filter) { thoughts(session?.turns.orEmpty(), filter) }
    val opened = remember(session?.id) { mutableStateMapOf<String, Boolean>() }
    val list = rememberLazyListState()
    val live = session != null && ai.running
    val words = filter != ThoughtsFilter.ACTIONS
    val tail = rows.size + (if (live) ai.activity.size + ai.reasoning.length / 200 + ai.streaming.length / 80 else 0)
    FollowEnd(list, session?.id, tail)
    // One reading column (READABILITY.md §2): 72 characters of prose at most, centred in a window wider than that, so a
    // maximised Thoughts on a 1920 desk reads as a page, not a banner.
    val column = readingMeasure() + 32.dp
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Segmented(filter, ThoughtsFilter.entries, { it.label }, { filter = it }, small = true, compact = true)
            Box(Modifier.weight(1f))
            if (session != null && !ai.running) MicroLink("New topic", { ai.newChat(AiSession.MODE_WORLD) })
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (rows.isEmpty() && !live) {
                Help(
                    if (session == null) "Nothing yet. Ask ${ai.name} below: what it reasons, does and says while it works here is written in this stream as it happens."
                    else "Nothing to show under ${filter.label}.",
                    Modifier.padding(16.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(rows) { row ->
                    Box(Modifier.widthIn(max = column).fillMaxWidth()) {
                        when (row) {
                            is Thought.Asked -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Micro("You", color = c.ink70)
                                Body(row.text, color = c.ink, maxLines = 6)
                            }
                            is Thought.Reasoned -> ReasoningView(ai, row.text, live = false, opened)
                            is Thought.Said -> ReplyView(ai, row.text)
                            is Thought.Did -> DidRow(h, row)
                        }
                    }
                }
                if (live && words && ai.reasoning.isNotBlank()) item { Box(Modifier.widthIn(max = column).fillMaxWidth()) { ReasoningView(ai, ai.reasoning, live = true, opened) } }
                if (live && filter != ThoughtsFilter.WORDS) items(ai.activity) { Box(Modifier.widthIn(max = column).fillMaxWidth()) { DidRow(h, Thought.Did("→", it.summary.ifBlank { it.name }, it.isError, null)) } }
                if (live && words && ai.streaming.isNotEmpty()) item { Box(Modifier.widthIn(max = column).fillMaxWidth()) { ReplyView(ai, ai.streaming, live = true) } }
            }
            ScrollbarFor(list)
        }
        // The composer under the column, as wide as it; its rule the window's width.
        Box(
            Modifier.fillMaxWidth().drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.widthIn(max = column)) {
                if (session != null && ai.configured) {
                    Composer(ai, Modifier.deskTarget(h, BuiltInApp.THOUGHTS.id, Anchor.COMPOSER), phone = phone)
                } else {
                    AskLine(h)
                }
            }
        }
    }
}

/** An action as a compact row: its glyph, its words, a link to what it made. */
@Composable
private fun DidRow(h: NeueHolders, d: Thought.Did) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val goes = d.goes
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot && goes != null) c.ink06 else c.paper))
            .let { m ->
                if (goes == null) m else m.hoverable(source).cursorPointer(caption = "Open").muClickable(interactionSource = source) { follow(h, goes) }
            }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Mono(if (d.isError) "✕" else d.glyph, Modifier.widthIn(min = 14.dp), color = if (d.isError) c.ink else c.ink70)
        MuText(d.words, Modifier.weight(1f), style = WorldType.mono(f, LocalPhone.current), color = if (d.isError) c.ink else c.ink70, maxLines = 3)
    }
}

private fun follow(h: NeueHolders, goes: Goes) {
    val world = h.world
    when (goes) {
        is Goes.Page -> world.browser.show(goes.address)
        is Goes.App -> world.apps.windows.open(goes.ref, WorldEvent.YOU)
        is Goes.File -> {
            world.saveEditor()
            world.showFile(goes.path)
            world.apps.windows.open(BuiltInApp.EDITOR.ref, WorldEvent.YOU)
        }
    }
}

/** With no World conversation yet: a line to Ai, and Enter starts one, here, without the panel docking. */
@Composable
private fun AskLine(h: NeueHolders) {
    val c = Mu.colors
    var text by remember { mutableStateOf("") }
    fun ask() {
        h.ai.askInWorld(h, text)
        text = ""
    }
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiMark(16.dp, name = h.ai.name)
            MuInput(text, { text = it }, Modifier.weight(1f), placeholder = "Ask ${h.ai.name} to find something out here", onSubmit = { ask() })
            MuButton("Ask", { ask() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = text.isNotBlank(), reason = "Type a question first")
        }
    }
}

/**
 * The person's words to Ai on the World page (§6.5): a World conversation, about the world open here, started where the
 * person reads — Thoughts — and the panel left as it was. With no connection yet the panel opens on its setup.
 */
internal fun AiState.askInWorld(h: NeueHolders, question: String) {
    val q = question.trim()
    if (q.isEmpty()) return
    val w = h.world.open
    val asked = if (w != null) "In Ai World, in the world “${w.title}”: $q" else "$q (in Ai World)"
    if (prefs.connection == null) {
        draft = asked
        setOpen(true)
        return
    }
    if (session?.mode == AiSession.MODE_WORLD && !running) {
        send(asked)
    } else {
        newChat(AiSession.MODE_WORLD)
        send(asked)
    }
}

/** Keeps [list] at its end as it grows, only while the reader is there (`ChatFollow`, the chat's rule). */
@Composable
internal fun FollowEnd(list: LazyListState, key: Any?, size: Int) {
    val follow = remember(key) { ChatFollow() }
    val ours = remember { booleanArrayOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (!moving && !ours[0]) follow.readerScrolled(atEnd = !list.canScrollForward)
        }
    }
    LaunchedEffect(size) {
        if (size == 0 || !follow.shouldFollow(readerScrolling = list.isScrollInProgress)) return@LaunchedEffect
        ours[0] = true
        try {
            list.scrollToItem(size - 1, 1_000_000)
        } finally {
            ours[0] = false
        }
    }
}
