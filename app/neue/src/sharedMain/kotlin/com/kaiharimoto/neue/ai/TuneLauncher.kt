package com.kaiharimoto.neue.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.AiSession
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
 * Fine Tuning's three ways in (1.0.54 adds the third, kai: "a 'Learn this deck from first
 * principles' which studies without looking online for guides and focuses/refines on the goals of
 * the deck and how the cards pair, interact, and connect with each other"). The first two: (1.0.48, kai: "two options for the user: 1) have the user teach
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
    var mode by remember { mutableStateOf(AiSession.MODE_TUNE) }
    val study = mode != AiSession.MODE_TUNE
    var intensity by remember { mutableStateOf(TuneIntensity.of(ai.prefs.tuneIntensity)) }
    MuDialog(
        title = "Fine Tuning · $deck",
        onDismiss = { ai.tuneAsk = false },
        width = 560.dp,
        description = "Three ways for ${ai.name} to learn how this deck plays. What it learns goes into the deck's guide — a document it keeps across sessions — and each session ends with a report and its confidence, as a PDF.",
        footer = {
            MuButton("Cancel", { ai.tuneAsk = false }, variant = BtnVariant.GHOST)
            MuButton(
                when (mode) {
                    AiSession.MODE_STUDY -> "Start studying"
                    AiSession.MODE_PRINCIPLES -> "Start learning"
                    else -> "Start teaching"
                },
                { ai.startTuning(mode, intensity) },
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
                selected = mode == AiSession.MODE_TUNE,
            ) { mode = AiSession.MODE_TUNE }
            Choice(
                "Study it yourself",
                "${ai.name} reads every card, the archetype's page and how recent tournament lists build it, works out the lines " +
                    "and thinks out loud as it goes, so you learn with it. Then it asks what it could not settle.",
                selected = mode == AiSession.MODE_STUDY,
            ) { mode = AiSession.MODE_STUDY }
            Choice(
                "Learn it from first principles",
                "${ai.name} reads only the cards and the rules — no guides, no lists, nothing online — and works out what the deck is " +
                    "trying to do and how its cards pair, interact and connect, thinking out loud as it goes.",
                selected = mode == AiSession.MODE_PRINCIPLES,
            ) { mode = AiSession.MODE_PRINCIPLES }
            Micro("Intensity", color = c.ink45)
            Segmented(intensity, TuneIntensity.entries, { it.label }, { intensity = it })
            Small(if (study) intensity.studyTime else intensity.teachTime, color = c.ink)
            Help(
                when (mode) {
                    AiSession.MODE_STUDY -> intensity.studies
                    AiSession.MODE_PRINCIPLES -> "About ${intensity.steps} rounds of reading and reasoning, from the card text alone."
                    else -> "About ${intensity.questions} questions, one at a time; stop whenever you like."
                },
                color = c.ink70,
            )
            if (!saved) Help("Save “$deck” first: the guide belongs to a saved deck.", color = c.ink)
        }
    }
}

/**
 * Learn About You's way in (1.0.54): how long an interview about the person, and a start. Not
 * about a deck, so it needs none; what it learns is their profile, read in every conversation.
 */
@Composable
fun ProfileLauncher(ai: AiState) {
    if (!ai.profileAsk) return
    val c = Mu.colors
    var intensity by remember { mutableStateOf(TuneIntensity.of(ai.prefs.tuneIntensity)) }
    MuDialog(
        title = "Learn About You",
        onDismiss = { ai.profileAsk = false },
        width = 520.dp,
        description = "${ai.name} interviews you — your goals, your preferences, how you work — and keeps what it learns as your profile, " +
            "which it reads in every conversation. It builds on it each time, and you can read or edit it whenever you like.",
        footer = {
            MuButton("Your profile", { ai.profileAsk = false; ai.openProfile() }, variant = BtnVariant.GHOST)
            MuButton("Cancel", { ai.profileAsk = false }, variant = BtnVariant.GHOST)
            MuButton("Start", { ai.startProfile(intensity) }, variant = BtnVariant.PRIMARY, arrow = true, enabled = ai.configured, reason = "Set up ${ai.name} first")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("Intensity", color = c.ink45)
            Segmented(intensity, TuneIntensity.entries, { it.label }, { intensity = it })
            Small(intensity.teachTime, color = c.ink)
            Help("About ${intensity.questions} questions, one at a time; stop whenever you like.", color = c.ink70)
        }
    }
}
