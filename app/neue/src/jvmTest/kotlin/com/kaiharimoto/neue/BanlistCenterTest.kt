package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.cards.BanlistCodec
import com.kaiharimoto.mastertool.core.cards.BanlistPlan
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.neue.banlist.BanlistCenter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The banlists' holder (1.1.1): reads only what it lacks, one fetch at a time, keeps what it read, and says why when it cannot. */
class BanlistCenterTest {
    private val pages = mutableMapOf(
        "January 2025 Lists (TCG)" to page("January 1, 2025", "March 31, 2025", "Pot of Greed"),
        "April 2025 Lists (TCG)" to page("April 1, 2025", "", "Maxx \"C\""),
        "Broken Lists (TCG)" to "This page has no list on it.",
    )
    private val asked = mutableListOf<String>()
    private var offline = false

    private fun page(start: String, end: String, forbidden: String) =
        "Intro.\n{{Limitation list\n| start_date = $start\n| end_date = $end\n| medium = TCG\n| forbidden =\n$forbidden // prev::Limited\n| limited =\nRaigeki\n}}\n"

    private fun category() = buildJsonObject {
        put("batchcomplete", "")
        put("query", buildJsonObject {
            put("categorymembers", buildJsonArray {
                pages.keys.forEach { t -> add(buildJsonObject { put("ns", 0); put("title", t) }) }
            })
        })
    }.toString()

    private fun answer(url: String): String {
        if (offline) throw UnknownHostException("yugipedia.com")
        asked += url
        if ("list=categorymembers" in url) return category()
        val titles = url.substringAfter("titles=").substringBefore("&").split("%7C").map { it.replace('_', ' ') }
        return buildJsonObject {
            put("query", buildJsonObject {
                put("pages", buildJsonObject {
                    titles.forEachIndexed { i, t ->
                        put("$i", buildJsonObject {
                            put("title", t)
                            pages[t]?.let { text -> put("revisions", buildJsonArray { add(buildJsonObject { put("*", text) }) }) } ?: put("missing", "")
                        })
                    }
                })
            })
        }.toString()
    }

    private var now = 1_000L * BanlistPlan.WEEK_MS

    private fun center(dir: File) = BanlistCenter(dir, fetch = { answer(it) }, clock = { now }, today = { "2025-05-01" }, pause = 0)

    @Test
    fun theFirstAskReadsEverythingAndTheNextReadsNothing() = runBlocking {
        val dir = Files.createTempDirectory("banlists").toFile()
        val c = center(dir)
        val got = c.ensure(Format.TCG)
        assertNull(got.problem)
        val h = assertNotNull(got.history)
        assertEquals(listOf("January 2025 Lists (TCG)", "April 2025 Lists (TCG)"), h.lists.map { it.title })
        assertEquals(BanStatus.FORBIDDEN, h.statusOf("Maxx \"C\"", "2025-05-01"))
        assertEquals(2, asked.size, "the category, then one batch of pages")
        // Kept whole, the page that could not be read said with it.
        val doc = assertNotNull(BanlistCodec.decode(File(dir, "tcg.json").readText()))
        assertEquals("Broken Lists (TCG) has no {{Limitation list}} on it.", doc.unreadable["Broken Lists (TCG)"])
        assertTrue(File(dir, "tcg.json.tmp").exists().not())

        // Within the week: nothing asked, here or in a new holder reading the file.
        c.ensure(Format.TCG)
        val again = center(dir).ensure(Format.TCG)
        assertEquals(2, again.history!!.lists.size)
        assertEquals(2, asked.size)

        // A week on: the category, and only the list still in force and the page that failed.
        now += BanlistPlan.WEEK_MS
        pages["September 2025 Lists (TCG)"] = page("September 1, 2025", "", "Raigeki")
        asked.clear()
        assertNull(c.ensure(Format.TCG).problem)
        assertEquals(2, asked.size)
        assertEquals(
            listOf("April 2025 Lists (TCG)", "Broken Lists (TCG)", "September 2025 Lists (TCG)"),
            asked[1].substringAfter("titles=").substringBefore("&").split("%7C").map { it.replace('_', ' ') }.sorted(),
        )
        assertEquals(3, c.history(Format.TCG)!!.lists.size)
    }

    @Test
    fun offlineItSaysWhyAndKeepsWhatItHas() = runBlocking {
        val dir = Files.createTempDirectory("banlists").toFile()
        offline = true
        val none = center(dir).ensure(Format.TCG)
        assertNull(none.history)
        assertTrue("could not look up yugipedia.com" in none.problem!!, none.problem)

        offline = false
        val c = center(dir)
        assertNotNull(c.ensure(Format.TCG).history)
        now += BanlistPlan.WEEK_MS
        offline = true
        val stale = c.ensure(Format.TCG)
        assertEquals(2, stale.history!!.lists.size, "the lists kept stay in use")
        assertTrue("Yugipedia" in stale.problem!!)
        assertEquals(stale.problem, c.problems[Format.TCG])
    }

    @Test
    fun twoAsksAtOnceFetchOnce() = runBlocking {
        val dir = Files.createTempDirectory("banlists").toFile()
        val c = center(dir)
        val got = (1..4).map { async(kotlinx.coroutines.Dispatchers.IO) { c.ensure(Format.TCG) } }.awaitAll()
        assertTrue(got.all { it.history?.lists?.size == 2 })
        assertEquals(1, asked.count { "list=categorymembers" in it })
        assertNull(center(dir).history(Format.OCG), "nothing kept for the OCG")
    }
}
