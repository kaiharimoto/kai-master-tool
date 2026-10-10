# Visual red team of the deck tools (before Phase G)

kai asked on 2026-10-10 for a visual red team of the underdeveloped features, for improvements rather than bugs. This
note is its companion to `G-REDTEAM.md`, the code red team of the same tools.

## How it was done

- The app's own studio drew 25 views at 1920 × 1080 in the paper theme, plus four at phone size.
- The pages: Builder, Shootout, Gameplay Mapper, the Effects app and the goldfish, Ai World's instruments, Prep, Siding,
  Format.
- Every view was read for:
  - what it shows well;
  - what a player optimizing a deck needs from it and cannot get;
  - where its space goes.
- The write-up with every picture and five mockups drawn in Master UI is a private page:
  https://claude.ai/artifact/NP99Rn4dWAA6dbiWq8Jhtt.
- `app/studio/studio-regional.ydkw` is the field the Prep, Siding and Format shots load (the studio runs in `app/`, so the flag reads `--ydkw=studio/studio-regional.ydkw`):
  - the lab deck;
  - the K9 Vanquish Soul list `ShootoutStudio` builds;
  - a Yubel list taken from the pool.

```
tools/shoot.sh --page=builder --groups=true [--select=main:3]
tools/shoot.sh --page=shootout --shootout=demo --shootout-view=results|trial|setup [--shootout-target=matchup] [--shootout-teach=trust]
tools/shoot.sh --page=mapper --mapper=demo [--mapper-show=overview] [--mapper-tab=starters] [--mapper-moment=first]
tools/shoot.sh --effects=pane|goldfish-result
tools/shoot.sh --page=world --world=demo --world-app=instruments|hand-odds|matchups
tools/shoot.sh --page=prep --ydkw=studio/studio-regional.ydkw --prep-demo=true --prep-tab=PLAN|PRACTICE|DRILLS
tools/shoot.sh --page=siding --ydkw=studio/studio-regional.ydkw --siding=0 --against=1
tools/shoot.sh --page=format --ydkw=studio/studio-regional.ydkw [--matchups=true]
```

## What the pictures have in common

1. **One deck, one moment.**
   - Every page measures one deck. None shows what a change does: no before and after, no ±1 beside a copy count, no
     "compare with".
   - Ranges appear on Shootout and nowhere else. Prep's "49 %" from twelve games reads as sure as Shootout's "76 %" from
     thirty-three.
2. **The numbers live far from the decision.**
   - The exact ratio tools sit in Ai World behind a typed grammar.
   - Shootout's per-card worth never reaches the builder or Siding.
   - Effect coverage decides what the Mapper and the goldfish can say, yet only the Effects app shows it.
3. **Paper where the data should be.**
   - Shootout fits 3½ of 16 cards on a screen, and the whole page is 4,200 px tall.
   - Format's field is three tiles on an empty page.
   - The Mapper's starter table leaves about 1,000 px between a starter's name and its numbers.

## The five changes that matter most

Listed in the order to build them. Each is drawn on the page.

| # | Change | What it gives |
|---|---|---|
| A | **Shootout's cards on one shared axis.** One plot per column, a continuous zero rule, 26 px rows, "as your draw" a hollow mark on the same row. | The whole deck on one screen (470 px instead of 4,200), every card comparable at a glance. |
| B | **Format and Prep as "where the event is won or lost".** <ul><li>The expected match win with its range and n.</li><li>The Top 8 chance: at a 48 % match win, 6-1 or better in 7 rounds is about 5 %.</li><li>The field as one strip.</li><li>A row per deck: your match win (range), games, siding status, and what it costs you (share × (1 − win)).</li><li>"Practise next".</li></ul> | The target a deck is optimized against. In the demo the smaller share, Yubel, costs more than K9. |
| C | **The inspector's "In this deck".** The stepper with each stored question at −1 / now / +1, first and second (exact), Shootout's verdict, and how the field plays the card. | The copy count decided where it is changed. The deck's stored goals (`HandGoal`) are drawn in Neue for the first time. |
| D | **The Mapper led by board depth.** <ul><li>The share of hands ending on ≥1, ≥2, ≥3 interruptions, with ranges.</li><li>A coverage strip: 4 of 30 cards play.</li><li>"Compare with…" answered on paired hands.</li></ul> | One curve to compare lists by, and an honest floor. |
| E | **Side deck coverage on Siding.** Side card × matchup × turn, copies brought in, and the share of the field each meets. | In the demo, 8 of 15 copies come in against nothing and Yubel (35 %) has no plan. |

## Page by page

### Builder (01)
- **Label the group percentages.** ">99 %" and "58 %" are "at least one going first". Show first and second (Handtraps: 58 · 65).
- **Pin the deck's questions in the empty band above Groups**, recomputed per edit, with the change since the last step.
- **Keep the odds still.** They sit on slides 2–4 of a carousel set to Auto.
- **Give the inspector's first screen to the deck (mockup C).** Today the art takes 540 px and the copy count sits below
  the fold.
- **Put one moving number in the deck's header row**, e.g. "Opens engine + trap 57 % · 65 %".

### Shootout (09)
- **The shared-axis plot (mockup A).**
- **Say first or second.** Against K9, Game 1 first is 52 % and Game 1 second is 69 %; the page never says "go second".
- **Shrink the big boxes to one strip**, with the settled count as progress.
- **Give each call a next step.** "Try −1 in the builder"; "Side it out going first".
- **Move Pairs beside the cards.** They are two rows at the bottom of a 4,200 px page.
- **Use the trial's empty band.** 270 px sit above the answers: show group chips under the cards and the hovered card's
  text.
- **Show a first-run preview.** Setup is two thirds empty.

### Gameplay Mapper (10)
- **Lead with board depth (mockup D).**
- **Fold the 20 of 32 boards that do nothing.**
- **Show coverage in the header.**
- **Add "Compare with…".**
- **Fill the starter table's middle.**
  - the best board each starter reaches;
  - a "Without it" column;
  - a mark for whether the card is in your Starters group;
  - the exact chance of opening any mapped starter.
- **Flow the board cards in a grid.**
- **Before the first run, show coverage and time.**

### Ai World: Effects, the goldfish, the instruments (08)
- **Give the goldfish's headline its real range.** "At least 33.6 %" with a quarter of hands undecided is 33.6–58.4 %.
- **Draw coverage as the deck with inert cards dimmed**, not three lines of names.
- **Draw a kept run under the new one, with the difference.**
- **Order "What to write first" by the hands each card would open.**
- **Answer in the Instruments window.**
  - Build conditions from chips of the deck's groups, not a typed grammar.
  - Offer the same answer in the builder.
- **Charts of rates draw their range.** Ai's Matchup tracker lists 21–94 % and draws flat bars.

### Prep (05)
- **Show "Your odds at this event" beside the countdown.** At 49 %, 6-1 or better is about 5.6 %; at 55 %, 10.2 %.
- **Give the expected match win its range and n.**
- **Draw rates by turn as dots with ranges.** Show n under each, and grey out cells under five games.
- **Add "Practise next" and a first-or-second column.**
- **Fit the drill board to the screen.** At 1080p the side deck falls below the fold.

### Siding (03)
- **Mark weak cards on the board.** Show Shootout's worth for the stratum under each copy, and the playbook's
  dead-in-matchup notes.
- **Show side deck coverage (mockup E).** Give each side card a tally of the plans it is in.
- **Show what the plan does to the odds** in the turn's header ("Hand traps ≥1: 58 % → 72 %").
- **Fill the opponent's covers by default** with its three most-copied cards.

### Format (04)
- **Draw the field as the event (mockup B).**
- **Mark where each share came from:** its source, its age and its trend.
- **Make each matchup row everything about one opponent.**
  - the plan as out → in thumbnails;
  - Prep's logged rate;
  - Shootout's per-turn rate.

### On a phone
- **Prep: the answer first, the form behind "Log a game".**
- **Give the Mapper a tab.**
- **Label the group percentages here too.**
