package com.kaiharimoto.mastertool.core.cards

import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.search.TextMatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every banlist, by date (Phase B §3): read off Yugipedia's real pages, of every era, and asked by day. */
class BanlistsTest {
    private fun page(title: String, text: String, region: Format? = null): LimitationList =
        LimitationParser.read(title, text, region).let { assertNotNull(it.list, it.problem) }

    private val april2025: LimitationList by lazy {
        val text = assertNotNull(YugipediaLists.pages(BanlistFixture.APRIL_2025_TCG_JSON)?.get("April 2025 Lists (TCG)"))
        page("April 2025 Lists (TCG)", text)
    }
    private val tcg by lazy {
        listOf(
            page("July 2002 Lists", BanlistFixture.JULY_2002, Format.TCG),
            page("April 2005 Lists", BanlistFixture.APRIL_2005, Format.TCG),
            page("April 2015 Lists (TCG)", BanlistFixture.APRIL_2015_TCG),
            page("April 2020 Lists (TCG)", BanlistFixture.APRIL_2020_TCG),
            april2025,
            page("September 2026 Lists (TCG)", BanlistFixture.SEPTEMBER_2026_TCG),
        )
    }
    private val ocg by lazy {
        listOf(
            page("October 2015 Lists", BanlistFixture.OCTOBER_2015_OCG, Format.OCG),
            page("October 2025 Lists (OCG)", BanlistFixture.OCTOBER_2025_OCG, Format.OCG),
        )
    }

    @Test
    fun aListIsReadWithItsDatesAndEveryCardAtItsStatus() {
        val l = april2025
        assertEquals(Format.TCG, l.region)
        assertEquals("2025-04-07", l.start)
        assertEquals("2025-09-14", l.end)
        assertEquals("December 2024 Lists (TCG)", l.prev)
        assertEquals("September 2025 Lists (TCG)", l.next)
        // The "// prev::Unlimited" note is not part of the name.
        assertEquals(BanStatus.FORBIDDEN, l.statuses["Abyss Dweller"])
        assertEquals(BanStatus.FORBIDDEN, l.statusOf("Maxx \"C\""))
        assertEquals(BanStatus.FORBIDDEN, l.statusOf("maxx c"), "names are matched normalised")
        assertEquals(BanStatus.LIMITED, l.statusOf("Bystial Druiswurm"))
        assertEquals(BanStatus.SEMI_LIMITED, l.statusOf("Snake-Eye Ash"))
        // Off the list: named, and unlimited.
        assertEquals(BanStatus.UNLIMITED, l.statuses["Cyber Jar"])
        assertTrue(l.names("Cyber Jar"))
        // Not named at all: unlimited.
        assertEquals(BanStatus.UNLIMITED, l.statusOf("Ash Blossom & Joyous Spring"))
        assertFalse(l.names("Ash Blossom & Joyous Spring"))
        assertEquals(109, l.at(BanStatus.FORBIDDEN).size)
        assertEquals(82, l.at(BanStatus.LIMITED).size)
        assertEquals(16, l.at(BanStatus.SEMI_LIMITED).size)
        assertTrue(l.statuses.keys.none { "//" in it || it.isBlank() })
    }

    @Test
    fun everyEraReads() {
        val (y2002, y2005, y2015, y2020, _, y2026) = tcg
        assertEquals("2002-07-01" to "2002-09-30", y2002.start to y2002.end)
        assertTrue(y2002.at(BanStatus.FORBIDDEN).isEmpty(), "2002 had no Forbidden section")
        assertEquals(BanStatus.LIMITED, y2002.statusOf("Mirror Force"), "prev::Not yet released")
        assertEquals(16, y2002.statuses.size)
        assertEquals(Format.TCG, y2005.region, "the page's medium says TCG where its title does not")
        assertEquals("2005-04-01" to "2005-09-30", y2005.start to y2005.end)
        assertEquals(74, y2005.statuses.size)
        assertEquals(164, y2015.statuses.size)
        assertEquals("2020-04-01" to "2020-06-14", y2020.start to y2020.end)
        assertEquals(198, y2020.statuses.size)
        // Still in force: an empty end date.
        assertEquals("2026-09-21", y2026.start)
        assertNull(y2026.end)
        assertNull(y2026.next)
        assertEquals(235, y2026.statuses.size)

        val (o2015, o2025) = ocg
        assertEquals(Format.OCG, o2025.region)
        assertEquals("2025-10-01" to "2025-12-31", o2025.start to o2025.end)
        assertEquals(BanStatus.SEMI_LIMITED, o2025.statusOf("Maxx \"C\""))
        // "Allure of Darkness|sc": the annotation is not the name.
        assertEquals(BanStatus.SEMI_LIMITED, o2015.statuses["Allure of Darkness"])
        assertTrue(o2015.statuses.keys.none { '|' in it })
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    @Test
    fun anUnreadablePageIsReportedNeverThrown() {
        val cases = mapOf(
            "garbage" to "\u0000{{{{ not a page",
            "no template" to "This page was moved.",
            "no names" to "{{Limitation list\n| start_date = April 1, 2005\n| medium = TCG\n| forbidden =\n}}",
            "no date" to "{{Limitation list\n| medium = TCG\n| limited =\nPot of Greed\n}}",
            "no region" to "{{Limitation list\n| start_date = April 1, 2005\n| limited =\nPot of Greed\n}}",
        )
        cases.forEach { (why, text) ->
            val r = LimitationParser.read("Some page", text)
            assertNull(r.list, why)
            assertTrue(r.problem!!.startsWith("Some page"), why)
            assertNull(LimitationParser.parse("Some page", text), why)
        }
        // The region and the start can come from the title, or the category asked.
        val lean = "{{Limitation list\n| limited =\n* [[Pot of Greed]] <!-- a note -->\n[[Raigeki|the bolt]]<ref name=\"x\" />\n}}"
        val fromTitle = assertNotNull(LimitationParser.parse("March 2009 Lists (TCG)", lean))
        assertEquals(Format.TCG to "2009-03-01", fromTitle.region to fromTitle.start)
        assertEquals(setOf("Pot of Greed", "Raigeki"), fromTitle.statuses.keys)
        assertEquals(Format.OCG, LimitationParser.parse("March 2009 Lists", lean, Format.OCG)!!.region)
    }

    @Test
    fun datesAreReadAsAWikiWritesThem() {
        assertEquals("2025-04-07", LimitationParser.date("April 7, 2025"))
        assertEquals("2025-04-07", LimitationParser.date("7 April 2025"))
        assertEquals("2025-04-07", LimitationParser.date("2025-04-07"))
        assertEquals("2004-09-01", LimitationParser.date("Sept. 1st, 2004"))
        assertEquals("2025-04-07", LimitationParser.date("April 7, 2025<ref>{{cite web | url = x}}</ref>"))
        assertEquals("2005-04-01", LimitationParser.date("April 2005"))
        assertNull(LimitationParser.date(""))
        assertNull(LimitationParser.date("soon"))
        assertEquals("2025-04-01", LimitationParser.fromTitle("April 2025 Lists (TCG)"))
    }

    @Test
    fun theCategoriesAndABatchOfPagesAreRead() {
        val tcgPages = assertNotNull(YugipediaLists.members(BanlistFixture.TCG_CATEGORY))
        assertEquals(82, tcgPages.titles.size)
        assertTrue("April 2025 Lists (TCG)" in tcgPages.titles && "April 2005 Lists" in tcgPages.titles)
        assertNull(tcgPages.continueFrom)
        val ocgPages = assertNotNull(YugipediaLists.members(BanlistFixture.OCG_CATEGORY))
        assertEquals(88, ocgPages.titles.size, "the subcategories are left out")
        assertTrue(ocgPages.titles.none { it.startsWith("Category:") })

        val batch = assertNotNull(YugipediaLists.pages(BanlistFixture.BATCH))
        assertEquals(setOf("July 2002 Lists", "May 2002 Lists (TCG)", "No Such Month 1999 Lists"), batch.keys)
        assertNull(batch["No Such Month 1999 Lists"], "a missing page has no text")
        assertEquals(Format.TCG, LimitationParser.parse("July 2002 Lists", batch["July 2002 Lists"]!!)!!.region)
        assertEquals("2002-05-07", LimitationParser.parse("May 2002 Lists (TCG)", batch["May 2002 Lists (TCG)"]!!)!!.start)

        assertNull(YugipediaLists.members("""{"error":{"code":"x"}}"""))
        assertNull(YugipediaLists.members("<html>"))
        assertNull(YugipediaLists.pages("not json"))
        // The newer JSON shape reads too.
        assertEquals(mapOf("A" to "text"), YugipediaLists.pages("""{"query":{"pages":[{"title":"A","revisions":[{"slots":{"main":{"content":"text"}}}]}]}}"""))

        val url = YugipediaLists.categoryUrl(Format.TCG)
        assertTrue("cmtitle=Category:TCG_Advanced_Format_Forbidden_%26_Limited_Lists" in url, url)
        assertTrue("titles=April_2025_Lists_(TCG)%7CJuly_2002_Lists&" in YugipediaLists.pagesUrl(listOf("April 2025 Lists (TCG)", "July 2002 Lists")))
    }

    @Test
    fun theListInForceOnADay() {
        val h = BanlistHistory(Format.TCG, tcg + ocg)
        assertEquals(6, h.lists.size, "the OCG's lists are not the TCG's")
        assertNull(h.asOf("2001-12-31"), "before the first list")
        assertEquals("July 2002 Lists", h.asOf("2002-07-01")!!.title)
        assertEquals("April 2005 Lists", h.asOf("2014-01-01")!!.title, "the latest kept that started by then")
        assertEquals("April 2025 Lists (TCG)", h.asOf("2025-04-07")!!.title)
        assertEquals("April 2025 Lists (TCG)", h.asOf("2025-05-01")!!.title)
        assertEquals("September 2026 Lists (TCG)", h.asOf("2026-10-04")!!.title)
        assertEquals("September 2026 Lists (TCG)", h.latest!!.title)
        assertEquals(BanStatus.FORBIDDEN, h.statusOf("Maxx \"C\"", "2025-05-01"))
        assertEquals(BanStatus.UNLIMITED, h.statusOf("Maxx \"C\"", "2015-05-01"))
        assertNull(h.statusOf("Pot of Greed", "1999-01-01"))
        assertEquals(BanStatus.SEMI_LIMITED, BanlistHistory(Format.OCG, tcg + ocg).statusOf("Maxx \"C\"", "2025-11-01"))
        // A page read twice is kept once.
        assertEquals(6, BanlistHistory(Format.TCG, tcg + tcg).lists.size)
    }

    @Test
    fun whatMovedBetweenTwoLists() {
        val h = BanlistHistory(Format.TCG, tcg)
        val c = assertNotNull(h.changes("2025-05-01", "2026-10-01"))
        assertEquals("April 2025 Lists (TCG)", c.from.title)
        assertEquals("September 2026 Lists (TCG)", c.to.title)
        assertEquals(78, c.changes.size)
        c.changes.forEach { ch ->
            assertEquals(c.from.statusOf(ch.name), ch.before, ch.name)
            assertEquals(c.to.statusOf(ch.name), ch.after, ch.name)
        }
        assertTrue(BanChange("Archnemeses Protos", BanStatus.LIMITED, BanStatus.FORBIDDEN) in c.changes)
        assertTrue(BanChange("Black Dragon Collapserpent", BanStatus.SEMI_LIMITED, BanStatus.UNLIMITED) in c.changes)
        assertTrue(c.changes.none { it.name == "Pot of Greed" }, "unchanged is not a change")
        // Most restricted first.
        assertEquals(c.changes.sortedBy { it.after.maxCopies }.map { it.after }, c.changes.map { it.after })
        assertNull(h.changes("1990-01-01", "2025-01-01"))
        assertTrue(BanlistWords.changes(c).startsWith("From April 2025 Lists (TCG) (7 Apr 2025) to September 2026 Lists (TCG) (21 Sep 2026): 78 cards moved."))
        assertEquals(0, BanlistHistory.changes(c.from, c.from).changes.size)
    }

    @Test
    fun aCardsHistoryIsItsStretchesAtEachStatus() {
        val h = BanlistHistory(Format.TCG, tcg)
        val spells = h.historyOf("Card Destruction")
        assertEquals(
            listOf(
                BanSpell(BanStatus.SEMI_LIMITED, "2002-07-01", "2005-04-01", listOf("July 2002 Lists")),
                BanSpell(BanStatus.LIMITED, "2005-04-01", "2015-04-01", listOf("April 2005 Lists")),
                BanSpell(BanStatus.FORBIDDEN, "2015-04-01", "2020-04-01", listOf("April 2015 Lists (TCG)")),
                BanSpell(BanStatus.LIMITED, "2020-04-01", null, listOf("April 2020 Lists (TCG)", "April 2025 Lists (TCG)", "September 2026 Lists (TCG)")),
            ),
            spells,
        )
        // From the first list that names it.
        assertEquals("2020-04-01", h.historyOf("Maxx \"C\"").first().from)
        assertTrue(h.historyOf("Ash Blossom & Joyous Spring").isEmpty())
        assertTrue(BanlistWords.history("Ash Blossom & Joyous Spring", emptyList()).contains("Unlimited throughout"))
        assertTrue(BanlistWords.history("Card Destruction", spells).contains("- Forbidden from 1 Apr 2015 until 1 Apr 2020 (April 2015 Lists (TCG))"))
    }

    // ---- matched to the pool, and the validator --------------------------------------------------------------

    private val maxx = Card(CardId(23434538), "Maxx \"C\"", "Effect Monster", "effect", alternateIds = listOf(CardId(23434539)))
    private val druiswurm = Card(CardId(6637331), "Bystial Druiswurm", "Effect Monster", "effect", tcgBanStatus = BanStatus.UNLIMITED)
    private val fillers = (1..13).map { Card(CardId(it), "Filler $it", "Normal Monster", "normal") }
    private val filler = fillers.first()
    private val pool = listOf(maxx, druiswurm) + fillers

    /** [n] cards of filler, three of each. */
    private fun fill(n: Int): List<CardId> = (0 until n).map { fillers[it / 3].id }
    private val byName = pool.associateBy { TextMatching.normalize(it.name) }
    private val lookup: (String) -> Card? = { byName[TextMatching.normalize(it)] }
    private val byId: (CardId) -> Card? = { id -> pool.firstOrNull { id in it.passcodes } }

    @Test
    fun aListMatchedToThePoolKeepsWhatItCouldNotMatch() {
        val m = april2025.match(lookup)
        assertEquals(BanStatus.FORBIDDEN, m.byCard[maxx.id])
        assertEquals(BanStatus.LIMITED, m.statusOf(druiswurm))
        assertEquals(april2025.statuses.size - 2, m.unmatched.size, "every name the pool lacks is reported")
        assertTrue(m.unmatched.none { it.startsWith("Filler") })
        assertTrue("Abyss Dweller" in m.unmatched)
        assertEquals(BanStatus.UNLIMITED, m.statusOf(filler))
        // An alternate artwork is the same card.
        assertEquals(BanStatus.FORBIDDEN, m.statusOf(maxx.copy(id = CardId(23434539))))
        assertTrue(BanlistWords.body(april2025, m.unmatched).contains("Not matched to a card in the app's pool (210)"))
    }

    @Test
    fun theValidatorChecksADeckAgainstADatedList() {
        val deck = Deck(main = fill(39) + maxx.id, side = listOf(druiswurm.id, druiswurm.id))
        // Today's pool says nothing against either.
        assertTrue(DeckValidator.validate(deck, byId, Format.TCG).errors.isEmpty())
        val dated = DeckValidator.validate(deck, byId, Format.TCG, limits = april2025.match(lookup))
        val words = dated.errors.map { it.message }
        assertTrue("Maxx \"C\" is Forbidden on the April 2025 Lists (TCG), deck has 1." in words, words.toString())
        assertTrue("Bystial Druiswurm is limited to 1 on the April 2025 Lists (TCG), deck has 2." in words, words.toString())
        // Copies are counted by card: one of each printing is two.
        val semi = LimitationList(Format.TCG, "Test", "2025-01-01", statuses = mapOf("Maxx \"C\"" to BanStatus.LIMITED))
        val twoPrints = Deck(main = fill(38) + maxx.id + CardId(23434539))
        assertEquals(1, DeckValidator.validate(twoPrints, byId, Format.TCG, limits = semi.match(lookup)).errors.size)
        val today = DeckValidator.validate(twoPrints, byId, Format.TCG, limits = BanSource.current(Format.TCG)).errors
        assertTrue(today.isEmpty(), today.toString())
    }

    // ---- the stored document -----------------------------------------------------------------------------------

    @Test
    fun theStoredDocumentRoundTripsAndReadsForgivingly() {
        val doc = BanlistDoc(region = Format.TCG, lists = tcg, unreadable = mapOf("X Lists" to "why"), fetched = mapOf("July 2002 Lists" to 5L), checked = 9L)
        val back = assertNotNull(BanlistCodec.decode(BanlistCodec.encode(doc)))
        assertEquals(doc, back)
        assertEquals(tcg.map { it.statuses }, back.lists.map { it.statuses }, "statuses keep the page's order")
        assertNull(BanlistCodec.decode("{ not json"))
        val future = """{"version":7,"region":"OCG","somethingNew":[1],"lists":[{"region":"OCG","title":"T","start":"2025-01-01","statuses":{"A":"LIMITED","B":"FORBIDDEN"},"later":true}]}"""
        val read = assertNotNull(BanlistCodec.decode(future))
        assertEquals(7, read.version)
        assertEquals(BanStatus.LIMITED, read.lists.single().statusOf("A"))
        assertEquals("ocg.json", BanlistCodec.file(Format.OCG))
    }

    @Test
    fun aRefreshAsksOnlyForWhatItLacksAndTheListStillInForce() {
        val week = BanlistPlan.WEEK_MS
        val now = 100 * week
        assertTrue(BanlistPlan.stale(null, now))
        val doc = BanlistDoc(
            region = Format.TCG,
            lists = tcg,
            fetched = tcg.associate { it.title to now - week / 2 },
            checked = now - week / 2,
        )
        assertFalse(BanlistPlan.stale(doc, now))
        assertTrue(BanlistPlan.stale(doc, now + week))
        val titles = tcg.map { it.title } + "New Lists (TCG)"
        assertEquals(listOf("New Lists (TCG)"), BanlistPlan.pages(doc, titles, "2026-10-04", now))
        // A week on, the one in force is read again; ended lists never are.
        assertEquals(listOf("September 2026 Lists (TCG)", "New Lists (TCG)"), BanlistPlan.pages(doc, titles, "2026-10-04", now + week))

        val merged = BanlistPlan.merge(doc, Format.TCG, titles - "July 2002 Lists", read = listOf(april2025), failed = mapOf("New Lists (TCG)" to "names no cards"), now = now + week)
        assertEquals(5, merged.lists.size, "a page gone from the category is let go")
        assertEquals(now + week, merged.fetched["April 2025 Lists (TCG)"])
        assertEquals(now + week, merged.checked)
        assertEquals(mapOf("New Lists (TCG)" to "names no cards"), merged.unreadable)
        // Read at last, it leaves the unreadable.
        val later = BanlistPlan.merge(merged, Format.TCG, null, read = listOf(april2025.copy(title = "New Lists (TCG)")), failed = emptyMap(), now = now + 2 * week)
        assertTrue(later.unreadable.isEmpty())
        assertEquals(now + week, later.checked, "a refresh without the category keeps when it was last read")
    }

    @Test
    fun theWordsNameTheListAndItsDays() {
        assertEquals("April 2025 Lists (TCG) — the TCG Forbidden & Limited list, in force 7 Apr 2025 – 14 Sep 2025", BanlistWords.header(april2025))
        assertTrue(BanlistWords.header(tcg.last()).endsWith("in force since 21 Sep 2026"))
        val body = BanlistWords.body(april2025)
        assertTrue(body.startsWith("Forbidden (109): Abyss Dweller, "), body.take(80))
        assertTrue("Unlimited now (came off the list) (5): Cyber Jar" in body)
    }

    @Test
    fun aPagesGapBetweenTwoEqualListsIsFilledAndSaidToBeInferred() {
        // Yugipedia's "January 2016 Lists" (OCG) leaves out Pot of Greed, Forbidden on the lists either side.
        val before = LimitationList(Format.OCG, "October 2015 Lists", "2015-10-01", statuses = mapOf("Pot of Greed" to BanStatus.FORBIDDEN, "Raigeki" to BanStatus.LIMITED))
        val gap = LimitationList(Format.OCG, "January 2016 Lists", "2016-01-01", statuses = mapOf("Raigeki" to BanStatus.UNLIMITED))
        val after = LimitationList(Format.OCG, "April 2016 Lists (OCG)", "2016-04-01", statuses = mapOf("Pot of Greed" to BanStatus.FORBIDDEN))
        val h = BanlistHistory(Format.OCG, listOf(after, gap, before))
        assertEquals(BanStatus.FORBIDDEN, h.statusOf("Pot of Greed", "2016-02-01"))
        assertEquals(setOf("Pot of Greed"), h.asOf("2016-02-01")!!.inferred)
        // A card the page names is never overridden: Raigeki came off, said so, and stays off.
        assertEquals(BanStatus.UNLIMITED, h.statusOf("Raigeki", "2016-02-01"))
        assertTrue("(inferred): Pot of Greed" in BanlistWords.body(h.asOf("2016-02-01")!!))
        // The newest list has nothing after it, and a gap with a different status after is a real change.
        assertEquals(emptySet(), h.latest!!.inferred)
        val moved = BanlistHistory(Format.OCG, listOf(before, gap, after.copy(statuses = mapOf("Pot of Greed" to BanStatus.LIMITED))))
        assertEquals(BanStatus.UNLIMITED, moved.statusOf("Pot of Greed", "2016-02-01"))
    }
}
