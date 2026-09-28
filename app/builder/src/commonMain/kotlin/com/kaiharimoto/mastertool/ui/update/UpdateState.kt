package com.kaiharimoto.mastertool.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.update.Release
import com.kaiharimoto.mastertool.core.update.UpdateChecker
import com.kaiharimoto.mastertool.core.update.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives the update check and the download.
 *
 * A check started automatically on launch stays silent unless there is
 * genuinely something to install; a check the user asked for always says
 * something back, even "you're up to date".
 */
class UpdateState(
    private val checker: UpdateChecker,
    private val updater: AppUpdater,
    private val scope: CoroutineScope,
) {
    var isChecking by mutableStateOf(false)
        private set

    /** Set when there is something to show the user in a dialog. */
    var pendingUpdate by mutableStateOf<Release?>(null)
        private set

    /** True when [pendingUpdate] has no APK and can only be opened in a browser. */
    var pendingUpdateIsManual by mutableStateOf(false)
        private set

    var downloadProgress by mutableStateOf<Float?>(null)
        private set

    var isDownloading by mutableStateOf(false)
        private set

    /** One-shot message for the snackbar. */
    var message by mutableStateOf<String?>(null)
        private set

    val currentVersionName: String get() = updater.currentVersionName

    val canInstallInPlace: Boolean get() = updater.canInstallInPlace

    fun check(userInitiated: Boolean) {
        if (isChecking || isDownloading) return

        scope.launch {
            isChecking = true
            when (val status = checker.check()) {
                is UpdateStatus.Available -> {
                    pendingUpdate = status.release
                    pendingUpdateIsManual = !updater.canInstallInPlace
                }

                is UpdateStatus.AvailableWithoutApk -> {
                    pendingUpdate = status.release
                    pendingUpdateIsManual = true
                }

                is UpdateStatus.UpToDate ->
                    if (userInitiated) {
                        message = "You're on the latest version ($currentVersionName)."
                    }

                is UpdateStatus.Failed ->
                    if (userInitiated) {
                        message = status.message
                    }
            }
            isChecking = false
        }
    }

    fun install() {
        val release = pendingUpdate ?: return
        if (!updater.canInstallInPlace || release.apkUrl == null) {
            updater.openReleasePage(release.htmlUrl)
            dismiss()
            return
        }

        scope.launch {
            isDownloading = true
            downloadProgress = null

            when (val outcome = updater.downloadAndInstall(release) { downloadProgress = it }) {
                is InstallOutcome.HandedToInstaller -> {
                    message = "Installing ${release.versionName}…"
                    dismiss()
                }

                is InstallOutcome.NeedsInstallPermission ->
                    message = "Allow this app to install unknown apps, then tap Update again."

                is InstallOutcome.Failed ->
                    message = "Update failed: ${outcome.message}"
            }

            isDownloading = false
            downloadProgress = null
        }
    }

    fun openInBrowser() {
        pendingUpdate?.let { updater.openReleasePage(it.htmlUrl) }
        dismiss()
    }

    fun dismiss() {
        if (isDownloading) return
        pendingUpdate = null
        pendingUpdateIsManual = false
    }

    fun consumeMessage() {
        message = null
    }
}
