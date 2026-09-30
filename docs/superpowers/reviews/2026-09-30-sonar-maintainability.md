# SonarCloud maintainability cleanup — 2026-09-30

The live query for `Yukuhu_home-control`, branch `main`, returned **21 OPEN/CONFIRMED maintainability issues**. This change addresses **15 issue keys in source**: **7 refactors and 8 scoped exceptions**. The other **6** need SonarCloud dispositions: **4 false positives and 2 accepted product choices**. No external statuses have been changed, and a subsequent analysis must confirm the source fixes.

## Scope and constraints

The clean starting checkout was `d0bdb89fdcb3c71944fac03d4d2e393926d71383`; work is on local branch `fix/sonar-maintainability`. The live query used the explicit main branch, the MAINTAINABILITY impact filter, OPEN/CONFIRMED statuses, and a 500-item page; its total was 21. All 12 distinct rule definitions were read. Reported line numbers can lag the checkout, so locations below use paths and the code sites were inspected directly.

The [previous triage](2026-09-29-sonarcloud-triage.md) documented the remaining deliberate choices and false positives. This change preserves those decisions while implementing the previously deferred collection/control-flow and independent-read cleanups.

- Device JSON formats, migration, ordering and validation are preserved, including the indexed error for a null device.
- Provider matching retains its mutable result and configured order.
- Legacy properties retain precedence and warning behavior, including `shield.*` aliases.
- The certificate uses Instant/Duration internally and converts only at BouncyCastle's Date-based API.
- DataDirectory stays a filesystem service with identity semantics.
- Correct AssertJ actual/expected argument order is retained.
- Application-script reads run concurrently. Coverage conversion remains sequential to bound memory.
- JUnit reads run in batches of four; aggregation stays sorted and unreadable files do not discard valid results. The bounded batch await is an explicit exception, as allowed by S9382's resource-cap rationale.
- No analysis exclusions, quality-profile changes or broad Java suppressions were added.

## Complete issue ledger

“Scoped exception” means an in-source, documented suppression at the narrowest supported declaration or line. “Pending” requires a SonarCloud issue disposition, not an application rewrite.

| Issue | Rule | File | Action | Reason |
| --- | --- | --- | --- | --- |
| [AaDzmbCyRDWz2fwaTTJQ](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDzmbCyRDWz2fwaTTJQ&branch=main) | `java:S5838` | `src/test/java/dev/andre/homecontrol/deployment/DependencyVerificationWorkflowTest.java` | Fix | Dedicated AssertJ map assertion, `doesNotContainEntry("continue-on-error", true)`, retains the absent-or-false condition. |
| [AaDtbUXzjz4Vp6fZsVxn](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDtbUXzjz4Vp6fZsVxn&branch=main) | `java:S9391` | `src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java` | Fix | Map device nodes with `valueStream()`; keep null records until indexed validation, then return the immutable copy. |
| [AaDrqsRSK9zMojdxnIGd](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDrqsRSK9zMojdxnIGd&branch=main) | `java:S135` | `src/main/java/dev/andre/homecontrol/config/LegacyPropertyNames.java` | Fix | Use an else branch for the ignored-new-name case, retaining the missing-old-name continue, property-source precedence and warning text. |
| [AaDoIwyaPuP0RBWqQYvh](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDoIwyaPuP0RBWqQYvh&branch=main) | `java:S3415` | `src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutorTest.java` | Scoped exception | Method-local `java:S3415`: production retry duration is the actual value; independently constructed duration is expected. |
| [AaDoIwyaPuP0RBWqQYvi](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDoIwyaPuP0RBWqQYvi&branch=main) | `java:S3415` | `src/test/java/dev/andre/homecontrol/sources/jellyfin/JellyfinRouteExecutorTest.java` | Scoped exception | Same method-local `java:S3415`: production pause step is the actual value; independently constructed duration is expected. |
| [AaDoIwoQPuP0RBWqQYvg](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDoIwoQPuP0RBWqQYvg&branch=main) | `java:S3415` | `src/test/java/dev/andre/homecontrol/sources/youtube/GoogleOAuthClientTest.java` | Scoped exception | Method-local `java:S3415`: the production polling floor is the actual value; the one-second duration is expected. |
| [AaDuLmTG3f2ysA7Z5683](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDuLmTG3f2ysA7Z5683&branch=main) | `javascript:S9382` | `scripts/pr-summary/summary.mjs` | Fix | Read/parse four JUnit files concurrently per batch and aggregate in sorted order. The batch await has a line-local memory-bound exception. |
| [AaDuLmTm3f2ysA7Z5684](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDuLmTm3f2ysA7Z5684&branch=main) | `javascript:S9382` | `scripts/browser-coverage/report.mjs` | Fix | Read independent application scripts with Promise.all; construct the Map afterward to retain the original insertion order. |
| [AaDuLmTm3f2ysA7Z5685](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDuLmTm3f2ysA7Z5685&branch=main) | `javascript:S9382` | `scripts/browser-coverage/report.mjs` | Scoped exception | Line-local NOSONAR: sequential zero-coverage conversion bounds converter memory. |
| [AaDuLmTm3f2ysA7Z5686](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDuLmTm3f2ysA7Z5686&branch=main) | `javascript:S9382` | `scripts/browser-coverage/report.mjs` | Scoped exception | Line-local NOSONAR: retain one raw capture file at a time instead of loading every capture into memory. |
| [AaDuLmTm3f2ysA7Z5687](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDuLmTm3f2ysA7Z5687&branch=main) | `javascript:S9382` | `scripts/browser-coverage/report.mjs` | Scoped exception | Line-local NOSONAR: convert one entry at a time, bounding converter memory and updating the shared coverage map in order. |
| [AaDKnB7XHkjsOnJ6opKD](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnB7XHkjsOnJ6opKD&branch=main) | `java:S2143` | `src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/ClientCertificate.java` | Scoped exception | Remove the Date import and use a method-local `java:S2143` exception only at certificate generation; BouncyCastle's API requires Date, and validity arithmetic already uses Instant/Duration. |
| [AaDKnCMhHkjsOnJ6opNK](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnCMhHkjsOnJ6opNK&branch=main) | `css:S7924` | `src/main/resources/static/app.css` | FALSE_POSITIVE pending | Drawer-toggle accent text on its alpha-composited default background has 9.54:1 contrast; the selected-card gradient endpoints have at least 7.17:1. |
| [AaDKnCMhHkjsOnJ6opNL](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnCMhHkjsOnJ6opNL&branch=main) | `css:S7924` | `src/main/resources/static/app.css` | FALSE_POSITIVE pending | Placeholder text over the art-gradient endpoints, with the maximum radial tint composited, has at least 5.17:1 contrast. |
| [AaDKnCNpHkjsOnJ6opNX](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnCNpHkjsOnJ6opNX&branch=main) | `Web:S9379` | `src/main/resources/templates/login.html` | ACCEPTED pending | Retain the documented autofocus choice for the labeled password field on the single-field login page; its accessibility tradeoff remains real. |
| [AaDKnCteHkjsOnJ6opVR](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnCteHkjsOnJ6opVR&branch=main) | `docker:S6595` | `scripts/e2e.Dockerfile` | FALSE_POSITIVE pending | Playwright installs OS packages with --with-deps inside the same RUN as apt-get update; the layer then removes /var/lib/apt/lists. |
| [AaDtbUMFjz4Vp6fZsVxl](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDtbUMFjz4Vp6fZsVxl&branch=main) | `java:S9391` | `src/main/java/dev/andre/homecontrol/adapters/cast/protocol/ReceiverStatus.java` | Fix | Map application and namespace nodes with Jackson valueStream(), retaining missing-field defaults and immutable lists. |
| [AaDtbT_yjz4Vp6fZsVxk](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDtbT_yjz4Vp6fZsVxk&branch=main) | `java:S9391` | `src/main/java/dev/andre/homecontrol/sources/tmdb/ProviderMatcher.java` | Fix | Filter configured provider keys with a stream; retain configured order and the mutable ArrayList result. |
| [AaDKnCOhHkjsOnJ6opNb](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnCOhHkjsOnJ6opNb&branch=main) | `Web:S6845` | `src/main/resources/templates/dashboard.html` | FALSE_POSITIVE pending | The custom touchpad implements Arrow keys, Enter and Space in touchpad.js. Its tab stop is necessary for keyboard access. |
| [AaDKnCOhHkjsOnJ6opNd](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDKnCOhHkjsOnJ6opNd&branch=main) | `Web:S6819` | `src/main/resources/templates/dashboard.html` | ACCEPTED pending | Retain the documented custom radio buttons: checked state, roving tabindex, arrow-key selection and native button Space activation are implemented. |
| [AaDrqsOHK9zMojdxnIGc](https://sonarcloud.io/project/issues?id=Yukuhu_home-control&issues=AaDrqsOHK9zMojdxnIGc&branch=main) | `java:S6206` | `src/main/java/dev/andre/homecontrol/storage/DataDirectory.java` | Scoped exception | Class-local `java:S6206`: DataDirectory is a filesystem service with identity semantics; a record would change equality, hashCode and toString. |

## Pending external dispositions

Apply the four FALSE_POSITIVE and two ACCEPTED dispositions in the ledger with their individual reasons. There is no SonarCloud mutation tool or SONAR_TOKEN in this environment, and browser discovery returned no connected browser. The read-only SonarCloud connector supplies the issue and rule inventory but cannot submit dispositions.

The contrast calculations were repeated against the current default-theme selectors, composing sRGB colors before calculating relative luminance. They cover the flagged drawer and placeholder rules and their gradient endpoints; they do not claim an audit of every theme or artwork.

The Docker finding is unchanged: update, Playwright's indirect package installation, and apt-list cleanup are already in one layer. The HTML decisions retain the documented keyboard behavior and login product choice. Hiding these with file-wide scanner exclusions would obscure future findings.

## Verification

- The baseline targeted Java run passed, then the same **116 tests** passed after the Java edits.
- Both tooling npm test commands exited successfully. Their sandboxed child-process output reported file-level results only, so an additional direct `node --test --test-isolation=none scripts/browser-coverage/report.test.mjs scripts/pr-summary/summary.test.mjs` run verified **62 individual tests**, all passing.
- Full `./gradlew build --console=plain` passed in 1m 43s: **3,156 tests** across **377 suites**, no failures/errors, and one opt-in test skipped.
- Docker is unavailable, so `scripts/gradle.sh build` fails at launcher startup. Verification uses installed Java 25 and the wrapper directly, as permitted by `docs/dev/testing.md`.
- `git diff --check` passed and the frozen architecture store is unchanged.
- Independent read-only review found no Critical, Important or Minor issues and verified the test-report counts and the complete ledger. Source cleanup is ready for integration.
- The reviewer could not verify closure in SonarCloud without a subsequent analysis, apply the six external dispositions through the read-only connection, or claim a fresh accessibility audit across every theme/browser. These remain explicitly outside the completed source verification.
