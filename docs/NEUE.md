# Neue Master Tool

The deck builder for a desk: mouse, keyboard, a large display, and the
**Master UI** design language (`github.com/kaiharimoto/Master-UI`,
`kit/MASTER-UI.md`) — paper and ink, zero radius, no shadows, Inter, numbered
pages, a quiet voice.

**It is the app.** It began as a separate desktop application beside the tablet
app; kai has since made it the one this repository is about, to be ported fully
and faithfully to the Android tablet — where it replaces the tablet app in place
— and to a proper Mac app. It is built on `:core` (every deck rule, fitter, lens
and odds calculation) and `:builder` (the state holders, `DeckBuilderState`
first, moved out of the old tablet app's `:ui` with their packages intact). None of its
look comes from anywhere else. The port's phases are in `CLAUDE.md`, "The port".

![The builder, paper](shots/neue-builder.png)
![The builder, ink](shots/neue-builder-ink.png)

Play mode is not in it. kai will rebuild play from scratch later; Neue is the
builder, its odds and its statistics.

---

## 1. Where it lives

| | |
|---|---|
| `app/neue/` | the module: every screen; the desktop's `main` in `jvmMain`, the APK hosts the rest |
| `app/builder/` | `DeckBuilderState` and the plumbing Neue shares with the APK |
| `app/neue/VERSION` | the version a local build carries; releases pass `-Pneue.versionName` |
| `app/neue/icons/` | installer icons, drawn by `tools/neue/mark.py` |
| `core/input/DeskShortcuts.kt` | the keyboard, as data |
| `core/prefs/NeuePreferences.kt` | its settings document (`"neue.ui"`) |
| `core/update/NeueReleaseTrack.kt` | how it finds its own releases |
| `.github/workflows/release-neue.yml` | the release |
| `studio/.../NeueStudio.kt` | headless screenshots: `tools/shoot.sh --neue` |

```
cd app && ./gradlew :neue:run                  # run it (needs Google Maven, like every module but :core)
./gradlew :neue:jvmTest                         # the Master UI law test and the key map
tools/shoot.sh --neue --page=builder --theme=ink --width=2560 --height=1440 --name=b
```

### 1a. One app, two targets (1.0.20)

`:neue` compiles for the desktop (`jvm`, packaged as the installers) and for
Android (`android`, a library the APK hosts). Nearly all of it is in
**`src/sharedMain`**, a source set between the two: both targets are JVM, so the
JDK is there (`java.io.File`, `java.time`, `String.format`), and only what really
is one platform's lives in `src/jvmMain` or `src/androidMain`. The compiler cannot
tell those apart — Android's build resolves `java.awt` too, and it dies on the
device — so **`SharedPortabilityTest`** reads `sharedMain` and refuses AWT, Swing,
`java.net.http`, Skia, desktop windows, `ProcessBuilder` and the desktop-only
Compose APIs. Each has one seam:

| Desktop-only | The seam, in `sharedMain` |
|---|---|
| the machine: data folder, version, browser, clipboard, a file picker | `expect object Platform` (`platform/Platform.kt`); the crash file and the issue report are shared extensions on it |
| `Modifier.onPointerEvent` | `Modifier.onPointer` (`kit/Pointer.kt`), the same behaviour on the common `pointerInput` |
| `TooltipArea`, the text fields' context menu | `PlatformTip`, `ProvideTextMenus` (`kit/Overlays.kt`) |
| `VerticalScrollbar` | `expect fun BoxScope.ScrollbarFor` |
| the AWT blank cursor | `blankPointerIcon()` |
| Skia pixel reads for the name masks | `coil3.Image.argb`, `alphaBitmap` — the letter-finding is shared (`NameMasks.compute`) |
| Skia's blur for zen's shadows | `DrawScope.blurRect` — `ZenShadows.kt` and its platform halves are still the one place a blur is allowed |
| `java.net.http` (the originals, the updater) | `httpDownload` (`platform/Download.kt`) — `java.net.http` on the desktop as ever, `HttpURLConnection` on Android |
| launching an installer | `handOffInstaller` |
| AWT's HSB arithmetic (group shimmer) | `core/model/Hsb.kt`, swept bit for bit against `java.awt.Color` by `HsbTest` |
| classpath fonts | Compose resources (`src/commonMain/composeResources/font`) |
| the deck picture (Skia offscreen) | `expect class DeckShots`; the desktop keeps `DeckShot` |

The desktop entry point (`Main.kt`), its JDBC driver and its file dialogs
(`Files.kt`) are `jvmMain`'s alone. The desktop is unchanged by any of it; the
studio's shots before and after are the proof.

### 1b. The tablet (1.3.0)

From **v1.3.0** the APK *is* Neue: `app/androidApp`'s `MainActivity` builds
`rememberHolders` on the same `kai_master_tool.db` the tablet app used, so an
installed tablet updates onto it and keeps its decks. Its settings are Neue's
own document (`neue.ui`); the tablet app's `UiPreferences` are left in the
database, unread. The tablet UI and the play stage are gone from the APK — they
are at `c2fc8d8` (`neue-v1.0.19`) and in `docs/classic/`.

- **Landscape first** (`userLandscape`). A phone's portrait layout is its own
  later phase.
- **A mouse and a keyboard on the tablet are the desk's.** Compose reports a
  pointer's type and buttons, so a mouse gets every `DeskMouse` idiom and a
  hardware keyboard every `DeskShortcuts` chord (`dispatchKeyEvent` hands keys
  to the same `onKey`). Only a *finger* reads the touch table.
- **Touch is a third table, `core/input/DeskTouch.kt`**, beside the keyboard's
  and the mouse's, and `DeskTouchTest` holds it to the mouse's — nothing a mouse
  can do to a card is out of a finger's reach:

  | Finger | Pool | Deck |
  |---|---|---|
  | Tap | select, read in the inspector | select, read in the inspector |
  | Double-tap | add (the right-click) | remove this copy (the right-click) |
  | Press and hold | open large, every action beside it | the same |
  | Drag | across picks it up; up and down scrolls | picks it up |

- **Android reports no button for a finger**, so every "primary button" test is
  `isPrimaryPress` and a finger is told apart by `byFinger` (`kit/Pointer.kt`).
  A right-click menu is `Modifier.onContextMenu`: the secondary button, or a
  finger held for `HOLD_MS`.
- **Hover-only affordances have a finger's form**: the art chip shows on the
  *selected* card (`LocalTouchFirst`), a group's swatches open on a tap of its
  square, the rail stays pinned (`NeueState.railPinned`) because no edge can be
  reached for, two fingers pinch the deck's zoom (and zen's gaps in deep zen),
  and deep zen always shows its corner — nothing else wakes it without a key.
- **Back** is `dismissTop()`: the topmost sheet, menu or pop-out, as `Esc` is.
- **The family cursor is a mouse's only.** It is drawn when a mouse moves and
  never for a finger.
- **Updates** read `/releases/latest` — the `v*` track every tablet already
  reads (`apkUpdate`, `DesktopOs.ANDROID`) — download to the cache and hand the
  APK to the package installer through the `FileProvider`, asking once for the
  install-unknown-apps permission.
- **Not yet on the tablet**: the deck picture (a note says so), tooltips (no
  hover), and Settings' "open the data folder".

#### Room and ways out (1.3.1, the touch swarm's first release)

The touch swarm (seven agents walking three tablet sessions through the code)
found the finger's grammar sound and the frame round it broken. The first release
fixes the frame:

- **`core/layout/PaneBudget`** shares the page's width. On touch the index is a
  56dp strip of numerals (`IndexStrip`), the pool and inspector are 320, the
  deck keeps a 520 floor (the inspector yields first, then the pool narrows), and
  with Groups on the Groups panel takes the inspector's place. The deck went from
  194dp to 570. Widths are **physical** — dp at a scale of one — on the desk too,
  so the interface scale grows what is in the panes and never the panes, as
  `NeuePreferences` always said. A `ResizeRule` has a 32dp grip on touch, laid
  over paper, with no width of its own.
- **`core/input/BackChain`** is Esc's chain and Android's Back, one list. Back
  never drops focus or the selection, and from another page goes to the builder
  before it leaves the app. `MainActivity`'s callback is enabled only while there
  is something to close, so the system's predictive back-to-home plays.
- **Immersive by finger**: a tap on the 32dp paper strip along the top brings the
  bar out, a tap in the left gutter the index (`EdgeReveal.onTap`), and deep zen's
  corner offers **Leave full screen** beside Leave zen.
- **The Decks rows** show **More** on touch, and a hold opens the same menu; the
  desk's hover-revealed buttons are not composed there, so nothing invisible is
  tappable.
- **A deck is never lost**: auto save is on by default (1.0.22, everywhere),
  leaving the app saves, and opening another deck saves or asks first.
- **Every kit text field reports its focus** (`LocalTextFocus`), so a keyboard
  cover's typing never reaches the shortcut table.
- **A draft's double-tap is two votes**, never a removal
  (`DeskTouch.resolve(…, drafting)`), and the art chip comes only after
  `CHIP_DELAY_MS` and only on cards at least 48dp wide.

#### Words and the keyboard (1.3.2)

- **Hold any control to read its name.** Android's `PlatformTip` is a finger's
  hold (the card's hold time), shown clear of the finger; the rest of the press is
  spent, so the lift does not click, and the name lingers
  `DeskTouch.LABEL_LINGER_MS`. A pen's hover shows it too. A tap on a disabled
  control that knows why says why (`explainsWhenDisabled`, `LocalReasonNote`).
- **The soft keyboard's key ends editing.** `MuInput` splits a hardware Enter
  (`onSubmit`, as on the desk) from the IME action, which always hides the
  keyboard and lets go; searches read **Search**, number fields get the number
  pad, and the palette's key reads **Go** and runs the highlighted row.
- **The keyboard pushes panes, never the deck** (`imePadding` on the pool, the
  Groups panel, the Decks list, dialogs, the palette and toasts). A finger on the
  deck or the inspector, a finger scrolling the pool, or the keyboard put away
  lets go of the field (`releasesTypingOnFinger`). The search pop-out does not
  raise the keyboard when a finger opened it (`Studio.focus`), and keeps its
  search while closed (`StudioMemory`).
- **The bar on a tablet**: Import and Export in words, no Screenshot (it has no
  Android half yet), room between unlike neighbours, and the inspector's Hide at
  its bottom left. **Key hints only with a keyboard** attached
  (`LocalHardwareKeyboard`, from the activity's configuration); a gesture's name
  in the same box is always drawn.
- **Speaking finger**: `core/input/DeskWords` writes the empty places' hints for
  either hand, and `DeskWordsTest` refuses a finger's sentence that says click,
  hover or a key. The help is **Fingers** on a tablet, the finger's table first
  and the keyboard's only with one attached; the index strip has a `?` cell for
  it, and the first launch shows one note (`touchIntroSeen`) with the way to it.
#### Fingers that are sure (1.3.3)

- **Taps are counted by surface, the platform's way** (`core/input/TapBurst`).
  The pool's grid, each deck section and the search pop-out's grid own one burst:
  a second press within `DOUBLE_TAP_MS` of the first **lift**, and within 24 dp of
  the first press, is a double-tap on the **first** card wherever it landed; in
  the pool every further tap adds again. The hold is the system's long-press
  delay, at least `DeskTouch.MIN_HOLD_MS` (`holdMs`), which honours Android's
  "touch and hold delay"; the pop-out's grid answers a tap at once.
- **The carried card rides above the finger** (`core/input/CarryOffset`): at least
  64 dp wide, its bottom 12 dp over the fingertip, and the drop resolves at the
  **drawn** card's centre — the classic rule, "a drop lands where the card is
  drawn". Hysteresis and a deck card's pick-up are 12 dp, not pixels; the pool
  picks up at `PICK_UP_RATIO` across for its run along. Over the pool a deck card
  frames the pool in ink and its line reads **Let go to remove**; a drop that would
  be refused hatches the carried card with a ✕. A second finger lets it go home.
- **Haptics, one vocabulary, hand events only** (`core/haptics/DeskFeel`, played by
  `kit/Feel` through `View.performHapticFeedback`: no permission, the system's
  switch honoured, nothing on the desk). Hold, pick-up, a slot change, a drop, an
  add, a removal, an art step, a zen snap and a pinch's stop; a select, a refused
  add and a refused drop are nothing, so "no buzz" means "not in the deck". The
  edits report whether they applied (`addCard`, `removeAt`, `moveCardTo`).
  `NeueState.actingBy` marks a finger's gesture, so the desk never buzzes or rings.
- **A press shows itself**: every kit control's hot look is `collectIsHotAsState`,
  hovered *or pressed*, held `PRESS_ECHO_MS` after the lift.
- **A finger's add or drop is ringed where it landed** for `REVEAL_MS` (a 2 dp
  ink ring inside the card, `DeckBuilderState.revealAt`, `DeckHistory.addedAt`);
  into a hidden section, that section's Ex or Si box flashes instead.
- **Two fingers on the deck** (`TwoFinger`): apart or together sizes the deck;
  slid up or down with the groups on sets their gap; spread past full size hides
  the pool and the inspector, pinched at full size brings them back. The size is
  held and written once, on release (`NeueState.update(persist = false)`).
- **Two fingers tapped undo, three redo**, anywhere (`MultiTap`, `DeskTouch.window`,
  in the help's **Anywhere** column). On a tablet the History menu leads with
  **Undo: …**, and a goal edit is named as one (`goalsChanged`).
- **A resting thumb fires nothing** (`muClickable`): a finger's click must be a
  tap by `DeskTouch.isTap`, the cards' own rule; the kit's controls and the
  dialog scrims use it.
- **The S Pen's side button is the right-click** on cards (`PEN_BUTTON_TAP`),
  tested before the finger's grammar; drawing up a group it is still a vote.

#### Targets and type (1.3.4)

- **Touch metrics** (`core/input/TouchMetrics`, held by a test to a 44 dp pitch):
  chips and list tags 32 dp tall and 12 dp apart, links that act boxed 32 dp tall,
  menu rows 44 dp, small segments outside the deck 36 dp at 11 sp. The lens row
  and the bar's format keep 28 dp (`Segmented(compact)`): that is deck budget.
- **The pool's header**: Hide pool and Advanced search 40 dp with the field
  between them; the Text and Side switches are each one target with their word;
  Side reads **To side**, the pool's line says a double-tap adds to side, and the
  side deck's name is inverted. The keyboard's cursor in the results is an outline,
  and only with a keyboard attached.
- **A Groups row**: a 32 dp colour square, 32 dp swatches on their own line, 40 dp
  Edit, Up and Down; Delete is on the row's hold menu only.
- **Your own art**: on a tablet Remove is on the card's hold menu, and on either it
  asks first (`confirmRemoveArt`); + Your own is a button, the arrows 40 dp; a pick
  that fails says so, and a picture named without its extension is known by its bytes.
- **Share** (`Platform.canShare`): the builder's Export menu and a Decks row's gain
  **Share YDKe code…** and **Share .ydkx file…**, through the system's share sheet.
- **Text size** (`NeuePreferences.textScale`, 100 / 115 / 130%): the type alone,
  multiplying the font scale, never the panes or the cards. Unset is the platform's
  own choice (`textScaleOn`): 115% on a tablet, 100% on the desk, so nothing is
  seeded. The group-name tab is measured off its type (`nameTab()`), 17 dp at 100%.
  No type is under 11 sp any more (Stats' level labels, a missing card's number).
- **Settings on a tablet**: a switch's whole row is its target, 48 dp; the HD art
  help is not cut off; the art library downloads only on an unmetered network
  (`Platform.onUnmeteredNetwork`, "Waiting for Wi-Fi"); no Index row or Pin link;
  "Stored on this tablet".
- **A read-only scroll thumb** on Android (`Scrollbars.android.kt`): 3 dp of ink25,
  no track, no input, whenever a pane continues.
- **Deep zen keeps the screen on** (`FLAG_KEEP_SCREEN_ON` while immersive and deep),
  and lets it go on waking, leaving immersive or leaving the app.

- **The proof is an emulator**, since the studio cannot draw Android:
  `.github/workflows/android-smoke.yml` boots a Pixel Tablet image, runs
  `NeueSmokeTest` (a saved deck survives a launch, the builder opens it, no crash
  is written) and uploads its screenshot.

### 1c. The phone (1.3.5)

kai tried the APK on a phone and it was unusable. The manifest locked it lying
down; the desk's 48 dp bar ran off a 412 dp screen and took the **Update** pill
with it; and the update dialog, 672 dp wide with a 360 dp box of notes and one row
of buttons, was taller and wider than the phone, so **Install could not be reached**
and the app could not update itself. kai's four choices are what follows.

- **What it is running on** is `core/layout/FormFactor`: a touch screen whose
  *smallest* width is under 600 dp is a PHONE (Android's own line, so turning it
  round never makes it a tablet), any other touch screen a TABLET, the desk always
  DESK. `NeueWindowContent` reads it off the window in physical dp, before the
  interface scale, into `NeueState.form` and `posture` (`Posture`, TALL or WIDE);
  `LocalPhone` carries it to the pages. The studio draws one with `--form=phone`.
- **The screen turns by a setting** (`ScreenOrientation`, `NeuePreferences.orientation`:
  Portrait, Landscape or Auto; null is the device's default, upright on a phone and
  lying down on a tablet). The manifest says `fullUser`; `MainActivity.applyOrientation`
  sets the user-flavoured `requestedOrientation` before the first frame and again
  when the setting moves, so the rotation lock is respected. The one-tap toggle is
  **Rotate** in the bar's menu (Portrait → Landscape → Auto), and Settings → Screen.
- **Dialogs fit any window**, desk included (`MuDialog`): never wider than the window
  less 12–16 dp a side, never taller; the body scrolls between the title and the
  footer, which always stay on screen, and the footer's buttons wrap (`FlowRow`). A
  body that scrolls itself passes `scrolls = false`. The menu layer scrolls a menu
  taller than the window. The emulator walk proves the update's Download is on the
  phone's screen.
- **The shell** (`shell/PhoneShell.kt`): `PhoneBar`, one 48 dp line — the deck's
  name (editable, `DeckNameField`) and its standing, undo, redo, a filled **Update**
  chip whenever there is one (never in the overflow), and ⋯, the overflow, which
  holds every tool of the desk's bar (`NeueHolders.phoneMenu`: search, advanced
  search, groups, history, format, save, auto save, new, import, export, rotate,
  full screen, theme, gestures, check for updates). The pages are `TabBar`, five tabs
  along the bottom, hidden while the soft keyboard is up; lying down they are a
  strip down the left, where the height is the deck's.
- **The builder upright** (`builder/TallBuilder.kt`): the deck on top, the pool docked
  along the bottom (`PoolDock`: PEEK is the grabber and the pool's header, measured;
  HALF keeps 200 dp of deck; FULL is the screen). Typing is FULL; letting go of the
  field never drops below HALF (`PoolDock.afterTyping`); the stop is
  `NeuePreferences.phoneDockStop`; a drag or a flick on the grabber chooses one, a
  tap steps it. `imePadding` is outside the dock's height, so the keyboard lifts the
  dock rather than eating its field. The deck is fitted width first and scrolls
  (`DeckLabels.stack` over `DeckFitter.stack`), every section `DeckFitter.phoneColumns`
  across — five on a 412 dp phone, each card at least 72 dp — and `NeueState.phoneColumns`
  keeps the arrow keys' grid the same. The lens tabs are one **Lens ▾** button.
  While the groups are out the dock has two tabs, **Cards** and **Groups**
  (`PhonePool`); the Groups panel is never beside the deck on a phone.
- **No inspector on a phone**: a finger's tap opens the card large
  (`NeueState.viewSoon`) once `DeskTouch.PHONE_VIEW_MS` has passed without a second
  tap, so a double-tap still adds or removes. The viewer stacks the art over the
  actions and the text when the window is taller than wide.
- **Lying down** a phone uses `PaneBudget.solve(phone = true)`: the strip, the pool at
  40% (at least 240), no inspector, and the deck the rest, with no 520 floor.
- **The pages** lay themselves out below 600 dp: `PageHeader` drops the title the bar
  already says and wraps its actions; `SettingRow` stands its label over its control;
  a library row is one cover, the name and More; Odds' rates are 84 dp and Stats'
  columns stand one over the other; the search pop-out's filters and reading pane are
  sheets over the results. The phone's text size defaults to 100%, not the tablet's 115%.
- **The emulator** runs twice (`android-smoke.yml`, a matrix of `pixel_tablet` and
  `pixel_7`); the phone runs `NeueSmokeTest.aPhoneHeldUpright` and the tablet walk
  assumes it is not on one.

**1.3.6, kai's three:**

- **Upright, the deck is the decklist's 10×4** — the desk's rows (`columnsOf`, ten and
  fifteen) fitted to the phone's width by `DeckLabels.place`, the whole deck on screen
  with no scroll. `naturalDeckHeight` sums it the way `DeckBody` fits it, and the dock
  is told (`DockMetrics.deck`): at HALF the pool has everything under the deck, where
  it had half the window. Lying down the deck keeps `phoneColumns` and scrolls.
- **The foil follows the phone** (`NeuePreferences.foilTilt`, on). `TiltFilter` (core)
  turns gravity — `TYPE_GRAVITY`, else the accelerometer; no permission, no drift —
  into the screen's tilt about a rest that follows the hand over 2.5 s, so held still
  the light settles in the middle and it is the *turn* that moves it, sitting up or
  lying down. `rememberDeviceTilt` listens only while the activity is resumed;
  `LocalTilt` is read in each card's draw, so a turn redraws foil and recomposes
  nothing. A card a finger is over keeps the finger's light.
- **Full screen** (`NeueState.showcase`, `builder/Showcase.kt`, the viewer's ⤢): the
  card on ink, as large as it goes (`Showcase.card`), turned against the hand as a card
  held in the hand seems to stay put, its foil catching the light; or **Art**, the
  artwork alone covering the screen (`Showcase.art`, from `ArtFrame`) and sliding 5% behind
  the glass as it tilts. The screen stays on; Back returns to the viewer. On the desk
  the pointer stands in for the tilt.

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

kai granted three exceptions. The law test names the files the two colour
ones live in; the third is motion, and lives in `core/motion/DeskLean.kt`:

- **`cards/Foil.kt`** and **`cards/Holo.kt`** — the foil border on a card's
  face. It is kept, as kai asked, and it is content rather than chrome (§17:
  the pixels of a picture keep their colour). See §2a.
- **`cards/GroupMarkers.kt`** — *"colors are allowed for deckbuilding markers
  only (like card groups in the deck itself assigned by the user)"*. A group's
  hue shows in the deck's cracks, on its key, on its mark. Lenses that are
  facts rather than the user's own drawing — type, copies, legality — are told
  apart by ink weight, the way everything else in Master UI is.

- **Cards move** — *"have the cards animate and react by tilting in a
  satisfying way"*, and a card that *"slightly tilts and lifts in 3d space"*
  when picked up. See §2b. Cards only: chrome never moves, and nothing uses a
  spring (the law test still refuses one) — a thing that follows a pointer has
  no duration, so its easing is `DeskLean.approach`, a frame-rate independent
  half-life.

Card art keeps its colour inside a §17 content frame. Nothing else does.

### 2a. The holographic foil

kai chose it from six Blender mockups (`tools/foil/`, variant C) and asked for it
to be made better; `Holo.kt` is the refined model, per pixel, as a runtime
shader through `ui/gpu/StageShader`. A foil stamp is a mirror with a
diffraction grating pressed into it:

- **silver** with an anisotropic highlight — a streak that slides round the
  band — under two lights, a key from the upper left and a bounce from the
  lower right;
- **diffraction** from concentric grooves: order *m* shows the wavelength
  `λ = d · |(L + V) · g| / m`, three orders, d = 1600 nm, added as light;
- **grooves** you can see up close, which break the rainbow into striations
  (their pitch never drops below 3 px — finer is moiré);
- a **rim** where the stamp meets the print, lit by the same key, with an ink
  hairline.

**The frame round the artwork is the same stamp.** One set of grooves runs
across the card, so the border and the art frame catch the light together. Where
that frame is depends on the card's template, and `core/layout/ArtFrame.kt`
holds the three, measured to the pixel off YGOPRODeck's renders (813 × 1185):
**standard** for every monster, spell, trap and token frame, a square bevel from
(86, 205) to (728, 846); **pendulum**, wider and reaching down past the scales
because the art runs on behind the pendulum-effect box, from (44, 204) to (769,
887); and **Link**, the standard square with the eight arrow sockets left
unfoiled, because they are printed over it. Skill cards have no frame here.
`ArtFrameTest` pins the shapes; `:studio:shootFoil --cards=id:frameType,...`
draws a row of card types for checking the alignment by eye.

The eye is a couple of card-widths away and the **pointer moves it**: a mouse
stands in for tilting the card, and the light glides there over 180 ms when
the pointer leaves. The card itself never moves. Where runtime shaders are
unavailable the classic two-hue band is drawn instead (DESIGN.md §6).

`tools/shoot.sh`'s studio has a mode for it — `:studio:shootFoil` draws the
real `drawFoil` on real card art at scripted pointer positions, or as a sweep
for a GIF — so the app's foil can be compared frame for frame with the Blender
reference. Settings → Foil offers Holographic (the default), Classic and Off.

### 2b. Cards that lean

One picture rather than two rules: **the pointer is a finger pressed up under
a cloth the cards lie on.** The card over it rises (3.5%); the cards around it
sit on the slope, each with its edge nearest the pointer higher than its far
edge, falling away over about a card and a half (`DeskLean.toward`). Sweep
across the deck and the bump travels with you, and each card's foil catches the
light as it turns. A card being carried lifts 7% and leans back against its own
motion, its leading edge up (`DeskLean.carried`); the deck makes way for it as
it passes over.

It is the play stage's recipe: one `LeanField` per deck, stepped by one frame
loop that **sleeps once the cards settle** (nothing idles), and every card reads
its pose inside its own `graphicsLayer`, so leaning never recomposes. Two things
are load-bearing. The hit area is outside the layer, so a leaning card never
changes what the pointer is over (a card that tilted away from the pointer and
lost the hover would flicker). And the eye is **2.2 of the card's own widths**
away: `cameraDistance` is in 72-pixel inches, so a fixed one leaves a small card
flat and throws a large one at the viewer.

### 2c. The name in foil

kai asked whether a card's *name* could be stamped in the foil too, saw three
versions (`:studio:shootNames`: as printed; foil letters; foil letters over an
ink outline) and chose **foil letters**, which is the default. Settings → Card
names offers the other two.

The letters are found in the pixels (`core/layout/NameInk.kt`): every render
sets the name in one bar left of the attribute icon, and the ink is black or
white by frame (spells, traps, Xyz and Link are white), which the frame says
more reliably than the pixels can. A letter is how far a pixel has gone from the
bar's median toward its ink, so the letters keep their antialiased edges. The
mask is read off the picture the card has already decoded (`cards/NameMasks.kt`,
off the UI thread, kept by card and size), and the stamp is `Holo`'s sheet mode
kept only where the mask has a letter, so the name and the border catch the
light together. Holographic foil only; the screenshot carries it too.

---

### 2d. The family cursor

Neue uses the pointer every Master app uses, **Crop caption** — four 2 px trim
marks around a 2 px point, in difference mode; over something clickable they
open out and frame it, and a micro-caps caption in the slug under the frame says
what a click will do. `docs/master-ui/CURSOR.md` is the spec, vendored from the
Master UI repo (where it lives, with `kit/cursor/cursor.js`, on the
`claude/beautiful-bell-7du12e` branch; `main` does not carry it yet).

`cursor.js` cannot run here — it reads intent off a DOM, and Compose has none —
so it is **ported, not installed**: the arithmetic is `core/input/CropCaption.kt`
(the reference script's numbers: 5 px pad, 6 px inset past 480 × 160, the caret
at 1.8 × the type between 20 and 56, the 36 × 14 drag bar, the caption 6 px
under the frame and flipped above near the bottom, 180 ms snaps, 120 ms fades,
a tick every 300 ms), tested in `CropCaptionTest`; the drawing is
`neue/cursor/FamilyCursor.kt`. Three differences are forced by Compose and
worth knowing:

- **Intent is declared, not read.** A target says what it is with
  `Modifier.cursor(…)` / `cursorPointer(…)` — `caption`, `label` (the
  `aria-label`), `reason`, `value`, `showsWords` — which is the kit's
  `data-cursor*` hooks as parameters. Which targets are under the pointer is
  Compose's own hit testing (their hover), so a dialog occludes what is under it
  exactly as it does for a click; the innermost wins.
- **Menus are not popups any more.** A Compose `Popup` draws above everything in
  the window, the cursor included, and the kit's menus must keep the family
  cursor. The context menu and the select list now open in the window's own layer
  (`AnchoredBox`, `Overlays`), under the cursor. Tooltips are still popups; they
  sit under the caption slot now (36 px below the component), so the two never
  overlap. The text field's own Cut/Copy/Paste menu is still a popup, and the
  system pointer shows over it — as over a native top-layer surface.
- **The system pointer is hidden by the window's root** (`pointerHoverIcon(…,
  overrideDescendants = true)` with a one-pixel transparent cursor), over every
  child's own icon, and comes back wherever the cursor steps aside. Where the
  toolkit cannot make that cursor (headless), the arrow stays, as the kit's
  does when its script never loads.

**Over a card the marks are heavier** (kai, 1.0.12): difference-mode marks two
pixels thick go muddy on card art, so framing a card draws them 3 px thick with
12 px arms and a 4 px point, in paper with a 1 px ink edge — they read on any
picture. It is `emphasis` on the target (`CropCaption.arm`, `weight`, `point`),
set by every card and nothing else; everywhere else the kit's marks are as
specified.

Busy: the pool while the card pool syncs (a region, `Working`), the update
download (`Downloading` and its percent) and the screenshot export
(`Exporting`). The crash reporter's window has no family cursor: it is drawn
theme-free, to render when nothing else can, and keeps the system arrow.
`tools/shoot.sh --neue --hovers=card@0.3,0.2;undo@0.47,0.02` photographs the
cursor at each point and logs what it resolved to.

## 3. The window

**One 48 px bar** — the mark, the page you are on, then whatever the page puts
there (the builder puts the deck's name, its legality, its tools and Save), what
is being fetched and how far (§4h), the update pill and immersive mode — the 232
px index rail —
`01 Decks · 02 Builder · 03 Odds · 04 Stats`; below the rule, what is being
fetched, `Search Ctrl K` and Settings — and the page. Until 1.0.10 the app and
the builder each had a bar; kai merged them, moved search to the rail beside
Settings, and let the card count go to the pool, where it is read.

**The rail folds away** until the pointer reaches the window's left edge, and
comes out *over* the page rather than pushing it: a rail that pushed would
re-fit the deck, and every card would jump. It can be pinned (Settings, or on
the rail itself). **Immersive mode** (`F11`, or the button in the title bar) is
full screen with the title bar and the builder's bar folded over the top,
coming out when the pointer reaches the edge —
the deck and the panes that build it get the whole screen. `Esc`, last in its
chain, leaves it; so does leaving full screen any other way. **It stays when
another window takes focus** (1.0.13). Compose's full screen on Windows is the
JDK's exclusive mode (`GraphicsDevice.setFullScreenWindow`, via skiko, and nothing
else): with Java2D's Direct3D pipeline it minimised the window the moment a click
went to another monitor, and without it (1.0.12's attempt) the window stayed
decorated and never went full screen. So on Windows immersive mode is **a second,
borderless window laid exactly over the monitor** the builder is on — which Windows
treats as full screen, taskbar and all, and leaves alone when focus moves. A
frame's decorations cannot change while it is showing (`JFrame.setUndecorated`
throws), so entering and leaving swap windows (`Main.kt`); everything the builder
knows lives in `NeueHolders`, outside the window, and carries across. macOS and
Linux keep the window's own full screen, which does neither.

**The swap is a handover** (1.0.24, kai: "the transition between entering and
exiting immersive mode is very jarring. The whole app disappears for a second").
Two things made the second. The window on screen was disposed first and the next
built after it, so for as long as the next took — a native surface, a whole tree,
its first frame — there was no window at all. And the app's own lifetime was the
window's: every swap stopped and restarted the card pool, the preferences, the
update check and the art library, and made the image loader afresh, so every
card's picture was read again. Now the lifetime is `NeueEffects`, composed once
outside the windows (the tablet's `NeueRoot` still brings it along), and the swap
runs in three steps (`WindowHandover.kt`): the window on screen draws one more
frame through a graphics layer, keeps it as a picture and shows that in place of
its tree, which it lets go (`Shown.released`); then the next window is built —
Compose paints a window's first frame before it shows it — and shown over the
picture; and only once it is on screen and painted does the picture's window go
(three seconds at most, whatever happens). The tree goes *before* the next is
built, never after: two live trees register the same drop targets and zen's slots,
and the one let go last would take them from the other. `WindowHandoverTest` holds
the picture to the frame it replaces, pixel for pixel. A `Z` pressed long ago no
longer reads as pressed now in a tree composed afresh (`zenHandled`), which had
sent every trip into immersive mode after the first `Z` straight to zen. **The borderless window is not resizable** (1.0.15): Compose gives an
undecorated window that is resizable its own resize border
(`UndecoratedWindowResizer`), an invisible band round the edge that takes the
pointer — and at the left edge that band was exactly where the rail comes out,
so reaching for the rail dragged the window instead.
In immersive mode the page keeps **32 px of paper at the top** (`IMMERSIVE_TOP`),
so the deck's own row is out of the bar's reach, and the deck's spare height is
shared above and below it — centred, not pushed down. Leaving goes
through `Floating` first and re-maximises a moment later: Compose's
`placement = Maximized` sets maximised and **never clears full screen**, which is
how 1.0.3 left a window stuck full screen with its bars back. And a click below
the folded-out header lets go of the deck name — on a desktop nothing else takes
focus from a text field, so a bar held out while you type never folded away. One pointer
watcher at the root, consuming nothing, decides all of it
(`core/layout/EdgeReveal.kt`): a bar comes out at 8 px from the edge and folds
once the pointer is 24 px clear of it; a deck name being typed holds the top
out; a carried card opens nothing.

**02 Builder** is three columns under the window's one bar. The pool (search,
inline filters, a flush grid of cards) and the inspector are resizable and
hideable; the deck between them is **fitted, never scrolled**, by the tablet's
own `DeckFitter.plan`: row widths in (10 main, 15 extra and side), one card size
out. On a large display the fitter simply hands back larger cards.

**Every pixel of chrome is a pixel off every card** — kai's brief for 1.0.9 and
again for 1.0.10. The exploration behind it measured the column at 1920 × 1080:
432 px of the 1080 went to bars, strips and padding, and the cards had 648.

| | 1.0.8 | 1.0.9 | 1.0.10 |
|---|---|---|---|
| app bar | 40 | 40 | **one bar, 48** — the app's and the builder's merged |
| page header | 92 | builder bar, 48 | (in the one bar) |
| footer | 64 | gone | gone |
| main strip + lens keys | 45 + 45 | 36 + 36 | **one row, 40**: Groups button, name, count, lens |
| extra and side strips | 37 each | 28 each | **none** — the names go where the deck has room to spare |
| padding round each grid | 24 | 12 | 12 |
| gap between cards | 2 | 0 | 0; with a lens on, the deck in pieces (1.0.15) |

**The section names go in the deck's own empty space** (`core/layout/DeckLabels.kt`).
A fitted deck is limited by one side and has room along the other: limited by
its height, there is paper beside the grids; by its width, paper under them. So
the deck is fitted both ways — the extra and side decks' names in a gutter to
the left of their cards, or in a 24 px row over them — and the arrangement that
draws the larger card wins. With the pool and the inspector out at 1920 × 1080
the deck is width-limited, the names take rows, and the rows cost nothing.

**Groups break the deck into pieces** (1.0.15, `core/layout/GroupPieces.kt`,
`builder/Pieces.kt`), kai's picture: "have cards of one group together with no
gaps, and have it separate from the next group … if cards are in the same group
across rows, have them be close together with no gap vertically. The end result
should be a deck broken apart and split into pieces." A **piece** is a set of
cards of one group that touch in the grid (ungrouped cards are a group of their
own, so they stay flush with each other). Every card keeps its size and its
cell; a piece moves as one rigid shape — right by some number of 10 px gaps and
down by some number — so inside it every card is flush and its rows are aligned,
and between two pieces there is always at least one gap, along rows and along
columns. Each piece's shift is the least that keeps it a gap clear of every
piece to its left and above: the longest path through two orderings.

Two things about it are load-bearing:

- **Pieces are grown, not found.** The one arrangement no shift can satisfy is a
  piece on both sides of another — and ungrouped cards in a U round a grouped
  card are the *ordinary* case, not a rare one (the first version fell back to
  one-row pieces for the whole deck, and every ungrouped column came apart). So
  every run along a row starts as a piece, and a run joins the run of its group
  above it unless that makes such a loop. Only the arm that would close a U
  stands apart. `GroupPiecesTest` sweeps four hundred random decks: flush inside
  a piece, a gap between two, and no card over another.
- **The fitter is told.** The gaps are width and height the cards pay for, so
  they go to `DeckFitter` as `extraWidth`/`extraHeight` — the same contract as
  the chrome — and the deck still fits. A drop resolves against where the cards
  really are (`GridGeometry.placed`), and the insert bar stands at the left of the
  card it names.

The gap is 28 px and each piece is outlined 5 px in its group's colour (1.0.18;
18 and 4 in 1.0.17, 10 and 2 in 1.0.15 — kai asked for wider twice), in the gap
round it, so it reads as one shape with its colour on it. **The group's name is
written once**, on a tab in its colour rising from a top edge of its pieces — kai: "have it instead just write the name
of the group in the border once instead of abbreviation symbol on every card"
(1.0.18). The edge is the longest there is (`PieceLayout.labelEdge`, 1.0.22): a
top edge is a run of cards along a row with nothing of their own piece above,
and the name is cut short to what it stands on, so a lone card over three in
the next row is named over the three — kai: "prefer the longest edge, instead of
the first card". An edge the name already fits is as long as any, so a short
name stays at the largest piece's top-left. The tab is as wide as the name or
the edge, whichever is less, lettered black or white by the colour's luminance; the room it needs over
the grid (`NAME_TAB`, 17 px) is declared to the fitter with the gaps. The lens opens and closes the pieces
over 320 ms; closing, they close from where they were. The screenshot export
draws the same pieces.

**The Groups button is the groups** (1.0.15): the Roles lens, the pieces and the
panel beside the deck, all one switch — kai: "untoggling the groups button
should hide the groups and gaps between the cards." Off, the deck is plain. The
Roles tab is gone from the lens, because the user's own groups are what the
button is for; the tabs — Deck, Archetype, Type, Copies, Legality — are the other
ways to see the deck in pieces, and `b` walks them. `K` is the button; `G` turns
it on.

**The wheel sizes the deck** (1.0.17, kai: "let the user adjust the card sizes
using the scroll wheel. cards will stay center of screen with more negative space
around them"). Down, the fitter is handed a smaller share of the column
(`NeuePreferences.deckZoom`, 40–100%) and the deck, re-fitted, stays centred with
paper round it; up, back to the size that fills the column. It is a re-fit, not a
transform, so every rule of the layout — the pieces, the labels, the drop targets —
holds at any size. With the groups on, **Shift and the wheel** open and close the
gaps between them (`groupGap`, 0.4–3 standard gaps). The pool's cards keep the
size the deck *would* be, not what the wheel made of it — the fit at the full
size itself, so nothing in the pool stirs while the deck moves.

**The wheel glides** (1.0.24, kai: "more sensitive and more fluid/smooth
feeling"; `core/layout/DeckZoom.kt`). A notch took a flat 4 % off and the deck was
re-fitted at once, so the cards jumped a step a notch, fifteen notches end to end,
and a touchpad's stream of small deltas came through as stutters. Now **a notch
is a ratio**, `e^(−0.12)` — about 11 %, eight notches end to end, the same-looking
step at any size — and the delta is taken as it comes, so a touchpad's fraction
of a notch is that fraction of the change; a flung wheel is capped at three
notches an event, and the last notch up lands on the full size rather than a hair
short of it. The share the wheel sets is where the deck is going: **the deck is
re-fitted every frame at a share closing on it exponentially** (a 45 ms time
constant, there to the pixel in about the family's 180 ms). That is not a spring
— it never overshoots — and a notch mid-glide only moves the target, so a spun
wheel is one motion. The stored preference is the target, written once the wheel
rests; a pinch on the tablet and the size read on opening are not glided. A deck
limited by its width sat at the top at full size and in the middle below it, which
a glide would have shown as a drop of half the spare height in its first frame; it
now moves to the middle over the first 3 % (`DeckZoom.centring`). Re-fitting a
frame is the fitter's arithmetic twice and one recomposition of the deck, with the
cards' art already loaded — the same work a pinch always did per event. **The lens row stands
still** (1.0.18, kai: "have the ui elements like buttons stay in fixed
positions"): it is laid out once at the top of the column, inset to the deck's
edge at full size, and only the deck below it is re-fitted and centred — the
buttons no longer ride inward and downward with the cards.

**Beside Groups, the foil switch** (1.0.15): a boxed button the same size carrying
a small card in the holographic foil itself (`FoilGlyph`, drawn by the same
`drawFoil` a card face uses, its light following the pointer over the button);
off, every card face is plain and the glyph is a bare outline. Beside it,
**Extra** and **Side** are a switch each (1.0.17; one switch for both in 1.0.15),
and the main deck has whatever they give up (`extraVisible`, `sideVisible`); a
hidden section takes no drops and leaves no slots in zen. The row is the deck's and never clips a tab: narrower
than 860 px it drops the words "Main deck", narrower than 720 px it shortens
Archetype, Legality, Extra and Side and keeps the count only when it is out of range.

**The Groups panel is centred down its column** (1.0.16) — the column is taller
than its groups, and a reach for the first row at the top brought the window's bar
out — and **New group** is a full-width button under the groups rather than a
link over them. A list taller than the column still starts at the top and scrolls.

**A group may hold any card of the deck** (1.0.17): drawing one up, a click on
the extra or the side deck adds the card as a click on the main deck does, and a
group's count is its cards in all three. Assignment was always per passcode, so
this was two small refusals — the draft listened to the main deck alone, and a
group reopened for editing was seeded from the main deck alone, which quietly
dropped its extra- and side-deck cards when it was saved again.

**Palettes** (1.0.17, kai: "more choices in color palettes … from designer
choices"). Under the groups, seven palettes of six — Prism (the tablet's), Bauhaus,
Pastel, Earth, Ocean, Neon, Vintage. Since 1.0.18 they are a dropdown: the
closed header is the palette in use, its name and its colours, and a click opens
the other six. **Choosing one leaves them open** (1.0.24, kai: "the user is most
likely going to choose between the palettes to their liking, so it minimizing
upon choosing a palette makes it harder"): every group is recoloured at once and
the next can be tried straight away. They fold at a press anywhere off the Groups
panel — the window's one pointer watcher, consuming nothing, against the panel's
bounds (`NeueState.groupPalettesOpen`, `groupsPanel`), so the press still does
what it was for — at `Esc`, at the header again, or when the panel goes.
A group's colour is stored as an index, so a palette is only a reading of it:
choosing one recolours every group at once and changes nothing in the deck file
(`GroupMarkers.palettes`, the one file with colour in it; `groupPalette`).

**Slides** (1.0.18, kai: "add some data analysis visuals in a box that changes
like slides, with an auto play slide feature that can be toggled"): between the
groups and the palettes, a box of four slides — share of the main deck (and what
is ungrouped); the chance to open each group going first (five cards) and, in a
fainter bar under it, going second (six); how many of it a hand holds on
average; and **too many**, the chance of two or more in five — flooding on hand
traps, bricks or garnets. The second and fourth are kai's picks for 1.0.24. Bars in each group's colour; the numbers are `GroupStats.of`, in
core, from the same `LensOdds` the rows use. ‹ › step, **Auto** turns every six
seconds (`slidesAutoplay`, on by default) and the pointer over the box pauses it.
In 1.0.24 the controls took a row of their own and the title and each group's
name the box's whole width, wrapping rather than cut short (kai: "sometimes the
text is truncated"), and the slides of card types and of cards across main, extra
and side were dropped — kai: "basically useless to players".

**The Groups panel is where groups are edited** (`GroupsPanel`, 288 px beside the
deck). Every group is a row that can be changed where it stands: its name is a
field (written on Enter or on leaving it, so one rename is one undo), its colour
square, whose six swatches come out only while the pointer is on it or on them
(1.0.18: all of them on every row was "a bit distracting"), its count and opening rate beside the name, and **Edit cards**, up,
down and **Delete** as buttons on the row. The colour square isolates the group.
Right-click a row for the same and more. The Groups drawer that used to hold all
this — a sidebar over the deck — is deleted: kai, "there's no need for a pop up
sidebar … we have so much space in the roles column."

The bar keeps the words on Import, Export and Screenshot while it is 1500 px or
wider and drops to their icons (with their tooltips) below that; the wordmark
goes first. Its title field sets its own line height: the type scale's display
leading is tighter than a descender, and a single-line field clips to its line
— the tail of a `y` was cut off.

The pool's cards are drawn **the size of the main deck's** by default
(`DeckSized` in `PoolPane.kt`: as many columns as that width fills, rounded to
the nearest — `GridCells.Adaptive` only rounds down, so every card came out
larger than asked), and while the rail folds away the pool keeps **32 px of
empty paper** at the window's edge, because the rail comes out at that edge and
a pool running up to it put its first column where reaching for a card called
the rail.

**The inspector**: the picture on top (1.0.9 put the words first; kai moved the
picture back), then the name, the numbers and the card's text, then the details
(type, attribute, archetype, banlist) and the copies in the deck as sections that
fold shut with a click and stay shut (`NeuePreferences.inspectorFolded`).

**Contrast** is darker than the kit's ramp, in both themes, and Settings has a
*High* setting on top of that (`MuColors.of(ink, high)`; the table of alphas and
what each has to clear is on `MuColors`). The kit's `.60` meta text and `.25`
field borders sat at the edge of 4.5:1 and well under the 3:1 a control's
outline needs.

**The rail's foot** (1.0.15): light and dark is a sun on paper and a moon on ink
rather than the word; and its tips open *above* it. A tip normally opens under
its control, clear of the cursor's caption — at the bottom of the window there is
no room under it, the tip was pushed back up over its own button, and Pin could
not be clicked (`Tip(above = true)`).

## 4. Mouse and keyboard

The mouse is a table too, `core/input/DeskMouse.kt`, and the help dialog
(`F1`) renders it:

| | a card in the pool | a card in the deck |
|---|---|---|
| hover | the inspector shows it | the inspector shows it |
| click | select | select |
| right-click | **add** to the main deck (the extra, for a card that lives there) | **remove this copy** |
| Shift right-click | add to the side deck | add another copy |
| hold | **open it large** | **open it large** |
| hold right | **add** | **add another copy** |
| double-click | add (Shift: side) | — |
| drag | pick it up | move it; drop it on the pool to remove |

That is kai's brief for 1.0.10, in their words: **"hold left click should open
the inspector, hold right click duplicates. just right click removes the card
from the deck."** A right-click is the quick edit in both places — in from the
pool, out of the deck — a held right button is one more copy, and a held left
button opens the card large: the art as tall as the window allows, the text in
16 px beside it, the copies, and every action the old menu had
(`builder/CardViewer.kt`). A click outside it, `Esc` or its ✕ closes it; `Space`
opens the selected card the same way. Shift is still "the other way". A
right-click is read on release, the first moment it is known not to be a hold.
`DeskMouseTest` holds these rows and no gesture meaning two things.

The table moved three times before this (1.0.3: right-click removes; 1.0.4:
right-click adds everywhere, hold is the menu; 1.0.9: hold left adds, hold right
opens). **Right-click did nothing from 1.0.3 to 1.0.8**, and the table was never
the reason: Compose's `awaitFirstDown` answers only to the *primary* button, so
a right press went past the card as though it were not there. `CardPointer`
waits for a press of any button (`awaitAnyDown`). The studio's `--mouse` flag
drives real presses through the real modifier and logs the deck's counts, so a
gesture is checked rather than read:
`--mouse="right@0.3,0.25;left-hold@0.3,0.25;right-hold@0.3,0.25"`.

A press selects at once, then becomes a click, a drag (past the slop) or a hold
(450 ms still), whichever comes first; the card rises under the button while
the hold counts down, so the press says what it is about to do.

**The pool's Side switch** (1.0.14, beside Text over the results): kai's "Add to
Side" toggle, for siding a list in without holding a key. On, the pool's two adds
trade places — right-click, double-click, a held right button and `Enter` send a
card to the side deck, and Shift, still "the other way", sends it to the main
(`DeskMouse.forPool`, one function, so the table the help dialog shows stays the
off state). It is a setting (`NeuePreferences.poolToSide`), and the empty side
deck's hint follows it.

**The arrow keys walk the selection** (1.0.18, kai: "once a player selects a card
in the normal deck builder, let them move the selection with the arrow keys, which
will reflect in the inspector"). `←` `→` run along a row and through its ends;
`↑` `↓` go a row up or down, and past the top or bottom row of a section into the
next section on show, keeping the column. A pool card walks the pool the same way,
by the columns the pool is really drawn in (`GridStep`, in core with a test). `↑`
and `↓` were already the pool's results keys, so they are one pair with two jobs:
in the search field, or with nothing selected, the results; with a card selected,
the selection. The inspector follows, because a key clears the hover.

**Auto save** (1.0.18, beside Save): on, a changed deck is written a second and a
half after the last change, quietly — no toast — and Save reads **Saved** while
nothing is waiting. An empty deck that was never saved is left alone.
`DeckBuilderState.dirty` is what it waits on: set by every edit, cleared by a
load and by a save no edit overtook (`autoSave`). **On by default** since 1.0.22 —
kai: "have auto-save on as default" — and turned on once for everyone: the switch
is stored as `autoSaveOn`, so the `false` every document carried from when off was
the default is not read back.

**History** (1.0.17, beside undo and redo): every step undo can take back and
redo can put back, newest first, in words — "+ Ash Blossom", "Moved Nibiru to
side", "Changed the groups" — and a click goes to the deck as it was just after
that step. The undo stack keeps whole decks rather than edits, so each step is
read back off the decks either side of it (`DeckHistory.describe`, in core with a
test; `DeckBuilderState.history`, `travel`).

**Export is a menu** (1.0.15, kai: "a sub option between YDK, YDKX, YDKe Code,
and a Copied Text list"): the bar's Export, or `Ctrl E`, opens it under the
button. **YDK file** is the plain list; **YDKX file, with groups** carries the
groups, goals and anything else the deck holds; **YDKe code** copies the
`ydke://` line EDOPro and the deck sites paste (`YdkeCodec`: each section's
passcodes as little-endian 32-bit integers, Base64'd, `!` after each); **Text
list** copies the decklist as it is posted — `Main Deck (40)`, then `3 Ash
Blossom & Joyous Spring`, each card once in the order it first appears
(`DeckText`). Both codecs are in core with tests, the YDKe one round-tripped.
The palette has all four.

**A deck crosses from the desk to a phone as a QR code** (1.0.30, v1.3.7, kai:
"import deck via QR code" on Android, "export as QR code" on the desktop; **the
whole deck** from 1.0.31, v1.3.8: "carry as much information as possible,
including groups"). **QR code to scan**, last in Export (the builder's and a
library row's), shows the deck as a QR code in a dialog (`QrDialog`, `neue/qr/`),
with Copy the YDKe code beside Done for the simulators.

**What the code holds is `DeckQr`** (core): the deck's own `.ydkx` — the cards,
the groups, the lens, the hand goals, notes, siding patterns and any key a later
build writes, verbatim — under a `#name` line and a `#covers` line, which
`YdkCodec` reads as comments, so the body is itself a deck file anything imports.
zlib shrinks it (`Zlib`, `JvmZlib` in core's `jvmMain`: `java.util.zip`, which
the desk and Android both have) and **Base45** (RFC 9285, `Base45`) writes it in
the 45 characters a QR code's alphanumeric mode packs at 5.5 bits each, behind
`NMT1:` — the EU's COVID certificates travel the same way. The fullest deck, 60 ·
15 · 15 with every card in one of eight groups, a goal and a note, is about 1,150
characters: a version-23 code, 109 modules a side, and one code.

**A deck past one comfortable code is several** (1.0.32, v1.3.9, kai: "scan
multiple QR codes when we go over the limit… don't have it show one at a time").
Past `SINGLE_CHARS` (1,600) the Base45 is cut into even parts of at most
`PART_CHARS` (1,200, about version 23 each), each `NMT1P:<i>/<n>/<tag>:` and its
run, the tag an FNV-1a hash of the whole. The legacy `lab.ydkx` is two. **The
dialog shows every part at once**, numbered, in the grid that makes each largest
(`DeckQrGrid`, core) — nothing to page, nothing to press. On the phone
**`ScanActivity`** (the APK's own, on the embedded scanner's `DecoratedBarcodeView`)
stays open and reads *every* code in each frame with ZXing's `QRCodeMultiReader`,
counting "2 of 3 codes read" with a tick for each new one, and returns once the last
is in; the parts outlive a turn of the phone. **`DeckQrParts`** (core) collects
them in any order, each as often as it passes, starts over on a part of another
deck, and joins them only if the whole hashes to the tag. A screenshot of the grid
holds them all: **A picture of a QR code** reads every code in the picture
(`QrReader.readAll`), and asks for another picture only while parts are missing.
Only a deck past `MAX_PARTS` (24 codes, some twenty kilobytes compressed) sheds
anything — every extended key but the groups, then the groups — and the dialog
says what stayed behind; the cards and the name always fit. Covers cross as passcodes (an own picture is a file
on the machine that made the code) and are kept by the deck's id once the scanned
deck is first saved (`DeckBuilderState.importFrom`'s `onFirstSave`).

The code is drawn **black on white in both themes** — scanners read little else,
and those are Master UI's two fills — each module a whole number of pixels, with
the standard four-module quiet zone, **as large as the window allows** (the
dialog is sized to it), at level M up to version 25 and level L past it. The
dialog lists what the code carries ("the cards, the name, 5 groups, 1 hand goal
and notes").

On a phone or a tablet **Import is a menu** (the bar's, the Decks page's, the
phone's ⋯): **A .ydk or .ydkx file**, **Scan a QR code** — `ScanActivity`, above,
which asks for the camera on first use and turns with the phone — and **A picture
of a QR code**, for a screenshot someone sent (the phone cannot
scan its own screen), read by `QrReader` with both binarizers and the picture
inverted. Whatever is read goes through `DeckCodes.read` (core): Neue's own code;
a `ydke://` code anywhere in the text, a deck site's link included; or the lines
of a `.ydk`/`.ydkx`. A deck of no cards is no deck. It replaces the deck as a file
import does, under the code's name (else "Scanned deck"), groups and all, with
Undo on the toast. The desk's Import stays one click to the file dialog, and
`Ctrl O` is a file everywhere. `Platform.scanSources` is the seam: empty on the
desk, the picture always on Android, the camera where there is one. `DeckQrTest`
(core) and `QrTest` (neue: drawing the codes, a grid of parts included, and
reading them back from the pixels) hold it.

**Three clicks select a field's whole line** (1.0.14), so the search is cleared
for the next card by typing over it. `MuInput` counts presses on the way down
without consuming them — the field's own click and double-click are untouched —
and selects everything a frame after the third release, once the field has put
its own caret down. Every field in the kit has it, not only the search.

**The palette's list is the keys' or the hand's** (1.0.24, kai: "when I try to
scroll down with the scroll wheel its interrupted by the auto scroll"). It scrolled
to its highlighted row, and the pointer moves the highlight too: the wheel rolled a
row under the still pointer, that row took the highlight, and the list scrolled
back to it. Now only `↑` and `↓` bring the highlight into view, and once a wheel,
a touchpad, a finger or the scrollbar has moved the list it follows nothing until
the palette closes; opened again, it follows again (`FollowScroll`, in core). A new
query starts its list at the top, where its highlighted first row is.

The keyboard is `DeskShortcuts`, one table resolved in one place, rendered by
the help dialog (`F1`) and reachable by name from the palette (`Ctrl K`).
`DeskShortcutsTest` holds every action bound and no chord meaning two things at
once; `DeskKeysTest` holds every key in the table pressable. The pool keys
(`↑ ↓ Enter`, `Shift Enter` for the side) are live *while typing in the search
field* and nowhere else, so confirming a deck name never adds a card.

### 3a. Zen

In immersive mode, on the builder, doing nothing is a mode too
(`core/motion/Zen.kt`, `neue/zen/`):

- **Three seconds** idle: everything that is not a card fades — the rows, the
  rules, the search and its controls, the inspector's text, the scrollbars. The
  cards stay exactly where they are. Any movement brings it back.
- **Ten seconds**: this is zen. The pool and the inspector go too, and the deck
  comes to the middle of the window and grows into it (`ZenStage`: a transform
  about its own centre, never a re-fit, filling at most 72% of the width and 80%
  of the height so there is table round it). The cards **float in their blocks**
  — each lens group, or each section with no lens, drifting as one so it keeps
  its shape — and **flutter like scales**: a slow lean about the diagonal runs
  through each block corner to corner, one diagonal a moment behind the last
  (`ZenFloat.inBlock`). **A card let go snaps** (`ZenSnap`, 1.0.13): near its
  own slot it goes back in and rejoins its block; near the edge of another card
  (left, right, above, below, the slot empty) it snaps flush beside it and joins
  *that* card's block, one cell over, so cards can be grouped by hand; anywhere
  else it stays where it was put and keeps floating with its own block, so picking
  a card up never makes it jump. While a card is carried the family cursor stays
  on it (`holdOnPress`): the hovers of the cards it passed over used to snap the
  frame between them, which was the jitter. Everything leans, dreamily, toward the pointer. (Until
  1.0.12 every card drifted its own way, which kai found chaotic.)
- **The cards are the garden.** kai scrapped the sand garden that stood behind
  the deck for six releases (spirals, rakes, a sun) — "they don't look good" —
  for the cards themselves: each floats over **its own shadow** (`ZenShadow`,
  drawn by `ZenShadows.kt` with Skia's analytic rect blur; the higher a card,
  the further, softer and fainter its shadow), and **the pointer picks them up
  and puts them down anywhere** (`ZenArrangement`). A card put down lands on top
  of what it is put on; one being carried floats higher and casts further. The
  arrangement is only a picture: the deck's order never changes. Waking draws
  every card home, and **every zen begins with the cards in their slots**
  (1.0.24, kai: "zen mode seems to remember card positions if they were moved
  before exiting zen mode. Have it reset every time we enter zen mode"):
  `ZenLayer.begin`, the moment the phase turns deep, forgets the last zen's
  arrangement and what was picked out. Until then the next zen put them back
  where they were left.
- **Only a key wakes it.** In deep zen the pointer is for arranging, so neither
  moving it nor clicking ends zen; any key does, and that key does nothing else.
- **Many cards at once** (1.0.14, kai: "drag boxes to select multiple cards, and
  hold shift to select multiple cards and be able to drag them as a group. I can
  also combine groups"). The grammar is `ZenGestures`, in core with a test on
  every row:

  | on | bare | with Shift |
  |---|---|---|
  | the table, dragged | a box: picks out every card it touches | adds them |
  | a card | picks out that card alone (again: none) | puts it in, or takes it out |
  | a card, twice | picks out its whole block | adds the block |
  | a card, dragged | carries everything picked out if it is, else it alone | adds it, carries them all |
  | the table | lets go of everything | nothing |

  Picked-out cards wear the selection's double ring, the cursor reads "Move 5",
  and a group is carried as one — every card up to the top of the pile in the
  order it already lay (`ZenArrangement.moveAll`) — and let go as one
  (`ZenSnap.snapAll`): home if every card is near its own slot; flush against a
  card outside it if one of its cards is near a free slot there *and* the whole
  group, moved that much, lands on nothing — joining that card's block, which is
  **how two groups combine**; otherwise where it was put, as **one block**, the
  block of the card it was carried by, so cards gathered from several groups float
  on together. Every card's cell is measured from the card it snapped against, so
  the scales keep running corner to corner across the new block. Waking forgets
  what was picked out.

  The box is drawn by the window's pointer watcher, not by the cards: a press is
  on the table when `ZenPick.at` finds no card where the cards are *drawn*
  (their rest slot, their offset, then `ZenStage` about the deck's centre), and
  that press is spent there, on the way down, so the pool and the inspector —
  faded out, not gone — never hear it. A section's pane rises with its highest
  card, so a group carried out of the main deck is drawn over the extra deck.
- **An empty deck has no zen** (1.0.14): with nothing to float, the builder
  stays awake.
- **Faded out is shielded** (1.0.15). In deep zen the pool and the inspector are
  still laid out, only transparent — and kai found them still answering the
  pointer: a hover filled the invisible inspector, a wheel scrolled the invisible
  pool. Each is now covered by a shield that takes every pointer event, and the
  deck is lifted above both, so a card floated over where they were is still a
  card under the pointer. The corner is 440 × 180 and **always** offers
  **Leave zen**, beside **Groups** and "Put the cards back" when they apply — it
  used to show nothing at all until a card had been moved, which read as broken.
  Once its buttons are out it is at least as wide as they are (`ZenCorner.reaches`,
  1.0.24): a row of four on a scaled display is wider than 440 px, and the corner
  let go of the pointer on its way to Leave zen.
- **Faded is not gone, all of it** (1.0.24, kai: "zen mode is accidentally trying
  to click on objects not in zen mode which is blocking me from clicking on cards.
  I can tell this because the tooltips are showing"). The shields covered the pool
  and the inspector, but not the rest of the chrome that only fades: the **Groups
  panel**, which stands beside the deck *after* it in the row, so above it for the
  pointer — and zen grows the deck into the middle of the window, over where the
  panel is, so every card there was under an invisible panel that took the press
  and showed its tooltips; the **lens row**, which rides with the deck to the
  middle (its tips, and an invisible Extra or Side switch that a click hid a deck
  with); the section names; and the hidden panes' handles. Now `zenQuiet` itself
  takes faded chrome off the page once zen is deep and the fade is done: still
  measured, so nothing moves, but not placed — neither drawn nor hit, so a hover,
  a tooltip or a press goes to the card under the pointer. It is placed again the
  moment zen ends, still transparent, and fades in from there. The shields and the
  deck's lift go on the moment the phase is deep (`ZenLayer.asleep`), not halfway
  through its fade. The studio proves it: `--groups=true --zen=deep
  --zen-groups=true --zen-drags=drag@k9>k9+0,0.25` carried nothing before and
  carries the card now.
- **The wheel opens and closes zen's gaps** (1.0.17, kai: "let the scroll wheel
  expand the gaps further and tighten, card sizes adjust automatically"). Up
  opens the Roles pieces if they are closed, and then widens them; down narrows
  them, from a third of a gap to five (`ZenLayer.gapScale`). Zen fits the deck
  *as it is drawn* to the window (`ZenLayer.stageRect`): the rest rectangle grown
  sideways by the widest section's gaps and downward by all of them, because each
  section's pieces open below the growth of the sections above it
  (`PiecePlacer.zenAbove`) — about the middle, the main deck opened into the
  extra. So the wider the gaps, the smaller the cards, and the whole stays in the
  middle of the window.
- **Auto zen is a switch on the bar** (1.0.16, `NeuePreferences.autoZen`, on by
  default): off, immersive mode never drifts into zen by itself, and `Z` is the
  only way in.
- **`Z` is zen, now** (1.0.15, kai: "instantly start zen mode with a one button
  hotkey"): immersive if it was not — a moment later, so the deck is laid out
  full screen before it is measured for the middle — and deep at once. Any key
  brings the builder back, as ever.
- **The groups, in zen** (1.0.15). **Groups** comes out in the bottom-right
  corner beside "Put the cards back" when the deck has groups; it breaks the deck
  into its Roles pieces — the same `GroupPieces` as the builder — over 900 ms,
  and each piece's outline glows, faintly and prismatically, in its group's
  colour: a soft band along every edge on the outline, its hue swung up to 22°
  either way by where it is and by the clock (`GroupMarkers.shimmer`, drawn by
  `zenGlow` beside the shadows, the one file allowed a blur). Pressed again, the
  pieces close back into the deck. It starts on when the builder had its groups
  on. The pieces are centred in the deck's own box whichever way they are, so
  the deck does not slide when they open, and with them out each card floats with
  its Roles group. A card carried away leaves its piece: the outline opens where
  it stood, and closes round the card wherever it is put. Picking and snapping in
  zen measure the pieces as they are drawn (`ZenLayer.homesNow`).
- **The groups' names, in zen** (1.0.24, kai: "let the user toggle the labels for
  the groups as well"). **Labels** stands beside Groups in the corner — always
  there when Groups is, so pressing Groups never slides it out from under the
  pointer, and live only while the pieces are out, since a closed deck has nothing
  for a name to stand on. On (the default, `NeuePreferences.zenLabels`, kept in the
  settings), each group's name is written once on its pieces as the builder writes
  it — the same tab, the same longest top edge (`drawGroupLabel`, `labelEdge`) —
  floating with the cards it stands on and fading with the glow. Two things are
  zen's own (`ZenLabels`, in core): a card carried out of its piece takes no name
  with it, so the name stands on the cards still there; and zen reserves no room
  for a tab, so it stands in whatever gap is over its edge — the gap between two
  pieces as wide as the wheel has made it, the paper between two sections, or the
  table over the top of the deck — and is not drawn where that is too short to read.
- **"Put the cards back"** comes out, faintly, when the pointer goes into the
  window's bottom-right corner (`ZenCorner`, 240 × 140) and something has been
  moved; it draws every card home over a slow beat and forgets the arrangement.

The shadow is **kai's exception to Master UI's "no shadows"**, and it is one
file: `MasterUiLawTest` refuses a blur or a mask filter anywhere else in
`neue/`. In Ink it is the exact inversion — a faint light under each card —
because a black shadow on black says nothing and dark is paper and ink swapped.
The studio photographs it with `--zen=deep`, and `--zen-drags` drives real
presses through the real handlers — `drag@0.05,0.1>k12` (a box from a point on
the table to the middle of main-deck card 12), `shift-click@k25`,
`drag@k11>k11+0,0.5`, `dbl@k5` — logging what was picked out and where the
carried cards settled, with a still mid-gesture and one after.

### 4a. Groups

Edited in the Groups panel beside the deck (§3), not in a drawer. Delete keeps
the cards and offers Undo. Before 1.0.9, deleting a group meant opening it and
finding a link in the lens strip, and kai could not tell it was possible; since
1.0.15 every control a group has is on its row.

### 4b. High-resolution art

YGOPRODeck's small renders are 268 px wide, and the inspector — and the deck, on
a large display — draws wider than that, which blurs the small print first.
`art/ArtLibrary.kt` downloads every card's 813 × 1185 original into
`<data>/card-art-hd` while the app is used: what is on screen first (the deck,
the card being read, the pool's results, anything drawn wider than 268 px), then
the rest of the pool. About 2 GB. Four at a time, under a dozen requests a
second (YGOPRODeck allows twenty and asks that images be kept, not re-fetched);
a file is written beside its name and moved into place, so quitting halfway
costs one picture. A card draws the original the moment it is on disk, over the
small render until it has decoded, so arriving never flashes. Settings has the
switch, the count, the size and the folder (§4h).

**Sharp from the start.** kai reported that on opening the app the deck's
thumbnails were blurry, and that going to Settings and back fixed them. Coil
decodes a picture to the size of its box *as measured when the request starts*
(`ConstraintsSizeResolver` resolves once) and never looks again. A card's box
at first composition is rarely its last: the window opens at its default size
and only then takes the saved bounds and Maximized, the interface scale arrives
with the settings, the deck re-fits as the panes and the wheel settle. So each
card kept the decode made for its first, smaller box, stretched to its final
one — blurry until Settings and back composed it afresh, at the size it had by
then. `NeueCard` now asks for its decode size itself, from `DecodeSize` (core,
tested): it follows the card's measured width **up only**, in 64 px steps, and
never past the source (268 px for the small render, 813 for the original). A
card that grows asks again; the sharper decode's placeholder is the softer one
(`placeholderMemoryCacheKey`), and an original already showing stays shown
while its sharper decode loads, so the change is a crossfade from soft to sharp,
never a flash of the hatch.

### 4c. The deck it opens with, and the deck's covers

**The builder opens a deck** (1.0.14): the one marked **Make default** in `01
Decks` ("Opens first" on its row, **Not default** to take it off), else the one
saved most recently — `StartingDeck.pick`, which treats a default since deleted as
no default. It opens only onto an empty builder, once the settings have been read,
so an import made while the library was opening wins. Deleting the deck on the
builder opens the next one the same way rather than leaving an empty builder,
and clears it from the default and the covers.

**Covers**: every row in the library shows up to three cards the person chose
(`DeckCovers`), flush like the deck's own mosaic, in three places whether or not
they are filled so every name starts on one line. A deck card's menu (hold it)
has **Put on the deck's cover** / **Take off the deck's cover** with the count;
a fourth lets go of the first rather than refusing. **Click the thumbnails** on a
library row (1.0.15) and a picker opens with every card in the deck once: a
click puts one on the cover or takes it off, numbered 1–3 in the order they will
stand, and **Clear** goes back to the most-played card. They are kept by the
passcode in the deck, so an alternate artwork the deck holds is the picture on
its cover; a cover that has left the deck is not drawn; none, and the row shows
the most-played main-deck card as it always did. An unsaved deck has no id to
hang covers on, and the entry says so ("Save the deck first").

### 4d. Alternate artworks

A card printed with more than one picture shows **Art 2 of 3** and a step either
way under its art in the inspector, and in the card viewer (1.0.14). The choice is
the card's everywhere — pool, deck, library, screenshot, the card in the air —
because `NeueCard` applies it itself (`LocalArts`, `NeuePreferences.arts`). It
is only ever a picture: `CardArt.show` hands the drawing the same card under the
other passcode, with that picture's addresses — YGOPRODeck serves every artwork
at the same path under its own passcode, and the pool stores only the first — so
the originals library and the foil's name masks keep the two apart, and the
deck, the rules and the banlist never see it.

**Reaching the switch** (1.0.16). kai reported that alternate arts did not
work. They did — the picture changed — but the only switch was under the
inspector's art, and the inspector follows the pointer: moving from a card to its
arrows crossed other cards or left the card, and the inspector let go of it
first. So the switch is now on the card itself: hover a card printed with more
than one picture, in the pool or the deck, and a `2/9` chip comes out in its
top-right corner — a click is the next artwork, a right-click the one before, and
the chip spends its own press, so it never also selects, adds or drags the card
(`NeueCard(artChip = true)`, not in deep zen, where a press carries the card).
`A` and `Shift A` step the card being read, and the card's menu has **Next
artwork · 2 of 9**. The studio drives it: `--art=46986414 --mouse=left@…` on the
chip, and the inspector reads `Art 3 of 9`.

**The chip turned by itself** under a resting pointer (reported against 1.0.16):
its handler took any pointer event for a press, and hover sends a stream of them.
It now waits for a real press (1.0.18).

**Some alternate artworks are not in the pool, and cannot be** (kai, 1.0.18:
Nibiru, the Primal Being; Lady Labrynth of the Silver Castle). YGOPRODeck lists
an artwork only when it has a passcode of its own; reprints that share one —
most newer alternates — are a single image there, so no switch can find them.
The remedy is **+ Your own** under the inspector's art: pick a picture from disk
and it is copied into `<data>/custom-art/<passcode>/` and becomes one more
choice on the chip, the keys and the switch, drawn like any other (`CustomArt`;
a choice is a passcode, or −k for the card's k-th own picture), with **Remove**
beside it while it is showing.

**Your own art, cropped into the card** (kai, 1.0.34: "let the user upload an
image, drag a file from an explorer, or just paste a copied image, then … crop
the area that they wish to be just the card art"). + Your own opens
`ArtCropDialog`: a picture arrives by **Choose a picture**, by **dropping** one
on the dialog (or straight on the inspector's card, which opens the dialog with
it), or by **pasting** (`Ctrl V`/`Cmd V`, or Paste). A box of the art window's
shape lies over it — moved by a drag, resized by a corner, the wheel or a
pinch, never leaving the picture (`ArtCrop`, core) — and the card beside it
shows the crop in place. **Replace art** draws the card's own original (the
printing chosen, when the pool knows it; `ArtLibrary.ensure`) with the crop in
its art box, and keeps *that* as one of the card's own pictures — so the foil,
the name stamped in it, the viewer and the screenshot read it as they read any
picture, and nothing else had to learn about crops. **Whole card** keeps the
picture as it was, as before 1.0.34.

The art box is `ArtWindow` (core, beside `ArtFrame`), measured off the same
renders: the bevel's inside on a standard card; on a pendulum, down to the top
of the pendulum-effect box (row 736 of 1185), whose bevel hides the seam — the
art runs on behind that translucent box, but its text is printed on it; on a
link card, the square with its corners cut where the arrow sockets reach in
(`x + y < 41` from the frame's outer corner), so the sockets stay printed over
the new art. The edge arrows sit on the bevel and need nothing. A skill card
has no art box and offers only Whole card. The seams are `platform/Pictures.kt`:
decoding and PNG encoding (Skia on the desk, `BitmapFactory` on Android),
the clipboard (AWT's image and file-list flavours; the `ClipboardManager`'s
URI), and a drop (the AWT transferable; the drag's `ClipData`, read once the
activity has asked `requestDragAndDropPermissions`). `ArtBakeTest` holds the
box to its pixels.

### 4e. The screenshot

`Ctrl Shift S`, or Screenshot in the header: main, extra and side as they stand,
with none of the window. Above them, the deck's name, its counts, its format, the
date, and the newest TCG set already out on that date (`cardsets.php`,
`CardSetReleases.latest` — the feed lists announced sets too). Drawn by
`shot/DeckShot.kt` offscreen from the originals, in the theme you are in.

**Two shapes** (1.0.23), chosen under Settings → Building → Screenshot. kai asked
for the picture to be rethought for where it is looked at — "most of time it will
be shown on mobile phones, meaning the 'at a glance' factor is really important" —
and chose these two of four explored (the others: copies stacked with a count, and
groups as packed blocks):

- **Picture**, the default: the deck as the builder draws it, every copy, ten
  across. With the groups on it is in their pieces, outlined in each group's
  colour, with each name on its tab (`drawPieces`, the builder's own code) — the
  screenshot had fallen behind the builder, still drawing 1.0.15's thin gaps, a
  code on every card and a legend. 1600 dp wide at 2×.
- **List**: a decklist, 1080 dp wide at 3× so its words read on a phone (the
  names come out near 8 pt there; the picture's header, at 1600, near 11). Each
  card once, its art window squared beside its count and name, under its group's
  bar, the main deck in two columns balanced by whole groups
  (`DeckList`, in core). Without groups the rows are split into Monsters, Spells
  and Traps (kai). Cards in no group, when there are groups, are **Other**.

Both are laid out by arithmetic into a plan before anything is drawn
(`ShotDesigns`), so the image is exactly as tall as its contents.
`tools/shoot.sh --deckshot=true` renders the chosen shape headlessly, and
`--deckshot=all` every shape.

### 4g. Finding cards (1.0.19)

**The panes hide themselves.** kai: "let me toggle to hide the card database
sidebar and inspector sidebar within the sidebar themselves rather than the top
bar so it's more intuitive". The pool's hide button is at the end of its search
row, and the inspector's is a 20 px button in its top-right corner, inside the
24 px margin so it costs the picture nothing. A hidden pane leaves a 36 px strip
where it stood with the one button that brings it back; the pool's strip starts
past the rail's gutter, so reaching for it never brings the rail out instead.
**The whole strip brings it back** (1.0.24, kai: "reopen them by clicking anywhere
on the drawer rather than just the button. This should only be while it's
hidden"): it is one target, shaded under the pointer and framed by the family
cursor with `Show`, and a tap on it does the same on the tablet. The button stays
in it. The gutter is padding outside the strip, so it still clicks nothing, and
hiding is still the panes' own buttons.
`Ctrl B` and `Ctrl J` still work. The two buttons on the window's bar are gone.

**Filters, as the deck builders people use have them** (kai: "refer to …
duelingbook, master duel, and neuron"). One `FilterPanel` for the pool and the
pop-out, facets that cannot apply hidden (pick Spell and the monster rows go):
order (best match, name, ATK, DEF, level, reversible); card; monster type
(Normal, Effect, Ritual, Fusion, Synchro, Xyz, Pendulum, Link); ability (Tuner,
Flip, Gemini, Spirit, Toon, Union); attribute; type; spell and trap property;
level or rank 0–13; link rating; pendulum scale; link arrows as the card's own
compass; ATK and DEF between two numbers; effect; archetype found by typing; and
the banlist. `CardFilter` carries the new facets as trailing fields with empty
defaults, so the tablet's filter does not change. Within a facet any value
passes, except the link arrows and the effects, where every one chosen is
required: that is how every deck builder reads arrows, and "searches *and*
special summons" is the question an effect filter is asked. A monster type and a
spell property are one choice (`races` or `properties`), because YGOPRODeck keeps
both in one field. `f` opens the pool's panel. It scrolls inside the top of the
pool, so the results keep the rest.

**Effects are read off the text** (`EffectKinds`, in core with a test on real
card texts). Search, Special Summon, Draw, Negate, Destroy, Banish, Send to GY,
Return to hand, Set, Gain LP, Burn, Token, Protection, Hand trap and Floodgate:
Neuron's and Master Duel's categories. Konami files them by hand and YGOPRODeck
does not carry them, so each is one pattern over the printed phrasing, erring
toward a player's reading: "cannot be destroyed" is protection, not destruction,
and "must be Special Summoned" is a condition, not a summon.

**The search pop-out** (`SearchStudio`; kai: "an advanced card search pop out that
focuses the screen on searching with a dedicated layout. it should incorporate
elements from the inspector as well"). `Ctrl Shift F`, the search icon beside the
pool's field, or the palette. The window is given over to it in three columns:
every filter, always open; the results, a thousand deep rather than the pool's
150; and the card being read, large — the inspector's picture, artwork switch,
heading, text and copies, plus what it is filed under and what it does, each a
click that filters by it, and the actions. It keeps its own query and filter,
starting from the pool's, so it does not disturb the pool. A double-click or
Enter adds, and a right-click is the card's whole menu. Esc or **Done** closes it.

**Lists of cards kept for consideration** (kai: "a custom list of cards in the
database for consideration and toggle the custom list … intuitive and easy to
get cards into"). They are stored as `NeuePreferences.cardLists`: a name and
passcodes, with no copies, sections or limits. Under the pool's search, **All
cards** and a tag for each list with its count; a click shows the list in the
pool, where every search and filter still applies (it is the filter's `onlyIds`,
kept in step by `PoolSource`). Right-click a list to add cards, show it or delete
it. **+ List** makes one. Getting a card onto a list:

- `L` on the card being read, or the highlighted result, puts it on the active
  list or takes it off. With no list yet, `L` makes "Considering".
- Every card's menu, in the pool, the deck and the pop-out, has **Put on …** for
  each list.
- **Add cards** opens the search pop-out adding to that list. There a
  double-click puts a card on or takes it off, a ✓ marks what is on it, and
  **On the list** shows only those.

`Shift L` switches the pool between the list and every card. The studio photographs
all of it: `--filters=true --effect=SEARCH`, `--list=12`, `--nopool=true
--noinspector=true`, `--studio=deck|list`.

### 4f. The library

`01 Decks` (1.0.18, kai: "let me duplicate decks, export them, a tagging system
based on the card type in the deck, and … type the name of a card and it will
filter decks by those with the card in it"):

- **Duplicate** on a row saves a copy as "<name> copy", with its groups and its
  covers.
- **Export** on a row is the builder's Export menu for that deck, without opening
  it: `.ydk`, `.ydkx` with its groups, a YDKe code or a text list.
- **Tags** are read off the cards (`DeckTags.of`, in core with a test): up to two
  archetypes with six or more cards, the summoning mechanics the extra deck (or
  the rituals) lean on, and a lean — Monster-, Spell- or Trap-heavy. They stand
  on each row, and every tag in the library is a strip under the header; a click
  on either keeps the decks carrying it.
- **The search** matches a deck's name, the name of any card in it, or a tag
  (`DeckSearch.match`). A row found by its cards says which: "With Ash Blossom &
  Joyous Spring".

### 4h. Ready for offline

kai asked for two things together: to see when the app is downloading pictures
or updating the card pool, and to be able to check, before a flight, that
everything the builder needs is on the computer — and to bring it up to date
there and then, with a bar, "so they know when they're ready to use offline".

**What is being fetched is in the bar.** While the card pool updates or the
art library sweeps, the title bar carries `CARD POOL 42%` or `CARD ART 42%` over
a 3 px track (§6's progress), before the update pill; its tip has the whole
sentence ("6,210 of 14,590, about 12 min left", or why it is waiting), and a
click opens Settings. The pool's update wins the place while it runs, since it
changes what you search. Narrow, the words give way and the figure and bar stay.
The rail's own line for the art stood inside the pool's block, so it showed only
while the pool synced; it stands on its own now. `Offline.readout` (core) decides
what the bar says.

**Settings → 03 Offline** has three rows, and the card pool moved there from
Building:

- **Card pool.** *Check for updates* asks YGOPRODeck's `checkDBVer.php` — a
  few dozen bytes — which version its database is at, against the version this
  pool was fetched at (`CardRepository.check`, `PoolFreshness`): "Up to date ·
  14,590 cards · checked 12:04", "An update is available · 147.21, this pool is
  147.20", or "Couldn't reach YGOPRODeck · 14,590 cards on this device". *Update
  now* fetches the whole pool with a bar that follows it through its four steps
  (`PoolProgress`): asking the version, downloading (about 21 MB, sent with no
  length, so measured against the last download's size), reading, and writing
  it into the database a few hundred cards at a time.
- **High-resolution art**: the switch, a bar, the count, the size and how long
  is left at the pace pictures have been arriving, and *Download all*, which
  turns the library on if it was off and, if it was waiting out the network,
  tries again at once. The sweep's pace is unchanged — four at a time, under a
  dozen a second. Cards YGOPRODeck has no original for are *settled*, not
  pending (`ArtCount.unavailable`), or the bar would sit at 99% for ever.
- **Ready for offline**: `Ready` once the pool is known current (checked or
  updated this session) and every card's original is here or known not to
  exist; otherwise what is left, in order — "update the card pool, then wait for
  7,590 more pictures". `Offline.readiness` (core, tested).

**The pool's version is written down** beside the pool: one JSON row under the
preferences table's `card.pool` key (`PoolRecord`: the database version and the
download's size) — a new row, not a new schema, so no migration. A pool fetched
before there was a row has no version; it counts as current if it was fetched
more than a day after YGOPRODeck's `last_update` (which carries no time zone,
hence the day), and adopts the version when it does. The weekly refresh on
start is unchanged.

The studio drives both: `--check=true` asks for real and prints the answer;
`--update=downloading|saving` presses Update now and takes the picture at that
step (`--frames=2`, so it has not finished).

---

### 4h½. Stragglers join their group (1.0.33)

kai: "sometimes cards in a group overhang past a row and aren't grouped together
in the last row (this happens most when decks go over 40 cards)… have the straggler
cards join their group." The last row of a section is short, so it is the one row
whose cards can stand in other columns without moving another card:
`StragglerSlide` (core) places its runs — each group's cards, kept together and in
deck order — along it where the most of them stand under a card of their own group
in the row above, so they touch it and `GroupPieces` makes them one piece. Nothing
changes when nothing gains; among equal placements, the nearest to the row as read.
`PieceLayout.column` says where each card stands (its index's column everywhere
else), neighbours are found by cell (`at`), and `PiecePlacer` slides the stragglers
with the gaps (`crack`), so turning Groups off takes them home. The screenshot uses
the same columns. A group that wraps from the end of a full row to the start of the
next still breaks there: only a last row has room.

### 4i. Format: webs of decks (1.0.33)

kai: "format web (expected decks at a tournament to play against)… the user can
web multiple YDKX decks together as one exportable group… sharable among people so
they can use it to learn and simulate tournament environments. New section called
Format… When in a web, the user can easily change decks in the web in the deck
builder." Designed with kai on a mockup first (the Format page, the builder's
switcher, the siding editor, a phone, the PDF guide); kai's picks: **one `.ydkw`
text file**, **a web's decks live in their web**, **the guide is a PDF**, **any
deck sides against any other**. Built in three releases: this one (the web, its
file, the page, the switcher), then the siding editor, then the guide.

- **The model is `DeckWeb`** (`core/web/`), not "Format": `Format` is the TCG/OCG
  banlist everywhere. A web is a name, notes and ordered `WebEntry`s — a deck id,
  whether it is **yours** (★, first in its lists and the decks you side as) and its
  **share** of the field in percent, when known. `WebLibrary` holds every web as one
  JSON document in the preferences table (`neue.webs`) — a row, not a migration,
  so the schema stays 3. A deck belongs to one web (`put` takes it out of any other).
- **A web's decks are ordinary saved decks**, so the builder edits them untouched,
  groups and all. The Decks page leaves them to their web (`hidden`). **Add from
  library** copies a deck in (the library keeps its own); **Copy to my library**
  copies one out; **Remove** deletes it (a web's deck is nowhere else) behind a
  confirm, as does deleting a web with its decks.
- **`.ydkw`** (`WebCodec`): `#ydkw 1`, a `#web {json}` header (name, notes, the
  decks' ids, stars and shares), then a `#deck <id> <name>` block per deck — each
  block a complete `.ydkx`, so any tool opens a deck cut out of it. No line inside a
  block can begin `#deck `. Opening one makes a new web with new deck ids (twice is
  two webs); the file's ids are kept on read so the siding patterns that name
  another deck of the web can be pointed at the new ids. A `.ydkw` that arrives
  through a deck's Import, or from another app (the manifest takes `.ydkw` too), is
  sent to Format (`DeckBuilderState.onWebFile`).
- **The page** (`05`, `Ctrl 5`, `FormatPage`): the webs down the left (chips on a
  phone), the open web's name and notes written where they stand, **Import deck**
  (a file; on a phone or tablet also a deck's QR code, `CardActions.readCode`),
  **Add from library**, **Export .ydkw** (save, or share on Android) and **Delete
  web**; then **the field**, a tile per deck — covers, ★, share, counts — that opens
  it in the builder; its menu (hold, right-click or More) stars it, sets its share,
  moves it, copies it out or removes it.
- **The builder steps through the web** (`WebSwitch` in `BuilderBar`): a chip with
  the web's name and the deck's place (`Spring Regional · 3/6`) opening the list of
  its decks, and ‹ › — `Alt ←`/`Alt →`, `WEB_PREVIOUS`/`WEB_NEXT`. The deck on the
  builder is saved as it goes (`NeueHolders.openDeck`). A phone has both in ⋯.

## 5. Releases, updates and feedback — the permanent numbers

`release-neue.yml`, dispatched with a version, builds a `.msi` (Windows), two
`.dmg`s (macOS: `-arm64` for Apple silicon, `-x64` for Intel, from 1.0.21) and a
`.deb` (Linux) and publishes them as `neue-master-tool-<version>[-<arch>].<ext>`
under the tag **`neue-v<version>`**.

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
  /passive` and reopens the new build, and quits. On macOS it installs over
  itself (§5a); on Linux it hands the `.deb` to the package installer.
- The installers are **not code-signed**. Windows SmartScreen and macOS
  Gatekeeper will warn once; the release notes say how to get past it. The Mac
  has a switch for that (§5a), off until kai has an Apple Developer account.

### 5a. The Mac (1.0.21)

On kai's word the Mac is ported "fully and faithfully" and stays unsigned for
now. What that is:

- **Two builds.** A bundled JDK is native code, so the release has an Apple
  silicon leg (`macos-latest`) and an Intel one (`macos-15-intel`), and each
  checks `uname -m` so a runner label that changed processor fails the release
  rather than a Mac. `NeueReleaseTrack.installerFor` takes the `.dmg` for the
  machine's `os.arch` (`CpuArch`); a release with one unsuffixed `.dmg` — every
  one before 1.0.21 — still answers.
- **The menu bar is the table.** `core/input/DeskMenuBar.kt` lays out File,
  Edit, View and Help from `DeskAction`s, so the menus cannot disagree with the
  keyboard. An item carries its accelerator only when the chord has a modifier
  *and* the table lets it fire while typing: a Mac menu takes its key before any
  text field, and Undo belongs to the deck name while you type it. Chosen from
  the menu, an action obeys the table's scopes (`DeskMenuBar.enabled`), and one
  press heard twice — by the accelerator and by the window — runs once
  (`ActionEcho`). The application menu's About and Settings open Settings; Quit
  saves the settings document first. `neue/MacChrome.kt` is all of it.
- **`⌘` in every label.** `DeskShortcuts.kbd` writes the Mac's way on a Mac —
  `⌘K`, `⇧⌘F`, modifiers in Apple's order and run together, as every menu on the
  machine prints them. The kit's "never ⌘" is a rule for a web page, which cannot
  know the keyboard; the glyphs live in `:core`, so the law test still refuses
  one in `neue/`. Prose that names a chord reads it from the table too.
- **A title bar in the theme's light** (`apple.awt.windowAppearance`): dark
  over ink, light over paper.
- **An update installs itself.** The app writes a script (`MacInstall`, in core
  with a test), starts it and quits; the script waits for the process to go,
  mounts the image, `ditto`s the new bundle beside the old one, swaps them —
  putting the old one back if the swap fails half-way — clears the quarantine
  flag and reopens the app. Its log is `neue-update.log` in the temporary folder.
  A development run, or a bundle this user cannot write, falls back to opening
  the image for a drag.
- **The signing switch, off.** With five repository secrets — `MAC_CERT_P12`
  (a base64 Developer ID Application `.p12`), `MAC_CERT_PASSWORD`, `APPLE_ID`,
  `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD` (an app-specific password) — the Mac legs
  import the certificate into a throwaway keychain, set `MAC_SIGN_IDENTITY`, and
  the `macOS { signing; notarization }` block in `app/neue/build.gradle.kts`
  turns on with the hardened runtime's entitlements
  (`app/neue/macos/entitlements.plist`: a JVM's JIT and Skiko's unsigned
  libraries); the workflow then notarises and staples. Without them, nothing
  about the `.dmg` changes. It has never run: the first signed release is its
  test, and worth a re-publish if it fails.

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
`main` with the next patch, and confirm the tag and **all four** installers
are on the release before calling it shipped. A change that touches only
`neue/` is on the tablet too from v1.3.0, so a change the tablet would see
ships on both tracks: `release-neue.yml` for the desktop, `release.yml` for the
APK.
