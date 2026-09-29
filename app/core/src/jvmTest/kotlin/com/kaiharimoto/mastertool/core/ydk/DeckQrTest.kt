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

/** A deck's QR code (1.0.31): the whole `.ydkx`, the name and the covers, in one code or (1.0.32) in parts. */
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
        // One code: the fullest deck, every card grouped, needs no second.
        val text = code.parts.single()
        assertTrue(text.startsWith(DeckQr.PREFIX))
        assertTrue(text.all { it in Base45.ALPHABET }, "alphanumeric mode, which packs a QR code tightest")
        assertTrue(code.leftOut.isEmpty())
        assertTrue("8 groups" in code.carries && "1 hand goal" in code.carries && "notes" in code.carries && "the cover" in code.carries, "${code.carries}")
        // A version-25-or-so code at level M, read from across a desk.
        assertTrue(text.length < 1300, "${text.length} characters")

        val read = assertNotNull(DeckCodes.read(text, JvmZlib))
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
        // ~1,700 characters: two codes rather than one dense one.
        assertEquals(2, code.parts.size)
        val read = assertNotNull(DeckCodes.read(join(code.parts), JvmZlib)).parsed.document
        assertEquals(document.deck, read.deck)
        assertEquals(document.extended, read.extended)
    }

    /** The parts through a collector, in the order given; the joined code. */
    private fun join(parts: List<String>): String {
        val collector = DeckQrParts()
        return parts.map { collector.offer(it) }.filterIsInstance<DeckQrParts.Offer.Whole>().single().text
    }

    /** Notes no deflate can shrink much: a deck past one code. */
    private fun bulky(deck: Deck, size: Int) = JsonObject(
        groupsFor(deck, 2) + ("notes" to JsonPrimitive((1..size).joinToString("") { ((it * 7919) % 104729).toString(36) })),
    )

    @Test
    fun aDeckPastOneCodeIsSplitAndJoinsInAnyOrder() {
        val extended = bulky(deck, 1500)
        val code = assertNotNull(DeckQr.write("Big", YdkDocument(deck, extended = extended), emptyList(), JvmZlib))
        assertTrue(code.parts.size >= 3, "${code.parts.size} parts")
        assertTrue(code.leftOut.isEmpty())
        code.parts.forEachIndexed { i, part ->
            assertTrue(part.startsWith("${DeckQr.PART_PREFIX}${i + 1}/${code.parts.size}/"), part.take(30))
            assertTrue(part.length <= DeckQr.PART_CHARS + 20, "${part.length}")
            assertTrue(part.all { it in Base45.ALPHABET }, "each part in alphanumeric mode too")
            // A part alone is no deck.
            assertNull(DeckCodes.read(part, JvmZlib))
        }

        // Backwards, and with every part passing the camera twice.
        val collector = DeckQrParts()
        val offers = (code.parts.reversed() + code.parts).map { collector.offer(it) }
        assertEquals(DeckQrParts.Offer.Part(1, code.parts.size, true), offers.first())
        val whole = offers.filterIsInstance<DeckQrParts.Offer.Whole>().first().text
        val read = assertNotNull(DeckCodes.read(whole, JvmZlib))
        assertEquals(deck, read.parsed.document.deck)
        assertEquals(extended, read.parsed.document.extended)
        assertEquals("Big", read.name)
    }

    @Test
    fun aPartOfAnotherDeckStartsOver() {
        val one = assertNotNull(DeckQr.write("One", YdkDocument(deck, extended = bulky(deck, 1500)), emptyList(), JvmZlib)).parts
        val other = assertNotNull(DeckQr.write("Other", YdkDocument(deck, extended = bulky(deck, 1600)), emptyList(), JvmZlib)).parts
        val collector = DeckQrParts()
        collector.offer(one[0])
        assertEquals(DeckQrParts.Offer.Part(1, other.size, true), collector.offer(other[1]))
        val whole = other.map { collector.offer(it) }.filterIsInstance<DeckQrParts.Offer.Whole>().single().text
        assertEquals("Other", DeckCodes.read(whole, JvmZlib)?.name)
        // Anything else passes through untouched.
        assertEquals(DeckQrParts.Offer.Whole("ydke://abc!!!"), collector.offer("ydke://abc!!!"))
    }

    @Test
    fun aDamagedPartIsCaughtByTheTag() {
        val parts = assertNotNull(DeckQr.write("One", YdkDocument(deck, extended = bulky(deck, 1500)), emptyList(), JvmZlib)).parts
        val broken = parts[0].dropLast(3) + "000"
        val collector = DeckQrParts()
        val offers = (listOf(broken) + parts.drop(1)).map { collector.offer(it) }
        assertTrue(offers.none { it is DeckQrParts.Offer.Whole })
        assertEquals(0, collector.have)
    }

    @Test
    fun onlyPastTheMostPartsDoesADeckShedItsExtrasThenItsGroups() {
        val extended = JsonObject(groupsFor(deck, 2) + ("notes" to JsonPrimitive("x".repeat(400) + (1..300).joinToString())))
        val document = YdkDocument(deck, extended = extended)
        val whole = assertNotNull(DeckQr.write("Big", document, emptyList(), JvmZlib)).parts.single().length

        val groupsOnly = assertNotNull(DeckQr.write("Big", document, emptyList(), JvmZlib, maxParts = 1, singleChars = whole - 1))
        assertEquals(listOf("notes"), groupsOnly.leftOut)
        val read = assertNotNull(DeckCodes.read(groupsOnly.parts.single(), JvmZlib))
        assertEquals(extended["groups"], read.parsed.document.extended?.get("groups"))

        val cardsOnly = assertNotNull(DeckQr.write("Big", document, emptyList(), JvmZlib, maxParts = 1, singleChars = groupsOnly.parts.single().length - 1))
        assertEquals(listOf("groups", "notes"), cardsOnly.leftOut)
        assertEquals(deck, DeckCodes.read(cardsOnly.parts.single(), JvmZlib)?.parsed?.document?.deck)
        assertEquals("Big", DeckCodes.read(cardsOnly.parts.single(), JvmZlib)?.name)
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
        val body = assertNotNull(JvmZlib.inflate(assertNotNull(Base45.decode(code.parts.single().removePrefix(DeckQr.PREFIX))), 1 shl 20)).decodeToString()
        assertTrue(body.startsWith("#name A deck\n#main\n"), body)
        assertEquals(deck, YdkCodec.parse(body).document.deck)
        assertEquals(groupsFor(deck, 1), YdkCodec.parse(body).document.extended)
    }
}
