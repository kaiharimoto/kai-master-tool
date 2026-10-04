package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.ai.meta.DeckAnalysis
import com.kaiharimoto.mastertool.core.ai.meta.FieldBuilder
import com.kaiharimoto.mastertool.core.ai.meta.FieldLegality
import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.HttpClientFactory
import com.kaiharimoto.mastertool.core.remote.RecentDecks
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import com.kaiharimoto.mastertool.core.remote.TournamentDecks
import com.kaiharimoto.mastertool.core.remote.YgoProDeckDecks
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaTest {
    /** YGOPRODeck's answer, as captured, trimmed to what the app reads. */
    private val captured = """
        [{"username":"milliezone","deck_name":"Branded Light and Darkness Ritual","deck_excerpt":"Branded Light and Darkness Ritual  / Foggia WCQ Regional Top 4",
          "main_deck":"[\"70405001\",\"44001993\",\"98684220\",\"98684220\"]","extra_deck":"[\"76636978\"]","side_deck":"[\"102380\",\"102380\"]",
          "deckNum":735416,"pretty_url":"branded-light-and-darkness-ritual-735416","format":"Tournament Meta Decks","submit_date":"3 days ago",
          "tournamentPlayerName":"Francesco","tournamentName":"Foggia WCQ Regional","tournamentPlayerCount":80,"tournamentPlacement":"Top 4"},
         {"deck_name":"Materiactor Phantom Knights","main_deck":"[\"1\",\"2\"]","extra_deck":"[]","side_deck":null,"deckNum":"735415",
          "format":"Tournament Meta Decks (Genesys)","submit_date":"1 week ago","tournamentName":"Greenville Genesys Regional","tournamentPlacement":"Top 8"},
         {"deck_name":"Broken","main_deck":"not a list","deckNum":1}]
    """

    @Test
    fun theCapturedAnswerIsRead() {
        val decks = TournamentDecks.parse(captured, tier = 2)
        assertEquals(2, decks.size, "a list that cannot be read is left out")
        val first = decks[0]
        assertEquals(735416, first.number)
        assertEquals(4, first.deck.main.size)
        assertEquals(listOf(CardId(102380), CardId(102380)), first.deck.side)
        assertEquals(DeckFormat.TCG, first.format)
        assertEquals(3, first.daysAgo)
        assertEquals(80, first.players)
        assertTrue(first.url.endsWith("735416"))
        assertEquals(DeckFormat.GENESYS, decks[1].format)
        assertEquals(7, decks[1].daysAgo)
    }

    @Test
    fun relativeDatesAndWeights() {
        assertEquals(0, TournamentDecks.daysAgo("8 hours ago"))
        assertEquals(14, TournamentDecks.daysAgo("2 weeks ago"))
        assertEquals(30, TournamentDecks.daysAgo("a month ago"))
        assertEquals(999, TournamentDecks.daysAgo(null))
        assertTrue(TournamentDecks.placementWeight("Winner") > TournamentDecks.placementWeight("Top 8"))
        assertTrue(TournamentDecks.sizeWeight(1000) > TournamentDecks.sizeWeight(16))
    }

    @Test
    fun theSourceIsAskedPolitelyAndItsFailureSaid() = runTest {
        var asked = 0
        val ok = YgoProDeckDecks(HttpClientFactory.create(MockEngine { asked++; respond(captured, HttpStatusCode.OK) }), clock = { 0L })
        val (decks, problems) = ok.recent(minTier = 4, days = 30, format = DeckFormat.TCG, maxPages = 1)
        assertEquals(listOf(735416), decks.map { it.number })
        assertTrue(problems.isEmpty())
        ok.page(4, 0)
        assertEquals(1, asked, "an hour's cache")
        val down = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }), clock = { 0L })
        val (none, why) = down.recent(minTier = 4, days = 30, format = null, maxPages = 1)
        assertTrue(none.isEmpty())
        assertTrue(why.single().contains("503"))
    }

    @Test
    fun anAnswerThatIsNotAListIsAnErrorNotNothing() = runTest {
        // Red team: an object (or a page of HTML) read as "no lists" ended the search as if there
        // were nothing older, and the field snapshot answered "No TCG results".
        val shape = """{"error":"Rate limited, please slow down","code":429}"""
        val thrown = assertFailsWith<IllegalStateException> { TournamentDecks.parse(shape, tier = 2) }
        assertEquals("YGOPRODeck answered in a shape the app doesn't know: $shape", thrown.message)
        assertTrue(assertFailsWith<IllegalStateException> { TournamentDecks.parse("<html> <body>Down for maintenance</body></html>", 2) }.message!!.endsWith("<html> <body>Down for maintenance</body></html>"))
        assertEquals(200 + "YGOPRODeck answered in a shape the app doesn't know: ".length, TournamentDecks.unknownShape("x".repeat(500)).length)
        assertTrue(TournamentDecks.parse("[]", tier = 2).isEmpty(), "past the last page is an empty list, and that is fine")
        val odd = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond(shape, HttpStatusCode.OK) }), clock = { 0L })
        val (decks, problems) = odd.recent(minTier = 4, days = 30, format = null, maxPages = 3)
        assertTrue(decks.isEmpty())
        assertTrue(problems.single().startsWith("Tier 4, page 1: YGOPRODeck answered in a shape the app doesn't know"), problems.toString())
        assertTrue(odd.page(4, 0).isFailure)
    }

    @Test
    fun aWindowLongerThanThePagesReadSaysSo() = runTest {
        // Every page full of fresh lists: the last page read was still inside the window.
        val fresh = (1..YgoProDeckDecks.PAGE).joinToString(",", "[", "]") { """{"deck_name":"D$it","main_deck":"[\"1\"]","deckNum":$it,"submit_date":"1 day ago"}""" }
        val busy = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond(fresh, HttpStatusCode.OK) }), clock = { 0L })
        val read = busy.recent(minTier = 3, days = 30, format = null, maxPages = 2)
        assertEquals(listOf(3, 4), read.unread, "both tiers had more in the window than two pages")
        assertTrue(read.problems.isEmpty())
        // A page that reaches past the window, or an empty one, ends the tier with nothing left unread.
        val old = """[{"deck_name":"Old","main_deck":"[\"1\"]","deckNum":9,"submit_date":"3 months ago"}]"""
        val quiet = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond(old, HttpStatusCode.OK) }), clock = { 0L })
        assertTrue(quiet.recent(minTier = 4, days = 30, format = null, maxPages = 1).unread.isEmpty())
        val empty = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond("[]", HttpStatusCode.OK) }), clock = { 0L })
        assertTrue(empty.recent(minTier = 4, days = 30, format = null, maxPages = 1).unread.isEmpty())
    }

    @Test
    fun aShareOfTopCutsIsNeverCalledAShareOfTheField() {
        // Red team: FieldBuilder weighs top cuts; the skill and expected_winrate called it the field.
        assertTrue("top cuts" in FieldBuilder.SHARE_CAVEAT && "not of the whole field" in FieldBuilder.SHARE_CAVEAT)
        assertTrue("share of top cuts" in AiTools.fieldSnapshot.description)
        assertTrue("top cuts" in AiTools.expectedWinrate.description && "over-represent" in AiTools.expectedWinrate.description)
        val webs = BuiltInSkills.all.single { it.name == "format-webs" }.body
        assertTrue("share of top cuts" in webs && "not a share" in webs, "the skill says what the share is")
        assertTrue("ask the person what their scene plays, or estimate the field separately" in webs.replace(Regex("\\s+"), " "))
        assertFalse("85% of the field" in webs)
    }

    private fun td(number: Int, name: String, main: List<Int>, placement: String = "Top 8", players: Int = 64) = TournamentDeck(
        number, name, "Event $number", placement, players, null, DeckFormat.TCG, 3, 2, Deck(main.map(::CardId), emptyList(), emptyList()), "",
    )

    @Test
    fun theFieldGroupsListsByWhatTheyPlayNotWhatTheyAreCalled() {
        val staples = listOf(900, 901, 902)
        val snake = listOf(1, 2, 3, 4, 5, 6)
        val tenpai = listOf(20, 21, 22, 23, 24, 25)
        val decks = listOf(
            td(1, "Snake-Eye", snake + staples, "Winner", 300),
            td(2, "Snake-Eye Fire King", snake.take(5) + 7 + staples, "Top 8"),
            td(3, "Snake-Eye", snake + staples, "Top 4"),
            td(4, "Tenpai Dragon", tenpai + staples, "Runner-Up"),
            td(5, "Tenpai", tenpai.drop(1) + 26 + staples, "Top 8"),
            td(6, "Rogue", listOf(40, 41, 42, 43), "Top 32", 20),
        )
        assertTrue(FieldBuilder.staples(decks).containsAll(staples.map(::CardId)))
        val field = FieldBuilder.build(decks)
        assertEquals(3, field.size)
        assertEquals("Snake-Eye", field[0].name, "named by the words its lists share")
        assertEquals(3, field[0].decks.size)
        assertEquals(1, field[0].representative.number, "the list most like the rest, the best result breaking ties")
        assertEquals("Tenpai", field[1].name)
        assertTrue(abs(field.sumOf { it.share } - 100) <= 1)
        assertTrue(field[0].share > field[1].share)
        assertTrue(CardId(1) in field[0].core)
    }

    @Test
    fun hybridsNoLongerChainTwoEnginesIntoOneStrategy() {
        // Engine A, engine B, and three hybrids between them, strongest results first: A, then the hybrids, then B.
        val a = (1..12).toList()
        val b = (101..112).toList()
        val decks = listOf(
            td(1, "A", a, "Winner"),
            td(2, "A", a.take(11) + 13, "Runner-Up"),
            td(3, "A", a.take(11) + 14, "Top 4"),
            td(4, "Hybrid", a.take(9) + b.take(3), "Top 8"),
            td(5, "Hybrid", a.take(6) + b.take(6), "Top 8"),
            td(6, "Hybrid", a.take(3) + b.take(9), "Top 8"),
            td(7, "B", b, "Top 16"),
            td(8, "B", b.take(11) + 113, "Top 16"),
            td(9, "B", b.take(11) + 114, "Top 16"),
        )
        // Joining on the most alike member chained them: A ← A-ish ← half ← B-ish ← B, one strategy of nine.
        val field = FieldBuilder.build(decks)
        assertTrue(field.size >= 2, field.map { c -> c.decks.map { it.number } }.toString())
        val home = field.map { c -> c.decks.map { it.number }.toSet() }
        fun together(x: Int, y: Int) = home.any { x in it && y in it }
        assertFalse(together(1, 7), "engine A and engine B are two strategies: $home")
        assertTrue(together(1, 2) && together(2, 3), "A's lists are one: $home")
        assertTrue(together(7, 8) && together(8, 9), "B's lists are one: $home")
        assertTrue(together(1, 4), "the A-heavy hybrid is like A as a whole: $home")
    }

    @Test
    fun theFieldCountsByCardNotByPrinting() {
        // Two lists, one playing Ash's alternate artwork: the same card, so the same list.
        val ash = TestCards.ashBlossom.copy(alternateIds = listOf(CardId(14558127), CardId(14558128)))
        val pool = listOf(ash).flatMap { c -> c.passcodes.map { it to c } }.toMap()
        val engine = listOf(1, 2, 3)
        val decks = listOf(
            td(1, "X", engine + List(3) { 14558127 }),
            td(2, "X", engine + List(3) { 14558128 }),
            td(3, "Y", listOf(50, 51, 52, 14558128)),
            td(4, "Z", listOf(60, 61, 62, 14558127)),
        )
        assertEquals(1.0, FieldBuilder.similarity(FieldBuilder.cardsOf(decks[0], pool::get), FieldBuilder.cardsOf(decks[1], pool::get), emptyMap()))
        assertTrue(FieldBuilder.similarity(FieldBuilder.cardsOf(decks[0]), FieldBuilder.cardsOf(decks[1]), emptyMap()) < 1.0, "as printed they differ")
        // Ash is in every list: by card a staple, by printing neither half is.
        assertTrue(CardId(14558127) in FieldBuilder.staples(decks, pool::get))
        assertFalse(CardId(14558127) in FieldBuilder.staples(decks))
        assertFalse(CardId(14558128) in FieldBuilder.weights(decks, pool::get), "one weight for Ash, under its own passcode")
    }

    @Test
    fun listsTheBanlistNoLongerAllowsAreDroppedAndSaid() {
        // Ash is Limited and Maxx "C" Forbidden in the fixtures; an alternate-art Ash is still Ash.
        val ash = TestCards.ashBlossom.copy(alternateIds = listOf(CardId(14558127), CardId(14558128)))
        val pool = (listOf(ash) + TestCards.all.filter { it.id != ash.id }).flatMap { c -> c.passcodes.map { it to c } }.toMap()
        val decks = listOf(
            td(1, "Legal", listOf(14558127, 1, 2, 3)),
            td(2, "Two Ash", listOf(14558127, 14558128, 1, 2)),
            td(3, "Maxx", listOf(23434538, 1, 2)),
            td(4, "Unknown cards", listOf(999_999, 999_999, 999_999, 999_999)),
        )
        val read = FieldLegality.check(decks, pool::get, Format.TCG)
        assertEquals(listOf(1, 4), read.kept.map { it.number }, "a card the pool does not know drops nothing")
        assertEquals(listOf(2, 3), read.dropped.map { it.deck.number })
        val two = read.dropped.first()
        assertEquals(2, two.copies)
        assertEquals(1, two.limit)
        val words = FieldLegality.words(read.dropped, "today's TCG list")
        assertTrue(words.startsWith("2 lists illegal under today's TCG list were left out:"), words)
        assertTrue("Maxx \"C\" (Forbidden)" in words && "more than 1 Ash Blossom & Joyous Spring (Limited)" in words, words)
        assertEquals("", FieldLegality.words(emptyList(), "x"))
        // A dated banlist plugs in as the limit: under one where Ash is unlimited, the two-Ash list is legal.
        assertEquals(listOf(1, 2, 4), FieldLegality.check(decks, pool::get, Format.TCG) { if (it.id == ash.id) 3 else it.tcgBanStatus.maxCopies }.kept.map { it.number })
    }

    @Test
    fun everyTierIsCutToTheSameWindowWhenOneStopsShort() = runTest {
        // Captured shapes: tier 3 is busy — twenty lists a page, a day apart a page — and its two pages reach back
        // only 2 days; tier 4 is quiet, one page reaching 40 days.
        fun list(n: Int, ago: String) = """{"deck_name":"D$n","main_deck":"[\"1\"]","deckNum":$n,"submit_date":"$ago"}"""
        val busy0 = (1..YgoProDeckDecks.PAGE).joinToString(",", "[", "]") { list(it, "1 day ago") }
        val busy1 = (21..40).joinToString(",", "[", "]") { list(it, "2 days ago") }
        val quiet = listOf(list(100, "1 day ago"), list(101, "5 days ago"), list(102, "2 weeks ago"), list(103, "a month ago"), list(104, "3 months ago"))
            .joinToString(",", "[", "]")
        val source = YgoProDeckDecks(
            HttpClientFactory.create(
                MockEngine { req ->
                    val url = req.url.toString()
                    val body = when {
                        "tier-3" in url && "offset=0" in url -> busy0
                        "tier-3" in url -> busy1
                        "tier-4" in url && "offset=0" in url -> quiet
                        else -> "[]"
                    }
                    respond(body, HttpStatusCode.OK)
                },
            ),
            clock = { 0L },
        )
        val read = source.recent(minTier = 3, days = 45, format = null, maxPages = 2)
        assertEquals(listOf(3), read.unread)
        assertEquals(mapOf(3 to 2), read.reached)
        // Tier 3 may hold more lists from two days ago: every tier is cut to the day before, the last 1 day.
        assertEquals(1, read.window)
        assertEquals(1, read.covers(45))
        assertTrue(read.decks.all { it.daysAgo <= 1 }, read.decks.map { it.daysAgo }.toString())
        assertEquals((1..20).toList() + 100, read.decks.map { it.number }, "tier 4's older lists are not weighed against tier 3's two days")
        val said = read.cutWords(45, "a shorter days window reads all of it")
        assertTrue("tier 3 reached back only 2 days" in said && "the last 1 day, not the last 45 asked for" in said, said)
        assertEquals("last 1 day", read.windowWords(45))
        // A window read whole is the window asked for, said as itself.
        val whole = YgoProDeckDecks(HttpClientFactory.create(MockEngine { respond(quiet, HttpStatusCode.OK) }), clock = { 0L })
            .recent(minTier = 4, days = 45, format = null, maxPages = 2)
        assertEquals(null, whole.window)
        assertEquals("last 45 days", whole.windowWords(45))
        assertEquals("", whole.cutWords(45, "x"))
        assertEquals(listOf(100, 101, 102, 103), whole.decks.map { it.number })
    }

    @Test
    fun theEventsDayIsReadFromTheDescription() {
        // As YGOPRODeck answered on 4 Oct 2026: the site's age is "1 week ago", the event's own day is in the description.
        val body = """
            [{"deck_name":"Azamina Mitsurugi","deck_description":"<p>Category: Tournament Meta Decks (TCG)</p><p>Creator: Jose Carlo Carrillo Toscano</p><p>Tournament: Mexico City WCQ Regional &ndash; September 27th 2026</p><p>Placement: Top 8</p>",
              "main_deck":"[\"9674034\"]","deckNum":736326,"format":"Tournament Meta Decks","submit_date":"1 week ago","tournamentName":"Mexico City WCQ Regional","tournamentPlacement":"Top 8"},
             {"deck_name":"Old","deck_description":"<p>Tournament: B&egrave;gles WCQ Regional &ndash; September 14th 2025</p>","main_deck":"[\"1\"]","deckNum":2,"submit_date":"1 year ago"},
             {"deck_name":"No date","deck_description":"<p>Placement: Top 8</p>","main_deck":"[\"1\"]","deckNum":3,"submit_date":"2 months ago"}]
        """
        val decks = TournamentDecks.parse(body, tier = 2)
        assertEquals("September 27th 2026", decks[0].date)
        assertEquals("2026-09-27", decks[0].day)
        assertEquals("2025-09-14", decks[1].day)
        assertEquals(null, decks[2].day)
        assertEquals("2026-02-28", TournamentDecks.eventDay("Feb. 28, 2026"))
        assertEquals("2026-03-01", TournamentDecks.eventDay("1st March 2026"))
        assertEquals(null, TournamentDecks.eventDay("February 30th 2026"), "a day that does not exist")
        // The event's day decides the age, not the site's month-wide "2 months ago".
        val today = IsoDate.epochDay("2026-10-04")!!
        assertEquals(7, RecentDecks.ageOf(decks[0], today))
        assertEquals(385, RecentDecks.ageOf(decks[1], today))
        assertEquals(60, RecentDecks.ageOf(decks[2], today), "no day read: the site's age")
    }

    /**
     * A tier of [pages] pages of twenty, newest first, two event days a page (page p: days 2p and 2p + 1 before
     * 4 Oct 2026), each list's description carrying its day and its posting said the site's rough way; tiers 3 and 4
     * have nothing. [late] lists are an old event posted late: [late] maps a list's number to its event's age.
     */
    private class History(val pages: Int = 300, val late: Map<Int, Int> = emptyMap()) {
        val today = IsoDate.epochDay("2026-10-04")!!
        var asked = 0
        private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private fun written(age: Int): String {
            val (y, m, d) = IsoDate.of(today - age).split('-')
            return "${months[m.toInt() - 1]} ${d.toInt()}th $y"
        }
        private fun posted(age: Int) = when {
            age < 7 -> "$age days ago"
            age < 30 -> "${age / 7} weeks ago"
            age < 365 -> "${age / 30} months ago"
            else -> "${age / 365} years ago"
        }
        fun page(p: Int): String = if (p >= pages) "[]" else (0 until YgoProDeckDecks.PAGE).joinToString(",", "[", "]") { i ->
            val n = p * YgoProDeckDecks.PAGE + i
            val age = 2 * p + i / 10
            val event = late[n] ?: age
            """{"deck_name":"D$n","deck_description":"<p>Tournament: Event $n &ndash; ${written(event)}</p>","main_deck":"[\"1\"]","deckNum":$n,"submit_date":"${posted(age)}"}"""
        }
        val source = YgoProDeckDecks(
            HttpClientFactory.create(
                MockEngine { req ->
                    val url = req.url.toString()
                    if ("tier-2" in url) asked++
                    val p = Regex("offset=(\\d+)").find(url)!!.groupValues[1].toInt() / YgoProDeckDecks.PAGE
                    respond(if ("tier-2" in url) page(p) else "[]", HttpStatusCode.OK)
                },
            ),
            clock = { today * YgoProDeckDecks.DAY_MS + 12 * 60 * 60 * 1000L },
        )
        fun age(d: TournamentDeck) = RecentDecks.ageOf(d, today)
    }

    @Test
    fun aPastWindowIsFoundBySearchingNotByReadingEveryPage() = runTest {
        // A year back: page 182 holds the lists of 364 and 365 days ago. Four pages from there do not reach the window's
        // start, so it is cut — and said — like any other.
        val h = History()
        val read = h.source.recent(minTier = 2, days = 30, format = null, maxPages = 4, asOf = "2025-10-04")
        assertEquals(365, read.end)
        assertEquals("2025-10-04", read.asOf)
        assertTrue(h.asked <= YgoProDeckDecks.MAX_PROBES + 5, "found by a search, not 182 pages read in turn: ${h.asked} requests")
        assertTrue(h.asked < 30, "${h.asked} requests")
        val ages = read.decks.map(h::age)
        assertEquals((365..370).toSet(), ages.toSet(), "nothing newer than the day asked; cut to the days read whole")
        assertEquals(60, read.decks.size)
        assertEquals(listOf(2), read.unread)
        assertEquals(370, read.window)
        assertEquals(5, read.covers(30))
        assertEquals("5 days to 4 Oct 2025", read.windowWords(30))
        assertEquals("2025-09-28", read.earlier(), "the day before the part read begins")
        val said = read.cutWords(30, "x")
        assertTrue("tier 2 reached back only to 28 Sep 2025" in said && "the 5 days to 4 Oct 2025, not the 30 days to 4 Oct 2025 asked for" in said, said)

        // Enough pages: the whole window, and one old event posted late inside it neither ends the reading nor counts.
        val stray = 190 * YgoProDeckDecks.PAGE + 19
        val whole = History(late = mapOf(stray to 900)).source.recent(minTier = 2, days = 30, format = null, maxPages = 20, asOf = "2025-10-04")
        assertEquals(null, whole.window)
        assertTrue(whole.unread.isEmpty())
        assertEquals("30 days to 4 Oct 2025", whole.windowWords(30))
        assertEquals(31 * 10 - 1, whole.decks.size)
        assertFalse(stray in whole.decks.map { it.number })
        assertEquals("", whole.cutWords(30, "x"))
        assertEquals("", whole.endsWords())
    }

    @Test
    fun aDayBeforeTheSiteListsAnythingIsSaidAsSuch() = runTest {
        val h = History()
        val read = h.source.recent(minTier = 2, days = 30, format = null, maxPages = 4, asOf = "2020-01-01")
        assertTrue(read.decks.isEmpty())
        assertTrue(read.problems.isEmpty(), read.problems.toString())
        assertEquals(mapOf(2 to 599), read.ends, "tier 2's oldest list is 599 days old")
        assertTrue(h.asked <= YgoProDeckDecks.MAX_PROBES, "${h.asked} requests")
        val oldest = Legality.readable(IsoDate.of(h.today - 599))
        assertTrue("tier 2's to $oldest" in read.endsWords() && "nothing in the window" in read.endsWords(), read.endsWords())
        // As of today is the last days, said as ever.
        val now = History().source.recent(minTier = 2, days = 3, format = null, maxPages = 4, asOf = "2026-10-04")
        assertEquals(null, now.asOf)
        assertEquals("last 3 days", now.windowWords(3))
        assertEquals((0..3).toSet(), now.decks.map(h::age).toSet())
    }

    @Test
    fun aPastFieldIsHeldToThatDaysListAndTheCardsOutThen() {
        val day = "2025-10-04"
        val ash = TestCards.ashBlossom.copy(formats = listOf("TCG", "OCG"), tcgDate = "2017-01-12")
        val nibiru = TestCards.nibiru.copy(formats = listOf("TCG", "OCG"), tcgDate = "2026-01-15")
        val japan = TestCards.maxxC.copy(formats = listOf("OCG"), ocgDate = "2015-01-01")
        val pool = listOf(ash, nibiru, japan).flatMap { c -> c.passcodes.map { it to c } }.toMap()
        // On that day's list Ash is Limited; today's pool says nothing of it.
        val list = object : BanSource {
            override fun statusOf(card: Card) = if (card.id == ash.id) BanStatus.LIMITED else BanStatus.UNLIMITED
            override val label = "April 2025 Lists (TCG)"
        }
        val decks = listOf(
            td(1, "Legal", listOf(ash.id.value, 1, 2)),
            td(2, "Two Ash", listOf(ash.id.value, ash.id.value, 1)),
            td(3, "From a later format", listOf(nibiru.id.value, ash.id.value, ash.id.value)),
            td(4, "OCG only", listOf(japan.id.value, 1)),
            td(5, "Unknown", listOf(999_999, 999_999, 999_999, 999_999)),
        )
        val read = FieldLegality.asOf(decks, pool::get, Format.TCG, day, list)
        assertEquals(listOf(1, 5), read.kept.map { it.number })
        assertEquals(listOf(3, 4), read.unreleased.map { it.deck.number }, "set aside first, counted once")
        assertEquals(listOf(2), read.dropped.map { it.deck.number })
        val words = FieldLegality.unreleasedWords(read.unreleased, Format.TCG, day)
        assertTrue(words.startsWith("2 lists held cards not out in the TCG on 4 Oct 2025 and were left out:"), words)
        assertTrue("1 plays ${nibiru.name} (out 15 Jan 2026)" in words && "1 plays ${japan.name} (never released in the TCG)" in words, words)
        assertEquals("", FieldLegality.unreleasedWords(emptyList(), Format.TCG, day))
        assertTrue(FieldLegality.words(read.dropped, "the April 2025 Lists (TCG)").startsWith("1 list illegal under the April 2025 Lists (TCG) was left out: 1 plays more than 1 "), FieldLegality.words(read.dropped, "x"))
        // Never more than three, whatever a source says.
        val loose = BanSource { BanStatus.UNLIMITED }
        val four = FieldLegality.asOf(listOf(decks[1], td(6, "Four", List(4) { ash.id.value })), pool::get, Format.TCG, day, loose)
        assertEquals(listOf(2), four.kept.map { it.number })
        assertEquals(3, four.dropped.single().limit)
    }

    @Test
    fun aDeckInNumbers() {
        assertEquals(1.0 - (35.0 * 34 * 33 * 32 * 31) / (40.0 * 39 * 38 * 37 * 36), DeckAnalysis.atLeastOne(5, 40, 5), 1e-9)
        assertEquals(0.0, DeckAnalysis.atLeastOne(0, 40, 5))
        val cards = TestCards.all.associateBy { it.id }
        val deck = Deck(List(3) { TestCards.ashBlossom.id } + List(37) { TestCards.nibiru.id }, emptyList(), emptyList())
        val text = DeckAnalysis.describe(deck, cards::get, Format.TCG)
        assertTrue("Main 40" in text)
        assertTrue("Not legal" in text, "three Ash at Limited is not legal: $text")
    }
}
