# kai's master tool — the app

One Kotlin codebase, one app: **Neue Master Tool** (`neue`), the deck builder in
Master UI, on Windows, macOS and Linux and — from v1.3.0 — as the Android APK.
The tablet app that came before it (`ui`, with its play stage) is *classic* and
retired: it is at commit `c2fc8d8` (`neue-v1.0.19`) and in `docs/classic/`.

The root [`README.md`](../README.md) is the front page - what the app does and
how to install it. This file is how it is put together.
[`docs/NEUE.md`](../docs/NEUE.md) is the authority on the app itself.

## Modules

| Module | What it is | Depends on Google Maven |
|---|---|---|
| `core` | Pure Kotlin: models, YDK/YDKX codec, deck rules, search and filters, hand odds, layout, the keyboard and mouse tables, API client, SQLite | No |
| `builder` | What the builder is, not how it looks: `DeckBuilderState`, `AppDependencies`, the updater seam, the image loader, the shader seam, the card foil. Files keep the `ui.*` packages they had in the old `ui` module | Yes |
| `neue` | **Neue Master Tool**: every screen, in Master UI; packaged as `.msi` / `.dmg` / `.deb` | Yes |
| `androidApp` | The APK: hosts `neue` in one activity, with the crash reporter and the emulator smoke test | Yes |
| `studio` | Draws the app to PNG headlessly (`tools/shoot.sh --neue`). Opt-in with `-Pmastertool.studio=true`, ships in nothing | Yes |

`core` deliberately has no Compose and no platform code, so it compiles and its
tests run anywhere — including environments with no Android SDK.

## Building

```bash
# Domain tests. Works with no Android SDK installed.
./gradlew :core:jvmTest -Pmastertool.android=false

# Debug APK -> androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

# Neue Master Tool
./gradlew :neue:jvmTest
./gradlew :neue:run
./gradlew :neue:packageDistributionForCurrentOS
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
(so a failure there is the rules, not the SDK), the debug APK, and Neue — its
tests, the Master UI law among them, and a `.deb`. The APK and the `.deb` are
uploaded as run artifacts. `.github/workflows/android-smoke.yml` boots a tablet
emulator and runs `NeueSmokeTest` against the APK, uploading its screenshot.

`.github/workflows/release-neue.yml` publishes Neue's installers on the `neue-v*`
track, always as a pre-release; `docs/NEUE.md` §5 has why.

`.github/workflows/shots.yml` is Neue drawn to PNG on a runner, for the pictures
in the root README. Dispatch only, and on a `claude/**` branch.

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

The Android app checks this repository's latest release on launch, as the
desktop builds check theirs. If a newer version has an APK attached, it
downloads it and hands it to Android's package installer.

- The first update asks you to allow the app to install unknown apps. That is a
  one-time per-app Android setting.
- Pre-releases are ignored by stable builds, so a test release cannot push
  itself onto a normal install.
- An unparseable or older tag never counts as an update.
- The desktop builds follow the `neue-v*` track instead; `docs/NEUE.md` §5.

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

**The APK is landscape, for now.** Neue is laid out for a wide window, so the
manifest is `userLandscape` until Neue has a phone arrangement of its own. The
classic tablet app had two (`core/layout/Posture.kt`, `docs/classic/DEVICES.md`
§6), and that decision will be re-made for Neue rather than inherited.

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

Shipping, on the desktop and the tablet: the deck builder with search, advanced
filters and card lists, drag and drop between the pool and every section, groups
that break the deck into pieces, the lenses and their exact opening odds, deck
statistics, the library with tags and covers, alternate and own artworks, zen,
YDK/YDKX/`ydke://` import and export. Keyboard shortcuts throughout (`?` lists
them, from the table that implements them), a mouse grammar and a touch grammar,
both tables too.

Not yet built: play mode (the classic play stage is retired; kai will rebuild it
inside Neue), a phone layout, siding patterns and shootout mode (deliberately
deferred, to be redesigned rather than ported), and PDF export.
