package com.kaiharimoto.mastertool.core.world

/**
 * The World's limits (`docs/world/DESKTOP.md` §11): cages for code a model wrote, none of them a bound on what Ai may
 * know — Ai's memory is not in the World and has no cap there. Moved here from `Worlds` (neue) so core, the tools and the
 * page read one number.
 */
object WorldLimits {
    /** A world file, in characters: a long export, a CSV of a season's games or a copy of a guide fits (was 200,000). */
    const val MAX_FILE = 16 * 1024 * 1024

    /** A run's output kept in the Terminal and the record; past it the whole output goes to [RunLog]. */
    const val RUN_OUTPUT = 64_000

    /** The Editor edits a file in place up to this; a larger one opens read-only, drawn lazily. */
    const val EDITOR_IN_PLACE = 1024 * 1024

    /** `world_read`'s page, in characters, read on with `from`. */
    const val READ_PAGE = 16_000

    /** `ygo.read`'s page, in characters: a script reads a world file in pages this long. */
    const val SCRIPT_READ_PAGE = 256 * 1024

    /** `ygo.knowledge.read`'s page, in characters. */
    const val KNOWLEDGE_PAGE = 256 * 1024

    /** `ygo.knowledge.search`'s hits. */
    const val KNOWLEDGE_HITS = 200

    /** A run's time, by default and at most when Ai asks. */
    const val RUN_SECONDS = 30
    const val RUN_SECONDS_MAX = 120
}

/**
 * Where a run's whole output goes when it is longer than [WorldLimits.RUN_OUTPUT] (§11): `files/out/<run>.log`, `<run>`
 * being the run's time in base 36 as `world://runs/<t>` names it, up to [WorldLimits.MAX_FILE]. Nothing is lost; the
 * Terminal and the record keep the first 64k and say where the rest is.
 */
object RunLog {
    /** The log's path under `files/`. */
    fun path(runAt: Long): String = "out/${runAt.toString(36)}.log"

    /** The line the Terminal and the record end with when the output was cut. */
    fun pointer(runAt: Long): String = "… the whole output is in ${path(runAt)}"

    /**
     * Collects a run's every line, to be written to [path] once the run ends — only when the output went past what is
     * kept ([needed]); bounded by [WorldLimits.MAX_FILE] so a runaway print loop cannot fill a disk. One writer (the run's thread); read once the run has ended.
     */
    class Collector {
        private val out = StringBuilder()
        var full: Boolean = false
            private set
        var needed: Boolean = false
            private set

        fun line(text: String) {
            if (out.length + text.length + 1 > WorldLimits.RUN_OUTPUT) needed = true
            if (full) return
            if (out.length + text.length + 1 > WorldLimits.MAX_FILE) {
                out.append("… (past ${WorldLimits.MAX_FILE / (1024 * 1024)} MB, the rest was not kept)\n")
                full = true
                return
            }
            out.append(text).append('\n')
        }

        fun text(): String = out.toString()
    }
}

/**
 * A file read a page at a time (`world_read`, `ygo.read`, §11): [text] from [from], [next] where the next page starts (null
 * at the end), [total] the file's length. A page ends at a line break where one is near, so a line is never cut in two
 * unless it is longer than a page.
 */
data class WorldPage(val text: String, val from: Int, val next: Int?, val total: Int) {
    /** What Ai is told under a page: where it was, and how to read on. */
    fun footer(path: String): String = when (next) {
        null -> if (from == 0) "" else "(${path}: characters $from–$total of $total, the end.)"
        else -> "(${path}: characters $from–$next of $total. world_read with from: $next reads on.)"
    }

    companion object {
        fun of(content: String, from: Int = 0, size: Int = WorldLimits.READ_PAGE): WorldPage {
            val start = from.coerceIn(0, content.length)
            var end = minOf(content.length, start + size.coerceAtLeast(1))
            if (end < content.length) {
                val nl = content.lastIndexOf('\n', end - 1)
                if (nl >= start + size / 2) end = nl + 1
            }
            return WorldPage(content.substring(start, end), start, end.takeIf { it < content.length }, content.length)
        }
    }
}
