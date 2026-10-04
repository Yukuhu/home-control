# Testing

How the test suites are built and how to run them.

## Test layers

| Layer | What it is | Where |
| --- | --- | --- |
| Unit tests | Plain JUnit 5 with AssertJ, Mockito and Awaitility (the modules' tests use no Mockito). Most device and source tests drive the real client against an in-process fake that speaks the real protocol over a socket. | `src/test/java` of the module that holds the code: `core/`, `protocols/` or the root |
| Web slices | One shared `@WebMvcTest` context over every controller, with their collaborators mocked: test classes extend `WebSliceTest`. | `src/test/java/.../web`, and next to each content source's own controllers |
| End to end | The whole application with fake devices or services, over MockMvc or real HTTP: one shared `FullAppTest` context, reset after every class; a few keep their own. | classes named `*EndToEndTest` |
| Browser tests | Playwright driving the dashboard in Chromium, Firefox and WebKit. | `src/e2e/java`, see [Browser tests](#browser-tests) |
| Architecture | ArchUnit package rules. | `ArchitectureTest`, see [Architecture](architecture.md#package-rules) |

When this page was written, the unit, slice, end-to-end and architecture tests numbered 2,689.

## Web slices

A web-layer test extends `testsupport.WebSliceTest`. That class is one `@WebMvcTest` over every controller and
controller advice, with every collaborator declared once as a `@MockitoBean`, so the web tests share one Spring
context per test JVM.
- A test class stubs the base's mocks (`devices`, `pairing`, `sources`, …) and adds nothing to the context: it and its
  nested classes carry no Spring annotation (no `@Import`, property source, profile, `@DirtiesContext` or
  `@AutoConfigure…`), no bean-override field such as `@MockitoBean`, and no `@DynamicPropertySource`. Any of those
  would give it a context of its own, and `SharedContextRulesTest` fails.
- A controller that needs a new collaborator gets it as a `@MockitoBean` in `WebSliceTest`. If every `/setup` render
  calls it and Mockito's `null` would break the page, `stubSafeDefaults()` gives it a safe value.
- A module's setup section (`config.SetupSection`) is a plain component, which a `@WebMvcTest` does not pick up.
  Add a new one to `WebSliceTest`'s `@Import`, or the slice's setup page leaves it out.
- Every `/setup` render carries every module's section, so a check on the setup page reads its own section with
  `section(page, id)`, or asserts text only that section renders. A form field or a word that another section also
  renders would otherwise pass for the wrong reason.
- A test that needs a bean to be absent does not fit the slice: build the controller with a standalone MockMvc, as
  `ContentPlayWithoutPinsTest` does.

## Module switches

A test that checks a module switched off extends `testsupport.ModulesOffTest`: one application context with every
module in `config.Module` switched off, shared by all such tests. It checks that its module leaves no bean, setup
section, route or file behind (`dataDir()`), and `ModulesOffSmokeTest` that the application starts that way. A new
module added to `config.Module` is switched off there, and checked by `noSwitchableModuleNeedsAnotherModulesBean`,
without further changes. The same rules as for web slices apply (`SharedContextRulesTest`). A test of another
combination, such as sports on with TheSportsDB off, keeps a `@SpringBootTest` of its own and is named in
`SharedContextRulesTest.OWN_CONTEXT`.

## Full-application tests

A test of the whole application extends `testsupport.FullAppTest`: one context per test JVM with every module that is on
by default, a real port and MockMvc. After every class, `FullAppReset` returns the application to a fresh install: no
devices, no installed themes, no login or secrets, no sports settings, pins or workflows, an unused YouTube quota, no
rate limit, no pending pairing, fresh rails and settings files, empty caches of TMDB, YouTube and TheSportsDB answers,
and the shared web-API fakes (`SharedFakes`: TMDB, Google, TheSportsDB) reset.

- A test that sets up state it cannot leave for the next class relies on that reset; a test class that needs a fresh
  install after every test calls `FullAppReset.reset(context)` in `@AfterEach`, as `LoginGatingTest` does.
- A shared fake's routes go in `@BeforeAll` or the test, never a static initializer, and a test never closes it.
- Only the first class to use a context sees it start. `filesAtStartup()` names the files in the data directory once
  the application had started, for a test of what startup writes, as `SportsUnconfiguredTest` has.
- A test that needs other beans or properties keeps a `@SpringBootTest` of its own and is named in
  `SharedContextRulesTest.OWN_CONTEXT`, with the reason.
- New state that outlives a test class needs a line in `FullAppReset`, using the bean's own operations; only where
  none exists does the bean get a small reset method, documented as existing for the shared test context.

## Running tests

- Everything: `scripts/gradle.sh build`. It runs `./gradlew` in the `gradle:jdk25` container image, using Docker or
  Podman, for machines without a JDK 25; with one, `./gradlew build` does the same. Both build helpers choose Docker
  when available, then Podman; set `HC_CONTAINER_RUNTIME=podman` to select Podman explicitly. Rootless Podman keeps
  the host user identity for bind mounts and disables container SELinux labels without relabelling the checkout.
- Two checks run with the build. Error Prone runs as the code compiles, and one of its errors fails compilation; its
  warnings are off. `spotlessCheck` fails on trailing whitespace or a missing final newline in any source, text or
  build file, and on a leading tab or an unused import in Java; `scripts/gradle.sh spotlessApply` fixes what it finds.
- One class of the app: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ErrorAdviceTest'`. One class of
  `core`: `scripts/gradle.sh :core:test --tests 'dev.andre.homecontrol.core.ActionTest'`. One class of `protocols`:
  `scripts/gradle.sh :protocols:test --tests 'dev.andre.homecontrol.adapters.net.DeviceUrisTest'`. `test --tests`
  runs its filter in every module: a module without a match passes, but the app's own `test` fails when nothing in the
  app matches. So `test --tests` naming only a class of `core` or `protocols` runs it there and still fails the build
  at the app's `test`; run such a class with its module's task, as above. A module's own test task
  (`:core:test --tests`, `:protocols:test --tests`) fails when nothing in it matches.
- A failure's details: `grep -A20 '<failure' build/test-results/test/*.xml core/build/test-results/test/*.xml
  protocols/build/test-results/test/*.xml`.
- The unit tests run in up to four JVMs at once, and Gradle reuses the results of tasks whose inputs did not change,
  so a test that passed and was not touched is not run again.
- Every test and lifecycle method has a 60 s timeout (`junit-platform.properties` in the `src/test/resources` of the
  root, `core/` and `protocols/`), off while a debugger is attached. A test that needs longer declares `@Timeout` on
  the method, or on the class for its test methods; a long `@BeforeAll` or `@BeforeEach` needs its own.

## Dependency verification

Gradle verifies dependencies and build plugins against the SHA-256 checksums committed in
`gradle/verification-metadata.xml`, including their POM and module metadata. A missing or mismatched checksum
fails the build before the affected artifact is used.

After adding or upgrading a dependency, generate the new entries with an empty Gradle home and project cache:

```bash
rm -rf build/empty-gradle-home build/empty-project-cache
scripts/gradle.sh -g build/empty-gradle-home --project-cache-dir build/empty-project-cache \
  --write-verification-metadata sha256 build e2eClasses
```

With a warm cache, generation has left out parent POMs that the cache already held, and CI, which starts with an
empty cache, then failed on them.

Review the diff and compare the new checksums with fresh artifacts from Maven Central or the Gradle Plugin
Portal before committing them. Generation trusts the artifacts it finds, including the local cache; it does
not establish that those artifacts are authentic. Normal builds and required CI enforce the committed
metadata; generation is only a preparation step for review.

The metadata also covers the published `protoc` executables for Linux, macOS and Windows. When upgrading
protobuf, update those platform checksums too: generation on one machine discovers only its own compiler.
Run generation on the other platforms, or download their compiler artifacts from Maven Central and calculate
their SHA-256 checksums. Run `scripts/gradle.sh build` again without the generation flag to check enforcement.

### Dependabot updates

Dependabot updates the Gradle version catalog. CI completes verification metadata automatically on the same
pull request, using two jobs in `.github/workflows/ci.yml`:

1. `Verify dependency checksums` checks committed metadata strictly. When a Dependabot update needs new hashes,
   it generates candidate metadata in an isolated cache, uploads it with the PR/head/base/merge identities, and
   lets only the update job proceed. Application builds wait.
2. `Update dependency checksums` runs trusted helpers from the base commit on a fresh runner. It checks each
   new artifact against a fresh Maven Central download, falling back to the Gradle Plugin Portal only on 404.
   POM-advertised Gradle module metadata is included even when generation omits it. Existing hashes and verification settings cannot change. Protobuf updates include all published protoc
   platforms. Metadata already verified on the base is retained.
3. The job commits only `gradle/verification-metadata.xml` to the existing PR branch using the checksum App.
   Its push starts a new CI run, which verifies the committed metadata strictly before running the normal tests.

The preparation runner has no publishing credential. The update runner never runs dependency code, Gradle, or
scripts from the PR. Only same-repository Dependabot PRs changing `gradle/libs.versions.toml` and optionally
verification metadata qualify for automatic writes. There is no separate checksum workflow or PR.

Generated commits include `[dependabot skip]` so Dependabot can replace them during a rebase. A stale head is
never overwritten; a later run handles the updated PR. Repeating an already verified run makes no commit.
A repeated missing-checksum failure immediately after the App updated the same base/catalog fails visibly
instead of making an endless sequence of commits. Only verified committed metadata can make `CI passed` green.

#### One-time App setup

Install the private `yukuhu-home-control-checksums` GitHub App on this repository with **Contents: read and write**
and **Pull requests: read-only**. It needs no webhook, OAuth callback, or main-branch rules bypass.

- Repository variable: `CHECKSUM_APP_CLIENT_ID` (the App's client ID).
- Actions secret: `CHECKSUM_APP_PRIVATE_KEY` (the complete downloaded PEM private key).
- Dependabot secret: `CHECKSUM_APP_PRIVATE_KEY` (the same key).

Dependabot-triggered runs use Dependabot secrets; App-triggered and maintainer runs use Actions secrets.
The pinned `actions/create-github-app-token` action creates a short-lived repository-scoped token only after
candidate validation. Publishing authenticates normal git commands through `gh auth setup-git`.

The workflow and its base-branch helpers must be deployed together. Update an older open dependency PR with
that base, using `gh pr update-branch <number>`; rerunning a historical workflow run uses the historical code.

#### Troubleshooting and local checks

Read the failing job's log for the rejected artifact or credential error. The candidate artifact and the
verified report identify the exact PR revision. A checksum mismatch for an already trusted artifact is never
accepted automatically. The trust policy is documented in [ADR 0008](../adr/0008-automatic-dependency-checksums.md).

Run the tooling tests with:

```bash
python3 -m unittest discover -s scripts/dependency-checksums -p 'test_*.py' -v
scripts/gradle.sh test --tests 'dev.andre.homecontrol.deployment.DependencyVerificationWorkflowTest'
npm ci --prefix scripts/pr-summary
npm test --prefix scripts/pr-summary
```

For a dependency update outside the supported automatic scope, use the manual checksum-generation and review
commands above, then run `scripts/gradle.sh build` without generation enabled. Keep generation out of build and
test jobs: only verification against committed metadata may unblock them.

## Fakes and fixtures

- A fake of a device or a service is named `Fake…` and sits in the test package of the code it fakes, for example
  `FakeCastReceiver` or `FakeJellyfinServer`. It speaks the real protocol, so the production client runs unchanged.
  A fake that the app's tests share with the protocol tests sits in `protocols`' test fixtures
  (`protocols/src/testFixtures/java`), in its protocol's package. The app's tests and browser tests get the test
  fixtures through `testImplementation(testFixtures(project(":protocols")))`.
- Shared helpers live in `dev.andre.homecontrol.testsupport`: `FakeHttpServer` for any HTTP or HTTPS fake,
  `TestTls` for a self-signed server certificate (with `Request` and `Fixtures`, in `protocols`' test fixtures),
  `MutableClock`, `RecordingStateListener`, `EventStreamReader`, `RailHtml` to read a rendered rail, and
  `FakeLoginContext` for a browser's login where `LoginService` is a mock (with a real `LoginService`, wrap a
  `MockHttpServletRequest` in `RequestLoginContext`).
  A fake of a web API (Jellyfin, TMDB, Google, TheSportsDB, calendars, workflows) is a thin wrapper over
  `FakeHttpServer` that keeps the service's own vocabulary; a fake of a socket protocol (UPnP, Tizen, Cast, Android
  TV, mpv) stays protocol-specific. A test that needs a request to stay open until it has checked something holds
  the answer with `FakeHttpServer.hold` and a `CountDownLatch` (the calendar and TheSportsDB fakes wrap it); the
  request is recorded on arrival, so the test can wait for it.
- Recorded device and API responses live in `fixtures/<device or source>/` on the test classpath: the protocols'
  own (`cast`, `ics`, `sonos`, `ssdp`, `tizen`, `upnp`, `webos`) in `protocols/src/testFixtures/resources/`, the
  content sources' in `src/test/resources/`. Read one with `Fixtures.read("upnp/didl-track.xml")` or
  `Fixtures.bytes(…)`: a module's tests run in the module's directory, so a path relative to it finds only that
  module's files.
- Waiting for something asynchronous uses Awaitility (`await().atMost(...)`), never `Thread.sleep`. A fake may
  sleep to model a slow peer, with `@SuppressWarnings("java:S2925")` and a one-line reason.
- Device sessions take their waits as a `*Timings` record of `Duration`s (`CastTimings`, `TizenTimings`, …), built
  from the `*Properties` in production. Tests pass 50–500 ms for the intervals and backoffs they wait through, and keep
  whole seconds for timeouts that must not fire; a "nothing happens" check (`await().during(...)`) covers at least five
  of the intervals it watches. Tests assert outcomes (the exception, its message, the order of events), never an upper
  bound on elapsed time unless that time is the behaviour under test (`EventStreamShutdownEndToEndTest`).
- A device session composes the toolkit in `adapters.support`: a `SessionLoop` (one virtual thread), a
  `StatePublisher`, a `ConnectionSlot` and, for Cast, Android TV and webOS, a `Reconnector`. The publisher drops a
  state that repeats the last one in everything the UI shows, so a test counts connection attempts at the fake
  (`connections()`), or counts CONNECTING states only where each follows a DISCONNECTED one.
- `close()` interrupts the session's loop, and on a virtual thread that aborts a blocking socket call. A test that
  needs a step to finish after `close()`, as a step that had already finished would, holds it in a step that ignores
  the interrupt (`UpnpSessionTest.awaitIgnoringInterrupts`, `SonosSessionTest.HeldClock`).
- A session's commands fail through `DeviceCalls`, so their messages read the same everywhere: "… did not answer in
  time when asked to …", "… refused to …: …", "… could not be reached to …" and "… is not connected". A test asserts
  one of these, or only the reason the device gave.
- A test never shares a file or a fixed port with another test JVM: files go in `@TempDir` or
  `Files.createTempDirectory`, and servers bind port 0.
- On a plain `ws://` connection, the JDK's WebSocket client loses an orderly close (FIN) that arrives while its
  listener still handles a frame; over TLS the close is reported. A test that drops a plain WebSocket connection
  either lets the session's liveness check notice it (`WebOsSessionTest.startedWithLivenessCheck`) or drops with a
  reset (`FakeWebSocketServer.resetAll()`).

## Test configuration

Spring tests load the production `src/main/resources/application.yaml` with `src/test/resources/config/application.yaml`
on top of it. The test file holds only what a test run must change: no LAN discovery, no real upstream APIs, short
timeouts, no scheduled rail refresh, and no secrets from the developer's environment. Add a key there only with a
comment saying why a test run needs it.

The unit tests run in up to four JVMs at once. The test file gives each its own data directory,
`build/test-data/<worker>`, and Bluetooth runtime directory, `<java.io.tmpdir>/home-control-bluetooth-<worker>`, from
the system property `org.gradle.test.worker`, which Gradle sets in every test JVM (outside Gradle it is 0).

## Browser tests

`./gradlew build` (or `scripts/gradle.sh build` without a local JDK) never resolves Playwright or
needs a browser installed — the Playwright-driven dashboard
tests (play sheet, device switching, rail failure, login gating, touchpad) live in their own
`e2e` source set and Gradle task, outside `check`/`build`.

To run them:

```bash
./gradlew installPlaywrightBrowsers   # once; needs root or passwordless sudo, Ubuntu 22.04-26.04
./gradlew e2eTest                     # Chromium, Firefox and WebKit; -Pe2eBrowsers=chromium to narrow
```

Without a local JDK, `scripts/e2e.sh` builds a `gradle:jdk25`-based image with all three browsers
already installed and runs `e2eTest` inside it (a tracked copy of the same
`.superpowers/e2e.sh` this project's agents use). Playwright traces from any run land in
`build/e2e-artifacts/<test>-<browser>.zip`; open one at https://trace.playwright.dev.

The pages send a Content-Security-Policy that allows scripts only from this server. Every `BrowserSession` records what
the policy blocks, and a test whose pages broke it fails when its session closes, listing each violation. A page's
code belongs in its ES module under `static/js`; `InlineCodeTest` also refuses inline scripts, `on…=` handler
attributes and `hx-on` in the templates, without a browser.
