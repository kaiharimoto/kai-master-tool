package com.kaiharimoto.neue.sync

import com.kaiharimoto.mastertool.core.sync.SyncException
import com.kaiharimoto.mastertool.core.sync.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A folder some other app already keeps in sync, as a store (1.0.68): iCloud Drive, Dropbox, OneDrive,
 * Google Drive for desktop, Syncthing. The files go in a `NeueMasterTool` folder inside it, each written
 * whole beside itself and then moved into place, so the syncing app never sends half a file.
 */
class FileFolderStore(private val folder: File) : SyncStore {
    private val root = File(folder, "NeueMasterTool")
    override val label: String = folder.path

    override suspend fun list(folder: String): List<String> = withContext(Dispatchers.IO) {
        File(root, folder).listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }.map { it.name }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        File(root, name).takeIf { it.isFile }?.let { f -> runCatching { f.readBytes() }.getOrElse { throw SyncException("A file in the folder could not be read: ${it.message}", it) } }
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (!this@FileFolderStore.folder.isDirectory) throw SyncException("The sync folder is not there any more. Choose it again in Settings › Sync.")
        val target = File(root, name)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw SyncException("A file could not be saved in the sync folder.")
            }
        } catch (e: java.io.IOException) {
            temp.delete()
            throw SyncException("A file could not be saved in the sync folder: ${e.message}", e)
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(root, name).delete() }
    }
}
