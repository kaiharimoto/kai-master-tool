package com.kaiharimoto.mastertool.core.ai

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A conversation's saves land in order, the newest last (1.0.92). */
class LatestWritesTest {
    @Test
    fun onlyTheNewestWaitingSaveIsWritten() = runTest {
        val written = mutableListOf<Pair<String, Int?>>()
        val saves = LatestWrites<String, Int?>(this, EmptyCoroutineContext) { k, v -> written += k to v }
        saves.put("a", 1)
        saves.put("a", 2)
        saves.put("b", 1)
        saves.put("a", 3)
        assertTrue(saves.has("a"))
        assertEquals(3, saves.pending("a"))
        saves.settle()
        assertEquals(listOf<Pair<String, Int?>>("b" to 1, "a" to 3), written)
        assertFalse(saves.has("a"))
    }

    @Test
    fun aSaveMadeWhileOneIsWritingLandsAfterIt() = runTest {
        val written = mutableListOf<Int?>()
        var seen: Int? = null
        lateinit var saves: LatestWrites<String, Int?>
        saves = LatestWrites(this, EmptyCoroutineContext) { _, v ->
            // While 1 is on its way, 2 then 3 are saved: 3 must be the last on disk, 2 never written.
            if (v == 1) {
                seen = saves.pending("a")
                saves.put("a", 2)
                saves.put("a", 3)
            }
            written += v
        }
        saves.put("a", 1)
        saves.settle()
        assertEquals(listOf<Int?>(1, 3), written)
        assertEquals(1, seen, "the save on its way is the pending one")
    }

    @Test
    fun aDeletionIsAValueAndADropForgets() = runTest {
        val written = mutableListOf<Pair<String, Int?>>()
        val saves = LatestWrites<String, Int?>(this, EmptyCoroutineContext) { k, v -> written += k to v }
        saves.put("a", 1)
        saves.put("a", null)
        assertTrue(saves.has("a"))
        assertNull(saves.pending("a"))
        saves.put("b", 5)
        saves.drop("b")
        saves.settle()
        assertEquals(listOf<Pair<String, Int?>>("a" to null), written)
    }

    @Test
    fun aFailedWriteDoesNotStopTheRest() = runTest {
        val written = mutableListOf<String>()
        val saves = LatestWrites<String, Int>(this, EmptyCoroutineContext) { k, _ ->
            if (k == "bad") error("disk full")
            written += k
        }
        saves.put("bad", 1)
        saves.put("good", 2)
        saves.settle()
        assertEquals(listOf("good"), written)
    }
}
