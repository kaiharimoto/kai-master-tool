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
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.animatedColor
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
    web: DeckWeb,
    decks: List<StoredDeck>,
    me: StoredDeck,
    state: DeckBuilderState,
    neue: NeueState,
    onBack: () -> Unit,
) {
    val c = Mu.colors
    val phone = LocalPhone.current
    val myDeck = webs.deckOf(me, state)
    var siding by remember(me.entry.id) { mutableStateOf(webs.sidingOf(me, state)) }
    var turn by remember { mutableStateOf(Turn.FIRST) }
    val opponents = decks.filter { it.entry.id != me.entry.id }
    // Matchups against no deck of this web: a legacy plan, or one the deck brought from elsewhere.
    val loose = siding.matchups.filter { m -> opponents.none { siding.against(it.entry.id, it.entry.name) == m } }
    var selected by remember(me.entry.id) {
        mutableStateOf(webs.sidingAgainst?.takeIf { a -> opponents.any { it.entry.id == a } } ?: opponents.firstOrNull()?.entry?.id ?: loose.firstOrNull()?.let { "m:${it.id}" })
    }
    val opponent = opponents.firstOrNull { it.entry.id == selected }
    val matchup: Matchup? = opponent?.let { siding.against(it.entry.id, it.entry.name) } ?: siding.byId(selected?.removePrefix("m:"))
    val name = opponent?.entry?.name ?: matchup?.name ?: ""

    /** [change] to this matchup, made (and linked to the web deck) on its first edit. */
    fun edit(change: (Matchup) -> Matchup) {
        val base = matchup ?: Matchup("m-${Random.nextLong().toULong().toString(36)}", name)
        val linked = if (opponent != null) base.copy(deckId = opponent.entry.id, name = opponent.entry.name) else base
        val next = siding.put(change(linked))
        siding = next
        webs.saveSiding(me.entry.id, next, state)
    }

    fun plan(t: Turn) = matchup?.plan(t) ?: SidePlan()
    fun setPlan(t: Turn, p: SidePlan) = edit { it.withPlan(t, p) }

    Column(Modifier.fillMaxSize()) {
        val library = com.kaiharimoto.neue.art.LocalArt.current
        val custom = com.kaiharimoto.neue.art.LocalCustomArt.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var making by remember { mutableStateOf(false) }
        SidingBar(webs, web, decks, me, onBack, neue, making) {
            making = true
            scope.launch {
                try {
                    GuideExport.deliver(webs, web, decks, me, state, neue, library, custom)
                } finally {
                    making = false
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
        if (opponents.isEmpty() && loose.isEmpty()) {
            com.kaiharimoto.neue.kit.EmptyState(
                "Nobody to side against yet.",
                "Add the decks you expect to face to ${web.name}; each becomes a matchup here.",
            )
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 1100.dp
            val narrow = maxWidth < 700.dp
            val theirs: @Composable (Modifier) -> Unit = { modifier ->
                TheirPlan(webs, opponent, me, turn, state, neue, modifier) { webs.side(it, me.entry.id) }
            }
            val body: @Composable (Modifier) -> Unit = { modifier ->
                val scroll = rememberScrollState()
                Box(modifier) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (narrow) 16.dp else 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (narrow) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                opponents.forEach { o ->
                                    val m = siding.against(o.entry.id, o.entry.name)
                                    Tag(o.entry.name, selected == o.entry.id, { selected = o.entry.id }, count = marks(m), caption = "Side")
                                }
                                loose.forEach { m -> Tag(m.name, selected == "m:${m.id}", { selected = "m:${m.id}" }, count = marks(m), caption = "Side") }
                            }
                        }
                        OpponentHeader(opponent, name, state, neue)
                        PlanNote(matchup?.note.orEmpty(), { note -> edit { it.copy(note = note) } }, "The matchup: how it plays, what matters, what to hold.")
                        if (narrow) {
                            com.kaiharimoto.neue.kit.Segmented(turn, Turn.entries, { it.title }, { turn = it })
                            TurnColumn(turn, plan(turn), true, state, myDeck, { setPlan(turn, it) }, {})
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Turn.entries.forEach { t ->
                                    TurnColumn(t, plan(t), t == turn, state, myDeck, { setPlan(t, it) }, { turn = t }, Modifier.weight(1f))
                                }
                            }
                        }
                        Tray(myDeck, plan(turn), turn, state, { setPlan(turn, it) })
                        if (!wide) theirs(Modifier.fillMaxWidth())
                    }
                    ScrollbarFor(scroll)
                }
            }
            if (narrow) {
                body(Modifier.fillMaxSize())
            } else {
                Row(Modifier.fillMaxSize()) {
                    MatchupList(opponents, loose, siding, selected, { selected = it }, Modifier.width(224.dp).fillMaxHeight())
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

/** Back to the web, and which deck is being sided. */
@Composable
private fun SidingBar(webs: Webs, web: DeckWeb, decks: List<StoredDeck>, me: StoredDeck, onBack: () -> Unit, neue: NeueState, making: Boolean, onGuide: () -> Unit) {
    val c = Mu.colors
    val phone = LocalPhone.current
    var asAt by remember { mutableStateOf(Offset.Zero) }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        MuButton("← ${web.name.ifBlank { "The web" }}", onBack, variant = BtnVariant.GHOST, size = BtnSize.SM)
        Micro("Siding as", color = c.ink70)
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
        GuideButton(making, onGuide)
        if (!phone) {
            Small(
                if (LocalTouchFirst.current) "Tap a card in the deck to side it; tap one in a list to take it back."
                else "Pick a column, then click a card in the deck to side it out or in. Click one in a list to take it back.",
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

/** Every opponent: the web's other decks, then any plan against a deck not in the web. */
@Composable
private fun MatchupList(
    opponents: List<StoredDeck>,
    loose: List<Matchup>,
    siding: DeckSiding,
    selected: String?,
    onSelect: (String) -> Unit,
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
                Micro("Not in this web", Modifier.padding(start = 8.dp, top = 12.dp, bottom = 6.dp), color = c.ink45)
                loose.forEach { m -> MatchupRow(m.name, marks(m), selected == "m:${m.id}") { onSelect("m:${m.id}") } }
            }
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

/** Who this is against: their faces and their name. */
@Composable
private fun OpponentHeader(opponent: StoredDeck?, name: String, state: DeckBuilderState, neue: NeueState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (opponent != null) {
            val faces = remember(opponent.entry.deck, state.index) { faces(opponent, neue, state) }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                faces.forEach { NeueCard(it, Modifier.size(34.dp, 50.dp), foil = "off") }
            }
        }
        MuText("vs $name", style = MuType.h2(LocalMuFonts.current), color = Mu.colors.ink, maxLines = 2)
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
            PlanList("Out", SidingMath.counted(plan.out), state, deck, strong = false, Modifier.weight(1f)) { onPlan(plan.minusOut(it)) }
            PlanList("In", SidingMath.counted(plan.into), state, deck, strong = true, Modifier.weight(1f)) { onPlan(plan.minusIn(it)) }
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

/** A note, written where it is read. */
@Composable
private fun PlanNote(value: String, onChange: (String) -> Unit, placeholder: String) {
    NotesField(value, onChange, placeholder)
}

/**
 * The deck, to side from: every card of the main and extra decks (a click takes a
 * copy out) and of the side deck (a click brings one in), each with how many
 * copies are left to move in the turn being sided.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tray(deck: Deck, plan: SidePlan, turn: Turn, state: DeckBuilderState, onPlan: (SidePlan) -> Unit) {
    val c = Mu.colors
    val width = if (LocalTouchFirst.current) 64.dp else 56.dp
    val when_ = if (turn == Turn.FIRST) "going first" else "going second"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("Main and extra deck", color = c.ink)
            Small("side out, $when_", color = c.ink45)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (deck.main.distinct() + deck.extra.distinct()).forEach { id ->
                val total = SidingMath.outOf(deck, id)
                TrayCard(id, total - plan.outCount(id), total, state, width, "Side out") { if (SidingMath.canOut(deck, plan, id)) onPlan(plan.plusOut(id)) }
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("Side deck", color = c.ink)
            Small("side in, $when_", color = c.ink45)
        }
        if (deck.side.isEmpty()) Small("The side deck is empty.", color = c.ink45)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            deck.side.distinct().forEach { id ->
                val total = SidingMath.inOf(deck, id)
                TrayCard(id, total - plan.inCount(id), total, state, width, "Side in") { if (SidingMath.canIn(deck, plan, id)) onPlan(plan.plusIn(id)) }
            }
        }
    }
}

@Composable
private fun TrayCard(id: CardId, left: Int, total: Int, state: DeckBuilderState, width: Dp, verb: String, onClick: () -> Unit) {
    val c = Mu.colors
    val card = state.index.byId(id) ?: return
    val spent = left <= 0
    Box(
        Modifier
            .width(width)
            .aspectRatio(CARD_RATIO)
            .cursorPointer(caption = if (spent) null else verb, enabled = !spent, reason = "Every copy is sided already")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !spent, onClick = onClick),
    ) {
        NeueCard(card, Modifier.fillMaxSize(), foil = "off", dimmed = spent)
        Box(Modifier.align(Alignment.BottomEnd).padding(2.dp).background(c.paper).border(1.dp, c.ink).padding(horizontal = 3.dp)) {
            Mono("$left/$total", color = c.ink, size = 9.sp)
        }
    }
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
    modifier: Modifier,
    onSideAs: (String) -> Unit,
) {
    val c = Mu.colors
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (opponent == null) {
            Micro("How they side against you", color = c.ink70)
            Small("Only a deck of this web can show its own plan against you.", color = c.ink45)
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
            CardGrid("They bring in", "what you will face", SidingMath.counted(plan.into), state, columns = 3, struck = false)
            CardGrid("They take out", "less to play around", SidingMath.counted(plan.out), state, columns = 4, struck = true)
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

/** Cards as pictures in a grid, each with its count: what the opponent brings in or takes out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardGrid(title: String, caption: String, counted: List<Pair<CardId, Int>>, state: DeckBuilderState, columns: Int, struck: Boolean) {
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
                            Box(Modifier.align(Alignment.BottomEnd).background(if (struck) c.paper else c.ink).padding(horizontal = 4.dp)) {
                                Mono("×$n", color = if (struck) c.ink else c.paper, size = if (struck) 10.sp else 12.sp)
                            }
                        }
                        MuText(
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
