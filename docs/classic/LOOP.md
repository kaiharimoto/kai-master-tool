> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

# The fishbowl loop

An autonomous loop pointed at one thing: **making the room real.**

Not the cards — the room. kai's brief, in his words: the fidelity of a driving
simulator, and *"until the entire environment (desk, window with sun outside,
millennium puzzle, floor, bed, bookshelf, etc) is completely photorealistic and
done"*. The play stage is a place now, and the cards happen to be lying on the
desk in it.

## The mandate, and what it suspends

kai has lifted the constraints that were holding the room back. Written down
here rather than left implicit, because half of `DESIGN.md` was built around the
opposite goal and a future iteration reading it cold would put them back.

**Suspended, for the DESK scenes only:**
- *True black, sharp white.* The room is lit by a window and a lamp and it is
  allowed to look like it. Black was a stage; this is an interior.
- *Colour is meaning or light, never decoration.* Wood is brown because wood is
  brown.
- *Nothing in a `SceneModel` may hold decoration.* A bookshelf is decoration.
  That is now the point.
- *No shaders.* Already lifted (`AAA.md` #99); see `DESIGN.md` §6.

**Still standing, and not up for revision by an iteration:**
- **`Scene.MINIMAL` does not change.** It is the handbook's stage, it is what
  kai falls back to, and it is the control against which the room is judged.
- **Nothing idles.** A room that breathes is a screensaver. Light moves when the
  hour moves; nothing moves because time passed.
- **The release contract.** versionCode only rises, migrations are never
  renumbered, the APK is signed with the committed key. None of that is
  ambition; it is what stops a device being bricked by an update.
- **Every shader has a plain-draw fallback.** `minSdk` is 26.

## The list, and what "done" would mean

Named objects, because "photorealistic" is not a state anything can be tested
against but *"the bookshelf exists and has books in it"* is.

| | state |
|---|---|
| Desk top | **wood**, per-pixel: ring bands, fine grain, pores, latewood gloss |
| Playmat | **gone.** The desk is the surface; zones are routed into it |
| Zones | outlines. Should be inlay or engraving, and lit as such |
| Window | **a window**: four panes, a frame, a graded sky |
| Sun outside | not possible through this window — iteration 8 has the numbers |
| Floor | a flat plane, and the room's plan: the walls stand on its edges |
| Wall | **a fifth of the screen**, and painted rather than void |
| Side walls | **two returns**, standing on the floor like the back wall does |
| Ceiling | does not exist. The room is open above |
| Millennium Puzzle | **deleted** — see iteration 13. Nothing stands on the left of the desk |
| Lamp | **a turned lamp**: brass foot, tapered stem, a shade that is a shell and is lit from inside |
| Desk clutter | does not exist. `DESIGN.md` §11 forbids it until `AAA.md` #92 and #93 exist |
| Bed | does not exist |
| Bookshelf | does not exist |
| Room shadows | nothing in the room casts one — **and kai has now asked for them.** `AAA.md` #61d |
| Night | **the room falls away from the lamp**; the table does not |

That obstacle is gone. It was the first real one — **the board filled the stage
vertically**, so there were six pixels of wall at the table seat and no room for
anything behind the desk — and iterations 7 and 8 cleared it. `roomAbove` keeps
a fifth of the stage's height for the room, at a cost of a fifth of the card,
and everything left on the list is now somewhere a camera can look.

### And the framing question iteration 1 left open is answered

Iteration 1 ended on the one thing the loop could not decide for itself: *"a room
whose back wall is a 6px hairline cannot hold a window, a lit patch, or anything
else #61c and #62 want, and giving it more means re-framing a stage he has
tuned."* kai has now given it more, in four answers:

- **The POV seat becomes the seat the stage opens at.** Thirty-two degrees of
  elevation — a head at a desk — where the room is about half the picture rather
  than a fifth. `StageSeat.POV` already exists and is already tested; what
  changes is `CameraTune`'s defaults.
- **The Desk scenes may be graded like a photograph.** AgX and an exposure, a
  cos⁴ vignette, tone-enveloped grain, a little lateral chromatic aberration,
  bloom and depth of field. `Scene.MINIMAL` is untouched, as always.
- **The room gets built.** Bed, bookshelf with books in it, a ceiling, side
  walls, more floor, clutter on the desk — and room shadows **as a set**, which
  is the decision `AAA.md` #61d was holding open.
- **The card art stays as it is.** A 168×246 thumbnail is the hero surface and
  it is not going to be replaced; the fidelity is bought in the stock, the foil,
  the sleeve, the cut edge, the shadow and the light instead. That refuses
  `AAA.md` #34 rather than deferring it.

Two of those cost something and the cost is accepted rather than hidden. The low
seat draws a card about a third shorter — `cos(pitch)` — and the hold-to-read
card reader carries legibility instead. And the room at sixty to a hundred
pieces is past what `ScenePainter` sorts and past what
`SceneryTest.theRoomIsAHandfulOfObjectsRatherThanAScene` allows, so the
furniture is downstream of `AAA.md` #92 and #93 rather than beside them.

This document is the loop. It says what one iteration is, what it may not do,
and what it has already done. An agent picking this up cold should be able to
run an iteration from this page and `docs/classic/AAA.md` alone.

---

## 1. Why this can be a loop at all

Because the stage can now be *looked at* without a tablet.

`:studio` composes the real `PlayScreen` into an `ImageComposeScene` and writes
a PNG. Real theme, real dependencies, real card art off the network, a frame
clock advanced by hand, no window and no GPU:

```
tools/shoot.sh                                   # the default contact sheet
tools/shoot.sh --shots=desk-night-seated         # one named shot
tools/shoot.sh --shots=a,b,c --keys=n --settle=200
tools/shoot.sh --density=2 --width=2960 --height=1848   # tablet metrics
```

A shot name is parsed for its room, its hour and its seat: `desk`/`minimal`,
`day`/`night`, `overhead`/`table`/`seated`. `--keys` presses the stage's own
shortcuts before shooting, so a script can deal, draw or change the room the
way a hand would.

That is the whole reason this stopped being a wish list. Before it, every
visual idea cost a signed release to evaluate and the evaluation was somebody
squinting at a tablet. The very first picture it took had no cards in it, and
that turned out to be the app rather than the harness — see §5, iteration 0.

**A loop without an organ of perception is not a loop, it is a random walk.**
Anything that makes the next look cheaper or truer is therefore always in
scope, and outranks the feature it was going to be spent on.

---

## 2. One iteration

Six steps. Every one of them, every time.

1. **Look.** Shoot the contact sheet against the current tree and actually read
   the pictures. Name what is wrong in a sentence a person would say out loud —
   *"the cards look like stickers"*, not *"shadow term is under-weighted"*.

2. **Pick one thing.** From `docs/classic/PHOTOREAL.md`, then `docs/classic/FIDELITY.md`, then
   `docs/classic/AAA.md`, then whatever the look just showed — in that order, because
   each is argued for and the earlier ones supersede the later. One thing. An
   iteration that lands two changes cannot tell you which one worked.

   `PHOTOREAL.md` is the staged plan to a photographic table, written after kai
   moved the target to driving-simulator fidelity and after the shader seam
   opened; it says which parts of `FIDELITY.md` it supersedes, and it is honest
   about where the ceiling is. `FIDELITY.md` is the canvas-only backlog and is
   still right about everything a shader does not touch. `AAA.md` is the
   hundred-item menu and outranks neither on scope.

3. **Say what will change.** Before writing code, write the line that will be
   true of the *picture* afterwards. If that line cannot be written, the item
   is not understood well enough to build and the iteration is research.

4. **Build it in `:core`, wire it in `:ui`.** Arithmetic goes in `core/render`,
   `core/scene`, `core/motion` with a `commonTest` that names a *claim* — not a
   golden. `:ui` only turns solved values into paint. `./gradlew :core:jvmTest`.

5. **Look again, and compare.** Same shots, before and after. If the picture
   does not show the line from step 3, the change is wrong even if the maths is
   right. Revert or fix; do not ship a change whose effect you cannot see.

6. **Ship it.** Push, CI green on all three jobs, fast-forward `main`, dispatch
   `release.yml` with the next patch, confirm the APK is attached. `CLAUDE.md`
   §*Ship Every Change* is the authority and it is not optional: kai judges this
   on the tablet, and work on a branch is work he cannot see.

Then write the iteration into §5 — one paragraph, including what did **not**
work. A loop that only records its wins relearns its losses.

---

## 3. What the loop may not do

Four of these come from `docs/classic/DESIGN.md` and are restated because a long
autonomous run will drift into all of them.

- **No engine.** Ever. No 3D engine reaches KMP common code and every one of
  them costs the desktop target. Geometry is arithmetic in `:core`, reaching the
  screen through one `graphicsLayer` and a canvas.
- **A shader colours; it never draws.** The seam is open (`AAA.md` #99 is done,
  `DESIGN.md` §6 has the rules) and it does not relax the line above: nothing in
  SkSL holds a scene, owns a transform or decides where anything is. And
  `compileStageShader` returns null — Android below 33, a refusing driver — so
  **every caller ships a drawing that works without one**. That fallback is not
  a degraded mode to tolerate; `minSdk` is 26.
- **Nothing idles.** No breathing cards, no drifting light, no ambience. Motion
  explains a change; it never announces one. This is the single rule a fidelity
  loop is most likely to break, because idle motion is the cheapest way to look
  alive and the fastest way to look like a screensaver.
- **Colour is meaning or light, never decoration.** The prismatic ramp is for
  the thing under your finger and for reveals. Swiss: sharp white on true black.
- **Do not re-record a golden and change behaviour in the same release.**
  `GoldenStageTest` earns its place by being the one recording in the
  repository. A release that moves it is a release that has lost the ability to
  say which change moved it. Split them.
- **Do not silently widen scope.** Siding patterns and shootout mode are
  deferred by kai. The near edge of the table, the daylight patch and shadows in
  the room are each *decisions* recorded in `AAA.md` (#61, #61c, #61d), not
  oversights to fix on the way past.

And one that is this loop's own:

- **Never ship a picture you have not looked at.** The studio makes looking
  cheap. There is no longer an excuse.

---

## 4. What "better" means, so the loop can tell

Five gates. A change passes all five or it does not ship.

| Gate | How it is checked |
|---|---|
| It is visible | The before/after shots differ in the way step 3 predicted |
| It is true | A `commonTest` names the claim; `:core:jvmTest` green |
| It is affordable | `tools/shoot.sh --budget=120` before and after — the **ratio**, not the milliseconds; and no new per-card per-frame allocation |
| It is the house style | `docs/classic/DESIGN.md` — and if the change argues with the handbook, the handbook gets the amendment *first*, in its own commit |
| It survives the matrix | `tools/devices.sh` — `phone-small` and `tab-s11` at minimum. See `docs/classic/DEVICES.md` |

The frame budget is the one that will bite. Sixty cards is the working number,
and a per-card layer or a per-card blur is the class of change that has to be
measured before it enters a signed build, not after.

`--budget=N` deals a hand and times N rasters of the whole scene, twice, and
reports the second — the first pass is JIT and layer allocation and showed up as
a p95 nine times the median. It is **not** the tablet: it is software Skia on a
container's CPU, and the absolute figure means little. The ratio means a great
deal. The baseline at 1600×1000, six cards in the air, is *median 92ms, p95
101ms*; a term that takes that to 180 will not be free on a tablet either.
`FrameProbe` on the device is still the honest number and still the last word.

**And for shader work there is no honest local answer at all.** The studio has
no GPU, so it runs every SkSL pixel on the CPU, and `ImageComposeScene` redraws
the whole scene every frame where a device skips a surface entirely when nothing
about it moved. Iteration 5's weave read +111ms for ~40 ALU over ~0.7Mpx; treat
that as the studio's calibration constant, use the ratio only to catch
order-of-magnitude mistakes, and get `FrameProbe` off the tablet before any
per-pixel change enters a signed build.

---

## 5. The ledger

Newest last. One paragraph each: what was tried, what the picture showed, what
shipped, and what was learned that the next iteration should not have to
rediscover.

### Iteration 0 — an eye, and the first thing it saw

Built `:studio` so the stage can be drawn to a PNG headlessly, and pointed it at
the play screen. The first shot came back with a room, a mat, two shuffle marks
and **no cards at all**. That was the app. `PlayScreen`'s single frame loop was
`LaunchedEffect(Unit)` while `cards` and `pilot` are both `remember`ed *against
the deck*, so a deck arriving under an already-composed stage left the loop
stepping the previous, empty map — every card in the new one stayed parked at
the pose it was dealt from, the opening five included, stacked invisibly on the
deck they came out of. The camera and the puzzle kept moving the whole time,
because those two are remembered against nothing, which is exactly why it read
as *the hand is missing* rather than as *the stage is frozen*. Keyed the loop on
`cards` and `pilot`. Shipped in v1.2.29.

The lesson worth keeping: **ordinary use never hit this**, because you pick a
deck and *then* open the table. The studio hit it on its first run, before any
fidelity work at all, because a harness starts the screen in states a person
would have to work to reach. That is most of the argument for having built it.

### Iteration 1 — the window nobody has ever seen

Then made the eye trustworthy: `tools/compare.py` for before-and-after with the
difference multiplied twelve times, and a **fixed shuffle** — `PlayScreen` takes
an optional seed, null still meaning a fresh deal — because two pictures of two
different hands are not a comparison. Two studio runs are now bit-identical, so
every pixel that moves is a change in the code.

The first thing the contact sheet showed was a pale wedge lying across the top
bar with seven of its controls printed on it at about 1.2:1. It is the room's
**window**, and the stage's box is padded down by `TOP_BAR` precisely so this
cannot happen — but the mat's `graphicsLayer` is a rotation, and a rotation does
not clip, so the back of the room projects up out of its own box and onto the
chrome. Most of the time what lands there is the dark wall and nobody noticed.

Measuring it is what made the iteration worth having. At 1600×1000 the pane
occupies rows 0..36 against a 44-tall bar: **every row of the window is inside
the chrome, and none of it has ever been visible as a window.** Behind it the
wall itself gets 6px of open air at the table seat, 25 seated and 26 overhead.
`AAA.md` #62 already argued the window down as low as it could go to be seen at
all; the measurement says it did not go far enough, and could not have.

Shipped the half that is not a matter of taste: the bar stands on Ink, opaque
rather than a scrim, because a translucent wash over content is the second line
of `DESIGN.md` §12 and an 82%-opaque bar is a bar that fails on the one
background it needed to survive. The ground behind "Overhead" went from
luminance 223 to 6. Exactly 4.4% of pixels moved — 44 rows of 1000, the bar and
nothing else, which is what a deterministic studio buys you.

The half that **is** a matter of taste is left for kai and written down here so
it is not quietly decided by the next iteration: a room whose back wall is a
6px hairline cannot hold a window, a lit patch, or anything else #61c and #62
want, and giving it more means re-framing a stage he has tuned. Until that is
answered, the room's ceiling is real and the fidelity work belongs on the felt,
the cards and the light — which is where the backlog points anyway.

### Iteration 2 — nothing ever lands square

`AAA.md` #52, and #47 for the graveyard. The line written before the code: *the
five cards in the hand stop reading as one printed strip.* They were dead level,
evenly stepped, tops on a perfect horizontal — five copies of one card rather
than five cards.

`core/motion/Settle.kt` answers "where did instance N come to rest", as a turn
in degrees and a slip in fractions of a card's width. Three things about it are
the design rather than the implementation. It is a **hash of the instance id**,
not a random number: there are two hundred levels of undo here and #38 is
explicit that anything unreplayable breaks them, and a hash needs no storage, no
undo entry and no migration — it is the same answer forever. It is **three
independent channels**, because deriving the turn and both slips from one hash
by shifting gives a hand where every card that leans left is also low and left,
which is a pattern rather than an unevenness. And the amount is a **property of
how the card got there** — `SQUARED` for a deck tapped against the table,
`PLACED` for a card set down, `THROWN` for a graveyard swept into a heap —
because a board where the discard pile is as tidy as the deck is a board nobody
built by hand.

Frame cost: nothing. It is three float operations per card, evaluated when the
board changes rather than per frame.

Two things learned. The first test written said *no dealt hand leans entirely
one way*, which is false and was rightly red on the first run: five fair coins
agree once in sixteen, so a hand that happens to lean is the feature working.
The claim that actually matters is that **consecutive instance ids are
uncorrelated** — a dealt hand is five consecutive ids, and that is precisely the
input a cheap `id * prime` hash fails, fanning them out in an even ramp that
reads as drawn. The second: `--budget` is noisy across runs when anything else
is using the container, so measure it back-to-back or not at all.

Shipped in v1.2.30.

### Iteration 3 — the deck has been printing its own count backwards

Not from the backlog. From zooming in on the deck pile — the most prominent
physical object on the table — while looking for something else.

`▤34` on the deck was drawn as its own mirror image, and so was `▤14` on the
extra deck, and so is the count of any face-down stack on the mat. The one
number a player checks most, reversed, in every shipped build.

The cause was already solved five lines away. A card's map takes its rectangle
to its four real flattened corners, and for a back those corners are wound the
other way, so everything inside the card's box comes out mirrored —
which is *correct* for the printed side and is why `CardFace` carries
`if (!faceUp) scaleX = -1f` with a comment explaining it. The badge is a
Compose child of the same box and never got the same line.

Read live from `motion.pose.rotY` rather than from `seat.faceUp`, because a card
*turning over* is the case that matters: the glyphs have to flip on the same
frame the printed side does, which is the frame the card is edge-on and neither
is visible.

The compare said `0.0% of pixels moved, peak 215` — a few hundred pixels changed
completely and nothing else changed at all, which for a 50×24 badge in a
1600×1000 frame is exactly right. That number is worth remembering as the
signature of a surgical fix: a high peak against a near-zero area.

The lesson: **zoom in.** Three iterations found three defects and two of them
were invisible at 1:1. A contact sheet says whether the room reads; a 4x crop of
one object is what says whether the object does.

### Iteration 4 — a penumbra the same width on all four sides

`docs/classic/FIDELITY.md` landed between iterations, and F3 is the first thing on it.
The line: *a card's shadow stops being fatter above and below than it is at the
sides.*

`StageRender.polygon` grew a shape by pushing every corner **away from the
shape's centre**, which is not an offset. On a 59:86 card the corners sit 52.1
half-units out while the long edges sit at 43 and the short at 29.5, so a growth
of `w` moved the long edges by `0.825w` and the short ones by `0.566w` — every
shadow on the table 1.46 times wider top and bottom than at its sides, for no
physical reason at all. A disc light throws the same penumbra on every edge of a
rectangle. The same line took the half-*diagonal* as its inset guard, so the
umbra was allowed to eat one and a half times the half-width it was protecting.

`core/render/Outset.kt` does it the textbook way: push each *edge* out along its
own normal and intersect the neighbours. No angle formula to get the wrong way
round — and the bisector form is `w / sin(θ/2)`, which the survey wrote with
`cos`, which is exactly the kind of thing this avoids by not needing it. The
guard now measures the distance to the nearest **edge**.

Four gates. Visible: 0.7% of pixels moved and the amplified difference is
concentric penumbra rings and the two pile shadows, with nothing on any card
face. True: the test asserts each of a card's four edges moves out by exactly
`w`, that no inset up to 80px ever folds the shape, and that a quad seen almost
edge-on — which every card passes through twice per flip — cannot grow a
whisker. Affordable: 53.6ms against 52.8ms back-to-back, which is noise.

Learned: `--budget` measured while Gradle is running reports 117ms median and a
424ms p95 against a true 53ms. The note in §4 about measuring back-to-back is
not a nicety; the number is worthless otherwise.

### Iteration 5 — the mat becomes cloth, and the stage gets a shader

kai changed the target: the fishbowl should reach the fidelity of a driving
simulator. That answers `AAA.md` #99, which had been marked *[your call]* since
it was written, so the handbook was amended first and in its own commit — §6 of
`DESIGN.md` now carries the seam and its four rules.

**Proved it before building on it.** `:studio` runs on a raster surface with
`skiko.renderApi=SOFTWARE` and no GL context at all, so the sharp question was
not "does SkSL exist" but "does it raster without a GPU" — because a shader the
loop's own eye cannot see would put every per-pixel change back behind a signed
release. `:studio:spikeShader` compiled a weave and drew it: 47 levels of
luminance modulation on a near-black base, no GPU. That answer is the reason
everything below was worth writing.

`ui/gpu/StageShader.kt` is the seam — `RuntimeShader` on Android 33+, Skia's
`RuntimeEffect` on desktop, `null` everywhere else. `FeltWeave` is its first
use: plain weave, warp over weft, normals from central differences because
neither platform's runtime shaders have `dFdx`, composited in `BlendMode.Overlay`
so the identity is mid grey and a bug in it cannot blank the mat.

**The bug worth remembering.** The first version compiled, ran, cost a full pass
and changed *nothing* — 0.0% of pixels, peak 1. `Light.direction` is the way the
light **travels**; the key is `(0.30, 0.45, -0.84)`, heading down onto the table,
and every dot product wants the vector pointing back at the lamp. Handed the
travel direction, `max(dot(n, l), 0)` is zero on every thread of an upward-facing
surface, so the shader returned overlay's identity everywhere. It is
indistinguishable from a shader that failed to load, and the only thing that told
them apart was a one-line probe on `compile() != null`.

**The gate that could not be answered here.** Frame cost went 53.6ms → 165ms,
and that number should not be believed. The studio has no GPU, so it runs every
pixel of SkSL on the CPU, and `ImageComposeScene` redraws the whole scene every
frame where a device skips the felt entirely while the camera is still. This is
the one case so far where the studio's ratio is a bad proxy, because the change
moves work *from* CPU paths *to* a GPU fragment program. `FrameProbe` on the
tablet — three taps on the life-point total — is the only honest answer, and it
is asked of kai rather than assumed.

### Iteration 6 — the cloth stops being able to crawl

`docs/classic/PHOTOREAL.md` landed — nine stages from here to a photographic table — and
the first thing its §1 says is that iteration 5 shipped a defect. It is right,
and this is the loop working: **a survey of the code caught what looking at
three seats could not.**

The weave's threads are 3.06 mat px. Screen density falls as `cosTilt · scale²`
with depth, so at the envelope's steepest legal angle the far half of the mat
reaches 0.97 cycles per pixel — twice Nyquist. Past Nyquist a procedural texture
does not soften, it *aliases*, and an aliased texture **crawls as the camera
orbits**, which breaks "nothing idles" by accident rather than by choice.

The measurable half was visible at the shipped seats: per-pixel contrast fell
from 2.17 near to 1.34 far, the weave quietly losing energy as it compressed. The
part that actually bites is at 58° and the studio can only reach the three seats,
which is a real gap in the eye and is now in §6.

`StagePlane.jacobian(x, y)` answers how far a mat unit travels on the glass — the
projection's exact derivative, closed form, because `project` at z = 0 is closed
form in four cached trig values. Four claims, and the load-bearing one is that it
agrees with a finite difference of `project` itself across six poses and five
points. Everything downstream — the band-limit here, level of detail (`AAA.md`
#97) — stands on that agreement.

The weave is rewritten around it: a basket weave in closed form returning height
*and* both slopes, rather than four samples of a height at an epsilon nobody
could defend. The relief fades out as the cloth approaches half a cycle per
pixel, per axis, and the glint the fade removes is returned as a broadened lobe
so the far felt does not read as a different material from the near felt.

**Proved the fade rather than trusting it.** The three seats are all below
Nyquist, so the path could not be photographed — so the threads were temporarily
made twelve times too fine. Per-pixel contrast collapsed from 3.11 to **0.15**:
the cloth dissolves into a flat surface instead of breaking into moiré. Restored
afterwards.

And it is *cheaper*: 165ms → 120ms in the studio, because two transcendentals
beat six. A correctness fix that also pays for itself is worth noticing — the
first version's expense was buying an epsilon, not an answer.

### Iteration 7 — the board stops using the whole stage

The foundation the table above had been waiting on, and the smallest change in
this ledger by line count. `BoardLayouter.solve` gains `roomAbove`: a fraction
of the stage's height it declines to use. `Scenery.ROOM_ABOVE` is a fifth and
only the desk pays it; minimal passes zero and comes out bit-identical, which is
the mandate's requirement and is checked rather than assumed.

It costs card size and there is no arrangement where it does not — the board is
height-constrained on every device this ships to, so the reserve comes straight
off the card: 107dp down to 85dp on the tablet, 115px down to 92px on the
narrowest phone against a floor of 28. That is the trade, and it is written into
the KDoc rather than left for somebody to discover.

The picture: 80% of pixels moved, and above the desk's far edge there is a wall
with a window in it instead of wood to every corner. Which immediately showed
that the wall was `#191B22` — the colour of the void behind the desk, chosen
when it was a six-pixel hairline.

### Iteration 8 — the window, and two bugs in the floor under it

The line written first: *the window stops being a bright rectangle cut in a
black band.* It is now a four-pane sash — two stiles, two rails, a cross of
glazing bars, on their own `Surface.FRAME` — with a graded sky in it and a
painted wall behind it.

**There is no sun in it, and that is measured rather than conceded.** The room's
vertical axis is drawn compressed: a wall four and a half card widths tall
standing under an eye eleven and a half card widths up. Trace the day key back
from the eye and it crosses this wall's plane about 2 700 pixels above a head
that is at 232, at every seat the camera can reach. Bringing the disc into the
opening would need light arriving from *below* the eye — so a `Sky` that placed
the sun honestly was built, found to compute something invisible, and deleted.
kai's *"window with sun outside"* is answered by the light in the room and by
which side of the sky is brighter, not by a disc. Lowering the day key to a
golden-hour elevation would change every shadow in the room and is his call.

**And the room's paint order was never an order.** The joinery took the room
from ten pieces to seventeen and a glazing bar drew straight through the
Millennium Puzzle. Two separate defects, both already shipped:

`behind` was cyclic. It finds an axis that separates two boxes, a pair can be
separated on several, and both earlier versions *picked between* disagreeing
axes — the first that separated, then the one the camera was most nearly looking
along. Either choice loops: A above B, B left of C, C nearer than A, one axis
each. Fourteen of sixteen pieces in a single cycle at the overhead seat, and the
ten-piece room had them too. The fix is to stop asserting an order for any pair
no ray connects: disagreeing axes mean *no answer* (disagreement is the proof
that no ray hits both), an eye inside the gap between two boxes means no answer,
and pairs that do not overlap on screen are never compared.

And `order` bubbled, which cannot topologically sort a partial order at all — an
adjacent swap cannot move a piece past a *third* piece that has no opinion, and
no-opinion is the common case. It is a topological walk now. `ScenePainterTest`
holds both claims at 432 cameras.

The lesson: **the room's own tests could not see the camera.** `PuzzleTest` swept
360 degrees of yaw while handing the eye a fixed `(0, 0, 1)`, which turns the
*projection* and leaves the camera parked over the middle of the table — so
seventy-two poses asked one question, and every cycle lived in the ones it never
asked.

Frame cost 346ms to 356ms back to back. Both shipped in v1.2.36.

### Iteration 9 — night stops being day with the lights down

The line: *at night the wall goes dark away from the lamp, so the only reason
anything above the desk is lit is the lamp standing on it.*

The two desk rooms differed by an ambient of 0.76 against 0.55 and sRGB flattens
that to nine levels — the wall came back **67 by day and 68 at night**. Every
cue that says night lived in the one term nothing was allowed to touch, because
`NIGHT_FLOOR` protects `Tone.veil`'s approximation on card art.

`StageRig.room` is the seam that lets both be true. The table keeps `face` and
keeps the floor; the room gets `Light.roomReach` — the ambient as what it
physically is, which is bounce, off a lamp, from over there. Three things about
it were each a wrong first attempt:

- **Dimming the ambient alone left the far wall at half its daylight
  brightness.** The rim was supplying a quarter of the light on a vertical
  surface, undimmed, from a lamp that does not exist after dark. Ambient, fill
  and rim are all *the room*; only the key's beam is the lamp.
- **A square root, not the attenuation.** Scattered light falls off far more
  gently than the beam it came from. Falling like the beam gives a wall that is
  black a card's width from the lamp.
- **And a face needs more than one answer.** `room` shades a face at its centre
  and the wall is fifteen hundred pixels wide, so the whole correct calculation
  came out as one flat grey stepping to another at a seam — the same defect one
  level up, and the shot after the first two fixes was almost unchanged.
  `StageRig.wash` samples along whichever of a quad's two axes the light runs
  along; the renderer draws it as a gradient, exactly as it already draws
  `LightPool` on the mat. Null under two levels of 255, so daylight allocates no
  brush.

The wall now runs 52 beside the lamp to 34 at the far end, against 67 by day.
Day and minimal shots are bit-identical, which is the mandate. 371ms against
379ms back to back.

Two tests were wrong before the code was. *"Night is dimmer than day
everywhere"* is false — a bulb a hand's width from a wall is the brightest thing
that ever happens to that wall, and the claim that holds is about the far end.
And *"a glazing bar is too small for a gradient"* is false twice over: a bar is
eight pixels thick and a hundred and seventy long, and a patch forty pixels tall
varies by more than the threshold on its other axis.

### Iteration 10 — an instrument, and a camera dial that was lying about its name

Not a fidelity iteration. kai asked for a tuning tool — *"find tune the camera
(angle, FOV, depth of field, focal length) and position of everything … it will
export a copy paste json for me to feed you back to make the default"* — so this
one builds the thing that makes the next ten cheaper. `docs/classic/TUNING.md` is the
handbook; only what the loop should remember is here.

**Fifteen numbers, and the line that decides which fifteen.** Nothing that
re-solves the board may go on the panel: the mat is a single
`pointerInput(layout)`, so a slider that re-solves tears down the gesture
arbiter mid-drag — including the drag moving the slider. Card size is the free
variable in that solve, so a slider could also cross `MIN_CARD_WIDTH` and
replace the stage with "Not enough room to lay a table out here". The room, the
window, `ROOM_ABOVE` and the gap fractions ride in the export's `reference`
block instead, read off live constants so a paste cannot lie about its build.

*(Later: eleven of those turned out to be on the wrong side of the line — see
iteration 14. The rule is unchanged; the room simply never re-solved the board,
and nobody had checked. `ROOM_ABOVE` does, and is still read-only.)*

**The dial was wrong before the tool was.** kai: *"touching the distance slider
resets the focal length and vice versa, it's not working as I envisioned."* The
values were not resetting — the model was. `lens` multiplied `cameraDistance`
alone, which pins the framing and swings the perspective: a **dolly zoom**, and
not a focal length. Both dials therefore changed the same thing, and the mm
readout was computed from `distance × lens`, so walking backwards moved the
number beside the lens slider from 36° to 26°. It now multiplies `zoom` too, so
the subtended angle is untouched and the lens is pure magnification. It cost the
`minDistanceAt` lens factor (it cancels) and it retired the caveat that a lens
under 0.947 turns `StagePlaneTest` red. It buys back nothing that catches a zoom
running off the edge of the screen, which is what a zoom does.

Three harness bugs, each of which looked like the tool being broken:

- **`combinedClickable` on the life-point number ate the first key event** by
  making the node focusable, so the first shot of every studio run came back at
  the table seat whatever it had asked for. `detectTapGestures` touches no focus.
- **A `LaunchedEffect` placing the tuned camera cost determinism** — 77.7% of
  pixels apart between two runs of an identical tuning, because *which frame* it
  landed on was the scheduler's. `SideEffect` runs after composition and cannot
  straddle one. For the same reason the studio writes the tuning once before the
  settle: a preferences write is a coroutine and the seat press is not.
- **The shot's seat press overrode the tuned camera**, so two tunings differing
  only in the camera compared as identical and the file looked ignored. A
  non-default `camera` block now skips the press.

The whole thing is inert at its defaults — shot with and without the working
tree via `git stash -u`, room and felt max difference **0** — which is what let
it ship ahead of anything that moves a number.

### Iteration 11 — the two objects with no material get one

kai, on the two things left in the frame that were still placeholders: *"the
millennium puzzle and lamp both look extremely rudimentary and completely
lackluster."* The contact sheet agreed in one sentence each. **The lamp is a
cardboard box on a matchstick** — a cuboid shade, a grey stick, a flat tile,
and at night the shade came back *darker* than the same cloth by day. **The
puzzle is a Post-it note** — one mustard quadrilateral, no highlight, no seam,
nothing to say which pendant it was.

Both were the shapes core could express. Until this iteration `core/render` had
exactly two mesh constructors — a quad and a slab — so the room was boxes and
the table was cards, which is the right pair of primitives for a card table and
the wrong pair for the things standing beside one.

**`core/render/Turned.kt`** is a lathe: a profile of rings, spun, capped, posed.
Three things about it are the design. Every vertex goes through `Rot3.place`,
where a slab hangs its body down the *stage's* z whatever the pose says — which
is what later bought the puzzle a lean it could not otherwise have had. A band's
normal is *solved from the profile* rather than crossed from its corners, which
keeps it exact at an apex; the exact answer is short by `cos(pi / sides)` in its
radial half, because a chord is shorter than the arc it spans, and `TurnedTest`
measures the normal each face carries against the vector area its corners
really have. And the side count is derived from the sagitta at half a pixel, and
forced **even**, because an even polygon is centrally symmetric and therefore
has a bounding box centred on its own axis — which is what the room sorts,
measures and lights every fixture through.

The lamp is four turned pieces stacked in z, because `ScenePiece.mesh` holds one
*convex* solid and a cove and an overhanging shade are not. The shade is a
**shell** — up the outside, across the opening, back down the inside — and the
picture is what found that: a solid frustum has a lid, and a lid at this camera
is a bright disc in the middle of the rim, so it came out as a drum with a plate
on it.

Then the material, and the room had none: `StageRig.lit` is ambient plus lambert
plus a graze-gated rim with **no specular term anywhere in it**. Three new terms,
all *beside* the rig rather than inside it, because `GoldenStageTest` records
what `lit` returns and §3 forbids re-recording a golden and changing behaviour in
one release. `gleam` is the lamp mirrored in a surface, additive. `spill` grades
a fixture down its own height. `Face.smooth` lets a facet carry the normals of
the curve it stands for.

**Six defects, and five of them only a picture could have found.**

1. *Every shared edge came out as a hairline of background.* Two antialiased
   fills each cover about half the pixels along the edge they share, and half
   over half is three quarters. Four faint lines on a box nobody mentioned in
   two years; twenty-six on a turned shade is a wireframe. Each facet now strokes
   its own outline in its own paint — and **not** on `Scene.MINIMAL`, because
   that also grows the silhouette by half a pixel and minimal is the control.
2. *An additive pass must not close its own seam.* Painting a seam twice is free
   when the paint replaces what is under it and is a doubling when it adds, so
   the seamed `gleam` drew a bright spoke along every edge of the lamp's foot.
3. *The lit shade came out grey.* Four tenths of the bulb is the honest
   transmission of linen and completely wrong here — everything the renderer does
   with a colour is a multiply, so that is four tenths of `#E4DAC6`.
4. *The gold blew out to white.* A highlight is the source reflected, and the day
   key is a window twenty-two degrees across; gold's own sharpness of thirty-six
   applied to it turns a flat face into a white rectangle, because a large flat
   surface under a directional lamp has **one** alignment over the whole of it.
   The lobe is widened by the source's angular radius now, in quadrature, and the
   same two numbers give a broad whisper by day and a hard glint at night.
5. *The starburst, three times.* A facet is not a point (iteration 9's lesson, one
   level down); the gradient's endpoints must be the midpoints of **opposite**
   edges, not adjacent ones; and a curved face may not choose its gradient axis by
   brightness, because that chooses *per facet* and two neighbours that choose
   differently cannot meet at their shared edge. Measured at 0.074 of the
   surface's light — eighteen levels of 255. Grading along the axis the normal
   turns is the same choice for every facet of one turn.
6. *An inverted pyramid balanced on its point shows nothing but its base.* From
   anywhere above every flank slopes inward and away and is occluded by the top
   face's own edge — twelve pixels of visible strip at the seat this stage opens
   at, and a flat gold square everywhere else. The puzzle is propped back
   thirty-four degrees now, which is what makes the flanks, the chamfer and the
   bail read at all.

And one that is arithmetic rather than taste: a homography onto a facet's own
corners **crushes anything drawn on it toward the narrow end**, because a
projective map's midline is where the projective midpoint is and this flank
tapers 1:0.07. Anything cast into a facet of a cone or a pyramid wants its own
patch, cut out of the face rather than taken as the whole of it.

Two things were wrong before the code was, and both were tests rather than
pictures. `rest` dropped the solid to the desk once, but `Rot3` spins about the
object's own axis *before* tipping it, so which corner is lowest changes with the
turn and the puzzle sank three quarters of a pixel into the wood on every nudge.
And `reach` was the square's own diagonal, written by hand and true right up
until the shape changed underneath it.

`minimal-day-table` is bit-identical throughout, which is the mandate. Frame cost
238–292ms against a 273ms baseline — inside the run-to-run spread.

**And the loop got its eye back on this machine.** The Android SDK installs from
`dl.google.com` inside this container, so `tools/shoot.sh` and `tools/compare.py`
both run here now: `sdkmanager` into `/opt/android-sdk`, `sdk.dir` in
`app/local.properties`, `pip install Pillow`. `tools/crop.py` is new and is the
other half of iteration 3's lesson — a contact sheet says whether the room reads,
a 6x crop of one object says whether the object does. Every one of the six
defects above was found in a crop.

---

### Iteration 12 — the eye is cut

kai, on the release: *"while the lamp is alright, the millennium eye is
completely unsatisfactory. I think the move here is to just delete it entirely."*
So it is deleted — `core/scene/Wdjat.kt`, its test, `Puzzle.eyeQuad` and the
renderer's `drawMark` — and this paragraph is the whole of what is left of it,
because a loop that only records its wins relearns its losses.

**What was actually wrong with it, so the next attempt does not start here.** The
drawing was fine in the flat: rendered into a square it is a recognisable wedjat.
What failed is everything between that square and the object. A hundred pixels of
facet, seen at a slant, through a perspective map, engraved as two flat fills a
pixel apart — the six marks come out as about four pixels of stroke each, and at
four pixels a tapered ribbon is a scratch and a spiral is a blob. It is the same
class of problem as `AAA.md` #61c's daylight patch: the idea was right, the thing
it had to be drawn *through* was not able to carry it.

Three things a second attempt would need, and none of them is a better outline:

- **Relief, not fill.** A cast mark reads because its groove wall catches the key
  and its far wall does not. Two flat fills offset by a pixel is a decal of that,
  and at this size the pixel is the whole mark. It wants a normal, which means it
  wants the shader seam — `docs/classic/PHOTOREAL.md` stage 4's per-pixel path, with the
  mark as a height field rather than a path.
- **Or a bigger object.** The puzzle is a card wide because it has to clear the
  mat and stay off the lens. Nothing about the mark works until the facet it is
  on is two or three times the size, and that is a framing decision of the kind
  `docs/classic/LOOP.md` iteration 1 left open, not a rendering one.
- **Or no mark at all**, which is where it is now, and which is not obviously
  wrong: the object reads as a gold pyramid with a bail, propped on a desk, and
  `docs/classic/DESIGN.md` §11's whole argument for it is that an easter egg's value is
  that nobody told you.

**What survived, and why.** The lean. It went in so the flanks could carry the
eye, and it turns out to be the thing that makes the object read at all: balanced
on its point an inverted pyramid presents nothing but its base, and the shot
without the lean is a flat gold square with a ring lying on it. Shot both ways
before deciding, which is the only reason this paragraph is a claim rather than
a preference.

---

### Iteration 13 — the puzzle is cut

kai, correcting iteration 12: *"What I meant was to delete the puzzle entirely."*
So the whole prop goes — `core/scene/Puzzle.kt`, `PuzzleTest`, `ui/play/StageProp.kt`,
`SceneRender`'s `Prop`/`Prop.Part`, `Surface.GOLD`, `StageRig.Gloss.Gold`, and
`MatPilot`'s two prop lambdas. The room is fourteen pieces and the left of the
desk is bare.

**Read iteration 12 as one iteration too narrow.** The instruction was
"delete it entirely" and the thing that was cut was the mark on it, which is a
reading the sentence permits and the release did not support: the object had been
called out by name in the same breath as the lamp, and taking the decoration off
the decoration is not what "entirely" means. The check that would have caught it
is the one this loop already has and I did not run — *say what will change, in a
sentence, before building* — because "the puzzle loses its eye" is visibly not
"the puzzle goes".

**What is worth keeping out of it**, because a second prop will want all of it:

- **A prop is a pose, not a `ScenePiece`.** Furniture is solved twice a day and
  remembered; a thing that moves cannot live in a value that is deliberately
  recomputed. `Puzzle.stirred(layout, turns, lifted)` was a pure function of two
  numbers the screen owned, so what was drawn, what was touched and where it
  stood could not come apart.
- **It must be convex, or arrive as convex parts.** Inside one piece the renderer
  sorts faces by the depth of their own centres, which is meaningless across two
  solids — the bail sat behind its own pyramid the first time it was drawn as one
  list.
- **And it must share no volume with anything**, which is what `ScenePainter`'s
  separating axis needs *between* pieces.
- **It is claimed after the mat's own affordances**, never before: a shuffle mark
  and an open fan outrank an ornament beside them, and all three come out before
  `claimForCamera`.

`Turned`, `gleam`, `spill`, `Face.smooth` and `ScenePiece.mesh` all stay. They
were built for the puzzle and the lamp together, and the lamp is still standing
on the other side of the desk using every one of them.

**Two things in the picture changed that are not the puzzle**, and they are worth
knowing before reading a diff of the shots: the deck-builder copy count moved off
the attribute symbol to the bottom right, and every card now wears the legacy
tool's foil edge (`UiPreferences.prismaticCards`, on by default). Both are card
treatments rather than room work, and both move `minimal-day-table` — which is
the one shot this document otherwise insists must never move. It is still the
control for *the room*; it is no longer the control for a card.

**The foil is free, measured.** Two `drawRoundRect` calls per card, sixty cards,
which is the class of change §3's affordability gate exists for. Three
`--budget=120` runs of `minimal-day-table` on each side, from a worktree at the
parent commit so it is the same machine and the same Gradle state: before
*398 / 407 / 404ms*, after *429 / 394 / 404ms*. The medians are 404 against 404
and the spread inside one condition is ±4%, so the honest reading is **no
measurable cost** rather than a small one. Which is what a stroke and a fill next
to sixty homographies and sixty decoded thumbnails should be. Do not read the
absolute figure as anything: it is software Skia on a container CPU and §3 says
what that is worth.

---

### Iteration 14 — the room goes on the panel

kai: *"allow me to fine tune the following with maximum ranges: the size and
location of the lamp, the length of the lamp pole; the dimensions of the table
(extending the table from the front should push the wall with the window back);
the size and location of the window on the wall."*

Eleven knobs, in a `ROOM` group: desk depth, desk width, wall distance, four for
the window, four for the lamp. `RoomTune` in `core/tune`, threaded through
`Scenery.of`/`desk`/the lamp profiles/`lightingFor` as a trailing defaulted
parameter, so `SceneryTest` and `GoldenStageTest` are asking for the room that
shipped and did not move.

**The rule this looked like it broke, and did not.** `docs/classic/TUNING.md` had listed
"the window, the lamp, the whole room" under *deliberately not on the panel*,
with the re-solve argument attached. That entry was wrong and had been for two
releases: only `ROOM_ABOVE` re-solves the board. Everything else about the room
is read *inside* `Scenery.of`, which `PlayScreen` remembers against the already
solved layout — so a room slider rebuilds the scene and leaves the board, the hit
boxes and the live gesture untouched. Checking that took one `grep` and would
have taken one at any point in the last two releases. **A constraint nobody has
re-derived is a constraint that has started guessing.**

**Maximum ranges cost four clamps, and every one of them was a red test.**
`SceneryTest.everyRoomKnobAtEitherEndStillLeavesARoom` walks each slider to both
ends and asks the room's own invariants — nothing over the mat, nothing above the
ceiling, no two boxes sharing volume. It found, in order: a sill at zero hanging
its bottom rail through the desk; a window squeezed shorter than a glazing bar is
thick, so the bar reached through the head rail; head and sill clamping against
each other into an opening with no height; and the lamp walking onto the felt.
None of those is a taste question and none would have been found by looking at a
picture of the default room.

The last one is worth its own line: the first fix pushed the lamp out by
`max(LAMP_BASE, LAMP_SHADE_RIM)`, which is the widest constant and not the widest
*part* — the hoop rolled over the shade's rim is 0.025 of a card wider. So the
reach is measured off the profiles themselves (`Turned.widest`), which cannot
drift from the shape the way a hand-picked constant just did.

**Not tunable, deliberately:** `Scenery.lampHeight`. Where the *light* is comes
from the shipped night key's own horizontal-to-vertical ratio and is arithmetic
rather than taste; `lampMast` moves the lamp you can see, which is compressed to
about a third of it because an honest desk lamp is off the top of the picture.

### Iteration 15 — the panel had been eating its own settings

kai: *"the slider can go below 1.4x but it doesn't show it visually and won't let
me get closer… when I press focal length to adjust distortion it resets the
distance to the table"*, and then: *"this resetting of the slider when touching
another slider also happens for a lot of the other settings."*

Two faults wearing one sentence, and the first guess at the first one was wrong
in a way worth recording.

**The reset was not the camera.** Iteration 10 had already fixed a real lens
reset — `nudge` and `step` rebuilding a pose positionally — so the report read
like that bug coming back, and the first pass went looking at `CameraPose`,
`planeFor` and the two dials' arithmetic. All of it was innocent, and thirty
minutes went on confirming it. The fault was one line of Compose:
`KnobRow`'s track is `pointerInput(knob.path)`, a key that never changes, so the
gesture coroutine is installed once and keeps the lambda it captured then — a
lambda closing over the **whole tuning document**. Every drag wrote the document
as it had been when the panel opened, plus its own field. Twenty-seven knobs, one
mechanism, and it had been there since the panel shipped.

The tell was in the report and not in the code: *"a lot of the other settings."*
A camera bug cannot reach `room.lampOut`. **When a symptom spans knobs that share
nothing but a widget, stop reading the domain and read the widget.**

`ui/dnd/DragSource.kt` has carried the fix in its comments since the first
drag — *"the gesture coroutine below outlives any single composition"* — which
makes this the second time the same Compose fact has cost this repository a
debugging round. The repair is structural rather than another
`rememberUpdatedState` (though there is one of those too): a slider hands back a
`Knob` and a number, and the read-modify-write happens against the live document
inside the preferences transform. A lambda that carries no document cannot carry
a stale one.

**The floor was real, and it was 1.37.** `CameraEnvelope.minDistanceAt` solves a
minimum distance from the pitch and the mat's diagonal; at forty degrees on a
2800×1607 stage it lands at 1.368, which is kai's "one point four" measured. The
low seat is the one you take looking for distortion, which is why the two
complaints arrived together looking like one. Nothing on the panel said the value
was being held — the distance row prints `· held at 1.17` now — and the number it
was solved against, `CLEARANCE`, was a private constant. It is
`CameraEnvelope.clearance` and on the panel, at 0.68 rather than 0.5.

**Three things the tests found that reading would not have.**

- `StagePlane.SAFE_DEPTH` was also 0.5, and the two being equal was a coincidence
  that had read as a design. Letting the camera closer than 0.5 without moving it
  would have culled the **desk**, which reaches further toward the viewer than the
  mat does by construction. It is 0.9 now, which is what a guard about `MIN_GAP`
  and the number one should always have been.
- A `NaN` clearance survives `coerceIn` — every comparison against it is false —
  and poisons the floor, the pose and every pile edge downstream without throwing.
  Found on the first run of the test written to claim it could not happen.
- The knob cannot go *below* what shipped. At 0.42 the floor at thirty-four
  degrees passes 1.34, which is the **Seated** button, so tightening it far enough
  makes a seat on the bar somewhere the envelope refuses to sit. Measured, not
  guessed: "maximum ranges" met a limit that is about the app rather than the
  geometry.

**Also retired:** `StageCameraTest.fittingTheFieldRatherThanTheHandIsWhatLetsTheCameraComeClose`
asserted the field alone reaches the envelope's floor. That was true only while
the floor sat *above* what `CameraFit` allows; with the floor lowered the two
swapped places and the fitter binds first. The assertion is now against the floor
as it shipped, because the durable claim is "the fitter is not what holds you
out", and a test reading "the fitter never binds" would assert `CameraFit` does
nothing.

### Iteration 16 — the focal length was carrying the camera's position

Not a fidelity iteration either, and the second in a row that began with kai
describing the tool as broken rather than as ugly: *"the perspective seems to
shift a lot when moving the camera around and it doesn't behave the way a real
life camera would … it also has a close distance issue where it locks me out
from getting closer past the close limit. there shouldn't be a limit I want
complete freedom and control."*

**Two complaints, one cause, and the cause was a line iteration 10 had already
half-fixed.** `cameraDistance` *is* the focal length in pixels —
`StagePlane.project` divides by `cameraDistance − depth` and Compose's
`graphicsLayer` does the identical thing — and `planeFor` had `distance` on it
as well as `lens`. Measured on a 1600-wide stage the field of view ran from 15°
at the back of the envelope to 116° at the front: a 130mm lens to a 9mm, with
the lens dial sitting still. Every dolly was a dolly zoom. So was every *tilt*,
because the pitch moves `minDistanceAt`, which moved the distance, which moved
this — so a one-finger drag down the felt, the plainest gesture on the stage,
quietly zoomed.

**The lesson, and it is iteration 10's own lesson arriving from the other
side.** Iteration 10 found the `lens` dial on `cameraDistance` alone, named it a
dolly zoom, and fixed it by putting the lens on *both* terms. That fix was
correct and it stopped the investigation one field early: the same expression
still had `distance` on the focal length, and nobody looked, because the dial
that had been complained about was now right. **When you find the wrong thing on
a term, read the whole term.** A focal length has room for exactly one number,
and there were two on it for five more releases.

**The close limit was three walls and two of them were taste.** A flat
`minDistance` of 0.8 sat in front of the solved floor and never let it speak —
below about fifty degrees the solved answer was *under* it. `CameraFit` ran on
every camera release and sprang the camera back out after every pinch; it was
also inconsistent, because the mouse wheel never went through the arbiter, so a
desktop user could already dolly past where a finger was allowed to stop. The
third wall — the near corner reaching the lens plane — is arithmetic and stays,
and it dropped on its own: with the distance off the focal length the floor
collapses from a square root to a line, and every floor anybody can reach is
lower for it (0.85 to 0.38 at the table seat).

Three things worth keeping from the rest of it:

- **A refusal in a KDoc is not a measurement.** `CameraPose` spent a paragraph
  refusing a pan, and half of it was a description of a *different* pan. Sliding
  the finished picture sideways moves the vanishing point off the middle of the
  glass and would need an off-axis inverse; moving what the camera *looks at*
  does not, so `unprojectAt` kept its closed form and swapped which point it
  adds back. The expensive half never arrived. The cheap half — a
  `transformOrigin` on the mat's layer — is two lines.
- **Opening an envelope moves things that were true because it was closed.** At
  eighty degrees the table's horizon is on the glass, and `unprojectAt`'s
  vanishing-divisor guard hands back the camera's own target — a defensible
  number and a terrible hit test, because a tap on the wall would grab whatever
  is in the middle of the board. `StagePlane.belowHorizon` clamps rather than
  refuses, so a press in the sky lands off the table (and goes to the camera,
  which is what pressing on nothing already did) and a card dragged that way
  slides to the far edge instead of teleporting.
- **The golden was re-recorded for the first time in four releases, and only a
  third of it.** `Home` and `Turned` are both at `HOME_DISTANCE`, which is the
  one distance where the old projection and the corrected one agree, so they
  came out byte for byte. `Steep` moved because it is meant to — and moved again,
  1.9 to 1.96, because the corrected projection put a quantised value *exactly*
  on a rounding boundary. The distances either side were swept and measured
  rather than guessed, which took one throwaway test and about a minute.

**And a table of numbers in prose got a test under it.** `docs/classic/TUNING.md`'s
distance-floor table is `StageCameraTest.theWholeFloorTableIsWhatTheDocumentSaysItIs`
now, cell for cell — three of its twelve cells were wrong when first written out
by hand. The lamp's height read "five to one" for two releases and measured
2.92; this is the same failure caught before shipping instead of after.

### Iteration 17 — three of the seats were the same seat

Not a fidelity iteration, and the third in a row that began with kai asking for
something the tool could not say: *"how can we change the way the camera works
and how the view is presented in the fishbowl play mode to push it to the next
level and feel like a true real life POV simulation?"*

**The finding is a units one, and it had been sitting in plain sight since the
first camera.** `StagePlane.tiltDegrees` is the angle off the table's *normal*,
so the elevation above the felt is ninety minus it. Overhead is at eighty-five
degrees of elevation, Table at sixty-nine, and Seated — whose own KDoc says "from
the player's own chair" — at **fifty-six**. Nobody sits fifty-six degrees above a
desk. All three seats were somebody standing over a table, and the only thing
that distinguished them was how far over. The envelope has allowed eighty degrees
of tilt since iteration 16 and no seat had ever followed it down.

**And the reason none could is that the projection had two middles welded
together.** `StagePlane` had three points in it and two names: `targetX` is the
mat point the spin, the tilt and the divide all turn about, and `centreX` was
both the middle of the glass *and* the place that pivot lands. So what the camera
was aimed at was always drawn dead centre, and the only way to get the horizon
onto the glass was to pitch until it walked down into frame — by which point the
table is edge-on and a card is a line. Splitting the second job out is `axisX` /
`axisY` and `CameraPose.shiftX` / `shiftY`, a photographer's **lens shift**.

**It is one subtraction, and the KDoc that refused it was right about a different
pan for the third time.** Iteration 16 found `CameraPose` refusing a pan on the
grounds that a movable vanishing point needs an off-axis inverse, and found that
half the paragraph described a *different* pan. This is that other pan — sliding
the finished picture — and it moves the vanishing point exactly as the paragraph
said it would, and the inverse is still closed form, because a shift moves the
*image* and not the *eye*. The divide is still centred on the pivot and the pivot
is still drawn at one known place; only that place has moved. `flatten` stays
exact, which is what every pile edge and airborne shadow is drawn through.

**The plan said the shift would buy the room its screen area for free. It does
not, and a throwaway test said so in about a minute.** The board is solved to
fill the stage vertically, so there is no slack to aim into: at the Seated seat
the bottom of the hand band is already at 99.8% of the screen, and any downward
shift pushes it off. Room comes from *sitting lower*. What the shift does is make
sitting lower fit.

Four things worth keeping:

- **A number in a plan is a guess until a test prints it.** "Put eye level in the
  upper third with a shift" was arithmetically impossible at a seated pitch — the
  horizon is `d·cot(pitch)` above the axis, which is over two stage-heights at
  thirty-four degrees. Fifteen minutes of scratch test replaced a paragraph of
  confident prose, and then replaced the shift constant too when the phone came
  out 0.139 against the tablet's 0.149.
- **The keystone is a function of the distance as a multiple of the floor, and
  the floor is linear in `sin(pitch)`, so that multiple is the same at every
  pitch.** Which means a low seat need not distort more than a high one — it has
  to step back proportionally. That is the whole reason `StageSeat.POV` can draw
  a card at exactly the width Seated draws it.
- **A bug was waiting for the first seat that declared a shift.** `CameraRig.step`
  sprang three fields and then assigned `pose = target` on the frame those three
  settled, which snapped the other four in one. Invisible for as long as every
  seat declared a pan of nothing, because the jump was always zero to zero. Both
  pans and both shifts spring now.
- **Three tests went red and every one of them was a fact about the seats wearing
  a claim's clothes.** "The centre of the mat is the one point that does not
  move" meant the middle of the *surface*; it means the optical axis. "Every seat
  is legal at every clearance" was true while every seat looked down from fifty
  degrees; a low seat is nearer the lens plane and the tightest close limit now
  holds it out, which is the knob working. And "the lamp's mirror is on the mat at
  every seat" was true for the same reason — from a chair the reflection has slid
  off the near edge, which is the *point* of the slide.

### Iteration 18 — the defocus dial had been dead since it shipped

kai, on the tuning panel: *"it's currently not working at all so you'll need to
examine how it works and decide from there."* Three faults, and two of them the
same mistake twice.

**It is hard-zeroed on `Scene.MINIMAL`, which is the room the app opens in.**
That zero is deliberate — a haze over `#060608` is a grey rectangle marking where
the room ends — but nothing said so, and the default scene is the one where the
slider does nothing wherever you put it. The note under the knob says it now.

**And both of its constants were quoted against the camera distance when the
thing they are about is the depth of the board.** `StagePlane.project` reports
depth in units of `cameraDistance`; the board occupies 0.027 of that overhead,
0.124 at the table seat, 0.209 seated and 0.319 at the new POV chair. The focus
plane travelled ±0.5 — four to thirty-seven times the table — so most of that
slider moved a plane already past every card. The falloff was a gradient pinned
at f/8 and read off a far-edge fraction somebody chose, which came out at nine
per cent of the dial at the table seat and **one and a half per cent** overhead.
There was no setting of the three knobs at which the far edge was visibly softer
than the near one.

`StagePlane.depthReach` is the fix for both: the projection reporting on itself,
exactly as `perspectiveGrowth` does. Everything divides by it, so ±1 on the plane
dial is the far corner and the near one at every seat — and because the reach
divides out, **the far corner reads identically at every seat**, across four
seats whose depths differ by a factor of twelve.

Three things worth keeping:

- **Pin a scale at the end of its range, not in the middle of it.** The old
  gradient was quoted at f/8, which meant it was really quoted at whatever
  far-edge fraction the person choosing it had in mind — an unstated number, and
  therefore an unstated *seat*. `Defocus.WIDE_F` is f/2 and just saturates at the
  far corner; every longer stop follows by the reciprocal of the f-number, which
  is what an f-number is.
- **A range that cannot reach "obviously too much" hides its own right value.**
  Strength went from a quarter to a half — not taste, arithmetic: full defocus
  used to be past the end of the table, so the strongest thing the dial could put
  on a card was a tenth of its own number.
- **A literal in a test is a tripwire pointed at the wrong thing.**
  `StageTuningTest` hard-coded 0.25 as the defocus ceiling and went red for a
  range change with nothing wrong in it. It reads the knob's own `max` now, the
  way the two assertions above it already read the envelope's.

### Iteration 19 — the tablet becomes a window

`docs/classic/AAA.md` #8, unbuilt since the list was written and the only head-parallax
idea anywhere in the corpus: *"let the tablet's own tilt move it a degree or
two… the cheapest three-dimensional tell that exists on a handheld."*

**The obvious implementation is worthless, and iteration 17 is why it is
obvious.** The lens shift that release built is exactly the term a parallax
effect reaches for — and a shift moves every pixel by the same amount *by
construction*, so near and far move together and there is no parallax at all. It
is a picture being dragged. What makes a handheld screen read as a window is that
things at different depths move by *different* amounts, and the only thing that
does that is moving the eye. So `HeadSway` produces a small yaw and pitch, which
is what #8 asked for in the first place.

Four things it is built out of, and three are rules this loop already had:

- **It is added beside the rig, never written into it.** In `CameraRig.pose` it
  would fight every drag (a gesture assigns; a sway a frame later assigns over
  it), cancel every coast, and — the quiet one — stop `Turns.seatAt` ever naming
  a seat, so the readout and the detent would both go dead the moment a wrist
  moved. It is also what keeps the panel's **Read camera** honest: it reads a
  pose somebody chose, not that pose plus whatever angle the tablet was at.
- **"Nothing idles" is satisfied by a latch rather than by an assertion.** A
  rotation vector is never exactly still, so a naive version redraws sixty cards
  forever because a tablet is a hundredth of a degree off level. `step` returns
  false once the filter has arrived, which is how the frame loop knows not to
  write the plane, and a deadband throws away anything under two degrees outright.
- **The reference is the first sample, not level.** Nobody holds a tablet flat —
  it is on a stand, on a lap, propped on a knee — and a sway measured against
  level arrives pinned to its limit and stays there, which reads as the camera
  being broken rather than as the feature being on.
- **The desktop actual is empty rather than zero.** `:studio` draws the real
  `PlayScreen` on that target and two runs being bit-identical is the loop's only
  instrument. A seam that *could* report something would put that guarantee in a
  runtime condition instead of in the type system.

And one number the test caught immediately: `GAIN` was a sixth, its own KDoc
claimed a wrist's twelve degrees reached the two-degree limit, and a sixth
reaches 1.67. It is derived from the other two constants now
(`LIMIT / (WRIST - DEADBAND)`), so the three cannot drift apart again. That is
the third time this project has found a constant disagreeing with the paragraph
above it, and the second time in three iterations.

It ships **off**, beside `cameraTouch`, which is off for the same reason: it is
the second thing on this stage that moves without anybody deciding to move it,
and the first one had to be switched off after kai played on it. Whether this is
the best thing here or a wobble cannot be settled from a contact sheet.

### Iteration 20 — the table hit-tests on the felt and draws in the air

Four defects from the tablet, and **one cause behind three of them**. The stage
projects everything with a height through `StagePlane`, so a card that is lifted
or leaned is *drawn* up-table of the mat coordinate it occupies — while a finger
arrives on the glass and unprojects onto the felt, at z = 0, where that offset is
zero. `StagePlane.raise` exists to close exactly that gap and was called in one
place, for a spread pile. The hand and every carried card were left on the felt,
and kai's tuning — lean −24° → −32°, lift ×1.0 → ×1.6, hand lift ratio 1.6 →
2.18 — roughly doubled the error, which is why it went from imprecise to
unusable.

Measured at his camera (2960×1848, 41.5°, distance 1.145):

| | lift | drawn up-table by | what it cost |
|---|---|---|---|
| hand card at rest | 124 mat px | 63 px, 23% of a card | **28% of the card dead to touch**, live band 76 screen px below what you see |
| carried board card | 151 px | 78 px, 29% | drops land a third of a card nearer you |
| **carried hand card** | 330 px | **195 px, 71%** | drops land a whole row nearer you |

That last row **is** "a set monster lands in attack position". You aim the card
you can see at a monster zone; the drop resolves in the spell/trap row;
`SetPosition` correctly answers *upright* for a spell/trap zone. Nothing in the
set logic was ever wrong, and the second-order cause — `DropCommit` throwing the
solved position away for a card already on the mat — had been cancelling against
a carry that started at zero quarter turns, so fixing either alone would have
stood every defence monster up when it was nudged.

Three fixes, and the shape of each is the same: **ask the geometry rather than a
comment.**

- **The hand is hit-tested against the quad it is drawn as** — `CardSolid.face`
  through `StagePlane.flatten`, which is literally the expression `StagedCard`
  already builds its homography from. A leaned card is a quad at *two* heights,
  so `raise` cannot fix it and the KDoc that said the offset "still lies well
  inside its own footprint" could not have been fixed either. It was true at the
  shipped lean and false at kai's. A comment is not a guard.
- **A drop lands where the card is drawn.** `CarryHeight` is the one place the
  three lifts are worked out and the one place the offset is applied; `Carry`
  gained a `landing` beside its `at`, so the finger stays where it has always
  been drawn — a little below the card, which is the only reason a card being
  dragged on a touchscreen is not under a thumb.
- **The `cards` map is pruned.** Tapping the deck gives forty cards a seat at a
  `PileFan` coordinate centred on the middle of the board; closing the fan takes
  the seats away and left forty `StageCard`s parked there, stepped every frame,
  so the next appearance sprang from mid-board. kai's "it may be doing it from an
  invisible fan" was exact.

And the fourth report cost one line: `MatDesk.MAX_LANES` 2 → 10. Everything under
it was already collection-shaped, and **the existing routing already is the palm
rejection** — a lane only opens for a finger that lands on a token, so a palm on
the felt still joins the nearest gesture at any cap. What it did cost is the
three-finger guards, which only ever fired because a third contact was *forced*
into a full lane; `MatThreeFingerTest`'s sixteen tests stay green while the app
changes, because they drive the machine directly and never see a lane. Said out
loud here rather than discovered later.

### Iteration 21 — kai's room becomes the room

The twenty-six numbers he exported become the defaults, and five invariants went
red against them. Four were real, and the fifth is a lesson about tests.

- **The lamp's source no longer scales with the lamp.** `lampScale` multiplied
  the light's angular radius as well as every radius in the profile, on the
  reasonable ground that a bigger lamp is bigger. But the lamp is drawn
  dishonestly on purpose — its light is solved and its mast is chosen, at about
  two-fifths of it — so that number is a decision about the picture. At 2.02 the
  coupling took the night key past the *window's* own angular size, and
  `CardShadowTest`'s two claims about night being a room rather than a colour
  grade both failed. The night room's shadows are the thing that makes it a
  room.
- **The lamp steps forward off the wall.** It is placed as a fraction of the
  mat's depth from the far edge, and the wall may come within `WALL_MIN_BACK` of
  that same edge; a shade 1.3 card widths deep reaches through it. Furniture
  yields to the room.
- **The wall stands 3.9 card heights rather than 3.2.** From the POV seat — 32°
  above the felt, which is new — its top edge landed at y = 29 on an 856-pixel
  stage, leaving a strip of void along the top of the picture.
- **`SceneryTest` was solving its layout without `ROOM_ABOVE`.** A board that
  does not decline a fifth of the stage fills it, so cards are a fifth bigger and
  so is everything measured in card heights. That fixture is why the wall's hole
  was invisible, and why a window at kai's head height read as *off* the glass at
  three seats when on the real layout it is on at all four. A test fixture that
  is not the configuration the app ships is a test of a different app.

The fifth: `HandFanTest` probed the exact midpoint between two hand slots, which
is the rounding boundary. Moving the default step from 0.62 of a card to 0.74
took the arithmetic from 1.5 to 1.4999999 and the test failed without anything
about the hand having changed. A probe on a tie is a test of the FPU.

Eight `Scenery` constants were deleted rather than updated. They were the
"record of what shipped" and nothing read them, so by the time the defaults moved
every one disagreed with the room the app draws — and one carried a
*measurement*, that a window on this wall is either low or invisible, which had
stopped being true when the board learned to decline a fifth of the stage.
`RoomTune`'s defaults are the room now, and `StageTuningTest` is the only place
the numbers are written twice, which is that test's whole job.


### Iteration 22 — two targets on top of each other, and a row drawn one way and measured another

Four reports off the tablet, and no two of them had the same cause.

**A searched card could not reach the board.** `PileFan` spreads a pile over
`layout.field`, which *is* the seven-by-three grid, and every fan card is drawn
lifted — so the felt footprint of the slot a card came out of lands 34 mat
pixels, a fifth of a card, from a monster zone's centre. Rank 0 of
`DropTargets.resolve` is "put it back where it came from" with a half-card enter
disc, whose own KDoc called it *"the tightest catchment on the board, and
deliberately so"*. Measured, it is 94 pixels against the 104-pixel zone disc it
outranks — 91% of it. The hysteresis then made it terminal: once "Put back"
latched, every point inside the zone was still within the 166-pixel sticky
radius, so nudging the finger could never escape. Every card of a six-card
graveyard search lost the entire monster row.

Size cannot separate two targets that sit on top of each other, and the fix is
that neither of the two new gates is about size. `FanHome` adds **history** — a
put-back is a change of mind, and a change of mind needs a mind that changed, so
the gap is not a target until the card has been carried 1.1 card widths clear of
it. Rank 0 then also asks whether a **zone is pulling harder**, through the same
`nearestZone` rank 3 uses so the two cannot answer differently. Either gate alone
leaves aims lost, and the two failures are different: the latch alone fixes a
straight aim and leaves a drag that wanders before it aims failing at exactly the
old rate, and the comparison alone fixes the zones and leaves the piles, because
a slot at the end of a row is nearer the graveyard than any zone is.

The test that shipped the bug is the more useful finding. `PutBackTest` asserted
"a fan is open and the board still takes cards" about **one hand-picked
`MatPoint`** — and that point was not even clear of the board: it sat an eighth
of a card from a monster zone's centre, so the file's own guard was a claim about
a coincidence. It now drives real `PileFan` output through the real projection,
over every slot of a 40-, 15- and 6-card spread, against every zone and every
pile, along a straight aim and along a path that wanders first.

**A pile left the table when its top card did.** Mine, from iteration 20: a pile
is drawn as *one* seat, its top card's face at `pile.size` thick, so the line
added to settle a duplicate-seat collision — skip the pile seat while its top
card is carried — took the whole graveyard off the board for the length of the
drag. It draws the pile *minus what is in the air* now, which has an instance id
of its own and so cannot collide. `Seat.bornAt` stops the newly revealed card
flying in from the deck's square to a place it never left.

**Two hands out of one deck, and letting one go put the other back.** The
`stillHolds` guard, working exactly as designed and as documented — *"the honest
answer to 'the board moved under this gesture' is to put the card back, not to
guess which card the user now means"*. It was a false dichotomy: the gesture was
already carrying the card's own identity, so it does not have to guess and it
does not have to refuse. `PlayField.rebase` asks where the held instance is now,
within the place it came from and never across one, and still refuses when the
card has genuinely left. Four render sites that also indexed by position now ask
by identity — without that, a carried card visibly becomes a *different card* the
instant the other hand commits.

**And the hand was drawn one way and measured another.** Three independent
designs found this without being told: `seatsFor` drew `hand.size` places while
`HandFan.insertAt` measured `count - 1` and corrected for the card in the air.
Both defensible, not the same row, and 0.5 of a step apart — 0.37 of a card
width, which is most of the width of the gap the caret was pointing at.
`HandRow` is the row itself, one entry per place, and the four readers read it.
`insertAt` counts drawn cards rather than dividing by a step, which is the only
form that inverts a row with a hole in it, and the gap it names is the gap the
row is already holding open. That fixed point is swept over every hand size, every
card that could be in the air and four hundred finger positions, because a row
that re-asked and got a different answer would flicker at sixty frames a second.

The affordance follows from it: the hole is *there* rather than drawn beside a row
that never moved. The spread does the same through `FanParting`, paying for its
window out of the row's slack first and out of its own overlap when there is
none — a forty-card deck leaves 0.18 of a card, which is why the second currency
exists at all.

**What the workflow was worth, since this was the first design run of the loop.**
Three designs, two judges, and both judges independently implemented the
proposals and *simulated the drag frame by frame* rather than reading them. That
is what caught the thing no design's own test list would have: the arming latch
on its own, which every design's proposed tests would have shipped green, fails
at today's exact rate on a drag that wanders before it aims — because those tests
all walked straight from the slot to the target, which is the one path arming
fixes. The second gate exists because a judge measured 40 lost aims out of 728
on the path a player actually makes.

### Iteration 23 — the chair, and a number worth more than the distance it arbitrated

kai asked for a AAA first-person game, answered the four questions
`docs/classic/PHOTOREAL.md` ends on, and the first of them is a seat: **`StageSeat.POV`
becomes the pose the stage opens at.** Thirty-two degrees of elevation instead of
48.5, the room going from a fifth of the picture to about half, and a card losing
about a third of its drawn *height* to `cos(pitch)` — which is what looking at a
table from a chair does, and is why the hold-to-read card reader exists.

**Two things had to be fixed before the seat could move, and neither was the
seat.** The first is that `CameraTune`'s defaults had never reached the camera at
all: the `SideEffect` that places the opening pose guards on
`placed[0] != tune.camera`, and `placed` was seeded with
`StageTuning.DEFAULT.camera` — so on any device whose stored tuning still equalled
the defaults it compared them against themselves and placed nothing, leaving the
camera where `CameraRig(seat = StageSeat.TABLE)` built it. Iteration 21 made kai's
own export the defaults and that is why it did not change what he opened on. The
studio could not see it either, because every shot types a seat digit first; a
shot named `opening` presses nothing, and it is what confirmed the fix rather than
argued it.

The second is the one worth remembering. `PutBackTest` is not a test of pinned
numbers — it drives real `PileFan` geometry through the *default* pose over every
slot of three spreads — and at 58 degrees **two of its assertions failed in
opposite directions**: a slot could no longer reach a monster zone, and a
different slot could no longer be put back. Opposite failures are the signature of
a threshold that is larger than the thing it is deciding between, and a probe said
so exactly: a spread's holes and the zones sit on the same grid, separated only
because a fanned card is drawn lifted, and that separation grows with
`sin(tilt)` — so the nearest hole-to-zone distance is **0.178 card widths at 41.5
degrees and 0.072 at 58**, against an `INCUMBENT_BIAS` of **0.12**. At the old
seat the bias was comfortably smaller than the gap; at the new one it was larger,
so a latched put-back beat a zone the finger pointed exactly at and a latched zone
beat a hole the finger pointed exactly at.

The fix is not a smaller bias, because that loses the anti-flicker the bias is
for. It is that **hysteresis on a two-way contest may never be worth more than
half the distance between the two candidates** — capped there, neither can carry
past the other's centre, by construction, at any angle. Where the two are
comfortably apart it is still the whole bias, so iteration 22's tuning is
untouched. `bothGesturesSurviveEveryPitchTheStageCanOpenAt` sweeps seven pitches
from 21 to 72 rather than the four seats, because the camera is free and somebody
searching a graveyard at sixty-five degrees is still searching a graveyard.

Three things found while measuring, all kept. **The clearance had to go to 0.9,
not the 0.62 a 16:10 stage suggests** — `minDistanceAt(58) <= 1.33` needs 0.647 on
a Pixel 8, 0.650 on an S24 and 0.662 on `phone-small`, and 0.9 is
`CameraEnvelope.DEFAULT_CLEARANCE`, at which POV's distance is *exactly* 1.5x the
floor. The seat was solved against the shipped envelope all along; carrying kai's
tighter 0.59 underneath it was the mismatch. **The previously shipped default was
already being clamped on `phone-small`** (floor 1.1663 against distance 1.1446),
so one box had never opened where the other six did. And **only the aspect ratio
matters** — `reach` and `governing` both scale with the surface — which is why
`theOpeningPoseIsLegalOnEveryScreenTheAppRunsOn` sweeps dp boxes and ignores
density.

What the picture shows: the window is a window, the lamp has a stem and a foot,
and there is a room behind the desk instead of a strip above it. What it also
shows, now that half the frame is room: the wall is a flat fill, the desk grain
reads as ripples rather than timber, and nothing in the room casts a shadow. Those
are phases 6 and 7 and they are next.

---

### Iteration 24 — shinier meant dimmer, and a foil had no light at all

Iteration 23's release said the anisotropic streak was built. It was, and it did
nothing, and the studio's new finger is what proved it: tap the extra deck open
and the pictures come back **byte-identical** with the anisotropy on and off —
and byte-identical again with `Foil.specular` cranked from 0.52 to 1.0. A
temporary fill gated on the draw threshold drew on nothing at all. The shape was
right and the term it shaped was zero.

The measurement, for a card lying flat at the seat the stage opens at: a foil's
specular was **0.00052** under the day rig and 0.0012 at night, against a draw
threshold of 0.004. So the material carrying the highest specular constant on the
table was the only one with no highlight — **sixty-four times dimmer than a
sleeve**. That is `docs/classic/LOOP.md`'s oldest impression, *"nothing on a card catches
the light"*, turned into a number.

Two things were wrong and only one of them was obvious.

**The lobe was never energy-normalised.** Raising a number below one to a higher
power can only remove light, so every increase in `shininess` was quietly a
reduction in how much of the lamp came back: "shinier" meant "dimmer", which is
not what the word means. `(n + 8) / 8pi` is the standard fix and it is now there.

**And normalisation alone does not reach it** — 2.07x on a number three orders
under the threshold. What reaches it is a **second lobe**, and the argument is a
surface rather than a floor bolted under a number: printed stock is varnished,
the varnish is smooth where the stock is not, and a broad weak lobe is what a
varnish returns. It is why a matte card still has a sheen when you tilt it. So
`CardMaterial.coat` is how much lacquer is over the finish — most on a sleeve,
which *is* a sheet of plastic, least on a foil, where the holographic layer is
the thing you are meant to see — and the finish lobe is what still *flashes*
when the angle comes good.

Two attempts did not work and are worth not repeating. Putting the anisotropy
into the *intensity* (Ashikhmin-Shirley, so a ruled stock answers broadly along
its grooves) is physically right and swung a foil's brightness **640x** with card
rotation, 0.0005 to 0.32 — which is not a material, it is a strobe, and it fights
`DESIGN.md` §7's "the highlight moves, the brightness does not". And the first
tuning left the peak at 0.600 on a well-aligned foil, which is `PHOTOREAL.md`'s
predicted clip: it says in as many words that normalising makes foil clip unless
the constants come down in the same change. They came down — foil's specular
0.52 to 0.30 — and the peak is 0.40.

`GoldenStageTest` moved and only its `spec` column moved: every face, solid,
shadow, `diff`, `rim` and `hot` line is identical, which is the whole point of a
recording with one identifiable cause. `Shading.FAINTEST` moved into core in the
same change, because the threshold is the line the *shading* has to clear and a
magic number in the renderer cannot be asserted against.

What the picture shows: 3.4% of pixels at the opening frame, 6.3% with the extra
deck open, peak 7 of 255. **That is honest and it is small**, because the day key
is oblique and a card lying flat under it genuinely does not blaze. The
structural defect is gone; how bright a card highlight should *be* is a taste
call, it belongs with the rim constant and the exposure, and it wants the tablet.

---

### Iteration 25 — the room is looked at through a lens

The first thing on this stage that is about the *camera* rather than about any
surface. One `RenderEffect` on one layer above the mat — never on the mat's own,
because that layer carries the yaw, the tilt and the camera distance, so a
vignette drawn there is an ellipse whose centre walks off the optical axis as
the table turns. `DESIGN.md` §6 was widened for it first, in its own commit.

Three artefacts, each there because its *absence* is what makes a render look
rendered. **cos⁴ vignette**, in linear, about the true optical axis — not a taste
dial: the falloff is the projection's own, and at the reference stage it is 0.77
EV at the horizontal edge and a full stop in the corner. **Grain**, under the
envelope `σ(u) = σmax·2√(u(1−u))`, which is exactly zero at code 0 and at code
255 *by construction* — the only form that can coexist with a true-black design,
because uniform-amplitude grain speckles `#000000` and ends the identity. And
**lateral chromatic aberration** as a magnification difference rather than a
constant offset, at 0.45 px in the corner of a Tab S11: deliberately invisible,
which is the exact opposite of `Prismatic.kt`'s fringes and why it is kept out of
their namespace.

Measured rather than admired. A patch of wall that was a flat **62.00 across the
whole frame** now reads 61.3 in the middle and **47.8 at the edge**; its
neighbour-difference goes from **0.000 to 0.958**, which is the grain existing.
And `Scene.MINIMAL` comes back ungraded — three flat corners at mean 6.00 with
neighbour-difference **0.000** — which is the handbook's stage staying the
handbook's stage.

What is not in it: no bloom and no depth of field, which want a second pass or a
layer per card, and **no tonemap**, because AgX cannot land while `Tone.veil`
survives — under it the veil a card wants goes negative and a black overlay can
only darken. And nothing here idles: the grain is a function of the pixel and not
of the clock, fixed in the frame like grain on a plate.

The one debt this leaves is the parity gap `PHOTOREAL.md` §0 predicted: the
shader text is unreachable from `commonTest`, so the vignette's curve and the
grain's envelope are asserted by a measurement in this entry rather than by a
claim in core. Generating the SkSL from core constants is the fix and it is not
done.

---

### Iteration 26 — the room was a flat, and its walls were in the air

Two defects wearing the same black, found by counting rather than by looking. A
17x11 sweep of the glass, over yaw, asking of every grid point whether *any*
projected face covers it: **ten holes at thirty degrees of yaw**, none at zero.
Which is the whole of what "the room is a flat behind a desk" means, stated as a
number.

**It had no sides.** The camera turns freely now — that is iterations 16 and 17
and kai's "complete freedom and control" — and a single back wall has nothing at
its edges the moment it does. Two returns close it, from the back wall's front
face toward the player, meeting the back wall along one edge each and sharing no
volume with it, which is what `ScenePainter`'s separating axis needs.

**And no wall reached the floor.** Every one of them stood on z = 0 — a desk's
height in the air — and the desk hid the void under them from straight ahead and
from nowhere else. Two of the ten holes were that, and it is the older bug: the
wall's KDoc had recorded a skirt being *removed* for a paint-order reason, which
was correct about the symptom (one tall box from the floor to the wall top shares
volume with the desk) and wrong about the fix. The right shape is three skirts,
because a wall that reaches the floor has to be **two boxes**:
`noPieceOfTheRoomStraddlesTheDeskTop` is what makes `ground` and `standing` a
partition, and that partition is where the felt goes. The seam costs nothing —
the halves share a face — and the desk now stops at the wall's face instead of
running under it, because the strip under the wall belongs to the skirt.

Underneath both was **one number too many**. The wall stood 1.12 desk-spans out;
the floor ran eight card widths past the desk. Two numbers for one room is a room
with a gap in it, and the gap was 530 pixels wide: past the wall's end you were
looking over the floor's far edge into nothing. The room has one plan now.
`ROOM_MARGIN` is the rectangle, the floor tiles it, the three walls stand on its
edges, and `FLOOR_MARGIN` is half a card of skirting so there is floor under a
wall's thickness. `ROOM_DEPTH` is measured rather than chosen: the bottom corner
of the glass looks past the room's near end at a quarter turn, and the hole closes
at eight card widths and does not get smaller at sixteen.

Ten holes at thirty degrees, **none anywhere through a quarter turn either way**.
The limit is honest rather than convenient: three walls and no ceiling means the
top corner of the glass looks over the wall past about fifty degrees of yaw, and
at eighty degrees of *pitch* the top third of the picture is void — those are
objects to build, and they are phase 8's.

Two tests moved rather than being deleted, which is the half of this worth
keeping. The piece budget goes from twenty to twenty-four **on the record**: it
was written when the room was a flat, and closing it costs five. And the back
wall's wash is no longer monotone — the wall runs past the lamp now, so it peaks
opposite the lamp and falls away in both directions, which is what a pool is. The
old claim was an artefact of a wall that stopped short of it, and the wall with no
lamp in front of it carries the monotone half instead.

At night none of this is visible, and that is worth saying plainly: the room falls
away so far from the lamp that a hole and a wall are the same colour. The day room
is where it shows, and the crop that proves it is the desk's right-hand edge at
thirty-five degrees — a pure-black wedge before, a lit wall after.

---

## 6. Seen, not yet done

Things a look has already found, so the next iteration does not have to find
them again. Not a backlog — `AAA.md` and `FIDELITY.md` are that — just a list of
what the eye has caught and the hand has not reached.

- **A 35-card deck is drawn as about seven thick slabs.** The pile's ruled side
  is evenly banded at a spacing that does not follow the count, so a deck reads
  as corrugated card rather than as a stack of thin ones. `CardSolid.pileDepth`
  and whatever draws the side. This is the biggest object on the table and the
  most obviously wrong thing left in the frame.
- **A shadow on true black has nowhere to go.** The hand's cast shadows are
  drawn and correct and invisible, because the felt they land on is `#0E0E12`.
  `AAA.md` #18 (shadows are not black) and #65 (the felt has a weave) are one
  item, not two: neither is worth doing without the other.
- **Nothing on a card catches the light.** `Shading.of` puts a specular pool on
  the stock and at these sizes it is not reaching the picture. Worth measuring
  before assuming it needs to be stronger — the handbook is explicit that the
  highlight *moves* rather than brightens (§7).
- **The first run against a cold art cache is not comparable.** Iteration 8's
  before-shot showed 4.3% of the *minimal* stage moving, which no change in the
  tree could have caused; re-shooting the previous commit gave a bit-identical
  picture. Coil serves a lower-resolution decode until the art lands, and the
  shot that comes first in a cold run gets it. Warm the cache with a throwaway
  shot before recording a before.
- ~~**The eye can only reach three seats by digit.**~~ **Fixed.** Every
  unparameterised shot used to be one of 5°, 21° or 34° — all of them somebody
  standing over the table, and none of them the seat the stage now opens at.
  `Seat.POV` is on `4`, `pov` (or `head`, or `chair`) names it in a shot, and
  `desk-night-pov` is in the contact sheet. **And the envelope is aimable:**
  `--pitch=` and `--yaw=` move the whole run, so eighty degrees — where a
  procedural surface aliases worst and where the table's horizon comes onto the
  glass — is one flag rather than a hand-written tuning file. It is a *run*
  rather than a shot on purpose: writing a tuning per shot beside the room is
  the race iteration 10 recorded, where the seat press and the preferences
  write landed in an order the dispatcher chose. An aimed run then ignores the
  seat in every name, by the rule that already existed — a tuning carrying a
  camera *is* the camera.
- ~~**The card specular is not reaching the picture.**~~ **Fixed — iteration 24.**
  Kept because the measurement is the useful half.
 `docs/classic/LOOP.md` has carried *"nothing on a card catches the light"*
  as an impression since iteration 0, with the note that it was *"worth
  measuring before assuming it needs to be stronger"*. Measured, at the seat the
  stage now opens at, for a card lying flat:

  | | `Minimal` | `DeskDay` | `DeskNight` |
  |---|---|---|---|
  | Sleeve (`specular` 0.16) | 0.086 | 0.033 | 0.040 |
  | Gloss (0.34) | 0.068 | 0.0058 | 0.0094 |
  | **Foil (0.52)** | 0.034 | **0.00052** | **0.0012** |

  `drawCardSurface` skips the whole block below 0.004, so **a foil lying on the
  desk has no highlight at all in either Desk room** — which is where every foil
  on this table lives. And the order is inverted: the material with the *highest*
  specular constant is the dimmest thing on the stage, sixty-four times dimmer
  than a sleeve, because `shininess = 44` takes the half-vector alignment to the
  forty-fourth power and a lobe that tight is extinguished by any deviation at
  all. Proved rather than argued: a temporary fill gated on
  `shade.specular > 0.004f` drew nothing on any foil in the frame, and cranking
  `Foil.specular` from 0.52 to 1.0 gave a **byte-identical** picture.

  What fixed it was not a bigger number: a **second, broad lobe** for the
  varnish every printed card carries, plus the `(n + 8) / 8pi` normalisation the
  single lobe never had. Every stock now clears the threshold in every room, and
  `ShadingTest` says so over the whole matrix rather than at one pose.
- **The eye cannot see a foil, and that is now the biggest hole in it.**
  `CardStock.of` gives `CardMaterial.Foil` to face-up **extra-deck** cards only,
  and nothing in the play stage's shortcut table opens the extra deck — `f`
  searches the main deck, and spreading a pile is a tap. So the studio cannot
  photograph the anisotropic streak (`AAA.md` #21, built and unphotographed),
  the prismatic ramp on a foil, or anything stage 8's diffraction grating will
  do. `Keys.kt` refuses to name keys the app does not bind, which is the right
  rule and rules out a fake shortcut; the fix is either a pointer tap the
  studio can send, or a real shortcut the players are arguably missing too.
- **Driving a real orbit** — a pointer drag on the felt, which `MatInput`
  already understands — is now the cheapest remaining upgrade to the loop's own
  perception, and it is the same mechanism the entry above needs. Everything
  here is still a still.
- ~~**Moving `CameraTune`'s defaults to the POV seat is two lines and one real
  regression.**~~ **Done — iteration 23.** Kept below because the measurement is
  the useful part and the next person to move a threshold on this table should
  read it. The numbers are settled: the
  pose is `StageSeat.POV.pose` exactly, and `clearance` must go to **0.9**, not
  to the 0.62 a 16:10 stage alone suggests. `minDistanceAt(58) <= 1.33` needs
  0.614 on 16:10, 0.647 on a Pixel 8, 0.650 on an S24 and **0.662** on
  `phone-small`, so a number solved on the reference stage fails on three of the
  seven boxes in `docs/classic/DEVICES.md`. 0.9 is not a number chosen to clear that: it
  is `CameraEnvelope.DEFAULT_CLEARANCE`, and POV's distance is *exactly* 1.5x
  the floor at it (1.33341 against 1.33), which is the derivation POV's own KDoc
  gives. The seat was solved against the shipped envelope; carrying kai's
  tighter 0.59 underneath it was the mismatch. Only the aspect ratio matters —
  `reach` and `governing` both scale with the surface — so densities do not
  enter it.
  **What blocks it is `PutBackTest`**, which is not a test of pinned numbers: it
  drives real `PileFan` geometry through `StageTuning.DEFAULT.camera.pose()`
  over every slot of three spreads. At 58 degrees two assertions fail and they
  fail in **opposite directions** — slot 16 of a 40-card spread can no longer
  reach `ExtraMonster(1)` after wandering (put-back wins when it should not),
  and slot 13 will no longer go back (a zone wins when put-back should). Opposite
  failures mean tightening either gate breaks the other, so this is a
  re-derivation rather than a nudge. The mechanism is in `DropIntent`'s own
  KDoc, which measured the separation as *"34 mat pixels on the shipped stage"*:
  a fanned card is drawn lifted, so its felt footprint sits up-table of its slot
  by an offset that grows with `sin(tilt)` — 0.6626 to 0.8480, a factor of
  **1.28**, taking those 34 pixels to about 43.5 and pushing the hole further
  into the zone discs it is contesting. Iteration 22 separated those two targets
  by history and by a rank-0 comparison at one tilt; making the separation a
  function of tilt is the work.
  One thing found on the way that is worth fixing on its own: the **currently
  shipped** default is already clamped on `phone-small` (floor 1.1663 against a
  distance of 1.1446), so one of the seven boxes has never opened where the other
  six do. The new defaults at 0.9 clear every box.
- **And the studio runs in a Claude session now.** It needs the Android SDK
  because `:ui` has an `androidTarget`, and that used to be the end of it. Both
  `dl.google.com` and Google's Maven are reachable from the sandbox: install
  `cmdline-tools` plus `platforms;android-36`, `pip install pillow`, write
  `app/local.properties`, and `tools/shoot.sh`, `compare.py` and `crop.py` all
  work. *"Never ship a picture you have not looked at"* stopped being a rule
  that had to be taken on trust.
