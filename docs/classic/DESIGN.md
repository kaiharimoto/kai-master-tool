> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

# kai's master tool — design handbook

The rules this app is drawn by. When a decision is not covered here, decide it
the way the nearest thing here was decided, then write it down.

One warning before anything else: **"material" in this document means physical
material — ink, card stock, light — and never Material Design.** The app is
built on Material 3 components because they are what Compose ships, and every
one of them is restyled on the way in. If something looks like stock Material,
that is a bug in this handbook's application, not a style choice.

---

## 1. What the app is

A deck builder that behaves like a desk with cards on it. Three convictions
follow from that, and everything else in this document is downstream of them:

1. **The deck is the interface.** Chrome is what you spend to see cards, and
   every dp of it is a dp the cards do not get. Controls earn their space or go
   in a menu.
2. **Nothing lies about the deck.** What is on screen is the deck as it is
   stored. A view may add space, colour or emphasis; it may not reorder,
   rename or invent. If a view would need to rearrange the deck to look good,
   the design is wrong.
3. **Only what you touch is alive.** Tilt, lift, sheen, sound and colour-as-
   light belong to the thing under the finger. Everything else holds still.

## 2. Ink and light

**True black, sharp white.** The stage is `#060608`; surfaces lift by luminance
alone (`#0E0E12`, `#1A1A21`, `#26262F`); separation is a hairline, never a
shadow. Light mode is the exact inversion with the same hues.

Colour appears for exactly two reasons. If a colour is doing neither, delete it.

- **Colour as meaning.** Card type, legality, deck section, group identity.
  Semantic colours are fixed and never decorative: `Danger #FF4D4D` (forbidden,
  illegal), `Warning #FFB020` (limited, caution), `Success #2CE08B`,
  `Info #39B8FF`.
- **Colour as light.** The six-hue prismatic ramp — red, amber, green, cyan,
  violet, magenta — is white light through a lens. It fringes what is being
  interacted with (`ui/theme/Prismatic.kt`), and it is the palette user-defined
  groups draw from. Groups take green, cyan, violet and magenta before red and
  amber, because those two already mean *illegal* and *warning* everywhere else
  and a group wearing them reads as a problem.

**And one thing that is neither, which is the user's.** The **foil** edge —
`drawPrismaticInset`, white → hot pink → cyan → white in a ring four per cent
inside the card, at an angle that follows the hand — is on every card *face* in
the app. It is not a fringe: two hues rather than six, its own two hex values
rather than the aberration pair, inside the card rather than glowing outside it,
and it means nothing at all. It is what a sleeved card does under a light, it is
what the original tool drew on every card in its deck grid, and kai asked for it
back by name after it had failed to make the crossing into this one.

The rule it appears to break is the one directly above, and the resolution is
that this is a **preference** (`UiPreferences.prismaticCards`), on by default and
one menu item from off. "Colour as meaning or as light" is how *the app* is
allowed to spend colour, on its own initiative, everywhere the user has not said
otherwise. Somebody asking for their cards to be foiled is not the app spending
anything.

**And never on a back.** A foil is what a sleeve does to the *printed* side; on
the back it is a rainbow outline round the one thing on the table that is meant
to be anonymous, and since the deck, the graveyard and every set card are backs,
it turned every pile in the room into a neon rectangle. One condition, in
`CardFace`, which is one side of a card by construction.

**Solid, not translucent.** A tint over a card is a colour you cannot name and a
card you cannot read. When something must be coloured, colour the space around
it — the frame, the gutter, the edge — at full strength. Translucency is for
light effects (sheen, fringe), never for identity.

**A fact gets one colour, forever.** Card type and legality were coloured long
before any lens existed — the statistics panel's type split, the ban badge in
the corner of a card. When a new view shows the same fact it takes the colour
that fact already has (`KeyPaint.Tone`), never a fresh one, or the screen ends
up with two different reds meaning one thing.

**Six hues is a hard limit, so colour never carries a name alone.** Groups also
carry a two-letter mark (`GroupMarks`), rendered identically on the deck, in the
legend and in menus. Reading beats hue-matching at arm's length.

## 3. Type

**Archivo** throughout — a grotesque with punchy caps and solid numerals, which
is most of what this app sets. Bundled, OFL.

**Inter Medium** for the wordmark only, set lower case at natural tracking:
`kai's master tool`. Helvetica Neue is the reference and cannot ship (Linotype's,
and absent on Android), so the closest free neo-grotesque travels with the app
instead. Nothing else uses this family; swapping in a licensed Helvetica Neue is
a one-line change in `wordmarkFamily()`.

Scale is tuned for arm's length on a 14-inch tablet: larger than Material's
defaults, with tight tracking on headings so long card names stay on one line.
Labels are `labelMedium` and below; a control that needs bigger type is a
control that needs less text.

## 4. Geometry

**Square, or nearly.** Radii: 2dp on chips and marks, 4dp on cards, 6dp on
panes, 8dp on sheets. Nothing rounder. Cards are rectangles and a builder full
of pill shapes fights its content.

**Spacing is a scale, not a feeling:** 2, 4, 6, 8, 12, 16, 24. Inside a
component use the small end; between components use the large end.

**The card is 59:86**, always, everywhere, at every size. Never crop, never
letterbox, never stretch.

**Hairlines separate; shadows lift.** A 1dp `#26262F` line is the default
boundary. A shadow means the thing has left the surface and is in your hand.

## 5. Density and the fitter

Layout is solved, not negotiated. `core/layout/DeckFit.kt` sizes all three deck
panes in one pass:

- **Row widths are the input** — ten across for the main deck, fifteen for the
  extra and side, because that is how a decklist is read.
- **Row counts follow from the deck**, with the main deck sized for forty from
  the start so nothing reflows until the deck outgrows it.
- **The one free variable is the width the whole stack is drawn at**, solved so
  the three panes exactly spend the height available. Leftover width is
  negative space *around* the centred stack, never a margin inside one pane.

Two rules follow, and both have been learned the hard way:

- **Anything the layout spends must be declared to the fitter.** A bar that
  appears without being counted is height the cards were promised and did not
  get, and cards end up out of their box.
- **Card size may depend on the window and the deck's size. Nothing else.** Not
  on which mode is open, not on how many groups exist, not on how a partition
  falls. A size that changes when you assign a card is a size that flickers
  while you work.

## 6. Motion

**Springs, never curves.** `core/motion/Spring.kt`: `Snappy` (520) for snaps and
returns, `Bouncy` (380, ζ 0.62) for lifts and lands that should feel like mass,
`Calm` (120) for scene moves. Compose's `spring()` with the same constants for
anything simple enough not to need the frame loop.

**One loop, not one per object.** The sanctioned pattern for many moving things
is a single `withFrameNanos` loop with bulk state in plain lists and per-object
state read *inside* `graphicsLayer` — see `EasterEgg.kt` and `ui/play/StageCard.kt`.
Reading animated state in a composable body recomposes the tree sixty times a
second; reading it in a layer does not.

**Motion explains a change; it never announces one.** If nothing about the
deck changed, nothing moves. Staggers are for showing structure — pieces of a
deck arriving in order — and are measured in tens of milliseconds, not hundreds.

**Real geometry, no engine.** The rule used to read "fake 3D only", and the
second half of it still holds: there is no Filament, no OpenGL, no scene graph,
and there never will be — none of them reaches Kotlin Multiplatform common code,
and all of them would take the desktop target with them.

What changed is the first half. The geometry is now *real* and lives in
`core/render/`, tested like everything else in core: `Rot3` interprets the same
three Euler angles `graphicsLayer` is about to rasterise with, `CardSolid` gives
a card a thickness and six faces with normals, `Turned` is a lathe — a profile of
rings spun about the pose's own vertical, capped, and genuinely posed, which is
what everything round in the room is made of and the only mesh here whose facets
may carry the normals of the curve they stand for — `Shading` is Lambert plus
Blinn-Phong with a moving specular pool, `Shadows` casts by projecting every
corner along the light, and `StageRig` holds the one key, the one fill and the
one eye that all of it agrees about.

That reaches the screen through the two things Compose does give you: a
`graphicsLayer` is a genuine perspective-correct textured quad, and a canvas
will draw a path. `StagePlane.flatten` is the join between them — it rewrites a
point *with a height* as the point on the mat that will look like it once the
plane's own transform has run, so shadows, pile edges and card thickness can all
be drawn by a canvas that only speaks two dimensions.

**And, since the target became photographic, a third thing: a shader.** The rule
that stood here said the stage draws with paths and gradients and nothing else,
because a runtime shader is not common code. `AAA.md` #99 was that rule with a
question mark on it and kai has now answered it: the fishbowl should reach the
fidelity of a driving simulator, and no arrangement of paths and gradients does
that. What separates a surface from a fill colour is that its **normal varies
per pixel** and the light is asked about it there — felt with a weave, lacquer
with a clear coat, foil as a diffraction grating. All of those are per-pixel or
they are pretend.

`ui/gpu/StageShader.kt` is the seam: `expect fun compileStageShader`, actual on
Android over `RuntimeShader` and on desktop over Skia's `RuntimeEffect`. Four
rules hold it up.

**The no-*engine* rule is untouched and still right.** Nothing in a shader draws
geometry, holds a scene or owns a transform. Every vertex on this stage is still
solved by tested arithmetic in `:core`; a shader colours a rectangle that core
decided the shape of.

**`compile` returns null, never throws** — an Android below 33, a driver that
refuses, a typo in the SkSL. So every caller ships a drawing that works without
one, and that fallback is not a degraded mode to be tolerated: `minSdk` is 26,
and it is what shipped before the seam existed.

**Composite so a bug cannot blank the stage.** The felt's weave goes over the
mat in `BlendMode.Overlay`, whose identity is mid grey, so the shader emits 0.5
where the cloth is flat and the worst a mistake in it can do is push the felt
around a little. A shader that *replaces* a surface has to earn that separately.

**A shader may also colour the whole picture, once, above everything.** The rule
above governs a shader that colours *a surface*, and the seam it describes hands
back a `Brush`. There is a second seam — `StageEffect`, a `RenderEffect` on a
layer — and it exists for the things that are not about any surface at all: a
vignette, a grain, a tonemap, a lens's chromatic fringe. Those are facts about
the *camera*, and a camera does not belong to the felt or to a card.

Three conditions, and the first is the one that costs something.

**It goes on its own layer, above the mat's, and never on the mat's own.** That
layer already carries `rotationZ = -yaw; rotationX = tilt; cameraDistance`, so a
vignette drawn there is an ellipse whose centre walks off the optical axis as the
table turns — a lens artefact that moves with the subject is not a lens artefact.
The cost is an offscreen pass, and it is affordable *exactly once*: one
full-screen composite is about a twelfth of a tablet's memory bus, and one per
card is sixty of them and would end this stage. **Nothing that draws per card may
use this seam.**

**It is the Desk scenes' privilege, not the stage's.** `Scene.MINIMAL` is the
handbook's stage and is graded by nobody; the camera arrives with the room.

**And it degrades to nothing.** `compileStageEffect` returns null below API 33 or
on a driver that refuses, and the picture that comes back is simply the ungraded
one — which is what a third of Android sees and what shipped before this existed.

**`Light.direction` is the way the light travels.** The key is
`(0.30, 0.45, -0.84)`, heading *down* onto the table, and every dot product in a
shader wants the vector pointing back at the lamp. Handed the travel direction,
`max(dot(n, l), 0)` is zero on every thread of a surface facing up — so the
first weave compiled, ran, cost a full pass and returned overlay's identity
everywhere. It looked exactly like a shader that had failed to load.

## 7. Materials

The physical feeling to reproduce is handling a single card.

- **Tilt** toward the pointer or the pressing finger, ±7°, on a 480-stiffness
  spring.
- **Lift** with a shadow while held, and a 5% grow — the only place anything
  scales.
- **The highlight moves; the brightness does not.** This is the whole material
  model in one sentence. A card that gets *brighter* as you tilt it is a
  brightness animation; a pool of light that slides across the face is why you
  tilt a real card to read the small print. `Shading.of` puts the pool where a
  mirror at that angle would send the lamp, and lets it slide off the edge when
  it should — clamping it to the border pins a bright smear to a card that has
  simply stopped catching the light.
- **Distance may take contrast, and only contrast** (`core/render/Defocus.kt`).
  The rule above forbids a card changing brightness because the camera moved,
  and this is the one amendment to it. The two are not in conflict once the
  question is *whose* brightness: the highlight rule is about **shading** — what
  the lamp is doing to a surface — and this is about **atmosphere**, which is
  the air between the eye and the card. A far card losing its bite is not the
  light on it changing; it is you not being able to see it as well, which is
  true of every far thing anybody has ever looked at.

  Three limits keep it honest. It is a *contrast* falloff and never a
  brightness one, so nothing gets darker on average — the whites come toward the
  ground and the blacks come up with them. It is **off unless somebody turns it
  on**, because it is a look rather than a correction. And it is **zero on
  `Scene.MINIMAL`**: a haze over `#060608` is a grey rectangle where the room
  ends, and the mandate that suspends things for the desk scenes suspends
  nothing for the handbook's own stage.

  It is also honestly not depth of field, and calling it that in the UI would
  be a lie. There is no blur available — `BlurEffect` is API 31 against a
  `minSdk` of 26, and a `renderEffect` per card is the shape of change
  `docs/classic/PHOTOREAL.md` measured and called fatal. Nor is one worth much here:
  the whole depth span of the board is 55 mat pixels at the reading seat and
  425 at the seated one, so a physically-tuned circle of confusion is **zero
  pixels overhead and under three seated**, on a card 102 wide. What defocus
  destroys first is micro-contrast, and at three pixels that is the entire
  visible effect — so the cheap thing and the correct thing are the same thing.
- **Three stocks, chosen by what the card is** (`CardStock`): gloss for card
  stock in a sleeve, foil for extra-deck frames, matte for the back of a sleeve.
  Foil is brighter *and tighter*, which is what makes it read as metal rather
  than as brighter paper, and it is the only one that splits its highlight into
  the prismatic ramp — colour as light, inside the specular term or nowhere.
- **A card is a solid.** It has an edge, piles have a visible white band, and
  both are drawn from `CardSolid` with back-face culling. A pile's height is
  notation rather than measurement — a three-card pile is a millimetre, which
  the tilt then divides by four — but notation on a saturating curve, so it is
  honest at both ends: three cards read, and sixty do not become a tower.
- **Sound**: short, quiet, and only for things a hand does — lift, set down,
  slide, shuffle, deal. The pickup is a soft pitched tap (55ms, fast attack,
  smooth tail), not a click. Synthesis lives in `tools/sounds/`.
- **Haptics** follow sound, on the same toggle, from one vocabulary in
  `core/haptics/`: lift, land, stack, slide, detent, flip, deal, shuffle, peek.
  Each carries a *crispness* as well as a pattern, because that is the axis the
  modern Android API exposes and a tick played as "buzz for nine milliseconds"
  is a smear. Two rules: nothing outlasts the gesture that caused it (the riffle
  is the one exception and earns it), and nothing fires for something the *app*
  did — only for something the hand did. A table that buzzes when a turn counter
  increments is a phone.

Idle things cost nothing: springs run only while touched.

## 8. Components

- **Card tile** — 59:86, 4dp radius, 1dp hairline. The hairline is dropped when
  the card sits inside a drawn region, because the region's edge is the card's
  edge and two lines a millimetre apart read as a printing error. A solid ring
  means *chosen*; the turning prismatic ring means *this is the one you asked
  for* and is reserved for reveals. Beneath both, every card face wears the
  **foil** edge (`drawPrismaticInset`) — see §2. Backs do not.
- **Copy count** — bottom right, white on a soft dark scrim, no chip. The top
  right belongs to the card: a Yu-Gi-Oh! card prints its attribute there, and an
  accent rectangle parked on it hid information the card was already showing
  behind a number the app had added.
- **Pane header** — 28dp, one line: colour bar, name, count against its legal
  bounds, then controls, then collapse. Icon buttons are 26dp, not Material's
  48dp.
- **Bars** (legend, draft editor) — fixed height, declared to the fitter,
  horizontally scrollable rather than wrapping.
- **Chips** — 30dp, 2dp radius, label in `labelMedium`.
- **Sheets** — for things that are about the list rather than about a card.
  Anything requiring you to look at the deck while you do it must not be a
  sheet.

## 9. The breakdown, as a worked example

The rules above decide it completely, and it took four attempts to see that.

**A lens is a partition, and the partition is a parameter.** `core/deck/DeckLens.kt`
hands the layout a `LensKeying`: a list of keys and, index-aligned with the
section, which key each card falls in. Four of them ship — Deck (none), Roles
(the user's own groups), Archetype and Copies — and the drawing code cannot tell
them apart. That split is the point. A breakdown wired to the user's own labels
can only ever show back the bookkeeping they already did; one pointed at facts
the card database already knows has something to say about a list imported ten
seconds ago, which is when a reading is worth most.

**Paint says what kind of difference it is.** A key is painted `Hue` — nominal,
off the prismatic ramp, *these are different* — or `Grey` — ordinal, read as a
scale — or `Tone`, a fact the app already agreed a colour for. Copies is grey,
brightest for singletons, because the singleton is the card you are looking for.
Archetype takes hues 2–5 and never 0 or 1, which mean illegal and warning
everywhere else. Type and Legality are tones.

**A reading is worth drawing because of the number next to it.** "Twelve
starters" is a fact about a list; "twelve starters, 89%" is an argument about a
deck, and the ratio was only ever being chosen to move that number. Every key
carries its exact opening rate, hypergeometric over the same counts the blocks
are drawn from, so the bar can never disagree with the deck beneath it. It never
rounds to a certainty it does not have: 99.7% reads `>99%`, because `100%` is a
promise.

**A question is stated once and kept.** Goals live in the deck file beside the
groups they are written in terms of, keyed by id so renaming a role does not
detach the question from it. They sit pinned right of the keys under every lens
— left is how you are reading, middle is what that reading found, right is
whether the deck does what you asked. A question you have to rebuild from memory
is one you stop asking.

**The deck is a mosaic that cracks.** 2dp between cards, near enough to touching
that a row reads as one surface. A lens does not open every gutter — only the
ones where two keys meet. A card gives way by 4dp on a side that faces a
different key, and on no other side, so the deck cracks along exactly the lines
the lens draws. The key's colour is then drawn **solid** in the space that
opened, and the block reads as one object with a bold edge.

**Cards with no key have no edges.** The unclaimed part of the deck is not a
group with a shape; it is the surface the blocks are lifted out of, and it stays
flush while they go. This is also the reading: what is in no archetype is the
non-engine, and what is in no role is the part not yet thought about.

**The cell is fixed and the card moves inside it.** `core/layout/CardPlacement.kt`
places a card in the cell the fitter solved for: it gives way on cracked sides,
keeps 59:86 exactly, and never grows. The crack was once applied as padding,
which made every item taller than the fitter had promised and pushed the last
row of the main deck out of its pane — §5's first rule, broken by the mode drawn
on top of it.

What this design refuses, and why:

- **Rearranging the deck to make prettier blocks.** Rule 2. A key whose cards
  are scattered is drawn as several blocks, because that is where those cards
  are — and that reading is itself useful, since it says the deck is not sorted
  the way it is being thought about.
- **Tinting keyed cards.** Rule in §2: solid, not translucent.
- **Labelling blocks with names.** Names go in the lens bar; the deck carries
  two-letter marks, one per block.
- **Making a lens cost card size.** The bar is 30dp, always present, declared to
  the fitter whether a lens is on or not; the crack is bought from the cell.
  Nothing about the deck's geometry changes when a lens does.

## 10. The play stage, as a worked example

The freeform table (`ui/play/`) is the second place where the whole design
argument shows up at once, and it is worth reading before touching it.

**One arbiter, not one per card.** Every gesture on the mat is decided by a
single `MatGestureMachine` in core, driven by a single `pointerInput` over the
whole stage. Compose hit-tests each pointer independently, so per-card gesture
detectors let one finger start a drag on one card while a second finger starts
a *separate* drag on another. Consumption cannot fix that: by the time it
happens there are already two gesture loops that each believe they are in
charge. It also means touch and pointer are two producers of one vocabulary
rather than two implementations, which is how "every gesture ships with both
idioms" stays true structurally instead of by discipline.

**Fingers on a card move the card; fingers on the felt move you.** That
sentence is the whole control scheme, and it is worth insisting that it stays
sayable in one line — a table with fifteen gestures on it is not a table, it is
a keyboard. The split is made in the arbiter, once, on the press:
`claimForCamera` is called when the hit test finds nothing, and from there the
gesture cannot become a drag, a peek, a twist or a menu whatever the fingers go
on to do. On the felt, **one finger orbits, and two pan and pinch**; the pointer
idioms are a drag, the wheel, and a middle-drag (or alt and drag) for the pan.

Two fingers used to orbit *as well*, which meant the hand's travel did two jobs
at once and the camera could only ever be aimed at the middle of the mat. The
split costs nothing — the arbiter already reports how many fingers are down, and
both signals were being measured every frame — and it is the idiom every
three-dimensional viewer has used for as long as trackpads have had two fingers
on them, so it needs no teaching. That is the bar a gesture on this table has to
clear.

**And letting go of a turn does not stop it.** A flick coasts and eases to rest
on the app's own damping, because a camera that stops dead the instant a finger
leaves the glass has no weight. Any press catches it: a flick you cannot stop is
the thing that makes people say inertia fights them.

Before that split existed, all four two-finger gestures were reachable on empty
felt, where there is no card for any of them to be about, and all four did
nothing at all — a third of the vocabulary spent on the most-touched surface on
the screen to no effect, while the one thing a hand on a table obviously wants
to do, look at it from somewhere else, had no pinch.

**The gesture is decided by time as much as by position.** All four two-finger
gestures *on a card* are spoken for — tap flips, twist turns to defence, drag
takes the pile, hold opens the menu — so the remaining channels are duration and
stillness. A one-finger hold peeks; a *carried* card held still goes underneath
the card it is over rather than on top. Both are driven from the frame loop,
because a finger that has stopped moving stops producing pointer events, which
is precisely the condition being detected.

**The table has almost no affordances on it, so it has a guide.** Nothing is
drawn on the felt to tell you what you may do, because a table does not do that
either. The price of that stance has to be payable on a press: `MatGuide` in core
is the gesture vocabulary as data, and the button on the bar renders it beside
the keyboard table. It is data for the same reason `ShortcutTable` is — a control
list written by hand beside the code it describes is wrong within two changes,
and a wrong guide is worse than none, because it turns a user who does not know
a gesture into one who believes the table is broken.

*Almost*, and the exception is worth stating rather than hiding, because the
rule is only load-bearing while it costs something. There are two shuffle marks
on the felt, one under the deck and one under the extra deck (`MatControls`).
Shuffling earns them on one point: it is the only thing in the game you do to a
*pile as a whole* rather than to a card, and every other route to it was
somewhere else — a button in a bar and a key on a keyboard nobody holding a
tablet has, both a long way from the deck they are about, when the place your
hand already is when you think about shuffling is exactly where the deck is.
They are marks rather than labels because the mat is a tilted plane and type set
on it keystones, and they go through the one gesture arbiter like every card, so
"a gesture detector attached to a card rather than to the mat" stays true. **A
third one needs a better argument than this one, not a precedent.**

**The indicator is the intent, not a picture of it.** `DropTargets` resolves
the finger's position to one `DropIntent`, the highlight draws that value, and
the release commits that same value. There is no second decision at release
time that could disagree with what the user was shown. Every threshold in the
resolver is a pair — harder to enter than to leave — because a single number
makes a finger resting on a boundary flicker between two answers several times
a second, and which one you get is luck.

**Draw order is depth, and it is arranged rather than asserted.** The stage
paints the room, then every card in turn — each one its own shadow, its own white
edges and its own picture, before the next is touched — sorted by
`StagePlane.project(...).depth`. Both halves of that were wrong for a long time
and neither was visible until the mat stopped being black. Nothing was sorted by
depth at all: the mat's cards went by raw mat-space `y`, which stops meaning
anything the moment the table can turn, and the hand and the four piles were
appended afterwards in the order somebody typed them, so the graveyard painted
over the deck standing in front of it. And the passes were *global* — every
shadow, then every edge, then every picture — which meant no card could ever be
occluded by a pile's wall whatever the order was. A card, its body and its shadow
are one object, so they are one thing to paint.

**One tilted plane, and everything on it.** The plane is a `graphicsLayer`;
everything on the stage — the table, the mat, a card resting on the felt, the
top card of a deck, the card in your hand — is drawn inside it, and height is
carried by `StagePlane.flatten`, which rewrites a point *with* a z as the point
on the felt that will look like it once the camera has run. There was a second,
flat layer above this one for whatever a hand was holding, and it worked
precisely as long as there was only one camera angle: "flat" and "square to the
reader" were the same thing. A camera that turns ends that, and it is gone.

**Height is notation, and notation has to be legible.** Everything with a z on
this stage reaches the screen multiplied by `sin(tilt)`, which is the exchange
rate between the geometry and anything anybody can see. A physically honest
deck — forty cards, a fifth of a card width — comes out four pixels tall, and
four pixels is a diagram. So the pile curve exaggerates (`CardSolid.pileDepth`),
the default tilt is 21° rather than the 11° it started at, and a pile carries
two more cues that survive angles its height does not: its side is *ruled* into
the cards it is made of, and it *leans*, because nobody has ever squared up a
graveyard and a slouch is the only thing that says "several cards" from directly
overhead. All three are capped rather than clamped, so a deck never becomes a
tower or a mess.

**The mat is lying on a table, and the table is an object.** A true-black stage
has no place in it: gradients on black are a mat floating in a void, and no
amount of shading on the cards makes a void into a room. So the table is drawn
as a slab — the same `CardSolid.slab` a card is, fifty times the size — and it
has a thickness whose sides swing into view as the camera comes down. That is
the cue that says the thing you are turning is a solid rather than a picture
being sheared, and it is the cheapest one on the stage.

**A carried card's position is assigned from the finger and never sprung toward
it** — any spring between a finger and the thing it is holding is lag, and lag
on a touch drag is the one thing that makes a simulator feel fake. Its
*attitude* is where the weight went instead: `CardDynamics` reads the speed the
finger is dragging at and banks the card into the sweep, leading edge back, and
hands that to the same rotation springs. Position is exact, attitude has
inertia, and the card ends up feeling like an object without any of it being
between the finger and the card.

**What says a card is off the table is its shadow, not its size.** At any
camera distance that does not keystone the text, lifting a card grows it by a
few per cent — unreadable. What is readable is the shadow separating from it
and softening, so that is what the geometry is tuned around, and it is why the
shadow is cast properly (every corner projected along the light) rather than
drawn as an offset copy. An offset copy is right only for a card lying flat, and
the moment one banks in the air, the difference between the two *is* the effect.
A held card's shadow is also drawn **over** the cards it falls across, on a
second canvas above the resting ones: a shadow is on the table even when the
thing casting it is not.

**Every face-down card has a back.** `CardBack` draws it — kai's own artwork,
bundled, a crimson field of speckle inside a white margin — and something correct
is painted under it while the resource resolves, because a face-down card that is
a blank rectangle for a frame is a face-down card that flickers when a hand is
dealt. Nothing in the app draws a blank rectangle where a card back belongs.

It replaced two *drawn* backs and the preference that chose between them. The
argument for drawing rather than shipping is unchanged and was never about
artwork in general: the official back is Konami's and this repository is public,
which is what `cardBackUrl` is still for.

## 11. Scenes, and the exception they are

Everything above describes one stage: a mat on a slab, sharp white on true
black, nothing on the felt that has not argued its way there. That stage is
**Minimal**, it is what this app is, and it does not change.

The **Desk** scenes are a different contract, and writing that down is the point
of this section. A desk in a bedroom, lit by a window in the day and a lamp at
night, is a room full of objects that are there because they are nice rather
than because they are needed — which §12 below calls decoration and bans
outright. Rather than quietly bend that rule until it means nothing, it is
suspended in one named place, on terms:

- **A scene is chosen, never arrived at.** `Scene.MINIMAL` is the default and
  the preference survives a layout reset. Nobody gets a bedroom because they
  updated.
- **Nothing idles, in any scene.** No drifting dust, no flickering lamp, no
  curtains, no breathing. `AAA.md` #60 survives whole, because the thing it is
  actually protecting — *only what you touch is alive* — is the identity, and a
  room that moves on its own is an engine with nothing to say. A prop may answer
  a finger. It may not perform.
- **No room tone.** `AAA.md` #77 survives too. Sound is for things a hand does,
  in every scene.
- **A prop is never a control.** The two shuffle marks earned their place on the
  felt with a functional argument (§10), and a bedroom is not a precedent that
  retires it. Nothing decorative may also be the way to do something.
- **The cards still own the screen.** The desk is dark walnut rather than pine,
  and the light does the brightening. A large bright surface beside a deck of
  cards is the first anti-pattern in the list below, whichever room it is in.

**Nothing in a scene may stand over the mat.** This one is architecture rather
than taste. Cards are one composable each, sorted by projected depth in
`PlayScreen`; the room is painted in a single canvas beneath all of them. Those
are two orderings, and the only thing stopping them contradicting each other is
that no object in the second can ever need to be in front of an object in the
first. A mug on the felt would need exactly that the first time a card was
carried past it. So the felt is the boundary, `SceneryTest` holds it, and the
rule can be dropped when `AAA.md` #92 and #93 exist and not before.

**One rig, chosen by the hour.** `StageRig`'s KDoc says two rigs would be one
too many, and it is right: every surface has to agree about where the light is
or the table stops reading as one room. That guarantee now lives one level down
— `StageLighting` is a value, exactly one is in play at a time, and no call site
holds a lamp of its own — which is the whole of what the singleton was buying.
The wood does not change at dusk; the lamp does.

**A fixture and the light it throws are one value.** The lamp on the desk is
not a picture of a lamp standing near where the key light happens to point: its
position *is* the key's position, and the key's direction is derived from it.
`Scenery` computes the foot once and emits both the fixture and the `Light`, so
the object and the lamp cannot end up in two places. (It emitted *boxes* when
this was written; the lamp is four turned profiles on one spindle now, and the
guarantee is the one thing about it that did not change.) The same rule sends the
window's aperture through the identical function that casts a card's shadow —
one arithmetic, two consumers, and no second place for the light to be.

**A lamp's mast is foreshortened, and its foot and its light are exact.** This
is §10's height rule run the other way. A physically honest desk lamp on this
stage stands six hundred and sixty-seven pixels up on a 1600×856 board and is off
the top of the picture at every seat; one low enough to draw honestly throws
shadows nearly three times too long. So the light is at its true height and the shade is drawn at
about a third of it — 2.92 to one on a 1600×856 board, which is a measurement and
was written here as "a fifth" for as long as nobody took it. What survives the compression is the pair that survives for a pile: the
*foot* is exact — the shade is directly above the source, the pool is centred on
it, every shadow points away from it, the sheen is its mirror — and the *light*
is exact, because the height was solved from the shadow length the preset
already shipped rather than chosen. Nobody can measure a stem. `SceneryTest`
pins the ratio so it cannot drift into a lie by accident.

**A light with no place must be a no-op, to the bit.** Every term a positioned
lamp adds — the direction to it, the falloff, the angular size, the umbra —
returns a literal before touching a float when there is no position. That is
what lets `GoldenStageTest` stay green without being re-recorded across a
release that changed how every surface is lit, and it is the difference between
"we think the minimal stage is unchanged" and knowing it.

**Night does not lower the ambient.** `Tone.veil` carries the numbers: card art
is a picture the renderer cannot read, so it is shaded by one black overlay at
one opacity, and that approximation goes wrong quickly as the light falls —
1.19x too bright on dark channels at 0.72, 1.87x at 0.3. So `NIGHT_FLOOR` is
0.55 and the darkness is bought outside the cards, in the mat's fall-off and in
a room with nothing lighting it. That is also what §2 wants: a card is the
brightest object in the frame.

**A prop is a pose, not a piece of furniture** — and the line between them is
the recomputation, not the shape. Everything else in the room is a `ScenePiece`,
solved once for a board size and an hour and identical on every frame until one
of those changes. It stopped being *a box* when the lathe arrived: a piece
carries a `mesh` when a box is not its shape, and the box goes on being only what
it is sorted by and measured against.

**Nothing on this stage is a prop today**, and this section is the record of the
one that was. A Millennium Puzzle stood on the left of the desk for two releases
and kai cut it (`docs/classic/LOOP.md` iterations 12 and 13). Everything below is what it
established, kept because it is the specification for the next one and because
every clause of it was a shipped bug first.

**A prop is built the way a card is.** `stirred(layout, turns, lifted)` was a
pure function of two numbers the screen owned, so the thing being drawn, the
thing being touched and the thing casting a shadow were all that one value. A
moving object stored in a value that is deliberately recomputed twice a day is a
prop that snaps back to where it started the first time the clock crosses dusk.

**It may spin and rise; it may not tumble — and that is now taste rather than
arithmetic.** It used to be arithmetic. `CardSolid.slab` hangs a solid's body
straight down the *stage's* z, which is a fact about the surface a thing is
resting on rather than about the thing, so a rotation about that same axis
commuted with a translation along it and gave bit-exactly the rotated solid,
while a tip about x or y left the body hanging vertically as the face turned —
a fraction of a pixel over a card, the entire silhouette over a hand's width of
pyramid. The price of a tumble was named here as *a posed box in core with its
own eight corners*, and `core/render/Turned.kt` is that box: a solid of
revolution whose every vertex goes through `Rot3.place`. That is what let the
puzzle be **statically propped thirty-four degrees back**, which is what made it
read at all — balanced on its point an inverted pyramid shows nothing but its
base.

What is left of the rule is the part that was always taste: a nudge spins and
lifts, and does not roll. A prop may answer a finger; a prop that tumbled would
be performing.

**A prop is two shapes: a box to sort it, and faces to draw it — and so is half
the room now.** `ScenePainter` knows only about axis-aligned boxes, and it does
not need to know more: what a painter's algorithm wants from an object is a
separating axis, and a bounding box that shares no volume with anything supplies
one exactly as well as the real solid would. `ScenePiece.mesh` is that same split
applied to furniture, and four of the room's eighteen pieces use it.

Inside one object the split stops helping, and the puzzle is where that showed.
Its body and its ring shared every axis — the ring sat in the middle of the top
face, wholly inside the leaned pyramid's own box — so the painter declined the
pair and something else had to decide. What decided it was a **plane**: the
body's own top face, which the ring stands on and the body lies entirely under.
For two convex bodies either side of a plane the order is which side the eye is
on, and nothing else. It was not always the ring, either — at the envelope's
steepest pitch the propped top face turns away and the body is in front. So a
prop joins the room's sort as its **reach**, everything it could occupy over
every pose it can hold, and is drawn from where it actually is this frame; and
its own parts are ordered *among themselves* by the same painter, because one
list of faces depth-sorted across two solids puts the far side of a ring in front
of the top it is standing on. Painting a prop last instead was the first version,
and it assumed a camera in front of the table: yaw here is free, and walking
round past about 145° puts you behind the room's own wall, where the header above
the window really is nearer than the desk. Seated at 150° the puzzle drew
straight through it over a 269×280 patch.

**A prop is hit-tested where it appears, not where it stands.** A solid a hand
tall does not draw on top of its own base, and the gap is the whole game: on a
1600×856 board the middle of the puzzle's top face landed 140px from the point it
touched the desk at the table seat and 188px seated, against cards 104px wide. So
the test is against the **flattened silhouette** — `StagePlane.flatten` answers
in the mat's own coordinates, which is exactly the frame the finger has already
been unprojected into, so both sides of the comparison are in the one frame the
whole stage computes in and no screen coordinate is involved on either side.

**The camera claims the gesture last.** §10's rule is that fingers on the felt
move the camera, and it holds because the claim is made once, on the press, when
the hit test finds nothing. A prop is a third thing that is neither a card nor
the felt — after a shuffle mark and the space inside an open fan — and like both
of those it has to be taken out *before* the claim, because a claimed gesture can
never become a tap again. It is also asked **last** of the three, and that order
is the priority: everything the table itself offers wins over an ornament beside
it.

**An easter egg is not in the guide.** `MatGuide` exists because the table has no
affordances drawn on it and somebody has to say the house rules. An easter egg is
the one thing on the stage whose value is that nobody told you, so it stays out —
and it still ships with both idioms, because a tap and a click are the same
gesture and the pointer never needed a second one.

## 12. Anti-patterns

- Colour that is neither meaning nor light.
- A translucent wash over content.
- Rounded corners past 8dp, or a pill.
- A shadow used to separate two things that are both on the table.
- Any animation of something that did not change.
- Chrome that grows without being declared to the fitter.
- Stock Material spacing (48dp targets, 56dp fields) inside the deck column.
- A view that reorders, renames or invents anything about the deck.
- A gesture detector attached to a card rather than to the mat.
- A drop indicator computed separately from the drop.
- A shadow drawn as an offset copy of the thing casting it.
- A highlight that brightens instead of moving.
- A haptic for something the app did rather than something the hand did.
- A spring between a finger and the thing it is dragging.
