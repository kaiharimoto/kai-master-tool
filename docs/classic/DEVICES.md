> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

# Every device, not just the tablet

This app was built against one Samsung Tab S11 in landscape and it shows. The
first phone it met loaded perfectly and could not be played: the top bar threw
half its controls off the right-hand edge and nothing in the repository could
see it happen.

This is the contract that stops that being a recurring surprise.

---

## 1. Look at all of them

```
tools/devices.sh                     # the whole matrix
tools/devices.sh desk-night-seated   # one shot, every device
```

Seven geometries, in **physical pixels at real densities**, because the dp box
is what the layout solver responds to and it is the product of the two:

| | px | density | dp box |
|---|---|---|---|
| `tab-s11` | 2960×1848 | 2.0 | 1480×924 |
| `tab-a9` | 1920×1200 | 1.5 | 1280×800 |
| `fold-open` | 2208×1768 | 2.2 | 1004×804 |
| `pixel8` | 2400×1080 | 2.625 | 914×411 |
| `s24` | 2340×1080 | 3.0 | 780×360 |
| `phone-small` | 1920×1080 | 3.0 | 640×360 |
| `desktop` | 1600×1000 | 1.0 | 1600×1000 |
| `s24-portrait` | 1080×2340 | 3.0 | 360×780 |
| `pixel8-portrait` | 1080×2400 | 2.625 | 411×914 |
| `small-portrait` | 1080×1920 | 3.0 | 360×640 |
| `tab-s11-portrait` | 1848×2960 | 2.0 | 924×1480 |

The last four are new, and they are the ones §6 is about. Anything after the
shot name is passed through, so `tools/devices.sh flat --screen=builder` shoots
the deck builder at all eleven.

**640×360 is the floor.** Below that the app may say it does not fit; above it
the app must be *playable*, which is a stronger claim than *renderable* and is
the distinction this whole document exists for.

Note the shape of the problem: the tablet is 1480dp wide and the narrowest phone
is 640dp. That is a **2.3× range in width and a 2.6× range in height**, and
nothing designed against one end of it survives the other by accident.

## 2. The rules a layout has to keep

**Chrome may never hide a control.** Not off an edge, not under another element.
If it does not fit, it scrolls, wraps, or collapses into something that opens —
but it is always reachable. `PlayTopBar` is the worked example: above
`BAR_FITS_AT` it is the arrangement designed for the tablet, below it the row
scrolls.

**A threshold in the middle of a layout is measured, not chosen.** Find the
width where it actually breaks by shooting it, then round away from the failure.

**Anything compared against a size is in dp, never pixels.** A floor in pixels
is a floor that moves with density: `BoardLayouter.MIN_CARD_WIDTH` is 28
*pixels*, which is 14dp on the tablet and 9dp on a 3× phone — so the "too small
to read" guard fires three times later on the device that needs it most. See §4.

**Chrome that is a fixed dp is a fixed dp that grows as a fraction.** `TOP_BAR`
at 44dp is 4.8% of the tablet's height and 12.2% of a phone's. Fine for a bar;
not fine for anything that stacks.

**A material's scale is a property of the object, not of the viewport.** See §4.

## 3. What the four gates become

`docs/classic/LOOP.md` §4 gains a fifth, and it is cheap now:

> **It survives the matrix.** `tools/devices.sh`, and look at `phone-small` and
> `tab-s11` at minimum. A change that is only checked at one size is a change
> that has only been checked on kai's desk.

Two shots is usually enough — the narrowest and the widest — because almost
every layout failure is monotonic in width.

## 4. The bars, swept

Three had the same defect — a `Row`, a `weight(1f)` spacer, and more content
than a phone is wide — and all three rendered perfectly while hiding controls:

| | controls lost below its threshold | fits at |
|---|---|---|
| Deck builder header | Save, Undo, Redo, search, stats, odds, Table, Play, Library, menu | 1400dp |
| Play stage bar | phase, turn, Draw, Shuffle, Undo, Redo, New hand | 1180dp |
| Duel table bar | Draw, Shuffle, Undo, Redo, New hand | 1150dp |

`components/OverflowBar.kt` is the fix as a component, so a fourth bar inherits
it. Its `Gap()` is the important part: calling `Modifier.weight` by hand throws
the moment the bar is narrow enough to scroll, which is the only case anyone was
trying to fix.

**Still hand-rolled:** the builder header and the play bar predate the component
and carry their own copies of it. They work and are shipped; converting them is
tidy-up, and worth doing before a fourth bar copies the wrong one.

**And the builder header now has a sibling** — `TallTopBar`, one line, for the
portrait window (§6). It is a different bar rather than a scrolling copy of the
1400dp one, because a horizontally scrolling *primary* header is a header whose
controls you have to go looking for. What keeps the two honest is that their
overflow menu is one piece of code (`BuilderMenu.kt`): a setting cannot exist on
the tablet and not on the phone, which is the §4 failure in its other form.

The other four weighted spacers are clear, and each was checked rather than
assumed:

- `CardInspector` — two chevrons around "N of M". Bounded by construction; it
  cannot overflow at any width. Cleared by reading it.
- `DeckLibraryScreen` — shot at 640dp. Back arrow, title and the saved count,
  with room to spare.
- `SearchPane` — the Filters button and the match count, intact in the builder
  shot at 780dp.
- `DeckPanes` section headers — the count, the stepper and the menu, intact in
  the same shot.

`--screen=` reaches `play`, `builder`, `table` and `library` directly, which is
what made checking three of those four cost one shot each.

## 5. Known, not yet fixed

Written down rather than fixed because each is a judgement call kai should have
a say in, and two of them trade against each other.

- **`MIN_CARD_WIDTH` is in pixels.** Making it honest — say 40dp — would be
  correct and would *also* make the play stage refuse to open on a 640dp phone,
  where the card currently lands at about 40dp. So the right fix is not the
  guard; it is giving the board more of the screen on narrow devices. Changing
  the constant alone would trade a small board for no board.
- **The board leaves the screen half empty on a phone.** The solver takes
  `min(byWidth, byHeight)`, and on a 360dp-tall landscape phone height wins hard
  — so the cards shrink to fit three rows plus a hand, and the width goes
  unused. A phone probably wants a different *arrangement* rather than the same
  one scaled down.
- **Material scales are tied to `cardWidth`.** `WoodGrain`'s ring pitch is
  `cardWidth / 0.8`, so the desk's planks are physically narrower on a phone
  than on a tablet — the same desk made of different timber depending on what
  you hold. Grain belongs to the desk; it should be tied to the desk's own size.
- **No quality tiers.** A full-screen per-pixel wood shader at 3× density on a
  midrange phone GPU is untested and is the most likely performance cliff in the
  app. `AAA.md` #98.
- **The play stage in portrait.** The manifest no longer refuses it (§6), so
  the stage can now be turned upright, where `min(byWidth, byHeight)` gives it a
  360dp-wide board with most of the height unused — the bullet two above this
  one, in its other orientation. The builder is the screen a phone is for and
  the stage is landscape by preference, not by lock; a portrait arrangement for
  the stage is its own piece of work.

## 6. Portrait, and the second builder

The app was `android:screenOrientation="userLandscape"` for its whole life,
which is what "built against one tablet" looks like as a policy. kai asked to
use it on a phone; the layout that came back was landscape-on-a-phone, which is
the worst of both — a 780×360 window where the deck gets a third of the height
and the pool a third of the width.

So there are two builders now, and one rule picks between them.

**`Posture.of(width, height)`: taller than wide is `TALL`, everything else is
`WIDE`.** No dp threshold, deliberately. A threshold has to be chosen and
re-chosen for every new dp box and gets foldables wrong in both directions; the
aspect ratio is what the arrangement actually turns on, because the two layouts
differ in what they put *beside* what. A portrait tablet gets `TALL` too, and
that is correct rather than incidental.

| | `WIDE` (unchanged) | `TALL` |
|---|---|---|
| pool | pane down the left | docked along the bottom, three stops |
| search field | top of the pane | bottom of the dock, above the keyboard |
| deck | right, fitted, all three panes visible | above, one row width, scrolls |
| deck sizing | `DeckFitter.plan` — solve width for the height | `DeckFitter.stack` — width is given, scroll |
| row width | 10 / 15 / 15, per section | one number for all three, default 6 |
| header | 1400dp of controls, scrolls below that | one line, everything else in the overflow |

Three things are worth knowing before touching it:

- **The two postures share a device, so they may not share their numbers.** A
  row width written while a tablet was held upright must not still be there when
  it is turned back on its side. `tallColumns` and `tallSearchColumns` are
  separate preferences for exactly that reason, and `DeckLayoutState.setColumns`
  takes the posture rather than inferring it.
- **The pool's furniture is declared to the thing that positions it**, the same
  contract `DeckFitter` has with `chromeFor`. `PoolDock`'s `PEEK` stop *is* the
  dock's chrome height, so a bar added to the dock and not added to
  `DOCK_CHROME` is a search field pushed off the bottom of the screen.
- **`imePadding` goes outside the height, not inside it.** Inside, the keyboard
  eats the dock's own content, and focusing the *deck name* field while the pool
  is at `PEEK` squeezes the search field to nothing. Outside, the padding lifts
  the dock and the height is coerced into what is left.

What is **not** done: the play stage in portrait, above.
