package com.kaiharimoto.mastertool.core.files

/**
 * What of this computer is taken out of text that is sent (Phase H, H.8; the red team, 2026-10): the diagnostics file's log
 * said where the data, working and temporary folders are, and those paths hold the person's name more often than not.
 * [scrub] puts a placeholder in each folder's place — `<data>`, `<temp>`, `<home>`, `<work>` — the longest first, so a folder
 * inside another is named by the nearer one; in any case, either way round of its separators, and as a JSON string escapes
 * it. A web address keeps only its site ([addresses]): a course's or a page's path says what the person studies.
 *
 * [folders] are each a path and its placeholder; a path of fewer than [SEGMENTS] parts (`/`, `C:\`, `/tmp`) says nothing of
 * anyone and is left, since replacing it would take every path in the text apart.
 */
class PathScrub(folders: List<Pair<String, String>>) {
    private val forms: List<Pair<String, String>> = folders
        .flatMap { (path, placeholder) -> forms(path).map { it to placeholder } }
        .distinctBy { it.first.lowercase() }
        .sortedByDescending { it.first.length }

    /** [text] with every folder given in its placeholder's place, and every web address cut to its site. */
    fun scrub(text: String): String {
        var out = text
        for ((path, placeholder) in forms) out = out.replace(path, placeholder, ignoreCase = true)
        return addresses(out)
    }

    companion object {
        /** The fewest parts a folder replaced has. */
        const val SEGMENTS = 2

        /** The ways [path] is written: as given, with `/`, with `\`, and with `\` doubled as JSON writes it; none for a short one. */
        fun forms(path: String): List<String> {
            val p = path.trim().trimEnd('/', '\\')
            if (p.split('/', '\\').count { it.isNotEmpty() } < SEGMENTS) return emptyList()
            val back = p.replace('/', '\\')
            return listOf(p, p.replace('\\', '/'), back, back.replace("\\", "\\\\")).distinct()
        }

        private val ADDRESS = Regex("""(https?://[^/\s"'<>]+)/[^\s"'<>)\]]+""")

        /** [text] with each web address cut to its site: `https://metafy.gg/@kai/course` reads `https://metafy.gg/…`. */
        fun addresses(text: String): String = ADDRESS.replace(text) { "${it.groupValues[1]}/…" }
    }
}
