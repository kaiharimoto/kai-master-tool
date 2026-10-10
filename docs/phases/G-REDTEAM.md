# Deck optimization red team (before Phase G)

kai asked on 2026-10-10 for "an improvement red team run on the tools to look for ways that would help me optimize my deck
using everything that's available to us". Five red teams read the code at 1.1.60, each through one lens:

1. opening hands and ratios;
2. the field, matchups, siding and Prep;
3. simulation and measured comparison (the goldfish, the Mapper, Shootout);
4. Ai as the optimizer (its tools, skills and the evidence ledger);
5. the builder, the card data and the loop across deck versions.

Every finding was read in the code. The ones marked ✓ were checked again by hand before this was written. Nothing here is
fixed yet: this note is the input to Phase G's design note (`ROADMAP.md` §4, Phase G), and the bugs in §1 are small enough
to fix before Phase G starts.

Paths: `core/` is `app/core/src/commonMain/kotlin/com/kaiharimoto/mastertool/core/`, and `neue/` is
`app/neue/src/sharedMain/kotlin/com/kaiharimoto/neue/`.

---

## The short answer

- **The arithmetic is sound.** `HandOdds` is an exact multivariate hypergeometric. `HandCounter` (`core/world/HandMath.kt`)
  is exact over overlapping sets with AND, OR and any bounds. `TestStats.matchWin` is right. Wrong numbers come from what
  these functions are given, not from the functions themselves.
- **No tool can answer "is version B better than A, and how sure is that?"**
  - Every engine measures one deck.
  - The goldfish and the Mapper deal hands by position in the list. Cutting one card and adding another therefore re-deals
    every hand, and a comparison of two runs carries about ±3 points of noise at 2,000 hands. That is more than most
    single-card changes are worth.
  - Nothing removes a card to see what is lost.
  - Nothing keeps deck versions, so every practice game, record and report is pooled across versions.
- **Ai cannot prove a deck change.**
  - It edits the deck directly ("act confidently").
  - The skill an "optimize" request loads has it count by hand. It never names the exact tools.
  - The ledger that holds its numbers accepts any equal number in any tool output as proof.
- **Data the app already fetches is thrown away:**
  - the field's side decks and its hand-trap density;
  - card-by-card ratios of other players on your strategy;
  - Shootout's per-card worth;
  - card prices and printings;
  - which cards decided a game.
- **The plan.** Twelve small bugs give wrong numbers today (§1). Fix them first. Then build one spine: keyed dealing →
  paired comparison → `deck_compare` → `deck_propose` → a `deck-optimize` skill (§2, §8). Most of the rest plugs into that
  spine.

---

## 1. Wrong numbers today

All are small (effort S) and need no stored-data change unless noted.

| # | Finding | Where | What it does to a decision | Fix |
|---|---|---|---|---|
| 1 ✓ | **Alternate-art copies fall out of their group.** Groups are written under the canonical passcode (the inspector, the deck menu and the draft all use `card.id`, which `CardIndex.byId` resolves) but read under the cell's raw passcode. | `core/deck/DeckGroups.kt:86-90`, `core/deck/DeckLens.kt:193`, `core/deck/GroupStats.kt:50`, `core/ai/meta/DeckAnalysis.kt:54`, `core/hand/HandGoal.kt:121` | An imported list with 2 Ash + 1 alternate Ash shows Hand traps = 2. "At least one going first" reads 23.7 % when it is 33.8 %. This affects the builder's odds, the group slides, `analyze_deck`, Present's odds module and World's `DeckRead`. `set_groups` says the card "is not in the deck" (`neue/ai/AiHost.kt:797`). The inspector's stepper reads 0 copies (`copiesIn` counts by passcode, `DeckBuilderState.kt:677`). The viewer opens the pool's menu for a card that is in the deck (`neue/builder/CardViewer.kt:223`). | Read through `CardIdentity`: `groupOf(id, cards)` and `countIn(section, gid, cards)`, used by every reader. `copiesIn` goes through `CardIdentity.copiesOf`. A test with `14558127` ×2 and `14558128` ×1. Nothing stored changes. |
| 2 ✓ | **A logged win can lower a matchup's rate.** A split cell with no games borrows the turn's smoothed pooled rate. A cell with one game is pulled hard toward 50 %. | `core/prep/TestStats.kt:183-199`; copied in `core/world/MatchMath.kt:82` | At 25 of 29 sided games won going first, Game 1 going first reads 27/33 = 81.8 %. Logging one Game 1 **win** going first drops it to 3/5 = 60 %, and the matchup's best of three drops from 73.9 % to 68.4 %. Prep defaults to Game 1, and the Duel page always logs Game 1. | Shrink in layers: a split cell toward its turn's pooled rate, and the pooled rate toward the person's own rate across opponents. One function shared by `TestStats` and `MatchMath`, and a test that a win never lowers a rate. |
| 3 ✓ | **The goldfish's hands depend on the list's order.** Hand *k* is a Fisher–Yates riffle of `main` *as listed*, but the fingerprint that labels a result "same deck" ignores order. | `core/duel/effects/goldfish/GoldfishTable.kt:33`, `neue/effects/GoldfishPane.kt:110`, `core/ai/evidence/Ledger.kt:78-80`; the Mapper deals the same way (`core/duel/mapper/Mapper.kt:176`) | After a drag in the builder, a kept result opens a different hand than the one it counted (`GoldfishBrowse.setup`). Re-checking a guide number with the same seed gives another answer. Comparing two versions is unpaired (§2, G1). | Keyed dealing (G1), with the deal's version stamped on `GoldfishResult` so old results still reproduce. |
| 4 ✓ | **The evidence ledger proves a number by coincidence.** `Numbers.values` takes every number in a source, and every n ≤ 100 also as n / 100. `judge` then takes the first source holding the value. | `core/ai/evidence/Numbers.kt:39-45`, `core/ai/evidence/Evidence.kt:146`; reported for `FactCheck.ground` and `recheckGuide` too | "main 40" from `get_deck` proves "bricks 40 % of the time" as **CHECKED**. A stale number can be re-marked as checked by chance instead of as contradicted. Any future proof of a deck change inherits this. | Count a source number as a probability only where it was written as one (%, 0.x, "k in n", or a tool's JSON). Require the claim's card or group words in the matched line. Instruments emit `claims: [{subject, value}]`. Add this exact coincidence to `EvidenceTest`. |
| 5 ✓ | **Ai World's `matchups` instrument counts tournament rounds as practice games.** | `neue/world/WorldSnapshot.kt:108` passes all of `prep.doc.games`, and `core/world/MatchupInstrument.kt:30` does not filter `round == null` | Each event round is counted as a Game 1 game. Prep, `AiPrep` and `ModuleData` all filter rounds out; only the World does not. | Filter `round == null` in the snapshot. |
| 6 ✓ | **"Card for card" counts Main and Extra together.** | `core/prep/PrepPlan.kt:119` (`out.size != into.size`) | A plan with 1 Main card out and 1 Extra Deck card in passes, leaving the Main Deck at 39. `LoungeMatch.check` would refuse it. | Check the post-side deck with `SidingMath.postSide` against the same rules `LoungeMatch.check` uses, one function for both. |
| 7 ✓ | **`analyze_deck` checks legality against `state.format` alone.** | `neue/ai/AiMeta.kt:73` → `core/ai/meta/DeckAnalysis.kt:50` | It says "Legal in TCG." for a deck over the Genesys cap or illegal on the chosen date. CLAUDE.md: "never `state.format` alone". Most deck skills call this tool first. | Pass `DeckRules`. |
| 8 ✓ | **The pool's ban filter and `search_cards ban_status` read the pool's current list.** | `core/search/CardFilter.kt:88` (`card.banStatus(format)`) | Under Genesys or a dated list, "Limited" filters by the wrong list. | A trailing `rules: DeckRules? = null` on `CardFilter`; ban chips read `rules.statusOf` (see §9). |
| 9 ✓ | **`EffectKinds` misreads common text.** NEGATE is `text.contains("negate")`. FLOODGATE matches "neither player can". HAND_TRAP needs a *monster* paying with itself from the hand. | `core/search/EffectKinds.kt:49,65,77-78` | "Cannot be negated" reads as a negator, and "neither player can target this card" as a floodgate. Nibiru, PSY-Framegear Gamma and Infinite Impermanence are not hand traps. `analyze_deck`'s "Hand traps: N … a hand of 5 sees one X %" is built on this, and Ai quotes it. | Strip "cannot be negated" before matching. Drop "cannot target" from floodgates. Count a hand trap as a Quick Effect that summons itself from the hand, or a Trap activated from the hand. Add tests with real card texts as negative cases. |
| 10 ✓ | **The fingerprint includes the Side Deck.** | `core/ai/evidence/Ledger.kt:80` | A side-only edit marks kept goldfish results "not the deck's hands now", which is false. It makes every Mapper board stale and forgets its stress results (`BoardLibrary.rebased`). Guide numbers go stale too. | Fingerprint the Main and Extra Deck, canonically, for odds and simulation. Keep a separate side print for siding. |
| 11 ✓ | **The Effects app's coverage line counts the wrong statuses.** | `neue/effects/EffectsApp.kt:146-147` | "N of M written as code" includes UNSUPPORTED, which the goldfish leaves inert (`FxTrust.INERT`). "To repair" includes WARNED, which the goldfish plays. The line overstates what the simulation knows. | Count `FxTrust.USED` as played and `INERT` as not. Say "played by the goldfish". |
| 12 ✓ | **Mapper and Shootout proofs never go stale, and the Mapper cannot back a line claim.** | `core/ai/evidence/Evidence.kt:19` (`DECK_TOOLS`), `:63` (`RERUNNABLE`), `:130` (lines: goldfish only) | "Makes this board 31 %" from `mapper_library` is refused. Reworded, it is kept as checked forever. `matchup_matrix` and `expected_winrate` are never re-run, and a newly logged game never makes them stale. | Add `mapper_*` and `shootout_*` to `DECK_TOOLS` with the scripts' fingerprint. Let Mapper shares back line claims. Make the Prep tools rerunnable, stale on a new game. |

Smaller, same pass:
- **Ratio sweeps ignore the card's limit.** The `ratios` sweep is clamped to 0..3 (`core/world/HandInstruments.kt:203-204`), so a
  Limited card is reported at 2 and 3 copies. There is no Genesys points column.
- **A candidate card outside the deck's groups silently counts toward nothing** in `ratios` and `siding`. Testing a card not yet
  in the deck is exactly the optimization case. Add an `as` (group) argument, and warn when the card is in no group.
- **The Duel page's games are pooled with real opponents.** This includes games where the person played both seats, and games
  against Ai at any knowledge setting (`neue/duel/DuelAi.kt:83-101`). The only marker is the note.
- **`TestGame.keyCards` is never written** (`core/prep/PrepModel.kt:70`). `log_game` has no input for it.
- **`ygopro_field_snapshot` ignores Genesys by default.** `formatOf(null)` reads only OCG or TCG (`neue/ai/AiMeta.kt:76-81`).
- **The system prompt's page list stops at Settings** (`core/ai/prompt/PromptBuilder.kt:133-141`). Prep, Duel, World, Shootout
  and Mapper are missing, so Ai is never told where the measuring tools live.
- **Wording:**
  - "Five going second, six on the draw" in the comments of `HandGoal.kt:46` and `LensOdds.kt:19`. Five is going first.
  - The "Too many" slide calls two hand traps "flooding" (`neue/builder/GroupSlides.kt:165`).
  - The guide's "Nothing starts. X % of hands." is P(no card of the *first-listed* role), which need not be the starters
    (`core/ai/report/book/GuideFacts.kt:84-86`).
- **Latent:**
  - Placement weights match by substring (`core/remote/YgoProDeckDecks.kt:450-460`): "21st" would read as a win and
    "Top 48" as a Top 4. The fixtures hold only "Top 4" and "Top 8".
  - A strategy that is ≥ 60 % of all lists loses its own engine from "Core", because `core` excludes `staples`.

---

## 2. The spine: is version B better than A, and how sure?

This is the lever under almost everything else. Phase G's "Done when" asks for "a simulated gain and a confidence interval",
and nothing designed so far produces one.

**G1. Keyed dealing (common random numbers).** *Impact H, effort M. New.*
- Give each physical copy a key: hash(seed, *k*, canonical passcode, copy number). Hand *k* is the five or six smallest keys.
- The deal no longer depends on the list's order.
- Two versions give every shared copy the same key, so their hands differ only where an added or removed copy ranks in the
  top five.
- The goldfish, the Mapper and Shootout's `Reporter` pools all use it.
- Stamp the deal's version on `GoldfishResult` and `MapperRun`, and keep v1 for pinned old results (`GoldfishSeedTest`,
  `OldDataTest`).
- Example arithmetic: at 2,000 hands per version near a 50 % rate, the unpaired difference is known to about ±3.1 points.
  Paired, with about 4 % of hands differing, it is known to about ±0.9 points. That is roughly twelve times fewer hands for
  the same certainty.

**G2. `VersionCompare` in `core/duel/mapper/compare/`, pure and tested.** *Impact H, effort M after G1.*
- Inputs: deck A and a variant, either a saved deck or ops ("cut X, add Y") so nothing has to be saved; the ask (a
  `BoardPreset` or an `EndBoard`); going first or second; the number of hands; a seed.
- Output:
  - the four paired counts;
  - the difference with a paired 95 % interval (Newcombe) and an exact McNemar test;
  - the hands that changed, each one openable.
- Sequential stop: stop once the interval excludes 0, or once it lies within ±1 point.
- Shown as "Compare with…" on page 10 and in the goldfish pane.
- Ai's tool is `deck_compare` (§8).

**G3. Card ablation, "without it".** *Impact H, effort S after G2. New.*
- `ablate(card, copies = one | all, replaceWith = blank | card)` over `StarterTable.engine(main)`, with a blank card as a
  synthetic passcode with no facts.
- Per card: the change in the ask's share with its paired interval, the boards lost, and the hands that become bricks.
- Shown as a "Without it" column on the Starters tab. Ai gets `mapper_ablate`.
- ROADMAP §9's "ablation runner" is a different thing: it is Shootout's test of Ai's learning pieces (S.md §6½), not of
  cards.

**G4. A guard against coverage bias.** *Impact H, effort S. New.*
- A comparison is biased, not merely bounded from below:
  - Cards without a trusted script are inert, so a cut of a written X for an unwritten Y always looks worse.
  - Hand traps are worth nothing with no opponent.
  - Undecided hands count as misses: at a 5,000-move budget, 12–14 % of hands were undecided (D.md §5.7).
  - The Mapper gives dealt hands a fifth of the starter table's budget (`Mappers.RUN_BUDGET`). A version with more extenders
    branches more, runs out more often, and is tilted *against*.
- `VersionCompare` should:
  - refuse, or flag, when a differing card is inert but not a blank, with **Write these** beside it;
  - say plainly when the swap is engine for non-engine: "the goldfish cannot value Ash: that is a stress test or a Shootout
    question";
  - report undecided hands per version, and the difference's bounds with them counted both ways;
  - search the discordant undecided hands again at five times the budget;
  - show "N of M engine cards trusted" beside every Mapper share. Today `MapperReport.run` never names an inert card.

**G5. Exact starter odds from the starter table, with no sampling.** *Impact H, effort S. New.*
- Turn the `StarterTable` rows whose boards pass the ask into a condition, and count it exactly with `HandCounter`.
- The result is an exact P(open a mapped starter or pair), first and second, instant and needing no seed.
- Sweep each card from 0 to its limit with the deck size held.
- Label it a lower bound (singles and pairs only).
- It also audits the person's groups: "Y reaches a board alone but is in no group", "X in Starters reaches nothing alone".

**G6. Runs that survive a version switch.** *Impact M-H, effort M. New.*
- Today `startHands` writes over `run.json`, a fingerprint change makes every board stale and forgets its stress results,
  and `BoardLibrary.add` drops lines found on another version.
- Keep runs per (fingerprint, seed, side), newest N.
- Keep lines and stress results keyed by the deck and scripts fingerprints instead of dropping them.
- Add a device-only `MapCache` keyed by the reduced hand, the cards scripts can pick, the Extra Deck, the scripts
  fingerprint, the budget and the side. With G1, a one-copy swap re-searches only about one hand in eight going first.

**G7. Stress tests (M2), narrowest first.** *Impact H, effort L. Planned: M.md §3, step M2.*
- Ash alone, one interruption, worst case. `Op.Negate` and `Cond.Newest` already cover Ash and Called by the Grave.
- Lingering negation (Veiler, Imperm) is missing from the vocabulary.
- Record `through:ash` per hand, and let `VersionCompare` compare on it.
- Weight it by the field's chance the opponent opens Ash (§5, F1).
- This is the only way the simulation will ever value a hand trap or an extender over a starter.

---

## 3. The builder's own numbers

**B1. Questions in the Groups panel, and ±1 beside the copy stepper.** *Impact H, effort M. New, though the stored half
exists.*
- `HandGoal`/`HandGoals`/`GoalOdds` hold compound questions ("Starters ≥1 & Hand traps ≥1 & ungrouped ≤1"). They are kept
  in the payload's `goals` key and have a full editing API in `DeckBuilderState` (`newGoal`, `openGoal`, `oddsOf`), but
  **nothing in Neue calls it**.
- The builder shows only "at least one per group" (`GroupsPanel.kt:167`, `GroupSlides.kt:67`).
- The tools that answer ratio questions, the instruments `openings`, `ratios` and `optimize`, are reachable only through
  Ai World. Their default conditions are "each group ≥1", not the deck's stored goals.
- Proposal:
  - A Questions strip with each goal's odds first and second, recomputed on every edit, with a signed change since the last
    step.
  - The inspector's stepper shows each question at −1 / now / +1 copy, with the deck size held by a blank (the `ratios`
    resize logic).
  - "Sweep…" opens `ratios` inline.
  - "Best counts" runs `optimize` off the frame thread.
  - Extend `HandGoal` with an optional condition in the `Goals.parse` grammar, keeping `asks` readable.

**B2. `hand_odds` asks the questions players ask.** *Impact M, effort S. New.*
- Today it takes at most two sets, "at least" only: no at-most, no none, no OR, no deck change (`core/ai/AiTools.kt:541`).
- Add a `condition` input (the Goals grammar, through `HandCounter`), plus `with`/`without`/`deck_size`, so "+1 Ash, −1
  Called by" is one call.

**B3. Searchers in the odds.** *Impact M, effort S-M. New.*
- `card_web` computes each card's "access" (its copies plus the copies of every card that searches it), but only as a
  table.
- Add a `reach(X)` word to the grammar: X or anything that fetches it, one level deep.
- Add a "Starters including searchers" row to `openings`.

**B4. The value of the 41st card.** *Impact M, effort M after G1/D2. Partly Phase G.*
- `optimize` ranks by probability, so for "≥" goals it always picks the smallest deck.
- A "41st card" study adds one copy of a candidate and judges it three ways in one table: the conditions' odds, a paired
  goldfish variant, and a Shootout contrast.

**B5. Legality-aware sweeps.** *Impact M (H for Genesys), effort S. New.*
- Cap every sweep at the card's limit under `rulesInForce`.
- Add a Genesys points column, and a `points ≤ cap` constraint to `optimize`. Under Genesys, points are the real trade-off.

**B6. Marginal worth instead of lift.** *Impact L-M, effort S. New.*
- `openings`' "what each card is worth" is P(goal | card in hand) − P(goal), going first only (`HandInstruments.kt:150-165`).
- Members of the condition always score about +(1 − base), and every other card scores below zero, so the headline names a
  card the question already names.
- Report Δ P(goal) for ±1 copy, first and second.

**B7. Overlapping roles.** *Impact M, effort M. New.*
- A card belongs to one group, by design (`DeckGroups.kt:21-32`), while `HandCounter` handles overlap.
- Secondary tags in the payload (`tags: Map<CardId, Set<String>>`; unknown keys already pass through) would let a question
  say "Starters ≥1 & Extenders ≥1" with one card in both.

---

## 4. Shootout's numbers, put to use

kai's own judgments are the best signal the app holds about hands it cannot simulate. Today they never leave page 09.

**D1. `shootout_results` for Ai and the builder.** *Impact H, effort M. New.*
- `ShootoutResults` holds each card's worth per stratum with 80 % and 95 % ranges, pairs and per-stratum win rates. Nothing
  outside the package reads it.
- `shootout_state` returns only trust data, for the page's current target.
- Proposal:
  - A look-only tool: per card, copies, worth ± range, trials and calls, per stratum.
  - The inspector: "Shootout: +2.1 per copy (−0.3 … +4.5), 38 trials".
  - Siding: the lowest-worth Main Deck cards in that stratum as out-candidates.
  - Prep: Shootout's per-stratum rate beside the logged one.

**D2. The next copy's worth, and what-if.** *Impact H, effort M. Planned: S.md §5, §7 "Left".*
- `Reporter.variant(deck′)` gives E_variant[w] − E_base[w] on coupled pools (G1).
- It offers ±1 per card and swaps.
- A card with no trials reads "unrated", never a number.

**D3. The worth of a duplicate is assumed.** *Impact M-H, effort M. New.*
- `copyDecay = 0.5` for every card (`core/shootout/model/ModelSpec.kt:83`). A hard once-per-turn starter's second copy in
  hand is worth far less than half, so any "third copy" number is inflated by assumption.
- Fit a per-card duplicate parameter, shrunk to a role mean, with a prior read from the text (hard OPT, soft OPT, none).
- Let the picker target hands holding two or more copies.

**D4. Calls need the right ranges.** *Impact M, effort S. Planned: S.md §5 verdicts.*
- "Its range is below zero, so a copy could go" is said on 80 % ranges across about 20 cards × 4 strata
  (`ShootoutResults.kt:117`, `ShootoutWords.kt:114`). False calls are expected.
- Use 95 % with a Holm correction, or the posterior chance below zero ≥ 0.95.
- Say "a copy could go" only when the ablation (G3) agrees, and name a replacement.

**D5. A Mapper judge.** *Impact M, effort M. Planned: S.md §6 item 5.*
- Add a `MapperJudge` in `core/shootout/sim` for the deck-alone strata.
- One "Card value" table, with Shootout worth, ablation change, starter rows and coverage side by side, showing where they
  disagree.

---

## 5. The field

**F1. `FieldProfile`: what the field interrupts with, and what it sides.** *Impact H, effort S-M. Consumers planned (M.md §3.4,
Phase E); the profile is new.*
- Every list's Side Deck is read (`core/remote/YgoProDeckDecks.kt:399`), then dropped: `FieldBuilder.cardsOf` uses the Main
  and Extra Deck only.
- `staples()` is computed only to exclude cards from a strategy's core.
- Per strategy, weighted by share:
  - the main deck's hand traps and negates, and P(opponent opens ≥1 / ≥2), for five and six cards;
  - side-deck frequencies (% of lists, mean copies);
  - "what the field sides against strategy X".
- This feeds the stress suite (G7), the goldfish's targets, and a draft of "how they side against you" for an unlinked
  matchup.
- Surfaces: a tool `field_profile`, and "Interaction" and "They side" lines in `ygopro_field_snapshot`.

**F2. Your list against the field's lists of the same strategy.** *Impact H, effort S. New.*
- `FieldCluster` keeps every member list but reports only `core` (cards in ≥ 80 % of lists, no counts) and one
  representative.
- The ygoprodeck skill asks Ai to "compare two lists of one strategy by what differs" with no tool to do it, so it reads lists
  one by one.
- `StrategyRatios`, per canonical card and section (side included): the weighted share of lists playing it, its mean and modal
  copies, and yours.
- Buckets: "the field plays it, you don't", "you run more or fewer", "your techs (≤ 10 %)".
- A consensus list (modal counts) as the web's stand-in, instead of one pilot's list.
- Surfaces: a tool `field_compare`, and the inspector's "Field: 3 in 88 % of 41 lists like yours".
- Counts only. Never claim a card causes a placement: there are too few lists, and they are confounded.

**F3. Honest shares, and a trend.** *Impact M-H, effort M. New.*
- The weight is placement × size, summed per list (`YgoProDeckDecks.kt:48,450-467`).
- There is no age term, though `RecentDecks.ageOf` exists. An event that publishes a deep cut outweighs a regional with only
  a Top 8.
- Add:
  - a per-event budget and a recency half-life;
  - presence (share of tops) apart from conversion ("over-performs ×1.4");
  - `FieldTrend`: cluster once over two windows (`as_of` already reads back), and give each strategy's change with a Wilson
    range, banlist changes marked.
- Record a share's source (top cut, estimate or by hand) on `WebEntry` as a trailing field.

**F4. Banlist exposure.** *Impact M, effort M (L with the backtest). New.*
- `Banlists.historyOf` is read only by Ai's `banlist` tool and World scripts.
- `BanRisk.signals`: times restricted, the last movement, the share of weighted tops across strategies, and whether the card
  is core to a top strategy.
- Words and computed shares only, never a probability (the evidence rule).
- Backtest the ranking against past lists, as a Trust set.

---

## 6. Matchups, siding and the event

**M1. Show the range, and say which games to play next.** *Impact H, effort S. Scoring afterwards is Phase F; this is new.*
- `MatchMath.field` already computes a 95 % interval. Only the World's instrument shows it.
- Prep shows a bare point (`neue/prep/PrepPage.kt:486`), and so does `expected_winrate`.
- The tournament-prep skill ranks practice by "share × weakness", but practice reduces uncertainty, not weakness.
- Proposal:
  - Show point, range and n everywhere.
  - Add `PracticePlan.next(rows, shares)`: for each opponent and each of the four cells, how much five more games would
    narrow the field's range. "Next: 5 sided games going second vs Yubel (−4 points of width)."
  - Add `Policy.cutChance(p, rounds)`: the binomial tail to the record `Policy.cutRecord` names, at the person's rate instead
    of 0.5.

**M2. Side deck coverage, and a legal post-side deck.** *Impact H, effort S. A prerequisite for Phase G's "a side deck chosen
from the matchup matrix".*
- Nothing aggregates siding across matchups.
- `SidingMath.postSide` has no caller outside a test.
- The World's `siding` instrument takes typed lists, not the saved plans.
- `SideCoverage(deck, siding, shares)` gives:
  - per side card, the share of the field it comes in against, first and second;
  - dead side slots;
  - Main Deck cards sided out against more than half the field (candidates for the side);
  - side cards brought in against more than half the field (candidates for the main);
  - copies needed against copies held;
  - each plan's post-side legality (bug 6).
- Run every saved plan through the `siding` instrument's exact before-and-after odds.
- Surfaces: a panel on Siding, a block on the guide's first page, a line in `EventCheck`, and `side_coverage` for Ai.

**M3. First or second, per matchup.** *Impact M, effort S. New.*
- In a best of three, the loser's choice does not change the match: P(2–0) and P(0–2) are symmetric in it. Only Game 1's
  choice on a won roll matters.
- `TestStats.turnCall(row)`: P(Game 1 going second beats going first), from the two posteriors, with Shootout's Game 1
  strata where present.
- "Win the roll vs Yubel: go second (83 % sure, 14 games)."
- Flag rolls chosen against the person's own data. `DuelResult.rollWinner`/`choseFirst` are stored and unused.

**M4. The rest of the field, and time.** *Impact L-M, effort S. New.*
- `expected()` renormalises over the listed shares, so a web covering 85 % spreads the other 15 % over its decks.
- Add an explicit "Other" share, at a rate the person sets.
- Count P(match unfinished) from logged minutes as a loss, which it is under §V.B.

**M5. Siding plans played out.** *Impact M, effort L. Phase G.*
- An Ai vs Ai series: seeded pairs with seats swapped, best of three, each deck's `SidePlan` applied through `postSide`.
- Recorded as "simulated" (§7, L2).
- Its value is capped by Ai's play strength until Phase E.

---

## 7. Versions and the learning loop

"I changed my deck last week. Did it get better?" No part of the app can answer that today.

**L1. Deck versions.** *Impact H, effort M-L. New. Phase G's "suggestions scored against your results" needs it first.*
- `Deck.sq`'s upsert is `INSERT OR REPLACE`, the undo stack lives in memory, and duplicating a deck records no parent.
- `TestGame`, `DuelResult` seats and `SessionReport` key on the deck id only, so every version's games are pooled into one
  matrix.
- There are two incompatible fingerprints, the ledger's and Shootout's, and neither can be turned back into a list.
- Proposal: `core/deck/DeckVersions`.
  - A version is the canonical Main and Extra counts, with the side apart: the prints, the lists, a time, an optional label
    and a parent.
  - Stored one immutable file each, `<data>/decks/versions/<deckId>/<print>.json`. Because each file never changes, sync's
    newer-wins rule is safe. The files are backed up and deleted with the deck.
  - A version is made on save when the print changes, and whenever something is measured against the deck.
- A trailing `deckPrint: String? = null` on `TestGame`, `ResultSeat`, `SessionReport` and `StoredTrial`, with the old shapes
  added to `OldDataTest`.
- `compare_versions`: the card changes in words (`DeckHistory.describe`), Prep's rates per version with Wilson ranges and a
  plain "too few games to tell", and goldfish rates per target.
- Duplicate records a parent. Today it carries Ai's learning but none of the measurements (Shootout, goldfish, Mapper,
  combos, Prep games).

**L2. One matchup ledger.** *Impact M-H, effort M. New; Phase F's backtest needs it.*
- Prep's `TestGame`, the Duel page's records, the Lounge's (no game number, no siding state) and Ai vs Ai's are four
  islands.
- A `MatchupLedger` reads them all, each tagged with a source: people, vs Ai at a knowledge setting, self-play, simulated.
- The matrix counts people only by default, with the other sources beside it.
- Trailing fields: `TestGame.source`, and `game`/`match`/`sided` on Lounge results. Old records read as "unknown, Game 1".

**L3. Opponent identity by strategy, not id.** *Impact H, effort M. New.*
- Games are keyed by a web deck's id or a typed name. Games against "Yubel" typed by name never count for the web's Yubel
  deck.
- A new web for the next event, or a re-imported list, starts from zero practice data.
- Resolve identity when the data is read: an opponent through `FieldBuilder.similarity` ≥ `SAME_STRATEGY`, plus name and
  `Matchup.covers`; your own deck through the lineage in L1.
- Show "n games from earlier lists", which the person can include or leave out. Nothing stored needs migrating.

**L4. What decided the game.** *Impact M, effort S-M. New.*
- Write `keyCards`, which `log_game` and the Prep form should both take.
- Add `TestGame.duel` (the replay link) and the opening hand, read from the replay's `SeatSetup`.
- Add `TestStats.byCard`: the win rate with a card in the opener and without, and the brick rate per version against the
  exact P(no starter), all with Wilson ranges and a sample-size caveat.
- Label it as association, never cause.

---

## 8. Ai as the optimizer

Today "optimize my deck for the next locals" would most likely end with direct `edit_deck` changes, backed by
hand-counted arithmetic, with no reason or proof kept.

| Engine | Ai can run it | Ai can read it | Tool |
|---|---|---|---|
| Exact hand odds | Yes, at most two sets, "at least" only | Yes | `hand_odds`, `calculate` |
| Instruments (`openings`, `ratios`, `optimize`, `siding`, `combos`, `card_web`, `matchups`) | Only inside an open World, one run at a time, saved deck ids only | Yes, cut at 4,000 characters | `world_tool` |
| Goldfish | On a saved deck | Its own runs; the Effects app's: no | `world_tool goldfish` |
| Mapper | On the builder's saved deck | Yes | `mapper_map`, `mapper_starters`, `mapper_library` |
| Shootout ratings | **No** | **No** | none |
| Field clustering | Yes | Share, core and representative; ratios and sides dropped | `ygopro_field_snapshot` |
| Prep statistics | No | Pooled across versions and sources | `matchup_matrix`, `expected_winrate` |
| Ai vs Ai | **No** | Results only | `duel_records` |
| A deck variant or duplicate | **No** | — | none (`new_deck` loses groups) |

**A1. `deck_propose`: a change as a reviewable proposal.** *Impact H, effort M. New; Phase G's "Done when" assumes it.*
- The prompt says "Every deck edit lands on the builder's undo, so act confidently" (`PromptBuilder.kt:146`).
- `editDeck` keeps only an undo label.
- The ```compare block has no Apply.
- The fact-check skips advice ("opinions and advice are not claims").
- In Fine Tuning, a change is barred and Ai is told to "suggest the change in words instead", so it ends up in a report
  nothing acts on.
- Proposal:
  - `deck_propose {deck_id, title, ops, why, evidence: [tool-call or compare ids], expect: [{metric, before, after,
    interval}]}`, kept in `<data>/ai/proposals/<deck>.json`.
  - A card with Apply / Not now, like `FxRequestCard`. Apply goes through `editDeck`.
  - `why` and `expect` go through `Evidence.judge`.
  - It is allowed in the Tune and study modes, since it changes no deck.
  - Accepted proposals are later scored against results (L1).
- The prompt line becomes "propose, then apply on yes" for any change that is the person's choice.

**A2. A `deck-optimize` skill, and an honest `deck-assessment`.** *Impact H, effort S. New.*
- `deck-assessment` (`core/ai/skills/BuiltInSkills.kt:66-90`) predates every engine. It teaches "1 − C(40−s,5)/C(40,5)" and
  "5 × n / 40" by hand, then "offer to make the change with `edit_deck`".
- The new skill:
  1. Plan.
  2. A computed baseline: openings first and second, `card_web`, `fx_state`, the Mapper if scripts exist.
  3. The field: `field_profile`, `field_compare`, the matchup matrix with n.
  4. The person's judgments: `shootout_results`, and the playbook by matchup and by card.
  5. Each hypothesis with the metric that would prove it.
  6. `deck_compare` on the same seed, keeping only gains that clear their interval.
  7. `deck_propose`, applied on yes.
  8. Siding through `side_coverage`.
  9. Write-up through the ledger.
- Its rules:
  - No percentage from memory.
  - A change with no metric is labelled "(judgment)".
  - Name any playbook line a cut breaks.
- Repoint `deck-assessment` at `hand_odds` and the instruments, and finish the prompt's page list.

**A3. `deck_compare`, and instruments without a World.** *Impact H, effort M after G2. Phase G names it; the harness is new.*
- `Instruments.deckFor` takes a saved id only.
- The Mapper maps only the builder's deck, and its runs overwrite each other.
- `world_tool` fails with "No world is open".
- `delegate` helpers cannot run instruments.
- Proposal:
  - `deck_compare {base, changes | variant_deck_id, studies, conditions, target, hands, seed}` returns paired deltas with
    intervals and a `compare_id` that `deck_propose` cites.
  - `deckFor` accepts `{base, out, in}`.
  - The pure hand instruments run without a World, and are look-only.

**A4. What a cut breaks.** *Impact M, effort S-M. Planned: AI-INTELLIGENCE.md §3, "carried from the old roadmap".*
- Lines live in three places: the Duel page's combos, the playbook's lines and the Mapper's starter table. No edit checks any
  of them.
- `DeckDependents.of(removed, …)`: on an edit that removes a card's last copy, "− Engraver: used by 3 combos, 5 mapped
  boards, 2 playbook lines", with Check lines.
- `edit_deck` and `deck_propose` report it too.
- The `combos` instrument falls back to the playbook's lines when no combos are saved.

**A5. Ai vs Ai on request.** *Impact M (weak until Phase E), effort M. Phases E and G.*
- `startAiVsAi` is reachable only from the person's dialog.
- A `match_request` offer, like `fx_request`, with the cost said and the person pressing Start.
- Results are counted apart (L2).

---

## 9. Finding the replacement card

**R1. Cards like this one, and more cards for this role.** *Impact H, effort M. New.*
- Search has names, text and facets, but no likeness.
- `search_cards` has no "like" mode and no "legal now" option.
- `CardLikeness` scores on:
  - `EffectKinds` Jaccard (after bug 9);
  - category, Spell/Trap property or monster frame;
  - Level/Rank ±1, attribute and race;
  - archetype, or the text naming it;
  - shingle Jaccard over the text.
- Filtered by `DeckRules`, Genesys points left, and cards already at their limit.
- A group's profile searches for its role. It never assigns: assignment stays manual (`DeckGroups.kt:23`).
- Results go through `CardFilter.onlyIds`, so the pool needs no new chrome.
- Entry points: "Like this" in the inspector, "More like these" on a group row, and `similar_cards` for Ai.

**R2. Search that knows the rules in force.** *Impact M-H (decisive in Genesys), effort S. New.*
- Trailing `CardFilter` fields: `rules`, `legalOnly`, a `points` range, `releasedAfter`, and `notYetInTcg` (OCG-dated with no
  TCG date: "what is coming for my archetype").
- `CardSort.POINTS` and `CardSort.NEWEST`.
- The same inputs in `search_cards`.

**R3. Prices, printings and popularity.** *Impact M, effort M. New.*
- The pool query already returns `card_prices`, `card_sets` and `misc_info`'s `staple`, `views` and `md_rarity`.
  `CardDto` drops them ("the rest … are ignored").
- Parse them into a device-only `<data>/prices/pool.json` at each pool sync, never synced: no extra request and no schema
  change, the same pattern as `<data>/banlists/`.
- `DeckCost`: canonical copies × the price from a chosen source, minus owned copies. Unpriced cards are counted, never
  guessed.
- A price sort and a "≤ $N" facet for budget replacements, and a "staple" facet that can also cross-check `EffectKinds`.
- Every price says its source and date.
- Owned copies need a new synced preference, `collection`, which is kai's call (below).

---

## 10. The order to build it in

1. **The bugs in §1, in one release.** They are all small. Bugs 1, 2, 4 and 9 change numbers people already read. Bug 10
   changes when results go stale, and needs no stored change. Ship on both tracks, since the tablet shows the same builder.
2. **The spine:**
   - G1 keyed dealing;
   - G2 `VersionCompare`;
   - G4 the coverage guard;
   - G3 ablation;
   - A3 `deck_compare`;
   - A1 `deck_propose`;
   - A2 the `deck-optimize` skill.

   This is the first release in which "cut X for Y" carries a measured gain with a range.
3. **Cheap, high-yield reads of data already held:**
   - G5 exact starter odds;
   - D1 `shootout_results`;
   - F1 `FieldProfile`;
   - F2 `field_compare`;
   - M1 the range and the practice plan;
   - M2 side coverage;
   - B1 questions in the builder;
   - B2 `hand_odds` conditions.
4. **The loop:** L1 deck versions, then L2 the ledger, L3 opponent identity and L4 what decided games. These carry
   stored-data changes, each with `OldDataTest` cases.
5. **Larger builds:**
   - G7 stress tests (M2);
   - D2/D3 Shootout's what-if and duplicates;
   - R1 likeness;
   - F3 shares and trend;
   - R3 prices;
   - F4 ban exposure;
   - M5 the siding series;
   - A5 Ai vs Ai on request.

## 11. Decisions that are kai's

| Decision | Default assumed until kai says |
|---|---|
| May Ai still edit a deck directly when asked to, or does every change go through `deck_propose`? | Direct when the person asks for that edit; a proposal whenever Ai chooses the change. |
| Should the matchup matrix count games against Ai and self-play? | People only by default, with the other sources shown beside it. |
| Prices: which source and region (TCGplayer, Cardmarket, the cheapest printing)? Should the app track owned copies? | TCGplayer for TCG and Cardmarket for OCG. No collection until kai asks. |
| Should field shares carry recency and a per-event budget? | Yes, with the old weighting kept selectable so earlier webs read the same. |
| Shootout calls at 95 % with a multiplicity correction, though fewer cards will be called | Yes. |
