# ADR: Device control from content sources

**Date:** 2026-09-29
**Status:** Accepted
**Context:** Architecture roadmap, Phase 3C (playback routes).
**Spec:** `docs/superpowers/specs/2026-09-29-phase-3c-playback-routes-design.md` §4.

## Decision

- **A content service's own playback API stays in its source.** The source defines the route as a `DelegatedRoute`
  and runs it with a `RouteExecutor` registered for the route's key. Jellyfin sessions (`PlayNow` through the
  Jellyfin server) and the YouTube Lounge (the pairing API behind YouTube's Cast receiver) are such APIs.
- **Anything that commands a device goes through `DeviceCommands`,** as an `Action` or a receiver question, and never
  through a protocol client inside a source:
  - workflow Cast resolves its media, then sends a `CastLoad`;
  - Jellyfin wakes a device with a `WAKEUP` key press and opens its app, or VLC, with `OpenAppLink`;
  - the Lounge asks the Cast receiver for its screen id through `DeviceCommands.query`.
- **A source's references, routes and route strategy live in its module.** `core.playback` defines the references
  every source shares, the five device routes, and two open extension points, `SourceRef` and `DelegatedRoute`.
  A source's module declares its own `RouteStrategy` bean on the preference ladder (`Rung`), so a switched-off module
  contributes nothing to planning.

This records the behaviour the code already had; no device control moved for it.

## Options considered

### 1. Every device-facing step as a device route

Model Jellyfin's `PlayNow` and the Lounge as `Action`s that adapters carry out. Rejected: both talk to a content
service (the Jellyfin server, YouTube's Lounge server), not to the device, and need the source's credentials and
settings. An adapter would have to depend on a source.

### 2. Sources hold their own device clients

Let a source open its own Cast or ADB connection when it needs one. Rejected: it would duplicate the adapters'
protocols, bypass the device's connection state and capabilities, and break the rule that only adapters speak device
protocols (`ArchitectureTest`).

## Consequences

- A new source adds playback without editing `core.playback`: a `SourceRef`, a `DelegatedRoute` and a `RouteExecutor`
  when it needs a content service's API, or only a strategy that builds device routes when it does not.
- Two executors that claim one route key fail at startup. A switched-off module contributes neither its strategy
  nor its executor, so its routes are never planned. A delegated route that no executor runs, which only a module
  wired without its executor could plan, says "<device>: <source> is switched off on this server".
- A source that needs a new device step asks for a new `Action` or receiver question, which an adapter implements.
