package com.kaiharimoto.mastertool.core.update

import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class NeueReleaseTrackTest {

    private fun release(tag: String, prerelease: Boolean = true, vararg assets: String) = Release(
        versionName = tag,
        tagName = tag,
        notes = "",
        apkUrl = null,
        apkSizeBytes = null,
        htmlUrl = "https://example.test/$tag",
        isPreRelease = prerelease,
        assets = assets.map { ReleaseAsset(it, "https://example.test/$it", 10) },
    )

    @Test
    fun readsOnlyItsOwnTags() {
        assertEquals("1.2.3", NeueReleaseTrack.versionOf("neue-v1.2.3"))
        assertNull(NeueReleaseTrack.versionOf("v1.2.3"))
        assertNull(NeueReleaseTrack.versionOf("3ds-v1.0.0"))
        assertNull(NeueReleaseTrack.versionOf("neue-vbanana"))
    }

    @Test
    fun theApkUpdaterCannotMistakeANeueTagForAnUpdate() {
        // /releases/latest skips pre-releases, and this is the second fence: the
        // APK's parser reads the tag as unknown, and unknown is never newer.
        assertEquals(AppVersion.UNKNOWN, AppVersion.parse("neue-v9.9.9"))
        assertFalse(AppVersion.isNewer("1.2.3", "neue-v9.9.9"))
    }

    @Test
    fun picksTheNewestNewerReleaseWhateverTheListOrder() {
        val releases = listOf(
            release("neue-v1.0.1", assets = arrayOf("neue-master-tool-1.0.1.msi")),
            release("v1.9.0", prerelease = false, assets = arrayOf("kai-master-tool-1.9.0.apk")),
            release("neue-v1.0.10", assets = arrayOf("neue-master-tool-1.0.10.msi", "neue-master-tool-1.0.10.dmg")),
            release("neue-v1.0.2"),
        )
        val update = NeueReleaseTrack.newest(releases, "1.0.1", DesktopOs.MAC)!!
        assertEquals("1.0.10", update.versionName)
        assertEquals("neue-master-tool-1.0.10.dmg", update.installer?.name)
    }

    @Test
    fun nothingNewerIsNothing() {
        val releases = listOf(release("neue-v1.0.0"), release("v2.0.0", prerelease = false))
        assertNull(NeueReleaseTrack.newest(releases, "1.0.0", DesktopOs.WINDOWS))
    }

    @Test
    fun aReleaseWithoutThisMachinesInstallerIsStillReported() {
        // So the title bar can offer the release page rather than stay silent.
        val update = NeueReleaseTrack.newest(
            listOf(release("neue-v1.1.0", assets = arrayOf("neue-master-tool-1.1.0.deb"))),
            "1.0.0",
            DesktopOs.WINDOWS,
        )!!
        assertNull(update.installer)
    }

    @Test
    fun theOperatingSystemIsReadFromItsJavaName() {
        assertEquals(DesktopOs.WINDOWS, DesktopOs.of("Windows 11"))
        assertEquals(DesktopOs.MAC, DesktopOs.of("Mac OS X"))
        assertEquals(DesktopOs.LINUX, DesktopOs.of("Linux"))
        assertEquals("neue-master-tool-1.0.0.msi", NeueReleaseTrack.installerName("1.0.0", DesktopOs.WINDOWS))
    }

    @Test
    fun theCheckerListsReleasesAndFindsTheInstaller() = runTest {
        val body = """
            [{"tag_name":"v1.5.0","prerelease":false,"html_url":"a","assets":[{"name":"kai-master-tool-1.5.0.apk","browser_download_url":"x","size":1}]},
             {"tag_name":"neue-v1.0.3","prerelease":true,"body":"Foil.","html_url":"b",
              "assets":[{"name":"neue-master-tool-1.0.3.msi","browser_download_url":"https://example.test/n.msi","size":4096}]},
             {"tag_name":"neue-v9.0.0","draft":true,"prerelease":true,"html_url":"c","assets":[]}]
        """.trimIndent()
        val api = GitHubReleaseApi(
            HttpClientFactory.create(
                MockEngine {
                    respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
        )
        val status = NeueUpdateChecker(api, "1.0.0", DesktopOs.WINDOWS).check()
        val update = assertIs<NeueUpdateStatus.Available>(status).update
        // The draft is newer and must not be offered.
        assertEquals("1.0.3", update.versionName)
        assertEquals("https://example.test/n.msi", update.installer?.url)
        assertEquals(4096L, update.installer?.sizeBytes)
        assertEquals("Foil.", update.release.notes)
    }

    @Test
    fun anUnreachableGitHubIsAFailureNotAnUpdate() = runTest {
        val api = GitHubReleaseApi(
            HttpClientFactory.create(
                MockEngine { respond(ByteReadChannel("{}"), HttpStatusCode.InternalServerError) }
            )
        )
        assertIs<NeueUpdateStatus.Failed>(NeueUpdateChecker(api, "1.0.0", DesktopOs.LINUX).check())
    }
}
