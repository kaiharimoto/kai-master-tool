package com.kaiharimoto.neue.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.library.DeckCovers
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.art.LocalArt
import com.kaiharimoto.neue.art.LocalCustomArt
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.web.Webs
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Siding (kai, 1.0.35: "siding patterns, can use other decks in the deck web as
 * a matchup and also write notes… patterns would differ for every matchup, going
 * first and second"). One of the web's decks — yours, usually — sided against
 * each of the others:
 *
 * - **Matchups** down the left, each with its two marks (`■` sided with a
 *   reason, `□` sided, `·` not yet), going first then second.
 * - **The plan**: a note on the matchup, and a column for each turn — the one
 *   being sided is inverted; a card in the deck below goes into it, out from
 *   the main and extra decks or in from the side, and a card in its lists
 *   comes back out with a click. Each turn has its own why.
 * - **How they side against you**, beside it (kai: "more visually intuitive"):
 *   the opponent's own plan against this deck, for the turn that answers the
 *   one being sided — you going first is them going second — as the cards
 *   they bring in, large, and the cards they take out, small and struck; with
 *   their deck by its groups under it.
 *
 * Every change is written as it is made ([Webs.saveSiding]), under the deck's
 * own `siding` key ([SidingCodec]), so the plan travels in its `.ydkx` and its
 * web's `.ydkw`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SidingEditor(
    webs: Webs,
    /** The web [me] is sided in, or null for a deck sided on its own (1.0.42): its opponents are its matchups. */
    web: DeckWeb?,
    decks: List<StoredDeck>,
    me: StoredDeck,
    state: DeckBuilderState,
    neue: NeueState,
    /** Back to the web on Format; null where there is no web to go back to. */
    onBack: (() -> Unit)?,
    reload: Int = 0,
) {
    val c = Mu.colors
    val phone = LocalPhone.current
    val myDeck = webs.deckOf(me, state)
    var siding by remember(me.entry.id) { mutableStateOf(webs.sidingOf(me, state)) }
    var turn by remember { mutableStateOf(Turn.FIRST) }
    val opponents = if (web == null) emptyList() else decks.filter { it.entry.id != me.entry.id }
    // Matchups against no deck of this web: a legacy plan, one the deck brought from elsewhere,
    // or — for a deck sided on its own — every one it has, made here by name (1.0.42).
    val loose = siding.matchups.filter { m -> opponents.none { siding.against(it.entry.id, it.entry.name) == m } }
    // Every deck of the library: a matchup made by name may be linked to one, whose list and plan it then shows.
    var library by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(me.entry.id, reload, webs.revision) { library = webs.libraryDecks() }
    fun linked(m: Matchup?): StoredDeck? = m?.deckId?.let { id -> decks.firstOrNull { it.entry.id == id } ?: library.firstOrNull { it.entry.id == id } }
    var selected by remember(me.entry.id) {
        mutableStateOf(webs.sidingAgainst?.takeIf { a -> opponents.any { it.entry.id == a } || loose.any { "m:${it.id}" == a } } ?: opponents.firstOrNull()?.entry?.id ?: loose.firstOrNull()?.let { "m:${it.id}" })
    }
    val opponent = opponents.firstOrNull { it.entry.id == selected }
    val matchup: Matchup? = opponent?.let { siding.against(it.entry.id, it.entry.name) } ?: siding.byId(selected?.removePrefix("m:"))
    val name = opponent?.entry?.name ?: matchup?.name ?: ""
    // The deck they play: the web's, or the one a matchup made here was linked to.
    val theirDeck = opponent ?: linked(matchup)

    fun save(next: DeckSiding) {
        siding = next
        webs.saveSiding(me.entry.id, next, state)
    }

    /** [change] to this matchup, made (and linked to the web deck) on its first edit. */
    fun edit(change: (Matchup) -> Matchup) {
        val base = matchup ?: Matchup("m-${Random.nextLong().toULong().toString(36)}", name)
        val linkedToWeb = if (opponent != null) base.copy(deckId = opponent.entry.id, name = opponent.entry.name) else base
        save(siding.put(change(linkedToWeb)))
    }

    fun plan(t: Turn) = matchup?.plan(t) ?: SidePlan()
    fun setPlan(t: Turn, p: SidePlan) = edit { it.withPlan(t, p) }

    // An opponent made or changed here (1.0.42): a name and three cards, its decklist linked later.
    var creating by remember { mutableStateOf(webs.newOpponent != null) }
    var changing by remember { mutableStateOf<Matchup?>(null) }
    fun remove(m: Matchup) {
        val before = siding
        save(siding.remove(m.id))
        selected = opponents.firstOrNull()?.entry?.id ?: siding.matchups.firstOrNull()?.let { "m:${it.id}" }
        neue.note = com.kaiharimoto.neue.Note("Removed “${m.name}”", action = "Undo") {
            save(before)
            selected = "m:${m.id}"
        }
    }
    /** The library's decks to link a matchup made here to (1.0.49: a button, no longer inside a ⋯ menu). */
    fun linkMenu(m: Matchup, at: Offset) {
        val choices = library.filter { it.entry.id != me.entry.id }.sortedBy { it.entry.name.lowercase() }
        neue.menu = MenuSpec(at, if (choices.isEmpty()) {
            listOf(MenuEntry("No other decks in the library", enabled = false) {})
        } else {
            choices.map { d -> MenuEntry(d.entry.name, hint = if (d.entry.id == m.deckId) "✓" else null) { save(siding.put(m.copy(deckId = d.entry.id))) } }
        })
    }
    val art = neue.prefs.sidingView != NeuePreferences.SIDING_LIST

    Column(Modifier.fillMaxSize()) {
        val arts = LocalArt.current
        val custom = LocalCustomArt.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var making by remember { mutableStateOf(false) }
        // The guide reads every deck a matchup names: the web's, and any linked from the library.
        val named = decks + library.filter { lib -> decks.none { it.entry.id == lib.entry.id } && siding.matchups.any { it.deckId == lib.entry.id } }
        SidingBar(webs, web, decks, me, onBack, neue, making) {
            making = true
            scope.launch {
                try {
                    GuideExport.deliver(webs, web, named, me, state, neue, arts, custom)
                } finally {
                    making = false
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
        creating.takeIf { it }?.let {
            OpponentDialog(null, state, { creating = false; webs.newOpponent = null }, typed = webs.newOpponent.orEmpty()) { n, covers ->
                webs.newOpponent = null
                val made = Matchup("m-${Random.nextLong().toULong().toString(36)}", n, covers = covers)
                save(siding.put(made))
                selected = "m:${made.id}"
                creating = false
            }
        }
        changing?.let { m ->
            OpponentDialog(m, state, { changing = null }) { n, covers ->
                save(siding.put(m.copy(name = n, covers = covers)))
                changing = null
            }
        }
        if (opponents.isEmpty() && loose.isEmpty()) {
            EmptyState(
                "No matchups yet.",
                if (web == null) {
                    "Add the decks you expect to face: a name and three cards to know each by. Side against it here, and link its decklist later if you get one."
                } else {
                    "Add the decks you expect to face to ${web.name}, or add one here by its name and three cards."
                },
            ) { MuButton("New opponent", { creating = true }, variant = BtnVariant.PRIMARY, icon = com.kaiharimoto.neue.kit.Icons.Plus) }
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1100.dp
            val narrow = maxWidth < 700.dp
            val theirs: @Composable (Modifier) -> Unit = { modifier ->
                TheirPlan(webs, theirDeck, me, turn, state, neue, web != null, art, modifier) { webs.side(it, me.entry.id) }
            }
            val showExtra = neue.prefs.sidingExtra
            val onShowExtra: (Boolean) -> Unit = { v -> neue.update { it.copy(sidingExtra = v) } }
            // Who, the note and the two turns' plans: above the deck to side from.
            val plans: @Composable () -> Unit = {
                if (narrow) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        opponents.forEach { o ->
                            val m = siding.against(o.entry.id, o.entry.name)
                            Tag(o.entry.name, selected == o.entry.id, { selected = o.entry.id }, count = marks(m), caption = "Side")
                        }
                        loose.forEach { m -> Tag(m.name, selected == "m:${m.id}", { selected = "m:${m.id}" }, count = marks(m), caption = "Side") }
                        Tag("+ Opponent", false, { creating = true }, caption = "New")
                    }
                }
                val own = matchup?.takeIf { opponent == null }
                OpponentHeader(
                    theirDeck,
                    matchup?.covers.orEmpty(),
                    name,
                    state,
                    neue,
                    actions = own?.let { m ->
                        OpponentActions(
                            decklist = linked(m)?.entry?.name,
                            onEdit = { changing = m },
                            onLink = { at -> linkMenu(m, at) },
                            onUnlink = { save(siding.put(m.copy(deckId = null))) },
                            onRemove = { remove(m) },
                        )
                    },
                )
                PlanNote(matchup?.note.orEmpty(), { note -> edit { it.copy(note = note) } }, "The matchup: how it plays, what matters, what to hold.")
                if (narrow) {
                    Segmented(turn, Turn.entries, { it.title }, { turn = it })
                    TurnColumn(turn, plan(turn), true, state, myDeck, art, { setPlan(turn, it) }, {})
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Turn.entries.forEach { t ->
                            TurnColumn(t, plan(t), t == turn, state, myDeck, art, { setPlan(t, it) }, { turn = t }, Modifier.weight(1f))
                        }
                    }
                }
            }
            val body: @Composable (Modifier) -> Unit = { modifier ->
                if (narrow) {
                    // A phone: one page that scrolls, the deck's sections stacked under the plans.
                    val scroll = rememberScrollState()
                    Box(modifier) {
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            plans()
                            SidingBoard(myDeck, plan(turn), turn, state, showExtra, onShowExtra) { setPlan(turn, it) }
                            theirs(Modifier.fillMaxWidth())
                        }
                        ScrollbarFor(scroll)
                    }
                } else {
                    // The desk (1.0.51, kai: "have the main deck all fit in the screen without needing to
                    // scroll"): the plans take what they need, up to half the height, and scroll on their
                    // own past it; the deck to side from fills the rest, fitted whole, the Side Deck beside it.
                    BoxWithConstraints(modifier) {
                        val limit = maxHeight * 0.5f
                        Column(Modifier.fillMaxSize()) {
                            val scroll = rememberScrollState()
                            // The plans' own height, measured, so the board gets every pixel they leave (the
                            // scrollbar alone would stretch the box to its cap).
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            var natural by remember { mutableStateOf(limit) }
                            Box(Modifier.fillMaxWidth().height(minOf(natural, limit))) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(scroll)
                                        .onSizeChanged { natural = with(density) { it.height.toDp() } }
                                        .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    plans()
                                    if (!wide) theirs(Modifier.fillMaxWidth())
                                }
                                ScrollbarFor(scroll)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
                            SidingBoard(
                                myDeck, plan(turn), turn, state, showExtra, onShowExtra,
                                Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                                fit = true,
                            ) { setPlan(turn, it) }
                        }
                    }
                }
            }
            if (narrow) {
                body(Modifier.fillMaxSize())
            } else {
                Row(Modifier.fillMaxSize()) {
                    MatchupList(opponents, loose, siding, selected, web != null, { selected = it }, { creating = true }, Modifier.width(224.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
                    body(Modifier.weight(1f).fillMaxHeight())
                    if (wide) {
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
                        val scroll = rememberScrollState()
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            Column(Modifier.fillMaxSize().verticalScroll(scroll)) { theirs(Modifier.fillMaxWidth()) }
                            ScrollbarFor(scroll)
                        }
                    }
                }
            }
        }
    }
}

/** A matchup's two marks, going first then second. */
private fun marks(m: Matchup?): String = if (m == null) "· ·" else "${m.mark(Turn.FIRST)} ${m.mark(Turn.SECOND)}"

/** Back to the web, and which deck is being sided — or, for a deck sided on its own, its name. */
@Composable
private fun SidingBar(webs: Webs, web: DeckWeb?, decks: List<StoredDeck>, me: StoredDeck, onBack: (() -> Unit)?, neue: NeueState, making: Boolean, onGuide: () -> Unit) {
    val c = Mu.colors
    val phone = LocalPhone.current
    var asAt by remember { mutableStateOf(Offset.Zero) }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (web != null && onBack != null) MuButton("← ${web.name.ifBlank { "The web" }}", onBack, variant = BtnVariant.GHOST, size = BtnSize.SM)
        Micro("Siding as", color = c.ink70)
        if (web != null) {
            val mine = web.entry(me.entry.id)?.mine == true
            MuButton(
                "${if (mine) "★ " else ""}${me.entry.name} ▾",
                {
                    // Yours first: the decks you side as.
                    val ordered = decks.sortedByDescending { web.entry(it.entry.id)?.mine == true }
                    neue.menu = MenuSpec(asAt, ordered.map { d ->
                        val star = web.entry(d.entry.id)?.mine == true
                        MenuEntry("${if (star) "★ " else ""}${d.entry.name}", hint = if (d.entry.id == me.entry.id) "✓" else null) { webs.side(d.entry.id) }
                    })
                },
                Modifier.onGloballyPositioned { asAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) },
                variant = BtnVariant.SECONDARY,
                size = BtnSize.SM,
            )
        } else {
            // A deck on its own (1.0.42): the one in the builder, by name.
            MuText(me.entry.name, style = MuType.body(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1)
        }
        Segmented(
            neue.prefs.sidingView,
            listOf(NeuePreferences.SIDING_ART, NeuePreferences.SIDING_LIST),
            { if (it == NeuePreferences.SIDING_LIST) "List" else "Art" },
            { v -> neue.update { it.copy(sidingView = v) } },
            small = true,
        )
        GuideButton(making, onGuide)
        if (!phone) {
            Small(
                if (LocalTouchFirst.current) "Tap a card in the deck to side it; tap it again to take it back."
                else "Pick a turn, then click a card in the deck below to side it out or in. Click it again to take it back.",
                color = c.ink45,
            )
        }
    }
}

/** The guide as a PDF: every matchup, both turns and their plans, to print or send. */
@Composable
internal fun GuideButton(making: Boolean, onGuide: () -> Unit) {
    MuButton(
        if (making) "Making the guide…" else "Siding guide · PDF",
        onGuide,
        variant = BtnVariant.PRIMARY,
        size = BtnSize.SM,
        icon = com.kaiharimoto.neue.kit.Icons.Export,
        enabled = !making,
        reason = "The guide is being made",
    )
}

/** Every opponent: the web's other decks, then the matchups made by name (1.0.42), and a way to make one. */
@Composable
private fun MatchupList(
    opponents: List<StoredDeck>,
    loose: List<Matchup>,
    siding: DeckSiding,
    selected: String?,
    inWeb: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier,
) {
    val c = Mu.colors
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Micro("Matchups", Modifier.padding(start = 8.dp, bottom = 8.dp), color = c.ink70)
            opponents.forEach { o ->
                MatchupRow(o.entry.name, marks(siding.against(o.entry.id, o.entry.name)), selected == o.entry.id) { onSelect(o.entry.id) }
            }
            if (loose.isNotEmpty()) {
                if (inWeb) Micro("Not in this web", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp), color = c.ink45)
                loose.forEach { m -> MatchupRow(m.name, marks(m), selected == "m:${m.id}") { onSelect("m:${m.id}") } }
            }
            MuButton("New opponent", onNew, Modifier.padding(top = 8.dp), variant = BtnVariant.GHOST, size = BtnSize.SM, icon = com.kaiharimoto.neue.kit.Icons.Plus)
            Help("The marks: going first, then second. ■ sided with a reason, □ sided, · not yet.", Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp))
        }
        ScrollbarFor(scroll)
    }
}

@Composable
private fun MatchupRow(name: String, marks: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (LocalTouchFirst.current) 44.dp else 36.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .hoverable(source)
            .cursorPointer(caption = if (on) null else "Side")
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MuText(name, Modifier.weight(1f), style = MuType.body(LocalMuFonts.current).copy(fontSize = 14.sp), color = if (on) c.paper else c.ink, maxLines = 1)
        Mono(marks, color = if (on) c.paper else c.ink70)
    }
}

/** What a matchup made here can do (1.0.49, kai: buttons, not a ⋯ menu — "we have an abundance of UI space"). */
private class OpponentActions(
    val decklist: String?,
    val onEdit: () -> Unit,
    val onLink: (Offset) -> Unit,
    val onUnlink: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * Who this is against: their faces — their deck's, else the three cards the matchup was
 * made with — and their name; for a matchup made here (1.0.42), its actions as buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpponentHeader(opponent: StoredDeck?, covers: List<CardId>, name: String, state: DeckBuilderState, neue: NeueState, actions: OpponentActions?) {
    val c = Mu.colors
    var at by remember { mutableStateOf(Offset.Zero) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val faces = if (opponent != null) remember(opponent.entry.deck, state.index) { faces(opponent, neue, state) } else covers.mapNotNull(state.index::byId)
            if (faces.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    faces.forEach { NeueCard(it, Modifier.size(34.dp, 50.dp), foil = "off") }
                }
            }
            Column(Modifier.weight(1f, fill = false)) {
                MuText("vs $name", style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 2)
                if (actions != null) Small(actions.decklist?.let { "Decklist: $it" } ?: "No decklist linked", color = c.ink45)
            }
        }
        if (actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Name and cards", actions.onEdit, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = com.kaiharimoto.neue.kit.Icons.Pencil)
                MuButton(
                    if (actions.decklist == null) "Link a decklist ▾" else "Change decklist ▾",
                    { actions.onLink(at) },
                    Modifier.onGloballyPositioned { at = it.boundsInWindow().bottomLeft + Offset(0f, 4f) },
                    variant = BtnVariant.SECONDARY,
                    size = BtnSize.SM,
                )
                if (actions.decklist != null) MuButton("Unlink", actions.onUnlink, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton("Remove", actions.onRemove, variant = BtnVariant.GHOST, size = BtnSize.SM, icon = com.kaiharimoto.neue.kit.Icons.Trash)
            }
        }
    }
}

/** A deck's faces: its chosen covers, else its three most-played main-deck cards. */
internal fun faces(stored: StoredDeck, neue: NeueState, state: DeckBuilderState): List<Card> {
    val deck = stored.entry.deck
    val covers = neue.prefs.covers[stored.entry.id].orEmpty()
    val ids = if (covers.isNotEmpty()) {
        DeckCovers.shown(covers, deck)
    } else {
        deck.main.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(DeckCovers.MAX)
    }
    return ids.mapNotNull(state.index::byId)
}

/** One turn: its balance, what goes out and what comes in, and why. */
@Composable
private fun TurnColumn(
    turn: Turn,
    plan: SidePlan,
    active: Boolean,
    state: DeckBuilderState,
    deck: Deck,
    art: Boolean,
    onPlan: (SidePlan) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Mu.colors
    Column(modifier.border(1.dp, if (active) c.ink else c.ink25)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(animatedColor(if (active) c.ink else Color.Transparent))
                .cursorPointer(caption = if (active) null else "Side this turn")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro(turn.title, Modifier.weight(1f), color = if (active) c.paper else c.ink)
            Mono("${plan.out.size} out · ${plan.into.size} in · ${SidingMath.balanceWords(plan)}", color = if (active) c.paper else c.ink70)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (art) {
                PlanArt("Out", SidingMath.counted(plan.out), state, deck, strong = false, Modifier.weight(1f)) { onPlan(plan.minusOut(it)) }
                PlanArt("In", SidingMath.counted(plan.into), state, deck, strong = true, Modifier.weight(1f)) { onPlan(plan.minusIn(it)) }
            } else {
                PlanList("Out", SidingMath.counted(plan.out), state, deck, strong = false, Modifier.weight(1f)) { onPlan(plan.minusOut(it)) }
                PlanList("In", SidingMath.counted(plan.into), state, deck, strong = true, Modifier.weight(1f)) { onPlan(plan.minusIn(it)) }
            }
        }
        Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            PlanNote(plan.note, { onPlan(plan.copy(note = it)) }, "Why: what this turn is afraid of, what it is for.")
        }
    }
}

/** A list of copies, a row a card: a click takes one copy back. */
@Composable
private fun PlanList(
    title: String,
    counted: List<Pair<CardId, Int>>,
    state: DeckBuilderState,
    deck: Deck,
    strong: Boolean,
    modifier: Modifier,
    onTakeBack: (CardId) -> Unit,
) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro(title, color = c.ink70)
        if (counted.isEmpty()) Small("Nothing yet", color = c.ink45)
        counted.forEach { (id, n) ->
            val card = state.index.byId(id)
            // A copy the deck no longer holds: the plan is older than the deck.
            val gone = if (strong) n > SidingMath.inOf(deck, id) else n > SidingMath.outOf(deck, id)
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHotAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (LocalTouchFirst.current) 40.dp else 30.dp)
                    .border(1.dp, if (strong) c.ink else c.ink25)
                    .background(animatedColor(if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(caption = "Take back")
                    .clickable(interactionSource = source, indication = null) { onTakeBack(id) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MuText(
                    card?.name ?: "#${id.value}",
                    Modifier.weight(1f),
                    style = MuType.body(LocalMuFonts.current).copy(fontSize = 13.sp, textDecoration = if (gone) TextDecoration.LineThrough else null),
                    color = if (gone) c.ink45 else c.ink,
                    maxLines = 2,
                )
                Mono("×$n", color = c.ink)
            }
        }
    }
}

/**
 * The same list as pictures (1.0.49, kai: "Art mode would only display cards per copy, not
 * using a quantity tag"): a card for every copy, five to a row, a click taking that copy back.
 * A copy the deck no longer holds is dimmed and struck through with a rule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanArt(
    title: String,
    counted: List<Pair<CardId, Int>>,
    state: DeckBuilderState,
    deck: Deck,
    strong: Boolean,
    modifier: Modifier,
    onTakeBack: (CardId) -> Unit,
) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro(title, color = c.ink70)
            Mono(counted.sumOf { it.second }.toString(), color = c.ink45)
        }
        if (counted.isEmpty()) {
            Small("Nothing yet", color = c.ink45)
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val width = ((maxWidth - gap * 4) / 5).coerceAtMost(72.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                counted.forEach { (id, n) ->
                    val held = if (strong) SidingMath.inOf(deck, id) else SidingMath.outOf(deck, id)
                    val card = state.index.byId(id)
                    repeat(n) { k ->
                        val gone = k >= held
                        Box(
                            Modifier
                                .width(width)
                                .aspectRatio(CARD_RATIO)
                                .border(if (strong) 2.dp else 1.dp, if (gone) c.ink25 else if (strong) c.ink else c.ink45)
                                .cursorPointer(caption = "Take back")
                                .muClickable { onTakeBack(id) },
                        ) {
                            if (card != null) {
                                NeueCard(card, Modifier.fillMaxSize().padding(if (strong) 2.dp else 1.dp), format = state.format, foil = "off", dimmed = gone || !strong)
                            } else {
                                Mono("#${id.value}", Modifier.align(Alignment.Center), color = c.ink45, size = 9.sp)
                            }
                            if (gone) Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(c.ink))
                        }
                    }
                }
            }
        }
    }
}

/** A note, written where it is read. */
@Composable
private fun PlanNote(value: String, onChange: (String) -> Unit, placeholder: String) {
    NotesField(value, onChange, placeholder)
}

/**
 * How the opponent sides against this deck, from their own plan in the web:
 * for the turn that answers the one being sided. What they bring in is what
 * you will face, so it is large; what they take out is less to play around,
 * so it is small and struck.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TheirPlan(
    webs: Webs,
    opponent: StoredDeck?,
    me: StoredDeck,
    turn: Turn,
    state: DeckBuilderState,
    neue: NeueState,
    inWeb: Boolean,
    art: Boolean,
    modifier: Modifier,
    onSideAs: (String) -> Unit,
) {
    val c = Mu.colors
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (opponent == null) {
            Micro("How they side against you", color = c.ink70)
            Small(
                if (inWeb) "Only a deck of this web, or a matchup linked to a decklist, can show its own plan against you."
                else "Link this matchup to a decklist (Link a decklist, beside its name) to see how it sides against you.",
                color = c.ink45,
            )
            return@Column
        }
        val name = opponent.entry.name
        val theirs = webs.sidingOf(opponent, state)
        val plan = theirs.against(me.entry.id, me.entry.name)?.plan(turn.theirs)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("How $name sides against you", color = c.ink70)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(c.ink).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    MuText(if (turn == Turn.FIRST) "You go first" else "You go second", style = MuType.small(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold), color = c.paper)
                }
                MuText("→", style = MuType.small(LocalMuFonts.current), color = c.ink)
                Box(Modifier.border(1.dp, c.ink).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    MuText(if (turn.theirs == Turn.FIRST) "They go first" else "They go second", style = MuType.small(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold), color = c.ink)
                }
            }
            Help("Follows the turn you are siding.")
        }
        if (plan == null || !plan.sided && plan.note.isBlank()) {
            Small("$name has no plan against you for this turn yet.", color = c.ink45)
            MicroLink("Side as $name", { onSideAs(opponent.entry.id) }, color = c.ink)
        } else {
            CardGrid("They bring in", "what you will face", SidingMath.counted(plan.into), state, columns = 3, struck = false, art = art)
            CardGrid("They take out", "less to play around", SidingMath.counted(plan.out), state, columns = 4, struck = true, art = art)
            if (plan.note.isNotBlank()) {
                Row(Modifier.fillMaxWidth().background(c.ink06)) {
                    Box(Modifier.width(2.dp).heightIn(min = 24.dp).background(c.ink))
                    MuText("“${plan.note}”", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MuType.small(LocalMuFonts.current), color = c.ink)
                }
            }
        }
        // Their deck, by its groups: what they play, in the words they sorted it with.
        val groups = remember(opponent, state.deckId) { DeckGroupsCodec.read(webs.extendedOf(opponent, state)).groups }
        if (groups.groups.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
            Micro("$name, by its groups", color = c.ink70)
            val deck = webs.deckOf(opponent, state)
            groups.ordered().forEach { g ->
                val names = (deck.main + deck.extra).distinct().filter { groups.assignments[it] == g.id }.mapNotNull { state.index.byId(it)?.name }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(10.dp).background(GroupMarkers.hue(g.color)))
                    MuText(
                        "${g.name} · ${names.joinToString(" · ").ifEmpty { "no cards" }}",
                        style = MuType.small(LocalMuFonts.current),
                        color = c.ink,
                    )
                }
            }
        }
    }
}

/**
 * Cards as pictures in a grid: what the opponent brings in or takes out. In list mode each
 * card once with its count and name; in art mode (1.0.49) a picture for every copy, and
 * neither — the picture is the name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardGrid(title: String, caption: String, grouped: List<Pair<CardId, Int>>, state: DeckBuilderState, columns: Int, struck: Boolean, art: Boolean) {
    val counted = if (art) grouped.flatMap { (id, n) -> List(n) { id to 1 } } else grouped
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            MuText(title, Modifier.weight(1f), style = MuType.small(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold), color = if (struck) c.ink70 else c.ink)
            Small(caption, color = c.ink45)
        }
        if (counted.isEmpty()) {
            Small("Nothing", color = c.ink45)
            return@Column
        }
        counted.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, n) ->
                    val card = state.index.byId(id)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).border(if (struck) 1.dp else 2.dp, if (struck) c.ink45 else c.ink)) {
                            if (card != null) NeueCard(card, Modifier.fillMaxSize().padding(if (struck) 1.dp else 2.dp), foil = "off", dimmed = struck)
                            if (!art) {
                                Box(Modifier.align(Alignment.BottomEnd).background(if (struck) c.paper else c.ink).padding(horizontal = 4.dp)) {
                                    Mono("×$n", color = if (struck) c.ink else c.paper, size = if (struck) 10.sp else 12.sp)
                                }
                            }
                        }
                        if (!art) MuText(
                            card?.name ?: "#${id.value}",
                            style = MuType.small(LocalMuFonts.current).copy(
                                fontSize = if (struck) 10.sp else 11.sp,
                                textDecoration = if (struck) TextDecoration.LineThrough else null,
                            ),
                            color = if (struck) c.ink70 else c.ink,
                            maxLines = 2,
                        )
                    }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
