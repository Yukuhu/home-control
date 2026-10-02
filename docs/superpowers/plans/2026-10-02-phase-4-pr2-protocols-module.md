# Phase 4 PR 2: The Protocols Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Spring-free wire libraries become the Gradle module `protocols`, with the fakes and recordings the
app's tests share as its test fixtures, and the build times measured before and after.

**Architecture:** `protocols/` is a `java-library` and `java-test-fixtures` subproject on top of `core`, with the
protobuf plugin. The root (the app) depends on it with `implementation(project(":protocols"))` and gets the shared
fakes with `testImplementation(testFixtures(project(":protocols")))`. Wake-on-LAN's Spring configuration moves into
the app first, and every test reads a recording from the classpath before the recordings move.

**Tech Stack:** Gradle 9.8 (Kotlin DSL, version catalog), Java 25, protobuf Gradle plugin 0.10.0, Jackson 3, Bouncy
Castle, SLF4J, Spring Boot 4.1.1 (app and test support only), ArchUnit 1.5.1, JUnit 5, AssertJ, Awaitility.

**Spec:** `docs/superpowers/specs/2026-10-01-phase-4-gradle-modules-design.md`, section 2 (`protocols`) and section 3
(build times). PR 1 (#166, `2b7662c`) built the scaffolding and `core`.

## Global Constraints

- Main code keeps its package and class names, except `adapters.net.NetConfiguration`, which becomes
  `adapters.support.WakeOnLanConfiguration`. The setting `home-control.wake-on-lan` keeps its name.
- Two test fakes change package: `FakeUpnpRenderer` moves to `adapters.upnp.protocol`, and `FakeSsdpResponder` to
  `discovery.ssdp.protocol`.
- The module's jar is `home-control-protocols` (`base.archivesName`).
- **Libraries:** `protocols` uses Jackson, BouncyCastle, protobuf-java and the SLF4J API, with versions from the
  Spring Boot BOM and the Jackson BOM.
  - A library whose types appear in a public signature is `api`: Jackson (`MediaStatus.parse(JsonNode)`) and
    protobuf-java (the app's `AndroidTvKeys`). The others are `implementation`.
  - The root drops the protobuf plugin (now `apply false`), `protobuf-java` and `bcpkix`. It keeps `bcprov`, which
    `crypto.Argon2id` uses.
- No new artifacts: `gradle/verification-metadata.xml` stays unchanged. Every library above is already verified,
  including `spring-boot-test` 4.1.1 and `logback-classic` 1.5.38.
- Nothing a user sees changes: no `/data` format, HTTP endpoint, page or setting.
- Every package rule stays strict.
- `scripts/gradle.sh build` and `scripts/e2e.sh -Pe2eBrowsers=chromium` pass at the end.
- Commits follow Conventional Commits and end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
  ```
- Stage only the files you changed (`git add <paths>`, never `git add -A`). `git mv` stages its own moves.
- **Rulings carried into this plan:**
  - **"Before" times:** the baseline for the clean build and for app and core changes is PR 1's "after" medians:
    245 s, 220 s and 222 s. Main has changed since only by one test line. Task 1 measures only a protocol-class
    change.
  - **`IcsFixtureContractTest` splits.** Its parser checks move with the ICS parser. Its two checks of what the
    sports source makes of the calendars use `CalendarSchedule`, `SportsItems` and `SportsSettings`, so they stay in
    the app as `sources.sports.calendar.CalendarFixtureContractTest`.
  - **The HTTP test kit stays.** `Request` and `TestTls` move to the test fixtures, as the spec says. `FakeHttpServer`,
    `Response` and `FakeHttpServerTest` (which needs Awaitility) stay in the app's `testsupport`.
  - **`protocols`' tests get `spring-boot-test` and Logback.** `RemoteConnectionTest` checks a WARN line through
    Spring Boot's `OutputCaptureExtension`. A `logback-test.xml` keeps the module's tests at INFO, as the app's are.
  - **Whole recording directories move**: `cast`, `ics`, `sonos`, `ssdp`, `tizen`, `upnp` and `webos`. Protocol tests
    or shared fakes read each of them.
  - **"Nearly a clean build's time"** (spec section 3) means at least 80% of it.

## Review Focus

1. **The Wake-on-LAN sender after its configuration moves.** Every TV adapter wakes a sleeping TV through the one
   `WakeOnLan` bean. Expected: it still sends magic packets to the configured address and port, and a bad port still
   fails startup. Pinned by `WakeOnLanConfigurationTest` (Task 2) and the full-application tests.
2. **Test fixtures leaking into production.** Expected:
   - the boot jar holds `home-control-protocols-….jar` and no `-test-fixtures` jar;
   - `ArchitectureTest` does not check the fakes as production code.

   Pinned by the boot-jar listing and the `DoNotIncludeGradleTestFixtures` RED run (Task 4), and the guard's
   test-fixture assertion (Task 5).
3. **A recording read by a path that depends on the module, or a stale copy left behind.** Expected: every test reads
   the one copy from the classpath, and no moved directory is left under `src/test/resources/fixtures`. Pinned by
   `FixturesTest` and the grep checks (Tasks 3 and 4).
4. **SonarCloud counting the test fixtures as production code, or losing protocols' coverage.** Expected: the test
   fixtures are not in `:protocols`' `sonar.sources`, and the combined report credits app tests to protocol classes.
   Pinned by the Sonar property dump and the coverage comparison (Task 4), and after the PR by SonarCloud's measure for
   a protocol file.
5. **A class or test of a protocols package left in the app,** escaping the compiler as a `core` class would. Expected:
   ArchUnit refuses it. Pinned by `protocolsLiveInTheirModuleAlone` and
   `TestArchitectureTest.protocolTestsSitInTheProtocolsModule`, with RED runs against scratch classes (Task 5).

---

### Task 1: Baseline for a protocol-class change

Nothing is committed. The plan's workspace is `.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/`
(git-ignored).

**Files:**
- Create: `.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/measure.py` (not committed)
- Create: `.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/coverage.py` (not committed)
- Output: `.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/measures.md`

**Interfaces:**
- Produces:
  - `measure.py LABEL [clean] [PATH ...]` appends `## LABEL` and one median per item to `measures.md`; Task 6 runs it
    again.
  - `coverage.py PREFIX FILE...` prints a JaCoCo report's line counts; Task 4 uses it.

- [ ] **Step 1: Write the measuring script**

`.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/measure.py`:

```python
"""Times `scripts/gradle.sh build` for Phase 4's measures. "clean" times a clean build; a path times a build after a
change that moves every line number of that class, so its bytecode changes and no signature does. Three timed runs
each, without the build cache; appends the runs and their medians to measures.md beside this script.

Usage: measure.py LABEL [clean] [PATH ...] (paths relative to the repository root)."""
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


def main(label, *items):
    results = []
    for item in items:
        if item == "clean":
            results.append(line("clean `build`", [build("clean", "build") for _ in range(3)]))
        else:
            results.append(line(f"`build` after a change to `{item}`", after_change(item)))
    text = f"## {label}\n\n" + "\n".join(results) + "\n\n"
    with open(HERE / "measures.md", "a", encoding="utf-8") as out:
        out.write(text)
    print(text, flush=True)


main(*sys.argv[1:])
```

- [ ] **Step 2: Write the coverage script**

`.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/coverage.py`:

```python
"""Prints the line counts of JaCoCo XML reports: of the whole report with PREFIX "-", else of the packages whose
path starts with PREFIX (for example dev/andre/homecontrol/adapters/cast/protocol).

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

- [ ] **Step 3: Measure a protocol-class change on main**

Run in the background from the repository root. It takes about 15 minutes, and nothing else may build meanwhile.

```bash
python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/measure.py "Before: main 2b7662c, core a module" src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/DidlLite.java
```

Expected: exit 0, and `measures.md` holds one line under `## Before: main 2b7662c, core a module`. If a run fails on
a flaky test, the script stops. Check `git status`, restore the class with `git checkout -- <path>` if it was left
changed, and rerun.

---

### Task 2: Wake-on-LAN's Spring configuration moves to `adapters.support`

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfigurationTest.java`
- Move: `src/main/java/dev/andre/homecontrol/adapters/net/NetConfiguration.java` →
  `src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfiguration.java`
- Move: `src/main/java/dev/andre/homecontrol/adapters/net/WakeOnLanProperties.java` →
  `src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanProperties.java`

**Interfaces:**
- Produces: `adapters.support.WakeOnLanConfiguration` (`@Bean WakeOnLan wakeOnLan(WakeOnLanProperties)`) and
  `adapters.support.WakeOnLanProperties(String broadcastAddress, int port)`.
- After this task, `adapters.net` holds no Spring class, which Task 4 relies on.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfigurationTest.java`:

```java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.FakeWakeOnLanReceiver;
import dev.andre.homecontrol.adapters.net.WakeOnLan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WakeOnLanConfigurationTest {

    private static final String MAC = "AA:BB:CC:DD:EE:FF";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WakeOnLanConfiguration.class);

    @Test
    void theSenderWakesAtTheConfiguredAddressAndPort() throws Exception {
        try (FakeWakeOnLanReceiver receiver = new FakeWakeOnLanReceiver()) {
            context.withPropertyValues(
                            "home-control.wake-on-lan.broadcast-address=" + receiver.address().getHostString(),
                            "home-control.wake-on-lan.port=" + receiver.port())
                    .run(started -> {
                        started.getBean(WakeOnLan.class).wake(MAC);

                        assertThat(receiver.nextPacket()).isEqualTo(WakeOnLan.magicPacket(MAC));
                    });
        }
    }

    @Test
    void aPortOutsideTheRangeFailsStartup() {
        context.withPropertyValues("home-control.wake-on-lan.port=0")
                .run(started -> assertThat(started).hasFailed());
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.WakeOnLanConfigurationTest'`
Expected: FAIL. Compilation fails with `cannot find symbol` for `WakeOnLanConfiguration`.

- [ ] **Step 3: Move the two classes**

```bash
git mv src/main/java/dev/andre/homecontrol/adapters/net/NetConfiguration.java src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfiguration.java
git mv src/main/java/dev/andre/homecontrol/adapters/net/WakeOnLanProperties.java src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanProperties.java
```

`src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfiguration.java` becomes:

```java
package dev.andre.homecontrol.adapters.support;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/** The one Wake-on-LAN sender the TV modules share, aimed at the configured broadcast address and port. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WakeOnLanProperties.class)
public class WakeOnLanConfiguration {

    @Bean
    public WakeOnLan wakeOnLan(WakeOnLanProperties properties) {
        return new WakeOnLan(new InetSocketAddress(properties.broadcastAddress(), properties.port()));
    }
}
```

In `src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanProperties.java`, the first line
`package dev.andre.homecontrol.adapters.net;` becomes `package dev.andre.homecontrol.adapters.support;`. Nothing else
changes.

- [ ] **Step 4: Run it to see it pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.support.WakeOnLanConfigurationTest'`
Expected: PASS, 2 tests.

Run: `grep -rn "NetConfiguration\|adapters.net.WakeOnLanProperties" src docs AGENTS.md`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfigurationTest.java src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanConfiguration.java src/main/java/dev/andre/homecontrol/adapters/support/WakeOnLanProperties.java
git commit -F - <<'EOF'
refactor: Wake-on-LAN's Spring configuration moves to adapters.support

adapters.net keeps the sender itself, which the protocols module will take; its Spring configuration and properties
record move to adapters.support, beside WakeOnLanPower. NetConfiguration becomes WakeOnLanConfiguration. The setting
home-control.wake-on-lan keeps its name.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

---

### Task 3: Recordings are read from the classpath

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/testsupport/Fixtures.java`
- Create: `src/test/java/dev/andre/homecontrol/testsupport/FixturesTest.java`
- Create: `.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/read_from_classpath.py` (not committed)
- Modify: the 26 test files that read a recording by path (the script lists them)

**Interfaces:**
- Produces:
  - `Fixtures.read(String name) throws IOException` returns UTF-8 text.
  - `Fixtures.bytes(String name) throws IOException` returns `byte[]`.
  - Both read `/fixtures/` + `name` from the classpath, and a missing recording throws `FileNotFoundException`
    naming `fixtures/<name>`.
  - `FakeUpnpRenderer.resource(String)` and `Layout.descriptionFixture()` now take names under `fixtures/`, for
    example `upnp/renderer-description.xml`.

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/homecontrol/testsupport/FixturesTest.java`:

```java
package dev.andre.homecontrol.testsupport;

import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixturesTest {

    @Test
    void aRecordingIsReadAsText() throws IOException {
        assertThat(Fixtures.read("upnp/didl-track.xml")).contains("Bunny Song");
    }

    @Test
    void aRecordingIsReadAsItsBytes() throws IOException {
        assertThat(Fixtures.bytes("ssdp/lg-description.xml"))
                .startsWith("<?xml".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aMissingRecordingFailsWithItsName() {
        assertThatThrownBy(() -> Fixtures.read("upnp/no-such-recording.xml"))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("fixtures/upnp/no-such-recording.xml");
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FixturesTest'`
Expected: FAIL. Compilation fails with `cannot find symbol` for `Fixtures`.

- [ ] **Step 3: Write `Fixtures`**

`src/test/java/dev/andre/homecontrol/testsupport/Fixtures.java`:

```java
package dev.andre.homecontrol.testsupport;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Recorded device and service responses, read from the classpath under {@code fixtures/}. A module's tests run in the
 * module's own directory, so a path relative to it would find only that module's recordings; the classpath holds the
 * recordings of every module the tests depend on.
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** A recording as UTF-8 text, for example {@code read("upnp/didl-track.xml")}. */
    public static String read(String name) throws IOException {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    /** A recording's bytes, as stored. */
    public static byte[] bytes(String name) throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new FileNotFoundException("No recording fixtures/" + name + " on the classpath");
            }
            return in.readAllBytes();
        }
    }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.testsupport.FixturesTest'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Turn every path read of a recording into a classpath read**

`.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/read_from_classpath.py`:

```python
"""Turns every read of a recording by a path under src/test/resources/fixtures into a read through Fixtures, and
points FakeUpnpRenderer's recordings at names under fixtures/."""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ROOTS = [REPO / "src/test/java", REPO / "src/e2e/java"]
IMPORT = "import dev.andre.homecontrol.testsupport.Fixtures;"
READ = re.compile(r'Files\.readString\(Path\.of\("src/test/resources/fixtures/([^"]*)"((?: \+ \w+)?)\)\)')
BYTES = re.compile(r'Files\.readAllBytes\(Path\.of\("src/test/resources/fixtures/([^"]*)"((?: \+ \w+)?)\)\)')
EXACT = {
    "adapters/upnp/FakeUpnpRenderer.java": [
        ('"fixtures/upnp/renderer-description.xml"', '"upnp/renderer-description.xml"'),
        ('resource("fixtures/upnp/rendering-control-scpd.xml")', 'resource("upnp/rendering-control-scpd.xml")'),
        ('return Files.readString(Path.of("src/test/resources/" + name));', "return Fixtures.read(name);"),
    ],
    "adapters/sonos/FakeSonosPlayer.java": [
        ('"fixtures/ssdp/sonos-description.xml"', '"ssdp/sonos-description.xml"'),
    ],
}


def add_import(text, statement):
    lines = text.split("\n")
    imports = [n for n, line in enumerate(lines) if line.startswith("import ") and not line.startswith("import static ")]
    ours = [n for n in imports if lines[n].startswith("import dev.andre.")]
    before = [n for n in ours if lines[n] < statement]
    if before:
        at = before[-1] + 1
    elif ours:
        at = ours[0]
    else:
        at = imports[0]
    lines.insert(at, statement)
    return "\n".join(lines)


def drop_unused(text):
    body = "\n".join(line for line in text.split("\n") if not line.startswith("import "))
    for name in ("Files", "Path"):
        statement = f"import java.nio.file.{name};\n"
        if statement in text and not re.search(r"\b" + name + r"\b", body):
            text = text.replace(statement, "")
    return text


changed = []
for root in ROOTS:
    for path in sorted(root.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        new = READ.sub(r'Fixtures.read("\1"\2)', text)
        new = BYTES.sub(r'Fixtures.bytes("\1"\2)', new)
        for old, replacement in EXACT.get(path.as_posix().split("dev/andre/homecontrol/", 1)[-1], []):
            assert old in new, (path, old)
            new = new.replace(old, replacement)
        if new == text:
            continue
        if IMPORT not in new and "package dev.andre.homecontrol.testsupport;" not in new:
            new = add_import(new, IMPORT)
        path.write_text(drop_unused(new), encoding="utf-8")
        changed.append(path.relative_to(REPO).as_posix())
print("\n".join(changed))
print(len(changed), "files")
```

Run: `python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/read_from_classpath.py`
Expected: `26 files`, among them `FakeUpnpRenderer.java`, `FakeSonosPlayer.java`, `FakeSsdpResponder.java`,
`FakeTizenServer.java`, `FakeSsapServer.java` and `UpnpSessionTest.java`.

Run: `grep -rnE 'src/test/resources/(fixtures/(cast|ics|sonos|ssdp|tizen|upnp|webos)|" \+)' src/test/java src/e2e/java`
Expected: no output.

Run: `git diff --stat`
Expected: 26 test files changed (plus nothing else tracked), every one a few lines.

- [ ] **Step 6: Run the tests**

Run: `scripts/gradle.sh test`
Expected: exit 0.

- [ ] **Step 7: Commit**

Stage `Fixtures.java`, `FixturesTest.java` and every file the script listed, by name. Then:

```bash
git commit -F - <<'EOF'
test: recordings are read from the classpath

Fixtures.read and Fixtures.bytes read fixtures/<name> from the classpath. Tests read the recordings that protocol
tests share by a path relative to the working directory, which a test running in another module's directory would
not find; they now read them through Fixtures, and FakeUpnpRenderer takes recording names under fixtures/.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

---

### Task 4: The `protocols` module

**Files:**
- Modify: `settings.gradle.kts`, `build.gradle.kts`
- Create: `protocols/build.gradle.kts`
- Create: `protocols/src/test/resources/junit-platform.properties`
- Create: `protocols/src/test/resources/logback-test.xml`
- **Move to the module (main code):** the 73 main classes of
  - `adapters/{androidtv,cast,sonos,tizen,upnp,webos}/protocol`;
  - `adapters/net`;
  - `discovery/ssdp/protocol`;
  - `sources/sports/ics`;

  plus `src/main/proto`.
- **Move to the module (tests):** the tests of those packages, into `protocols/src/test/java`.
- **Move to the test fixtures** (`protocols/src/testFixtures/java`):
  - the eight shared fakes;
  - `FakeUpnpRenderer`, which changes package;
  - `FakeSsdpResponder`, which changes package;
  - `Request`, `TestTls` and `Fixtures`.
- **Move the recordings:** `src/test/resources/fixtures/{cast,ics,sonos,ssdp,tizen,upnp,webos}` go to
  `protocols/src/testFixtures/resources/fixtures/`.
- Modify: `protocols/src/test/java/dev/andre/homecontrol/sources/sports/ics/IcsFixtureContractTest.java`
- Create: `src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFixtureContractTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java` (the import options)
- Modify: the users of `FakeUpnpRenderer` and `FakeSsdpResponder` (imports only)
- Create (not committed): `.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/move_protocols.py`,
  `repackage_fakes.py` and `sonar-props.init.gradle`

**Interfaces:**
- Consumes: `WakeOnLanConfiguration` in `adapters.support` (Task 2); `Fixtures` (Task 3); `coverage.py` (Task 1).
- Produces:
  - the project `:protocols`, with jar `home-control-protocols-<version>.jar` and test-fixtures jar
    `home-control-protocols-<version>-test-fixtures.jar`;
  - the test task `:protocols:test`;
  - `FakeUpnpRenderer` in `dev.andre.homecontrol.adapters.upnp.protocol` and `FakeSsdpResponder` in
    `dev.andre.homecontrol.discovery.ssdp.protocol`;
  - `ArchitectureTest` importing with `DoNotIncludeTests` and `DoNotIncludeGradleTestFixtures`.

  Task 5 adds guards over these; Task 6 measures
  `protocols/src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/DidlLite.java`.

- [ ] **Step 1: Declare the module**

`settings.gradle.kts` becomes:

```kotlin
rootProject.name = "home-control"

// The root project is the app. core, the domain model, and protocols, the Spring-free wire libraries, are modules
// beside it (docs/dev/architecture.md#modules).
include("core", "protocols")
```

`protocols/build.gradle.kts`:

```kotlin
// The Spring-free wire libraries: the device protocols, adapters.net, SSDP's wire code and the ICS parser. With
// nothing but core and these libraries on its classpath, the compiler keeps Spring and the rest of the application
// out (docs/dev/architecture.md#modules). Its test fixtures hold the fakes and recordings the app's tests share.
plugins {
    `java-library`
    `java-test-fixtures`
    id("com.google.protobuf")
}

base {
    archivesName = "home-control-protocols"
}

dependencies {
    api(project(":core"))
    // Versions for everything below; the Jackson BOM raises Jackson above the version Spring Boot manages
    // (gradle/libs.versions.toml says why).
    api(platform(libs.spring.boot.dependencies))
    api(platform(libs.jackson.bom))
    // Parsers take and return Jackson trees, and the app maps keys to the generated protobuf classes: both are part
    // of this module's API.
    api("tools.jackson.core:jackson-databind")
    api(libs.protobuf.java)
    implementation(libs.bouncycastle.bcpkix)
    implementation("org.slf4j:slf4j-api")

    // TestTls signs the fakes' self-signed certificates.
    testFixturesImplementation(libs.bouncycastle.bcpkix)

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    // OutputCaptureExtension, for a test that checks what a protocol logs; Logback writes that log.
    testImplementation("org.springframework.boot:spring-boot-test")
    testRuntimeOnly("ch.qos.logback:logback-classic")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
}
```

`protocols/src/test/resources/junit-platform.properties`:

```properties
# The same defaults as the app's tests (src/test/resources/junit-platform.properties): every test method gets 60
# seconds unless it declares a longer @Timeout, and timeouts are off while a debugger is attached.
junit.jupiter.execution.timeout.default = 60 s
junit.jupiter.execution.timeout.mode = disabled_on_debug
```

`protocols/src/test/resources/logback-test.xml`:

```xml
<configuration>
    <!-- The protocols' tests log at INFO, as the app's do; a test can check what a protocol logs. -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

- [ ] **Step 2: Point the root build at the module**

In `build.gradle.kts`:

- In `plugins {}`, `alias(libs.plugins.protobuf)` becomes:

  ```kotlin
      // Applied by protocols; declared here so that it loads with the build classpath's raised versions.
      alias(libs.plugins.protobuf) apply false
  ```

- In `dependencies {}`:
  - after `implementation(project(":core"))`, add `implementation(project(":protocols"))`;
  - delete `implementation(libs.protobuf.java)` and `implementation(libs.bouncycastle.bcpkix)`;
  - the bcprov comment becomes `// Argon2id for the login hash and the HOME_CONTROL_SECRET key.`;
  - after `testImplementation("org.springframework.boot:spring-boot-webmvc-test")`, add:

    ```kotlin
        // The fakes and recordings the protocol tests share with the app's tests and browser tests.
        testImplementation(testFixtures(project(":protocols")))
    ```

- Delete the `protobuf { protoc { … } }` block.

- [ ] **Step 3: Move the code, tests, fakes and recordings**

`.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/move_protocols.py`:

```python
"""Moves protocols' code, tests, test fixtures and recordings with git mv."""
import os
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
P = "dev/andre/homecontrol/"
PACKAGES = ["adapters/androidtv/protocol", "adapters/cast/protocol", "adapters/sonos/protocol",
            "adapters/tizen/protocol", "adapters/upnp/protocol", "adapters/webos/protocol", "adapters/net",
            "discovery/ssdp/protocol", "sources/sports/ics"]
SHARED_FAKES = ["adapters/androidtv/protocol/FakePairingServer.java",
                "adapters/androidtv/protocol/FakeRemoteServer.java",
                "adapters/androidtv/protocol/RefusingPairingServer.java",
                "adapters/cast/protocol/FakeCastReceiver.java",
                "adapters/net/FakeWakeOnLanReceiver.java",
                "adapters/net/FakeWebSocketServer.java",
                "adapters/tizen/protocol/FakeTizenServer.java",
                "adapters/webos/protocol/FakeSsapServer.java"]
FROM_APP_TESTS = {"adapters/upnp/FakeUpnpRenderer.java": "adapters/upnp/protocol/FakeUpnpRenderer.java",
                  "discovery/ssdp/FakeSsdpResponder.java": "discovery/ssdp/protocol/FakeSsdpResponder.java",
                  "testsupport/Request.java": "testsupport/Request.java",
                  "testsupport/TestTls.java": "testsupport/TestTls.java",
                  "testsupport/Fixtures.java": "testsupport/Fixtures.java"}
RECORDINGS = ["cast", "ics", "sonos", "ssdp", "tizen", "upnp", "webos"]


def mv(source, target):
    (REPO / target).parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "mv", source, target], cwd=REPO, check=True)


for package in PACKAGES:
    mv(f"src/main/java/{P}{package}", f"protocols/src/main/java/{P}{package}")
    mv(f"src/test/java/{P}{package}", f"protocols/src/test/java/{P}{package}")
mv("src/main/proto", "protocols/src/main/proto")
for fake in SHARED_FAKES:
    mv(f"protocols/src/test/java/{P}{fake}", f"protocols/src/testFixtures/java/{P}{fake}")
for source, target in FROM_APP_TESTS.items():
    mv(f"src/test/java/{P}{source}", f"protocols/src/testFixtures/java/{P}{target}")
mv(f"src/test/java/{P}testsupport/FixturesTest.java", f"protocols/src/test/java/{P}testsupport/FixturesTest.java")
for directory in RECORDINGS:
    mv(f"src/test/resources/fixtures/{directory}", f"protocols/src/testFixtures/resources/fixtures/{directory}")
print("moved")
```

Run: `python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/move_protocols.py`
Expected: `moved`.

Run: `git status --short | cut -c1-2 | sort | uniq -c`
Expected: only renames (`R`), the modified build files and the new untracked files. Then
`ls src/test/resources/fixtures`. Expected: neither `cast`, `ics`, `sonos`, `ssdp`, `tizen`, `upnp` nor `webos`.

- [ ] **Step 4: Move the two fakes into their protocol packages**

`.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/repackage_fakes.py`:

```python
"""FakeUpnpRenderer and FakeSsdpResponder join their protocol packages in protocols' test fixtures: their package
lines change, and every user imports them from there."""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
MOVED = {
    "FakeUpnpRenderer": ("dev.andre.homecontrol.adapters.upnp", "dev.andre.homecontrol.adapters.upnp.protocol"),
    "FakeSsdpResponder": ("dev.andre.homecontrol.discovery.ssdp", "dev.andre.homecontrol.discovery.ssdp.protocol"),
}
ROOTS = ["src/test/java", "src/e2e/java", "protocols/src/test/java", "protocols/src/testFixtures/java"]


def package_of(text):
    return re.search(r"^package ([\w.]+);", text, re.M).group(1)


def add_import(text, statement):
    lines = text.split("\n")
    imports = [n for n, line in enumerate(lines) if line.startswith("import ") and not line.startswith("import static ")]
    ours = [n for n in imports if lines[n].startswith("import dev.andre.")]
    before = [n for n in ours if lines[n] < statement]
    at = before[-1] + 1 if before else (ours[0] if ours else imports[0])
    lines.insert(at, statement)
    return "\n".join(lines)


changed = []
for root in ROOTS:
    for path in sorted((REPO / root).rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        new = text
        for name, (old, moved) in MOVED.items():
            if path.stem == name:
                new = new.replace(f"package {old};", f"package {moved};", 1)
                # Imports of classes now in the fake's own package are redundant.
                new = re.sub(r"^import " + re.escape(moved) + r"\.[A-Z]\w*;\n", "", new, flags=re.M)
                continue
            if not re.search(r"\b" + name + r"\b", new):
                continue
            own_package = package_of(new)
            old_import, new_import = f"import {old}.{name};", f"import {moved}.{name};"
            if old_import in new:
                new = new.replace(old_import + "\n", "" if own_package == moved else new_import + "\n")
            elif own_package == old:
                new = add_import(new, new_import)
        if new != text:
            path.write_text(new, encoding="utf-8")
            changed.append(path.relative_to(REPO).as_posix())
print("\n".join(changed))
print(len(changed), "files")
```

Run: `python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/repackage_fakes.py`
Expected: about 17 files, among them both fakes, `UpnpSessionTest.java`, `SsdpDiscoveryTest.java` (same-package
users, which gain an import) and `RendererResolverTest.java` (which loses its import).

Run: `grep -rnE "adapters\.upnp\.FakeUpnpRenderer|discovery\.ssdp\.FakeSsdpResponder" src protocols`
Expected: no output.

In `protocols/src/testFixtures/java/dev/andre/homecontrol/testsupport/Request.java`, the Javadoc's
`{@link FakeHttpServer}` becomes `{@code FakeHttpServer}`: that class stays in the app's tests.

Run: `grep -rn "{@link" protocols/src/testFixtures`
Expected: every link names a class in `protocols`, `core` or the JDK.

- [ ] **Step 5: Split `IcsFixtureContractTest`**

In `protocols/src/test/java/dev/andre/homecontrol/sources/sports/ics/IcsFixtureContractTest.java`:

- Delete these imports:

  ```java
  import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
  import dev.andre.homecontrol.sources.sports.SportsItems;
  import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
  import dev.andre.homecontrol.sources.sports.calendar.CalendarSchedule;
  ```

  and `import java.util.HashSet;`, `import java.util.Set;` and `import java.util.regex.Pattern;`.
- Delete the constant
  `private static final Pattern MAPPED_ID = Pattern.compile("^ics:c-3f9a1c2b7d4e:[0-9a-f]{16}$");`.
- Delete the methods `mappedEventsAreWellFormed`, `idsAreStableAcrossParses` and `mappedIds`, with their
  annotations.
- The line `Path dir = Path.of(IcsFixtureContractTest.class.getResource("/fixtures/ics").toURI());` becomes:

  ```java
          // The recordings sit in this module's test fixtures, and the module's tests run in its directory.
          Path dir = Path.of("src/testFixtures/resources/fixtures/ics");
  ```

`src/test/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFixtureContractTest.java`:

```java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.sports.SportsItems;
import dev.andre.homecontrol.sources.sports.feed.SportsEvent;
import dev.andre.homecontrol.sources.sports.ics.IcsCalendar;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrence;
import dev.andre.homecontrol.sources.sports.ics.IcsOccurrences;
import dev.andre.homecontrol.sources.sports.ics.IcsParser;
import dev.andre.homecontrol.sources.sports.settings.SportsSettings;
import dev.andre.homecontrol.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the sports source makes of the calendar recordings. The recordings themselves are checked in the protocols
 * module, by IcsFixtureContractTest.
 */
class CalendarFixtureContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final List<String> VALID_FIXTURES = List.of("bundesliga.ics", "recurring.ics", "outlook.ics");
    private static final Pattern MAPPED_ID = Pattern.compile("^ics:c-3f9a1c2b7d4e:[0-9a-f]{16}$");

    @Test
    void mappedEventsAreWellFormed() throws IOException {
        SportsSettings settings = SportsSettings.empty().withCalendars(List.of(
                new SportsSettings.CalendarEntry("c-3f9a1c2b7d4e", "Bundesliga 2026/27", "calendar.example.org", null, Instant.EPOCH)));
        for (String name : VALID_FIXTURES) {
            IcsCalendar calendar = IcsParser.parse(Fixtures.read("ics/" + name));
            IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                    Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), Duration.ofMinutes(120));
            Set<String> ids = new HashSet<>();
            for (IcsOccurrence occurrence : result.occurrences()) {
                SportsEvent event = CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence);
                assertThat(event.itemId()).as(name).matches(MAPPED_ID.pattern());
                assertThat(ids.add(event.itemId())).as(name + ": unique id " + event.itemId()).isTrue();
                assertThat(event.competitionKey()).isEqualTo("calendar:c-3f9a1c2b7d4e");

                var item = SportsItems.toItem(event, settings, BERLIN, Locale.forLanguageTag("de-DE"), Instant.parse("2026-09-19T14:00:00Z"));
                assertThat(item.kind().name()).isEqualTo("LIVE_EVENT");
                assertThat(item.sourceId()).isEqualTo("sports");
                assertThat(item.subtitle()).as(name).isNotBlank();
                assertThat(item.startsAt()).isEqualTo(event.startsAt());
                assertThat(item.endsAt()).isEqualTo(event.endsAt());
            }
        }
    }

    @Test
    void idsAreStableAcrossParses() throws IOException {
        List<String> first = mappedIds();
        List<String> second = mappedIds();
        assertThat(first).isEqualTo(second);
    }

    private static List<String> mappedIds() throws IOException {
        IcsCalendar calendar = IcsParser.parse(Fixtures.read("ics/bundesliga.ics"));
        IcsOccurrences.Result result = IcsOccurrences.expand(calendar, BERLIN,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-10T00:00:00Z"), Duration.ofMinutes(120));
        return result.occurrences().stream()
                .map(occurrence -> CalendarSchedule.toEvent("c-3f9a1c2b7d4e", occurrence).itemId())
                .toList();
    }
}
```

- [ ] **Step 6: Run the module's tests**

Run: `scripts/gradle.sh :protocols:test`
Expected: exit 0. Then `ls protocols/build/test-results/test/TEST-*.xml | wc -l`. Expected: `43` (the 42 moved
test classes and `FixturesTest`).

- [ ] **Step 7: Watch `ArchitectureTest` take the fakes for production code**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: FAIL. The import now holds the test fixtures' fakes as production code: `protocolPackagesStandAlone` fails,
because `FakeUpnpRenderer` uses `core.Device`.

`ArchitectureTest`'s annotation becomes:

```java
@AnalyzeClasses(packages = "dev.andre.homecontrol",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeGradleTestFixtures.class})
```

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS.

- [ ] **Step 8: Run the build**

Run: `scripts/gradle.sh build`
Expected: exit 0. Then `git status --short gradle/verification-metadata.xml`. Expected: no output.

- [ ] **Step 9: Check that app tests count towards protocols' coverage**

Run: `scripts/gradle.sh :protocols:jacocoTestReport`, then:

```bash
python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/coverage.py dev/andre/homecontrol/adapters/cast/protocol protocols/build/reports/jacoco/test/jacocoTestReport.xml build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml
python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/coverage.py - build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml
```

Expected:
- the first two lines have the same `total`, and the combined report's `covered` is greater, because Cast's session
  tests run in the app;
- the last line's `total` is 26,386, the same main code as before the move;
- no test-fixture class is counted: `grep -c "FakeCastReceiver" build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml`
  prints `0`.

- [ ] **Step 10: Check the boot jar**

Run: `scripts/gradle.sh bootJar`, then:

```bash
unzip -l build/libs/home-control-0.0.0-SNAPSHOT.jar | grep -o 'BOOT-INF/lib/home-control-[^ ]*'
unzip -l build/libs/home-control-0.0.0-SNAPSHOT.jar | grep -c 'BOOT-INF/classes/dev/andre/homecontrol/adapters/cast/protocol/'
unzip -l protocols/build/libs/home-control-protocols-0.0.0-SNAPSHOT.jar | grep -c 'adapters/androidtv/protocol/remote/RemoteMessage.class'
```

Expected:
- the first command prints exactly `BOOT-INF/lib/home-control-core-0.0.0-SNAPSHOT.jar` and
  `BOOT-INF/lib/home-control-protocols-0.0.0-SNAPSHOT.jar`, and no `test-fixtures` jar;
- the second prints `0`;
- the third prints `1`: the generated protobuf classes are in the module's jar.

- [ ] **Step 11: Check what SonarCloud counts as production code**

`.superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/sonar-props.init.gradle`:

```groovy
// Prints the source, test, binary and coverage properties the SonarCloud plugin computes for every module.
rootProject {
    tasks.register('printSonarProperties') {
        doLast {
            def props = rootProject.tasks.getByName('sonar').getProperties()
            if (props instanceof org.gradle.api.provider.Provider) {
                props = props.get()
            }
            props.findAll { k, v -> k.endsWith('sonar.sources') || k.endsWith('sonar.tests') || k.contains('xmlReportPaths') }
                 .sort()
                 .each { k, v -> println "SONARPROP $k=$v" }
        }
    }
}
```

Run:
`scripts/gradle.sh --init-script .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/sonar-props.init.gradle printSonarProperties | grep ":protocols"`

Expected:
- `:protocols.sonar.sources` lists `protocols/src/main/java` and the generated protobuf sources, and not
  `protocols/src/testFixtures/java`;
- `:protocols.sonar.coverage.jacoco.xmlReportPaths` is the combined report.

If the test fixtures appear in `sonar.sources`, add this to the root `build.gradle.kts` after the root's `sonar {}`
block, ledger a ruling, and run the check again:

```kotlin
// The test fixtures are test code: fakes and recordings the app's tests share.
project(":protocols") {
    sonar {
        properties {
            property("sonar.sources", "src/main/java,build/generated/sources/proto/main/java")
            property("sonar.tests", "src/test/java,src/testFixtures/java")
        }
    }
}
```

- [ ] **Step 12: Verify checksums and run the browser tests**

Run: `scripts/gradle.sh --dependency-verification strict verifyDependencyChecksums`
Expected: exit 0, and `git status --short gradle/verification-metadata.xml` prints nothing.

Run: `scripts/e2e.sh -Pe2eBrowsers=chromium`
Expected: exit 0, 68 tests passed.

- [ ] **Step 13: Commit**

Stage:
- `settings.gradle.kts` and `build.gradle.kts`;
- `protocols/build.gradle.kts` and the two resource files;
- `IcsFixtureContractTest.java` and `CalendarFixtureContractTest.java`;
- `ArchitectureTest.java` and `Request.java`;
- every file `repackage_fakes.py` listed.

The `git mv` moves are already staged. Then:

```bash
git commit -F - <<'EOF'
refactor: the wire protocols become the Gradle module protocols

The device protocol packages, adapters.net, discovery.ssdp.protocol, sources.sports.ics and the protobuf messages move
to protocols/, a java-library project on top of core with Jackson, BouncyCastle, protobuf and SLF4J on its classpath
and no Spring, so the compiler keeps Spring and the app out of them. Its jar is home-control-protocols.

The fakes and recordings the app's tests share with the protocol tests are its test fixtures, which the app's tests
and browser tests depend on. FakeUpnpRenderer and FakeSsdpResponder join their protocol packages, Request and TestTls
move with them, and IcsFixtureContractTest keeps its parser checks while the sports mapping checks stay in the app as
CalendarFixtureContractTest. ArchitectureTest leaves the test fixtures out of its import.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

Check `git show --stat HEAD`. Expected: renames for every moved file, and no file deleted and created again under
another name.

---

### Task 5: Guards that protocols' packages live in their module alone

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/TestArchitectureTest.java`

**Interfaces:**
- Consumes: the `:protocols` jar names and `DoNotIncludeGradleTestFixtures` (Task 4).
- Produces: the guards `ArchitectureTest.protocolsClassesAreImported` and
  `ArchitectureTest.protocolsLiveInTheirModuleAlone`, and the rule
  `TestArchitectureTest.protocolTestsSitInTheProtocolsModule`.

- [ ] **Step 1: Write the guards**

In `ArchitectureTest.java`, add `import dev.andre.homecontrol.adapters.net.DeviceUris;` in its sorted place. After
`coreLivesInItsModuleAlone`, add:

```java
    // protocols is a module too: its classes reach this test as a jar, and its test fixtures, which hold fakes in
    // protocol packages, stay out of the import.
    @ArchTest
    static void protocolsClassesAreImported(JavaClasses classes) {
        assertThat(classes.contain(DeviceUris.class)).as("the import holds protocols' classes").isTrue();
        assertThat(classes.contain("dev.andre.homecontrol.adapters.upnp.protocol.FakeUpnpRenderer"))
                .as("the import leaves out the test fixtures")
                .isFalse();
    }

    // As for core: the compiler keeps Spring out of protocols only for the classes in that module, so its packages
    // live there alone.
    @ArchTest
    static void protocolsLiveInTheirModuleAlone(JavaClasses classes) {
        assertThat(classes.that(resideInAnyPackage("..protocol..", "dev.andre.homecontrol.adapters.net..",
                "dev.andre.homecontrol.sources.sports.ics..")))
                .isNotEmpty()
                .allSatisfy(javaClass -> assertThat(javaClass.getSource())
                        .hasValueSatisfying(source -> assertThat(source.getUri().toString())
                                .as(javaClass.getName())
                                .contains("home-control-protocols")
                                .doesNotContain("test-fixtures")));
    }
```

In `TestArchitectureTest.java`, add after `coreTestsSitInTheCoreModule`:

```java
    @ArchTest
    static final ArchRule protocolTestsSitInTheProtocolsModule = noClasses()
            .should().resideInAnyPackage("..protocol..", "dev.andre.homecontrol.adapters.net..",
                    "dev.andre.homecontrol.sources.sports.ics..")
            .because("the tests of protocols' packages sit in the protocols module, which compiles them against "
                    + "protocols and core alone");
```

- [ ] **Step 2: Run the guards**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest' --tests 'dev.andre.homecontrol.TestArchitectureTest'`
Expected: PASS. The code obeys the guards; the next step shows each one failing.

- [ ] **Step 3: Watch each guard fail**

1. **Main side.** Create `src/main/java/dev/andre/homecontrol/adapters/net/ScratchLeak.java`:

   ```java
   package dev.andre.homecontrol.adapters.net;

   import org.springframework.util.StringUtils;

   /** Scratch violation for the RED run of protocolsLiveInTheirModuleAlone; deleted right after. */
   public final class ScratchLeak {

       private ScratchLeak() {
       }

       public static boolean blank(String text) {
           return !StringUtils.hasText(text);
       }
   }
   ```

2. **Test side.** Create `src/test/java/dev/andre/homecontrol/adapters/net/ScratchLeakTest.java`:

   ```java
   package dev.andre.homecontrol.adapters.net;

   /** Scratch violation for the RED run of protocolTestsSitInTheProtocolsModule; deleted right after. */
   class ScratchLeakTest {
   }
   ```

3. Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest' --tests 'dev.andre.homecontrol.TestArchitectureTest'`
   Expected: FAIL. `protocolsLiveInTheirModuleAlone` names `ScratchLeak`, and `protocolTestsSitInTheProtocolsModule`
   names `ScratchLeakTest`.

4. Delete both scratch files, and the directories `src/main/java/dev/andre/homecontrol/adapters/net` and
   `src/test/java/dev/andre/homecontrol/adapters/net` if they are now empty.

5. Temporarily change `ArchitectureTest`'s import options back to `ImportOption.DoNotIncludeTests.class` alone, and
   run the same command. Expected: FAIL, with `protocolsClassesAreImported` failing on
   `[the import leaves out the test fixtures]`. Then restore both options.

6. Run the same command. Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dev/andre/homecontrol/ArchitectureTest.java src/test/java/dev/andre/homecontrol/TestArchitectureTest.java
git commit -F - <<'EOF'
test: protocols' packages live in their module alone

As for core, the compiler keeps Spring out of protocols only for the classes inside the module. ArchitectureTest
checks that the protocol packages, adapters.net and sources.sports.ics come from protocols' jar, that the import holds
them and leaves out the test fixtures; TestArchitectureTest checks that no app test sits in those packages.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

---

### Task 6: Build times after the split, and the docs

**Files:**
- Modify: `AGENTS.md` ("Where things live")
- Modify: `docs/dev/architecture.md` ("Modules", package map, "Package rules", "Progress measures")
- Modify: `docs/dev/testing.md` ("Test layers", "Running tests", "Fakes and fixtures")

**Interfaces:**
- Consumes: `measure.py` and `measures.md` (Task 1); the module layout (Tasks 4 and 5).

- [ ] **Step 1: Measure the split**

Run in the background from the repository root. It takes about 60 minutes, and nothing else may build meanwhile.

```bash
python3 .superpowers/sdd/2026-10-02-phase-4-pr2-protocols-module/measure.py "After: protocols is a module" clean src/main/java/dev/andre/homecontrol/web/ErrorAdvice.java core/src/main/java/dev/andre/homecontrol/core/Device.java protocols/src/main/java/dev/andre/homecontrol/adapters/upnp/protocol/DidlLite.java
```

Expected: exit 0, with four lines under `## After: protocols is a module`. A run that fails on a flaky test needs the
toggled class restored (`git checkout -- <path>`) and a rerun. Write the medians as minutes and seconds; Step 3 uses
them.

- [ ] **Step 2: `AGENTS.md`**

In "Where things live":

- The "Modules" bullet becomes:

  ```markdown
  - Modules: the root project is the app. `core/` holds the domain model (`dev.andre.homecontrol.core`), which depends
    on the JDK alone. `protocols/` holds the Spring-free wire libraries: every `protocol` package, `adapters.net` and
    `sources.sports.ics`. Run a module's tests with `scripts/gradle.sh :core:test` or `:protocols:test`. See
    [Modules](docs/dev/architecture.md#modules).
  ```

- The "Device adapters" bullet becomes:

  ```markdown
  - Device adapters: `src/main/java/dev/andre/homecontrol/adapters/<device>/`, with the wire protocol in
    `protocols/src/main/java/dev/andre/homecontrol/adapters/<device>/protocol/`.
  ```

- The "Tests sit…" bullet becomes:

  ```markdown
  - Tests sit in the same package as the code they test, in the code's module. Fakes of devices and services are named
    `Fake…` and speak the real protocol. A fake the app's tests share with the protocol tests sits in `protocols`' test
    fixtures (`protocols/src/testFixtures/java/`). Recorded device and API responses are in
    `src/test/resources/fixtures/<device or source>/`, or in `protocols/src/testFixtures/resources/fixtures/<device>/`
    for the protocols' own. Read a recording with `Fixtures.read`.
  ```

- The "Shared test helpers" bullet becomes:

  ```markdown
  - Shared test helpers: `src/test/java/dev/andre/homecontrol/testsupport/`, and `Fixtures`, `Request` and `TestTls`
    in `protocols/src/testFixtures/java/dev/andre/homecontrol/testsupport/`. Build a new web-API fake on
    `FakeHttpServer` instead of opening a server of its own.
  ```

- [ ] **Step 3: `docs/dev/architecture.md`**

The "Modules" section's opening paragraph and table become:

```markdown
The build has three Gradle projects. Each compiles against only what its build file declares, so the compiler refuses
an import that crosses a module's boundary. That holds only for the classes inside a module, so `ArchitectureTest` and
`TestArchitectureTest` check that no class or test of a module's packages sits in the app. `ArchitectureTest` leaves
`protocols`' test fixtures out of its import.

| Module | Directory | Holds | Depends on |
| --- | --- | --- | --- |
| `core` | `core/` | the package `core` | the JDK |
| `protocols` | `protocols/` | every `protocol` package, `adapters.net`, `sources.sports.ics` and the protobuf messages; as test fixtures, the fakes and recordings the app's tests share | `core`, Jackson, BouncyCastle, protobuf and the SLF4J API |
| app | the repository root | every other package; it builds the boot jar | `core`, `protocols`, Spring Boot and the libraries in `build.gradle.kts` |
```

In the same section, `because the app's tests exercise much of `core`` becomes
``because the app's tests exercise much of `core` and `protocols` ``.

In the package map:
- In the `adapters` row, `with its wire protocol in a `protocol` subpackage` becomes
  ``with its wire protocol in a `protocol` subpackage in the `protocols` module``.
- After the `adapters.net` parenthesis `(TLS, WebSockets, Wake-on-LAN, device URLs)`, add
  `` in the `protocols` module``.
- In the `adapters.support` list, after `` `WakeOnLanPower` ``, add `` and `WakeOnLanConfiguration`, the one sender for
  the configured broadcast address``.
- In the `discovery` row, `is the protocol package `discovery.ssdp.protocol`` becomes
  ``is the protocol package `discovery.ssdp.protocol`, in the `protocols` module``.
- In the `sources` row, `a parser that depends on the JDK alone` becomes
  ``a parser in the `protocols` module that depends on the JDK alone``.

In "Package rules":
- Add after the `core` row:

  ```markdown
  | `protocols`' packages depend on neither Spring nor the app | the `protocols` module: nothing but `core` and its libraries is on its classpath |
  ```

- The intro sentence's `except one whose status names a module: the compiler enforces it, and `ArchitectureTest`
  checks that the package lives in that module alone.` becomes `except those whose status names a module: the
  compiler enforces them, and `ArchitectureTest` checks that the packages live in their module alone.`
- In the paragraph about `core`'s tests, `keeps the app's tests out of package `core`.` becomes
  ``keeps the app's tests out of the modules' packages.``

In "Progress measures", replace the three build-time rows with these five rows (four build times and one count),
filled in from `measures.md`. "After" is
Task 6's median, "core" is PR 1's (4 min 5 s, 3 min 40 s, 3 min 42 s), "one project" is main's before PR 1
(4 min 8 s, 3 min 48 s, 3 min 52 s), and "before" in the last row is Task 1's median:

```markdown
| Clean `build` | not measured | <after> (4 min 5 s with `core` alone a module, 4 min 8 s with one project) |
| `build` after a change to one app class | not measured | <after> (3 min 40 s with `core` alone a module, 3 min 48 s with one project) |
| `build` after a change to one `core` class | not measured | <after> (3 min 42 s with `core` alone a module, 3 min 52 s with one project) |
| `build` after a change to one `protocols` class | not measured | <after> (<before> before `protocols` was a module) |
| Classes the compiler keeps free of Spring and the app | 0 | 149 (`core` 76, `protocols` 73) |
```

Replace the paragraph that begins `The build times are the median of three runs` with the following. Choose its last
sentence from the medians: the first if the app-class median is at least 80% of the clean median, else the second.

```markdown
The build times are the median of three runs of `scripts/gradle.sh` on the same four-CPU machine, without the build
cache. A change moves every line number of `web/ErrorAdvice.java`, `core/Device.java` or
`adapters/upnp/protocol/DidlLite.java`, so the class file changes and no signature does. The app's `test` task runs
again after any change to the app or to a module it uses, and takes most of every build.
An app-class change still costs most of a clean build, so a further split of the app gets its own spec (roadmap,
Phase 4).
An app-class change costs well under a clean build, so the three modules stay (roadmap, Phase 4).
```

- [ ] **Step 4: `docs/dev/testing.md`**

In "Test layers", the unit-test row's "What it is" cell becomes
`Plain JUnit 5 with AssertJ, Mockito and Awaitility (the modules' tests use no Mockito). Most device and source tests
drive the real client against an in-process fake that speaks the real protocol over a socket.`, and its "Where" cell
becomes `` `src/test/java` of the module that holds the code: `core/`, `protocols/` or the root``.

In "Running tests":
- After the `core` example, add
  `` One class of `protocols`: `scripts/gradle.sh :protocols:test --tests 'dev.andre.homecontrol.adapters.net.DeviceUrisTest'`. ``
- The `:core:test --tests` sentence becomes
  ``A module's own test task (`:core:test --tests`, `:protocols:test --tests`) fails when nothing in it matches.``
- The failure-details command becomes
  `grep -A20 '<failure' build/test-results/test/*.xml core/build/test-results/test/*.xml protocols/build/test-results/test/*.xml`.
- The timeout bullet's ``(`junit-platform.properties` in `src/test/resources` and `core/src/test/resources`)`` becomes
  ``(`junit-platform.properties` in the `src/test/resources` of the root, `core/` and `protocols/`)``, rewrapped to the
  guide's 120 columns.

In "Fakes and fixtures":
- The first bullet becomes:

  ```markdown
  - A fake of a device or a service is named `Fake…` and sits in the test package of the code it fakes, for example
    `FakeCastReceiver` or `FakeJellyfinServer`. It speaks the real protocol, so the production client runs unchanged.
    A fake that the app's tests share with the protocol tests sits in `protocols`' test fixtures
    (`protocols/src/testFixtures/java`), in its protocol's package. The app's tests and browser tests get the test
    fixtures through `testImplementation(testFixtures(project(":protocols")))`.
  ```

- In the second bullet, `` `TestTls` for a self-signed server certificate`` becomes
  ``` ``TestTls` for a self-signed server certificate (with `Request` and `Fixtures`, in `protocols`' test fixtures)`` ```.
- The recordings bullet becomes:

  ```markdown
  - Recorded device and API responses live in `fixtures/<device or source>/` on the test classpath: the protocols'
    own (`cast`, `ics`, `sonos`, `ssdp`, `tizen`, `upnp`, `webos`) in `protocols/src/testFixtures/resources/`, the
    content sources' in `src/test/resources/`. Read one with `Fixtures.read("upnp/didl-track.xml")` or
    `Fixtures.bytes(…)`: a module's tests run in the module's directory, so a path relative to it finds only that
    module's files.
  ```

- [ ] **Step 5: Check the docs**

Run: `grep -n "<after>\|<before>" docs/dev/architecture.md`
Expected: no output.

Run: `grep -rn "src/test/resources/fixtures/<device or source>/\`\.\|NetConfiguration" AGENTS.md docs/dev`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add AGENTS.md docs/dev/architecture.md docs/dev/testing.md
git commit -F - <<'EOF'
docs: the protocols module in the guides, with build times

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
EOF
```

---

## After the tasks

- The final review package covers `2b7662c..HEAD`.
- The pull request's description carries `measures.md` and the build-time conclusion.
- **SonarCloud check (Review Focus 4):** once CI has run, ask SonarCloud for the coverage of a protocol file:

  ```
  https://sonarcloud.io/api/measures/component?component=Yukuhu_home-control:protocols/src/main/java/dev/andre/homecontrol/adapters/net/DeviceUris.java&pullRequest=<N>&metricKeys=coverage
  ```

  Expected: a coverage value, not an empty measure.
- **CodeQL:** the move re-raises alerts dismissed at the old paths, such as the `TizenRest` SSRF alert that was
  dismissed three times. Dismissing one is the user's decision; ask.
