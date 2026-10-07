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
  Ai's harness in `core/ai`, and the classic play stage's geometry), all tested
  in commonTest
- `app/table/` — **the duel table and what it draws with** (Master UI's theme and kit, the family cursor, the card
  face and foil, `Duels` and the table's composables, `DuelPlayArea`), compiled for the desktop, Android **and the
  browser** (wasmJs) for the Lounge (`docs/LOUNGE.md`); packages kept as `com.kaiharimoto.neue.*`. What it reaches of
  the app goes through `TableHost` (Ai: `TableAi`; voice: `TableVoice`), files through `DuelStore`, the network
  through `TableNet`, Ai vs Ai through `LiveMatch` — Neue implements each (`NeueTable.kt`, `FileDuelStore.kt`)
- `app/builder/` — the builder's state and plumbing that is not a look
  (`DeckBuilderState`, `AppDependencies`, updater seam, image loader, shader
  seam, card foil); files keep their `com.kaiharimoto.mastertool.ui.*` packages
- `app/core/.../duel/` — the duel simulator's model, rules, log, views, words and command line
  (1.0.74); **never `core/board`**, which is the classic play stage's, ported to the 3DS and frozen
  by golden vectors
- `app/neue/` — **Neue Master Tool**: every screen
- `app/androidApp/` — the APK: one activity hosting Neue, the theme-free crash
  reporter, and `NeueSmokeTest` for the emulator
- `app/studio/` — the headless renderer; `tools/shoot.sh`
- `docs/NEUE.md` (the app), `docs/SYNC.md` (sync), `docs/classic/` (the tablet app and play stage),
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
- **SQLite schema version is 4** (from Neue 1.1.0 / APK v1.3.78, `migrations/3.sqm`: card release data) and can
  never decrease — devices that installed v1.1.0 are stamped at `user_version 3`
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

- **It is built on `:core`, `:builder` and `:table` and nothing else.** `:table` is the duel table and Master UI's kit,
  portable to the browser (the Lounge): nothing in it may lean on the JVM (`SharedPortabilityTest` reads it; the `web`
  CI job compiles it for wasmJs), and the law test reads it as it reads `neue/`. `:builder` is the
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
  their deck (`cards/GroupMarkers.kt`). Card art keeps its colour as content, and so does the card back: kai's
  own artwork from the classic app, `composeResources/drawable/card_back.png` drawn as it is (1.0.88, `cards/CardBackArt.kt`).
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
  main deck (the boxed Groups button, the name, the lens), and **no names or counts
  on the extra and side decks** (1.0.41, kai: players know them on sight, and both
  hold fifteen) — their room is the cards', and 28 dp of paper stays under the last
  section. On the builder the bar drops the wordmark, a legal deck is a ✓ (its words in
  the tip; issues and notes counted) and Import, Export and Screenshot are icons, so the
  deck's name has room. A selected card stands up out of the page, framed outside its
  edge in paper and ink, above its neighbours. The wheel's notch is 5 %. The lens tabs are
  gone (1.0.42): As is, Fitted and Separate stand in their place over the main deck. The Groups panel stands beside the deck.
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
  read through one of seven palettes (`GroupMarkers.palettes`). **Out, the groups are
  As is, Fitted or Separate** (1.0.37, `NeuePreferences.groupArrangement`, `Shift K`):
  As is is the pieces above; Fitted lays the main deck out in bands of group blocks
  a whole gap apart (`GroupBands`), and Separate puts each group on rows of its own
  with no fitting rules (`GroupRows`, 1.0.40) — Fitted's blocks keep copy sets whole, blocks at most four rows,
  groups in order, ungrouped last, an edit holding the last shapes (`BandMemory`),
  and never much smaller cards than As is (1.0.38: a layout below nine tenths of the
  plain deck's card pays heavily; gaps and the other sections are counted) —
  handed on as a `PieceLayout` with `rowOf`, so outlines, tabs, drops and zen read
  it unchanged. `NEUE.md` §4h¾. **Reordering by drag** (1.0.39, `DeckReorder`): a card
  over its own section takes the place of the card it is over and the rest glide aside —
  one copy through fixed cells As is; its whole copy set within its own group in Fitted
  and Separate, which keep **their own order** (`DeckGroups.fitted`, saved as the groups
  payload's `"fitted"`), never the deck's. `NEUE.md` §4h⅞. **The wheel
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
- **Ai has a face** (1.0.52, `NEUE.md` §4k′): the approved magatama mockup drawn live.
  - The geometry is traced (`AvatarGeometry`, generated by `tools/avatar/gen.py`; never
    edit it by hand).
  - The twenty kaomoji faces and the rig are `core/ai/avatar` (`Expression`, `AvatarRig`,
    tested). `MoodTracker` maps what Ai is doing to a face, and the `express` tool is
    Ai's own choice of five.
  - `neue/ai/avatar/AiAvatar.kt` draws it. It is the third file allowed colour.
  - The face sits on the composer's top edge with its status (`FaceStrip`).
  - The bar's button is `AiBadge` on every platform: since 1.0.64 the live face alone, no box,
    no name, no running line; its brain opens from the panel's head. Wherever Ai is named, its
    still `AiMark` stands by the name (`AiName`: replies, the panel's head, Settings) — kai: "every
    mention of Ai is a chance for the art".
  - Only the whole head moves; the net never distorts.
- **Chessy, the ghost in the system** (kai, 2026-10, `NEUE.md` §4k″): a cat girl who hacks the app and takes Ai's
  place after five replies (or `/chessy`). Her pictures are kai's approved mockup exactly (`tools/chessy/build.py` reads its data, `--check` proves parity →
  `composeResources/files/chessy/`; `tools/chessy/README.md`); the rig and warp are `core/ai/chessy` (pure, tested); `ChessyAvatar` draws each
  picture as a bent mesh (`drawMesh`: Skia/Android `drawVertices`). `AiPrefs.persona = chessy` puts her face where
  Ai's is (`LocalChessy`). Her twenty moods are Ai's moods worn in parts cut from her three faces
  (`tools/chessy/parts.py` → `moods.json`; `ChessyMoods`), with Ai's body language and marks placed round her
  (`ChessyMarks`, drawn in `ChessyInk.kt`, allowed colour). **Never drawn under 104 dp** (`ChessySizes.MIN`; a smaller
  spot shows `ChessyTag`); she lives in the chat box at 132 dp. **Her copies** teleport beside what each tool works on
  and say it in a box (`ChessyCrew`, hooked in `AiHost.run`; spots are `Modifier.chessySpot`, named in `ChessyPoint` —
  a new place a tool works on gets one). **Her name is `AiState.name`** while she is the assistant (Ai's own,
  which renaming edits, is `ownName`); **her mark is her ears** (`ChessyEars`, generated by `tools/chessy/ears.py`;
  `ChessyMark`, drawn by `AiMark`). **Her rig was red-teamed against Live2D/VTuber practice** (`docs/chessy/RIG-REDTEAM.md`, shipped in
  neue-v1.1.34 / v1.4.13): moods fade (`ChessyMoodBlend`), Flap follows her words (`SpeechText`), `ChessyMood` carries how she looks,
  blinks and breathes; every feel is a named constant in `ChessyRig`. Round two: half-lids (`ChessyLids`, `lids.py`) bring the
  lid down over irises that never move (kai's choice), and a jumping look or a mood change takes a blink. `/chessy`, `/ai`, `/catmode` are intercepted in `AiState.send`. **Her voice is `ChessyVoice`** (kai: cute evil,
  loving): a prompt section after the soul, cat mode the full nya voice, never on card names, numbers or tool inputs; a
  change mid-conversation is said in the next message (`AiSession.voiceShown`, since prompts are frozen). **Her marks are
  foil** (`ChessyInk.foil`: the mark, then `Holo.drawHoloSheet` with `SrcIn` in a layer the mark's size). **A press held
  on her face in the chat box opens the petting mode** (kai's Pokemon-Amie Easter egg: `ChessyAmieLayer`; zones,
  reactions, lines with kaomoji, fondness and particles are `core/ai/chessy/ChessyAmie.kt`, tested; `--chessy-amie=`).
  **Petting her** is half the window with **her aura** (`chessyAura`) and **the takeover's box** (`ChessySay`; typed
  by `ChessyType`: whole line laid out first, emoticons unbreakable); **her room** (1.1.27): a paw for the pointer (`CursorMode.PAW`, `ChessyInk.drawPaw`), a toy box
  of a yarn ball, a feather wand, a wind-up mouse and a bag of catnip to pour (1.1.30, `Catnip`, `Flakes`; she rolls in it, `PlayState.ROLL`) with real physics (`core/ai/chessy/toys/PetToys`, tested) drawn as
  the dice are (`PetToysInk`, paper and ink, a step of shade per facing), one arbiter for every press; **she plays**
  (1.1.29): roams, bites the yarn and feather, stalks and pounces on the mouse (`ChessyPlay`, tested), flat ink shadows
  under her and the toys (`floorShadow`, kai's word); **it sounds** (`core/audio/PetSounds`: her meows and purr in code,
  the toys, chimes; `PetMix` into `Speaker.stream`, `PetAudio`; `AiPrefs.petSound`; silent while the window is not focused); **her name glitches** (`ChessyGlitchName`); her
  layers have **no white rim** (`tools/chessy/defringe.py`, a step of `build.py`). **The takeover's horn is kai's
  tuning** (`TakeoverHorn`, never changed without kai). **The takeover** (1.1.25): `core/ai/chessy/Takeover` is the
  cinematic as pure functions of its clock (safety held by `TakeoverTest`), `core/audio/Synth` + `TakeoverSound` render
  its sound in code, `platform/Speaker` streams it from the clock; it glitches **the live app**, recorded into a
  `GraphicsLayer` by the shell while it plays (`TakeoverLayer`, colour in `TakeoverInk.kt`). It plays after the fifth
  reply, on `/takeover` and from Settings (`/chessy` just switches, 1.1.31); Skip and Sound in its corner, Esc/Back skip; `takeoverSound`
  turns its sound off; `--takeover=<s>,…` photographs it. 1.1.31: once held she faces you; **her gifts** at full hearts (`core/ai/chessy/gifts`: `GiftCatalog`, `GiftCollection` in `AiPrefs.chessyGifts`, `GiftMeshes` real 3D solids, `GiftBody`, `GiftPlay`, the chest and its drawer `GiftDrawer`; drawn by `GiftInk.kt`, the gifts' colour exception), and a hand held still she rubs against, purring (`PlayState.SNUGGLE`). 1.1.33: toy play warms her hearts (`ChessyAmie.played`), and a gift picked up or brought out of the drawer is talked about (`ChessyAmie.admired`). 1.1.30 (kai's notes): red warning windows pile up (`Takeover.WARNINGS`), her lines leave 0.8 s to read, her voice is her nya's (`LINE_VOICES`), Ai holds her in a glitching frame she shoves against looking up (`TakeoverInk.contained`, `PUSHES`), and it lets go of the keyboard (`focusTaken`).
- **The pages are `01` Builder (home), `02` Decks, `03` Siding, `04` Format, `05` Prep, `06` Present, `07` Duel, `08` World, `09` Shootout** (1.0.40, kai:
  Odds and Stats removed; Builder first since 1.0.89, the logo opens the index (`NeueState.railHeld`); the siding editor its own page, `SidingPage`, opened by
  anything that asks `Webs.side`). Siding sides the deck asked for, else the builder's;
  a deck in no web is sided against opponents made there — a name and three cards
  (`OpponentDialog`, `Matchup.covers`), a library decklist linked later (1.0.42).
  **Siding is visual** (1.0.49): the deck to side from is laid out like the builder
  (`SidingBoard`, every copy, marked OUT/IN on itself), **Art | List**
  (`NeuePreferences.sidingView`) shows the plans as a picture per copy or as counted rows,
  and the PDF guide follows it (`GuideStyle`); a new opponent's name suggests its cards
  (`OpponentGuess`), and a matchup's actions are buttons, not a ⋯ menu.
  **The deck to side from fits the window** (1.0.51, `BoardFit`): plans above, capped at half
  the height; below, the Main Deck ten across with the Side Deck beside it, no scrolling; the
  Extra Deck behind a toggle (`sidingExtra`) offered only when the Side Deck holds Extra Deck cards.
  `NEUE.md` §3, §4j.
- **Format** (1.0.33, `04`, `NEUE.md` §4i): **webs of decks** — the field expected
  at an event, yours starred. `DeckWeb`/`WebLibrary` (core; the page is Format, the
  type is not, since `Format` is TCG/OCG) kept as one preferences document
  (`neue.webs`, no migration); a web's decks are ordinary decks the Decks page
  leaves to it. **`.ydkw`** (`WebCodec`) is one text file: a `#web` header, then a
  `#deck` block per deck, each a complete `.ydkx`. The builder bar steps through a
  web (`WebSwitch`, `Alt ←/→`), saving as it goes. **Siding** (1.0.35, `SidingEditor`):
  any deck of a web sided against any other, a plan per turn with its why, under
  the deck's own `siding` key (`SidingCodec`, core — legacy `sidingPatterns` read,
  never written); matchups link web decks by id, remapped when a `.ydkw` opens.
  Beside the plan, **how they side against you**: the opponent's own plan for the
  answering turn, as pictures. The web's page lists your matchups (`MatchupTable`).
  An edit to the builder's open deck goes into its payload too
  (`DeckBuilderState.putExtended`), or its next save would write the old plan back.
  **The siding guide** (1.0.36) is a PDF written by our own `core/pdf` (`PdfDocument`,
  `TrueType`: the app's Inter and JetBrains Mono embedded whole, real text with a
  `ToUnicode` map, RGB pictures) laid out by `SidingGuide` (core, tested) to kai's
  mockup: at a glance, then two matchups a page with both turns and **their plan**.
  `GuideExport` gathers the pictures (the chosen artworks) and `deliverFile` saves and
  opens it on the desk, shares it on Android. `tools/shoot.sh --guide=path` writes one.
- **Prep** (1.0.50, `05`, `NEUE.md` §4l): tournament preparation, designed from Konami's
  KDE-US Tournament Policy v2.5 (`core/prep/Policy.kt` cites each rule by section): an event
  (date, tier, players, the field's web, your deck), its countdown and what the policy means
  for it, whether the deck is ready (`EventCheck`); practice logged against the field with the
  match win to expect (`TestStats`: best of three from first and second, the loser choosing,
  weighted by shares) and the matchups at risk of time; **siding drills** (`Drill`, Leitner
  boxes) because no notes are allowed at the table; our own decklist sheet (`DecklistSheet`,
  never Konami's form) and the list as text; and the day's checklist and rounds. One document,
  `neue.prep` (`PrepDoc`), no migration. Ai reaches it through `prep_state`, `set_event`,
  `log_game`, `matchup_matrix`, `expected_winrate`, `drill` (`AiPrep`) and the
  `tournament-prep` skill.
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
  nothing) and `TouchMetrics` for chrome outside the deck. **A surface over the page blocks
  touches with `Modifier.keepsPresses()`, never by spending every move** — a finger always
  moves a little, and a scroll or button inside then gives up (1.1.36, the Keepsakes drawer;
  `KeepsPressesTest`).
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

- **Ai, the assistant** (1.0.43, `NEUE.md` §4k): a panel docked beside every page
  (`AiPanel`, `Ctrl I`, `AiState` in `NeueHolders` for the app's lifetime). One harness
  in `core/ai` — `AgentLoop`, the tool catalogue `AiTools`, append-only `ChatTurn`s —
  over two kinds of model: APIs the app talks to itself (Anthropic through the
  **official Java SDK**, `AnthropicBackend`; anything OpenAI-compatible over Ktor) and
  the coding-plan CLIs (Claude Code, Codex; `CliBackend`, desktop only), which reach the
  app's tools through **the app's own MCP server** on 127.0.0.1 (`McpServerCore`,
  `AiDesk.startMcp`). `AiHost` answers every tool on the same state the person's clicks
  change. **`AiToolsTest` holds "complete control"**: a new `DeskAction` is reachable by
  `run_action`, and a new `NeuePreferences` field must be described in `AiSettings` or
  listed as internal — or the test fails. Memory is markdown in `<data>/ai` (`SOUL.md`,
  `USER.md`, `MEMORY.md`, `webs/<id>.md`, `decks/<id>.md`, `guides/<id>.md`), scoped
  to the one deck or web in view (`MemoryScope`). **No cap on what Ai knows** (1.1.11, kai: "I don't want there to be a
  cap to the knowledge"): only `USER.md` is bounded (5,000); `MEMORY.md`, deck, web and guide files keep everything, one
  entry at most `ENTRY_CEILING` (8,000). **The prompt reads a budget, not the file** (`MemoryBudget`): a share of the
  model's window per kind, never below the old caps — the most relevant entries (latest words, scope, the guide's labels,
  recency) and an index line, "(Memory index: N more …)", that compaction keeps; the rest through `memory_read` (`query`,
  `label`, `from`/`count`, paged by `MemoryQuery`) and `recall` scope `memory`. A new place that puts a memory file in a
  prompt goes through `MemoryBudget.pick` and marks it `MemoryBudget.tagged`, or `ContextBreakdown` counts it as the page;
  work over a file's entries uses sets (a guide may be a megabyte). Keys live in `SecretStore`, never the
  database or an export. **Disable AI** (`AiPrefs.enabled`) hides every trace: the key
  (`DeskContext.ai`), the menu (`DeskMenuBar.aiShown`), the palette, the panel. The
  wizard (`SetupWizard`) is the only way a connection is made. `AiState.PHASE` says
  which tools and skills a build offers (1 the harness, 2 the meta, 3 learning).
  **The meta** (1.0.44): `YgoProDeckDecks` reads YGOPRODeck's tournament lists (the
  undocumented `getDecks.php?tournament=tier-N`, filtered on our side, dates relative);
  `FieldBuilder` clusters them into strategies by a rarity-weighted Jaccard — never a
  staple cut-off, which strips a popular deck of its own engine — and the
  `format-webs` skill builds a web from that on its own.
  **Fine Tuning** (1.0.45): **Tune** in the panel's head starts an interview
  (`AiSession.MODE_TUNE`, `ask_user` chips) that writes to memory as it goes; **Finish**
  shows the change entry by entry (`MemoryReview`, `ReviewDialog`) to keep or undo. A
  conversation left after four messages is reflected on once (`AiState.reflect`, API
  connections only, `AiSession.reflected`), its memory writes undoable from a note.
  **The first setup takes the whole window** (`AiSetupScreen`, while `NeueState.aiSetup`:
  Ai asked for with no connection), its words in `SetupGuide` (core, tested per
  provider). **On the builder Ai takes the inspector's place** (`NeueState.aiDocked`).
  **Its name is never set in capitals** (`MicroCaps`, `LocalKeepCase`): "Ai", not "AI".
  **Replies draw tables, ```chart blocks (`ChatChart`, drawn in ink by `ChartBlock`) and
  ```cards strips** (1.0.46). A CLI's output lines are never dropped (the old `trySend`
  buffer was the "typos"), and Claude Code's answer is committed from its snapshots.
  Setup ends on **Start chatting** and **What can you do?** (`AiDemo`); the name renames in
  place from the panel's head; the panel docks in immersive mode too.
  **1.0.47, the harness**: `calculate` (`Calc`), `hand_odds`, `todo_write`, `web_search`/
  `web_fetch` (Anthropic's server tools on Anthropic; DuckDuckGo then Yugipedia elsewhere),
  `rulings`/`archetype_guide` (Yugipedia, CC BY-SA, cached a week), `delegate` (a look-only
  helper loop). `RulesPrimer` is always in the prompt, in our own words — never copy
  Konami's rulebook. The loop retries, caps results and compacts (`Compaction`, a stored
  `AiSession.summary`), and reasoning streams into `Part.Reasoning` (display only).
  **1.0.48, Fine Tuning is about the deck**: `TuneLauncher` — the person teaches it
  (`fine-tuning`) or it studies the deck itself (`self-study`), at a `TuneIntensity` — both
  writing the deck's guide (`MemoryKind.GUIDE`, `guides/<id>.md`), read while it is open.
  **Any OpenAI-compatible provider with a key** (1.0.53, `Providers.compatible`, presets in
  `compatiblePresets`): address, key and the person's own name per connection, many side by side.
  **On a phone or tablet a local model is on a computer across the Wi-Fi** — the APK permits
  cleartext (`network_security_config.xml`; `Providers.plainHttpAllowed` keeps it local) and the
  server step never starts from `localhost` there (`Providers.isThisDevice`).
  **1.0.54, what it learns, written down**: **Learn it from first principles** (`MODE_PRINCIPLES`,
  skill `first-principles`: card text and rules only; `AiTools.FIRST_PRINCIPLES_BARRED` neither
  offered nor answered); every deck session ends with `session_report` (`SessionReport`: learned,
  insights, questions asked, and understanding / playing / mirror scores out of 100 with why),
  kept in `<data>/ai/reports/<deck>.json` (`ReportLog`) and deleted with the deck; **the living
  guide** (`GuideDoc` sorts the guide by its labels; `LivingDocDialog`) and each report as Master
  UI PDFs (`ReportPdf`, core, on `core/pdf`; `AiDocs`); **Learn About You** (`MODE_PROFILE`,
  `learn-about-you`, into `USER.md`, now 5000 characters); **quick settings** from the model's
  name in the panel's head (`QuickSettings`); **its brain** (`MemoryDialog`) from the bar's name
  button beside the marquee, which is only a line now; and **the face answers a hand**
  (`AvatarPlay`: taps, a double tap, poking, petting, holding, staring).
  **1.0.55, pictures in and cards out**: a picture attached, pasted, dropped or photographed
  (`Attachments`, `PictureFit`) is `Part.Image`, a file under `<data>/ai/images/<session>/` whose
  base64 is `@Transient` and put in only when sent (`AiFiles.hydrate`) — never store bytes in a
  session; every wire sends it (Claude Code by `--input-format stream-json`, Codex by `--image=`),
  and `Vision` guesses from the name whether a model sees. `resolve_cards` (`ReadCards`,
  `NameMatch`) and the `deck-from-picture` skill read a decklist off a picture. Replies draw
  ```deck, ```compare, ```line and ```board blocks as card art (`CardLayouts.kt`), and `[[Card]]`
  in words opens the card.
  **1.0.56, context**: the loop reports each call's usage (`AgentEvent.Round`), kept as
  `AiSession.context`; `Usage.read` is the whole on every wire (OpenAI's cached tokens split out
  as Anthropic's are); the window is `ContextWindows` unless `AiConnection.window` says; the gauge
  in the panel's head opens `ContextPanel` (`ContextBreakdown`, Compact now, Clear old tool
  results via `clearedBefore`, Start fresh via `carriedFrom`); Ai has `context_status`, `compact`
  and `recall` (`Recall`). The estimate counts the tool specs.
  **1.0.57, voice**: `platform/Voice` (expect): on the desk Java Sound + Whisper on the computer
  (`whisper-jni`, the model a checked download into `<data>/voice`, `VoiceModel`, `VoiceDialog`),
  on Android the system's recogniser and text-to-speech (`RECORD_AUDIO` through
  `Platform.attach(permission = …)`); `SpeechGate`, `Hints`, `Spoken` and `Pcm` in `core/ai/voice`,
  tested. The mic (`AI_VOICE`, `Ctrl Shift Space`) fills the box; talk mode (`AI_TALK`, `Ctrl Shift
  T`) listens, sends, answers aloud, listens again. `DeskAction.AI` is never `run_action`'s.
  `VoiceProbeTest` transcribes a real recording when `NEUE_WHISPER_MODEL`/`NEUE_WHISPER_WAV` are set.
  **1.0.58, the fact-check pass**: after an answer that names cards or talks rulings or odds,
  a look-only helper (`FactCheck.CHECKER`, tools `card_info`/`rulings`/`calculate`/`hand_odds`)
  checks every claim; the result is `AiSession.checks` (a field, never a new `Part`), a line
  under the answer, and — if a claim was wrong — a context-only turn asking for a short
  **Correction:**, never an edit to the answer. `ai.factCheck`. `NEUE.md` §4k has the roadmap
  of what would take Ai further (goldfish simulator first).
  **1.0.59**: **a player's lists** — the deck API cannot filter by player, so `ygopro_player` reads
  the site's player search, the player's page and `/deck/<number>` (`PlayerPages`, tested on captures;
  `YgoProDeckLiveTest` behind `NEUE_LIVE_YGOPRODECK`), and `ygopro_deck` reads any number. **Setup asks
  nothing twice**: with a connection made the wizard opens on Your connections (use one, add, close),
  a key given before is filled in, saved OpenAI-compatible services are presets under **Yours**, and a
  connection set up again replaces its twin (`SavedConnections`). **On Android the bar's Ai is
  `AiBadge`** — face and name in a box, no running line; held, it opens the brain.
  **1.0.60**: a player search that matches one player is **a 303 to their page**, not a list — read
  as that player (`YgoProDeckDecks.players` keeps where a request lands); the list is capped at 25.
  **1.0.61**: the chat follows only a reader at the end (`ChatFollow`), and a thought watched open
  stays open when filed; **while Ai works on Android, `AiWorkService`** (a data-sync foreground
  service with a wake lock, through `Platform.working`) keeps the answer alive out of sight, and
  "Ai answered" is posted when it lands there; `Unreachable` words a failed lookup.
  **1.0.62, a deck from a video**: `watch_video` hands a YouTube address to Gemini, which watches it
  on Google's side (frames and sound) and reports the decklist, plan, lines, choices and siding
  (`AiVideo`; `YouTube`, `GeminiVideo` in core) — never scrape YouTube (bot checks, per-session caption
  tokens; NewPipeExtractor is GPL and this app is MIT). The key is the person's own (`video:gemini` in
  `SecretStore`) or a Gemini connection's, and with none its box stands in the chat (`VideoKeyCard`).
  The `deck-from-video` skill writes the guide from it.
  **1.0.63**: what is typed in answer to Ai's question lives on the `Question` (never a lazy row's
  `remember`), and a question closed unanswered hands it to the message box (`keepUnsent`); the bar's
  Ai is `AiBadge` on every platform (the running `AiMarquee` and `AiBrainButton` are deleted), and the
  brain opens from an icon in the panel's head.
  **1.0.64**: the bar's Ai is the face alone, and `AiMark` (the art, drawn still) stands by every
  mention of Ai's name (`AiName`).
  **1.0.65**: tables are laid out to the panel by `TableFit` (fit, wrap, or stack a row at a time —
  never scrolled); `ask_user` has `heard`, shown as "What I heard", and interviews read back with it;
  Learn About You opens on `ProfileCoverage` and the person's own evidence (decks, event, webs, recent
  chats); the guide (`MemoryKind.GUIDE`) is `UNBOUNDED`, one entry at most 5,000 characters.
  **1.0.66**: one Fine Tuning run may add `TuneIntensity.guideBudget` to the guide (Deep 20,000;
  `GuideBudget`); **Refactor guide** (`MODE_REFACTOR`, skill `refactor-guide`) rewrites the whole guide
  with the memory tool's `rewrite` (`GuideRewrite`, only in that mode), reviewed on Finish. The reader's
  guide went through two explorations; kai kept the lessons cover and the board after each play.
  **1.0.67, the reader's guide is a book** (`NEUE.md` §4k): `GuideBook` (core `ai/report/book/`,
  chapters → sections → typed `Block`s, any length, stable ids, `guides/<deck>.book.json`, deleted
  with the deck). **One layout, three painters**: pictures are laid out once on an `Ink` (`Pen`,
  `Faces`, `book/Graphics.kt`, `BookArt`) — `PdfInk` paints a page, `RecordingInk` keeps a `Drawing`
  the app paints (`DrawingView`) and the HTML will. `BookPdf`: the cover with **the table of
  contents**, page numbers by laying out twice, links and bookmarks (`PdfDocument.link`, `bookmarks`).
  Ai writes it a chapter at a time (`MODE_WRITE`, skill `write-guide`, tool `reader_guide`, checked by
  `BookWriter`, reviewed by `BookReview`); numbers come from `GuideFacts`, never from Ai. **The reader**
  (`neue/ai/reader/BookReader`, `NeueState.reading`) paints the same drawings with real cards; a
  line's board after each play animates (`FramesPlayer`); PDF and JSON export. Notes and highlights
  baked in by Ai (1.0.68) and the interactive book and HTML (1.0.69) are next.
  `tools/shoot.sh --ai=panel|empty|wizard|setup|tune|review|chart|demo|reason|teach|study|guide|refactor|end|brain|quick|profile|about|petted|picture|visual|attach|summarised|context|listening|talk|voice|checked|reader|reader-lines|reader-lessons|reader-empty --ai-step=KEY:anthropic` photographs it; `--book=pdf|json` writes the sample book.

- **Sync: bring your cloud** (1.0.68, `docs/SYNC.md`, `NEUE.md` §4m): Settings › Sync meets the other
  devices in a synced **folder** (desk path, or an Android `OpenDocumentTree` grant), **WebDAV**, or
  **Google Drive**'s app folder (OAuth PKCE through the browser and
  `http://localhost:53682/`; offered once `CloudClients` holds kai's registration; Dropbox and OneDrive
  were built and taken out on kai's word). The engine is
  `core/sync`: `blobs/<sha-256>` plus one `devices/<id>.json` manifest per device (no device writes
  another's file), `SyncPlan` three ways, decks keep both, settings merge (`JsonMerge`), files newer-wins.
  **Every `NeuePreferences`/`AiPrefs` field must be in `SyncedPrefs.SYNCED` or `DEVICE`** (`SyncTest`
  fails otherwise). Keys never sync (`SecretStore`, `ai/credentials.*`). `NeueSyncLocal` turns the app's
  state into items; `SyncCenter` runs it on opening, 20 s after a change, every three minutes.

- **Setup on opening and backups** (1.0.69, `NEUE.md` §4n): `StartSteps` offers someone new every step
  and someone updating only what arrived after `StartPrefs.seen` (each `StartStep` names its desktop and APK
  release — **a feature that needs setting up adds a step**). `BackupCenter` writes a `.nmtbackup` before a new
  version changes anything, weekly and before a restore; Settings › Backups exports and restores.

- **Duel** (1.0.74, `07`, `Ctrl 7`, `NEUE.md` §4p; kai: "better than DuelingBook by miles … utilitarian … the
  controls will be a big factor"): a **manual** duel simulator, phased — the table (1.0.74), replays, Ai at the
  table (full / one seat's / Auto knowledge, peeks logged; combos as one batch, per deck in
  `<data>/duel/combos/`), direct two-player (host-authoritative, code or QR; response windows and a thinking
  signal), then a relay. kai chose flat paper and ink (no new colour exception). **The log is the duel**
  (`DuelGame`: a fold over `DuelAction`s, groups for undo, randomness stamped into each action on commit);
  `DuelRules` is physics only, never card text; `DuelView` redacts per seat (veils re-minted by a shuffle) for
  the hot-seat, Ai and the network; `DuelVerbs` is the one list of verbs a right-click, a key, the inspector,
  `DuelCommand` and Ai all run; `DuelDrop` is the drop's intent and its highlight's words. `DuelLayout` keeps the
  card the only free variable (capped; lanes a tenth of a card) and `DuelFrames` places every card, a pile's
  too. `DuelInput` (mouse ↔ finger, tested) and `DeskScope.DUEL` (a key per verb; a card just placed shows zone
  numbers for a moment). `neue/duel`: `Duels` (holder, `<data>/duel/current.json`), `DuelTable` (one pointer
  arbiter), `CardBack`. **Never `return@` out of an inline lambda with composable calls in it** (`key`, `Box`, a
  `forEach` in a composable): it left the non-local-return marker in the bytecode and the class failed to load
  as the table first drew — `NonLocalReturnTest` scans every shipped class. **Replays** (1.0.75):
  `<data>/duel/replays/` (synced, backed up), `Replays` steps by gesture/phase/turn both ways, inserts, cuts a
  step, notes, branches a what-if; `DeskScope.REPLAY` replaces the duel's keys while one is open.
  **Ai at the table** (1.0.76): `duel_state`/`duel_act`/`duel_peek`/`duel_log`/`duel_setup`/`duel_combo` (`AiDuel`),
  `DuelBrief` (the table in words through `DuelView` — never names a hidden card), knowledge self/auto/full with
  every auto peek logged, moves played out at a pace (`Duels.playOut`, checked whole first); combos in
  `<data>/duel/combos/<deck>.json`, steps by name never uid (`ComboRecorder`, `ComboRunner`).
  **Two players** (1.0.77, `core/duel/net`): host-authoritative — the guest is only ever sent its own `DuelView`
  and draws a `DuelMirror`; `DuelHost.resolve` refuses a ref the guest was never shown; `Wire` over a TCP socket
  (`DuelLink`), paired by `PairCode` (LAN address, port, secret) or its QR; response windows per player
  (`Windows`), take-backs asked of the other player. A relay (R5) is next, on the same messages.
  **1.0.78, a roomier table** (kai's notes): the duel's row is the window's bar (`DuelBarItems`, folds in immersive;
  `TitleBar(switches = false)` on Duel), no seat bars — names, LP and the turn in the score column (`DuelLayout.score`),
  `DuelPrefs.facing` turns their cards round, the inspector is art and text with the keys pinned, verbs stand beside
  the selected card (`VerbStrip`), open piles lie in rows over the field (`DuelFrames.stripGrid`, ≥ 80 % of each card)
  and close on a press outside or a card carried out, and a press on the table releases a text field (`releasesTyping`).
  **1.0.79, the line does what it says** (Ai's playtest; `DuelFeedbackTest`): `DuelCommand.lookup` reaches your own cards
  where a player would and lists an ambiguous name instead of guessing; a zone named is where the card goes (`PLACE`,
  `MOVE`); `emz left/right` are the actor's own (`el`/`er` stay absolute for old combos); `DuelVerbs.resolve` sends a
  Normal Spell/Trap to the GY; token ATK/DEF; a search reveals; `Propose`/`Decline` for the other seat's phase asks;
  `DuelTally` and `Lock`s; house rulings (`duel/rulings.json`, `duel_ruling`); Ai reads results through its knowledge
  setting and moves only its own seat (`aiBothSeats`).
  **1.0.80, Ai in the log**: the log's box is the duel's conversation with Ai (`AiSession.MODE_DUEL`, `AiState.sendDuel`,
  `DuelLog.kt`), Ai reading the table only when cued (`cueAi`: Your move, Catch up, No response, Respond/Done, Over to you;
  `Duels.aiRead`), its thinking behind a switch (`aiThinking`), its questions in the log's foot with No response; Insert
  here (`Duels.insertPast`, `Past`, `duel_act` `at`), Save as combo from two picked lines, a finished duel logged to Prep.
  **1.0.81, private stays private**: a card Set from the hand forgets who knew it and takes a fresh veil
  (`DuelRules.hidesOnSet`; from the Deck or GY it stays known), and Ai's words in the log never name its hidden cards
  (`Secrets.redact`, the original behind Thinking). **1.0.82**: a hand is its owner's alone (`DuelSight`): a search or a
  reveal names a card in the log for that moment, never after; what was seen of a card is forgotten as it enters a hand.
  The chain resolves whole: a resolved link's card waits (`DuelState.resolved`) and the chain's Normal Spells and Traps go
  to the GY together with the last link (`DuelVerbs.resolve`, `Keep`). **1.0.83**: attacks by drag in the Battle Phase
  (onto their monster, or their hand for a direct attack; `DuelAction.Attack`, `DuelState.attacks`, a heavy arrow).
  **1.0.84**: New duel clears Ai's side of the log too, and **New topic** (the log's head) starts Ai afresh mid-game,
  keeping the table's moves (`Duels.newTopic`, `sendDuel(fresh = true)`).
  **1.0.85, response triggers**: Ai leaves watches (`duel_watch`, `core/duel/ai/DuelTriggers`) for what its hand could answer;
  `Duels.act` checks the person's moves against them and wakes Ai only on a hit (`cueTriggered`), the person's move waiting
  (Don't wait) and a watched phase change held (`Duels.held`). A duel conversation gets `AiTools.DUEL` only and its own lean
  prompt (`PromptBuilder.duel`, the duel-table skill written in), and every cue carries the table. The red team's fixes are held
  by `DuelRedTeamTest` (guest reveals, turn and window checks, take-back, rejoin, ordered wire, Ai's knowledge cap, hand
  veils, per-viewer tally).
  **1.0.86** (three feature agents beside two red teams): attack is a verb (`DuelVerb.ATTACK`, `Shift A`, `Duels.attacking`,
  `DropSpot.Score`) with a battle chip (`DuelBattle`, a suggestion only); the phone's phases fold to Next / End (`DuelLayout.phasesCompact`);
  another seat's open pile is Target-only (`DuelSeats`); Ai's cues by key (`Y`, `Shift Y`, Esc stops, 1–6 answer; `AiCue.primary`);
  undo skips talk (`DuelGame.undoMove`); the table draws for a turn (`TurnStart`, `DuelPrefs.autoDraw`; to Main 1 by itself until 1.0.93); token uids and lock
  ids are stamped on commit (`DuelIds`), dice keyed to the roll (`forRoll`); the log folded once (`DuelFolds`); `Secrets` covers
  short names and Ai's questions. The second red-team pass on 1.0.85 is in `DuelRedTeamTest` and `DuelTriggersTest`.
  **1.0.87, Command mode** (kai: "like Magnus Carlsen … I can win with just typing too and not a mouse"): a duel played by
  keys, typing or voice alone, each complete without the others. **Table notation** (`DuelNotation`: `h1…`, `m1–5`, `s1–5`,
  `e1/e2` absolute, `fz`, `gy3`; theirs `o…`, their hand in veil order everywhere — typed, drawn and labelled), verb letters
  (`s h2 m3`, `a m1 om2`), `;` for several moves (`Parsed.Many`: a later move may touch only cards the seat knew as it typed,
  and fails without saying why), questions (`DuelAnswer`, never naming a hidden card) and the chrome's words; `DuelPreview`
  (no stamping), `DuelComplete` (never in an order the seat cannot see), `Phonetic`, `DuelCoverage` (every mouse gesture has a
  typed form, tested), `TypedDuelTest` (a whole duel typed). **Keys**: arrows walk a focus ring (`DuelFocus`), keys act on the
  focus when the keyboard moved last (`Duels.keyTarget`), Enter opens the verbs, `I` coordinates. **The Spotlight** (kai's pick
  of three, `neue/duel/Spotlight.kt`, core `text/Spotlight.kt`): `/`, `Ctrl L`, a free letter or holding M opens a box over the
  dimmed table, the touched cards lit and ringed, the destination dashed; rows are sentences with their consequence; Enter
  makes it, Shift Enter keeps typing, ↑ recent (`<data>/duel/lines.txt`), Tab takes, 1–3 did-you-mean; F1 renders
  `CommandHelp`. **Voice**: hold **M** (Alt M while typing; `DeskShortcut.hold`, KeyUp in `NeueApp`) — push-to-talk
  (`DuelVoice`, `Mic`, `SpeechGate.pushToTalk`, Whisper sized to the clip by `CommandTuning`, `CommandClip` against what
  Whisper invents), `DuelSpeech` normalize → classify, **shown, then confirmed** ("yes"/Enter; `DuelPrefs.voiceConfirm`),
  read back with `DuelPrefs.speak`; the `VOICE` start step. A combo plays by name, either copy (`anyCopy`).
  `--duel-focus=`, `--duel-coords`, `--duel-spot=attack|s_h2_m3`, `--duel-spot-state=listening|answer|many`, `--duel-heard=`.
  **kai's table notes (1.0.87)**: a hand monster's effect reveals it while the chain stands (`DuelSight.onChain`) unless its
  text pays with it (`DuelCardInfo.handCost`); the Deck is backs; top / shuffled in / bottom (K, Alt K, Shift K; the Deck's
  thirds, `DeckPart`); chance is `DuelAction.Pick` (`discard random`, `random oh to gy`); a monster dropped on a monster goes
  on top (`Move.over`); the ATK/DEF plate sits inside the card's foot (`StatPlate`); the inspector's art fills its column.
  **The opening roll (1.0.88)**: two 3D dice a seat, dragged and thrown (`core/duel/dice`: `DiceSim` rigid bodies, plain
  `Double` maths so host and guest agree; `DieFaces.relabel` puts the stamped value on the face physics left up — the values
  are `DuelRandom`'s, stamped on commit with the throw in `OpeningRoll`); higher sum chooses (`GoFirst`), a tie rolls again;
  `DuelState.opening` holds the turn back until then; the log names numbers only once the dice land (`Duels.diceRolling`);
  Shift R / `roll`; `DuelPrefs.openingRoll`. `--duel-dice=rest|flying|settled|choose`.
  **The chain by keys, several cards at once (1.0.90)**: `Shift Q` resolves the whole chain (`DuelVerbs.resolveAll`); the chain
  well is a focus cell (`DuelFocus.Slot.Link`, ↑/↓ walk links) and Enter on a link offers Resolve / Negate / Target with it / Read
  (`ChainMenu`); `DuelAction.Negate` marks `ChainLink.negated` (an activated Spell/Trap to the GY with it); Y with no Ai is No
  response (`DUEL_PASS`, `DeskAction.WITHOUT_AI`). **Selection** (`core/duel/DuelSelection`): Ctrl/⌘ click toggles, Shift click a
  run, Shift Space the focus, a finger's hold starts select mode; badges "2/4", the bar over the hand offers the verbs every card
  takes (a card the eyes cannot see only `blindVerbs`), one verb one group; K / Shift K on several opens the **ordering strip**
  (top first as they will stand; Alt ←/→, drag, R random, Alt K shuffle in). Typed: `g gy1 h2 ban1`, `k gy1 gy3` (gy1 on top),
  `kb …`, `negate 2`, `resolve all`. `--duel-multi=true`, `--duel-order=top|bottom`, `--duel-chain-focus=N --duel-chain-menu=true`.
  **kai's table notes (1.0.93)**: the table draws for a turn and the phases are the player's (`TurnStart` makes the draw
  alone); no hand is seen before the opening roll is decided (`DuelSight.sees`, `beforeTurnOne`); a Main Deck card goes to the
  Extra Deck only face-up (`CardInst.extraDeck` from the deal, `DuelRules.MAIN_TO_EXTRA`; the drop and verb only for a
  Pendulum); a card facing the other seat wears its plate at its own foot; a set card wears its back at half opacity; a card
  over the Deck shrinks and fades (`overDeck`); one button puts the log and the card away together (`DuelPrefs.logShown`).
  **The hand held (1.0.94)**: the near hand's card is 1.25× the field's on the window's bottom edge with a fifth below it
  (`DuelLayouter.HAND_SCALE`/`HAND_CUT`, `DuelLayout.handCard`; the table stands on the bottom edge), overlapping and riffling
  round the card under the pointer or the keys (`DuelFrames.held`, `riffle`); the life-point pad closes on a press outside.
  **1.0.95**: their hand is held the same at the top edge (`farHandCard`, `held(fromTop)`); the card back wears the foil
  (`LocalCardFoil`); a face-up Normal Trap's Default is Set, and the word is **Default**; a card joining the chain lifts and
  shines a holo star (`drawFoilStar`, `TableCard.flash`); the opening dice rest on the S/T row in crop marks (`RestMarks`) and
  roll over the middle row (`DiceSim.INNER`).
  **1.0.96, a die and a coin at the table**: each seat's beside its Extra Deck (`DiceStage.home`); click to roll or flip from
  the corner, drag to carry and throw (`TableChance`, `fling`); `DiceSim.Shape.COIN`, `Toss`, `TossRuns`; `Dice`/`Coin` carry
  an optional `toss` stamped after the value, `DuelState.chance` holds where they landed until the next move (`DuelChanceTest`).
  **1.1.9**: put back by a carry home, a double-click, Alt R or `stow` (`DuelAction.Stow`, social); a hand's throw crosses onto their field (`Toss.reach` = `DiceSim.ACROSS`, none = `INNER`; the opening roll keeps `INNER`), folded onto what a window draws (`DiceStage.shown`); the opening dice draw over their panel; the chain well's words are `DuelFrames.Z_CHAIN`, under every window.
  **Phase C, the measured duel** (`docs/phases/C.md`): every entry carries who made it (`DuelEntry.by`, `Provenance`: person,
  Ai, guest or table; Ai's seat and knowledge; the person's eyes; for Ai's moves a hash of the `DuelView` it acted on, never
  the view), stamped on commit — **a new way into the log passes a `by`**. A finished duel is a `DuelResult` in
  `<data>/duel/records/<id>.json` (one file a duel), counted by `DuelResults` ("Ai won N of M against kai, with these
  settings"; `duel_records`). Ai is held to the guest's rules for hidden cards (`DuelReach`), holds no seat at a networked
  table (`AiTable`), and a move put into the past never re-deals a later draw (`Past.redeals`).
  **Ai vs Ai** (kai: "have two different Ai sessions play each other"; `C.md` §6): two independent sessions, one a seat
  (`core/duel/match`: `AiMatch`, `AgentPlayer`, `MatchReferee`, `MatchTable`), each with its own backend
  (`AiState.newBackend`, never the panel's cached one), history and conversation (`AiSession.MODE_MATCH`), told only its own
  seat — its `DuelView`, its own deck's guide, four tools scoped to it, `DuelReach` and `Secrets` — **nothing of one session
  reaches the other but the table**. The referee keeps the roll, turn, windows, priority and chain, bounds each cue, says
  every pass, ended turn and forfeit in the log; a match is watched live on its own table (`DuelMatches`, `Duels.spectating`:
  the person's moves refused), API connections only, never networked; a finished one is a `DuelResult` of kind `ai-vs-ai`
  with each seat's connection and model, counted apart. Ai World's duel tables are a sandbox for scripts (kind `scripted`,
  never a record). **Its law** (the red team, 2026-10, `C.md` §7b, `MatchFairnessTest`): `MatchLaw` lets what only an effect
  does happen only while a seat resolves its own link (battle in its Battle Phase), each player resolves their own link, a
  player's note names its author, a limit is won on life points, and a seat's conversation is **append-only** (a new page
  when long, never an edited history — Opus 5.5 refuses an edited one). The other seat is asked on summons, attacks and
  phases (`Windows.FULL`, `end` through the End Phase), and an activation's targets and words join it (`MatchCommunicationTest`).
  **Shortcut at the table** (Phase D step 2, `D.md` §5¾.14): the Shortcut window is the table's `Chooser`, asking by replay
  (`ShortcutAsking`; words and placement `text/ShortcutWindow`, `PositionGlyphs`; `DuelShortcuts` part, `DeskScope.SHORTCUT_WINDOW`),
  handed written effects at `Duels.writtenEffects` (`FxSamples` in the reserved range until the library); `--duel-shortcut=which|…|declare`.
  `tools/shoot.sh --page=duel --duel=two --duel-play=true [--duel-replay=N] [--duel-facing=true] [--duel-select=near]`;
  `--duel-match=dialog|live|over` for Ai vs Ai.
- **Present** (1.0.70, `06`, `Ctrl 6`, `NEUE.md` §4o; kai: deck profiles for YouTube creators, "a slideshow
  presentation creator that's animated and interactive … record in app using a webcam"): `core/present` is
  the model (`Presentation`, `Slide`, one flat `Element`, `DeckFocus`, `DeckSnapshot` — the deck kept inside,
  so edits to the deck never break a take), `PresentCodec` (forgiving per slide and element), `Themes`,
  `SlideLayouts`; `stage/DeckStage` tells a deck three ways (Spotlight, Slides, Build-up) as pure frames keyed
  per copy, glided by `StageTween`; `stage/WebcamLayout` gives the zone and the room it leaves (STAGE-anchored
  elements re-flow round it); `play/` (`Builds`, `CompiledShow`) and `edit/` (`PresentEdits`, `EditHistory`,
  `Transform`, `Snap`, `Align`, `RichText`, `SlideClip`) are shared by the editor and, from 1.0.71, Ai.
  `neue/present`: `Presentations` (the holder, lazy in `NeueHolders`), the editor, `SlideCanvas`, `PropsPanel`,
  `play/PresentStage` and `PresenterConsole` (the second screen on the desk). **Slides are content**: colour,
  gradients, rounded shapes and shadows live in `present/paint/SlidePaint.kt` and `SlideColors.kt` only (the
  law test's `slideAllowed`). Stored as files in `<data>/present/` (synced and backed up), never the database.
  Keys: `DeskScope.PRESENT_EDIT` and `PRESENTING`; mouse and finger: `PresentMouse`/`PresentTouch`. Android
  lies down on Present. **1.0.71**: modules (`present/modules/Modules`: siding, matchups, performers — by
  hand —, tournament, shoutouts with an uploaded or pasted logo, odds, ratios, tech, combo, get the deck,
  decklist; `Slide.module` remembers how, and Refresh keeps what was `edited` by hand; `neue/present/ModuleData`
  gathers the app's data), export (PDF, pictures, a YouTube thumbnail), and **Ai builds it**: `present_state`,
  `present_edit` (ops applied by `core/present/ai/PresentWriter`, the editor's own `PresentEdits`, one Undo a
  batch; create, add_module, refresh_module in `neue/ai/AiPresent`), `present_view` (`PresentReport.check`,
  in words), the `deck-profile` and `slide-design` skills, and **Build with Ai** (`PresentBrief`,
  `AiSession.MODE_PRESENT`). **1.0.72**: **Master UI is the default look** (the `paper`/`ink` themes, named
  Master UI and Master UI Dark: Inter only, 4.5 : 1, `Theme.flat` — square, no shadows or glows, honoured by the
  painter; `PresentPrefs.startTheme`/`themeChosen` so 1.0.71's stored Arena no longer wins); the other looks
  are options (New dialog, **Style ▾**, the Theme tab, with a Flat switch); **Restyle** hands the look to Ai from
  the person's words and an optional picture (`RestyleBrief`, `AiSession.MODE_RESTYLE`, skill `restyle`; look only,
  never content; **Undo restyle** via `Presentations.restyleBefore`). **1.0.73**: geometry is sane wherever it
  enters (`Geometry.sane` on decode, paste and Ai's writes; a canvas box over a stage placeholder lands as
  written) and the painter never asks `Constraints` for more than `MAX_MEASURE`/`MAX_LAYOUT` (kai's crash,
  `SlidePaintTest`). **1.1.13, recording a take** (`docs/present/RECORDING.md`): record what happened, draw the video
  afterwards — the presenter's events (`TakeLog` on a pausable `TakeClock`), the camera (MJPEG `camera.mkv`) and the
  microphone (`audio.wav`), the presentation frozen beside them; Render replays the events through `StageView` (the
  presenter's own drawing) offscreen with the camera in its zone (`RenderPlan`, `CameraFit`, jvmMain `TakeRenderer`).
  `core/present/record` is the logic; `neue/present/record` the holder (`Presentations.takes`), the bar, the Takes and
  Camera-and-microphone dialogs; `platform/Capture` the seam (desk: JavaCV + bytedeco's **LGPL** FFmpeg, no transitives,
  one platform's natives per installer, never `-gpl`; Android: `canRecord = false`, the seam for CameraX/`MediaCodec`).
  `EncoderPick`: the OS's H.264, else VP9 + Opus, always MP4 — **never the bundled OpenH264**. Takes live in
  `<data>/present/<id>/takes/` and **never sync or back up** (`TakePaths.syncs`); `NeuePreferences.record` is
  device-only. `-Dneue.camera=synthetic` stands in for a camera; `TakeRenderTest` renders a real MP4.
  `tools/shoot.sh --page=present --present=demo …` photographs it (`--present-mode=restyle` the dialog,
  `--present-record=setup|countdown|bar|paused|takes|rendering|render` recording).

- **Ai World** (1.0.97, `08`, `Ctrl 8`, `NEUE.md` §4r; kai: "a free environment to build using coding tools … that the
  user can see and watch live"): Ai's own computer. `core/world` is the model (`World`, `Board` — its kind kept as its
  word, `Board.type` — `WorldEvent`, `WorldCodec`, `WorldPaths`), the boards (`ShowSpec`, `WorldChart`, `WorldGraph`,
  `GraphLayout`), the maths (`WorldStats`), the one door scripts use (`WorldApi`, with `WorldHost` = `neue/world/WorldSnapshot`,
  plain values for a script's thread), the prelude (`ygo.*` in JavaScript, `ygo.py`) and **the instruments**
  (`Instruments`: engineered, tested studies run in one step — reach for one before writing a script, and write new ones
  to its standard; `docs/world/INSTRUMENTS-REDTEAM.md`). **JavaScript** is Rhino **1.7.15** (never 1.8+: Android has no
  `jdk.dynalink`/`java.beans`), interpreted and shut in (`JsRuntime`, jvmMain, `JsRuntimeTest`); **Python** is the desk's
  own process (`WorldPython`, expect/actual), off until the person allows it — `WorldPrefs` is device-only and
  `AiSettings.INTERNAL`, so Ai can never turn it on. `neue/world/Worlds` is the holder (lazy, `h.world`; one run at a
  time, off the main thread; Ai's code typed into the editor), `AiWorld` Ai's tools (`world_state/new/write/read/run/tool/show`),
  `MODE_WORLD` and the `ai-world` skill; `WorldPaint.kt` is the World's one file allowed colour. `<data>/world/` is
  synced and backed up; the `WORLD` start step asks about Python on the desk. `tools/shoot.sh --page=world --world=demo`.
  **Since 1.1.14 it is a desktop** (`docs/world/DESKTOP.md`, kai: "a simulation of a computer desktop"; 1.0.97's panes and
  the boards canvas are gone): `core/world/desk` is the model (`Desk` + `DeskOp` through one reducer, `desk.json` by
  `DeskCodec`, `FocusPolicy`, `DeskTidy`, `AvatarPilot`/`AvatarPath`, `WorldIcons`, `WorldNotices`), `neue/world/desk/`
  the shell — `WorldDeskState` (the owned part of `Worlds`: every window change goes through `apply(DeskOp)`; Ai through
  `arrive`, never past the focus policy), `WorldDeskPage` (icons, plate, windows), `WindowFrame` (ink title bar in front,
  3 dp keep-out, no shadow; recede at 45 %), `Taskbar` (Ai's cell is the face on this page: no `AiBadge` here),
  `Launcher.kt`, `WorldPhone` (one app at a time, a dock), `IconPaint` — and `neue/world/system/` the shell's own Files,
  Editor (Take over) and Terminal (`TerminalCommand`). **What runs inside a window is asked of `DeskApps`, the one seam**
  for the app host (Browser, Thoughts, Instruments, Library, Ai's apps). **Ai's avatar moves in `DeskAvatar.kt` alone**
  (kai's waiver; `MasterUiLawTest.movementIsNamed` refuses animated offsets and translation anywhere else in
  `neue/world/`): its pose a plain array read in `offset {}`/`graphicsLayer {}`, its loop only while unsettled, asleep the
  still `AiMark`. The desktop's arrows and Enter are `worldDeskKey`, never `DeskShortcuts`. `--world-desk=…`,
  `--world-avatar=…` photograph it. **It shows what it is doing, on itself** (`DESKTOP.md` §5.7): a plate of a sign and
  a few words travels beside it, its face is the work's, and a square ink ring breathes while the person is wanted — all
  decided by core's `AvatarStatus` (fed by `AvatarTarget.doing`, `WorldDeskState.ran` and `AiNow`), drawn in
  `DeskAvatar.kt`; `--world-status=…` photographs it.
- **Shootout** (1.1.2, `09`, `Ctrl 9`, Phase S stage 2, `NEUE.md` §4t, `docs/phases/S.md`): hands judged one at a time, every card
  rated with its range. `core/shootout/bench`: `Bench` (canonical cards, roles from the groups, sided strata only with **both**
  plans — else *waiting*, never game-one hands), `ShootoutRun` (a session; the picker at its tuned settings, `STOP` ±5),
  `ShootoutResults` (every number opens its trials, `Behind`), `ShootoutWords` (keys 1–5 best to worst, the phone's swipe).
  `core/shootout/store`: `<data>/shootout/<deck>/alone.json` and `<opponent deck>.json`, an append-only versioned
  `ShootoutLog` keeping Ai's fields (`judge`, `sawAi`, `ai`) and plan fingerprints from day one (`OldDataTest`); synced,
  backed up, deleted with the deck. `neue/shootout/Shootouts` (lazy, `h.shootout`) runs every fit off the frame thread and
  writes each answer as it is given. Keys `DeskScope.SHOOTOUT`; mouse and finger `ShootoutMouse`/`ShootoutTouch`.
  `tools/shoot.sh --page=shootout --shootout=demo --shootout-target=matchup --shootout-view=trial|results`.
  **Stage 3, Ai learns to judge** (`core/shootout/teach`, S.md §6½, `NEUE.md` §4t): what Ai is handed with each hand —
  the **rubric** (`<deck>/<matchup>.rubric.md`, reviewed like the guide, numbers through `Evidence.judge`), the **example
  bank** (`Similarity`: cards, roles, turn, the opponent's interaction; only the person's answers given *before* the hand)
  and the model's `predict` — is `JudgeBrief`; Ai answers through `shootout_judge` in a request of its own (`judgeHand`,
  API connections, never the person's answer). **Ai's answers are trials of their own** (`judge: ai`, `of`, `mode`, what it
  was shown on `AiVerdict`) and **each kind of answer is its own judge** in the fit (`Bench.PERSON`/`AI`/`SEEN`: noise, a
  wide lean and a per-card reading of its own), so a biased judge never moves the ratings. **Trust**: agreement within one
  step, counted only on held-out pairs (`JudgedPair.heldOut`), per `HandKind`, 80 % Wilson ranges; the gate opens a kind when
  the range on the hands Ai was sure of clears the person's bar; audits (1 in 3, then 1 in 10) close a kind on two misses
  beyond its range; a deck change re-earns from a short set (`Trust`). The four ways to teach are `ShootoutTeach` (calibration
  set and exam, apprentice and its one question, supervised with `Space`) and the interview (`MODE_RUBRIC`, skill
  `shootout-interview`; judging is `MODE_SHOOTOUT`, `shootout-judge`). `ShootoutTrustSimulationTest` proves it on a simulated
  judge. `--shootout-teach=supervised|question|calibration|solo|exam|trust|rubric`.
- **Study a course** (kai, 2026-10, `NEUE.md` §4v): Ai (and so Chessy) learns a guide someone wrote — a Metafy course —
  unattended. The person pastes its address in Fine Tuning and logs in once; the study runs in **the person's own Chrome
  or Edge over the DevTools Protocol** (`neue/browser`, profile `<data>/browser/`, never synced or backed up), a step a
  conversation (`MODE_COURSE`, `CourseTools.forStep`, a `StudyRun` in the coroutine context so `AiHost` answers for the
  study, never the panel; a CLI connection gets an MCP server of the step's own, `ownMcp`), resumable from `ai/courses/<id>/course.json` (`StudyQueue`), at a person's pace (`HumanPace`),
  never past `BrowseGuard` (no typing, buying, posting, forms or other hosts), its guide writes reviewed when the person
  is back. A number read in someone's words is `QUOTED` (`Evidence.QUOTED_TOOLS`): written "(per <author>)" or refused.
  Video chapters are played muted in that browser, never downloaded: captions first, else the page's own sound
  (`captureStream`) through the desktop's Whisper (`VideoListening`), pictures at each new scene (`KeyFrames`, `course_frames`). **DuelingBook replays** a chapter links to (1.1.41) are read from
  the replay page in that browser — the body the page itself received after DuelingBook's Turnstile check
  (`WebSurface.openReceiving`), never requested by the app — kept whole and in words (`DbReplays`), noted one at a time
  (`study-replay`) and counted together by the app (`course_replays`, `ReplayStats`), so a pattern in the guide carries a
  computed count. **No spending cap; a part at a time** (1.1.46, `StudyChunks`): notes a run of sections at a time, the
  playbook a kind at a time, the guide a few chapters at a time, each part saved as it ends and a stopped one set back to
  its start (`notesMark`, `partBegun`); a limit or the network is waited out (`StudyRetry`, `Course.retryAt`), and the exam
  keeps each answer as given (`ExamLog.sitting`). A new long step of a study goes in parts too.
- **The Lounge** (1.1.44, `docs/LOUNGE.md`, `NEUE.md` §4x; kai: friends duel at kai's tables from a browser at
  labrynth.info): kai's computer opens a door (`LoungeServer`, desk only) behind a passcode (PBKDF2 hash in `SecretStore`)
  and Cloudflare's tunnel; rooms, seats, held seats, swaps and watchers are `core/duel/lounge` (pure, tested), each
  room's duel a `RoomTable` sending every viewer only its `DuelView` as the LAN table's `Wire`. **The page is `:guest`**
  (wasmJs), drawing **`:table`** — the duel table and kit, shared with Neue pixel for pixel through `TableHost`; it may
  depend on `:core`, `:builder` and `:table` only, and **nothing in their web build may import an npm module** (no
  bundler). Releases pack it with `-Pneue.loungePage=true`; `tools/lounge/smoke.sh` walks it in Chromium on CI.
  **Ai in the Lounge** (L5, kai's per-room switch, `LoungePrefs.aiDailyTokens`): a seat (`RoomAiTurn` says when it is
  owed a move; each Ai seat an `AgentPlayer` on `MatchTable`'s tools for its seat alone) and the log's conversation
  (`LoungeTalk`: the public table for the room, a seat's for a private ask) — **each its own session, sharing nothing**.
  **1.1.47**: people chat in the lobby, the room and the log (`ChatStrip`, `TableHost.roomChat`); the page reconnects by
  itself under its token for the seat's three held minutes; an ended duel is a `lounge` record (`DuelResults.lounge`, never
  Ai's against kai); *Test the address* (`/api/ping`, `LoungeProbe`) says what is wrong between Cloudflare and the door.
- **Mastery: the playbook** (1.1.43, `NEUE.md` §4w; kai: "beat a human player from the guide … notes thorough"): what Ai
  learns of a deck is data beside the guide — `core/ai/playbook` (`Play`: line, decision, card, matchup, principle,
  ruling; sources and confidence; `PlaybookEdits` refuses an entry too thin to play from), `ai/playbooks/<deck>.json`
  (+ `.md`), reached by `LearnTools` (`playbook_*`, `course_search`/`course_open`) in chat, every Fine Tuning mode, a
  course study and at the table — **an Ai vs Ai seat only for its own deck**. A course is read in numbered sections with
  coverage checked (`Sections`, `notes_coverage`), its cards read first (`course_cards`), noted again at `CourseDepth`
  when shallower, consolidated, then distilled with no room limit; **a fifth of its replays is the exam
  (`ReplayExam`), never shown to a study or a tool**. The table plays at `DuelPrefs.aiStrength` (Strong by default) and
  each cue carries the playbook for the position (`DuelGuide.playbook`, `DuelPosition`). A new way of learning a deck
  writes the playbook (`DeckSkills.PLAYBOOK_STEP`). **1.1.44**: a chapter with a video is always watched (`settleVideo`;
  it was skipped beside 150+ words of text); `StudyMonitor`/`CourseMonitor` show what a study reads beside what it
  writes, live; **the exam** (`core/ai/exam`: `AuthorExam`, `ExamLog`; `CourseExams`) asks the author's turns from the
  held-out replays, as the author saw them, and grades Ai's plays against theirs. Unattended work runs through `studyStep`.
- **Numbers carry their proof** (1.0.98, the evidence ledger, `core/ai/evidence`): a percentage, odds or probability in a
  guide entry or a book chapter must be one a tool computed in the conversation or the person said (`Numbers`,
  `Evidence.judge`), else it is refused unless marked "(estimate)"; its proof is kept in `ai/evidence/<deck>.json`
  (`Ledger`), marked stale when the deck changes and checked again (`recheckGuide`). The fact-check's "ok" is held to what
  it looked up (`FactCheck.ground`). Rulings read Konami's OCG Q&A from YGOrganization first (`YgoOrg`), always with the
  OCG caveat, then Yugipedia. A new place Ai writes numbers people rely on goes through the same check.
- **The goldfish** (Phase D step 4, `D.md` §5, §11, `NEUE.md` §4u): it trusts `FxTrust` (UNTESTED/WARNED used, BROKEN/UNSUPPORTED/MISSING
  inert, open warnings named, "played by you" marks in `effects/played.json`) — never "verified only"; hands are `GoldfishHands` (riffle by
  `forRoll(seed, k)`), counts never depend on threads (`Goldfish.run`), and a line's percentage in a guide needs a `goldfish` source
  (`Evidence.lineClaims`, `Proof.library`). A new store of goldfish data goes under `effects/goldfish/`, deleted with the deck.
- **Effects are written only for cards the person asked for** (Phase D step 2, `D.md` §3.1, `NEUE.md` §4u): the ask is always
  the person's click (Write its effect, Write these in the Effects app `Alt 8`, Write its cards, Write on `fx_request`'s card —
  `fx_request` only offers), kept in `<data>/effects/asked.json` (`FxAsks`; `go` refuses Ai, `gate` refuses Ai's write to an
  unasked card), and starts `AiSession.MODE_EFFECTS`; the cost is said before (`FxCost`, `Prices`) and kept after. A new place
  that asks goes through `NeueHolders.go`.
  **The goldfish in the app** (step 4, agent (c)): the Effects app's Goldfish tab (`GoldfishPane`, `Effects.goldfishRuns`,
  targets edited by `TargetDraft`, every number's hands by `GoldfishBrowse`); a hand opens as an unsaved replay
  (`Duels.openGame`, `Replay.kept`) — never write one the person did not Keep.
- **Card truth** (1.1.0, Phase B, `docs/phases/B.md`, `NEUE.md` §4s): **count copies by card, never by passcode** —
  `CardIdentity` (an alternate artwork is the same card); a new count of copies or "does the deck hold X" goes through it.
  **Legality is region and date too** (`Legality`, from each card's `formats`/`tcgDate`/`ocgDate`, schema 4); missing
  release data is *unknown*, never illegal, and so is a region YGOPRODeck lacks unless Yugipedia agrees (`RegionNames`, 1.1.1). **Banlists by date** are `core/cards` (Yugipedia's lists, a device-only cache in
  `<data>/banlists/`, never synced) behind `BanSource`; a new place that checks copy limits takes a `BanSource`.
  **The builder checks `DeckRules`** (1.1.1): a chosen day's list or Genesys, set from `NeuePreferences.legalAsOf`/
  `genesys`/`genesysCap` by `legalityRules`; read `state.rulesInForce`, never `state.format` alone, for "legal in …".
  **What is played is `PlayChoice`** (1.1.8, `TCG | OCG | Genesys` in the bar): Genesys is `genesys = true` with the
  region kept TCG, never OCG beside it (`setPlay`); a card the builder draws takes `state.marks` (`CardMarks`: a failing
  card's inverted ✕, Genesys points, else the list's mark), and is given its `section` only when drawn in the deck.
- **Outside text is in an envelope** (1.0.97): every tool result read from outside the app goes through `Untrusted.wrap`,
  and `web_fetch` through `UrlGuard`; a new tool that brings outside text in must do the same. The red team on Ai's
  learning and real-world intelligence, its research and the roadmap: `docs/AI-INTELLIGENCE.md`.

## Where the big holders' code lives (1.0.91, the cleanup)

- **`Duels`** (`table/…/neue/duel/Duels.kt`, in `:table` since the Lounge) keeps the table's state, `act`/`verb`, focus, the chain, the line runner, undo and
  saving; its parts are owned classes beside it — `DuelNetTable.kt` (`DuelNet`), `DuelReplays`, `DuelRulings`,
  `DuelSpotlightState`, `DuelAiWatch`, `DuelOpening` (turn opening and dice), `DuelPicking` (selection, ordering) — and
  every moved member stays on `Duels` by forwarding, so `duels.x` reads the same everywhere. Add new state to the part
  it belongs to, with a forwarder only if outside code needs it.
- **`NeueHolders`**: the keyboard is `neue/NeueKeys.kt` (`onKey`, `run(action)`, …), the palette and phone menu
  `NeuePalette.kt`, the window's pointer watcher `shell/WindowPointer.kt`, zen's clock `ZenClockwork.kt` — extensions
  on `NeueHolders`, its state still members.
- **`AiState`**: `AiVoice.kt`, `AiContext.kt`, `AiTuning.kt`, `AiConnections.kt`, `AiHelpers.kt` — extensions; the state
  stays in `AiState.kt`.
- **One list per fact**: the duel's verb letters are `core/duel/text/DuelLetters.kt` (the typed words, the completion,
  the help and the window's `DuelWiring`/`DuelRails` maps all read it; `DuelLettersTest` holds it to `DeskShortcuts`),
  and the pile words `PileWords.kt`; `DuelVocabularySnapshotTest` pins every table they feed.
- **Imports, not inline names**: write `import …Name`, not `com.kaiharimoto….Name` in code, except for the short names
  that clash (`Wire`, `Slot`, `Showcase`, `Block`, `Lock`, `Spoken`, `Heard`, `Card`, `Spotlight`, `Mode`).

## Keeping it fast (1.0.92, `NEUE.md` §4q)

kai's ask: "run the best while maintaining the graphics quality". A performance change keeps **the same pixels**, and
proves it (a raster comparison, a memo-against-old test, studio shots). The rules the red team left:
- Nothing requests frames while nothing moves (`AiAvatar` sleeps to its next step). Something always on screen paces
  itself: Chessy steps every frame only while she is `lively`, else every ~65 ms (`ChessyPacingTest`); each frame asked
  for is the whole window repainted. Measure with the palette's *Show
  frame times* (`FrameStats`, `FrameMeter`).
- A modifier on every card is a `Modifier.Node` (`CursorNode`, `onPointer`), never a keyless `composed {}`; never read
  layout-written state (bounds) in composition.
- Pointer, drag, animation and z-order values are read in `offset {}`/`layout {}`/`graphicsLayer {}`/draw
  (`CarriedCard`, `zIndexAsPlaced`); deck cards are `key`ed.
- The foil keeps its path and brush (`HoloCache`, `BrushMemo`); name masks are alpha-only in a bounded `SizedLru`.
- The duel's lookups are cached (`DuelCatalog.cached`, `Secrets.Redactor`, `DuelCheckpoints`, `DiceRuns`).
- Heavy work is off the frame thread: the pool download, searches (debounced), backups (`ZipFile`), sync, Prep's writes.

## Stored data outlives versions — a rule

kai's progress lives in what older builds wrote; every release must read it. So:
- A preference is a field with a default; never rename one (keep the old name with `@SerialName`) or remove
  one; documents are read with unknown keys ignored, so an older build reads a newer document too.
- New `NeuePreferences`/`AiPrefs` fields are sorted into `SyncedPrefs.SYNCED` or `DEVICE`, and described in
  `AiSettings` or listed `INTERNAL` (two tests fail otherwise).
- The SQLite schema only rises, with a migration `MigrationTest` proves; the `.ydkx` payload's unknown keys
  pass through byte for byte.
- Every stored shape an older version wrote is held in `core/compat/OldDataTest`; a release that changes
  what is stored adds the old shape there, in the same commit.
- Backups are read through the app (`Backups`), never a database copy, so an old backup restores into any
  later version.

Play mode is rebuilt inside Neue as **Duel** (`07`, 1.0.74–, `NEUE.md` §4p), from scratch on `core/duel`;
the classic play stage's rules below still apply to it.

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

**Siding was redesigned from scratch** (1.0.35, Format), not built on the legacy
designs: its own `siding` key, the legacy `sidingPatterns` only read. Shootout mode
is still deferred. `YdkCodec` keeps round-tripping the opaque `#ydkx-extended`
payload (`DeckGroupsCodec` and `SidingCodec` each replace only their own key and
preserve every other byte-for-byte).

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
