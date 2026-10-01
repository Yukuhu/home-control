# Phase 2D: Adapter Lifecycle Support

**Status:** approved in conversation on 2026-10-01, section by section.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "2D: Adapter lifecycle
support". It unblocks 3E (adapter layering).

## Purpose

Seven device sessions, about 2,800 lines, each carry their own copy of the same plumbing, and the copies disagree:

| Session | Lines | Style | Scheduler | Backoff | State publishing | Live connection |
| --- | --- | --- | --- | --- | --- | --- |
| `CastSession` | 527 | push, plus a media-position poll | own platform thread | `net.Backoff` | closed guard, no deduplication | volatile field + generation counter |
| `AndroidTvSession` | 431 | push; usable only once the TV says "ready" | own platform thread | `net.Backoff` | closed guard, no deduplication | volatile field + generation counter |
| `WebOsSession` | 457 | push, plus a liveness poll | own platform thread | `net.Backoff` | deduplicated, but CONNECTING always republished | compare-and-set reference |
| `TizenSession` | 420 | poll that opens a channel when needed | own platform thread | its own time gate for handshakes | deduplicated; a throwing listener is not caught | compare-and-set reference |
| `SonosSession` | 340 | poll | `ReconnectingPoller` | the poller's own copy | deduplicated; no closed guard | none (`live` flag) |
| `UpnpSession` | 315 | poll | `ReconnectingPoller` | the poller's own copy | deduplicated; no closed guard | none (`endpoints` field) |
| `BluetoothSpeakerSession` | 292 | poll | own virtual thread | none | deduplicated; no closed guard | none |

Around them:

- Wake-on-LAN power-on and MAC learning appear only in webOS and Tizen, nearly verbatim.
- Four adapters (webOS, Tizen, Sonos, UPnP) keep the same session registry.
- Sonos and UPnP read and publish renderer state with near-identical code.
- Three timeout exceptions (`CastTimeoutException`, `SoapTimeoutException`, `SsapTimeoutException`) and a catch
  ladder per session translate protocol failures, with slightly different wording.
- `CastTls` repeats `InsecureTls`'s trust-any manager byte for byte.
- `RendererCommands`, `RendererFaultException` and `NowPlayings` build core types inside `upnp.protocol`: 17 of the
  42 frozen ArchUnit violations.

The roadmap names three races:

- **Play/pause:** webOS and Tizen toggle it with a read followed by a set, so two presses can send the same command.
- **Bluetooth:** a command that races `close()` gets a `RejectedExecutionException` after the command itself
  succeeded. Cast and Android TV have the same unguarded scheduling.
- **Sonos and UPnP:** a connect still in flight at `close()` re-enables the session (`endpoints` or `live` set again)
  and can publish afterwards.

After this workstream the sessions compose one toolkit in `adapters.support`, keep only their protocol and their
policies, and the races are gone.

## Decisions (the user's, 2026-10-01)

- **Two PRs.**
  - **PR 1:** the toolkit, the timeout and refusal types with `DeviceCalls`, the renderer code moved out of
    `upnp.protocol`, `CastTls` on `InsecureTls`, virtual request threads, and the three poll-style sessions (Sonos and
    UPnP with `RendererStatePoller`, Bluetooth) with their races.
  - **PR 2:** the four push and hybrid sessions (Cast, Android TV, webOS, Tizen) with `WakeOnLanPower`, `LearnedMac`,
    `SessionRegistry` and the play/pause race.
- **Approach A: a shared `Reconnector`.** A push-style session's `connect()` returns `CONNECTED`, `PENDING`, `RETRY` or
  `STOP`, and the `Reconnector` schedules the attempts. The session policies stay in each `connect()`: Android TV's
  unpaired latch, webOS's key-rejected stop and its wake-triggered reconnect. (Rejected: approach B, building blocks
  only, which keeps four slightly different reconnect loops.)
- **Composition, not inheritance** (roadmap).

## Constraints

- Nothing a user sees changes except the visible changes listed at the end of this spec.
- Protocol packages (`..protocol..`) may depend only on protocol packages and `adapters.net` (ArchUnit
  `protocolPackagesStandAlone`). Anything a protocol class throws therefore lives in `adapters.net`; anything that
  builds core exceptions or core state lives in `adapters.support` or the session's own package.
- A state listener that throws a `RuntimeException` is logged and the session goes on; an `Error` propagates
  (`AndroidTvSessionTest.doesNotContinueConnectingAfterAStateListenerThrowsAnError` pins it).
- `DeviceState.updatedAt` is never shown and drives no decision (`sameIgnoringTime`: "equal in everything the UI
  shows"; `DeviceStates.compose` only carries it). Suppressing states that differ only in it is invisible.
- Frozen violations: never refreeze; a fixed violation's line leaves the store in the same commit.

## Design

### 1. The toolkit, `adapters.support` (PR 1)

```java
/** A device's state as its listener sees it: only changes the UI shows, never after close. */
public final class StatePublisher {
    public StatePublisher(String deviceId, DeviceState initial, Consumer<DeviceState> listener);
    public DeviceState current();
    public DeviceState update(UnaryOperator<DeviceState> change);  // atomic read-change-publish
    public void publish(DeviceState next);
    public void close();                                           // nothing is published afterwards
}
```

- **One deduplication rule.** A state is published, and becomes `current()`, only when it differs from the last
  published one in something other than its timestamp (`sameIgnoringTime`).
- **Listener failures:** a `RuntimeException` from the listener is logged with the device id. An `Error` propagates.
- `update` and `publish` serialize on the publisher, so command threads and the session loop cannot interleave a
  read-change-publish.

```java
/** One virtual thread per session; scheduling after close is a no-op, never an exception. */
public final class SessionLoop implements AutoCloseable {
    public SessionLoop(String name);
    public boolean execute(Runnable task);                  // false once closed
    public boolean schedule(Runnable task, Duration delay); // the one pending task: replaces the previous one
    public void every(Runnable task, Duration interval);    // fixed delay; exceptions logged
    public void close();                                    // cancels everything
}
```

- **Threads:** every session runs on a virtual thread. Cast, Android TV, webOS and Tizen use platform threads today.
- **Closed loop:** `execute` and `schedule` return false instead of throwing `RejectedExecutionException`.

```java
public final class Backoff {                    // replaces the static net.Backoff
    public Backoff(Duration initial, Duration max);
    public Duration next();                     // the current delay, then doubles up to max
    public void reset();
}

/** Holds one live connection; every connection it gives up is closed exactly once. */
public final class ConnectionSlot<C extends AutoCloseable> {
    public boolean set(C connection);           // false, and the connection is closed, once the slot is closed
    public Optional<C> current();
    public boolean takeIf(C expected);          // compare-and-remove, then close
    public void close();                        // takes and closes the current one; refuses later sets
}

/** Drives a push-style session's connect(). */
public final class Reconnector {
    public enum Outcome { CONNECTED, PENDING, RETRY, STOP }
    public Reconnector(SessionLoop loop, Backoff backoff, Supplier<Outcome> connect);
    public void start();                        // first attempt now
    public void lost();                         // retry after backoff.next()
    public void connected();                    // a PENDING attempt became usable: reset the backoff
    public void reconnectNow();                 // reset and attempt now, unless stopped
    public void retryIn(Duration wait);         // webOS after a Wake-on-LAN packet
}
```

- `RETRY` schedules the next attempt after `backoff.next()`. `CONNECTED` resets the backoff. `PENDING` waits for
  `connected()` or `lost()`. `STOP` ends the attempts until the session itself decides otherwise.
- **`ReconnectingPoller`** moves from `upnp.protocol` to `adapters.support` and uses `Backoff`. Its `Link` throws
  only `IOException`: Sonos and UPnP handle `SoapFault` inside their links as each does today (a fault while
  connecting is a failed attempt; a fault while polling is logged and polling goes on).
- `net.Backoff` stays until PR 2 deletes it with its last callers.

### 2. Errors, TLS and virtual request threads (PR 1)

**Two exception types in `adapters.net`:**

- **`DeviceTimeoutException extends IOException`** replaces `CastTimeoutException`, `SoapTimeoutException` and
  `SsapTimeoutException`, which are deleted. PR 1 changes the Cast and webOS catch clauses to the new type without
  restructuring those sessions.
- **`DeviceRefusedException extends IOException`** ("the device answered, and said no") becomes the base of
  `SsapException` (webOS) and `DialException` (Tizen).
- `TextWebSocket` and `MpvIpc` keep throwing a plain `IOException` on a timeout.

**One translation, `adapters.support.DeviceCalls`:**

```java
public static <T> T run(String deviceName, String what, Call<T> call);  // Call: T run() throws IOException
public static void run(String deviceName, String what, VoidCall call);
public static DeviceOfflineException notConnected(String deviceName);   // "{name} is not connected"
```

| Exception | Becomes | Message |
| --- | --- | --- |
| `DeviceTimeoutException` | `ActionFailedException` | "{name} did not answer in time when asked to {what}" |
| `DeviceRefusedException` | `ActionFailedException` | "{name} refused to {what}: {reason}" |
| any other `IOException` | `DeviceOfflineException` | "{name} could not be reached to {what}" |

`what` is a verb phrase ("set the volume", "press the HOME button"). Bluetooth keeps its own mapping: its failures
are mpv and BlueZ errors with redacted stream details.

**Renderer code out of the protocol package.** `RendererCommands`, `RendererFaultException` and `NowPlayings` move
from `upnp.protocol` to `adapters.support`. `RendererCommands` keeps its `SoapFault` handling (Sonos's retry needs the
fault code) and sends timeouts and I/O failures through `DeviceCalls`. The 17 frozen lines for these classes leave
the store.

**TLS.** `InsecureTls.trustingAnyCertificate()` becomes public and `CastTls` uses it instead of its own copy.
`TlsSockets` (Android TV) is unchanged: it pins a client certificate and a fingerprint.

**Virtual request threads.** `spring.threads.virtual.enabled: true` in `application.yaml`. No request thread holds a
`ThreadLocal`; `synchronized` no longer pins a carrier on JDK 25; Argon2 stays capped at two concurrent hashes. A
configuration test pins the setting.

### 3. The poll-style sessions (PR 1: Sonos, UPnP, Bluetooth)

```java
/** Reads a UPnP renderer's state and publishes it; shared by Sonos and UPnP. */
public final class RendererStatePoller {
    public record Endpoints(ServiceEndpoint avTransport, ServiceEndpoint renderingControl, // control may be null
                            int volumeMax, boolean volumeRequired) { }
    public RendererStatePoller(RendererCommands commands, StatePublisher publisher, Duration pollInterval,
                               Duration playingPollInterval);
    public void read(Endpoints endpoints) throws IOException; // transport, position, volume → publish
    public Duration nextPollDelay();                          // faster while something plays
    public void lost(Exception cause);                        // publish DISCONNECTED
}
```

- **Shared:** transport and position reading with the existing position fallback, `NowPlayings.of(info, position,
  lastPlayed)` and the last-played memory, the volume read, publishing, and the poll delay.
- **Sonos keeps** its topology, coordinator and topology refresh; its volume is required with a maximum of 100, so a
  volume fault fails the poll.
- **UPnP keeps** its description and SCPD resolution; its rendering control is optional, its volume maximum comes from
  the SCPD, and a volume fault is ignored.
- **The after-close race.** `close()` closes the publisher first, so a connect that finishes afterwards publishes
  nothing. The command gate checks the session's closed flag as well as `endpoints` (UPnP) or `live` (Sonos), so a late
  connect no longer re-enables commands.

**Bluetooth** uses `SessionLoop` instead of its own scheduler: `start`, `pollNow` and the self-rescheduling poll are
no-ops once the session is closed, so a command that races `close()` answers with its own result. `StatePublisher`
replaces its `publish`/`report` pair and adds the closed guard. Commands stay serialized on their monitor; the poll
keeps its fixed intervals.

### 4. The push and hybrid sessions (PR 2: Cast, Android TV, webOS, Tizen)

**On the `Reconnector`:**

| Session | Outcome | When |
| --- | --- | --- |
| Cast | `CONNECTED` / `RETRY` | It never stops on its own |
| Android TV | `PENDING` | The connection opened; "ready" calls `connected()`, a drop calls `lost()` |
| Android TV | `STOP` | Certificate mismatch, or the unpaired latch after 5 confirmations (policy unchanged) |
| webOS | `CONNECTED` | After registration |
| webOS | `STOP` | No client key, or the key was rejected; the state becomes UNPAIRED |

- Each session holds its connection in a `ConnectionSlot`; stale callbacks are recognised by connection identity,
  which replaces the generation counters in Cast and Android TV.
- Cast's media-position poll and webOS's liveness check run as `loop.every(...)`.
- Android TV drops its legacy session-level `RemoteListener`, which duplicates the per-attempt listener.

**Tizen stays poll-driven, without a `Reconnector`.** Its poll runs on `loop.every`; the handshake gate takes its
delay from `Backoff`; the stop flag stays. Its channel lives in a `ConnectionSlot`, which closes the channel exactly
once when the app request fails after the channel was set. A throwing state listener is logged like everywhere else
(today it reaches whoever pressed the power button).

**Shared helpers in `adapters.support`:**

```java
public final class WakeOnLanPower {   // the identical "power on" half of webOS and Tizen
    public WakeOnLanPower(String deviceName, Supplier<Device> device, String adapterId, WakeOnLan wakeOnLan);
    public void wake();               // today's two messages: no MAC known yet / the packet could not be sent
}
public final class LearnedMac {       // offered by webOS on connect and by Tizen on each poll
    public LearnedMac(Supplier<Device> device, String adapterId, LearnedSettings learned);
    public void offer(String mac);    // stored unless entered by hand or already known
}
public final class SessionRegistry<S> { // webOS, Tizen, Sonos and UPnP adapters
    public S open(String deviceId, Function<Runnable, S> create); // create gets the callback that removes exactly this session
    public Optional<S> find(Predicate<S> matches);                 // by host, UUID or UDN
}
```

- Power off stays protocol-specific. After waking, webOS calls `reconnector.retryIn(wakeGrace)` and Tizen schedules a
  poll.
- `LearnedMac` reads the MAC settings from the device itself, so Tizen no longer resolves its pairing key on every
  poll for this check.
- All four sessions translate through `DeviceCalls` and `DeviceCalls.notConnected`.
- The play/pause toggle in webOS and Tizen becomes atomic (one compare-and-set loop).
- `net.Backoff` and `BackoffTest` are deleted with their last callers.

### 5. Rules

- **PR 1:** the frozen store shrinks from 42 to 25 lines. `adaptersAreIndependent` gains one stated exception:
  `adapters.support` may use `upnp.protocol` (as Sonos already does), since `RendererCommands` speaks SOAP through it.
- **PR 2:** a strict rule: classes named `*Session` in `adapters` do not create executors; only `SessionLoop` and
  `ReconnectingPoller` in `adapters.support` do.

## Testing

- **Toolkit (PR 1):** `StatePublisherTest` (deduplication, closed guard, a `RuntimeException` logged and an `Error`
  propagated, `update` atomic under concurrent callers), `SessionLoopTest` (no exception after close, `schedule`
  replaces the pending task, `every` survives a throwing task), `BackoffTest` (moved and adapted), `ConnectionSlotTest`
  (each connection closed exactly once across `set`/`takeIf`/`close` races), `ReconnectorTest` (each outcome, backoff
  growth and reset, `PENDING` then `connected()`/`lost()`, `STOP`, `retryIn`), `ReconnectingPollerTest` (moved),
  `DeviceCallsTest`, `RendererStatePollerTest`.
- **Races (PR 1):** a UPnP description fetch and a Sonos topology call held on the fake until `close()` ran: neither
  publishes nor accepts a command afterwards. Bluetooth: `close()` while a command is in flight, and the command still
  returns its result.
- **PR 2:** two concurrent play/pause presses send play once and pause once (webOS and Tizen); `WakeOnLanPower`,
  `LearnedMac` and `SessionRegistry` unit tests; a Tizen app-list failure closes its channel exactly once. Tests that
  count CONNECTING publications as connection attempts (Cast, Android TV) count attempts at the fake device instead.
- Every existing session test stays green, unchanged except for those attempt counts and the moved types.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` at the end of each PR.

## Delivery

**PR 1** (`refactor/adapter-lifecycle`, from main `e9f5e9d`):

1. `refactor:` the toolkit (`StatePublisher`, `SessionLoop`, `Backoff`, `ConnectionSlot`, `Reconnector`) and
   `ReconnectingPoller` moved into `adapters.support`.
2. `refactor:` `DeviceTimeoutException`, `DeviceRefusedException` and `DeviceCalls`; the three timeout clones deleted.
3. `refactor:` the renderer code moved out of `upnp.protocol`, with `RendererStatePoller`; the store shrinks to 25.
4. `fix:` Sonos and UPnP stay closed after `close()`.
5. `fix:` a Bluetooth command that races `close()` gets its own result.
6. `refactor:` `CastTls` uses `InsecureTls`.
7. `feat:` web requests run on virtual threads.
8. `docs:` the architecture guide's adapters row.

**PR 2** (planned after PR 1 merges): the four sessions on the toolkit, the shared helpers, `fix:` for the play/pause
toggle and the Tizen double close, the executor rule, and `docs:` for the testing guide.

No ADR: a package of helpers is easy to reverse.

## Visible changes

- Web requests run on virtual threads (approved in the roadmap).
- webOS's and Cast's "offline" messages read "{name} could not be reached to {what}" instead of "dropped the
  connection"; Android TV's messages name the device.
- A Tizen state-listener failure no longer reaches whoever pressed a button.
- Sonos and UPnP no longer accept commands or publish after `close()`; a Bluetooth command racing `close()` answers
  with its own result; concurrent play/pause presses alternate.

## Measures

| | Before | After |
| --- | --- | --- |
| Executors owned by sessions | 6 | 0 |
| Backoff implementations | 4 | 1 |
| Timeout exception types | 3 | 1 |
| Trust-any managers | 2 | 1 |
| Session registries in adapters | 4 | 1 |
| Frozen violations | 42 | 25 |

Each session is expected to shrink by roughly 40–70 lines.

## Out of scope

- `TextWebSocket` and `MpvIpc` timeouts (they stay plain `IOException`).
- Bluetooth's own error translation.
- The remaining frozen violations (`RemoteConnection`, `MediaStatus`, `ServiceEndpoint`, `SonosEndpoints`,
  `UpnpXml`, `SoapClient`) and the webOS and Tizen `protocol/` packages: 3E.
- The rail cache dropping a refresh request while a load is in flight (all sources; noted on #159).
