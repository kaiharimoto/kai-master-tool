package com.kaiharimoto.mastertool.core.ai.skills

/**
 * The skills the app ships (kai: "skills that teach it how to use YGOProDeck to find
 * tournaments and look at deck lists… and know intimately how to create format webs
 * on its own out of the box"). Each is a `SKILL.md`; the prompt lists them by name
 * and Ai reads one with `skill_view` before its task. A skill Ai or the person writes
 * with the same name replaces the app's (`Skills.merge`).
 */
object BuiltInSkills {
    private fun skill(name: String, description: String, phase: Int, body: String): Pair<Int, Skill> =
        phase to Skill(name, description, body.trimIndent().trim(), builtIn = true)

    private val catalogue: List<Pair<Int, Skill>> = listOf(
        // The rules' edge cases, beyond the primer always in the prompt (1.0.47).
        2 to Skill(
            com.kaiharimoto.mastertool.core.ai.rules.GameRulesSkill.NAME,
            com.kaiharimoto.mastertool.core.ai.rules.GameRulesSkill.DESCRIPTION,
            com.kaiharimoto.mastertool.core.ai.rules.GameRulesSkill.BODY.trim(),
            builtIn = true,
        ),
        skill(
            "app-control",
            "How to drive Neue Master Tool: which tool does what, and the order that works.",
            1,
            """
            # Driving the app

            ## Find out where you are
            - `app_state` first when a request depends on what is open. The <app_context> block already says the page and
              the open deck; call app_state for more (the selection, the webs).

            ## Decks
            - Build a deck from scratch: `new_deck` with every card at once (main, extra, side). It opens and saves it.
              Then `set_groups` so the person sees the deck in its roles.
            - Change the open deck: one `edit_deck` call with every change as ops, in order. It is one undo step.
              Use `set` for exact counts ("run 2 Ash") — it is the least surprising op.
            - Another deck: `list_decks` → `open_deck` (the open one is saved first). Never edit a deck by id that is not open.
            - A decklist pasted by the person, a ydke:// code or a .ydk: `import_deck`.
            - Before you name a card you are unsure of: `search_cards` (by name or by what it does) or `card_info`.
              Tell the person when you guessed ("read ‘Ash’ as Ash Blossom & Joyous Spring").

            ## Groups (the Groups button)
            - Groups are named sets of the deck's cards: Engine, Starters, Extenders, Hand traps, Board breakers, Bricks…
              Each card is in one group at most. `set_groups` with replace: true rebuilds them all.
            - Good default groups for a competitive deck: Starters (one-card combos), Extenders, Engine (the rest of the
              combo), Hand traps, Board breakers, Garnets/Bricks (cards you never want to draw).

            ## Webs, siding (Format and Siding pages)
            - A web is the field at one event. `create_web`, then `add_deck_to_web` for each deck (from the library, or
              from a decklist), with `share` in percent and `mine: true` for the person's own deck.
            - A siding plan: `set_siding_plan` for one deck against one web deck, per turn (first / second), cards out
              of main or extra, cards in from the side deck, and why. Out and in should balance (40 stays 40).

            ## Settings and everything else
            - `get_settings` lists every setting with what it means; `set_setting` changes one.
            - `run_action` does anything a shortcut does: TOGGLE_THEME, ZEN, IMMERSIVE, SCREENSHOT, TOGGLE_KEYS…
            - `navigate` goes to a page. Take the person to what you made when it helps them see it.

            ## Manners
            - Deleting asks the person by itself; do not ask twice in words first.
            - After acting, say in a line what changed. Do not paste the whole list back unless asked.
            """,
        ),
        skill(
            "deck-assessment",
            "Judging a decklist: ratios, engine and non-engine, choke points, and what to change.",
            1,
            """
            # Assessing a deck

            1. Read it whole: `get_deck` (and `validate_deck` for legality in the person's format).
            2. Count what matters, from the card text (`card_info` when unsure what a card does):
               - **Starters**: cards that begin a full combo by themselves. A competitive 40-card deck wants enough that it
                 opens one about 85–90% of the time — roughly 12–15 in 40 (1 − C(40−s,5)/C(40,5)).
               - **Extenders**: keep going through one interruption.
               - **Non-engine**: hand traps (Ash Blossom, Infinite Impermanence, Effect Veiler, Droll & Lock Bird, Nibiru,
                 Ghost Belle…), board breakers (Evenly Matched, Dark Ruler No More, Forbidden Droplet, Harpie's Feather
                 Duster…), and floodgates. Count how many a hand of 5 sees on average (5 × n / 40).
               - **Bricks**: cards that do nothing alone. Name them.
            3. The deck's plan going first and going second, in a sentence each. Its end board and what it plays through.
            4. **Choke points**: which one card stops it (e.g. Ash on the searcher, Imperm on the starter, Nibiru after
               five summons). Say which hand traps it is most afraid of and whether it has outs.
            5. Legality and the list's shape: 40 is the norm; say why when above. The extra deck: every card used by a line?
            6. Recommend concretely: "−1 X, +1 Y, because …". Offer to make the change with `edit_deck`.

            Keep the verdict short: strengths, weaknesses, the three changes that matter most.
            """,
        ),
        skill(
            "ygoprodeck-tournaments",
            "Finding recent tournament results and decklists on YGOPRODeck, and reading them well.",
            2,
            """
            # YGOPRODeck tournament decks

            - `ygopro_tournament_decks` lists recent tournament decklists from YGOPRODeck's curated meta decks: deck name,
              event, placement, player count, format and date, newest first. `tier`: 1 locals, 2 regionals and WCQs,
              3 nationals and YCS, 4 Worlds. Default tier 2 and up. Filter with `archetype`, `event`, `format`, `days`;
              `page` goes back further.
            - `ygopro_deck` shows one list in full with card names; `import_ygopro_deck` copies it into the library or a web.
              Both take any deck number, including the one at the end of a ygoprodeck.com/deck/… address.
            - **By player**: YGOPRODeck's deck lists cannot be filtered by player, so `ygopro_player` reads the site's
              player pages: every top a player has, newest first, with the list's deck number where one is published.
              Asked for someone's list ("Matthew Cane's Maliss"), use `ygopro_player` first, then `ygopro_deck` on the
              number; a top without a number has no published list — say so. `player` on `ygopro_tournament_decks`
              only filters the recent pages.
            - Always say where results come from ("YGOPRODeck, regionals and up, last 30 days") and how many decks the claim
              rests on. A handful of tops is a signal, not a meta.
            - Placement matters more than presence: a win at a 250-player event says more than a top 8 at 20 players.
            - Read a list for its choices, not its core: the flex slots, the side deck (what the pilot feared), the hand
              trap mix, the ratios. Compare two lists of one strategy by what differs.
            - Formats: TCG and OCG have different banlists; Genesys decks use a points system and are a different game —
              never mix them into a TCG field. The app's own format is in <app_context>.
            - If the source fails or has nothing, say so plainly; never fill the gap from memory as if it were data.
            """,
        ),
        skill(
            "format-webs",
            "Building a web of decks for an event: the expected field, shares, the person's deck, notes and siding.",
            2,
            """
            # Building a web for an event

            A web is the field the person expects at one event. Build it like this:

            1. **Ask what you need**, one question at a time when it is missing: the event (name, date), the format
               (TCG/OCG), its size, and which deck the person will play (a library deck, or one to build).
            2. **Read the field**: `ygopro_field_snapshot` for the format and a window of recent results (30–60 days;
              shorter right after a banlist). It groups decks into strategies with a share of results.
            3. **Choose the decks**: the strategies that together cover about 85% of the field, usually 5–9. Fold a
               strategy under 3% into "other" unless the person asks for it. Adjust for what you know of the event
               (a local skews to what its players own; the person may know their scene — ask).
            4. **Make it**: `create_web` (named after the event, notes with the date, format, size and your sources), then
               for each strategy `import_ygopro_deck` with its representative deck number into the web, with its `share`.
               Add the person's own deck with `add_deck_to_web` (deck_id from the library) and `mine: true`.
               Shares should sum to about 100.
            5. **Write the web's notes** with `memory` (scope web): per deck, its plan, what it fears, and the choke points
               that matter for the person's deck. Short entries.
            6. **Offer siding**: for each matchup with a real share, a plan going first and going second
               (`set_siding_plan`), from the person's side deck; balanced in and out; the why in a sentence.
            7. **Tell the person** what the web says in three lines: the top decks, the person's hardest matchup, and the
               side-deck cards that matter most. Take them to Format (`navigate`).

            Rebuild, don't pile on: when asked to update a web, adjust shares and swap lists rather than adding duplicates.
            """,
        ),
        skill(
            "siding",
            "Writing siding plans: what to take out and bring in, going first and going second, and why.",
            2,
            """
            # Siding plans

            - A plan is per matchup and per turn. **Going first** you want interaction that stops their turn two
              (floodgates, traps, more negates) and fewer cards that only answer boards. **Going second** you want board
              breakers and hand traps that stop their turn one, and fewer traps and floodgates.
            - Take out, in order: cards that are dead in the matchup (a hand trap that has no target, a floodgate that does
              not touch them), then your weakest non-engine, then — rarely — a copy of an engine piece. Never cut starters
              to fit side cards unless the deck is overloaded.
            - Bring in only what the side deck holds. Keep the count even (in = out) so the deck stays at its size.
            - Know their choke points and your own: side what beats their key card, and cut what their deck blanks.
            - Write the why in one sentence the person will remember at the table ("Their turn one is all Special Summons:
              Droll does nothing, Veiler stops Diabellstar").
            - `get_siding` shows what is there; `set_siding_plan` replaces one turn's plan.
            """,
        ),
        // Fine Tuning, about the deck (1.0.48, kai): the person teaches it, or Ai studies it itself.
        3 to Skill(DeckSkills.FINE_TUNING_NAME, DeckSkills.FINE_TUNING_DESCRIPTION, DeckSkills.FINE_TUNING.trim(), builtIn = true),
        3 to Skill(DeckSkills.SELF_STUDY_NAME, DeckSkills.SELF_STUDY_DESCRIPTION, DeckSkills.SELF_STUDY.trim(), builtIn = true),
        // From first principles, and the person's own profile (1.0.54).
        3 to Skill(DeckSkills.FIRST_PRINCIPLES_NAME, DeckSkills.FIRST_PRINCIPLES_DESCRIPTION, DeckSkills.FIRST_PRINCIPLES.trim(), builtIn = true),
        3 to Skill(DeckSkills.ABOUT_YOU_NAME, DeckSkills.ABOUT_YOU_DESCRIPTION, DeckSkills.ABOUT_YOU.trim(), builtIn = true),
        // Tournament prep, its own feature (1.0.50, kai: "we can build a tournament prep feature separately").
        3 to Skill(DeckSkills.TOURNAMENT_PREP_NAME, DeckSkills.TOURNAMENT_PREP_DESCRIPTION, DeckSkills.TOURNAMENT_PREP.trim(), builtIn = true),
        // A decklist read off a picture (1.0.55).
        3 to Skill(DeckSkills.DECK_FROM_PICTURE_NAME, DeckSkills.DECK_FROM_PICTURE_DESCRIPTION, DeckSkills.DECK_FROM_PICTURE.trim(), builtIn = true),
        3 to Skill(DeckSkills.DECK_FROM_VIDEO_NAME, DeckSkills.DECK_FROM_VIDEO_DESCRIPTION, DeckSkills.DECK_FROM_VIDEO.trim(), builtIn = true),
        // Refactor guide (1.0.66): the deck's guide rewritten as one document worth reading.
        3 to Skill(DeckSkills.REFACTOR_GUIDE_NAME, DeckSkills.REFACTOR_GUIDE_DESCRIPTION, DeckSkills.REFACTOR_GUIDE.trim(), builtIn = true),
        // The reader's guide (1.0.67): a book about the deck for people, a chapter at a time.
        3 to Skill(DeckSkills.WRITE_GUIDE_NAME, DeckSkills.WRITE_GUIDE_DESCRIPTION, DeckSkills.WRITE_GUIDE.trim(), builtIn = true),
        // Present (1.0.71): a deck profile for a video, and how its slides read.
        3 to Skill(DeckSkills.DECK_PROFILE_NAME, DeckSkills.DECK_PROFILE_DESCRIPTION, DeckSkills.DECK_PROFILE.trim(), builtIn = true),
        3 to Skill(DeckSkills.SLIDE_DESIGN_NAME, DeckSkills.SLIDE_DESIGN_DESCRIPTION, DeckSkills.SLIDE_DESIGN.trim(), builtIn = true),
        // Restyle (1.0.72): the look from the person's words or a picture.
        3 to Skill(DeckSkills.RESTYLE_NAME, DeckSkills.RESTYLE_DESCRIPTION, DeckSkills.RESTYLE.trim(), builtIn = true),
        // Duel (1.0.76): Ai at the table — honest knowledge, moves as a player says them, combos.
        3 to Skill(DeckSkills.DUEL_TABLE_NAME, DeckSkills.DUEL_TABLE_DESCRIPTION, DeckSkills.DUEL_TABLE.trim(), builtIn = true),
    )

    /** The skills a build that has shipped up to [phase] carries. */
    fun upTo(phase: Int): List<Skill> = catalogue.filter { it.first <= phase }.map { it.second }

    val all: List<Skill> get() = catalogue.map { it.second }
}
