# NVIDIA Shield Web Remote — Design

**Date:** 2026-08-29
**Status:** Approved, ready for implementation planning

## 1. Overview

A Spring Boot web application, run in Docker on the home LAN, that controls an
NVIDIA Shield (and, later, other Android TV devices) from a browser. It finds
devices on the network by itself, pairs with them through the 6-digit code the
TV displays, and then offers a virtual remote, an app launcher, and a live view
of what the device is doing.

### Goals

1. **Autodetection** — find Android TV devices on the LAN without manual configuration.
2. **Pairing via code exchange** — pair by entering the code shown on the TV; the
   resulting client certificate is the durable credential.
3. **Remote replacement** — D-pad, OK, back, home, menu, transport keys, volume, power.
4. **App launching** — start apps and open deep links.
5. **Live device state** — current foreground app, volume, mute, connection status,
   pushed to the browser without polling.

### Non-goals

- Installing, uninstalling, or managing apps; shell access; file transfer (would
  require ADB, which pairs with an RSA key and an on-screen dialog rather than a code,
  and needs developer mode enabled).
- Authentication and user accounts. The UI is LAN-only and unauthenticated by
  intent — the trust model is that of a physical remote lying on the couch.
- Wake-on-LAN. The Shield stays reachable in standby, so `KEYCODE_POWER` toggles it.
  WoL only matters for a fully powered-down device; it is a cheap later addition.
- Casting / media streaming from the browser to the device.

## 2. Constraints

| Constraint | Consequence |
|---|---|
| Runs as a Docker container | mDNS discovery requires `network_mode: host`; a manual host entry path must be a first-class feature, not an error fallback |
| Pairing credential is a client certificate | Must persist on a mounted volume; losing it means re-pairing |
| Java 25 (GraalVM CE) and Gradle 9.3 available locally; no Maven | Gradle Kotlin DSL, Java 25 toolchain |
| No maintained Java client exists for the Android TV Remote v2 protocol | The protocol is implemented in this project |

## 3. Decisions

| Decision | Choice | Why |
|---|---|---|
| Control protocol | Android TV Remote v2 (ports 6467 pairing / 6466 commands) | It is exactly the code-exchange pairing model requested, and covers keys, app launch, and state without developer mode |
| Protocol implementation | Pure Java, own `.proto` schemas, `protobuf-java`, `SSLSocket` | Self-contained jar, one runtime in the image, testable behind an interface |
| Rejected: Python `androidtvremote2` sidecar | — | Lower protocol risk, but two runtimes per image, IPC to debug, and the Spring app becomes a wrapper around another client. Kept as the documented fallback |
| Rejected: ADB control | — | Wrong pairing model, requires developer mode |
| Frontend | Thymeleaf + htmx + Server-Sent Events | No Node toolchain; one Gradle build produces the whole image; phone-friendly |
| Device scope | Registry abstraction now, single-device UI now | "One now, multiple later" — adding devices later is a UI change plus a session map, not a rewrite |
| Persistence | Two files on `/data`: `keystore.p12`, `devices.json` | A database is unwarranted for a handful of records |
| Access control | None; LAN-only | Explicit user decision |
| Self-signed certificate generation | BouncyCastle `bcpkix-jdk18on` | The JDK exposes no public API for issuing an X.509; the alternative is `sun.security` internals |

## 4. Architecture

```
shield/
├── build.gradle.kts          Spring Boot 4.0.x · Java 25 toolchain · protobuf plugin
├── Dockerfile · compose.yaml
└── src/
    ├── main/proto/           pairing.proto · remote.proto
    ├── main/java/dev/andre/shield/
    │   ├── protocol/         the ONLY package aware of TLS and protobuf
    │   │   ├── PairingSession       pairing handshake state machine (6467)
    │   │   ├── RemoteConnection     long-lived command channel (6466)
    │   │   ├── MessageStream        varint-length-prefixed framing
    │   │   ├── PairingSecret        SHA-256 over both certs + code nonce
    │   │   └── ClientCertificate    self-signed keypair, PKCS12 storage
    │   ├── discovery/        MdnsDiscovery (JmDNS, _androidtvremote2._tcp)
    │   ├── device/           Device · DeviceRegistry · DeviceSession · DeviceState
    │   ├── apps/             AppCatalog (YAML)
    │   └── web/              PairingController · DiscoveryController
    │                         RemoteController · StateController (SSE)
    ├── main/resources/       templates/ · static/ · application.yaml
    └── test/java/…/fake/     FakeShieldServer
```

### The protocol boundary

`protocol` exposes exactly two types outward. **No protobuf-generated type crosses
this line.** This is what keeps the sidecar fallback cheap and lets the entire
Spring layer be tested without a device.

```java
interface PairingSession extends AutoCloseable {
    void start();                       // connect, handshake; TV then displays a code
    PairingResult submitCode(String code);   // PAIRED | WRONG_CODE | FAILED(reason)
}

interface RemoteConnection extends AutoCloseable {
    void sendKey(KeyCode key, KeyDirection direction);
    void launchAppLink(URI appLink);
    void adjustVolume(VolumeDirection direction);
    void addListener(RemoteListener listener);   // onState(DeviceState), onDisconnected(Cause)
}
```

`DeviceSession` sits between protocol and web: it owns one `RemoteConnection`,
performs reconnect with backoff, holds the last known `DeviceState`, and is the
only collaborator the controllers know.

## 5. Protocol reference

> The wire details below are recorded from knowledge of the public Android TV
> Remote v2 protocol and the open-source clients that implement it
> (`androidtvremote2`, `androidtv-remote`). **Every byte-level detail marked (V)
> must be verified against a reference implementation during implementation and
> pinned by a test vector before it is trusted.**

### 5.1 Discovery

- mDNS service type `_androidtvremote2._tcp.local.` — the service instance name
  carries the friendly device name; the SRV record gives host and port 6466.
- `_googlecast._tcp.local.` may be used as a secondary signal for a friendly name
  and model string, but is not required.

### 5.2 Pairing (port 6467, TLS)

The client presents a self-signed certificate; the server's certificate is also
self-signed, so the pairing connection trusts it unconditionally and records its
fingerprint. Messages are protobuf, each prefixed with a varint length (V) —
`writeDelimitedTo` / `parseDelimitedFrom` semantics.

| Step | Direction | Message |
|---|---|---|
| 1 | → | `PairingRequest { service_name, client_name }` |
| 2 | ← | `PairingRequestAck { server_name }` |
| 3 | → | `PairingOption { input_encodings: [{ HEXADECIMAL, symbol_length: 6 }], preferred_role: INPUT }` |
| 4 | ← | `PairingOptionAck` |
| 5 | → | `PairingConfiguration { client_role: INPUT, encoding: { HEXADECIMAL, 6 } }` |
| 6 | ← | `PairingConfigurationAck` — **the TV now displays the code** |
| 7 | → | `PairingSecret { secret: digest }` |
| 8 | ← | `PairingSecretAck` — the client certificate is now authorized |

The digest at step 7:

```
nonce  = hexBytes(code.substring(2))          // last 4 hex chars → 2 bytes   (V)
digest = SHA-256( clientModulus ‖ clientExponent ‖ serverModulus ‖ serverExponent ‖ nonce )
assert digest[0] == hexByte(code.substring(0, 2))    // the check digit       (V)
```

Modulus and exponent are the big-endian unsigned bytes of the RSA public key.
In Java, `BigInteger.toByteArray()` prepends a `0x00` sign byte whenever the high
bit is set; **that byte must be stripped** (V). This single detail is the most
likely cause of a pairing that fails with a correctly typed code, and it is why
the test suite pins the digest with fixed certificates.

A wrong code causes the device to close the connection. The next attempt causes
the TV to display a **new** code, so the UI restarts the flow rather than
re-prompting into a dead session.

### 5.3 Command channel (port 6466, TLS with the paired certificate)

Handshake: `RemoteConfigure` (exchange device info) → `RemoteSetActive` →
`RemoteStart`, after which a reader thread runs for the life of the connection.

Inbound, unsolicited:
- foreground app package — carried on `RemoteImeKeyInject.app_info` (V)
- `RemoteSetVolumeLevel { volume_max, volume_level, volume_muted }`
- `RemotePingRequest` — **must be answered** with `RemotePingResponse` or the device disconnects
- `RemoteError`

Outbound:
- `RemoteKeyInject { key_code: KEYCODE_*, direction: SHORT }`
- `RemoteAdjustVolumeLevel`
- `RemoteAppLinkLaunchRequest { app_link: <uri> }`

## 6. Data model and persistence

```java
record Device(String id, String name, String host, int port,
              String certAlias, String serverCertFingerprint, Instant lastSeen) {}

record DeviceState(boolean connected, String currentAppPackage,
                   int volumeLevel, int volumeMax, boolean muted, Instant updatedAt) {}
```

- `/data/keystore.p12` — PKCS12 holding the client private key and certificate
  per device alias. **This is the pairing credential.** Password from
  `SHIELD_KEYSTORE_PASSWORD`, defaulted for convenience.
- `/data/devices.json` — the registry, written atomically (temp file + rename).

`serverCertFingerprint` is recorded during pairing and **pinned**: the command
channel accepts only a server certificate matching it. A device presenting a
different certificate is treated as failure class 2 (§8), not as a device to
trust silently — a factory-reset Shield must be re-paired deliberately.

The protocol reports no explicit power state, so `DeviceState.connected` is the
proxy: the Shield remains reachable in standby, and a device that has genuinely
powered off simply drops the connection.

`DeviceRegistry` is an interface (`findAll`, `findById`, `save`, `delete`) with a
JSON-file implementation. `DeviceSessionManager` maps device id → `DeviceSession`;
in v1 the UI resolves "the active device" as the single registered one.

## 7. Web layer

| Route | Purpose |
|---|---|
| `GET /` | Remote: D-pad cluster, back/home/menu, transport keys, volume, power, app grid; header shows connection badge and current app |
| `GET /setup` | Discovered devices, manual `host:port` form, pairing code entry |
| `POST /setup/pair` | Begin pairing with a chosen or manually entered host |
| `POST /setup/code` | Submit the displayed code |
| `POST /key/{keyCode}` | Single key press (`hx-swap="none"`) |
| `POST /apps/{id}/launch` | Launch a catalog entry |
| `POST /volume/{up\|down\|mute}` | Up/down go through `adjustVolume`; mute is `KEYCODE_MUTE` via `sendKey` |
| `GET /events` | SSE stream of `DeviceState` |

Key presses are htmx posts with no swap; on a LAN this is comfortably sub-100ms.
A small inline script binds arrow keys, Enter, Backspace, and space for desktop use.
Every `DeviceState` change is broadcast to all SSE subscribers, so the badge,
volume, and now-playing chip update on their own.

### App catalog

`/data/apps.yaml`, user-editable, prepopulated with common Android TV apps:

```yaml
apps:
  - id: netflix
    name: Netflix
    package: com.netflix.ninja
  - id: youtube
    name: YouTube
    package: com.google.android.youtube.tv
  - id: plex
    name: Plex
    package: com.plexapp.android
    deepLink: "plex://"          # optional; falls back to market://launch?id=<package>
```

Launching uses the entry's `deepLink` when present, otherwise
`market://launch?id=<package>` (V), the generic "open this app" URI Android TV honours.
The protocol reports only the *current* foreground app and cannot enumerate what is
installed — hence a configured catalog rather than a discovered list.

## 8. Error handling

Three failure classes that look alike but need different responses:

1. **Network unreachable** (device asleep and dropped, reboot, DHCP move) —
   `DeviceSession` marks disconnected, broadcasts state, reconnects with exponential
   backoff (1s doubling to a 60s cap, indefinitely). Key presses during this window
   are rejected with a toast, not queued.
2. **TLS handshake rejected** on 6466 — the device no longer trusts our certificate
   (factory reset, pairing cleared). This must **not** enter the retry loop; it surfaces
   as "this device no longer accepts the pairing" with a link to `/setup`.
3. **Pairing failures** — wrong code, no code appearing before timeout, connection
   refused — each with its own message; a wrong code restarts the flow from step 1.

A staleness watchdog treats "no inbound message for 30 seconds" (configurable) as a dead connection
even when the socket has not reported an error, and triggers reconnect.
`RemoteError` messages are logged without dropping the connection.

## 9. Testing strategy

Development is test-driven. The centerpiece is a **`FakeShieldServer`** in the test
sources: an `SSLServerSocket` that speaks the same framing and replays both handshakes.
It makes the following testable with no hardware:

- framing round-trips, including a message split across TCP reads and a message
  arriving in the same read as its successor
- the complete pairing handshake, and the wrong-code path (server closes; UI restarts)
- **`PairingSecret` against fixed test certificates and a fixed code → a known digest** —
  the test that pins every (V) detail above, in particular modulus zero-stripping
- reconnect and backoff behaviour when the server hangs up
- ping requests being answered
- an inbound volume message propagating out as an SSE event

Controllers are tested with `@WebMvcTest` over a stubbed `DeviceSession`; one
`@SpringBootTest` wires the fake server end to end.

**The fake server proves internal consistency only.** The first pairing against the
real Shield is the true gate; if the recorded protocol details are wrong, that is
where it surfaces.

## 10. Build and deployment

- Gradle Kotlin DSL, Java 25 toolchain, Spring Boot 4.0.x.
- Dependencies: `spring-boot-starter-web`, `spring-boot-starter-thymeleaf`,
  `protobuf-java` with the `com.google.protobuf` Gradle plugin, `org.jmdns:jmdns`,
  `org.bouncycastle:bcpkix-jdk18on`. Exact versions resolved at implementation time.
- Multi-stage Dockerfile: `gradle:jdk25` build stage → `eclipse-temurin:25-jre` runtime.
- `compose.yaml` uses `network_mode: host` (required for mDNS) and mounts `/data`;
  a commented bridge-mode variant documents that discovery stops working there and
  manual host entry must be used.
- `application.yaml`: data directory, keystore password (env-overridable),
  discovery enable/disable, app catalog path, staleness timeout, backoff bounds.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Byte-level pairing details wrong → correct code still fails | Every (V) detail verified against a reference implementation and pinned by a digest test vector before the first hardware attempt |
| Protobuf schemas incomplete or field numbers wrong | Schemas taken from open-source implementations; unknown fields tolerated by protobuf |
| mDNS silently absent in Docker | Manual host entry is a first-class UI path, and the compose file documents the requirement |
| Pure-Java protocol proves unworkable | The `protocol` boundary is designed so a Python-sidecar implementation can replace it without touching the Spring layer |

## 12. Future work

Explicitly deferred, in rough order of likely value: multi-device switching in the
UI (the registry already supports it), Wake-on-LAN for a fully powered-down device,
text input to the TV (the protocol carries an IME channel), and optional
authentication if the app is ever exposed beyond the LAN.
