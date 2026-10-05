package com.kaiharimoto.mastertool.core.ai.library

import java.io.File
import java.io.Reader

/**
 * The Library's files on disk (`docs/world/DESKTOP.md` §10): the app's data folder, read-only. A path is `/`-separated
 * and relative to [data]; one with `..`, a drive or a root, or one that resolves outside the folder, is not there.
 */
class DiskLibraryFiles(private val data: File) : LibraryFiles {
    private val base = data.canonicalFile

    private fun at(path: String): File? {
        if (path.isEmpty() || path.startsWith('/') || ':' in path || '\\' in path) return null
        if (path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        val f = File(base, path).canonicalFile
        return f.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    override fun list(dir: String): List<FileStat> {
        val d = at(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        return d.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.startsWith(".") }.map { f ->
            FileStat(f.relativeTo(base).invariantSeparatorsPath, f.length(), f.lastModified())
        }.toList()
    }

    override fun reader(path: String): LibraryReader? {
        val f = at(path)?.takeIf { it.isFile } ?: return null
        val r: Reader = f.bufferedReader(Charsets.UTF_8, 64 * 1024)
        return object : LibraryReader {
            override fun read(into: CharArray, off: Int, len: Int): Int = r.read(into, off, len)
            override fun close() = r.close()
        }
    }
}
