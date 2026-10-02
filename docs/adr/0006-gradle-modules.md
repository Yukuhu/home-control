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
- The compiler checks only the classes inside a module, so ArchUnit checks that no class or test of a module's
  package sits in the app.
- `test --tests` runs its filter in every module. A module without a match passes, and the app's `test` fails when
  nothing matches, so a filter for a class of `core` runs with `:core:test --tests`, which fails when nothing
  matches.
- Whether to split the app further is decided from CI's "Build and test" job time (`docs/dev/architecture.md`,
  "Progress measures"), not from timings on one developer's machine.
