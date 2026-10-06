package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.kaiharimoto.neue.kit.keepsPresses
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyType
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftBody
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftCatalog
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftCollection
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftItem
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftKind
import com.kaiharimoto.mastertool.core.ai.chessy.gifts.GiftMeshes
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The chest's drawer (kai, 1.1.31: "once received, they're collected and the user can open them in a chest drawer
 * anytime. For items not yet unlocked, gray out their silhouette with a question mark, and add a progress tracker"): every
 * gift she can give, in 3D, by kind — the keepsakes, the Maliss cards, the notes — what is yours drawn as it is, what is
 * not yet a grey silhouette with a "?". Looking at one (hover, a tap) turns it slowly and shows what it is and what she
 * said giving it; **Take out** puts it back in the room to play with. Paper and ink round the gifts; Esc, Back or Close
 * shuts it.
 */
@Composable
internal fun GiftDrawer(
    catalog: GiftCatalog,
    collection: GiftCollection,
    cards: (Int) -> Card?,
    compact: Boolean,
    name: String,
    onTakeOut: (GiftItem) -> Unit,
    onClose: () -> Unit,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val progress = remember(catalog, collection) { collection.progress(catalog) }
    var looked by remember { mutableStateOf(catalog.all.firstOrNull { collection.owned(it.id) } ?: catalog.all.first()) }
    var frame by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf(0f) }
    // the one gift looked at turns: a frame loop while the drawer is open
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) withFrameNanos { clock = (it - start) / 1e9f; frame++ }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper.copy(alpha = .86f))
            // nothing under the drawer hears a press while it is open, and its own scroll and buttons keep theirs
            .keepsPresses(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(if (compact) 10.dp else 32.dp)
                .widthIn(max = 1040.dp)
                .fillMaxWidth()
                .fillMaxHeight(if (compact) .96f else .9f)
                .background(c.paper)
                .border(2.dp, c.ink)
                .padding(if (compact) 14.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // the head: what it is, how much is yours, and Close
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Micro("Keepsakes", color = c.ink45)
                    MuText("$name's gifts", style = MuType.h2(f), color = c.ink)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Mono("${progress.have} / ${progress.of}", color = c.ink, size = 14.sp)
                    Box(
                        Modifier.width(if (compact) 96.dp else 160.dp).height(6.dp).border(1.dp, c.ink).drawBehind {
                            drawRect(c.ink, size = Size(size.width * progress.share, size.height))
                        },
                    )
                }
                MuButton("Close", onClose, size = BtnSize.SM)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (k in listOf(GiftKind.HEART, GiftKind.PHOTO, GiftKind.CUPCAKE, GiftKind.CARD, GiftKind.NOTE)) {
                    val (have, of) = progress.perKind[k] ?: (0 to 0)
                    if (of > 0 && (!compact || k == GiftKind.CARD || k == GiftKind.NOTE)) Mono("${k.title} $have/$of", color = c.ink70)
                }
                if (!compact) { Spacer(Modifier.weight(1f)); Mono("${progress.received} given", color = c.ink45) }
            }
            val grid: @Composable (Modifier) -> Unit = { mod ->
                BoxWithConstraints(mod) {
                    val tile = if (compact) 92.dp else 104.dp
                    val gap = 8.dp
                    val across = max(1, ((maxWidth + gap) / (tile + gap)).toInt())
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        for ((title, items) in listOf(
                            "Keepsakes" to GiftCatalog.KEEPSAKES,
                            GiftKind.CARD.title to catalog.ofKind(GiftKind.CARD),
                            GiftKind.NOTE.title to catalog.ofKind(GiftKind.NOTE),
                        )) {
                            if (items.isEmpty()) continue
                            Micro(title, color = c.ink70)
                            for (row in items.chunked(across)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    for (item in row) GiftTile(item, collection, cards, looked.id == item.id, tile, { frame }, { clock }) { looked = item }
                                }
                            }
                        }
                    }
                }
            }
            val detail: @Composable (Modifier) -> Unit = { mod -> GiftDetail(looked, collection, cards, compact, mod, onTakeOut) }
            if (compact) {
                grid(Modifier.weight(1f).fillMaxWidth())
                detail(Modifier.fillMaxWidth().heightIn(max = 260.dp))
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    grid(Modifier.weight(1f).fillMaxHeight())
                    detail(Modifier.width(320.dp).fillMaxHeight())
                }
            }
        }
    }
}

/** One gift in the drawer: in 3D, turning while looked at; a grey silhouette and a "?" while not yet yours; how many. */
@Composable
private fun GiftTile(item: GiftItem, collection: GiftCollection, cards: (Int) -> Card?, looked: Boolean, side: androidx.compose.ui.unit.Dp, frame: () -> Int, clock: () -> Float, look: () -> Unit) {
    val c = Mu.colors
    val owned = collection.owned(item.id)
    val rest = remember(item.kind) { GiftBody.restOf(item.kind) }
    Box(
        Modifier
            .size(side)
            .border(if (looked) 2.dp else 1.dp, if (looked) c.ink else c.ink12)
            .cursorPointer(caption = if (owned) "Look" else "Not yet")
            .pointerInput(item.id) { awaitPointerEventScope { while (true) { if (awaitPointerEvent().type == PointerEventType.Enter) look() } } }
            .muClickable(onClick = look),
        contentAlignment = Alignment.Center,
    ) {
        GiftSolid(
            GiftMeshes.of(item.kind), item, Modifier.fillMaxSize().padding(8.dp),
            pose = { if (looked) turning(clock(), rest) else rest },
            frame = { if (looked) frame() else 0 },
            clock = { if (looked) clock() else 0f },
            fill = .78f,
            silhouette = !owned,
            card = if (owned && item.kind == GiftKind.CARD) cards(item.passcode) else null,
        )
        if (!owned) MuText("?", style = MuType.h2(LocalMuFonts.current), color = c.ink45)
        else if (collection.times(item.id) > 1) Mono("×${collection.times(item.id)}", Modifier.align(Alignment.BottomEnd).padding(4.dp), color = c.ink70)
    }
}

/** A slow turn about the upright, from [rest]: the gift looked at shows every side. */
internal fun turning(t: Float, rest: Quat): Quat {
    val a = t * .7 / 2
    return Quat(cos(a), 0.0, sin(a), 0.0) * rest
}

/** What the gift looked at is: its name, what it is, how often she has given it, and what she said, in her box. */
@Composable
private fun GiftDetail(item: GiftItem, collection: GiftCollection, cards: (Int) -> Card?, compact: Boolean, modifier: Modifier, onTakeOut: (GiftItem) -> Unit) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val owned = collection.owned(item.id)
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro(item.kind.title, color = c.ink45)
        MuText(if (owned) item.name else "Not yet received", style = MuType.h2(f), color = c.ink)
        if (owned) {
            MuText(item.description, style = MuType.body(f), color = c.ink70)
            Mono(if (collection.times(item.id) == 1) "Received once" else "Received ×${collection.times(item.id)}", color = c.ink45)
            val units = remember(item.give) { ChessyType.layout(item.give).units }
            ChessySay(item.give, units, CHESSY_NAME, 0f, caretOn = { false }, textSize = if (compact) 15.sp else 16.sp)
            MuButton("Take out", { onTakeOut(item) }, variant = BtnVariant.PRIMARY, size = if (compact) BtnSize.SM else BtnSize.MD)
        } else {
            MuText("Fill her hearts and she might make you this one. Every gift is a surprise.", style = MuType.body(f), color = c.ink70)
        }
    }
}

