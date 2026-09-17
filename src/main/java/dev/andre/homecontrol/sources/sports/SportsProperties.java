package dev.andre.homecontrol.sources.sports;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
                               @DefaultValue("120m") Duration defaultEventDuration,
                               @DefaultValue Calendar calendar,
                               @DefaultValue TheSportsDb theSportsDb) {

    @Validated
    public record Calendar(@DefaultValue("6h") Duration refresh,
                           @DefaultValue("5") @Positive int connectTimeoutSeconds,
                           @DefaultValue("15") @Positive int requestTimeoutSeconds,
                           @DefaultValue("5242880") @Positive int maxBytes,
                           @DefaultValue("3") @PositiveOrZero int maxRedirects,
                           @DefaultValue("false") boolean allowLoopback) {
    }

    @Validated
    public record TheSportsDb(@DefaultValue("true") boolean enabled,
                              @DefaultValue("https://www.thesportsdb.com/api/v1/json") URI apiBaseUrl,
                              @DefaultValue("123") String freeKey,
                              @DefaultValue("24h") Duration fixturesTtl,
                              @DefaultValue("5") @Positive int connectTimeoutSeconds,
                              @DefaultValue("15") @Positive int requestTimeoutSeconds,
                              Map<String, Duration> sportDurations) {

        public static final Map<String, Duration> DEFAULT_DURATIONS = Map.of(
                "soccer", Duration.ofMinutes(120), "basketball", Duration.ofMinutes(150),
                "americanfootball", Duration.ofMinutes(210), "icehockey", Duration.ofMinutes(165),
                "handball", Duration.ofMinutes(105), "rugby", Duration.ofMinutes(120),
                "tennis", Duration.ofMinutes(180), "motorsport", Duration.ofMinutes(150),
                "darts", Duration.ofMinutes(240));

        public TheSportsDb {
            Map<String, Duration> source = sportDurations == null || sportDurations.isEmpty() ? DEFAULT_DURATIONS : sportDurations;
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
