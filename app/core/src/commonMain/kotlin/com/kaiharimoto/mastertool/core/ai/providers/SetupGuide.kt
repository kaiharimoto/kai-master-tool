package com.kaiharimoto.mastertool.core.ai.providers

/**
 * What the first setup says beside its steps (kai: "the guide should be intuitive and
 * give the user everything they need to set up without issues"): what the person needs
 * before they start, for the way they chose, and for each step the ways it goes wrong
 * and what to do about each. Written once here so every path is covered by a test,
 * not by whoever last touched the wizard.
 */
object SetupGuide {
    /** One thing that goes wrong, and what to do. */
    data class Trouble(val problem: String, val fix: String)

    /** Before choosing: the three ways, in a line each, and how to pick. */
    val choosing: List<String> = listOf(
        "Already pay for Claude Pro or Max, or ChatGPT Plus or Pro? Use that plan — nothing more to pay. It needs the desktop app.",
        "Otherwise an API key: you pay the provider for what you use, a few cents a conversation with the strongest models.",
        "Or run a model on your own computer, free and private, if it is powerful enough — smaller models build decks less well.",
    )

    /**
     * What to have ready for [kind], or for [provider] once one is chosen. [onDevice] is a
     * phone or tablet, where a local model runs on a computer on the same Wi-Fi, never on it.
     */
    fun needs(kind: ConnectKind?, provider: Provider?, onDevice: Boolean = false): List<String> = when {
        onDevice && provider != null && provider.kind == ConnectKind.LOCAL -> listOf(
            "A computer on the same Wi-Fi running ${if (provider.id == "custom") "the server" else provider.label}, with a model that can call tools.",
            if (provider.id == "ollama") "Ollama set to listen on the network: OLLAMA_HOST=0.0.0.0 on that computer, then restart Ollama."
            else if (provider.id == "lmstudio") "LM Studio's server set to Serve on Local Network (Developer tab)."
            else "The server listening on the network, not only on localhost.",
            "That computer's address on the Wi-Fi, like 192.168.1.20.",
        )
        onDevice && kind == ConnectKind.LOCAL && provider == null -> listOf("A computer on the same Wi-Fi running a model server; this phone or tablet talks to it.")
        provider != null -> when (provider.id) {
            "claude-code" -> listOf("A Claude Pro or Max plan.", "Nothing installed beforehand: the app gives you one line to paste into a terminal, and opens the terminal.", "About five minutes.")
            "codex" -> listOf("A ChatGPT Plus, Pro or Team plan.", "Node.js (nodejs.org), or Homebrew on a Mac, to install Codex.", "A terminal, which the app opens for you.", "About five minutes.")
            "anthropic" -> listOf("An Anthropic account (console.anthropic.com).", "Credit on it: five dollars goes a long way.", "About two minutes.")
            "openai" -> listOf("An OpenAI platform account (platform.openai.com) — not the same as ChatGPT.", "Credit on it.", "About two minutes.")
            "gemini" -> listOf("A Google account.", "Nothing else: Google AI Studio has a free tier.", "About two minutes.")
            "openrouter" -> listOf("An OpenRouter account.", "Credit on it; one key reaches many providers' models.", "About two minutes.")
            "ollama" -> listOf("Ollama installed (ollama.com).", "A model that can call tools, pulled: for example qwen3.", "A computer with 16 GB of memory or more for a useful model.")
            "lmstudio" -> listOf("LM Studio installed (lmstudio.ai).", "A model loaded that can call tools.", "Its local server started, in the Developer tab.")
            else -> listOf("The server's address, up to /v1.", "Its key, if it asks for one.", "A model on it that can call tools.")
        }
        kind == ConnectKind.PLAN -> listOf("A Claude or ChatGPT subscription.", "This computer: the plans work through their command-line apps.")
        kind == ConnectKind.KEY -> listOf("An account with the provider, and credit (Gemini has a free tier).")
        kind == ConnectKind.LOCAL -> listOf("A model server running on this computer or your network.")
        else -> listOf("An account with Anthropic, OpenAI, Google or OpenRouter — or a Claude or ChatGPT plan — or a model on your own computer.", "A few minutes.")
    }

    /** What goes wrong at [step] with [provider], and the fix. Empty where nothing usually does. */
    fun trouble(step: SetupStep, provider: Provider?, onDevice: Boolean = false): List<Trouble> = when (step) {
        SetupStep.NAME, SetupStep.CONNECT, SetupStep.PROVIDER, SetupStep.PERMISSIONS, SetupStep.DONE -> emptyList()
        SetupStep.INSTALL -> listOf(
            Trouble("“npm” is not recognised", "Install Node.js from nodejs.org first, then run the line again in a new terminal."),
            Trouble("Installed, but not found here", "Close and reopen this app so it sees the new PATH, or paste the program's full path below."),
            Trouble("Permission denied", if (provider?.id == "codex") "Use the Homebrew line, or put sudo in front of the npm line." else "Use the installer line rather than npm; it needs no administrator."),
        )
        SetupStep.SIGN_IN -> listOf(
            Trouble("The browser did not open", "Copy the link the terminal prints into your browser."),
            Trouble("Signed in, but it still says not", "Close the terminal once it says you are logged in, then Check again."),
            Trouble("Signed in with the wrong account", if (provider?.wire == Wire.CODEX_CLI) "Run codex logout, then Sign in again." else "Run claude, type /logout, then Sign in again."),
        )
        SetupStep.KEY -> buildList {
            add(Trouble("401 or “invalid key”", "Copy the whole key again — it is shown once; if it is lost, make a new one."))
            if (provider?.id != "gemini") add(Trouble("402, 429 or “credit balance too low”", "Add credit to the account; a new key works only once there is some."))
            add(Trouble("Could not connect", "Check the internet connection, and that no firewall or VPN blocks ${provider?.baseUrl?.substringAfter("://")?.substringBefore('/') ?: "the provider"}."))
            provider?.keyPrefix?.let { add(Trouble("It says the key looks wrong", "A key for ${provider.label} starts with $it — a key from another provider will not work here.")) }
        }
        SetupStep.SERVER -> if (onDevice) listOf(
            Trouble("localhost does nothing here", "On a phone or tablet, localhost is the device itself. Type the computer's address instead, like ${provider?.let(Providers::examplePhoneAddress) ?: "http://192.168.1.20:11434/v1"}."),
            Trouble("Connection refused or timed out", if (provider?.id == "ollama") "Ollama listens only on its own computer until told otherwise: set OLLAMA_HOST=0.0.0.0 there and restart it. Check the computer's firewall allows the port." else if (provider?.id == "lmstudio") "Turn on Serve on Local Network in LM Studio's Developer tab, and check the firewall." else "Have the server listen on 0.0.0.0, not 127.0.0.1, and check the computer's firewall."),
            Trouble("Finding the computer's address", "Windows: ipconfig, the IPv4 Address. Mac: System Settings, Wi-Fi, Details. Linux: ip addr. Both must be on the same Wi-Fi."),
            Trouble("Found, but no models", if (provider?.id == "ollama") "Pull one on the computer: ollama pull qwen3." else "Load a model into the server first."),
        ) else listOf(
            Trouble("Connection refused", if (provider?.id == "lmstudio") "Start the server in LM Studio's Developer tab." else if (provider?.id == "ollama") "Open Ollama, or run ollama serve in a terminal." else "Start the server, and check the address and port."),
            Trouble("Found, but no models", if (provider?.id == "ollama") "Pull one: ollama pull qwen3." else "Load a model into the server first."),
            Trouble("Another computer on the network", "Use its address (http://192.168.…), and have the server listen beyond localhost."),
        )
        SetupStep.MODEL -> buildList {
            add(Trouble("Not sure which", "Take the one marked Recommended; you can change it later in Settings."))
            if (provider?.kind == ConnectKind.LOCAL) {
                add(Trouble("It did not call the tool", "The model can chat but not act in the app. Try a larger model or one made for tools (qwen3, llama3.1, mistral-small)."))
                add(Trouble("Answers stop short or forget the start", "The app's instructions and tools are long: give the model a context length of 32k or more (Ollama: num_ctx; LM Studio: Context Length)."))
            }
            else add(Trouble("A model is missing from the list", "Your account may not have it yet; type its id in the box instead."))
        }
    }
}
