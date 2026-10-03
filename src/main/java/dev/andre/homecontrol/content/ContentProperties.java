package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.SourcePreferences;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("home-control.content")
public record ContentProperties(@DefaultValue Rails rails, @DefaultValue Search search,
                                @DefaultValue("de-DE") String locale, @DefaultValue("DE") String region) {

    /**
     * Checked by the rules stored preferences are checked by, so that a value the rails could not use fails startup
     * and names its setting, instead of failing the dashboard on every view.
     */
    public ContentProperties {
        check("home-control.content.locale (HOME_CONTROL_LOCALE)", locale,
                () -> SourcePreferences.defaults(locale, "DE"));
        check("home-control.content.region (HOME_CONTROL_REGION)", region,
                () -> SourcePreferences.defaults("de-DE", region));
    }

    private static void check(String setting, String value, Runnable rules) {
        try {
            rules.run();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(setting + " is \"" + value + "\": " + e.getMessage());
        }
    }

    public record Rails(@DefaultValue("true") boolean schedulerEnabled,
                        @DefaultValue("15s") Duration tick,
                        @DefaultValue("1m") Duration retryAfterFailure,
                        @DefaultValue("4") int maxConcurrentFetches,
                        Map<String, Duration> refreshIntervals) {
        public Rails {
            refreshIntervals = refreshIntervals == null ? Map.of() : Map.copyOf(refreshIntervals);
            if (maxConcurrentFetches < 1) {
                throw new IllegalArgumentException("home-control.content.rails.max-concurrent-fetches must be at least 1");
            }
        }
    }

    /** How long the unified search box waits for every source before it reports the slow ones as failed. */
    public record Search(@DefaultValue("8s") Duration timeout) {
    }
}
