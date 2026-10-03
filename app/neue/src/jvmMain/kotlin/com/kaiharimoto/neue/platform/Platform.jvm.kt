package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.core.update.DesktopOs
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

actual object Platform {
    actual val os: DesktopOs = DesktopOs.of(System.getProperty("os.name") ?: "")

    /** Injected by Gradle (`-Dneue.version`), so the version lives in one file: `neue/VERSION`. */
    actual val version: String = System.getProperty("neue.version")?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

    /**
     * Per-user data, following each platform's convention, and deliberately not
     * the tablet-era desktop app's folder: Neue keeps its own database, so an
     * experiment here never touches decks there.
     */
    actual val dataDir: File by lazy {
        val home = System.getProperty("user.home")
        val base = when (os) {
            DesktopOs.MAC -> File(home, "Library/Application Support/NeueMasterTool")
            DesktopOs.WINDOWS -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "NeueMasterTool")
            DesktopOs.LINUX, DesktopOs.ANDROID -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "neue-master-tool")
        }
        base.mkdirs()
        base
    }

    actual fun systemLine(): String =
        "Neue Master Tool $version · ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(${System.getProperty("os.arch")}) · Java ${System.getProperty("java.version")}"

    actual fun browse(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else if (os == DesktopOs.LINUX) {
                ProcessBuilder("xdg-open", url).start()
            }
        }
    }

    actual fun open(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
            } else {
                ProcessBuilder(if (os == DesktopOs.MAC) "open" else "xdg-open", file.absolutePath).start()
            }
        }
    }

    actual fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    /**
     * The operating system's own dialog. `FileDialog` rather than Swing's
     * `JFileChooser`: it is the native one on Windows and macOS. Modal, on the
     * UI thread, as it always was.
     */
    actual suspend fun pick(title: String, extensions: Set<String>): PickedFile? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
            isVisible = true
        }
        val chosen = dialog.file ?: return null
        val source = File(dialog.directory, chosen)
        if (!source.isFile) return null
        return runCatching { PickedFile(source.name, source.readBytes()) }.getOrNull()
    }

    actual val canShare: Boolean = false

    actual fun shareText(text: String, title: String) = copy(text)

    actual fun onUnmeteredNetwork(): Boolean = true

    /** The desk shows its decks' QR codes (1.0.30); it reads none. */
    actual val scanSources: Set<QrSource> = emptySet()

    actual suspend fun scanQr(from: QrSource): QrScan = QrScan.Cancelled

    actual val canTakePhoto: Boolean = false

    actual suspend fun takePhoto(): PickedFile? = null

    /** A computer does not freeze a window's process when another has the focus. */
    actual fun working(on: Boolean, title: String, line: String) = Unit

    actual fun keepAwake(key: String, on: Boolean, title: String, line: String) = Unit

    actual fun answered(title: String, line: String) = Unit
}
