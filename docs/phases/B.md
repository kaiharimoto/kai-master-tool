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

Kept on `Card` as `tcgDate`, `ocgDate`, `formats`, `genesysPoints`, `konamiId`, stored in four new columns
(**schema 4**, `migrations/3.sqm`, held by `MigrationTest`). A pool stored by an older build has none of them; the
app reads that as *unknown*, never as illegal, and refreshes the pool once (`PoolRecord.misc`) so the data arrives.

**Legality.** `Legality.of(card, format, asOf)` says one of:
- **legal**;
- **not released here**: no date in this region and the region not among its formats (an OCG-only card in TCG);
- **not out yet**: its date in this region is after `asOf` ("out 14 Nov 2026");
- **unknown**: the pool has no release data (an old pool, a card the site has not dated) — never an error.

`DeckValidator` reports the first two as errors beside the banlist's, with the date in the words.

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
- Still to come: the builder's own legality against a dated list (a choice in the UI), `hand_odds` with `as_of`, and
  the field, `expected_winrate` and `field_snapshot` with `as_of` (the meta's own work, §4).

## 4. The field, read honestly (`core/ai/meta`, `core/prep/TestStats.kt`)

| Lead | Fix |
|---|---|
| Lists illegal under the current list are counted. | Dropped, and the count dropped said with the cards that dropped them (`FieldLegality.check`/`words`: copies by card across Main, Extra and Side over `limitOf`, today's `DeckEditor.copyLimit` by default — the `as_of` list plugs in there; a card the pool does not know drops nothing; Genesys lists are not checked, having no list). |
| Each tier is cut at a page cap, so one tier's window is days and another's weeks. | One window for all tiers (`YgoProDeckDecks.recent`, `RecentDecks.window`): where any tier's reading stopped short, every tier is cut to the same date — the day before the newest of the capped tiers' oldest dates, since that day itself may be part-read — and the answer says the window it really covers (`windowWords`, `cutWords`: "the last 11 days, not the last 45 asked for"). |
| Clustering chains hybrids into one strategy (a list joins if it is like **any** member). | Average linkage (`FieldBuilder.build`): a list joins a strategy only if it is like the strategy **as a whole**; two strategies merge only on their average (a merge pass on summed similarities kept up to date). |
| Copies counted by passcode. | By card (§1): `FieldBuilder` takes the pool's lookup (`AS_PRINTED` by default, for callers with none) for similarity, weights, staples and cores; `DeckSearch` names a card once; the builder's copy badge and the inspector's opening odds count every printing. |
| Expected match win drops the mirror and renormalises. | The mirror stays in the field at its share (`TestStats.field`), at the logged mirror rate, else 50 %; games typed against the deck's own name are folded under its id (`TestStats.mirrored`). Prep, `expected_winrate`, Present's practice module and the World's matchups all read it so. |
| Game 1 pooled with sided games. | Game 1 is played at the pre-side rates and games 2 and 3 at the post-side ones, each going first and second (`Row.preFirst/preSecond/postFirst/postSecond`, `matchWin` with four rates; the old two-rate form stays for callers that have only two). A split with no games falls back to its turn's pooled rate, so an old log reads as before; `MatchMath.field` draws the four the same way. |
| `hand_odds` drops names it cannot resolve. | It says which it could not find ("Not found, so not counted"), and refuses if none of a set resolved; an `and_group` it cannot find is refused, not ignored. |
| `hand_odds` is wrong when its two sets overlap. | Counted by `HandCounter` through `core/hand/CardSetOdds`, exact with overlap: a card in both sets counts for each, and the answer says how many are shared. |
| `watch_video` returns a cut report as complete. | Already carried by the cut-off notices (1.0.98); checked again here. |

## 5. Releases

- **1.1.0** — §1, §2, the `hand_odds` leads, and §4. Stored-data changes: schema 4 (four columns), the pool refreshed once.
- **1.1.1** — §3, banlist history and "as of" everywhere.
- **1.1.2** — what the first two leave, and a legality-and-odds regression set in Trust (F1).

**Needs:** F3's versioned documents (the banlist file), Phase A's runner (the regression set).
