package com.kaiharimoto.mastertool.core.update

/** The three desktops Neue Master Tool is packaged for, and the installer each one takes. */
enum class DesktopOs(val extension: String) {
    WINDOWS("msi"),
    MAC("dmg"),
    LINUX("deb"),
    ;

    companion object {
        /** From `System.getProperty("os.name")`. Anything unrecognised is treated as Linux. */
        fun of(osName: String): DesktopOs {
            val name = osName.lowercase()
            return when {
                name.startsWith("win") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MAC
                else -> LINUX
            }
        }
    }
}

/** A newer build of Neue Master Tool, and the installer for this machine if it has one. */
data class NeueUpdate(
    val versionName: String,
    val release: Release,
    val installer: ReleaseAsset?,
)

/**
 * Neue Master Tool's release track: tags `neue-v1.2.3`, published as
 * pre-releases.
 *
 * Both halves are how it stays out of the APK's way. `/releases/latest` — the
 * endpoint every installed APK asks — skips pre-releases, and even if one got
 * through, [AppVersion.parse] reads `neue-v1.2.3` as unknown, which is never an
 * update. The 3DS track does the same thing for the same reason.
 *
 * So on this track "pre-release" is a publishing mechanism, not a statement
 * about stability, and [newest] does not skip them.
 */
object NeueReleaseTrack {
    const val TAG_PREFIX = "neue-v"

    /** `neue-v1.2.3` → `1.2.3`; anything off the track → null. */
    fun versionOf(tagName: String): String? =
        tagName.trim()
            .takeIf { it.startsWith(TAG_PREFIX) }
            ?.removePrefix(TAG_PREFIX)
            ?.takeIf { AppVersion.parse(it) != AppVersion.UNKNOWN }

    /** The newest release on the track strictly newer than [currentVersion], or null. */
    fun newest(releases: List<Release>, currentVersion: String, os: DesktopOs): NeueUpdate? =
        releases
            .mapNotNull { release -> versionOf(release.tagName)?.let { it to release } }
            .filter { (version, _) -> AppVersion.isNewer(currentVersion, version) }
            .maxWithOrNull { a, b -> AppVersion.parse(a.first).compareTo(AppVersion.parse(b.first)) }
            ?.let { (version, release) ->
                NeueUpdate(
                    versionName = version,
                    release = release,
                    installer = installerFor(release, os),
                )
            }

    fun installerFor(release: Release, os: DesktopOs): ReleaseAsset? =
        release.assets.firstOrNull { it.name.endsWith(".${os.extension}", ignoreCase = true) }

    /** The file name the release workflow gives an installer. */
    fun installerName(version: String, os: DesktopOs): String = "neue-master-tool-$version.${os.extension}"
}

/** The outcome of asking GitHub, in the words the title bar needs. */
sealed interface NeueUpdateStatus {
    data object UpToDate : NeueUpdateStatus
    data class Available(val update: NeueUpdate) : NeueUpdateStatus
    data class Failed(val message: String) : NeueUpdateStatus
}

class NeueUpdateChecker(
    private val api: GitHubReleaseApi,
    private val currentVersionName: String,
    private val os: DesktopOs,
) {
    suspend fun check(): NeueUpdateStatus {
        val releases = api.releases().getOrElse { error ->
            return NeueUpdateStatus.Failed(error.message ?: "Could not reach GitHub")
        }
        return NeueReleaseTrack.newest(releases, currentVersionName, os)
            ?.let { NeueUpdateStatus.Available(it) }
            ?: NeueUpdateStatus.UpToDate
    }
}
