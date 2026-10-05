package com.kaiharimoto.mastertool.core.ai.providers

/**
 * A model's id as a person says it (Ai vs Ai's seats, its records): `claude-opus-5-5` is "Opus 5.5", `gpt-5` is "GPT-5",
 * `gemini-2.5-pro` is "Gemini 2.5 Pro". Display only — the id is what is kept — so the rule can change freely. Dates,
 * a provider's routing prefix and a version tag are dropped; an id it does not know is tidied, never refused.
 */
object ModelNames {
    private val date = Regex("""[-_@](20\d{6}|20\d{2}-\d{2}-\d{2}|\d{4})$""")
    private val version = Regex("""-v\d+(:\d+)?$""")
    private val serial = Regex("""-\d{3}$""")

    fun short(id: String): String {
        var s = id.trim()
        if (s.isEmpty()) return s
        // A cloud's routing prefix ("us.anthropic.", "models/", "openai/").
        s = s.substringAfterLast('/')
        s.indexOf("claude").takeIf { it > 0 }?.let { s = s.substring(it) }
        s = version.replace(s, "")
        s = s.removeSuffix("-latest")
        repeat(2) { s = date.replace(s, "") }
        s = serial.replace(s, "")
        val lower = s.lowercase()
        return when {
            lower.startsWith("claude") -> claude(lower)
            lower.startsWith("gpt-") -> gpt(s)
            // OpenAI's reasoning models are written as they are: o3, o4-mini.
            Regex("""^o\d""").containsMatchIn(lower) -> lower
            else -> words(s)
        }
    }

    /** `claude-opus-5-5` → "Opus 5.5"; `claude-3-5-sonnet` → "Sonnet 3.5"; `claude-2.1` → "Claude 2.1". */
    private fun claude(id: String): String {
        val parts = id.removePrefix("claude").trim('-', '_').split('-', '_').filter { it.isNotEmpty() }
        val family = parts.firstOrNull { p -> p.any(Char::isLetter) }
        val numbers = parts.filter { p -> p.all { it.isDigit() || it == '.' } && p.length <= 3 }
        val name = family?.replaceFirstChar { it.uppercase() } ?: "Claude"
        return if (numbers.isEmpty()) name else "$name ${numbers.joinToString(".")}"
    }

    /** `gpt-5` → "GPT-5"; `gpt-4.1-mini` → "GPT-4.1 mini". */
    private fun gpt(id: String): String {
        val parts = id.substring(4).split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "GPT"
        return (listOf("GPT-${parts.first()}") + parts.drop(1)).joinToString(" ")
    }

    /** Any other id: its words split on dashes and colons, each capitalised — `gemini-2.5-pro` → "Gemini 2.5 Pro". */
    private fun words(id: String): String =
        id.split('-', '_', ':', ' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
