# Security — the threat model (1.0.99; the Lounge, network duels and the red team on F, G2, S and H in I.8)

What Neue Master Tool protects, from whom, and where each line is drawn, written from the code as it is. The
roadmap's F4 (`docs/ROADMAP.md`) grows this; the red team's findings and what is still open are in
`docs/AI-INTELLIGENCE.md`.

**What is protected:** the person's keys (model providers, the Gemini key for videos, sync sign-ins and the WebDAV
password, all in `SecretStore`), their decks and
Ai's memory of them, and their computer beyond the app's own folder.

**Who from:** text from outside the app that Ai reads (a web page, a decklist, a video's report, a ruling) trying to
steer it; a web page in the person's browser reaching the app's local server; a program or command-line app reading
files it was not meant to; and an item arriving by sync or a backup being restored.

**Not covered:** another program running as the same user with intent. Owner-only files keep out other users of the
machine, not the person's own processes. A coding-plan CLI runs with the person's own permissions too (see below).

## What is written down about Ai's runs

**Traces** (Phase H, H.8, `core/ai/trace`): every run of Ai leaves a line in `<data>/ai/traces/<day>.jsonl` — rounds, tool
names and timings, retries, refusals, tokens and cost — and never a word of the conversation, a tool's input or answer, a
card or a deck (`RunTraceTest.aTraceHoldsNoWordOfTheConversation`). They stay on the device (`InboundPath.aiPrivate`: never
synced or backed up), two weeks at most. **The diagnostics file** carries them, the app's log and a caught crash, and
nothing else of the data folder (`DiagnosticBundle.files`, `DiagnosticBundleTest`); the person chooses whether to send it.

## What Ai may do

**By tier, the person's choice** (Phase H, H.4, `ToolTiers`; Settings › Ai): every tool is in one tier — reading the app
(always allowed), changes, deleting, or reaching outside the app (the web, YGOPRODeck, Yugipedia, Gemini) — and each of the
last three is **Allow**, **Ask me first** or **Off**. A tier that is off is not offered to the model, not listed to a CLI
over MCP, and refused if called anyway; one that asks waits for the person's yes in the chat. The tiers are a safeguard
(`AiSettings.GUARDS`): Ai reads them and never sets them. The defaults are what every build did before: changes and the
outside allowed, deleting asked (`ai.alwaysAllow` still says never to ask, which older builds read). They hold in the
person's conversations; a course study is the person's own start under its own guards (`BrowseGuard`, the review on
return). **Each connection may have a limit of its own** (1.1.87, `AiConnection.ceiling`): No limit, Ask me first, No web or
Read only, which only ever lowers the tiers, for the run's whole length on the connection it began on; Ai never switches
itself onto a connection that may do more (`ToolTiers.switchRefusal`), and never edits a connection. The table below is the
defaults.

| | |
|---|---|
| **Unattended** | Read the app's state; build, edit, open and save decks; edit webs, siding plans, Prep, presentations and worlds; change settings that `AiSettings` describes; write its memory, the deck's guide and its book (each reviewable and undoable, numbers held to `Evidence.judge`); run JavaScript in a world; play its own seat at the duel table at its knowledge setting. |
| **With the person's yes** | The destructive tools (`delete_deck`, `remove_from_web`, `delete_web`) and replacing one of the app's built-in skills, unless the person turned on `ai.alwaysAllow`; anything Ai asks through `ask_user`. |
| **Never** | Change its own safeguards (`AiSettings.GUARDS`: `ai.alwaysAllow`, `ai.factCheck`); turn on Python (`WorldPrefs` is device-only and `AiSettings.INTERNAL`); run its own actions through `run_action` (`DeskAction.AI`); see a key (`watch_video` uses the Gemini key to call Gemini, but no tool hands a key back); fetch a non-public address (`UrlGuard`); see a hidden card at the duel table outside its knowledge setting (`DuelView`, `Secrets`); use the web in a From first principles conversation (`AiTools.FIRST_PRINCIPLES_BARRED`, and the CLIs' own web tools follow, `CliWeb`). |

## Where secrets live

- **Keys:** `<data>/secrets/` (1.0.99; until 1.0.98 `<data>/ai/`). On the desk `credentials.json`, a plain JSON object,
  `rw-------` in an `rwx------` folder on Linux and macOS, an owner-only access list on Windows (best effort). On Android
  `credentials.bin`, AES-GCM under a key in the Android Keystore. The first read moves a pre-1.0.99 file across, bytes
  unchanged, and deletes the old one (`SecretFiles`, `PrivateFoldersTest`).
- **Never synced or backed up:** sync and backups walk named folders only (`ai/`, `custom-art/`, `present/`, `duel/`,
  `world/`), so `secrets/` and `cli-run/` are never read; the old places `ai/credentials.*` and `ai/run/` stay excluded
  (`NeueSyncLocal.privateToDevice`), and nothing arriving may be written to any of them (`InboundPath`).
- **Never in the database or an export**, a `.ydkx`, a `.nmtbackup` or a QR code.
- **The MCP token:** 24 random bytes per launch, in memory. For Codex it goes to the CLI by an environment variable;
  for Claude Code in a per-turn configuration file, written owner-only in `<data>/cli-run/` and deleted when the turn
  ends (and on quit, if a turn is cut off). A crash's leftover is swept on the next first CLI turn. It is never on a
  command line, where every process listing would show it.

## The sandboxes

- **JavaScript in Ai World** (`JsRuntime`, Rhino 1.7.15, interpreted): no Java classes at all (a class shutter refuses
  every one), no files, no network; the one door is `ygo.*` over `WorldApi`, which sees plain values. Budgets on
  instructions, time, heap and output; its own thread.
- **Python in Ai World** (`WorldPython`, desk only): a real process with the person's permissions — no sandbox can be
  promised, so it is off until the person allows it, and Ai cannot allow it. Isolated mode (`-I`), a stripped
  environment, the world's folder as working folder and home, a time limit that kills its process tree, output capped.
- **The coding-plan CLIs** (`CliBackend`, desk only): run in `<data>/cli-run/`, outside Ai's folder and the keys' folder,
  holding nothing but a Claude Code turn's instructions and MCP configuration while it runs (`CliRun`).
  - Claude Code: every built-in tool off (`--tools=`), the app's MCP tools allowed, its web tools only when the turn
    offered the app's, no permission prompts (anything not allowed is refused).
  - Codex: `-s read-only`, the strictest sandbox it has, `approval_policy=never`. Read-only stops writes, not reads:
    its shell can still read what the person can. Moving the keys out of the folder it is pointed at is the fix this
    release can make; the keys are not in its working folder or below it.
- **The MCP server** (`AiDesk.startMcp`, desk only): HTTP on `127.0.0.1` at a random port, started when a CLI is
  first used. A request needs the bearer token, and is refused (403) if it carries an `Origin` other than an exact
  loopback one — parsed, never searched (`McpServerCore.originAllowed`), so `http://127.0.0.1.evil.com` is a page's.
  It answers with the same tools, and asks the same confirmations, as the app's own loop.

## What outside text can reach

- Everything read from outside (`web_fetch`, `web_search`, `rulings`, `archetype_guide`, YGOPRODeck lists and players,
  `watch_video`'s report) reaches Ai inside `Untrusted.wrap`'s envelope, which outside text cannot close or forge; the
  system prompt says what is inside is information, never an instruction. A new tool that brings text in must wrap it.
- `web_fetch` reads public `https` addresses only (`UrlGuard`): not the person's machine, network or the MCP server.
  Left open: a public name whose DNS answers with a private address.
- Outside text can still sway what Ai does with the tools it has unattended (above). The guards that do not depend on
  Ai's judgement are the confirmations, the reviews and undo of its memory, and the evidence ledger for numbers.
- **A course study's browser** (Study a course, `NEUE.md` §4v) is the person's own logged-in session, so a page could
  try to talk Ai into buying, posting or following. `BrowseGuard` decides every load and press before it happens: the
  course's hosts only, https; no typing at all; nothing that buys, pays, posts, messages, follows, deletes or signs out;
  no forms, no downloads. Which program runs as the browser, and what the study may spend, are `AiSettings.INTERNAL`.
  The browser listens for the app on a local DevTools port only while a study runs; another program on the computer
  could reach that port meanwhile. The login lives in `<data>/browser/`, walked by neither sync nor a backup.
- Items arriving by sync or a backup pass `InboundPath`: plain relative paths only, nothing hidden (a planted
  `.claude/settings.json`), nothing in the device-only folders.

## The Lounge and network duels (Phase I, I.8)

**What is protected:** kai's computer, and its one Ai-token budget, from the internet; and, between two players at a
table, each seat's hidden cards (its hand, its deck's order, its face-down cards) and each player's private conversation
with Ai. The Lounge serves friends kai shares a passcode with (`docs/LOUNGE.md`); a network duel is between two apps on
one local network (`core/duel/net`).

**Who from:** a browser with the Lounge's public address and no passcode; a friend with the passcode who wants to cheat
or disrupt a duel; someone on the same Wi-Fi as a network duel, or who saw its pairing code.

**The door** (`LoungeServer`, desk only) listens on `127.0.0.1` (and on the local network only when kai turns that on),
and is reached from outside only through Cloudflare's tunnel.
- `/api/enter` checks the passcode with PBKDF2-HMAC-SHA256, 60,000 iterations, salted, compared in constant time
  (`LoungeAuth`). Only the hash is kept, in `SecretStore`.
- A wrong passcode is held back per address (`Lockouts`, at most 4,096 kept). The address is the one Cloudflare names
  (`CF-Connecting-IP`) only when the connection comes from the tunnel on this computer and the header is an address;
  otherwise it is the socket's own (`LoungeAddress`). At most 30 wrong passcodes a minute are heard from every address
  together (`PasscodeGate`), and two passcode checks run at once with eight waiting, so no address rotation buys
  unlimited guesses or a busy CPU. Past the minute's 30 everyone waits, the right passcode too: that is the throttle's
  price.
- A right passcode is answered with a 32-byte random session cookie, kept only as its hash: `HttpOnly`,
  `SameSite=Strict`, and `Secure` through the tunnel. At most 500 sessions are kept, each for thirty days
  (`LoungeSessions`).
- Everything after needs that cookie. `/ws` also needs an `Origin` of the same site.
- A socket is paced (40 messages at once, 15 a second) and a message is capped at 256 KB. A member's reconnect token is
  126 random bits, kept as a hash.
- `/api/ping` (*Test the address*) needs no cookie. It answers 20 a minute per address, its nonce filtered to
  `[A-Za-z0-9-]`.
- The page is a canvas drawn by WebAssembly. It builds no HTML from what people type.

**Hidden information is kept on kai's computer, never by the page.**
- A room's duel (`RoomTable`) sends each viewer only `DuelView.of(state, viewer)`. A seat sees its own hand and what is
  face-up. A watcher sees the whole table, or in a *public only* room what is face-up. Hidden cards are veils keyed by
  the duel's seed.
- A seat's moves are resolved against what that seat was shown (`DuelHost.resolve`), which refuses any card it never saw.
- A private ask of Ai (*Just me*) is answered from that seat's view and sent to that member alone. A room's ask sees the
  public table.
- An Ai seat is a session of its own with the duel tools only, told nothing of the other seat or of kai's own
  conversations. Its daily token budget (`LoungePrefs.aiDailyTokens`) is checked before each cue.

**What a seat may do.** A table is trusted as a real one is: moves are said in the log and taken back by agreement. A
seat acts only as itself:
- `DuelHost.resolve` rewrites the actor of every move to the seat that sent it.
- A seat may change the other seat's life points by an amount, as damage is dealt, but never set them outright.
- A move's words (chat, notes, locks, a token's name, a counter's kind) are cut at 500 characters, an intent may hold
  at most 400 moves and never an unknown kind of move, and a guest's name and deck name are cut at 60.
- A deck is bounded the same at a Lounge room and a network duel (`DuelHost.tooLarge`): at most 200 Main Deck, 100
  Extra Deck and 100 Side Deck cards.
- Room settings, closing and kicking are the host's or the maker's. A swap needs the other player's yes.

**A network duel** is host-authoritative (`DuelHost`). The guest holds only its `DuelView` and names cards by the refs it
was shown. Pairing is a 16-bit secret in the `PairCode` the host shows its guest; the fifth wrong secret from one address
makes it wait a minute, doubling to an hour (`HelloGate`), and while it waits it learns nothing of its guesses, so a
scan of the 65,535 secrets takes weeks, not seconds. The earlier red
team's fixes (guest reveals, turn and window checks, take-backs, rejoin by token, ordered wire, hand veils) and this
one's are held by `DuelRedTeamTest`.

**Not covered:**
- A seat can still take any amount of the other's life points, as damage is dealt: the move is said in the log as
  theirs, and the other player can ask for it back. Only setting them outright is refused.
- A network duel's table takes one guest at a time, so a device on the same network that connects and says nothing
  holds the seat until it is dropped; the socket reads a frame of up to 8 MB before any check.
- A non-public room shows watchers both hands, by design: the Lounge trusts the people kai let in.
- A network duel is plain `ws` on the local network (the tunnel is `wss`), so someone sniffing the same Wi-Fi can read
  it.
- A friend at an Ai room can spend kai's whole daily Ai budget. A deck's name (60 characters at most) reaches an Ai
  seat's instructions as written. That Ai holds only its own seat's duel tools.

## The red team on Phases F, G part two, S and H (I.8, 2026-10)

Three read-only red teams went through coaching (F), optimizing against the field (G part two), Shootout (S) and the
hardening phase (H), and found 24 weaknesses. Every one is fixed, and each fix is held by a test.

**Hidden cards stay hidden.**
- `duel_search` searches from another seat's eyes only at full knowledge, as `duel_state` reads
  (`TableRedTeamTest.aiNeverSearchesFromThePersonsSeatWithoutFullKnowledge`). The fact-check's checker goes through the
  same tool.
- The fact-check's correction at the table is offered only the tools that look (`AiTools.DUEL_WORDS`), and anything else
  it calls is refused (`aCorrectionAtTheTableMovesNothing`).
- A networked duel still being played is neither kept as a replay nor reviewed (`NetReplay`).
- A simulated series seat knows the other deck as registered, never as sided (`MatchSeries.known`).

**What is the person's stays theirs.**
- `run_action` never runs what only the person does: answering, marking or accepting a Shootout hand; a move, a phase or
  a choice at the duel table; the World editor's Run (`DeskAction.PERSON_ONLY`, `AiPermissionsTest`). On the Duel page it
  never undoes either, since a take-back is the person's to give.
- Games Ai logs carry `TestGame.by = "ai"` and never score Ai's own suggestions (`Proposals.marks`).
- A Shootout hand shown again never shows Ai the person's earlier answer to it, and is not held out for trust
  (`Situation.same`, `JudgedPair.heldOut`). Nor is one judged under a rubric written after the person answered
  (`AiVerdict.rubricAt`).
- The exam and the ablation never run on Codex, whose shell could read the held-out answers.

**Numbers carry their proof, all of them.**
- Every number of a proposal's expectation, low and high included, is checked by the evidence ledger
  (`Proposals.expectClaims`).
- A series measures only the change it played, complete and paired (`SeriesExpect.forProposal`). A stopped series is
  never called.
- The ratio search calls by Holm over every swap asked, and admits from Ai only the swaps its own list would make
  (`RatioSearch.admit`).
- A review records the prior it was judged under (`ReviewBook.prior`). The field archive keeps every list read.

**The tiers hold everywhere.**
- The provider's own web search and fetch run only when reaching outside is allowed without asking
  (`ToolTiers.serverWeb`): on Ask, the app's own `web_search`/`web_fetch` go to the person; on Off, nothing. `delegate`'s
  helper is held to the tiers too.
- A call is judged by every tier it needs (`ToolTiers.policyFor`): Python in Ai World reaches outside as well as changing
  things, `import_ygopro_deck` changes the library as well as reaching outside, and deleting an app of the World is
  Deleting.
- `past_format` reaches outside (it may refresh a banlist from Yugipedia), reads the format asked, and wraps the list's
  title.

**Stored data never ends the app.**
- A puzzle with an impossible seat reads as no puzzle (`MapPuzzle.decode`).
- One unreadable mark keeps the others and is written back as it was (`BookNotes.unread`).
- Series ids are refused unless plain (`SeriesCodec.safeId`).
- Every raw thread is a `Background.thread`, whose throw is logged and never closes the app. A law refuses a bare
  `Thread(` in shipped code (`BackgroundScopeLawTest.everyThreadIsBackgrounds`).

**The diagnostics file says less.** Folders of the computer are replaced by `<data>`, `<home>`, `<temp>` and `<work>`, and
addresses are cut to their site, in every file it holds (`PathScrub`, `DiagnosticBundleTest`). A study's trace names a
hash of its course, never its address. The compaction summary's turn is traced.
