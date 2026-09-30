package com.kaiharimoto.neue.prep

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.Checklist
import com.kaiharimoto.mastertool.core.prep.Countdown
import com.kaiharimoto.mastertool.core.prep.DecklistSheet
import com.kaiharimoto.mastertool.core.prep.Decklists
import com.kaiharimoto.mastertool.core.prep.Drill
import com.kaiharimoto.mastertool.core.prep.EventCheck
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.Policy
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.siding.DeckSiding
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.ai.ChartBlock
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Meter
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuTabs
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Stat
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.neue.pages.NotesField
import com.kaiharimoto.neue.pages.SidingBoard
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.web.Webs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Prep page's tabs, in the order an event is prepared for. */
enum class PrepTab(val title: String) {
    PLAN("Plan"),
    PRACTICE("Practice"),
    DRILLS("Drills"),
    DECKLIST("Decklist"),
    DAY("The day"),
}

/**
 * Tournament prep (1.0.50, `NEUE.md` §4l; kai: "do a research run and design the tournament
 * prep feature"). The page is shaped by the rules of the KDE-US Tournament Policy v2.5 that a
 * player prepares around: fifty-minute rounds where an unfinished match is a loss for both, the
 * loser of a duel choosing who goes first, siding card for card in under three minutes — and no
 * notes at the table, not even a siding plan, so the plans are drilled until they are known.
 *
 * - **Plan**: the event (date, tier, players, the web of the field, your deck, how the list is
 *   handed in), its countdown, what the policy means for it, and whether the deck is ready.
 * - **Practice**: test games logged in two clicks; the matchup table, the match win to expect
 *   against the field, the matchups at risk of time.
 * - **Drills**: a matchup and a turn, the plan hidden, side from memory against the clock.
 * - **Decklist**: our own sheet as a PDF, and the list as text to paste into registration.
 * - **The day**: what to bring, the rounds as they are played, and the record the cut needs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrepPage(prep: Prep, webs: Webs, state: DeckBuilderState, neue: NeueState, reload: Int) {
    val c = Mu.colors
    val phone = LocalPhone.current
    var library by remember { mutableStateOf<List<StoredDeck>>(emptyList()) }
    LaunchedEffect(reload, webs.revision) { library = webs.libraryDecks() }
    if (!prep.loaded) return
    val doc = prep.doc
    val event = prep.active
    if (event == null) {
        EmptyState(
            "No event yet.",
            "Add the event you are preparing for: its date, its size and the field you expect. Then practise against the field, drill your siding plans and print your decklist here.",
        ) { MuButton("New event", { newEvent(prep, webs, state) }, variant = BtnVariant.PRIMARY, icon = Icons.Plus) }
        return
    }
    val web = webs.library.byId(event.webId)
    val mine = library.firstOrNull { it.entry.id == event.deckId }
    Row(Modifier.fillMaxSize()) {
        if (!phone) {
            EventList(prep, event, { newEvent(prep, webs, state) }, Modifier.width(224.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
        }
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (phone) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        doc.events.forEach { e -> Tag(e.name.ifBlank { "Untitled event" }, e.id == event.id, { prep.select(e.id) }, caption = "Open") }
                        Tag("+ Event", false, { newEvent(prep, webs, state) }, caption = "New")
                    }
                }
                EventHead(event, prep.today(), web, mine)
                MuTabs(prep.tab, PrepTab.entries, { it.title }, { prep.tab = it })
                when (prep.tab) {
                    PrepTab.PLAN -> PlanTab(prep, event, webs, web, mine, library, state)
                    PrepTab.PRACTICE -> PracticeTab(prep, event, webs, web, mine, library, state, neue)
                    PrepTab.DRILLS -> DrillsTab(prep, webs, mine, state, neue)
                    PrepTab.DECKLIST -> DecklistTab(prep, event, webs, mine, state, neue)
                    PrepTab.DAY -> DayTab(prep, event, webs, web, library, state, neue)
                }
            }
            ScrollbarFor(scroll)
        }
    }
}

/** A new event, a fortnight out, on the web and deck in view: the likeliest start. */
private fun newEvent(prep: Prep, webs: Webs, state: DeckBuilderState) {
    val today = IsoDate.epochDay(prep.today()) ?: 0L
    val web = webs.selected
    val deck = web?.entries?.firstOrNull { it.mine }?.deckId ?: state.deckId
    prep.putEvent(PrepEvent(prep.newId("ev"), "New event", IsoDate.of(today + 14), webId = web?.id, deckId = deck))
    prep.tab = PrepTab.PLAN
}

@Composable
private fun EventList(prep: Prep, active: PrepEvent, onNew: () -> Unit, modifier: Modifier) {
    val c = Mu.colors
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Micro("Events", Modifier.padding(start = 8.dp, bottom = 8.dp), color = c.ink70)
            val today = prep.today()
            prep.doc.events.forEach { e ->
                val days = IsoDate.daysBetween(today, e.date)
                com.kaiharimoto.neue.kit.MenuRow(
                    text = e.name.ifBlank { "Untitled event" },
                    onClick = { prep.select(e.id) },
                    hint = days?.let { if (it >= 0) "${it}d" else "past" },
                    selected = e.id == active.id,
                )
            }
            MuButton("New event", onNew, Modifier.padding(top = 8.dp), variant = BtnVariant.GHOST, size = BtnSize.SM, icon = Icons.Plus)
        }
        ScrollbarFor(scroll)
    }
}

@Composable
private fun EventHead(event: PrepEvent, today: String, web: DeckWeb?, mine: StoredDeck?) {
    val c = Mu.colors
    val days = IsoDate.daysBetween(today, event.date)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MuText(event.name.ifBlank { "Untitled event" }, style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 2)
        Small(
            listOfNotNull(
                event.date.takeIf { it.isNotBlank() },
                days?.let(IsoDate::words),
                "Tier ${event.tier}",
                event.attendance.takeIf { it > 0 }?.let { "$it players" },
                web?.name?.let { "field: $it" },
                mine?.entry?.name?.let { "deck: $it" },
            ).joinToString(" · "),
            color = c.ink70,
        )
    }
}

// ---------------------------------------------------------------- Plan

private val TIERS = listOf(1, 2, 3, 4)
private fun tierName(t: Int) = when (t) {
    1 -> "1 · Locals"
    2 -> "2 · Regional"
    3 -> "3 · YCS"
    else -> "4 · Worlds"
}
private val METHODS = listOf(PrepEvent.DECKLIST_PAPER, PrepEvent.DECKLIST_NEURON, PrepEvent.DECKLIST_ONLINE)
private fun methodName(m: String) = when (m) {
    PrepEvent.DECKLIST_NEURON -> "NEURON"
    PrepEvent.DECKLIST_ONLINE -> "Online"
    else -> "Paper"
}

@Composable
private fun PlanTab(prep: Prep, event: PrepEvent, webs: Webs, web: DeckWeb?, mine: StoredDeck?, library: List<StoredDeck>, state: DeckBuilderState) {
    fun put(e: PrepEvent) = prep.putEvent(e, activate = false)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 900.dp
        val form: @Composable (Modifier) -> Unit = { m -> EventForm(prep, event, webs, library, ::put, m) }
        val side: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                CountdownBox(event, prep.today())
                PolicyBox(event)
                ReadyBox(event, webs, mine, state)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                form(Modifier.weight(1f))
                side(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                form(Modifier.fillMaxWidth())
                side(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun EventForm(prep: Prep, event: PrepEvent, webs: Webs, library: List<StoredDeck>, put: (PrepEvent) -> Unit, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Event")
        MuInput(event.name, { put(event.copy(name = it)) }, Modifier.fillMaxWidth(), placeholder = "Regional, YCS, locals…")
        FieldLabel("Date", hint = IsoDate.daysBetween(prep.today(), event.date)?.let(IsoDate::words) ?: "yyyy-mm-dd")
        MuInput(event.date, { put(event.copy(date = it.trim())) }, Modifier.fillMaxWidth(), placeholder = "2026-10-17", mono = true)
        FieldLabel("Tier", hint = "Konami's")
        Segmented(event.tier, TIERS, ::tierName, { put(event.copy(tier = it)) }, small = true)
        FieldLabel("Players", hint = "expected")
        MuInput(event.attendance.takeIf { it > 0 }?.toString().orEmpty(), { v -> put(event.copy(attendance = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) }, Modifier.fillMaxWidth(), placeholder = "64", mono = true)
        FieldLabel("The field", hint = "a web on Format")
        val webChoices = listOf<DeckWeb?>(null) + webs.library.webs
        MuSelect(webs.library.byId(event.webId), webChoices, { it?.name ?: "None" }, { w ->
            val deck = w?.entries?.firstOrNull { it.mine }?.deckId ?: event.deckId
            put(event.copy(webId = w?.id, deckId = deck))
        }, Modifier.fillMaxWidth())
        FieldLabel("Your deck")
        // The web's starred decks first, then the library.
        val web = webs.library.byId(event.webId)
        val starred = web?.entries?.filter { it.mine }?.map { it.deckId }.orEmpty()
        val decks = listOf<StoredDeck?>(null) + library.sortedWith(compareByDescending<StoredDeck> { it.entry.id in starred }.thenBy { it.entry.name.lowercase() })
        MuSelect(library.firstOrNull { it.entry.id == event.deckId }, decks, { d -> d?.let { (if (it.entry.id in starred) "★ " else "") + it.entry.name } ?: "None" }, { d ->
            put(event.copy(deckId = d?.entry?.id))
        }, Modifier.fillMaxWidth())
        FieldLabel("Decklist", hint = if (Policy.decklistRequired(event.tier)) "required" else "not required at Tier 1")
        Segmented(event.decklist, METHODS, ::methodName, { put(event.copy(decklist = it)) }, small = true)
        FieldLabel("Decklist due", hint = "as the organiser wrote it")
        MuInput(event.deadline.orEmpty(), { put(event.copy(deadline = it.trim().ifBlank { null })) }, Modifier.fillMaxWidth(), placeholder = "2026-10-15", mono = true)
        FieldLabel("Check-in")
        MuInput(event.checkIn.orEmpty(), { put(event.copy(checkIn = it.ifBlank { null })) }, Modifier.fillMaxWidth(), placeholder = "Saturday 9:00, closes 9:45")
        FieldLabel("Notes")
        NotesField(event.notes, { put(event.copy(notes = it)) }, "The venue, the prize support, who you are going with.")
        MuButton("Remove this event", { prep.removeEvent(event.id) }, Modifier.padding(top = 8.dp), variant = BtnVariant.GHOST, size = BtnSize.SM, icon = Icons.Trash)
        Help("Its games stay in the log.", color = c.ink45)
    }
}

@Composable
private fun CountdownBox(event: PrepEvent, today: String) {
    val c = Mu.colors
    val milestones = Countdown.milestones(event, today)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Countdown", color = c.ink70)
        if (milestones.isEmpty()) Small("Give the event a date (yyyy-mm-dd) to count down to.", color = c.ink45)
        milestones.forEach { m ->
            val past = (m.days ?: 0) < 0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (past) c.ink25 else c.ink))
                MuText(m.label, Modifier.weight(1f).padding(start = 10.dp), style = MuType.body(LocalMuFonts.current).copy(fontSize = 14.sp), color = if (past) c.ink45 else c.ink, maxLines = 1)
                Mono(m.date, color = c.ink45)
                Mono(m.days?.let(IsoDate::words).orEmpty(), Modifier.width(96.dp).padding(start = 10.dp), color = if (past) c.ink45 else c.ink)
            }
        }
    }
}

@Composable
private fun PolicyBox(event: PrepEvent) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("What the policy means for it", color = c.ink70)
        if (event.attendance > 0) {
            val swiss = Policy.swiss(event.tier, event.attendance)
            MuText(
                buildString {
                    append("${swiss.rounds} rounds of Swiss")
                    if (swiss.twoDays) append(" (${swiss.day1} on Day 1, ${swiss.day2} on Day 2)")
                    if (swiss.topCut > 0) append(", then Top ${swiss.topCut}")
                    append(".")
                },
                style = MuType.body(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold),
                color = c.ink,
            )
            Small(Policy.cutRecord(swiss, event.attendance) + ".", color = c.ink)
        } else {
            Small("Say how many players to expect, and the rounds and the record the cut needs follow.", color = c.ink45)
        }
        Policy.rules.forEach { r ->
            Row {
                Mono("·", Modifier.width(14.dp), color = c.ink45)
                Small(r, color = c.ink70)
            }
        }
        Help("KDE-US Tournament Policy v2.5. The event's own documents outrank it.")
    }
}

@Composable
private fun ReadyBox(event: PrepEvent, webs: Webs, mine: StoredDeck?, state: DeckBuilderState) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("Ready to register", color = c.ink70)
        if (mine == null) {
            Small("Choose your deck, and it is checked here: its legality today and every siding plan.", color = c.ink45)
            return@Column
        }
        val deck = webs.deckOf(mine, state)
        val items = remember(deck, state.index, state.format, webs.revision, event.tier) {
            EventCheck.check(deck, DeckValidator.validate(deck, state.index::byId, state.format), webs.sidingOf(mine, state), event.tier)
        }
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono(if (!item.ok) "✕" else if (item.warning) "·" else "✓", Modifier.width(14.dp), color = c.ink, size = 13.sp)
                Column(Modifier.weight(1f)) {
                    MuText(item.title, style = MuType.body(LocalMuFonts.current).copy(fontSize = 14.sp, fontWeight = if (item.ok) FontWeight.Normal else FontWeight.Bold), color = c.ink)
                    if (item.detail.isNotBlank()) Small(item.detail, color = c.ink45)
                }
            }
        }
        Help("Checked against the Forbidden & Limited List the card pool knows today; the list in force on the event's date may differ.")
    }
}

// ---------------------------------------------------------------- Practice

private val REASONS = listOf(TestGame.REASON_BRICK, TestGame.REASON_INTERRUPTED, TestGame.REASON_OUTPLAYED, TestGame.REASON_TIME, TestGame.REASON_OTHER)
private fun reasonName(r: String) = when (r) {
    TestGame.REASON_BRICK -> "Bricked"
    TestGame.REASON_INTERRUPTED -> "Interrupted"
    TestGame.REASON_OUTPLAYED -> "Outplayed"
    TestGame.REASON_TIME -> "Time"
    else -> "Other"
}

/** An opponent to log against: a web deck by id, or a name. */
private data class Foe(val key: String, val name: String, val share: Int?)

private fun foes(web: DeckWeb?, library: List<StoredDeck>, mine: StoredDeck?): List<Foe> =
    web?.entries.orEmpty().filter { it.deckId != mine?.entry?.id }.mapNotNull { e ->
        library.firstOrNull { it.entry.id == e.deckId }?.let { Foe(e.deckId, it.entry.name, e.share) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeTab(prep: Prep, event: PrepEvent, webs: Webs, web: DeckWeb?, mine: StoredDeck?, library: List<StoredDeck>, state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val field = foes(web, library, mine)
    val loose = mine?.let { m -> webs.sidingOf(m, state).matchups.filter { x -> field.none { it.key == x.deckId } }.map { Foe(it.name, it.name, null) } }.orEmpty()
    val foes = field + loose
    var foe by remember(event.id) { mutableStateOf<Foe?>(null) }
    var typed by remember { mutableStateOf("") }
    var turn by remember { mutableStateOf(TestGame.FIRST) }
    var game by remember { mutableStateOf(1) }
    var reason by remember { mutableStateOf<String?>(null) }
    var minutes by remember { mutableStateOf("") }
    val games = prep.doc.games.filter { it.round == null && (mine == null || it.deckId == mine.entry.id) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Micro("Log a game", color = c.ink70)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            foes.forEach { f -> Tag(f.name, foe == f, { foe = if (foe == f) null else f }, count = f.share?.let { "$it%" }, caption = "Against") }
        }
        MuInput(typed, { typed = it; if (it.isNotBlank()) foe = null }, Modifier.widthIn(max = 360.dp).fillMaxWidth(), placeholder = if (foes.isEmpty()) "Who against: a deck's name" else "Or another deck, by name")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Segmented(turn, listOf(TestGame.FIRST, TestGame.SECOND), { if (it == TestGame.FIRST) "Going first" else "Going second" }, { turn = it }, small = true)
            Segmented(game, listOf(1, 2, 3), { "Game $it" }, { game = it }, small = true)
            MuInput(minutes, { minutes = it.filter(Char::isDigit).take(3) }, Modifier.width(96.dp), placeholder = "Minutes", mono = true, dense = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            REASONS.forEach { r -> Tag(reasonName(r), reason == r, { reason = if (reason == r) null else r }, caption = "Why") }
        }
        val against = foe ?: typed.trim().takeIf { it.isNotEmpty() }?.let { Foe(it, it, null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TestGame.WIN to "Won", TestGame.LOSS to "Lost", TestGame.DRAW to "Draw").forEach { (result, label) ->
                MuButton(label, {
                    val a = against ?: return@MuButton
                    val g = TestGame(
                        prep.newId("g"), System.currentTimeMillis(), mine?.entry?.id, a.key, a.name, turn, game, result, reason,
                        minutes = minutes.toIntOrNull(),
                    )
                    prep.log(g)
                    reason = null
                    minutes = ""
                    neue.note = Note("Logged: ${label.lowercase()} against ${a.name}, ${if (turn == TestGame.FIRST) "going first" else "going second"}", action = "Undo") { prep.removeGame(g.id) }
                }, variant = if (result == TestGame.WIN) BtnVariant.PRIMARY else BtnVariant.SECONDARY, enabled = against != null, reason = "Choose who it was against")
            }
        }
        HRule()
        Summary(games, field, mine)
        HRule()
        RecentGames(prep, games)
    }
}

@Composable
private fun Summary(games: List<TestGame>, field: List<Foe>, mine: StoredDeck?) {
    val c = Mu.colors
    val rows = remember(games) { TestStats.matrix(games) }
    val shares = field.mapNotNull { f -> f.share?.let { f.key to it } }.toMap()
    val expected = TestStats.expected(rows, shares)
    val risk = TestStats.timeRisk(rows).toSet()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Stat("Expected match win", if (shares.isEmpty()) "--" else percent(expected))
            Stat("Games", games.size.toString())
            Stat(
                "Record",
                "${games.count { it.result == TestGame.WIN }}-${games.count { it.result == TestGame.LOSS }}-${games.count { it.result == TestGame.DRAW }}",
            )
        }
        Help(
            if (shares.isEmpty()) "Give the field's decks their shares on Format and the match win to expect against it follows."
            else "Best of three against the field by its shares: game 1's turn a coin flip, then the loser of each duel going first. Few games are pulled toward even.",
        )
        if (rows.isEmpty()) {
            Small("No games logged yet.", color = c.ink45)
            return@Column
        }
        Matrix(rows, shares, risk)
        if (risk.isNotEmpty()) {
            Small("At risk of time: ${rows.filter { it.opponent in risk }.joinToString(" · ") { it.name }}. Three games of these run past 50 minutes, and an unfinished match is a loss for both.", color = c.ink)
        }
        val shown = rows.filter { it.all.games > 0 }.take(8)
        if (shown.isNotEmpty()) Box(Modifier.widthIn(max = 720.dp)) {
            ChartBlock(
                ChatChart.Chart(
                    ChatChart.Type.BAR,
                    "Win rate by turn",
                    shown.map { it.name },
                    listOf(
                        ChatChart.Series("Going first", shown.map { it.first.pct * 100 }),
                        ChatChart.Series("Going second", shown.map { it.second.pct * 100 }),
                    ),
                    unit = "%",
                ),
            )
        }
    }
}

/** The matchup table: on a narrow screen only the turns, the games and the minutes, which fit 360 dp. */
@Composable
private fun Matrix(rows: List<TestStats.Row>, shares: Map<String, Int>, risk: Set<String>) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    fun rate(r: TestStats.Rate) = if (r.games == 0) "--" else "${percent(r.pct)} (${r.games})"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 600.dp
        val columns: List<Triple<String, Float, (TestStats.Row) -> String>> = if (narrow) {
            listOf(
                Triple("First", 1.2f) { r -> rate(r.first) },
                Triple("Second", 1.2f) { r -> rate(r.second) },
                Triple("Min", 0.6f) { r -> r.avgMinutes?.let { "${it.toInt()}" } ?: "--" },
            )
        } else {
            listOf(
                Triple("Share", 0.8f) { r -> shares[r.opponent]?.let { "$it%" } ?: "--" },
                Triple("First", 1.2f) { r -> rate(r.first) },
                Triple("Second", 1.2f) { r -> rate(r.second) },
                Triple("Game 1", 1.2f) { r -> rate(r.preSide) },
                Triple("Games 2–3", 1.2f) { r -> rate(r.postSide) },
                Triple("Minutes", 0.9f) { r -> r.avgMinutes?.let { "${it.toInt()}" } ?: "--" },
            )
        }
        val nameWeight = if (narrow) 1.4f else 2.2f
        Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
            Row(Modifier.fillMaxWidth().background(c.ink06).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Micro("Against", Modifier.weight(nameWeight), color = c.ink70)
                columns.forEach { (h, w, _) -> Micro(h, Modifier.weight(w), color = c.ink70) }
            }
            rows.forEach { r ->
                HRule()
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    MuText(r.name + if (r.opponent in risk) " · time" else "", Modifier.weight(nameWeight), style = MuType.body(f).copy(fontSize = 14.sp), color = c.ink, maxLines = 2)
                    columns.forEach { (_, w, value) -> Mono(value(r), Modifier.weight(w), color = c.ink) }
                }
            }
        }
    }
}

@Composable
private fun RecentGames(prep: Prep, games: List<TestGame>) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro("Recent games", color = c.ink70)
        games.takeLast(12).reversed().forEach { g ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Mono(g.result, Modifier.width(22.dp), color = c.ink, size = 13.sp)
                Small(
                    listOfNotNull(
                        g.opponentName,
                        if (g.turn == TestGame.FIRST) "first" else "second",
                        "game ${g.game}",
                        g.reason?.let(::reasonName)?.lowercase(),
                        g.minutes?.let { "$it min" },
                    ).joinToString(" · "),
                    Modifier.weight(1f),
                    color = c.ink,
                )
                com.kaiharimoto.neue.kit.IconButton(Icons.X, { prep.removeGame(g.id) }, size = 28.dp, label = "Remove")
            }
        }
    }
}

// ---------------------------------------------------------------- Drills

/** One plan to drill: a matchup and a turn the deck is sided for. */
private data class DrillPlan(val key: String, val matchup: Matchup, val turn: Turn, val plan: SidePlan)

private fun drillPlans(siding: DeckSiding): List<DrillPlan> =
    siding.matchups.flatMap { m -> Turn.entries.map { t -> DrillPlan(Drill.key(m.id, t.name), m, t, m.plan(t)) } }.filter { it.plan.sided }

@Composable
private fun DrillsTab(prep: Prep, webs: Webs, mine: StoredDeck?, state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    if (mine == null) {
        Small("Choose your deck on Plan, and its siding plans are drilled here.", color = c.ink45)
        return
    }
    val deck = webs.deckOf(mine, state)
    val plans = drillPlans(webs.sidingOf(mine, state))
    if (plans.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Small("No siding plans to drill yet. Plan each matchup on the Siding page, going first and going second.", color = c.ink70)
            MuButton("Open Siding", { webs.side(mine.entry.id) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
        }
        return
    }
    var current by remember(mine.entry.id) { mutableStateOf(Drill.next(plans.map { it.key }, prep.doc.drills, System.currentTimeMillis())) }
    val drill = plans.firstOrNull { it.key == current } ?: plans.first()
    var picked by remember(drill.key, current) { mutableStateOf(SidePlan()) }
    var score by remember(drill.key, current) { mutableStateOf<Drill.Score?>(null) }
    var left by remember(drill.key, current) { mutableStateOf(Policy.SIDING_MINUTES * 60) }
    LaunchedEffect(drill.key, current, score) {
        while (score == null && left > 0) {
            delay(1000)
            left--
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Help("No notes may be read at the table, not even between duels, so the plan has to be known. Side from memory, as you would across from them, then check.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                MuText("vs ${drill.matchup.name}", style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 1)
                Small("${drill.turn.title} · ${drill.plan.out.size} out, ${drill.plan.into.size} in", color = c.ink70)
            }
            // The clock is a number, not a motion (Master UI): three minutes, as the policy gives.
            Mono("${left / 60}:${(left % 60).toString().padStart(2, '0')}", color = if (left == 0) c.ink45 else c.ink, size = 28.sp)
        }
        if (score == null) {
            SidingBoard(deck, picked, drill.turn, state, neue.prefs.sidingExtra, { v -> neue.update { it.copy(sidingExtra = v) } }) { picked = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("Check", {
                    val s = Drill.score(drill.plan.out.map { it.value }, drill.plan.into.map { it.value }, picked.out.map { it.value }, picked.into.map { it.value })
                    score = s
                    prep.drilled(drill.key, s)
                }, variant = BtnVariant.PRIMARY)
                MuButton("Start over", { picked = SidePlan() }, variant = BtnVariant.GHOST)
            }
        } else {
            val s = score!!
            MuText(
                if (s.perfect) "Exactly the plan." else "${percent(s.fraction)} right",
                style = MuType.body(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                color = c.ink,
            )
            if (!s.perfect) {
                Small(
                    listOfNotNull(
                        s.outMissed.takeIf { it > 0 }?.let { "$it to side out you kept" },
                        s.outWrong.takeIf { it > 0 }?.let { "$it sided out that the plan keeps" },
                        s.inMissed.takeIf { it > 0 }?.let { "$it to bring in you left" },
                        s.inWrong.takeIf { it > 0 }?.let { "$it brought in that the plan leaves" },
                    ).joinToString(" · "),
                    color = c.ink70,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Copies("The plan: out", drill.plan.out, state, strong = false, Modifier.weight(1f))
                Copies("The plan: in", drill.plan.into, state, strong = true, Modifier.weight(1f))
            }
            if (drill.plan.note.isNotBlank()) Small("Why: ${drill.plan.note}", color = c.ink)
            MuButton("Next drill", {
                current = Drill.next(plans.map { it.key }, prep.doc.drills, System.currentTimeMillis())
                picked = SidePlan()
                score = null
                left = Policy.SIDING_MINUTES * 60
            }, variant = BtnVariant.PRIMARY, arrow = true)
        }
        HRule()
        Micro("How each plan is going", color = c.ink70)
        plans.forEach { p ->
            val stat = prep.doc.drills[p.key]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small("${p.matchup.name}, ${p.turn.title.lowercase()}", Modifier.weight(1f), color = c.ink)
                Meter(stat?.box ?: 0, Drill.TOP_BOX, Modifier.width(64.dp))
                Mono(stat?.let { "${it.correct}/${it.seen}" } ?: "new", Modifier.width(48.dp), color = c.ink70)
            }
        }
    }
}

/** A plan's copies as small pictures, a picture per copy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Copies(title: String, ids: List<CardId>, state: DeckBuilderState, strong: Boolean, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro(title, color = c.ink70)
        if (ids.isEmpty()) Small("Nothing", color = c.ink45)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SidingMath.counted(ids).forEach { (id, n) ->
                val card = state.index.byId(id) ?: return@forEach
                repeat(n) {
                    Box(Modifier.width(56.dp).aspectRatio(CARD_RATIO).border(if (strong) 2.dp else 1.dp, if (strong) c.ink else c.ink45)) {
                        NeueCard(card, Modifier.fillMaxSize().padding(if (strong) 2.dp else 1.dp), foil = "off", dimmed = !strong)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Decklist

@Composable
private fun DecklistTab(prep: Prep, event: PrepEvent, webs: Webs, mine: StoredDeck?, state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val profile = prep.doc.profile
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Help("Our own sheet with every field a KDE-US decklist asks for, printed before the event: there are no printers there. Names are written out in full; an abbreviation on a list is a Deck Error waiting to happen.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Your name", hint = "as on your ID")
                MuInput(profile.name, { prep.profile(profile.copy(name = it)) }, Modifier.fillMaxWidth())
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("CARD GAME ID")
                MuInput(profile.cardGameId, { prep.profile(profile.copy(cardGameId = it.filter(Char::isDigit).take(12))) }, Modifier.fillMaxWidth(), mono = true)
            }
            Column(Modifier.weight(0.6f)) {
                FieldLabel("Country")
                MuInput(profile.country, { prep.profile(profile.copy(country = it)) }, Modifier.fillMaxWidth())
            }
        }
        if (mine == null) {
            Small("Choose your deck on Plan to make its decklist.", color = c.ink45)
            return@Column
        }
        val deck = webs.deckOf(mine, state)
        val content = remember(deck, profile, event, state.index) { Decklists.content(deck, mine.entry.name, state.index::byId, profile, event) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton(if (making) "Making the decklist…" else "Decklist · PDF", {
                making = true
                scope.launch {
                    try {
                        val bytes = DecklistPdf.write(content)
                        com.kaiharimoto.neue.platform.deliverFile("${mine.entry.name} · decklist.pdf", "application/pdf", bytes)?.let { neue.note = Note(it) }
                    } catch (e: Exception) {
                        neue.note = Note("The decklist could not be made")
                    } finally {
                        making = false
                    }
                }
            }, variant = BtnVariant.PRIMARY, icon = Icons.Export, enabled = !making, reason = "The decklist is being made")
            MuButton("Copy as text", {
                com.kaiharimoto.neue.platform.Platform.copy(DecklistSheet.plainText(content))
                neue.note = Note("The decklist is on the clipboard")
            }, variant = BtnVariant.SECONDARY, icon = Icons.Copy)
        }
        if (profile.name.isBlank() || profile.cardGameId.isBlank()) Small("Your name and CARD GAME ID are printed on the sheet: fill them in above, or write them in by hand.", color = c.ink45)
        DecklistPreview(content)
    }
}

@Composable
private fun DecklistPreview(content: DecklistSheet.Content) {
    val c = Mu.colors
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(16.dp)) {
        val columns = listOf(
            "Monster" to content.monsters,
            "Spell" to content.spells,
            "Trap" to content.traps,
            "Side Deck" to content.side,
            "Extra Deck" to content.extra,
        )
        val perRow = if (maxWidth >= 900.dp) 5 else if (maxWidth >= 520.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MuText("${content.deckName} · Main Deck ${content.mainTotal}", style = MuType.body(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold), color = c.ink)
            columns.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (title, lines) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row { Micro(title, Modifier.weight(1f), color = c.ink70); Mono(lines.sumOf { it.count }.toString(), color = c.ink70) }
                            HRule(strong = true)
                            if (lines.isEmpty()) Small("None", color = c.ink45)
                            lines.forEach { l ->
                                Row {
                                    Mono(l.count.toString(), Modifier.width(16.dp), color = c.ink)
                                    Small(l.name, color = c.ink, maxLines = 2)
                                }
                            }
                        }
                    }
                    repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- The day

private val MATCH_RESULTS = listOf("2–0" to TestGame.WIN, "2–1" to TestGame.WIN, "1–2" to TestGame.LOSS, "0–2" to TestGame.LOSS, "Draw" to TestGame.DRAW, "Time" to TestGame.LOSS)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayTab(prep: Prep, event: PrepEvent, webs: Webs, web: DeckWeb?, library: List<StoredDeck>, state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val mine = library.firstOrNull { it.entry.id == event.deckId }
    val rounds = prep.doc.games.filter { it.eventId == event.id && it.round != null }.sortedBy { it.round }
    var against by remember { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 900.dp
        val list: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro("Bring and do", color = c.ink70)
                Checklist.of(event).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MuCheckbox(item.id in event.checked, { prep.check(event, item.id, it) })
                        Small(item.label, color = if (item.id in event.checked) c.ink45 else c.ink)
                    }
                }
            }
        }
        val log: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Micro("Rounds", color = c.ink70)
                val wins = rounds.count { it.result == TestGame.WIN }
                val losses = rounds.count { it.result == TestGame.LOSS }
                val draws = rounds.count { it.result == TestGame.DRAW }
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Stat("Record", "$wins-$losses" + if (draws > 0) "-$draws" else "")
                    if (event.attendance > 0) {
                        val swiss = Policy.swiss(event.tier, event.attendance)
                        Stat("Rounds", "${rounds.size} of ${swiss.rounds}")
                    }
                }
                if (event.attendance > 0) Small(Policy.cutRecord(Policy.swiss(event.tier, event.attendance), event.attendance) + ".", color = c.ink70)
                rounds.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mono("R${r.round}", Modifier.width(36.dp), color = c.ink70)
                        Mono(r.result, Modifier.width(22.dp), color = c.ink)
                        Small(listOf(r.opponentName, r.note).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f), color = c.ink)
                        com.kaiharimoto.neue.kit.IconButton(Icons.X, { prep.removeGame(r.id) }, size = 28.dp, label = "Remove")
                    }
                }
                val next = (rounds.maxOfOrNull { it.round ?: 0 } ?: 0) + 1
                FieldLabel("Round $next", hint = "report it within ${Policy.REPORT_MINUTES} minutes")
                val field = foes(web, library, mine)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.forEach { f -> Tag(f.name, against == f.name, { against = if (against == f.name) "" else f.name }, caption = "Against") }
                }
                MuInput(against, { against = it }, Modifier.widthIn(max = 360.dp).fillMaxWidth(), placeholder = "Their deck")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MATCH_RESULTS.forEach { (label, result) ->
                        MuButton(label, {
                            val key = field.firstOrNull { it.name == against }?.key ?: against.trim()
                            prep.log(
                                TestGame(
                                    prep.newId("r"), System.currentTimeMillis(), event.deckId, key, against.trim().ifBlank { "Unknown" }, "", game = 1,
                                    result = result, reason = if (label == "Time") TestGame.REASON_TIME else null, note = label, eventId = event.id, round = next,
                                ),
                            )
                            against = ""
                            neue.note = Note("Round $next: $label. Restore the deck to its registered list before the next.")
                        }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
                    }
                }
                Help("After each round, take the side cards back out: the deck must match its registered list at the start of every match.")
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                list(Modifier.weight(1f))
                log(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                list(Modifier.fillMaxWidth())
                log(Modifier.fillMaxWidth())
            }
        }
    }
}
