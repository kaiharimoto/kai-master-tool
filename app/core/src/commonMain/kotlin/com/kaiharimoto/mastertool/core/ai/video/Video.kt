package com.kaiharimoto.mastertool.core.ai.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A YouTube video, told apart from any other link (1.0.62, kai: "let me link a youtube video of the
 * deck profile and let the AI parse it with vision and transcription to learn about the deck").
 */
object YouTube {
    private val shapes = listOf(
        Regex("""(?:youtube\.com|youtube-nocookie\.com)/(?:watch\?(?:[^#]*&)?v=|embed/|shorts/|live/|v/)([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
    )

    /** The video's id in any of YouTube's addresses, or null when [link] is not one. */
    fun id(link: String): String? = shapes.firstNotNullOfOrNull { it.find(link.trim())?.groupValues?.get(1) }

    /** Where a video is watched: the address Gemini is given. */
    fun watch(id: String): String = "https://www.youtube.com/watch?v=$id"

    /** The video's largest thumbnail, open to anyone. */
    fun thumbnail(id: String): String = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"

    /** YouTube's public description of a video (oEmbed): its title and channel, and no sign-in. */
    fun oembed(id: String): String = "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$id"

    /** The title and channel from oEmbed's answer, or null. */
    fun titleOf(json: String): Pair<String, String?>? = runCatching {
        val o = Json.parseToJsonElement(json) as JsonObject
        val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return null
        title to (o["author_name"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}

/**
 * A video watched by Gemini, which takes a YouTube address as it is and watches it on Google's
 * side — the frames and the sound — so nothing is scraped here (1.0.62). YouTube asks a scraper to
 * sign in and wants a token per session for its captions; Gemini is the one reader it lets in.
 */
object GeminiVideo {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val FALLBACK_MODEL = "gemini-2.5-flash"

    /** What Gemini is asked of a deck profile, with [focus] from the person when they gave one. */
    fun brief(title: String?, focus: String?): String = buildString {
        appendLine("You are watching a Yu-Gi-Oh! video${title?.let { " titled \"$it\"" }.orEmpty()} — most likely a deck profile — for someone who will play the deck.")
        appendLine("Report, in plain text with these headings:")
        appendLine("DECKLIST: every card shown on screen, section by section (Main, Extra, Side), one line each as \"count x exact card name\". Read the names off the cards and any list shown; mark a name you are unsure of with (?). Say at what time the list is shown.")
        appendLine("ABOUT: the player, the event, the placement and the format or banlist, if said or shown.")
        appendLine("PLAN: what the deck is trying to do, in the player's own reasoning.")
        appendLine("LINES: each combo or line they walk through, step by step with card names, and its timestamp (mm:ss).")
        appendLine("CHOICES: each tech, ratio or cut they explain, and why, with timestamps.")
        appendLine("SIDING: what they side in and out against each matchup, going first and second.")
        appendLine("TIPS: anything else a player of the deck should know (choke points, mistakes to avoid).")
        append("Quote the player where it matters. Do not invent what the video does not show or say; write \"not covered\" under a heading instead.")
        focus?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\nThey especially want to know: ").append(it) }
    }

    /** The request: the video, then the brief. */
    fun request(videoUrl: String, brief: String): String = buildJsonObject {
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { putJsonObject("file_data") { put("file_uri", videoUrl) } })
                        add(buildJsonObject { put("text", brief) })
                    }
                },
            )
        }
    }.toString()

    /** What Gemini said, or why it did not, from its answer and the status it came with. */
    fun read(status: Int, body: String): Result<String> = runCatching {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        if (status !in 200..299 || error != null) {
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val code = (error?.get("code") as? JsonPrimitive)?.intOrNull ?: status
            error(
                when {
                    "API key" in message || code == 401 -> "The Gemini key was refused: $message"
                    code == 403 && ("private" in message.lowercase() || "permission" in message.lowercase()) -> "Gemini could not open the video: it may be private or age-restricted. ($message)"
                    code == 429 -> "Gemini's limit for this key is reached for now (free keys allow a few hours of video a day). Try again later."
                    code == 404 -> "Gemini does not know that model. ($message)"
                    else -> "Gemini answered $code: ${message.ifEmpty { body.take(200) }}"
                },
            )
        }
        val candidates = root?.get("candidates") as? JsonArray ?: error("Gemini sent no answer.")
        val first = candidates.firstOrNull() as? JsonObject ?: error("Gemini sent no answer.")
        val parts = ((first["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("").trim()
        if (text.isEmpty()) {
            val why = (first["finishReason"] as? JsonPrimitive)?.contentOrNull
            error("Gemini watched it and said nothing${why?.let { " ($it)" }.orEmpty()}.")
        }
        text
    }

    /**
     * Which of the key's models to watch with: the newest Gemini Flash that writes text (a video is
     * long, and Flash watches it well for a fraction of Pro's price), else the newest Gemini at all.
     */
    fun pick(listJson: String): String {
        val names = runCatching {
            ((Json.parseToJsonElement(listJson) as JsonObject)["models"] as JsonArray).mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                (o["name"] as? JsonPrimitive)?.contentOrNull?.removePrefix("models/")?.takeIf { "generateContent" in methods || methods.isEmpty() }
            }
        }.getOrDefault(emptyList())
        val usable = names.filter { n ->
            n.startsWith("gemini-") && listOf("tts", "image", "live", "embedding", "audio", "lite", "robotics", "computer").none { it in n }
        }
        fun version(n: String) = Regex("""gemini-(\d+(?:\.\d+)?)""").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val ordered = usable.sortedWith(compareByDescending<String> { version(it) }.thenBy { if ("preview" in it || "exp" in it) 1 else 0 }.thenBy { it.length })
        return ordered.firstOrNull { "flash" in it } ?: ordered.firstOrNull() ?: FALLBACK_MODEL
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: buildJsonArray { }
}
