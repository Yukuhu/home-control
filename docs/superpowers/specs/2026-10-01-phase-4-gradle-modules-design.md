# Phase 4: Gradle Modules

**Status:** approved in conversation on 2026-10-01, section by section.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, "Phase 4: Gradle modules (M)". Its
precondition, an empty frozen ArchUnit store, holds: 3E's PR 1 (#164) removed the store, and every rule is strict.

## Purpose

ArchUnit checks the package rules after the code compiles. A module makes the compiler refuse a wrong import as it is
typed, and lets Gradle skip the compiling and testing a change cannot affect. The roadmap's first split has three
modules:

- **`core`:** the domain model, which depends only on the JDK. 76 classes, 2,544 lines, 27 test classes.
- **`protocols`:** the Spring-free wire libraries and the ICS parser. 73 classes, about 5,170 lines, 50 test classes:
  - the seven protocol packages, `adapters.{androidtv,cast,sonos,tizen,upnp,webos}.protocol` and
    `discovery.ssdp.protocol`;
  - `adapters.net` (TLS, WebSockets, device URLs, Wake-on-LAN), without its two Spring classes;
  - `sources.sports.ics`.
- **`app`:** everything that uses Spring.

Whether to split `app` further is decided from build times measured before and after.

## Decisions (the user's, 2026-10-01)

- **Two PRs.** PR 1: the multi-module scaffolding and `core`. PR 2: `protocols` and the measured build times.
- **The root project stays the app.** It keeps `src/main`, `src/test`, `src/e2e` and the `bootJar`. `core/` and
  `protocols/` are new subprojects beside it, so only the moved files change paths. (Rejected: everything in
  subprojects, which moves all ~1,500 files to `app/src/…` and changes every path in CI, Docker, scripts and the
  guides; source sets in one project, which gives no per-module test avoidance, the thing the roadmap wants to
  measure.)
- **Shared settings in one `subprojects {}` block** of the root build script. (Rejected: a `buildSrc` convention
  plugin, which adds the `kotlin-dsl` build dependencies and their checksum entries for two small modules.)
- **One combined coverage report.** App tests exercise most of the core and protocol code through the fakes, so a
  report per module would undercount both. The root builds one JaCoCo report over every module's classes from every
  module's tests, and every SonarCloud module reads it. (Agreed with section 2; it amends section 1's per-module
  reports.)

## Constraints

- Main code keeps its package and class names, except `adapters.net.NetConfiguration`, which becomes
  `adapters.support.WakeOnLanConfiguration`. The setting `home-control.wake-on-lan` keeps its name. Two test fakes
  change package (section 2).
- One boot jar, one Docker image, the same CasaOS app. The modules' jars are named `home-control-core` and
  `home-control-protocols`, so they cannot collide with a library's `core` jar inside the boot jar.
- Nothing a user sees changes: no `/data` format, HTTP endpoint, page or setting.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` pass at the end of each PR, and CI's image
  smoke test starts the image.

## Design

### 1. PR 1: scaffolding and `core`

1. **Layout.** `settings.gradle.kts` includes `:core`. `git mv` takes the 76 classes to
   `core/src/main/java/dev/andre/homecontrol/core/…` and the 27 test classes to `core/src/test/java/…`.
2. **Build.**
   - `core/build.gradle.kts` applies `java-library`, sets `base.archivesName` to `home-control-core`, and has no
     main dependencies.
   - Its tests use JUnit and AssertJ, with versions from the Spring Boot BOM (`libs.spring.boot.dependencies`).
   - The root adds `implementation(project(":core"))`.
   - The settings every Java project shares (toolchain 25, `mavenCentral()`, JaCoCo 0.8.15, the JUnit platform, test
     logging) move into one `subprojects {}` block. The root keeps applying them to itself, along with the settings
     only the app has: the test task's system properties, environment, forks and deployment inputs; the e2e source
     set; the Playwright tasks.
3. **The compiler is the gate.**
   - `core` cannot reach Spring or the app, since neither is on its classpath.
   - `ArchitectureTest.coreDependsOnlyOnTheJdk` is removed.
   - `TestArchitectureTest` goes: its one rule (core's tests do not use `sources`) is now a compile error.
   - The architecture guide's rule table says the module enforces both.
   - `ArchitectureTest` imports `core`'s classes from the classpath, so the other rules, such as no cycles between
     top-level packages, still see them. A guard test asserts that the imported classes include `core`'s: a rule
     that silently matched no classes would pass for the wrong reason.
4. **Coverage.**
   - The root applies Gradle's `jacoco-report-aggregation` plugin. Its `testCodeCoverageReport` covers the root's
     and its project dependencies' classes, with the execution data of every module's `test` task.
   - Every SonarCloud module reads that XML report through `sonar.coverage.jacoco.xmlReportPaths`.
   - The `sonar` task depends on the combined report, not on the root's `jacocoTestReport`.
5. **CI, Docker and SonarCloud.**
   - CI's test-result and report paths become `**/build/…` globs (`**/build/test-results/test/TEST-*.xml`,
     `**/build/reports/tests/test`), and the coverage path points at the combined report.
   - `.dockerignore` ignores `**/build`.
   - The Dockerfile's `COPY . .` already includes `core/`, and `bootJar` builds it. The boot jar stays at
     `build/libs/`.
   - `verifyDependencyChecksums` resolves every resolvable configuration of every project.
6. **Docs.**
   - `AGENTS.md`'s "Where things live" names the modules.
   - The architecture guide gains a module table and marks the rules the compiler now enforces.
   - The testing guide covers running one module's tests (`scripts/gradle.sh :core:test`).
   - The CI guide covers the globbed paths and the combined report.
   - ADR 0006 records the module split, its layout and the root-as-app decision.

### 2. PR 2: `protocols`

1. **What moves** (`git mv`, packages unchanged):
   - the seven protocol packages;
   - `adapters.net` without its Spring classes;
   - `sources.sports.ics`;
   - `src/main/proto`, along with the protobuf plugin;
   - the 50 test classes of those packages.
2. **Build.**
   - `protocols/build.gradle.kts` applies `java-library` and `java-test-fixtures`, sets `base.archivesName` to
     `home-control-protocols`, and depends on `api(project(":core"))`.
   - Its libraries: Jackson, BouncyCastle, protobuf-java and the SLF4J API, with versions from the Spring Boot BOM.
     The Jackson BOM that raises Jackson above Spring Boot's version applies here as it does in the root, so the
     protocol tests run against the Jackson the app ships.
   - A library whose types appear in a public signature is `api`; the rest are `implementation`. protobuf-java is
     `api`: `adapters.androidtv.AndroidTvKeys` in the app uses the generated `RemoteKeyCode`.
   - In the root build, `implementation(project(":protocols"))` replaces the protobuf plugin and those libraries. A
     library the app also uses directly stays in its dependencies.
3. **Wake-on-LAN's Spring classes.**
   - `NetConfiguration` and `WakeOnLanProperties` move to `adapters.support`, next to `WakeOnLanPower`.
     `NetConfiguration` becomes `WakeOnLanConfiguration`.
   - `adapters.net` then lives in `protocols` alone. No main package is split between modules.
4. **Shared test code.**
   - **Fakes the app's tests or the browser tests use** move to `protocols/src/testFixtures/java`:
     - the protocol fakes;
     - `FakeUpnpRenderer`, into `adapters.upnp.protocol`;
     - `FakeSsdpResponder`, into `discovery.ssdp.protocol`;
     - `TestTls` and `Request`, which stay in `testsupport`.
   - **Fakes only protocol tests use** stay in `protocols/src/test`.
   - **Recorded responses** that protocol tests read move to `protocols/src/testFixtures/resources/fixtures/`, under
     the same subdirectories. Many are read by app tests too.
   - **Reading the recordings.** Tests that read one with `Path.of("src/test/resources/…")` switch to a classpath
     helper in the test fixtures, `Fixtures.read("upnp/didl-track.xml")`. Each module's tests run in that module's
     directory, so the relative path no longer finds the file.
   - **Wiring.** The root adds `testImplementation(testFixtures(project(":protocols")))`. The e2e source set extends
     `testImplementation`, so the browser tests get the fakes too.
5. **ArchUnit.**
   - The compiler keeps Spring and the app out of `protocols`, but not `core`, which `protocols` depends on.
     `protocolPackagesStandAlone` stays: protocol packages reach `core` only through `adapters.net`.
   - `ArchitectureTest` still imports `protocols`' classes from the classpath.
   - It adds ArchUnit's `DoNotIncludeGradleTestFixtures` import option, so the fakes are not checked as production
     code.
   - The guard test from PR 1 also asserts that the imported classes include `protocols`'.
6. **Docs.**
   - `AGENTS.md`: where the fakes and recorded responses live now.
   - The architecture guide: the module table and the package map.
   - The testing guide: test fixtures and `Fixtures.read`.

### 3. Build times

Three measurements, each with `scripts/gradle.sh` on the same machine, a warm dependency cache and no build cache,
taking the median of three runs:

- a clean `build`;
- `build` after a method-body change in an app class;
- `build` after a method-body change in a core class (from PR 1 on), and in a protocol class (from PR 2 on).

The measurements run on main at `1a6938d` before PR 1, and again after each PR. The numbers go in each PR's
description and in the architecture guide's measures. If an app-class change still costs nearly a clean build's
time, a further split of `app` gets its own spec; otherwise the three modules stay.

## Testing

- **PR 1:**
  - `:core:test` runs core's 27 test classes.
  - `ArchitectureTest` passes without `coreDependsOnlyOnTheJdk`, and its guard test finds `core`'s classes.
  - The combined coverage report holds core's classes, with lines covered by app tests.
  - The rest of the suite passes unchanged.
  - CI's smoke test starts the image built from the new layout.
- **PR 2:**
  - `:protocols:test` runs the 50 moved test classes.
  - `FixturesTest` covers reading a recording from the classpath, and a missing one failing with its name.
  - `ArchitectureTest`'s guard test finds `protocols`' classes, and no test-fixture class.
  - App and browser tests pass with the fakes from the test fixtures.
  - The boot jar holds `home-control-core` and `home-control-protocols`, and the app starts in CI's smoke test.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` at the end of each PR.

## Delivery

**PR 1** (`build/gradle-modules`, from main `1a6938d`). The baseline measurements on main come first and need no
commit.

1. `build:` the multi-module scaffolding: `subprojects {}`, the combined coverage report, SonarCloud, CI globs,
   `.dockerignore`, project-wide checksums.
2. `refactor:` `core` becomes the `:core` module. Its rule and `TestArchitectureTest` go.
3. `docs:` `AGENTS.md`, the architecture, testing and CI guides, and ADR 0006.

**PR 2** (planned after PR 1 merges, from main):

1. `refactor:` Wake-on-LAN's Spring configuration moves to `adapters.support`.
2. `test:` recordings are read from the classpath through `Fixtures.read`.
3. `refactor:` the `protocols` module: main code, tests, test fixtures, protobuf.
4. `docs:` `AGENTS.md` and the architecture and testing guides, with the measured build times.

## Visible changes

None.

## Measures

| | Before | After |
| --- | --- | --- |
| Gradle projects | 1 | 3 |
| Classes the compiler keeps free of Spring and the app | 0 | 149 (`core` 76, `protocols` 73) |
| ArchUnit rules replaced by the compiler | 0 | 2 (`coreDependsOnlyOnTheJdk`, `coreTestsDoNotUseSources`) |
| Clean `build` | measured on main | measured after PR 2 |
| `build` after an app-class change | measured on main | measured after PR 2 |
| `build` after a core- or protocol-class change | measured on main | measured after PR 2 |

## Out of scope

- A further split of `app`. The measurements decide whether it gets its own spec.
- A `buildSrc` convention plugin, and Java modules (`module-info.java`).
- Bluetooth's BlueZ and mpv code, which has no protocol package.
- 3E's deferred minors: Sonos using `core.Hosts.authority`, `CastApps`' test gaps, test-only public members, and
  `CastSession` above its estimate.
- 2D's deferred minors: the Reconnector threading contract, Tizen key codes in messages, and the executor rule's
  nested classes.
