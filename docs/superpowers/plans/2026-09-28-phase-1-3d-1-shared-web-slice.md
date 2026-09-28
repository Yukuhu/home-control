# Phase 1.3d-1 Shared Web Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The 28 `@WebMvcTest` classes stop starting about 26 Spring contexts. All but one extend a single `WebSliceTest`, which starts one context per test JVM.

**Architecture:**
- **One base class.** `testsupport.WebSliceTest` is a `@WebMvcTest` over every controller and controller advice, with Bluetooth switched on. Every collaborator any of them needs is declared there once, as a protected `@MockitoBean`. The real beans some tests need (three properties records and a prompt-pairing stub) come from one `@Import` on the base.
- **Test classes add nothing.** A test class extends the base and keeps its own stubbing. It declares no `@MockitoBean`, `@Import` or nested `@TestConfiguration`, because any of those gives it a context of its own. An ArchUnit rule keeps it that way.
- **Safe defaults.** Mockito resets the mocks after each test. A `@BeforeEach` in the base then stubs the handful of calls that every `/setup` render makes and that would otherwise return `null`.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.test.context.bean.override.mockito.MockitoBean`), JUnit 6, Mockito (BDD), ArchUnit, Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.3d Shared Spring contexts", part "Web slice, 1 context", and "Progress measures".

## Global Constraints

- **Start condition:** #133 (Phase 1.3c) is merged. Branch `test/shared-web-slice` from `origin/main` (`78f3a30`).
- **Delivery (the user's decision, 2026-09-28):** 1.3d ships as three pull requests:
  - 1.3d-1, this plan: the web slice;
  - 1.3d-2: the module-switch tests, with one "modules off" context plus context runners;
  - 1.3d-3: the shared full-application context, with its resets.
- **Production code does not change in this plan.** `git diff origin/main -- src/main` stays empty.
- **Test count:** equal or higher. A moved test names its replacement in the commit message. This plan adds three tests, so 2,749 becomes 2,752.
- **Build and test only through `scripts/gradle.sh`.** There is no local JDK, and `scripts/gradle.sh build` is green at the end.
- **Commits:**
  - Conventional Commits, ending with a `Co-Authored-By:` trailer naming the model that wrote them.
  - Stage only the files you changed: `git add <paths>`, never `git add -A`.

## What the inventory found

Measured on `78f3a30` with one full run, four JVMs and 4 CPUs:
- **Context starts:** 71 across the four JVMs (13, 13, 31 and 14), taking 138.5 s in total.
- **Web slices:** 26 of the 71 starts, one for each `@WebMvcTest` class, except that three `ContentPlay*` classes may already share one.
- **Other times:** summed class time 655 s, wall time 3 min 32 s.
- **How to count:** every Spring Boot context start logs `Started <TestClass> in <seconds> seconds`, and the log line carries the PID of its test JVM. `ApplicationContextRunner` contexts do not log it.

The 28 classes, the collaborators each mocks, and the controllers:
- **Controllers and advices:** 33 classes under `src/main` carry `@Controller`, `@RestController` or `@ControllerAdvice`, and all of them are public. Only Bluetooth's two classes need a property: `home-control.bluetooth.enabled=true`. Every other module is on by default.
- **Four advices call a collaborator that Mockito would answer with `null`** on every `/setup` render:
  - `SportsSetupAdvice`: `SportsSettingsService.current()` and `SportsTimeZones.effective()`;
  - `SourcesSetupAdvice`: `SourcePreferencesService.current()`;
  - `YouTubeSetupAdvice`: `YouTubeSetupService.settings()`;
  - `BluetoothSetupAdvice`: `BluetoothPairingService.lastScan()`.

  Safe values exist for each: `SportsSettings.empty()`, `SourcePreferences.defaults(...)`, `YouTubeSettings.EMPTY` and `BluetoothScan.NONE`.
- **Test-only beans that keep a class on its own context:**
  - four nested `@TestConfiguration`s: `PinnedSetupControllerTest`, `SportsSetupControllerTest`, `TheSportsDbSetupControllerTest` and `YouTubeThumbnailControllerTest`;
  - two `@Import`s: `BluetoothSetupControllerTest` and `PromptPairingSetupTest`.

  A nested `@TestConfiguration` is detected only on the concrete test class, so all six move into the base.
- **Tests that rely on a bean being absent:**
  - `ContentPlayPreviewTest.noPinWithoutThePinnedModule` relies on `PinnedLinks` being absent, which `ContentPlayController` checks with `pinnedLinks.getIfAvailable() == null`.
  - `BluetoothSetupOffTest` relies on Bluetooth being off.

## Plan decisions

1. **The base lives in `dev.andre.homecontrol.testsupport`,** next to the other shared test helpers. Every type it names is public.
2. **The collaborators that advices take only through `ObjectProvider`, and that no test stubs, are not mocked:** `JellyfinSessions`, `QuotaLedger` and `YouTubePlaylists`. When they are absent, `getIfAvailable()` returns `null`, and the advices handle that by design. Mocking them would only add more `null` answers.
3. **Mocks get one name each in the base,** unique by type. Classes that used another name rename their references (Tasks 2–4 give each class's table):
   - `PairingService` (Android TV) → `pairing`;
   - the prompt-pairing stub → `promptPairing`;
   - `StoredRailPreferences` → `railPreferences`, because `rails` is the `RailCache`.
4. **The properties records become real beans in the base,** with the values the tests use today:
   - `PinnedProperties(true, 200)`;
   - `SportsSetupControllerTest`'s `SportsProperties`, which `TheSportsDbSetupControllerTest` builds identically;
   - `YouTubeThumbnailControllerTest`'s `YouTubeProperties`.

   `BluetoothProperties` stays a mock, as `BluetoothSetupControllerTest` stubs it.
5. **The prompt-pairing stub becomes `testsupport.StubPromptPairing`,** a bean of the base, with accessors in place of its fields. The base clears it before every test. Every `/setup` render now offers its "LG webOS TV" option. No test asserts the option's absence.
6. **`ContentPlayPreviewTest.noPinWithoutThePinnedModule` moves** to `web.ContentPlayWithoutPinsTest`. That class uses a standalone MockMvc over a `ContentPlayController` built with an empty `PinnedLinks` provider, so it starts no context. It gets its own RED: the test must fail when the provider holds a `PinnedLinks`.
7. **`BluetoothSetupOffTest` keeps its own `@WebMvcTest` in this pull request.** It is a module-off test, and 1.3d-2 moves it into the "modules off" context. The ArchUnit rule names it as the one exception until then.
8. **Security filters stay out of the slice, as today.** `SecurityConfiguration` declares them in a plain `@Configuration`, which `@WebMvcTest` does not load. The slice has `LoginController` now, so `LoginRateLimiter` joins the mocks.

## Review Focus

- **A test class that adds a bean, a property or an import silently gets its own context again.** Test: `WebSliceRulesTest` (Task 5). No class but `WebSliceTest` (and, until 1.3d-2, `BluetoothSetupOffTest`) carries `@WebMvcTest`, and no subclass declares a `@MockitoBean`, an `@Import` or a nested `@TestConfiguration`. Each rule is shown failing on a temporary violation.
- **Now that every advice is in the slice, a `/setup` assertion may match text from another module's section.** A `contains(...)` could pass for the wrong reason, and a `doesNotContain(...)` could start failing. Test: in Tasks 3 and 4, every `/setup` body assertion is checked against the fragment it names, and a `contains` whose text appears in more than one section is narrowed, for example to an element id.
- **The one test that relies on `PinnedLinks` being absent must still test that.** Test: `ContentPlayWithoutPinsTest.noPinWithoutThePinnedModule` is shown failing with a present `PinnedLinks` (Task 2).
- **Stubs and stub state must not leak between tests or classes.** `@MockitoBean` resets after each test, and `StubPromptPairing` is cleared in the base's `@BeforeEach`. Test: `PromptPairingSetupTest` runs in the same context after `SetupControllerTest` (Task 3), and the whole slice runs twice with `--rerun`.
- **There is one context per test JVM, not one per class.** Test: Task 5 counts the `Started WebSliceTest…` log lines per PID after a full run. There is at most one per JVM, from whichever slice class ran first.

---

### Task 1: `WebSliceTest`, its self-test, and the first two classes

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/WebSliceTest.java`
- Create: `src/test/java/dev/andre/homecontrol/testsupport/StubPromptPairing.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/SharedWebSliceTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/web/IconControllerTest.java`, `src/test/java/dev/andre/homecontrol/web/PwaControllerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public abstract class WebSliceTest`, with these `@MockitoBean protected` fields:
    - devices and pairing: `DeviceManager devices`, `PairingService pairing`;
    - Bluetooth: `BluetoothPairingService bluetoothPairing`, `BluetoothHostChecks bluetoothChecks`, `BluetoothProperties bluetoothProperties`;
    - Jellyfin: `JellyfinClient jellyfinClient`, `JellyfinSetupService jellyfinSetup`;
    - pins: `PinnedShortcuts pins`, `PinnedLinks pinnedLinks`;
    - sports: `SportsCalendars sportsCalendars`, `SportsSettingsService sportsSettings`, `SportsTimeZones sportsZones`, `CalendarSchedule calendarSchedule`, `SportsCompetitions sportsCompetitions`, `TheSportsDbSchedule theSportsDbSchedule`;
    - TMDB: `TmdbSetupService tmdbSetup`;
    - workflows: `WorkflowStore workflowStore`, `WorkflowTestService workflowTests`;
    - login: `LoginService login`, `LoginRateLimiter loginRateLimiter`;
    - YouTube: `YouTubeSetupService youTubeSetup`, `YouTubeHttp youTubeHttp`;
    - content and playback: `ContentSources sources`, `RailCache rails`, `SearchService search`, `PlaybackService playback`, `DeepLinkTestService deepLinkTests`;
    - the rest: `DeviceStateBroadcaster broadcaster`, `SourcePreferencesService sourcePreferences`, `StoredRailPreferences railPreferences`.
  - `@Autowired protected StubPromptPairing promptPairing`.
  - `public final class StubPromptPairing implements PromptPairing`, with `willAnswer(PromptPairingResult)`, `host()`, `name()` and a package-private `reset()`.

- [ ] **Step 1: Write the self-test**

`src/test/java/dev/andre/homecontrol/testsupport/SharedWebSliceTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupAdvice;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSetupController;
import dev.andre.homecontrol.security.LoginModelAdvice;
import dev.andre.homecontrol.sources.jellyfin.JellyfinImageController;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupAdvice;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupController;
import dev.andre.homecontrol.sources.pinned.PinUpgradeController;
import dev.andre.homecontrol.sources.pinned.PinnedSetupAdvice;
import dev.andre.homecontrol.sources.pinned.PinnedSetupController;
import dev.andre.homecontrol.sources.sports.SportsSetupAdvice;
import dev.andre.homecontrol.sources.sports.SportsSetupController;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSetupController;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupAdvice;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupController;
import dev.andre.homecontrol.sources.workflows.WorkflowSetupAdvice;
import dev.andre.homecontrol.sources.workflows.WorkflowSetupController;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupAdvice;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupController;
import dev.andre.homecontrol.sources.youtube.YouTubeThumbnailController;
import dev.andre.homecontrol.web.ContentController;
import dev.andre.homecontrol.web.ContentPlayController;
import dev.andre.homecontrol.web.DashboardController;
import dev.andre.homecontrol.web.DeepLinkTestController;
import dev.andre.homecontrol.web.DeviceController;
import dev.andre.homecontrol.web.IconController;
import dev.andre.homecontrol.web.LoginController;
import dev.andre.homecontrol.web.PwaController;
import dev.andre.homecontrol.web.RailController;
import dev.andre.homecontrol.web.SearchController;
import dev.andre.homecontrol.web.SetupController;
import dev.andre.homecontrol.web.SourcesSetupAdvice;
import dev.andre.homecontrol.web.SourcesSetupController;
import dev.andre.homecontrol.web.StateController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared slice holds every controller and controller advice of the application, once. */
class SharedWebSliceTest extends WebSliceTest {

    @Autowired
    ApplicationContext context;

    @Test
    void holdsEveryControllerAndControllerAdvice() {
        List<Class<?>> webLayer = List.of(BluetoothSetupAdvice.class, BluetoothSetupController.class,
                LoginModelAdvice.class, JellyfinImageController.class, JellyfinSetupAdvice.class,
                JellyfinSetupController.class, PinUpgradeController.class, PinnedSetupAdvice.class,
                PinnedSetupController.class, SportsSetupAdvice.class, SportsSetupController.class,
                TheSportsDbSetupController.class, TmdbSetupAdvice.class, TmdbSetupController.class,
                WorkflowSetupAdvice.class, WorkflowSetupController.class, YouTubeSetupAdvice.class,
                YouTubeSetupController.class, YouTubeThumbnailController.class, ContentController.class,
                ContentPlayController.class, DashboardController.class, DeepLinkTestController.class,
                DeviceController.class, IconController.class, LoginController.class, PwaController.class,
                RailController.class, SearchController.class, SetupController.class, SourcesSetupAdvice.class,
                SourcesSetupController.class, StateController.class);

        assertThat(webLayer).allSatisfy(type ->
                assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).hasSize(1));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.SharedWebSliceTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class WebSliceTest`.

- [ ] **Step 3: The prompt-pairing stub**

`src/test/java/dev/andre/homecontrol/testsupport/StubPromptPairing.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.PromptPairing;
import dev.andre.homecontrol.core.PromptPairingResult;

/** The web slice's prompt-pairing adapter: answers what a test sets and records the last request. */
public final class StubPromptPairing implements PromptPairing {

    private volatile PromptPairingResult next;
    private volatile String host;
    private volatile String name;

    public void willAnswer(PromptPairingResult result) {
        next = result;
    }

    public String host() {
        return host;
    }

    public String name() {
        return name;
    }

    void reset() {
        next = null;
        host = null;
        name = null;
    }

    @Override
    public String adapterId() {
        return "webos";
    }

    @Override
    public String displayName() {
        return "LG webOS TV";
    }

    @Override
    public String instructions() {
        return "Accept the request on the TV.";
    }

    @Override
    public PromptPairingResult pair(String host, String name) {
        this.host = host;
        this.name = name;
        return next;
    }
}
```

- [ ] **Step 4: The base class**

`src/test/java/dev/andre/homecontrol/testsupport/WebSliceTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.PairingService;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothHostChecks;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothPairingService;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothProperties;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothScan;
import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.SearchService;
import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.content.StoredRailPreferences;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.content.SourcePreferences;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.DeepLinkTestService;
import dev.andre.homecontrol.playback.PlaybackService;
import dev.andre.homecontrol.security.LoginRateLimiter;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.jellyfin.JellyfinSetupService;
import dev.andre.homecontrol.sources.pinned.PinnedProperties;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsProperties;
import dev.andre.homecontrol.sources.sports.SportsSettings;
import dev.andre.homecontrol.sources.sports.SportsSettingsService;
import dev.andre.homecontrol.sources.sports.SportsTimeZones;
import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
import dev.andre.homecontrol.sources.sports.calendar.SportsCalendars;
import dev.andre.homecontrol.sources.sports.thesportsdb.SportsCompetitions;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSchedule;
import dev.andre.homecontrol.sources.tmdb.TmdbSetupService;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.workflows.WorkflowTestService;
import dev.andre.homecontrol.sources.youtube.YouTubeHttp;
import dev.andre.homecontrol.sources.youtube.YouTubeProperties;
import dev.andre.homecontrol.sources.youtube.YouTubeSettings;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import dev.andre.homecontrol.web.DeviceStateBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;

import static org.mockito.BDDMockito.given;

/**
 * The one web-layer test context: {@code @WebMvcTest} over every controller and controller advice, with every
 * collaborator they need declared here once. A test class extends it and declares no beans of its own (no
 * {@code @MockitoBean}, {@code @Import} or nested {@code @TestConfiguration}): anything that differs between classes
 * gives them separate contexts, which {@code WebSliceRulesTest} forbids. Mockito resets the mocks after each test;
 * {@link #stubSafeDefaults()} then answers the calls every {@code /setup} render makes, which Mockito would answer
 * with {@code null}.
 */
@WebMvcTest(properties = "home-control.bluetooth.enabled=true")
@Import(WebSliceTest.SliceBeans.class)
public abstract class WebSliceTest {

    @MockitoBean
    protected DeviceManager devices;
    @MockitoBean
    protected PairingService pairing;
    @MockitoBean
    protected BluetoothPairingService bluetoothPairing;
    @MockitoBean
    protected BluetoothHostChecks bluetoothChecks;
    @MockitoBean
    protected BluetoothProperties bluetoothProperties;
    @MockitoBean
    protected JellyfinClient jellyfinClient;
    @MockitoBean
    protected JellyfinSetupService jellyfinSetup;
    @MockitoBean
    protected PinnedShortcuts pins;
    @MockitoBean
    protected PinnedLinks pinnedLinks;
    @MockitoBean
    protected SportsCalendars sportsCalendars;
    @MockitoBean
    protected SportsSettingsService sportsSettings;
    @MockitoBean
    protected SportsTimeZones sportsZones;
    @MockitoBean
    protected CalendarSchedule calendarSchedule;
    @MockitoBean
    protected SportsCompetitions sportsCompetitions;
    @MockitoBean
    protected TheSportsDbSchedule theSportsDbSchedule;
    @MockitoBean
    protected TmdbSetupService tmdbSetup;
    @MockitoBean
    protected WorkflowStore workflowStore;
    @MockitoBean
    protected WorkflowTestService workflowTests;
    @MockitoBean
    protected LoginService login;
    @MockitoBean
    protected LoginRateLimiter loginRateLimiter;
    @MockitoBean
    protected YouTubeSetupService youTubeSetup;
    @MockitoBean
    protected YouTubeHttp youTubeHttp;
    @MockitoBean
    protected ContentSources sources;
    @MockitoBean
    protected RailCache rails;
    @MockitoBean
    protected SearchService search;
    @MockitoBean
    protected PlaybackService playback;
    @MockitoBean
    protected DeepLinkTestService deepLinkTests;
    @MockitoBean
    protected DeviceStateBroadcaster broadcaster;
    @MockitoBean
    protected SourcePreferencesService sourcePreferences;
    @MockitoBean
    protected StoredRailPreferences railPreferences;

    @Autowired
    protected StubPromptPairing promptPairing;

    /** Runs before each subclass's own {@code @BeforeEach}, whose stubs then win. */
    @BeforeEach
    protected void stubSafeDefaults() {
        given(bluetoothPairing.lastScan()).willReturn(BluetoothScan.NONE);
        given(sportsSettings.current()).willReturn(SportsSettings.empty());
        given(sportsZones.effective()).willReturn(ZoneId.of("Europe/Berlin"));
        given(sportsZones.chosen()).willReturn(true);
        given(sourcePreferences.current()).willReturn(SourcePreferences.defaults("de-DE", "DE"));
        given(youTubeSetup.settings()).willReturn(YouTubeSettings.EMPTY);
        promptPairing.reset();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SliceBeans {

        @Bean
        PinnedProperties pinnedProperties() {
            return new PinnedProperties(true, 200);
        }

        @Bean
        SportsProperties sportsProperties() {
            return new SportsProperties(true, "", 30, 10, 10, Duration.ofMinutes(120),
                    new SportsProperties.Calendar(Duration.ofHours(6), 5, 15, 5242880, 3, false),
                    new SportsProperties.TheSportsDb(true, URI.create("https://www.thesportsdb.com/api/v1/json"),
                            "123", Duration.ofHours(24), 5, 15, null));
        }

        @Bean
        YouTubeProperties youTubeProperties() {
            return new YouTubeProperties(true, URI.create("http://oauth.test"), URI.create("http://api.test"),
                    URI.create("http://lounge.test"), URI.create("http://thumbs.test"), 2, 5, 10000, 20, 30, 30, 5,
                    Duration.ofHours(24), 20, Duration.ofMinutes(60), Duration.ofMinutes(15), Duration.ofHours(6));
        }

        @Bean
        StubPromptPairing promptPairing() {
            return new StubPromptPairing();
        }
    }
}
```

- [ ] **Step 5: Run the self-test**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.SharedWebSliceTest' --rerun`

Expected: PASS.
- **If the context does not start,** read the cause in the result file.
  - A missing bean of type X: add `@MockitoBean protected X <name>;` to the base, with a unique name.
  - A missing configuration value: find where production sets it, and record the fix in your report.
- **If an advice or controller is missing from the slice** (`hasSize(1)` fails for it), add it to `@Import` on the base, beside `SliceBeans.class`. Record which ones you added.

- [ ] **Step 6: Move `IconControllerTest` and `PwaControllerTest` onto the base**

Both declare no mocks. In each:
1. Replace `@WebMvcTest(IconController.class)` or `@WebMvcTest(PwaController.class)` with nothing.
2. Make the class `extends WebSliceTest`.
3. Add `import dev.andre.homecontrol.testsupport.WebSliceTest;`, and remove the now unused `WebMvcTest` import.

Before the change, run both classes with `--rerun` and note their test counts from the result files.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.IconControllerTest' --tests 'dev.andre.homecontrol.web.PwaControllerTest' --tests 'dev.andre.homecontrol.testsupport.SharedWebSliceTest' --rerun`

Expected: PASS, with the same counts as before plus the self-test's one.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/WebSliceTest.java src/test/java/dev/andre/homecontrol/testsupport/StubPromptPairing.java src/test/java/dev/andre/homecontrol/testsupport/SharedWebSliceTest.java src/test/java/dev/andre/homecontrol/web/IconControllerTest.java src/test/java/dev/andre/homecontrol/web/PwaControllerTest.java
git commit -m "test: add one shared web slice and move the icon and PWA tests onto it"
```

End the message with your `Co-Authored-By:` trailer.

### Task 2: The content and device classes

**Files:**
- Modify (all under `src/test/java/dev/andre/homecontrol/web/`): `ContentControllerTest.java`, `ContentPlayControllerTest.java`, `ContentPlayErrorBodiesTest.java`, `ContentPlayPinOfferTest.java`, `ContentPlayPreviewTest.java`, `DashboardPageTest.java`, `DeepLinkTestControllerTest.java`, `DeviceControllerTest.java`, `RailControllerTest.java`, `SearchControllerTest.java`, `StateControllerTest.java`
- Create: `src/test/java/dev/andre/homecontrol/web/ContentPlayWithoutPinsTest.java`

**Interfaces:**
- Consumes: `WebSliceTest` and its field names (Task 1).
- Produces: nothing.

**The recipe for each class** (the same in Tasks 3 and 4):
1. Before the change, run the class with `--rerun` and note its test count from the result file.
2. Remove its `@WebMvcTest(...)` annotation, and make it `extends WebSliceTest`.
3. Remove every `@MockitoBean` field it declares.
4. Rename its references to those fields to the base's names, using the class's rename table. A table entry "same" means the name already matches.
   - Rename only whole words, and only the removed fields' uses.
   - A local variable or parameter with the same name keeps its name.
   - Read the diff before running.
5. Remove the imports that are no longer used, and add `import dev.andre.homecontrol.testsupport.WebSliceTest;`.
6. Run the class with `--rerun`. The test count must equal the one from step 1.

- [ ] **Step 1: Move the ten classes that only lose their mocks**

| Class | Its fields → base names |
| --- | --- |
| `ContentControllerTest` | `sources`, `rails` same; `searchService` → `search` |
| `ContentPlayControllerTest` | `devices`, `sources`, `playback` same |
| `ContentPlayErrorBodiesTest` | `devices`, `sources`, `playback` same |
| `ContentPlayPinOfferTest` | `devices`, `sources`, `playback`, `pinnedLinks` same |
| `DashboardPageTest` | `devices`, `rails`, `sources` same |
| `DeepLinkTestControllerTest` | `devices` same; `tests` → `deepLinkTests` |
| `DeviceControllerTest` | `devices`, `playback` same |
| `RailControllerTest` | `rails`, `sources` same |
| `SearchControllerTest` | `search` same |
| `StateControllerTest` | `devices`, `broadcaster`, `rails` same |

Apply the recipe to each.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ContentControllerTest' --tests 'dev.andre.homecontrol.web.ContentPlayControllerTest' --tests 'dev.andre.homecontrol.web.ContentPlayErrorBodiesTest' --tests 'dev.andre.homecontrol.web.ContentPlayPinOfferTest' --tests 'dev.andre.homecontrol.web.DashboardPageTest' --tests 'dev.andre.homecontrol.web.DeepLinkTestControllerTest' --tests 'dev.andre.homecontrol.web.DeviceControllerTest' --tests 'dev.andre.homecontrol.web.RailControllerTest' --tests 'dev.andre.homecontrol.web.SearchControllerTest' --tests 'dev.andre.homecontrol.web.StateControllerTest' --rerun`

Expected: PASS, with each class's count unchanged.

- [ ] **Step 2: Write the test that proves the pin offer needs the pinned module**

`src/test/java/dev/andre/homecontrol/web/ContentPlayWithoutPinsTest.java`. It holds the test that was `ContentPlayPreviewTest.noPinWithoutThePinnedModule`. It uses a standalone MockMvc over a controller built directly, so `PinnedLinks` is absent without a context of its own.

```java
package dev.andre.homecontrol.web;

import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.core.content.ContentSource;
import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.core.content.PinnedLinks;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.core.playback.PlayableRef;
import dev.andre.homecontrol.core.playback.Route;
import dev.andre.homecontrol.core.playback.ServiceLinks;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.playback.PlaybackPreview;
import dev.andre.homecontrol.playback.PlaybackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Without the pinned module there is no {@code PinnedLinks} bean, and the route preview offers no pin. A standalone
 * MockMvc, because the shared web slice has a {@code PinnedLinks} mock.
 */
class ContentPlayWithoutPinsTest {

    private static final String ITEM_ID = "3f2a9c1e7b6d4e5f8a9b0c1d2e3f4a5b";

    private final DeviceManager devices = mock(DeviceManager.class);
    private final ContentSources sources = mock(ContentSources.class);
    private final PlaybackService playback = mock(PlaybackService.class);
    private final ContentSource tmdb = mock(ContentSource.class);
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new ContentPlayController(devices, sources, playback, beans.getBeanProvider(PinnedLinks.class))).build();

    @Test
    void noPinWithoutThePinnedModule() throws Exception {
        Device living = new Device("living", "Living Room", DeviceKind.ANDROID_TV, "10.0.0.5",
                Map.of("androidtv", Map.of()), Instant.now());
        PlayableRef.AppLink netflixHome = new PlayableRef.AppLink(ServiceLinks.appHome("netflix").orElseThrow(), "netflix");
        ContentItem appHomeItem = new ContentItem(ITEM_ID, "tmdb", ContentKind.VIDEO, "Stranger Things", null, null,
                List.of(netflixHome));
        given(devices.device("living")).willReturn(Optional.of(living));
        given(sources.find("tmdb")).willReturn(Optional.of(tmdb));
        given(tmdb.item(ITEM_ID)).willReturn(Optional.of(appHomeItem));
        given(playback.preview(appHomeItem, "living")).willReturn(new PlaybackPreview(living,
                List.of(new Route.OpenAppLink(netflixHome.uri(), netflixHome.service())), null));

        mockMvc.perform(get("/devices/living/route-preview").param("source", "tmdb").param("item", ITEM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pin").value(nullValue()));
    }
}
```

Adjust the imports to the types' real packages if the compiler says otherwise. `ServiceLinks` was named `dev.andre.homecontrol.core.playback.ServiceLinks` in the old test. Use the same types and values as `ContentPlayPreviewTest.noPinWithoutThePinnedModule`.

- [ ] **Step 3: Show that it tests the absence**

Temporarily, before the `mockMvc` field, register a `PinnedLinks` mock in the factory: add `{ beans.addBean("pinnedLinks", mock(PinnedLinks.class)); }` as an instance initializer. Put it before the `mockMvc` field, because field initializers run in order.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ContentPlayWithoutPinsTest' --rerun`

Expected: FAIL at `jsonPath("$.pin")`, because a pin offer appears. Remove the initializer, and run again. Expected: PASS.

If the offer does not appear with a bare mock, stub what `PinOffers.offer` needs, as `ContentPlayPinOfferTest` does, until the RED shows. Then remove the whole temporary block.

- [ ] **Step 4: Move `ContentPlayPreviewTest` onto the base without that test**

Apply the recipe (`devices`, `sources`, `playback` same). Delete its `noPinWithoutThePinnedModule` method.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ContentPlayPreviewTest' --tests 'dev.andre.homecontrol.web.ContentPlayWithoutPinsTest' --rerun`

Expected: PASS. `ContentPlayPreviewTest` has one test fewer than before, and `ContentPlayWithoutPinsTest` has one.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/ContentControllerTest.java src/test/java/dev/andre/homecontrol/web/ContentPlayControllerTest.java src/test/java/dev/andre/homecontrol/web/ContentPlayErrorBodiesTest.java src/test/java/dev/andre/homecontrol/web/ContentPlayPinOfferTest.java src/test/java/dev/andre/homecontrol/web/ContentPlayPreviewTest.java src/test/java/dev/andre/homecontrol/web/ContentPlayWithoutPinsTest.java src/test/java/dev/andre/homecontrol/web/DashboardPageTest.java src/test/java/dev/andre/homecontrol/web/DeepLinkTestControllerTest.java src/test/java/dev/andre/homecontrol/web/DeviceControllerTest.java src/test/java/dev/andre/homecontrol/web/RailControllerTest.java src/test/java/dev/andre/homecontrol/web/SearchControllerTest.java src/test/java/dev/andre/homecontrol/web/StateControllerTest.java
git commit -m "test: move the content and device web tests onto the shared slice

ContentPlayPreviewTest.noPinWithoutThePinnedModule moves to
ContentPlayWithoutPinsTest, a standalone MockMvc without PinnedLinks."
```

End the message with your `Co-Authored-By:` trailer.

### Task 3: The setup page classes

**Files:**
- Modify (all under `src/test/java/dev/andre/homecontrol/web/`): `SetupControllerTest.java`, `PromptPairingSetupTest.java`, `SourcesSetupControllerTest.java`, `BluetoothSetupControllerTest.java`

**Interfaces:**
- Consumes: `WebSliceTest`, its field names and `StubPromptPairing` (Task 1).
- Produces: nothing.

Use Task 2's recipe. These classes render `/setup`, which now carries every module's section.

- [ ] **Step 1: Move the four classes**

| Class | Its fields → base names | Also |
| --- | --- | --- |
| `SetupControllerTest` | `pairing`, `devices` same | – |
| `PromptPairingSetupTest` | the stub field `pairing` (type `StubPairing`) → `promptPairing`, **renamed first**; then `androidTvPairing` → `pairing`; `devices` same | Delete the nested `StubPairing` and `StubPairingConfiguration` classes, the `@Import`, and the `@Autowired StubPairing` field. Replace `promptPairing.next = X` with `promptPairing.willAnswer(X)`, `promptPairing.host` with `promptPairing.host()`, and `promptPairing.name` with `promptPairing.name()`. |
| `SourcesSetupControllerTest` | `pairing`, `devices`, `sources` same; `prefs` → `sourcePreferences`; `rails` (type `StoredRailPreferences`) → `railPreferences` | Its `@Autowired RequestMappingHandlerMapping` stays. |
| `BluetoothSetupControllerTest` | the field `pairing` (type `BluetoothPairingService`) → `bluetoothPairing`, **renamed first**; then `androidPairing` → `pairing`; `checks` → `bluetoothChecks`; `properties` → `bluetoothProperties`; `devices` same | Remove `properties = "home-control.bluetooth.enabled=true"` and `@Import(BluetoothSetupAdvice.class)`: the base has both. |

- [ ] **Step 2: Check every `/setup` body assertion against the fragment it names**

In the four classes, find every `content().string(containsString(...))`, `not(containsString(...))`, or similar assertion on a `/setup` response.
- **Positive assertions:** check that the text occurs only in the section the test is about. Search `src/main/resources/templates/setup.html` and `templates/fragments/*.html`. Text that also occurs in another module's section now matches for the wrong reason. Narrow it, for example to the section's element id or a longer phrase, and list each change in your report.
- **Negative assertions:** check that no other section renders the text.

- [ ] **Step 3: Run the classes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.SetupControllerTest' --tests 'dev.andre.homecontrol.web.PromptPairingSetupTest' --tests 'dev.andre.homecontrol.web.SourcesSetupControllerTest' --tests 'dev.andre.homecontrol.web.BluetoothSetupControllerTest' --tests 'dev.andre.homecontrol.testsupport.SharedWebSliceTest' --rerun`

Expected: PASS, with each class's count unchanged.
- **If a `/setup` render fails** with a `NullPointerException` inside an advice or a template, a collaborator returned Mockito's `null`. Add its safe value to `WebSliceTest.stubSafeDefaults()`, the smallest existing constant or factory of the returned type, and record it in your report.
- **Do not stub it in the test class instead:** every class that renders `/setup` needs the same default.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/web/SetupControllerTest.java src/test/java/dev/andre/homecontrol/web/PromptPairingSetupTest.java src/test/java/dev/andre/homecontrol/web/SourcesSetupControllerTest.java src/test/java/dev/andre/homecontrol/web/BluetoothSetupControllerTest.java src/test/java/dev/andre/homecontrol/testsupport/WebSliceTest.java
git commit -m "test: move the setup page web tests onto the shared slice"
```

(Stage `WebSliceTest.java` only if Step 3 changed it.) End the message with your `Co-Authored-By:` trailer.

### Task 4: The content-source classes

**Files:**
- Modify:
  - `src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinImageControllerTest.java`, `JellyfinSetupControllerTest.java`;
  - `sources/pinned/PinUpgradeControllerTest.java`, `PinnedSetupControllerTest.java`;
  - `sources/sports/SportsSetupControllerTest.java`, `sources/sports/thesportsdb/TheSportsDbSetupControllerTest.java`;
  - `sources/tmdb/TmdbSetupControllerTest.java`;
  - `sources/workflows/WorkflowSetupControllerTest.java`;
  - `sources/youtube/YouTubeSetupControllerTest.java`, `YouTubeThumbnailControllerTest.java`.

  All paths are under `src/test/java/dev/andre/homecontrol/`.

**Interfaces:**
- Consumes: `WebSliceTest` and its field names (Task 1).
- Produces: nothing.

Use Task 2's recipe.

- [ ] **Step 1: Move the ten classes**

| Class | Its fields → base names | Also |
| --- | --- | --- |
| `JellyfinImageControllerTest` | `client` → `jellyfinClient`; `setup` → `jellyfinSetup` | – |
| `JellyfinSetupControllerTest` | `setup` → `jellyfinSetup`; `devices` same | – |
| `PinUpgradeControllerTest` | `pins` same | – |
| `PinnedSetupControllerTest` | `pins`, `pairing`, `devices` same | Delete the nested `Config`: the base has `PinnedProperties(true, 200)`. |
| `SportsSetupControllerTest` | `calendars` → `sportsCalendars`; `settings` → `sportsSettings`; `zones` → `sportsZones`; `schedule` → `calendarSchedule`; `login`, `pairing`, `devices` same | Delete the nested `Config`: the base has the same `SportsProperties`. |
| `TheSportsDbSetupControllerTest` | as `SportsSetupControllerTest`, plus `competitions` → `sportsCompetitions` and `tsdbSchedule` → `theSportsDbSchedule` | Delete the nested `Config`. |
| `TmdbSetupControllerTest` | `setup` → `tmdbSetup`; `pairing`, `devices`, `login` same | – |
| `WorkflowSetupControllerTest` | `store` → `workflowStore`; `testService` → `workflowTests`; `login`, `pairing`, `devices` same | **Only the `@WebMvcTest` class `WorkflowSetupControllerTest`.** The second class in the same file, `WorkflowSetupAuthenticationTest` (a `@SpringBootTest`), keeps its own fields and names, including its `store` and `http`: rename nothing inside it. 1.3d-3 moves it. |
| `YouTubeSetupControllerTest` | `setup` → `youTubeSetup` | – |
| `YouTubeThumbnailControllerTest` | `http` → `youTubeHttp` | Delete the nested `Config` and the `@Import`: the base has the same `YouTubeProperties`. |

- [ ] **Step 2: Check every `/setup` body assertion**

As in Task 3 Step 2, for the classes that render `/setup`: `PinnedSetupControllerTest`, `SportsSetupControllerTest`, `TheSportsDbSetupControllerTest`, `TmdbSetupControllerTest` and `WorkflowSetupControllerTest`.

- [ ] **Step 3: Run the classes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinImageControllerTest' --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinSetupControllerTest' --tests 'dev.andre.homecontrol.sources.pinned.PinUpgradeControllerTest' --tests 'dev.andre.homecontrol.sources.pinned.PinnedSetupControllerTest' --tests 'dev.andre.homecontrol.sources.sports.SportsSetupControllerTest' --tests 'dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbSetupControllerTest' --tests 'dev.andre.homecontrol.sources.tmdb.TmdbSetupControllerTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowSetupControllerTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowSetupAuthenticationTest' --tests 'dev.andre.homecontrol.sources.youtube.YouTubeSetupControllerTest' --tests 'dev.andre.homecontrol.sources.youtube.YouTubeThumbnailControllerTest' --rerun`

Expected: PASS, with each class's count unchanged, including `WorkflowSetupAuthenticationTest`. The `NullPointerException` rule of Task 3 Step 3 applies.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinImageControllerTest.java src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/pinned/PinUpgradeControllerTest.java src/test/java/dev/andre/homecontrol/sources/pinned/PinnedSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/sports/SportsSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/sports/thesportsdb/TheSportsDbSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/youtube/YouTubeSetupControllerTest.java src/test/java/dev/andre/homecontrol/sources/youtube/YouTubeThumbnailControllerTest.java src/test/java/dev/andre/homecontrol/testsupport/WebSliceTest.java
git commit -m "test: move the content-source web tests onto the shared slice"
```

(Stage `WebSliceTest.java` only if you changed it.) End the message with your `Co-Authored-By:` trailer.

### Task 5: The rules that keep the slice shared, the measure and the docs

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/WebSliceRulesTest.java`
- Modify: `docs/dev/testing.md` (the "Test layers" table, and a new section "Web slices"), `docs/dev/architecture.md` ("Progress measures")

**Interfaces:**
- Consumes: `WebSliceTest` (Task 1).
- Produces: nothing.

- [ ] **Step 1: The rules**

`src/test/java/dev/andre/homecontrol/testsupport/WebSliceRulesTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/** Keeps every web-layer test on the one shared context of {@link WebSliceTest}. */
class WebSliceRulesTest {

    private static final JavaClasses TESTS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("dev.andre.homecontrol");

    private static final DescribedPredicate<JavaClass> A_SLICE_TEST = DescribedPredicate.describe(
            "a subclass of WebSliceTest",
            type -> type.isAssignableTo(WebSliceTest.class) && !type.isEquivalentTo(WebSliceTest.class));

    @Test
    void onlyTheSharedSliceIsAWebMvcTest() {
        // BluetoothSetupOffTest needs the Bluetooth module off; Phase 1.3d-2 moves it into the modules-off context.
        classes().that().areAnnotatedWith(WebMvcTest.class)
                .should().be(WebSliceTest.class).orShould().haveSimpleName("BluetoothSetupOffTest")
                .check(TESTS);
    }

    @Test
    void sliceTestsDeclareNoBeansOfTheirOwn() {
        noFields().that().areDeclaredInClassesThat(A_SLICE_TEST)
                .should().beAnnotatedWith(MockitoBean.class).orShould().beAnnotatedWith(MockitoSpyBean.class)
                .allowEmptyShould(true)
                .check(TESTS);
        noClasses().that(A_SLICE_TEST).should().beAnnotatedWith(Import.class)
                .allowEmptyShould(true)
                .check(TESTS);
        noClasses().that(DescribedPredicate.describe("nested in a subclass of WebSliceTest",
                        (JavaClass type) -> type.getEnclosingClass().map(A_SLICE_TEST::test).orElse(false)))
                .should().beAnnotatedWith(TestConfiguration.class)
                .allowEmptyShould(true)
                .check(TESTS);
    }
}
```

If an ArchUnit method name differs in the project's ArchUnit version, use its equivalent. Check `ArchitectureTest` for the version's style, and keep each rule's meaning.

- [ ] **Step 2: Run it, then show each rule failing**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.WebSliceRulesTest' --rerun`

Expected: PASS.

Then, one at a time, make a temporary violation, run, see that rule fail, and remove the violation:
1. Add `@org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` to a new empty class in the test tree.
2. Add `@MockitoBean java.time.Clock clock;` to `SearchControllerTest`.
3. Add `@Import(Object.class)` to `SearchControllerTest`.
4. Add a nested `@TestConfiguration static class Extra {}` to `SearchControllerTest`.

`git diff` must show only the new rules file afterwards.

- [ ] **Step 3: Count the context starts**

```bash
scripts/gradle.sh test --rerun
grep -h -o 'INFO [0-9]* --- \[[^]]*\] [^:]*: Started [A-Za-z0-9_$]* in [0-9.]* seconds' build/test-results/test/*.xml > /tmp/claude-1000/-home-docker1-home-control/75551f99-893a-43a2-943d-df740a2b3a32/scratchpad/p13d1-starts.txt
awk '{pid=$2; for(i=1;i<=NF;i++) if($i=="in") s=$(i+1); n[pid]++; t[pid]+=s; N++; T+=s} END {for (p in n) printf "pid %s: %d starts, %.1f s\n", p, n[p], t[p]; printf "total: %d starts, %.1f s\n", N, T}' /tmp/claude-1000/-home-docker1-home-control/75551f99-893a-43a2-943d-df740a2b3a32/scratchpad/p13d1-starts.txt
```

Expected:
- The suite is green, with 2,752 tests.
- No PID has more than one `Started` line for a class extending `WebSliceTest`, and the class names in those lines are the slice classes.
- The total is about 71 − 26 + 4 = 49 starts. Record the total and each PID's count and seconds.

A known flake that fails under four JVMs and passes alone is recorded, not fixed here.

- [ ] **Step 4: The docs**

In `docs/dev/testing.md`, "Test layers", replace the "Web slices" row with:

```markdown
| Web slices | One shared `@WebMvcTest` context over every controller, with their collaborators mocked: test classes extend `WebSliceTest`. | `src/test/java/.../web`, and next to each content source's own controllers |
```

After the "Test layers" section, add:

```markdown
## Web slices

A web-layer test extends `testsupport.WebSliceTest`. That class is one `@WebMvcTest` over every controller and
controller advice, with every collaborator declared once as a `@MockitoBean`, so all web tests share one Spring context
per test JVM.
- A test class stubs the base's mocks (`devices`, `pairing`, `sources`, …) and declares no beans of its own: no
  `@MockitoBean`, `@Import` or nested `@TestConfiguration`. Any of those would give it a context of its own, and
  `WebSliceRulesTest` fails.
- A controller that needs a new collaborator gets it as a `@MockitoBean` in `WebSliceTest`. If every `/setup` render
  calls it and Mockito's `null` would break the page, `stubSafeDefaults()` gives it a safe value.
- A test that needs a bean to be absent does not fit the slice: build the controller with a standalone MockMvc, as
  `ContentPlayWithoutPinsTest` does.
```

In `docs/dev/architecture.md`, "Progress measures", the row "Spring context starts per test run" gets the Step 3 total in its "Now" cell. Write it in the table's style, followed by the per-JVM spread, for example `49 (four JVMs: 12, 11, 14, 12)`.

- [ ] **Step 5: Build and check**

```bash
scripts/gradle.sh build
git diff --stat origin/main -- src/main
```

Expected: the build is green, and the second command prints nothing.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/WebSliceRulesTest.java docs/dev/testing.md docs/dev/architecture.md
git commit -m "test: keep every web test on the shared slice, and record the context starts"
```

End the message with your `Co-Authored-By:` trailer.
