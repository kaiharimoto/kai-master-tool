package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.ui.DeckFileAccess
import com.kaiharimoto.mastertool.ui.ImportedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Deck files through the operating system's own dialogs.
 *
 * `FileDialog` rather than Swing's `JFileChooser`: it is the native dialog on
 * Windows and macOS — recent folders, the sidebar, everything a person already
 * knows — where the Swing one is a 1998 imitation of it. It is modal, so it
 * opens off the UI thread.
 */
class NeueFileAccess : DeckFileAccess {

    override suspend fun importDeck(): ImportedFile? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Import deck", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.endsWith(".ydk", true) || name.endsWith(".ydkx", true) }
            // Windows ignores the filter above; a pattern in the name field is what it reads.
            file = "*.ydk;*.ydkx"
            isVisible = true
        }
        val name = dialog.file ?: return@withContext null
        val file = File(dialog.directory, name)
        runCatching { ImportedFile(file.name, file.readText()) }.getOrNull()
    }

    override suspend fun exportDeck(suggestedName: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Export deck", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val name = dialog.file ?: return@withContext false
        runCatching { File(dialog.directory, name).writeText(content) }.isSuccess
    }

    /** A desktop has no share sheet: write the file and show it in its folder. */
    override suspend fun shareDeck(suggestedName: String, content: String) {
        withContext(Dispatchers.IO) {
            val file = File(System.getProperty("java.io.tmpdir"), suggestedName)
            runCatching {
                file.writeText(content)
                Platform.open(file.parentFile)
            }
        }
    }
}
