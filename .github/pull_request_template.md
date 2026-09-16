## What changed

<!-- One or two sentences. What is different after this, from a user's side? -->

## Which module

- [ ] `:core` — logic, with a `commonTest`
- [ ] `:ui` — Compose screens
- [ ] `:androidApp` / `:desktopApp`
- [ ] `3ds/`
- [ ] Docs, CI or tooling only

## Checks

- [ ] `cd app && ./gradlew :core:jvmTest -Pmastertool.android=false` passes
- [ ] New logic has a test in `:core`, or there is no new logic
- [ ] New gestures have both a touch idiom and a pointer/keyboard idiom
- [ ] `make -C 3ds/test test` passes, or `3ds/` is untouched
- [ ] Golden vectors regenerated in this same commit, if a `:core` rule moved

## Does this need a release?

- [ ] Yes — it reaches the device, so it needs a signed build
- [ ] No — docs, tests or CI only

<!-- If it changes stored preferences, the SQLite schema, or the deck-file
     payload, say so here: it belongs in the release notes so a surprise on the
     tablet has an explanation waiting. -->
