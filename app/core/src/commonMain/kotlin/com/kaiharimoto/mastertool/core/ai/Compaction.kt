package com.kaiharimoto.mastertool.core.ai

/**
 * Keeping a long conversation inside what a model can read (1.0.47), the way the agent
 * harnesses do it (DeepSeek Harness, Claude Code): first the cheap step — old tool results,
 * the bulk of any agent's history, cut to their head and tail — and only then the costly
 * one, a summary of the oldest turns written by the model itself.
 *
 * Two rules keep it safe. A cut never falls between a tool call and its result (a model
 * refuses a result with no call), and the kept tail always starts at something the person
 * said, so the summary can ride in front of it. And the tail is kept byte for byte —
 * Anthropic's thinking blocks included — so what the newest models check is untouched.
 */
object Compaction {
    /** Characters per token, near enough for a budget: English and JSON both run about four. */
    const val CHARS_PER_TOKEN = 4

    /** Past this share of the window the history is shortened before the next call. */
    const val SUMMARIZE_AT = 0.6

    /** Old tool results are cut to this many characters, head and tail. */
    const val PRUNED = 600

    /** Any one tool result, before it joins the history: head and tail kept. */
    const val RESULT_CAP = 16_000

    fun estimate(system: String, turns: List<ChatTurn>): Int =
        (system.length + turns.sumOf { t -> t.parts.sumOf { size(it) } }) / CHARS_PER_TOKEN

    private fun size(p: Part): Int = when (p) {
        is Part.Text -> p.text.length
        is Part.Context -> p.text.length
        is Part.ToolUse -> p.name.length + p.input.toString().length
        is Part.ToolResult -> p.content.length
        is Part.Opaque -> p.json.length
        is Part.Activity, is Part.Reasoning -> 0
    }

    /** A long text as its head and tail, with how much went between. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val tail = max / 4
        val head = max - tail
        return text.take(head) + "\n[… ${text.length - head - tail} characters cut to fit …]\n" + text.takeLast(tail)
    }

    /**
     * Tool results older than the last [keep] turns cut short; everything else as it was.
     * Only user turns change, so an assistant's own blocks are never touched.
     */
    fun prune(turns: List<ChatTurn>, keep: Int = 6, max: Int = PRUNED): List<ChatTurn> {
        val from = (turns.size - keep).coerceAtLeast(0)
        return turns.mapIndexed { i, t ->
            if (i >= from || t.role != Role.USER || t.toolResults.none { it.content.length > max }) {
                t
            } else {
                t.copy(parts = t.parts.map { p -> if (p is Part.ToolResult && p.content.length > max) p.copy(content = cut(p.content, max)) else p })
            }
        }
    }

    /**
     * Where to cut [turns] for a summary so what is kept fits [keepTokens]: the index of
     * the first kept turn — always something the person said, never a tool result — or
     * null when there is nothing worth summarising (the whole history is the last message).
     */
    fun cutAt(turns: List<ChatTurn>, keepTokens: Int, from: Int = 0): Int? {
        val starts = turns.indices.filter { i -> i > from && turns[i].role == Role.USER && !turns[i].isToolResults }
        if (starts.isEmpty()) return null
        // The earliest start whose tail fits; failing that, the last message alone.
        return starts.firstOrNull { i -> estimate("", turns.drop(i)) <= keepTokens } ?: starts.last()
    }

    /** The turns a summary is written from, as plain lines: who said what, what was done. */
    fun transcript(turns: List<ChatTurn>, maxChars: Int = 60_000): String {
        val lines = turns.mapNotNull { t ->
            when {
                t.isToolResults -> t.toolResults.joinToString("\n") { r -> "  (${r.name}: ${cut(r.summary.ifBlank { r.content }, 300)})" }
                t.role == Role.USER -> "Person: " + t.text
                else -> buildString {
                    append("Assistant: ").append(t.text)
                    t.toolUses.forEach { u -> append("\n  → ").append(u.name).append(' ').append(cut(u.input.toString(), 200)) }
                }
            }.takeIf { it.isNotBlank() }
        }
        return cut(lines.joinToString("\n"), maxChars)
    }

    /** What the model is asked when the oldest turns are summarised. */
    const val SUMMARY_ASK =
        "The conversation below is being shortened to fit. Write a summary that lets you carry on as if you remembered it: " +
            "what the person wants and has decided, the decks, webs and cards involved (with their ids), what you did in the app " +
            "and what is still open. Plain short lines, no greeting. Under 400 words."

    /**
     * Whether a provider's error says the history was too long, so shortening it is the fix.
     */
    fun overflowed(message: String): Boolean {
        val m = message.lowercase()
        return ("context" in m && ("length" in m || "window" in m || "too long" in m || "exceed" in m || "maximum" in m)) ||
            "prompt is too long" in m || "too many tokens" in m || "maximum context" in m || "input is too long" in m
    }
}
