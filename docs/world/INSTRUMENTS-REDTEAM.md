# Ai World's instruments: the red team (1.0.95 → 1.0.96)

kai: "run a red team on your own instruments for quality, usefulness, and accuracy, and if they can be made better
and more advanced". The instruments are `core/world/Instruments.kt` as 1.0.95 shipped them: `openings`, `ratios`,
`card_web`, `composition`, `matchups`. Each was attacked for **accuracy** (does every number agree with arithmetic done
another way?), **statistics** (intervals, small samples, what a number means), **usefulness** (would a top player
trust it and reach for it?) and **design** (arguments for a model calling it, errors, lines, boards, determinism,
speed).

Every bug below was written as a test first and failed on the 1.0.95 code
(`commonTest/.../world/InstrumentsRedTeamTest.kt`, `r1`–`r17`); the failure it gave is quoted. Then it was fixed.

## Bugs, proven

| # | Instrument | What was wrong | How it failed |
|---|---|---|---|
| R1 | all | `deck: "open"` — what the ai-world skill tells Ai to pass — found nothing once the open deck had an id of its own (the app's snapshot keys it by its id; only `null` meant the open deck). | `no deck “open” to study` |
| R2 | ratios | A card swept from 0 to 3 copies **took the deck down to 37 cards**: the 0-copy step was a 37-card deck, so every step but the last overstated the odds. | 0 copies of a 3-of starter read 36.2 %; three starters in forty is 33.8 %. |
| R3 | ratios | The summary promised a sweep of "a card's (or a group's) copies"; there was no group sweep — `group` was ignored and the deck grew with blanks instead. | 6 steps of blanks, not 7 group counts. |
| R4 | openings | A clause split at every `&` and ` and `, so **unquoted names holding them broke**: `Ash Blossom & Joyous Spring>=1`, `Light and Darkness Dragon>=1`, and the default conditions built from a group named `Search & Extenders`. | `“Ash Blossom” reads as a group or card, then …` |
| R5 | openings | `Bricks<0` (impossible) was read as `Bricks<=0`. | 66.2 % where the answer is 0. |
| R6 | openings | Groups given as names were matched **by exact case**: `ash blossom & joyous spring` counted nothing, silently, and a misspelt name (`Ash Blosom`) did too. | 0 % where the answer is 33.8 %. |
| R7 | openings, ratios | Overlapping groups (a card in two groups — the ordinary case: a starter that is also an extender) were **only simulated**, and ratios then ran a fixed 50,000-hand, seed-1 simulation it never reported. Overlaps can be counted exactly: split the deck into the regions of the Venn diagram. | `exactFirst` missing. |
| R8 | openings | The sample hands board wrote one name per line; the cards fence reads a leading number as a count, so **`7 Colored Fish` drew as seven `Colored Fish`** (and a colon after a short word reads as a label). | `[Colored Fish, Colored Fish, …]` |
| R9 | matchups | A heatmap cell with no games was written as `NaN`, which is not JSON (JavaScript's `JSON.parse` refuses it). | `"values":[[100.0,NaN,…` |
| R10 | matchups | Unlike every other instrument it **mixed every deck's games by default**, and `deck: "open"` filtered on the literal id `open`. A matchup table of two different decks is a wrong table. | `no decided games for that deck` |
| R11 | card_web | The verb was the last of a list of substrings in the sentence, so **a lock read as a summon** ("You cannot Special Summon monsters, except "Snake-Eye" monsters" → `summons` every Snake-Eye) and `add` matched inside "In **add**ition". | `[Lockdown, Snake-Eye Ash, summons], …` |
| R12 | card_web | Only quoted names were read. **Most searches are by property** — "add 1 Level 1 FIRE monster" — so the web missed the deck's real engine. | Ash's search found nothing. |
| R13 | composition | Cards the pool could not resolve were dropped without a word, so the charts did not add up to the deck. | no `unresolved` |
| R14 | ratios | An **Extra Deck card could be swept into the Main Deck**. | `I:P Masquerena` swept 0–3 copies. |
| R15 | openings | A condition on a group with no Main Deck card (empty, misspelt, or all Extra Deck) read 0 % with no warning. | `Links>=1: first 0.0%` |
| R16 | openings | `trials` was clamped to 1,000–500,000 silently: Ai asked for fifty million and was told 500,000 without being told why. | no line said so |
| R17 | prelude | `ygo.tools` in JavaScript listed the instruments by hand, beside `Instruments.ALL`: a new instrument would be missing from scripts. Held now by a test. | (guard) |

## Statistics

- **openings**: the exact odds are right (hypergeometric, multivariate where groups are disjoint); the simulation's
  Wilson interval is right. But nothing *compared* the two: a simulation outside its own 99.9 % interval of the exact
  number was never flagged. A check that is never read is not a check. Fixed: each row says `check ok` or `check off`.
- **ratios** reported percentages rounded to 0.1 in its answer while openings reported fractions: one unit everywhere
  now (fractions in answers, percentages in words).
- **matchups**:
  - *Raw rates in best of three.* 2–0 going first and 0–1 going second gave a best-of-three from 100 % and 0 %.
    Three games are not a rate. `TestStats.expected` already pulls toward 50 % by a Beta(2, 2) prior (four games'
    worth); the instrument now shows that shrunk rate beside the raw one, and best of three from the shrunk rates.
  - *No interval on best of three.* Now a 95 % credible interval, integrated exactly over both rates' Beta
    posteriors on a grid — deterministic, no seed.
  - *Multiple comparisons.* With ten opponents, one will look lopsided by chance. A matchup is called
    favoured or unfavoured only when its interval leaves 50 %, and the board's note says how many would by chance.
  - *Small samples.* A matchup with fewer than ten decided games is marked "few games".
  - *The field.* The instrument could not answer the question a player brings to Prep — what is my match win against
    this event's field? It now takes `shares` (or the host's field) and gives `TestStats.expected`.
  - *Draws* are counted in no rate (as Prep does), and are now said.
- **"share"** in matchups means percent of the field, normalised over the shares given; it is said in the note.

## Usefulness (would a top player use it?)

- openings answered "how often" but not **why not**: the bricks — the hands that fail, by what they hold — are what
  a player changes the deck for. And not **which card earns its slot**: how often a card is in the hands that work.
- There was no **"or"**: "a starter, or two extenders" is how players state a playable hand.
- ratios could not answer the deck builder's real question, "is the ninth hand trap worth the starter it costs?" —
  a sweep has to say *what it cuts* to make room.
- No instrument **optimised**: given role bounds and a goal, which counts are best? Players do this by hand with
  spreadsheets.
- No **draw-into** odds (by turn N, with extra draws), no **combo** odds from the deck's saved combos, no check of a
  **side plan**.
- card_web had no notion of **access**: a card searched by three others is effectively more copies.
- composition did not count what cards *do* (searchers, negates, hand traps) — the ratios players talk about.
- matchups did not break losses down by **reason** (brick, interrupted, outplayed, time), which Prep logs.

## Design

- Errors told Ai what was wrong but rarely how to call it right. Every error now ends with a working example.
- Lines read as a log, not a study: now each run says its question, its method (exact, or simulated with n and
  seed), its finding, and any warning.
- Boards had empty notes and generic titles. Every board now has a note saying how it was made; a headline number is
  a stat board, detail a table, comparison a chart.
- Determinism held (seeded); speed was fine (twelve conditions × 500,000 hands both ways in 3.7 s on the desk) but
  the simulation copied the deck and hashed names for every hand. It now draws integers.

## What changed (1.0.96)

Fixed: all of the above. Upgraded: `openings` (any condition exact, `|`/`or`, `any(…)`, bricks, per-card
contribution, a self-check), `ratios` (keeps the deck size, card/group sweeps with `cut`, marginal value per copy, a
second condition alongside), `card_web` (properties read off Konami's phrasing, materials, verbs merged, hubs, stand-
alone cards, access odds), `composition` (what cards do, subtypes, unresolved), `matchups` (shrinkage, best of three
with intervals, the field, reasons). New: `optimize`, `draws`, `combos`, `siding`, and `guide` — how an instrument is
built, for Ai's own.
