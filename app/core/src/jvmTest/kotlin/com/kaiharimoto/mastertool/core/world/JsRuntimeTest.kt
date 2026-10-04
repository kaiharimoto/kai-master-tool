package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.cards.BanlistHistory
import com.kaiharimoto.mastertool.core.cards.LimitationList
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.model.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sandbox holds: no Java, no endless loop, no flood of output, and the same seed deals the same hands. */
class JsRuntimeTest {
    private val cards = listOf(
        Card(CardId(1), "Ash Blossom & Joyous Spring", "Tuner Effect Monster", "effect", "Discard this card…", atk = 0, def = 1800, level = 3),
        Card(CardId(2), "Starter", "Effect Monster", "effect", "Add 1 card."),
        Card(CardId(3), "Brick", "Normal Monster", "normal", ""),
        Card(CardId(4), "Pot of Desires", "Spell Card", "spell", "Banish 10 cards…"),
    )

    private val deck = DeckEntry(
        "d1", "Test deck",
        Deck(main = List(3) { CardId(1) } + List(12) { CardId(2) } + List(22) { CardId(3) } + List(3) { CardId(4) }),
        0, 0,
    )

    private val host = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override fun search(query: String, limit: Int) = cards.filter { query.lowercase() in it.name.lowercase() }.take(limit)
        override fun deck(id: String?) = if (id == null || id == "d1") deck else null
        override fun decks() = listOf(deck)
        override fun groups(deckId: String) = mapOf("Starters" to listOf(2))
        override fun file(path: String) = if (path == "lib/odds.js") "var lib = { twice: function (x) { return 2 * x; } }; lib;" else null
    }

    private fun run(code: String, limits: JsRuntime.Limits = JsRuntime.Limits(), stop: () -> Boolean = { false }): Pair<JsRuntime.Result, WorldApi> {
        val api = WorldApi(host)
        return JsRuntime(limits).run(code, "test.js", api, stop = stop) to api
    }

    @Test
    fun aScriptReadsTheDeckAndPrints() {
        val (r, _) = run("var d = ygo.deck(); print(d.name, d.main.length, d.groups.Starters.length); d.main.length")
        assertTrue(r.ok, r.err)
        assertEquals("Test deck 40 1\n", r.out)
        assertEquals("40", r.value)
    }

    @Test
    fun theMathsIsTheApps() {
        val (r, _) = run("ygo.atLeast(40, 12, 5, 1).toFixed(4)")
        assertTrue(r.ok, r.err)
        assertEquals("0.8506", r.value)
        val (odds, _) = run("ygo.handOdds({groups: {s: 12}, deck: 40, hand: 5, need: [{group: 's', min: 1}]}).toFixed(4)")
        assertEquals("0.8506", odds.value)
    }

    @Test
    fun aSeedDealsTheSameHandsEveryTime() {
        val code = "var d = ygo.deck(); JSON.stringify(ygo.simulate(5, 42, function (r) { return ygo.deal(d.main, r.int(1e9)).hand; }))"
        val a = run(code).first
        val b = run(code).first
        assertTrue(a.ok, a.err)
        assertEquals(a.value, b.value)
        val c = run(code.replace("42", "43")).first
        assertTrue(a.value != c.value)
    }

    @Test
    fun aMonteCarloAgreesWithTheExactOdds() {
        val (r, _) = run(
            """
            var d = ygo.deck();
            var hits = ygo.simulate(20000, 7, function (r) { return ygo.hand(d.main, r).indexOf('Starter') >= 0; });
            var rate = ygo.rate(hits);
            Math.abs(rate.p - ygo.atLeast(40, 12, 5, 1)) < 0.015
            """.trimIndent(),
        )
        assertTrue(r.ok, r.err)
        assertEquals("true", r.value)
    }

    @Test
    fun nothingOfJavaIsInReach() {
        listOf(
            "java.lang.System.exit(1)",
            "Packages.java.io.File",
            "new java.io.File('/etc/passwd')",
            "importPackage(java.io)",
            "(function(){}).constructor('return java')()",
            "this.getClass()",
            "ygo.card.getClass()",
            "Object.getPrototypeOf(ygo.card).constructor('return Packages')()",
        ).forEach { code ->
            val (r, _) = run(code)
            val reached = r.ok && r.value != null && r.value != "undefined" && !r.value!!.contains("undefined")
            assertFalse(reached && (r.value!!.contains("java") || r.value!!.contains("Package")), "$code reached ${r.value}")
            if (r.ok) assertTrue(r.value == null || r.value == "undefined" || !r.value!!.startsWith("[Java"), "$code gave ${r.value}")
        }
        assertFalse(run("typeof java !== 'undefined' || typeof Packages !== 'undefined'").first.value == "true")
    }

    @Test
    fun anEndlessLoopIsStopped() {
        val (r, _) = run("while (true) {}", JsRuntime.Limits(instructions = 5_000_000, millis = 10_000))
        assertFalse(r.ok)
        assertTrue("Stopped" in r.err, r.err)
    }

    @Test
    fun aScriptCannotCatchItsOwnStop() {
        val (r, _) = run("for (;;) { try { while (true) {} } catch (e) {} }", JsRuntime.Limits(instructions = 5_000_000))
        assertFalse(r.ok)
        assertTrue("Stopped" in r.err, r.err)
    }

    @Test
    fun theStopButtonStops() {
        var asked = 0
        val (r, _) = run("while (true) {}", stop = { ++asked > 3 })
        assertFalse(r.ok)
        assertEquals("Stopped.", r.err)
    }

    @Test
    fun aFloodOfOutputIsCut() {
        val (r, _) = run("for (var i = 0; i < 100000; i++) print('line ' + i)", JsRuntime.Limits(output = 1_000))
        assertTrue(r.ok, r.err)
        assertTrue(r.cut)
        assertTrue(r.out.length <= 1_000)
    }

    @Test
    fun oneHugeCallIsRefused() {
        val (r, _) = run("'x'.repeat(1e9).length")
        assertFalse(r.ok)
        assertTrue("5,000,000" in r.err, r.err)
    }

    @Test
    fun deepRecursionIsReportedNotFatal() {
        val (r, _) = run("function f(n) { return f(n + 1) + 1; } f(0)")
        assertFalse(r.ok)
        assertTrue(r.err.isNotBlank())
    }

    @Test
    fun anErrorSaysWhere() {
        val (r, _) = run("var a = 1;\nvar b = undefinedThing + 1;")
        assertFalse(r.ok)
        assertTrue("line 2" in r.err, r.err)
        assertTrue("test.js" in r.err, r.err)
    }

    @Test
    fun aBadShowIsAnErrorTheScriptCanCatch() {
        val (r, api) = run(
            """
            try { ygo.show.chart({type: 'pie3d'}); } catch (e) { print('caught: ' + e.message); }
            ygo.show.stat({value: '63%', label: 'Opens a starter'}, {note: 'from 20,000 hands'});
            ygo.show.graph({edges: [['Ash', 'Maxx']]}, {title: 'Who stops whom', id: 'web'});
            """.trimIndent(),
        )
        assertTrue(r.ok, r.err)
        assertTrue("caught: show(chart)" in r.out, r.out)
        assertEquals(listOf(BoardKind.STAT, BoardKind.GRAPH), api.shown.map { it.kind })
        assertEquals("web", api.shown[1].id)
        assertEquals("Who stops whom", api.shown[1].title)
        assertEquals("from 20,000 hands", api.shown[0].note)
    }

    @Test
    fun aScriptPlaysOnATableOfItsOwn() {
        val (r, _) = run(
            """
            var t = ygo.duel.start({seed: 3});
            var s = t.state();
            print(s.seats[0].hand.length, s.seats[0].deck);
            var res = t.do('draw');
            print(res.ok, t.state().seats[0].hand.length);
            """.trimIndent(),
        )
        assertTrue(r.ok, r.err)
        assertEquals("5 35\ntrue 6\n", r.out)
    }

    @Test
    fun aScriptPlaysAiAgainstItselfToTheEnd() {
        val (r, api) = run(
            """
            var t = ygo.duel.start({a: 'd1', b: 'd1', seed: 21, first: 1});
            print(t.seed, t.first, t.state().active);
            print(t.moves(1).length > 0, t.do('draw', 1).ok, t.result());
            var end = t.do('concede', 0);
            print(end.ended.winner, end.ended.kind, t.result().winner, t.do('draw', 1).ok);
            var u = ygo.duel.start({a: 'd1', b: 'd1'});
            print(u.seed !== 1 && u.seed > 0);
            """.trimIndent(),
        )
        assertTrue(r.ok, r.err)
        assertEquals("21 1 1\ntrue true null\n1 self-play 1 false\ntrue\n", r.out)
        assertEquals(1, api.finished.size)
    }

    @Test
    fun theStatisticsAreReachable() {
        val (r, _) = run("var w = ygo.stats.wilson(81, 263); [w[0].toFixed(3), ygo.stats.median([3,1,2])].join(' ')")
        assertTrue(r.ok, r.err)
        assertEquals("0.255 2", r.value)
    }

    @Test
    fun pythonDataCarriesTheDecks() {
        val data = WorldApi(host).pythonData()
        assertTrue("\"open\":\"d1\"" in data)
        assertTrue("Ash Blossom" in data)
    }

    @Test
    fun aScriptUsesTheWorldsOwnLibrary() {
        val (r, _) = run("var lib = ygo.use('lib/odds.js'); print(lib.twice(21));")
        assertTrue(r.ok, r.err)
        assertTrue("42" in r.out, r.out)
        val (missing, _) = run("ygo.use('lib/none.js');")
        assertFalse(missing.ok)
    }

    @Test
    fun hostileNumbersAreRefusedAtTheDoorNotLoopedOver() {
        val calls = listOf(
            "ygo.comb(1e15, 5e14)",
            "ygo.atLeast(1e300, 1e300, 1e300, 0)",
            "ygo.stats.binomCdf(2e9, 1e9, 0.5)",
            "ygo.handOdds({deck: 1000, hand: 60, groups: {a:60,b:60,c:60,d:60,e:60,f:60,g:60,h:60}, need: [{group:'a'},{group:'b'},{group:'c'},{group:'d'},{group:'e'},{group:'f'},{group:'g'},{group:'h'}]})",
            "ygo.comb(-1, 2.5)",
        )
        calls.forEach { c ->
            val started = System.currentTimeMillis()
            val (r, _) = run(c)
            assertFalse(r.ok, c)
            assertTrue(System.currentTimeMillis() - started < 2_000, "$c took too long")
        }
        val (fine, _) = run("print(ygo.comb(40, 5))")
        assertTrue(fine.ok && "658008" in fine.out, fine.out + fine.err)
    }

    @Test
    fun aScriptAsksForTheBanlistOnADayAndChecksADeckAgainstIt() {
        val lists = listOf(
            LimitationList(Format.TCG, "January 2025 Lists (TCG)", "2025-01-01", "2025-03-31", mapOf("Pot of Desires" to BanStatus.LIMITED)),
            LimitationList(Format.TCG, "April 2025 Lists (TCG)", "2025-04-01", null, mapOf("Ash Blossom & Joyous Spring" to BanStatus.SEMI_LIMITED, "Pot of Desires" to BanStatus.UNLIMITED, "Not In The Pool" to BanStatus.FORBIDDEN)),
        )
        val dated = object : WorldHost by host {
            override fun banlists(region: Format) = if (region == Format.TCG) BanlistHistory(Format.TCG, lists) else null
            override fun today() = "2026-10-04"
        }
        fun go(code: String): JsRuntime.Result = JsRuntime().run(code, "ban.js", WorldApi(dated))

        val l = go("var l = ygo.banlist('2025-02-01'); print(l.title, l.start, l.end, l.limited.join('|'), l.status('pot of desires'), l.status('Brick')); l.source")
        assertTrue(l.ok, l.err)
        assertEquals("January 2025 Lists (TCG) 2025-01-01 2025-03-31 Pot of Desires limited unlimited\n", l.out)
        assertTrue("Yugipedia" in l.value!!)
        // Today's, by default; what the pool could not match is said.
        val now = go("var l = ygo.banlist(); print(l.title, l.end, l.semiLimited[0], l.unmatched.join('|'), l.offList.join('|'))")
        assertEquals("April 2025 Lists (TCG) null Ash Blossom & Joyous Spring Not In The Pool Pot of Desires\n", now.out, now.err)
        assertEquals("null", go("JSON.stringify(ygo.banlist('2001-01-01'))").value, "before the first list")

        // The test deck has 3 Pot of Desires and 3 Ash (and a dozen of a starter, never legal): the Pot is over in February, the Ash in April.
        val feb = go("var r = ygo.legal(ygo.deck(), '2025-02-01'); print(r.legal, r.list); r.issues.map(function (i) { return i.message; }).join('|')")
        assertTrue(feb.ok, feb.err)
        assertEquals("false January 2025 Lists (TCG)\n", feb.out)
        assertTrue("Pot of Desires is limited to 1 on the January 2025 Lists (TCG), deck has 3." in feb.value!!, feb.value)
        assertFalse("Ash Blossom" in feb.value!!)
        val apr = go("ygo.legal('d1', '2025-05-01').issues.map(function (i) { return i.message; }).join('|')")
        assertTrue("Ash Blossom & Joyous Spring is limited to 2 on the April 2025 Lists (TCG), deck has 3." in apr.value!!, apr.value)

        // Asked wrongly, or of a region with nothing kept: the script's error, in words.
        assertTrue("yyyy-MM-dd" in go("ygo.banlist('May 2025')").err)
        assertTrue("'tcg' or 'ocg'" in go("ygo.banlist('2025-05-01', 'md')").err)
        assertTrue("no OCG banlists are kept yet" in go("ygo.banlist('2025-05-01', 'ocg')").err)
        assertTrue("no TCG list was in force then" in go("ygo.legal(null, '1999-01-01')").err)
    }
}
