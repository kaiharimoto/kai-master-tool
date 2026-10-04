package com.kaiharimoto.neue.world

import java.io.File

/**
 * Python for Ai World (1.0.95): on the desk a real Python, found on the PATH or where the person pointed, run as a
 * process of its own; on a phone or tablet there is none, and the World offers JavaScript alone.
 */
expect object WorldPython {
    /** Whether this platform can run Python at all. */
    val possible: Boolean

    /** The Python found at [path] (or on the PATH when blank) with its version, or null with none. */
    fun find(path: String): Found?

    /**
     * Runs Python with [args] (a script and what it is given) with [python] in [dir], each line of output to [onLine] as it comes, for at most
     * [seconds]; [stop] is asked as it runs. Blocks until it ends.
     */
    fun run(python: Found, dir: File, args: List<String>, seconds: Int, onLine: (String, Boolean) -> Unit, stop: () -> Boolean): Ended
}

data class Found(val command: List<String>, val version: String)

/** How a run ended: [code] the exit code, or null when it was stopped or timed out ([why]). */
data class Ended(val code: Int?, val why: String = "")
