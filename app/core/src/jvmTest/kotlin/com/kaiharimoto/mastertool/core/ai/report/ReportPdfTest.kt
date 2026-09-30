package com.kaiharimoto.mastertool.core.ai.report

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The guide and the session report are real PDFs, paginated, with their words in them. */
class ReportPdfTest {
    private fun font(name: String) = TrueType(File("../neue/src/commonMain/composeResources/font/$name.ttf").readBytes())
    private val fonts = GuideFonts(font("inter_regular"), font("inter_bold"), font("jetbrainsmono_regular"))
    private fun picture(id: CardId) = PdfImage(4, 6, ByteArray(4 * 6 * 3) { (40 + id.value % 180).toByte() })

    private val guide = GuideDoc.parse(
        "# How lab plays\n\n" + (1..40).joinToString("\n") { i ->
            val section = listOf("Goals", "Lines", "Connections", "Card roles", "Weak points")[i % 5]
            "- $section: entry $i about [[Arianna the Labrynth Servant]] and **why it matters**, long enough to wrap across the column of the page at least once."
        },
    )
    private val reports = (1..3).map { i ->
        SessionReport(
            "d", "lab", i * 1000L, SessionReport.PRINCIPLES, "standard",
            summary = "Session $i: the spine is Arianna into Big Welcome.",
            learned = listOf("Arianna is a one-card starter.", "Big Welcome is the payoff."),
            insights = listOf("Run a third Welcome Labrynth."),
            openQuestions = listOf("Which trap to set first?"),
            understanding = 50 + i * 8, playing = 40 + i * 5, mirror = 45 + i,
            why = "The lines are legal by the text; interaction is untested.",
            questions = listOf(SessionReport.Asked("Which is your real starter?", "Arianna, always.")),
            startedAt = i * 1000L - 600_000,
        )
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    /** Kept under build/ for a look by eye; nothing reads them back. */
    private fun keep(name: String, bytes: ByteArray) = File("build/report-samples").apply { mkdirs() }.resolve(name).writeBytes(bytes)

    @Test
    fun theGuideIsAPaginatedPdf() {
        val bytes = ReportPdf.guide(guide, "lab", reports, listOf(ReportPdf.KeyCard(CardId(1), "Arianna the Labrynth Servant")), "30 September 2026", fonts, ::picture, null)
        keep("guide.pdf", bytes)
        val pdf = text(bytes)
        assertTrue(pdf.startsWith("%PDF-"))
        val pages = Regex("/Type\\s*/Page[^s]").findAll(pdf).count()
        assertTrue(pages >= 2, "forty entries run over a page: $pages")
        assertTrue(pdf.contains("/Subtype /Image") || pdf.contains("/Subtype/Image"), "the key card's picture")
    }

    @Test
    fun theSessionReportIsAPdfEvenWithNothingButScores() {
        val bytes = ReportPdf.session(reports.last(), reports, guide, "30 September 2026", fonts, null)
        keep("session.pdf", bytes)
        val full = text(bytes)
        assertTrue(full.startsWith("%PDF-"))
        val bare = SessionReport("d", "lab", 5L, SessionReport.TAUGHT, understanding = 10, playing = 5, mirror = 30)
        assertTrue(text(ReportPdf.session(bare, listOf(bare), GuideDoc.parse(null), "today", fonts, null)).startsWith("%PDF-"))
        val empty = text(ReportPdf.guide(GuideDoc.parse(null), "empty", emptyList(), emptyList(), "today", fonts, ::picture, null))
        assertTrue(empty.startsWith("%PDF-"))
    }
}
