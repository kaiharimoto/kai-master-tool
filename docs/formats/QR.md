# The deck QR code

Neue shows a whole deck as QR codes (Export › QR code to scan) for a phone or tablet to read with Import › Scan a
QR code, or out of a picture of them. A code carries the deck's `.ydkx` — the cards and every key of its extended
payload: groups, sets of groups, hand goals, siding, the legacy tool's keys — with the deck's name and its covers.
A deck too large for one comfortable code is split into parts, all shown at once, read in any order. This
document is what another tool, or a later version, needs to write or read one. The code is `core/ydk/DeckQr.kt`
(`DeckQr`, `DeckQrParts`, `DeckQrGrid`), `core/ydk/Base45.kt`, `core/ydk/DeckExports.kt` (`DeckCodes`) and
`neue/qr/Qr.kt` (`QrMatrix`, `QrReader`).

## The layers

1. **The body**, UTF-8 text: a `.ydkx` under one or two header lines.
2. **zlib** (RFC 1950): the body deflated at level 9 (`JvmZlib`, `Deflater.BEST_COMPRESSION`).
3. **Base45** (RFC 9285, `Base45`): two bytes in three characters of
   `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:` — exactly the QR alphanumeric set, so a code is in
   alphanumeric mode throughout. The EU's COVID certificates travel the same way.
4. **The frame**: `NMT1:` and the Base45 in one code, or parts headed `NMT1P:<i>/<n>/<tag>:`.

## The body

```
#name <the deck's name>
#covers <passcode>,<passcode>,<passcode>
#main
…
#extra
…
!side
…
#ydkx-extended
<the payload, compact JSON on one line>
```

- `#name ` (`DeckQr.NAME`) is always the first line. CR and LF in the name are written as spaces. Neue names a
  deck with no name `Untitled Deck`.
- `#covers ` (`DeckQr.COVERS`) is the second line, written only when there is a cover: the library's chosen cover
  cards (`NeuePreferences.covers`, at most three), positive passcodes only — a negative choice is an own picture,
  a file on the machine that made the code.
- Then the deck exactly as `YdkCodec.write` writes it with no `#created by` (`YDKX.md`): `#main`, `#extra`,
  `!side`, one passcode a line; and, when the deck has a payload, `#ydkx-extended` and the JSON object, compact.
- To a deck-file reader `#name` and `#covers` are comment lines: **the body is a deck file anything can import**.

## One code, or parts

The Base45 of the deflated body is the *payload*.

- **One code** when `NMT1:` and the payload together are at most `SINGLE_CHARS` (1,600) characters — about version
  27, easy off a screen: `NMT1:<payload>`.
- **Parts** past that. `count` is `max(2, ⌈length / PART_CHARS⌉)` with `PART_CHARS` 1,200; the payload is cut into
  runs of `⌈length / count⌉` characters (the last shorter), and `n` is the number of runs, which may be fewer than
  `count`. Part `i` of `n` is

  ```
  NMT1P:<i>/<n>/<tag>:<run i>
  ```

  `i` from 1, `i` and `n` in decimal; `tag` is FNV-1a (32 bits, offset `0x811C9DC5`, prime `0x01000193`) over the
  whole payload's characters, unsigned, in base 36, upper case (`DeckQr.tag`). The runs joined in order are the
  payload; the tag says which parts belong together and that they joined whole.
- **At most `MAX_PARTS` (24)**, some twenty kilobytes of compressed deck file. Past that the deck sheds, trying in
  turn: the whole payload; only `groups` and `groupSets`; only `groups`; no payload (the cards, the name and the
  covers). The first that fits is written, and `DeckQrCode.leftOut` names the top-level keys left behind
  (`version` is never named). If even the cards do not fit, there is no code (`DeckQr.write` returns null) and
  the dialog says to share the `.ydkx` file. Ninety passcodes deflate to a few hundred bytes; it does not happen.

`SINGLE_CHARS`, `PART_CHARS` and `MAX_PARTS` are the writer's choices. A reader assumes none of them.

## A code, worked through

The deck of `YDKX.md`'s example, named `Blue-Eyes test`, with two covers. The body:

```
#name Blue-Eyes test
#covers 89631139,14558127
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

393 bytes, deflated to 245 (a zlib stream, `78 DA`), 368 characters of Base45. One code, 373 characters (the
spaces are Base45 digits):

```
NMT1:NCFFZA9WPIUO952QBU:GNKSLM L/8K6NGL4HCHK0:IG%Q+OCXX3NEPGB9LAW0-DW:E$RIVUK .RD0P9P3UJF1.9+0NOG3ZIHAB1*0O:GK5+8/YBR94F%JD/J%+I%Y4R39RM76MV+DR L3.G9/-J3E1WG7 KO/ALBFAQD3F6P%G2YIA.OJM7AN/E-HAGYFQ5C:ZI2%SVEBKOUWFKBZ1.R2VN1L+T818E8I%$PZOTWETTZHO2TB-5$:12AHOP9K$BIEDJ9RCVSC3S%*7OJ4H.U1G21VD6*6V2WD-IF5E-15YXD:C99CPEVA7NA+04ANJ3BWV$LJEULAM3EUB6MPOULYBXS3-LU9XF FF5BSDGBX:2QA5W1
```

The same deck written with `singleChars = 100, partChars = 100` (the parameters the tests use to force a
split) is four parts of 92 characters, tag `110HKQS`:

```
NMT1P:1/4/110HKQS:NCFFZA9WPIUO952QBU:GNKSLM L/8K6NGL4HCHK0:IG%Q+OCXX3NEPGB9LAW0-DW:E$RIVUK .RD0P9P3UJF1.9+0NOG
NMT1P:2/4/110HKQS:3ZIHAB1*0O:GK5+8/YBR94F%JD/J%+I%Y4R39RM76MV+DR L3.G9/-J3E1WG7 KO/ALBFAQD3F6P%G2YIA.OJM7AN/E-
NMT1P:3/4/110HKQS:HAGYFQ5C:ZI2%SVEBKOUWFKBZ1.R2VN1L+T818E8I%$PZOTWETTZHO2TB-5$:12AHOP9K$BIEDJ9RCVSC3S%*7OJ4H.U
NMT1P:4/4/110HKQS:1G21VD6*6V2WD-IF5E-15YXD:C99CPEVA7NA+04ANJ3BWV$LJEULAM3EUB6MPOULYBXS3-LU9XF FF5BSDGBX:2QA5W1
```

Deflate is not canonical: another zlib may make other bytes from the same body, and they read the same. Compare
decks, never codes.

## The symbol

`QrMatrix.of` (ZXing's encoder): error correction **M** while that keeps the code at version 25 or under (117
modules a side), else **L**, whose smaller code a camera reads more easily off a screen. A quiet zone of four
modules. Black on white in both themes. The parts stand together in one grid, each labelled `i of n`, as large
as the window allows (`DeckQrGrid`), so a camera swept across them, or one screenshot, collects them all. The
largest code there is, version 40 at L, holds 4,296 alphanumeric characters (`QrTest.theLargestCodeThereIsIsDrawn`).

## Reading

**Collecting parts** (`DeckQrParts.offer`, one collector a scan): the text, CR and LF trimmed from both ends and
leading spaces from the front, is matched whole against

```
NMT1P:(\d{1,3})/(\d{1,3})/([0-9A-Z]{1,8}):(.*)
```

Not a part, it is handed on as it was (`Offer.Whole`): a lone `NMT1:` code, a `ydke://` code, anything. A part
with `n` < 1 or `i` outside 1…`n` is ignored. A part whose tag or `n` differs from the collection's starts it over
with that deck. A part seen again changes nothing (`Offer.Part.new` is false). When all `n` are in, they are
joined in order and the join's tag checked: right, it is `NMT1:` and the join (`Offer.Whole`); wrong, every part
is dropped (`Offer.Part(0, n, false)`). The collector starts empty after either.

**Reading a code** (`DeckQr.read`): nothing but whitespace may come before `NMT1:`. The rest, less trailing CR and LF
— never trailing spaces, which are Base45 digits — is Base45-decoded (a length of 1 modulo 3, a character
outside the alphabet, a triple over 65,535 or a final pair over 255 is no code), inflated to at most 1 MiB
(`INFLATED_LIMIT`; a stream that ends early, wants a dictionary or grows past it is no code), decoded as UTF-8
and read by `YdkCodec.parse`. The name is a `#name ` line among the first three lines, trimmed, none when empty;
the covers a `#covers ` line among the first three, split on commas, each a positive integer or skipped. A
caller keeps at most three (`DeckCovers.MAX`).

**Reading a scan** (`DeckCodes.read`): Neue's code first; else the first `ydke://` code anywhere in the text,
alone or inside a link (`YdkeCodec`); else, when a line is a section marker (`#main`, `#extra`, `!side`, `#side`,
`!main`, `!extra`), the text as a deck file. A deck of no cards is none. The builder takes the code's name, else
`Scanned deck`, and keeps the covers under the deck's id once it is saved. Ai and the Format page read pasted
text the same way.

**Out of pixels** (`QrReader`, the APK's `ScanActivity`): ZXing's multi reader for every code in a frame or
picture, then the single reader for a lone code at an angle; both binarizers, and the picture inverted, for a
light code on a dark screen.

## Versioning

- **`NMT1` is the version.** A change a version-1 reader would misread takes a new prefix (`NMT2:`, `NMT2P:`). A
  reader that does not know a prefix reads no deck from it, and says so: it never guesses.
- Within version 1 **the body is a `.ydkx`**, and new data travels as `YDKX.md` says: a new top-level key of the
  payload, which every version-1 reader carries.
- A new header line is a `#` comment after `#name` and `#covers`: those two stay the first two lines, since a
  reader looks only at the first three, and a new line must not be one a deck-file reader takes for a marker
  (`main`, `extra`, `side`, `ydkx-extended`, `created by`).
- Fixed for version 1: the header grammar, the tag function, Base45, zlib, UTF-8 and the 1 MiB ceiling. The sizes
  of codes and parts, and the order of shedding, may change.

## What a reader must tolerate

- Parts in any order, each any number of times, mixed with other codes; a part of another deck (start over); a
  damaged part (the tag catches it).
- Up to 999 parts and runs of any length, whatever the writer's limits.
- A space at the end of a payload, and `:` inside one: split the header off with the grammar above, never at the
  last colon.
- A body with no `#covers`, an empty name, or no payload; everything `YDKX.md` asks a deck-file reader to
  tolerate.

## Held by

- `app/core/src/jvmTest/kotlin/com/kaiharimoto/mastertool/core/ydk/DeckQrTest.kt` — the round trip and the
  frame: `theWholeDeckCrosses`, `theLabFileFits`, `aDeckPastOneCodeIsSplitAndJoinsInAnyOrder`,
  `aPartOfAnotherDeckStartsOver`, `aDamagedPartIsCaughtByTheTag`,
  `onlyPastTheMostPartsDoesADeckShedItsExtrasThenItsGroups`, `aScannedYdkeCodeIsADeck`,
  `aScannedDeckFileKeepsItsGroups`, `aScanWithNoDeckInItIsNothing`, `aCodeIsADeckFileOnceUnpacked`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/ydk/Base45Test.kt` — RFC 9285's examples and
  every byte: `theRfcsExamples`, `everyByteRoundTrips`, `somethingElseIsNot`.
- `app/neue/src/jvmTest/kotlin/com/kaiharimoto/neue/QrTest.kt` — through pixels and back:
  `theWholeDeckWithItsGroupsReadsBackOffTheScreen`, `theLegacyLabFileIsTwoCodesAndOnePictureOfThemReadsBack`,
  `theLargestCodeThereIsIsDrawn`, `aCodeLightOnDarkReadsToo`, `aPictureWithNoCodeIsNothing`.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/ydk/DeckQrGridTest.kt` — the grid the parts
  stand in (layout, not the format).
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/ydk/DeckExportsTest.kt` — the `ydke://` codes a
  scan also reads.

Not yet held by a test: the `groups` and `groupSets` step of shedding (the test covers everything → `groups` →
nothing); the 1 MiB ceiling; a name with a line break; the first-three-lines rule for `#name` and `#covers`.
