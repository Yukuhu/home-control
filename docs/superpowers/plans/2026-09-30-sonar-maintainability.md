# SonarCloud maintainability cleanup implementation plan

> **For agentic workers:** Use superpowers:executing-plans for the code cleanup and superpowers:requesting-code-review for a final independent review.

**Goal:** Address the 21 OPEN/CONFIRMED maintainability issues reported for `Yukuhu_home-control`, branch `main`.

**Architecture:** Preserve application behavior and persisted formats. Use Jackson 3's `valueStream()` for collection mapping, simplify configuration control flow, and improve independent tooling reads. Document justified exceptions at their narrowest supported scope.

**Tech Stack:** Java 25, Jackson 3, Spring Boot 4.1.1, Node ES modules used only by CI tooling.

**Spec:** The live SonarCloud issue query, repository `AGENTS.md`, and the previous triage at `docs/superpowers/reviews/2026-09-29-sonarcloud-triage.md`.

## Global constraints

- Keep `shield.*` aliases and property-source precedence working.
- Keep device validation, ordering, migration and JSON field names unchanged.
- Preserve `DataDirectory`'s service identity and the tested AssertJ actual/expected order.
- Bound concurrent JUnit reads and coverage conversion memory; preserve deterministic diagnostics.
- Preserve the existing accessible custom controls and documented login behavior.
- Use existing behavior tests for these reversible refactors; do not add implementation-mirroring tests.

## Review focus

- Null device records must still reach indexed validation before immutable-list construction.
- Missing Cast application/namespace fields must still produce empty collections.
- Provider filtering must preserve configured order and list mutability.
- Legacy properties must preserve warnings and precedence across property sources.
- Malformed JUnit files must not discard valid results, and failures must remain sorted.

### Task 1: Java cleanup and justified Java exceptions

- [x] Refactor `JsonFileDeviceRegistry.readDevices`, `ReceiverStatus.parse`, `ProviderMatcher.matches`, and `LegacyPropertyNames.apply` without changing their contracts.
- [x] Use `doesNotContainEntry` in `DependencyVerificationWorkflowTest`.
- [x] Add reasoned `java:S3415` method suppressions to the two reported test methods; suppress `java:S6206` on `DataDirectory` and `java:S2143` only at certificate generation.
- [x] Run the existing registry, Cast status, provider, legacy property, storage, deployment, Jellyfin and OAuth tests. Expected: all pass.

### Task 2: Tooling cleanup and bounded sequential exceptions

- [x] Read application scripts concurrently while retaining Map insertion order in `scripts/browser-coverage/report.mjs`.
- [x] Read and parse JUnit files in batches of four in `scripts/pr-summary/summary.mjs`; aggregate in sorted order and preserve per-file failures.
- [x] Document line-local coverage conversion exceptions: sequential conversion and raw-file reading bound memory and ordered updates to the shared coverage map.
- [x] Run `npm test` in both script directories. Expected: all pass.

### Task 3: Record all dispositions and verify

- [x] Record each of the 21 issue keys, its implemented fix or justified exception, and direct SonarCloud link in `docs/superpowers/reviews/2026-09-30-sonar-maintainability.md`.
- [x] Record the six CSS/HTML/Docker findings needing SonarCloud dispositions; do not conceal them with file-wide analysis exclusions.
- [x] Run the required build, inspect frozen architecture-store changes, and run `git diff --check`. Expected: green build, unchanged frozen store, clean diff.
- [x] Obtain an independent code review and resolve material findings.

## Execution notes

- Starting commit: `d0bdb89fdcb3c71944fac03d4d2e393926d71383`; clean checkout, local branch `fix/sonar-maintainability`.
- The Docker wrapper fails because Docker is unavailable. Use installed Java 25 with `./gradlew`, permitted by `docs/dev/testing.md`.
- Local Git/cache writes require sandbox escalation. Branch creation and the baseline targeted tests succeeded with escalation.
- No SonarCloud mutation tool, token, or connected browser is available. Source fixes require a subsequent analysis; external dispositions cannot be applied in this environment.
