package com.kaiharimoto.mastertool.core.sync

/**
 * The one test a path from outside this device passes before it becomes a file (1.0.97, the red team): an item
 * arriving by sync and an entry of a backup being restored. A path is plain segments joined by `/` — no backslash
 * (a separator on Windows), no colon (a drive or a stream), no empty, `.` or `..` segment, nothing hidden (a planted
 * `.claude/settings.json` is read by the command-line apps) — and nothing in Ai's folder that is this device's alone:
 * its keys, the command-line apps' working folder, its caches.
 */
object InboundPath {
    /** Under Ai's folder (`ai/`), what never travels: [rel] is the path inside it. */
    fun aiPrivate(rel: String): Boolean =
        rel.startsWith("credentials.") || rel.startsWith("run/") || rel.startsWith("cache/")

    /** [path] if it may be written, else null. */
    fun safe(path: String): String? {
        if (path.isEmpty() || path.length > 1024) return null
        if (path.any { it == '\\' || it == ':' || it.code < 0x20 }) return null
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) return null
        if (segments.first() == "ai" && aiPrivate(segments.drop(1).joinToString("/"))) return null
        return path
    }
}
