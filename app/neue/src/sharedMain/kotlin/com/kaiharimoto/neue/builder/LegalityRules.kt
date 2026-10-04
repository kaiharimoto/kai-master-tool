package com.kaiharimoto.neue.builder

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
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * The rules the builder checks a deck against (1.1.1, Phase B): the person's choice of day and of Genesys, turned into
 * [DeckRules] with the list in force that day from the banlist history. A day whose lists cannot be read is checked
 * on its release dates with the pool's list, and says so — never silently today's.
 */
internal suspend fun NeueHolders.legalityRules(p: NeuePreferences, format: Format): DeckRules {
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

/**
 * The top of the Issues drawer: what the deck is checked against. The Forbidden & Limited list on a day (blank is
 * today), or Genesys under a points cap. A day is kept only once it is a whole date, so typing never checks a
 * half-written one.
 */
@Composable
internal fun RulesPicker(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val p = neue.prefs
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FieldLabel("Check against")
        Segmented(p.genesys, listOf(false, true), { if (it) "Genesys" else "Forbidden & Limited" }, { on ->
            neue.update { it.copy(genesys = on) }
        }, small = true)
        if (p.genesys) {
            var cap by remember { mutableStateOf(p.genesysCap.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel("Points cap", hint = "100 unless the event sets another")
                MuInput(cap, { v ->
                    cap = v.filter(Char::isDigit).take(4)
                    cap.toIntOrNull()?.takeIf { it in NeuePreferences.MIN_GENESYS_CAP..NeuePreferences.MAX_GENESYS_CAP }
                        ?.let { n -> neue.update(debounce = true) { it.copy(genesysCap = n) } }
                }, Modifier.width(88.dp), placeholder = "100", mono = true)
            }
            state.rulesInForce.points(state.deck, state.index::byId)?.let { r ->
                Small(
                    "${r.points} of ${r.cap} points" + if (r.unknown.isNotEmpty()) " · ${r.unknown.size} card${if (r.unknown.size == 1) "" else "s"} with no points known, counted as 0" else "",
                    color = if (r.points > r.cap) c.ink else c.ink70,
                )
            }
        }
        var day by remember { mutableStateOf(p.legalAsOf) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("On", hint = if (day.isBlank()) "today" else if (Legality.isDate(day)) Legality.readable(day) else "yyyy-mm-dd")
            MuInput(day, { v ->
                day = v.trim().take(10)
                if (day.isEmpty() || Legality.isDate(day)) neue.update(debounce = true) { it.copy(legalAsOf = day) }
            }, Modifier.width(132.dp), placeholder = "today", mono = true)
            if (day.isNotEmpty()) {
                MuButton("Today", {
                    day = ""
                    neue.update { it.copy(legalAsOf = "") }
                }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
        }
        val rules = state.rulesInForce
        Small(
            when {
                rules.genesys -> "No Forbidden & Limited list; TCG cards released by the day; no Link or Pendulum monsters."
                rules.listName != null -> "The ${rules.listName}, from Yugipedia (CC BY-SA), and the cards released by then."
                rules.asOf != null -> "The cards released by then."
                else -> "Today's list and the cards released by today."
            },
            color = c.ink45,
        )
    }
}
