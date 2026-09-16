# Bluetooth Speakers (Sub-project J) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a household play music on Bluetooth speakers paired with the host running Home Control: an optional module (off by default) that pairs, trusts, connects and forgets A2DP speakers through the host's BlueZ over the D-Bus system socket, registers each speaker as a device with `LOCAL_AUDIO_SINK`, and plays audio `StreamUrl`s (pasted links, Jellyfin tracks) through a server-side `mpv` subprocess into the host audio stack (PipeWire or PulseAudio) — with pause, resume, stop, volume, mute and now-playing, documented host requirements, Compose and CasaOS variants, an `mpv` image variant, and setup-page diagnostics for every host failure mode.

**Architecture:** One adapter module `adapters/bluetooth` on the A/B/F/I contract, switched by `home-control.bluetooth.enabled` (default `false`). `adapters/bluetooth/bluez` hides BlueZ behind the small `BluezClient` interface; `DbusBluezClient` is the only class in the code base importing `org.freedesktop.dbus`, `org.bluez` or `com.github.hypfvieh` and is instantiated only inside `BluetoothConfiguration` (`@ConditionalOnProperty`), so a disabled module never loads a D-Bus class (proved by a child-JVM class-loading test). Tests use `FakeBluezClient`. `adapters/bluetooth/player` owns the server-side player: `ProcessMpvLauncher` starts `mpv --idle=once --no-video --input-ipc-server=<socket> --audio-device=<id>`, `MpvIpc` speaks mpv's JSON IPC over a Unix domain socket (`UnixDomainSocketAddress`), `MpvPlayer` is one player per speaker (the stream URL is sent with `loadfile` over IPC, never on the command line), `AudioDeviceResolver` finds the speaker's PipeWire/PulseAudio sink by its MAC in `mpv --audio-device=help`. `BluetoothSpeakerSession` (a `DeviceHandle`) polls BlueZ and the player, reports now-playing, and stops playback when the speaker disconnects. `BluetoothPairingService` + `BluetoothSetupController` + `BluetoothHostChecks` render the pairing UI and diagnostics on `/setup`. Core gains `Route.PlayLocally` and `LocalAudioSinkStrategy` (spec §5.3 rung 5, last), and I's `Action.PlayMedia/Pause/Resume/Stop` are also accepted by `LOCAL_AUDIO_SINK`; C7's Jellyfin resolver builds a stream from the server's own Jellyfin address for local sinks.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Jackson 3 under `tools.jackson`), Gradle through `.superpowers/gradle.sh`, Thymeleaf, htmx, vanilla ES modules, JDK `SocketChannel` over `StandardProtocolFamily.UNIX`, `ProcessBuilder`, JUnit 5, AssertJ, Mockito, Awaitility, SnakeYAML (already on the test classpath through Spring Boot). **New dependencies (verified on repo1.maven.org 2026-09-16):**
- `com.github.hypfvieh:bluez-dbus:0.3.5` — `maven-metadata.xml` `<release>0.3.5</release>`, last updated 2026-06-10; compiled for Java 17 (`maven.compiler.release` 17, runs on 25); depends on `dbus-java-core` 5.2.0 and `slf4j-api`. API checked with `javap`: `org.bluez.Adapter1.StartDiscovery/StopDiscovery/RemoveDevice(DBusPath)`, `org.bluez.Device1.Pair/Connect/Disconnect/CancelPairing` (throwing `org.bluez.exceptions.Bluez*Exception`), wrappers `com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter(Adapter1, String, DBusConnection)` with `setPowered(boolean)`, `BluetoothDevice(Device1, BluetoothAdapter, String, DBusConnection)` with `setTrusted(boolean)`. (The wrapper's `pair()`/`connect()` return `boolean` and swallow the reason, so this plan calls `Device1` directly.)
- `com.github.hypfvieh:dbus-java-core:5.2.1` — `<release>5.2.1</release>`, last updated 2026-09-12; pinned above bluez-dbus's 5.2.0; Java 17 bytecode; `module-info` requires `jdk.security.auth` (present in `eclipse-temurin:25-jre`) for SASL EXTERNAL. Used: `DBusConnectionBuilder.forAddress(String).build()`, `DBusConnection.getRemoteObject(String, String, Class)`, `org.freedesktop.dbus.interfaces.ObjectManager.GetManagedObjects()`, `MethodCall.setDefaultTimeout(long)`, `org.freedesktop.dbus.types.Variant`, `DBusPath.getPath()`, `DBusExecutionException.getType()`.
- `com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.1` — `<release>5.2.1</release>`; the JDK 16+ Unix-socket transport (`NativeTransportProvider` via `META-INF/services`), no JNR/JNI.

**Runtime tool (not a Maven dependency):** `mpv` 0.41.0 from Ubuntu 26.04.1 (the `eclipse-temurin:25-jre` base). Verified 2026-09-16: `apt-get install --no-install-recommends mpv` grows `/usr` from 139 MB to 571 MB (+~430 MB, 195 packages); `mpv --ao=help` lists `pipewire`, `pulse`, `alsa`; `mpv --audio-device=help` without a sound server prints exactly:

```text
List of detected audio devices:
  'auto' (Autoselect device)
  'alsa' (Default (alsa))
  'jack' (Default (jack))
  'sndio' (Default (sndio))
```

**Spec:** `docs/superpowers/specs/2026-09-16-home-control-center-concept.md` — §2 (plug-and-play, ephemeral commands), §3 assumption 5 (Bluetooth lowest priority), §4.1 Bluetooth row (host D-Bus socket, adapter, audio stack; Raspberry Pi works, NAS awkward), §5.1 (`LOCAL_AUDIO_SINK`), §5.2 (`StreamUrl`), §5.3 rung 5 (`LOCAL_AUDIO_SINK` and item is audio with `StreamUrl` → server player), §6.1 (setup page), §6.2 (now playing), §7 (`adapters/bluetooth/`: BlueZ D-Bus + local player, optional module), §7.1 (`bluez-dbus` + `mpv` subprocess), §11 (Bluetooth host plumbing risk: optional module, documented checklist, no impact on other features), §12, §13 question 4. Roadmap `docs/superpowers/plans/2026-09-16-home-control-center-roadmap.md` section J (J1–J4, GitHub epic #14, tasks #74–#77). Contracts: A `2026-09-16-multi-device-core.md`, B `2026-09-16-google-cast-adapter.md`, C `2026-09-16-jellyfin-source.md` (C6 `JellyfinStreams`, C7 `JellyfinPlayableResolver`/`PlaybackService`, C9 end-to-end frame), D `2026-09-16-dashboard-shell.md` (`RouteKeys`, drawer, `setup.html` sections), F `2026-09-16-smart-tv-adapters.md` (`core.MacAddress`, `DeviceState.sameIgnoringTime`), I `2026-09-16-wifi-speakers.md` (`Action.PlayMedia/Pause/Resume`, `Action.acceptedBy`, `core.RedactedUris`, `Route.Render`, `MediaRendererStrategy`, renderer drawer controls, music rails, `item-track.json`/`music-recent.json` fixtures, `SpeakerJellyfinEndToEndTest` frame). J is executed last: everything A, B, C, D, E, F, G, H and I produce is assumed to exist.

## Global Constraints

Program constraints (roadmap):

- LAN appliance: one Docker container, host networking for discovery, no cloud relay, plain HTTP works.
- One build and runtime: Spring Boot, Thymeleaf, htmx, SSE, vanilla ES modules. No Node production build, no frontend framework.
- Plug-and-play: device setup is the device's own pairing flow (here: the speaker's pairing mode plus one button on the setup page). No ADB, no developer mode.
- Commands are ephemeral: a command or play request that cannot be carried out now fails now with a reason. Nothing is queued or retried later.
- Only adapters speak device protocols; only sources speak content APIs. `core`, `device`, `playback`, `web`, `security`, `content` and `sources` must not import anything under `adapters.bluetooth`.
- Route by capability, not by brand. The planner never looks at `DeviceKind`.
- Persistent state stays in `/data` as JSON written atomically; `devices.json` and `keystore.p12` keep working without re-pairing. BlueZ keeps the Bluetooth link keys on the host (`/var/lib/bluetooth`), never in `/data`.
- Every adapter is a Spring `@ConditionalOnProperty` module that can be switched off. Every adapter has a fake in tests; sources have recorded JSON fixtures; the planner has pure unit tests.
- Conventional commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `build:`, `ci:`), each ending with the trailer lines required by `.superpowers/sdd/implementer-common.md`. Stage only your own files.

Build and tooling:

- There is no local JDK. "Build" means `.superpowers/gradle.sh build`; focused tests `.superpowers/gradle.sh test --tests '<pattern>'`. Failing test detail: grep `<failure` in `build/test-results/test/*.xml`.
- Spring Boot 4.1.1: MockMvc test auto-configuration is `org.springframework.boot.webmvc.test.autoconfigure`; `@MockitoBean` is `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Jackson 3: `tools.jackson.databind.JsonNode`, `tools.jackson.databind.json.JsonMapper`, `tools.jackson.databind.node.ObjectNode/ArrayNode`, `tools.jackson.core.JacksonException` (unchecked). Always use `asString(default)`, `asInt(default)`, `asLong(default)`, `asDouble(default)`, `asBoolean(default)`.
- A–I are the contract. Where real code under `src/main/java/dev/andre/homecontrol` differs from their plans' listings (field names, helper names, where D moved the drawer markup, how C9's end-to-end test logs in), adapt the edit to the real code, keep the behaviour this plan specifies, and say so in the task report.

Epic constraints:

- `home-control.bluetooth.enabled` defaults to `false` in `application.yaml` and in the condition (`havingValue = "true"`, no `matchIfMissing`). With the module off nothing of it is instantiated, no D-Bus class is loaded, no socket is opened, no process is started, the setup page shows no Bluetooth section and every other feature behaves exactly as before.
- Only `adapters/bluetooth/bluez/DbusBluezClient.java` may import `org.freedesktop.dbus.*`, `org.bluez.*` or `com.github.hypfvieh.*`. It has no Spring annotation and is constructed only in a `BluetoothConfiguration` bean method. Its constructor performs no I/O; it connects on first use and again after a failure.
- The application must start with the module **on** and no D-Bus socket, no BlueZ, no adapter, no audio server and no `mpv`: every such failure surfaces as a named host check on the setup page and as a clear command error, never as a startup failure.
- Stream URLs may carry a Jellyfin `ApiKey`. A stream URL is passed to `mpv` only through the IPC `loadfile` command — never in `argv` (visible in `ps` on the host), never logged, never put in an exception message, never sent to the browser. Everything read from `mpv`'s stderr or stdout passes `StreamRedaction.redact` before it is stored or shown. Now-playing titles come from the item title or stream metadata, never from a URL or mpv's `filename`/`media-title`.
- The local player only accepts `http`/`https` URLs with an `audio/*` MIME type (checked in the strategy and again in the session). `mpv` always runs with `--no-config --ytdl=no --load-scripts=no --input-default-bindings=no` (no user scripts, no youtube-dl, no config files); mpv's default `--load-unsafe-playlists=no` stays in force so a remote playlist cannot open local files.
- One `mpv` process per speaker at most; a new play request replaces it; `Stop`, a Bluetooth disconnect, closing the device handle, forgetting the device and application shutdown all end it (quit over IPC, then SIGTERM, then SIGKILL).
- Bluetooth and player calls run on the caller's thread for commands and on the session's single virtual-thread scheduler for polling; `DeviceState` is created only through `DeviceState.initial()` and its `with…` methods and published only when `sameIgnoringTime` (F) reports a change.
- Error mapping for commands (B's rule): the speaker is not paired/connected and cannot be connected now → `DeviceOfflineException` (409); the speaker or the host audio path answered but failed (no audio output, mpv missing, stream refused, load timeout) → `ActionFailedException` (502); the speaker cannot do it at all (video, non-HTTP URL, remote keys) → `UnsupportedActionException` (422).
- Unknown device id → HTTP 404 everywhere in the web layer.
- Real speakers, adapters and Raspberry Pi hosts cannot be operated by agents: the acceptance checklist marks every item "Pending — requires real hardware" and is never marked passed.

## Decisions

- Decision: module default `home-control.bluetooth.enabled=false` — it needs host plumbing (D-Bus socket mount, BlueZ, adapter, audio server) that most CasaOS/NAS hosts lack, it starts subprocesses, and spec §11 promises "no impact on other features"; the Compose override and the CasaOS variant switch it on — cost if wrong: users set `HOME_CONTROL_BLUETOOTH_ENABLED=true`.
- Decision: BlueZ through `bluez-dbus` 0.3.5 + `dbus-java-core` 5.2.1 + `dbus-java-transport-native-unixsocket` 5.2.1 (JDK Unix sockets, no native code), hidden behind `BluezClient`; `DbusBluezClient` reads adapters and devices with one `ObjectManager.GetManagedObjects()` call and calls `Device1.Pair/Connect/Disconnect` and `Adapter1.StartDiscovery/StopDiscovery/RemoveDevice` directly, using the library wrappers only for `setPowered`/`setTrusted` — the wrappers' `pair()`/`connect()` swallow the failure reason the setup page must show, and per-property wrapper reads cost one D-Bus round trip each — cost if wrong: one class to rewrite; nothing else sees D-Bus.
- Decision: the D-Bus address is `home-control.bluetooth.dbus-address` (default `unix:path=/run/dbus/system_bus_socket`); the container mounts the host's whole `/run/dbus` directory read-only, not the socket file — a file bind mount goes stale when the host's dbus restarts (new inode), and `connect()` on a socket inside a read-only bind mount works (EROFS applies to regular files, directories and links, not sockets) — cost if wrong: mount `/run/dbus` read-write.
- Decision: no BlueZ pairing agent is registered — BlueZ pairs a request from a caller without an agent with IO capability `NoInputNoOutput` ("Just Works"), which is what A2DP speakers use; a host desktop agent, if any, still answers — cost if wrong: legacy speakers that demand PIN `0000` fail with "refused pairing"; an `Agent1` export answering `0000` can be added inside `DbusBluezClient`.
- Decision: D-Bus reply timeout `home-control.bluetooth.bluez-timeout-seconds` = 45 through the static `MethodCall.setDefaultTimeout` — `Pair()` waits for the speaker and can take 30 s; dbus-java's default is shorter — cost if wrong: a hung `bluetoothd` blocks one poll for 45 s.
- Decision: scanning is blocking: `POST /setup/bluetooth/scan` runs `StartDiscovery` for `scan-seconds` (10), `StopDiscovery`, then lists what the adapter knows, and redirects back; pairing is blocking too (like F's prompt pairing) — one household pairs one speaker at a time, and no polling endpoint or JS is needed — cost if wrong: a servlet thread is held for ≤ 10 s (scan) or ≤ 45 s (pair).
- Decision: scan results list devices that advertise the A2DP sink UUID `0000110b-0000-1000-8000-00805f9b34fb`, or have a BlueZ icon starting with `audio-`, or have no UUIDs yet (many speakers only reveal services after pairing); everything else is counted as "other Bluetooth devices hidden". A device whose UUIDs are known and lack the A2DP sink is refused before pairing; one that reveals no A2DP sink after pairing is removed again and refused — cost if wrong: an exotic speaker without the UUID needs a retry after it is paired on the host with `bluetoothctl`.
- Decision: pairing = `Pair` (skipped when already paired; `AlreadyExists` counts as success) → `Trusted=true` (so the speaker may reconnect by itself) → `Connect` → register. A failed connect still registers the paired speaker and shows "Paired <name>, but it did not connect: <reason>" — pairing already succeeded and the drawer/setup page can connect later — cost if wrong: a registered chip that stays disconnected until the speaker is in range.
- Decision: a speaker becomes `Device("bluetooth-aa-bb-cc-dd-ee-ff", <BlueZ name or MAC>, BLUETOOTH, "AA:BB:CC:DD:EE:FF", {bluetooth: {address, adapter[, audioDevice]}})` registered through B's `DeviceManager.adopt` — `host` holds the MAC so the setup page shows something meaningful; a MAC is never a resolvable host (`InetAddress.getByName` rejects it as an invalid IPv6 literal without DNS), so F's host merge, B's host matching and C's session matching never match it. B's name fallback may absorb a pairing-free Cast receiver with exactly the same name (a speaker that does Cast and Bluetooth), which is intended — cost if wrong: the user splits it on the setup page.
- Decision: `BluetoothSpeakerAdapter.discovered()` is always empty; scan results live only in the Bluetooth setup section — B's generic discovered list would otherwise render them with the Android TV pairing form — cost if wrong: none.
- Decision: `adapter` setting = the adapter's MAC (stable across reboots, unlike `hci0` numbering with several dongles); `home-control.bluetooth.adapter` blank picks the first powered adapter (else the first); a value matches an adapter's MAC or its id (`hci0`); scanning powers an unpowered adapter on — cost if wrong: a moved dongle means re-pairing, which BlueZ requires anyway.
- Decision: the session polls BlueZ every `poll-interval-seconds` (5) and the player every `playing-poll-interval-seconds` (1) while a player runs; status = `UNPAIRED` when BlueZ no longer knows the device as paired, `CONNECTED` when BlueZ reports `Connected`, else `DISCONNECTED`; with `auto-connect` (true) the session tries `Connect` once after start; a play request on a disconnected speaker tries `Connect` once before playing — speakers are often switched off, and paging a missing speaker every few seconds is wasteful — cost if wrong: a speaker switched on later shows disconnected until something is played or the user presses Connect (or the speaker reconnects itself, which trusted speakers usually do).
- Decision: when BlueZ reports the speaker disconnected while a player runs, the session quits the player — PipeWire and PulseAudio move an orphaned stream to the default sink, so music would otherwise continue on the host's HDMI or jack output — cost if wrong: up to one second of audio on another output.
- Decision: server-side player = `mpv` subprocess with JSON IPC, not `ffplay` — mpv has a documented IPC for pause/resume/volume/mute/time-pos/metadata and lists audio devices; ffplay has no control channel — cost if wrong: ~430 MB image layer.
- Decision: the default image stays without mpv; `Dockerfile` and `Dockerfile.dist` gain `ARG WITH_MPV=false` and install `mpv --no-install-recommends` only when `true`; CI publishes a second multi-arch image with the suffix `-bluetooth` (`<version>-bluetooth`, `<major.minor>-bluetooth`, `latest-bluetooth`) and the image job proves the `WITH_MPV=true` build on amd64 — +430 MB would triple the image for every user while Bluetooth is the lowest-priority feature — cost if wrong: a slower release job (apt under QEMU for arm64, cached per scope).
- Decision: one `mpv` process per play: `--idle=once` so mpv exits by itself when the track ends; the URL goes in `loadfile` over IPC after the socket is up; a new play quits the previous process first; `Stop` sends `quit` — no stale player keeps the audio device open, and `argv` never contains a credential — cost if wrong: ~0.3 s start latency per track.
- Decision: mpv command line (normative, `MpvCommandLine`): `--no-config --idle=once --no-video --input-terminal=no --msg-level=all=error --ytdl=no --load-scripts=no --input-default-bindings=no --audio-client-name=home-control --volume-max=100 --volume=<0..100> --network-timeout=15 --audio-device=<id> --input-ipc-server=<socket>` — `--volume-max=100` maps `Action.SetVolume` percent 1:1; stderr is kept (last 20 redacted lines) for error messages — cost if wrong: an option renamed in a future mpv fails at start with a visible stderr line.
- Decision: volume is mpv's software volume (0–100), remembered per session (default `default-volume` 50) and applied at each start; the speaker's own hardware volume (AVRCP absolute volume through the audio server) is not touched — works identically on PipeWire, PulseAudio and ALSA — cost if wrong: users may need the speaker's buttons for loud rooms.
- Decision: audio device resolution order: the device's manual `audioDevice` setting (setup page) → `home-control.bluetooth.audio-device-template` with `{mac}` (`AA:BB:CC:DD:EE:FF`) and `{mac_}` (`AA_BB_CC_DD_EE_FF`) replaced → the first entry of `mpv --no-config --audio-device=help` whose id contains the MAC in either spelling (case-insensitive), preferring `pipewire/` over `pulse/` over `alsa/`; nothing found → `ActionFailedException` naming the fix. Resolution runs at play time because the sink only exists while the speaker is connected — PipeWire names the sink `bluez_output.AA_BB_CC_DD_EE_FF.1` (older: `….a2dp-sink`), PulseAudio `bluez_sink.AA_BB_CC_DD_EE_FF.a2dp_sink`, bluealsa is addressed as `alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp` — cost if wrong: one `mpv` run (~50 ms) per play.
- Decision: supported audio stacks: PipeWire (with WirePlumber and pipewire-pulse) or PulseAudio on the host, reached through the pulse protocol socket mounted at `/run/pulse` with `PULSE_SERVER=unix:/run/pulse/native`; bluealsa is documented as possible but not in the published image (it needs the bluez-alsa ALSA plugin inside the container and the template property) — Raspberry Pi OS ships PipeWire, and the pulse protocol is one socket for both servers — cost if wrong: bluealsa users extend the image.
- Decision: the player runtime directory is `home-control.bluetooth.runtime-dir` (default `${java.io.tmpdir}/home-control-bluetooth`, mode 0700), socket `mpv-<first 12 hex of SHA-256(device id)>.sock` — Unix socket paths are limited to 108 bytes and device ids are user-visible text — cost if wrong: none.
- Decision: now-playing title = the item title sent with `PlayMedia`, else the `title` (any case) or `icy-title` entry of mpv's `metadata` property, else `Unknown title`; state `PAUSED` when `pause`, `BUFFERING` when `paused-for-cache`, else `PLAYING`; position `time-pos` (0 when unavailable), duration `duration` when > 0 — mpv's `media-title`/`filename` fall back to the URL's last segment including its query, which can hold the Jellyfin key — cost if wrong: radio streams without metadata show the item title.
- Decision: the local route is `Route.PlayLocally(URI url, String mimeType, String title, String subtitle)` with `describe()` = `Play through the server on this Bluetooth speaker` and route key `local-audio`, whose `action()` is I's `Action.PlayMedia`; I's `PlayMedia`, `Pause`, `Resume` and `Stop` are additionally `acceptedBy` `LOCAL_AUDIO_SINK` (their `requires()` stay unchanged) — the drawer's pause/resume/stop/volume endpoints from B and I then work unchanged for speakers — cost if wrong: a device that is both a renderer and a local sink sends `PlayMedia` to its first accepting adapter, which is the renderer rung the planner chose anyway.
- Decision: `LocalAudioSinkStrategy` is the last strategy (spec §5.3 rung 5) and takes the first `StreamUrl` whose MIME type starts with `audio/` and whose scheme is `http`/`https`; the planner explains a video-only item on a local sink with `a Bluetooth speaker plays audio streams only` — the server player must not decode video into a speaker, and pasted `file:` URLs must never reach a server-side player — cost if wrong: an audio file served as `application/octet-stream` is refused (Jellyfin and `AppLinks` always send `audio/*`).
- Decision: C7's resolver builds Jellyfin streams for devices with `LOCAL_AUDIO_SINK` too, using the server's own Jellyfin address (`JellyfinSettings.serverUrl()`) when the device is neither a Cast receiver nor a media renderer — the server itself fetches the stream, and C's "address for TVs and speakers" may not resolve inside the container — cost if wrong: none; a mixed device keeps the device address.
- Decision: host diagnostics (`BluetoothHostChecks`) are six named checks — D-Bus socket, BlueZ, adapter, mpv, audio output — cached for `host-check-cache-seconds` (30) with a "Check again" button, each with a one-sentence fix pointing at `docs/bluetooth-speakers.md`; BlueZ failures are classified from the D-Bus error name/message (`BluezFailures`), including `br-connection-profile-unavailable` → "the host has no Bluetooth audio service" (PipeWire/PulseAudio not running for the host user) — the roadmap's failure modes must be visible where the user acts — cost if wrong: two `mpv` runs and one D-Bus call per setup page view after the cache expires.
- Decision: the Compose variant is an override file `compose.bluetooth.yaml` (`docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build`) and the CasaOS variant a second manifest `casaos/docker-compose.bluetooth.yml` with the **same** app id `dev.andre.shield-remote` (install one or the other) — the default files stay byte-for-byte free of Bluetooth mounts, and implementer rules forbid changing the app id — cost if wrong: CasaOS users switch variants by re-importing.
- Decision: the container keeps running as root (as today) — the host's BlueZ D-Bus policy allows root, and the pulse socket accepts root — cost if wrong: rootless Docker or user-namespace remapping cannot reach BlueZ; documented as a failure mode.
- Decision: "no D-Bus class loaded when off" is proved by `BluetoothClassLoadingTest`, which starts the real application in a child JVM with `-Xlog:class+load=info` twice (module off: no `org.freedesktop.dbus.`, `org.bluez.` or `com.github.hypfvieh.` class; module on: `DbusBluezClient` is loaded, proving the probe works), plus a grep in Final Verification — Spring's `@ConditionalOnProperty` reads class metadata with ASM, but only a fresh JVM can show what was actually loaded — cost if wrong: ~20 s of test time.
- Decision: the fake `mpv` is one test class `FakeMpv` that speaks the IPC protocol in-process (for unit tests through `InProcessMpvLauncher`) and runs as a real subprocess through a generated `#!/bin/sh` script (for `ProcessMpvLauncherTest` and the end-to-end tests); its classpath comes from the system property `home-control.test.runtime-classpath` that `build.gradle.kts` sets from the test runtime classpath — the protocol and the process lifecycle both need proof without a real mpv — cost if wrong: one JVM start (~0.5 s) per subprocess play in tests.
- Decision: task order follows the roadmap: J1 packaging, properties and documentation (the module exists but has no beans), J2 BlueZ client, adapter, session connection state and pairing UI (the speaker appears with `LOCAL_AUDIO_SINK`; playing answers "not available yet"), J3 player, route and now-playing, J4 failure-mode hardening, end-to-end tests, failure-mode documentation and the checklist — every task stays test-first — cost if wrong: none.

## File Structure Map

Sources under `src/main/java/dev/andre/homecontrol/`, tests under `src/test/java/dev/andre/homecontrol/`. Paths are relative to those roots unless they start with `src/`, `docs/`, `casaos/`, `.github/` or are top-level files.

### Files to create

- `adapters/bluetooth/BluetoothProperties.java`, `BluetoothConfiguration.java` (Task 1).
- `adapters/bluetooth/bluez/BluezClient.java`, `BluetoothAdapterInfo.java`, `BluetoothDeviceInfo.java`, `BluezFailure.java`, `BluezException.java`, `BluezFailures.java`, `DbusBluezClient.java` (Task 2).
- `adapters/bluetooth/BluetoothSettings.java`, `BluetoothSpeakerAdapter.java`, `BluetoothSpeakerSession.java`, `BluetoothScan.java`, `BluetoothPairing.java`, `BluetoothSetupException.java`, `BluetoothPairingService.java`, `HostCheck.java`, `BluetoothHostChecks.java`, `BluetoothSetupAdvice.java`, `BluetoothSetupController.java`; `src/main/resources/templates/fragments/bluetooth-setup.html` (Task 2).
- `adapters/bluetooth/player/StreamRedaction.java`, `MpvException.java`, `MpvNotInstalledException.java`, `MpvProcess.java`, `MpvLauncher.java`, `ProcessMpvLauncher.java`, `MpvCommandLine.java`, `MpvIpc.java`, `PlayerStatus.java`, `MpvPlayer.java`, `AudioDevice.java`, `AudioDevices.java`, `AudioDeviceNotFoundException.java`, `AudioDeviceResolver.java`; `core/playback/LocalAudioSinkStrategy.java` (Task 3).
- `compose.bluetooth.yaml`, `casaos/docker-compose.bluetooth.yml`, `docs/bluetooth-speakers.md` (Task 1; failure-mode section Task 4).
- `docs/superpowers/reviews/2026-09-16-bluetooth-speakers-acceptance.md` (Task 4).
- Tests: `deployment/BluetoothDeploymentTest.java`, `adapters/bluetooth/BluetoothModuleSwitchTest.java` (Task 1); `adapters/bluetooth/bluez/FakeBluezClient.java`, `BluezFailuresTest.java`, `DbusBluezClientTest.java`, `adapters/bluetooth/BluetoothSettingsTest.java`, `BluetoothSpeakerSessionTest.java`, `BluetoothSpeakerAdapterTest.java`, `BluetoothPairingServiceTest.java`, `BluetoothHostChecksTest.java`, `web/BluetoothSetupControllerTest.java`, `web/BluetoothSetupOffTest.java`, `ContextSmoke.java`, `adapters/bluetooth/BluetoothClassLoadingTest.java` (Task 2); `adapters/bluetooth/player/FakeMpv.java`, `FakeMpvScript.java`, `InProcessMpvLauncher.java`, `StreamRedactionTest.java`, `MpvCommandLineTest.java`, `MpvIpcTest.java`, `AudioDevicesTest.java`, `AudioDeviceResolverTest.java`, `MpvPlayerTest.java`, `ProcessMpvLauncherTest.java`, `core/playback/LocalAudioSinkStrategyTest.java` (Task 3); `web/BluetoothSpeakerEndToEndTest.java`, `web/BluetoothJellyfinEndToEndTest.java` (Task 4).

### Files to modify

- `build.gradle.kts` — three dependencies (Task 2), test system property `home-control.test.runtime-classpath` (Task 2).
- `Dockerfile`, `Dockerfile.dist` — `WITH_MPV` build argument (Task 1).
- `.github/workflows/ci.yml` — `-bluetooth` image build and publish (Task 1).
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — `home-control.bluetooth.*` (Task 1).
- `src/main/resources/templates/setup.html` — Bluetooth section include (Task 2).
- `core/Action.java` — `acceptedBy` of `PlayMedia`, `Pause`, `Resume`, `Stop` (Task 3).
- `core/playback/Route.java` — `PlayLocally`; `core/playback/PlaybackPlanner.java` — stream explanation; `core/playback/RouteKeys.java` — `local-audio`; `playback/PlaybackService.java` (and any other exhaustive `switch` over `Route`) — execute `PlayLocally`; `HomeControlConfiguration.java` — strategy order (Task 3).
- `sources/jellyfin/JellyfinPlayableResolver.java` — local sinks (Task 3).
- `web/DashboardController.java`, `src/main/resources/templates/dashboard.html` — playback controls, link form and hint for local sinks (Task 3).
- `adapters/bluetooth/BluetoothConfiguration.java`, `BluetoothSpeakerAdapter.java`, `BluetoothSpeakerSession.java`, `BluetoothHostChecks.java` — player (Task 3).
- `README.md` — Bluetooth speakers section and configuration rows (Task 4).
- Tests: `core/ActionTest.java`, `device/DeviceManagerExecuteTest.java`, `core/playback/PlaybackPlannerTest.java`, `core/playback/RouteKeysTest.java`, `playback/PlaybackServiceTest.java`, `sources/jellyfin/JellyfinPlayableResolverTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java` (Task 3); `adapters/bluetooth/bluez/FakeBluezClient.java`, `adapters/bluetooth/player/FakeMpv.java`, `BluetoothSpeakerSessionTest.java`, `BluetoothPairingServiceTest.java`, `BluetoothHostChecksTest.java`, `deployment/BluetoothDeploymentTest.java` (Task 4).

### Files to delete

- None.

---
### Task 1: J1 · Host requirements and packaging

**Files:**
- Create: `adapters/bluetooth/BluetoothProperties.java`, `adapters/bluetooth/BluetoothConfiguration.java`, `compose.bluetooth.yaml`, `casaos/docker-compose.bluetooth.yml`, `docs/bluetooth-speakers.md`
- Modify: `Dockerfile`, `Dockerfile.dist`, `.github/workflows/ci.yml`, `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- Test: `src/test/java/dev/andre/homecontrol/deployment/BluetoothDeploymentTest.java`, `src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothModuleSwitchTest.java`

**Interfaces:**
- Consumes: existing `deployment/CasaOsManifestTest` style (SnakeYAML `org.yaml.snakeyaml.Yaml`), `compose.yaml`, `casaos/docker-compose.yml`, CI workflow jobs `image` and `release` (steps `meta` and `Build and push`).
- Produces:
  - `record BluetoothProperties(boolean enabled, String dbusAddress, String adapter, int scanSeconds, int bluezTimeoutSeconds, int pollIntervalSeconds, int playingPollIntervalSeconds, boolean autoConnect, String mpvPath, Path runtimeDir, String audioDeviceTemplate, int defaultVolume, int playerStartTimeoutSeconds, int loadTimeoutSeconds, int commandTimeoutSeconds, int hostCheckCacheSeconds)` bound to `home-control.bluetooth`, with `static BluetoothProperties defaults()`, `withDbusAddress(String)`, `withMpvPath(String)`, `withRuntimeDir(Path)`, `withAudioDeviceTemplate(String)`, `withAdapter(String)`, `withScanSeconds(int)`, `withTimings(int pollIntervalSeconds, int playingPollIntervalSeconds, int playerStartTimeoutSeconds, int loadTimeoutSeconds, int commandTimeoutSeconds)`, `withAutoConnect(boolean)`, `Optional<Path> dbusSocketPath()`.
  - `BluetoothConfiguration` — `@Configuration(proxyBeanMethods = false)`, `@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")`, `@EnableConfigurationProperties(BluetoothProperties.class)`; no beans yet.
  - Docker build argument `WITH_MPV` (default `false`); published image tags `<version>-bluetooth`, `<major.minor>-bluetooth`, `latest-bluetooth`.
  - `compose.bluetooth.yaml`, `casaos/docker-compose.bluetooth.yml`, `docs/bluetooth-speakers.md` (host checklist).

- [ ] **Step 1: Write the failing deployment tests**

`src/test/java/dev/andre/homecontrol/deployment/BluetoothDeploymentTest.java` — plain JUnit, files read relative to the project directory (the Gradle test working directory), YAML through `new Yaml().load(...)` with the same `map`/`maps` helpers as `CasaOsManifestTest` (copy them). Cases:

- `composeOverrideSwitchesTheModuleOnWithItsMounts`: `compose.bluetooth.yaml` → `services.shield-remote.build.context` = `.`, `services.shield-remote.build.args.WITH_MPV` = `"true"`; `environment` map has `HOME_CONTROL_BLUETOOTH_ENABLED` = `"true"` and `PULSE_SERVER` = `unix:/run/pulse/native`; `volumes` (list of strings) contains exactly `/run/dbus:/run/dbus:ro` and `/run/user/${HOST_AUDIO_UID:-1000}/pulse:/run/pulse`; the service has no `network_mode`, `image` or `ports` key (it inherits host networking from `compose.yaml`).
- `defaultComposeStaysFreeOfBluetooth`: the text of `compose.yaml` contains neither `/run/dbus` nor `BLUETOOTH` nor `WITH_MPV`.
- `casaOsVariantKeepsTheAppAndAddsTheMounts`: `casaos/docker-compose.bluetooth.yml` → service `shield-remote` with `image` `ghcr.io/yukuhu/home-control:latest-bluetooth`, `network_mode` `host`, `restart` `unless-stopped`, `environment` map `HOME_CONTROL_BLUETOOTH_ENABLED` = `"true"`, `PULSE_SERVER` = `unix:/run/pulse/native`; `volumes` are three bind mounts in this order: `/DATA/AppData/$AppID/data` → `/data`; `/run/dbus` → `/run/dbus` with `read_only: true`; `/run/user/1000/pulse` → `/run/pulse`; service `x-casaos.volumes` describes all three containers (`/data`, `/run/dbus`, `/run/pulse`); top-level `x-casaos.id` = `dev.andre.shield-remote`, `main` = `shield-remote`, `architectures` = `[amd64, arm64]`.
- `casaOsDefaultStaysFreeOfBluetooth`: the text of `casaos/docker-compose.yml` contains neither `/run/dbus` nor `latest-bluetooth`.
- `imagesInstallMpvOnlyOnRequest`: for both `Dockerfile` and `Dockerfile.dist`, the text after the **last** `FROM ` line contains the lines `ARG WITH_MPV=false` and `      && apt-get install -y --no-install-recommends mpv \` and `if [ "$WITH_MPV" = "true" ]; then \`; the part before the last `FROM` (build stage of `Dockerfile`) does not contain `mpv`.
- `ciPublishesABluetoothVariant`: `.github/workflows/ci.yml` parsed as YAML → job `release` has a step with `id` `meta-bluetooth` whose `with.flavor` contains `suffix=-bluetooth` and `latest=false`, and a step named `Build and push the Bluetooth variant` whose `with.build-args` = `WITH_MPV=true`, `with.platforms` = `linux/amd64,linux/arm64`, `with.tags` = `${{ steps.meta-bluetooth.outputs.tags }}`; job `image` has a step named `Build Dockerfile with mpv` whose `with.build-args` = `WITH_MPV=true` and `with.push` = `false`. (SnakeYAML reads the key `on` as boolean `true`; do not look it up.)
- `hostDocumentationCoversTheChecklist`: `docs/bluetooth-speakers.md` contains each of: `/run/dbus:/run/dbus:ro`, `systemctl enable --now bluetooth`, `rfkill unblock bluetooth`, `loginctl enable-linger`, `PULSE_SERVER=unix:/run/pulse/native`, `monitor.bluez.seat-monitoring`, `with-logind`, `WITH_MPV=true`, `latest-bluetooth`, `HOME_CONTROL_BLUETOOTH_ENABLED`, `bluetoothctl`, `A2DP`.

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothModuleSwitchTest.java` — `new ApplicationContextRunner().withUserConfiguration(BluetoothConfiguration.class)`:
- `isOffByDefault`: no properties → `context.getBeansOfType(BluetoothProperties.class)` is empty.
- `canBeSwitchedOn`: `home-control.bluetooth.enabled=true` → one `BluetoothProperties` bean with `dbusAddress()` = `unix:path=/run/dbus/system_bus_socket`, `scanSeconds()` 10, `defaultVolume()` 50, `mpvPath()` `mpv`, `runtimeDir()` = `Path.of(System.getProperty("java.io.tmpdir"), "home-control-bluetooth")`.
- `applicationYamlKeepsItOff`: load `src/main/resources/application.yaml` with SnakeYAML → `home-control.bluetooth.enabled` is `false`; `src/test/resources/application.yaml` → the same.
- `propertiesFallBackToSafeValues`: `new BluetoothProperties(true, " ", null, 0, -1, 0, 0, true, "", null, null, 500, 0, 0, 0, 0)` → `dbusAddress()` default, `adapter()` `""`, `scanSeconds()` 10, `bluezTimeoutSeconds()` 45, `pollIntervalSeconds()` 5, `playingPollIntervalSeconds()` 1, `mpvPath()` `mpv`, `runtimeDir()` default, `audioDeviceTemplate()` `""`, `defaultVolume()` 100 (clamped), `playerStartTimeoutSeconds()` 5, `loadTimeoutSeconds()` 15, `commandTimeoutSeconds()` 3, `hostCheckCacheSeconds()` 30; `defaults().withScanSeconds(1).scanSeconds()` 1.
- `findsTheSocketOfAUnixAddress`: `defaults().dbusSocketPath()` = `/run/dbus/system_bus_socket`; `withDbusAddress("unix:path=/tmp/x.sock,guid=1234").dbusSocketPath()` = `/tmp/x.sock`; `withDbusAddress("tcp:host=localhost,port=1234").dbusSocketPath()` empty.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.deployment.*' --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: compilation failure — `BluetoothConfiguration` and `BluetoothProperties` do not exist.

- [ ] **Step 2: Implement the properties and the empty module**

`src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothProperties.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@code home-control.bluetooth.*}. Off by default: the module needs the host's D-Bus socket,
 * BlueZ, a Bluetooth adapter, an audio server and mpv (see docs/bluetooth-speakers.md).
 */
@ConfigurationProperties("home-control.bluetooth")
public record BluetoothProperties(
        boolean enabled,
        String dbusAddress,
        String adapter,
        int scanSeconds,
        int bluezTimeoutSeconds,
        int pollIntervalSeconds,
        int playingPollIntervalSeconds,
        boolean autoConnect,
        String mpvPath,
        Path runtimeDir,
        String audioDeviceTemplate,
        int defaultVolume,
        int playerStartTimeoutSeconds,
        int loadTimeoutSeconds,
        int commandTimeoutSeconds,
        int hostCheckCacheSeconds) {

    public static final String DEFAULT_DBUS_ADDRESS = "unix:path=/run/dbus/system_bus_socket";

    public BluetoothProperties {
        dbusAddress = dbusAddress == null || dbusAddress.isBlank() ? DEFAULT_DBUS_ADDRESS : dbusAddress.strip();
        adapter = adapter == null ? "" : adapter.strip();
        scanSeconds = positive(scanSeconds, 10);
        bluezTimeoutSeconds = positive(bluezTimeoutSeconds, 45);
        pollIntervalSeconds = positive(pollIntervalSeconds, 5);
        playingPollIntervalSeconds = positive(playingPollIntervalSeconds, 1);
        mpvPath = mpvPath == null || mpvPath.isBlank() ? "mpv" : mpvPath.strip();
        runtimeDir = runtimeDir == null ? Path.of(System.getProperty("java.io.tmpdir"), "home-control-bluetooth") : runtimeDir;
        audioDeviceTemplate = audioDeviceTemplate == null ? "" : audioDeviceTemplate.strip();
        defaultVolume = defaultVolume <= 0 ? 50 : Math.min(defaultVolume, 100);
        playerStartTimeoutSeconds = positive(playerStartTimeoutSeconds, 5);
        loadTimeoutSeconds = positive(loadTimeoutSeconds, 15);
        commandTimeoutSeconds = positive(commandTimeoutSeconds, 3);
        hostCheckCacheSeconds = positive(hostCheckCacheSeconds, 30);
    }

    public static BluetoothProperties defaults() {
        return new BluetoothProperties(false, null, null, 0, 0, 0, 0, true, null, null, null, 0, 0, 0, 0, 0);
    }

    /** The socket file of a {@code unix:path=…} address; empty for other transports. */
    public Optional<Path> dbusSocketPath() {
        if (!dbusAddress.startsWith("unix:")) {
            return Optional.empty();
        }
        for (String part : dbusAddress.substring("unix:".length()).split(",")) {
            if (part.startsWith("path=")) {
                return Optional.of(Path.of(part.substring("path=".length())));
            }
        }
        return Optional.empty();
    }

    public BluetoothProperties withDbusAddress(String value) {
        return new BluetoothProperties(enabled, value, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withAdapter(String value) {
        return new BluetoothProperties(enabled, dbusAddress, value, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withScanSeconds(int value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, value, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withAutoConnect(boolean value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, value, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withMpvPath(String value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, value, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withRuntimeDir(Path value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, value, audioDeviceTemplate, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withAudioDeviceTemplate(String value) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, pollIntervalSeconds,
                playingPollIntervalSeconds, autoConnect, mpvPath, runtimeDir, value, defaultVolume,
                playerStartTimeoutSeconds, loadTimeoutSeconds, commandTimeoutSeconds, hostCheckCacheSeconds);
    }

    public BluetoothProperties withTimings(int poll, int playingPoll, int playerStart, int load, int command) {
        return new BluetoothProperties(enabled, dbusAddress, adapter, scanSeconds, bluezTimeoutSeconds, poll,
                playingPoll, autoConnect, mpvPath, runtimeDir, audioDeviceTemplate, defaultVolume,
                playerStart, load, command, hostCheckCacheSeconds);
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
```

`src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The Bluetooth speaker module, off unless {@code home-control.bluetooth.enabled=true}. Only this
 * configuration's bean methods may construct the D-Bus client, so a disabled module never loads
 * a D-Bus class.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BluetoothProperties.class)
public class BluetoothConfiguration {
}
```

`src/main/resources/application.yaml` — under the existing top-level `home-control:` key add:

```yaml
  bluetooth:
    # Bluetooth speakers through the host's BlueZ and a server-side mpv player.
    # Needs host plumbing and the -bluetooth image: see docs/bluetooth-speakers.md.
    enabled: false
    dbus-address: unix:path=/run/dbus/system_bus_socket
    # Blank = first powered adapter; otherwise an adapter MAC or id such as hci0.
    adapter: ""
    scan-seconds: 10
    bluez-timeout-seconds: 45
    poll-interval-seconds: 5
    playing-poll-interval-seconds: 1
    auto-connect: true
    mpv-path: mpv
    # runtime-dir defaults to <java.io.tmpdir>/home-control-bluetooth
    # e.g. alsa/bluealsa:DEV={mac},PROFILE=a2dp — blank = find the speaker's PipeWire/PulseAudio sink.
    audio-device-template: ""
    default-volume: 50
    player-start-timeout-seconds: 5
    load-timeout-seconds: 15
    command-timeout-seconds: 3
    host-check-cache-seconds: 30
```

`src/test/resources/application.yaml` — under `home-control:` add `bluetooth:` with `enabled: false` in block style.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS.

- [ ] **Step 3: Add the mpv build argument to both images**

In `Dockerfile` (runtime stage) and `Dockerfile.dist`, directly after `FROM eclipse-temurin:25-jre` insert (indentation exactly as shown, so the test's line match holds):

```dockerfile
# Bluetooth speakers (optional) need the mpv player, which adds about 430 MB.
# Default builds leave it out; --build-arg WITH_MPV=true (the -bluetooth image) installs it.
ARG WITH_MPV=false
RUN if [ "$WITH_MPV" = "true" ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends mpv \
      && rm -rf /var/lib/apt/lists/*; \
    fi
```

Everything else in both files stays unchanged (the build stage of `Dockerfile` gets nothing).

- [ ] **Step 4: Publish and prove the Bluetooth image variant in CI**

`.github/workflows/ci.yml`:

In job `image`, after the step `Build Dockerfile`, add:

```yaml
      # The -bluetooth variant installs mpv; prove that build too (amd64 only, not pushed).
      - name: Build Dockerfile with mpv
        uses: docker/build-push-action@v7
        with:
          context: .
          platforms: linux/amd64
          push: false
          build-args: WITH_MPV=true
          cache-from: type=gha,scope=bluetooth
          cache-to: type=gha,mode=max,scope=bluetooth
```

In job `release`, after the step `Build and push` and before `Tag and release`, add:

```yaml
      - id: meta-bluetooth
        if: steps.version.outputs.skipped == 'false'
        uses: docker/metadata-action@v6
        with:
          images: ghcr.io/${{ github.repository }}
          flavor: |
            latest=false
            suffix=-bluetooth
          tags: |
            type=raw,value=${{ steps.version.outputs.version }}
            type=raw,value=${{ steps.guard.outputs.majorminor }}
            type=raw,value=latest

      # Same jar, plus mpv for Bluetooth speakers (docs/bluetooth-speakers.md).
      - name: Build and push the Bluetooth variant
        if: steps.version.outputs.skipped == 'false'
        uses: docker/build-push-action@v7
        with:
          context: dist
          platforms: linux/amd64,linux/arm64
          push: true
          build-args: WITH_MPV=true
          tags: ${{ steps.meta-bluetooth.outputs.tags }}
          labels: ${{ steps.meta-bluetooth.outputs.labels }}
          cache-from: type=gha,scope=bluetooth
          cache-to: type=gha,mode=max,scope=bluetooth
```

- [ ] **Step 5: Write the Compose override and the CasaOS variant**

`compose.bluetooth.yaml`:

```yaml
# Bluetooth speakers (optional). Use together with compose.yaml:
#
#   docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build
#
# Host requirements (BlueZ, an adapter, PipeWire or PulseAudio for the audio user):
# docs/bluetooth-speakers.md. HOST_AUDIO_UID is the uid whose PipeWire/PulseAudio
# plays the audio (1000 is the first user on Raspberry Pi OS and Ubuntu).
services:
  shield-remote:
    build:
      context: .
      args:
        WITH_MPV: "true"
    # Or pull the published variant instead of building:
    # image: ghcr.io/yukuhu/home-control:latest-bluetooth
    environment:
      HOME_CONTROL_BLUETOOTH_ENABLED: "true"
      PULSE_SERVER: unix:/run/pulse/native
    volumes:
      # The host's D-Bus system bus, where BlueZ lives. The directory, not the socket
      # file, so a restart of dbus on the host does not leave a stale mount.
      - /run/dbus:/run/dbus:ro
      # The host user's PipeWire (pipewire-pulse) or PulseAudio socket directory.
      - /run/user/${HOST_AUDIO_UID:-1000}/pulse:/run/pulse
      # PulseAudio (not PipeWire) also checks its cookie:
      # - /home/pi/.config/pulse/cookie:/root/.config/pulse/cookie:ro
```

`casaos/docker-compose.bluetooth.yml` — a copy of `casaos/docker-compose.yml` with these differences (keep every other key, including the whole top-level `x-casaos` block, identical):
- `image: ghcr.io/yukuhu/home-control:latest-bluetooth`
- service `environment:` map `HOME_CONTROL_BLUETOOTH_ENABLED: "true"` and `PULSE_SERVER: unix:/run/pulse/native`
- `volumes:` after the `/data` bind mount add

```yaml
      - type: bind
        source: /run/dbus
        target: /run/dbus
        read_only: true
      - type: bind
        source: /run/user/1000/pulse
        target: /run/pulse
```

- service `x-casaos.volumes` gains `- container: /run/dbus` (`description: en_US: Host D-Bus system bus, where BlueZ runs (read-only)`) and `- container: /run/pulse` (`description: en_US: Host PipeWire or PulseAudio socket directory of the audio user`)
- a comment on top: `# Variant with Bluetooth speakers. Install this OR docker-compose.yml, not both (same app id). Host requirements: docs/bluetooth-speakers.md in the repository.`
- top-level `x-casaos.tagline`/`description` unchanged.

- [ ] **Step 6: Write the host checklist**

`docs/bluetooth-speakers.md`:

````markdown
# Bluetooth speakers — host requirements

Home Control can play music on Bluetooth (A2DP) speakers that are paired with the machine it
runs on. The server itself becomes the audio player: it decodes the stream with `mpv` and hands
the sound to the host's audio server, which sends it to the speaker. This only works when the
host provides all of the plumbing below — a Raspberry Pi class machine with a desktop-style
audio stack does; most NAS boxes do not. The module is **off by default** and has no effect on
anything else while off.

## Checklist

Tick every item on the host (not in the container) before switching the module on. The setup
page shows the same checks under **Bluetooth speakers** once the module is on.

1. **A Bluetooth adapter.** `bluetoothctl list` shows a controller. If it is missing or
   "soft blocked": `sudo rfkill unblock bluetooth`. USB dongles need no driver on Raspberry Pi OS,
   Debian or Ubuntu.
2. **BlueZ is installed and running.** `sudo apt install bluez` then
   `sudo systemctl enable --now bluetooth`; `systemctl status bluetooth` says `active (running)`.
3. **The D-Bus system socket exists** at `/run/dbus/system_bus_socket` (it does on every systemd
   host). The container reaches it through the mount `/run/dbus:/run/dbus:ro`.
4. **The container may talk to BlueZ.** BlueZ's D-Bus policy (`/etc/dbus-1/system.d/bluetooth.conf`)
   allows `root`; the published image runs as root, so nothing is needed. Rootless Docker and
   user-namespace remapping do not work. BlueZ does not use polkit for these calls. On hosts
   whose AppArmor denies D-Bus to containers (the setup page shows "refused this container"), add
   `security_opt: [apparmor:unconfined]` to the service.
5. **An audio server with Bluetooth support runs for one host user** — PipeWire (with WirePlumber
   and `pipewire-pulse`, the Raspberry Pi OS default) or PulseAudio (with
   `pulseaudio-module-bluetooth`). Without it BlueZ refuses to connect the speaker with
   `br-connection-profile-unavailable`.
   - Check: `systemctl --user status pipewire-pulse wireplumber` (or `pulseaudio`) as that user.
   - The audio server must run without anyone logged in: `sudo loginctl enable-linger <user>`.
   - Headless hosts: WirePlumber only enables Bluetooth for the "active seat" by default. Turn
     that off for the audio user and restart WirePlumber:
     - WirePlumber 0.5 (`wireplumber --version`): create
       `~/.config/wireplumber/wireplumber.conf.d/51-headless-bluetooth.conf` with
       ```
       wireplumber.profiles = {
         main = {
           monitor.bluez.seat-monitoring = disabled
         }
       }
       ```
     - WirePlumber 0.4: create `~/.config/wireplumber/bluetooth.lua.d/51-headless.lua` with
       `bluez_monitor.properties["with-logind"] = false`
   - Then `systemctl --user restart wireplumber pipewire pipewire-pulse`.
6. **The container can reach that audio server.** Mount the user's pulse socket directory
   `/run/user/<uid>/pulse` to `/run/pulse` and set `PULSE_SERVER=unix:/run/pulse/native`
   (both done by `compose.bluetooth.yaml`; `HOST_AUDIO_UID` defaults to 1000). With PulseAudio
   (not PipeWire) also mount the user's `~/.config/pulse/cookie` to `/root/.config/pulse/cookie`.
7. **The image contains `mpv`.** Use the image tag `latest-bluetooth` (or `<version>-bluetooth`),
   or build with `--build-arg WITH_MPV=true` (the Compose override does). The default image has
   no player to stay small (mpv adds about 430 MB).
8. **Host networking** as for the rest of Home Control (`network_mode: host`).
9. **Switch the module on:** `HOME_CONTROL_BLUETOOTH_ENABLED=true` (set by both variants).

## Running it

Plain Compose:

```bash
docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build
```

CasaOS: import `casaos/docker-compose.bluetooth.yml` **instead of** `casaos/docker-compose.yml`
(same app). Adjust `/run/user/1000/pulse` if the audio user is not uid 1000.

## Pairing a speaker

1. Put the speaker into pairing mode.
2. Open **Setup → Bluetooth speakers**, press **Scan for speakers** (about 10 seconds).
3. Press **Pair and add** next to the speaker. Home Control pairs, trusts and connects it and
   opens its chip. The speaker is paired with the **host**; `bluetoothctl devices Paired` lists it.
4. **Forget** on the setup page removes it from Home Control and unpairs it on the host.

## Other audio setups

- **bluealsa** instead of PipeWire/PulseAudio: possible, but the published image lacks the
  bluez-alsa ALSA plugin. Extend the image with it, mount `/run/dbus` as above and set
  `HOME_CONTROL_BLUETOOTH_AUDIO_DEVICE_TEMPLATE=alsa/bluealsa:DEV={mac},PROFILE=a2dp`.
- **A fixed output** (for example a speaker the host always uses): set the speaker's audio device
  on the setup page to one of the ids `mpv --audio-device=help` prints inside the container.
````

- [ ] **Step 7: Run the tests, then the build**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.deployment.*' --tests 'dev.andre.homecontrol.adapters.bluetooth.*'` then `.superpowers/gradle.sh build`
Expected: PASS; BUILD SUCCESSFUL. (Optional local proof, not required: `docker build --build-arg WITH_MPV=true -t hc-bt-check .` succeeds and `docker run --rm --entrypoint mpv hc-bt-check --version` prints `mpv v0.41.0`.)

- [ ] **Step 8: Commit**

```bash
git add Dockerfile Dockerfile.dist .github/workflows/ci.yml compose.bluetooth.yaml casaos/docker-compose.bluetooth.yml \
  docs/bluetooth-speakers.md src/main/resources/application.yaml src/test/resources/application.yaml \
  src/main/java/dev/andre/homecontrol/adapters/bluetooth \
  src/test/java/dev/andre/homecontrol/deployment/BluetoothDeploymentTest.java \
  src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothModuleSwitchTest.java
git commit -m "build: optional Bluetooth module switch, mpv image variant and host checklist"
```

---

### Task 2: J2 · BlueZ pairing UI

**Files:**
- Create: `adapters/bluetooth/bluez/BluezClient.java`, `BluetoothAdapterInfo.java`, `BluetoothDeviceInfo.java`, `BluezFailure.java`, `BluezException.java`, `BluezFailures.java`, `DbusBluezClient.java`; `adapters/bluetooth/BluetoothSettings.java`, `BluetoothSpeakerAdapter.java`, `BluetoothSpeakerSession.java`, `BluetoothScan.java`, `BluetoothPairing.java`, `BluetoothSetupException.java`, `BluetoothPairingService.java`, `HostCheck.java`, `BluetoothHostChecks.java`, `BluetoothSetupAdvice.java`, `BluetoothSetupController.java`; `src/main/resources/templates/fragments/bluetooth-setup.html`
- Modify: `build.gradle.kts`, `adapters/bluetooth/BluetoothConfiguration.java`, `src/main/resources/templates/setup.html`
- Test: `adapters/bluetooth/bluez/FakeBluezClient.java`, `BluezFailuresTest.java`, `DbusBluezClientTest.java`; `adapters/bluetooth/BluetoothSettingsTest.java`, `BluetoothSpeakerSessionTest.java`, `BluetoothSpeakerAdapterTest.java`, `BluetoothPairingServiceTest.java`, `BluetoothHostChecksTest.java`, `BluetoothModuleSwitchTest.java`, `BluetoothClassLoadingTest.java`; `ContextSmoke.java` (package `dev.andre.homecontrol`); `web/BluetoothSetupControllerTest.java`, `web/BluetoothSetupOffTest.java`

**Interfaces:**
- Consumes: A — `Device`, `DeviceKind.BLUETOOTH`, `Capability.LOCAL_AUDIO_SINK/VOLUME`, `DeviceAdapter`, `DeviceHandle`, `DeviceState`, `DeviceStatus` (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `UNPAIRED`), `DeviceNotFoundException`, `DeviceOfflineException`, `UnsupportedActionException`, `Device.withAdapter/adapterSettings/hasAdapter`, `SetupController`, `HomeControlApplication`. B — `DeviceAdapter.kind()`, `DeviceManager.adopt/device/devices/state/forget`, `DeviceState.withNowPlaying`, test helper `device/StubAdapter`. F — `core.MacAddress.normalize`, `DeviceState.sameIgnoringTime`. Task 1 — `BluetoothProperties`, `BluetoothConfiguration`.
- Produces:
  - `interface BluezClient extends AutoCloseable` — `List<BluetoothAdapterInfo> adapters()`, `void powerOn(String adapterAddress)`, `List<BluetoothDeviceInfo> discover(String adapterAddress, Duration duration)`, `List<BluetoothDeviceInfo> devices(String adapterAddress)`, `Optional<BluetoothDeviceInfo> device(String adapterAddress, String address)`, `void pair(String adapterAddress, String address)`, `void trust(String adapterAddress, String address)`, `void connect(String adapterAddress, String address)`, `void disconnect(String adapterAddress, String address)`, `void remove(String adapterAddress, String address)` — every method `throws BluezException`; `void close()` (no checked exception).
  - `record BluetoothAdapterInfo(String id, String address, String alias, boolean powered)` with `static Optional<BluetoothAdapterInfo> select(List<BluetoothAdapterInfo>, String wanted)` and `static String describe(List<BluetoothAdapterInfo>)`.
  - `record BluetoothDeviceInfo(String address, String name, String icon, boolean paired, boolean trusted, boolean connected, List<String> uuids, Short rssi)` with `A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb"`, `audioSink()`, `servicesKnown()`, `mayBeSpeaker()`, `displayName()`.
  - `enum BluezFailure { NO_DBUS_SOCKET, ACCESS_DENIED, BLUEZ_NOT_RUNNING, NO_ADAPTER, ADAPTER_OFF, NO_AUDIO_PROFILE, NOT_FOUND, PAIRING_REJECTED, UNREACHABLE, BUSY, ALREADY_DONE, TIMEOUT, FAILED }`; `class BluezException extends Exception { BluezException(BluezFailure, String); BluezException(BluezFailure, String, Throwable); BluezFailure failure(); }`; `BluezFailures.classify(String errorName, String message) → BluezFailure`, `BluezFailures.message(BluezFailure, String detail) → String`, `BluezFailures.noSocket(Path) → String`.
  - `DbusBluezClient(String address, Optional<Path> socket, Duration replyTimeout) implements BluezClient`.
  - `record BluetoothSettings(String address, String adapter, String audioDevice)` with `ADAPTER_ID = "bluetooth"`, `of(Device)`, `toMap()`, `withAudioDevice(String)`, `static String deviceId(String address)`.
  - `BluetoothSpeakerAdapter(BluetoothProperties, BluezClient)`: id `bluetooth`, kind `BLUETOOTH`, capabilities `LOCAL_AUDIO_SINK, VOLUME`, `discovered()` empty, `forget` unpairs on the host.
  - `BluetoothSpeakerSession(Device, BluetoothProperties, BluezClient, Consumer<DeviceState>) implements DeviceHandle` with `start()`, package-private `pollNow()`.
  - `record BluetoothScan(Instant scannedAt, List<BluetoothDeviceInfo> speakers, int hiddenCount, String error)` with `NONE`, `ran()`; `record BluetoothPairing(Device device, String warning)`; `class BluetoothSetupException extends Exception`.
  - `BluetoothPairingService(BluezClient, DeviceManager, BluetoothProperties)` (+ `Clock` constructor): `BluetoothScan lastScan()`, `BluetoothScan scan()`, `BluetoothPairing pair(String address) throws BluetoothSetupException`, `Device connect(String deviceId)`, `Device disconnect(String deviceId)`, `Device setAudioDevice(String deviceId, String audioDevice)` (the last three `throws BluetoothSetupException`, and `DeviceNotFoundException` for an id that is not a registered Bluetooth speaker).
  - `record HostCheck(String id, String label, boolean ok, String detail)`; `BluetoothHostChecks(BluetoothProperties, BluezClient, Clock)` with `List<HostCheck> results()`, `void invalidate()`.
  - Setup page: model attribute `bluetooth` (`BluetoothSetupAdvice.View`), section `id="bluetooth"`; `POST /setup/bluetooth/check`, `POST /setup/bluetooth/scan`, `POST /setup/bluetooth/pair` (`address`), `POST /setup/bluetooth/connect` (`id`), `POST /setup/bluetooth/disconnect` (`id`), `POST /setup/bluetooth/audio-device` (`id`, `audioDevice`) → redirects (pair success → `/?device={id}`), 404 for unknown speaker ids.
  - Test support: `FakeBluezClient`; `ContextSmoke.main`; system property `home-control.test.runtime-classpath`.

**D-Bus usage (normative, `DbusBluezClient`).** Bus name `org.bluez`. Listing = `ObjectManager.GetManagedObjects()` on object `/` → `Map<DBusPath, Map<String interface, Map<String property, Variant<?>>>>`. Adapter = object with interface `org.bluez.Adapter1`: `Address` (s), `Alias` (s), `Powered` (b); its id is the last path segment (`/org/bluez/hci0` → `hci0`). Device = object with `org.bluez.Device1`: `Address` (s), `Name` (s, optional), `Alias` (s; BlueZ sets it to the address with dashes when there is no name), `Icon` (s, optional, e.g. `audio-card`, `audio-headphones`), `Paired`, `Trusted`, `Connected` (b), `UUIDs` (as, optional), `RSSI` (n, optional, only while discovering), `Adapter` (o). Methods: `Adapter1.StartDiscovery()`, `StopDiscovery()`, `RemoveDevice(o device)`; `Device1.Pair()`, `Connect()`, `Disconnect()`; properties `Adapter1.Powered=true` and `Device1.Trusted=true` are written through the bluez-dbus wrappers (`BluetoothAdapter.setPowered(true)`, `BluetoothDevice.setTrusted(true)`). BlueZ D-Bus error names seen in practice: `org.bluez.Error.AuthenticationFailed|AuthenticationRejected|AuthenticationCanceled|AuthenticationTimeout|ConnectionAttemptFailed|AlreadyExists|AlreadyConnected|NotConnected|InProgress|NotReady|DoesNotExist|Failed` (the last with messages such as `br-connection-page-timeout`, `br-connection-profile-unavailable`, `Host is down`), bus errors `org.freedesktop.DBus.Error.ServiceUnknown` (BlueZ not running), `AccessDenied`, `NoReply`, `UnknownObject`.

- [ ] **Step 1: Add the dependencies and the test classpath property**

`build.gradle.kts` — in `dependencies` after `org.bouncycastle:bcpkix-jdk18on`:

```kotlin
    // Bluetooth speakers (optional module, off by default). Only adapters/bluetooth/bluez/DbusBluezClient imports these.
    implementation("com.github.hypfvieh:bluez-dbus:0.3.5")
    implementation("com.github.hypfvieh:dbus-java-core:5.2.1")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.1")
```

and after the existing `tasks.withType<Test> { … }` block:

```kotlin
// Child-JVM tests (class loading, fake mpv) start java with exactly the test runtime classpath.
tasks.named<Test>("test") {
    systemProperty("home-control.test.runtime-classpath", sourceSets["test"].runtimeClasspath.asPath)
}
```

Run: `.superpowers/gradle.sh dependencies --configuration runtimeClasspath | grep hypfvieh`
Expected: `bluez-dbus:0.3.5`, `dbus-java-core:5.2.0 -> 5.2.1`, `dbus-java-transport-native-unixsocket:5.2.1`.

- [ ] **Step 2: Write the failing BlueZ tests**

`adapters/bluetooth/bluez/BluezFailuresTest.java` — parameterized over `(errorName, message, expected)`:
- `("org.freedesktop.dbus.errors.ServiceUnknown", "The name org.bluez was not provided by any .service files", BLUEZ_NOT_RUNNING)`
- `("org.freedesktop.DBus.Error.AccessDenied", "Rejected send message, 1 matched rules", ACCESS_DENIED)`; `("org.freedesktop.dbus.exceptions.DBusException", "Failed to auth", ACCESS_DENIED)`
- `("org.bluez.Error.Failed", "br-connection-profile-unavailable", NO_AUDIO_PROFILE)`
- `("org.bluez.exceptions.BluezAuthenticationFailedException", "Authentication Failed", PAIRING_REJECTED)`; `("org.bluez.Error.AuthenticationTimeout", "Authentication Timeout", PAIRING_REJECTED)`; `("org.bluez.Error.AuthenticationCanceled", "", PAIRING_REJECTED)`
- `("org.bluez.Error.AlreadyExists", "Already Exists", ALREADY_DONE)`; `("org.bluez.exceptions.BluezAlreadyConnectedException", null, ALREADY_DONE)`; `("org.bluez.Error.NotConnected", "Not Connected", ALREADY_DONE)`
- `("org.bluez.Error.InProgress", "Operation already in progress", BUSY)`
- `("org.bluez.Error.NotReady", "Resource Not Ready", ADAPTER_OFF)`
- `("org.bluez.Error.DoesNotExist", "Does Not Exist", NOT_FOUND)`; `("org.freedesktop.DBus.Error.UnknownObject", "Method \"Pair\" with signature \"\" on interface \"org.bluez.Device1\" doesn't exist", NOT_FOUND)`
- `("org.bluez.Error.Failed", "br-connection-page-timeout", UNREACHABLE)`; `("org.bluez.Error.Failed", "Host is down", UNREACHABLE)`; `("org.bluez.Error.ConnectionAttemptFailed", "Page Timeout", UNREACHABLE)`
- `("org.freedesktop.DBus.Error.NoReply", "Did not receive a reply", TIMEOUT)`
- `("org.bluez.Error.Failed", "Input/output error", FAILED)`; `(null, null, FAILED)`

and `messagesNameTheFix`: `message(BLUEZ_NOT_RUNNING, "x")` contains `systemctl enable --now bluetooth`; `NO_ADAPTER` contains `rfkill unblock bluetooth`; `ADAPTER_OFF` contains `rfkill unblock bluetooth`; `NO_AUDIO_PROFILE` contains `PipeWire` and `docs/bluetooth-speakers.md`; `ACCESS_DENIED` contains `apparmor:unconfined`; `PAIRING_REJECTED` contains `pairing mode`; `UNREACHABLE` contains `Switch it on`; `noSocket(Path.of("/run/dbus/system_bus_socket"))` contains `/run/dbus/system_bus_socket` and `Mount /run/dbus`.

`adapters/bluetooth/bluez/DbusBluezClientTest.java`:
- `constructingTouchesNothing`: `new DbusBluezClient("unix:path=/nonexistent/hc-bus.sock", Optional.of(Path.of("/nonexistent/hc-bus.sock")), Duration.ofSeconds(2))` does not throw; `close()` without use does not throw.
- `aMissingSocketIsNamed`: the same client's `adapters()` throws `BluezException` with `failure()` `NO_DBUS_SOCKET` and a message containing `/nonexistent/hc-bus.sock`.
- `aSocketNobodyServesIsAFailureNotACrash`: `@TempDir` file `bus.sock` created as an empty regular file; client on `unix:path=<file>` → `adapters()` throws `BluezException` whose failure is not `NO_DBUS_SOCKET` and whose message contains `D-Bus`; a second call throws again (no cached broken connection).

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.bluez.*'`
Expected: compilation failure — the `bluez` package does not exist.

- [ ] **Step 3: Implement the BlueZ package**

`adapters/bluetooth/bluez/BluezClient.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The host's BlueZ, as the Bluetooth module needs it. Addresses are MACs in {@code AA:BB:CC:DD:EE:FF}
 * form; the adapter is identified by its MAC. Implementations must not do I/O in their constructor.
 */
public interface BluezClient extends AutoCloseable {

    List<BluetoothAdapterInfo> adapters() throws BluezException;

    void powerOn(String adapterAddress) throws BluezException;

    /** Runs discovery for {@code duration}, stops it, then lists every device the adapter knows. */
    List<BluetoothDeviceInfo> discover(String adapterAddress, Duration duration) throws BluezException;

    List<BluetoothDeviceInfo> devices(String adapterAddress) throws BluezException;

    Optional<BluetoothDeviceInfo> device(String adapterAddress, String address) throws BluezException;

    /** Pairs; an already paired device is not an error. */
    void pair(String adapterAddress, String address) throws BluezException;

    void trust(String adapterAddress, String address) throws BluezException;

    /** Connects; an already connected device is not an error. */
    void connect(String adapterAddress, String address) throws BluezException;

    /** Disconnects; a disconnected device is not an error. */
    void disconnect(String adapterAddress, String address) throws BluezException;

    /** Unpairs and removes the device from the adapter; an unknown device is not an error. */
    void remove(String adapterAddress, String address) throws BluezException;

    @Override
    void close();
}
```

`adapters/bluetooth/bluez/BluetoothAdapterInfo.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public record BluetoothAdapterInfo(String id, String address, String alias, boolean powered) {

    /** Blank {@code wanted}: the first powered adapter, else the first. Otherwise the adapter with that id or MAC. */
    public static Optional<BluetoothAdapterInfo> select(List<BluetoothAdapterInfo> adapters, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            return adapters.stream().filter(BluetoothAdapterInfo::powered).findFirst()
                    .or(() -> adapters.stream().findFirst());
        }
        return adapters.stream()
                .filter(adapter -> adapter.id().equalsIgnoreCase(wanted.strip()) || adapter.address().equalsIgnoreCase(wanted.strip()))
                .findFirst();
    }

    public static String describe(List<BluetoothAdapterInfo> adapters) {
        return adapters.isEmpty() ? "no adapter"
                : adapters.stream().map(a -> a.id() + " (" + a.address() + ")").collect(Collectors.joining(", "));
    }
}
```

`adapters/bluetooth/bluez/BluetoothDeviceInfo.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.util.List;

public record BluetoothDeviceInfo(String address, String name, String icon, boolean paired, boolean trusted,
                                  boolean connected, List<String> uuids, Short rssi) {

    /** Advanced Audio Distribution Profile, sink role: what a speaker or headphones offer. */
    public static final String A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb";

    public BluetoothDeviceInfo {
        uuids = uuids == null ? List.of() : List.copyOf(uuids);
    }

    public boolean audioSink() {
        return uuids.stream().anyMatch(A2DP_SINK::equalsIgnoreCase);
    }

    /** Many speakers reveal their services only after pairing. */
    public boolean servicesKnown() {
        return !uuids.isEmpty();
    }

    public boolean mayBeSpeaker() {
        return audioSink() || !servicesKnown() || (icon != null && icon.startsWith("audio-"));
    }

    public String displayName() {
        return name == null || name.isBlank() ? address : name.strip();
    }
}
```

`BluezFailure.java` — the enum listed under Produces, each constant with a one-line Javadoc. `BluezException.java` — `public class BluezException extends Exception` with the two constructors and `failure()`.

`adapters/bluetooth/bluez/BluezFailures.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.bluez;

import java.nio.file.Path;
import java.util.Locale;

import static dev.andre.homecontrol.adapters.bluetooth.bluez.BluezFailure.*;

/** Turns D-Bus/BlueZ errors into a failure kind and a sentence that names the fix. */
public final class BluezFailures {

    private static final String DOCS = "see docs/bluetooth-speakers.md";

    private BluezFailures() {
    }

    public static BluezFailure classify(String errorName, String message) {
        String text = ((errorName == null ? "" : errorName) + " " + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        if (has(text, "serviceunknown", "namehasnoowner", "org.bluez was not provided")) {
            return BLUEZ_NOT_RUNNING;
        }
        if (has(text, "accessdenied", "failed to auth", "rejected send message")) {
            return ACCESS_DENIED;
        }
        if (has(text, "profile-unavailable", "protocol not available")) {
            return NO_AUDIO_PROFILE;
        }
        if (has(text, "authenticationfailed", "authenticationrejected", "authenticationcanceled",
                "authenticationtimeout", "authentication failed", "authentication rejected")) {
            return PAIRING_REJECTED;
        }
        if (has(text, "alreadyexists", "alreadyconnected", "notconnected", "already exists", "already connected")) {
            return ALREADY_DONE;
        }
        if (has(text, "inprogress", "in progress", "busy")) {
            return BUSY;
        }
        if (has(text, "notready", "not ready", "rfkill")) {
            return ADAPTER_OFF;
        }
        if (has(text, "doesnotexist", "does not exist", "unknownobject", "doesn't exist")) {
            return NOT_FOUND;
        }
        if (has(text, "connectionattemptfailed", "connectfailed", "notavailable", "page-timeout", "page timeout",
                "host is down", "br-connection")) {
            return UNREACHABLE;
        }
        if (has(text, "noreply", "timeout", "timed out")) {
            return TIMEOUT;
        }
        return FAILED;
    }

    public static String message(BluezFailure failure, String detail) {
        String why = detail == null || detail.isBlank() ? "" : " (" + detail.strip() + ")";
        return switch (failure) {
            case NO_DBUS_SOCKET -> "No D-Bus system socket" + why + ". Mount /run/dbus into the container, " + DOCS + ".";
            case ACCESS_DENIED -> "The host's D-Bus refused this container" + why + ". Run the container as root; "
                    + "with AppArmor add security_opt apparmor:unconfined, " + DOCS + ".";
            case BLUEZ_NOT_RUNNING -> "BlueZ is not running on the host" + why
                    + ". Install bluez and run: sudo systemctl enable --now bluetooth";
            case NO_ADAPTER -> "No Bluetooth adapter found on the host" + why
                    + ". Check bluetoothctl list, and run rfkill unblock bluetooth.";
            case ADAPTER_OFF -> "The Bluetooth adapter is off or blocked" + why + ". Run rfkill unblock bluetooth on the host.";
            case NO_AUDIO_PROFILE -> "The host has no Bluetooth audio service for this speaker" + why
                    + ". Start PipeWire (with WirePlumber) or PulseAudio with Bluetooth support for the audio user, " + DOCS + ".";
            case NOT_FOUND -> "The host's Bluetooth adapter does not know this device" + why
                    + ". Put the speaker into pairing mode and scan again.";
            case PAIRING_REJECTED -> "The speaker refused pairing" + why + ". Put it into pairing mode and try again.";
            case UNREACHABLE -> "The speaker did not answer" + why + ". Switch it on, bring it closer and try again.";
            case BUSY -> "The Bluetooth adapter is busy" + why + ". Try again in a few seconds.";
            case ALREADY_DONE -> "Nothing to do" + why + ".";
            case TIMEOUT -> "BlueZ did not answer in time" + why + ".";
            case FAILED -> "Bluetooth operation failed" + why + ".";
        };
    }

    public static String noSocket(Path socket) {
        return message(NO_DBUS_SOCKET, "nothing at " + socket);
    }

    private static boolean has(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
```

`adapters/bluetooth/bluez/DbusBluezClient.java` (the only file importing D-Bus/BlueZ libraries):

```java
package dev.andre.homecontrol.adapters.bluetooth.bluez;

import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import org.bluez.Adapter1;
import org.bluez.Device1;
import org.bluez.exceptions.BluezAlreadyConnectedException;
import org.bluez.exceptions.BluezAlreadyExistsException;
import org.bluez.exceptions.BluezInProgressException;
import org.bluez.exceptions.BluezNotConnectedException;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.DBusExecutionException;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.messages.MethodCall;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BlueZ over the D-Bus system bus via bluez-dbus. Connects on first use and again after the
 * connection broke, so the application starts without D-Bus and the setup page can say why.
 * Never annotate this class: only BluetoothConfiguration may construct it.
 */
public final class DbusBluezClient implements BluezClient {

    private static final Logger log = LoggerFactory.getLogger(DbusBluezClient.class);
    private static final String BLUEZ = "org.bluez";
    private static final String ADAPTER_INTERFACE = "org.bluez.Adapter1";
    private static final String DEVICE_INTERFACE = "org.bluez.Device1";

    private interface DbusCall<T> {
        T run(DBusConnection connection) throws DBusException, BluezException;
    }

    private final String address;
    private final Optional<Path> socket;
    private final Object connectionLock = new Object();
    private DBusConnection connection; // guarded by connectionLock

    public DbusBluezClient(String address, Optional<Path> socket, Duration replyTimeout) {
        this.address = address;
        this.socket = socket;
        // Pair() waits for the speaker; dbus-java's default reply timeout is shorter.
        MethodCall.setDefaultTimeout(replyTimeout.toMillis());
    }

    @Override
    public List<BluetoothAdapterInfo> adapters() throws BluezException {
        return call("list adapters", connection -> {
            List<BluetoothAdapterInfo> adapters = new ArrayList<>();
            managedObjects(connection).forEach((path, interfaces) -> {
                Map<String, Variant<?>> properties = interfaces.get(ADAPTER_INTERFACE);
                if (properties != null) {
                    String objectPath = path.getPath();
                    adapters.add(new BluetoothAdapterInfo(objectPath.substring(objectPath.lastIndexOf('/') + 1),
                            text(properties, "Address"), text(properties, "Alias"), flag(properties, "Powered")));
                }
            });
            adapters.sort(Comparator.comparing(BluetoothAdapterInfo::id));
            return adapters;
        });
    }

    @Override
    public void powerOn(String adapterAddress) throws BluezException {
        call("power on the adapter", connection -> {
            String path = adapterPath(managedObjects(connection), adapterAddress);
            new BluetoothAdapter(connection.getRemoteObject(BLUEZ, path, Adapter1.class), path, connection).setPowered(true);
            return null;
        });
    }

    @Override
    public List<BluetoothDeviceInfo> discover(String adapterAddress, Duration duration) throws BluezException {
        Adapter1 adapter = call("start scanning", connection -> {
            Adapter1 remote = connection.getRemoteObject(BLUEZ, adapterPath(managedObjects(connection), adapterAddress), Adapter1.class);
            try {
                remote.StartDiscovery();
            } catch (BluezInProgressException alreadyScanning) {
                // another client (bluetoothctl, a desktop) is scanning: the results are shared
            }
            return remote;
        });
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                adapter.StopDiscovery();
            } catch (Exception e) {
                log.debug("StopDiscovery failed: {}", e.toString());
            }
        }
        return devices(adapterAddress);
    }

    @Override
    public List<BluetoothDeviceInfo> devices(String adapterAddress) throws BluezException {
        return call("list devices", connection -> {
            Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
            String adapterPath = adapterPath(objects, adapterAddress);
            List<BluetoothDeviceInfo> devices = new ArrayList<>();
            objects.values().forEach(interfaces -> {
                Map<String, Variant<?>> properties = interfaces.get(DEVICE_INTERFACE);
                if (properties != null && adapterPath.equals(objectPath(properties.get("Adapter")))) {
                    devices.add(device(properties));
                }
            });
            return devices;
        });
    }

    @Override
    public Optional<BluetoothDeviceInfo> device(String adapterAddress, String address) throws BluezException {
        return devices(adapterAddress).stream().filter(found -> address.equalsIgnoreCase(found.address())).findFirst();
    }

    @Override
    public void pair(String adapterAddress, String address) throws BluezException {
        call("pair " + address, connection -> {
            try {
                device1(connection, adapterAddress, address).Pair();
            } catch (BluezAlreadyExistsException alreadyPaired) {
                // paired before
            }
            return null;
        });
    }

    @Override
    public void trust(String adapterAddress, String address) throws BluezException {
        call("trust " + address, connection -> {
            Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
            String adapterPath = adapterPath(objects, adapterAddress);
            String devicePath = devicePath(objects, adapterPath, address)
                    .orElseThrow(() -> new BluezException(BluezFailure.NOT_FOUND, BluezFailures.message(BluezFailure.NOT_FOUND, address)));
            BluetoothAdapter adapter = new BluetoothAdapter(connection.getRemoteObject(BLUEZ, adapterPath, Adapter1.class), adapterPath, connection);
            new BluetoothDevice(connection.getRemoteObject(BLUEZ, devicePath, Device1.class), adapter, devicePath, connection).setTrusted(true);
            return null;
        });
    }

    @Override
    public void connect(String adapterAddress, String address) throws BluezException {
        call("connect " + address, connection -> {
            try {
                device1(connection, adapterAddress, address).Connect();
            } catch (BluezAlreadyConnectedException alreadyConnected) {
                // fine
            }
            return null;
        });
    }

    @Override
    public void disconnect(String adapterAddress, String address) throws BluezException {
        call("disconnect " + address, connection -> {
            try {
                device1(connection, adapterAddress, address).Disconnect();
            } catch (BluezNotConnectedException notConnected) {
                // fine
            }
            return null;
        });
    }

    @Override
    public void remove(String adapterAddress, String address) throws BluezException {
        call("remove " + address, connection -> {
            Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
            String adapterPath = adapterPath(objects, adapterAddress);
            Optional<String> devicePath = devicePath(objects, adapterPath, address);
            if (devicePath.isPresent()) {
                connection.getRemoteObject(BLUEZ, adapterPath, Adapter1.class).RemoveDevice(new DBusPath(devicePath.get()));
            }
            return null;
        });
    }

    @Override
    public void close() {
        synchronized (connectionLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (IOException e) {
                    log.debug("Closing the D-Bus connection failed: {}", e.toString());
                }
                connection = null;
            }
        }
    }

    private <T> T call(String what, DbusCall<T> action) throws BluezException {
        DBusConnection current = connection();
        try {
            return action.run(current);
        } catch (DBusException | DBusExecutionException e) {
            String name = e instanceof DBusExecutionException execution && execution.getType() != null
                    ? execution.getType() : e.getClass().getName();
            BluezFailure failure = BluezFailures.classify(name, e.getMessage());
            if (!current.isConnected()) {
                close();
            }
            throw new BluezException(failure, BluezFailures.message(failure, what + ": " + e.getMessage()), e);
        }
    }

    private DBusConnection connection() throws BluezException {
        synchronized (connectionLock) {
            if (connection != null && connection.isConnected()) {
                return connection;
            }
            connection = null;
            if (socket.isPresent() && !Files.exists(socket.get())) {
                throw new BluezException(BluezFailure.NO_DBUS_SOCKET, BluezFailures.noSocket(socket.get()));
            }
            try {
                connection = DBusConnectionBuilder.forAddress(address).withShared(false).build();
                return connection;
            } catch (DBusException | RuntimeException e) {
                BluezFailure failure = BluezFailures.classify(e.getClass().getName(), e.getMessage());
                throw new BluezException(failure, BluezFailures.message(failure,
                        "could not connect to D-Bus at " + address + ": " + e.getMessage()), e);
            }
        }
    }

    private static Map<DBusPath, Map<String, Map<String, Variant<?>>>> managedObjects(DBusConnection connection) throws DBusException {
        return connection.getRemoteObject(BLUEZ, "/", ObjectManager.class).GetManagedObjects();
    }

    private static String adapterPath(Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects, String adapterAddress)
            throws BluezException {
        for (Map.Entry<DBusPath, Map<String, Map<String, Variant<?>>>> entry : objects.entrySet()) {
            Map<String, Variant<?>> properties = entry.getValue().get(ADAPTER_INTERFACE);
            if (properties != null && adapterAddress.equalsIgnoreCase(text(properties, "Address"))) {
                return entry.getKey().getPath();
            }
        }
        throw new BluezException(BluezFailure.NO_ADAPTER, BluezFailures.message(BluezFailure.NO_ADAPTER, "no adapter " + adapterAddress));
    }

    private static Optional<String> devicePath(Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects, String adapterPath,
                                               String address) {
        return objects.entrySet().stream()
                .filter(entry -> {
                    Map<String, Variant<?>> properties = entry.getValue().get(DEVICE_INTERFACE);
                    return properties != null && address.equalsIgnoreCase(text(properties, "Address"))
                            && adapterPath.equals(objectPath(properties.get("Adapter")));
                })
                .map(entry -> entry.getKey().getPath())
                .findFirst();
    }

    private static Device1 device1(DBusConnection connection, String adapterAddress, String address)
            throws DBusException, BluezException {
        Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = managedObjects(connection);
        String path = devicePath(objects, adapterPath(objects, adapterAddress), address)
                .orElseThrow(() -> new BluezException(BluezFailure.NOT_FOUND, BluezFailures.message(BluezFailure.NOT_FOUND, address)));
        return connection.getRemoteObject(BLUEZ, path, Device1.class);
    }

    private static BluetoothDeviceInfo device(Map<String, Variant<?>> properties) {
        String address = text(properties, "Address");
        String name = text(properties, "Name");
        if (name == null || name.isBlank()) {
            String alias = text(properties, "Alias");
            name = alias != null && !alias.replace('-', ':').equalsIgnoreCase(address) ? alias : null;
        }
        Variant<?> rssi = properties.get("RSSI");
        return new BluetoothDeviceInfo(address, name, text(properties, "Icon"), flag(properties, "Paired"),
                flag(properties, "Trusted"), flag(properties, "Connected"), strings(properties.get("UUIDs")),
                rssi != null && rssi.getValue() instanceof Number number ? number.shortValue() : null);
    }

    private static String text(Map<String, Variant<?>> properties, String name) {
        Variant<?> value = properties.get(name);
        return value == null || value.getValue() == null ? null : String.valueOf(value.getValue());
    }

    private static boolean flag(Map<String, Variant<?>> properties, String name) {
        Variant<?> value = properties.get(name);
        return value != null && Boolean.TRUE.equals(value.getValue());
    }

    private static List<String> strings(Variant<?> value) {
        if (value == null) {
            return List.of();
        }
        Object raw = value.getValue();
        if (raw instanceof String[] array) {
            return List.of(array);
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String objectPath(Variant<?> value) {
        if (value == null || value.getValue() == null) {
            return null;
        }
        return value.getValue() instanceof DBusPath path ? path.getPath() : String.valueOf(value.getValue());
    }
}
```

(If a signature differs in the resolved library version — e.g. `isConnected()` lives on `AbstractConnection` under another name — adapt minimally and say so in the report; the behaviour above is what matters.)

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.bluez.*'`
Expected: PASS.

- [ ] **Step 4: Write the fake BlueZ**

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/bluez/FakeBluezClient.java` — `public final class FakeBluezClient implements BluezClient`, fully `synchronized`, in-memory:
- `public static final String ADAPTER = "00:1A:7D:DA:71:13";` The constructor creates one adapter `hci0` with that address, alias `raspberrypi`, powered.
- `public FakeBluezClient noAdapters()`, `adapterPowered(boolean)`, `addAdapter(String id, String address, boolean powered)` — builder-style, return `this`.
- `public FakeDevice addDevice(String address, String name)` — a device on `ADAPTER`, not paired, not connected, no UUIDs, no icon, `rssi` null, **not visible** in `devices()`/`device()` until a `discover` ran or it is paired (a real adapter only knows devices it has seen). `public FakeDevice known(String address, String name)` — the same but visible at once. `public FakeDevice device(String address)`.
- `public final class FakeDevice` with chainable setters `name(String)`, `icon(String)`, `paired(boolean)`, `trusted(boolean)`, `connected(boolean)`, `uuids(String...)`, `uuidsAfterPairing(String...)`, `rssi(int)`, getters `paired()`, `trusted()`, `connected()`, and `BluetoothDeviceInfo info()`.
- `public void unavailable(BluezFailure failure)` — while non-null every method throws `new BluezException(failure, BluezFailures.message(failure, "fake"))`.
- `public void failNext(String operation, BluezFailure failure, String detail)` — the next call of `operation` (`adapters`, `powerOn`, `discover`, `devices`, `device`, `pair`, `trust`, `connect`, `disconnect`, `remove`) throws `new BluezException(failure, BluezFailures.message(failure, detail))`; `failAlways(String operation, BluezFailure failure, String detail)` until `heal(operation)`.
- `public void delay(String operation, Duration delay)` — sleeps before answering.
- Behaviour: an adapter address that is not known → `NO_ADAPTER`; an unknown or invisible device for `pair`/`trust`/`connect`/`disconnect` → `NOT_FOUND`; `powerOn` powers the adapter; `discover` makes every device visible and returns `devices(adapter)` (it does not sleep); `pair` sets `paired` and, when `uuidsAfterPairing` was set, replaces the UUIDs; `trust` sets `trusted`; `connect` sets `connected` (also on unpaired devices, like BlueZ which pairs implicitly — keep `paired` unchanged); `disconnect` clears it; `remove` deletes the device (unknown: no error).
- Recording: `public List<String> calls()` — one entry per mutating call in order: `powerOn <adapter>`, `discover <adapter> <seconds>s`, `pair <address>`, `trust <address>`, `connect <address>`, `disconnect <address>`, `remove <address>`; `clearCalls()`; `public int reads()` counts `adapters`, `devices` and `device` calls (polling proof).
- `close()` sets `closed = true` (readable through `closed()`).

- [ ] **Step 5: Write the failing adapter, session and pairing tests**

`adapters/bluetooth/BluetoothSettingsTest.java`:
- `normalizesAndRoundTrips`: `new BluetoothSettings("aa-bb-cc-dd-ee-ff", "00:1a:7d:da:71:13", null)` → `address()` `AA:BB:CC:DD:EE:FF`, `adapter()` `00:1A:7D:DA:71:13`, `audioDevice()` `""`; `toMap()` = `{address=AA:BB:CC:DD:EE:FF, adapter=00:1A:7D:DA:71:13}` (no `audioDevice` key); with `audioDevice` `pulse/bluez_output.AA_BB_CC_DD_EE_FF.1` the map has it; `of(device)` reads it back.
- `deviceIdIsDerivedFromTheMac`: `deviceId("AA:BB:CC:DD:EE:FF")` = `bluetooth-aa-bb-cc-dd-ee-ff`.
- `refusesBadInput`: address `nope` → `IllegalArgumentException` containing `Not a MAC address`; audio device `pulse/x; rm -rf` or containing a space or newline → `IllegalArgumentException` containing `audio device`.

`adapters/bluetooth/BluetoothSpeakerAdapterTest.java` (with `FakeBluezClient bluez`):
- `declaresALocalAudioSink`: `id()` `bluetooth`, `kind()` `BLUETOOTH`, `capabilities(device)` = `{LOCAL_AUDIO_SINK, VOLUME}`, `discovered()` empty, `settingsFor(any)` empty.
- `forgetUnpairsOnTheHost`: registered device for `AA:BB:CC:DD:EE:FF` → `forget(device)` → `bluez.calls()` contains `remove AA:BB:CC:DD:EE:FF`.
- `forgetNeverFails`: `bluez.unavailable(BLUEZ_NOT_RUNNING)` → `forget(device)` does not throw.
- `connectStartsASession`: `connect(device, states::add)` returns a `BluetoothSpeakerSession`; `states` eventually non-empty; close it.

`adapters/bluetooth/BluetoothSpeakerSessionTest.java` — `FakeBluezClient bluez`; speaker `bluez.known("AA:BB:CC:DD:EE:FF", "JBL Flip 5").paired(true).connected(true).uuids(A2DP_SINK)`; device `new Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", BLUETOOTH, "AA:BB:CC:DD:EE:FF", Map.of("bluetooth", new BluetoothSettings("AA:BB:CC:DD:EE:FF", FakeBluezClient.ADAPTER, "").toMap()), Instant.now())`; properties `BluetoothProperties.defaults().withTimings(1, 1, 5, 5, 2)`; published states into a `CopyOnWriteArrayList`; every session closed in `@AfterEach`; Awaitility ≤ 5 s:
- `publishesTheInitialStateOnStart`: right after `start()` the list is non-empty and its first state has status `DISCONNECTED` (the first poll may already have published more).
- `aConnectedSpeakerIsConnected`: eventually `state()` has status `CONNECTED`, `powerOn()` true, `volumeLevel()` 50, `volumeMax()` 100, `nowPlaying()` null.
- `aSwitchedOffSpeakerIsDisconnected`: `connected(false)`, properties `.withAutoConnect(false)` → for 2 s status `DISCONNECTED`; `bluez.calls()` has no `connect`.
- `autoConnectTriesOnceAfterStart`: `connected(false)`, `bluez.failNext("connect", UNREACHABLE, "br-connection-page-timeout")` → after 3 s status `DISCONNECTED` and `calls()` contains `connect AA:BB:CC:DD:EE:FF` exactly once.
- `autoConnectConnects`: `connected(false)` → eventually `CONNECTED`, `calls()` contains `connect AA:BB:CC:DD:EE:FF`.
- `aSpeakerUnpairedOnTheHostIsUnpaired`: `bluez.remove(ADAPTER, "AA:BB:CC:DD:EE:FF")` after connected → eventually `UNPAIRED`.
- `bluezTroubleIsDisconnectedNotACrash`: after connected, `bluez.unavailable(BLUEZ_NOT_RUNNING)` → eventually `DISCONNECTED`; `unavailable(null)` → eventually `CONNECTED` again.
- `publishesOnlyChanges`: once `CONNECTED`, remember `states.size()`; 3 s later the size is unchanged.
- `playbackIsNotAvailableYet`: `execute(new Action.PlayMedia(URI.create("http://127.0.0.1:9/a.mp3"), "audio/mpeg", "A", null))` and `execute(new Action.PressKey(RemoteKey.HOME))` → `UnsupportedActionException` (the first containing `not available yet`).
- `closeStopsPolling`: `close()`; remember `bluez.reads()`; 2.5 s later unchanged; `execute(new Action.Stop())` → `DeviceOfflineException`.

`adapters/bluetooth/BluetoothPairingServiceTest.java` — `FakeBluezClient bluez`, Mockito `DeviceManager devices` (`devices.device(anyString())` → `Optional.empty()` unless stubbed), properties `defaults()`, fixed `Clock` at `2026-09-16T10:00:00Z`; `ArgumentCaptor<Device>` for `adopt`:
- `scanPowersTheAdapterAndListsSpeakersFirst`: `adapterPowered(false)`; devices `11:11:11:11:11:01` "Phone" icon `phone` uuids `0000110a-0000-1000-8000-00805f9b34fb` (A2DP source only) rssi -40; `11:11:11:11:11:02` "Headphones" icon `audio-headphones` no uuids rssi -80; `11:11:11:11:11:03` "JBL Flip 5" icon `audio-card` uuids `A2DP_SINK` rssi -70; `11:11:11:11:11:04` null name, no icon, no uuids, rssi -50 → `scan()`: `calls()` = `[powerOn 00:1A:7D:DA:71:13, discover 00:1A:7D:DA:71:13 10s]`; speakers' addresses in order `…03, …04, …02` (sink first, then by signal); `hiddenCount()` 1; `error()` null; `scannedAt()` the clock instant; `lastScan()` returns the same object.
- `scanFailureIsAMessage`: `noAdapters()` → `scan().error()` contains `No Bluetooth adapter`; speakers empty.
- `aConfiguredAdapterIsUsed`: `addAdapter("hci1", "00:1A:7D:DA:71:99", true)`, properties `.withAdapter("hci1")` → `calls()` contains `discover 00:1A:7D:DA:71:99 10s`; `.withAdapter("hci7")` → error contains `Adapter hci7 not found` and `hci0 (00:1A:7D:DA:71:13)`.
- `pairsTrustsConnectsAndRegisters`: known speaker `AA:BB:CC:DD:EE:FF` "JBL Flip 5" uuids sink, not paired → `pair("aa:bb:cc:dd:ee:ff")` → `calls()` = `[pair AA:BB:CC:DD:EE:FF, trust AA:BB:CC:DD:EE:FF, connect AA:BB:CC:DD:EE:FF]`; captured device = `Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", BLUETOOTH, "AA:BB:CC:DD:EE:FF", {bluetooth: {address: AA:BB:CC:DD:EE:FF, adapter: 00:1A:7D:DA:71:13}}, clock instant)`; `warning()` null.
- `anAlreadyPairedSpeakerIsOnlyTrustedAndConnected`: `paired(true)` → `calls()` = `[trust …, connect …]`.
- `aConnectedSpeakerIsNotConnectedAgain`: `paired(true).connected(true)` → `calls()` = `[trust …]`.
- `refusesANonAudioDeviceBeforePairing`: uuids `0000110a-…` only → `BluetoothSetupException` with message `Phone is not a speaker or headphones (no A2DP audio sink)`; `calls()` empty; no `adopt`.
- `removesADeviceThatTurnsOutNotToBeASpeaker`: no uuids, `uuidsAfterPairing("00001101-0000-1000-8000-00805f9b34fb")` → exception with the same wording; `calls()` ends with `remove AA:BB:CC:DD:EE:FF`; no `adopt`.
- `acceptsASpeakerWhoseServicesStayUnknown`: no uuids before or after → registered.
- `aRejectedPairingIsExplained`: `failNext("pair", PAIRING_REJECTED, "Authentication Rejected")` → exception message contains `refused pairing`; no `adopt`.
- `anUnknownAddressIsExplained`: `pair("AA:BB:CC:DD:EE:00")` → message contains `pairing mode and scan again`.
- `registersEvenWhenConnectFails`: `failNext("connect", UNREACHABLE, "br-connection-page-timeout")` → `adopt` called; `warning()` = `Paired JBL Flip 5, but it did not connect: ` + the BlueZ message (assert `startsWith("Paired JBL Flip 5, but it did not connect: The speaker did not answer")`).
- `pairingAgainKeepsNameAndAudioDevice`: `devices.device("bluetooth-aa-bb-cc-dd-ee-ff")` → a registered device named `Kitchen speaker` with settings `audioDevice` `alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp` → captured device name `Kitchen speaker` and settings contain that `audioDevice`.
- `rejectsABadAddress`: `pair("nope")` → message contains `Not a MAC address`.
- `connectsAndDisconnectsARegisteredSpeaker`: registered device stubbed → `connect(id)` → `calls()` contains `connect …`; `disconnect(id)` → `disconnect …`; both return the device; BlueZ failure → `BluetoothSetupException` with the BlueZ message.
- `unknownSpeakersAreNotFound`: `connect("ghost")`, `setAudioDevice("ghost", "")` → `DeviceNotFoundException`; a registered device without a `bluetooth` adapter → `DeviceNotFoundException`.
- `setsAndClearsTheAudioDevice`: `setAudioDevice(id, "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1")` → `adopt` with that setting; `""` → `adopt` without the key; `"bad device"` → `BluetoothSetupException` containing `audio device`.

`adapters/bluetooth/BluetoothHostChecksTest.java` — `@TempDir` file `bus.sock` created as a regular file; properties `defaults().withDbusAddress("unix:path=" + socket)`; a mutable test clock (`Clock` subclass over an `AtomicReference<Instant>`):
- `allGood`: `results()` ids `[dbus-socket, bluez, adapter]`, all `ok`; details `Found <socket>`, `BlueZ answered on the system bus`, `hci0 (00:1A:7D:DA:71:13)`.
- `aMissingSocketBlocksTheRest`: socket deleted → `dbus-socket` not ok with `Mount /run/dbus`; `bluez` not ok `Needs the D-Bus socket first`; `adapter` not ok `Needs BlueZ first`; `bluez.reads()` 0.
- `bluezNotRunning`: `unavailable(BLUEZ_NOT_RUNNING)` → `bluez` not ok containing `systemctl enable --now bluetooth`; `adapter` `Needs BlueZ first`.
- `noAdapter`: `noAdapters()` → `adapter` not ok containing `rfkill unblock bluetooth`.
- `aConfiguredAdapterIsMissing`: `.withAdapter("hci3")` → `adapter` detail `Adapter hci3 not found; the host has hci0 (00:1A:7D:DA:71:13)`.
- `aPoweredOffAdapter`: `adapterPowered(false)` → `adapter` not ok containing `powered off` and `rfkill unblock bluetooth`.
- `otherTransportsSkipTheFileCheck`: `withDbusAddress("tcp:host=127.0.0.1,port=1")` → `dbus-socket` ok with `Using tcp:host=127.0.0.1,port=1`.
- `resultsAreCachedUntilInvalidated`: `results()` twice → `bluez.reads()` 1; clock +29 s → still 1; `invalidate()` → 2; clock +31 s after that → 3.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: compilation failure — settings, adapter, session, pairing and host check classes do not exist.

- [ ] **Step 6: Implement settings, adapter and session**

`adapters/bluetooth/BluetoothSettings.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.MacAddress;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Per-device settings under {@code adapters.bluetooth}: the speaker's MAC, its adapter's MAC, an optional audio device. */
public record BluetoothSettings(String address, String adapter, String audioDevice) {

    public static final String ADAPTER_ID = "bluetooth";
    private static final Pattern AUDIO_DEVICE = Pattern.compile("[A-Za-z0-9_.:/=,@+-]{1,200}");

    public BluetoothSettings {
        address = MacAddress.normalize(address);
        adapter = adapter == null || adapter.isBlank() ? "" : MacAddress.normalize(adapter);
        audioDevice = audioDevice == null ? "" : audioDevice.strip();
        if (!audioDevice.isEmpty() && !AUDIO_DEVICE.matcher(audioDevice).matches()) {
            throw new IllegalArgumentException("An audio device id may only contain letters, digits and _ . : / = , @ + -");
        }
    }

    public static BluetoothSettings of(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        return new BluetoothSettings(settings.get("address"), settings.get("adapter"), settings.get("audioDevice"));
    }

    public static String deviceId(String address) {
        return "bluetooth-" + MacAddress.normalize(address).toLowerCase(Locale.ROOT).replace(':', '-');
    }

    public BluetoothSettings withAudioDevice(String value) {
        return new BluetoothSettings(address, adapter, value);
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("address", address);
        map.put("adapter", adapter);
        if (!audioDevice.isEmpty()) {
            map.put("audioDevice", audioDevice);
        }
        return map;
    }
}
```

`adapters/bluetooth/BluetoothSpeakerAdapter.java` — `public class BluetoothSpeakerAdapter implements DeviceAdapter` (no Spring annotation), constructor `(BluetoothProperties properties, BluezClient bluez)`:
- `id()` → `BluetoothSettings.ADAPTER_ID`; `kind()` → `DeviceKind.BLUETOOTH`; `capabilities(Device)` → `EnumSet.of(Capability.LOCAL_AUDIO_SINK, Capability.VOLUME)`; `discovered()` → `List.of()` (scan results live in the Bluetooth setup section).
- `connect(device, onChange)` → `BluetoothSpeakerSession session = new BluetoothSpeakerSession(device, properties, bluez, onChange); session.start(); return session;`
- `forget(device)` → `BluetoothSettings settings = BluetoothSettings.of(device); bluez.remove(settings.adapter(), settings.address());` inside `try`, catching `BluezException | IllegalArgumentException` → `log.warn("Could not unpair {} on the host: {}", device.name(), e.getMessage())`.

`adapters/bluetooth/BluetoothSpeakerSession.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezClient;
import dev.andre.homecontrol.adapters.bluetooth.bluez.BluezException;
import dev.andre.homecontrol.core.Action;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceHandle;
import dev.andre.homecontrol.core.DeviceOfflineException;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.UnsupportedActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One Bluetooth speaker: polls BlueZ on one virtual thread and publishes state changes. */
public class BluetoothSpeakerSession implements DeviceHandle {

    private static final Logger log = LoggerFactory.getLogger(BluetoothSpeakerSession.class);

    private final Device device;
    private final BluetoothSettings settings;
    private final BluetoothProperties properties;
    private final BluezClient bluez;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService loop;

    private volatile DeviceState state = DeviceState.initial();
    private volatile int volume;
    private volatile boolean muted;
    private volatile boolean closed;
    private ScheduledFuture<?> nextPoll;      // loop thread only
    private boolean lookedOnce;                // loop thread only

    public BluetoothSpeakerSession(Device device, BluetoothProperties properties, BluezClient bluez,
                                   Consumer<DeviceState> onChange) {
        this.device = device;
        this.settings = BluetoothSettings.of(device);
        this.properties = properties;
        this.bluez = bluez;
        this.onChange = onChange;
        this.volume = properties.defaultVolume();
        this.loop = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("bluetooth-" + device.id()).factory());
    }

    public void start() {
        onChange.accept(state);
        loop.execute(this::poll);
    }

    @Override
    public DeviceState state() {
        return state;
    }

    @Override
    public void execute(Action action) {
        if (closed) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        throw new UnsupportedActionException(device.name() + ": playback through the server is not available yet");
    }

    @Override
    public void close() {
        closed = true;
        loop.shutdownNow();
    }

    void pollNow() {
        if (!closed) {
            loop.execute(this::poll);
        }
    }

    private void poll() {
        if (closed) {
            return;
        }
        try {
            readState();
        } catch (RuntimeException e) {
            log.warn("Reading the state of {} failed", device.name(), e);
        } finally {
            if (!closed) {
                if (nextPoll != null) {
                    nextPoll.cancel(false);
                }
                nextPoll = loop.schedule(this::poll, nextPollSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    /** Task 3 makes this depend on the player. */
    private long nextPollSeconds() {
        return properties.pollIntervalSeconds();
    }

    private void readState() {
        DeviceStatus status = bluetoothStatus();
        publish(state.withStatus(status).withPower(status == DeviceStatus.CONNECTED).withVolume(volume, 100, muted));
    }

    private DeviceStatus bluetoothStatus() {
        // Auto-connect only on the very first look after start: never page a switched-off speaker repeatedly,
        // and never reconnect behind the back of a poll that just saw the speaker go away during playback.
        boolean firstLook = !lookedOnce;
        lookedOnce = true;
        try {
            Optional<BluetoothDeviceInfo> info = bluez.device(settings.adapter(), settings.address());
            if (info.isEmpty() || !info.get().paired()) {
                return DeviceStatus.UNPAIRED;
            }
            if (info.get().connected()) {
                return DeviceStatus.CONNECTED;
            }
            if (properties.autoConnect() && firstLook) {
                bluez.connect(settings.adapter(), settings.address());
                return DeviceStatus.CONNECTED;
            }
            return DeviceStatus.DISCONNECTED;
        } catch (BluezException e) {
            log.debug("{}: {}", device.name(), e.getMessage());
            return DeviceStatus.DISCONNECTED;
        }
    }

    private void publish(DeviceState next) {
        if (!next.sameIgnoringTime(state)) {
            state = next;
            onChange.accept(next);
        }
    }
}
```

(`muted` is only read here; Task 3 writes it.)

- [ ] **Step 7: Implement the pairing service and the host checks**

`BluetoothScan.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.adapters.bluetooth.bluez.BluetoothDeviceInfo;

import java.time.Instant;
import java.util.List;

public record BluetoothScan(Instant scannedAt, List<BluetoothDeviceInfo> speakers, int hiddenCount, String error) {

    public static final BluetoothScan NONE = new BluetoothScan(null, List.of(), 0, null);

    public BluetoothScan {
        speakers = List.copyOf(speakers);
    }

    public boolean ran() {
        return scannedAt != null;
    }
}
```

`BluetoothPairing.java`: `public record BluetoothPairing(Device device, String warning) {}`. `BluetoothSetupException.java`: `public class BluetoothSetupException extends Exception` with a `(String message)` constructor. `HostCheck.java`: `public record HostCheck(String id, String label, boolean ok, String detail) {}`.

`BluetoothPairingService.java` — no Spring annotation (bean in `BluetoothConfiguration`); constructors `(BluezClient, DeviceManager, BluetoothProperties)` → `this(…, Clock.systemUTC())` and `(BluezClient, DeviceManager, BluetoothProperties, Clock)`; field `private volatile BluetoothScan lastScan = BluetoothScan.NONE;`. Behaviour (normative):

```java
    private static final Comparator<BluetoothDeviceInfo> SPEAKERS_FIRST =
            Comparator.comparing((BluetoothDeviceInfo found) -> !found.audioSink())
                    .thenComparing((BluetoothDeviceInfo found) -> found.rssi() == null ? Integer.MIN_VALUE : (int) found.rssi(),
                            Comparator.reverseOrder())
                    .thenComparing(BluetoothDeviceInfo::displayName, String.CASE_INSENSITIVE_ORDER);

    public BluetoothScan scan() {
        BluetoothScan result;
        try {
            BluetoothAdapterInfo adapter = adapter();
            if (!adapter.powered()) {
                bluez.powerOn(adapter.address());
            }
            List<BluetoothDeviceInfo> found = bluez.discover(adapter.address(), Duration.ofSeconds(properties.scanSeconds()));
            List<BluetoothDeviceInfo> speakers = found.stream().filter(BluetoothDeviceInfo::mayBeSpeaker).sorted(SPEAKERS_FIRST).toList();
            result = new BluetoothScan(clock.instant(), speakers, found.size() - speakers.size(), null);
        } catch (BluezException e) {
            result = new BluetoothScan(clock.instant(), List.of(), 0, e.getMessage());
        }
        lastScan = result;
        return result;
    }

    public BluetoothPairing pair(String rawAddress) throws BluetoothSetupException {
        String address = mac(rawAddress);
        try {
            BluetoothAdapterInfo adapter = adapter();
            BluetoothDeviceInfo info = bluez.device(adapter.address(), address).orElseThrow(() -> new BluetoothSetupException(
                    address + " is not known to the host's Bluetooth adapter. Put the speaker into pairing mode and scan again."));
            if (info.servicesKnown() && !info.audioSink()) {
                throw notASpeaker(info);
            }
            if (!info.paired()) {
                ignoringAlreadyDone(() -> bluez.pair(adapter.address(), address));
            }
            bluez.trust(adapter.address(), address);
            String warning = null;
            if (!info.connected()) {
                try {
                    bluez.connect(adapter.address(), address);
                } catch (BluezException e) {
                    warning = "Paired " + info.displayName() + ", but it did not connect: " + e.getMessage();
                }
            }
            BluetoothDeviceInfo paired = bluez.device(adapter.address(), address).orElse(info);
            if (paired.servicesKnown() && !paired.audioSink()) {
                try {
                    bluez.remove(adapter.address(), address);
                } catch (BluezException e) {
                    log.warn("Could not remove {} again: {}", address, e.getMessage());
                }
                throw notASpeaker(paired);
            }
            String id = BluetoothSettings.deviceId(address);
            Optional<Device> existing = devices.device(id);
            String name = existing.map(Device::name).orElse(paired.displayName());
            String audioDevice = existing.filter(d -> d.hasAdapter(BluetoothSettings.ADAPTER_ID))
                    .map(d -> BluetoothSettings.of(d).audioDevice()).orElse("");
            Device device = new Device(id, name, DeviceKind.BLUETOOTH, address,
                    Map.of(BluetoothSettings.ADAPTER_ID, new BluetoothSettings(address, adapter.address(), audioDevice).toMap()),
                    clock.instant());
            devices.adopt(device);
            return new BluetoothPairing(device, warning);
        } catch (BluezException e) {
            throw new BluetoothSetupException(e.getMessage());
        }
    }
```

with helpers: `adapter()` = `BluetoothAdapterInfo.select(bluez.adapters(), properties.adapter())`, throwing `BluezException(NO_ADAPTER, message(NO_ADAPTER, null))` when the list is empty and `BluezException(NO_ADAPTER, "Adapter " + wanted + " not found; the host has " + describe(adapters))` when a configured one is missing; `mac(String)` = `MacAddress.normalize` with `IllegalArgumentException` → `BluetoothSetupException(e.getMessage())`; `notASpeaker(info)` = `new BluetoothSetupException(info.displayName() + " is not a speaker or headphones (no A2DP audio sink)")`; `ignoringAlreadyDone` rethrows unless `failure() == ALREADY_DONE`. `connect(id)`/`disconnect(id)`: look the device up with `devices.device(id).filter(d -> d.hasAdapter(ADAPTER_ID))` (else `throw new DeviceNotFoundException("No Bluetooth speaker " + id)`), call BlueZ with its settings, map `BluezException` → `BluetoothSetupException(e.getMessage())`, return the device. `setAudioDevice(id, value)`: same lookup; `BluetoothSettings.of(device).withAudioDevice(value)` (`IllegalArgumentException` → `BluetoothSetupException`); `Device updated = device.withAdapter(ADAPTER_ID, settings.toMap()); devices.adopt(updated); return updated;`.

`BluetoothHostChecks.java` — constructor `(BluetoothProperties properties, BluezClient bluez, Clock clock)`; `public synchronized List<HostCheck> results()` returns the cached list while `clock.instant()` is before `cachedAt + hostCheckCacheSeconds`, else runs the checks; `public synchronized void invalidate()` drops the cache. Checks in order (labels and texts normative):
1. `dbus-socket` / `D-Bus system socket`: `properties.dbusSocketPath()` empty → ok, `Using <dbusAddress>`; exists → ok, `Found <path>`; missing → not ok, `BluezFailures.noSocket(path)`.
2. `bluez` / `BlueZ`: when check 1 failed → not ok `Needs the D-Bus socket first`; else `bluez.adapters()` → ok `BlueZ answered on the system bus`, or not ok with the exception message.
3. `adapter` / `Bluetooth adapter`: no adapter list (check 1 or 2 failed) → not ok `Needs BlueZ first`; empty → not ok `BluezFailures.message(NO_ADAPTER, null)`; `select` empty → not ok `Adapter <wanted> not found; the host has <describe>`; not powered → not ok `<id> (<address>) is powered off. Scanning switches it on; if that fails run rfkill unblock bluetooth on the host.`; else ok `<id> (<address>)`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS (except `BluetoothModuleSwitchTest` additions and `BluetoothClassLoadingTest`, written next).

- [ ] **Step 8: Wire the module and prove the switch and class loading**

`BluetoothConfiguration` gains beans (imports only the `bluez` package's `DbusBluezClient` inside a method body — never as a field, parameter or return type):

```java
    @Bean(destroyMethod = "close")
    public BluezClient bluezClient(BluetoothProperties properties) {
        return new DbusBluezClient(properties.dbusAddress(), properties.dbusSocketPath(),
                Duration.ofSeconds(properties.bluezTimeoutSeconds()));
    }

    @Bean
    public BluetoothSpeakerAdapter bluetoothSpeakerAdapter(BluetoothProperties properties, BluezClient bluez) {
        return new BluetoothSpeakerAdapter(properties, bluez);
    }

    @Bean
    public BluetoothPairingService bluetoothPairingService(BluezClient bluez, DeviceManager devices, BluetoothProperties properties) {
        return new BluetoothPairingService(bluez, devices, properties);
    }

    @Bean
    public BluetoothHostChecks bluetoothHostChecks(BluetoothProperties properties, BluezClient bluez) {
        return new BluetoothHostChecks(properties, bluez, Clock.systemUTC());
    }
```

Extend `BluetoothModuleSwitchTest` (runner gains `.withBean(DeviceManager.class, () -> mock(DeviceManager.class))`):
- `isOffByDefault` additionally: no `BluezClient`, `BluetoothSpeakerAdapter`, `BluetoothPairingService`, `BluetoothHostChecks` beans.
- `canBeSwitchedOn` (with `home-control.bluetooth.enabled=true` and `home-control.bluetooth.dbus-address=unix:path=/nonexistent/hc-bus.sock`): all four beans exist; the context started although no D-Bus socket exists; `context.getBean(BluezClient.class).adapters()` throws `BluezException` with `NO_DBUS_SOCKET`.

`src/test/java/dev/andre/homecontrol/ContextSmoke.java`:

```java
package dev.andre.homecontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the whole application once and exits. Child-JVM tests run it. */
public final class ContextSmoke {

    private ContextSmoke() {
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(HomeControlApplication.class, args);
        System.out.println("CONTEXT-OK");
        context.close();
        System.exit(0);
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothClassLoadingTest.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.ContextSmoke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the real application in a fresh JVM and reads which classes it loaded. */
class BluetoothClassLoadingTest {

    @TempDir
    Path temp;

    @Test
    void aDisabledModuleLoadsNoDbusClass() throws Exception {
        List<String> loaded = loadedClasses("false");
        assertThat(loaded).noneMatch(line -> line.contains(" org.freedesktop.dbus.")
                || line.contains(" org.bluez.") || line.contains(" com.github.hypfvieh."));
        // BluetoothProperties may load (@ConfigurationPropertiesScan registers every properties record); nothing else may.
        assertThat(loaded).noneMatch(line -> line.contains(" dev.andre.homecontrol.adapters.bluetooth.")
                && !line.contains(" dev.andre.homecontrol.adapters.bluetooth.BluetoothProperties "));
    }

    @Test
    void theProbeSeesTheModuleWhenEnabled() throws Exception {
        List<String> loaded = loadedClasses("true");
        assertThat(loaded).anyMatch(line -> line.contains(" dev.andre.homecontrol.adapters.bluetooth.bluez.DbusBluezClient "));
    }

    private List<String> loadedClasses(String enabled) throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("home-control.test.runtime-classpath");
        assertThat(classpath).as("home-control.test.runtime-classpath is set by build.gradle.kts").isNotBlank();
        Path output = temp.resolve("child-" + enabled + ".log");
        Process child = new ProcessBuilder(java, "-Xlog:class+load=info", "-cp", classpath, ContextSmoke.class.getName(),
                "--server.port=0",
                "--shield.data-dir=" + temp.resolve("data-" + enabled),
                "--shield.discovery-enabled=false",
                "--home-control.ssdp.enabled=false",
                "--home-control.bluetooth.enabled=" + enabled,
                "--home-control.bluetooth.dbus-address=unix:path=" + temp.resolve("no-bus.sock"),
                "--home-control.bluetooth.runtime-dir=" + temp.resolve("runtime-" + enabled))
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        assertThat(child.waitFor(3, TimeUnit.MINUTES)).as("the child application finished").isTrue();
        List<String> lines = Files.readAllLines(output);
        assertThat(lines).as("child output ends with CONTEXT-OK").anyMatch(line -> line.contains("CONTEXT-OK"));
        assertThat(child.exitValue()).isZero();
        return lines.stream().filter(line -> line.contains("[class,load]")).toList();
    }
}
```

(If another module added since A needs a switch to start without network in this child — e.g. a scheduler property from D, `home-control.content.rails.scheduler-enabled=false` — add it to both runs; the test resources' `application.yaml` is on this classpath and already disables most.)

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS. If `aDisabledModuleLoadsNoDbusClass` fails, find the loader (`grep -rn "bluez\|freedesktop\|hypfvieh" src/main/java`) and move the reference into a `BluetoothConfiguration` method body.

- [ ] **Step 9: Write the failing web tests**

`src/test/java/dev/andre/homecontrol/web/BluetoothSetupControllerTest.java` — `@WebMvcTest(controllers = {SetupController.class, BluetoothSetupController.class}, properties = "home-control.bluetooth.enabled=true")` with `@Import(BluetoothSetupAdvice.class)`; `@MockitoBean` `BluetoothPairingService pairing`, `BluetoothHostChecks checks`, `BluetoothProperties properties` (stub `scanSeconds()` 10) and every constructor dependency of `SetupController` exactly as `SetupControllerTest` mocks them (including `DeviceManager devices`, whose `devices()`, `pairable()`, `addable()` return empty lists by default). Default stubs: `checks.results()` → three ok checks; `pairing.lastScan()` → `BluetoothScan.NONE`. Cases:
- `showsTheSectionWithHostChecks`: `checks.results()` → `[HostCheck("dbus-socket", "D-Bus system socket", true, "Found /run/dbus/system_bus_socket"), HostCheck("bluez", "BlueZ", false, "BlueZ is not running on the host. Install bluez and run: sudo systemctl enable --now bluetooth")]` → `GET /setup` 200 contains `id="bluetooth"`, `Bluetooth speakers`, `D-Bus system socket`, `sudo systemctl enable --now bluetooth`, `data-check="bluez"`, `action="/setup/bluetooth/scan"`, `Scanning takes about 10 seconds`, and `docs/bluetooth-speakers.md` (the not-ready hint).
- `showsScanResults`: `lastScan()` → scan with `BluetoothDeviceInfo("AA:BB:CC:DD:EE:FF", "JBL Flip 5", "audio-card", false, false, false, List.of(A2DP_SINK), (short) -60)` and `BluetoothDeviceInfo("11:22:33:44:55:66", null, null, true, false, false, List.of(), null)`, hidden 2 → page contains `JBL Flip 5`, `speaker`, `11:22:33:44:55:66`, `unknown type`, `paired with the host`, `name="address" value="AA:BB:CC:DD:EE:FF"` (attribute order as rendered), `Pair and add`, `2 other Bluetooth devices hidden`.
- `listsRegisteredSpeakers`: `devices.devices()` → a Bluetooth device `bluetooth-aa-bb-cc-dd-ee-ff` "JBL Flip 5" with settings `audioDevice` `pulse/bluez_output.AA_BB_CC_DD_EE_FF.1`, plus an Android TV device; `devices.state(id)` → `DeviceState.initial().withStatus(CONNECTED)` → page contains `/setup/bluetooth/connect`, `/setup/bluetooth/disconnect`, `/setup/bluetooth/audio-device`, `value="pulse/bluez_output.AA_BB_CC_DD_EE_FF.1"`, `CONNECTED`; a scan result with the registered address shows `Added` instead of a pair form for it.
- `checkAgainInvalidates`: `POST /setup/bluetooth/check` → 302 `/setup#bluetooth`; `verify(checks).invalidate()`.
- `scanRedirectsWithAMessage`: `pairing.scan()` → scan with one speaker → 302 `/setup#bluetooth`, flash `bluetoothMessage` = `Found 1 device`; with none → `No speakers found. Put the speaker into pairing mode and scan again.`; with `error` `No Bluetooth adapter found on the host.` → flash `bluetoothError` with that text and `verify(checks).invalidate()`.
- `pairingOpensTheNewSpeaker`: `pairing.pair("AA:BB:CC:DD:EE:FF")` → `BluetoothPairing(device, null)` → `POST /setup/bluetooth/pair address=AA:BB:CC:DD:EE:FF` → 302 `/?device=bluetooth-aa-bb-cc-dd-ee-ff`.
- `aPairingWarningStaysOnSetup`: warning `Paired JBL Flip 5, but it did not connect: …` → 302 `/setup#bluetooth` with flash `bluetoothError` = the warning.
- `aPairingFailureIsShown`: `BluetoothSetupException("The speaker refused pairing (x). Put it into pairing mode and try again.")` → 302 `/setup#bluetooth`, flash `bluetoothError` with that message.
- `connectDisconnectAndAudioDevice`: `POST /setup/bluetooth/connect id=…` → 302, flash `bluetoothMessage` `Connected JBL Flip 5`; `disconnect` → `Disconnected JBL Flip 5`; `POST /setup/bluetooth/audio-device id=… audioDevice=pulse/x` → `JBL Flip 5 plays on pulse/x`; blank → `JBL Flip 5 finds its audio output automatically`; `BluetoothSetupException` → flash `bluetoothError`.
- `unknownSpeakersAre404`: `pairing.connect("ghost")` throws `DeviceNotFoundException("No Bluetooth speaker ghost")` → `POST /setup/bluetooth/connect id=ghost` → 404 with that text.

`src/test/java/dev/andre/homecontrol/web/BluetoothSetupOffTest.java` — `@WebMvcTest(SetupController.class)` with the same `SetupController` mocks and default properties: `GET /setup` does not contain `id="bluetooth"`; `POST /setup/bluetooth/scan` → 404 (no handler; with a login gate from C the test slice has no filter).

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.BluetoothSetup*'`
Expected: compilation failure — `BluetoothSetupAdvice` and `BluetoothSetupController` do not exist.

- [ ] **Step 10: Implement the setup section**

`adapters/bluetooth/BluetoothSetupAdvice.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth;

import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.web.SetupController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ControllerAdvice(assignableTypes = SetupController.class)
@ConditionalOnProperty(prefix = "home-control.bluetooth", name = "enabled", havingValue = "true")
public class BluetoothSetupAdvice {

    public record SpeakerRow(String id, String name, String address, String status, String audioDevice) {
    }

    public record View(List<HostCheck> checks, boolean hostReady, BluetoothScan scan, int scanSeconds,
                       List<SpeakerRow> speakers, Set<String> registeredAddresses) {
    }

    private final ObjectProvider<BluetoothHostChecks> checks;
    private final ObjectProvider<BluetoothPairingService> pairing;
    private final ObjectProvider<DeviceManager> devices;
    private final ObjectProvider<BluetoothProperties> properties;

    public BluetoothSetupAdvice(ObjectProvider<BluetoothHostChecks> checks, ObjectProvider<BluetoothPairingService> pairing,
                                ObjectProvider<DeviceManager> devices, ObjectProvider<BluetoothProperties> properties) {
        this.checks = checks;
        this.pairing = pairing;
        this.devices = devices;
        this.properties = properties;
    }

    @ModelAttribute("bluetooth")
    public View bluetooth() {
        BluetoothHostChecks hostChecks = checks.getIfAvailable();
        BluetoothPairingService service = pairing.getIfAvailable();
        DeviceManager manager = devices.getIfAvailable();
        BluetoothProperties props = properties.getIfAvailable();
        if (hostChecks == null || service == null || manager == null || props == null) {
            return null;
        }
        List<HostCheck> results = hostChecks.results();
        List<SpeakerRow> speakers = manager.devices().stream()
                .filter(device -> device.hasAdapter(BluetoothSettings.ADAPTER_ID))
                .map(device -> {
                    BluetoothSettings settings = BluetoothSettings.of(device);
                    return new SpeakerRow(device.id(), device.name(), settings.address(),
                            manager.state(device.id()).status().name(), settings.audioDevice());
                })
                .toList();
        return new View(results, results.stream().allMatch(HostCheck::ok), service.lastScan(), props.scanSeconds(),
                speakers, speakers.stream().map(SpeakerRow::address).collect(Collectors.toSet()));
    }
}
```

`adapters/bluetooth/BluetoothSetupController.java` — `@Controller` with the same `@ConditionalOnProperty`; constructor `(BluetoothPairingService pairing, BluetoothHostChecks checks)`; every handler takes `RedirectAttributes flash` and returns `redirect:/setup#bluetooth` unless stated:
- `POST /setup/bluetooth/check` → `checks.invalidate()`.
- `POST /setup/bluetooth/scan` → `BluetoothScan scan = pairing.scan()`; error → `checks.invalidate()`, flash `bluetoothError` = `scan.error()`; none → flash `bluetoothMessage` `No speakers found. Put the speaker into pairing mode and scan again.`; else `Found <n> device` / `Found <n> devices`.
- `POST /setup/bluetooth/pair` (`@RequestParam String address`) → `BluetoothPairing result = pairing.pair(address)`; warning → flash `bluetoothError` = warning; else `return "redirect:/?device=" + UriUtils.encodeQueryParam(result.device().id(), StandardCharsets.UTF_8)`. `BluetoothSetupException` → flash `bluetoothError`.
- `POST /setup/bluetooth/connect` / `disconnect` (`@RequestParam String id`) → flash `bluetoothMessage` `Connected <name>` / `Disconnected <name>`; `BluetoothSetupException` → `bluetoothError`.
- `POST /setup/bluetooth/audio-device` (`@RequestParam String id`, `@RequestParam(required = false) String audioDevice`) → `Device device = pairing.setAudioDevice(id, audioDevice)`; blank → `<name> finds its audio output automatically`, else `<name> plays on <audioDevice>`; `BluetoothSetupException` → `bluetoothError`.
- `@ExceptionHandler(DeviceNotFoundException.class)` → `ResponseEntity.status(404).contentType(TEXT_PLAIN).body(e.getMessage())`.

`src/main/resources/templates/fragments/bluetooth-setup.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="en">
<body>
<section th:fragment="section" id="bluetooth" class="setup bluetooth">
    <h2>Bluetooth speakers</h2>
    <p class="error" th:if="${bluetoothError}" th:text="${bluetoothError}">Error</p>
    <p class="hint" th:if="${bluetoothMessage}" th:text="${bluetoothMessage}">Message</p>

    <ul class="host-checks">
        <li th:each="check : ${bluetooth.checks()}" th:classappend="${check.ok()} ? 'ok' : 'problem'"
            th:attr="data-check=${check.id()}">
            <strong th:text="${check.label()}">D-Bus system socket</strong>
            <span th:text="${check.detail()}">Found /run/dbus/system_bus_socket</span>
        </li>
    </ul>
    <p class="hint" th:unless="${bluetooth.hostReady()}">
        Fix the items marked above on the host; docs/bluetooth-speakers.md in the repository lists every step.
    </p>
    <form method="post" th:action="@{/setup/bluetooth/check}">
        <button type="submit">Check again</button>
    </form>

    <h3>Add a speaker</h3>
    <p class="hint" th:text="|Put the speaker into pairing mode first. Scanning takes about ${bluetooth.scanSeconds()} seconds.|">
        Put the speaker into pairing mode first.</p>
    <form method="post" th:action="@{/setup/bluetooth/scan}">
        <button type="submit">Scan for speakers</button>
    </form>
    <ul class="bluetooth-scan" th:if="${bluetooth.scan().ran()}">
        <li th:each="found : ${bluetooth.scan().speakers()}">
            <span th:text="${found.displayName()}">JBL Flip 5</span>
            <code th:text="${found.address()}">AA:BB:CC:DD:EE:FF</code>
            <span class="badge" th:text="${found.audioSink()} ? 'speaker'
                    : (${found.icon() != null and #strings.startsWith(found.icon(), 'audio-')} ? 'audio device' : 'unknown type')">speaker</span>
            <span class="badge" th:if="${found.paired()}">paired with the host</span>
            <span th:if="${bluetooth.registeredAddresses().contains(found.address())}">Added</span>
            <form method="post" th:action="@{/setup/bluetooth/pair}"
                  th:unless="${bluetooth.registeredAddresses().contains(found.address())}">
                <input type="hidden" name="address" th:value="${found.address()}">
                <button type="submit">Pair and add</button>
            </form>
        </li>
    </ul>
    <p class="hint" th:if="${bluetooth.scan().ran() and bluetooth.scan().hiddenCount() > 0}"
       th:text="|${bluetooth.scan().hiddenCount()} other Bluetooth devices hidden|">2 other Bluetooth devices hidden</p>

    <h3 th:if="${!#lists.isEmpty(bluetooth.speakers())}">Paired speakers</h3>
    <ul class="bluetooth-speakers">
        <li th:each="speaker : ${bluetooth.speakers()}">
            <span th:text="${speaker.name()}">JBL Flip 5</span>
            <code th:text="${speaker.address()}">AA:BB:CC:DD:EE:FF</code>
            <span class="badge" th:text="${speaker.status()}">CONNECTED</span>
            <form method="post" th:action="@{/setup/bluetooth/connect}">
                <input type="hidden" name="id" th:value="${speaker.id()}">
                <button type="submit">Connect</button>
            </form>
            <form method="post" th:action="@{/setup/bluetooth/disconnect}">
                <input type="hidden" name="id" th:value="${speaker.id()}">
                <button type="submit">Disconnect</button>
            </form>
            <form method="post" th:action="@{/setup/bluetooth/audio-device}">
                <input type="hidden" name="id" th:value="${speaker.id()}">
                <label>Audio output
                    <input name="audioDevice" th:value="${speaker.audioDevice()}" placeholder="automatic" autocomplete="off">
                </label>
                <button type="submit">Save</button>
            </form>
        </li>
    </ul>
    <p class="hint" th:if="${!#lists.isEmpty(bluetooth.speakers())}">Forget a speaker in the device list to unpair it from the host.</p>
</section>
</body>
</html>
```

`src/main/resources/templates/setup.html` — after the device sections (B's addable/paired lists and F's pairing forms) and before C's Jellyfin section add:

```html
    <th:block th:if="${bluetooth != null}">
        <section th:replace="~{fragments/bluetooth-setup :: section}"></section>
    </th:block>
```

`src/main/resources/static/app.css` — add minimal rules: `.host-checks { list-style: none; padding: 0; }`, `.host-checks li.ok::before { content: "✓ "; }`, `.host-checks li.problem::before { content: "✗ "; }`, `.host-checks li.problem { color: var(--danger, #b00020); }` (use the variable names the stylesheet already defines, if any).

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS.

- [ ] **Step 11: Run the build**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL; `HomeControlApplicationTest` still starts the context with the module off; `grep -rln "org.freedesktop\|org.bluez\|com.github.hypfvieh" src/main/java` prints only `src/main/java/dev/andre/homecontrol/adapters/bluetooth/bluez/DbusBluezClient.java`.

- [ ] **Step 12: Commit**

```bash
git add build.gradle.kts src/main/java/dev/andre/homecontrol/adapters/bluetooth \
  src/main/resources/templates/fragments/bluetooth-setup.html src/main/resources/templates/setup.html \
  src/main/resources/static/app.css \
  src/test/java/dev/andre/homecontrol/adapters/bluetooth src/test/java/dev/andre/homecontrol/ContextSmoke.java \
  src/test/java/dev/andre/homecontrol/web/BluetoothSetupControllerTest.java src/test/java/dev/andre/homecontrol/web/BluetoothSetupOffTest.java
git commit -m "feat: pair Bluetooth speakers through the host's BlueZ from the setup page"
```

---

### Task 3: J3 · Server-side player

**Files:**
- Create: `core/playback/LocalAudioSinkStrategy.java`; `adapters/bluetooth/player/StreamRedaction.java`, `MpvException.java`, `MpvNotInstalledException.java`, `MpvProcess.java`, `MpvLauncher.java`, `ProcessMpvLauncher.java`, `MpvCommandLine.java`, `MpvIpc.java`, `PlayerStatus.java`, `MpvPlayer.java`, `AudioDevice.java`, `AudioDevices.java`, `AudioDeviceNotFoundException.java`, `AudioDeviceResolver.java`
- Modify: `core/Action.java`, `core/playback/Route.java`, `core/playback/PlaybackPlanner.java`, `core/playback/RouteKeys.java`, `playback/PlaybackService.java` (and every other exhaustive `switch` over `Route`), `HomeControlConfiguration.java`, `sources/jellyfin/JellyfinPlayableResolver.java`, `web/DashboardController.java`, `src/main/resources/templates/dashboard.html`, `adapters/bluetooth/BluetoothConfiguration.java`, `BluetoothSpeakerAdapter.java`, `BluetoothSpeakerSession.java`, `BluetoothHostChecks.java`
- Test: `core/ActionTest.java`, `device/DeviceManagerExecuteTest.java`, `core/playback/LocalAudioSinkStrategyTest.java`, `PlaybackPlannerTest.java`, `RouteKeysTest.java`, `playback/PlaybackServiceTest.java`, `sources/jellyfin/JellyfinPlayableResolverTest.java`, `web/DeviceControllerTest.java`, `web/DashboardPageTest.java`; `adapters/bluetooth/player/FakeMpv.java`, `FakeMpvScript.java`, `InProcessMpvLauncher.java`, `StreamRedactionTest.java`, `MpvCommandLineTest.java`, `MpvIpcTest.java`, `AudioDevicesTest.java`, `AudioDeviceResolverTest.java`, `MpvPlayerTest.java`, `ProcessMpvLauncherTest.java`; `adapters/bluetooth/BluetoothSpeakerSessionTest.java`, `BluetoothSpeakerAdapterTest.java`, `BluetoothHostChecksTest.java`, `BluetoothModuleSwitchTest.java`

**Interfaces:**
- Consumes: I — `Action.PlayMedia(URI url, String mimeType, String title, String subtitle)` (redacted `toString`), `Action.Pause()`, `Action.Resume()`, `Action.acceptedBy(Set<Capability>)`, `Stop.acceptedBy`, `core.RedactedUris.withoutQuery(URI)`, `Route.Render`, `MediaRendererStrategy`, `PlaybackPlanner.explain` `StreamUrl` case, dashboard `rendererControls` and `canOpenLinks`, `POST /devices/{id}/pause|resume`. B — `Action.SetVolume(int level)`, `Action.Mute(boolean muted)`, `Action.Stop()`, `ActionFailedException(String)`, `NowPlaying(String title, PlaybackState state, double positionSeconds, Double durationSeconds)`, `PlaybackState`, `DeviceState.withNowPlaying`, `AppLinks.fromUrl` (adds `StreamUrl` for `.mp3/.m4a/.aac/.flac/.ogg/.wav` with the decoded file name as title), `CastStreamStrategy`, `POST /devices/{id}/volume|mute|stop`. C — `PlayableResolver`, `JellyfinPlayableResolver` step rules, `JellyfinSettings.serverUrl()/deviceServerUrl()`, `JellyfinStreams.directStream(JellyfinConnection, URI, JsonNode)`. D — `RouteKeys.key/optimistic`, `PlaybackPlanner.routes`, `PlaybackService.preview/attempt`. Task 2 — `BluetoothSpeakerSession`, `BluetoothSpeakerAdapter`, `BluetoothHostChecks`, `FakeBluezClient`, `BluetoothSettings`.
- Produces:
  - `Action.PlayMedia`, `Pause`, `Resume`: `acceptedBy` true for `MEDIA_RENDERER` or `LOCAL_AUDIO_SINK`; `Stop.acceptedBy` true for `CAST_RECEIVER`, `MEDIA_RENDERER` or `LOCAL_AUDIO_SINK`. `requires()` unchanged.
  - `Route.PlayLocally(URI url, String mimeType, String title, String subtitle)` with `Action action()` (→ `Action.PlayMedia`), `describe()` = `Play through the server on this Bluetooth speaker`, redacted `toString()`; route key `local-audio`, not optimistic.
  - `LocalAudioSinkStrategy` (`LOCAL_AUDIO_SINK` + first `http(s)` `StreamUrl` with an `audio/*` type) with `static boolean playable(PlayableRef.StreamUrl)`; planner bean order `JellyfinSessionStrategy, AppLinkStrategy, CastMessageStrategy, CastLoadStrategy, CastStreamStrategy, MediaRendererStrategy, LocalAudioSinkStrategy` (plus whatever E–H inserted before `MediaRendererStrategy`, unchanged).
  - Planner explanation for `StreamUrl` on a local sink with no playable audio stream: `a Bluetooth speaker plays audio streams only`.
  - `StreamRedaction.redact(String) → String`.
  - `class MpvException extends Exception` with `static MpvException refused(String command, String error)` (message `mpv refused <command>: <error>`), `static MpvException loadFailed(String reason)` (message `the stream could not be loaded (<reason>)`), `String error()`.
  - `class MpvNotInstalledException extends IOException` (`mpv was not found at "<path>"`).
  - `interface MpvProcess { long pid(); boolean alive(); CompletableFuture<Integer> onExit(); String recentErrors(); void terminate(Duration grace); }`
  - `interface MpvLauncher extends AutoCloseable { MpvProcess start(List<String> arguments) throws IOException; String run(List<String> arguments, Duration timeout) throws IOException; void close(); }` — `arguments` exclude the executable.
  - `ProcessMpvLauncher(String mpvPath) implements MpvLauncher`.
  - `MpvCommandLine.arguments(Path socket, String audioDevice, int volume) → List<String>`, `MpvCommandLine.validAudioDevice(String) → boolean`.
  - `final class MpvIpc implements AutoCloseable` — `static MpvIpc connect(Path socket, Duration timeout, BooleanSupplier processAlive, EventListener listener) throws IOException`, `JsonNode command(Duration timeout, Object... command) throws IOException, MpvException`, `boolean open()`, `void close()`, `interface EventListener { void onEvent(JsonNode event); void onClosed(); }`, package-private `static void readLines(SocketChannel, Consumer<String>) throws IOException`.
  - `record PlayerStatus(boolean paused, boolean buffering, double positionSeconds, Double durationSeconds, String metadataTitle, int volume, boolean muted)`.
  - `final class MpvPlayer implements AutoCloseable` — `MpvPlayer(MpvLauncher launcher, Path socket, Duration startTimeout, Duration loadTimeout, Duration commandTimeout)`, `void play(URI url, String audioDevice, int volume, boolean muted) throws IOException, MpvException`, `boolean active()`, `void pause(boolean paused)`, `void volume(int percent)`, `void mute(boolean muted)` (these three `throws IOException, MpvException`), `Optional<PlayerStatus> status()`, `void stop()`, `void close()`, `static Path socketFor(Path runtimeDir, String deviceId)`.
  - `record AudioDevice(String id, String description)`; `AudioDevices.parse(String helpOutput) → List<AudioDevice>`, `AudioDevices.forMac(List<AudioDevice>, String mac) → Optional<AudioDevice>`, `AudioDevices.soundServerOutputs(List<AudioDevice>) → List<AudioDevice>`.
  - `class AudioDeviceNotFoundException extends Exception`; `AudioDeviceResolver(MpvLauncher launcher, String template, Duration timeout)` with `String resolve(String mac, String manualDevice) throws AudioDeviceNotFoundException, IOException`.
  - `BluetoothSpeakerSession(Device, BluetoothProperties, BluezClient, MpvPlayer, AudioDeviceResolver, Consumer<DeviceState>)` executing `PlayMedia`, `Pause`, `Resume`, `Stop`, `SetVolume`, `Mute`; `static final String MPV_MISSING`.
  - `BluetoothSpeakerAdapter(BluetoothProperties, BluezClient, MpvLauncher)`; `BluetoothHostChecks(BluetoothProperties, BluezClient, MpvLauncher, Clock)` (+ package-private constructor with `Function<String, String> environment`) with checks `mpv` and `audio-output`.
  - `JellyfinPlayableResolver` builds a `StreamUrl` for `LOCAL_AUDIO_SINK` devices, from `serverUrl()` unless the device is also a Cast receiver or media renderer.
  - Dashboard: `rendererControls` and `canOpenLinks` also true for `LOCAL_AUDIO_SINK`; model attribute `localAudio` (`LOCAL_AUDIO_SINK`).
  - Test support: `FakeMpv` (in-process and `main`), `FakeMpvScript.create(Path dir, Map<String,String> env)`, `FakeMpvScript.log(Path)`, `InProcessMpvLauncher`.

**mpv JSON IPC (normative, mpv 0.41 `--input-ipc-server`).** A Unix stream socket. Each message is one UTF-8 JSON object terminated by `\n`, both ways. Request: `{"command":["<name>",arg…],"request_id":<n>}` (n ≥ 1). Reply: `{"request_id":<n>,"error":"success","data":<value>}` (`data` present for `get_property`; may be `null`), or `{"request_id":<n>,"error":"<reason>"}` with reasons such as `property unavailable` (e.g. `time-pos` while idle), `property not found`, `invalid parameter`. Events carry no `request_id`: `{"event":"start-file","playlist_entry_id":1}`, `{"event":"file-loaded"}`, `{"event":"playback-restart"}`, `{"event":"end-file","reason":"eof|stop|quit|error|redirect","playlist_entry_id":1,"file_error":"loading failed"}` (`file_error` only with `error`). Commands used: `["loadfile","<url>","replace"]`, `["get_property","pause|paused-for-cache|time-pos|duration|metadata|volume|mute|idle-active"]`, `["set_property","pause",true|false]`, `["set_property","volume",<0..100>]`, `["set_property","mute",true|false]`, `["quit"]`. With `--idle=once` mpv exits after the first loaded file ends (eof, error or `stop`).

- [ ] **Step 1: Write the failing core tests**

`core/ActionTest.java` — add `localAudioSinksAcceptPlaybackActions`: with `sink = EnumSet.of(LOCAL_AUDIO_SINK)`: `new Action.PlayMedia(URI.create("http://nas/a.mp3"), "audio/mpeg", "A", null).acceptedBy(sink)`, `new Action.Pause().acceptedBy(sink)`, `new Action.Resume().acceptedBy(sink)`, `new Action.Stop().acceptedBy(sink)` are true; `new Action.SetVolume(10).acceptedBy(sink)` false and `acceptedBy(EnumSet.of(LOCAL_AUDIO_SINK, VOLUME))` true; `new Action.PressKey(RemoteKey.HOME).acceptedBy(sink)` false; `requires()` of `PlayMedia`/`Pause`/`Resume` is still `MEDIA_RENDERER` and of `Stop` still `CAST_RECEIVER`; I's renderer and Cast assertions still hold.

`device/DeviceManagerExecuteTest.java` — add `playbackReachesALocalAudioSink`: registry holds `Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF", {bluetooth: {}}, now)`; manager over `new StubAdapter("bluetooth", DeviceKind.BLUETOOTH, false, false, LOCAL_AUDIO_SINK, VOLUME)`; `start()`; `execute` of `PlayMedia`, `Pause`, `Resume`, `Stop`, `SetVolume(20)`, `Mute(true)` all reach the stub handle's `executed` in order; `execute(id, new Action.PressKey(RemoteKey.HOME))` → `UnsupportedActionException`.

`core/playback/LocalAudioSinkStrategyTest.java`:
- `playsTheFirstAudioStream`: item title `Bunny Song`, subtitle `The Rabbits`, playables `[StreamUrl(URI("http://nas/film.mp4"), "video/mp4"), StreamUrl(URI("http://nas/a.flac"), "audio/flac"), StreamUrl(URI("http://nas/b.mp3"), "audio/mpeg")]`, capabilities `{LOCAL_AUDIO_SINK, VOLUME}` → `Route.PlayLocally(URI("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits")`; its `action()` equals `new Action.PlayMedia(URI("http://nas/a.flac"), "audio/flac", "Bunny Song", "The Rabbits")`; `describe()` = `Play through the server on this Bluetooth speaker`.
- `needsALocalAudioSink`: `{MEDIA_RENDERER, VOLUME}` → empty.
- `refusesVideoAndNonHttpStreams`: only the video stream → empty; `StreamUrl(URI("file:///music/a.flac"), "audio/flac")` → empty; `StreamUrl(URI("HTTPS://nas/a.ogg"), "Audio/Ogg")` → present (case-insensitive); `playable(...)` mirrors these.
- `neverPrintsTheStreamCredential`: `new Route.PlayLocally(URI("http://h:8096/Audio/x/stream.flac?ApiKey=secret-key"), "audio/flac", "T", null).toString()` contains `http://h:8096/Audio/x/stream.flac?…` and not `secret-key`.

`core/playback/PlaybackPlannerTest.java` — the planner field gets `new LocalAudioSinkStrategy()` appended; with `AUDIO = StreamUrl(URI("http://nas/a.mp3"), "audio/mpeg")` and `VIDEO = StreamUrl(URI("http://nas/f.mp4"), "video/mp4")` add:
- `aLocalAudioSinkGetsTheAudioStream`: `plan(item(AUDIO), {LOCAL_AUDIO_SINK, VOLUME})` is a `Route.PlayLocally` with `AUDIO.url()`.
- `everyOtherRungComesFirst`: `{MEDIA_RENDERER, LOCAL_AUDIO_SINK}` → `Route.Render`; `{CAST_RECEIVER, LOCAL_AUDIO_SINK}` → `Route.Cast`; `routes(item(AUDIO), {MEDIA_RENDERER, LOCAL_AUDIO_SINK})` → `[Render, PlayLocally]` in that order.
- `explainsVideoOnABluetoothSpeaker`: `plan(item(VIDEO), {LOCAL_AUDIO_SINK, VOLUME})` is `Unroutable` whose reason contains `a Bluetooth speaker plays audio streams only`; a planner without `LocalAudioSinkStrategy` and `item(AUDIO)` on `{LOCAL_AUDIO_SINK}` → reason contains `the stream was not accepted`; `{REMOTE_KEYS}` still `this device cannot play a direct stream`.
- `theApplicationsPlannerEndsWithTheLocalAudioSink`: `new HomeControlConfiguration().playbackPlanner(…)` (whatever the real bean method needs) routes `item(AUDIO)` on `{LOCAL_AUDIO_SINK}` to `PlayLocally` and on `{MEDIA_RENDERER, LOCAL_AUDIO_SINK}` to `Render`.

`core/playback/RouteKeysTest.java` — `localPlaybackHasAKey`: `key(new Route.PlayLocally(URI("http://nas/a.mp3"), "audio/mpeg", "A", null))` = `local-audio`; `optimistic(...)` false.

`playback/PlaybackServiceTest.java` — `executesALocalPlaybackRoute`: planner `List.of(new AppLinkStrategy(), new CastStreamStrategy(), new MediaRendererStrategy(), new LocalAudioSinkStrategy())`, device `Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", BLUETOOTH, "AA:BB:CC:DD:EE:FF", {bluetooth: {}}, now)`, capabilities `{LOCAL_AUDIO_SINK, VOLUME}` → `play(AppLinks.fromUrl("http://nas.local/music/song.mp3"), id)` is a `Route.PlayLocally`; `verify(devices).execute(id, new Action.PlayMedia(URI("http://nas.local/music/song.mp3"), "audio/mpeg", "song.mp3", null))`. If D's `attempt` executes routes through its own switch, add the same assertion for `attempt(item, id, Set.of())`.

`web/DeviceControllerTest.java` — `describesALocalPlaybackRoute`: `playback.play(any(), eq("shield"))` → `new Route.PlayLocally(URI("http://nas/a.mp3"), "audio/mpeg", "A", null)` → `POST /devices/shield/play uri=http://nas/a.mp3` → 200 body `Play through the server on this Bluetooth speaker`.

`web/DashboardPageTest.java` — `aBluetoothSpeakerGetsPlaybackControlsAndTheLinkForm`: device `Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", DeviceKind.BLUETOOTH, "AA:BB:CC:DD:EE:FF", {bluetooth: {address: AA:BB:CC:DD:EE:FF}}, now)`, capabilities `{LOCAL_AUDIO_SINK, VOLUME}` (stubs as in I's `aMediaRendererGetsPlaybackControls`) → page contains `/devices/bluetooth-aa-bb-cc-dd-ee-ff/pause`, `/resume`, `/stop`, `/volume`, `/mute`, `/devices/bluetooth-aa-bb-cc-dd-ee-ff/play` and `This speaker plays through the server`; not `/devices/bluetooth-aa-bb-cc-dd-ee-ff/key/`.

`sources/jellyfin/JellyfinPlayableResolverTest.java` — `aLocalAudioSinkStreamsFromTheServersOwnAddress` (the existing test setup: connected settings, fake server; set the device-facing address to `http://192.0.2.10:8096` in however the setup stores `JellyfinSettings`): device `Device("bluetooth-aa-bb-cc-dd-ee-ff", "JBL Flip 5", BLUETOOTH, "AA:BB:CC:DD:EE:FF", {bluetooth: {}}, now)`; fake `GET /Sessions` → `sessions.json`, `GET /Items/c0ffee00c0ffee00c0ffee00c0ffee01` → `item-track.json`, `POST /Items/c0ffee00c0ffee00c0ffee00c0ffee01/PlaybackInfo` → `playback-info-audio.json`; `resolve(JellyfinItem(SERVER_ID, "c0ffee00c0ffee00c0ffee00c0ffee01", 0), item, device, EnumSet.of(LOCAL_AUDIO_SINK, VOLUME))` → playables exactly `[StreamUrl(<fake server URL>/Audio/c0ffee00c0ffee00c0ffee00c0ffee01/stream.flac?static=true&mediaSourceId=c0ffee00c0ffee00c0ffee00c0ffee01&ApiKey=<token>, "audio/flac")]`, no URL containing `192.0.2.10`, notes `[no Jellyfin app is open on JBL Flip 5]`. Also `aMixedDeviceKeepsTheDeviceAddress`: capabilities `{MEDIA_RENDERER, LOCAL_AUDIO_SINK}` → the stream starts with `http://192.0.2.10:8096/`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.core.*' --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.playback.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.sources.jellyfin.*'`
Expected: compilation failure — `Route.PlayLocally` and `LocalAudioSinkStrategy` do not exist.

- [ ] **Step 2: Implement the local route**

`core/Action.java` — add a private helper to the interface and override `acceptedBy` in I's records:

```java
    /** Actions that drive a stream: renderers play it on the device, local sinks through the server. */
    private static boolean playsStreams(Set<Capability> capabilities) {
        return capabilities.contains(Capability.MEDIA_RENDERER) || capabilities.contains(Capability.LOCAL_AUDIO_SINK);
    }
```

In `PlayMedia`, `Pause` and `Resume` add

```java
        @Override
        public boolean acceptedBy(Set<Capability> capabilities) {
            return Action.playsStreams(capabilities);
        }
```

and change `Stop.acceptedBy` to `return capabilities.contains(Capability.CAST_RECEIVER) || Action.playsStreams(capabilities);`.

`core/playback/Route.java` — add (next to I's `Render`):

```java
    /** Play an audio stream with the server's own player on a local audio sink such as a Bluetooth speaker (spec §5.3 rung 5). */
    record PlayLocally(URI url, String mimeType, String title, String subtitle) implements Route {

        public Action action() {
            return new Action.PlayMedia(url, mimeType, title, subtitle);
        }

        @Override
        public String describe() {
            return "Play through the server on this Bluetooth speaker";
        }

        @Override
        public String toString() {
            return "PlayLocally[url=" + RedactedUris.withoutQuery(url) + ", mimeType=" + mimeType + ", title=" + title + "]";
        }
    }
```

`core/playback/LocalAudioSinkStrategy.java`:

```java
package dev.andre.homecontrol.core.playback;

import dev.andre.homecontrol.core.Capability;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Rung 5 of spec §5.3, the last: a local audio sink plays an http(s) audio stream through the server's player. */
public class LocalAudioSinkStrategy implements RouteStrategy {

    @Override
    public Optional<Route> route(ContentItem item, Set<Capability> capabilities) {
        if (!capabilities.contains(Capability.LOCAL_AUDIO_SINK)) {
            return Optional.empty();
        }
        return item.playables().stream()
                .filter(PlayableRef.StreamUrl.class::isInstance)
                .map(PlayableRef.StreamUrl.class::cast)
                .filter(LocalAudioSinkStrategy::playable)
                .findFirst()
                .map(stream -> new Route.PlayLocally(stream.url(), stream.mimeType(), item.title(), item.subtitle()));
    }

    /** Only audio, and only over HTTP: the server must never open local files or decode video for a speaker. */
    public static boolean playable(PlayableRef.StreamUrl stream) {
        String scheme = stream.url().getScheme();
        return scheme != null
                && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && stream.mimeType() != null
                && stream.mimeType().toLowerCase(Locale.ROOT).startsWith("audio/");
    }
}
```

`PlaybackPlanner.explain` — the `StreamUrl` case becomes (keep I's wording for the other cases):

```java
                case PlayableRef.StreamUrl stream -> reasons.add(
                        capabilities.contains(Capability.LOCAL_AUDIO_SINK) && !LocalAudioSinkStrategy.playable(stream)
                                && !capabilities.contains(Capability.CAST_RECEIVER) && !capabilities.contains(Capability.MEDIA_RENDERER)
                                ? "a Bluetooth speaker plays audio streams only"
                                : capabilities.contains(Capability.CAST_RECEIVER) || capabilities.contains(Capability.MEDIA_RENDERER)
                                        || capabilities.contains(Capability.LOCAL_AUDIO_SINK)
                                        ? "the stream was not accepted" : "this device cannot play a direct stream");
```

`core/playback/RouteKeys.java` — `key`: `case Route.PlayLocally ignored -> "local-audio";`; `optimistic` returns false for it (add a branch if it is an exhaustive switch).

Every other exhaustive `switch` over `Route` (`grep -rn "case Route.Render" src/main/java`) gets the parallel branch; in `PlaybackService` (both `play` and D's `attempt`/execute helper): `case Route.PlayLocally local -> devices.execute(deviceId, local.action());`.

`HomeControlConfiguration.playbackPlanner` — append `new LocalAudioSinkStrategy()` as the last strategy; the comment ends "…then media renderers (DLNA/UPnP/Sonos), then the server's own player for local audio sinks (Bluetooth)."

`sources/jellyfin/JellyfinPlayableResolver.java` (C7 rules): step 3 becomes "Device has none of `CAST_RECEIVER`, `MEDIA_RENDERER`, `LOCAL_AUDIO_SINK` → return the notes only"; step 6 builds the direct stream with

```java
            // The server's own player fetches the stream for a local sink; TVs and speakers need the device-facing address.
            URI streamBase = capabilities.contains(Capability.CAST_RECEIVER) || capabilities.contains(Capability.MEDIA_RENDERER)
                    ? settings.deviceServerUrl() : settings.serverUrl();
```

and passes `streamBase` to `JellyfinStreams.directStream`. Step 5 (Cast message) is unchanged.

`web/DashboardController` — `rendererControls` = `MEDIA_RENDERER || LOCAL_AUDIO_SINK`; `canOpenLinks` gains `|| LOCAL_AUDIO_SINK`; `model.addAttribute("localAudio", capabilities.contains(Capability.LOCAL_AUDIO_SINK))`. `dashboard.html` — after I's renderer hint add `<p class="hint" th:if="${localAudio}">This speaker plays through the server: direct audio links (.mp3, .flac, .m4a, .ogg …) and Jellyfin music work; the server must be able to reach the URL.</p>`.

Run the Step 1 command. Expected: PASS.

- [ ] **Step 3: Commit the route**

```bash
git add src/main/java/dev/andre/homecontrol/core src/main/java/dev/andre/homecontrol/playback \
  src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java \
  src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java \
  src/main/java/dev/andre/homecontrol/web/DashboardController.java src/main/resources/templates/dashboard.html \
  src/test/java/dev/andre/homecontrol/core src/test/java/dev/andre/homecontrol/device/DeviceManagerExecuteTest.java \
  src/test/java/dev/andre/homecontrol/playback src/test/java/dev/andre/homecontrol/web/DeviceControllerTest.java \
  src/test/java/dev/andre/homecontrol/web/DashboardPageTest.java \
  src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolverTest.java
git commit -m "feat: route audio streams to local audio sinks as the last planner rung"
```

- [ ] **Step 4: Write the failing player helper tests**

`adapters/bluetooth/player/StreamRedactionTest.java`:
- `hidesQueriesOfUrls`: `redact("Failed to open http://192.168.1.20:8096/Audio/x/stream.flac?static=true&ApiKey=secret.")` = `Failed to open http://192.168.1.20:8096/Audio/x/stream.flac?…` (the query token runs to the next whitespace or quote, so the final dot goes with it); `https://h/a.mp3?token=abc def` → `https://h/a.mp3?… def`; two URLs in one line are both redacted.
- `hidesLooseKeys`: `redact("api_key=abc&x=1 ApiKey=def token=ghi")` contains none of `abc`, `def`, `ghi` and contains `api_key=…`, `ApiKey=…`, `token=…`.
- `leavesOtherTextAlone`: `redact("[ao] Failed to initialize audio output")` unchanged; `redact(null)` null.

`adapters/bluetooth/player/MpvCommandLineTest.java`:
- `buildsTheNormativeArguments`: `arguments(Path.of("/tmp/hc/mpv-1.sock"), "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", 35)` equals exactly `[--no-config, --idle=once, --no-video, --input-terminal=no, --msg-level=all=error, --ytdl=no, --load-scripts=no, --input-default-bindings=no, --audio-client-name=home-control, --volume-max=100, --volume=35, --network-timeout=15, --audio-device=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1, --input-ipc-server=/tmp/hc/mpv-1.sock]`.
- `clampsTheVolume`: `-5` → `--volume=0`; `150` → `--volume=100`.
- `refusesUnsafeAudioDevices`: `"pulse/x --script=/tmp/evil.lua"`, `"a\nb"`, `""`, `null` → `IllegalArgumentException`; `validAudioDevice("alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp")` true.

`adapters/bluetooth/player/AudioDevicesTest.java` — `PIPEWIRE_HOST` text block (the format mpv 0.41 prints, see Tech Stack):

```text
List of detected audio devices:
  'auto' (Autoselect device)
  'pipewire' (Default (pipewire))
  'pipewire/alsa_output.platform-bcm2835_audio.stereo-fallback' (Built-in Audio Stereo)
  'pipewire/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)
  'pulse/alsa_output.platform-bcm2835_audio.stereo-fallback' (Built-in Audio Stereo)
  'pulse/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)
  'alsa' (Default (alsa))
```

and `NO_SERVER` = the four-line output quoted under Tech Stack:
- `parsesEveryDevice`: `parse(PIPEWIRE_HOST)` has 7 devices; the first is `AudioDevice("auto", "Autoselect device")`, the second `AudioDevice("pipewire", "Default (pipewire)")`; lines without quotes are ignored; `parse("")` and `parse(null)` empty.
- `findsTheSpeakerByMacPreferringPipeWire`: `forMac(parse(PIPEWIRE_HOST), "aa:bb:cc:dd:ee:ff")` = `pipewire/bluez_output.AA_BB_CC_DD_EE_FF.1`; without the two `pipewire/` lines → `pulse/bluez_output.AA_BB_CC_DD_EE_FF.1`; a PulseAudio sink `pulse/bluez_sink.AA_BB_CC_DD_EE_FF.a2dp_sink` matches; an ALSA id `alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp` matches the colon spelling; another MAC → empty.
- `soundServerOutputs`: `soundServerOutputs(parse(PIPEWIRE_HOST))` has the 4 ids containing `/` that start with `pipewire/` or `pulse/`; `soundServerOutputs(parse(NO_SERVER))` empty.

`adapters/bluetooth/player/AudioDeviceResolverTest.java` (with `InProcessMpvLauncher`, written in Step 6 — this test compiles once it exists):
- `aManualDeviceWins`: `resolve("AA:BB:CC:DD:EE:FF", "alsa/hw:1,0")` = `alsa/hw:1,0`; launcher `runs` empty.
- `aTemplateComesNext`: template `alsa/bluealsa:DEV={mac},PROFILE=a2dp` → `alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp`; template `pulse/bluez_sink.{mac_}.a2dp_sink` → `pulse/bluez_sink.AA_BB_CC_DD_EE_FF.a2dp_sink`; no runs.
- `otherwiseMpvIsAsked`: launcher options with devices `pulse/alsa_output.hdmi=HDMI` and `pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5` → `pulse/bluez_output.AA_BB_CC_DD_EE_FF.1`; the run's arguments = `[--no-config, --audio-device=help]`.
- `nothingFoundIsExplained`: only the HDMI device → `AudioDeviceNotFoundException` whose message contains `AA:BB:CC:DD:EE:FF`, `PipeWire or PulseAudio` and `setup page`.
- `mpvMissingPropagates`: `launcher.startFailure = new MpvNotInstalledException("mpv", new IOException("error=2"))` → `MpvNotInstalledException`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.player.StreamRedactionTest' --tests 'dev.andre.homecontrol.adapters.bluetooth.player.MpvCommandLineTest' --tests 'dev.andre.homecontrol.adapters.bluetooth.player.AudioDevicesTest'`
Expected: compilation failure — the `player` package does not exist. (Leave `AudioDeviceResolverTest` for Step 7 if it does not compile yet; create it together with `InProcessMpvLauncher`.)

- [ ] **Step 5: Implement the helpers**

`adapters/bluetooth/player/StreamRedaction.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import java.util.regex.Pattern;

/** Stream URLs can carry credentials; everything mpv prints passes through here before it is kept or shown. */
public final class StreamRedaction {

    private static final Pattern URL_QUERY = Pattern.compile("((?:https?|rtsp|rtmp)://[^\\s?#'\"]*)\\?[^\\s'\"]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOSE_KEY = Pattern.compile("\\b(api_?key|token)=[^\\s&'\"]+", Pattern.CASE_INSENSITIVE);

    private StreamRedaction() {
    }

    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String withoutQueries = URL_QUERY.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(match.group(1)) + "?…");
        return LOOSE_KEY.matcher(withoutQueries).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(match.group(1)) + "=…");
    }
}
```

`adapters/bluetooth/player/MpvCommandLine.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** The mpv arguments. The stream URL is never an argument: it goes over IPC, because argv is visible in ps. */
public final class MpvCommandLine {

    private static final Pattern AUDIO_DEVICE = Pattern.compile("[A-Za-z0-9_.:/=,@+-]{1,200}");

    private MpvCommandLine() {
    }

    public static List<String> arguments(Path socket, String audioDevice, int volume) {
        if (!validAudioDevice(audioDevice)) {
            throw new IllegalArgumentException("Not a usable audio device id");
        }
        return List.of(
                "--no-config",
                "--idle=once",
                "--no-video",
                "--input-terminal=no",
                "--msg-level=all=error",
                "--ytdl=no",
                "--load-scripts=no",
                "--input-default-bindings=no",
                "--audio-client-name=home-control",
                "--volume-max=100",
                "--volume=" + Math.clamp(volume, 0, 100),
                "--network-timeout=15",
                "--audio-device=" + audioDevice,
                "--input-ipc-server=" + socket);
    }

    public static boolean validAudioDevice(String audioDevice) {
        return audioDevice != null && AUDIO_DEVICE.matcher(audioDevice).matches();
    }
}
```

`adapters/bluetooth/player/AudioDevice.java`: `public record AudioDevice(String id, String description) {}`.

`adapters/bluetooth/player/AudioDevices.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@code mpv --audio-device=help}: lines like {@code   'pulse/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)}. */
public final class AudioDevices {

    private static final Pattern LINE = Pattern.compile("^\\s*'([^']+)'\\s*\\((.*)\\)\\s*$");

    private AudioDevices() {
    }

    public static List<AudioDevice> parse(String helpOutput) {
        List<AudioDevice> devices = new ArrayList<>();
        if (helpOutput == null) {
            return devices;
        }
        for (String line : helpOutput.split("\\R")) {
            Matcher matcher = LINE.matcher(line);
            if (matcher.matches()) {
                devices.add(new AudioDevice(matcher.group(1), matcher.group(2)));
            }
        }
        return devices;
    }

    /** The speaker's output: its MAC appears in the sink name (PipeWire/PulseAudio with underscores, bluealsa with colons). */
    public static Optional<AudioDevice> forMac(List<AudioDevice> devices, String mac) {
        String colons = mac.toUpperCase(Locale.ROOT);
        String underscores = colons.replace(':', '_');
        return devices.stream()
                .filter(device -> {
                    String id = device.id().toUpperCase(Locale.ROOT);
                    return id.contains(underscores) || id.contains(colons);
                })
                .min(Comparator.comparingInt(AudioDevices::rank));
    }

    /** Outputs of a reachable PipeWire or PulseAudio server; empty when mpv sees no sound server. */
    public static List<AudioDevice> soundServerOutputs(List<AudioDevice> devices) {
        return devices.stream().filter(device -> device.id().startsWith("pipewire/") || device.id().startsWith("pulse/")).toList();
    }

    private static int rank(AudioDevice device) {
        if (device.id().startsWith("pipewire/")) {
            return 0;
        }
        if (device.id().startsWith("pulse/")) {
            return 1;
        }
        return device.id().startsWith("alsa/") ? 2 : 3;
    }
}
```

`AudioDeviceNotFoundException.java`: `public class AudioDeviceNotFoundException extends Exception` with `(String message)`. `MpvNotInstalledException.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.IOException;

public class MpvNotInstalledException extends IOException {
    public MpvNotInstalledException(String mpvPath, IOException cause) {
        super("mpv was not found at \"" + mpvPath + "\"", cause);
    }
}
```

`MpvException.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

/** mpv answered, but refused. */
public class MpvException extends Exception {

    private final String error;

    private MpvException(String message, String error) {
        super(message);
        this.error = error;
    }

    public static MpvException refused(String command, String error) {
        return new MpvException("mpv refused " + command + ": " + error, error);
    }

    public static MpvException loadFailed(String reason) {
        return new MpvException("the stream could not be loaded (" + StreamRedaction.redact(reason) + ")", reason);
    }

    public String error() {
        return error;
    }
}
```

`MpvProcess.java` and `MpvLauncher.java` — the interfaces listed under Produces, with Javadoc: `terminate(grace)` = "SIGTERM now, SIGKILL when still alive after `grace`; returns when the process is gone or after two more seconds"; `recentErrors()` = "the last stderr lines, redacted, joined with ` | `"; `run` = "runs mpv to completion and returns stdout and stderr (redacted); throws `MpvNotInstalledException` when the executable cannot be started".

`AudioDeviceResolver.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Which mpv --audio-device plays on a given speaker. Resolved per play: the sink exists only while the speaker is connected. */
public class AudioDeviceResolver {

    private final MpvLauncher launcher;
    private final String template;
    private final Duration timeout;

    public AudioDeviceResolver(MpvLauncher launcher, String template, Duration timeout) {
        this.launcher = launcher;
        this.template = template == null ? "" : template.strip();
        this.timeout = timeout;
    }

    public String resolve(String mac, String manualDevice) throws AudioDeviceNotFoundException, IOException {
        if (manualDevice != null && !manualDevice.isBlank()) {
            return manualDevice.strip();
        }
        if (!template.isEmpty()) {
            return template.replace("{mac_}", mac.replace(':', '_')).replace("{mac}", mac);
        }
        List<AudioDevice> devices = AudioDevices.parse(launcher.run(List.of("--no-config", "--audio-device=help"), timeout));
        return AudioDevices.forMac(devices, mac).map(AudioDevice::id).orElseThrow(() -> new AudioDeviceNotFoundException(
                "No audio output for " + mac + " was found. Make sure the speaker is connected and the host's "
                        + "PipeWire or PulseAudio lists it, or set its audio output on the setup page."));
    }
}
```

Run the Step 4 command. Expected: PASS.

- [ ] **Step 6: Write the fake mpv**

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/player/FakeMpv.java` (protocol-level test infrastructure; Jackson 3 names — if one differs in the resolved version, use its Jackson 3 equivalent):

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Speaks enough of mpv's JSON IPC, with --idle=once semantics, for the player: in-process for unit
 * tests, or as a fake mpv executable through FakeMpvScript (environment variables configure it).
 */
public final class FakeMpv implements AutoCloseable {

    public record Options(double durationSeconds, String metadataTitle, String failUrlsContaining, List<String> audioDevices) {

        public static Options defaults() {
            return new Options(187.0, null, null, List.of());
        }

        public Options withDuration(double seconds) {
            return new Options(seconds, metadataTitle, failUrlsContaining, audioDevices);
        }

        public Options withMetadataTitle(String title) {
            return new Options(durationSeconds, title, failUrlsContaining, audioDevices);
        }

        public Options failingFor(String urlPart) {
            return new Options(durationSeconds, metadataTitle, urlPart, audioDevices);
        }

        /** Each entry is {@code id=description}. */
        public Options withAudioDevices(String... devices) {
            return new Options(durationSeconds, metadataTitle, failUrlsContaining, List.of(devices));
        }

        static Options fromEnvironment(Map<String, String> env) {
            String duration = env.getOrDefault("FAKE_MPV_DURATION", "187");
            String devices = env.getOrDefault("FAKE_MPV_DEVICES", "");
            return new Options("none".equals(duration) ? 0 : Double.parseDouble(duration), env.get("FAKE_MPV_TITLE"),
                    env.get("FAKE_MPV_FAIL_URLS_CONTAINING"), devices.isBlank() ? List.of() : List.of(devices.split(";")));
        }
    }

    public static final String VERSION = "mpv v0.41.0-fake Copyright © 2000-2025 mpv/MPlayer/mplayer2 projects";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ServerSocketChannel server;
    private final Path socket;
    private final Options options;
    private final Consumer<String> log;
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final List<SocketChannel> clients = new CopyOnWriteArrayList<>();
    private final CountDownLatch quit = new CountDownLatch(1);

    // playback state, guarded by this
    private String path;
    private boolean paused;
    private double positionAtMark;
    private long markNanos = System.nanoTime();
    private double volume;
    private boolean muted;
    private boolean everLoaded;
    private boolean closing;

    private FakeMpv(ServerSocketChannel server, Path socket, Options options, double volume, Consumer<String> log) {
        this.server = server;
        this.socket = socket;
        this.options = options;
        this.volume = volume;
        this.log = log;
    }

    public static FakeMpv serve(Path socket, Options options, double initialVolume, Consumer<String> log) throws IOException {
        Files.deleteIfExists(socket);
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        FakeMpv fake = new FakeMpv(server, socket, options, initialVolume, log);
        Thread.ofVirtual().name("fake-mpv-accept").start(fake::acceptLoop);
        Thread.ofVirtual().name("fake-mpv-clock").start(fake::clockLoop);
        return fake;
    }

    public static String audioDeviceHelp(List<String> devices) {
        StringBuilder help = new StringBuilder("List of detected audio devices:\n  'auto' (Autoselect device)\n");
        for (String device : devices) {
            String[] parts = device.split("=", 2);
            help.append("  '").append(parts[0]).append("' (").append(parts.length > 1 ? parts[1] : parts[0]).append(")\n");
        }
        return help.toString();
    }

    public List<List<String>> commands() {
        return List.copyOf(commands);
    }

    public boolean hasQuit() {
        return quit.getCount() == 0;
    }

    public void awaitQuit() throws InterruptedException {
        quit.await();
    }

    public synchronized double volume() {
        return volume;
    }

    public synchronized boolean muted() {
        return muted;
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized String path() {
        return path;
    }

    /** Ends the current file as if the stream finished. */
    public void finishTrack() {
        List<ObjectNode> events = new ArrayList<>();
        boolean quitAfter;
        synchronized (this) {
            quitAfter = end("eof", events);
        }
        events.forEach(this::broadcast);
        if (quitAfter) {
            close();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closing) {
                return;
            }
            closing = true;
        }
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (SocketChannel client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
        }
        log.accept("{\"type\":\"exit\"}");
        quit.countDown();
    }

    private void acceptLoop() {
        try {
            while (!hasQuit()) {
                SocketChannel client = server.accept();
                clients.add(client);
                Thread.ofVirtual().name("fake-mpv-client").start(() -> serveClient(client));
            }
        } catch (IOException closed) {
            // server closed
        }
    }

    private void serveClient(SocketChannel client) {
        try {
            MpvIpc.readLines(client, line -> handle(client, line));
        } catch (IOException closed) {
            // client gone
        }
        clients.remove(client);
    }

    private void clockLoop() {
        while (!hasQuit()) {
            boolean finished;
            synchronized (this) {
                finished = path != null && options.durationSeconds() > 0 && position() >= options.durationSeconds();
            }
            if (finished) {
                finishTrack();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handle(SocketChannel client, String line) {
        JsonNode request;
        try {
            request = JSON.readTree(line);
        } catch (JacksonException e) {
            return;
        }
        List<String> command = new ArrayList<>();
        request.path("command").forEach(argument -> command.add(argument.isString() ? argument.asString("") : argument.toString()));
        commands.add(List.copyOf(command));
        log.accept(JSON.writeValueAsString(Map.of("type", "command", "command", command)));
        ObjectNode response = JSON.createObjectNode();
        List<ObjectNode> events = new ArrayList<>();
        boolean quitAfter = false;
        synchronized (this) {
            switch (command.isEmpty() ? "" : command.getFirst()) {
                case "get_property" -> getProperty(command.size() > 1 ? command.get(1) : "", response);
                case "set_property" -> setProperty(command.size() > 1 ? command.get(1) : "", request.path("command").path(2), response);
                case "loadfile" -> {
                    response.put("error", "success");
                    quitAfter = load(command.size() > 1 ? command.get(1) : "", events);
                }
                case "stop" -> {
                    response.put("error", "success");
                    quitAfter = end("stop", events);
                }
                case "quit" -> {
                    response.put("error", "success");
                    quitAfter = true;
                }
                default -> response.put("error", "invalid parameter");
            }
        }
        response.put("request_id", request.path("request_id").asLong(0));
        send(client, response);
        events.forEach(this::broadcast);
        if (quitAfter) {
            close();
        }
    }

    private void getProperty(String name, ObjectNode response) {
        boolean loaded = path != null;
        switch (name) {
            case "pause" -> response.put("data", paused);
            case "volume" -> response.put("data", volume);
            case "mute" -> response.put("data", muted);
            case "idle-active" -> response.put("data", !loaded);
            case "paused-for-cache" -> {
                if (!loaded) {
                    response.put("error", "property unavailable");
                    return;
                }
                response.put("data", false);
            }
            case "time-pos" -> {
                if (!loaded) {
                    response.put("error", "property unavailable");
                    return;
                }
                response.put("data", position());
            }
            case "duration" -> {
                if (!loaded || options.durationSeconds() <= 0) {
                    response.put("error", "property unavailable");
                    return;
                }
                response.put("data", options.durationSeconds());
            }
            case "metadata" -> {
                if (!loaded) {
                    response.put("error", "property unavailable");
                    return;
                }
                ObjectNode metadata = response.putObject("data");
                if (options.metadataTitle() != null) {
                    metadata.put("title", options.metadataTitle());
                }
            }
            default -> {
                response.put("error", "property not found");
                return;
            }
        }
        response.put("error", "success");
    }

    private void setProperty(String name, JsonNode value, ObjectNode response) {
        switch (name) {
            case "pause" -> {
                if (!value.isBoolean()) {
                    response.put("error", "invalid parameter");
                    return;
                }
                positionAtMark = position();
                markNanos = System.nanoTime();
                paused = value.asBoolean(false);
            }
            case "volume" -> {
                if (!value.isNumber() || value.asDouble(-1) < 0 || value.asDouble(-1) > 100) {
                    response.put("error", "invalid parameter");
                    return;
                }
                volume = value.asDouble(0);
            }
            case "mute" -> {
                if (!value.isBoolean()) {
                    response.put("error", "invalid parameter");
                    return;
                }
                muted = value.asBoolean(false);
            }
            default -> {
                response.put("error", "property not found");
                return;
            }
        }
        response.put("error", "success");
    }

    /** Returns whether the player quits (--idle=once: a failed first file ends the playlist). */
    private boolean load(String url, List<ObjectNode> events) {
        events.add(event("start-file"));
        if (options.failUrlsContaining() != null && url.contains(options.failUrlsContaining())) {
            ObjectNode end = event("end-file");
            end.put("reason", "error");
            end.put("file_error", "loading failed");
            events.add(end);
            path = null;
            return true;
        }
        path = url;
        paused = false;
        positionAtMark = 0;
        markNanos = System.nanoTime();
        everLoaded = true;
        events.add(event("file-loaded"));
        events.add(event("playback-restart"));
        return false;
    }

    private boolean end(String reason, List<ObjectNode> events) {
        if (path != null) {
            ObjectNode end = event("end-file");
            end.put("reason", reason);
            events.add(end);
        }
        path = null;
        return everLoaded;
    }

    private double position() {
        if (path == null) {
            return 0;
        }
        double position = paused ? positionAtMark : positionAtMark + (System.nanoTime() - markNanos) / 1e9;
        return options.durationSeconds() > 0 ? Math.min(position, options.durationSeconds()) : position;
    }

    private static ObjectNode event(String name) {
        ObjectNode event = JSON.createObjectNode();
        event.put("event", name);
        event.put("playlist_entry_id", 1);
        return event;
    }

    private void broadcast(ObjectNode event) {
        clients.forEach(client -> send(client, event));
    }

    private static void send(SocketChannel client, ObjectNode message) {
        byte[] bytes = (JSON.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (client) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    client.write(buffer);
                }
            } catch (IOException gone) {
                // client closed
            }
        }
    }

    /** Fake mpv executable. Environment: FAKE_MPV_LOG, FAKE_MPV_DEVICES, FAKE_MPV_DURATION, FAKE_MPV_TITLE,
     *  FAKE_MPV_FAIL_URLS_CONTAINING, FAKE_MPV_START_DELAY_MS, FAKE_MPV_EXIT_AT_START ("code:stderr text"), FAKE_MPV_IGNORE_TERM=1. */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        List<String> argv = List.of(args);
        Path logFile = env.containsKey("FAKE_MPV_LOG") ? Path.of(env.get("FAKE_MPV_LOG")) : null;
        Consumer<String> log = line -> appendLine(logFile, line);
        if (argv.contains("--version")) {
            System.out.println(VERSION);
            return;
        }
        Options options = Options.fromEnvironment(env);
        if (argv.contains("--audio-device=help")) {
            System.out.print(audioDeviceHelp(options.audioDevices()));
            return;
        }
        log.accept(JSON.writeValueAsString(Map.of("type", "start", "pid", ProcessHandle.current().pid(), "args", argv)));
        String exitAtStart = env.get("FAKE_MPV_EXIT_AT_START");
        if (exitAtStart != null) {
            String[] parts = exitAtStart.split(":", 2);
            System.err.println(parts.length > 1 ? parts[1] : "fake failure");
            System.exit(Integer.parseInt(parts[0]));
        }
        if ("1".equals(env.get("FAKE_MPV_IGNORE_TERM"))) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException ignored) {
                }
            }));
        }
        Thread.sleep(Long.parseLong(env.getOrDefault("FAKE_MPV_START_DELAY_MS", "0")));
        Path socket = Path.of(value(argv, "--input-ipc-server="));
        String volume = value(argv, "--volume=");
        try (FakeMpv fake = serve(socket, options, volume == null ? 100 : Double.parseDouble(volume), log)) {
            fake.awaitQuit();
        }
        System.exit(0);
    }

    private static String value(List<String> argv, String prefix) {
        return argv.stream().filter(argument -> argument.startsWith(prefix)).map(argument -> argument.substring(prefix.length()))
                .findFirst().orElse(null);
    }

    private static void appendLine(Path file, String line) {
        if (file == null) {
            return;
        }
        synchronized (FakeMpv.class) {
            try {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/player/FakeMpvScript.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

/** Writes an executable "mpv" that runs FakeMpv in a child JVM with the given environment. */
public final class FakeMpvScript {

    private FakeMpvScript() {
    }

    public static Path create(Path directory, Map<String, String> environment) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("home-control.test.runtime-classpath", System.getProperty("java.class.path"));
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        environment.forEach((name, value) -> script.append("export ").append(name).append('=').append(quote(value)).append('\n'));
        script.append("exec ").append(quote(java)).append(" -XX:TieredStopAtLevel=1 -cp ").append(quote(classpath))
                .append(' ').append(FakeMpv.class.getName()).append(" \"$@\"\n");
        Files.createDirectories(directory);
        Path file = directory.resolve("mpv");
        Files.writeString(file, script.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /** The JSON lines FakeMpv wrote to FAKE_MPV_LOG. */
    public static List<JsonNode> log(Path logFile) throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        return Files.exists(logFile)
                ? Files.readAllLines(logFile).stream().filter(line -> !line.isBlank()).map(json::readTree).toList()
                : List.of();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
```

`src/test/java/dev/andre/homecontrol/adapters/bluetooth/player/InProcessMpvLauncher.java` — `public final class InProcessMpvLauncher implements MpvLauncher`:
- public mutable fields: `volatile FakeMpv.Options options = FakeMpv.Options.defaults()`, `volatile IOException startFailure`, `volatile Duration startDelay = Duration.ZERO`, `volatile String version = FakeMpv.VERSION`; recordings `final List<List<String>> starts`, `final List<List<String>> runs`, `final List<FakeMpv> players` (all `CopyOnWriteArrayList`).
- `start(arguments)`: throw `startFailure` when set; record; read `--input-ipc-server=` and `--volume=`; start a virtual thread that sleeps `startDelay`, returns at once when the process was terminated meanwhile, else `FakeMpv.serve(socket, options, volume, line -> {})`, adds it to `players`, `awaitQuit()`, then completes the exit future with `0` (an exception while serving completes it with `1`). Returns an `MpvProcess` with an increasing fake `pid` (from 1000), `alive()` = exit future not done, `onExit()` = the future, `recentErrors()` = `""`, `terminate(grace)` = mark terminated, close the fake if it exists (else complete the future with `143`), then wait ≤ 2 s for the future.
- `run(arguments, timeout)`: throw `startFailure` when set; record; `--version` → `version + "\n"`; `--audio-device=help` → `FakeMpv.audioDeviceHelp(options.audioDevices())`; else `""`.
- helpers `FakeMpv latest()` (last player, or null), `long alive()` (count of started processes whose exit future is not done), `close()` closes every player.

- [ ] **Step 7: Write the failing IPC, player and launcher tests**

`adapters/bluetooth/player/MpvIpcTest.java` — `@TempDir Path dir`; a `FakeMpv.serve(dir.resolve("s.sock"), FakeMpv.Options.defaults().withMetadataTitle("Meta Song"), 50, line -> {})` per test (closed in `@AfterEach`); events collected into a `CopyOnWriteArrayList<JsonNode>`:
- `answersRequestsById`: `connect(socket, 1s, () -> true, listener)`; `command(1s, "get_property", "volume").asDouble(-1)` = 50.0; `command(1s, "set_property", "volume", 30)` then `fake.volume()` = 30.0; `command(1s, "get_property", "idle-active").asBoolean(false)` true.
- `concurrentRequestsGetTheirOwnAnswers`: 20 virtual threads alternating `get_property volume` and `get_property pause` → each result has the right type (number vs boolean).
- `refusalsAreMpvExceptions`: `get_property time-pos` while idle → `MpvException` with `error()` `property unavailable` and message `mpv refused get_property: property unavailable`; `get_property nope` → `property not found`.
- `eventsReachTheListener`: `command(1s, "loadfile", "http://nas/a.mp3", "replace")` → eventually the events contain `start-file` and `file-loaded` in that order.
- `aSilentServerTimesOut`: a raw `ServerSocketChannel` (UNIX) at `dir/silent.sock` that accepts and never writes → `command(Duration.ofMillis(300), "get_property", "volume")` throws `IOException` with message `mpv did not answer get_property within 300 ms`.
- `waitsForTheSocketToAppear`: start the fake 300 ms after calling `connect(socket, 2s, () -> true, listener)` on another thread → connect succeeds.
- `givesUpWhenTheProcessDied`: no socket, `processAlive` false → `IOException` `mpv exited before opening its control socket` within 200 ms; no socket, alive true, timeout 300 ms → `IOException` containing `did not open its control socket`.
- `aClosedSocketFailsPendingRequestsAndReportsClosed`: raw silent server; a `command` pending on another thread; close the server's accepted channel → the pending call throws `IOException` quickly; `listener.onClosed()` called once; `open()` false; later `command` throws `IOException` `mpv control socket is closed`.
- `neverSendsGarbageForUnknownArgumentTypes`: `command(1s, "set_property", "volume", new Object())` → `IllegalArgumentException`.

`adapters/bluetooth/player/AudioDeviceResolverTest.java` — as specified in Step 4.

`adapters/bluetooth/player/MpvPlayerTest.java` — `InProcessMpvLauncher launcher`, `MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(dir, "bluetooth-aa-bb-cc-dd-ee-ff"), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1))`:
- `socketPathsAreShortAndStable`: `socketFor(Path.of("/tmp/x"), "bluetooth-aa-bb-cc-dd-ee-ff")` = `/tmp/x/mpv-<12 hex chars>.sock`, equal for equal ids, different for different ids.
- `playsAfterTheFileLoaded`: `play(URI("http://nas/a.mp3?ApiKey=secret"), "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", 40, false)` returns; `active()` true; `launcher.starts` has one entry equal to `MpvCommandLine.arguments(<socket>, "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1", 40)` and no argument contains `nas`; `launcher.latest().commands()` contains `[loadfile, http://nas/a.mp3?ApiKey=secret, replace]`; the runtime directory has POSIX permissions `rwx------`.
- `mutesBeforeLoadingWhenAsked`: `play(…, 40, true)` → the first command is `[set_property, mute, true]`, then the `loadfile`.
- `reportsStatus`: after play, `status()` present with `paused` false, `buffering` false, `positionSeconds` ≥ 0, `durationSeconds` 187.0, `volume` 40, `muted` false, `metadataTitle` null; with options `withMetadataTitle("Meta Song").withDuration(0)` → title `Meta Song`, duration null.
- `pausesResumesAndSetsVolume`: `pause(true)` → `latest().paused()` true and `status().paused()` true; `pause(false)`; `volume(25)` → `latest().volume()` 25.0; `mute(true)` → `latest().muted()` true.
- `aLoadFailureStopsThePlayer`: options `failingFor("broken")` → `play(URI("http://nas/broken.mp3?ApiKey=secret"), …)` throws `MpvException` with message `the stream could not be loaded (loading failed)`; `active()` false; `launcher.alive()` 0.
- `aSlowStartTimesOut`: `launcher.startDelay = Duration.ofSeconds(5)`, start timeout 500 ms → `IOException` containing `did not open its control socket`; `launcher.alive()` eventually 0.
- `aNewPlayReplacesTheOldProcess`: two plays → `launcher.starts` size 2, `launcher.alive()` 1, the first fake `hasQuit()`.
- `stopEndsEverything`: `stop()` → `active()` false, `status()` empty, `launcher.alive()` 0, the socket file is gone; `stop()` again does not throw; `pause(true)` → `IOException` `nothing is playing`.
- `aTrackThatEndsLeavesNoPlayer`: after play `latest().finishTrack()` → eventually `status()` empty and `active()` false.

`adapters/bluetooth/player/ProcessMpvLauncherTest.java` — real subprocesses through `FakeMpvScript.create(dir.resolve("bin"), env)` with `FAKE_MPV_LOG` = `dir/log.jsonl`; every launcher closed in `@AfterEach`; Awaitility ≤ 20 s (JVM start):
- `startsControlsAndQuits`: `MpvProcess process = launcher.start(MpvCommandLine.arguments(socket, "pulse/x", 30))`; `MpvIpc ipc = MpvIpc.connect(socket, Duration.ofSeconds(15), process::alive, listener)`; `ipc.command(2s, "get_property", "volume").asDouble(-1)` = 30.0; `ipc.command(2s, "quit")` (an `IOException` because the socket closes is acceptable); `process.onExit().get(10, SECONDS)` = 0; the log's `start` line has the arguments and `pid` = `process.pid()`.
- `terminateKillsAProcessThatIgnoresTerm`: env `FAKE_MPV_IGNORE_TERM=1`; after the socket exists `process.terminate(Duration.ofMillis(500))` → `alive()` false right after it returns.
- `aMissingBinaryIsNotInstalled`: `new ProcessMpvLauncher(dir.resolve("nope/mpv").toString())` → `start(List.of())` and `run(List.of("--version"), 1s)` throw `MpvNotInstalledException` with message `mpv was not found at "…/nope/mpv"`.
- `stderrIsKeptRedacted`: env `FAKE_MPV_EXIT_AT_START=2:Failed to open http://h/a.mp3?ApiKey=secret` → `onExit().get()` = 2; eventually `recentErrors()` contains `Failed to open http://h/a.mp3?…` and not `secret`.
- `runCapturesOutput`: `run(List.of("--no-config", "--version"), 15s)` starts with `mpv v0.41.0-fake`; env `FAKE_MPV_DEVICES=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5` → `run(List.of("--no-config", "--audio-device=help"), 15s)` contains `'pulse/bluez_output.AA_BB_CC_DD_EE_FF.1' (JBL Flip 5)`.
- `runTimesOut`: a script whose content is `#!/bin/sh\nexec sleep 30` (write it directly, mode `rwxr-xr-x`) → `run(List.of(), Duration.ofMillis(500))` throws `IOException` with message `mpv did not finish within 500 ms`; the child is gone afterwards.
- `closeTerminatesEveryProcess`: two `start` calls on two sockets → `launcher.close()` → both `alive()` false.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.player.*'`
Expected: compilation failure — `MpvIpc`, `MpvPlayer`, `ProcessMpvLauncher` do not exist.

- [ ] **Step 8: Implement IPC, launcher and player**

`adapters/bluetooth/player/MpvIpc.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** mpv's JSON IPC over its Unix socket: one JSON object per line, replies matched by request_id. */
public final class MpvIpc implements AutoCloseable {

    public interface EventListener {
        void onEvent(JsonNode event);

        void onClosed();
    }

    private static final Logger log = LoggerFactory.getLogger(MpvIpc.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_LINE_BYTES = 1 << 20;

    private final SocketChannel channel;
    private final EventListener listener;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    private MpvIpc(SocketChannel channel, EventListener listener) {
        this.channel = channel;
        this.listener = listener;
        Thread.ofVirtual().name("mpv-ipc").start(this::readLoop);
    }

    /** Waits until mpv created its socket (shortly after start), unless the process died first. */
    public static MpvIpc connect(Path socket, Duration timeout, BooleanSupplier processAlive, EventListener listener)
            throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            if (!processAlive.getAsBoolean()) {
                throw new IOException("mpv exited before opening its control socket");
            }
            if (Files.exists(socket)) {
                SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                try {
                    channel.connect(UnixDomainSocketAddress.of(socket));
                    return new MpvIpc(channel, listener);
                } catch (IOException e) {
                    channel.close();
                    last = e;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for mpv");
            }
        }
        if (!processAlive.getAsBoolean()) {
            throw new IOException("mpv exited before opening its control socket");
        }
        throw new IOException("mpv did not open its control socket within " + timeout.toMillis() + " ms", last);
    }

    public JsonNode command(Duration timeout, Object... command) throws IOException, MpvException {
        ObjectNode request = JSON.createObjectNode();
        ArrayNode arguments = request.putArray("command");
        for (Object argument : command) {
            switch (argument) {
                case String text -> arguments.add(text);
                case Integer number -> arguments.add(number);
                case Long number -> arguments.add(number);
                case Double number -> arguments.add(number);
                case Boolean flag -> arguments.add(flag);
                default -> throw new IllegalArgumentException("Unsupported mpv argument type " + argument.getClass().getSimpleName());
            }
        }
        String name = String.valueOf(command[0]);
        long id = nextId.getAndIncrement();
        request.put("request_id", id);
        CompletableFuture<JsonNode> answer = new CompletableFuture<>();
        pending.put(id, answer);
        try {
            if (closed.get()) {
                throw new IOException("mpv control socket is closed");
            }
            ByteBuffer line = ByteBuffer.wrap((JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8));
            synchronized (writeLock) {
                while (line.hasRemaining()) {
                    channel.write(line);
                }
            }
            JsonNode response = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String error = response.path("error").asString("success");
            if (!"success".equals(error)) {
                throw MpvException.refused(name, error);
            }
            return response.path("data");
        } catch (TimeoutException e) {
            throw new IOException("mpv did not answer " + name + " within " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            throw new IOException("mpv control socket closed while waiting for " + name, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for mpv");
        } finally {
            pending.remove(id);
        }
    }

    public boolean open() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            IOException gone = new IOException("mpv control socket is closed");
            pending.values().forEach(waiter -> waiter.completeExceptionally(gone));
            try {
                listener.onClosed();
            } catch (RuntimeException e) {
                log.debug("mpv close listener failed", e);
            }
        }
    }

    /** Splits a channel into UTF-8 lines until end of stream. Shared with the test fake. */
    static void readLines(SocketChannel channel, Consumer<String> lines) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                byte next = buffer.get();
                if (next == '\n') {
                    lines.accept(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                } else if (line.size() >= MAX_LINE_BYTES) {
                    throw new IOException("mpv sent a line longer than 1 MiB");
                } else {
                    line.write(next);
                }
            }
            buffer.clear();
        }
    }

    private void readLoop() {
        try {
            readLines(channel, this::dispatch);
        } catch (IOException e) {
            log.debug("mpv control socket ended: {}", e.toString());
        } finally {
            close();
        }
    }

    private void dispatch(String text) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JacksonException e) {
            return;
        }
        if (message.has("event")) {
            try {
                listener.onEvent(message);
            } catch (RuntimeException e) {
                log.debug("mpv event listener failed", e);
            }
            return;
        }
        JsonNode id = message.path("request_id");
        if (id.isNumber()) {
            CompletableFuture<JsonNode> waiter = pending.get(id.asLong(-1));
            if (waiter != null) {
                waiter.complete(message);
            }
        }
    }
}
```

(`aSilentServerTimesOut` expects `within 300 ms` — the message above prints milliseconds.)

`adapters/bluetooth/player/ProcessMpvLauncher.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Starts real mpv processes and ends them all on close. */
public final class ProcessMpvLauncher implements MpvLauncher {

    private static final int ERROR_LINES = 20;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final String mpvPath;
    private final Set<LocalProcess> live = ConcurrentHashMap.newKeySet();

    public ProcessMpvLauncher(String mpvPath) {
        this.mpvPath = mpvPath;
    }

    @Override
    public MpvProcess start(List<String> arguments) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command(arguments)).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new MpvNotInstalledException(mpvPath, e);
        }
        LocalProcess started = new LocalProcess(process);
        live.add(started);
        process.onExit().thenRun(() -> live.remove(started));
        return started;
    }

    @Override
    public String run(List<String> arguments, Duration timeout) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(command(arguments)).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new MpvNotInstalledException(mpvPath, e);
        }
        process.getOutputStream().close();
        CompletableFuture<String> output = new CompletableFuture<>();
        Thread.ofVirtual().name("mpv-run").start(() -> {
            try (InputStream in = process.getInputStream()) {
                String text = new String(in.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
                in.transferTo(OutputStream.nullOutputStream());
                output.complete(text);
            } catch (IOException e) {
                output.completeExceptionally(e);
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("mpv did not finish within " + timeout.toMillis() + " ms");
            }
            return StreamRedaction.redact(output.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while running mpv");
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("could not read mpv's output", e);
        }
    }

    @Override
    public void close() {
        new ArrayList<>(live).forEach(process -> process.terminate(Duration.ofSeconds(1)));
    }

    private List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(mpvPath);
        command.addAll(arguments);
        return command;
    }

    private static final class LocalProcess implements MpvProcess {

        private final Process process;
        private final Deque<String> errors = new ArrayDeque<>();

        LocalProcess(Process process) {
            this.process = process;
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            Thread.ofVirtual().name("mpv-stderr-" + process.pid()).start(this::drainErrors);
        }

        private void drainErrors() {
            try (BufferedReader reader = process.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String safe = StreamRedaction.redact(line);
                    synchronized (errors) {
                        errors.addLast(safe.length() > 300 ? safe.substring(0, 300) : safe);
                        while (errors.size() > ERROR_LINES) {
                            errors.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public CompletableFuture<Integer> onExit() {
            return process.onExit().thenApply(Process::exitValue);
        }

        @Override
        public String recentErrors() {
            synchronized (errors) {
                return String.join(" | ", errors);
            }
        }

        @Override
        public void terminate(Duration grace) {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

`adapters/bluetooth/player/PlayerStatus.java` — the record under Produces.

`adapters/bluetooth/player/MpvPlayer.java`:

```java
package dev.andre.homecontrol.adapters.bluetooth.player;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One speaker's player: at most one mpv process, started per play, the URL sent over IPC. */
public final class MpvPlayer implements AutoCloseable {

    private record Running(MpvProcess process, MpvIpc ipc) {
        boolean alive() {
            return process.alive() && ipc.open();
        }
    }

    private final MpvLauncher launcher;
    private final Path socket;
    private final Duration startTimeout;
    private final Duration loadTimeout;
    private final Duration commandTimeout;
    private volatile Running running;

    public MpvPlayer(MpvLauncher launcher, Path socket, Duration startTimeout, Duration loadTimeout, Duration commandTimeout) {
        this.launcher = launcher;
        this.socket = socket;
        this.startTimeout = startTimeout;
        this.loadTimeout = loadTimeout;
        this.commandTimeout = commandTimeout;
    }

    /** Unix socket paths are limited to 108 bytes; device ids are free text. */
    public static Path socketFor(Path runtimeDir, String deviceId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8));
            return runtimeDir.resolve("mpv-" + HexFormat.of().formatHex(digest).substring(0, 12) + ".sock");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized void play(URI url, String audioDevice, int volume, boolean muted) throws IOException, MpvException {
        stop();
        Files.createDirectories(socket.getParent());
        try {
            Files.setPosixFilePermissions(socket.getParent(), PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // best effort
        }
        Files.deleteIfExists(socket);
        MpvProcess process = launcher.start(MpvCommandLine.arguments(socket, audioDevice, volume));
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        MpvIpc ipc;
        try {
            ipc = MpvIpc.connect(socket, startTimeout, process::alive, new MpvIpc.EventListener() {
                @Override
                public void onEvent(JsonNode event) {
                    switch (event.path("event").asString("")) {
                        case "file-loaded" -> loaded.complete(null);
                        case "end-file" -> loaded.completeExceptionally(MpvException.loadFailed(
                                event.path("file_error").asString(event.path("reason").asString("stopped"))));
                        default -> {
                        }
                    }
                }

                @Override
                public void onClosed() {
                    loaded.completeExceptionally(new IOException("mpv closed its control socket"));
                }
            });
        } catch (IOException e) {
            process.terminate(Duration.ofSeconds(1));
            throw withErrors(e, process);
        }
        running = new Running(process, ipc);
        process.onExit().thenRun(() -> loaded.completeExceptionally(new IOException("mpv exited")));
        try {
            if (muted) {
                ipc.command(commandTimeout, "set_property", "mute", true);
            }
            ipc.command(commandTimeout, "loadfile", url.toString(), "replace");
            loaded.get(loadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            stop();
            throw new IOException("the stream did not start within " + loadTimeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            stop();
            if (e.getCause() instanceof MpvException refused) {
                throw refused;
            }
            throw withErrors(e.getCause() instanceof IOException io ? io : new IOException(e.getCause()), process);
        } catch (IOException | MpvException e) {
            stop();
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new InterruptedIOException("interrupted while starting playback");
        }
    }

    public boolean active() {
        Running current = running;
        return current != null && current.alive();
    }

    public void pause(boolean paused) throws IOException, MpvException {
        require().ipc().command(commandTimeout, "set_property", "pause", paused);
    }

    public void volume(int percent) throws IOException, MpvException {
        require().ipc().command(commandTimeout, "set_property", "volume", Math.clamp(percent, 0, 100));
    }

    public void mute(boolean muted) throws IOException, MpvException {
        require().ipc().command(commandTimeout, "set_property", "mute", muted);
    }

    /** Empty when nothing is loaded; a dead player is cleaned up. */
    public Optional<PlayerStatus> status() {
        Running current = running;
        if (current == null) {
            return Optional.empty();
        }
        if (!current.alive()) {
            stop();
            return Optional.empty();
        }
        try {
            if (flag(current, "idle-active")) {
                return Optional.empty();
            }
            return Optional.of(new PlayerStatus(flag(current, "pause"), flag(current, "paused-for-cache"),
                    number(current, "time-pos").orElse(0.0),
                    number(current, "duration").filter(duration -> duration > 0).orElse(null),
                    metadataTitle(current),
                    (int) Math.round(number(current, "volume").orElse(0.0)),
                    flag(current, "mute")));
        } catch (IOException e) {
            stop();
            return Optional.empty();
        }
    }

    public synchronized void stop() {
        Running current = running;
        running = null;
        if (current == null) {
            return;
        }
        try {
            current.ipc().command(commandTimeout, "quit");
        } catch (IOException | MpvException ignored) {
            // already gone, or it closed the socket while quitting
        }
        current.ipc().close();
        current.process().terminate(Duration.ofSeconds(2));
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        stop();
    }

    private Running require() throws IOException {
        Running current = running;
        if (current == null || !current.alive()) {
            throw new IOException("nothing is playing");
        }
        return current;
    }

    private boolean flag(Running current, String property) throws IOException {
        try {
            return current.ipc().command(commandTimeout, "get_property", property).asBoolean(false);
        } catch (MpvException unavailable) {
            return false;
        }
    }

    private Optional<Double> number(Running current, String property) throws IOException {
        try {
            JsonNode value = current.ipc().command(commandTimeout, "get_property", property);
            return value.isNumber() ? Optional.of(value.asDouble(0)) : Optional.empty();
        } catch (MpvException unavailable) {
            return Optional.empty();
        }
    }

    private String metadataTitle(Running current) throws IOException {
        try {
            JsonNode metadata = current.ipc().command(commandTimeout, "get_property", "metadata");
            String icy = null;
            for (Map.Entry<String, JsonNode> entry : metadata.properties()) {
                String value = entry.getValue().asString("").strip();
                if (value.isEmpty()) {
                    continue;
                }
                if (entry.getKey().equalsIgnoreCase("title")) {
                    return value;
                }
                if (entry.getKey().equalsIgnoreCase("icy-title")) {
                    icy = value;
                }
            }
            return icy;
        } catch (MpvException unavailable) {
            return null;
        }
    }

    private static IOException withErrors(IOException e, MpvProcess process) {
        String errors = process.recentErrors();
        return errors == null || errors.isBlank() ? e
                : new IOException(StreamRedaction.redact(e.getMessage()) + " (mpv: " + errors + ")", e);
    }
}
```

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.player.*'`
Expected: PASS.

- [ ] **Step 9: Write the failing session and host check tests**

`BluetoothSpeakerSessionTest` — rebuild the fixture: `InProcessMpvLauncher launcher` with `options = FakeMpv.Options.defaults().withAudioDevices("pulse/alsa_output.platform-bcm2835_audio.stereo-fallback=Built-in Audio", "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5")`; `@TempDir runtime`; the session is `new BluetoothSpeakerSession(device, properties, bluez, new MpvPlayer(launcher, MpvPlayer.socketFor(runtime, device.id()), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1)), new AudioDeviceResolver(launcher, properties.audioDeviceTemplate(), Duration.ofSeconds(2)), states::add)`; `PLAY = new Action.PlayMedia(URI.create("http://127.0.0.1:9/music/song.mp3?ApiKey=secret-key"), "audio/mpeg", "Bunny Song", "The Rabbits")`. Replace `playbackIsNotAvailableYet` with:
- `playsOnTheSpeakersOwnOutput`: after `CONNECTED`, `execute(PLAY)` → `launcher.starts` has one entry containing `--audio-device=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1` and `--volume=50`, and no entry argument contains `127.0.0.1`; `launcher.latest().commands()` contains `[loadfile, http://127.0.0.1:9/music/song.mp3?ApiKey=secret-key, replace]`; eventually `state().nowPlaying()` has title `Bunny Song`, state `PLAYING`, `durationSeconds` 187.0.
- `connectsADisconnectedSpeakerBeforePlaying`: properties `.withAutoConnect(false)`, `connected(false)` → `execute(PLAY)` succeeds; `bluez.calls()` contains `connect AA:BB:CC:DD:EE:FF`.
- `aSpeakerThatCannotConnectIsOffline`: `.withAutoConnect(false)`, `connected(false)`, `failAlways("connect", UNREACHABLE, "br-connection-page-timeout")` → `DeviceOfflineException` whose message starts with `JBL Flip 5 is not connected: The speaker did not answer`; `launcher.starts` empty.
- `anUnpairedSpeakerIsOffline`: device removed from the fake → `DeviceOfflineException` containing `Pair it again on the setup page`.
- `refusesVideoAndNonHttpStreams`: `PlayMedia(URI("http://nas/f.mp4"), "video/mp4", …)` → `UnsupportedActionException` `JBL Flip 5 plays audio only`; `PlayMedia(URI("file:///etc/passwd"), "audio/mpeg", …)` → `JBL Flip 5 plays http and https streams only`; nothing started.
- `pauseResumeVolumeMuteAndStop`: after `execute(PLAY)`: `execute(new Action.Pause())` → eventually `nowPlaying().state()` `PAUSED` and `latest().paused()` true; `Resume` → `PLAYING`; `SetVolume(30)` → `latest().volume()` 30.0 and eventually `state().volumeLevel()` 30; `Mute(true)` → `latest().muted()` true and eventually `state().muted()` true; `Stop` → `launcher.alive()` 0 and eventually `nowPlaying()` null; a second `Stop` does not throw.
- `volumeIsRememberedForTheNextPlay`: without playing, `execute(new Action.SetVolume(70))` and `Mute(true)` → eventually `state().volumeLevel()` 70; then `execute(PLAY)` → start arguments contain `--volume=70` and the first IPC command is `[set_property, mute, true]`.
- `pauseWithNothingPlayingFails`: `execute(new Action.Pause())` → `ActionFailedException` `Nothing is playing on JBL Flip 5`.
- `aNewPlayReplacesThePlayer`: two plays → `launcher.alive()` 1.
- `aStreamThatFailsIsReported`: `options.failingFor("broken")`; `PlayMedia(URI("http://127.0.0.1:9/broken.mp3?ApiKey=secret-key"), "audio/mpeg", "X", null)` → `ActionFailedException` with message `JBL Flip 5 could not play the stream: the stream could not be loaded (loading failed)`; `launcher.alive()` 0.
- `mpvMissingIsExplained`: `launcher.startFailure = new MpvNotInstalledException("mpv", new IOException("error=2"))` → `ActionFailedException` whose message equals `BluetoothSpeakerSession.MPV_MISSING` (contains `latest-bluetooth` and `WITH_MPV=true`).
- `noAudioOutputIsExplained`: options without the bluez device → `ActionFailedException` whose message starts with `JBL Flip 5: No audio output for AA:BB:CC:DD:EE:FF was found`.
- `aManualAudioDeviceWins`: device settings `audioDevice` `alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp` → start arguments contain `--audio-device=alsa/bluealsa:DEV=AA:BB:CC:DD:EE:FF,PROFILE=a2dp`; `launcher.runs` empty.
- `stopsPlaybackWhenTheSpeakerDisconnects`: playing; `bluez.device(ADDRESS).connected(false)` (autoConnect already used) → within 3 s `launcher.alive()` 0, `nowPlaying()` null, status `DISCONNECTED`.
- `aTrackThatEndsClearsNowPlaying`: playing; `launcher.latest().finishTrack()` → eventually `nowPlaying()` null and `launcher.alive()` 0.
- `titlesFallBackToMetadataNeverToTheUrl`: `PlayMedia(URI("http://127.0.0.1:9/stream?ApiKey=secret-key"), "audio/mpeg", null, null)` with options `withMetadataTitle("Radio Bunny")` → title `Radio Bunny`; without metadata → `Unknown title`.
- `remoteKeysAreUnsupported`: `PressKey(HOME)`, `OpenAppLink(URI("https://youtube.com/watch?v=x"))` → `UnsupportedActionException`.
- `closeStopsThePlayer`: playing → `close()` → `launcher.alive()` 0.
- `neverLeaksTheStreamQuery`: after the tests above that play, no published state's `toString()`, no exception message and no start argument contains `secret-key` (collect exceptions in the tests that expect them and assert in each).

`BluetoothSpeakerAdapterTest` — construct with `(properties, bluez, launcher)`; `connectStartsASession` unchanged.

`BluetoothHostChecksTest` — construct with `(properties, bluez, launcher, clock, name -> env.get(name))` where `env` is a test `Map`; `launcher.options` with the two pulse devices from the session test:
- `allGood` ids now `[dbus-socket, bluez, adapter, mpv, audio-output]`; `mpv` detail `mpv v0.41.0-fake`; `audio-output` detail `PipeWire or PulseAudio reachable (2 outputs)`.
- `mpvMissing`: `launcher.startFailure = new MpvNotInstalledException("mpv", …)` → `mpv` not ok with `BluetoothSpeakerSession.MPV_MISSING`; `audio-output` not ok `Needs mpv first`.
- `noSoundServer`: options without devices → `audio-output` not ok containing `No PipeWire or PulseAudio server is reachable` and `PULSE_SERVER=unix:/run/pulse/native`; with `env` `PULSE_SERVER=unix:/run/pulse/native` the detail also contains `PULSE_SERVER is unix:/run/pulse/native, but nothing answers there.`
- `aTemplateSkipsTheServerCheck`: `.withAudioDeviceTemplate("alsa/bluealsa:DEV={mac},PROFILE=a2dp")` → `audio-output` ok `Using the template alsa/bluealsa:DEV={mac},PROFILE=a2dp`; `launcher.runs` has only the `--version` run.
- `resultsAreCachedUntilInvalidated`: additionally `launcher.runs` grows only on refresh.

`BluetoothModuleSwitchTest.canBeSwitchedOn` — additionally a `MpvLauncher` bean exists and is a `ProcessMpvLauncher`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: compilation failure — the new constructors do not exist.

- [ ] **Step 10: Implement playback in the session**

`BluetoothSpeakerAdapter` — constructor `(BluetoothProperties properties, BluezClient bluez, MpvLauncher launcher)`; `connect` builds

```java
        MpvPlayer player = new MpvPlayer(launcher, MpvPlayer.socketFor(properties.runtimeDir(), device.id()),
                Duration.ofSeconds(properties.playerStartTimeoutSeconds()), Duration.ofSeconds(properties.loadTimeoutSeconds()),
                Duration.ofSeconds(properties.commandTimeoutSeconds()));
        AudioDeviceResolver audioDevices = new AudioDeviceResolver(launcher, properties.audioDeviceTemplate(),
                Duration.ofSeconds(properties.playerStartTimeoutSeconds()));
        BluetoothSpeakerSession session = new BluetoothSpeakerSession(device, properties, bluez, player, audioDevices, onChange);
```

`BluetoothConfiguration` — add `@Bean(destroyMethod = "close") public MpvLauncher mpvLauncher(BluetoothProperties properties) { return new ProcessMpvLauncher(properties.mpvPath()); }` and pass it to `bluetoothSpeakerAdapter` and `bluetoothHostChecks`. (Spring destroys the `DeviceManager`, which closes every session, before the launcher it depends on indirectly; the launcher's `close` then ends any leftover process.)

`BluetoothSpeakerSession` — new constructor `(Device device, BluetoothProperties properties, BluezClient bluez, MpvPlayer player, AudioDeviceResolver audioDevices, Consumer<DeviceState> onChange)` (remove the Task 2 constructor), fields `player`, `audioDevices`, `private volatile String title;`, `private final Object commands = new Object();`, and:

```java
    static final String MPV_MISSING = "mpv is not installed in this container. Use the image tag latest-bluetooth, "
            + "or build the image with WITH_MPV=true (see docs/bluetooth-speakers.md).";

    @Override
    public void execute(Action action) {
        if (closed) {
            throw new DeviceOfflineException(device.name() + " is not connected");
        }
        synchronized (commands) {
            switch (action) {
                case Action.PlayMedia play -> play(play);
                case Action.Pause ignored -> pause(true);
                case Action.Resume ignored -> pause(false);
                case Action.Stop ignored -> {
                    player.stop();
                    title = null;
                }
                case Action.SetVolume set -> {
                    volume = set.level();
                    whilePlaying("change the volume", () -> player.volume(set.level()));
                }
                case Action.Mute mute -> {
                    muted = mute.muted();
                    whilePlaying("mute", () -> player.mute(mute.muted()));
                }
                default -> throw new UnsupportedActionException(device.name()
                        + " is a Bluetooth speaker and cannot handle " + action.getClass().getSimpleName());
            }
        }
        pollNow();
    }

    @Override
    public void close() {
        closed = true;
        loop.shutdownNow();
        player.stop();
    }

    private void play(Action.PlayMedia play) {
        String scheme = play.url().getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new UnsupportedActionException(device.name() + " plays http and https streams only");
        }
        if (!play.mimeType().toLowerCase(Locale.ROOT).startsWith("audio/")) {
            throw new UnsupportedActionException(device.name() + " plays audio only");
        }
        ensureConnected();
        try {
            String audioDevice = audioDevices.resolve(settings.address(), settings.audioDevice());
            player.play(play.url(), audioDevice, volume, muted);
            title = play.title();
        } catch (MpvNotInstalledException e) {
            throw new ActionFailedException(MPV_MISSING);
        } catch (AudioDeviceNotFoundException e) {
            throw new ActionFailedException(device.name() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ActionFailedException(device.name() + ": the audio output id is not usable; fix it on the setup page");
        } catch (IOException | MpvException e) {
            throw new ActionFailedException(device.name() + " could not play the stream: " + StreamRedaction.redact(e.getMessage()));
        }
    }

    private void pause(boolean paused) {
        if (!player.active()) {
            throw new ActionFailedException("Nothing is playing on " + device.name());
        }
        whilePlaying(paused ? "pause" : "resume", () -> player.pause(paused));
    }

    private interface PlayerCall {
        void run() throws IOException, MpvException;
    }

    private void whilePlaying(String what, PlayerCall call) {
        if (!player.active()) {
            return;
        }
        try {
            call.run();
        } catch (IOException | MpvException e) {
            throw new ActionFailedException(device.name() + " did not " + what + ": " + StreamRedaction.redact(e.getMessage()));
        }
    }

    private void ensureConnected() {
        try {
            Optional<BluetoothDeviceInfo> info = bluez.device(settings.adapter(), settings.address());
            if (info.isEmpty() || !info.get().paired()) {
                throw new DeviceOfflineException(device.name()
                        + " is not paired with this server any more. Pair it again on the setup page.");
            }
            if (!info.get().connected()) {
                bluez.connect(settings.adapter(), settings.address());
            }
        } catch (BluezException e) {
            throw new DeviceOfflineException(device.name() + " is not connected: " + e.getMessage());
        }
    }
```

`nextPollSeconds()` returns `player.active() ? properties.playingPollIntervalSeconds() : properties.pollIntervalSeconds()`. `readState()` becomes:

```java
    private void readState() {
        DeviceStatus status = bluetoothStatus();
        if (status != DeviceStatus.CONNECTED && player.active()) {
            log.info("{} is no longer connected; stopping playback so it does not move to another output", device.name());
            player.stop();
        }
        NowPlaying nowPlaying = null;
        Optional<PlayerStatus> playing = player.status();
        if (playing.isPresent()) {
            PlayerStatus now = playing.get();
            volume = now.volume();
            muted = now.muted();
            String shown = title != null && !title.isBlank() ? title
                    : now.metadataTitle() != null ? now.metadataTitle() : "Unknown title";
            PlaybackState playbackState = now.paused() ? PlaybackState.PAUSED
                    : now.buffering() ? PlaybackState.BUFFERING : PlaybackState.PLAYING;
            nowPlaying = new NowPlaying(shown, playbackState, now.positionSeconds(), now.durationSeconds());
        }
        publish(state.withStatus(status).withPower(status == DeviceStatus.CONNECTED)
                .withVolume(volume, 100, muted).withNowPlaying(nowPlaying));
    }
```

`BluetoothHostChecks` — constructors `(BluetoothProperties, BluezClient, MpvLauncher, Clock)` → `this(…, System::getenv)` and package-private `(BluetoothProperties, BluezClient, MpvLauncher, Clock, Function<String, String> environment)`; after check 3 add:
4. `mpv` / `mpv player`: `launcher.run(List.of("--no-config", "--version"), Duration.ofSeconds(5))` → ok with the first non-blank line cut before ` Copyright` (e.g. `mpv v0.41.0`); `MpvNotInstalledException` → not ok `BluetoothSpeakerSession.MPV_MISSING` (make the constant `public`); other `IOException` → not ok `mpv did not run: <redacted message>`.
5. `audio-output` / `Audio output`: template non-blank → ok `Using the template <template>`; mpv not ok → not ok `Needs mpv first`; else `AudioDevices.soundServerOutputs(AudioDevices.parse(launcher.run(List.of("--no-config", "--audio-device=help"), Duration.ofSeconds(5))))` non-empty → ok `PipeWire or PulseAudio reachable (<n> outputs)`; empty → not ok `No PipeWire or PulseAudio server is reachable from the container. Mount the audio user's /run/user/<uid>/pulse to /run/pulse and set PULSE_SERVER=unix:/run/pulse/native (see docs/bluetooth-speakers.md).` followed, when `environment.apply("PULSE_SERVER")` is non-null, by ` PULSE_SERVER is <value>, but nothing answers there.`; `IOException` → not ok `mpv did not run: <redacted message>`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS.

- [ ] **Step 11: Run the build**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL. `grep -rn "loadfile" src/main/java` shows only `MpvPlayer`; `grep -rn "getRuntime().exec\|ProcessBuilder" src/main/java` shows only `ProcessMpvLauncher` (plus anything that existed before this sub-project).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/bluetooth src/test/java/dev/andre/homecontrol/adapters/bluetooth
git commit -m "feat: play audio streams on Bluetooth speakers through a server-side mpv player"
```

---

### Task 4: J4 · Acceptance

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/web/BluetoothSpeakerEndToEndTest.java`, `src/test/java/dev/andre/homecontrol/web/BluetoothJellyfinEndToEndTest.java`, `docs/superpowers/reviews/2026-09-16-bluetooth-speakers-acceptance.md`
- Modify: `docs/bluetooth-speakers.md` (failure modes), `README.md`, `adapters/bluetooth/BluetoothSpeakerSessionTest.java`, `BluetoothPairingServiceTest.java`, `BluetoothHostChecksTest.java`, `deployment/BluetoothDeploymentTest.java`
- Uses unchanged: `FakeBluezClient`, `FakeMpv`, `FakeMpvScript`, `InProcessMpvLauncher` (Tasks 2–3), `FakeJellyfinServer` and fixtures `music-recent.json`, `sessions.json`, `item-track.json`, `playback-info-audio.json` (C, I), C9's/I's end-to-end test frame.

**Interfaces:**
- Consumes: the whole application context; `DeviceManager.adopt/state/capabilities/forget`; `DeviceRegistry.findById`; endpoints `/setup`, `/setup/bluetooth/scan|pair|connect|disconnect|audio-device|check`, `/setup/forget`, `/devices/{id}/play|pause|resume|stop|volume|mute|route`, `/`, `/sources/jellyfin/rails/{id}`, `/events`.
- Produces: end-to-end proof over real subprocesses and Unix sockets from the setup page to mpv's IPC; failure-mode tests; the failure-mode documentation; the manual checklist; README section.

- [ ] **Step 1: Write the failure-mode tests**

`BluetoothSpeakerSessionTest` — add:
- `aPlayerThatCrashesClearsNowPlaying`: playing; `launcher.latest().close()` (the fake vanishes without an `end-file`, like a killed mpv) → eventually `nowPlaying()` null while status stays `CONNECTED`; `execute(PLAY)` then works again and `launcher.alive()` is 1.
- `aSlowBluezDoesNotPileUpPolls`: `bluez.delay("device", Duration.ofSeconds(2))`, poll interval 1 s → after 5 s `bluez.reads()` ≤ 4.
- `concurrentPlaysLeaveOnePlayer`: five virtual threads call `execute(PLAY)` at once (collect exceptions; none expected) → eventually `launcher.alive()` 1.
- `aBluezOutageDuringPlaybackStopsTheMusic`: playing; `bluez.unavailable(BLUEZ_NOT_RUNNING)` → within 3 s `launcher.alive()` 0 and status `DISCONNECTED` (unknown connection state counts as disconnected: never let audio drift to another output).

`BluetoothPairingServiceTest` — add:
- `aBusyAdapterIsExplained`: `failNext("discover", BUSY, "Operation already in progress")` → `scan().error()` contains `busy`.
- `aMissingAudioServiceIsExplainedWhenConnecting`: `failNext("connect", NO_AUDIO_PROFILE, "br-connection-profile-unavailable")` → device registered, `warning()` contains `no Bluetooth audio service` and `PipeWire`.
- `accessDeniedIsExplained`: `unavailable(ACCESS_DENIED)` → `pair(...)` message contains `refused this container`.

`BluetoothHostChecksTest` — add a parameterized `everyHostFailureModeNamesItsCheck` over `(setup, failing check id, detail fragment)`:
- socket file deleted → `dbus-socket`, `No D-Bus system socket`
- `bluez.unavailable(BLUEZ_NOT_RUNNING)` → `bluez`, `BlueZ is not running on the host`
- `bluez.unavailable(ACCESS_DENIED)` → `bluez`, `refused this container`
- `bluez.noAdapters()` → `adapter`, `No Bluetooth adapter found`
- `bluez.adapterPowered(false)` → `adapter`, `powered off`
- `launcher.startFailure = new MpvNotInstalledException("mpv", new IOException("error=2"))` → `mpv`, `mpv is not installed`
- launcher options without devices → `audio-output`, `No PipeWire or PulseAudio server is reachable`
and asserts that exactly that check (plus the ones documented to depend on it: `bluez`/`adapter` after `dbus-socket`, `adapter` after `bluez`, `audio-output` after `mpv`) is not ok.

`BluetoothDeploymentTest` — add `hostDocumentationCoversEveryFailureMode`: `docs/bluetooth-speakers.md` contains `## Failure modes` and each of `No D-Bus system socket`, `BlueZ is not running on the host`, `refused this container`, `No Bluetooth adapter found`, `powered off`, `mpv is not installed`, `No PipeWire or PulseAudio server is reachable`, `refused pairing`, `br-connection-profile-unavailable`, `did not answer`, `No audio output for`, `could not play the stream`, `plays audio only`, `not paired with this server any more`, `Nothing is playing`.

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.adapters.bluetooth.*' --tests 'dev.andre.homecontrol.deployment.*'`
Expected: the unit tests PASS (a failure is a real defect in Tasks 2–3 — fix the production code in this task and name it in the commit body); `hostDocumentationCoversEveryFailureMode` FAILS until Step 5.

- [ ] **Step 2: Write the Bluetooth speaker end-to-end test**

`src/test/java/dev/andre/homecontrol/web/BluetoothSpeakerEndToEndTest.java`:

```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(BluetoothSpeakerEndToEndTest.FakeBluez.class)
class BluetoothSpeakerEndToEndTest {

    static final String ADDRESS = "AA:BB:CC:DD:EE:FF";
    static final String ID = "bluetooth-aa-bb-cc-dd-ee-ff";
    static final FakeBluezClient BLUEZ = new FakeBluezClient();
    static final Path ROOT;
    static final Path LOG;
    static final Path MPV;

    static {
        try {
            ROOT = Files.createTempDirectory("bluetooth-e2e");
            LOG = ROOT.resolve("mpv-log.jsonl");
            Files.createFile(ROOT.resolve("system_bus_socket")); // the host check only needs the path to exist
            MPV = FakeMpvScript.create(ROOT.resolve("bin"), Map.of(
                    "FAKE_MPV_LOG", LOG.toString(),
                    "FAKE_MPV_DURATION", "600",
                    "FAKE_MPV_FAIL_URLS_CONTAINING", "unreachable",
                    "FAKE_MPV_DEVICES", "pulse/alsa_output.platform-bcm2835_audio.stereo-fallback=Built-in Audio;"
                            + "pulse/bluez_output.AA_BB_CC_DD_EE_FF.1=JBL Flip 5"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BLUEZ.addDevice(ADDRESS, "JBL Flip 5").icon("audio-card")
                .uuids(BluetoothDeviceInfo.A2DP_SINK, "0000110e-0000-1000-8000-00805f9b34fb").rssi(-58);
        BLUEZ.addDevice("11:22:33:44:55:66", "Pixel 9").icon("phone").uuids("0000110a-0000-1000-8000-00805f9b34fb");
    }

    @TestConfiguration
    static class FakeBluez {
        @Bean
        @Primary
        BluezClient fakeBluezClient() {
            return BLUEZ;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("shield.data-dir", () -> ROOT.resolve("data").toString());
        registry.add("home-control.bluetooth.enabled", () -> "true");
        registry.add("home-control.bluetooth.dbus-address", () -> "unix:path=" + ROOT.resolve("system_bus_socket"));
        registry.add("home-control.bluetooth.mpv-path", MPV::toString);
        registry.add("home-control.bluetooth.runtime-dir", () -> ROOT.resolve("run").toString());
        registry.add("home-control.bluetooth.scan-seconds", () -> "1");
        registry.add("home-control.bluetooth.poll-interval-seconds", () -> "1");
        registry.add("home-control.bluetooth.playing-poll-interval-seconds", () -> "1");
        registry.add("home-control.bluetooth.player-start-timeout-seconds", () -> "20");  // child JVM start
        registry.add("home-control.bluetooth.load-timeout-seconds", () -> "10");
        registry.add("home-control.bluetooth.command-timeout-seconds", () -> "3");
        registry.add("home-control.bluetooth.host-check-cache-seconds", () -> "1");
    }
}
```

Autowire `MockMvc mockMvc`, `DeviceManager devices`, `DeviceRegistry registry`. Helpers: `List<JsonNode> starts()` = `FakeMpvScript.log(LOG)` entries with `type` `start`; `List<List<String>> ipcCommands()` = entries with `type` `command`; `boolean anyFakeMpvAlive()` = any start entry's `pid` with `ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)`. Awaitility ≤ 20 s per wait. `@AfterAll`: `BLUEZ.close()`; assert `anyFakeMpvAlive()` is false.

Test `pairsPlaysControlsAndForgetsASpeaker`, in order:
1. `GET /setup` → 200, contains `id="bluetooth"`, `data-check="dbus-socket"`, `mpv v0.41.0-fake`, `PipeWire or PulseAudio reachable (2 outputs)`, `hci0 (00:1A:7D:DA:71:13)`.
2. `POST /setup/bluetooth/scan` → 3xx `/setup#bluetooth`; `GET /setup` contains `JBL Flip 5`, `name="address" value="AA:BB:CC:DD:EE:FF"` and `1 other Bluetooth devices hidden`, and not `Pixel 9`.
3. `POST /setup/bluetooth/pair address=AA:BB:CC:DD:EE:FF` → 3xx with `Location` `/?device=bluetooth-aa-bb-cc-dd-ee-ff`; `BLUEZ.calls()` contains, in order, `pair AA:BB:CC:DD:EE:FF`, `trust AA:BB:CC:DD:EE:FF`, `connect AA:BB:CC:DD:EE:FF`; `registry.findById(ID)` has kind `BLUETOOTH`, host `AA:BB:CC:DD:EE:FF`, `adapterSettings("bluetooth")` `{address=AA:BB:CC:DD:EE:FF, adapter=00:1A:7D:DA:71:13}`; `devices.capabilities(ID)` contains `LOCAL_AUDIO_SINK` and `VOLUME`; eventually `devices.state(ID).status()` `CONNECTED`.
4. `GET /?device=bluetooth-aa-bb-cc-dd-ee-ff` → contains `/devices/bluetooth-aa-bb-cc-dd-ee-ff/pause`, `/devices/bluetooth-aa-bb-cc-dd-ee-ff/play` and `This speaker plays through the server`.
5. `POST /devices/{ID}/play uri=http://127.0.0.1:9/music/Bunny%20Song.mp3` → 200 body `Play through the server on this Bluetooth speaker`; `starts()` has one entry whose `args` contain `--audio-device=pulse/bluez_output.AA_BB_CC_DD_EE_FF.1` and no argument containing `127.0.0.1:9`; `ipcCommands()` contains `[loadfile, http://127.0.0.1:9/music/Bunny%20Song.mp3, replace]`; eventually `devices.state(ID).nowPlaying()` has title `Bunny Song.mp3`, state `PLAYING`, `durationSeconds` 600.0.
6. `POST …/pause` → 204 → eventually `PAUSED`; `POST …/resume` → 204 → `PLAYING`; `POST …/volume level=30` → 204 → eventually `volumeLevel()` 30 and `ipcCommands()` contains `[set_property, volume, 30]`; `POST …/mute muted=true` → 204 → eventually `muted()` true.
7. `POST …/play uri=http://127.0.0.1:9/films/bunny.mp4` → 422, body contains `a Bluetooth speaker plays audio streams only`.
8. `POST …/play uri=http://127.0.0.1:9/music/unreachable.mp3` → 502, body `JBL Flip 5 could not play the stream: the stream could not be loaded (loading failed)`; eventually `nowPlaying()` null and `anyFakeMpvAlive()` false.
9. `POST …/play uri=http://127.0.0.1:9/music/Bunny%20Song.mp3` → 200; then `BLUEZ.device(ADDRESS).connected(false)` → eventually status `DISCONNECTED`, `nowPlaying()` null, `anyFakeMpvAlive()` false.
10. `POST …/play uri=http://127.0.0.1:9/music/Bunny%20Song.mp3` → 200 (the session connects first: `BLUEZ.calls()` gained another `connect AA:BB:CC:DD:EE:FF`); `POST …/stop` → 204 → eventually `anyFakeMpvAlive()` false and `nowPlaying()` null; `POST …/pause` → 502 body `Nothing is playing on JBL Flip 5`.
11. `POST /setup/bluetooth/audio-device id=bluetooth-aa-bb-cc-dd-ee-ff audioDevice=pulse/alsa_output.platform-bcm2835_audio.stereo-fallback` → 3xx; `POST …/play uri=…/Bunny%20Song.mp3` → 200; the newest start entry contains `--audio-device=pulse/alsa_output.platform-bcm2835_audio.stereo-fallback`; `POST …/stop` → 204.
12. `POST /setup/bluetooth/disconnect id=bluetooth-aa-bb-cc-dd-ee-ff` → 3xx; `BLUEZ.calls()` ends with `disconnect AA:BB:CC:DD:EE:FF`. `POST /setup/bluetooth/connect id=ghost` → 404.
13. `POST /setup/forget id=bluetooth-aa-bb-cc-dd-ee-ff` → 3xx; `registry.findById(ID)` empty; `BLUEZ.calls()` ends with `remove AA:BB:CC:DD:EE:FF`; `anyFakeMpvAlive()` false; `LOG` text does not contain `secret` (no URL in this test has one, the check guards future edits) and no `start` entry contains `http`.

- [ ] **Step 3: Write the Jellyfin-to-Bluetooth end-to-end test**

`src/test/java/dev/andre/homecontrol/web/BluetoothJellyfinEndToEndTest.java` — copy the class frame of I's `SpeakerJellyfinEndToEndTest` (itself C9's `JellyfinEndToEndTest` frame: Spring Boot on a random port, temp data dir, `HttpClient` browser with cookies and one stranger without, `send/get/page/post` helpers, every browser response body collected, SSDP disabled) and add the Bluetooth properties, `FakeBluez` test configuration and fake mpv script of Step 2 (own temp root and `FakeBluezClient`, whose speaker is `known(...)`, `paired(true)`, `connected(true)`, `uuids(A2DP_SINK)`). One test `playsAJellyfinTrackOnABluetoothSpeakerWithoutLeakingTheToken`:

```java
        try (FakeJellyfinServer jellyfin = new FakeJellyfinServer().withConnectableServer()
                     .respond("GET", "/Items", 200, "music-recent.json")
                     .respond("GET", "/Sessions", 200, "sessions.json")
                     .respond("GET", "/Items/" + TRACK, 200, "item-track.json")
                     .respond("POST", "/Items/" + TRACK + "/PlaybackInfo", 200, "playback-info-audio.json")) {
            devices.adopt(new Device(ID, "JBL Flip 5", DeviceKind.BLUETOOTH, ADDRESS,
                    Map.of("bluetooth", new BluetoothSettings(ADDRESS, FakeBluezClient.ADAPTER, "").toMap()), Instant.now()));
            try {
                // connect Jellyfin exactly as the copied frame does (sets the login password and logs the browser in)
                // …
                await().until(() -> devices.state(ID).status() == DeviceStatus.CONNECTED);
                assertThat(send(browser, get("/sources/jellyfin/rails/music-recent")).body()).contains("Bunny Song");
                assertThat(send(browser, get("/devices/" + ID + "/route?source=jellyfin&item=" + TRACK)).body())
                        .isEqualTo("Play through the server on this Bluetooth speaker");
                HttpResponse<String> played = send(browser, post("/devices/" + ID + "/play", Map.of("source", "jellyfin", "item", TRACK)));
                assertThat(played.statusCode()).isEqualTo(200);
                List<List<String>> loads = ipcCommands().stream().filter(command -> command.getFirst().equals("loadfile")).toList();
                assertThat(loads).singleElement().satisfies(load -> assertThat(load.get(1))
                        .startsWith(jellyfin.url() + "/Audio/" + TRACK + "/stream.flac?static=true")
                        .contains("ApiKey=" + ACCESS_TOKEN));                      // the player needs the key …
                assertThat(starts()).allSatisfy(start -> assertThat(start.toString())
                        .doesNotContain("ApiKey").doesNotContain("/Audio/"));      // … but never on the command line
                await().until(() -> devices.state(ID).nowPlaying() != null
                        && devices.state(ID).nowPlaying().title().equals("Bunny Song"));
                // open /events for the browser for 3 s and add what arrived to browserBodies
                assertThat(browserBodies).noneMatch(body -> body.contains(ACCESS_TOKEN) || body.contains("ApiKey"));
            } finally {
                devices.forget(ID);
            }
        }
```

with `TRACK = "c0ffee00c0ffee00c0ffee00c0ffee01"`, `ID = "bluetooth-aa-bb-cc-dd-ee-ff"`, `ADDRESS = "AA:BB:CC:DD:EE:FF"`, and afterwards `anyFakeMpvAlive()` false. (The speaker has no IP, so rung 1 finds no Jellyfin session; it is neither a Cast receiver nor a media renderer, so the stream is built from the server address and the local rung plays it.)

- [ ] **Step 4: Run the new tests**

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.web.Bluetooth*' --tests 'dev.andre.homecontrol.adapters.bluetooth.*'`
Expected: PASS. A flaky wait gets a longer Awaitility timeout, never a sleep. If step 8 of the speaker test answers 409 instead of 502, check B's `DeviceController` mapping of `ActionFailedException` before changing the assertion.

- [ ] **Step 5: Document the failure modes**

Append to `docs/bluetooth-speakers.md`:

````markdown
## Failure modes

Every problem below shows up in words on the setup page (**Setup → Bluetooth speakers**, the
checks at the top) or as the message of a failed command. Nothing here affects the rest of Home
Control; switching the module off (`HOME_CONTROL_BLUETOOTH_ENABLED=false`) removes it entirely.

| What you see | Cause | Fix |
|---|---|---|
| ✗ D-Bus system socket — "No D-Bus system socket (nothing at /run/dbus/system_bus_socket)" | `/run/dbus` is not mounted into the container | Add `/run/dbus:/run/dbus:ro` (use `compose.bluetooth.yaml` or the CasaOS Bluetooth manifest). |
| ✗ BlueZ — "BlueZ is not running on the host" | `bluez` missing or `bluetooth.service` stopped | `sudo apt install bluez && sudo systemctl enable --now bluetooth` |
| ✗ BlueZ — "The host's D-Bus refused this container" | Rootless Docker, user-namespace remapping, a non-root container user, or AppArmor denying D-Bus | Run the container as root (default); with AppArmor add `security_opt: [apparmor:unconfined]`. |
| ✗ Bluetooth adapter — "No Bluetooth adapter found on the host" | No controller (many NAS and virtual machines), USB dongle unplugged, hard-blocked radio | Plug in a dongle; `bluetoothctl list`; `sudo rfkill unblock bluetooth`. |
| ✗ Bluetooth adapter — "hci0 (…) is powered off" | Soft-blocked or switched off | Scanning switches it on; otherwise `sudo rfkill unblock bluetooth`. |
| ✗ mpv player — "mpv is not installed in this container" | The default image has no player | Use the tag `latest-bluetooth`, or build with `WITH_MPV=true`. |
| ✗ Audio output — "No PipeWire or PulseAudio server is reachable from the container" | Socket directory not mounted, wrong uid, audio server not running (no one logged in and no linger), PulseAudio cookie missing | Mount `/run/user/<uid>/pulse` to `/run/pulse`, set `PULSE_SERVER=unix:/run/pulse/native`, `sudo loginctl enable-linger <user>`, mount the cookie for PulseAudio. |
| Pairing: "The speaker refused pairing" | Speaker not in pairing mode, or a legacy speaker that wants a PIN | Enter pairing mode and retry. PIN speakers: pair once on the host with `bluetoothctl` (`pair`, `trust`), then **Pair and add**. |
| Pairing: "… is not a speaker or headphones (no A2DP audio sink)" | The device offers no A2DP audio sink (phone, keyboard, hands-free-only headset) | Pick an audio device. |
| Connect: "The host has no Bluetooth audio service for this speaker (br-connection-profile-unavailable)" | PipeWire/WirePlumber or PulseAudio's Bluetooth module is not running for the audio user — typical on headless hosts | Enable linger and turn off WirePlumber seat monitoring (see the checklist), then connect again. |
| Connect or play: "The speaker did not answer" | Speaker off, out of range, or connected to a phone | Switch it on, disconnect it from the phone, move it closer. |
| Play: "JBL Flip 5 is not paired with this server any more" | The pairing was removed on the host (`bluetoothctl remove`) or on the speaker | Forget it on the setup page and pair again. |
| Play: "No audio output for AA:BB:… was found" | BlueZ connected the speaker but the audio server shows no sink for it (another user's session owns Bluetooth audio, or WirePlumber ignores it) | Fix the audio server as above, or set the speaker's audio output on the setup page to an id from `mpv --audio-device=help`. |
| Play: "… could not play the stream: mpv exited before opening its control socket (mpv: … Failed to initialize audio output …)" | The chosen audio output does not exist or the audio server refused the stream | Leave the audio output blank (automatic) or correct it. |
| Play: "… could not play the stream: the stream could not be loaded (…)" | The **server** cannot fetch the URL (wrong Jellyfin address inside the container, link needs a login, unsupported format) | Open the link from the host; check Jellyfin's server address on the setup page. |
| Play: "… plays audio only" / "a Bluetooth speaker plays audio streams only" | A video or a non-HTTP link | Play music; videos belong on a TV. |
| Pause/Play: "Nothing is playing on …" | The track ended or the speaker disconnected | Play again. |
| Music stops when the speaker switches off | Intended: Home Control stops the player so audio never continues on the host's HDMI or headphone output | — |
| Stuttering audio | 2.4 GHz interference (Wi-Fi on 2.4 GHz, USB 3 ports), weak onboard Bluetooth | Use 5 GHz Wi-Fi or Ethernet, a USB Bluetooth dongle on an extension cable. |
````

Run: `.superpowers/gradle.sh test --tests 'dev.andre.homecontrol.deployment.*'`
Expected: PASS.

- [ ] **Step 6: Write the manual acceptance checklist**

`docs/superpowers/reviews/2026-09-16-bluetooth-speakers-acceptance.md`:

```markdown
# Bluetooth speakers (sub-project J) — manual acceptance

Automated coverage: `BluetoothSpeakerEndToEndTest`, `BluetoothJellyfinEndToEndTest`, `BluetoothClassLoadingTest`,
`BluetoothDeploymentTest` and the unit tests of Tasks 1–4, all against in-process fakes (`FakeBluezClient`,
`FakeMpv` in-process and as a child process, `FakeJellyfinServer`). No test talks to a real D-Bus, BlueZ, adapter,
audio server, mpv or speaker. Agents cannot operate real hardware: every item below is
**Pending — requires real hardware** until a person runs it on the target host and replaces the status with
Passed/Failed plus notes. Household facts are unknown (spec §13 question 4); record them first.

- Host (model, RAM, OS and version, kernel): _unknown — target is a Raspberry Pi class machine (e.g. Pi 4/5, Raspberry Pi OS Bookworm or later)_
- Bluetooth adapter (onboard / USB dongle model, `bluetoothctl show`): _unknown_
- Audio server (PipeWire + WirePlumber versions, or PulseAudio), audio user uid: _unknown_
- Docker / CasaOS version, image tag used: _unknown_
- Speakers (brand, model, firmware, PIN or Just Works): _unknown_

## Host and packaging

| # | Check | Status |
|---|---|---|
| H1 | With the default image and the module off, Home Control behaves exactly as before (no Bluetooth section, no extra log lines, no `mpv` process) | Pending — requires real hardware |
| H2 | `docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build` builds on arm64 and starts; `docker exec … mpv --version` prints mpv 0.41 | Pending — requires real hardware |
| H3 | The published `latest-bluetooth` image pulls and runs on arm64 | Pending — requires real hardware |
| H4 | The CasaOS Bluetooth manifest imports, shows the three mounts and starts the app | Pending — requires real hardware |
| H5 | Following docs/bluetooth-speakers.md on a fresh Raspberry Pi OS install turns every setup-page check green | Pending — requires real hardware |
| H6 | After a host reboot with nobody logged in (linger enabled), all checks are still green | Pending — requires real hardware |
| H7 | Image size difference between the default and the -bluetooth image is recorded (expected ≈ +430 MB uncompressed) | Pending — requires real hardware |

## Pairing

| # | Check | Status |
|---|---|---|
| P1 | "Scan for speakers" lists a speaker in pairing mode within the scan time, with its name, marked "speaker" | Pending — requires real hardware |
| P2 | Phones, keyboards and other non-audio devices are hidden and counted | Pending — requires real hardware |
| P3 | "Pair and add" pairs, trusts and connects; the dashboard opens on the new chip, which shows connected | Pending — requires real hardware |
| P4 | `bluetoothctl info <MAC>` on the host shows Paired: yes, Trusted: yes, Connected: yes | Pending — requires real hardware |
| P5 | A speaker already paired on the host via bluetoothctl is added without re-pairing | Pending — requires real hardware |
| P6 | A PIN/legacy speaker: record whether "Pair and add" works or the bluetoothctl workaround is needed | Pending — requires real hardware |
| P7 | Disconnect and Connect on the setup page work; the chip follows within 5 s | Pending — requires real hardware |
| P8 | Switching the speaker off shows disconnected within 5 s; switching it on reconnects (trusted) or connects on the next play | Pending — requires real hardware |
| P9 | Forget removes the chip and unpairs the speaker on the host (`bluetoothctl devices Paired` no longer lists it) | Pending — requires real hardware |
| P10 | After a container restart, registered speakers come back connected without pairing again | Pending — requires real hardware |

## Playback

| # | Check | Status |
|---|---|---|
| A1 | A pasted direct .mp3 link plays on the speaker within 3 s; the chip shows the file name as now playing | Pending — requires real hardware |
| A2 | .flac, .m4a and .ogg links play | Pending — requires real hardware |
| A3 | A Jellyfin track from "Recently played music" plays; the play sheet names "Play through the server on this Bluetooth speaker" first | Pending — requires real hardware |
| A4 | Pause, Play and Stop in the drawer act within a second; now playing follows | Pending — requires real hardware |
| A5 | The volume slider and mute change loudness; the next track keeps the volume | Pending — requires real hardware |
| A6 | The position advances in the chip while playing and stops while paused | Pending — requires real hardware |
| A7 | A track that ends clears now playing and leaves no mpv process (`docker exec … ps`) | Pending — requires real hardware |
| A8 | Playing a second track replaces the first without two streams overlapping | Pending — requires real hardware |
| A9 | Two speakers paired at once: record whether both play simultaneously (depends on the adapter) | Pending — requires real hardware |
| A10 | `ps aux` on the host during playback shows no stream URL or Jellyfin key in mpv's command line | Pending — requires real hardware |
| A11 | CPU load of mpv while playing FLAC is recorded (expected well below one core on a Pi 4) | Pending — requires real hardware |
| A12 | Audio stays in sync and does not stutter for a 30-minute session | Pending — requires real hardware |
| A13 | An internet radio (Icecast MP3) link plays and shows its stream title | Pending — requires real hardware |

## Failure modes

| # | Check | Status |
|---|---|---|
| F1 | Without the `/run/dbus` mount the setup page shows "No D-Bus system socket"; the app otherwise works | Pending — requires real hardware |
| F2 | With `bluetooth.service` stopped the page shows "BlueZ is not running on the host" | Pending — requires real hardware |
| F3 | With the adapter blocked (`rfkill block bluetooth`) the page shows the adapter problem; scanning or unblocking recovers | Pending — requires real hardware |
| F4 | With the default image and the module on, the page shows "mpv is not installed" and playing answers the same | Pending — requires real hardware |
| F5 | Without the pulse socket mount the page shows "No PipeWire or PulseAudio server is reachable" | Pending — requires real hardware |
| F6 | With the audio server stopped for the audio user, connecting shows the br-connection-profile-unavailable explanation | Pending — requires real hardware |
| F7 | Switching the speaker off during playback stops mpv within 2 s; no sound comes out of HDMI or the headphone jack | Pending — requires real hardware |
| F8 | A Jellyfin address the container cannot reach gives "could not play the stream" instead of silence | Pending — requires real hardware |
| F9 | Removing the pairing with bluetoothctl shows the speaker as unpaired and playing asks to pair again | Pending — requires real hardware |
| F10 | Stopping the container during playback leaves no mpv process on the host and releases the speaker | Pending — requires real hardware |
| F11 | On a NAS without Bluetooth (CasaOS), the module on shows clear problems and nothing else breaks | Pending — requires real hardware |

## Findings

_None yet._
```

- [ ] **Step 7: Document Bluetooth speakers in the README**

`README.md` — add `## Bluetooth speakers (optional)` after the Wi-Fi speakers section: Home Control can play music on Bluetooth speakers paired with the machine it runs on, by playing the stream itself with mpv through the host's PipeWire or PulseAudio; off by default; needs a host with BlueZ, an adapter and an audio server (Raspberry Pi class machines work, most NAS boxes do not); start with `docker compose -f compose.yaml -f compose.bluetooth.yaml up -d --build` or the image tag `latest-bluetooth`, or `casaos/docker-compose.bluetooth.yml` on CasaOS; pair on **Setup → Bluetooth speakers**; what works (direct audio links and Jellyfin music, pause/play/stop, volume, mute, now playing) and limits (audio only; the server must reach the stream; the speaker's hardware volume is not changed; music stops when the speaker disconnects; one stream per speaker). Link `docs/bluetooth-speakers.md` for the checklist and failure modes. Configuration rows:

| Property | Default | Meaning |
|---|---|---|
| `home-control.bluetooth.enabled` | `false` | Bluetooth speaker module |
| `home-control.bluetooth.dbus-address` | `unix:path=/run/dbus/system_bus_socket` | Host D-Bus system bus |
| `home-control.bluetooth.adapter` | *(first powered)* | Adapter MAC or id such as `hci0` |
| `home-control.bluetooth.scan-seconds` | `10` | Length of a scan |
| `home-control.bluetooth.mpv-path` | `mpv` | Player executable |
| `home-control.bluetooth.audio-device-template` | *(blank: find the speaker's sink)* | e.g. `alsa/bluealsa:DEV={mac},PROFILE=a2dp` |
| `home-control.bluetooth.default-volume` | `50` | Player volume until changed |

- [ ] **Step 8: Build and commit**

Run: `.superpowers/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add src/test/java/dev/andre/homecontrol/web/BluetoothSpeakerEndToEndTest.java \
  src/test/java/dev/andre/homecontrol/web/BluetoothJellyfinEndToEndTest.java \
  src/test/java/dev/andre/homecontrol/adapters/bluetooth src/test/java/dev/andre/homecontrol/deployment/BluetoothDeploymentTest.java \
  docs/bluetooth-speakers.md docs/superpowers/reviews/2026-09-16-bluetooth-speakers-acceptance.md README.md
# plus any production file fixed because a Step 1 test found a defect
git commit -m "test: Bluetooth speakers end to end with fake BlueZ and mpv, failure modes and acceptance checklist"
```

---

## Final Automated Verification

- [ ] `.superpowers/gradle.sh build` — BUILD SUCCESSFUL with every test above, including `BluetoothClassLoadingTest`.
- [ ] `grep -rln "org.freedesktop\|org.bluez\|com.github.hypfvieh" src/main/java` — exactly `src/main/java/dev/andre/homecontrol/adapters/bluetooth/bluez/DbusBluezClient.java`.
- [ ] `grep -rn "DbusBluezClient" src/main/java` — only its own file and a method body in `BluetoothConfiguration.java`.
- [ ] `grep -rn "adapters\.bluetooth" src/main/java/dev/andre/homecontrol/{core,device,playback,web,sources,security,content}` — no matches.
- [ ] `grep -rn "loadfile" src/main/java` — only `MpvPlayer.java`; `grep -rn "ProcessBuilder" src/main/java` — only `ProcessMpvLauncher.java` (plus anything that predates J).
- [ ] `grep -n "enabled" src/main/resources/application.yaml` under `bluetooth:` is `false`; `compose.yaml` and `casaos/docker-compose.yml` contain no `/run/dbus`.
- [ ] The acceptance checklist has no status other than "Pending — requires real hardware".

## Out of scope for this plan

- A BlueZ pairing agent (PIN `0000` legacy pairing, passkey confirmation); speakers needing it are paired once with `bluetoothctl`.
- Hardware (AVRCP absolute) volume, speaker battery level, speaker buttons controlling playback (AVRCP media control).
- Multi-speaker synchronized playback, grouping Bluetooth speakers, sending one stream to several sinks.
- bluealsa inside the published image; ALSA-only hosts beyond the documented template.
- Queues, albums and playlists (no queue route exists), seeking, resume position for local playback.
- Video on Bluetooth speakers; Bluetooth headphones as a separate device kind (they work as speakers).
- Running Bluetooth without host networking or as a non-root container user.
- Auto-reconnecting speakers in the background beyond one attempt after start and one before each play.
