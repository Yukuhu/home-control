# Phase 2A: Configuration Root and Module Switches

**Status:** approved in conversation on 2026-09-28. The user answered each design question and approved each design
section, then said to skip the written-spec and plan reviews and implement directly.

**Roadmap:** `docs/superpowers/specs/2026-09-27-architecture-roadmap-design.md`, workstream "2A: Configuration root
and module switches". The roadmap already approved one visible change used here: "configuration keys are renamed
with the old names still accepted".

## Purpose

The application's configuration root is owned by the Android TV adapter today. `AndroidTvProperties` (`shield.*`)
supplies the data directory, the keystore and the mDNS switch to nine classes. Every other module repeats its on/off
switch as a string. Time settings are a mix of `Duration` keys and 55 integer `*-seconds` keys. After this
workstream:

- the application owns its root (`home-control.*`);
- every device adapter and content source, Android TV included, switches on and off the same way;
- one class knows the file names under `/data`;
- every time setting is a `Duration`.

That serves the roadmap's goals of cheap new devices and sources and code that is easy to navigate.

## Decisions (the user's, 2026-09-28)

1. **Android TV becomes a switchable module,** on by default. AGENTS.md's product rule changes from "Android TV is
   the one that is always on" to "every device adapter and content source can be switched off".
2. **One pull request** for the whole workstream, committed in small steps.
3. **Old keys stay accepted through our own `EnvironmentPostProcessor`,** not through
   `spring-boot-properties-migrator` or duplicate fields on the records.
4. **The spec and plan are written but not reviewed separately.** The user asked for native execution straight after
   the design.

## Constraints

- Existing installs upgrade in place. Every old key keeps working:
  - in `application.yaml`;
  - in a mounted configuration file;
  - as an environment variable (`SHIELD_KEYSTORE_PASSWORD`, `SHIELD_DATA_DIR`,
    `HOME_CONTROL_WEBOS_CONNECT_TIMEOUT_SECONDS`, …);
  - as a command-line argument or a system property.
- Some names never change (AGENTS.md): the CasaOS app id `dev.andre.shield-remote` and the Android TV client package
  name `dev.andre.shield`. Old configuration names also stay accepted, and AGENTS.md keeps saying so.
- Behaviour stays the same by default. The only visible changes are the new key names in the docs, the images and
  `compose.yaml`, and one startup warning per old key in use.
- `./gradlew build` stays green, and the test count stays equal or higher.

## Design

### 1. The configuration root and the old names

- **Package `dev.andre.homecontrol.config`** holds the classes below. It can't be the root package: the root depends
  on every module, so a module depending on the root would create a package cycle, which `ArchitectureTest` forbids.
  It can't be `core` either, which may only depend on the JDK.
- **`config.HomeControlProperties`** (`@ConfigurationProperties("home-control")`) binds:
  - `data-dir` (`Path`, default `./data`);
  - `discovery.enabled` (mDNS, default `true`).

  SSDP keeps its own `home-control.ssdp.*`.
- **`storage.DataDirectory`** is built from `HomeControlProperties.dataDir()`. It gains `path()` and
  `resolve(String name)`, and one constant per file:
  - `KEYSTORE` = `keystore.p12`, `DEVICES` = `devices.json`;
  - `SECRETS` = `secrets.json`, `SECRET_KEY` = `secret.key`;
  - `SOURCES` = `sources.json`, `SPORTS` = `sports.json`, `PINNED` = `pinned.json`;
  - `YOUTUBE_QUOTA` = `youtube-quota.json`.

  The configurations that build these paths today ask `DataDirectory`: `HomeControlConfiguration`,
  `SecurityConfiguration`, the sports, pinned and YouTube configurations, and Android TV. Its error message stops
  saying "Shield data directory".
- **`AndroidTvProperties`** becomes `@ConfigurationProperties("home-control.androidtv")`: `enabled`,
  `keystore-password`, `stale-timeout`, `reconnect-initial-delay` and `reconnect-max-delay`. It keeps only Android TV
  settings and loses `dataDir`, `discoveryEnabled`, `keystoreFile()` and `devicesFile()`.
  - The segment is `androidtv`, matching the package and the adapter id. `HOME_CONTROL_ANDROIDTV_…` environment
    variables map to it. With `android-tv`, the natural-looking `HOME_CONTROL_ANDROID_TV_…` variables would bind to
    `android.tv`.
- **`config.LegacyPropertyNames`** implements `org.springframework.boot.EnvironmentPostProcessor` and `Ordered`. It is
  registered in `META-INF/spring.factories` and ordered just after `ConfigDataEnvironmentPostProcessor`, so
  `application.yaml` and mounted configuration files are loaded before it runs. It holds one explicit table of all 58
  renames (below). For each old name:
  - It finds the first property source that sets the old name in any spelling the binder accepts: `shield.data-dir`,
    `shield.dataDir` or `shield.data_dir` in a file, `SHIELD_DATA_DIR` or `SHIELD_DATADIR` in the environment. It
    looks the names up the way the binder does (`ConfigurationPropertySource`), not by exact key.
  - `application.yaml` keeps `keystore-password: ${SHIELD_KEYSTORE_PASSWORD:shield}`, so the old name still works
    from sources relaxed binding never mapped, such as a system property with that literal name.
  - If the new name is set in the same source or a higher-ranked one, the new name wins. The old value is ignored,
    with a warning.
  - Otherwise it copies the value to the new name, into a `MapPropertySource` named
    `legacyPropertyNames:<source>` and placed directly below the old key's source. A copied value therefore keeps the
    old key's rank: `SHIELD_KEYSTORE_PASSWORD` in the environment beats the new key's default shipped in
    `application.yaml`.
  - A seconds key's value `v` is copied as `v.strip() + "s"`: `10` and ` 10 ` become `10s`, and a placeholder
    `${X:10}` becomes `${X:10}s`, which resolves to `10s`.
  - It logs through `DeferredLogFactory`, once per old key in use:
    `Configuration key shield.data-dir is deprecated; use home-control.data-dir`. If the new key wins, it adds
    `(ignored: home-control.data-dir is also set)`.
- **Values set with `@DynamicPropertySource` are not mapped.** Spring's test framework adds them after environment
  post-processing. Tests use the new names; the legacy tests pass old names as command-line arguments or inlined
  properties.
- **Deployment and build:**
  - `Dockerfile` and `Dockerfile.dist` set `ENV HOME_CONTROL_DATA_DIR=/data` and pass
    `--home-control.data-dir=/data`.
  - `compose.yaml` sets `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD: change-me`.
  - The CasaOS manifests use no `shield` names and stay as they are.
  - `settings.gradle.kts` sets `rootProject.name = "home-control"`. Only the jar's file name changes; the Dockerfiles
    and CI copy `*.jar`.

### 2. Module switches and Android TV

- **`config.Module`** is an enum of every switchable module, with its property segment, its default and its parent:

  | Constant | Segment | Default | Parent |
  | --- | --- | --- | --- |
  | `ANDROIDTV` | `androidtv` | on | – |
  | `CAST` | `cast` | on | – |
  | `WEBOS` | `webos` | on | – |
  | `TIZEN` | `tizen` | on | – |
  | `UPNP` | `upnp` | on | – |
  | `SONOS` | `sonos` | on | – |
  | `BLUETOOTH` | `bluetooth` | off | – |
  | `JELLYFIN` | `jellyfin` | on | – |
  | `YOUTUBE` | `youtube` | on | – |
  | `TMDB` | `tmdb` | on | – |
  | `PINNED` | `pinned` | on | – |
  | `SPORTS` | `sports` | on | – |
  | `THESPORTSDB` | `sports.thesportsdb` | on | `SPORTS` |
  | `WORKFLOWS` | `workflows` | on | – |

  `Module.property()` returns `home-control.<segment>.enabled`.
- **`@ConditionalOnModule(Module)`** is a meta-annotation for `@Conditional(OnModuleCondition.class)`, on types and
  `@Bean` methods. `OnModuleCondition` extends `SpringBootCondition`. A module is on when its property (default: the
  module's default) is `true` and its parent is on. It replaces all 32 `@ConditionalOnProperty` annotations in 32
  classes. The defaults live in one place, and a misspelt module no longer compiles.
- **Test lists derive from `Module.values()`:**
  - `ModulesOffTest` switches every module off, Android TV included, through its inherited `@DynamicPropertySource`
    instead of an annotation list;
  - `HomeControlApplicationTest`'s `SWITCHABLE` set holds every module without a parent.
- **Android TV becomes a module:**
  - A new `adapters.androidtv.AndroidTvConfiguration`, annotated `@ConditionalOnModule(Module.ANDROIDTV)`, declares
    `CertificateStore` (moved from `HomeControlConfiguration`), `MdnsDiscovery`, `AndroidTvAdapter` and
    `PairingService`. They lose their `@Component` and `@Service` annotations. The shared `MdnsBrowser` stays in
    `HomeControlConfiguration`.
  - **Pairing by code gets a `core` interface,** next to the existing `core.PromptPairing`.
    `core.CodePairing` has `boolean inProgress()`, `void begin(String host, String name) throws IOException` and
    `CodePairingOutcome submit(String code)`. `PairingService` implements it, and `PairingOutcome` moves to `core` as
    `CodePairingOutcome`.
  - **`SetupController` takes `Optional<CodePairing>`** instead of `PairingService`. `POST /setup/pair` and
    `POST /setup/code` keep their URLs and render the setup page as today. Without the module they answer 404.
    `setup.html` shows the Android TV pairing form only when a `CodePairing` exists (`codePairing` model attribute).
    This removes the six frozen violations of "web does not depend on adapters" in store
    `ba1e77bf-4f55-4da3-9db0-7f64cf972c19` and their lines in `34477544-f6df-4e51-a96d-cc0f65ebc691`.
  - `JsonFileDeviceRegistry` keeps migrating old `devices.json` files: it only uses the adapter id constant.
    A device that still carries an Android TV entry shows no Android TV controls, as with Cast today.
  - Code that checked for an `androidtv` entry in `devices.json` must check what the device can do now. Jellyfin's
    route executor wakes and launches the app only when the device has the `APP_LINK` and `REMOTE_KEYS`
    capabilities; otherwise it plays the open Jellyfin session directly.
  - `DeviceManager` refuses to merge or split an entry whose module is switched off: its pairing may be bound to the
    device id, as Android TV's is.
- **`@Value` reads move into the records:**
  - `SetupController` takes `DeepLinkTestProperties.timeout()`;
  - both Jellyfin beans take `JellyfinProperties.startupTimeout()`.
- **The 15 redundant `@EnableConfigurationProperties`** go. `@ConfigurationPropertiesScan` on `HomeControlApplication`
  already binds every record, whether its module is on or off, as it does today.
- **AGENTS.md's product rule** becomes: "Device adapters and content sources are modules that can be switched off,
  with `home-control.<module>.enabled` (`config.Module` lists them). Bluetooth is off by default, the rest are on."

### 3. Durations

- Every `*-seconds` component of a properties record becomes a `Duration` without the suffix, as in the table below.
  Each carries:
  - `@DurationUnit(ChronoUnit.SECONDS)`, so a bare number still means seconds;
  - a `@DefaultValue` in seconds (`"10s"`);
  - `@DurationMin`, in place of `@Positive` or `@Min(0)`: `nanos = 1` for positive, `nanos = 0` for zero or more.
  Keys that are already `Duration`s keep their units.
- The records' users take the `Duration` directly. Since 1.3b the sessions take `Duration`s, so the conversions at
  construction go away.
- `application.yaml`, `src/test/resources/config/application.yaml`, the tests and `docs/user/configuration.md` use
  the new names.

### The renames

| Old name (still accepted) | New name | Default |
| --- | --- | --- |
| `shield.data-dir` | `home-control.data-dir` | `./data`; the images pass `/data` |
| `shield.discovery-enabled` | `home-control.discovery.enabled` | `true` |
| `shield.keystore-password` | `home-control.androidtv.keystore-password` | `shield` |
| `shield.stale-timeout-seconds` | `home-control.androidtv.stale-timeout` | `10s` |
| `shield.reconnect-initial-delay-seconds` | `home-control.androidtv.reconnect-initial-delay` | `1s` |
| `shield.reconnect-max-delay-seconds` | `home-control.androidtv.reconnect-max-delay` | `60s` |
| `home-control.ssdp.search-interval-seconds` | `home-control.ssdp.search-interval` | `60s` |
| `home-control.webos.connect-timeout-seconds` | `home-control.webos.connect-timeout` | `3s` |
| `home-control.webos.request-timeout-seconds` | `home-control.webos.request-timeout` | `10s` |
| `home-control.webos.pairing-timeout-seconds` | `home-control.webos.pairing-timeout` | `60s` |
| `home-control.webos.reconnect-initial-delay-seconds` | `home-control.webos.reconnect-initial-delay` | `1s` |
| `home-control.webos.reconnect-max-delay-seconds` | `home-control.webos.reconnect-max-delay` | `30s` |
| `home-control.webos.wake-grace-seconds` | `home-control.webos.wake-grace` | `3s` |
| `home-control.webos.liveness-interval-seconds` | `home-control.webos.liveness-interval` | `30s` |
| `home-control.tizen.connect-timeout-seconds` | `home-control.tizen.connect-timeout` | `3s` |
| `home-control.tizen.request-timeout-seconds` | `home-control.tizen.request-timeout` | `5s` |
| `home-control.tizen.pairing-timeout-seconds` | `home-control.tizen.pairing-timeout` | `30s` |
| `home-control.tizen.poll-interval-seconds` | `home-control.tizen.poll-interval` | `5s` |
| `home-control.tizen.wake-grace-seconds` | `home-control.tizen.wake-grace` | `3s` |
| `home-control.upnp.poll-interval-seconds` | `home-control.upnp.poll-interval` | `2s` |
| `home-control.upnp.idle-poll-interval-seconds` | `home-control.upnp.idle-poll-interval` | `10s` |
| `home-control.upnp.command-timeout-seconds` | `home-control.upnp.command-timeout` | `5s` |
| `home-control.upnp.connect-timeout-seconds` | `home-control.upnp.connect-timeout` | `3s` |
| `home-control.upnp.reconnect-initial-delay-seconds` | `home-control.upnp.reconnect-initial-delay` | `1s` |
| `home-control.upnp.reconnect-max-delay-seconds` | `home-control.upnp.reconnect-max-delay` | `60s` |
| `home-control.sonos.poll-interval-seconds` | `home-control.sonos.poll-interval` | `2s` |
| `home-control.sonos.idle-poll-interval-seconds` | `home-control.sonos.idle-poll-interval` | `10s` |
| `home-control.sonos.topology-interval-seconds` | `home-control.sonos.topology-interval` | `30s` |
| `home-control.sonos.command-timeout-seconds` | `home-control.sonos.command-timeout` | `5s` |
| `home-control.sonos.connect-timeout-seconds` | `home-control.sonos.connect-timeout` | `3s` |
| `home-control.sonos.reconnect-initial-delay-seconds` | `home-control.sonos.reconnect-initial-delay` | `1s` |
| `home-control.sonos.reconnect-max-delay-seconds` | `home-control.sonos.reconnect-max-delay` | `60s` |
| `home-control.bluetooth.scan-seconds` | `home-control.bluetooth.scan-duration` | `10s` |
| `home-control.bluetooth.bluez-timeout-seconds` | `home-control.bluetooth.bluez-timeout` | `45s` |
| `home-control.bluetooth.poll-interval-seconds` | `home-control.bluetooth.poll-interval` | `5s` |
| `home-control.bluetooth.playing-poll-interval-seconds` | `home-control.bluetooth.playing-poll-interval` | `1s` |
| `home-control.bluetooth.player-start-timeout-seconds` | `home-control.bluetooth.player-start-timeout` | `5s` |
| `home-control.bluetooth.load-timeout-seconds` | `home-control.bluetooth.load-timeout` | `15s` |
| `home-control.bluetooth.command-timeout-seconds` | `home-control.bluetooth.command-timeout` | `3s` |
| `home-control.bluetooth.host-check-cache-seconds` | `home-control.bluetooth.host-check-cache-ttl` | `30s` |
| `home-control.cast.heartbeat-interval-seconds` | `home-control.cast.heartbeat-interval` | `5s` |
| `home-control.cast.stale-timeout-seconds` | `home-control.cast.stale-timeout` | `15s` |
| `home-control.cast.reconnect-initial-delay-seconds` | `home-control.cast.reconnect-initial-delay` | `1s` |
| `home-control.cast.reconnect-max-delay-seconds` | `home-control.cast.reconnect-max-delay` | `60s` |
| `home-control.cast.command-timeout-seconds` | `home-control.cast.command-timeout` | `5s` |
| `home-control.cast.load-timeout-seconds` | `home-control.cast.load-timeout` | `20s` |
| `home-control.cast.media-status-interval-seconds` | `home-control.cast.media-status-interval` | `5s` |
| `home-control.jellyfin.connect-timeout-seconds` | `home-control.jellyfin.connect-timeout` | `5s` |
| `home-control.jellyfin.request-timeout-seconds` | `home-control.jellyfin.request-timeout` | `15s` |
| `home-control.jellyfin.startup-timeout-seconds` (read with `@Value` today) | `home-control.jellyfin.startup-timeout` | `30s` |
| `home-control.tmdb.connect-timeout-seconds` | `home-control.tmdb.connect-timeout` | `5s` |
| `home-control.tmdb.request-timeout-seconds` | `home-control.tmdb.request-timeout` | `10s` |
| `home-control.sports.calendar.connect-timeout-seconds` | `home-control.sports.calendar.connect-timeout` | `5s` |
| `home-control.sports.calendar.request-timeout-seconds` | `home-control.sports.calendar.request-timeout` | `15s` |
| `home-control.sports.thesportsdb.connect-timeout-seconds` | `home-control.sports.thesportsdb.connect-timeout` | `5s` |
| `home-control.sports.thesportsdb.request-timeout-seconds` | `home-control.sports.thesportsdb.request-timeout` | `15s` |
| `home-control.youtube.connect-timeout-seconds` | `home-control.youtube.connect-timeout` | `5s` |
| `home-control.youtube.request-timeout-seconds` | `home-control.youtube.request-timeout` | `15s` |

`SHIELD_KEYSTORE_PASSWORD` reaches `shield.keystore-password` through relaxed binding, so it is covered by the third
row.

## Testing

- **`OnModuleConditionTest`** uses `ApplicationContextRunner` with a bean guarded by `@ConditionalOnModule`. It covers:
  - default on;
  - switched off;
  - Bluetooth's default off;
  - the child on while its parent `SPORTS` is off, which means off.
- **`LegacyPropertyNamesTest`** runs on a `StandardEnvironment` with the property sources a real start has. It covers:
  - an old key copied to the new name;
  - a seconds value gaining its unit;
  - an old environment variable (`SHIELD_KEYSTORE_PASSWORD`) beating the new key's `application.yaml` default;
  - a new key at the same or a higher rank winning, with an "ignored" warning;
  - one warning per old key in use;
  - no source added when no old key is set.
- **`LegacyPropertyNamesBindingTest`** sets every old name in the table to a distinctive value. It binds every
  properties record with a `Binder` after the post-processor, and checks that each record carries the value. That
  also catches a table entry whose new name binds nothing.
- **`LegacyConfigurationStartupTest`** starts the application with `SpringApplicationBuilder`, as
  `EventStreamShutdownEndToEndTest` does, using only old names:
  - `--shield.data-dir`;
  - `--shield.keystore-password`;
  - `--home-control.webos.connect-timeout-seconds=7`.

  It checks the application starts, uses that data directory, and binds `WebOsProperties.connectTimeout()` to 7 s.
- **`DataDirectoryTest`** covers `resolve` and the constants.
- **Android TV:**
  - `ModulesOffTest` switches Android TV off. `ModulesOffSmokeTest` checks that no `AndroidTvAdapter`,
    `PairingService`, `CertificateStore` or `CodePairing` bean remains.
  - A new `AndroidTvModuleSwitchTest` on the modules-off context checks that the setup page has no Android TV pairing
    form, and that `POST /setup/pair` and `/setup/code` answer 404.
  - `HomeControlApplicationTest.noSwitchableModuleNeedsAnotherModulesBean` covers `androidtv` through `SWITCHABLE`.
  - The existing pairing tests in the web slice mock `CodePairing` instead of `PairingService`, and keep their cases.
- **Durations:** each record's test, where one exists, asserts `Duration`s. `CastPropertiesTest` and
  `TmdbPropertiesTest` check binding a bare number as seconds.
- **`ArchitectureTest`:** the frozen store loses the SetupController lines. No new violation may appear.

## Out of scope

- Renaming `home-control.ssdp.*` under `home-control.discovery.*`.
- Removing the old names. They stay accepted indefinitely, as AGENTS.md says.
- The CasaOS app id and the Android TV client package name.
- Changing any default value or unit of a key that is already a `Duration`.
- `RailCache`'s restart gap (roadmap 2D).

## Risks

- **A copied value lands at the wrong rank** and a user's old setting silently loses to a shipped default. Covered by
  the precedence cases in `LegacyPropertyNamesTest` and the startup test.
- **A seconds value with a unit already** (`10s` in an old `-seconds` key) becomes `10ss` and fails at startup. It was
  invalid for the old `int` key too, so nothing that worked breaks.
- **Switching Android TV off leaves a bean that needs it.** Covered by the modules-off smoke test and the
  module-independence check.
