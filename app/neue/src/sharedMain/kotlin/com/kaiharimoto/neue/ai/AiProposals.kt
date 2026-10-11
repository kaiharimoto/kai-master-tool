package com.kaiharimoto.neue.ai

import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookCodec
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookPaths
import com.kaiharimoto.mastertool.core.ai.proposals.Proposal
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalBook
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalCodec
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalPaths
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalScore
import com.kaiharimoto.mastertool.core.deck.DeckDependents
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.prep.ledger
import com.kaiharimoto.neue.effects.combosOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Ai's proposals (Phase G, G.9; the red team's A1), kept per deck in `<data>/ai/proposals/<deck>.json` — under Ai's folder,
 * so they sync and back up with the rest of what Ai keeps, and go with the deck — and what a cut breaks (A4).
 */

/** [deckId]'s proposals, read now. */
fun AiState.proposalBook(deckId: String): ProposalBook = ProposalCodec.decode(files.read(ProposalPaths.of(deckId)))

/** [p] kept (or put in place of the one with its id), and every card showing it told. */
fun AiState.keepProposal(p: Proposal) {
    val book = proposalBook(p.deckId)
    files.write(ProposalPaths.of(p.deckId), ProposalCodec.encode(book.copy(deckId = p.deckId).put(p)))
    proposalsRevision++
}

/** Every deck's proposals, read now (G2.3: the suggestions scored across them all). */
fun AiState.allProposals(): List<Proposal> =
    files.file(ProposalPaths.FOLDER).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        .flatMap { f -> runCatching { ProposalCodec.decode(f.readText()).proposals }.getOrDefault(emptyList()) }

/**
 * Ai's suggestions scored against the person's games (G2.3): every applied proposal held to the games of its deck since, from the
 * ledger as every count of a deck's games reads it. Off the frame thread.
 */
suspend fun NeueHolders.proposalScore(): ProposalScore.Score = withContext(Dispatchers.IO) {
    val proposals = ai.allProposals()
    val games = proposals.filter { it.state == Proposal.APPLIED }.map { it.deckId }.distinct().flatMap { id -> runCatching { ledger(id, null).all }.getOrDefault(emptyList()) }
    ProposalScore.score(proposals, games.distinctBy { it.id })
}

/** [p] as it stands now (applied, put aside), from its deck's book; the card's own copy until it is kept. */
fun AiState.proposalNow(p: Proposal): Proposal = proposalBook(p.deckId).byId(p.id) ?: p

/**
 * What the change from [before] to [after] breaks in [deckId]'s lines (A4): the combos, the playbook's lines and the mapped
 * boards that used a card whose last copy goes. Read off the main thread; empty when nothing used the cards.
 */
suspend fun NeueHolders.cutBreaks(deckId: String?, before: Deck, after: Deck): List<DeckDependents.Of> = withContext(Dispatchers.IO) {
    val index = builder.index
    if (index.cards.isEmpty()) return@withContext emptyList()
    val gone = DeckDependents.lastCopiesGone(before, after, index::byId)
    if (gone.isEmpty()) return@withContext emptyList()
    val combos = combosOf(deckId)
    val plays = deckId?.let { id -> PlaybookCodec.read(ai.files.read(PlaybookPaths.of(id)), id)?.entries }.orEmpty()
    val starters = if (deckId != null && mapperStarted && mapper.deckId == deckId) mapper.starterRows() else emptyList()
    DeckDependents.of(gone, combos, plays, starters)
}
