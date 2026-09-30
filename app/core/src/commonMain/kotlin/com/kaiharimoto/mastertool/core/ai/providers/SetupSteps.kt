package com.kaiharimoto.mastertool.core.ai.providers

/**
 * The setup wizard's steps (kai: "guided in an intuitive way … step by step for each
 * provider", after Hermes's `hermes model`). Which steps a provider has depends on
 * how it is reached: a CLI is installed and signed in, a key is pasted and tried, a
 * local server is found. Every path ends by choosing a model, how much Ai may do
 * without asking, and a first hello.
 */
enum class SetupStep(val title: String) {
    NAME("Name your assistant"),
    CONNECT("How do you want to connect?"),
    PROVIDER("Choose a provider"),
    INSTALL("Install"),
    SIGN_IN("Sign in"),
    KEY("Your API key"),
    SERVER("Find the server"),
    MODEL("Choose a model"),
    PERMISSIONS("What may it do on its own?"),
    DONE("Ready"),
}

object SetupSteps {
    fun of(provider: Provider?): List<SetupStep> = buildList {
        add(SetupStep.NAME)
        add(SetupStep.CONNECT)
        add(SetupStep.PROVIDER)
        when (provider?.wire) {
            Wire.CLAUDE_CLI, Wire.CODEX_CLI -> {
                add(SetupStep.INSTALL)
                add(SetupStep.SIGN_IN)
            }
            Wire.ANTHROPIC -> add(SetupStep.KEY)
            Wire.OPENAI_COMPAT -> add(if (provider.kind == ConnectKind.LOCAL) SetupStep.SERVER else SetupStep.KEY)
            null -> Unit
        }
        if (provider != null) {
            add(SetupStep.MODEL)
            add(SetupStep.PERMISSIONS)
            add(SetupStep.DONE)
        }
    }

    fun next(step: SetupStep, provider: Provider?): SetupStep? = of(provider).let { steps -> steps.getOrNull(steps.indexOf(step) + 1) }

    fun back(step: SetupStep, provider: Provider?): SetupStep? = of(provider).let { steps -> steps.getOrNull(steps.indexOf(step) - 1) }

    /** "Step 3 of 7" for the wizard's header; the count is known once a provider is chosen. */
    fun position(step: SetupStep, provider: Provider?): Pair<Int, Int> {
        val steps = of(provider)
        val total = if (provider == null) 7 else steps.size
        return (steps.indexOf(step) + 1) to total
    }
}
