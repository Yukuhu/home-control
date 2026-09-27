# Phase 1.1 ArchUnit Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce the roadmap's package rules on every build, with today's violations frozen so that only new ones fail.

**Architecture:**
- One ArchUnit test class checks production classes against nine rules.
- Rules the code already keeps are strict.
- Rules it breaks are wrapped in `FreezingArchRule`. Their known violations are committed in `src/test/archunit-store/`, and a CI step fails if a build leaves that store changed.
- `docs/dev/architecture.md` records the rules and the frozen counts. Phase 1.2 builds the rest of that page.

**Tech Stack:** Java 25, Gradle (Kotlin DSL, version catalog), JUnit 5, ArchUnit 1.5.1 (`com.tngtech.archunit:archunit-junit5`), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-27-phase-1-guardrails-design.md`, section "1.1 ArchUnit rules with a frozen store".

## Global Constraints

- Build and test only through `scripts/gradle.sh`. It runs Gradle in the `gradle:jdk25` container; there is no local JDK. A focused run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`.
- ArchUnit `1.5.1` is a `testImplementation` dependency only, with its version in `gradle/libs.versions.toml`. No other new dependency.
- No production code changes in this plan.
- The store lives at `src/test/archunit-store/` and is committed. `src/test/resources/archunit.properties` sets `freeze.store.default.path=src/test/archunit-store`, `freeze.store.default.allowStoreCreation=false` and `freeze.refreeze=false`.
- Branch `build/archunit-rules` from `main`. One pull request. `scripts/gradle.sh build` is green at the end.
- Commits use conventional-commit style and end with a `Co-Authored-By:` trailer naming the model that wrote them.

## Review Focus

- **The rules must actually fail.** A strict rule that passes on today's code proves nothing until it has been seen to fail on a violation. Task 1 Step 4 adds a violation on purpose and removes it.
- **A new violation of a frozen rule must still fail.** Freezing records only today's violations. Task 2 Step 7 adds a new one on purpose and removes it.
- **Moving code must not "unfreeze" a violation.** ArchUnit's default line matcher ignores line numbers, so an inserted line above a frozen violation must not turn it into a new one. Task 2 Step 8 checks this.
- **Every rule must run.** The ArchUnit engine must discover every rule as a test. Task 1 Step 5 and Task 2 Step 6 count them in the JUnit report.
- **The store must stay committed.** Editing a frozen rule's description, or fixing a violation, changes the store; CI must fail until that change is committed. Task 2 Step 9 adds the CI check, and Step 10 makes it visibly fail.

---

### Task 1: ArchUnit dependency and the strict rules

The four rules that `main` already keeps (checked with ArchUnit 1.5.1 against `main`'s classes: 0 violations each) become strict:
- `core` depends only on the JDK;
- content sources are independent of each other;
- device adapters are independent of each other;
- network libraries appear only in adapters, sources and discovery.

`adapters.links.ContentLinks` is shared by the webOS and Tizen adapters by design ("content ids inside service URLs, for adapters that launch apps by id"). It is allowed next to `adapters.net` and the future `adapters.support`.

**Files:**
- Modify: `gradle/libs.versions.toml` (`[versions]` and `[libraries]`)
- Modify: `build.gradle.kts:71-73` (test dependencies)
- Create: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `dev.andre.homecontrol.ArchitectureTest`, annotated `@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.DoNotIncludeTests.class)`, with `static final ArchRule` fields annotated `@ArchTest`. Task 2 adds six more fields to this class.

- [ ] **Step 1: Add the dependency**

In `gradle/libs.versions.toml`, add to `[versions]`, directly after the `playwright` line:

```toml
archunit = "1.5.1"
```

and to `[libraries]`, directly after the `playwright` line:

```toml
archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }
```

In `build.gradle.kts`, add after `testImplementation("org.springframework.boot:spring-boot-webmvc-test")`:

```kotlin
    // Package rules checked on every build (src/test/java/dev/andre/homecontrol/ArchitectureTest.java).
    testImplementation(libs.archunit.junit5)
```

- [ ] **Step 2: Write the rules**

Create `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`:

```java
package dev.andre.homecontrol;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The package rules of docs/dev/architecture.md, checked on every build. Rules the code still breaks are frozen:
 * their known violations are recorded in src/test/archunit-store, and only new ones fail.
 */
@AnalyzeClasses(packages = "dev.andre.homecontrol", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreDependsOnlyOnTheJdk = classes()
            .that().resideInAPackage("dev.andre.homecontrol.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.andre.homecontrol.core..")
            .because("core is the domain model every other package builds on");

    @ArchTest
    static final ArchRule sourcesAreIndependent = slices()
            .matching("dev.andre.homecontrol.sources.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAPackage("dev.andre.homecontrol.sources.http.."))
            .because("each content source is a module that can be switched off; sources.http is their shared support");

    @ArchTest
    static final ArchRule adaptersAreIndependent = slices()
            .matching("dev.andre.homecontrol.adapters.(*)..")
            .should().notDependOnEachOther()
            .ignoreDependency(alwaysTrue(), resideInAnyPackage("dev.andre.homecontrol.adapters.net..",
                    "dev.andre.homecontrol.adapters.links..", "dev.andre.homecontrol.adapters.support.."))
            .ignoreDependency(resideInAPackage("dev.andre.homecontrol.adapters.sonos.."),
                    resideInAPackage("dev.andre.homecontrol.adapters.upnp.protocol.."))
            .because("each device adapter is a module that can be switched off; net, links and support are shared, "
                    + "and Sonos speaks UPnP");

    @ArchTest
    static final ArchRule networkLibrariesStayInAdaptersSourcesAndDiscovery = noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.adapters..", "dev.andre.homecontrol.sources..",
                    "dev.andre.homecontrol.discovery..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.apache.hc..", "javax.jmdns..",
                    "org.freedesktop.dbus..", "com.github.hypfvieh..")
            .because("only adapters speak device protocols and only sources speak content APIs");
}
```

- [ ] **Step 3: Run the rules on today's code**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS. All four rules held on `main` when this plan was written. If one fails, stop and report the violation it names; do not weaken the rule.

- [ ] **Step 4: See each kind of rule fail (red check, not committed)**

Temporarily add this field to the class body of `src/main/java/dev/andre/homecontrol/core/Hosts.java`:

```java
    private static final Class<?> ARCHITECTURE_PROBE = java.net.http.HttpClient.class;
```

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: FAIL in `networkLibrariesStayInAdaptersSourcesAndDiscovery` only, naming `Hosts` and `java.net.http.HttpClient`. `coreDependsOnlyOnTheJdk` still passes, because `java.net.http` is part of the JDK (`java..`).

Then replace that line with one that breaks the core rule:

```java
    private static final Class<?> ARCHITECTURE_PROBE = dev.andre.homecontrol.storage.StorageException.class;
```

Run the same command.
Expected: FAIL in `coreDependsOnlyOnTheJdk`, naming `Hosts` and `StorageException`.

Remove the field again (`git checkout -- src/main/java/dev/andre/homecontrol/core/Hosts.java`), and record both failure messages in your report.

- [ ] **Step 5: Confirm every rule ran**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`, then
`grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-dev.andre.homecontrol.ArchitectureTest.xml`
Expected: `tests="4" skipped="0" failures="0"`. If `tests="0"`, the ArchUnit engine did not discover the class: make the class and its fields `public` and run again.

- [ ] **Step 6: Build and commit**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL.

```bash
git add gradle/libs.versions.toml build.gradle.kts src/test/java/dev/andre/homecontrol/ArchitectureTest.java
git commit -m "build: check the package rules the code already keeps with ArchUnit

core depends only on the JDK, sources and adapters stay independent of
each other (sharing only sources.http, adapters.net, adapters.links and
Sonos's use of UPnP), and network libraries stay in adapters, sources and
discovery. All four held on main; they now fail the build if broken."
```

End the message with your own `Co-Authored-By:` trailer line, as the Global Constraints say, e.g. by adding it with `git commit --amend` before moving on.

### Task 2: Frozen rules, the committed store and the CI check

The six rules `main` breaks today are frozen. ArchUnit 1.5.1 on `main`'s classes reported:

| Field | Rule | Violations on `main` |
| --- | --- | ---: |
| `protocolPackagesStandAlone` | `..protocol..` depends on neither Spring nor any app package other than `adapters.net` and other protocol packages | 46 |
| `topLevelPackagesAreFreeOfCycles` | no cycles between top-level packages | 6 |
| `sourcesDoNotDependOnAdapters` | `sources` does not depend on `adapters` | 6 |
| `adaptersDoNotDependOnSourcesOrWeb` | `adapters` depends on neither `sources` nor `web` | 1 |
| `webDoesNotDependOnAdapters` | `web` does not depend on `adapters` | 6 |
| `servletTypesStayAtTheWebEdge` | `jakarta.servlet` only in `web`, `security`, controllers and controller advice | 24 |

**Files:**
- Modify: `src/test/java/dev/andre/homecontrol/ArchitectureTest.java`
- Create: `src/test/resources/archunit.properties`
- Create: `src/test/archunit-store/` (written by ArchUnit)
- Modify: `.github/workflows/ci.yml` (the "Build and test" job, after "Build and run the full suite")
- Create: `docs/dev/architecture.md`

**Interfaces:**
- Consumes: `ArchitectureTest` from Task 1.
- Produces: `src/test/archunit-store/` (a `stored.rules` index and one file per frozen rule) and `docs/dev/architecture.md`. Phase 1.2 extends that page and does not rename it.

- [ ] **Step 1: Configure the store for its one-time creation**

Create `src/test/resources/archunit.properties`:

```properties
# FreezingArchRule: known violations of the frozen rules in ArchitectureTest live in this committed store.
# A fixed violation is removed from it on the next run; commit the smaller store (CI fails otherwise).
freeze.store.default.path=src/test/archunit-store
freeze.store.default.allowStoreCreation=true
freeze.refreeze=false
```

(`allowStoreCreation` is `true` only until the store exists; Step 4 sets it to `false`.)

- [ ] **Step 2: Add the frozen rules**

In `ArchitectureTest`, add these imports:

```java
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;
```

and these fields after the existing ones:

```java
    @ArchTest
    static final ArchRule protocolPackagesStandAlone = freeze(noClasses()
            .that().resideInAPackage("..protocol..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .orShould().dependOnClassesThat(resideInAPackage("dev.andre.homecontrol..")
                    .and(not(resideInAnyPackage("..protocol..", "dev.andre.homecontrol.adapters.net.."))))
            .because("wire protocols are libraries: they take plain values and know nothing of Spring or the app"));

    @ArchTest
    static final ArchRule topLevelPackagesAreFreeOfCycles = freeze(slices()
            .matching("dev.andre.homecontrol.(*)..")
            .should().beFreeOfCycles()
            .because("packages in a cycle cannot be understood, tested or split apart on their own"));

    @ArchTest
    static final ArchRule sourcesDoNotDependOnAdapters = freeze(noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.sources..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.adapters..")
            .because("sources see devices only through the domain model in core"));

    @ArchTest
    static final ArchRule adaptersDoNotDependOnSourcesOrWeb = freeze(noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.adapters..")
            .should().dependOnClassesThat().resideInAnyPackage("dev.andre.homecontrol.sources..",
                    "dev.andre.homecontrol.web..")
            .because("adapters speak device protocols and nothing else"));

    @ArchTest
    static final ArchRule webDoesNotDependOnAdapters = freeze(noClasses()
            .that().resideInAPackage("dev.andre.homecontrol.web..")
            .should().dependOnClassesThat().resideInAPackage("dev.andre.homecontrol.adapters..")
            .because("the web layer sees the domain model only"));

    @ArchTest
    static final ArchRule servletTypesStayAtTheWebEdge = freeze(noClasses()
            .that().resideOutsideOfPackages("dev.andre.homecontrol.web..", "dev.andre.homecontrol.security..")
            .and().areNotMetaAnnotatedWith(Controller.class)
            .and().areNotMetaAnnotatedWith(ControllerAdvice.class)
            .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
            .because("services and stores take values, not requests"));
```

`areNotMetaAnnotatedWith(Controller.class)` also covers `@RestController`, which is meta-annotated with `@Controller`.

- [ ] **Step 3: Create the store**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS, and `src/test/archunit-store/` now holds `stored.rules` plus six rule files.

Count the frozen violations per rule:

```bash
for f in src/test/archunit-store/*; do [ "$(basename "$f")" = stored.rules ] || echo "$(wc -l < "$f") $(basename "$f")"; done
cat src/test/archunit-store/stored.rules
```

`stored.rules` maps each rule description to its file. The counts should match the table above: 46, 6, 6, 1, 6, 24. Code merged since this plan was written may move them slightly. Record the counts you get, per rule; Step 11 writes them down.

- [ ] **Step 4: Forbid creating another store**

In `src/test/resources/archunit.properties`, change `freeze.store.default.allowStoreCreation=true` to `freeze.store.default.allowStoreCreation=false`.

- [ ] **Step 5: Run again against the committed store**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS, and `git status --porcelain -- src/test/archunit-store` shows only the new, untracked store (no modified files after this second run).

- [ ] **Step 6: Confirm every rule ran**

Run: `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-dev.andre.homecontrol.ArchitectureTest.xml`
Expected: `tests="10" skipped="0" failures="0"`.

- [ ] **Step 7: See a new violation of a frozen rule fail (red check, not committed)**

Temporarily add this field to the class body of `src/main/java/dev/andre/homecontrol/sources/pinned/PinnedShortcuts.java`:

```java
    private static final Class<?> ARCHITECTURE_PROBE = dev.andre.homecontrol.adapters.net.WakeOnLan.class;
```

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: FAIL in `sourcesDoNotDependOnAdapters`, naming `PinnedShortcuts` and `WakeOnLan`. The six frozen `sources` violations do not appear.

Remove the field (`git checkout -- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedShortcuts.java`) and run again: PASS. Check that `git status --porcelain -- src/test/archunit-store` shows no modified store file. Record the failure message in your report.

- [ ] **Step 8: See moved code stay frozen (not committed)**

Temporarily insert an empty line near the top of `src/main/java/dev/andre/homecontrol/sources/pinned/PinnedConfiguration.java`, directly after its `package` line. That file holds two frozen `sourcesDoNotDependOnAdapters` violations, and the empty line shifts their line numbers.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS, with no modified store file. ArchUnit's default line matcher ignores line numbers.

Remove the line (`git checkout -- src/main/java/dev/andre/homecontrol/sources/pinned/PinnedConfiguration.java`).

- [ ] **Step 9: Make CI fail on an uncommitted store change**

In `.github/workflows/ci.yml`, in the "Build and test" job, directly after the step

```yaml
      - name: Build and run the full suite
        run: ./gradlew build
```

insert:

```yaml
      # ArchitectureTest freezes today's package-rule violations in src/test/archunit-store. The build removes a
      # fixed violation from the store and records a newly frozen rule there; either change must be committed.
      - name: Check the frozen architecture violations are committed
        run: |
          changes="$(git status --porcelain -- src/test/archunit-store)"
          if [ -n "$changes" ]; then
            echo "The build changed src/test/archunit-store; run the tests locally and commit the store:"
            echo "$changes"
            exit 1
          fi
```

- [ ] **Step 10: See the CI check fail and pass (not committed)**

Run the check's shell body locally, from the repository root, before the store is committed:

```bash
changes="$(git status --porcelain -- src/test/archunit-store)"; if [ -n "$changes" ]; then echo "changed"; else echo "clean"; fi
```

Expected: `changed`, because the store is still untracked. Run it again after Step 12's commit; expected: `clean`.

- [ ] **Step 11: Write the architecture page**

Create `docs/dev/architecture.md`, putting the counts you recorded in Step 3 into the table:

```markdown
# Architecture

Phase 1.2 of the architecture roadmap extends this page with the package map and a dependency diagram.

## Package rules

`src/test/java/dev/andre/homecontrol/ArchitectureTest.java` checks these rules on every build.

| Rule | Status |
| --- | --- |
| `core` depends only on the JDK | strict |
| Content sources are independent of each other, apart from the shared `sources.http` | strict |
| Device adapters are independent of each other, apart from the shared `adapters.net`, `adapters.links` and `adapters.support`, and Sonos using `adapters.upnp.protocol` | strict |
| `java.net.http`, Apache HttpClient 5, jmDNS and D-Bus are used only in `adapters`, `sources` and `discovery` | strict |
| `..protocol..` packages depend on neither Spring nor any application package other than `adapters.net` and other protocol packages | frozen: <count> |
| No cycles between the top-level packages | frozen: <count> |
| `sources` does not depend on `adapters` | frozen: <count> |
| `adapters` depends on neither `sources` nor `web` | frozen: <count> |
| `web` does not depend on `adapters` | frozen: <count> |
| `jakarta.servlet` is used only in `web`, `security`, controllers and controller advice | frozen: <count> |

## Frozen violations

A frozen rule records the violations it had when it was frozen in `src/test/archunit-store/`. It fails only on new
ones.

- **Fixing a violation** removes it from the store the next time the tests run. Commit the smaller store with the fix.
  CI fails when a build leaves the store changed.
- **Never refreeze to make a build pass.** `freeze.refreeze` stays `false`.
- **Changing a frozen rule's description** (its `because` text included) makes ArchUnit treat it as a new rule and
  record all of its current violations again. The store diff shows this, so review it like code.

The number of frozen violations only goes down. It is the progress measure for the roadmap's Phase 2 and 3
workstreams, which remove them.
```

Replace each `<count>` with the number you recorded for that rule in Step 3.

- [ ] **Step 12: Build and commit**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL, and `git status --porcelain -- src/test/archunit-store` still shows the store only as untracked (the build did not modify it).

```bash
git add src/test/java/dev/andre/homecontrol/ArchitectureTest.java src/test/resources/archunit.properties \
        src/test/archunit-store .github/workflows/ci.yml docs/dev/architecture.md
git commit -m "build: freeze today's package-rule violations and fail on new ones

Protocol packages standing alone, no package cycles, sources and web kept
off adapters, adapters kept off sources and web, and servlet types kept at
the web edge are frozen with their current violations in
src/test/archunit-store. New violations fail the build; CI fails when a
build leaves the store changed, so fixed violations are committed."
```

End the message with your own `Co-Authored-By:` trailer line, as in Task 1.

Then run Step 10's check once more: expected `clean`.
