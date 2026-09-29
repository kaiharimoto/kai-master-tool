# CLAUDE.md

This file guides Claude Code (claude.ai/code) when working with this repository.

## Quick Start

**What this is:** kai's master tool — a Yu-Gi-Oh! deck building and tournament
preparation tool. **The app is Neue Master Tool** (`app/neue/`), drawn in Master
UI, shipping on Windows, macOS and Linux and — from v1.3.0 — as the Android APK
on landscape tablets, where it replaced the old tablet app in place, and from
v1.3.5 on phones, upright or lying down. A proper
Mac app is the port's next phase. **`docs/NEUE.md` is the
authority on it; read it before changing anything in `app/neue/`.**

Kotlin Multiplatform + Compose Multiplatform. `app/README.md` has the modules.

**Key locations:**
- `app/core/` — pure Kotlin logic (models, deck editing, groups, hand odds,
  search and filters, layout solving, the keyboard and mouse tables, motion,
  and the classic play stage's geometry), all tested in commonTest
- `app/builder/` — the builder's state and plumbing that is not a look
  (`DeckBuilderState`, `AppDependencies`, updater seam, image loader, shader
  seam, card foil); files keep their `com.kaiharimoto.mastertool.ui.*` packages
- `app/neue/` — **Neue Master Tool**: every screen
- `app/androidApp/` — the APK: one activity hosting Neue, the theme-free crash
  reporter, and `NeueSmokeTest` for the emulator
- `app/studio/` — the headless renderer; `tools/shoot.sh`
- `docs/NEUE.md` (the app), `docs/classic/` (the tablet app and play stage),
  `docs/PORT.md` (the 3DS)
- `legacy/` — the archived original HTML tool; `3ds/` — the New 3DS rewrite
- `ydk/`, `lab.ydkx` — sample deck files (YDKX = YDK + `#ydkx-extended` JSON)

## Development Workflow — read this before changing anything

1. **All logic goes in `:core` with commonTest tests.** Run locally:
   `cd app && ./gradlew :core:jvmTest`.
2. **Everything else needs Google Maven**, which `settings.gradle.kts` detects
   (an Android SDK present) and otherwise skips. Where it is reachable — this
   environment has had it — `:builder`, `:neue`, `:androidApp` and `:studio`
   compile locally: `./gradlew -Pmastertool.android=true :neue:jvmTest`, and
   `:androidApp:assembleDebug`. CI (`.github/workflows/build-app.yml`, on every
   push to `main` or `claude/**`) is the real compile check either way. **Never
   trust a piped local exit code** — grep the Gradle output for `BUILD
   SUCCESSFUL`, and the test results for `<failure`.
3. **Every gesture ships with its idioms**: Neue's keyboard is data in
   `core/input/DeskShortcuts.kt`, its mouse in `core/input/DeskMouse.kt`, and a
   finger's in `core/input/DeskTouch.kt`, held to the mouse's by a test. The palette and the help
   dialog render the tables, so they can never drift.
4. **Finish by shipping it.** See *Ship Every Change* below — kai judges the app
   on their own machines, so work that is only on a branch is work they cannot
   see.

## Seeing Neue without a window

`:studio` draws Neue to a PNG headlessly — real theme, real dependencies, real
card art, a frame clock advanced by hand. Off by default and in nothing shipped:

```
tools/shoot.sh --page=builder --groups=true --name=x   # shots/x.png
tools/shoot.sh --page=builder --theme=ink
tools/shoot.sh --page=decks --save=true --default=true
tools/compare.py shots/before shots/after                     # what moved
```

Flags drive the real state: `--groups`, `--lens=`, `--zoom=`, `--zen=deep`,
`--zen-groups`, `--mouse=left@x,y;right@…` (real presses through the real
handlers, logging the deck's counts), `--hovers=`, `--art=`, `--history`,
`--list=N`, `--filters`, `--effect=`, `--studio=deck|list`, `--nopool`,
`--noinspector`, `--save`, `--covers=`. `--palette` is the command palette;
the groups' palette is `--group-palette=`. Each run takes about four minutes —
run it in the background. `shots/` is gitignored scratch; `docs/shots/neue-*.png`
are the README's pictures, re-shot by dispatching `.github/workflows/shots.yml`
**on the `claude/**` branch, before the fast-forward, never on `main`** — a
commit CI puts on `main` is one the next fast-forward is rejected by. It refuses
a page with too few colours, which is what a pool that did not sync looks like.
The studio cannot draw Android; `android-smoke.yml`'s emulator screenshot is
the tablet's picture.

## Ship Every Change — standing instruction from the user

The machine it runs on is the only place this app can really be judged. So the
default end of a piece of work is a release, not a branch. Standing permission
is granted for all of it — do not stop to ask.

There are two tracks, and a change ships on whichever it reaches:

- **The desktop** (`app/neue/`, and `:core`/`:builder` changes it uses):
  `release-neue.yml`, tagged `neue-v*`, always a pre-release, with a `.msi`, two
  `.dmg`s (`-arm64`, `-x64`) and a `.deb`.
- **The APK** (`app/androidApp/`, and — since v1.3.0 — everything in `neue/`'s
  `sharedMain` and `androidMain`, `:builder` and `:core`): `release.yml`, tagged
  `v*`. A change to Neue that the tablet would see ships on **both** tracks; a
  change only to `neue/src/jvmMain` or the desktop packaging needs no APK.

Every time, in this order:

1. Push the work to the `claude/**` branch and wait for `build-app.yml` to go
   green on every job. A red build is the one thing that stops the rest.
2. Fast-forward `main` onto the branch — push the *commit CI passed*
   (`git push origin <sha>:main`), not whatever the working tree has grown since.
   Releases build from `main`.
3. Dispatch the release on `main` with the **next patch** of that track (the
   newest `neue-v*` tag, or `get_latest_release` for the APK). Bump the minor
   only when the user asks, or when the patch would reach 100.
4. Confirm the release actually published — the tag exists and every asset is
   attached (four installers, or `kai-master-tool-<version>.apk`) — before
   telling the user it is ready. "Dispatched" is not "shipped".

Note in the release notes when a build changes stored preferences, the schema,
or the deck-file payload. Say out loud when a change needs no release (docs,
tests, this file; a desktop-only change needs no APK), and remember that a version
number, once published, is spent forever.

## Release Contract — numbers shipped to devices are permanent

`.github/workflows/release.yml` (manual dispatch with a `version` input, or a
`v*` tag) builds the signed APK and publishes the GitHub release that the
in-app updater installs from. Two hard-learned rules:

- **versionCode** is derived from the version name
  (`100000 + major*10000 + minor*100 + patch`). It must only ever go up;
  v1.1.0 shipped as 281 from a commit-count scheme, which is why the floor
  exists. Never revert to commit counts.
- **SQLite schema version is 3** and can never decrease — devices that
  installed v1.1.0 are stamped at `user_version 3`
  (see `core/.../db/migrations/2.sqm`). SQLDelight derives the version from
  the number of `.sqm` files: adding a table means adding a `.sq` change AND a
  new `.sqm`, and `MigrationTest` must prove upgrade == fresh create. Never
  renumber or delete migration files once a signed build has shipped.
- The APK must be signed with the committed key
  (`androidApp/keystore/kai-master-tool.jks`); the workflow hard-gates on its
  SHA-256.
- Android crashes surface through the built-in crash reporter
  (`MainActivity`): the trace persists and is shown, shareable, on next
  launch. Keep that screen theme-free — it must render when the theme cannot.
- **The APK is Neue, in place.** kai chose it: **v1.3.0** (versionCode 110300,
  above the 110263 of v1.2.63) is the first APK built from Neue, under the same
  `com.kaiharimoto.mastertool`, signed with the same key, reading the same
  `kai_master_tool.db` — so installed tablets update onto it and keep their
  decks. Its settings are Neue's `neue.ui` document; the classic
  `UiPreferences` row stays in the database, unread. The APK updates on the `v*`
  track (`/releases/latest`), never the `neue-v*` one.
- **Neue's two permanent numbers** are `windows.upgradeUuid` in
  `app/neue/build.gradle.kts` and the rule that its version only rises. Neue
  releases are always pre-releases: `/releases/latest` is what every APK reads.

## Neue Master Tool — the app

`app/neue/` is **Neue Master Tool**, the deck builder for a mouse, a keyboard
and a large display — and for a finger on a tablet — drawn in **Master UI** (`kaiharimoto/Master-UI`,
`kit/MASTER-UI.md`): paper and ink, zero radius, no shadows, Inter, `01`
numerals. **`docs/NEUE.md` is the authority.** The short version:

- **It is built on `:core` and `:builder` and nothing else.** `:builder` is the
  slice of the old `:ui` that is not a look — `DeckBuilderState`,
  `AppDependencies`, the updater seam, `configureImageLoader`, the shader seam,
  the card foil — moved with its `ui.*` packages intact. No Material, no
  `MasterToolPalette`. Its own data folder and its own settings document
  (`NeuePreferences`, key `neue.ui`).
- **Master UI is enforced by a test**, `MasterUiLawTest`: a radius, a shadow, a
  gradient, a colour literal, a Material import, weight 600 or a spring in
  `neue/` fails CI. Read `MASTER-UI.md` before drawing anything there.
- **Colour is allowed in exactly two places**, on kai's instruction: the foil
  on a card's face (`cards/Foil.kt`, `cards/Holo.kt` — the holographic shader
  kai chose from the Blender mockups) and the group markers the user draws on
  their deck (`cards/GroupMarkers.kt`). Card art keeps its colour as content.
- **Cards may move, and nothing else may.** kai asked for the deck to lean
  toward the pointer and for a carried card to tilt and lift; that is
  `core/motion/DeskLean.kt`, read inside each card's `graphicsLayer` from one
  frame loop that sleeps when the cards settle. No springs — the law test still
  refuses them — and `cameraDistance` is set from the card's own width, because
  it is in 72-pixel inches and a fixed one leaves small cards flat.
- **Its keyboard is `core/input/DeskShortcuts.kt`**, a second table beside
  `ShortcutTable`, so the tablet's exhaustive `when`s never carry desktop
  actions. The palette and the help dialog render it. **Its mouse is
  `core/input/DeskMouse.kt`**, kai's 1.0.10 wording: right-click adds from the
  pool and **removes** from the deck, holding the left button opens the card
  large with every action beside it (`CardViewer`), holding the right button
  duplicates. **Compose's `awaitFirstDown` ignores every button but the
  primary** — it is why right-click did nothing for six releases;
  `CardPointer.awaitAnyDown` is the fix, and `tools/shoot.sh --neue --mouse=…`
  proves a gesture by the deck's counts. `NEUE.md` §4 has why.
- **It uses the Master UI family cursor, "Crop caption"** — ported, because
  `cursor.js` reads a DOM and Compose has none: `core/input/CropCaption.kt` (the
  arithmetic, tested) and `neue/cursor/FamilyCursor.kt` (the drawing). Every
  target declares its intent with `Modifier.cursor(…)`/`cursorPointer(…)` — the
  kit's `data-cursor*` hooks as parameters — so **a new clickable needs one**,
  with a verb caption where it shows no words, and a `reason` where it can be
  disabled for a reason that is not obvious. Menus open in the window's own
  layer (`AnchoredBox`), never a `Popup`, or they draw over the cursor.
  `docs/master-ui/CURSOR.md` is the spec; `NEUE.md` §2d.
- **Every pixel of chrome is a pixel off every card.** One 48px bar for the
  window and the builder together (search is on the rail), one row over the
  main deck (the boxed Groups button, the name, the lens), and the extra and
  side decks' names wherever the deck has room to spare — `DeckLabels` fits it
  both ways and keeps the larger card. The Groups panel stands beside the deck.
  `NEUE.md` §3 has the budget. The ramp is darker than the kit's in both
  themes, with a High contrast setting.
- **Groups break the deck into pieces** (`GroupPieces`, 1.0.15): cards of one
  group that touch are a piece, flush inside and aligned across rows; each piece
  shifts as one rigid shape by whole gaps, so there is a gap between any two
  pieces and none inside one. Pieces are *grown* from row runs, joining upward
  only where that keeps the shifts solvable — ungrouped cards in a U round a
  grouped one are the ordinary case, and a whole-deck fallback broke every
  column. The gaps are declared to `DeckFitter` (`extraWidth`/`extraHeight`), and
  drops resolve against the placed cards. **The Groups button is the Roles lens
  and the panel together**; off, the deck is plain. The Roles tab is gone from the
  lens, and groups are edited on their rows in the panel — the Groups drawer is
  deleted. A group may hold extra- and side-deck cards; its colour is an index
  read through one of seven palettes (`GroupMarkers.palettes`). **The wheel
  re-fits the deck** smaller (`deckZoom`) — a re-fit, not a transform, so every
  layout rule holds — and Shift-wheel sets the groups' gap. `NEUE.md` §3.
- **The index rail folds away and F11 is immersive mode**, both decided by
  `core/layout/EdgeReveal.kt`; bars come out *over* the page, never pushing it,
  or the deck re-fits and every card jumps. Leaving full screen must go through
  `Floating`: Compose's `Maximized` never clears full screen. On Windows immersive
  is **a borderless window over the monitor**, swapped in for the decorated one:
  Compose's full screen there is the JDK's exclusive mode, which with Direct3D
  minimised on focus loss and without it kept the title bar (1.0.12, reverted).
  That window is **not resizable**: Compose puts an invisible resize border on a
  resizable undecorated window, and it swallowed the reach for the rail.
  **The swap is a handover** (1.0.24, `WindowHandover.kt`): the window on screen
  keeps its last frame as a picture and lets its tree go *before* the next is
  built, and the picture goes only once the next is on screen — two live trees
  would fight over the drop targets and zen's slots. The app's lifetime (pool,
  preferences, art library, image loader) is `NeueEffects`, outside the windows:
  never put app-lifetime effects back inside `NeueRoot`'s window content.
  Immersive keeps 32 px at the top (`IMMERSIVE_TOP`) and
  centres the deck below it.
- **Immersive mode has a zen**: idle three seconds and the chrome fades, ten and
  the deck floats in the middle of the window, **each group as one block,
  fluttering corner to corner like scales**, over **its own shadows** —
  kai's one exception to "no shadows", in `neue/zen/ZenShadows.kt` alone, which
  the law test enforces. **The cards are the garden**: in deep zen the pointer
  picks any card up and puts it down anywhere (`ZenArrangement`, a picture only
  — the deck's order never changes); let go near its slot it goes back, near
  another card's edge it snaps flush and joins that block (`ZenSnap`), only a key wakes it, and "Put the cards
  back" comes out in the bottom-right corner. The sand garden that stood behind
  the deck for six releases (spirals, rakes, a sun, `SpiralGarden`) is deleted,
  on kai's word that it did not look good. **Many cards move as one** (1.0.14):
  a box dragged over the table, Shift-click and a double-click on a block pick
  cards out, and a group let go against another card joins its block — the
  grammar is `ZenGestures`, the arithmetic `ZenPick` and `ZenSnap.snapAll`, all
  in core with tests. The box is the window's pointer watcher's, and it spends
  the press so the faded-out pool never hears it. An empty deck has no zen.
  `Z` is zen at once. **Groups** in the zen corner breaks the deck into its
  Roles pieces with a faint prismatic glow round each (`zenGlow`, in
  `ZenShadows.kt` because it blurs), and **Labels** beside it writes each group's
  name on its piece (`ZenLabels`, `NeuePreferences.zenLabels`). Every zen begins
  with the cards in their slots (`ZenLayer.begin`). **Faded is not gone**: in deep
  zen the pool and inspector are shielded (`ZenShield`) and the deck lifted over
  them, and `zenQuiet` chrome is unplaced once faded (measured, not drawn or hit)
  — the Groups panel stands above the deck for the pointer and hid cards behind
  its tooltips (1.0.24). New chrome that fades must use `zenQuiet`, or it answers
  the pointer in deep zen. The corner always offers Leave zen. The
  wheel sets zen's gaps, and zen fits the deck *as drawn* (`stageRect`, grown by
  its pieces) so the cards shrink as the gaps widen.
  `NEUE.md` §3a.
- **The screenshot has two shapes** (1.0.23, kai's pick of four): **Picture**, the
  default — the builder's pieces and name tabs, every copy — and **List**, a
  decklist of art, counts and names made to read on a phone, split into
  Monsters, Spells and Traps when there are no groups. Settings → Screenshot;
  `ShotDesigns` plans both, `DeckList` (core) is the list's arithmetic. `NEUE.md` §4e.
- **History** beside undo/redo lists each step in words (`DeckHistory`, read off
  the decks either side, since the undo stack keeps decks, not edits).
- **Export is a menu**: `.ydk`, `.ydkx` with groups, a `ydke://` code or a text
  decklist to the clipboard (`YdkeCodec`, `DeckText` in core), or **a QR code**
  of the whole deck shown in a dialog (1.0.30; `neue/qr/`, ZXing, black on white
  in both themes). **The code carries everything** (1.0.31, kai: "as much
  information as possible, including groups"): `DeckQr` (core) packs the deck's
  `.ydkx` with its name and covers — zlib, then Base45 behind `NMT1:` for QR's
  alphanumeric mode. **A deck past one code is several** (1.0.32): parts
  `NMT1P:i/n/tag:`, **all shown at once** in a grid (`DeckQrGrid`; kai: never
  one at a time, nothing to click), collected in any order by `DeckQrParts`;
  only past 24 codes does a deck shed its extras, then its groups. On a phone or
  tablet **Import is a menu** that adds scanning with the camera (`ScanActivity`,
  the APK's own, reading every code in each frame with ZXing's multi reader) or
  from a picture, one screenshot holding every part (v1.3.7); `DeckCodes.read` (core) turns what
  was read into a deck. `NEUE.md` §4. A tip at the
  bottom of the window opens `above`, or it covers its own control.
- **Format** (1.0.33, `05`, `NEUE.md` §4i): **webs of decks** — the field expected
  at an event, yours starred. `DeckWeb`/`WebLibrary` (core; the page is Format, the
  type is not, since `Format` is TCG/OCG) kept as one preferences document
  (`neue.webs`, no migration); a web's decks are ordinary decks the Decks page
  leaves to it. **`.ydkw`** (`WebCodec`) is one text file: a `#web` header, then a
  `#deck` block per deck, each a complete `.ydkx`. The builder bar steps through a
  web (`WebSwitch`, `Alt ←/→`), saving as it goes. Siding patterns and the PDF
  guide are the next two releases, on kai's mockup.
- **The builder opens a deck**: the library's default, else the one saved last
  (`StartingDeck`). A library row shows up to three chosen covers
  (`DeckCovers`), and a card's alternate artworks are a picture choice applied
  inside `NeueCard` (`CardArt`, `LocalArts`) — never a change to the deck. The
  switch is **on the card** (a `2/9` chip on hover, `A`), because the
  inspector follows the pointer and lets go of a card before you reach it.
  YGOPRODeck has no picture for an alternate sharing its passcode (Nibiru,
  Lady Labrynth), so **+ Your own** in the inspector imports one (`CustomArt`;
  a choice is a passcode, or −k for an own picture). **It crops** (1.0.34,
  `ArtCropDialog`): a picture chosen, dropped or pasted, a box of the art
  window's shape (`ArtWindow`, `ArtCrop` in core), and **Replace art** keeps the
  card's own original with the crop baked into its art box — a whole picture,
  so nothing downstream knows about crops. `NEUE.md` §4c–§4d.
- **The library** duplicates and exports a deck from its row, tags each deck from
  its cards (`DeckTags`) and finds a deck by a card in it (`DeckSearch`).
  **Auto save** waits on `DeckBuilderState.dirty`. The arrow keys walk the
  selection (`GridStep`); `↑`/`↓` are the pool's results keys too, and walk the
  results only in the search field or with nothing selected. Each group's name
  is written once, on the longest top edge of its pieces (`labelEdge`, 1.0.22;
  `NAME_TAB`, declared to the fitter), and
  the lens row stays put when the wheel shrinks the deck. `NEUE.md` §3, §4, §4f.
- **Finding cards** (1.0.19): the pool and inspector hide from their own
  buttons and leave a strip to bring them back. `FilterPanel` is shared by the
  pool and the **search pop-out** (`SearchStudio`, `Ctrl Shift F`), and carries
  DuelingBook-, Master Duel- and Neuron-style facets. The new `CardFilter` fields
  are trailing and empty by default, so the tablet is unchanged. Effect
  categories are read off the text (`EffectKinds`). **Lists of cards** are in
  `NeuePreferences.cardLists`; the pool shows one through the filter's `onlyIds`,
  `L` puts a card on the active list, and the pop-out opened on a list adds to it.
  `NEUE.md` §4g.
- **Card art comes from a local library of originals** (`art/ArtLibrary.kt`,
  about 2 GB, downloaded in the background under YGOPRODeck's rate limit),
  falling back to the small render. **Card names are stamped in the foil**
  too, kai's pick — the letters found in the render's pixels by `NameInk`,
  the ink's polarity decided by the frame type (`NEUE.md` §2c).
- **Releases are `neue-v*`, always published as pre-releases**, by
  `release-neue.yml` (`.msi`, two `.dmg`s, `.deb`). Never tag one `v*`, and never
  publish one as a full release: `/releases/latest` is what every APK reads.
  `windows.upgradeUuid` in `app/neue/build.gradle.kts` and the rule that the
  version only rises are permanent, like the `versionCode` floor.
- **Ship a Neue change on its own track**: green `build-app.yml` (its `neue`
  job), fast-forward `main`, dispatch `release-neue.yml` with the next patch,
  confirm all four installers attached — and the APK's track too when the
  tablet would see the change.
- **On the tablet** (v1.3.0, `NEUE.md` §1b): a finger's grammar is
  `core/input/DeskTouch.kt` — tap reads, double-tap is the right-click,
  press-and-hold opens the card large, drag moves it (in the pool only a drag
  *across*, since the pool scrolls). **Android reports no button for a finger**,
  so a "primary press" is `isPrimaryPress` and a finger is `byFinger`
  (`kit/Pointer.kt`); a right-click menu is `onContextMenu`, which a held finger
  opens too. Every hover-only affordance has a finger's form (the art chip on the
  selected card, swatches on a tap, the rail pinned, a pinch for the wheel), and
  a mouse and keyboard plugged into the tablet keep the desk's idioms.
  **The touch swarm** (v1.3.1–v1.3.4, `NEUE.md` §1b) gave the tablet its deck
  width (`PaneBudget`), Back as Esc (`BackChain` — and `MainActivity` hands Back
  to it before Compose, which with a keyboard attached spends a Back on clearing
  focus), taps counted per surface (`TapBurst`), a carried card above the finger
  (`CarryOffset`), haptics for hand events only (`DeskFeel`), two- and
  three-finger undo and redo (`MultiTap`), `muClickable` (a resting thumb fires
  nothing) and `TouchMetrics` for chrome outside the deck.
- **On a phone** (v1.3.5, `NEUE.md` §1c): `FormFactor` (smallest width under
  600 dp and touch) is `NeueState.form`/`LocalPhone`; the screen turns by
  `NeuePreferences.orientation` (Portrait/Landscape/Auto, `MainActivity.applyOrientation`,
  manifest `fullUser`); `PhoneBar` with its ⋯ overflow (`phoneMenu`) and the Update
  chip that never overflows; `TabBar` along the bottom; `TallBuilder` — deck on top
  (10×4 upright; `DeckLabels.stack`/`DeckFitter.phoneColumns` lying down), pool in a `PoolDock` with Cards and
  Groups tabs; a tap opens the viewer (`viewSoon`). **`MuDialog` fits any window** and
  keeps its footer on screen — the phone could not reach Install before. Upright the
  deck is the decklist's 10×4 at full width (1.3.6, `naturalDeckHeight`, the dock gets
  the rest); the foil follows the phone's tilt (`TiltFilter`, `LocalTilt`, read in the
  draw only); a card can be shown full screen turning with the hand (`Showcase`). New chrome
  must work at 360 dp wide: `tools/shoot.sh --form=phone --width=1080 --height=2400 --density=2.625`.
- **The emulator walk** (`NeueSmokeTest`) waits by the clock and polls for state,
  never `waitForIdleSync` or `ActivityScenario.onActivity`: a caret blinks for
  ever, and on the CI emulator the main thread never falls idle. It seeds its
  cards into the pool, because the pool's download can outlast the walk, and it
  taps high on the deck while the soft keyboard is up.
- `tools/shoot.sh --page=builder --theme=ink` photographs it headlessly.

Play mode is not in Neue; kai will rebuild it from scratch inside Neue in a later session.

## The port — Neue on Android and on the Mac

The plan, phased, one shipped release per phase:

1. **Neue first** (done): `:builder` extracted so Neue needs nothing of the
   tablet's look; `:desktopApp` deleted (never released — Neue is the desktop
   app); the tablet and play-stage docs moved to `docs/classic/`; README and this
   file lead with Neue.
2. **`:neue` builds for Android**, desktop unchanged: an `androidTarget`, a
   `sharedMain` source set between `jvmMain` and `androidMain` (both are JVM, so
   `java.io`/`java.time`/`String.format` are fine there), and one portable seam
   for each desktop-only API — AWT clipboard and file dialogs, `onPointerEvent`,
   `TooltipArea`, the desktop scrollbar, the AWT cursor, `java.net.http`, Skia
   in the name masks, zen's blur and the screenshot, classpath fonts.
   `MasterUiLawTest` scans every source set.
3. **Neue is the APK** (v1.3.0, done), tablet landscape first: `MainActivity`
   renders `NeueRoot` on the same database; touch has its own table beside
   `DeskMouse`, and every hover-only affordance a touch equivalent; `:ui` and the
   studio's tablet task are deleted; an emulator smoke test runs in CI. A phone
   layout for Neue is a later phase.
4. **The Mac, faithfully** (1.0.21, done) — still unsigned, on kai's word: Apple
   silicon and Intel `.dmg`s, the native menu bar read off the tables
   (`DeskMenuBar`, with `ActionEcho` so an accelerator and the window never both
   run one press), `⌘` in every shortcut label (`DeskShortcuts.kbd(…, mac)`), an
   updater that installs over itself (`MacInstall`), and a signing/notarization
   switch that turns on when the five Apple secrets are added. `NEUE.md` §5a.

A phone layout for the APK shipped in v1.3.5 (`NEUE.md` §1c). Next: play mode
rebuilt inside Neue.

## Classic — the tablet app and its play stage

The tablet app (`app/ui/`) and its play stage are deleted from the tree; the
APK is Neue from v1.3.0. They live at commit `c2fc8d8` (the tag
`neue-v1.0.19`) — `git show c2fc8d8:CLAUDE.md` has their rules at length — and in
`docs/classic/`: `DESIGN.md` (the tablet's handbook), `TUNING.md`, `DEVICES.md`,
`TABLE.md`, `AAA.md`, `FIDELITY.md`, `PHOTOREAL.md`, `LOOP.md`. Read them before
rebuilding play mode inside Neue, which kai will ask for: the play stage's six load-bearing rules (one arbiter for the mat, the
finger on the felt and the card in the air, a gesture holds a card not a place…)
were each a bug first, and will be again in a new renderer that forgets them.

`:core`'s play-stage packages (`render`, `scene`, `board`, `mat`, `tune`) stay:
they are tested, the 3DS port's golden vectors are generated from them, and they
are what play mode will be rebuilt on.

## There is a second console, and it is a rewrite

`3ds/` is a New 3DS port, sideloaded as a `.cia`. **`docs/PORT.md` is the
authority** — read it before touching anything in there. The short version:

- **It shares no code with `app/`, and cannot.** There is no Kotlin/Native or
  JVM target for Horizon OS, and the PICA200 has no programmable fragment
  shader — only a fixed-function lighting stage driven by lookup tables. It is C
  against libctru and citro3d, sharing the *arithmetic* and the *design*.
- **The arithmetic crosses over proved, not by hand.** `GoldenVectorExportTest`
  (in `:core`'s jvmTest source set) sweeps each ported function over a grid and
  writes `3ds/test/vectors/`; `3ds/test/mt_test.c` compiles the C with the *host*
  compiler and asserts against the same files. `make -C 3ds/test test` is a
  second's work on any machine and is the port's whole safety net.
- **The vectors are committed, and CI diffs them.** A change in `:core` that
  moves a zone will fail the `vectors` job. That is not a bug — it means the
  console's copy of the rules has to move too, and the regenerated file belongs
  in the same commit so the change is visible in review.
- **`3ds/src/core/` may never include a libctru header.** That one rule is what
  keeps the above true. It is the same rule that keeps `:core` compiling in a
  sandbox where `:ui` cannot.
- **The port deletes rather than ports the projection layer.** `StagePlane`,
  `CarryHeight` and `MatInput.handQuad` exist because Compose has no real 3D;
  citro3d has matrices and the bottom screen is orthographic, so three of the six
  load-bearing play-stage rules above become vacuous rather than translated.
- **Its release track is separate**, on kai's instruction: `release-3ds.yml`,
  tagged `3ds-v1.0.0` against Android's `v1.2.3`. That prefix is load-bearing —
  `/releases/latest` returns the newest release of *any* tag shape and the
  in-app APK updater reads exactly that endpoint, so `AppVersion.parse` is the
  only thing keeping them apart. It could not until now: it read `3ds-v1.0.0` as
  major version **3**, newer than any APK ever shipped and carrying none.
- **Two numbers are permanent**, the same class as the Android `versionCode`
  floor: `UniqueId` `0xFF4D7` in `3ds/app.rsf`, and the `3ds/VERSION` triple,
  which may only go up. And a `.3dsx` inherits its host's permissions while a
  `.cia` gets only what its RSF grants — a missing service is a black screen
  after install, not a build error, which is why `app.rsf` already lists every
  service the app will ever call.

## Cards can be searched by what they say, not only by what they are called

Some archetypes are not a word in anybody's name — kai's example is the deck
built out of cards that *mention* "Light and Darkness Ritual", which a
name-search can only find if you already know them. `core/search/EffectMatching.kt`
reads the printed text, and three decisions in it are worth keeping:

- **No index.** The obvious implementation maps every word of every card's text
  to the cards using it, which is about five megabytes on a thirteen-thousand
  card pool and buys a search that was already fast enough. `containsRun` walks
  the *raw* text and normalises as it goes — lowercasing, treating any run of
  punctuation as one word break — so nothing is copied and nothing is kept alive
  between queries.
- **Every text tier ranks below every name tier** (400 and 300 against a name
  floor of 460), so widening the scope can only append rows to the bottom of a
  list whose top is unchanged. That is what makes `searchEffects` safe to default
  **on**.
- **The query can carry its own scope.** `text:`/`effect:` searches only the
  text, `name:` only names, and a quoted `"run of words"` must appear contiguous.
  A prefix costs no chrome, which matters most on the screen that has none —
  and the trailing word is always matched as a prefix, so the list is not empty
  until you finish typing.

**Explicitly deferred by the user — do not build on the legacy designs:**
siding patterns and shootout mode will be redesigned from scratch in a future
run. The only obligation today is that `YdkCodec` keeps round-tripping the
opaque `#ydkx-extended` payload (it does — `DeckGroupsCodec` preserves
unknown keys byte-for-byte).

## Multi-Team Trigger

When the user starts a prompt with **"mt"** or **"mt:"**, they want a
multi-agent team: strip the prefix, create a team, break the task into 2-4
subtasks, spawn 2-3 general-purpose teammates, coordinate, report back.

## Data Formats (unchanged from the original)

- **Card**: YGOPRODeck API v7 shape (`core/model/Card.kt`); ids are Konami
  passcodes.
- **Deck**: ordered multisets of ids per section (main 40-60, extra/side
  0-15, 3 copies across the whole deck) — order round-trips to `.ydk`.
- **YDKX**: plain YDK + `#ydkx-extended` + one JSON object. The app owns the
  `groups` key; everything else (legacy `sidingPatterns`, `notes`,
  `configurations`) passes through untouched.

## Debugging

- Core logic: write a failing commonTest first; `./gradlew :core:jvmTest`.
- Neue: `./gradlew -Pmastertool.android=true :neue:run`; the studio for
  pictures; a crash is written to `<data>/crash.txt` and shown on next launch.
- Android: the in-app crash reporter shows and shares the trace (`last-crash.txt`
  in the app's files); `android-smoke.yml` runs the APK on an emulator and
  uploads a screenshot, since no emulator runs in this sandbox (no KVM).
- Preferences are one JSON document in SQLite (`NeuePreferences`); adding a preference is a field with a default, never
  a schema migration.
