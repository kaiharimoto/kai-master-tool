package com.kaiharimoto.neue.shot

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.deck.LensKeying
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.BandLayout
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.deck.BanSource
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.remote.CardSetRelease
import com.kaiharimoto.neue.cards.Marker
import com.kaiharimoto.neue.cards.MarkerChip
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.NameMask
import com.kaiharimoto.neue.cards.NameStyles
import com.kaiharimoto.neue.cards.drawFoil
import com.kaiharimoto.neue.cards.drawFoilName
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuTheme
import org.jetbrains.skia.EncodedImageFormat

/** One section of the picture: its cards in deck order, and its lens when the groups are showing. */
data class ShotSection(
    val section: DeckSection,
    val cards: List<Card?>,
    val keying: LensKeying?,
    /** The main deck's bands of group blocks, when the builder shows them (1.0.37), else null: rows as they read. */
    val bands: BandLayout? = null,
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
    /** The picture's shape: the Settings page's choice. */
    val style: ShotStyle = ShotStyle.PICTURE,
    /** The rules the builder checks against, for the limit marks (finding 1); null is the pool's list in [format]. */
    val limits: BanSource? = null,
)

/**
 * The picture a deck is shared as: main, extra and side, as they stand, with
 * none of the window around them — no pool, no buttons, no inspector. What the
 * builder was *showing* stays: if the groups were on, the deck is in their
 * pieces with each group's name on its tab, as the builder draws it.
 *
 * Two shapes (1.0.23, kai's pick from four): the **picture** ([ShotStyle.PICTURE]),
 * the default, and the **decklist** ([ShotStyle.LIST]). Both are laid out by
 * arithmetic before anything is drawn ([ShotDesigns.plan]), so the image is
 * exactly as tall as its contents.
 */
object DeckShot {

    /** The sections the picture carries: main always, extra and side when they have anything in them. */
    fun shown(model: ShotModel) = model.sections.filter { it.section == DeckSection.MAIN || it.cards.isNotEmpty() }

    /** Draws [model] to a PNG. [images] holds the pictures by card id; a card without one is drawn as the hatch. */
    fun render(model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask> = emptyMap()): ByteArray {
        val plan = ShotDesigns.plan(model)
        val density = ShotDesigns.density(model.style)
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
                val ban = model.limits?.statusOf(card) ?: card.banStatus(model.format)
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
