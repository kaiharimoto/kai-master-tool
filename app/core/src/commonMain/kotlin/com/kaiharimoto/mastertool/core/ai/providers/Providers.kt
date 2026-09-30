package com.kaiharimoto.mastertool.core.ai.providers

import com.kaiharimoto.mastertool.core.update.DesktopOs

/** How a provider is reached: which backend the app builds for it. */
enum class Wire {
    /** The user's own `claude` CLI (Claude Code), on their Claude plan. Desktop only. */
    CLAUDE_CLI,

    /** The user's own `codex` CLI, on their ChatGPT plan. Desktop only. */
    CODEX_CLI,

    /** Anthropic's API with a key, through the official SDK. */
    ANTHROPIC,

    /** Any OpenAI-compatible Chat Completions endpoint. */
    OPENAI_COMPAT,
}

/** The wizard's first question, "how do you want to connect?", answered. */
enum class ConnectKind(val title: String, val line: String) {
    PLAN("I pay for Claude or ChatGPT", "Use your subscription through its command-line app on this computer."),
    KEY("I have an API key", "Anthropic, OpenAI, Google Gemini or OpenRouter, billed by use."),
    LOCAL("Run it on my own machine", "Ollama, LM Studio, or any OpenAI-compatible server."),
}

/** One way to reach a model, with everything the wizard needs to walk the person through it. */
data class Provider(
    val id: String,
    val label: String,
    val kind: ConnectKind,
    val wire: Wire,
    val blurb: String,
    /** Where the base URL is, for an API ([Wire.ANTHROPIC], [Wire.OPENAI_COMPAT]). */
    val baseUrl: String? = null,
    /** Where a key is made. */
    val keyPage: String? = null,
    /** The environment variable a key is often already in, offered on the desktop. */
    val envVars: List<String> = emptyList(),
    /** What the key looks like, to catch a paste of the wrong thing ("sk-ant-"). */
    val keyPrefix: String? = null,
    val needsKey: Boolean = false,
    /** Model ids to prefer, in order, from what the provider lists (substring match). */
    val prefer: List<String> = emptyList(),
    /** Models to offer when the provider cannot list them (the CLIs' aliases). */
    val presetModels: List<String> = emptyList(),
    /** Effort levels the model takes; empty when it has none. */
    val efforts: List<String> = emptyList(),
    val defaultEffort: String = "",
    val desktopOnly: Boolean = false,
    /** The program's name on the PATH, for a CLI. */
    val program: String? = null,
    /** A line the wizard must say plainly before the person commits. */
    val caution: String? = null,
    /** Extra headers every request carries. */
    val headers: Map<String, String> = emptyMap(),
    val sendsEffort: Boolean = false,
    /** Docs for the provider, for "Read more". */
    val docs: String? = null,
)

object Providers {
    val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

    val claudeCode = Provider(
        id = "claude-code",
        label = "Claude Code",
        kind = ConnectKind.PLAN,
        wire = Wire.CLAUDE_CLI,
        blurb = "Your Claude Pro or Max plan, through Claude Code installed on this computer.",
        presetModels = listOf("", "opus", "sonnet", "fable"),
        efforts = EFFORTS,
        defaultEffort = "medium",
        desktopOnly = true,
        program = "claude",
        caution = "This uses your own Claude Code login on this computer; the app never sees your password or token. " +
            "Anthropic's terms restrict third-party products from offering claude.ai login, so treat this as personal use " +
            "at your own discretion. An Anthropic API key is the fully supported way.",
        docs = "https://code.claude.com/docs/en/setup",
    )

    val codex = Provider(
        id = "codex",
        label = "Codex",
        kind = ConnectKind.PLAN,
        wire = Wire.CODEX_CLI,
        blurb = "Your ChatGPT Plus or Pro plan, through OpenAI's Codex CLI installed on this computer.",
        presetModels = listOf(""),
        efforts = listOf("low", "medium", "high"),
        defaultEffort = "medium",
        desktopOnly = true,
        program = "codex",
        docs = "https://developers.openai.com/codex/cli",
    )

    val anthropic = Provider(
        id = "anthropic",
        label = "Anthropic",
        kind = ConnectKind.KEY,
        wire = Wire.ANTHROPIC,
        blurb = "Claude through Anthropic's API, with a key from the Claude Console.",
        baseUrl = "https://api.anthropic.com",
        keyPage = "https://console.anthropic.com/settings/keys",
        envVars = listOf("ANTHROPIC_API_KEY"),
        keyPrefix = "sk-ant-",
        needsKey = true,
        prefer = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5"),
        efforts = EFFORTS,
        defaultEffort = "medium",
        docs = "https://docs.claude.com/en/api/getting-started",
    )

    val openai = Provider(
        id = "openai",
        label = "OpenAI",
        kind = ConnectKind.KEY,
        wire = Wire.OPENAI_COMPAT,
        blurb = "GPT models through OpenAI's API, with a key from the OpenAI platform.",
        baseUrl = "https://api.openai.com/v1",
        keyPage = "https://platform.openai.com/api-keys",
        envVars = listOf("OPENAI_API_KEY"),
        keyPrefix = "sk-",
        needsKey = true,
        prefer = listOf("gpt-5", "gpt-4.1", "gpt-4o", "o4", "o3"),
        efforts = listOf("low", "medium", "high"),
        sendsEffort = true,
        docs = "https://platform.openai.com/docs/quickstart",
    )

    val gemini = Provider(
        id = "gemini",
        label = "Google Gemini",
        kind = ConnectKind.KEY,
        wire = Wire.OPENAI_COMPAT,
        blurb = "Gemini through Google AI Studio's API key (its OpenAI-compatible endpoint).",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        keyPage = "https://aistudio.google.com/apikey",
        envVars = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
        keyPrefix = "AI",
        needsKey = true,
        prefer = listOf("gemini-3", "gemini-2.5-pro", "gemini-2.5-flash"),
        docs = "https://ai.google.dev/gemini-api/docs/openai",
    )

    val openrouter = Provider(
        id = "openrouter",
        label = "OpenRouter",
        kind = ConnectKind.KEY,
        wire = Wire.OPENAI_COMPAT,
        blurb = "Hundreds of models behind one key, billed through OpenRouter.",
        baseUrl = "https://openrouter.ai/api/v1",
        keyPage = "https://openrouter.ai/keys",
        envVars = listOf("OPENROUTER_API_KEY"),
        keyPrefix = "sk-or-",
        needsKey = true,
        prefer = listOf("anthropic/claude-opus-5", "anthropic/claude-sonnet-5", "openai/gpt-5", "google/gemini"),
        headers = mapOf("HTTP-Referer" to "https://github.com/kaiharimoto/kai-master-tool", "X-Title" to "Neue Master Tool"),
        docs = "https://openrouter.ai/docs/quickstart",
    )

    val ollama = Provider(
        id = "ollama",
        label = "Ollama",
        kind = ConnectKind.LOCAL,
        wire = Wire.OPENAI_COMPAT,
        blurb = "Models running on your own computer with Ollama. Choose one that can call tools.",
        baseUrl = "http://localhost:11434/v1",
        docs = "https://ollama.com/download",
    )

    val lmstudio = Provider(
        id = "lmstudio",
        label = "LM Studio",
        kind = ConnectKind.LOCAL,
        wire = Wire.OPENAI_COMPAT,
        blurb = "Models running in LM Studio's local server. Choose one that can call tools.",
        baseUrl = "http://localhost:1234/v1",
        docs = "https://lmstudio.ai/docs/app/api",
    )

    val custom = Provider(
        id = "custom",
        label = "Another server",
        kind = ConnectKind.LOCAL,
        wire = Wire.OPENAI_COMPAT,
        blurb = "Any server that speaks OpenAI's Chat Completions: vLLM, llama.cpp, a gateway at work.",
        baseUrl = "",
    )

    val all = listOf(claudeCode, codex, anthropic, openai, gemini, openrouter, ollama, lmstudio, custom)

    fun byId(id: String?): Provider? = all.firstOrNull { it.id == id }

    fun of(kind: ConnectKind): List<Provider> = all.filter { it.kind == kind }

    /** Whether [provider] can run on [os] (the CLIs need a desktop). */
    fun available(provider: Provider, os: DesktopOs): Boolean = !provider.desktopOnly || os != DesktopOs.ANDROID

    /**
     * The model the wizard picks first from what the provider listed: the first of
     * [Provider.prefer] that matches (newest-looking first among its matches), else
     * the first model listed.
     */
    fun recommended(provider: Provider, listed: List<String>): String? {
        val usable = chatModels(listed)
        provider.prefer.forEach { want ->
            usable.filter { it.contains(want, ignoreCase = true) }
                .sortedWith(compareBy<String> { it.contains("preview") || it.contains("exp") }.thenByDescending { it })
                .firstOrNull()?.let { return it }
        }
        return usable.firstOrNull()
    }

    /** What a model list offers for chat: embeddings, speech, images and moderation left out. */
    fun chatModels(listed: List<String>): List<String> = listed.filterNot { id ->
        val m = id.lowercase()
        NOT_CHAT.any { it in m }
    }.distinct()

    private val NOT_CHAT = listOf("embed", "whisper", "tts", "dall-e", "moderation", "image", "audio", "transcribe", "realtime", "search-preview", "davinci", "babbage")

    /** How to install a CLI on [os], as a command to copy. */
    fun install(provider: Provider, os: DesktopOs): List<String> = when (provider.wire) {
        Wire.CLAUDE_CLI -> when (os) {
            DesktopOs.WINDOWS -> listOf("irm https://claude.ai/install.ps1 | iex", "npm install -g @anthropic-ai/claude-code")
            else -> listOf("curl -fsSL https://claude.ai/install.sh | bash", "npm install -g @anthropic-ai/claude-code")
        }
        Wire.CODEX_CLI -> when (os) {
            DesktopOs.MAC -> listOf("brew install --cask codex", "npm install -g @openai/codex")
            else -> listOf("npm install -g @openai/codex")
        }
        else -> emptyList()
    }

    /** How to sign a CLI in, as a command to run in a terminal. */
    fun login(provider: Provider): String? = when (provider.wire) {
        Wire.CLAUDE_CLI -> "claude auth login"
        Wire.CODEX_CLI -> "codex login"
        else -> null
    }

    /**
     * Whether [url] may be reached over plain `http`: only on this machine or the
     * local network. Anything on the internet must be `https`, where a key would
     * otherwise cross it in the clear.
     */
    fun plainHttpAllowed(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.startsWith("https://")) return true
        if (!u.startsWith("http://")) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':').removePrefix("[").removeSuffix("]")
        if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127) || (a == 169 && b == 254)
    }

    /** A key that looks wrong for [provider], in words, or null when it will do. */
    fun keyProblem(provider: Provider, key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return if (provider.needsKey) "Paste the key first." else null
        if (k.any { it.isWhitespace() }) return "A key has no spaces; paste only the key."
        val prefix = provider.keyPrefix ?: return null
        if (!k.startsWith(prefix)) return "${provider.label} keys start with “$prefix”. Is this the right key?"
        return null
    }
}
