package dev.andre.homecontrol.sources.youtube;

import jakarta.validation.constraints.Positive;
import java.time.temporal.ChronoUnit;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * {@code home-control.youtube.*}. {@code enabled=false} removes the module entirely, as with the
 * other content sources. All components used by later YouTube tasks (quota, subscriptions,
 * searches) are declared now so the config surface does not change again.
 */
@ConfigurationProperties("home-control.youtube")
@Validated
public record YouTubeProperties(@DefaultValue("true") boolean enabled,
                                @DefaultValue("https://oauth2.googleapis.com") URI oauthBaseUrl,
                                @DefaultValue("https://www.googleapis.com/youtube/v3") URI apiBaseUrl,
                                @DefaultValue("https://www.youtube.com/api/lounge") URI loungeBaseUrl,
                                @DefaultValue("https://i.ytimg.com") URI thumbnailBaseUrl,
                                @DefaultValue("5s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                Duration connectTimeout,
                                @DefaultValue("15s") @DurationUnit(ChronoUnit.SECONDS) @DurationMin(nanos = 1)
                                Duration requestTimeout,
                                @DefaultValue("10000") @Positive int dailyQuotaUnits,
                                @DefaultValue("20") @Positive int searchesPerDay,
                                @DefaultValue("30") @Positive int railSize,
                                @DefaultValue("30") @Positive int channelsPerRefresh,
                                @DefaultValue("5") @Positive int videosPerChannel,
                                @DefaultValue("24h") Duration subscriptionsRefresh,
                                @DefaultValue("20") @Positive int maxSubscriptionPages,
                                @DefaultValue("60m") Duration refreshInterval,
                                @DefaultValue("15m") Duration minRefreshSpacing,
                                @DefaultValue("6h") Duration searchCacheTtl,
                                @DefaultValue("false") boolean allowLoopback) {
}
