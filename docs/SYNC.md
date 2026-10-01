# Sync — bring your cloud

Neue Master Tool keeps every device the same — decks, webs, Prep, the settings that are about you,
Ai's notes, guides and conversations, and your own card pictures — through a place you already have.
Settings › Sync chooses it:

| Choice | What it is | Set up |
|---|---|---|
| **Folder** | A folder another app keeps in sync: iCloud Drive, Dropbox, OneDrive, Google Drive for desktop, Syncthing. On Android, any folder the system's picker reaches (Syncthing, Nextcloud's app). | Choose the folder on each device. |
| **WebDAV** | Nextcloud, ownCloud, pCloud, Koofr, Synology, any WebDAV server. | The folder's WebDAV address, a user name and an app password. |
| **Google Drive** | Drive's hidden app data folder. | Sign in with Google. |
| **Dropbox** | `Apps/Neue Master Tool`. | Sign in with Dropbox. |
| **OneDrive** | `Apps/Neue Master Tool`. | Sign in with Microsoft. |

Everything is free on the person's side. The three sign-ins need the app registered with each service
once (below); until a service's id is in `CloudClients.kt` it is not offered.

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

## Registering the app with the sign-in clouds

Each is free and done once, by the app's owner. All three use the redirect **`http://localhost:53682/`**
(`CloudSignIn.REDIRECT`): the browser signs in on the service's own page and comes back to the app,
which listens on that port for that one request — on the desk and on Android alike. Client ids are not
secrets (every installed copy carries them); put them in
`app/core/src/commonMain/kotlin/com/kaiharimoto/mastertool/core/sync/CloudClients.kt`.

### Google Drive

1. [console.cloud.google.com](https://console.cloud.google.com) → create a project, "Neue Master Tool".
2. APIs & Services → Library → **Google Drive API** → Enable.
3. Google Auth Platform → **Branding**: app name, support email, developer email. **Audience**: External,
   then **Publish app** (in Testing, sign-ins expire after seven days and only listed testers may sign in).
4. **Data Access** → Add scope `https://www.googleapis.com/auth/drive.appdata` (non-sensitive: no review).
5. **Clients** → Create client → **Desktop app** → copy the **Client ID** and **Client secret** into
   `GOOGLE_ID` and `GOOGLE_SECRET`. (Google: an installed app's secret "is obviously not treated as a
   secret".) Desktop clients accept any loopback port; nothing else to register.

### Dropbox

1. [dropbox.com/developers/apps](https://www.dropbox.com/developers/apps) → **Create app** → Scoped access →
   **App folder** → name it "Neue Master Tool".
2. **Permissions**: tick `files.content.read`, `files.content.write`, `files.metadata.read`
   (`account_info.read` is on already) → Submit.
3. **Settings**: OAuth 2 → Redirect URIs → add `http://localhost:53682/`; **Allow public clients
   (Implicit Grant & PKCE)** → Allow. Copy the **App key** into `DROPBOX_ID`.
4. A new app serves 500 people in development; Settings › **Apply for production** lifts that.

### OneDrive

1. [entra.microsoft.com](https://entra.microsoft.com) (a personal Microsoft account can make a free
   directory) → Applications → **App registrations** → New registration.
2. Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**.
   Redirect URI: **Public client/native (mobile & desktop)** → `http://localhost:53682/`.
3. **API permissions** → Microsoft Graph → Delegated: `Files.ReadWrite.AppFolder`, `User.Read`,
   `offline_access`.
4. **Authentication** → Allow public client flows → **Yes**.
5. Copy the **Application (client) ID** into `ONEDRIVE_ID`.

Once an id is in, the service appears in Settings › Sync on the next release.
