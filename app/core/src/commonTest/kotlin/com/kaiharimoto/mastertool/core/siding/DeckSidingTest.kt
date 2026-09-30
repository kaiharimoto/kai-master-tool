package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeckSidingTest {

    private val ash = CardId(14558127)
    private val nibiru = CardId(27204311)
    private val shifter = CardId(91800273)
    private val evenly = CardId(55144522)
    private val link = CardId(86066372)

    private val yubel = Matchup(
        id = "m-1",
        name = "Yubel",
        deckId = "deck-yubel",
        note = "They go second",
        first = SidePlan(out = listOf(nibiru, nibiru), into = listOf(shifter, shifter), note = "Stop Fuwalos"),
        second = SidePlan(out = listOf(ash), into = listOf(evenly)),
    )

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun sidingRoundTripsAndLeavesEveryOtherKeyAlone() {
        val before = obj("""{"groups":{"defs":[]},"notes":{"cards":{}},"future":[1,2]}""")
        val written = SidingCodec.write(before, DeckSiding(listOf(yubel)))!!
        assertEquals(before["groups"], written["groups"])
        assertEquals(before["notes"], written["notes"])
        assertEquals(before["future"], written["future"])
        assertEquals(DeckSiding(listOf(yubel)), SidingCodec.read(written))
        // Through text, as a file carries it.
        assertEquals(DeckSiding(listOf(yubel)), SidingCodec.read(obj(written.toString())))
    }

    @Test
    fun anOpponentWithNoDecklistIsANameAndThreeCardsAndSurvivesTheFile() {
        // Made on its own (1.0.42): no deck to link to yet, three cards to know it by.
        val made = Matchup("m-2", "Snake-Eye", covers = listOf(ash, nibiru, shifter))
        val back = SidingCodec.read(obj(SidingCodec.write(null, DeckSiding(listOf(made)))!!.toString()))
        assertEquals(listOf(ash, nibiru, shifter), back.matchups.single().covers)
        assertNull(back.matchups.single().deckId)
        // Linked to a decklist later, it keeps its cards and its plans.
        val linked = back.put(back.matchups.single().copy(deckId = "deck-snake"))
        assertEquals("deck-snake", SidingCodec.read(SidingCodec.write(null, linked)).matchups.single().deckId)
        // Never more than three, and a matchup without any writes none.
        val many = Matchup("m-3", "Too many", covers = listOf(ash, nibiru, shifter, evenly))
        assertEquals(3, SidingCodec.read(SidingCodec.write(null, DeckSiding(listOf(many)))).matchups.single().covers.size)
        val plain = SidingCodec.write(null, DeckSiding(listOf(yubel)))!!
        assertTrue("covers" !in plain.toString())
    }

    @Test
    fun noSidingWritesNoKeyAndNothingWritesNothing() {
        assertNull(SidingCodec.write(null, DeckSiding.EMPTY))
        val other = obj("""{"groups":{}}""")
        assertEquals(other, SidingCodec.write(other, DeckSiding.EMPTY))
        assertSame(DeckSiding.EMPTY, SidingCodec.read(null))
    }

    @Test
    fun theLegacyPatternsAreReadButNeverWritten() {
        val legacy = obj(
            """{"sidingPatterns":{
                "Fiendsmith": {"deckName":"Fiendsmith Radiant Typhoon","goingFirst":{"in":["91800273","91800273"],"out":["14558127"]},
                               "goingSecond":{"in":["55144522"],"out":["27204311"]}},
                "Old": {"out":["14558127"],"in":["91800273"]},
                "Broken": "not an object"
            }}""",
        )
        val read = SidingCodec.read(legacy)
        assertEquals(listOf("Fiendsmith Radiant Typhoon", "Old"), read.matchups.map { it.name })
        assertEquals(listOf(shifter, shifter), read.matchups[0].first.into)
        assertEquals(listOf(nibiru), read.matchups[0].second.out)
        // The oldest shape is one plan, going first.
        assertEquals(listOf(ash), read.matchups[1].first.out)
        assertTrue(read.matchups[1].second.isEmpty)
        // Written back: the new key beside the old, which is untouched.
        val written = SidingCodec.write(legacy, read)!!
        assertEquals(legacy["sidingPatterns"], written["sidingPatterns"])
        assertEquals(read, SidingCodec.read(written))
    }

    @Test
    fun aMatchupIsFoundByItsDeckAndElseByName() {
        val loose = Matchup("m-2", "Tenpai Dragon")
        val siding = DeckSiding(listOf(yubel, loose))
        assertEquals(yubel, siding.against("deck-yubel", "anything"))
        assertEquals(loose, siding.against("deck-other", " tenpai dragon "))
        // A name only finds a matchup with no deck of its own.
        assertNull(siding.against("deck-other", "Yubel"))
    }

    @Test
    fun remappingFollowsTheNewIdsAndForgetsUnknownOnes() {
        val extended = SidingCodec.write(null, DeckSiding(listOf(yubel, yubel.copy(id = "m-3", deckId = "gone"))))
        val remapped = SidingCodec.read(SidingCodec.remap(extended, mapOf("deck-yubel" to "new-id")))
        assertEquals(listOf("new-id", null), remapped.matchups.map { it.deckId })
        // A payload with no siding is returned as it was.
        val plain = obj("""{"groups":{}}""")
        assertSame(plain, SidingCodec.remap(plain, mapOf("a" to "b")))
    }

    @Test
    fun marksReadTheWayTheListSaysTheyDo() {
        assertEquals('■', yubel.mark(Turn.FIRST))
        assertEquals('□', yubel.mark(Turn.SECOND))
        assertEquals('·', Matchup("m", "x").mark(Turn.FIRST))
        assertEquals(Turn.SECOND, Turn.FIRST.theirs)
    }

    @Test
    fun aPlanCountsCopiesAndNeverTakesMoreThanTheDeckHas() {
        val deck = Deck(main = listOf(ash, ash, nibiru), extra = listOf(link), side = listOf(shifter, evenly, evenly))
        var plan = SidePlan()
        assertTrue(SidingMath.canOut(deck, plan, ash))
        plan = plan.plusOut(ash).plusOut(ash)
        assertTrue(!SidingMath.canOut(deck, plan, ash))
        assertTrue(SidingMath.canOut(deck, plan, link))
        assertTrue(!SidingMath.canIn(deck, plan, ash))
        plan = plan.plusIn(evenly)
        assertEquals(-1, plan.balance)
        assertEquals("1 more out", SidingMath.balanceWords(plan))
        assertEquals(listOf(ash to 2), SidingMath.counted(plan.out))
        assertEquals(listOf(ash), plan.minusOut(ash).out)
    }

    @Test
    fun theDeckAfterSidingSwapsCopiesAndKeepsItsSize() {
        val deck = Deck(main = listOf(ash, ash, nibiru), extra = listOf(link), side = listOf(shifter, evenly, CardId(1)))
        val plan = SidePlan(out = listOf(ash, link), into = listOf(shifter, CardId(1)))
        val after = SidingMath.postSide(deck, plan) { it == link || it == CardId(1) }
        assertEquals(listOf(ash, nibiru, shifter), after.main)
        assertEquals(listOf(CardId(1)), after.extra)
        assertEquals(listOf(evenly, ash, link), after.side)
        assertEquals(deck.main.size + deck.extra.size + deck.side.size, after.main.size + after.extra.size + after.side.size)
    }

    @Test
    fun aPlanOlderThanItsDeckSaysWhatIsGone() {
        val deck = Deck(main = listOf(ash), side = listOf(shifter))
        val plan = SidePlan(out = listOf(ash, ash, nibiru), into = listOf(shifter))
        assertEquals(listOf(ash to 1, nibiru to 1), SidingMath.stale(deck, plan))
    }

    @Test
    fun strangeShapesAreDroppedNotFailed() {
        val odd = obj("""{"siding":{"matchups":[{"id":"a","name":"A","first":{"out":["x",0,-3,14558127]}}, 7, {"id":"a","name":"dup"}]}}""")
        val read = SidingCodec.read(odd)
        assertEquals(1, read.matchups.size)
        assertEquals(listOf(ash), read.matchups[0].first.out)
        assertEquals("A", read.matchups[0].name)
    }
}
