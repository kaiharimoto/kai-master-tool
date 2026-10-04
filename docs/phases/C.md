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

**Still open, for stage 2:** coordinates in the brief and the parser for the opponent's hand; Ai's text after `;` and in
combo steps reaching the record unredacted; cues dropping the person's moves on Ai's cards; Ai World's duel tables.

## 4. The table in full for Ai (stage 2)

The research's biggest cheap gain (`docs/AI-INTELLIGENCE.md` §2):
- **The brief in full:** the phase, every public zone with ATK/DEF, Level, Attribute and Type, the Extra Deck, and the
  history since Ai last read — today the brief gives names only.
- **A menu of legal moves** from `DuelVerbs` (each card's verbs where it stands, through `DuelReach`), so Ai chooses
  instead of composing command lines.
- **Its guide at the table:** the deck's guide (`MemoryKind.GUIDE`) read in the duel's mode, its numbers carrying their
  proof.

## 5. Puzzles: an evaluation set (stage 2–3)

Positions with a known best line, checked by `DuelRules` — "end the turn with Baronne and a negate", "survive this
board" — kept as an F1 set (`core/ai/eval`): a position (a `DuelRecord` up to a point), the goal as a check on the table,
and the grader is the fold. Run against a connection like Trust's sets, scored per model; **the baseline score** is part
of "done".

## 6. Self-play tables in Ai World (stage 3)

Ai World's duel tables (`WorldApi`) take a seed, who goes first, and a fork of the live position (the duel in play as it
stands, through `DuelView` for the seat Ai would hold). Every self-play game is a record like any other, its provenance
`ai` on both seats, kept apart from games against people.

## 7. Order of work

1. **Stage 1 (this note's §1–§3):** provenance, results and the summary, Prep's first or second, the four leads.
2. **Stage 2:** the table in full for Ai, the legal-move menu, its guide at the table; the rest of the leads.
3. **Stage 3:** puzzles and their baseline; self-play tables; a red team on the whole phase.

**Stored-data changes in stage 1** (for the release notes): every new duel entry may carry `by` (provenance); a new
folder `<data>/duel/records/` (one JSON file per finished duel, synced and backed up). Old duels, replays and settings
read unchanged; no preference and no schema change.

**Needs:** F1 (the puzzle set), F4 (Ai's permissions at the table), Phase B (correct cards).
