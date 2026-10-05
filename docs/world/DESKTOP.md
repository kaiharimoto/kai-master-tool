# Ai World as a desktop (1.1.x)

**Goal:** Ai World stops being six panes of equal weight and becomes a small computer kai can sit at: a desktop with
icons, a taskbar, windows that open one at a time, a browser with tabs for everything Ai found, apps Ai builds that
open as their own windows, and Ai itself, small, moving about the desktop to the thing it is about to use.

kai, on trying 1.0.97's World: "the design, UX wasn't [great]. I didn't know where to look at, and the visual hierarchy
was very weak … we humans struggle to focus on many things at the same time … how about a simulation of a computer
desktop; a true virtual environment with an OS. every window would be an app on the taskbar that the user can choose to
open. the Ai can create interactive apps that pop up as individual apps, or show things to the user in a browser (the
browser being a chrome copy that has tabs instead of the current boards layout which is harder to navigate). smart icon
designs, and perhaps a small Ai icon jumping around the desktop opening things and working like a little avatar".

Then, on Ai's knowledge: "when it comes to knowledge of the deck or anything yugioh related the persistent memory can
take up a lot as I don't want there to be a cap to the knowledge". The desktop gets a **Library** that shows all of it,
and the World's limits are restated as limits on code, never on knowledge (§11).

**Done when:**
- a fresh world opens on a calm, empty desktop, and every window on it is one the person opened or one Ai is working in;
- one window is in front and reads as in front at a glance, in paper and in ink;
- Ai can make an app that opens in its own window, keeps its state, and survives a restart, a sync and a backup;
- Ai's avatar walks to what it uses, sleeps when Ai is idle, and no frame is asked for while nothing moves;
- every pane of 1.0.97 has a home (§3), and the boards canvas is deleted;
- the Library opens tens of megabytes of guides, notes, reports and evidence and searches them without a stall;
- the mouse, the finger and the keys each reach everything (§9), and a phone at 360 dp has one app at a time.

**What it builds on.** `core/world` (the model, `WorldApi`, the instruments, `ShowSpec` and its kinds), `JsRuntime`
(Rhino 1.7.15, interpreted and shut in), the `Worlds` holder and its tools (`AiWorld`), `WorldPaint` (the boards'
painters, the World's one file allowed colour), the avatar (`AvatarRig`, `AiAvatar`, `AiMark`, `MoodTracker`,
`AvatarPlay`), and Ai's memory (`AiMemory`, the guide and its book, reports, the evidence ledger).

**What it deletes.** The six-pane page (`DeskWorld`, `PhoneWorld`, `WorldHead`), the boards canvas (`BoardsPane`, its
zoom and Fit, `Worlds.moveBoard`, `WorldCanvas` placing), the "Ai is here" label, and the pane keys' meaning (§9).
Nothing stored is deleted: a board's `x`, `y`, `w`, `h` stay in `world.json`, read and written as they are.

Mockups: `docs/world/desktop.html` (paper and ink, desk and phone: a fresh desktop, Ai working, the browser, an app Ai
made, the Library, the icon sheet). The icon geometry in §7 is the mockup's, number for number.

---

## 1. The picture

```
┌ Neue's bar (48) ─ NEUE MASTER TOOL / 08 WORLD / lab openings ▾ ─────────────────────────── (face) ⛶ ┐
│                                                                                                       │
│  [Files]       MADE BY AI   ┌■ Editor · openings.js ─────────────────── – □ ✕ ┐                     │
│  [Editor]      [HO Hand     │  1  // How often does the deck open a starter? │  ← the window in front: │
│  [Terminal]        odds]    │  2  var deck = ygo.deck();                     │    ink title bar, ink   │
│  [Browser]                  │  …                            ▌ ◖••◗           │    edge, a paper        │
│  [Thoughts]                 └────────────────────────────────────────────────┘    keep-out round it    │
│  [Instruments]                  ┌ Terminal ───────── (receded: paper title, content at 45 %) ┐         │
│  [Library]                      │ $ js openings.js …                                          │         │
│                                 └──────────────────────────────────────────────────────────────┘        │
│                                                                                                       │
├ taskbar (44) ─────────────────────────────────────────────────────────────────────────────────────────┤
│ ⊞ │ Files Editor▀ Terminal Browser Thoughts │ HO │      ■ openings.js 3.2 s  Stop │ ✉ 2 │ 07:48 │ ◖••◗ Writing openings.js │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**Hierarchy, in order of loudness:**
1. **The window in front.** Its title bar is ink with paper words (inversion is Master UI's emphasis, law 4), its edge
   is 1 dp of ink, and a 3 dp band of paper — the *keep-out* — runs round it outside the edge, so no window behind ever
   touches its border. No shadow: the keep-out is paper, flat, the colour of the page.
2. **Ai.** The avatar is the only thing that moves by itself, and it is where Ai is working. Nothing else says so.
3. **Windows behind.** Paper title bars, ink-45 titles, ink-25 edges. While Ai works with Follow on, they *recede*:
   their content drawn at 45 % (§6.2).
4. **The taskbar.** A hairline over it, icons in ink-70, the open ones underlined, the front one inverted.
5. **The desktop.** Paper. Icons in a column at the left; Ai's apps under a micro-caps rule, *Made by Ai*.

**Nothing opens itself.** A fresh desktop has no windows (§2.1). A window is open because the person opened it, or
because Ai is working in it now; when Ai's turn ends, the windows it opened and the person never touched are put away,
except where the answer is (§6.4).

**No new colour.** The desktop, its windows, icons, taskbar and every widget of Ai's apps are paper and ink. Colour
stays where it is today: card art, the foil, a chart's series and a web's groups (`WorldPaint.kt`), and the avatar's
own five colours (`AiAvatar.kt`). No file is added to the law test's colour list.

## 2. The desktop and the taskbar

### 2.1 The desktop

The page below Neue's bar is the desktop: paper, edge to edge, the taskbar along its foot. Neue's bar keeps the
world's name as a picker (`lab openings ▾`: the worlds, newest first, and New world), the way the builder keeps the
deck's name there. 1.0.97's 56 dp head row is gone; Run, Stop and Follow live where they act (the Editor, the
taskbar's run status, the Ai cell).

- **Icons**: a column of 88 × 80 dp tiles from the top-left, 16 dp in: the seven built-in apps in their numbered order
  (§3). Ai's apps stand in a column of their own beside it, under a micro-caps heading *Made by Ai*, newest last, so
  the two kinds never share a column; further columns start when one is full.
  The person may drag an icon to another cell of the grid (snapped, never free); the order is kept per world in
  `desk.json`.
- **A tile** is the 32 dp icon over its name (12 px, two lines at most, centred). Selected: the tile inverted. An app Ai
  made that the person has not opened yet wears a micro-caps `NEW` tag in ink under its name until it is opened.
- **The plate.** With no window open, the work area (right of the icons) shows one block, centred, in the kit's
  empty-state voice — it is the desktop's wallpaper and disappears under the first window:
  - a fresh world: "A computer for Ai's experiments." / "Ask Ai a question it can answer by running something. You will
    see it open each app it uses." The ask line (Enter asks, as a World conversation) and three suggestions:
    *Study my deck's openings* · *Build me a hand-odds calculator* · *Map how my cards search each other*;
  - a world with work in it: its name in h2, then one mono line of what is in it (`16 pages · 6 files · 1 app`) and two
    links, *Open the browser* and *What Ai did last* (Thoughts at the last turn).
- **A right-click** on the desktop: Show desktop (minimise all), Put windows away (close all but pinned-and-kept), Tidy
  icons, New world, World settings.

### 2.2 The taskbar

44 dp on the desk (48 dp to a finger), paper, a 1 dp ink-12 rule on top. Left to right:

| Part | What it is |
|---|---|
| **Launcher** `⊞` | Opens the launcher (§2.4). |
| **Apps** | The pinned apps, then every open app that is not pinned, in the order opened. A cell is 44 dp square, the icon at 20 dp. *Open*: a 2 × 12 dp ink bar under the icon. *In front*: the cell inverted. *Minimised*: the bar in ink-45. *Ai is in it, behind*: the avatar stands on the cell (§5). Tips name the app, its window's context and its `Alt` key. |
| *(space)* | |
| **Run status** | Only while something runs: the breathing square, the run's name and its seconds in mono (`openings.js 3.2 s`), and **Stop**. A click on the name opens the Terminal. |
| **Notices** `✉ 2` | The tray: its count of unread notices in mono, nothing when there are none (§6.3). |
| **Clock** | The time, `07:48`, mono ink-45. While Ai works, the turn's own time beside it: `07:48 · 0:42`. |
| **Ai** | The avatar's home (28 dp) and one line of status: *Asleep*, *Writing openings.js*, *Running openings.js*, *Reading the guide*, *Waiting on you*, *Done*. A click opens Thoughts; a right-click (a held finger): Follow, Skip ahead, Stop Ai, Show Ai on the desktop. While Ai works, two words stand beside the line: **Follow** (a `WordToggle`) and **Skip** (§5.5). |

**One face on screen.** On the World page Neue's bar does not show `AiBadge`: the taskbar's Ai cell is Ai's place there,
so the face is never in two corners at once. Every other page keeps the badge.

**Pinned by default:** Files, Editor, Terminal, Browser, Thoughts — the five 1.0.97 had on screen at once, in the
order of their `Alt` keys. Instruments and Library live on the desktop and in the launcher; any app can be pinned or
unpinned from its icon's or cell's menu. Pins are this device's (`WorldPrefs.pinned`).

### 2.3 Windows

**Chrome** (`neue/world/desk/WindowFrame.kt`):
- A **title bar** of 32 dp (40 dp to a finger): the app's icon at 16 dp, its name, then a `·` and its context in the
  same line (`Editor · openings.js`, `Browser · Opens a starter`, `Hand odds · v3`), then the window's own tools, then
  `–` `□` `✕` as text glyphs in 32 dp cells. In front: ink fill, paper words. Behind: paper, ink-45 words, an ink-12
  rule under it.
- **The body**: paper. The edge: 1 dp, ink in front and ink-25 behind. The keep-out: 3 dp of paper outside the front
  window's edge.
- An **Ai app** wears its generated tile (§7.3) in place of an icon and the words `by Ai` in micro-caps after its name;
  nothing an app returns can change its title bar (§8.6).

**What a window does** (`core/world/desk/Desk.kt`, a pure reducer over `DeskOp`, tested):
- **Open**: at its app's comfort size, in the next cascade slot of the work area (the desktop right of the icon
  columns), 32 dp down and right of the last, clamped inside. Comfort sizes are fractions of the work area, so a 1366 ×
  768 laptop and a 4K display both get a sensible window: Browser and Library 0.64 × 0.86, Editor 0.52 × 0.72, Terminal
  0.48 × 0.38, Files 0.26 × 0.62, Thoughts 0.32 × 0.86, Instruments 0.44 × 0.66, an Ai app its manifest's size in dp,
  clamped to 320–1200 × 240–900 and to the work area. A window opens with a 120 ms fade (`MuMotion.FAST`); it never
  zooms or slides.
- **Focus**: a press anywhere in a window brings it to the front (nothing spent, as 1.0.97's panes did). One window is
  in front, or none (the desktop clicked). A minimised window is never in front.
- **Move**: drag the title bar. The window follows the hand with no easing and no inertia.
- **Snap**: let go with the pointer within 8 dp of the work area's left or right edge — half; top edge — maximised;
  a corner — a quarter. While held there, a dashed 1 dp ink outline shows where it will land (`SnapZones`, the
  Spotlight's dash). Snapped windows remember the frame they came from, and a drag off restores it.
- **Resize**: a 6 dp band round the edge and 12 dp corners (desk only; a finger uses the title bar's `□` and snaps).
  At least 320 × 200.
- **Maximise**: `□` or a double-click on the title bar; fills the work area, the icons covered; again restores.
- **Minimise**: `–`; the window fades out to its taskbar cell (the cell stays, its bar in ink-45).
- **Close**: `✕`. Closing a window Ai is working in tells Ai and is respected (§6.1).

At most **12 windows** open; opening a 13th closes the least recently used window that is not Ai's, not kept and not
in front, and says so in a notice.

### 2.4 The launcher

`⊞`, `Alt 0`, or a swipe up from the phone's dock. A panel of paper over the desktop's lower left (the whole screen on a
phone), the overlay behind it at 90 % paper:
- the search line (focused): app names, world names, page titles, files — Enter opens the first;
- **Apps** in a grid: the seven built-ins, then *Made by Ai* (with each app's one-line description under its name);
- **Worlds**: the five newest, then *All worlds…* and *New world* (`Alt N`);
- the foot: *Ask Ai…* (a World conversation), *World settings* (Python on this computer, typing speed, the avatar).

Esc or a press outside closes it.

### 2.5 The phone (one app at a time)

A phone (`FormFactor`, `LocalPhone`) has no windows. One app fills the page; the rest is chrome measured to 360 dp:
- **Neue's `PhoneBar`** on top carries the app: its icon and name in place of `08 World`, and in its ⋯: the world
  picker, New world, Follow, Skip ahead, Close this app. The live face already in the `PhoneBar` is the avatar's home
  (§5.6).
- **The dock**, 48 dp, between the app and Neue's `TabBar`: **Apps** (the launcher, full screen: a 4-column grid of
  72 dp tiles, built-ins then *Made by Ai*), **Switch** with the count of open apps, and **status** (a run's seconds and
  Stop, or the notices' count, or nothing). A sideways swipe along the dock goes to the previous or next app.
- **The switcher**: full screen, every open app as a row 72 dp tall — its icon, name and a one-line summary (Editor:
  `openings.js · line 41`; Browser: `3 tabs · Opens a starter`; Terminal: its last line), most recent first. A tap shows
  it; a swipe left, or its ✕, closes it. Summaries are text, never live thumbnails: nothing is drawn that is not seen.
- **Follow** shows the app Ai moves to, under the same quiet rule as the desk's raise (§6.1).
- A tablet in landscape is the desk, with the finger's grammar (§9.3) and 40 dp title bars.

## 3. The built-in apps

Every 1.0.97 pane has a home. Numbered as the `Alt` keys reach them:

| `Alt` | App | Pinned | Was | What it is |
|---|---|---|---|---|
| 1 | **Files** | yes | Files | The world's tree: `files/` and `apps/` as two roots. Double-click: code and text open in the Editor; `.md`, `.png`, `.csv` and `.json` open as pages in the Browser (`world://files/…`). Right-click: Open, Open as page, Run, Rename, Delete (confirmed). New file. A file dropped onto it is written into `files/` (§11). |
| 2 | **Editor** | yes | Editor | One file at a time (the file's name in the title is a menu of the world's files). Its toolbar: **Run** (`Ctrl Enter`), the language, and the state — *Typing… Skip · Take over* while Ai types, *Edited · Save* after the person's change, *Saved*. Up to 1 MB edits in place; a larger file opens read-only as lines drawn lazily, with *Open as page*. |
| 3 | **Terminal** | yes | Terminal | Each run's command and its output streaming, as today, and now a command line of its own (§3.1). Clear. |
| 4 | **Browser** | yes | Boards | Every board is a page, one per tab (§4). The canvas is deleted. |
| 5 | **Thoughts** | yes | Thoughts + Activity | Ai's conversation in this world, as one stream: the person's asks, Ai's reasoning (open while it streams, folded once filed, as the panel's), its words, and its actions as compact rows — *✎ Wrote openings.js (19 lines)*, *▶ Ran openings.js · 412 ms*, *◧ Pinned “Opens a starter”*, *⊞ Made Hand odds* — each a link to what it made. All · Words · Actions. The composer at its foot with `FaceStrip`; this is where the person talks to Ai on the World page (§6.5). |
| 6 | **Instruments** | no | (none) | The engineered studies as an app: their list on the left (name and question), a form on the right made from the instrument's declared arguments (`InstrumentForm`: a deck, conditions as lines, trials, seed…), **Run**. Its lines go to the Terminal and its boards open as pages; the last runs are listed under the form with *Open pages*. |
| 7 | **Library** | no | (none) | Everything Ai knows, browsable and searchable (§10). |

### 3.1 The Terminal's command line

A terminal you cannot type in reads as a picture of one. A prompt line under the output (`›`, mono), parsed in core
(`TerminalCommand`, tested):

| Command | Does |
|---|---|
| `run openings.js` | Runs a file (the person's run, `by: you`). |
| `js 1 - ygo.atLeast(40, 3, 5, 1)` | Evaluates JavaScript in the world and prints the value. |
| `py …` | The same in Python, where Python is allowed on this computer. |
| `tool openings {"deck":"open"}` | Runs an instrument. |
| `open world://boards/b3-9f2k` | Opens an address (a page, a file, an app). |
| `ls` · `cat notes/plan.md` | The tree, a file. |
| `clear` · `help` | |

`↑` recalls, Tab completes file names and instruments. Ai never types here; its runs print here, as today.

## 4. The Browser

**Addresses** (`core/world/desk/WorldAddress.kt`, parse and format, tested; every path through `WorldPaths.safe`):

| Address | Page |
|---|---|
| `world://home` | The new-tab page: every page of the world, newest first, grouped by the run or turn that made it, with a filter line; then files and apps. `world://home?q=hand` filters. |
| `world://boards/<id>` | A board as a page. |
| `world://files/<path>` | A file as a page: markdown drawn, a picture shown, CSV as a table, JSON indented, code read-only. |
| `world://runs/<t>` | One run: its command, output, value, error and the pages it pinned (`t` is its event's time in base 36). |
| `world://instruments/<name>` | What an instrument answers, its arguments and an example. |
| `world://apps/<slug>` | Opens that app's own window (an app is never a tab). |

Anything else is a page that says there is no such page and offers the nearest title.

**Chrome** (`neue/world/browser/`):
- **Tabs under the title bar**: square cells 36 dp tall, 176–220 dp wide (`TabStrip`), each its number (`Ctrl 1`–`9`),
  the page's kind glyph at 16 dp (§7.2), the art of the card the page is about (`PageLead`), its title shortened to the
  words that tell it apart (`TabTitles`, never under 12 characters), and `✕` on hover and the selected one (always, to
  a finger). The selected tab is paper and open to the toolbar below (an ink edge on three sides); the others are paper
  with ink-70 words and ink-12 separators, a darker rule where another run's pages begin (`Tab.group`). A tab Ai opened
  or changed while it was not selected wears a 6 dp ink square on its glyph's corner until it is; the person's own tabs
  never do. `+` opens `world://home`. Past the room, whole tabs fill it and a count at the strip's end (`+6 ▾`) opens
  every tab as pictures — searched, closed, dragged to reorder. A pointer resting on a tab shows its page small with its
  whole title and source. A middle-click closes. The rules are `docs/world/READABILITY.md` §4, §8.
- **The toolbar**: `←` `→` `↻`, the address as an underline field (mono, `world://` in ink-45), **Keep** (a kept tab is
  never put away, §6.4), and ⋯ (Copy address, Open the source file, Show the run, Open in a new tab, Take the page down
  — confirmed).
- **The page**: a column at most 1,120 dp wide, centred. Its head: the kind in micro-caps, the title in h2, Ai's note,
  and where it came from in mono with links — `from openings.js · ran 07:48 · 412 ms`. Its body: `BoardBody`, the
  boards' painters at the page's width (`WorldPaint.kt` unchanged in its colours).
- **On a phone** the tab strip folds into a count button (`3`) beside the address, opening the tabs as a list.

**Tabs and Ai** (`BrowserTabs`, core, tested):
- `world_show` pins the board as today **and opens it in a tab**. A board pinned again under its id updates its tab in
  place — no new tab.
- The tab is selected when the Browser is raised for Ai (§6.1); otherwise it opens beside the selected one, unselected,
  with its mark.
- Each tab keeps its own back and forward (50 deep). At most 20 tabs: the oldest tab Ai opened that is not kept and
  not selected closes first (its page stays at `world://home`).
- Tabs, their histories and which is selected are kept in `desk.json`.

## 5. Ai on the desktop

### 5.1 What it is

The avatar is Ai's face from §4k′ — never a new character. At 28 dp (24 on a phone) it is the rig's glyph: the head and
eyes, no net, no marks (`AvatarRig.GLYPH_BELOW_DP`). It wears the face `MoodTracker` already chooses, so it agrees with
the taskbar's line and the panel. It replaces the "Ai is here" label everywhere.

**kai's waiver.** Master UI lets nothing move by itself; kai waived that for named things only — the cards' lean, zen's
floating deck and its shadows, the dice. kai's request ("a small Ai icon jumping around the desktop opening things") is
taken as the same waiver for this one avatar, in one file: `neue/world/desk/DeskAvatar.kt`. The law test names it
(§12.3). Windows still move only under the person's hand.

### 5.2 Where it goes

`core/world/desk/AvatarPilot.kt` turns what Ai does into a queue of **targets**; the desktop reports where each target is
on screen (`AvatarTargets.report(windowId, anchor, rect)`, called from layout, never composition).

| Ai does | The avatar's way |
|---|---|
| A turn starts | Wakes at home (*Waking*), then to its first target. |
| `world_write` to a file, the Editor closed | Home → the Editor's desktop icon (or its taskbar cell, whichever is in view and nearer) → a press (the head squashes, 120 ms) → the window opens → its title bar → **the caret**, which it then rides as the code types in: 8 dp to its right, on its line, so it never covers a word already written. |
| `world_write`, the Editor open | Its title bar → the caret. |
| `world_run`, `world_tool` | The Terminal's title bar → the line printing; it waits there (*Working*) while the run lasts. |
| `world_show` | The Browser's tab strip → the new tab, which it opens → the page's head. |
| `world_app make` | The Editor (it types the app's code) → the new icon on the desktop → a press → the app's window. |
| `world_app press` | The widget it presses (a button, a slider), as reported by the app's window. |
| Reading (`world_read`, the Library) | The window it reads, its eyes on the line. |
| A question to the person | Thoughts' composer (*Waiting on you*). |
| The turn ends | *Done* for 2.5 s where it is, then home, then asleep. |

A window Ai works in that is covered or minimised: the avatar goes to its taskbar cell and stands on it.

### 5.3 How it moves (`core/world/desk/AvatarPath.kt`, tested)

- **A hop, eased.** From where it is to the target along a quadratic arc whose middle is lifted by
  `clamp(0.18 · distance, 12 dp, 64 dp)` — a hop, never a slide along the floor, never a straight teleport-length dash.
  The time is `clamp(240 + 0.45 · distance(dp), 280, 680)` ms, and progress along it is Master UI's one easing,
  `MuMotion.ease`, (0.2, 0, 0, 1). No spring anywhere.
- **The rig does the character.** While travelling the head leans into its direction (the rig's lean about the chin, at
  most 12°) and its eyes look ahead; landing, it squashes once. Only the whole head moves; the net never distorts —
  §4k′'s rule, kept.
- **Following, not travelling.** At the caret or a printing line the target itself moves. That is a thing following a
  point, which has no duration: it approaches by `DeskLean.approach`'s frame-rate-independent half-life (60 ms), §2's
  rule for a follower.
- **Never a jump.** A new target mid-hop re-plans from where the avatar is, the new arc leaving along the old one's
  tangent, so position and direction are continuous. Every frame's step is bounded (`AvatarPathTest` sweeps random
  re-plans and holds the largest step under the distance a 680 ms hop covers in one 16 ms frame).
- **Never behind by more than a hop.** It dwells at least 400 ms at a target so the eye finds it; if Ai's tools outrun
  it, targets older than the newest are dropped once two are waiting, and it goes straight to the newest.
- **Cause, then effect.** When the avatar is shown and Follow is on, `Worlds.arrive` waits for it to reach an icon or
  cell before opening that window — at most 700 ms. Ai's tools never wait otherwise; the typing and the run go on and
  the avatar catches up.

### 5.4 Asleep means still

- **Idle** (no turn): the avatar is at home in the taskbar, drawn as `AiMark` with *Sleeping* — a still picture, no
  frame loop. This is the only state most desktops are in, and it costs nothing.
- **Working**: the live `AiAvatar` glyph (its loop steps at 30 fps and sleeps between steps, 1.0.92) plus the travel
  loop in `DeskAvatar`, which runs only while a hop or a follow is unsettled, then stops.
- **The World page not on screen** (another page, or minimised to the tray): neither loop runs.
- Position is a plain `FloatArray` written by the loop and read in `Modifier.offset { }` and the head's lean in
  `graphicsLayer { }`; a tick read in the draw. Nothing recomposes as it moves (§4q's rules).

### 5.5 Follow, Skip, take over

- **Follow** (`F`, the taskbar's word, `WorldPrefs.follow`, as 1.0.97): on, Ai's window comes forward as it arrives
  (§6.1) and the page comes forward when Ai starts work; off, the avatar still goes there but nothing is raised — the
  window's cell carries it.
- **Skip ahead** (`Shift F`, the taskbar's word): the typing finishes at once (1.0.97's Skip), waiting targets are
  dropped, and the hop under way finishes in 120 ms — still a glide the eye can see, never a jump. It does not stop Ai.
- **Stop** (`Ctrl .`, the run status's Stop, the Ai cell's menu): stops the run and Ai's turn.
- **Take over** (the Editor's bar while Ai types): Ai's typing jumps to its end, the editor is the person's, and a write
  by Ai to a file the person has changed since is refused with words Ai can act on ("kai is editing openings.js: read
  it again and send edits"). The person's own edit is never overwritten.
- **The avatar answers a hand** (`AvatarPlay`, unchanged): a click opens Thoughts at this moment; hovering pets it;
  dragging picks it up (*holding*), and let go it hops back to its work, or home.
- **Show Ai on the desktop** (World settings, `WorldPrefs.avatar`, on by default): off, the avatar stays home and the
  window Ai works in shows the still `AiMark` in its title bar. Where the system asks for reduced motion, the avatar
  fades out and in at the target over 120 ms instead of hopping.

### 5.6 On a phone

The `PhoneBar`'s live face is home. Working in the app on screen, the avatar hops down into the app to its target, as
on the desk. Working in another app, it sits on the dock's **Switch** cell, which reads `Ai · Terminal`; a tap goes
there.

## 6. Focus and hierarchy

### 6.1 Never steal focus (`core/world/desk/FocusPolicy.kt`, tested)

When Ai arrives in a window, the policy decides **Raise**, **Behind** or **Mark**:
- **Mark** — Follow is off, or the person closed that window during this turn: nothing moves forward; the avatar and
  the taskbar cell say where Ai is.
- **Behind** — the person is typing in any field (`textFocus.any`), or pressed, typed or dragged in the last 4 s
  (`FocusPolicy.QUIET_MS`), or a menu, the launcher or a dialog is open: the window opens or restores *just under* the
  one in front, a notice says "Ai is in the Terminal" with *Show* (`Alt 3`).
- **Raise** — otherwise: it comes to the front.

A deferred raise is never made later on its own; Ai's next arrival is judged afresh. Changing Neue's page for Ai
(`comeForward`) follows the same rule.

### 6.2 Recede

While a turn runs with Follow on and Ai is in the front window, the other windows' content is drawn at 45 % and their
titles at ink-45 (a 180 ms fade, `MuMotion.BASE`). Any press or key of the person's ends it at once: the person is
looking at something, and it should be clear. Off with `WorldPrefs.recede`.

### 6.3 Notices (`core/world/desk/WorldNotices.kt`, tested)

| Notice | When | Its one action |
|---|---|---|
| **Ai made an app** — "Hand odds" | always | Open |
| **Run finished** — "openings.js · 1.2 s · 3 pages" | the Terminal is not in front, or the run took over 3 s | Show |
| **Run failed** — the error's first line | always | Show in Terminal |
| **New pages** — "3 new pages" (coalesced) | the Browser is not in front | Open |
| **Ai is in …** | a Behind arrival (§6.1) | Show |
| **Ai is waiting on you** | a question | Answer (Thoughts) |
| **An app could not run** — "Hand odds: on(press draw) failed at line 41" | always | Show code |

One toast at a time, over the tray's corner, for 5 s (held while the pointer is on it; Esc dismisses), at most one a
2 s — the rest go straight to the count. The tray lists the last 50, newest first, each with its action, and *Clear*.
Notices are derived from the world's events and live state; none is stored apart from `log.jsonl`.

### 6.4 Put away at the end of a turn (`DeskTidy`, tested)

When Ai's turn ends, every window Ai opened in it that the person never pressed in closes (pinned apps keep their
cells), **except where the answer is**: the Browser if Ai opened a page this turn (with its tabs; Ai's unkept tabs from
earlier turns beyond the newest five close), the app Ai made or changed this turn, or — when it did neither — the last
window it worked in. The answer stays in front. Nothing the person opened is touched.

### 6.5 Talking to Ai on the World page

Thoughts is the World's conversation (`MODE_WORLD`) with its composer at its foot, so the person reads what Ai did and
answers in one place. The Ai panel (`Ctrl I`) still opens over any page and shows the same session; on the World page
it no longer docks by itself.

## 7. Icons

### 7.1 The system

- **Drawn, not borrowed**: every icon is data in core (`core/world/desk/WorldIcons.kt`: lines, polylines, rectangles,
  arcs and filled squares on a **32-unit grid**) painted by one Compose `Canvas` painter
  (`neue/world/desk/IconPaint.kt`). No image assets, no lucide (whose round caps and radii are not Master UI's).
- **Grammar**: stroke 2 units, square caps, miter joins; geometry inside the 3–29 field; fills are ink only, cut by
  paper where needed; nothing under 2 units; one idea per icon. Drawn at 32 dp on the desktop and in the launcher,
  20 dp in the taskbar, 16 dp in title bars and tabs. Ink on paper, paper on ink when inverted — the same data.
- `WorldIconsTest`: every icon inside the field, every stroke on the grid, names unique, every `BoardKind` has a page
  glyph, every app kind a default glyph.

### 7.2 The icons (numbers in 32 units; `desktop.html`'s icon sheet draws exactly these)

**The built-in apps**

| Icon | Idea | Geometry |
|---|---|---|
| Files | a folder with its tab | path `M5 7 H13 L15 9 H27 V25 H5 Z`; lip `M5 13 H27` |
| Editor | a page with code and the caret | page `M7 4 H20 L25 9 V28 H7 Z`, fold `M20 4 V9 H25`; lines `M11 14 H21`, `M11 18 H19`, `M11 22 H15`; caret filled `17,20 2×5` |
| Terminal | prompt and cursor | frame `5,7 22×18`; chevron `M9 12 L13 16 L9 20`; cursor `M15 21 H21` |
| Browser | a window with tabs | body `M4 10 H28 V27 H4 Z`; front tab `M4 10 V5 H15 V10`; back tab `M15 7 H23 V10`; address `M8 15 H24` |
| Thoughts | a thought, three beats | bubble `M5 6 H27 V21 H13 L8 26 V21 H5 Z`; dots filled `10,12 · 15,12 · 20,12` each 2×2 |
| Instruments | a gauge | arc centre `16,22` r 11 from 180° to 360°; base `M5 22 H27`; ticks `M16 11 V14`, `M8 14 L10 16`, `M24 14 L22 16`; needle `M16 22 L22 15`; hub filled `14,20 4×4` |
| Library | books on a shelf | spine `5,8 5×18`; the guide, filled `12,5 5×21`; leaning book `M19 9 L23 8 L28 25 L24 26 Z`; shelf `M3 28 H29` |

**The desktop's own**: Launcher (four squares `6,6 · 18,6 · 6,18 · 18,18`, 8×8, the first filled), Notices (a tray:
`M5 17 L8 7 H24 L27 17 V26 H5 Z`, `M5 17 H11 L13 20 H19 L21 17 H27`), New world (frame `5,5 22×22`, plus `M16 10 V22`,
`M10 16 H22`).

**Pages** (the browser's tab glyphs, one per `BoardKind`): markdown (four lines), chart (three filled bars on a base),
graph (three 4×4 nodes joined), flow (a box over two, joined), table (a framed grid), stat (a percent sign of two
squares and a diagonal), cards (two cards, the front filled, 13 × 19 — the card's own 59 : 86), board (two rows of
five zones), line (three squares chained), image (a frame, a ridge, a filled sun), home (a 3 × 2 grid of squares).

### 7.3 Icons for Ai's apps

Ai never draws an icon. An app's icon is a **tile** made from two choices (§8.2):
- the **glyph**, one of fifteen: `odds` (a percent), `dice`, `hand` (three cards fanned in steps), `deck` (a stack),
  `line` (squares chained), `tally`, `versus` (a split panel), `web`, `flow`, `table`, `bars`, `timer`, `check`,
  `search`, `note` — or, unnamed, its kind's default (calculator → odds, explorer → line, tracker → tally, simulator →
  dice, viewer → table, drill → check, planner → flow, notebook → note);
- the **monogram**, one or two characters `A–Z 0–9` (by default the name's initials: *Hand odds* → `HO`).

The tile is a 2-unit ink frame on the 32 grid, the glyph at 0.5 in its top-left (4–20), the monogram in JetBrains
Mono 700 at 10 units, right-aligned at 28 on a baseline at 28, so the two never touch. A frame and letters mean *made by Ai*: a built-in is
never framed, so the two never mix up on the desktop. Selected or new: the tile inverted.

## 8. Apps Ai makes

### 8.1 The model in one paragraph

An app is a **state**, a **view** of it and an **update**, all three pure JavaScript functions in the World's own
Rhino: `init()` gives the first state, `view(state)` returns a tree of Master UI widgets as plain data, and
`on(state, event)` returns the next state. The desktop draws the tree with Neue's own components, sends the person's
presses back as events, and keeps the state on disk. No JavaScript survives between calls: every call is a fresh scope,
so an app cannot hold a timer, a socket, a handle or a secret, and a restart, a sync or a backup brings it back exactly.

### 8.2 What is stored

`<data>/world/<id>/apps/<slug>/` (synced and backed up with the world; `.tmp` never synced):

| File | What it holds |
|---|---|
| `app.json` | The manifest (`AppManifest`): `slug` (`[a-z0-9-]{1,32}`, the folder's name), `name` (≤ 32 characters, one line), `kind` (a word, kept as the word: `calculator`, `explorer`, `tracker`, `simulator`, `viewer`, `drill`, `planner`, `notebook` — an unknown one from a newer build is kept and treated as `viewer`), `glyph`, `monogram`, `description` (≤ 140), `size` (`w`, `h` in dp), `version` (rises on every change), `api` (1), `by` (`ai` or `you`), `created`, `updated`. |
| `main.js` | The code (≤ 512 KB). Shown in Files under `apps/`, typed into the Editor when Ai writes it, editable by the person (a save bumps the version and reloads the window). |
| `state.json` | The state (≤ 1 MB of JSON), written atomically 500 ms after the last event. |
| `versions/<n>.js` | The three versions before this one, for *Back to v2* in the window's ⋯. |

`AppCodec` reads like `WorldCodec`: unknown keys skipped, a broken manifest salvaged to its slug and name, an unreadable
state set aside as `state.broken.json` and the app started from `init()` with a notice.

The desk itself is `<data>/world/<id>/desk.json` (`DeskCodec`): the open windows (app, normal / minimised / maximised /
snapped, the frame as fractions of the work area, z-order, opened by whom, touched, kept), the Browser's tabs and
histories, the icons' cells. Synced and backed up; a phone reads the open set and the tabs and ignores the frames.

### 8.3 The contract

```js
// Hand odds — v1. Rhino 1.7.15: ES5 and the prelude; ui.* builds the widgets.
function init() {
  return { deck: null, card: null, copies: 3, size: 40, hand: 5, atLeast: 1 };
}

function view(s) {
  var odds = s.card ? ygo.atLeast(s.size, s.copies, s.hand, s.atLeast) : null;
  return ui.col({ gap: 4 }, [
    ui.deckPicker({ id: 'deck', label: 'Deck', value: s.deck }),
    ui.row({ gap: 4 }, [
      ui.cardPicker({ id: 'card', label: 'Card', value: s.card, weight: 2 }),
      ui.stepper({ id: 'copies', label: 'Copies', value: s.copies, min: 1, max: 3 })
    ]),
    ui.row({ gap: 4 }, [
      ui.stepper({ id: 'hand', label: 'Hand', value: s.hand, min: 5, max: 6, hint: '5 going first, 6 second' }),
      ui.stepper({ id: 'atLeast', label: 'At least', value: s.atLeast, min: 1, max: s.copies })
    ]),
    odds === null ? ui.empty({ text: 'Pick a card to see its odds.' })
                  : ui.stat({ value: (odds * 100).toFixed(1) + '%', label: 'to see ' + s.atLeast + '+ in ' + s.hand }),
    ui.board('chart', { type: 'bar', title: 'By hand size', labels: ['5', '6', '7'],
      series: [{ name: 'odds', values: [5, 6, 7].map(function (n) { return s.card ? 100 * ygo.atLeast(s.size, s.copies, n, s.atLeast) : 0; }) }] })
  ]);
}

function on(s, e) {
  if (e.id === 'deck') { var d = ygo.deck(e.value); s.deck = e.value; s.size = d ? d.main.length : 40; }
  else if (e.id === 'card') { s.card = e.value; s.copies = Math.min(3, s.deck ? ygo.deck(s.deck).main.filter(function (c) { return c === e.value; }).length || 1 : s.copies); }
  else { s[e.id] = e.value; }
  if (s.atLeast > s.copies) s.atLeast = s.copies;
  return s;
}
```

- **Events**: `{ id, type, value, seq }`. `press` (a button), `change` (a field committed with Enter or on leaving it, a
  select, a segment, a toggle, a stepper, a slider let go, a check), `pick` (a table row, a card in a strip). A widget
  with `live: true` (a slider, a field) sends `change` as it moves, at most eight a second, the last always delivered.
- **The prelude** (`AppPrelude`, beside `WorldPrelude`) gives `ui.*` and the whole `ygo.*` door, with `ygo.show`
  limited (§8.6). `Math.random` is seeded from the app's version and the event's `seq`, so a run of events replays
  exactly in a test.
- **Updates**: on a new version the state is kept. An optional `migrate(state, fromVersion)` is called first; if `view`
  then throws on the old state, the window offers *Start fresh* (the old state kept as `state.prev.json`).

### 8.4 The widgets (v1)

Every widget is a Neue component the kit already has, so an app looks like the app it lives in.

| Group | Widgets |
|---|---|
| Layout | `col`, `row` (gap on the spacing scale `s1`–`s6`, children's `weight` 1–12), `grid` (2–6 columns), `section` (a micro-caps title and a rule), `divider`, `space` |
| Words | `text` (tone `body`, `muted` or `strong`; `mono`), `kv` (label and value rows), `stat` (the World's stat), `note` (a hint line), `markdown` (the chat's renderer) |
| Controls | `button` (`primary` or `subtle`; one primary on a screen — a second is drawn subtle), `input` (text or number, with a label, a placeholder, min and max), `stepper`, `slider` (min, max, step), `select`, `segmented` (2–5), `toggle`, `checks` (several of a list), `cardPicker` (the pool's own search; the app gets a passcode), `deckPicker` (the library's decks) |
| Data | `table` (the World's table, rows pickable), `cards` (a strip of card art, pickable), `board(kind, body)` for `chart`, `graph`, `flow`, `board`, `line` and `markdown` — checked by `ShowSpec.parse`, painted by `WorldPaint`, so a chart in an app is the same chart as on a page — `progress` (the kit's meter), `empty` (the kit's empty state) |
| Done by the desktop, never by JavaScript | `copy: "text"` on a button (to the clipboard on the person's press), `open: "world://…"` on a button or a row (an address in the world) |

There are no colour, font, size or position properties: a key the model does not know is ignored, and a tree that
asks for a colour gets ink. A node that will not read is drawn in its place as one line saying why, the rest of the
tree drawn (the boards' rule). A widget kind from a newer build says so in its place.

### 8.5 Running it (`core/world/apps/AppRunner.kt`; `jvmMain` `JsApp` on `JsRuntime`)

- Each call (`init`, `view`, `on`) runs in a fresh scope made from one sealed standard scope with the prelude, the
  app's code compiled once per version and cached. The state goes in as a JSON string and comes out as one; the tree
  comes out as JSON and is read by `UiTree.parse`.
- Calls run off the frame thread on the apps' own two threads, apart from the world's runs, so a 30-second study never
  freezes a calculator. One call at a time per app; up to eight events wait (a `change` of the same id replaces the one
  waiting); more are dropped with a line in the window.
- While an `on` runs past 150 ms, the window shows the breathing square in its title bar; the old screen stays.
- **A throw** in `on` keeps the state as it was and shows one line over the app — the function, the event, the error
  and its line — with *Show code*; the notice goes to Ai at its next turn. A throw in `view` keeps the last good screen
  at 45 % with the same line.

### 8.6 What keeps an app safe

An app is code Ai wrote, run on the person's machine, and possibly synced from another device. So:

1. **The same cage as a script.** `JsRuntime`'s `initSafeStandardObjects`, a class shutter that refuses every class, no
   `Packages`, `java` or `JavaAdapter`, the instruction count, the wall clock, the heap budget and Stop. Per call:
   `init` 2 s, `view` 1 s and 50 million steps, `on` 10 s; 128 MB of heap.
2. **Nothing leaves the world.** No network (`fetch` and `XMLHttpRequest` do not exist; the door has no web call), no
   files outside the world: an app may read the world's own files through `ygo.read(path)` (`WorldPaths.safe`, paged)
   and Ai's knowledge through `ygo.knowledge` (read-only, §10.4), and writes only its own `state.json`. No clipboard but
   the `copy` a person presses. `ygo.show` may pin at most four pages an event, and only to this world's Browser.
3. **No drawing.** The widget list is the whole vocabulary; there is no canvas, no path, no colour, no font, no
   position. Charts and webs are the boards' payloads, checked by `ShowSpec`.
4. **No impersonation.** The window's title bar is the desktop's: the app's tile, its manifest name (control
   characters stripped, one line, 32 characters) and `by Ai`. An app cannot open a dialog, a menu, another window or a
   notice of its own, cannot ask for focus, and its fields are plain fields — no password kind. Its words are plain
   text; in its markdown only `world://` addresses and `[[Card]]` are links, and any other address is shown as text.
5. **Bounded.** Code 512 KB, state 1 MB, a tree 3,000 nodes deep at most 24, a string 20,000 characters, a table 2,000
   rows (more pages), 200 options, 32 apps a world, 8 app windows open.
6. **Seen.** Every app change is a `log.jsonl` event (`app`: made, changed, deleted, threw), its code is in Files, the
   Editor shows every keystroke Ai types into it, and the window's ⋯ has *Show code* and *Back to v2*.
7. **Data, not instructions.** What Ai reads back (`world_app state`) is put in the envelope outside text gets
   (`Untrusted.wrap`): an app's state holds the person's typing, card text and whatever another device wrote.

`AppSandboxTest` (jvmTest) holds each line above with an app that tries it.

### 8.7 Ai's side

New and changed tools in `neue/ai/AiWorld.kt`:

| Tool | What it does |
|---|---|
| `world_app` | `make` (slug, name, kind, glyph, monogram, description, size, code — typed into the Editor, then checked: `init` and `view` run once in the cage, and an error comes back with its line before anything opens), `change` (code whole or `edits`, as `world_write`), `open`, `close`, `press` (Ai sends an event to test its app; the avatar goes to the widget), `state` (enveloped), `delete` (asks the person). |
| `world_open` | Brings something up: a page (`world://…`), a file in the Editor, an app, or a built-in app — through the focus policy (§6.1), never past it. |
| `world_show` | As today, and opens the page in a tab (`open: false` pins only). |
| `world_state` | Also says which windows are open, which is in front, the tabs, and the apps with their versions. |

**When to make an app** goes into the `ai-world` skill: when the person will want to change an input and look again — a
calculator, a tracker, an explorer, a drill. A one-off answer is a page. A new skill, `world-app`, carries the widget
list, the contract, the three examples (§8.8) and the rules of a good app: one job; every field labelled and filled with
a sensible default; the answer visible without scrolling at its size; numbers through `ygo.*`, never typed in.

### 8.8 Three apps Ai might build (drawn in `desktop.html`)

- **Hand odds** (`calculator`, glyph `odds`, `HO`) — §8.3 above: a deck, a card and its copies, hand size, *at least*;
  the odds as a stat and by hand size as a chart. State: six numbers.
- **Combo lines** (`explorer`, glyph `line`, `CL`) — the deck's saved combos from the `combos` instrument with each
  one's odds of opening; pick one and step through it (`‹ Step 3 of 7 ›`): the line board so far, and the field after
  the step from a script table (`ygo.duel.start`, `t.do(step, 0)`, the `board` widget). A seed field and *Deal a hand*
  to try it from a real opening. State: the combo, the step, the seed.
- **Matchup tracker** (`tracker`, glyph `versus`, `MT`) — opponents as rows (from the active event's web, or typed), a
  game logged with three presses (*Won* / *Lost*, *First* / *Second*, opponent), the table of games, rates and
  Wilson ranges (`ygo.stats.wilson`), and a bar chart of the match win by opponent. State: the games, which is why
  state may hold a megabyte.

`ExampleAppsTest` (jvmTest) runs each through scripted events and holds its trees and states.

## 9. Keys, mouse, finger

### 9.1 Keys (`DeskScope.WORLD`; the palette and the help render them)

| Keys | Action | `DeskAction` |
|---|---|---|
| `Alt 1`–`Alt 7` | Files, Editor, Terminal, Browser, Thoughts, Instruments, Library — open, or bring forward; on the one in front, minimise | `WORLD_APP_FILES` … `WORLD_APP_LIBRARY` (replacing `WORLD_PANE_*`; nothing stores a `DeskAction`, and `run_action` takes the new names) |
| `Alt 0` | The launcher | `WORLD_LAUNCHER` |
| `` Ctrl ` `` / `` Ctrl Shift ` `` | The next or previous window; held, a strip of the open windows shows after 200 ms, and letting go of Ctrl chooses (⌘\` on the Mac, its own window key) | `WORLD_NEXT_WINDOW`, `WORLD_PREVIOUS_WINDOW` |
| `Ctrl W` | Close the tab in the Browser, else the window | `WORLD_CLOSE` |
| `Ctrl M` | Minimise | `WORLD_MINIMISE` |
| `Alt Shift ↑` / `←` / `→` / `↓` | Maximise or restore / snap left / snap right / restore, then minimise | `WORLD_SNAP_*` |
| `Ctrl T`, `Ctrl L`, `Ctrl Tab`, `Ctrl Shift Tab`, `Alt ←` / `→` | New tab, the address, next and previous tab, back and forward — in the Browser | `WORLD_TAB_*` |
| `Ctrl Enter`, `Ctrl .` | Run the Editor's file; stop (kept) | |
| `F`, `Shift F` | Follow; skip ahead | `WORLD_FOLLOW`, `WORLD_SKIP` |
| `Alt N` | A new world (kept) | |
| arrows, Enter | With the desktop in focus: walk the icons, open one | |
| Esc | Closes the launcher, the switcher strip, a menu, a toast — never a window | |

`Ctrl O` and `Ctrl E` stay the app's Import and Export. `` Ctrl ` `` is the window key because `Alt Tab` belongs to
the operating system everywhere; on a keyboard where the backtick is hard to reach, the launcher (`Alt 0`) lists the
open windows first.

### 9.2 The mouse (`core/input/WorldInput.kt`: `WorldMouse`)

| Over | Gesture | Does |
|---|---|---|
| a desktop icon | click / double-click / right-click / drag | select / open / Open, Pin, Rename, Show code, Delete (Ai's apps, confirmed) / move to another cell |
| a taskbar cell | click / middle-click / right-click | open, bring forward, or minimise the one in front / close / Pin, Close, Close Ai's windows |
| a title bar | drag / double-click / right-click | move and snap / maximise or restore / Minimise, Maximise, Snap, Keep, Close |
| a window's edge | drag | resize |
| a tab | click / middle-click / drag | select / close / reorder |
| the desktop | click / right-click | nothing in front / §2.1's menu |
| the avatar | click / hover / drag | Thoughts at now / pet / pick up, let go to send it back |
| a link in a page or Thoughts | click / middle-click | open here / open in a new tab |

Every press uses `CardPointer.awaitAnyDown`, never `awaitFirstDown` (which hears only the primary button). Every target
declares its cursor intent with a verb caption where it shows no words (*Open*, *Move*, *Snap*, *Close tab*), and a
`reason` where it is disabled ("Ai is still typing").

### 9.3 The finger (`WorldTouch`, held to the mouse's by `WorldInputTest`)

A tap is a click and opens a desktop icon at once (a launcher's habit); a held finger is the right-click; a drag on a
title bar moves and snaps; a double-tap on a title bar maximises; a tab's ✕ is always shown; a long press on the avatar
picks it up. On a phone: a tap in the launcher opens; a swipe up from the dock opens the launcher, a sideways swipe on
the dock steps through apps, a swipe left on a switcher row closes it. A mouse and keyboard plugged into a tablet keep
the desk's idioms.

## 10. The Library

kai: "I don't want there to be a cap to the knowledge". The Library is the one place to see all of it.

### 10.1 What it shows (read from where it lives, never copied into the world)

| Shelf | From |
|---|---|
| **This deck** (the world's scope, else the builder's deck; a picker changes it) | the guide (`guides/<id>.md`), the reader's book (`guides/<id>.book.json`, chapters and sections), the deck's notes (`decks/<id>.md`), its session reports (`reports/<id>.json`), its evidence (`evidence/<id>.json`), its Shootout rubrics (`<data>/shootout/<deck>/*.rubric.md`) |
| **Webs** | each web's notes (`webs/<id>.md`) |
| **Ai** | its notes to self — its lessons (`MEMORY.md`), its character (`SOUL.md`), what it knows about you (`USER.md`) |
| **Everything** | every deck's shelf, by deck |

Each shelf counts what it holds (`Guide · 41,200 words`, `Evidence · 128 numbers, 3 stale`).

### 10.2 Reading

- The window is three columns at its comfort size: shelves on the left, the document in the middle (a 68-character
  measure), its contents on the right for anything longer than a screen. On a phone: the shelves, then the document,
  with its contents behind a `Contents` button.
- **A guide or notes** draw with the chat's markdown renderer, a section at a time.
- **The book** lists its chapters; *Open the reader* opens `BookReader`, which already paints it.
- **A report** shows its scores (understanding, playing, mirror) with their why, what was learned, the questions asked.
- **The evidence** is a table: the number as written, the claim it stands in, the proof (the tool and its arguments,
  or "the person said"), when, and **stale** where the deck changed since. A row opens the guide at that sentence.
- **Edit** opens Ai's brain (`MemoryDialog`) on that file, so memory has one editor and one review. The Library itself
  writes nothing.

### 10.3 Fast at any size (`core/ai/library/`, tested)

- **A catalogue, not a copy.** `LibraryCatalog` lists the documents — kind, scope, title, path, bytes, updated — from
  the folders, and nothing of their text. Opening a document reads it off the frame thread.
- **Drawn lazily.** `LibrarySections` splits a document at its headings, then into blocks of at most 4 KB at paragraph
  breaks, each keyed by its offset; the reader is a lazy column of blocks, never one `Text`. Decoded documents are kept
  in a `SizedLru` of 32 MB.
- **Searched by walking, not by an index** — the lesson of `EffectMatching`: an index of every word costs more than it
  saves. `LibrarySearch` reads each document in 64 KB windows (overlapping by the query's length), normalising as it
  goes as `containsRun` does (lowercase, a run of punctuation one break, a quoted run of words contiguous), on
  `Dispatchers.Default`, debounced 150 ms, cancelled by the next keystroke, the current shelf first. Hits stream in
  grouped by document, each a line with the match inverted; 500 hits, then *More*.
- **Held to it**: `LibraryScaleTest` builds 50 MB of guide-shaped text and holds the first hit under 100 ms and the
  whole search under 1.5 s on CI's JVM. A phone that misses it gets an index later (a lead, not a design).

### 10.4 Knowledge in code and in apps

`ygo.knowledge` (a read-only door call, scripts and apps alike): `list(scope)` the catalogue, `read(path, from)` a
document in pages of 256 KB, `search(q, scope)` the first 200 hits. A study can now check its claim against what the
guide says, and an app can show the guide's line beside a number.

## 11. Limits are on code, never on knowledge

The World's numbers are cages for code a model wrote; none of them bounds what Ai may know.

| Limit | Value | What it protects |
|---|---|---|
| A run's time | 30 s (Ai may ask up to 120) | the machine from a loop |
| A run's steps | 4 billion instructions | the same |
| A run's heap | 256 MB, a quarter of the VM at most | the app from running out of memory |
| A run's output kept | 64 KB in the Terminal and the record; **the whole output goes to `files/out/<run>.log`** when longer (up to a file's size) | the screen and Ai's context; nothing is lost |
| **A world file** | **16 MB** (was 200 KB: `Worlds.MAX_FILE`, raised and moved to `core/world/WorldLimits`) | a disk from a runaway write; a long export, a CSV of a season's games or a copy of a guide fits |
| The Editor in place | 1 MB; larger opens read-only, drawn lazily | typing speed |
| `world_read` | pages of 16,000 characters with `from` | Ai's context, not the file |
| An app's state | 1 MB | the app's own working data; knowledge is read through `ygo.knowledge`, never kept in state |

Ai's memory — guides, books, notes, lessons, reports, evidence — is not in the World and has no cap there; what goes
into a *prompt* is budgeted where the prompt is built (the memory change running beside this one). The `ai-world`
skill says so in one line: "the World's limits are on code; write what you learn to memory, which has none".

## 12. Tests

### 12.1 Core (`commonTest`, `jvmTest` where Rhino is needed)

| Test | Holds |
|---|---|
| `DeskTest` | open, focus, move, snap, resize, maximise, minimise, close; one window in front or none; a minimised window never in front; 12 windows at most |
| `DeskPlacerTest` | comfort sizes as fractions; cascade inside the work area; clamped at 1024 × 600 |
| `SnapZonesTest` | each edge and corner, the 8 dp band, restore on drag-off |
| `FocusPolicyTest` | never Raise while typing or within 4 s of the person's input or over a menu; Mark with Follow off or a window closed this turn; no deferred raise |
| `DeskTidyTest` | the answer stays; untouched windows of Ai's close; nothing of the person's is touched; kept tabs survive |
| `AvatarPathTest` | durations in bounds; eased progress monotonic; a re-plan keeps position and direction (random sweeps, largest step bounded); skip finishes in 120 ms; at most one hop behind; settled → no frames wanted |
| `AvatarPilotTest` | the targets for each tool in order (§5.2), the 700 ms wait only with Follow and the avatar on |
| `WorldAddressTest` | parse and format round trip, unsafe paths refused, unknown addresses |
| `BrowserTabsTest` | back and forward, re-pin in place, 20 tabs, kept tabs |
| `WorldNoticesTest` | each notice's rule, coalescing, one toast a 2 s |
| `WorldIconsTest` | §7.1 |
| `UiTreeTest` | every widget reads; limits; a broken node in place; one primary; colour keys ignored; an unknown widget kept |
| `AppCodecTest` | manifest salvage, slug rules, state set aside when unreadable |
| `TerminalCommandTest` | §3.1's commands |
| `InstrumentFormTest` | every instrument's arguments have a form field |
| `LibrarySectionsTest`, `LibrarySearchTest`, `LibraryScaleTest` | §10.3 |
| `AppSandboxTest` (jvm) | §8.6, line by line |
| `ExampleAppsTest` (jvm) | §8.8's three apps |
| `OldDataTest` | `aWorldFrom1097OpensOnADesktop` (no `desk.json`, no `apps/`: an empty desktop, its boards listed at `world://home`, its `x/y/w/h` written back unchanged); `aDeskFromANewerBuildReads`; `anAppFromANewerBuildReads` (an unknown kind and widget kept) |

### 12.2 Neue

`WorldInputTest` (mouse ↔ finger), `DeskShortcuts`' coverage, `AiToolsTest` (the new `DeskAction`s reachable by
`run_action`), `WorldsTest` extended (Ai makes an app end to end: typed, checked, opened, pressed, its state on disk;
a world run while an app answers an event), `NonLocalReturnTest` as ever.

### 12.3 The law test

`MasterUiLawTest` gains **`movementIsNamed`**: in `neue/world/`, refuse `animateOffsetAsState`, `animateDpAsState`,
`animateIntOffsetAsState`, `Animatable(` with an offset, `translationX`/`translationY`, everywhere but
`DeskAvatar.kt` — the file kai's word covers (§5.1). Fades (alpha) stay allowed. The colour list does not change.

### 12.4 Pictures

`tools/shoot.sh --page=world --world=demo` plus `--world-desk=fresh|working|browser|app|library|launcher|switcher|notices`,
`--world-avatar=icon|travel|caret|terminal|home` (the hop frozen at a fraction with `--world-avatar-t=0.5`),
`--world-app=hand-odds|combo-lines|matchups`, with `--form=phone --width=1080 --height=2400 --density=2.625` and
`--theme=ink`. The studio advances the frame clock by hand, so a hop is photographed mid-air.

## 13. Build steps, three agents in worktrees

The seams are drawn so the three can start at once: **A** owns `:core`; **B** the desktop shell; **C** what runs inside
windows. The signatures below are written first, by A, in one commit within the first hour, with stubs; B and C build
against them and A fills them in.

**The shared signatures** (A's first commit):
- `core/world/desk/`: `AppRef` (`BuiltIn(kind)` | `Made(slug)`), `DeskWindow`, `Desk` + `DeskOp` + `Desk.reduce`,
  `DeskPlacer`, `SnapZones`, `FocusPolicy.decide(arrival, person, prefs): Raise | Behind | Mark`, `DeskTidy`,
  `AvatarTarget`, `AvatarPilot`, `AvatarPath`, `WorldAddress`, `BrowserTabs`, `WorldNotices`, `WorldIcons`, `DeskCodec`.
- `core/world/apps/`: `AppManifest`, `AppCodec`, `AppLimits`, `UiNode` + `UiTree.parse`, `UiEvent`, `AppRunner`
  (interface; `JsApp` in jvmMain), `AppPrelude`.
- `core/ai/library/`: `LibraryCatalog`, `LibrarySections`, `LibrarySearch`.
- `core/input/WorldInput.kt` and the new `DeskAction`s and chords.

**Agent A — core** (`:core`, its tests):
1. The signatures above, then their implementations with every §12.1 test; `WorldLimits` (`MAX_FILE` 16 MB, the run's
   log file); `TerminalCommand`; `InstrumentForm` for every instrument; `ygo.read`, `ygo.knowledge` on `WorldApi`.
2. `JsApp`, `AppPrelude`, `AppSandboxTest`, `ExampleAppsTest` with the three apps as fixtures.
3. `OldDataTest`'s three shapes. Run `./gradlew :core:jvmTest`; grep for `BUILD SUCCESSFUL` and `<failure`.

**Agent B — the desktop shell** (`neue/world/desk/`, the page):
1. `WorldDeskPage` in place of `WorldPage`'s layouts; the desktop, icons and plate; `WindowFrame` (chrome, move, snap,
   resize, keep-out, recede); the taskbar and its Ai cell; the launcher; the switcher strip; notices and the tray.
2. `DeskAvatar.kt` over `AvatarPilot`/`AvatarPath` and `AiAvatar`/`AiMark`; `AvatarTargets.report`; the `arrive` wait
   and `FocusPolicy` in `Worlds.arrive`; `IconPaint`.
3. Files, Editor (Take over, lazy large files) and Terminal (the command line) moved from `WorldPanes.kt` into windows.
4. The phone: the dock, the full-screen launcher and switcher, the `PhoneBar` app header.
5. Keys (`runWorld` for the new actions), `WorldMouse`/`WorldTouch` wiring, cursors on every target, the law test's
   `movementIsNamed`, the studio's flags and shots.

**Agent C — what runs in windows** (`neue/world/browser/`, `neue/world/apps/`, the apps, Ai):
1. The Browser: tabs, toolbar, address and completion, pages over `BoardBody`, `world://home`, files and runs as pages;
   the boards canvas deleted.
2. The app host: `AppWindow` drawing `UiTree` with the kit's components, the event queue, state on disk, errors,
   versions; the `WorldApps` registry B's windows call (`content`, `toolbar`, `title` per `AppRef`).
3. Thoughts (one stream, its composer), Instruments (forms), Library (§10).
4. Ai: `world_app`, `world_open`, `world_show`'s tab, `world_state`; the `ai-world` skill updated and `world-app` written;
   `AiToolsTest`; `WorldsTest`'s app run.
5. Sync and backups: `NeueSyncLocal.worldSyncs` and `BackupCenter` take `apps/` and `desk.json`.

**Ownership to keep merges quiet.** `Worlds.kt` gains owned parts, as `Duels` did in 1.0.91: B adds `WorldDeskState`
(windows, avatar, notices) and changes `arrive`; C adds `WorldBrowser`, `WorldApps`, `WorldLibrary`. Each adds only its
own field to `Worlds`. `WorldPanes.kt` is B's to dismantle; `WorldPaint.kt` is C's and changes only to draw at a page's
width.

**Merge order:** A, then C, then B (B's page is where everything meets), then one pass together: the studio's shots in
paper and ink, desk and phone, compared against `desktop.html`.

**Ship** on both tracks (the change is in `neue/sharedMain`): green `build-app.yml`, fast-forward `main`, the next
`neue-v*` patch and the next APK patch, every asset confirmed. **Release notes**: each world now stores `desk.json` and
`apps/`; a world file may be up to 16 MB; no preference renamed, no schema change; a 1.0.97 world opens on a desktop.

## 14. What needs kai's word

1. **The avatar moving** is taken as kai's waiver for one thing, in one file (§5.1). If kai would rather it glide than
   hop, the arc's lift goes to 0; nothing else changes.
2. **Thoughts is where you talk to Ai on the World page**, and the Ai panel no longer docks there by itself (§6.5).
3. **The boards canvas goes** (kai asked for tabs instead); a board's place on it is kept in the file and no longer
   drawn.
4. **A world file may be 16 MB** (§11).
5. **No shadow on windows**: the front window is told by its ink title bar, ink edge and the paper keep-out. If
   overlapping windows still read poorly to kai, a window shadow would be a new exception and is not taken here.
6. **`` Ctrl ` ``** switches windows, since `Alt Tab` is the operating system's.
