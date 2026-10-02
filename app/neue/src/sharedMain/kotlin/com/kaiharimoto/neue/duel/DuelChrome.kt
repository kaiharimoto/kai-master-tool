package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.RequestFocusOnce
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/** The short names on the phase strip, as players say them. */
private val PHASE_SHORT = mapOf(
    DuelPhase.DRAW to "DP", DuelPhase.STANDBY to "SP", DuelPhase.MAIN1 to "M1",
    DuelPhase.BATTLE to "BP", DuelPhase.MAIN2 to "M2", DuelPhase.END to "EP",
)

/**
 * Each seat's bar: its name, its life points (a click opens the pad), whether it is that seat's turn,
 * whether it is thinking, and what is in its hand, deck and piles at a glance.
 */
@Composable
internal fun SeatBars(h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout) {
    val c = Mu.colors
    l.bars.forEach { (seat, slot) ->
        val st = s.seats[seat]
        val turn = s.active == seat
        Row(
            Modifier.zIndex(30f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp)
                .border(1.dp, if (turn) c.ink else c.ink12)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turn) {
                Box(Modifier.background(c.ink).padding(horizontal = 5.dp, vertical = 1.dp)) { Micro("Turn ${s.turn}", color = c.paper, size = 9.sp) }
            }
            RowText(DuelWords.seatName(s, seat), Modifier.widthIn(max = 160.dp), color = c.ink)
            Box(
                Modifier.cursorPointer(caption = "Change LP").muClickable { duels.lpPad = if (duels.lpPad == seat) null else seat }
                    .padding(horizontal = 4.dp),
            ) {
                Mono("${st.lp}", color = c.ink, size = if (slot.height >= 34f) 18.sp else 15.sp)
            }
            Micro("LP", color = c.ink45, size = 9.sp)
            if (seat in s.thinking) {
                Box(Modifier.border(1.dp, c.ink).padding(horizontal = 5.dp, vertical = 1.dp)) { Micro("Thinking", color = c.ink, size = 9.sp) }
            }
            if (s.conceded == seat) Micro("Conceded", color = c.ink, size = 9.sp)
            Box(Modifier.weight(1f))
            val counts = if (slot.width < 460f) "H ${st.hand.size} · D ${st.deck.size}"
            else "Hand ${st.hand.size} · Deck ${st.deck.size} · Extra ${st.extra.size} · GY ${st.gy.size}"
            Mono(
                counts,
                color = c.ink70,
            )
        }
    }
}

/** The phases beside the field: the current one inverted, any of them a click away, End turn last. */
@Composable
internal fun PhaseStrip(duels: Duels, s: DuelState, l: DuelLayout) {
    val c = Mu.colors
    val slot = l.phases
    Column(
        Modifier.zIndex(30f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DuelPhase.entries.forEach { p ->
            val on = s.phase == p
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .background(if (on) c.ink else c.paper)
                    .border(1.dp, if (on) c.ink else c.ink25)
                    .cursorPointer(caption = p.label)
                    .muClickable { if (!on) duels.act(DuelAction.Phase(p), s.active) },
                contentAlignment = Alignment.Center,
            ) {
                Mono(PHASE_SHORT.getValue(p), color = if (on) c.paper else c.ink, size = 11.sp)
            }
        }
        Box(
            Modifier.weight(1.2f).fillMaxWidth().border(1.dp, c.ink)
                .cursorPointer(caption = "End turn")
                .muClickable { duels.act(DuelAction.EndTurn, s.active) },
            contentAlignment = Alignment.Center,
        ) {
            Micro(if (s.solo) "Next" else "End", color = c.ink, size = 9.sp)
        }
    }
}

/** The chain written down: its links, newest at the bottom. A click resolves the newest; a right-click clears it. */
@Composable
internal fun ChainWell(s: DuelState, l: DuelLayout, duels: Duels) {
    val c = Mu.colors
    val slot = l[DuelSpot.Chain] ?: return
    Column(
        Modifier.zIndex(20f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Micro("Chain", color = if (s.chain.isEmpty()) c.ink45 else c.ink, size = 9.sp)
        s.chain.takeLast(6).forEachIndexed { i, link ->
            val n = s.chain.size - minOf(6, s.chain.size) + i + 1
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(c.ink).padding(horizontal = 3.dp)) { Mono("$n", color = c.paper, size = 9.sp) }
                Mono(link.uid?.let { s.cards[it] }?.let { duels.catalog.nameOf(it) } ?: link.note.ifBlank { "Effect" }, color = c.ink, size = 9.sp)
            }
        }
    }
}

/** The ground of an open pile, over the near side of the table, with its name and a way to close it. */
@Composable
internal fun StripGround(duels: Duels, s: DuelState, l: DuelLayout, seat: Int, kind: PileKind) {
    val c = Mu.colors
    val band = DuelFrames.stripBand(l)
    val pad = l.gap
    Box(
        Modifier.zIndex(DuelFrames.Z_STRIP - 0.5f)
            .offset((band.left - pad).dp, (band.top - pad - 24f).dp)
            .size((band.width + pad * 2).dp, (band.height + pad * 2 + 24f).dp)
            .background(c.paper).border(1.dp, c.ink),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Micro("${DuelWords.seatName(s, seat)} · ${kind.label} · ${s.seats[seat].pile(kind).size}", Modifier.weight(1f), color = c.ink)
            if (kind == PileKind.DECK) {
                MuButton("Shuffle and close", { duels.act(DuelAction.Shuffle(seat, PileKind.DECK), seat); duels.strip = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            IconButton(Icons.X, { duels.strip = null }, size = 22.dp, label = "Close")
        }
    }
}

/** The life-point pad: type a change (−1000, +500, =4000, /2) or tap one. */
@Composable
internal fun LpPad(duels: Duels, s: DuelState, l: DuelLayout, seat: Int) {
    val c = Mu.colors
    val bar = l.bars[seat] ?: return
    val focus = remember { FocusRequester() }
    var text by remember(seat) { mutableStateOf("") }
    fun apply(expr: String) {
        val who = if (seat == duels.bottom) "" else "opp "
        if (duels.run("lp $who$expr")) duels.lpPad = null
    }
    val above = seat != l.bottom
    val top = if (above) bar.bottom + 6f else bar.top - 6f - 92f
    Column(
        Modifier.zIndex(70f).offset((bar.left + 40f).dp, top.dp).width(300.dp).height(92.dp)
            .background(c.paper).border(1.dp, c.ink).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("${DuelWords.seatName(s, seat)} · ${s.seats[seat].lp}", color = c.ink)
            MuInput(text, { text = it }, Modifier.weight(1f), placeholder = "-1000", mono = true, dense = true, focusRequester = focus, onSubmit = { apply(text) })
            IconButton(Icons.X, { duels.lpPad = null }, size = 24.dp, label = "Close")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("-1000", "-500", "-100", "+500", "+1000", "/2").forEach { chip ->
                Box(
                    Modifier.weight(1f).border(1.dp, c.ink25).cursorPointer(caption = chip).muClickable { apply(chip) }.padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Mono(if (chip == "/2") "½" else chip, color = c.ink) }
            }
        }
    }
    RequestFocusOnce(focus, seat)
}
