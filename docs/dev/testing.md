# Testing

How the test suites are built and how to run them.

## Test layers

| Layer | What it is | Where |
| --- | --- | --- |
| Unit tests | Plain JUnit 5 with AssertJ, Mockito and Awaitility. Most device and source tests drive the real client against an in-process fake that speaks the real protocol over a socket. | `src/test/java`, next to the code |
| Web slices | `@WebMvcTest` of one controller with its collaborators mocked. | `src/test/java/.../web`, and next to each content source's own controllers |
| End to end | `@SpringBootTest` with the whole application and fake devices or services, over MockMvc or real HTTP. | classes named `*EndToEndTest` |
| Browser tests | Playwright driving the dashboard in Chromium, Firefox and WebKit. | `src/e2e/java`, see [Browser tests](#browser-tests) |
| Architecture | ArchUnit package rules. | `ArchitectureTest`, see [Architecture](architecture.md#package-rules) |

When this page was written, the unit, slice, end-to-end and architecture tests numbered 2,689.

## Running tests

- Everything: `scripts/gradle.sh build`. It runs `./gradlew` in the `gradle:jdk25` Docker image, for machines without
  a JDK 25; with one, `./gradlew build` does the same.
- One class: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.core.ActionTest'`.
- A failure's details: `grep -A20 '<failure' build/test-results/test/*.xml`.
- The unit tests run in up to four JVMs at once, and Gradle reuses the results of tasks whose inputs did not change,
  so a test that passed and was not touched is not run again.
- Every test and lifecycle method has a 60 s timeout (`src/test/resources/junit-platform.properties`), off while a
  debugger is attached. A test that needs longer declares `@Timeout` on the method, or on the class for its test
  methods; a long `@BeforeAll` or `@BeforeEach` needs its own.

## Fakes and fixtures

- A fake of a device or a service is named `Fake…` and sits in the test package of the code it fakes, for example
  `FakeCastReceiver` or `FakeJellyfinServer`. It speaks the real protocol, so the production client runs unchanged.
- Shared helpers live in `dev.andre.homecontrol.testsupport`: `FakeHttpServer` for any HTTP or HTTPS fake,
  `TestTls` for a self-signed server certificate, `MutableClock`, `RecordingStateListener` and `EventStreamReader`.
  A fake of a web API (Jellyfin, TMDB, Google, TheSportsDB, calendars, workflows) is a thin wrapper over
  `FakeHttpServer` that keeps the service's own vocabulary; a fake of a socket protocol (UPnP, Tizen, Cast, Android
  TV, mpv) stays protocol-specific.
- Recorded device and API responses live in `src/test/resources/fixtures/<device or source>/`, for example
  `fixtures/cast/` or `fixtures/jellyfin/`.
- Waiting for something asynchronous uses Awaitility (`await().atMost(...)`), never `Thread.sleep`. A fake may
  sleep to model a slow peer, with `@SuppressWarnings("java:S2925")` and a one-line reason.
- Device sessions take their waits as a `*Timings` record of `Duration`s (`CastTimings`, `TizenTimings`, …), built
  from the `*Properties` in production. Tests pass 50–500 ms for the intervals and backoffs they wait through, and keep
  whole seconds for timeouts that must not fire; a "nothing happens" check (`await().during(...)`) covers at least five
  of the intervals it watches. Tests assert outcomes (the exception, its message, the order of events), never an upper
  bound on elapsed time unless that time is the behaviour under test (`EventStreamShutdownEndToEndTest`).
- A test never shares a file or a fixed port with another test JVM: files go in `@TempDir` or
  `Files.createTempDirectory`, and servers bind port 0.
- The JDK's WebSocket client loses an orderly close (FIN) that arrives while its listener still handles a frame. A
  test that drops a WebSocket connection either lets the session's liveness check notice it
  (`WebOsSessionTest.startedWithLivenessCheck`) or drops with a reset (`FakeWebSocketServer.resetAll()`).

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
