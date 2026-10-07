package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidingGuideTest {

    private fun font(name: String) = TrueType(File("../table/src/commonMain/composeResources/font/$name.ttf").readBytes())
    private val fonts = GuideFonts(font("inter_regular"), font("inter_bold"), font("jetbrainsmono_regular"))

    /** A flat picture per card, a different grey each, standing in for its art. */
    private fun picture(id: CardId): PdfImage {
        val shade = (40 + id.value % 180).toByte()
        return PdfImage(4, 6, ByteArray(4 * 6 * 3) { i -> if (i % 3 == 2) (shade + 30).toByte() else shade })
    }

    private fun card(n: Int, name: String, count: Int) = GuideCard(CardId(n), name, count)

    private val handtraps = listOf(card(1, "Nibiru, the Primal Being", 2), card(2, "Droll & Lock Bird", 1))
    private val sideIns = listOf(card(3, "Dimension Shifter", 2), card(4, "Ghost Belle & Haunted Mansion", 1))

    private fun matchup(i: Int) = GuideMatchup(
        name = "Opponent $i",
        share = 10 + i,
        note = "They go second into our board with Dimension Shifter. Keep Called by the Grave for their Fuwalos; we win by out-grinding the Fiend engine.",
        covers = listOf(CardId(5), CardId(6), CardId(7)),
        turns = listOf(
            GuideTurn(
                Turn.FIRST,
                GuidePlan(handtraps, sideIns, "Their board is not there yet: trade the non-engine handtraps for cards that stop Fuwalos."),
                GuidePlan(listOf(card(8, "Evenly Matched", 2)), listOf(card(9, "Mulcharmy Fuwalos", 1)), "They break our board at the end phase."),
            ),
            GuideTurn(Turn.SECOND, if (i % 2 == 0) null else GuidePlan(sideIns, handtraps, ""), null),
        ),
    )

    @Test
    fun wrappingKeepsEveryWordAndEveryLineFits() {
        val text = "Their board is not there yet: trade the non-engine handtraps for cards that stop Fuwalos and Shifter."
        val lines = SidingGuide.wrap(text, fonts.regular, 8f, 120f)
        assertTrue(lines.size > 1)
        lines.forEach { assertTrue(fonts.regular.width(it, 8f) <= 120f, "“$it” fits") }
        assertEquals(text.split(' '), lines.joinToString(" ").split(' '))
        // A word longer than the line is broken inside it rather than overflowing.
        SidingGuide.wrap("Supercalifragilisticexpialidocious", fonts.regular, 12f, 40f).forEach { assertTrue(fonts.regular.width(it, 12f) <= 40f) }
        assertEquals(listOf("one", "", "two"), SidingGuide.wrap("one\n\ntwo", fonts.regular, 8f, 100f))
    }

    @Test
    fun aGuideOfManyMatchupsRunsOverPagesAndCountsThem() {
        val content = GuideContent("Snake-Eye Fiendsmith", "Spring Regional", "40 · 15 · 15", (1..7).map(::matchup), webNotes = "Yubel is a third of the room.")
        val bytes = SidingGuide.write(content, fonts, ::picture, JvmZlib)
        File("build/guide-sample.pdf").writeBytes(bytes)
        val text = String(bytes, Charsets.ISO_8859_1)
        val pages = Regex("/Type /Page /Parent").findAll(text).count()
        assertTrue(pages >= 2, "$pages pages")
        // Each card's picture is written once, however often it is printed.
        assertEquals(9, Regex("/Subtype /Image").findAll(text).count())
    }

    @Test
    fun theListStyleNamesTheCardsAndPrintsOnlyTheirFaces() {
        val content = GuideContent("Snake-Eye Fiendsmith", "Spring Regional", "40 · 15 · 15", (1..7).map(::matchup))
        val art = SidingGuide.write(content, fonts, ::picture, null, GuideStyle.ART)
        val list = SidingGuide.write(content, fonts, ::picture, null, GuideStyle.LIST)
        File("build/guide-sample-list.pdf").writeBytes(list)
        val artImages = Regex("/Subtype /Image").findAll(String(art, Charsets.ISO_8859_1)).count()
        val listImages = Regex("/Subtype /Image").findAll(String(list, Charsets.ISO_8859_1)).count()
        assertTrue(listImages < artImages, "a list prints the opponents' faces, not the plans' cards: $listImages vs $artImages")
        assertTrue(list.size < art.size)
    }
}
