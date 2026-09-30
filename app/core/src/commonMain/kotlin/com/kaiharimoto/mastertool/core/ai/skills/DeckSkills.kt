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
