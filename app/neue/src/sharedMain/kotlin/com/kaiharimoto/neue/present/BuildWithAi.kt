package com.kaiharimoto.neue.present

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.present.ai.PresentBrief
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.theme.Mu

/**
 * Build with Ai (1.0.71): how long the video runs, its tone, the modules and whether Ai writes
 * the script — then a Present conversation that builds the open presentation from it, slide by
 * slide, each one checked. Everything Ai makes is one step of Undo at a time, and editable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuildWithAiDialog(h: NeueHolders) {
    val present = h.present
    if (!present.briefing) return
    val p = present.open ?: run { present.briefing = false; return }
    val ai = h.ai
    var length by remember { mutableStateOf(PresentBrief.STANDARD) }
    var tone by remember { mutableStateOf(PresentBrief.TONE_TEACHING) }
    var modules by remember { mutableStateOf(setOf<String>()) }
    var script by remember { mutableStateOf(true) }
    var extra by remember { mutableStateOf("") }
    val c = Mu.colors

    fun go() {
        present.briefing = false
        ai.buildPresentation(
            PresentBrief(
                presentationId = p.id,
                deckId = p.deck?.deckId,
                deckName = p.deck?.name.orEmpty(),
                style = p.style,
                length = length,
                tone = tone,
                modules = PresentBrief.OFFERED.filter { it in modules },
                script = script,
                extra = extra,
            ),
        )
    }

    MuDialog(
        "Build with ${ai.name}",
        { present.briefing = false },
        width = 620.dp,
        description = "${ai.name} reads the deck, its siding and its record, then fills these slides in and checks each one. Every change is yours to undo or edit.",
        footer = {
            MuButton("Cancel", { present.briefing = false }, variant = BtnVariant.GHOST)
            MuButton("Build it", { go() }, variant = BtnVariant.PRIMARY)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("How long the video runs")
            Segmented(length, PresentBrief.LENGTHS, { "${PresentBrief.lengthName(it)} · ${PresentBrief.lengthLine(it)}" }, { length = it }, Modifier.fillMaxWidth())
            FieldLabel("Tone")
            Segmented(tone, PresentBrief.TONES, PresentBrief::toneName, { tone = it }, Modifier.fillMaxWidth())
            FieldLabel("Modules", hint = "made from the app's own data where it has it")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PresentBrief.OFFERED.forEach { t ->
                    Tag(Modules.name(t), t in modules, { modules = if (t in modules) modules - t else modules + t })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MuSwitch(script, { script = it })
                Column {
                    Small("Write my script", color = c.ink)
                    Small(if (script) "In each slide's speaker notes, fitted to the length" else "A few cue words per slide; you talk freely", color = c.ink45)
                }
            }
            FieldLabel("Anything else", hint = "optional")
            MuInput(extra, { extra = it }, Modifier.fillMaxWidth(), placeholder = "Say the deck topped a regional; keep the combo short")
        }
    }
}
