package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ModelBackend
import com.kaiharimoto.mastertool.core.ai.mcp.McpServerCore
import com.kaiharimoto.mastertool.core.ai.memory.Persona
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.SetupStep
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiChatBackend
import com.kaiharimoto.mastertool.core.ai.wire.OpenAiEndpoint
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Ai's connections and their setup, on [AiState]: the wizard, the backend a connection is spoken to through,
// the app's MCP server for a plan's command-line app, keys, and the name.

/**
 * Opens the wizard in the panel, from its start unless it is part-way through. With a
 * connection already made, the start is the connections themselves (1.0.59): use one, or add.
 */
fun AiState.openWizard(adding: Boolean = false) {
    if (wizard.step == SetupStep.NAME || adding) {
        wizard.name = ownName
        wizard.saved = configured && !adding
        if (adding && configured) wizard.step = SetupStep.CONNECT
    }
    wizardOpen = true
    historyOpen = false
    if (!prefs.panelOpen) h.neue.update { it.copy(ai = it.ai.copy(panelOpen = true)) }
}

internal fun AiState.backendFor(connection: AiConnection): ModelBackend {
    val key = "${connection.id}:${connection.model}:${connection.baseUrl}:${connection.program}"
    backend?.takeIf { it.first == key }?.let { return it.second }
    val made = newBackend(connection)
    (backend?.second as? AnthropicBackend)?.close()
    backend = key to made
    return made
}

/**
 * A backend of [connection]'s own, never the panel's kept one (an Ai vs Ai match's seats, `docs/phases/C.md` §6): two
 * seats on two connections would otherwise close each other's. The caller closes it when done ([closeBackend]).
 */
internal fun AiState.newBackend(connection: AiConnection): ModelBackend {
    val provider = Providers.byId(connection.provider) ?: error("Unknown provider ${connection.provider}")
    return when (provider.wire) {
        Wire.ANTHROPIC -> AnthropicBackend(secret(connection) ?: error("No key saved for ${provider.label}."), connection.baseUrl)
        Wire.OPENAI_COMPAT -> {
            val base = connection.baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl ?: error("No server address.")
            if (!Providers.plainHttpAllowed(base)) error("$base is not encrypted; use https, or a server on this machine or network.")
            OpenAiChatBackend(http, OpenAiEndpoint(base, secret(connection), provider.headers, provider.sendsEffort), System::currentTimeMillis)
        }
        Wire.CLAUDE_CLI, Wire.CODEX_CLI -> {
            if (!AiDesk.canRunCli) error("${provider.label} runs on the desktop app only.")
            val program = connection.program ?: error("Set up ${provider.label} again: the app lost where it is installed.")
            // A working folder outside Ai's (1.0.99): nothing of Ai's, and no key, where the CLI is pointed.
            CliBackend(provider.wire, program, CliRun.folder(Platform.dataDir), files.root, mcpServer() ?: error("The app could not open its tools to ${provider.label}."))
        }
    }
}

/** A backend made by [newBackend], let go. */
internal fun closeBackend(b: ModelBackend) {
    (b as? AnthropicBackend)?.close()
}

/** The app's MCP server, started on first use by a CLI. */
private fun AiState.mcpServer(): McpHandle? {
    mcp?.let { return it }
    val core = McpServerCore(
        tools = { tools },
        call = { call -> withContext(Dispatchers.Main) { host.run(call) } },
        serverName = "neue",
        serverVersion = Platform.version,
        instructions = "The tools of Neue Master Tool, a Yu-Gi-Oh! deck builder: read and change its decks, webs, siding plans and settings.",
    )
    mcp = AiDesk.startMcp { _, body -> core.handle(body) }
    return mcp
}

fun AiState.secret(connection: AiConnection): String? = SecretStore.get(secretKey(connection.id))

fun AiState.secretKey(id: String) = "connection:$id"

/** A connection saved and made the one in use, its key (if any) in the secret store. */
fun AiState.connect(connection: AiConnection, key: String?) {
    if (!key.isNullOrBlank()) SecretStore.put(secretKey(connection.id), key.trim())
    h.neue.update { p ->
        val others = p.ai.connections.filterNot { it.id == connection.id }
        p.copy(ai = p.ai.copy(connections = others + connection, active = connection.id))
    }
    backend = null
    wizardOpen = false
    if (session?.connection != connection.id) newChat()
}

fun AiState.forget(connectionId: String) {
    SecretStore.remove(secretKey(connectionId))
    h.neue.update { p -> p.copy(ai = p.ai.copy(connections = p.ai.connections.filterNot { it.id == connectionId })) }
    backend = null
}

/** A connection changed in place — its model, its label (quick settings, 1.0.54) — the conversation kept. */
fun AiState.tweak(connectionId: String, change: (AiConnection) -> AiConnection) {
    h.neue.update { p -> p.copy(ai = p.ai.copy(connections = p.ai.connections.map { if (it.id == connectionId) change(it) else it })) }
    backend = null
}

fun AiState.use(connectionId: String) {
    h.neue.update { it.copy(ai = it.ai.copy(active = connectionId)) }
    backend = null
    newChat()
}

fun AiState.rename(to: String) {
    val old = ownName
    val clean = to.trim().take(AiPrefs.MAX_NAME).ifBlank { AiPrefs.DEFAULT_NAME }
    if (clean == old) return
    files.read(Persona.FILE)?.let { files.write(Persona.FILE, Persona.rename(it, old, clean)) }
    h.neue.update { it.copy(ai = it.ai.copy(name = clean)) }
    // The conversation on screen was begun under the old name, and its instructions are
    // never rewritten (the cache): its next message says the new one (1.0.46).
    if (session?.turns?.isNotEmpty() == true) renamedTo = clean
}

/** Ai off: every trace gone, nothing running, nothing listening. */
fun AiState.shutDown() {
    stop()
    cancelBackground()
    mcp?.stop()
    mcp = null
    (backend?.second as? AnthropicBackend)?.close()
    backend = null
    wizardOpen = false
}
