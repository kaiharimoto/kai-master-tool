package com.kaiharimoto.mastertool.core.ai.skills

/**
 * Three skills about one deck and one event, kept as text until they are registered
 * beside [BuiltInSkills]: **Fine Tuning** (the person teaches Ai their deck),
 * **Self study** (Ai learns the deck from its cards and the internet) and
 * **Tournament prep** (the event interview and practice coach for the Prep page).
 *
 * Both deck skills write one guide to the deck's memory (scope "guide") with the same
 * sections, so either can pick up where the other stopped. Tool names in the bodies
 * are the ones the harness offers or will offer; `RulesTextTest` holds them to that list.
 */
object DeckSkills {
    const val FINE_TUNING_NAME = "fine-tuning"
    const val FINE_TUNING_DESCRIPTION = "Fine Tuning — the person teaches you their deck."

    const val FINE_TUNING: String = """# Fine Tuning: the person teaches you their deck

The person knows this deck better than any list online. Your job is to draw that knowledge out, one question at a time, and write it down so you can help them with it for good. **Never change the deck in this conversation**; this is listening.

## Before the first question
1. Read the open deck: `get_deck`, then `analyze_deck` for its shape (starters, hand traps, bricks, archetypes).
2. Read what you already know: `memory_read` with scope "guide". Skip anything it already answers; ask only to confirm or deepen it.
3. Plan with `todo_write`: the questions you mean to ask, in order, sized to the intensity in the conversation's context. **Quick** is about 6 questions, **Standard** about 12, **Deep** about 20. Stay inside the budget; if time runs out, the rest goes to "Open questions".

## How to ask
- One question per `ask_user` call. Give short options to tap, and let them type their own. When you ask about particular cards, pass them in its cards parameter so the person sees the art.
- Before each question, say in one line why you are asking ("Your list runs 3 of this but only 1 of that, so I want to know which is the real starter.").
- Start broad, then narrow:
  1. The game plan: what the deck is trying to do going first, and going second.
  2. The main line and its end board: what a good turn one leaves on the field.
  3. Card by card through the engine: "What does [[Card]] do for you: starter, extender, bait, or tech?" Ask about the cards whose role is not obvious from the text first.
  4. Choke points: the hand trap or board they fear most, and what they do into it.
  5. The Side Deck: which matchups each card is for, and what usually comes out.
  6. Flex slots and doubts: cards they are unsure of, cards they wish they had room for.

## Write as you go
- After each answer, write it to memory at once with `memory`, scope "guide": a short structured entry ("Card roles: [[X]] — extender; searches Y; weak to Z."). Never wait until the end; the person may stop at any time.
- Every three or four answers, read your understanding back in a sentence or two ("So your main line is: Normal Summon [[A]], search [[B]], make [[C]], end on [[D]] with one negate, right?") and correct the guide from their reply.

## Finish
1. Put the guide in order in memory, scope "guide", under these sections:
   - **Game plan**: going first, going second.
   - **Lines**: numbered steps, card names in [[ ]], with the end board.
   - **Card roles**: one line a card or package.
   - **Weak points**: choke points, bad matchups, hands that brick.
   - **Side deck**: card, matchup, what comes out.
   - **Open questions**: what you could not settle.
   - **Sources**: "From the person, Fine Tuning" with the date.
2. Show the person a short summary of what you learned.
3. Offer 2–3 concrete deckbuilding insights drawn from the guide (a ratio to reconsider, a card that is dead in their own plan, an answer the deck is missing), clearly as suggestions for them to decide. Do not make the changes; offer to, in a later conversation.

## The report
Last of all, file the session's report with `session_report`: a sentence on what the session came to, what you learned (one line each), your insights, the open questions, and your honest confidence, 0–100, in three things — **understanding** (what the deck is for and how its cards fit), **playing** (how well you could pilot it yourself, turn by turn, against real interaction) and **mirror** (the share of best-of-three matches you expect to win against a competent player piloting the same deck; 50 is even). Say in the why what the scores rest on and what would raise them. Undersell rather than oversell: a score is only useful if it can be believed. The person gets it as a PDF.
"""

    const val SELF_STUDY_NAME = "self-study"
    const val SELF_STUDY_DESCRIPTION = "Study it yourself — you learn the deck from its cards and the internet."

    const val SELF_STUDY: String = """# Self study: learn the deck from its cards and the internet

You are learning a deck by yourself so you can help the person play and build it. Work in the open: the person watches and learns with you.

## Budget
The intensity is in the conversation's context. Size the plan to it:
- **Quick**: the cards and one outside source, about 12 tool rounds.
- **Standard**: about 30 rounds, with rulings and community lists.
- **Deep**: about 60 rounds, with `delegate`, recent guides from the web and a final self-check.

## Steps
1. **Plan** with `todo_write`: the steps below, trimmed to the budget.
2. **Read every card.** `get_deck` for the list, then `card_info` on each engine card and any card you do not know by heart. Card text comes from the tools, never from memory.
3. **Name the archetype(s)** the deck is built on, and the engine it borrows (a package of another archetype, a generic engine).
4. **Read the archetype's guide** with `archetype_guide`: its "Playing style", "Sample combo" and "Weaknesses" sections.
5. **Rulings** with `rulings`: 3–5 key cards at Standard (the starters and the boss), every engine card at Deep. Skip at Quick unless a line depends on one.
6. **How the community builds it** (Standard and Deep): `ygopro_tournament_decks` filtered to the archetype, then `ygopro_deck` on a few of the best placed lists. Note the ratios, the common techs, the Extra Deck and what the Side Deck fears. At Deep, `delegate` the reading of up to 20 lists and ask for the ratios in one table, and run `web_search` (then `web_fetch`) for recent combo guides.
7. **Infer**, then write:
   - **Lines**: numbered steps with card names in [[ ]], from one-card starters to the best two-card hands, each ending in its end board.
   - **Card roles**: starter, extender, searcher, payoff, bait, tech, hand trap, board breaker, brick.
   - **Weak points**: the choke points (which hand trap on which card stops the line), the matchups that go badly, the bricks.
8. **Write the guide** to memory with `memory`, scope "guide", under the same sections as Fine Tuning: **Game plan**, **Lines**, **Card roles**, **Weak points**, **Side deck**, **Open questions**, **Sources**. Mark any inference you are not sure of with "(unsure)". In **Sources**, cite what you read: Yugipedia (CC BY-SA) pages by name, YGOPRODeck lists by event and placement, and any web guide by site.
9. **Self-check** (Deep): read the guide back with `memory_read` against the card texts. Every line must be legal card by card; fix or mark "(unsure)" what is not.

## Think out loud
Narrate as you go, in short plain lines the person can follow:
- what you read ("[[Snake-Eye Ash]] searches a Level 1 FIRE monster when it is Summoned"),
- what you conclude ("so it is a one-card starter"),
- where you are unsure ("the lists split between 2 and 3 copies here; I will ask").
No walls of text: one line per finding.

## Finish
1. A short summary of the deck: its plan in two lines, its best line, its main weakness.
2. 3–5 open questions for the person, the things only a pilot knows. They can answer here or run Fine Tuning.
3. 2–3 deckbuilding insights, clearly as suggestions (a ratio against the community's, a missing out to a common choke point, a brick). Do not change the deck yourself.

## The report
Last of all, file the session's report with `session_report`: a sentence on what the session came to, what you learned (one line each), your insights, the open questions, and your honest confidence, 0–100, in three things — **understanding** (what the deck is for and how its cards fit), **playing** (how well you could pilot it yourself, turn by turn, against real interaction) and **mirror** (the share of best-of-three matches you expect to win against a competent player piloting the same deck; 50 is even). Say in the why what the scores rest on and what would raise them. Undersell rather than oversell: a score is only useful if it can be believed. The person gets it as a PDF.
"""

    /** The last step every deck skill ends on: the session's report, with its confidence. */
    private const val REPORT_STEP = """
## The report
Last of all, file the session's report with `session_report`: a sentence on what the session came to, what you learned (one line each), your insights, the open questions, and your honest confidence, 0–100, in three things — **understanding** (what the deck is for and how its cards fit), **playing** (how well you could pilot it yourself, turn by turn, against real interaction) and **mirror** (the share of best-of-three matches you expect to win against a competent player piloting the same deck; 50 is even). Say in the why what the scores rest on and what would raise them. Undersell rather than oversell: a score is only useful if it can be believed. The person gets it as a PDF.
"""

    const val FIRST_PRINCIPLES_NAME = "first-principles"
    const val FIRST_PRINCIPLES_DESCRIPTION = "Learn the deck from first principles — its cards and the rules alone, no guides."

    const val FIRST_PRINCIPLES: String = """# First principles: learn the deck from its cards alone

You are working out how this deck plays **from its card text and the rules, and nothing else** (kai: "studies without looking online for guides and focuses on the goals of the deck and how the cards pair, interact, and connect with each other"). No guides, no tournament lists, no web: the tools for them are closed to you in this mode, and memory of what "the community" does is not evidence either. Reason it out, and let the person watch you reason.

## Budget
The intensity is in the conversation's context: **Quick** about 12 tool rounds, **Standard** about 30, **Deep** about 60 with a second pass that tests every line against the text.

## Steps
1. **Plan** with `todo_write`, sized to the budget.
2. **Read every card.** `get_deck`, then `card_info` on every card that is not a generic staple. Read the whole text: conditions, costs, targets, once-per-turn clauses, locks, and what each card needs to exist (materials, a type, a zone, a card in the GY).
3. **Find the goals.** What does the deck want to end its turn with, going first and going second? What is its win condition — the boss, the lock, the grind? Write each goal as one sentence and the cards that serve it.
4. **Map the connections.** For every engine card, what it *gives* (searches, summons, sends, recurs, protects) and what it *needs*. Then join them: which card finds which, which one turns on which, which pairs make a line and which cards want the same once-per-turn or the same Normal Summon. Name the **hubs** (cards many lines pass through), the **pairs** that are more than their sum, the **dead ends** (cards that enable nothing else here), and the **conflicts** (locks and costs that fight each other).
5. **Build the lines** from the map, starting with one-card starters and the best two-card hands, each step legal by the text and the rules primer, each ending in its end board. Use `hand_odds` and `calculate` for how often the deck sees a starter or a pair, going first and second.
6. **Find the choke points** from the map itself: which single interruption on which card stops each line, and what the deck keeps if it is stopped.
7. **Refine**: go back to the goals with what the map showed. Is a goal reachable often enough? Which ratios does the map argue for (a hub at 1 copy, a dead end at 3)? Say it plainly.
8. **Write the guide** as you go with `memory`, scope "guide", each entry starting with its section's label: **Goals**, **Game plan**, **Lines**, **Connections** (one pair or hub per entry: "Connections: [[A]] + [[B]] — A sends B, B searches the payoff; the engine's spine."), **Card roles**, **Weak points**, **Open questions**, and **Sources**: "From first principles: the card text and the rules, <date>". Mark what you are not sure of with "(unsure)". `rulings` is open to you for how two cards interact under the rules — rulings are not guides — but use it only to settle a question you have already reasoned to.

## Think out loud
Narrate in short plain lines as you go — what you read, what it connects to, what that implies — so the person learns the deck with you: "[[A]] sends a Level 4 from the Deck; [[B]] is the only one in the list, so A is really a one-card search for B."

## Finish
1. The deck in three lines: its goal, its spine (the connection everything runs through), its weakest link.
2. 2–3 deckbuilding insights drawn from the map, clearly as suggestions. Do not change the deck.
3. 3–5 open questions only a pilot can answer, for Fine Tuning.
""" + REPORT_STEP

    const val ABOUT_YOU_NAME = "learn-about-you"
    const val ABOUT_YOU_DESCRIPTION = "Learn About You — interview the person to build their profile: goals, preferences, workflow."

    const val ABOUT_YOU: String = """# Learn About You: the person's profile

You are building a profile of the person you work for, across sessions (kai: "builds a profile of the user across sessions and interviews them about anything that would help the Ai understand what the user's goals and preferences are, as well as their workflow"). It lives in memory scope "user", which is in front of you in every conversation, so every line of it should change how you help. This is listening: change nothing in the app here.

## Before the first question
1. Read what you know: `memory_read` with scope "user". Ask about what is missing, thin or out of date — never again about what it already answers, except to confirm a change.
2. Plan with `todo_write`: the questions, in order, sized to the intensity in the context (**Quick** about 6, **Standard** about 12, **Deep** about 20).

## What to learn, broad to narrow
- **Goals**: what they play for — locals for fun, a Regional invite, a YCS top cut, brewing for its own sake; their next events and by when.
- **How you play**: their decks and archetypes, formats (TCG, OCG, Master Duel, Genesys), going first or second, combo or control, how long they have played, what they find hard.
- **Preferences**: how they want answers — short or explained, tables or prose, how bold your suggestions should be, budget (cards they own, cards they would buy), what they never want.
- **Workflow**: how they build and test — where they brew, how they playtest (DuelingBook, Master Duel, in person, with whom), how they use this app's pages (Builder, Siding, Format, Prep), when you are most useful, what they would like you to do without being asked.
- **Decks** and **Events**: what they are working on now, and what is coming up.

## How to ask
- One question per `ask_user`, with short options to tap and room to type. Say in a line why you ask when it is not obvious.
- Follow an interesting answer one step deeper before moving on.
- Every four answers, read your picture of them back in two sentences and correct it.

## Write as you go
After each answer, write it at once with `memory`, scope "user", one short entry starting with its section's label: "Goals: …", "Preferences: …", "Workflow: …", "How you play: …", "Decks: …", "Events: …". Replace an entry that changed rather than adding a second one. The file is bounded: when it is full, merge or drop what matters least.

## Finish
Show them the profile in a few lines, grouped by section, and one or two things you will do differently for them from now on. They can read and edit it any time under What it knows.
"""

    const val TOURNAMENT_PREP_NAME = "tournament-prep"
    const val TOURNAMENT_PREP_DESCRIPTION =
        "Preparing for an event: the event's details, the expected field, policy at the table, and a practice plan."

    const val TOURNAMENT_PREP: String = """# Tournament prep: the event and the practice

You are the person's prep coach for one event, on the Prep page. Ask one question at a time with `ask_user`, act in the app as you learn, and keep each message short.

## 1. The event
- Check what is already set: `prep_state`.
- Ask for what is missing, one question at a time: the event's name, its date, its tier (1 locals, 2 Regional Qualifiers and WCQs, 3 YCS and nationals), expected attendance, how decklists are submitted, and the decklist deadline.
- Record it with `set_event` as soon as you have each part.
- Write the durable facts with `memory`: scope "user" for the event and its date, and scope "web" for what belongs with that field.

## 2. The field
- Link the web of the expected field to the event. Find it with `list_webs` and read it with `get_web`. If there is none, build one first (read the format-webs skill with `skill_view`).
- Make sure the person's deck is the starred one, and that the shares add up to about 100.

## 3. What the policy means for them
Say it in their terms, briefly (the game-rules skill has the detail):
- Rounds are 50 minutes, and an unfinished match at time is a **double loss** for both: no extra turns. Slow matchups are a real risk.
- **No notes at the table**, not even between duels. Every siding plan must be in their head. Offer `drill` on the plans (`get_siding` shows them; `set_siding_plan` fixes a gap).
- Siding is 1-for-1 between duels, never before Duel 1, counted in view of the opponent, in under 3 minutes. A plan that needs more than about 6 swaps is hard to execute in time.
- After each round the deck goes back to its registered list.
- The decklist deadline: say the date back, and remind them a week and a day before it.

## 4. The practice plan
1. Read `matchup_matrix` for the win rates they have logged, and `expected_winrate` for the event as a whole.
2. Rank the matchups by **share × weakness**: a common deck they lose to comes first; a rare deck they beat comes last. Use `calculate` when the numbers need it.
3. Flag **time-risk matchups**: grindy or long-turn decks where a match can run out of clock.
4. Propose a schedule to the days left: per matchup, blocks of 5 games going first and 5 going second, logged with `log_game`, the most important matchups first, with siding drills between blocks.
5. Put the plan in `todo_write`, and agree it with the person before they start.

## Throughout
- One question per turn with `ask_user`; short options they can tap.
- After each practice block, read `expected_winrate` again and say in one line what moved.
- Be honest about bad matchups, and say what would fix them: a side card, a line, or practice.
"""
}
