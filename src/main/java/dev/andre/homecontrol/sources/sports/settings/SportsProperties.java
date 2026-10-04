package dev.andre.homecontrol.sources.sports.settings;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@ConfigurationProperties("home-control.sports")
@Validated
public record SportsProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("") String timeZone,
                               @DefaultValue("30") @Positive int railSize,
                               @DefaultValue("10") @Positive int maxCalendars,
                               @DefaultValue("10") @Positive int maxCompetitions,
                               @DefaultValue("120m") @DurationUnit(ChronoUnit.MINUTES) Duration defaultEventDuration,
                               @DefaultValue Calendar calendar,
                               @DefaultValue TheSportsDb theSportsDb) {

    public SportsProperties {
        atLeastAMinute("home-control.sports.default-event-duration", defaultEventDuration);
    }

    /**
     * A sports duration under a minute fails startup and names its setting: a bare number read in the wrong unit (90
     * as milliseconds), or a negative one, would otherwise fail every sports rail.
     */
    static void atLeastAMinute(String setting, Duration value) {
        if (value != null && value.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException(setting + " (" + value.toMillis() + " ms) must be at least a minute;"
                    + " give it a unit, for example 90m");
        }
    }

    @Validated
    public record Calendar(@DefaultValue("6h") @DurationUnit(ChronoUnit.HOURS) Duration refresh,
                           @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                           Duration connectTimeout,
                           @DefaultValue("15s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                           Duration requestTimeout,
                           @DefaultValue("5242880") @Positive int maxBytes,
                           @DefaultValue("3") @PositiveOrZero int maxRedirects,
                           @DefaultValue("false") boolean allowLoopback) {

        public Calendar {
            atLeastAMinute("home-control.sports.calendar.refresh", refresh);
        }
    }

    @Validated
    public record TheSportsDb(@DefaultValue("true") boolean enabled,
                              @DefaultValue("https://www.thesportsdb.com/api/v1/json") URI apiBaseUrl,
                              @DefaultValue("123") String freeKey,
                              @DefaultValue("24h") @DurationUnit(ChronoUnit.HOURS) Duration fixturesTtl,
                              @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration connectTimeout,
                              @DefaultValue("15s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                              Duration requestTimeout,
                              Map<String, Duration> sportDurations,
                              @DefaultValue("false") boolean allowLoopback) {

        public static final Map<String, Duration> DEFAULT_DURATIONS = Map.of(
                "soccer", Duration.ofMinutes(120), "basketball", Duration.ofMinutes(150),
                "americanfootball", Duration.ofMinutes(210), "icehockey", Duration.ofMinutes(165),
                "handball", Duration.ofMinutes(105), "rugby", Duration.ofMinutes(120),
                "tennis", Duration.ofMinutes(180), "motorsport", Duration.ofMinutes(150),
                "darts", Duration.ofMinutes(240));

        public TheSportsDb {
            atLeastAMinute("home-control.sports.thesportsdb.fixtures-ttl", fixturesTtl);
            Map<String, Duration> source = sportDurations == null || sportDurations.isEmpty() ? DEFAULT_DURATIONS : sportDurations;
            // The map's values are read without a unit of their own: a bare 90 is 90 ms.
            source.forEach((sport, duration) ->
                    atLeastAMinute("home-control.sports.thesportsdb.sport-durations." + sport, duration));
            Map<String, Duration> normalised = new HashMap<>();
            source.forEach((sport, duration) -> normalised.put(normalise(sport), duration));
            sportDurations = Map.copyOf(normalised);
        }

        public Duration durationFor(String sport, Duration fallback) {
            return sport == null ? fallback : sportDurations.getOrDefault(normalise(sport), fallback);
        }

        static String normalise(String sport) {
            return sport.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }
    }
}
