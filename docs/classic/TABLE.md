> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

# What DuelingBook knows, and what to do about it

Research notes and a design, from reading how the interface most Yu-Gi-Oh!
players actually use is built — and where this app already beats it, where it
loses badly, and what the difference says about what to build next.

## 1. What DuelingBook proves

**Manual is the right choice, and it is not a compromise.** DuelingBook does not
resolve card effects. You press draw to draw. You press attack and pick a
target. Every simulator that automates effects (Master Duel, EDOPro) can only
ever play the cards someone has scripted, which is why the competitive practice
scene lives on the manual one. This app's play stage made the same call — "the
rules of the game living entirely in your head, which is what a table is" — and
the research confirms it is the majority position rather than a shortcut.

**The hot path is searching your deck, and typing beats browsing.** The single
most frequent non-trivial action in modern Yu-Gi-Oh is fetching a specific card
out of a sixty-card deck, and DuelingBook's native flow for it — open the deck
viewer, scroll, click, menu, "add to hand" — is slow enough that the community
built a command layer on top of it. `/search <name>` adds a card from deck to
hand. `/dig <name>` views the deck filtered by name. Then `/send` to mill it,
`/ban` to banish it, `/atk` and `/def` to summon it, `/st` to set it, `/ex <n>`
to excavate. Most of those are a *third-party extension*, which is the tell: the
native interface was slow enough that people wrote their own.

**Its own users say the menu is the problem.** One extension exists to add
"fully customizable hotkeys" across eighteen-plus commands specifically to
eliminate "repetitive clicking through menus"; another advertises letting you
"left-click a card to interact with it in many ways without needing the janky
menu", and unlocks thirty-plus card actions the base client does not expose at
all. A long drawer of options attached to every card is the thing DuelingBook is
most criticised for.

**And the competing manual mode wins on exactly that axis.** YGO Omega's manual
mode is described as easier "because you just need to point and click to the
location where you want to move a card to rather than select from a long drawer
of options."

That last sentence is worth reading twice, because it is a description of a
design decision this app already made.

## 2. Where this app already wins

- **Movement.** `DropTargets` resolves the finger's position to one intent, the
  highlight draws that value, and the release commits the same value. There is
  no drawer. This is the thing Omega is praised for and DuelingBook is
  criticised for, and it is already the deepest rule in `docs/classic/DESIGN.md` §10.
- **Undo.** Two hundred levels, over an immutable field. DuelingBook's is far
  weaker, and for combo practice — where the whole activity is "try the line
  again from three steps back" — undo is not a convenience, it is the feature.
- **The deck builder.** Lenses, exact opening rates, goals stored with the deck.
  Nothing on DuelingBook is close.
- **Feel.** Nothing else in this space has a card with a thickness, a light and
  a cast shadow, or a haptic vocabulary.

## 3. The hole

**Nothing in this app can ask for a card that is not on top of a pile.**

`PlayField` already exposes `playFromDeck(index, at, position)`, and the same
for the extra deck, the graveyard and the banished pile. The domain has been
able to play an arbitrary card out of any pile since it was written. But
`MatInput.whatIsUnder` returns `DragOrigin.Pile(slot, 0)` — always index zero —
so no gesture in the app can name anything but the top card.

The consequences are worse than "search is missing":

- You cannot **search your deck**, the most common action in the game.
- You cannot **choose an extra deck monster**. Every Xyz, Synchro, Fusion and
  Link play is unreachable, which is most of what combo practice *is*.
- You cannot **read your own graveyard** — you can peek its top card and nothing
  else — which decides whether half the deck's effects are live.
- You cannot **look at the banished pile**, same problem.

This is not a domain change. It is a way of asking. That is the whole of the
work, and everything below is downstream of it.

## 4. The fan

One gesture, one surface, and it reuses everything.

**Hold a pile and it fans out.** The one-finger hold already means "let me look
at this" — it is the peek. On a *pile* it spreads the pile into a row across the
near half of the table, above the hand and in front of the board. The board
stays visible and dimmed rather than covered, because §8 of the handbook is
explicit: anything requiring you to look at the deck while you do it must not be
a sheet.

This also resolves an existing awkwardness. The peek is currently refused on the
deck, on the grounds that the top of your own deck is the one card a goldfish is
only honest without. That reasoning does not apply to a fan: fanning your deck
is a *search*, which is a legal, public, everyday action. The deck stops being
the one pile you cannot interact with.

**Order says what the pile is.** The graveyard and the banished pile fan in
recency order, because that order is real and it matters — which card was sent
first is a fact about the game. The deck does **not** fan in deck order, because
deck order is secret and showing it would leak the shuffle. It fans in the order
of the **current lens**: the user's own roles, or archetype, or type, or copy
count. The deck builder already computes that partition (`core/deck/DeckLens.kt`),
and searching your deck through the same lens you built it with is something no
other simulator can do, because no other simulator knows what a starter is.

**Narrowing is tapping, not typing.** DuelingBook's command layer proves typing
beats browsing on a keyboard — so on desktop, type and the fan narrows. But this
app is for a tablet, and a search that demands an on-screen keyboard has already
lost. So the lens keys become filter chips along the fan: tap *Starters* and the
fan narrows to your starters. Tap *Extenders*. That is the same reduction as
typing four letters, with no keyboard and one touch, and it is only possible
because the deck already carries the labels.

**Taking a card is a drag, not a menu.** You drag a card straight out of the fan
onto the mat, and every existing rule applies unchanged — `DropTargets` resolves
it, the indicator shows it, `DropCommit` carries it out. Searching to hand,
milling to the graveyard, banishing from deck, summoning out of the extra deck
and setting from the deck are then *the same gesture aimed somewhere different*,
rather than six commands to remember. `/search`, `/send`, `/ban`, `/atk`, `/def`
and `/st` all collapse into one motion.

**Closing the deck fan shuffles the deck.** Because you just searched it. It is
correct by the rules, it costs nothing, and it stops the tool teaching a habit
that would lose a game. The graveyard and banished fans do not shuffle, because
those piles are ordered and public.

## 5. Everything else, mapped

Ordered by what it would change about a practice session, not by effort.

**Done since this list was written**, as eight changes kai asked for in one go:
card text on a hold (#3, and it *stays up* — a held finger is a glance, not a
read); two-handed play, which was not on this list at all and is the largest of
the eight; a hand you can arrange by dragging along it; two fingers to set a card
face-down, with the zone deciding whether it lies sideways; a tap that declares
an effect, which is #8's cheap half — the announcement without the chain state; a
camera you can switch off, because the felt is most of the screen; a card put
back into the gap it came out of; and the fan actually closing when you drag a
card out of it, which had never worked at all.

**And four more since**, on kai's report from the tablet. Two-handed play is now
**ten**-handed — *"let's expand it to 10 fingers"* — which cost one line
(`MatDesk.MAX_LANES`) because everything under it was already collection-shaped,
and cost the three-finger guards, which now only fire on bare felt. A hand card
is grabbable over the whole shape it is *drawn* as rather than over a rectangle
on the felt, which at his tuning had left 28 per cent of every hand card dead to
touch. A drop lands where the card is drawn rather than under the finger holding
it — which is what "a set monster lands in attack position" turned out to be,
because a card out of the hand is drawn most of a card up-table of the finger and
the drop was resolving a whole row nearer the player. And closing a spread no
longer leaves forty cards parked invisibly in the middle of the board, which is
why the top of the deck used to fly to the graveyard from nowhere.

**And four more after that**, all from one session on the tablet. A card taken
out of a spread can reach the board underneath it: "put back" is not a target
until the card has actually left its slot, and even then it only wins while no
zone is pulling harder. A pile stays on the table while its top card is in the
air — it is drawn one card thinner rather than not at all. Two hands out of one
deck both land their own card, because the release asks where the held card is
*now* instead of refusing when the index it remembered went stale. And the hand
and the open spread both make a real gap where the card is going, rather than
drawing a caret beside a row that never moves.

1. **Tokens.** A real functional gap — a large share of modern combos put tokens
   on the board and they are currently unrepresentable. Conjure one onto the mat;
   it behaves as a card with no back.
2. **A scrubbable history.** The undo stack is already two hundred immutable
   fields deep, which means the app is one slider away from letting you scrub
   backwards and forwards through a combo the way you scrub a video. DuelingBook
   has replays of finished duels; nobody has this *while you are practising*, and
   it is close to free.
3. ~~**Card text on the peek.**~~ Done — `ui/play/CardReader.kt`. It became a
   panel that stays up rather than text beside the lifted card, and the reason is
   worth keeping: reading four sentences of ruling text with your thumb on the
   glass is not reading, it is holding still.
4. **Excavate.** Turn the top N of the deck face-up in a row without committing
   to any of them — the `/ex` command, as a drag off the deck.
5. **Mill by number.** `/send`, as a drag from the deck to the graveyard with a
   count.
6. **A real life-point pad.** The top bar has four fixed buttons; DuelingBook has
   a calculator with operators because damage is arithmetic. Make it a pad, and
   put it on the table rather than in a bar.
7. **Dice and a coin.** Both are real game actions, not decoration, and both are
   the best possible showcase for the rigid-body solver in `docs/classic/AAA.md`.
8. **Activation as a state.** Half done. A tap now *declares* a card — its name
   in the bar, a chime, and the card rising and settling — which is the moment.
   What is still missing is the **chain**: a declaration that stays up until it
   resolves, so that what is currently on the chain is something the table holds
   for you rather than something you remember. The bump already goes through the
   pose springs, so holding it up is a flag rather than an animation.
9. **Attach as material by gesture.** `DropIntent.Attach` exists in the domain
   and has no idiom. Once the fan exists, the extra deck is reachable and Xyz
   play becomes the common case rather than an edge case.
10. **The card menu is our own janky drawer.** `CardActions` is twelve options in
    a Material `DropdownMenu`, which is exactly the pattern DuelingBook is most
    criticised for and exactly the component §8 says to restyle on the way in.
    Most of its entries are destinations, and destinations belong on the table:
    a card lifted and dropped on the graveyard should not need a menu at all.
11. **A pile's count, always visible.** Deck, graveyard and banished all show a
    number in every other simulator. Ours shows a pile height and nothing else.
12. **Search-and-shuffle honesty for the extra deck too** — no shuffle, since the
    extra deck is public information.
13. **A "reveal" that means something in solo.** Turning a card face-up to the
    room is meaningless alone, but *marking* a card — this is the one I am
    holding for the trap — is genuinely useful in practice.
14. **Phase-aware nothing.** DuelingBook's phase buttons do not enforce anything
    either. Keep it that way; the phase is a note to yourself.
15. **Timers.** Competitive players practise against the clock. Low priority,
    trivially cheap, worth remembering.
16. **Favourites.** DuelingBook lets you hold right-click to favourite a card so
    you can find your staples across every deck. Our equivalent already exists
    and is better: the lens. Do not build favourites; make the lens do it.
17. **Hotkeys for the fan.** Every gesture ships with both idioms, so the fan
    needs keys: one per pile, plus type-to-filter on desktop.
18. **Multi-select.** Taking three cards out of the graveyard at once is common
    (banishing for a cost). The mat has no notion of a selection — though two
    hands now take two cards at once, which covers the common case of two and
    makes a selection model harder to justify than it was.
19. **Sleeves and a playmat.** DuelingBook's whole cosmetic economy. We have card
    backs already; a user playmat is in `docs/classic/AAA.md` §6.
20. **Spectating, chat, rated duels, replays of others.** All require an
    opponent. Out of scope until this stops being a solo tool, and possibly
    forever — the thing this app is good at is *practice*.

## 6. What not to copy

- **The drawer.** Every destination that can be a place on the table should be a
  place on the table. The menu is for what is left over.
- **Commands as the fast path.** They are a symptom. If the direct manipulation
  is good enough, `/search` never needs to exist — and if it does need to exist,
  the direct manipulation is not good enough yet.
- **Modal viewers.** The reason DuelingBook's deck viewer is slow is not the
  scrolling, it is that it is a window over the game. You choose a card *because
  of* what is on the board.
