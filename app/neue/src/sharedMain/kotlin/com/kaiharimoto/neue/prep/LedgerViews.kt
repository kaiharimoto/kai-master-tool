package com.kaiharimoto.neue.prep

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.deck.DeckVersion
import com.kaiharimoto.mastertool.core.deck.DeckVersions
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.hand.GoalCount
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.mastertool.core.prep.OpponentMatch
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The one ledger on Prep's practice tab (Phase G, G.8; the red team's L1–L4): which games count — the people's always, the
 * other sources beside them, earlier lists of today's decks — which version of the deck, and what decided the games.
 */

/** "All versions": the version filter's word for none. */
internal const val ALL_VERSIONS = "*"

/** The sources counted, the earlier lists, and the versions to read: chips over the practice tab's numbers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedgerStrip(
    prep: Prep,
    ledger: LedgerRead?,
    versions: List<DeckVersion>,
    current: String?,
    version: String,
    onVersion: (String) -> Unit,
) {
    val c = Mu.colors
    val doc = prep.doc
    val read = ledger ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Micro("Counted", Modifier.padding(end = 4.dp), color = c.ink45)
            read.sources.forEach { s ->
                key(s.source) {
                    if (s.people) {
                        // The people's games always count: the others stand beside them.
                        Tag(MatchupLedger.sourceName(s.source), true, {}, count = "${s.rate.games}", caption = "Always counted")
                    } else {
                        val on = s.source in doc.sources
                        Tag(MatchupLedger.sourceName(s.source), on, {
                            prep.commit(doc.copy(sources = if (on) doc.sources - s.source else (doc.sources + s.source).distinct()))
                        }, count = "${s.rate.games}", caption = if (on) "Leave out" else "Count beside")
                    }
                }
            }
            read.earlier?.takeIf { it.earlierTotal > 0 }?.let { f ->
                Tag("Earlier lists", doc.earlier, { prep.commit(doc.copy(earlier = !doc.earlier)) }, count = "${f.earlierTotal}", caption = if (doc.earlier) "Leave out" else "Count")
            }
        }
        read.earlier?.let { f -> OpponentMatch.words(f.earlierTotal, f.included)?.let { Help("$it: games against an older list of the same strategy, a re-import or another web, found by the cards it plays.") } }
        // Each version with games, newest first; a game from before versions were kept (or at a list no version holds) last.
        val practice = read.games.filter { it.round == null }
        val numbers = DeckVersions.numbered(versions).associate { it.second.print to it.first }
        val by = MatchupLedger.byVersion(practice).sortedByDescending { numbers[it.print] ?: 0 }
        if (by.size > 1 || (by.size == 1 && by.single().print != current)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Micro("Version", Modifier.padding(end = 4.dp), color = c.ink45)
                Tag("All", version == ALL_VERSIONS, { onVersion(ALL_VERSIONS) }, count = "${practice.size}", caption = "Every version")
                by.forEach { v ->
                    key(v.print ?: "") {
                        val n = numbers[v.print]
                        val name = (if (n == null) (if (v.print == null) "Unknown" else "Earlier") else "v$n") + if (v.print == current) " · now" else ""
                        Tag(name, version == (v.print ?: ""), { onVersion(v.print ?: "") }, count = "${v.all.games}", caption = "Only this version's games")
                    }
                }
            }
            // The newest two versions with games, the older first: did the change move the rate?
            val pair = by.filter { numbers[it.print] != null && it.all.games > 0 }.take(2)
            if (pair.size == 2) {
                val (newer, older) = pair
                MatchupLedger.difference(older.all, newer.all)?.let { d ->
                    Small(
                        "v${numbers[older.print]} → v${numbers[newer.print]}: " +
                            "${MatchupLedger.rateWords(older.all)} → ${MatchupLedger.rateWords(newer.all)}; ${MatchupLedger.differenceWords(d)}.",
                        color = c.ink,
                    )
                }
            }
        }
    }
}

/** The games of [version] ([ALL_VERSIONS]: all; "": those of an unknown version). */
internal fun LedgerRead.ofVersion(version: String): LedgerRead =
    if (version == ALL_VERSIONS) this else LedgerRead(games.filter { (it.deckPrint ?: "") == version }, all, sources, earlier)

/**
 * What decided the games (L4): each card's games when it opened (the Duel page's deal) and when it was logged as deciding,
 * beside the deck's own rate, and the bricks logged against the deck's first question's odds for the version played.
 */
@Composable
internal fun WhatDecided(games: List<TestGame>, mine: StoredDeck?, version: DeckVersion?, state: DeckBuilderState, phone: Boolean) {
    val c = Mu.colors
    val decided = games.filter { it.result != TestGame.DRAW }
    if (decided.isEmpty()) return
    val cards = TestStats.byCard(games).take(if (phone) 6 else 10)
    val overall = TestStats.Rate(decided.count { it.result == TestGame.WIN }, decided.size)
    // The first question ("Opens") over the version played: the chance a hand holds no starter, first and second.
    val noStarter by produceState<Pair<Double, Double>?>(null, mine?.entry?.id, version?.print, mine?.extended) {
        value = withContext(Dispatchers.Default) {
            val stored = DeckGroupsCodec.read(mine?.extended)
            val goal = stored.goals.goals.firstOrNull() ?: return@withContext null
            val main = version?.deck?.main ?: mine?.entry?.deck?.main ?: return@withContext null
            runCatching { GoalCount.odds(goal, main, stored.groups, { id: CardId -> state.index.byId(id)?.name }) }.getOrNull()?.let { (1 - it.first) to (1 - it.second) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro("What decided games", color = c.ink70)
        noStarter?.let { (f, s) -> TestStats.bricks(games, f, s)?.let { b -> Small(TestStats.brickWords(b) + ".", color = if (b.tooMany) c.ink else c.ink70) } }
            ?: Small("Ask the builder a question first (Opens: a starter in hand) and the bricks logged are read against its odds.", color = c.ink45)
        if (cards.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Micro("Card", Modifier.weight(1f), color = c.ink45)
                    Micro("Opened", Modifier.width(if (phone) 72.dp else 120.dp), color = c.ink45)
                    Micro("Decided", Modifier.width(if (phone) 72.dp else 120.dp), color = c.ink45)
                }
                cards.forEach { r ->
                    key(r.card) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RowText(state.index.byId(CardId(r.card))?.name ?: "#${r.card}", Modifier.weight(1f), color = c.ink, maxLines = 1)
                            Mono(cell(r.opened), Modifier.width(if (phone) 72.dp else 120.dp), color = if (r.opened.games < 5) c.ink45 else c.ink70)
                            Mono(cell(r.decided), Modifier.width(if (phone) 72.dp else 120.dp), color = if (r.decided.games < 5) c.ink45 else c.ink70)
                        }
                    }
                }
            }
        }
        Help(
            "The deck won ${percent(overall.pct)} of ${overall.games}. Opened: the games a card was in the opening hand (the Duel page's deal); " +
                "decided: the games it was logged as deciding. Faint under five games, which say little yet.",
        )
    }
}

private fun cell(r: TestStats.Rate) = if (r.games == 0) "--" else "${percent(r.pct)} · ${r.games}"
