package com.kaiharimoto.neue.duel

import com.kaiharimoto.neue.ai.avatar.AiBadge
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.run
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.muClickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Small
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuDrawer
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.theme.Mu

/** A deck a seat can sit down with: the builder's own, or one from the library. */
private data class DeckChoice(val id: String?, val name: String, val deck: Deck)

/**
 * The Spotlight's layer while it is open and no replay is (1.0.92): whether it is open is read here, so opening it and
 * every key typed in it redraws the Spotlight, never the page round it.
 */
@Composable
private fun SpotlightWhenOpen(h: NeueHolders, game: DuelGame, phone: Boolean, live: Boolean) {
    if (h.duel.spotlight != null && live) SpotlightLayer(h, game, phone)
}

/**
 * The Duel page (1.0.74): the duel simulator. A row of what the duel is (one table or two, whose seat
 * you are at, how much the hot-seat shows, undo, the command line), and below it the table between
 * the inspector and the log. Everything about the table's size is [DuelLayouter]'s; this only gives
 * it the room.
 */
@Composable
internal fun DuelPage(h: NeueHolders) {
    val c = Mu.colors
    val duels = h.duel
    val neue = h.neue
    val prefs = neue.prefs.duel
    val index = h.builder.index
    duels.useIndex(index)
    LaunchedEffect(Unit) { duels.load() }
    // The speech model read in before the first command is spoken, when voice is set up (1.0.87).
    LaunchedEffect(Unit) { h.duelVoice.prewarm() }
    // A refusal is said once, at the foot of the window.
    LaunchedEffect(duels.problem) { duels.problem?.let { neue.note = Note(it); duels.problem = null } }

    val game = duels.shown
    val replay = duels.replay
    val phone = LocalPhone.current
    duels.myWindows = prefs.windows
    Column(Modifier.fillMaxSize()) {
        // On a desk or a tablet the duel's row is the window's bar (1.0.78), so it folds away in
        // immersive mode; a phone's bar is its own, so the row stays here.
        if (replay != null) ReplayBar(duels, replay)
        else if (phone) PhoneDuelBar(h, duels)
        if (duels.role != null && phone) NetBar(h, duels)
        if (replay != null || phone) Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
        if (game == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    "No duel yet.",
                    "Shuffle a deck and draw: one player's table to test a deck, or two to duel across a table. Every card moves by hand — drag it, right-click it, or type what you would say.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuButton("Test hand", { testHand(h) }, variant = BtnVariant.PRIMARY)
                        MuButton("Set up a duel", { duels.setupOpen = true })
                        MuButton("Replays", { duels.libraryOpen = true }, variant = BtnVariant.GHOST)
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val layout = remember(maxWidth, maxHeight, prefs.twoSided, game.state.solo, neue.form, duels.bottom, prefs.logShown) {
                    DuelLayouter.solve(
                        maxWidth.value, maxHeight.value,
                        twoSided = prefs.twoSided && !game.state.solo,
                        form = neue.form,
                        bottom = duels.bottom,
                        // The log and the card beside the table, or both put away together (kai, 1.0.93): the table takes
                        // their room, and they open from the Table menu as drawers.
                        wantRails = prefs.logShown,
                    )
                }
                // The same eyes as the last frame are the same set (1.0.92): a new one equal to it each time made every
                // part of the table that takes it draw itself again.
                val eyes = duels.viewers(prefs)
                val viewers = remember(eyes) { eyes }
                val viewer = if (viewers.size > 1) null else viewers.first()
                DuelTable(h, duels, game, layout, viewers)
                layout.inspector?.let { r ->
                    Box(Modifier.offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp)) {
                        if (layout.logInInspector) {
                            var tab by remember { mutableStateOf("Card") }
                            Column(Modifier.fillMaxSize()) {
                                Segmented(tab, listOf("Card", "Log"), { it }, { tab = it }, Modifier.padding(8.dp), small = true)
                                if (tab == "Card") DuelInspector(h, duels, game, viewers, Modifier.weight(1f))
                                else DuelLogRail(h, duels, game, viewer, Modifier.weight(1f), head = { LogHead(h) })
                            }
                        } else {
                            DuelInspector(h, duels, game, viewers, Modifier.fillMaxSize())
                        }
                    }
                    Box(Modifier.offset((r.right + 6).dp, r.top.dp).width(1.dp).height(r.height.dp).background(c.ink12))
                }
                layout.log?.let { r ->
                    Box(Modifier.offset((r.left - 7).dp, r.top.dp).width(1.dp).height(r.height.dp).background(c.ink12))
                    Box(Modifier.offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp)) {
                        DuelLogRail(h, duels, game, viewer, Modifier.fillMaxSize(), head = { LogHead(h) })
                    }
                }
                if (layout.drawers) {
                    MuDrawer(duels.drawer == "card", { duels.drawer = null }, header = { FieldLabel("The card") }) {
                        DuelInspector(h, duels, game, viewers, Modifier.fillMaxWidth(), fill = false)
                    }
                    MuDrawer(duels.drawer == "log", { duels.drawer = null }, header = { FieldLabel("Log") }) {
                        DuelLogRail(h, duels, game, viewer, Modifier.fillMaxWidth().height(480.dp), head = { LogHead(h) })
                    }
                }
                // What a networked table waits on, over its top edge: never a row that pushes the cards down.
                // Over the table's own width, between the rails, never over their heads.
                val across = Modifier.offset(layout.field.left.dp, 4.dp).width((layout.phases.right - layout.field.left).dp)
                if (duels.role != null && !phone) Box(Modifier.zIndex(95f).then(across)) { NetBar(h, duels, overlay = true) }
                // The other seat's ask to move on, for the turn player to answer (1.0.79).
                if (game.state.proposal != null && replay == null) {
                    Box(Modifier.zIndex(96f).then(across)) { ProposalBar(duels, game.state) }
                }
                // Command mode's Spotlight (1.0.87): over the table and its rails, in the window's own layer.
                SpotlightWhenOpen(h, game, phone, replay == null)
            }
        }
    }
    if (duels.setupOpen) SetupDialog(h, duels)
    if (duels.libraryOpen) ReplayLibrary(duels)
    if (duels.combosOpen) DuelAiDialog(h)
    // Ai takes its seat's turns by itself when asked to (1.0.76): once a turn, when the turn passes to it.
    val live = duels.game
    // A duel that ends against a known deck is a practice game on Prep (1.0.80).
    LaunchedEffect(live?.state?.conceded, live?.state?.seats?.map { it.lp }) { logFinishedDuel(h) }
    // Ai's response triggers (1.0.85): the table watches for it, and wakes it on what it could answer.
    val watching = aiAtTable(h) && prefs.aiTriggers && live?.state?.solo == false && duels.replay == null
    // Ai changed seats: its watches were for the other hand (1.0.85).
    // Forgotten only on a real change of seat, never because the page opened again (1.0.86, the red team).
    LaunchedEffect(prefs.aiSeat) {
        if (duels.watchSeat != null && duels.watchSeat != prefs.aiSeat) duels.forgetTriggers()
        duels.watchSeat = prefs.aiSeat
    }
    // The seat Ai throws the opening roll for (1.0.87): its own, while it takes its seat's turns.
    val aiRolls = if (aiAtTable(h) && prefs.aiPlays && live?.state?.solo == false) prefs.aiSeat else null
    SideEffect {
        // Holding M opens the Spotlight listening, and what is heard is understood there (1.0.87).
        wireSpotlightVoice(h)
        duels.stopAi = { h.ai.stop() }
        duels.cueAi = { u -> lineCue(h, u) }
        duels.watcher = if (watching) prefs.aiSeat else null
        duels.aiEngaged = aiAtTable(h) && live?.state?.solo == false && (prefs.aiPlays || duels.aiSession != null)
        duels.autoDraw = prefs.autoDraw
        duels.openingRoll = prefs.openingRoll
        // Ai throws its own dice for who goes first, and chooses when it wins (1.0.87), while it takes its seat's turns.
        duels.aiOpeningSeat = aiRolls
    }
    // Ai at the table throws a moment after the duel is dealt, and chooses once both seats' dice have landed.
    LaunchedEffect(live?.state?.opening, aiRolls) {
        val o = live?.state?.opening ?: return@LaunchedEffect
        val seat = aiRolls ?: return@LaunchedEffect
        duels.aiOpeningSeat = seat
        if (o.decided) return@LaunchedEffect
        if (o.waitsOn(seat)) { kotlinx.coroutines.delay(AI_THROW_MS); duels.aiOpening() }
        else if (o.winner == seat) { kotlinx.coroutines.delay(AI_CHOOSE_MS); duels.aiOpening() }
    }
    // One effect, in order (1.0.86, the red team): Ai's answer is settled — the person's moves go on, a held change is
    // made, the turn's opening resumes — before Ai is woken on its watches, a kept cue, or its own turn. Two effects
    // raced, and Ai was asked to play its turn while its opening still waited.
    LaunchedEffect(duels.fired, duels.queuedCue, h.ai.running, watching, live?.state?.turn, live?.state?.active, prefs.aiPlays, duels.aiAnswering, duels.held) {
        if (h.ai.running) return@LaunchedEffect
        // Ai has answered (or stopped): the person's moves go on, and a phase change held for it is made.
        if (duels.aiAnswering || (!watching && duels.held != null)) duels.dontWait()
        if (!watching && duels.fired.isNotEmpty()) duels.fired = emptyList()
        // A turn's opening paused on a watch goes on once nothing waits on Ai (1.0.86); a step of it may fire a watch.
        duels.resumeTurn()
        val g = duels.game
        when {
            watching && duels.fired.isNotEmpty() -> cueTriggered(h)
            duels.queuedCue != null -> cueQueued(h)
            // Ai takes its seat's turns by itself when asked to (1.0.76): once a turn, once its opening is made.
            g != null && prefs.aiPlays && neue.prefs.ai.enabled && !g.state.solo && g.state.active == prefs.aiSeat &&
                duels.aiAskedTurn != g.state.turn && duels.replay == null && !duels.waitingOnAi && !duels.opening &&
                !g.state.beforeTurnOne -> askAiToPlay(h)
        }
    }

}

/**
 * The duel's row (1.0.78, kai: "the top bar with duel controls should be auto-hiding in immersive
 * mode … the immersive button can be moved to the other top bar"): on a desk or a tablet it is the
 * window's bar, so it folds away with it; New duel, Replays, the Table menu (the table's shape, whose
 * eyes, which way their cards face, Ai and combos), the seat, undo, the command line and full screen.
 */
@Composable
internal fun RowScope.DuelBarItems(h: NeueHolders, narrow: Boolean, phone: Boolean = false) {
    val c = Mu.colors
    val duels = h.duel
    val neue = h.neue
    // A guest's table is the host's duel as its seat sees it.
    val game = duels.shown
    val online = duels.role != null
    if (duels.replay != null) {
        Micro("Replay", color = c.ink45)
        Box(Modifier.weight(1f))
    } else {
        Tip("Start again with new decks", kbd = DeskShortcuts.chordFor(DeskAction.DUEL_NEW)?.let(DeskShortcuts::kbd)) {
            MuButton(if (phone || narrow) "New" else "New duel", { duels.setupOpen = true }, size = BtnSize.SM, icon = Icons.Plus)
        }
        Tip("Keep this duel, or watch one again") {
            MuButton("Replays", { duels.libraryOpen = true }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
        }
        if (game != null) {
            Box(Modifier.onGloballyPositioned { duels.tableMenuAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
                Tip("The table: its sides, whose eyes, which way their cards face") {
                    MuButton("Table ▾", { neue.menu = MenuSpec(duels.tableMenuAt, tableMenu(h)) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
                }
            }
            if (!game.state.solo && !online) {
                Tip("Sit at the other seat", kbd = DeskShortcuts.chordFor(DeskAction.DUEL_SWAP)?.let(DeskShortcuts::kbd)) {
                    MuButton("Seat: ${DuelWords.seatName(game.state, duels.bottom)}", { duels.swap() }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
                }
            }
            if (online) Small("Online · ${duels.peer ?: "waiting"}", color = c.ink70, maxLines = 1)
            VRule(Modifier.height(24.dp), color = c.ink12)
            IconButton(Icons.Undo, { duels.undo() }, enabled = game.canUndoMove || online || duels.held != null, label = if (online) "Ask to take back" else "Undo", reason = "Nothing to take back")
            if (!online) IconButton(Icons.Redo, { duels.redo() }, enabled = game.canRedo, label = "Redo", reason = "Nothing to put back")
            if (phone) {
                Box(Modifier.weight(1f))
                IconButton(Icons.More, { duels.drawer = if (duels.drawer == "log") null else "log" }, label = "Log")
            } else {
                SpotlightOpener(duels, Modifier.weight(1f), short = narrow)
                // Hold to speak a command (1.0.87): the M key for a hand on the mouse.
                DuelMic(h)
            }
        } else {
            Box(Modifier.weight(1f))
        }
    }
    if (!phone && game != null && duels.replay == null) {
        // The log and the card inspector, put away and brought back together (kai, 1.0.93).
        val shown = neue.prefs.duel.logShown
        Tip(if (shown) "Put the log and the card away: the table takes their room" else "Bring back the log and the card") {
            IconButton(
                if (shown) Icons.PanelLeftClose else Icons.PanelLeftOpen,
                { neue.update { it.copy(duel = it.duel.copy(logShown = !it.duel.logShown)) } },
                toggled = !shown,
                size = 32.dp,
                label = if (shown) "Hide the log and the card" else "Show the log and the card",
            )
        }
    }
    if (!phone) {
        Tip(if (neue.immersive) "Leave immersive mode" else "Immersive mode: full screen, the bar out of the way", kbd = DeskShortcuts.chordFor(DeskAction.IMMERSIVE)?.let(DeskShortcuts::kbd)) {
            IconButton(if (neue.immersive) Icons.Minimize else Icons.Maximize, { h.run(DeskAction.IMMERSIVE) }, toggled = neue.immersive, size = 32.dp, label = if (neue.immersive) "Leave full screen" else "Full screen")
        }
    }
}

/** The log's head (1.0.78): Ai's face, which opens it, and the deck's combos — moved here from the bars. */
@Composable
private fun LogHead(h: NeueHolders) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MuButton("Combos", { h.duel.combosOpen = true }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        if (h.neue.prefs.ai.enabled) AiBadge(h, height = 28.dp)
    }
}

/**
 * Where the command line stood (1.0.87): a slim line that opens the Spotlight, for a mouse or a finger — the keys open it
 * themselves (`/`, `Ctrl L`, any letter that is no duel key).
 */
@Composable
private fun SpotlightOpener(duels: Duels, modifier: Modifier, short: Boolean) {
    val c = Mu.colors
    Row(
        modifier
            .height(28.dp)
            .cursorPointer(caption = "Type a command")
            .muClickable { duels.openSpotlight() }
            .drawBehind { drawLine(c.ink25, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mono(if (short) "Type a command" else "Type a command · s h2 m3 · a m3 om1 · hand", Modifier.weight(1f), color = c.ink45, size = 12.sp)
        Kbd("/")
    }
}

/** The Table menu: the switches that change how the table is shown, not what is on it. */
private fun tableMenu(h: NeueHolders): List<MenuEntry> {
    val duels = h.duel
    val neue = h.neue
    val prefs = neue.prefs.duel
    val s = duels.shown?.state ?: return emptyList()
    val online = duels.role != null
    fun key(a: DeskAction) = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)
    return buildList {
        if (!s.solo && !online) {
            add(MenuEntry(if (prefs.twoSided) "One side of the table" else "Both sides of the table", hint = key(DeskAction.DUEL_SIDES)) {
                neue.update { it.copy(duel = it.duel.copy(twoSided = !it.duel.twoSided)) }
            })
            add(MenuEntry(if (prefs.knowledge == DuelPrefs.KNOW_ALL) "Only this seat's eyes" else "Both hands face-up") {
                neue.update { it.copy(duel = it.duel.copy(knowledge = if (it.duel.knowledge == DuelPrefs.KNOW_ALL) DuelPrefs.KNOW_SEAT else DuelPrefs.KNOW_ALL)) }
            })
        }
        if (!s.solo) {
            add(MenuEntry(if (prefs.facing) "Their cards face you" else "Their cards face them", hint = key(DeskAction.DUEL_FACING)) {
                neue.update { it.copy(duel = it.duel.copy(facing = !it.duel.facing)) }
            })
        }
        if (!s.solo && !online) add(MenuEntry("Sit at the other seat", hint = key(DeskAction.DUEL_SWAP)) { duels.swap() })
        // Command mode (1.0.87): every place's coordinate, as a chessboard's edge.
        add(MenuEntry(if (prefs.coordinates) "Hide the coordinates" else "Show the coordinates", hint = key(DeskAction.DUEL_COORDINATES)) {
            neue.update { it.copy(duel = it.duel.copy(coordinates = !it.duel.coordinates)) }
        })
        // The table draws for each turn (1.0.86; the draw alone from 1.0.93); the help for it is in the Ai and combos dialog.
        if (!online) {
            add(MenuEntry(if (prefs.autoDraw) "Don't draw for each turn" else "Draw for each turn") {
                neue.update { it.copy(duel = it.duel.copy(autoDraw = !it.duel.autoDraw)) }
            })
        }
        // The opening roll (1.0.87): new two-seat duels open with the dice, or the first seat goes first.
        if (!online) {
            add(MenuEntry(if (prefs.openingRoll) "New duels: the first seat goes first" else "New duels: roll for who goes first", hint = key(DeskAction.DUEL_ROLL)) {
                neue.update { it.copy(duel = it.duel.copy(openingRoll = !it.duel.openingRoll)) }
            })
        }
        // Command mode speaks back (1.0.87): the move understood, their moves, the answers — off unless asked for.
        add(MenuEntry(if (prefs.speak) "Keep the moves silent" else "Say the moves aloud") {
            neue.update { it.copy(duel = it.duel.copy(speak = !it.duel.speak)) }
        })
        // Show, then confirm (1.0.87, kai's choice): a spoken move waits for Enter or "yes" — or is made at once.
        add(MenuEntry(if (prefs.voiceConfirm) "Make spoken moves at once" else "Confirm spoken moves first") {
            neue.update { it.copy(duel = it.duel.copy(voiceConfirm = !it.duel.voiceConfirm)) }
        })
        add(MenuEntry(if (prefs.logShown) "Hide the log and the card" else "Show the log and the card", separatorBefore = true) {
            neue.update { it.copy(duel = it.duel.copy(logShown = !it.duel.logShown)) }
        })
        add(MenuEntry("The card", hint = "Read it large") { duels.drawer = "card" })
        add(MenuEntry("Log and chat") { duels.drawer = "log" })
        add(MenuEntry(if (neue.prefs.ai.enabled) "${h.ai.name} and combos…" else "Combos…") { duels.combosOpen = true })
        if (online) add(MenuEntry("Leave the table", separatorBefore = true, danger = true) { duels.leave() })
    }
}

/** A phone's duel row, under its own bar; the command line has a row of its own. */
@Composable
private fun PhoneDuelBar(h: NeueHolders, duels: Duels) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { DuelBarItems(h, narrow = true, phone = true) }
        if (duels.shown != null && duels.replay == null) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpotlightOpener(duels, Modifier.weight(1f), short = true)
                // A phone has no M key: the microphone is held instead (1.0.87).
                DuelMic(h, size = 36.dp)
            }
        }
    }
}

/**
 * The names the two seats sit down with (1.0.79): the names last typed, never the old "You" and
 * "Opponent" (which read as "You's turn"), and Ai's own name at the seat it plays, when it plays one.
 */
internal fun seatNames(h: NeueHolders, aiAtSeat: Int? = null): List<String> {
    val typed = h.neue.prefs.duel.names
    fun legacy(n: String) = n.isBlank() || n.equals("You", true) || n.equals("Opponent", true)
    val names = MutableList(2) { i -> typed.getOrNull(i)?.takeUnless(::legacy) ?: "Player ${i + 1}" }
    if (aiAtSeat != null && aiAtSeat in 0..1 && h.neue.prefs.ai.enabled) names[aiAtSeat] = h.ai.name
    return names
}

/** A test hand at once: the builder's deck, one player's table. */
internal fun testHand(h: NeueHolders, solo: Boolean = true) {
    val b = h.builder
    if (b.deck.main.isEmpty()) {
        h.neue.note = Note("The builder's deck is empty. Build one, or set up a duel with a deck from the library.")
        return
    }
    val names = seatNames(h)
    h.duel.start(
        DuelHeader(
            id = "d${System.currentTimeMillis()}",
            seed = System.nanoTime(),
            seats = listOf(
                SeatSetup(names[0], b.deck.main.map { it.value }, b.deck.extra.map { it.value }, b.deckId, b.deckName),
                if (solo) SeatSetup(names[1]) else SeatSetup(names[1], b.deck.main.map { it.value }, b.deck.extra.map { it.value }, b.deckId, b.deckName),
            ),
            solo = solo,
            created = System.currentTimeMillis(),
            openingRoll = !solo && h.neue.prefs.duel.openingRoll,
        ),
    )
}

/** Who sits where, with which deck: the builder's, any in the library, or — for the second seat — no one. */
@Composable
private fun SetupDialog(h: NeueHolders, duels: Duels) {
    val neue = h.neue
    val prefs = neue.prefs.duel
    var library by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(Unit) { library = h.deps.deckRepository.all() }
    val b = h.builder
    val builderChoice = DeckChoice(b.deckId, "${b.deckName} (the builder)", b.deck)
    val choices = listOf(builderChoice) + library.filter { it.entry.id != b.deckId }.map { DeckChoice(it.entry.id, it.entry.name, it.entry.deck) }
    val nobody = DeckChoice("-", "No one: one player's table", Deck())
    var mine by remember(library) { mutableStateOf(choices.firstOrNull { it.id == prefs.deckId } ?: builderChoice) }
    var theirs by remember(library) { mutableStateOf(if (prefs.opponentDeckId == "-") nobody else choices.firstOrNull { it.id == prefs.opponentDeckId } ?: mine) }
    var me by remember { mutableStateOf(seatNames(h)[0]) }
    var them by remember { mutableStateOf(seatNames(h)[1]) }
    val solo = theirs === nobody || theirs.id == "-"
    var where by remember { mutableStateOf(if (duels.role == Duels.NetRole.HOST) "host" else if (duels.role == Duels.NetRole.GUEST) "join" else "here") }
    val seat = { SeatSetup(me, mine.deck.main.map { it.value }, mine.deck.extra.map { it.value }, mine.id, mine.name) }
    MuDialog(
        "New duel",
        { duels.setupOpen = false },
        description = "Both decks are shuffled and five cards drawn each. The duel is kept as you play, so closing the window loses nothing.",
        footer = {
            if (where != "here") {
                MuButton("Close", { duels.setupOpen = false }, variant = BtnVariant.GHOST)
            } else {
            MuButton("Cancel", { duels.setupOpen = false }, variant = BtnVariant.GHOST)
            MuButton("Shuffle and draw", {
                if (mine.deck.main.isEmpty()) { neue.note = Note("That deck has no Main Deck to draw from."); return@MuButton }
                neue.update { it.copy(duel = it.duel.copy(deckId = mine.id, opponentDeckId = if (solo) "-" else theirs.id, names = listOf(me, them))) }
                duels.start(
                    DuelHeader(
                        id = "d${System.currentTimeMillis()}",
                        seed = System.nanoTime(),
                        seats = listOf(
                            SeatSetup(me, mine.deck.main.map { it.value }, mine.deck.extra.map { it.value }, mine.id, mine.name),
                            if (solo) SeatSetup(them) else SeatSetup(them, theirs.deck.main.map { it.value }, theirs.deck.extra.map { it.value }, theirs.id, theirs.name),
                        ),
                        solo = solo,
                        created = System.currentTimeMillis(),
                        // Who goes first is rolled for (1.0.87), unless the setting says the first seat does.
                        openingRoll = !solo && prefs.openingRoll,
                    ),
                )
                duels.setupOpen = false
            }, variant = BtnVariant.PRIMARY)
            }
        },
    ) {
        Segmented(where, listOf("here", "host", "join"), {
            when (it) { "here" -> "On this screen"; "host" -> "Host on the network"; else -> "Join a table" }
        }, { where = it }, small = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Your deck")
                MuSelect(mine, choices, { it.name }, { mine = it }, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(0.6f)) {
                FieldLabel("Your name")
                MuInput(me, { me = it }, Modifier.fillMaxWidth())
            }
        }
        if (where == "here") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Across the table")
                    MuSelect(theirs, choices + nobody, { it.name }, { theirs = it }, Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(0.6f)) {
                    FieldLabel("Their name")
                    MuInput(them, { them = it }, Modifier.fillMaxWidth())
                }
            }
            Help("Two seats on one screen is a hot-seat: Tab sits you at the other.")
        } else {
            OnlineSetup(h, duels, hosting = where == "host", mine = seat)
        }
    }
}

/** Ai throws its dice this long after the duel is dealt, and chooses this long after the last die lands (1.0.87). */
private const val AI_THROW_MS = 1200L
private const val AI_CHOOSE_MS = 3200L

/** The command line's help, one line: what to type. */
internal val COMMAND_HINT: String = DuelCommand.EXAMPLES.take(8).joinToString(" · ")
