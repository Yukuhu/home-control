package dev.andre.homecontrol.adapters.upnp;

import dev.andre.homecontrol.config.ConditionalOnModule;
import dev.andre.homecontrol.config.Module;
import dev.andre.homecontrol.config.OnModuleCondition;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The UPnP renderer module. {@code home-control.upnp.enabled=false} removes discovery and the adapter. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnModule(Module.UPNP)
@EnableConfigurationProperties(UpnpProperties.class)
public class UpnpConfiguration {

    @Bean(destroyMethod = "close")
    public UpnpDiscovery upnpDiscovery(SsdpDiscovery ssdp, ApplicationEventPublisher events, Environment environment) {
        // With the Sonos module on, Sonos players are its rooms, not plain renderers. "On" is the module rule's own,
        // so a value such as "yes" cannot switch Sonos off and still hide its players here.
        return new UpnpDiscovery(ssdp, events, OnModuleCondition.isOn(environment, Module.SONOS));
    }

    @Bean
    public UpnpAdapter upnpAdapter(UpnpProperties properties, UpnpDiscovery discovery) {
        return new UpnpAdapter(properties, discovery);
    }
}
