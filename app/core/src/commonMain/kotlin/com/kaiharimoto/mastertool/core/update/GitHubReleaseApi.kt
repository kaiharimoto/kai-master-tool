package com.kaiharimoto.mastertool.core.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A published release and the APK attached to it. */
data class Release(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val apkUrl: String?,
    val apkSizeBytes: Long?,
    val htmlUrl: String,
    val isPreRelease: Boolean,
) {
    val hasApk: Boolean get() = apkUrl != null
}

/**
 * Reads the repository's releases from the public GitHub API.
 *
 * Unauthenticated, because the repository is public and the alternative is
 * shipping a token in the APK. That caps requests at 60/hour per IP, which is
 * far above what a manual "check for updates" button will ever use.
 */
class GitHubReleaseApi(
    private val client: HttpClient,
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    suspend fun latestRelease(): Result<Release?> = runCatching {
        val response: HttpResponse = client.get("$baseUrl/repos/$owner/$repo/releases/latest") {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }

        // 404 is the normal answer before the first release is published.
        if (response.status.value == 404) return@runCatching null
        if (!response.status.isSuccess()) {
            error("GitHub returned ${response.status}")
        }

        response.body<ReleaseDto>().toDomain()
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<AssetDto> = emptyList(),
    ) {
        fun toDomain(): Release {
            val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            return Release(
                // Prefer the tag: release names are free text and often blank.
                versionName = tagName.removePrefix("v").removePrefix("V"),
                tagName = tagName,
                notes = body.orEmpty().trim(),
                apkUrl = apk?.browserDownloadUrl,
                apkSizeBytes = apk?.size,
                htmlUrl = htmlUrl,
                isPreRelease = prerelease,
            )
        }
    }

    @Serializable
    private data class AssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    companion object {
        const val DEFAULT_OWNER = "kaiharimoto"

        /**
         * The repository the installed app asks for its updates.
         *
         * It was `kaihari-s-master-tool` until the rename. GitHub redirects the
         * API for a renamed repository, so shipped builds kept updating - but a
         * freed-up name is one anybody can register, and this is the endpoint
         * that hands a device an APK to install. It points at the real name.
         */
        const val DEFAULT_REPO = "kai-master-tool"
        const val DEFAULT_BASE_URL = "https://api.github.com"
    }
}
