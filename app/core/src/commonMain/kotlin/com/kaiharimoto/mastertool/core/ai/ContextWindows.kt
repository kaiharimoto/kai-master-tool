package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.memory.MemoryBudget

/**
 * How many tokens a model can read at once (1.0.56): the size of the gauge in Ai's panel, and the
 * budget past which a conversation's oldest turns are summarised. Read off the model's name, as
 * no provider lists it with the model; a connection may say otherwise (`AiConnection.window`).
 */
object ContextWindows {
    fun of(provider: String, model: String, local: Boolean = false): Int {
        val m = model.lowercase()
        return when {
            "[1m]" in m || m.endsWith("-1m") || "1m-context" in m -> 1_000_000
            provider == "anthropic" || provider == "claude-code" || m.startsWith("claude") -> 200_000
            m.startsWith("gemini") || provider == "gemini" -> 1_000_000
            m.startsWith("gpt-4.1") -> 1_000_000
            m.startsWith("gpt-5") -> 400_000
            m.startsWith("o3") || m.startsWith("o4") -> 200_000
            m.startsWith("gpt-4o") || m.startsWith("chatgpt-4o") -> 128_000
            provider == "codex" -> 400_000
            "deepseek" in m -> 128_000
            "grok-4" in m -> 256_000
            "llama-4" in m -> 1_000_000
            "kimi" in m || "moonshot" in m -> 128_000
            "mistral" in m || "codestral" in m -> 128_000
            "qwen" in m -> if (local) 32_000 else 128_000
            local -> 32_000
            else -> 128_000
        }
    }

    /** A number of tokens as people read it: 950, 31k, 1.2M. */
    fun words(tokens: Long): String = when {
        tokens < 1_000 -> tokens.toString()
        tokens < 100_000 -> "${(tokens + 500) / 1_000}k".let { if (tokens < 10_000) "${tokens / 1_000}.${(tokens % 1_000) / 100}k" else it }
        tokens < 1_000_000 -> "${(tokens + 500) / 1_000}k"
        else -> "${tokens / 1_000_000}.${(tokens % 1_000_000) / 100_000}M".replace(".0M", "M")
    }
}

/**
 * What fills a conversation's window (1.0.56), part by part, estimated at four characters a token
 * and scaled to what the provider last measured when it has: the Context panel's bars, and what
 * `context_status` tells Ai.
 */
object ContextBreakdown {
    data class Slice(val label: String, val tokens: Long)

    /**
     * [system] split into the voice and instructions, the rules primer, the memory and the skills;
     * then the tool specs, the summary, the conversation's words, the tools' results and the pictures.
     * [measured] is what the provider counted last round, or 0.
     */
    fun of(session: AiSession, tools: List<ToolSpec>, measured: Long = 0): List<Slice> {
        val system = session.system
        fun section(start: String, vararg ends: String): Int {
            val a = system.indexOf(start)
            if (a < 0) return 0
            val b = ends.map { system.indexOf(it, a + start.length) }.filter { it > a }.minOrNull() ?: system.length
            return b - a
        }
        val rules = section("## The rules of the game", "\n## Memory", "\n## Skills")
        val memory = section("## Memory", "\n## Skills", "\n## This conversation")
        val skills = section("## Skills", "\n## This conversation")
        val voice = (system.length - rules - memory - skills).coerceAtLeast(0)
        var words = 0
        var results = 0
        var pictures = 0
        var context = 0
        // The memory put in front of messages (1.1.9: the deck's guide, the scope's notes, within their budgets) is
        // marked as such in the app's blocks, and counted with the memory in the instructions, not as the page.
        var shown = 0
        session.sent.forEach { t ->
            t.parts.forEach { p ->
                when (p) {
                    is Part.Text, is Part.ToolUse, is Part.Opaque -> words += Compaction.sizeOf(p)
                    is Part.ToolResult -> results += Compaction.sizeOf(p)
                    is Part.Image -> pictures += Compaction.sizeOf(p)
                    is Part.Context -> {
                        val mem = MemoryBudget.taggedChars(p.text)
                        shown += mem
                        context += Compaction.sizeOf(p) - mem
                    }
                    is Part.Activity, is Part.Reasoning -> Unit
                }
            }
        }
        val raw = listOf(
            "Voice and instructions" to voice,
            "Rules of the game" to rules,
            "Memory" to memory + shown,
            "Skills" to skills,
            "Tools" to Compaction.toolChars(tools),
            "What the app showed" to context,
            "The conversation" to words,
            "Tools' results" to results,
            "Pictures" to pictures,
        ).map { (label, chars) -> Slice(label, chars.toLong() / Compaction.CHARS_PER_TOKEN) }
        val estimated = raw.sumOf { it.tokens }
        // The provider's count is the truth; the estimate only says how it divides.
        if (measured <= 0 || estimated <= 0) return raw.filter { it.tokens > 0 }
        val scale = measured.toDouble() / estimated
        return raw.filter { it.tokens > 0 }.map { it.copy(tokens = (it.tokens * scale).toLong()) }
    }

    /** The whole, measured when it was, estimated when not. */
    fun total(session: AiSession, tools: List<ToolSpec>): Long =
        session.context.takeIf { it > 0 } ?: Compaction.estimate(session.system, session.sent, tools).toLong()
}

/**
 * The conversation's own past, searched (1.0.56, the `recall` tool): once the start of a long
 * conversation is summarised, the words are still saved whole, and a detail the summary dropped
 * can be found again. Ranked by how many of the words a turn holds, then by how recent it is.
 */
object Recall {
    data class Hit(val session: String, val title: String, val at: Long, val who: Role, val excerpt: String, val score: Int)

    fun words(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct()

    fun search(sessions: List<AiSession>, query: String, limit: Int = 12): List<Hit> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        return sessions.flatMap { s ->
            s.turns.flatMap { t ->
                val texts = t.parts.mapNotNull {
                    when (it) {
                        is Part.Text -> it.text
                        is Part.ToolResult -> it.content
                        else -> null
                    }
                }
                texts.mapNotNull { text ->
                    val lower = text.lowercase()
                    // Whole words, as the query was split: "it" is not found in "with", nor "ash" in "flash".
                    val at = words.map { wordAt(lower, it) }
                    val found = at.count { it >= 0 }
                    if (found == 0 || found < (words.size + 1) / 2) return@mapNotNull null
                    val first = at.filter { it >= 0 }.minOrNull() ?: 0
                    val excerpt = text.substring((first - 100).coerceAtLeast(0), (first + 260).coerceAtMost(text.length)).replace('\n', ' ').trim()
                    Hit(s.id, s.title, t.at, if (t.isToolResults) Role.ASSISTANT else t.role, excerpt, found)
                }
            }
        }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.at }).take(limit)
    }

    /** Where [word] first stands in [lower] as a word of its own (no letter or digit either side); -1 when it does not. */
    fun wordAt(lower: String, word: String): Int {
        var from = 0
        while (true) {
            val at = lower.indexOf(word, from)
            if (at < 0) return -1
            val end = at + word.length
            val alone = (at == 0 || !lower[at - 1].isLetterOrDigit()) && (end == lower.length || !lower[end].isLetterOrDigit())
            if (alone) return at
            from = at + 1
        }
    }
}
