package com.kaiharimoto.neue.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.TuneIntensity
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * Fine Tuning's two ways in (1.0.48, kai: "two options for the user: 1) have the user teach
 * it, and 2) have the AI teach itself by reading the cards and going online. The user can set
 * the intensity of how long it takes the AI and how much effort"). Both are about the deck
 * open in the builder, and both write its guide (`MemoryKind.GUIDE`), which the person sees
 * change by change before it is kept.
 */
@Composable
fun TuneLauncher(ai: AiState) {
    if (!ai.tuneAsk) return
    val c = Mu.colors
    val deck = ai.h.builder.deckName
    val saved = ai.h.builder.deckId != null
    var study by remember { mutableStateOf(false) }
    var intensity by remember { mutableStateOf(TuneIntensity.of(ai.prefs.tuneIntensity)) }
    MuDialog(
        title = "Fine Tuning · $deck",
        onDismiss = { ai.tuneAsk = false },
        width = 560.dp,
        description = "Two ways for ${ai.name} to learn how this deck plays. What it learns goes into the deck's guide, and you see every change before it is kept.",
        footer = {
            MuButton("Cancel", { ai.tuneAsk = false }, variant = BtnVariant.GHOST)
            MuButton(
                if (study) "Start studying" else "Start teaching",
                { ai.startTuning(study, intensity) },
                variant = BtnVariant.PRIMARY,
                arrow = true,
                enabled = saved && ai.configured,
                reason = if (!saved) "Save the deck first" else "Set up ${ai.name} first",
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice(
                "I'll teach you",
                "${ai.name} asks about the deck one question at a time — the plan, the lines, what each card is for, what you fear — " +
                    "and writes down what you say. You know things no list online does.",
                selected = !study,
            ) { study = false }
            Choice(
                "Study it yourself",
                "${ai.name} reads every card, the archetype's page and how recent tournament lists build it, works out the lines " +
                    "and thinks out loud as it goes, so you learn with it. Then it asks what it could not settle.",
                selected = study,
            ) { study = true }
            Micro("Intensity", color = c.ink45)
            Segmented(intensity, TuneIntensity.entries, { it.label }, { intensity = it })
            Small(if (study) intensity.studyTime else intensity.teachTime, color = c.ink)
            Help(if (study) intensity.studies else "About ${intensity.questions} questions, one at a time; stop whenever you like.", color = c.ink70)
            if (!saved) Help("Save “$deck” first: the guide belongs to a saved deck.", color = c.ink)
        }
    }
}
