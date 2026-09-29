# Phase 2B: Storage

**Status:** approved in conversation on 2026-09-29. The user answered each design question and approved each design
section, then chose, as for 2A, to skip the written spec and plan reviews and implement natively.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "2B: Storage". The roadmap
approves this visible change: "the login no longer disappears when the last secret is removed", with explicit "set
password" and "remove password" actions.

## Purpose

Every store under `/data` writes its own file its own way. Five JSON stores rename a temp file without fsync, a
directory sync or owner-only permissions. Only `secrets.json` and `secret.key` write through `OwnerOnlyFiles`. Other
problems:

- Each store reads, validates and versions by hand.
- `sources.json` flattens nested data into prefixed keys.
- The webOS client key and the Tizen token sit in plain text in `devices.json`.
- The Android TV keystore is protected by the default password `shield`.
- The login disappears when the last secret is removed.

After this workstream:

- one class writes every file atomically and durably;
- one class versions, migrates, caches and writes the JSON stores;
- source settings are typed;
- device pairing keys are encrypted;
- the keystore password is random;
- the household sets and removes the login explicitly.

This serves the roadmap's goals of fewer bugs and races, and code that is easy to navigate.

## Decisions (the user's, 2026-09-29)

1. **Two kinds of secrets.**
   - **Device secrets** never need a login: the webOS client key, the Tizen token and the keystore password.
   - **Account credentials** still do: Jellyfin, TMDB, YouTube, workflows and the TheSportsDB key.
   - The login is set and removed explicitly. It cannot be removed while account credentials are stored, and it no
     longer disappears with the last secret.
2. **One pull request**, committed in small steps.
3. **`VersionedJsonFile<T>` by composition:** each store holds one. There is no base class.
4. **The spec and plan are written but not reviewed separately.** Native execution follows.

## Constraints

- **Existing installs upgrade in place.**
  - Every `/data` format change bumps a schema version, migrates on read, and ships with a test that loads the
    previous format.
  - A migrated file's original is kept once as `<name>.v<n>.json`, for a rollback.
  - Pairings (the Android TV keystore, webOS keys, Tizen tokens), secrets, pins, sports settings, source settings and
    the quota survive the upgrade.
- **Secrets are encrypted at rest and never reach the browser.** No message or log line carries a secret, a key or a
  password.
- **Behaviour stays the same by default.** The visible changes are:
  - the login's new rules;
  - the Account section's new forms;
  - `compose.yaml` no longer sets a keystore password.
- **Some names never change:** the CasaOS app id, the Android TV client package name, and every configuration key
  2A introduced.
- `./gradlew build` stays green, the test count stays equal or higher, and frozen ArchUnit violations only fall.

## Design

### 1. The file layer

- **`storage.AtomicFiles`** is `OwnerOnlyFiles` made public and renamed. It writes to a 0600 temp file in the target's
  directory, forces it to disk, moves it atomically over the target, and syncs the directory. `write(target, bytes,
  replace)` and `createTemp` stay. Every writer under `/data` uses it:
  - `JsonFileSourceSettings`, `JsonFileDeviceRegistry`, `JsonFilePinStore`, `JsonFileSportsStore` and `QuotaLedger`,
    through `VersionedJsonFile`;
  - `SecretStore` and `SecretKeySource`, as today;
  - `CertificateStore` for `keystore.p12`, which writes directly today. It moves from `adapters.androidtv.protocol`
    to `adapters.androidtv`: it stores credentials rather than speaking the wire protocol, and the protocol packages may
    not use `storage`. That also removes its four frozen violations.
- **`storage.VersionedJsonFile<T>`**, held by each JSON store:
  - `VersionedJsonFile(Path file, String description, int version, Supplier<T> empty, Function<JsonNode, T> reader,
    Function<T, ObjectNode> writer)`. The reader and writer convert between the document and the store's type: a
    record bound with Jackson, or the store's own lenient parsing where it skips bad entries with a warning (pins,
    sports).
  - `migrate(int from, UnaryOperator<JsonNode> step)` registers the step from version `from` to `from + 1`, as a JSON
    tree. `versionOf(ToIntFunction<JsonNode>)` replaces the default version lookup (the `version` field) for files
    that predate it.
  - `synchronized T read()`: the cached snapshot. The file is loaded once. A missing file gives `empty`, without
    writing.
  - `synchronized T update(UnaryOperator<T> change)`: applies the change to the snapshot, writes the result through
    `AtomicFiles` (pretty-printed, with `"version": <current>` first), then replaces the snapshot.
  - `synchronized void write(T value)` for a store that replaces its whole document.
  - `synchronized void delete()` removes the file and forgets the snapshot, for the shared test context.
  - **Loading** reads the tree and takes its version.
    - A newer version fails: `"<description> in <file> was written by a newer Home Control (version N); upgrade Home
      Control or restore a backup"`.
    - An older version runs each step in turn, copies the original once to `<name>.v<n>.json` beside it, and writes
      the migrated document.
    - Unreadable JSON, a missing step or a reader that rejects the document fails with `StorageException("Could not
      read <description> in <file>; fix or delete it")`.
- **Adoption:**

  | File | Version before → after | Change |
  | --- | --- | --- |
  | `pinned.json` | 1 → 1 | Onto `VersionedJsonFile`; same JSON. |
  | `sports.json` | 1 → 1 | Onto `VersionedJsonFile`; same JSON; its validation messages stay. |
  | `youtube-quota.json` | 1 → 1 | Onto `VersionedJsonFile`. It keeps its own policy: an unreadable file is moved aside and the count starts from zero, and a file from an earlier day starts fresh. |
  | `devices.json` | 1 or 2 (both bare arrays) → 3 | `{"version": 3, "devices": [...]}`. A bare array with an element that has no `kind` is version 1, otherwise 2. Step 1 → 2 is today's per-element migration; step 2 → 3 wraps the array. The backup is `devices.v1.json` or `devices.v2.json`. |
  | `sources.json` | 1 → 2 | Section 2. |

- **The device registry is cached and written through.** `findAll` reads the snapshot, and `save`/`delete` update it.
  - Its own checks stay: id, name, host, kind and `lastSeen` are required.
  - Adapter-specific checks move into the adapters. `core.DeviceAdapter` gains `default void validate(Device device)`.
    Android TV and Cast validate their port.
  - `DeviceManager.start()` calls `validate` for each entry whose adapter is running. A failure stops startup with the
    same message as today (`invalid device record … androidtv port must be an integer between 1 and 65535`), now
    naming the device id.
  - This removes `device`'s imports of `adapters.androidtv` and `adapters.cast`.
- **Tests and caching.** A store no longer notices its file being changed or deleted behind its back. `FullAppReset`
  resets through the stores' operations instead of deleting their files: pins and sports through their services,
  `sources.json` and the quota through a `reset()` that calls `VersionedJsonFile.delete()`, secrets through
  `removeSecrets` and `removeLogin`.

### 2. Typed sections in `sources.json`

- **Format v2:** `{"version": 2, "sources": {…}, "unmigrated": {…}, "preferences": {…}}`.
  - `sources.<id>` holds each source's settings record as JSON. `Instant`s, URIs and enums are strings, maps are
    objects, and sets are arrays.
  - `JellyfinSettings` stores `sessionLinks` and `players` as objects keyed by device id.
  - `YouTubeSettings` stores `playlists` as an object and `loungeDevices` as an array.
  - `TmdbSettings` keeps its two fields.
- **Migration.**
  - The v1 → v2 step moves each flat v1 section, unchanged, to `unmigrated.<id>`. The original stays as
    `sources.v1.json`.
  - When a source reads its settings and finds only `unmigrated.<id>`, it converts the flat map with its own
    `fromVersionOne(Map<String, String>)`, which is today's `fromMap`. It then stores the typed section and removes the
    flat one in the same update.
  - A switched-off module's section stays in `unmigrated` until the module is on.
- **API** (`storage.JsonFileSourceSettings`):
  - `<T> Optional<T> get(String sourceId, Class<T> type, Function<Map<String, String>, Optional<T>> fromVersionOne)`
    (a flat section the source cannot use reads as "not connected" and is dropped);
  - `void put(String sourceId, Object settings)`;
  - `void remove(String sourceId)`, which removes the typed and the unmigrated section;
  - `preferences()` and `putPreferences` keep their format and behaviour;
  - `reset()` deletes the file, for the shared test context.

  Top-level keys the store does not know are no longer kept: the version now says which keys a file has.

  A section that does not bind fails with `StorageException("Could not read the <id> settings in <file>; …")`.
- `toMap()` goes from `JellyfinSettings`, `YouTubeSettings` and `TmdbSettings`. `fromMap` becomes `fromVersionOne`,
  used only by the migration.

### 3. Two kinds of secrets, device keys, the keystore password and the login

- **Kinds.** A secret whose name starts with `device.` is a device secret; every other name is an account credential.
  The `secrets.json` format and the name pattern are unchanged; the longest device secret name has 40 characters.
- **`core.DeviceSecrets`**, implemented by `SecretStore`, is all an adapter sees: `deviceSecret(name)`,
  `putDeviceSecret(name, value)` and `removeDeviceSecrets(names)`, each refusing a name without the `device.` prefix,
  and `newReference()` for a `keyRef`. Device secrets never involve the login, so `LoginService` has no part in them.
- **`SecretStore`:**
  - `putSecrets(values)` requires a login only if a value is an account credential;
  - `putFirstSecrets(values, login)` stays, for the first account credential;
  - `removeSecrets(names)` never removes the login;
  - new `setLogin(LoginCredential)` and `removeLogin()`;
  - new `hasAccountCredentials()`.

  The invariant is: account credentials exist only while a login exists.
- **`LoginService`:**
  - `storeSecrets` is unchanged for account credentials.
  - New `setPassword(password, confirmation, request)`: allowed only when no login exists. It checks the password with
    `checkNewPassword`, creates the login, starts this browser's session and notifies listeners.
  - New `removePassword(current, request)`: the current password is rate-limited like every guess, and a wrong one is
    `WrongPasswordException`. It is refused with `IllegalStateException` while `hasAccountCredentials()`, naming the
    connected sources. Otherwise it removes the login and notifies listeners. Sessions need no ending: without a
    login every browser is let in, and a session's version no longer matches any later password.
  - `removeSecrets` never removes the login.
- **Web.**
  - `POST /setup/password/set` and `POST /setup/password/remove` sit next to the existing `/setup/password`. The
    remove route is guarded by the same rate limiter and cross-origin rules.
  - The Account section is always rendered:
    - without a login: "Set a password", with a new password and its confirmation;
    - with a login: "Change password", as today, and "Remove password", with the current password. The remove form is
      disabled, with the reason, while account credentials are stored.
- **webOS and Tizen keys.**
  - `WebOsSettings` and `TizenSettings` keep a `keyRef`: 16 random hex characters, not secret. The key itself is
    stored as the device secret `device.webos.<keyRef>.client-key` or `device.tizen.<keyRef>.token`.
  - The reference is part of the adapter settings, so merge and split carry it along, and forgetting a device removes
    its secret through the adapter's `forget`.
  - **Migration.** `core.DeviceAdapter` gains `default Device migrate(Device device)`, which `DeviceManager.start()`
    calls for each entry whose adapter is running. It saves the device when the result differs.
    - webOS and Tizen move a key that is still in the settings into a device secret under a new `keyRef`, and drop it
      from the settings.
    - The step is idempotent. A switched-off module's entries move when it is on.
- **The keystore password.**
  - `home-control.androidtv.keystore-password` has no default. `application.yaml` holds
    `${SHIELD_KEYSTORE_PASSWORD:}`, so the old variable still works, and empty means "generated".
  - **Configured:** when it is set, by the new name, `SHIELD_KEYSTORE_PASSWORD` or a file, that password is used
    exactly as today.
  - **Otherwise,** `AndroidTvConfiguration` builds `CertificateStore` with the device secret
    `device.androidtv.keystore-password`, generating 32 random bytes (Base64) and storing them first when it is
    missing. The store asks for its password only when it first opens or writes an existing or new keystore, so an
    install that never pairs an Android TV gets no secret; an existing keystore is opened, and re-protected if needed,
    at startup.
    - When a `keystore.p12` exists and does not open with that password, it is opened with each shipped default in
      turn, `shield` (`application.yaml`) and `change-me` (`compose.yaml`), and re-saved under the stored password
      through `AtomicFiles`. Storing the password first and re-protecting on every start where it does not open makes
      a crash between the two steps harmless.
    - If the keystore opens with none of them, startup fails: `"keystore.p12 does not open with the stored password or
      an old default; set home-control.androidtv.keystore-password to the password it was created with, or delete
      keystore.p12 and pair the Android TV devices again"`.
  - `compose.yaml` drops `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD: change-me`. An install that keeps the line keeps
    its password; one that drops it has its keystore re-protected under a generated one.

## Testing

- **`AtomicFilesTest`** (renamed from the `OwnerOnlyFiles` tests): 0600, replace and no-replace, and no temp file left
  behind.
- **`VersionedJsonFileTest`:**
  - a missing file gives `empty` without writing;
  - `update` writes the version and caches;
  - an old version migrates through two steps, and its backup is kept once;
  - a newer version fails with the named message;
  - bad JSON fails naming the file.
- **Each store's existing tests stay.** In addition:
  - `JsonFileDeviceRegistryTest` loads a v2 bare-array fixture and a v1 fixture, and writes v3;
  - `JsonFileSourceSettingsTest` loads a v1 fixture: Jellyfin links and players, YouTube playlists and lounge
    devices, TMDB, and preferences. Each source converts on first read, and a switched-off source's section survives;
  - `CertificateStoreTest` writes through `AtomicFiles`.
- **Device validation:** `DeviceManagerTest` checks that a bad Android TV port stops startup with the named message,
  and that a switched-off module's entry is not validated.
- **Secrets and login** (`SecretStoreTest`, `LoginServiceTest`):
  - a device secret is stored without a login, and an account credential is not;
  - the login survives removing the last secret;
  - `setPassword` only works without a login, and `removePassword` checks the password and is refused while accounts
    exist;
  - a session from before the removal does not count once a new password is set.
- **webOS/Tizen migration:** a v2 `devices.json` fixture with a webOS key and a Tizen token. After start, the keys are
  device secrets and gone from the file. Merge keeps the webOS pairing working, and forget removes the secret.
- **Keystore:**
  - a keystore under `shield`, and one under `change-me`, is re-protected, and its pairing still loads;
  - a stored password whose keystore is still under `shield` (a crash between the two steps) is re-protected;
  - a configured password is used unchanged;
  - a keystore that does not open fails with the named message;
  - a fresh install gets a password with its first keystore, and none before.
- **Web:** the Account section's three states, set and remove password, the refusal while accounts are connected,
  the rate limit and cross-origin refusal.
- **End to end:** `LoginGatingTest`'s cases that relied on the login disappearing with the last secret now assert
  that it stays. `FullAppReset` removes the login explicitly.

## Out of scope

- Moving the login's own hash out of `secrets.json`.
- Encrypting `devices.json` or the other non-secret stores.
- Rotating `secret.key`.
- Downgrading after a migration. The `<name>.v<n>.json` copies and a note in `docs/user/configuration.md` cover a
  manual rollback.

## Risks

- **A migration that loses a pairing.** Covered by the fixture tests for `devices.json` and the keystore, and by
  keeping the originals.
- **A cached store that misses a write from elsewhere.** Only the store writes its file; tests stop deleting files
  behind stores.
- **Removing the login while accounts are connected.** Refused by the service. The Account section explains why.
