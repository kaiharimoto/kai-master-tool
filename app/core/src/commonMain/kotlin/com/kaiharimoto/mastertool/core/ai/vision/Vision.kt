package com.kaiharimoto.mastertool.core.ai.vision

/**
 * Whether a model can see a picture (1.0.55). No provider says so in its list of models, so this
 * is read off the names: every Claude and Gemini model can, OpenAI's since GPT-4o, and the open
 * models that say so in their names (`-vl`, `vision`, `llava`…). [Sight.MAYBE] is a model this
 * cannot tell — sent anyway, since the provider's refusal is clear when it comes.
 */
object Vision {
    enum class Sight { YES, NO, MAYBE }

    private val sees = listOf(
        "claude", "gemini", "gpt-4o", "gpt-4.1", "gpt-4.5", "gpt-5", "o3", "o4", "chatgpt-4o",
        "vision", "-vl", "vl-", "llava", "bakllava", "pixtral", "gemma3", "gemma-3", "llama-4",
        "llama3.2-vision", "llama-3.2-11b-vision", "llama-3.2-90b-vision", "minicpm-v", "moondream",
        "grok-4", "grok-2-vision", "qwen2.5-vl", "qwen-vl", "qvq", "internvl", "glm-4v", "kimi-vl",
        "mistral-medium-3", "mistral-small-3.1", "mistral-small-3.2", "phi-4-multimodal", "granite-vision",
    )

    private val blind = listOf(
        "gpt-3.5", "deepseek-chat", "deepseek-reasoner", "deepseek-r1", "deepseek-v3", "o1-mini", "o3-mini",
        "codestral", "mixtral", "qwen2.5-coder", "qwq", "llama3.1", "llama-3.1", "llama3:", "gpt-oss",
    )

    fun of(provider: String, model: String): Sight {
        val m = model.lowercase()
        if (provider in setOf("anthropic", "claude-code", "gemini")) return Sight.YES
        if (blind.any { it in m }) return Sight.NO
        if (sees.any { it in m }) return Sight.YES
        if (provider == "codex") return Sight.YES
        return Sight.MAYBE
    }

    /** Whether a provider's error says the model cannot take a picture. */
    fun refused(message: String): Boolean {
        val m = message.lowercase()
        return listOf("image", "vision", "multimodal", "image_url").any { it in m } &&
            listOf("not support", "unsupported", "does not support", "cannot", "invalid", "unknown", "not enabled", "no endpoints").any { it in m }
    }

    const val REFUSED = "This model can't see pictures. Pick one that can in quick settings (the model's name at the top of the panel), or send words alone."
}
