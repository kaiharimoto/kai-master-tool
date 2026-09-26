# Neue Master Tool

The deck builder for a desk: mouse, keyboard, a large display, and the
**Master UI** design language (`github.com/kaiharimoto/Master-UI`,
`kit/MASTER-UI.md`) — paper and ink, zero radius, no shadows, Inter, numbered
pages, a quiet voice.

It is a **separate application** from the tablet app and from the older
`:desktopApp`. It installs on its own, keeps its own data, updates itself from
its own release track, and shares only the rules: `:core` (every deck rule,
fitter, lens and odds calculation) and `:ui`'s state holders
(`DeckBuilderState`, whose behaviour is the tablet's by construction). None of
its look comes from `:ui`.

![The builder, paper](neue/neue-builder-paper.png)
![The builder, ink, at 2560 × 1440](neue/neue-builder-ink.png)

Play mode is not in it. kai will rebuild play from scratch later; Neue is the
builder, its odds and its statistics.

---

## 1. Where it lives

| | |
|---|---|
| `app/neue/` | the module: UI and `main`, JVM only |
| `app/neue/VERSION` | the version a local build carries; releases pass `-Pneue.versionName` |
| `app/neue/icons/` | installer icons, drawn by `tools/neue/mark.py` |
| `core/input/DeskShortcuts.kt` | the keyboard, as data |
| `core/prefs/NeuePreferences.kt` | its settings document (`"neue.ui"`) |
| `core/update/NeueReleaseTrack.kt` | how it finds its own releases |
| `.github/workflows/release-neue.yml` | the release |
| `studio/.../NeueStudio.kt` | headless screenshots: `tools/shoot.sh --neue` |

```
cd app && ./gradlew :neue:run                  # run it (needs the Android SDK, like :ui)
./gradlew :neue:jvmTest                         # the Master UI law test and the key map
tools/shoot.sh --neue --page=builder --theme=ink --width=2560 --height=1440 --name=b
```

---

## 2. Master UI, and the two exceptions

`neue/theme/` is `MASTER-UI.md` §2–§7 translated into Compose: `MuColors` is
paper, ink and the ink alpha ramp; Ink is `MuColors.of(ink = true)`, the exact
inversion; `MuType` is the 96/56/32/20/14/12/11 scale in Inter (bundled, OFL)
with JetBrains Mono for numbers; `MuMotion` is one easing and three durations.
`neue/kit/` is the component set — buttons, underline inputs, selects,
segmented controls, tabs, switches, sliders, meters, the hatch, the breathing
square, tooltips, menus, dialogs, drawers, toasts — built on foundation
primitives. **Material is not used**, not restyled: it is not imported.

**`MasterUiLawTest` is the kit's `check.mjs` for Kotlin** and runs in CI: a
radius, a shadow, a gradient, a colour literal, a Material import, weight 600,
a spring or an exclamation mark anywhere in `neue/` fails the build.

kai granted two exceptions, and the law test names the two files they live in:

- **`cards/Foil.kt`** — the prismatic border on a card's face. It is kept, as
  kai asked, and it is content rather than chrome (§17: the pixels of a
  picture keep their colour). The choice between the Blender mockups
  (`tools/foil/`) replaces the classic band here.
- **`cards/GroupMarkers.kt`** — *"colors are allowed for deckbuilding markers
  only (like card groups in the deck itself assigned by the user)"*. A group's
  hue shows in the deck's cracks, on its key, on its mark. Lenses that are
  facts rather than the user's own drawing — type, copies, legality — are told
  apart by ink weight, the way everything else in Master UI is.

Card art keeps its colour inside a §17 content frame. Nothing else does.

---

## 3. The window

A 40 px title bar (mark, wordmark, update pill, `Search Ctrl K`, status), the
232 px index rail — `01 Decks · 02 Builder · 03 Odds · 04 Stats`, Settings
below the rule — and the page.

**02 Builder** is three columns and a footer. The pool (search, inline
filters, a ruled grid of cards) and the inspector are resizable and hideable;
the deck between them is **fitted, never scrolled**, by the tablet's own
`DeckFitter.plan`: row widths in (10 main, 15 extra and side), one card size
out. On a large display the fitter simply hands back larger cards. The footer
carries the one strong rule, the counts, legality, and the one primary action.

## 4. Mouse and keyboard

| Mouse | |
|---|---|
| hover a card | the inspector shows it |
| click | select (the inspector keeps it; `Delete` acts on it) |
| double-click, pool | add to the main or extra deck |
| Shift double-click, pool | add to the side deck |
| double-click, deck | remove that copy |
| right-click | everything else: move, group, copy name, remove all |
| drag | anywhere, with the tablet's drop rules (`GridDropResolver`, `DeckBuilderState`) |

Presses select immediately and a second press within 350 ms is the
double-click — no `combinedClickable`, which delays every single click.
Dragging starts past the slop, with no hold: a mouse scrolls with its wheel,
so the tablet's 120 ms settle has nothing to disambiguate.

The keyboard is `DeskShortcuts`, one table resolved in one place, rendered by
the help dialog (`F1`) and reachable by name from the palette (`Ctrl K`).
`DeskShortcutsTest` holds every action bound and no chord meaning two things at
once; `DeskKeysTest` holds every key in the table pressable. The pool keys
(`↑ ↓ Enter`, `Shift Enter` for the side) are live *while typing in the search
field* and nowhere else, so confirming a deck name never adds a card.

---

## 5. Releases, updates and feedback — the permanent numbers

`release-neue.yml`, dispatched with a version, builds a `.msi` (Windows), a
`.dmg` (macOS) and a `.deb` (Linux) and publishes them as
`neue-master-tool-<version>.<ext>` under the tag **`neue-v<version>`**.

- **Every Neue release is a pre-release, and its tag is never `v*`.**
  `/releases/latest`, which every installed APK asks, returns the newest
  non-pre-release of any tag; a Neue release that was not a pre-release would
  hide the next APK from every tablet. The 3DS track learned this first
  (`release-3ds.yml`). Neue lists releases and filters by its prefix
  (`NeueReleaseTrack`), so the flag costs it nothing.
- **`windows.upgradeUuid` is permanent** (`app/neue/build.gradle.kts`). An MSI
  with a different one installs beside the old app rather than over it.
- **The version only goes up**, and is three numbers with major and minor
  under 256 — an MSI constraint. The workflow refuses anything else.
- The MSI is **per-user**, so an update needs no administrator. On Windows the
  app downloads the installer, starts a small script that runs `msiexec
  /passive` and reopens the new build, and quits. On macOS it opens the
  `.dmg`; on Linux it hands the `.deb` to the package installer.
- The installers are **not code-signed**. Windows SmartScreen and macOS
  Gatekeeper will warn once; the release notes say how to get past it.

A crash is written to `<data>/crash.txt` and shown on the next launch in a
window with no theme (so it renders when the theme was what broke), with
`Copy` and `Report →`. **Report an issue** (Settings and the palette) opens a
GitHub issue with the version and the system filled in.

Data lives in `%APPDATA%\NeueMasterTool`, `~/Library/Application
Support/NeueMasterTool` or `$XDG_DATA_HOME/neue-master-tool` — its own SQLite
database (schema version 3, the same migrations), its own card-art cache. Decks
move between Neue and the other builds by `.ydk`/`.ydkx`.

## 6. Shipping a change to Neue

The same discipline as the APK, on its own track: push to the `claude/**`
branch, wait for `build-app.yml` (its `neue` job runs the law test and
packages the `.deb`), fast-forward `main`, dispatch `release-neue.yml` on
`main` with the next patch, and confirm the tag and **all three** installers
are on the release before calling it shipped. A change that touches only
`neue/` does not need an APK release; one that changes `:core` or `:ui`
behaviour does, as always.
