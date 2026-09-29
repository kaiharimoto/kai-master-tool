package com.kaiharimoto.neue.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual suspend fun deliverFile(name: String, mime: String, bytes: ByteArray): String? {
    val extension = name.substringAfterLast('.', "")
    val target = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Save $name", FileDialog.SAVE).apply {
            file = name
            isVisible = true
        }
        val chosen = dialog.file ?: return@withContext null
        // The dialog keeps what was typed; the file keeps its kind.
        val named = if (extension.isNotEmpty() && !chosen.endsWith(".$extension", ignoreCase = true)) "$chosen.$extension" else chosen
        File(dialog.directory, named)
    } ?: return null
    val written = withContext(Dispatchers.IO) { runCatching { target.writeBytes(bytes) }.isSuccess }
    if (!written) return "${target.name} could not be saved there"
    Platform.open(target)
    return "Saved ${target.name}"
}
