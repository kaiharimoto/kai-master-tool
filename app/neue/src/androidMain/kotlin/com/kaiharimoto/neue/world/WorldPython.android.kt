package com.kaiharimoto.neue.world

import java.io.File

/** A phone or tablet has no Python: the World runs JavaScript there. */
actual object WorldPython {
    actual val possible: Boolean = false

    actual fun find(path: String): Found? = null

    actual fun run(python: Found, dir: File, args: List<String>, seconds: Int, onLine: (String, Boolean) -> Unit, stop: () -> Boolean): Ended =
        Ended(null, "Python does not run on this device.")
}
