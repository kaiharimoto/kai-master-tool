package com.kaiharimoto.mastertool.core.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A world to and from `world.json`, and its log to and from `log.jsonl`. Read the way `PresentCodec` reads: a newer
 * build's keys skipped, a board that will not read dropped alone, a log line that will not read skipped — a world is
 * Ai's hours of work and the person's to keep.
 */
object WorldCodec {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val pretty = Json(json) { prettyPrint = true }

    fun encode(w: World): String = pretty.encodeToString(World.serializer(), w)

    /** The world in [text], or null when it is not one at all. */
    fun decode(text: String?): World? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(World.serializer(), text)
        } catch (e: Exception) {
            salvage(text)
        }
    }

    private fun salvage(text: String): World? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val id = (root["id"] as? JsonPrimitive)?.content ?: return null
        val boards = (root["boards"] as? JsonArray).orEmpty().mapNotNull { b ->
            try {
                json.decodeFromJsonElement(Board.serializer(), b)
            } catch (e: Exception) {
                null
            }
        }
        val base = try {
            json.decodeFromJsonElement(World.serializer(), JsonObject(root.filterKeys { it != "boards" }))
        } catch (e: Exception) {
            World(id = id, title = (root["title"] as? JsonPrimitive)?.content ?: "World")
        }
        return base.copy(boards = boards)
    }

    /** One event as a line of `log.jsonl`. */
    fun line(e: WorldEvent): String = json.encodeToString(WorldEvent.serializer(), e)

    /** Every event in [text] that reads, in order. */
    fun events(text: String?): List<WorldEvent> =
        text.orEmpty().lineSequence().filter { it.isNotBlank() }.mapNotNull { l ->
            try {
                json.decodeFromString(WorldEvent.serializer(), l)
            } catch (e: Exception) {
                null
            }
        }.toList()
}

/**
 * Paths inside a world's `files/`, made safe: relative, forward slashes, no `..`, no drive or root, plain
 * characters. What Ai or a script names is always read through here before it touches a disk.
 */
object WorldPaths {
    const val MAX_DEPTH = 6
    const val MAX_LENGTH = 160
    private val PART = Regex("[A-Za-z0-9._-]{1,64}")

    /** [raw] as a safe relative path, or null with no way to make it one. */
    fun safe(raw: String): String? {
        val p = raw.trim().replace('\\', '/').removePrefix("./")
        if (p.isEmpty() || p.length > MAX_LENGTH || ':' in p || p.startsWith('/')) return null
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_DEPTH) return null
        if (parts.any { it == "." || it == ".." || !PART.matches(it) || it.startsWith('.') }) return null
        return parts.joinToString("/")
    }

    /** The language a file runs in, from its name: js or py, or null for a file that does not run. */
    fun lang(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> LANG_JS
        "py" -> LANG_PY
        else -> null
    }

    const val LANG_JS = "js"
    const val LANG_PY = "py"
}
