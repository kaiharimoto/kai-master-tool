package com.kaiharimoto.mastertool.core.sync

/**
 * The one test a path from outside this device passes before it becomes a file (1.0.97, the red team): an item
 * arriving by sync and an entry of a backup being restored. A path is plain segments joined by `/` — no backslash
 * (a separator on Windows), no colon (a drive or a stream), no empty, `.` or `..` segment, nothing hidden (a planted
 * `.claude/settings.json` is read by the command-line apps) — and nothing in Ai's folder that is this device's alone:
 * its keys, the command-line apps' working folder, its caches.
 *
 * Since 1.0.99 the keys live in `<data>/secrets/` and the command-line apps run in `<data>/cli-run/`, both outside
 * Ai's folder and never walked by a sync or a backup ([DEVICE_FOLDERS]). Their old places under `ai/` stay refused,
 * so an item from a device still on 1.0.98 cannot plant one there either.
 */
object InboundPath {
    /**
     * Folders of the data folder that are this device's alone (1.0.99): its keys, and the command-line apps' working
     * folder — and (1.1.1) the banlists read from Yugipedia, a cache each device fetches for itself — and (Phase D step 2)
     * `fxcache/`, the effects' verdicts and test runs, which each device recomputes: a planted "verified" never arrives.
     */
    val DEVICE_FOLDERS = setOf("secrets", "cli-run", "banlists", "fxcache")

    /** Under Ai's folder (`ai/`), what never travels: [rel] is the path inside it. */
    fun aiPrivate(rel: String): Boolean =
        rel.startsWith("credentials.") || rel.startsWith("run/") || rel.startsWith("cache/")

    /** [path] if it may be written, else null. */
    fun safe(path: String): String? {
        if (path.isEmpty() || path.length > 1024) return null
        if (path.any { it == '\\' || it == ':' || it.code < 0x20 }) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (segments.first() in DEVICE_FOLDERS) return null
        if (segments.first() == "ai" && aiPrivate(segments.drop(1).joinToString("/"))) return null
        return path
    }
}
