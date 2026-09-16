# Contributing

This is a personal tool that happens to be public. Issues and pull requests are
welcome; so is reading the code and taking ideas out of it. What follows is what
you need to know to change something without breaking a device that already has
the app installed.

## Start here: `:core` runs anywhere

```bash
cd app
./gradlew :core:jvmTest -Pmastertool.android=false
```

That is the whole setup. `:core` is pure Kotlin — models, deck rules, search,
layout solving, hand odds, the renderer's arithmetic, the gesture state machines
— with no Compose and no platform code, so it compiles and its tests run with no
Android SDK installed.

`:ui`, `:androidApp` and `:desktopApp` are a different story: every Android
artifact is served only from Google's Maven, so they need network access to it.
`settings.gradle.kts` detects whether an SDK is present and **skips those modules
when it is not**, which is why the command above works in a bare container.
Force it either way with `-Pmastertool.android=true|false`.

If you cannot build the UI locally, push a branch: `.github/workflows/build-app.yml`
compiles all three modules on every push and is the real compile check.

## The rules that are not negotiable

**Logic goes in `:core`, with a test.** Not in a composable. If a rule can be
stated without mentioning the screen, it belongs in `:core` and it belongs in a
`commonTest`. There are ~89 test files there and that is the reason the app can
be changed quickly.

**Every gesture ships with two idioms.** One for a finger, one for a pointer or
a key. Keyboard shortcuts are *data* in `core/input/ShortcutTable.kt` and the
in-app help sheet renders that table, so the two cannot drift apart.

**`versionCode` may only ever go up.** It is derived from the version name as
`100000 + major*10000 + minor*100 + patch`. The floor exists because an earlier
commit-count scheme produced a *lower* code from a shorter branch and every
installed device refused the update. Never go back to commit counts, and keep
the patch digit under 100 (1.2.100 and 1.3.0 collide).

**The SQLite schema version may only ever go up.** SQLDelight derives it from the
number of `.sqm` files. Adding a table means a `.sq` change *and* a new `.sqm`,
and `MigrationTest` has to prove that an upgraded database equals a freshly
created one. Never renumber or delete a migration once a signed build has
shipped — the failure is invisible on a fresh install and fatal on every real
device.

**Preferences are one JSON document, not a schema.** Adding a preference is a
field with a default (`UiPreferences`). It is never a migration.

## Before you touch the UI

Read **[`docs/DESIGN.md`](docs/DESIGN.md)** first. It is the handbook — palette,
type scale, spacing, motion, component rules, anti-patterns — with the reasoning
attached, and it will save you writing something that gets reverted. The play
stage has its own additions in [`docs/TUNING.md`](docs/TUNING.md) and
[`docs/DESIGN.md` §11](docs/DESIGN.md).

Two Compose facts have each cost this repo more than one debugging round, and
both are written on the files they bit:

- A gesture coroutine installed by `pointerInput` **outlives the composition that
  installed it**. A callback closing over a whole document therefore writes the
  document as it was when the screen opened. Hand back the one field that
  changed and do the read-modify-write inside the transform.
- Anything with a height reaches the screen through a projection, so **where a
  card is drawn and where a finger lands are two different places**. Hit-test
  against the quad the thing is drawn as.

## Changing the 3DS port

`3ds/` shares no code with `app/` and cannot — there is no Kotlin target for
Horizon OS. It shares the *arithmetic*, and it proves it:

```bash
make -C 3ds/test test     # host gcc, no devkitARM, about a second
```

`GoldenVectorExportTest` in `:core` writes `3ds/test/vectors/`, and the C
compiled with the host compiler asserts against the same files. **If a change in
`:core` moves a zone, the `vectors` CI job goes red — that is not a bug.** It
means the console's copy of the rule has to move too, and the regenerated vectors
belong in the same commit so a reviewer can see it happen.

One rule keeps all of that true: `3ds/src/core/` may never `#include` a libctru
header. See [`docs/PORT.md`](docs/PORT.md).

## Pull requests

- Say which module you changed and whether `./gradlew :core:jvmTest` passed.
- A change that reaches a device needs a release; a docs-only change does not.
  Say which one it is.
- Keep the diff to what the change needs. Drive-by refactors in a feature PR
  make it impossible to tell which line caused the regression.
