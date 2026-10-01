package com.kaiharimoto.neue.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.kaiharimoto.mastertool.core.sync.SyncException
import com.kaiharimoto.mastertool.core.sync.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

actual object SyncPlatform {
    private var context: Context? = null
    private var picker: (suspend () -> String?)? = null

    /** The activity's folder picker (`OpenDocumentTree`, its grant kept), set as it starts. */
    fun attach(context: Context, picker: suspend () -> String?) {
        this.context = context.applicationContext
        this.picker = picker
    }

    actual fun folderStore(folder: String): SyncStore =
        if (folder.startsWith("content://")) TreeStore(context ?: throw SyncException("The app is not ready to sync yet."), Uri.parse(folder))
        else FileFolderStore(File(folder))

    actual suspend fun pickFolder(): String? = picker?.invoke()

    /** A granted folder as the picker showed it: its provider's name for it, else the end of its path. */
    actual fun folderLabel(folder: String): String {
        if (!folder.startsWith("content://")) return folder
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)) }.getOrNull() ?: return folder
        return Uri.decode(id).substringAfter(':').ifBlank { Uri.decode(id) }
    }

    actual val deviceName: String by lazy {
        val c = context
        val named = c?.let { runCatching { android.provider.Settings.Global.getString(it.contentResolver, "device_name") }.getOrNull() }
        named?.takeIf { it.isNotBlank() } ?: (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL)
    }

    private val secure = SecureRandom()

    actual fun random(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return String(CharArray(length) { chars[secure.nextInt(chars.length)] })
    }
}

/**
 * A folder the system's picker granted (1.0.68): Syncthing's, Nextcloud's, any provider's. Read and
 * written through the Storage Access Framework, so the app needs no permission over the phone's storage.
 */
private class TreeStore(private val context: Context, private val tree: Uri) : SyncStore {
    override val label: String = SyncPlatform.folderLabel(tree.toString())
    private val resolver get() = context.contentResolver

    /** Each folder's children, by name, read once per store. */
    private val children = HashMap<String, MutableMap<String, String>>()

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun rootId(): String = DocumentsContract.getTreeDocumentId(tree)

    private fun childrenOf(parentId: String): MutableMap<String, String> = children.getOrPut(parentId) {
        val out = HashMap<String, String>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = runCatching { resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null) }
            .getOrElse { throw SyncException("The sync folder can no longer be read. Choose it again in Settings › Sync.", it) }
        cursor?.use { c -> while (c.moveToNext()) out[c.getString(1)] = c.getString(0) }
        out
    }

    /** The folder at [path] under the store's root, made when [make] and missing. */
    private fun folderId(path: List<String>, make: Boolean): String? {
        var id = rootId()
        for (name in listOf("NeueMasterTool") + path) {
            val found = childrenOf(id)[name]
            id = found ?: if (make) {
                val made = DocumentsContract.createDocument(resolver, docUri(id), Document.MIME_TYPE_DIR, name)
                    ?: throw SyncException("A folder could not be made in the sync folder.")
                val newId = DocumentsContract.getDocumentId(made)
                childrenOf(id)[name] = newId
                newId
            } else return null
        }
        return id
    }

    override suspend fun list(folder: String): List<String> = withContext(Dispatchers.IO) {
        val id = folderId(folder.split('/').filter { it.isNotEmpty() }, make = false) ?: return@withContext emptyList()
        childrenOf(id).keys.filter { !it.startsWith(".") && !it.endsWith(".tmp") }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val parts = name.split('/')
        val parent = folderId(parts.dropLast(1), make = false) ?: return@withContext null
        val id = childrenOf(parent)[parts.last()] ?: return@withContext null
        resolver.openInputStream(docUri(id))?.use { it.readBytes() }
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val parts = name.split('/')
        val parent = folderId(parts.dropLast(1), make = true)!!
        val existing = childrenOf(parent)[parts.last()]
        val id = existing ?: run {
            val made = DocumentsContract.createDocument(resolver, docUri(parent), "application/octet-stream", parts.last())
                ?: throw SyncException("A file could not be made in the sync folder.")
            DocumentsContract.getDocumentId(made).also { childrenOf(parent)[parts.last()] = it }
        }
        val out = resolver.openOutputStream(docUri(id), "wt") ?: throw SyncException("A file in the sync folder could not be written.")
        out.use { it.write(bytes) }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) {
            val parts = name.split('/')
            val parent = folderId(parts.dropLast(1), make = false) ?: return@withContext
            val id = childrenOf(parent).remove(parts.last()) ?: return@withContext
            runCatching { DocumentsContract.deleteDocument(resolver, docUri(id)) }
        }
    }
}
