# The Lounge — friends duel at kai's tables, from a browser

kai's ask (2026-10): *"a guest mode … a simpler but fully functional version of this program that is only meant for
dueling, but everything is hosted on my computer … with a set passcode that i can share … a nickname and room system
that lets us swap around and play or spectate … at labrynth.info … upload decks and edit decks and duel in our group of
3–5 people."*

The Lounge is that. Neue on kai's computer opens a door. Friends open an address in any modern browser, type kai's
passcode, then pick a name. They land in a lobby of rooms where they can:

- sit down, stand up, swap seats or watch;
- bring their decks as `.ydk`, `.ydkx` or `ydke://`, or build them on the page;
- duel at **Neue's own duel table**, the same code drawing the same pixels, compiled to WebAssembly.

The rules, the decks, the duel and (later) Ai all live on kai's computer. A browser is only ever sent what its member
may see.

## Setting it up (kai)

1. **A passcode.** Go to Settings › The Lounge › Passcode, at least eight characters. Share it with your friends only.
   Only a salted PBKDF2 hash is kept, with the app's other secrets; it never syncs and never goes into a backup.
2. **Open it.** Use the switch in Settings › The Lounge, or Duel › New duel › The Lounge. On this computer it answers at
   `http://localhost:47380` straight away.
3. **The address: Cloudflare Tunnel**, so `https://duel.labrynth.info` reaches this computer without opening your
   router. This is done once:
   1. Put labrynth.info on Cloudflare (free plan). At cloudflare.com, *Add a domain*, then change the nameservers at
      your registrar to the two Cloudflare gives you. Wait for *Active*.
   2. Install `cloudflared` on this computer:
      - Windows: `winget install --id Cloudflare.cloudflared`
      - macOS: `brew install cloudflared`
      - Debian/Ubuntu: the `.deb` from Cloudflare's downloads page.
   3. In the Cloudflare dashboard, open *Zero Trust › Networks › Tunnels › Create a tunnel*. Choose *Cloudflared* and
      name it (for example *lounge*). Copy the token from the install command it shows: the long string after
      `--token`, or the whole line.
   4. In the same tunnel, under *Public hostname*, set:
      - subdomain `duel`;
      - domain `labrynth.info`;
      - service type `HTTP`;
      - URL `localhost:47380`.

      WebSockets need nothing extra.
   5. In Neue, under Settings › The Lounge:
      - paste the token into *Cloudflare tunnel* and press Save;
      - set *Address* to `https://duel.labrynth.info`;
      - leave the tunnel switch on.

      Opening the Lounge now starts `cloudflared` with it. The row says *Connected* once Cloudflare has the tunnel.
4. **Optional, a second wall: Cloudflare Access.** In *Zero Trust › Access › Applications*, add a self-hosted
   application for `duel.labrynth.info`, with a policy allowing your friends' e-mail addresses. They then sign in with
   a code mailed to them before the passcode page.
5. **Friends in the same house** can skip the tunnel. Turn on *Local network* and they open
   `http://<this computer's address>:47380`. Settings shows the address.

The Lounge is open only while Neue is. Closing it, or Neue, closes the door and stops the tunnel.

## Playing

- **Friends** open the address, type the passcode once (the browser keeps a 30-day cookie), then a name. A friend who
  comes back from the same browser comes back as themselves, with their decks. Decks are kept on kai's computer, so
  they follow a friend to any browser they join from.
- **Rooms.** Anyone can make a room. There are two seats; everyone else in the room watches. A seat is taken with
  *Sit*, then a deck is chosen. The duel deals itself when both seats are ready and opens with the dice, as Neue's does.
- **Dropping out.** Someone who drops mid-duel has their seat held for three minutes. Coming back sits them down where
  they were.
- **Swapping.** Seats can be swapped by asking: the other player answers. *End* finishes a duel. Every finished duel
  is kept as one of kai's replays.
- **Watchers see everything by default** and choose what to hide: *Both hands*, *Seat 1's*, *Seat 2's* or *Neither*.
  The browser remembers the choice. kai can make a room **public only**, where watchers are sent
  only what is face-up. That is enforced on kai's computer, not by the page.
- **kai** plays from Neue itself. In Duel › New duel › The Lounge, the lobby is the one friends see. *Bring a deck*
  copies a library deck in. Sitting at a room whose duel is on makes the Duel page that room's table. *Leave the
  table* in the Table menu goes back to the lobby, and the local duel comes back.
- **Keys** are Neue's duel keys, the same table, in the browser too (`TableKeys`). Phones get Neue's phone table.

## What the browser needs

A browser with WebAssembly garbage collection: Chrome or Edge 119+, Firefox 120+, Safari 18.2+. An older one is told
so by the page itself. The page is about 5 MB to download (gzipped), once per Neue version. After that, files are
re-checked by tag and not sent again.

## How it is built (for whoever works on it)

```
friend's browser ──https/wss──► Cloudflare (duel.labrynth.info) ──tunnel──► cloudflared on kai's computer
                                                                               └► LoungeServer 127.0.0.1:47380 (Neue, desktop)
                                                                                   ├ /            the :guest page (in the installer)
                                                                                   ├ /api/enter   passcode → HttpOnly cookie
                                                                                   ├ /cards.json  the pool, gzipped
                                                                                   ├ /art/…       art from here (ArtLibrary, else fetched once and cached)
                                                                                   └ /ws          LoungeWire, a room's table inside as Wire
```

- **`core/duel/lounge`**, pure and tested:
  - `Lounge` and `LoungeRules`: members, rooms, seats, held seats, swaps, kicks.
  - `RoomTable`: one room's duel. It serves every viewer `DuelView.of(state, viewer)`: a seat its own, a watcher the
    whole table or `PUBLIC`.
  - `LoungeWire`: the lobby's messages; a room's table travels inside as the LAN table's `Wire`, unchanged.
  - `LoungeAuth`: PBKDF2-HMAC-SHA256 on the common `Sha256`, and the doubling `Lockout`.
  - `LoungeDecks` and `LoungePrefs`. `NeuePreferences.lounge` is device-only and `AiSettings.INTERNAL`: Ai can never
    open this computer to the internet.
- **`:table`** (jvm, android, wasmJs) is the duel table and everything it draws with. Neue and the page both compose
  `DuelPlayArea` through the seams:
  - `TableHost`, `TableAi`, `TableVoice`;
  - `DuelStore`, with `FileDuelStore` on the desk;
  - `TableNet`: `DuelNet` for the LAN, `LoungeTableNet` for a room;
  - `LiveMatch`;
  - `ArtSource`.

  `LoungeClient` is one member's side, and `LoungeLobby` the lobby both draw.
- **`neue/lounge`**:
  - `LoungeHost`: the Lounge's state on the main thread, members' decks and tokens under `<data>/lounge/`.
  - `LoungeCenter`: open, close, passcode, tunnel, and kai joining in-process as the host.
  - `LoungeDoor`: the desk's Ktor server and `cloudflared`; Android has none.
  - `LoungeSettings`, and `LoungeDesk` (the Duel page's dialog).
  - The server (`jvmMain/LoungeServer`):
    - checks the session cookie and the `Origin` on the socket;
    - paces each socket (40 at once, 15 a second);
    - caps a message at 256 KB;
    - locks out wrong passcodes per `CF-Connecting-IP`;
    - refuses `..` in a path.
- **`:guest`** is the page: `GuestApp`, `DecksPage`, `GuestHost` (the table's host in a browser) and `Web` (socket,
  fetch, files, storage). It depends on `:core`, `:builder` and `:table` only, so nothing private (keys, Ai's memory,
  the Anthropic SDK) can reach the bundle.
  - It is gathered by hand from the compiler's output (`guestBundle`), not through webpack, so a build fetches nothing
    from GitHub.
  - `-Pmastertool.guestOptimize=true` takes binaryen's optimised module (CI and releases).
  - `-Pneue.loungePage=true` packs it into Neue's desktop resources under `lounge/`, and `release-neue.yml` passes
    both. An everyday build leaves the page out, and the door then says so.
- **Proof:**
  - `LoungeRulesTest`, `RoomTableTest`, `LoungeWireTest` and `LoungeAuthTest` (core);
  - `LoungeServerTest`: real sockets, two friends through the opening roll while a third watches, refusals, coming
    back;
  - `tools/lounge/smoke.sh`: the built page in headless Chromium through passcode, name, a pasted deck, a seat and the
    opening throw, which kai's computer must see. CI's web job runs it and uploads the screenshots.
  - `LoungeBrowserHarness` is the door with a few real cards and kai seated, held open for a browser.

### Gotchas

- Kotlin/Wasm's `RequestInit(…)` writes every field it is not given as `null`, and the browser refuses `mode: null`.
  `Web.kt` calls `fetch` through `js()` with only what is given.
- Nothing in `:table`'s or `:core`'s web build may import an npm module. The page has no bundler, and a bare import
  such as `@js-joda/core` stops it loading. `duelStamp` reads the browser's `Date`.
- A detached `<input type=file>` opens no chooser in Safari. `chooseText` puts it in the document, hidden.
- Page files are answered `no-cache` with an ETag. An hour's cache once mixed a new `guest.wasm` with an old
  `guest.mjs`.

## Ai at the tables (L5)

kai turns Ai on per room: the lobby's *Your settings for this room* › *Ai may sit and play here*. Then anyone in that
room can press *Ai sits here* on an empty seat and choose one of **their own** Lounge decks for it:

- **Ai against a person:** a person takes the other seat and readies, and the duel deals.
- **Ai against Ai:** Ai sits at both seats, and the duel deals at once for the room to watch. Anyone in the room may end
  it, since no person plays it.

How it plays:
- Each Ai seat is **a session of its own**, as an Ai vs Ai seat is (`AgentPlayer`). It sees only its own seat
  (`MatchTable`'s brief through its `DuelView`), has only the four table tools (`duel_state`, `duel_moves`, `duel_act`,
  `card_info`), and keeps nothing of kai's conversations, memory or other tools. Its conversation is never kept among
  kai's.
- **Whose move:** `core/duel/lounge/RoomAi` (`RoomAiTurn`, pure, tested) says when Ai is owed a move and what the table
  does for it: its dice, its draw, a pass or an ended turn when it stalls, a concession when it fails three times in a
  row. A person's move is theirs, at their pace, and Ai waits.
  - Passes on a chain are read off the log, so a person's *No response* counts as Ai's does.
  - Each tool call takes up the table as it stands, so a person may move while Ai thinks. While Ai's move is actually
    being made, a person's waits a moment.
  - Each player's own response-window setting is kept (`MatchTable`'s `seatWindows`).
- **On kai's connection, within a budget.** It must be an API connection; a plan's command-line app runs its own loop,
  which cannot be held to one seat. What it reads and writes counts against Settings › The Lounge › *Ai at the tables*:
  2M tokens a day by default, Off keeps it out. Past the budget, Ai stops where it is and says so in the log. The day's
  spend is `<data>/lounge/ai-spend.json`.
- Proof: `RoomAiTest` (core) and `LoungeAiTest`, which runs the real driver with a scripted player: Ai throws, chooses,
  plays its turn and hands it back, waits on the person, and is let go when the duel ends.

## Next

The rest of L5:
- one shared conversation per room in the log, where everyone asks Ai about the table it can see publicly;
- a private ask, answered with that seat's knowledge to that player alone.
