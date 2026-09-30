package com.kaiharimoto.neue.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.TuneIntensity
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * Quick settings (1.0.54, kai: "allow the user to change which model and effort they use, as well
 * as other configuration parameters that would be useful, without having to go through the entire
 * setup process in an accessible way, perhaps by clicking the top model name as a button"). Every
 * change applies at once and keeps the conversation: which connection, its model (the provider's
 * own list, or any id typed), how hard it thinks, how its thinking shows, how long Fine Tuning
 * runs, whether deleting asks first, and its name. The full setup is one button away, for a new
 * connection.
 */
@Composable
fun QuickSettings(ai: AiState) {
    if (!ai.quickOpen) return
    val c = Mu.colors
    val prefs = ai.prefs
    val connection = prefs.connection
    val provider = Providers.byId(connection?.provider)
    var models by remember(connection?.id) { mutableStateOf(provider?.presetModels.orEmpty()) }
    var listing by remember(connection?.id) { mutableStateOf(false) }
    // The provider's own list of models, fetched once per connection shown.
    LaunchedEffect(connection?.id) {
        val p = provider ?: return@LaunchedEffect
        if (connection == null || p.wire == Wire.CLAUDE_CLI || p.wire == Wire.CODEX_CLI) return@LaunchedEffect
        listing = true
        listModels(p, ai.secret(connection).orEmpty(), connection.baseUrl ?: p.baseUrl).onSuccess { if (it.isNotEmpty()) models = it }
        listing = false
    }
    var typed by remember(connection?.id) { mutableStateOf(connection?.model.orEmpty()) }
    var name by remember { mutableStateOf(ai.name) }
    MuDialog(
        title = "${ai.name} · settings",
        onDismiss = {
            if (name.isNotBlank() && name != ai.name) ai.rename(name)
            ai.quickOpen = false
        },
        width = 560.dp,
        description = "Changes apply at once, and the conversation carries on.",
        footer = {
            MuButton("Its brain", {
                ai.quickOpen = false
                ai.memoryOpen = "USER.md"
            }, variant = BtnVariant.GHOST)
            MuButton("New connection…", {
                ai.quickOpen = false
                ai.openWizard()
            }, variant = BtnVariant.GHOST)
            MuButton("Done", {
                if (name.isNotBlank() && name != ai.name) ai.rename(name)
                ai.quickOpen = false
            }, variant = BtnVariant.PRIMARY)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Name")
            MuInput(name, { name = it.take(AiPrefs.MAX_NAME) }, Modifier.fillMaxWidth(), onSubmit = { if (name.isNotBlank()) ai.rename(name) })
            if (prefs.connections.size > 1) {
                FieldLabel("Connection", hint = "${prefs.connections.size} saved")
                MuSelect(connection, prefs.connections, { it?.label?.ifBlank { null } ?: Providers.byId(it?.provider)?.label ?: "None" }, { it?.let { ai.use(it.id) } }, Modifier.fillMaxWidth())
            }
            if (connection != null && provider != null) {
                FieldLabel("Model", hint = if (listing) "asking ${provider.label}…" else "${provider.label}${if (models.isNotEmpty()) " · ${models.size} offered" else ""}")
                if (models.isNotEmpty()) {
                    MuSelect(connection.model.takeIf { it in models } ?: "", listOf("") + models, { it.ifBlank { "Default" } }, { m ->
                        typed = m
                        ai.tweak(connection.id) { it.copy(model = m) }
                    }, Modifier.fillMaxWidth())
                }
                MuInput(typed, { typed = it.trim() }, Modifier.fillMaxWidth(), placeholder = "Or type a model's id", mono = true, dense = true, onSubmit = {
                    ai.tweak(connection.id) { it.copy(model = typed) }
                })
                if (typed != connection.model) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuButton("Use ${typed.ifBlank { "the default" }}", { ai.tweak(connection.id) { it.copy(model = typed) } }, variant = BtnVariant.SECONDARY)
                        Small("Enter does the same.", color = c.ink45)
                    }
                }
                if (provider.efforts.isNotEmpty()) {
                    FieldLabel("Effort", hint = "how hard it thinks; higher is slower and costs more")
                    Segmented(prefs.effort, listOf("") + provider.efforts, { if (it.isBlank()) "Default" else it.replaceFirstChar { ch -> ch.uppercase() } }, { e ->
                        ai.h.neue.update { it.copy(ai = it.ai.copy(effort = e)) }
                    }, small = true)
                }
            }
            FieldLabel("Its thinking", hint = "in the conversation")
            Segmented(prefs.showReasoning, AiPrefs.REASONINGS, { it.replaceFirstChar { ch -> ch.uppercase() } }, { v ->
                ai.h.neue.update { it.copy(ai = it.ai.copy(showReasoning = v)) }
            }, small = true)
            FieldLabel("Fine Tuning and Learn About You", hint = "how long they run")
            Segmented(TuneIntensity.of(prefs.tuneIntensity), TuneIntensity.entries, { it.label }, { v ->
                ai.h.neue.update { it.copy(ai = it.ai.copy(tuneIntensity = v.id)) }
            }, small = true)
            FieldLabel("Deleting a deck or a web")
            Segmented(prefs.alwaysAllow, listOf(false, true), { if (it) "Never ask" else "Ask me first" }, { v ->
                ai.h.neue.update { it.copy(ai = it.ai.copy(alwaysAllow = v)) }
            }, small = true)
            if (connection != null) {
                Help("${connection.label.ifBlank { provider?.label.orEmpty() }}: the key, the address and the program stay as set up. Change them with New connection.", color = c.ink45)
            }
        }
    }
}
