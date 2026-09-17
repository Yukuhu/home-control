package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.adapters.androidtv.AndroidTvProperties;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The YouTube module. {@code home-control.youtube.enabled=false} removes all of it. Later tasks add beans here. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "home-control.youtube.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(YouTubeProperties.class)
public class YouTubeConfiguration {

    @Bean
    public Clock youtubeClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public YouTubeHttp youTubeHttp(YouTubeProperties properties) {
        return new YouTubeHttp(properties);
    }

    @Bean
    public GoogleOAuthClient googleOAuthClient(YouTubeHttp http, YouTubeProperties properties,
                                               @Qualifier("youtubeClock") Clock clock) {
        return new GoogleOAuthClient(http, properties.oauthBaseUrl(), clock);
    }

    @Bean
    public GoogleTokens googleTokens(GoogleOAuthClient oauth, SecretStore secrets, Clock clock) {
        return new GoogleTokens(oauth, secrets, clock);
    }

    @Bean(destroyMethod = "close")
    public YouTubeAuthorizationService youTubeAuthorizationService(GoogleOAuthClient oauth, SecretStore secrets,
                                                                    GoogleTokens tokens, JsonFileSourceSettings sourceSettings,
                                                                    Clock clock) {
        return new YouTubeAuthorizationService(oauth, secrets, tokens, sourceSettings, clock, true);
    }

    @Bean
    public QuotaLedger youTubeQuotaLedger(AndroidTvProperties storage, YouTubeProperties properties, Clock clock) {
        return new QuotaLedger(storage.dataDir().resolve("youtube-quota.json"), clock, properties.dailyQuotaUnits(),
                properties.searchesPerDay());
    }

    @Bean
    public YouTubeApiClient youTubeApiClient(YouTubeHttp http, YouTubeProperties properties, GoogleTokens tokens,
                                             QuotaLedger ledger) {
        return new YouTubeApiClient(http, properties.apiBaseUrl(), tokens, ledger);
    }

    @Bean
    public KnownVideos knownVideos() {
        return new KnownVideos(1000);
    }

    @Bean
    public YouTubeSetupService youTubeSetupService(SecretStore secrets, LoginService login,
                                                   JsonFileSourceSettings sourceSettings, GoogleOAuthClient oauth,
                                                   GoogleTokens tokens, YouTubeAuthorizationService authorization,
                                                   ObjectProvider<YouTubeAccount> account, QuotaLedger ledger,
                                                   ObjectProvider<YouTubeContentSource> source,
                                                   ObjectProvider<YouTubePlaylists> playlists,
                                                   ObjectProvider<DeviceManager> devices) {
        return new YouTubeSetupService(secrets, login, sourceSettings, oauth, tokens, authorization, account, ledger, source,
                playlists, devices);
    }

    @Bean
    public YouTubeAccount youTubeAccount(YouTubeApiClient api, YouTubeSetupService setup) {
        return new YouTubeAccount(api, setup);
    }

    @Bean
    public SubscriptionsFeed subscriptionsFeed(YouTubeApiClient api, YouTubeProperties properties, Clock clock) {
        return new SubscriptionsFeed(api, properties, clock);
    }

    @Bean
    public YouTubePlaylists youTubePlaylists(YouTubeApiClient api, YouTubeProperties properties, Clock clock) {
        return new YouTubePlaylists(api, properties, clock);
    }

    @Bean
    public YouTubeSearch youTubeSearch(YouTubeApiClient api, KnownVideos known, YouTubeProperties properties, Clock clock) {
        return new YouTubeSearch(api, known, properties, clock);
    }

    @Bean
    public YouTubeContentSource youTubeContentSource(YouTubeSetupService setup, SubscriptionsFeed feed, YouTubeApiClient api,
                                                     YouTubePlaylists playlists, YouTubeSearch search, QuotaLedger ledger,
                                                     KnownVideos known, YouTubeProperties properties, Clock clock) {
        return new YouTubeContentSource(setup, feed, api, playlists, search, ledger, known, properties, clock);
    }

    @Bean
    public YouTubeThumbnailController youTubeThumbnailController(YouTubeHttp http, YouTubeProperties properties) {
        return new YouTubeThumbnailController(http, properties);
    }

    @Bean
    public LoungeClient youTubeLoungeClient(YouTubeHttp http, YouTubeProperties properties) {
        return new LoungeClient(http, properties.loungeBaseUrl());
    }

    /** Needs no Google account: the Lounge switch per Cast device is all that decides. */
    @Bean
    public YouTubeLoungeResolver youTubeLoungeResolver(YouTubeSetupService setup) {
        return new YouTubeLoungeResolver(setup);
    }

    @Bean
    public YouTubeLoungeRouteExecutor youTubeLoungeRouteExecutor(DeviceManager devices, LoungeClient lounge,
                                                                 YouTubeSetupService setup) {
        return new YouTubeLoungeRouteExecutor(devices, lounge, setup);
    }

    /** After a successful device authorization, the account's own channel is looked up once and cached settings cleared. */
    @Bean
    public ApplicationRunner youTubeConnectHook(YouTubeAuthorizationService authorization, YouTubeAccount account,
                                                YouTubeContentSource source) {
        return args -> authorization.onConnected(() -> {
            source.forgetAccount();
            account.refreshChannel();
        });
    }
}
