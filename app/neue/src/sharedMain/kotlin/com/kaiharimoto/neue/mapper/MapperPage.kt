package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.kaiharimoto.mastertool.core.duel.mapper.MapperReport
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
import com.kaiharimoto.neue.kit.Body
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
import com.kaiharimoto.neue.theme.Mu
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 10 Gameplay Mapper (Phase M step M1, `docs/phases/M.md` §6; kai: "it would find optimized endboards from a library that
 * it found during runs … a range of boards based on what the user wants like a filter system with adjustable weights"):
 * the open deck's board library going first or second, chosen by the person's filters and weights — nothing ranks a board
 * in advance — with the Pareto front marked and each board's share of dealt hands; the inspector reads a board in full and
 * plays any of its lines on the Duel page. The Starters tab is the starter table: every engine card and pair, what it
 * reaches, how often it is opened.
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
    Column(Modifier.fillMaxSize()) {
        PageHeader(numeral = 10, title = "Gameplay Mapper", subtitle = subtitle(h)) { HeaderActions(h, phone) }
        RunBar(h, phone)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                h.builder.deckId == null -> EmptyState("Nothing to map yet.", "Save the deck first: its boards are kept with it.") {
                    MuButton("Save the deck", { h.builder.save { h.decksReload++ } }, variant = BtnVariant.PRIMARY, arrow = true)
                }
                !m.loaded -> Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Breathe()
                    Small("Reading the deck's boards")
                }
                m.tab == MapperTab.LIBRARY -> LibraryTab(h, phone)
                else -> StartersTab(h, phone)
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

/** How many hands, the seed, Map hands and Map the starters; a run's progress and Stop while one runs; what it said. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunBar(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val pad = if (phone) 16.dp else 32.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val running = m.running
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
        } else if (h.builder.deckId != null) {
            val default = if (phone) Mappers.PHONE_HANDS else Mappers.DESK_HANDS
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Micro("Hands", color = c.ink45)
                    MuInput(m.handsText ?: default.toString(), { t -> m.handsText = t.filter(Char::isDigit).take(5) }, Modifier.width(72.dp), mono = true, dense = true)
                    Micro("Seed", color = c.ink45)
                    MuInput(m.seedText, { t -> m.seedText = t.filter { it.isDigit() || it == '-' }.take(18) }, Modifier.width(if (phone) 88.dp else 120.dp), mono = true, dense = true)
                    MuButton("Re-roll", { m.reroll() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val why = remember(h.builder.deck, h.effects.loaded, h.effects.revision, m.loaded, m.first, m.side, m.running) { refusal(h) }
                    MuButton("Map hands", { runHands(h) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = why == null, reason = why)
                    KeyCap(keyOf(DeskAction.MAPPER_RUN, "R"))
                    MuButton("Map the starters", { runStarters(h) }, size = BtnSize.SM, enabled = why == null, reason = why)
                    KeyCap(keyOf(DeskAction.MAPPER_RUN_STARTERS, "Shift R"))
                }
            }
        }
        m.said?.let { said ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small(said, Modifier.weight(1f, fill = false), maxLines = 3)
                MicroLink("Dismiss", { m.said = null })
            }
        }
    }
    HRule()
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

// ---- the library --------------------------------------------------------------------------------------------------

@Composable
private fun LibraryTab(h: NeueHolders, phone: Boolean) {
    val m = h.mapper
    val c = Mu.colors
    val lib = m.side.library
    if (m.side.unreadable.isNotEmpty()) {
        EmptyState("This deck's boards could not be read.", "${m.side.unreadable.joinToString()} was written by a newer version: update the app to read it. Nothing is written over it.")
        return
    }
    if (lib.boards.isEmpty()) {
        EmptyState(
            "No boards yet ${if (m.first) "going first" else "going second"}.",
            "Map the starters to find what each engine card makes alone and with a partner, or map dealt hands to count how often the deck reaches each kind of board. Only cards with written effects play: the Effects app on the World page writes them.",
        )
        return
    }
    val ranked = m.ranked()
    if (phone) {
        Column(Modifier.fillMaxSize()) {
            PhoneQueryRow(h)
            BoardList(h, ranked, phone = true, Modifier.weight(1f))
        }
        return
    }
    Row(Modifier.fillMaxSize()) {
        QueryPanel(h, Modifier.width(280.dp).fillMaxHeight())
        VRule(color = c.ink12)
        BoardList(h, ranked, phone = false, Modifier.weight(1f))
        VRule(color = c.ink12)
        Box(Modifier.width(380.dp).fillMaxHeight()) {
            val e = m.selected?.let { lib.byKey[it] }
            if (e == null) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Micro("The inspector", color = c.ink45)
                    Small("Choose a board to read it in full: its cards, every trait, its share of hands, the lines that reach it and the starters that open them.")
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { BoardInspector(h, e) }
            }
        }
    }
}

/** Presets, weights, bounds, cards and stale boards: what the library is chosen by. */
@Composable
private fun QueryPanel(h: NeueHolders, modifier: Modifier) {
    val m = h.mapper
    val c = Mu.colors
    val q = m.query
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PresetPicker(h)
        if (q.by == BoardPreset.AI && q.why.isNotBlank()) Small("${h.ai.name}: ${q.why}", color = c.ink70)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("Weights", color = c.ink45)
            Help("Right: more is better. Left: less is. Nothing ranks a board until you say what you want.", color = c.ink45)
            BoardTraits.HEADS.forEach { head -> key(head) { WeightRow(h, head) } }
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

/** A phone's query: the preset and a button for the rest. */
@Composable
private fun PhoneQueryRow(h: NeueHolders) {
    val m = h.mapper
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Small(MapperReport.query(m.query) { name(h, it) }, Modifier.weight(1f), maxLines = 2)
        MuButton("Weights", { open = true }, size = BtnSize.SM)
    }
    if (open) MuDialog("Weights and filters", { open = false }) { QueryPanel(h, Modifier.fillMaxWidth()) }
}

/** The library as the look draws it, with [only]'s strip over it. */
@Composable
private fun BoardList(h: NeueHolders, ranked: List<BoardQuery.Ranked>, phone: Boolean, modifier: Modifier) {
    val m = h.mapper
    val c = Mu.colors
    Column(modifier) {
        val only = m.only
        Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val front = ranked.count { it.front }
            Small("${GoldfishWords.count(ranked.size)} boards pass · $front on the front", Modifier.weight(1f), color = c.ink70, maxLines = 1)
            if (only != null) Tag(only.words, true, { m.only = null }, caption = "Show every board")
            if (!phone) Segmented(m.look, MapperLook.entries, { it.words }, { m.look = it }, small = true)
        }
        HRule()
        if (ranked.isEmpty()) {
            EmptyState("No board passes.", "Loosen a bound or take a card off, or show the stale boards.")
            return
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (if (phone) MapperLook.GALLERY else m.look) {
                MapperLook.GALLERY -> Gallery(h, ranked, phone)
                MapperLook.TABLE -> BoardTable(h, ranked)
                MapperLook.PLOT -> BoardPlot(h, ranked)
            }
        }
    }
}

/** A board chosen: read in the inspector (a phone's opens over the list); a second tap plays its cheapest line. */
private fun Modifier.boardTaps(h: NeueHolders, taps: TapSurface, e: BoardEntry): Modifier = this
    .surfaceTaps(taps, onTap = { choose(h, e.key) }, onDoubleTap = { choose(h, e.key); e.lines.firstOrNull()?.let { replay(h, it) } })
    .cursorPointer(caption = "Read")

private fun choose(h: NeueHolders, key: String) {
    val m = h.mapper
    m.selected = key
    if (h.neue.phone) m.inspecting = true
}

// Look A: the gallery — each board as its field's card art, ranked, the front marked.

@Composable
private fun Gallery(h: NeueHolders, ranked: List<BoardQuery.Ranked>, phone: Boolean) {
    val m = h.mapper
    val taps = remember { TapSurface(repeats = false) }
    val state = rememberLazyGridState()
    LaunchedEffect(m.selected) {
        val at = ranked.indexOfFirst { it.entry.key == m.selected }
        if (at >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == at }) state.scrollToItem(at)
    }
    LazyVerticalGrid(
        GridCells.Adaptive(if (phone) 300.dp else 320.dp),
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(if (phone) 12.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ranked.size, key = { ranked[it].entry.key }) { i ->
            val r = ranked[i]
            BoardTile(h, r, i, taps)
        }
    }
}

@Composable
private fun BoardTile(h: NeueHolders, r: BoardQuery.Ranked, i: Int, taps: TapSurface) {
    val m = h.mapper
    val c = Mu.colors
    val e = r.entry
    val selected = m.selected == e.key
    Inverted(selected) {
        val ci = Mu.colors
        Column(
            Modifier.fillMaxWidth().background(ci.paper).border(1.dp, if (selected) ci.ink else c.ink12).boardTaps(h, taps, e).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Numeral(i + 1, color = ci.ink)
                if (r.front) Badge("Front", inverted = true)
                if (e.stale) Badge("Stale")
                Spacer(Modifier.weight(1f))
                Mono(score(r), color = ci.ink45)
            }
            FieldStrip(h, e.cards, cardWidth = 42.dp, most = 7)
            Small(MapperWords.traits(e.traits), color = ci.ink70, maxLines = 2)
            ShareLine(h, e, compact = true)
        }
    }
}

private fun score(r: BoardQuery.Ranked): String = if (r.parts.isEmpty()) "" else "%.2f".format(r.score)

// Look B: the table — one row a board, its traits in columns, a strip of its field.

private val TABLE_HEADS = listOf("interruptions", "negates", "removal", "handInterruptions", "bodies", "set", "hand")

@Composable
private fun BoardTable(h: NeueHolders, ranked: List<BoardQuery.Ranked>) {
    val m = h.mapper
    val c = Mu.colors
    val taps = remember { TapSurface(repeats = false) }
    val state = rememberLazyListState()
    LaunchedEffect(m.selected) {
        val at = ranked.indexOfFirst { it.entry.key == m.selected }
        if (at >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == at }) state.scrollToItem(at)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("#", Modifier.width(36.dp), color = c.ink45)
            Micro("Field", Modifier.weight(1f), color = c.ink45)
            TABLE_HEADS.forEach { head ->
                val w = m.query.weights[head] ?: 0.0
                Micro(MapperWords.short(head), Modifier.width(44.dp), color = if (w != 0.0) c.ink else c.ink45)
            }
            Micro("Hands", Modifier.width(72.dp), color = c.ink45)
        }
        HRule()
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(ranked.size, key = { ranked[it].entry.key }) { i ->
                val r = ranked[i]
                val e = r.entry
                val selected = m.selected == e.key
                Inverted(selected) {
                    val ci = Mu.colors
                    Row(
                        Modifier.fillMaxWidth().background(ci.paper).boardTaps(h, taps, e).padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.width(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            Numeral(i + 1, color = ci.ink)
                            if (r.front) Mono("◆", color = ci.ink)
                        }
                        Box(Modifier.weight(1f)) { FieldStrip(h, e.cards, cardWidth = 28.dp, most = 9) }
                        TABLE_HEADS.forEach { head -> Mono(e.traits[head]?.toInt()?.toString() ?: "—", Modifier.width(44.dp), color = ci.ink) }
                        Mono(m.counted?.atLeast(e.traits)?.let { GoldfishWords.pct(it.share) } ?: "—", Modifier.width(72.dp), color = ci.ink70)
                    }
                }
                HRule()
            }
        }
    }
}

// Look C: the map — two traits across and up, every board a square at its point, the front filled; the boards at the
// chosen point listed under it.

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
        Help("A square is every board at that point, larger where there are more; filled where one is on the Pareto front.", color = c.ink45)
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
 * Traps, then the Set ones marked Set. At most [most]; the rest counted.
 */
@Composable
private fun FieldStrip(h: NeueHolders, cards: BoardCards, cardWidth: Dp, most: Int) {
    val tokens = cards.tokens.toMutableList()
    val shown = buildList {
        cards.monsters.forEach { add(Shown(it, token = if (it == 0) tokens.removeFirstOrNull() ?: "Token" else null)) }
        cards.setMonsters.forEach { add(Shown(it, set = true)) }
        cards.spells.forEach { add(Shown(it)) }
        cards.set.forEach { add(Shown(it, set = true)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shown.isEmpty()) Small("Nothing on the field", color = Mu.colors.ink45)
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

/** "41.0 % of 500 hands make at least this much (95 %: 36.8–45.3 %)", a press making it a filter; else why there is none. */
@Composable
private fun ShareLine(h: NeueHolders, e: BoardEntry, compact: Boolean = false) {
    val m = h.mapper
    val c = Mu.colors
    val run = m.counted
    if (run == null) {
        if (!compact) Help("Map dealt hands to count how many make at least this much.", color = c.ink45)
        return
    }
    val share = run.atLeast(e.traits)
    val words = if (compact) "${GoldfishWords.pct(share.share)} of hands make at least this" else "At least this much: ${MapperWords.share(share)}"
    Box(Modifier.cursorPointer(caption = "Boards with at least this much").muClickable { m.atLeast(e) }) {
        Small(words, color = c.ink, maxLines = 2)
    }
}

/** The board in full: its zones as art, every trait, its share, its lines (each plays on the Duel page), its starters. */
@Composable
private fun BoardInspector(h: NeueHolders, e: BoardEntry) {
    val m = h.mapper
    val c = Mu.colors
    val name: (Int) -> String = { name(h, it) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            H2("The board", Modifier.weight(1f))
            val rank = m.ranked().firstOrNull { it.entry.key == e.key }
            if (rank?.front == true) Badge("Front", inverted = true)
            if (e.stale) Badge("Stale")
        }
        if (e.stale) Small("The deck or its effects changed since a run reached this board. Map again, or play every line again, to know whether it still lands.")
        Zones(h, e.cards)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Micro("What it measures", color = c.ink45)
            e.traits.heads().forEach { head ->
                Row {
                    RowText(MapperWords.head(head), Modifier.weight(1f), color = c.ink70)
                    Mono(e.traits[head]?.toInt()?.toString() ?: "—", color = c.ink)
                }
            }
        }
        ShareLine(h, e)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(if (e.lines.size == 1) "The line to it" else "The cheapest lines to it", color = c.ink45)
            if (e.lines.isEmpty()) Small("No line found on the deck as it is: it was found on another version of the deck.")
            e.lines.forEachIndexed { i, l ->
                key(i) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Small("${l.steps.size} moves from ${l.starter.joinToString(" + ") { name(it) }}", Modifier.weight(1f), color = c.ink)
                            MuButton(
                                if (m.opening == l) "Opening" else "Play",
                                { replay(h, l) },
                                size = BtnSize.SM,
                                enabled = m.opening == null,
                                reason = "A line is being opened",
                            )
                            if (i == 0) KeyCap(keyOf(DeskAction.MAPPER_REPLAY, "Enter"))
                        }
                        Help(MapperWords.line(l, name), color = c.ink70)
                    }
                }
            }
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
        )
        return
    }
    val rows = m.starterRows()
    val stale = t.stale(m.side.library.deck, m.side.library.library)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Small(
                    "${rows.size} starters, ${GoldfishWords.count(t.moves)} engine moves" + (if (t.stopped) " · stopped before the end" else "") + (if (stale) " · the deck changed since: map again" else ""),
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
        if (!phone) {
            VRule(color = c.ink12)
            Box(Modifier.width(380.dp).fillMaxHeight()) {
                val row = m.starter?.let { s -> rows.firstOrNull { it.cards == s } }
                if (row == null) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro("The inspector", color = c.ink45)
                        Small("Choose a starter to read what it makes: its best boards, and for a pair the ones neither card makes alone.")
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) { StarterInspector(h, row) }
                }
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
        else -> Unit
    }
}

/** Esc and Back on page 10: the phone's inspector, then a starter's boards, then the board chosen. */
internal fun dismissMapper(h: NeueHolders): Boolean {
    if (h.neue.page != Page.MAPPER || h.neue.hasTop || h.overlays.isOpen || h.textFocus.any) return false
    if (!h.mapperStarted) return false
    val m = h.mapper
    when {
        m.inspecting -> m.inspecting = false
        m.only != null -> m.only = null
        m.selected != null && m.tab == MapperTab.LIBRARY -> m.selected = null
        m.starter != null && m.tab == MapperTab.STARTERS -> m.starter = null
        else -> return false
    }
    return true
}
