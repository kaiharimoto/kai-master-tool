package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.WorldEvent
import java.io.File

/**
 * A world's apps on disk (`docs/world/DESKTOP.md` §8.2): `<world>/apps/<slug>/` with `app.json`, `main.js`, `state.json`
 * and the [AppLimits.VERSIONS] versions before this one in `versions/<n>.js`. Every write is atomic (a `.tmp` beside it,
 * then a rename, and `.tmp` is never synced). What it changes, it says as a [WorldEvent] of kind `app` for the log —
 * every app change is seen (§8.6 point 6).
 *
 * Blocking file work: the host calls it off the frame thread. One writer per world (the host's own lock).
 */
class AppStore(private val world: File) {
    private val root = File(world, AppPaths.ROOT)

    private fun dir(slug: String) = File(root, slug)
    private fun file(slug: String, name: String) = File(dir(slug), name)

    /** Every app of the world, by the order they were made. */
    fun list(): List<AppManifest> =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
            AppCodec.decode(File(d, AppPaths.MANIFEST).takeIf { it.isFile }?.readText(), d.name)?.takeIf { File(d, AppPaths.CODE).isFile }
        }.sortedWith(compareBy({ it.created }, { it.slug }))

    fun manifest(slug: String): AppManifest? =
        if (!AppCodec.validSlug(slug) || !file(slug, AppPaths.CODE).isFile) null else AppCodec.decode(file(slug, AppPaths.MANIFEST).takeIf { it.isFile }?.readText(), slug)

    fun code(slug: String): String? = if (!AppCodec.validSlug(slug)) null else file(slug, AppPaths.CODE).takeIf { it.isFile }?.readText()

    /** The app as a runner takes it, at its current version. */
    fun appCode(slug: String): AppCode? {
        val m = manifest(slug) ?: return null
        return AppCode(slug, m.version, code(slug) ?: return null)
    }

    /** A new app: [m]'s slug free, the world under [AppLimits.APPS], [code] within its limit. Version 1. */
    fun make(m: AppManifest, code: String, now: Long): Result<Pair<AppManifest, WorldEvent>> = runCatching {
        require(AppCodec.validSlug(m.slug)) { "an app's slug is a-z, 0-9 and -, at most 32: “${m.slug}”" }
        require(manifest(m.slug) == null) { "there is an app “${m.slug}” already: change it, or choose another slug" }
        require(list().size < AppLimits.APPS) { "a world holds at most ${AppLimits.APPS} apps: delete one first" }
        AppCodec.codeProblem(code)?.let { throw IllegalArgumentException(it) }
        val made = AppCodec.sane(m.copy(version = 1, api = AppManifest.API, created = now, updated = now))
        dir(m.slug).mkdirs()
        write(file(m.slug, AppPaths.CODE), code)
        write(file(m.slug, AppPaths.MANIFEST), AppCodec.encode(made))
        made to event(made, "Made “${made.title}”", now)
    }

    /**
     * New code for [slug]: the code it had kept as `versions/<n>.js` (the newest [AppLimits.VERSIONS] kept), the version
     * raised, the state kept (§8.3). [manifest] changes the manifest's other fields too, when given.
     */
    fun change(slug: String, code: String, now: Long, by: String = WorldEvent.AI, manifest: AppManifest? = null): Result<Pair<AppManifest, WorldEvent>> = runCatching {
        val old = manifest(slug) ?: throw IllegalArgumentException("there is no app “$slug”")
        AppCodec.codeProblem(code)?.let { throw IllegalArgumentException(it) }
        val before = code(slug).orEmpty()
        File(dir(slug), AppPaths.VERSIONS).mkdirs()
        write(file(slug, "${AppPaths.VERSIONS}/${old.version}.js"), before)
        prune(slug)
        val next = AppCodec.sane((manifest ?: old).copy(slug = slug, version = old.version + 1, created = old.created, updated = now))
        write(file(slug, AppPaths.CODE), code)
        write(file(slug, AppPaths.MANIFEST), AppCodec.encode(next))
        next to event(next, "Changed “${next.title}” to v${next.version}", now, by)
    }

    /** *Back to v2*: that version's code as the next version (a version only rises). */
    fun back(slug: String, to: Int, now: Long, by: String = WorldEvent.YOU): Result<Pair<AppManifest, WorldEvent>> = runCatching {
        val code = file(slug, "${AppPaths.VERSIONS}/$to.js").takeIf { it.isFile }?.readText() ?: throw IllegalArgumentException("v$to of “$slug” is not kept")
        change(slug, code, now, by).getOrThrow()
    }

    /** The versions kept for Back to, newest first. */
    fun versions(slug: String): List<Int> =
        File(dir(slug), AppPaths.VERSIONS).listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".js").toIntOrNull() }.sortedDescending()

    private fun prune(slug: String) {
        val keep = versions(slug).take(AppLimits.VERSIONS).toSet()
        File(dir(slug), AppPaths.VERSIONS).listFiles().orEmpty().forEach { f ->
            if (f.name.removeSuffix(".js").toIntOrNull() !in keep) f.delete()
        }
    }

    /** The stored state; one that will not read is moved aside to `state.broken.json` and the app starts from `init()`. */
    fun readState(slug: String): StateRead {
        val f = file(slug, AppPaths.STATE)
        val read = AppCodec.readState(f.takeIf { it.isFile }?.readText())
        if (read is StateRead.Broken) {
            val aside = file(slug, AppPaths.BROKEN)
            aside.delete()
            if (!f.renameTo(aside)) {
                aside.writeText(f.readText())
                f.delete()
            }
        }
        return read
    }

    fun writeState(slug: String, json: String) {
        AppCodec.stateProblem(json)?.let { throw IllegalArgumentException(it) }
        write(file(slug, AppPaths.STATE), json)
    }

    /** *Start fresh* (§8.3): the state kept as `state.prev.json`, none in its place. */
    fun startFresh(slug: String) {
        val f = file(slug, AppPaths.STATE)
        if (!f.isFile) return
        val prev = file(slug, AppPaths.PREVIOUS)
        prev.delete()
        if (!f.renameTo(prev)) {
            prev.writeText(f.readText())
            f.delete()
        }
    }

    /** Deletes the app and everything of it (the person confirmed). */
    fun delete(slug: String, now: Long, by: String = WorldEvent.YOU): Result<WorldEvent> = runCatching {
        val m = manifest(slug) ?: throw IllegalArgumentException("there is no app “$slug”")
        dir(slug).deleteRecursively()
        event(m, "Deleted “${m.title}”", now, by)
    }

    /** An app's call threw (§8.5): the log keeps it, and Ai hears of it at its next turn. */
    fun threw(m: AppManifest, failed: AppCall.Failed, now: Long): WorldEvent = event(m, "“${m.title}”: ${failed.words}", now, WorldEvent.YOU)

    private fun event(m: AppManifest, text: String, now: Long, by: String = m.by) =
        WorldEvent(now, WorldEvent.Kind.APP, by, path = AppPaths.code(m.slug), text = text, app = m.slug)

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }
}
