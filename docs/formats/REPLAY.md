# Duel records — the duel in play and its replays

A duel on the Duel page is its log: how it began, and everything that happened since, in order (1.0.74, `NEUE.md` §4p).
One JSON document holds both, a `DuelRecord`, and the same document is the duel in play, a replay in the library, a
"what if" played on from one, and the position inside a puzzle. A reader that has the record has the duel: the table at
any moment is the deal with the entries up to that moment applied, and everything left to chance is written into the
entries themselves, so every reader folds the same table. The code is `core/duel/DuelLog.kt` (`DuelRecord`, `DuelHeader`,
`DuelEntry`, `DuelCodec`) and `core/duel/DuelAction.kt`.

## Where it is kept

| File | What it is | Sync | Backup |
|---|---|---|---|
| `<data>/duel/current.json` | The duel in play (`Duels.CURRENT`), written 300 ms after each change and at once when the window closes. | Never: this device's own. | Yes |
| `<data>/duel/replays/<id>.json` | A replay in the library. The id is `r` and the time it was kept in ms; the file name less `.json` is the id. | Yes, newer wins | Yes |
| `<data>/duel/puzzles/<id>.json` | A puzzle (`MapPuzzle`): the record is its `record` key. | Yes | Yes |

The Duel page writes through `FileDuelStore`: to `<name>.tmp`, then renamed over the file. Results (`<data>/duel/records/`), combos and
house rulings in the same folder are other documents, not this one.

## The record

The app writes it on one line with no spaces, leaving out every key whose value is its default (`DuelCodec.json`:
`encodeDefaults = false`, `classDiscriminator = "t"`). It is spread out here.

```json
{
  "header": {
    "id": "d8", "seed": 5, "created": 1760000000000,
    "seats": [
      {"name": "Kai", "main": [1, 2, 3, 4, 5, 6], "deckId": "k", "deckName": "Labrynth"},
      {"name": "Ai", "main": [7, 8, 9, 10, 11, 12]}
    ]
  },
  "entries": [
    {"i": 0, "at": 1760000000000, "action": {"t": "shuffle", "seat": 0, "salt": 7385128309316491911}},
    {"i": 1, "at": 1760000000000, "action": {"t": "draw", "seat": 0, "n": 5}},
    {"i": 2, "at": 1760000000000, "action": {"t": "shuffle", "seat": 1, "salt": -2207013125874104541}},
    {"i": 3, "at": 1760000000000, "action": {"t": "draw", "seat": 1, "n": 5}},
    {"i": 4, "at": 1760000004200, "seat": 0, "group": 1, "by": {"eyes": "seat"},
     "action": {"t": "move", "uid": 2, "to": {"t": "zone", "seat": 0, "kind": "MONSTER", "index": 2}, "pos": "FACE_UP_ATK", "how": "normal"}},
    {"i": 5, "at": 1760000009800, "seat": 1, "group": 2,
     "by": {"by": "ai", "aiSeat": 1, "aiKnows": "auto", "eyes": "seat", "view": "0123456789abcdef", "peeks": 1},
     "action": {"t": "lp", "seat": 0, "delta": -100}},
    {"i": 6, "seat": 0, "group": 3, "action": {"t": "note", "text": "Ash here?", "seat": 0}}
  ],
  "cursor": 6
}
```

The first four entries are the deal. Entry 6 is past the cursor: undone, kept for redo.

### `DuelRecord`

| Key | Default | Meaning |
|---|---|---|
| `header` | an empty header | How the duel began. |
| `entries` | `[]` | Everything that happened, oldest first. |
| `cursor` | the number of entries | How many entries are in play. Those after it were undone and are kept for redo. |
| `name` | `""` | A replay's name; blank reads as "Untitled duel". The duel in play has none. |
| `version` | `1` | See *Versioning*. |
| `parent`, `parentAt` | none | A "what if": the replay it was played on from, and after how many of that replay's entries. |
| `saved` | `0` | When it was kept as a replay, in ms. |

### `header` (`DuelHeader`)

| Key | Default | Meaning |
|---|---|---|
| `id` | `""` | The duel's id. |
| `seed` | `0` | The duel's secret: the root of every shuffle, coin and die it rolled. |
| `seats` | two empty seats | Seat 0 and seat 1, each `{name, main, extra, deckId, deckName}`: the decks as passcodes in their written order, and the library deck each came from when it did. |
| `first` | `0` | The seat that goes first when there is no opening roll. |
| `solo` | `false` | A one-player table: seat 1 is not dealt. |
| `startLp` | `8000` | Each seat's life points at the deal. |
| `handSize` | `5` | Cards each opening hand draws. |
| `created` | `0` | When it was dealt, in ms. |
| `openingRoll` | `false` | The duel opens with the dice (1.0.87): each seat throws two, the higher sum chooses who goes first. |
| `start` | none | A fork's starting table (Phase E, E.2): the whole engine `DuelState`, from which the entries fold instead of from `seats`. Absent for every dealt duel. Its shape is the engine's own and is not specified here. |

### `entries[]` (`DuelEntry`)

| Key | Default | Meaning |
|---|---|---|
| `i` | required | Its place in the log. The app renumbers from the position in the array when it opens a duel; the written number is not trusted. |
| `at` | `0` | When, in ms. |
| `seat` | none | Who acted, 0 or 1. None is the table itself. The deal is the run of entries at the start with no seat; undo never goes behind it. |
| `group` | `0` | Entries sharing a group were one gesture (an Xyz Summon and its materials, a combo's steps). Undo and a replay's step take a group at a time. Groups only rise along the log. |
| `action` | required | What happened, below. |
| `by` | none | Who made it (Phase C, `Provenance`), below. |
| `fx` | none | Which written effect made it (Phase D, `FxTag`), below. |

### `action` (`DuelAction`)

An object whose `t` names it. Fields with their defaults in brackets; uids are the table's numbers for cards (below).

| `t` | Fields |
|---|---|
| `move` | `uid`, `to` (a place), `pos` [none: the card's face kept where that makes sense], `how` [none; "normal", "special", "set", "activate", "tribute", "search", "send", "banish", "return", "attach" — words for the log, never read by the table], `over` [false: on top of the monster in that zone, as an Xyz Summon] |
| `draw` | `seat`, `n` [1] |
| `shuffle` | `seat`, `pile` [`DECK`], `salt` [0] — stamped |
| `pos` | `uid`, `pos` |
| `counter` | `uid`, `delta`, `kind` [""] |
| `token` | `seat`, `to` (a zone), `pos` [`FACE_UP_DEF`], `code` [0], `name` ["Token"], `atk`, `def`, `uid` — stamped |
| `lp` | `seat`, `delta` [0], `set` [none] |
| `phase` | `phase` |
| `end` | — (the turn passes) |
| `propose` | `seat`, `phase` [none], `end` [false] |
| `decline` | `seat` |
| `lock` | `seat`, `text`, `until` ["turn"; or "chain", "duel"], `id` — stamped |
| `unlock` | `id` |
| `chain` | `seat`, `uid` [none], `note` [""], `targets` [[]] |
| `resolve`, `clear` | — |
| `attack` | `seat`, `attacker`, `target` [none: a direct attack] |
| `negate` | `seat`, `link` (1-based) |
| `keep` | `uid` |
| `target` | `seat`, `from` [none], `to` [[]], `on` [true] |
| `reveal` | `seat`, `uids`, `to` [none: both seats] |
| `coin` | `seat`, `heads` [true], `toss` — stamped |
| `dice` | `seat`, `value` [1], `toss` — stamped |
| `stow` | `seat`, `coin` [none: die and coin] |
| `pick` | `seat`, `to` (a pile), `from` (a pile) [none], `among` [[]], `n` [1], `pos`, `how`, `salt` — stamped |
| `opening-roll` | `seat`, `values` [[]], `toss` — stamped |
| `go-first` | `seat`, `first` [true] |
| `chat` | `seat`, `text` |
| `ping` | `seat`, `kind` ["look"; or "ok", "no", "wait"], `uid`, `place` |
| `thinking` | `seat`, `on` [true] |
| `answer` | `seat`, `respond` |
| `note` | `text`, `seat` [none] |
| `concede` | `seat` |

**Places** are objects with their own `t`: `zone` `{seat, kind, index [0]}`, `pile` `{seat, kind, at [none]}`, `under`
`{host, index [-1]}`, and `void` with no fields (out of the duel, where a token goes). A pile's `at` none is its natural place (the Deck's top, the hand's right end), 0 the
top, -1 the bottom. Where a field can only be one kind of place — `token.to` (a zone), `pick.to` and `pick.from` (piles) —
it is written without `t`, and a `t` there is ignored.

**Words**: positions `FACE_UP_ATK`, `FACE_UP_DEF`, `FACE_DOWN_DEF`, `FACE_DOWN_ATK`; phases `DRAW`, `STANDBY`, `MAIN1`,
`BATTLE`, `MAIN2`, `END`; zones `MONSTER`, `SPELL`, `FIELD`, `EMZ`; piles `HAND`, `DECK`, `EXTRA`, `GY`, `BANISHED`.

**Throws**: a coin's or die's `toss` is `{start: {p, q, v, w}, reach}` (`dice/Toss`), an opening roll's
`{dice: [start, start]}` (`dice/DiceThrow`): positions and velocities `{x, y, z}`, an orientation `{w, x, y, z}`, every
number rounded so the physics replays bit for bit. A throw decides where the die lands; the stamped value decides what it
reads.

### `by` (`Provenance`)

Stamped when the entry is committed. Every key has a default, so `{}` is a person's move with nothing else to say, and an
entry with no `by` at all — the deal, and everything written before 1.1.2 — is counted as no one's.

| Key | Default | Meaning |
|---|---|---|
| `by` | `"person"` | `person` (at this device), `ai`, `guest` (the network's), `table` (a turn's own draw). |
| `aiSeat`, `aiKnows` | none | The seat Ai held and its knowledge setting then (`self`, `auto`, `full`, `opponent`). None when no Ai sat at the table. |
| `eyes` | none | The person's eyes on a hot-seat then: `all` or `seat`. |
| `view` | none | Ai's own move: the first 16 hex digits of the SHA-256 of the view it acted on. A fingerprint, never the view. |
| `peeks` | none | Ai's own move: the peeks it had taken before it. |
| `peek` | false | This entry is one of Ai's peeks. |
| `net` | false | Made at a networked table. |
| `engine` | none | Ai's move made by the app's own search, by version (`search-v0`, …). |

### `fx` (`FxTag`)

Only on entries the effects engine made (Shortcut, the goldfish's lines): `uid` (the card, 0 for the turn's own),
`effect` (`e1`…, `proc` or `rule`), `part` (`cost`, `activate`, `resolve`, `proc`, `rule`, `skip`), `link`, `script` (12
hex digits of the compiled script's hash), `verified`, `memo` (the engine's own memory of the step, opaque to other tools).
It changes nothing on the table; with it a tagged log folds to the engine's exact state.

## Chance is written down

Nothing is rolled when a log is read. An action that leaves something to chance (`DuelRandom.rolls`: `shuffle`, `coin`,
`dice`, `pick`, `opening-roll`) is stamped when it is committed: the salt, the face or the values are filled in, and a
throw is made for a coin or die no hand threw. `DuelGame.act` stamps the nth such action of the duel from
`DuelRandom.forRoll(seed, n)`, so a shuffle undone and made again comes out the same and undo cannot fish for a draw;
1.0.74–1.0.85 keyed them by entry, and what those builds stamped keeps its values. An action put into a replay or into the
past of the duel in play is stamped where it goes in.

**A reader takes the stamped values as they are and never rolls again.** To fold the table it needs only how a salt is
spent: `DuelRandom.riffle(list, salt)` is a Fisher–Yates from the last item down, `j = nextInt(i + 1)` on Kotlin's
`kotlin.random.Random(salt)`, spelled out so every platform and Kotlin version deals the same. A shuffle riffles its pile;
a pick riffles its pool (`among`, else the `from` pile) and takes the first `n`.

Numbers are stamped too. At the deal seat s's Main Deck cards take uids from `1 + s × 1000` in their written order, then
its Extra Deck's (`DuelState.SEAT_UIDS`). A token's uid (from 100000, `DuelState.TOKEN_UIDS`) and a lock's id are written
into its action on commit (1.0.86, `DuelIds`); a log from before has none, and the fold numbers them as it always did — a
token the table's next uid, a lock the highest held plus one.

## Replays, notes and what-ifs

A replay is a record kept in the library and walked by group, phase or turn (`core/duel/replay/Replays`). Editing it is
editing the list:

- **A step taken out** (`Replays.deleteGroup`) removes a whole group; tokens and locks after it keep their numbers.
- **A move put in** (`Replays.insert`) is a new group where the replay stands; the groups after it are renumbered, and a
  gesture it lands inside is split in two.
- **A note** (`Replays.annotate`) is a `note` action put in the same way: it changes nothing on the table.
- An entry an edit leaves impossible stays in the file. The fold skips it and the timeline strikes it through.
- **A what-if** (`Replays.branch`) is the record's first entries as a live duel with the same header, so its chance comes
  from the same seed. It is written as the duel in play with `parent` and `parentAt`, and kept as a replay with them.

## What a reader must tolerate

- `DuelCodec.decode` is null only when the text is not a duel at all: not JSON, or an entry without `i` or `action`.
- Unknown keys anywhere are ignored, and a null or unknown word where a key has a default reads as the default
  (`ignoreUnknownKeys`, `coerceInputValues`, `isLenient`).
- **An action this build cannot read** — an unknown `t`, or a known one whose fields do not fit — is kept as
  `DuelAction.Unknown`, the object exactly as it came, and written back as it came (`LenientAction`). It is talk: it
  changes nothing on the table, and undo steps over it.
- Unknown keys elsewhere — in the record, the header, an entry, or inside an action that reads — are not kept: a build
  that does not know `fx` writes the entry back without it.
- `cursor` is held to 0…the number of entries.
- A record with `header.start` folds from that table. A build before Phase E E.2 ignores the key and folds the entries onto
  an empty deal, every move refused; another tool that does not know `start` should treat such a record as unreadable.
- An entry the table refuses is skipped and the fold goes on.

## Versioning

- `version` is 1 (`DuelRecord.VERSION`). Because the codec leaves defaults out, the app writes it only when it is not 1;
  a file from 1.0.74 may carry `"version":1`. Both mean 1. No reader checks it.
- The record grows by adding: a new key with a default (absent reads as before), or a new action under a new `t` (an older
  build keeps it whole as unknown). Never rename a key or a `t`, never change what a written value means, and never remove
  an action an older file holds.
- A release that changes what is written adds the old shape to `OldDataTest` in the same commit.

## Held by

- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/DuelGameTest.kt`:
  `aDuelSurvivesItsFileAndUnknownActionsPassThrough` — table, entries and cursor back equal; a newer build's action read
  as unknown and written back with its keys.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/DuelRecordTest.kt`:
  `provenanceIsWrittenAndReadBackAndSurvivesUndoAndInsertion`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/ReplaysTest.kt`: `aWhatIfPlaysOnFromAnyPoint` —
  `parent` and `parentAt` through a file.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/DuelOpeningTest.kt`: `theRollReadsBackFromTheFile`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/search/TurnSearchTest.kt`:
  `aTurnIsSolvedOnTheTableAsTheSeatSeesIt` — a fork's `start` through a file; a dealt duel writes no `start`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/effects/FxFoldTest.kt`: `theFoldSurvivesTheFile`;
  `.../effects/FxDeterminismTest.kt`: `theSameTableAndAnswersGiveTheSameLog` — the same table and answers write the same
  file.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/compat/OldDataTest.kt`, each shape as a build wrote it:
  `aDuelFrom1074StillReads`, `aReplayFrom1075StillReads`, `aDuelFrom1077StillReads`,
  `aDuelFrom1085WithUnstampedTokensAndLocksStillReads`, `aDuelFrom1086BeganWithoutTheDiceAnd1087sOpeningRollReadsBack`,
  `aDuelFrom1095WithRollsAndFlipsStillReads`, `aThrowFrom1096PlaysAsItDidAnd119sStowReadsBack`,
  `aDuelAndAReplayWithoutProvenanceStillReadAnd112sShapeReadsBack`, `aDuelWithoutEffectTagsStillReadsAnd120sTagsReadBack`.
