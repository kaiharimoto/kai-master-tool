package com.kaiharimoto.neue.platform

import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Facts about the machine and the build, and the few things done to it from outside Compose. */
object Platform {
    val os: DesktopOs = DesktopOs.of(System.getProperty("os.name") ?: "")

    /** Injected by Gradle (`-Dneue.version`), so the version lives in one file: `neue/VERSION`. */
    val version: String = System.getProperty("neue.version")?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

    const val REPOSITORY = "https://github.com/${GitHubReleaseApi.DEFAULT_OWNER}/${GitHubReleaseApi.DEFAULT_REPO}"

    /**
     * Per-user data, following each platform's convention, and deliberately not
     * the tablet-era desktop app's folder: Neue keeps its own database, so an
     * experiment here never touches decks there.
     */
    val dataDir: File by lazy {
        val home = System.getProperty("user.home")
        val base = when (os) {
            DesktopOs.MAC -> File(home, "Library/Application Support/NeueMasterTool")
            DesktopOs.WINDOWS -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "NeueMasterTool")
            DesktopOs.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "neue-master-tool")
        }
        base.mkdirs()
        base
    }

    val crashFile: File get() = File(dataDir, "crash.txt")

    fun browse(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            } else if (os == DesktopOs.LINUX) {
                ProcessBuilder("xdg-open", url).start()
            }
        }
    }

    fun open(file: File) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file)
            } else {
                ProcessBuilder(if (os == DesktopOs.MAC) "open" else "xdg-open", file.absolutePath).start()
            }
        }
    }

    /** The facts a bug report needs and nobody remembers to include. */
    fun systemLine(): String =
        "Neue Master Tool $version · ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(${System.getProperty("os.arch")}) · Java ${System.getProperty("java.version")}"

    /**
     * A new GitHub issue with the version and the system already written in.
     * [detail] is a crash trace when there is one; it is cut to fit in a URL.
     */
    fun reportIssue(title: String = "", detail: String? = null) {
        val body = buildString {
            appendLine("**What happened**")
            appendLine()
            appendLine()
            appendLine("**What you expected**")
            appendLine()
            appendLine()
            appendLine("---")
            appendLine(systemLine())
            if (detail != null) {
                appendLine()
                appendLine("```")
                appendLine(detail.take(5000))
                appendLine("```")
            }
        }
        fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
        browse("$REPOSITORY/issues/new?labels=neue&title=${enc(title.ifBlank { "Neue: " })}&body=${enc(body)}")
    }

    fun writeCrash(error: Throwable) {
        runCatching {
            crashFile.writeText(systemLine() + "\n\n" + error.stackTraceToString())
        }
    }

    /**
     * Keeps a full-screen window full screen when another window takes focus.
     *
     * Compose (through skiko) makes a window full screen on Windows and Linux with
     * `GraphicsDevice.setFullScreenWindow`, the JDK's exclusive mode, and on
     * Windows the JDK then adds a listener of its own that **minimises the window
     * the moment it loses focus** (`Win32GraphicsDevice`'s full-screen window
     * adapter, and Direct3D's) — made for games that change the display mode.
     * Neue changes no display mode, so a click on another monitor should leave
     * the builder where it is (kai, 1.0.12). Removing that one listener is the
     * whole fix; it is found by name because the class is private to the JDK,
     * and a JDK that does not add it leaves nothing to remove.
     */
    fun keepFullScreen(window: java.awt.Window) {
        window.windowListeners
            .filter { it.javaClass.name.contains("FSWindowAdapter") }
            .forEach(window::removeWindowListener)
    }
}
