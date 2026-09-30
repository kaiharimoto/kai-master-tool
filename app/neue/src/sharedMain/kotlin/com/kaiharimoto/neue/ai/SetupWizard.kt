package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.kaiharimoto.mastertool.core.ai.BackendEvent
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.ToolGroup
import com.kaiharimoto.mastertool.core.ai.ToolSpec
import com.kaiharimoto.mastertool.core.ai.TurnRequest
import com.kaiharimoto.mastertool.core.ai.cli.ClaudeCli
import com.kaiharimoto.mastertool.core.ai.cli.CodexCli
import com.kaiharimoto.mastertool.core.ai.providers.ConnectKind
import com.kaiharimoto.mastertool.core.ai.providers.Provider
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.SetupStep
import com.kaiharimoto.mastertool.core.ai.providers.SetupSteps
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.ai.schema
import com.kaiharimoto.mastertool.core.ai.wire.AnthropicModels
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiChatBackend
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiEndpoint
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Badge
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.util.UUID

/** Where the setup wizard is, kept while the panel closes and opens. */
class WizardState(name: String) {
    var step by mutableStateOf(SetupStep.NAME)
    var name by mutableStateOf(name)
    var kind by mutableStateOf<ConnectKind?>(null)
    var provider by mutableStateOf<Provider?>(null)
    var program by mutableStateOf<String?>(null)
    var programTyped by mutableStateOf("")
    var version by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf<Boolean?>(null)
    var key by mutableStateOf("")
    var baseUrl by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var model by mutableStateOf("")
    var effort by mutableStateOf("")
    var alwaysAllow by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var good by mutableStateOf<String?>(null)
    var toolsWork by mutableStateOf<Boolean?>(null)

    fun choose(p: Provider) {
        provider = p
        program = null
        version = null
        signedIn = null
        key = ""
        baseUrl = p.baseUrl.orEmpty()
        models = emptyList()
        model = ""
        effort = p.defaultEffort
        message = null
        good = null
        toolsWork = null
    }
}

/**
 * The setup wizard (Ai, 1.0.42; kai: "guided in an intuitive way… step by step for each
 * provider", after Hermes's `hermes model`): a name, how to connect, which provider,
 * then that provider's own steps — install and sign in a CLI, paste and try a key,
 * find a local server — then a model, what it may do on its own, and done. Every step
 * checks itself live and says what it found.
 */
@Composable
fun SetupWizard(ai: AiState, modifier: Modifier = Modifier) {
    val w = ai.wizard
    val c = Mu.colors
    val scroll = rememberScrollState()
    val (at, of) = SetupSteps.position(w.step, w.provider)
    Column(modifier.verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono("${at.toString().padStart(2, '0')} / ${of.toString().padStart(2, '0')}", color = c.ink45)
            Box(Modifier.weight(1f))
            SetupSteps.back(w.step, w.provider)?.let { previous ->
                MicroLink("← Back", { w.message = null; w.good = null; w.step = previous })
            }
        }
        MuText(w.step.title, style = MuType.h2(LocalMuFonts.current), color = c.ink)
        when (w.step) {
            SetupStep.NAME -> NameStep(ai, w)
            SetupStep.CONNECT -> ConnectStep(w)
            SetupStep.PROVIDER -> ProviderStep(w)
            SetupStep.INSTALL -> InstallStep(w)
            SetupStep.SIGN_IN -> SignInStep(w)
            SetupStep.KEY -> KeyStep(w)
            SetupStep.SERVER -> ServerStep(w)
            SetupStep.MODEL -> ModelStep(w)
            SetupStep.PERMISSIONS -> PermissionsStep(ai, w)
            SetupStep.DONE -> DoneStep(ai, w)
        }
        w.message?.let { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Mono("✕", color = c.ink); Small(it, color = c.ink) } }
        w.good?.let { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Mono("✓", color = c.ink); Small(it, color = c.ink) } }
    }
}

private fun WizardState.next() {
    message = null
    good = null
    SetupSteps.next(step, provider)?.let { step = it }
}

@Composable
private fun NameStep(ai: AiState, w: WizardState) {
    Help("Your assistant is called ${ai.name} unless you name it something else. It answers to the name, and it remembers what you tell it.")
    MuInput(w.name, { w.name = it.take(24) }, Modifier.fillMaxWidth(), placeholder = "Ai", onSubmit = { w.next() })
    Help("Ai is the duelling partner from Yu-Gi-Oh! VRAINS: playful, a little cheeky, and on your side. You can change how it talks later, in Settings.")
    MuButton("Next", {
        if (w.name.isNotBlank() && w.name != ai.name) ai.rename(w.name)
        w.next()
    }, variant = BtnVariant.PRIMARY, arrow = true)
}

@Composable
private fun ConnectStep(w: WizardState) {
    val desk = AiDesk.canRunCli
    ConnectKind.entries.forEach { kind ->
        val enabled = kind != ConnectKind.PLAN || desk
        Choice(
            title = kind.title,
            line = if (enabled) kind.line else "The Claude and ChatGPT plans work through apps on a computer: set this up in the desktop app, or use an API key here.",
            selected = w.kind == kind,
            enabled = enabled,
            reason = "Desktop only",
        ) {
            w.kind = kind
            w.provider = null
            w.next()
        }
    }
}

@Composable
private fun ProviderStep(w: WizardState) {
    val kind = w.kind ?: return
    Providers.of(kind).forEach { p ->
        val enabled = Providers.available(p, Platform.os)
        Choice(p.label, p.blurb, selected = w.provider == p, enabled = enabled, reason = "Desktop only") {
            w.choose(p)
            w.next()
        }
    }
}

@Composable
private fun InstallStep(w: WizardState) {
    val p = w.provider ?: return
    val program = p.program ?: return
    val scope = rememberCoroutineScope()
    fun check() {
        scope.launch {
            w.checking = true
            w.message = null
            val found = AiDesk.which(program, w.programTyped.takeIf { it.isNotBlank() })
            w.program = found
            if (found != null) {
                val v = AiDesk.run(listOf(found, "--version"), timeoutMs = 15_000)
                w.version = (v.out.ifBlank { v.err }).lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                w.good = "Found ${p.label}${w.version?.let { " $it" } ?: ""} at $found."
            } else {
                w.good = null
                w.message = "${p.label} is not installed, or not where the app can see it."
            }
            w.checking = false
        }
    }
    LaunchedEffect(p.id) { if (w.program == null) check() }
    if (w.program == null) {
        Help("Install ${p.label} with one of these, in a terminal. It takes a minute.")
        Providers.install(p, Platform.os).forEach { command -> CommandLine(command) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton("Open a terminal", { AiDesk.openTerminal(Providers.install(p, Platform.os).first()) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
            MuButton(if (w.checking) "Checking" else "Check again", ::check, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = !w.checking, reason = "Checking")
        }
        Help("Installed somewhere unusual? Its full path:")
        MuInput(w.programTyped, { w.programTyped = it }, Modifier.fillMaxWidth(), placeholder = if (Platform.os == DesktopOs.WINDOWS) "C:\\…\\$program.exe" else "/…/$program", mono = true, onSubmit = ::check)
        p.docs?.let { MicroLink("${p.label}'s own guide →", { Platform.browse(it) }) }
    } else {
        MuButton("Next", { w.next() }, variant = BtnVariant.PRIMARY, arrow = true)
    }
    if (w.checking) Breathe()
}

@Composable
private fun SignInStep(w: WizardState) {
    val p = w.provider ?: return
    val program = w.program ?: return
    val scope = rememberCoroutineScope()
    fun check() {
        scope.launch {
            w.checking = true
            w.message = null
            w.signedIn = when (p.wire) {
                Wire.CLAUDE_CLI -> AiDesk.run(listOf(program, "auth", "status"), timeoutMs = 20_000).let { r -> ClaudeCli.loggedIn(r.out) ?: r.ok }
                else -> AiDesk.run(listOf(program, "login", "status"), timeoutMs = 20_000).let { r -> CodexCli.loggedIn(r.exit, r.out + r.err) }
            }
            if (w.signedIn == true) w.good = "Signed in." else w.message = "Not signed in yet."
            w.checking = false
        }
    }
    LaunchedEffect(program) { if (w.signedIn == null) check() }
    p.caution?.let { Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Help(it, color = Mu.colors.ink70) } }
    if (w.signedIn != true) {
        Help(
            when (p.wire) {
                Wire.CLAUDE_CLI -> "Sign Claude Code in to your Claude account: a terminal opens, your browser asks you to log in, then come back here."
                else -> "Sign Codex in with your ChatGPT account: a terminal opens, your browser asks you to log in, then come back here."
            },
        )
        Providers.login(p)?.let { CommandLine(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuButton("Sign in", { Providers.login(p)?.let { AiDesk.openTerminal(program.let { path -> it.replaceFirst(p.program!!, quoted(path)) }) } }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
            MuButton(if (w.checking) "Checking" else "Check again", ::check, variant = BtnVariant.PRIMARY, size = BtnSize.SM, enabled = !w.checking, reason = "Checking")
        }
    } else {
        MuButton("Next", { w.next() }, variant = BtnVariant.PRIMARY, arrow = true)
    }
    if (w.checking) Breathe()
}

private fun quoted(path: String) = if (path.contains(' ')) "\"$path\"" else path

@Composable
private fun KeyStep(w: WizardState) {
    val p = w.provider ?: return
    val scope = rememberCoroutineScope()
    val env = p.envVars.firstNotNullOfOrNull { name -> AiDesk.env(name)?.let { name to it } }
    Steps(
        when (p.id) {
            "anthropic" -> listOf("Open the Claude Console and sign in.", "Add credit under Billing, if you have not.", "Create a key and copy it.", "Paste it here and try it.")
            "openai" -> listOf("Open the OpenAI platform and sign in.", "Add credit under Billing, if you have not.", "Create a secret key and copy it.", "Paste it here and try it.")
            "gemini" -> listOf("Open Google AI Studio and sign in with your Google account.", "Create an API key and copy it.", "Paste it here and try it.")
            "openrouter" -> listOf("Open OpenRouter and sign in.", "Add credit.", "Create a key and copy it.", "Paste it here and try it.")
            else -> listOf("Paste the key and try it.")
        },
    )
    p.keyPage?.let { page -> MuButton("Open the key page", { Platform.browse(page) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true) }
    if (env != null && w.key.isEmpty()) {
        MuButton("Use the key in ${env.first}", { w.key = env.second }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
    }
    SecretField(w.key, { w.key = it.trim() }, "Paste the key")
    Help("The key stays on this ${if (AiDesk.canRunCli) "computer" else "device"}, in a store of its own: never in a deck file, an export or a conversation.")
    Providers.keyProblem(p, w.key)?.takeIf { w.key.isNotEmpty() }?.let { Help(it, color = Mu.colors.ink) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MuButton(if (w.checking) "Trying" else "Try the key", {
            scope.launch {
                w.checking = true
                w.message = null
                val listed = listModels(p, w.key, p.baseUrl)
                listed.onSuccess { models ->
                    w.models = models
                    w.model = Providers.recommended(p, models) ?: models.firstOrNull().orEmpty()
                    w.good = "The key works: ${models.size} models."
                    w.next()
                }.onFailure { w.message = "That key did not work: ${it.message ?: it::class.simpleName}" }
                w.checking = false
            }
        }, variant = BtnVariant.PRIMARY, enabled = w.key.isNotBlank() && !w.checking, reason = "Paste the key first", arrow = true)
        if (w.checking) Breathe()
    }
}

@Composable
private fun ServerStep(w: WizardState) {
    val p = w.provider ?: return
    val scope = rememberCoroutineScope()
    Help(
        when (p.id) {
            "ollama" -> "Install Ollama, then pull a model that can call tools (for example `ollama pull qwen3`). Ollama listens on this computer by itself."
            "lmstudio" -> "In LM Studio, load a model and start the local server (Developer tab). It listens on port 1234."
            else -> "The server's address up to /v1, and a key if it asks for one."
        },
    )
    MuInput(w.baseUrl, { w.baseUrl = it.trim() }, Modifier.fillMaxWidth(), placeholder = "http://localhost:8000/v1", mono = true)
    if (p.id == "custom") SecretField(w.key, { w.key = it.trim() }, "Key, if the server wants one")
    if (w.baseUrl.isNotBlank() && !Providers.plainHttpAllowed(w.baseUrl)) Help("Plain http only on this machine or your own network; anything further needs https.", color = Mu.colors.ink)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MuButton(if (w.checking) "Looking" else "Find it", {
            scope.launch {
                w.checking = true
                w.message = null
                listModels(p, w.key, w.baseUrl).onSuccess { models ->
                    w.models = models
                    w.model = models.firstOrNull().orEmpty()
                    w.good = if (models.isEmpty()) "Found the server, with no models loaded." else "Found the server: ${models.size} models."
                    if (models.isNotEmpty()) w.next()
                }.onFailure { w.message = "Could not reach ${w.baseUrl}: ${it.message ?: it::class.simpleName}" }
                w.checking = false
            }
        }, variant = BtnVariant.PRIMARY, enabled = w.baseUrl.isNotBlank() && Providers.plainHttpAllowed(w.baseUrl) && !w.checking, reason = "An address first", arrow = true)
        if (w.checking) Breathe()
    }
}

/** What a provider offers, by its own list. */
private suspend fun listModels(p: Provider, key: String, base: String?): Result<List<String>> = when (p.wire) {
    Wire.ANTHROPIC -> AnthropicBackend(key, null).let { b -> b.models().also { b.close() } }
    else -> OpenAiChatBackend(HttpClientFactory.create(), OpenAiEndpoint(base.orEmpty(), key.takeIf { it.isNotBlank() }, p.headers)).models()
        .map { Providers.chatModels(it) }
}

@Composable
private fun ModelStep(w: WizardState) {
    val p = w.provider ?: return
    val scope = rememberCoroutineScope()
    val c = Mu.colors
    val offered = if (w.models.isNotEmpty()) w.models else p.presetModels
    val recommended = if (p.wire == Wire.ANTHROPIC) AnthropicModels.DEFAULT else Providers.recommended(p, offered)
    Help(
        when (p.wire) {
            Wire.CLAUDE_CLI -> "Default is whatever Claude Code uses; opus, sonnet and fable are its names for the newest of each."
            Wire.CODEX_CLI -> "Leave it on the default, or type a model your plan includes."
            else -> "The stronger the model, the better the decks and the plans — and the more each answer costs."
        },
    )
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        offered.forEach { m ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(animatedColor(if (w.model == m) c.ink else if (hovered) c.ink06 else androidx.compose.ui.graphics.Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(caption = "Choose")
                    .muClickable(interactionSource = source) { w.model = m }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Mono(m.ifBlank { "Default" }, color = if (w.model == m) c.paper else c.ink, modifier = Modifier.weight(1f))
                if (m == recommended && m.isNotBlank()) {
                    if (w.model == m) com.kaiharimoto.neue.kit.Micro("Recommended", color = c.paper) else Badge("Recommended")
                }
            }
        }
    }
    MuInput(w.model, { w.model = it.trim() }, Modifier.fillMaxWidth(), placeholder = "Or type a model's id", mono = true, dense = true)
    if (p.efforts.isNotEmpty()) {
        Help("How hard it thinks before answering. Higher is slower and costs more; medium suits most.")
        // A select, not a row of buttons: six efforts are wider than the panel.
        com.kaiharimoto.neue.kit.MuSelect(w.effort, listOf("") + p.efforts, { if (it.isBlank()) "Default" else it.replaceFirstChar { ch -> ch.uppercase() } }, { w.effort = it })
    }
    if (p.kind == ConnectKind.LOCAL) {
        Help("Ai needs a model that can call tools to act in the app. Try it:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton(if (w.checking) "Trying" else "Try tool use", {
                scope.launch {
                    w.checking = true
                    w.toolsWork = probeTools(p, w)
                    if (w.toolsWork == true) w.good = "${w.model} can call tools." else w.message = "${w.model} did not call the tool: it can chat, but not act in the app. Choose another model if you can."
                    w.checking = false
                }
            }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, enabled = w.model.isNotBlank() && !w.checking, reason = "Choose a model")
            if (w.checking) Breathe()
        }
    }
    MuButton("Next", { w.next() }, variant = BtnVariant.PRIMARY, arrow = true, enabled = w.model.isNotBlank() || p.presetModels.contains(""), reason = "Choose a model")
}

/** Whether a local model calls a tool when asked to: one small request with one tool. */
private suspend fun probeTools(p: Provider, w: WizardState): Boolean {
    val ping = ToolSpec("ping", "Answers pong. Call it when asked to ping.", schema { }, ToolGroup.APP)
    val backend = OpenAiChatBackend(HttpClientFactory.create(), OpenAiEndpoint(w.baseUrl, w.key.takeIf { it.isNotBlank() }, p.headers))
    val events = runCatching {
        backend.turn(TurnRequest("You test tools. Call the ping tool now.", listOf(ChatTurn.user("Ping, please.")), listOf(ping), w.model)).toList()
    }.getOrDefault(emptyList())
    return events.filterIsInstance<BackendEvent.Finished>().any { it.turn?.toolUses?.any { use -> use.name == "ping" } == true }
}

@Composable
private fun PermissionsStep(ai: AiState, w: WizardState) {
    Help("${w.name.ifBlank { ai.name }} changes decks, webs and settings when you ask, and every deck edit can be undone. Deleting cannot:")
    Choice("Ask me before deleting anything", "Deleting a deck, a web or a deck from a web waits for your OK in the chat. Recommended.", selected = !w.alwaysAllow) { w.alwaysAllow = false }
    Choice("Never ask", "It deletes when you ask it to, without checking first.", selected = w.alwaysAllow) { w.alwaysAllow = true }
    MuButton("Next", { w.next() }, variant = BtnVariant.PRIMARY, arrow = true)
}

@Composable
private fun DoneStep(ai: AiState, w: WizardState) {
    val p = w.provider ?: return
    Help("${w.name.ifBlank { ai.name }} will talk through ${p.label}${w.model.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}.")
    Help("Open it any time with the ${ai.name} button in the bar${if (AiDesk.canRunCli) " or ${com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(com.kaiharimoto.mastertool.core.input.DeskAction.AI_PANEL)?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd).orEmpty()}" else ""}. It follows you to every page.")
    MuButton("Start", {
        val connection = AiConnection(
            id = "${p.id}-${UUID.randomUUID().toString().take(6)}",
            provider = p.id,
            label = p.label,
            model = w.model,
            baseUrl = w.baseUrl.takeIf { p.kind == ConnectKind.LOCAL && it.isNotBlank() },
            program = w.program,
        )
        ai.h.neue.update { it.copy(ai = it.ai.copy(effort = w.effort, alwaysAllow = w.alwaysAllow)) }
        ai.connect(connection, w.key.takeIf { p.needsKey || it.isNotBlank() })
        ai.wizard = WizardState(ai.name)
    }, variant = BtnVariant.PRIMARY, arrow = true)
}

/** Numbered steps, `01` to `04`, one a line. */
@Composable
private fun Steps(lines: List<String>) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono((i + 1).toString().padStart(2, '0'), color = c.ink45)
                Small(line, color = c.ink)
            }
        }
    }
}

/** A big choice: a title and a line, boxed, inverted when chosen. */
@Composable
private fun Choice(title: String, line: String, selected: Boolean, enabled: Boolean = true, reason: String? = null, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, animatedColor(if (selected || (hovered && enabled)) c.ink else c.ink25))
            .background(animatedColor(if (selected) c.ink else androidx.compose.ui.graphics.Color.Transparent))
            .hoverable(source, enabled)
            .cursorPointer(caption = "Choose", enabled = enabled, reason = reason)
            .muClickable(enabled = enabled, interactionSource = source, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MuText(title, style = MuType.row(LocalMuFonts.current).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (selected) c.paper else if (enabled) c.ink else c.ink45)
        Small(line, color = if (selected) c.paper else if (enabled) c.ink70 else c.ink45)
    }
}

/** A command to copy: in mono, with Copy beside it. */
@Composable
private fun CommandLine(command: String) {
    val c = Mu.colors
    var copied by remember(command) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MuText(command, Modifier.weight(1f), style = MuType.mono(LocalMuFonts.current), color = c.ink)
        MuButton(if (copied) "Copied" else "Copy", { Platform.copy(command); copied = true }, variant = BtnVariant.GHOST, size = BtnSize.SM)
    }
}

/** A key: dots unless shown, underline only, as the kit's fields are. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    var shown by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val style = MuType.mono(f, 13.sp).copy(color = c.ink)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .weight(1f)
                .height(36.dp)
                .cursor(CursorMode.TEXT, fontSize = style.fontSize, focused = focused)
                .drawBehind {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(if (focused) c.ink else c.ink25, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) MuText(placeholder, style = style, color = c.ink45, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(c.ink),
                interactionSource = source,
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().reportsTextFocus(),
            )
        }
        MicroLink(if (shown) "Hide" else "Show", { shown = !shown })
    }
}
