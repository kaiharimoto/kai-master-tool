# Phase B: Card truth (1.1.x)

**Goal:** the card data everything else stands on is correct. A copy is counted by card, not by printing; a card
is legal only where and when it has been released; any past banlist can be asked for by date; and the field is
read honestly.

**Done when:**
- an alternate-art Ash counts against the three-copy limit;
- an OCG-only card is illegal in TCG, and a card not yet out in the TCG says when it will be;
- "odds as of the March list" works;
- every red-team "real-world data" lead (`docs/AI-INTELLIGENCE.md` §1) is closed or carried with a reason.

---

## 1. One card, whatever its printing (`core/model/CardIdentity.kt`)

**The fault.** A deck file holds passcodes, and an alternate artwork has its own (Ash Blossom is `14558127`, its
alternate `14558128`). Every count in the app counted passcodes: two of each Ash read as two and two, so four Ash
were legal; `hand_odds` asked about "Ash" found only the printing the index calls canonical; the field counted one
strategy's Ash in two columns.

**The rule.** A card is its canonical passcode: the one `CardIndex.byId` resolves any printing to (YGOPRODeck's first
image). `CardIdentity` turns a list of passcodes into counts by card, and everything that counts copies goes through it:
- `DeckValidator` and `DeckEditor` (the copy limit, the remaining-copies badge, add and drop);
- `hand_odds` (and the World's `HandCounter`, which already counted overlap exactly — `hand_odds` now goes through it);
- the field (`FieldBuilder`'s similarity, staples and cores) and `DeckTags`, `DeckSearch`.

The deck itself is never rewritten: the printing a person chose is kept, and a `.ydk` round-trips byte for byte.

**Konami's own number.** `Card.konamiId` (YGOPRODeck's `misc_info.konami_id`, Konami's database id) is kept, so a
ruling or a list from Konami's site can be matched to a card without its name.

## 2. Released where, and when (`core/model/CardRelease.kt`, `core/deck/Legality.kt`)

**From the pool.** The pool is fetched with `misc=yes&format=genesys`, which adds, per card:
- `tcg_date`, `ocg_date` (first release in each region, `yyyy-MM-dd`);
- `formats` (`TCG`, `OCG`, `Master Duel`, …);
- `genesys_points`.

Kept on `Card` as `tcgDate`, `ocgDate`, `formats`, `genesysPoints`, `konamiId`, stored in five new columns
(**schema 4**, five columns, `migrations/3.sqm`, held by `MigrationTest`). A pool stored by an older build has none of them; the
app reads that as *unknown*, never as illegal, and refreshes the pool once (`PoolRecord.misc`) so the data arrives.

**Legality.** `Legality.of(card, format, asOf)` says one of:
- **legal**;
- **not released here**: no date in this region and the region not among its formats (an OCG-only card in TCG);
- **not out yet**: its date in this region is after `asOf` ("out 14 Nov 2026");
- **unknown**: the pool has no release data (an old pool, a card the site has not dated) — never an error.

`DeckValidator` reports the first two as errors beside the banlist's, with the date in the words.

**Two sources for "not released here"** (1.1.1, kai: "Trap holic exists in the tcg"). YGOPRODeck's `formats` lag:
Trap Holic was printed in the TCG in Duelist's Advance (DUAD-EN078, 4 July 2025), and a year later the site still lists it
as OCG and Master Duel only — 47 of the 383 cards it calls OCG-only were in Yugipedia's "TCG cards" category on
2026-10-04, most of them Duelist's Advance and 2026 sets. So a region missing from the pool is **not released** only when
Yugipedia agrees (`RegionNames`: it knows the card, in "TCG cards" or "OCG cards", and not in that region's); the agreed
regions are `Card.absentFrom`, laid over the pool in memory (`CardRepository.useRegions`, nothing stored). Where they
disagree, or Yugipedia has not been read, it is **unknown**: never illegal, never vouched for. The two categories are a
device cache beside the lists (`<data>/banlists/regions.json`, about 60 requests a week).

**Genesys** (Konami's points format, from September 2025): no Link or Pendulum monsters, no Forbidden & Limited
list, and the deck's points — main, extra and side, each copy — at most the event's cap (100 unless the store sets
another). `GenesysRules.check(deck, cards, cap)` is in core with tests and the points are shown on `card_info`; a
Genesys switch in the builder waits until the format choice can be stored without an older build misreading it
(`UiPreferences.format` is an enum two releases of the APK read).

## 3. Every banlist, by date (`core/cards/Banlists.kt`)

**The source.** Yugipedia keeps each list as a page — "April 2025 Lists (TCG)", or "April 2005 Lists" before the
regions shared a month — whose `{{Limitation list}}` template gives `start_date` ("April 7, 2025"), `end_date` (empty
while it is in force), `medium` (TCG/OCG), `format` (TCG pages only), `prev`, `next`, and the cards by name, one a line,
under `forbidden`, `limited`, `semi_limited` and `no_longer_on_list` (unlimited now), each line optionally `// prev::Status`
or another `//` note (`force-smw`, `prev-note:: … <ref …/>`). The category "TCG Advanced Format Forbidden & Limited
Lists" (82 pages, 2002–) and "OCG Forbidden & Limited Lists" (88 pages, 1999–, beside five subcategories) list them.
Every page of both, read on 2026-10-04, has the template, a start date and a medium; 2002's have no Forbidden section;
one OCG name is written `Allure of Darkness|sc`. The API answers 50 pages' wikitext in one request
(`prop=revisions&rvprop=content`, the text under `revisions[0]["*"]`).

**The model** (`core/cards/Banlists.kt`).
- `LimitationList(region, title, start, end, statuses: Map<name as written, BanStatus>, prev, next)`; a card not named is
  unlimited, and a card off the list (`no_longer_on_list`) is named, unlimited, so its history shows the step.
- `LimitationParser.read(title, wikitext, region)` → the list or the reason, never an exception: dates as a wiki writes
  them, sections by any spelling, every `//` note, `<ref>`, comment, link and `|annotation` dropped; the region from
  `medium`, else the title, else the category asked; the start from the title ("April 2005" → the 1st) when the page
  gives none. Tested on eleven captured answers, 2002 to 2026, both regions (`BanlistFixture`).
- `BanlistHistory` holds a region's lists sorted by start: `asOf(date)` (the latest that started on or before it),
  `changes(from, to)`, `statusOf(name or card, date)`, `historyOf(name or card)` — stretches at one status, from the
  first list that names it.
- Names are matched to the pool by the pool's own lookup (`TextMatching.normalize`): `LimitationList.match(lookup)`
  is a `BanlistMatch`, a `BanSource` that finds a card by any printing, then by its name, and keeps the names it could
  not match (`unmatched`), said in every answer that lists them. The pool keeps no former names, so a card renamed
  since a list is one of those, never dropped in silence.
- **A cache, not a document:** `<data>/banlists/<tcg|ocg>.json` (`BanlistDoc`, `version` 1, read with unknown keys
  ignored, a broken file read as none, written whole through a temporary file; an `OldDataTest` case). It is not
  synced and not backed up — every device fetches its own — and `banlists` is in `InboundPath.DEVICE_FOLDERS`, so
  neither a sync nor a restore can plant one.
- **Fetched** by `neue/banlist/BanlistCenter` (`NeueHolders.banlists`), off the main thread, one fetch at a time, a
  second between requests, with the app's User-Agent: the category weekly (`BanlistPlan.stale`), then only the pages
  it lacks — a list never changes once it has ended — the one still in force again weekly, and a page that could not
  be read again at each refresh (`BanlistPlan.pages`). A failure is worded by `Unreachable`; the lists kept stay in
  use, and a fetch cut short does not mark the category read, so the next ask carries on.
- Outside text: Ai reads the names inside `Untrusted`, and every answer cites the list's title and Yugipedia (CC BY-SA).

**"As of".** A date anywhere the banlist matters:
- `DeckValidator.validate(…, limits: BanSource?)`: the pool's status by default (`BanSource.current`), or a dated list
  (`BanlistMatch`), named in the words ("… is limited to 1 on the April 2025 Lists (TCG), deck has 2."); copies by card.
- Ai's `banlist` tool (`neue/ai/AiBanlist`): `date` (default today), `region` (default the builder's format), `card` (its
  status that day and its history), `compare_to` (what moved between two days' lists).
- `validate_deck` with `as_of`: that day's list and the cards released by then (`Legality`).
- Ai World's `ygo.banlist(date, region)` (the list, its names by status, `l.status(name)`, the unmatched names and the
  source) and `ygo.legal(deck, date)` (→ `{legal, list, issues}`), through `WorldApi`'s `banlist`/`banStatus`/`legal`
  and `WorldHost.banlists` — read from what is kept, never fetched on a script's thread (`WorldSnapshot.of` starts a
  background refresh when one is due). JavaScript only: Python cannot call back into the app.
- `hand_odds` with `as_of` (and `format`): the deck cut to that day's list first (`CardSetOdds.legalised`: a card's
  allowance counted across the deck by card, the Extra and Side Decks' copies first, the Main Deck's last copies taken
  out), the answer naming the list and what was taken out — "odds as of the March list".
- **The field as of a date** (1.1.1): `ygopro_field_snapshot` and `ygopro_tournament_decks` take `as_of` (a day; the
  region is the lists' `format`, default the builder's), so a past format is read as it was:
  - **The window ends on that day**: lists whose event fell in [as_of − days, as_of]. A list's age is its **event's
    day**, read from the description YGOPRODeck sends with it ("Tournament: … – September 27th 2026",
    `TournamentDecks.eventDate`/`eventDay`, `TournamentDeck.day`), else the site's own "2 months ago" (a month wide).
    The pages are newest first, so `YgoProDeckDecks.recent(…, asOf)` **searches** for the page where the window ends —
    a gallop from a guess made from page 0's span, then halving, at most `MAX_PROBES` (32) requests a tier and never
    past `MAX_PAGE` (2,500), each paced a second apart — and reads `maxPages` from there (from the page before, which
    the search already read). A year back is some twenty requests a tier, not three hundred pages in turn. One old
    event posted late neither starts nor ends the reading (a page counts by its majority, or its last list by its event
    and its posting both).
  - **The honest window holds**: a tier stopped at the page cap still inside the window cuts every tier to the same
    date (`RecentDecks.window`, now counted from `end`, the days before today the window ends), said as "the 5 days to
    4 Oct 2025, not the 30 days to 4 Oct 2025 asked for", with the `as_of` that reads the rest (`earlier`); a tier whose
    lists on YGOPRODeck end before the window is said (`endsWords`).
  - **Legal on that day** (`FieldLegality.asOf`): a list holding a card not out in the region by then, or never
    released there (`Legality.release`: `NotYet`/`NotReleased`), is set aside first and said on its own ("2 lists held
    cards not out in the TCG on 4 Oct 2025 and were left out: …", `unreleasedWords`); the rest are held to the list in
    force that day (`AiBanlist.listOn`, `min(3, statusOf(card).maxCopies)`), dropped and said as before. The answer names
    the list, cites Yugipedia (CC BY-SA), and counts the list's names the pool could not match. `ygopro_tournament_decks`
    checks legality only with `as_of`; without it, it is unchanged. Genesys has no list: its window alone.
  - `expected_winrate` takes no `as_of`: it reads the games logged, not the field.
- Still to come: the builder's own legality against a dated list (a choice in the UI).

## 4. The field, read honestly (`core/ai/meta`, `core/prep/TestStats.kt`)

| Lead | Fix |
|---|---|
| Lists illegal under the current list are counted. | Dropped, and the count dropped said with the cards that dropped them (`FieldLegality.check`/`words`: copies by card across Main, Extra and Side over `limitOf`, today's `DeckEditor.copyLimit` by default — the `as_of` list plugs in there, `FieldLegality.asOf`, §3; a card the pool does not know drops nothing; Genesys lists are not checked, having no list). |
| Each tier is cut at a page cap, so one tier's window is days and another's weeks. | One window for all tiers (`YgoProDeckDecks.recent`, `RecentDecks.window`): where any tier's reading stopped short, every tier is cut to the same date — the day before the newest of the capped tiers' oldest dates, since that day itself may be part-read — and the answer says the window it really covers (`windowWords`, `cutWords`: "the last 11 days, not the last 45 asked for"). |
| Clustering chains hybrids into one strategy (a list joins if it is like **any** member). | Average linkage (`FieldBuilder.build`): a list joins a strategy only if it is like the strategy **as a whole**; two strategies merge only on their average (a merge pass on summed similarities kept up to date). |
| Copies counted by passcode. | By card (§1): `FieldBuilder` takes the pool's lookup (`AS_PRINTED` by default, for callers with none) for similarity, weights, staples and cores; `DeckSearch` names a card once; the builder's copy badge and the inspector's opening odds count every printing. |
| Expected match win drops the mirror and renormalises. | The mirror stays in the field at its share (`TestStats.field`), at the logged mirror rate, else 50 %; games typed against the deck's own name are folded under its id (`TestStats.mirrored`). Prep, `expected_winrate`, Present's practice module and the World's matchups all read it so. |
| Game 1 pooled with sided games. | Game 1 is played at the pre-side rates and games 2 and 3 at the post-side ones, each going first and second (`Row.preFirst/preSecond/postFirst/postSecond`, `matchWin` with four rates; the old two-rate form stays for callers that have only two). A split with no games falls back to its turn's pooled rate, so an old log reads as before; `MatchMath.field` draws the four the same way. |
| `hand_odds` drops names it cannot resolve. | It says which it could not find ("Not found, so not counted"), and refuses if none of a set resolved; an `and_group` it cannot find is refused, not ignored. |
| `hand_odds` is wrong when its two sets overlap. | Counted by `HandCounter` through `core/hand/CardSetOdds`, exact with overlap: a card in both sets counts for each, and the answer says how many are shared. |
| `watch_video` returns a cut report as complete. | It was not carried: a Gemini answer that stopped for any reason but `STOP` now ends with a cut-off note saying the rest is missing and not to treat the decklist or plan as complete (`GeminiVideo.cutNote`). |

## 5. Releases

- **1.1.0** (shipped together, as one release) — §1, §2, §3 and §4: counting by card, release data (schema 4, the pool
  fetched once more), every banlist by date with `banlist`, `validate_deck`/`hand_odds` `as_of` and `ygo.banlist`/`legal`,
  and the field read honestly. Stored-data changes: schema 4 (five columns), `PoolRecord.misc`, a new device-only cache
  `<data>/banlists/`.
- **1.1.1** (shipped together) — everything Phase B had left:
  - the builder's legality against a chosen day's list, or Genesys under a points cap (`DeckRules`, the Issues drawer's
    **Check against**; `NeuePreferences.legalAsOf`/`genesys`/`genesysCap`, synced);
  - the field as of a date (`ygopro_field_snapshot` and `ygopro_tournament_decks` `as_of`, §3; `expected_winrate` needs
    none, reading logged games);
  - the banlist history filling a page's gap between two equal lists, said to be inferred (three in 169);
  - a legality-and-odds regression set in Trust (F1): **Card truth** (`EvalSets.cardTruth`, `card-truth`), 32
  items, each with its source — 14 banlist-by-date questions in both regions from 2004 to 2023 (cards that moved between
  lists among them: Raigeki, Monster Reborn, Harpie's Feather Duster TCG against OCG), 8 release questions (OCG-only,
  a Speed Duel Skill Card, TCG against OCG dates), 5 copy counts by passcode across alternate artworks, and 5 Genesys
  points graded by a new `Grader.Number`. The facts were read on 2026-10-04: Yugipedia's list pages through the app's own
  `LimitationParser`/`BanlistHistory`, and YGOPRODeck's pool (`misc=yes`). `banlist` joins `EVAL_TOOLS`; `validate_deck`
  does not (it checks the person's decks, not a list in the question). Genesys points can change: re-read them when
  Konami does.
  - Stored: three new preferences with defaults; nothing else.

**Needs:** F3's versioned documents (the banlist file), Phase A's runner (the regression set).
