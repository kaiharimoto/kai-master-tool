package com.kaiharimoto.neue.present

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.modules.ModuleInput
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.modules.Pick
import com.kaiharimoto.mastertool.core.present.modules.Shoutout
import com.kaiharimoto.mastertool.core.present.modules.SideMatchup
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuCheckbox
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.platform.PICTURE_EXTENSIONS
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.pastedPicture
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch

/**
 * A module made into slides (1.0.71): what it reads gathered from the app, what it needs picked
 * by hand asked for here, and the slides it makes put after the one in view — ordinary slides,
 * every part editable, with Refresh on the Slide tab to make them again from fresh data.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModuleDialog(h: NeueHolders) {
    val present = h.present
    val type = present.addingModule ?: return
    val p = present.open ?: return
    val scope = rememberCoroutineScope()
    val c = Mu.colors
    var input by remember(type) { mutableStateOf(ModuleInput()) }
    var matchups by remember(type) { mutableStateOf<List<SideMatchup>>(emptyList()) }
    var chosen by remember(type) { mutableStateOf<Set<String>>(emptySet()) }
    var events by remember(type) { mutableStateOf<List<PrepEvent>>(emptyList()) }
    var event by remember(type) { mutableStateOf<PrepEvent?>(null) }
    var ready by remember(type) { mutableStateOf(false) }
    LaunchedEffect(type) {
        when (type) {
            Modules.SIDING -> {
                matchups = ModuleData.matchups(h, p)
                chosen = matchups.map { it.name }.toSet()
            }
            Modules.TOURNAMENT -> {
                events = ModuleData.events(h)
                event = events.firstOrNull()
            }
            else -> input = ModuleData.gather(h, p, type, input)
        }
        ready = true
    }

    fun make() {
        scope.launch {
            val base = when (type) {
                Modules.SIDING -> input.copy(params = mapOf("matchups" to chosen.joinToString("\n")))
                Modules.TOURNAMENT -> input.copy(params = mapOf("event" to (event?.id ?: "")))
                else -> input
            }
            val gathered = ModuleData.gather(h, p, type, base)
            val slides = Modules.generate(type, gathered, System.currentTimeMillis())
            val o = present.open ?: return@launch
            var next = o
            var after = present.slideIndex
            slides.forEach { s -> next = PresentEdits.addSlide(next, s, after); after++ }
            present.commit(next, "Add ${Modules.name(type).lowercase()}")
            slides.firstOrNull()?.let { present.slideId = it.id }
            present.selection = emptySet()
            present.addingModule = null
        }
    }

    MuDialog(
        Modules.name(type),
        { present.addingModule = null },
        width = 760.dp,
        description = Modules.line(type),
        footer = {
            MuButton("Cancel", { present.addingModule = null }, variant = BtnVariant.GHOST)
            MuButton("Add to the presentation", ::make, variant = BtnVariant.PRIMARY, enabled = ready && canMake(type, input, chosen, event), reason = whyNot(type, input))
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Title", hint = "optional")
            MuInput(input.title, { input = input.copy(title = it) }, Modifier.fillMaxWidth(), placeholder = Modules.name(type))
            when (type) {
                Modules.SIDING -> {
                    if (p.deck?.deckId == null) Help("This presentation's deck was never saved, so it has no siding plans.")
                    else if (ready && matchups.isEmpty()) Help("No siding plans yet. Side the deck on 03 Siding, then add this module.")
                    matchups.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MuCheckbox(m.name in chosen, { on -> chosen = if (on) chosen + m.name else chosen - m.name })
                            Small("vs ${m.name} · going first ${m.first.out.size} out, going second ${m.second.out.size} out", maxLines = 1)
                        }
                    }
                    Help("One slide per matchup: what comes out and goes in, going first and going second, with why.")
                }
                Modules.MATCHUPS -> {
                    if (ready && input.rows.isEmpty()) Help("No practice games logged for this deck yet: log them on 05 Prep, then add this module.")
                    else Small("${input.rows.size} opponents, ${input.rows.sumOf { it.games }} games" + (input.expected?.let { " · ${(it * 100).toInt()}% match win to expect" } ?: ""))
                }
                Modules.TOURNAMENT -> {
                    if (ready && events.isEmpty()) Help("No events yet: add one on 05 Prep and log its rounds on The day.")
                    else MuSelect(event, events, { it?.let { e -> "${e.name} · ${e.date}" } ?: "None" }, { event = it }, Modifier.fillMaxWidth())
                    FieldLabel("Where it finished", hint = "optional")
                    MuInput(input.placement, { input = input.copy(placement = it) }, Modifier.fillMaxWidth(), placeholder = "Top 8 · 1st after Swiss")
                }
                Modules.PERFORMERS -> {
                    Help("Click a card once for Strong (S), again for Weak (W), a third time to leave it out. ✕ beside a card below takes it off.")
                    DeckPicker(h, p, { id -> when { input.strong.any { it.card == id } -> "S"; input.weak.any { it.card == id } -> "W"; else -> null } }) { id ->
                        input = when {
                            input.strong.any { it.card == id } -> input.copy(strong = input.strong.filterNot { it.card == id }, weak = input.weak + Pick(id))
                            input.weak.any { it.card == id } -> input.copy(weak = input.weak.filterNot { it.card == id })
                            else -> input.copy(strong = input.strong + Pick(id))
                        }
                    }
                    PickNotes(h, "Strong", input.strong, ordered = false) { input = input.copy(strong = it) }
                    PickNotes(h, "Weak", input.weak, ordered = false) { input = input.copy(weak = it) }
                }
                Modules.TECH, Modules.COMBO -> {
                    Help(if (type == Modules.COMBO) "Click the cards in the order they are played; a card can be played twice. Reorder or take a step off below." else "Click the cards to explain; click again to take one off.")
                    DeckPicker(h, p, { id -> input.picks.indexOfFirst { it.card == id }.takeIf { it >= 0 }?.let { "${it + 1}" } }) { id ->
                        input = if (input.picks.any { it.card == id } && type == Modules.TECH) input.copy(picks = input.picks.filterNot { it.card == id }) else input.copy(picks = input.picks + Pick(id))
                    }
                    PickNotes(h, if (type == Modules.COMBO) "Steps" else "Why each", input.picks, ordered = type == Modules.COMBO) { input = input.copy(picks = it) }
                }
                Modules.SHOUTOUTS -> {
                    input.shoutouts.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MuButton(if (s.media == null) "Logo…" else "Logo ✓", {
                                scope.launch {
                                    val picked = Platform.pick("A logo or picture", PICTURE_EXTENSIONS) ?: return@launch
                                    val name = present.putMedia(picked.bytes, picked.extension)
                                    input = input.copy(shoutouts = input.shoutouts.toMutableList().also { it[i] = s.copy(media = name) })
                                }
                            }, size = BtnSize.SM)
                            MuButton("Paste", {
                                scope.launch {
                                    val picked = pastedPicture() ?: return@launch
                                    val name = present.putMedia(picked.bytes, picked.extension)
                                    input = input.copy(shoutouts = input.shoutouts.toMutableList().also { it[i] = s.copy(media = name) })
                                }
                            }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                            MuInput(s.name, { v -> input = input.copy(shoutouts = input.shoutouts.toMutableList().also { it[i] = s.copy(name = v) }) }, Modifier.weight(1f), dense = true, placeholder = "Name")
                            MuInput(s.handle, { v -> input = input.copy(shoutouts = input.shoutouts.toMutableList().also { it[i] = s.copy(handle = v) }) }, Modifier.weight(1f), dense = true, placeholder = "@handle")
                            IconButton(Icons.X, { input = input.copy(shoutouts = input.shoutouts.filterIndexed { k, _ -> k != i }) }, label = "Remove")
                        }
                        MuInput(s.line, { v -> input = input.copy(shoutouts = input.shoutouts.toMutableList().also { it[i] = s.copy(line = v) }) }, Modifier.fillMaxWidth(), dense = true, placeholder = "What for")
                    }
                    MuButton("Someone to thank", { input = input.copy(shoutouts = input.shoutouts + Shoutout()) }, size = BtnSize.SM, variant = BtnVariant.GHOST, icon = Icons.Plus, enabled = input.shoutouts.size < 6, reason = "Six fit on a slide")
                }
                Modules.ODDS, Modules.RATIOS -> {
                    if (p.deck?.groups.isNullOrEmpty()) Help("The deck has no groups. Give it groups on the builder (K), then refresh the deck on the Deck tab.")
                    input.odds.forEach { g -> Small("${g.name}: ${g.count} cards · ${(g.opening * 100).toInt()}% to open one going first") }
                }
                Modules.GET_THE_DECK -> Help("A QR code of the whole deck as a ydke:// code, which Neue Master Tool's Import and the simulators read.")
                Modules.DECKLIST -> Help("The whole deck on one slide, drawn as the Deck tab tells it.")
            }
        }
    }
}

private fun canMake(type: String, input: ModuleInput, chosen: Set<String>, event: PrepEvent?): Boolean = when (type) {
    Modules.SIDING -> chosen.isNotEmpty()
    Modules.TOURNAMENT -> event != null
    Modules.PERFORMERS -> input.strong.isNotEmpty() || input.weak.isNotEmpty()
    Modules.TECH, Modules.COMBO -> input.picks.isNotEmpty()
    Modules.SHOUTOUTS -> input.shoutouts.any { it.name.isNotBlank() || it.media != null }
    // A data module with nothing to show is refused, as Ai's add_module refuses it (I3).
    else -> Modules.missing(type, input) == null
}

private fun whyNot(type: String, input: ModuleInput): String = when (type) {
    Modules.SIDING -> "Choose a matchup"
    Modules.TOURNAMENT -> "Choose an event"
    Modules.SHOUTOUTS -> "Add someone"
    Modules.PERFORMERS, Modules.TECH, Modules.COMBO -> "Pick a card"
    else -> Modules.missing(type, input) ?: "Nothing to show yet"
}

/** The presentation's deck, each card once, marked by [mark] and clicked by [onClick]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckPicker(h: NeueHolders, p: Presentation, mark: (Int) -> String?, onClick: (Int) -> Unit) {
    val c = Mu.colors
    val deck = p.deck ?: run { Help("This presentation has no deck."); return }
    // Each card with its name under it (I3): at 52 dp and faded, a picker of art alone was a guessing game.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        deck.distinct.forEach { id ->
            val card = h.builder.index.byId(CardId(id)) ?: return@forEach
            val m = mark(id)
            Column(
                Modifier.width(72.dp)
                    .cursorPointer(label = card.name)
                    .muClickable { onClick(id) },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(59f / 86f)
                        .border(if (m != null) 2.dp else 1.dp, if (m != null) c.ink else c.ink12)
                        .graphicsLayer { alpha = if (m != null) 1f else 0.82f },
                ) {
                    NeueCard(card, Modifier.fillMaxSize(), format = h.builder.format, foil = "off")
                    if (m != null) {
                        Box(Modifier.align(Alignment.TopStart).background(c.paper).border(1.dp, c.ink).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            Micro(m, color = c.ink)
                        }
                    }
                }
                Help(card.name, color = if (m != null) c.ink else c.ink70, maxLines = 2)
            }
        }
    }
}

/**
 * A line of words per pick, in order, each with ✕ to take it off and — when the order is the point
 * ([ordered], a combo's steps) — ↑ and ↓ to move it (I3: a wrong click used to mean Cancel and start again).
 */
@Composable
private fun PickNotes(h: NeueHolders, label: String, picks: List<Pick>, ordered: Boolean, onChange: (List<Pick>) -> Unit) {
    if (picks.isEmpty()) return
    FieldLabel(label)
    picks.forEachIndexed { i, pk ->
        val name = h.builder.index.byId(CardId(pk.card))?.name ?: "Card"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ordered) Micro("${i + 1}", Modifier.width(18.dp), color = Mu.colors.ink70)
            Small(name, Modifier.width(200.dp), maxLines = 1)
            MuInput(pk.note, { v -> onChange(picks.toMutableList().also { it[i] = pk.copy(note = v) }) }, Modifier.weight(1f), dense = true, placeholder = "Why")
            if (ordered) {
                IconButton(Icons.ArrowUp, { onChange(moved(picks, i, -1)) }, enabled = i > 0, label = "Earlier", reason = "Already first")
                IconButton(Icons.ArrowDown, { onChange(moved(picks, i, 1)) }, enabled = i < picks.lastIndex, label = "Later", reason = "Already last")
            }
            IconButton(Icons.X, { onChange(picks.filterIndexed { k, _ -> k != i }) }, label = "Take off")
        }
    }
}

private fun moved(picks: List<Pick>, i: Int, by: Int): List<Pick> {
    val to = (i + by).coerceIn(0, picks.lastIndex)
    if (to == i) return picks
    return picks.toMutableList().also { val p = it.removeAt(i); it.add(to, p) }
}
