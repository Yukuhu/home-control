# Phase 2A Configuration Root and Module Switches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The application owns a `home-control.*` configuration root. Every device adapter and content source, Android TV included, switches through `@ConditionalOnModule`. One class knows the file names under `/data`. Every time setting is a `Duration`. The 58 old names stay accepted.

**Architecture:** A new `config` package holds four things:
- `HomeControlProperties`;
- the `Module` enum;
- `@ConditionalOnModule` and its condition;
- `LegacyPropertyNames`, an `EnvironmentPostProcessor` that copies each old key's value to its new name at the old key's rank, and logs a deprecation warning.

Android TV gets its own conditional configuration and a `core.CodePairing` interface, so the web layer no longer imports it. Properties records swap their `int …Seconds` components for `Duration`s.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (`org.springframework.boot.EnvironmentPostProcessor`, `DeferredLogFactory`, `SpringBootCondition`, `@DurationUnit`), Hibernate Validator 9.1 (`@DurationMin`), JUnit 6, ArchUnit 1.5.1, Gradle through `scripts/gradle.sh`.

**Spec:** `docs/superpowers/specs/2026-09-28-phase-2a-configuration-root-design.md` (it holds the full rename table).

## Global Constraints

- **Start condition:** #138 is merged. Branch `refactor/configuration-root` from `origin/main` (`0aabed6`). One pull request (the user's decision, 2026-09-28).
- **Execution:** Native (executing-plans). The user waived the spec and plan reviews.
- **Behaviour stays the same by default.** The only visible changes are the new key names in the docs, the images and `compose.yaml`, and one startup warning per old key in use.
- **Old names stay accepted:** every row of the spec's rename table, in YAML, mounted files, environment variables (`SHIELD_KEYSTORE_PASSWORD`), command-line arguments and system properties.
- **Some names never change:** the CasaOS app id `dev.andre.shield-remote` and the Android TV client package `dev.andre.shield`.
- **Frozen violations only fall.** Commit the smaller `src/test/archunit-store` when a fix removes lines. Never refreeze.
- **Tests:** the count stays equal or higher. A moved test names its replacement in the commit message.
- **Build only through `scripts/gradle.sh`.** It is quiet: results land in `build/test-results/test/*.xml`.
- **A change is done when `scripts/gradle.sh build` is green.**
- **Commits:**
  - Conventional Commits, ending with the session's `Co-Authored-By:` and `Claude-Session:` trailers.
  - Stage only your own files with `git add <paths>`.
  - Refactors are `refactor:`. User-visible renames go in a `feat:` commit whose body names the new keys, because the release notes come from commit messages.

## Review Focus

- **An old key in a mounted configuration file** (for example `/app/config/application.yaml` with `shield.data-dir`) must beat the jar's new default. Test: `LegacyPropertyNamesTest.anOldKeyInAHigherRankedFileBeatsTheShippedDefault` (Task 2).
- **An existing install's `SHIELD_KEYSTORE_PASSWORD`** must still open its keystore. A wrong password loses every pairing. Test: `LegacyPropertyNamesTest.anOldEnvironmentVariableBeatsTheShippedDefault` (Task 2), and `LegacyConfigurationStartupTest` binding `AndroidTvProperties.keystorePassword()` (Task 7).
- **A bare number in a new `Duration` key** (`stale-timeout: 10`) must mean seconds, as the old key did. Test: `CastPropertiesTest.aBareNumberMeansSeconds` and `TmdbPropertiesTest.aBareNumberMeansSeconds` (Task 6).
- **Android TV switched off with Android TV devices in `devices.json`:** the dashboard and the setup page still render. Test: `AndroidTvModuleSwitchTest.aDeviceWithAnAndroidTvEntryStillRenders` (Task 5).
- **A seconds value written as a placeholder** (`${X:10}`) keeps working after it is copied. Test: `LegacyPropertyNamesTest.aSecondsPlaceholderGainsItsUnit` (Task 2).

---

### Task 1: `Module`, `@ConditionalOnModule` and `OnModuleCondition`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/config/Module.java`, `src/main/java/dev/andre/homecontrol/config/ConditionalOnModule.java`, `src/main/java/dev/andre/homecontrol/config/OnModuleCondition.java`
- Test: `src/test/java/dev/andre/homecontrol/config/OnModuleConditionTest.java`

**Interfaces:**
- Produces:
  - `public enum Module` with `String segment()`, `boolean enabledByDefault()`, `Optional<Module> parent()` and `String property()`;
  - `@ConditionalOnModule(Module value)`.

- [ ] **Step 1: The failing test**

```java
package dev.andre.homecontrol.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OnModuleConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Guarded.class);

    @Configuration(proxyBeanMethods = false)
    static class Guarded {

        @Bean
        @ConditionalOnModule(Module.JELLYFIN)
        String jellyfinBean() {
            return "jellyfin";
        }

        @Bean
        @ConditionalOnModule(Module.BLUETOOTH)
        String bluetoothBean() {
            return "bluetooth";
        }

        @Bean
        @ConditionalOnModule(Module.THESPORTSDB)
        String theSportsDbBean() {
            return "thesportsdb";
        }
    }

    @Test
    void aModuleIsOnByDefault() {
        runner.run(context -> assertThat(context).hasBean("jellyfinBean").hasBean("theSportsDbBean"));
    }

    @Test
    void aModuleCanBeSwitchedOff() {
        runner.withPropertyValues("home-control.jellyfin.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("jellyfinBean"));
    }

    @Test
    void bluetoothIsOffUnlessSwitchedOn() {
        runner.run(context -> assertThat(context).doesNotHaveBean("bluetoothBean"));
        runner.withPropertyValues("home-control.bluetooth.enabled=true")
                .run(context -> assertThat(context).hasBean("bluetoothBean"));
    }

    @Test
    void aModuleIsOffWhenItsParentIs() {
        runner.withPropertyValues("home-control.sports.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("theSportsDbBean"));
    }

    @Test
    void onlyTrueSwitchesAModuleOnAsBefore() {
        runner.withPropertyValues("home-control.jellyfin.enabled=TRUE")
                .run(context -> assertThat(context).hasBean("jellyfinBean"));
        runner.withPropertyValues("home-control.jellyfin.enabled=yes")
                .run(context -> assertThat(context).doesNotHaveBean("jellyfinBean"));
    }

    @Test
    void everyModuleNamesItsProperty() {
        assertThat(Module.THESPORTSDB.property()).isEqualTo("home-control.sports.thesportsdb.enabled");
        assertThat(Module.ANDROIDTV.property()).isEqualTo("home-control.androidtv.enabled");
        assertThat(Module.THESPORTSDB.parent()).contains(Module.SPORTS);
    }
}
```

- [ ] **Step 2: Run it, and watch it fail**

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.config.OnModuleConditionTest'`. Expected: FAIL at `compileTestJava` with `cannot find symbol: class ConditionalOnModule`.

- [ ] **Step 3: The three classes**

`Module.java`:

```java
package dev.andre.homecontrol.config;

import java.util.Optional;

/**
 * Every device adapter and content source that can be switched off, each with {@code home-control.<segment>.enabled}
 * and its default. The one place that lists them: {@link ConditionalOnModule} reads it, and so do the tests that
 * switch every module off.
 */
public enum Module {
    ANDROIDTV("androidtv", true, null),
    CAST("cast", true, null),
    WEBOS("webos", true, null),
    TIZEN("tizen", true, null),
    UPNP("upnp", true, null),
    SONOS("sonos", true, null),
    BLUETOOTH("bluetooth", false, null),
    JELLYFIN("jellyfin", true, null),
    YOUTUBE("youtube", true, null),
    TMDB("tmdb", true, null),
    PINNED("pinned", true, null),
    SPORTS("sports", true, null),
    THESPORTSDB("sports.thesportsdb", true, SPORTS),
    WORKFLOWS("workflows", true, null);

    private final String segment;
    private final boolean enabledByDefault;
    private final Module parent;

    Module(String segment, boolean enabledByDefault, Module parent) {
        this.segment = segment;
        this.enabledByDefault = enabledByDefault;
        this.parent = parent;
    }

    public String segment() {
        return segment;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /** A module that only runs inside another, such as TheSportsDB inside sports. */
    public Optional<Module> parent() {
        return Optional.ofNullable(parent);
    }

    public String property() {
        return "home-control." + segment + ".enabled";
    }
}
```

`ConditionalOnModule.java`:

```java
package dev.andre.homecontrol.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Registers the annotated configuration, component or bean only while {@link #value()} and its parent are on. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnModuleCondition.class)
public @interface ConditionalOnModule {

    Module value();
}
```

`OnModuleCondition.java`:

```java
package dev.andre.homecontrol.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * A module is on when {@code home-control.<segment>.enabled} is {@code true} (in any case), or is unset and the
 * module is on by default, and its parent is on too. Any other value switches it off, as the
 * {@code @ConditionalOnProperty(havingValue = "true")} it replaces did.
 */
class OnModuleCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Module module = metadata.getAnnotations().get(ConditionalOnModule.class).getEnum("value", Module.class);
        for (Module each = module; each != null; each = each.parent().orElse(null)) {
            if (!enabled(context.getEnvironment(), each)) {
                return ConditionOutcome.noMatch("module " + each.segment() + " is off (" + each.property() + ")");
            }
        }
        return ConditionOutcome.match("module " + module.segment() + " is on");
    }

    private static boolean enabled(Environment environment, Module module) {
        String value = environment.getProperty(module.property());
        return value == null ? module.enabledByDefault() : "true".equalsIgnoreCase(value);
    }
}
```

- [ ] **Step 4: Run it**

Run the Step 2 command with `--rerun`. Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/config/Module.java src/main/java/dev/andre/homecontrol/config/ConditionalOnModule.java src/main/java/dev/andre/homecontrol/config/OnModuleCondition.java src/test/java/dev/andre/homecontrol/config/OnModuleConditionTest.java
git commit -m "refactor: add one list of switchable modules and a condition that reads it"
```

### Task 2: `LegacyPropertyNames`, the post-processor for old names

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/config/LegacyPropertyNames.java`
- Modify: `src/main/resources/META-INF/spring.factories`
- Test: `src/test/java/dev/andre/homecontrol/config/LegacyPropertyNamesTest.java`

**Interfaces:**
- Produces:
  - `public final class LegacyPropertyNames implements org.springframework.boot.EnvironmentPostProcessor, Ordered`;
  - `record Rename(String oldName, String newName, boolean seconds)` with factories `Rename.of(old, new)` and `Rename.seconds(old, new)`;
  - `static final List<Rename> RENAMES`, empty here and filled by Tasks 3 and 6;
  - `static void apply(ConfigurableEnvironment environment, List<Rename> renames, Log log)`.

- [ ] **Step 1: The failing test**

```java
package dev.andre.homecontrol.config;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LegacyPropertyNamesTest {

    private static final List<LegacyPropertyNames.Rename> RENAMES = List.of(
            LegacyPropertyNames.Rename.of("shield.data-dir", "home-control.data-dir"),
            LegacyPropertyNames.Rename.of("shield.keystore-password", "home-control.androidtv.keystore-password"),
            LegacyPropertyNames.Rename.seconds("home-control.webos.connect-timeout-seconds", "home-control.webos.connect-timeout"));

    private final StandardEnvironment environment = new StandardEnvironment();
    private final MutablePropertySources sources = environment.getPropertySources();
    private final Log log = mock(Log.class);

    @BeforeEach
    void noRealSystemSources() {
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    }

    private void apply() {
        LegacyPropertyNames.apply(environment, RENAMES, log);
    }

    @Test
    void anOldKeyIsCopiedToItsNewName() {
        sources.addLast(new MapPropertySource("jar", Map.of("shield.data-dir", "/data")));

        apply();

        assertThat(environment.getProperty("home-control.data-dir")).isEqualTo("/data");
        verify(log).warn("Configuration key shield.data-dir is deprecated; use home-control.data-dir");
    }

    @Test
    void aSecondsValueGainsItsUnit() {
        sources.addLast(new MapPropertySource("jar", Map.of("home-control.webos.connect-timeout-seconds", "7")));

        apply();

        assertThat(environment.getProperty("home-control.webos.connect-timeout")).isEqualTo("7s");
    }

    @Test
    void aSecondsPlaceholderGainsItsUnit() {
        sources.addLast(new MapPropertySource("jar",
                Map.of("home-control.webos.connect-timeout-seconds", "${WEBOS_TIMEOUT:9}")));

        apply();

        assertThat(environment.getProperty("home-control.webos.connect-timeout")).isEqualTo("9s");
    }

    @Test
    void anOldEnvironmentVariableBeatsTheShippedDefault() {
        sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("SHIELD_KEYSTORE_PASSWORD", "old-secret")));
        sources.addLast(new MapPropertySource("jar", Map.of("home-control.androidtv.keystore-password", "shield")));

        apply();

        assertThat(environment.getProperty("home-control.androidtv.keystore-password")).isEqualTo("old-secret");
    }

    @Test
    void anOldKeyInAHigherRankedFileBeatsTheShippedDefault() {
        sources.addLast(new MapPropertySource("mounted", Map.of("shield.data-dir", "/srv/home")));
        sources.addLast(new MapPropertySource("jar", Map.of("home-control.data-dir", "./data")));

        apply();

        assertThat(environment.getProperty("home-control.data-dir")).isEqualTo("/srv/home");
    }

    @Test
    void aNewKeyAtTheSameOrAHigherRankWins() {
        sources.addLast(new MapPropertySource("args", Map.of("home-control.data-dir", "/new")));
        sources.addLast(new MapPropertySource("mounted", Map.of("shield.data-dir", "/old",
                "shield.keystore-password", "old", "home-control.androidtv.keystore-password", "new")));

        apply();

        assertThat(environment.getProperty("home-control.data-dir")).isEqualTo("/new");
        assertThat(environment.getProperty("home-control.androidtv.keystore-password")).isEqualTo("new");
        verify(log).warn("Configuration key shield.data-dir is deprecated; use home-control.data-dir"
                + " (ignored: home-control.data-dir is also set)");
    }

    @Test
    void nothingChangesWithoutOldKeys() {
        sources.addLast(new MapPropertySource("jar", Map.of("home-control.data-dir", "./data")));
        int before = sources.size();

        apply();

        assertThat(sources.size()).isEqualTo(before);
        verify(log, never()).warn(anyString());
    }
}
```

- [ ] **Step 2: Run it, and watch it fail**

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.config.LegacyPropertyNamesTest'`. Expected: FAIL at `compileTestJava` with `cannot find symbol: class LegacyPropertyNames`.

- [ ] **Step 3: The post-processor**

```java
package dev.andre.homecontrol.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps old configuration keys working. Each old key's value is copied to its new name, into a property source placed
 * directly below the one the old key came from, so it keeps the old key's rank: an old environment variable still
 * beats the default shipped in {@code application.yaml}, and a new key set at the same or a higher rank wins. Runs
 * right after the configuration files are loaded, and logs one warning per old key in use.
 */
public final class LegacyPropertyNames implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    private static final String SOURCE_PREFIX = "legacyPropertyNames:";

    /** An old key and its new name; an old {@code -seconds} value gains the unit it was implicitly in. */
    public record Rename(String oldName, String newName, boolean seconds) {

        static Rename of(String oldName, String newName) {
            return new Rename(oldName, newName, false);
        }

        static Rename seconds(String oldName, String newName) {
            return new Rename(oldName, newName, true);
        }
    }

    /** Every renamed key; docs/user/configuration.md lists them. */
    static final List<Rename> RENAMES = List.of();

    private final Log log;

    public LegacyPropertyNames(DeferredLogFactory logs) {
        this.log = logs.getLog(LegacyPropertyNames.class);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment, RENAMES, log);
    }

    static void apply(ConfigurableEnvironment environment, List<Rename> renames, Log log) {
        MutablePropertySources sources = environment.getPropertySources();
        Map<String, Map<String, Object>> copies = new LinkedHashMap<>();
        for (Rename rename : renames) {
            PropertySource<?> oldSource = firstContaining(sources, rename.oldName());
            if (oldSource == null) {
                continue;
            }
            String warning = "Configuration key " + rename.oldName() + " is deprecated; use " + rename.newName();
            PropertySource<?> newSource = firstContaining(sources, rename.newName());
            if (newSource != null && sources.precedenceOf(newSource) <= sources.precedenceOf(oldSource)) {
                log.warn(warning + " (ignored: " + rename.newName() + " is also set)");
                continue;
            }
            Object value = oldSource.getProperty(rename.oldName());
            if (value instanceof OriginTrackedValue tracked) {
                value = tracked.getValue();
            }
            copies.computeIfAbsent(oldSource.getName(), name -> new LinkedHashMap<>())
                    .put(rename.newName(), rename.seconds() ? value + "s" : value);
            log.warn(warning);
        }
        copies.forEach((sourceName, values) ->
                sources.addAfter(sourceName, new MapPropertySource(SOURCE_PREFIX + sourceName, values)));
    }

    /** Skips Spring Boot's attached source, which wraps all the others and so contains every key. */
    private static PropertySource<?> firstContaining(MutablePropertySources sources, String name) {
        for (PropertySource<?> source : sources) {
            if (!"configurationProperties".equals(source.getName()) && source.containsProperty(name)) {
                return source;
            }
        }
        return null;
    }
}
```

In `src/main/resources/META-INF/spring.factories`, add:

```
org.springframework.boot.EnvironmentPostProcessor=\
dev.andre.homecontrol.config.LegacyPropertyNames
```

- [ ] **Step 4: Run it**

Run the Step 2 command with `--rerun`. Expected: PASS, 7 tests. Then run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.HomeControlApplicationTest' --rerun`. Expected: PASS. The post-processor loads, and with an empty table it changes nothing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/config/LegacyPropertyNames.java src/main/resources/META-INF/spring.factories src/test/java/dev/andre/homecontrol/config/LegacyPropertyNamesTest.java
git commit -m "refactor: add a post-processor that keeps renamed configuration keys working"
```

### Task 3: The configuration root, the data directory and Android TV's own settings

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/config/HomeControlProperties.java`
- Modify:
  - `storage/DataDirectory.java` and `adapters/androidtv/AndroidTvProperties.java`;
  - `adapters/androidtv/AndroidTvTimings.java` and every other user of the removed methods;
  - `HomeControlConfiguration.java`, `security/SecurityConfiguration.java`;
  - `sources/sports/SportsConfiguration.java`, `sources/pinned/PinnedConfiguration.java`, `sources/youtube/YouTubeConfiguration.java`;
  - `config/LegacyPropertyNames.java` (the first six rows);
  - `src/main/resources/application.yaml` and `src/test/resources/config/application.yaml`;
  - `Dockerfile`, `Dockerfile.dist`, `compose.yaml`;
  - every test that names `shield.*`.
- Test: `src/test/java/dev/andre/homecontrol/storage/DataDirectoryTest.java`, `src/test/java/dev/andre/homecontrol/ApplicationYamlTest.java`, `src/test/java/dev/andre/homecontrol/config/LegacyPropertyNamesTest.java`

**Interfaces:**
- Consumes: `LegacyPropertyNames.Rename` (Task 2).
- Produces:
  - `config.HomeControlProperties(Path dataDir, Discovery discovery)`, with `record Discovery(boolean enabled)`;
  - `DataDirectory.path()`, `DataDirectory.resolve(String name)`, and the constants `KEYSTORE`, `DEVICES`, `SECRETS`, `SECRET_KEY`, `SOURCES`, `SPORTS`, `PINNED` and `YOUTUBE_QUOTA`;
  - `AndroidTvProperties(boolean enabled, String keystorePassword, Duration staleTimeout, Duration reconnectInitialDelay, Duration reconnectMaxDelay)` at `home-control.androidtv`.

- [ ] **Step 1: The failing tests**

In `DataDirectoryTest`, add:

```java
    @Test
    void resolvesItsFilesInsideItself(@TempDir Path directory) {
        DataDirectory data = new DataDirectory(directory);

        assertThat(data.path()).isEqualTo(directory);
        assertThat(data.resolve(DataDirectory.SECRETS)).isEqualTo(directory.resolve("secrets.json"));
        assertThat(List.of(DataDirectory.KEYSTORE, DataDirectory.DEVICES, DataDirectory.SECRETS,
                DataDirectory.SECRET_KEY, DataDirectory.SOURCES, DataDirectory.SPORTS, DataDirectory.PINNED,
                DataDirectory.YOUTUBE_QUOTA)).containsExactly("keystore.p12", "devices.json", "secrets.json",
                "secret.key", "sources.json", "sports.json", "pinned.json", "youtube-quota.json");
    }
```

In `ApplicationYamlTest.theTestOverridesWin`, replace the two `shield.*` assertions with:

```java
            assertThat(environment.getProperty("home-control.data-dir")).isEqualTo("build/test-data/" + fork);
            assertThat(environment.getProperty("home-control.discovery.enabled")).isEqualTo("false");
```

In `LegacyPropertyNamesTest`, add:

```java
    @Test
    void theProductionTableRenamesTheShieldKeys() {
        assertThat(LegacyPropertyNames.RENAMES).extracting(LegacyPropertyNames.Rename::oldName)
                .contains("shield.data-dir", "shield.discovery-enabled", "shield.keystore-password",
                        "shield.stale-timeout-seconds", "shield.reconnect-initial-delay-seconds",
                        "shield.reconnect-max-delay-seconds");
    }
```

- [ ] **Step 2: Run them, and watch them fail**

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.DataDirectoryTest' --tests 'dev.andre.homecontrol.ApplicationYamlTest' --tests 'dev.andre.homecontrol.config.LegacyPropertyNamesTest'`. Expected: FAIL at `compileTestJava` (`cannot find symbol: method path()` and the constants).

- [ ] **Step 3: The root, the directory and Android TV's settings**

`HomeControlProperties.java`:

```java
package dev.andre.homecontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/** {@code home-control.*}: where the application keeps its files, and whether it looks for devices with mDNS. */
@ConfigurationProperties("home-control")
public record HomeControlProperties(@DefaultValue("./data") Path dataDir, @DefaultValue Discovery discovery) {

    /** {@code home-control.discovery.*}; SSDP keeps its own {@code home-control.ssdp.*}. */
    public record Discovery(@DefaultValue("true") boolean enabled) {
    }
}
```

In `DataDirectory`:
- add the eight `public static final String` constants, with the values in the test;
- add `public Path path()` and `public Path resolve(String name) { return path.resolve(name); }`;
- add a class Javadoc: "The one place that knows the application's files under `/data`";
- change the `verifyWritable` message from `"Shield data directory is not writable: "` to `"Data directory is not writable: "`, and the probe prefix `.shield-write-check-` to `.write-check-`.

`AndroidTvProperties` becomes:

```java
package dev.andre.homecontrol.adapters.androidtv;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/** {@code home-control.androidtv.*}: the keystore password and the connection's waits. */
@ConfigurationProperties("home-control.androidtv")
public record AndroidTvProperties(@DefaultValue("true") boolean enabled,
                                  @DefaultValue("shield") String keystorePassword,
                                  @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) Duration staleTimeout,
                                  @DefaultValue("1s") @DurationUnit(ChronoUnit.SECONDS) Duration reconnectInitialDelay,
                                  @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) Duration reconnectMaxDelay) {
}
```

`AndroidTvTimings.from` becomes `new AndroidTvTimings(properties.staleTimeout(), properties.reconnectInitialDelay(), properties.reconnectMaxDelay())`.

Users of the removed `dataDir()`, `discoveryEnabled()`, `keystoreFile()` and `devicesFile()`, with the data directory taken from the `DataDirectory` bean:

| File | Change |
| --- | --- |
| `HomeControlConfiguration` | `dataDirectory(HomeControlProperties properties)` builds `new DataDirectory(properties.dataDir())`. `mdnsBrowser` takes `HomeControlProperties` and uses `properties.discovery().enabled()`. `deviceRegistry`, `certificateStore` and `sourceSettings` take `DataDirectory data` and resolve `DEVICES`, `KEYSTORE` and `SOURCES`. |
| `SecurityConfiguration.secretStore` | Takes `DataDirectory data`, and resolves `SECRET_KEY` and `SECRETS`. |
| `SportsConfiguration.jsonFileSportsStore` | Takes `DataDirectory data`, and resolves `SPORTS`. |
| `PinnedConfiguration.jsonFilePinStore` | Takes `DataDirectory data`, and resolves `PINNED`. |
| `YouTubeConfiguration.youTubeQuotaLedger` | Takes `DataDirectory data`, and resolves `YOUTUBE_QUOTA`. |
| `testsupport/FullAppReset` | `app.getBean(DataDirectory.class).path()`. |
| Tests constructing `AndroidTvProperties` (4 files) | The new five-argument constructor, with `Duration`s. |

If one of these configurations then no longer imports anything from `adapters.androidtv`, a frozen "sources do not depend on adapters" violation is gone. Keep the smaller store.

In `LegacyPropertyNames.RENAMES`, add the first six rows of the spec's table:

```java
    static final List<Rename> RENAMES = List.of(
            Rename.of("shield.data-dir", "home-control.data-dir"),
            Rename.of("shield.discovery-enabled", "home-control.discovery.enabled"),
            Rename.of("shield.keystore-password", "home-control.androidtv.keystore-password"),
            Rename.seconds("shield.stale-timeout-seconds", "home-control.androidtv.stale-timeout"),
            Rename.seconds("shield.reconnect-initial-delay-seconds", "home-control.androidtv.reconnect-initial-delay"),
            Rename.seconds("shield.reconnect-max-delay-seconds", "home-control.androidtv.reconnect-max-delay"));
```

In `src/main/resources/application.yaml`:
- the `shield:` block goes;
- under `home-control:`, add these keys, keeping the old comment on `stale-timeout`:

```yaml
  data-dir: ./data
  discovery:
    enabled: true
  androidtv:
    enabled: true
    keystore-password: shield
    # The device pings when idle; commands postpone pings. Count traffic in both directions.
    stale-timeout: 10s
    reconnect-initial-delay: 1s
    reconnect-max-delay: 60s
```

`SHIELD_KEYSTORE_PASSWORD` still works: the post-processor finds `shield.keystore-password` in the environment.

In `src/test/resources/config/application.yaml`, the `shield:` block becomes `home-control.data-dir: build/test-data/${org.gradle.test.worker:0}` and `home-control.discovery.enabled: false`, in the existing `home-control:` block.

Tests naming `shield.*` move to the new names:
- `registry.add("shield.data-dir", …)` becomes `registry.add("home-control.data-dir", …)` in `FullAppTest`, `ModulesOffTest`, `SportsModuleSwitchTest`, `WorkflowModuleSwitchTest`, the seven own-context end-to-end tests and `E2eApplicationTest`;
- `--shield.data-dir` and `--shield.discovery-enabled` become `--home-control.data-dir` and `--home-control.discovery.enabled` in `BluetoothClassLoadingTest` and `EventStreamShutdownEndToEndTest`.

In `Dockerfile` and `Dockerfile.dist`: `ENV HOME_CONTROL_DATA_DIR=/data`, and the entry point passes `--home-control.data-dir=/data`. In `compose.yaml`: `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD: change-me`.

- [ ] **Step 4: Run them, then everything**

Run the Step 2 command with `--rerun`. Expected: PASS. Then run `scripts/gradle.sh test`. Expected: PASS. Look at the XML for `is deprecated` warnings: expect none, because nothing in the repository uses an old key any more.

- [ ] **Step 5: Commit**

```bash
git add <every file listed above, and src/test/archunit-store if it shrank>
git commit -m "feat: move the data directory and Android TV settings to home-control.*

home-control.data-dir, home-control.discovery.enabled and
home-control.androidtv.* (keystore-password, stale-timeout,
reconnect-initial-delay, reconnect-max-delay) replace shield.*. The
shield.* keys and SHIELD_KEYSTORE_PASSWORD keep working, with a
deprecation warning at startup. DataDirectory is the one place that
knows the files under /data."
```

### Task 4: `@ConditionalOnModule` everywhere, and module lists from `Module`

**Files:**
- Modify:
  - the 32 classes in the spec that carry `@ConditionalOnProperty`: 8 in `adapters/`, 24 in `sources/`;
  - `src/test/java/dev/andre/homecontrol/testsupport/ModulesOffTest.java`;
  - `src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java`.

**Interfaces:**
- Consumes: `Module` and `@ConditionalOnModule` (Task 1).

- [ ] **Step 1: The replacements**

Each `@ConditionalOnProperty(name = "home-control.<m>.enabled", havingValue = "true", matchIfMissing = true)`, and each `prefix = "home-control.<m>", name = "enabled"` form, becomes `@ConditionalOnModule(Module.<M>)`:

| String | Constant |
| --- | --- |
| `jellyfin` | `JELLYFIN` |
| `youtube` | `YOUTUBE` |
| `tmdb` | `TMDB` |
| `pinned` | `PINNED` |
| `sports` | `SPORTS` |
| `workflows` | `WORKFLOWS` |
| `cast` | `CAST` |
| `webos` | `WEBOS` |
| `tizen` | `TIZEN` |
| `upnp` | `UPNP` |
| `sonos` | `SONOS` |

The special cases:
- **Bluetooth** has no `matchIfMissing`, so it is off by default. It becomes `@ConditionalOnModule(Module.BLUETOOTH)`, whose default is off.
- **TheSportsDB** checks two properties, `home-control.sports.enabled` and `home-control.sports.thesportsdb.enabled`. It becomes `@ConditionalOnModule(Module.THESPORTSDB)`, whose parent is `SPORTS`.

Replace the import with `import dev.andre.homecontrol.config.ConditionalOnModule;` and `import dev.andre.homecontrol.config.Module;`.

`ModulesOffTest` drops the `properties = {…}` list from `@SpringBootTest`. Its `@DynamicPropertySource` becomes:

```java
    /** Inherited by every subclass, so they share one cache key: one data directory, and every module off. */
    @DynamicPropertySource
    static void everyModuleOff(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("modules-off");
        registry.add("home-control.data-dir", dataDir::toString);
        for (Module module : Module.values()) {
            registry.add(module.property(), () -> "false");
        }
    }
```

Update its Javadoc: every switchable module is off; Android TV becomes switchable in Task 5, and until then its switch does nothing.

`HomeControlApplicationTest.SWITCHABLE` becomes the set of every module without a parent:

```java
    /** Every module a user can switch off, as its package name; the modules-off tests switch all of them off. */
    private static final Set<String> SWITCHABLE = Arrays.stream(Module.values())
            .filter(module -> module.parent().isEmpty()).map(Module::segment).collect(Collectors.toUnmodifiableSet());
```

`noSwitchableModuleNeedsAnotherModulesBean` drops its hard-coded Bluetooth exception. It checks every module in `SWITCHABLE` that is on by default:

```java
        assertThat(modulesSeen).containsAll(Arrays.stream(Module.values())
                .filter(module -> module.parent().isEmpty() && module.enabledByDefault()).map(Module::segment).toList());
```

- [ ] **Step 2: Check that no string is left**

`git grep -n "ConditionalOnProperty" -- src/main/java` prints nothing.

- [ ] **Step 3: Run the module tests and the context tests**

Run `scripts/gradle.sh test --tests '*ModuleSwitch*' --tests '*ModulesOff*' --tests '*ModuleEnabled*' --tests 'dev.andre.homecontrol.HomeControlApplicationTest' --tests 'dev.andre.homecontrol.ArchitectureTest' --tests 'dev.andre.homecontrol.testsupport.*' --rerun`. Expected: PASS. `noSwitchableModuleNeedsAnotherModulesBean` now includes `androidtv`, and finds no crossing, because Task 3 removed the sources' `AndroidTvProperties` injections.

- [ ] **Step 4: Commit**

```bash
git add <the 32 classes> src/test/java/dev/andre/homecontrol/testsupport/ModulesOffTest.java src/test/java/dev/andre/homecontrol/HomeControlApplicationTest.java
git commit -m "refactor: switch every module with @ConditionalOnModule instead of 32 property strings"
```

### Task 5: Android TV becomes a switchable module

**Files:**
- Create:
  - `src/main/java/dev/andre/homecontrol/core/CodePairing.java`;
  - `src/main/java/dev/andre/homecontrol/core/CodePairingOutcome.java`, moved from `adapters/androidtv/PairingOutcome.java`;
  - `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvConfiguration.java`;
  - `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvModuleSwitchTest.java`.
- Modify:
  - `adapters/androidtv/PairingService.java`, `AndroidTvAdapter.java` and `MdnsDiscovery.java`: drop `@Service` and `@Component`;
  - `HomeControlConfiguration.java`: `certificateStore` moves out;
  - `web/SetupController.java` and `templates/setup.html`;
  - `testsupport/WebSliceTest.java` (mock `CodePairing pairing`), `web/SetupControllerTest.java` and `testsupport/ModulesOffSmokeTest.java`;
  - every other user of `PairingOutcome`;
  - `AGENTS.md`;
  - `src/test/archunit-store`.

**Interfaces:**
- Consumes: `Module.ANDROIDTV` and `@ConditionalOnModule` (Task 1); `DataDirectory.resolve` and `AndroidTvProperties.keystorePassword()` (Task 3).
- Produces:
  - `core.CodePairing`, with `boolean inProgress()`, `void begin(String host, String name) throws IOException` and `CodePairingOutcome submit(String code)`;
  - `core.CodePairingOutcome`, with `Paired()`, `WrongCode()` and `Failed(String reason)`.

- [ ] **Step 1: The failing tests**

`AndroidTvModuleSwitchTest`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.core.CodePairing;
import dev.andre.homecontrol.core.Device;
import dev.andre.homecontrol.core.DeviceKind;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.testsupport.ModulesOffTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code home-control.androidtv.enabled=false}: no adapter, pairing, keystore or setup form. */
class AndroidTvModuleSwitchTest extends ModulesOffTest {

    @Autowired
    DeviceManager devices;

    @Test
    void theModuleLeavesNoBeans() {
        assertThat(context.getBeanNamesForType(AndroidTvAdapter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PairingService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CodePairing.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CertificateStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MdnsDiscovery.class)).isEmpty();
    }

    @Test
    void theSetupPageOffersNoAndroidTvPairingAndItsRoutesAreGone() throws Exception {
        String setup = mockMvc.perform(get("/setup")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(setup).doesNotContain("Add an Android TV by address");
        mockMvc.perform(post("/setup/pair").param("host", "192.0.2.1")).andExpect(status().isNotFound());
        mockMvc.perform(post("/setup/code").param("code", "123456")).andExpect(status().isNotFound());
    }

    @Test
    void aDeviceWithAnAndroidTvEntryStillRenders() throws Exception {
        devices.adopt(new Device("old-shield", "Old Shield", DeviceKind.ANDROID_TV, "127.0.0.1",
                Map.of("androidtv", Map.of()), Instant.now()));
        try {
            mockMvc.perform(get("/").param("device", "old-shield")).andExpect(status().isOk());
            mockMvc.perform(get("/setup")).andExpect(status().isOk());
        } finally {
            devices.forget("old-shield");
        }
    }
}
```

In `ModulesOffSmokeTest`, add Android TV's beans to its "none of these modules' beans" check: `AndroidTvAdapter`, `PairingService` and `CertificateStore`.

- [ ] **Step 2: Run them, and watch them fail**

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.AndroidTvModuleSwitchTest'`. Expected: FAIL at `compileTestJava` with `cannot find symbol: class CodePairing`. With only that interface added, `theModuleLeavesNoBeans` would still fail, because the adapter is still a bean.

- [ ] **Step 3: The module**

`core/CodePairing.java`:

```java
package dev.andre.homecontrol.core;

import java.io.IOException;

/**
 * Pairing by typing a code the device shows (Android TV). The web layer uses it without knowing any protocol; there
 * is no bean when the module is switched off.
 */
public interface CodePairing {

    boolean inProgress();

    /** Starts a pairing attempt; the device shows a code. A new attempt replaces an unfinished one. */
    void begin(String host, String name) throws IOException;

    CodePairingOutcome submit(String code);
}
```

`core/CodePairingOutcome.java` is `PairingOutcome` moved with `git mv`, renamed, and put in package `dev.andre.homecontrol.core`. Its Javadoc now says it is what `CodePairing.submit` reports. `PairingService implements CodePairing`, and its `submit` returns `CodePairingOutcome`. Update every `PairingOutcome` user found with `git grep -n PairingOutcome`, tests included.

`AndroidTvConfiguration.java`:

```java
package dev.andre.homecontrol.adapters.androidtv;

import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;
import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.MdnsBrowser;
import dev.andre.homecontrol.storage.DataDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Android TV Remote v2, on unless {@code home-control.androidtv.enabled=false}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.ANDROIDTV)
public class AndroidTvConfiguration {

    @Bean
    public CertificateStore certificateStore(DataDirectory data, AndroidTvProperties properties) {
        return new CertificateStore(data.resolve(DataDirectory.KEYSTORE), properties.keystorePassword().toCharArray());
    }

    @Bean
    public MdnsDiscovery mdnsDiscovery(MdnsBrowser browser) {
        return new MdnsDiscovery(browser);
    }

    @Bean
    public AndroidTvAdapter androidTvAdapter(CertificateStore certificates, AndroidTvProperties properties,
                                             MdnsDiscovery discovery) {
        return new AndroidTvAdapter(certificates, properties, discovery);
    }

    @Bean
    public PairingService pairingService(CertificateStore certificates, DeviceManager devices, DataDirectory data) {
        return new PairingService(certificates, devices, data);
    }
}
```

`HomeControlConfiguration.certificateStore` goes. `MdnsDiscovery`'s `@Autowired` on its public constructor goes with `@Service`.

`SetupController`:
- the constructor takes `Optional<CodePairing> codePairing` in place of `PairingService pairing`, and `DeepLinkTestProperties deepLinkTest` in place of the `@Value` `Duration`, using `deepLinkTest.timeout()`;
- `setup` passes `codePairing.map(CodePairing::inProgress).orElse(false)`;
- `pair` and `code` start with `CodePairing pairing = codePairing.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Android TV is switched off"));`;
- `populateSetupModel` adds `model.addAttribute("codePairing", codePairing.isPresent());`.

In `setup.html`, the `<details class="manual-device">` block "Add an Android TV by address" gets `th:if="${codePairing}"`.

In `WebSliceTest`, the field `@MockitoBean protected PairingService pairing;` becomes `@MockitoBean protected CodePairing pairing;`. Its import changes too. `SetupControllerTest` and every other web-slice test that stubs `pairing` switch to `CodePairingOutcome`.

In `AGENTS.md`, the product rule becomes:

```markdown
- **Device adapters and content sources are modules that can be switched off**, with
  `home-control.<module>.enabled` (`config.Module` lists them). Bluetooth is off by default, the rest are on.
```

- [ ] **Step 4: Run them, then the neighbours**

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.*' --tests 'dev.andre.homecontrol.web.*' --tests '*ModulesOff*' --tests 'dev.andre.homecontrol.HomeControlApplicationTest' --tests 'dev.andre.homecontrol.ArchitectureTest' --tests 'dev.andre.homecontrol.testsupport.*' --rerun`. Expected: PASS.

`git diff --stat -- src/test/archunit-store` shows lines removed only: the six `SetupController` → `PairingService` lines in `ba1e77bf-…`, and their copies in `34477544-…`.

- [ ] **Step 5: Commit**

```bash
git add <files above> src/test/archunit-store
git commit -m "feat: let Android TV be switched off like every other module

home-control.androidtv.enabled=false removes the Android TV adapter,
its pairing and keystore, and the setup page's Android TV form; it is
on by default. The setup page now pairs through core.CodePairing, so
the web layer no longer depends on the adapter."
```

### Task 6: Every time setting becomes a `Duration`

**Files:**
- Modify:
  - the records `SsdpProperties`, `WebOsProperties`, `TizenProperties`, `UpnpProperties`, `SonosProperties`, `BluetoothProperties`, `CastProperties`, `JellyfinProperties`, `TmdbProperties`, `SportsProperties` (`Calendar`, `TheSportsDb`) and `YouTubeProperties`, and their users;
  - `JellyfinConfiguration` (the `@Value` reads);
  - `LegacyPropertyNames.RENAMES` (the other 52 rows);
  - both `application.yaml` files;
  - every test that constructs these records or names a `-seconds` key.
- Create: `src/test/java/dev/andre/homecontrol/config/LegacyPropertyNamesBindingTest.java`
- Test: `CastPropertiesTest`, `TmdbPropertiesTest`

**Interfaces:**
- Consumes: `LegacyPropertyNames.Rename` and `RENAMES` (Tasks 2 and 3).
- Produces: each record's `Duration` accessor, named as in the spec's table, for example `WebOsProperties.connectTimeout()`, `BluetoothProperties.scanDuration()` and `JellyfinProperties.startupTimeout()`.

- [ ] **Step 1: The failing tests**

`LegacyPropertyNamesBindingTest`:
- It sets every old name in `RENAMES` to a distinctive value, applies the post-processor, and binds every properties record in `dev.andre.homecontrol`.
- For each rename, it finds the component the new name reaches and checks it holds the value:
  - a seconds key set to `7` must give `Duration.ofSeconds(7)`;
  - `shield.data-dir` set to `/srv/legacy` must give `Path.of("/srv/legacy")`;
  - `shield.discovery-enabled` set to `false` must give `false`;
  - `shield.keystore-password` set to `legacy-password` must give it back.

```java
package dev.andre.homecontrol.config;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Every old key in the table reaches a component of a properties record under its new name. */
class LegacyPropertyNamesBindingTest {

    private static final Map<String, Object> OLD_VALUES = Map.of(
            "shield.data-dir", "/srv/legacy", "shield.discovery-enabled", "false",
            "shield.keystore-password", "legacy-password");

    @Test
    void everyOldKeyBindsUnderItsNewName() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> old = new HashMap<>();
        for (LegacyPropertyNames.Rename rename : LegacyPropertyNames.RENAMES) {
            old.put(rename.oldName(), rename.seconds() ? "7" : OLD_VALUES.get(rename.oldName()));
        }
        environment.getPropertySources().addFirst(new MapPropertySource("old", old));
        LegacyPropertyNames.apply(environment, LegacyPropertyNames.RENAMES, mock(Log.class));
        Binder binder = Binder.get(environment);

        List<Class<?>> records = propertiesRecords();
        for (LegacyPropertyNames.Rename rename : LegacyPropertyNames.RENAMES) {
            Object expected = rename.seconds() ? Duration.ofSeconds(7) : expected(rename.oldName());
            assertThat(bound(binder, records, rename.newName()))
                    .as(rename.oldName() + " -> " + rename.newName()).isEqualTo(expected);
        }
    }

    private static Object expected(String oldName) {
        return switch (oldName) {
            case "shield.data-dir" -> Path.of("/srv/legacy");
            case "shield.discovery-enabled" -> false;
            default -> OLD_VALUES.get(oldName);
        };
    }

    private static List<Class<?>> propertiesRecords() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        List<Class<?>> records = new java.util.ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("dev.andre.homecontrol")) {
            records.add(Class.forName(candidate.getBeanClassName()));
        }
        return records;
    }

    /** Binds the record with the longest prefix of {@code name}, then walks the rest of the name through components. */
    private static Object bound(Binder binder, List<Class<?>> records, String name) throws Exception {
        Class<?> owner = records.stream()
                .filter(type -> name.startsWith(prefix(type) + "."))
                .max(Comparator.comparingInt(type -> prefix(type).length()))
                .orElseThrow(() -> new AssertionError("No properties record binds " + name));
        Object value = binder.bindOrCreate(prefix(owner), owner);
        for (String segment : name.substring(prefix(owner).length() + 1).split("\\.")) {
            Optional<RecordComponent> component = java.util.Arrays.stream(value.getClass().getRecordComponents())
                    .filter(each -> each.getName().equals(camel(segment))).findFirst();
            assertThat(component).as(name + " has no component " + segment).isPresent();
            value = component.get().getAccessor().invoke(value);
        }
        return value;
    }

    private static String prefix(Class<?> type) {
        ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
        return annotation.value().isEmpty() ? annotation.prefix() : annotation.value();
    }

    private static String camel(String kebab) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
```

In `CastPropertiesTest` and `TmdbPropertiesTest`, add `aBareNumberMeansSeconds`. It binds the record with `ApplicationContextRunner` or the test's existing binding helper, sets a new key to a bare number (`home-control.cast.stale-timeout=15`, `home-control.tmdb.request-timeout=12`), and asserts `Duration.ofSeconds(15)` and `Duration.ofSeconds(12)`.

- [ ] **Step 2: Run them, and watch them fail**

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.config.*' --tests 'dev.andre.homecontrol.adapters.cast.CastPropertiesTest' --tests 'dev.andre.homecontrol.sources.tmdb.TmdbPropertiesTest'`. Expected: FAIL. `everyOldKeyBindsUnderItsNewName` fails on its first seconds key, because `RENAMES` lacks it, or because no component has the new name. The bare-number tests fail at compile, because `staleTimeout()` and `requestTimeout()` don't exist yet.

- [ ] **Step 3: The records, their users and the table**

This is the conversion for each seconds component, shown here on `SsdpProperties`:

```java
                             @DefaultValue("60") @Positive int searchIntervalSeconds,
```

becomes

```java
                             @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                             Duration searchInterval,
```

- `@Positive` becomes `@DurationMin(nanos = 1)`, and `@Min(0)` or `@PositiveOrZero` become `@DurationMin(nanos = 0)`. A component without a constraint gets none.
- `@DurationMin` is `org.hibernate.validator.constraints.time.DurationMin`.
- Keep each record's order of components, and rename it as in the spec's table:
  - `scanSeconds` → `scanDuration`;
  - `hostCheckCacheSeconds` → `hostCheckCacheTtl`;
  - every other `xSeconds` → `x`.
- `JellyfinProperties` gains `@DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1) Duration startupTimeout`. `JellyfinConfiguration`'s two `@Value("${home-control.jellyfin.startup-timeout-seconds:30}") int` parameters are replaced by `properties.startupTimeout()`.

Each user drops its `Duration.ofSeconds(properties.xSeconds())` for `properties.x()`. A user that needs a number, such as an HTTP client's connect timeout, calls `properties.x()` and passes the `Duration`, or `toMillis()` where an API takes a number. Find the users by compiling: `scripts/gradle.sh compileJava`.

The components, by record:

| Record | Components becoming `Duration`s |
| --- | --- |
| `SsdpProperties` | `searchInterval` |
| `WebOsProperties` | `connectTimeout`, `requestTimeout`, `pairingTimeout`, `reconnectInitialDelay`, `reconnectMaxDelay`, `wakeGrace`, `livenessInterval` |
| `TizenProperties` | `connectTimeout`, `requestTimeout`, `pairingTimeout`, `pollInterval`, `wakeGrace` |
| `UpnpProperties` | `pollInterval`, `idlePollInterval`, `commandTimeout`, `connectTimeout`, `reconnectInitialDelay`, `reconnectMaxDelay` |
| `SonosProperties` | `pollInterval`, `idlePollInterval`, `topologyInterval`, `commandTimeout`, `connectTimeout`, `reconnectInitialDelay`, `reconnectMaxDelay` |
| `BluetoothProperties` | `scanDuration`, `bluezTimeout`, `pollInterval`, `playingPollInterval`, `playerStartTimeout`, `loadTimeout`, `commandTimeout`, `hostCheckCacheTtl` |
| `CastProperties` | `heartbeatInterval`, `staleTimeout`, `reconnectInitialDelay`, `reconnectMaxDelay`, `commandTimeout`, `loadTimeout`, `mediaStatusInterval` |
| `JellyfinProperties` | `connectTimeout`, `requestTimeout`, `startupTimeout` (new) |
| `TmdbProperties` | `connectTimeout`, `requestTimeout` |
| `SportsProperties.Calendar`, `SportsProperties.TheSportsDb` | `connectTimeout`, `requestTimeout` in each |
| `YouTubeProperties` | `connectTimeout`, `requestTimeout` |

`BluetoothProperties.defaults()` and its `with…` copies pass `Duration`s.

`LegacyPropertyNames.RENAMES` gains every seconds row of the spec's table after the six `shield.*` rows, as `Rename.seconds(old, new)`, in the table's order.

In `src/main/resources/application.yaml`, each seconds key takes its new name, and its value gets an `s`. For example `connect-timeout-seconds: 3` becomes `connect-timeout: 3s`. Comments stay with their keys. Do the same in `src/test/resources/config/application.yaml`.

Tests move to the new names:
- every test that constructs one of these records passes `Duration.ofSeconds(n)` where it passed `n`. Fix them by compiling: `scripts/gradle.sh compileTestJava compileE2eJava`;
- `FullAppTest`'s Cast and UPnP registrations and the own-context tests' `-seconds` registrations take the new names, with `s` values;
- so do `UpnpModuleSwitchTest`, `BluetoothModuleSwitchTest`, `SonosModuleSwitchTest`, `CastPropertiesTest` and `TmdbPropertiesTest`.

- [ ] **Step 4: Run them, then everything**

Run the Step 2 command with `--rerun`. Expected: PASS. Then run `scripts/gradle.sh test` and `scripts/gradle.sh compileE2eJava`. Expected: PASS. Last, run `git grep -n -- '-seconds' -- src/main/resources src/test/resources src/main/java`. It must print nothing except `LegacyPropertyNames`' table.

- [ ] **Step 5: Commit**

```bash
git add <records, their users, LegacyPropertyNames, both application.yaml files, tests>
git commit -m "feat: make every time setting a Duration

Each home-control.*-seconds key loses its suffix and takes a Duration
(connect-timeout: 3s); a bare number still means seconds.
bluetooth.scan-seconds becomes scan-duration and host-check-cache-seconds
becomes host-check-cache-ttl. The old keys keep working, with a
deprecation warning at startup."
```

### Task 7: Clean-up, the startup check with old names only, and the docs

**Files:**
- Create: `src/test/java/dev/andre/homecontrol/config/LegacyConfigurationStartupTest.java`
- Modify:
  - the 15 classes with `@EnableConfigurationProperties`;
  - `settings.gradle.kts`;
  - `docs/user/configuration.md` and `README.md`;
  - `docs/dev/architecture.md`, for the frozen-violations count and a note on `config`.

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: The startup test with old names only**

```java
package dev.andre.homecontrol.config;

import dev.andre.homecontrol.HomeControlApplication;
import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.adapters.webos.WebOsProperties;
import dev.andre.homecontrol.storage.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** An install configured entirely with the old names still starts, and every value reaches its new name. */
class LegacyConfigurationStartupTest {

    @Test
    void anInstallWithOnlyOldNamesStarts(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(HomeControlApplication.class)
                .run("--server.port=0", "--shield.data-dir=" + dataDir, "--shield.keystore-password=old-secret",
                        "--shield.discovery-enabled=false", "--home-control.webos.connect-timeout-seconds=7")) {
            assertThat(app.getBean(DataDirectory.class).path()).isEqualTo(dataDir);
            assertThat(app.getBean(AndroidTvProperties.class).keystorePassword()).isEqualTo("old-secret");
            assertThat(app.getBean(HomeControlProperties.class).discovery().enabled()).isFalse();
            assertThat(app.getBean(WebOsProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(7));
        }
    }
}
```

Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.config.LegacyConfigurationStartupTest'`. Expected: PASS. It tests Tasks 2–6 together.

To show it can fail, temporarily empty `RENAMES` and run it again. Expected: FAIL, because `DataDirectory.path()` is the test configuration's `build/test-data/…`. Restore the table.

- [ ] **Step 2: Clean-up**

- Delete the 15 `@EnableConfigurationProperties(…)` annotations and their imports. `@ConfigurationPropertiesScan` binds every record.
- `settings.gradle.kts`: `rootProject.name = "home-control"`. Check that nothing names `shield-remote.jar`: `git grep -n "shield-remote" -- build.gradle.kts .github scripts Dockerfile Dockerfile.dist` shows only CasaOS and service names, which stay.

Run `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 3: The docs**

In `docs/user/configuration.md`:
- The `shield.*` rows become `home-control.data-dir`, `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD`, `home-control.discovery.enabled`, `home-control.androidtv.stale-timeout` and `home-control.androidtv.reconnect-max-delay`, with the defaults `10s` and `60s`.
- Every other `-seconds` row takes its new name and a unit (`home-control.webos.pairing-timeout` | `60s`).
- A new row: `home-control.androidtv.enabled` | `true` | "Turn Android TV off entirely; the setup page drops its pairing form".
- A section at the end:

```markdown
## Renamed settings

Older versions used different names. They keep working, and the log says which new name to use:

| Old name | New name |
| --- | --- |
| `shield.data-dir` | `home-control.data-dir` |
| `shield.discovery-enabled` | `home-control.discovery.enabled` |
| `shield.keystore-password`, `SHIELD_KEYSTORE_PASSWORD` | `home-control.androidtv.keystore-password`, `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD` |
| `shield.stale-timeout-seconds`, `shield.reconnect-*-seconds` | `home-control.androidtv.stale-timeout`, `home-control.androidtv.reconnect-*` |
| any `home-control.…-seconds` key | the same key without `-seconds`, as a duration (`3s`, `2m`); a bare number still means seconds |
| `home-control.bluetooth.scan-seconds` | `home-control.bluetooth.scan-duration` |
| `home-control.bluetooth.host-check-cache-seconds` | `home-control.bluetooth.host-check-cache-ttl` |
```

In `README.md`, where it names `SHIELD_KEYSTORE_PASSWORD`, name `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD`, and say that the old name still works.

In `docs/dev/architecture.md`:
- the "Frozen ArchUnit violations" row gets the new count: the lines across `src/test/archunit-store/*`, excluding `stored.rules`;
- the package overview, if it lists packages, gains `config`: "the configuration root, the module list and old-key mapping".

- [ ] **Step 4: Build and check**

```bash
scripts/gradle.sh build
scripts/gradle.sh compileE2eJava
git grep -n "shield\." -- src/main src/test/resources Dockerfile Dockerfile.dist compose.yaml
```

Expected:
- the build is green;
- the grep shows only `LegacyPropertyNames`' table and `CertificateStore`'s client name (`dev.andre.shield`), which stays;
- the test count is higher than 2,771.

- [ ] **Step 5: Commit**

```bash
git add <the 15 classes> settings.gradle.kts docs/user/configuration.md README.md docs/dev/architecture.md src/test/java/dev/andre/homecontrol/config/LegacyConfigurationStartupTest.java
git commit -m "docs: document the renamed settings, and check that an install with only old names starts"
```
