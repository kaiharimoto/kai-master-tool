package com.kaiharimoto.mastertool.core.async

import java.util.concurrent.ThreadFactory

/**
 * A daemon thread named [name] running [block] (Phase H, H.1, for work that is a thread rather than a coroutine: a
 * microphone's loop, a process's reader, a shutdown hook): what it throws is written down and said ([Background.failure]),
 * never handed to the default handler, which writes a crash and closes the app. An interrupt is the thread asked to stop,
 * not a failure. [start] false hands it back unstarted (a shutdown hook is registered so).
 *
 * The thread's own uncaught handler says the same, so an executor's worker made by [threads] and a coroutine dispatched on
 * one report what they throw too: the coroutine machinery hands a throw no scope caught to the thread's handler.
 */
fun Background.thread(name: String, start: Boolean = true, block: () -> Unit): Thread {
    val t = Thread({
        try {
            block()
        } catch (_: InterruptedException) {
            // Asked to stop.
        } catch (e: Throwable) {
            failure(name, e)
        }
    }, name)
    t.isDaemon = true
    t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> if (e !is InterruptedException) failure(name, e) }
    if (start) t.start()
    return t
}

/** Threads for an executor (a pool, a dispatcher made of one), each a [thread] named [name]: a task's throw is said, never the app's end. */
fun Background.threads(name: String): ThreadFactory = ThreadFactory { r -> thread(name, start = false) { r.run() } }
