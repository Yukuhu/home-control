package dev.andre.homecontrol.adapters.sonos;

import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Sonos module. {@code home-control.sonos.enabled=false} removes it; Sonos players then show up as plain UPnP renderers. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.sonos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SonosProperties.class)
public class SonosConfiguration {

    @Bean(destroyMethod = "close")
    public SonosDiscovery sonosDiscovery(SsdpDiscovery ssdp, SonosProperties properties, ApplicationEventPublisher events) {
        return new SonosDiscovery(ssdp, properties, events);
    }

    @Bean
    public SonosAdapter sonosAdapter(SonosProperties properties, SonosDiscovery discovery) {
        return new SonosAdapter(properties, discovery);
    }
}
