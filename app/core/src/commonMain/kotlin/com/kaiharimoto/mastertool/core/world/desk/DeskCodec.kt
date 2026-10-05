package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A world's desk to and from `<data>/world/<id>/desk.json` (§8.2). Read the way [WorldCodec] reads a world: a newer
 * build's keys skipped, a window or tab that will not read dropped alone, a window of an app this build does not know
 * dropped (it has no way to draw it), and no desk at all — a 1.0.97 world — an empty desktop. A phone reads the open set
 * and the tabs and ignores the frames.
 */
object DeskCodec {
    const val FILE = "desk.json"

    private val pretty = Json(WorldCodec.json) { prettyPrint = true }

    fun encode(d: Desk): String = pretty.encodeToString(Desk.serializer(), d)

    /** The desk in [text]; an empty desktop for none, or one that will not read at all. Never null: a world always has a desk. */
    fun decode(text: String?): Desk {
        if (text.isNullOrBlank()) return Desk()
        val read = try {
            WorldCodec.json.decodeFromString(Desk.serializer(), text)
        } catch (e: Exception) {
            salvage(text)
        }
        return clean(read)
    }

    private fun salvage(text: String): Desk {
        val root = try {
            WorldCodec.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return Desk()
        }
        fun <T> each(key: String, f: (JsonElement) -> T): List<T> =
            (root[key] as? JsonArray).orEmpty().mapNotNull { runCatching { f(it) }.getOrNull() }
        val windows = each("windows") { WorldCodec.json.decodeFromJsonElement(DeskWindow.serializer(), it) }
        val icons = each("icons") { WorldCodec.json.decodeFromJsonElement(IconCell.serializer(), it) }
        val tabs = (root["tabs"] as? JsonObject)?.let { t ->
            runCatching { WorldCodec.json.decodeFromJsonElement(BrowserTabs.serializer(), t) }.getOrNull()
                ?: BrowserTabs(
                    tabs = (t["tabs"] as? JsonArray).orEmpty().mapNotNull { runCatching { WorldCodec.json.decodeFromJsonElement(Tab.serializer(), it) }.getOrNull() },
                    selected = (t["selected"] as? JsonPrimitive)?.contentOrNull,
                    next = (t["next"] as? JsonPrimitive)?.intOrNull ?: 1,
                )
        } ?: BrowserTabs()
        return Desk(
            windows = windows,
            front = (root["front"] as? JsonPrimitive)?.contentOrNull,
            tabs = tabs,
            icons = icons,
            turn = (root["turn"] as? JsonPrimitive)?.intOrNull ?: 0,
            cascade = (root["cascade"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /** One window per app this build knows; the front one open and not minimised; tab ids unique and `next` past them. */
    private fun clean(d: Desk): Desk {
        val windows = d.windows.filter { it.ref != null }.distinctBy { it.app }
        val front = d.front?.takeIf { f -> windows.any { it.app == f && !it.minimised } }
        val tabs = d.tabs.tabs.distinctBy { it.id }
        val highest = tabs.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0
        val sel = d.tabs.selected?.takeIf { s -> tabs.any { it.id == s } } ?: tabs.firstOrNull()?.id
        return d.copy(
            windows = windows,
            front = front,
            tabs = d.tabs.copy(tabs = tabs, selected = sel, next = maxOf(d.tabs.next, highest + 1)),
            icons = d.icons.distinctBy { it.app },
        )
    }
}
