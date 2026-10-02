# Working on Home Control

Rules for everyone who changes this repository, people and coding agents alike. The
[developer guides](docs/dev/) explain the architecture, the tests and CI in depth.

## Build and test

- There may be no local JDK. `scripts/gradle.sh <arguments>` runs `./gradlew` in the `gradle:jdk25` image using Docker or Podman;
  use it wherever the documentation says `./gradlew`. See [Running tests](docs/dev/testing.md#running-tests).
- A change is done when `scripts/gradle.sh build` is green.
- Browser tests run with `scripts/e2e.sh`; see [Browser tests](docs/dev/testing.md#browser-tests).

## Stack

- Java 25, Spring Boot 4.1.1, Jackson 3 (packages `tools.jackson.*`). MockMvc's test auto-configuration is in
  `org.springframework.boot.webmvc.test.autoconfigure`.
- Thymeleaf, htmx, server-sent events and plain ES modules. No Node build and no frontend framework.

## Product rules

- **A LAN appliance.** One Docker container, run with Compose or CasaOS. No cloud service; HTTPS is a reverse
  proxy's job.
- **Commands are ephemeral.** A request that cannot be carried out now fails now, with a reason. Nothing is queued.
- **State is JSON under `/data`, written atomically.** Secrets are encrypted at rest and never reach the browser.
- **Existing installs upgrade in place.** A change to a `/data` format migrates the old format when it reads it.
- **Device adapters and content sources are modules that can be switched off**, with
  `home-control.<module>.enabled` (`config.Module` lists them). Bluetooth is off by default, the rest are on.
- **Some names never change:**
  - configuration under `shield.*` and `SHIELD_KEYSTORE_PASSWORD` keeps working;
  - the CasaOS app id stays `dev.andre.shield-remote`;
  - the Android TV client package name sent to devices stays `dev.andre.shield`.

## Architecture rules

- Only adapters speak device protocols, and only sources speak content APIs. `ArchitectureTest` enforces the package
  rules, and the Gradle modules what each module may depend on; see [Architecture](docs/dev/architecture.md).
- Every package rule is strict. A change that breaks one changes the code, not the rule; a rule that needs an
  exception names it in the rule, with its reason.
- Decisions that are hard to reverse get an [architecture decision record](docs/adr/README.md).

## Where things live

- Modules: the root project is the app. `core/` holds the domain model (`dev.andre.homecontrol.core`), which depends
  on the JDK alone. `protocols/` holds the Spring-free wire libraries: every `protocol` package, `adapters.net` and
  `sources.sports.ics`. Run a module's tests with `scripts/gradle.sh :core:test` or `:protocols:test`. See
  [Modules](docs/dev/architecture.md#modules).
- Device adapters: `src/main/java/dev/andre/homecontrol/adapters/<device>/`, with the wire protocol in
  `protocols/src/main/java/dev/andre/homecontrol/adapters/<device>/protocol/`.
- Content sources: `src/main/java/dev/andre/homecontrol/sources/<source>/`.
- Tests sit in the same package as the code they test, in the code's module. Fakes of devices and services are named
  `Fake…` and speak the real protocol. A fake the app's tests share with the protocol tests sits in `protocols`' test
  fixtures (`protocols/src/testFixtures/java/`). Recorded device and API responses are in
  `src/test/resources/fixtures/<device or source>/`, or in `protocols/src/testFixtures/resources/fixtures/<device>/`
  for the protocols' own. Read a recording with `Fixtures.read`.
- Shared test helpers: `src/test/java/dev/andre/homecontrol/testsupport/`, and `Fixtures`, `Request` and `TestTls`
  in `protocols/src/testFixtures/java/dev/andre/homecontrol/testsupport/`. Build a new web-API fake on
  `FakeHttpServer` instead of opening a server of its own.
- Test configuration: `src/test/resources/config/application.yaml` holds only overrides of the production
  `application.yaml`.
- User guides are in `docs/user/`, developer guides in `docs/dev/`. Specs, plans and reviews of larger pieces of work
  are in `docs/superpowers/`.

## Commits and pull requests

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`, `refactor:`,
  `test:`, `docs:`, `build:`, `ci:`, `chore:`, and `revert:`. Releases and the changelog are made from them, and CI
  checks every commit of a pull request (`scripts/check-commits.sh`); see
  [CI and releases](docs/dev/ci-and-releases.md).
- Pull requests follow `.github/pull_request_template.md`.
- Stage only the files you changed (`git add <paths>`, never `git add -A`); other work may be in progress in the same
  tree.
- A deliberate exception to a SonarCloud rule uses the narrowest `@SuppressWarnings("java:S…")` possible, with a
  one-line reason. The same goes for an Error Prone check, which fails compilation: `@SuppressWarnings("CheckName")`.
