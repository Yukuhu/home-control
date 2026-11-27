# Improvement analysis: the catalogue (2026-11-27)

Every proposal that survived verification, by area. The summary, priorities and roadmap are in [the
analysis](2026-11-27-improvement-analysis.md), which also says how this was done.

Each entry gives impact, effort (S < 2 h, M < 1 day, L a few days, XL more than a week), its kind, how verification left
it, and its status at `main` 3932f9b. Evidence was re-pointed to the line numbers at 3932f9b. A proposal that duplicates
another names the one it was merged into and is not repeated.

| Area | Prefix | Proposals | Merged into another | Partly fixed | Fixed | Rejected |
| --- | --- | --- | --- | --- | --- | --- |
| [Architecture, modularity and code health](#arch) | ARCH | 15 | 2 | 1 | 0 | 1 |
| [Security](#sec) | SEC | 13 | 0 | 0 | 0 | 1 |
| [Device adapters, discovery and device management](#dev) | DEV | 16 | 3 | 1 | 0 | 0 |
| [Content sources, rails, search and playback planning](#src) | SRC | 15 | 3 | 0 | 0 | 1 |
| [Storage, configuration, upgrades and data lifecycle](#data) | DATA | 15 | 4 | 2 | 0 | 1 |
| [Web layer, frontend UX, accessibility and PWA](#web) | WEB | 16 | 3 | 1 | 2 | 0 |
| [Performance, resource use and operability](#ops) | OPS | 12 | 4 | 1 | 0 | 1 |
| [Testing strategy and test quality](#test) | TEST | 15 | 4 | 1 | 0 | 0 |
| [Build, CI/CD, release, supply chain and packaging](#build) | BUILD | 15 | 3 | 0 | 0 | 1 |
| [Documentation and developer experience](#docs) | DOCS | 14 | 5 | 1 | 0 | 0 |
| [Product capabilities and feature opportunities](#prod) | PROD | 15 | 4 | 0 | 0 | 1 |

<a id="arch"></a>

## ARCH: Architecture, modularity and code health

The architecture is in good health and well guarded. `core` (JDK only, 76 classes) and `protocols` (Spring-free, 73
classes) are Gradle modules the compiler enforces. 20 strict ArchUnit rules with no frozen violations cover the rest,
and module independence is checked on the live bean graph. The core model hangs together: `Action`, `Route` and
`PlayableRef` are sealed types with exhaustive pattern-matching switches, the planner is pure and works on a rung
ladder, and `SourceRef`/`DelegatedRoute`/`RouteExecutor` are explicit extension points. Java 25 features (records,
sealed types, record patterns, unnamed variables, virtual threads) are already used idiomatically; `ScopedValue` is the
one remaining fit, and structured concurrency is still preview in 25. Extensibility walk-through: outside its own
package, a new content source touches only `config.Module`, `application.yaml` and, if it stores a credential,
`LoginService.ACCOUNTS`. A new device adapter also needs a core `DeviceKind` constant (stored but never read), a session
with an exhaustive 12-case `Action` switch, a fake in the `protocols` test fixtures, and hand-kept test lists that
otherwise skip it without failing. The main weaknesses: (1) the 2026-10-03 review's R6 registry/rail fixes (DC2–DC5)
were never merged; (2) the device sessions duplicate code and have drifted apart (~50 refusal branches CommandRouter can
never reach, and the push-session skeleton copied 4×); (3) several seams depend on matching strings: workflow validator
text, streaming-service keys, theme hook names, and the 'Workflow changed' sentinel; (4) the build's checks have gaps:
adapters can write the registry, ArchUnit does not cover low-level `java.net`, and all Error Prone warnings are off. Of
the large classes, `GuardedHttpClient` (450 lines) does one job and should stay as it is. `WorkflowSetupController`
(433) and `ThemeCss` (431) mix several concerns. `Enrollment` (463) and `RailCache` (394) are an acceptable size but
contain the open defects.

<a id="arch-1"></a>

### ARCH-1 — Make registry changes atomic and keep I/O and plaintext keys out of them (finish R6: DC2, DC3, DC4)

Impact **medium** · effort **M** · defect · revised by the verifier · **open**

**Problem.** Three registry defects from the 2026-10-03 review are still in the code. (a) Merge and split are not
atomic. Scenario: the disk fills, an fsync fails, or the container is killed between `delete(source)` and
`save(merged)`. The source's adapter entries, including a webOS/Tizen keyRef, disappear from devices.json while the
merged device was never written. Its secret is orphaned in secrets.json, and its live session is never ended, because
`after.remove` runs only after both writes. (b) Forgetting a Bluetooth speaker makes a BlueZ D-Bus call while holding
the registry lock. A slow or hung bluetoothd stalls every discovery merge, pairing and learned-setting write until the
reply timeout. (c) A user who upgrades from a v2 devices.json with the webOS or Tizen module switched off keeps the TV
key in plain text in the live devices.json for as long as the module stays off. This breaks the rule that secrets are
encrypted at rest.

**Proposal.** Finish R6's registry items as one PR. 1. Add one write that covers several devices:
`DeviceRegistry.replace(Collection<Device> saved, Collection<String> deleted)`, done as a single `file.update`. Merge
and split use it. If a core interface change is unwanted, at least save before delete, so a failure leaves a duplicate
instead of a loss. 2. Split forget into a local part under the lock and an I/O part after it: add `default void
afterForget(Device)`, run from AfterLock.run(), and move Bluetooth's BlueZ removal into it. 3. Run the TV-key moves for
every device, whatever its module state. Use an always-on helper that the device package calls from `Enrollment.start`,
built on PairingKeys.migrate. Use the same helper on forget, so a switched-off module's key is also removed (ARCH-V1).
Name the exception in ADR 0002. The registry already knows these two fields in withoutTvKeys, so the ADR should record
both places. 4. Tests: a failure injected between the writes, the lock is free during BlueZ removal, and an upgrade with
webOS off leaves no key in devices.json.

**Trade-offs.** Step 3 puts two adapter ids (webos/clientKey, tizen/token) into an always-on class. That is a narrow
exception to ADR 0002's 'the registry knows no adapter' and should be named in the ADR. The alternative is to refuse to
start, or warn loudly, when a plaintext key belongs to a switched-off module. Step 1 changes a small `core` interface.
All three steps serve the product rules: atomic writes, secrets encrypted at rest, and upgrades in place.

**Evidence:**

- src/main/java/dev/andre/homecontrol/device/Enrollment.java:373-378 (merge: delete then save, connections changed only
  afterwards)
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:416-419 (split: two saves)
- src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java:198-211
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:167-187 (forget runs adapter.forget under the lock)
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerAdapter.java:75-82
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/bluez/DbusBluezClient.java:57-58,167-178
- src/main/resources/application.yaml:114 (bluez-timeout: 45s)
- src/main/java/dev/andre/homecontrol/device/RegistryLock.java:3-6
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:126-139 (migrate only for adapters present)
- src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java:34-56

<a id="arch-2"></a>

### ARCH-2 — RailCache: apply the current refresh interval when a load ends (DC5), and pull the per-rail state machine into its own class

Impact **low** · effort **S** · defect · revised by the verifier · **open**

**Problem.** Scenario: a user changes TMDB's refresh interval from 60 to 15 minutes while a TMDB rail is loading.
`reschedule()` sets dueAt from fetchedAt + 15 minutes, and the finishing load then overwrites it with now + 60 minutes,
so the next refresh comes 45 minutes late. The same thing happens to the failure backoff cap. More generally, the rail
lifecycle (LOADING, in flight, load-again, failure backoff, version numbering) is spread over `start`, `fetch`,
`succeed`, `fail`, `reschedule` and `invalidate`, each changing `Entry` fields under `this`. That makes races like this
hard to see and hard to test without threads.

**Proposal.** Fix DC5. In succeed() and fail(), under `this`, re-read `preferences.refreshInterval(source)` instead of
using the value read before the load. Add a test that changes the interval while a load is held. Extract the per-rail
state machine (RailEntry with pure transitions, tested with a fixed clock) only together with the next RailCache change.

**Trade-offs.** The fix alone is a few lines. The extraction is worth doing only together with the next RailCache
change. No product-rule impact.

**Evidence:**

- src/main/java/dev/andre/homecontrol/content/RailCache.java:296-300
- src/main/java/dev/andre/homecontrol/content/RailCache.java:323-335 (succeed)
- src/main/java/dev/andre/homecontrol/content/RailCache.java:337-350 (fail)
- src/main/java/dev/andre/homecontrol/content/RailCache.java:226-239 (reschedule)
- src/main/java/dev/andre/homecontrol/content/RailCache.java:61-75 (Entry)

<a id="arch-3"></a>

### ARCH-3 — Typed workflow diagnostics instead of reverse-parsing English text in WorkflowSetupController

Impact **low** · effort **M** · risk · revised by the verifier · **open**

**Problem.** The validator's English messages are the API between the domain and the form. Rewording one message, e.g.
'entry subtitle pointer' → 'subtitle pointer', moves the error highlight to the wrong field or drops the guidance text,
and nothing fails at compile time. Rewording the 'Workflow changed' sentinel in WorkflowStore turns a 409 conflict into
a generic field error. Raising MAX_CALLS to 10 makes the binder refuse `calls[8]` (regex), and `charAt(6)` reads only
the first digit of `calls[10]`. The 433-line controller also mixes three jobs: parameter allowlisting, binding, and
mapping errors to fields.

**Proposal.** Step 1 (S): replace the five copies of the 'Workflow changed' literal with a `WorkflowException.changed()`
factory, or a `WorkflowConflictException` subclass the controller catches by type. Build the binder regexes from
WorkflowValidator.MAX_CALLS, MAX_HEADERS and MAX_VARIABLES, and parse the call index properly instead of charAt(6). Add
one parameterised test that runs the real WorkflowValidator over failing drafts and asserts the form field it maps to,
so a reword fails a test. Step 2 (M, optional, alongside the next workflow-editor change): a typed WorkflowProblem
(sealed: Conflict, CallProblem(index, field, code), VariableProblem, ScalarProblem) built by the validator and store.
The controller then maps it with exhaustive switches and splits into binding, error mapping and endpoints.

**Trade-offs.** Touches the validator and the stored-definition check that the codec runs, and needs care to keep stored
files readable (no format change is needed). Tests that assert detail strings must change. No product-rule impact.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupController.java:203-234 (new text match at 223-225)
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupController.java:338-415
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupController.java:44-49,96
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowForm.java:147-148 (new diagnostic text, 5eb0d43)
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowStore.java:26
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTestService.java:88
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowContentSource.java:82
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCastRouteExecutor.java:46
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowValidator.java:15-17,56
- src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupControllerErrorsTest.java:66-113 (mocked store,
  hand-copied diagnostics)

<a id="arch-4"></a>

### ARCH-4 — Collapse the ~50 unreachable 'unsupported action' branches into one refusal built from Action.purpose(), and pin the contract with a test

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** Every refusal branch for an action outside an adapter's declared capabilities is unreachable in production;
only tests call `handle.execute` directly. Even so, each new `Action` means editing 7 sessions and their refusal tests
(commit 25c8f1c added ~200 test lines for them). The texts have drifted into 7 styles, and Bluetooth's would show users
a Java class name if a routing change ever made it reachable.

**Proposal.** Add `Refusals.cannot(Device, Action)` to adapters.support, returning
`UnsupportedActionException(device.name() + " cannot " + action.purpose())`, the router's own wording. In each session,
fold the capability-excluded actions into one multi-pattern case, as Bluetooth already does, and use Refusals.cannot
there; this also fixes Bluetooth's class-name text. Keep specific messages only for sub-cases inside a declared
capability (UPnP without RenderingControl, unsupported MIME types, missing keys). Replace the per-session refusal tests
with one parameterised test: for every adapter and every Action that its constant capabilities do not accept, the
session refuses with the standard text. Do it alongside the next new Action rather than on its own.

**Trade-offs.** A plain `default ->` branch would cut churn further but lose the compiler prompt when an Action is
added; the multi-pattern case keeps it. Hints such as Android TV's 'use the volume keys' are lost, but they are
unreachable today; if they matter, they belong in the capability-aware UI.

**Evidence:**

- src/main/java/dev/andre/homecontrol/device/CommandRouter.java:58-63,117-120,153
- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java:150-179
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:164-171
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:131-148
- src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java:122-137
- src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpSession.java:113-119,133-134
- src/main/java/dev/andre/homecontrol/adapters/sonos/SonosSession.java:161-165,267-268
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java:120-123
- core/src/main/java/dev/andre/homecontrol/core/Action.java:13-22
- src/test/java/dev/andre/homecontrol/adapters/FeaturesMatchCapabilitiesTest.java:41-70

<a id="arch-6"></a>

### ARCH-6 — Adapters hold the writable DeviceRegistry: give them a read-only view and add an ArchUnit rule for registry writes

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** The single-lock rule for registry writes is enforced only by convention. Any adapter or source can inject
DeviceRegistry and call save() or delete(), bypassing RegistryLock, Enrollment's connect bookkeeping and the
DeviceConnections generations. Nothing in the build would object. Today no class outside `device` writes the registry,
so a rule would pass now and catch the first regression.

**Proposal.** (a) Add a strict ArchUnit rule: no class outside dev.andre.homecontrol.device.. calls DeviceRegistry.save
or DeviceRegistry.delete, because every registry write runs under the RegistryLock with the connection bookkeeping. (b)
Optional: give the two TV adapters a read-only view (a core `DeviceLookup` with findById/findAll that
JsonFileDeviceRegistry implements, or a `Supplier<Device>` passed to connect next to LearnedSettings). Do (b) only if
the connect signature changes anyway (ARCH-12).

**Trade-offs.** (a) is trivial. (b) changes DeviceAdapter or the adapter constructors. Injecting DeviceQueries instead
would create a bean cycle (Devices needs the adapters), which is presumably why DeviceRegistry is used.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/DeviceRegistry.java:15-17
- src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java:34-37,44
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsAdapter.java:42,49,106-108
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:331-333
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenAdapter.java:38,45,103-105
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:364-366
- core/src/main/java/dev/andre/homecontrol/core/LearnedSettings.java:5-9
- src/test/java/dev/andre/homecontrol/ArchitectureTest.java:169-175

<a id="arch-7"></a>

### ARCH-7 — Close the ArchUnit gaps for low-level java.net use (sockets, URL.openStream, DNS lookups)

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** java.net.Socket, DatagramSocket, HttpURLConnection, `URL.openConnection/openStream` and
`InetAddress.getByName/getAllByName` are unrestricted, so a source, or web, content or storage code, could reach the
network without address pinning, body caps or a deadline, and the build stays green. A concrete case exists today: a
device registered by host name (e.g. 'shield.local') makes Jellyfin's session matching do an unbounded platform or mDNS
lookup during every preview and play, outside the 'one deadline' of ADR 0005.

**Proposal.** Extend the network rules. Outside adapters and discovery: no dependency on Socket, ServerSocket,
DatagramSocket, MulticastSocket, SSLSocket or HttpURLConnection, and no calls to URL.openConnection or URL.openStream.
Outside adapters and discovery: calls to InetAddress.getByName or getAllByName only from named exceptions, each with its
reason in the rule: device.HostAddresses (pairing only, before the lock), core.Hosts (pairing only). For
sources.jellyfin.JellyfinSessions, either name it or bound the resolver's sessionFor call the way
JellyfinRouteExecutor.findSession already bounds its own, so preview and play cannot wait on a slow lookup of a
host-name device. Literal parsing (InetAddress.ofLiteral, getByAddress) stays allowed.

**Trade-offs.** This follows AGENTS.md: a rule that needs an exception names it, with its reason. The cost is a few
rules and one small Jellyfin change.

**Evidence:**

- src/test/java/dev/andre/homecontrol/ArchitectureTest.java:111-125
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSessions.java:29-31,58-60,127-138
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java:76-88
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutor.java:207-230 (already deadline-bounded)
- src/main/java/dev/andre/homecontrol/playback/PlaybackService.java:74-91,107-125
- src/main/java/dev/andre/homecontrol/device/HostAddresses.java:35-42
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:198-199,342-344 (HostAddresses callers: attach, and merge
  since 55ed395)
- core/src/main/java/dev/andre/homecontrol/core/Hosts.java:56-68
- docs/adr/0005-outbound-http-for-content-sources.md:18-19

<a id="arch-8"></a>

### ARCH-8 — One typed catalogue of streaming services instead of string keys spread over core and the TV adapters

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Service identity is an untyped string defined in at least five places. Scenario: LG renames its Netflix app
id, and a maintainer updates `WebOsLaunches.NETFLIX` to the new id. The switch then stops matching the service key
"netflix", so Netflix title links on webOS silently fall through to the generic `OPEN` target (app home or browser),
with no compile error and no failing test. Adding a service (say Disney+, already in StreamingProviders) means editing
AppLinks, ServiceLinks and every TV launcher, and nothing reports a launcher that was missed.

**Proposal.** Step 1 (S): have AppLinks.serviceOf return the ServiceLinks constants. Switch on ServiceLinks.YOUTUBE,
NETFLIX and PRIME_VIDEO in WebOsLaunches and TizenLaunches, so webOS app ids stay separate from service keys. Add a test
that every key serviceOf can return has a ServiceLinks display name. Step 2 (M, optional, before adding a new service):
a core StreamingService enum (key, display name, optional app home, host matcher). AppLinks, ServiceLinks and the 'Open
in the X app' wording use it, and Action.OpenAppLink carries it, so adapters switch exhaustively instead of parsing the
URI again. StreamingProviders stays a list of subscription keys that may reference the enum. String keys stay at the
persistence boundaries (pins, sources.json), so existing installs upgrade in place.

**Trade-offs.** An enum in core is fine (JDK only). Persisted formats stay unchanged. Some churn across core, the TV
adapters and their tests.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/playback/ServiceLinks.java:16-37
- core/src/main/java/dev/andre/homecontrol/core/playback/AppLinks.java:113-128
- core/src/main/java/dev/andre/homecontrol/core/content/StreamingProviders.java:7-24 (documented superset)
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsLaunches.java:20-22,27-55
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenLaunches.java:29-40
- core/src/main/java/dev/andre/homecontrol/core/playback/Route.java:35-49

<a id="arch-9"></a>

### ARCH-9 — Remove source-specific knowledge from core and security: drop JELLYFIN_CLIENT and live capabilities, move receiver and account labels to their sources

Impact **low** · effort **M** · debt · confirmed · **open**

**Problem.** The ADR 0004 promise is not fully true. A second source that needs a 'live' condition must add a core
Capability, one with its own Cast receiver must edit core.playback.Route to get a readable label, and one with a
credential must edit security.LoginService or its raw id is shown on the Account section. JELLYFIN_CLIENT and the core
liveCapabilities concept are redundant: the presence of JellyfinPlayable.Session, App or Vlc refs already carries that
information.

**Proposal.** Make JellyfinSessionStrategy route on its own refs alone, then remove JELLYFIN_CLIENT and
`Resolution.liveCapabilities` from core. Add a `receiverLabel` to PlayableRef.CastLoad and Route.Cast, as CastMessage
already has, and remove RECEIVER_NAMES. Let each source contribute its account label (e.g. a small `AccountLabel` bean,
or the SetupSection title) instead of LoginService's map. Update ADR 0004 if anything is kept.

**Trade-offs.** Low urgency. Closed enums are otherwise a strength (EnumSet, exhaustive switches). Removing a core
concept also removes tests that pin it. Judgement call.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/Capability.java:26-27
- core/src/main/java/dev/andre/homecontrol/core/playback/PlayableResolver.java:20-29
- src/main/java/dev/andre/homecontrol/playback/PlaybackService.java:107-125
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java:50-64,76-88
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSessionStrategy.java:22-42
- core/src/main/java/dev/andre/homecontrol/core/playback/Route.java:68-90,101-114
- src/main/java/dev/andre/homecontrol/security/LoginService.java:35-37,204-212

<a id="arch-10"></a>

### ARCH-10 — Clean device-supplied fault text in one place before it reaches the browser

Merged into [SEC-1](#sec-1), which covers it. Impact medium, effort S, status open.

<a id="arch-11"></a>

### ARCH-11 — Theme vocabulary: one source of truth tied to the templates, and a ThemeCss split

Impact **low** · effort **S** · debt · confirmed · **open**

**Problem.** The theme API is maintained by hand in two places, with no check against the markup. Six advertised hooks
already style nothing: a theme author who targets `.welcome-card` sees no effect, and app.css carries dead rules. If a
template renames a class, every installed theme silently loses that styling while the compiler still accepts its CSS.

**Proposal.** Move the vocabulary into a resource (e.g. `themes/api-v1.txt`) that ThemeCss loads. Add tests that (a)
every class or ID hook occurs in at least one template or JS module, and (b) the docs' hook list matches the resource,
or generate that docs section. Remove or restore the six dead hooks deliberately, as a theme-API compatibility decision
(docs/dev/themes.md:209). Optionally split ThemeCss into ThemeVocabulary, SelectorCompiler and ValueValidator.

**Trade-offs.** Removing a public hook is an API change for imported themes. Keeping it, documented as deprecated, is
the compatible choice. The tests are cheap; the split is optional.

**Evidence:**

- src/main/java/dev/andre/homecontrol/themes/ThemeCss.java:22-32
- docs/dev/themes.md:205-237
- src/main/resources/static/app.css:240,266,301
- src/main/resources/themes/cyberpunk/theme.css:311

<a id="arch-12"></a>

### ARCH-12 — Remove or use dead model surface: PlayableRef.kindLabel, DeviceKind, the unused connect overload

Impact **low** · effort **S** · debt · revised by the verifier · **open**

**Problem.** Every new SourceRef must implement a method nobody calls, and every new adapter must edit core for a field
nobody reads. The docs describe behaviour (kind labels in explanations, per-kind icons) that does not exist.

**Proposal.** Delete PlayableRef.kindLabel, its six implementations and the tests that pin it. Keep DeviceKind as stored
metadata (removing it would need a devices.json migration), and either fix its Javadoc or use it for per-kind device
icons in the strip and the setup list. Invert the DeviceAdapter.connect overloads: make `connect(Device,
Consumer<DeviceState>, LearnedSettings)` abstract, and give the 2-arg form a default that passes
LearnedSettings.DISCARD. webOS and Tizen then lose their duplicate override, and the other adapters gain a third
parameter they ignore.

**Trade-offs.** Trivial. Keeping DeviceKind costs nothing; using it is a small UI feature.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/playback/PlayableRef.java:14-18
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayable.java:13,27,39,50
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCastRef.java:7
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeLoungeRef.java:12
- src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableTest.java:29-33
- core/src/main/java/dev/andre/homecontrol/core/DeviceKind.java:3-6
- src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java:166-168
- core/src/main/java/dev/andre/homecontrol/core/DeviceAdapter.java:21-34
- src/main/java/dev/andre/homecontrol/device/DeviceConnections.java:105-107
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsAdapter.java:87-99

<a id="arch-13"></a>

### ARCH-13 — Let the compiler check the concurrency conventions: curated Error Prone checks, @GuardedBy, and ScopedValue in GuardedHttpClient

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** The concurrency design is good but enforced only by comments and review; most of the 2026-10-03 defects
were lock or race bugs. Error Prone already runs on every compile, but only its error-level checks count.
GuardedHttpClient binds per-exchange state with a manual set and remove on a ThreadLocal, which is exactly the use
ScopedValue (final in Java 25, JEP 506) was designed for.

**Proposal.** Run Error Prone once with warnings on to count findings per check. Raise to errors only the checks with
few findings and real value, such as FutureReturnValueIgnored, MissingCasesInEnumSwitch, ReferenceEquality and
StringCaseLocaleUsage. Add error_prone_annotations as compileOnly to app and protocols, and replace the 'guarded by'
comments with @GuardedBy, which Error Prone already enforces at error level. core keeps its comments unless a
compile-only exception is named. Be clear that this does not catch I/O under a lock; that stays a review item, or a
targeted test as in ARCH-1 step 4. Leave GuardedHttpClient's ThreadLocal as it is, or switch it to ScopedValue only when
that class is next changed.

**Trade-offs.** Some false positives and a one-off cleanup. `@GuardedBy` adds the error_prone_annotations dependency to
app and protocols, which is allowed, but it does not fit `core` (JDK only) unless named as a compile-only exception.

**Evidence:**

- build.gradle.kts:56-65
- src/main/java/dev/andre/homecontrol/adapters/support/ConnectionSlot.java:18-19
- src/main/java/dev/andre/homecontrol/content/RailCache.java:54
- src/main/java/dev/andre/homecontrol/sources/http/GuardedHttpClient.java:86,90,99,185,199,233
- protocols/src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/RemoteConnection.java:61,63 (new comments,
  04928a4)
- protocols/src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenRemoteConnection.java:134 (new comment,
  aa65caf)
- docs/superpowers/reviews/2026-10-03-codebase-review.md:532-533 (the review's defect themes: non-atomic
  close/in-flight, locks across I/O)

<a id="arch-14"></a>

### ARCH-14 — Retire dependency overrides whose removal condition is met, and stop the documented versions drifting

Impact **low** · effort **S** · debt · confirmed · **partly fixed at 3932f9b**

**Problem.** Dependabot raises Spring Boot but cannot remove the overrides placed for older versions. The Tomcat
constraint is now a no-op. The Jackson raise's own removal condition is met, though dropping it would move Jackson from
3.2.4 back to 3.1.7, which needs a decision. The contributor guide names the wrong Spring Boot version.

**Proposal.** Drop the Tomcat constraints. Decide whether to keep Jackson 3.2.x deliberately and, if so, reword the
comment to say why. Update AGENTS.md. Add a small Gradle check or unit test that reads the resolved BOM and fails when
an override is no longer above the version the BOM manages, so stale overrides surface on the Dependabot PR that makes
them stale.

**Trade-offs.** Trivial, but needs one CI run with dependency verification. The check is optional.

**Since the analysis.** Three parts are fixed. 4d898c9 (Dependabot) raised Tomcat to 11.0.29, above the 11.0.27 that
Spring Boot 4.1.2 manages, so the constraint is no longer a no-op. 432fbd2 rewrote the Tomcat and Jackson comments to
give the current reason (deliberately raised above the managed version) and a removal condition tied to the current
version, which settles the Jackson 3.2.x question. 432fbd2 also corrected AGENTS.md:15 to Spring Boot 4.1.2. Still open:
there is no automated Gradle check or test that fails when an override is no longer above the version the BOM manages.
Stale overrides still depend on someone reading the comments.

**Evidence:**

- gradle/libs.versions.toml:2-11
- build.gradle.kts:6-12,169-177
- protocols/build.gradle.kts:16-20
- /root/.gradle/caches/.../spring-boot-dependencies-4.1.2.pom:83 (jackson-bom 3.1.7), :215 (tomcat 11.0.27)
- AGENTS.md:15 (now 4.1.2)

<a id="arch-15"></a>

### ARCH-15 — An 'add an adapter or source' guide plus a contract-test kit that discovers adapters instead of hand lists

Impact **medium** · effort **M** · docs · revised by the verifier · **open**

**Problem.** A new device adapter must edit: core DeviceKind (and Capability if needed), config.Module,
application.yaml, an adapters/<x> package (Configuration, Properties, Timings, Settings, Adapter, Session with a 12-case
Action switch), a protocols/<x>/protocol package with a Fake in testFixtures, a pairing bean or SetupSection with its
template fragment, and hand-maintained test lists. A new content source must edit config.Module, application.yaml and
LoginService.ACCOUNTS, plus its own package and fragment. None of this is written down, and the test lists fail open.

**Proposal.** (S) Add an 'Adding a device adapter or content source' section to docs/dev/architecture.md listing every
shared file to edit: config.Module, application.yaml, DeviceKind, LoginService.ACCOUNTS, the protocols package with its
Fake in testFixtures, the setup section, and the hand-kept test lists. (S) Make the hand lists fail closed: a test that
imports every DeviceAdapter implementation (ArchUnit's ClassFileImporter over dev.andre.homecontrol.adapters) and fails
when one is missing from FeaturesMatchCapabilitiesTest's list. (M, optional) Grow that into a parameterised session
contract suite: refusals use the standard text (ARCH-4), nothing is published after close, close is idempotent.

**Trade-offs.** Small. It makes the architecture's extension story match what the code already supports.

**Evidence:**

- src/test/java/dev/andre/homecontrol/adapters/FeaturesMatchCapabilitiesTest.java:47-56
- src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java:33-34,86-88
- docs/dev/architecture.md:6-120 (no extension checklist)
- src/main/java/dev/andre/homecontrol/config/Module.java:10-24
- core/src/main/java/dev/andre/homecontrol/core/DeviceKind.java:4-6
- src/main/java/dev/andre/homecontrol/security/LoginService.java:35-37
- core/src/main/java/dev/andre/homecontrol/core/AdapterDiscovery.java:49-56 (new hook, 55ed395)

<a id="arch-v1"></a>

### ARCH-V1 — Forgetting a device whose adapter module is switched off leaves its pairing credentials behind

Merged into [ARCH-1](#arch-1), which covers it. Impact low, effort S, status open.

### Rejected in ARCH

- **ARCH-5 — Compose one PushSession helper for the four push sessions and make their lifecycle differences explicit.**
  The problem rests on the variations being accidental, and the code shows they are deliberate. (1) close() is the same
  in all four sessions: publisher.close, loop.close, connection.close, onClose.run (AndroidTvSession.java:282-287,
  CastSession.java:268-273, WebOsSession.java:342-348, TizenSession.java:344-350). (2) The `closed` flag exists only in
  webOS and Tizen because only they turn POWER into Wake-on-LAN when there is no connection; commit 132aee5 added it on
  purpose so a closed handle never wakes the TV. (3) requireConnected checks status CONNECTED only in Android TV and
  Cast because those put the connection in the slot before the handshake ends (Cast waits for the first RECEIVER_STATUS,
  the AC1 fix). webOS fills the slot only after register() succeeds (WebOsSession.java:242-249), so a status check there
  would be redundant. (4) AC1 (Cast) and AC6 (Android TV, 54e6d44) were protocol-specific fixes, not one skeleton bug
  fixed four times. (5) Tizen has no Reconnector at all (TizenSession.java:99-104 uses a handshake Backoff), and the
  shared wiring is about 20-30 lines per session, not 40-60. A PushSession layer would add an abstraction over a toolkit
  that is already composed (SessionLoop, Reconnector, ConnectionSlot, StatePublisher), for little gain and real
  close-race risk. One part does hold: inverting the DeviceAdapter.connect overloads (DeviceAdapter.java:25-34;
  production calls only the 3-arg form, DeviceConnections.java:105-107; webOS and Tizen override both). That is folded
  into ARCH-12.

<a id="sec"></a>

## SEC: Security

The web edge is well built. The order is: a Host allowlist against DNS rebinding, then a Go-style cross-origin guard
based on Fetch Metadata and Origin, then a login gate that matches exact raw URIs. Sessions are fixed against fixation,
remembered logins keep only hashes, Argon2id has bounded concurrency, the rate limiter is proxy-aware, and AES-256-GCM
secrets are written atomically. sources.http pins DNS answers, caps bodies and strips credentials, which makes it an
unusually disciplined SSRF boundary for a hobby-scale appliance. The remaining risks sit around that core, not in it.
Credentials still leave through side doors: device fault text reaches toasts unredacted (an open question from
2026-10-03, still open), the Jellyfin token is embedded in URLs handed to untrusted renderers, and webOS falls back to
plaintext after any TLS failure, with no certificate pinning for webOS or Tizen. Packaging is the weakest layer. The
Bluetooth variant runs as root next to the host's system D-Bus (a container-escape path), the CasaOS install runs as
root with default capabilities in the host network namespace, and no compose file drops capabilities. Install-time
security is the other main gap. In the default install with no password, any LAN guest can claim the first password and
lock the owner out, or fill the heap with 30-day anonymous sessions. Nothing records security events, so the owner
cannot see either attack. Offline protection of a stolen /data backup depends on a passphrase that has no strength
check, a login-grade KDF, and no way to rotate it.

<a id="sec-1"></a>

### SEC-1 — Redact credentials from device-provided failure text before it reaches toasts and logs

Impact **low** · effort **S** · defect · revised by the verifier · **open**

**Problem.** Product rule: secrets never reach the browser. Scenario: Jellyfin is connected and the user plays a film on
a DLNA TV. The TV's firmware rejects SetAVTransportURI with <errorCode>714</errorCode><errorDescription>Illegal
MIME-type for http://192.168.1.20:8096/Videos/<id>/stream.mkv?static=true&mediaSourceId=…&ApiKey=3f…</errorDescription>.
RendererCommands builds the message "Living room TV refused to play the stream (UPnP error 714: Illegal MIME-type for
http://…&ApiKey=3f…)". ErrorAdvice answers 502 with that text, and the dashboard shows the long-lived Jellyfin token in
a toast. People screenshot toasts and paste them into bug reports. The same applies to Cast custom receivers that echo
the contentId, and to any log line that prints these exceptions. I could not verify which real devices echo the URL; the
code path is certain.

**Proposal.** Move StreamRedaction's text redactor into core, next to RedactedUris (for example
RedactedUris.inText(String)), and make TextWebSocket.withoutQuery delegate to RedactedUris. Use it at the two app-side
funnels that every device refusal passes through: DeviceCalls.run (DeviceCalls.java:36) and RendererCommands.run
(RendererCommands.java:104). Cap device-supplied text at about 300 characters there. Optionally apply it at the protocol
edge too (SoapClient.fault, CastApps.reason, SsapConnection errorText), so log lines are clean as well. Pin it with a
FakeRenderer that echoes CurrentURI in errorDescription and an assertion that the 502 body contains no 'ApiKey='.
Bluetooth keeps working through the moved helper.

**Trade-offs.** Redacting query strings may hide a harmless detail in a genuine device error; that is acceptable.
Redacting at the protocol edge is precise, while the ErrorAdvice net is generic and regex-based. Putting the helper in
adapters.net respects the module rules: protocols depends only on core and its libraries, and the bluetooth package can
import it from there.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinStreams.java:95-100 (ApiKey in stream query)
- src/main/java/dev/andre/homecontrol/adapters/support/RendererCommands.java:38-39,99-107 (fault message copied into
  RendererFaultException)
- protocols/src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/SoapClient.java:111-122 (errorDescription taken
  verbatim)
- protocols/src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/SoapFault.java:29-35
- protocols/src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastApps.java:102,119,150-153 (receiver message
  verbatim)
- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java:206-212 (errorText verbatim)
- src/main/java/dev/andre/homecontrol/adapters/support/DeviceCalls.java:35-36 (refusal message appended)
- src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java:41-44,65-67 (502 body is e.getMessage())
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/StreamRedaction.java:6-22 (text redactor, Bluetooth
  only)
- core/src/main/java/dev/andre/homecontrol/core/RedactedUris.java:5-16
- protocols/src/main/java/dev/andre/homecontrol/adapters/net/TextWebSocket.java:189-191 (duplicate withoutQuery)

<a id="sec-2"></a>

### SEC-2 — Device-flow OAuth: stop echoing Google's error_description and validate verification_url

Impact **low** · effort **S** · defect · revised by the verifier · **open**

**Problem.** The two OAuth paths disagree about echoing Google's text. With an unexpected error code, the device flow
copies Google's free-form error_description onto the Setup page, which the browser path's own comment says may contain
submitted credentials. verification_url is trusted as given. If oauth-base-url points at a mirror or a misbehaving
proxy, Setup would tell the user to enter the Google code at an arbitrary https page, which is phishing for the device
code. The CSP blocks javascript: URLs but not an https link. A malformed URL also makes URI.create throw an
IllegalArgumentException that nothing maps.

**Proposal.** In GoogleOAuthClient.poll's default branch, return Failed(error, "") or a fixed message per known code, as
exchangeCode and failure() do, and keep only the short error code on Setup. As optional hardening, accept
verification_url only when it is https on google.com or www.google.com, and otherwise use DEFAULT_VERIFICATION_URL,
which also avoids an unmapped IllegalArgumentException from URI.create. Add a fake-OAuth-server test with a hostile
description.

**Trade-offs.** Users lose Google's prose for rare errors, but the error code remains for searching. A Google change of
the verification host would need a code update; falling back to the default URL keeps the flow working.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClient.java:123-126,157-166,211-225 (exchangeCode never
  echoes; poll default echoes error_description; failure() does not)
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeAuthorizationService.java:272-273 (description shown on
  Setup)
- src/main/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClient.java:99-103 (verification_url trusted;
  URI.create may throw)
- src/main/resources/templates/fragments/youtube-setup.html:143 (link rendered)
- src/main/resources/application.yaml:228 (oauth-base-url)

<a id="sec-3"></a>

### SEC-3 — Make TV TLS sticky and pin TV certificates, so pairing keys never go out in cleartext or to an impostor

Impact **low** · effort **M** · risk · revised by the verifier · **open**

**Problem.** Scenario (an untrusted LAN host): an LG TV that speaks wss:3001 is paired. An attacker on the LAN
ARP-spoofs the TV's address, or takes it over by DHCP while the TV is unplugged. It resets connections to :3001 and
accepts :3000. Home Control's reconnect treats the TLS failure as 'old firmware', opens ws:// and sends the stored
client-key in the register message in plaintext. The attacker now holds a pairing key that drives the TV: inputs, apps,
power and state. Pairing itself has the same downgrade, so the newly granted key can come back over ws. For Tizen,
whoever answers TLS on :8002 at the TV's address receives the token in the URL, because no certificate is checked.

**Proposal.** Step 0 (S): align the code with the owner's TV5 ruling. Fall back to ws://:3000 only when the wss connect
fails with a refused connection (a ConnectException cause), not after a timeout or TLS failure. Step 1 (M): sticky TLS.
Once SSAP over TLS has worked for a device, including at pairing, record it through LearnedSettings and never fall back
for that device. Re-pairing clears the flag. Step 2 (M, optional): trust on first use. Record the SHA-256 of the TV's
leaf certificate at pairing, or at the first TLS connect after the upgrade, refuse a different certificate, and show
'the TV's certificate changed: pair it again'. Apply the same to Tizen. Existing installs learn the flag and the
fingerprint on their next good connect, which satisfies the in-place upgrade rule.

**Trade-offs.** A firmware update or factory reset that regenerates the certificate forces a re-pair, so the message
must be clear. Some models may ship one self-signed certificate per model, which weakens pinning against someone who
extracts its key from firmware (a judgement call; it still raises the bar a lot). Sticky TLS could strand a TV whose TLS
port stops working, so re-pairing must clear the flag. The webOS fallback ruling is kept.

**Evidence:**

- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java:63-96,104-106 (fallback on
  any IOException; key sent in register)
- docs/superpowers/reviews/2026-10-03-codebase-review.md:555-556 (TV5: fall back only when the TLS port refuses)
- protocols/src/main/java/dev/andre/homecontrol/adapters/net/InsecureTls.java:14-19,42-44 (accept any certificate, none
  pinned)
- protocols/src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenMessages.java:31-38 (token in wss URL)
- protocols/src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/TlsSockets.java:60-72 (Android TV pins, the
  model to copy)
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:50-53 (LearnedSettings available)

<a id="sec-4"></a>

### SEC-4 — Bluetooth variant: running as root with the host system D-Bus is a host-root escape path

Impact **medium** · effort **M** · risk · revised by the verifier · **open**

**Problem.** A read_only bind of /run/dbus only blocks writes to the mount; connect() to system_bus_socket still
succeeds. dbus-daemon authenticates the peer by SO_PEERCRED as uid 0, and with no user-namespace remapping that is host
root. Root on the system bus can call org.freedesktop.systemd1.Manager.StartTransientUnit, which is what systemd-run
does, and run any command as host root. Scenario: a memory-safety bug in mpv or ffmpeg demuxing a hostile stream from a
workflow's remote API, or any JVM library flaw, gives code execution in the container. It is one D-Bus call from full
host compromise, on a box with host networking. The docs present the mount as read-only, which suggests it is safe.

**Proposal.** Run the -bluetooth image as uid 1000 by default, with group_add set to the host's bluetooth gid (an env
var such as HOST_BLUETOOTH_GID, documented per distribution). Document a one-file D-Bus policy drop-in that allows
send_destination=org.bluez for uid 1000, for hosts whose policy allows root only. Uid 1000 also owns
/run/user/1000/pulse, so the audio socket no longer needs root's DAC override. Keep root as a documented fallback that
states plainly what root plus the host system bus means (host root wherever polkit is installed). Change the
ACCESS_DENIED hint in BluezFailures.java:63 to point at group_add and the drop-in first. Validate on Raspberry Pi OS and
Debian or Ubuntu. Record the reversal of the 2026-09-16 decision in the bluetooth docs, or in an ADR if it changes the
supported setup.

**Trade-offs.** The bluetooth gid differs per host, and CasaOS manifests cannot discover it, so an env var or a
documented step is needed. Upstream BlueZ policies and AppArmor setups vary, so this needs validation on real hardware
(the reason for medium confidence). The default image and the default module set are unaffected, since Bluetooth is off
by default.

**Evidence:**

- Dockerfile:32-35,43 (bluetooth image stays root)
- compose.bluetooth.yaml:14-15,24,28 (RUN_AS 0:0, /run/dbus ro, pulse dir)
- casaos/docker-compose.bluetooth.yml:6,17-20,25-27
- docs/user/bluetooth-speakers.md:26-31
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/bluez/BluezFailures.java:63 (ACCESS_DENIED hint)
- docs/superpowers/archive/2026-09-16-bluetooth-speakers.md:89
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvCommandLine.java:15-33

<a id="sec-5"></a>

### SEC-5 — Harden the shipped compose and CasaOS manifests: drop all capabilities, no-new-privileges, read-only root filesystem

Impact **medium** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** On CasaOS the JVM runs as root with Docker's default capability set inside the host's network namespace.
CAP_NET_RAW there lets code that gains execution sniff the host's traffic and ARP-spoof the LAN, for example to
impersonate a TV and harvest pairing keys (see SEC-3). CHOWN, DAC_OVERRIDE, SETUID and SETGID make further escalation
easier. The app needs none of these: its ports are at least 1024, joining a multicast group and broadcasting Wake-on-LAN
need no capability, and nothing pings over ICMP (no isReachable in the code).

**Proposal.** Add cap_drop: [ALL], security_opt: ["no-new-privileges:true"], read_only: true and tmpfs: [/tmp] to
compose.yaml and casaos/docker-compose.yml. /tmp holds java.io.tmpdir, Tomcat's work directory, hsperfdata and the
Bluetooth runtime directory. For the Bluetooth manifests, either sequence this after SEC-4 (non-root), or, while they
stay root, use cap_drop: [ALL] with cap_add: [DAC_OVERRIDE] so the pulse socket stays reachable. Run
scripts/smoke-test-image.sh with the same flags, so a change that needs a capability or a write outside /data and /tmp
fails in CI. Optionally add mem_limit and pids_limit. The app id stays dev.andre.shield-remote.

**Trade-offs.** read_only may surface an unexpected write, such as JVM hsperfdata; the /tmp tmpfs or -XX:-UsePerfData
covers it. The app id dev.andre.shield-remote is unchanged. An alternative for CasaOS is an entrypoint that chowns /data
and drops to uid 1000 with setpriv; that is more moving parts for a smaller gain than cap_drop.

**Evidence:**

- compose.yaml:1-25 (no cap_drop/security_opt/read_only)
- casaos/docker-compose.yml:7,10
- casaos/docker-compose.bluetooth.yml:6,25-27
- compose.bluetooth.yaml:14-15,28
- scripts/smoke-test-image.sh:85
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothProperties.java:45

<a id="sec-6"></a>

### SEC-6 — Bound anonymous sessions: 30-day in-memory sessions with no cap let any LAN client exhaust the heap

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** Scenario in the default no-password install: a guest or a compromised device runs `while true; do curl -s
-X POST -d password=x -d confirmation=y http://host:8080/setup/password/set; done` without keeping cookies. Every
request creates a new Tomcat session holding an unconsumed flash map. Tomcat keeps each one for 30 days, and
StandardManager's maxActiveSessions is unlimited by default. At roughly a kilobyte per session (an estimate), a 1 GB
default heap (25% of a 4 GB Pi) fills after about a million requests, a few minutes at LAN speed. The JVM then fails
with OutOfMemoryError and stays up but unusable. Once a login exists, unauthenticated requests no longer reach
session-creating code, so the exposure is the no-login default.

**Proposal.** Add -XX:+ExitOnOutOfMemoryError to the ENTRYPOINT, so the restart policy recovers from any OOM. Cut
server.servlet.session.timeout to a few hours: remembered-login tokens carry the 30-day login and the gate resumes a
missing session from them. Optionally cap active sessions with a TomcatContextCustomizer. Check that an event stream
whose session idles out reconnects and resumes cleanly; whileLoggedIn closes it, and the next connection resumes from
the token. Keep security.md's '30 days from login' wording, which stays true.

**Trade-offs.** A tab that only holds an event stream can see its session idle out; the stream closes and the next
reconnect resumes it from the token, costing one reconnect. In no-login mode a lost session only loses a pending flash
message or OAuth state. A hard session cap refuses new sessions when full, which is bounded and recoverable but should
be combined with short anonymous timeouts. security.md's '30 days from login' stays true.

**Evidence:**

- src/main/resources/application.yaml:8,14 (session timeout and cookie max-age 30d)
- src/main/java/dev/andre/homecontrol/security/LoginGateFilter.java:55-58
- src/main/java/dev/andre/homecontrol/web/LoginController.java:118-130
- src/main/java/dev/andre/homecontrol/security/LoginService.java:75-77,127-138
- src/main/java/dev/andre/homecontrol/web/ThemeController.java:150-158
- src/main/java/dev/andre/homecontrol/security/RequestLoginContext.java:53-56,82-87
- Dockerfile:44 (ENTRYPOINT without ExitOnOutOfMemoryError)

<a id="sec-8"></a>

### SEC-8 — Add a security audit trail: log logins, lockouts, password changes, pairing and theme changes, and show recent activity in Setup

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** Scenario: overnight, a LAN device brute-forces the login and trips the global 50-attempt cap. In the
morning a new phone gets 'Too many attempts' and the owner has no way to learn why, when or from which address.
Likewise, nothing records that a guest set the first password (SEC-7), that the password was removed, or that a device
was paired or forgotten. An appliance that is meant to be left alone needs a way for its owner to notice abuse.

**Proposal.** Step 1 (S): add a dedicated logger (dev.andre.homecontrol.security.audit) that writes one INFO or WARN
line per event, with the remote address from request.getRemoteAddr() (already resolved by TrustedProxies/RemoteIpValve)
and never a secret or URL. Events: login failed or succeeded, limiter tripped (per address or global, rate-limited so a
flood does not flood the log), first password set, password changed or removed, login reset applied, theme installed or
removed, device paired or forgotten, source connected or disconnected. Also make the 429 message say when the global cap
is the cause. Step 2 (M, optional): keep the last 100 events in a VersionedJsonFile under /data, written atomically and
versioned from the start, and show them as 'Recent sign-in activity' under Setup → Account.

**Trade-offs.** LAN IP addresses appear in logs and in /data, which is fine on an appliance and covered by the existing
/data guidance. The JSON file adds a new /data format, versioned from the start. Logging alone is S; the Setup panel
brings it to M.

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/LoginController.java:62-96,144-169
- src/main/java/dev/andre/homecontrol/security/LoginService.java:215-294
- src/main/java/dev/andre/homecontrol/security/LoginRateLimiter.java:40-51
- src/main/java/dev/andre/homecontrol/web/ThemeController.java:88-105
- src/main/java/dev/andre/homecontrol/web/SetupController.java:103

<a id="sec-9"></a>

### SEC-9 — Protect HOME_CONTROL_SECRET against offline guessing: a strength floor, a stronger KDF and migration of existing files

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** The stolen-backup threat model rests on the passphrase. Scenario: an owner follows the guide and sets
HOME_CONTROL_SECRET=garage42. A thief with the /data backup tests guesses offline against the GCM tag, using Argon2id at
login-grade parameters chosen to be fast per request. At these parameters a short dictionary passphrase falls quickly
(my estimate is thousands of guesses per second per GPU; a judgement call). That exposes every token, the YouTube
refresh token and client secret, the TV pairing keys and the keystore password. The derivation runs once per start, so
the login-grade cost buys nothing at runtime.

**Proposal.** When a new passphrase header is created (newPassphraseHeader, so a fresh install or a key-file→passphrase
migration), refuse a HOME_CONTROL_SECRET shorter than 16 characters with a clear startup error. Where a passphrase
header already exists, log a WARN instead. Raise the KDF to at least 64 MiB, t=3 for new headers. In the SecretStore
constructor, re-encrypt when the stored header's cost is below the current constants, as the key-file migration already
does. Document `openssl rand -base64 32` in security.md.

**Trade-offs.** Startup gains up to about a second on a Pi and a one-off 64 MiB heap spike. Refusing a weak passphrase
outright on existing installs would break the in-place upgrade rule, hence warn-only there.

**Evidence:**

- src/main/java/dev/andre/homecontrol/storage/SecretKeySource.java:27-29,69-71,95-109,111-125
- src/main/java/dev/andre/homecontrol/storage/SecretStore.java:57,67-76,263-278
- docs/user/security.md:33-38

<a id="sec-10"></a>

### SEC-10 — Deliver the secrets key as a file and support rotating it

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Environment variables show up in `docker inspect`, in CasaOS's app settings screen, in /proc/1/environ, and
in compose files that end up in the same backups the passphrase is meant to protect against. If the passphrase or
secret.key leaks, the owner cannot rotate it without losing every stored secret, including the TV pairings.

**Proposal.** (1) S: document, with a test, delivering the passphrase as a Compose secret through Spring's configtree
(SPRING_CONFIG_IMPORT=optional:configtree:/run/secrets/ plus a secret file named HOME_CONTROL_SECRET), and tell compose
users not to put the value in compose.yaml next to ./data. (2) M: rotation through HOME_CONTROL_SECRET_PREVIOUS. At
startup, decrypt with the previous value, re-encrypt atomically with the current one (AtomicFiles gives old-or-new), log
it, and fail clearly when neither value opens the file. Add an equivalent switch that rotates secret.key. Tell the user
to remove the previous value after one start.

**Trade-offs.** Adds configuration surface and one more startup migration to test. Rotation must stay crash-safe;
AtomicFiles already gives old-or-new. The previous value should be removed after one start, as with the reset-login
marker pattern.

**Evidence:**

- src/main/resources/application.yaml:148-150
- compose.yaml:16-17
- docs/user/security.md:33-38
- src/main/java/dev/andre/homecontrol/storage/SecretKeySource.java:73-103
- src/main/java/dev/andre/homecontrol/storage/SecretStore.java:67-76

<a id="sec-11"></a>

### SEC-11 — Stop handing the long-lived Jellyfin token (an admin key in API-key mode) to renderers inside stream URLs

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** Scenario: an item is played on a cheap DLNA TV or speaker, or through VLC on the Shield. The device
receives http://nas:8096/Videos/<id>/stream?...&ApiKey=<token>. The firmware may log it or send it home, VLC keeps it in
its history, and plain-http LAN traffic to Jellyfin can be sniffed. The token does not expire until it is revoked. It
gives full API access as that user (all libraries, settings, playback state), or full administrator rights in API-key
mode (create users, delete media, change server configuration). Each untrusted renderer on the LAN thus gets a
long-lived, broad credential.

**Proposal.** (a) S: next to the 'Sign in with' choice on Jellyfin setup, and on the connected summary when the mode is
API key, say that the key is handed to TVs, speakers and VLC inside stream URLs and gives administrator rights, and
recommend switching to a user login. (b) S: in sources.md, say that in user-login mode, disconnecting or reconnecting
revokes the old token on the server (already implemented), and that an API key must be revoked in Jellyfin's dashboard.
Keep per-play proxied capability URLs as a separate idea only if a user asks for it; it needs its own ADR amending 0005.

**Trade-offs.** Option (b) routes media bytes through the appliance. That is fine on a gigabit LAN but costs CPU on
small boards. It also needs a streaming mode outside GuardedHttpClient's body cap and deadline, which amends ADR 0005
and needs a new ADR. The login gate would need a strict raw-URI pattern for the capability path, mindful of the earlier
C1 bypass. The Jellyfin Cast receiver route needs the access token by Jellyfin's own design and is out of scope.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinStreams.java:95-100 (ApiKey in stream URL)
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinVlcExecutor.java:125-127 (token handed to VLC)
- src/main/java/dev/andre/homecontrol/adapters/support/RendererCommands.java:38-39
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSetupService.java:86-98,114-126,154-160,173-179 (connect,
  API-key grant, disconnect, revokeQuietly)
- src/main/resources/templates/fragments/jellyfin-setup.html:63-67 (Sign in with: no warning)
- docs/user/sources.md:146-149 (mentions Cast only; no revocation note)

<a id="sec-12"></a>

### SEC-12 — Refuse calendar redirects that lead from a public server into the LAN

Impact **low** · effort **S** · risk · confirmed · **open**

**Problem.** Scenario: the household subscribes to a public fixtures feed at https://fixtures.example.org/club.ics. The
feed host, compromised or malicious, answers 302 Location: http://192.168.1.1/cgi-bin/<action> or
http://192.168.1.40/api/reboot. Home Control sits inside the LAN and follows the redirect on every 6-hourly refresh and
every Setup check. A remote party has turned the appliance into a blind GET proxy into the LAN: CSRF against routers and
IoT devices with side effects on GET, and port probing by timing. A user who types a LAN calendar URL is trusting the
LAN; a public host choosing a LAN target is a different trust decision.

**Proposal.** In GuardedHttpClient, record whether the first hop's approved addresses were all public, meaning not
loopback, site-local, ULA, CGNAT 100.64/10 or link-local. In CHECKED mode, refuse a redirect whose addresses are not
public, with the message 'a public server redirected into your network'. This mirrors browsers' Private Network Access.
LAN-to-LAN and public-to-public redirects keep working.

**Trade-offs.** A public URL that legitimately redirects to a LAN mirror would break; this is rare, and the user can
enter the LAN URL directly. A small change in sources.http, worth one line in ADR 0005's consequences. Workflows, which
follow same-origin redirects only, and the API sources, which follow none, are unaffected.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcher.java:35-38
- src/main/java/dev/andre/homecontrol/sources/http/GuardedHttpClient.java:231-259,265-276
- src/main/java/dev/andre/homecontrol/sources/http/OutboundAddressPolicy.java:68-72
- docs/user/security.md:83,96-97

<a id="sec-13"></a>

### SEC-13 — Login abuse: a global lockout any LAN host can trigger, and no screening of common passwords

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** Scenario: an untrusted LAN device uses 10 addresses, from IPv4 aliases or IPv6 privacy addresses (one /64
gives endless addresses), and sends five wrong passwords from each. The global counter reaches 50, and every browser,
including the owner's with the right password, gets 429 for 15 minutes. Repeating every 15 minutes blocks new logins and
password changes, the obvious remedy, indefinitely. Browsers already logged in keep working through remembered tokens.
Separately, '1234567890' or 'password12' passes the policy. At up to 4,800 guesses a day, a 10k-entry common-password
list is exhausted in about two days.

**Proposal.** (1) S: in checkNewPassword, refuse passwords found on a small embedded list of common passwords of 10 or
more characters. (2) S, with SEC-8: log every lockout, and say in the 429 message when the global cap, not this address,
is the cause. (3) Optional M, only if lockouts are seen in practice: OWASP's device-cookie pattern, which exempts
browsers that logged in before from the global cap. Add rehash-on-login in the same change that raises the Argon2
defaults, not before.

**Trade-offs.** Adds one more persistent browser token. A stolen device cookie buys only guessing budget, not access.
The blocklist adds about 100 KB of resources. Keying IPv6 by /64 is not a fix on a home LAN, where every host shares one
/64.

**Evidence:**

- src/main/java/dev/andre/homecontrol/security/LoginRateLimiter.java:40-51
- src/main/resources/application.yaml:157-159
- src/main/java/dev/andre/homecontrol/web/LoginController.java:62-76,144-150
- src/main/java/dev/andre/homecontrol/security/LoginService.java:147-157
- src/main/java/dev/andre/homecontrol/security/Argon2PasswordHasher.java:32-71

<a id="sec-14"></a>

### SEC-14 — Resource-isolation policy for cross-site GETs, plus CORP, COOP and Permissions-Policy headers

Impact **low** · effort **S** · improvement · confirmed · **open**

**Problem.** A malicious site the household visits can include <script src="http://192.168.1.N:8080/themes/catalog.js">
for N = 1..254. The address that loads sets globalThis.homeControlThemes in the attacker's page, revealing that and
where Home Control runs, plus the installed theme names (XSSI and LAN fingerprinting). In a password-less install it can
also fire cross-site GETs (search results, artwork, route previews) that make the server do work. Browsers'
local-network-access prompts mitigate this only partly and not everywhere.

**Proposal.** In CrossOriginFilter, refuse a GET or HEAD carrying Sec-Fetch-Site: cross-site unless Sec-Fetch-Mode is
navigate and Sec-Fetch-Dest is document (a resource-isolation policy). Requests without Fetch Metadata (curl, the health
check) still pass. Add Cross-Origin-Resource-Policy: same-origin, Cross-Origin-Opener-Policy: same-origin and a
restrictive Permissions-Policy (camera, microphone, geolocation and others set to ()).

**Trade-offs.** Cross-site top-level navigations must keep working (bookmarks, and Google's OAuth callback, which
arrives as a navigation from accounts.google.com). PWA manifest and icon fetches are same-origin. The value is low where
a login exists, because Lax cookies are not sent on cross-site subresource requests.

**Evidence:**

- src/main/java/dev/andre/homecontrol/security/CrossOriginGuard.java:32-35
- src/main/java/dev/andre/homecontrol/security/CrossOriginFilter.java:66-71
- src/main/java/dev/andre/homecontrol/web/ThemeAssetsController.java:32-37
- src/main/java/dev/andre/homecontrol/security/LoginGateFilter.java:33-38
- docs/user/security.md:19-21

### Rejected in SEC

- **SEC-7 — Require a one-time claim code to set the first login password.** The code path is accurate: without a login
  every request passes (LoginGateFilter.java:55-58), and /setup/password/set is open and first-come
  (LoginController.java:118-130, LoginService.java:215-229). The proposal still does not pay for itself in this product.
  (1) The no-password stance is documented and deliberate (security.md:5-9). Anyone on the LAN can already control and
  remove devices, so a guest setting a password is not a privilege boundary being crossed. (2) Recovery is complete and
  documented (security.md:40-45): HOME_CONTROL_RESET_LOGIN removes the password and the credentials the guest connected,
  and the TV pairings stay. (3) A claim code would put a 'read the container log' step in front of every new install,
  CasaOS ones included, and into the content-source connect flow (permitSecrets/storeSecrets). That is a large
  onboarding cost against a prank-level threat. (4) First-come owner setup is the norm for comparable LAN appliances
  (Home Assistant onboarding, the Jellyfin setup wizard). Logging who set the first password, as part of SEC-8, gives
  the owner visibility at a fraction of the cost.

<a id="dev"></a>

## DEV: Device adapters, discovery and device management

The device layer is in good shape structurally: every session is built from one small toolkit (SessionLoop on a virtual
thread, Reconnector, ConnectionSlot, StatePublisher). Callbacks hop onto the session's loop and drop events from
replaced connections. DeviceConnections keeps connect, close and publish outside every lock, and almost every network
call has a hard deadline and a size cap. The 2026-10-03 fixes for adapter races, SSDP hardening and TLS-first webOS are
in place and hold up. Several items are still open, though. The owner-approved Android TV identity work (AC2/R7) and the
registry/keystore hygiene batch (DC2, DC3, DC4, AC8) never landed, and the UNPAIRED latch timing (AC5) is unchanged. Two
of the open questions hold: Tizen hard-codes wss, so pre-2018 sets cannot connect even by configuration, and mDNS picks
its bind address from getLocalHost and skips jmDNS's own loopback avoidance. A wider problem is identity: most adapters
treat the IP address as the device's identity. Cast is the exception and follows an unauthenticated mDNS id to any
address, which goes against the policy UPnP and Sonos enforce. On capabilities, the protocol code already supports more
than users can reach: Cast pause/play payloads, Remote v2 IME and extra keycodes, the webOS text and app-list
permissions, and Tizen's installed-app list. Text input, an app launcher, channel and source keys, and per-adapter
diagnostics are the clearest gaps.

<a id="dev-1"></a>

### DEV-1 — Choose the mDNS interface by routing, not getLocalHost, and make it configurable

Impact **medium** · effort **S** · defect · revised by the verifier · **open**

**Problem.** With host networking on Debian, Ubuntu or Raspberry Pi OS (CasaOS's usual hosts), /etc/hosts maps the
hostname to 127.0.1.1, so getLocalHost() succeeds with a loopback address and MdnsBrowser passes it to JmDNS. Passing an
explicit address skips jmDNS's own no-argument path, which (from my reading of the jmDNS HostInfo source; please verify
against 3.6.5) replaces a loopback getLocalHost with a real interface. What happens next depends on the host. (a) For
127.0.1.1, NetworkInterface.getByInetAddress probably finds no interface, so jmDNS joins on the default route and
discovery works by accident. (b) For a hostname mapped to 127.0.0.1, it binds to lo and Android TV and Cast discovery
finds nothing, silently. (c) Where the hostname resolves to a VPN, docker0 or second-NIC address, it listens on the
wrong network. The only override, the net.mdns.interface system property, is undocumented. A smaller related issue:
Android TV discovery uses the first resolved address, so an AAAA-first resolution yields an fe80:: host, which breaks
reconnect matching (AndroidTvAdapter.java:40) and becomes the device id if paired.

**Proposal.** In bindAddress, treat a loopback or any-local getLocalHost() result like a failure. Better still, choose
the address the kernel routes 224.0.0.251 from: connect a DatagramSocket to 224.0.0.251:5353 and read getLocalAddress(),
with selectFallback as the last resort. Do not just pass null to JmDNS: its own substitute takes
NetworkTopologyDiscovery's first address, which can be docker0 or IPv6. Add home-control.discovery.interface (an address
or NIC name) that feeds the existing configured branch. Keep net.mdns.interface working. Log the chosen address and
interface at INFO on start, and document the setting in docs/user/devices.md. Make Android TV discovery use
MdnsService.host(). The problem statement should say that with a 127.x bind address jmDNS sends its queries from a
loopback-bound socket, so active queries never reach the LAN: discovery depends on devices announcing themselves or on
other clients querying. bindAddress is pure, so unit tests can cover the selection.

**Trade-offs.** Small and local. JmmDNS on every interface would also answer on docker0 and VPN interfaces, so prefer
the routing-based single choice plus the explicit override. Fits all product rules; no /data change.

**Evidence:**

- src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java:101-102 — JmDNS.create(bindAddress(net.mdns.interface,
  getLocalHost, ...))
- src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java:111-118 — loopback getLocalHost() used as-is
- src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java:141-148 — selectFallback only on UnknownHostException
- jmDNS 3.6.5 HostInfo.newHostInfo / JmDNSImpl.openMulticastSocket — unchanged dependency behaviour
- src/main/java/dev/andre/homecontrol/adapters/androidtv/MdnsDiscovery.java:87-91 — addresses[0] instead of
  MdnsService.host() (MdnsBrowser.java:51-56)
- compose.yaml:11, casaos/docker-compose.yml:7 — network_mode: host

<a id="dev-2"></a>

### DEV-2 — Learn and remember each TV's channel: ws:8001 for pre-2018 Samsung sets, sticky TLS for LG

Impact **medium** · effort **M** · defect · revised by the verifier · **open**

**Problem.** Samsung sets from before 2018 (Tizen 2016/2017, without TokenAuthSupport) offer the remote channel only as
plain ws on 8001. Every pairing attempt and every session connect fails the TLS handshake, so these TVs cannot be used
at all. Setting port 8001 does not help, because the scheme is fixed. On LG, the owner's ruling (review :555) says fall
back to ws:3000 only when the TLS port refuses. The code falls back on any failure, including a timeout, and forgets
that a TV once answered over TLS. An on-path host that blackholes 3001 therefore gets the client key in clear on every
reconnect, and a TV known to be plain-only pays a doomed wss attempt (up to twice the connect timeout) on every retry.

**Proposal.** Tizen: give TizenMessages.remoteUri a scheme parameter. At pairing, read rest.deviceInfo before opening
the channel: use wss:8002 when TokenAuthSupport is "true", and ws:8001 otherwise or when the wss handshake fails while
REST answers. Store the choice in the tizen adapter settings (e.g. channel=ws|wss plus channelPort). Sessions default to
today's wss/8002 when the key is absent, so existing installs need no migration. Add a plain mode to FakeTizenServer
with pairing and session tests, and name the supported model years in docs/user/devices.md. Treat the ws:8001 behaviour
as unverified until someone checks it on a 2016/2017 set. webOS: drop the sticky-TLS change as a security measure. With
InsecureTls it does not stop an active attacker. If it is wanted, pair it with an explicit decision on certificate
pinning at first TLS success (TOFU), which needs on-device evidence that LG certificates survive firmware updates.
Optionally, remember that a TV is plain-only so a reconnect skips the wss attempt; this is a small latency gain, not a
security fix.

**Trade-offs.** Adds two adapter settings keys; with defaults that match today's behaviour, no migration is needed.
Sticky TLS could lock out a TV whose firmware later drops 3001, which is unlikely; a re-pair clears it. Respects the
names-never-change rule (the client name and keys are untouched).

**Evidence:**

- protocols/src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenMessages.java:31-38 — remoteUri always wss
- protocols/src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenRest.java:49 — TokenAuthSupport parsed,
  unused
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenProperties.java:22 — global port, no scheme
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:254 and TizenPairing.java:59 — open the wss
  channel
- protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/tizen/protocol/FakeTizenServer.java:67 — TLS-only fake;
  FakeWebSocketServer.plain at
  protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/net/FakeWebSocketServer.java:120
- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java:83-94 — ws fallback on any
  wss IOException
- protocols/src/main/java/dev/andre/homecontrol/adapters/net/InsecureTls.java:14-35 — trusts any certificate

<a id="dev-3"></a>

### DEV-3 — Stable device identity across address changes, verified by the stored credential (AC2/R7 and beyond)

Impact **high** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Example: a Shield, an LG TV or a Sonos room gets a new DHCP lease. Its session retries the old address
forever (Reconnector backoff up to 60 s). Re-pairing creates a second device: Android TV gets a new id and alias, the
old entry stays DISCONNECTED holding any merged Cast entry, and the two cannot be merged. webOS/Tizen get a new keyRef,
and the old one leaks until Forget. Sonos and UPnP appear again under Ready to add. The user's only tools are Forget and
re-pair; there is no way to edit an address or a name.

**Proposal.** Ship in three steps. (1) AC2 as ruled (M): a re-pair whose server certificate matches a registered
certificateFingerprint lands on that entry, keeping its id and alias with the new host. Optionally re-point the entry on
an mDNS announcement, after a pinned handshake at the new address (TlsSockets already fails on a mismatch). (2) Rename
and Change address on the device's Setup entry (M), through Enrollment under the registry lock and followed by a
reconnect. This alone gives every adapter a recovery path without re-pairing. (3) A generic identity hook in
AdapterDiscovery (L): identity(Device) and identity(DiscoveredDevice), meaning the Android TV fingerprint, the SSDP USN
uuid for webOS/Tizen, the RINCON uuid for Sonos, the UDN for UPnP and the Cast id. It replaces reconnectIfMoved's
literal keys. webOS and Tizen first need that uuid learned into their settings (via LearnedSettings when an SSDP
announcement matches the registered host), so existing installs upgrade in place. Re-point automatically only after a
credential proof that cannot prompt (Android TV pinned TLS). For webOS, probe only after a uuid match, and treat a
PROMPT answer as 'not proven' (close at once). For Tizen, UPnP, Sonos and Cast, offer '<name> answers at <new address> —
Move' on Setup and re-point on confirmation. Rate-limit probes per identity. Ids stay opaque, so devices.json needs no
migration.

**Trade-offs.** Ids stay as they are (opaque after creation), so devices.json needs no migration and existing installs
upgrade in place. A probe costs one handshake per announcement of a moved device; rate-limit it per identity. The
confirmation step keeps UPnP's current refusal to follow a UDN silently (UpnpDiscovery.java:57-62). Large but
well-bounded; split into AC2 first (M), then the generic hook and the Setup UI.

**Evidence:**

- src/main/java/dev/andre/homecontrol/adapters/androidtv/PairingService.java:72-73,185-187 — id and alias from the host
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:363-365 — id-bound pairing cannot be merged
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:367-370 — merge now refuses an address-dialling entry at
  another address (55ed395)
- src/main/java/dev/andre/homecontrol/device/DeviceMatching.java:33-46 and
  src/main/java/dev/andre/homecontrol/adapters/support/PairingKeys.java:53-62 — host-only matching for webOS/Tizen
- core/src/main/java/dev/andre/homecontrol/core/AdapterDiscovery.java:37-39 — default carries is a host match
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:321-331 — reconnectIfMoved compares literal host/port keys
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSettings.java:17-20,
  src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSettings.java:18-21 — no stable identity stored
- docs/superpowers/reviews/2026-10-03-codebase-review.md:551-554 — R7 ruling
- core/src/main/java/dev/andre/homecontrol/core/DeviceEnrollment.java — no rename/change-address

<a id="dev-4"></a>

### DEV-4 — Stop silently re-pointing a Cast entry to whoever announces its mDNS id

Impact **medium** · effort **M** · risk · revised by the verifier · **open**

**Problem.** Any host on the LAN can multicast _googlecast._tcp with TXT id=<the living-room TV's id>. Enrollment
re-points the registered Cast entry to that host, saves it to devices.json and reconnects there. The next Jellyfin play
on that device sends a CastMessage containing the Jellyfin accessToken (and Default Media Receiver loads send stream
URLs with ApiKey) to the impostor. Nothing is shown to the user, and the genuine receiver's next announcement flips it
back, so the hijack can be timed. The code base already treats this exact attack as unacceptable for UPnP and Sonos.

**Proposal.** Short term (M): let discovery move a Cast entry or merge one in automatically only when the announcement
comes from the device's own address, or from an address its stored hostname resolves to. When the TXT id or the name
matches but the address does not, log a WARN and show it on Setup as 'Move <device> to <address>' or 'Add to <device>',
applied on confirmation. This covers both the re-point path (reconnectIfMoved) and the by-name merge path
(DeviceMatching.owner in onDiscovered and absorbAddable). Optionally re-point without asking when the current handle is
not CONNECTED, but say plainly that this only narrows the window. Update docs/user/devices.md:80-83 and 95-99. Longer
term (L, amend ADR 0001): Cast device authentication, i.e. the deviceauth AuthChallenge verified against the Cast root
CAs, before any silent re-point. It is a judgement call on how far the LAN is trusted, but the app already enforces this
policy for UPnP and Sonos.

**Trade-offs.** The short-term guard narrows the attack (the real receiver must be down) but does not remove it, so this
is a judgement call on threat model; the guide already treats the LAN as semi-trusted. Device auth brings Google's root
certificates into the image and some protocol work. Either is better than the current inconsistency.

**Evidence:**

- src/main/java/dev/andre/homecontrol/adapters/cast/CastAdapter.java:92-100 and CastSettings.java:41-44 — id match from
  any address
- src/main/java/dev/andre/homecontrol/adapters/cast/CastAdapter.java:77-81 — remembersOwnHost() true keeps name merges
  on for Cast (55ed395)
- src/main/java/dev/andre/homecontrol/adapters/cast/CastSettings.java:36-39 — the Cast entry keeps its own host
- src/main/java/dev/andre/homecontrol/adapters/cast/CastDiscovery.java:59-62 — event only when the instance's entry
  changes
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:298-310,321-331 — re-point and by-name merge without checks
- src/main/java/dev/andre/homecontrol/device/DeviceMatching.java:54-59,74-85 — owner() falls back to a unique name match
  when byName
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinCastMessages.java:43 — accessToken in the Cast message
- src/main/java/dev/andre/homecontrol/adapters/upnp/UpnpDiscovery.java:57-62, UpnpSession.java:150-155 — the opposite
  policy
- docs/user/devices.md:80-83,95-99 — promises automatic name merge and pickup

<a id="dev-5"></a>

### DEV-5 — Pause, resume (and seek) for whatever a Cast receiver plays — the protocol code is already there

Impact **medium** · effort **M** · feature · revised by the verifier · **open**

**Problem.** A Chromecast or Nest speaker shows "now playing", including casts started from a phone
(docs/user/devices.md:93), but the drawer offers only Volume, Mute and Stop casting. There is no pause or resume, and no
position control, even though the receiver accepts these on the transport the session already follows.

**Proposal.** Keep the followed media transport id and the last mediaSessionId in volatile fields, written on the loop
from onMediaStatus and cleared by followMedia and handleDisconnect. Add a CastApps.mediaCommand(transportId, payload)
that sends on the media namespace and waits, within the command timeout, for the MEDIA_STATUS reply (or an error) with
the matching requestId. Implement Pause and Resume as PAUSE/PLAY on it. Throw UnsupportedActionException("nothing is
casting") when no media session is followed, so CommandRouter falls through to a UPnP renderer on the same device.
Either add CAST_RECEIVER to Pause/Resume.requires(), or introduce a MEDIA_TRANSPORT capability (Cast, UPnP, Sonos,
Bluetooth) and drive the dashboard's pause/resume controls from it. Test against FakeCastReceiver, including a
phone-started cast. Seek can follow later (M).

**Trade-offs.** Routing order matters on multi-adapter TVs: the first adapter that accepts gets the command, so Cast
must decline cleanly when idle. Small and additive; no persistence change.

**Evidence:**

- src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java:134-135 — Pause/Resume unsupported
- src/main/java/dev/andre/homecontrol/adapters/cast/CastSession.java:72-76,338-343 — followed transport and loop-only
  lastMedia
- protocols/src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastPayloads.java:88-98 — builders unused outside
  tests
- protocols/src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastApps.java:45-109 — no media request/reply
  helper
- protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/cast/protocol/FakeCastReceiver.java:28-29,385-391
- core/src/main/java/dev/andre/homecontrol/core/Action.java:153-177
- src/main/java/dev/andre/homecontrol/web/DashboardController.java:57-58

<a id="dev-6"></a>

### DEV-6 — Redact device-supplied failure text before it reaches the browser

Merged into [SEC-1](#sec-1), which covers it. Impact low, effort S, status open.

<a id="dev-7"></a>

### DEV-7 — Make the Android TV UNPAIRED latch time-based and self-healing (AC5 still open)

Impact **medium** · effort **S** · defect · confirmed · **open**

**Problem.** After a power cut, a Shield that accepts TCP and TLS but resets connections for more than about 15 s while
booting (the window the comment itself describes as up to about 30 s) is latched UNPAIRED for good. The user is told to
re-pair, and no announcement or later success clears it until Home Control restarts. Unlike Tizen, a refused Android TV
connection shows nothing on the TV screen, so the "never retry" caution protects nothing here.

**Proposal.** Latch only when there are at least 5 consecutive ambiguous verdicts and at least N seconds (for example
60) have passed since the first. Then, instead of stopping, keep a slow probe (for example every 10 minutes, and on each
mDNS announcement) with the same credential. A configure/active exchange clears UNPAIRED and resumes normal backoff. A
fingerprint mismatch still latches at once with no probe. Fix the Javadoc either way.

**Trade-offs.** One extra TLS attempt per 10 minutes for a truly de-paired device, which is negligible. The UI keeps
showing UNPAIRED during probing, so the advice to re-pair is unchanged.

**Evidence:**

- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java:46-58 — 'about half a minute' comment,
  threshold 5
- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java:123-129,239-251,323-328
- src/main/java/dev/andre/homecontrol/adapters/support/Reconnector.java:62-80 — stop is final; reconnectNow resets the
  backoff
- src/main/java/dev/andre/homecontrol/adapters/support/Backoff.java — 1 s doubling

<a id="dev-8"></a>

### DEV-8 — Do not hold the SSAP connection's monitor across the pointer-socket request and connect

Impact **low** · effort **S** · risk · confirmed · **partly fixed at 3932f9b**

**Problem.** Suppose a TV hangs on getPointerInputSocket (it reboots, or a firmware stall) while the user presses d-pad
keys. The first press holds the monitor for up to 10 s, and every further press queues behind it and makes its own 10 s
request, so five presses answer after about 50 s. When the liveness check or the WebSocket close reports the loss,
lost() on the session loop blocks in close() behind the monitor. A session close() from DeviceConnections (re-pair,
forget, shutdown) blocks the device's turn the same way.

**Proposal.** Open the pointer socket as a single-flight CompletableFuture<TextWebSocket> held in an AtomicReference.
Concurrent presses share one attempt, and failure clears it. Make close() lock-free: swap out pointer and socket
atomically, then close them. Keep the per-send ordering that TextWebSocket.send already synchronizes.
FakeSsapServer.ignoreRequests(POINTER_INPUT_SOCKET) makes this easy to test.

**Trade-offs.** Small internal change in one protocol class; no behaviour change for a healthy TV.

**Since the analysis.** aab4fe8 made close() unsynchronized: it first fails every pending request (so a press waiting on
getPointerInputSocket gives up at once and queued presses fail fast via closedReason), then closes the main socket
outside the monitor (SsapConnection.java:289-307). lost() and DeviceConnections close no longer wait out 10 s requests.
Two things remain. (1) button() is still synchronized, with no single-flight pointer open (SsapConnection.java:160-180).
While a TV hangs on getPointerInputSocket without the connection closing, each press still queues and makes its own 10 s
request, so five presses still take about 50 s. (2) close() still takes the monitor to close the pointer
(SsapConnection.java:302-306), so a close during a press's pointer TextWebSocket.connect still waits up to
connectTimeout. The pointer is not swapped out atomically.

**Evidence:**

- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java:160-180 — synchronized
  button() opens the pointer socket
- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java:289-307 — close() now fails
  waiting requests and closes main outside the monitor; pointer still under it (aab4fe8)
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:116-128,309-317
- src/main/java/dev/andre/homecontrol/adapters/support/ConnectionSlot.java:51-60 — takeIf closes on the caller's thread
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsProperties.java:27-30

<a id="dev-9"></a>

### DEV-9 — Land the registry and keystore hygiene batch from the 2026-10-03 review (DC2, DC3, DC4, AC8 still open)

Merged into [ARCH-1](#arch-1), which covers it. Impact medium, effort M, status open.

<a id="dev-10"></a>

### DEV-10 — Text input and an app launcher for TVs (protocol support and permissions already in place)

Impact **high** · effort **L** · feature · confirmed · **open**

**Problem.** Typing a search term with a d-pad is the most painful part of using a phone as a TV remote, and users
expect a keyboard button. Users also expect to open any installed app (Plex, Disney+, the TV's own apps) from the
drawer, not only YouTube, Netflix and Prime Video through links. The TVs support both, and this code already negotiates
the needed features and permissions without using them.

**Proposal.** Add Action.TypeText(text, submit) with Capability.TEXT_INPUT. webOS:
ssap://com.webos.service.ime/insertText {text, replace:0} plus sendEnterKey. Tizen: ms.remote.control with
TypeOfRemote=SendInputString and base64 text. Android TV: RemoteImeBatchEdit using the ime/field counters from the
device's RemoteImeShowRequest; this is the hardest of the three, so ship it last. Add a connection feature AppListing
(like InputListing) and Action.LaunchApp(appId) with Capability.APP_LAUNCH. webOS: listApps plus launch {id}. Tizen: the
cached installed list plus ed.apps.launch (TizenLaunches already chooses DEEP_LINK or NATIVE_LAUNCH). Android TV can
launch by app link (market://launch?id=) from a user-curated list. In the UI: a keyboard field in the remote drawer and
an Apps grid next to Inputs.

**Trade-offs.** New core types (additive). The text endpoint must keep commands ephemeral: send now or fail, no
buffering of keystrokes. Typed text can be sensitive (passwords), so never log it. Android TV IME is subtle; do webOS
and Tizen first. The ArchUnit rules are unaffected because everything stays inside the adapters.

**Evidence:**

- protocols/src/main/proto/remotemessage.proto:52-75,452-453
- protocols/src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/RemoteConnection.java:36-40,48-52 — features
  negotiated (IME receive, app link)
- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapMessages.java:17-25
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:266 and TizenLaunches.java:43-62
- protocols/src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenRemoteConnection.java:94-101

<a id="dev-11"></a>

### DEV-11 — Channel, source and search keys, and input lists for Samsung and Google TV sets

Impact **medium** · effort **M** · feature · confirmed · **open**

**Problem.** Owners of TVs with a tuner or several HDMI sources cannot change channel, open the source menu or jump to
an HDMI input from the remote. Those are among the most-used buttons of a physical TV remote. Only webOS lists inputs.

**Proposal.** Add CHANNEL_UP, CHANNEL_DOWN, INPUT and SEARCH (and optionally DIGIT_0..9) to RemoteKey and map them:
Android TV KEYCODE_CHANNEL_UP/DOWN, KEYCODE_TV_INPUT, KEYCODE_SEARCH; webOS CHANNELUP/CHANNELDOWN; Tizen KEY_CHUP,
KEY_CHDOWN, KEY_SOURCE. Unmapped keys keep throwing UnsupportedActionException as today. For Tizen, expose a static
InputListing (HDMI 1–4 via KEY_HDMI1..4). For Android-TV-based TVs (Sony, TCL, Philips Google TV), do the same via
KEYCODE_TV_INPUT_HDMI_n behind a per-device setting, so a Shield (which has no inputs) does not offer them.

**Trade-offs.** The core enum grows (additive; the web layer parses key names, so old clients keep working). Static
input lists cannot know which ports are connected or their names, so label them plainly. Exact key-code support varies
by model year; this is a judgement call that needs on-device checks (see DEV-14).

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/RemoteKey.java:4-27
- protocols/src/main/proto/remotemessage.proto:177,259,271,336
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:131-132
- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java:170-171
- docs/user/devices.md:122

<a id="dev-12"></a>

### DEV-12 — Per-connection diagnostics and a Reconnect button on Setup

Merged into [OPS-4](#ops-4), which covers it. Impact medium, effort M, status open.

<a id="dev-13"></a>

### DEV-13 — Let Stop pre-empt an in-flight Bluetooth play

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** A user starts a radio stream on a speaker that is still waking (BlueZ page timeout) or behind a slow stream
server, then presses Stop. The Stop request waits until the play finishes, often 10–20 s and at worst about a minute.
The stream that just started is then stopped, so the user hears a blip after a long, unexplained wait. Volume and mute
requests wait the same way.

**Proposal.** Add a play generation counter. Stop, the setup page's Disconnect and session close() each increment it and
stop the player without taking the commands monitor. play() checks the generation after ensureConnected and again in
MpvPlayer before spawning mpv. A bumped generation completes the pending 'loaded' future exceptionally, so the wait ends
at once and the play gives up quietly. MpvPlayer.close() sets its closed flag and terminates the running process without
waiting for the play's monitor. Volume and mute update the stored values under a small lock and apply them only if a
player is running. Same pattern as the BU1 closed flag.

**Trade-offs.** Slightly more state in one session class. A Play racing a Stop returns "stopped" instead of succeeding,
which is the intended behaviour.

**Evidence:**

- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothSpeakerSession.java:101-127,139-144,202-215
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvPlayer.java:102-131 — synchronized play waits for the
  load
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvPlayer.java:238,259-263 — stop() and close()
  synchronized
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothProperties.java:30,38-40

<a id="dev-14"></a>

### DEV-14 — Opt-in real-device probes that record redacted transcripts as fixtures, so the fakes stay faithful

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Several questions in this review (Tizen ws vs wss, LG TLS behaviour, whether renderers echo URLs, the
Android TV boot window behind the UNPAIRED latch, key-code support by model year) can only be settled on real hardware.
The fakes encode a reading of other projects rather than recorded sessions, so a firmware quirk shows up only in a
user's bug report.

**Proposal.** Step 1 (S–M): add a redacted wire-trace logger per protocol (for example a
dev.andre.homecontrol.wire.<device> category at TRACE). It logs frames with keys, tokens, client keys and URL queries
stripped by the shared redactor (DEV-6), and users switch it on with one environment variable in Compose or CasaOS
(LOGGING_LEVEL_…=TRACE). Document it in docs/user under troubleshooting, so a bug report can carry a transcript. Step 2
(M, optional): an opt-in probe task (for example -Ddevice.probe=tizen@192.168.1.40) that runs the production clients
through a read-mostly flow (connect, read state, list inputs and apps, one harmless key) and writes the redacted trace
to build/probes/. Maintainers promote the traces into fixtures/<device>/. Never part of CI.

**Trade-offs.** This is a judgement call: real-device runs can never be part of CI, and the flows must not change TV
state destructively (no power off, no factory actions). It costs a little maintenance in exchange for much better
protocol fidelity over time.

**Evidence:**

- protocols/src/testFixtures/resources/fixtures/ — no androidtv recordings
- protocols/src/main/java/dev/andre/homecontrol/adapters/tizen/protocol/TizenMessages.java:17,
  protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapMessages.java:9-11
- protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/tizen/protocol/FakeTizenServer.java:67
- src/test/java/dev/andre/homecontrol/adapters/androidtv/MdnsDiscoveryTest.java:54-56
- protocols/src/main — no log.trace, five log.debug calls

<a id="dev-v1"></a>

### DEV-V1 — webOS: when the TV has forgotten the key, the session hangs up on the Allow prompt the TV is showing

Impact **low** · effort **S** · improvement · found by the verifier · **open**

**Problem.** After a factory reset, or after the user removes 'Home Control' from the TV's connected-apps list, the next
background reconnect sends the stored key. The TV answers with pairingType PROMPT and, by the protocol's own description
in SsapConnection.java:99-102, shows the Allow prompt. The session throws KEY_REJECTED at once, closes the socket and
latches UNPAIRED. The user sees a prompt on the TV; pressing Allow does nothing, because nobody is listening any more.
They then have to go to Setup and pair again, which shows the same prompt a second time. Whether the TV keeps the prompt
on screen after the socket closes is not verified on a device.

**Proposal.** When a register with a stored key gets PROMPT, keep the socket and wait up to the pairing timeout
(home-control.webos.pairing-timeout) for the TV's 'registered' or 'error' answer. Publish UNPAIRED with a hint ('accept
the prompt on the TV') while waiting. On 'registered', store the returned key through the existing branch at
WebOsSession.java:246-248 and continue as CONNECTED. On an error or timeout, latch UNPAIRED and stop, as today. Update
the WebOsTimings Javadoc and the user guide paragraph. Test with FakeSsapServer answering PROMPT to a known key,
followed by registered or error.

**Trade-offs.** The session loop is held for up to the pairing timeout once, with nothing else to do while disconnected.
The prompt appears either way, because the TV shows it when the key is rejected; this change only makes accepting it
work. The user's Allow on the TV is explicit consent, so no queuing or product rule is involved. Needs a quick on-device
check that the prompt stays up while the socket is open.

**Evidence:**

- protocols/src/main/java/dev/andre/homecontrol/adapters/webos/protocol/SsapConnection.java:98-116 — PROMPT with a
  stored key throws KEY_REJECTED at once
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:242-258 — on SsapPairingException closes,
  publishes UNPAIRED, STOPs
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:246-248 — a new key on 'registered' is stored
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsTimings.java:5-21 — registerTimeout never used for a prompt in
  sessions
- docs/user/devices.md:150-151 — 'pair it again from Setup'

<a id="dev-v2"></a>

### DEV-V2 — Discovery-driven merges and re-points run on the discovery thread and reconnect every adapter of the device

Impact **low** · effort **M** · risk · found by the verifier · **open**

**Problem.** Example: a webOS TV's built-in Cast receiver is seen for the first time, or announces a new address.
onDiscovered runs on jmDNS's only listener thread. It waits for the registry lock, which a slow Bluetooth Forget can
hold for up to 45 s (DC3). It writes devices.json, then completes the reconnect in the device's turn. That turn closes
and reopens every adapter of the device, not only Cast. The webOS session goes DISCONNECTED and re-registers and
resubscribes, and its close can block behind a stalled pointer-socket request (DEV-8). Meanwhile no mDNS callback runs
for any service, so Android TV and Cast announcements, reconnectNow calls included, wait. Each step is short in the
common case, so this is a robustness risk, not a frequent failure.

**Proposal.** In Enrollment (or Devices), hand DeviceDiscoveredEvent processing to its own single virtual-thread worker,
as UpnpDiscovery already does with submit. Discovery threads then never take the registry lock or a device's turn.
Separately, and as a judgement call given how carefully DeviceConnections is built, consider a per-adapter replace
(begin and complete for one adapter id) so that adding or re-pointing a pairing-free receiver does not tear down a
device's paired sessions.

**Trade-offs.** The worker is a small, local change and keeps commands ephemeral (it handles discovery, not commands). A
per-adapter replace adds complexity to DeviceConnections' generation model and needs careful tests. Doing only the
worker part may be enough.

**Evidence:**

- src/main/java/dev/andre/homecontrol/device/Devices.java:74-77 — synchronous @EventListener
- src/main/java/dev/andre/homecontrol/adapters/cast/CastDiscovery.java:59-62 — published from the mDNS listener callback
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:297-312 — registry lock, file write, then AfterLock.run on
  the same thread
- src/main/java/dev/andre/homecontrol/device/DeviceConnections.java:75-83,94-97,197-202 — begin/complete retire and
  reconnect all the device's handles
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:167-187 — forget can hold the lock for the BlueZ call (DC3)

<a id="src"></a>

## SRC: Content sources, rails, search and playback planning

The source, rail, search and playback layer is sound. Every source reaches the network through GuardedHttpClient: DNS
answers are pinned to the approved addresses, one deadline covers the whole exchange, bodies are capped and failures
carry no cause. The planner is pure, built on sealed types and extended by sources through SourceRef, DelegatedRoute and
RouteExecutor, and Jellyfin calls the current 10.9+ endpoints with the Authorization header and the ApiKey query
parameter. The weaknesses are not crashes. They are policy gaps: rails refresh around the clock whether anyone is
looking, one global pool of four fetch permits does nothing to stop a slow source starving the others, a rail shows
"ready" when part of it failed, and the as-you-type search waits for its slowest source. Verified defects: a slow
on-demand YouTube search is cancelled at 8 s after it has already been charged (open question confirmed); moving a rail
loses a concurrent move and also drops the saved positions of rails whose source is disabled; pinned media links lose
their stream reference, so they cannot play on Cast, DLNA or Bluetooth; and DC5, the stale refresh interval, is still
open. From the code, it also looks as if Jellyfin search cannot find a TV series by its name (series are never requested
and folders are dropped), though no live server was queried. The biggest gap users would notice is streaming-service
routing. Only Netflix, Prime Video and DAZN have app homes, so tiles marked "On Disney+" (and the five other services)
cannot be played. The planner also offers at most one app link per item. The workflow engine is safe, but it can only
build a media URL on a fixed host and Cast it. The feature proposals (start over, recently played, merged search, more
expressive workflows) fit the existing extension points without breaking any product rule; where one touches a recorded
decision (ADR 0003, the workflow specs), it says so.

<a id="src-1"></a>

### SRC-1 — A slow on-demand YouTube search burns 100 quota units and a daily search, then is thrown away

Impact **low** · effort **S** · defect · revised by the verifier · **open**

**Problem.** The user presses 'Search YouTube' on a slow link where Google answers in 9-14 s. At 8 s SearchService
cancels the future, the interrupt makes GuardedHttpClient abort the request, and the page says 'YouTube did not answer
in time'. The ledger already charged 100 units and one of the 20 daily searches (Google also counts the call). Because
the result never arrived, it is not cached, so pressing again costs another search, which may time out the same way.
Three tries can use 15% of the day's searches and show nothing.

**Proposal.** Do not cancel an on-demand search before its transport deadline. Smallest fix: give searchSource its own
deadline, either a new home-control.content.search.on-demand-timeout (default 25 s) or the longer of the configured
timeout and a new ContentSource.searchDeadline() default method. Better UX (option B): on timeout, do not cancel an
on-demand future. Let it finish, so YouTubeSearch caches the result, and answer 'Still searching YouTube, press again in
a moment (no extra quota)'. A second press then joins YouTubeSearch.inFlight or hits the cache. Add a SearchService test
whose fake source answers after the deadline and assert it is not interrupted. Add a YouTube test with a fake Google
answering after 10 s. Nothing is queued: the user still decides whether to press again.

**Trade-offs.** With option A the HTTP request thread waits up to 20 s for the button press; acceptable for an explicit
on-demand action. Option B needs a small fragment change. Neither touches product rules: nothing is queued, because the
user still decides whether to press again.

**Evidence:**

- src/main/java/dev/andre/homecontrol/content/SearchService.java:51-59
- src/main/java/dev/andre/homecontrol/content/SearchService.java:84-86
- src/main/java/dev/andre/homecontrol/content/ContentProperties.java:47
- src/main/resources/application.yaml:257-259
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeProperties.java:26-29
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeApiClient.java:48-52
- src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java:95-110
- src/main/java/dev/andre/homecontrol/sources/http/GuardedHttpClient.java:146-151
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeSearch.java:57-97

<a id="src-2"></a>

### SRC-2 — Moving a rail loses concurrent moves and forgets where disabled or unavailable sources' rails were

Merged into [DATA-6](#data-6), which covers it. Impact low, effort S, status open.

<a id="src-3"></a>

### SRC-3 — Pinned links to media files lose their stream, so they cannot play on Cast, DLNA, Sonos or Bluetooth

Impact **medium** · effort **S** · defect · revised by the verifier · **open**

**Problem.** Pasting https://radio.example/live.mp3 through 'Play a link' plays on a Chromecast (Default Media
Receiver), a Sonos or a Bluetooth speaker, because AppLinks.fromUrl adds a StreamUrl. Pinning the same URL so it is one
tap away turns it into an app link only. On a Sonos or Bluetooth speaker the play sheet then says 'this device cannot
open app links', and on a plain Chromecast nothing routes. Pinned radio streams and podcasts, the obvious things to pin
for a speaker, are unplayable on speakers.

**Proposal.** Add AppLinks.playables(URI) (public, in core) that returns the app link plus a StreamUrl when mediaTypeOf
matches, and an AppLinks.kindOf(URI). Call both from PinnedContentSource.toItem, so kind and refs are derived on read
and stored pins need no migration. Plain service pins (Netflix, Prime Video, DAZN) are unchanged, because their paths
carry no media extension. Tests: a pinned .mp3 plans 'local-audio' on a LOCAL_AUDIO_SINK, 'render' on a MEDIA_RENDERER
and a Default Media Receiver Cast on a CAST_RECEIVER. Note in docs/user/sources.md that direct stream links ending in a
media extension also play on speakers and Cast. Extension-less streams remain app links; detecting them would need a
fetch, which pinning deliberately avoids.

**Trade-offs.** Stored pins keep kind VIDEO unless the kind is derived on read as well; derive it on read so /data needs
no migration. Only http(s) URLs are pinned, so RouteStrategies.playsLocally keeps the server from opening local files.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/playback/AppLinks.java:35-54
- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedContentSource.java:86-88
- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedShortcuts.java:117
- core/src/main/java/dev/andre/homecontrol/core/playback/RouteStrategies.java:38-64
- src/main/java/dev/andre/homecontrol/web/DeviceController.java:69-79

<a id="src-4"></a>

### SRC-4 — Jellyfin search apparently cannot find a TV series by its name; add series hits that play the next-up episode

Impact **high** · effort **M** · defect · revised by the verifier · **open**

**Problem.** Typing 'Breaking Bad' or 'The Office' in the dashboard search returns no Jellyfin hits unless an episode
happens to have that name: series are never requested, and the mapper drops folders. As far as I know Jellyfin's
searchTerm matches an item's own name and original title, not an episode's series name. That is from Jellyfin's
behaviour, not checked against a live server here. TMDB then offers the same show as 'Open the Netflix app (not this
title)' while the household owns it in Jellyfin. For a Jellyfin household, finding a show is the main reason to search.

**Proposal.** First, confirm the premise with a recorded response from a real Jellyfin 10.9+ server for
searchTerm=<series name> and store it as a fixture. Then add Series to the search types and map a Series to a
ContentItem carrying a new JellyfinPlayable.Series(serverId, seriesId) ref. Teach item() to return series. In
JellyfinPlayableResolver, resolve a Series at play time to the next-up episode (/Shows/NextUp?seriesId=&userId=),
falling back to the first unplayed episode, then continue through the existing Item path with its resume ticks. The
preview names the episode. Consider MusicArtist and MusicAlbum hits as a follow-up: they need an 'instant mix' or
first-track rule, because a renderer plays one stream. No /data change.

**Trade-offs.** Preview gains one Jellyfin call for series items. Albums (MusicAlbum) could follow, but a DLNA renderer
plays one stream, so they are left out here. No /data change. Confidence is medium only for the Jellyfin
search-semantics premise; the code gap (no Series type, folders dropped) is certain.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java:106-119
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinItemMapper.java:32-61
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java:69-70
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java:82-99
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java:33-65
- src/test/resources/fixtures/jellyfin/search.json

<a id="src-5"></a>

### SRC-5 — Streaming-service routing: six of nine known services cannot be played, and an item can never offer a second app link

Impact **medium** · effort **L** · improvement · revised by the verifier · **open**

**Problem.** A household that selects Disney+, Apple TV+, Paramount+, WOW, Joyn or RTL+ gets a 'Trending on your
services' rail full of 'On Disney+ · 2025' tiles, and every tap answers 'This item has nothing playable', because those
services have no app home. A Netflix + Prime household sees 'On Netflix, Prime Video', but Play offers only Netflix. If
the Netflix app is signed out or missing, 'try another way' cannot fall back to Prime: there is one app-link route per
item, and skipping it by its key removes every app link.

**Proposal.** (1) Highest value, mostly verification work (M): add app-home links for Disney+, Apple TV+, Paramount+,
WOW, Joyn and RTL+ to ServiceLinks.APP_HOMES (and NAMES), each checked on Android TV, webOS and Tizen and recorded in
the streaming launchers acceptance checklist. Until a service is verified, keep today's behaviour (the tile offers
'paste a link'), and make the tile or preview say so up front ('Pin a Disney+ link to play this') instead of failing on
Play. Do not drop those items. (2) Optional, L: offer one app-link route per matched service. Give OpenAppLink the key
'app-link:<service>', which fits ContentPlayController's 64-character skip cap. Then either register one RefStrategy per
service or let RouteStrategy return a list, and have TMDB and sports attach one AppLink per matched service in the
household's order. This is a core contract change, so it needs an ADR note.

**Trade-offs.** Changing RouteStrategy's return type touches core and every strategy; registering one RefStrategy per
service is less invasive but more beans. Unverified app-home URLs would make 'optimistic' routes fail quietly, so each
needs device verification. No product rule is affected.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/playback/ServiceLinks.java:33-57
- core/src/main/java/dev/andre/homecontrol/core/content/StreamingProviders.java:13-22
- src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbContentSource.java:157-166
- src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbContentSource.java:231-247
- src/main/java/dev/andre/homecontrol/sources/sports/SportsItems.java:41-53
- core/src/main/java/dev/andre/homecontrol/core/playback/RefStrategy.java:19-26
- core/src/main/java/dev/andre/homecontrol/core/playback/Route.java:35-39
- core/src/main/java/dev/andre/homecontrol/core/playback/PlaybackPlanner.java:28-42
- src/main/java/dev/andre/homecontrol/web/ContentPlayController.java:69-76
- docs/superpowers/archive/2026-09-16-sports-dazn.md:74

<a id="src-6"></a>

### SRC-6 — Refresh rails when someone is looking, not 24/7

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** With nobody at home, Home Control still sends Jellyfin about 1,440 rail requests a day and spends about 720
YouTube units, and every workflow rail hits its third-party API every 15 minutes (up to 50 workflows × 50 entry calls
each). A NAS that runs Jellyfin and spins its disks down or sleeps when idle is kept awake around the clock by the
5-minute Continue watching and Latest polls. Rate-limited APIs called by workflows see constant traffic.

**Proposal.** Optional, low priority. Record the last time a dashboard was viewed (an EventStream subscribe or
heartbeat, or snapshots()). When nobody has viewed for more than 2x a rail's interval, stretch its due time by a
configurable idle multiplier (home-control.content.rails.idle-multiplier, default 6, capped at 6 h), and start a refresh
on the next view. The page already shows cached items with RailSnapshot.refreshing. The value is mainly for workflow
rails that hit rate-limited third-party APIs. A cheaper first step: allow a per-workflow refresh interval, so one chatty
workflow can be slowed without slowing the others.

**Trade-offs.** A wall tablet with the dashboard always open saves nothing, which is correct. After idle, the first view
shows stale items briefly. Sports 'Live' labels could lag on first view, but the refresh starts immediately. Nothing is
queued; this changes only when cached data is fetched.

**Evidence:**

- src/main/java/dev/andre/homecontrol/content/RailCache.java:170-207
- src/main/java/dev/andre/homecontrol/content/RailCache.java:352-371
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java:122-125
- src/main/java/dev/andre/homecontrol/sources/sports/SportsContentSource.java:93-96
- core/src/main/java/dev/andre/homecontrol/core/content/ContentSource.java:56-59
- src/main/resources/application.yaml:198-199
- src/main/java/dev/andre/homecontrol/content/StoredRailPreferences.java:62-72

<a id="src-7"></a>

### SRC-7 — Keep one slow source from starving the rail cache, and fix the still-open stale-interval bug (DC5)

Impact **low** · effort **M** · risk · revised by the verifier · **open**

**Problem.** Five workflow rails call a self-hosted API that is alive but slow, so each refresh runs into its 60 s
budget. On a tick all five are due: four take every fetch permit for about a minute, and Jellyfin's Continue watching,
sports and YouTube refreshes wait behind them. With backoff this repeats every few minutes, so unrelated rails are often
stale. Google answering slowly has the same effect through the YouTube subscriptions rail's long chain of sequential
calls. DC5 (verified still open): change Jellyfin from 60 to 15 minutes while its rail loads, and the load's stale 60
minutes overwrites the new schedule.

**Proposal.** (1) Fix DC5 now (S): read preferences.refreshInterval(found) inside succeed() and fail(), under the lock,
instead of before the load. Add a test that changes the interval while a fake rail load blocks. (2) Optional (M): cap
concurrent loads per source inside the global permits (for example, at most 2 for workflows and 1 for every other
source), and start due rails round-robin across sources. (3) Log, at info level, a rail load that takes longer than
about 30 s, so a slow source shows in the logs.

**Trade-offs.** Per-source caps make a source with many rails (workflows) refresh more slowly overall. That is intended,
but the workflow cap should be configurable. Small, contained change in RailCache; no product-rule impact.

**Evidence:**

- src/main/java/dev/andre/homecontrol/content/RailCache.java:46
- src/main/java/dev/andre/homecontrol/content/RailCache.java:85
- src/main/java/dev/andre/homecontrol/content/RailCache.java:278-335
- src/main/java/dev/andre/homecontrol/content/RailCache.java:226-239
- src/main/java/dev/andre/homecontrol/content/ContentProperties.java:33-43
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowProperties.java:10-18
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowContentSource.java:32-35
- src/main/java/dev/andre/homecontrol/sources/youtube/SubscriptionsFeed.java:141-197

<a id="src-8"></a>

### SRC-8 — Report partial failures on a rail instead of showing it as fully loaded

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** A household adds a Bundesliga calendar and a Champions League competition. The calendar URL changes and
starts returning 404, but 'Live today' still loads (READY) with only the Champions League matches, so nobody learns that
half the fixtures are missing until a match is missed. Likewise a workflow's per-entry calls fail and tiles lose their
artwork and subtitles with no hint, YouTube channels are skipped for an hour, and TMDB titles vanish when single lookups
time out.

**Proposal.** Add an optional warning to core Rail (an additive constructor keeps every caller compiling) and carry it
into a READY RailSnapshot. Sports sets it from FeedResult.errors when some feeds failed ('1 of 2 calendars could not be
read; see Setup'). Workflows pass a count from Refresh.problems ('3 tiles are missing details; run Test'). Keep YouTube
and TMDB at a count only. Show one small warning line under the rail header, with a link to the matching Setup section
where the detail already lives. Show it only after the same warning on two consecutive loads, so a single hiccup does
not flicker.

**Trade-offs.** Touches core's Rail record (an additive constructor), RailSnapshot, the SSE rail view and the dashboard
template. It must not become noisy: show a warning only when the same problem persists over two loads, or cap each rail
at one line.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/content/Rail.java:9-13
- src/main/java/dev/andre/homecontrol/content/RailSnapshot.java:45-51
- src/main/java/dev/andre/homecontrol/sources/sports/SportsSchedule.java:24-32
- src/main/java/dev/andre/homecontrol/sources/sports/SportsSetupSection.java:79-95
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java:51-53
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTestService.java:46-49
- src/main/java/dev/andre/homecontrol/sources/youtube/SubscriptionsFeed.java:182-196
- src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbContentSource.java:113-126

<a id="src-9"></a>

### SRC-9 — Use the unused YouTube quota to keep 'New from your subscriptions' fresh, and optionally keep Shorts out

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** With 300 subscriptions and the defaults, each channel is checked about every 10 hours, so a favourite
channel's new video can take most of a day to appear, while about 7,000 of the 10,000 daily units go unused. The uploads
playlist also includes Shorts, which fill a 30-item TV rail with vertical clips that are a poor fit on a TV.

**Proposal.** Cheapest first (S): in docs/user/sources.md, state the arithmetic (each channel checked every
subscriptions / channels-per-refresh hours) and suggest raising channels-per-refresh for large subscription lists. Show
on Setup how often channels are checked with the current settings. Optional (M): size each refresh from the remaining
budget, keeping a configurable reserve (for example, 30%) and the full search allowance, and poll channels with bounded
parallelism (2 or 3), so a larger refresh does not hold a fetch permit for minutes. Treat a Shorts filter through the
undocumented UULF playlist as an opt-in experiment that falls back to UU on NOT_FOUND.

**Trade-offs.** Spending more of the quota leaves less margin if the household also uses the same Cloud project
elsewhere, so keep a configurable reserve (e.g. 20%). The UULF playlist is undocumented and Google could withdraw it;
with the fallback, the worst case is today's behaviour. Combined with SRC-6, idle hours spend little either way.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/youtube/SubscriptionsFeed.java:165-197
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeProperties.java:30-38
- docs/user/sources.md:370-376
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeVideoMapper.java:23-52

<a id="src-10"></a>

### SRC-10 — Let workflows play a URL their API returns, and send audio to speakers, not only Cast

Impact **medium** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Typical APIs (internet-radio directories, IPTV or recording servers, podcast feeds) return a complete
stream URL on a CDN whose host varies. A workflow cannot express 'play the URL the API returned': the host must be a
literal, and a path value like 'live/abc/index.m3u8' becomes 'live%2Fabc%2Findex.m3u8'. A radio-station workflow also
cannot play on a Sonos or a Bluetooth speaker, even though a radio stream is exactly what those devices play, and live
HLS is loaded as BUFFERED.

**Proposal.** (1) Allow a media template that is exactly one non-sensitive placeholder ({streamUrl}), so no stored
header or sensitive value can go to an API-chosen host, and vet it with checkMedia and HttpUrls as now. (2) Add reserved
expansion {+path}, which keeps '/' but still rejects dot segments. (3) Optionally take the mime type from a variable,
checked by the same type/subtype rule, and add a 'live' flag that sets streamType LIVE. (4) Add a WorkflowRenderRoute
(MEDIA_RENDERER) whose executor resolves at Play time. Add a WorkflowLocalRoute (LOCAL_AUDIO_SINK, audio/* only) only
for fixed-host media templates: a local sink makes the server's own mpv fetch the URL, and mpv does not use the pinned
addresses. Record the change as an amendment to the 2026-09-22 and 2026-09-29 workflow specs and to ADR 0003's
consequences.

**Trade-offs.** Points 1 and 4 revise decisions in the 2026-09-22 and 2026-09-29 workflow specs: the media URL is no
longer always on a fixed host, and actions other than Cast are added. The credential argument still holds: no stored
header or sensitive value is sent to an API-chosen host, and only the device, not the server, fetches the media. The
server does vet the host's address. The editor and Test UI need wording for 'whole URL from response'. Cost: L, mostly
in validator, editor and tests.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTemplate.java:91-103
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowTemplate.java:158-169
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowDraft.java:71
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowConfiguration.java:60-64
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java:113-123
- core/src/main/java/dev/andre/homecontrol/core/playback/CastLoads.java:31
- core/src/main/java/dev/andre/homecontrol/core/playback/RouteStrategies.java:50-64
- docs/superpowers/specs/2026-09-22-dynamic-workflows-design.md:119-127
- docs/superpowers/specs/2026-09-29-workflow-chains-design.md:38-40

<a id="src-11"></a>

### SRC-11 — Do not make the search box wait for its slowest source

Impact **medium** · effort **M** · improvement · confirmed · **open**

**Problem.** When the Jellyfin NAS is asleep or off, each debounced search waits until the connection attempt fails or
the 8 s deadline passes. TMDB and Pinned results, ready after about 200 ms, are shown only then. Every refinement of the
query pays the same wait, and each starts another doomed connection to the NAS. The search feels broken whenever one
source is down.

**Proposal.** Render one result section per source, each loading on its own: the results fragment lists the enabled
sources, and each section fetches /search/results/{source} with hx-trigger=load and its own deadline. Fast sources then
appear at once and a slow one shows a spinner, then its error. Also remember a source's UNREACHABLE or timeout for 30-60
s and skip it in as-you-type searches with 'Jellyfin is not answering, skipped'. Unlike RailCache, search has no backoff
today. Keep the per-source GET endpoint read-only (no quota) and the YouTube on-demand POST as is.

**Trade-offs.** More requests per search (one per source), all on the LAN; htmx handles it without new JS. The skip
memory adds a little state to SearchService. No product-rule impact: nothing is queued, and a skipped source says so.

**Evidence:**

- src/main/java/dev/andre/homecontrol/content/SearchService.java:40-48
- src/main/java/dev/andre/homecontrol/content/SearchService.java:68-101
- src/main/java/dev/andre/homecontrol/web/SearchController.java:36-52
- src/main/resources/templates/dashboard.html:44-46
- src/main/resources/templates/fragments/search.html:5-28
- src/main/resources/application.yaml:165-168
- src/main/resources/application.yaml:257-259

<a id="src-12"></a>

### SRC-12 — Offer 'Resume' or 'Start over' for items with progress

Impact **medium** · effort **M** · feature · confirmed · **open**

**Problem.** A household member half-watched a movie last week and someone else wants to watch it from the start. Every
route (session, app start, Jellyfin Cast receiver) resumes at the stored position, and the play sheet offers no way to
start from the beginning other than seeking back on the TV remote. The UI already shows progress (ContentItem.progress),
so the choice is natural to offer.

**Proposal.** Add an optional `from=start` parameter to play-attempt and route-preview, and a 'Start over' button next
to Play on the sheet when the item's progress is non-null. Pass a small PlayOptions(startOver) through PlaybackService
into PlayableResolver.resolve (additive core change with a default method), and have JellyfinPlayableResolver use 0
ticks when startOver. Other sources ignore it.

**Trade-offs.** A small core interface addition. The VLC and direct-stream routes already start at 0, so the button
applies only where a position exists. Effort is mostly UI and tests.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinItemMapper.java:56-60
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java:68-73
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java:76-88
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinPlayableResolver.java:108-111
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSessions.java:63-71
- src/main/java/dev/andre/homecontrol/web/ContentPlayController.java:78-99
- core/src/main/java/dev/andre/homecontrol/core/playback/PlayableResolver.java:14-18

<a id="src-13"></a>

### SRC-13 — A 'Recently played' rail and history across sources

Merged into [PROD-12](#prod-12), which covers it. Impact medium, effort L, status open.

<a id="src-15"></a>

### SRC-15 — Device-flow sign-in shows Google's error_description, unlike the browser flow

Merged into [SEC-2](#sec-2), which covers it. Impact low, effort S, status open.

<a id="src-v1"></a>

### SRC-V1 — Background workflow refreshes take the outbound slots a workflow Play needs, so one slow workflow can make another workflow's Play fail

Impact **medium** · effort **S** · risk · found by the verifier · **open**

**Problem.** Three workflow rails come due together (at startup, or after a tick) and call an API that is up but slow,
so each request runs toward its 10 s timeout. Their refreshes ask for up to 9 to 12 slots and hold all 8. The user then
presses Play on a tile of a fourth workflow whose API is fast. Its calls and the media vet wait for a slot, because
slots belong to the client, not the host. With up to 10 s per held request and a 20 s play budget, the Play can end in
'the workflow took too long; try again later', though its own API is healthy. An explicit user command fails because of
background work.

**Proposal.** Give Play its own slots. Use two WorkflowHttpClient/GuardedHttpClient instances, one for refreshes and
Test, and one for Play (the route executor's resolve), each with its own maxConcurrentFetches, as YouTubeHttp already
does for thumbnails. Alternatively, cap refreshes below the pool (for example, at most 6 of 8 slots), so Play always
finds a free one. Add a test: a fake API that stalls refresh calls, and a Play on another workflow that still completes
within its budget.

**Trade-offs.** A second client doubles the worst-case outbound concurrency for workflows (8 + 8). Keep both small, or
split the existing 8 as 6 for refresh and 2 for Play. No product rule is affected: nothing is queued, and a Play that
cannot get a slot still fails at once with a reason.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java:24-31 — one GuardedHttpClient pool for
  refreshes and Play
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowProperties.java:13-18 — 8 slots, 10 s timeout, 20 s
  play, 60 s refresh
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java:18-19, 47-49 — refresh 3 parallel calls,
  Play 4
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java:55-65 — entry calls run concurrently
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCalls.java:92-101 — waits for a slot until the run's
  deadline
- src/main/java/dev/andre/homecontrol/sources/http/OutboundRequest.java:41-43, 70-73 — get() waits for a slot until the
  deadline
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowRunner.java:113-123 — Play's resolve plus checkMedia
  take slots
- src/main/java/dev/andre/homecontrol/content/RailCache.java:46, 85 — up to 4 rail loads at once
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeHttp.java:74-87 — precedent: separate thumbnail slots

### Rejected in SRC

- **SRC-14 — Merge search hits for the same title across Jellyfin and TMDB (owned first).** The facts are right. Results
  are grouped per source (SearchController.java:94-103), Jellyfin does not request ProviderIds
  (JellyfinContentSource.java:109-117), and ContentItem has no external ids (ContentItem.java:13-15; the cited :201-203
  does not exist in a 43-line file). But the analyst rates it low impact at L effort: a new core field, a merge policy
  and a new 'Top results' UI. It helps only households with both Jellyfin and TMDB, which already see the owned hit in
  its own Jellyfin section. For a single-household appliance kept by a small team, it is not worth it now. If it is
  wanted later, the cheap variant is better: list the Jellyfin section first and mark a TMDB hit 'In your library' when
  an exact title and year match a Jellyfin hit, after SRC-4 makes series findable.

<a id="data"></a>

## DATA: Storage, configuration, upgrades and data lifecycle

The storage layer is sound. Every file under /data goes through AtomicFiles: an owner-only temp file, fsynced, renamed
and the directory synced. Every JSON store except the encrypted secrets.json holds a VersionedJsonFile, which migrates
step by step, refuses newer files and keeps the original once. Secrets use AES-256-GCM with careful error messages. The
gaps are in the data lifecycle around this core. No backup, export, restore or move-to-a-new-host process exists beyond
"copy the directory" (and the docs tell users to do that before every upgrade, which CasaOS users on :latest never get
the chance to do). Downgrades are handled differently for each file. Several formats are defined only by Java record
names: Jackson 3 ignores unknown fields by default, and only round-trip tests guard them, so a refactor can silently
drop data. Stores are read lazily, so a damaged or newer sources.json shows up as a 500 on the dashboard and setup pages
instead of a startup failure. The R6 group of the 2026-10-03 review is still open: ST1-ST4 (theme heap, lock and
orphans), DC2 (merge/split writes the registry twice) and DC4 (plaintext TV keys when the module is off). The rail-order
open question holds up and has a second, more common failure mode. Configuration validation is mostly good (DC1 fixed,
legacy names complete), but module switches treat anything except "true" as off, and nothing logs which modules are on.

<a id="data-1"></a>

### DATA-1 — Snapshot /data automatically before a new version's first start, and refuse a downgrade in one place

Impact **medium** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Rollback depends on the user taking a manual copy at the right moment. Scenario: a CasaOS user updates the
app. On first start, devices.json v2 becomes v3, the TV keys move into secrets.json, and keystore.p12 is re-protected
under a generated password. A regression in the new release makes them pin the previous tag. The old image reads neither
devices.json nor the keystore, so they must pair every Android TV again and reconnect every source, as
configuration.md:101-103 admits. Separately, what a downgraded image does depends on which file it touches first: it
fails to start, runs with a 500 dashboard (DATA-3), quietly resets the quota, or offers to delete workflows it cannot
read.

**Proposal.** Take the snapshot when a one-way change is about to happen, not when the app version changes. Add
DataDirectory.snapshotBeforeUpgrade(reason), which runs at most once per process. It hard-links every regular file under
/data, except backups/ and *.tmp, into /data/backups/<UTC timestamp>-<reason>/, falls back to a copy where links are
unsupported, syncs, and keeps the newest 3 snapshots. Call it from the three places that rewrite data one-way: -
VersionedJsonFile.load before backUp, when found < version; - KeystorePassword before reprotect; - SecretStore's
key-file-to-passphrase re-encryption (SecretStore.java:72-75). This needs no version stamp, and a snapshot exists
exactly when something migrates. Because files are only ever replaced by rename (AtomicFiles.java:35-36), the links keep
the pre-upgrade bytes. Document the restore steps (stop, move backups/<x>/* back, start the older image) next to
configuration.md:99-105. Prune the snapshots on HOME_CONTROL_RESET_LOGIN, because they still hold the removed
credentials. Downgrade refusal stays per store, which already works for every VersionedJsonFile store. Only make the
outliers consistent: QuotaLedger should stop moving a newer file aside, and WorkflowStore should label a newer schema as
'written by a newer Home Control' rather than offering Remove. This amends ADR 0002's consequences, so it needs an ADR
note.

**Trade-offs.** Snapshots hold secrets under the same key, which is the same exposure as the live directory
(security.md:33-38). After HOME_CONTROL_RESET_LOGIN, older snapshots still hold the removed credentials: prune them on
reset or document it. Development builds (0.0.0-SNAPSHOT) need a format fingerprint or must be skipped. SMB/NFS mounts
may not support hard links, so the copy fallback costs space; themes/packages could be excluded because revisions are
immutable. Refusing a downgrade is stricter for files that degrade today (quota, themes), so offer an expert override.
This fits 'existing installs upgrade in place' and changes ADR 0002's consequences, so it needs an ADR.

**Evidence:**

- docs/user/configuration.md:99-105
- casaos/docker-compose.yml:5
- src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java:161-185
- src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java:46-56
- src/main/java/dev/andre/homecontrol/adapters/androidtv/KeystorePassword.java:43-51
- docs/adr/0002-versioned-data-files-and-device-secrets.md:46-49
- src/main/java/dev/andre/homecontrol/storage/SecretStore.java:72-75,230-233
- src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java:174-186
- build.gradle.kts:47

<a id="data-2"></a>

### DATA-2 — Give users a backup, restore and move-to-a-new-host process: a guide now, a consistent export later

Impact **medium** · effort **S** · feature · revised by the verifier · **open**

**Problem.** Compose users have no instructions. A copy taken while the app runs can be inconsistent across files:
AtomicFiles keeps each file whole, but devices.json (keyRef), secrets.json (the key) and keystore.p12 (the alias) are
written at different moments. A TV paired during a `cp -r` can end up in the copy's devices.json without its key.
Nothing tells a user moving hosts to: - carry HOME_CONTROL_SECRET along; - keep ownership for uid 1000 (`cp` without
`-a` resets it); - re-pair Bluetooth speakers on the new host; - stay on the same LAN, because Android TV ids come from
IP addresses; - stop the old instance first (DATA-14).

**Proposal.** (S) Add a 'Back up, restore, move to another machine' section to docs/user/configuration.md and link it
from the README's CasaOS section. - Recipe: stop the container, `tar -cpzf` the data directory (this keeps the 0600
modes and the owner: uid 1000 for Compose, root for CasaOS, casaos/docker-compose.yml:9-10), then start the container. -
Inventory. Essential: keystore.p12, devices.json, secrets.json, secret.key, sources.json, pinned.json, sports.json,
themes/. Disposable: logins.json, youtube-quota.json, *.v<n>.json, *.corrupt-*, login-reset.done. - What does not
travel: Bluetooth pairings (they live in the host's BlueZ), the HOME_CONTROL_SECRET value, and TV addresses. - Stop the
old instance before starting the new one. Add a THEMES constant to DataDirectory and use it in ThemeCatalog, so the
class really lists every file. Leave an in-app export out of this proposal. If it is ever wanted, it needs its own ADR,
because it sends secrets to the browser.

**Trade-offs.** The guide costs nothing against the product rules. The in-app export puts the household's credentials
into a browser download. They are ciphertext under a user passphrase, but this still bends 'secrets never reach the
browser', so it needs an ADR, the login plus password re-entry, and the login's strength rules applied to the
passphrase. A consistent snapshot needs either a short write pause or the shared store registry from DATA-11.

**Evidence:**

- README.md:82-91
- docs/user/configuration.md:99-108
- docs/user/security.md:33-38
- src/main/java/dev/andre/homecontrol/storage/DataDirectory.java:10-26
- src/main/java/dev/andre/homecontrol/themes/ThemeCatalog.java:35,60
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenPairing.java:63-65
- docs/user/bluetooth-speakers.md:78
- casaos/docker-compose.yml:9-10

<a id="data-3"></a>

### DATA-3 — A damaged or newer sources.json makes the dashboard and the setup page answer 500, instead of failing startup or being contained

Impact **medium** · effort **M** · defect · confirmed · **partly fixed at 3932f9b**

**Problem.** Scenario A: the user reverts to an older image after a release that bumped sources.json. devices.json is
still readable, so the app starts and /health says ok, but GET / and /setup answer 500. The store's 'fix or delete it'
message appears only in the log, and the rail ticker logs a failure every 15 s. Scenario B: a hand edit writes
`"railOrder": "jellyfin/latest"` (a string, not an array), with the same result. The setup page that could reset the
preferences cannot open. The only way out is editing or deleting sources.json, and deleting it also removes every
source's settings.

**Proposal.** (1) Preflight: read every VersionedJsonFile store once during startup, using the registry from DATA-11. A
newer or unparseable file then stops startup with its own message, as devices.json does. (2) Contain a malformed
preferences object: SourcePreferencesService.current() falls back to the defaults and exposes problem(), like
PinnedShortcuts.problem() (sources/pinned/PinnedShortcuts.java:60-68). Setup shows the problem with a 'Reset dashboard
preferences' button that rewrites only the preferences object. (3) Add an ErrorAdvice handler for StorageException that
renders its message with 503. These messages name files, never secrets; SecretStore.java:255-257 deliberately drops
causes.

**Trade-offs.** Failing startup for an unreadable sources.json is stricter than today, but the dashboard is unusable in
that state anyway. Damage to one source's section stays contained, as it is now
(JsonFileSourceSettings.java:104-115,167-174, with ContentSources.available catching). The eager reads add a few
milliseconds to startup.

**Since the analysis.** 523459b 'fix(web): answer a storage failure with its reason' adds ErrorAdvice.storage
(ErrorAdvice.java:46-50). It answers StorageException with 503 and its message as plain text, and ErrorAdviceTest covers
it. This is part (3), so GET / and /setup now show the store's 'fix or delete it' text instead of a bare 500. Still
open: (1) there is no startup preflight of the VersionedJsonFile stores, so /health stays ok and the app runs
half-broken. (2) A malformed preferences object is not contained: SourcePreferencesService.current() and
SourcesSetupAdvice still throw, so Setup cannot open to reset the preferences. The RailCache ticker also still logs a
failure on every tick. 930f76a only added a debug log on a different catch, RailCache.java:303-304.

**Evidence:**

- src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java:117-120
- src/main/java/dev/andre/homecontrol/storage/JsonFileSourceSettings.java:133-152,176-191
- src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java:88-93,141-144,187-189
- src/main/java/dev/andre/homecontrol/content/SourcePreferencesService.java:27-29
- src/main/java/dev/andre/homecontrol/content/RailCache.java:89-90,184-185,361-366
- src/main/java/dev/andre/homecontrol/web/DashboardController.java:65
- src/main/java/dev/andre/homecontrol/web/SourcesSetupAdvice.java:32-40
- src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java:24-68 (new StorageException handler 46-50)
- src/main/java/dev/andre/homecontrol/web/HealthController.java:15-18
- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedShortcuts.java:60-68

<a id="data-4"></a>

### DATA-4 — Persisted formats are defined by Java record names that only round-trip tests guard: freeze the current formats with golden files

Merged into [TEST-2](#test-2), which covers it. Impact low, effort M, status open.

<a id="data-5"></a>

### DATA-5 — Merge and split write the registry twice, and a failure between the writes loses a device entry (DC2, still open)

Merged into [ARCH-1](#arch-1), which covers it. Impact low, effort S, status open.

<a id="data-6"></a>

### DATA-6 — Moving a rail loses concurrent moves and drops the positions of every unavailable source's rails

Impact **low** · effort **S** · defect · revised by the verifier · **open**

**Problem.** (a) Lost update (the 2026-10-03 open question holds): with rails [A,B,C], two quick 'up' clicks on C, or
two tabs, both compute [A,C,B], so the user sees one move. Moves of two different rails overwrite each other. (b) More
common: while the Jellyfin server is off (available() is false), disabled in Setup, or its module is switched off,
moving a YouTube rail saves an order without Jellyfin's rails. When Jellyfin comes back, its rails sit at the end. That
contradicts 'a switched-off source keeps its settings' (JsonFileSourceSettings.java:23-29).

**Proposal.** Compute the move inside the update: `prefs.update(p -> p.withRailMoved(key, direction, visibleKeys,
knownSourceIds))`. Add a pure core method that: - starts from p.railOrder() and appends the visible keys missing from
it; - swaps the rail with its nearest visible neighbour; - keeps the keys of sources that exist but are unavailable or
disabled where they are; - drops the keys whose source is available and enabled but no longer offers that rail (a
deleted workflow, a deselected playlist); - stays under SourcePreferences' 200-key cap by dropping the oldest
unknown-source keys first. Add a core unit test for each rule, and an MVC test for two moves in a row with one source
unavailable.

**Trade-offs.** None of note; core gains one pure method.

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/SourcesSetupController.java:98-119,158-160
- src/main/java/dev/andre/homecontrol/content/StoredRailPreferences.java:37-60
- core/src/main/java/dev/andre/homecontrol/core/content/SourcePreferences.java:31,33-38,73-75
- src/main/java/dev/andre/homecontrol/content/SourcePreferencesService.java:40-45
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowContentSource.java:78
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeContentSource.java:82

<a id="data-7"></a>

### DATA-7 — Serve imported theme assets from disk and publish the catalog as an immutable snapshot (ST1-ST4, still open)

Impact **medium** · effort **M** · improvement · revised by the verifier · **partly fixed at 3932f9b**

**Problem.** Example: a 1 GB Raspberry Pi (default heap about 256 MB) with a few 20-30 MB themes, each updated twice: -
the heap holds about twice the disk footprint; - every page view clones the 10 MB background image again; - an
OutOfMemoryError during startup crash-loops under `restart: unless-stopped` until /data/themes is deleted by hand. In
addition, a JVM crash during an install leaves `.staging-*` directories that count against the quota forever. Every
request that checks a theme asset waits while an install runs.

**Proposal.** (1) Keep only descriptors and a path → (file, content type, length, digest) index in memory. Write every
presentation asset at publish time, as theme.css already is, and serve it from the revision directory with a
FileSystemResource. Check the stored compiled CSS against a digest in catalog.json instead of recompiling it. Read
source files from disk only for export. (2) Publish an immutable snapshot through a volatile field so readers take no
lock. Stage and validate outside the monitor, swap under it, and restrict ThemeViewAdvice to page controllers (ST2). (3)
At startup, delete `.staging-*` directories and revisions that catalog.json no longer references. (4) Log the causes at
WARN. ADR 0007 needs a note.

**Trade-offs.** Each asset request reads from disk, but the OS caches it and assets are sent `immutable` with a one-year
max-age. Serving the stored compiled CSS instead of recompiling needs a digest check against the catalog. ADR 0007 needs
a note.

**Since the analysis.** 8e64157 'fix(themes): stop a theme install from holding up every request', with c0eedf7 (a
java:S3077 suppression and a note), does most of part (2). Readers now use an immutable View published through a
volatile field (ThemeCatalog.java:49-57, 167-178) and take no lock, and install() inspects (unpacks and compiles) before
taking the monitor (96-105). So requests no longer wait while an install runs. Remaining: (1) all asset bytes are still
held on the heap. publishView copies publicAssets (177), ThemeAsset.bytes() still clones on every serve, and assets are
still served from memory, not from disk, with the CSS recompiled on load. (2) ThemeViewAdvice is still a global
@ControllerAdvice, though each call is now cheap. (3) Leftover .staging-* directories and unreferenced revisions are
still not cleaned up at startup (publish 256-271, storedBytes 273-284). (4) load() and reclaim() still swallow their
causes (221, 228, 309). The ADR 0007 note is still missing.

**Evidence:**

- src/main/java/dev/andre/homecontrol/themes/ThemeCatalog.java:43-45,74-85,96-139,207-231,233-253,264-284,306-310 (new
  View 49-57, publishView 167-178)
- src/main/java/dev/andre/homecontrol/themes/ThemePackage.java:18-29
- src/main/java/dev/andre/homecontrol/themes/ThemeAsset.java:7-8
- src/main/java/dev/andre/homecontrol/web/ThemeAssetsController.java:39-47
- src/main/java/dev/andre/homecontrol/ThemeConfiguration.java:18-21
- src/main/java/dev/andre/homecontrol/web/ThemeViewAdvice.java:10-25
- src/main/java/dev/andre/homecontrol/themes/ThemeArchive.java:25-26
- Dockerfile:44

<a id="data-8"></a>

### DATA-8 — Say what actually went wrong when a write fails, and keep YouTube working when the disk is full

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** With /data full (an SD card filled by logs, or a NAS quota): - adding a pin or pairing an Android TV says
'check that /data is bind-mounted and writable', so the user checks a mount that is fine; - every YouTube rail refresh
and search fails because the quota bookkeeping cannot be saved, though the API call itself needs no disk. A read-only
remount after a file-system error gets the same misleading text.

**Proposal.** Add one factory, StorageException.cannotWrite(description, path, IOException), and use it at the five
sites. It maps the cause: - 'No space left on device' or 'Disk quota exceeded' → '/data is full; free space on the
host'; - AccessDeniedException → the ownership remedy (ProcessUser.current() plus UnusableDataDirectoryException's chown
text); - 'Read-only file system' → a remount hint; - anything else → today's text. In QuotaLedger.charge and
markExhausted, catch the StorageException from write(), log it once per day, and let the call proceed; the in-memory
count is already updated. A startup warning when usable space falls below about 64 MiB is optional.

**Trade-offs.** Matching on reason strings is platform-dependent; the image is Linux. A restart while the disk is full
can under-count the day's YouTube units, which is acceptable because Google enforces the real quota.

**Evidence:**

- src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java:109-114,181-184
- src/main/java/dev/andre/homecontrol/storage/SecretStore.java:341-344
- src/main/java/dev/andre/homecontrol/storage/SecretKeySource.java:146-153
- src/main/java/dev/andre/homecontrol/security/LoginReset.java:44-46
- src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java:95-115

<a id="data-9"></a>

### DATA-9 — The login reset and a lost secret.key also delete every workflow, and no text says so

Impact **medium** · effort **S** · docs · confirmed · **open**

**Problem.** A household that forgot its password follows the guide and starts with HOME_CONTROL_RESET_LOGIN=true. Every
workflow is deleted (up to 50, each with up to 8 hand-built calls and mappings). No text warned them and no copy is
kept. A lost secret.key has the same effect.

**Proposal.** (S) Say it in security.md, configuration.md:30, application.yaml:160, the LoginReset log line and the
lost-key message: 'workflows are removed too'. (S-M) Before forgetLogin, write the workflow definitions with every
secret field blanked to /data/workflows-before-reset-<ts>.json, and offer to import them. The editor already knows which
fields are secret ('Keep saved URL/headers/template', docs/user/sources.md:100-103). (M, optional) Keep the non-secret
parts in workflows.json and only the secret headers and URL parts as account credentials, so a reset keeps the workflows
and asks only for the secrets again.

**Trade-offs.** The dump must contain nothing secret: a URL marked sensitive is blanked whole. The split reverses a
deliberate spec decision and needs an ADR. The documentation fix has no cost.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowStore.java:22,94,216-243
- src/main/java/dev/andre/homecontrol/storage/SecretStore.java:153-161
- src/main/java/dev/andre/homecontrol/security/LoginReset.java:39-43
- docs/user/security.md:40-45
- docs/user/configuration.md:30
- src/main/resources/application.yaml:161
- src/main/java/dev/andre/homecontrol/storage/SecretKeySource.java:84-87
- docs/superpowers/specs/2026-09-22-dynamic-workflows-design.md:214-218

<a id="data-10"></a>

### DATA-10 — Migrate TV pairing keys and remove orphaned device secrets even while the module is off (DC4, still open)

Merged into [ARCH-1](#arch-1), which covers it. Impact low, effort M, status open.

<a id="data-12"></a>

### DATA-12 — Module switches: reject values that mean nothing, log which modules are on, and cover every setting with a constraint

Impact **low** · effort **S** · improvement · confirmed · **open**

**Problem.** `HOME_CONTROL_BLUETOOTH_ENABLED=1` or `=yes`, both common in compose files, leaves Bluetooth off without a
log line, while the Bluetooth properties record reads true. The user sees no Bluetooth section and no reason. A tick of
0 fails startup without naming the key, and `refresh-intervals.jellyfin: 0s` refetches on every 15 s tick.

**Proposal.** In OnModuleCondition, accept exactly Spring's boolean words and, for any other value, stop startup through
a FailureAnalyzer that names the property and its value. Log one INFO line at application-ready, e.g. 'Modules:
androidtv on, bluetooth off (default), jellyfin off (HOME_CONTROL_JELLYFIN_ENABLED=false)'. Add @DurationMin to
ContentProperties' durations, plus a reflection test that every Duration or int component of a @ConfigurationProperties
record has a constraint or is listed with a reason. Pin the TMDB provider-id environment binding with a Binder test; it
works, see the prior-review status.

**Trade-offs.** 'yes' changes from off to on, which matches what anyone writing it meant. The alternative is to fail on
it. Small change.

**Evidence:**

- src/main/java/dev/andre/homecontrol/config/OnModuleCondition.java:9-40
- src/main/java/dev/andre/homecontrol/config/Module.java:10-51
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothProperties.java:26
- src/main/java/dev/andre/homecontrol/content/ContentProperties.java:33-48
- src/main/java/dev/andre/homecontrol/content/RailCache.java:361-369

<a id="data-13"></a>

### DATA-13 — Entries skipped on read are deleted for good by the next write

Impact **low** · effort **S** · risk · confirmed · **open**

**Problem.** A user edits pinned.json with the app stopped and mistypes one URL (`htps://`) or writes a title longer
than 120 characters. At startup that pin is skipped, with only a log line. The next pin added in the UI rewrites
pinned.json without it, and no copy is kept. The same happens to a sports calendar whose host is longer than 253
characters, and to a pin of a kind that a newer release added without bumping the version.

**Proposal.** Before the first write that would drop entries, move the original aside once as <name>.rejected-<ts>.json,
as QuotaLedger does. Alternatively, keep the rejected entries verbatim under a 'rejected' array that is written back
unchanged. Show the count on the setup page with a Remove action.

**Trade-offs.** Pass-through keeps a broken entry around until it is removed, hence the visible count. The move-aside is
simpler but leaves the fixing to the user.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/pinned/JsonFilePinStore.java:60-121,139-155
- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedShortcuts.java:118-121,180-195
- src/main/java/dev/andre/homecontrol/sources/sports/settings/JsonFileSportsStore.java:64-137
- src/main/java/dev/andre/homecontrol/sources/sports/settings/SportsSettingsService.java:36-44
- docs/user/configuration.md:107-108
- src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java:174-186

<a id="data-14"></a>

### DATA-14 — Refuse to start a second Home Control on the same data directory

Impact **low** · effort **S** · risk · revised by the verifier · **open**

**Problem.** A user moving hosts with /data on a NAS share starts the new instance while the old one still runs. Or a
second compose project points at the same ./data with a different SERVER_PORT. Each instance overwrites the other's
devices.json and secrets.json with its own stale snapshot, so a TV paired on one disappears at the other's next write.
Both also run migrations and the login reset.

**Proposal.** When the DataDirectory bean is created, open /data/.lock (create it empty if missing) and hold a
FileChannel.tryLock() for the life of the context; release it in the bean's destroy method. Do not write into the locked
file. Write host and pid to a separate /data/.lock-owner through AtomicFiles, and name them in the failure: 'another
Home Control (host, pid) is using /data'. Report the failure through a FailureAnalyzer, like
UnusableDataDirectoryFailureAnalyzer. Treat an OverlappingFileLockException (the same JVM) as allowed, or give each test
context its own data dir, so Spring's cached test contexts keep working. Document it as best-effort on NFS/SMB, and list
both files as disposable in the backup inventory.

**Trade-offs.** Advisory locks over NFS/SMB are unreliable, so this is a best-effort guard. A crash releases the lock in
the kernel. Exclude the file from snapshots and backups.

**Evidence:**

- src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java:19-24
- src/main/java/dev/andre/homecontrol/storage/SecretStore.java:67-76
- docs/adr/0002-versioned-data-files-and-device-secrets.md:50-51
- src/test/resources/config/application.yaml:7
- src/test/java/dev/andre/homecontrol/testsupport/FullAppTest.java:41-44

<a id="data-15"></a>

### DATA-15 — Allow HOME_CONTROL_SECRET and secret.key to be rotated, and version secrets.json's contents

Merged into [SEC-10](#sec-10), which covers it. Impact low, effort M, status open.

<a id="data-v1"></a>

### DATA-V1 — Stored dashboard preferences are re-validated on read against lists that change between releases, so a provider rename or a downgrade breaks the dashboard without a format version change

Impact **medium** · effort **S** · risk · found by the verifier · **open**

**Problem.** Scenario 1, an upgrade. A release renames the provider key "wowtv" to "wow" to match an AppLinks key, or
drops a service that closed. On every install that had ticked it, each read of the preferences throws, and GET / and
/setup answer 500. Setup could clear the choice, but it cannot open. sources.json's version did not change, so no
migration runs. That breaks 'existing installs upgrade in place' without anyone having touched a /data format. Scenario
2, a downgrade. Release N+1 adds a provider, the household selects it, and they roll back to N. The result is the same
500, although sources.json still says version 2 and N accepts the file. DATA-3's fallback to the defaults would also
throw away the rail order, the hidden rails and the disabled sources because of one stale provider key. The 200-key rail
cap reaches the same path if DATA-6's fix keeps stale keys.

**Proposal.** Read leniently and write strictly. In JsonFileSourceSettings.preferences(), before calling the
constructor: - filter providers to StreamingProviders.KNOWN; - drop rail keys and source ids that fail the patterns, and
refresh minutes outside 1-1440; - cut railOrder down to the cap; - log one WARN naming each dropped key; these keys are
not secret. Strict validation stays in the constructor for user input. Add tests showing that a stored unknown provider
and an over-long rail order are read with the bad entries dropped, not as a StorageException. Add one line to the
format-change rule (see DATA-4): renaming or removing a StreamingProviders key needs a migration of the stored
preferences.

**Trade-offs.** A stored value is silently corrected rather than refused, but these are preferences, not data that is
lost. A WARN plus the next save rewrites them clean. It fits the 'existing installs upgrade in place' rule and keeps the
core record unchanged. The alternative is to bump sources.json's version for every list change, which is easy to forget
and does nothing for downgrades.

**Evidence:**

- src/main/java/dev/andre/homecontrol/storage/JsonFileSourceSettings.java:139-151 — preferences() still throws a
  StorageException on any constructor IllegalArgumentException
- core/src/main/java/dev/andre/homecontrol/core/content/SourcePreferences.java:61-66 — an unknown provider still throws
- core/src/main/java/dev/andre/homecontrol/core/content/SourcePreferences.java:31,35-37 — the 200-key cap still applies
  on read
- core/src/main/java/dev/andre/homecontrol/core/content/StreamingProviders.java:12-24 — the hand-kept provider list is
  unchanged
- src/main/java/dev/andre/homecontrol/storage/JsonFileSourceSettings.java:34 — still version 2
- src/main/java/dev/andre/homecontrol/content/SourcePreferencesService.java:27-29 and web/SourcesSetupAdvice.java:40 —
  still no catch; the failure now surfaces as a 503 through ErrorAdvice.java:46-50

### Rejected in DATA

- **DATA-11 — One cache and one lock per data file: make VersionedJsonFile the store and keep a registry of data
  files.** The files are as described: JsonFilePinStore.java:51-57, JsonFileSportsStore.java:56-62,
  SportsSettingsService.java:16-44 and PinnedShortcuts.java:48,314. The claimed problem does not follow from them. - The
  'up to three copies' are three references to one immutable value. SportsSettingsService caches the object store.load()
  returns, and PinnedShortcuts copies a List of immutable Pins. - The monitors always nest in the same order (service →
  store → VersionedJsonFile), so they cannot deadlock. - Each service already does its read-modify-write under one lock
  (SportsSettingsService.update :36-44, PinnedShortcuts' synchronized blocks). - DATA-6 does not come from this
  layering. It comes from a controller computing the order before calling SourcePreferencesService.update, and it would
  survive this refactor unchanged. The data-file registry is only an enabler for DATA-1, DATA-2 and DATA-3, and can be
  added with whichever of those is done. On its own, this is churn across about 6 classes and their tests for little
  gain.

<a id="web"></a>

## WEB: Web layer, frontend UX, accessibility and PWA

The web layer is in good shape. The event stream was rebuilt after the 2026-10-03 review: each tab has its own queue and
virtual thread, the snapshot is the first item on that queue, logins are revalidated, and the stream ends cleanly on
shutdown. The ES modules are small and do one job each, use delegated listeners and leak nothing I could find, and run
under a strict CSP. The accessibility basics are stronger than usual: a CSS layer that themes cannot override,
reduced-motion and forced-colors rules, 44px touch targets, native dialogs with focus return, and roving arrow keys. The
weak points are in interaction details, not structure. Every scheduled rail refresh replaces each rail element twice,
which resets its scroll position and drops focus. Remote buttons have no hold-to-repeat and no ordering, and the
keyboard path sends one request per OS auto-repeat. Nothing on the client notices a dead stream after a phone wakes up.
Setup forms block for up to 60 s with no progress shown, often give no confirmation, land on the wrong part of the page,
and destructive actions have no confirmation. There is no error boundary for users: Spring's Whitelabel page or a JSON
error body can appear, and text from devices reaches toasts unredacted (an open question that still holds). Device
status, app and volume are shown as raw enum names, Android package names and the adapter's own volume scale. Every
string is hard-coded in English, although the defaults target German households. The service worker caches only
presentation assets and never caches commands or state, which fits the product rules. Perceived performance is fine on a
LAN: first paint is server-rendered, loading rails show skeletons, and the only waterfall is the unversioned assets
marked no-cache plus a module import chain three deep. I do not propose work on that.

<a id="web-1"></a>

### WEB-1 — Every background rail refresh re-renders each rail twice, resetting its scroll position and dropping keyboard focus

Impact **high** · effort **M** · defect · revised by the verifier · **open**

**Problem.** Every scheduled refresh of a rail sends two rail events: 'refreshing' at the start and 'ready' at the end.
Each bumps the version, so rails.js fetches the fragment and replaces the rail element twice. A household member scrolls
Jellyfin's 'Continue watching' rail to the twelfth tile on a phone. Within five minutes the rail jumps back to the first
tile, twice, even when nothing changed. A keyboard or screen-reader user focused on a tile loses focus to <body>, and
lazy-loaded artwork is rebuilt and flickers. A rail that keeps failing (NAS asleep, retry-after-failure 1m) is replaced
twice a minute.

**Proposal.** 1) Render data-fetched-at on the rail section. In rails.js, when an event differs from the rendered rail
only in `refreshing` (same status, same fetchedAt), toggle the .refreshing class and aria-busy and update data-version
without fetching. 2) Before any outerHTML swap, remember `.tiles.scrollLeft` and the data-item of the focused tile (or
of the tile that opened the play sheet). After htmx.ajax resolves, restore both on the new element. 3) Optional, server
side: give a READY snapshot whose items did not change a flag, or keep its previous version, so the client skips the
fetch. 4) Add a browser test that scrolls a rail, focuses a tile, publishes a refresh, and asserts that scrollLeft and
focus survive. Note: a failing rail is re-rendered twice per retry, and retries back off from 1m up to the refresh
interval, not twice a minute.

**Trade-offs.** A morph swap (idiomorph) would be more general but adds a second vendored library and checksum. The
targeted client fix keeps the 'server renders, SSE only says which rail' design. No product or architecture rule is
affected.

**Evidence:**

- src/main/java/dev/andre/homecontrol/content/RailCache.java:170-181 — tick() starts every due rail
- src/main/java/dev/andre/homecontrol/content/RailCache.java:244-267 — start() bumps the version for the refreshing
  marker and publishes before the fetch
- src/main/java/dev/andre/homecontrol/content/RailCache.java:323-334 — succeed() bumps the version again, items changed
  or not
- src/main/java/dev/andre/homecontrol/content/RailCache.java:337-349 — fail() backs off exponentially up to the refresh
  interval
- src/main/java/dev/andre/homecontrol/content/RailSnapshot.java:41-47 — refreshing() changes only the flag and version
- src/main/java/dev/andre/homecontrol/web/EventStream.java:123-127 — every RailUpdatedEvent becomes a 'rail' SSE event
- src/main/resources/static/js/rails.js:17-40,43-47 — a higher version triggers an outerHTML swap (now with .catch at
  :35)
- src/main/resources/templates/fragments/rails.html:15-20,41-45,62-75 — id-less tile buttons; data-status but no
  fetchedAt
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java:122-125 — 5-minute refresh

<a id="web-2"></a>

### WEB-2 — Remote keys: no hold-to-repeat, no ordering, keyboard auto-repeat floods the device, no haptics

Impact **high** · effort **M** · defect · revised by the verifier · **partly fixed at 3932f9b**

**Problem.** (a) Holding ArrowDown on a desktop for 2 s fires about 60 keydowns. Each becomes a POST; the browser allows
6 at a time and queues the rest, so the TV keeps scrolling after the key is released. Fast taps can arrive out of order
because the requests run concurrently. (b) Holding Vol+ on a phone sends one step. Android TV has no volume slider (the
slider exists only with castControls), so going from 10 to 30 takes 20 taps. (c) There is no tactile confirmation on a
glass screen, and a slightly low tap on Menu hits Power.

**Proposal.** Add a remote-buttons module that drives the remote buttons from pointer events, keeping hx-post as the
no-JS fallback. A press sends one key. Keys marked data-repeat (D-pad arrows, VOLUME_UP/DOWN, REWIND/FAST_FORWARD)
repeat every ~150 ms after a ~400 ms hold, and stop on pointerup, pointercancel, pointerleave, visibilitychange or
pagehide, as the touchpad does. Allow one request in flight per device. Presses of the same key made meanwhile are
counted and sent as one follow-up with repeat=n (n ≤ 4), so taps are no longer lost to htmx's default 'last' queue. A
different key waits for the in-flight request, which keeps the order. A failure drops the pending count, stops the
repeat and shows a toast. Route the keyboard (app.js and the touchpad's keydown) through the same pipeline, so OS
auto-repeat collapses too. Accept arrows and Backspace while focus is on a remote button, because they have no native
activation there. navigator.vibrate and moving Power are optional and a judgement call. The PR must say that the pending
count is a per-page buffer of at most one request, dropped on failure and never kept beyond the page, so it does not
conflict with 'commands are ephemeral, nothing is queued'.

**Trade-offs.** Collapsing presses is a client-side buffer of at most one pending request per device. It never outlives
the page and is dropped on failure, so it fits 'commands are ephemeral, nothing is queued', but say so in the PR. The
strictest alternative is to drop presses while one is in flight, which loses taps. iOS has no Vibration API. The buttons
stop being pure htmx (keep hx-post as the no-JS fallback). Browser tests need fake-clock holds.

**Since the analysis.** 89e8d65 drops hx-post from the remote buttons. They are now type=button data-key buttons, and
one click handler (app.js:31-37) sends each press at once through sendKey, so htmx's 'last' queue no longer drops taps.
Everything in the problem paragraph remains. (a) Keyboard auto-repeat: still one POST per keydown with no event.repeat
check (app.js:55-63), and the touchpad's keydown is the same. Ordering: every press, buttons included, now goes out as
its own concurrent fetch, so there is no per-element serialization any more and different keys can still arrive out of
order. Nothing allows one request in flight or collapses presses into repeat=n. (b) No hold-to-repeat: click only. (c)
No haptics, and Power is still in the essential row. Arrows and Backspace are still ignored while a button has focus
(app.js:58).

**Evidence:**

- src/main/resources/templates/dashboard.html:77-105 — remote keys are now type=button data-key buttons (89e8d65);
  nothing on hold
- src/main/resources/static/js/app.js:31-37 — click handler sends each press at once, concurrently, with no ordering or
  coalescing
- src/main/resources/static/vendor/htmx.min.js — no longer drives the remote keys, so the 'last' queue point is moot
- src/main/resources/static/js/app.js:55-63 — one POST per keydown, no event.repeat check
- src/main/resources/static/js/app.js:58 — shortcuts ignored while any button has focus
- src/main/resources/static/js/touchpad.js:110-116 — touchpad keydown sends one request per auto-repeat
- src/main/java/dev/andre/homecontrol/web/DeviceController.java:37-66 — key endpoint accepts repeat=1..4
- src/main/resources/static/js/touchpad.js:35-38,67-103,132-138 — hold, release and cancel model to reuse
- src/main/resources/templates/dashboard.html:86-91 — Power in the essential row under the D-pad

<a id="web-3"></a>

### WEB-3 — Setup forms block without progress, give no confirmation, land at the wrong place, and re-submit pairing on reload

Impact **medium** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** A household member presses Pair on a discovered LG TV. For up to 60 s nothing changes except a greyed-out
button. They press refresh, the browser re-POSTs /setup/prompt-pair, and the TV shows a second prompt. Changing the
Shield's Jellyfin player redirects to the top of a long page, so 'Player preference saved' (or the error) sits far below
where they can see it. Forgetting or merging a device gives no confirmation at all.

**Proposal.** 1) Every setup POST redirects with its section anchor and a flash message: #paired-devices or #devices for
device actions, #jellyfin for Jellyfin, #account for password changes. 2) Give the device section its own message and
error slot next to its forms. 3) Use POST-redirect-GET for pairing failures: flash the error and redirect to GET /setup,
which already re-derives the awaiting-code state from CodePairing.inProgress() (SetupController.java:82-86). 4) Add a
data-pending text to long-running forms (prompt and code pairing, Bluetooth scan and pair, Test connection). setup.js
swaps the button label, sets aria-busy and writes the text into a polite live region. Add data-submit-once to the
Bluetooth forms. 5) Add a browser test for anchor plus message after a device action and after a password change.

**Trade-offs.** Low risk and no product-rule impact. The flash-attribute approach is already used by the source
controllers. The error must survive the redirect without echoing secrets typed into the form (the Jellyfin controller
already keeps only non-secret fields).

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/SetupController.java:233-252,262-272 — device actions redirect to bare /setup
  with no message
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSetupController.java:30 — bare REDIRECT; messages in
  src/main/resources/templates/fragments/jellyfin-setup.html:5-6
- src/main/java/dev/andre/homecontrol/web/LoginController.java:30,115,129,137 — bare redirect; messages in
  src/main/resources/templates/setup.html:232-233
- src/main/java/dev/andre/homecontrol/web/SetupController.java:88-108,139-163 — pairing failures render from the POST
- src/main/resources/application.yaml:65,82 — webOS pairing timeout 60s, Tizen 30s
- src/main/resources/templates/setup.html:90-91,144 — static wait hints
- src/main/resources/templates/fragments/bluetooth-setup.html:28-30 — scan form without data-submit-once
- src/main/resources/static/js/setup.js:9-19 — only disables the submit buttons

<a id="web-4"></a>

### WEB-4 — A phone that wakes from sleep can keep a dead event stream that still shows as live

Impact **medium** · effort **M** · risk · confirmed · **open**

**Problem.** The phone sits locked for 20 minutes while the access point drops the idle TCP flow, or the phone hops from
Wi-Fi to mobile data and back. No FIN or RST reaches the browser, so the EventSource stays OPEN. After unlock the header
says 'Live updates', but the chips show the old state (TV 'CONNECTED' with the previous app) until the OS times the
socket out. Meanwhile the server keeps the dead subscriber until its writes fail after the TCP retransmission timeout,
which is often around 15 minutes. Judgement call: I reasoned this from how SSE behaves on mobile networks and did not
reproduce it on a device.

**Proposal.** 1) Also send the heartbeat as a named event (`event: heartbeat`, empty data), keeping the comment for
proxies. 2) In events.js, close and reopen the stream when no message has arrived for about 2.5× the heartbeat interval.
Pass the interval to the page as a data attribute or in a first `hello` event, because EventStreamProperties makes it
configurable. 3) Close the EventSource on pagehide, and on visibilitychange to hidden after a short grace period (about
30 s). Reopen on visible, pageshow or online; the snapshot repaints everything. 4) Show 'Reconnecting…' during the gap.

**Trade-offs.** More reconnects and snapshots, which is negligible for a household. The wire format changes only by
adding an event, and older pages ignore unknown event names, so existing installs upgrade in place. Closing hidden tabs
also saves phone battery and server threads.

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/EventStream.java:164-168 — heartbeat is an SSE comment
- src/main/resources/static/js/events.js:23-34 — only open and error drive the state
- src/main/resources/static/js/app.js:24-29 — 'Live updates' until an error event
- src/main/resources/static/js/touchpad.js:125-130 — touchpad availability follows the stream
- src/main/java/dev/andre/homecontrol/web/EventStreamController.java:40-61 — every (re)connect gets a full snapshot
- src/main/java/dev/andre/homecontrol/web/EventStreamProperties.java:16 — heartbeat interval configurable (default 25s)

<a id="web-5"></a>

### WEB-5 — No error boundary for users: Whitelabel pages, JSON error bodies in toasts, and device text passed through unredacted

Impact **medium** · effort **M** · risk · revised by the verifier · **open**

**Problem.** A DLNA renderer rejects a Jellyfin stream with errorDescription 'Unsupported URI
http://nas:8096/Audio/…?static=true&ApiKey=abc123'. The toast shows the household's Jellyfin token, against the product
rule 'Secrets … never reach the browser' (this was a 2026-10-03 open question and still holds). Smaller cases: a rail
whose source was switched off in another tab answers a refetch with 404, and the toast shows
`{"timestamp":…,"status":404,"error":"Not Found","path":"/rails/…"}`. A form POST to a module that is switched off shows
Spring's unthemed Whitelabel page with no way back.

**Proposal.** 1) Add error.html (and error/404.html) built on the layout fragments, with Back to Home and Setup links,
so a page request never shows the Whitelabel page. 2) Add ErrorAdvice fallbacks. ResponseStatusException answers its
reason as text/plain. Any other exception on a non-HTML request answers one generic sentence and is logged at WARN. 3)
Add one redaction helper used where device and source text becomes a user message: RendererCommands.run, CastApps.reason
or CastRefusedException, ErrorAdvice.text and the PlayResultView message. It strips query strings and userinfo from
URLs, collapses whitespace and caps the length at about 300 characters, and the full text is logged at DEBUG. 4) The
toast code shows the body only for a text/plain Content-Type, otherwise a generic sentence. 5) Make the YouTube
device-flow poll match the exchange path and stop echoing error_description.

**Trade-offs.** Redaction can hide diagnostics, so log the full text server-side at DEBUG. Logs are not the browser, but
the HttpClient logging rule in application.yaml shows logs must still be handled with care. If WEB-15 narrows
ThemeViewAdvice, the error controller must stay in its scope.

**Evidence:**

- src/main/resources/templates/ — still no error.html or error/ directory; src/main/resources/application.yaml has no
  server.error settings
- src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java:23-68 — core exceptions plus StorageException (:46-50);
  text() returns e.getMessage() verbatim at :65-67
- src/main/java/dev/andre/homecontrol/web/SetupController.java:176-179 and
  src/main/java/dev/andre/homecontrol/web/RailController.java:46 — ResponseStatusException reasons fall through to Boot
- src/main/java/dev/andre/homecontrol/web/ContentPlayController.java:92-94 — cause.getMessage() into PlayResultView
- src/main/resources/static/js/app.js:36,40-43 and src/main/resources/static/js/remote-transport.js:5-7 — toast the
  response text whatever its content type
- protocols/src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/SoapFault.java:28-39 and
  src/main/java/dev/andre/homecontrol/adapters/support/RendererCommands.java:98-107 — device errorDescription embedded
- protocols/src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastApps.java:152-155 — receiver message used
  verbatim
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinStreams.java:95-100 — stream URLs carry ApiKey=<token>
- src/main/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClient.java:165 vs :210-218;
  src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeAuthorizationService.java:270-273

<a id="web-6"></a>

### WEB-6 — Device state is shown as raw enum names, package names and the adapter's own volume scale; 'UNPAIRED' offers no way out

Impact **medium** · effort **M** · improvement · confirmed · **open**

**Problem.** Household members see 'UNPAIRED', 'CONNECTING' or 'org.jellyfin.androidtv' on the device chips. A TV that
rejected the client certificate shows 'UNPAIRED' with no hint that it must be paired again, or where. The drawer can
show 'vol 7' next to a slider at 47, because the label uses the adapter's own scale and the slider is rescaled.

**Proposal.** Add one presentation mapping in the web layer, used by Thymeleaf and by live updates: Connected,
Connecting…, Offline, and 'Needs pairing again' with a link to /setup#paired-devices for UNPAIRED. Put a `statusLabel`
(and `appLabel`) in the SSE state payload through a web view record, so the JS does not duplicate the mapping. Add a
small table of known Android TV packages (Netflix, YouTube, Jellyfin, VLC, Prime Video, DAZN, Disney+, Plex, launcher →
'Home screen'), falling back to the raw package name. Show volume as a percentage of volumeMax, consistent with the
slider. Use display names instead of adapter ids in Split. Document the statuses in devices.md and correct 'bottom
panel'.

**Trade-offs.** Keep the labels out of core, which depends on the JDK only. Browser tests that assert 'CONNECTED' must
change. The package table needs occasional upkeep; the raw fallback keeps it safe. This is natural groundwork for
WEB-12.

**Evidence:**

- src/main/resources/templates/dashboard.html:29-33,61,63,194-196
- src/main/resources/static/js/state-view.js:14-30,36-38,44
- core/src/main/java/dev/andre/homecontrol/core/DeviceStatus.java:3-7
- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java:314-317
- src/main/resources/templates/setup.html:180
- docs/user/devices.md:33-35 and src/main/resources/static/app.css:149-163

<a id="web-7"></a>

### WEB-7 — Toasts may not be announced, disappear before they can be used, and cannot be dismissed

Impact **medium** · effort **S** · defect · confirmed · **open**

**Problem.** A live region that is display:none when its content changes is often not announced by VoiceOver or NVDA. A
blind user pressing a key for an offline TV may get no feedback at all. A keyboard or switch user has 10 s to reach the
'Try Cast with …' button before it vanishes (WCAG 2.2.1 Timing Adjustable, 4.1.3 Status Messages). Errors are announced
as politely as successes.

**Proposal.** Keep two live regions rendered at all times and visually hidden when empty: polite for success, assertive
(role=alert) for failures. Write the message there and show the visual toast separately. Pause the timer on hover and on
focus within the toast, add a close button, and keep toasts with an action until they are dismissed or replaced. Add a
browser test asserting that the live region exists before it is updated and receives the text.

**Trade-offs.** Small change and the visual design stays the same. Assertive announcements should stay limited to
failures, to avoid noise.

**Evidence:**

- src/main/resources/templates/dashboard.html:210 — <output id="toast" hidden aria-live="polite">
- src/main/resources/static/app.css:7 — [hidden] { display: none !important }
- src/main/resources/static/js/toast.js:4-15 — text set while hidden; 4 s / 10 s auto-hide; no pause or close
- src/main/resources/static/js/remote-drawer.js:32-33,43-44 — toast moved in and out of the dialog
- src/main/resources/static/js/play-sheet.js:184-196 — fallback route only in the toast

<a id="web-8"></a>

### WEB-8 — Phone-first entry: the app always opens on the most recently paired device, and the remote deep link is unused

Impact **medium** · effort **M** · feature · revised by the verifier · **open**

**Problem.** In a home with two TVs and a speaker, the bedroom phone opens the installed app on the living-room TV,
because that one was paired last. Every time, the user must find the bedroom chip (a full page reload), then tap Remote.
Switching to another device from the remote means closing it, reloading, and opening it again. The /remote/<id> link
that would solve part of this exists, but nothing exposes it.

**Proposal.** 1) Remember the device last chosen in this browser in a cookie, checked against the registry;
DashboardController uses it before defaultDevice(). 2) Add manifest shortcuts that name no device: 'Remote' →
/?remote=open, 'Search' → /#search-q. 3) When the remote is open, keep remote=open on the chip links, so switching
device keeps it open. That is a one-line template change and covers most of the in-drawer switcher's value without a new
fragment endpoint. 4) Document /remote/<id> as a bookmarkable link in devices.md.

**Trade-offs.** A per-browser preference is not /data state, the same as the theme choice, so no format migration is
needed. Static shortcuts keep the public manifest free of device names, and its 1 h cache does not matter. The in-drawer
switcher needs a drawer fragment endpoint, which is moderate template work.

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/DashboardController.java:43-45 — falls back to defaultDevice()
- src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java:189-196 — default is the latest lastSeen
- src/main/java/dev/andre/homecontrol/web/PwaController.java:25-44 — start_url '/', no shortcuts
- src/main/java/dev/andre/homecontrol/web/DashboardController.java:71-78 — /remote/{id} redirect, linked from nowhere
- src/main/resources/templates/dashboard.html:23-25 — chips link to /?device=id without remote=open
- src/main/java/dev/andre/homecontrol/security/LoginGateFilter.java:33-38 — manifest is an open path

<a id="web-9"></a>

### WEB-9 — Destructive setup actions run on a single tap

Impact **medium** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** On a phone, Forget sits in the same list item as 'Test deep link' and the Wake-on-LAN Save button. A
mis-tap removes the pairing at once, and pairing again means walking to the TV for a code or prompt. A mis-tap on
Disconnect Jellyfin deletes the credentials, and reconnecting needs the Jellyfin password.

**Proposal.** Add data-confirm with the item's name (for example "Forget Living Room TV? You will have to pair it
again.") to the truly destructive forms: Forget device, the Jellyfin, TMDB and YouTube Disconnects, Remove pin, sports
calendar and competition, Remove workflow, and Remove theme. Leave out Remove password, which already asks for the
current password, and Bluetooth Disconnect, which can be undone. setup.js, and the appearance page's script, intercept
submit and show a small native <dialog> with Cancel and the action. Without JS the form still submits. This is CSP-safe
with no inline handlers.

**Trade-offs.** One extra tap on rare actions. Keep it off frequent non-destructive buttons. CSP-safe because no inline
handlers are needed.

**Evidence:**

- src/main/resources/templates/setup.html:153-158,185-189 — Forget in the same list item as other actions
- src/main/resources/templates/fragments/jellyfin-setup.html:24-26 — Disconnect
- src/main/resources/templates/fragments/tmdb-setup.html:17 — Disconnect
- src/main/resources/templates/fragments/youtube-setup.html:15 — Disconnect
- src/main/resources/templates/fragments/pinned-setup.html:46-48, fragments/sports-setup.html:39,141,
  fragments/workflows-setup.html:22, appearance.html:74 — Remove buttons
- grep: no confirm, hx-confirm or data-confirm in templates or static/js

<a id="web-10"></a>

### WEB-10 — Accessibility regressions are caught only by hand-written checks; several controls have ambiguous names or noisy live regions

Impact **medium** · effort **M** · improvement · confirmed · **open**

**Problem.** In a screen reader's button list, the Dashboard preferences show five identical 'Move up' buttons. Typing
in search makes the reader recite dozens of titles. The theme system lets packages set any colours, and nothing
automated would notice the built-in themes or the layout dropping below WCAG contrast or losing names.

**Proposal.** Add com.deque.html.axe-core:playwright to the e2e source set. Scan the dashboard (drawer closed and open,
play sheet open), setup, appearance and login under both built-in themes in Chromium, and fail on serious or critical
violations. Fix what it finds: contextual aria-labels from the item names; a short search status ('12 results for
“dune”') as the live region instead of the results container; role=alert only when a rail newly fails; the mode switch
as a radio group (or real tabpanels); no aria-disabled on role-less containers. Optional: warn at theme import when
fg/bg, muted/bg or accent-ink/accent contrast is below 4.5:1.

**Trade-offs.** Adds a dependency to the e2e source set only, so `build` is unaffected (it still needs a checksum
entry). axe results depend on the theme, so the scan should be pinned to the built-ins. Running it only in Chromium
keeps CI time down.

**Evidence:**

- src/e2e/java/dev/andre/homecontrol/e2e/InterfaceE2eTest.java:164-192 — hand-written label checks; no axe
- src/main/resources/templates/fragments/sources-setup.html:38,43,48 — unnamed Move up, Move down and Hide
- src/main/resources/templates/fragments/pinned-setup.html:37-44,47 — named moves, unnamed Remove
- src/main/resources/templates/dashboard.html:51 — aria-live on the whole search results
- src/main/resources/templates/fragments/rails.html:47 — role=alert re-announced on every swap
- src/main/resources/static/js/touchpad.js:63 and src/main/resources/templates/dashboard.html:67-70

<a id="web-11"></a>

### WEB-11 — Setup mixes everyday tasks with expert settings on one long page

Impact **low** · effort **L** · improvement · revised by the verifier · **open**

**Problem.** The brief asks whether a non-technical household member can use setup. Today that person meets Docker
networking advice, keystore file names, quota arithmetic and OAuth console steps on the same scrolling page as 'Pair',
'Install app' and 'Set password'. They must type 'de-DE' exactly. The welcome checklist never reflects what is already
done. The docs send them to section names the page does not show. This is a judgement call about the intended audience.

**Proposal.** Quick wins first (S): align the section names in sources.md with the UI ('Movies & series', 'Dashboard').
Offer language and region as <select>s of supported tags, or normalise the case before validating. Give the Merge
selects an empty placeholder so the server's refusal is not the first feedback. Show display names in Split. Then, as a
separate judgement call (L): put the expert material (merge and split, Wake-on-LAN MAC, Jellyfin app links, YouTube
Lounge and quota, Bluetooth host checks, workflows) into collapsed <details> or an Advanced page, keeping every existing
anchor id because redirects and docs use them, and make the welcome steps reflect what is already done.

**Trade-offs.** The largest UX change in this list. Anchors and ids must stay stable because redirects and docs use
them, and SetupFlowsE2eTest needs updating. Experts lose one click to reach advanced settings.

**Evidence:**

- src/main/resources/templates/setup.html:43-52,77-78,180,185-189
- src/main/resources/templates/fragments/youtube-setup.html:123-124
- core/src/main/java/dev/andre/homecontrol/core/content/SourcePreferences.java:134-140 — locale must already be
  canonical
- src/main/resources/templates/fragments/sources-setup.html:55-61 — free-text locale and region
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:338-341 — merging a device into itself is refused (plus a
  new address refusal at :367-370)
- docs/user/sources.md:251,253 against src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbSetupSection.java:23 and
  src/main/resources/templates/setup.html:30

<a id="web-12"></a>

### WEB-12 — No internationalisation: every string is hard-coded English in templates, JS and controllers, while the defaults target German households

Impact **low** · effort **XL** · feature · revised by the verifier · **open**

**Problem.** Content titles come back in German, but every label, toast, hint and error around them is English.
Household members who don't read English get no help. The fixed HH:mm in the container zone can show the YouTube code
expiry an hour or two off. Judgement call: English-only may be acceptable if the audience is just the maintainer's
household.

**Proposal.** Split it. Now (S): render the YouTube code expiry as <time datetime="…"> and format it in the browser
(toLocaleTimeString), or in a configured household zone, instead of the container's default zone. Later, and only if the
maintainers decide the UI should be translated (XL): MessageSource with messages.properties and messages_de.properties,
a LocaleResolver that prefers the household setting over Accept-Language, and templates moved to #{…}. JS strings come
from a server-rendered per-locale ES module (CSP-safe, fits InlineCodeTest). Controller flash messages use message keys.
Browser tests stay pinned to en. Do WEB-6 first, since it centralises many of the strings.

**Trade-offs.** Large churn across about 25 templates and 10 modules. Browser tests assert English text, so pin them to
en. Translations need upkeep. This does not touch any product rule. Doing WEB-6 first shrinks the work.

**Evidence:**

- src/main/resources/templates/dashboard.html:2 — lang="en"; no messages*.properties under src/main/resources
- src/main/resources/static/js/app.js:27,41,43 and src/main/resources/static/js/play-sheet.js:184-196 — JS string
  literals
- docs/user/configuration.md:24-25 — de-DE and DE content defaults
- src/main/resources/templates/fragments/youtube-setup.html:146-147 — expiry formatted HH:mm in ZoneId.systemDefault()

<a id="web-13"></a>

### WEB-13 — Front-end logic is tested by grepping source text; pure modules have no unit tests

Impact **low** · effort **M** · debt · revised by the verifier · **open**

**Problem.** A refactor that keeps the asserted strings but breaks behaviour, such as an off-by-one in stepsFor or a
back-off that never resets, passes `scripts/gradle.sh build`. It is caught only by the slower three-browser browser
tests, if one happens to exercise that path. A harmless rewording breaks StaticAssetsTest.

**Proposal.** Replace the source-grep assertions in StaticAssetsTest that stand in for behaviour (the gesture constants,
the literal sentences) with behavioural checks. Either add node:test files for the pure modules (touchpad-gestures,
describePlaying and formatTime, the back-off) to the existing Node step in CI, or, without a second runner, import them
in a Playwright page from the e2e suite as RemoteLayoutE2eTest already does. Keep StaticAssetsTest to serving, caching
and wiring checks.

**Trade-offs.** A second test runner. The gradle:jdk25 image has no Node, so these tests would not be part of 'done =
build green' unless wired in through the e2e image; decide that explicitly. No Node build is introduced and the app
keeps plain ES modules, so the 'No Node build' rule holds.

**Evidence:**

- src/test/java/dev/andre/homecontrol/web/StaticAssetsTest.java:51-58,112-121 — source-text assertions
- src/e2e/java/dev/andre/homecontrol/e2e/TouchpadE2eTest.java:27-56 — behavioural gesture checks
- src/e2e/java/dev/andre/homecontrol/e2e/RemoteLayoutE2eTest.java:219 — e2e imports a module in the page
- src/main/resources/static/js/state-view.js:42-59 and src/main/resources/static/js/events.js:36-40 — pure functions
  without unit tests
- /.github/workflows/ci.yml:525-589 — browser tests in three browsers; :530-541 Node setup for scripts/browser-coverage

<a id="web-14"></a>

### WEB-14 — Rail order: moves are computed outside the preferences lock, and every step is a full page reload

Merged into [DATA-6](#data-6), which covers it. Impact low, effort S, status open.

<a id="web-15"></a>

### WEB-15 — Every request, including key presses and the event stream, takes the theme catalog's lock (ST2 still open on the web side)

Merged into [DATA-7](#data-7), which covers it. Impact low, effort S, status fixed at 3932f9b.

<a id="web-v1"></a>

### WEB-V1 — Repeated taps on a remote button are silently dropped by htmx's default per-element queue

Merged into [WEB-2](#web-2), which covers it. Impact medium, effort S, status fixed at 3932f9b.

<a id="ops"></a>

## OPS: Performance, resource use and operability

On small hosts the runtime is careful and economical. Request threads are virtual. Argon2 runs on two bounded platform
threads. Each device session runs on one virtual thread, and the ArchUnit rule keeps it that way. Every event-stream tab
has its own bounded queue. Device states are de-duplicated before they are published, and the caches have bounds and are
pruned. The weak side is the container's JVM and deployment defaults, and how the app explains itself when something
goes wrong. The image starts a fat jar with no JVM options: no OOM policy, the default 25% heap, no AOT cache and one 40
MB layer per release. The compose files set no TZ, no log rotation and no stop grace period. A failed play or command
reaches the browser but leaves no line in the log. There is no place in the app that shows discovery, device-connection
or system state. Two concrete defects or risks stand out. First, the mDNS bind-address open question holds, and it is
worse than the review thought: it bypasses jmDNS's own loopback guard. Second, the event stream sends no proxy-buffering
header, although the docs recommend a reverse proxy. ST1 and ST2 from the 2026-10-03 review (theme heap use and the
catalog lock) are still open, and they now also put a lock and descriptor building on every request, the health check
included. Most proposals are small (S/M); the diagnostics page is the one large item. A few points are judgement calls
that should be measured first (heap and GC defaults, AOT gains, keep-alive benefit); they are marked as such.

<a id="ops-1"></a>

### OPS-1 — mDNS binds to whatever getLocalHost() returns, loopback or a wrong NIC included, and silently loses discovery

Merged into [DEV-1](#dev-1), which covers it. Impact high, effort S, status open.

<a id="ops-2"></a>

### OPS-2 — No JVM memory or OOM policy: an OutOfMemoryError leaves a half-dead app that Docker reports as healthy

Impact **medium** · effort **S** · risk · revised by the verifier · **open**

**Problem.** The JVM sizes its maximum heap at 25% of the container limit, or of host RAM without one. Two scenarios
follow. A CasaOS user sets a 512 MB memory limit, so the heap is 128 MB. A Pi with 1 GB gets 256 MB. A theme import, or
the theme heap use in OPS-6, then raises an OutOfMemoryError on some thread. If it lands in a SessionLoop task, that
device's polling stops for good. If it lands in the rail ticker, no rail refreshes again. Tomcat still answers /health,
so Docker shows 'healthy' and restart: unless-stopped never acts. The user sees a remote with frozen state until they
restart by hand. On larger hosts (a 4 GB Pi 4 or a NAS), G1 is chosen with a 1 GB maximum heap, and the process can hold
far more memory than it needs on a box shared with other services. The heap and GC part is a judgement call; the missing
OOM policy is a fact.

**Proposal.** Add -XX:+ExitOnOutOfMemoryError to the ENTRYPOINT itself (java -XX:+ExitOnOutOfMemoryError -jar
/app/app.jar …), so that no user override can drop it. Exit code 3 then makes restart: unless-stopped recover a
half-dead app. Leave JDK_JAVA_OPTIONS unset in the image, and document it in configuration.md as the place for heap
tuning (-Xmx, -XX:MaxRAMPercentage) and for JVM system properties such as the mDNS interface (OPS-1). Change
MaxRAMPercentage or the GC only after a footprint measurement on a 1 GB arm64 host (see OPS-11); until then that part is
a judgement call. Fix OPS-6's theme memory model first or together, because with this flag an OOM during a large import
restarts the app instead of failing one request.

**Trade-offs.** With ExitOnOutOfMemoryError, an OOM during one large theme import restarts the whole app (about 20-40 s
on a Pi) instead of failing one request, so fix OPS-6 first or together. SerialGC adds pause time with large heaps,
which does not matter at 100-300 MB. The deployment tests that read the Dockerfile need an update. No product rule is
affected.

**Evidence:**

- Dockerfile:44 — ENTRYPOINT ["java", "-jar", "/app/app.jar", "--home-control.data-dir=/data"], no JVM options
- src/main/java/dev/andre/homecontrol/adapters/support/SessionLoop.java:17-18,95-97 — an Error is logged, rethrown and
  ends a periodic task for good
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:108-109 — loop.every(this::poll, …)
- src/main/java/dev/andre/homecontrol/content/RailCache.java:363-369 — the ticker catches only RuntimeException
- src/main/java/dev/andre/homecontrol/web/HealthController.java:15-18 — always 200
- compose.yaml:25 and casaos/docker-compose.yml:8 — restart: unless-stopped
- src/main/java/dev/andre/homecontrol/themes/ThemeArchive.java:25-26 — 10 MiB compressed, 30 MiB expanded

<a id="ops-3"></a>

### OPS-3 — Failed plays and commands leave no trace in the log, LAN-library noise is not pinned, and log levels are undocumented

Impact **high** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Scenario: 'Play on Living Room TV' fails with a toast. The user files a bug and pastes docker compose logs,
as the template asks. The log holds nothing about the attempt: not the device, not the route, not the reason. Only
Jellyfin's executor logs its steps at INFO. The maintainer can only ask for screenshots. There is also no documented way
to raise one module's log level (e.g. LOGGING_LEVEL_DEV_ANDRE_HOMECONTROL_ADAPTERS_TIZEN=DEBUG), or to know which levels
are safe to share. In the other direction, jmDNS logs WARN on malformed records that phones and Macs on the LAN send.
The volume is unverified, but it is outside the app's control and only org.apache.hc is pinned.

**Proposal.** 1) Give DeviceOfflineException and ActionFailedException (message, cause) constructors in core, and pass
the IOException on in DeviceCalls.run. The user-facing message stays exactly as it is. 2) Move StreamRedaction from
adapters.bluetooth.player to core (it uses only java.util.regex) and use it for every logged device or upstream message.
3) Log one INFO line per failed command (ErrorAdvice) and per failed or unroutable play attempt (ContentPlayController
or PlaybackService). It carries the device id, the action or route key, the exception kind, and the redacted cause class
and message. Optionally limit identical lines per device to one a minute. 4) In each session, log the first reach
failure after a connected state at INFO, with its redacted cause; repeats stay at DEBUG. That answers 'why is it
disconnected' at the default level. 5) Add a 'Logs and log levels' section to the user docs: docker compose logs
--since, raising one package with LOGGING_LEVEL_DEV_ANDRE_HOMECONTROL_ADAPTERS_<X>=DEBUG, and which output is safe to
paste. Do not pin jmDNS's DNSIncoming until its noise has been seen in a real log.

**Trade-offs.** A few more INFO lines in normal operation. Redaction must not hide what support needs: keep the host,
drop the query. Logging stays server-side, so the rule that secrets never reach the browser is not touched.

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java:25-67 — no logging (new StorageException handler at 46-50
  also silent)
- src/main/java/dev/andre/homecontrol/playback/PlaybackService.java:88-103 — Failed is returned unlogged
- src/main/java/dev/andre/homecontrol/web/ContentPlayController.java:88-95 — failure goes only to the browser
- src/main/java/dev/andre/homecontrol/adapters/support/DeviceCalls.java:33-39 — the IOException cause is discarded
- core/src/main/java/dev/andre/homecontrol/core/DeviceOfflineException.java:4-6 and ActionFailedException.java:12-14 —
  message-only constructors
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:291,
  adapters/androidtv/AndroidTvSession.java:215, adapters/cast/CastSession.java:234,
  adapters/support/ReconnectingPoller.java:98 — reach failures at DEBUG
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/StreamRedaction.java — existing URL/key redaction helper
- src/main/java/dev/andre/homecontrol/adapters/support/RendererCommands.java:99-107 — device fault text passed on
  verbatim
- .github/ISSUE_TEMPLATE/1-bug.yml:93-98 — bug reports ask for docker compose logs
- src/main/resources/application.yaml:30-34 — only org.apache.hc is pinned

<a id="ops-4"></a>

### OPS-4 — Add a Diagnostics section: discovery, device-connection and system state are invisible in the app

Impact **medium** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Answering 'my TV does not show up' or 'it says Disconnected' today needs docker logs and knowledge of the
code. Did mDNS start, and on which address? Did SSDP get port 1900? Is this bridge networking? Has anything been heard?
Why is the device disconnected (off, refusing the pairing, TLS pin mismatch, wrong address)? Which version is running,
in which time zone, with how much free space on /data? All of this exists inside the process. Only fragments reach the
log, mostly at DEBUG.

**Proposal.** Stage it. Phase 1 (S): one INFO startup summary line with the version, Java version, max heap, CPU count,
effective time zone, data directory with free space, the modules that are on, the mDNS bind address and interface (or
the start error), and the SSDP search and notify sockets with the listen port. Phase 2 (M): a login-gated 'System'
section on Setup, contributed through a config-level contract like SetupSection, so web depends on no discovery or
adapter package. It shows the same facts, the services heard per search target with when each was last heard, the
per-rail last success and failure (already in RailSnapshot), and a 'Copy support info' button that produces redacted
text (reusing the redaction from OPS-3). Phase 3 (L, optional): each session records its last connection failure with a
time (Reconnector, ReconnectingPoller and Tizen's own poll), exposed through a core query, never through DeviceState or
the SSE format. Show no secrets, pairing keys or URL queries. Word the bridge-network heuristic as a hint.

**Trade-offs.** The largest proposal: a new contract, plumbing of the last failure through adapters.support and core, a
template and redaction tests. It must show no secrets, URLs with queries or pairing keys, to keep the rule that secrets
never reach the browser. It stays on the LAN with no telemetry, so it fits the appliance rule. The bridge-network
heuristic can be wrong and must be worded as a hint.

**Evidence:**

- src/main/resources/templates/setup.html:75-79 — the only troubleshooting hint
- src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscovery.java:108-125,153-157 — socket failures are WARN lines
  only; listenPort() not shown
- src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java:105-108 — start result only in the log
- core/src/main/java/dev/andre/homecontrol/core/DeviceState.java:7-8 — no reason, no last contact
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:285-293 — Tizen's own reach-failure reason at
  DEBUG (no Reconnector)
- src/main/java/dev/andre/homecontrol/adapters/support/ReconnectingPoller.java:98 — UPnP/Sonos poll failures at DEBUG
- docs/dev/architecture.md:13 — SetupSection lets modules add setup sections

<a id="ops-5"></a>

### OPS-5 — The event stream sends no anti-buffering headers; behind nginx (the recommended reverse proxy setup), live updates stall

Impact **medium** · effort **S** · risk · revised by the verifier · **open**

**Problem.** nginx, and with it Nginx Proxy Manager (common on CasaOS and home-lab setups), buffers proxied responses by
default (proxy_buffering on) unless the upstream sends X-Accel-Buffering: no. The /events frames are small: a 15-byte
heartbeat every 25 s and state or rail frames of a few hundred bytes. They can sit in nginx's buffer, so power, volume
and now-playing changes reach the browser late or in bursts. The browser never shows 'Reconnecting…' because the
connection stays open, so the remote just looks stale. This is well-known nginx behaviour, but it was not reproduced
here.

**Proposal.** Return ResponseEntity<SseEmitter> from EventStreamController.events with the headers X-Accel-Buffering: no
and Cache-Control: no-cache, no-transform, and add a MockMvc test for them. Add a short 'Behind a reverse proxy'
paragraph to the user docs. With the header, nginx and Nginx Proxy Manager need nothing more; otherwise use
proxy_buffering off. proxy_read_timeout must exceed the 25 s heartbeat. Caddy and Traefik flush event streams by
themselves.

**Trade-offs.** Two headers and a doc paragraph, with no downside for direct LAN access. Proxies that ignore
X-Accel-Buffering still need their own configuration, which the doc covers. HTTPS stays the proxy's job.

**Evidence:**

- src/main/java/dev/andre/homecontrol/web/EventStreamController.java:40-44 — returns stream.subscribe(...), a bare
  SseEmitter
- src/main/java/dev/andre/homecontrol/web/EventStream.java:27-36 — heartbeat 'so a reverse proxy does not close' a quiet
  stream
- src/main/java/dev/andre/homecontrol/security/CrossOriginFilter.java:67-70 — only security headers are added
- docs/user/security.md:10-11 and docs/user/configuration.md:26 — reverse proxy recommended; no buffering guidance

<a id="ops-6"></a>

### OPS-6 — Theme catalog: ST1 (assets held twice in heap) and ST2 (one lock across installs) are still open, and every request now pays for them

Merged into [DATA-7](#data-7), which covers it. Impact medium, effort M, status partly fixed at 3932f9b.

<a id="ops-7"></a>

### OPS-7 — Compose and CasaOS defaults: no TZ, unbounded logs, a stop timeout shorter than graceful shutdown

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** Without TZ the JVM runs in UTC. A German household then sees YouTube's quota reset as '08:00 here' when it
is 09:00 CET. Sports kick-offs show in UTC until a zone is chosen, and every log timestamp is UTC, which makes 'it
failed at 20:15' hard to match. Docker's json-file driver does not rotate by default, so logs grow on the SD card for
the container's whole life; that matters more once OPS-3 adds lines. docker stop sends SIGKILL after 10 s, while Spring
waits up to 30 s for requests in flight, such as a pairing waiting for the TV prompt. Atomic writes keep the data safe,
but @PreDestroy cleanup (mpv processes, jmDNS close) is skipped. Without an init process, mpv's own child processes
would not be reaped in the -bluetooth image.

**Proposal.** In compose.yaml, add a commented TZ: Europe/Berlin under the existing commented environment block, and
logging: {driver: local} (or json-file with max-size 10m, max-file 3). In casaos/docker-compose.yml and the bluetooth
variant, add environment TZ: $TZ (a CasaOS-provided variable) and the same logging options, keeping the app id
dev.andre.shield-remote. Narrow CasaOsManifestTest's no-environment assertion to 'no keystore password variable' instead
of dropping it. Optionally set stop_grace_period: 35s for a pairing in flight. Leave out init: true unless mpv is shown
to fork.

**Trade-offs.** Configuration only; no names that must stay fixed are changed. A longer grace period makes docker stop
wait longer in the rare case of a request in flight. A household-wide time zone setting (extending SourcePreferences,
which already holds locale and region) would be the thorough fix for UTC defaults, but it needs a migration of the
sports time zone and is left as an optional follow-up.

**Evidence:**

- compose.yaml:21-25 — environment commented out; no TZ, logging or stop_grace_period
- casaos/docker-compose.yml:1-45 and casaos/docker-compose.bluetooth.yml:1-30 — no TZ, logging or stop_grace_period
- src/test/java/dev/andre/homecontrol/deployment/CasaOsManifestTest.java:28 — doesNotContainKeys("ports", "environment")
- README.md:97-98 — CasaOS manifest deliberately sets no keystore password
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeConfiguration.java:31-33 — Clock.systemDefaultZone()
- src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java:142-147 — reset shown in the JVM zone
- src/main/java/dev/andre/homecontrol/sources/sports/settings/SportsTimeZones.java:18-27 — falls back to
  ZoneId.systemDefault()
- docs/user/sources.md:434-437 — says to set TZ in the Compose file

<a id="ops-8"></a>

### OPS-8 — Take an automatic snapshot of /data before an upgrade migrates it

Merged into [DATA-1](#data-1), which covers it. Impact medium, effort M, status open.

<a id="ops-9"></a>

### OPS-9 — Layered image plus a JDK AOT cache for faster startup and smaller update pulls

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Restarts are routine for this appliance (upgrades, power cuts, the OOM exit from OPS-2), and the remote is
down for the whole start. A cold start of the full context takes about 10+ s on an x86 build host, so on a Pi 4 it is
likely several times that (unmeasured). Each release also makes every host pull the whole 40 MB jar layer, even when
only the app's classes changed, which is slow on Pi SD cards and metered links.

**Proposal.** Step 1 (S): add an extract stage (java -Djarmode=tools -jar app.jar extract --layers --destination
/extracted) and COPY dependencies, spring-boot-loader, snapshot-dependencies and application as separate layers. Keep
DockerImageTest's jar-stage assertions true, because the jar stage still feeds the extract stage. Step 2 (M, only if
OPS-11 or a Pi measurement shows that startup matters): add an AOT training run in the runtime stage
(-XX:AOTCacheOutput, -Dspring.context.exit=onRefresh, a throwaway data dir, discovery off) and start with -XX:AOTCache.
Keep it only if arm64 startup improves measurably and the image growth is acceptable. Train with the same ENTRYPOINT JVM
flags as runtime.

**Trade-offs.** The image grows by the cache, tens of MB, measured once. The Dockerfile gets more complex, and
DockerImageTest's COPY assertion changes. Training must use the same JVM options as runtime (including OPS-2's
JDK_JAVA_OPTIONS); with other options the JVM warns and ignores the cache. The training run creates beans (keystore,
secret key) only in the temporary data directory, and spring.context.exit=onRefresh exits before lifecycle beans and the
ApplicationReadyEvent listeners start, so no discovery or network traffic happens during the build. Frequent Dependabot
bumps change the dependency layer often, which lowers but does not remove the pull savings. A jlink runtime is a further
option, but it risks missing modules such as jdk.localedata, which the de-DE default needs.

**Evidence:**

- Dockerfile:28,44 — one COPY of the fat jar; java -jar
- build/libs/home-control-0.0.0-SNAPSHOT.jar BOOT-INF/layers.idx — dependencies, spring-boot-loader,
  snapshot-dependencies, application
- .github/actions/smoke-image/action.yml:69-83 and scripts/smoke-test-image.sh:50-56 — native per-architecture builds
- Dockerfile:41 — HEALTHCHECK start-period 60s

<a id="ops-10"></a>

### OPS-10 — A troubleshooting guide for 'my device does not show up' and 'play fails'

Impact **medium** · effort **S** · docs · confirmed · **open**

**Problem.** The most common support questions have causes outside the app that the docs do not name. A host firewall
with default-deny input (ufw, firewalld) drops the unicast M-SEARCH replies, because conntrack does not match them to
the multicast request. TVs then appear only when their next NOTIFY arrives, which can be many minutes later. Docker
Desktop on macOS or Windows has no LAN host networking. Mesh Wi-Fi and IGMP snooping filter multicast. Another SSDP
server (minidlna, Plex, Home Assistant) can hold port 1900. A multi-homed host binds the wrong interface (OPS-1). For
play failures, users do not know what each toast means, how to raise one adapter's log level, or that a deep-link test
and alternative routes exist.

**Proposal.** Add docs/user/troubleshooting.md, linked from the setup page hint and the bug template. It holds a
discovery checklist: host networking; firewall rules for UDP 5353, UDP 1900 and SSDP replies from the LAN; Docker
Desktop; VLANs and multicast; the 'Not listening for SSDP announcements' log line; the discovery interface setting; and
adding a device by address. A connection section maps each status to its usual cause. A play section explains the
toasts, the deep-link test and how to turn on DEBUG for one adapter. A logs section covers docker compose logs --since,
TZ, and what is safe to paste. Cross-link it from OPS-4's diagnostics view once that exists.

**Trade-offs.** Docs only. They have to be kept true as the code changes; the 2026-10-03 review found recovery text that
contradicted the guides (FB1, FB5, FB6), so check it against the code.

**Evidence:**

- docs/user/devices.md:229-232 — the whole discovery troubleshooting section
- src/main/resources/templates/setup.html:75-79 — only network_mode: host is mentioned
- src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscovery.java:108-125 — ephemeral search port; the port-1900
  listener can fail with only a WARN
- docs/user/bluetooth-speakers.md:97-98 — an existing symptom/fix table to follow

<a id="ops-11"></a>

### OPS-11 — Measure the runtime footprint in CI: startup time, RSS and image size

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** Several decisions here depend on numbers nobody tracks: heap defaults (OPS-2), the AOT cache (OPS-9), and
the theme memory model (OPS-6). A dependency bump that adds seconds of startup or 100 MB of RSS would ship unnoticed,
and the target hosts (Pi, NAS) are the most sensitive to it.

**Proposal.** In smoke-test-image.sh, after the container is healthy, record the Started time, RSS after a short idle
(docker stats --no-stream), the JVM's max heap (java -XX:+PrintFlagsFinal -version | grep MaxHeapSize in the image) and
the image size, and write them to $GITHUB_STEP_SUMMARY. Leave baseline comparison for later (M), because it needs a
stored history. Separately, measure once on a 1 GB arm64 host and use those numbers for the OPS-2 heap defaults and the
OPS-9 AOT decision.

**Trade-offs.** GitHub's runners are not Pis, so the numbers are relative and good for spotting regressions, not
absolute budgets. RSS right after start varies by a few MB. CI runs slightly longer (about 30 s per image).

**Evidence:**

- scripts/smoke-test-image.sh:95-96 — Started line grepped and printed, not recorded
- scripts/smoke-test-image.sh:77-106 — container started natively and waited on until healthy
- docs/dev/architecture.md:121-136 — progress measures have no startup or memory row

<a id="ops-v1"></a>

### OPS-V1 — Every open dashboard tab keeps an SSE connection open, even when hidden; over plain-HTTP LAN access this uses up the browser's six connections per host

Merged into [WEB-4](#web-4), which covers it. Impact medium, effort S, status open.

### Rejected in OPS

- **OPS-12 — Let outbound HTTP reuse connections within a vetted route.** The facts are right:
  VettedHttpClients.java:17-19,53 turns reuse off. The proposal is not worth it. The benefit is unmeasured and small.
  TMDB watch-provider lookups are cached for 24 h in an LRU of 2000 (application.yaml:178-180, provider-cache-ttl: 24h),
  so the 40-lookup loop at TmdbContentSource.java:115-120 is mostly cache hits after the first refresh. Thumbnails and
  Jellyfin images are browser-cached (YouTubeThumbnailController sets max-age 1 day; JellyfinImageController.java sets
  max-age 3600 or an immutable year), and RailCache never makes a page wait. The cost lands in the most
  security-sensitive client. The class doc names reuse among the deliberate offs, and GuardedHttpClient cancels
  exchanges instead of draining them (:303-317). With automatic retries off (:51), a pooled connection the server has
  closed turns into a user-visible rail or search failure. The proposed 'close the pool when the policy changes' is
  moot: every OutboundAddressPolicy is fixed at construction from properties (e.g. TmdbClient.java:41,
  YouTubeHttp.java:81-86). For a single-household LAN appliance, the risk outweighs a modest TLS-handshake saving.

<a id="test"></a>

## TEST: Testing strategy and test quality

The suite is large and carefully built. It has 4,366 JVM tests (3,711 in the app, 409 in protocols, 246 in core) and 119
browser tests run on three engines. The app's `test` task takes 171 s of wall time across four JVMs (538 s summed);
protocols takes 67 s in one JVM. Its habits are better than most projects': shared Spring contexts enforced by an
ArchUnit allow-list, fakes that speak the real protocol over real sockets, Awaitility instead of sleeps, servers on port
0, per-worker data directories and a 60 s default timeout. Leaving out the generated protobuf code, line coverage is
97.3% and branch coverage 90.0%. So the remaining risk is not code that never runs. It is weak assertions, inputs nobody
thought of, and product rules that are checked one file or one adapter at a time. The biggest gaps: no test boots the
app on a whole old /data directory, LAN-facing parsers and the ICS recurrence engine never see generated input, two
fakes reuse the production framing code (so a framing mistake is the same on both sides and invisible), and the
documented configuration is never checked against what actually binds. One open question from the 2026-10-03 review
holds up as a likely field defect: mDNS can bind to 127.0.1.1 on Debian-family hosts. The TMDB environment-variable
question and the rail-order race are also still open. Smaller debt: six new wall-clock upper-bound assertions break the
suite's own rule, the browser suite restarts the app about 25 times per engine, and some numbers in the testing docs are
stale.

<a id="test-1"></a>

### TEST-1 — mDNS binds to the loopback address when the host name resolves to 127.0.1.1; add the missing test and fall back

Merged into [DEV-1](#dev-1), which covers it. Impact high, effort S, status open.

<a id="test-2"></a>

### TEST-2 — Test upgrades of a whole install, not one file at a time: complete old /data directories, a second start that changes nothing, and fixtures that freeze each current format

Impact **medium** · effort **M** · risk · revised by the verifier · **open**

**Problem.** 'Existing installs upgrade in place' is checked one file at a time. Nothing starts the application on a
realistic older /data directory holding devices v1/v2 with TV keys, sources v1 sections, secrets v1, a keystore with the
old 'shield' password, pins, sports and themes, all at once. Coupling between migrations goes untested: adapter.migrate
moving keys into secrets.json, Enrollment re-saving devices, backups being written. So does idempotence: a second start
must not write new backups or change any bytes. Reader and writer live in the same class, so a renamed record field
passes every write-then-read test. Scenario: someone renames a field in RememberedLogins.Login or in the theme catalog's
record. All tests stay green. After the upgrade, existing installs lose every remembered login, or the theme catalog
becomes unreadable and refuses changes.

**Proposal.** 1. Move devices-v1.json into fixtures/devices/ and read it with Fixtures. Add synthetic whole-install
snapshots under fixtures/data/<name>/: 'v0.3' (a v1 registry and a keystore made with 'shield'), 'pre-device-secrets'
(devices v2 with a webOS clientKey and a Tizen token, sources v1, secrets v1, pins, sports) and 'current'. 2. Add a
DataUpgradeTest in the style of KeystoreUpgradeStartupTest. It copies each snapshot into a @TempDir and boots
HomeControlApplication. Assert that it starts; that devices are listed and their TV keys have moved into secrets.json;
that pins, sports settings and the rail order are visible; that each migrated file has exactly one .v<N>.json backup;
and that a second boot writes no new backup and changes no file bytes. 3. Add the missing current-format fixtures
(devices v3, sources v2, logins.json, themes/catalog.json, youtube-quota.json) with a read-then-assert test for each,
like JsonFilePinStoreTest.readsTheDocumentedShape. For logins, include a valid literal entry that must be remembered. 4.
Instead of a release checklist (releases are automatic), add a guard test: list the `new VersionedJsonFile<>(` call
sites with their VERSION and fail when no committed '<name>-v<VERSION>.json' fixture exists, so a format bump forces a
fixture and a migration. 5. Optional, main branch only: in the smoke-image job, start the previous published image, seed
state, then start the new image on the same volume.

**Trade-offs.** Fixtures need upkeep: each format change adds one file. That is the point, since it forces a version
bump and a migration. Step 5 needs a GHCR pull in CI and adds a few minutes, so it could run only on main. This directly
enforces the AGENTS.md rule that existing installs upgrade in place.

**Evidence:**

- src/test/java/dev/andre/homecontrol/adapters/androidtv/KeystoreUpgradeStartupTest.java:18-45 — boots on an empty or
  keystore-only data directory
- src/test/java/dev/andre/homecontrol/adapters/webos/WebOsKeyMigrationTest.java:50-65 — registry and secret migration
  together, without Spring
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:106,127-132 — adapters migrate registered devices at
  startup (unchanged; 55ed395 edits only merge/matching)
- src/test/resources/fixtures/{devices,pinned,sports,sources,secrets}/ — existing format fixtures;
  src/test/resources/devices-v1.json still outside fixtures/, read with Path.of (JsonFileDeviceRegistryTest.java:163)
- src/test/java/dev/andre/homecontrol/sources/pinned/JsonFilePinStoreTest.java:37-51 and
  sources/sports/settings/JsonFileSportsStoreTest.java:35-45 — current-format freeze tests
- src/test/java/dev/andre/homecontrol/storage/VersionedJsonFileTest.java:70 — backup kept once on repeated migration
- src/main/java/dev/andre/homecontrol/security/RememberedLogins.java:168-195 — explicit JSON keys;
  src/test/java/dev/andre/homecontrol/security/RememberedLoginsTest.java:140-145 — only literal entries, all invalid
- scripts/smoke-test-image.sh:115-124 — image upgrade check seeds only a root-owned '[]'

<a id="test-3"></a>

### TEST-3 — Check docs/user/configuration.md against what actually binds: every documented variable reaches its property and every documented default is real

Merged into [DOCS-1](#docs-1), which covers it. Impact medium, effort M, status open.

<a id="test-4"></a>

### TEST-4 — Compare ICS recurrence expansion with a deliberately simple reference on generated rules, including dates around DST changes

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Recurrence expansion combines interval, week start, BYDAY, COUNT or UNTIL, EXDATE, overrides, zones and
DST. Bugs there are classically combinatorial, and the 2026-10-03 review found eight at the edges. The example tests
cover the cases someone thought of. Scenario: a weekly rule with INTERVAL=2;BYDAY=SU;WKST=SU, DTSTART at 02:30
Europe/Berlin on 2027-03-28 (inside the spring-forward gap) and COUNT=5. Whether the first instance moves to 03:30 as
RFC 5545 requires, and whether COUNT counts it, is untested. A wrong answer shows a match on the wrong day or hour, or
drops one.

**Proposal.** 1. Now (S): add two example tests that pin the DST semantics explicitly. One has a weekly series with
DTSTART;TZID=Europe/Berlin:20270328T023000 and expects 01:30Z with COUNT honoured. The other has a series at 02:30 on
the fall-back day and expects the earlier, CEST offset. An EXDATE given in the same local time must remove exactly that
instance. 2. Optional (M): a seeded oracle test in protocols for the combinatorial part only. Generate DAILY/WEEKLY
rules with INTERVAL, WKST, BYDAY, COUNT or UNTIL and EXDATE, in UTC and floating time, so DST does not enter the oracle.
A naive reference walks every day from DTSTART. Assert that IcsOccurrences.expand matches whenever the step budget is
not reached, and print the seed on failure. Use a plain seeded Random rather than adding jqwik, which would need
verification-metadata upkeep.

**Trade-offs.** jqwik is a new test dependency, which needs a verification-metadata update and gets Dependabot updates.
A 40-line seeded generator avoids that but loses shrinking. The reference must stay naive (quadratic is fine) or it just
repeats the production logic. Runtime can be kept to a few seconds with a few hundred cases per run.

**Evidence:**

- protocols/src/main/java/dev/andre/homecontrol/sources/sports/ics/IcsRecurrence.java:15-124 — the supported subset
- protocols/src/main/java/dev/andre/homecontrol/sources/sports/ics/IcsOccurrences.java:221-307 — expansion; atZone at
  276 and 297 decides gap/overlap behaviour
- protocols/src/test/java/dev/andre/homecontrol/sources/sports/ics/IcsOccurrencesTest.java:159-180 —
  wallClockSurvivesDst, the only DST test
- protocols/src/test/java/dev/andre/homecontrol/sources/sports/ics/IcsOccurrencesEdgeCaseTest.java:33-173 — hand-picked
  edge cases (48-64 new in 67a2cea, durations only)

<a id="test-5"></a>

### TEST-5 — Fuzz the parsers that read unauthenticated LAN input, starting with seeded mutations of the recorded fixtures

Impact **low** · effort **M** · risk · revised by the verifier · **open**

**Problem.** Anything on the LAN can send SSDP datagrams, and any device or anything spoofing one can answer with XML,
JSON or framed protobuf. Each parser has an implicit contract: return a value, return empty, or throw one named
exception, quickly. Nothing checks those contracts on inputs nobody wrote by hand. Scenario: a Sonos ZoneGroupState with
a member missing Location, or a device description whose URLBase is an opaque URI, or a Cast payload with a string where
a number belongs. If any of them throws a NullPointerException or ClassCastException that the caller's catch does not
expect, a discovery or poll loop can die or a page can return 500 (the TV1/TV2 pattern).

**Proposal.** Add one deterministic ParserRobustnessTest in protocols, with no new dependency, for the parsers that
unauthenticated LAN peers feed: SsdpMessage.parse, DeviceDescriptions, ZoneGroupState.parse and the Cast and UPnP
payload readers. Declare each parser's contract in a table: the allowed result and the single allowed exception type.
Take the recorded fixtures as seeds and generate a few hundred seeded mutations per seed: truncation, byte flips,
duplicated or oversized headers and attributes, 20-digit numbers, invalid UTF-8. Assert the contract, with @Timeout as a
hang guard, and print the seed of any failure. Skip the session-level garbage pushes: the session loops already catch
and reconnect, and RemoteConnectionTest covers Android TV. Jazzer stays out of scope.

**Trade-offs.** Stage 1 is deterministic and cheap, a few seconds, and fits the 'no flaky tests' culture. It finds fewer
bugs than coverage-guided fuzzing. Jazzer adds a native agent and a dependency to verify, so keep it out of `build`.
Contracts may need small production fixes once they are written down, which is the point.

**Evidence:**

- protocols/src/test/java/dev/andre/homecontrol/discovery/ssdp/protocol/SsdpMessageTest.java:116-125 — a single seeded
  random datagram (170-175: one new hand-written case, 308eac5)
- protocols/src/main/java/dev/andre/homecontrol/discovery/ssdp/protocol/SsdpMessage.java:28-30 — point fix for the
  line-breaks-only crash (308eac5)
- src/main/java/dev/andre/homecontrol/discovery/ssdp/SsdpDiscovery.java:213-214 — receive loop catches RuntimeException
- protocols/src/main/java/dev/andre/homecontrol/adapters/sonos/protocol/ZoneGroupState.java:73-84 — missing or invalid
  Location dropped, not thrown
- protocols/src/test/java/dev/andre/homecontrol/adapters/androidtv/protocol/RemoteConnectionTest.java:288-293 —
  malformed varint frame already tested
- protocols/src/testFixtures/resources/fixtures/{ssdp,upnp,sonos,cast,webos,tizen}/ — seed corpora

<a id="test-6"></a>

### TEST-6 — Pin wire formats independently of the shared codecs, test the in-memory BlueZ fake against the real D-Bus client, and record where each fixture came from

Impact **low** · effort **M** · risk · revised by the verifier · **partly fixed at 3932f9b**

**Problem.** When the fake and the client share a codec, a mistake in it is the same on both sides and the tests stay
green. The Android TV comment's premise is false for app links of 128 bytes or more. Those get a two-byte varint prefix.
Whether a real TV accepts that is unverified, because the fake decodes with the same code. Scenario: a pinned DAZN or
Netflix link longer than about 120 characters goes to a Shield, and nothing in the suite would notice if the device
framed it differently. Separately, FakeBluezClient re-implements BlueZ behaviour (already-paired handling, failure
classification) that can drift from DbusBluezClient, and seven test classes trust it. And without provenance, nobody can
tell which fixtures were captured from real devices and which were hand-written. That weakens the AGENTS.md promise that
fakes speak the real protocol.

**Proposal.** (a) S: correct the MessageStream Javadoc and the design spec. Framing is protobuf varint delimiting, so
messages of 128 bytes or more, such as long app links, get a multi-byte prefix. Add one MessageStreamTest that writes a
200-character app link and asserts a two-byte varint prefix and a round trip, so the behaviour is stated. Mark in the
spec that real-device acceptance of multi-byte prefixes is unverified until someone captures it. (b) Optional, when
Bluetooth work resumes: an abstract BluezClientContract run against FakeBluezClient and against DbusBluezClient over
FakeBluezService, holding the shared expectations (already-paired or already-connected counts as success, failure
classification). (c) Optional: one short PROVENANCE note per fixtures directory saying recorded or hand-written, the
device or API version, and what was redacted. No enforcing test; it is not worth a gate.

**Trade-offs.** Real captures need hardware. Where none is available, writing 'hand-written from the spec' honestly is
still worth it. The contract test makes BlueZ behaviour changes cost two edits. If androidtvremote2 or the device does
use varint delimiting, (a) simply confirms the code; the comment still needs fixing.

**Since the analysis.** 04928a4 rewrote the MessageStream Javadoc (MessageStream.java:13-20). It now says every message
has a protobuf varint length prefix, which drops the false 'always shorter than 128 bytes' premise, and adds a 64 KiB
read cap. It also added MessageStreamTest.readsAMessageWhoseLengthTakesSeveralBytes (76-82), a round trip of a 300+
character link, plus hand-written varint prefixes for the rejection cases (113-136). Still open for (a): the design spec
still states the single-byte premise (docs/superpowers/specs/2026-08-29-shield-remote-design.md:129-133) and does not
mark multi-byte prefixes as unverified on real devices. The new round trip writes and reads through the production
MessageStream and never asserts the two-byte prefix bytes, and FakeRemoteServer still uses the production MessageStream.
(b) The BluezClientContract and (c) the fixture provenance notes are not started.

**Evidence:**

- protocols/src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/MessageStream.java:13-20 — Javadoc now
  states varint framing (04928a4)
- docs/superpowers/specs/2026-08-29-shield-remote-design.md:129-133 — still says single-byte length, always shorter than
  128 bytes
- protocols/src/test/java/dev/andre/homecontrol/adapters/androidtv/protocol/MessageStreamTest.java:76-82 — multi-byte
  round trip, no prefix-byte assertion (04928a4)
- core/src/main/java/dev/andre/homecontrol/core/playback/AppLinks.java:22 — MAX_LENGTH = 2048
- protocols/src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/RemoteConnection.java:142-146 — an app link
  sent in one frame
- protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/androidtv/protocol/FakeRemoteServer.java:272-273 — fake
  uses the production MessageStream
- protocols/src/test/java/dev/andre/homecontrol/adapters/cast/protocol/CastFramingTest.java:30-38 — Cast's prefix pinned
  independently
- src/test/java/dev/andre/homecontrol/adapters/bluetooth/bluez/FakeBluezClient.java:10-11 — in-memory BlueZ;
  src/test/java/dev/andre/homecontrol/adapters/bluetooth/bluez/DbusBluezProtocolTest.java:116-129 — AlreadyExists
  counted as success

<a id="test-7"></a>

### TEST-7 — A device-session contract kit that every adapter runs: commands are ephemeral, close stops everything, reconnects replay nothing

Merged into [ARCH-15](#arch-15), which covers it. Impact low, effort S, status open.

<a id="test-8"></a>

### TEST-8 — Measure assertion strength with mutation testing (PIT) on core, protocols and the validators, since line coverage is already 97%

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** With coverage this high, the open question is whether the assertions would notice a change. Validators and
allow-lists are where a deleted or inverted condition fails open without breaking any test. A rule like 'kind must be
video or audio' can be removed today and every test passes. In ThemeCss, the catch-all for constructs it does not
recognise has never been exercised. A ph-css upgrade that starts producing a new member type would reach it untested,
or, if a case arm is ever loosened, pass hostile CSS without a test failing.

**Proposal.** 1. S: add a table-driven WorkflowValidatorRejectionTest, one row per fail() message that JaCoCo shows
unreached (definition has no draft, mode is required, kind must be video or audio, too many calls, duplicate mapping or
call or header names, single or generated mode rules, pointer rules). Each row asserts the exact message. 2. Optional,
M: add the info.solidsoft.pitest plugin, scoped to core and to protocols' pure parsers (ics, ssdp.protocol,
upnp.protocol, sonos.protocol). Keep it out of `build`, run it on a label or weekly, start with a baseline and no gate,
and write tests only for surviving mutants in validators and allow-lists.

**Trade-offs.** A new plugin means verification-metadata updates and Dependabot updates. Full-suite PIT would be far too
slow (538 s of tests, many of them waiting on sockets), so scoping is essential. Mutation scores need judgement:
equivalent mutants exist, so do not chase 100%.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowValidator.java:33,35,36,53,55,56 — untested rejection
  branches (and ~25 more, e.g. 59, 66, 76, 84, 100, 108, 125-137, 153, 161-173)
- src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowDefinitionTest.java:89 and
  WorkflowHeaderTemplateTest.java:36 — header-injection checks tested
- src/main/java/dev/andre/homecontrol/themes/ThemeCss.java:300 — default arm throws (fails closed); ph-css now 8.4.1
  (2cbb430)
- build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml — build artifact measured at f9f9de9
  (WorkflowValidator BRANCH missed=32)

<a id="test-9"></a>

### TEST-9 — Shorten the slow spots: protocols runs in one JVM, a D-Bus test waits about 9.5 s per connection attempt, and three classes each import the classes for ArchUnit

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** The suite is not slow overall. Most of its time is deliberate waiting in the session tests, which is
acceptable. But three things cost more than they should. A developer running `scripts/gradle.sh :protocols:test` waits
67 s where about 25 s would do. A single D-Bus test costs 19 s. And that 9.5 s per attempt is unexplained: if the
production client also waits that long when the socket file exists but nobody serves it (an unverified guess), the
Bluetooth part of the setup page would hang about 10 s per check. ArchUnit imports the same test classes twice per JVM.

**Proposal.** 1. Move maxParallelForks (capped at 4) into the shared tasks.withType<Test>() block in allprojects, so
local `:protocols:test` runs use several JVMs. 2. Bound dbus-java's connect and authentication wait by the client's
configured reply timeout, through the builder's transport configuration, so a dead bus fails within the configured time.
Keep the existing test as a hang guard with a @Timeout rather than a wall-clock assertion. This also cuts the suite's
slowest single test from about 19 s to a few seconds. 3. Drop the ArchUnit import-sharing idea; the gain is too small to
justify the change.

**Trade-offs.** More JVMs in protocols compete with the app's four JVMs on a 4-CPU runner. In a full build protocols
overlaps the app's tests, so the gain is mostly in local per-module runs. ArchUnit resolution limits could weaken rules
that need supertypes resolved, so keep them only if every rule still fails on a planted violation.

**Evidence:**

- build.gradle.kts:210 — maxParallelForks only on the app's test task
- protocols/build/test-results/test — build artifact measured at f9f9de9 (44 classes, 66.8 s; RemoteConnectionTest 27.3
  s)
- src/test/java/dev/andre/homecontrol/adapters/bluetooth/bluez/DbusBluezClientTest.java:35-47 — ~19 s, two ~9.5 s
  attempts
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/bluez/DbusBluezClient.java:218 —
  DBusConnectionBuilder...build() without a transport timeout
- dbus-java-core-5.2.2.jar org/freedesktop/dbus/connections/config/TransportConfig (javap) — timeout default 10000
  (dbus-java still 5.2.2)
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/BluetoothHostChecks.java:63-70 — results cached

<a id="test-10"></a>

### TEST-10 — Six wall-clock upper-bound assertions have crept back against the suite's own rule; turn them into outcome checks

Impact **low** · effort **S** · debt · confirmed · **open**

**Problem.** There are seven upper bounds, not one. Their margins are generous, so the flakiness risk is low, but it
grows on a loaded runner. The real cost is that the rule and the progress measure now disagree, and nothing stops more
from appearing. Each of these behaviours can be stated without a stopwatch.

**Proposal.** In the WorkflowCalls and WorkflowHttpClient tests, give the run a long deadline (minutes), so the only way
to return is not waiting for the held call. The per-test @Timeout then acts as a hang guard, and the test asserts the
404 or 'busy' outcome. In the slot tests, assert ordering: publicInfo or postForm returned while every image or
thumbnail request is still held (the fake still records MAX_CONCURRENT open requests and the release latch is still at
1). Add a small source-scanning guard, like InlineCodeTest, that flags `nanoTime() -` combined with isLessThan outside
an allow-list containing EventStreamShutdownEndToEndTest. Update the progress table.

**Trade-offs.** It is mechanical. A deadline measured in minutes relies on the 60 s default timeout to fail a
regression, so the failure is slower but deterministic.

**Evidence:**

- docs/dev/testing.md:210-211 — the rule
- docs/dev/architecture.md:134 — 'Wall-clock upper-bound assertions | 9 | 1'
- src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClientTest.java:84
- src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowCallsTest.java:136,159,202
- src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinClientTest.java:69-70
- src/test/java/dev/andre/homecontrol/sources/youtube/YouTubeHttpTest.java:97-98

<a id="test-11"></a>

### TEST-11 — Browser tests start the application about 25 times per engine; share one context and reset it with FullAppReset

Impact **low** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** The JVM suite solved per-class isolation with one shared context plus FullAppReset, which took context
starts from 67 to 22. The browser suite still evicts after every class, and after every method in two classes: about 25
starts in each engine's CI job, and about 75 when all three engines run locally. At 3-5 s each, that is roughly 1.5-2
minutes per engine. This is an estimate, since no e2e timings were available locally. It also keeps a second isolation
mechanism alive.

**Proposal.** 1. Give E2eApplicationTest one shared context and data directory, and reset it after each class with
FullAppReset.reset(context) plus new reset() methods on FakeContentSource and FakeDeviceAdapter. 2. Replace method-level
eviction in SetupFlowsE2eTest and PinUpgradeE2eTest with the same reset after each method. 3. Keep separate contexts
only for WorkflowE2eTest and YouTubeOAuthE2eTest, which register their own properties. 4. Extend
SharedContextRulesTest's import to the e2e classes, or add a sibling rule. Expected gain: about 20 fewer context starts
per CI engine job, and about 36 fewer when all three engines run locally.

**Trade-offs.** State leaking between browser tests is harder to debug than in MockMvc tests. FullAppResetTest already
guards the reset. Playwright time dominates, so the gain is moderate.

**Evidence:**

- src/e2e/java/dev/andre/homecontrol/e2e/E2eApplicationTest.java:24-38 — class-level eviction and per-class data
  directory
- src/e2e/java/dev/andre/homecontrol/e2e/SetupFlowsE2eTest.java:18 (5 @BrowserTest methods) and
  PinUpgradeE2eTest.java:22 (3) — eviction after each method
- src/e2e/java/dev/andre/homecontrol/e2e/BrowserTest.java — @ParameterizedTest over all browsers within one class
- build.gradle.kts:233-238 — e2e source set includes test output
- docs/dev/architecture.md:151-153 — about 4.7 s per context start

<a id="test-12"></a>

### TEST-12 — Check FullAppReset's completeness structurally, not only state by state

Impact **low** · effort **S** · risk · confirmed · **open**

**Problem.** The reset is complete only if someone remembers to add a step. Scenario: a new module writes
/data/foo.json, or keeps a cache, and its author forgets the step. The next FullAppTest class in the same JVM sees that
state. Gradle assigns classes to JVMs differently as classes are added, so failures show up on unrelated pull requests
and look flaky.

**Proposal.** After the first reset of a fresh context, record a fresh-install fingerprint: the data directory's file
names plus canonical-JSON hashes of their contents. Assert after every later reset that the directory matches it, and
name any file that differs. Optionally do the same for selected caches, by asserting key read-only views (rails.peek(),
pins.all(), and so on) are empty.

**Trade-offs.** Files rewritten with equivalent content (for example an empty pin list) need canonical comparison. It
covers /data and not in-memory state, which still needs the explicit probes.

**Evidence:**

- src/test/java/dev/andre/homecontrol/testsupport/FullAppReset.java:55-110 — the hand-written steps
- src/test/java/dev/andre/homecontrol/testsupport/FullAppResetTest.java:103-147 — per-state probes
- src/test/java/dev/andre/homecontrol/testsupport/FullAppTest.java:60-72 — filesAtStartup recorded; only used at
  sources/sports/SportsUnconfiguredTest.java:37
- docs/dev/testing.md:61-62 — 'New state that outlives a test class needs a line in FullAppReset'

<a id="test-13"></a>

### TEST-13 — Make local runs honest: stale numbers in the guides, tests silently skipped as root, Linux-only loopback addresses, and a coverage report that counts generated code

Impact **low** · effort **S** · docs · revised by the verifier · **open**

**Problem.** Several small mismatches make the suite look different from what it is. Agents and containers that run as
root never execute the data-directory permission tests, which guard the '/data must be writable' startup message. A
macOS contributor with a local JDK gets about seven test classes failing with 'Cannot assign requested address'. The
coverage report in the repository understates real coverage by about 25 points. If SonarCloud also counts the generated
sources, which I could not verify (no sonar exclusion is set and the protobuf plugin adds them to the main source set),
the project figure is understated there too.

**Proposal.** 1. Replace the dated count in testing.md:15 with a pointer to where the current counts are shown (the PR
summary or the test report), and drop or complete the example list at line 244. 2. In CI, fail if any of the six
root-guarded tests is reported as skipped (a grep over the JUnit XML), so the '/data must be writable' guards are proven
to run somewhere. 3. Document the macOS loopback aliases for native local-JDK runs, or say plainly that
scripts/gradle.sh is the supported path there. 4. Exclude the generated protobuf packages (androidtv/protocol/remote,
androidtv/protocol/pairing, cast/protocol/channel) from testCodeCoverageReport's classDirectories. Set
sonar.coverage.exclusions for them if SonarCloud turns out to count them.

**Trade-offs.** Documentation and build hygiene only; no product rule is affected.

**Evidence:**

- docs/dev/testing.md:15 — dated count of 2,689 (example list at 244)
- src/test/java/dev/andre/homecontrol/storage/DataDirectoryTest.java:65,123,132;
  security/RememberedLoginsTest.java:76,117; security/RequestLoginContextTest.java:205 — assumeFalse(root)
- scripts/gradle.sh:11 — container runs as the host user
- src/test/java/dev/andre/homecontrol/adapters/sonos/SonosSessionTest.java:58-59, SonosDiscoveryTest.java:49-54,
  web/SpeakersEndToEndTest.java:71-72 — 127.0.0.2/.3
- build.gradle.kts:32-35 and :83 — combined report used by Sonar, no exclusions

<a id="test-14"></a>

### TEST-14 — Two concurrent rail moves can lose one (open review question still holds); move the computation inside the update and add a race test

Merged into [DATA-6](#data-6), which covers it. Impact low, effort S, status open.

<a id="test-v1"></a>

### TEST-V1 — Check 'secrets never reach the browser' across the whole app with canary secrets, instead of one feature at a time

Impact **medium** · effort **M** · risk · found by the verifier · **open**

**Problem.** The rule is checked one feature at a time, so a new path to the browser is covered only if its author
remembers to check it. One path is open today and unverified. Scenario: a Jellyfin item is played on a DLNA renderer,
which rejects it with a SOAP fault whose description echoes the CurrentURI (some renderers do this). DeviceCalls turns
that into 'Living Room refused to play …: Unsupported URI http://nas:8096/…&ApiKey=<token>', and the toast shows the
Jellyfin token in the browser. A Cast receiver whose LOAD_FAILED message names the contentId leaks it the same way. No
test would notice, because each device test asserts only its own message.

**Proposal.** Add one FullAppTest, for example SecretsStayOnTheServerEndToEndTest. 1. Seed every kind of secret with a
unique canary value: the login password, the Jellyfin token, the TMDB key, YouTube OAuth tokens, workflow header
secrets, the webOS client key and the Tizen token. 2. Configure the shared fakes to echo request content in their
faults: a FakeUpnpRenderer planned fault whose description contains the CurrentURI it received, and a FakeCastReceiver
LOAD_FAILED message containing the contentId. 3. Request every GET mapping from RequestMappingHandlerMapping, filling
path variables from fixtures, plus the SSE snapshot. Trigger each play action against each fake device. 4. Assert that
no canary appears in any body, header, flash attribute or event. The test should fail today on the echo path, which also
answers the review's open question. Then redact query strings, or at least ApiKey/api_key/token parameters, in device
fault text in one place: DeviceCalls, or where SoapFault and CastApps.reason build their messages.

**Trade-offs.** Medium effort: route enumeration needs fixture values for path variables, and echoing fakes need a small
option. It runs in the shared FullAppTest context, so it adds little time. It enforces an AGENTS.md product rule
directly and breaks none. Redacting fault text slightly reduces how much detail a user sees from a device's error
message.

**Evidence:**

- AGENTS.md:24 — 'Secrets are encrypted at rest and never reach the browser'
- src/main/java/dev/andre/homecontrol/adapters/support/DeviceCalls.java:35-36 — 'refused to …: ' + e.getMessage()
- protocols/src/main/java/dev/andre/homecontrol/adapters/cast/protocol/CastApps.java:150-153 — receiver 'message'
  returned verbatim
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinStreams.java:99 — ApiKey=… in the stream URL
- docs/superpowers/reviews/2026-10-03-codebase-review.md:570-573 — open question still open; only StreamRedaction at
  BluetoothHostChecks.java:140
- protocols/src/testFixtures/java/dev/andre/homecontrol/adapters/upnp/protocol/FakeUpnpRenderer.java:54-73,131 — planned
  fault with any description
- src/main/java/ — 28 @GetMapping, no test walks the routes

<a id="build"></a>

## BUILD: Build, CI/CD, release, supply chain and packaging

The build and delivery pipeline is unusually careful about supply-chain hygiene. Everything is pinned, dependency
verification is strict and its updates are automated in a well-isolated way, each architecture is built natively from
one jar, and the release only re-tags image digests that were already smoke-tested, with provenance and an SBOM
attached. The weak spot is the release policy, not the mechanics. Dependency and base-image updates are committed as
`build(deps)`, and the conventionalcommits preset never releases those, so security fixes waited 25 to 35 days for an
unrelated `fix:`. Meanwhile a CI-only `fix(ci)` commit did cut a release, and per-commit releases produced 9 versions in
under 2 hours. The documented pre-1.0 rule ("feat! bumps the minor") is not implemented: the version action passes no
preMajor setting and has no 0.x handling, so any breaking-change commit fails the jar job. Dependabot churn is high:
about 46 PRs and 60 commits on main in 6.5 weeks, with each Actions bump duplicated per directory and each Gradle bump
costing two PR runs plus a main run. That churn is made worse by over-broad cache keys: the whole test task re-runs on
any workflow edit, and the browser cache resets on any catalog bump. On main, the release waits about 3.5 minutes for a
SonarCloud scan that does not check the quality gate there. Published images are never scanned for OS-package CVEs, and
the CasaOS manifest is stale and runs the image as root. Note: this checkout (f9f9de9, 2026-11-17) is behind origin/main
(3932f9b, v0.21.24); release and run timings come from GitHub's API for main.

<a id="build-1"></a>

### BUILD-1 — Dependency and base-image updates never reach a release, and CI-only fixes do

Impact **high** · effort **S** · defect · confirmed · **open**

**Problem.** Versions come only from feat/fix/perf/revert commits. Dependabot writes `build(deps)`, which the preset
hides, so the changelog is empty and the run releases nothing. Example: the Nov 7 eclipse-temurin digest bump carried
Ubuntu security fixes in the JRE base, and Spring Boot 4.1.2 was merged Nov 1. Users on `latest` and CasaOS got neither
until a feature fix happened to land on Nov 26, 19 to 33 days later. The README promise that latest is the newest code
is false for exactly the updates that carry security fixes. The opposite also happens: `fix(ci)` passes check-commits.sh
(line 11 accepts any scope), so a CI-only change published v0.21.15 with an unchanged app. There is also no manual way
to publish a maintenance release.

**Proposal.** (1) In dependabot.yml, give the ecosystems that ship in the image a releasable prefix: `commit-message:
{prefix: fix, include: scope}` for `gradle` and `docker`, producing `fix(deps): bump …`. Keep `build`/`ci` for
github-actions and npm, which never ship. (2) Add `workflow_dispatch` (input: release type, default patch) so a
maintainer can release a rebuilt base or a dependency batch on demand, and pass it to the plan step as a forced patch.
(3) In scripts/check-commits.sh, reject feat/fix with a non-product scope (`ci`, `test`, `build`, `deps-dev`) and point
to `ci:`/`test:`/`build:`. Add `perf` to the allowed types: docs/dev/ci-and-releases.md:83 lists it, but the pattern
rejects it. (4) Update the README and the release table to say how dependency updates are released. A more precise
option for (1): in the jar job, release a patch whenever `BOOT-INF/classpath.idx` of the new jar or the Dockerfile's
FROM digests differ from the previous release, whatever the commit type.

**Trade-offs.** With prefix `fix` on Gradle, bumps of test or tooling libraries (Spotless, Error Prone, JaCoCo,
Playwright) also cut patch releases with an unchanged image. That is harmless but noisy, and the grouping in BUILD-3
limits it to about one a week. The classpath/digest comparison avoids this but is more work (M). Adding
workflow_dispatch adds an entry point that must keep the same gates (it re-runs the whole pipeline on main, so it does).
No product rule is affected. If the per-commit release bursts are unwanted (9 releases, v0.21.16 to v0.21.24, between
18:41 and 20:38 UTC on 2026-11-26), a release-PR model such as release-please would batch releases while still deriving
versions from Conventional Commits. That is a judgement call.

**Evidence:**

- .github/workflows/ci.yml:285-299 — TriPSs conventional-changelog-action, preset conventionalcommits, skip-on-empty:
  true
- .github/workflows/ci.yml:314-318 — 'No releasable commits since the last tag: nothing will be published.'
- TriPSs/conventional-changelog-action@469f0e5 src/index.js:176-183 — skipped=true when the cleaned changelog is empty
  (external, unchanged pin)
- .github/dependabot.yml:1-59 — still no commit-message prefix; :40-42 says these PRs are how base-image security fixes
  reach a release
- README.md:52-53 — 'latest is both the newest release and the newest code'
- docs/dev/ci-and-releases.md:80-85 — release table omits build; :83 lists perf
- scripts/check-commits.sh:11 — pattern has no perf and accepts any scope on fix
- GitHub releases — v0.21.15 2026-10-18T20:30Z, v0.21.16 2026-11-26T18:41Z (external); delta 4d898c9 build(deps) Tomcat
  11.0.29 not releasable by itself
- .github/workflows/ci.yml:3-6 — push and pull_request only, no workflow_dispatch

<a id="build-2"></a>

### BUILD-2 — The documented pre-1.0 rule ('feat! bumps the minor') is not implemented; a breaking-change commit fails CI

Impact **medium** · effort **S** · defect · confirmed · **open**

**Problem.** Suppose a PR changes a `/data` format or a configuration name incompatibly and the author marks it honestly
as `feat(config)!: …` or with a `BREAKING CHANGE:` footer. The action computes 1.0.0, and the plan step exits 1 on the
PR's jar job. The smoke jobs are skipped and `CI passed` fails, so the PR cannot merge until the breaking marker is
removed. Authors therefore cannot flag breaking changes, so the changelog never has a 'BREAKING CHANGES' section, even
though upgrades in place depend on users reading exactly that. If such a commit reached main anyway (an admin merge, for
instance), every later push to main would fail the same way and block all releases, because the commit stays in the
range since the last tag.

**Proposal.** Make the plan step implement the documented rule. When the previous tag is v0.x and the action proposes a
major version, compute the next minor of the previous tag yourself (for example `IFS=. read -r _ minor _ <<<
"${previous#v}"; NEXT=0.$((minor+1)).0`) and set TAG accordingly. The changelog text needs no change: the action already
strips its heading (clean_changelog). Keep the fail-loudly guard only for an explicit opt-in to 1.0, such as a
repository variable. Add a deployment test that pins this branch of the step, as the existing workflow tests do.

**Trade-offs.** An alternative is a `config-file-path` with `preMajor: true`. That also demotes `feat` from minor to
patch, which changes the documented feat→minor behaviour, so computing the minor in bash fits the documentation better.
The change is small and local to the jar job, and affects no product rule.

**Evidence:**

- docs/dev/ci-and-releases.md:84 — 'feat!: or BREAKING CHANGE: | Minor bump, because this project is still pre-1.0'
- .github/workflows/ci.yml:281-282 — 'breaking -> minor while still pre-1.0'
- .github/workflows/ci.yml:319-324 — refuses v0.* -> non-0.* (exit 1) before the ON_MAIN check at :326
- TriPSs/conventional-changelog-action@469f0e5 src/index.js:105-111 — no preMajor passed (external, unchanged pin)
- src/test/java/dev/andre/homecontrol/deployment/CommitMessagesTest.java:26 — 'refactor!:' is an accepted subject

<a id="build-3"></a>

### BUILD-3 — Cut Dependabot churn: group updates across directories and ecosystems, add a cooldown, and squash Dependabot PRs

Impact **medium** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** In 6.5 weeks the repository received about 46 Dependabot PRs. Each one triggered 2 to 3 full CI runs of
about 20 jobs, and together they produced 60 commits on main: roughly 64% of all commits, so dependency bumps bury
product history. Duplicate per-directory PRs for the same action version double the work for no information. Each Gradle
bump also pays for the checksum candidate run plus the strict rerun. Five PRs opened at once can saturate a free
account's concurrent-job limit and queue human PRs behind them (judgement). Separately, adopting releases on the day
they are published maximises exposure to a compromised upstream release, which ADR 0008 says the checksum flow cannot
detect.

**Proposal.** dependabot.yml: (a) github-actions: one group with patterns ['*'], which gives one PR across both
directories, on a monthly interval. (b) gradle: one group for minor and patch updates of runtime libraries and one for
build tooling (spotless, errorprone, sonarqube, jacoco, test-retry, playwright, archunit, protobuf plugin). Majors stay
ungrouped, and gradle-wrapper is excluded, because publish.py:115 accepts only catalog and metadata changes. (c) docker:
one base-images group. (d) Add a cooldown (e.g. default-days 5, semver-major-days 14) where Dependabot supports it;
security updates are not delayed by it. (e) Squash-merge Dependabot PRs, so a bump and its metadata commit land as one
commit. This pairs with BUILD-1: about one releasable dependency commit a week.

**Trade-offs.** One bad update blocks its whole group until someone ignores that dependency; Dependabot supports
`@dependabot ignore` per dependency inside a group. Cooldown delays non-security fixes by a few days. Squash merging
loses the separate App commit on main, which the checksum flow does not need after the merge. No product rule is
affected. It works with BUILD-1: one releasable `fix(deps)` commit a week instead of many.

**Evidence:**

- .github/dependabot.yml:13-25 — four Gradle groups only
- .github/dependabot.yml:29-38 — two directories, codeql group only
- .github/dependabot.yml:43-46 — docker, no group, no cooldown
- git log HEAD, author date since 2026-10-04 — 79 of 148 commits are build(deps), 19 of them metadata commits; delta
  a753c3b + e538744 duplicate setup-java bump
- scripts/dependency-checksums/publish.py:21,115 — ALLOWED = {metadata, catalog}; a wrapper change is refused

<a id="build-4"></a>

### BUILD-4 — Keep Gradle off the contents:write token in the dependency-graph job

Impact **medium** · effort **S** · risk · revised by the verifier · **open**

**Problem.** Take a Dependabot PR that bumps a Gradle plugin, say a compromised Spotless or protobuf-plugin release. It
passes the checksum flow, because the bytes match Maven Central or the Plugin Portal. It then executes at configuration
time in the `dependencies` job, where the job-level permissions have raised Dependabot's normally read-only GITHUB_TOKEN
to contents: write. Code in a job can recover that token from the runner process, as the 2025 tj-actions incident
showed, then push to the PR branch or other unprotected branches, or create tags and releases. Every other job that runs
Gradle on that PR is read-only. This job is the exception.

**Proposal.** Split the job so Gradle never runs with a write token. Job A, `contents: read`, keeps the needs and the if
(so DependencyVerificationWorkflowTest still holds) and runs dependency-submission with `dependency-graph:
generate-and-upload`. If the action allows it, A also runs with `cache-read-only: true`, so downloads made with
verification off never enter main's shared Gradle home. Job B, `contents: write`, needs A and runs dependency-submission
with `dependency-graph: download-and-submit`: no Gradle, no project code. Run dependency-review after B. Record in
testing.md (and as a note on ADR 0008) that the submission action forces `dependency.verification=off`, so that job is
deliberately read-only. Optional hygiene: `persist-credentials: false` on the checkouts at ci.yml:385, :435, :523, :572,
:585 and :598.

**Trade-offs.** One more job, about 20 seconds of overhead. The download-and-submit mode is what gradle/actions
documents for fork PRs, so it is supported. The release still does not wait for these jobs. This answers the 2026-10-03
open question without changing ADR 0008.

**Evidence:**

- GitHub Actions run 45891280664, job 'Dependency vulnerabilities' log — gradlew
  -Dorg.gradle.dependency.verification=off ... ForceDependencyResolutionPlugin_resolveAllDependencies (external)
- .github/workflows/ci.yml:655-657 — permissions: contents: write
- .github/workflows/ci.yml:647-652 — runs after checksums are verified, on push and same-repo PRs (Dependabot included)
- .github/workflows/ci.yml:669-670 — gradle/actions/dependency-submission (generate-submit-and-upload by default)
- gradle/verification-metadata.xml — still no github-dependency-graph plugin entry
- src/test/java/dev/andre/homecontrol/deployment/DependencyVerificationWorkflowTest.java:18 — 'dependencies' listed
  among the strict Gradle jobs
- ci.yml:385, :435, :523, :572, :585, :598 — checkouts without persist-credentials: false

<a id="build-5"></a>

### BUILD-5 — Scan the published images for OS-package vulnerabilities on a schedule

Impact **low** · effort **M** · feature · revised by the verifier · **open**

**Problem.** A CVE in glibc, openssl or zlib in the JRE base, or in ffmpeg or the codec libraries that mpv pulls into
the -bluetooth image, raises no alert, because Dependabot sees only Maven coordinates. The only path to a fix is a
weekly Dependabot digest bump, which then is not released at all (BUILD-1). Maintainers have no signal for 'the image
users run today is vulnerable'.

**Proposal.** Add a separate scheduled workflow (weekly plus workflow_dispatch, contents: read and security-events:
write) that scans :latest and :latest-bluetooth for both platforms with a scanner pinned by SHA or digest. Report only
fixable HIGH and CRITICAL findings, as SARIF in code scanning. Do this after BUILD-1, so a finding has a release path.
Optionally, bypass the mpv layer cache once a week (cache-from off on scheduled rebuilds), so Ubuntu package fixes reach
the -bluetooth image between base digest bumps.

**Trade-offs.** It adds a pinned scanner to keep up to date, plus some alert noise; unfixable Ubuntu CVEs need a
vulnerability exclusion file (.trivyignore) or severity filter. The scan runs in CI only, against the public registry,
so the LAN-appliance and no-cloud rules are untouched. An alternative with no new tool is to read the existing SBOM
attestation with Grype (`grype sbom:…`).

**Evidence:**

- .github/workflows/ci.yml:3-6 — no schedule trigger
- .github/actions/smoke-image/action.yml:121-123 — SBOM attached, never consumed
- .github/actions/smoke-image/action.yml:48-55 — the mpv apt layer is cached per architecture on main
- .github/workflows/ci.yml:641-681 — vulnerability coverage is the Gradle graph only
- Dockerfile:18-26 — Ubuntu-based JRE; mpv installed for the -bluetooth image

<a id="build-6"></a>

### BUILD-6 — Rethink where the SonarCloud job sits: it delays releases on main without gating them, and fails every fork PR

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** On every release, the release job waits about 3 minutes, roughly 30% of the run, for a scan that on main
cannot fail on quality. It can only fail on scanner errors, which then block a release whose PR already passed the gate.
The release therefore waits on the least relevant job. Separately, any external contributor's PR is red by construction,
which conflicts with inviting contributions through the four issue templates. A smaller cost: the serial checksums gate
adds about 1 minute to every critical path, although it only protects Dependabot PRs.

**Proposal.** Choose one, and make docs/dev/ci-and-releases.md:96 match. (a) Remove sonar from release.needs, so the PR
gate is the quality decision and the main scan is baseline bookkeeping. Or (b) set qualitygate.wait=true on main too, so
the wait checks something. Cache ~/.sonar/cache. Optionally, skip sonar for fork PRs with an explicit if, so they are
not red by construction, and say in the docs that a maintainer reviews the main scan for them. Keep the checksums gate
as it is.

**Trade-offs.** With (1), a scanner outage on main no longer blocks a release, which is the intended behaviour. (3)
means fork PRs are merged without a Sonar gate until the workflow_run path exists, so a maintainer must look at the main
scan. (4) complicates the ci-passed logic and its deployment tests; it is worth it only if the minute matters. No
product rule is affected.

**Evidence:**

- .github/workflows/ci.yml:869 — release needs include sonar
- .github/workflows/ci.yml:632,639 — qualitygate.wait=false on push
- docs/dev/ci-and-releases.md:92-99 — :96 claims the release waits for the quality gate
- run 45891280664 — sonar 20:33:56-20:37:47, release start 20:37:48 (external)
- .github/workflows/ci.yml:634-637 — missing SONAR_TOKEN exits 1 (fork PRs)
- src/test/java/dev/andre/homecontrol/deployment/DependencyVerificationWorkflowTest.java:16-21 — pins the checksums gate
  on every Gradle job

<a id="build-7"></a>

### BUILD-7 — Two cache keys cover far more than they cache: workflow files invalidate the whole unit suite, and any catalog bump resets the browser cache

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** An Actions-only PR has no Java or test changes and should take its unit-test result straight from main's
build cache. Instead it re-runs about 3.5 minutes of tests twice, because ci.yml is an input of every test class. That
happened 29 times since Oct 4. Similarly, each of the 16 Gradle catalog bumps (jmdns, ph-css, and so on) changed the
browser cache key and forced Chromium, Firefox and WebKit to download again in all three browser jobs, though Playwright
did not change. That also churns the repository's 10 GB cache budget.

**Proposal.** (1) Move dev.andre.homecontrol.deployment.* into its own Test task, `deploymentTest`, wired into check.
Mark it `outputs.cacheIf { false }` and never up to date: it takes seconds, and no input list is left to keep complete.
Keep the list of docs guides only where ChangedFilesTest and code-changed.sh need it. Exclude the package from `test`,
and fix the gradle.properties comment. (2) Key the Playwright browser cache on the Playwright version read from the
catalog.

**Trade-offs.** One more test task to keep in mind for local `test --tests` filters; document it in testing.md next to
the module notes. Also check that ChangedFilesTest's list of files read by tests still matches the new task's inputs.
Small, low-risk changes.

**Evidence:**

- build.gradle.kts:211-227 — deploymentFiles inputs on the whole test task
- src/test/java/dev/andre/homecontrol/deployment/DockerImageTest.java:52 — reads
  gradle/wrapper/gradle-wrapper.properties, which is not an input
- build.gradle.kts:216 — lists .github/workflows/dependency-checksums.yml, which does not exist
- gradle.properties:1-3 — claims a workflow-only change tests nothing again
- .github/actions/browser-tests/action.yml:24-28 — cache keyed on the whole catalog (:28)

<a id="build-8"></a>

### BUILD-8 — Layer the image into dependencies and application, and optionally add a JVM AOT cache

Merged into [OPS-9](#ops-9), which covers it. Impact low, effort M, status open.

<a id="build-10"></a>

### BUILD-10 — CasaOS packaging: runs the image as root, version frozen at 0.3.0, description from the Shield-only days, latest-only

Impact **medium** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** CasaOS is a first-class install target: its app id is one of the names that never change. Yet every CasaOS
install runs the network-facing JVM as root with host networking, the exposure the default image was hardened against.
The app-store metadata shows a version 18 minor releases old and describes a much narrower product. Because the manifest
says only `latest`, a CasaOS user cannot pin or roll back to a known-good release.

**Proposal.** (1) Add an entrypoint script to the default image (USER stays 1000). When it starts as uid 0 and an opt-in
variable is set (for example HOME_CONTROL_RUN_AS=1000:1000), it runs chown -R on /data and then `exec setpriv
--reuid=1000 --regid=1000 --clear-groups java ...`. The CasaOS manifest keeps `user: "0:0"` and sets that variable, so
existing CasaOS data is migrated in place on the next start, and `--user 0:0` without the variable keeps today's
documented behaviour. Extend the smoke test and update README.md:70-72. The -bluetooth variant stays root. (2) Refresh
the tagline and description, and bump `version`, or render it from the release, keeping id dev.andre.shield-remote and
the service and container names. (3) Optionally, attach version-pinned manifests to each GitHub release.

**Trade-offs.** (1) changes a deliberate behaviour. Today a root-owned data directory is refused with a chown
instruction, which the smoke test asserts (scripts/smoke-test-image.sh:116-125). The automatic chown would need to be
limited, for example to an empty directory or to CasaOS's path, and documented, and the smoke test extended. This is a
judgement call for the maintainer. The -bluetooth variant stays root as it must (D-Bus). (2) and (3) are low-risk;
CasaOsManifestTest needs to allow the rendered variant.

**Evidence:**

- casaos/docker-compose.yml:9-10 — user: "0:0" (deliberate: README.md:70-72)
- casaos/docker-compose.yml:44, casaos/docker-compose.bluetooth.yml:66 — version "0.3.0"
- casaos/docker-compose.yml:35-38 — Shield-only tagline and description
- src/test/java/dev/andre/homecontrol/deployment/CasaOsManifestTest.java:25 — pins :latest
- scripts/smoke-test-image.sh:62-68,116-125 — non-root and root-owned-data checks

<a id="build-11"></a>

### BUILD-11 — Close ADR 0008's acknowledged gap: verify PGP signatures of dependencies, not only their checksums

Impact **low** · effort **L** · risk · revised by the verifier · **open**

**Problem.** Suppose an attacker publishes a new version of an existing dependency, for example by taking over a
maintainer's Sonatype account or stealing a Plugin Portal key. The automated flow accepts it as soon as Dependabot
proposes it, because Central serves the same bytes to the verifier. The build code then runs in CI and the code ships in
the appliance. Checksums prove the download was not tampered with in transit; they do not prove the publisher is who it
was last time. Most Maven Central artifacts, including Spring, Jackson, BouncyCastle, Tomcat and protobuf, are
PGP-signed, so that signal is available but unused.

**Proposal.** Do it after the BUILD-3 cooldown, and only if the team wants publisher authentication. Enable
verify-signatures with a committed keyring and per-group trusted keys (keyservers off during builds), and keep
checksum-only entries for unsigned artifacts. Extend the trusted publisher so it accepts a new version automatically
only when it is signed by a key already trusted for that group. Amend ADR 0008.

**Trade-offs.** Initial keyring curation is real work (L). Some plugin markers and Portal artifacts are unsigned. Key
rotations by upstream projects will occasionally need a human. Together with the cooldown in BUILD-3, this is the main
defence against a compromised upstream release; without it, cooldown alone gives time but not proof. ADR 0008 explicitly
requires a deliberate change and an ADR update for this.

**Evidence:**

- gradle/verification-metadata.xml:5 — <verify-signatures>false</verify-signatures>
- docs/adr/0008-automatic-dependency-checksums.md:37,44-45 — Consequences: does not independently authenticate a
  publisher
- scripts/dependency-checksums/verify.py:228-239 — accepts on a SHA-256 match with a fresh download
- .github/workflows/ci.yml:165-256 — automatic commit, no human in the loop

<a id="build-12"></a>

### BUILD-12 — verification-metadata.xml only grows and is no longer sorted: prune stale versions and insert new entries in order

Impact **low** · effort **S** · debt · revised by the verifier · **open**

**Problem.** Old versions stay trusted forever. If a resolution change, a removed constraint or a BOM downgrade brought
tomcat-embed-core back to 11.0.24, verification would pass silently instead of drawing attention. The file grows with
every update, so review gets harder. And the next manual regeneration (the documented path for anything outside the
automatic scope) reorders hundreds of appended entries, producing a diff in which the real change cannot be reviewed.

**Proposal.** (1) Insert new components in Gradle's sort order in verify.py, so manual regeneration
(docs/dev/testing.md:93-101) does not produce a large reorder diff. (2) Add a maintainer-run prune script that deletes
only components no configuration resolves, keeping all protoc platforms and the Playwright driver jars used by
scripts/e2e.Dockerfile. Run it as an occasional reviewed PR. Removing trust is allowed under ADR 0008, because it never
edits a kept hash.

**Trade-offs.** The prune must keep entries that local resolution does not see: the other protoc platforms, and
Playwright's driver jars used by scripts/e2e.Dockerfile. Pruning PRs touch many lines, but only by deletion, which is
easy to review. Low risk.

**Evidence:**

- gradle/verification-metadata.xml — 574 components, 168 multi-version coordinates, 17 sort breaks starting at line 2473
- gradle/verification-metadata.xml:1226,1231,2604,3888 — tomcat-embed-core 11.0.24/.26/.27/.29 all still trusted
- scripts/dependency-checksums/verify.py:211-214 — new blocks are inserted before </components>
- scripts/dependency-checksums/verify.py:217-226 — trusted entries may never disappear from a candidate
- .github/workflows/ci.yml:674-681 — dependency-review fails vulnerable changes on PRs

<a id="build-13"></a>

### BUILD-13 — Add an image-level upgrade test: the previous release's data, opened by the new image

Merged into [TEST-2](#test-2), which covers it. Impact low, effort M, status open.

<a id="build-14"></a>

### BUILD-14 — Raise a curated set of Error Prone warnings to errors

Merged into [ARCH-13](#arch-13), which covers it. Impact low, effort M, status open.

<a id="build-15"></a>

### BUILD-15 — Local build ergonomics: visible test failures, a warm daemon, pinned helper images, a complete .dockerignore

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** The documented daily loop is `scripts/gradle.sh test --tests …`. It pays JVM and Gradle startup on every
call, prints no failure details, and may run on a different JDK and Gradle image than CI and the Dockerfile, depending
on when the developer first pulled the tag. The e2e image fetches executable code without checking hashes the repository
already holds. Building from a working copy uploads hundreds of megabytes of unrelated worktrees as build context, plus
any stray keystore. The keystore stays in the local builder's cache but never reaches the final image.

**Proposal.** (1) Make failures visible under -q: `testLogging.quiet { events("failed"); exceptionFormat = FULL }`, or
drop -q. Update docs/dev/testing.md:80-81. (2) Pin scripts/e2e.Dockerfile's base by digest, check the driver and
driver-bundle jars with sha256sum -c against the values in verification-metadata.xml before unzipping, and add /scripts
to Dependabot's docker directories. (3) Make .dockerignore mirror .gitignore (.worktrees/, .claude/, **/node_modules,
*.p12). (4) Optionally, default gradle.sh's image to the Dockerfile's pinned reference. Leave out the toolchain
resolver.

**Trade-offs.** Each change is small. An allow-list .dockerignore must be kept in sync with new top-level build inputs;
DockerImageTest could pin that. The daemon mode adds a long-lived container that developers must stop. The toolchain
resolver adds one plugin plus its verification entries. No product rule is affected.

**Evidence:**

- scripts/gradle.sh:9,14 — floating image, --no-daemon -q
- build.gradle.kts:76-79 — testLogging showExceptions only
- docs/dev/testing.md:80-81 — grep the XML to see a failure
- scripts/e2e.Dockerfile:4,14-18 — unpinned base; unchecked downloads of the Playwright driver and bundle
- gradle/verification-metadata.xml:3859-3874 — SHA-256 values for the current 1.65.0 driver jars already exist (1.64.0
  at :2781-2796)
- .dockerignore:1-6 — missing .worktrees/, node_modules, *.p12 from .gitignore
- src/test/java/dev/andre/homecontrol/deployment/DockerImageTest.java:47-55 — the wrapper pins and checks Gradle's own
  version

<a id="build-v1"></a>

### BUILD-V1 — Maven Central rate limits (HTTP 429) fail main runs and skip their releases

Impact **medium** · effort **S** · risk · found by the verifier · **open**

**Problem.** About ten jobs per run resolve from Maven Central, and the Plugin Portal redirects there too. During merge
bursts, or after dependency bumps that miss the cache, Central answers 429. Gradle then disables the repository for the
rest of the build and the job fails within seconds. On main, that run publishes nothing: on 2026-11-26 the runs for
c0eedf7 and 04928a4 failed this way and their changes waited for the next push. If the failing push is the last one of a
batch, the release waits for a human to re-run the jobs or for another releasable commit. PR runs suffer the same and
need manual re-runs. It gets worse with more frequent releases (BUILD-1) and with ungrouped Dependabot bursts.

**Proposal.** (1) In CI, run Gradle with a bounded retry and back-off on resolution throttling. First check whether
Gradle 9.8 treats 429 as transient. If it does, raise the internal retry settings for CI only
(org.gradle.internal.repository.max.tentatives and initial.backoff, via GRADLE_OPTS in the workflow). If it does not,
wrap the Gradle steps in a small script that re-runs once after about 60 s when the log contains 'status code 429'. (2)
Cut request volume: group Dependabot updates (BUILD-3), and check why restored Gradle homes still miss plugin POMs that
have not changed in weeks (for example, cache cleanup of entries a job's build did not touch). (3) Say in
docs/dev/ci-and-releases.md that a main run that failed on a transient error is re-run from the Actions page, and that
the next releasable push otherwise includes its commits.

**Trade-offs.** The retry settings are internal Gradle properties that can change between versions, so a wrapper script
is more stable but adds one more file under scripts/ (and a deployment test). Retries lengthen a failing run by about a
minute. A third-party Central mirror would cut load, but it is a new trust and availability dependency: integrity is
still enforced by verification-metadata.xml, but ADR 0008's publisher compares downloads against Central and the Portal
only. No product rule is affected.

**Evidence:**

- GitHub Actions run 45884183148 (main, 04928a4, 2026-11-26), job 'Verify dependency checksums' — HTTP 429 from Maven
  Central, then repository disabled (external)
- GitHub Actions run 45881050619 (main, c0eedf7, 2026-11-26), job 'Build and test' — 429, BUILD FAILED in 10s (external)
- GitHub Actions run 44969875840 (main, 460be1f, 2026-11-22), job 'Browser tests (Firefox)' — 429 during the build
  (external)
- Main run list — 3 of the 4 failed main runs from 2026-11-17 to 2026-11-26 are 429s (external)
- .github/workflows/ci.yml:861-869 — release runs only if every needed job passed; nothing retries a failed main run

### Rejected in BUILD

- **BUILD-9 — Check, rather than assume, that the pushed image is the one that was smoke-tested; make image builds
  reproducible.** The failure scenario cannot be produced. Both build-push-action steps already have identical inputs,
  pinned by tests: DockerImageTest.java:28-37 pins context, file and build-contexts, and
  BluetoothDeploymentTest.java:116-119 pins the build-args. Labels come from the same metadata step output
  (smoke-image/action.yml:78,116). The second build runs seconds later in the same job on the same fresh builder, so
  every step is a local cache hit, and BuildKit garbage collection does not evict between two consecutive builds. The
  proposed check would also fail on every release. Without SOURCE_DATE_EPOCH, BuildKit stamps the history and `created`
  times at export, so the loaded image's config digest (`docker image inspect .Id`) never equals the pushed config
  digest. The filesystem layers are what match. Reproducibility for third parties adds little on top of the
  GitHub-signed provenance the release already attaches (ci.yml:937-943), and the -bluetooth apt layer is not
  reproducible anyway.

<a id="docs"></a>

## DOCS: Documentation and developer experience

The documentation is much better than usual for a hobby-scale appliance. A README routes readers to six user guides,
three developer guides, eight ADRs and an AGENTS.md that is short and accurate. Every relative link and anchor in
README, AGENTS.md, docs/user, docs/dev and docs/adr resolves (I checked them with a script: 0 broken). I spot-checked
about 27 concrete claims against the code: ports, defaults and units in configuration.md, timeouts and labels in
devices.md, workflow and theme limits, the reconnect delay in events.js, touchpad steps, keyboard shortcuts, the test
timeout, the per-worker test data directory, image tags and the JsonMapper and class counts in architecture.md. About 20
hold. The mismatches cluster in four places: (1) configuration.md claims "Every setting" but leaves out about 80 of the
150 keys in application.yaml, and never explains how to turn a property into an environment variable; (2) map-keyed
settings set by environment variable bind only for keys that application.yaml already declares (I checked this against
the Spring Boot 4.1.2 Binder); (3) sources.md still names three setup labels that no longer exist, the same kind of
drift as FB8; (4) small contradictions on commit types, the Spring Boot version, CasaOS metadata and image tags. For
onboarding, a contributor with Docker can get a green build from one documented command. But the docs never say how to
run the app locally (bootRun is not mentioned) and there is no way to see the UI without real devices, although the
browser tests already have fake device, content and pairing beans. A new user gets a sound security story but has no
requirements or network section, no troubleshooting index and no upgrade/backup page; the one-way upgrade warning sits
inside the property reference. docs/superpowers (95 files, 5.4 MB, about 97k lines) has no index or status markers and
dominates searches: DeviceManager appears in 405 lines there and in no code file. Note: this analysis is of the local
checkout at f9f9de9. The remote has since released v0.21.24 with fixes that are not in this checkout.

<a id="docs-1"></a>

### DOCS-1 — Make the configuration reference complete and machine-checked (configuration metadata plus a docs test)

Impact **medium** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** The guide promises every setting, but many settings users actually look for are missing: the login
rate-limit knobs (relevant to anyone behind a proxy), per-source rail refresh intervals, rail sizes, the Jellyfin
startup budget and every Cast timing. A Compose or CasaOS user must also work out the env-var spelling on their own.
Example: someone who wants a faster Jellyfin refresh finds `refresh-intervals` only in a YAML comment, and has no
documented env-var form to put in compose.yaml. Nothing keeps the table in sync: a renamed or removed key leaves a stale
row, and a new key never gets one.

**Proposal.** Add spring-boot-configuration-processor as an annotationProcessor, with its verification-metadata entries,
and add a ConfigurationGuideTest that reads the generated META-INF/spring-configuration-metadata.json and
docs/user/configuration.md. It fails when (a) a home-control.* property is neither in the guide nor in an explicit,
commented list of internal tuning keys, or (b) a property or HOME_CONTROL_* name in the guide matches no property. Add
the guide to build.gradle.kts test inputs and to read_by_tests in scripts/code-changed.sh. Add the user-relevant missing
rows: the login rate-limit knobs, rail refresh intervals, rail sizes, the Jellyfin startup budget and real Cast rows in
place of 'see CastProperties'. Put the internal keys in an appendix. Add a short 'Setting a property' section with the
env-var rule, a pointer to the compose.yaml `environment:` example and the map-key caveat from DOCS-2. Say that in the
Docker image the data directory is always /data (Dockerfile:44) and is changed by the volume mount, not by
HOME_CONTROL_DATA_DIR. Alternatively, drop the redundant ENV or the pinned argument so the documented property works.
Optionally, add additional-spring-configuration-metadata.json entries that mark the shield.* names as deprecated.

**Trade-offs.** The processor adds a build dependency and verification-metadata entries, and slightly slows compilation.
Listing every key can drown users, hence the user-facing table plus an appendix. The test reads a doc file, so
configuration.md must join read_by_tests in scripts/code-changed.sh. A docs-only edit to it then runs the build, which
is the intended FB10 behaviour. No product rule is affected.

**Evidence:**

- docs/user/configuration.md:3 - 'Every setting, as a Spring property or an environment variable.'
- src/main/resources/application.yaml:157-159 - login-attempts-per-address/total/login-window, absent from
  configuration.md; behaviour at docs/user/security.md:52-53
- src/main/resources/application.yaml:255-256 - refresh-intervals: {} with the 'e.g. jellyfin: 10m' comment
- src/main/resources/application.yaml:169-170,178 - jellyfin.startup-timeout (cited by docs/user/sources.md:210),
  jellyfin/tmdb rail-size
- docs/user/configuration.md:15 - `home-control.cast.*` default 'see `CastProperties`'
- docs/user/configuration.md:8 vs Dockerfile:37,44 - ENV HOME_CONTROL_DATA_DIR plus pinned --home-control.data-dir=/data
- compose.yaml:21-24 - only env example (SERVER_PORT); no upper-case/underscore rule in the guide
- build.gradle.kts:168-200, gradle/libs.versions.toml - no spring-boot-configuration-processor
- src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbProperties.java:17-30 - constructor-bound record with
  @DefaultValue

<a id="docs-2"></a>

### DOCS-2 — Map-keyed settings set by environment variable bind only for keys application.yaml already lists

Merged into [DOCS-1](#docs-1), which covers it. Impact low, effort S, status open.

<a id="docs-3"></a>

### DOCS-3 — Fix three stale setup labels in sources.md and guard the user guides against label and link drift

Impact **medium** · effort **M** · defect · revised by the verifier · **open**

**Problem.** Users are told to look for setup sections and controls that do not exist under those names. A new TMDB user
scanning Setup for 'TMDB' finds only 'Movies & series', and nothing on the page is called 'Content'. The 2026-10-03
review fixed the same problem in devices.md, but nothing stops it from recurring, and docs-only PRs skip every test.

**Proposal.** Fix the three references now: 'Setup → Movies & series', 'Setup → Dashboard → Language and services', and
'YouTube on Cast devices (experimental)'. As a separate step, add a WebSliceTest subclass that renders /setup and
/setup/appearance, collects the nav, h2, h3 and summary texts, and checks every bold 'Setup → …' path in docs/user/*.md
against them. Add docs/user/*.md to the test inputs in build.gradle.kts and to read_by_tests, and accept that user-guide
PRs then run the build. A standard-library link and anchor checker run as a CI job that is not skipped for docs-only PRs
is the cheaper half and worth doing first.

**Trade-offs.** The label test couples prose to template text, which is the point, but a UI copy change then needs a
docs update in the same PR, which is the desired discipline. Keep the test in the web slice so it does not need a new
Spring context (SharedContextRulesTest).

**Evidence:**

- docs/user/sources.md:251 - 'into **Setup → TMDB**'; src/main/resources/templates/fragments/tmdb-setup.html:3 -
  <h2>Movies &amp; series</h2>
- docs/user/sources.md:253 - '**Setup → Content**'; src/main/resources/templates/setup.html:30 - nav 'Dashboard';
  src/main/resources/templates/fragments/sources-setup.html:53 - <h3>Language and services</h3>
- docs/user/sources.md:393 - '**YouTube Cast (best effort)**';
  src/main/resources/templates/fragments/youtube-setup.html:60 - <summary>YouTube on Cast devices
  (experimental)</summary>
- scripts/code-changed.sh:11,14 - docs/ counts as documentation; only the Bluetooth guides are read_by_tests
- docs/superpowers/reviews/2026-10-03-codebase-review.md:510 - FB8, the same drift in devices.md

<a id="docs-4"></a>

### DOCS-4 — Add a documented way to run the app locally, including a dev mode with fake devices

Impact **medium** · effort **M** · feature · revised by the verifier · **open**

**Problem.** A contributor changing a template, CSS, a theme or the JS modules has no documented way to see the page.
They must guess `./gradlew bootRun`, which needs a local JDK 25, or build the Docker image, and then they still see an
empty dashboard unless real TVs are on their network. The only way to exercise the play sheet, device drawer or touchpad
without hardware is to write a Playwright test. Theme authors (docs/dev/themes.md) are hit hardest.

**Proposal.** Move FakeDeviceAdapter, FakeContentSource, FakeCodePairing and E2eFakesConfiguration into a small source
set (or the test source set) that does not depend on Playwright, and let e2e depend on it. Add a DevApplication there:
SpringApplication.from(HomeControlApplication::main).with(E2eFakesConfiguration.class, DevSeed.class), where DevSeed
enrolls the same three fake devices E2eApplicationTest adopts and fills the fake rails. Expose it as a `devRun` JavaExec
task, like installPlaywrightBrowsers, and say in its docs that it inherits the test overrides (no discovery, no real
upstream APIs, data under build/test-data). Document both 'run against your real devices' (`docker compose up --build`,
or `./gradlew bootRun` with a JDK 25) and 'run with fakes' (`scripts/gradle.sh devRun`, Linux hosts only because of host
networking) in a 'Run the app locally' section of docs/dev/testing.md. Add a one-line smoke test that starts
DevApplication so the seed keeps working.

**Trade-offs.** It reuses fakes that already exist and puts nothing into the production jar, so ArchitectureTest and the
product rules are unaffected. The seed must be kept working as the fakes evolve; a smoke test can start DevApplication
once. Fakes speak no real protocol here, so this is for UI work, not adapter work.

**Evidence:**

- README.md, AGENTS.md, docs/dev/*.md - no mention of bootRun or running the app for UI work
- src/e2e/java/dev/andre/homecontrol/e2e/E2eFakesConfiguration.java:13-32 - fake adapter, fake source, @Primary
  FakeCodePairing
- src/e2e/java/dev/andre/homecontrol/e2e/E2eApplicationTest.java:36-44,68-73 - real app with fakes, temp data dir, three
  seeded devices
- build.gradle.kts:230-248 - e2e source set kept apart; :285 installPlaywrightBrowsers is a JavaExec
- gradle/verification-metadata.xml:3867 - playwright driver-bundle 1.65.0 on the e2e classpath
- src/test/resources/config/application.yaml:5-47 - test overrides a run on the test classpath inherits
- scripts/gradle.sh:11 - --network host

<a id="docs-5"></a>

### DOCS-5 — Give contributors one entry point: CONTRIBUTING.md, SECURITY.md, a docs/dev index and an 'add a module' checklist

Impact **low** · effort **M** · docs · revised by the verifier · **open**

**Problem.** A first-time contributor arriving from GitHub sees no CONTRIBUTING guide. Someone adding a device adapter
or content source has to piece the required steps together from five files, and missing one (for example the
WebSliceTest @Import) only shows up as a confusing test failure. The first `scripts/gradle.sh build` is silent for many
minutes with no stated expectation. A Codex user other than the maintainer gets an MCP server that cannot start, with no
explanation.

**Proposal.** Write the 'Adding a device adapter or content source' checklist as a section of docs/dev/architecture.md,
not a new file. Its steps: Module entry, @ConditionalOnModule, properties record plus application.yaml plus
configuration.md row, SetupSection plus WebSliceTest @Import, a Fake… speaking the real protocol, fixtures, a user-guide
section and the issue-template area. Link it from AGENTS.md. Add a SECURITY.md of a few lines pointing at the advisory
link, so GitHub's Security tab shows it. Skip a separate CONTRIBUTING.md, or make it three lines pointing to AGENTS.md.
For the silent first build, make -q opt-out in scripts/gradle.sh (for example HC_GRADLE_QUIET=0) and say in testing.md
that the first run pulls the image and dependencies and takes several minutes. Move .codex/config.toml to
.codex/config.example.toml with a comment, or document it in one line.

**Trade-offs.** More files to keep current. Keep them thin and link-based so they do not duplicate AGENTS.md. Dropping
-q from gradle.sh changes output agents parse; an opt-in flag or a documented `--console=plain` avoids that.

**Evidence:**

- repository root, .github/ - no CONTRIBUTING.md, SECURITY.md or CHANGELOG
- docs/dev/ - architecture.md, testing.md, ci-and-releases.md, themes.md; no index
- src/main/java/dev/andre/homecontrol/config/Module.java:10-24; docs/dev/testing.md:28-29,38-42 - module steps partly
  documented
- scripts/gradle.sh:14 - `./gradlew --no-daemon -q "$@"`
- docs/dev/architecture.md:131 - test task wall time 3 min 27 s; docs/dev/testing.md:15 - 2,689 tests
- /.codex/config.toml:4-13 - tracked MCP config tied to the maintainer's $HOME layout

<a id="docs-6"></a>

### DOCS-6 — Index docs/superpowers, mark what is history, and keep it out of default searches

Impact **medium** · effort **S** · debt · confirmed · **open**

**Problem.** Newcomers and coding agents searching for a class, key or behaviour mostly hit superseded plans full of old
code. Someone grepping for DeviceManager or a shield.* key gets hundreds of plan lines and may follow outdated designs.
Nothing says which documents describe the current system and which are history.

**Proposal.** Add docs/superpowers/README.md with one table row per document: kind, date, status (implemented in vX /
superseded by / open), and the ADR or user/dev guide that now holds the result. Move finished plans from plans/ into
archive/. Update spec status lines to 'Implemented'. Add a root `.ignore` (honoured by ripgrep and the tools built on
it) listing docs/superpowers/archive/ and docs/superpowers/plans/, so default searches favour code and current guides;
an explicit path or --no-ignore still reaches them. Strip the NUL bytes. In AGENTS.md, say these files are history, not
guidance. Renaming the folder (for example to docs/history) is optional and needs the link updates above.

**Trade-offs.** Hiding files from default search can also hide useful rationale; the index and the ADR links offset
that. Pruning was considered but rejected because shallow clones would lose the files. This is a judgement call on how
much history a working tree should carry.

**Evidence:**

- docs/superpowers - 95 files, 5.4 MB; no README.md
- grep -a: 'DeviceManager' in 410 lines of docs/superpowers, 0 Java files
- docs/superpowers/archive/2026-09-16-smart-tv-adapters.md:5944 - one NUL byte
- docs/superpowers/specs/2026-09-16-home-control-center-concept.md:4 - 'Status: Draft for review';
  docs/superpowers/specs/2026-10-04-automatic-dependency-checksums-design.md:4 - 'rollout pending'
- AGENTS.md:60-61 - 'Specs, plans and reviews of larger pieces of work are in docs/superpowers/'
- docs/dev/architecture.md:4, docs/user/sources.md:274, docs/adr/0001-0006:6 Spec: lines - the only links into the
  folder

<a id="docs-7"></a>

### DOCS-7 — Record the 2026-10-03 owner rulings as ADRs and make ADR headers consistent

Impact **low** · effort **S** · docs · confirmed · **open**

**Problem.** Two of these rulings define a /data format or a pin-id migration, and one defines how device identity
survives a re-pair. All are hard to reverse and covered by AGENTS.md's ADR rule, but they live only in a review file
nobody would think to read. A future contributor changing logins.json or sports ids would not learn the constraints,
such as 'nothing on disk logs anyone in' or 'old pin ids keep working'.

**Proposal.** Write short ADRs 0009 (persistent logins and logins.json), 0010 (sports item identity and pin migration)
and 0011 (Android TV identity by certificate fingerprint; TLS-first webOS can join it). Each links back to the review
section. Normalise 0007/0008 to the header the README prescribes, or change the README to the newer style. Add the ADR
item to the PR template (DOCS-12).

**Trade-offs.** Writing after the fact costs little because the rulings are already written up. The risk is ADR
inflation; limit ADRs to data formats and identity rules.

**Evidence:**

- docs/superpowers/reviews/2026-10-03-codebase-review.md:549-565 - owner's rulings AC2, TV5, WS2, SP4
- docs/adr/README.md:3-5,16,18 - criterion, index ends at 0008, 'copy the header of 0001'
- docs/adr/0001-cast-sender.md:1-6 vs docs/adr/0007-theme-packages.md:1-5 and
  docs/adr/0008-automatic-dependency-checksums.md:1-5 - different header styles
- src/main/java/dev/andre/homecontrol/storage/DataDirectory.java:23-24 - LOGINS
- docs/user/security.md:13-17 - the only description of logins.json

<a id="docs-8"></a>

### DOCS-8 — Add an 'Updating, backups and your data' guide and put upgrade notes in release notes

Merged into [DATA-2](#data-2), which covers it. Impact medium, effort M, status open.

<a id="docs-9"></a>

### DOCS-9 — Write a requirements, network and troubleshooting page for first-run users

Merged into [OPS-10](#ops-10), which covers it. Impact medium, effort M, status open.

<a id="docs-10"></a>

### DOCS-10 — Refresh the CasaOS store metadata and the image-tag documentation (the app id stays)

Impact **low** · effort **S** · defect · revised by the verifier · **open**

**Problem.** CasaOS users browsing or installing see an app that claims to be a Shield-only remote at version 0.3.0,
which undersells the product and confuses version reporting. A user who copies the short sha tag from the bug template
cannot pull it. Users looking for the Bluetooth image tags must find them in devices.md.

**Proposal.** Update the title, tagline, description and port and volume descriptions in both CasaOS manifests to
describe Home Control. Keep x-casaos.id dev.andre.shield-remote, the service name and container_name shield-remote, as
the product rules require. Drop or automate `version`. Add assertions to CasaOsManifestTest that the title is not
'Shield Remote' and that both manifests share their display fields. Fix the bug-template example to `sha-<full
40-character commit>`, or tell users to give the version from the startup log. Add `latest-bluetooth` and
`<version>-bluetooth` rows to the README tag table, with a current version as the example.

**Trade-offs.** Changing the CasaOS title is cosmetic; I am assuming CasaOS keys installs on id, not title, which should
be confirmed on a CasaOS host. Keeping the version in sync automatically adds a release-job edit, or a commit, which the
release job does not make today.

**Evidence:**

- casaos/docker-compose.yml:19,23,33,36,38,44 - Shield Remote title, tagline, description, version 0.3.0
- casaos/docker-compose.bluetooth.yml:32,55,58,60,66 - the same
- src/test/java/dev/andre/homecontrol/deployment/CasaOsManifestTest.java:44-51 - pins
  id/main/index/port_map/scheme/category/architectures, not display fields
- /.github/workflows/ci.yml:911 - type=sha,format=long; :987-993 - -bluetooth suffix tags
- /.github/ISSUE_TEMPLATE/1-bug.yml:46 - short sha example (sha-343d27c)
- README.md:46-50 - tag table without -bluetooth rows; '0.1.0, 0.1' example

<a id="docs-11"></a>

### DOCS-11 — Fix small contradictions: perf: commits, Spring Boot version, untracked .superpowers references, stale measures

Impact **low** · effort **S** · defect · confirmed · **partly fixed at 3932f9b**

**Problem.** Concrete failure: a contributor who follows ci-and-releases.md and writes `perf: cache the TMDB
configuration` gets a red 'Check commit messages' job. The other items are drift that makes careful readers distrust the
docs: a pinned version that rots with every Dependabot bump, and instructions to sync with files that are not in the
repository.

**Proposal.** Decide on perf: either add it to check-commits.sh, CommitMessagesTest and AGENTS.md, or delete it from
ci-and-releases.md and the ci.yml comment. Complete the release table with build: and revert:. Write 'Spring Boot 4.1
(exact versions in gradle/libs.versions.toml)'. Align the PR template command. Make scripts/ the single source and drop
the .superpowers wording. Move the roadmap 'Progress measures' table into the roadmap's archive entry, or stamp it with
a date and commit, and drop the test count.

**Trade-offs.** None of note. Adding perf: is more permissive and matches the release tooling's default preset.

**Since the analysis.** Fixed: 432fbd2 (docs: correct stale version notes) changed AGENTS.md:15 to Spring Boot 4.1.2,
which matches gradle/libs.versions.toml:2. It still names an exact version, though, so it will go stale again; the
proposed 'Spring Boot 4.1 (exact versions in gradle/libs.versions.toml)' wording was not adopted. Still to do: (1) perf:
is still listed as a patch bump in ci-and-releases.md:83 and the ci.yml:282 comment, while check-commits.sh:11 still
rejects it. (2) The release table still has no build: or revert: rows. (3) The PR template still says `./gradlew build`.
(4) scripts/gradle.sh, scripts/e2e.sh and testing.md:255-256 still describe themselves as copies of the gitignored
.superpowers/ scripts, and e2e.sh still calls the CI job 'e2e', not e2e-chromium/firefox/webkit. (5) architecture.md:127
still says Enrollment has 453 lines, but 55ed395 grew Enrollment.java to 480 lines, so the measure is further off.

**Evidence:**

- docs/dev/ci-and-releases.md:83 and .github/workflows/ci.yml:282 - perf: as patch bump; scripts/check-commits.sh:11 -
  pattern without perf
- docs/dev/ci-and-releases.md:80-85 - no build:/revert: rows
- AGENTS.md:15 - 'Spring Boot 4.1.2' (fixed by 432fbd2); gradle/libs.versions.toml:2 - 4.1.2
- /.github/pull_request_template.md:21 - '`./gradlew build` passes locally'
- scripts/gradle.sh:2, scripts/e2e.sh:2-6, docs/dev/testing.md:255-256; .gitignore:10 - .superpowers/ is ignored
- /.github/workflows/ci.yml:516,565,578 - e2e-chromium/firefox/webkit
- docs/dev/architecture.md:127 - 453 lines (Enrollment); src/main/java/dev/andre/homecontrol/device/Enrollment.java has
  480

<a id="docs-12"></a>

### DOCS-12 — Align the PR template with the AGENTS.md product and architecture rules

Impact **low** · effort **S** · improvement · revised by the verifier · **open**

**Problem.** The rules that most often cause expensive mistakes (a /data format change without migration, a new module
without a switch, a renamed shield.* key, a missing ADR) are absent from the one checklist every PR author sees. A
reviewer has to remember them, and an external contributor will not know them.

**Proposal.** Add three one-line conditional items under 'If this PR…': 'changes a /data format: it reads and migrates
the old one, and the commit carries an upgrade note'; 'adds or renames a property: configuration.md row, old name kept
through LegacyPropertyNames'; 'makes a hard-to-reverse decision: ADR added'. Change line 21 to `scripts/gradle.sh build`
(or `./gradlew build`). Skip the items tests already enforce.

**Trade-offs.** Longer templates get skimmed; keep the items one line each and grouped under 'If this PR…'. It adds no
tooling cost.

**Evidence:**

- /.github/pull_request_template.md:18-24 - five generic items
- AGENTS.md product and architecture rules - /data migration, module switches, names that never change, ADRs
- src/test/java/dev/andre/homecontrol/deployment/CasaOsManifestTest.java:44-51;
  src/test/java/dev/andre/homecontrol/config/LegacyPropertyNamesTest.java:146-152; docs/dev/testing.md:38-42 - rules
  already pinned by tests

<a id="docs-v1"></a>

### DOCS-V1 — .dockerignore ships agent worktrees and nested docs into every local image build

Merged into [BUILD-15](#build-15), which covers it. Impact low, effort S, status open.

<a id="docs-v2"></a>

### DOCS-V2 — No documented way to get diagnostic logs, and no warning about the one logger that leaks secrets

Merged into [OPS-10](#ops-10), which covers it. Impact low, effort S, status open.

<a id="prod"></a>

## PROD: Product capabilities and feature opportunities

Home Control already covers what its concept promised. It controls seven device families, the Jellyfin, YouTube, TMDB,
sports, pinned-link and workflow sources are wired to a capability-based planner, and the planner previews every route
before it plays. The phone UX is polished: touchpad, keyboard shortcuts, live SSE and PWA install. The main gaps are not
in coverage. They are in how the household uses it every day. There are no one-tap routines, no safe (idempotent)
"switch on", no typing into TV apps, no app launcher, and no way to rename "[LG] webOS TV OLED55C14LB" or read
"com.netflix.ninja" on a chip. Music on speakers plays one track at a time, because albums, playlists and series are not
modelled. Nothing outside the browser can drive the app once a login exists, and every install that connects a content
source has one. That rules out Home Assistant, iOS Shortcuts and Stream Deck. Operability for CasaOS users lacks backup
and restore before one-way upgrades. Several items were planned but never built: the specified text input (vNext v0.5),
Cast groups and per-person profiles. A few candidates do not hold up. The now-playing overview mostly exists already,
because every chip shows what its device plays, live. A 10-foot TV UI adds little to a phone remote. Desktop keyboard
shortcuts are already there. Web Push and scheduled commands conflict with the LAN and ephemeral-command rules, so the
reminders and scenes proposals are shaped so that nothing runs unless a person asks for it. Demand-dependent items (Roku
and Kodi, profiles, localisation) are marked as judgement calls.

<a id="prod-1"></a>

### PROD-1 — A documented local automation API with per-integration access tokens

Impact **high** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Other home systems cannot drive Home Control. Scenario: a household's Home Assistant rest_command, iOS
Shortcut or Stream Deck posts to /devices/shield/key/POWER and works. Then they connect Jellyfin, a password becomes
mandatory, and every call returns 401. The only workaround is to script POST /login with the household password. That
stores the password in plaintext in Home Assistant, it falls under the five-per-15-minutes address limit, and it breaks
on every password change. Even logged in, there is no JSON read of devices or now-playing state. What Home Control alone
offers (content routing such as "play this Jellyfin episode on the bedroom TV by whatever route works", and scenes from
PROD-2) stays locked in the browser.

**Proposal.** Keep the proposal as written (named control-scope tokens stored as SHA-256 hashes in /data/tokens.json,
Bearer accepted only on /devices/**, /api/** and /scenes/**/run, thin JSON reads over DeviceQueries and RailCache,
docs/user/api.md, tokens revoked on reset and password change). Correct the problem statement. Today an integration must
store the household password and either reuse the 30-day HOME_CONTROL_LOGIN cookie, which ends at expiry or a password
change, or log in on every call. The latter adds an uncapped entry to logins.json each time (RememberedLogins.remember).
It is not throttled by the login limiter, which counts only failures. Optionally cap remembered logins as part of the
same work.

**Trade-offs.** Larger attack surface: a leaked token controls devices, though it cannot read secrets or change setup.
Mitigations: control-only scope, hash-only storage, revocation, failure rate-limiting. Product rules: still LAN-only, no
cloud. A freshly minted token reaches the browser once, like the login cookie's token, and nothing stored can be turned
back into it, so stored secrets still never reach the browser. Commands stay ephemeral: the API fails now with the same
409/422/502 reasons; any schedule lives in Home Assistant or cron, outside Home Control. The cost is committing to API
stability. Most of the effort is security tests (filter, scope, CSRF interplay, reset).

**Evidence:**

- src/main/java/dev/andre/homecontrol/security/LoginGateFilter.java:53-80 — only a session or remembered-login cookie
  passes once a login exists
- src/main/java/dev/andre/homecontrol/web/LoginController.java:69-94 and
  src/main/java/dev/andre/homecontrol/security/LoginRateLimiter.java:96-101 — only failed logins are limited
- src/main/java/dev/andre/homecontrol/security/RememberedLogins.java:72-88 — each login adds an uncapped 30-day entry
- src/main/java/dev/andre/homecontrol/web/DeviceController.java:37-143 — command endpoints as form POSTs
- src/main/java/dev/andre/homecontrol/web/ContentPlayController.java:54-80 — play, route, route-preview
- src/main/java/dev/andre/homecontrol/web/EventStreamController.java:40-60 — state only as SSE
- src/main/java/dev/andre/homecontrol/security/CrossOriginGuard.java:33-46 — a header-less POST passes
- docs/user/sources.md:233 — the only documented API is GET /devices/<id>/route

<a id="prod-2"></a>

### PROD-2 — Scenes: one-tap routines ("Movie night", "Good night"), built on idempotent switch-on and switch-off

Impact **high** · effort **XL** · feature · revised by the verifier · **open**

**Problem.** Movie night today: tap the LG chip (a page reload), open the remote, press Power, wait, choose HDMI 2, tap
the Sonos chip, set the volume, go back, find the tile and play. That is 8–10 taps over several reloads, and "Good
night" means visiting every device. Power is a toggle on LG and Samsung, so a routine or an external automation that
"switches the TV on" while the reported state is stale switches it off instead.

**Proposal.** Ship in two steps. Step 1 (M): add core Action.PowerOn and Action.PowerOff, each idempotent. Android TV
uses WAKEUP and SLEEP. webOS uses Wake-on-LAN, or turnOff only when it reports on. Tizen uses Wake-on-LAN, or KEY_POWER
only when the REST API reports on. Each adapter declares what it carries, and the fakes and adapter tests cover each
case. This alone makes "switch on" safe for the drawer, PROD-1 clients and the Jellyfin executor. Step 2 (L): a
switchable scenes module (home-control.scenes.enabled) with steps stored in /data/scenes.json through VersionedJsonFile.
SceneRunner runs the steps in order inside the request, under one total deadline. AwaitReady is bounded like the
Jellyfin startup budget. The first failure stops the scene, and the answer says which step failed and which ran. The UI
is a Scenes row and a Setup editor. POST /scenes/{id}/run accepts a PROD-1 token. No time triggers and no retries after
the response, so the ephemeral-command rule holds.

**Trade-offs.** Commands-ephemeral rule: a scene runs only when someone asks, entirely inside that request. AwaitReady
is bounded like the Jellyfin startup budget, and nothing is retried or resumed after the response, so it complies.
Time-triggered scenes, "at kick-off" triggers and sleep timers would be deferred commands; they are excluded, and an
external scheduler can call the API instead. There is no rollback of a scene that only partly ran; the report must be
clear. Inputs and items can vanish (TV firmware, a deleted Jellyfin item), so steps are validated at run time and stale
steps are flagged in the editor. The editor is most of the effort. An API-only first cut (no editor) is S–M but misses
most households.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/Action.java:11-266 — no PowerOn or PowerOff action
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:185,212-224 — POWER toggles
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsKeys.java:20-34 and WebOsSession.java:193-195 — no WAKEUP
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:163,193-205 — POWER toggles on Samsung
- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvKeys.java:25 — only Android TV has WAKEUP
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutor.java:94-112,174-175 — bounded wake, launch
  and wait
- src/main/java/dev/andre/homecontrol/playback/PlaybackService.java:33 — commands are ephemeral

<a id="prod-3"></a>

### PROD-3 — Type on the phone into the TV (text input), as specified in vNext v0.5 but never built

Merged into [DEV-10](#dev-10), which covers it. Impact high, effort L, status open.

<a id="prod-4"></a>

### PROD-4 — App shortcuts in the remote drawer (open Netflix, Disney+, ARD, Plex …)

Merged into [DEV-10](#dev-10), which covers it. Impact medium, effort M, status open.

<a id="prod-5"></a>

### PROD-5 — Human names: rename and reorder devices, and show app names instead of package ids

Impact **medium** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Chips read "SHIELD", "[LG] webOS TV OLED55C14LB" or "[TV] Samsung Q80 Series (55)", with
"com.netflix.ninja" underneath. A household with two Shields, or with "Kitchen" and "Living room" speakers, cannot name
devices by room, and the strip keeps registration order. These names also become the entity and scene names for PROD-1
and PROD-2.

**Proposal.** Add an optional label and position per device in devices.json. It is a format bump migrated on read, with
a test that loads the previous format. Device.displayName() returns the label or else the name, and every view, toast
and route description uses it, while name stays the discovered name for DeviceMatching and the Jellyfin fallback. A name
typed at manual pairing should become the label, not the name. Setup → Your devices gets Rename and Up/Down. A core
AppNames table maps Android packages and webOS ids to labels, seeded from ServiceLinks and PROD-4's catalog. Samsung
already reports names. Unknown ids fall back to the raw id.

**Trade-offs.** A devices.json format change is one-way, like earlier ones; it needs a test that loads the previous
format. Messages built inside adapters from device.name() (for example "LG TV has no … button") need the display name
passed to sessions, which causes some churn. Low risk otherwise.

**Evidence:**

- core/src/main/java/dev/andre/homecontrol/core/Device.java:14-15 — only the discovered or typed name
- src/main/resources/templates/setup.html:149-190 — no rename or order; 119-121,139-141 — a name only at manual pairing
- src/main/java/dev/andre/homecontrol/device/DeviceMatching.java:54-59,73-89 — name-based merge fallback, now only when
  byName (Cast)
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSessions.java:85-104 — link, then address, then name
- src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvSession.java:314-317 and
  src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:267-271 — raw app ids
- src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSession.java:297-308 — Tizen reports names for known apps
- src/main/resources/static/js/state-view.js:42-44, src/main/resources/templates/dashboard.html:28,32-33

<a id="prod-6"></a>

### PROD-6 — Albums, playlists and series from Jellyfin, played through the device's own queue

Impact **high** · effort **L** · feature · confirmed · **open**

**Problem.** Scenario: a family wants an album on the kitchen Sonos. Search finds only single tracks, one track plays,
then silence. "Play season 2" or a Jellyfin party playlist cannot be done, and searching for a series name returns
episodes, not the series. Music is the main use of the Wi-Fi and Bluetooth speaker adapters, so this caps their value.

**Proposal.** 1. Add a collection kind (ALBUM, SERIES, PLAYLIST, or one COLLECTION) and map MusicAlbum, Playlist, Series
and BoxSet. Include them in search and add a Playlists rail. 2. The play sheet shows a collection's children (GET
/Items?parentId=) and offers Play all and Shuffle. 3. Route only through device-native queues: - the Jellyfin session's
PlayNow with itemIds=a,b,c, and the Cast receiver's items list (both already lists); - Sonos: AddURIToQueue, then play
x-rincon-queue; - Bluetooth: an mpv playlist (mpv already plays .m3u); - plain UPnP renderers: the first item only,
labelled "plays the first track only". 4. Core gains a queue form of PlayMedia (a list of streams). Start with Jellyfin
session, Cast and Bluetooth (S–M); Sonos comes second.

**Trade-offs.** Commands-ephemeral rule: handing a list to the device's own queue in one command is not a deferred
command. A server loop that sends "next track" when a plain UPnP renderer finishes would be the server issuing later
commands on its own, so it is excluded unless the owner rules otherwise. The change touches core, Jellyfin, Sonos,
Bluetooth and the play sheet.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinItemMapper.java:82-89
- core/src/main/java/dev/andre/homecontrol/core/playback/ContentKind.java
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinContentSource.java:59-79,107-118
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSessions.java:63-69
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinCastMessages.java:34
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvPlayer.java:127
- docs/user/devices.md:193-194

<a id="prod-7"></a>

### PROD-7 — Backup and restore from Setup, plus an automatic snapshot before one-way upgrades

Impact **medium** · effort **L** · feature · revised by the verifier · **open**

**Problem.** CasaOS users, the documented primary install, rarely open a shell. A CasaOS or Watchtower image update runs
the one-way migration with no copy taken. A dead SD card loses the Android TV keystore (every pairing), the LG and
Samsung keys and every source credential. Moving to a new host means knowing which files belong together and that the
directory must be chown'ed to 1000.

**Proposal.** 1. Setup → Account → Download backup, behind the login, needing a typed passphrase. It is a ZIP of the
data files without logins.json, encrypted with AES-GCM under an Argon2id-derived key (crypto.Argon2id). Its manifest
records the app version, each file's format version, and whether secret.key was left out because HOME_CONTROL_SECRET is
set. 2. Restore from backup, offered on a fresh install (no devices, no login) where nothing would be overwritten.
Otherwise it is staged in /data/restore and applied at the next start before any store is opened. A backup in a newer
format is refused with the existing "written by a newer Home Control" check. 3. At startup, when the running version
differs from the last one recorded in /data, write /data/backups/pre-<version>.zip of the whole directory before any
store is read, keeping the last three. Document that this protects rollbacks, not disk loss.

**Trade-offs.** A backup holding secret.key and secrets.json is a full credential dump, so a passphrase is mandatory and
the login is required. A backup in a newer format must be refused, reusing the existing "written by a newer Home
Control" checks. The staged restore is a deferred change of state, not a device command, so the ephemeral rule is
untouched, but the UI must say it happens on restart. Restoring the Android TV keystore on a new host works, because the
pairing is a client certificate.

**Evidence:**

- README.md:83-92
- docs/user/configuration.md:99-106
- src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java:128-165 — lazy migration on first read; 168-182 —
  one redacted per-file copy
- src/main/java/dev/andre/homecontrol/storage/DataDirectory.java:15-26
- src/main/java/dev/andre/homecontrol/crypto/Argon2id.java
- compose.yaml:25, casaos/docker-compose.yml:8 — restart: unless-stopped

<a id="prod-8"></a>

### PROD-8 — Workflow tiles play on Wi-Fi and Bluetooth speakers, not only Cast

Impact **medium** · effort **M** · improvement · revised by the verifier · **open**

**Problem.** Workflows are the no-code way to turn a JSON API into playable tiles, for example a radio directory or a
podcast feed with fresh tokens. Scenario: an internet-radio workflow plays on a Chromecast Audio, but the household's
Sonos and DLNA receiver answer "This device is not a Cast receiver", although the resolved stream is exactly what they
play.

**Proposal.** Add a second workflow route strategy for MEDIA_RENDERER that sends Action.PlayMedia with the resolved
stream. Keep the Cast route ranked first on Cast devices. Keep the revision and enabled rechecks and the dispatch
serialised under store.ifCurrent, and replace any renderer error text with a fixed message, as the Cast branch does.
Leave LOCAL_AUDIO_SINK out for now, or add it only once mpv's fetch is constrained: no redirects, or redirects only to
the vetted host, and the address pinned. With Bluetooth, the server fetches feed-derived URLs itself, outside the
guarded client and the workflow loopback rule. Update docs/user/sources.md and the spec's "cannot fall back to DLNA"
line.

**Trade-offs.** Fresh media tokens in URLs now reach more kinds of device, which may log them; that is the same exposure
as Cast today. ConnectionManager format matching applies, so a renderer may refuse a type. No new UI, and the ephemeral
rule is unaffected.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCastRoute.java:5
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowConfiguration.java:62-63 — planned only for
  CAST_RECEIVER
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowCastRouteExecutor.java:38-66,73-77
- src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java:52-66 — the media URL is vetted once
- src/main/java/dev/andre/homecontrol/adapters/bluetooth/player/MpvPlayer.java:127 — mpv fetches the URL itself
- docs/superpowers/specs/2026-09-22-dynamic-workflows-design.md:182-183
- docs/user/sources.md:115-120

<a id="prod-9"></a>

### PROD-9 — Cast speaker groups as playable targets

Impact **medium** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Households with Nest Audio or Google Home speakers group them in the Google Home app ("Downstairs"). Home
Control can only play to one of them at a time. Sonos gets grouping from the drawer; Cast speakers get nothing.

**Proposal.** Discover groups as their own devices, identified only by the Cast UUID (TXT id). Stop
CastSettings.isReceiver from matching by host when both sides have a cast id, so a group never re-points its leader's
entry. Name that exception in the matching code with its reason. Follow a group across leader changes by its UUID with
the existing address-changed path, which already stores the port per adapter. Capabilities: CAST_RECEIVER and VOLUME.
The drawer shows members read-only, from multizone status. Add DeviceMatching and Enrollment tests for a group and its
leader at one host. Groups are still created in Google Home.

**Trade-offs.** Device identity has to allow two devices at one host on different ports, a change in an area with a
history of identity bugs (AC2). Groups are still created and edited in Google Home; there is no API for that. The change
stays inside the cast module and its switch.

**Evidence:**

- src/main/java/dev/andre/homecontrol/adapters/cast/CastDiscovery.java:72-76,89-98 — groups dropped
- src/main/java/dev/andre/homecontrol/adapters/cast/CastSettings.java:42-44 — isReceiver matches by castId or same host
- src/main/java/dev/andre/homecontrol/adapters/cast/CastAdapter.java:94-100 — carries uses that host fallback
- src/main/java/dev/andre/homecontrol/device/Enrollment.java:321-330 — same host on another port is treated as moved
- src/main/java/dev/andre/homecontrol/device/DeviceMatching.java:54-55 — owner() skips devices that already have a cast
  adapter
- docs/user/devices.md:94-95

<a id="prod-10"></a>

### PROD-10 — Samsung 2016 TVs that only offer ws:8001 cannot connect: add the plain WebSocket fallback (verified open question)

Merged into [DEV-2](#dev-2), which covers it. Impact low, effort M, status open.

<a id="prod-11"></a>

### PROD-11 — Next adapters by fit: Roku (ECP) and Kodi (JSON-RPC); Fire TV, Apple TV, AirPlay and Spotify Connect judged poor fits

Impact **low** · effort **L** · feature · revised by the verifier · **open**

**Problem.** A household with a Roku stick or TV, or a Kodi box (common among Jellyfin self-hosters), gets no keys, no
app launch and no now playing for it. Kodi at best shows up as a plain DLNA renderer, and only if its UPnP renderer is
switched on.

**Proposal.** Now (S): add the platform-fit evaluation to the concept doc. Kodi (JSON-RPC) and Roku (ECP) are good fits.
Fire TV is out (ADB only). Apple TV and AirPlay are out (no Java implementation, reverse-engineered pairing). Spotify
Connect is out (cloud Web API, Premium). Plex is the next source to evaluate (local API, PIN flow under ADR 0005).
Later, only when a household has the device (L each): a switchable kodi module (mDNS _xbmc-jsonrpc-h._tcp, Input.* keys,
Input.SendText, volume, Player.Open as MEDIA_RENDERER, notifications on 9090) and a roku module (SSDP roku:ecp, keypress
including PowerOn and PowerOff, launch, query/active-app), each with a fake in protocols' test fixtures and a hardware
checklist.

**Trade-offs.** Demand is a judgement call. Roku's share in Germany, the concept's assumed market, is small; it is large
in the US. Kodi is niche but overlaps with Jellyfin users. Roku OS's "Control by mobile apps" setting can restrict ECP;
document it. Rejected or deferred: - Fire TV: no open control protocol besides ADB, which the project rules out. - Apple
TV: Companion/MRP pairing is reverse-engineered, with only a Python reference (XL). - AirPlay: same problem, and the
server would become an audio sender. - Spotify Connect: control only through Spotify's cloud Web API, which needs
Premium and is limited by developer quotas. Not forbidden, but a poor fit for a LAN appliance. Plex as a content source
is plausible: a local server API, an On Deck rail, and a token through the plex.tv PIN flow, like YouTube's device flow
and allowed by ADR 0005. It is the next source to evaluate.

**Evidence:**

- src/main/java/dev/andre/homecontrol/config/Module.java:11-24
- docs/superpowers/specs/2026-09-16-home-control-center-concept.md:56-64,87
- docs/superpowers/specs/2026-08-30-shield-remote-vnext-design.md:467-468

<a id="prod-12"></a>

### PROD-12 — A "Played from Home Control" rail: local history across every source

Impact **medium** · effort **M** · feature · confirmed · **open**

**Problem.** "Continue watching across Jellyfin and YouTube" cannot come from the services: YouTube's API exposes no
watch history or position, and Netflix and Prime expose nothing. Home Control does know what the household launched
through it. Scenario: the kids want "the YouTube video from yesterday" and must search again, which costs 100 of the
10,000 daily quota units and one of the 20 daily searches.

**Proposal.** A `history` content source (module `home-control.history.enabled`). PlaybackService publishes a
ContentPlayedEvent (source, item id, title, subtitle, artwork, device, time) after a successful play. The source keeps
the last 50 distinct items in /data/history.json through VersionedJsonFile, never URLs or credentials: workflow items
are kept by id only and resolved again through ContentSources at play time. A "Recently played" rail, "Play again on
<same device>" in the play sheet, and Clear history and an off switch in Setup.

**Trade-offs.** The history is shared by the whole household (per person only with PROD-13), so it must be clearable and
switchable off. It holds no resume positions for YouTube or Netflix, so it is labelled honestly ("played from Home
Control", not "continue watching"). Workflow items whose revision changed can no longer play and are dropped. Fits the
storage rules: local JSON, written atomically, ready for migrations.

**Evidence:**

- src/main/java/dev/andre/homecontrol/playback/PlaybackService.java:69-104
- src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeProperties.java:30-31
- core/src/main/java/dev/andre/homecontrol/core/content/ContentSource.java:32
- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedShortcuts.java:45-58 — resolves through ContentSources

<a id="prod-13"></a>

### PROD-13 — Household members: a Jellyfin user and rail preferences per person

Impact **low** · effort **L** · feature · revised by the verifier · **open**

**Problem.** Jellyfin's Continue watching and Next up are per Jellyfin user. When each person has their own Jellyfin
user, the dashboard shows only the user who connected Jellyfin, and Cast receiver playback records progress against that
user, not the person watching.

**Proposal.** Defer until a household asks. If built: Setup → Household lists members, each with an optional Jellyfin
user whose token is kept in secrets.json, and optional hidden rails. The current member is chosen per browser. Because
members have no passwords, a member mapped to a restricted Jellyfin user (a child) must not be able to switch to an
unrestricted one without the household login. Otherwise the picker bypasses Jellyfin's parental controls. RailCache and
the rail SSE events gain a member key, and Cast PlayNow uses the member's user. YouTube stays household-wide.

**Trade-offs.** Rails are fetched once per member (cheap for a local Jellyfin). The UI must say plainly that this is not
security. RailCache and the SSE rail events gain a member dimension. Device-side Jellyfin apps keep their own logged-in
user. For one- or two-person homes this may be overkill, so ship it only if asked.

**Evidence:**

- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSettings.java:11-13,18
- src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinCastMessages.java:41
- core/src/main/java/dev/andre/homecontrol/core/content/SourcePreferences.java:24-26
- docs/superpowers/specs/2026-09-16-home-control-center-concept.md:62-63

<a id="prod-15"></a>

### PROD-15 — Localise the interface, German first

Merged into [WEB-12](#web-12), which covers it. Impact medium, effort XL, status open.

<a id="prod-v1"></a>

### PROD-V1 — LG TVs' absolute volume works and is documented, but the drawer never shows a slider for a TV on its own

Impact **medium** · effort **S** · defect · found by the verifier · **open**

**Problem.** Scenario: an LG TV is paired by itself, with no merged Cast or DLNA receiver, so its capabilities are
REMOTE_KEYS, APP_LINK, VOLUME, INPUTS and WAKE_ON_LAN. The drawer shows only the Vol −, Mute and Vol + key buttons and
the "vol N" label. No slider appears, because the template ties the slider to Cast or renderer capabilities, not to
VOLUME. The user guide's table says LG has an absolute volume slider. The adapter's SetVolume is therefore unreachable
from the UI. Going from 8 to 30 takes 22 taps, and the guide promises a control that is not there.

**Proposal.** Add a dashboard model flag for VOLUME, and render the existing volume slider (posting to
/devices/{id}/volume, with id volume-<device>, which state-view.js already updates) when the device has VOLUME and
neither Cast nor renderer controls already show one. Add a DashboardPageTest case for REMOTE_KEYS plus VOLUME and a
browser test step. Otherwise, correct docs/user/devices.md:121.

**Trade-offs.** Small template and controller change with no protocol work. For a TV that is merged with a DLNA
renderer, check that only one slider shows and which adapter gets SetVolume. No conflict with the product rules: the
command is still sent now or fails now.

**Evidence:**

- docs/user/devices.md:121 — 'Absolute volume slider | yes (LG) | no (Samsung)'
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsAdapter.java:82-84 — webOS declares Capability.VOLUME
- src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSession.java:159-160 — SetVolume is carried out
- src/main/java/dev/andre/homecontrol/web/DashboardController.java:56-58 — castControls and rendererControls only,
  nothing for VOLUME
- src/main/resources/templates/dashboard.html:115-119,129-133 — range inputs only inside .cast and .renderer
- src/test/java/dev/andre/homecontrol/web/DashboardPageTest.java:189-195,289-294 — slider cases only with CAST_RECEIVER,
  MEDIA_RENDERER or LOCAL_AUDIO_SINK

### Rejected in PROD

- **PROD-14 — Kick-off reminders on the open dashboard, offering the command rather than running it.** The evidence is
  accurate: data-starts-at and data-ends-at are rendered (fragments/rails.html:63) and read by no script, and the
  subtitle bakes in the time (SportsItems.java:55-67). The proposal is not worth its cost. The reminder appears only
  while the dashboard is open. That dashboard is a phone remote, rarely left open; the Notification API also needs HTTPS
  and an open tab, and Web Push is rightly excluded by the no-cloud rule. So the banner would seldom reach anyone at
  kick-off. The remaining piece, a live countdown or relabel from data-starts-at, is cosmetic, since the sports rail
  refreshes every 5 minutes anyway (SportsContentSource.java:94-96). The analyst rated it low impact for M effort, and
  nothing found here raises that.
