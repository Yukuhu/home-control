# Test coverage and browser scenarios

Goal: raise test coverage as far as tests can reach, and widen the browser tests to the flows no browser test drives
yet. Tests only; a production change is made only where a test shows a defect, in its own `fix:` commit.

## Starting point (main 0ddaa8cd, CI run 37216390353)

| Measure | Lines | Branches |
| --- | --- | --- |
| Java, handwritten code (generated protobuf left out) | 16,738 / 17,654 = 94.8 % | 7,428 / 8,860 = 83.8 % |
| JavaScript in the browser tests (Chromium) | 1,554 / 1,601 = 97.1 % | 568 / 650 = 87.4 % |

The combined JaCoCo report also counts the generated Android TV and Cast protobuf classes (about 7,000 lines), which
SonarCloud does not analyse; the figures above leave them out.

## Batches, one pull request each

| Batch | Scope | Largest gaps |
| --- | --- | --- |
| C1 protocols and core | `protocols/`, `core/` | TextWebSocket, CastConnection, SsapConnection, RemoteConnection, TlsSockets, DeviceFetch, PairingSession, InsecureTls, RendererResolver, SonosEndpoints, DialClient, SourcePreferences, AppLinks, ServiceLinks |
| C2 adapters and discovery | app `adapters`, `discovery` | MdnsBrowser, SsdpDiscovery, SonosSession, CastSession, AndroidTvSession, WebOs/Tizen sessions, Bluetooth player, CertificateStore |
| C3 content sources | app `sources` | WorkflowValidator, workflow setup/calls/runner/JSON, YouTube setup/authorization/OAuth, Jellyfin executors and setup, TheSportsDB mapper, CalendarSchedule, pinned store |
| C4 web, security, storage, themes | app `web`, `security`, `storage`, `themes`, `content`, `device` | ThemeCss, ThemeCatalog, SourcesSetupController, LoginService, RememberedLogins, JsonFileSourceSettings, VersionedJsonFile, SecretStore, Enrollment, SearchService |
| E1 browser scenarios | `src/e2e` | see below |

## Browser scenarios to add

- Play sheet failure paths: the route preview answers with an error, the play attempt cannot reach the server,
  a play attempt fails without a route, pinning a link cannot reach the server, the pin hint for an item without a
  service (title and live event), an optimistic app link whose device changes state (no "may not be installed" hint).
- Remote: a key the device refuses shows its reason.
- Dashboard controls no browser test touches: volume slider, mute and unmute, pause, play, stop, inputs, group
  join and leave.
- Setup flows: pinned shortcuts (add, rename, move, remove), rail visibility and order, source switches, forgetting,
  merging and splitting devices, adding a device by address, setting and removing the password, logging out, a wrong
  password.
- Install card: the browser's install prompt, an installed app, the page restored from the back-forward cache.

## Not reachable by a test

Each batch records here the misses it leaves, with the reason (an `InterruptedException` handler, real multicast,
a real `mpv` process, a TLS handshake failure inside the JDK).

- C1: catch blocks for exceptions the JDK never throws here (`NoSuchAlgorithmException` for SHA-256, an in-memory
  keystore's `IOException`, `UnknownHostException` for an address that already passed `isIpLiteral`, a Cast waiter
  completed with anything but an `IOException`); `TextWebSocket`'s own opening timeout, which the JDK's handshake
  timeout always beats; a send that fails or is interrupted mid-write (the outcome races the listener's close); the
  `InsecureTls` trust manager's client-side callbacks, which an outbound connection never calls; the IANA fallback
  spelling of Europe/Kyiv on JDKs that know it.
- C2: real multicast in `MdnsBrowser` and `SsdpDiscovery` (CI runners have none; `-Dmdns.tests=true` runs the
  opt-in test); the mDNS listener adapters in `CastDiscovery` and `MdnsDiscovery`, reachable only through the
  browser's package-private dispatch; `PairingService.begin(host, name)`, which would need the fixed pairing port.
- C3: JDK-exception handlers that cannot fire; races a test would have to time (a calendar removed between being
  found due and downloaded, the Jellyfin deadline between its check and the session lookup, a run's deadline against
  a free workflow slot).
- C4: the theme upload's second size check and the multipart-limit handler (a servlet container limit MockMvc does
  not enforce); the interrupt paths of `SearchService` and `EventStream`.
- JavaScript: `sw.js`'s branches (the service worker runs in its own isolate; only some of its paths are driven by
  the offline tests).
- Unused code, left for a separate decision: `PlayableRef.kindLabel()` and `ContentSourceException`'s constructor
  with a cause are never called in production. `remote-transport.js`'s `openLink` was removed (E2).
- A measurement gap, not a test gap: `WorkflowValidator`, `WorkflowJson`, `WorkflowPlan` and `YouTubePlaylists`
  refuse input through helpers that always throw but return nothing, so JaCoCo counts the calling line and its
  branch as missed even when a test reaches it. A helper that returns the exception (`throw fail(...)`) would let
  them count.

## Results

Measured against main at the start (handwritten Java: 94.81 % of lines, 83.84 % of branches; browser JavaScript:
97.06 % of lines, 87.38 % of branches). Each Java row is that batch alone on main.

| Batch | Pull request | Tests added | Java lines | Java branches |
| --- | --- | --- | --- | --- |
| C1 protocols and core | #197 | 95 | 95.24 % | 85.12 % |
| C2 adapters and discovery | #198 | 91 | 95.50 % | 84.89 % |
| C3 content sources | #201 | 213 | 96.09 % | 87.30 % |
| C4 web, security, storage, themes | #200 | 28 | 95.03 % | 84.23 % |

| Batch | Pull request | Browser tests added | JavaScript lines | JavaScript branches |
| --- | --- | --- | --- | --- |
| E1 user flows | #199 | 24 per browser | 99.06 % | 88.41 % |
| E2 swipes, labels, restored forms | #202 | 5 per browser | see its CI summary | |

Open questions the batches raised, for the owner: whether TheSportsDB's `strTime` offset (`18:45:00+01:00`) should
be honoured (it is read as UTC; no test pins either reading), and the two unused members above.

## Rules

- New app tests are plain unit tests or extend `WebSliceTest`, `FullAppTest` or `ModulesOffTest`; no own Spring
  context (`SharedContextRulesTest`). Protocol and core tests sit in their module (`TestArchitectureTest`).
- Per batch: `scripts/gradle.sh build` green, and the per-class test counts checked against main's `junit-test`
  artifact before pushing.
