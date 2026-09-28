package com.kaiharimoto.mastertool.core.update

/** The three desktops Neue Master Tool is packaged for, and the installer each one takes. */
enum class DesktopOs(val extension: String) {
    WINDOWS("msi"),
    MAC("dmg"),
    LINUX("deb"),

    /**
     * Neue on a tablet (1.0.20). Never answered by [of] — Android says what it is
     * itself — and its installer is the APK, which updates on the Android track
     * (`v*`, `/releases/latest`) rather than this one.
     */
    ANDROID("apk"),
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

/**
 * The processor a Mac build is for (Phase 4). From 1.0.21 the Mac has two
 * `.dmg`s, `-arm64` and `-x64`, because a JDK is native code and a bundled
 * runtime built for one will not start on the other.
 */
enum class CpuArch(val suffix: String) {
    ARM64("arm64"),
    X64("x64"),
    ;

    companion object {
        /** From `System.getProperty("os.arch")`: `aarch64` and `arm64`, `x86_64` and `amd64`; anything else is unknown. */
        fun of(osArch: String): CpuArch? = when (osArch.lowercase()) {
            "aarch64", "arm64" -> ARM64
            "x86_64", "amd64", "x64" -> X64
            else -> null
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
    fun newest(releases: List<Release>, currentVersion: String, os: DesktopOs, arch: CpuArch? = null): NeueUpdate? =
        releases
            .mapNotNull { release -> versionOf(release.tagName)?.let { it to release } }
            .filter { (version, _) -> AppVersion.isNewer(currentVersion, version) }
            .maxWithOrNull { a, b -> AppVersion.parse(a.first).compareTo(AppVersion.parse(b.first)) }
            ?.let { (version, release) ->
                NeueUpdate(
                    versionName = version,
                    release = release,
                    installer = installerFor(release, os, arch),
                )
            }

    /**
     * This machine's installer on [release]. On a Mac with a known [arch], the
     * `.dmg` built for it; failing that one built for no processor in particular
     * (every release before 1.0.21 had exactly one, for Apple silicon); and only
     * then any `.dmg` at all, since a build under Rosetta beats none.
     */
    fun installerFor(release: Release, os: DesktopOs, arch: CpuArch? = null): ReleaseAsset? {
        val all = release.assets.filter { it.name.endsWith(".${os.extension}", ignoreCase = true) }
        if (arch == null) return all.firstOrNull()
        fun built(asset: ReleaseAsset, a: CpuArch) = asset.name.endsWith("-${a.suffix}.${os.extension}", ignoreCase = true)
        return all.firstOrNull { built(it, arch) }
            ?: all.firstOrNull { asset -> CpuArch.entries.none { built(asset, it) } }
            ?: all.firstOrNull()
    }

    /**
     * Neue on a tablet updates on the APK's track, not this one (1.0.20): the
     * newest full release (`/releases/latest`, `v1.3.0`), when it is newer than
     * [currentVersion] and carries an APK. That is the endpoint every tablet
     * already reads, and the path a classic tablet takes onto Neue.
     */
    fun apkUpdate(latest: Release?, currentVersion: String): NeueUpdate? {
        val release = latest?.takeIf { !it.isPreRelease } ?: return null
        if (!AppVersion.isNewer(currentVersion, release.versionName)) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        return NeueUpdate(versionName = release.versionName, release = release, installer = apk)
    }

    /** The file name the release workflow gives an installer. */
    fun installerName(version: String, os: DesktopOs, arch: CpuArch? = null): String =
        "neue-master-tool-$version${arch?.let { "-${it.suffix}" } ?: ""}.${os.extension}"
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
    private val arch: CpuArch? = null,
) {
    suspend fun check(): NeueUpdateStatus {
        if (os == DesktopOs.ANDROID) {
            val latest = api.latestRelease().getOrElse { error ->
                return NeueUpdateStatus.Failed(error.message ?: "Could not reach GitHub")
            }
            return NeueReleaseTrack.apkUpdate(latest, currentVersionName)
                ?.let { NeueUpdateStatus.Available(it) }
                ?: NeueUpdateStatus.UpToDate
        }
        val releases = api.releases().getOrElse { error ->
            return NeueUpdateStatus.Failed(error.message ?: "Could not reach GitHub")
        }
        return NeueReleaseTrack.newest(releases, currentVersionName, os, arch)
            ?.let { NeueUpdateStatus.Available(it) }
            ?: NeueUpdateStatus.UpToDate
    }
}
