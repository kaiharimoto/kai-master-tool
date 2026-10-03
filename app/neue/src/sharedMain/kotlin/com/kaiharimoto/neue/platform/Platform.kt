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

    /**
     * Where a deck's QR code can be read from here (v1.3.7): a phone's or a
     * tablet's camera, where it has one, and a picture of a code. The desk reads
     * none — it shows its decks' codes for those to scan.
     */
    val scanSources: Set<QrSource>

    /** The text of a QR code, read by the camera or found in a picture the person picks. */
    suspend fun scanQr(from: QrSource): QrScan

    /** Whether a photo can be taken here (1.0.55): a phone's or a tablet's camera, for Ai to see. */
    val canTakePhoto: Boolean

    /** A photo taken with the device's camera app, upright, or null when there is none. */
    suspend fun takePhoto(): PickedFile?

    /**
     * Ai is at work, or has stopped (1.0.61, kai: "I want to be able to [switch apps] without the
     * conversation cutting off"). On Android a foreground service keeps the process and its
     * connection alive while the app is out of sight, [line] its notification; the desk needs none.
     */
    fun working(on: Boolean, title: String, line: String)

    /**
     * The same keeping-alive for anything else that must not be frozen out of sight, by [key] (1.0.87: a sign-in
     * waiting on the browser — Android froze the listener, and the browser loaded for ever). The service stays while
     * any key holds it; the desk needs none.
     */
    fun keepAwake(key: String, on: Boolean, title: String, line: String)

    /** Ai finished while the app was out of sight: a notification says so (Android); nothing on the desk. */
    fun answered(title: String, line: String)
}

/** Where a QR code is read from: the camera, or a picture (a screenshot someone sent). */
enum class QrSource { CAMERA, PICTURE }

/** What a scan came back with. */
sealed interface QrScan {
    /**
     * What was read: the camera's one code (a split deck's parts already joined,
     * 1.0.32), or every code a picture holds.
     */
    data class Read(val texts: List<String>) : QrScan

    /** Put away without a code: says nothing. */
    data object Cancelled : QrScan

    /** A picture with no code in it that could be read. */
    data object NotFound : QrScan

    /** The camera was refused, or could not be opened. */
    data object NoCamera : QrScan
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
