package com.kaiharimoto.neue.lounge

import com.kaiharimoto.mastertool.core.model.Deck

/**
 * kai's rules for a legal deck, as the Lounge checks friends' decks by them (`docs/LOUNGE.md`, round two): the builder's
 * rules in force ([words]: "TCG", "Genesys, 100 points"), and what is wrong with a deck under them ([check], one line a
 * problem, empty when it is legal). A room kai keeps to legal decks refuses any other.
 */
class LoungeLegality(val words: String, val check: (Deck) -> List<String>)
