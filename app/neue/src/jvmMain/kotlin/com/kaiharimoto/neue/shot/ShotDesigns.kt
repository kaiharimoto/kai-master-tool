package com.kaiharimoto.neue.shot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.DeckList
import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.neue.builder.Labels
import com.kaiharimoto.neue.builder.drawPieces
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.cards.NameMask
import com.kaiharimoto.neue.kit.Mark
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.MuColors
import com.kaiharimoto.neue.theme.MuType

/**
 * The shapes the deck's picture takes (1.0.23). Four were explored — the
 * builder's picture, copies stacked with a count, groups as packed blocks, and a
 * decklist — and kai kept the first as the default and the last as the option.
 */
enum class ShotStyle(val pref: String) {
    /** The builder's picture: every copy, ten across, in the groups' pieces with their name tabs. The default. */
    PICTURE(NeuePreferences.SHOT_PICTURE),

    /** A decklist: each card's art, count and name in rows under its group, in two columns. */
    LIST(NeuePreferences.SHOT_LIST),
    ;

    companion object {
        fun of(pref: String) = entries.firstOrNull { it.pref == pref } ?: PICTURE
    }
}

// ---- The plan: everything the picture draws, placed, in dp, before anything is drawn ----

/** The picture's type faces: the kit's, sized by the plan. [HEADING] is a section's name, set without the micro caps' tracking. */
internal enum class Face { DISPLAY, HEADING, ROW, MICRO, MONO, WORDMARK }

internal sealed interface Placed {
    val x: Float
    val y: Float
    val w: Float
    val h: Float
}

internal data class PCard(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val card: Card?) : Placed
internal data class PText(
    override val x: Float, override val y: Float, override val w: Float, override val h: Float,
    val text: String, val face: Face, val size: Float, val color: Color, val align: TextAlign = TextAlign.Start,
) : Placed
internal data class PFill(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val color: Color) : Placed
internal data class PArt(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val card: Card?) : Placed
internal data class PMark(override val x: Float, override val y: Float, override val w: Float, override val h: Float) : Placed
internal data class PPieces(
    override val x: Float, override val y: Float, override val w: Float, override val h: Float,
    val keys: List<String?>, val pieces: PieceLayout, val cells: List<Offset>, val cardW: Float, val cardH: Float,
    val frame: Float, val tab: Float, val labelSize: Float, val colors: Map<String, Color>, val names: Map<String, String>,
) : Placed

internal class Plan(val width: Float, val height: Float, val items: List<Placed>)

/** A group as the picture shows it: its name and its colour (ink for the kinds a deck without groups is split by). */
private data class Group(val name: String, val color: Color)

private class Builder(val width: Float, val model: ShotModel) {
    val c = MuColors.of(model.ink)
    val u = width / 1080f
    val pad = 40f * u
    val inner = width - pad * 2
    val items = ArrayList<Placed>()
    var y = 0f

    fun text(x: Float, y: Float, w: Float, h: Float, s: String, face: Face, size: Float, color: Color = c.ink, align: TextAlign = TextAlign.Start) {
        items += PText(x, y, w, h, s, face, size, color, align)
    }

    fun fill(x: Float, y: Float, w: Float, h: Float, color: Color) {
        items += PFill(x, y, w, h, color)
    }

    fun header() {
        y = pad
        text(pad, y, inner, 72f * u, model.name.ifBlank { "Untitled deck" }, Face.DISPLAY, 60f * u)
        y += 84f * u
        val counts = model.sections.joinToString("   ") { "${it.cards.size} ${it.section.displayName}" }
        text(pad, y, inner, 36f * u, "$counts   ·   ${model.format.name}   ·   ${model.date}", Face.ROW, 26f * u, c.ink70)
        y += 38f * u
        model.latestSet?.let { set ->
            val code = if (set.code.isNotBlank()) " (${set.code})" else ""
            text(pad, y, inner, 30f * u, "Latest set: ${set.name}$code", Face.ROW, 21f * u, c.ink45)
            y += 32f * u
        }
        y += 18f * u
        fill(pad, y, inner, 2f * u, c.ink)
        y += 2f * u + 26f * u
    }

    fun heading(x: Float, w: Float, section: DeckSection, count: Int) {
        // kai (1.0.24): micro caps' tracking spread "Main deck" too wide, so the heading face.
        text(x, y, w * 0.7f, 32f * u, "${section.displayName} deck", Face.HEADING, 24f * u)
        text(x + w * 0.7f, y, w * 0.3f, 32f * u, count.toString(), Face.MONO, 22f * u, c.ink45, TextAlign.End)
    }

    fun footer() {
        y += 8f * u
        fill(pad, y, inner, 1f * u, c.ink25)
        y += 1f * u
        val h = 72f * u
        items += PMark(pad, y + (h - 22f * u) / 2f, 22f * u, 22f * u)
        text(pad + 32f * u, y + (h - 26f * u) / 2f, inner / 2f, 26f * u, "NEUE MASTER TOOL", Face.WORDMARK, 20f * u)
        text(pad + inner / 2f, y + (h - 26f * u) / 2f, inner / 2f, 26f * u, "Card images from YGOPRODeck", Face.ROW, 18f * u, c.ink45, TextAlign.End)
        y += h
    }

    fun plan() = Plan(width, y, items)

    /**
     * The section's groups, by its lens when it has one, else by kind: Monsters,
     * Spells and Traps, the way every decklist is quoted (kai, 1.0.23).
     */
    fun groupsOf(s: ShotSection): Pair<List<String?>, Map<String, Group>> {
        val keying = s.keying?.takeIf { !it.isEmpty }
        if (keying != null) {
            val keys = List(s.cards.size) { keying.keyAt(it) }
            val groups = keying.keys.associate { it.id to Group(it.label, GroupMarkers.paint(it.paint, c.ink)) }
            return keys to groups
        }
        val keys = s.cards.map { card -> card?.let { DeckList.kindOf(it.type).name } }
        val present = keys.toSet()
        return keys to DeckList.Kind.entries.filter { it.name in present }.associate { it.name to Group(it.label, c.ink) }
    }

    /** A section's cards with the copies collapsed, each with its card. */
    fun stacksOf(s: ShotSection, keys: List<String?>): List<Pair<Card?, DeckList.Stack>> =
        DeckList.stacks(s.cards.map { it?.id?.value }, keys).map { s.cards[it.first] to it }
}

private fun labelInk(color: Color) = if (color.luminance() > 0.5f) Color.Black else Color.White

/**
 * Lays [cells] out as the builder does: in rows of [columns], broken into pieces by [keys]
 * when there are any — or in the builder's bands of group blocks ([bands], 1.0.37), [gap]
 * across and [gapY] down between them.
 */
private fun Builder.grid(
    cells: List<Card?>, keys: List<String?>?, columns: Int, cardW: Float, gap: Float, frame: Float, tab: Float,
    labelSize: Float, colors: Map<String, Color>, names: Map<String, String>,
    bands: PieceLayout? = null, gapY: Float = gap,
) {
    val pieces = bands ?: if (keys != null && keys.any { it != null }) GroupPieces.of(keys, columns) else null
    val cardH = cardW / CARD_RATIO
    val room = if (pieces != null) tab else 0f
    val rows = pieces?.rowCount ?: ((cells.size + columns - 1) / columns)
    val top = y + room
    val offsets = cells.indices.map { i ->
        Offset(
            pad + (pieces?.col(i) ?: (i % columns)) * cardW + (pieces?.shiftX?.get(i) ?: 0) * gap,
            top + (pieces?.row(i) ?: (i / columns)) * cardH + (pieces?.shiftY?.get(i) ?: 0) * gapY,
        )
    }
    val h = room + rows * cardH + (pieces?.spanY ?: 0) * gapY
    if (pieces != null && keys != null) {
        items += PPieces(0f, 0f, width, y + h + gap, keys, pieces, offsets, cardW, cardH, frame, tab, labelSize, colors, names)
    }
    cells.forEachIndexed { i, card -> items += PCard(offsets[i].x, offsets[i].y, cardW, cardH, card) }
    y += h
}

internal object ShotDesigns {

    fun plan(model: ShotModel): Plan = when (model.style) {
        ShotStyle.PICTURE -> picture(model)
        ShotStyle.LIST -> list(model)
    }

    /** Pixels per dp each is drawn at: both come out a little over 3000 pixels wide. */
    fun density(style: ShotStyle) = if (style == ShotStyle.PICTURE) 2f else 3f

    private fun columnsOf(section: DeckSection) = if (section == DeckSection.MAIN) 10 else 15

    /** The builder's picture, 1600 wide: every copy, the groups' pieces and name tabs, the builder's proportions. */
    private fun picture(model: ShotModel): Plan {
        val b = Builder(1600f, model)
        b.header()
        val mainW = b.inner / 10f
        val gap = mainW * 0.24f
        val tab = gap * 0.8f
        DeckShot.shown(model).forEach { s ->
            b.heading(b.pad, b.inner, s.section, s.cards.size)
            b.y += 44f * b.u
            val keying = s.keying?.takeIf { !it.isEmpty }
            val keys = keying?.let { k -> List(s.cards.size) { k.keyAt(it) } }
            val bands = s.bands?.takeIf { keys != null }?.pieces()
            val cols = bands?.columns ?: columnsOf(s.section)
            val frame = mainW * 0.035f
            // Fitted and Separate both stand a whole gap apart (1.0.40).
            val gapX = gap
            val gapY = gap
            val span = bands?.spanX ?: keys?.let { GroupPieces.of(it, cols).spanX } ?: 0
            val cardW = (b.inner - gapX * span) / cols
            val colors = keying?.keys?.associate { it.id to GroupMarkers.paint(it.paint, b.c.ink) }.orEmpty()
            val names = keying?.keys?.associate { it.id to it.label }.orEmpty()
            if (s.cards.isEmpty()) {
                b.text(b.pad, b.y, b.inner, 40f, "Empty", Face.ROW, 22f * b.u, b.c.ink45)
                b.y += 40f
            }
            b.grid(s.cards, keys, cols, cardW, gapX, frame, tab, tab * 0.6f, colors, names, bands, gapY)
            b.y += 36f * b.u
        }
        b.footer()
        return b.plan()
    }

    /**
     * A decklist, 1080 wide so its words read on a phone: each card once with its art
     * (the art window, squared), its count and its name, under its group's bar — or,
     * without groups, under Monsters, Spells and Traps. The main deck in two columns,
     * then the extra deck beside the side deck.
     */
    private fun list(model: ShotModel): Plan {
        val b = Builder(1080f, model)
        b.header()
        val colGap = 36f
        val colW = (b.inner - colGap) / 2f
        val row = 62f
        val bar = 40f
        val between = 14f

        class Block(val group: Group, val stacks: List<Pair<Card?, DeckList.Stack>>) {
            val height get() = bar + stacks.size * row + between
        }

        fun blocksOf(s: ShotSection): List<Block> {
            val (keys, groups) = b.groupsOf(s)
            val st = b.stacksOf(s, keys)
            // The groups in their order, then any card in none.
            return (groups.keys.toList() + listOf<String?>(null)).mapNotNull { k ->
                val mine = st.filter { it.second.key == k }
                if (mine.isEmpty()) null else Block(groups[k] ?: Group("Other", b.c.ink), mine)
            }
        }

        fun draw(x: Float, top: Float, blocks: List<Block>): Float {
            var y = top
            blocks.forEach { block ->
                val ink = labelInk(block.group.color)
                b.fill(x, y, colW, bar, block.group.color)
                b.text(x + 12f, y, colW - 80f, bar, block.group.name.uppercase(), Face.MICRO, 19f, ink)
                b.text(x + colW - 68f, y, 56f, bar, block.stacks.sumOf { it.second.count }.toString(), Face.MONO, 21f, ink, TextAlign.End)
                y += bar
                block.stacks.forEach { (card, st) ->
                    b.items += PArt(x, y, row, row, card)
                    b.text(x + row + 12f, y, 44f, row, "${st.count}", Face.MONO, 26f, b.c.ink)
                    val ban = card?.banStatus(model.format)
                    val banned = ban != null && ban != BanStatus.UNLIMITED
                    b.text(x + row + 56f, y, colW - row - 56f - (if (banned) 40f else 0f), row, card?.name ?: "Unknown card", Face.ROW, 22f, b.c.ink)
                    if (banned) {
                        b.fill(x + colW - 30f, y + (row - 30f) / 2f, 30f, 30f, b.c.ink)
                        b.text(x + colW - 30f, y + (row - 30f) / 2f, 30f, 30f, ban!!.maxCopies.toString(), Face.MONO, 18f, b.c.paper, TextAlign.Center)
                    }
                    b.fill(x, y + row - 1f, colW, 1f, b.c.ink12)
                    y += row
                }
                y += between
            }
            return y
        }

        val shown = DeckShot.shown(model)
        val main = shown.first { it.section == DeckSection.MAIN }
        b.heading(b.pad, b.inner, main.section, main.cards.size)
        b.y += 42f
        // The main deck's blocks split into two columns as evenly as whole blocks allow.
        val mb = blocksOf(main)
        val best = DeckList.split(mb.map { it.height })
        val top = b.y
        val l = draw(b.pad, top, mb.take(best))
        val r = draw(b.pad + colW + colGap, top, mb.drop(best))
        b.y = maxOf(l, r) + 12f
        val rest = shown.filter { it.section != DeckSection.MAIN }
        if (rest.isNotEmpty()) {
            val start = b.y
            var bottom = start
            rest.forEachIndexed { i, s ->
                val x = b.pad + i * (colW + colGap)
                b.y = start
                b.heading(x, colW, s.section, s.cards.size)
                bottom = maxOf(bottom, draw(x, start + 42f, blocksOf(s)))
            }
            b.y = bottom + 12f
        }
        b.footer()
        return b.plan()
    }

    @Composable
    fun Picture(plan: Plan, model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask>) {
        val c = MuColors.of(model.ink)
        val f = LocalMuFonts.current
        val measurer = rememberTextMeasurer()
        Box(Modifier.fillMaxSize().background(c.paper)) {
            plan.items.forEach { item ->
                val at = Modifier.offset(item.x.dp, item.y.dp).size(item.w.dp, item.h.dp)
                when (item) {
                    is PFill -> Box(at.background(item.color))
                    is PMark -> Mark(item.w.dp, ink = c.ink, paper = c.paper, modifier = Modifier.offset(item.x.dp, item.y.dp))
                    is PText -> Box(at, contentAlignment = Alignment.CenterStart) {
                        val style = when (item.face) {
                            Face.DISPLAY -> MuType.display(f)
                            Face.HEADING -> MuType.h2(f)
                            Face.ROW -> MuType.row(f)
                            Face.MICRO -> MuType.micro(f)
                            Face.MONO -> MuType.mono(f)
                            Face.WORDMARK -> MuType.wordmark(f)
                        }.copy(fontSize = item.size.sp, lineHeight = (item.size * 1.2f).sp)
                        MuText(item.text, Modifier.size(item.w.dp, (item.size * 1.3f).dp), style, item.color, maxLines = 1, align = item.align)
                    }
                    is PCard -> Box(at) {
                        DeckShot.ShotCard(item.card, images[item.card?.id?.value], masks[item.card?.id?.value], model, null)
                    }
                    is PArt -> Canvas(at) {
                        val card = item.card
                        val image = card?.let { images[it.id.value] }
                        if (image == null) {
                            drawRect(c.ink06)
                            return@Canvas
                        }
                        val frame = ArtFrame.of(card.frameType) ?: ArtFrame.STANDARD
                        val l = (frame.left + frame.bevel) * image.width
                        val t = (frame.top + frame.bevel * image.width / image.height) * image.height
                        val r = (frame.right - frame.bevel) * image.width
                        val bt = (frame.bottom - frame.bevel * image.width / image.height) * image.height
                        // The art window, squared about its middle: a pendulum's is wider than tall.
                        val side = minOf(r - l, bt - t)
                        val sx = (l + r - side) / 2f
                        drawImage(
                            image,
                            srcOffset = IntOffset(sx.toInt(), t.toInt()),
                            srcSize = IntSize(side.toInt(), side.toInt()),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                    is PPieces -> Canvas(at) {
                        val k = density
                        drawPieces(
                            keys = item.keys,
                            pieces = item.pieces,
                            at = { i -> Offset(item.cells[i].x * k, item.cells[i].y * k) },
                            cardWidth = item.cardW * k,
                            cardHeight = item.cardH * k,
                            frame = item.frame * k,
                            colorOf = { id -> item.colors[id] ?: c.ink },
                            alphaOf = { 1f },
                            labels = Labels(measurer, MuType.micro(f).copy(fontSize = item.labelSize.sp), item.tab * k) { id -> item.names[id].orEmpty() },
                        )
                    }
                }
            }
        }
    }
}
