package com.kaiharimoto.neue.shot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.deck.LensKeying
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.remote.CardSetRelease
import com.kaiharimoto.neue.builder.drawPieces
import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.Marker
import com.kaiharimoto.neue.cards.MarkerChip
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.NameMask
import com.kaiharimoto.neue.cards.NameStyles
import com.kaiharimoto.neue.cards.drawFoil
import com.kaiharimoto.neue.cards.drawFoilName
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Mark
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuTheme
import com.kaiharimoto.neue.theme.MuType
import org.jetbrains.skia.EncodedImageFormat

/** One section of the picture: its cards in deck order, and its lens when the groups are showing. */
data class ShotSection(
    val section: DeckSection,
    val cards: List<Card?>,
    val keying: LensKeying?,
)

/** Everything the picture shows, gathered off the builder at the instant it was asked for. */
data class ShotModel(
    val name: String,
    val format: Format,
    /** `yyyy-MM-dd`. */
    val date: String,
    val latestSet: CardSetRelease?,
    val sections: List<ShotSection>,
    /** The lens's name when the groups are showing, else null. */
    val lens: String?,
    val ink: Boolean,
    val foil: String,
    /** How the names are drawn (`NameStyles`). */
    val names: String = NameStyles.FOIL,
)

/**
 * The picture a deck is shared as: main, extra and side, as they stand, with
 * none of the window around them — no pool, no buttons, no inspector. What the
 * builder was *showing* stays: if a lens was on, its colours are in the
 * picture, because that is the part of a deck its builder drew.
 *
 * Laid out by arithmetic rather than by measurement ([heightOf]) so the image
 * is exactly as tall as its contents before anything is drawn.
 */
object DeckShot {
    val WIDTH = 1600.dp
    private val PAD = 48.dp
    /** The gap between two of the deck's pieces when a lens is on, as the builder draws it (`GroupPieces`). */
    private val GAP = 10.dp
    private val HEADER = 196.dp
    private val STRIP = 44.dp
    private val LEGEND = 40.dp
    private val SECTION_BOTTOM = 28.dp
    private val FOOTER = 64.dp

    /** Pixels per dp: a card 146dp wide is 292 pixels, and an original is 813, so nothing is upscaled. */
    const val DENSITY = 2f

    private fun columnsOf(section: DeckSection) = if (section == DeckSection.MAIN) 10 else 15

    /** The section in pieces by its lens, or one plain piece with no lens. */
    private fun piecesOf(s: ShotSection): PieceLayout {
        val keying = s.keying?.takeIf { !it.isEmpty }
        return GroupPieces.of(List(s.cards.size) { keying?.keyAt(it) }, columnsOf(s.section))
    }

    private fun cardWidth(s: ShotSection): Dp {
        val cols = columnsOf(s.section)
        return (WIDTH - PAD * 2 - GAP * piecesOf(s).spanX) / cols
    }

    private fun gridHeight(s: ShotSection): Dp {
        val cols = columnsOf(s.section)
        val rows = (s.cards.size + cols - 1) / cols
        val h = cardWidth(s) / CARD_RATIO
        return if (rows == 0) 0.dp else h * rows + GAP * piecesOf(s).spanY
    }

    private fun legendShown(s: ShotSection) = s.keying?.let { !it.isEmpty } == true

    /** The sections the picture carries: main always, extra and side when they have anything in them. */
    fun shown(model: ShotModel) = model.sections.filter { it.section == DeckSection.MAIN || it.cards.isNotEmpty() }

    fun heightOf(model: ShotModel): Dp =
        HEADER + shown(model).fold(0.dp) { acc, s ->
            acc + STRIP + (if (legendShown(s)) LEGEND else 0.dp) + 12.dp + gridHeight(s).coerceAtLeast(40.dp) + SECTION_BOTTOM
        } + FOOTER

    /** Draws [model] to a PNG. [images] holds the pictures by card id; a card without one is drawn as the hatch. */
    fun render(model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask> = emptyMap()): ByteArray {
        val width = (WIDTH.value * DENSITY).toInt()
        val height = (heightOf(model).value * DENSITY).toInt()
        val scene = ImageComposeScene(width, height, Density(DENSITY)) {
            MuTheme(ink = model.ink) { Picture(model, images, masks) }
        }
        try {
            val image = scene.render(0L)
            return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("The picture could not be encoded")
        } finally {
            scene.close()
        }
    }

    /** Draws [model] in [style] to a PNG. */
    fun render(style: ShotStyle, model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask> = emptyMap()): ByteArray {
        val plan = ShotDesigns.plan(style, model)
        val density = ShotDesigns.density(style)
        val scene = ImageComposeScene((plan.width * density).toInt(), (plan.height * density).toInt(), Density(density)) {
            MuTheme(ink = model.ink) { ShotDesigns.Picture(plan, model, images, masks) }
        }
        try {
            val image = scene.render(0L)
            return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("The picture could not be encoded")
        } finally {
            scene.close()
        }
    }

    @Composable
    fun Picture(model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask> = emptyMap()) {
        val c = Mu.colors
        val f = LocalMuFonts.current
        Column(Modifier.fillMaxSize().background(c.paper).padding(horizontal = PAD)) {
            // The header: whose deck, how big, when, and what the game looked like that day.
            Column(Modifier.fillMaxWidth().height(HEADER).padding(top = PAD)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mark(18.dp, ink = c.ink, paper = c.paper)
                    MuText("NEUE MASTER TOOL", style = MuType.wordmark(f))
                    Box(Modifier.weight(1f))
                    Micro("Taken", color = c.ink45)
                    Mono(model.date, color = c.ink, size = 13.sp)
                }
                MuText(model.name.ifBlank { "Untitled deck" }, Modifier.padding(top = 20.dp), MuType.display(f).copy(fontSize = 44.sp), maxLines = 1)
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    model.sections.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Micro(s.section.displayName, color = c.ink45)
                            Mono(s.cards.size.toString(), color = c.ink, size = 15.sp)
                        }
                    }
                    Micro(model.format.name, color = c.ink45)
                    if (model.lens != null) Micro("Lens · ${model.lens}", color = c.ink45)
                    Box(Modifier.weight(1f))
                    model.latestSet?.let { set ->
                        Micro("Latest TCG set", color = c.ink45)
                        MuText(set.name, style = MuType.row(f), maxLines = 1)
                        if (set.code.isNotBlank()) Mono(set.code, color = c.ink45, size = 12.sp)
                        set.tcgDate?.let { Mono(it, color = c.ink45, size = 12.sp) }
                    }
                }
            }
            HRule(strong = true)

            shown(model).forEach { s -> SectionBlock(s, model, images, masks) }

            // The footer: one quiet line, so a picture that travels says where it came from.
            Row(Modifier.fillMaxWidth().height(FOOTER), verticalAlignment = Alignment.CenterVertically) {
                Small("${model.sections.sumOf { it.cards.size }} cards", color = c.ink45)
                Box(Modifier.weight(1f))
                Small("Card images from YGOPRODeck", color = c.ink45)
            }
        }
    }

    @Composable
    private fun SectionBlock(s: ShotSection, model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask>) {
        val c = Mu.colors
        val cols = columnsOf(s.section)
        val w = cardWidth(s)
        val h = w / CARD_RATIO
        val pieces = piecesOf(s)
        fun at(i: Int) = androidx.compose.ui.unit.DpOffset(w * (i % cols) + GAP * pieces.shiftX[i], h * (i / cols) + GAP * pieces.shiftY[i])
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(STRIP), verticalAlignment = Alignment.CenterVertically) {
                Micro("${s.section.displayName} deck", color = c.ink)
                Box(Modifier.weight(1f))
                Mono(s.cards.size.toString(), color = c.ink45, size = 13.sp)
            }
            HRule()
            val keying = s.keying?.takeIf { !it.isEmpty }
            if (keying != null) {
                Row(
                    Modifier.fillMaxWidth().height(LEGEND),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    keying.keys.forEach { key ->
                        val n = keying.countOf(key.id)
                        if (n == 0) return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(10.dp).background(GroupMarkers.paint(key.paint, c.ink)).border(1.dp, c.ink))
                            Small(key.label, color = c.ink, maxLines = 1)
                            Mono(n.toString(), color = c.ink45)
                        }
                    }
                }
            }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(gridHeight(s).coerceAtLeast(40.dp))) {
                if (s.cards.isEmpty()) {
                    Small("Empty", Modifier.align(Alignment.CenterStart), color = c.ink45)
                }
                if (keying != null) {
                    Canvas(Modifier.size(WIDTH - PAD * 2, gridHeight(s))) {
                        drawPieces(
                            keys = List(s.cards.size) { keying.keyAt(it) },
                            pieces = pieces,
                            at = { i -> at(i).let { Offset(it.x.toPx(), it.y.toPx()) } },
                            cardWidth = w.toPx(),
                            cardHeight = h.toPx(),
                            frame = 2.dp.toPx(),
                            colorOf = { id -> keying.keyById(id)?.let { GroupMarkers.paint(it.paint, c.ink) } ?: c.ink },
                            alphaOf = { 1f },
                        )
                    }
                }
                s.cards.forEachIndexed { i, card ->
                    val key = keying?.keyById(keying.keyAt(i))
                    Box(
                        Modifier
                            .offset(at(i).x, at(i).y)
                            .size(w, h),
                    ) {
                        ShotCard(card, images[card?.id?.value], masks[card?.id?.value], model, key?.let { Marker(it.mark, GroupMarkers.paint(it.paint, c.ink)) })
                    }
                }
            }
            Box(Modifier.height(SECTION_BOTTOM))
        }
    }

    @Composable
    internal fun ShotCard(card: Card?, image: ImageBitmap?, mask: NameMask?, model: ShotModel, marker: Marker?) {
        val c = Mu.colors
        Box(Modifier.fillMaxSize().background(c.ink06).clipToBounds()) {
            if (image == null || card == null) {
                Hatch(Modifier.fillMaxSize(), live = false, color = c.ink25)
                if (card != null) {
                    Box(Modifier.align(Alignment.Center).padding(6.dp).background(c.paper).padding(3.dp)) {
                        Small(card.name, color = c.ink, maxLines = 4)
                    }
                }
            } else {
                val frame = ArtFrame.of(card.frameType)
                Image(
                    bitmap = image,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        // The foil at rest: the light straight on, as a card lies on a table.
                        drawFoil(model.foil, Offset.Zero, frame)
                        if (mask != null && model.foil == Foils.HOLO && model.names != NameStyles.PRINTED) {
                            drawFoilName(mask, Offset.Zero, outlined = model.names == NameStyles.OUTLINE)
                        }
                    },
                )
            }
            if (card != null) {
                val ban = card.banStatus(model.format)
                if (ban != BanStatus.UNLIMITED) {
                    Inverted {
                        Box(
                            Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).background(Mu.colors.paper),
                            contentAlignment = Alignment.Center,
                        ) {
                            Mono(ban.maxCopies.toString(), color = Mu.colors.ink, size = 11.sp, align = TextAlign.Center)
                        }
                    }
                }
            }
            if (marker != null) MarkerChip(marker, Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
    }

}
