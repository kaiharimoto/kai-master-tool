package com.kaiharimoto.mastertool.core.files

/**
 * The data folder as the tablet's and the phone's Files app see it (I.2): Android keeps an app's folder from every other app,
 * so the APK lends it, read only, to the system's Files app (`DataFolderProvider`) — everything but what is this device's
 * secret. A document's id is its path under the folder, behind [ROOT]; an id that names anything hidden, or climbs out of
 * the folder, names nothing.
 */
object DataTree {
    /** The root's document id, and the start of every other. */
    const val ROOT = "data"

    /**
     * Never lent: the keys (`secrets/`), the command-line apps' working folder (`cli-run/`) and the course browser's profile
     * (`browser/`, its cookies). Under Ai's folder, the keys' old place and the run folder that holds a turn's token.
     */
    val HIDDEN = setOf("secrets", "cli-run", "browser")

    /** Whether the file at [rel] (its path under the folder, `/`-joined; empty for the folder) may be shown. */
    fun visible(rel: String): Boolean {
        if (rel.isEmpty()) return true
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") || it.contains('\\') }) return false
        if (segments.first() in HIDDEN) return false
        if (segments.first() == "ai" && segments.size > 1) {
            val inside = segments[1]
            if (inside.startsWith("credentials.") || inside == "run") return false
        }
        return true
    }

    /** The document id of the file at [rel]. */
    fun idOf(rel: String): String = if (rel.isEmpty()) ROOT else "$ROOT/$rel"

    /** The path an id names, or null when it names nothing that may be shown. */
    fun relOf(id: String): String? = when {
        id == ROOT -> ""
        id.startsWith("$ROOT/") -> id.removePrefix("$ROOT/").takeIf(::visible)
        else -> null
    }
}
