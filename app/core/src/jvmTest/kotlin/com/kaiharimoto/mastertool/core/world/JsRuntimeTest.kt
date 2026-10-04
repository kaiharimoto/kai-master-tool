package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
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
}
