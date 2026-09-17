package dev.andre.homecontrol.adapters.webos;

import dev.andre.homecontrol.adapters.net.WakeOnLan;
import dev.andre.homecontrol.core.DeviceRegistry;
import dev.andre.homecontrol.device.DeviceManager;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The LG webOS module; {@code home-control.webos.enabled=false} removes it entirely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "home-control.webos", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebOsProperties.class)
public class WebOsConfiguration {

    @Bean
    public WebOsAdapter webOsAdapter(WebOsProperties properties, SsdpDiscovery ssdp, DeviceRegistry registry,
                                     WakeOnLan wakeOnLan) {
        return new WebOsAdapter(properties, ssdp, registry, wakeOnLan);
    }

    @Bean
    public WebOsPairing webOsPairing(WebOsProperties properties, SsdpDiscovery ssdp, DeviceManager devices) {
        return new WebOsPairing(properties, ssdp, devices);
    }
}
