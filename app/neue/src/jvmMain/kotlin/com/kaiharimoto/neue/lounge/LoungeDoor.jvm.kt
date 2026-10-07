package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.duel.lounge.LoungePrefs
import com.kaiharimoto.mastertool.core.model.Card
import java.io.File

/** The desk's door: [LoungeServer] on 127.0.0.1 (and the LAN when asked), and `cloudflared` beside it. */
actual object LoungeDoor {
    actual val available: Boolean = true
    private var server: LoungeServer? = null
    private var tunnel: Process? = null

    actual fun open(host: LoungeHost, prefs: LoungePrefs, passcodeHash: () -> String?, pool: () -> List<Card>, original: (Int) -> File?, artCache: File): String? {
        close()
        val s = LoungeServer(host, passcodeHash, pool, original, artCache, page = { path -> page(path) })
        return runCatching { s.start(prefs.port, prefs.lan); server = s; null }
            .getOrElse { "The door could not open on port ${prefs.port}: ${it.message ?: "it is in use"}. Choose another port." }
    }

    actual fun close() {
        server?.stop()
        server = null
    }

    /** The Lounge's page, shipped inside the app (`:guest`'s build): the same version as the computer serving it. */
    private fun page(path: String): ByteArray? =
        LoungeDoor::class.java.getResourceAsStream("/lounge/$path")?.use { it.readBytes() }

    actual fun tunnel(token: String, line: (String) -> Unit, ended: (Int) -> Unit): String? {
        stopTunnel()
        val program = cloudflared() ?: return "cloudflared is not installed: see Settings › Lounge for how to add it"
        val p = runCatching {
            // The token travels in the environment, never on a command line another program on this computer could read.
            ProcessBuilder(program, "tunnel", "--no-autoupdate", "run")
                .apply { environment()["TUNNEL_TOKEN"] = token }
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return "cloudflared could not start: ${it.message}" }
        tunnel = p
        Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine(line) }
            ended(runCatching { p.waitFor() }.getOrDefault(-1))
        }.apply { isDaemon = true; name = "lounge-tunnel"; start() }
        return null
    }

    actual fun stopTunnel() {
        tunnel?.let { p -> p.descendants().forEach { it.destroy() }; p.destroy() }
        tunnel = null
    }

    /** `cloudflared` on the PATH, or where its installers put it. */
    private fun cloudflared(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("win")
        val name = if (windows) "cloudflared.exe" else "cloudflared"
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).map { File(it, name) }
        val known = listOf(
            File("/opt/homebrew/bin/cloudflared"), File("/usr/local/bin/cloudflared"), File("/usr/bin/cloudflared"),
            File(System.getenv("ProgramFiles(x86)").orEmpty(), "cloudflared/cloudflared.exe"),
            File(System.getenv("ProgramFiles").orEmpty(), "cloudflared/cloudflared.exe"),
        )
        return (path + known).firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
