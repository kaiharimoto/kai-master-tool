package com.kaiharimoto.neue.world

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuTabs
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * Ai World's page (1.0.97, `08`; kai: "I want to see everything the Ai is doing. The Ai is operating in a mini virtual
 * computer"): a head with the world, its picker, New, Run or Stop, Follow and what runs here; under it the six panes —
 * Files over Activity, the Editor over the Terminal, the Boards over Thoughts — drawn in `WorldPanes.kt`, their boards
 * in `WorldPaint.kt`. A phone shows one pane at a time under a strip of tabs.
 */
@Composable
fun WorldPage(h: NeueHolders) {
    val world = h.world
    val neue = h.neue
    val phone = LocalPhone.current
    // The world open last is opened again next time.
    LaunchedEffect(world.open?.id) {
        val id = world.open?.id ?: return@LaunchedEffect
        if (neue.prefs.world.open != id) neue.update { it.copy(world = it.world.copy(open = id)) }
    }
    val w = world.open
    Column(Modifier.fillMaxSize()) {
        WorldHead(h, w, phone)
        when {
            w == null -> NoWorld(h)
            phone -> PhoneWorld(h, w)
            else -> DeskWorld(h, w)
        }
    }
}

private fun kbd(action: DeskAction): String? = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/** A new world, about the builder's deck when one is open. */
internal fun newWorld(h: NeueHolders) {
    val b = h.builder
    val deck = b.deckId
    val title = if (deck != null) "${b.deckName} study" else "World ${h.world.list.size + 1}"
    h.world.make(title, deck?.let { World.SCOPE_DECK + it })
}

/**
 * Hands [question] to Ai as a fresh World conversation (`AiState.startWorld`), about the world open here when one is.
 * With no connection yet the panel opens on its setup, and the question waits in its box.
 */
internal fun askAi(h: NeueHolders, question: String) {
    val q = question.trim()
    if (q.isEmpty()) return
    val w = h.world.open
    val asked = if (w != null) "In Ai World, in the world “${w.title}”: $q" else "$q (in Ai World)"
    if (h.ai.prefs.connection == null) h.ai.draft = asked
    h.ai.startWorld(asked)
}

/** A line to Ai: Enter sends it, as a fresh World conversation. */
@Composable
private fun AskBox(h: NeueHolders, modifier: Modifier = Modifier, dense: Boolean = false) {
    var text by remember { mutableStateOf("") }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiMark(16.dp, name = h.ai.name)
        MuInput(
            text,
            { text = it },
            Modifier.weight(1f),
            placeholder = if (h.world.open != null) "Ask ${h.ai.name} to try something here" else "Ask ${h.ai.name} a question to answer by experiment",
            dense = dense,
            onSubmit = { askAi(h, text); text = "" },
        )
        MuButton("Ask", { askAi(h, text); text = "" }, size = BtnSize.SM, variant = BtnVariant.SUBTLE, enabled = text.isNotBlank(), reason = "Type a question first")
    }
}

/** What runs here, in words: the person decides about Python on this computer, never Ai. */
private fun pythonWords(h: NeueHolders): Pair<String, String> = when {
    !WorldPython.possible -> "JavaScript" to "Scripts here are JavaScript: Python runs on a computer."
    !h.neue.prefs.world.python -> "Python off" to "Python is off on this computer: allow it in Settings › Ai World. JavaScript always runs."
    else -> "Python on" to "Python and JavaScript both run here."
}

// ---- The head -----------------------------------------------------------------------------------------------------

@Composable
private fun WorldHead(h: NeueHolders, w: World?, phone: Boolean) {
    val world = h.world
    val neue = h.neue
    val c = Mu.colors
    var pickerAt by remember { mutableStateOf(Offset.Zero) }
    var moreAt by remember { mutableStateOf(Offset.Zero) }

    fun pickerMenu(): List<MenuEntry> = buildList {
        if (world.list.isEmpty()) add(MenuEntry("No worlds yet"))
        world.list.forEach { other ->
            add(MenuEntry(other.title, hint = "${other.boards.size} boards", enabled = other.id != w?.id, reason = "Open now") {
                world.saveEditor()
                world.openWorld(other.id)
            })
        }
        add(MenuEntry("New world", separatorBefore = true) { newWorld(h) })
    }

    Row(
        Modifier.fillMaxWidth().height(if (phone) 52.dp else 56.dp).padding(horizontal = if (phone) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (phone) 8.dp else 12.dp),
    ) {
        // The world's name opens the picker.
        val source = remember { MutableInteractionSource() }
        val hovered by source.collectIsHotAsState()
        Row(
            Modifier
                .weight(1f, fill = false)
                .onGloballyPositioned { pickerAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }
                .hoverable(source)
                .cursorPointer(caption = "Switch")
                .muClickable(interactionSource = source) { neue.menu = MenuSpec(pickerAt, pickerMenu()) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MuText(
                w?.title ?: "Ai World",
                Modifier.widthIn(max = if (phone) 200.dp else 420.dp),
                style = MuType.h2(LocalMuFonts.current),
                color = c.ink,
                maxLines = 1,
            )
            MuText("▼", style = MuType.help(LocalMuFonts.current).copy(fontSize = 10.sp), color = animatedColor(if (hovered) c.ink else c.ink45))
        }
        if (!phone && w != null) Small("${world.files.size} files · ${w.boards.size} boards", color = c.ink45, maxLines = 1)
        Box(Modifier.weight(1f))
        if (!phone && w != null && neue.prefs.ai.enabled) AskBox(h, Modifier.widthIn(min = 200.dp, max = 380.dp).weight(1f, fill = false), dense = true)
        world.running?.let { r ->
            if (!phone) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Breathe()
                    Mono(r, Modifier.widthIn(max = 200.dp), color = c.ink70)
                }
            }
        }
        val (python, why) = pythonWords(h)
        val follow = neue.prefs.world.follow
        if (phone) {
            if (world.running != null) {
                MuButton("Stop", { world.stop() }, size = BtnSize.SM)
            } else if (w != null) {
                MuButton("Run", { world.runEditor() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = world.runBlocked == null, reason = world.runBlocked)
            }
            Box(Modifier.onGloballyPositioned { moreAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
                IconButton(Icons.More, {
                    neue.menu = MenuSpec(moreAt, listOf(
                        MenuEntry("New world") { newWorld(h) },
                        MenuEntry(if (follow) "Stay put" else "Follow Ai", hint = if (follow) "The page stays where you leave it" else "Pane to pane as it works") { toggleFollow(h) },
                        MenuEntry(python, hint = why, separatorBefore = true),
                    ))
                }, size = 40.dp, label = "More")
            }
        } else {
            Tip(why) { Mono(python, color = c.ink45) }
            Tip(if (follow) "The page follows Ai from pane to pane, and comes forward when it starts work" else "The page stays where you leave it", kbd = kbd(DeskAction.WORLD_FOLLOW)) {
                WordToggle("Follow", follow) { toggleFollow(h) }
            }
            Tip("A new world: a folder of Ai's own", kbd = kbd(DeskAction.WORLD_NEW)) {
                MuButton("New world", { newWorld(h) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE, icon = Icons.Plus)
            }
            if (world.running != null) {
                Tip("Stop the run", kbd = kbd(DeskAction.WORLD_STOP)) { MuButton("Stop", { world.stop() }, size = BtnSize.SM) }
            } else {
                Tip("Run the file in the editor", kbd = kbd(DeskAction.WORLD_RUN)) {
                    MuButton("Run", { world.runEditor() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = world.runBlocked == null, reason = world.runBlocked, arrow = true)
                }
            }
        }
    }
}

internal fun toggleFollow(h: NeueHolders) {
    val on = !h.neue.prefs.world.follow
    h.neue.update { it.copy(world = it.world.copy(follow = on)) }
    h.neue.note = Note(if (on) "Following Ai from pane to pane" else "The World stays where you leave it")
}

// ---- No world yet -------------------------------------------------------------------------------------------------

@Composable
private fun NoWorld(h: NeueHolders) {
    val c = Mu.colors
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EmptyState(
            "No world yet.",
            "A world is ${h.ai.name}'s own small computer: it writes code there, runs it, and pins what it finds to boards — charts, webs of cards, tables. You watch every keystroke, run and thought as it happens, and can change any of it.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MuButton("New world", { newWorld(h) }, variant = BtnVariant.PRIMARY, icon = Icons.Plus)
                }
                if (h.neue.prefs.ai.enabled) {
                    Help("Or ask ${h.ai.name}, and it makes one itself:", color = c.ink45)
                    AskBox(h, Modifier.widthIn(max = 520.dp))
                    listOf(
                        "Study my deck's openings",
                        "Map how my deck's cards search each other",
                        "Simulate a thousand opening hands",
                    ).forEach { q -> MicroLink("$q →", { askAi(h, "$q in Ai World") }, color = c.ink) }
                }
            }
        }
    }
}

// ---- The panes, laid out ------------------------------------------------------------------------------------------

@Composable
private fun DeskWorld(h: NeueHolders, w: World) {
    val world = h.world
    val max = world.maximized
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        if (max != null) {
            PaneFor(h, w, max, Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.2f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaneFor(h, w, WorldPane.FILES, Modifier.weight(0.5f))
                    PaneFor(h, w, WorldPane.ACTIVITY, Modifier.weight(0.5f))
                }
                Column(Modifier.weight(0.36f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaneFor(h, w, WorldPane.EDITOR, Modifier.weight(0.6f))
                    PaneFor(h, w, WorldPane.TERMINAL, Modifier.weight(0.4f))
                }
                Column(Modifier.weight(0.44f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaneFor(h, w, WorldPane.BOARDS, Modifier.weight(0.64f))
                    PaneFor(h, w, WorldPane.THOUGHTS, Modifier.weight(0.36f))
                }
            }
        }
    }
}

/** One pane at a time on a phone, chosen by the tabs (or by Ai, when the page follows it). */
@Composable
private fun PhoneWorld(h: NeueHolders, w: World) {
    val world = h.world
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            MuTabs(
                world.focus,
                WorldPane.entries,
                { p -> if (world.aiPane == p) "${p.title} · ${h.ai.name}" else p.title },
                { world.focus = it },
            )
        }
        PaneFor(h, w, world.focus, Modifier.fillMaxSize().padding(top = 8.dp), framed = false)
    }
}

@Composable
private fun PaneFor(h: NeueHolders, w: World, pane: WorldPane, modifier: Modifier, framed: Boolean = true) {
    val world = h.world
    val c = Mu.colors
    Pane(
        h,
        pane,
        // A press anywhere in a pane brings it forward, for the keys; nothing is spent.
        modifier.pointerInput(pane) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Press) world.focus = pane
                }
            }
        },
        framed = framed,
        trailing = {
            when (pane) {
                WorldPane.EDITOR -> EditorStatus(h)
                WorldPane.TERMINAL -> if (world.terminal.isNotEmpty() && world.running == null) MicroLink("Clear", { world.terminal.clear() })
                WorldPane.BOARDS -> Mono("${w.boards.size}", color = c.ink45)
                WorldPane.FILES -> Mono("${world.files.size}", color = c.ink45)
                WorldPane.THOUGHTS -> if (h.ai.running) Breathe()
                WorldPane.ACTIVITY -> Mono("${world.activity.size}", color = c.ink45)
            }
        },
    ) {
        when (pane) {
            WorldPane.FILES -> FilesPane(h)
            WorldPane.EDITOR -> EditorPane(h)
            WorldPane.TERMINAL -> TerminalPane(h)
            WorldPane.BOARDS -> BoardsPane(h, w)
            WorldPane.THOUGHTS -> ThoughtsPane(h)
            WorldPane.ACTIVITY -> ActivityPane(h)
        }
    }
}

// ---- Keys ---------------------------------------------------------------------------------------------------------

/** Esc and Back on the World page: a pane given the page goes back among the others, before anything below. */
internal fun dismissWorld(h: NeueHolders): Boolean {
    if (h.neue.page != com.kaiharimoto.neue.Page.WORLD || h.neue.hasTop || h.overlays.isOpen || h.textFocus.any) return false
    if (h.world.maximized == null) return false
    h.world.maximized = null
    return true
}

/** The World's keys (`DeskScope.WORLD`), and the same actions from the palette and the menus. */
internal fun runWorld(h: NeueHolders, action: DeskAction) {
    val world = h.world
    fun bring(p: WorldPane) {
        // With a pane given the page, the key gives it to this one instead; otherwise it comes forward.
        if (world.maximized != null) world.maximized = p
        world.focus = p
    }
    when (action) {
        DeskAction.WORLD_RUN -> world.runBlocked?.let { h.neue.note = Note(it) } ?: world.runEditor()
        DeskAction.WORLD_STOP -> world.stop()
        DeskAction.WORLD_FOLLOW -> toggleFollow(h)
        DeskAction.WORLD_NEW -> newWorld(h)
        DeskAction.WORLD_PANE_FILES -> bring(WorldPane.FILES)
        DeskAction.WORLD_PANE_EDITOR -> bring(WorldPane.EDITOR)
        DeskAction.WORLD_PANE_TERMINAL -> bring(WorldPane.TERMINAL)
        DeskAction.WORLD_PANE_BOARDS -> bring(WorldPane.BOARDS)
        DeskAction.WORLD_PANE_THOUGHTS -> bring(WorldPane.THOUGHTS)
        DeskAction.WORLD_PANE_ACTIVITY -> bring(WorldPane.ACTIVITY)
        else -> Unit
    }
}
