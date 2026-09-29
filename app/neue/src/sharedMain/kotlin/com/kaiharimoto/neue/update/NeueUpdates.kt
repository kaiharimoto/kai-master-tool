package com.kaiharimoto.neue.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.update.NeueUpdate
import com.kaiharimoto.mastertool.core.update.NeueUpdateChecker
import com.kaiharimoto.mastertool.core.update.NeueUpdateStatus
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.downloadDir
import com.kaiharimoto.neue.platform.handOffInstaller
import com.kaiharimoto.neue.platform.httpDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * The update dialog opened on [update] without asking GitHub: how the studio draws it
     * and the emulator walk proves its Install button is on a phone's screen (v1.3.5).
     */
    fun offer(update: NeueUpdate) {
        available = update
        dialogOpen = true
    }

    /** A made-up release with a page of notes, for [offer]. */
    fun sample(): NeueUpdate {
        val asset = com.kaiharimoto.mastertool.core.update.ReleaseAsset("kai-master-tool-9.9.9.apk", "https://example.invalid/app.apk", 40_000_000)
        val notes = (1..24).joinToString("\n") { "- Line $it of the notes, long enough to wrap on a phone held upright." }
        val release = com.kaiharimoto.mastertool.core.update.Release("9.9.9", "v9.9.9", notes, asset.url, asset.sizeBytes, "https://example.invalid", false, listOf(asset))
        return NeueUpdate("9.9.9", release, asset)
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
                withContext(Dispatchers.IO) { download(asset.url, File(downloadDir, asset.name), asset.sizeBytes) }
            }.getOrElse { error ->
                downloading = false
                message = "Download failed: ${error.message}"
                return@launch
            }
            downloading = false
            runCatching { handOffInstaller(file)?.let { message = it } }.onFailure { message = "Could not start the installer: ${it.message}" }
        }
    }

    private fun download(url: String, target: File, expected: Long): File {
        val partial = File(target.path + ".part")
        val status = httpDownload(url, partial, mapOf("Accept" to "application/octet-stream"), timeoutSeconds = 600, expected = expected) { progress = it }
        if (status !in 200..299) {
            partial.delete()
            error("GitHub returned $status")
        }
        target.delete()
        if (!partial.renameTo(target)) error("Could not write ${target.name}")
        return target
    }
}
