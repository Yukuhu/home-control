# Testing

How the test suites are built and how to run them.

## Browser tests

`./gradlew build` (or `scripts/gradle.sh build` without a local JDK) never resolves Playwright or
needs a browser installed — the Playwright-driven dashboard
tests (play sheet, device switching, rail failure, login gating, touchpad) live in their own
`e2e` source set and Gradle task, outside `check`/`build`.

To run them:

```bash
./gradlew installPlaywrightBrowsers   # once; needs root or passwordless sudo, Ubuntu 22.04-26.04
./gradlew e2eTest                     # Chromium and WebKit; -Pe2eBrowsers=chromium to narrow
```

Without a local JDK, `scripts/e2e.sh` builds a `gradle:jdk25`-based image with both browsers
already installed and runs `e2eTest` inside it (a tracked copy of the same
`.superpowers/e2e.sh` this project's agents use). Playwright traces from any run land in
`build/e2e-artifacts/<test>-<browser>.zip`; open one at https://trace.playwright.dev.
