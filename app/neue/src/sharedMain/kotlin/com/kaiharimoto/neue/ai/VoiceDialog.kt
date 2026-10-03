package com.kaiharimoto.neue.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.voice.VoiceModel
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * Voice on the desk, the first time (1.0.57, kai chose on-device): the microphone's words are
 * written out by a speech model on this computer, so nothing heard leaves it — which means a model
 * to download once. This asks, says how big, and lets the person pick fast or accurate.
 */
@Composable
fun VoiceDialog(ai: AiState) {
    if (!ai.voiceAsk) return
    val c = Mu.colors
    val model = ai.voiceModel
    // Asked for by the duel's push-to-talk (1.0.87): its own words, since Ai may be off.
    val duel = ai.voiceForDuel
    MuDialog(
        title = if (duel) "Speak your moves" else "Voice on this computer",
        onDismiss = { ai.voiceAsk = false },
        width = 520.dp,
        description = if (duel) {
            "Hold M and say a move — \"summon Ash Blossom to monster zone three\" — and let go: it is written out here, on this " +
                "computer, and shown on the table before Enter makes it. Nothing you say leaves the computer. That needs a speech model, " +
                "downloaded once (${model.megabytes} MB)."
        } else {
            "Speak to ${ai.name} and your words are written out here, on this computer — nothing you say is sent anywhere " +
                "until you send the words. That needs a speech model, downloaded once (${model.megabytes} MB)."
        },
        footer = {
            MuButton("Not now", { ai.voiceAsk = false }, variant = BtnVariant.GHOST)
            MuButton("Download ${model.megabytes} MB", { ai.downloadVoiceModel() }, variant = BtnVariant.PRIMARY, arrow = true)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceModelChoice(ai)
            Help(
                "It comes from whisper.cpp's own repository and is checked before it is used. " +
                    if (duel) "Fast is quickest for short commands." else "You can change it any time in ${ai.name}'s quick settings.",
                color = c.ink45,
            )
        }
    }
}

/** Which speech model the desk writes out with: fast, standard, accurate, or any language. */
@Composable
internal fun VoiceModelChoice(ai: AiState) {
    val c = Mu.colors
    val model = ai.voiceModel
    Micro("Speech model", color = c.ink45)
    Segmented(model, VoiceModel.entries, { it.label }, { m -> ai.h.neue.update { it.copy(ai = it.ai.copy(voiceModel = m.id)) } }, small = true)
    Small("${model.about} ${model.megabytes} MB.", color = c.ink)
}

/** Quick settings' voice: the model on the desk, whether replies are spoken in talk mode, and how fast. */
@Composable
internal fun VoiceSettings(ai: AiState) {
    val c = Mu.colors
    val prefs = ai.prefs
    // The desk's model choice; a phone's recogniser is the system's and has none.
    if (com.kaiharimoto.neue.platform.Voice.usesModels) {
        VoiceModelChoice(ai)
        if (com.kaiharimoto.neue.platform.Voice.needsModel(ai.voiceModel)) Help("Downloaded the first time you speak.", color = c.ink45)
    }
    Micro("Talk mode", color = c.ink45)
    Segmented(prefs.speakReplies, listOf(AiPrefs.SPEAK_IN_TALK, AiPrefs.SPEAK_NEVER), { if (it == AiPrefs.SPEAK_IN_TALK) "Answers aloud" else "Answers on screen" }, { v ->
        ai.h.neue.update { it.copy(ai = it.ai.copy(speakReplies = v)) }
    }, small = true)
    if (prefs.speakReplies == AiPrefs.SPEAK_IN_TALK) {
        Segmented(prefs.speechRate, listOf(0.8f, 1f, 1.25f, 1.5f), { if (it == 1f) "Normal" else "${it}×" }, { v ->
            ai.h.neue.update { it.copy(ai = it.ai.copy(speechRate = v)) }
        }, small = true)
    }
    if (!com.kaiharimoto.neue.platform.Voice.canSpeak) Help("This computer has no voice to speak with, so answers stay on screen.", color = c.ink45)
}
