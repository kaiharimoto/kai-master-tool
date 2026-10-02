package com.kaiharimoto.neue.duel

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.DuelLayouter
import com.kaiharimoto.mastertool.core.model.CardId
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
    duels.catalog = remember(index) { DuelCatalog { code -> index.byId(CardId(code))?.let(DuelCardInfo::of) } }
    LaunchedEffect(Unit) { duels.load() }
    // A refusal is said once, at the foot of the window.
    LaunchedEffect(duels.problem) { duels.problem?.let { neue.note = Note(it); duels.problem = null } }

    val game = duels.shown
    val replay = duels.replay
    Column(Modifier.fillMaxSize()) {
        if (replay != null) ReplayBar(duels, replay) else DuelBar(h, duels, prefs)
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
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
                    )
                }
                val viewers = duels.viewers(prefs)
                val viewer = if (viewers.size > 1) null else viewers.first()
                DuelTable(h, duels, game, layout, viewers)
                layout.inspector?.let { r ->
                    Box(Modifier.offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp)) {
                        if (layout.logInInspector) {
                            var tab by remember { mutableStateOf("Card") }
                            Column(Modifier.fillMaxSize()) {
                                Segmented(tab, listOf("Card", "Log"), { it }, { tab = it }, Modifier.padding(8.dp), small = true)
                                if (tab == "Card") DuelInspector(h, duels, game, viewers, Modifier.weight(1f))
                                else DuelLogRail(duels, game, viewer, Modifier.weight(1f))
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
                        DuelLogRail(duels, game, viewer, Modifier.fillMaxSize())
                    }
                }
                if (layout.drawers) {
                    MuDrawer(duels.drawer == "card", { duels.drawer = null }, header = { FieldLabel("The card") }) {
                        DuelInspector(h, duels, game, viewers, Modifier.fillMaxWidth())
                    }
                    MuDrawer(duels.drawer == "log", { duels.drawer = null }, header = { FieldLabel("Log") }) {
                        DuelLogRail(duels, game, viewer, Modifier.fillMaxWidth().height(480.dp))
                    }
                }
            }
        }
    }
    if (duels.setupOpen) SetupDialog(h, duels)
    if (duels.libraryOpen) ReplayLibrary(duels)
    if (duels.combosOpen) DuelAiDialog(h)
    // Ai takes its seat's turns by itself when asked to (1.0.76): once a turn, when the turn passes to it.
    val live = duels.game
    LaunchedEffect(live?.state?.turn, live?.state?.active, prefs.aiPlays) {
        val g = duels.game ?: return@LaunchedEffect
        if (prefs.aiPlays && neue.prefs.ai.enabled && !g.state.solo && g.state.active == prefs.aiSeat &&
            duels.aiAskedTurn != g.state.turn && duels.replay == null && !h.ai.running
        ) askAiToPlay(h)
    }
}

/** The page's own row: the duel's shape, the seat, the hot-seat's knowledge, undo and the command line. */
@Composable
private fun DuelBar(h: NeueHolders, duels: Duels, prefs: DuelPrefs) {
    val c = Mu.colors
    val neue = h.neue
    val phone = LocalPhone.current
    val game = duels.game
    val focus = remember { FocusRequester() }
    LaunchedEffect(duels.commandFocus) { if (duels.commandFocus > 0) runCatching { focus.requestFocus() } }
    val commandLine: @Composable (Modifier) -> Unit = { m ->
        MuInput(
            duels.command,
            { duels.command = it },
            m,
            placeholder = if (phone) "Type a command: ash to hand" else "Type a command: ash to hand · summon droll to m3 · lp -1000 · mill 3   ( / )",
            dense = true,
            focusRequester = focus,
            onSubmit = { duels.run(duels.command) },
        )
    }
    Column {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tip("Start again with new decks", kbd = DeskShortcuts.chordFor(DeskAction.DUEL_NEW)?.let(DeskShortcuts::kbd)) {
                MuButton(if (phone) "New" else "New duel", { duels.setupOpen = true }, size = BtnSize.SM, icon = Icons.Plus)
            }
            Tip("Keep this duel, or watch one again") {
                MuButton("Replays", { duels.libraryOpen = true }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
            }
            Tip(if (neue.prefs.ai.enabled) "${h.ai.name} at the table, and the deck's combos" else "The deck's combos") {
                MuButton(if (neue.prefs.ai.enabled && !phone) "${h.ai.name} · Combos" else "Combos", { duels.combosOpen = true }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
            }
            if (game != null) {
                if (!game.state.solo) {
                    if (!phone) {
                        Segmented(prefs.twoSided, listOf(true, false), { if (it) "Two sides" else "One side" }, { v -> neue.update { it.copy(duel = it.duel.copy(twoSided = v)) } }, small = true, compact = true)
                    }
                    Tip("Sit at the other seat", kbd = DeskShortcuts.chordFor(DeskAction.DUEL_SWAP)?.let(DeskShortcuts::kbd)) {
                        MuButton("Seat: ${com.kaiharimoto.mastertool.core.duel.text.DuelWords.seatName(game.state, duels.bottom)}", { duels.swap() }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
                    }
                    if (!phone) {
                        Segmented(
                            prefs.knowledge, listOf(DuelPrefs.KNOW_ALL, DuelPrefs.KNOW_SEAT),
                            { if (it == DuelPrefs.KNOW_ALL) "Both hands" else "This seat's eyes" },
                            { v -> neue.update { it.copy(duel = it.duel.copy(knowledge = v)) } },
                            small = true, compact = true,
                        )
                    }
                }
                VRule(Modifier.height(24.dp), color = c.ink12)
                IconButton(Icons.Undo, { duels.undo() }, enabled = game.canUndo, label = "Undo", reason = "Nothing to take back")
                IconButton(Icons.Redo, { duels.redo() }, enabled = game.canRedo, label = "Redo", reason = "Nothing to put back")
                if (phone) Box(Modifier.weight(1f)) else commandLine(Modifier.weight(1f))
                IconButton(Icons.More, { duels.drawer = if (duels.drawer == "log") null else "log" }, label = "Log")
            } else {
                Box(Modifier.weight(1f))
            }
        }
        // On a phone the command line has a row of its own.
        if (phone && game != null) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                commandLine(Modifier.weight(1f))
            }
        }
    }
}

/** A test hand at once: the builder's deck, one player's table. */
internal fun testHand(h: NeueHolders, solo: Boolean = true) {
    val b = h.builder
    if (b.deck.main.isEmpty()) {
        h.neue.note = Note("The builder's deck is empty. Build one, or set up a duel with a deck from the library.")
        return
    }
    val names = h.neue.prefs.duel.names
    h.duel.start(
        DuelHeader(
            id = "d${System.currentTimeMillis()}",
            seed = System.nanoTime(),
            seats = listOf(
                SeatSetup(names.getOrElse(0) { "You" }, b.deck.main.map { it.value }, b.deck.extra.map { it.value }, b.deckId, b.deckName),
                if (solo) SeatSetup(names.getOrElse(1) { "Opponent" }) else SeatSetup(names.getOrElse(1) { "Opponent" }, b.deck.main.map { it.value }, b.deck.extra.map { it.value }, b.deckId, b.deckName),
            ),
            solo = solo,
            created = System.currentTimeMillis(),
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
    var me by remember { mutableStateOf(prefs.names.getOrElse(0) { "You" }) }
    var them by remember { mutableStateOf(prefs.names.getOrElse(1) { "Opponent" }) }
    val solo = theirs === nobody || theirs.id == "-"
    MuDialog(
        "New duel",
        { duels.setupOpen = false },
        description = "Both decks are shuffled and five cards drawn each. The duel is kept as you play, so closing the window loses nothing.",
        footer = {
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
                    ),
                )
                duels.setupOpen = false
            }, variant = BtnVariant.PRIMARY)
        },
    ) {
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
        Help("Two seats on one screen is a hot-seat: Tab sits you at the other. Playing someone over the network comes in a later version.")
    }
}

/** The command line's help, one line: what to type. */
internal val COMMAND_HINT: String = DuelCommand.EXAMPLES.take(8).joinToString(" · ")
