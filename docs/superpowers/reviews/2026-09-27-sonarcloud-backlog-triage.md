# SonarCloud backlog triage — 2026-09-27

Branch: `fix/sonarcloud-backlog`, from `main` at `67e9adf`.

## Baseline

SonarCloud reported **480 open issues** on `main`, 480 of them in the new-code period: 411 code smells, 49 bugs and
20 vulnerabilities (5 blocker, 63 critical, 157 major, 254 minor, 1 info). The quality gate failed on new-code
reliability (E, from the blocker bug) and security (D, from the critical TLS findings); both conditions require A,
which means **no open bug and no open vulnerability**. Coverage (87.6%), duplication and maintainability passed; there
were no security hotspots to review. This continues the [first triage](2026-09-26-sonarcloud-triage.md), which left
TLS trust, concurrency, sleeps, complexity and build metadata for separate work.

## Result

Every issue key has exactly one decision, listed with its reason in
[`2026-09-27-sonarcloud-backlog-decisions.tsv`](2026-09-27-sonarcloud-backlog-decisions.tsv).

| Type | Fixed | Accepted | False positive | Total |
| --- | ---: | ---: | ---: | ---: |
| Bug | 21 | 0 | 28 | 49 |
| Vulnerability | 2 | 18 | 0 | 20 |
| Code smell | 361 | 41 | 9 | 411 |
| **Total** | **384** | **59** | **37** | **480** |

*Accepted* means the rule is right in general but the code is deliberately that way, for a reason stated at the site.
*False positive* means the analyzer misreads the code.

**How decisions are declared.** 82 of the 96 unfixed findings are Java and carry the narrowest possible
`@SuppressWarnings("java:S…")` — on the field, method, constant or nested class, never a whole top-level class — with a
one-line comment stating why. The remaining 14 are in files or scopes where no inline declaration exists (Dockerfiles,
HTML, CSS, a project-level Gradle finding, a file-level finding with no line, and a taint-analysis finding); they
need resolving in SonarCloud once, see [SonarCloud follow-up](#sonarcloud-follow-up). No rule was disabled and no
exclusion was added to the scanner configuration.

## Real defects found

- **Calendar removal lost a concurrent refresh.** `CalendarSchedule.forget()` rebuilt the event index from a read of a
  volatile map and wrote it back without the lock `events()` holds while replacing the same map. A refresh finishing
  in between was overwritten, so new events disappeared from `find()` until the next refresh. The index is now an
  `AtomicReference` edited with `updateAndGet`.
- **A late Android TV pairing submit cancelled the next attempt.** `PairingService.submit()` ended by cancelling
  whatever attempt was current, and two concurrent `begin()` calls could replace an attempt without closing its TLS
  pairing socket. Attempts are now swapped atomically: `begin` closes the attempt it displaces and `submit` only clears
  its own. `aSubmitThatEndsAfterANewAttemptBeganLeavesTheNewAttemptInProgress` fails on the old code.
- **TV connections could be closed twice.** Tizen and webOS sessions read, cleared and closed their connection both on
  the scheduler thread and in `close()` without a common lock. Harmless today because closing is idempotent; each
  connection is now taken out exactly once.
- **Errors vanished.** The workflow fetch worker and the SSE fan-out caught `Throwable`, so an `Error` was reported as
  an ordinary failure or logged at debug level. Errors now reach the thread's uncaught-exception handler. The waiting
  workflow caller is still told at once; the SSE subscriber whose send raised the Error is dropped, as before, and
  later events reach the remaining tabs (both tested). The one difference from `main`: tabs after the failing one miss
  that single event, because the Error ends the fan-out task instead of being swallowed.
- **Dead conditions.** `DeviceManager`'s stop fallback tested a flag that was always true there, and the TMDB rail
  tracked an "attempted" flag already implied by a recorded failure. Both are gone with unchanged results.
- **Records compared byte arrays by identity.** `JellyfinClient.Image`, `YouTubeHttp.Response`,
  `SecretKeySource.KeyHeader` and the workflow `FetchResponse` now compare contents, and their `toString` prints a
  byte count instead of an array identity.
- **Tests that could not fail, or failed at random:**
  - `YouTubeSearchTest`'s coalescing test slept 200 ms and hoped eight callers had queued; a late caller hit the cache
    and the test passed vacuously. It now waits until every caller is parked inside `YouTubeSearch.search`.
  - `TizenAdapterTest.anSsdpAnnouncementTriggersAnImmediatePoll` passed without SSDP: an HTTP-client handshake retry
    after the 500 ms sleep could connect on its own. The first poll is now answered with "standby", and the test waits
    for it before switching the TV on.
  - `LoginGatingTest` and `SourcesSetupControllerTest` asserted over collections that could be empty.
  - Four SSE tests (`SpeakerJellyfinEndToEndTest`, `BluetoothJellyfinEndToEndTest`, `CastEndToEndTest`,
    `DeviceStateStreamEndToEndTest`) left a reader thread that died with an uncaught `UncheckedIOException` after
    `shutdownNow()`. Awaitility reports such exceptions from whatever `await()` is running, including the next
    test's — the intermittent `SpeakerJellyfinEndToEndTest` failure seen twice during this work. A shared
    `EventStreamReader` now ends quietly on shutdown and is joined.
  - `WorkflowEndToEndTest` asserted "no upstream fetch yet" right after saving a generated workflow, racing the rail
    cache's background load of the new rail (failed once in the full run). The tests already count fetches once the
    rail is ready — one for a generated rail, none for a single tile — which proves saving fetched nothing.
  - `RailFailureE2eTest` waited for a rail snapshot newer than the one `refresh()` returned, but `refresh()` reads the
    rail after handing the fetch to the executor, and the instant fake source can finish first. This failed `main`'s
    CI twice on 2026-09-26 (runs 36277806061 and 36280306788) and once locally. The helper now takes the version
    before triggering and waits for the fetch's own finished snapshot.

## What was fixed

| Area | Rules | Change |
| --- | --- | --- |
| Generated test names | S117 (124), S1612 | The earlier S5778 refactor had hoisted arguments into locals such as `preparedArg157_1`. All 202 such locals (flagged or not, including `failingActionNNN`) now have meaningful names; values hoisted repeatedly in one method are hoisted once; no-argument receivers became method references. |
| Sleeps in tests | S2925 (31 of 43) | Waits for observable conditions (Awaitility, latches, thread state). "Nothing may happen for this long" checks became `await().during(window)`, which checks continuously for the same window. |
| Complexity | S3776 (29), S135, S1141, S3358, S127, S6916 | Extracted named steps in the ICS parser and recurrence rules, TheSportsDB schedule, calendar fetcher, sports store, TMDB rail and mapper, DeviceManager, host allowlist, Jellyfin client/resolver/setup, workflow editor/template, YouTube lounge and API errors, playback planner. Order, error handling, logging and locking are unchanged; an ICS duration-regex split was compared with the original on 4.3 million generated inputs. |
| Concurrency | S3077 (9 of 37), S2445 | Real races fixed as above. Fields only ever touched by one thread lost `volatile`; an override set once became `final`. DeviceManager's state generation got an explicit lock owner. |
| Blockers | S1845, S2095, S3516 | Fields clashing with constants renamed (`SecretStore.credential`, `LoginController.loginService`). The Tizen factory now builds its `AutoCloseable` connection only after the WebSocket is open. The sports time-zone handler has one exit. |
| Small correctness | S2184, S5850, S2479, S5843, S8786, S2589, S2629, S6218, S6206, S1181, S112, S4449 | Long arithmetic, explicit regex grouping, escaped BOM, linear trailing-slash strip, narrowed exceptions (`IOException, SoapFault` for poller links), lazy debug logging, array-aware records. |
| Tests | S5838, S5853, S6126, S1186, S108, S1130, S5841, S5958, S5976, and others | Assertion chains, text blocks (byte-identical; SSDP fixtures keep CRLF via `\r`), explained empty listeners, stronger exception checks, one parameterized test. |
| Build | kotlin:S6624, docker:S6506 | Versions moved into `gradle/libs.versions.toml`; the resolved dependency graph and plugin classpath are byte-identical to `main`. `protobuf-java` and `protoc` share one version, so Dependabot can no longer bump them apart (PR #97 bumped only the runtime and was closed). The e2e image's `curl` refuses plain-HTTP URLs and redirects. |
| Templates, CSS | Web:PageWithoutTitleCheck, Web:S6807, css:S4666 | Three fragments lost an `html`/`body` wrapper their seven siblings never had; the play sheet's radios got a static `aria-checked` prototype value; a duplicated `.tiles` rule was merged. |

## Behaviour changes

Everything else is refactoring or declarations: endpoints, status codes, bodies, content types, persisted formats,
IDs, protocol messages, trust and pinning, timeouts and retries are unchanged.

- Android TV pairing: a late code submission no longer cancels a newer attempt, and a displaced attempt's socket is
  closed.
- Errors in the workflow fetch worker and the SSE fan-out reach the uncaught-exception handler instead of being
  swallowed (see above for the SSE detail).
- The play-preview and play-attempt endpoints are typed; their 400/404 answers come from the controller's existing
  plain-text handlers with the same status, text and content type (`ContentPlayErrorBodiesTest` pins this).
- The workflow editor shows "Check the form and try again." for a field error without text; the controller never
  creates one, so this is unreachable today.
- Records holding byte arrays compare contents and print a byte count.
- `UpnpSession` restores the interrupt flag when `close()` interrupts a description fetch; the poller handles the
  resulting `InterruptedIOException` as before.
- Build: `protobuf-java` and `protoc` share one version.

## Accepted and false positives

The per-issue reasons are in the decisions table; the groups are:

- **Trust-all TLS towards LAN appliances (java:S4830, 12, accepted — owner decision).** Cast receivers
  (`CastTls`, see the [Cast sender ADR](../specs/2026-09-16-cast-sender-adr.md)) and Samsung/LG TVs (`InsecureTls`:
  Tizen `wss:8002`, webOS SSAP `wss:3001` fallback and pointer socket) only present self-signed certificates and none
  of these protocols offers a pinning handshake. The Shield never uses these trust managers: pairing authenticates via
  the on-screen code and every later session pins the stored certificate fingerprint.
- **SSRF in `TlsSockets.connect` (javasecurity:S5144, accepted).** The host is a device address entered on
  `POST /setup/pair` and the port is the fixed pairing port 6467. Requests pass the Host allowlist and cross-origin
  refusal and, once a household login exists, the login gate. Arbitrary LAN addresses, including global IPv6
  addresses, are deliberate input.
- **Root in the runtime image (docker:S6471, 3, accepted).** Existing installs bind-mount a root-owned `/data`, and
  the Bluetooth variant talks to the host's D-Bus (BlueZ policy: root or the host's `bluetooth` group) and to uid
  1000's PulseAudio socket. Adding `USER` would break every existing install. A migration needs an entrypoint that
  fixes `/data` ownership, a documented uid/gid and group mapping for the Bluetooth variant, and release notes. The e2e
  image is local test tooling that already runs as the invoking uid.
- **Dependency verification and locking (kotlin:S6474, text:S8569, accepted).** Dependabot cannot update
  `gradle/verification-metadata.xml` (dependabot-core #1996), so every weekly update PR would fail verification. The
  resolved graph contains no ranges or dynamic versions (checked with `gradle dependencies`); every version is exact or
  BOM-managed, so a lockfile would duplicate what is already deterministic. Revisit if a range is ever added or the
  project moves to Renovate, which can maintain verification metadata.
- **Volatile snapshots (java:S3077, 28 false positives).** Each field holds an immutable record/list or a
  self-synchronized object and is only replaced wholesale — on a single owning thread, or under an existing lock — so
  `volatile` gives readers exactly the visibility they need. Every declaration names the threads involved.
- **Long constructors (java:S107, 9 accepted).** Spring injection points with distinct collaborators (plus a clock or
  random source that tests pin), `Argon2id.derive` mirroring RFC 9106's own parameter set, and `DeviceMerge.attach`.
  The one case with a real value object (`IcsOccurrences` time window) was fixed.
- **Test design (java:S5961 9, java:S2925 12, java:S3415 1).** End-to-end journeys whose steps depend on the previous
  step's state; sleeps that *are* the simulated behaviour (slow fake servers, a trickling device, fake mpv process
  timing), each isolated in a small helper; and an "argument order" warning on a recorded actual value.
- **Protocol and route constants (java:S1075, 5).** Sonos UPnP control paths identical across firmware, and this
  app's own routes (one must be a compile-time constant for `@GetMapping`).
- **Controller shapes (java:S1452 4, java:S3516 1, java:S2094 1).** Content endpoints answer JSON or one of several
  plain-text error bodies the browser shows verbatim; the pinned-links controller follows post/redirect/get; the
  preferences event is matched by type alone.
- **`java.util.Date` in `ClientCertificate` (java:S2143, accepted).** BouncyCastle's certificate builder only accepts
  `Date`; the code converts from `Instant` at that one call.
- **Front end (5).** The touchpad (`role="application"`) handles arrow/Enter/Space keys and needs its tab stop; the
  play-on picker implements the WAI-ARIA radio-group pattern and is covered by browser tests; autofocus on the
  single-field login page is the documented appropriate use; the two contrast findings ignore translucency and
  gradients (actual contrast about 9:1 and 6.6:1); `/js/pwa.js` in an inline module script is a URL, not a path.
- **`apt-get` in the e2e image (docker:S6595).** Update and install run in the same `RUN`; the install is done by
  `playwright install --with-deps`, which the analyzer cannot see.

## SonarCloud follow-up

Findings declared in code disappear on the next analysis of `main`. The 14 rows marked `sonarcloud` in the decisions
table must be resolved once in SonarCloud; six of them are vulnerabilities, so `main`'s gate stays red until then. With
a token that may administer issues on `Yukuhu_home-control`:

```bash
export SONAR_TOKEN=...   # https://sonarcloud.io/account/security
awk -F'\t' '$8 == "sonarcloud" { print $1 "\t" $7 "\t" $9 }' \
    docs/superpowers/reviews/2026-09-27-sonarcloud-backlog-decisions.tsv |
while IFS=$'\t' read -r key outcome reason; do
    transition=$([ "$outcome" = FALSE_POSITIVE ] && echo falsepositive || echo accept)
    curl -fsS -u "$SONAR_TOKEN:" https://sonarcloud.io/api/issues/add_comment \
        --data-urlencode "issue=$key" --data-urlencode "text=$reason (docs/superpowers/reviews/2026-09-27-sonarcloud-backlog-triage.md)" >/dev/null
    curl -fsS -u "$SONAR_TOKEN:" https://sonarcloud.io/api/issues/do_transition \
        --data-urlencode "issue=$key" --data-urlencode "transition=$transition" >/dev/null
    echo "$key: $transition"
done
```

## Verification

All runs used the repository's Gradle wrapper inside `gradle:jdk25` (the host has no JDK), on the final commit.

- `./gradlew build compileE2eJava` — BUILD SUCCESSFUL: 300 test classes, 2,630 tests, 0 failures, 0 errors, 1 skipped
  (`MdnsDiscoveryTest.findsAServiceAdvertisedOnTheLocalNetwork`, opt-in via `-Dmdns.tests=true`, unchanged).
- `./gradlew e2eTest -Pe2eCoverage=true` in the Playwright image built from the changed `scripts/e2e.Dockerfile` —
  BUILD SUCCESSFUL: 102 browser tests in Chromium and WebKit, 0 failures. `RailFailureE2eTest` passed three further
  reruns.
- The five touched SSE/workflow end-to-end classes passed three consecutive reruns; each scope's timing-sensitive test
  classes were rerun at least twice while they were being changed.
- `gradle dependencies buildEnvironment` output is byte-identical to `main` (2,526 lines, every configuration and the
  plugin classpath) and contains no dynamic versions or ranges.
- The e2e image's `curl` downloads the Playwright driver over HTTPS and refuses a plain-HTTP URL.
- An independent review of the whole diff found no critical or important issues; its two actionable minor points
  (the SSE subscriber on Error, an over-broad S3415 declaration) are fixed.
- All 480 issue keys appear exactly once in the decisions table; every Java declaration was checked against its key.
- A pull request's analysis only reports issues on changed lines, so fixes whose flagged line stays unchanged (a
  method signature, a loop header) were checked separately. PMD 7.28's `CognitiveComplexity`, which follows the same
  specification, finds all 29 flagged methods at or below 15 now (30 methods above it on `main`, one of which Sonar
  itself never flagged). Every flagged loop has at most one `break`/`continue`, every moved method sits in `Link`, and
  the remaining one-off fixes were read. This audit caught one fix that had silently not happened (the BOM escape).
- The pull request's first analysis passed its gate but reported 13 minor findings in new code (record patterns in
  the new `equals` methods, an unused import, two assertion chains, long lines in the e2e image, a parameterizable
  trio of tests, a helper that Sonar wanted inside `CalendarReader`). All are fixed except the last, which is declared.

SonarCloud itself has not analyzed this branch yet: the pull request's CI run imports JaCoCo and browser LCOV and
applies the gate to the changed lines. After merge, `main` needs the [SonarCloud follow-up](#sonarcloud-follow-up)
before its gate can pass.
