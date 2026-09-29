package com.kaiharimoto.neue

import com.google.zxing.RGBLuminanceSource
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.DeckQr
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.qr.QrMatrix
import com.kaiharimoto.neue.qr.QrReader
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

/**
 * The desk's QR code and the phone's reading of it (1.0.30; the whole deck,
 * 1.0.31), end to end: a deck's code drawn as the dialog draws it, read back
 * out of the pixels as a picture of it is, and back into the deck.
 */
class QrTest {

    /** The largest deck there is, every card in one of eight groups, with a goal and a note. */
    private val fullest = Deck(
        main = List(60) { CardId(10_000_000 + it / 3 * 1_234_567) },
        extra = List(15) { CardId(20_000_000 + it * 987_654) },
        side = List(15) { CardId(30_000_000 + it * 55_555) },
    )

    private val extended = buildJsonObject {
        putJsonObject("groups") {
            put("defs", buildJsonArray { repeat(8) { g -> add(buildJsonObject { put("id", "g$g"); put("name", "Group number $g"); put("color", g); put("order", g) }) } })
            putJsonObject("cards") {
                (fullest.main + fullest.extra + fullest.side).distinct().forEachIndexed { i, id -> put(id.value.toString(), "g${i % 8}") }
            }
            put("lens", "ROLES")
            put("goals", buildJsonArray { add(buildJsonObject { put("id", "q1"); put("name", "Opens"); put("hand", 5); putJsonObject("asks") { put("g0", "AT_LEAST_1") } }) })
        }
        putJsonObject("notes") { put("plan", "Open Ash into their starter; side in the backrow hate game two.") }
    }

    /** [matrix] as pixels, [scale] to a module, with the quiet zone; [invert] for light on dark. */
    private fun picture(matrix: QrMatrix, scale: Int = 4, invert: Boolean = false): RGBLuminanceSource {
        val span = (matrix.size + QrMatrix.QUIET * 2) * scale
        val pixels = IntArray(span * span) { i ->
            val x = i % span / scale - QrMatrix.QUIET
            val y = i / span / scale - QrMatrix.QUIET
            val dark = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
            if (dark != invert) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return RGBLuminanceSource(span, span, pixels)
    }

    @Test
    fun theWholeDeckWithItsGroupsReadsBackOffTheScreen() {
        val code = assertNotNull(DeckQr.write("Fiendsmith", YdkDocument(fullest, extended = extended), listOf(14558127), JvmZlib))
        val matrix = assertNotNull(QrMatrix.of(code.text))
        println("The fullest deck with eight groups: ${code.text.length} characters, ${matrix.size} modules a side")
        // Version 25 or under: five pixels a module or more in the dialog on a 1080p screen.
        assertTrue(matrix.size <= 117, "a ${matrix.size}-module code")
        val read = assertNotNull(QrReader.read(picture(matrix)))
        assertEquals(code.text, read)
        val deck = assertNotNull(DeckCodes.read(read, JvmZlib))
        assertEquals(fullest, deck.parsed.document.deck)
        assertEquals(extended, deck.parsed.document.extended)
        assertEquals("Fiendsmith", deck.name)
        assertEquals(listOf(14558127), deck.covers)
    }

    @Test
    fun theLegacyLabFileReadsBackToo() {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "lab.ydkx").isFile) dir = dir.parentFile
        val document = YdkCodec.parse(File(assertNotNull(dir), "lab.ydkx").readText()).document
        val code = assertNotNull(DeckQr.write("Lab", document, emptyList(), JvmZlib))
        val matrix = assertNotNull(QrMatrix.of(code.text))
        println("lab.ydkx, siding patterns and all: ${code.text.length} characters, ${matrix.size} modules a side")
        val read = assertNotNull(QrReader.read(picture(matrix, scale = 3)))
        assertEquals(document.extended, DeckCodes.read(read, JvmZlib)?.parsed?.document?.extended)
    }

    @Test
    fun theLargestCodeThereIsIsDrawn() {
        // A deck the ladder could not shrink further would still be drawn: version 40, level L.
        val text = DeckQr.PREFIX + "0".repeat(DeckQr.MAX_CHARS - DeckQr.PREFIX.length)
        assertEquals(177, assertNotNull(QrMatrix.of(text)).size)
        assertNull(QrMatrix.of(text + "0"))
    }

    @Test
    fun aCodeLightOnDarkReadsToo() {
        val code = YdkeCodec.encode(Deck(main = listOf(CardId(89631139), CardId(14558127))))
        val matrix = assertNotNull(QrMatrix.of(code))
        assertEquals(code, QrReader.read(picture(matrix, invert = true)))
    }

    @Test
    fun aPictureWithNoCodeIsNothing() {
        assertNull(QrReader.read(RGBLuminanceSource(64, 64, IntArray(64 * 64) { 0xFFFFFFFF.toInt() })))
    }
}
