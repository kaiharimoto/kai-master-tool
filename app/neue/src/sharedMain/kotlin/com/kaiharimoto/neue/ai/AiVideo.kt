package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.video.GeminiVideo
import com.kaiharimoto.mastertool.core.ai.video.YouTube
import com.kaiharimoto.mastertool.core.ai.wire.Unreachable
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Ai watches a video (1.0.62, kai: "let me link a youtube video of the deck profile and let the AI
 * parse it with vision and transcription to learn about the deck"). YouTube asks a program that
 * reads it to sign in, and wants a token per session for its captions; Gemini takes the video's
 * address as it is and watches it on Google's side, frames and sound. So a video is watched with a
 * Gemini key — the person's own for videos (quick settings → Videos), else a Gemini connection's —
 * whatever model the conversation itself runs on.
 */
internal class AiVideo(private val ai: AiState) {
    private val http by lazy { HttpClientFactory.create() }
    private var model: String? = null

    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    /** The key a video is watched with, or null when there is none. */
    fun key(): String? =
        SecretStore.get(KEY)?.takeIf { it.isNotBlank() }
            ?: ai.prefs.connections.filter { it.provider == "gemini" }.firstNotNullOfOrNull { c -> ai.secret(c)?.takeIf { it.isNotBlank() } }

    suspend fun watch(url: String, focus: String?): MetaAnswer {
        val id = YouTube.id(url) ?: return fail("That is not a YouTube address: “$url”. Only YouTube videos can be watched.")
        val key = key() ?: run {
            // The box for the key stands in the chat under the answer, so it is pasted where it was asked for.
            ai.videoKeyAsked = true
            return fail(NEEDS_KEY)
        }
        val about = runCatching {
            val r = http.get(YouTube.oembed(id))
            if (r.status.value in 200..299) YouTube.titleOf(r.bodyAsText()) else null
        }.getOrNull()
        val title = about?.first
        val chosen = model ?: runCatching {
            val r = http.get("${GeminiVideo.BASE}/models?pageSize=200") { header("x-goog-api-key", key) }
            if (r.status.value in 200..299) GeminiVideo.pick(r.bodyAsText()) else GeminiVideo.FALLBACK_MODEL
        }.getOrDefault(GeminiVideo.FALLBACK_MODEL).also { model = it }
        val said = runCatching {
            val r = http.post("${GeminiVideo.BASE}/models/$chosen:generateContent") {
                header("x-goog-api-key", key)
                contentType(ContentType.Application.Json)
                setBody(GeminiVideo.request(YouTube.watch(id), GeminiVideo.brief(title, focus)))
                // A long video takes Gemini a while to watch.
                timeout {
                    requestTimeoutMillis = WATCH_MS
                    socketTimeoutMillis = WATCH_MS
                }
            }
            GeminiVideo.read(r.status.value, r.bodyAsText())
        }.getOrElse { Result.failure(it) }
        val text = said.getOrElse {
            // Gemini's own refusals are already in words; a network failure is said plainly.
            return fail(if (it is IllegalStateException) it.message.orEmpty() else Unreachable.say(GeminiVideo.BASE, it.message))
        }
        val head = buildString {
            append("Video: ").append(title?.let { "“$it”" } ?: YouTube.watch(id))
            about?.second?.let { append(" by ").append(it) }
            append(" — ").append(YouTube.watch(id)).append('\n')
            append("Watched by Gemini ($chosen): what it saw on screen and heard. The player's view, not the rules.\n\n")
        }
        return MetaAnswer(head + text.take(MAX_REPORT), "Watched ${title?.let { "“$it”" } ?: "the video"}")
    }

    companion object {
        /** Where the person's own key for videos is kept, never in the preferences. */
        const val KEY = "video:gemini"
        const val KEY_PAGE = "https://aistudio.google.com/apikey"
        private const val WATCH_MS = 10 * 60 * 1000L
        private const val MAX_REPORT = 16_000

        /** Whether [key] opens Gemini: the model videos will be watched with, or why not. */
        suspend fun check(key: String): Result<String> = runCatching {
            val r = HttpClientFactory.create().get("${GeminiVideo.BASE}/models?pageSize=200") { header("x-goog-api-key", key) }
            val body = r.bodyAsText()
            if (r.status.value !in 200..299) GeminiVideo.read(r.status.value, body).getOrThrow()
            GeminiVideo.pick(body)
        }.recoverCatching { if (it is IllegalStateException) throw it else error(Unreachable.say(GeminiVideo.BASE, it.message)) }

        const val NEEDS_KEY = "Watching a video needs a Gemini API key: Gemini is the model that can watch a YouTube video, " +
            "frames and sound. A box for the key is now shown in the chat under your answer, with a link to make a free one at " +
            "aistudio.google.com/apikey (a Google account, no card, a minute); once it is saved, watch the video again. It is also " +
            "in quick settings under Videos. The conversation stays on its own model. Tell the person this in a line or two."
    }
}
