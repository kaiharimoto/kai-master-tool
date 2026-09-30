package com.kaiharimoto.mastertool.core.ai.wire

/**
 * What each Claude model accepts, so the request the app builds never earns a 400
 * for a field the chosen model does not take. Read off the model's id:
 * `claude-<family>-<major>[-<minor>]`.
 *
 * - Adaptive thinking and `effort` from the 4.6 generation on (Haiku 4.5 takes
 *   neither); `xhigh` from 4.7.
 * - Server-side refusal fallbacks (`fallbacks: "default"`) on the models Anthropic
 *   lists for it: Fable 5.1, Opus 5.5, Opus 5 and Sonnet 5.5.
 */
object AnthropicModels {
    const val DEFAULT = "claude-opus-5-5"
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private val FALLBACKS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    data class Id(val family: String, val major: Int, val minor: Int)

    fun parse(model: String): Id? {
        val m = Regex("^claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-\\d{8})?$").find(model.trim()) ?: return null
        return Id(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 0)
    }

    private fun atLeast(id: Id, major: Int, minor: Int) = id.major > major || (id.major == major && id.minor >= minor)

    /** Sends `thinking: {type: "adaptive"}`. Unknown ids (a newer model) are assumed current. */
    fun adaptive(model: String): Boolean {
        val id = parse(model) ?: return true
        if (id.family == "haiku") return false
        return atLeast(id, 4, 6)
    }

    /** The effort to send, clamped to what the model takes; null sends none. */
    fun effort(model: String, wanted: String): String? {
        if (wanted.isBlank()) return null
        val id = parse(model)
        if (id != null && (id.family == "haiku" || !atLeast(id, 4, 6))) return null
        if (wanted == "xhigh" && id != null && !atLeast(id, 4, 7)) return "high"
        return wanted
    }

    fun fallbacks(model: String): Boolean = model.trim() in FALLBACKS

    /** Room for an answer: generous, since the stream never times out on length. */
    fun maxTokens(model: String): Long {
        val id = parse(model) ?: return 32_000
        return if (id.family == "haiku") 16_000 else 32_000
    }
}
