package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.world.Instruments
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.world.WorldPane
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Ai's hands in Ai World (1.0.97): every tool goes through [com.kaiharimoto.neue.world.Worlds], the same holder the
 * person's clicks change, so what Ai writes is typed into the editor they watch, what it runs prints into their
 * terminal, and what it shows is pinned to their canvas.
 */
internal class AiWorld(private val h: NeueHolders) {
    private val world get() = h.world

    /** The apps, `world_open` and `world_read`'s pages: what runs inside the desktop's windows (agent C). */
    private val inside = AiWorldApps(h)

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    suspend fun run(name: String, i: JsonObject): MetaAnswer? = when (name) {
        "world_state" -> state(ToolArgs.string(i, "world_id"))
        "world_new" -> new(ToolArgs.string(i, "title"), ToolArgs.string(i, "scope"), ToolArgs.string(i, "world_id"))
        "world_write" -> write(i)
        "world_read" -> inside.read(ToolArgs.string(i, "path").orEmpty(), (ToolArgs.int(i, "from") ?: 0).coerceAtLeast(0))
        "world_app" -> inside.app(i)
        "world_open" -> inside.open(ToolArgs.string(i, "address").orEmpty())
        "world_run" -> runIt(ToolArgs.string(i, "path"), ToolArgs.string(i, "code"), ToolArgs.string(i, "lang"), ToolArgs.int(i, "seconds") ?: 30)
        "world_tool" -> tool(ToolArgs.string(i, "name").orEmpty(), i["args"])
        "world_show" -> show(i)
        else -> null
    }

    private fun state(id: String?): MetaAnswer {
        val w = id?.let { wanted -> world.list.firstOrNull { it.id == wanted } ?: return fail("No world $id.") } ?: world.open
        return ok(world.describe(w), w?.let { "Looked at “${it.title}”" } ?: "Listed the worlds")
    }

    private suspend fun new(title: String?, scope: String?, id: String?): MetaAnswer {
        if (id != null) {
            val w = world.openWorld(id) ?: return fail("No world $id. world_state lists them.")
            world.arrive(WorldPane.FILES)
            return ok("Opened “${w.title}”.\n" + world.describe(w), "Opened the world “${w.title}”")
        }
        val scoped = when {
            scope.isNullOrBlank() -> null
            scope.trim().equals("open", ignoreCase = true) -> h.builder.deckId?.let { World.SCOPE_DECK + it }
            scope.startsWith(World.SCOPE_DECK) || scope.startsWith(World.SCOPE_WEB) -> scope.trim()
            else -> return fail("scope is deck:<id>, web:<id> or open.")
        }
        val w = world.create(title ?: "World", scoped)
        world.arrive(WorldPane.FILES)
        return ok("Made and opened “${w.title}” (${w.id}). Write a script with world_write, run it with world_run, or run an instrument with world_tool.", "Made the world “${w.title}”")
    }

    private suspend fun write(i: JsonObject): MetaAnswer {
        val path = ToolArgs.string(i, "path") ?: return fail("world_write needs a path.")
        if (ToolArgs.bool(i, "delete") == true) return world.delete(path).fold({ ok(it, it) }, { fail(it.message.orEmpty()) })
        val text = ToolArgs.string(i, "text") ?: run {
            val edits = ToolArgs.objects(i, "edits")
            if (edits.isEmpty()) return fail("Give text (the whole file) or edits.")
            var t = world.read(path) ?: return fail("There is no $path to edit; write it whole with text.")
            edits.forEachIndexed { n, e ->
                val find = ToolArgs.string(e, "find").orEmpty()
                val replace = ToolArgs.string(e, "replace").orEmpty()
                val at = t.indexOf(find)
                if (find.isEmpty() || at < 0) return fail("Edit ${n + 1}: “${find.take(80)}” is not in $path.")
                if (t.indexOf(find, at + 1) >= 0) return fail("Edit ${n + 1}: “${find.take(80)}” appears more than once in $path; give more of it.")
                t = t.substring(0, at) + replace + t.substring(at + find.length)
            }
            t
        }
        return world.write(path, text).fold({ ok(it, it) }, { fail(it.message.orEmpty()) })
    }

    private suspend fun runIt(path: String?, code: String?, lang: String?, seconds: Int): MetaAnswer =
        world.run(path, code, lang, seconds).fold(
            { o -> MetaAnswer(o.words(), (if (o.record.ok) "Ran " else "Failed ") + (path ?: "a snippet"), isError = !o.record.ok) },
            { fail(it.message.orEmpty()) },
        )

    private suspend fun tool(name: String, raw: JsonElement?): MetaAnswer {
        if (name == "list") return ok(Instruments.list(), "Listed the instruments")
        val args = when (raw) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            is JsonPrimitive -> raw.contentOrNull?.let { runCatching { WorldCodec.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?: return fail("args is an object, like {\"conditions\": [\"Starters>=1\"]}.")
            else -> return fail("args is an object.")
        }
        return world.tool(name, args).fold(
            { o -> ok(o.words() + (o.value?.let { "\nAnswer: $it" }.orEmpty()), "Ran the $name instrument") },
            { fail(it.message.orEmpty()) },
        )
    }

    private suspend fun show(i: JsonObject): MetaAnswer = when (ToolArgs.string(i, "action")) {
        "remove" -> {
            val id = ToolArgs.string(i, "id") ?: return fail("remove needs the board's id.")
            world.removeBoard(id).fold({ ok(it, it) }, { fail(it.message.orEmpty()) })
        }
        else -> {
            val kind = ToolArgs.string(i, "kind") ?: return fail("put needs a kind.")
            val body = when (val b = i["body"]) {
                null, JsonNull -> return fail("put needs a body.")
                is JsonPrimitive -> b.contentOrNull.orEmpty()
                else -> b.toString()
            }
            val tab = ToolArgs.bool(i, "open") != false
            world.putBoard(ToolArgs.string(i, "id"), kind, ToolArgs.string(i, "title"), body, ToolArgs.string(i, "note"), tab = tab).fold(
                { b ->
                    val at = com.kaiharimoto.mastertool.core.world.desk.WorldAddress.Board(b.id).format()
                    ok("Pinned ${b.id}: “${b.title}” at $at" + (if (tab) ", open in a Browser tab." else "; not opened (world_open shows it)."), "Pinned “${b.title}”")
                },
                { fail(it.message.orEmpty()) },
            )
        }
    }
}
