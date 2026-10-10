# Phase G: the measured builder

The next version, planned from two red teams of the tools a deck is improved with:
- **`G-REDTEAM.md`, the code.** Twelve bugs that give wrong numbers today, the missing way to compare two versions of a
  deck, and data the app fetches and throws away.
- **`G-VISUAL-REDTEAM.md`, the pages.** Twenty-five studio renders, what each page leaves out, and five redesigns. They
  are drawn as mockups on a private page: https://claude.ai/artifact/NP99Rn4dWAA6dbiWq8Jhtt.

Finding ids (bug 1–12, G1–G7, B1–B7, D1–D5, F1–F4, M1–M5, L1–L4, A1–A5, R1–R3) are the code red team's. Mockups A–E are the
visual red team's.

---

## 1. Why now, and why before Phase E

`ROADMAP.md` put Phase G after B, D and E, because "Ai builds and sides better than the field" needs a player that searches.
The red teams split G in two:

- **Part one needs no search.** It is everything that measures a change to a deck:
  - comparisons on the same hands, deck versions and the results kept against them;
  - the field's interaction and its ratios;
  - side deck coverage;
  - Ai proposing a change with its proof.

  It stands on what is built: the goldfish, the Mapper, Shootout, Prep and the field. This is the next version.
- **Part two needs Phase E.** That is a side deck chosen from the matchup matrix by simulated match win, and tech choices
  compared by simulated games. It stays after E.

Part one also comes first because E and F need it. Search is scored on paired seeds, and the coach needs results kept by
deck version. Both are built here.

## 2. What the version is for, and when it is done

**Goal: "Cut X for Y" is answered in the app.**
- The answer is a measured gain and its range, from whichever engine can see it:
  - the simulation, for boards;
  - your own judgments, for hands;
  - your games, for matchups.
- Ai offers the change as a proposal carrying that proof, for you to accept.

**Done when:**
1. The twelve wrong-number bugs are closed and held by tests.
2. Two versions of a deck are compared on the same hands, with an interval, by the goldfish, the Mapper and Shootout.
3. The builder shows each of the deck's questions at −1 / now / +1 copy, where the copy count is changed.
4. Every rate on Prep, Format and Siding carries its range and how many games stand behind it.
5. Results, games and judgments separate by deck version.
6. Ai's changes arrive as proposals with evidence, and an optimization set in Trust has a baseline score per connection.

## 3. Rules this version keeps

Each is a lesson from the red teams. A change that breaks one fails review.

1. **Both sides of a comparison see the same hands.** Dealing is keyed by card, never by list position. Two separate runs
   are never compared by eye.
2. **A rate is shown with its range and its n.** A bare percentage is a bug.
3. **A simulated number shows its coverage.** The goldfish and the Mapper see only cards with trusted scripts, and the page
   says so beside every share.
4. **Record now, show later.** From the first release, every new game, result, judgment and report is stamped with the
   deck's print and its source. The screens that read them can come later, and the data will already be there.
5. **Each engine ships its Ai tool in the same release.** The tool is look-only, so Ai can read every number the person
   can.
6. **Master UI holds.**
   - Colour appears only in the deck's group markers.
   - Hatching is drawn as strokes, because the law test refuses gradients.
   - Every new chrome works at 360 dp.
7. **Stored data outlives versions.** New fields trail, with defaults. Each new shape gets an `OldDataTest` case in the
   same commit, and is named in the release notes.
8. **Every release re-shoots its pages.** It re-shoots the visual red team's views of what it changed, and its release
   notes carry the before and after (`tools/compare.py`).

---

## 4. The releases

Nine releases, one at a time, each shipped on both tracks: the desktop (`neue-v*`) and the APK (`v*`). Every one changes
`:core` or the shared screens. The numbers below are the next patches as of today (Neue 1.1.60, APK v1.4.36). If anything
ships between, they move along.

| # | Neue / APK | What it does | Findings | Stored data | Size |
|---|---|---|---|---|---|
| G.1 | 1.1.61 / v1.4.37 | **True numbers** — the twelve bugs, keyed dealing, prints recorded, Card against card landed | bugs 1–12, G1 (dealing), record-now | yes | L |
| G.2 | 1.1.62 / v1.4.38 | **Same hands** — compare two versions, ablate a card, coverage everywhere | G2–G6, mockup D | runs kept per print | L |
| G.3 | 1.1.63 / v1.4.39 | **The builder decides** — questions, −1 / now / +1, search that knows the rules | B1–B3, B5, B6, R2, G5, mockup C | goals' condition | M |
| G.4 | 1.1.64 / v1.4.40 | **Shootout you can read** — one axis, first or second, the next copy's worth | D1, D2, D4, B4, mockup A | none | M |
| G.5 | 1.1.65 / v1.4.41 | **The event** — the field's interaction and ratios, your odds, what to practise | F1–F3, M1, M3, M4, mockup B | a share's source | L |
| G.6 | 1.1.66 / v1.4.42 | **Siding with evidence** — side deck coverage, weak cards marked, legal post-side | M2, mockup E | none | M |
| G.7 | 1.1.67 / v1.4.43 | **Finding the replacement** — cards like this one, for this role, legal now | R1, F4 (the history line) | none | M |
| G.8 | 1.1.68 / v1.4.44 | **Versions and the loop** — deck versions, one ledger of results, opponents by strategy | L1–L4 | versions, ledger fields | L |
| G.9 | 1.1.69 / v1.4.45 | **Ai proposes** — `deck_propose`, the optimize skill, the optimization eval | A1–A4 | proposals | M |

### G.1 True numbers (1.1.61 / v1.4.37)

**Land what is built.** Card against card (PR #6, `claude/shootout-card-compare-0ebd7o`) compares one card with a
substitute from your own judgments.
- kai asked for it on 2026-10-08. It went green that day and was never merged.
- Main has gained only 1.1.60's diagnostic log since, and merges in cleanly. Merge it, run CI green, and ship it here.
- It is the Shootout half of "is B better than A". Everything in G.2 is the simulation half.

**The twelve fixes** (`G-REDTEAM.md` §1), grouped by where they live:

| Area | Fixes |
|---|---|
| Card identity | Groups read through `CardIdentity` (1). Through it too: `copiesIn`, `set_groups` and the viewer's menu. |
| Prep | <ul><li>Shrinkage in layers, so a win never lowers a rate (2).</li><li>Rounds out of Ai World's `matchups` instrument (5).</li><li>"Card for card" checked by section through `postSide`, by the same rule `LoungeMatch.check` uses (6).</li></ul> |
| Evidence | <ul><li>A number is matched by what it is, not by its value alone (4).</li><li>Mapper and Shootout proofs carry a print and go stale (12).</li><li>`matchup_matrix` and `expected_winrate` can be re-run, and are stale after a new game.</li></ul> |
| Rules in force | `analyze_deck`, the pool's ban chips and `search_cards ban_status` read `rulesInForce` (7, 8). `ygopro_field_snapshot` defaults to Genesys when Genesys is played. |
| Card text | `EffectKinds` negatives: "cannot be negated", "neither player can target", hand traps that summon themselves or are Traps (9). |
| Simulation | <ul><li>**Keyed dealing (G1).** Each copy is keyed by (seed, hand, canonical passcode, copy); hand *k* is the smallest keys. This fixes bug 3 for good, and every later comparison stands on it.</li><li>The goldfish and the Mapper stamp `deal = 2`; kept results replay with the deal they were made with.</li><li>The Effects app counts what the goldfish plays (11).</li></ul> |
| Smaller | <ul><li>Sweeps capped at the card's limit.</li><li>"Counts toward no condition" warned.</li><li>The prompt's page list completed.</li><li>The wording slips fixed.</li></ul> |

**Record now.**
- `DeckPrint` is the canonical Main and Extra Deck, with a side print apart. It replaces the ledger's fingerprint for new
  proofs (bug 10).
  - Old proofs are read with the old print.
  - Nothing goes stale on upgrade.
- Stamped as trailing nullable fields on `TestGame`, `DuelResult` seats, `SessionReport` and `StoredTrial`.
- `TestGame.source` (person, Ai, self-play, network, unknown) is written by the Duel page and `log_game`.
- `keyCards` is written at last: `log_game` takes it and the Prep form picks it.

**Done when:**
- `CardTruthTest` holds 2 + 1 alternate Ash in the group: 33.8 %, not 23.7 %.
- `TestStatsTest` holds that a win never lowers a rate.
- `EvidenceTest` refuses "main 40" as proof of "40 %".
- `GoldfishSeedTest` deals the same hands for any order of one list.
- `OldDataTest` has each new field's old shape.
- The emulator walk and the studio shots pass.

### G.2 Same hands: compare two versions (1.1.62 / v1.4.38)

**Builds:**
- **`VersionCompare` in `core/duel/mapper/compare` (G2):**
  - Its inputs are a deck and a variant: a saved deck, or ops (cut, add, swap) that change no deck.
  - Its output:
    - the paired counts;
    - the difference with a Newcombe 95 % interval and an exact McNemar test;
    - a sequential stop, once the interval leaves zero or lies within ±1 point;
    - the hands that changed, each one openable.
- **Ablation (G3):** "without it" against a blank card, one copy or all, over the engine. It gives the boards lost and the
  hands that become bricks.
- **The coverage guard (G4):**
  - A differing card that is inert but not a blank is refused, with Write these beside it.
  - An engine-for-non-engine swap is named as a question for a stress test or Shootout.
  - Undecided hands are counted both ways.
  - Discordant undecided hands are searched again at five times the budget.
- **Runs kept per print (G6):** the newest five per side, with lines and stress results kept by deck and script prints.
  `MapCache` is device-only.

**The pages** (mockup D):
- **The Mapper:**
  - The Library leads with board depth: hands ending on at least 1, 2 or 3 interruptions, with ranges.
  - A coverage strip and Write these.
  - Boards that do nothing folded to one line.
  - "Compare with…".
  - The Cards view as a grid.
- **The Mapper's Starters:** the best board each reaches, "Without it", a mark for your Starters group, and the exact
  chance of opening any mapped starter (G5).
- **The goldfish:**
  - The real range in the headline (reached to reached + undecided).
  - The coverage map: the deck small, inert cards dimmed.
  - A kept run drawn under the new one, with the paired difference.
- **One coverage component**, shared by the Mapper, the goldfish and the Effects app. The Effects app's "What to write
  first" is ordered by the hands each card would open.

**Ai:** `deck_compare` (runs on the page, with its progress and Stop) and `mapper_ablate`.

**Done when:**
- `CompareStudyTest`: on a synthetic deck where B is truly better by 3 points, the paired run finds it in an eighth of the
  hands an unpaired one needs, and its interval covers the truth in at least 93 % of 200 seeds.
- Ablating a blank is exactly 0.
- A differing inert card is refused.
- `MapperBenchTest` shows a one-copy swap costing at most 1.3 × one run.

### G.3 The builder decides (1.1.63 / v1.4.39)

**Builds** (mockup C):
- **The deck's questions, drawn at last:**
  - `HandGoal` is kept in the deck file and was never drawn in Neue. It becomes a Questions strip in the empty band above
    Groups.
  - Each question shows first and second, recomputed on every edit, with the change since the last step.
  - A goal gains an optional condition in the `Goals` grammar (overlap, OR, at most), and its old `asks` stay readable.
- **The inspector's "In this deck":**
  - The stepper, with each question at −1 / now / +1, first and second, exact.
  - The card's Shootout worth.
  - Its field line, once G.5 lands.
- **The odds as numbers that mean something:**
  - The group percentages labelled "1st · 2nd".
  - The odds slides hold still.
  - The deck's header row carries one number that moves as you edit.
- **Instruments in the window:** they answer under their form, and conditions are built from chips of the deck's groups.
- **The exact tools, sharper:**
  - **`hand_odds`:** takes a `condition`, `with`, `without` and `deck_size` (B2).
  - **Searchers counted:** `reach(X)` counts a card's searchers (B3).
  - **Sweeps:** capped by the limit and by Genesys points (B5).
  - **`openings`:** reports the marginal ±1, not the lift (B6).
- **Search that knows the rules in force (R2):** `CardFilter` gets `rules`, `legalOnly`, a points range, release dates and
  "not yet in the TCG". `CardSort` gets POINTS and NEWEST.

**Ai:** `shootout_results`, look-only, since the inspector reads it (D1). `hand_odds` is extended.

**Stored:** the goal's condition in the payload's `goals` key, held in `OldDataTest`.

**Done when:**
- The inspector's −1 / now / +1 equal the `ratios` instrument for every question (a test).
- A Genesys sweep never passes the cap.
- The phone shot at 360 dp holds the strip.

### G.4 Shootout you can read (1.1.64 / v1.4.40)

**Builds** (mockup A):
- **The cards on one shared axis:**
  - A continuous zero rule, 26 px rows, and "as your draw" as a hollow mark.
  - Per stratum in a matchup.
  - Card against card's results drawn on the same plot.
- **The strata as one strip with ranges:**
  - It says the first-or-second call: "Win the roll: go second (+17, ±15)".
  - The settled count reads as progress, with the hands left.
- **Calls you can act on:**
  - Made at 95 % with a Holm correction (D4).
  - Each with a next step: "Try −1" opens the builder's −1 column, and "Side it out" opens Siding with the copy marked.
- **The rest of the page:**
  - Pairs beside the cards.
  - The trial's empty band holds group chips and the hovered card's text.
  - The setup page shows a preview.
- **The next copy's worth (D2):** `Reporter.variant` on coupled pools, built on keyed dealing. A card with no trials reads
  "unrated".
- **The 41st card (B4):** one table of the odds, a paired goldfish run and the Shootout contrast.

**Ai:** `shootout_whatif`.

**Done when:**
- A 40-card deck's table fits a 1080p screen.
- A null simulation calls a card wrongly at most 5 % of the time.
- The next-copy contrast recovers a planted value in simulation (`ShootoutTrustSimulationTest`'s pattern).

### G.5 The event (1.1.65 / v1.4.41)

**Builds** (mockup B):
- **`FieldProfile` (F1):** the field's hand traps and negates per strategy, and P(they open ≥1 / ≥2). Its side decks are
  read at last.
- **`StrategyRatios` and `field_compare` (F2):** the consensus list, and your list against it. The inspector's field line
  goes live.
- **Shares, honestly weighted (F3):**
  - A per-event budget and a recency half-life, with the old weighting still selectable.
  - `WebEntry.shareSource`.
  - `FieldTrend` with banlist changes marked.
- **Prep:**
  - The expected match win with its range and n, everywhere.
  - Rates drawn as dots with ranges, cells under five games greyed.
  - `PracticePlan.next` (M1) and `Policy.cutChance` (Top 8 at your rate).
  - A first-or-second column (M3).
  - An "Other" share, and unfinished matches counted as losses (M4).
  - On the phone, the answer first and the form behind "Log a game".
- **Format redrawn as the event:**
  - The field as one strip.
  - A row per deck: your match win (range), games, siding status, and what it costs you.
  - "Practise next".
  - Its matchup rows join Prep's and Shootout's rates.
- **Prep's plan:** "Your odds at this event" beside the countdown.

**Ai:** `field_profile` and `field_compare`. `expected_winrate` gives its range.

**Stored:** `WebEntry.shareSource` (trailing). The field cache is device-only.

**Done when:**
- `MatchMath.field`'s interval covers the truth in simulation.
- `cutChance` equals the binomial.
- `PracticePlan` picks the cell that narrows the range most, in simulation.
- `field_compare` holds on captured lists.

### G.6 Siding with evidence (1.1.66 / v1.4.42)

**Builds** (mockup E):
- **`SideCoverage` (M2):**
  - The share of the field each side card meets, and dead slots.
  - Main Deck cards sided out against more than half the field, and side cards brought in against more than half.
  - Copies needed against copies held.
  - Post-side legality, by one function shared with `LoungeMatch`.
- **Where it shows:** on Siding, on the guide's first page and as a line in `EventCheck`.
- **On the board:**
  - Each copy marked with its Shootout worth for that stratum, and the playbook's dead-in-matchup notes.
  - The turn's header shows what the plan does to the deck's questions, through the `siding` instrument on the saved plan.
- **The opponent:**
  - Covers default to its three most-copied cards.
  - "How they side against you" is drafted from `FieldProfile` when the matchup is unlinked.
- **Drills:** they fit the screen and show their box and when they are due.

**Ai:** `side_coverage`.

**Done when:**
- Every saved plan's post-side deck passes the shared rule.
- The coverage numbers hold on fixtures.
- The studio's Siding shot shows the panel at 1080p and 360 dp.

### G.7 Finding the replacement (1.1.67 / v1.4.43)

**Builds:**
- **`CardLikeness` (R1):**
  - What it measures:
    - effect kinds, category and frame;
    - level, attribute and race;
    - archetype;
    - the text itself.
  - What it filters by: the rules in force, Genesys points left and copies at their limit.
- **Where it shows:**
  - "Like this" in the inspector.
  - "More like these" on a group's row, which searches by the group's own profile and never assigns.
  - The results in the pool through `onlyIds`.
- **The banlist history line (F4's first half):** "Limited 2019–21". There is no risk ranking yet.

**Ai:** `similar_cards`.

**Done when:**
- Hand traps find hand traps on real texts.
- Nothing illegal under the rules in force is offered.

### G.8 Versions and the loop (1.1.68 / v1.4.44)

**Builds:**
- **`DeckVersions` (L1):**
  - One immutable file a version, `<data>/decks/versions/<deck>/<print>.json`. It is synced (newer-wins is safe on files that
    never change), backed up and deleted with the deck.
  - A Versions row on Decks, and saved versions in History.
  - Duplicate records its parent.
  - Duplicate carries the measurements as well as Ai's learning: Shootout, the goldfish, the Mapper, combos and Prep's
    games.
- **`MatchupLedger` (L2):** Prep's games and the Duel page's, the Lounge's and Ai vs Ai's records, each by source. People
  only by default; the other sources stand beside them.
- **Opponents by strategy (L3):** through `FieldBuilder.similarity` and `Matchup.covers`, so a re-imported list or a new web
  keeps its games. The matrix says "n games from earlier lists", to include or leave out.
- **What decided games (L4):** the opening hand from the replay, `TestStats.byCard`, and a brick check against the exact
  P(no starter) for that version.

**Ai:** `compare_versions`.

**Stored:** version files, held in `OldDataTest`. Records from before G.1 read as "unknown version". From G.1 on they
already carry their print.

**Done when:**
- Games before and after a change separate.
- A re-imported list keeps its games.
- A duplicate keeps its lineage and its measurements.

### G.9 Ai proposes (1.1.69 / v1.4.45)

**Builds:**
- **`deck_propose` (A1):**
  - Ops, why, evidence and the expected change, kept in `<data>/ai/proposals/<deck>.json`.
  - A card with Apply / Not now.
  - Allowed in Fine Tuning and the study modes, because it changes no deck.
  - Accepted proposals scored later against the results of the version they made.
- **The prompt:** "propose, then apply on yes" for any change Ai chooses.
- **The skills (A2):**
  - A `deck-optimize` skill: baseline, field, your judgments, hypotheses each with its metric, a paired compare, then
    propose.
  - `deck-assessment` repointed at the exact tools.
- **What a cut breaks (A4):** `DeckDependents` names the combos, mapped boards and playbook lines a removal breaks, on every
  edit.
- **The instruments for Ai:**
  - They run without a World and from `delegate` helpers.
  - They emit a `claims` footer the ledger matches against.
- **An optimization set in Trust:** decks with a known better change planted. Ai must find it with the tools and propose
  it with proof that passes `Evidence.judge`.

**Done when:**
- The set's baseline per connection is in the release notes.
- In Tune modes no deck is changed except through a proposal.
- Every proposal's numbers pass the ledger.

---

## 5. Order and what can run beside what

```
G.1 true numbers ──► G.2 same hands ──► G.3 the builder decides ──► G.4 Shootout you can read
        │                     │                                                   │
        │                     └──► (M2 stress tests, beside G.4–G.6)              │
        ├──► G.5 the event ──► G.6 siding with evidence ─────────────────────────┤
        ├──► G.7 finding the replacement                                         │
        └──► G.8 versions and the loop (its data recorded since G.1) ──► G.9 Ai proposes
```

- **G.1 comes first and alone.** It changes numbers people already read and starts recording prints.
- **G.2 before G.3 and G.4.** Both use keyed dealing and the comparison.
- **G.5 needs only G.1.** It can be built beside G.2–G.4 by a second agent in its own worktree and ship in its turn. The same
  holds for G.7.
- **G.9 comes last.** It drives every engine the others build.
- **Stress tests (the Mapper's step M2: Ash first, worst case).** This is the only way the simulation will ever value a
  hand trap or an extender. They run on G.2's comparison, so start them after G.2, beside G.4–G.6. They ship as the
  Mapper's own release, between G releases, when ready.

## 6. After this version

- **Phase G, part two (after E):** a side deck chosen from the matchup matrix, tech choices by simulated match win, and
  siding played out in Ai vs Ai series (M5, A5).
- **Shootout:**
  - Each card's duplicate worth, fitted, not assumed (D3).
  - Ai's priors from the cards, asking why, and fatigue (`S.md` §7).
- **Banlist exposure, ranked and backtested (F4's second half).**
- **Prices and a collection (R3).** These wait on kai's decision below.
- **Overlapping roles (B7).**
- **The Mapper's M3 (training) and M4 (puzzles).**

## 7. Risks, and what keeps each small

| Risk | Guard |
|---|---|
| The new print makes every old proof stale at once | Old proofs are read with the print they were made with. |
| The new deal changes kept goldfish results | Each result keeps its `deal`, and replays with it. |
| Paired runs double the cost | Only discordant hands are searched again, and `MapCache` holds what is shared. `MapperBenchTest` holds a one-copy swap to 1.3 × one run. |
| New intervals mislead | Each interval gets a coverage study in simulation before it ships, as Shootout's did. |
| Nine releases is long | Each ships alone and leaves the app better. Prints are recorded from G.1, so G.7 or G.8 can slip without losing data. |
| A redesign moves a page people know | The old view stays one switch away for a release, where kai asks. Studio before-and-after go in the notes. |

## 8. Decisions that are kai's

Each has a default the plan assumes until kai says otherwise.

| Decision | Default assumed |
|---|---|
| Part one of G before Phase E | Yes, as this note sets out. |
| Card against card (PR #6) shipped as it is in G.1 | Yes, once main is merged in and CI is green. |
| Call this version 1.2.0 (a minor bump is kai's call) | Patches 1.1.61–1.1.69; kai names it if they want. |
| May Ai still edit a deck directly? | When the person asks for that edit, yes. When Ai chooses the change, it proposes. |
| Do games against Ai and self-play count in the matchup numbers? | People only by default, the others shown beside them. |
| Field shares weighted by recency and per event | Yes, with the old weighting selectable so earlier webs read the same. |
| Shootout calls at 95 % with a correction | Yes; fewer cards are called, and the calls are right. |
| The Mapper's place on a phone's tab bar | The Mapper joins the bar and Settings moves to ⋯. |
| Prices: their source, and whether to track owned copies | After this version. TCGplayer for TCG, Cardmarket for OCG, no collection until kai asks. |
