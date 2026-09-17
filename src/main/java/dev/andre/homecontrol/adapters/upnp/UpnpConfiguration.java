package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The UPnP renderer module. {@code home-control.upnp.enabled=false} removes discovery and the adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.upnp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(UpnpProperties.class)
public class UpnpConfiguration {

    @Bean(destroyMethod = "close")
    public UpnpDiscovery upnpDiscovery(SsdpDiscovery ssdp, ApplicationEventPublisher events, Environment environment) {
        // With the Sonos module on, Sonos players are its rooms, not plain renderers.
        return new UpnpDiscovery(ssdp, events, environment.getProperty("home-control.sonos.enabled", Boolean.class, true));
    }

    @Bean
    public UpnpAdapter upnpAdapter(UpnpProperties properties, UpnpDiscovery discovery) {
        return new UpnpAdapter(properties, discovery);
    }
}
