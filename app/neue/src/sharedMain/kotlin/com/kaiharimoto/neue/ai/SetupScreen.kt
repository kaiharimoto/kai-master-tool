package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.providers.SetupStep
import com.kaiharimoto.mastertool.core.ai.providers.SetupSteps
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * Ai's first setup (1.0.45), the whole window. kai: "When the user starts the AI for the
 * first time, it should take over the entire program's UI and focus on it, as it's rather
 * advanced and needs the user's full attention. The guide should be intuitive and give the
 * user everything they need to set up without issues."
 *
 * Shown while Ai is asked for and has no connection (`NeueState.aiSetup`); the first
 * connection made ends it, and Ai docks beside the page. Down the left, the way through:
 * every step of the chosen path, the one you are on, the ones done (each a way back), and
 * what to have ready (`SetupGuide.needs`). On the right, one step at a time, large, with
 * what usually goes wrong at it and the fix (`SetupGuide.trouble`). A phone drops the
 * left column and says the same things inline. "Not now" puts it away; Esc and Back too.
 */
@Composable
fun AiSetupScreen(ai: AiState, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val w = ai.wizard
    LaunchedEffect(Unit) { if (!ai.wizardOpen) ai.openWizard() }
    BoxWithConstraints(
        modifier
            .background(c.paper)
            // The page under it hears nothing while it is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .imePadding(),
    ) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Micro("Setting up ${w.name.ifBlank { ai.name }}", color = c.ink)
                Box(Modifier.weight(1f))
                MuButton("Not now", { ai.h.neue.update { it.copy(ai = it.ai.copy(panelOpen = false)) } }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            HRule(color = c.ink)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    Column(
                        Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Rail(w)
                        Needs(w)
                    }
                    VRule(color = c.ink12)
                }
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = if (wide) 48.dp else 16.dp, vertical = if (wide) 48.dp else 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        val (at, of) = SetupSteps.position(w.step, w.provider)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mono("${at.toString().padStart(2, '0')} / ${of.toString().padStart(2, '0')}", color = c.ink45, modifier = Modifier.weight(1f))
                            SetupSteps.back(w.step, w.provider)?.let { previous ->
                                MicroLink("← Back", { w.message = null; w.good = null; w.step = previous })
                            }
                        }
                        if (w.step == SetupStep.NAME) Welcome(ai)
                        MuText(w.step.title, style = MuType.h1(LocalMuFonts.current), color = c.ink)
                        // The rail's list, where there is no rail: at the start of the provider's own steps.
                        if (!wide && w.step == SetupSteps.of(w.provider).getOrNull(3)) Needs(w)
                        WizardStep(ai, w)
                        Trouble(w)
                    }
                }
            }
        }
    }
}

/** What Ai is, said once, before anything is asked. */
@Composable
private fun Welcome(ai: AiState) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MuText("Meet ${ai.name}.", style = MuType.display(LocalMuFonts.current), color = c.ink)
        Small(
            "An assistant that lives in the app and can do everything you can: build and tune decks, sort them into groups, " +
                "read the latest tournament results, make webs of the field and siding plans for each matchup, change any setting.",
            color = c.ink70,
        )
        Small(
            "It thinks with a language model you connect it to — your Claude or ChatGPT plan, an API key, or a model on your own computer. " +
                "This takes a few minutes, one step at a time, and every step checks itself as you go.",
            color = c.ink70,
        )
        HRule(Modifier.padding(top = 8.dp))
    }
}

/** The way through: every step of the path chosen so far, the current one inverted, the done ones a way back. */
@Composable
private fun Rail(w: WizardState) {
    val c = Mu.colors
    val steps = SetupSteps.of(w.provider)
    val here = steps.indexOf(w.step)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Micro("The steps", color = c.ink45, modifier = Modifier.padding(bottom = 8.dp))
        steps.forEachIndexed { i, step ->
            val done = i < here
            val current = i == here
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(animatedColor(if (current) c.ink else if (done && hovered) c.ink06 else Color.Transparent))
                    .let {
                        if (done) it.hoverable(source).cursorPointer(caption = "Back to").muClickable(interactionSource = source) {
                            w.message = null
                            w.good = null
                            w.step = step
                        } else it
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono(if (done) "✓" else (i + 1).toString().padStart(2, '0'), color = if (current) c.paper else if (done) c.ink else c.ink45)
                Small(step.short, color = if (current) c.paper else if (done) c.ink else c.ink45)
            }
        }
        // Until a provider is chosen, its own steps are not known yet.
        if (w.provider == null) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mono("··", color = c.ink25)
                Help("Its own steps, then a model", color = c.ink45)
            }
        }
    }
}
