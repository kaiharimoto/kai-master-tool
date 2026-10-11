package com.kaiharimoto.mastertool.core.async

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Work that outlives a screen (Phase H, H.1): a job that throws is written down and said, and nothing else stops. */
class BackgroundTest {
    private val reported = ArrayList<Pair<String, Throwable>>()

    @AfterTest
    fun quiet() {
        Background.report = { _, _ -> }
    }

    @Test
    fun aJobThatThrowsIsReportedAndItsScopeAndSiblingsCarryOn() = runTest {
        Background.report = { where, e -> reported += where to e }
        Background.clock = { 42L }
        val scope = Background.scope("the banlists", StandardTestDispatcher(testScheduler))
        scope.launch { throw IllegalStateException("a pattern Android refused", IllegalArgumentException("near index 11")) }
        val sibling = scope.async { 7 }
        advanceUntilIdle()
        assertEquals(1, reported.size)
        assertEquals("the banlists", reported.single().first)
        val f = assertNotNull(Background.failed.value)
        assertEquals("the banlists", f.where)
        assertEquals(42L, f.at)
        assertTrue("IllegalStateException: a pattern Android refused" in f.what && "near index 11" in f.what, f.what)
        // The scope lives on: its other jobs finish, and a new one runs.
        assertEquals(7, sibling.await())
        assertTrue(scope.isActive)
        var ran = false
        scope.launch { ran = true }
        advanceUntilIdle()
        assertTrue(ran)
    }

    @Test
    fun aCancelledJobIsNoFailure() = runTest {
        Background.report = { where, e -> reported += where to e }
        val scope = Background.scope("sync", StandardTestDispatcher(testScheduler))
        scope.launch { throw CancellationException("stopped") }
        advanceUntilIdle()
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aReportThatThrowsDoesNotEither() = runTest {
        Background.report = { _, _ -> error("the log is full") }
        val scope = Background.scope("backups", StandardTestDispatcher(testScheduler))
        scope.launch { throw IllegalStateException("disk") }
        advanceUntilIdle()
        assertEquals("backups", Background.failed.value?.where)
        assertTrue(scope.isActive)
    }

    @Test
    fun aFailureInWordsIsOneLine() {
        val words = Background.words(IllegalStateException("line one\nline two"))
        assertTrue('\n' !in words, words)
        assertTrue(words.startsWith("IllegalStateException: line one"), words)
    }
}
