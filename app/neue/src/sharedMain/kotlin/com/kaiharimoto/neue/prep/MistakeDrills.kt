package com.kaiharimoto.neue.prep

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.duel.coach.MisplayDrills
import com.kaiharimoto.mastertool.core.prep.Drill
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/**
 * Drills from your own mistakes (Phase F, F.3) as the Prep page reaches them: the drills, a replay's name, and the position
 * shown on the Duel page. Only replays still kept, and only reviews that still line up with them.
 */
internal class MistakeSource(
    val items: List<MisplayDrills.Item>,
    val name: (String) -> String,
    val show: (MisplayDrills.Item) -> Unit,
)

@Composable
internal fun rememberMistakes(h: NeueHolders): MistakeSource {
    val reviews = h.reviews
    LaunchedEffect(Unit) {
        reviews.load()
        h.duel.loadReplays()
    }
    val books = reviews.books
    val replays = h.duel.replays
    val items = remember(books, replays) {
        val kept = replays.associateBy { it.id }
        MisplayDrills.items(books.values.filter { b -> kept[b.replay]?.let { b.fits(it.entries) } == true })
    }
    return MistakeSource(items, { id -> replays.firstOrNull { it.id == id }?.name ?: "a replay" }) { item ->
        h.duel.openReplay(item.replay, item.at)
        h.neue.go(Page.DUEL)
    }
}

/**
 * One drill from a mistake (F.3): where it was, the ways the search weighed there to choose from (what was played among
 * them), and once chosen, what each gives up — right within an inaccuracy of the best. Then how each is going.
 */
@Composable
internal fun MistakeDrill(prep: Prep, m: MistakeSource) {
    val c = Mu.colors
    val keys = m.items.map { it.key }
    var current by remember(keys) { mutableStateOf(Drill.next(keys, prep.doc.drills, System.currentTimeMillis())) }
    val item = m.items.firstOrNull { it.key == current } ?: m.items.first()
    var chosen by remember(item.key, current) { mutableStateOf<MisplayDrills.Choice?>(null) }
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Help(
            "Every mistake and blunder a review found in your duels, as a position to find the better way in. Right is within an " +
                "inaccuracy — 5 points of win chance — of the best way the app's own search found.",
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MuText(MisplayDrills.question(item, m.name(item.replay)), style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 3)
            Small("${item.mark.sign} ${item.mark.words} in your review · ${Drill.dueWords(prep.doc.drills[item.key], now)}", color = c.ink70)
            MuButton("Show the position", { m.show(item) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
        }
        val picked = chosen
        if (picked == null) {
            item.choices.forEachIndexed { i, ch ->
                Row(
                    Modifier.fillMaxWidth().border(1.dp, c.ink12)
                        .cursorPointer(caption = "Choose")
                        .muClickable {
                            chosen = ch
                            prep.answered(item.key, ch.right)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Mono("${i + 1}", color = c.ink45)
                    Small(ch.words, Modifier.weight(1f), color = c.ink)
                }
            }
        } else {
            MuText(
                if (picked.right) "Right." else "Not this one.",
                style = MuType.body(LocalMuFonts.current).copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                color = c.ink,
            )
            Small(MisplayDrills.verdict(item, picked), color = c.ink)
            item.choices.forEach { ch ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Mono(if (ch == picked) "▸" else " ", Modifier.width(12.dp), color = c.ink)
                    Small(ch.words, Modifier.weight(1f), color = if (ch.right) c.ink else c.ink70)
                    Mono(if (ch.drop == 0.0) "best" else "−${pts(ch.drop)}", color = if (ch.right) c.ink else c.ink45)
                }
            }
            MuButton("Next drill", {
                current = Drill.next(keys, prep.doc.drills, System.currentTimeMillis())
                chosen = null
            }, variant = BtnVariant.PRIMARY, arrow = true)
        }
        HRule()
        Micro("How your mistakes are going", color = c.ink70)
        m.items.forEach { it ->
            val stat = prep.doc.drills[it.key]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small("${it.mark.sign} " + MisplayDrills.question(it, m.name(it.replay)).substringBefore(":"), Modifier.weight(1f), color = c.ink, maxLines = 1)
                Mono(
                    if (stat == null || stat.seen == 0) "new" else "box ${stat.box} of ${Drill.TOP_BOX} · ${stat.correct}/${stat.seen} · ${Drill.dueWords(stat, now)}",
                    color = c.ink70,
                )
            }
        }
    }
}

private fun pts(x: Double): String = if (x == x.toInt().toDouble()) "${x.toInt()}" else "$x"
