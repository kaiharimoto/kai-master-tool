package com.kaiharimoto.neue.duel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The duel's files on the desk and Android: under [dir], `<data>/duel/`, as they always were. */
class FileDuelStore(val dir: File) : DuelStore {
    private fun file(path: String) = File(dir, path)

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) { readNow(path) }

    override fun readNow(path: String): String? = runCatching { file(path).takeIf { it.exists() }?.readText() }.getOrNull()

    override suspend fun write(path: String, text: String) = withContext(Dispatchers.IO) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        Unit
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) { file(path).delete(); Unit }

    override suspend fun list(folder: String, suffix: String): List<StoredFile> = withContext(Dispatchers.IO) {
        file(folder).listFiles { f -> f.name.endsWith(suffix) }.orEmpty().mapNotNull { f ->
            runCatching { StoredFile(f.name, f.readText(), f.lastModified()) }.getOrNull()
        }
    }
}

/**
 * The duel written now and waited for: the app is closing. Off the main thread and never long: the writers lock on
 * the IO pool, so nothing waits on this thread (1.0.85).
 */
fun Duels.flushNow() {
    val g = takeUnsaved() ?: return
    kotlinx.coroutines.runBlocking(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(2000) { saveNow(g) } }
}

/** The desk's and Android's table: its files under [dir] (`<data>/duel/`), played over the local network's sockets. */
fun Duels(dir: File): Duels = Duels(FileDuelStore(dir)).also {
    it.network = DuelNet(it)
    it.match = DuelMatches(it)
}
