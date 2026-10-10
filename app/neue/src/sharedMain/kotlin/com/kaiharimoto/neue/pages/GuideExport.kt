package com.kaiharimoto.neue.pages

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.pdf.PdfImage
import com.kaiharimoto.mastertool.core.pdf.TrueType
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.siding.SideCoverage
import com.kaiharimoto.mastertool.core.siding.GuideCard
import com.kaiharimoto.mastertool.core.siding.GuideContent
import com.kaiharimoto.mastertool.core.siding.GuideFonts
import com.kaiharimoto.mastertool.core.siding.GuideMatchup
import com.kaiharimoto.mastertool.core.siding.GuidePlan
import com.kaiharimoto.mastertool.core.siding.GuideTurn
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.GuideStyle
import com.kaiharimoto.mastertool.core.siding.SidingGuide
import com.kaiharimoto.mastertool.core.siding.SidingMath
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.art.ArtLibrary
import com.kaiharimoto.neue.art.CustomArt
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.res.Res
import com.kaiharimoto.neue.web.Webs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * The siding guide for deck [me] of [web], as a PDF's bytes (1.0.36): every
 * other deck of the web a matchup — written or not yet, so the guide is also a
 * list of what is left to do — and any plan against a deck outside it, each
 * with the opponent's own plan against [me] where they have one.
 *
 * Cards are printed with the artwork chosen for them (`CardArt`, `CustomArt`),
 * from the full-size originals, drawn down to [PICTURE_WIDTH] pixels: sharp on
 * paper at the size they print, and a guide of fifty cards stays a few
 * megabytes.
 */
object GuideExport {
    private const val PICTURE_WIDTH = 150

    /** Makes the guide and hands it over: saved and opened on the desk, shared on a tablet or phone. */
    suspend fun deliver(
        webs: Webs,
        web: DeckWeb?,
        decks: List<StoredDeck>,
        me: StoredDeck,
        state: DeckBuilderState,
        neue: NeueState,
        library: ArtLibrary?,
        custom: CustomArt?,
    ) {
        val bytes = runCatching { build(webs, web, decks, me, state, neue, library, custom) }.getOrElse {
            neue.note = com.kaiharimoto.neue.Note("The guide could not be made")
            return
        }
        // A deck's name as a file's: nothing a file system refuses.
        val safe = me.entry.name.map { if (it in UNSAFE) '-' else it }.joinToString("").trim().ifBlank { "Deck" }
        com.kaiharimoto.neue.platform.deliverFile("$safe · siding guide.pdf", "application/pdf", bytes)?.let { neue.note = com.kaiharimoto.neue.Note(it) }
    }

    private const val UNSAFE = "\\/:*?\"<>|"

    suspend fun build(
        webs: Webs,
        /** The web [me] is sided in; null for a deck sided on its own (1.0.42), whose [decks] are the ones its matchups link. */
        web: DeckWeb?,
        decks: List<StoredDeck>,
        me: StoredDeck,
        state: DeckBuilderState,
        neue: NeueState,
        library: ArtLibrary?,
        custom: CustomArt?,
    ): ByteArray {
        val siding = webs.sidingOf(me, state)
        val deck = webs.deckOf(me, state)
        fun name(id: CardId) = state.index.byId(id)?.name ?: "#${id.value}"
        fun cards(ids: List<CardId>) = SidingMath.counted(ids).map { (id, n) -> GuideCard(id, name(id), n) }
        fun printed(p: SidePlan?) = p?.let { GuidePlan(cards(it.out), cards(it.into), it.note) }

        val opponents = decks.filter { it.entry.id != me.entry.id }
        val inWeb = opponents.map { o ->
            val m = siding.against(o.entry.id, o.entry.name)
            val theirs = webs.sidingOf(o, state).against(me.entry.id, me.entry.name)
            GuideMatchup(
                name = o.entry.name,
                share = web?.entry(o.entry.id)?.share,
                note = m?.note.orEmpty(),
                covers = faces(o, neue, state).map { it.id },
                turns = Turn.entries.map { t -> GuideTurn(t, printed(m?.plan(t)), printed(theirs?.plan(t.theirs))) },
            )
        }
        val loose = siding.matchups
            .filter { m -> opponents.none { siding.against(it.entry.id, it.entry.name) == m } }
            .filter { it.first.sided || it.second.sided || it.note.isNotBlank() }
            .map { m -> GuideMatchup(m.name, null, m.note, m.covers, Turn.entries.map { t -> GuideTurn(t, printed(m.plan(t)), null) }) }
        val content = GuideContent(
            deckName = me.entry.name,
            webName = web?.name.orEmpty(),
            counts = "${deck.main.size} · ${deck.extra.size} · ${deck.side.size}",
            matchups = inWeb + loose,
            webNotes = web?.notes.orEmpty(),
            // The Side Deck across the field on the first page (Phase G, G.6).
            coverage = SideCoverage.guideLines(webs.coverage(me, state)) { state.index.byId(it)?.name ?: "#${it.value}" },
        )

        val fonts = GuideFonts(
            TrueType(Res.readBytes("font/inter_regular.ttf")),
            TrueType(Res.readBytes("font/inter_bold.ttf")),
            TrueType(Res.readBytes("font/jetbrainsmono_regular.ttf")),
        )
        // Every picture the guide prints, read before it is laid out: the layout itself never waits.
        // The guide is drawn as Siding is shown (1.0.49): art prints a picture per copy, a list only names.
        val style = if (neue.prefs.sidingView == NeuePreferences.SIDING_LIST) GuideStyle.LIST else GuideStyle.ART
        val wanted = content.matchups.flatMap { m ->
            m.covers + if (style == GuideStyle.LIST) emptyList() else m.turns.flatMap { t -> listOfNotNull(t.plan, t.theirs).flatMap { p -> (p.out + p.into).map { it.id } } }
        }.distinct()
        val pictures = HashMap<CardId, PdfImage>()
        wanted.forEach { id -> picture(id, state, neue, library, custom)?.let { pictures[id] = it } }
        return withContext(Dispatchers.Default) { SidingGuide.write(content, fonts, { pictures[it] }, JvmZlib, style) }
    }

    /** A card's picture, as the app shows it, drawn down to print size; null when there is none to be had. */
    internal suspend fun picture(id: CardId, state: DeckBuilderState, neue: NeueState, library: ArtLibrary?, custom: CustomArt?, width: Int = PICTURE_WIDTH, jpeg: Boolean = false): PdfImage? {
        val card = state.index.byId(id) ?: return null
        val choice = neue.prefs.arts[id.value]
        val drawn = custom?.drawn(card, choice) ?: CardArt.show(card, choice?.let(::CardId))
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val own = drawn.imageUrl?.takeIf { drawn.id.value < 0 && it.startsWith("file:") }
                if (own != null) File(URI(own)).readBytes() else library?.ensure(drawn)?.readBytes()
            }.getOrNull()
        } ?: return null
        return withContext(Dispatchers.Default) {
            decodePicture(bytes)?.let { image ->
                // A JPEG when asked (1.0.67): a guide shared in a chat must be small, and card art deflates poorly.
                if (jpeg) scaled(image, width).let { small -> com.kaiharimoto.neue.platform.encodeJpeg(small, 82)?.let { PdfImage.jpeg(small.width, small.height, it) } } ?: rgb(image, width) else rgb(image, width)
            }
        }
    }

    /** [image] drawn [w] pixels wide at its own shape. */
    private fun scaled(image: ImageBitmap, w: Int): ImageBitmap {
        val h = (w * image.height.toFloat() / image.width).toInt().coerceAtLeast(1)
        val small = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(small), Size(w.toFloat(), h.toFloat())) {
            drawImage(image, dstSize = IntSize(w, h), filterQuality = FilterQuality.High)
        }
        return small
    }

    /** [image] drawn [w] pixels wide (by default [PICTURE_WIDTH]) at the card's own shape, as RGB. */
    private fun rgb(image: ImageBitmap, w: Int = PICTURE_WIDTH): PdfImage {
        val small = scaled(image, w)
        val h = small.height
        val argb = IntArray(w * h)
        small.readPixels(argb)
        val out = ByteArray(w * h * 3)
        argb.forEachIndexed { i, p ->
            out[3 * i] = (p shr 16).toByte()
            out[3 * i + 1] = (p shr 8).toByte()
            out[3 * i + 2] = p.toByte()
        }
        return PdfImage(w, h, out)
    }
}
