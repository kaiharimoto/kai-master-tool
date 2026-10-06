package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCond
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDoc
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHost
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `goldfish` instrument (Phase D step 4) to the instruments' standard: its question, method and finding in its lines,
 * the library's fingerprint said (what `Evidence` keeps), boards with notes, the same answer for the same arguments, and
 * errors that show a call that works.
 */
class GoldfishInstrumentTest {
    private val pond = DeckEntry("pond", "Pond", Deck(main = GoldfishFixtures.deck(FROG to 3, CALLER to 3, STONE to 34).map(::CardId)), 0, 0)
    private val two = EndBoard("t-two", "Two Pond monsters", "pond", listOf(BoardCond.Controls(GoldfishFixtures.pondMonster, 2)))

    private fun host(trust: FxTrust = GoldfishFixtures.trust(), combos: List<Combo> = emptyList()) = object : WorldHost {
        val byId = (GoldfishFixtures.cards + FxRef.cards).associateBy { it.id.value }
        override fun cardById(id: Int): Card? = byId[id]
        override fun cardNamed(name: String): Card? = byId.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override fun search(query: String, limit: Int): List<Card> = emptyList()
        override fun deck(id: String?): DeckEntry? = if (id == null || id == "pond") pond else null
        override fun decks(): List<DeckEntry> = listOf(pond)
        override fun combos(deckId: String): List<Combo> = combos
        override fun goldfish(): GoldfishHost = object : GoldfishHost {
            override val trust: FxTrust = trust
            override fun doc(deckId: String): GoldfishDoc = GoldfishDoc(deck = deckId, targets = listOf(two))
            override val defaultHands: Int = 300
        }
    }

    private fun prim(v: Any): JsonElement = when (v) {
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to prim(v) })

    private fun cond(t: String, vararg rest: Pair<String, Any>) = obj("t" to t, *rest)

    private fun target(name: String, vararg conds: JsonObject) = obj("name" to name, "all" to JsonArray(conds.toList()))

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else if (v is JsonObject) v else JsonPrimitive(v.toString()) })

    @Test
    fun itAnswersTheQuestionWithItsMethodAndItsLibrary() {
        val r = Instruments.run("goldfish", args("target" to "Two Pond monsters", "seed" to 7), host())
        assertTrue(r.lines[0].startsWith("goldfish: Pond — “Two Pond monsters”"), r.lines[0])
        assertTrue("going first?" in r.lines[0])
        val method = r.lines[1]
        assertTrue("300 hands from seed 7" in method && "library " in method, method)
        assertTrue(r.lines.any { it.trim().startsWith("Gets there in at least ") }, r.lines.joinToString("\n"))
        val answer = r.answer.jsonObject
        assertEquals(300, answer["hands"]!!.jsonPrimitive.content.toInt())
        val library = answer["library"]!!.jsonPrimitive.content
        assertEquals(library, Evidence.libraryOf(r.lines.joinToString("\n")), "the proof can read the library off the lines")
        assertTrue(r.boards.any { it.id == "goldfish-rate" } && r.boards.all { it.note.isNotBlank() })
        // The same arguments, the same answer.
        val again = Instruments.run("goldfish", args("target" to "Two Pond monsters", "seed" to 7), host())
        assertEquals(r.answer, again.answer)
        // Going second, by its word.
        val second = Instruments.run("goldfish", args("target" to "t-two", "going" to "second", "hands" to 100), host())
        assertTrue("going second" in second.lines[0])
    }

    @Test
    fun aTargetMayBeGivenWholeAndALineByItsComboName() {
        val given = target("One Pond monster", cond("controls", "where" to obj("t" to "name-has", "word" to "Pond"), "n" to 1))
        val r = Instruments.run("goldfish", JsonObject(mapOf("target" to given, "hands" to JsonPrimitive(100))), host())
        assertTrue("“One Pond monster”" in r.lines[0], r.lines[0])
        val combo = Combo("c1", "Frog and Call", "pond", needs = listOf("Pond Frog", "Pond Caller"), steps = listOf("summon Pond Frog to m1", "u Pond Caller e1"))
        val line = Instruments.run("goldfish", args("target" to "Two Pond monsters", "combo" to "frog and call", "hands" to 100), host(combos = listOf(combo)))
        assertTrue(line.lines.any { "This line gets there in" in it }, line.lines.joinToString("\n"))
        assertEquals("c1", line.answer.jsonObject["combo"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsShowACallThatWorks() {
        val none = assertFailsWith<IllegalArgumentException> { Instruments.run("goldfish", JsonObject(emptyMap()), host()) }
        assertTrue("give target" in none.message!! && "Two Pond monsters" in none.message!!, none.message)
        val wrong = assertFailsWith<IllegalArgumentException> { Instruments.run("goldfish", args("target" to "Three"), host()) }
        assertTrue("no target “Three”" in wrong.message!!, wrong.message)
        val hands = assertFailsWith<IllegalArgumentException> { Instruments.run("goldfish", args("target" to "t-two", "hands" to 50_000), host()) }
        assertTrue("hands: 2000" in hands.message!!, hands.message)
        val bad = target("x", cond("lp"))
        val unread = assertFailsWith<IllegalArgumentException> { Instruments.run("goldfish", JsonObject(mapOf("target" to bad)), host()) }
        assertTrue("controls" in unread.message!!, unread.message)
        // A target that needs a card played as inert is refused, naming it.
        val elder = target("Elder", cond("controls", "where" to obj("t" to "name", "card" to GoldfishFixtures.ELDER)))
        val inert = assertFailsWith<IllegalArgumentException> { Instruments.run("goldfish", JsonObject(mapOf("target" to elder)), host()) }
        assertTrue("Pond Elder" in inert.message!! && "fx_request" in inert.message!!, inert.message)
        // Without the library, it says so.
        val bare = object : WorldHost by host() { override fun goldfish(): GoldfishHost? = null }
        assertFailsWith<IllegalArgumentException> { Instruments.run("goldfish", args("target" to "t-two"), bare) }
    }
}
