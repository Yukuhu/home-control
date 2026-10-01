# Working on Home Control

Rules for everyone who changes this repository, people and coding agents alike. The
[developer guides](docs/dev/) explain the architecture, the tests and CI in depth.

## Build and test

- There may be no local JDK. `scripts/gradle.sh <arguments>` runs `./gradlew` in the `gradle:jdk25` Docker image;
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

- Modules: the root project is the app. `core/` holds the domain model (`dev.andre.homecontrol.core`), a Gradle
  module that depends on the JDK alone; run its tests with `scripts/gradle.sh :core:test`. See
  [Modules](docs/dev/architecture.md#modules).
- Device adapters: `src/main/java/dev/andre/homecontrol/adapters/<device>/`, with the wire protocol in `protocol/`.
- Content sources: `src/main/java/dev/andre/homecontrol/sources/<source>/`.
- Tests sit in the same package as the code they test. Fakes of devices and services are named `Fake…` and speak
  the real protocol; recorded device and API responses are in `src/test/resources/fixtures/<device or source>/`.
- Shared test helpers: `src/test/java/dev/andre/homecontrol/testsupport/`. Build a new web-API fake on
  `FakeHttpServer` instead of opening a server of its own.
- Test configuration: `src/test/resources/config/application.yaml` holds only overrides of the production
  `application.yaml`.
- User guides are in `docs/user/`, developer guides in `docs/dev/`. Specs, plans and reviews of larger pieces of work
  are in `docs/superpowers/`.

## Commits and pull requests

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`, `refactor:`,
  `test:`, `docs:`, `build:`, `ci:`, `chore:`. Releases and the changelog are made from them; see
  [CI and releases](docs/dev/ci-and-releases.md).
- Pull requests follow `.github/pull_request_template.md`.
- Stage only the files you changed (`git add <paths>`, never `git add -A`); other work may be in progress in the same
  tree.
- A deliberate exception to a SonarCloud rule uses the narrowest `@SuppressWarnings("java:S…")` possible, with a
  one-line reason.
