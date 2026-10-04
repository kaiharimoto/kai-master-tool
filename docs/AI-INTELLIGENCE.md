# Ai, from assistant to player: the red team, the research and the roadmap (1.0.97)

kai asked for a red team of Ai's workflow and harness "for learning and real world intelligence", then a research and
brainstorming run on how to make it better. The goals:
- tools that give Ai a player's intelligence and let it play better than a player;
- Ai learns a deck with kai and on its own, how to play it and how to build it, through research, maths and reasoning;
- Ai plays the duel itself;
- Ai coaches;
- Ai builds decks.

This is the report. It covers what the red team found, what 1.0.97 fixed, what the research says, and the roadmap.
`NEUE.md` §4k (the harness) and §4r (Ai World) describe the app as it is. `docs/world/INSTRUMENTS-REDTEAM.md` is the red
team on Ai World's own instruments.

## The short answer

**Nothing Ai learned was checked against anything.**
- Its session scores were its own.
- The duel engine checks physics, never card text.
- `OpeningHand` was never called.
- Logged games never reached the guide.
- The only maths Ai could run were a one-line `calculate` and `hand_odds`.

The research is unanimous on why that matters:
- Language models do not improve by reflecting alone. Huang et al. (ICLR 2024) found self-correction without outside
  feedback does not help, and sometimes makes answers worse.
- Every system that learns without changing its weights closes the loop with an outside check: Reflexion with test
  results, Voyager with a critic, ExpeL with task success.
- For playing, no published method reaches expert level by asking a model for moves. The strong agents search over an
  executable model of the game, and use the language model to propose moves, to model the opponent and to judge
  positions. Examples: PokéChamp, Code World Models, the Hearthstone and Legends of Code and Magic competition winners.

So 1.0.97 gives Ai the outside check first: **Ai World**, a computer of its own where every number is a run.
- It has **instruments**: engineered, tested studies of a deck, run in one step, that Ai uses before writing code and
  imitates when it writes its own.
- Separately, the red team's confirmed bugs are fixed: those that let learning go unreviewed, outside text pass as
  instructions, or a script or a synced file reach where it should not.

The roadmap below builds the rest on that foundation, in order:
1. lessons that cite their runs;
2. an evaluation harness;
3. a duel record that can measure "Ai beats players";
4. a forward model of the deck's own cards, written by Ai in Ai World and tested against its combos and replays;
5. search over that model;
6. coaching and deck building on the numbers that search produces.

---

## 1. The red team

**How it ran.**
- Seven finders read the code through seven lenses:
  - learning integrity;
  - the harness;
  - injection and exfiltration (Ai World's sandbox included);
  - real-world data;
  - Ai at the duel table;
  - evaluability;
  - a "prove it" lens that wrote failing tests.
- They returned 65 findings.
- 28 went to verification. Three verifiers each judged a finding: does it reproduce, is it real in this code, does it
  matter to kai's goals. 22 were confirmed by all three, 3 more by two of three, and 3 were rejected.
- The session ran out of budget before the other 37 were verified. They are listed below as **leads**, clearly marked:
  read the code before acting on one.

### Fixed in 1.0.97

**From the exploration before the run, the eleven the plan named:**

| # | What was wrong | What it is now |
|---|---|---|
| 1 | `session_report` stored a score of `1` as 100 | scores are taken 0–100 as given |
| 2 | Ai's own skills escaped the review snapshot and Undo; shadowing a built-in was silent; the unattended reflection could write skills | skills are in the snapshot and reviewed line by line; shadowing asks; reflection cannot write skills |
| 3 | outside text arrived unmarked, a door for injected instructions | every outside result is in an `<untrusted source="…">` envelope (`Untrusted`), named by every prompt (`UNTRUSTED_RULE`) |
| 4 | `web_fetch` could reach this computer, its network, a cloud's metadata service | `UrlGuard`: public hosts only, every redirect checked |
| 5 | a command-line connection kept Claude Code's and Codex's web tools in first-principles mode | `CliWeb` follows the app's own rules |
| 6 | `delegate`'s helper got the whole conversation's prompt and passed a cut-off line as its report | `PromptBuilder.helper`; `Done.outOfSteps` is said |
| 7 | "do not change their decks" was only words in the deck modes | `AiTools.barredIn(mode)`, offered and answered |
| 8 | `Recall` matched "it" inside "with" | whole words |
| 9 | an unknown answer shape from YGOPRODeck, a failed later page, a changed layout all read as "no decks" | said as failures (`Unreachable.of`, `LayoutChanged`, `RecentDecks.unread`) |
| 10 | Yugipedia's cache key could collide and kept error pages; rulings lost their TCG/OCG section and source | SHA-256 keys, errors never cached, `Ruling.line` keeps both |
| 11 | the field snapshot's "share" was a placement-weighted share of top cuts, used as a share of the field | named for what it is (`FieldBuilder.SHARE_CAVEAT`) in the tool, `expected_winrate` and the format-webs skill |

**From the run itself:**

| Finding (verdict) | Fix |
|---|---|
| **Windows path traversal** in sync apply and backup restore: guards split on `/` only, and `\` is a separator on Windows (critical, 3/3) | `InboundPath.safe` (core, tested) refuses backslashes, colons, empty, `.`, `..` and hidden segments; the file must land inside the data folder |
| **A planted `ai/run/.claude/settings.json` or `CLAUDE.md`** arriving by sync or backup would be loaded by Claude Code; `credentials.json` could be overwritten (critical, lead) | `InboundPath` refuses Ai's device-private paths (`credentials.*`, `run/`, `cache/`) inbound as well as outbound, and every hidden segment |
| **World host calls were unbounded Kotlin loops**: `comb(1e15, 5e14)`, `atLeast(1e300, …)` (a float loop that never ends), a binomial over 2 billion, a `handOdds` walk exponential in its groups. Rhino's budget cannot stop a loop in Kotlin (high, 3/3) | counts are held to a deck's scale at the door (`JsonObject.size`), `handOdds` bounds its walk before it starts, and `Calc` refuses to count past ten million |
| **Ai could turn off its own delete confirmation** (`set_setting ai.alwaysAllow`), synced to every device (high, 3/3) | `AiSettings.GUARDS`: `ai.alwaysAllow` and `ai.factCheck` are read by Ai, set only by the person |
| **The OpenAI-compatible wire sent an all-thought turn as `{content: null}`**, and every later message failed with HTTP 400 (medium, 3/3) | such a turn is not sent |
| **Leaving a Fine Tuning run by anything but Finish skipped the review** (New, Start fresh, a connection switch, another conversation from history) (high, 3/3) | `settleTuning()`: every exit offers the review |

### Fixed in 1.0.98

Every confirmed open finding below except the CLI shell's reach and the MCP token's file, with the evidence ledger (Phase 1,
items 1–3 of the roadmap) and the fact-check held to what it looked up. `NEUE.md` §4k has the details.

### Fixed in 1.0.99

The two security findings below: the CLIs run in an empty folder of their own (`<data>/cli-run/`), the keys moved to
`<data>/secrets/`, the MCP token is on disk only owner-only for one turn, and the Origin check is exact. `docs/SECURITY.md`
is the threat model; `NEUE.md` §4k has the details.

### Confirmed, open: the next fixes (as of 1.0.97)

All confirmed by three verifiers, all judged small, local fixes. They are Phase 1 of the roadmap.

**Learning integrity**
- **A Fine Tuning run is not pinned to its deck.**
  - What follows the open deck instead: guide writes, the session report, the reader's book, and the prompt's guide.
  - The budget is measured against whichever guide is open.
  - Fix: record the deck id at `startTuning` and pass it to every write.
- **Undo all can delete another deck's whole book.** The snapshot keys differ before and after.
- **The post-conversation reflection races concurrent writers.** Its Undo reverts other sessions' learning and drops a
  pending Fine Tuning review.
- **The brain editor overwrites what Ai wrote while it was open.** The person's own edits then show up in reviews as
  Ai's.
- **Sync settles memory, guides and reports newest-file-wins**, so learning on one device erases learning on another.
  - Fix: merge markdown memory by entry, as `JsonMerge` does for settings.
- **What Ai learned does not follow a deck** into a web, a duplicate or a copy.
- **After compaction the deck's guide and scope notes leave the conversation for good**, so long runs write duplicates
  and contradictions.
- **The reader's guide passes for current after any write.** It never notices the deck changed, and freezes Ai-typed
  numbers next to recomputed ones.

**Harness**
- **Stop mid-batch writes "Stopped by the person before it ran." for calls that already ran**, so Ai repeats their side
  effects.
  - Fix: write each result as it lands.
- **`stop()` then `begin()` race.** The cancelled run's `finish()` lands in the new session.
- **`max_tokens` and cut streams are not signalled.** A cut answer looks finished.
- **The context estimate counts Anthropic assistant turns twice** and ignores the provider's measured count, so
  summarising starts too early.
- **The summary is written from a transcript cut at 60k characters in the middle**, with tool outputs cut to 300.
- **Background passes are untracked.** Stop, Forget everything and Disable Ai do not cancel the reflection or the
  fact-check.
- **Retries stack** (the SDK's times the loop's). Every `Throwable` is "retryable".
- **Tool results differ by wire.** MCP results are uncapped, and `open_deck` says "Opened" after a timeout.

**Security**
- **A Codex connection has a shell that can read every file**, `credentials.json` next door included.
  - Fix: run the CLIs with a working folder that holds nothing, and keep keys out of `<data>/ai`.
- **The MCP server's bearer token sits in a plain file at default permissions**, and the Origin check is a substring
  match.

### Leads: found, not yet verified

Read the code before acting on any of these.

**At the duel table** (the first four, and `duel_peek`'s reason, closed in Phase C's first stage: `docs/phases/C.md` §3)
- Ai's own-seat moves may reveal, flip or take the opponent's hidden cards. The knowledge cap covers reads only.
- On a networked table, Ai's tools read and move the guest's seat (`aiSeat` defaults to 1).
- `duel_combo` records name hidden cards Ai touched.
- `duel_act at` rewrites the past and re-deals later draws. That is fishing for a better hand.
- Coordinates in the brief and in the parser disagree for the opponent's hand.
- Ai's text after `;`, in `duel_peek`'s reason, and in combo steps reaches the record unredacted.
- Cues drop the person's moves on Ai's cards.
- **Ai has no access to its guide at the table.**
- The brief gives names only: no ATK/DEF, Level, Attribute or Type.
- Ai World's duel tables are seed 1 by default, with no choice of who goes first and no fork of the live position.

**Evaluability**
- Duel games are logged to Prep with the wrong first/second whenever the opening roll decided it. (Closed, Phase C §2.)
- **Duel records carry no provenance** (who moved, Ai's seat, its knowledge), so "Ai beats players" cannot be measured.
  (Closed, Phase C §1–§2: `Provenance` on every entry, `DuelResult` per finished duel, `duel_records`.)
- `hand_odds` is wrong when `cards` and `and_cards` overlap, and the fact-checker treats it as ground truth.
  - Ai World's `openings` counts overlap exactly (`HandCounter`): route `hand_odds` through it.
- Combos are never round-trip verified.
- The reader's guide checks names only, never numbers.
- Expected match win is never backtested against logged rounds.
- A fact-check "OK" is not tied to the tool calls it claims.
- Drills stay "known" after the plan changes.

**Real-world data** (every lead below closed in 1.1.0, Phase B: `docs/phases/B.md` §1, §2 and §4)
- Alternate-art passcodes break copy limits, `hand_odds` and the field: copies are counted by passcode, not by card.
- OCG-only and unreleased cards read as TCG-legal.
- The field snapshot cuts each tier at 120 lists, so "last 45 days" mixes one or two weeks of regionals with 45 days of
  YCS.
- Expected match win drops the mirror and renormalises.
- It pools Game 1 with sided games.
- It counts lists illegal under the current banlist.
- The clustering chains hybrids into one strategy.
- `hand_odds` silently drops names it cannot resolve.
- `watch_video` returns cut reports as complete.

**Learning**
- The guide budget is bypassed in Refactor, Write, chat and the reflection.
- The review is all-or-nothing, and is lost if the app closes.
- A memory file over its cap traps Ai.

---

## 2. What the research says

Six research agents read the field. The claims below carry their sources; where an agent could not verify something it
said so, and so does this list.

### Yu-Gi-Oh! AI today
- **No published Yu-Gi-Oh! AI plays at expert human level.**
  - The strongest is **ygo-agent** ([sbl1996/ygo-agent](https://github.com/sbl1996/ygo-agent), MIT). It uses deep RL
    on ygopro-core with LLM embeddings of card text. It took about 100M games, roughly 32 RTX 4090s for 5 days, and is
    measured only against itself and a random player.
  - **WindBot** is hand-written rules per deck, with no search ([IceYGO/windbot](https://github.com/IceYGO/windbot),
    MIT; the EDOPro fork is AGPL).
  - Konami does not document Master Duel's AI.
- **One LLM-on-engine harness** ([ygo-harness](https://github.com/kwabenaa/ygo-harness)) has the model pick from the
  engine's legal-action menu. It solved **2 of 20** Master Rule 5 puzzles. Its failures came from information the
  interface left out (the phase, the full GY, the Extra Deck), not from misunderstood rules.
- **In nearby card games, search wins under a small budget.**
  - Hearthstone AI Competition winners used dynamic lookahead and IS-MCTS
    ([2020 results](https://hearthstoneai.github.io/files/slides/2020-Results-Hearthstone-AI-Competition.pdf)).
  - Legends of Code and Magic was won by depth-3 alpha-beta, until ByteRL's cluster-trained RL
    ([arXiv 2305.11814](https://arxiv.org/html/2305.11814)).
  - Search over sampled opponent decks raised a policy from 26.8 % to 51.35 % against ByteRL
    ([arXiv 2609.06816](https://arxiv.org/abs/2609.06816)).
- **LLMs alone are weak players, and the harness matters more than the model.** In PTCG-Bench, removing legal-action
  masking cost 118 Glicko and removing the history 115. Each is more than the gap between adjacent models
  ([arXiv 2605.29653](https://arxiv.org/abs/2605.29653)).
- **PokéChamp** puts the LLM inside minimax, as move sampler, opponent model and value function. It reaches an
  estimated top 10–30 % of the Pokémon Showdown ladder with no training ([arXiv 2503.04094](https://arxiv.org/abs/2503.04094)).
- **Perfect play is undecidable.**
  - For Yu-Gi-Oh!, deciding whether a computable strategy wins from a state is Π¹₁-complete, with decks legal today
    ([arXiv 2603.02863](https://arxiv.org/abs/2603.02863), a preprint).
  - MTG is undecidable too ([arXiv 1904.09828](https://arxiv.org/abs/1904.09828)).
  - So "optimal" here means *good under a budget*.

### Search under hidden information
- **Determinization (PIMC)** samples the hidden cards and searches each sample as if it were seen. It works in practice
  where leaf correlation is high ([Long et al., AAAI 2010](https://ojs.aaai.org/index.php/AAAI/article/view/7562)).
  - It suffers **strategy fusion**: it assumes it can see the hand, so it never values baiting or playing around a card.
  - **IS-MCTS** searches one tree over what can be known ([Cowling, Powley, Whitehouse 2012](https://eprints.whiterose.ac.uk/id/eprint/75048/1/CowlingPowleyWhitehouse2012.pdf)).
  - [OpenSpiel](https://github.com/google-deepmind/open_spiel) (Apache-2.0) has a reference implementation.
- **Ensemble determinization for MTG** matched an expert rule-bot in **under one CPU second**: a phone's budget
  ([Cowling, Ward, Powley 2012](https://eprints.whiterose.ac.uk/id/eprint/75050/1/EnsDetMagic.pdf)). It did this by:
  - splitting subset choices (materials, costs) into yes/no chains;
  - using slightly random rollouts.
- **Weighting samples by what the opponent did and did not do** beats uniform sampling (Policy-Based Inference, Skat:
  [arXiv 1905.10911](https://arxiv.org/abs/1905.10911)). The app's field prior (FieldBuilder over YGOPRODeck lists, the
  web's shares) is exactly such a model.
- **ReBeL, Student of Games and Pluribus** are sound, but cost a cluster ([ReBeL](https://arxiv.org/abs/2007.13544),
  [SoG](https://arxiv.org/abs/2112.03178)). They are not for a laptop.
- **Code World Models** (DeepMind, [arXiv 2510.04542](https://arxiv.org/abs/2510.04542); ICLR 2026) is the closest
  pattern to Ai World.
  - The LLM writes the game as code: moves, transitions, a guess at hidden state, a value heuristic.
  - It is tested against logged play, then searched with IS-MCTS.
  - It beat or matched the LLM playing directly in 9 of 10 games.
  - [Strategist](https://arxiv.org/abs/2408.10635) has the LLM improve its evaluation from self-play feedback.

### Rules engines and licences
- The engines and their licences:
  - **Fluorohydride/ygopro-core** is MIT, but useless without card scripts.
  - Its scripts ([ygopro-scripts](https://github.com/Fluorohydride/ygopro-scripts)) are **GPL-2.0**.
  - EDOPro's core ([edo9300/ygopro-core](https://github.com/edo9300/ygopro-core)) and
    [Project Ignis CardScripts](https://github.com/ProjectIgnis/CardScripts) are **AGPL-3.0**.
- **This MIT app may not bundle or link any of them.** The APK is one distributed program.
  - A user-installed engine reached as a subprocess over a narrow protocol is the defensible boundary (the GPL FAQ on
    pipes and sockets), on the desk only.
  - The research recommends against it anyway.
- **Game rules are not copyrightable; their expression is** ([US Copyright Office](https://www.copyright.gov/register/tx-games.html)).
  Effects written as the app's own structured data or code, from the card's meaning, are the clean path. It is the one
  `DuelRules` already takes ("physics only, never card text").
- **Fan simulators were taken down in 2016** ([Vice](https://www.vice.com/en/article/yu-gi-oh-online/)): Dueling Network
  got a cease-and-desist, and YGOPro's site went offline. A manual simulator like Duel is the lower-risk shape.
- **Hand calculators already model lines** as exact hypergeometric over OR-of-AND groups, with searchers:
  - [Duelists Unite's](https://forum.duelistsunite.org/t/ygo-probability-calculator/1753);
  - [SeyTi01's](https://github.com/SeyTi01/ygo-advanced-probability-calculator-web), MIT;
  - kai's own [Yugioh-Deck-Simulator](https://github.com/kaiharimoto/Yugioh-Deck-Simulator).

  Ai World's `openings` and `optimize` do this exactly, overlapping groups and all, with a seeded simulation as a
  self-check.

### How an agent learns without new weights
- **[Reflexion](https://arxiv.org/abs/2303.11366)** keeps reflections between trials. It reached 91 % pass@1 on
  HumanEval, against 80 % for GPT-4.
- **[Voyager](https://arxiv.org/abs/2305.16291)** keeps a library of verified, runnable skills, recalled by the meaning
  of their description. A skill enters the library only once a critic confirms it worked.
- **[ExpeL](https://arxiv.org/abs/2308.10144)** turns experience into insights.
- Every one of them depends on an outside signal. **Without one, self-correction does not help**
  ([Huang et al.](https://arxiv.org/abs/2310.01798)).
- **Memory in two layers.**
  - Episodic: reports, logs, replays.
  - Semantic: the guide, insights, USER.md.
  - A small always-in-prompt block over searchable stores ([MemGPT/Letta](https://arxiv.org/abs/2310.08560)), ranked by
    recency, importance and relevance ([Generative Agents](https://arxiv.org/abs/2304.03442)).
  - The app has the bounded markdown, Recall and Compaction already. What is missing is retrieval by meaning, and a
    *verified* flag on each insight.
- **Evals** ([Anthropic](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)):
  - start with 20–50 real failures;
  - keep capability evals apart from regression evals;
  - grade by code where possible;
  - report pass^k, not only pass@k;
  - read the transcripts.
- **Model judges are biased** by position, length and their own answers ([Zheng et al.](https://arxiv.org/abs/2306.05685),
  [Wang et al.](https://arxiv.org/abs/2305.17926)).
- **Break coaching into atomic claims and check each with tools.** For chess commentary, without tools GPT-5.4 got
  22 % of sub-claims wrong ([ACT-Eval](https://arxiv.org/abs/2608.04240)).
- **Lichess** scores a move by the drop in win chance: 0.3 is a blunder, 0.2 a mistake, 0.1 an inaccuracy
  ([accuracy](https://lichess.org/page/accuracy)). It is a template for a misplay finder once positions have values.
- **Reasoning models told to win against an engine often hack the environment**
  ([Palisade](https://arxiv.org/abs/2502.13295)). Ai's knowledge cap and the redaction in the duel exist for this reason;
  keep them.

### Data
- **Matchup records** (real match-by-match results) exist for retro formats on
  [Format Library](https://formatlibrary.com/api/replays?page=1&limit=1), an undocumented JSON API that links
  DuelingBook replays to winner and loser deck types.
  - For the current format, the public sources give placements and lists only: YGOPRODeck, Konami's YCS standings, the
    MDM/yugiohmeta API.
  - The app's own Prep log and Duel replays are its first-party match records.
- **Rulings:** the [YGOrg Q&A API](https://db.ygorganization.com/about/api) holds Konami's OCG Q&A as JSON, with English
  translations and a `translationStatus`.
  - It is keyed by Konami ID, which YGOPRODeck's `misc_info.konami_id` maps from passcodes.
  - It is cached by revision; never crawl it.
  - Keep Yugipedia (CC BY-SA, attributed) for the TCG view.
  - **Shipped in 1.0.98**: `rulings` reads it first (`YgoOrg`): the card's FAQ notes and its newest Q&As (or those
    shared with a second card), each with Konami's date and its translation status, the site's TCG warnings quoted,
    and the OCG caveat on every answer; Yugipedia follows. The name goes to an id through the site's own English
    name index, kept a week, so no passcode map is needed. Using `X-Cache-Revision` and `/manifest/<rev>` instead
    of the week is a later refinement.
- **Banlists by date:** Yugipedia's `{{Limitation list}}` template carries start and end dates
  ([API](https://yugipedia.com/api.php)). That allows a past format to be replayed in Ai World.
- **Never Konami's Neuron.** Its terms forbid building a database by downloading
  ([ToU](https://legal.konami.com/games/neuron/terms/tou/en/)).
- **Never DuelingBook's replays.** A captcha guards them against grabbing bots.
- **Master Duel has no export.** EDOPro's `.yrpX` is documented, but its engine is AGPL. A clean-room header reader is
  possible; linking is not.
- **YGOPRODeck allows 20 requests a second**, with a one-hour block past it, and asks for local copies
  ([guide](https://ygoprodeck.com/api-guide/)). The art library already keeps to this.

### A computer for an agent
- **One append-only log of actions and observations, with every pane a view over it.** This is how
  [OpenHands](https://docs.openhands.dev/sdk/arch/events) works, and Devin's "Follow"
  ([docs](https://docs.devin.ai/work-with-devin/devin-session-tools)). Ai World is built this way: `WorldEvent`, the
  Activity pane, Follow.
- **Checkpoints** of files, conversation and memory, previewable before restoring
  ([Replit](https://docs.replit.com/replitai/checkpoints-and-rollbacks)). Ai World does not have them yet.
- **A model's written reasoning is not a faithful trace.** Claude 3.7 Sonnet named the hint it used 25 % of the time
  ([Anthropic](https://www.anthropic.com/research/reasoning-models-dont-say-think)). So:
  - the Thoughts pane is Ai's own account;
  - the proof is the code that ran, its seed and its output;
  - a number in the chat should link to its run.
- **Rhino 1.7.15.1 is the right engine.**
  - Interpreted mode is the only one where the instruction observer works, and the only one Android runs.
  - `initSafeStandardObjects` plus a total ClassShutter.
  - The stop is a `java.lang.Error`, which a script's `catch`/`finally` cannot swallow while
    `FEATURE_ENHANCED_JAVA_ACCESS` stays off.
  - It already carries the fix for CVE-2025-66453.
  - **Never put a live `java.lang.Class` in scope.** CVE-2026-35482 escaped a sandbox exactly that way.
- **Python** ([-I](https://docs.python.org/3/using/cmdline.html)) is isolation from the environment, not a sandbox
  (PEP 578 says the same of audit hooks). That is why it is off until the person allows it.
  - Further limits: Windows Job Objects (kill on close, memory, process count) and `RLIMIT_*` on Linux.
  - macOS does not enforce `RLIMIT_AS`.
  - Kill descendants before the parent (`ProcessHandle.descendants()`).

---

## 3. The roadmap

The whole program's phased plan, with the foundation tracks under it, is `docs/ROADMAP.md` (from 1.0.98); this section is the Ai part of it as first written.

Each phase is one or a few releases, ordered by what it unlocks. Each item names the goal it serves:
- **L**: Ai learns;
- **P**: Ai plays;
- **C**: Ai coaches;
- **B**: Ai builds.

### Phase 0: the foundation (shipped, 1.0.97)
- **Ai World**: files, editor, terminal, boards, thoughts and activity, live; JavaScript everywhere, Python on the desk.
- **Instruments**: `openings`, `ratios`, `optimize`, `draws`, `combos`, `siding`, `card_web`, `composition`,
  `matchups`.
  - Each states its method, checks itself, and draws its boards.
  - `ygo.use` lets Ai build its own library in `lib/`, to the same standard.
- **The fixes above.**

### Phase 1: lessons that cite their evidence (L, small to medium; items 1–3 shipped in 1.0.98)
1. **The confirmed open fixes** in §1, the learning-integrity ones first:
   - the run pinned to its deck;
   - sync merging memory by entry;
   - the guide re-sent after compaction;
   - Stop writing real results.
2. **A verified lesson carries its run.**
   - A guide entry that claims a number cites the board (and seed) it came from.
   - `GuideFacts` re-runs the instrument when the deck changes, and marks the claim *stale* or *confirmed*.
   - A claim with no run is shown as Ai's opinion.
   - This is Voyager's critic and ExpeL's insights, with code as the critic.
3. **`hand_odds` through `HandCounter`**, so the fact-checker's ground truth is exact for overlapping groups.
4. **The evaluation harness** (a new instrument set and a Settings page). Graded by code, with pass^k per connection:
   - opening-hand questions with exact answers;
   - ruling questions with known answers (house rulings, YGOrg);
   - decklist reads from pictures;
   - puzzle positions.

   It answers "which model should I trust with this deck?", and turns every future change into a measured one.

### Phase 2: the duel as a measured game (P, C, medium)
1. **Provenance on every duel record**: who moved, Ai's seat, its knowledge setting, and the opening roll's decision.
   Prep's first/second taken from the roll. Without this, "Ai beats players" cannot be counted.
2. **Verify and fix the duel leads** in §1: own-seat moves on hidden cards, the guest's seat, combo records, `at`
   re-dealing.
3. **The table in full for Ai.** The research's biggest cheap gain, worth more than the gap between models:
   - the brief gives phase, every public zone, stats, the Extra Deck and the history;
   - the legal moves are offered as a menu from `DuelVerbs`.
4. **Ai's guide at the table**, read in the duel's mode.
5. **Puzzles**: positions with a known best line, checked by `DuelRules`, as the play eval.

### Phase 3: a forward model of the deck's own cards (P, L, B, large: the main lever)
The research's one clear conclusion: strength comes from searching an executable model, and the model has to be the
app's own, because the engines that exist are copyleft.
1. **Effects as code, written by Ai, for the decks in view.**
   - Ai writes each card's effect in Ai World (`lib/effects/<passcode>.js`), against a small effect API over `DuelRules`:
     costs, targets, summons, searches, chains, once-per-turn.
   - This is the Code World Models pattern, scoped to one deck and its field.
2. **Tested against the deck's own record.**
   - Each saved combo (`ComboRunner`) and each replay is a test the effect code must reproduce.
   - An effect is *verified* only when its tests pass.
3. **The goldfish simulator** on top: thousands of opening hands played out by the verified effects, with the end
   boards counted. "This line gets there 63 % of the time", with its seed.

### Phase 4: search (P, large)
1. **Determinized search over the opponent's interruptions.**
   - Their hand traps and when they use them are sampled from the field prior (`FieldBuilder`, the web's shares).
   - Samples are weighted by what the opponent has shown and not shown.
   - Lines are scored by regret.
   - Subset choices are split into yes/no chains; rollouts are lightly random.
   - The budget is a phone's: about one CPU second a decision.
2. **IS-MCTS where hiding and baiting matter**: which card to lead into a possible Ash. A port of OpenSpiel's
   (Apache-2.0), or Python on the desk.
3. **The LLM inside the search**, as PokéChamp and Strategist use it: proposing moves, modelling the opponent, writing
   and tuning the value heuristic from self-play in Ai World. Never picking moves alone.

### Phase 5: coaching on measured numbers (C)
1. **The misplay finder**: a replay scored by the drop in value at each decision, with Lichess's thresholds as the
   template. Until Phase 4, by departure from verified lines.
2. **Claim-by-claim coaching**: every coaching answer split into atomic claims, each checked by an instrument or the
   rules (ACT-Eval).
3. **Expected match win backtested** against the logged event rounds. Drills tied to the plan's version.

### Phase 6: building (B)
1. **`optimize` against the field**: hand traps against the field's interruptions, the side deck chosen by the matchup
   matrix, the main deck's ratios by the goldfish's end boards.
2. **Correct data under it.**
   - Alternate-art passcodes counted as one card.
   - Release and format data, so an OCG-only card is not TCG-legal.
   - Banlists by date, from Yugipedia's template, so a past format can be replayed.
   - The field's lists checked against the current list.
3. **Match records where they exist**: Format Library for retro formats, the app's own logs for the current one.

### Carried from the old roadmap
- Art recognition for name-less screenshots (perceptual hashes from `ArtLibrary`).
- Parallel helpers.
- Model routing: a cheaper model for look-ups and checks.
- Proactive notes when an edit breaks a combo the guide relies on.
- Overnight research with a notification.
- Memory by meaning (embeddings for `recall`).
- Live event mode between rounds.

### Ai World's own next steps
- **Checkpoints**: files, boards and the conversation, previewable before restoring.
- **A number in the chat links to the run** that made it.
- **Python's limits**: Job Objects on Windows, `RLIMIT_*` on Linux, descendants killed first.
- **Seeded self-play tables** with a choice of who goes first, and a fork of the live position.

### What the app will not do
- Bundle or link ygopro-core's scripts, EDOPro's core or Project Ignis' scripts (GPL/AGPL).
- Scrape Konami's Neuron.
- Grab DuelingBook replays.
- Copy Konami's rulebook.
- Let Ai loosen its own safeguards.
