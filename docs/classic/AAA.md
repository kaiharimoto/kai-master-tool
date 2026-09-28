> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

# A hundred changes toward a game

The play stage has real geometry now — a card is a solid with a normal, the
light is a rig, the shadow is cast by projecting corners rather than drawn as an
offset copy. It is still a diagram that animates rather than a table that
behaves.

This is a hundred things between here and there, grouped by what they touch.
Four of them are marked **[foundation]**: work nothing else can be built on top
of until it exists. Four are marked **[guardrail]**: rules in `docs/classic/DESIGN.md`
that a list this long will try to break, restated so they survive it. Four are
marked **[your call]**: places this argues with something already locked in, or
asks a question only kai can answer.

Nothing here is scheduled. It is a menu.

**Four of the calls have now been made**, on kai's *"level up play mode … it's as
if we're really sitting at a desk playing with cards"*, and they move four items
off the menu and onto a road: the **POV seat becomes the seat the stage opens
at** (§1, and it is what #61's near edge and #68's defocus are re-measured
against); the Desk scenes may be **graded like a photograph** (#20, #68, and the
whole of `docs/classic/PHOTOREAL.md` stage 7); the room gets **built and gets shadows**
(#61d and #62, gated on #92 and #93); and **#34 is refused** — the card's
printed picture stays a 168×246 thumbnail and the fidelity is bought in the
object around it. `docs/classic/LOOP.md` §"The list" carries the same four in the form
an iteration reads.


## 1. The camera

A movable camera is one change in core and eleven consequences. Everything here waits on the first item.

1. **Turn the stage into a camera.** **[foundation]** Yaw, pitch, distance and a target, instead of one hard-coded tilt — keeping `project`, `unproject` and `flatten` exactly as they are, so nothing downstream notices it moved. Every other item in this section waits on this one. *The target was the last of the four to arrive, six releases later, and it kept the promise: `unprojectAt` swapped which point it adds back at the end and stayed closed form, so `flatten` — and every pile edge, card thickness and airborne shadow drawn through it — never noticed.*

2. **Clamp pitch, lock roll.** *Half of this is retired.* It shipped at four to fifty-eight and is **zero to eighty** now, on kai's *"complete freedom and control"*: card text keystones badly past sixty, and that is something to see happening and pull back from rather than something the tool refuses on your behalf. Ninety is the one angle that genuinely cannot be drawn. **No roll ever** stands — a horizon that tips is nausea, and that is not a matter of taste either.

3. ~~**Three seats, not free flight.**~~ **Half rejected by kai.** The seats stayed and are the way home; the *"not free flight"* did not survive contact — *"there shouldn't be a limit I want complete freedom and control."* The camera now walks, tilts and aims anywhere the arithmetic allows, and the argument this item made is answered rather than overruled: free-fly cameras end up somewhere useless and then you have to fly back, so **1 · 2 · 3 · 4 fly you back**, framing the board with `CameraFit` on the way. *There are four now, and the fourth is not like the others: `tiltDegrees` is measured off the table's normal, so the three that shipped look down at it from 85, 69 and 56 degrees of elevation — all of them somebody standing over a table. `StageSeat.POV` is at 32, which is a head at a desk, and it needed the lens shift to fit at all.*

4. **Move the camera on the springs the cards use.** `PosePhysics` is already there. A camera that eases on a curve while the cards spring reads as two physical worlds sharing a screen.

5. **Orbit needs a gesture that isn't already taken.** **[your call]** All four two-finger channels are spoken for on the mat — tap flips, twist turns, drag takes the pile, hold opens the menu. Three fingers, a margin outside the felt, or a held modifier: this needs a decision before it needs code.

6. **Pinch dollies, it does not zoom.** Moving the camera changes the perspective. Scaling the image does not, and that difference is the entire reason to have a camera. *This was true of the gesture and not of the projection for six releases: `cameraDistance` — which **is** the focal length — carried the camera's distance as well as the lens, so a dolly also swung the field of view from 130mm to 9mm across the envelope, and the keystone moved as `1/distance²`. `docs/classic/TUNING.md` has the measurement.*

7. ~~**Inertial orbit.**~~ **Built.** Flick the felt and the table coasts to rest on the app's own damping. Rates are per *second*, so a flick runs down over the same seconds at 60Hz and 120Hz, and any press catches it — a flick you cannot stop is what makes people say inertia fights them.

8. ~~**Let the tablet's own tilt move it a degree or two.**~~ **Built, and it ships off.** One sensor listener and a low-pass filter, as this said — `ui/fx/Tilt.kt` and `core/motion/HeadSway.kt`. *What this entry could not have known is the trap the lens shift later laid for it: a shift is exactly the term a parallax effect reaches for, and it moves every pixel by the same amount by construction, so near and far move together and there is no parallax at all. Only moving the eye gives depths different speeds, so it produces a small yaw and pitch instead.* It is added **beside** the rig rather than into it — in `CameraRig.pose` it would fight every drag, cancel every coast, and stop `Turns.seatAt` naming a seat. "Nothing idles" is kept by a latch: `step` returns false once the filter has arrived, so a device lying still writes no plane at all. Off by default, beside `cameraTouch`, and for the same reason.

9. **A CameraFit, solved in core.** *Built, then moved.* It ran on every camera release, and that turned out to be the whole of kai's *"it locks me out from getting closer past the close limit"*: you pinch in, let go, and the table slides back out. A correction nobody asked for reads as the tool refusing. It lives on the **seat buttons** now (`StageCameraState.sitAt`), where being put back where the board fits is exactly what was asked for — and it is what makes free flight safe rather than what makes it impossible.

10. **Dolly out as a peeked card comes in.** The card grows, the room recedes. It is two lines against the existing peek and it is the most cinematic thing the table could do.

11. **The camera leans toward the action.** A card carried to the far edge pulls the view two or three degrees. Not a follow-cam — a shift of weight.

12. **Remember the angle per deck.** You come back to the seat you left. Preferences are one JSON document; this is a field with a default.


## 2. Light and shading

The renderer exists now. These are the twelve things it does not yet do that separate a lit room from a lit equation.

The three rooms all run on one rig, made a value in `StageLighting`. Read the note on `NIGHT_FLOOR` before touching an ambient: a night scene cannot buy darkness by dimming the room, because card art is shaded by a single black overlay whose error grows sharply as the light falls (`Tone.veil`). Items 16 and 17 are what the desk lamp is actually waiting for.

13. ~~**Add a rim light.**~~ **Done, and not where this said to put it.** A light *behind* the table is culled before it is ever shaded — the stage draws solids back-face culled and looks at them from above, and the first version of this lamp pointed that way and changed exactly one face on a whole board, which a test proved. `StageRig.Rim` sits on the **player's** side instead, low and cool: the light a room throws back off whoever is sitting at the table. And it is gated on the **graze** rather than on the lambert alone, so it lands only along a silhouette and is exactly zero square on — a rim that is on everything is not a rim, it is the ambient again.

14. **Give the key a temperature.** Warm key, cool fill. Two hex values, and it is most of the difference between a room and an arithmetic result.

15. ~~**Shade in linear space.**~~ **Done, and the half that was left was worse than this entry knew.** The multiply moved into linear a while ago — `Tone.shade` is `toSrgb(toLinear(base) * amount)`. What was still wrong was the *specular*, and it was not an encoding problem: the Blinn-Phong lobe was never energy-normalised, so raising `shininess` only ever removed light and "shinier" meant "dimmer". Measured, a foil lying flat came to 0.00052 under the day rig against a 0.004 draw threshold — the highest specular constant on the table belonged to the only material with no highlight at all. `(n + 8) / 8pi` fixes the direction and does not reach the magnitude; what does is a second broad lobe for the varnish (`CardMaterial.coat`). `docs/classic/LOOP.md` iteration 24 has the whole measurement.

16. ~~**Make the key a point light.**~~ **Done.** `Light` takes an optional position and the falloff is the on-axis irradiance of a uniform disc, `R²/(R² + d²)` — so #16 and #17 share one term instead of guessing two. Normalised at the lamp's own height, because a form normalised at the mat's centre reaches 1.7 near the lamp and clips half the table flat to white. The near corner of the mat is 21 levels of 255 brighter than the far one, where it was 0. The falloff lands on the *directional* term only, which is what keeps `NIGHT_FLOOR` a guarantee rather than a hope.

17. ~~**Give the light a size.**~~ **Half done, and the half that was wrong.** `SOFT_PER_HEIGHT` was always standing in for a source's angular radius; now the angle is measured and the constant is only what a light with no size falls back on. Daylight loses its edge at 0.67 card heights and lamplight at 1.24. Shadows also *straddle* their edge now rather than feathering outward, which was making a soft shadow read as a hard one wearing a halo. What is **not** done is the stronger half — sampling N points on the source disc and running the projection N times, so the anisotropy and the round corners emerge rather than being modelled. That needs a layer per card to composite coverage linearly, and a layer per card at sixty cards is a cost to measure with `FrameProbe` before it enters a signed build.

18. ~~**Shadows are not black.**~~ **Done.** A shadow is the surface it lands on, lit by everything except the one lamp something is standing in front of — `StageRig.shadowed` is `lit` with the key's direct term dropped, and `StageRig.occlusion` is the share that removes. On the mat under the shipped rig it is about **a fifth**, against the 0.66 that `Shadows.DARKEST` had chosen. That constant could not have been right in more than one room: the ambient a shadow does not occlude is 0.72 in Minimal and 0.55 at night, so one number was a hole punched through a lit surface in one and roughly right in the other. `CardShadow.alpha` is now **coverage** — how much of the shadow is there — and how dark it comes out is asked of the room. The colour falls out too, without anybody picking a hex: losing the key's direct term loses the key's *warmth*, so a shadow is cooler than what surrounds it.

19. **Occlude between cards.** A card overlapping another should darken the overlap. Every card currently shadows only the felt, so two overlapping cards read as two stickers.

20. **Bloom the specular.** Threshold, blur, add. Without a shader that is a second additive pass at half resolution, which is enough to sell it.

21. ~~**Stretch the foil highlight.**~~ **Built, and lit as of iteration 24.** Holofoil is a diffraction grating, so its highlight is a streak along one axis rather than a circular pool — the single change that would make foils read as foil. `CardMaterial.anisotropy` says how combed a stock is, and `Shade.streak` carries the shape out; both are trailing and inert by default, so every other material is unchanged to the bit and `GoldenStageTest` — which records `diff`, `spec`, `rim` and `hot` — does not move. **The ratio is derived rather than chosen:** splitting one `shininess` about the anisotropy gives the two exponents a ruled surface really has, and a Blinn-Phong lobe's half-width goes as `1/sqrt(n)`, so the streak is `sqrt(across / along)` — three to one at foil's 44 and 0.8, capped at six because a highlight longer than the card is a bar across the art. It changes the pool's *shape* and deliberately not its strength, which is `DESIGN.md` §7's rule rather than an accident. What is left is the hue sweep, which is `PHOTOREAL.md` stage 8 and is gated on #25. **And then it was photographed, which is the point of this entry now.** The studio grew a finger (`studio/Pointer.kt`), tapped the extra deck open, and the streak changed nothing: byte-identical pictures with the anisotropy on and off, and byte-identical again with `Foil.specular` cranked from 0.52 to 1.0. The cause is not this item, it is underneath it — **a flat foil's specular is 0.00052 in `DeskDay` and 0.0012 in `DeskNight`, and `drawCardSurface` skips the block below 0.004**, so a foil lying on the desk has no highlight at all in either room a foil appears in. `shininess = 44` takes the alignment to the forty-fourth power, which makes the material with the highest specular constant the dimmest surface on the table — sixty-four times dimmer than a sleeve. So this is done and dormant, and what wakes it is #15 and `PHOTOREAL.md` stage 2 re-deriving the lobe; normalising by `(s + 8) / (8pi)` alone does not, being 2.07x on a number three orders under where it needs to be. `docs/classic/LOOP.md` §6 has the table.

21b. **`DARKEST` and `FADE_OVER` are now computable rather than chosen.** Under the night lamp the directional share of the felt's light is 38%, so a full umbra can only remove 0.38 — and `FADE_OVER` halves a shadow at 96 px where the umbra survives to 317. Both are golden lines, and re-recording the golden in the same release that gave light a position is how you lose the ability to say which change moved it. A clean separable release, and it is now computable, which is the thing this one bought.

22. **Draw a card's own edge in the air.** `CardSolid` already computes the slab for every card and only piles render it. A card banked in your hand should be showing its white edge.

23. **A faint reflection of the table in glossy stock.** A darkened, blurred copy of what is below, at grazing angles only, and gone by the time the card is square to you.

24. **Let the light move on big moments.** A summon swings the key across the board. Nothing else in the app ever moves the light, which is exactly what would make it land.


## 3. The card as an object

A card is not a rectangle with a picture on it. Ten ways it could stop being one.

25. **Read rarity from the card database.** YGOPRODeck ships `card_sets` with rarities in it. A secret rare that looks like a common is throwing away data the app already downloaded — and it makes the finish *meaning*, which is the only licence the palette gives colour.

26. **Sleeves as a material, not a texture.** A sleeve is frosted on the back, glossy on the front, and holds the card a millimetre inside itself. All three are visible, and all three are why a sleeved card looks different from a bare one.

27. **Sleeved cards are thicker.** A sleeved deck stands visibly taller. `THICKNESS_RATIO` stops being a constant and becomes a property of the stock.

28. **The back's foil oval is its own layer.** It catches the light separately from the brown field, which is precisely what you see when you fan a deck under a lamp.

29. **Counters become objects.** They are a text badge today. Make them discs that sit on the card, cast their own small shadows, and can be knocked off it.

30. **Dice and coins, simulated.** Both are real game actions rather than decoration, and a tumbling die is the best showcase a rigid-body solver will ever get.

31. **Life points as a thing on the table.** A pad or a dial beside the mat, rather than a number in a top bar. The top bar is the last part of this screen that is still a website.

32. **Let people load their own playmat.** A playmat is the most personal object a player owns and the app currently draws a gradient where it goes.

33. **Edge wear on played cards.** **[your call]** Corner whitening after enough handling. Probably a step too far into skeuomorphism for a tool whose identity is Swiss — noted because somebody will ask for it.

34. ~~**Render card faces once, to a texture.**~~ **Refused by kai, and it is worth knowing what the refusal costs.** Compositing an image, a name and three overlays every frame for sixty cards is the cost that will stop everything else on this list from fitting in the budget — and doing it once to a texture is also the only thing that would let a card face be *authored* rather than fetched. The face is `art.imageUrlSmall`, a **168×246 network JPEG** drawn at about 104 logical pixels, with the compressor's ringing visible in the name bar, and `docs/classic/PHOTOREAL.md` calls it the single largest gap on the whole stage. Asked directly whether to fetch the full-size art, or to fetch it *and* composite each face into a texture so it could be shaded per-pixel, kai's answer was neither: leave the art alone and spend the effort on the stock, the foil, the sleeve, the cut edge, the shadow and the light. So the ceiling on a card's *printed picture* is now a decision rather than an omission, and nothing downstream should plan around lifting it. What is still open is #26 and everything in `docs/classic/PHOTOREAL.md` stage 4 — the card as an *object* — which is where that effort goes.


## 4. Physics

Springs to a target are a good animation model and a bad simulation. This is the largest single piece of work here, and about a third of the list depends on it.

35. **A rigid-body solver in core.** **[foundation]** Mass, an inertia tensor, contacts, friction. Tested in `commonTest` like everything else, and it is what turns the play stage from a diagram that animates into a table that behaves.

36. **Cards collide with each other.** Push one card into another and it nudges. This is the biggest single tell of a real table and the app has never had it at all.

37. **Fixed timestep, accumulated.** Otherwise the simulation behaves one way on a 120 Hz tablet and another on a 60 Hz desktop, and every bug report is unreproducible.

38. **Seed everything.** There are two hundred levels of undo in this app. A shuffle that cannot be replayed exactly is a shuffle that breaks undo, and that is not a trade worth making.

39. **Felt has friction, and so do sleeves.** Sleeve-on-sleeve slides differently from card-on-felt, and differently again from card-on-card. Players have strong opinions about this in real life.

40. **Throw a card and it slides.** Released with velocity, decelerating under friction and angular drag, stopping where it stops rather than where a target said.

41. **Dropped cards flutter.** A flat plate falling generates lift, which is why a dropped card never lands where you aimed it. It is simulable, it is cheap, and it is instantly recognisable.

42. **A stack is bodies, not an integer.** `PlacedCard.depth` becomes N cards resting on each other, which is what lets you knock the top one off by accident.

43. **Piles scatter when you hit them.** And the rest of the pile settles into the gap that opened, which is the part that sells it.

44. **Sweep with your hand and everything moves.** Momentum transfer along the whole path of the finger, not just under the point of it.

45. **The riffle is an actual riffle.** Two halves, bridged, interleaved one card at a time — and the order that falls out is the order the deck is now in. The shuffle stops being a permutation with a sound over it.

46. **Cutting the deck.** Take the top half, put it underneath. Already on the roadmap; make it a physical grab rather than a menu item.

47. **The graveyard grows crooked.** Real discard piles are never square. A perfectly aligned graveyard is the clearest possible statement that nothing here is a real object.

48. **A gesture that squares a pile up.** And you watch it happen, because watching it happen is half the pleasure of doing it.


## 5. Animation

Motion explains a change and never announces one. That rule stays; these are the changes that currently go unexplained.

49. **The opening hand is dealt, not placed.** Five cards flicked off the deck in turn, arcing, landing, settling. It is the first thing anybody sees and it currently springs into existence.

50. **Draw from the top of the deck.** The deck has a real height now. The card should leave from up there, and the pile should drop by one card's thickness as it goes.

51. **Flip about the thumb, not the centre.** A real card turns over about the edge you are holding. Nobody can name this and everybody notices it.

52. **Nothing ever lands square.** A degree of rotation and a pixel of offset on every landing. Perfect alignment is the tell, every time.

53. **Anticipation on the pickup.** The card presses into the felt for two frames before it lifts. Two frames.

54. **A sequence type in core.** **[foundation]** Multi-card moments — the deal, a search, a summon — need to be data with a timeline, not nested delays inside a composable. Without this every one of them is written twice and behaves differently.

55. **Every sequence stays interruptible.** The springs already retarget mid-flight. A keyframed sequence that cannot is a sequence that will one day lock the table while a player is waiting to act.

56. **Fan the deck to search it.** Spread, pick, square it back up. The physical answer to a search effect, and the reason to have built the fan.

57. **Banishing is a flick.** The card leaves the table rather than teleporting into a pile. Banish is supposed to feel worse than the graveyard.

58. **Phases get a beat.** A shift in the light and a small camera move, instead of a label changing in a bar.

59. **The turn ends and the board settles.** One quiet moment where everything squares itself and the room takes a breath.

60. **Nothing idles.** **[guardrail]** Breathing cards and drifting light are what an engine does when it has nothing to say. `DESIGN.md` already bans animating something that did not change — restated here because a list this long will try to break it.


## 6. The table and the room

Nine things beyond the felt. A table floating in a void is the most common way a three-dimensional scene looks cheap.

61. **The table has an edge** — *three of the four are there.* The desk runs off both sides of the picture, and because `BoardLayouter` centres a seven-column field, a third of the width down each side is bare wood, plus a strip above the mat before the wall. That is where the felt stops and the wood starts, at every seat — and the strip is its full forty pixels now: the wall used to carry a skirt down to the desk's underside, which was hidden by the desk top from every angle **and painted over it**, eating thirty of them. The wall stands on z = 0 and the desk runs back under it. The **near** edge is still not reachable: the board fills the stage vertically, so it projects below the glass at every seat and dollying out does not bring it back (the term that puts it there is `flat`, which distance does not scale). It would take a smaller board or a wider envelope. See `Scenery.DESK_NEAR`.

61c. **The window should throw a patch of light on the desk, and does not yet.** Built and cut in the same release, which is worth writing down because the reason is structural rather than a bug. The patch was drawn as a multiply — more light landing on an albedo, which is what `Tone.shade` computes — but the rig *already* lights the whole desk from the window's direction, so the desk top is at full brightness before the patch is drawn and every ring of it came out **darker than the surface it landed on**. A stain, not light. The fix is not in the patch: it is to split the day key into sky and sun, shade the room with the sky alone, and let the patch supply the sun where the window lets it through. That changes the brightness of the entire day room and wants the tablet in hand. `Shadows.landOn` was extracted for it and stays — it is what casts a card's shadow too.

62. ~~**There is a room past it.**~~ **Done, then furnished — and now reopened, because kai has asked for the rest of it.** *The open half first:* the brief named *"desk, window with sun outside, millennium puzzle, floor, bed, bookshelf, etc"*, and what exists is the desk, the window, the floor and the lamp. There is no bed, no bookshelf, no ceiling, no side wall and nothing on the desk, and at the POV seat the room is about half the picture rather than a fifth — so what is missing is now the larger half of the frame. Asked how much room to build, kai's answer was the whole of it, room shadows included. **Two walls stand in front of that and both are in this document.** #92 and #93: `ScenePainter.order` is a pairwise separating-axis test over every pair *and* a topological walk, both quadratic, both run every draw, and `SceneryTest.theRoomIsAHandfulOfObjectsRatherThanAScene` is the twenty-piece budget written as a test. Sixty to a hundred pieces needs a retained scene and a cached order before it needs a bookshelf. And `docs/classic/DESIGN.md` §11's *nothing in a scene may stand over the mat* — which is what a mug on the desk is — says in its own text that it may be dropped when #92 and #93 exist **and not before**. So the furniture is downstream of the foundations rather than beside them, and anything round comes off `Turned` rather than off new machinery. *What was already done:* a wall with a window in it, a floor under the desk, and a lamp standing on it — eighteen pieces against a budget of twenty, four of them the turned profiles of the lamp. The window is a *hole*: four wall pieces and a pane that tile the old single wall exactly, which is one line of test. It sits low because it has to — at the wall's plane the largest z that lands anywhere on the glass is 86 px at the table seat, so a window at a realistic sill height would be geometry nobody could ever see. The floor earns its place on a measurement rather than an opinion about floors: turned side-on it takes the uncovered points of a screen grid down by nearly half.

63. **The opponent's half exists.** A duel mat has two sides and the geometry reads as wrong without the other one, even empty, even unplayable.

64. **The zones are printed, not stroked.** Ink on the mat, catching the key at a grazing angle, rather than two hairlines drawn on a canvas.

65. **The felt has a weave.** Procedural and sub-pixel. It is what stops a large flat surface from reading as a fill colour.

66. **Dust, and a crease or two.** One well-chosen imperfection is worth ten polish passes on everything else.

67. ~~**A choice of surface.**~~ **Done.** `Scene` is a preference — minimal or the desk — and `DeskLight` picks the hour or leaves it to the clock. The material half is now **half done too, and by a different route than this entry assumed**. It said every surface was "one Blinn-Phong response", which was never true of the room: `StageRig.lit` is ambient plus lambert plus a graze-gated rim and has no specular term in it at all, so surfaces differed in colour and in nothing else. `StageRig.gleam` is the missing term — additive, beside `lit` rather than inside it so no golden moves, with its lobe widened by the source's own angular radius so a window gives a broad whisper and a bulb a hard glint — and `Surface.gloss` says which surface gets one. Gold and brass do; cloth, plaster and glass do not. What is still not done is the *mat*: a rubber playmat and a bare table are both `Gloss.None`, and telling them apart is a per-pixel job, which is `docs/classic/PHOTOREAL.md` stage 5.

61d. ~~**Nothing in the room casts a shadow.**~~ **Built, as a set.** The argument for having none was that a single object throwing one reads as the thing that got special treatment rather than as better lighting — so the answer was never one lamp with a shadow, it is every fixture or none, and `SceneryTest` asserts exactly that. There is still no depth buffer and there cannot be one: paint order in the room is `ScenePainter`'s separating axis, and a buffer able to disagree with it would be a contradiction no test could catch. So a room shadow is a **decal on a receiver's face** — carried by `SceneModel.deskShadows`, painted between the desk it lands on and the lamp that throws it, and never entered into the piece ordering. Solved with the furniture rather than per frame, because furniture is solved twice a day. Darkened by `StageRig.occlusion` like a card's, so the two cannot disagree about the room. **Two things are deliberately outside the set**, and both were bugs first: the wall, which cast a broad band across the desk thrown by the very surface the daylight arrives through — a wall with a window in it is the aperture, not an occluder of its own window — and the window's joinery, which would throw bars across the desk and is #61c, still cut until the day key splits into sky and sun. `Surface.isShell` is the line and it is exhaustive, so a new surface will not compile until somebody says which side it is on. And anything turned casts an **octagon** rather than its box, because a round foot throwing a hard-cornered rectangle is the part a person notices immediately.

67b. ~~**Something on the desk that answers a finger.**~~ **Built, then cut.** The Millennium Puzzle — a four-sided turn, apex down, chamfered, with a torus bail, propped thirty-four degrees back on the bare left of the desk — shipped in v1.2.40, lost its Eye of Wdjat in v1.2.41 and was deleted whole in v1.2.42, on kai's *"delete the puzzle entirely"*. `docs/classic/LOOP.md` iterations 12 and 13 have the two halves of why. What it established stands as the specification for whatever goes there next, and `docs/classic/DESIGN.md` §11 keeps it: a prop is a **pose** rather than a `ScenePiece`, because furniture is solved twice a day and a moving thing cannot live in a value that is deliberately recomputed; it must be **convex or arrive as convex parts**, because inside one piece the renderer sorts faces by the depth of their own centres and that is meaningless across two solids; it must **share no volume** with anything, which is what `ScenePainter`'s separating axis needs between pieces; it is hit-tested against its **flattened silhouette** rather than its footprint, the two being 98 to 188 pixels apart depending on the seat; and the **camera claims the gesture last**, after a shuffle mark and an open fan, because the table's own affordances outrank an ornament beside them. The desk is bare and this item is open again.

68. **Depth of field from the low seat.** Sharp from overhead, soft at the far edge from the player's chair. The per-card blur radius comes straight off `Projected.depth`, which is already computed.

69. **A photo mode.** Freeze it, free the camera, hide the chrome, export a PNG. Board states are the thing players actually share with each other.


## 7. Sound

Short, quiet, and only for things a hand does. Eight ways to keep that rule and still make the table louder.

70. **Pan to where it happened.** The projected x of every event is already computed sixty times a second.

71. **Loudness from impact speed.** A card slapped down and a card placed play the identical sample today, at the identical level.

72. **Three variants of everything.** Repeating one sample is exactly how a table starts sounding like a machine, and it takes about four repeats.

73. **Sleeve, felt, card — three surfaces.** The material system already knows which two are meeting. It just is not being asked.

74. **The riffle sound comes from the riffle.** One tick per interleave, so the rhythm is different every shuffle, because the shuffle is.

75. **A pile tapped square.** The most satisfying sound in the game, and nothing in this app makes it.

76. **A mix bus with ducking.** Four card sounds inside one second currently stack into noise instead of into a hand doing something quickly.

77. **Still no room tone.** **[guardrail]** Ambience is the standing temptation of every project like this one, and the handbook already refuses it. Sound is for things a hand does. Listed so it stays refused.


## 8. Haptics

The vocabulary shipped this week. Seven ways it stops being a lookup table and starts being an output of the simulation.

78. **Amplitude from the simulation.** Impact velocity drives strength, instead of one constant frozen into each event at authoring time.

79. **Texture during a drag.** A card crossing another card's edge should be felt going over it. Primitives cannot do this; a very low-amplitude waveform can.

80. **The riffle is felt one card at a time.** Same source as the sound, so the two cannot drift apart into a buzz and a noise that disagree.

81. **Weight.** A forty-card deck lifts differently from a single card, and amplitude is the only channel that can say so.

82. **Detents on the camera seats.** The twist already has them, and they are the reason that gesture works without looking at it.

83. **A refusal.** Two dull thuds for a move the table will not make. It is the one thing the vocabulary is currently missing.

84. **Everything stays under sixty milliseconds.** **[guardrail]** The rule that already governs the set. Restated because every new event will want to be the exception, and the riffle already is.


## 9. Spectacle

Where the budget goes. Seven moments — and the discipline that keeps them moments rather than a permanent condition.

85. **A summon is an event.** The one place to spend everything at once: light, camera, a held frame, silence before it. If this whole list only ever buys one moment, buy this one.

86. **Effects resolve in three beats.** Activation, the window to respond, resolution. Quiet beats — the pacing is the thing, not the effect.

87. **Attacks.** Two cards, one line between them, and enough geometry that it reads from any camera seat rather than only from overhead.

88. **Damage is felt.** The counter, the sound and the haptic together, all three scaled by how much. Life points are the only number in the game with stakes.

89. **The prismatic ring stays rare.** **[guardrail]** It means *this is the one you asked for* and it is reserved for reveals. A summon that fringes everything spends the whole visual identity in a single turn.

90. **Slow motion, once.** For the last card of a combo, and never twice in a row. The second time is a cutscene.

91. **A reset that feels like clearing a table.** Sweep it all into a pile, square it, deal again. Right now a new hand is a state swap with a fade.


## 10. Foundations

The unglamorous nine. None of them are visible, and about seventy of the items above quietly assume all of them.

92. **A retained scene in core.** **[foundation]** A list of renderables with transforms, sorted once. The room is the first thing to run into this: nothing in a `SceneModel` may stand over the mat, because cards are sorted in the composable tree and the room is painted beneath all of them, and a prop that needed to be in front of a card could not be. That restriction lifts here and nowhere else. The composable tree *being* the scene is what will stop this scaling somewhere north of eighty objects, and the play stage already holds sixty.

93. **A real depth sort.** The plane-then-air invariant works because resting cards are flat. Give everything a height and it stops working — quietly, and only on boards with a lot on them.

94. **Batch by material.** One path per surface type rather than one per card. The shadow pass alone currently allocates a few hundred paths a frame.

95. **Golden-image tests.** Render to a bitmap in `commonTest` and compare. A hundred visual changes without this is a hundred silent regressions in each other's work.

96. ~~**A frame budget readout.**~~ **Done.** `FrameProbe` was written and wired to nothing; it is in the play stage's frame loop now, behind three taps on the life-point number. It reports the last frame, p95, the worst in the window, missed frames and how many objects were moving, against a budget derived from the panel's own refresh rate.

97. **Level of detail.** A card at the far edge of the mat does not need a specular pool, a soft shadow or a foil sweep.

98. **A quality setting.** The Tab S11 can take all of this. A five-year-old phone cannot, and the app should not discover that by dropping frames on somebody.

99. ~~**A shader seam behind expect/actual.**~~ **Done, and it was kai's call to make.** Android 13+ has `RuntimeShader`, desktop Skia has `RuntimeEffect`, and neither is common code — but a thin platform seam with a plain-draw fallback is reachable, and it is what unlocks real blur, bloom and foil. This is the one place the no-engine rule deserved re-reading rather than restating, and the brief that settled it was "the fidelity of a truck driving simulator". `ui/gpu/StageShader.kt`, with a plain-draw fallback that is what a third of Android still sees. It rasters without a GPU, which is why `:studio` can look at it. The felt's weave (#65) is its first use.

100. **Decide what this is.** **[your call]** A simulator you play *on* and a game you play *against* want different work. Roughly thirty of the items above only make sense for the second one, and that is a decision rather than a discovery.


## Where it starts

Four items gate most of the rest, in this order:

1. **A rigid-body solver in core** (#35) — a third of the list is downstream of it.
2. **Turn the stage into a camera** (#1) — everything in §1, and it changes what
   every shading term is computed against.
3. **A sequence type in core** (#54) — without it every multi-card moment is
   written twice.
4. **A retained scene in core** (#92) — the ceiling everything else runs into.

And two that are not features at all, and should have come before any of them.
Both now exist: **golden-image tests** (#95) as `GoldenStageTest`, and **a
frame budget readout** (#96) in the play stage's loop. A hundred visual changes
without those two would have been a hundred silent regressions in each other's
work — the first of them, making the rig a value, was proved harmless by the
golden going green without being re-recorded.
