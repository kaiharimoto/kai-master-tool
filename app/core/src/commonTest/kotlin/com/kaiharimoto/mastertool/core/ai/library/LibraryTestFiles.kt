package com.kaiharimoto.mastertool.core.ai.library

/** The Library's files as a map, for tests: path → text. Reads in the windows asked for, as a disk would. */
class MapLibraryFiles(private val files: Map<String, String>, private val updated: Long = 1L) : LibraryFiles {
    var opened = 0
        private set

    override fun list(dir: String): List<FileStat> =
        files.filterKeys { it.startsWith("$dir/") }.map { (p, t) -> FileStat(p, t.length.toLong(), updated) }

    override fun reader(path: String): LibraryReader? {
        val text = files[path] ?: return null
        opened++
        var at = 0
        return object : LibraryReader {
            override fun read(into: CharArray, off: Int, len: Int): Int {
                if (at >= text.length) return -1
                val n = minOf(len, text.length - at)
                text.toCharArray(into, off, at, at + n)
                at += n
                return n
            }

            override fun close() {}
        }
    }
}
