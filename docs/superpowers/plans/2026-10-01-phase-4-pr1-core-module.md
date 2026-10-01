# Phase 4 PR 1: The Core Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `core` becomes a Gradle module of its own, which the compiler keeps free of Spring and the app, with one
coverage report for the whole build and build times measured before and after.

**Architecture:** The root project stays the app. `core/` is a `java-library` subproject that depends on nothing; the
root depends on it with `implementation(project(":core"))`. The settings every project shares sit in one
`allprojects {}` block of the root build script, and Gradle's `jacoco-report-aggregation` plugin builds one JaCoCo
report over the root and `core`.

**Tech Stack:** Gradle 9.8 (Kotlin DSL, version catalog `gradle/libs.versions.toml`), Java 25, Spring Boot 4.1.1
plugin, JaCoCo 0.8.15, SonarCloud Gradle plugin 7.5, ArchUnit 1.5.1, JUnit 5, AssertJ, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-01-phase-4-gradle-modules-design.md` (section 1 and section 3 are this PR).

## Global Constraints

- Main code keeps its package and class names: `core` stays `dev.andre.homecontrol.core…`, now under
  `core/src/main/java/`.
- The module's jar is `home-control-core` (`base.archivesName`), so it cannot collide with a library's `core` jar
  inside the boot jar.
- One boot jar at `build/libs/`, one Docker image, the same CasaOS app. Nothing a user sees changes: no `/data`
  format, HTTP endpoint, page or setting.
- No new artifacts: `gradle/verification-metadata.xml` should not change. If verification asks for an entry, generate
  it with `scripts/gradle.sh --write-verification-metadata sha256 build e2eClasses`, check that only the expected
  entries were added, and ledger a ruling.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` pass at the end.
- Commits follow Conventional Commits and end with the session trailer:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
  ```
- Stage only the files you changed (`git add <paths>`, never `git add -A`).
- **Ruling carried into this plan:** the spec's "one `subprojects {}` block, which the root keeps applying to itself" is
  written as one `allprojects {}` block, which is the same thing in one place. A small `subprojects {}` block holds
  only what the modules need and the root must not get twice: `-parameters` (Spring Boot's plugin already gives it to
  the app) and the lenient test filter (Task 3).

## Review Focus

1. **`core` compiled without the app's compiler settings.** Spring Boot's plugin compiles the app with `-parameters`.
   A module compiled without it loses parameter names that reflection reads. Expected: `core` compiles as the app
   does. Pinned by `CompiledWithParameterNamesTest` (Task 3).
2. **A boot jar or image without `core`.** The app would fail to start in the container. Expected: the boot jar holds
   `BOOT-INF/lib/home-control-core-…jar`, and no core class sits in `BOOT-INF/classes`. Pinned by the boot-jar listing
   step (Task 3); CI's image jobs start the image.
3. **ArchUnit no longer seeing `core`.** `core`'s classes now reach `ArchitectureTest` as a jar. Were they missing,
   every rule would pass without seeing any dependency on `core`. Expected: the import holds them. Pinned by the
   guard `coreClassesAreImported`, with a RED run that leaves jars out (Task 3).
4. **Coverage lost on the way to SonarCloud.** The report path, the artifact layout between CI jobs, or a module's
   report property could each drop core's coverage, or count only core's own tests. Expected: the combined report
   counts core's lines covered by app tests. Pinned by the coverage comparison (Task 3) and, after the PR opens, by
   SonarCloud's coverage for a `core` file.
5. **`test --tests` habits.** Developers and agents run `scripts/gradle.sh test --tests '<app class>'`. With two
   modules, the filter runs in both. Expected: an app class still runs this way, and a filter that matches nothing
   still fails the build. Pinned by the filter checks (Task 3).

---

### Task 1: Baseline build times on main

The branch holds main `1a6938d` plus the spec, which is documentation only, so measuring here is measuring main.
Nothing is committed. The plan's workspace is `.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/` (git-ignored).

**Files:**
- Create: `.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/measure.py` (not committed)
- Create: `.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage.py` (not committed)
- Output: `.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/measures.md`,
  `.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage-before.xml`

**Interfaces:**
- Produces: `measure.py LABEL APP_CLASS CORE_CLASS`, which appends `## LABEL` and three medians to `measures.md`
  (Task 4 runs it again); `coverage.py PREFIX FILE…`, which prints a JaCoCo report's line counts (Tasks 2 and 3).

- [ ] **Step 1: Write the measuring script**

`.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/measure.py`:

```python
"""Times `scripts/gradle.sh build` for Phase 4's measures: a clean build, and a build after a change that moves every
line number of one class, so its bytecode changes and no signature does. Three timed runs each, without the build
cache; appends the runs and their medians to measures.md beside this script.

Usage: measure.py LABEL APP_CLASS CORE_CLASS (paths relative to the repository root)."""
import statistics
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
GRADLE = [str(REPO / "scripts" / "gradle.sh")]


def build(*tasks):
    start = time.monotonic()
    subprocess.run(GRADLE + list(tasks) + ["--no-build-cache"], cwd=REPO, check=True)
    return time.monotonic() - start


def toggle(path):
    """Adds an empty line after the package line, or removes the one added before."""
    lines = path.read_text(encoding="utf-8").split("\n")
    at = next(n for n, line in enumerate(lines) if line.startswith("package ")) + 1
    if lines[at] == "" and lines[at + 1] == "":
        del lines[at]
    else:
        lines.insert(at, "")
    path.write_text("\n".join(lines), encoding="utf-8")


def after_change(relative):
    path = REPO / relative
    build("build")
    runs = []
    for _ in range(3):
        toggle(path)
        runs.append(build("build"))
    toggle(path)
    build("build")
    subprocess.run(["git", "diff", "--exit-code", "--", relative], cwd=REPO, check=True)
    return runs


def line(what, runs):
    return f"- {what}: median {statistics.median(runs):.0f} s ({', '.join(f'{r:.0f} s' for r in runs)})"


def main(label, app_class, core_class):
    results = [
        line("clean `build`", [build("clean", "build") for _ in range(3)]),
        line(f"`build` after a change to `{app_class}`", after_change(app_class)),
        line(f"`build` after a change to `{core_class}`", after_change(core_class)),
    ]
    text = f"## {label}\n\n" + "\n".join(results) + "\n\n"
    with open(HERE / "measures.md", "a", encoding="utf-8") as out:
        out.write(text)
    print(text, flush=True)


main(*sys.argv[1:])
```

- [ ] **Step 2: Write the coverage script**

`.superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage.py`:

```python
"""Prints the line counts of JaCoCo XML reports: of the whole report with PREFIX "-", else of the packages whose
path starts with PREFIX (for example dev/andre/homecontrol/core).

Usage: coverage.py PREFIX FILE..."""
import sys
import xml.etree.ElementTree as ET


def lines(path, prefix):
    root = ET.parse(path).getroot()
    if prefix == "-":
        counters = root.findall("counter")
    else:
        counters = [counter for package in root.iter("package") if package.get("name").startswith(prefix)
                    for counter in package.findall("counter")]
    line_counters = [counter for counter in counters if counter.get("type") == "LINE"]
    covered = sum(int(counter.get("covered")) for counter in line_counters)
    missed = sum(int(counter.get("missed")) for counter in line_counters)
    return covered, missed


prefix = sys.argv[1]
for path in sys.argv[2:]:
    covered, missed = lines(path, prefix)
    print(f"{path}: covered {covered}, missed {missed}, total {covered + missed}")
```

- [ ] **Step 3: Measure main**

Run in the background, from the repository root (about 45 minutes; nothing else may build meanwhile):

```bash
python3 .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/measure.py "Before: main 1a6938d, one project" src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java src/main/java/dev/andre/homecontrol/core/Device.java
```

Expected: exit 0, and `measures.md` holds three lines under `## Before: main 1a6938d, one project`, each with a
median and three runs. If a run fails on a flaky test, the script stops: rerun it, and keep only a complete run's
numbers.

- [ ] **Step 4: Keep the root's coverage report as the before state**

The last build of Step 3 wrote `build/reports/jacoco/test/jacocoTestReport.xml`.

```bash
cp build/reports/jacoco/test/jacocoTestReport.xml .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage-before.xml
python3 .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage.py - .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage-before.xml
```

Expected: one line, `…coverage-before.xml: covered N, missed M, total T`, with N greater than 0.

---

### Task 2: Scaffolding for a multi-module build

**Files:**
- Modify: `build.gradle.kts` (the plugin list; lines 22–60, the project settings, JaCoCo and SonarCloud; lines
  124–127, the test platform; lines 184–196, `verifyDependencyChecksums`)
- Modify: `.github/workflows/ci.yml` (the build job's uploads, lines 310–333; the SonarCloud job's download and scan,
  lines 520–545)
- Modify: `.dockerignore`

**Interfaces:**
- Consumes: `coverage.py`, `coverage-before.xml` (Task 1).
- Produces: the `allprojects {}` block, which Task 3's module inherits; the task `testCodeCoverageReport`, with its
  XML at `build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml`; `check` and `sonar` depend on it.

- [ ] **Step 1: See that the combined report does not exist yet**

Run: `ls build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml`
Expected: `ls: cannot access …: No such file or directory`.

- [ ] **Step 2: Add the aggregation plugin**

In `build.gradle.kts`, the `plugins {}` block becomes:

```kotlin
plugins {
    java
    jacoco
    `jacoco-report-aggregation`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.test.retry)
}
```

- [ ] **Step 3: Replace the project settings, JaCoCo and SonarCloud blocks**

Replace everything from `group = "dev.andre"` through the closing brace of `sonar {}` (lines 22–60) with:

```kotlin
// One coverage report for the whole build: the app's tests exercise the modules' code too, so a report per module
// would count only each module's own tests. SonarCloud reads it for every module.
val combinedCoverageReport =
    layout.buildDirectory.file("reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")

// What every Java project of the build shares: the root project, which is the app, and the modules beside it
// (docs/dev/architecture.md#modules).
allprojects {
    apply(plugin = "java")
    apply(plugin = "jacoco")

    group = "dev.andre"
    // CI passes the version computed from conventional commits; local builds get an
    // honest SNAPSHOT rather than claiming to be a release.
    version = (findProperty("releaseVersion") as String? ?: "0.0.0-SNAPSHOT")

    configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(25) }
    }

    repositories { mavenCentral() }

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.15"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { showExceptions = true }
    }

    sonar {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", combinedCoverageReport.get().asFile.absolutePath)
        }
    }

    // Resolve artifacts before CI fans out into builds and browser tests. Task inputs force
    // verification of every resolvable configuration without compiling or executing tests.
    tasks.register("verifyDependencyChecksums") {
        description = "Verifies dependency checksums across all configurations without compiling."
        group = "verification"
        inputs.files(configurations.filter { it.isCanBeResolved })
        doLast { logger.lifecycle("Dependency checksums verified.") }
    }
}

tasks.named<JacocoReport>("testCodeCoverageReport") {
    reports {
        xml.required = true
    }
}

tasks.check {
    dependsOn(tasks.named("testCodeCoverageReport"))
}

tasks.named("sonar") {
    dependsOn(tasks.named("testCodeCoverageReport"))
}

sonar {
    properties {
        property("sonar.projectKey", "Yukuhu_home-control")
        property("sonar.organization", "yukuhu")
        property("sonar.gradle.scanAll", "true")
        property("sonar.javascript.lcov.reportPaths",
            "build/reports/browser-coverage/lcov.info,build/reports/pr-summary/lcov.info")
    }
}
```

This removes the old `java { toolchain }`, `repositories`, `jacoco {}`, `tasks.jacocoTestReport {}`,
`tasks.test { finalizedBy(…) }` and `tasks.named("sonar") { dependsOn(tasks.jacocoTestReport) }` blocks. Their settings
are in the block above, or replaced by the combined report.

- [ ] **Step 4: Remove the settings the shared block now holds**

Delete this block (lines 124–127 before the edit):

```kotlin
tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showExceptions = true }
}
```

Delete the old `verifyDependencyChecksums` registration at the end of the file, with its two comment lines:

```kotlin
// Resolve artifacts before CI fans out into builds and browser tests. Task inputs force
// verification of every resolvable configuration without compiling or executing tests.
tasks.register("verifyDependencyChecksums") {
    description = "Verifies dependency checksums across all configurations without compiling."
    group = "verification"
    inputs.files(configurations.filter { it.isCanBeResolved })
    doLast { logger.lifecycle("Dependency checksums verified.") }
}
```

- [ ] **Step 5: Point CI at every module's results and the combined report**

In `.github/workflows/ci.yml`, the build job's three uploads become:

```yaml
      - name: Upload Java analysis reports for SonarCloud
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: java-analysis
          # Every module's results, with the paths they have in the repository; the SonarCloud job
          # downloads them into its checkout.
          path: |
            build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml
            **/build/test-results/test/TEST-*.xml
          if-no-files-found: error

      - name: Upload the test report
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: test-report
          path: '**/build/reports/tests/test'

      # Uploaded even when tests fail, which is when the pull request summary needs them most.
      - name: Upload the test results for the pull request summary
        if: always()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: junit-test
          path: '**/build/test-results/test/TEST-*.xml'
          if-no-files-found: ignore
```

In the SonarCloud job, the `java-analysis` download and the scan become:

```yaml
      - uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
        with:
          name: java-analysis
          path: .
```

```yaml
          ./gradlew classes testClasses sonar -x test -x jacocoTestReport -x testCodeCoverageReport \
            "-Dsonar.qualitygate.wait=$REQUIRE_SONAR_QUALITY_GATE"
```

Why `path: .`: with a `**` pattern in its paths, the artifact's root is the working directory. It holds
`build/reports/…`, `build/test-results/…` and `core/build/test-results/…`, so it unpacks into the checkout, not into
`build/`.

- [ ] **Step 6: Keep every module's build directory out of the Docker context**

In `.dockerignore`, the line `build` becomes `**/build`.

- [ ] **Step 7: Build**

Run: `scripts/gradle.sh build`
Expected: exit 0 with no output (`-q`). The deployment tests that parse `ci.yml` pass.

- [ ] **Step 8: Compare the combined report with the old one**

Run:
```bash
python3 .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage.py - .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage-before.xml build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml
```
Expected: two lines with the same `total`; `covered` within a few lines of each other, since timing-dependent paths
vary between runs. The combined report covers the same classes as the old one: there is no module yet.

- [ ] **Step 9: Verify checksums across projects**

Run: `scripts/gradle.sh --dependency-verification strict verifyDependencyChecksums`
Expected: exit 0 with no output. Then run `git status --short gradle/verification-metadata.xml`. Expected: no output.

- [ ] **Step 10: Commit**

```bash
git add build.gradle.kts .github/workflows/ci.yml .dockerignore
git commit -F - <<'EOF'
build: shared project settings and one coverage report for every module

The settings every Java project shares (toolchain, repository, JaCoCo, the JUnit platform, the checksum task and
SonarCloud's coverage path) move into one allprojects block, so a module added beside the app gets them. Gradle's
jacoco-report-aggregation plugin builds one report over the app and its modules, which check and sonar depend on, and
CI uploads every module's test results.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

---

### Task 3: `core` becomes the `:core` module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/build.gradle.kts`
- Create: `core/src/test/resources/junit-platform.properties`
- Modify: `build.gradle.kts` (the `subprojects {}` block after `allprojects {}`; `implementation(project(":core"))`)
- Move: `src/main/java/dev/andre/homecontrol/core/` → `core/src/main/java/dev/andre/homecontrol/core/` (76 classes)
- Move: `src/test/java/dev/andre/homecontrol/core/` → `core/src/test/java/dev/andre/homecontrol/core/` (27 classes)
- Create: `core/src/test/java/dev/andre/homecontrol/core/CompiledWithParameterNamesTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java` (remove `coreDependsOnlyOnTheJdk`, lines 32–36;
  add the guard)
- Delete: `src/test/java/dev/andre/homecontrol/TestArchitectureTest.java`

**Interfaces:**
- Consumes: the `allprojects {}` block and `testCodeCoverageReport` (Task 2); `coverage.py` (Task 1).
- Produces: the project `:core`, with jar `home-control-core-<version>.jar`; the test task `:core:test`; core's
  report `core/build/reports/jacoco/test/jacocoTestReport.xml` from `:core:jacocoTestReport`. Task 4 measures
  `core/src/main/java/dev/andre/homecontrol/core/Device.java`.

- [ ] **Step 1: Write the import guard**

In `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`, add these imports in their sorted places:

```java
import com.tngtech.archunit.core.domain.JavaClasses;
import dev.andre.homecontrol.core.Device;
```

```java
import static org.assertj.core.api.Assertions.assertThat;
```

Add as the class's first member:

```java
    // core is a module of its own, and its classes reach this test as a jar on the classpath. Were they missing from
    // the import, no rule would see a dependency on core, and every rule would still pass.
    @ArchTest
    static void coreClassesAreImported(JavaClasses classes) {
        assertThat(classes.contain(Device.class)).as("the import holds core's classes").isTrue();
    }
```

- [ ] **Step 2: Run the guard before the move**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS. `core` is still in the root project. The guard is for the move; Step 9 shows it failing when `core`'s
jar is left out.

- [ ] **Step 3: Declare the module**

`settings.gradle.kts`:

```kotlin
rootProject.name = "home-control"

// The root project is the app; core, the domain model, is a module beside it (docs/dev/architecture.md#modules).
include("core")
```

`core/build.gradle.kts`:

```kotlin
// The domain model every other part of the application builds on. It depends on the JDK alone: with nothing else on
// its classpath, the compiler keeps Spring and the rest of the application out (docs/dev/architecture.md#modules).
plugins {
    `java-library`
}

base {
    archivesName = "home-control-core"
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

`core/src/test/resources/junit-platform.properties`:

```properties
# The same defaults as the app's tests (src/test/resources/junit-platform.properties): every test method gets 60
# seconds unless it declares a longer @Timeout, and timeouts are off while a debugger is attached.
junit.jupiter.execution.timeout.default = 60 s
junit.jupiter.execution.timeout.mode = disabled_on_debug
```

In `build.gradle.kts`, add after the `allprojects {}` block:

```kotlin
// What only the modules need. `test --tests` runs its filter in every project: a module without a match passes, and
// the app's `test` still fails when nothing matches, so a filter that matches no test anywhere fails the build.
subprojects {
    tasks.withType<Test>().configureEach {
        filter.isFailOnNoMatchingTests = false
    }
}
```

In the root's `dependencies {}` block, add after the two `platform(…)` lines and the Tomcat constraints:

```kotlin
    implementation(project(":core"))
```

- [ ] **Step 4: Move the code**

```bash
mkdir -p core/src/main/java/dev/andre/homecontrol core/src/test/java/dev/andre/homecontrol
git mv src/main/java/dev/andre/homecontrol/core core/src/main/java/dev/andre/homecontrol/core
git mv src/test/java/dev/andre/homecontrol/core core/src/test/java/dev/andre/homecontrol/core
```

Expected: `git status --short` lists 103 renames (`R`), the two new files, and the modified build files.

- [ ] **Step 5: Let the compiler carry the core rules**

In `ArchitectureTest.java`, delete the rule:

```java
    @ArchTest
    static final ArchRule coreDependsOnlyOnTheJdk = classes()
            .that().resideInAPackage("dev.andre.homecontrol.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.andre.homecontrol.core..")
            .because("core is the domain model every other package builds on");
```

If `classes` is no longer used in the file, delete `import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;`.

Delete the test of the tests, whose one rule (core's tests do not use `sources`) is now a compile error:

```bash
git rm -q src/test/java/dev/andre/homecontrol/TestArchitectureTest.java
```

- [ ] **Step 6: Run core's tests in their module**

Run: `scripts/gradle.sh :core:test`
Expected: exit 0. Then `ls core/build/test-results/test/TEST-*.xml | wc -l`. Expected: `27`.

- [ ] **Step 7: Write the failing compiler-settings test**

`core/src/test/java/dev/andre/homecontrol/core/CompiledWithParameterNamesTest.java`:

```java
package dev.andre.homecontrol.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledWithParameterNamesTest {

    @Test
    void parameterNamesAreKeptAsInTheApp() throws NoSuchMethodException {
        // Spring Boot's plugin compiles the app with -parameters, and the app reads parameter names by reflection.
        // core is compiled the same way, so a class behaves the same in either project.
        Parameter host = Hosts.class.getMethod("isValid", String.class).getParameters()[0];

        assertThat(host.isNamePresent()).isTrue();
        assertThat(host.getName()).isEqualTo("host");
    }
}
```

- [ ] **Step 8: Watch it fail, then compile the modules as the app**

Run: `scripts/gradle.sh :core:test --tests 'dev.andre.homecontrol.core.CompiledWithParameterNamesTest'`
Expected: FAIL. `parameterNamesAreKeptAsInTheApp` fails with `Expecting value to be true but was false`.

In `build.gradle.kts`, the `subprojects {}` block becomes:

```kotlin
// What only the modules need: Spring Boot's plugin gives the app's compiler -parameters, which the modules get the
// same way here. `test --tests` runs its filter in every project: a module without a match passes, and the app's
// `test` still fails when nothing matches, so a filter that matches no test anywhere fails the build.
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }
    tasks.withType<Test>().configureEach {
        filter.isFailOnNoMatchingTests = false
    }
}
```

Run: `scripts/gradle.sh :core:test --tests 'dev.andre.homecontrol.core.CompiledWithParameterNamesTest'`
Expected: PASS.

- [ ] **Step 9: Watch the import guard fail without `core`'s jar**

Temporarily change `ArchitectureTest`'s annotation to:

```java
@AnalyzeClasses(packages = "dev.andre.homecontrol",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
```

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: FAIL, with `coreClassesAreImported` failing on `[the import holds core's classes]`. This also proves that
`core` reaches the test as a jar. Other rules may fail too.

Restore the annotation:

```java
@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.DoNotIncludeTests.class)
```

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS.

- [ ] **Step 10: Run the build**

Run: `scripts/gradle.sh build`
Expected: exit 0. `git status --short gradle/verification-metadata.xml` prints nothing; see Global Constraints if it
does.

- [ ] **Step 11: Check that app tests count towards core's coverage**

Run: `scripts/gradle.sh :core:jacocoTestReport`, then:

```bash
python3 .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/coverage.py dev/andre/homecontrol/core core/build/reports/jacoco/test/jacocoTestReport.xml build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml
```

Expected: two lines with the same `total` (both reports count core's lines). The combined report's `covered` is
greater: app tests cover core lines that core's own tests do not.

- [ ] **Step 12: Check the boot jar**

Run: `scripts/gradle.sh bootJar`, then:

```bash
unzip -l build/libs/home-control-0.0.0-SNAPSHOT.jar | grep 'BOOT-INF/lib/home-control-core'
unzip -l build/libs/home-control-0.0.0-SNAPSHOT.jar | grep -c 'BOOT-INF/classes/dev/andre/homecontrol/core/'
```

Expected: the first prints one line ending in `BOOT-INF/lib/home-control-core-0.0.0-SNAPSHOT.jar`. The second prints
`0`.

- [ ] **Step 13: Check the test filters**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ErrorAdviceTest'`
Expected: exit 0. `:core:test` matches nothing and passes; the app's `test` runs `ErrorAdviceTest`.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.NoSuchTest'`
Expected: exit 1, with `No tests found for given includes: [dev.andre.homecontrol.NoSuchTest](--tests filter)` for
task `:test`.

Run: `scripts/gradle.sh :core:test --tests 'dev.andre.homecontrol.core.HostsTest'`
Expected: exit 0.

- [ ] **Step 14: Run the browser tests**

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium`
Expected: exit 0, 68 tests passed.

- [ ] **Step 15: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/build.gradle.kts core/src/test/resources/junit-platform.properties core/src/test/java/dev/andre/homecontrol/core/CompiledWithParameterNamesTest.java src/test/java/dev/andre/homecontrol/ArchitectureTest.java
git commit -F - <<'EOF'
refactor: core becomes a Gradle module of its own

The domain model moves to core/, a java-library project with nothing on its classpath but the JDK, so the compiler
refuses what ArchitectureTest's coreDependsOnlyOnTheJdk and TestArchitectureTest's rule reported after compiling;
both go. ArchitectureTest gains a guard that core's classes, now a jar on its classpath, are still imported.

The module compiles with -parameters, as Spring Boot's plugin compiles the app, and its jar is home-control-core so
that it cannot collide with a library's jar inside the boot jar. `test --tests` runs in every project: a module
without a match passes, the app's test task still fails when nothing matches.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

The `git mv` and `git rm` changes are already staged. Check `git show --stat HEAD`. Expected: 103 renames, the
deletion of `TestArchitectureTest.java`, three new files (`core/build.gradle.kts`, core's
`junit-platform.properties`, `CompiledWithParameterNamesTest.java`) and three modified files (`settings.gradle.kts`,
`build.gradle.kts`, `ArchitectureTest.java`).

---

### Task 4: Build times after the split, and the docs

**Files:**
- Modify: `AGENTS.md` ("Architecture rules", "Where things live")
- Modify: `docs/dev/architecture.md` (package map's `core` row, a new "Modules" section, "Package rules", "Progress
  measures")
- Modify: `docs/dev/testing.md` ("Running tests")
- Modify: `docs/dev/ci-and-releases.md` ("Jobs" table, "CI quality gate")
- Create: `docs/adr/0006-gradle-modules.md`
- Modify: `docs/adr/README.md`

**Interfaces:**
- Consumes: `measure.py` and the "Before" medians in `measures.md` (Task 1); the `:core` project (Task 3).

- [ ] **Step 1: Measure the split**

Run in the background, from the repository root (about 45 minutes; nothing else may build meanwhile):

```bash
python3 .superpowers/sdd/2026-10-01-phase-4-pr1-core-module/measure.py "After: core is a module" src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java core/src/main/java/dev/andre/homecontrol/core/Device.java
```

Expected: exit 0, and `measures.md` holds both sections. Write the six medians down as minutes and seconds, for
example `5 min 12 s`; Steps 3 and 7 use them.

- [ ] **Step 2: `AGENTS.md`**

In "Architecture rules", the first bullet becomes:

```markdown
- Only adapters speak device protocols, and only sources speak content APIs. `ArchitectureTest` enforces the package
  rules, and the Gradle modules what each module may depend on; see [Architecture](docs/dev/architecture.md).
```

In "Where things live", add as the first bullet:

```markdown
- Modules: the root project is the app. `core/` holds the domain model (`dev.andre.homecontrol.core`), a Gradle
  module that depends on the JDK alone; run its tests with `scripts/gradle.sh :core:test`. See
  [Modules](docs/dev/architecture.md#modules).
```

- [ ] **Step 3: `docs/dev/architecture.md`**

In the package map, the `core` row's last sentence `It depends only on the JDK.` becomes
`` It is the Gradle module `core`, which depends only on the JDK. ``

Add this section after the package map's table, before "## Dependencies":

```markdown
## Modules

The build has two Gradle projects. Each compiles against only what its build file declares, so the compiler refuses an
import that crosses a module's boundary.

| Module | Directory | Holds | Depends on |
| --- | --- | --- | --- |
| `core` | `core/` | the package `core` | the JDK |
| app | the repository root | every other package; it builds the boot jar | `core`, Spring Boot and the libraries in `build.gradle.kts` |

What every project shares (Java 25, Maven Central, JaCoCo, the JUnit platform, the checksum task) is in the
`allprojects {}` block of the root `build.gradle.kts`. A module compiles with `-parameters`, as Spring Boot's plugin
compiles the app, and its jar is named `home-control-<module>`. One JaCoCo report,
`build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml`, covers every module's classes with every
module's tests, because the app's tests exercise much of `core`; SonarCloud reads it for every module. Why the root
project stays the app: [ADR 0006](../adr/0006-gradle-modules.md).
```

In "Package rules", the first table row becomes:

```markdown
| `core` depends only on the JDK | the `core` module: nothing else is on its classpath |
```

Replace the paragraph that begins `` `src/test/java/dev/andre/homecontrol/TestArchitectureTest.java` checks one rule ``
with:

```markdown
`core`'s tests sit in the `core` module and compile against `core` alone. A source's own types are tested in its
module; tests that need every module, such as the route keys and the application's preference ladder, sit in
`playback`.
```

In "Progress measures", add three rows at the end of the table. Each "Now" cell holds the "After" median and, in
brackets, the "Before" median from `measures.md`:

```markdown
| Clean `build` | not measured | <after> (<before> before `core` was a module) |
| `build` after a change to one app class | not measured | <after> (<before> before `core` was a module) |
| `build` after a change to one `core` class | not measured | <after> (<before> before `core` was a module) |
```

Then add this paragraph below the table's existing paragraphs:

```markdown
The build times are the median of three runs of `scripts/gradle.sh` on the same four-CPU machine, without the build
cache. A change moves every line number of `web/ErrorAdvice.java` or `core/Device.java`, so the class file changes
and no signature does. The roadmap splits the app further only if these numbers show that it would pay.
```

- [ ] **Step 4: `docs/dev/testing.md`**

In "Running tests", the bullets for one class and for a failure's details become:

```markdown
- One class of the app: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.ErrorAdviceTest'`. One class of
  `core`: `scripts/gradle.sh :core:test --tests 'dev.andre.homecontrol.core.ActionTest'`. `test --tests` runs its
  filter in every module: a module without a match passes, and the app's `test` fails when nothing matches, so a
  filter that matches no test anywhere fails the build.
- A failure's details: `grep -A20 '<failure' build/test-results/test/*.xml core/build/test-results/test/*.xml`.
```

In the timeout bullet, `` (`src/test/resources/junit-platform.properties`) `` becomes
`` (`junit-platform.properties` in `src/test/resources` and `core/src/test/resources`) ``.

- [ ] **Step 5: `docs/dev/ci-and-releases.md`**

In the "Jobs" table, the "Build and test" row becomes:

```markdown
| Build and test | Runs `./gradlew build` with every module's tests and uploads their results and reports, with one coverage report for all modules. |
```

In "CI quality gate", after the first paragraph's sentence that ends `…its build stays red until a separate scan path
is configured.`, add:

```markdown
The Java coverage it reads is one JaCoCo report over every module, which the build job
uploads with every module's test results.
```

- [ ] **Step 6: The ADR**

`docs/adr/0006-gradle-modules.md`:

```markdown
# ADR: Gradle modules, with the root project as the app

**Date:** 2026-10-01
**Status:** Accepted
**Context:** Roadmap Phase 4 (Gradle modules), first pull request.
**Spec:** `docs/superpowers/specs/2026-10-01-phase-4-gradle-modules-design.md`.

## Decision

- The build splits into Gradle modules along package rules it already checked: `core`, the domain model, which
  depends on the JDK alone, and, in the second pull request, `protocols`, the Spring-free wire libraries. The compiler
  then refuses what `ArchitectureTest` reported after compiling. ArchUnit keeps the rules inside the app.
- The root project stays the app. It keeps `src/main`, `src/test` and `src/e2e` and builds the boot jar, and the
  modules are subprojects beside it. Only the moved files change paths; CI, Docker, the scripts and the guides keep
  theirs. (Rejected: every project in a subdirectory, which moves all ~1,500 files; source sets in one project, which
  give no per-module test avoidance.)
- What every project shares sits in one `allprojects {}` block of the root build script. (Rejected: a `buildSrc`
  convention plugin, which adds the `kotlin-dsl` build dependencies and their checksums for two small modules.)
- One JaCoCo report covers every module's classes with every module's tests, and SonarCloud reads it for every
  module, because the app's tests exercise much of the modules' code.
- A module's jar is named `home-control-<module>`, so it cannot collide with a library's jar inside the boot jar. A
  module compiles with `-parameters`, as Spring Boot's plugin compiles the app.

## Consequences

- A class can move into a module only when everything it uses is in that module or in a module below it.
- `test --tests` runs its filter in every module. A module without a match passes, and the app's `test` fails when
  nothing matches, so a filter for a class of `core` runs with `:core:test --tests`.
- Whether to split the app further is decided from the build times measured before and after each pull request
  (`docs/dev/architecture.md`, "Progress measures").
```

In `docs/adr/README.md`, add after the 0005 row:

```markdown
| [0006](0006-gradle-modules.md) | Gradle modules, with the root project as the app | Accepted | 2026-10-01 |
```

- [ ] **Step 7: Check the docs**

Run: `grep -rn "TestArchitectureTest\|coreDependsOnlyOnTheJdk\|jacocoTestReport.xml" AGENTS.md docs/dev docs/adr .github`
Expected: no output.

Run: `grep -n "<after>\|<before>" docs/dev/architecture.md`
Expected: no output. Every measure cell holds a number.

- [ ] **Step 8: Commit**

```bash
git add AGENTS.md docs/dev/architecture.md docs/dev/testing.md docs/dev/ci-and-releases.md docs/adr/0006-gradle-modules.md docs/adr/README.md
git commit -F - <<'EOF'
docs: the core module in the guides, with build times and ADR 0006

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

---

## After the tasks

- The final review package covers `1a6938d..HEAD`.
- In the pull request, the description carries `measures.md`'s two sections.
- **SonarCloud check (Review Focus 4):** once CI has run, ask SonarCloud for the coverage of a `core` file:

  ```
  https://sonarcloud.io/api/measures/component?component=Yukuhu_home-control:core/src/main/java/dev/andre/homecontrol/core/Hosts.java&pullRequest=<N>&metricKeys=coverage
  ```

  Expected: a coverage value, not an empty measure.
- **CodeQL:** a moved file can re-raise an alert that was dismissed at its old path. Dismissing one is the user's
  decision.
