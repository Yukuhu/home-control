# SonarCloud Cleanup Implementation Plan

> **For agentic workers:** Execute the independent scopes in parallel; the parent owns integration, verification, and commits.

**Goal:** Fix most actionable findings in the 1,195-issue SonarCloud snapshot on `fix/sonarcloud-after-remote`, ready for one PR.

**Architecture:** Preserve application behavior while simplifying the flagged code and strengthening test synchronization. Collect Chromium's JavaScript coverage during the existing browser suite, convert it using the standard Istanbul tools, and import the LCOV report alongside JaCoCo in CI.

**Tech stack:** Java 25, Spring Boot, Gradle, Playwright Java, browser JavaScript, Node.js coverage tooling.

**Spec:** [Initial triage](../reviews/2026-09-26-sonarcloud-triage.md) and the user's instruction to keep all further fixes on this branch.

## Constraints and review focus

- Preserve persisted IDs, HTTP endpoints, protocol messages, pairing, and playback semantics.
- Keep rule checks and coverage thresholds enabled. Do not replace unsafe code with suppressions.
- Review shared mutable state by ownership, including close/reconnect races and cancellation.
- Preserve timeout tests' ability to detect late work, and assertion tests' intended failure source.
- Coverage must come from executed application scripts and survive navigations; WebKit tests must remain runnable.
- Preserve keyboard, touch, focus, and screen-reader behavior when changing native HTML semantics.

## Tasks

- [x] Clean up `src/test/java/**`: isolate exception assertions, strengthen AssertJ assertions, replace unused bindings and arbitrary sleeps where observable synchronization is available. Record addressed inventory keys.
- [x] Clean up production Java outside `adapters/**`: meaningful constants, record patterns, focused complexity refactors, and verified concurrency fixes. Record addressed inventory keys.
- [x] Clean up `adapters/**`: the same mechanical fixes, protocol state-machine complexity, and resource/ownership findings. Leave TLS trust changes requiring a separate protocol design documented.
- [x] Fix frontend findings in `src/main/resources/static/**` and `templates/**`, checking browser behavior and accessibility semantics.
- [x] Add browser coverage capture in `Browsers`/`BrowserSession`, a tested V8-to-LCOV conversion script, and CI artifact transfer into the Sonar job. Configure `sonar.javascript.lcov.reportPaths`.
- Deferred: centralize dependency versions and generate verification/locking metadata; prioritize the reported Shield outage and validate dependency-update workflows separately.
- [x] Reproduce the Shield failure locally against the physical device: fix the invalid bootstrap self-signature requirement, premature retry-counter reset, and hostname-dependent mDNS startup. Preserve the on-screen pairing check, saved certificate pins, and existing credentials; verify that a fresh identity reaches code entry.
- [x] Compile all source sets, run the full Java suite and Chromium browser suite, review cross-scope diffs, and fix regressions.
- [x] Update triage with measured results, remaining findings, and verification; prepare all changes on the same branch for one PR.
