# `.ydkw` — a web of decks

A `.ydkw` is one text file holding a web: the field of decks expected at an event, yours starred among them, with a
share of the room for each when it is known (kai, 1.0.33: "sharable among people so they can use it to learn and simulate
tournament environments"). Format writes one from a web (Format › Export .ydkw) and opens one as a new web (Open a .ydkw,
a deck's Import, or a file handed over by another app). Every deck inside is a complete `.ydkx`, so a tool that reads deck
files can read each deck without knowing anything about webs. The code is `core/web/WebCodec.kt`; the app's side is
`Webs.fileText` and `Webs.open` (`neue/web/Webs.kt`).

## The file

UTF-8 text, lines ending `\n`. In order:

1. `#ydkw 1` — the magic (`WebCodec.MAGIC`), a space, the version (`WebCodec.VERSION`).
2. `#web ` and the header: one line of JSON.
3. For each deck, in the web's order: a blank line, `#deck <id> <name>`, then the deck exactly as `YdkCodec.write` writes
   a `.ydkx` — `#main` and its passcodes, `#extra` and its passcodes, `!side` and its passcodes, one a line, then, when the
   deck has a payload, `#ydkx-extended` and the payload's JSON over the lines that follow.

A block runs to the next line that begins `#deck ` or to the end of the file. No line inside a block can begin `#deck `:
the cards are numbers, the markers are fixed, and the payload is JSON, which never starts a line with `#`. The app writes
no `#created by` line in a block.

```
#ydkw 1
#web {"name":"Spring Regional","notes":"Top 32 of last month.\nYubel is a third of the room.","decks":[{"id":"a1","mine":true,"share":20},{"id":"b2","share":18}]}

#deck a1 Snake-Eye Fiendsmith
#main
14558127
14558127
89631139
#extra
1861629
!side
1
#ydkx-extended
{"groups":{"lens":"ROLES"},"siding":{"matchups":[{"id":"m-1","name":"Yubel","deck":"b2","first":{"out":[89631139],"in":[1]},"second":{"out":[89631139],"in":[1]}}]}}

#deck b2 Yubel
#main
78371393
#extra
!side
```

The app writes each payload on one line (`YdkCodec`, since 1.1.96; pretty-printed before); any layout of the same JSON reads the same.

## The header

The `#web` line's JSON object. The app writes it compactly and leaves out what is empty or default.

| Key | Type | Meaning |
|---|---|---|
| `name` | string | The web's name. Read blank, it is "Imported web". |
| `notes` | string | Notes about the room, newlines escaped. |
| `decks` | array | One object per deck, in the web's order. |
| `decks[].id` | string | The deck's id in this file: the same id as its `#deck` line. |
| `decks[].mine` | boolean | Starred: one of your decks, first in the web's lists and the decks you side as. Absent is false. |
| `decks[].share` | integer | Its expected share of the field, a whole percent 0–100. Absent is unknown. |

## A deck block

- **The `#deck` line** is `#deck `, the id, a space, the name. The id is everything up to the first space; the writer
  turns any space in an id into `-` and any newline in an id or a name into a space. A block whose id is blank is
  `deck<n>`, a blank name `Deck <n>` (n from 1, the block's place in the file).
- **The deck** is the `.ydkx` grammar the app reads everywhere (`YdkCodec.parse`): sections in any order, `#` or `!`
  markers, anything unrecognised a warning, never a failure.
- **The payload** is the deck's `#ydkx-extended` JSON object, carried whole. The app owns `groups` and `groupSets`
  (`DeckGroupsCodec`) and `siding` (`SidingCodec`); every other key (the legacy tool's `sidingPatterns`, `notes`,
  `configurations`) passes through untouched.

## Decks that point at each other

A siding plan names the deck it is against by id: `siding.matchups[].deck` (`SidingCodec`, `Matchup.deckId`). Inside a
`.ydkw` that id is another block's id. So ids need only be unique within the file, and they **must** be: the app gives
each id one new id when it opens the file, so two blocks with one id become one deck.

Opening a `.ydkw` (`Webs.open`) never reuses its ids. Every deck is saved as a new deck under a new id, and the web gets a
new id of its own, so opening the same file twice makes two webs. Before each deck is saved its payload goes through
`SidingCodec.remap` with the map from file ids to new ids:

- a matchup against a deck of the file follows that deck to its new id;
- a matchup against a deck the file does not hold (a library deck linked by hand, 1.0.42) keeps its name and loses the
  link;
- the `siding` key is written again through the app's own reading of it; every other key of the payload is untouched.

Nothing else in the file names a deck.

## What the file does not carry

- The web's own id and its last change (`DeckWeb.id`, `updatedAtEpochMs`): an opened web is a new web.
- Where a share came from (`WebEntry.shareSource`, Phase G): an opened web's shares have no source.
- A deck's library notes (the `notes` the library keeps beside the deck) and its created and changed times. A deck's
  notes travel only when they are in its payload.

## What a reader must tolerate

`WebCodec.read` is the reference. It:

- treats a text as a web when, after an optional byte-order mark and leading whitespace, it begins `#ydkw`. The number
  after it is not checked;
- reads the header from the last `#web ` line before the first `#deck ` line. A `#web` line after a block is part of that
  block;
- reads the header field by field, so one odd value never costs the rest: a header that is not a JSON object is an empty
  header; unknown keys are skipped; `mine` is true for `true` or the string `"true"` in any case; a deck entry with no id
  or a blank one is skipped;
- reads a share from a number or a string: a trailing `%` is dropped; a value strictly between 0 and 1 is a fraction of
  the field (0.2 is 20); anything else is a percent; it is rounded to a whole percent and held to 0–100; a negative, a
  non-number or NaN is unknown;
- keeps a block the header does not list, after the listed ones, in file order, not starred, with no share; and ignores a
  header entry with no block;
- reads line endings `\n`, `\r\n` or `\r`.

Written back, a header read this way is whole again (`aShareThatIsNotWholeCostsNothingElse`).

## Versioning

- The file says `#ydkw 1`. No reader checks the number yet.
- Change by adding, never by changing: a new key in the header or in a header deck entry (skipped by every reader so far),
  or a new key in a deck's payload (passed through by every `.ydkx` reader). Never a new kind of line inside a block, and
  never a line inside one that begins `#deck `.
- A change today's reader would get wrong cannot lean on the number alone, since nothing refuses a higher one: readers
  must first learn to refuse a version above their own, in a release before it.

## Held by

- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/web/WebCodecTest.kt`
  - `aWebRoundTrips` — written and read back equal; the file begins `#ydkw 1\n#web {`.
  - `eachDeckBlockIsADeckFileOnItsOwn` — a block cut out reads as the same `.ydkx`, payload and all, notes holding
    `#deck` and a newline.
  - `aDeckTheHeaderDoesNotListIsKeptAtTheEnd`
  - `aShareThatIsNotWholeCostsNothingElse`
  - `oddFieldsReadForgivinglyAndNonsenseIsSkipped`
  - `onlyAWebIsAWeb` — the magic, a byte-order mark, a broken header still reading the decks.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/siding/DeckSidingTest.kt`:
  `remappingFollowsTheNewIdsAndForgetsUnknownOnes` — the remap opening applies.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/ydk/YdkCodecTest.kt` — the deck grammar inside each block.
- Not held by a test of its own: the opening path in `Webs.open` (new ids, the remap per block, the web saved).
