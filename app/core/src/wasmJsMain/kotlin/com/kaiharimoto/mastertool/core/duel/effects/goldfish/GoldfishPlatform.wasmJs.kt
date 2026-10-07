package com.kaiharimoto.mastertool.core.duel.effects.goldfish

/** A browser page has one thread. */
internal actual fun goldfishCores(): Int = 1

/**
 * A browser cannot block: nothing there may wait for a coroutine. The goldfish runs on the host's computer (the
 * Lounge's guest page never studies hands), so this is never reached in the browser; it says so if it is.
 */
internal actual fun <T> goldfishBlocking(block: suspend () -> T): T =
    throw UnsupportedOperationException("The goldfish runs on the host, not in the browser")
