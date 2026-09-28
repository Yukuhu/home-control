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
    static final List<Rename> RENAMES = List.of(
            Rename.of("shield.data-dir", "home-control.data-dir"),
            Rename.of("shield.discovery-enabled", "home-control.discovery.enabled"),
            Rename.of("shield.keystore-password", "home-control.androidtv.keystore-password"),
            Rename.seconds("shield.stale-timeout-seconds", "home-control.androidtv.stale-timeout"),
            Rename.seconds("shield.reconnect-initial-delay-seconds", "home-control.androidtv.reconnect-initial-delay"),
            Rename.seconds("shield.reconnect-max-delay-seconds", "home-control.androidtv.reconnect-max-delay"));

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
