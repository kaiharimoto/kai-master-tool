package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.WorldCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** §8.8's three apps run through scripted events: their screens and states held (`docs/world/DESKTOP.md` §12.1). */
class ExampleAppsTest {
    private val runner = JsApp()
    private val host = ExampleApps.host()

    /** An app driven the way its window drives it: init, then each event through on, the screen read after each. */
    private inner class Driven(source: String, slug: String) {
        val code = AppCode(slug, 1, source)
        var state: String = ok(runner.init(code, host))
        var seq = 0L

        fun screen(): UiTree = ok(runner.view(code, state, host))

        fun send(id: String, type: String, value: JsonElement = JsonNull): UiTree {
            state = ok(runner.on(code, state, UiEvent(id, type, value, ++seq), host))
            return screen()
        }

        fun field(name: String): JsonElement = WorldCodec.json.parseToJsonElement(state).jsonObject.getValue(name)
    }

    private fun <T> ok(c: AppCall<T>): T = when (c) {
        is AppCall.Ok -> c.value
        is AppCall.Failed -> fail(c.words)
    }

    private fun UiNode.all(): List<UiNode> = listOf(this) + when (this) {
        is UiNode.Col -> children.flatMap { it.all() }
        is UiNode.Row -> children.flatMap { it.all() }
        is UiNode.Grid -> children.flatMap { it.all() }
        is UiNode.Section -> children.flatMap { it.all() }
        else -> emptyList()
    }

    private inline fun <reified T : UiNode> UiTree.find(): List<T> = root.all().filterIsInstance<T>()

    @Test
    fun handOdds() {
        val app = Driven(ExampleApps.HAND_ODDS, "hand-odds")
        val first = app.screen()
        assertTrue(first.problems.isEmpty(), "${first.problems}")
        assertEquals("Pick a card to see its odds.", first.find<UiNode.Empty>().single().text)
        assertEquals(listOf("copies", "hand", "atLeast"), first.find<UiNode.Stepper>().map { it.id })
        app.send("deck", UiEvent.CHANGE, JsonPrimitive("d1"))
        assertEquals(40, app.field("size").jsonPrimitive.int)
        val picked = app.send("card", UiEvent.CHANGE, JsonPrimitive(14558127))
        assertEquals(3, app.field("copies").jsonPrimitive.int)
        // 1 − C(37,5)/C(40,5): a three-of in a forty-card deck, at least one in five.
        assertEquals("33.8%", picked.find<UiNode.Stat>().single().value)
        // The card counted stands beside its odds as its art (`ui.card`), by its passcode.
        assertEquals("14558127", picked.find<UiNode.Card>().single().card)
        val chart = picked.find<UiNode.Board>().single()
        assertEquals(BoardKind.CHART, chart.kind)
        val sixth = app.send("hand", UiEvent.CHANGE, JsonPrimitive(6))
        assertEquals("39.4%", sixth.find<UiNode.Stat>().single().value)
        // At least is held to the copies.
        app.send("atLeast", UiEvent.CHANGE, JsonPrimitive(3))
        app.send("copies", UiEvent.CHANGE, JsonPrimitive(2))
        assertEquals(2, app.field("atLeast").jsonPrimitive.int)
        // Six numbers, and nothing else, are its state.
        assertEquals(setOf("deck", "card", "copies", "size", "hand", "atLeast"), WorldCodec.json.parseToJsonElement(app.state).jsonObject.keys)
    }

    @Test
    fun comboLines() {
        val app = Driven(ExampleApps.COMBO_LINES, "combo-lines")
        assertEquals(1, app.screen().find<UiNode.Empty>().size)
        val loaded = app.send("deck", UiEvent.CHANGE, JsonPrimitive("d1"))
        assertTrue(loaded.problems.isEmpty(), "${loaded.problems}")
        val table = loaded.find<UiNode.Table>().single()
        assertEquals(listOf("Aluber into Fusion", "Fusion alone"), table.rows.map { it[0] })
        assertTrue(table.rows.all { it[1].endsWith("%") }, "each combo's odds of opening: ${table.rows}")
        assertEquals("Step 1 of 3", loaded.find<UiNode.Text>().first { it.text.startsWith("Step") }.text)
        val next = app.send("next", UiEvent.PRESS)
        assertEquals("Step 2 of 3", next.find<UiNode.Text>().first { it.text.startsWith("Step") }.text)
        assertEquals("summon aluber to m3\naluber search branded fusion", next.find<UiNode.Board>().single().payload)
        app.send("next", UiEvent.PRESS)
        val end = app.send("next", UiEvent.PRESS)
        assertEquals("Step 3 of 3", end.find<UiNode.Text>().first { it.text.startsWith("Step") }.text, "it stops at the last step")
        val other = app.send("pick", UiEvent.PICK, JsonPrimitive(1))
        assertEquals("Fusion alone", other.find<UiNode.Section>().single().title)
        assertEquals("Step 1 of 2", other.find<UiNode.Text>().first { it.text.startsWith("Step") }.text)
        assertEquals(1, app.field("combo").jsonPrimitive.int)
    }

    @Test
    fun matchupTracker() {
        val app = Driven(ExampleApps.MATCHUP_TRACKER, "matchup-tracker")
        assertEquals(1, app.screen().find<UiNode.Empty>().size)
        assertEquals(1, app.screen().find<UiNode.Button>().count { it.primary })
        repeat(3) { app.send("log", UiEvent.PRESS) }
        app.send("result", UiEvent.CHANGE, JsonPrimitive("lost"))
        app.send("log", UiEvent.PRESS)
        app.send("opp", UiEvent.CHANGE, JsonPrimitive("Tenpai"))
        val s = app.send("log", UiEvent.PRESS)
        assertEquals(5, app.field("games").jsonArray.size)
        val table = s.find<UiNode.Table>().single()
        // Each opponent known by its signature card: a column the app marks as cards, drawn as art.
        assertEquals(listOf(5), table.cardColumns)
        assertEquals("Snake-Eye Ash", table.rows[0][5])
        val rows = table.rows
        assertEquals(listOf("Snake-Eye", "4", "3", "75%"), rows[0].take(4))
        assertEquals(listOf("Tenpai", "1", "0", "0%"), rows[1].take(4))
        // Four games say little: the range is wide.
        val (lo, hi) = rows[0][4].removeSuffix("%").split("–").map { it.toInt() }
        assertTrue(lo < 40 && hi >= 95, "${rows[0][4]}")
        assertIs<UiNode.Board>(s.find<UiNode.Board>().single())
    }

    @Test
    fun eachIsCheckedLikeANewApp() {
        listOf(ExampleApps.HAND_ODDS, ExampleApps.COMBO_LINES, ExampleApps.MATCHUP_TRACKER).forEachIndexed { i, src ->
            val c = runner.check(AppCode("example-$i", 1, src), host)
            assertIs<AppCall.Ok<UiTree>>(c, (c as? AppCall.Failed)?.words)
            assertTrue(c.ms < AppLimits.INIT_MS + AppLimits.VIEW_MS)
        }
    }
}
