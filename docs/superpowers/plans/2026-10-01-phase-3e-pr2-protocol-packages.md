# Phase 3E, PR 2: Protocol Packages and Choreography — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** webOS and Tizen get `protocol` packages that take plain values, Cast's receiver-app choreography and UPnP's
renderer resolution move into their protocol packages, and `SsdpMessage` joins `discovery.ssdp.protocol`.

**Architecture:** Classes move with `git mv`, become public where the adapter uses them, and stop taking Spring
properties records: `SsapOptions` and `TizenOptions` carry ports and `Duration`s, built by `WebOsProperties.ssap()`
and `TizenProperties.protocol()`. `adapters.net.DeviceUris` builds device URLs so an IPv6 address gets its brackets
without `core.Hosts`. `CastApps` and `RendererResolver` hold the protocol work `CastSession` and `UpnpSession` did;
the sessions keep their state and translate failures through `DeviceCalls`.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Jackson 3, JDK `HttpClient` and WebSocket, JUnit 5, AssertJ, Awaitility.
Build with `scripts/gradle.sh` (Gradle in the `gradle:jdk25` image; `-q`, silent on success).

**Spec:** `docs/superpowers/specs/2026-10-01-phase-3e-adapter-layering-design.md`, sections 2 and 3 and the PR 2 lines
of Testing, Delivery and Visible changes. PR 1 (#164) merged as `35d8fe4`; this branch is `refactor/adapter-protocols`.

## Global Constraints

- Protocol packages (`..protocol..`) depend on neither Spring nor any application package other than `adapters.net`
  and other protocol packages. The rule is strict (PR 1): a violation fails the build.
- Protocol classes throw `IOException` and its subclasses for I/O: `DeviceTimeoutException`,
  `DeviceRefusedException` and its subclasses, and a protocol's own I/O exceptions. Core exceptions are made only by
  sessions (through `DeviceCalls`) and by the adapters' pairing services.
- Nothing a user sees changes except the spec's visible change: Cast's refusals read "{name} refused to {what}:
  {reason}".
- `scripts/gradle.sh build` green at the end of every task; `scripts/e2e.sh -Pe2eBrowsers=chromium` at the end of the
  PR.
- Commits follow Conventional Commits, stage only the task's files (`git add <paths>`, `git mv`, `git rm`; never
  `git add -A`), and end with:

  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
  ```
- A deliberate exception to a SonarCloud rule uses the narrowest `@SuppressWarnings("java:S…")` with a one-line
  reason.

## Review Focus

1. **A TV registered by an IPv6 address still connects:** its URLs carry the address in brackets, as
   `core.Hosts.authority` did. Pinned by `DeviceUrisTest.anIpv6AddressGetsItsBrackets` (Task 2).
2. **Tizen's client name and token stay encoded once in the remote URL** (a `%` encoded again would change the token
   the TV sees). Pinned by `DeviceUrisTest.anEncodedPathAndQueryStayAsGiven` and the moved
   `TizenMessagesTest` (Tasks 2, 3).
3. **A Tizen TV's MAC in another notation is still normalised before it is stored, and a garbled one is ignored.**
   Pinned by `TizenSessionTest.aReportedMacIsNormalisedAndAGarbledOneIgnored` (Task 3).
4. **A Cast custom message that no receiver rejects within the error window counts as taken.** Pinned by
   `CastAppsTest.aCustomMessageNobodyRejectsIsTaken` (Task 4).
5. **A UPnP renderer whose SCPD cannot be read keeps the default volume range instead of failing.** Pinned by
   `RendererResolverTest.anScpdThatCannotBeReadKeepsTheDefaultVolumeRange` (Task 5).

## Plan rulings (where this plan departs from the spec's text, or settles what it left open)

1. **`SsdpMessage` moves to `discovery.ssdp.protocol`** (PR 1's deferred minor 3). It is the SSDP datagram parser,
   JDK-only; with it there, the spec's measure "packages with wire code outside `protocol`: 0" holds.
2. **Parsers of received data may throw `IllegalArgumentException` for malformed input**, as `DeviceDescriptions`,
   `UpnpXml`, `ReceiverStatus` and the Cast framing do today; their callers catch it. The spec's `IOException` line
   is about I/O. The architecture guide says so (Task 6). (PR 1's declined item.)
3. **`adapters.net.DeviceUris.of(scheme, host, port, pathAndQuery)`** builds `scheme://host:port` with `URI`'s
   multi-argument constructor, which adds an IPv6 address's brackets, and appends the already-encoded path and query
   unchanged. Calling the multi-argument constructor with the path and query too would encode Tizen's `%`-escapes a
   second time. One helper instead of four copies.
4. **`SsapOptions(port, securePort, connectTimeout, requestTimeout)` and `TizenOptions(port, restPort, dialPort,
   clientName, connectTimeout, requestTimeout)`**, built by `WebOsProperties.ssap()` and `TizenProperties.protocol()`.
5. **The fakes move with the protocol tests:** `FakeSsapServer` to `webos.protocol`, `FakeTizenServer` to
   `tizen.protocol`. `FakeTizenServer.options()` replaces `TizenRestTest.properties(fake)` for the protocol tests;
   `TizenSessionTest` gets its own `properties(fake)`.
6. **`CastApps` is built per command** around the connection the command checked, not kept in a field: a reconnect
   replaces the connection, and a command must not use a stale one.
7. **`RendererResolver` logs the device's host** where `UpnpSession` logged its id; it knows no id.
8. **Cast's `what` per step:** "start receiver app {id}" for launching and waiting for a namespace, "load the media",
   "start playback" for a custom message, "answer {replyType}" for a query, and the existing texts for receiver
   commands ("set the volume", "stop {app}").

## File structure

| File | Change |
| --- | --- |
| `discovery/ssdp/SsdpMessage.java` + test | moved to `discovery/ssdp/protocol/` |
| `adapters/net/DeviceUris.java` | new |
| `adapters/webos/{SsapConnection,SsapMessages,SsapUris,SsapException,SsapPairingException}.java` | moved to `adapters/webos/protocol/` |
| `adapters/webos/protocol/SsapOptions.java` | new |
| `adapters/tizen/{TizenRemoteConnection,TizenMessages,TizenRest,DialClient,DialException,TizenApp,TizenDeviceInfo}.java` | moved to `adapters/tizen/protocol/` |
| `adapters/tizen/protocol/TizenOptions.java` | new |
| `adapters/cast/protocol/{CastApps,CastRefusedException}.java` | new |
| `adapters/upnp/protocol/RendererResolver.java` | new |
| `CastSession`, `UpnpSession`, `WebOsSession`, `TizenSession`, the pairing services, `WebOsLaunches`, `TizenLaunches` | use the protocol packages |
| `docs/dev/architecture.md` | protocol packages, parser exceptions, measures |

Paths are under `src/main/java/dev/andre/homecontrol/` and `src/test/java/dev/andre/homecontrol/` unless they start
with `src/` or `docs/`.

---

### Task 1: `SsdpMessage` joins `discovery.ssdp.protocol`

**Files:**
- Move: `src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpMessage.java` →
  `src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/SsdpMessage.java`
- Move: `src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpMessageTest.java` →
  `src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol/SsdpMessageTest.java`
- Modify (imports): `discovery/ssdp/SsdpDiscovery.java`; test `discovery/ssdp/FakeSsdpResponder.java`

**Interfaces:**
- Produces: `dev.andre.homecontrol.discovery.ssdp.protocol.SsdpMessage`, unchanged apart from its package (it is a
  public record whose members are public already).

- [ ] **Step 1: Move the class and its test**

```bash
git mv src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpMessage.java src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/
git mv src/test/java/dev/andre/homecontrol/discovery/ssdp/SsdpMessageTest.java src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol/
```

Both files: `package dev.andre.homecontrol.discovery.ssdp;` → `package dev.andre.homecontrol.discovery.ssdp.protocol;`.
`SsdpDiscovery` and `FakeSsdpResponder` gain `import dev.andre.homecontrol.discovery.ssdp.protocol.SsdpMessage;`,
sorted among their imports with a blank line before the `java.` block.

- [ ] **Step 2: Run the SSDP tests and the rules**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.discovery.*' --tests 'dev.andre.homecontrol.adapters.upnp.*' --tests 'dev.andre.homecontrol.adapters.sonos.*' --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS (`SsdpMessageTest` runs from its new package; `protocolPackagesStandAlone` holds for it).

- [ ] **Step 3: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 4: Commit**

Message:

```
refactor: SsdpMessage joins discovery.ssdp.protocol

SsdpMessage is the SSDP datagram parser and needs nothing but the JDK; it now sits beside the
other SSDP wire code, so no package outside a protocol package holds wire code.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 2: webOS's protocol package

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/net/DeviceUris.java`,
  `src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapOptions.java`
- Move to `adapters/webos/protocol/`: `SsapConnection.java`, `SsapMessages.java`, `SsapUris.java`,
  `SsapException.java`, `SsapPairingException.java`
- Move to the test package `adapters/webos/protocol/`: `SsapConnectionTest.java`, `FakeSsapServer.java`
- Modify: `adapters/webos/WebOsProperties.java` (`ssap()`), `WebOsSession.java`, `WebOsPairing.java`,
  `WebOsLaunches.java`, `WebOsLaunch.java` if it names a moved type
- Modify (test imports): `adapters/webos/WebOsSessionTest.java`, `WebOsAdapterTest.java`, `WebOsPairingTest.java`,
  `WebOsPayloadsTest.java`, `WebOsLaunchesTest.java`, `web/WebOsEndToEndTest.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/net/DeviceUrisTest.java` (new)

**Interfaces:**
- Produces:
  - `public static URI DeviceUris.of(String scheme, String host, int port, String pathAndQuery)` (`adapters.net`;
    Task 3 uses it).
  - `public record SsapOptions(int port, int securePort, Duration connectTimeout, Duration requestTimeout)`;
    `public SsapOptions WebOsProperties.ssap()`.
  - `public static SsapConnection SsapConnection.open(HttpClient http, String host, SsapOptions options,
    Consumer<String> onClosed) throws IOException`; `register`, `request`, `fire`, `subscribe`, `button` public.
  - Public: the classes `SsapConnection`, `SsapMessages`, `SsapUris`, `SsapException`, `SsapPairingException`;
    `SsapMessages.JSON` and `SsapMessages.empty()`; every `SsapUris` constant; `SsapPairingException.Reason` and
    `reason()`.

- [ ] **Step 1: Write the failing test for the URL helper**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/net/DeviceUrisTest.java
package dev.andre.homecontrol.adapters.net;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceUrisTest {

    @Test
    void aHostNameAndPortMakeTheAuthority() {
        assertThat(DeviceUris.of("http", "tv.local", 8001, "/api/v2/"))
                .isEqualTo(URI.create("http://tv.local:8001/api/v2/"));
    }

    @Test
    void anIpv6AddressGetsItsBrackets() {
        assertThat(DeviceUris.of("ws", "fe80::1", 3000, "")).isEqualTo(URI.create("ws://[fe80::1]:3000"));
    }

    @Test
    void anEncodedPathAndQueryStayAsGiven() {
        String pathAndQuery = "/api/v2/channels/samsung.remote.control?name=SG9tZQ%3D%3D&token=a%2Bb";

        assertThat(DeviceUris.of("wss", "192.0.2.5", 8002, pathAndQuery).toString())
                .isEqualTo("wss://192.0.2.5:8002" + pathAndQuery);
    }

    @Test
    void aHostNoUrlCanCarryIsRefused() {
        assertThatThrownBy(() -> DeviceUris.of("http", "not a host", 80, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a host");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.net.DeviceUrisTest`
Expected: compilation fails: `DeviceUris` does not exist.

- [ ] **Step 3: Write the helper and the options record**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/net/DeviceUris.java
package dev.andre.homecontrol.adapters.net;

import java.net.URI;
import java.net.URISyntaxException;

/** URLs of a device on the LAN, from their parts, so that an IPv6 address gets the brackets a URL needs. */
public final class DeviceUris {

    private DeviceUris() {
    }

    /**
     * {@code scheme://host:port} followed by {@code pathAndQuery}, which must already be encoded and is kept as given.
     * A host that no URL can carry is refused.
     */
    public static URI of(String scheme, String host, int port, String pathAndQuery) {
        URI base;
        try {
            base = new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a host name or an IP address: " + host, e);
        }
        return URI.create(base + pathAndQuery);
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapOptions.java
package dev.andre.homecontrol.adapters.webos.protocol;

import java.time.Duration;

/** Where an LG TV's SSAP sockets listen, and how long a connection waits for them. */
public record SsapOptions(int port, int securePort, Duration connectTimeout, Duration requestTimeout) {
}
```

In `WebOsProperties`, after the constructors:

```java
    /** The SSAP connection's ports and waits. */
    public SsapOptions ssap() {
        return new SsapOptions(port, securePort, connectTimeout, requestTimeout);
    }
```

with `import dev.andre.homecontrol.adapters.webos.protocol.SsapOptions;`.

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.net.DeviceUrisTest`
Expected: PASS.

- [ ] **Step 4: Move the SSAP classes and their tests**

```bash
mkdir -p src/main/java/dev/andre/homecontrol/adapters/webos/protocol src/test/java/dev/andre/homecontrol/adapters/webos/protocol
git mv src/main/java/dev/andre/homecontrol/adapters/webos/SsapConnection.java src/main/java/dev/andre/homecontrol/adapters/webos/SsapMessages.java src/main/java/dev/andre/homecontrol/adapters/webos/SsapUris.java src/main/java/dev/andre/homecontrol/adapters/webos/SsapException.java src/main/java/dev/andre/homecontrol/adapters/webos/SsapPairingException.java src/main/java/dev/andre/homecontrol/adapters/webos/protocol/
git mv src/test/java/dev/andre/homecontrol/adapters/webos/SsapConnectionTest.java src/test/java/dev/andre/homecontrol/adapters/webos/FakeSsapServer.java src/test/java/dev/andre/homecontrol/adapters/webos/protocol/
```

In the seven moved files the package line becomes `package dev.andre.homecontrol.adapters.webos.protocol;`.

Visibility in the moved main classes:
- `final class SsapConnection` → `public final class SsapConnection`; `register`, `request`, `fire`, `subscribe` and
  `synchronized void button` become `public`.
- `final class SsapMessages` → `public final class SsapMessages`; `static final JsonMapper JSON` and
  `static ObjectNode empty()` become `public`.
- `final class SsapUris` → `public final class SsapUris`; every `static final String` constant becomes `public`.
- `class SsapException` → `public class SsapException`.
- `class SsapPairingException` → `public class SsapPairingException`; `enum Reason` and `Reason reason()` become
  `public`.

`SsapConnection` takes the options instead of `WebOsProperties`, and builds its URLs with `DeviceUris`:

```java
    private SsapConnection(HttpClient http, SsapOptions options) {
        this.http = http;
        this.connectTimeout = options.connectTimeout();
        this.requestTimeout = options.requestTimeout();
    }

    /** ws://host:port first, then wss://host:securePort (firmware that closed the plain port or insists on TLS). */
    public static SsapConnection open(HttpClient http, String host, SsapOptions options, Consumer<String> onClosed)
            throws IOException {
        SsapConnection connection = new SsapConnection(http, options);
        TextWebSocket.Listener listener = new TextWebSocket.Listener() {
            @Override
            public void onText(String text) {
                connection.dispatch(text);
            }

            @Override
            public void onClosed(String reason) {
                connection.failEverythingWaiting(reason);
                onClosed.accept(reason);
            }
        };
        try {
            connection.socket = TextWebSocket.connect(http, DeviceUris.of("ws", host, options.port(), ""),
                    connection.connectTimeout, listener);
        } catch (IOException plainFailed) {
            try {
                connection.socket = TextWebSocket.connect(http, DeviceUris.of("wss", host, options.securePort(), ""),
                        connection.connectTimeout, listener);
            } catch (IOException secureFailed) {
                secureFailed.addSuppressed(plainFailed);
                throw secureFailed;
            }
        }
        return connection;
    }
```

Imports in `SsapConnection`: add `dev.andre.homecontrol.adapters.net.DeviceUris`; remove `dev.andre.homecontrol.core.Hosts`
and `java.net.URI` if nothing else uses it. The pointer socket (`button`) builds its URL from the TV's answer; keep
it as it is.

Callers in `adapters.webos`:
- `WebOsSession`: `SsapConnection.open(http, device.host(), properties, …)` →
  `SsapConnection.open(http, device.host(), properties.ssap(), …)`.
- `WebOsPairing`: `SsapConnection.open(http, host, properties, reason -> { })` →
  `SsapConnection.open(http, host, properties.ssap(), reason -> { })`.
- `WebOsSession`, `WebOsPairing`, `WebOsLaunches` (and `WebOsLaunch` if it names one) import what they use from
  `dev.andre.homecontrol.adapters.webos.protocol` (`SsapConnection`, `SsapException`, `SsapMessages`,
  `SsapPairingException`, `SsapUris`).

Tests:
- `SsapConnectionTest`: its helper `static WebOsProperties properties(int port, int securePort, int pairingTimeoutSeconds)`
  becomes

  ```java
      static SsapOptions options(int port, int securePort) {
          return new SsapOptions(port, securePort, Duration.ofSeconds(2), Duration.ofSeconds(2));
      }
  ```

  and its three calls `properties(a, b, 2)` become `options(a, b)`; the `WebOsProperties` import goes.
- `WebOsSessionTest`, `WebOsAdapterTest`, `WebOsPairingTest`, `WebOsPayloadsTest`, `WebOsLaunchesTest` and
  `web/WebOsEndToEndTest` import `FakeSsapServer`, `SsapMessages` and `SsapUris` from the new packages where they use
  them.

Then `grep -rn "core.Hosts" src/main/java/dev/andre/homecontrol/adapters/webos` prints nothing.

- [ ] **Step 5: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.adapters.net.*' --tests dev.andre.homecontrol.web.WebOsEndToEndTest --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS; `protocolPackagesStandAlone` holds for `adapters.webos.protocol`.

- [ ] **Step 6: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 7: Commit**

Message:

```
refactor: webOS gets a protocol package

SsapConnection, SsapMessages, SsapUris and the SSAP exceptions move to adapters.webos.protocol,
with SsapConnectionTest and FakeSsapServer. The connection takes an SsapOptions record of ports
and Durations instead of the Spring properties record, and builds its URLs with the new
adapters.net.DeviceUris, which puts an IPv6 address in brackets without core.Hosts. The session,
the pairing service and the launches use the package; nothing a user sees changes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 3: Tizen's protocol package

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenOptions.java`
- Move to `adapters/tizen/protocol/`: `TizenRemoteConnection.java`, `TizenMessages.java`, `TizenRest.java`,
  `DialClient.java`, `DialException.java`, `TizenApp.java`, `TizenDeviceInfo.java`
- Move to the test package `adapters/tizen/protocol/`: `TizenRemoteConnectionTest.java`, `TizenRestTest.java`,
  `DialClientTest.java`, `TizenMessagesTest.java`, `FakeTizenServer.java`
- Modify: `adapters/tizen/TizenProperties.java` (`protocol()`), `TizenSession.java`, `TizenPairing.java`,
  `TizenLaunches.java`, `TizenLaunch.java` if it names a moved type
- Modify (test imports): `adapters/tizen/TizenSessionTest.java`, `TizenAdapterTest.java`, `TizenPairingTest.java`,
  `TizenLaunchesTest.java`, `web/TizenEndToEndTest.java`

**Interfaces:**
- Consumes: `DeviceUris.of` (Task 2).
- Produces:
  - `public record TizenOptions(int port, int restPort, int dialPort, String clientName, Duration connectTimeout,
    Duration requestTimeout)`; `public TizenOptions TizenProperties.protocol()`.
  - `public static TizenRemoteConnection TizenRemoteConnection.open(HttpClient http, String host, TizenOptions options,
    String token, Consumer<String> onClosed) throws IOException`; `public TizenRest(HttpClient http, TizenOptions
    options)`; `public DialClient(HttpClient http, TizenOptions options)`.
  - Public: the seven moved types; `TizenRemoteConnection.Authorization`, `awaitAuthorization`, `token`, both `key`
    methods, `launchApp`, `requestInstalledApps`, `installedApps`; `TizenRest.deviceInfo` and `appVisible`;
    `DialClient.launch`; `TizenMessages.JSON` and `installedApps(JsonNode)`; `TizenDeviceInfo.on()`.
  - `TizenDeviceInfo.macAddress()` goes: the record reports the raw `wifiMac`, and the session normalises it.
  - `public TizenOptions FakeTizenServer.options()` (test).

- [ ] **Step 1: Write the failing test for the session's MAC handling**

`TizenSession` gains a package-private `static Optional<String> reportedMac(TizenDeviceInfo info)`. In
`TizenSessionTest`, add:

```java
    @Test
    void aReportedMacIsNormalisedAndAGarbledOneIgnored() {
        assertThat(TizenSession.reportedMac(new TizenDeviceInfo("TV", "QE55", "on", "70-2a-d5-01-02-03", true)))
                .contains("70:2A:D5:01:02:03");
        assertThat(TizenSession.reportedMac(new TizenDeviceInfo("TV", "QE55", "on", "not a mac", true))).isEmpty();
        assertThat(TizenSession.reportedMac(new TizenDeviceInfo("TV", "QE55", "on", "", true))).isEmpty();
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.tizen.TizenSessionTest`
Expected: compilation fails: `reportedMac` does not exist.

- [ ] **Step 3: Write the options record, and move the MAC rule into the session**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenOptions.java
package dev.andre.homecontrol.adapters.tizen.protocol;

import java.time.Duration;

/** Where a Samsung TV's remote socket, REST API and DIAL server listen, the name it shows, and how long to wait. */
public record TizenOptions(int port, int restPort, int dialPort, String clientName, Duration connectTimeout,
                           Duration requestTimeout) {
}
```

In `TizenProperties`:

```java
    /** The protocol clients' ports, name and waits. */
    public TizenOptions protocol() {
        return new TizenOptions(port, restPort, dialPort, clientName, connectTimeout, requestTimeout);
    }
```

with `import dev.andre.homecontrol.adapters.tizen.protocol.TizenOptions;`.

In `TizenSession`, the poll's `info.flatMap(TizenDeviceInfo::macAddress).ifPresent(learnedMac::offer);` becomes
`info.flatMap(TizenSession::reportedMac).ifPresent(learnedMac::offer);`, and the session gains:

```java
    /** Named {@code wifiMac}, but it is the MAC of the active interface, wired or not; a garbled one is ignored. */
    static Optional<String> reportedMac(TizenDeviceInfo info) {
        try {
            return info.wifiMac().isEmpty() ? Optional.empty() : Optional.of(MacAddress.normalize(info.wifiMac()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
```

with `import dev.andre.homecontrol.core.MacAddress;`. `TizenDeviceInfo.macAddress()` and its `MacAddress` import go.

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.tizen.TizenSessionTest`
Expected: PASS.

- [ ] **Step 4: Move the Tizen protocol classes and their tests**

```bash
mkdir -p src/main/java/dev/andre/homecontrol/adapters/tizen/protocol src/test/java/dev/andre/homecontrol/adapters/tizen/protocol
git mv src/main/java/dev/andre/homecontrol/adapters/tizen/TizenRemoteConnection.java src/main/java/dev/andre/homecontrol/adapters/tizen/TizenMessages.java src/main/java/dev/andre/homecontrol/adapters/tizen/TizenRest.java src/main/java/dev/andre/homecontrol/adapters/tizen/DialClient.java src/main/java/dev/andre/homecontrol/adapters/tizen/DialException.java src/main/java/dev/andre/homecontrol/adapters/tizen/TizenApp.java src/main/java/dev/andre/homecontrol/adapters/tizen/TizenDeviceInfo.java src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/
git mv src/test/java/dev/andre/homecontrol/adapters/tizen/TizenRemoteConnectionTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenRestTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/DialClientTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/TizenMessagesTest.java src/test/java/dev/andre/homecontrol/adapters/tizen/FakeTizenServer.java src/test/java/dev/andre/homecontrol/adapters/tizen/protocol/
```

In the twelve moved files the package line becomes `package dev.andre.homecontrol.adapters.tizen.protocol;`.

Visibility and options in the moved main classes:
- `TizenRemoteConnection`: `public final class`; `enum Authorization`, `awaitAuthorization`, `token`, `key(String)`,
  `key(String, String)`, `launchApp`, `requestInstalledApps`, `installedApps` public; `open` becomes

  ```java
      /** Nothing that needs closing exists until the socket is open, so a failed open leaves nothing behind. */
      public static TizenRemoteConnection open(HttpClient http, String host, TizenOptions options, String token,
                                               Consumer<String> onClosed) throws IOException {
          Channel channel = new Channel(onClosed);
          TextWebSocket socket = TextWebSocket.connect(http,
                  TizenMessages.remoteUri(host, options.port(), options.clientName(), token),
                  options.connectTimeout(), channel);
          return new TizenRemoteConnection(socket, channel);
      }
  ```

- `TizenMessages`: `public final class`; `JSON` and `installedApps(JsonNode)` public. `remoteUri` builds its URL with
  `DeviceUris` and keeps its encoding:

  ```java
      /** The TV shows {@code name} in its Allow prompt and device list; the token proves an earlier Allow. */
      static URI remoteUri(String host, int port, String clientName, String token) {
          String name = Base64.getEncoder().encodeToString(clientName.getBytes(StandardCharsets.UTF_8));
          StringBuilder pathAndQuery = new StringBuilder("/api/v2/channels/samsung.remote.control?name=")
                  .append(URLEncoder.encode(name, StandardCharsets.UTF_8));
          if (token != null && !token.isBlank()) {
              pathAndQuery.append("&token=").append(URLEncoder.encode(token, StandardCharsets.UTF_8));
          }
          return DeviceUris.of("wss", host, port, pathAndQuery.toString());
      }
  ```

- `TizenRest`: `public final class`; the field `TizenProperties properties` becomes `TizenOptions options`; the
  constructor is `public TizenRest(HttpClient http, TizenOptions options)`; `deviceInfo` and `appVisible` public;
  `getJson` builds `DeviceUris.of("http", host, options.restPort(), path)` and times out after
  `options.requestTimeout()`.
- `DialClient`: `public final class`; `public DialClient(HttpClient http, TizenOptions options)`; `public void
  launch(…)` builds `DeviceUris.of("http", host, options.dialPort(), "/ws/apps/" + app)` and times out after
  `options.requestTimeout()`.
- `DialException`: `public class`. `TizenApp`, `TizenDeviceInfo`: `public record`; `TizenDeviceInfo.on()` public.
- `core.Hosts` imports go from `TizenMessages`, `TizenRest` and `DialClient`; each imports
  `dev.andre.homecontrol.adapters.net.DeviceUris` instead.

Callers in `adapters.tizen`:
- `TizenSession`: `new TizenRest(http, properties)` → `new TizenRest(http, properties.protocol())`,
  `new DialClient(http, properties)` → `new DialClient(http, properties.protocol())`,
  `TizenRemoteConnection.open(http, device.host(), properties, …)` → `…(http, device.host(), properties.protocol(), …)`.
- `TizenPairing`: the same for its `TizenRest` and `TizenRemoteConnection.open`.
- `TizenSession`, `TizenPairing`, `TizenLaunches` (and `TizenLaunch` if it names one) import what they use from
  `dev.andre.homecontrol.adapters.tizen.protocol`.

Tests:
- `FakeTizenServer` gains:

  ```java
      /** Protocol clients pointed at this fake, with two-second waits. */
      public TizenOptions options() {
          return new TizenOptions(port(), httpPort(), httpPort(), "Home Control", Duration.ofSeconds(2),
                  Duration.ofSeconds(2));
      }
  ```

- `TizenRestTest`: `properties(fake)` goes; `new TizenRest(http, properties(fake))` → `new TizenRest(http,
  fake.options())`. `DialClientTest` and `TizenRemoteConnectionTest` use `fake.options()` the same way (their
  `TizenRestTest.properties(fake)` or own properties become `fake.options()`).
- `TizenSessionTest` keeps its own helper, moved from `TizenRestTest`:

  ```java
      private static TizenProperties properties(FakeTizenServer fake) {
          return new TizenProperties(true, fake.port(), fake.httpPort(), fake.httpPort(), "Home Control",
                  Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1),
                  Duration.ofSeconds(0));
      }
  ```

  and `TizenRestTest.properties(tv)` → `properties(tv)`.
- `TizenSessionTest`, `TizenAdapterTest`, `TizenPairingTest`, `TizenLaunchesTest` and `web/TizenEndToEndTest`
  import `FakeTizenServer`, `TizenApp`, `TizenDeviceInfo` and `TizenMessages` from the new packages where they use
  them.

Then `grep -rn "core.Hosts" src/main/java/dev/andre/homecontrol/adapters/tizen src/main/java/dev/andre/homecontrol/adapters/webos`
prints nothing.

- [ ] **Step 5: Run the tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.tizen.*' --tests dev.andre.homecontrol.web.TizenEndToEndTest --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS, the moved `TizenMessagesTest` URL tests included; `protocolPackagesStandAlone` holds for
`adapters.tizen.protocol`.

- [ ] **Step 6: Run the build**

Run: `scripts/gradle.sh build`
Expected: green.

- [ ] **Step 7: Commit**

Message:

```
refactor: Tizen gets a protocol package

TizenRemoteConnection, TizenMessages, TizenRest, DialClient, DialException, TizenApp and
TizenDeviceInfo move to adapters.tizen.protocol, with their tests and FakeTizenServer. They take
a TizenOptions record of ports, client name and Durations instead of the Spring properties record,
and build their URLs with DeviceUris instead of core.Hosts. TizenDeviceInfo reports the raw MAC,
and the session normalises it before LearnedMac stores it. Nothing a user sees changes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 4: Cast's receiver-app choreography moves into `cast.protocol.CastApps`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastApps.java`,
  `src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastRefusedException.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastAppsTest.java` (new),
  `src/test/java/dev/andre/homecontrol/adapters/cast/CastSessionTest.java`

**Interfaces:**
- Produces:
  - `public CastApps(CastConnection connection, Duration commandTimeout, Duration loadTimeout, Duration errorWindow)`
  - `public void receiverCommand(ObjectNode payload) throws IOException`
  - `public ReceiverStatus.ReceiverApp running(ReceiverStatus known, String appId) throws IOException` (`known` may
    be null)
  - `public ReceiverStatus.ReceiverApp speaking(ReceiverStatus.ReceiverApp app, String namespace) throws IOException`
  - `public void load(ReceiverStatus.ReceiverApp app, Map<String, Object> body) throws IOException`
  - `public void send(ReceiverStatus.ReceiverApp app, String namespace, Map<String, Object> message) throws IOException`
  - `public Map<String, Object> query(ReceiverStatus.ReceiverApp app, String namespace, Map<String, Object> message,
    String replyType) throws IOException`
  - `public class CastRefusedException extends DeviceRefusedException` with `public CastRefusedException(String reason)`.

- [ ] **Step 1: Write the failing tests**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastAppsTest.java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CastAppsTest {

    private static final String NS = "urn:x-cast:com.example.test";
    private static final String CUSTOM_APP = "F007D354";
    private static final Map<String, Object> MEDIA_BODY = Map.of("media",
            Map.of("contentId", "http://nas.local/films/bunny.mp4", "contentType", "video/mp4", "streamType", "BUFFERED"),
            "autoplay", true);

    private final CastConnection.Listener ignored = new CastConnection.Listener() {
        @Override
        public void onMessage(CastIncoming message) {
            // The calls under test wait for their own answers.
        }

        @Override
        public void onDisconnected(CastDisconnectCause cause) {
            // Nothing to follow.
        }
    };

    private FakeCastReceiver receiver;
    private CastConnection connection;
    private CastApps apps;

    @BeforeEach
    void connect() throws Exception {
        receiver = new FakeCastReceiver();
        connection = CastConnection.open("127.0.0.1", receiver.port(), Duration.ofSeconds(5), Duration.ofSeconds(10),
                ignored);
        apps = new CastApps(connection, Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(300));
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
        receiver.close();
    }

    /** The receiver's status as a session following it would know it. */
    private ReceiverStatus status() throws Exception {
        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus(),
                Duration.ofSeconds(2));
        return ReceiverStatus.parse(reply.payload().path("status"));
    }

    private static ObjectNode json(String text) {
        return (ObjectNode) JsonMapper.builder().build().readTree(text);
    }

    @Test
    void anAppTheStatusListsIsUsedWithoutLaunching() throws Exception {
        receiver.runApp(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER, "Default Media Receiver");

        ReceiverStatus.ReceiverApp app = apps.running(status(), FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThat(app.appId()).isEqualTo(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
        assertThat(receiver.received(RECEIVER, "LAUNCH")).isEmpty();
    }

    @Test
    void anAppThatIsNotRunningIsLaunched() throws Exception {
        ReceiverStatus.ReceiverApp app = apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThat(app.appId()).isEqualTo(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
        assertThat(receiver.last(RECEIVER, "LAUNCH").orElseThrow().payload().path("appId").asString(""))
                .isEqualTo(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);
    }

    @Test
    void aRefusedLaunchIsARefusalWithTheReceiversReason() {
        receiver.refuseLaunch(FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThatThrownBy(() -> apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER))
                .isInstanceOf(CastRefusedException.class)
                .hasMessageContaining("LAUNCH_ERROR: NOT_FOUND");
    }

    @Test
    void loadsMediaOnTheAppsTransport() throws Exception {
        ReceiverStatus.ReceiverApp app = apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        apps.load(app, MEDIA_BODY);

        assertThat(receiver.last(MEDIA, "LOAD").orElseThrow().destinationId()).isEqualTo(app.transportId());
        assertThat(receiver.virtualConnections()).contains(app.transportId());
    }

    @Test
    void aFailedLoadIsARefusal() throws Exception {
        receiver.failNextLoad();
        ReceiverStatus.ReceiverApp app = apps.running(null, FakeCastReceiver.DEFAULT_MEDIA_RECEIVER);

        assertThatThrownBy(() -> apps.load(app, MEDIA_BODY))
                .isInstanceOf(CastRefusedException.class)
                .hasMessageContaining("LOAD_FAILED");
    }

    @Test
    void aStaleStopIsARefusalWithTheReceiversReason() {
        assertThatThrownBy(() -> apps.receiverCommand(CastPayloads.stop("no-such-session")))
                .isInstanceOf(CastRefusedException.class)
                .hasMessage("INVALID_REQUEST: INVALID_SESSION_ID");
    }

    @Test
    void aReceiverCommandThatIsNeverAnsweredTimesOut() {
        receiver.ignore("SET_VOLUME");

        assertThatThrownBy(() -> apps.receiverCommand(CastPayloads.setVolumeLevel(0.5)))
                .isInstanceOf(DeviceTimeoutException.class);
    }

    @Test
    void anAppThatSpeaksTheNamespaceIsReturnedAtOnce() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        assertThat(apps.speaking(app, NS).speaks(NS)).isTrue();
    }

    @Test
    void anAppThatNeverSpeaksTheNamespaceTimesOut() throws Exception {
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        assertThatThrownBy(() -> apps.speaking(app, NS)).isInstanceOf(DeviceTimeoutException.class);
    }

    @Test
    void aCustomMessageNobodyRejectsIsTaken() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        apps.send(app, NS, Map.of("command", "PlayNow"));

        assertThat(receiver.received(NS, "")).hasSize(1);
    }

    @Test
    void anErrorAnswerToACustomMessageIsARefusalWithItsMessage() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        receiver.answerCustom(NS, json("""
                {"type":"error","message":"Missing one or more required params"}
                """));
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);
        Map<String, Object> playNow = Map.of("command", "PlayNow");

        assertThatThrownBy(() -> apps.send(app, NS, playNow))
                .isInstanceOf(CastRefusedException.class)
                .hasMessage("Missing one or more required params");
    }

    @Test
    void aQueryReturnsTheReplyOfTheAskedType() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        receiver.answerCustom(NS, json("""
                {"type":"pong","value":7}
                """));
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);

        Map<String, Object> reply = apps.query(app, NS, Map.of("type", "ping"), "pong");

        assertThat(reply).containsEntry("value", 7);
    }

    @Test
    void anErrorReplyToAQueryIsARefusal() throws Exception {
        receiver.appSpeaks(CUSTOM_APP, NS);
        receiver.answerCustom(NS, json("""
                {"type":"error","message":"nope"}
                """));
        ReceiverStatus.ReceiverApp app = apps.running(null, CUSTOM_APP);
        Map<String, Object> ping = Map.of("type", "ping");

        assertThatThrownBy(() -> apps.query(app, NS, ping, "pong"))
                .isInstanceOf(CastRefusedException.class)
                .hasMessage("nope");
    }
}
```

In `CastSessionTest`, the four assertions that pin refusal texts take the `DeviceCalls` wording:

- `.hasMessage("Living Room TV refused to stop Default Media Receiver (INVALID_REQUEST: INVALID_SESSION_ID)")` →
  `.hasMessage("Living Room TV refused to stop Default Media Receiver: INVALID_REQUEST: INVALID_SESSION_ID")`
- `.hasMessage("Living Room TV refused to stop CC1AD845 (INVALID_REQUEST: INVALID_SESSION_ID)")` →
  `.hasMessage("Living Room TV refused to stop CC1AD845: INVALID_REQUEST: INVALID_SESSION_ID")`
- `.hasMessageContaining("refused to play it (Missing one or more required params")` →
  `.hasMessageContaining("refused to start playback: Missing one or more required params")`
- `.hasMessageContaining("refused the request (nope)")` → `.hasMessageContaining("refused to answer mdxSessionStatus: nope")`

- [ ] **Step 2: Run them to see them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*'`
Expected: compilation fails: `CastApps` and `CastRefusedException` do not exist. With `CastAppsTest` set aside for a
moment, the four changed `CastSessionTest` assertions FAIL on today's wording; restore `CastAppsTest`.

- [ ] **Step 3: Write `CastApps` and its exception**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastRefusedException.java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.DeviceRefusedException;

/** A receiver answered and said no; the message is its reason. */
public class CastRefusedException extends DeviceRefusedException {

    public CastRefusedException(String reason) {
        super(reason);
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastApps.java
package dev.andre.homecontrol.adapters.cast.protocol;

import dev.andre.homecontrol.adapters.net.DeviceTimeoutException;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.MEDIA;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.PLATFORM_RECEIVER_ID;
import static dev.andre.homecontrol.adapters.cast.protocol.CastNamespaces.RECEIVER;

/**
 * What a sender does with receiver apps over one {@link CastConnection}: start them, wait until a custom one speaks
 * its namespace, and exchange receiver, media and custom messages with them. Every call blocks its caller; none may
 * run on the connection's reader thread. No answer in time is a {@link DeviceTimeoutException}; an answer that says
 * no is a {@link CastRefusedException} carrying the receiver's reason.
 */
public final class CastApps {

    private static final String RECEIVER_STATUS = "RECEIVER_STATUS";
    private static final String STATUS_FIELD = "status";
    private static final Set<String> CUSTOM_ERROR_TYPES = Set.of("error", "connectionerror", "playbackerror");

    private final CastConnection connection;
    private final Duration commandTimeout;
    private final Duration loadTimeout;
    private final Duration errorWindow;

    /**
     * {@code errorWindow}: receivers check a custom request at once, and loading goes on after the request returns,
     * so a rejection that has not arrived within it counts as accepted.
     */
    public CastApps(CastConnection connection, Duration commandTimeout, Duration loadTimeout, Duration errorWindow) {
        this.connection = connection;
        this.commandTimeout = commandTimeout;
        this.loadTimeout = loadTimeout;
        this.errorWindow = errorWindow;
    }

    /** A receiver-namespace command, answered with a RECEIVER_STATUS; any other answer is a refusal. */
    public void receiverCommand(ObjectNode payload) throws IOException {
        CastIncoming reply = connection.request(RECEIVER, PLATFORM_RECEIVER_ID, payload, commandTimeout);
        if (!RECEIVER_STATUS.equals(reply.type())) {
            throw new CastRefusedException(reply.describeFailure());
        }
    }

    /** The app as {@code known} lists it, or else launched. {@code known} may be null. */
    public ReceiverStatus.ReceiverApp running(ReceiverStatus known, String appId) throws IOException {
        Optional<ReceiverStatus.ReceiverApp> listed = known == null ? Optional.empty() : known.app(appId);
        return listed.isPresent() ? listed.get() : launch(appId);
    }

    /** {@code app} once it lists {@code namespace}: a freshly launched custom receiver announces it in a later status. */
    public ReceiverStatus.ReceiverApp speaking(ReceiverStatus.ReceiverApp app, String namespace) throws IOException {
        if (app.speaks(namespace)) {
            return app;
        }
        String appId = app.appId();
        CastConnection.Waiter ready = connection.expect(incoming -> RECEIVER.equals(incoming.namespace())
                && RECEIVER_STATUS.equals(incoming.type())
                && ReceiverStatus.parse(incoming.payload().path(STATUS_FIELD)).app(appId)
                        .filter(candidate -> candidate.speaks(namespace)).isPresent());
        CastIncoming status;
        try {
            connection.send(RECEIVER, PLATFORM_RECEIVER_ID, CastPayloads.getStatus());
            status = ready.await(loadTimeout);
        } finally {
            ready.cancel();
        }
        return ReceiverStatus.parse(status.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    /** Connects to the app's transport and loads media there; any answer but MEDIA_STATUS is a refusal. */
    public void load(ReceiverStatus.ReceiverApp app, Map<String, Object> body) throws IOException {
        connection.connect(app.transportId()); // harmless if the media follower already connected
        CastIncoming reply = connection.request(MEDIA, app.transportId(), CastPayloads.load(app.sessionId(), body),
                loadTimeout);
        if (!"MEDIA_STATUS".equals(reply.type())) {
            throw new CastRefusedException(reply.describeFailure());
        }
    }

    /** Sends a custom message; an error answer within the error window is a refusal, silence is acceptance. */
    public void send(ReceiverStatus.ReceiverApp app, String namespace, Map<String, Object> message) throws IOException {
        connection.connect(app.transportId());
        CastConnection.Waiter rejection = connection.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && CUSTOM_ERROR_TYPES.contains(incoming.type()));
        try {
            connection.send(namespace, app.transportId(), CastPayloads.custom(message));
            CastIncoming error;
            try {
                error = rejection.await(errorWindow);
            } catch (DeviceTimeoutException _) {
                return; // no rejection: the receiver took the request
            }
            throw new CastRefusedException(reason(error));
        } finally {
            rejection.cancel();
        }
    }

    /** Sends a query and waits (command timeout) for the reply of {@code replyType}; an error reply is a refusal. */
    public Map<String, Object> query(ReceiverStatus.ReceiverApp app, String namespace, Map<String, Object> message,
                                     String replyType) throws IOException {
        connection.connect(app.transportId());
        CastConnection.Waiter answer = connection.expect(incoming -> namespace.equals(incoming.namespace())
                && app.transportId().equals(incoming.sourceId())
                && (replyType.equals(incoming.type()) || CUSTOM_ERROR_TYPES.contains(incoming.type())));
        try {
            connection.send(namespace, app.transportId(), CastPayloads.custom(message));
            CastIncoming reply = answer.await(commandTimeout);
            if (!replyType.equals(reply.type())) {
                throw new CastRefusedException(reason(reply));
            }
            return CastPayloads.toMap(reply.payload());
        } finally {
            answer.cancel();
        }
    }

    private ReceiverStatus.ReceiverApp launch(String appId) throws IOException {
        int requestId = connection.nextRequestId();
        ObjectNode launch = CastPayloads.launch(appId);
        launch.put("requestId", requestId);
        // The reply to LAUNCH can be a RECEIVER_STATUS still showing the previous app; wait for
        // the status that lists ours, or for an error answering our request.
        CastConnection.Waiter outcome = connection.expect(message -> RECEIVER.equals(message.namespace())
                && ((RECEIVER_STATUS.equals(message.type())
                        && ReceiverStatus.parse(message.payload().path(STATUS_FIELD)).app(appId).isPresent())
                    || (message.requestId() == requestId && !RECEIVER_STATUS.equals(message.type()))));
        CastIncoming reply;
        try {
            connection.send(RECEIVER, PLATFORM_RECEIVER_ID, launch);
            reply = outcome.await(loadTimeout);
        } finally {
            outcome.cancel();
        }
        if (!RECEIVER_STATUS.equals(reply.type())) {
            throw new CastRefusedException(reply.describeFailure());
        }
        return ReceiverStatus.parse(reply.payload().path(STATUS_FIELD)).app(appId).orElseThrow();
    }

    private static String reason(CastIncoming reply) {
        String reason = reply.payload().path("message").asString("");
        return reason.isBlank() ? reply.type() : reason;
    }
}
```

- [ ] **Step 4: `CastSession` uses it**

In `CastSession`, `receiverCommand`, `load`, `customMessage`, `query`, `awaitNamespace`, `awaitRejection` and
`launch` are replaced by these (`stopForegroundApp`, `describe` and `requireConnected` stay):

```java
    /** The commands of one connection; built per command, so a command never uses a connection a reconnect replaced. */
    private CastApps apps(CastConnection current) {
        return new CastApps(current, timings.commandTimeout(), timings.loadTimeout(), timings.customMessageErrorWindow());
    }

    /** Runs on the caller's thread: the reply arrives on the reader thread, and the loop keeps following state. */
    private void receiverCommand(ObjectNode payload, String what) {
        CastApps apps = apps(requireConnected());
        DeviceCalls.run(device.name(), what, () -> apps.receiverCommand(payload));
    }

    /** Launch the receiver app unless it already runs, then load the media on its transport. */
    private void load(String appId, Map<String, Object> body) {
        CastApps apps = apps(requireConnected());
        ReceiverStatus.ReceiverApp app = DeviceCalls.run(device.name(), "start receiver app " + appId,
                () -> apps.running(receiver, appId));
        DeviceCalls.run(device.name(), "load the media", () -> apps.load(app, body));
    }

    /** Launch the app unless it runs, wait until it speaks {@code namespace}, send; a quick error reply fails. */
    private void customMessage(String appId, String namespace, Map<String, Object> message) {
        CastApps apps = apps(requireConnected());
        ReceiverStatus.ReceiverApp app = speaking(apps, appId, namespace);
        DeviceCalls.run(device.name(), "start playback", () -> apps.send(app, namespace, message));
    }

    /**
     * Launch the app unless it runs, wait until it speaks the namespace, send, and wait
     * (command timeout) for the reply of the asked type; an error reply fails.
     */
    @Override
    public Map<String, Object> query(CastAppQuery query) {
        CastApps apps = apps(requireConnected());
        ReceiverStatus.ReceiverApp app = speaking(apps, query.receiverAppId(), query.namespace());
        return DeviceCalls.run(device.name(), "answer " + query.replyType(),
                () -> apps.query(app, query.namespace(), query.message(), query.replyType()));
    }

    private ReceiverStatus.ReceiverApp speaking(CastApps apps, String appId, String namespace) {
        return DeviceCalls.run(device.name(), "start receiver app " + appId,
                () -> apps.speaking(apps.running(receiver, appId), namespace));
    }
```

`REACH_PREFIX` and `CUSTOM_ERROR_TYPES` go from `CastSession`; so do the imports nothing uses any more
(`DeviceTimeoutException`, `ActionFailedException` if unused, `java.util.Set`); `CastApps` is imported. Run the
helper `prune.py` on the file, or remove them by hand.

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.cast.*' --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS: all of `CastAppsTest`, and `CastSessionTest` with its four new texts (launch refusal, failed load,
the namespace timeout and the query timeout tests unchanged).

- [ ] **Step 6: Run the build**

Run: `scripts/gradle.sh build`
Expected: green. Note `CastSession`'s length for the measures (`wc -l`).

- [ ] **Step 7: Commit**

Message:

```
refactor: Cast's receiver-app choreography moves into cast.protocol.CastApps

Launching a receiver app, waiting for a custom receiver to speak its namespace, loading media and
exchanging custom messages and queries were protocol work inside CastSession. CastApps holds
them now, over one CastConnection, and reports a receiver's no as a CastRefusedException carrying
its reason. CastSession keeps the connection and the state it follows, and translates through
DeviceCalls, so its refusals read like the other TVs': "Living Room TV refused to stop Default
Media Receiver: INVALID_REQUEST: INVALID_SESSION_ID" instead of "… (INVALID_REQUEST: …)".

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 5: UPnP's renderer resolution moves into `upnp.protocol.RendererResolver`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/RendererResolver.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpSession.java`
- Test: `src/test/java/dev/andre/homecontrol/adapters/upnp/protocol/RendererResolverTest.java` (new)

**Interfaces:**
- Produces: `public RendererResolver(HttpClient http, Duration timeout)`; `public RendererResolver.Renderer
  resolve(URI location, String deviceHost, String expectedUdn) throws IOException`; `public record Renderer(
  ServiceEndpoint avTransport, ServiceEndpoint renderingControl, ServiceEndpoint connectionManager, int volumeMax)`
  (the middle two may be null).

- [ ] **Step 1: Write the failing test**

```java
// File: src/test/java/dev/andre/homecontrol/adapters/upnp/protocol/RendererResolverTest.java
package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.adapters.upnp.FakeUpnpRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RendererResolverTest {

    private final RendererResolver resolver = new RendererResolver(SoapClient.httpClient(Duration.ofSeconds(1)),
            Duration.ofSeconds(1));
    private FakeUpnpRenderer renderer;

    @BeforeEach
    void start() throws IOException {
        renderer = new FakeUpnpRenderer();
    }

    @AfterEach
    void stop() throws Exception {
        renderer.close();
    }

    private static String description() throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/upnp/renderer-description.xml"));
    }

    private RendererResolver.Renderer resolve() throws IOException {
        return resolver.resolve(renderer.location(), renderer.host(), FakeUpnpRenderer.UDN);
    }

    @Test
    void findsTheServicesAndTheVolumeRange() throws IOException {
        renderer.setVolumeMax(50);

        RendererResolver.Renderer found = resolve();

        assertThat(found.avTransport().controlUrl().getPath()).isEqualTo("/upnp/control/AVTransport1");
        assertThat(found.renderingControl()).isNotNull();
        assertThat(found.volumeMax()).isEqualTo(50);
    }

    @Test
    void aLocationOffTheDevicesHostIsRefusedUnread() throws Exception {
        try (FakeUpnpRenderer impostor = new FakeUpnpRenderer("127.0.0.2", FakeUpnpRenderer.Layout.GENERIC)) {
            assertThatThrownBy(() -> resolver.resolve(impostor.location(), renderer.host(), FakeUpnpRenderer.UDN))
                    .isInstanceOf(IOException.class);
            assertThat(impostor.requestedPaths()).isEmpty();
        }
    }

    @Test
    void aDescriptionOfAnotherDeviceIsRefused() throws Exception {
        renderer.overrideDescription(description().replace(FakeUpnpRenderer.UDN, "uuid:00000000-0000-0000-0000-000000000bad"));

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("another device");
    }

    @Test
    void aDescriptionThatIsNotXmlIsUnreadable() {
        renderer.overrideDescription("<html>");

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("Unreadable");
    }

    @Test
    void anAvTransportOnAnotherHostLeavesNothingToControl() throws Exception {
        renderer.overrideDescription(description().replace("<controlURL>/upnp/control/AVTransport1</controlURL>",
                "<controlURL>http://192.0.2.1:1/upnp/control/AVTransport1</controlURL>"));

        assertThatThrownBy(this::resolve).isInstanceOf(IOException.class).hasMessageContaining("AVTransport");
    }

    @Test
    void aServiceWithAMalformedTypeIsLeftOut() throws Exception {
        renderer.overrideDescription(description().replace(
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>",
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1#x</serviceType>"));

        assertThat(resolve().renderingControl()).isNull();
    }

    @Test
    void anScpdOffTheDescriptionsHostKeepsTheDefaultVolumeRange() throws Exception {
        renderer.setVolumeMax(50);
        renderer.overrideDescription(description().replace("<SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>",
                "<SCPDURL>http://192.0.2.1:1/scpd/RenderingControl1.xml</SCPDURL>"));

        assertThat(resolve().volumeMax()).isEqualTo(VolumeRange.DEFAULT_MAXIMUM);
        assertThat(renderer.requestedPaths()).doesNotContain("/scpd/RenderingControl1.xml");
    }

    @Test
    void anScpdThatCannotBeReadKeepsTheDefaultVolumeRange() throws Exception {
        renderer.setVolumeMax(50);
        renderer.overrideDescription(description().replace("<SCPDURL>/scpd/RenderingControl1.xml</SCPDURL>",
                "<SCPDURL>/scpd/missing.xml</SCPDURL>"));

        assertThat(resolve().volumeMax()).isEqualTo(VolumeRange.DEFAULT_MAXIMUM);
        assertThat(renderer.requestedPaths()).contains("/scpd/missing.xml");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests dev.andre.homecontrol.adapters.upnp.protocol.RendererResolverTest`
Expected: compilation fails: `RendererResolver` does not exist.

- [ ] **Step 3: Write the resolver**

```java
// File: src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/RendererResolver.java
package dev.andre.homecontrol.adapters.upnp.protocol;

import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceDescription;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceDescriptions;
import dev.andre.homecontrol.discovery.ssdp.protocol.DeviceFetch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Finds a media renderer's services in its device description, under F1's rules ({@link DeviceFetch}): the
 * description only from the device's own address (plain HTTP to an IP literal, 64 KiB at most, no redirects), only a
 * description that names the expected UDN, and only services whose control URL is on the description's host and whose
 * type is well formed. The volume maximum comes from the RenderingControl SCPD on that host, else the default.
 * Locations are never logged.
 */
public final class RendererResolver {

    /** A renderer's services; {@code renderingControl} and {@code connectionManager} may be null. */
    public record Renderer(ServiceEndpoint avTransport, ServiceEndpoint renderingControl,
                           ServiceEndpoint connectionManager, int volumeMax) {
    }

    private static final Logger log = LoggerFactory.getLogger(RendererResolver.class);

    private final HttpClient http;
    private final Duration timeout;

    public RendererResolver(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    /** {@code expectedUdn} is the UDN the description must name, or null to accept any. */
    public Renderer resolve(URI location, String deviceHost, String expectedUdn) throws IOException {
        if (location == null || !DeviceFetch.isSafeToFetch(location, deviceHost)) {
            throw new IOException("No description address on the device's own host");
        }
        DeviceDescription description;
        try {
            description = DeviceDescriptions.parse(
                    DeviceFetch.get(http, location, timeout, DeviceFetch.MAX_DESCRIPTION_BYTES), location);
        } catch (IllegalArgumentException _) {
            throw new IOException("Unreadable device description");
        } catch (InterruptedException _) {
            // Only close() interrupts the poll loop; the attempt fails like any unreachable device.
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading the device description");
        }
        if (expectedUdn != null && (description.udn() == null || !expectedUdn.equalsIgnoreCase(description.udn()))) {
            throw new IOException("The description at the device's address belongs to another device");
        }
        ServiceEndpoint avTransport = service(description, UpnpActions.AV_TRANSPORT, location, deviceHost)
                .orElseThrow(() -> new IOException("No usable AVTransport service"));
        ServiceEndpoint renderingControl = service(description, UpnpActions.RENDERING_CONTROL, location, deviceHost)
                .orElse(null);
        ServiceEndpoint connectionManager = service(description, UpnpActions.CONNECTION_MANAGER, location, deviceHost)
                .orElse(null);
        int volumeMax = renderingControl == null ? 0 : volumeMaximum(renderingControl, location);
        return new Renderer(avTransport, renderingControl, connectionManager, volumeMax);
    }

    /** Services on another host than the (already verified) description location are refused (epic constraint). */
    private static Optional<ServiceEndpoint> service(DeviceDescription description, String typePrefix, URI location,
                                                     String deviceHost) {
        return description.service(typePrefix).map(ServiceEndpoint::of).filter(endpoint -> {
            boolean sameHost = onHost(endpoint.controlUrl(), location);
            if (!sameHost) {
                log.warn("Ignoring {} of the renderer at {}: its control URL is not on the host it announced itself from",
                        typePrefix, deviceHost);
            }
            boolean validType = SoapClient.isValidServiceType(endpoint.serviceType());
            if (!validType) {
                log.warn("Ignoring {} of the renderer at {}: malformed service type", typePrefix, deviceHost);
            }
            return sameHost && validType;
        });
    }

    private static boolean onHost(URI url, URI location) {
        return url != null && "http".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                && url.getHost().equalsIgnoreCase(location.getHost());
    }

    private int volumeMaximum(ServiceEndpoint renderingControl, URI location) {
        URI scpd = renderingControl.scpdUrl();
        if (!onHost(scpd, location)) {
            return VolumeRange.DEFAULT_MAXIMUM;
        }
        try {
            return VolumeRange.maximum(DeviceFetch.get(http, scpd, timeout, DeviceFetch.MAX_DESCRIPTION_BYTES));
        } catch (IOException _) {
            return VolumeRange.DEFAULT_MAXIMUM;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return VolumeRange.DEFAULT_MAXIMUM;
        }
    }
}
```

- [ ] **Step 4: `UpnpSession` uses it**

`UpnpSession` gains a field `private final RendererResolver resolver;`, set in the package-private constructor as
`this.resolver = new RendererResolver(http, timings.commandTimeout());`. In `Link`, `resolve()` becomes the following,
and `service`, `onHost` and `volumeMaximum` go:

```java
        /** The announced location for this UDN, else the stored one; {@link RendererResolver} checks it. */
        private Endpoints resolve() throws IOException {
            Optional<URI> announced = Optional.ofNullable(settings.udn()).flatMap(locator);
            // Whatever the source, the description must live on the registered device's address: an
            // announcement cannot move this session (and the stream URLs it sends) to another host.
            RendererResolver.Renderer found = resolver.resolve(announced.orElse(settings.location()), device.host(),
                    settings.udn());
            ProtocolInfo sink = ProtocolInfo.UNKNOWN;
            if (found.connectionManager() != null) {
                try {
                    sink = commands.sink(found.connectionManager());
                } catch (SoapFault fault) {
                    log.debug("{} did not list its formats: {}", device.id(), fault.getMessage());
                }
            }
            return new Endpoints(found.avTransport(), found.renderingControl(), found.volumeMax(), sink);
        }
```

The imports nothing uses any more go (`DeviceDescription`, `DeviceDescriptions`, `DeviceFetch`,
`InterruptedIOException`, `VolumeRange` and `UpnpActions` if unused); `RendererResolver` is imported. Run
`prune.py` on the file, or remove them by hand. If the `http` field is now unused, it goes too.

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.upnp.*' --tests 'dev.andre.homecontrol.adapters.sonos.*' --tests dev.andre.homecontrol.ArchitectureTest`
Expected: PASS: `RendererResolverTest`, and every `UpnpSessionTest` resolution test (foreign host, wrong UDN, not XML,
malformed type, SCPD off-host and unreadable, announced location off the host).

- [ ] **Step 6: Run the build**

Run: `scripts/gradle.sh build`
Expected: green. Note `UpnpSession`'s length for the measures.

- [ ] **Step 7: Commit**

Message:

```
refactor: UPnP's renderer resolution moves into upnp.protocol.RendererResolver

Reading a renderer's device description and SCPD, under the rules that keep a session on the
registered device's address, was protocol work inside UpnpSession. RendererResolver holds it now
and returns the renderer's services and volume range; the session keeps finding the location and
reading the sink formats. Nothing a user sees changes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

### Task 6: The architecture guide

**Files:**
- Modify: `docs/dev/architecture.md`

**Interfaces:**
- Consumes: Tasks 1–5.

- [ ] **Step 1: Update the guide**

- The `adapters` row of the package table: "with its wire protocol in a `protocol` subpackage where it has one"
  becomes "with its wire protocol in a `protocol` subpackage (Bluetooth's BlueZ and mpv code sits behind interfaces
  instead)". In the `adapters.net` parenthesis, after "Wake-on-LAN" add ", device URLs".
- The `discovery` row: "(the safe description fetch and the description parser)" becomes "(the datagram parser, the
  safe description fetch and the description parser)".
- In the rule table, the protocol rule's row gets a sentence after it, as a new paragraph below the table:

  ```markdown
  A protocol class reports a failed exchange with an `IOException`: a `DeviceTimeoutException` when the device did not
  answer in time, a `DeviceRefusedException` when it answered no, with the device's reason. A parser of received data
  may throw `IllegalArgumentException` for input it cannot read; its caller decides what that means. Sessions turn
  both into core exceptions through `DeviceCalls`, and pairing services into pairing results.
  ```

- "Progress measures": the largest-class row's "Now" cell from
  `find src/main/java -name '*.java' -exec wc -l {} + | sort -n | tail -3` (the largest file, in the form
  `NNN lines (`ClassName`)`).

- [ ] **Step 2: Run the build and the browser tests**

Run: `scripts/gradle.sh build`
Expected: green.

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium`
Expected: 68 tests, 0 failures.

- [ ] **Step 3: Commit**

Message:

```
docs: the architecture guide on protocol packages and their exceptions

Every device adapter but Bluetooth now keeps its wire code in a protocol subpackage, and
discovery.ssdp.protocol holds all of SSDP's. The guide says so, says how protocol classes report
failures (IOException for an exchange, IllegalArgumentException for unreadable input) and who
turns them into core exceptions, and updates the largest class.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
```

---

## Self-review

- **Spec coverage (sections 2 and 3, PR 2 Testing, Delivery, Visible changes).** webOS protocol package with plain
  values and no core helpers (Task 2); Tizen likewise, the raw MAC normalised in the session (Task 3); `CastApps` with
  `CastRefusedException` and the `DeviceCalls` wording (Task 4); `RendererResolver` with today's rules (Task 5); the
  moved protocol tests and fakes (Tasks 2, 3); `CastAppsTest` and `RendererResolverTest` with the cases the spec lists
  (Tasks 4, 5); the Cast tests that pin refusal texts (Task 4); the architecture guide (Task 6); build and browser
  tests (Task 6). Delivery order webOS, Tizen, Cast, UPnP, docs, with Task 1 settling PR 1's `SsdpMessage` minor first.
- **Measures:** packages with wire code outside `protocol` 3 → 0 (Tasks 1–3); `CastSession` and `UpnpSession` lines
  (Tasks 4, 5).
- **Types.** `DeviceUris.of` (Task 2) is used by Task 3; `SsapOptions`/`TizenOptions` and the properties' builders
  (Tasks 2, 3); `CastApps` methods (Task 4) match `CastSession`'s calls; `RendererResolver.Renderer` (Task 5) matches
  `UpnpSession`'s use.
