package dev.andre.homecontrol.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
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
    static final List<Rename> RENAMES = List.of(
            Rename.of("shield.data-dir", "home-control.data-dir"),
            Rename.of("shield.discovery-enabled", "home-control.discovery.enabled"),
            Rename.of("shield.keystore-password", "home-control.androidtv.keystore-password"),
            Rename.seconds("shield.stale-timeout-seconds", "home-control.androidtv.stale-timeout"),
            Rename.seconds("shield.reconnect-initial-delay-seconds", "home-control.androidtv.reconnect-initial-delay"),
            Rename.seconds("shield.reconnect-max-delay-seconds", "home-control.androidtv.reconnect-max-delay"),
            Rename.seconds("home-control.ssdp.search-interval-seconds", "home-control.ssdp.search-interval"),
            Rename.seconds("home-control.webos.connect-timeout-seconds", "home-control.webos.connect-timeout"),
            Rename.seconds("home-control.webos.request-timeout-seconds", "home-control.webos.request-timeout"),
            Rename.seconds("home-control.webos.pairing-timeout-seconds", "home-control.webos.pairing-timeout"),
            Rename.seconds("home-control.webos.reconnect-initial-delay-seconds",
                    "home-control.webos.reconnect-initial-delay"),
            Rename.seconds("home-control.webos.reconnect-max-delay-seconds", "home-control.webos.reconnect-max-delay"),
            Rename.seconds("home-control.webos.wake-grace-seconds", "home-control.webos.wake-grace"),
            Rename.seconds("home-control.webos.liveness-interval-seconds", "home-control.webos.liveness-interval"),
            Rename.seconds("home-control.tizen.connect-timeout-seconds", "home-control.tizen.connect-timeout"),
            Rename.seconds("home-control.tizen.request-timeout-seconds", "home-control.tizen.request-timeout"),
            Rename.seconds("home-control.tizen.pairing-timeout-seconds", "home-control.tizen.pairing-timeout"),
            Rename.seconds("home-control.tizen.poll-interval-seconds", "home-control.tizen.poll-interval"),
            Rename.seconds("home-control.tizen.wake-grace-seconds", "home-control.tizen.wake-grace"),
            Rename.seconds("home-control.upnp.poll-interval-seconds", "home-control.upnp.poll-interval"),
            Rename.seconds("home-control.upnp.idle-poll-interval-seconds", "home-control.upnp.idle-poll-interval"),
            Rename.seconds("home-control.upnp.command-timeout-seconds", "home-control.upnp.command-timeout"),
            Rename.seconds("home-control.upnp.connect-timeout-seconds", "home-control.upnp.connect-timeout"),
            Rename.seconds("home-control.upnp.reconnect-initial-delay-seconds",
                    "home-control.upnp.reconnect-initial-delay"),
            Rename.seconds("home-control.upnp.reconnect-max-delay-seconds", "home-control.upnp.reconnect-max-delay"),
            Rename.seconds("home-control.sonos.poll-interval-seconds", "home-control.sonos.poll-interval"),
            Rename.seconds("home-control.sonos.idle-poll-interval-seconds", "home-control.sonos.idle-poll-interval"),
            Rename.seconds("home-control.sonos.topology-interval-seconds", "home-control.sonos.topology-interval"),
            Rename.seconds("home-control.sonos.command-timeout-seconds", "home-control.sonos.command-timeout"),
            Rename.seconds("home-control.sonos.connect-timeout-seconds", "home-control.sonos.connect-timeout"),
            Rename.seconds("home-control.sonos.reconnect-initial-delay-seconds",
                    "home-control.sonos.reconnect-initial-delay"),
            Rename.seconds("home-control.sonos.reconnect-max-delay-seconds", "home-control.sonos.reconnect-max-delay"),
            Rename.seconds("home-control.bluetooth.scan-seconds", "home-control.bluetooth.scan-duration"),
            Rename.seconds("home-control.bluetooth.bluez-timeout-seconds", "home-control.bluetooth.bluez-timeout"),
            Rename.seconds("home-control.bluetooth.poll-interval-seconds", "home-control.bluetooth.poll-interval"),
            Rename.seconds("home-control.bluetooth.playing-poll-interval-seconds",
                    "home-control.bluetooth.playing-poll-interval"),
            Rename.seconds("home-control.bluetooth.player-start-timeout-seconds",
                    "home-control.bluetooth.player-start-timeout"),
            Rename.seconds("home-control.bluetooth.load-timeout-seconds", "home-control.bluetooth.load-timeout"),
            Rename.seconds("home-control.bluetooth.command-timeout-seconds", "home-control.bluetooth.command-timeout"),
            Rename.seconds("home-control.bluetooth.host-check-cache-seconds",
                    "home-control.bluetooth.host-check-cache-ttl"),
            Rename.seconds("home-control.cast.heartbeat-interval-seconds", "home-control.cast.heartbeat-interval"),
            Rename.seconds("home-control.cast.stale-timeout-seconds", "home-control.cast.stale-timeout"),
            Rename.seconds("home-control.cast.reconnect-initial-delay-seconds",
                    "home-control.cast.reconnect-initial-delay"),
            Rename.seconds("home-control.cast.reconnect-max-delay-seconds", "home-control.cast.reconnect-max-delay"),
            Rename.seconds("home-control.cast.command-timeout-seconds", "home-control.cast.command-timeout"),
            Rename.seconds("home-control.cast.load-timeout-seconds", "home-control.cast.load-timeout"),
            Rename.seconds("home-control.cast.media-status-interval-seconds",
                    "home-control.cast.media-status-interval"),
            Rename.seconds("home-control.jellyfin.connect-timeout-seconds", "home-control.jellyfin.connect-timeout"),
            Rename.seconds("home-control.jellyfin.request-timeout-seconds", "home-control.jellyfin.request-timeout"),
            Rename.seconds("home-control.jellyfin.startup-timeout-seconds", "home-control.jellyfin.startup-timeout"),
            Rename.seconds("home-control.tmdb.connect-timeout-seconds", "home-control.tmdb.connect-timeout"),
            Rename.seconds("home-control.tmdb.request-timeout-seconds", "home-control.tmdb.request-timeout"),
            Rename.seconds("home-control.sports.calendar.connect-timeout-seconds",
                    "home-control.sports.calendar.connect-timeout"),
            Rename.seconds("home-control.sports.calendar.request-timeout-seconds",
                    "home-control.sports.calendar.request-timeout"),
            Rename.seconds("home-control.sports.thesportsdb.connect-timeout-seconds",
                    "home-control.sports.thesportsdb.connect-timeout"),
            Rename.seconds("home-control.sports.thesportsdb.request-timeout-seconds",
                    "home-control.sports.thesportsdb.request-timeout"),
            Rename.seconds("home-control.youtube.connect-timeout-seconds", "home-control.youtube.connect-timeout"),
            Rename.seconds("home-control.youtube.request-timeout-seconds", "home-control.youtube.request-timeout"));

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
            Found old = firstContaining(sources, rename.oldName());
            if (old == null) {
                continue;
            }
            String warning = "Configuration key " + rename.oldName() + " is deprecated; use " + rename.newName();
            Found found = firstContaining(sources, rename.newName());
            if (found != null && sources.precedenceOf(found.source()) <= sources.precedenceOf(old.source())) {
                log.warn(warning + " (ignored: " + rename.newName() + " is also set)");
            } else {
                Object value = old.value();
                // Keeps where the old key was set, so a bad value fails startup naming that file and line.
                copies.computeIfAbsent(old.source().getName(), name -> new LinkedHashMap<>())
                        .put(rename.newName(), OriginTrackedValue.of(
                                rename.seconds() ? String.valueOf(value).strip() + "s" : value, old.origin()));
                log.warn(warning);
            }
        }
        copies.forEach((sourceName, values) ->
                sources.addAfter(sourceName, new OriginTrackedMapPropertySource(SOURCE_PREFIX + sourceName, values)));
    }

    private record Found(PropertySource<?> source, Object value, Origin origin) {
    }

    /**
     * The first source that sets {@code name} in any spelling the binder accepts ({@code keystorePassword},
     * {@code SHIELD_KEYSTOREPASSWORD}, …). Skips Spring Boot's attached source, which wraps all the others.
     */
    private static Found firstContaining(MutablePropertySources sources, String name) {
        ConfigurationPropertyName key = ConfigurationPropertyName.of(name);
        for (PropertySource<?> source : sources) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            ConfigurationPropertySource adapted = ConfigurationPropertySource.from(source);
            ConfigurationProperty property = adapted == null ? null : adapted.getConfigurationProperty(key);
            if (property != null) {
                return new Found(source, property.getValue(), property.getOrigin());
            }
        }
        return null;
    }
}
