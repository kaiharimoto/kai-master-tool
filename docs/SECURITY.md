# Security — a first threat model (1.0.99)

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

## What Ai may do

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
- Items arriving by sync or a backup pass `InboundPath`: plain relative paths only, nothing hidden (a planted
  `.claude/settings.json`), nothing in the device-only folders.
