# CLAUDE.md

This file guides Claude Code (claude.ai/code) when working with this repository.

## Quick Start

**What this is:** kai's master tool — a Yu-Gi-Oh! deck building and tournament
preparation tool, being built into an immersive, 3D-feeling deck building
simulator.

**The project is the cross-platform app in `app/`.** Kotlin Multiplatform +
Compose Multiplatform, targeting Android (landscape tablets, Samsung Tab
S11-class; and now phones in portrait) and desktop (macOS/Windows/Linux) from
one codebase. See `app/README.md` for architecture and build instructions.

**`legacy/kai master tool.html` is the archived original** — the single-file
web tool the app replaces (~36k lines, plain HTML + Tailwind + vanilla JS).
It is kept as a reference for the original vision and its feature set (siding
patterns, shootout mode, sandbox board, PDF export, the prismatic hover
effects). Open it in a browser from inside `legacy/` if you need to study it.
Do not develop it further; maintenance only if something in it is truly broken.

**Key locations:**
- `app/core/` — pure Kotlin logic (models, deck editing, groups, hand odds,
  motion physics, 3D geometry and lighting, haptics, board domain), all tested
  in commonTest
- `app/ui/` — Compose Multiplatform UI (androidTarget + jvm("desktop"))
- `app/androidApp/`, `app/desktopApp/` — platform entry points
- `legacy/` — the archived original tool and its assets
- `ydk/`, `lab.ydkx` — sample deck files (YDKX = YDK + `#ydkx-extended` JSON)

---

## Seeing the play stage without a tablet

`:studio` draws the real `PlayScreen` to a PNG headlessly — real theme, real
dependencies, real card art, a frame clock advanced by hand, no window and no
GPU. It is off by default and ships in nothing (`-Pmastertool.studio=true`), so
CI builds exactly the three modules it always did.

```
tools/shoot.sh                                    # the default contact sheet
tools/shoot.sh --shots=desk-night-seated --keys=n # one shot, after a fresh deal
tools/shoot.sh --budget=120                       # what a frame costs
tools/compare.py shots/before shots/after         # what actually moved
```

A shot's name is its parameters: `desk`/`minimal`, `day`/`night`,
`overhead`/`table`/`seated`. Two runs are bit-identical — the deal is seeded —
so every pixel that moves is a change in the code. It needs the Android SDK
(`:ui` has an `androidTarget`); point `ANDROID_HOME` at one and the script
writes `local.properties` itself.

**`shots/` is the loop's scratch; `docs/shots/` is the published strip.** The
first is gitignored and regenerated constantly. The second is the three pictures
at the top of the root README, written by dispatching
`.github/workflows/shots.yml` — which renders at the reference 1600×1000, reads
the studio's *log* as well as its pixels (a card pool that did not sync and a
deck that did not import both render a pretty, empty table and exit 0), refuses
two shots that came back identical, and resamples to half size before
committing. **Dispatch it on the `claude/**` branch, before the fast-forward,
never on `main`** — a commit CI puts on `main` is a commit the next
fast-forward is rejected by. It refuses that itself, but the reason is worth
knowing.

**`docs/TUNING.md` is the in-app tuning panel.** Long-press the life-point
number on the play stage: twenty-nine numbers — camera angle, focal length, where
it is aimed, how close you may sit, the defocus falloff, the hand, the card lifts,
and the room's own furniture — live, persisted, and exported as JSON. Read it
before changing any of those numbers by hand, and before adding a knob:
**nothing that re-solves the board may go on the panel.** The room is on it and
does not break that rule — the desk, the wall, the window and the lamp are read
inside `Scenery.of`, which is remembered against the *already solved* layout, so
moving them cannot re-key `pointerInput(layout)` under a live gesture.
`ROOM_ABOVE`, which really does re-solve, stays off it.

**`StageTuning.DEFAULT` is kai's own export, and there is no second copy of it.**
The document used to mirror a set of named constants, and `Scenery` carried eight
of them for the room — a record of what shipped that nothing read, so by the time
the defaults moved every one disagreed with the room the app draws. They are
deleted; `RoomTune`'s defaults are the room, and `StageTuningTest` is the one
place the numbers are written twice, which is that test's whole job.
`tools/shoot.sh --tune=x.json` still replays any export headlessly. Because
preferences serialise with `encodeDefaults = true`, changing a default changes
nothing on a device that has ever opened the panel — what it changes is a fresh
install and the panel's **Reset**.

**A knob hands back a knob and a number, never a document.** A slider's track is
a `pointerInput`, its gesture coroutine outlives the composition that installed
it, and a callback closing over the whole `StageTuning` therefore wrote the
document as it was when the panel *opened* — so for three releases every slider
silently reset every other slider. The read-modify-write belongs inside the
preferences transform, where the live document is; `StageKnobsIndependenceTest`
pins the half of that core can see. The same Compose fact is written on
`ui/dnd/DragSource.kt` and has now cost two debugging rounds.

**`docs/LOOP.md` is the autonomous loop that uses it**: six steps an iteration
takes, five gates a change passes, and a ledger of what has been tried. Read it
before doing fidelity work on the play stage.

**`docs/PHOTOREAL.md` is the road, and it is now phased.** Nine stages of
engineering toward a photographic table, and — since kai asked for the play stage
to reach a AAA first-person game — eleven **phases** that ship them, in
§"The phases those stages are shipped in". One phase is one session and at least
one signed release. The three questions the document used to end on are answered
in the section above that table, along with a fourth it asked back: the POV seat
becomes the seat the stage opens at, the Desk scenes may be graded like a
photograph, the room gets built and gets shadows, and **`AAA.md` #34 is refused**
— the card's printed picture stays a 168×246 thumbnail and every bit of the
fidelity is bought in the object around it.

## Development Workflow — read this before changing anything

1. **All logic goes in `:core` with commonTest tests.** Run locally:
   `cd app && ./gradlew :core:jvmTest`. This is the only module that compiles
   in a default Claude environment.
2. **`:ui`/`:androidApp`/`:desktopApp` compile ONLY in CI** (Google Maven is
   unreachable from most sandboxes; `settings.gradle.kts` auto-skips those
   modules). Pushing to `main` or any `claude/**` branch runs
   `.github/workflows/build-app.yml`, which is the compile check and APK
   build. **Never trust a piped local exit code** — grep the Gradle output for
   `BUILD SUCCESSFUL`.
3. Every gesture ships with both a touch idiom and a pointer/keyboard idiom.
   Keyboard shortcuts are data in `core/input/ShortcutTable.kt` (layer-aware;
   the help sheet renders the table, so it can never drift).
4. **Finish by shipping it.** See *Ship Every Change* below — the user tests on
   the tablet, so work that is only on a branch is work they cannot see.

## Ship Every Change — standing instruction from the user

The device is the only place this app can really be judged, and a debug APK
cannot install over the signed one. So the default end of a piece of work is a
release, not a branch. Standing permission is granted for all of it — do not
stop to ask.

Every time, in this order:

1. Push the work to the `claude/**` branch and wait for `build-app.yml` to go
   green on all three jobs (core tests, desktop, Android APK). A red build is
   the one thing that stops the rest.
2. Fast-forward `main` onto the branch and push it. Releases build from `main`.
3. Read the current version — `get_latest_release`, or the newest `v*` tag —
   and dispatch `release.yml` on `main` with the **next patch** number
   (v1.2.3 → v1.2.4). Bump the minor instead only when the user asks, or when
   the patch would reach 100: the versionCode formula gives 1.2.100 and 1.3.0
   the same code, so the patch digit must stay under it.
4. Confirm the release actually published — the tag exists and the
   `kai-master-tool-<version>.apk` asset is attached — before telling the user
   it is ready. The signing gate can fail the run *after* the APK builds, and
   "dispatched" is not "shipped".

Note in the release notes when a build changes stored preferences, the schema,
or the deck-file payload, so a surprise on the tablet has an explanation
waiting.

Two things to say out loud rather than silently skip: a change that cannot
reach the device (docs, this file, tests only) does not need a release, and a
version number, once published, is spent forever — there is no reissuing one.

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

## The play stage is now a room — kai's brief, and what it suspends

The DESK scenes are pursuing **photorealism**: *"the entire environment (desk,
window with sun outside, millennium puzzle, floor, bed, bookshelf, etc)
completely photorealistic"*, at the fidelity of a driving simulator. The
constraints below that argue with that goal are suspended **for those scenes**
— true black, colour-only-as-meaning, and no decoration in the room. `docs/LOOP.md`
§"The mandate" is the authority and carries the full list, including what is
*not* suspended (`Scene.MINIMAL`, "nothing idles", the release contract, and a
plain-draw fallback behind every shader).

The playmat is gone: the desk is the playing surface and the zones are routed
into the wood. The board also declines a fifth of the stage's height
(`Scenery.ROOM_ABOVE`) so that there is somewhere for the room to be — it costs
a fifth of the card, on every device, and that trade is the reason a window,
a wall and anything behind them can be seen at all.

## Design Identity (locked in with the user)

**`docs/DESIGN.md` is the handbook — read it before drawing anything.** It holds
the palette, the type scale, the spacing scale, the motion specs, the component
rules and the anti-patterns, with the reasoning attached. What follows here is
the short version; where the two disagree, the handbook is right and this should
be corrected.

- **Swiss + material finish: sharp white on true black.** "Material" means ink,
  card stock and light — never Material Design, whose components are used and
  restyled on the way in.
- **Swiss + prismatic: sharp white on true black.** Colour appears only as
  *meaning* (card types, legality) or as *light* — the six-hue prismatic ramp
  (`MasterToolPalette.Prism`) shown as chromatic-aberration fringes on things
  being interacted with. Light mode is the exact inversion, same colours.
  Primitives live in `ui/theme/Prismatic.kt`; use them sparingly — fringing
  everything reads as decoration, fringing the thing under your finger reads
  as light.
- **Cards are the one exception, and it is the user's.** `drawPrismaticInset`
  is the original tool's card border, restored at kai's request: a *foil*, not
  a fringe — two hues rather than six, inset four per cent inside the card
  rather than glowing outside it, and swung by where the card is rather than by
  a timer. It is on every card *face*, in the builder and on the play stage,
  because that is what it was — and never on a back, which is a sleeve's front
  edge and made every pile in the room a neon rectangle. It is
  `UiPreferences.prismaticCards`, on by default and one menu item away from off,
  because "sparingly" is a house rule and this is a matter of taste somebody is
  entitled to disagree with.
- **The card back is kai's own artwork**, bundled at
  `ui/composeResources/drawable/card_back.png` and drawn `FillBounds` because it
  is 750×1050 against a card's 59×86. It replaced two *drawn* backs and the
  preference that chose between them; the argument for drawing rather than
  shipping still stands for **Konami's** back, which is why `cardBackUrl` is
  still the way that one gets onto a device without this repository carrying it.
- **Typeface: Archivo** (bundled, OFL; expanded cut for display moments).
- **Tactility lives on things the user interacts with** — tilt, lift, sheen,
  quiet card sounds, haptics. No decorative clutter, no skeuomorphic
  inefficiency. The physical feeling to capture is handling a single card.
- **Motion = springs.** `core/motion/` (`Springs`, `PosePhysics`) with the
  one-`withFrameNanos`-loop recipe (see `EasterEgg.kt` and `ui/play/StageCard.kt`
  for the sanctioned perf pattern: bulk state in plain lists, per-object state
  read inside `graphicsLayer`).
- **A shader seam, since the target became photographic.** kai's brief is the
  fidelity of a driving simulator, and a surface stops reading as a fill colour
  only when its normal varies *per pixel*. `ui/gpu/StageShader.kt` is an
  expect/actual over Android's `RuntimeShader` and desktop Skia's
  `RuntimeEffect`. It compiles to null rather than throwing — Android below 33,
  a refusing driver, a bad shader — so **every caller keeps a drawing that works
  without one**. See `docs/DESIGN.md` §6 for the four rules, including the sign
  convention that made the first one invisible.
- **Real geometry, no engine.** `core/render/` is a small, tested renderer:
  `Rot3` (the same Euler angles `graphicsLayer` rasterises with), `CardSolid`
  (a card has a thickness and six faces), `Turned` (a solid of revolution: a
  profile of rings, spun, capped, and genuinely posed — everything round in the
  room comes off it), `Shading` (Lambert + Blinn-Phong,
  with a specular pool that *moves*), `Shadows` (cast by projecting every
  corner along the light), `StageRig` (one key, one fill, one eye). It reaches
  the screen through `graphicsLayer` — which is a real perspective-correct
  quad — and a canvas, joined by `StagePlane.flatten`. **No 3D engine, ever:**
  none reaches KMP common code and all of them would cost the desktop target.
- **Height is notation, and `sin(tilt)` is its exchange rate.** Every z on the
  stage reaches the screen multiplied by it, so a physically honest forty-card
  deck is four pixels and a diagram. `CardSolid.pileDepth` exaggerates and
  saturates; a pile's side is ruled into its cards and leans, because those two
  survive the angles its height does not. The mat lies on a table drawn as a
  slab, because gradients on true black are a mat floating in a void.
- **The stage has rooms, and one of them is an exception.** `Scene.MINIMAL` is
  the handbook's stage and does not change. The **Desk** scenes — a desk lit by
  a window in the day and a lamp at night — are a different contract, argued in
  `docs/DESIGN.md` §11 rather than smuggled in: they may hold decoration, they
  are chosen rather than arrived at, and *nothing idles* in either of them. Two
  rules hold the whole thing up. Nothing in a scene may stand over the mat,
  because cards are sorted in the composable tree and the room is painted
  beneath all of them. And night never lowers the rig's ambient below 0.55 —
  see `Tone.veil` for the numbers — so its darkness comes from the room falling
  away rather than from the cards going dim.

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

## Roadmap State

Shipped: the full deck builder (drag-and-drop, exact consistency calculator,
per-card tactility, sound/haptics, 3D card inspect) and the freeform play stage
that replaced the goldfish screen.

Two parts of the builder are worth knowing before touching them, because both
replaced an earlier design that looked reasonable and was not:

- **Layout is solved, not negotiated.** `core/layout/DeckFit.kt` sizes all
  three panes in one pass: row widths are the input (main 10, extra/side 15),
  row counts follow from the deck, and card size is the single free variable.
  Per-pane auto-fitting against divider-dragged heights is what put cards out
  of bounds; do not go back to it. Anything the panes spend on chrome must be
  declared to the fitter or the cards pay for it.
- **The breakdown never moves a card.** The deck is a mosaic (2dp gutters) that
  cracks open only where two groups meet — `BreakdownLayout.plan` says which
  sides of a card face another group, `GridRegion` traces the blocks, and the
  group's colour is drawn solid in the space that opened. A grid index is
  always a deck position, so a drop always means insert. Assignment is a
  selection gesture: `GroupDraft` in core, tapped out on the deck itself.
  Rearranging the display to make tidier blocks was tried twice and rejected
  by the user both times; see `docs/DESIGN.md` §9.

The breakdown is now a **lens** (`core/deck/DeckLens.kt`): the partition is a
parameter, so the same machinery draws the user's roles, the deck's archetypes,
its type split, its copy counts and its banlist exposure. Every key reports its
exact opening rate (`core/hand/LensOdds.kt`), and the consistency question is
stored *with the deck* as a `HandGoal` rather than rebuilt in a sheet each time.

**There is one play surface, and there used to be two.** `ui/play/` is the
freeform table that replaced the goldfish screen: cards go anywhere, stack on
each other, and can be set face-down. Its domain is `core/board/PlayField.kt`,
and everything about *what a release means* is core and tested — `DropTargets`
resolves the finger's position to an intent, `DropCommit` carries it out, and the
indicator the user sees is that same value, so the table cannot promise one thing
and do another.

The other was **Table**, a zone board over a `BoardState` that put cards in the
five monster and five spell/trap zones. kai cut it — *"delete table mode
entirely, it's redundant"* — and it is gone, along with `BoardState` itself.
What survives it is `FieldZone` and `CardPosition` in `core/board/BoardCard.kt`,
because the zones did not go anywhere: `core/layout/BoardLayout.kt` still solves
all ten of them, the play stage still draws them and still snaps to them, and the
only thing that has stopped being true is that a card must be inside one.

Six rules the play stage would be broken without, each of which was a bug
first:

- **One arbiter for the whole mat**, in core, driven by one `pointerInput`.
  Per-card detectors let one finger start a drag on one card while a second
  starts a separate drag on another, and consumption cannot fix that after the
  fact. It is now **ten machines behind one router** — `core/mat/MatDesk.kt`
  owns up to ten `MatGestureMachine` lanes and decides, at the instant a finger
  lands, which one it belongs to. That is kai's two-handed play, and it does not
  retreat from the rule: two competing drags were a bug when nothing decided
  between them; ten deliberate drags are a feature because something does. The
  rule is one sentence — *a finger that lands on something nothing else is
  holding starts its own gesture; every other finger joins the gesture nearest
  it* — and it settles the twist, the menu, the set, the steadying hand and the
  pinch without a special case for any of them. Each machine still sees only its
  own pointers and still knows nothing about the other.
- **Fingers on a card move the card; fingers on the felt move the camera — when
  the felt is switched on.** The whole control scheme, and it has to stay sayable
  in one line. The split is made once, on the press, by `claimForCamera` when the
  hit test finds nothing; from there the gesture cannot become a drag, a peek or
  a menu. One finger orbits, two pan and pinch. **`UiPreferences.cameraTouch`
  defaults to off**, on kai's report of too many accidental touches, and locking
  it costs the table no other gesture — a felt press then dies in `PRESS`, where
  a tap can still close an open fan. The mouse keeps the wheel and the
  middle-drag either way; neither goes through the arbiter. The table has almost
  no affordances drawn on it, so it has a guide — `core/input/MatGuide.kt`, data
  rather than prose, rendered by the button on the bar and held to
  both-idioms-present by a test. The one exception is the two shuffle marks
  (`core/layout/MatControls.kt`), argued for there and in `docs/DESIGN.md` §10;
  a third needs its own argument rather than their precedent.
- **Both of its clocks report to `MatPilot`.** Pointer events and the frame
  loop each produce gesture events, and both must act on the same memory of
  what the press landed on. `onTick` called for its side effects, with its
  return value dropped, is the shape of that bug: peek and the two-finger
  menu compute correctly and then vanish.
- **The gesture belongs to the finger that started it**, but does not end the
  instant that finger lifts — the second finger of a tap is a frame behind.
  It opens a grace window instead; a hand left resting on the mat loses the
  gesture when the window closes.
- **A drag out of a pile or the hand is holding an index, and an index goes
  stale.** With two hands, one release renumbers what the other is holding, and
  it would then drop the card next door — correctly, silently, and wrong.
  `PlayField.stillHolds` is checked at the release; a gesture whose board moved
  under it puts its card back rather than guessing which card the user now means.
- **The finger is on the felt and the card is in the air, and those are two
  different places.** Everything with a height reaches the screen through
  `StagePlane`, so a lifted or leaned card is *drawn* up-table of the mat point
  it occupies — 29% of a card for one slid across the board and **71%** for one
  out of the hand, at kai's tuning. A finger unprojects at z = 0, where that
  offset is nothing. So a hand card is hit-tested against the quad it is
  **drawn** as (`MatInput.handQuad`, the same `CardSolid.face` expression
  `StagedCard` builds its homography from — a leaned card is a quad at two
  heights, which is why `StagePlane.raise` cannot fix that one), and a drop
  lands where the card is **drawn** rather than under the finger
  (`core/board/CarryHeight.kt`, and `Carry.landing` beside `Carry.at`). That one
  offset was three separate bug reports: a hand whose top half would not
  respond, drops landing a third of a card short, and a monster set into the
  spell/trap row — where `SetPosition` then correctly answered *upright*.
- **A gesture holds a card, not a place, and a place goes stale.** A
  `DragOrigin` into a pile or the hand is an index; one release renumbers it, and
  the guard that caught that turned it into a *refusal* — the card left the air
  and nothing happened, which is exactly what "I take two cards out of the deck
  and letting one go puts the other back" is. `PlayField.rebase` asks where the
  held instance is **now**, within the place it came from and never across one,
  and still answers null when the card has genuinely left. Four render sites that
  also indexed by position ask by identity, or a carried card visibly becomes a
  different card the instant the other hand commits.
- **Two targets that sit on top of each other cannot be separated by size.** A
  spread is laid out over `layout.field` — the seven-by-three grid itself — and
  its cards are drawn lifted, so the felt footprint of the slot a card came out
  of lands a fifth of a card from a monster zone's centre. "Put back" outranked
  everything with a half-card catchment, which is 91% of the zone catchment it
  was outranking, so a searched card could not reach the board and the hysteresis
  made it terminal. `FanHome` separates them by **history** — a put-back is a
  change of mind, so the gap is not a target until the card has been carried
  clear of it — and rank 0 separates them again by asking whether a zone is
  pulling harder. Either alone leaves aims lost. `PutBackTest` drives real
  `PileFan` output through the real projection over every slot of three spreads
  rather than asserting anything about one hand-picked point.

**The gesture vocabulary, after kai's eight changes.** A tap on a card
*declares* it (a name in the bar, a chime, a small bump) and no longer brings it
to the front, which is in the menu; a tap on a pile or a stack still spreads it.
Two fingers dragging a card **set** it face-down, and where it lands decides how
it lies — `core/board/SetPosition.kt`, monster zones sideways, spell/trap zones
upright, and off the zones the card's own category. The two-finger *pile* drag it
replaced cost nothing: one finger has always moved a whole stack
(`PlayField.moveOnMat` slides a card and its pile), so `DRAG_STACK`,
`LiftedStack`, `stackModifier`, the shift-drag and `Carry.whole` are all gone
with it. A hold opens the card reader (`ui/play/CardReader.kt`) and it stays up
until dismissed. `DropIntent.Hand` carries a gap index, so a hand can be
arranged. And `DropIntent.Cancel` is reachable at last: drop a card back on the
gap it came out of and it goes back — aimed at that one slot rather than at the
whole fan, because a spread covers `layout.field` and "inside the fan wins" would
make the board undroppable while a pile is open.

**The hand and the spread open where a card is going.** `core/layout/HandFan.kt`
holds a `HandRow` — one entry per drawn place, a hand index or null for a place
held open — and the pose, the hit box, the insert index and the indicator are
four readings of it rather than four reconstructions. They *were* four
reconstructions, and two of them disagreed: the hand was drawn with a place
standing for the card in the air and measured as though the row had closed up,
so every gap the caret named was 0.37 of a card width from the gap the card went
into. `insertAt` counts drawn cards rather than dividing by a step, which is the
only form that inverts a row with a hole in it, and the gap it names is the gap
the row is already holding open — a fixed point, swept in `HandFanTest`, because
a row that re-asked and got a different answer would flicker every frame.

`PileFan.spread` takes a `FanParting` and opens a window where the carried card
will land, paid for first out of the row's slack inside `layout.field` and then,
when there is none — a forty-card deck leaves 0.18 of a card — out of the row's
own overlap down to `MIN_STEP`. Two things stay still on purpose: the slot the
card came out of, because `FanHome` wrote its position down at the lift, and
`FanSpread.bounds`, because it decides whether a press belongs to the fan or to
the camera and a footprint that breathed would drop the gesture that was making
it move. `MatInput.fanOf` is the one place a spread is solved and the renderer is
handed its answer.

**Any pile can be searched.** Tap one and it spreads across the board —
`core/layout/PileFan.kt` solves the geometry, and the cards never change size
because a search shows you the cards that are on the table. Take one by
dragging it anywhere (every existing drop rule applies unchanged) or tap it to
send it to your hand. The deck fans in its own order and closing it shuffles,
which is correct by the rules. This was `docs/TABLE.md` §3's "hole", and the
whole of it at the input end was that the hit test returned
`DragOrigin.Pile(slot, 0)` — the domain had taken an arbitrary index since it
was written. **Ten to a row, four rows at most**, so a deck spreads the same way
whether it holds forty cards or sixty.

**And `StagePlane.raise` is why you get the card you pointed at.** `flatten` is
how everything with a height reaches the screen, and nothing inverted it — so a
spread pile, which floats about half a card above the felt, was *drawn* tens of
mat pixels from the coordinates it was *hit-tested* at. The finger arrived on
the felt; the cards were not on the felt. `raise` is that inverse in closed
form, exact for anything flat at one height, and it is the identity at z = 0, so
everything lying on the mat is pointed at exactly where it was computed. The
hand carries the same offset and is deliberately left alone: its cards lean, so
their footprint is a quad at two heights rather than a rectangle at one.

**The camera is a camera, and the one limit left on it is arithmetic.** kai's
brief was *"as realistic as possible … there shouldn't be a limit I want complete
freedom and control"*, and four things came out of it. `docs/TUNING.md` is the
authority; the short version:

- **`cameraDistance` is the focal length in pixels** — `project` divides by
  `cameraDistance − depth` and Compose's `graphicsLayer` does the same thing —
  so nothing but the lens may be on it. It carried `CameraPose.distance` for six
  releases, which made the field of view swing from 130mm to 9mm across the
  envelope and the keystone move as `1/distance²`. Every dolly, and every *tilt*
  (the pitch moves `minDistanceAt`, which moved the distance), was a dolly zoom.
- **The floor is linear and there is nothing in front of it.** With the distance
  off the lens, `minDistanceAt` collapses from a square root to a line, and the
  flat `minDistance` that used to sit in front of it is a twentieth. What remains
  is `clearance`: past the lens plane `project` clamps, and a clamp cannot be
  inverted, so `unproject` stops agreeing with it and every pile edge and
  airborne shadow tears. That one is not taste and is not going anywhere.
- **`CameraFit` moved to the seat buttons.** Run on every release it *was* the
  "locks me out" — pinch in, let go, and the table slides away. On `1 · 2 · 3 · 4`
  (`StageCameraState.sitAt`) it is the way home from anywhere free flight can
  reach, and it is still handed `layout.field` rather than `layout.bounds`: the
  hand band along the bottom is the first thing a push-in costs, and treating
  that as a reason to refuse capped the camera at 1.47 against a floor of 1.05.
  Which is also why a *seat* that aims low has to be measured against `bounds`
  itself — the fitter will not catch a chair that puts your hand off the screen.
- **The lens can be aimed as well as the camera, and that is a fourth seat.**
  `StagePlane` had three points and two names: `targetX` is the mat point the
  spin, the tilt and the divide all turn about, and `centreX` was both the middle
  of the glass *and* where that pivot lands. Splitting the second out is
  `axisX`/`axisY` — a view camera's **rise and fall** — and it costs one
  subtraction, because a shift moves the *image* and not the *eye*: the divide is
  still centred on the pivot, so `unprojectAt` and `flatten` stay closed form. It
  moves neither `minDistanceAt` nor `eyePoint`, both pinned, so aiming changes
  nothing about how close you may sit or where a single highlight lands.
  It exists because **three of the seats were the same seat**. `tiltDegrees` is
  measured off the table's normal, so elevation is ninety minus it: Overhead is
  85° above the felt, Table 69°, and Seated — "the player's own chair" — **56°**,
  which is standing over a table rather than sitting at one. `StageSeat.POV` on
  `4` is at 32°, draws a card exactly as wide as Seated does, and gives the room
  half the picture instead of a fifth. What it costs is card *height*
  (`cos(pitch)`, about a third), which is what looking at a table from a chair
  does and is why it is a fourth seat rather than a change to the third — the
  hold-to-read card reader carries legibility instead.
  A shift does **not** buy the room screen area on its own, and the plan that said
  so was wrong: the board fills the stage vertically, the hand band's bottom edge
  is already at 99.8% of the glass at Seated, and there is no slack to aim into.
  Room comes from sitting lower; the shift is what makes sitting lower fit.
- **The camera can be aimed.** `CameraPose.panX/panY` move the *target* rather
  than the picture, so the vanishing point stays in the middle of the glass and
  `unprojectAt` keeps its closed form — the KDoc that refused a pan was
  describing a different one. The mat's layer needs a `transformOrigin` and a
  translation, which is the whole cost. Two fingers pan; a middle-drag or
  alt-drag does on a pointer.

A flick coasts (`CameraRig.coast`), on rates per *second* so it runs down over
the same seconds at 60Hz and 120Hz, and any press catches it. And at eighty
degrees of pitch the table's horizon is on the glass, so `StagePlane.belowHorizon`
holds a finger above it down to the last row that is looking at the table —
otherwise `unprojectAt`'s guard hands back the camera's own target and a tap on
the wall grabs the middle of the board.

The room is eighteen pieces: a floor, the desk, four wall pieces around a
window opening, the pane, seven of joinery around it, and four for the lamp.
`docs/AAA.md` #62 was the brief —
*"there is a room past it. Dark, out of focus, present."* — and #16 and #17 are
what made day and night two rooms rather than two colour grades: a `Light` may
now have a **position**, a **radius** and a **distance**, so shadows diverge
from a point, the far corner of the table is dimmer than the near one, and a
window's edge is soft where a lamp's is hard.

Four things about it are load-bearing, and three of them were bugs first:

- **A light with no place is a no-op to the bit — and so is a flat face with no
  material.** Every placed-lamp term returns a literal before touching a float
  when `position` is null. Two later terms outgrew that exact test and reach the
  same end another way: `StageRig.wash` now also fires for a face carrying
  `Face.smooth` normals, because a turned solid is faceted at noon exactly as
  badly as at midnight, and `gleam` is gated on the surface's own `Gloss` rather
  than on a position at all. What keeps them inert is that a slab has no smooth
  normals and the minimal key has no size. Which is why `GoldenStageTest` went
  green without being re-recorded across three releases that changed how every
  surface in the room is lit. (It was re-recorded in the fourth, and only a
  third of it: taking the camera's distance off the focal length moves every
  pose that is not at `HOME_DISTANCE`, which is exactly one of its three.)
- **The lamp's height is solved, not chosen.** The shipped night key's
  horizontal-to-vertical ratio *is* how long a night shadow is per unit of
  height, so the lamp stands where the ray to the middle of the table has
  exactly that ratio. The lamp is then *drawn* at about two-fifths of that
  height — 2.40 to one, measured, against kai's mast — because an honest desk
  lamp is off the top of the picture. The foot and the light are exact and
  `SceneryTest` pins the compression against `RoomTune().lampMast`; nobody can
  measure a stem. (This read "five to one" until it was measured, which is what a
  number in prose with no test under it is worth. The `Scenery` constant it used
  to be pinned against is gone — see below.) **The lamp's own scale does not
  touch its light.** `lampScale` is how big the fixture is *drawn*, and coupling
  the source's angular radius to it made a stouter lamp cast daylight.
- **Paint order is a topological sort over a separating axis.** Sorting boxes
  by nearest-corner depth puts a 511px wall after a 241px lamp and paints it
  over the top. `ScenePainter` finds an axis that separates each pair — and
  never asserts an order for a pair no ray connects: axes that disagree mean no
  answer, an eye inside the gap means no answer, and pieces that do not overlap
  on screen are not compared. All three matter, and each was a cycle. The order
  itself is a topological walk, because adjacent swaps cannot sort a partial
  order at all.
- **The felt is lit by the rig now.** It was a gradient aimed by a *direction* —
  the one surface that could disagree with the shadows on it. Its highlight is
  the lamp's mirror image, so it slides toward you as you sit down.

One thing was built and cut in the same release, and the reason is worth
knowing before rebuilding it: a **patch of daylight on the desk**. It was drawn
as a multiply, which is correct — but the rig already lights the whole desk from
the window's direction, so the surface was at full brightness before the patch
went on and every ring of it came out *darker* than the wood it landed on. A
stain, not light. Fixing it means splitting the day key into sky and sun and
shading the room with the sky alone, which changes the brightness of the entire
day room. `docs/AAA.md` #61c has it.

**The room is turned on a lathe.** `core/render/Turned.kt` is the third mesh
primitive, beside `CardSolid.face` and `CardSolid.slab`: a profile of rings, spun
about the pose's own vertical, capped. Everything round in the room comes off it
— the lamp's foot, its stem, its shade and its finial. Four things about it are
load-bearing:

- **It is genuinely posed.** A slab hangs its body down the *stage's* z whatever
  the pose says, because that is a fact about the felt a card rests on. Every
  vertex here goes through `Rot3.place` instead, so a turned solid may be tipped
  without shearing — which is the "posed box in core with its own eight corners"
  `docs/DESIGN.md` §11 said a tumble would cost, arriving for another reason.
- **A facet's normal is solved from the profile, not crossed from its corners**,
  which keeps it exact where a band ends in a point and one edge is gone. The
  exact answer is short by `cos(π / sides)` in its radial half, because a chord is
  shorter than the arc it spans; `TurnedTest` measures the normal each face
  carries against the vector area its corners really have, so dropping that
  cosine is a red build rather than a highlight one facet out.
- **The side count is derived and always even.** A segment's sagitta is
  `r·(1 − cos(π/n))` and half a pixel is where the flat stops being visible.
  Even, because an even polygon is centrally symmetric and so has a bounding box
  centred on its own axis — and the room sorts, measures and lights every fixture
  through that box.
- **A facet may carry the normals of the curve it stands for** (`Face.smooth`,
  trailing and null for every card, pile and box). Shading twenty wedges by their
  own twenty normals is correct arithmetic about the wrong object: it steps at
  every seam and a round foot reads as a paper fan.

`ScenePiece` gained a `mesh` and a `lining` to carry all that, both trailing and
null by default. The rule for `mesh` is the painter's algorithm rather than
convexity as such — a piece's faces are ordered by the depth of their own
centres, so the faces the camera can *see* must come out right that way. Convex
satisfies it for free; the lampshade's **shell** is the one argued exception.

**Nothing stands on the left of the desk, and that is a deletion rather than a
gap.** A Millennium Puzzle stood there for two releases — a four-sided turn,
propped back, with a torus bail, that turned a third of a turn when tapped — and
kai cut it: *"delete the puzzle entirely."* `docs/LOOP.md` iterations 12 and 13
are the record. Four things it established are the pattern a second prop
inherits, and all four are why the machinery it used is still here:

- **A prop is a pose, not a `ScenePiece`.** Furniture is solved twice a day and
  remembered; a moving thing cannot live in a value that is deliberately
  recomputed. It was a pure function of two numbers the screen owned, so what was
  drawn, what was touched and where it stood could not come apart.
- **It must be convex, or arrive as convex parts.** Inside one piece the renderer
  sorts faces by the depth of their own centres, which is right for a convex body
  and meaningless across two solids; the bail sat behind the pyramid it stands on
  the first time it was drawn as one list of faces.
- **And it must share no volume with anything else in the room**, which is what
  `ScenePainter`'s separating axis needs *between* pieces.
- **The camera claims the gesture last.** A prop is a third thing that is neither
  a card nor the felt, after a shuffle mark and the inside of an open fan, and
  like both it has to be taken out before `claimForCamera` — asked last of the
  three, because the table's own affordances outrank an ornament beside it.

**The room has a material now, as well as a colour.** `StageRig.lit` is ambient
plus lambert plus a graze-gated rim and has no specular in it, so until this the
brass and the cloth and the painted timber differed in colour and in nothing else
(`docs/AAA.md` #67's unfinished half). Three terms sit *beside* it rather than
inside it, because `GoldenStageTest` records what `lit` returns: `gleam`, an
additive Blinn-Phong highlight whose lobe is **widened by the source's own
angular radius** so a window gives a broad whisper and a bulb a hard glint;
`spill`, which grades a lit fixture down its own height because the bulb sits low
in the shade; and `Surface.gloss`, so a finish stays a fact about a material.

Next for the room: `docs/AAA.md` #61d is the shadow question — nothing in the
room casts one, on purpose, and that is a decision to revisit as a set rather
than to bolt onto one object.

Next: attaching as material is reachable in the domain and not yet by gesture
(`DropIntent.Attach` needs an idiom that is not already spoken for). After
that: the deck **showcase** stage, and play polish (deal-origin projection,
stack shuffle/cut). `docs/TABLE.md` §5 is the ordered list of everything else,
and its first three — tokens, a scrubbable history, card text on the peek —
are now the cheapest things on it.

## The builder has two arrangements, and one rule picks between them

The app was `android:screenOrientation="userLandscape"` until kai asked to use
it on a phone. `core/layout/Posture.kt` is the whole decision: **a window taller
than it is wide is `TALL`, everything else is `WIDE`** — no dp threshold, because
a threshold has to be re-chosen for every new device and the aspect ratio is what
the two arrangements actually turn on. A portrait *tablet* gets `TALL` too, and
that is right rather than incidental.

`WIDE` is the tablet builder and is untouched. `TALL` is `ui/deckbuilder/
TallBuilder.kt`, and one sentence generates all of it: **the pool goes where the
thumbs are.** It docks along the bottom, its search field sits at the very bottom
of the dock immediately above the keyboard, and the deck takes what is left
above. `docs/DEVICES.md` §6 is the authority and carries the comparison table.
Four things are load-bearing:

- **The two postures share a device, so they may not share their numbers.**
  `tallColumns` and `tallSearchColumns` are separate preferences, and
  `setColumns` takes the posture rather than inferring it — otherwise a row width
  chosen on a phone-shaped window is still there when the tablet is turned back
  on its side.
- **Portrait sizes the deck width-first and scrolls** (`DeckFitter.stack`).
  `DeckFitter.plan` solves for the width that makes the whole deck fit the height
  it has, which is the right question on a tablet and hands back a 23dp card in a
  phone's portrait window. One row width for all three sections, default six,
  because the tablet's size hierarchy between main and extra is a luxury of width.
- **Typing means the pool.** A finger in the search field takes the dock to FULL
  and the deck out of the way; letting go brings it back, but never below HALF,
  because closing the dock over the results of the search just typed throws the
  answer away. That is `PoolDock.afterTyping`, in core, with a test on it.
- **The dock's furniture is declared to the thing that positions it**, the same
  contract `DeckFitter` has with `chromeFor`: `PEEK` *is* the chrome height, so a
  bar added to the dock and not to `DOCK_CHROME` is a search field pushed off the
  bottom of the screen. And `imePadding` goes **outside** the height, not inside
  it — inside, focusing the deck-name field at `PEEK` squeezes the search field
  to nothing.

The play stage is not in on this. It rotates because the activity does, and in
portrait it draws a 360dp board with the height unused; it is landscape by
preference now rather than by lock, and a portrait stage is its own piece of work
(`docs/DEVICES.md` §5).

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
- UI on Android: the in-app crash reporter shows and shares the trace.
- Desktop: `./gradlew :desktopApp:run` (needs Google Maven access).
- Preferences are one JSON document in SQLite (`UiPreferences`); adding a
  preference is a field with a default, never a schema migration.
