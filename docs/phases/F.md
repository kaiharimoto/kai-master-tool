# Phase F: The coach — Ai makes you better, and proves it

**The roadmap's goal:** Ai makes you better, and proves it. It is done when:
- a replay comes back annotated;
- your measured misplay rate over a month is a chart.

**Why now.** Phase E gave the app a value of a turn's end and a search that weighs every way on from a seat's view
(`Regret`, the ladder, the version in use). A misplay can now be measured the way chess measures one: by how much the move
played was worth less than the best the search found, from what the player could see.

**What is not changing.** The engine decides what is legal; the search only weighs. Cards play only by the scripts the
person asked for, and every judgement says how much of the position the engine could play. Hidden is hidden: a decision is
judged from the seat's own view (`Seen`), never from the cards it could not see.

---

## 1. What "done" means

| The roadmap's words | Exactly |
|---|---|
| "Each decision in a replay scored by how much value it lost" | For every decision a seat made in a replay — one gesture of its own, never the table's, never talk — the search weighs every way on from that seat's view, and the decision's loss is the regret of the way it took (`Regret.Weighed.regret`), turned into a drop in win chance. |
| "On Lichess-style thresholds" | Lichess marks a move by the drop in winning chances on its −1…1 scale: 0.1 an inaccuracy, 0.2 a mistake, 0.3 a blunder — 5, 10 and 15 points of win chance on 0–100. The app uses the same marks: `?!`, `?`, `??`. |
| "With the better line shown on the board" | The best way and the line it was valued by open as an unsaved what-if from that step (`openGame`), as Solve this turn does. |
| "Coaching split into small claims, each checked" | A coaching answer is split into its claims and each checked by an instrument (odds, the calculator), the rules (a move's legality on the table) or the search (`duel_search`); what nothing checked is labelled opinion. |
| "Drills from your own mistakes" | Each mistake and blunder becomes a position to find the better move in, on the siding drills' Leitner boxes. |
| "Predictions scored" | Every round logged at an event is scored against what the odds said before it: the expected match win recomputed from the games logged before that round (a backtest, so nothing needs storing first), a Brier score per matchup and overall, with its calibration. |
| "Your misplay rate over a month is a chart" | Mistakes and blunders per 100 of your decisions, by month, over the replays reviewed, each bar with its range and count. |

---

## 2. What it stands on, and what is missing

**Built:** the replay log and its stepping (`DuelRecord`, `DuelGame.stateAt`, `Replays`), the engine state at any entry
(`FxFold`), provenance on every move (`Provenance.by`, `engine`), the search's weighing of every way (`Regret.decide`),
`TurnSolve` and its what-if, the version in use (`h.ladder.inUse`), the records and the ledger (`DuelResults`,
`MatchupLedger`), Prep's events, rounds and odds (`TestStats`, `EventOdds`), the Leitner drills (`Drill`, `DrillStat`), the
fact-check pass (`FactCheck`) and the evidence ledger (`Evidence`, `Numbers`), Shootout's calibration (`Trust.Calibration`).

**Missing, and where each is built:**

| Gap | Built in |
|---|---|
| No "is a decision" rule, and no walk over a replay's decisions by seat. | F.1: `Decisions` — a seat's own gesture (`seat != null`, not talk, not the table's, not a peek), one per group. |
| No mapping from what was played to the search's ways (`Root`). | F.1: a tagged engine move by its tag; a phase, an attack, the end of a turn, a pass by their actions; an untagged move by playing each way the engine offers and comparing the table it leaves. A decision that maps to no way is "not judged", never guessed. |
| The value of a turn's end is not a win chance. | F.1: `WinChance`, a logistic fit of value to the game's result on the ladder's deals, with its fit stated. |
| No annotations beside a replay. | F.1: `<data>/duel/coach/<replay>.json`, synced and backed up with the duel folder. |
| No predictions kept or scored; Brier only in Shootout. | F.2: the backtest over logged rounds, `Brier` per matchup with Shootout's calibration bins. |
| Fact-check runs only in chat, and only with look-ups. | F.2: in duel conversations too, with the table's moves and the search as instruments, and "opinion" on what nothing checked. |
| Drills score siding only. | F.3: a position drill, right when the move chosen is within an inaccuracy of the best. |

**The ceiling, said everywhere.** A decision on a card with no written effect is judged as the engine plays it (inert), and
the review says how much of the position was written. Battle effects are inert; the search plays whole turns and the other
seat's answers at its moments. A review is the search's opinion of a move, with its numbers, never a verdict on the player.

---

## 3. Rules this phase keeps

1. **Judged from the seat's view.** A decision is weighed on `Seen.of(game before it, seat)`, the other seat's hidden cards
   dealt from the prior, never read.
2. **Not judged is a result.** A decision the search cannot map, or whose position it could not search, says so and is not
   counted in a rate.
3. **Deterministic.** The same replay, version and strength give the same review on any device.
4. **A number carries its proof.** Every loss carries its search version, strength, worlds and whether every world was
   searched whole; every rate its count and range.
5. **Stored data outlives versions.** Each new shape goes into `OldDataTest` in the release that writes it.

---

## 4. The releases

| # | Neue / APK | What it does | Stored data |
|---|---|---|---|
| F.1 | 1.1.77 / v1.4.53 | **The misplay finder**: `Decisions`, the mapping to the search's ways, `WinChance`, Review in the replay bar (off the frame thread, Stop), marks on the timeline, the step's words and Show the better line, the monthly misplay chart, `duel_review` for Ai | `<data>/duel/coach/<replay>.json` |
| F.2 | 1.1.78 / v1.4.54 | **Predictions scored and coaching checked**: the rounds backtest with Brier per matchup and calibration in Prep; the fact-check in duel conversations with the table and the search as instruments, "opinion" for the unchecked | none |
| F.3 | 1.1.79 / v1.4.55 | **Drills from your own mistakes**: each mistake and blunder a position drill on the Leitner boxes, in Prep's drills and Ai's `drill` | `PrepDoc.drills` keys `misplay:…` |

**Done when:** F.1 — a replay of the bench decks comes back annotated, its marks held by a test with a known blunder, and
the chart draws from reviewed replays. F.2 — the backtest scores every logged round and a test holds its Brier. F.3 — a
mistake reviewed becomes a drill that climbs a box when answered right.

---

## 5. F.1 as built (1.1.77 / v1.4.53)

**Where.** A kept replay's bar has **Review** beside Play from here and Solve from here. It weighs every decision off the
frame thread, a decision at a time, and **Stop n/m** keeps what is judged so far. The marks stand over the timeline: a
blunder is a filled square, a mistake an open one, an inaccuracy a short bar. Under the timeline, the decision just played:
its mark (`?!`, `?`, `??`), the points of win chance it gave up, what was played and the better way, the search's line, and
**Show the better line**, which goes to that step and opens Solve this turn there. The replay library shows each reviewed
replay's counts and **Your misplays by month**: a bar a month of mistakes and blunders per 100 of your decisions judged,
its 95 % range (Wilson) as a whisker, the count under it.

**How** (`core/duel/coach/Review.kt`):
- `Decisions.of(game)`: a group of entries made by a seat, one decision a group. Never the deal, never the table's own
  moves (`Provenance.TABLE`), never a peek, never a group that is only talk or social.
- `DecisionRoots.matches`: what was played among the search's ways (`Root`):
  - an activation by its tag's card and effect, a procedure by its card;
  - a Normal Summon or Set by its card, `how` and face;
  - a phase, the Battle Phase from Main 1, an attack by attacker and target;
  - the turn's end with no Sets, a pass.
  More than one is read charitably (the least regret); none is "not judged", never guessed. An untagged move the engine
  could have made is not yet matched by playing each way and comparing tables (§2's plan); it is "not judged".
- `Review.judge`: the search in use at Fast (`h.ladder.inUse`) weighs the decision from its seat's view of the table before
  it (`Seen.of`, `DuelBrief.SELF`), the other seat's hidden cards from the table's prior (`searchPrior`), the answers it would
  give with the version's hold. A seat's own turn is judged, and the other seat at a chain or an open table; anything else
  is "not a moment the search weighs". A one-player table and the moves before turn one are not judged either.
- **Win chance** (`WinChance`) is a logistic of the turn player's value, `1 ÷ (1 + e^−(A + B·v))`, fitted by
  `WinChanceFitTest` (`NEUE_FIT=1`): search v0 at Fast against itself on the ladder's 24 deals from both seats, 48 games,
  2,757 decisions. **A −0.605, B 0.141; log-loss 0.645, Brier 0.228 against a coin's 0.250** — a weak reading, said
  beside every review. A won turn is 1, a lost one 0.
- **The drop is world by world**: each way's win chance is averaged over the worlds that valued it
  (`Regret.Weighed.expected`), so one won world among seven middling ones is an eighth of a win, never all of one. The drop
  is the choice's chance less the played way's, for the deciding seat, rounded to a tenth; `Mark.of` is Lichess's 5/10/15.
- **Kept**: `<data>/duel/coach/<replay>.json` (`ReviewBook`, `ReviewCodec`, forgiving), synced and backed up with the duel
  folder, read again when a sync brings replays (`Reviews`, `h.reviews`). Every judged decision carries its worlds and
  whether every world was searched whole; the book carries the version and strength.
- **The rate** (`MisplayRate.byMonth`): only the person's judged decisions count (`Judged.by` = `person`); not judged is
  never counted (rule 2). Months are the duel's own time (the header's), else the review's.
- **Ai**: `duel_review` (read-only, `ReviewWords`) lists the reviews with the monthly rate, or reads one replay's decisions,
  the marked ones first. Reviews are started by the person.
- **Held by** `ReviewTest`: a decision is a seat's own gesture; what was played is read as the search's ways; ending the turn
  with lethal on board is a `??` with the Battle Phase the better way, and taking the Battle Phase gives up nothing; the same
  review twice is the same; the other seat outside its moments is not judged and says why; the marks; the rate; the codec.
  `OldDataTest.aReplaysReviewAsF1WroteItReads`.
- `tools/shoot.sh --page=duel --duel=two --duel-play=true --duel-replay=N --duel-review=bar|library`.

**Not yet.** Battle effects stay inert, Main Phase 2 is not searched (so a Set in Main 2 is judged only as the turn's end),
and the fit is v0's against itself on three bench decks: a stronger fit needs more games, and a person's decks with
unwritten cards are judged as the engine plays them.

---

## 6. F.2 as built (1.1.78 / v1.4.54)

**Predictions scored.** Prep's Day tab scores what the odds said, every round at every event (`core/prep/Backtest.kt`):
- **A backtest, so nothing had to be stored first.** A round is the games of one event's round against one opponent (the
  Day tab logs it as one game with the match's result). Its call is `TestStats.matchAgainst` of the games of the same deck
  against the same opponent logged before its first game — the event reading's own smoothing, a coin's 50 % with none.
  Games after a round never move its call (`BacktestTest`).
- **The score**: Brier, the mean of (said − happened)², against a coin's 0.25, its skill against the coin, each matchup's,
  and the calibration in four bins (finer near a half, where most calls sit). Under ten rounds it is said and never judged.
  A round neither won nor lost is not counted, and says so.
- The history is the ledger's games (`MatchupLedger.select`, the people's by default), as everywhere a deck's games are
  counted. Each round on the Day tab shows "said N %"; under them, **What the odds said, scored**. Ai reads the same in
  `prep_state`.

**Coaching checked at the table.** The fact-check pass (1.0.58) runs in the duel's conversation too, on Ai's answer to the
person's own words — a cue's answer is Ai's play, not coaching (`AiState.duelAsked`, set by `cueAi` for `Cue.SAY`):
- **The coach's checker** (`FactCheck.DUEL_CHECKER`) splits the answer into small claims and checks each with the
  instruments (`calculate`, `hand_odds`), the rules on this table (`duel_state`, `duel_moves`) or the app's own search
  (`duel_search`: "which play is better" is ok when its least-regret way agrees, wrong when it clearly prefers another), with
  card text and rulings as before (`FactCheck.DUEL_LOOK`). It sees the table as Ai's seat does.
- **Opinion is said.** Advice nothing can check is a claim of kind `opinion` (`FactCheck.Claim.kind`): never "ok", counted
  apart in the line ("1 is opinion, unchecked") and marked `~` when opened. A kind of its own, not a new verdict, so a build
  before F.2 reads an opinion as "unsure".
- The check stands under Ai's words in the duel log (`LogLine.AiChecked`, `TableAi.Checked`), its claims redacted as its
  words are. A wrong claim asks Ai for a short **Correction:**, told to move nothing on the table.
- **Held by** `FactCheckTest.coachingAtTheTableIsCheckedAndAnOpinionIsNeverOk`.
- `tools/shoot.sh --page=prep --ydkw=… --prep-demo=true --prep-tab=day`.
