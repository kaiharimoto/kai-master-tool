package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.model.Attribute
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The red team's findings on the 1.0.95 instruments (`docs/world/INSTRUMENTS-REDTEAM.md`), each written as a test that
 * failed on the code it was found in, before the fix. Every number is checked against arithmetic done here, by hand.
 */
class InstrumentsRedTeamTest {
    private fun c(id: Int, name: String, type: String, frame: String, text: String = "", level: Int? = null, attribute: Attribute = Attribute.UNKNOWN, race: String? = null) =
        Card(CardId(id), name, type, frame, text, race = race, attribute = attribute, level = level, atk = 1000)

    private val cards = listOf(
        c(1, "Snake-Eye Ash", "Effect Monster", "effect", "If this card is Normal or Special Summoned: You can add 1 Level 1 FIRE monster from your Deck to your hand.", 1, Attribute.FIRE, "Pyro"),
        c(2, "Snake-Eye Oak", "Effect Monster", "effect", "You can Special Summon 1 \"Snake-Eye\" monster from your hand or GY.", 1, Attribute.FIRE, "Pyro"),
        c(3, "Brick", "Normal Monster", "normal", "", 8),
        c(4, "Ash Blossom & Joyous Spring", "Tuner Effect Monster", "effect", "Discard this card; negate that effect.", 3, Attribute.FIRE, "Zombie"),
        c(5, "Light and Darkness Dragon", "Effect Monster", "effect", "", 8, Attribute.LIGHT, "Dragon"),
        c(6, "Filler", "Spell Card", "spell", ""),
        c(7, "I:P Masquerena", "Link Effect Monster", "effect_link", ""),
        c(8, "7 Colored Fish", "Normal Monster", "normal", "", 4, Attribute.WATER, "Fish"),
        c(9, "Lockdown", "Spell Card", "spell", "You cannot Special Summon monsters, except \"Snake-Eye\" monsters. In addition to your Normal Summon, you can Normal Summon 1 \"Snake-Eye\" monster."),
    )

    private val openId = "abc123"

    /** 3 Ash, 3 Oak, 3 Brick, 3 Ash Blossom, 1 LaD Dragon, 27 Filler: forty. */
    private val main = List(3) { CardId(1) } + List(3) { CardId(2) } + List(3) { CardId(3) } + List(3) { CardId(4) } + listOf(CardId(5)) + List(27) { CardId(6) }

    private fun host(
        deck: Deck = Deck(main = main, extra = listOf(CardId(7))),
        groups: Map<String, List<Int>> = mapOf("Starters" to listOf(1, 2), "Hand traps" to listOf(4), "Bricks" to listOf(3)),
        games: List<TestGame> = emptyList(),
    ) = object : WorldHost {
        val entry = DeckEntry(openId, "Snake-Eye", deck, 0, 0)
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override fun search(query: String, limit: Int) = emptyList<Card>()

        // As the app's snapshot does: null is the open deck, which has an id of its own.
        override fun deck(id: String?) = if (id == null || id == openId) entry else null
        override fun decks() = listOf(entry)
        override fun groups(deckId: String) = groups
        override fun games() = games
    }

    private fun json(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to el(v) })

    private fun el(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is List<*> -> JsonArray(v.map { el(it!!) })
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to el(x!!) })
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun choose(n: Int, k: Int): Double = if (k < 0 || k > n) 0.0 else (1..k).fold(1.0) { a, i -> a * (n - k + i) / i }

    /** P(at least one of [k] cards in a [hand] from [n]). */
    private fun atLeastOne(n: Int, k: Int, hand: Int) = 1 - choose(n - k, hand) / choose(n, hand)

    /** The rows of an openings answer (an array in 1.0.95, `rows` since). */
    private fun rows(r: Instruments.Result) = ((r.answer as? JsonArray) ?: r.answer.jsonObject["rows"]!!.jsonArray).map { it.jsonObject }
    private fun JsonObject.d(key: String) = this[key]!!.jsonPrimitive.double

    // ---- R1: "open" did not reach the open deck once it had an id of its own --------------------------------

    @Test
    fun r1_openReachesTheOpenDeck() {
        val r = Instruments.run("openings", json("deck" to "open", "conditions" to listOf("Starters>=1")), host())
        assertTrue(abs(rows(r)[0].d("exactFirst") - atLeastOne(40, 6, 5)) < 1e-12)
    }

    // ---- R2: a ratio sweep shrank the deck below forty --------------------------------------------------------

    @Test
    fun r2_aCardSweepKeepsTheDeckSize() {
        val r = Instruments.run("ratios", json("condition" to "Starters>=1", "card" to "Snake-Eye Ash"), host())
        val first = r.answer.jsonObject["first"]!!.jsonArray.map { it.jsonPrimitive.double }
        // 0 copies of Ash is 3 starters in forty (a card outside the condition takes each place), not 3 in 37.
        assertTrue(abs(first[0] - atLeastOne(40, 3, 5)) < 1e-9, first.toString())
        assertTrue(abs(first[3] - atLeastOne(40, 6, 5)) < 1e-9, first.toString())
    }

    // ---- R3: ratios promised a group sweep and had none ---------------------------------------------------------

    @Test
    fun r3_aGroupSweepCountsTheGroup() {
        val r = Instruments.run("ratios", json("condition" to "Hand traps>=1", "group" to "Hand traps", "from" to 3, "to" to 9), host())
        val a = r.answer.jsonObject
        val first = a["first"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(7, first.size)
        (3..9).forEachIndexed { i, n -> assertTrue(abs(first[i] - atLeastOne(40, n, 5)) < 1e-9, "$n: ${first[i]}") }
    }

    // ---- R4: an & or an "and" inside a name split it, unquoted ---------------------------------------------------

    @Test
    fun r4_namesWithAndOrAmpersandAreOneName() {
        val r = Instruments.run("openings", json("conditions" to listOf("Ash Blossom & Joyous Spring>=1", "Light and Darkness Dragon>=1")), host())
        assertTrue(abs(rows(r)[0].d("exactFirst") - atLeastOne(40, 3, 5)) < 1e-12)
        assertTrue(abs(rows(r)[1].d("exactFirst") - atLeastOne(40, 1, 5)) < 1e-12)
        // And the default conditions, read off group names holding an &.
        val g = Instruments.run("openings", JsonObject(emptyMap()), host(groups = mapOf("Search & Extenders" to listOf(1, 2))))
        assertTrue(abs(rows(g)[0].d("exactFirst") - atLeastOne(40, 6, 5)) < 1e-12)
    }

    // ---- R5: "< 0" read as "<= 0" -------------------------------------------------------------------------------

    @Test
    fun r5_lessThanZeroCannotHappen() {
        val r = Instruments.run("openings", json("conditions" to listOf("Bricks<0")), host())
        assertEquals(0.0, rows(r)[0].d("exactFirst"))
    }

    // ---- R6: given groups were matched by exact case, so a lower-case name counted nothing -----------------------

    @Test
    fun r6_givenGroupsMatchNamesWhateverTheCase() {
        val r = Instruments.run(
            "openings",
            json("conditions" to listOf("Traps>=1"), "groups" to mapOf("Traps" to listOf("ash blossom & joyous spring"))),
            host(),
        )
        assertTrue(abs(rows(r)[0].d("exactFirst") - atLeastOne(40, 3, 5)) < 1e-12)
        // A name in no section of the deck and not in the pool is an error that says what to do.
        val e = assertFailsWith<IllegalArgumentException> {
            Instruments.run("openings", json("conditions" to listOf("Traps>=1"), "groups" to mapOf("Traps" to listOf("Ash Blosom"))), host())
        }
        assertTrue("Ash Blosom" in e.message.orEmpty(), e.message)
    }

    // ---- R7: overlapping groups were only simulated -------------------------------------------------------------

    @Test
    fun r7_overlappingGroupsAreExact() {
        val r = Instruments.run(
            "openings",
            json("conditions" to listOf("Snakes>=1 & Ash>=1", "Snakes>=2 & Ash=0"), "groups" to mapOf("Snakes" to listOf("Snake-Eye Ash", "Snake-Eye Oak"), "Ash" to listOf("Snake-Eye Ash"))),
            host(),
        )
        // Ash is inside Snakes: the first is P(Ash ≥ 1); the second is P(Oak ≥ 2, Ash = 0).
        assertTrue(abs(rows(r)[0].d("exactFirst") - atLeastOne(40, 3, 5)) < 1e-12)
        val oak2noAsh = (2..3).sumOf { k -> choose(3, k) * choose(34, 5 - k) } / choose(40, 5)
        assertTrue(abs(rows(r)[1].d("exactFirst") - oak2noAsh) < 1e-12)
    }

    // ---- R8: the sample hands board misread a name that starts with a number ------------------------------------

    @Test
    fun r8_sampleHandsReadBackAsTheCardsDealt() {
        val fish = Deck(main = List(40) { CardId(8) })
        val r = Instruments.run("openings", json("conditions" to listOf("7 Colored Fish>=1")), host(deck = fish, groups = emptyMap()))
        val board = r.boards.first { it.kind == BoardKind.CARDS }
        val names = ChatMarkdown.cardGroups(board.payload).flatMap { g -> g.lines.map { it.name } }
        assertTrue(names.isNotEmpty() && names.all { it == "7 Colored Fish" }, names.toString())
    }

    // ---- R9: a heatmap with an empty cell was written as NaN, which is not JSON ---------------------------------

    @Test
    fun r9_boardsAreStrictJson() {
        val games = listOf(
            TestGame("1", 0, openId, "a", "A", TestGame.FIRST, 1, TestGame.WIN),
            TestGame("2", 0, openId, "b", "B", TestGame.SECOND, 1, TestGame.LOSS),
        )
        val r = Instruments.run("matchups", JsonObject(emptyMap()), host(games = games))
        // JSON has no NaN (JavaScript's JSON.parse refuses it): an empty cell is null.
        r.boards.filter { it.kind == BoardKind.CHART || it.kind == BoardKind.TABLE || it.kind == BoardKind.STAT }.forEach {
            assertTrue("NaN" !in it.payload, it.payload)
            Json.parseToJsonElement(it.payload)
        }
    }

    // ---- R10: matchups mixed every deck's games, and "open" found none ------------------------------------------

    @Test
    fun r10_matchupsAreTheOpenDecksByDefault() {
        val games = listOf(
            TestGame("1", 0, openId, "a", "A", TestGame.FIRST, 1, TestGame.WIN),
            TestGame("2", 0, "other-deck", "a", "A", TestGame.FIRST, 1, TestGame.LOSS),
        )
        val r = Instruments.run("matchups", json("deck" to "open"), host(games = games))
        assertTrue(r.lines.any { "1 game" in it }, r.lines.toString())
        val d = Instruments.run("matchups", JsonObject(emptyMap()), host(games = games))
        assertEquals(r.answer, d.answer)
    }

    // ---- R11: the card web read locks as summons and "addition" as a search -------------------------------------

    @Test
    fun r11_locksAndLookalikeWordsAreNotLinks() {
        val lock = Deck(main = List(3) { CardId(9) } + List(3) { CardId(1) } + List(3) { CardId(2) } + List(31) { CardId(6) })
        val r = Instruments.run("card_web", JsonObject(emptyMap()), host(deck = lock, groups = emptyMap()))
        val edges = r.answer.jsonObject["edges"]!!.jsonArray.map { e -> e.jsonArray.map { it.jsonPrimitive.content } }
        val fromLock = edges.filter { it[0] == "Lockdown" }
        assertTrue(fromLock.none { "summons" in it[2] || "searches" in it[2] }, fromLock.toString())
    }

    // ---- R12: the card web could not see a search by properties ------------------------------------------------

    @Test
    fun r12_aSearchByPropertiesIsALink() {
        val r = Instruments.run("card_web", JsonObject(emptyMap()), host())
        val edges = r.answer.jsonObject["edges"]!!.jsonArray.map { e -> e.jsonArray.map { it.jsonPrimitive.content } }
        // Ash adds "1 Level 1 FIRE monster": Oak is one (Ash is too, but a card never links to itself).
        assertTrue(edges.any { it[0] == "Snake-Eye Ash" && it[1] == "Snake-Eye Oak" && "searches" in it[2] }, edges.toString())
        // Ash Blossom is FIRE but Level 3: no link.
        assertTrue(edges.none { it[0] == "Snake-Eye Ash" && it[1] == "Ash Blossom & Joyous Spring" }, edges.toString())
    }

    // ---- R13: composition dropped cards it could not resolve without a word -------------------------------------

    @Test
    fun r13_compositionCountsWhatItCannotRead() {
        val unknown = Deck(main = main.dropLast(1) + CardId(999))
        val r = Instruments.run("composition", JsonObject(emptyMap()), host(deck = unknown))
        assertEquals(1, r.answer.jsonObject["unresolved"]!!.jsonPrimitive.content.toInt())
        assertTrue(r.lines.any { "1 card" in it && "unknown" in it }, r.lines.toString())
    }

    // ---- R14: an Extra Deck card could be swept into the Main Deck ----------------------------------------------

    @Test
    fun r14_anExtraDeckCardIsNotSweptIntoTheMain() {
        val e = assertFailsWith<IllegalArgumentException> {
            Instruments.run("ratios", json("condition" to "Starters>=1", "card" to "I:P Masquerena"), host())
        }
        assertTrue("Extra Deck" in e.message.orEmpty(), e.message)
    }

    // ---- R15: a condition on a group with no Main Deck card read 0% without a word ------------------------------

    @Test
    fun r15_anEmptyGroupIsSaid() {
        val r = Instruments.run("openings", json("conditions" to listOf("Links>=1"), "groups" to mapOf("Links" to listOf("I:P Masquerena"))), host())
        assertTrue(r.lines.any { "Links" in it && "no Main Deck card" in it }, r.lines.toString())
    }

    // ---- R16: trials were clamped silently ----------------------------------------------------------------------

    @Test
    fun r16_clampedTrialsAreSaid() {
        val r = Instruments.run("openings", json("conditions" to listOf("Starters>=1"), "trials" to 50_000_000), host())
        assertTrue(r.lines.any { "trials" in it && "at most" in it }, r.lines.toString())
    }

    // ---- R17: the script prelude's list of instruments drifted from the app's ------------------------------------

    @Test
    fun r17_thePreludeOffersEveryInstrument() {
        Instruments.ALL.forEach { assertTrue("'${it.name}'" in WorldPrelude.JS, it.name) }
    }
}
