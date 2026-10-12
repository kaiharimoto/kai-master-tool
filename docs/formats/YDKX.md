# YDK and YDKX — the deck file

A deck leaves Neue Master Tool as a text file. `.ydk` is YGOPro's decklist: three sections of passcodes, one
copy a line. `.ydkx` is the same file with one more block at the end, `#ydkx-extended`, holding a JSON object:
the deck's groups, its other sets of groups, its hand goals and its siding, beside whatever the legacy HTML tool
or a later build put there. A `.ydk` reader that stops at the block reads a `.ydkx` as its decklist. This document
is what another tool, or a later version of this one, needs to read and write both. The code is
`core/ydk/YdkCodec.kt` (the lines), `core/deck/DeckGroupsCodec.kt` (`groups`, `groupSets`) and
`core/siding/DeckSiding.kt` (`SidingCodec`, `siding`).

## A file, exactly as Neue writes it

A short deck with two groups, opened from a legacy file that carried `"version": "1.0"`:

```
#created by kai's master tool
#main
89631139
89631139
14558127
14558127
23434538
#extra
1861629
!side
27204311
94145021
#ydkx-extended
{"version":"1.0","groups":{"defs":[{"id":"g-5f3a1c","name":"Engine","color":2,"order":0},{"id":"g-9b07e4","name":"Hand traps","color":3,"order":1}],"cards":{"89631139":"g-5f3a1c","14558127":"g-9b07e4","23434538":"g-9b07e4"},"lens":"ROLES"}}
```

(Produced by the same steps as `YdkCodec.write`. Since 1.1.96 the payload is one line; builds before it
pretty-printed it, four spaces a level — the same JSON, read the same.)

## The lines

**Encoding.** UTF-8. Neue writes no byte-order mark and ends every line, the last too, with LF. It reads a
leading U+FEFF and CRLF. A line break is LF; a CR alone is not one.

**Each line is trimmed**, and blank lines are skipped. A line starting `#` or `!` is a marker or a comment: the
rest of it, trimmed and lowercased, is compared whole.

| After `#` or `!` | Meaning |
|---|---|
| `main` | Cards that follow are the Main Deck. |
| `extra` | … the Extra Deck. |
| `side` | … the Side Deck. |
| `created by …` | The tool that wrote it (`YdkDocument.createdBy`). Read, never stored with a saved deck. Matched in any case, but the words `created by` are cut from the value only when written in lower case (`#Created by X` reads as `Created by X`). |
| `ydkx-extended` | Everything after this line is the extended block. Must be alone on its line. |
| anything else | A comment, ignored without a warning. |

So `#side`, `!side`, `!main` and `# MAIN` all work, and markers may come in any order, more than once, or not at
all: cards before the first marker are the Main Deck, and a repeated marker adds to its section.

**A card line** is digits only that fit a signed 32-bit integer: the card's passcode (`CardId`). Each copy is its
own line, in the deck's order; that order is the deck's (`Deck.main`, `extra`, `side`) and round-trips. Leading
zeros are not kept: `00012345` is written back `12345`. Any other line is skipped with a warning
(`YdkParseResult.warnings`); the rest of the deck still loads.

**The extended block** is every line after the marker, to the end of the file, joined and trimmed, read as one
JSON value. It must be an object. Empty, it is no block (the file reads as a `.ydk`). Not JSON, or not an
object, it is dropped with one warning and the deck still loads. No card is read after the marker, so the block
is always last. Neue reads it leniently (kotlinx `isLenient`); write strict JSON (RFC 8259).

**What Neue writes** (`YdkCodec.write`): `#created by kai's master tool` (from the builder and the library's
Export; nothing from `DeckRepository.exportText`, `.ydkw` or the QR body), then `#main`, `#extra`, `!side` —
always all three, in that order, even when a section is empty — then, when the deck has a payload,
`#ydkx-extended` and the object on one line (before 1.1.96 it was pretty-printed, one array element a line, the last passcode
of each list stood alone on a line, and a reader that reads on took it for a Side Deck card). A deck with
nothing to put in the block is written without one, so a `.ydkx` may hold no block; the Export menu's "YDKX file,
with groups" still names it `.ydkx`. "YDK file" never writes the block.

## The extended payload

One JSON object. Neue owns three keys, reads one more, and carries every other key through.

| Key | Owner | Neue |
|---|---|---|
| `groups` | `DeckGroupsCodec` | Reads; rewrites whole on every save of the deck. |
| `groupSets` | `DeckGroupsCodec` | Reads; rewrites whole on every save. |
| `siding` | `SidingCodec` | Reads; rewritten whole when a plan is edited (`Webs.saveSiding`: the siding editor, Ai). A save from the builder carries it. |
| `sidingPatterns` | the legacy tool | Read once, when there is no `siding` object. Never written; carried through. |
| `version`, `cardPool`, `configurations`, `notes` | the legacy tool | Never read; carried through. |
| anything else | a later build, another tool | Never read; carried through. |

An owned key is removed when there is nothing to store, and a payload left empty is no block, so a deck that
never used a feature carries nothing for it.

**Carried through means the same JSON value, not the same bytes.** A key Neue does not own comes back equal as
JSON (`YdkCodecTest.roundTripPreservesExtendedBlockVerbatim` compares values). Whitespace is printed again; a
string's escapes are chosen again (`é` comes back `é`, `\/` comes back `/`); a repeated key keeps its last
value; an integer that fits 64 bits keeps its digits, and any other number passes through a 64-bit double
(`1.10` comes back `1.1`, `1e2` comes back `100.0`, a 23-digit integer loses digits). The same holds where the
payload is stored (SQLite's `extendedJson`, sync, backups), which print it compact.

### `groups` — the groups in use

```json
"groups": {
  "defs": [{"id": "g-5f3a1c", "name": "Engine", "color": 2, "order": 0}],
  "cards": {"89631139": "g-5f3a1c", "14558127": "g-9b07e4"},
  "lens": "ROLES",
  "fitted": [14558127, 89631139],
  "goals": [{"id": "q-1c0e", "name": "Opens", "hand": 5,
             "asks": {"g-5f3a1c": "AT_LEAST_1", " ungrouped": "AT_MOST_1"},
             "condition": "Engine>=1 | Hand traps>=2"}]
}
```

| Field | Type | Meaning (Kotlin) | Absent |
|---|---|---|---|
| `defs` | array | The groups (`DeckGroup`), written in display order: `order`, then `id`. | No groups. |
| `defs[].id` | string | The group's id. Neue mints `g-<hex>`. | The entry is skipped. |
| `defs[].name` | string | Its name. | `"Group"` |
| `defs[].color` | integer | An index into the palette in use (`GroupMarkers.palettes`, six hues), read modulo 6. Never a colour. | `0` |
| `defs[].order` | integer | Its place. | Last (`Int.MAX_VALUE`) |
| `cards` | object | Passcode (as a key) → group id (`DeckGroups.assignments`). One entry a card, not a copy. An id no `defs` entry holds reads as no group (`groupOf`); a passcode need not be in the deck, since an alternate artwork takes its card's group (`projectedOnto`). | Nothing grouped. |
| `lens` | string | How the deck was last read (`Lens`): `DECK`, `ROLES`, `ARCHETYPE`, `TYPE`, `COPIES`, `LEGALITY`. Always written with the key. Unknown → `DECK`. | `ROLES` if `"breakdown": true` (v1.2), else `DECK`. |
| `fitted` | array | The Fitted and Separate order (`DeckGroups.fitted`, 1.0.39): passcodes, each once; numbers or numeric strings. Written only once set. | The deck's order. |
| `goals` | array | Hand goals (`HandGoal`), written only when there are any. | None. |
| `goals[].id` | string | Neue mints `q-<hex>`. | The goal is skipped. |
| `goals[].name` | string | | `""` |
| `goals[].hand` | integer | Hand size: 5 first, 6 second. | `5` (`LensOdds.DEFAULT_HAND`) |
| `goals[].asks` | object | Group id → `Ask`: `ANY`, `AT_LEAST_1`, `AT_LEAST_2`, `AT_MOST_1`, `NONE`. The key `" ungrouped"` (a leading space, `HandGoal.UNGROUPED`) is the cards in no group. An unknown name is dropped (reads as `ANY`). | No asks. |
| `goals[].condition` | string | A condition in the instruments' grammar (`Goals.parse`, Phase G), held with the asks. Written only when not blank. | None. |
| `breakdown` | boolean | v1.2's flag. Read for `lens`, never written. | |

The whole key is left out when there are no groups, no goals and the lens is `DECK`.

### `groupSets` — the deck's other sets of groups (2026-10)

```json
"groupSets": {
  "active": "s1",
  "sets": [
    {"id": "s1", "name": "Roles"},
    {"id": "s2", "name": "Going second",
     "defs": [{"id": "g-77d2b0", "name": "Breakers", "color": 4, "order": 0}],
     "cards": {"27204311": "g-77d2b0"}}
  ]
}
```

`GroupSets`: every set in the menu's order and the one in use, `active`. **The set in use is never kept twice**:
its groups are the `groups` key, which every older build reads, and its entry here is `id` and `name` alone.
Every other set carries `defs`, `cards` and, once set, `fitted`, shaped as in `groups` (no `lens`, no `goals`:
those belong to the deck). Written only when the deck has more than its one set named `Set 1`
(`GroupSets.isPlain`). Read: an entry without a non-blank `id` is skipped, a blank `name` reads as the id, a
repeated id keeps the first, an empty list is no sets, and an `active` naming no set puts the first in use. Neue
mints `s<N>`. `groupSets` may stand without `groups` when the set in use has no groups.

### `siding` — how the deck sides (1.0.35)

```json
"siding": {"matchups": [{
  "id": "m-3k9x2a", "name": "Yubel", "deck": "<deck id>", "note": "They break boards going second.",
  "first":  {"out": [23434538], "in": [27204311], "note": "Maxx C does little going first."},
  "second": {"out": [89631139], "in": [94145021], "outCopies": {"89631139": [1]}},
  "covers": [78371393, 4779091, 31764700]
}]}
```

`DeckSiding`: the matchups in the order they were made (`Matchup`).

| Field | Type | Meaning | Absent |
|---|---|---|---|
| `id` | string | Neue mints `m-<base36>`. A repeated id keeps the first. | `m-<index>` |
| `name` | string | The opponent. | `"Opponent"` |
| `deck` | string | The deck it is against, by id: a deck of the same web, or a library deck (1.0.42). Written only when linked. A `.ydkw` opened with new ids remaps it, and an id it cannot map becomes absent (`SidingCodec.remap`). | Known by name only. |
| `note` | string | The matchup's note. Written only when not blank. | `""` |
| `first`, `second` | object | The plan going first and going second (`SidePlan`). Always written. | An empty plan. |
| `….out` | array | Cards taken out of the Main and Extra Decks, one entry a copy. Numbers, or numeric strings; anything not a positive integer is skipped. Always written. | `[]` |
| `….in` | array | Cards brought in from the Side Deck, the same way. Always written. | `[]` |
| `….note` | string | Why. Written only when not blank. | `""` |
| `….outCopies`, `….inCopies` | object | Which copies (2026-10): passcode → copy indices, 0 the first of that card's copies in its section (Main or Extra for out, Side for in). Written only when there are picks; non-negative integers, repeats dropped. Copies moved beyond the picks are the first ones not picked (`SidingMarks`). | The first copies. |
| `covers` | array | Up to three passcodes the opponent is known by (1.0.42, `SidingCodec.COVERS`); more are cut, repeats dropped. Written only when there are any. | None. |

If `siding` is an object, `sidingPatterns` is never read. With no matchups the key is removed.

### `sidingPatterns` — the legacy tool's siding (read only)

```json
"sidingPatterns": {
  "vs Snake-Eye": {"deckName": "Snake-Eye", "goingFirst": {"in": ["91800273"], "out": ["14558127"]},
                   "goingSecond": {"in": [], "out": []}},
  "Old": {"out": ["14558127"], "in": ["91800273"]}
}
```

Each entry is a matchup: its name is `deckName`, else `ydkDeckName`, else the key; its id `legacy-<index>`. An
entry with neither `goingFirst` nor `goingSecond` is the oldest shape, one plan, read as going first. Every other
field the legacy tool wrote (`associatedYdk`, `backgroundColor`, `thumbnails`, `tags`, …) is ignored. The key is
never written or changed: a deck edited in Neue writes `siding` beside it, and the legacy tool still finds its own.

## Order

- **Sections**: Neue writes Main, Extra, Side. The legacy tool wrote Main, Side, then Extra (only when not
  empty). Readers must take any order.
- **Cards** within a section: meaningful, the deck's own order. Kept.
- **Object keys**: not meaningful. Neue writes the keys it carries first, in the order it read them, and its own
  last, in the order it last rewrote them: a builder save puts `groups` then `groupSets` at the end. Never depend
  on key order.
- **Arrays**: `defs` in display order; `fitted`, `matchups`, `sets` and each plan's `out` and `in` are kept as
  written.

## Versioning

There is no version number on Neue's side (`version` is the legacy tool's, carried, never read). Compatibility
is kept by these rules:

1. **A new kind of data takes a new top-level key.** Every build since the payload was kept opaque carries
   unknown top-level keys, so an older build keeps it through its own saves.
2. **A new field inside an owned key** (`groups`, `groupSets`, `siding`) must mean "as before" when absent, and is
   written only when it says something else — as `fitted`, `goals`, `condition`, `outCopies`, `inCopies`,
   `covers` and `deck` were. An older build reads past it, but **drops it on its next save**, because an owned key
   is rewritten whole and unknown fields inside it are not kept. Anything that must outlive an older build's save
   goes in a top-level key: that is why `groupSets` stands beside `groups` and not inside it.
3. Never rename a key or an enum name, never change a field's type, never reuse a retired name. Unknown enum
   names read as the quiet default (`lens` → `DECK`; an ask is dropped).
4. A shape an older build wrote is held in `core/compat/OldDataTest` in the same commit that changes it.

## What a reader must tolerate

- A BOM, CRLF, blank lines, spaces round a line, `#` and `!` used either way, sections in any order, repeated or
  missing, cards before any marker, comment lines, and lines that are not passcodes.
- **Stop reading cards at `#ydkx-extended`.** A file written before 1.1.96 holds the block pretty-printed, one
  array element a line, so the last passcode of each array (`fitted`, `out`, `in`, `covers`) stands on a line of
  bare digits; a reader that reads on takes them for Side Deck cards. Neue writes the block on one line now
  (`YdkCodecTest.aPlainYdkReaderGainsNoCardFromTheExtendedBlock`), but files of older builds are out there.
- A `.ydkx` with no block; an empty block; a block that is not JSON.
- Keys it does not know, at any level; a field missing, or of another type (read as absent); passcodes written as
  numeric strings in `fitted` and the plans; an enum name it does not know.
- Group ids no group holds; passcodes in `cards` the deck does not hold; more than three `covers`.

What is not in the file: the deck's name (Neue names an imported deck after the file, less its extension), its
library notes (`DeckEntry.notes`), its covers (`NeuePreferences.covers`; the QR code carries them, `QR.md`), and
card names or text. Whether another simulator accepts the JSON lines of a `.ydkx` is that simulator's affair;
the `.ydk` export, with no block, is the form for other tools.

Where else the format appears: each `#deck` block of a `.ydkw` web is a complete `.ydkx` with no `#created by`
(`WebCodec`, `YDKW.md`, `WebCodecTest.eachDeckBlockIsADeckFileOnItsOwn`), and the QR code's body is a `.ydkx` (`QR.md`).

## Held by

- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/ydk/YdkCodecTest.kt` — the lines and the
  round trip: `parsesCanonicalFile`, `preservesDuplicateCountsAndOrder`, `acceptsSectionsInAnyOrder`,
  `acceptsHashSideMarker`, `handlesCrlfAndBom`, `treatsLeadingIdsAsMainWhenMarkerMissing`,
  `reportsUnreadableLinesWithoutLosingTheDeck`, `ignoresUnknownCommentLines`, `readsYdkxExtendedBlock`,
  `roundTripPreservesExtendedBlockVerbatim`, `writesCanonicalSectionOrder`,
  `writesEmptySectionMarkersSoOtherToolsCanRead`, `deckRoundTripsExactly`,
  `malformedExtendedBlockWarnsInsteadOfFailing`, `emptyInputProducesEmptyDeck`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/deck/DeckGroupsTest.kt` — `groups`:
  `theFittedOrderIsSavedAndReopenedAndAbsentWhenNeverSet`, `codecRoundTripsThroughTheExtendedPayload`,
  `theQuestionSurvivesBeingSavedAndReopened`, `anAskThisBuildHasNeverHeardOfIsDroppedNotFatal`,
  `aDeckWithNoQuestionsGainsNoGoalsKey`, `aDeckSavedByTheOldBreakdownFlagStillOpensOnItsGroups`,
  `anUnknownLensFallsBackToThePlainDeck`, `codecPreservesEveryOtherKeyVerbatim`,
  `emptyGroupsOnAPlainDeckWriteNothing`, `codecShrugsOffMalformedShapes`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/deck/GroupSetsTest.kt` — `groupSets`:
  `aDeckWithoutSetsWritesNoSetsKey`, `setsRoundTripWithTheOneInUseInTheGroupsKey`,
  `setsSurviveWhenTheSetInUseHasNoGroups`, `aBrokenSetsKeyReadsPlainOrRepaired`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/siding/DeckSidingTest.kt` — `siding` and
  `sidingPatterns`: `sidingRoundTripsAndLeavesEveryOtherKeyAlone`,
  `anOpponentWithNoDecklistIsANameAndThreeCardsAndSurvivesTheFile`, `noSidingWritesNoKeyAndNothingWritesNothing`,
  `theLegacyPatternsAreReadButNeverWritten`, `remappingFollowsTheNewIdsAndForgetsUnknownOnes`,
  `strangeShapesAreDroppedNotFailed`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/siding/SidingCopiesTest.kt` —
  `picksTravelInThePlanAndAPlanWithoutThemWritesAsBefore`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/compat/OldDataTest.kt` — older shapes written
  back unchanged: `aGroupsPayloadFromBeforeSetsReadsAsOneSetAndWritesBackTheSame`,
  `aQuestionFromBeforeConditionsReadsWithNoneAndWritesBackTheSame`,
  `aSidePlanFromBeforeCopiesWerePickedReadsAndWritesBackTheSame`.
- `app/core/src/jvmTest/kotlin/com/kaiharimoto/mastertool/core/data/RepositoryTest.kt` —
  `preservesYdkxExtendedPayloadThroughStorage` (the payload through SQLite and back out to a file).

Not yet held by a test: one `.ydkx` text with `groups`, `groupSets` and `siding` together through `YdkCodec` and
all three codecs; a carried key holding a decimal or an exponent (which does not come back byte for byte, above);
`!ydkx-extended`; a block that is JSON but not an object.
