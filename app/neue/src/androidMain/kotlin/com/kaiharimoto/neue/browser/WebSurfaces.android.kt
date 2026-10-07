package com.kaiharimoto.neue.browser

import java.io.File

/** The study's browser on a phone or tablet is the next phase: here the study says so and starts nothing. */
actual object WebSurfaces {
    actual fun missing(custom: String): String? = "Studying a course needs the desktop app for now: it reads the course in Chrome or Edge there."

    actual suspend fun launch(profile: File, start: String, custom: String, visible: Boolean): WebSurface =
        error(missing(custom)!!)
}
