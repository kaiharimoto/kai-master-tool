package com.kaiharimoto.mastertool.core.ydk

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deck's QR code (1.0.31): the whole `.ydkx`, the name and the covers, in one code. */
class DeckQrTest {

    private val deck = Deck(
        main = listOf(CardId(14558127), CardId(14558127), CardId(89631139)),
        extra = listOf(CardId(1861629)),
        side = emptyList(),
    )

    /** The largest deck there is: sixty, fifteen and fifteen. */
    private val fullest = Deck(
        main = List(60) { CardId(10_000_000 + it / 3 * 1_234_567) },
        extra = List(15) { CardId(20_000_000 + it * 987_654) },
        side = List(15) { CardId(30_000_000 + it * 55_555) },
    )

    /** Every card of [deck] in one of [count] groups, a hand goal and a note: what the code must still carry. */
    private fun groupsFor(deck: Deck, count: Int) = buildJsonObject {
        putJsonObject("groups") {
            put(
                "defs",
                buildJsonArray {
                    repeat(count) { g -> add(buildJsonObject { put("id", "g$g"); put("name", "Group number $g"); put("color", g); put("order", g) }) }
                },
            )
            putJsonObject("cards") {
                (deck.main + deck.extra + deck.side).distinct().forEachIndexed { i, id -> put(id.value.toString(), "g${i % count}") }
            }
            put("lens", "ROLES")
            put(
                "goals",
                buildJsonArray {
                    add(buildJsonObject { put("id", "q1"); put("name", "Opens"); put("hand", 5); putJsonObject("asks") { put("g0", "AT_LEAST_1") } })
                },
            )
        }
        putJsonObject("notes") { put("plan", "Open Ash into their starter; side in the backrow hate game two.") }
    }

    @Test
    fun theWholeDeckCrosses() {
        val extended = groupsFor(fullest, 8)
        val code = assertNotNull(DeckQr.write("Fiendsmith: Radiant ✦ Typhoon", YdkDocument(fullest, extended = extended), listOf(14558127, -2), JvmZlib))
        assertTrue(code.text.startsWith(DeckQr.PREFIX))
        assertTrue(code.text.all { it in Base45.ALPHABET }, "alphanumeric mode, which packs a QR code tightest")
        assertTrue(code.leftOut.isEmpty())
        assertTrue("8 groups" in code.carries && "1 hand goal" in code.carries && "notes" in code.carries && "the cover" in code.carries, "${code.carries}")
        // A version-25-or-so code at level M, read from across a desk.
        assertTrue(code.text.length < 1300, "${code.text.length} characters")

        val read = assertNotNull(DeckCodes.read(code.text, JvmZlib))
        assertEquals(fullest, read.parsed.document.deck)
        assertEquals(extended, read.parsed.document.extended)
        assertEquals("Fiendsmith: Radiant ✦ Typhoon", read.name)
        // An own picture is a file on the machine that made the code.
        assertEquals(listOf(14558127), read.covers)
    }

    @Test
    fun theLabFileFits() {
        // The legacy tool's deck: siding patterns, a card pool, its settings.
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "lab.ydkx").isFile) dir = dir.parentFile
        val document = YdkCodec.parse(File(assertNotNull(dir), "lab.ydkx").readText()).document
        val code = assertNotNull(DeckQr.write("Lab", document, emptyList(), JvmZlib))
        assertTrue(code.leftOut.isEmpty(), "${code.leftOut}")
        assertTrue(code.text.length < 2000, "${code.text.length} characters")
        val read = assertNotNull(DeckCodes.read(code.text, JvmZlib)).parsed.document
        assertEquals(document.deck, read.deck)
        assertEquals(document.extended, read.extended)
    }

    @Test
    fun tooMuchShedsTheExtrasFirstAndThenTheGroups() {
        val extended = JsonObject(groupsFor(deck, 2) + ("notes" to JsonPrimitive("x".repeat(400) + (1..300).joinToString())))
        val document = YdkDocument(deck, extended = extended)
        val whole = assertNotNull(DeckQr.write("Big", document, emptyList(), JvmZlib)).text.length

        val groupsOnly = assertNotNull(DeckQr.write("Big", document, emptyList(), JvmZlib, maxChars = whole - 1))
        assertEquals(listOf("notes"), groupsOnly.leftOut)
        val read = assertNotNull(DeckCodes.read(groupsOnly.text, JvmZlib))
        assertEquals(extended["groups"], read.parsed.document.extended?.get("groups"))

        val cardsOnly = assertNotNull(DeckQr.write("Big", document, emptyList(), JvmZlib, maxChars = groupsOnly.text.length - 1))
        assertEquals(listOf("groups", "notes"), cardsOnly.leftOut)
        assertEquals(deck, DeckCodes.read(cardsOnly.text, JvmZlib)?.parsed?.document?.deck)
        assertEquals("Big", DeckCodes.read(cardsOnly.text, JvmZlib)?.name)
    }

    @Test
    fun aScannedYdkeCodeIsADeck() {
        val code = YdkeCodec.encode(deck)
        assertEquals(deck, DeckCodes.read(code, JvmZlib)?.parsed?.document?.deck)
        // Padded by a scanner, or inside a deck site's link.
        assertEquals(deck, DeckCodes.read("  $code\n", JvmZlib)?.parsed?.document?.deck)
        assertEquals(deck, DeckCodes.read("https://example.com/import?deck=$code&from=qr", JvmZlib)?.parsed?.document?.deck)
    }

    @Test
    fun aScannedDeckFileKeepsItsGroups() {
        val extended = groupsFor(deck, 2)
        val read = DeckCodes.read(YdkCodec.write(deck, extended = extended), JvmZlib)
        assertEquals(deck, read?.parsed?.document?.deck)
        assertEquals(extended, read?.parsed?.document?.extended)
    }

    @Test
    fun aScanWithNoDeckInItIsNothing() {
        assertNull(DeckCodes.read("https://example.com", JvmZlib))
        assertNull(DeckCodes.read("89631139", JvmZlib))
        assertNull(DeckCodes.read(YdkeCodec.encode(Deck()), JvmZlib))
        assertNull(DeckCodes.read("#main\n#extra\n!side\n", JvmZlib))
        assertNull(DeckCodes.read(DeckQr.PREFIX + "NOT ZLIB AT ALL", JvmZlib))
        assertNull(DeckCodes.read(DeckQr.PREFIX + "abc", JvmZlib))
    }

    @Test
    fun aCodeIsADeckFileOnceUnpacked() {
        // The body is a .ydkx with a comment line on top: anything that reads a deck file reads it.
        val code = assertNotNull(DeckQr.write("A deck", YdkDocument(deck, extended = groupsFor(deck, 1)), emptyList(), JvmZlib))
        val body = assertNotNull(JvmZlib.inflate(assertNotNull(Base45.decode(code.text.removePrefix(DeckQr.PREFIX))), 1 shl 20)).decodeToString()
        assertTrue(body.startsWith("#name A deck\n#main\n"), body)
        assertEquals(deck, YdkCodec.parse(body).document.deck)
        assertEquals(groupsFor(deck, 1), YdkCodec.parse(body).document.extended)
    }
}
