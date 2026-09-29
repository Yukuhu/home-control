package dev.andre.homecontrol.sources.jellyfin;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.core.DeviceCommands;
import dev.andre.homecontrol.core.DeviceQueries;
import dev.andre.homecontrol.security.LoginService;
import dev.andre.homecontrol.storage.JsonFileSourceSettings;
import dev.andre.homecontrol.storage.SecretStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** The Jellyfin module. {@code home-control.jellyfin.enabled=false} removes all of it. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.JELLYFIN)
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
                                                              JellyfinClient client, JellyfinStreams streams,
                                                              DeviceQueries devices) {
        return new JellyfinPlayableResolver(setup, sessions, client, streams, devices::adapterEnabled);
    }

    @Bean
    public JellyfinVlcExecutor jellyfinVlcExecutor(JellyfinSetupService setup, JellyfinClient client,
                                                   DeviceQueries devices, DeviceCommands commands,
                                                   JellyfinProperties properties) {
        return new JellyfinVlcExecutor(setup, client, devices, commands, properties.startupTimeout());
    }

    @Bean
    public JellyfinRouteExecutor jellyfinRouteExecutor(JellyfinSessions sessions, DeviceQueries devices,
                                                       DeviceCommands commands, JellyfinProperties properties) {
        return new JellyfinRouteExecutor(sessions, devices, commands, properties.startupTimeout());
    }
}
