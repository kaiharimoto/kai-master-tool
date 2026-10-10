package com.kaiharimoto.neue.prep

import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResultCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.mastertool.core.prep.OpponentMatch
import com.kaiharimoto.mastertool.core.prep.PrepDoc
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the ledger read for a deck (Phase G, G.8): the games every reading takes ([games]: the people's, the sources the
 * person added, earlier lists folded in when they count), every source's games before that choice ([all]), the count per
 * source, and the earlier lists found.
 */
class LedgerRead(
    val games: List<TestGame>,
    val all: List<TestGame>,
    val sources: List<MatchupLedger.SourceCount>,
    val earlier: OpponentMatch.Folded?,
) {
    companion object {
        val EMPTY = LedgerRead(emptyList(), emptyList(), listOf(MatchupLedger.SourceCount(MatchupLedger.PEOPLE, TestStats.Rate.NONE)), null)
    }
}

/**
 * [deckId]'s games from the one ledger (`MatchupLedger`): Prep's, the Lounge's and Ai vs Ai's records, its lineage's at the
 * list it began as, opponents read by strategy against [web]'s decks, then the sources [doc] counts. With no deck, Prep's
 * games of every deck, as before. Off the main thread: it reads the duel records and the decks.
 */
suspend fun NeueHolders.ledger(deckId: String?, web: DeckWeb?, doc: PrepDoc = prep.doc): LedgerRead = withContext(Dispatchers.IO) {
    if (deckId == null) {
        val games = doc.games
        return@withContext LedgerRead(MatchupLedger.select(games, doc.sources), games, MatchupLedger.bySource(games), null)
    }
    val records = readRecords()
    val gathered = MatchupLedger.gather(doc.games, records, deckId, versions.lineage(deckId))
    val index = builder.index
    val byId = index::byId.takeIf { index.cards.isNotEmpty() }
    // Every deck once: an old opponent's list is read from its deck while it is still kept.
    val decks = deps.deckRepository.all().associateBy { it.entry.id }
    fun cardsOf(id: String): Set<CardId>? = decks[id]?.entry?.deck?.let { d ->
        if (byId != null) CardIdentity.distinct(d.main + d.extra, byId) else (d.main + d.extra).toSet()
    }
    val mine = decks[deckId]
    val covers = mine?.extended?.let { SidingCodec.read(it).matchups }.orEmpty()
    val opponents = web?.entries.orEmpty().filter { it.deckId != deckId }.mapNotNull { e ->
        val d = decks[e.deckId] ?: return@mapNotNull null
        OpponentMatch.Opponent(e.deckId, d.entry.name, cardsOf(e.deckId).orEmpty(), covers.firstOrNull { it.deckId == e.deckId }?.covers.orEmpty())
    }
    val folded = if (opponents.isEmpty()) null else OpponentMatch.fold(gathered, opponents, ::cardsOf, doc.earlier)
    val all = folded?.games ?: gathered
    LedgerRead(MatchupLedger.select(all, doc.sources), all, MatchupLedger.bySource(all), folded)
}

/** Every finished duel's record under `<data>/duel/records/`, read from the files (the Duel page need not be open). */
private fun readRecords(): List<DuelResult> =
    File(Platform.dataDir, "duel/" + DuelResultCodec.FOLDER).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { DuelResultCodec.decode(f.readText()) }.getOrNull() }

/**
 * [game] with what finds it again later (Phase G, G.8): the opponent's list as it is now, its distinct Main and Extra Deck
 * cards by card, when the opponent is a deck of the library and the game did not say.
 */
suspend fun NeueHolders.withOpponentCards(game: TestGame): TestGame {
    if (game.opponentCards != null) return game
    val stored = deps.deckRepository.byId(game.opponent) ?: return game
    val index = builder.index
    val cards = stored.entry.deck.main + stored.entry.deck.extra
    val ids = if (index.cards.isEmpty()) cards.distinct() else CardIdentity.distinct(cards, index::byId).toList()
    return game.copy(opponentCards = ids.map { it.value }.sorted().takeIf { it.isNotEmpty() })
}

/**
 * What the games are, in words, for Ai and the page: "Games counted: People 34 (20 won). Beside them, not counted:
 * Against Ai 12 (8 won). 6 games from earlier lists counted."
 */
fun ledgerWords(read: LedgerRead, counted: Collection<String> = emptyList()): String = buildString {
    fun one(s: MatchupLedger.SourceCount) = "${MatchupLedger.sourceName(s.source)} ${s.rate.games} (${s.rate.wins} won)"
    val (on, off) = read.sources.partition { it.people || it.source in counted }
    append("Games counted: " + on.joinToString("; ") { one(it) } + ".")
    off.filter { it.rate.games > 0 }.takeIf { it.isNotEmpty() }?.let { append(" Beside them, not counted: " + it.joinToString("; ") { s -> one(s) } + ".") }
    read.earlier?.let { f -> OpponentMatch.words(f.earlierTotal, f.included)?.let { append(" $it.") } }
}
