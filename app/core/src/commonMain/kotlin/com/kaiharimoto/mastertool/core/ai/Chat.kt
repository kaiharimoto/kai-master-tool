package com.kaiharimoto.mastertool.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/**
 * The conversation with Ai, in the app's own words — not any provider's. Each
 * backend translates it on the way out and back; the chat panel and the saved
 * sessions read only this.
 *
 * **Append-only.** A turn once added is never edited: prompt caching and the
 * newest models' preserved thinking both check that the history they are sent
 * is the history they wrote. What changes (the page, the open deck, a memory
 * written) goes in as a later [Part.Context], never as an edit to an earlier turn.
 */
@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
sealed interface Part {
    /** Words, the person's or Ai's. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Part

    /**
     * What the app is showing, written by the app into a user turn (the page, the
     * open deck, the memory in scope). Sent to the model, drawn faintly or not at all.
     */
    @Serializable
    @SerialName("context")
    data class Context(val text: String) : Part

    /** Ai asking the app to do something (a tool call). */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Part

    /** What the app answered, in a user turn. */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        /** What the chat shows for it, in words ("Added 3 Ash Blossom"); the model reads [content]. */
        val summary: String = "",
    ) : Part

    /**
     * A line of what happened, for the chat alone and never sent: a tool a CLI called
     * through the app's MCP server, whose traffic is the CLI's own history, not ours.
     */
    @Serializable
    @SerialName("activity")
    data class Activity(val name: String, val summary: String, val isError: Boolean = false) : Part

    /**
     * A provider's own blocks, kept byte for byte so they can be sent back exactly
     * as they came (an Anthropic assistant turn's thinking blocks, which the newest
     * models refuse to read if they were changed). Only the backend of [provider]
     * reads [json]; everyone else reads the turn's other parts.
     */
    @Serializable
    @SerialName("opaque")
    data class Opaque(val provider: String, val json: String) : Part

    /**
     * What the model thought before answering, as far as its provider shows it (1.0.47,
     * kai: "the AI should think out loud at least partially so the user can also learn
     * with it"). For the chat alone: never sent back as words — Anthropic's own thinking
     * blocks travel in [Opaque], untouched.
     */
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val text: String) : Part

    /**
     * A picture the person showed Ai (1.0.55, kai: "enable … image input"): a screenshot of a
     * decklist, a card, a board. The bytes live in a file of their own under Ai's folder
     * ([file], relative to it), never in the saved conversation, so the list of conversations
     * stays quick to read. [data] is the picture in base64, put in by the app just before a
     * turn is sent and never saved; a wire that finds it missing says a picture was lost.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val file: String,
        val mime: String,
        val width: Int = 0,
        val height: Int = 0,
        @Transient val data: String? = null,
    ) : Part {
        /** A picture's description in words, for the wires that cannot send one. */
        val missing: String get() = "[A picture was attached here, but it could not be read.]"

        companion object {
            /** Roughly what a picture costs a model's window, in characters (about 1,600 tokens). */
            const val WEIGHT = 6_400
        }
    }
}

@Serializable
data class ChatTurn(
    val role: Role,
    val parts: List<Part>,
    /** When it was added, epoch ms. */
    val at: Long = 0,
) {
    /** The words of the turn, context and tool traffic left out. */
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n\n") { it.text }

    val images: List<Part.Image> get() = parts.filterIsInstance<Part.Image>()

    val toolUses: List<Part.ToolUse> get() = parts.filterIsInstance<Part.ToolUse>()
    val toolResults: List<Part.ToolResult> get() = parts.filterIsInstance<Part.ToolResult>()

    /** A turn of tool results only: the app answering, not the person speaking. */
    val isToolResults: Boolean get() = role == Role.USER && parts.isNotEmpty() && parts.all { it is Part.ToolResult }

    companion object {
        fun user(text: String, context: String? = null, at: Long = 0, images: List<Part.Image> = emptyList()) = ChatTurn(
            Role.USER,
            buildList {
                if (!context.isNullOrBlank()) add(Part.Context(context))
                addAll(images)
                if (text.isNotBlank() || images.isEmpty()) add(Part.Text(text))
            },
            at,
        )

        fun assistant(text: String, at: Long = 0) = ChatTurn(Role.ASSISTANT, listOf(Part.Text(text)), at)
    }
}

/** Tokens spent, when the provider says. */
@Serializable
data class Usage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    /** What it cost, when the provider says (the CLIs do). */
    val costUsd: Double? = null,
) {
    /** What the model read, all told: the new tokens and the cached ones (1.0.56). */
    val read: Long get() = input + cacheRead + cacheWrite

    operator fun plus(other: Usage) = Usage(
        input + other.input,
        output + other.output,
        cacheRead + other.cacheRead,
        cacheWrite + other.cacheWrite,
        if (costUsd == null && other.costUsd == null) null else (costUsd ?: 0.0) + (other.costUsd ?: 0.0),
    )
}

/** Why a model stopped talking. */
enum class StopReason {
    END, TOOL_USE, MAX_TOKENS, REFUSAL, CANCELLED, ERROR,

    /** The provider paused a long turn of its own tools (a web search) and wants it sent back to carry on. */
    PAUSED,
}

/**
 * One conversation with Ai, as it is saved (`ai/sessions/<id>.json`): its turns, the
 * instructions it was started with — frozen, so a resumed conversation sends the very
 * same prompt and the cache still holds it — and a CLI's own session to resume.
 */
@Serializable
data class AiSession(
    val id: String,
    val title: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** The connection it was held on, by id; a new connection starts a new conversation. */
    val connection: String? = null,
    val system: String = "",
    val turns: List<ChatTurn> = emptyList(),
    /** A CLI's session id, to carry on where it left off. */
    val resume: String? = null,
    /** The memory scope whose notes the conversation has already been given (`MemoryScope.path`). */
    val scopeShown: String? = null,
    /** "chat"; "tune" when the person teaches Ai a deck; "study" when Ai studies it itself (Fine Tuning). */
    val mode: String = MODE_CHAT,
    val usage: Usage = Usage(),
    /** How many of [turns] the reflection after a conversation has already read (phase 3). */
    val reflected: Int = 0,
    /**
     * The conversation's first [summarized] turns, in a model's summary (1.0.47): what is
     * sent instead of them once the conversation grew past what the model can read. The
     * turns stay here whole for the person to scroll back through.
     */
    val summary: String = "",
    val summarized: Int = 0,
    /** The deck whose guide the conversation has already been given (1.0.48). */
    val guideShown: String? = null,
    /**
     * How many tokens the model read in the last round (1.0.56): what the conversation weighs
     * now, as the provider counted it. 0 until it has said.
     */
    val context: Long = 0,
    /** Tool results in the turns before this one are sent cut short (1.0.56, "Clear old tool results"). */
    val clearedBefore: Int = 0,
    /** The conversation this one carries on from, when it was started fresh with a summary (1.0.56). */
    val carriedFrom: String? = null,
    /** Answers checked against the card text (1.0.58, the fact-check pass): a field, so older builds read past it. */
    val checks: List<com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check> = emptyList(),
) {
    /**
     * What the model is sent: the summary in front of the turns after it, or every turn; the
     * results of tools before [clearedBefore] cut short. A conversation started fresh from
     * another carries that one's summary in front of its first turn.
     */
    val sent: List<ChatTurn>
        get() {
            val cleared = if (clearedBefore > 0) Compaction.prune(turns, keep = (turns.size - clearedBefore).coerceAtLeast(0), max = CLEARED) else turns
            if (summary.isBlank()) return cleared
            if (summarized <= 0 && carriedFrom == null) return cleared
            val tail = cleared.drop(summarized.coerceAtLeast(0))
            val first = tail.firstOrNull() ?: return cleared
            val note = Part.Context(
                if (summarized > 0) "Summary of the conversation before this message (the earlier turns were shortened to fit):\n$summary"
                else "Summary of an earlier conversation this one carries on from:\n$summary",
            )
            return listOf(first.copy(parts = listOf(note) + first.parts)) + tail.drop(1)
        }

    /** The person's messages the reflection has not read yet. */
    val unreflected: Int get() = turns.drop(reflected).count { it.role == Role.USER && !it.isToolResults }

    /** A title from the first thing the person said. */
    fun titled(): AiSession = if (title.isNotBlank()) this else copy(
        title = turns.firstOrNull { it.role == Role.USER && !it.isToolResults }?.let { first ->
            first.text.lineSequence().firstOrNull()?.take(60)?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (first.images.isNotEmpty()) "A picture" else null
        }.orEmpty(),
    )

    companion object {
        const val MODE_CHAT = "chat"
        const val MODE_TUNE = "tune"
        const val MODE_STUDY = "study"

        /** Fine Tuning from the card text and the rules alone (1.0.54): the web's tools are closed. */
        const val MODE_PRINCIPLES = "principles"

        /** Learn About You (1.0.54): an interview that builds the person's profile in USER.md. */
        const val MODE_PROFILE = "profile"

        /** Refactor guide (1.0.66): the deck's guide rewritten whole, reviewed at the end. */
        const val MODE_REFACTOR = "refactor"

        /** Writing the reader's guide (1.0.67): a book for people, a chapter at a time, reviewed at the end. */
        const val MODE_WRITE = "write"

        /** Build with Ai on Present (1.0.71): a deck profile for a video, slide by slide, checked as it goes. */
        const val MODE_PRESENT = "present"

        /** Restyle on Present (1.0.72): the look of a presentation changed from the person's words, and nothing else. */
        const val MODE_RESTYLE = "restyle"

        /** At the duel table (1.0.80): Ai talks in the duel's log, reading the table only when cued. */
        const val MODE_DUEL = "duel"

        /** What an old tool result is cut to once the person clears them. */
        const val CLEARED = 200

        /** The modes that are Fine Tuning of a deck, and end on a session report. */
        val DECK_MODES = setOf(MODE_TUNE, MODE_STUDY, MODE_PRINCIPLES)
    }
}
