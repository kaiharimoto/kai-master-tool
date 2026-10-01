package com.kaiharimoto.mastertool.core.ai.report

/**
 * A reader's guide as Ai would write one, for the pictures and the tests (1.0.66): Kaihuang Zhang's
 * Labrynth from the Las Vegas Regional (YGOPRODeck #711878), the list as played. The layouts are
 * judged on it, so it is written the way a guide should read — the words are a sample, not rulings.
 */
object ReaderGuideSample {
    val labrynth = ReaderGuide(
        deckName = "Labrynth",
        subtitle = "Kaihuang Zhang · Las Vegas WCQ Regional, Top 8 · May 2026",
        pitch = "A trap deck that plays on your opponent's turn. Your Fiends and the Labrynth furniture keep setting Normal Traps, " +
            "and every trap that resolves pays you back — another card, another body, another trap. You win by answering each " +
            "of their plays one at a time until they run out, then turning the grind into damage.",
        roles = listOf(
            ReaderGuide.Role(
                "Starters",
                listOf(
                    ReaderGuide.RoleCard("Arianna the Labrynth Servant", "Summon it: add Big Welcome. One card, your whole turn.", 3),
                    ReaderGuide.RoleCard("Labrynth Stovie Torbie", "From the hand, it sets a trap from the Deck.", 3),
                    ReaderGuide.RoleCard("Labrynth Chandraglier", "The other piece of furniture: a card from the hand becomes a set card.", 3),
                ),
            ),
            ReaderGuide.Role(
                "Engine",
                listOf(
                    ReaderGuide.RoleCard("Big Welcome Labrynth", "Brings out a Fiend from the Deck on their turn — usually Lady.", 3),
                    ReaderGuide.RoleCard("Lady Labrynth of the Silver Castle", "Once she is out, your traps keep coming.", 1),
                    ReaderGuide.RoleCard("Lovely Labrynth of the Silver Castle", "Turns a resolved trap into another set trap.", 1),
                    ReaderGuide.RoleCard("Arias the Labrynth Butler", "A Fiend that joins in as your traps go off.", 3),
                ),
            ),
            ReaderGuide.Role(
                "Traps",
                listOf(
                    ReaderGuide.RoleCard("Trap Trick", "Becomes the trap you need, ready this turn.", 3),
                    ReaderGuide.RoleCard("Destructive Daruma Karma Cannon", "Flips their board face-down; your best break.", 3),
                    ReaderGuide.RoleCard("Simultaneous Equation Cannons", "Clears the field; why the Extra Deck is all Xyz.", 3),
                    ReaderGuide.RoleCard("Transaction Rollback", "A second use of the best trap in your graveyard.", 2),
                ),
            ),
            ReaderGuide.Role(
                "Going second",
                listOf(
                    ReaderGuide.RoleCard("Mulcharmy Fuwalos", "Draws you cards while they combo.", 3),
                    ReaderGuide.RoleCard("Dominus Impulse", "A trap you can play straight from the hand.", 3),
                    ReaderGuide.RoleCard("Absolute King Back Jack", "Sets traps from the top of your Deck.", 3),
                ),
            ),
        ),
        goingFirst = listOf(
            "Open on Arianna or a piece of furniture; set two or three traps and pass.",
            "Keep Big Welcome for their turn: it answers their best play and brings Lady.",
            "Spend your traps one at a time, on the plays that matter — not the first thing they do.",
        ),
        goingSecond = listOf(
            "Fuwalos and Dominus Impulse first: survive their turn with cards in hand.",
            "Break with Daruma Karma Cannon or Equation Cannons, then set up as if going first.",
            "Don't race: a board of set traps wins the long game.",
        ),
        lines = listOf(
            ReaderGuide.Line(
                "Arianna, one card",
                "Your most common opening. Everything happens on their turn.",
                steps = listOf(
                    ReaderGuide.Step("Arianna the Labrynth Servant", "Normal Summon. Add Big Welcome Labrynth."),
                    ReaderGuide.Step("Big Welcome Labrynth", "Set it. Pass."),
                    ReaderGuide.Step("Big Welcome Labrynth", "On their turn, answer a play: Special Summon Lady from the Deck."),
                    ReaderGuide.Step("Lady Labrynth of the Silver Castle", "Set a trap from the Deck — Trap Trick if unsure."),
                ),
                endBoard = listOf("Lady Labrynth of the Silver Castle", "Arianna the Labrynth Servant", "Trap Trick"),
            ),
            ReaderGuide.Line(
                "Furniture into traps",
                "When you open furniture and no Arianna.",
                steps = listOf(
                    ReaderGuide.Step("Labrynth Stovie Torbie", "From the hand: set a trap from the Deck."),
                    ReaderGuide.Step("Labrynth Chandraglier", "From the hand: set another card."),
                    ReaderGuide.Step("Trap Trick", "Turn it into Big Welcome or a Cannon, as the matchup needs."),
                ),
                endBoard = listOf("Big Welcome Labrynth", "Destructive Daruma Karma Cannon", "Trap Trick"),
            ),
        ),
        chokePoints = listOf(
            ReaderGuide.Choke("Ash Blossom & Joyous Spring", "On Arianna's search: lead with furniture first when you can, so Ash has a worse target."),
            ReaderGuide.Choke("Harpie's Feather Duster", "Clears every set trap. Keep one trap in hand, not all on the field, when they could have it."),
            ReaderGuide.Choke("Dimension Shifter", "Stops the graveyard loops. Play for the traps you can flip from the field, not Rollback."),
        ),
        siding = listOf(
            ReaderGuide.Side("Maliss", sideIn = listOf("Rescue-ACE Impulse", "Rescue-ACE Impulse", "Different Dimension Ground"), sideOut = listOf("Simultaneous Equation Cannons", "Simultaneous Equation Cannons", "Transaction Rollback"), why = "They play fast and early: answers you can play from the hand beat slow traps."),
            ReaderGuide.Side("Spell-heavy decks", sideIn = listOf("Eradicator Epidemic Virus", "Lord of the Heavenly Prison"), sideOut = listOf("Destructive Daruma Karma Cannon", "Absolute King Back Jack"), why = "Their plays are spells; name spells and keep the board clear."),
        ),
        tips = listOf(
            "Count your traps before you pass: two set and one in hand is the sweet spot.",
            "Lady is your engine — protect her before protecting anything else.",
            "The Extra Deck is only there for Equation Cannons: keep one Xyz of each Rank it needs.",
        ),
        sources = listOf("The list: Kaihuang Zhang, Las Vegas WCQ Regional Top 8 (YGOPRODeck #711878).", "Written by Ai from its notes on the deck — a sample for the layouts."),
        updatedAt = 1_790_000_000_000,
    )
}
