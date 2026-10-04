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

**The source.** Yugipedia keeps each list as a page — "April 2025 Lists (TCG)" — whose `{{Limitation list}}` template
gives `start_date`, `end_date`, `medium`, `format` and the cards by name under `forbidden`, `limited`, `semi-limited`
and `unlimited` (the last only for changes), each line optionally `// prev::Status`. The category
"TCG Advanced Format Forbidden & Limited Lists" (and its OCG twin) lists the pages.

**The model.**
- `LimitationList(region, start, end, statuses: Map<name, BanStatus>)`; a card not named is unlimited.
- `BanlistHistory` holds a region's lists sorted by start; `asOf(date)` is the one in force that day.
- Names are matched to cards against the pool (`TextMatching.normalize`, then the card's former names where the
  pool knows them); unmatched names are kept and reported, never dropped in silence.
- Read by a parser tested on captured wikitext; fetched once a week and kept in `<data>/banlists/<region>.json`
  (synced and backed up, versioned, an `OldDataTest` case). Outside text, so it goes through `Untrusted` where Ai reads it.

**"As of".** A date anywhere the banlist matters:
- the builder's legality (`DeckValidator` takes a `BanSource`: today's from the pool, or a dated list);
- `hand_odds` with `as_of`: copies over that list's limits are taken out first, and the answer says which;
- the field, `expected_winrate` and `field_snapshot` with `as_of`: lists illegal under that list are dropped;
- Ai World's `ygo.banlist(date)` and `ygo.legal(deck, date)`;
- Ai's `banlist` tool: the list on a date, a card's history of statuses, what changed between two lists.

## 4. The field, read honestly (`core/ai/meta`, `core/prep/TestStats.kt`)

| Lead | Fix |
|---|---|
| Lists illegal under the current list are counted. | Dropped (or under the `as_of` list), and the count dropped said. |
| Each tier is cut at a page cap, so one tier's window is days and another's weeks. | One window for all tiers: where any tier's reading stopped short, every tier is cut to the same date, and the answer says the window it really covers. |
| Clustering chains hybrids into one strategy (a list joins if it is like **any** member). | Average linkage: a list joins a strategy only if it is like the strategy **as a whole**; two strategies merge only on their average. |
| Copies counted by passcode. | By card (§1). |
| Expected match win drops the mirror and renormalises. | The mirror stays in the field at its share, at the logged mirror rate, else 50 %. |
| Game 1 pooled with sided games. | Game 1 is played at the pre-side rates and games 2 and 3 at the post-side ones, each going first and second (`matchWin` with four rates; the old two-rate form stays for callers that have only two). |
| `hand_odds` drops names it cannot resolve. | It says which it could not find, and refuses if none resolved. |
| `hand_odds` is wrong when its two sets overlap. | Counted by `HandCounter`, exact with overlap. |
| `watch_video` returns a cut report as complete. | Already carried by the cut-off notices (1.0.98); checked again here. |

## 5. Releases

- **1.1.0** — §1, §2, the `hand_odds` leads, and §4. Stored-data changes: schema 4 (four columns), the pool refreshed once.
- **1.1.1** — §3, banlist history and "as of" everywhere.
- **1.1.2** — what the first two leave, and a legality-and-odds regression set in Trust (F1).

**Needs:** F3's versioned documents (the banlist file), Phase A's runner (the regression set).
