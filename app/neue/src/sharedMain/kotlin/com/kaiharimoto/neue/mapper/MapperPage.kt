package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.BoardCards
import com.kaiharimoto.mastertool.core.duel.mapper.BoardEntry
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.BoardQuery
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapLine
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView
import com.kaiharimoto.mastertool.core.duel.mapper.MapperView.Moment
import com.kaiharimoto.mastertool.core.duel.mapper.MapperWords
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.builder.TapSurface
import com.kaiharimoto.neue.builder.surfaceTaps
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSlider
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Stepper
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.pages.PageHeader
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 10 Gameplay Mapper (Phase M step M1, `docs/phases/M.md` §6; kai: "it would find optimized endboards from a library that
 * it found during runs … a range of boards based on what the user wants like a filter system with adjustable weights"):
 * the open deck's board library going first or second, chosen by the person's filters and weights — nothing ranks a board
 * in advance — with the boards nothing beats marked and each board's share of dealt hands; the inspector reads a board in
 * full and plays any of its lines on the Duel page. The Starters tab is the starter table: every engine card and pair, what
 * it reaches, how often it is opened.
 *
 * **The design run** (M.md §6½, kai: "information hierarchy, density control, and layouts … smart and adaptive … intuitive
 * to the user of what they're looking at"): the page leads with what the moment needs ([MapperView.Moment]: the two runs
 * before any board, the count once before any share, the library after), asks what the board should do in one press
 * ([MapperView.ASKS], the weights folded behind them), draws the library at three densities and in sections named by what
 * their boards share, and opens the inspector only for a board chosen.
 *
 * Runs (dealt hands, the starter table) are the holder's ([Mappers]), off the frame thread, with their progress and Stop.
 * Master UI throughout: paper and ink, the cards the only colour, and nothing moves but them.
 */
@Composable
fun MapperPage(h: NeueHolders) {
    val m = h.mapper
    LaunchedEffect(h.builder.deckId, h.decksReload) { m.open(h.builder.deckId) }
    LaunchedEffect(Unit) { if (!h.effects.loaded) h.effects.reloadNow() }
    val phone = LocalPhone.current
    val moment = moment(h)
    Column(Modifier.fillMaxSize()) {
        PageHeader(numeral = 10, title = "Gameplay Mapper", subtitle = subtitle(h)) { HeaderActions(h, phone) }
        RunLine(h, phone, moment)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (moment) {
                Moment.NO_DECK -> EmptyState("Nothing to map yet.", "Save the deck first: its boards are kept with it.") {
                    MuButton("Save the deck", { h.builder.save { h.decksReload++ } }, variant = BtnVariant.PRIMARY, arrow = true)
                }
                Moment.LOADING -> Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Breathe()
                    Small("Reading the deck's boards")
                }
                Moment.UNREADABLE -> EmptyState(
                    "This deck's boards could not be read.",
                    "${m.side.unreadable.joinToString()} was written by a newer version: update the app to read it. Nothing is written over it.",
                )
                Moment.FIRST_RUN -> if (m.tab == MapperTab.STARTERS && m.side.starters != null) StartersTab(h, phone) else FirstRun(h, phone)
                else -> if (m.tab == MapperTab.LIBRARY) LibraryTab(h, phone, moment) else StartersTab(h, phone)
            }
        }
    }
    if (phone && m.inspecting) {
        val e = m.selected?.let { m.side.library.byKey[it] }
        val row = m.starter?.let { s -> m.starterRows().firstOrNull { it.cards == s } }
        when {
            m.tab == MapperTab.LIBRARY && e != null -> MuDialog("The board", { m.inspecting = false }) { BoardInspector(h, e) }
            m.tab == MapperTab.STARTERS && row != null -> MuDialog("The starter", { m.inspecting = false }) { StarterInspector(h, row) }
            else -> m.inspecting = false
        }
    }
}

/** Where the person is on this page: what it leads with. */
private fun moment(h: NeueHolders): Moment {
    val m = h.mapper
    return MapperView.moment(
        hasDeck = h.builder.deckId != null,
        loaded = m.loaded,
        unreadable = m.side.unreadable.isNotEmpty(),
        boards = m.side.library.boards.size,
        counted = m.counted != null,
    )
}

private fun subtitle(h: NeueHolders): String {
    val m = h.mapper
    val lib = m.side.library
    val parts = mutableListOf(h.builder.deckName.ifBlank { "No deck" }, if (m.first) "Going first" else "Going second")
    if (m.loaded) {
        parts += "${GoldfishWords.count(lib.live.size)} boards"
        val stale = lib.boards.size - lib.live.size
        if (stale > 0) parts += "$stale stale"
        m.counted?.let { parts += "${GoldfishWords.count(it.hands)} hands counted" }
    }
    return parts.joinToString(" · ")
}

/** A key's chord as the table writes it, else [fallback]. */
private fun keyOf(action: DeskAction, fallback: String) = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd) ?: fallback

@Composable
private fun KeyCap(text: String) {
    if (!LocalPhone.current) Kbd(text)
}

/** Library | Starters, and going first | second. */
@Composable
private fun HeaderActions(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Segmented(m.tab, MapperTab.entries, { it.words }, { m.tab = it }, small = true)
        KeyCap("${keyOf(DeskAction.MAPPER_LIBRARY, "L")} ${keyOf(DeskAction.MAPPER_STARTERS, "S")}")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Segmented(m.first, listOf(true, false), { if (it) "Going first" else "Going second" }, { side(h, it) }, small = true)
        KeyCap(keyOf(DeskAction.MAPPER_SIDE, "G"))
    }
}

/** The other side's library on screen: the board and starter chosen belong to one side. */
private fun side(h: NeueHolders, first: Boolean) {
    val m = h.mapper
    if (m.first == first) return
    m.first = first
    m.selected = null
    m.starter = null
    m.only = null
}

// ---- runs -------------------------------------------------------------------------------------------------------

/**
 * The runs, as the moment needs them: while one runs, its progress and Stop; before any board, nothing here (the page
 * itself is the two runs, [FirstRun]); after, one quiet line — what the library was counted from, and Map hands and Map
 * the starters beside it, the hands and seed folded away. What the last run said stays under it until dismissed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunLine(h: NeueHolders, phone: Boolean, moment: Moment) {
    val m = h.mapper
    val c = Mu.colors
    val pad = if (phone) 16.dp else 32.dp
    val running = m.running
    val quiet = running == null && moment in setOf(Moment.NO_DECK, Moment.LOADING, Moment.UNREADABLE, Moment.FIRST_RUN)
    if (quiet && m.said == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running != null) {
            val p = m.progress
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Breathe()
                val words = buildString {
                    append(running.kind.words)
                    if (!running.first) append(" going second")
                    if (p != null && p.total > 0) append(": ${GoldfishWords.count(p.done)} of ${GoldfishWords.count(p.total)}")
                    if (p != null && p.ms >= 1000) append(" · ${p.ms / 1000} s")
                }
                Small(words, Modifier.weight(1f), color = c.ink, maxLines = 2)
                MuButton("Stop", m::stop, size = BtnSize.SM)
                KeyCap(keyOf(DeskAction.MAPPER_STOP, "Ctrl ."))
            }
            Progress(p?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total })
        } else if (!quiet) {
            val why = remember(h.builder.deck, h.effects.loaded, h.effects.revision, m.loaded, m.first, m.side, m.running) { refusal(h) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Small(countedFrom(h), Modifier.weight(1f), color = c.ink70, maxLines = 1)
                if (phone) {
                    MuButton("Map again", { m.runSettings = true }, size = BtnSize.SM)
                } else {
                    MicroLink(if (m.runSettings) "Hide hands and seed" else "Hands and seed", { m.runSettings = !m.runSettings })
                    MuButton("Map hands", { runHands(h) }, size = BtnSize.SM, enabled = why == null, reason = why)
                    KeyCap(keyOf(DeskAction.MAPPER_RUN, "R"))
                    MuButton("Map the starters", { runStarters(h) }, size = BtnSize.SM, variant = BtnVariant.GHOST, enabled = why == null, reason = why)
                    KeyCap(keyOf(DeskAction.MAPPER_RUN_STARTERS, "Shift R"))
                }
            }
            if (!phone && m.runSettings) RunSettings(h, phone)
        }
        m.said?.let { said ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small(said, Modifier.weight(1f, fill = false), maxLines = 3)
                MicroLink("Dismiss", { m.said = null })
            }
        }
    }
    HRule()
    if (phone && m.runSettings) {
        MuDialog("Map again", { m.runSettings = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RunSettings(h, phone)
                RunButtons(h, stacked = true) { m.runSettings = false }
            }
        }
    }
}

/** What the library on screen was counted from, in a line: "Counted from 500 hands dealt with seed 1". */
private fun countedFrom(h: NeueHolders): String {
    val m = h.mapper
    val run = m.counted
    return if (run == null) "${GoldfishWords.count(m.side.library.live.size)} boards found by the starter table and earlier runs"
    else "Counted from ${GoldfishWords.count(run.hands)} hands dealt with seed ${run.seed}" +
        if (run.incomplete > 0) " · ${GoldfishWords.count(run.incomplete)} not searched to the end, so each share is at least what it says" else ""
}

/** How many hands, the seed and Re-roll. */
@Composable
private fun RunSettings(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val default = if (phone) Mappers.PHONE_HANDS else Mappers.DESK_HANDS
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Hands", color = c.ink45)
        MuInput(m.handsText ?: default.toString(), { t -> m.handsText = t.filter(Char::isDigit).take(5) }, Modifier.width(72.dp), mono = true, dense = true)
        Micro("Seed", color = c.ink45)
        MuInput(m.seedText, { t -> m.seedText = t.filter { it.isDigit() || it == '-' }.take(18) }, Modifier.width(if (phone) 88.dp else 120.dp), mono = true, dense = true)
        MuButton("Re-roll", { m.reroll() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
}

/** Map hands and Map the starters, side by side or (a phone's dialog) one under the other; [then] after a press. */
@Composable
private fun RunButtons(h: NeueHolders, stacked: Boolean, then: () -> Unit = {}) {
    val m = h.mapper
    val why = remember(h.builder.deck, h.effects.loaded, h.effects.revision, m.loaded, m.first, m.side, m.running) { refusal(h) }
    val hands: @Composable () -> Unit = {
        MuButton("Map hands", { runHands(h); then() }, Modifier.let { if (stacked) it.fillMaxWidth() else it }, variant = BtnVariant.PRIMARY, enabled = why == null, reason = why)
    }
    val starters: @Composable () -> Unit = {
        MuButton("Map the starters", { runStarters(h); then() }, Modifier.let { if (stacked) it.fillMaxWidth() else it }, enabled = why == null, reason = why)
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { hands(); starters() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { hands(); starters() }
    }
}

private fun refusal(h: NeueHolders): String? {
    val m = h.mapper
    if (!h.effects.loaded) return "Reading the written effects"
    return m.refusal(h.goldfishDeck(), h.goldfishKit(), m.first)
}

internal fun runHands(h: NeueHolders) {
    val m = h.mapper
    if (m.busy || !h.effects.loaded) return
    val phone = h.neue.phone
    m.startHands(h.goldfishDeck(), h.goldfishKit(), m.hands(if (phone) Mappers.PHONE_HANDS else Mappers.DESK_HANDS))
}

internal fun runStarters(h: NeueHolders) {
    val m = h.mapper
    if (m.busy || !h.effects.loaded) return
    m.startStarters(h.goldfishDeck(), h.goldfishKit())
}

/**
 * Before any board: the page is the two runs, in the order that answers most soonest — the starters (what each engine
 * card makes, alone and with a partner), then dealt hands (how often the deck gets there) — each saying what it answers.
 */
@Composable
private fun FirstRun(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val why = remember(h.builder.deck, h.effects.loaded, h.effects.revision, m.loaded, m.first, m.side, m.running) { refusal(h) }
    val side = if (m.first) "going first" else "going second"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = if (phone) 20.dp else 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        MuText("What can this deck make $side?", Modifier.widthIn(max = 720.dp), MuType.h1(LocalMuFonts.current))
        Small(
            "Two runs answer it, and both are kept with the deck. Only cards with written effects play: the Effects app on the World page writes them.",
            Modifier.widthIn(max = 640.dp),
        )
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StepBox(1, "Map the starters", "Every engine card alone, then every pair: the boards each makes, and what two make that neither makes alone. Quick, and the first boards in the library.") {
                MuButton("Map the starters", { runStarters(h) }, variant = BtnVariant.PRIMARY, enabled = why == null && !m.busy, reason = why ?: "A run is going")
                KeyCap(keyOf(DeskAction.MAPPER_RUN_STARTERS, "Shift R"))
            }
            StepBox(2, "Map dealt hands", "Deal hands and map each one: how often the deck reaches each kind of board, the share every board is shown with.") {
                MuButton("Map hands", { runHands(h) }, enabled = why == null && !m.busy, reason = why ?: "A run is going")
                KeyCap(keyOf(DeskAction.MAPPER_RUN, "R"))
                if (!phone) RunSettings(h, phone)
            }
            if (phone) RunSettings(h, phone)
        }
        if (why != null) Help(why, color = c.ink45)
    }
}

/** One numbered step: what it is, what it answers, its buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepBox(n: Int, title: String, line: String, actions: @Composable () -> Unit) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().border(1.dp, c.ink25).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Numeral(n, color = c.ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            H2(title)
            Small(line, color = c.ink70)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

// ---- the library --------------------------------------------------------------------------------------------------

/**
 * The library: what to ask of a board and how to see the answer over it, the boards in sections, and — only for a board
 * chosen — the inspector beside them. The weights, bounds and card rules stand on the left only while asked for ([Mappers.tuning]).
 */
@Composable
private fun LibraryTab(h: NeueHolders, phone: Boolean, moment: Moment) {
    val m = h.mapper
    val c = Mu.colors
    val lib = m.side.library
    val boards = m.ordered()
    if (phone) {
        Column(Modifier.fillMaxSize()) {
            AskBar(h, phone = true, boards.size)
            if (moment == Moment.UNCOUNTED) UncountedNote(h, phone = true)
            Boards(h, boards, phone = true, Modifier.weight(1f))
        }
        if (m.tuning) MuDialog("Weights and filters", { m.tuning = false }) { QueryPanel(h, Modifier.fillMaxWidth()) }
        return
    }
    Row(Modifier.fillMaxSize()) {
        if (m.tuning) {
            QueryPanel(h, Modifier.width(280.dp).fillMaxHeight())
            VRule(color = c.ink12)
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            AskBar(h, phone = false, boards.size)
            if (moment == Moment.UNCOUNTED) UncountedNote(h, phone = false)
            Boards(h, boards, phone = false, Modifier.weight(1f))
        }
        val e = m.selected?.let { lib.byKey[it] }
        if (e != null) {
            VRule(color = c.ink12)
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { BoardInspector(h, e) }
            }
        }
    }
}

/** Once, over the library, while no dealt hands were counted on it: every share waits on that one run. */
@Composable
private fun UncountedNote(h: NeueHolders, phone: Boolean) {
    val c = Mu.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp).border(1.dp, c.ink).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Small("How often each board is made waits on dealt hands: the starter table found them, a run of hands counts them.", Modifier.weight(1f), color = c.ink)
        if (!phone) MuButton("Map hands", { runHands(h) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
    }
}

/**
 * What to ask of a board, in one press ([MapperView.ASKS], or "Your own" once the weights are moved), and how to see the
 * answer: the count, the order and the density, each with its key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskBar(h: NeueHolders, phone: Boolean, count: Int) {
    val m = h.mapper
    val c = Mu.colors
    val q = m.query
    val asked = MapperView.askOf(q.weights)
    val pad = if (phone) 16.dp else 24.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val asks: @Composable () -> Unit = {
            Micro("Lead with", color = c.ink45)
            MapperView.ASKS.forEach { a -> key(a.id) { Tag(a.name, asked?.id == a.id, { m.ask(a) }, caption = askWords(a)) } }
            Tag(if (asked == null) "Your own: ${yourOwn(q)}" else "Your own", asked == null, { m.tuning = true }, caption = "Set the weights yourself")
        }
        if (phone) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { asks() }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                asks()
                Spacer(Modifier.width(6.dp))
                MuButton("Weights and filters", { m.tuning = !m.tuning }, size = BtnSize.SM, variant = BtnVariant.GHOST, toggled = m.tuning)
                KeyCap(keyOf(DeskAction.MAPPER_TUNE, "W"))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Small(countWords(h, count), color = c.ink70, maxLines = 1)
            m.only?.let { only -> Tag(only.words, true, { m.only = null }, caption = "Show every board") }
            narrowings(h).forEach { (words, off) -> Tag(words, true, off, caption = "Take off") }
            if (phone) {
                MuButton("Weights and filters", { m.tuning = true }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phone) {
                MuSelect(m.order, MapperView.Order.entries, { it.words }, { m.order = it }, Modifier.weight(1f), small = true)
                Segmented(m.show(count, phone = true), listOf(MapperShow.OVERVIEW, MapperShow.CARDS), { it.words }, { m.chosenShow = it }, small = true)
            } else {
                Micro("Order", color = c.ink45)
                Segmented(m.order, MapperView.Order.entries, { it.words }, { m.order = it }, small = true)
                KeyCap(keyOf(DeskAction.MAPPER_ORDER, "O"))
                Spacer(Modifier.weight(1f))
                Micro("Show", color = c.ink45)
                Segmented(m.show(count, phone = false), MapperShow.entries, { it.words }, { m.chosenShow = it }, small = true)
                KeyCap("${keyOf(DeskAction.MAPPER_DENSER, "-")} ${keyOf(DeskAction.MAPPER_LOOSER, "=")}")
            }
        }
    }
    HRule()
}

/** The weights on screen in a few words, for the "Your own" chip: "negates ×2, kept in hand ×0.5". */
private fun yourOwn(q: BoardPreset): String =
    q.weights.filterValues { it != 0.0 }.entries.sortedByDescending { kotlin.math.abs(it.value) }.take(2)
        .joinToString(", ") { (k, w) -> "${MapperView.label(k, 2)} ×${trim(kotlin.math.abs(w))}${if (w < 0) " less" else ""}" }
        .ifEmpty { "nothing asked" }

/** What an ask weighs, for its caption. */
private fun askWords(a: BoardPreset): String =
    a.weights.entries.sortedByDescending { it.value }.joinToString(", ") { (k, w) -> "${MapperView.label(k, 2)} ×${trim(w)}" }

/** "27 boards · 2 unbeaten", with stale and hidden boards said. */
private fun countWords(h: NeueHolders, count: Int): String {
    val m = h.mapper
    val lib = m.side.library
    val front = m.ranked().count { it.front }
    val hidden = lib.boards.size - count
    return buildString {
        append(GoldfishWords.count(count)).append(if (count == 1) " board" else " boards")
        if (front > 0) append(" · ").append(front).append(" unbeaten")
        if (hidden > 0) append(" · ").append(GoldfishWords.count(hidden)).append(" left out")
    }
}

/** The bounds and card rules on screen, each a chip and its way off: the reader always sees what narrows the library. */
private fun narrowings(h: NeueHolders): List<Pair<String, () -> Unit>> {
    val m = h.mapper
    val q = m.query
    return q.filters.map { f ->
        val words = buildString {
            if (f.min != null) append("At least ${MapperView.unit(f.head, f.min!!.toInt())}")
            if (f.max != null) append(if (f.min != null) ", at most ${f.max!!.toInt()}" else "At most ${MapperView.unit(f.head, f.max!!.toInt())}")
        }
        words to { m.query = m.query.copy(id = "", name = "", filters = m.query.filters - f) }
    } + q.uses.map { id -> "With ${name(h, id)}" to { m.query = m.query.copy(id = "", name = "", uses = m.query.uses - id) } } +
        q.avoids.map { id -> "Without ${name(h, id)}" to { m.query = m.query.copy(id = "", name = "", avoids = m.query.avoids - id) } } +
        (if (q.stale) listOf("Stale shown" to { m.query = m.query.copy(id = "", name = "", stale = false) }) else emptyList())
}

/** Presets, weights, bounds, cards and stale boards: what the library is chosen by, beside it while asked for. */
@Composable
private fun QueryPanel(h: NeueHolders, modifier: Modifier) {
    val m = h.mapper
    val c = Mu.colors
    val q = m.query
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("Weights and filters", Modifier.weight(1f), color = c.ink)
            if (!LocalPhone.current) MicroLink("Fold away", { m.tuning = false })
        }
        PresetPicker(h)
        if (q.by == BoardPreset.AI && q.why.isNotBlank()) Small("${h.ai.name}: ${q.why}", color = c.ink70)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("Weights", color = c.ink45)
            Help("Right: more is better. Left: less is. Nothing ranks a board until you say what you want.", color = c.ink45)
            // The weighted traits first: what is asked stands above what is not.
            val heads = BoardTraits.HEADS.sortedBy { if ((q.weights[it] ?: 0.0) != 0.0) 0 else 1 }
            heads.forEach { head -> key(head) { WeightRow(h, head) } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("At least", color = c.ink45)
            (BoardTraits.MORE_IS_BETTER + "bodies").forEach { head ->
                key(head) {
                    val min = q.filters.firstOrNull { it.head == head }?.min?.toInt() ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowText(MapperWords.head(head), Modifier.weight(1f), color = if (min > 0) c.ink else c.ink70)
                        Stepper(min, { m.bound(head, it) }, min = 0, max = 9)
                    }
                }
            }
        }
        CardRules(h)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MuSwitch(q.stale, { m.query = q.copy(id = "", name = "", stale = it) })
            Small("Show stale boards", Modifier.weight(1f))
        }
        val lib = m.side.library
        if (lib.boards.size > lib.live.size && !m.busy) {
            MicroLink("Play every line again on the deck as it is", { m.startCheck(h.goldfishDeck(), h.goldfishKit()) })
        }
    }
}

/** The presets: chosen from a menu, saved under a name, deleted. Ai's are marked as Ai's. */
@Composable
private fun PresetPicker(h: NeueHolders) {
    val m = h.mapper
    val c = Mu.colors
    val q = m.query
    var naming by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Preset", color = c.ink45)
        val all = m.presets.all
        val chosen = all.firstOrNull { it.id == q.id && q.id.isNotEmpty() }
        val options = listOf<String?>(null) + all.map { it.id }
        MuSelect(chosen?.id, options, { id ->
            if (id == null) "Your own" else all.firstOrNull { it.id == id }?.let { p -> p.name + if (p.by == BoardPreset.AI) " · ${h.ai.name}'s" else "" } ?: "?"
        }, { id -> if (id != null) m.choosePreset(id) }, Modifier.fillMaxWidth(), small = true)
        val name = naming
        if (name != null) {
            MuInput(name, { naming = it.take(60) }, Modifier.fillMaxWidth(), placeholder = "Name it", dense = true, onSubmit = {
                m.savePreset(name)
                naming = null
            })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MuButton("Save", { m.savePreset(name); naming = null }, size = BtnSize.SM, enabled = name.isNotBlank(), reason = "Name it first")
                MuButton("Cancel", { naming = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MicroLink("Save as a preset", { naming = chosen?.name ?: "" })
                if (chosen != null && chosen.id != BoardPreset.DEFAULT.id) MicroLink("Delete it", { m.deletePreset(chosen.id) })
            }
        }
    }
}

/** One trait's weight: a slider from less is better to more is better, nothing in the middle. */
@Composable
private fun WeightRow(h: NeueHolders, head: String) {
    val m = h.mapper
    val c = Mu.colors
    val w = m.query.weights[head] ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowText(MapperWords.head(head), Modifier.weight(1f), color = if (w != 0.0) c.ink else c.ink70)
            Mono(weightWords(w), color = if (w != 0.0) c.ink else c.ink45)
        }
        MuSlider(w.toFloat(), { v -> m.weigh(head, (Math.round(v * 2f) / 2.0)) }, Modifier.fillMaxWidth(), range = -2f..2f, steps = 7, name = MapperWords.head(head), valueText = weightWords(w))
    }
}

private fun weightWords(w: Double): String = when {
    w == 0.0 -> "—"
    w > 0 -> "more ×${trim(w)}"
    else -> "less ×${trim(-w)}"
}

private fun trim(x: Double): String = if (x == Math.floor(x)) x.toInt().toString() else x.toString()

/** The cards boards must use or must not, each with its way off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardRules(h: NeueHolders) {
    val m = h.mapper
    val c = Mu.colors
    val q = m.query
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Cards", color = c.ink45)
        if (q.uses.isEmpty() && q.avoids.isEmpty()) {
            Help("Right-click a card on a board (hold it, with a finger) for only the boards with it, or without it.", color = c.ink45)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            q.uses.forEach { id -> Tag("With ${name(h, id)}", true, { m.query = q.copy(id = "", name = "", uses = q.uses - id) }, caption = "Take off") }
            q.avoids.forEach { id -> Tag("Without ${name(h, id)}", true, { m.query = q.copy(id = "", name = "", avoids = q.avoids - id) }, caption = "Take off") }
        }
    }
}


/** One thing in the library's list: a section's heading, or a board with its place in the order. */
private sealed interface Item {
    data class Heading(val title: String, val count: Int, val n: Int) : Item
    data class Board(val r: BoardQuery.Ranked, val at: Int) : Item
}

/** [boards] cut into the sections a reader can name ([MapperView.sections]), flattened for one lazy list. */
private fun flatten(h: NeueHolders, boards: List<BoardQuery.Ranked>, coarse: Boolean): List<Item> {
    val m = h.mapper
    var at = 0
    return MapperView.sections(boards, m.order, m.query.weights, coarse) { m.shareOf(it) }.mapIndexed { n, s -> n to s }.flatMap { (n, s) ->
        val head = if (s.title.isEmpty()) emptyList() else listOf<Item>(Item.Heading(s.title, s.boards.size, n))
        head + s.boards.map { Item.Board(it, at++) }
    }
}

/** The library as [Mappers.show] draws it, the chosen board kept in view. */
@Composable
private fun Boards(h: NeueHolders, boards: List<BoardQuery.Ranked>, phone: Boolean, modifier: Modifier) {
    val m = h.mapper
    val show = m.show(boards.size, phone)
    val list = remember(boards, m.order, m.query.weights, m.counted, show) { flatten(h, boards, coarse = show == MapperShow.OVERVIEW) }
    Box(modifier.fillMaxWidth()) {
        when {
            boards.isEmpty() -> EmptyState("No board passes.", "Take a filter or a card off (their chips are over the library), or show the stale boards.")
            show == MapperShow.MAP -> BoardPlot(h, boards)
            show == MapperShow.ROWS -> BoardRows(h, list)
            else -> BoardGrid(h, list, overview = show == MapperShow.OVERVIEW, phone)
        }
    }
}

/** A board chosen: read in the inspector (a phone's opens over the list); a second tap plays its cheapest line. */
private fun Modifier.boardTaps(h: NeueHolders, taps: TapSurface, e: BoardEntry): Modifier = this
    .surfaceTaps(taps, onTap = { choose(h, e.key) }, onDoubleTap = { choose(h, e.key); e.lines.firstOrNull()?.let { replay(h, it) } })
    .cursorPointer(caption = "Read")

/** [key] chosen, or let go when it was the one chosen (a second press closes the inspector on the desk). */
private fun choose(h: NeueHolders, key: String) {
    val m = h.mapper
    m.selected = key
    if (h.neue.phone) m.inspecting = true
}

/** A section's heading: what its boards share, and how many. */
@Composable
private fun Heading(item: Item.Heading, phone: Boolean) {
    val c = Mu.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp, start = if (phone) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Micro(item.title, color = c.ink)
        Mono(if (item.count == 1) "1 board" else "${item.count} boards", color = c.ink45)
        Box(Modifier.weight(1f).height(1.dp).background(c.ink12))
    }
}

/** Cards and the overview: tiles in a grid, a heading across the whole row over each section. */
@Composable
private fun BoardGrid(h: NeueHolders, list: List<Item>, overview: Boolean, phone: Boolean) {
    val m = h.mapper
    val taps = remember { TapSurface(repeats = false) }
    val state = rememberLazyGridState()
    LaunchedEffect(m.selected) {
        val at = list.indexOfFirst { it is Item.Board && it.r.entry.key == m.selected }
        if (at >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == at }) state.scrollToItem(at)
    }
    val cell = when {
        overview && phone -> 104.dp
        overview -> 196.dp
        phone -> 300.dp
        else -> 300.dp
    }
    LazyVerticalGrid(
        GridCells.Adaptive(cell),
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = if (phone) 12.dp else 24.dp, end = if (phone) 12.dp else 24.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(if (overview) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (overview) 8.dp else 12.dp),
    ) {
        list.forEach { cell ->
            when (cell) {
                is Item.Heading -> item(key = "h:${cell.n}", span = { GridItemSpan(maxLineSpan) }) { Heading(cell, phone) }
                is Item.Board -> item(key = cell.r.entry.key) {
                    if (overview) OverviewTile(h, cell.r, cell.at, taps, phone) else BoardTile(h, cell.r, cell.at, taps)
                }
            }
        }
    }
}

/**
 * A board as a tile, read top to bottom in the order a player asks: how much of what I asked for (the leads, large), what
 * is on the field (the art), how often I get it (the share, as a bar), what else it holds (in words, zeros left out), and
 * where it stands (its place, whether nothing beats it, its shortest line).
 */
@Composable
private fun BoardTile(h: NeueHolders, r: BoardQuery.Ranked, i: Int, taps: TapSurface) {
    val m = h.mapper
    val c = Mu.colors
    val e = r.entry
    val selected = m.selected == e.key
    val leads = MapperView.leads(e.traits, m.query.weights)
    Inverted(selected) {
        val ci = Mu.colors
        Column(
            Modifier.fillMaxWidth().background(ci.paper).border(1.dp, if (selected) ci.ink else c.ink12).boardTaps(h, taps, e).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Leads(leads, big = true, Modifier.weight(1f))
                Numeral(i + 1, color = ci.ink45)
            }
            FieldStrip(h, e.cards, cardWidth = 46.dp, most = 6)
            ShareBar(h, e)
            val rest = MapperView.rest(e.traits, leads)
            if (rest.isNotEmpty()) Help("Also $rest", color = ci.ink70, maxLines = 2)
            Footer(r)
        }
    }
}

/**
 * The densest tile: the field's art, then a line of the numbers its section's heading does not say (the overview's sections
 * cut by the heaviest ask alone), whether nothing beats it, and its share. A phone's three to a row keep the art and the
 * share only.
 */
@Composable
private fun OverviewTile(h: NeueHolders, r: BoardQuery.Ranked, i: Int, taps: TapSurface, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val e = r.entry
    val selected = m.selected == e.key
    val leads = MapperView.leads(e.traits, m.query.weights).let { if (m.order == MapperView.Order.ASKED) it.drop(1) else it }.take(2)
    Inverted(selected) {
        val ci = Mu.colors
        Column(
            Modifier.fillMaxWidth().background(ci.paper).border(1.dp, if (selected) ci.ink else c.ink12).boardTaps(h, taps, e).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FieldStrip(h, e.cards, cardWidth = if (phone) 24.dp else 32.dp, most = if (phone) 3 else 5, words = false)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!phone) Mono(leads.joinToString("  ") { "${it.value} ${MapperWords.short(it.head).lowercase()}" }, Modifier.weight(1f), color = ci.ink)
                else Spacer(Modifier.weight(1f))
                if (r.front) Box(Modifier.cursor(CursorMode.DEFAULT, caption = "Unbeaten: no board beats it on everything you asked for")) { Mono("◆", color = ci.ink) }
                m.shareOf(e)?.let { Mono(MapperView.pct(it), color = ci.ink70) }
            }
        }
    }
}

/** The numbers a board leads with: each a large numeral and its word, what was asked in ink, the rest lighter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Leads(leads: List<MapperView.Lead>, big: Boolean, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val fonts = LocalMuFonts.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        leads.forEach { l ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MuText(l.value.toString(), style = MuType.mono(fonts, if (big) 26.sp else 20.sp), color = if (l.asked) c.ink else c.ink70, maxLines = 1)
                Micro(MapperView.label(l.head, l.value), Modifier.padding(bottom = if (big) 4.dp else 2.dp), color = if (l.asked) c.ink else c.ink45)
            }
        }
    }
}

/** The share of hands as a bar and its number; a press makes it a filter. Nothing when uncounted (said once, over the list). */
@Composable
private fun ShareBar(h: NeueHolders, e: BoardEntry) {
    val m = h.mapper
    val c = Mu.colors
    val share = m.shareOf(e) ?: return
    Row(
        Modifier.fillMaxWidth().cursorPointer(caption = "Boards with at least this much").muClickable { m.atLeast(e) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(4.dp).background(c.ink12)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).background(c.ink))
        }
        Mono("${MapperView.pct(share)} of hands", color = c.ink)
    }
}

/** Where a board stands: nothing beats it, stale, its shortest line. */
@Composable
private fun Footer(r: BoardQuery.Ranked) {
    val c = Mu.colors
    val e = r.entry
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.front) Unbeaten()
        if (e.stale) Badge("Stale")
        Spacer(Modifier.weight(1f))
        MapperView.shortest(e)?.let { Mono(if (it == 1) "1 move" else "$it moves", color = c.ink45) }
    }
}

/** The Pareto front, in a word a player reads: nothing beats it on everything asked at once. */
@Composable
private fun Unbeaten() {
    Box(Modifier.cursor(CursorMode.DEFAULT, caption = "No board beats it on everything you asked for at once")) { Badge("Unbeaten", inverted = true) }
}

// Rows: one line a board, the numbers asked for first and in ink, the rest after, then the share and the line's length.

@Composable
private fun BoardRows(h: NeueHolders, list: List<Item>) {
    val m = h.mapper
    val c = Mu.colors
    val taps = remember { TapSurface(repeats = false) }
    val state = rememberLazyListState()
    LaunchedEffect(m.selected) {
        val at = list.indexOfFirst { it is Item.Board && it.r.entry.key == m.selected }
        if (at >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == at }) state.scrollToItem(at)
    }
    val w = m.query.weights
    val heads = remember(w) { TABLE_HEADS.sortedBy { if ((w[it] ?: 0.0) != 0.0) 0 else 1 } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("#", Modifier.width(40.dp), color = c.ink45)
            Micro("Field", Modifier.weight(1f), color = c.ink45)
            heads.forEach { head ->
                val asked = (w[head] ?: 0.0) != 0.0
                Box(Modifier.width(56.dp).cursor(CursorMode.DEFAULT, caption = MapperWords.head(head))) {
                    Micro(MapperWords.short(head), color = if (asked) c.ink else c.ink45)
                }
            }
            Micro("Hands", Modifier.width(120.dp), color = c.ink45)
            Micro("Moves", Modifier.width(52.dp), color = c.ink45)
        }
        HRule()
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 24.dp)) {
            list.forEach { row ->
                when (row) {
                    is Item.Heading -> item(key = "h:${row.n}") { Box(Modifier.padding(horizontal = 24.dp)) { Heading(row, phone = false) } }
                    is Item.Board -> item(key = row.r.entry.key) {
                        val r = row.r
                        val e = r.entry
                        val selected = m.selected == e.key
                        Inverted(selected) {
                            val ci = Mu.colors
                            Row(
                                Modifier.fillMaxWidth().background(ci.paper).boardTaps(h, taps, e).padding(horizontal = 24.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(Modifier.width(40.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Numeral(row.at + 1, color = ci.ink45)
                                    if (r.front) Mono("◆", color = ci.ink)
                                }
                                Box(Modifier.weight(1f)) { FieldStrip(h, e.cards, cardWidth = 28.dp, most = 9) }
                                heads.forEach { head ->
                                    val v = e.traits[head]?.toInt()
                                    val asked = (w[head] ?: 0.0) != 0.0
                                    Mono(if (v == null) "—" else if (v == 0 && !asked) "·" else v.toString(), Modifier.width(56.dp), color = if (asked) ci.ink else ci.ink70, size = if (asked) 14.sp else 11.sp)
                                }
                                Row(Modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val share = m.shareOf(e)
                                    if (share == null) Mono("—", color = ci.ink45) else {
                                        Box(Modifier.width(48.dp).height(3.dp).background(ci.ink12)) {
                                            Box(Modifier.fillMaxHeight().fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).background(ci.ink))
                                        }
                                        Mono(MapperView.pct(share), color = ci.ink)
                                    }
                                }
                                Mono(MapperView.shortest(e)?.toString() ?: "—", Modifier.width(52.dp), color = ci.ink70)
                            }
                        }
                        HRule()
                    }
                }
            }
        }
    }
}

private val TABLE_HEADS = listOf("interruptions", "negates", "removal", "handInterruptions", "bodies", "set", "hand")

// The map: two traits across and up, every board a square at its point, the boards nothing beats filled; the boards at
// the chosen point listed under it.

@Composable
private fun BoardPlot(h: NeueHolders, ranked: List<BoardQuery.Ranked>) {
    val m = h.mapper
    val c = Mu.colors
    val heads = BoardTraits.HEADS
    val points = remember(ranked, m.plotX, m.plotY) {
        ranked.groupBy { r -> (r.entry.traits[m.plotX] ?: 0.0).toInt() to (r.entry.traits[m.plotY] ?: 0.0).toInt() }
    }
    val chosen = m.selected?.let { k -> ranked.firstOrNull { it.entry.key == k } }
    val at = chosen?.let { (it.entry.traits[m.plotX] ?: 0.0).toInt() to (it.entry.traits[m.plotY] ?: 0.0).toInt() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("Up", color = c.ink45)
            MuSelect(m.plotY, heads, MapperWords::head, { m.plotY = it }, Modifier.width(180.dp), small = true)
            Micro("Across", color = c.ink45)
            MuSelect(m.plotX, heads, MapperWords::head, { m.plotX = it }, Modifier.width(180.dp), small = true)
        }
        val maxX = max(1, points.keys.maxOfOrNull { it.first } ?: 1)
        val maxY = max(1, points.keys.maxOfOrNull { it.second } ?: 1)
        val most = points.values.maxOfOrNull { it.size } ?: 1
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxHeight().width(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Mono(maxY.toString())
                Mono("0")
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().border(1.dp, c.ink25)) {
                val ink = c.ink
                val faint = c.ink12
                Canvas(
                    Modifier.fillMaxSize()
                        .cursor(CursorMode.POINTER, caption = "Read")
                        .pointerInput(points, maxX, maxY) {
                            detectTapGestures { p ->
                                val gx = Math.round(p.x / size.width * (maxX + 1) - 0.5f)
                                val gy = Math.round((1f - p.y / size.height) * (maxY + 1) - 0.5f)
                                val near = points.keys.minByOrNull { (x, y) -> (x - gx) * (x - gx) + (y - gy) * (y - gy) } ?: return@detectTapGestures
                                points[near]?.firstOrNull()?.let { choose(h, it.entry.key) }
                            }
                        },
                ) {
                    val cw = size.width / (maxX + 1)
                    val ch = size.height / (maxY + 1)
                    for (x in 0..maxX) drawLine(faint, Offset(cw * (x + 0.5f), 0f), Offset(cw * (x + 0.5f), size.height))
                    for (y in 0..maxY) drawLine(faint, Offset(0f, size.height - ch * (y + 0.5f)), Offset(size.width, size.height - ch * (y + 0.5f)))
                    points.forEach { (xy, boards) ->
                        val side = (8.dp.toPx() + 18.dp.toPx() * sqrt(boards.size.toFloat() / most)).coerceAtMost(minOf(cw, ch) * 0.8f)
                        val center = Offset(cw * (xy.first + 0.5f), size.height - ch * (xy.second + 0.5f))
                        val tl = center - Offset(side / 2, side / 2)
                        if (boards.any { it.front }) drawRect(ink, tl, Size(side, side)) else drawRect(ink, tl, Size(side, side), style = Stroke(1.dp.toPx()))
                        if (xy == at) drawRect(ink, tl - Offset(4.dp.toPx(), 4.dp.toPx()), Size(side + 8.dp.toPx(), side + 8.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Mono("0")
            Mono(maxX.toString())
        }
        Help("A square is every board at that point, larger where there are more; filled where one is unbeaten.", color = c.ink45)
        val here = at?.let { points[it] }.orEmpty()
        if (here.isNotEmpty()) {
            val taps = remember { TapSurface(repeats = false) }
            Micro("${here.size} at ${MapperWords.head(m.plotX).lowercase()} ${at!!.first}, ${MapperWords.head(m.plotY).lowercase()} ${at.second}", color = c.ink45)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                here.take(4).forEach { r ->
                    key(r.entry.key) {
                        val sel = r.entry.key == m.selected
                        Box(Modifier.border(1.dp, if (sel) c.ink else c.ink12).boardTaps(h, taps, r.entry).padding(8.dp)) {
                            FieldStrip(h, r.entry.cards, cardWidth = 30.dp, most = 6)
                        }
                    }
                }
                if (here.size > 4) Mono("+${here.size - 4}")
            }
        }
    }
}

// ---- a board ------------------------------------------------------------------------------------------------------

/**
 * A board's field as card art, left to right: face-up monsters (a token named in a frame), set monsters, face-up Spells and
 * Traps, then the Set ones marked Set. At most [most]; the rest counted. An empty field is said in [words], or drawn as an
 * empty card where words do not fit (the overview).
 */
@Composable
private fun FieldStrip(h: NeueHolders, cards: BoardCards, cardWidth: Dp, most: Int, words: Boolean = true) {
    val tokens = cards.tokens.toMutableList()
    val shown = buildList {
        cards.monsters.forEach { add(Shown(it, token = if (it == 0) tokens.removeFirstOrNull() ?: "Token" else null)) }
        cards.setMonsters.forEach { add(Shown(it, set = true)) }
        cards.spells.forEach { add(Shown(it)) }
        cards.set.forEach { add(Shown(it, set = true)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shown.isEmpty()) {
            if (words) Small("Nothing on the field", color = Mu.colors.ink45)
            else Box(Modifier.size(cardWidth, cardWidth / CARD_RATIO).border(1.dp, Mu.colors.ink12), contentAlignment = Alignment.Center) { Mono("—") }
        }
        shown.take(most).forEachIndexed { i, s -> key(i, s.id) { BoardCard(h, s, cardWidth) } }
        if (shown.size > most) Mono("+${shown.size - most}")
    }
}

private data class Shown(val id: Int, val set: Boolean = false, val token: String? = null)

/** One card of a board: its art, a Set one marked, a token as its name in a frame; a card's rule on its menu. */
@Composable
private fun BoardCard(h: NeueHolders, s: Shown, width: Dp, rules: Boolean = false) {
    val c = Mu.colors
    val size = Modifier.size(width, width / CARD_RATIO)
    val card = if (s.id == 0) null else h.builder.index.byId(CardId(s.id))
    var origin by remember { mutableStateOf(Offset.Zero) }
    val menu = if (rules && card != null) Modifier
        .onGloballyPositioned { origin = it.positionInWindow() }
        .onContextMenu { local ->
            h.neue.menu = MenuSpec(origin + local, listOf(
                MenuEntry("Only boards with ${card.name}") { h.mapper.card(s.id, use = true) },
                MenuEntry("Only boards without it") { h.mapper.card(s.id, use = false) },
            ))
        }
        .cursor(CursorMode.DEFAULT, caption = "Right-click: with or without", emphasis = true)
    else Modifier
    Box(menu) {
        when {
            s.token != null || card == null -> Box(size.border(1.dp, c.ink25).padding(2.dp), contentAlignment = Alignment.Center) {
                Micro(s.token ?: "#${s.id}", color = c.ink70, maxLines = 3)
            }
            else -> {
                val unmarked = remember(card) { card.copy(tcgBanStatus = BanStatus.UNLIMITED, ocgBanStatus = BanStatus.UNLIMITED) }
                NeueCard(unmarked, size, format = h.builder.format, foil = h.neue.prefs.foil, dimmed = s.set)
            }
        }
        if (s.set && width >= 36.dp) {
            Box(Modifier.align(Alignment.TopStart).background(c.ink).padding(horizontal = 3.dp, vertical = 1.dp)) { Micro("Set", color = c.paper, size = 9.sp) }
        }
    }
}



/**
 * The board in full, in the order a player reads it: how much of what was asked, how often, how to make it (Play, first),
 * what it trades against the first board, its zones as art, everything it measures (zeros in one line), its starters.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardInspector(h: NeueHolders, e: BoardEntry) {
    val m = h.mapper
    val c = Mu.colors
    val name: (Int) -> String = { name(h, it) }
    val order = m.ordered()
    val at = order.indexOfFirst { it.entry.key == e.key }
    val rank = order.getOrNull(at)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            H2(if (at >= 0) "Board ${(at + 1).toString().padStart(2, '0')}" else "The board", Modifier.weight(1f))
            if (rank?.front == true) Unbeaten()
            if (e.stale) Badge("Stale")
            if (!LocalPhone.current) MicroLink("Close", { m.selected = null })
        }
        if (e.stale) Small("The deck or its effects changed since a run reached this board. Map again, or play every line again, to know whether it still lands.")
        Leads(MapperView.leads(e.traits, m.query.weights, most = 4), big = true)
        InspectorShare(h, e)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(if (e.lines.size == 1) "How to make it" else "How to make it · ${e.lines.size} lines, shortest first", color = c.ink45)
            if (e.lines.isEmpty()) Small("No line found on the deck as it is: it was found on another version of the deck.")
            e.lines.forEachIndexed { i, l ->
                key(i) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val n = MapperView.moves(l)
                            RowText("From ${l.starter.joinToString(" + ") { name(it) }}", Modifier.weight(1f), color = c.ink, maxLines = 2)
                            Mono(if (n == 1) "1 move" else "$n moves", color = c.ink45)
                            MuButton(
                                if (m.opening == l) "Opening" else "Play",
                                { replay(h, l) },
                                size = BtnSize.SM,
                                variant = if (i == 0) BtnVariant.PRIMARY else BtnVariant.SECONDARY,
                                enabled = m.opening == null,
                                reason = "A line is being opened",
                            )
                            if (i == 0) KeyCap(keyOf(DeskAction.MAPPER_REPLAY, "Enter"))
                        }
                        Help(MapperWords.line(l, name).substringAfter(": "), color = c.ink70)
                    }
                }
            }
        }
        val top = order.firstOrNull()
        if (top != null && at > 0) {
            val trade = MapperView.versus(e.traits, top.entry.traits, m.query.weights)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Micro("Against board 01", color = c.ink45)
                Small(if (trade.isEmpty()) "It measures the same: only its cards differ." else trade.joinToString(", ").replaceFirstChar { it.uppercase() } + ".", color = c.ink)
            }
        }
        Zones(h, e.cards)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Micro("Everything it measures", color = c.ink45)
            val heads = e.traits.heads()
            val some = heads.filter { (e.traits[it] ?: 0.0) != 0.0 }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                some.forEach { head -> Small(MapperView.unit(head, e.traits[head]!!.toInt()), color = c.ink) }
            }
            val none = heads - some.toSet()
            if (none.isNotEmpty()) Help("None: ${none.joinToString(", ") { MapperWords.head(it).lowercase() }}", color = c.ink45)
        }
        if (e.starters.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Micro("Starters that reach it", color = c.ink45)
                e.starters.take(STARTERS_SHOWN).forEach { s -> Small(s.joinToString(" + ") { name(it) }, color = c.ink70, maxLines = 2) }
                if (e.starters.size > STARTERS_SHOWN) Help("and ${e.starters.size - STARTERS_SHOWN} more", color = c.ink45)
            }
        }
    }
}

/** The share, large, with its range and what a press does; or why there is none yet. */
@Composable
private fun InspectorShare(h: NeueHolders, e: BoardEntry) {
    val m = h.mapper
    val c = Mu.colors
    val run = m.counted
    if (run == null) {
        Help("Map dealt hands to count how many make at least this much.", color = c.ink45)
        return
    }
    val share = run.atLeast(e.traits)
    Column(
        Modifier.fillMaxWidth().cursorPointer(caption = "Boards with at least this much").muClickable { m.atLeast(e) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuText(MapperView.pct(share.share), style = MuType.mono(LocalMuFonts.current, 26.sp), color = c.ink, maxLines = 1)
            Small("of hands make at least this", Modifier.padding(bottom = 3.dp), color = c.ink70)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.ink12)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share.share.toFloat().coerceIn(0f, 1f)).background(c.ink))
        }
        Help("${GoldfishWords.count(share.hits)} of ${GoldfishWords.count(share.of)} hands · 95 %: ${GoldfishWords.interval(share.hits, share.of)} · press: only boards with at least this much", color = c.ink45)
    }
}

private const val STARTERS_SHOWN = 8

/** The zones of a board, each a row of art with its name: the field, the Set cards, the hand, the GY, banished. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Zones(h: NeueHolders, cards: BoardCards) {
    val c = Mu.colors
    val tokens = cards.tokens.toMutableList()
    val zones = listOf(
        "Monsters" to (cards.monsters.map { Shown(it, token = if (it == 0) tokens.removeFirstOrNull() ?: "Token" else null) } + cards.setMonsters.map { Shown(it, set = true) }),
        "Spells and Traps" to (cards.spells.map { Shown(it) } + cards.set.map { Shown(it, set = true) }),
        "Hand" to cards.hand.map { Shown(it) },
        "GY" to cards.gy.map { Shown(it) },
        "Banished" to cards.banished.map { Shown(it) },
    )
    val under = cards.under.mapNotNull { s ->
        val host = s.substringBefore(':').toIntOrNull() ?: return@mapNotNull null
        val mats = s.substringAfter(':', "").split('+').mapNotNull { it.toIntOrNull() }
        if (mats.isEmpty()) null else "Under ${name(h, host)}: ${mats.joinToString(", ") { name(h, it) }}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        zones.filter { it.second.isNotEmpty() }.forEach { (words, shown) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Micro("$words · ${shown.size}", color = c.ink45)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shown.forEachIndexed { i, s -> key(i, s.id) { BoardCard(h, s, 52.dp, rules = true) } }
                }
            }
        }
        under.forEach { Help(it, color = c.ink70) }
        if (cards.lp > 0) Help("${cards.lp} LP left", color = c.ink70)
    }
}

/** [line] played again on the deck as it is and opened on the Duel page as a replay, unsaved unless kept. */
internal fun replay(h: NeueHolders, line: MapLine) {
    val m = h.mapper
    val deck = h.goldfishDeck()
    m.replay(line, deck.main, deck.extra, h.goldfishKit()) { r ->
        val game = r.game
        if (game == null) {
            h.neue.note = Note("The line could not be played: ${r.problem ?: "the hand could not be dealt"}")
        } else {
            h.duel.openGame("Gameplay Mapper: ${line.starter.joinToString(" + ") { name(h, it) }}, going ${if (line.deal.first) "first" else "second"}", game)
            h.neue.go(Page.DUEL)
            r.problem?.let { h.neue.note = Note("The line stopped short: $it") }
        }
    }
}

/** [n] with thousands marked, as the goldfish writes counts. */
private fun count(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

private fun name(h: NeueHolders, id: Int): String = if (id == 0) "Token" else h.builder.index.byId(CardId(id))?.name ?: "#$id"

// ---- the starters -------------------------------------------------------------------------------------------------

@Composable
private fun StartersTab(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val t = m.side.starters
    if (t == null) {
        EmptyState(
            "No starter table yet ${if (m.first) "going first" else "going second"}.",
            "Map the starters: every engine card alone, then every pair, each beside cards that do nothing, with the boards it reaches and how often it is opened.",
        ) {
            MuButton("Map the starters", { runStarters(h) }, variant = BtnVariant.PRIMARY, enabled = !m.busy, reason = "A run is going")
        }
        return
    }
    val rows = m.starterRows()
    val stale = t.stale(m.side.library.deck, m.side.library.library)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Small(
                    "${rows.size} starters, ${count(t.moves)} engine moves" + (if (t.stopped) " · stopped before the end" else "") + (if (stale) " · the deck changed since: map again" else ""),
                    Modifier.weight(1f), color = c.ink70, maxLines = 2,
                )
            }
            HRule()
            if (!phone) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Micro("Starter", Modifier.weight(1f), color = c.ink45)
                    Micro("Opened", Modifier.width(72.dp), color = c.ink45)
                    Micro("Boards", Modifier.width(64.dp), color = c.ink45)
                    Micro("Only together", Modifier.width(110.dp), color = c.ink45)
                }
                HRule()
            }
            val taps = remember { TapSurface(repeats = false) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.cards.joinToString(",") }) { row ->
                    StarterRowView(h, row, phone, taps)
                    HRule()
                }
            }
        }
        // The inspector stands beside the table only for a starter chosen: until then the table has the width.
        val row = m.starter?.let { s -> rows.firstOrNull { it.cards == s } }
        if (!phone && row != null) {
            VRule(color = c.ink12)
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { StarterInspector(h, row) }
            }
        }
    }
}

@Composable
private fun StarterRowView(h: NeueHolders, row: StarterTable.Row, phone: Boolean, taps: TapSurface) {
    val m = h.mapper
    val selected = m.starter == row.cards
    Inverted(selected) {
        val c = Mu.colors
        Row(
            Modifier.fillMaxWidth().background(c.paper)
                .surfaceTaps(taps, onTap = {
                    m.starter = row.cards
                    if (phone) m.inspecting = true
                }, onDoubleTap = { showBoards(h, row) })
                .cursorPointer(caption = "Read")
                .padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.cards.forEach { id -> key(id) { BoardCard(h, Shown(id), if (phone) 30.dp else 34.dp) } }
            Column(Modifier.weight(1f)) {
                RowText(row.cards.joinToString(" + ") { name(h, it) }, color = c.ink, maxLines = 2)
                if (phone) Help("Opened ${GoldfishWords.pct(row.odds)} · ${row.ends.size} boards" + if (row.together.isNotEmpty()) " · ${row.together.size} only together" else "", color = c.ink70)
                if (!row.complete) Help("Not searched to the end: there may be more", color = c.ink45)
            }
            if (!phone) {
                Mono(GoldfishWords.pct(row.odds), Modifier.width(72.dp), color = c.ink)
                Mono(row.ends.size.toString(), Modifier.width(64.dp), color = c.ink)
                Mono(if (row.cards.size > 1) row.together.size.toString() else "", Modifier.width(110.dp), color = c.ink)
            }
        }
    }
}

/** A starter's boards, only, on the Library tab. */
private fun showBoards(h: NeueHolders, row: StarterTable.Row) {
    val m = h.mapper
    m.only = Mappers.Only("Only ${row.cards.joinToString(" + ") { name(h, it) }}", row.ends.toSet())
    m.tab = MapperTab.LIBRARY
    m.inspecting = false
}

@Composable
private fun StarterInspector(h: NeueHolders, row: StarterTable.Row) {
    val m = h.mapper
    val c = Mu.colors
    val lib = m.side.library
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        H2(row.cards.joinToString(" + ") { name(h, it) }, maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.cards.forEach { id -> key(id) { BoardCard(h, Shown(id), 72.dp) } }
        }
        Small(
            "Opened in ${GoldfishWords.pct(row.odds)} of hands ${if (m.first) "going first" else "going second"}. Mapped beside " +
                (if (row.fodder.isEmpty()) "nothing else" else row.fodder.joinToString(", ") { name(h, it) } + ", which do nothing") +
                ": ${row.ends.size} boards" + (if (row.complete) "." else ", not searched to the end."),
        )
        val best = remember(row, lib, m.query) { BoardQuery.rank(row.ends.mapNotNull { lib.byKey[it] }, m.query.copy(filters = emptyList(), uses = emptyList(), avoids = emptyList(), stale = true)) }
        if (best.isNotEmpty()) {
            Micro("Its best boards, by your weights", color = c.ink45)
            best.take(4).forEach { r ->
                key(r.entry.key) {
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, c.ink12).cursorPointer(caption = "Read").muClickable { m.selected = r.entry.key; m.tab = MapperTab.LIBRARY }.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FieldStrip(h, r.entry.cards, cardWidth = 32.dp, most = 7)
                        Help(MapperWords.traits(r.entry.traits), color = c.ink70)
                    }
                }
            }
        } else {
            Small("None of its boards is in the library: it was kept only as the best of each field.", color = c.ink45)
        }
        if (row.cards.size > 1) {
            Small(
                if (row.together.isEmpty()) "Neither card adds a board the other does not make alone."
                else "${row.together.size} of its boards neither card makes alone: what each extends in the other.",
                color = c.ink,
            )
        }
        MuButton("Its boards in the library", { showBoards(h, row) }, size = BtnSize.SM, arrow = true)
    }
}

// ---- keys -----------------------------------------------------------------------------------------------------------

/** Gameplay Mapper's keys, from its table, the palette and the menus alike. */
internal fun runMapper(h: NeueHolders, action: DeskAction) {
    val m = h.mapper
    when (action) {
        DeskAction.GO_MAPPER -> h.neue.go(Page.MAPPER)
        DeskAction.MAPPER_LIBRARY -> { h.neue.go(Page.MAPPER); m.tab = MapperTab.LIBRARY }
        DeskAction.MAPPER_STARTERS -> { h.neue.go(Page.MAPPER); m.tab = MapperTab.STARTERS }
        DeskAction.MAPPER_SIDE -> side(h, !m.first)
        DeskAction.MAPPER_PREV -> m.step(-1)
        DeskAction.MAPPER_NEXT -> m.step(1)
        DeskAction.MAPPER_REPLAY -> when (m.tab) {
            MapperTab.LIBRARY -> m.selected?.let { m.side.library.byKey[it] }?.lines?.firstOrNull()?.let { replay(h, it) }
            MapperTab.STARTERS -> m.starter?.let { s -> m.starterRows().firstOrNull { it.cards == s } }?.let { showBoards(h, it) }
        }
        DeskAction.MAPPER_RUN -> { h.neue.go(Page.MAPPER); runHands(h) }
        DeskAction.MAPPER_RUN_STARTERS -> { h.neue.go(Page.MAPPER); runStarters(h) }
        DeskAction.MAPPER_STOP -> m.stop()
        DeskAction.MAPPER_DENSER, DeskAction.MAPPER_LOOSER -> if (m.tab == MapperTab.LIBRARY) {
            m.stepDensity(denser = action == DeskAction.MAPPER_DENSER, boards = m.ordered().size, phone = h.neue.phone)
        }
        DeskAction.MAPPER_ORDER -> MapperView.Order.entries.let { all -> m.order = all[(m.order.ordinal + 1) % all.size] }
        DeskAction.MAPPER_TUNE -> { m.tab = MapperTab.LIBRARY; m.tuning = !m.tuning }
        else -> Unit
    }
}

/** Esc and Back on page 10: the phone's inspector, then a starter's boards, then the board chosen, then the weights. */
internal fun dismissMapper(h: NeueHolders): Boolean {
    if (h.neue.page != Page.MAPPER || h.neue.hasTop || h.overlays.isOpen || h.textFocus.any) return false
    if (!h.mapperStarted) return false
    val m = h.mapper
    when {
        m.inspecting -> m.inspecting = false
        m.only != null -> m.only = null
        m.selected != null && m.tab == MapperTab.LIBRARY -> m.selected = null
        m.starter != null && m.tab == MapperTab.STARTERS -> m.starter = null
        m.tuning && m.tab == MapperTab.LIBRARY -> m.tuning = false
        else -> return false
    }
    return true
}
