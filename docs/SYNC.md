# Sync — bring your cloud

Neue Master Tool keeps every device the same — decks, webs, Prep, the settings that are about you,
Ai's notes, guides and conversations, and your own card pictures — through a place you already have.
Settings › Sync chooses it:

| Choice | What it is | Set up |
|---|---|---|
| **Folder** | A folder another app keeps in sync: iCloud Drive, Dropbox, OneDrive, Google Drive for desktop, Syncthing. On Android, any folder the system's picker reaches (Syncthing, Nextcloud's app). | Choose the folder on each device. |
| **WebDAV** | Nextcloud, ownCloud, pCloud, Koofr, Synology, any WebDAV server. | The folder's WebDAV address, a user name and an app password. |
| **Google Drive** | Drive's hidden app data folder. | Sign in with Google. |

Everything is free on the person's side. Google Drive needs the app registered with Google once (below);
until its keys are in the repository's secrets it is not offered. Dropbox and OneDrive sign-ins were built and taken
out (kai: not popular enough); their desktop apps still work as a synced folder.

## What travels, and what stays

- **Travels:** saved decks (`decks/<id>.json`: name, sections in order, notes, the `.ydkx` payload),
  each web (`webs/<id>.json`), Prep (`prefs/prep.json`), the settings in `SyncedPrefs.SYNCED` and Ai's
  in `AI_SYNCED` (`prefs/neue.json`), TCG/OCG and effect search (`prefs/format.json`), everything in
  `<data>/ai` but its keys, and `<data>/custom-art`.
- **Stays:** the window, pane widths, zoom, orientation, text size, what is open (`SyncedPrefs.DEVICE`),
  Ai's connections and every key (`SecretStore`: they never leave a device), the card pool and the
  downloaded art (each device fetches its own), the voice models.
- `SyncedPrefsTest` (in `SyncTest`) fails when a setting is added to `NeuePreferences` or `AiPrefs`
  without being put on one side.

## How it works (`core/sync`)

- The store holds `blobs/<sha-256>` (an item's content, named by its hash, never rewritten) and
  `devices/<device>.json` (each device's `Manifest`: which version of every item it holds). **No two
  devices ever write the same file**, so a folder synced by another app cannot be left half one
  device's and half another's.
- Each device keeps what it last agreed (`<data>/sync/state.json`) and decides each item three ways
  (`SyncPlan`): changed here only → send; changed elsewhere only → take; both, the same → agree; both,
  differently → a conflict, by the item's rule: **decks** keep both (the newer wins, the other becomes
  "Name (from Phone)"), **settings and Prep** merge key by key (`JsonMerge`), **files** go to the newer.
  An edit always beats a deletion. A device meeting a store for the first time takes what is there.
- Blobs go up before the manifest that names them; the state is saved only at the end; a failure part
  way is redone next time. A pulled blob is checked against its hash.
- It runs on opening, 20 seconds after anything that travels changes, and every three minutes while
  the app is open (`SyncCenter`, `NeueEffects`); Settings has Sync now and the last result.

## Registering the app with Google

Free, done once, by the app's owner. The browser signs in on Google's own page and comes back to the app
at **`http://localhost:53682/`** (`CloudSignIn.REDIRECT`), where the app listens for that one request — on the
desk and on Android alike. The client id and secret live in the repository's GitHub secrets, `GOOGLE_OAUTH_CLIENT_ID` and
`GOOGLE_OAUTH_CLIENT_SECRET` (Settings › Secrets and variables › Actions). The release workflows hand them to
the build, and `:core`'s `generateCloudKeys` writes them into a generated `CloudKeys` — never into the
repository. A local or CI build has none, and Google Drive is not offered there.

### Google Drive

1. [console.cloud.google.com](https://console.cloud.google.com) → create a project, "Neue Master Tool".
2. APIs & Services → Library → **Google Drive API** → Enable.
3. Google Auth Platform → **Branding**: app name, support email, developer email. **Audience**: External,
   then **Publish app** (in Testing, sign-ins expire after seven days and only listed testers may sign in).
4. **Data Access** → Add scope `https://www.googleapis.com/auth/drive.appdata` (non-sensitive: no review).
5. **Clients** → Create client → **Desktop app** → copy the **Client ID** and **Client secret** into the
   repository's secrets `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET`. (Google: an installed app's secret "is obviously not treated as a
   secret".) Desktop clients accept any loopback port; nothing else to register.

Once the id is in, Google Drive appears in Settings › Sync on the next release.
