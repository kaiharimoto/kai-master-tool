# Present (`06`): audit

Audited at `dcef34d9` (Neue 1.1.x). kai: "I think it's incompletely built and not fully functional."

**The short answer: he is right, and the biggest gap is recording.** The brief was deck profiles for YouTube:
"a slideshow presentation creator that's animated and interactive … record in app using a webcam." The
slideshow half exists and mostly works. The recording half was never built:

- no camera is ever opened;
- no take is ever recorded;
- no video is ever made.

`core/present/record` (`Take`, `TakeTimeline`, `Chapters`, `EncoderPick`) is tested scaffolding with no caller.
Today a creator who finishes a profile has two ways out:

- present it full screen and capture the screen with OBS;
- export still pictures.

During that, the audience sees an empty box with a heavy border where the camera should be (shots `play-*.png`).

The rest of the page breaks down like this:

- **Broken (13):** eleven bugs, two of which lose work, and the editor bar overflows on a phone and at 1280 px.
- **Missing (9):** features the help dialog and the docs promise but the code never got.
- **Incomplete (9):** half-wired parts.
- **Rough (10):** works, but poorly.

The tests are green but test only `core` (see *Tests* below).

**How this was checked.** Every file in `core/present/**` and `neue/present/**` was read, with the Present wiring in
`NeueApp`, `NeueKeys`, `Main.kt` and `MainActivity`. Then:

- **Shots.** 66 studio shots: desk 1920×1080 and 1280×800, a tablet 2560×1600 @2, a phone upright and lying down
  @2.625, paper and ink, every module, every theme, three camera places, play/overview/notes/frames.
  - Throwaway studio modes (never committed) reached the New dialog, the module dialogs, Build with Ai, the card
    picker, each props tab, the export overlay and the presenter console.
  - All shots and logs are in
    `/tmp/claude-0/-home-user-kai-master-tool/eb324e7d-cdf3-54a8-8b12-853425b5a859/scratchpad/present-audit/shots/`.
    Shot names below are relative to that folder.
- **Probes.** A throwaway commonTest proved two of the bugs (B2, B6).
- **Tests.** The Present tests were run.
- **Licence and size.** The recording dependency's licence and size were checked against Maven Central and the
  bytedeco build script.

---

## The creator's walk, start to finish

| Step | Works? | Where it breaks |
|---|---|---|
| Make a profile from a saved deck (New dialog) | yes | The builder's unsaved deck cannot be used, though Ai's `create` can use it. |
| Shape the story (deck steps, notes, modules) | mostly | Refresh can overwrite a siding slide with the wrong matchup (B2). Typed sizes jump (B1). "Slides from groups" silently replaces every deck slide (R3). |
| Style it | yes | Three places do the same job (Style ▾, Restyle, the Theme tab). Undo restyle can wipe later edits (B3). |
| Rehearse | yes | Timings are kept, but nothing uses them: there is no auto-advance. |
| Present on one screen | yes | Speaker notes (S) are drawn **on the slide**, so a screen recording captures them (I1). Their clock never ticks (B10). |
| Present on two screens | desktop only | The console's "Next" pane is blank whenever the next click is a build. |
| Show the webcam | **no** | The zone is a placeholder panel for ever (M1). |
| Record a video | **no** | Nothing records (M1). The workaround is the Green screen fill plus OBS, explained only at the bottom of the Theme tab. |
| Get images out | yes, roughly | The empty camera panel and the end card's dashed boxes are baked into every PDF page, picture and thumbnail (I2). Capture runs on a timer, not when the art has arrived. |
| Get chapters / a description | **no** | `Chapters` exists but nothing calls it. |
| Do it on a tablet or phone | partly | On the phone (forced landscape), Module, Style, Export and **Present** are off the edge of the bar (B11). On a 1280-dp tablet, Present's label is clipped. The screen sleeps mid-talk (B9). |

---

## Findings

Severity:

- **broken** — it does not work;
- **missing** — promised but absent;
- **incomplete** — half-built;
- **rough** — works, but poorly.

Paths are from `app/`. `neue/present` means `neue/src/sharedMain/kotlin/com/kaiharimoto/neue/present`, and
`core/present` means `core/src/commonMain/kotlin/com/kaiharimoto/mastertool/core/present`.

### Broken

**B1. Typing a width or height jumps to the wrong number.** `neue/present/PropsPanel.kt:369-375` (`NumberField`),
used at `:236-238`.
- **Repro.** Select any element, open the Item tab, select W and type `500`. The result is **1200**.
- **Why.** Each keystroke commits, and W/H are clamped to ≥ 12. `remember(value)` then resets the text, so the steps
  are: `5` → 12 → "12", then `0` → "120", then `0` → "1200".
  - Each keystroke is also its own Undo step.
  - The custom webcam box fields (`:697-704`) commit every partial number the same way.
- **Fix.** Keep the text local. Commit on Enter or on blur (or debounced), clamp only on commit, and coalesce as one
  Undo step.

**B2. Refresh can replace a siding slide with another matchup.** `neue/present/ModuleData.kt:129`:
`made.firstOrNull { it.title == slide.title } ?: made.firstOrNull()`.
- **Repro, proved by a probe.** Add Siding with two matchups. Retitle the "vs Yubel" slide on the Slide tab, then
  press Refresh. Its title element now reads **"vs Snake-Eye"**, and its cards and why are Snake-Eye's.
  - The same happens when a matchup is renamed or deleted on 03 Siding.
- **Fix.** Store the matchup's name (or id) in the slide's own `ModuleRef.params` and match on that.
  - When the matchup is gone, say so ("This matchup has no plan any more") instead of falling back to the first.
  - Add the case to `ModulesTest`.

**B3. Undo restyle throws away every edit made after the restyle.** `neue/present/Presentations.kt:178-185`
restores `before.slides` wholesale.
- `restyleBefore` lives until the next restyle, and Style ▾ keeps offering "Undo restyle"
  (`PresentEditor.kt:202`).
- **Repro.** Restyle, then add a slide and type notes for ten minutes, then choose Style ▾ → Undo restyle. The new
  slide and the notes are gone. Ordinary Undo can bring them back, but only if the person thinks to try it.
- **Fix.** Restore only look fields: `theme`, `themeOverride`, and per element fills, strokes, run colours and faces,
  plus slide backgrounds, matched by id. Or offer Undo restyle only while history's top is still the restyle. Clear
  `restyleBefore` once the person edits.

**B4. "Your name" changes nothing.** `Presentation.creator` is never drawn.
- `PresentEdits.newProfile` (`core/present/edit/PresentEdits.kt:76`) bakes "Deck profile · kai" into the title slide
  once. After that:
  - the Theme tab's **Your name** ("title and end slides", `PropsPanel.kt:712-714`) does nothing;
  - the New dialog's name ("for the title slide") cannot be corrected later.
- **Fix.** Either make the subtitle a token (`{creator}`) that the painter resolves, or rewrite untouched title and
  end slides on change. At the least, reword the hint.

**B5. "Big camera" draws two cameras.** `core/present/SlideLayouts.kt:105-109` puts a `CAMERA` element at 96,140
(1100×800). The slide's `camera` stays `DEFAULT`, so the corner zone is drawn too.
- **Shot.** `bigcam-play.png`: a big empty frame **and** the bottom-right frame.
- **Fix.** The layout should set `Slide.camera` to a new preset, `BIG` (zone = that box), rather than adding an
  element. See B6.

**B6. The Camera element does not move the camera.** It is More ▾ → Camera, "where the webcam stands on this slide"
(`PresentEditor.kt:257-259`).
- `CompiledShow.zone` (`core/present/play/PresentShow.kt:35`) reads only `presentation.webcam` and `slide.camera`.
  The element is just a second frame (`SlidePaint.kt:1083`).
- **Proved by a probe.**
  - With an element at 100,100, the zone stays at 1384,768 and the stage still avoids the corner.
  - With the webcam **off**, the element still draws a frame.
- **Fix.** Pick one model:
  - **(a) Recommended.** Delete the element type from the insert menu, and make "move the camera on this slide" the
    Slide tab's existing per-slide camera picker, plus a Custom box dragged on the canvas.
  - **(b)** Make the zone read a `CAMERA` element when one is on the slide.

**B7. The siding module shows the going-second "why" before its click.** `core/present/modules/Modules.kt:349`
(also the "No change" caption at `:335`): `caption(t.note…)` gets no animation, while the turn's label, tags, cards
and arrow build on click.
- **Shot.** `mod-siding2.png`: "Break the board, then out-grind." stands alone under an empty "Going second" band.
- **Fix.** Pass `anim(6)` to the caption, and add a test that every element of a clicked turn builds.

**B8. Charts in Master UI cannot tell their series apart.** The chart palette is
`@accent, @accent2, @accent3, @accent4` (`SlidePaint.kt:947`). In Master UI `accent == accent3 == #141414`, and in
Master UI Dark `accent == accent3` too (`core/present/Themes.kt:81,90`).
- **Shot.** `mod-ratios.png`: Labrynth engine and Breakers are the same black, and Handtraps and Rollback near the
  same grey. The legend repeats.
- **Fix.**
  - Give charts a palette of their own: four distinct steps of the ramp for Master UI, hatching as the fourth.
  - For Opening odds and Ratios, use the deck's group colours, which `GroupOdds.color` already carries. Group
    markers are one of kai's colour exceptions.

**B9. Android: the screen sleeps while presenting.** `androidApp/.../MainActivity.kt:336`: `keepOn` covers deep zen
and the showcase only.
- **Repro.** Present on the tablet and talk past the screen timeout. The screen dims and locks.
- **Fix.** Add `|| h.present.playing != null` (and recording, later).

**B10. The speaker-notes clock never ticks.** `neue/present/play/PresentStage.kt:345` reads `System.nanoTime()` in
composition, but nothing recomposes the panel: the frame clock sleeps once the slide settles. The clock in
`notes.png` stays at the time the slide arrived.
- **Fix.** Drive it from a one-second ticker, as `PresenterConsole` does (`PresenterConsole.kt:64`).

**B11. The editor bar overflows.** `neue/present/PresentEditor.kt:148-251` is one non-wrapping `Row` of about 16
controls.
- **Shots.**
  - `desk-1280.png` and `tablet-edit.png`: **Present** shrinks to a bare arrow at 1280 px and at 1280 dp.
  - `phone-land-edit.png`: on a phone (Present is forced landscape there), everything after **More** is off screen:
    Module, Style, Restyle, Build with Ai, Export, **Present**.
  - The props tabs lose **Theme** on the phone too.
  - On a phone the only ways to present are the library tile's Present, or F5 on a keyboard.
- **Fix.**
  - Order by importance and fold the rest into ⋯, as `PhoneBar` does. Present and Export never fold.
  - Make the tabs scroll or wrap.
  - Test at 360 dp, 1280 px and a phone lying down (CLAUDE.md: "New chrome must work at 360 dp wide").

**B12. Present loses its last edit on quit.**
- `NeueApp.kt:560-566` flushes the layout, prefs, Prep and Duel on dispose, but **not** `h.present`.
- `Presentations.save` waits 400 ms (`Presentations.kt:303-310`).
- `flush()` itself only launches a coroutine (`:313-317`) that the closing scope may never run.
- **Repro.** Type a note and press Ctrl Q within half a second. The note is gone.
- **Fix.** Add a blocking `flushNow()` (write on the calling thread), call it from both `onDispose` blocks and
  Android's `onStop`, and from `close()`.

**B13. The Builds tab's "Go" button is cut off.** `PropsPanel.kt:495`: three buttons in a non-wrapping `Row` inside
336 dp. Only "+" shows (`tab-builds.png`), so exit builds can hardly be found.
- **Fix.** Use a `FlowRow`.

### Missing

**M1. Recording: nothing of it exists.**
- **The core scaffolding has no caller.**
  - `core/present/record/Take.kt`: `TakeEvent`, `Take`, `TakeTimeline.stateAt`, `TakeCodec`.
  - `record/Chapters.kt`: `Chapters`, `EncoderPick`.
  - `grep` finds no use outside `TakeTest`.
- **The painter is ready but never fed.** `SlideView`/`PresentStage` take a `camera` composable slot
  (`PresentStage.kt:80`, `SlidePaint.kt:175` "the live picture once there is one (phase 3)"). No caller passes one
  (`PresentWiring.kt:286`, `PresenterConsole.kt:147`).
- **Stored but unused.** `WebcamZone.mirror` and `WebcamZone.device` (`core/present/stage/WebcamLayout.kt:76,80`) are
  read by nothing.
- **Unplanned in the code.** No camera permission or entitlement for the desk (macOS `infoPlist` has only
  `NSMicrophoneUsageDescription`), no takes folder, no Record key, no video export.
- **The docs disagree.** `NEUE.md §4o` still schedules recording for "1.0.72" and Android for "1.0.73". CLAUDE.md
  says 1.0.74 and 1.0.75. Those numbers went to other work.
- **Fix.** See the plan, track A.

**M2. Slide zoom.** The help dialog prints it (`core/input/PresentInput.kt:57` Ctrl wheel, `:84` pinch, `:85`
two-finger pan), but `SlideCanvas.kt` has no zoom or pan state. On a phone the slide is about 500 dp wide, and small
elements cannot be grabbed.

**M3. Picking several slides in the sorter.** Promised at `PresentInput.kt:61` ("Shift or Ctrl picks several").
`Presentations.slidesPicked` (`:125`) is only ever cleared, never set, so copy, duplicate, delete and move work on one
slide at a time.

**M4. A finger's menus and modes.**
- `PresentInput.kt` promises "Press and hold" for the element and canvas menus (`:81`, `:83`), plus a "Select
  several" switch (`:77`) and a "Keep shape" switch (`:87`).
- `SlideCanvas.kt:200` opens a menu only on `isSecondaryPressed`.
- **On a tablet, Cut, Copy, Paste and Duplicate are therefore unreachable without a keyboard.** The switches do not
  exist.
- `PresentInputTest` holds the two tables to each other, never to the canvas.

**M5. Dropping a picture on a slide.** Promised by the Item tab's help (`PropsPanel.kt:291`: "pasted (Ctrl V) or
dropped on the slide too"). The canvas has no `dragAndDropTarget`. The Ai panel has one (`ai/PictureViews.kt`) to
copy.

**M6. Naming a section.** "Start a section here" writes the literal word "Section" (`PresentEditor.kt:417-419`), and
no field renames it. Sections are what YouTube chapters would be named after (`Chapters.of` reads `s.section`).
- **Fix.** Add a Section field on the Slide tab, and rename in place in the sorter.

**M7. The Morph transition.** It is offered in every slide's list, but `PresentStage.kt:225` treats `MORPH` exactly
like `FADE`. Elements do not travel between slides. Only the deck glides, and it does that between any two deck
slides.
- **Fix.** Match elements by id or slot and tween their boxes and opacity (`StageTween` already does this for cards),
  or remove Morph from the list.

**M8. Cleaning up pictures.** The delete dialog says "Its pictures stay until no presentation uses them"
(`PresentPage.kt:106`), but nothing ever deletes from `<data>/present/media/`. The files are synced and backed up for
ever, including logos picked in a module dialog that was then cancelled.
- **Fix.** Sweep unreferenced media on load (newer than a grace period, and never during sync).

**M9. Seeing a slide as a picture, for Ai.** `present_view` answers in words only (`ai/AiPresent.kt:286-300`). With a
model that sees (`Vision`), Ai could judge colour, balance and crowding from the slide itself. The export path
already draws a slide to a bitmap.
- **Fix.** Attach a 960×540 PNG when the connection sees.

Smaller gaps:

- The library has no rename, search, export or import of a presentation file.
- Rehearsed timings never auto-advance.
- `PickTarget.FOCUS` (`CardPicker.kt:77`) is dead code.

### Incomplete

**I1. Presenter view only works with two screens, and the one-screen fallback leaks into the video.**
- With one screen, **S** draws the notes over the slide itself (`PresentStage.kt:246`, `notes.png`). A creator
  capturing that screen with OBS records their notes.
- The two-screen console (`PresenterConsole.kt`) works (`console.png`), with these gaps:
  - "Next" is blank whenever the next click builds the same slide ("The next click builds this slide.").
  - There is no pen, white screen, slide jump or notes for the next slide.
  - It is desktop only. Android could use the system's `Presentation` API.
- **Fix, for creators.** Add **"Slides in a window"**: the audience view as an ordinary resizable window on the same
  screen. OBS captures that window (Window Capture) while the console stays private. In-app recording (track A)
  removes the need, but the window is a small change and helps today.

**I2. Export draws the placeholders, and captures on a timer.** `neue/present/SlideExport.kt`.
- **Placeholders.**
  - Every PDF page, picture and **YouTube thumbnail** includes the empty camera panel with its border
    (`play-*.png` show what is drawn).
  - The end card's dashed "Next video" and "Subscribe" boxes are drawn too (`play-end.png`). Those are YouTube
    end-screen guides, not content.
  - **Fix.** Add an `exporting`/`final` flag to `SlideView` that hides the camera zone (or keeps the green screen
    on request) and drops guide-only elements. Mark the end card's boxes `guide = true`.
- **Timing.** `delay(if (at == 0) 1500 else 700)` (`:84`), then capture, regardless of whether art and pictures have
  decoded. A slow art download exports blank cards.
  - **Fix.** Capture when `ArtLibrary` and `present.bitmap` report nothing pending, with a time limit.
- **The thumbnail** is the slide scaled to 1280×720, with no layout of its own (big face, big title).
- **The PDF** is JPEG pages: no text, no links, larger than it needs to be. `core/pdf` already writes real text for
  the siding guide.
- **The overlay** shows the full-size 1920×1080 slide behind the progress box, overflowing the window.
- **Export is reachable only from inside the editor.**

**I3. Module gaps.** `core/present/modules/Modules.kt`, `neue/present/ModuleDialog.kt`.
- **The Title field is ignored by Siding** (`Modules.kt:151-152`, `md-siding.png`). **Decklist** puts the title on
  the slide list but draws no title (`:274-277`, `mod-decklist-play.png`).
- **Combo picks** can be added but not removed or reordered (`ModuleDialog.kt:159-164`). A wrong click means
  Cancel and start again.
- **Matchups / Odds / Ratios with no data** still make a slide. It says "Log practice games…" or shows an empty
  donut. The dialog allows it (`canMake` returns true). Ai's `add_module` refuses it instead.
- **Refresh of Decklist and Get the deck** reads the presentation's snapshot. Refresh never notices that the saved
  deck changed unless the Deck tab's "Refresh from the saved deck" is pressed first. Say so in the Slide tab's
  Refresh help, or refresh the snapshot first.
- **The performers, tech and combo picker** shows 52-dp cards with no names, at 60 % opacity (`md-performers.png`).
  Strong/weak/out by clicking a card three times is hard to discover.

**I4. Before the camera is live, the default zone fill is "Panel".** (`WebcamLayout.kt:78`; Theme tab, "Before the
camera is live".) It never is live, so every presentation shows the audience a blank box with a 6-px border. Green
screen, the one useful setting for OBS, is two clicks deep with its explanation last on the tab.
- **Fix, until track A lands.** Default new presentations to Clear while presenting and exporting. Keep Panel only in
  the editor.

**I5. Inserted elements land under the camera.** The More ▾ defaults use canvas boxes that ignore the zone:
- **Cards in a row**: 160,260,1600×620 (`CardPicker.kt:87`; `insert-row.png`, the 4th card's name under the camera).
- **Chart**: 360,260,1200×600 (`PresentEditor.kt:265`).
- **Fix.** Place inserts inside `show.stage(i)`, or insert them stage-anchored.

**I6. The deck focus is set by clicking cards on the slide only while the Deck tab is open.** (`SlideCanvas.kt:314-317`.)
With any other tab the same click starts a box selection. The mode cannot be seen. The Deck tab's help line is the
only hint.

**I7. The Theme tab's two face pickers have no labels** (`PropsPanel.kt:676-678`). Which is the heading face and
which the body face?

**I8. Phone upright: the editor has no canvas.** The sorter (132 dp) plus the props panel (260 dp) is wider than
411 dp (`PresentEditor.kt:113,121`; `phone-edit.png` shows no slide at all). The APK forces landscape on Present
(`MainActivity.kt:327-329`), so this shows only if the forcing fails or the page is reached another way. The editor
still has no layout of its own for a phone.

**I9. "Slides from groups" and the style switch are destructive and silent.**
- `PresentEdits.stepsFromGroups` (`core/.../PresentEdits.kt:121-124`) replaces **every** deck slide, with its notes,
  builds and added elements, and gives no warning.
- Switching style on the Deck tab does not move the whole-deck step: Build-up ends on it, the others open with it.

### Rough

- **R1. Delete with nothing selected deletes the whole slide.** Backspace too (`EditorActions.kt:94-99`). One
  accidental keypress after a click on the canvas removes a slide.
  - **Fix.** Delete the slide only when the sorter has focus.
- **R2. The sorter's drag fights scrolling under a finger.** `detectDragGestures` on every row
  (`PresentEditor.kt:364-379`) means a finger can scroll the list only from the gaps. The drag has no auto-scroll
  past the edge either.
- **R3. Cards are blurry when shown large.** Until the 2 GB art library has downloaded, the large cards in Slides and
  Spotlight are drawn from the small render (`play-s3-slides.png`). For video, Present should ask the art library to
  fetch the profiled deck's originals first.
- **R4. The cursor shows in screen recordings.** The family cursor (`PresentStage.kt:103`) stays on the slide during
  a show. Hide it after two seconds without movement, as video players do.
- **R5. The editor's guides vanish in the Ink app.** The safe-margin guide is drawn in the app's ink at 12 %
  (`SlideCanvas.kt:134-135`). A light slide in the Ink app does not show it (`edit-ink.png`). Draw guides in a colour
  that contrasts with the slide.
- **R6. A finger's "back" area is the left third.** The table says "the left half" (`PresentStage.kt:164` vs
  `PresentInput.kt:94`).
- **R7. Inserted Big number, Table and Chart arrive with sample content.** "87 %", "Opponent A 60 %" and "Win rate 60,
  45, 52" look like real data. On a stats slide that misleads, and the slide goes out unchanged if the person forgets
  it. Use visibly empty placeholders, or offer the app's data (the modules already have it).
- **R8. The New dialog cannot use the builder's open deck when it is unsaved** ("Save a deck first"), though Ai's
  `create` can (`AiPresent.kt:205-209`).
- **R9. Edits in the props panel are one Undo step per keystroke** wherever `coalesce` is missing (Number, Table,
  Chart and QR use a key; X, Y, W and H do not).
- **R10. Sample data shows through on real decks.** Odds tiles wrap their caption onto three lines
  ("100 % going second · 23 cards", `mod-odds.png`). The decklist slide outlines every group in one highlight colour
  instead of its own (`mod-decklist-play.png`).

---

## Ease of use: a newcomer's eye

The editor follows Master UI and reads cleanly in both themes (`edit-desk.png`, `edit-ink.png`). The slides
themselves look good in every theme (`theme-*.png`). Where it falls short is in **telling the person what comes
next**, and in a promise it never keeps: the camera.

1. **"Camera" is a promise with no follow-through.** **Clear fix (until track A).**
   - A grey box labelled "Camera" sits on every slide.
   - Nothing says the app will not show the webcam, or that the way to record is OBS plus Green screen. That advice
     is the last line of the Theme tab.
   - Put one line under the zone in the editor: "Your camera goes here when you record. Recording is coming; for now
     choose Green screen and record with OBS."
2. **After "Make it", nothing says what to do next.** **kai's choice.** Nine slides appear. The natural path is:
   check each deck step's focus and note, write speaker notes, rehearse, then record or export.
   - **(a) Recommended.** A thin checklist strip over the notes: "Notes on 0 of 9 slides · Rehearsed: no ·
     Recorded: no". Each item jumps to the place it names.
   - **(b)** A first-run tip sequence.
3. **Present is a menu, and at 1280 px its label is gone.** **Clear fix.** Make Present a split button: the main part
   presents from this slide, the arrow opens the menu. It never folds away (B11).
4. **Three doors to one room for the look.** **kai's choice.** The doors are Style ▾ in the bar, Restyle (also in the
   bar), and the Theme tab. Recommended: keep Style ▾, with Restyle with Ai inside it, and the Theme tab for
   fine-tuning. Drop the separate Restyle button.
5. **Two kinds of note with almost the same name.** **Clear fix.** "The note" on the Deck tab is **drawn on the
   slide**. "Speaker notes" are private. The presenter even falls back from one to the other. Rename them "On the
   slide" and "Only you see this".
6. **Card focus is a hidden mode** (I6). **Clear fix.** When a deck slide is selected and no element is, a click on
   a card should always toggle focus. A hint line over the canvas should say so.
7. **The Item tab is empty until something is selected, and Builds hides Go** (B13).
8. **The module dialogs ask for a Title that some modules ignore** (I3). The performers picker never names the cards.
9. **The library tile's Present opens the editor behind the show.** That is fine, but there is no way to rename a
   presentation without opening it.
10. **Delete removes slides too easily** (R1).

---

## Tests

All green:

| Suite | Tests |
|---|---|
| `PresentTest` | 22 |
| `PresentAiTest` | 10 |
| `ModulesTest` | 4 |
| `TakeTest` | 5 |
| `PresentInputTest` | 3 |
| `SlidePaintTest` (neue) | 1 |
| `MasterUiLawTest` (neue) | 9 |

The run was `./gradlew :core:jvmTest --tests '*present*' --tests '*Present*' --tests '*Take*' --tests '*Modules*'`
plus `:neue:jvmTest` on SlidePaint, MasterUiLaw and SharedPortability.

Gaps in coverage:

- **Modules.** No test refreshes a module slide after a retitle or a renamed matchup (B2). None checks that every
  element of a clicked turn builds (B7).
- **Charts.** No test that a chart's series are distinguishable in each theme (B8).
- **Holder and props panel.** Nothing exercises `Presentations` (undo restyle, flush, delete) or the props panel's
  number entry. Both are in `neue`, where only the painter is tested.
- **Input.** `PresentInputTest` checks the mouse and finger tables against each other, not that the canvas
  implements them (M2–M4).
- **Recording.** `TakeTest`/`Chapters` test the scaffolding, but nothing produces a `Take` (M1).

---

## Recording: route, licence and size

**Desktop: JavaCV + FFmpeg (LGPL build). Recommended, as CLAUDE.md planned.**

Licences:

- `org.bytedeco:javacv` and `javacpp` are Apache 2.0 or GPLv2 with Classpath exception. The app uses them under
  Apache 2.0.
- `org.bytedeco:ffmpeg` without a classifier is built with `--enable-version3` and **without** `--enable-gpl`
  (bytedeco `ffmpeg/cppbuild.sh`). It is therefore **LGPL v3**.
  - It includes libopenh264, libvpx, libaom, SVT-AV1, lame, opus, and **avdevice**. avdevice is the webcam and
    microphone input: dshow, avfoundation and v4l2.
  - The `-gpl` classifier adds x264 and x265 and must never be used: this app is MIT.

Size per installer, latest `8.1.2-1.5.14` on Maven Central. Only the target platform's native jar ships:

| Platform jar | Size |
|---|---|
| macOS arm64 | 20.6 MB |
| macOS x64 | 24.0 MB |
| Linux x64 | 26.9 MB |
| Windows x64 | 30.4 MB |
| javacv + javacpp | 1.0 MB |

So each installer grows by about **21–31 MB**.

- `javacv`'s POM pulls in OpenCV, OpenBLAS, librealsense and others. **Exclude every transitive** except `javacpp`
  and `ffmpeg`, or the app grows by about 1 GB.

LGPL obligations:

- Ship the licence and notices.
- Keep the libraries replaceable. They are separate shared objects that JavaCPP extracts at run time, which
  satisfies the relinking clause. Document how to swap them.

Encoder choice:

- Prefer the operating system's own H.264 encoders, which `EncoderPick` already lists first: `h264_mf` (Windows) and
  `h264_videotoolbox` (macOS).
- The bundled **OpenH264 is compiled from source, so Cisco's patent licence (which covers only Cisco's binaries)
  does not cover it.** Recommend **VP9 (libvpx, BSD) in MP4 or WebM** as the fallback ahead of `libopenh264`/`mpeg4`.
  YouTube takes VP9.
- Update `EncoderPick.order` and `TakeTest` accordingly.

**Permissive alternatives, considered and not recommended:**

- **JCodec** (BSD-2, pure Java, about 1 MB): H.264 baseline only, far slower than real time at 1080p, and no capture.
- **sarxos webcam-capture** (MIT): unmaintained since 2017, and its BridJ native layer fails on Apple silicon.
- **Each OS's own media APIs** (Media Foundation, AVFoundation, V4L2 + GStreamer): three native integrations to
  maintain, against one.

**Android: no FFmpeg.** Use CameraX (Apache 2.0) for the camera, and `MediaCodec`/`MediaMuxer` (the platform's
own, with the device's hardware H.264 licence) for encoding. The `android-arm64` FFmpeg jar alone is 23 MB.

**The design the core already chose: capture live, render offline.**
- **Live.** While presenting, record only:
  - the presenter's events (`TakeEvent`);
  - the camera, to `camera.mkv` (MJPEG or H.264 from the device, untouched);
  - the microphone, to `audio.wav`, through the Java Sound `Mic` the voice feature already has.
- **Offline.** Draw the video frame by frame from `TakeTimeline.stateAt` using the same `SlideView`, headless, in an
  `ImageComposeScene` as the studio does. Put the camera frame into the zone (shape, mirror). Encode and mux.
- **What this buys.**
  - Recording costs nothing extra while the person talks.
  - The output is pixel-identical to the editor.
  - A take can be re-rendered after a typo is fixed in the frozen copy.
  - Chapters come out free.

---

## Plan

The work splits into three tracks for three agents in worktrees. File ownership is chosen so the tracks rarely meet;
where they must, the order is stated.

### Track A: recording (one agent; the largest)

| Step | What | Files (new unless noted) |
|---|---|---|
| A0 | **Decide and document.** Takes live in `<data>/present/takes/<take>/`. **Device-only: never synced or backed up** (videos are large), so `NeueSyncLocal` and `BackupCenter` must skip `present/takes/`. A `TakeLibrary` holder, lazy in `NeueHolders`. Add a `StartStep` ("Record with your webcam") that asks for the camera and microphone. | `docs/present/RECORDING.md`, `neue/present/record/TakeLibrary.kt` |
| A1 | **The camera on the desk.** `platform/Camera` (expect/actual): device list, open, frames as `ImageBitmap` at 30 fps, close. jvmMain uses JavaCV `FFmpegFrameGrabber` (`dshow` / `avfoundation` / `v4l2`), with the Gradle dependency excluding every transitive but `javacpp` and `ffmpeg`. macOS: `NSCameraUsageDescription` plus the `com.apple.security.device.camera` entitlement (`neue/build.gradle.kts`, `macos/entitlements.plist`). The Theme tab's webcam section gets a **device picker and Mirror switch** (`WebcamZone.device`/`mirror`, already stored). | `neue/platform/Camera.kt` (+ `.jvm`, `.android` stub), `neue/build.gradle.kts` |
| A2 | **Live preview.** Pass a `camera` composable to `PresentStage` and the editor's `SlideView` when the camera is on. "Before the camera is live" now means something. | `PresentWiring.kt`, `play/PresentStage.kt` (after track C's C4), `SlideCanvas.kt` (coordinate with B) |
| A3 | **Recording a take.** Present ▾ → **Record** (and a `DeskAction.PRESENT_RECORD`, `R`, in `DeskShortcuts` plus `PresentInput`). A 3-2-1 count-in. `Playing` emits `TakeEvent`s (GO, OVERVIEW, BLANK, LASER, INK, MARK on `M`); pause and resume stop the take clock. Camera frames go to `FFmpegFrameRecorder` (MJPEG in MKV) and the mic to WAV, with the camera-to-clock offset stored. The frozen `presentation.json` is written at start. A red dot plus a timer shows in the console only. | `neue/present/record/Recorder.kt`, `Presentations.kt` (`Playing`), `core/input/DeskShortcuts.kt` |
| A4 | **Rendering.** `TakeRenderer` (jvmMain): an `ImageComposeScene` at 1920×1080 replays `stateAt(t)` per frame, draws `SlideView` with the camera frame at that time, encodes with `EncoderPick` (h264_mf / videotoolbox → **libvpx-vp9** → openh264 → mpeg4), and muxes AAC or Opus. Off the main thread, with progress, cancel, and a finished note with Open / Show in folder. Shares the "draw a slide offscreen and wait for its art" helper from C2. | `neue/src/jvmMain/.../present/record/TakeRenderer.kt` |
| A5 | **Takes UI.** A Takes panel per presentation: play back the take's render, re-render, delete, copy **YouTube chapters** (`Chapters.text`) and a description (deck name, ydke code, the creator's lines). Export a **thumbnail from a take's frame**. | `neue/present/record/TakesPanel.kt` |
| A6 | **Android.** `Camera.android` via CameraX. The recorder uses `MediaRecorder` for camera + mic. The renderer draws frames through `GraphicsLayer.toImageBitmap` into a `MediaCodec` input surface, muxed by `MediaMuxer`. `AiWorkService`-style foreground service while rendering. Keep the screen on (B9). | `neue/src/androidMain/...`, `androidApp` manifest (`CAMERA` is already declared for the scanner) |
| A7 | **Tests.** `TakeTimeline`/`Chapters` with real event streams; a 3-second headless render in jvmTest (skipped without FFmpeg natives); `OldDataTest` gets the take shape. | core and neue tests |

Order: A0 → A1 → A2 → A3 → A4 → A5 on the desktop, as one or two releases (record, then render and takes). Then A6
is its own APK release.

### Track B: the editor (one agent)

Owns `PresentEditor.kt`, `SlideCanvas.kt`, `PropsPanel.kt`, `EditorActions.kt`, `Presentations.kt`, `PresentPage.kt`,
`CardPicker.kt` and `MainActivity.kt`.

1. **Data and correctness:**
   - B1 (number fields);
   - B3 (look-only Undo restyle; `Presentations.undoRestyle`);
   - B12 (blocking flush on quit and on `onStop`);
   - B9 (keep the screen on);
   - R1 (Delete only in the sorter);
   - R9 (coalescing).
2. **The bar at every width:** B11 (a folding bar, Present as a split button, scrolling tabs) and B13 (`FlowRow`).
   Shoot at 360 dp, 1280 px and a phone lying down.
3. **Camera semantics:** B5 and B6 (`Slide.camera = BIG`/Custom, the element retired), I5 (inserts inside the stage),
   I4 (the zone fill default).
4. **Promised gestures:** M2 (zoom and pan), M3 (multi-pick in the sorter), M4 (press-and-hold menus, Select
   several, Keep shape, the sorter's drag handle so a finger can scroll), M5 (drop a picture), M6 (section names).
   `PresentInputTest` gains a check that each binding's action has a handler.
5. **Words and the newcomer:** B4 (creator token), I6 (focus clicks), I7 (face labels), I9 (confirm "Slides from
   groups"), R7 (empty placeholders), R8 (the builder's unsaved deck), and ease-of-use items 2, 3, 5, 6 and 10. The
   checklist strip (item 2) is kai's choice.

### Track C: modules, export, presenting, Ai (one agent)

Owns `core/present/modules/*`, `ModuleData.kt`, `ModuleDialog.kt`, `SlideExport.kt`, `play/*`, `paint/*`,
`Restyle.kt`, `BuildWithAi.kt` and `ai/AiPresent.kt`.

1. **Modules:** B2 (refresh by matchup), B7 (the caption's build), I3 (titles, combo editing, no-data guards, the
   picker's names, the Refresh wording), R10. `ModulesTest` gets cases for all of them.
2. **Export:** I2 (a `final` drawing flag hiding the camera zone and guides; capture when art is ready; a thumbnail
   layout; real-text PDF pages via `core/pdf` where possible). **Extract the "draw slide N offscreen and wait for its
   art" helper**, which track A's renderer reuses. Land it first and tell track A.
3. **Charts:** B8 (a chart palette per theme, group colours for odds and ratios, a test of distinct series per
   theme).
4. **Presenting:** B10 (the notes clock), R4 (hide the idle cursor), R6 (the finger's left half), M7 (a real morph,
   or drop it), I1 (**Slides in a window** for OBS, and the console's next-build preview, pen and jump list).
   *Hand `PresentStage.kt` to track A after this step* (A2 and A3 edit it).
5. **Ai:** M9 (`present_view` with a picture for vision models). Teach the `deck-profile` skill that the camera
   zone is live once A2 lands, and that recording exists once A5 lands.
6. **Housekeeping:** M8 (media sweep), library rename/search/export, `PickTarget.FOCUS` removed. Update
   `NEUE.md §4o` (its recording dates are wrong) and CLAUDE.md's Present bullet.

### Order and releases

| Stage | Track B | Track C | Track A |
|---|---|---|---|
| 1 | Steps 1–2 | Steps 1–3 | A0, A1 |
| 2 | Steps 3–4 | Steps 4–6 | A2, A3 (after C4 lands) |
| 3 | Step 5 | — | A4, A5 (using C2's helper) |
| 4 | — | — | A6 (APK) |

Releases follow CLAUDE.md's *Ship Every Change*:

- **Stage 1** fixes ship as the next `neue-v*` patch, and on the `v*` APK track because B9, B11 and B13 touch the
  tablet and phone.
- **Recording** is its own desktop release: no stored-data change except the new device-only `takes/` folder, to be
  noted in the release notes.
- **Android recording** follows on the APK track.

Track C's export, presenting and module fixes need nothing from recording, and recording's renderer is
straightforward once C2's offscreen helper exists. If only two agents are available, merge B and C (B's step 4 is
the largest piece) and keep A separate.
