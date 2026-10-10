package com.kaiharimoto.neue.versions

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.DeckVersion
import com.kaiharimoto.mastertool.core.deck.DeckVersions
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prep.IsoDate
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.prep.LedgerRead
import com.kaiharimoto.neue.prep.ledger
import com.kaiharimoto.neue.prep.ledgerWords
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the dialog reads at once, off the main thread: the versions, the print now, the games, and the lineage's names. */
private class VersionsRead(
    val versions: List<DeckVersion>,
    val now: String?,
    val ledger: LedgerRead,
    val lineage: List<Pair<String, String>>,
    val names: Map<String, String>,
    val parents: Map<String, List<DeckVersion>>,
)

/**
 * A deck's versions (Phase G, G.8; the red team's L1), newest first: when each was saved, what changed from the one before
 * it by card, the people's games at it with their range, and what the change did to the rate. A version can be put back
 * on the builder — one step of undo. A duplicate says where it came from.
 */
@Composable
fun VersionsDialog(h: NeueHolders, deckId: String, name: String, onDismiss: () -> Unit) {
    val c = Mu.colors
    val state = h.builder
    val doc = h.prep.doc
    val read by produceState<VersionsRead?>(null, deckId, h.versions.revision, doc.games, doc.sources, doc.earlier) {
        value = withContext(Dispatchers.IO) {
            val index = state.index
            val byId = index::byId.takeIf { index.cards.isNotEmpty() }
            val stored = h.deps.deckRepository.byId(deckId)
            stored?.let { h.versions.record(deckId, it.entry.deck, it.entry.name, it.entry.updatedAtEpochMs, byId) }
            val versions = h.versions.of(deckId)
            val web = h.webs.library.webs.firstOrNull { it.entry(deckId) != null }
            val lineage = h.versions.lineage(deckId)
            val all = h.deps.deckRepository.all().associate { it.entry.id to it.entry.name }
            VersionsRead(
                versions,
                stored?.entry?.deck?.let { DeckVersions.print(it, byId) },
                h.ledger(deckId, web),
                lineage,
                all,
                lineage.associate { (d, _) -> d to h.versions.of(d) },
            )
        }
    }
    MuDialog(
        title = "Versions of “$name”",
        onDismiss = onDismiss,
        width = 640.dp,
        description = "A version is kept each time a save changes the Main or Extra Deck by card. The games at each are the people's, as Prep counts them.",
        footer = { MuButton("Close", onDismiss, variant = BtnVariant.GHOST) },
    ) {
        val r = read
        if (r == null) Small("Reading the versions…", color = c.ink45) else VersionList(h, deckId, r, onDismiss)
    }
}

@Composable
private fun VersionList(h: NeueHolders, deckId: String, r: VersionsRead, onDismiss: () -> Unit) {
    val c = Mu.colors
    val state = h.builder
    val scope = rememberCoroutineScope()
    fun cardName(id: CardId) = state.index.byId(id)?.name ?: "#${id.value}"
    val numbered = DeckVersions.numbered(r.versions)
    val by = MatchupLedger.byVersion(r.ledger.games.filter { it.round == null }).associateBy { it.print }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Small(ledgerWords(r.ledger, h.prep.doc.sources), color = c.ink70)
        r.lineage.firstOrNull()?.let { (from, print) ->
            Small(
                "Duplicated from “${r.names[from] ?: "a deck no longer kept"}” at its ${DeckVersions.nameOf(print, r.parents[from].orEmpty())}: its games at that list count here too.",
                color = c.ink,
            )
        }
        numbered.reversed().forEach { (n, v) ->
            key(v.print) {
                val parent = numbered.firstOrNull { it.second.print == v.parent }
                val games = by[v.print]
                Column(Modifier.fillMaxWidth().border(1.dp, if (v.print == r.now) c.ink else c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Mono("v$n", color = c.ink)
                        RowText(if (v.print == r.now) "Now" else v.label.ifBlank { "Saved" }, Modifier.weight(1f), color = c.ink)
                        Mono(IsoDate.of(Math.floorDiv(v.at, 86_400_000L)), color = c.ink45)
                    }
                    Small(
                        parent?.let { (pn, p) -> "From v$pn: " + DeckVersions.words(DeckVersions.changes(p.deck, v.deck, state.index::byId), ::cardName, most = 5) }
                            ?: if (v.parentDeck != null) "The list it was duplicated as." else "The first version kept.",
                        color = c.ink70,
                    )
                    Small(games?.let { "Games: " + MatchupLedger.rateWords(it.all) } ?: "No games at this version.", color = if (games == null) c.ink45 else c.ink)
                    parent?.second?.print?.let { by[it] }?.let { before ->
                        if (games != null) MatchupLedger.difference(before.all, games.all)?.let { d -> Small("Against v${parent.first}: ${MatchupLedger.differenceWords(d)}.", color = c.ink70) }
                    }
                    if (v.print != r.now) {
                        MuButton("Put it back on the builder", {
                            scope.launch {
                                if (state.deckId != deckId) {
                                    state.load(deckId)
                                    snapshotFlow { state.deckId }.first { it == deckId }
                                }
                                state.setCards(v.deck, "Put v$n back. Save to keep it.")
                                h.neue.go(Page.BUILDER)
                                onDismiss()
                            }
                        }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                    }
                }
            }
        }
        val unknown = by.filterKeys { p -> p == null || r.versions.none { it.print == p } }.values
        if (unknown.isNotEmpty()) {
            val all = unknown.fold(TestStats.Rate.NONE) { a, b -> a + b.all }
            Small("${DeckVersions.UNKNOWN.replaceFirstChar { it.uppercase() }} (games from before versions were kept): ${MatchupLedger.rateWords(all)}.", color = c.ink45)
        }
        Help("Each version's games are its own, against whatever opponents came up: a difference says most when both were played against the same field.")
    }
}
