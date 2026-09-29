package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.hand.Ask
import com.kaiharimoto.mastertool.core.hand.HandGoal
import com.kaiharimoto.mastertool.core.hand.HandGoals
import com.kaiharimoto.mastertool.core.model.CardId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class DeckGroupsTest {

    private val engine = DeckGroup("g-engine", "Engine", color = 0, order = 0)
    private val traps = DeckGroup("g-traps", "Handtraps", color = 3, order = 1)

    private val ash = CardId(14558127)
    private val maxx = CardId(75500286)
    private val fiend = CardId(60764609)

    private fun groups() = DeckGroups.EMPTY
        .upsert(engine)
        .upsert(traps)
        .assign(fiend, "g-engine")
        .assign(ash, "g-traps")
        .assign(maxx, "g-traps")

    @Test
    fun assignmentIsPerPasscodeAndFullyManual() {
        val g = groups()
        assertEquals("g-traps", g.assignments[ash])
        // Assigning to a group that does not exist is a no-op, not a crash.
        assertEquals(g, g.assign(ash, "g-missing"))
        // Clearing returns the card to Ungrouped.
        assertNull(g.assign(ash, null).assignments[ash])
    }

    @Test
    fun removingAGroupFreesItsCards() {
        val g = groups().remove("g-traps")
        assertNull(g.byId("g-traps"))
        assertNull(g.assignments[ash])
        assertEquals("g-engine", g.assignments[fiend])
    }

    @Test
    fun reorderRenumbersDensely() {
        val g = groups().reorder("g-traps", 0)
        assertEquals(listOf("g-traps", "g-engine"), g.ordered().map { it.id })
        assertEquals(listOf(0, 1), g.ordered().map { it.order })
    }

    @Test
    fun pruningDropsDeadAssignments() {
        val g = groups()
            .copy(assignments = groups().assignments + (CardId(999) to "g-gone"))
            .pruned(deckIds = listOf(ash, fiend))

        assertNull(g.assignments[CardId(999)])
        assertNull(g.assignments[maxx]) // not in the deck any more
        assertEquals("g-traps", g.assignments[ash])
    }

    @Test
    fun aNewGroupTakesTheLeastUsedHueAndAvoidsTheAlarmColours() {
        // Red and amber mean forbidden and warning everywhere else in the app.
        assertEquals(2, DeckGroups.EMPTY.nextColor())

        val greenTaken = DeckGroups.EMPTY.upsert(DeckGroup("g1", "Engine", color = 2, order = 0))
        assertEquals(3, greenTaken.nextColor())

        // Once every hue is spoken for it starts again rather than giving up.
        var all = DeckGroups.EMPTY
        listOf(2, 3, 4, 5, 0, 1).forEachIndexed { i, colour ->
            all = all.upsert(DeckGroup("g$i", "G$i", color = colour, order = i))
        }
        assertEquals(2, all.nextColor())
    }

    // ---- codec -------------------------------------------------------------

    @Test
    fun theFittedOrderIsSavedAndReopenedAndAbsentWhenNeverSet() {
        val fitted = listOf(CardId(3), CardId(1), CardId(2))
        val stored = StoredGroups(groups().copy(fitted = fitted), Lens.ROLES)
        assertEquals(fitted, DeckGroupsCodec.read(DeckGroupsCodec.write(null, stored)).groups.fitted)
        // Never set, never written: a deck that has not used it round-trips as before.
        val plain = DeckGroupsCodec.write(null, StoredGroups(groups(), Lens.ROLES))
        assertEquals(null, (plain?.get("groups") as? kotlinx.serialization.json.JsonObject)?.get("fitted"))
    }

    @Test
    fun codecRoundTripsThroughTheExtendedPayload() {
        val stored = StoredGroups(groups(), Lens.ROLES)
        val written = DeckGroupsCodec.write(extended = null, stored = stored)
        val back = DeckGroupsCodec.read(written)

        assertEquals(stored.lens, back.lens)
        assertEquals(stored.groups.assignments, back.groups.assignments)
        assertEquals(
            stored.groups.ordered().map { it.id to it.name },
            back.groups.ordered().map { it.id to it.name },
        )
    }

    @Test
    fun theQuestionSurvivesBeingSavedAndReopened() {
        // The whole point of storing it: a goal you have to rebuild from memory
        // every session is one you stop asking.
        val goal = HandGoal(
            id = "q1",
            name = "Opens clean",
            handSize = 6,
            asks = mapOf("g1" to Ask.AT_LEAST_1, HandGoal.UNGROUPED to Ask.AT_MOST_1),
        )
        val stored = StoredGroups(groups(), Lens.ROLES, HandGoals(listOf(goal)))

        val back = DeckGroupsCodec.read(DeckGroupsCodec.write(extended = null, stored = stored))

        assertEquals(goal, back.goals.byId("q1"))
    }

    @Test
    fun anAskThisBuildHasNeverHeardOfIsDroppedNotFatal() {
        // A deck file written by a newer version must still open.
        val future = Json.parseToJsonElement(
            """{"groups":{"goals":[{"id":"q1","name":"X","hand":5,
                "asks":{"g1":"EXACTLY_THREE","g2":"AT_LEAST_1"}}]}}"""
        ) as JsonObject

        val goal = DeckGroupsCodec.read(future).goals.byId("q1")
        assertEquals(Ask.ANY, goal?.ask("g1"))
        assertEquals(Ask.AT_LEAST_1, goal?.ask("g2"))
    }

    @Test
    fun aDeckWithNoQuestionsGainsNoGoalsKey() {
        val written = DeckGroupsCodec.write(null, StoredGroups(groups(), Lens.ROLES))!!

        assertTrue((written["groups"] as JsonObject)["goals"] == null)
    }

    @Test
    fun aDeckSavedByTheOldBreakdownFlagStillOpensOnItsGroups() {
        // v1.2 wrote a boolean, back when there was one lens and it was Roles.
        val legacy = Json.parseToJsonElement(
            """{"groups":{"defs":[{"id":"g1","name":"Engine","color":2,"order":0}],
                "cards":{"1":"g1"},"breakdown":true}}"""
        ) as JsonObject

        assertEquals(Lens.ROLES, DeckGroupsCodec.read(legacy).lens)
    }

    @Test
    fun anUnknownLensFallsBackToThePlainDeck() {
        val future = Json.parseToJsonElement("""{"groups":{"lens":"MANA_CURVE"}}""") as JsonObject

        assertEquals(Lens.DECK, DeckGroupsCodec.read(future).lens)
    }

    @Test
    fun codecPreservesEveryOtherKeyVerbatim() {
        // A payload with the legacy tool's keys in it — they must survive
        // untouched, byte for byte.
        val legacy = Json.parseToJsonElement(
            """{"version":"1.0","sidingPatterns":{"vs Snake-Eye":{"deckName":"Snake-Eye"}},
                "notes":{"cards":{},"pairs":{}}}"""
        ) as JsonObject

        val written = DeckGroupsCodec.write(legacy, StoredGroups(groups(), Lens.DECK))!!
        assertEquals(legacy["sidingPatterns"], written["sidingPatterns"])
        assertEquals(legacy["notes"], written["notes"])
        assertEquals(legacy["version"], written["version"])

        // And removing the groups again restores the original exactly.
        assertEquals(legacy, DeckGroupsCodec.write(written, StoredGroups.EMPTY))
    }

    @Test
    fun emptyGroupsOnAPlainDeckWriteNothing() {
        assertNull(DeckGroupsCodec.write(extended = null, stored = StoredGroups.EMPTY))
    }

    @Test
    fun codecShrugsOffMalformedShapes() {
        val garbage = buildJsonObject { put("groups", JsonPrimitive("not an object")) }
        assertEquals(StoredGroups.EMPTY, DeckGroupsCodec.read(garbage))
        assertEquals(StoredGroups.EMPTY, DeckGroupsCodec.read(null))
    }
}
