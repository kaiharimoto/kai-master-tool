# kai's master tool — cross-platform app

A clean-room rebuild of the deck builder as a single Kotlin codebase targeting
Android and desktop. Not a port of the HTML tool: the domain rules were
rewritten from the rulebook up, with tests, and the UI was designed for touch
rather than adapted to it.

The root [`README.md`](../README.md) is the front page - what the app does and
how to install it. This file is how it is put together. The reasoning behind the
decisions is in [`docs/`](../docs).

## Modules

| Module | What it is | Depends on Google Maven |
|---|---|---|
| `core` | Pure Kotlin: models, YDK/YDKX codec, deck rules, search, API client, SQLite | No |
| `ui` | Compose Multiplatform screens shared by every platform | Yes |
| `androidApp` | The APK. Built for a Tab S11 Ultra; runs on phones too | Yes |
| `desktopApp` | JVM app, packaged as `.dmg` / `.msi` / `.deb` | Yes |
| `studio` | Draws the play stage to PNG headlessly. Opt-in with `-Pmastertool.studio=true`, ships in nothing | Yes |

`core` deliberately has no Compose and no platform code, so it compiles and its
tests run anywhere — including environments with no Android SDK.

## Building

```bash
# Domain tests. Works with no Android SDK installed.
./gradlew :core:jvmTest -Pmastertool.android=false

# Debug APK -> androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

# Desktop app
./gradlew :desktopApp:run
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### The Android toggle

Every Android artifact — AGP, `androidx.*`, the SDK — is served only from
Google's Maven. `settings.gradle.kts` detects whether an SDK is present and
skips the Android and Compose modules when it is not, so `:core` stays usable in
restricted environments. Android Studio and CI pick everything up automatically.
Force it either way with `-Pmastertool.android=true|false`.

iOS targets exist in the build but are off, and nothing ships from them; they
need a Mac and are enabled with `-Pmastertool.ios=true`.

### CI and releases

`.github/workflows/build-app.yml` runs on every push to `main` or a `claude/**`
branch that touches `app/`. Three jobs: `:core` tests with Android switched off
(so a failure there is the rules, not the SDK), the debug APK, and a desktop
build that catches genuinely platform-specific code the Android job cannot see.
The APK is uploaded as a run artifact.

`.github/workflows/shots.yml` is the play stage drawn to PNG on a runner, for
the pictures in the root README. Dispatch only, and on a `claude/**` branch -
see `tools/contact.py`.

`.github/workflows/release.yml` publishes a signed release. Trigger it by
pushing a `v*` tag, or from the Actions tab with a version number:

```bash
git tag v1.0.1 && git push origin v1.0.1
```

It derives `versionCode` from the version *name*, as
`100000 + major*10000 + minor*100 + patch`, verifies the APK carries the
committed signing certificate, and attaches `kai-master-tool-<version>.apk` to
the GitHub release.

**That formula is permanent.** An earlier scheme derived the code from the commit
count, which is not monotonic across branches: v1.1.0 shipped as 281 from a
281-commit branch, the next release came off a 61-commit branch and produced 61,
and every installed device refused the update. The `+100000` floor keeps the new
scheme above anything the old one ever emitted. Never go back to commit counts,
and keep the patch digit under 100 - 1.2.100 and 1.3.0 collide.

## Updating from GitHub

The Android app checks this repository's latest release on launch and whenever
you tap the version in the top bar. If a newer version has an APK attached, it
downloads it and hands it to Android's package installer.

- The first update asks you to allow the app to install unknown apps. That is a
  one-time per-app Android setting.
- Pre-releases are ignored by stable builds, so a test release cannot push
  itself onto a normal install.
- An unparseable or older tag never counts as an update.
- The desktop build does not self-update; it opens the release page instead.

This only works because every build is signed with the same **deliberately
public** keystore — Android rejects an update whose signature changed. See
`androidApp/keystore/README.md` for what that key does and does not protect.

## Design decisions worth knowing

**Extra Deck detection keys off `frameType`, not `type`.** A Pendulum Effect
Fusion Monster reads as a Pendulum monster but is summoned from the Extra Deck.
The frame carries the summoning mechanic; the type string does not.

**The `#ydkx-extended` block is preserved verbatim.** `.ydkx` files are plain YDK
text plus a JSON payload holding siding patterns and notes. The app does not
implement siding yet, so it round-trips that payload untouched rather than
dropping it — editing a deck on the tablet must not destroy work done on the
desktop tool.

**Alternate artwork passcodes resolve to the same card.** Deck files exported by
other tools frequently reference an alternate printing, whose passcode differs
from the one a database calls canonical. Every known passcode is indexed.

**All deck edits go through `DeckEditor`.** It is pure and total: copy limits,
banlist status and section legality live in one place, so drag, tap and stepper
cannot disagree with each other.

**A failed card-pool refresh never clears the cache.** An outdated pool beats no
pool at a venue with no signal.

**The builder has two arrangements, and one rule picks between them.** The
manifest was `userLandscape` until the app met a phone; it is `fullUser` now.
`core/layout/Posture.kt` is the whole decision - a window taller than it is wide
is `TALL`, everything else is `WIDE` - with no dp threshold, because a threshold
has to be re-chosen for every new device and the aspect ratio is what the two
arrangements actually turn on. A portrait tablet gets `TALL` too, which is right
rather than incidental. `docs/DEVICES.md` §6 is the authority.

Rotation therefore *does* recreate the activity, and the app still uses plain
remembered state holders rather than ViewModels: what survives a rotation is the
SQLite database and the preferences document, both of which are read back on the
way up. A screen's transient state is deliberately not worth preserving across a
turn of the device.

**Sorting a deck is an edit, not a view setting.** The stored order is exactly
what gets written back to `.ydk`, so a sort that only reordered the display would
leave the file disagreeing with the deck from then on. Being an edit also means
undo puts it back.

**Layout settings are one JSON document in the SQLite database.** No DataStore
(Android-only, would need a separate desktop path) and no settings library.
Storing them as a document rather than a column per setting means adding a
preference is a field, never a migration. Everything read back is clamped: a
weight of zero or NaN reaches `Modifier.weight`, which rejects both.

**A new table needs a migration file, and the failure is invisible on a fresh
install.** SQLDelight derives the schema version from the number of `.sqm` files,
and both driver factories hand `MasterToolDatabase.Schema` to the driver. Adding
a table to a `.sq` file alone leaves the version unchanged, so no migration runs
and the first query throws — on every device that already has the app, and on
none of the ones used for testing. `MigrationTest` upgrades a real old database
and compares it against a freshly created one; add a case to it whenever the
schema changes.

## Status

Shipping: deck builder with search and filters, drag and drop between the
pool and every deck section, per-section copy steppers and moves, adjustable and
collapsible deck panes with per-section sorting and card density, an inspector
you can page through the results in, deck statistics with opening-hand odds, a
deck-check panel that jumps to the card an issue names, a TCG/OCG toggle, deck
library, YDK/YDKX import, export and share.

On desktop: keyboard shortcuts throughout (press `?` for the list, which is
generated from the table that implements them) and a hover preview on any card.

Also shipping: the freeform **play stage** - a table where cards go anywhere,
stack, and set face-down, with ten simultaneous gesture lanes, searchable piles,
a hold-to-read card reader, and a free-flight camera over a room drawn by
`core/render/`. It replaced the goldfish screen. A second board - "Table", a
zone board over a `BoardState` - was built and then cut as redundant; the zones
survive it in `core/layout/BoardLayout.kt`, which still solves all ten of them.

Not yet built: siding patterns and shootout mode (both deliberately deferred, to
be redesigned from scratch rather than ported), PDF export, the deck showcase
stage, and autoscrolling a pane while dragging over its edge. `docs/TABLE.md` §5
is the ordered list.
