package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.prep.DecklistSheet.Line
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecklistSheetTest {

    private fun font(name: String) = TrueType(File("../neue/src/commonMain/composeResources/font/$name.ttf").readBytes())
    private val fonts = GuideFonts(font("inter_regular"), font("inter_bold"), font("jetbrainsmono_regular"))

    private val content = DecklistSheet.Content(
        playerName = "Kai Aoki Harimoto",
        cardGameId = "0123456789",
        country = "United States",
        eventName = "Spring Regional Qualifier",
        eventDate = "2026-10-17",
        deckName = "Snake-Eye Fire King",
        monsters = listOf(
            Line(3, "Snake-Eye Ash"), Line(2, "Snake-Eye Oak"), Line(3, "Ash Blossom & Joyous Spring"),
            Line(1, "Diabellstar the Black Witch"), Line(3, "Fire King Avatar Arvata"), Line(2, "Fire King High Avatar Garunix"),
            Line(2, "Maxx \"C\""), Line(1, "Salamangreat Circle"), Line(1, "Nibiru, the Primal Being"),
        ),
        spells = listOf(
            Line(3, "Original Sinful Spoils - Snake-Eye"), Line(1, "Called by the Grave"), Line(2, "Fire King Sanctuary"),
            Line(1, "Triple Tactics Talent"), Line(2, "Infinite Impermanence"),
        ),
        traps = listOf(Line(3, "Infinite Impermanence"), Line(2, "Sinful Spoils Subdual"), Line(2, "Solemn Judgment")),
        side = listOf(Line(3, "Dimension Shifter"), Line(3, "Ghost Belle & Haunted Mansion"), Line(2, "Evenly Matched"), Line(2, "Droll & Lock Bird")),
        extra = listOf(Line(2, "Snake-Eyes Doomed Dragon"), Line(1, "Promethean Princess, Bestower of Flames"), Line(2, "I:P Masquerena")),
    )

    /** [text] as the page writes it in [font]: glyph ids in hex, inside angle brackets. */
    private fun encoded(font: TrueType, text: String) =
        "<" + TrueType.codePoints(text).joinToString("") { font.glyph(it).toString(16).padStart(4, '0') } + ">"

    private fun printed(pdf: String, text: String) =
        listOf(fonts.regular, fonts.bold, fonts.mono).any { pdf.contains(encoded(it, text)) }

    @Test
    fun theSheetCarriesEveryFieldAndEveryTotal() {
        val bytes = DecklistSheet.write(content, fonts, zlib = null)
        File("build/decklist-sample.pdf").writeBytes(DecklistSheet.write(content, fonts, JvmZlib))
        val pdf = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-"))
        assertEquals(1, Regex("/Type /Page /Parent").findAll(pdf).count())
        assertTrue(pdf.contains("/MediaBox [0 0 612.0 792.0]"), "US Letter")
        listOf(
            "First & middle name", "Last name", "CARD GAME ID", "Event name", "Event date", "Country of residency",
            "MONSTER CARDS", "SPELL CARDS", "TRAP CARDS", "SIDE DECK", "EXTRA DECK", "Main Deck total",
            "Total Monster cards", "Total Spell cards", "Total Trap cards", "Total Side Deck", "Total Extra Deck",
        ).forEach { assertTrue(printed(pdf, it), "“$it” is on the sheet") }
        // The name split at its last space, and the fields filled.
        listOf("Kai Aoki", "Harimoto", "0123456789", "United States", "Spring Regional Qualifier", "2026-10-17", "Snake-Eye Fire King")
            .forEach { assertTrue(printed(pdf, it), "“$it” is filled in") }
        // Totals: 18 monsters, 9 spells, 7 traps = 34 main; 10 side, 5 extra.
        assertEquals(34, content.mainTotal)
        listOf("18", "9", "7", "34", "10", "5").forEach { assertTrue(printed(pdf, it), "total $it") }
        // Every name in full, never cut.
        assertTrue(printed(pdf, "Promethean Princess, Bestower of Flames"))
        assertTrue(printed(pdf, "Original Sinful Spoils - Snake-Eye"))
    }

    @Test
    fun aLongSectionRunsOntoASecondPage() {
        val many = (1..27).map { Line(1, "Monster number $it with a rather long card name to shrink") }
        val bytes = DecklistSheet.write(content.copy(monsters = many), fonts, zlib = null)
        val pdf = String(bytes, Charsets.ISO_8859_1)
        assertEquals(2, Regex("/Type /Page /Parent").findAll(pdf).count())
        assertTrue(printed(pdf, "DECKLIST · CONTINUED"))
        assertTrue(printed(pdf, "page 2 of 2"))
        assertTrue(printed(pdf, many.last().name), "the 27th name is on page two, whole")
    }

    @Test
    fun namesSplitAtTheLastSpace() {
        assertEquals("Kai Aoki" to "Harimoto", DecklistSheet.splitName("  Kai  Aoki Harimoto "))
        assertEquals("Kai" to "", DecklistSheet.splitName("Kai"))
        assertEquals("" to "", DecklistSheet.splitName(""))
    }

    @Test
    fun thePlainListIsForPasting() {
        val text = DecklistSheet.plainText(content.copy(side = emptyList()))
        val lines = text.lines()
        assertEquals("Monsters (18)", lines.first())
        assertEquals("3 Snake-Eye Ash", lines[1])
        assertTrue("Spells (9)" in lines && "Traps (7)" in lines && "Extra Deck (5)" in lines)
        assertTrue(lines.none { it.startsWith("Side Deck") }, "an empty side is left out")
        assertTrue("1 Promethean Princess, Bestower of Flames" in lines)
    }
}
