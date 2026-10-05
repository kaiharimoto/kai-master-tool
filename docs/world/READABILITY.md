# Ai World: reading at a glance (1.1.x)

kai, on the World desktop: "I'm having readability issues with the World interfaces. The tabs names are all truncated so
it's hard to tell which tab is which, so we need to also integrate a visually intuitive solution to tell which tab is
which. Next, we need to set visual guidelines and rules on text sizes and layouts to ensure that items are easy to read
at a glance." And: "Allow Ai world to also integrate card images where possible/needed for maximum visual pickup."

These are the rules every window in `neue/world/` keeps — the desk, the taskbar, the Browser, Thoughts, the Library,
Instruments, the apps Ai makes and the phone. Where a rule can be checked by a machine it is, and the test is named.
They sit inside Master UI (`kit/MASTER-UI.md`): paper and ink, Inter and JetBrains Mono, square, flat.

## 1. One type scale

Six tiers and one for chart data, each a size, a weight and a line height, a step larger on a phone. They are built on
Master UI's own tokens (`MuType`) in `neue/world/type/WorldType.kt`, which also gives the World its own `Body`,
`Heading`, `Small`, `Help`, `Mono`, `Micro`, `MicroLink` and `MonoLink`.

| Tier | Desk | Phone | Weight / leading | For |
|---|---|---|---|---|
| title | 20 | 20 | 500 / 1.15 | a page's or a plate's title |
| heading | 15 | 16 | 500 / 1.3 | a section of an app, a group of rows |
| **body** | **13** | **14** | 400 / 1.45 | prose, a row's words, a tab's title, a table's cells, a field's value |
| label | 12 | 13 | 400 / 1.35 | the line under a row: where it came from, a hint, a count |
| micro | 11 | 11 | 500, caps +0.08 em | a kind, a column's head, a section's name — never a sentence, never a file's name |
| mono | 12 | 13 | 400 / 1.35 | code, addresses, file names, numbers in data |
| data | 11 | 11 | mono | a chart's ticks and value labels, beside the marks they name |

The Editor's code is 13 / 22 (14 on a phone) and the Terminal's 12 / 20 (13): code is read closely.

**Floors.** Body text a person reads is at least 13 on the desk and 14 on a phone. Nothing is set under 11. A heatmap
value that would need less is left to its shade and the scale beside it.

*Held by* `WorldReadabilityTest`: no raw `.sp` in `neue/world/` outside `WorldType.kt` (the icon painter's monogram,
sized by the icon grid, is the one exception); no kit text helper below the scale imported there; no `MuType.small`,
`help`, `mono`, `micro`, `row` or `body` reached for directly; and the scale's own sizes keep the floors.

## 2. Measure

- Prose is set 45–75 characters to the line: **72** (`MEASURE_CHARS`), measured in the body tier from a line of ordinary
  prose (`readingMeasure()`), so it follows the face, the size and the phone instead of a guess at a character's width.
- A window wider than that centres its reading column (Thoughts maximised on a 1920 desk reads as a page, not a banner);
  the Library's document column is the same measure.
- Tables, code and charts may go wider: they are scanned, not read.

## 3. Hierarchy and a focal point

- One window is in front (DESKTOP.md §1); inside it, one thing is the answer — the page's title and its board, an app's
  stat, the line the Editor is on. Everything else is quieter by tier and ink, not by shrinking under the floor.
- At most one primary action in a window (`UiTree` makes a second primary subtle).
- A kind's micro caps sit over the title they name; the source line (`from openings.js · ran 18:20`) sits under it in
  the label tier with the file's name as itself, in mono — never in capitals.
- A notice with words beside its kind shows the kind in micro caps and the words in the body tier; a notice that is only
  a title ("6 new pages") says it in the body tier.

## 4. Titles are never cut to a stub

kai's complaint was a strip of tabs reading `"”…` and `St…`.

- **A title keeps at least 12 visible characters** (`TabTitles.FLOOR`). Where it would not, the layout gives it room —
  tabs are never narrower than `TabStrip.MIN_W` (176 dp: number, glyph, card, twelve characters and ✕) — or wraps to a
  second line (the overview, a chart's or table's names), or lists it (the tab count).
- **A cut keeps the distinctive words** (`TabTitles.short`): the quotes round a title go, then the little words and the
  dashes that join a title's parts, then the words most of its neighbours share (a deck's name every page of a study
  carries), and only then is it cut at a word's end with `…`. "What each card is worth to “Starters ≥ 1”" reads
  "Card worth Starters ≥ 1"; "Opening hands — lab openings" among its siblings reads "Opening hands".
- **The full title is always reachable**: a tab's tip, its preview, the overview and the phone's list give it whole,
  with where it came from.
- Conditions read as words, not code (`Goals.words`): `"Starters">=1` is "Starters ≥ 1" on every page and tab.

*Held by* `TabTitlesTest`, `TabStripTest` and `GoalWordsTest` (core).

## 5. Ink and contrast

Neue's ramp is darker than the kit's: ink-70 is 80 % and ink-45 is 66 % (62 % in ink), both over 7 : 1 on their page.
Still:

- **ink** for what the person must read first: titles, body, values, a selected tab.
- **ink-70** for what is read second: labels, sources, unselected tabs, legends.
- **ink-45** only for meta — a placeholder, a line number, a time beside its row, a tab's number — never the words of a
  reading tier.
- **ink-25 and ink-12** never colour words: they are rules, separators and disabled marks.

*Held by* `WorldReadabilityTest.faintInkNeverColoursText` and `faintInkNeverSetsWordsToRead`. Both themes are photographed
for every change (`--theme=ink`).

## 6. Spacing, targets and density

- Spacing comes from the kit's scale (4, 6, 8, 10, 12, 16, 24 dp); a row is at least 32 dp on the desk and 44 dp to a
  finger (`TouchMetrics`); a link that acts is 32 dp to a finger.
- A chart's key stands over it, read before the marks; its values share their places (`ChatChart.labels`: `38.1%` over
  `31.0%`, never over `31%`); its labels are the body tier, up to two lines.
- Colour stays content (DESKTOP.md §1, `WorldPaint.kt`): a chart's series and a web's groups, each with its name in ink.

## 7. Cards are pictures

A player knows a card by its art before they read its name. Wherever World names a card and says so, the card's art is
drawn beside the name — through the app's own `NeueCard` (via `ChatCard`/`CardChip`), so the person's chosen artworks
apply and a small card decodes the small render at the size drawn:

- **Pages**: a chart whose labels are cards (`cards: true`) shows each card's art in its label column; a table's card
  columns (`cards: ['Card']`) show it in each cell; a web's card nodes are cards; cards, line and board pages are art.
- **Tabs**: the card a page is about (`PageLead`: a chart's largest bar, a table's first card, a web's hub, the first
  `[[Card]]`) stands beside the kind's glyph on its tab, its preview and in the overview.
- **Ai's apps**: `ui.card(nameOrPasscode, {label, size})`, `ui.cards`, a table's `cards` columns, and the card picker's
  chosen card.
- **Words**: `[[Card]]` in Thoughts, the Library and a page draws a small card in the line before the name
  (`LocalCardChips`, World only).
- A cell is a card only when its board or app says so — never guessed from the words. A thumbnail never pushes a name
  below the floor: the column grows by the chip's width.

*Held by* `CardFieldsTest` (the `cards` flag on a chart and a table, `ui.card`, a table's card columns, round trips),
`PageLeadTest` and `ExampleAppsTest`.

## 8. Tabs anyone can tell apart

Each tab shows its number (`Ctrl 1`–`Ctrl 9`, the last for 9, while the Browser is in front), its kind's glyph, the
art of its card, and its short title; Ai's mark is a 6 dp square on the glyph's corner and never takes the title's
room, and only Ai's tabs wear it. Pages one run made stand together (`Tab.group`), a darker rule where another's begin.
A pointer resting on a tab (a held finger) shows the page itself, small, with its whole title and source; the count at
the strip's end opens every tab as pictures — searched, closed and reordered there. Pictures are the pages' own
painters laid out once and drawn through one layer: drawn again only when the page changes, never by a frame loop.
