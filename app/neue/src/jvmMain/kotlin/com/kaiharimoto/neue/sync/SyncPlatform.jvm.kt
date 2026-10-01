package com.kaiharimoto.neue.sync

import com.kaiharimoto.mastertool.core.sync.SyncStore
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.security.SecureRandom

actual object SyncPlatform {
    actual fun folderStore(folder: String): SyncStore = FileFolderStore(File(folder))

    /**
     * The folder chooser: on macOS the system's own, `FileDialog` asked for folders; elsewhere Swing's
     * chooser set to folders, since Windows' `FileDialog` cannot choose one.
     */
    actual suspend fun pickFolder(): String? {
        val mac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (mac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(null as Frame?, "Choose the folder to sync with", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory ?: return null
                val name = dialog.file ?: return File(dir).absolutePath
                return File(dir, name).absolutePath
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = javax.swing.JFileChooser(System.getProperty("user.home")).apply {
            dialogTitle = "Choose the folder to sync with"
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }

    actual fun folderLabel(folder: String): String = folder.replaceFirst(System.getProperty("user.home").orEmpty(), "~").ifBlank { folder }

    actual val deviceName: String by lazy {
        (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull())
            ?.removeSuffix(".local")?.takeIf { it.isNotBlank() } ?: "This computer"
    }

    private val secure = SecureRandom()

    actual fun random(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return String(CharArray(length) { chars[secure.nextInt(chars.length)] })
    }
}
