package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.report.GuideDoc
import com.kaiharimoto.mastertool.core.ai.report.ReportPdf
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.pages.GuideExport
import com.kaiharimoto.neue.platform.deliverFile
import com.kaiharimoto.neue.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ai's documents as PDFs (1.0.54): a deck's living guide and a Fine Tuning session's report,
 * laid out in core (`ReportPdf`) and set in the app's own fonts, with the key cards drawn in the
 * artworks the person chose — saved and opened on the desk, shared on a tablet or phone.
 */
object AiDocs {
    private val day = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun date(at: Long): String = day.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    private suspend fun fonts() = GuideFonts(
        TrueType(Res.readBytes("font/inter_regular.ttf")),
        TrueType(Res.readBytes("font/inter_bold.ttf")),
        TrueType(Res.readBytes("font/jetbrainsmono_regular.ttf")),
    )

    /** The guide's key cards: the ones it names most, resolved to cards the pool knows. */
    fun keyCards(h: NeueHolders, doc: GuideDoc, limit: Int = 8): List<ReportPdf.KeyCard> =
        doc.cardMentions().mapNotNull { name -> h.builder.index.byName(name)?.let { ReportPdf.KeyCard(it.id, it.name) } }
            .distinctBy { it.id }.take(limit)

    suspend fun guideBytes(h: NeueHolders, deckId: String, deckName: String): ByteArray {
        val doc = GuideDoc.parse(h.ai.files.read(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(MemoryKind.GUIDE, deckId)))
        val reports = h.ai.files.reports(deckId)
        val cards = keyCards(h, doc)
        val pictures = HashMap<CardId, PdfImage>()
        cards.forEach { c -> GuideExport.picture(c.id, h.builder, h.neue, h.art, h.customArt)?.let { pictures[c.id] = it } }
        val updated = date(reports.maxOfOrNull { it.at } ?: System.currentTimeMillis())
        val f = fonts()
        return withContext(Dispatchers.Default) { ReportPdf.guide(doc, deckName, reports, cards, updated, f, { pictures[it] }, JvmZlib) }
    }

    /**
     * The reader's guide as a PDF in [style] (1.0.66): every card it names drawn in the artwork the
     * person chose, large enough for the largest place a layout sets it.
     */
    suspend fun readerBytes(h: NeueHolders, guide: com.kaiharimoto.mastertool.core.ai.report.ReaderGuide, style: com.kaiharimoto.mastertool.core.ai.report.ReaderGuidePdf.Style): ByteArray {
        val width = if (style == com.kaiharimoto.mastertool.core.ai.report.ReaderGuidePdf.Style.MAGAZINE) 300 else 180
        val pictures = HashMap<String, PdfImage>()
        guide.cards().forEach { name ->
            h.builder.index.byName(name)?.let { card -> GuideExport.picture(card.id, h.builder, h.neue, h.art, h.customArt, width)?.let { pictures[name] = it } }
        }
        val f = fonts()
        val updated = date(guide.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
        return withContext(Dispatchers.Default) { com.kaiharimoto.mastertool.core.ai.report.ReaderGuidePdf.render(guide, style, f, { pictures[it] }, JvmZlib, updated) }
    }

    suspend fun reportBytes(h: NeueHolders, report: SessionReport): ByteArray {
        val doc = GuideDoc.parse(h.ai.files.read(com.kaiharimoto.mastertool.core.ai.memory.AiMemory.path(MemoryKind.GUIDE, report.deckId)))
        val log = h.ai.files.reports(report.deckId).ifEmpty { listOf(report) }
        val f = fonts()
        return withContext(Dispatchers.Default) { ReportPdf.session(report, log, doc, date(report.at), f, JvmZlib) }
    }

    /** A deck's name as a file's: nothing a file system refuses. */
    private fun safe(name: String) = name.map { if (it in "\\/:*?\"<>|") '-' else it }.joinToString("").trim().ifBlank { "Deck" }

    suspend fun deliverGuide(h: NeueHolders, deckId: String, deckName: String) {
        val bytes = runCatching { guideBytes(h, deckId, deckName) }.getOrElse {
            h.neue.note = Note("The guide could not be made")
            return
        }
        deliverFile("${safe(deckName)} · guide.pdf", "application/pdf", bytes)?.let { h.neue.note = Note(it) }
    }

    suspend fun deliverReport(h: NeueHolders, report: SessionReport) {
        val bytes = runCatching { reportBytes(h, report) }.getOrElse {
            h.neue.note = Note("The report could not be made")
            return
        }
        deliverFile("${safe(report.deckName)} · session report ${date(report.at)}.pdf", "application/pdf", bytes)?.let { h.neue.note = Note(it) }
    }
}
