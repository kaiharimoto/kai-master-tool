package com.kaiharimoto.mastertool

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.kaiharimoto.mastertool.core.files.DataTree
import java.io.File
import java.io.FileNotFoundException

/**
 * The app's data folder lent to the system's Files app, read only (Phase I, I.2): Android keeps an app's folder from every
 * other app, so Settings' "Open" on the data folder had nothing to open. What is lent, and what never is (the keys, the
 * command-line apps' folder, the course browser's profile), is [DataTree]; only the system's document picker and Files app
 * may ask (`MANAGE_DOCUMENTS` in the manifest), and nothing here writes, renames or deletes.
 */
class DataFolderProvider : DocumentsProvider() {
    private val folder: File get() = requireNotNull(context).filesDir

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow().apply {
            add(Root.COLUMN_ROOT_ID, DataTree.ROOT)
            add(Root.COLUMN_DOCUMENT_ID, DataTree.ROOT)
            add(Root.COLUMN_TITLE, "Neue Master Tool")
            add(Root.COLUMN_SUMMARY, "The app's data, read only")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_AVAILABLE_BYTES, folder.freeSpace)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { row(this, documentId, fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val parent = relOf(parentDocumentId)
        val dir = fileOf(parentDocumentId)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { f ->
                val rel = if (parent.isEmpty()) f.name else "$parent/${f.name}"
                if (DataTree.visible(rel)) row(this, DataTree.idOf(rel), f)
            }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("The data folder is lent read only")
        val file = fileOf(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = DataTree.relOf(parentDocumentId) ?: return false
        val child = DataTree.relOf(documentId) ?: return false
        return parent.isEmpty() || child.startsWith("$parent/")
    }

    private fun relOf(id: String): String = DataTree.relOf(id) ?: throw FileNotFoundException(id)

    /** The file an id names: inside the folder (checked again on the real path, links included), never a hidden one. */
    private fun fileOf(id: String): File {
        val rel = relOf(id)
        val base = folder.canonicalFile
        val file = File(base, rel).canonicalFile
        if (file != base && !file.path.startsWith(base.path + File.separator)) throw FileNotFoundException(id)
        if (!file.exists()) throw FileNotFoundException(id)
        return file
    }

    private fun row(cursor: MatrixCursor, id: String, file: File) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == DataTree.ROOT) "Neue Master Tool" else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun mimeOf(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        else -> file.extension.lowercase().let { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: TEXT[ext] ?: "application/octet-stream"
        }
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** The app's own files the system does not know: read as text. */
        private val TEXT = mapOf("md" to "text/markdown", "jsonl" to "text/plain", "ydk" to "text/plain", "ydkx" to "text/plain", "ydkw" to "text/plain", "log" to "text/plain")
    }
}
