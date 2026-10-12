package com.kaiharimoto.mastertool.core.async

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A thread of the app's (the red team, 2026-10): what it throws is written down and said, never handed to the default handler
 * — which writes a crash and closes the app. The microphone's write on a full disk did.
 */
class BackgroundThreadTest {
    private val reported = Collections.synchronizedList(ArrayList<Pair<String, Throwable>>())

    @AfterTest
    fun quiet() {
        Background.report = { _, _ -> }
    }

    @Test
    fun aThreadThatThrowsIsReportedAndNeverReachesTheDefaultHandler() {
        Background.report = { where, e -> reported += where to e }
        val reachedDefault = Collections.synchronizedList(ArrayList<Throwable>())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> reachedDefault += e }
        try {
            val t = Background.thread("the microphone") { throw IOException("No space left on device") }
            t.join(5_000)
            assertTrue(t.isDaemon)
            assertEquals("the microphone", t.name)
            assertEquals(listOf("the microphone"), reported.map { it.first })
            assertTrue("No space left on device" in Background.failed.value!!.what)
            // An interrupt is the thread asked to stop: nothing to say.
            val stop = Background.thread("a reader") { Thread.sleep(60_000) }
            stop.interrupt()
            stop.join(5_000)
            assertEquals(1, reported.size)
            // An executor's threads: a task's throw is said, the pool carries on.
            val pool = Executors.newSingleThreadExecutor(Background.threads("a take's render"))
            pool.execute { throw IllegalStateException("render") }
            val ran = CountDownLatch(1)
            pool.execute { ran.countDown() }
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
            // The worker that threw says so as it ends, which may be after the pool has moved on.
            val deadline = System.currentTimeMillis() + 5_000
            while (reported.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf("the microphone", "a take's render"), reported.map { it.first })
            assertTrue(reachedDefault.isEmpty(), reachedDefault.toString())
            // Unstarted, for a shutdown hook.
            assertEquals(Thread.State.NEW, Background.thread("the closing line", start = false) { }.state)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
