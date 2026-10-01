package com.kaiharimoto.mastertool.core.ai.report

/**
 * A reader's guide as Ai would write one, for the pictures and the tests (1.0.66, 1.0.67): Kaihuang
 * Zhang's Labrynth from the Las Vegas Regional (YGOPRODeck #711878), the forty cards as played, by
 * role. The layouts are judged on it, so it is written the way a guide should read — but the words
 * are a sample for the layouts, not rulings: the card text in the app is the authority.
 */
object ReaderGuideSample {
    private const val ARIANNA = "Arianna the Labrynth Servant"
    private const val STOVIE = "Labrynth Stovie Torbie"
    private const val CHANDRA = "Labrynth Chandraglier"
    private const val WELCOME = "Welcome Labrynth"
    private const val BIG = "Big Welcome Labrynth"
    private const val LADY = "Lady Labrynth of the Silver Castle"
    private const val LOVELY = "Lovely Labrynth of the Silver Castle"
    private const val ARIAS = "Arias the Labrynth Butler"
    private const val COOCLOCK = "Labrynth Cooclock"
    private const val TRICK = "Trap Trick"
    private const val DARUMA = "Destructive Daruma Karma Cannon"
    private const val CANNONS = "Simultaneous Equation Cannons"
    private const val ROLLBACK = "Transaction Rollback"
    private const val FUWALOS = "Mulcharmy Fuwalos"
    private const val DOMINUS = "Dominus Impulse"
    private const val JACK = "Absolute King Back Jack"
    private const val ASH = "Ash Blossom & Joyous Spring"
    private const val IMPERM = "Infinite Impermanence"
    private const val VEILER = "Effect Veiler"
    private const val DUSTER = "Harpie's Feather Duster"
    private const val SHIFTER = "Dimension Shifter"

    val labrynth = ReaderGuide(
        deckName = "Labrynth",
        subtitle = "Kaihuang Zhang · Las Vegas Regional, Top 8",
        bigIdea = "Set traps, pass, and answer their turn one play at a time.",
        pitch = "A trap deck that plays on your opponent's turn. Your Fiends and the Labrynth furniture keep setting Normal Traps, " +
            "and every trap that resolves pays you back — another card, another body, another trap. You win by answering each " +
            "of their plays one at a time until they run out, then turning the grind into damage.",
        lessons = listOf(
            ReaderGuide.Lesson(
                maxim = "Count the Welcome traps as starters.",
                card = WELCOME,
                number = "74 → 90%",
                numberLabel = "Hands that start, going first",
                why = "Nine monsters start the deck on their own, and nine of forty opens one only three hands in four. " +
                    "But a Welcome trap set on turn one does the same job a turn later — it summons Lady on their turn. " +
                    "Counted with the five Welcome traps, fourteen cards start, and nine hands in ten have one. " +
                    "Keep a Welcome trap over a second starter when you must discard.",
                show = "odds",
            ),
            ReaderGuide.Lesson(
                maxim = "Their turn is your turn.",
                card = LADY,
                number = "3 of 5",
                numberLabel = "Plays in the main line made on their turn",
                why = "Going first you summon one card and set three. Everything else — Big Welcome, Lady, the trap Lady sets, " +
                    "the trap that one becomes — happens while they play. So the question is never \"what do I do?\" but " +
                    "\"what do I let them do?\". Plan your turn around the plays you will answer on theirs.",
                show = "turn:0",
            ),
            ReaderGuide.Lesson(
                maxim = "Answer the play that matters, not the first one.",
                card = DARUMA,
                number = "1 trap = 1 play",
                numberLabel = "Each trap answers one of their plays",
                why = "Your traps are a budget. A trap spent on their first summon is a trap you will not have for their boss. " +
                    "Know their deck's two or three plays that win, and let the rest resolve. The lines below mark where.",
                show = "line:0",
            ),
        ),
        roles = listOf(
            ReaderGuide.Role(
                "Starters",
                listOf(
                    ReaderGuide.RoleCard(ARIANNA, "Summoned: adds a Labrynth trap. One card, your whole turn.", 3),
                    ReaderGuide.RoleCard(STOVIE, "From the hand: a Welcome trap from the Deck, set.", 3),
                    ReaderGuide.RoleCard(CHANDRA, "The other piece of furniture: the same, another way.", 3),
                    ReaderGuide.RoleCard(WELCOME, "Set turn one, summons Lady on theirs.", 2),
                    ReaderGuide.RoleCard(BIG, "The best Welcome: Lady from the Deck, at instant speed.", 3),
                ),
            ),
            ReaderGuide.Role(
                "Engine",
                listOf(
                    ReaderGuide.RoleCard(LADY, "The hub: once she is out, your traps keep coming.", 1),
                    ReaderGuide.RoleCard(LOVELY, "Turns a resolved trap into another set trap.", 1),
                    ReaderGuide.RoleCard(ARIAS, "A Fiend that joins in as your traps go off.", 3),
                    ReaderGuide.RoleCard(COOCLOCK, "A one-of that keeps the loop going.", 1),
                ),
            ),
            ReaderGuide.Role(
                "Traps",
                listOf(
                    ReaderGuide.RoleCard(TRICK, "Becomes the trap you need.", 3),
                    ReaderGuide.RoleCard(DARUMA, "Flips their board face-down: your best break.", 3),
                    ReaderGuide.RoleCard(CANNONS, "Clears the field; why the Extra Deck is Fusions and Xyz.", 3),
                    ReaderGuide.RoleCard(ROLLBACK, "A second use of the best trap in your graveyard.", 2),
                ),
            ),
            ReaderGuide.Role(
                "Going second",
                listOf(
                    ReaderGuide.RoleCard(FUWALOS, "Draws you cards while they combo.", 3),
                    ReaderGuide.RoleCard(DOMINUS, "A trap you can play straight from the hand.", 3),
                    ReaderGuide.RoleCard(JACK, "Sets traps from the top of your Deck.", 3),
                ),
            ),
        ),
        connections = listOf(
            ReaderGuide.Edge(ARIANNA, BIG, "adds"),
            ReaderGuide.Edge(STOVIE, WELCOME, "sets"),
            ReaderGuide.Edge(CHANDRA, BIG, "finds"),
            ReaderGuide.Edge(WELCOME, LADY, "summons"),
            ReaderGuide.Edge(BIG, LADY, "summons"),
            ReaderGuide.Edge(LADY, TRICK, "sets"),
            ReaderGuide.Edge(TRICK, DARUMA, "becomes"),
            ReaderGuide.Edge(TRICK, CANNONS, "becomes"),
            ReaderGuide.Edge(LOVELY, WELCOME, "re-sets"),
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
                "Your most common opening. Everything after the first step happens on their turn.",
                steps = listOf(
                    ReaderGuide.Step(ARIANNA, "Normal Summon. Add Big Welcome Labrynth.", "Your Main Phase 1",
                        stoppedBy = listOf(ASH), ifStopped = "Set what you have and pass: Ash spent here is Ash not spent on Lady."),
                    ReaderGuide.Step(BIG, "Set it, with anything else you hold. Pass.", "Your End Phase"),
                    ReaderGuide.Step(BIG, "Answer their first real threat: Special Summon Lady from the Deck.", "Their Main Phase 1",
                        stoppedBy = listOf(DUSTER), ifStopped = "Chain Big Welcome to the Duster: it still resolves."),
                    ReaderGuide.Step(LADY, "Set Trap Trick from the Deck.", "Their Main Phase 1",
                        stoppedBy = listOf(IMPERM, VEILER), ifStopped = "Lady's body stays: next turn she sets two."),
                    ReaderGuide.Step(TRICK, "Becomes Daruma Karma Cannon for their boss.", "Their Main Phase 1"),
                ),
                endBoard = listOf(LADY, ARIANNA),
                endSet = listOf(DARUMA, BIG),
            ),
            ReaderGuide.Line(
                "Furniture into traps",
                "When you open furniture and no Arianna.",
                steps = listOf(
                    ReaderGuide.Step(STOVIE, "From the hand: set Welcome Labrynth from the Deck.", "Your Main Phase 1",
                        stoppedBy = listOf(ASH), ifStopped = "Use Chandraglier the same way: two pieces, one Ash."),
                    ReaderGuide.Step(CHANDRA, "From the hand: find Big Welcome.", "Your Main Phase 1"),
                    ReaderGuide.Step(WELCOME, "On their turn: summon Lady.", "Their Main Phase 1"),
                    ReaderGuide.Step(TRICK, "Lady sets it; it becomes the Cannon the matchup needs.", "Their Main Phase 1"),
                ),
                endBoard = listOf(LADY),
                endSet = listOf(BIG, CANNONS),
            ),
        ),
        chokePoints = listOf(
            ReaderGuide.Choke(ASH, "On Arianna's search: lead with furniture first when you can, so Ash has a worse target."),
            ReaderGuide.Choke(DUSTER, "Clears every set trap. Keep one trap in hand, not all on the field, when they could have it."),
            ReaderGuide.Choke(SHIFTER, "Stops the graveyard loops. Play for the traps you can flip from the field, not Rollback."),
        ),
        siding = listOf(
            ReaderGuide.Side(
                "Maliss",
                sideIn = listOf("Rescue-ACE Impulse", "Rescue-ACE Impulse", "Rescue-ACE Impulse", "Different Dimension Ground"),
                sideOut = listOf(CANNONS, CANNONS, ROLLBACK, COOCLOCK),
                why = "They play fast and early: answers you can play from the hand beat slow traps.",
                theirChoke = "Maliss <P> Dormouse",
                plan = "Stop the first search; let the rest resolve.",
            ),
            ReaderGuide.Side(
                "Spell-heavy decks",
                sideIn = listOf("Eradicator Epidemic Virus", "Lord of the Heavenly Prison", "Lord of the Heavenly Prison"),
                sideOut = listOf(DARUMA, JACK, JACK),
                why = "Their plays are spells; name spells and keep the board clear.",
                theirChoke = DUSTER,
                plan = "Keep one trap in hand until Duster is gone.",
            ),
        ),
        checklist = listOf(
            "Two traps set, one in hand.",
            "Big Welcome kept for their turn, not yours.",
            "You know their two plays worth a trap.",
            "A Labrynth monster on the field for Big Welcome to return.",
            "Equation Cannons: count the cards — hands and field.",
            "Duster in their deck? Not every trap on the field.",
        ),
        tips = listOf(
            "Count your traps before you pass: two set and one in hand is the sweet spot.",
            "Lady is your engine — protect her before protecting anything else.",
            "The Extra Deck is there for Equation Cannons: Fusions and Xyz whose Levels and Ranks add up.",
        ),
        sources = listOf(
            "The list: Kaihuang Zhang, Las Vegas Regional Top 8 (YGOPRODeck #711878).",
            "Written by Ai from its notes on the deck — sample words for the layouts; the card text in the app is the authority.",
        ),
        updatedAt = 1_790_000_000_000,
    )
}
