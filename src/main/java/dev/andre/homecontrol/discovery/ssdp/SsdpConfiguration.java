package dev.andre.homecontrol.discovery.ssdp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Always present so adapters can depend on it; {@code home-control.ssdp.enabled=false} keeps it off the network. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SsdpProperties.class)
public class SsdpConfiguration {

    /** Starts itself once the application is ready; see {@link SsdpDiscovery#start}. */
    @Bean(destroyMethod = "close")
    public SsdpDiscovery ssdpDiscovery(SsdpProperties properties) {
        return new SsdpDiscovery(properties);
    }
}
