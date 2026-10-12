# Exchange formats

What leaves the app as a file, written down so another tool, or a later version of this one, can read it (Phase I, I.4;
F3: "a specification is a short document plus a round-trip test"). Each names the code that is its truth and the tests
that hold it.

| Format | What it is | Held by |
|---|---|---|
| [`.ydk` / `.ydkx`](YDKX.md) | A deck: YGOPro's decklist, and the payload after `#ydkx-extended` (groups, sets of groups, siding) | `YdkCodecTest`, `DeckGroupsTest`, `GroupSetsTest`, `DeckSidingTest` |
| [`.ydkw`](YDKW.md) | A web of decks: a `#web` header, then a complete `.ydkx` a deck | `WebCodecTest`, `DeckSidingTest` |
| [Deck QR code](QR.md) | A deck's `.ydkx` in one code (`NMT1:`) or several (`NMT1P:i/n/tag:`) | `DeckQrTest`, `DeckQrGridTest`, `QrTest` |
| [Duel records](REPLAY.md) | The duel in play and its replays: a header, then every action with who made it | `DuelRecordTest` and five more, `OldDataTest` |
| [`.nmtbackup`](NMTBACKUP.md) | Everything the person made, in one zip, with a manifest last | `BackupRoundTripTest`, `OldDataTest` |

## The rules every stored document keeps

These hold for the files above and for every document the app keeps in its data folder (`CLAUDE.md`, "Stored data outlives
versions"):

- **An older build reads a newer document.** Readers ignore keys they do not know and read a missing field as its default;
  so a new field is always added with a default, and never renamed or removed.
- **A stored list is version 1 for good.** Several documents are a bare JSON array (Test scores' log, a deck's reports, the
  evidence ledger, the exam's log, the replay library's index, a study's writes). Wrapped in an object to carry a `version`,
  one would read as nothing on an older device — and sync shares these files between devices on different versions — and
  that device's next write would put its own list over it. So their shape stays; a change that cannot be a new field with a
  default goes to a new file name. Each is held by an `OldDataTest` case as the build that first wrote it wrote it.
- **A key the app owns is rewritten whole.** In a `.ydkx`, `groups`, `groupSets` and `siding` are written from the app's own
  model on every save, so a field inside one that an older build does not know is lost when that build saves; only keys the
  app does not own pass through untouched. A new piece of a deck's data that older builds must keep is a new top-level key
  (which is why `groupSets` stands beside `groups`).
- **Unknown keys come back as equal JSON, not equal bytes.** A carried key is parsed and written again: `1.10` comes back
  `1.1`, an escape may come back as the character, and the app's own keys move to the end of the object.
