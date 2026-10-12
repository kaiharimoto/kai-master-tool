# `.nmtbackup` — everything the person made, in one file

A `.nmtbackup` is a zip of the person's work as the app reads it: every deck, every settings document, Ai's folder but its
keys, their own card pictures, presentations, duels and replays, worlds, Shootout's trials, deck versions and the effects
library (1.0.69, kai: "I have a lot of progress in my current version on desktop that might be outdated … how do we account
for that?"). It is never a copy of the database. A deck is written as a small JSON document and put back through the
library, and a settings document is decoded and stored again, so an old backup restores into any later version whatever
its tables look like. The manifest is `core/backup/Backups.kt`; writing and restoring are
`neue/backup/BackupCenter.kt` (`app/neue/src/sharedMain`), the same on the desk and on Android.

## When one is made, and where it is kept

- **On opening** (`BackupCenter.onOpen`, decided by `Backups.due`): the first time a version opens over existing work
  ("Before 1.1.95 (from 1.1.94)", or "Before 1.1.95" when no version was recorded), and once a week ("Weekly"). Existing
  work is a deck in the library or Ai's `MEMORY.md`. The last version seen is `<data>/backups/last-version.txt`.
- **By hand** (Settings › Backups, "By hand"), **on Export** ("Export", handed to the person as `application/zip`), and
  **before every restore** ("Before restoring"), so a restore can be undone.
- Kept in `<data>/backups/`, the newest ten (`Backups.KEEP`); older ones are deleted as a new one lands.
- Named by `Backups.fileName`: the local time as `yyyy-MM-dd HHmm`, a space, the reason with every character but letters,
  digits, `.` and `-` turned to a space and runs of spaces closed up, then `.nmtbackup` — for example
  `2026-10-01 0900 Before 1.0.69 from 1.0.68.nmtbackup`. Names sort by time. The name means nothing to a restore.
- Written to `.<name>.tmp` in the same folder and renamed when complete.

## The zip

An ordinary zip (`java.util.zip`, deflated). Entry names are relative paths with `/`. Only files are written, never folder
entries; in the folders below, a file or folder whose name begins with `.` and a file ending `.tmp` are skipped. Entries
are written in this order, `manifest.json` last:

| Entry | What it holds |
|---|---|
| `decks/<id>.json` | One per deck in the library (`BackupDeck`, below). |
| `prefs/neue.ui.json` | Neue's settings, `NeuePreferences` (the `neue.ui` document), Ai's settings inside it. |
| `prefs/deckbuilder.ui.json` | `UiPreferences` (the `deckbuilder.ui` document): the format and effect search among it. |
| `prefs/neue.webs.json` | Every web, `WebLibrary` (the `neue.webs` document). |
| `prefs/neue.prep.json` | Prep, `PrepDoc` as `PrepCodec.encode` writes it (the `neue.prep` document). |
| `ai/…` | `<data>/ai`, but what is this device's alone (`InboundPath.aiPrivate`: `credentials.*`, `run/`, `cache/`, `traces/`). |
| `custom-art/…` | `<data>/custom-art`: the person's own card pictures. |
| `present/…` | `<data>/present`, but every take (`<id>/takes/`, `TakePaths.syncs`). |
| `duel/…` | `<data>/duel`, all of it: the duel in play, replays, combos, records, rulings, puzzles and the rest. |
| `world/…` | `<data>/world`, but the Python helper's `.py` folders (`NeueSyncLocal.worldSyncs`). |
| `shootout/…` | `<data>/shootout` (`ShootoutPaths.FOLDER`): every deck's trials. |
| `deckversions/…` | `<data>/deckversions` (`DeckVersions.FOLDER`), `.json` files only. |
| `effects/…` | `<data>/effects` (`FxPaths.FOLDER`), what `FxPaths.syncs` allows: sources, compiled scripts, reviews, helpers, `asked.json`, `played.json`, `decks/*.json`, `goldfish/*.json`, `mapper/<deck>/*.json` — never the mapper's `train/`. |
| `manifest.json` | The manifest (`BackupManifest.NAME`), below. |

Nothing else of the data folder is read. Never in a backup, by being outside that list or excluded from it: the keys
(`secrets/`, and the old `ai/credentials.*`), the command-line apps' folder (`cli-run/`, and the old `ai/run/`), the
folders that are a device's own (`InboundPath.DEVICE_FOLDERS`: `secrets`, `cli-run`, `banlists`, `fxcache`, `field`),
the card pool (it is in the database and each device fetches it), downloaded card art, voice models, the course browser's
profile, Ai's traces, logs, sync's state, the Lounge's folder, Present's takes, and the backups themselves.

### `manifest.json` (`BackupManifest`)

Every key is written (`Backups.json`: `encodeDefaults = true`).

| Key | Meaning |
|---|---|
| `format` | `1` (`BackupManifest.FORMAT`). |
| `app` | `"Neue Master Tool"`. |
| `version` | The app version that wrote it. |
| `at` | When, in ms. |
| `reason` | Why, in words: "Before 1.1.95 (from 1.1.94)", "Weekly", "By hand", "Export", "Before restoring". |
| `device` | The device's name, as Sync names it (`SyncPlatform.deviceName`). |
| `decks` | How many `decks/` entries the zip holds. |
| `files` | How many entries the folders above added (not the decks, the four `prefs/` documents or the manifest). |

```json
{"format":1,"app":"Neue Master Tool","version":"1.1.95","at":1760259000000,"reason":"Weekly","device":"kai-desk","decks":42,"files":1873}
```

### `decks/<id>.json` (`BackupDeck`)

Everything the library keeps of a deck. `extended` is the deck's whole `#ydkx-extended` payload, left out when it has none.

```json
{"id":"3f9c…","name":"Labrynth","main":[2347477,2347477,…],"extra":[…],"side":[…],"notes":"","extended":{"groups":{…},"siding":{…}},"createdAt":1759000000000,"updatedAt":1760000000000}
```

### The settings documents

Each is the app's own document as `Backups.json` writes it, every field included. They are the same documents the
database's preferences table keeps under the keys named above, so their rules are the stored-data rules: a field has a
default, is never renamed or removed, and unknown keys are ignored. Ai's connections are in `prefs/neue.ui.json`; their
keys never are (`SecretStore`).

## Restoring

`BackupCenter.restore` reads the whole zip, then:

1. **It is a backup when `manifest.json` reads.** Otherwise nothing changes: "That file is not a Neue Master Tool backup".
   The manifest's other values are not checked. The picker offers `.nmtbackup` and `.zip`.
2. **A backup of the work as it is now is made first** ("Before restoring").
3. **Each entry goes back by its name**; an entry no rule names is skipped.
   - `decks/…`: decoded as a `BackupDeck` and saved through the library (`DeckRepository.save`) under the id inside it,
     replacing a deck with that id. The name of the entry is not used. Its times are not restored: the deck reads as
     changed now, and keeps its created time when the library already had it.
   - `ai/…`, `custom-art/…`, `present/…` (not a take), `duel/…`, `world/…`, `shootout/…`, `deckversions/…`, `effects/…`
     (what `FxPaths.syncs` allows): written to that place in the data folder, replacing the file there.
   - The four `prefs/` documents: decoded and stored. A document that does not decode is skipped; the rest of the backup
     still goes back. Neue's settings keep this device's own `sync`, `start` and `window`. Each web in the backup replaces
     the web with its id, and the others stay. Prep and the layout settings are replaced whole.
4. **Nothing made since is deleted.** A deck, a web or a file the backup does not hold stays.
5. The app reloads what was put back (decks, the open deck when it has no unsaved change, Ai's book, pictures, worlds,
   versions, Shootout, effects, the mapper, Present, the duel).

**Every file path passes two checks** before it is written. `InboundPath.safe`, the one test for a path from outside this
device (sync uses it too): at most 1,024 characters; no backslash, colon or control character; no empty, `.`, `..` or
hidden segment; not under a device-only folder; nothing private in Ai's folder. Then its real path must lie inside the data
folder. A `decks/` entry writes no file; its id is a key in the library.

### An old backup into a later version

The restore never looks at the backup's version or format. An older backup's deck is saved through the library as any
deck is, into whatever schema the newer app has. An older settings document lacks the fields added since, which read as
their defaults. An older folder's files are files the newer app already reads, under the stored-data rule that every
release reads what older builds wrote (`OldDataTest`). A newer backup restored into an older build loses only what that
build does not know: unknown keys, and entries no rule of its own names.

## Versioning

- `format` is 1. No reader checks it, so a change an older build would misread cannot be guarded by raising it alone.
- Change by adding. New work goes in a new top-level folder, or a new `prefs/` document, with a rule in the restore for
  it; an older build skips the entry. Never rename an entry, a folder or `manifest.json`.
- The documents and files inside version themselves, by their own rules.
- A reader finds the manifest by name. The app looks it up in the zip's central directory, since it is written last, and
  walks the entries when that fails; the order is a convenience, not a rule.

## Held by

- `app/neue/src/jvmTest/kotlin/com/kaiharimoto/neue/BackupRoundTripTest.kt` (added with this specification): a backup
  made by hand, the work changed, the backup restored. It checks that `manifest.json` is the last entry, the deck's entry
  is there, and no key is (`secrets/`, `cli-run/`, `ai/credentials.*`); that the deck comes back with its name, cards in
  order, notes and payload (a key the app does not know included); that Ai's `MEMORY.md`, a replay under `duel/replays/`
  and a Shootout trial come back as they were; that a setting comes back; that a "Before restoring" backup was made; and
  that no `.tmp` entry was written.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/compat/OldDataTest.kt`:
  `syncFilesFromALaterVersionStillRead` — a manifest of a later format, with a key this build does not know, still reads.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/start/StartStepsTest.kt`:
  `aBackupComesFirstWhenAVersionIsNewHere` (`Backups.due`), `theNewestTenAreKept` (`Backups.toDelete`, `Backups.fileName`).
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/sync/InboundPathTest.kt` — the path check a restore uses.
- `app/core/src/commonTest/kotlin/com/kaiharimoto/mastertool/core/duel/effects/FxLibraryTest.kt`:
  `theLibraryTravelsAndItsCacheNever` — `effects/` travels, `fxcache/` never.
