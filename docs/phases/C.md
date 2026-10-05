# Phase C: The measured duel (1.1.x)

**Goal:** every duel is a record you can measure, and Ai at the table sees and does only what a player would.

**Done when:**
- "Ai won N of M against kai, with these settings" can be read from the records;
- every duel lead in `docs/AI-INTELLIGENCE.md` §1 is closed and held by a test, or carried with a reason;
- the puzzle set has a baseline score.

**What it builds on.** The duel is already a log (`DuelGame`: a fold over `DuelAction`s, randomness stamped on commit),
redacted per seat (`DuelView`, `DuelSight`), with Ai at the table through one set of tools (`AiDuel`) and the network's
guest through another door (`DuelHost`). What it lacked: the log said *what* happened and *which seat* did it, never
*who* — the person, Ai, the network's guest or the table itself — nor how the table was set when they did. So nothing
could count Ai's results, and nothing could prove Ai played fair.

---

## 1. Provenance on every move (stage 1)

**The shape.** Each `DuelEntry` carries `by: Provenance?` (`core/duel/Provenance.kt`):

| Field | What it says |
|---|---|
| `by` | `person` (at this device), `ai`, `guest` (the network's), or `table` (a turn's draw made by itself) |
| `aiSeat`, `aiKnows` | the seat Ai held then and its knowledge (`self`, `auto`, `full`), when Ai sat at the table |
| `eyes` | the person's hot-seat eyes then (`all`: both hands face-up; `seat`: their own) |
| `view` | Ai's own moves only: the first 16 hex digits of the SHA-256 of the `DuelView` it acted on |
| `peeks` | Ai's own moves only: the peeks it had taken before |
| `peek` | the entry is one of Ai's peeks (`duel_peek`) |
| `net` | a networked table |

**Stamped on commit, as the dice are.** The page says who is moving and how the table is set (`Duels.provenance`, from
`Duels.context`, which `NeueHolders` points at the preferences); the log adds what only it knows (`Provenance.seal`,
called by `DuelGame.act`, `Replays.insert` and `DuelHost.act`). Ai's fingerprint is taken of the table the move was made
on, through Ai's own seat and knowledge, so a record proves which view Ai acted on **without holding anything hidden** —
a hash, never the cards. Anyone else's move carries no fingerprint, whatever it was handed.

**The opening roll** is already in the log (`OpeningRoll`, `GoFirst`); with provenance, who threw and who chose is known
too. The deal (the first shuffles and draws) is the table's own and carries none.

**Stored data.** One optional field on each entry of `<data>/duel/current.json` and every replay in
`<data>/duel/replays/`, left out when empty. A duel or replay written before has none and reads as it did, its moves
counted as no one's; an older build skips the key. `OldDataTest` holds both shapes.

## 2. A duel's result as a record (stage 1)

**The end.** A duel ends as it already did for Prep (1.0.80): a concession (`DuelAction.Concede`), or life points at 0 —
both seats at 0 together is a draw (`DuelResults.ending`). A one-player table never ends as a result. Undo can take an
end back; its record goes with it.

**`DuelResult`** (`core/duel/record/DuelResults.kt`), read off the log, never typed:
- the seats: name, deck, and who played each (`person`, `ai`, `guest` — whoever made most of its moves), with the counts;
- who had turn 1 and how it was decided (`roll`: the dice's winner chose; `set`: the table's first seat), each round's
  sums, the roll's winner, what it chose and who made the choice;
- the winner (none for a draw), how (`concede`, `lp`, `draw`) and the turn it ended in;
- Ai's play: its seat, its knowledge across the duel (`self+full` when it changed), its peeks, its moves, and whether
  each side played only its own seat (`clean`: Ai never moved the person's seat, the person never moved Ai's);
- networked or a hot-seat, and the person's eyes;
- `whatIf` for a duel played on from a replay — not a game of its own.

**Kept** one file a duel, `<data>/duel/records/<id>.json` (`DuelRecords`, a part of `Duels`): two devices never write the
same file, so sync's newer-wins never loses one. Synced and backed up as replays are (everything under `duel/` but the
duel in play), reloaded after a sync or a restore.

**The summary** (`DuelResults.aiAgainst`, `words`, `summary`; pure, tested): only duels where one seat was Ai's and the
other a person's (here or the guest), grouped by the person and by how the table was set — Ai's knowledge, the person's
eyes, networked or not, peeks or none, clean or not, the dice or not — because a win with full knowledge, or against a
person who saw Ai's hand, is a different number:

> Ai won 2 of 4 against kai (1 drawn), with these settings: Ai's knowledge its own seat's eyes; kai seeing only their own
> hand; the dice deciding who went first (Ai first in 4).

**Where it shows.** Ai reads it with `duel_records` (read-only: the summary, or the newest records one a line). The
person sees it in **Replays** on the Duel page, above the list: one quiet line per person and setting.

**Prep logs the truth.** A finished duel logged to Prep (`logFinishedDuel`) now takes going first or second from who had
turn 1 (`DuelResults.practice`): the red team found it always logged seat 0 as going first, wrong whenever the opening
roll decided otherwise. A draw is logged as a draw.

## 3. The duel leads (stage 1)

| Lead | Status | Held by |
|---|---|---|
| Ai's own moves may reveal, flip or take the person's hidden cards; the knowledge cap covers reads only. | **Was open.** The command line reaches a hidden card by its coordinate for the verbs that need not know it (`reveal oh1`, `flip os1`), so Ai's lines could. **Fixed:** Ai is held to the guest's rules, one list for both (`DuelReach`, from `DuelHost.resolve`'s 1.0.85 checks): another seat's hidden card may go to its owner's piles or side, never to Ai's side, hand or under its cards, never face-up by Ai's flip; Ai reveals only its own cards; a card in a hand or Deck is never a target. `duel_act`, its `at`, and `duel_combo run` check the whole line first (`ComboRunner.reach`). | `DuelLeadsTest.aisOwnMovesNeverRevealFlipOrTakeThePersonsHiddenCards`; the guest's side still by `DuelRedTeamTest` |
| On a networked table, Ai's tools read and move the guest's seat (`aiSeat` defaults to 1). | **Was open:** no duel tool asked whether the table was networked; `duel_state` read seat 1's hand, `duel_act` moved it through the host. **Fixed:** at a networked table Ai holds no seat (`AiTable.refusal`): the tools that read or move the table are refused; a deck's combos, house rulings and `duel_records` stay open. Its moves there are recorded as nobody's Ai, and `duelContext` gives no Ai seat. | `DuelLeadsTest.theGuestsSeatOnANetworkedTableIsTheGuests` |
| `duel_combo` records name hidden cards Ai touched. | **Was open:** `ComboRecorder` named every card. **Fixed:** recorded for a seat, a card is named only when that seat knew it (seen before or after the move, or from its own Deck or Extra Deck); any other is written by where it stood (`os1`), and a step with no such place is left out. Applied to Ai's `record`, Record this turn and Save as combo. | `DuelLeadsTest.comboRecordsNameNoHiddenCard` |
| `duel_act at` rewrites the past and re-deals later draws. | **Was open:** a move put into the past could change what every later draw took, and what it left to chance was salted by the log's length, so undoing and inserting again fished for a new shuffle. **Fixed:** a move put into the past that would change the cards any later draw or random pick took is refused (`Past.redeals`), for Ai and for Insert here alike; its chance comes from that place's own roll count (`Past.stamp`), so it comes out the same however often it is put in. | `DuelLeadsTest.aMoveInThePastCannotReDealTheDrawsSince` |

Also closed in passing: `duel_peek`'s reason is written into the log through `Secrets.redact`, so it never names Ai's own
hidden cards (one of the lead "Ai's text … in `duel_peek`'s reason … reaches the record unredacted").

**Already held before this stage** (checked against the code, not changed): a guest reveals only its own cards and never
takes, flips or targets the host's hidden ones (`DuelRedTeamTest`, 1.0.85); Ai reads only through its knowledge setting
and a perspective other than its own only with full knowledge (`AiDuel.perspective`, 1.0.85); Ai's words in the log
never name its hidden cards (`Secrets`, `DuelRedTeamTest`); undo cannot fish (`DuelRandom.forRoll`, 1.0.86).

**Left for stage 2** (closed there, §4): coordinates in the brief and the parser for the opponent's hand; Ai's text after
`;` and in combo steps reaching the record unredacted; cues dropping the person's moves on Ai's cards. Ai World's duel
tables are stage 3's (§6).

## 4. The table in full for Ai (stage 2, done)

The research's biggest cheap gain (`docs/AI-INTELLIGENCE.md` §2: an LLM player on an engine lost most to what the
interface left out — the phase, the full GY, the Extra Deck — never to the rules). Ai at the table sees and does what a
player would, no less and no more (stage 1's `DuelReach` and its knowledge setting).

- **The brief in full** (`DuelBrief.describe`, read by `duel_state` and every cue): the turn and phase, **who has
  priority** (`DuelBrief.priority`: a response window's responder, the seat that may chain to the newest link, else the
  turn player), every card the reader sees with **its printed facts** (`DuelBrief.facts`: Level, Rank or Link rating,
  Scale, Attribute, Type and kind, ATK and DEF — the table's own number where it holds one, a token's, with the printed
  one beside it where they differ; a Spell's or Trap's kind), counters and materials; both GYs (top first) and banished
  piles whole; the Extra Deck (its owner's every card, theirs face-up and counted); hand counts, the chain (negated links
  marked), LP; and **this turn's moves** (`DuelBrief.turnLines`, from just after the last End Turn, through
  `DuelHost.lines` as the reader saw them — the words the network's guest is sent). `DuelCardInfo` carries the new facts
  (`level`, `xyz`, `linkRating`, `attribute`, `race`, `typeLine`, `scale`), read off the card as printed. A hidden card is
  never named and never given facts (a hidden card's facts would name it): `DuelTableTest` reads the brief through each
  seat in turn.
- **One convention for coordinates.** Every card is written with its coordinate from the acting seat's side (`h1`, `m3`,
  `ogy1`, `oh2`), and the other seat's hand is listed in the order that seat is shown it (`DuelNotation.handOrder`), so
  each coordinate the brief prints parses back to the card beside it — under full knowledge too. The lead: `ComboRunner.plan`
  parsed Ai's lines with no secret (0) while the brief and `DuelView` dealt the hand under the duel's seed, so `oh2` was
  another card than the one shown there; `plan` now takes the duel's secret, from `duel_act`, its `at` and a combo Ai runs.
- **A menu of legal moves** (`duel_moves`, read-only; `DuelMoves`): for every card the seat may touch — its hand, field,
  GY, banished cards and Extra Deck, and the other seat's cards on the field and in its piles a player reaches across the
  table for — the verbs `DuelVerbs.offered` gives where it stands, each written as the exact op `duel_act` takes
  (`DuelLetters`' letters and `DuelNotation`'s coordinates: `s h2`, `a s1`, `g om1`, `a m3 om1`, `ss ex1`), with the
  phases ahead, the end, an answer to an ask, the opening roll, the chain (`resolve`, `negate 2`) and each attack. **A
  move is kept only when it plans** (`ComboRunner.plan`, as `duel_act` checks it) **and `DuelReach` lets the seat make
  it**; moves that do the same thing are offered once; a hidden card is written by its place, never named; capped at 160
  with the rest counted; `card=` gives one card's every verb with each free zone and host spelled out. Physics, never
  card text. Always Ai's own seat's, whatever its knowledge. `AiTools.DUEL` and `AiTable.TABLE_TOOLS` (refused at a
  networked table) carry it.
- **Its guide at the table** (`DuelGuide`, `duelGuide` in `neue/duel/DuelAi.kt`): the guide to the deck Ai's seat plays
  (`guides/<deck>.md` as `guideForPrompt` reads it, each number wearing its proof's mark) and the deck's combos, put in
  front of the duel conversation once (`AiState.sendDuel(guide = …)`, keyed in `AiSession.guideShown`), within a budget —
  entries in order, each cut to 600 characters, 4,000 for the guide and 1,500 for the combos, the rest counted — and again
  after a summary (`standingContext`). The other seat's deck's guide only when Ai reads the table with full knowledge;
  none at a networked table.

**The leads, closed** (held by `DuelTableTest`, each shown failing as it was first):

| Lead | Status | Held by |
|---|---|---|
| Coordinates in the brief and in the parser disagree for the opponent's hand. | **Was open:** the parser's order used secret 0, the brief's the seed. **Fixed** as above; the brief prints every coordinate. | `everyCoordinateTheBriefPrintsParsesBackToItsCard` |
| Ai's text after `;`, and in combo steps, reaches the record unredacted. | **Was open:** `duel_act` guarded an op only when it began with `say`/`note`/`lock`. **Fixed:** every planned step's words — chat, notes, locks, a chain link's note — go through `Secrets` on the table that step is made on (`ComboRunner.redacted`), for `duel_act`, its `at` and a combo Ai runs; the moves are unchanged. | `aisWordsAfterASemicolonAndInAComboAreKeptFromTheRecord` |
| Cues drop the person's moves on Ai's cards. | **Was open:** the page acts as a card's controller, so the person destroying Ai's monster was logged as Ai's seat, and the cue kept only the other seat's lines. **Fixed:** the cue keeps every entry but Ai's own, by provenance (`DuelBrief.since`: made by Ai, or the table's draw for Ai's seat; an entry with no provenance by its seat, as before). | `aPersonsMoveOnAisCardsReachesItsNextCue` |

**Stored data:** none new. `AiSession.guideShown` (a string since 1.0.48) holds the duel's guide key, the deck id (and
`+full:<id>` with full knowledge).

## 5. Puzzles: an evaluation set (stage 3, done)

**Duel puzzles** (`EvalSets.PUZZLES`, the sixth set in Test scores, named Trust then; `core/ai/eval/Puzzles.kt`, `PuzzleTable.kt`): 17 positions
with a known goal, played by Ai on a table of their own and **graded on the table** — never on what Ai says.

- **A puzzle** (`Puzzle`): a position (`PuzzleSetup`: both seats' life points, your hand, each side's monsters by zone and
  position), laid out by the table's own moves on a dealt `DuelGame` (`Puzzles.start`: turn 3, your Main Phase 1, behind
  undo's reach — so its record folds from a deal like any duel); the goal as a check on the table (`PuzzleGoal`: their LP
  at most n, your LP at least n, their field clear of monsters, you control a named monster, or all of these); a move
  budget; a recorded `solution`, a tempting `wrong` line, and `why` it is a puzzle.
- **Played** (`neue/ai/AiEval.kt`, `playPuzzle`): Ai is given the puzzle's prompt, the referee's rules (`PuzzleTable.RULES`)
  and three tools only — `duel_state`, `duel_moves`, `duel_act`, the very specs it plays kai with — answered by the
  puzzle's `PuzzleTable`, never by the duel in play. Each op is planned as `duel_act` plans a line at kai's table
  (`ComboRunner.plan`, the duel's secret), held to `DuelReach`, then admitted by the referee. Ai's moves carry its
  provenance. When Ai stops (DONE, or the step limit), `PuzzleTable.grade` checks the goal on the table and replays the
  admitted lines on a fresh table under the referee to the same table, so a grade rests on legal moves alone.
- **Why a goal cannot be cheated.** The engine is manual: life points can be typed and any card moved anywhere. So a
  puzzle is played under a **referee** (`PuzzleReferee`) that admits only a turn's legal moves and refuses the rest with
  the rule: the phases forward only; one Normal Summon or Set from the hand into a free zone, Level 5–6 after one Tribute
  and 7+ after two (a Tribute is your monster sent to the GY just before, only while the hand holds a monster that needs
  it, and nothing else may come between); a position change once a turn, never for a monster summoned this turn or that
  attacked, a face-down monster only by Flip Summon to face-up Attack; an attack once a monster, from face-up Attack
  Position, in the Battle Phase, at a monster of theirs or directly only when they control none — **battle damage and
  destruction worked out by the referee** from the printed numbers (`DuelBattle`), never typed; a Spell from the hand in a
  Main Phase whose text the puzzle writes as table moves (`PuzzleEffect`: Raigeki, Dark Hole, Fissure, Ookazi, Tremendous
  Fire), resolved at once. Refused: `lp`, sending, banishing, bouncing or moving a card by hand, a Special Summon, a token,
  a draw, the end of the turn, a Spell placed rather than activated. Every monster is a **Normal Monster** (no text to
  automate), the opponent has no hand and nothing set and never responds, and a tie Fissure would leave to the player is
  never guessed. So life points and cards change only as a real turn changes them.
- **The limit, honestly:** the referee knows a turn's rules and these few Spells, nothing more: no effect monsters, no
  Extra Deck summons, no responses, one turn. It is a test of planning a turn by the numbers on the table — battle order,
  Tributes, which Spell when — not of card knowledge. Effects as code (Phase D) is what widens it.

The puzzles (17; each proved solvable by its line and not by its wrong one in `PuzzleTest`):

| Id | Goal | What the obvious line misses |
|---|---|---|
| p01 | Their LP 0 (3800) | The warm-up: two direct attacks. |
| p02 | Their LP 0 (2000) | No direct attack while they control a monster: battle first, then direct. |
| p03 | Their LP 0 (3700) | The Normal Summon is the missing 1800. |
| p04 | Their LP 0 (2500) | Tribute Celtic Guardian for Summoned Skull: 2500 is exact. |
| p05 | Their LP 0 (4200) | Two 2000-DEF walls: Raigeki, not attacks into them. |
| p06 | Their LP 0 (2700) | The attack and Ookazi together; the Spell in a Main Phase. |
| p07 | Their LP 0 from 8000 | Exactly 8000: Raigeki for the wall, Ookazi, three direct attacks. |
| p08 | Their LP 0 (1900) | Dark Hole first, then summon the attacker. |
| p09 | Their LP 0 (2500) | Fissure reads ATK, not DEF: it takes the wall, battle takes the other. |
| p10 | Their LP 0 (2300) | A Defense Position monster set earlier changes position, then attacks. |
| p11 | Their LP 0 (3700) | A Flip Summon is not the Normal Summon: both attack. |
| p12 | Their LP 0 and yours ≥ 1 | At 500 LP Tremendous Fire is a draw; Ookazi wins. |
| p13 | Their field clear | Only Alexandrite beats Luster Dragon: the strongest takes the strongest. |
| p14 | Control Blue-Eyes, their field clear | Two Tributes for Blue-Eyes, not Dark Hole. |
| p15 | Their LP 0 (1900) | A 2500-into-2500 trade empties their field for the 1900. |
| p16 | Their LP 0 (4400) | Which two to Tribute: keep the 1400 for 4400 exactly. |
| p17 | Their LP 0 (3800) | Raigeki, not Dark Hole, keeps your attackers. |

**The baseline score** (`PuzzleBaselines`, held by `PuzzleTest.theBaselineBoundsTheSet`, shown in Test scores under the set):
doing nothing **0 of 17**; a battle-only greedy player (into the Battle Phase, each monster strongest first attacks the
weakest monster it destroys without loss, else directly at an empty field) **2 of 17** (p01, p02); the recorded solutions
**17 of 17**. A model's score is read between those bounds. Test scores estimates 30,000 tokens a puzzle.

## 6. Ai vs Ai: two sessions, one a seat (stage 3, reworked)

kai: "Instead of having Ai play itself, have two different Ai sessions play each other." Stage 3 first shipped (unreleased)
"self-play" as one script — or one Ai — moving both seats with full knowledge. That is gone: Ai's games against Ai are now
**two independent sessions, each a player in its own right**, on the Duel page, refereed, watched by the person.

**Two sessions, two seats.** Each seat is a headless agent run of its own (`core/duel/match/AiMatch.kt`, `AgentPlayer`): the
real `AgentLoop` with its own history, its own backend — the same connection or another, chosen per seat from the person's
connections (`AiState.newBackend`, never the panel's cached one, so two seats on two connections never close each other's)
— and its own `AiSession` (mode `ai-vs-ai`, `AiSession.MODE_MATCH`), kept with Ai's conversations so kai can read each
side afterwards (the bar's "Seat N's side"; a message typed into one starts a new conversation). What a session is given:

- **Its instructions** (`MatchPrompt.system`): who it is, the table's rules, the rules primer, and **its own deck's** guide
  and combos (`DuelGuide.block` of its seat's deck) — never the other's. The other deck's name is not told either.
- **Each cue** (`MatchPrompt.cue`): what happened since its last cue as its seat saw it (`DuelBrief.since` — every entry
  but its own; `aisOwn` now counts an Ai move as the reader's only when its `aiSeat` is the reader's, so the other session's
  moves are the opponent's), the brief through its own `DuelView` (knowledge self, no peeks: `MatchTable.brief`), and the ask.
- **Four tools, scoped to its seat** (`MatchTable.runner`): `duel_state`, `duel_moves`, `duel_act` — the very specs Ai plays
  kai with — and `card_info`, a card's printed text by name or by a coordinate the seat can see (the lookup never finds a
  hidden card). `duel_act` plans each op as at kai's table (`ComboRunner.plan` with the duel's secret), puts its words
  through `Secrets` on the table they are made on (`ComboRunner.redacted`), holds it to `DuelReach` exactly as Ai against a
  person and the network's guest, refuses a phase or End Turn off the seat's turn, a die (the table throws), a concession
  for the other seat, and a move while the seat's own response window waits on the other — and commits through
  `DuelHost.act`, so a move opens the other seat's response window as at a networked table. Every move carries
  `Provenance(ai, aiSeat = that seat, aiKnows = self)` and its view's fingerprint.
- **Nothing else crosses.** A session's reasoning and replies stay in its own conversation; only the table — its moves, as
  the other seat sees them, and its `say` lines, redacted — reaches the other.

**The referee** (`MatchReferee`, pure; `AiMatch`, the loop): the opening roll is played — the table throws each seat's dice
(`OpeningRoll`, stamped), the winner's session is cued to choose (`go first` / `go second`), and makes no choice → it goes
first, said. Then whose move it is: a response window's responder (`DuelState.window`; the windows a match opens are the
dialog's: on activations, or summons too), the turn player for an ask, the seat that may chain to the other's link, the
seat whose link resolves once both have passed (newest first; a negated link the table resolves itself), else the turn
player — with the turn's draw made by the table (`TurnStart`) and a Deck that cannot give it losing the duel (decked out).
**Bounded per cue**: at most `cueMoves` (12) table moves, `cueSteps` (10) rounds of tools, `cueMillis` (4 minutes). **A
seat that stalls**: a response or chain cue with no move is a pass (the table answers the window for it, said); the turn
player that makes no move twice, or stops five times without `end`, has its turn ended by the table, said; a seat that
does not resolve its link has it resolved; an ask unanswered is declined. **A seat that errors** (a failed call, a
timeout) three cues in a row forfeits (`Concede`, said). **The end**: life points at 0 or a concession; past the turn cap,
the token budget or the cue cap, a draw by limit, said in the log and in the record (`DuelResult.how = "limit"`, `said`).
**Stop** (the person's) cancels the referee's coroutine: the run in flight ends where it stands (its open tool calls
answered "Stopped by the person", its conversation kept), the log says so, no result is kept.

**Watching** (`neue/duel/DuelMatches.kt`, a part of `Duels`): the match runs off the main thread on a table of its own,
never the duel in play, which waits untouched behind it. While it is on the table (`Duels.spectating`) the page shows it
as a spectator sees it — both hands face-up, the person's view, never handed to either session — every move as it is made
at a pace (`MatchRules.paceMs`), and refuses the person's moves, undo, redo and the seat swap ("Ai vs Ai is on the table").
The bar over the table (`MatchBar`) says who plays whom, the turn and who is moving, with **Stop**; once over, how it ended,
each seat's conversation and **Back to your duel**. The finished (or stopped) match is kept as a replay.

**Starting one** (`AiVsAiDialog`, from the Table menu's "Ai vs Ai…" or the New duel dialog): each seat's deck and
connection (the active connection for both by default), a seed (optional), the turn cap (6/12/20/40), the response windows
and the token budget (250k–2M, both seats). **What it may spend is said before it starts** (`AiMatch.cost`: about four cues
a turn to the cap at about 18k tokens a cue, never past the budget, where it stops as a draw by limit). It refuses to start
with Ai off, at a networked table, with an empty deck, or with **a plan's command-line app** for a seat: Claude Code and
Codex run their own loop and reach the app's tools through its MCP server, which answers for the duel in play — they cannot
be held to one seat of a match's table (`DuelMatches.unusable`); the dialog says so. API connections only.

**Records** (`DuelResults.aiVsAi`): a finished match is a `DuelResult` of kind `ai-vs-ai`, each seat carrying its
connection's label and model (`ResultSeat.connection`, `model`, `engine`), its seed, and `said` when the table ended it.
Counted apart: `aiAgainst` skips every record with a kind; `aiVsAi(results)`/`matchWords` read "Ai vs Ai: claude-opus-5-5
beat gpt-x 3 of 5 (1 drawn; going first won 4)." — in `duel_records`' summary after the games against people, in its list
("Ai vs Ai: … v …, seed …"), and in Replays.

**Ai World's script tables are a sandbox now.** `ygo.duel.start/fork` keep their seed, `first` and fork (`DuelFork`), but a
script moves both seats as itself (no provenance: no player made those moves), and how a table ended (`t.result()`, the
`ended` of `t.do`) is the script's to read — kind `scripted`, never a `DuelResult`, never kept, never in a summary.
`WorldApi.finished`, `Worlds.keepDuel`, `DuelResults.selfPlay`, `againstItself` and `selfWords` are deleted. Scripts no
longer play duels for Ai: a script tests lines.

Held by `AiMatchTest` (core: the opening roll and the winner's choice, the turn cap → a draw by limit, a stalled seat's
turn ended and no choice going first, a response window cueing the other seat and both passing resolving the chain, a
failing session forfeiting, and each seat told only what its own seat sees), `AiVsAiTest` (neue: two scripted backends
play a match to the end through `Duels`, every request each was sent read — no card of the other's it could not see, no
guide of the other's, only the four tools — the record `ai-vs-ai` with both connections, a replay, both conversations
kept; and Stop ending both runs cleanly), `ScriptTableTest`, `JsRuntimeTest.aScriptPlaysASandboxTableToTheEnd`,
`WorldsTest` and `OldDataTest`.

## 7. The red team on Phase C (stage 3)

| Finding | Measured / verified | Status |
|---|---|---|
| **The brief's size in every cue.** | A very full table (both fields full with materials, a four-link chain, GYs of 25, 10 banished, full Extra Decks, 8-card hands, a turn of 40 moves): **12,501 characters**, 68 lines, about 3,100 tokens a cue. A quarter was the same Level/Attribute/Type/ATK printed again for every copy in the GYs. | **Fixed:** a card's printed facts at its first mention only (`DuelBrief.describe`, said in the brief's head): **8,934 characters** on the same table, nothing dropped. Bounded at 10,000 by `DuelScaleTest`. |
| **`duel_moves` on a very full table** plans a full line per candidate move. | The same table: **424 moves in 111 groups in about 0.55–0.66 s** on the desktop JVM (warm), 4,000 characters shown at the cap of 160. | Acceptable for a tool Ai calls, not a cue; bounded at 3 s by `DuelScaleTest`. Left: a cheaper check than a whole plan per move, if phones show it (a lead). |
| **A hidden card through the menu, `card=`, or a name.** | The whole menu, `card=` on their set card and their hand by place, and their hidden cards asked for by name. | **Held:** the menu writes a hidden card by its place ("os1 a face-down card"), offers no verbs for their hand, and a name never finds a card the seat cannot see, so a lookup cannot probe (`PhaseCRedTeamTest`). The guide stays the seat's deck's (the other's only with full knowledge). |
| **A hidden card through the brief's facts.** | Facts given once could leak if a hidden card took a fact slot. | **Held:** a hidden card has neither name nor facts (`DuelTableTest`, `PhaseCRedTeamTest`). |
| **Provenance: a number key after a placement re-made the log's last group under that group's provenance.** | With Ai's move logged since the person placed a card, the person's number key undid Ai's group and wrote it again as Ai's. | **Fixed:** the key moves only its own placement, never a group of Ai's (`Duels.replace`; `DuelNumberKeyTest`). |
| Provenance on the other commit paths. | Checked: `Duels.act` (person, Ai, the table's draw), a held phase change released (the person's, as made), `Replays.insert` and Insert here, `DuelHost.act` (host and guest), Ai's peeks, combos and lines (Ai). | Held as they were. |
| A fork leaking the person's cards. | The fork reads one seat's `DuelView` and that seat's decklist only. | **Held** by construction and `ScriptTableTest.aForkHoldsWhatAisSeatSeesAndNothingElse` (no passcode of a card the seat could not see). |
| One Ai session reading the other's seat in Ai vs Ai. | Every request two scripted backends were sent in a whole match, read word by word. | **Held:** each seat's cue, brief and tool results come through its own view, its guide is its own, its words to the other go through `Secrets` (`AiVsAiTest`, `AiMatchTest.eachSeatIsToldOnlyWhatItsOwnSeatSees`). |

## 8. Order of work

1. **Stage 1 (this note's §1–§3):** provenance, results and the summary, Prep's first or second, the four leads.
2. **Stage 2 (done, §4):** the table in full for Ai, the legal-move menu, its guide at the table; the rest of the leads.
3. **Stage 3 (done, §5–§7):** the puzzle set and its baseline; Ai vs Ai — two sessions, one a seat — in place of self-play
   (Ai World's tables a sandbox for scripts); the red team on the phase.

**Phase C is done:** "Ai won N of M against kai, with these settings" reads from the records, and Ai vs Ai beside it ("Ai
vs Ai: claude-opus-5-5 beat gpt-x 3 of 5"); every duel lead is closed and held by a test; the puzzle set has its baseline
(0 / 2 / 17 of 17).

**What Phase C leaves for later:**
- Puzzles with effect monsters, the Extra Deck, the opponent's responses and more than one turn: they need effects as code
  (Phase D). A puzzle is still a turn by the numbers.
- A model's score on the puzzle set: run in Test scores per connection (cost); no live model runs on push.
- A cheaper legality check for `duel_moves` than a whole plan per move, if a phone shows the half second.
- An Elo per Ai version from Ai vs Ai matches and puzzles (Phase E); Ai vs Ai for a plan's command-line app (it would need
  an MCP server answering one seat of a match's table); a cheaper cue (the brief as a diff) for long matches.

**Stored-data changes in stage 1** (for the release notes): every new duel entry may carry `by` (provenance); a new
folder `<data>/duel/records/` (one JSON file per finished duel, synced and backed up). Old duels, replays and settings
read unchanged; no preference and no schema change.

**Stored-data changes in stage 3:** a duel record may carry `kind` (`"ai-vs-ai"`), `seed` and `said`, and each of its seats
`connection` and `model` — each left out when empty; `how` may be `"limit"` (a draw by limit). A record without them is a
duel at the table, as before, and an older build reading an Ai vs Ai record skips the keys and still never counts it against
a person (both seats are Ai's). A record of kind `"self-play"` (with `forkOf`), written only by the unreleased first cut of
this stage, is still read — and counted nowhere. A new conversation mode, `ai-vs-ai`, in `<data>/ai/sessions/`. A finished
or stopped match is a replay in `<data>/duel/replays/`. `OldDataTest` holds the shapes. No preference, no schema change;
Test scores' runs of the puzzle set are ordinary `ai/evals/<connection>.json` runs.

**Needs:** F1 (the puzzle set), F4 (Ai's permissions at the table), Phase B (correct cards).
