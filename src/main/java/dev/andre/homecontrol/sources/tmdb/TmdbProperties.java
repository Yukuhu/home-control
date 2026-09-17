package dev.andre.homecontrol.sources.tmdb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("home-control.tmdb")
public record TmdbProperties(@DefaultValue("true") boolean enabled,
                             @DefaultValue("https://api.themoviedb.org/3") URI apiBaseUrl,
                             URI imageBaseUrl,
                             @DefaultValue("5") int connectTimeoutSeconds,
                             @DefaultValue("10") int requestTimeoutSeconds,
                             @DefaultValue("20") int railSize,
                             @DefaultValue("40") int trendingCandidates,
                             @DefaultValue("24h") Duration providerCacheTtl,
                             @DefaultValue("24h") Duration configurationCacheTtl,
                             Map<String, List<Integer>> providerIds) {

    public static final Map<String, List<Integer>> DEFAULT_PROVIDER_IDS =
            Map.of("netflix", List.of(8, 1796), "primevideo", List.of(9, 119, 2100));

    public TmdbProperties {
        providerIds = providerIds == null || providerIds.isEmpty() ? DEFAULT_PROVIDER_IDS : Map.copyOf(providerIds);
        if (imageBaseUrl != null && imageBaseUrl.toString().isBlank()) {
            imageBaseUrl = null;
        }
    }
}
