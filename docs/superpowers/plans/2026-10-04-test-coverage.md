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

## Rules

- New app tests are plain unit tests or extend `WebSliceTest`, `FullAppTest` or `ModulesOffTest`; no own Spring
  context (`SharedContextRulesTest`). Protocol and core tests sit in their module (`TestArchitectureTest`).
- Per batch: `scripts/gradle.sh build` green, and the per-class test counts checked against main's `junit-test`
  artifact before pushing.
