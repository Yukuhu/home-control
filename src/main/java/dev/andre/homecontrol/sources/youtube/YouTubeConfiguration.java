package dev.andre.homecontrol.sources.youtube;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.beans.factory.annotation.Qualifier;
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
    public YouTubeSetupService youTubeSetupService(SecretStore secrets, LoginService login,
                                                   JsonFileSourceSettings sourceSettings, GoogleOAuthClient oauth,
                                                   GoogleTokens tokens, YouTubeAuthorizationService authorization) {
        return new YouTubeSetupService(secrets, login, sourceSettings, oauth, tokens, authorization);
    }
}
