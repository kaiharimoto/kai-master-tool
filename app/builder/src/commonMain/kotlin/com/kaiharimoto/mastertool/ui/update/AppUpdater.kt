package com.kaiharimoto.mastertool.ui.update

import com.kaiharimoto.mastertool.core.update.Release

/** What happened when the app tried to install a downloaded build. */
sealed interface InstallOutcome {
    /** Handed to the system installer; the OS now owns the flow. */
    data object HandedToInstaller : InstallOutcome

    /**
     * Android will not install anything until this app is allowed to install
     * unknown apps. The user has been sent to the relevant settings screen.
     */
    data object NeedsInstallPermission : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

/**
 * Platform hooks for updating in place.
 *
 * Only Android can actually swap its own binary here; the desktop build is
 * distributed as an installer, so it reports [canInstallInPlace] false and the
 * UI offers the release page instead.
 */
interface AppUpdater {
    val currentVersionName: String

    val canInstallInPlace: Boolean

    /**
     * Downloads the release APK and hands it to the system installer.
     * [onProgress] receives 0f..1f, or null while the size is unknown.
     */
    suspend fun downloadAndInstall(
        release: Release,
        onProgress: (Float?) -> Unit,
    ): InstallOutcome

    /** Opens the release in a browser. */
    fun openReleasePage(url: String)
}
