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

    /** Roughly how many tokens a request is: its instructions, its [tools]' specs (1.0.56) and its turns. */
    fun estimate(system: String, turns: List<ChatTurn>, tools: List<ToolSpec> = emptyList()): Int =
        (system.length + toolChars(tools) + turns.sumOf(::turnSize)) / CHARS_PER_TOKEN

    /**
     * A turn's characters on the wire. An Anthropic turn keeps its blocks twice — as text and tool uses for the app, and
     * whole in a [Part.Opaque] that is what is sent — so where there is an opaque copy only it counts (1.0.98, the red team).
     */
    internal fun turnSize(t: ChatTurn): Int {
        val opaque = t.parts.filterIsInstance<Part.Opaque>()
        if (opaque.isEmpty()) return t.parts.sumOf { size(it) }
        return opaque.sumOf { it.json.length } + t.parts.filter { it is Part.Context || it is Part.ToolResult || it is Part.Image }.sumOf { size(it) }
    }

    /** The characters tool specs take on the wire: names, descriptions and schemas. */
    fun toolChars(tools: List<ToolSpec>): Int = tools.sumOf { it.name.length + it.description.length + it.schema.toString().length + 24 }

    internal fun sizeOf(p: Part): Int = size(p)

    private fun size(p: Part): Int = when (p) {
        is Part.Text -> p.text.length
        is Part.Context -> p.text.length
        is Part.ToolUse -> p.name.length + p.input.toString().length
        is Part.ToolResult -> p.content.length
        is Part.Opaque -> p.json.length
        is Part.Image -> Part.Image.WEIGHT
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
    fun transcript(turns: List<ChatTurn>, maxChars: Int = TRANSCRIPT_MAX): String = cut(lines(turns).joinToString("\n"), maxChars)

    /**
     * The turns in pieces whose transcripts each fit [maxChars] (1.0.98, the red team): a long conversation is summarised
     * a piece at a time, the summary carried from one to the next, instead of its middle silently cut away. A turn
     * longer than a piece is a piece of its own, cut.
     */
    fun chunks(turns: List<ChatTurn>, maxChars: Int = TRANSCRIPT_MAX): List<List<ChatTurn>> {
        val out = mutableListOf<List<ChatTurn>>()
        var piece = mutableListOf<ChatTurn>()
        var size = 0
        turns.forEach { t ->
            val n = lines(listOf(t)).sumOf { it.length + 1 }
            if (piece.isNotEmpty() && size + n > maxChars) {
                out += piece
                piece = mutableListOf()
                size = 0
            }
            piece += t
            size += n
        }
        if (piece.isNotEmpty()) out += piece
        return out
    }

    const val TRANSCRIPT_MAX = 60_000

    /** A tool's result in a transcript: enough of it to keep its ids and numbers (it was 300 characters before 1.0.98). */
    const val TRANSCRIPT_RESULT = 1_200

    private fun lines(turns: List<ChatTurn>): List<String> =
        turns.mapNotNull { t ->
            when {
                t.isToolResults -> t.toolResults.joinToString("\n") { r -> "  (${r.name}: ${cut(r.content.ifBlank { r.summary }, TRANSCRIPT_RESULT)})" }
                t.role == Role.USER -> "Person: " + t.text
                else -> buildString {
                    append("Assistant: ").append(t.text)
                    t.toolUses.forEach { u -> append("\n  → ").append(u.name).append(' ').append(cut(u.input.toString(), 200)) }
                }
            }.takeIf { it.isNotBlank() }
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
