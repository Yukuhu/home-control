# Phase 1.3d-2 Modules-Off Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nine module-off test classes, which today start nine Spring contexts, share one: a context with every module that can be switched off switched off.

**Architecture:**
- **One base class.** `testsupport.ModulesOffTest` is a `@SpringBootTest` with `@AutoConfigureMockMvc`. It switches off every module that can be switched off, and has one inherited `@DynamicPropertySource` for its data directory.
  - A module-off test extends it and adds nothing to the context, like `WebSliceTest`'s subclasses.
  - `ModulesOffSmokeTest` proves the application starts that way with no bean of any switched-off module.
- **Tests stay where they are.** Each class stays in its file and package, and keeps its test names. The one exception is the nested `SportsModuleSwitchTest.Off`, which becomes a top-level class.
- **The context-sharing rules cover both bases.** `WebSliceRulesTest` becomes `SharedContextRulesTest`. It applies the same allow-list to `ModulesOffTest`'s subclasses, and drops its `BluetoothSetupOffTest` exception.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`), JUnit 6, AssertJ, ArchUnit 1.5.1, Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.3d Shared Spring contexts", part "`ApplicationContextRunner`, 0 cached contexts", and "Progress measures". The user decided on 2026-09-28 to replace that part with one "modules off" context. See plan decision 1.

## Global Constraints

- **Start condition:** #134 (1.3d-1) is merged. Branch `test/modules-off-context` from `origin/main` (`da3a62f`).
- **Delivery (the user's decision):** 1.3d ships as three pull requests: 1.3d-1, the web slice (merged); 1.3d-2, this plan; 1.3d-3, the shared full-application context.
- **Production code does not change.** `git diff origin/main -- src/main` stays empty.
- **Test count:** equal or higher. A moved test names its replacement in the commit message. This plan adds `ModulesOffSmokeTest`'s one test, so 2,755 becomes 2,756.
- **Build and test only through `scripts/gradle.sh`.** There is no local JDK, and `scripts/gradle.sh build` is green at the end.
- **Commits:**
  - Conventional Commits, ending with a `Co-Authored-By:` trailer naming the model that wrote them.
  - Stage only the files you changed: `git add <paths>`, never `git add -A`.

## What the inventory found

On `78f3a30`, before 1.3d-1, with four JVMs:
- **Context starts:** 71 in total. 1.3d-1 brought them to 48.
- **Module-off contexts:** nine classes start one each:
  - `CastDisabledSmokeTest` (`dev.andre.homecontrol`);
  - `SmartTvModulesOffTest` and `BluetoothSetupOffTest` (`web`);
  - `JellyfinModuleSwitchTest`, `YouTubeModuleSwitchTest`, `TmdbModuleSwitchTest` and `PinnedModuleSwitchTest`;
  - `SportsModuleSwitchTest.Off`, a `@Nested` class;
  - `WorkflowDisabledSetupTest`, a second top-level class in `WorkflowModuleSwitchTest.java`.

  `BluetoothSetupOffTest` is the one `@WebMvcTest` that 1.3d-1 left outside the slice. The others are `@SpringBootTest`s.
- **What they check:** each checks that its module left no bean behind, that `/setup` (or `/` for Cast) shows none of its sections, that its routes answer 404, and in three cases that it wrote no file (`pinned.json`, `sports.json`, `secrets.json`).
- **Mixed combinations:** three more classes switch modules in combinations other than "off":
  - `SportsModuleSwitchTest.TheSportsDbOff`: sports on, TheSportsDB off;
  - `WorkflowOnlySetupTest`: every content source off except workflows;
  - `SportsModuleSwitchTest.OnButUnconfigured`: default properties.
- **The switches:** `home-control.<module>.enabled` exists for jellyfin, youtube, tmdb, pinned, sports, workflows, cast, webos, tizen, upnp, sonos, bluetooth and ssdp.
  - Bluetooth is off by default, and the test configuration switches SSDP off.
  - Android TV has no switch.
  - `SsdpDiscovery` is unconditional (`SsdpConfiguration`).

## Plan decisions

1. **One "modules off" context, not a context runner per test (the user's decision, 2026-09-28).**
   - The nine classes check rendered pages and routes, not only beans. A runner would have to rebuild MVC, Thymeleaf and every setup controller and advice by hand, and would lose the check that the whole application starts.
   - One context with every switchable module off keeps those checks, and costs one start per test JVM that runs such a class.
2. **Switching every module off at once loses no wiring coverage.** The per-module contexts also proved that switching one module off leaves the others starting. With everything off, that is covered by the architecture rules:
   - `ArchitectureTest.sourcesAreIndependent` and `adaptersAreIndependent`: no source depends on another, and no adapter depends on another.
   - The frozen store names only `pinned` and `sports` → `androidtv` as source-to-adapter dependencies, and Android TV cannot be switched off.
   - So any bean that could need a switched-off module is either always on, as `web`, `device`, `content` and `core` are, and then fails to start in this context too, or is switched off with it.
3. **The mixed combinations keep their own contexts.**
   - `TheSportsDbOff` checks the setup page's sports section without TheSportsDB, and a 404 route. `WorkflowOnlySetupTest` checks the page's "connections" category with workflows as its only source. Both need rendering, and their property sets conflict with each other and with "everything off".
   - At the handoff, "bean checks for mixed combinations become context runners" was assumed. The inventory shows they are page checks, so they stay as they are.
   - `OnButUnconfigured` has default properties: 1.3d-3 moves it into the shared full-application context.
   - Result: nine starts become one here, and three stay.
4. **UPnP and Sonos are switched off too,** so the context is "every module that can be switched off". Bluetooth is switched off explicitly, although that is its default. `SmartTvModulesOffTest`'s check that `SsdpDiscovery` still exists stays true, because that bean is unconditional.
5. **One data directory per context,** made once by the base's `@DynamicPropertySource`, a method all subclasses inherit, so they share one cache key. The three "no file written" checks read it through `dataDir()`.
6. **`CastDisabledSmokeTest` forgets the device it adopts,** in an `@AfterEach`. The device would otherwise stay in the shared context.
7. **`SportsModuleSwitchTest.Off` becomes `sources.sports.SportsModuleOffTest`.** A `@Nested` class inherits its enclosing class's configuration, and the enclosing class holds the two other sports variants.
8. **`WebSliceRulesTest` becomes `SharedContextRulesTest`.**
   - Its allow-list now covers every subclass of `WebSliceTest` or `ModulesOffTest`, and the classes nested in them: no Spring annotation, no bean override, no `@DynamicPropertySource`.
   - The `@WebMvcTest` rule loses its `BluetoothSetupOffTest` exception.

## Review Focus

- **A module-off check must still fail when its module leaks back.** In a context where everything is off, a check could pass only because a neighbouring module is off too. Test: in Task 3, one class's check is shown failing when its module is switched back on, with a temporary property on the base.
- **The shared data directory must not let one test's files satisfy or break another's "no file written" check.** Test: `CastDisabledSmokeTest` forgets its device after the test (Task 2), and all nine classes run together green.
- **A subclass that adds anything to the context silently gets a context of its own.** Test: `SharedContextRulesTest` (Task 4), shown failing on a temporary violation in a `ModulesOffTest` subclass.
- **The application must start with every module off.** Test: `ModulesOffSmokeTest` (Task 1) checks that Android TV's adapter exists and that no module's marker bean does.
- **There is one modules-off context per test JVM.** Test: Task 5 counts the `Started` lines of the nine classes per PID. There is at most one per JVM.

---

### Task 1: `ModulesOffTest` and its smoke test

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/ModulesOffTest.java`
- Test: `src/test/java/dev/andre/homecontrol/testsupport/ModulesOffSmokeTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public abstract class ModulesOffTest`, with:
  - `@Autowired protected ApplicationContext context`;
  - `@Autowired protected MockMvc mockMvc`;
  - `protected static Path dataDir()`.

- [ ] **Step 1: Write the smoke test**

`src/test/java/dev/andre/homecontrol/testsupport/ModulesOffSmokeTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapter;
import dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerAdapter;
import dev.andre.homecontrol.adapters.cast.CastAdapter;
import dev.andre.homecontrol.adapters.sonos.SonosAdapter;
import dev.andre.homecontrol.adapters.tizen.TizenAdapter;
import dev.andre.homecontrol.adapters.upnp.UpnpAdapter;
import dev.andre.homecontrol.adapters.webos.WebOsAdapter;
import dev.andre.homecontrol.sources.jellyfin.JellyfinClient;
import dev.andre.homecontrol.sources.pinned.PinnedShortcuts;
import dev.andre.homecontrol.sources.sports.SportsContentSource;
import dev.andre.homecontrol.sources.tmdb.TmdbContentSource;
import dev.andre.homecontrol.sources.workflows.WorkflowStore;
import dev.andre.homecontrol.sources.youtube.YouTubeSetupService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** With every module that can be switched off switched off, the application still starts, and none of them is left. */
class ModulesOffSmokeTest extends ModulesOffTest {

    @Test
    void theApplicationStartsWithEveryModuleOff() {
        assertThat(context.getBeanNamesForType(AndroidTvAdapter.class)).hasSize(1);
        assertThat(List.of(JellyfinClient.class, YouTubeSetupService.class, TmdbContentSource.class,
                PinnedShortcuts.class, SportsContentSource.class, WorkflowStore.class, CastAdapter.class,
                WebOsAdapter.class, TizenAdapter.class, UpnpAdapter.class, SonosAdapter.class,
                BluetoothSpeakerAdapter.class))
                .isNotEmpty()
                .allSatisfy(type -> assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.ModulesOffSmokeTest'`

Expected: FAIL at `compileTestJava`: `cannot find symbol: class ModulesOffTest`.

- [ ] **Step 3: The base class**

`src/test/java/dev/andre/homecontrol/testsupport/ModulesOffTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The one application context with every module that can be switched off switched off; Android TV, which cannot,
 * stays. A module-switch test extends it and adds nothing to the context (no Spring annotation, bean override or
 * dynamic property; {@code SharedContextRulesTest} checks it). It proves that its module leaves no bean, setup section,
 * route or file behind, and the application still starts without it.
 */
@SpringBootTest(properties = {"home-control.jellyfin.enabled=false", "home-control.youtube.enabled=false",
        "home-control.tmdb.enabled=false", "home-control.pinned.enabled=false", "home-control.sports.enabled=false",
        "home-control.workflows.enabled=false", "home-control.cast.enabled=false", "home-control.webos.enabled=false",
        "home-control.tizen.enabled=false", "home-control.upnp.enabled=false", "home-control.sonos.enabled=false",
        "home-control.bluetooth.enabled=false"})
@AutoConfigureMockMvc
public abstract class ModulesOffTest {

    private static Path dataDir;

    @Autowired
    protected ApplicationContext context;

    @Autowired
    protected MockMvc mockMvc;

    /** Inherited by every subclass, so they share one cache key: one data directory per context. */
    @DynamicPropertySource
    static void isolatedDataDirectory(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("modules-off");
        registry.add("shield.data-dir", dataDir::toString);
    }

    /** The context's data directory, into which a switched-off module writes nothing. */
    protected static Path dataDir() {
        return dataDir;
    }
}
```

- [ ] **Step 4: Run it**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.ModulesOffSmokeTest' --rerun`

Expected: PASS. If a marker bean exists although its module is off, the switch does not remove it. Read that module's configuration, record the finding in your report, and remove that type from the list only if the module has no switch for it. This plan changes no production code.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/ModulesOffTest.java src/test/java/dev/andre/homecontrol/testsupport/ModulesOffSmokeTest.java
git commit -m "test: add one shared context with every module switched off"
```

End the message with your `Co-Authored-By:` trailer.

### Task 2: The device-module tests

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/CastDisabledSmokeTest.java`, `src/test/java/dev/andre/homecontrol/web/SmartTvModulesOffTest.java`, `src/test/java/dev/andre/homecontrol/web/BluetoothSetupOffTest.java`

**Interfaces:**
- Consumes: `ModulesOffTest` (`context`, `mockMvc`, `dataDir()`) from Task 1.
- Produces: nothing.

**The recipe for each class** (the same in Task 3):
1. Before the change, note the class's test count from a `--rerun` of it.
2. Remove `@SpringBootTest(...)` (or `@WebMvcTest(...)`) and `@AutoConfigureMockMvc`, and make the class `extends ModulesOffTest`.
3. Remove its `@DynamicPropertySource` method and the data-directory field that method set. A file check uses `dataDir()`.
4. Remove its `@Autowired ApplicationContext context` and `@Autowired MockMvc mockMvc` fields: the base has them under these names. A field under another name, such as `mvc`, is removed too, and its uses become `mockMvc`. Other `@Autowired` fields, such as `DeviceManager devices` or `ContentSources sources`, stay.
5. Remove every `@MockitoBean` field, and the `@BeforeEach` that only stubbed them: the context is the real application.
6. Remove the imports that are no longer used, and add `import dev.andre.homecontrol.testsupport.ModulesOffTest;`.
7. Run the class with `--rerun`: the count equals step 1's.

- [ ] **Step 1: Move the three classes**

| Class | Also |
| --- | --- |
| `CastDisabledSmokeTest` | Keep `@Autowired DeviceManager devices`. Add `@AfterEach void forgetTheDevice() { devices.forget("shield-c"); }`, because the device would otherwise stay in the shared context. Keep the class Javadoc: an Android-TV-only box is what this context is. |
| `SmartTvModulesOffTest` | Its Javadoc becomes `/** Both TV modules are add-ons: switched off (here with every other module), nothing of them is left, and the app still starts. */`. |
| `BluetoothSetupOffTest` | Remove `@WebMvcTest(SetupController.class)`, both `@MockitoBean` fields (`PairingService pairing`, `DeviceManager devices`) and the `@BeforeEach defaults()` that stubbed them: the real device manager starts empty. |

- [ ] **Step 2: Run the classes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.CastDisabledSmokeTest' --tests 'dev.andre.homecontrol.web.SmartTvModulesOffTest' --tests 'dev.andre.homecontrol.web.BluetoothSetupOffTest' --tests 'dev.andre.homecontrol.testsupport.ModulesOffSmokeTest' --rerun`

Expected: PASS, with each class's count unchanged.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/CastDisabledSmokeTest.java src/test/java/dev/andre/homecontrol/web/SmartTvModulesOffTest.java src/test/java/dev/andre/homecontrol/web/BluetoothSetupOffTest.java
git commit -m "test: move the Cast, TV and Bluetooth module-off tests onto the modules-off context"
```

End the message with your `Co-Authored-By:` trailer.

### Task 3: The content-module tests

**Files:**
- Modify:
  - `src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinModuleSwitchTest.java`;
  - `sources/youtube/YouTubeModuleSwitchTest.java`, `sources/tmdb/TmdbModuleSwitchTest.java` and `sources/pinned/PinnedModuleSwitchTest.java`;
  - `sources/sports/SportsModuleSwitchTest.java`;
  - `sources/workflows/WorkflowModuleSwitchTest.java`.

  All paths are under `src/test/java/dev/andre/homecontrol/`.
- Create: `src/test/java/dev/andre/homecontrol/sources/sports/SportsModuleOffTest.java`

**Interfaces:**
- Consumes: `ModulesOffTest` from Task 1.
- Produces: nothing.

Use Task 2's recipe.

- [ ] **Step 1: Move the four single-module classes**

| Class | Also |
| --- | --- |
| `JellyfinModuleSwitchTest` | – |
| `YouTubeModuleSwitchTest` | – |
| `TmdbModuleSwitchTest` | Keep `@Autowired ContentSources sources`. |
| `PinnedModuleSwitchTest` | `dataDir.resolve("pinned.json")` becomes `dataDir().resolve("pinned.json")`, and the static `dataDir` field goes. |

- [ ] **Step 2: `SportsModuleSwitchTest.Off` becomes `SportsModuleOffTest`**

Create `src/test/java/dev/andre/homecontrol/sources/sports/SportsModuleOffTest.java`. It is the nested class `SportsModuleSwitchTest.Off`, moved to the top level:
- keep its Javadoc and its test `theModuleCanBeSwitchedOff` unchanged;
- make it `class SportsModuleOffTest extends ModulesOffTest`;
- `dataDir.resolve("sports.json")` becomes `dataDir().resolve("sports.json")`;
- copy only the imports the class uses.

Then delete the nested `Off` class from `SportsModuleSwitchTest`, together with the imports only it used. `OnButUnconfigured` and `TheSportsDbOff` stay as they are (plan decision 3).

- [ ] **Step 3: Move `WorkflowDisabledSetupTest`**

In `WorkflowModuleSwitchTest.java`, the second top-level class `WorkflowDisabledSetupTest`:
- loses its two fully qualified annotations (`SpringBootTest` and `AutoConfigureMockMvc`), its `data(...)` `@DynamicPropertySource` method, its static `directory` field, and its `mvc` and `context` fields;
- becomes `class WorkflowDisabledSetupTest extends ModulesOffTest`;
- `mvc.perform` becomes `mockMvc.perform`;
- `directory.resolve("secrets.json")` becomes `dataDir().resolve("secrets.json")`.

Add `import dev.andre.homecontrol.testsupport.ModulesOffTest;` to the file. `WorkflowModuleSwitchTest` (the runner test) and `WorkflowOnlySetupTest` stay as they are.

- [ ] **Step 4: Run the classes**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinModuleSwitchTest' --tests 'dev.andre.homecontrol.sources.youtube.YouTubeModuleSwitchTest' --tests 'dev.andre.homecontrol.sources.tmdb.TmdbModuleSwitchTest' --tests 'dev.andre.homecontrol.sources.pinned.PinnedModuleSwitchTest' --tests 'dev.andre.homecontrol.sources.sports.SportsModuleOffTest' --tests 'dev.andre.homecontrol.sources.sports.SportsModuleSwitchTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowDisabledSetupTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowModuleSwitchTest' --tests 'dev.andre.homecontrol.sources.workflows.WorkflowOnlySetupTest' --rerun`

Expected: PASS.
- Every class's count is unchanged, except `SportsModuleSwitchTest`, which has one test fewer, and the new `SportsModuleOffTest`, which has one.
- JUnit names nested tests `SportsModuleSwitchTest$OnButUnconfigured` and so on: count them from the result files.

- [ ] **Step 5: Show that a check still catches its module leaking back**

Temporarily change `home-control.jellyfin.enabled=false` to `true` in `ModulesOffTest`'s `@SpringBootTest` properties.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.jellyfin.JellyfinModuleSwitchTest' --rerun`

Expected: FAIL: a Jellyfin bean exists, and `/setup` names Jellyfin. Restore `false`, and check that `git diff` shows no change to `ModulesOffTest.java`.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinModuleSwitchTest.java src/test/java/dev/andre/homecontrol/sources/youtube/YouTubeModuleSwitchTest.java src/test/java/dev/andre/homecontrol/sources/tmdb/TmdbModuleSwitchTest.java src/test/java/dev/andre/homecontrol/sources/pinned/PinnedModuleSwitchTest.java src/test/java/dev/andre/homecontrol/sources/sports/SportsModuleSwitchTest.java src/test/java/dev/andre/homecontrol/sources/sports/SportsModuleOffTest.java src/test/java/dev/andre/homecontrol/sources/workflows/WorkflowModuleSwitchTest.java
git commit -m "test: move the content-source module-off tests onto the modules-off context

SportsModuleSwitchTest.Off moves to SportsModuleOffTest, unchanged."
```

End the message with your `Co-Authored-By:` trailer.

### Task 4: One set of rules for both shared contexts

**Files:**
- Rename and modify: `src/test/java/dev/andre/homecontrol/testsupport/WebSliceRulesTest.java` → `SharedContextRulesTest.java`
- Modify: `docs/dev/testing.md` (the "Web slices" section names `WebSliceRulesTest`)

**Interfaces:**
- Consumes: `WebSliceTest` (1.3d-1) and `ModulesOffTest` (Task 1).
- Produces: nothing.

- [ ] **Step 1: Rename the class, and widen its subject to both bases**

`git mv src/test/java/dev/andre/homecontrol/testsupport/WebSliceRulesTest.java src/test/java/dev/andre/homecontrol/testsupport/SharedContextRulesTest.java`, then in the file:
1. The class becomes `class SharedContextRulesTest`. Its Javadoc becomes:

```java
/**
 * Keeps every test that extends a shared-context base ({@link WebSliceTest}, {@link ModulesOffTest}) on that base's
 * one context. An allow-list: such a test class, and every class nested in one, carries no Spring annotation at all (a
 * property source, a profile, {@code @DirtiesContext}, an {@code @Import} or {@code @AutoConfigure…}, a class-level
 * bean override) and declares no bean override and no dynamic property; any of those would give it a context of its
 * own.
 */
```

2. `isASliceTest` becomes `isASharedContextTest`, true for a proper subclass of either base:

```java
    private static boolean isASharedContextTest(JavaClass type) {
        return (type.isAssignableTo(WebSliceTest.class) && !type.isEquivalentTo(WebSliceTest.class))
                || (type.isAssignableTo(ModulesOffTest.class) && !type.isEquivalentTo(ModulesOffTest.class));
    }
```

   Rename the predicates and methods that use it to match:
   - `A_SLICE_TEST` → `A_SHARED_CONTEXT_TEST` ("a subclass of WebSliceTest or ModulesOffTest");
   - `INSIDE_A_SLICE_TEST` → `INSIDE_A_SHARED_CONTEXT_TEST`;
   - `A_SLICE_TEST_OR_INSIDE_ONE` → `A_SHARED_CONTEXT_TEST_OR_INSIDE_ONE`;
   - `isInsideASliceTest` → `isInsideASharedContextTest`;
   - the test methods `sliceTests…` → `sharedContextTests…`, and `classesNestedInSliceTests…` → `classesNestedInSharedContextTests…`.
3. In `onlyTheSharedSliceIsAWebMvcTest`, remove the `BluetoothSetupOffTest` comment and exception. The rule becomes `classes().that(A_WEB_MVC_TEST).should().beAssignableTo(WebSliceTest.class).check(TESTS);`.

- [ ] **Step 2: Run it, then show it covers `ModulesOffTest`'s subclasses**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.SharedContextRulesTest' --rerun`

Expected: PASS, 5 of 5.

Then, temporarily, add `@org.springframework.test.context.TestPropertySource(properties = "temporary=1")` to `JellyfinModuleSwitchTest`, and `@org.springframework.test.context.bean.override.mockito.MockitoBean java.time.Clock temporaryClock;` to `TmdbModuleSwitchTest`. Run again.

Expected: FAIL in `sharedContextTestsCarryNoSpringAnnotation` (naming `JellyfinModuleSwitchTest`) and in `sharedContextTestsDeclareNoBeanOverride` (naming `TmdbModuleSwitchTest.temporaryClock`). Remove both. `git diff` shows only the rename and Step 1's changes.

- [ ] **Step 3: The testing guide names the new class**

In `docs/dev/testing.md`, replace `WebSliceRulesTest` with `SharedContextRulesTest`, and remove the sentence "`BluetoothSetupOffTest`, which needs the Bluetooth module off, keeps its own for now." After the "Web slices" section, add:

```markdown
## Module switches

A test that checks a module switched off extends `testsupport.ModulesOffTest`: one application context with every
module that can be switched off switched off (Android TV cannot be), shared by all such tests. It checks that its
module leaves no bean, setup section, route or file behind (`dataDir()`), and `ModulesOffSmokeTest` that the
application starts that way. The same rules as for web slices apply (`SharedContextRulesTest`). A test of another
combination, such as sports on with TheSportsDB off, keeps a `@SpringBootTest` of its own.
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/testsupport/WebSliceRulesTest.java src/test/java/dev/andre/homecontrol/testsupport/SharedContextRulesTest.java docs/dev/testing.md
git commit -m "test: apply the shared-context rules to the modules-off tests too"
```

End the message with your `Co-Authored-By:` trailer.

### Task 5: Measure and document

**Files:**
- Modify: `docs/dev/architecture.md` ("Progress measures")

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Count the context starts**

```bash
scripts/gradle.sh test --rerun
grep -h -o 'INFO [0-9]* --- \[[^]]*\] [^:]*: Started [A-Za-z0-9_$]* in [0-9.]* seconds' build/test-results/test/*.xml > /tmp/claude-1000/-home-docker1-home-control/75551f99-893a-43a2-943d-df740a2b3a32/scratchpad/p13d2-starts.txt
awk '{pid=$2; for(i=1;i<=NF;i++) if($i=="in") s=$(i+1); n[pid]++; t[pid]+=s; N++; T+=s} END {for (p in n) printf "pid %s: %d starts, %.1f s\n", p, n[p], t[p]; printf "total: %d starts, %.1f s\n", N, T}' /tmp/claude-1000/-home-docker1-home-control/75551f99-893a-43a2-943d-df740a2b3a32/scratchpad/p13d2-starts.txt
```

Expected:
- The suite is green, with 2,756 tests.
- For each PID, at most one `Started` line comes from a class that extends `ModulesOffTest`. Spring Boot logs the first subclass's name; list the nine classes with `grep -rl 'extends ModulesOffTest' src/test/java`.
- The total is about 48 − 8 = 40. Record it and each PID's count.

- [ ] **Step 2: The progress measure**

In `docs/dev/architecture.md`, the row "Spring context starts per test run" gets the Step 1 total and per-JVM spread in its "Now" cell, in the style `40 (four JVMs: 10, 9, 11, 10)`.

- [ ] **Step 3: Build and check**

```bash
scripts/gradle.sh build
git diff --stat origin/main -- src/main
```

Expected: green, and the second command prints nothing.

- [ ] **Step 4: Commit**

```bash
git add docs/dev/architecture.md
git commit -m "docs: record the context starts after the modules-off context"
```

End the message with your `Co-Authored-By:` trailer.
