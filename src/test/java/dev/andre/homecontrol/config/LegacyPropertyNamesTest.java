package dev.andre.homecontrol.config;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;

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
    void aCopiedValueKeepsWhereTheOldKeyWasSet() {
        Origin where = new TextResourceOrigin(new ClassPathResource("application.yaml"),
                new TextResourceOrigin.Location(4, 2));
        sources.addLast(new OriginTrackedMapPropertySource("jar",
                Map.of("home-control.webos.connect-timeout-seconds", OriginTrackedValue.of("abc", where))));

        apply();

        ConfigurationPropertyName copied = ConfigurationPropertyName.of("home-control.webos.connect-timeout");
        ConfigurationProperty property = StreamSupport
                .stream(ConfigurationPropertySources.get(environment).spliterator(), false)
                .map(source -> source.getConfigurationProperty(copied))
                .filter(Objects::nonNull)
                .findFirst().orElseThrow();
        // The binder wraps it with the copy's property source; what it says is the old key's file and line.
        assertThat(property.getOrigin()).hasToString(where.toString());
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

    @Test
    void theProductionTableRenamesTheShieldKeys() {
        assertThat(LegacyPropertyNames.RENAMES).extracting(LegacyPropertyNames.Rename::oldName)
                .contains("shield.data-dir", "shield.discovery-enabled", "shield.keystore-password",
                        "shield.stale-timeout-seconds", "shield.reconnect-initial-delay-seconds",
                        "shield.reconnect-max-delay-seconds");
    }

    @Test
    void anOldKeyInAnotherSpellingSpringAcceptsIsFound() {
        sources.addLast(new MapPropertySource("mounted", Map.of("shield.keystorePassword", "camel")));
        sources.addLast(new MapPropertySource("jar", Map.of("home-control.androidtv.keystore-password", "shield")));

        apply();

        assertThat(environment.getProperty("home-control.androidtv.keystore-password")).isEqualTo("camel");
    }

    @Test
    void anOldEnvironmentVariableInSpringsCanonicalFormIsFound() {
        sources.addLast(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("SHIELD_KEYSTOREPASSWORD", "canonical")));
        sources.addLast(new MapPropertySource("jar", Map.of("home-control.androidtv.keystore-password", "shield")));

        apply();

        assertThat(environment.getProperty("home-control.androidtv.keystore-password")).isEqualTo("canonical");
    }

    @Test
    void spacesAroundAnOldSecondsValueDoNotCount() {
        sources.addLast(new MapPropertySource("mounted", Map.of("home-control.webos.connect-timeout-seconds", " 20 ")));

        apply();

        assertThat(environment.getProperty("home-control.webos.connect-timeout")).isEqualTo("20s");
    }
}
