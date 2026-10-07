package com.kaiharimoto.neue.browser

import com.kaiharimoto.mastertool.core.ai.course.PageElement
import java.io.File

/**
 * The browser a course study reads in (Study a course): the person's own Chrome or Edge on the desk, run by the app in a
 * profile of its own (`<data>/browser/`), where the person logs in to the course once and the study reads it as they
 * would. What it may load and press is [com.kaiharimoto.mastertool.core.ai.course.BrowseGuard]'s to decide, before it
 * is asked here: this is the hand, not the judgment.
 */
interface WebSurface : AutoCloseable {
    /** Loads [url] and waits until it has settled; where it ended up, and its title. [referrer]: the page it was embedded in. */
    suspend fun open(url: String, referrer: String? = null): Loaded

    /** Where the page is now. */
    suspend fun here(): Loaded

    /** The page as it is drawn now, scripts run: its HTML. */
    suspend fun html(): String

    /** Every link on the page, its words and its whole address, in page order. */
    suspend fun links(): List<Pair<String, String>>

    /** What can be pressed on the page now, each with a ref good until the page changes. */
    suspend fun elements(): List<PageElement>

    /** What can be pressed at a point of the last [screenshot], in its pixels; null when nothing is there. */
    suspend fun elementAt(x: Int, y: Int): PageElement?

    /** Presses the element [ref] names, as a person's click would. */
    suspend fun click(ref: Int)

    /** A screen down, or up. */
    suspend fun scroll(down: Boolean)

    /** The page as the browser shows it now, as a PNG. */
    suspend fun screenshot(): ByteArray

    /** Whether the page holds a video player. */
    suspend fun hasVideo(): Boolean

    // ---- a video chapter (Phase 2) ----------------------------------------------------

    /** The page's video, or another site's player it embeds; null when it has neither. */
    suspend fun video(): Video?

    /** Every caption cue of the page's video: seconds and words; a cue at -1 is a whole caption file. */
    suspend fun captions(): List<Pair<Double, String>>

    /** Plays the video from the start at [rate], recording its sound as it goes: "ok", or why it cannot. */
    suspend fun listen(rate: Double): String

    /** The sound recorded since last asked: pieces of one webm file, in order. */
    suspend fun takeSound(): List<ByteArray>

    /** Stops the recording and the video. */
    suspend fun stopListening()

    /** A picture of the video's box as it shows now, as a JPEG at [scale] of its size; null when there is none. */
    suspend fun videoFrame(scale: Double = 1.0): ByteArray?

    data class Video(
        /** Another site's player the video is in, by its address: the study opens it on its own. Empty when it is here. */
        val frame: String = "",
        val duration: Double = 0.0,
        val time: Double = 0.0,
        val ended: Boolean = false,
        val paused: Boolean = true,
        /** A protected video (DRM): it plays, but its sound cannot be recorded. */
        val protected: Boolean = false,
        val x: Double = 0.0,
        val y: Double = 0.0,
        val w: Double = 0.0,
        val h: Double = 0.0,
    )

    /** Whether the browser is still there (the person may close its window). */
    val alive: Boolean

    data class Loaded(val url: String, val title: String)
}

/** Where the study's browser comes from, by platform. */
expect object WebSurfaces {
    /** Why this device cannot run the study's browser, or null when it can. [custom] is the person's own browser path. */
    fun missing(custom: String): String?

    /**
     * Opens the browser on [start] with its profile in [profile]; [visible] shows its window (the person logs in there,
     * and can watch the study read).
     */
    suspend fun launch(profile: File, start: String, custom: String, visible: Boolean = true): WebSurface
}
