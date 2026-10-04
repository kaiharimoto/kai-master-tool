package com.kaiharimoto.mastertool.core.ai

/**
 * The "What can you do?" demo: seven scripted scenes the chat panel plays like a
 * conversation — the person's message, the tool activity lines, then Ai's reply —
 * without calling any model. Replies are ordinary chat markdown: `[[Card]]` chips,
 * tables, and the fenced `chart`, `cards` and `compare` blocks. Every card named is a real TCG
 * card; the numbers are illustrations, and the meta scene says so.
 */
object AiDemo {
    data class Scene(
        val title: String,
        val caption: String,
        val person: String,
        val activity: List<String>,
        val reply: String,
    )

    val scenes: List<Scene> = listOf(
        Scene(
            title = "Build a deck",
            caption = "Ask for a deck and Ai builds it in the builder, grouped by role.",
            person = "Build me a Snake-Eye deck for my locals. Forty cards, and I own every hand trap.",
            activity = listOf(
                "Searched 14 cards for “Snake-Eye”",
                "Read 6 recent Snake-Eye lists on YGOPRODeck",
                "Created “Snake-Eye 40”",
                "Added 3 × Snake-Eye Ash",
                "Added 3 × Ash Blossom & Joyous Spring",
                "Set 5 groups: Starters, Extenders, Engine, Hand traps, Board breakers",
            ),
            reply = """
                Done, and it's open in the builder. **“Snake-Eye 40”** is built the way the recent tops play it:

                - **Starters**: [[Snake-Eye Ash]], [[Snake-Eye Oak]] and [[Diabellstar the Black Witch]] each start the combo alone.
                - **Extenders**: a light [[Fiendsmith Engraver]] package, so one hand trap doesn't end your turn.
                - **Hand traps**: [[Ash Blossom & Joyous Spring]], [[Infinite Impermanence]], [[Called by the Grave]] and [[Effect Veiler]].
                - **Board breakers**: [[Triple Tactics Talent]] and [[Harpie's Feather Duster]] for the games you go second.

                I grouped it by role, so press Groups to see the shape. Want me to check the odds before you sleeve it?
            """.trimIndent(),
        ),
        Scene(
            title = "Assess it with odds",
            caption = "Ai counts roles and works out how often a hand opens them.",
            person = "How often do I actually open a starter?",
            activity = listOf(
                "Read “Snake-Eye 40”",
                "Counted 13 starters, 9 hand traps, 2 bricks",
                "Calculated opening odds for 5 and 6 cards",
            ),
            reply = """
                Good news, genius-approved: you open a starter **87.7%** of the time going first.

                | Role | In deck | Going first | Going second |
                |---|---|---|---|
                | Starter | 13 | 87.7% | 92.3% |
                | Hand trap | 9 | 74.2% | 80.8% |
                | Brick | 2 | 23.7% | 28.1% |

                ```chart
                {"type":"bar","title":"Opening at least one","labels":["Starter","Hand trap","Brick"],"series":[{"name":"Going first","values":[87.7,74.2,23.7]},{"name":"Going second","values":[92.3,80.8,28.1]}],"unit":"%"}
                ```

                The two bricks show up in almost a quarter of your hands. Cut one for a 14th starter and the brick rate drops to 12.5%.
            """.trimIndent(),
        ),
        Scene(
            title = "Read the meta",
            caption = "Ai reads recent tournament decklists and groups them into strategies.",
            person = "What's been topping lately?",
            activity = listOf(
                "Read 60 tournament decks from YGOPRODeck (TCG, regionals and up, last 30 days)",
                "Grouped them into 7 strategies by the cards they play",
            ),
            reply = """
                Here's what has been topping, from 60 lists at regionals and up over the last 30 days (demo numbers here; ask me and I'll read the live ones). These are shares of top cuts, so strong decks look bigger than they are at the tables:

                ```chart
                {"type":"hbar","title":"Share of top results","labels":["Snake-Eye","Fiendsmith","Yubel","Tenpai Dragon","Branded","Other"],"series":[{"name":"Share","values":[24,19,14,11,9,23]}],"unit":"%"}
                ```

                Two decks make up over 40%, and both lean on searches from the Deck, so [[Ash Blossom & Joyous Spring]] stays a three-of. The "Other" slice is wide, which is why a flexible hand trap like [[Infinite Impermanence]] beats a narrow tech. Shall I turn this into a web for your event?
            """.trimIndent(),
        ),
        Scene(
            title = "Build a web of the field",
            caption = "The expected field becomes a web on the Format page, your deck starred.",
            person = "Yes. Make a web for my regional on October 18.",
            activity = listOf(
                "Read the tops: 7 strategies, 86% of top cuts",
                "Created web “Regional, Oct 18”",
                "Imported 6 decks with their shares",
                "Starred “Snake-Eye 40” as yours",
                "Wrote the web's notes",
            ),
            reply = """
                Your web **“Regional, Oct 18”** is on the Format page: six strategies covering 86% of the top cuts, each imported from its most typical list, with your **Snake-Eye 40** starred.

                I wrote notes for every deck: its plan, its choke points, and what it fears. The short version:

                1. **Snake-Eye mirror** (24%): whoever resolves [[Diabellstar the Black Witch]] first usually wins.
                2. **Fiendsmith** (19%): stop [[Fiendsmith Engraver]]'s search and the turn gets small.
                3. **Yubel** (14%): your hardest matchup on paper; it grinds and punishes attacks.

                Want siding plans for the top three next?
            """.trimIndent(),
        ),
        Scene(
            title = "Side against a matchup",
            caption = "A siding plan per turn, from your own Side Deck, with the why.",
            person = "What do I side going second against Fiendsmith?",
            activity = listOf(
                "Read the siding for “Snake-Eye 40” against “Fiendsmith”",
                "Checked rulings for 4 cards",
                "Saved the plan going second",
            ),
            reply = """
                Going second against Fiendsmith, you want to break their board and stop the turn before it gets there. Saved to the Siding page:

                ```compare
                Out:
                1 Droll & Lock Bird
                1 Effect Veiler
                1 Called by the Grave
                In:
                2 Nibiru, the Primal Being
                1 Evenly Matched
                ```

                Why, in one line for the table: their turn is Special Summon after Special Summon, so [[Nibiru, the Primal Being]] lands after the fifth, and [[Evenly Matched]] cleans up whatever is left. [[Ash Blossom & Joyous Spring]] stays in for [[Fiendsmith Engraver]]'s search.
            """.trimIndent(),
        ),
        Scene(
            title = "Teach it your deck",
            caption = "Fine Tuning: teach Ai your deck, or let it study the cards itself. Either way it writes a guide it keeps.",
            person = "Tune: I want you to really know this deck.",
            activity = listOf(
                "Read “Snake-Eye 40”",
                "Read the deck's guide (empty)",
                "Planned 12 questions (Standard)",
            ),
            reply = """
                Class is in session, and today you're the teacher. Twelve questions, one at a time; I'll write down everything as we go.

                Why I'm asking this first: you run three [[Snake-Eye Oak]] beside your three [[Snake-Eye Ash]], more Oak than most lists, so I think you use it differently.

                **What does [[Snake-Eye Oak]] do for you?**

                - Starter
                - Extender
                - Bait for a hand trap
                - Tech for a matchup

                Tap one, or say it in your own words.
            """.trimIndent(),
        ),
        Scene(
            title = "Prep for an event",
            caption = "The event, the policy at the table, and a practice plan from your win rates.",
            person = "Prep me for the regional.",
            activity = listOf(
                "Set the event: Regional Qualifier, Oct 18, Tier 2, about 180 players",
                "Linked web “Regional, Oct 18”",
                "Read the matchup matrix",
                "Calculated the expected win rate",
            ),
            reply = """
                Your expected match win rate against this field is **56%**. Solid, and we can push it.

                What the policy means for you:
                - **50-minute rounds**, and an unfinished match is a double loss. Yubel grinds, so it's your time risk: play at pace.
                - **No notes at the table**, not even between duels. Your siding plans live in your head, so we'll drill them.
                - Decklists are due **October 11**; I'll remind you on the 10th.

                Practice plan, by share × weakness:
                1. **Yubel** (14%, you're at 45%): 5 games first, 5 second.
                2. **Fiendsmith** (19%, 55%): 5 and 5, then a siding drill.
                3. **The mirror** (24%, 50%): 5 and 5.

                Log each game and I'll keep the numbers honest.
            """.trimIndent(),
        ),
    )
}
