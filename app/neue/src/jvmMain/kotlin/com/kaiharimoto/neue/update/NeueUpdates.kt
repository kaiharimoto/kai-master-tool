package com.kaiharimoto.neue.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.NeueUpdate
import com.kaiharimoto.mastertool.core.update.NeueUpdateChecker
import com.kaiharimoto.mastertool.core.update.NeueUpdateStatus
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

/**
 * Updates from GitHub, the way the tablet gets them: ask on launch, say so in
 * the title bar when there is something newer, and on a click download it and
 * hand it to the installer.
 *
 * - **Windows** installs in place. The MSI is per-user (see `build.gradle.kts`),
 *   so `msiexec` needs no administrator; a small script waits for it to finish
 *   and starts the new build, and this one quits so its files can be replaced.
 * - **macOS** opens the downloaded disk image. Replacing a running app bundle
 *   from inside itself is what Gatekeeper exists to stop.
 * - **Linux** hands the `.deb` to the desktop's package installer.
 */
class NeueUpdates(
    private val checker: NeueUpdateChecker,
    private val scope: CoroutineScope,
) {
    var checking by mutableStateOf(false)
        private set
    var available by mutableStateOf<NeueUpdate?>(null)
        private set
    var dialogOpen by mutableStateOf(false)
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    /** One line for Settings: the last answer from GitHub. */
    var status by mutableStateOf("Not checked yet")
        private set
    /** A line for a toast, consumed once shown. */
    var message by mutableStateOf<String?>(null)

    fun check(userInitiated: Boolean) {
        if (checking) return
        scope.launch {
            checking = true
            when (val result = checker.check()) {
                is NeueUpdateStatus.Available -> {
                    available = result.update
                    status = "${result.update.versionName} is available"
                    if (userInitiated) dialogOpen = true
                }
                NeueUpdateStatus.UpToDate -> {
                    available = null
                    status = "Up to date"
                    if (userInitiated) message = "Up to date"
                }
                is NeueUpdateStatus.Failed -> {
                    status = "Could not check · ${result.message}"
                    if (userInitiated) message = "Update check failed: ${result.message}"
                }
            }
            checking = false
        }
    }

    fun openReleasePage() {
        available?.release?.htmlUrl?.let(Platform::browse)
    }

    fun install() {
        val update = available ?: return
        val asset = update.installer
        if (asset == null) {
            openReleasePage()
            return
        }
        if (downloading) return
        scope.launch {
            downloading = true
            progress = null
            val file = runCatching {
                withContext(Dispatchers.IO) { download(asset.url, File(System.getProperty("java.io.tmpdir"), asset.name), asset.sizeBytes) }
            }.getOrElse { error ->
                downloading = false
                message = "Download failed: ${error.message}"
                return@launch
            }
            downloading = false
            runCatching { handOff(file) }.onFailure { message = "Could not start the installer: ${it.message}" }
        }
    }

    private fun download(url: String, target: File, expected: Long): File {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
        val request = HttpRequest.newBuilder(URI(url)).header("Accept", "application/octet-stream").build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) error("GitHub returned ${response.statusCode()}")
        val total = response.headers().firstValueAsLong("Content-Length").orElse(expected).takeIf { it > 0 }
        val partial = File(target.path + ".part")
        response.body().use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    read += n
                    if (total != null) progress = (read.toFloat() / total).coerceIn(0f, 1f)
                }
            }
        }
        target.delete()
        if (!partial.renameTo(target)) error("Could not write ${target.name}")
        return target
    }

    private fun handOff(installer: File) {
        when (Platform.os) {
            DesktopOs.WINDOWS -> {
                // The launcher that started this process is the one the new build replaces.
                val exe = ProcessHandle.current().info().command().orElse(null)
                val script = File(installer.parentFile, "neue-update.cmd")
                script.writeText(
                    buildString {
                        appendLine("@echo off")
                        appendLine("start \"\" /wait msiexec /i \"${installer.absolutePath}\" /passive /norestart")
                        if (exe != null && exe.endsWith(".exe", ignoreCase = true) && !exe.contains("java", ignoreCase = true)) {
                            appendLine("start \"\" \"$exe\"")
                        }
                    },
                )
                ProcessBuilder("cmd", "/c", script.absolutePath).start()
                exitProcess(0)
            }
            DesktopOs.MAC -> {
                ProcessBuilder("open", installer.absolutePath).start()
                message = "Drag Neue Master Tool into Applications to replace this one, then reopen it"
            }
            DesktopOs.LINUX -> {
                Platform.open(installer)
                message = "Install the package, then reopen Neue Master Tool"
            }
        }
    }
}
