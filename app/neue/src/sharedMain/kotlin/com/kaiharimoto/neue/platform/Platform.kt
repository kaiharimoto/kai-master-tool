package com.kaiharimoto.neue.platform

import com.kaiharimoto.neue.platform.reportIssue
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.mastertool.core.update.GitHubReleaseApi
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Facts about the machine and the build, and the few things done to it from
 * outside Compose — each platform's own (1.0.20): the desktop's in `jvmMain`, a
 * tablet's in `androidMain`. What is the same everywhere (the crash file, the
 * issue report) is written once below, on top of it.
 */
expect object Platform {
    val os: DesktopOs

    /** The version the build carries: `neue/VERSION` on the desktop, the APK's own on Android. */
    val version: String

    /** Per-user data, following each platform's convention. */
    val dataDir: File

    /** The facts a bug report needs and nobody remembers to include. */
    fun systemLine(): String

    fun browse(url: String)

    fun open(file: File)

    /** Onto the system clipboard. */
    fun copy(text: String)

    /** Asks the person for a file with one of [extensions]; its name and bytes, or null. */
    suspend fun pick(title: String, extensions: Set<String>): PickedFile?

    /** Whether there is a system share sheet (touch swarm, rec 25): a tablet's, not the desk's. */
    val canShare: Boolean

    /** [text] to another app through the system's share sheet. */
    fun shareText(text: String, title: String)

    /**
     * Whether the network costs nothing to use (rec 27): the ~2 GB art library waits
     * for Wi-Fi on a tablet. The desk is always on one.
     */
    fun onUnmeteredNetwork(): Boolean
}

/** A file the person picked: its name, for the extension, and what is in it. */
class PickedFile(val name: String, val bytes: ByteArray) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

const val REPOSITORY = "https://github.com/${GitHubReleaseApi.DEFAULT_OWNER}/${GitHubReleaseApi.DEFAULT_REPO}"

val Platform.crashFile: File get() = File(dataDir, "crash.txt")

/**
 * A new GitHub issue with the version and the system already written in.
 * [detail] is a crash trace when there is one; it is cut to fit in a URL.
 */
fun Platform.reportIssue(title: String = "", detail: String? = null) {
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

fun Platform.writeCrash(error: Throwable) {
    runCatching {
        crashFile.writeText(systemLine() + "\n\n" + error.stackTraceToString())
    }
}
