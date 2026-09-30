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
import com.kaiharimoto.mastertool.core.ai.providers.SavedConnections
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
import com.kaiharimoto.neue.kit.LocalPhone
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
    /** The connection's name: the provider's, or the person's own for [Providers.compatible]. */
    var label by mutableStateOf("")
    /** Where the key is made: the provider's page, or a preset's. */
    var keyPage by mutableStateOf<String?>(null)
    var models by mutableStateOf<List<String>>(emptyList())
    var model by mutableStateOf("")
    var effort by mutableStateOf("")
    var alwaysAllow by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var good by mutableStateOf<String?>(null)
    var toolsWork by mutableStateOf<Boolean?>(null)
    /**
     * Opened with connections already made (1.0.59): the wizard shows them first, to use one or add
     * another, so a press of Setup by mistake costs one click, not the whole way and a key.
     */
    var saved by mutableStateOf(false)
    /** The saved connection whose key is in [key], by its name; null once the person types their own. */
    var keyFrom by mutableStateOf<String?>(null)
    /** The model a saved preset used, chosen again once the key lists the models. */
    var preferModel by mutableStateOf<String?>(null)

    fun choose(p: Provider) {
        provider = p
        program = null
        version = null
        signedIn = null
        key = ""
        baseUrl = Providers.startingAddress(p, onDevice)
        label = if (p.id == Providers.compatible.id) "" else p.label
        keyPage = p.keyPage
        keyFrom = null
        preferModel = null
        models = emptyList()
        model = ""
        effort = p.defaultEffort
        message = null
        good = null
        toolsWork = null
    }
}

/**
 * The setup wizard (Ai, 1.0.43; kai: "guided in an intuitive way… step by step for each
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
    if (w.saved && ai.configured) {
        Column(modifier.verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { SavedStep(ai, w) }
        return
    }
    Column(modifier.verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono("${at.toString().padStart(2, '0')} / ${of.toString().padStart(2, '0')}", color = c.ink45)
            Box(Modifier.weight(1f))
            SetupSteps.back(w.step, w.provider)?.let { previous ->
                MicroLink("← Back", {
                    w.message = null
                    w.good = null
                    // With connections made, the way back from the start is to them, not to the name.
                    if (ai.configured && previous == SetupStep.NAME) {
                        w.step = SetupStep.NAME
                        w.saved = true
                    } else {
                        w.step = previous
                    }
                })
            }
        }
        MuText(w.step.title, style = MuType.h2(LocalMuFonts.current), color = c.ink)
        if (w.step == SetupSteps.of(w.provider).getOrNull(3)) Needs(w)
        WizardStep(ai, w)
        Trouble(w)
    }
}

/** The step itself, and what its last check found: the panel's and the first setup's alike. */
@Composable
internal fun WizardStep(ai: AiState, w: WizardState) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (w.step) {
            SetupStep.NAME -> NameStep(ai, w)
            SetupStep.CONNECT -> ConnectStep(w)
            SetupStep.PROVIDER -> ProviderStep(w)
            SetupStep.INSTALL -> InstallStep(w)
            SetupStep.SIGN_IN -> SignInStep(w)
            SetupStep.KEY -> KeyStep(ai, w)
            SetupStep.SERVER -> ServerStep(w)
            SetupStep.MODEL -> ModelStep(w)
            SetupStep.PERMISSIONS -> PermissionsStep(ai, w)
            SetupStep.DONE -> DoneStep(ai, w)
        }
        w.message?.let { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Mono("✕", color = c.ink); Small(it, color = c.ink) } }
        w.good?.let { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Mono("✓", color = c.ink); Small(it, color = c.ink) } }
    }
}

/** What to have ready for the way chosen (`SetupGuide.needs`). */
@Composable
internal fun Needs(w: WizardState, title: Boolean = true) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title) com.kaiharimoto.neue.kit.Micro("You will need", color = c.ink45)
        com.kaiharimoto.mastertool.core.ai.providers.SetupGuide.needs(w.kind, w.provider, onDevice).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("–", color = c.ink45)
                Small(line, color = c.ink70)
            }
        }
    }
}

/** The ways this step goes wrong, and what to do (`SetupGuide.trouble`). */
@Composable
internal fun Trouble(w: WizardState) {
    val c = Mu.colors
    val trouble = com.kaiharimoto.mastertool.core.ai.providers.SetupGuide.trouble(w.step, w.provider, onDevice)
    if (trouble.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.kaiharimoto.neue.kit.HRule()
        com.kaiharimoto.neue.kit.Micro("If something goes wrong", color = c.ink45)
        trouble.forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Small(t.problem, color = c.ink)
                Help(t.fix, color = c.ink70)
            }
        }
    }
}

/**
 * The connections already made (1.0.59), shown first when Setup is pressed with one in place:
 * use one, add another, or close — nothing asked again.
 */
@Composable
private fun SavedStep(ai: AiState, w: WizardState) {
    val c = Mu.colors
    MuText("Your connections", style = MuType.h2(LocalMuFonts.current), color = c.ink)
    Help("${ai.name} talks through the one chosen. Choose another, or add a new one: keys you gave before are kept and offered again.")
    val active = ai.prefs.connection?.id
    ai.prefs.connections.forEach { conn ->
        val provider = Providers.byId(conn.provider)
        Choice(
            title = conn.label.ifBlank { provider?.label ?: conn.provider },
            line = listOfNotNull(
                conn.model.ifBlank { "Default model" },
                provider?.label?.takeIf { it != conn.label },
                conn.baseUrl?.let(SavedConnections::address)?.removePrefix("https://"),
                if (conn.id == active) "in use" else null,
            ).joinToString(" · "),
            selected = conn.id == active,
        ) {
            if (conn.id != active) ai.use(conn.id)
            ai.wizardOpen = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MuButton("Add a connection", {
            w.saved = false
            w.name = ai.name
            w.message = null
            w.good = null
            w.step = SetupStep.CONNECT
        }, variant = BtnVariant.PRIMARY, arrow = true)
        MuButton("Close", { ai.wizardOpen = false }, variant = BtnVariant.GHOST)
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
    MuInput(w.name, { w.name = it.take(24) }, Modifier.fillMaxWidth(), placeholder = "Ai", onSubmit = {
        if (w.name.isNotBlank() && w.name != ai.name) ai.rename(w.name)
        w.next()
    })
    Help("Ai is the dueling partner from Yu-Gi-Oh! VRAINS: playful, a little cheeky, and on your side. You can rename it any time: click its name at the top of its panel.")
    MuButton("Next", {
        if (w.name.isNotBlank() && w.name != ai.name) ai.rename(w.name)
        w.next()
    }, variant = BtnVariant.PRIMARY, arrow = true)
}

@Composable
private fun ConnectStep(w: WizardState) {
    val desk = AiDesk.canRunCli
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.kaiharimoto.mastertool.core.ai.providers.SetupGuide.choosing.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("–", color = c.ink45)
                Small(line, color = c.ink70)
            }
        }
    }
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
private fun KeyStep(ai: AiState, w: WizardState) {
    val p = w.provider ?: return
    val scope = rememberCoroutineScope()
    // A key given before for this service is offered again (1.0.59), not asked for.
    LaunchedEffect(p.id, SavedConnections.address(w.baseUrl)) {
        if (w.key.isEmpty() || w.keyFrom != null) {
            val from = SavedConnections.keyFrom(ai.prefs.connections, p.id, w.baseUrl.takeIf { Providers.typedAddress(p) }) { !ai.secret(it).isNullOrBlank() }
            w.key = from?.let { ai.secret(it) }.orEmpty()
            w.keyFrom = from?.label?.ifBlank { p.label }
        }
    }
    val env = p.envVars.firstNotNullOfOrNull { name -> AiDesk.env(name)?.let { name to it } }
    Steps(
        when (p.id) {
            "anthropic" -> listOf("Open the Claude Console and sign in.", "Add credit under Billing, if you have not.", "Create a key and copy it.", "Paste it here and try it.")
            "openai" -> listOf("Open the OpenAI platform and sign in.", "Add credit under Billing, if you have not.", "Create a secret key and copy it.", "Paste it here and try it.")
            "gemini" -> listOf("Open Google AI Studio and sign in with your Google account.", "Create an API key and copy it.", "Paste it here and try it.")
            "openrouter" -> listOf("Open OpenRouter and sign in.", "Add credit.", "Create a key and copy it.", "Paste it here and try it.")
            "compatible" -> listOf("Pick the provider below, or type its API address from its docs.", "Make a key on its site, add credit if it needs some, and paste it here.", "Name it, so you know it among your connections.", "Try it.")
            else -> listOf("Paste the key and try it.")
        },
    )
    val compatible = p.id == Providers.compatible.id
    if (compatible) CompatibleFields(ai, w)
    w.keyPage?.let { page -> MuButton("Open the key page", { Platform.browse(page) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true) }
    if (env != null && w.key.isEmpty()) {
        MuButton("Use the key in ${env.first}", { w.key = env.second }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
    }
    SecretField(w.key, { w.key = it.trim(); w.keyFrom = null }, "Paste the key")
    w.keyFrom?.let { Help("The key saved with “$it” is filled in. Paste another to change it.", color = Mu.colors.ink70) }
    Help("The key stays on this ${if (AiDesk.canRunCli) "computer" else "device"}, in a store of its own: never in a deck file, an export or a conversation.")
    Providers.keyProblem(p, w.key)?.takeIf { w.key.isNotEmpty() }?.let { Help(it, color = Mu.colors.ink) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MuButton(if (w.checking) "Trying" else "Try the key", {
            scope.launch {
                w.checking = true
                w.message = null
                val listed = listModels(p, w.key, if (compatible) w.baseUrl else p.baseUrl)
                listed.onSuccess { models ->
                    w.models = models
                    w.model = w.preferModel?.takeIf { it in models } ?: Providers.recommended(p, models) ?: models.firstOrNull().orEmpty()
                    w.good = "The key works: ${models.size} models."
                    w.next()
                }.onFailure { w.message = "That key did not work: ${it.message ?: it::class.simpleName}" }
                w.checking = false
            }
        }, variant = BtnVariant.PRIMARY, enabled = w.key.isNotBlank() && !w.checking && (!compatible || Providers.addressProblem(w.baseUrl) == null), reason = if (compatible && Providers.addressProblem(w.baseUrl) != null) "The address first" else "Paste the key first", arrow = true)
        if (w.checking) Breathe()
    }
}

/**
 * Another provider's own fields (kai: "API keys that are openai compatible with different custom
 * providers"): a tap fills a known one's address, name and key page; or the address is typed from
 * its docs. The name is the connection's, so several providers live side by side.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CompatibleFields(ai: AiState, w: WizardState) {
    // The person's own, saved before (1.0.59): a tap fills the address, the name, the model and the key.
    val mine = SavedConnections.presets(ai.prefs.connections)
    if (mine.isNotEmpty()) {
        com.kaiharimoto.neue.kit.FieldLabel("Yours", hint = "saved before")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            mine.forEach { conn ->
                val address = conn.baseUrl.orEmpty()
                com.kaiharimoto.neue.kit.Tag(conn.label.ifBlank { SavedConnections.address(address) }, SavedConnections.address(w.baseUrl) == SavedConnections.address(address) && w.label == conn.label, {
                    w.baseUrl = address
                    w.label = conn.label
                    w.keyPage = Providers.compatiblePresets.firstOrNull { SavedConnections.address(it.baseUrl) == SavedConnections.address(address) }?.keyPage
                    w.preferModel = conn.model.takeIf { it.isNotBlank() }
                    ai.secret(conn)?.takeIf { it.isNotBlank() }?.let { w.key = it; w.keyFrom = conn.label.ifBlank { null } ?: "it" }
                    w.message = null
                }, caption = "Fill in")
            }
        }
        com.kaiharimoto.neue.kit.FieldLabel("Or a known provider")
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Providers.compatiblePresets.forEach { preset ->
            com.kaiharimoto.neue.kit.Tag(preset.name, w.baseUrl == preset.baseUrl, {
                w.baseUrl = preset.baseUrl
                w.label = preset.name
                w.keyPage = preset.keyPage
                w.preferModel = null
                w.message = null
            }, caption = "Fill in")
        }
    }
    com.kaiharimoto.neue.kit.FieldLabel("Name", hint = "yours, for this connection")
    MuInput(w.label, { w.label = it }, Modifier.fillMaxWidth(), placeholder = "DeepSeek, Groq, work gateway…")
    com.kaiharimoto.neue.kit.FieldLabel("API address", hint = "up to /v1")
    MuInput(w.baseUrl, { w.baseUrl = it.trim(); w.keyPage = Providers.compatiblePresets.firstOrNull { p -> p.baseUrl == it.trim() }?.keyPage }, Modifier.fillMaxWidth(), placeholder = "https://api.example.com/v1", mono = true)
    if (w.baseUrl.isNotBlank()) Providers.addressProblem(w.baseUrl)?.let { Help(it, color = Mu.colors.ink) }
}

/** A phone or tablet: a local model is on a computer across the Wi-Fi, never on the device. */
private val onDevice: Boolean get() = Platform.os == DesktopOs.ANDROID

@Composable
private fun ServerStep(w: WizardState) {
    val p = w.provider ?: return
    val scope = rememberCoroutineScope()
    Help(
        if (onDevice) {
            // A phone or tablet runs no model itself: it talks to the computer that does, over the Wi-Fi.
            when (p.id) {
                "ollama" -> "Ollama runs on your computer, not on this device. On the computer, set OLLAMA_HOST=0.0.0.0 and restart Ollama, then type the computer's address on your Wi-Fi."
                "lmstudio" -> "LM Studio runs on your computer. Start its server with Serve on Local Network on (Developer tab), then type the computer's address on your Wi-Fi."
                else -> "The address of the computer running the server, on your Wi-Fi, up to /v1, and a key if it asks for one."
            }
        } else {
            when (p.id) {
                "ollama" -> "Install Ollama, then pull a model that can call tools (for example `ollama pull qwen3`). Ollama listens on this computer by itself."
                "lmstudio" -> "In LM Studio, load a model and start the local server (Developer tab). It listens on port 1234."
                else -> "The server's address up to /v1, and a key if it asks for one."
            }
        },
    )
    MuInput(
        w.baseUrl,
        { w.baseUrl = it.trim() },
        Modifier.fillMaxWidth(),
        placeholder = if (onDevice) Providers.examplePhoneAddress(p) else p.baseUrl?.takeIf { it.isNotBlank() } ?: "http://localhost:8000/v1",
        mono = true,
    )
    if (onDevice && w.baseUrl.isNotBlank() && Providers.isThisDevice(w.baseUrl)) {
        Help("localhost here is this ${if (LocalPhone.current) "phone" else "tablet"}, not your computer. Use the computer's address, like ${Providers.examplePhoneAddress(p)}.", color = Mu.colors.ink)
    }
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
                }.onFailure {
                    w.message = "Could not reach ${w.baseUrl}: ${it.message ?: it::class.simpleName}" +
                        if (onDevice) " — is the server listening on the network, and is this device on the same Wi-Fi?" else ""
                }
                w.checking = false
            }
        }, variant = BtnVariant.PRIMARY, enabled = w.baseUrl.isNotBlank() && Providers.plainHttpAllowed(w.baseUrl) && !w.checking, reason = "An address first", arrow = true)
        if (w.checking) Breathe()
    }
}

/** What a provider offers, by its own list. */
internal suspend fun listModels(p: Provider, key: String, base: String?): Result<List<String>> = when (p.wire) {
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
    if (p.kind == ConnectKind.LOCAL || p.id == Providers.compatible.id) {
        Help("${w.name.ifBlank { "Ai" }} needs a model that can call tools to act in the app. Try it:")
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
    Help("${w.name.ifBlank { ai.name }} will talk through ${w.label.trim().ifBlank { p.label }}${w.model.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}.")
    Help("Open it any time with the ${ai.name} button in the bar${if (AiDesk.canRunCli) " or ${com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(com.kaiharimoto.mastertool.core.input.DeskAction.AI_PANEL)?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd).orEmpty()}" else ""}. It follows you to every page.")
    val connect = {
        val made = AiConnection(
            id = "${p.id}-${UUID.randomUUID().toString().take(6)}",
            provider = p.id,
            label = w.label.trim().ifBlank { p.label },
            model = w.model,
            baseUrl = w.baseUrl.takeIf { Providers.typedAddress(p) && it.isNotBlank() },
            program = w.program,
        )
        // The same service, name and model set up again replaces its connection, never a twin (1.0.59).
        val connection = SavedConnections.replaced(ai.prefs.connections, made)?.let { old -> made.copy(id = old.id, window = old.window) } ?: made
        ai.h.neue.update { it.copy(ai = it.ai.copy(effort = w.effort, alwaysAllow = w.alwaysAllow)) }
        ai.connect(connection, w.key.takeIf { p.needsKey || it.isNotBlank() })
        ai.wizard = WizardState(ai.name)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MuButton("Start chatting", { connect() }, variant = BtnVariant.PRIMARY, arrow = true)
        // kai (1.0.46): one way in, and a look at what it can do for anyone who wants one first.
        MuButton("What can you do?", {
            connect()
            ai.demoOpen = true
        }, variant = BtnVariant.GHOST)
    }
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
internal fun Choice(title: String, line: String, selected: Boolean, enabled: Boolean = true, reason: String? = null, onClick: () -> Unit) {
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
