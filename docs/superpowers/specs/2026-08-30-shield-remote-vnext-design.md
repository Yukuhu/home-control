# Shield Remote vNext — Staged Feature Roadmap

**Date:** 2026-08-30
**Status:** Approved; v0.3 implementation plan ready

## 1. Overview

Shield Remote will evolve from its v0.2 single-page remote into a reliable,
touch-first web app through three independently releasable increments:

1. **v0.3 — Reliable appliance:** make pairing survive CasaOS redeployments and
   remove the app-launching feature that cannot work reliably with Remote v2.
2. **v0.4 — Touch-first web app and PWA:** add gesture control, adaptive phone
   and tablet layouts, install metadata, and locally customizable secondary
   controls without replacing the existing Spring web stack.
3. **v0.5 — Remote text input:** implement the Remote v2 IME messages required
   to type from a phone or tablet when the TV has an editable field active.

Each release must be useful and stable on its own. The order is deliberate:
persistent server state must be trustworthy before richer clients depend on it,
and the new touch shell must exist before text entry adds another interaction
surface.

## 2. Product principles and constraints

### 2.1 Product principles

- **Plug-and-play with NVIDIA Shield:** pairing through Android TV Remote v2 is
  the only device-side setup. Developer mode and ADB remain out of scope.
- **LAN appliance first:** the application remains useful over ordinary HTTP on
  a private network and does not require a cloud service.
- **Cross-platform client:** iPhone, iPad, Android phones, and Android tablets are
  first-class targets. Desktop keyboard control continues to work.
- **Progressive enhancement:** unsupported browser features remove convenience,
  not core remote functionality.
- **Commands are ephemeral:** a command that cannot be sent immediately is
  rejected. It is never cached or replayed later.
- **One build and runtime:** Spring Boot, Thymeleaf, htmx, SSE, and vanilla
  JavaScript remain the production stack. No Node production build is added.

### 2.2 Explicit non-goals

- ADB, developer-mode setup, app installation, shell commands, or file transfer.
- Discovering installed applications or deriving an application's deep link from
  its package name.
- Voice control, Wake-on-LAN, multiple-device switching, user accounts, or
  internet exposure.
- Automatic trusted HTTPS for an arbitrary home LAN. The application can sit
  behind a user-provided trusted reverse proxy, but it cannot mint a certificate
  that mobile browsers will trust without external configuration.
- Server-synchronized UI preferences. Phone and tablet layouts intentionally may
  differ.

## 3. Architecture direction

The current layering remains intact:

```text
Browser/PWA
  ├── Thymeleaf shell and htmx commands
  ├── vanilla-JS gesture, layout, and transport modules
  └── SSE device state and transient error events
          │
Spring web controllers
          │
DeviceSession / DeviceSessionManager
          │
RemoteConnection (the only Remote v2/protobuf boundary)
          │
NVIDIA Shield
```

The rejected alternatives are a web-component framework and a full SPA. Both
would add a frontend toolchain and state-management layer without improving the
small number of screens enough to justify the migration. Progressive enhancement
keeps the existing control and state paths while isolating new browser behavior
in focused modules.

## 4. v0.3 — Reliable appliance

### 4.1 Goals

- Preserve the pairing credential and device registry across CasaOS container
  replacement.
- Remove controls that look functional but cannot reliably launch an app.
- Report missing or unwritable persistence explicitly instead of silently
  generating credentials that the Shield does not recognize.

### 4.2 Remove app launching

Remove the application catalog end to end:

- `AppCatalog`, `AppEntry`, `default-apps.yaml`, and their tests;
- `/apps/{id}/launch` and `/apps/current` routes;
- launcher buttons and the **Add current app** control;
- catalog-related JavaScript, CSS, configuration, and README instructions; and
- the protocol method used only to send app-link launch requests.

Replace the handshake's unexplained `622` feature value with named feature bits.
Continue advertising key, power, volume, and IME receive support (IME delivery is
what provides current-app events), but stop advertising removed app-link support
and unimplemented voice support in v0.3.

The live current-app indicator remains. Remote v2 reports the foreground package,
which is useful connection context even though it is insufficient for relaunching
the application.

Remote v2 exposes a URI-based launch request but no operation to enumerate an
app's declared deep links. The foreground-app event supplies a package name, not
a canonical launch URI. A package-to-URI database would be incomplete and would
contradict the plug-and-play requirement, so vNext has no app-shortcut feature.

### 4.3 CasaOS deployment

Add a first-class CasaOS-compatible Compose manifest and metadata. Its main
service must:

- use `ghcr.io/yukuhu/home-control`;
- use host networking, preserving mDNS discovery;
- bind `/DATA/AppData/$AppID/data` on the host to `/data` in the container;
- restart unless stopped;
- identify the web UI port as 8080; and
- omit `SHIELD_KEYSTORE_PASSWORD` by default so the application's stable default
  is used across redeployments. An advanced user may still set a stable override.

The existing ordinary Compose path continues to bind `./data:/data`. Documentation
must explain both layouts and show where `keystore.p12` and `devices.json` live.
The keystore password protects the local PKCS12 file; it is not application login
or network authentication.

CasaOS installs created before v0.3 without a host mapping cannot automatically
recover the anonymous container storage after it has been discarded. They require
one final pairing after installing the new manifest. Later redeployments reuse the
same bind-mounted credential.

### 4.4 Persistence behavior

Pairing must prove that the data directory can be created and written before the
TV accepts a pairing code. Certificate and registry writes retain their existing
atomic-file behavior where applicable.

At startup, a registered device and a stored credential are treated as one
logical record:

- both present: start the device session normally;
- registry present, credential absent: do not generate a replacement credential;
  expose `UNPAIRED` and link to setup;
- credential present, registry absent: retain the credential so a repair attempt
  for the same device identity can reuse it; and
- unreadable keystore or registry: fail with a message naming the path and likely
  password/permission cause rather than starting with empty state.

`PairingService` may create and persist a credential when a deliberate pairing
attempt begins. `DeviceSessionManager` must only load an existing credential for
a registered device; it must never call `loadOrCreate` during ordinary startup.

### 4.5 Error handling and validation

- An unwritable `/data` blocks pairing before code submission and produces an
  actionable setup-page error.
- A missing credential produces an `UNPAIRED` state, not a reconnect loop.
- A keystore password mismatch is not interpreted as a missing credential.
- Forgetting a device stops the session and removes both its registry entry and
  its certificate-store alias. The setup page must make clear that using the
  device again will require pairing.

### 4.6 Testing and acceptance

- Parse the CasaOS manifest in a test and assert host networking, the exact `/data`
  target, a persistent `/DATA/AppData/$AppID` source, and restart policy.
- Verify startup never creates a credential for a registry-only device.
- Verify registry-only state is `UNPAIRED` and performs no connection attempt.
- Verify pairing fails clearly when the data directory is unwritable.
- Verify the remote page still renders current-app state and contains no launcher
  controls or launcher endpoints.
- Smoke-test a CasaOS redeploy: pair once, replace the container, and reconnect
  without showing a new pairing code.

## 5. v0.4 — Touch-first web app and PWA

### 5.1 Goals

- Make the remote comfortable on phones and tablets through gestures.
- Preserve a precise, accessible full-button alternative.
- Make the site install-like everywhere practical and a true PWA when served from
  a trustworthy HTTPS origin.
- Keep all control paths compatible with the existing desktop page and endpoints.

### 5.2 Frontend modules

Split browser behavior into small, independently understandable modules:

- **`RemoteTransport`** owns command POSTs, connection gating, repeat/long-press
  parameters, and common error toasts. It does not own layout.
- **`TouchpadController`** translates Pointer Events into tap, swipe, and hold
  intents. It does not know URLs or device state.
- **`LayoutController`** switches modes, applies responsive layout, manages the
  secondary-control editor, and stores preferences locally.
- **`StateView`** applies SSE state to the status badge, current-app indicator,
  volume, availability, and transient command errors.
- **`PwaController`** handles install guidance, service-worker update state, and
  the explicit update action when secure-context features are available.

Implement them as separate, narrowly scoped ES modules loaded directly by the
page. The production artifact remains a Spring Boot jar with static resources;
there is no bundling step.

### 5.3 Control layouts

The browser remembers the selected mode per origin and browser:

1. **Touchpad mode:** a large gesture surface with essential buttons nearby.
2. **Button mode:** a conventional D-pad/OK layout and button groups.

The fixed core is D-pad/OK, Back, Home, Power, Mute, Volume Up, and Volume Down.
Menu, Play/Pause, Previous, Next, Rewind, Fast Forward, Stop, Info, Settings, and
Guide are secondary controls. Users can show, hide, and reorder secondary controls
but cannot remove the fixed core. A reset action restores the default secondary
set and order.

Phone layouts use the two-mode switch directly. In landscape tablet layouts,
touchpad mode becomes the approved adaptive split: touchpad on the left and
essential controls on the right. Button mode remains available. Layout changes
are driven by viewport/container size rather than user-agent detection.

Preferences are stored in versioned `localStorage` keys. They are intentionally
local: a phone can prefer the touchpad while a tablet prefers buttons. Invalid or
old preference values fall back to defaults without breaking the remote.

### 5.4 Gesture semantics

Use Pointer Events so mouse, pen, Android touch, and iOS/iPadOS touch share one
state machine. The touchpad applies `touch-action: none`; the rest of the page
retains normal browser behavior.

- A release within the tap-slop radius sends one `DPAD_CENTER` short press.
- Movement beyond the swipe activation threshold chooses the dominant axis.
- Swipe distance maps to one through four D-pad short presses. Constants live in
  one pure gesture-mapping unit and are capped to prevent runaway navigation.
- A hold within the tap-slop radius sends `START_LONG` for `DPAD_CENTER`; release
  or cancellation always sends `END_LONG` if a long press started.
- Pointer cancellation, connection loss, or mode switching clears the gesture
  without producing a delayed swipe.

The initial contract uses a 12 CSS-pixel tap slop, a 24 CSS-pixel swipe
activation threshold, one additional step per 56 CSS pixels, a four-step maximum,
and a 450 ms hold threshold. They are named constants. Changing them after
real-device testing requires updating their boundary tests in the same change.

Extend the existing key endpoint compatibly with validated repeat and direction
parameters. Repeat is restricted to 1–4. Long-press directions are accepted only
for supported remote keys. Existing button and keyboard calls continue to mean
one short press.

### 5.5 Connection and command behavior

SSE remains the authoritative live-state channel. When it reports a non-connected
device or the browser loses the stream:

- controls become visibly unavailable;
- in-progress pointer gestures are cancelled safely;
- a command attempt receives immediate feedback; and
- no command is retained for later delivery.

The browser's generic network status may supplement the message but never replace
device session state. Reconnection is automatic through the existing SSE and
`DeviceSession` mechanisms.

### 5.6 PWA levels

Every deployment receives:

- a web app manifest with name, short name, start URL, standalone display, theme
  colors, and 192/512 maskable icons;
- Apple touch icons and standalone/status-bar metadata;
- responsive safe-area handling for notches and home indicators; and
- platform-appropriate instructions for adding the remote to a home screen when
  the browser does not offer an install prompt.

Over ordinary LAN HTTP, the responsive remote remains complete, but the product
must not claim guaranteed PWA installability or service-worker support.

Over a browser-trusted HTTPS origin, register a service worker that:

- pre-caches versioned CSS, JavaScript, icons, and a dedicated disconnected page;
- uses network-first navigation with the disconnected page as the only offline
  fallback;
- never caches POST requests, SSE, live device state, setup forms, or pairing
  responses; and
- detects a waiting worker and offers an explicit **Update available** action.

The app never reloads automatically while the user is interacting. Activating an
update reloads only after the user chooses it. Offline mode is an explanatory
screen, not a simulated remote.

### 5.7 Accessibility and progressive feedback

- Interactive targets are at least 44 by 44 CSS pixels.
- Every icon-only control has an accessible name, visible focus treatment, and a
  semantic button.
- Mode selection and customization work without gestures.
- Respect `prefers-reduced-motion` and forced-colors/high-contrast behavior.
- Keep existing desktop keyboard bindings and document them in the UI.
- Vibration feedback is deferred; the first touch release has identical behavior
  on platforms with and without the Vibration API.

### 5.8 Testing and acceptance

Java controller tests cover endpoint validation, repeat caps, long-press
directions, connection conflicts, manifest delivery, and service-worker cache
rules. Gesture mapping is a pure unit with boundary tests around tap, dead-zone,
direction, distance, cap, hold, and cancellation.

Playwright for Java is a test-only Gradle dependency; it does not create a Node
production build. Browser tests cover:

- phone portrait touchpad and button modes;
- tablet landscape adaptive split;
- preference save, reload, invalid-value fallback, reorder, and reset;
- exact POSTs produced by tap, each swipe distance, hold, and cancellation;
- controls disabling on SSE loss and returning on reconnect;
- install/update UI in a secure localhost test context; and
- keyboard-only and accessible-name paths.

Run Chromium and Playwright WebKit in CI. Because Playwright WebKit is not real
iOS Safari, release acceptance also includes a manual iPhone and iPad smoke test
for layout, pointer behavior, home-screen launch, safe areas, and software-keyboard
interaction.

## 6. v0.5 — Remote text input

### 6.1 Goals

- Let a user type on the phone or tablet after selecting an editable field on the
  TV.
- Avoid mirroring or retaining sensitive TV field contents.
- Degrade clearly when a Shield build or Android TV application does not accept
  Remote v2 IME input.

### 6.2 Protocol boundary

Keep every protobuf type within `protocol`. Add domain-facing text operations and
events rather than exposing `RemoteImeBatchEdit` or `RemoteImeObject`.

During the configure handshake, intersect the device's advertised feature mask
with the named client feature mask introduced in v0.3. IME send support is active
only when both sides advertise IME; key, power, volume, and current-app behavior
continue to use the same negotiated mask.

`RemoteConnection` tracks the latest `ime_counter` and `field_counter` received in
`RemoteImeBatchEdit`. `sendText` constructs one insert edit using those counters
and the supplied string. The maintained `androidtvremote2` implementation is the
wire reference for this exchange:
<https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remote.py>.

Extend the domain listener with:

- text-input availability/counter changes; and
- a transient command-error event derived from `RemoteError`.

Do not include field contents in `DeviceState`. Do not write entered strings to
application logs, exception messages, SSE events, metrics, or persisted files.

### 6.3 Availability lifecycle

Text input becomes available after the device supplies usable IME field counters
or an equivalent verified editable-field signal. It becomes unavailable when:

- the device disconnects;
- the active app changes;
- the device does not negotiate IME support; or
- the protocol supplies a field/session transition that invalidates the counters.

The baseline clears availability on app change, disconnect, or a text-command
`RemoteError`; otherwise it remains available using the latest counters. If a
verified field-close message is observed during real-device tests, handle that as
an additional clear signal. The implementation must not invent cursor
synchronization it cannot verify.

### 6.4 Web API and UI

Add `POST /text` with an `application/json` body shaped as `{"text":"..."}`. It
validates:

- connected device;
- negotiated and currently available IME state;
- non-empty input; and
- a conservative maximum payload length.

The maximum is 4 KiB of UTF-8 payload. Oversized input is rejected before reaching
the protocol stream.
Add `ENTER` and `DEL` to the allowed remote-key subset for field submission and
correction.

When text input becomes available, the remote highlights **Type on phone**. The
browser does not force-open a software keyboard, which mobile platforms may block
without a user gesture. Tapping the action opens a bottom sheet and focuses a local
input. **Send** inserts the entered chunk and clears the local value after a
successful request. Enter and Backspace remain explicit buttons.

The UI does not fetch or display the TV's current field value, identify password
fields, synchronize cursor/selection, or preserve draft text across reloads. A
rejected text command shows a transient, non-sensitive error and leaves D-pad and
keyboard controls usable.

### 6.5 Events and errors

Persistent device state continues through the existing `state` SSE event. Add a
`textInputAvailable` boolean to that state. Asynchronous `RemoteError` responses
are transient and use a separate SSE event so old errors are not replayed as
current device state.

Only text errors belonging to the active connection are emitted. Disconnecting or
replacing a session clears IME counters and availability. A text send that races a
disconnect is reported as unavailable; it is never retried after reconnection.

### 6.6 Testing and acceptance

Extend the fake Shield server to negotiate IME, send field counters, inspect an
outgoing edit, and return a `RemoteError`. Tests cover:

- intersection of supported and implemented feature flags;
- preservation of the truthful v0.3 feature mask and device/client intersection;
- counter updates and invalidation across app changes/disconnects;
- exact outbound protobuf fields for ordinary and Unicode strings;
- empty, stale-field, disconnected, and oversized request rejection;
- absence of submitted text from captured logs and SSE payloads;
- transient error delivery without contaminating `DeviceState`; and
- the browser bottom sheet, focus-by-user-action, send-and-clear behavior, Enter,
  Backspace, and fallback controls.

A real Shield is the final protocol gate. Acceptance requires successful text
entry in the Shield system search plus at least two third-party applications, and
a clear fallback in an application that rejects remote IME.

## 7. Release boundaries and dependencies

```text
v0.3 Reliable appliance
  └── stable server persistence and honest feature surface
        └── v0.4 Touch-first web app/PWA
              └── stable responsive shell and browser transport
                    └── v0.5 Remote text input
```

No release waits for later stages to be useful. v0.3 can ship immediately after
its CasaOS redeploy smoke test. v0.4 does not depend on HTTPS for remote control;
HTTPS only unlocks full PWA installation/caching. v0.5 text entry works over HTTP
and HTTPS because the protocol operation itself does not require a service worker.

Each release gets its own implementation plan and test/commit sequence. If real
Shield testing reveals a protocol incompatibility, that release stops without
pulling later-stage work into the same fix.

## 8. Deferred ideas

After v0.5, candidate feature sets remain:

- multiple Shield/Android TV devices and room switching;
- Wake-on-LAN for fully powered-down devices;
- optional authentication for deployments intentionally exposed beyond a trusted
  LAN;
- voice input, only after cross-platform microphone capture and privacy behavior
  have a separate design; and
- device-identity improvements that survive DHCP address changes.

App launching remains intentionally removed unless Remote v2 gains a reliable,
device-provided way to discover launchable URIs. ADB is not a fallback.

## 9. External protocol and platform references

- Remote v2 app/IME schema:
  <https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto>
- Maintained Remote v2 text implementation:
  <https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remote.py>
- Reference app-link behavior:
  <https://github.com/louis49/androidtv-remote>
- PWA HTTPS/installability requirements:
  <https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps/Guides/Making_PWAs_installable>
- Service-worker secure-context requirement:
  <https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API>
- CasaOS persistent bind-mount convention:
  <https://github.com/IceWhaleTech/CasaOS-AppStore/blob/main/Apps/NginxProxyManager/docker-compose.yml>
