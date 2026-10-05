package com.kaiharimoto.mastertool.core.present.record

import kotlinx.serialization.Serializable

/**
 * Where takes live (1.1.13): `<data>/present/<presentation>/takes/<take>/`, each folder holding [Take.FILE], the
 * frozen [Take.PRESENTATION], the camera's [Take.CAMERA], the microphone's [Take.AUDIO] and the videos rendered from
 * it. **A take is this device's own**: a minute of camera is about 60 MB, so takes are never synced and never backed
 * up — [syncs] is what sync and backups ask of every path under `present/`, and it says no to anything in a
 * `takes` folder.
 */
object TakePaths {
    const val FOLDER = "takes"

    /** A take's folder under `present/`, as a relative path. */
    fun folder(presentationId: String, takeId: String): String = "$presentationId/$FOLDER/$takeId"

    /**
     * Whether [rel] — a path relative to `<data>/present/` — travels with sync and backups: the presentations and
     * their pictures do, takes never.
     */
    fun syncs(rel: String): Boolean {
        val parts = rel.replace('\\', '/').trim('/').split('/')
        return !(parts.size >= 2 && parts[1] == FOLDER)
    }

    /** A take's id: `t` and the time it began, so a folder listing sorts by age. */
    fun id(now: Long, salt: Int = 0): String = "t$now" + if (salt > 0) "-$salt" else ""

    /** A file name made of [name] that every system takes, with [extension]. */
    fun fileName(name: String, extension: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifBlank { "Take" }
        return "${clean.take(80)}.$extension"
    }
}

object TakeNames {
    /** The next take's name: "Take 1", then one past the highest number already used. */
    fun next(existing: List<String>): String {
        val n = existing.mapNotNull { Regex("^Take (\\d+)$").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "Take ${n + 1}"
    }

    /** `m:ss` under an hour, `h:mm:ss` from one: a take's length. */
    fun length(ms: Long): String = Chapters.stamp(ms, ms >= 3_600_000L)

    /** A file's size in words: "640 KB", "61 MB", "1.2 GB". */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "${(bytes / 100_000_000L) / 10.0} GB"
        bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "${(bytes / 1_000L).coerceAtLeast(1)} KB"
    }
}

/**
 * Recording on this device (1.1.13, `NeuePreferences.record`): which camera and microphone, by the names the
 * system gives them — machines differ, so this is never synced, and Ai never sets it (it would open a camera).
 */
@Serializable
data class RecordPrefs(
    /** The camera by its name; null is the system's first. */
    val camera: String? = null,
    /** The microphone by its name; null is the system's default. */
    val microphone: String? = null,
    /** Record no sound. */
    val muted: Boolean = false,
    /** Seconds counted in before a take begins: 3, or 0 for none. */
    val countdown: Int = 3,
    /** The camera shows in its zone while presenting, recording or not. */
    val liveCamera: Boolean = true,
    /** Frames a second the video is rendered at: 30, or 60. */
    val fps: Int = 30,
    /** The camera and microphone were chosen (the start step is done). */
    val chosen: Boolean = false,
) {
    /** [fps] held to what a render makes. */
    val renderFps: Int get() = if (fps >= 60) 60 else 30

    /** [countdown] held to 0–10. */
    val countIn: Int get() = countdown.coerceIn(0, 10)
}
