package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.content.SourcePreferencesService;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The TMDB module. {@code home-control.tmdb.enabled=false} removes all of it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "home-control.tmdb.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbConfiguration {

    @Bean
    public TmdbClient tmdbClient(TmdbProperties properties) {
        return new TmdbClient(properties);
    }

    @Bean
    public TmdbSetupService tmdbSetupService(TmdbClient client, JsonFileSourceSettings sources,
                                             SecretStore secrets, LoginService login) {
        return new TmdbSetupService(client, sources, secrets, login, Clock.systemUTC());
    }

    @Bean
    public TmdbImages tmdbImages(TmdbClient client, TmdbProperties properties) {
        return new TmdbImages(client, properties, Clock.systemUTC());
    }

    @Bean
    public TmdbWatchProviders tmdbWatchProviders(TmdbClient client, TmdbProperties properties) {
        return new TmdbWatchProviders(client, properties, Clock.systemUTC());
    }

    @Bean
    public TmdbContentSource tmdbContentSource(TmdbSetupService setup, TmdbClient client, TmdbImages images,
                                               TmdbWatchProviders providers, TmdbProperties properties,
                                               SourcePreferencesService preferencesService) {
        return new TmdbContentSource(setup, client, images, providers, properties, preferencesService::current);
    }
}
