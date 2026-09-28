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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.GroupPieces
import com.kaiharimoto.mastertool.core.layout.PieceLayout
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.DeckSection
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

/** The shapes the shared picture can take (the 1.0.22 exploration): kai picks one. */
enum class ShotStyle(val title: String) {
    /** The builder's picture, fixed: every copy, ten across, the pieces with their name tabs. */
    BUILDER("Builder"),

    /** Copies stacked into one card with a count, in the builder's pieces, sized for a phone. */
    STACKS("Stacks"),

    /** Each group a labelled block of its stacks, the blocks packed into lines. */
    BLOCKS("Blocks"),

    /** A decklist: each card's art, count and name in rows under its group, two columns. */
    LIST("List"),
}

/** [count] copies of [card], the stack a run of copies collapses into, in group [key]. */
internal data class Stack(val card: Card?, val count: Int, val key: String?)

/** A section's cards with the copies of each collapsed into one, in the order each first appears. */
internal fun stacksOf(cards: List<Card?>, keys: List<String?>): List<Stack> {
    val order = LinkedHashMap<Pair<Int, String?>, Stack>()
    cards.forEachIndexed { i, card ->
        val id = card?.id?.value ?: -(i + 1)
        val k = id to keys.getOrNull(i)
        order[k] = order[k]?.let { it.copy(count = it.count + 1) } ?: Stack(card, 1, keys.getOrNull(i))
    }
    return order.values.toList()
}

// ---- The plan: everything the picture draws, placed, in dp, before anything is drawn ----

internal enum class Face { DISPLAY, ROW, MICRO, MONO, WORDMARK }

internal sealed interface Placed {
    val x: Float
    val y: Float
    val w: Float
    val h: Float
}

internal data class PCard(override val x: Float, override val y: Float, override val w: Float, override val h: Float, val card: Card?, val count: Int, val badge: Float) : Placed
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

/** A group as the picture shows it: its name, its colour, and whether that colour is the user's (else ink). */
private data class Group(val id: String?, val name: String, val color: Color)

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
        text(x, y, w * 0.7f, 32f * u, "${section.displayName} deck", Face.MICRO, 22f * u)
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

    /** The section's groups, by its lens when it has one, else by the kind of card. */
    fun groupsOf(s: ShotSection): Pair<List<String?>, Map<String, Group>> {
        val keying = s.keying?.takeIf { !it.isEmpty }
        if (keying != null) {
            val keys = List(s.cards.size) { keying.keyAt(it) }
            val groups = keying.keys.associate { it.id to Group(it.id, it.label, GroupMarkers.paint(it.paint, c.ink)) }
            return keys to groups
        }
        val keys = s.cards.map { kindOf(it) }
        val kinds = listOf("Monsters", "Spells", "Traps", "Fusion", "Synchro", "Xyz", "Link")
        return keys to keys.filterNotNull().distinct().sortedBy { kinds.indexOf(it) }.associateWith { Group(null, it, c.ink) }
    }

    /** Monster, Spell, Trap in the main and side decks; Fusion, Synchro, Xyz, Link in the extra. */
    fun kindOf(card: Card?): String? {
        card ?: return null
        val t = card.type.lowercase()
        return when {
            "spell" in t -> "Spells"
            "trap" in t -> "Traps"
            "fusion" in t -> "Fusion"
            "synchro" in t -> "Synchro"
            "xyz" in t -> "Xyz"
            "link" in t -> "Link"
            else -> "Monsters"
        }
    }
}

private fun labelInk(color: Color) = if (color.luminance() > 0.5f) Color.Black else Color.White

/** Lays [plan]'s cards out as the builder does: in rows of [columns], broken into pieces by [keys]. */
private fun Builder.grid(
    cells: List<Pair<Card?, Int>>, keys: List<String?>?, columns: Int, cardW: Float, gap: Float, frame: Float, tab: Float,
    labelSize: Float, colors: Map<String, Color>, names: Map<String, String>, badge: Float,
) {
    val pieces = if (keys != null && keys.any { it != null }) GroupPieces.of(keys, columns) else null
    val cardH = cardW / CARD_RATIO
    val room = if (pieces != null) tab else 0f
    val rows = (cells.size + columns - 1) / columns
    val top = y + room
    val offsets = cells.indices.map { i ->
        Offset(
            pad + (i % columns) * cardW + (pieces?.shiftX?.get(i) ?: 0) * gap,
            top + (i / columns) * cardH + (pieces?.shiftY?.get(i) ?: 0) * gap,
        )
    }
    val h = room + rows * cardH + (pieces?.spanY ?: 0) * gap
    if (pieces != null && keys != null) {
        items += PPieces(0f, 0f, width, y + h + gap, keys, pieces, offsets, cardW, cardH, frame, tab, labelSize, colors, names)
    }
    cells.forEachIndexed { i, (card, count) -> items += PCard(offsets[i].x, offsets[i].y, cardW, cardH, card, count, badge) }
    y += h
}

internal object ShotDesigns {

    fun plan(style: ShotStyle, model: ShotModel): Plan = when (style) {
        ShotStyle.BUILDER -> builder(model)
        ShotStyle.STACKS -> stacks(model)
        ShotStyle.BLOCKS -> blocks(model)
        ShotStyle.LIST -> list(model)
    }

    /** Pixels per dp each is drawn at: every one comes out a little over 3000 pixels wide. */
    fun density(style: ShotStyle) = if (style == ShotStyle.BUILDER) 2f else 3f

    private fun columnsOf(section: DeckSection) = if (section == DeckSection.MAIN) 10 else 15

    private fun builder(model: ShotModel): Plan {
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
            val cols = columnsOf(s.section)
            val span = keys?.let { GroupPieces.of(it, cols).spanX } ?: 0
            val cardW = (b.inner - gap * span) / cols
            val colors = keying?.keys?.associate { it.id to GroupMarkers.paint(it.paint, b.c.ink) }.orEmpty()
            val names = keying?.keys?.associate { it.id to it.label }.orEmpty()
            if (s.cards.isEmpty()) {
                b.text(b.pad, b.y, b.inner, 40f, "Empty", Face.ROW, 22f * b.u, b.c.ink45)
                b.y += 40f
            }
            b.grid(s.cards.map { it to 1 }, keys, cols, cardW, gap, mainW * 0.035f, tab, tab * 0.6f, colors, names, 0f)
            b.y += 36f * b.u
        }
        b.footer()
        return b.plan()
    }

    /** Copies stacked, in the builder's pieces, with as many columns as puts the whole deck on one phone screen. */
    private fun stacks(model: ShotModel): Plan {
        fun build(cols: Int): Plan {
            val b = Builder(1080f, model)
            b.header()
            val gap = 34f
            val tab = 30f
            val sections = DeckShot.shown(model).map { s ->
                val keying = s.keying?.takeIf { !it.isEmpty }
                val keys = keying?.let { k -> List(s.cards.size) { k.keyAt(it) } } ?: List(s.cards.size) { null }
                // Each group's stacks together, in the groups' order, so every group is one clean piece.
                val order = keying?.keys?.map { it.id }.orEmpty()
                Triple(s, keying, stacksOf(s.cards, keys).sortedBy { st -> order.indexOf(st.key).let { if (it < 0) order.size else it } })
            }
            // One card size for every section: the narrowest the pieces leave.
            val cardW = sections.minOf { (_, keying, st) ->
                val span = if (keying != null) GroupPieces.of(st.map { it.key }, cols).spanX else 0
                (b.inner - gap * span) / cols
            }
            sections.forEach { (s, keying, st) ->
                b.heading(b.pad, b.inner, s.section, s.cards.size)
                b.y += 42f
                val colors = keying?.keys?.associate { it.id to GroupMarkers.paint(it.paint, b.c.ink) }.orEmpty()
                val names = keying?.keys?.associate { it.id to it.label }.orEmpty()
                b.grid(st.map { it.card to it.count }, if (keying != null) st.map { it.key } else null, cols, cardW, gap, 5f, tab, 19f, colors, names, 30f)
                b.y += 34f
            }
            b.footer()
            return b.plan()
        }
        // A phone held upright is about 9 by 19; aim a little shorter so it sits whole with the chrome round it.
        return (5..9).map(::build).minBy { kotlin.math.abs(it.height / it.width - 1.7f) }
    }

    /** Each group a block: its name on a bar in its colour, its stacks flush beneath, the blocks packed into lines. */
    private fun blocks(model: ShotModel): Plan {
        val b = Builder(1080f, model)
        b.header()
        val perLine = 7
        val cardW = b.inner / perLine
        val cardH = cardW / CARD_RATIO
        val gap = 22f
        val bar = 38f
        DeckShot.shown(model).forEach { s ->
            b.heading(b.pad, b.inner, s.section, s.cards.size)
            b.y += 42f
            val (keys, groups) = b.groupsOf(s)
            val st = stacksOf(s.cards, keys)
            val order = groups.keys.toList() + listOf<String?>(null)
            val blocks = order.mapNotNull { k ->
                val mine = st.filter { it.key == k }
                if (mine.isEmpty()) null else Triple(k, mine, groups[k] ?: Group(null, "Other", b.c.ink))
            }
            // First fit: a block goes into the first line with room, so a short one fills a gap left above.
            data class Line(var used: Float, var height: Float, val placed: MutableList<Pair<Float, Int>>)
            val lines = ArrayList<Line>()
            blocks.forEachIndexed { i, (_, mine, _) ->
                val cols = minOf(mine.size, perLine)
                val w = cols * cardW
                val h = bar + ((mine.size + perLine - 1) / perLine) * cardH
                val line = lines.firstOrNull { it.used + gap + w <= b.inner + 0.5f && it.placed.isNotEmpty() }
                    ?: Line(-gap, 0f, ArrayList()).also { lines += it }
                line.placed += (line.used + gap) to i
                line.used += gap + w
                line.height = maxOf(line.height, h)
            }
            lines.forEach { line ->
                line.placed.forEach { (x0, i) ->
                    val (_, mine, group) = blocks[i]
                    val x = b.pad + x0
                    val cols = minOf(mine.size, perLine)
                    b.fill(x, b.y, cols * cardW, bar, group.color)
                    val ink = labelInk(group.color)
                    val count = mine.sumOf { it.count }.toString()
                    b.text(x + 10f, b.y, cols * cardW - 64f, bar, group.name.uppercase(), Face.MICRO, 18f, ink)
                    b.text(x + cols * cardW - 54f, b.y, 44f, bar, count, Face.MONO, 20f, ink, TextAlign.End)
                    mine.forEachIndexed { j, stack ->
                        b.items += PCard(x + (j % perLine) * cardW, b.y + bar + (j / perLine) * cardH, cardW, cardH, stack.card, stack.count, 28f)
                    }
                }
                b.y += line.height + gap
            }
            b.y += 16f
        }
        b.footer()
        return b.plan()
    }

    /** A decklist: art, count and name, under each group's bar, the main deck in two columns and extra beside side. */
    private fun list(model: ShotModel): Plan {
        val b = Builder(1080f, model)
        b.header()
        val colGap = 36f
        val colW = (b.inner - colGap) / 2f
        val row = 62f
        val bar = 40f
        val between = 14f

        class Block(val group: Group, val stacks: List<Stack>) {
            val height get() = bar + stacks.size * row + between
        }

        fun blocksOf(s: ShotSection): List<Block> {
            val (keys, groups) = b.groupsOf(s)
            val st = stacksOf(s.cards, keys)
            return (groups.keys.toList() + listOf<String?>(null)).mapNotNull { k ->
                val mine = st.filter { it.key == k }
                if (mine.isEmpty()) null else Block(groups[k] ?: Group(null, "Other", b.c.ink), mine)
            }
        }

        fun draw(x: Float, top: Float, blocks: List<Block>): Float {
            var y = top
            blocks.forEach { block ->
                val ink = labelInk(block.group.color)
                b.fill(x, y, colW, bar, block.group.color)
                b.text(x + 12f, y, colW - 80f, bar, block.group.name.uppercase(), Face.MICRO, 19f, ink)
                b.text(x + colW - 68f, y, 56f, bar, block.stacks.sumOf { it.count }.toString(), Face.MONO, 21f, ink, TextAlign.End)
                y += bar
                block.stacks.forEach { st ->
                    b.items += PArt(x, y, row, row, st.card)
                    b.text(x + row + 12f, y, 44f, row, "${st.count}", Face.MONO, 26f, b.c.ink)
                    val ban = st.card?.banStatus(model.format)
                    val banned = ban != null && ban != BanStatus.UNLIMITED
                    b.text(x + row + 56f, y, colW - row - 56f - (if (banned) 40f else 0f), row, st.card?.name ?: "Unknown card", Face.ROW, 22f, b.c.ink)
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
        val total = mb.sumOf { it.height.toDouble() }.toFloat()
        var best = mb.size
        var bestH = Float.MAX_VALUE
        for (k in 0..mb.size) {
            val left = mb.take(k).sumOf { it.height.toDouble() }.toFloat()
            val h = maxOf(left, total - left)
            if (h < bestH) { bestH = h; best = k }
        }
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
                            Face.ROW -> MuType.row(f)
                            Face.MICRO -> MuType.micro(f)
                            Face.MONO -> MuType.mono(f)
                            Face.WORDMARK -> MuType.wordmark(f)
                        }.copy(fontSize = item.size.sp, lineHeight = (item.size * 1.2f).sp)
                        MuText(item.text, Modifier.size(item.w.dp, (item.size * 1.3f).dp), style, item.color, maxLines = 1, align = item.align)
                    }
                    is PCard -> Box(at) {
                        DeckShot.ShotCard(item.card, images[item.card?.id?.value], masks[item.card?.id?.value], model, null)
                        if (item.count > 1 && item.badge > 0f) {
                            val side = item.badge
                            Box(
                                Modifier.align(Alignment.BottomEnd).size((side * 1.5f).dp, side.dp).background(c.ink),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuText("×${item.count}", style = MuType.mono(f).copy(fontSize = (side * 0.7f).sp, fontWeight = FontWeight.Bold), color = c.paper, maxLines = 1, align = TextAlign.Center)
                            }
                        }
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
