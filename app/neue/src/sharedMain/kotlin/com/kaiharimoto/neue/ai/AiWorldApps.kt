package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldPage
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.apps.AppCodec
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.apps.UiEvent
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskSize
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.world.apps.WorldApps
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Ai's hands on what runs inside the World's windows (`docs/world/DESKTOP.md` §8.7, agent C): `world_app` (its own apps,
 * each change through `AppStore` and logged), `world_open` (a page, an app, a file or a built-in, through the desktop's
 * focus rules), and `world_read` a page at a time. Owned beside [AiWorld], which dispatches here.
 */
internal class AiWorldApps(private val h: NeueHolders) {
    private val world get() = h.world
    private val apps get() = world.apps

    private fun ok(content: String, summary: String) = MetaAnswer(content, summary)
    private fun fail(message: String) = MetaAnswer(message, message, isError = true)

    /** `world_read`: a page of 16,000 characters from [from], numbered from where the page starts, and how to read on. */
    fun read(path: String, from: Int): MetaAnswer {
        world.open ?: return fail("No world is open: world_new makes one.")
        val text = world.read(path) ?: return fail("There is no $path. world_state lists the files and apps.")
        val page = WorldPage.of(text, from)
        val first = text.substring(0, page.from).count { it == '\n' } + 1
        val numbered = page.text.removeSuffix("\n").lines().mapIndexed { n, l -> "${(first + n).toString().padStart(5)}  $l" }.joinToString("\n")
        if (from == 0) {
            world.showFile(WorldPaths.safe(path) ?: path)
            world.desk.arriveNow(BuiltInApp.EDITOR.ref, AiDoes.Read(BuiltInApp.EDITOR.ref, path.substringAfterLast('/')))
        }
        val footer = page.footer(path)
        return ok(numbered + if (footer.isNotEmpty()) "\n$footer" else "", "Read $path" + if (page.from > 0) " from ${page.from}" else "")
    }

    /** `world_open`: what the person asked to see, brought up through the desktop's focus rules — never past them. */
    suspend fun open(raw: String): MetaAnswer {
        val word = raw.trim()
        if (word.isEmpty()) return fail("world_open needs an address, a file's path, or an app's name.")
        BuiltInApp.entries.firstOrNull { it.id.equals(word, ignoreCase = true) || it.title.equals(word, ignoreCase = true) }?.let { app ->
            world.arrive(app.ref, AiDoes.Read(app.ref, app.title))
            return ok("Opened ${app.title}.", "Opened ${app.title}")
        }
        if (word.startsWith(WorldAddress.SCHEME, ignoreCase = true)) {
            return when (val a = WorldAddress.parse(word)) {
                is WorldAddress.Unknown -> fail("“${a.raw}” is no page: ${a.why}. world://home lists every page.")
                is WorldAddress.App -> if (apps.manifest(a.slug) == null) fail("There is no app “${a.slug}”. world_state lists the apps.") else {
                    world.arrive(AppRef.Made(a.slug), AiDoes.OpenApp(a.slug, apps.manifest(a.slug)?.title ?: a.slug))
                    ok("Opened the app ${a.slug}.", "Opened ${apps.manifest(a.slug)?.title ?: a.slug}")
                }
                else -> {
                    world.open ?: return fail("No world is open.")
                    world.browser.go(a.format(), WorldEvent.AI)
                    world.arrive(BuiltInApp.BROWSER.ref, AiDoes.Show(world.browser.tabs.selected.orEmpty()))
                    ok("Opened ${a.format()} in the Browser.", "Opened ${a.format()}")
                }
            }
        }
        val path = WorldPaths.safe(word) ?: return fail("“$word” is not an address (world://…), a file of the world, or an app's name.")
        if (world.read(path) == null) return fail("There is no $path. world_state lists the files.")
        world.saveEditor()
        world.showFile(path)
        world.arrive(BuiltInApp.EDITOR.ref, AiDoes.Read(BuiltInApp.EDITOR.ref, path))
        return ok("Opened $path in the Editor.", "Opened $path")
    }

    /** `world_app`. */
    suspend fun app(i: JsonObject): MetaAnswer {
        world.open ?: return fail("No world is open: world_new makes one.")
        val action = ToolArgs.string(i, "action") ?: return fail("world_app needs an action.")
        val slug = ToolArgs.string(i, "slug")?.trim()?.lowercase() ?: return fail("world_app needs the app's slug.")
        if (!AppCodec.validSlug(slug)) return fail("An app's slug is a-z, 0-9 and -, at most 32: “$slug”.")
        return when (action) {
            "make" -> make(slug, i)
            "change" -> change(slug, i)
            "back" -> {
                val to = ToolArgs.int(i, "to") ?: return fail("back needs to: the version to go back to.")
                apps.back(slug, to, WorldEvent.AI).fold(
                    { m -> ok("Back to v$to's code, now v${m.version}; the state kept.\n" + apps.screenNow(slug), "Put ${m.title} back to v$to") },
                    { fail(it.message.orEmpty()) },
                )
            }
            "open" -> {
                val m = apps.manifest(slug) ?: return fail("There is no app “$slug”. world_state lists the apps.")
                world.arrive(AppRef.Made(slug), AiDoes.OpenApp(slug, m.title))
                ok("Opened ${m.title} (v${m.version}).\n" + apps.screenNow(slug), "Opened ${m.title}")
            }
            "close" -> {
                apps.windows.close(AppRef.Made(slug))
                apps.closed(slug)
                ok("Closed $slug.", "Closed $slug")
            }
            "press" -> {
                val id = ToolArgs.string(i, "id") ?: return fail("press needs the widget's id: open the app to see them.")
                val type = ToolArgs.string(i, "type") ?: UiEvent.PRESS
                val value = i["value"] ?: if (type == UiEvent.PRESS) JsonPrimitive(true) else JsonNull
                apps.manifest(slug)?.let { m -> world.arrive(AppRef.Made(slug), AiDoes.Press(slug, m.title, id)) }
                apps.press(slug, id, type, value).fold({ ok(it, "Pressed $id in $slug") }, { fail(it.message.orEmpty()) })
            }
            "state" -> apps.stateForAi(slug)?.let { ok(it, "Read $slug's state") } ?: fail("There is no app “$slug”.")
            "delete" -> {
                val m = apps.manifest(slug) ?: return fail("There is no app “$slug”.")
                if (!h.ai.prefs.alwaysAllow && !h.ai.ask(Confirm("Delete the app “${m.title}”?", "Its code, its earlier versions and everything kept in it go. This cannot be undone.", "world_app"))) {
                    return fail("The person said no: ${m.title} stays.")
                }
                apps.delete(slug, WorldEvent.AI).fold({ ok(it, "Deleted ${m.title}") }, { fail(it.message.orEmpty()) })
            }
            else -> fail("world_app's actions are make, change, back, open, close, press, state and delete.")
        }
    }

    private suspend fun make(slug: String, i: JsonObject): MetaAnswer {
        if (apps.manifest(slug) != null) return fail("There is an app “$slug” already: change it, or choose another slug.")
        val code = ToolArgs.string(i, "code") ?: return fail("make needs code: main.js with init, view and on.")
        val name = ToolArgs.string(i, "name") ?: slug
        val w = (ToolArgs.int(i, "w") ?: 480).coerceIn(320, 1200)
        val ht = (ToolArgs.int(i, "h") ?: 360).coerceIn(240, 900)
        val m = AppManifest(
            slug = slug,
            name = name,
            kind = ToolArgs.string(i, "kind") ?: "viewer",
            glyph = ToolArgs.string(i, "glyph"),
            monogram = ToolArgs.string(i, "monogram"),
            description = ToolArgs.string(i, "description").orEmpty(),
            size = DeskSize(w.toDouble(), ht.toDouble()),
        )
        typeIn(slug, code)
        val made = apps.make(m, code, WorldEvent.AI).getOrElse { return fail(it.message.orEmpty()) }
        val open = ToolArgs.bool(i, "open") != false
        if (open) world.arrive(AppRef.Made(slug), AiDoes.MakeApp(slug, made.title))
        val screen = if (open) apps.screenNow(slug) else "(not opened: world_app open shows it)"
        return ok(
            "Made ${made.title} (v1) at world://apps/$slug, its code at ${WorldApps.codePath(slug)}.\n$screen\n" +
                "Test it with world_app press; tell the person what it is for in a line.",
            "Made ${made.title}",
        )
    }

    private suspend fun change(slug: String, i: JsonObject): MetaAnswer {
        val old = apps.manifest(slug) ?: return fail("There is no app “$slug”. world_state lists the apps.")
        val code = ToolArgs.string(i, "code") ?: run {
            val edits = ToolArgs.objects(i, "edits")
            if (edits.isEmpty()) return fail("change needs code (main.js whole) or edits.")
            var t = apps.code(slug) ?: return fail("$slug has no code to edit.")
            edits.forEachIndexed { n, e ->
                val find = ToolArgs.string(e, "find").orEmpty()
                val replace = ToolArgs.string(e, "replace").orEmpty()
                val at = t.indexOf(find)
                if (find.isEmpty() || at < 0) return fail("Edit ${n + 1}: “${find.take(80)}” is not in $slug's code.")
                if (t.indexOf(find, at + 1) >= 0) return fail("Edit ${n + 1}: “${find.take(80)}” appears more than once; give more of it.")
                t = t.substring(0, at) + replace + t.substring(at + find.length)
            }
            t
        }
        val manifest = old.copy(
            name = ToolArgs.string(i, "name") ?: old.name,
            kind = ToolArgs.string(i, "kind") ?: old.kind,
            glyph = ToolArgs.string(i, "glyph") ?: old.glyph,
            monogram = ToolArgs.string(i, "monogram") ?: old.monogram,
            description = ToolArgs.string(i, "description") ?: old.description,
            size = DeskSize((ToolArgs.int(i, "w") ?: old.size.w.toInt()).toDouble(), (ToolArgs.int(i, "h") ?: old.size.h.toInt()).toDouble()),
        )
        typeIn(slug, code)
        val next = apps.change(slug, code, manifest, WorldEvent.AI).getOrElse { return fail(it.message.orEmpty()) }
        val screen = if (apps.isLive(slug)) "\n" + apps.screenNow(slug) else ""
        return ok("Changed ${next.title} to v${next.version}; the state kept.$screen", "Changed ${next.title} to v${next.version}")
    }

    /** Ai's code typed into the Editor as the person watches, before it is checked (§8.7). */
    private suspend fun typeIn(slug: String, code: String) {
        world.saveEditor()
        world.arrive(BuiltInApp.EDITOR.ref, AiDoes.Write(WorldApps.codePath(slug)))
        world.typeOut(WorldApps.codePath(slug), code)
        world.edited = false
    }
}
