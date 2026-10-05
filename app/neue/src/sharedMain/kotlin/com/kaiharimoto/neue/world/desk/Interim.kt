package com.kaiharimoto.neue.world.desk

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.ActivityLine
import com.kaiharimoto.neue.ai.Composer
import com.kaiharimoto.neue.ai.ReasoningView
import com.kaiharimoto.neue.ai.ReplyView
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.BoardBody
import com.kaiharimoto.neue.world.system.FollowEnd

/**
 * Stand-ins for what the app host draws (agent C, `DESKTOP.md` §13): plain, working, and replaced whole when the host
 * lands — [DeskApps] is the one place that calls them. Thoughts is 1.0.97's pane with the panel's composer at its foot
 * (§6.5); the Browser is the world's pages as a list and the selected tab's page under it, painted by `BoardBody`.
 */
internal object Interim {
    /** The selected tab's page title, for the Browser's title bar. */
    fun browserTitle(h: NeueHolders): String? {
        val t = h.world.desk.desk.tabs.current ?: return null
        return when (val a = t.parsed) {
            is WorldAddress.Board -> h.world.open?.board(a.id)?.title ?: a.id
            is WorldAddress.Home -> "world://home"
            else -> t.address
        }
    }

    @Composable
    fun Browser(h: NeueHolders, modifier: Modifier) {
        val c = Mu.colors
        val world = h.world
        val w = world.open
        val desk = world.desk
        val tab = desk.desk.tabs.current
        val board: Board? = (tab?.parsed as? WorldAddress.Board)?.let { a -> w?.board(a.id) }
        Column(modifier) {
            if (w == null || w.boards.isEmpty()) {
                Help("No pages yet. What Ai shows you — charts, webs, tables, cards — opens here, a page a tab.", Modifier.padding(16.dp))
            } else {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.width(220.dp).fillMaxSize().drawBehind { drawLine(c.ink12, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) }) {
                        items(w.boards.sortedByDescending { it.updated }, key = { it.id }) { b -> PageRow(h, b, b.id == board?.id) }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        if (board == null) {
                            Help("Pick a page.", Modifier.padding(16.dp))
                        } else {
                            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Micro(board.kind, color = c.ink45)
                                MuText(board.title, style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 2)
                                if (board.note.isNotBlank()) Small(board.note, color = c.ink70, maxLines = 3)
                                BoardBody(h, board, w.id, Modifier.fillMaxWidth().weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PageRow(h: NeueHolders, b: Board, selected: Boolean) {
        val c = Mu.colors
        val source = remember { MutableInteractionSource() }
        val hot = source.collectIsHotAsState().value
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(animatedColor(if (selected) c.ink else if (hot) c.ink06 else Color.Transparent))
                .hoverable(source)
                .cursorPointer(caption = "Open")
                .muClickable(interactionSource = source) {
                    val desk = h.world.desk
                    val address = WorldAddress.Board(b.id).format()
                    val tabs = desk.desk.tabs
                    val there = tabs.showing(address)
                    desk.apply(DeskOp.Tabs(if (there != null) tabs.select(there.id) else tabs.open(address, WorldDeskState.now())))
                    h.world.selectedBoard = b.id
                }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconView(WorldIcons.page(b.type), 16.dp, color = if (selected) c.paper else c.ink70)
            MuText(b.title.ifBlank { b.id }, Modifier.weight(1f), style = MuType.small(LocalMuFonts.current), color = if (selected) c.paper else c.ink, maxLines = 1)
        }
    }

    /** One thing in Thoughts. */
    private sealed interface Thought {
        data class Asked(val text: String) : Thought
        data class Reasoned(val text: String) : Thought
        data class Did(val summary: String, val isError: Boolean) : Thought
        data class Said(val text: String) : Thought
    }

    private fun thoughts(turns: List<ChatTurn>): List<Thought> = buildList {
        turns.forEach { turn ->
            when {
                turn.role == Role.USER && turn.isToolResults -> turn.toolResults.forEach { add(Thought.Did(it.summary.ifBlank { it.name }, it.isError)) }
                turn.role == Role.USER -> if (turn.text.isNotBlank()) add(Thought.Asked(turn.text))
                else -> {
                    turn.parts.filterIsInstance<Part.Reasoning>().forEach { add(Thought.Reasoned(it.text)) }
                    turn.text.takeIf { it.isNotBlank() }?.let { add(Thought.Said(it)) }
                }
            }
        }
    }

    /** Thoughts: Ai's conversation in this world as one stream, the composer at its foot (§3, §6.5). */
    @Composable
    fun Thoughts(h: NeueHolders, modifier: Modifier, phone: Boolean) {
        val c = Mu.colors
        Column(modifier) {
            if (!h.neue.prefs.ai.enabled) {
                Help("Ai is off. Turn it on in Settings to watch it think here.", Modifier.padding(12.dp))
            } else {
                val ai = h.ai
                val session = ai.session
                val rows = remember(session?.turns) { thoughts(session?.turns.orEmpty()) }
                val opened = remember(session?.id) { mutableStateMapOf<String, Boolean>() }
                val list = rememberLazyListState()
                val tail = rows.size + ai.activity.size + (if (ai.reasoning.isNotBlank()) 1 else 0) + (if (ai.streaming.isNotEmpty()) 1 else 0)
                FollowEnd(list, session?.id, tail + ai.streaming.length / 80 + ai.reasoning.length / 200)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (rows.isEmpty() && !ai.running) {
                        Help("Nothing yet. What ${ai.name} reasons, does and says while it works is written here as it happens.", Modifier.padding(12.dp))
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(rows) { row ->
                            when (row) {
                                is Thought.Asked -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Micro("You", color = c.ink45)
                                    Small(row.text, color = c.ink70, maxLines = 4)
                                }
                                is Thought.Reasoned -> ReasoningView(ai, row.text, live = false, opened)
                                is Thought.Did -> ActivityLine(row.summary, row.isError)
                                is Thought.Said -> ReplyView(ai, row.text)
                            }
                        }
                        if (ai.reasoning.isNotBlank()) item { ReasoningView(ai, ai.reasoning, live = true, opened) }
                        items(ai.activity) { ActivityLine(it.summary.ifBlank { it.name }, it.isError) }
                        if (ai.streaming.isNotEmpty()) item { ReplyView(ai, ai.streaming, live = true) }
                    }
                    ScrollbarFor(list)
                }
                Composer(ai, Modifier.fillMaxWidth().deskTarget(h, com.kaiharimoto.mastertool.core.world.desk.BuiltInApp.THOUGHTS.id, com.kaiharimoto.mastertool.core.world.desk.Anchor.COMPOSER), phone = phone)
            }
        }
    }

    /** An app whose window the host draws: its name, and that it opens here once the host is in. */
    @Composable
    fun Placeholder(h: NeueHolders, ref: AppRef, modifier: Modifier) {
        val c = Mu.colors
        Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MuText(h.world.desk.title(ref.key), style = MuType.h2(LocalMuFonts.current), color = c.ink)
            Small(
                when (ref) {
                    is AppRef.Made -> h.world.desk.apps.firstOrNull { it.slug == ref.slug }?.description?.ifBlank { null } ?: "An app Ai made."
                    else -> "This app opens here."
                },
                Modifier.widthIn(max = 480.dp),
                color = c.ink70,
            )
        }
    }
}
