# ADR: Google Cast sender implementation

**Date:** 2026-09-16
**Status:** Accepted
**Context:** Sub-project B (Google Cast adapter), task B1 / GitHub issue #26.
**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` §4.1, §7.1, §11 ("Cast library abandonment").

## Decision

Build an **in-house minimal CASTV2 sender** under
`dev.andre.homecontrol.adapters.cast.protocol`: TLS socket to port 8009, 4-byte
big-endian length-prefixed `CastMessage` protobuf frames, JSON payloads handled
with the Jackson 3 (`tools.jackson`) mapper the application already uses.

Scope of the sender is exactly what sub-projects B and C need: the connection,
heartbeat, receiver and media namespaces (CONNECT/CLOSE, PING/PONG,
GET_STATUS/LAUNCH/STOP/SET_VOLUME, LOAD/GET_STATUS/PAUSE/PLAY/STOP). No device
authentication, no queue API, no custom-namespace framework beyond "send this
JSON to this transport".

No new third-party dependency is added. The only generated code comes from our
own copy of the `CastMessage` definition compiled by the protoc 4.36 toolchain
already in `build.gradle.kts`.

## Options considered

### 1. `su.litvak.chromecast:api-v2` ("chromecast-java-api-v2")

| Fact | Evidence |
|---|---|
| Latest version | `0.11.3`, published to Maven Central 2020-04-09 (maven-metadata `lastUpdated 20200409083916`) |
| Repository activity | `vitalidze/chromecast-java-api-v2`: last commit 2020-11-20 (dependabot junit bump); last functional commit 2020-04-28; 12 open issues; not archived |
| Licence | Apache-2.0 |
| Transitive dependencies | `com.google.protobuf:protobuf-java:2.6.0`, `com.fasterxml.jackson.core:jackson-databind` and `jackson-annotations` in the range `[2.9.1,2.9.99],[2.10.1,2.10.99]`, `org.jmdns:jmdns:[3.5.1,3.5.5]`, `slf4j-api [1.7.2,1.7.100]` |
| Bytecode target | Java 1.6 |
| Works with our protobuf 4.36 runtime on Java 25? | **No.** Spike: loading `su.litvak.chromecast.api.v2.CastChannel$CastMessage` with `protobuf-java-4.36.0` on the `gradle:jdk25` image (Java 25.0.4) fails with `IncompatibleClassChangeError: class ...CastChannel$CastMessage overrides final method com.google.protobuf.GeneratedMessageLite.getParserForType()`. Its generated code is protobuf 2.6 and cannot share a classpath with the Remote v2 code, which requires protobuf 4.x. |

Rejected: incompatible with the protobuf runtime the Android TV adapter needs,
abandoned for six years, and pins Jackson 2.9/2.10 ranges that conflict with the
Jackson 2.21 BOM Spring Boot 4.1.1 imports.

### 2. `org.digitalmediaserver:cast-api` (DigitalMediaServer `Cast-API` fork)

| Fact | Evidence |
|---|---|
| Latest version | `0.2.0`, published to Maven Central 2026-09-15 (previous: `0.1.2` 2025-11-06, `0.1.1` 2022-10-28) |
| Repository activity | `DigitalMediaServer/Cast-API`: 12 stars, 3 open issues, one active maintainer (Nadahar); commits cluster around releases (Nov–Dec 2025, Sep 2026) |
| Licence | Apache-2.0 (bundled `cast_channel.proto` is Chromium BSD) |
| Transitive dependencies | `com.google.protobuf:protobuf-java:3.21.7`, `com.fasterxml.jackson.core:jackson-databind:2.12.7.2`, `jackson-annotations:2.12.7`, `org.jmdns:jmdns:3.5.8`, `com.google.code.findbugs:annotations:3.0.1u2` (compile scope), `slf4j-api [1.7.2,1.7.100]` |
| Bytecode target | Java 1.7 (`Build-Jdk-Spec: 1.7`); the maintainer deliberately downgraded jmDNS "to the last version that actually supports Java 7" (commit 2025-12-08) |
| Works with our protobuf 4.36 runtime on Java 25? | **Yes, with a warning.** Spike: building, serialising and re-parsing a heartbeat `CastMessage` with `cast-api-0.2.0.jar` + `protobuf-java-4.36.0.jar` on Java 25.0.4 succeeds, but the runtime logs `WARNING: Vulnerable protobuf generated type in use: org.digitalmediaserver.cast.protobuf.CastChannel$CastMessage ... your gencode is vulnerable to a denial of service attack. You should regenerate your code using protobuf 25.6 or later` (GHSA-h4h5-3hr4-j3g2). |

Rejected because:

- It brings a **second JSON stack** (Jackson 2) into a Jackson 3 application;
  Spring Boot's Jackson 2 BOM would silently lift it from 2.12 to 2.21, a
  combination the library has never been tested with.
- Its generated protobuf code is **pre-22 gencode** flagged as vulnerable by the
  runtime we ship; fixing that requires the upstream to regenerate, which its
  Java 7 target makes unlikely (protoc 25+ gencode needs Java 8).
- `jmdns 3.5.8` would be upgraded to our 3.6.3 by Gradle conflict resolution;
  the library's `CastDeviceMonitor` opens **its own** `JmDNS` instance, so we
  would bypass it anyway to share one multicast socket.
- Its threading model (own listener threads, `CastEvent` bus, blocking
  `Channel` with its own ping task) duplicates what `DeviceHandle` already
  defines, and would have to be wrapped rather than used.
- Bus factor one, low adoption (12 stars): the risk the spec lists in §11 is
  simply moved, not removed.

### 3. In-house minimal sender (chosen)

- The protocol surface we use is small and has been stable since 2014: one
  protobuf message (`CastMessage`, 7 fields), a 4-byte length prefix, and a
  dozen JSON message types on four namespaces. Chromium, pychromecast and
  node-castv2 all document the same shapes.
- The codebase already runs a TLS + protobuf device protocol (Remote v2) with
  reconnect/backoff, stale detection, reader threads and in-process fake
  servers; the Cast sender follows the same patterns and test style.
- Estimated size: ~700 lines of main code (framing, TLS, connection with
  request correlation, payload builders, status parsers, session) and a
  ~400-line in-process fake receiver.

## Licence

- Our code: the project's own licence (see `LICENSE`).
- `src/main/proto/cast_channel.proto` contains the `CastMessage` definition
  copied verbatim from Chromium's `cast_channel.proto`
  (Copyright 2014 The Chromium Authors, BSD-style licence). The Chromium
  copyright header is kept in the file, which satisfies the BSD notice
  requirement. No other third-party code is copied.

## Maintenance risk

| Risk | Likelihood | Mitigation |
|---|---|---|
| Google changes CASTV2 framing or core namespaces | Low — every shipped Cast device and every Chrome sender depends on them | The fake receiver pins today's wire format in tests; real-hardware checklist per release |
| A receiver starts requiring sender device-auth (`urn:x-cast:com.google.cast.tp.deviceauth`) | Low — pychromecast and node-castv2 senders do not authenticate and work on current Google TV / Chromecast firmware | Sender auth is a receiver-side challenge senders may send, not answer; if that changes, add the `DeviceAuthMessage` definitions from the same Chromium file |
| CONNECT without `senderInfo` rejected by some firmware | Medium on newer Google TV builds | Send `origin`, `userAgent` and `senderInfo` exactly as pychromecast does |
| We own bugs a library would have fixed | Medium | Protocol-level unit tests for every message builder and parser, a fake receiver that speaks the wire format, reconnect/stale tests |
| Future needs (queues, multizone groups, YouTube Lounge) grow the sender | Medium | Out of scope for B; groups go to sub-project I, YouTube to E. The `CastConnection.request/expect` API is generic JSON, so new namespaces need no framework change |

## Consequences

- `build.gradle.kts` gains no dependency. `src/main/proto/cast_channel.proto`
  is compiled by the existing protobuf plugin into
  `dev.andre.homecontrol.adapters.cast.protocol.channel.CastMessage`.
- Cast code imports nothing from `adapters.androidtv`; TLS helpers are written
  for Cast (trust-any for the receiver's self-signed certificate, no client
  certificate).
- Discovery does not use any library's mDNS monitor; `_googlecast._tcp` is
  browsed through the application's single shared jmDNS instance.
- The spike code used to produce the evidence above was throwaway and is not
  committed.
- If the sender turns out to be a maintenance burden, `cast-api` remains the
  fallback once it regenerates its protobuf code and moves off Jackson 2; the
  adapter's `DeviceHandle` boundary keeps that swap local to
  `adapters/cast`.
