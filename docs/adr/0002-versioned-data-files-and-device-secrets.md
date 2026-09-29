# ADR: Versioned data files and device secrets

**Date:** 2026-09-29
**Status:** Accepted
**Context:** Phase 2B (storage) of the architecture roadmap.
**Spec:** `docs/superpowers/specs/2026-09-29-phase-2b-storage-design.md`.

## Context

Every store under `/data` wrote its own file its own way. Only `secrets.json` had a real format version, and only it
and `secret.key` were forced to disk before the rename. The other problems:

- `devices.json` was a bare array.
- `sources.json` flattened nested data into prefixed strings (`link.<device>`, `playlist.<id>`).
- webOS client keys and Tizen tokens sat in plain text in `devices.json`.
- The Android TV keystore was world-readable, and protected by a password everyone knew (`shield`, or `change-me` from
  `compose.yaml`).
- The login existed exactly while secrets existed: removing the last one removed it too.

## Decision

- **One writer.** Every file under `/data` is written through `storage.AtomicFiles`: an owner-only temp file, forced to
  disk, moved atomically over the target, and the directory synced.
- **One versioned layer.** Every JSON store holds a `storage.VersionedJsonFile`. It:
  - reads the file once and serves it from memory;
  - migrates it forward on that first read, keeping the original once as `<name>.v<n>.json`;
  - refuses a file a newer Home Control wrote.
- **New versions.**
  - `devices.json` 3: `{"version": 3, "devices": [...]}`. Versions 1 and 2 were bare arrays.
  - `sources.json` 2: each section is its source's settings record as JSON. A version 1 section waits under
    `unmigrated` until its source converts it, so the store needs no source's types and a switched-off source keeps
    its settings.
- **Two kinds of secrets.** Names starting with `device.` are device secrets and never need the login: TV pairing keys
  and the keystore password. Every other secret is an account credential and needs the household login. The login is
  set and removed on purpose, and cannot be removed while an account credential exists. `core.DeviceSecrets` is all an
  adapter sees.
- **TV pairing keys are device secrets.** A webOS client key or Tizen token is stored under a random reference kept in
  the device's settings, so merge and split carry it along and forgetting the device removes it.
- **The keystore password is generated** with the keystore unless one is configured. A keystore still under a shipped
  default is re-protected at startup.
- **The adapters check and migrate their own settings.** `DeviceAdapter.validate` and `DeviceAdapter.migrate` run when
  the device manager starts, so the registry no longer knows any adapter.

## Consequences

- An upgraded install cannot be downgraded by swapping the image alone: an older image reads neither the new formats
  nor the re-protected keystore. Only a copy of the whole data directory from before the upgrade rolls it back; the
  `<name>.v<n>.json` copies are for inspection and a manual repair, and a copy of the keystore under its old password
  is deliberately not kept.
- A store no longer notices its file being changed behind its back. Edit files under `/data` only while the app is
  stopped.
- `secrets.json` now also holds the TV pairing keys and the keystore password. Deleting it unpairs every TV, so a
  forgotten login password is reset with `HOME_CONTROL_RESET_LOGIN=true` instead. The reset runs once and is
  remembered in `/data` until a start without the setting, so a setting left in place never removes a new password.
- Installs that pair a TV get `secrets.json` and `secret.key` even without a login.
