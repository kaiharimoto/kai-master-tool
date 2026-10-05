package com.kaiharimoto.neue.builder

import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckRules
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.text.Dates
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Selection
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * The rules the builder checks a deck against (1.1.1, Phase B): the person's choice of day and of Genesys, turned into
 * [DeckRules] with the list in force that day from the banlist history. A day whose lists cannot be read is checked
 * on its release dates with the pool's list, and says so — never silently today's.
 */
suspend fun NeueHolders.legalityRules(p: NeuePreferences, format: Format): DeckRules {
    val day = p.legalAsOf.takeIf { Legality.isDate(it) }
    if (p.genesys) return DeckRules(format, day, genesysCap = p.genesysCap)
    if (day == null) return DeckRules(format)
    val got = banlists.ensure(format)
    val history = got.history
        ?: return DeckRules(format, day, note = "The ${format.name} lists could not be read, so copies are held to the pool's current list. ${got.problem.orEmpty()}".trim())
    val list = history.asOf(day)
        ?: return DeckRules(format, day, note = "No ${format.name} list was in force on ${Legality.readable(day)}, so copies are held to the pool's current list.")
    return DeckRules(format, day, list.match(builder.index::byName), list.title)
}

/** The event a day is most likely wanted for: the one being prepared for, else the next one, when its date is a day. */
fun eventForRules(events: List<PrepEvent>, active: PrepEvent?, today: String): PrepEvent? =
    (active ?: events.filter { it.date >= today }.minByOrNull { it.date })?.takeIf { Legality.isDate(it.date) }

/** The card [id] picked out in the deck and the drawer put away: what a Show beside a card's line does. */
internal fun showInDeck(state: DeckBuilderState, neue: NeueState, id: CardId, section: DeckSection? = null) {
    val at = section ?: DeckSection.entries.firstOrNull { id in state.deck[it] } ?: return
    val index = state.deck[at].indexOf(id)
    val card = state.index.byId(id)
    if (index >= 0 && card != null) neue.selection = Selection.InDeck(card, at, index)
    neue.drawer = null
}

private enum class DayMode { TODAY, DAY, EVENT }

/**
 * The top of the Legality drawer: what the deck is checked against. The Forbidden & Limited list as of a day — today,
 * a day typed, or the day of the event Prep is preparing for (the 1.1.2 design review, finding 6) — or Genesys under a
 * points cap, its points drawn as a bar with the dearest cards under it (finding 5). A day is kept only once it is a
 * whole date, so typing never checks a half-written one; it may be typed without dashes, which a phone's keyboard
 * keeps a page away.
 */
@Composable
internal fun RulesPicker(state: DeckBuilderState, neue: NeueState, event: PrepEvent? = null) {
    val p = neue.prefs
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Check against")
        Segmented(p.genesys, listOf(false, true), { if (it) "Genesys" else "Forbidden & Limited" }, { on ->
            neue.update { it.copy(genesys = on) }
        }, small = true)
        if (p.genesys) {
            GenesysCapField(neue, Modifier.padding(top = 8.dp))
            GenesysPoints(state, neue)
        }
        DayPicker(state, neue, event)
    }
}

/** The Genesys points cap, typed: kept once it is a number in range. The drawer's, and the setup's "What do you play?". */
@Composable
internal fun GenesysCapField(neue: NeueState, modifier: Modifier = Modifier) {
    val p = neue.prefs
    var cap by remember { mutableStateOf(p.genesysCap.toString()) }
    FieldLabel("Points cap", modifier, hint = "100 unless the event sets another")
    MuInput(cap, { v ->
        cap = v.filter(Char::isDigit).take(4)
        cap.toIntOrNull()?.takeIf { it in NeuePreferences.MIN_GENESYS_CAP..NeuePreferences.MAX_GENESYS_CAP }
            ?.let { n -> neue.update(debounce = true) { it.copy(genesysCap = n) } }
    }, Modifier.width(120.dp), placeholder = "100", mono = true)
}

/** The deck's Genesys points against the cap, as the kit's bar, and the five cards that cost the most. */
@Composable
private fun GenesysPoints(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val r = remember(state.deck, state.rules, state.index) { state.rulesInForce.points(state.deck, state.index::byId) } ?: return
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Micro("Points", Modifier.weight(1f))
            Mono(
                "${r.points} / ${r.cap} · " + if (r.over > 0) "${r.over} over" else "${r.cap - r.points} left",
                color = if (r.over > 0) c.ink else c.ink70,
            )
        }
        Progress(if (r.cap > 0) r.points.toFloat() / r.cap else 1f)
        if (r.unknown.isNotEmpty()) {
            Small("${r.unknown.size} card${if (r.unknown.size == 1) "" else "s"} with no points known, counted as 0.", color = c.ink45)
        }
        val dearest = r.costs.take(5)
        if (dearest.isNotEmpty()) {
            Micro("Costliest", Modifier.padding(top = 6.dp), color = c.ink45)
            dearest.forEach { cost ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RowText(cost.name, Modifier.weight(1f), color = c.ink)
                    // "3 × 20 = 60": what each copy costs and what they cost together, never one number for both.
                    Mono(if (cost.copies > 1) "${cost.copies} × ${cost.each} = ${cost.total}" else "${cost.total}", color = c.ink70)
                    MuButton("Show", { showInDeck(state, neue, cost.id) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                }
            }
        }
    }
}

/**
 * "Lists and cards as of": Today, A day, or the event's day. The field shows only for A day, with the day it read in
 * words under it, so the date is never in two shapes at opposite edges.
 */
@Composable
private fun DayPicker(state: DeckBuilderState, neue: NeueState, event: PrepEvent?) {
    val c = Mu.colors
    val p = neue.prefs
    val eventDay = event?.date
    var picking by remember { mutableStateOf(false) }
    val mode = when {
        p.legalAsOf.isBlank() -> if (picking) DayMode.DAY else DayMode.TODAY
        eventDay != null && p.legalAsOf == eventDay && !picking -> DayMode.EVENT
        else -> DayMode.DAY
    }
    var typed by remember { mutableStateOf(p.legalAsOf.takeIf { mode == DayMode.DAY }.orEmpty()) }
    // Android picks a day with its own date picker (1.1.8, finding 6, kai's choice); the desk types it.
    val scope = rememberCoroutineScope()
    val dark = p.theme == NeueTheme.INK
    fun pickDay() {
        scope.launch {
            val day = Platform.pickDay(p.legalAsOf.ifBlank { null }, dark) ?: return@launch
            typed = day
            neue.update { it.copy(legalAsOf = day) }
        }
    }
    FieldLabel("Lists and cards as of", Modifier.padding(top = 8.dp))
    val modes = listOfNotNull(DayMode.TODAY, DayMode.DAY, DayMode.EVENT.takeIf { eventDay != null })
    Segmented(mode, modes, { m ->
        when (m) {
            DayMode.TODAY -> "Today"
            DayMode.DAY -> "A day"
            DayMode.EVENT -> "Event · ${Dates.day(eventDay.orEmpty())}"
        }
    }, { m ->
        when (m) {
            DayMode.TODAY -> {
                picking = false
                neue.update { it.copy(legalAsOf = "") }
            }
            DayMode.DAY -> {
                picking = true
                typed = p.legalAsOf
                if (Platform.picksDays) pickDay()
            }
            DayMode.EVENT -> {
                picking = false
                neue.update { it.copy(legalAsOf = eventDay.orEmpty()) }
            }
        }
    }, small = true)
    if (mode == DayMode.DAY && Platform.picksDays) {
        // The day in words, and the platform's picker to change it: never a field of dashes on a phone's keyboard.
        val read = Dates.parseDay(p.legalAsOf)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small(read?.let(Dates::day) ?: "No day chosen yet.", color = if (read != null) c.ink else c.ink45)
            MuButton(if (read != null) "Change" else "Choose a day", { pickDay() }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
        }
    } else if (mode == DayMode.DAY) {
        MuInput(typed, { v ->
            typed = v.take(10)
            val day = Dates.parseDay(typed)
            if (day != null) neue.update(debounce = true) { it.copy(legalAsOf = day) }
        }, Modifier.width(160.dp), placeholder = "yyyy-mm-dd", mono = true)
        val read = Dates.parseDay(typed)
        Small(
            when {
                read != null -> Dates.day(read)
                typed.isBlank() -> "Type a day: 2025-05-01, or 20250501."
                else -> "Not a day yet: 2025-05-01, or 20250501."
            },
            color = if (read != null) c.ink else c.ink45,
        )
    }
    val rules = state.rulesInForce
    Small(
        when {
            rules.genesys -> "No Forbidden & Limited list; TCG cards released by then; no Link or Pendulum monsters."
            rules.listName != null -> "The ${rules.listName}, from Yugipedia (CC BY-SA), and the cards released by then."
            rules.asOf != null -> "The cards released by then."
            else -> "The current Forbidden & Limited List, and the cards released so far."
        },
        color = c.ink45,
    )
}
