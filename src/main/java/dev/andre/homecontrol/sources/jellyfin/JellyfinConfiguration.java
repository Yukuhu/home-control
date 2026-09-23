package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.device.DeviceManager;
import org.springframework.beans.factory.annotation.Value;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** The Jellyfin module. {@code home-control.jellyfin.enabled=false} removes all of it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "home-control.jellyfin.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JellyfinProperties.class)
public class JellyfinConfiguration {

    @Bean
    public JellyfinClient jellyfinClient(JellyfinProperties properties) {
        return new JellyfinClient(properties);
    }

    @Bean
    public JellyfinSetupService jellyfinSetupService(JellyfinClient client, JsonFileSourceSettings sources,
                                                     SecretStore secrets, LoginService login) {
        return new JellyfinSetupService(client, sources, secrets, login);
    }

    @Bean
    public JellyfinContentSource jellyfinContentSource(JellyfinClient client, JellyfinSetupService setup,
                                                        JellyfinProperties properties) {
        return new JellyfinContentSource(client, setup, properties, Clock.systemUTC());
    }

    @Bean
    public JellyfinSessions jellyfinSessions(JellyfinClient client, JellyfinSetupService setup) {
        return new JellyfinSessions(client, setup);
    }

    @Bean
    public JellyfinStreams jellyfinStreams(JellyfinClient client) {
        return new JellyfinStreams(client);
    }

    @Bean
    public JellyfinPlayableResolver jellyfinPlayableResolver(JellyfinSetupService setup, JellyfinSessions sessions,
                                                              JellyfinClient client, JellyfinStreams streams) {
        return new JellyfinPlayableResolver(setup, sessions, client, streams);
    }

    @Bean
    public JellyfinVlcExecutor jellyfinVlcExecutor(JellyfinSetupService setup, JellyfinClient client, DeviceManager devices,
            @Value("${home-control.jellyfin.startup-timeout-seconds:30}") int startupTimeoutSeconds) {
        return new JellyfinVlcExecutor(setup, client, devices, Duration.ofSeconds(startupTimeoutSeconds));
    }

    @Bean
    public JellyfinRouteExecutor jellyfinRouteExecutor(JellyfinSessions sessions, DeviceManager devices,
            @Value("${home-control.jellyfin.startup-timeout-seconds:30}") int startupTimeoutSeconds) {
        return new JellyfinRouteExecutor(sessions, devices, Duration.ofSeconds(startupTimeoutSeconds));
    }
}
