# The 3DS port

kai asked for the app on a New 3DS, sideloaded as a `.cia`, redesigned around
the touchscreen, with the fishbowl as the demo — and then, on being told the
outer cameras are a stereo pair, for **marker AR**: point the console at a real
desk and the duel table is on it.

This document is what `docs/classic/LOOP.md` is for the play stage: the shape of the
work, the decisions already taken, and a ledger of what has been tried. Read it
before touching anything under `3ds/`.

---

## 1. It is a rewrite, and saying so is load-bearing

There is no Kotlin/Native or JVM target for Horizon OS. 3DS homebrew is
devkitARM, libctru and citro3d, in C, and the PICA200 has **no programmable
fragment shader** — only a fixed-function fragment-lighting stage driven by
lookup tables. Nothing in `app/` runs here. What crosses over is the *arithmetic*
and the *design*, and the whole risk of the project is that a hand translation
of eleven thousand lines of arithmetic silently becomes a fork.

So it does not cross over by hand-copying. It crosses over **proved**:

- `GoldenVectorExportTest` in `:core`'s jvmTest source set sweeps each ported
  function over a fixed grid and writes the answers to `3ds/test/vectors/`.
- `3ds/test/mt_test.c` compiles `3ds/src/core/` with the *host* compiler and
  asserts against the same files.
- The vectors are **committed**, and CI diffs them. A change to `BoardLayouter`
  that moves a zone shows up in review as a diff in a vector file, next to the
  change that caused it.

`3ds/src/core/` therefore may not include a libctru header, ever. That single
rule is what keeps the port's foundation checkable in one second on any machine,
and it is the direct analogue of `:core` compiling in a sandbox where `:ui`
cannot.

**The suite has been falsified, which is the only reason to trust it.** Drifting
`GAP_FRACTION` from 0.11 to 0.1104 — 0.4%, invisible on a screen — produces 3,952
failures. Swapping two entries in the slot array while leaving every rectangle
correct produces 89, because the insertion order is what `slotAt` resolves
midline ties by. Both mutations were run and both were caught.

---

## 2. What the port deletes

The happiest finding. `StagePlane.flatten` / `raise` / `project` / `unproject`,
`CarryHeight`, `MatInput.handQuad`, `Defocus` and `Outset` exist because Compose
offers a `graphicsLayer` and a 2-D canvas and no way to draw a real perspective
scene. citro3d has actual matrices, and the bottom screen is orthographic on
purpose.

So three of the six load-bearing play-stage rules in CLAUDE.md — everything
about the finger being on the felt while the card is in the air — do not get
ported. They become *vacuous*. `MtMatPoint` is a 0..1 fraction, the bottom
screen's hit test is a multiplication, and the class of bug that produced "a hand
whose top half would not respond", "drops landing a third of a card short" and
"a monster set into the spell/trap row" has no surface to occur on.

`core/mat/MatDesk.kt`'s ten lanes go the same way. A stylus is one pointer, so
the router collapses to one lane — but its *rules* still port, because they were
never about fan-out: the grace window, `stillHolds` and `rebase` are all still
needed the moment a gesture outlives the frame it started on.

## 3. What the port reuses, and one surprise

Ported directly: `PlayField`, `BoardCard`, `DropIntent`/`DropTargets`,
`DropCommit`, `SetPosition`, `BoardLayout`, `HandFan`, `PileFan`, `YdkCodec`,
`Springs`, `PosePhysics`, `CardIndex`, `EffectMatching`.

Reimplemented on the GPU with the Kotlin as the spec: `CardSolid`, `Turned` and
`Rot3` become vertex buffers; `Shading`, `StageRig` and `StageLighting` become
citro3d fragment-lighting LUTs — the PICA200 does per-pixel Lambert,
Blinn-Phong *and a Fresnel LUT* in hardware, which is very nearly `StageRig.lit`'s
graze-gated rim for free. `WoodGrain`, `FeltWeave` and `AgX` become **baked
textures**, rendered by `:studio` at build time. That is the answer to having no
fragment shader: the procedural surfaces become PNGs, produced by the code that
already draws them.

The surprise is `core/render/Homography.kt`. `squareToQuad` was written so a
leaned card's picture would land on its own drawn corners; it is *exactly* the
primitive marker pose estimation needs, and it inverts for fiducial tracking
without modification.

---

## 4. The phases

Each ends in an installable `.cia` built by CI.

| | | state |
|---|---|---|
| **P0** | Skeleton, Makefile, RSF, CIA packaging, CI | in progress |
| **P1** | The domain in C, proved by golden vectors | in progress |

Ported and under vector so far: `mt_types`, `mt_board_layout`
(`BoardLayouter.solve` and `slotAt`, all seventeen slots in the order ties
depend on), `mt_drop` (the intent vocabulary, `SetPosition`, **and
`DropTargets.resolve` with `FanHome`**), `mt_spring`, `mt_ydk`, `mt_random`,
`mt_playfield`, `mt_handfan`. **50,129 checks.** Still to port: `DropCommit`,
`PileFan`, `CardIndex`, `EffectMatching`.

Hysteresis cannot be swept. Stickiness is a function of what the last answer
was, so most of the resolver's vectors are **paths**: the pointer walks a line
and each answer feeds back as `previous`, which is what a real drag does.

**Every unit is falsified before it is believed**, and it has earned its keep
five times — every single time, the hole was in the *test*, not the port. Mutating `PlayField` four ways caught two and missed two — the
script had hardcoded instance ids that a shuffle invalidates, so `stack`,
`attach`, `counter` and `takeFromUnder` were all quietly *refused* and 25,945
checks passed without executing the code they were written for; and piles
recorded ids only, so `lift` clearing counters and `toBanish` setting face-down
were invisible. A third round found `reorderHand`'s index adjustment untested
because the hand was too short for the branches to differ.

The resolver then did it twice more. `INCUMBENT_BIAS`'s cap at half the gap
passed cleanly when removed, because the fan home sat nowhere near a zone
centre — and that cap is the fix for two bugs in opposite directions, biting
only when the two targets are nearly on top of each other. And the fan home was
hardcoded on the C side rather than carried in the file, so the first path that
used a different one read as a port bug.

A suite that has never failed has not been shown to work. One case is
documented as **unreachable** rather than left looking tested: `nearestZone`'s
first-minimum tie-break, because `resolve` takes a `MatPoint` and the round trip
through the mat's fractions means two distances equal in real arithmetic come
back differing in the last bit.

`mt_random` is the one that is easy to think optional. `PlayField.riffled`
writes Fisher-Yates out by hand precisely so a seed deals the same hand
everywhere — and that argument does not survive a port unless the *generator*
comes over too. An identical shuffle fed by `rand()` is deterministic, correct,
and a different deal. So `kotlin.random.Random` is reproduced exactly: XorWow,
its seeding from a Long, its 64 discarded outputs, its rejection loop.

**P2 has a renderer.** `3ds/src/gfx/` draws the stage with real geometry — a
card is a six-faced solid, the camera is `Mtx_LookAt` from one of the four
seats, and the two eyes differ only in a projection built by
`Mtx_PerspStereoTilt` with the interaxial taken from the 3D slider. The desk,
the felt and the zones are separated in z rather than by paint order, because
there is a depth buffer now and coplanar surfaces z-fight.

Two decisions in it are worth knowing. `CARD_THICK` is 0.020 against a real
card's 0.0129 — nearly honest, where `CardSolid.pileDepth` on the tablet has to
exaggerate *and* saturate because every z there is multiplied by `sin(tilt)`.
And culling is off: a card is a thing you look at from both sides, the depth
buffer already hides what is behind, and a face wound the wrong way in code
written without a device is invisible geometry that looks exactly like a matrix
bug. Turn it on once there is a console to check it against.

Still missing from P2: the fragment-lighting LUTs (`StageRig` is not ported
yet — the per-face shading is a constant in the mesh), the baked wood and felt
textures, the card back, and the room.

| | | |
|---|---|---|
| **P2** | citro3d, card solids, lighting LUTs, baked textures, stereo | in progress |
| **P3** | The bottom-screen control surface — **playable, the demo** | started |
| **P4** | Marker AR: camera, CV, pose, stereo compositing | |
| **P5** | Card art over wi-fi | |
| **P6** | Search and deck editing | |
| **P7** | *(deferred)* QTM head tracking | |

## 5. The bottom screen is a control surface, not a view

320×240, one stylus, and the rule is that it **never shows a perspective view**.
Card text is unreadable at 40×58px regardless, so nothing is lost by admitting
it: a status line, the board as an orthographic map with the ten zones ruled in,
four pile tiles, and the hand as a linear strip along the bottom. `HandFan`'s
lean existed to sell three dimensions; in two, a strip is the honest form.

Buttons carry what buttons are good at, and this is a *third idiom* for
`MatGuide`'s "every gesture ships in every language" rule. **The table is
`3ds/src/core/mt_input.h`**, not this document: hold Select on the console and
the guide you get is rendered from that array, so a binding that changes and a
guide that does not cannot happen. It is in `src/core/` and therefore carries no
`KEY_` constants — the button is a *string*, and the switch that turns one into
an action is in `main.c` where the console headers already are.

Two things about it are worth saying out loud.

**The stylus line is the whole table.** Every destination on this board — a
zone, a stack, the graveyard, the deck, a particular gap in your hand — is
reached by dragging, because they exist inside `mt_drop_resolve` and nowhere
else. Nothing else in the list is a destination.

**While the stylus holds a card the shoulders are modifiers, not camera.** L
sets it face-down, R lays it sideways, ZR tucks it under as material — and that
last one makes `DropIntent.Attach` reachable on the console before it is
reachable on the tablet, where `docs/classic/TABLE.md` still records it as a domain
operation waiting for an idiom that is not already spoken for. On a 3DS one is
not: a stylus cannot be a second finger, so the shoulders are free the moment
the drag owns the gesture.

### The app must use the core it has, and one thing checks that

The conformance suite proves `src/core/` matches `:core`. **Nothing in it proves
the app uses `src/core/`** — and for one release the app did not. `main.c` called
21 of ~95 ported functions: it hand-rolled the drop as a rectangle test, so the
graveyard could not be reached by dragging at all and the readout was computed
separately from the commit; and it re-derived the hand with a *different step
cap*, left-aligned against the ported 0.74 centred. Both are bugs the tablet
already had and fixed, reintroduced in the one place the vectors cannot see.

Two tools sit in `3ds/tools/`, both run by the `conformance` job:

- **`check-app-layer.sh`** — a tripwire, and labelled as one. It fails on a
  constant the core owns appearing in the app layer, on the *shape* of a
  re-derivation, and — the stronger half — on a core function that used to be
  called and is not any more, which is the drift that leaves nothing suspicious
  behind. Falsified in all three shapes; the arithmetic patterns were written
  `\(float\)` at first, which in a basic regular expression is a *group* and
  matched nothing on earth, so the check passed for the wrong reason.
- **`audit-core-use.py`** — not a gate. It walks the call graph and reports what
  is ported and not reachable from the app, so the next gap is visible rather
  than discovered. Transitive on purpose: counting direct calls only would
  report `mt_field_to_graveyard` as unreached on the very release that made it
  reachable, since the app asks `mt_drop_commit` and the commit asks that.

At the wiring it reads **75 of 95 reachable**, and the twenty that are not fall
into four groups, none of them an oversight:

| | |
|---|---|
| a card menu | `add_counter`, `bring_to_front`, `set_position` |
| the spread (`PileFan`, P3) | `field_under`, `unstack`, `detach_material`, `from_buried`, `board_slot_at` |
| deck editing (P6) | `ydk_write`, `ydk_is_ydkx` |
| spare readings | `board_bounds`, `hand_row_of`, `hand_place_of`, `hand_opening_for`, `hand_point_for`, `spring_calm`, `spring_settled`, `shuffle_extra_deck`, `to_deck_bottom`, `hand_to_deck_bottom` |

`hand_point_for` is for a fan and the console draws a strip; `spring_settled` is
the optimisation of stopping a spring that has arrived, and at forty cards on a
New 3DS nothing yet needs it.

**One limit, stated rather than papered over.** The guide's button strings are
checked against the tablet's `MatGuide`, so the two cannot describe the same
gesture with different buttons. The *binding* is not checked against the string:
`main.c` reads `KEY_L` directly, and `src/core/` may not name a libctru constant
without taking the whole conformance suite down with it. Rebinding a button in
`main.c` and not in `mt_input.c` is a drift nothing here catches.

## 6. Marker AR

`Scene` is already a persisted enum with a room contract. AR is a third room, and
**the room is reality** — a cheerful shortcut through the eleven phases of
`docs/classic/PHOTOREAL.md`.

A printable ArUco-style sheet lives in `3ds/marker/`; its printed size is a
setting, because it is what sets the table's real-world scale. The pipeline is
capture at 400×240 RGB565 from both ports (both eyes to display, left eye to
track) → 2×2 box downsample to grayscale → adaptive threshold over an integral
image → border-following contour trace → 4-corner approximation → `squareToQuad`
→ sample the 6×6 bit grid → Hamming-decode → pose from the homography with
intrinsics out of `CAMU_GetStereoCameraCalibrationData` → smooth with the ported
`Springs`. CV at 15–30fps, gyro interpolating to 60.

Marker loss is designed rather than crashed: hold the last pose, desaturate,
fall back to gyro drift, and say so on the bottom screen.

Stereo is real — left camera to left eye, right to right, the board rendered
twice at the interaxial the calibration reports. `CameraPose.axisX`/`axisY`, the
existing lens rise-and-fall, is the lens-shift half of a shift stereo rig; the
interaxial eye translation is the one genuinely new term.

---

## 7. The release track is its own, and that is not cosmetic

kai's instruction is that the 3DS build is separate from the Android one. It is:
`build-3ds.yml` builds it, `release-3ds.yml` publishes it, and neither touches
`app/`. Android releases are tagged `v1.2.3`; these are tagged **`3ds-v1.0.0`**.

The prefix is load-bearing. GitHub's `/releases/latest` hands back the most
recent non-prerelease release of *any* tag shape, and that endpoint is exactly
what the Android app's in-app updater reads — so a 3DS release arrives in front
of the Android updater whether or not anybody wanted it to. `AppVersion.parse`
is the only place the two can be told apart, and it could not: `takeWhile(
Char::isDigit)` read `3ds-v1.0.0` as **major version 3** with a pre-release of
`v1.0.0`. Newer than any APK this app has ever shipped, permanently, carrying no
APK to install, and shadowing the real Android release published before it. A
segment must now be *entirely* digits; `AppVersionTest` pins it.

**And the parser fix alone is not enough, which the first release proved.**
`AppVersion.parse` protects a device that *has* it, and the device that most
needs protecting is the one still running the build from before it. Shipping the
Android patch first is necessary and is not sufficient: v1.2.62 published at
16:40 and `3ds-v1.0.0` took the repo's "Latest" from it at 16:45, five minutes
before any tablet could plausibly have installed the fix. A device on v1.2.61
then asks `/releases/latest`, is handed the 3DS release, reads it as version 3,
and reports "a newer release exists but carries no APK" — including when what it
is being blocked from is the very build that would stop it happening. The
updater cannot climb out of that on its own; the APK has to be sideloaded once,
by hand, from the releases page.

So **every 3DS release is published as a pre-release**, unconditionally, and
that is why `release-3ds.yml` has no input for it. `/releases/latest`'s own
documented definition is "the most recent non-pre-release, non-draft release",
so the flag is the one mechanism whose exclusion is a contract rather than an
inference from how the "Latest" label behaves. It costs the console nothing —
a `.cia` is installed by hand with FBI and nothing on a 3DS reads that endpoint
— and it trips `UpdateChecker`'s own skip-pre-releases rule as well, so the two
guards fail independently. The release notes say why the label is there, so it
does not read as "unfinished".

The gate that stops a version going backwards now also allows a deliberate
re-publish of the newest one, behind a `republish` input. The publish step had
always carried a branch for re-uploading over an existing release and that
branch was unreachable, because the gate rejected the only version that could
reach it — which is how a wrong `prerelease` flag on a published release became
something with no way to fix it.

## 8. Two numbers that are permanent

The same class of decision as the Android `versionCode` floor.

- **`UniqueId` is `0xFF4D7`, chosen once in `3ds/app.rsf`, and never changed.**
  A different UniqueId is a *different app* to the HOME menu: a second icon, and
  the first one's data orphaned with no way to reach it.
- **The CIA title version only ever goes up** (`3ds/VERSION`, major.minor.micro),
  or FBI reads an install as a downgrade and refuses it.

**And the trap that costs a day:** a `.3dsx` runs inside the Homebrew Launcher's
host process and inherits its permissions; a `.cia` gets exactly what its own
RSF grants. A service missing from `ServiceAccessControl` is not a build error
and not a useful runtime error — it is a black screen after install. Every
service the app will ever call is listed in `app.rsf` *now*, `qtm:u` included,
years ahead of the code that calls it, because adding one later costs everyone a
reinstall.

---

## 9. Risks, and what is actually known about each

**1 · TLS on a 2011 console. Partly answered, and the answer is not comfortable.**
Both `images.ygoprodeck.com` and `db.ygoprodeck.com` return **301 to HTTPS on
plain HTTP**, measured. There is no unencrypted path, so the on-console download
either negotiates TLS with a modern CDN or does not exist.

What is *not* known is whether it can. The development sandbox routes through an
egress proxy that terminates TLS — the certificate observed there is the proxy's,
not ygoprodeck's — so the cipher suites and chain the real endpoint demands
cannot be measured from it. **This must be tested on hardware**, and the test is:
build a `.cia` that does one `httpcOpenContext` against
`https://images.ygoprodeck.com/images/cards_small/89631139.jpg` and prints the
result code, first with certificate verification on and then with
`httpcSetSSLOpt(SSLCOPT_DisableVerify)`.

Because the answer can invalidate P5's design, card art is built behind a source
interface from the start, and a host-side bake (a desktop tool writing tiled
textures to the SD card) stays alive as the guaranteed fallback. A thumbnail is
about 25KB, so a deck is ~1.5MB either way.

**2 · Core 2 on New 3DS. Answered, and it was the wrong knob.** The CV pass
wants a core of its own, and the way to get one is not `AffinityMask` — it is
`CanAccessCore2: true` in the exheader, which `app.rsf` now sets. What remains
for P4 is measuring whether the tracker actually holds 15–30fps there, not
whether it may run there at all. Fallback is unchanged: CV on core 1 at 15fps.

**3 · The CIA toolchain is the most fragile thing in the build.** devkitPro
ships neither `makerom` nor `bannertool`, and bannertool's original upstream was
taken down — `3ds/tools/fetch-cia-tools.sh` uses the `carstene1ns` fork and
builds it from source rather than taking the prebuilt, which needs a newer glibc
than some devkitPro images carry. That script is deliberately the *only* place
this dependency lives.

**4 · CV robustness** under desk lighting. Mitigated by the gyro fallback and an
honest marker-loss state, not by hoping.

**5 · The card database must be streamed, never parsed into RAM.** Thirteen
thousand cards with printed text is ~20MB. A sorted-id index with offsets, and
`fseek`.

**6 · Frame budget** — three render targets plus camera capture plus CV.
Measured in P2 before 60fps is promised. 30fps on the top screen is acceptable
and the bottom screen can run at half rate.
