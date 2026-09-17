package dev.andre.homecontrol.content;

import dev.andre.homecontrol.core.content.ContentSources;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(ContentProperties.class)
public class ContentConfiguration {

    @Bean
    public SourcePreferencesService sourcePreferencesService(JsonFileSourceSettings settings, ContentProperties properties,
                                                             ApplicationEventPublisher events) {
        return new SourcePreferencesService(settings, properties, events);
    }

    @Bean
    public StoredRailPreferences railPreferences(SourcePreferencesService preferences, ContentProperties properties) {
        return new StoredRailPreferences(preferences, properties);
    }

    @Bean
    public RailCache railCache(ContentSources sources, RailPreferences preferences,
                               ApplicationEventPublisher events, ContentProperties properties) {
        return new RailCache(sources, preferences, events, Clock.systemUTC(), properties,
                Executors.newVirtualThreadPerTaskExecutor());
    }
}
