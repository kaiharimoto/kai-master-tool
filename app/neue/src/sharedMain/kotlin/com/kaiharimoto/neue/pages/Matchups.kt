package com.kaiharimoto.neue.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.web.Webs

/** The siding editor for deck [deckId] of [web], once the web's decks are read. */
@Composable
internal fun SidingHost(webs: Webs, web: DeckWeb, deckId: String, state: DeckBuilderState, neue: NeueState, reload: Int) {
    var decks by remember(web.id) { mutableStateOf<List<StoredDeck>?>(null) }
    LaunchedEffect(web.id, web.deckIds, reload) { decks = webs.decks(web) }
    val list = decks ?: return
    val me = list.firstOrNull { it.entry.id == deckId }
    if (me == null) {
        LaunchedEffect(deckId) { webs.sidingDeckId = null }
        return
    }
    SidingEditor(webs, web, list, me, state, neue, onBack = {
        webs.sidingDeckId = null
        webs.sidingAgainst = null
    })
}

/**
 * The web's matchups, from the side of the decks you play (kai, on the mockup:
 * the siding chart was confusing, so a list): a row for each deck of the field,
 * what you do against it going first and going second, in a line each, and a
 * way in to the plan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatchupTable(webs: Webs, web: DeckWeb, decks: List<StoredDeck>, state: DeckBuilderState, neue: NeueState, modifier: Modifier) {
    val c = Mu.colors
    val phone = LocalPhone.current
    val mine = decks.filter { web.entry(it.entry.id)?.mine == true }
    if (mine.isEmpty()) {
        EmptyState(
            "None of these decks is marked as yours.",
            "Star the decks you play — the ☆ on a tile — and how each sides against the field is listed here.",
        )
        return
    }
    var asId by remember(web.id) { mutableStateOf(mine.first().entry.id) }
    val me = mine.firstOrNull { it.entry.id == asId } ?: mine.first()
    val siding = webs.sidingOf(me, state)
    val opponents = decks.filter { it.entry.id != me.entry.id }
    val written = opponents.sumOf { o -> siding.against(o.entry.id, o.entry.name)?.let { m -> Turn.entries.count { m.plan(it).sided } } ?: 0 }
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (mine.size > 1) mine.forEach { d -> Tag("★ ${d.entry.name}", d.entry.id == me.entry.id, { asId = d.entry.id }, caption = "Side as") }
                else Micro("★ ${me.entry.name}", color = c.ink)
                Small("$written of ${opponents.size * 2} plans written", Modifier.padding(start = 8.dp), color = c.ink70)
            }
            if (opponents.isEmpty()) {
                Small("There is nobody else in this web to side against yet.", color = c.ink45)
            }
            if (!phone && opponents.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Micro("Against", Modifier.weight(1.3f), color = c.ink70)
                    Micro("When you go first", Modifier.weight(1f).padding(horizontal = 12.dp), color = c.ink70)
                    Micro("When you go second", Modifier.weight(1f).padding(horizontal = 12.dp), color = c.ink70)
                    Box(Modifier.width(96.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink))
            }
            opponents.forEach { o ->
                val m = siding.against(o.entry.id, o.entry.name)
                val share = web.entry(o.entry.id)?.share?.let { "$it% of the field" }
                val started = m != null && (m.first.sided || m.second.sided || m.note.isNotBlank())
                val open = { webs.side(me.entry.id, o.entry.id) }
                val who: @Composable (Modifier) -> Unit = { mod ->
                    Row(mod, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            faces(o, neue, state).forEach { NeueCard(it, Modifier.size(22.dp, 32.dp), foil = "off") }
                        }
                        Column {
                            MuText(o.entry.name, style = MuType.body(LocalMuFonts.current).copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = c.ink, maxLines = 2)
                            share?.let { Small(it, color = c.ink45) }
                        }
                    }
                }
                if (phone) {
                    Column(Modifier.fillMaxWidth().border(1.dp, c.ink25).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        who(Modifier.fillMaxWidth())
                        Summary("Going first", m?.first)
                        Summary("Going second", m?.second)
                        MuButton(if (started) "Open" else "Start", open, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        who(Modifier.weight(1.3f))
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { Summary(null, m?.first) }
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { Summary(null, m?.second) }
                        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                            MuButton(if (started) "Open" else "Start", open, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink12))
                }
            }
        }
        ScrollbarFor(scroll)
    }
}

/** A turn's plan in a line: `3 out · 3 in · “why”`, or a dashed box when there is none. */
@Composable
private fun Summary(turn: String?, plan: SidePlan?) {
    val c = Mu.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        turn?.let { Mono(it, color = c.ink70) }
        if (plan == null || !plan.sided && plan.note.isBlank()) {
            Box(Modifier.border(1.dp, c.ink25).padding(horizontal = 8.dp, vertical = 3.dp)) { Small("Not sided yet", color = c.ink45) }
        } else {
            Small(
                buildString {
                    append("${plan.out.size} out · ${plan.into.size} in")
                    if (plan.note.isNotBlank()) append(" · “${plan.note.lines().first().take(60)}${if (plan.note.length > 60) "…" else ""}”")
                },
                color = c.ink,
                maxLines = 2,
            )
        }
    }
}
